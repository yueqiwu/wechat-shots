#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""zip_toolbox.py — 把 PyInstaller 产物目录打成官网可下载的 zip。

用法:
  python tools/zip_toolbox.py --version 1.0.3
产物:
  record-toolbox/dist/record-toolbox-<version>-win64.zip
  压缩包内顶层目录为「笔录工具箱/」，解压后直接双击「笔录工具箱.exe」
"""
import argparse
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(ROOT)
DIST = os.path.join(PROJECT, "record-toolbox", "dist")
FOLDER = "笔录工具箱"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--source", default=os.path.join(DIST, FOLDER))
    ap.add_argument("--output", default="")
    args = ap.parse_args()

    source = os.path.abspath(args.source)
    if not os.path.isdir(source):
        print("找不到打包目录: %s" % source)
        return 1
    output = args.output or os.path.join(DIST, "record-toolbox-%s-win64.zip" % args.version)
    output = os.path.abspath(output)
    if os.path.exists(output):
        os.remove(output)

    count = 0
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for root, dirs, files in os.walk(source):
            dirs.sort()
            for name in sorted(files):
                path = os.path.join(root, name)
                rel = os.path.relpath(path, source)
                arc = os.path.join(FOLDER, rel).replace("\\", "/")
                zf.write(path, arc)
                count += 1
    size = os.path.getsize(output)
    print("文件数: %d" % count)
    print("产物  : %s" % output)
    print("大小  : %.1f MB" % (size / 1048576.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
