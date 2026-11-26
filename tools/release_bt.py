#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
release_bt.py  —  通过宝塔面板 API 发布 APK + version.json 到 qipaclub.top

背景 / 为什么用分片接口:
  宝塔 Windows 面板 8.2.0 的 /files?action=UploadFile（multipart/form-data）在
  API Token 调用下会触发面板内部异常，返回 385 字节的「入口校验失败」页面
  （multipart 请求在鉴权链路上 500，被 error_500 钩子替换成 autherr 页面）。
  面板前端实际使用的是分片上传接口 /files?action=upload：
      POST /files?action=upload&request_time=..&request_token=..
      form: f_name=<最终文件名> f_path=<服务器目录> f_size=<总字节>
            f_start=<本片起始偏移> b64_data=<本片 base64>
  服务端把分片追加写入 <f_path>/<f_name>.<f_size>.upload.tmp，写满后改名为最终文件；
  未完成时返回当前已收字节数（纯数字），完成时返回 {"status":true,"msg":"上传成功!"}。
  该接口支持 urlencoded 表单，可被 API Token 白名单（defs 中含 'upload'）放行。
  本脚本采用 8MB 分片 + 断点续传 + 失败重试。实测 98,574,321 B APK：
  上传 716.1s（约 0.13 MB/s，取决于本机上行带宽），总耗时 733.9s。
  断点续传：重跑时会先查服务器上 <f_name>.<f_size>.upload.tmp 的已有大小并从该偏移继续；
  单片失败会自动重试（服务端按 f_start 与临时文件大小校验，不会写重复数据）。

用法:
  python tools/release_bt.py \
      --apk wechat-shots-android/dist/wechat-shots-1.1.0-release.apk \
      --version 1.1.0 --code 3 \
      --notes "新增录屏转截图；修复..." \
      --server-dir "C:/wwwroot/qipaclub.top/wechat-shots-app"

可选:
  --chunk-mb 8            分片大小（默认 8）
  --skip-upload           跳过 APK 上传（仅更新 version.json）
  --skip-version-json     只上传 APK，不改 version.json
  --keep-name             服务器上保留 APK 原文件名（默认重命名为 wechat-shots-<version>.apk）
  --dry-run               只做本地检查与 URL 验证
"""

import argparse
import base64
import hashlib
import json
import os
import random
import sys
import time
from datetime import datetime, timedelta, timezone

import requests

# ---------------------------------------------------------------------------
# 面板配置（可用环境变量覆盖）
# ---------------------------------------------------------------------------
PANEL_BASE = os.environ.get("BT_PANEL_BASE", "http://43.142.178.233:8888")
API_KEY = os.environ.get("BT_API_KEY", "aqcACIVMLcHqE6HRKG9MAE0lDv6bRu9Y")
PUBLIC_BASE = os.environ.get("BT_PUBLIC_BASE", "https://qipaclub.top/wechat-shots-app")
SERVER_DIR = os.environ.get("BT_SERVER_DIR", "C:/wwwroot/qipaclub.top/wechat-shots-app")

MAX_RETRY = 5
CHUNK_MB = 8
TZ_CN = timezone(timedelta(hours=8))


def _sig():
    """request_token = md5(request_time + md5(api_key))"""
    t = str(int(time.time()))
    tok = hashlib.md5((t + hashlib.md5(API_KEY.encode()).hexdigest()).encode()).hexdigest()
    return t, tok


def _params(action):
    t, tok = _sig()
    return {"action": action, "request_time": t, "request_token": tok}


def _retry(fn, desc, retries=MAX_RETRY):
    last = None
    for i in range(retries):
        try:
            return fn()
        except Exception as e:  # noqa: BLE001 - 网络抖动统一重试
            last = e
            wait = min(2 ** i, 15) + random.random()
            sys.stderr.write("[warn] %s 失败(%d/%d): %s，%.1fs 后重试\n" % (desc, i + 1, retries, e, wait))
            time.sleep(wait)
    raise RuntimeError("%s 重试 %d 次仍失败: %s" % (desc, retries, last))


def api_files_get(action, timeout=60, **extra):
    params = _params(action)
    params.update(extra)

    def go():
        r = requests.get(PANEL_BASE + "/files", params=params, timeout=timeout)
        r.raise_for_status()
        return r

    return _retry(go, "GET %s" % action)


def api_files_post(action, form, timeout=300):
    params = _params(action)

    def go():
        r = requests.post(PANEL_BASE + "/files", params=params, data=form, timeout=timeout)
        r.raise_for_status()
        return r

    return _retry(go, "POST %s" % action)


def api_json(resp):
    try:
        return resp.json()
    except Exception:  # noqa: BLE001
        raise RuntimeError("面板返回非 JSON: %s %s" % (resp.status_code, resp.text[:300]))


# ---------------------------------------------------------------------------
# 面板文件操作
# ---------------------------------------------------------------------------
def get_dir(path):
    return api_json(api_files_get("GetDir", path=path))


def file_size(path):
    """服务器上文件大小；不存在返回 None。"""
    path = path.replace("\\", "/")
    d = path.rsplit("/", 1)[0]
    name = path.rsplit("/", 1)[1]
    data = get_dir(d)
    for item in data.get("FILES", []) + data.get("DIR", []):
        parts = item.split(";")
        if parts and parts[0] == name:
            return int(parts[1])
    return None


def upload_tmp_size(f_path, f_name, f_size):
    """查询服务器上未完成分片的临时文件大小（用于断点续传）。失败返回 0。"""
    tmp_name = "%s.%d.upload.tmp" % (f_name, f_size)
    try:
        data = get_dir(f_path)
    except Exception as e:  # noqa: BLE001
        sys.stderr.write("[warn] 查询断点信息失败（按从 0 开始处理）: %s\n" % e)
        return 0
    for item in data.get("FILES", []):
        parts = item.split(";")
        if parts and parts[0] == tmp_name:
            try:
                return int(parts[1])
            except (ValueError, IndexError):
                return 0
    return 0


def ensure_dir(path):
    resp = api_files_post("CreateDir", {"path": path})
    j = api_json(resp)
    msg = str(j.get("msg", ""))
    if j.get("status") or "EXISTS" in msg.upper() or "已存在" in msg:
        return True
    raise RuntimeError("创建目录失败 %s: %s" % (path, j))


def delete_file(path):
    try:
        api_json(api_files_post("DeleteFile", {"path": path}))
        return True
    except Exception as e:  # noqa: BLE001
        sys.stderr.write("[warn] 删除失败 %s: %s\n" % (path, e))
        return False


def write_text_file(path, text, encoding="utf-8"):
    """CreateFile(若不存在) + SaveFileBody 写 UTF-8 文本。"""
    j = api_json(api_files_post("CreateFile", {"path": path}))
    msg = str(j.get("msg", ""))
    # 文件已存在时 CreateFile 返回 status=false（Windows 面板返回中文 msg），可继续覆盖写
    if not j.get("status") and "FILE_EXISTS" not in msg.upper() and "已存在" not in msg:
        raise RuntimeError("创建文件失败 %s: %s" % (path, j))
    body = {"path": path, "data": text, "encoding": encoding}
    r = api_files_post("SaveFileBody", body)
    j = api_json(r)
    if not j.get("status"):
        raise RuntimeError("写入文件失败 %s: %s" % (path, j))
    return True


# ---------------------------------------------------------------------------
# 分片上传
# ---------------------------------------------------------------------------
def chunk_upload(local_path, server_path, chunk_mb=CHUNK_MB):
    """分片 + 断点续传上传本地文件到服务器绝对路径。返回耗时秒。"""
    size = os.path.getsize(local_path)
    f_path = server_path.rsplit("/", 1)[0]
    f_name = server_path.rsplit("/", 1)[1]
    ensure_dir(f_path)
    chunk = max(1, chunk_mb) * 1024 * 1024
    t0 = time.time()
    # 断点续传：先看服务器上是否有同大小临时分片文件
    sent = upload_tmp_size(f_path, f_name, size)
    if sent > 0:
        if sent >= size:
            # 异常残留（大小已满但未改名），删掉重传
            delete_file("%s/%s.%d.upload.tmp" % (f_path, f_name, size))
            sent = 0
        else:
            sys.stdout.write(
                "  断点续传：服务器已有 %d/%d bytes (%.1f%%)，从该偏移继续\n"
                % (sent, size, 100.0 * sent / size)
            )
            sys.stdout.flush()
    last_pct = int(sent * 100 / size) if size else -1
    if sent == 0:
        last_pct = -1
    fails = 0

    with open(local_path, "rb") as f:
        while sent < size:
            f.seek(sent)
            part = f.read(min(chunk, size - sent))
            if not part:
                raise RuntimeError("读取本地文件异常: sent=%d size=%d" % (sent, size))
            form = {
                "f_name": f_name,
                "f_path": f_path,
                "f_size": str(size),
                "f_start": str(sent),
                "b64_data": base64.b64encode(part).decode("ascii"),
            }
            try:
                r = api_files_post("upload", form, timeout=600)
            except Exception as e:  # noqa: BLE001
                fails += 1
                if fails > MAX_RETRY:
                    raise
                sys.stderr.write("[warn] 分片上传失败(%d/%d): %s\n" % (fails, MAX_RETRY, e))
                continue
            txt = r.text.strip()
            try:
                j = json.loads(txt)
            except Exception:  # noqa: BLE001
                j = None
            if isinstance(j, dict):
                if j.get("status"):
                    sent = size
                    break
                raise RuntimeError("分片上传被拒绝: %s" % j)
            # 未完成：返回服务器已收到的字节数
            try:
                sent = int(txt)
            except Exception:  # noqa: BLE001
                raise RuntimeError("分片上传返回未知内容: %s" % txt[:300])
            fails = 0
            pct = int(sent * 100 / size)
            if pct >= last_pct + 5 or pct == 100:
                last_pct = pct
                sys.stdout.write("  上传进度 %3d%%  %d/%d bytes  %.1fs\n" % (pct, sent, size, time.time() - t0))
                sys.stdout.flush()

    # 校验大小
    got = file_size(server_path)
    if got != size:
        raise RuntimeError("上传后大小不一致: 服务器=%s 本地=%s" % (got, size))
    return time.time() - t0


# ---------------------------------------------------------------------------
# 验证
# ---------------------------------------------------------------------------
def verify_apk(url, local_size):
    r = requests.head(url, timeout=60, allow_redirects=True)
    cl = r.headers.get("Content-Length")
    ok = r.status_code == 200 and cl is not None and int(cl) == local_size
    return {"url": url, "status": r.status_code, "content_length": cl, "local_size": local_size, "ok": ok}


def verify_version_json(url, code, version):
    r = requests.get(url, timeout=60)
    j = r.json() if r.status_code == 200 else None
    ok = bool(
        r.status_code == 200
        and isinstance(j, dict)
        and j.get("versionCode") == code
        and j.get("versionName") == version
        and isinstance(j.get("apk"), str)
        and j["apk"].startswith("http")
    )
    return {"url": url, "status": r.status_code, "json": j, "ok": ok}


# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="通过宝塔 API 发布 APK 与 version.json")
    ap.add_argument("--apk", required=True, help="本地 APK 路径")
    ap.add_argument("--version", required=True, help='版本名，如 "1.1.0"')
    ap.add_argument("--code", type=int, default=None, help="versionCode（默认从 --version 推导）")
    ap.add_argument("--notes", default="", help="更新说明；支持用字面 \\n 分隔多行")
    ap.add_argument("--notes-file", default=None, help="从 UTF-8 文本文件读取更新说明（每行一条）")
    ap.add_argument("--server-dir", default=SERVER_DIR, help="服务器目录")
    ap.add_argument("--public-base", default=PUBLIC_BASE, help="公网目录 URL")
    ap.add_argument("--chunk-mb", type=int, default=CHUNK_MB, help="分片大小 MB")
    ap.add_argument("--skip-upload", action="store_true")
    ap.add_argument("--skip-version-json", action="store_true")
    ap.add_argument("--keep-name", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    apk_path = os.path.abspath(args.apk)
    if not os.path.exists(apk_path):
        sys.exit("APK 不存在: " + apk_path)
    local_size = os.path.getsize(apk_path)
    if args.code is None:
        try:
            args.code = int(args.version.split(".")[-1])
        except ValueError:
            args.code = int(time.time() // 60)
    remote_name = os.path.basename(apk_path) if args.keep_name else "wechat-shots-%s.apk" % args.version
    server_path = "%s/%s" % (args.server_dir.rstrip("/"), remote_name)
    apk_url = "%s/%s" % (args.public_base.rstrip("/"), remote_name)
    version_url = "%s/version.json" % args.public_base.rstrip("/")

    # 兼容命令行里传入字面 "\n" 的更新说明；也支持 --notes-file（每行一条）
    notes = args.notes
    if args.notes_file:
        with open(args.notes_file, encoding="utf-8") as f:
            notes = f.read()
    notes = notes.replace("\\r\\n", "\n").replace("\\n", "\n").strip()

    print("=" * 72)
    print("发布 APK : %s" % apk_path)
    print("版本     : %s (versionCode=%s)" % (args.version, args.code))
    print("服务器   : %s" % server_path)
    print("公网 URL : %s" % apk_url)
    print("本地大小 : %d bytes (%.2f MB)" % (local_size, local_size / 1048576.0))
    if notes:
        print("更新说明 : %s" % notes.replace("\n", " / "))
    print("=" * 72)

    if args.dry_run:
        vr = verify_apk(apk_url, local_size)
        vj = verify_version_json(version_url, args.code, args.version)
        print("dry-run APK 校验 :", vr)
        print("dry-run version :", vj)
        sys.exit(0 if (vr["ok"] or vj["ok"]) else 1)

    t_all = time.time()
    if not args.skip_upload:
        print("[1/3] 分片上传 APK → %s ..." % server_path)
        secs = chunk_upload(apk_path, server_path, args.chunk_mb)
        print("[1/3] 上传完成，用时 %.1f 秒 (%.2f MB/s)" % (secs, local_size / 1048576.0 / max(secs, 0.001)))
    else:
        print("[1/3] 跳过 APK 上传")

    if not args.skip_version_json:
        now = datetime.now(TZ_CN).isoformat(timespec="seconds")
        data = {
            "versionCode": args.code,
            "versionName": args.version,
            "apk": apk_url,
            "minSdk": 30,
            "notes": notes,
            "publishedAt": now,
            "size": local_size,
            "sha256": hashlib.sha256(open(apk_path, "rb").read()).hexdigest(),
        }
        vpath = "%s/version.json" % args.server_dir.rstrip("/")
        print("[2/3] 写入 version.json → %s" % vpath)
        write_text_file(vpath, json.dumps(data, ensure_ascii=False, indent=2))
    else:
        print("[2/3] 跳过 version.json")

    print("[3/3] 验证公网访问 ...")
    time.sleep(1)
    vr = verify_apk(apk_url, local_size)
    print("  APK    : HTTP %s  Content-Length=%s  本地=%s  %s" % (
        vr["status"], vr["content_length"], vr["local_size"], "OK" if vr["ok"] else "MISMATCH"))
    if args.skip_version_json:
        vj = {"skipped": True, "ok": True}
        print("  version: 已跳过")
    else:
        vj = verify_version_json(version_url, args.code, args.version)
        print("  version: HTTP %s  %s" % (vj["status"], json.dumps(vj["json"], ensure_ascii=False)[:400]))
    print("  校验结果: %s" % ("全部通过" if vr["ok"] and vj["ok"] else "存在失败项!"))
    print("总耗时 %.1f 秒" % (time.time() - t_all))

    report = {
        "apk": apk_url,
        "serverPath": server_path,
        "versionCode": args.code,
        "versionName": args.version,
        "size": local_size,
        "apkVerify": vr,
        "versionVerify": vj,
        "ok": vr["ok"] and vj["ok"],
    }
    out = os.path.join(os.path.dirname(apk_path), "release_report.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("报告已写入 %s" % out)
    sys.exit(0 if report["ok"] else 2)


if __name__ == "__main__":
    main()
