"""轻量设置存储（JSON 文件，原子写入）。"""
from __future__ import annotations

import json
import os
import tempfile
import threading

from . import paths

_lock = threading.Lock()

DEFAULTS: dict = {
    "adb_path": "",
    "output_dir": "",
    "theme": "auto",              # auto | light | dark
    "overlap_percent": 10,        # 截图重叠度目标值（实际约 10%～25%，随机型自动调整）
    "crop_status_input": True,    # 自动裁剪状态栏与输入框
    "page_height": "standard",    # short | standard | tall
    "video_analysis_fps": 10.0,   # 录屏分析的初始抽帧率
    "video_refine_rounds": 2,     # 补采轮数（低配电脑默认更省）
    "max_shots": 500,             # ADB 截图最大张数保护
    "keep_raw_shots": False,      # 是否保留原始截图
}


def settings_file() -> str:
    return os.path.join(paths.data_dir(), "settings.json")


def load() -> dict:
    with _lock:
        data = dict(DEFAULTS)
        try:
            with open(settings_file(), "r", encoding="utf-8") as f:
                stored = json.load(f)
            if isinstance(stored, dict):
                for key, value in stored.items():
                    if key in DEFAULTS:
                        data[key] = value
        except (OSError, ValueError):
            pass
        return data


def save(updates: dict) -> dict:
    merged = load()
    for key, value in (updates or {}).items():
        if key in DEFAULTS:
            merged[key] = value
    merged["overlap_percent"] = max(5, min(40, int(merged.get("overlap_percent") or 10)))
    with _lock:
        directory = os.path.dirname(settings_file())
        os.makedirs(directory, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=directory, suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(merged, f, ensure_ascii=False, indent=2)
            os.replace(tmp, settings_file())
        finally:
            if os.path.exists(tmp):
                try:
                    os.remove(tmp)
                except OSError:
                    pass
    return merged
