"""路径与运行环境工具（兼容源码运行与 PyInstaller 打包运行）。"""
from __future__ import annotations

import os
import sys

APP_NAME = "笔录工具箱"
APP_ID = "RecordToolbox"
VERSION = "1.0.2"


def is_frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


def project_root() -> str:
    """源码根目录（record-toolbox/）。"""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def resource_path(*parts: str) -> str:
    """打包后资源位于 _MEIPASS；源码运行时位于工程根目录。"""
    base = getattr(sys, "_MEIPASS", None) or project_root()
    return os.path.join(base, *parts)


def data_dir() -> str:
    """用户数据目录：%LOCALAPPDATA%\\RecordToolbox（自动创建）。"""
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    path = os.path.join(base, APP_ID)
    os.makedirs(path, exist_ok=True)
    return path


def logs_dir() -> str:
    path = os.path.join(data_dir(), "logs")
    os.makedirs(path, exist_ok=True)
    return path


def sessions_dir() -> str:
    path = os.path.join(data_dir(), "sessions")
    os.makedirs(path, exist_ok=True)
    return path


def bundled_adb_candidates() -> list[str]:
    """打包/源码环境里随附的 adb.exe 候选路径。"""
    names = ["adb/adb.exe", "platform-tools/adb.exe", "adb.exe"]
    out = []
    for name in names:
        out.append(resource_path(*name.split("/")))
        out.append(os.path.join(project_root(), *name.split("/")))
    return out


def find_adb(explicit: str | None = None) -> str | None:
    """定位 adb.exe：设置 > 随附 > PATH > 常见 Android SDK 位置。"""
    if explicit and os.path.isfile(explicit):
        return explicit
    for cand in bundled_adb_candidates():
        if os.path.isfile(cand):
            return cand
    from shutil import which

    on_path = which("adb")
    if on_path:
        return on_path
    local = os.environ.get("LOCALAPPDATA", "")
    for cand in (
        os.path.join(local, "Android", "Sdk", "platform-tools", "adb.exe"),
        r"C:\Android\platform-tools\adb.exe",
        r"C:\platform-tools\adb.exe",
    ):
        if os.path.isfile(cand):
            return cand
    return None


def ensure_dirs() -> None:
    for fn in (data_dir, logs_dir, sessions_dir):
        fn()
