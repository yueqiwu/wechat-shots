"""HTTP API 分发：前端通过 POST /api/<action> 调用，后端统一返回 JSON。"""
from __future__ import annotations

import base64
import binascii
import ctypes
import json
import logging
import os
import re
import shutil
import sys
import threading
import time
import uuid
import zipfile
from dataclasses import dataclass, field
from typing import Any

from . import adb as adb_module
from . import paths, settings

log = logging.getLogger("record_toolbox.api")

VIDEO_EXTENSIONS = [
    "*.mp4", "*.mov", "*.m4v", "*.mkv", "*.avi", "*.webm", "*.3gp", "*.flv", "*.wmv", "*.ts",
]


# --------------------------------------------------------------------------- 会话
@dataclass
class Session:
    sid: str
    kind: str
    directory: str
    created_at: float = field(default_factory=time.time)
    shots: list[dict] = field(default_factory=list)
    video: dict | None = None
    meta: dict = field(default_factory=dict)

    def shots_dir(self) -> str:
        path = os.path.join(self.directory, "shots")
        os.makedirs(path, exist_ok=True)
        return path

    def next_shot_path(self) -> tuple[str, str]:
        shot_id = uuid.uuid4().hex[:12]
        return shot_id, os.path.join(self.shots_dir(), f"{shot_id}.png")


_sessions: dict[str, Session] = {}
_sessions_lock = threading.Lock()


def create_session(kind: str) -> Session:
    cleanup_old_sessions()
    sid = uuid.uuid4().hex[:12]
    directory = os.path.join(paths.sessions_dir(), sid)
    os.makedirs(directory, exist_ok=True)
    session = Session(sid=sid, kind=kind, directory=directory)
    with _sessions_lock:
        _sessions[sid] = session
    return session


def get_session(sid: str, create: bool = False, kind: str = "misc") -> Session:
    with _sessions_lock:
        session = _sessions.get(sid)
    if session is None:
        if not create:
            raise ValueError("会话已失效，请重新开始")
        session = create_session(kind)
    return session


def drop_session(sid: str, keep_files: bool = False) -> None:
    with _sessions_lock:
        session = _sessions.pop(sid, None)
    if session and not keep_files:
        shutil.rmtree(session.directory, ignore_errors=True)


def cleanup_old_sessions(max_age_hours: float = 24.0) -> None:
    root = paths.sessions_dir()
    now = time.time()
    try:
        for name in os.listdir(root):
            full = os.path.join(root, name)
            if not os.path.isdir(full) or name in _sessions:
                continue
            try:
                if now - os.path.getmtime(full) > max_age_hours * 3600:
                    shutil.rmtree(full, ignore_errors=True)
            except OSError:
                pass
    except OSError:
        pass


# --------------------------------------------------------------------------- 系统对话框
def _open_file_dialog(title: str, filters: list[str], label: str = "文件") -> str | None:
    """Win32 打开文件对话框（不依赖 Tk，无主窗口时也稳定）。"""
    if os.name != "nt":
        return None
    from ctypes import wintypes

    class OPENFILENAMEW(ctypes.Structure):
        _fields_ = [
            ("lStructSize", wintypes.DWORD),
            ("hwndOwner", wintypes.HWND),
            ("hInstance", wintypes.HINSTANCE),
            ("lpstrFilter", wintypes.LPCWSTR),
            ("lpstrCustomFilter", wintypes.LPWSTR),
            ("nMaxCustFilter", wintypes.DWORD),
            ("nFilterIndex", wintypes.DWORD),
            ("lpstrFile", wintypes.LPWSTR),
            ("nMaxFile", wintypes.DWORD),
            ("lpstrFileTitle", wintypes.LPWSTR),
            ("nMaxFileTitle", wintypes.DWORD),
            ("lpstrInitialDir", wintypes.LPCWSTR),
            ("lpstrTitle", wintypes.LPCWSTR),
            ("Flags", wintypes.DWORD),
            ("nFileOffset", wintypes.WORD),
            ("nFileExtension", wintypes.WORD),
            ("lpstrDefExt", wintypes.LPCSTR),
            ("lCustData", wintypes.LPARAM),
            ("lpfnHook", ctypes.c_void_p),
            ("lpTemplateName", wintypes.LPCWSTR),
            ("pvReserved", ctypes.c_void_p),
            ("dwReserved", wintypes.DWORD),
            ("FlagsEx", wintypes.DWORD),
        ]

    filter_parts = [label, ";".join(filters or ["*.*"]), "所有文件", "*.*", "", ""]
    filter_string = "\0".join(filter_parts) + "\0\0"

    buffer = ctypes.create_unicode_buffer(4096)
    ofn = OPENFILENAMEW()
    ofn.lStructSize = ctypes.sizeof(OPENFILENAMEW)
    ofn.hwndOwner = None
    ofn.lpstrFilter = filter_string
    ofn.nFilterIndex = 1
    ofn.lpstrFile = ctypes.cast(buffer, wintypes.LPWSTR)
    ofn.nMaxFile = len(buffer)
    ofn.lpstrTitle = title
    ofn.Flags = 0x00080000 | 0x00001000 | 0x00000800 | 0x00000008 | 0x00000004  # EXPLORER|FILEMUSTEXIST|PATHMUSTEXIST|NOCHANGEDIR|HIDEREADONLY

    try:
        ok = ctypes.windll.comdlg32.GetOpenFileNameW(ctypes.byref(ofn))
    except Exception as exc:  # pragma: no cover - 仅 Windows 原生对话框
        log.warning("打开文件对话框失败: %s", exc)
        return None
    if not ok:
        return None
    return buffer.value or None


def _pick_folder_dialog(title: str = "选择保存文件夹") -> str | None:
    if os.name != "nt":
        return None
    from ctypes import wintypes

    class BROWSEINFOW(ctypes.Structure):
        _fields_ = [
            ("hwndOwner", wintypes.HWND),
            ("pidlRoot", ctypes.c_void_p),
            ("pszDisplayName", wintypes.LPWSTR),
            ("lpszTitle", wintypes.LPCWSTR),
            ("ulFlags", wintypes.UINT),
            ("lpfn", ctypes.c_void_p),
            ("lParam", wintypes.LPARAM),
            ("iImage", ctypes.c_int),
        ]

    display = ctypes.create_unicode_buffer(260)
    bi = BROWSEINFOW()
    bi.hwndOwner = None
    bi.pszDisplayName = ctypes.cast(display, wintypes.LPWSTR)
    bi.lpszTitle = title
    bi.ulFlags = 0x00000001 | 0x00000040 | 0x00000010  # RETURNONLYFSDIRS|NEWDIALOGSTYLE|EDITBOX
    try:
        pidl = ctypes.windll.shell32.SHBrowseForFolderW(ctypes.byref(bi))
        if not pidl:
            return None
        path_buffer = ctypes.create_unicode_buffer(4096)
        ok = ctypes.windll.shell32.SHGetPathFromIDListW(ctypes.c_void_p(pidl), path_buffer)
        ctypes.windll.ole32.CoTaskMemFree(ctypes.c_void_p(pidl))
        return path_buffer.value if ok else None
    except Exception as exc:  # pragma: no cover
        log.warning("选择文件夹失败: %s", exc)
        return None


def _sanitize_name(name: str, fallback: str = "聊天记录") -> str:
    name = (name or "").strip()
    name = re.sub(r'[\\/:*?"<>|\r\n\t]+', "_", name)
    name = name.strip(" .")
    if not name:
        name = fallback
    return name[:60]


# --------------------------------------------------------------------------- 动作实现
def _decode_pages(files: list[dict]) -> list[tuple[str, bytes]]:
    decoded: list[tuple[str, bytes]] = []
    for item in files or []:
        name = _sanitize_name(item.get("name") or "page.png", "page.png")
        if not name.lower().endswith(".png"):
            name += ".png"
        data = item.get("data") or ""
        if "," in data[:200] and data.lstrip().startswith("data:"):
            data = data.split(",", 1)[1]
        try:
            raw = base64.b64decode(data, validate=False)
        except (binascii.Error, ValueError) as exc:
            raise ValueError(f"图片数据损坏：{name}") from exc
        if not raw:
            continue
        decoded.append((name, raw))
    if not decoded:
        raise ValueError("没有可保存的截图")
    return decoded


def _compose_note(meta: dict) -> str:
    lines = [
        "笔录工具箱 · 微信聊天记录导出",
        "=" * 32,
        f"导出对象：{meta.get('contact') or '未填写'}",
        f"导出方式：{meta.get('method') or '未知'}",
        f"时间范围：{meta.get('range') or '全部记录'}",
        f"生成时间：{time.strftime('%Y-%m-%d %H:%M:%S')}",
        "",
        "说明：",
        f"1. 共 {meta.get('pageCount', 0)} 张截图，按聊天时间从旧到新编号（001、002…）。",
        "2. 相邻截图之间已自动去重：每张只包含不重复的聊天内容，上下页连续。",
        "3. 已自动裁剪状态栏与输入框，只保留聊天内容区域。",
        "4. 所有处理都在本机完成，图片不会上传到任何服务器。",
        "",
        "提示：本文件由程序自动生成，请勿用于侵犯他人隐私等用途。",
    ]
    return "\r\n".join(lines) + "\r\n"


def save_export(payload: dict, as_zip: bool) -> dict:
    meta = payload.get("meta") or {}
    page_files = _decode_pages(payload.get("files") or [])
    target_root = payload.get("dir") or ""
    if not target_root or not os.path.isdir(target_root):
        # 设置里已配置输出目录时直接用它（不再弹窗）；否则才让用户选一次
        default_dir = (settings.load().get("output_dir") or "").strip()
        if default_dir and os.path.isdir(default_dir):
            target_root = default_dir
        else:
            target_root = _pick_folder_dialog("选择保存文件夹")
        if not target_root:
            return {"ok": False, "canceled": True, "message": "已取消保存"}
    folder_name = _sanitize_name(
        meta.get("folderName") or f"{meta.get('contact') or '聊天记录'}_{time.strftime('%Y%m%d_%H%M%S')}"
    )
    out_dir = os.path.join(target_root, folder_name)
    suffix = 1
    base_dir = out_dir
    while os.path.isdir(out_dir) and os.listdir(out_dir):
        suffix += 1
        out_dir = f"{base_dir}_{suffix}"
    os.makedirs(out_dir, exist_ok=True)

    for name, raw in page_files:
        with open(os.path.join(out_dir, name), "wb") as f:
            f.write(raw)

    meta_out = dict(meta)
    meta_out["generatedAt"] = time.strftime("%Y-%m-%d %H:%M:%S")
    meta_out["pageCount"] = len(page_files)
    with open(os.path.join(out_dir, "export.json"), "w", encoding="utf-8") as f:
        json.dump(meta_out, f, ensure_ascii=False, indent=2)
    with open(os.path.join(out_dir, "说明.txt"), "w", encoding="utf-8", newline="") as f:
        f.write(_compose_note(meta_out))

    zip_path = ""
    if as_zip:
        zip_path = os.path.join(target_root, folder_name + ".zip")
        count = 1
        while os.path.exists(zip_path):
            count += 1
            zip_path = os.path.join(target_root, f"{folder_name}_{count}.zip")
        with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
            for name, _ in page_files:
                zf.write(os.path.join(out_dir, name), name)
            zf.write(os.path.join(out_dir, "export.json"), "export.json")
            zf.write(os.path.join(out_dir, "说明.txt"), "说明.txt")

    settings.save({"output_dir": target_root})
    try:
        if payload.get("noOpen"):
            pass  # 自动化测试不弹资源管理器
        elif zip_path:
            os.startfile(zip_path)  # type: ignore[attr-defined]
        else:
            os.startfile(out_dir)  # type: ignore[attr-defined]
    except OSError:
        pass
    return {
        "ok": True,
        "dir": out_dir,
        "zip": zip_path,
        "pageCount": len(page_files),
        "message": f"已保存 {len(page_files)} 张截图",
    }


def adb_action(action: str, payload: dict) -> dict:
    serial = payload.get("serial") or None
    adb_path = (settings.load().get("adb_path") or "").strip() or None
    client = adb_module.Adb(adb_path=adb_path, serial=serial)
    if action == "devices":
        return adb_module.list_devices(adb_path)
    if action == "info":
        return {"ok": True, "info": client.device_info()}
    if action == "screencap":
        kind = payload.get("kind") or "adb"
        session = get_session(payload.get("sid") or "", create=True, kind=kind)
        shot_id, shot_path = session.next_shot_path()
        started = time.time()
        data = client.screencap_png()
        with open(shot_path, "wb") as f:
            f.write(data)
        width, height = _png_size(data)
        shot = {
            "id": shot_id,
            "sid": session.sid,
            "path": shot_path,
            "width": width,
            "height": height,
            "elapsedMs": int((time.time() - started) * 1000),
        }
        session.shots.append(shot)
        return {"ok": True, "sid": session.sid, "shot": {k: shot[k] for k in ("id", "width", "height", "elapsedMs")}}
    if action == "swipe":
        client.swipe(
            int(payload["x1"]), int(payload["y1"]), int(payload["x2"]), int(payload["y2"]),
            int(payload.get("duration") or 500),
        )
        return {"ok": True}
    if action == "tap":
        client.tap(int(payload["x"]), int(payload["y"]))
        return {"ok": True}
    if action == "keyevent":
        client.keyevent(payload.get("code") or 0)
        return {"ok": True}
    if action == "wake":
        client.wake()
        return {"ok": True}
    if action == "stayoff":
        client.stop_stayon()
        return {"ok": True}
    if action == "wlan_ip":
        return {"ok": True, "ip": client.wlan_ip()}
    if action == "enable_tcpip":
        output = client.enable_tcpip(int(payload.get("port") or 5555))
        return {"ok": True, "message": output.strip(), "ip": client.wlan_ip()}
    if action == "connect":
        host = (payload.get("host") or "").strip()
        port = int(payload.get("port") or 5555)
        if not host:
            raise ValueError("请填写手机 IP 地址")
        return {"ok": True, "message": client.connect(host, port)}
    if action == "disconnect":
        client.disconnect((payload.get("host") or "").strip(), int(payload.get("port") or 5555))
        return {"ok": True}
    if action == "pair":
        host = (payload.get("host") or "").strip()
        port = int(payload.get("port") or 0)
        code = re.sub(r"\D", "", str(payload.get("code") or ""))
        if not host or not (0 < port < 65536):
            raise ValueError("请填写配对弹窗里的 IP 和端口")
        if len(code) != 6:
            raise ValueError("配对码是 6 位数字，请重新输入")
        return {"ok": True, "message": client.pair(host, port, code)}
    if action == "mdns":
        return {"ok": True, "services": client.mdns_services()}
    raise ValueError(f"未知的 ADB 操作：{action}")


def _png_size(data: bytes) -> tuple[int, int]:
    if len(data) >= 24 and data.startswith(adb_module.PNG_SIGNATURE):
        width = int.from_bytes(data[16:20], "big")
        height = int.from_bytes(data[20:24], "big")
        return width, height
    return 0, 0


# --------------------------------------------------------------------------- 分发
def dispatch(action: str, payload: dict) -> dict:
    payload = payload or {}
    if action == "state":
        cfg = settings.load()
        adb_info = adb_module.list_devices(cfg.get("adb_path") or None)
        return {
            "ok": True,
            "app": {
                "name": paths.APP_NAME,
                "version": paths.VERSION,
                "frozen": paths.is_frozen(),
                "dataDir": paths.data_dir(),
                "platform": sys.platform,
            },
            "settings": cfg,
            "adb": adb_info,
        }
    if action == "settings.save":
        return {"ok": True, "settings": settings.save(payload.get("settings") or {})}
    if action == "pick_video":
        path = _open_file_dialog("选择聊天录屏文件", VIDEO_EXTENSIONS, "视频文件")
        if not path:
            return {"ok": False, "canceled": True}
        size = os.path.getsize(path)
        session = create_session("video")
        session.video = {"path": path, "name": os.path.basename(path), "size": size}
        settings.save({"output_dir": settings.load().get("output_dir", "")})
        return {
            "ok": True,
            "sid": session.sid,
            "video": {"name": os.path.basename(path), "size": size, "path": path},
        }
    if action == "pick_file":
        # 通用选文件（设置里选择 adb.exe 等）；exts 形如 ["*.exe"]
        path = _open_file_dialog(
            payload.get("title") or "选择文件",
            payload.get("exts") or ["*.*"],
            payload.get("label") or "文件",
        )
        if not path:
            return {"ok": False, "canceled": True}
        return {"ok": True, "path": path}
    if action == "pick_folder":
        folder = _pick_folder_dialog(payload.get("title") or "选择保存文件夹")
        return {"ok": bool(folder), "path": folder or ""}
    if action == "open_folder":
        target = payload.get("path") or ""
        if target and os.path.exists(target):
            os.startfile(target)  # type: ignore[attr-defined]
            return {"ok": True}
        return {"ok": False, "message": "文件夹不存在"}
    if action == "open_url":
        url = payload.get("url") or ""
        if url.startswith(("http://", "https://")):
            os.startfile(url)  # type: ignore[attr-defined]
            return {"ok": True}
        return {"ok": False}
    if action == "session.create":
        kind = payload.get("kind") or "misc"
        session = create_session(kind)
        # 自动化测试旁路：直接指定视频文件，跳过系统选择对话框（正常用户路径仍走 pick_video）
        src_path = payload.get("path") or ""
        if src_path and os.path.isfile(src_path):
            session.video = {
                "path": src_path,
                "name": os.path.basename(src_path),
                "size": os.path.getsize(src_path),
            }
        return {"ok": True, "sid": session.sid, "video": session.video}
    if action == "session.drop":
        drop_session(payload.get("sid") or "", bool(payload.get("keepFiles")))
        return {"ok": True}
    if action == "export.save":
        result = save_export(payload, as_zip=bool(payload.get("zip")))
        if result.get("ok"):
            sid = payload.get("sid") or ""
            if sid and not settings.load().get("keep_raw_shots"):
                drop_session(sid)
        return result
    if action in ("devices", "info", "screencap", "swipe", "tap", "keyevent", "wake", "stayoff",
                  "wlan_ip", "enable_tcpip", "connect", "disconnect", "pair", "mdns"):
        return adb_action(action, payload)
    if action == "log.write":
        log.info("[frontend] %s", payload.get("message") or "")
        return {"ok": True}
    if action == "app.quit":
        threading.Timer(0.3, lambda: os._exit(0)).start()
        return {"ok": True}
    raise ValueError(f"未知操作：{action}")


def dispatch_safe(action: str, payload: dict) -> dict:
    try:
        return dispatch(action, payload)
    except adb_module.AdbError as exc:
        log.warning("ADB 操作失败 %s: %s", action, exc)
        return {"ok": False, "error": str(exc)}
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}
    except Exception as exc:  # pragma: no cover - 兜底，避免前端无反馈
        log.exception("操作异常 %s", action)
        return {"ok": False, "error": f"内部错误：{exc}"}
