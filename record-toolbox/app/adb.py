"""ADB 封装：设备发现、截图、滑动、无线连接与配对。

设计要点（Windows 低配电脑）：
- 所有命令带超时与 CREATE_NO_WINDOW，避免黑框闪烁与卡死；
- 截图走 `exec-out screencap -p` 单次往返，直接拿到 PNG 字节，不再 adb pull 落盘；
- 错误统一转成中文，便于前端直接展示。
"""
from __future__ import annotations

import os
import re
import subprocess
import time
from dataclasses import dataclass
from typing import Iterable

from . import paths

NO_WINDOW = 0x08000000 if os.name == "nt" else 0
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class AdbError(RuntimeError):
    """ADB 相关错误，消息可直接展示给用户。"""


@dataclass
class Device:
    serial: str
    state: str
    model: str = ""
    product: str = ""
    device: str = ""
    transport_id: str = ""
    wireless: bool = False

    def to_dict(self) -> dict:
        return {
            "serial": self.serial,
            "state": self.state,
            "model": self.model,
            "product": self.product,
            "device": self.device,
            "transportId": self.transport_id,
            "wireless": self.wireless,
            "online": self.state == "device",
        }


def _no_window_kwargs() -> dict:
    if os.name == "nt":
        return {"creationflags": NO_WINDOW}
    return {}


class Adb:
    def __init__(self, adb_path: str | None = None, serial: str | None = None) -> None:
        self.adb_path = adb_path or paths.find_adb()
        self.serial = serial or ""

    # ------------------------------------------------------------------ 基础
    def require(self) -> str:
        if not self.adb_path or not os.path.isfile(self.adb_path):
            raise AdbError(
                "没有找到 adb.exe。请把 platform-tools 放到程序目录下的 adb\\ 文件夹，"
                "或在「设置」中手动指定 adb.exe 路径。"
            )
        return self.adb_path

    def run(
        self,
        args: Iterable[str],
        *,
        timeout: float = 20.0,
        serial: str | None = None,
        binary: bool = False,
    ) -> subprocess.CompletedProcess:
        cmd = [self.require(), *args]
        serial = serial if serial is not None else self.serial
        if serial and args and args[0] not in ("start-server", "kill-server", "devices", "mdns"):
            cmd = [cmd[0], "-s", serial, *cmd[1:]]
        try:
            return subprocess.run(
                cmd,
                capture_output=True,
                timeout=timeout,
                **_no_window_kwargs(),
            )
        except subprocess.TimeoutExpired as exc:
            raise AdbError(f"ADB 命令超时（{int(timeout)} 秒）：{' '.join(args)}") from exc
        except OSError as exc:
            raise AdbError(f"无法运行 adb.exe：{exc}") from exc

    def text(self, args: Iterable[str], *, timeout: float = 20.0, serial: str | None = None) -> str:
        proc = self.run(args, timeout=timeout, serial=serial)
        out = proc.stdout.decode("utf-8", "replace")
        err = proc.stderr.decode("utf-8", "replace")
        if proc.returncode != 0:
            message = (err or out).strip() or "ADB 命令执行失败"
            raise AdbError(message)
        return out

    def ensure_server(self) -> None:
        try:
            self.run(["start-server"], timeout=15)
        except AdbError:
            pass

    def version(self) -> str:
        try:
            out = self.text(["version"], timeout=10)
        except AdbError:
            return ""
        return out.strip().splitlines()[0] if out.strip() else ""

    # ------------------------------------------------------------------ 设备
    def devices(self) -> list[Device]:
        self.ensure_server()
        try:
            out = self.text(["devices", "-l"], timeout=15)
        except AdbError:
            return []
        result: list[Device] = []
        for line in out.splitlines()[1:]:
            line = line.strip()
            if not line or line.startswith("*"):
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            device = Device(serial=parts[0], state=parts[1])
            device.wireless = ":" in device.serial
            for item in parts[2:]:
                if ":" not in item:
                    continue
                key, _, value = item.partition(":")
                if key == "model":
                    device.model = value.replace("_", " ")
                elif key == "product":
                    device.product = value
                elif key == "device":
                    device.device = value
                elif key == "transport_id":
                    device.transport_id = value
            result.append(device)
        return result

    def first_online(self) -> Device:
        for device in self.devices():
            if device.state == "device":
                return device
        raise AdbError(
            "没有检测到已授权的手机。请用数据线连接手机、打开「USB 调试」，"
            "并在手机上点「允许 USB 调试」。"
        )

    # -------------------------------------------------------------- 设备信息
    def screen_size(self, serial: str | None = None) -> tuple[int, int]:
        out = self.text(["shell", "wm", "size"], serial=serial)
        matches = re.findall(r"(\d+)x(\d+)", out)
        if not matches:
            raise AdbError("无法获取手机屏幕尺寸")
        width, height = max(matches, key=lambda m: int(m[0]) * int(m[1]))
        return int(width), int(height)

    def density(self, serial: str | None = None) -> int:
        try:
            out = self.text(["shell", "wm", "density"], serial=serial)
            match = re.search(r"(\d+)", out)
            return int(match.group(1)) if match else 420
        except AdbError:
            return 420

    def android_version(self, serial: str | None = None) -> str:
        try:
            return self.text(["shell", "getprop", "ro.build.version.release"], serial=serial).strip()
        except AdbError:
            return ""

    def top_activity(self, serial: str | None = None) -> str:
        try:
            out = self.text(["shell", "dumpsys", "window"], serial=serial, timeout=20)
        except AdbError:
            return ""
        match = re.search(r"mCurrentFocus=Window\{[^}]*? ([^/\s}]+/[A-Za-z0-9_.$]+)", out)
        if match:
            return match.group(1)
        match = re.search(r"mFocusedApp=.*? ([^/\s}]+/[A-Za-z0-9_.$]+)", out)
        return match.group(1) if match else ""

    def device_info(self, serial: str | None = None) -> dict:
        width, height = self.screen_size(serial)
        return {
            "width": width,
            "height": height,
            "density": self.density(serial),
            "android": self.android_version(serial),
            "topActivity": self.top_activity(serial),
        }

    # -------------------------------------------------------------- 截图/操作
    def screencap_png(self, serial: str | None = None, retries: int = 2) -> bytes:
        last_error = ""
        for attempt in range(retries + 1):
            proc = self.run(
                ["exec-out", "screencap", "-p"],
                timeout=30,
                serial=serial,
                binary=True,
            )
            data = proc.stdout or b""
            if data.startswith(PNG_SIGNATURE) and len(data) > 1024:
                return data
            last_error = (proc.stderr or b"").decode("utf-8", "replace").strip()
            time.sleep(0.35 * (attempt + 1))
        raise AdbError(
            "手机截图失败" + (f"（{last_error}）" if last_error else "")
            + "。请确认手机未锁屏、微信在前台，并保持「USB 调试」授权。"
        )

    def swipe(
        self,
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        duration_ms: int = 500,
        serial: str | None = None,
    ) -> None:
        self.run(
            [
                "shell",
                "input",
                "swipe",
                str(int(x1)),
                str(int(y1)),
                str(int(x2)),
                str(int(y2)),
                str(int(duration_ms)),
            ],
            timeout=20,
            serial=serial,
        )

    def tap(self, x: int, y: int, serial: str | None = None) -> None:
        self.run(["shell", "input", "tap", str(int(x)), str(int(y))], timeout=20, serial=serial)

    def keyevent(self, code: int | str, serial: str | None = None) -> None:
        self.run(["shell", "input", "keyevent", str(code)], timeout=20, serial=serial)

    def wake(self, serial: str | None = None) -> None:
        """点亮并保持唤醒（截图期间避免息屏）。"""
        try:
            self.run(["shell", "input", "keyevent", "224"], timeout=10, serial=serial)
            self.run(["shell", "svc", "power", "stayon", "true"], timeout=10, serial=serial)
        except AdbError:
            pass

    def stop_stayon(self, serial: str | None = None) -> None:
        try:
            self.run(["shell", "svc", "power", "stayon", "false"], timeout=10, serial=serial)
        except AdbError:
            pass

    # -------------------------------------------------------------- 无线连接
    def wlan_ip(self, serial: str | None = None) -> str:
        candidates: list[str] = []
        for args in (
            ["shell", "ip", "-4", "addr", "show", "wlan0"],
            ["shell", "ip", "-4", "addr", "show", "wlan1"],
            ["shell", "ip", "-4", "route"],
        ):
            try:
                out = self.text(args, serial=serial, timeout=15)
            except AdbError:
                continue
            for match in re.finditer(r"inet\s+(\d{1,3}(?:\.\d{1,3}){3})", out):
                ip = match.group(1)
                if not ip.startswith("127.") and ip not in candidates:
                    candidates.append(ip)
        for ip in candidates:
            if ip.startswith(("192.168.", "10.", "172.")):
                return ip
        return candidates[0] if candidates else ""

    def enable_tcpip(self, port: int = 5555, serial: str | None = None) -> str:
        out = self.text(["tcpip", str(port)], serial=serial, timeout=30)
        for _ in range(20):
            if "restarting" in out or "restarted" in out:
                break
            time.sleep(0.3)
        # tcpip 重启 adbd 后设备会短暂掉线，稍等再探测
        for _ in range(30):
            devices = self.devices()
            if any(device.state == "device" for device in devices):
                break
            time.sleep(0.5)
        return out.strip()

    def connect(self, host: str, port: int = 5555) -> str:
        out = self.text(["connect", f"{host}:{port}"], timeout=30)
        text = out.strip()
        if "cannot" in text or "failed" in text or "unable" in text:
            raise AdbError(
                f"无线连接失败：{text}。请确认手机与电脑在同一 Wi-Fi，且无线调试或 5555 端口已开启。"
            )
        return text

    def disconnect(self, host: str, port: int = 5555) -> None:
        try:
            self.text(["disconnect", f"{host}:{port}"], timeout=15)
        except AdbError:
            pass

    def pair(self, host: str, port: int, code: str) -> str:
        out = self.text(["pair", f"{host}:{port}", code], timeout=45)
        text = out.strip()
        if "Successfully paired" not in text and "successfully paired" not in text.lower():
            raise AdbError(f"配对失败：{text or '没有返回结果'}。请确认配对码与端口来自同一个「与设备配对」弹窗。")
        return text

    def mdns_services(self) -> list[dict]:
        """`adb mdns services` 输出解析，用于自动发现配对/连接端口。"""
        try:
            out = self.text(["mdns", "services"], timeout=15)
        except AdbError:
            return []
        services: list[dict] = []
        for line in out.splitlines():
            line = line.strip()
            if not line or "_adb" not in line:
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            name = parts[0]
            endpoint = parts[1] if len(parts) > 1 else ""
            match = re.search(r"(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})", endpoint)
            if not match:
                continue
            services.append(
                {
                    "name": name,
                    "type": "pairing" if "pairing" in name else "connect",
                    "host": match.group(1),
                    "port": int(match.group(2)),
                }
            )
        return services


def list_devices(adb_path: str | None = None) -> dict:
    """前端「检测设备」入口。"""
    adb = Adb(adb_path=adb_path)
    path = adb.adb_path or ""
    try:
        devices = [device.to_dict() for device in adb.devices()]
        error = ""
    except AdbError as exc:
        devices, error = [], str(exc)
    return {
        "adbPath": path,
        "adbFound": bool(path and os.path.isfile(path)),
        "adbVersion": adb.version() if path else "",
        "devices": devices,
        "error": error,
    }
