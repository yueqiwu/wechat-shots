#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""publish_toolbox.py — 发布「笔录工具箱」Windows 版本。

做三件事：
  1. 上传 zip 到宝塔服务器（wwwroot/wechat-shots-app/record-toolbox-<version>-win64.zip）
  2. 写入版本清单 record-toolbox-version.json（应用内自动更新读取）
  3. 验证公网 zip 与清单都可访问

用法:
  python tools/publish_toolbox.py --zip record-toolbox/dist/record-toolbox-1.0.3-win64.zip \
      --version 1.0.3 --notes-file tools/release_notes_1.0.3.txt
"""
import argparse
import hashlib
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import release_bt as bt  # noqa: E402

MANIFEST_NAME = "record-toolbox-version.json"


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--notes-file", default="")
    ap.add_argument("--notes", default="")
    ap.add_argument("--skip-upload", action="store_true")
    ap.add_argument("--skip-manifest", action="store_true")
    args = ap.parse_args()

    local = os.path.abspath(args.zip)
    if not os.path.isfile(local):
        print("找不到压缩包: %s" % local)
        return 1
    version = args.version.strip()
    name = "record-toolbox-%s-win64.zip" % version
    size = os.path.getsize(local)
    digest = sha256_of(local)
    notes = []
    if args.notes_file and os.path.isfile(args.notes_file):
        with open(args.notes_file, encoding="utf-8") as fh:
            notes = [line.strip() for line in fh if line.strip()]
    elif args.notes:
        notes = [line.strip() for line in args.notes.split("|") if line.strip()]

    server_dir = bt.SERVER_DIR.rstrip("/")
    server_zip = "%s/%s" % (server_dir, name)
    url_zip = "%s/%s" % (bt.PUBLIC_BASE.rstrip("/"), name)
    url_manifest = "%s/%s" % (bt.PUBLIC_BASE.rstrip("/"), MANIFEST_NAME)

    print("=" * 72)
    print("版本       : %s" % version)
    print("本地 zip   : %s (%.1f MB)" % (local, size / 1048576.0))
    print("sha256     : %s" % digest)
    print("公网 zip   : %s" % url_zip)
    print("版本清单   : %s" % url_manifest)
    print("=" * 72)

    if not args.skip_upload:
        print("[1/3] 上传压缩包 ...")
        bt.chunk_upload(local, server_zip)
    else:
        print("[1/3] 跳过上传")

    manifest = {
        "version": version,
        "zip": url_zip,
        "size": size,
        "sha256": digest,
        "notes": notes,
        "publishedAt": time.strftime("%Y-%m-%dT%H:%M:%S+08:00"),
    }
    if not args.skip_manifest:
        print("[2/3] 写入版本清单 ...")
        bt.write_text_file("%s/%s" % (server_dir, MANIFEST_NAME), json.dumps(manifest, ensure_ascii=False, indent=2))
    else:
        print("[2/3] 跳过清单")

    print("[3/3] 验证公网 ...")
    ok = True
    with urllib.request.urlopen(urllib.request.Request(url_zip, method="GET"), timeout=180) as resp:
        downloaded = resp.read()
    zip_ok = len(downloaded) == size
    ok = ok and zip_ok
    print("  zip HTTP %s，下载 %d / 本地 %d -> %s" % (resp.status, len(downloaded), size, "OK" if zip_ok else "MISMATCH"))

    with urllib.request.urlopen(urllib.request.Request(url_manifest, method="GET"), timeout=60) as resp:
        remote_manifest = json.loads(resp.read().decode("utf-8"))
    manifest_ok = remote_manifest.get("version") == version and remote_manifest.get("sha256") == digest
    ok = ok and manifest_ok
    print("  清单 HTTP %s，version=%s -> %s" % (
        resp.status, remote_manifest.get("version"), "OK" if manifest_ok else "MISMATCH"))
    print("发布%s" % ("成功" if ok else "失败"))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
