#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
deploy_site_bt.py  —  把 website/ 静态站点部署到 qipaclub.top/wechat-shots-app/

原理:
  复用 release_bt.py 的宝塔 API 封装（签名 / 重试 / 文件操作）。
  对每个文本文件执行 CreateFile + SaveFileBody（UTF-8），即可写入服务器目录，
  无需 multipart 上传（面板 8.2.0 API Token 下 UploadFile 会返回「入口校验失败」）。

默认:
  - 部署 website/ 下所有文件（index.html / styles.css / app.js / favicon.svg ...）
  - 跳过 version.json（由 tools/release_bt.py 在发布时写入，避免覆盖真实版本信息）
    需要强制部署时加 --with-version-json
  - 清理之前探测遗留的垃圾文件（--keep-junk 可保留）

用法:
  python tools/deploy_site_bt.py
  python tools/deploy_site_bt.py --with-version-json --verify-only
"""

import argparse
import os
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

import release_bt as bt  # noqa: E402

DEFAULT_WEBSITE = os.path.join(os.path.dirname(ROOT), "website")
PUBLIC_URL = bt.PUBLIC_BASE + "/"

# 之前探测上传接口时留在服务器上的垃圾文件/目录
JUNK_FILES = [
    "_probe2.txt",
    "_probe_big.txt",
    "_probe_newline.txt",
    "_up_b.txt",
    "_up_c.txt",
    "_chunktest.bin",
    "_api_probe.txt",
]
JUNK_DIRS = ["_tmp_dir"]

SKIP_NAMES = {"version.json"}
TEXT_EXT = {".html", ".css", ".js", ".json", ".svg", ".txt", ".xml", ".webmanifest", ".map"}


def iter_site_files(website_dir, with_version_json):
    for name in sorted(os.listdir(website_dir)):
        p = os.path.join(website_dir, name)
        if not os.path.isfile(p):
            continue
        if name in SKIP_NAMES and not with_version_json:
            print("  [skip] %s（发布时由 release_bt.py 写入）" % name)
            continue
        ext = os.path.splitext(name)[1].lower()
        if ext not in TEXT_EXT:
            print("  [skip] %s（非文本文件，需另行处理）" % name)
            continue
        yield name, p


def clean_junk(server_dir, keep=False):
    if keep:
        print("[clean] 跳过垃圾清理")
        return
    print("[clean] 清理探测遗留文件 ...")
    for name in JUNK_FILES:
        path = "%s/%s" % (server_dir, name)
        try:
            if bt.file_size(path) is not None:
                bt.delete_file(path)
                print("  删除 %s" % path)
        except Exception as e:  # noqa: BLE001
            print("  [warn] 清理 %s 失败: %s" % (path, e))
    for name in JUNK_DIRS:
        path = "%s/%s" % (server_dir, name)
        try:
            j = bt.api_json(bt.api_files_post("DeleteDir", {"path": path}))
            print("  删除目录 %s → %s" % (path, j.get("msg", j)))
        except Exception as e:  # noqa: BLE001
            print("  [info] 目录 %s 未清理（可能不存在）: %s" % (path, e))


def deploy(website_dir, server_dir, with_version_json):
    files = list(iter_site_files(website_dir, with_version_json))
    if not files:
        sys.exit("website 目录没有可部署文件: " + website_dir)
    print("[deploy] %d 个文件 → %s" % (len(files), server_dir))
    total = time.time()
    for name, path in files:
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
        remote = "%s/%s" % (server_dir, name)
        t0 = time.time()
        bt.write_text_file(remote, text)
        print("  ✓ %-16s %6d bytes  %.2fs" % (name, len(text.encode("utf-8")), time.time() - t0))
    print("[deploy] 完成，用时 %.1f 秒" % (time.time() - total))


def verify(public_url, with_version_json):
    import requests

    print("[verify] 公网验证 ...")
    checks = []
    r = requests.get(public_url, timeout=60, headers={"Cache-Control": "no-cache"})
    # 宝塔静态服务不返回 charset，requests 会退回 ISO-8859-1；页面本身是 UTF-8，显式按字节解码
    body = r.content.decode("utf-8", errors="replace")
    markers = ["聊天截图导出", "无线 ADB", "录屏", "wechat-shots-"]
    ok = r.status_code == 200 and all(m in body for m in markers)
    checks.append(("首页 " + public_url, r.status_code, len(body), ok, ", ".join(m for m in markers if m in body)))
    print("  首页          : HTTP %s  %d bytes  %s" % (r.status_code, len(body), "OK" if ok else "内容缺失!"))

    r2 = requests.get(public_url + "styles.css", timeout=60)
    css = r2.content.decode("utf-8", errors="replace")
    ok2 = r2.status_code == 200 and "--accent" in css and len(css) > 2000
    checks.append(("styles.css", r2.status_code, len(css), ok2, ""))
    print("  styles.css    : HTTP %s  %d bytes  %s" % (r2.status_code, len(css), "OK" if ok2 else "异常"))

    r3 = requests.get(public_url + "app.js", timeout=60)
    js = r3.content.decode("utf-8", errors="replace")
    ok3 = r3.status_code == 200 and "version.json" in js
    checks.append(("app.js", r3.status_code, len(js), ok3, ""))
    print("  app.js        : HTTP %s  %d bytes  %s" % (r3.status_code, len(js), "OK" if ok3 else "异常"))

    r4 = requests.get(public_url + "favicon.svg", timeout=60)
    svg = r4.content.decode("utf-8", errors="replace")
    ok4 = r4.status_code == 200 and "<svg" in svg
    checks.append(("favicon.svg", r4.status_code, len(svg), ok4, ""))
    print("  favicon.svg   : HTTP %s  %d bytes  %s" % (r4.status_code, len(svg), "OK" if ok4 else "异常"))

    if with_version_json:
        r5 = requests.get(public_url + "version.json", timeout=60)
        try:
            j = r5.json()
            ok5 = r5.status_code == 200 and isinstance(j.get("versionCode"), int)
        except Exception:  # noqa: BLE001
            ok5 = False
        checks.append(("version.json", r5.status_code, len(r5.content), ok5, ""))
        print("  version.json  : HTTP %s  %s" % (r5.status_code, json_short(r5)))

    return [c for c in checks if c[3]]


def json_short(resp):
    import json

    try:
        return json.dumps(resp.json(), ensure_ascii=False)[:200]
    except Exception:  # noqa: BLE001
        return resp.text[:200]


def main():
    ap = argparse.ArgumentParser(description="部署官网静态文件到宝塔服务器")
    ap.add_argument("--website-dir", default=DEFAULT_WEBSITE)
    ap.add_argument("--server-dir", default=bt.SERVER_DIR)
    ap.add_argument("--with-version-json", action="store_true")
    ap.add_argument("--keep-junk", action="store_true")
    ap.add_argument("--verify-only", action="store_true")
    ap.add_argument("--skip-verify", action="store_true")
    args = ap.parse_args()

    server_dir = args.server_dir.replace("\\", "/").rstrip("/")
    print("=" * 72)
    print("官网部署 : %s" % args.website_dir)
    print("服务器   : %s" % server_dir)
    print("公网     : %s" % PUBLIC_URL)
    print("=" * 72)

    t0 = time.time()
    if not args.verify_only:
        bt.ensure_dir(server_dir)
        clean_junk(server_dir, args.keep_junk)
        deploy(args.website_dir, server_dir, args.with_version_json)
    if not args.skip_verify:
        time.sleep(1)
        checks = verify(PUBLIC_URL, args.with_version_json)
        expected = 5 if args.with_version_json else 4
        all_ok = len(checks) == expected
        print("[verify] 全部通过" if all_ok else "[verify] 存在失败项!")
        print("总耗时 %.1f 秒" % (time.time() - t0))
        sys.exit(0 if all_ok else 2)
    print("总耗时 %.1f 秒" % (time.time() - t0))


if __name__ == "__main__":
    main()
