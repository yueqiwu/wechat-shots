"""应用内自动更新。

流程：读取服务器上的版本清单（record-toolbox-version.json）→ 比较版本号 →
下载 zip 并校验大小/sha256 → 解压到 data_dir/update/staging →
生成 updater 批处理（等待当前进程退出 → robocopy 覆盖安装目录 → 重新启动）。

只有在 PyInstaller 打包后的 exe 里才会真正执行替换；源码运行时返回错误提示。
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile

from . import paths

log = logging.getLogger("record_toolbox.update")

DEFAULT_MANIFEST_URL = "https://qipaclub.top/wechat-shots-app/record-toolbox-version.json"

# 供前端轮询的下载/安装状态
STATE: dict = {"phase": "idle", "percent": 0, "message": ""}


def _set_state(phase: str, percent: int = 0, message: str = "") -> None:
    STATE.update(phase=phase, percent=int(percent), message=message)


def _version_tuple(version: str) -> tuple:
    parts = [int(x) for x in re.findall(r"\d+", version or "")[:4]]
    while len(parts) < 4:
        parts.append(0)
    return tuple(parts)


def _manifest_url(manifest_url: str | None = None) -> str:
    return manifest_url or os.environ.get("RTB_MANIFEST_URL") or DEFAULT_MANIFEST_URL


def _fetch_json(url: str, timeout: float = 8.0) -> dict:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": f"RecordToolbox/{paths.VERSION}", "Cache-Control": "no-cache"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def check(manifest_url: str | None = None, timeout: float = 8.0) -> dict:
    """检查是否有新版本。网络失败时返回 ok=False（前端静默处理）。"""
    url = _manifest_url(manifest_url)
    try:
        info = _fetch_json(url, timeout)
    except Exception as exc:  # noqa: BLE001
        log.info("检查更新失败：%s", exc)
        return {"ok": False, "error": "检查更新失败，请稍后再试", "detail": str(exc)}
    latest = str(info.get("version") or "").strip()
    if not latest:
        return {"ok": False, "error": "版本清单格式不正确"}
    has_update = _version_tuple(latest) > _version_tuple(paths.VERSION)
    return {
        "ok": True,
        "currentVersion": paths.VERSION,
        "hasUpdate": has_update,
        "latest": {
            "version": latest,
            "notes": info.get("notes") or [],
            "size": int(info.get("size") or 0),
            "sha256": str(info.get("sha256") or "").lower(),
            "zip": str(info.get("zip") or ""),
            "publishedAt": str(info.get("publishedAt") or ""),
        },
    }


def _download(url: str, target: str, expected_size: int = 0, expected_sha256: str = "") -> None:
    _set_state("download", 0, "正在下载新版本…")
    request = urllib.request.Request(
        url, headers={"User-Agent": f"RecordToolbox/{paths.VERSION}"}
    )
    digest = hashlib.sha256()
    got = 0
    with urllib.request.urlopen(request, timeout=60) as response, open(target, "wb") as fh:
        total = int(response.headers.get("Content-Length") or expected_size or 0)
        while True:
            chunk = response.read(512 * 1024)
            if not chunk:
                break
            fh.write(chunk)
            digest.update(chunk)
            got += len(chunk)
            if total:
                _set_state("download", min(99, int(got * 100 / total)), "正在下载新版本…")
    if expected_size and got != expected_size:
        raise RuntimeError(f"下载大小不符（{got}/{expected_size}），请稍后重试")
    if expected_sha256 and digest.hexdigest() != expected_sha256:
        raise RuntimeError("新版本文件校验失败（sha256 不一致），请稍后重试")


def _install_root() -> str:
    """当前安装目录（exe 所在目录）；源码运行返回空字符串。"""
    if not paths.is_frozen():
        return ""
    return os.path.dirname(os.path.abspath(sys.executable))


def _extract_staging(zip_path: str, dest: str) -> str:
    if os.path.isdir(dest):
        shutil.rmtree(dest, ignore_errors=True)
    os.makedirs(dest, exist_ok=True)
    _set_state("extract", 100, "正在解压新版本…")
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(dest)
    exe_name = os.path.basename(sys.executable)
    # zip 顶层通常是「笔录工具箱/」目录，找出真正包含主程序的目录
    subdirs = []
    for entry in os.listdir(dest):
        candidate = os.path.join(dest, entry)
        if os.path.isdir(candidate):
            if os.path.isfile(os.path.join(candidate, exe_name)):
                return candidate
            subdirs.append(candidate)
    if os.path.isfile(os.path.join(dest, exe_name)):
        return dest
    # 兼容主程序名与当前进程名不一致的情况（测试 / 改名）：找顶层目录里的 .exe
    for candidate in subdirs + [dest]:
        try:
            names = os.listdir(candidate)
        except OSError:
            continue
        for name in names:
            if name.lower().endswith(".exe") and os.path.isfile(os.path.join(candidate, name)):
                return candidate
    raise RuntimeError("新版本包里没有找到主程序")


def _write_updater(staging_root: str, install_root: str, pid: int) -> str:
    """生成 PowerShell 更新脚本（UTF-8 BOM，中文路径不依赖控制台代码页）。"""
    update_dir = os.path.join(paths.data_dir(), "update")
    os.makedirs(update_dir, exist_ok=True)
    script_path = os.path.join(update_dir, "apply-update.ps1")
    launcher = os.path.join(install_root, os.path.basename(sys.executable))

    def quote(text: str) -> str:
        return text.replace("'", "''")

    script = f"""# RecordToolbox auto-update script (generated by the app, safe to delete)
$ErrorActionPreference = 'Stop'
$src = '{quote(staging_root)}'
$dst = '{quote(install_root)}'
$exe = '{quote(launcher)}'
$log = Join-Path (Split-Path -Parent $PSCommandPath) 'update.log'

function Write-UpdateLog([string]$message) {{
  # 逐阶段记录，方便排查（用 -Append，避免覆盖上一次结果）
  "$(Get-Date -Format s) $message" | Out-File -FilePath $log -Encoding utf8 -Append
}}

Write-UpdateLog "start pid={pid} src=$src dst=$dst exe=$exe"
Start-Sleep -Seconds 2
Write-UpdateLog "waiting for old process to exit"
$tries = 0
while ($true) {{
  $proc = Get-Process -Id {pid} -ErrorAction SilentlyContinue
  if (-not $proc) {{ break }}
  $tries++
  if ($tries -gt 150) {{ Write-UpdateLog "old process still alive after 120s, giving up"; exit 1 }}
  Start-Sleep -Milliseconds 800
}}
Write-UpdateLog "old process exited, copying files"
Start-Sleep -Milliseconds 600
try {{
  robocopy $src $dst /E /R:3 /W:1 /NFL /NDL /NJH /NJS | Out-Null
  if ($LASTEXITCODE -ge 8) {{ throw "robocopy exit code $LASTEXITCODE" }}
  Write-UpdateLog "copy done, restarting exe"
  Start-Process -FilePath $exe -WorkingDirectory $dst
  "ok $(Get-Date -Format s)" | Out-File -FilePath $log -Encoding utf8 -Append
}} catch {{
  Write-UpdateLog ("update failed: " + $_)
  exit 1
}}
"""
    with open(script_path, "w", encoding="utf-8-sig", newline="\r\n") as fh:
        fh.write(script)
    return script_path


def apply(manifest_url: str | None = None) -> dict:
    """下载并安装更新，然后重启应用（当前进程会退出）。"""
    install_root = _install_root()
    if not install_root:
        return {"ok": False, "error": "开发模式（源码运行）不支持自动更新，请使用打包后的版本"}
    if STATE.get("phase") in {"download", "extract", "apply"}:
        return {"ok": False, "error": "正在更新中，请稍候"}

    checked = check(manifest_url)
    if not checked.get("ok"):
        return {"ok": False, "error": checked.get("error") or "检查更新失败"}
    if not checked.get("hasUpdate"):
        return {"ok": True, "upToDate": True, "currentVersion": paths.VERSION}

    latest = checked["latest"]
    zip_url = latest.get("zip") or ""
    if not zip_url.startswith(("http://", "https://")):
        return {"ok": False, "error": "版本清单里的下载地址无效"}
    update_dir = os.path.join(paths.data_dir(), "update")
    os.makedirs(update_dir, exist_ok=True)
    zip_path = os.path.join(update_dir, f"toolbox-{latest['version']}.zip")
    try:
        _set_state("download", 0, "正在下载新版本…")
        _download(zip_url, zip_path, latest.get("size") or 0, latest.get("sha256") or "")
        staging = _extract_staging(zip_path, os.path.join(update_dir, "staging"))
        _set_state("apply", 100, "正在准备重启并完成更新…")
        script_path = _write_updater(staging, install_root, os.getpid())
        # 注意：不要用 DETACHED_PROCESS——实测 powershell.exe 在无控制台模式下
        # 会直接退出、根本不执行 -File 脚本（自动更新静默失效）。CREATE_NO_WINDOW
        # 既能让控制台程序隐藏运行，又能在主进程退出后继续执行。
        flags = 0
        if os.name == "nt":
            flags = 0x08000000  # CREATE_NO_WINDOW
        subprocess.Popen(  # noqa: S603
            [
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script_path,
            ],
            cwd=update_dir,
            creationflags=flags,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            close_fds=True,
        )
        log.info("已启动更新脚本 %s，当前进程即将退出", script_path)
        threading.Timer(1.5, lambda: os._exit(0)).start()
        return {"ok": True, "restarting": True, "version": latest["version"]}
    except Exception as exc:  # noqa: BLE001
        log.warning("自动更新失败：%s", exc)
        _set_state("error", 0, str(exc))
        return {"ok": False, "error": f"更新失败：{exc}"}
