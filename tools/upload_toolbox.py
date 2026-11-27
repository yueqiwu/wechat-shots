#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""upload_toolbox.py — 把「笔录工具箱」Windows 版压缩包上传到宝塔服务器并验证公网下载。

用法:
  python tools/upload_toolbox.py
  python tools/upload_toolbox.py --zip record-toolbox/dist/record-toolbox-1.0.1-win64.zip
"""

import argparse
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import release_bt as bt  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", default=os.path.join("record-toolbox", "dist", "record-toolbox-1.0.1-win64.zip"))
    ap.add_argument("--name", default="record-toolbox-1.0.1-win64.zip")
    ap.add_argument("--skip-upload", action="store_true")
    args = ap.parse_args()

    local = os.path.abspath(args.zip)
    if not os.path.isfile(local):
        print("找不到压缩包: %s" % local)
        return 1
    size = os.path.getsize(local)
    server_path = "%s/%s" % (bt.SERVER_DIR.rstrip("/"), args.name)
    url = "%s/%s" % (bt.PUBLIC_BASE.rstrip("/"), args.name)

    print("=" * 72)
    print("Windows 包 : %s" % local)
    print("服务器     : %s" % server_path)
    print("公网 URL   : %s" % url)
    print("本地大小   : %d bytes (%.1f MB)" % (size, size / 1048576.0))
    print("=" * 72)

    if not args.skip_upload:
        bt.chunk_upload(local, server_path)

    print("[验证] 公网下载 ...")
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = resp.read()
    ok = len(data) == size
    print("  HTTP %s  下载大小=%d  本地=%d  %s" % (resp.status, len(data), size, "OK" if ok else "MISMATCH"))
    print("  大小: %.1f MB" % (len(data) / 1048576.0))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
