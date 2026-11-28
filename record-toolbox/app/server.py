"""本地 HTTP 服务：静态页面 + JSON API + 媒体流（视频 Range / 截图 PNG）。

只监听 127.0.0.1，随机端口，不暴露到局域网。
"""
from __future__ import annotations

import json
import logging
import mimetypes
import os
import re
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import api, paths

log = logging.getLogger("record_toolbox.server")

WEB_DIR = paths.resource_path("web")
ASSETS_DIR = paths.resource_path("assets")

DEBUG_TAG = "RecordToolbox"


class Handler(BaseHTTPRequestHandler):
    server_version = "RecordToolbox/1.0"
    protocol_version = "HTTP/1.1"

    # ------------------------------------------------------------------ 工具
    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        log.debug("%s - %s", self.address_string(), fmt % args)

    def _send_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_file(self, path: str, content_type: str | None = None, cache: str = "no-store") -> None:
        try:
            size = os.path.getsize(path)
        except OSError:
            self.send_error(404)
            return
        content_type = content_type or mimetypes.guess_type(path)[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", cache)
        self.end_headers()
        with open(path, "rb") as f:
            while True:
                chunk = f.read(1 << 18)
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return

    def _range_stream(self, path: str, content_type: str) -> None:
        try:
            size = os.path.getsize(path)
        except OSError:
            self.send_error(404)
            return
        range_header = self.headers.get("Range")
        start, end = 0, size - 1
        if range_header:
            match = re.match(r"bytes=(\d*)-(\d*)", range_header.strip())
            if match:
                if match.group(1):
                    start = int(match.group(1))
                if match.group(2):
                    end = int(match.group(2))
                if start > end or start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.end_headers()
                    return
        length = end - start + 1
        self.send_response(206 if range_header else 200)
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if range_header:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(1 << 18, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                remaining -= len(chunk)

    # -------------------------------------------------------------------- GET
    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = urllib.parse.unquote(parsed.path)
        try:
            if path.startswith("/media/video/"):
                sid = path[len("/media/video/"):].split("/")[0]
                session = api.get_session(sid)
                if not session.video:
                    self.send_error(404, "video not found")
                    return
                self._range_stream(session.video["path"], mimetypes.guess_type(session.video["path"])[0] or "video/mp4")
                return
            if path.startswith("/media/shot/"):
                parts = path[len("/media/shot/"):].split("/")
                if len(parts) >= 2:
                    session = api.get_session(parts[0])
                    shot_id = re.sub(r"\.png$", "", parts[1])
                    for shot in session.shots:
                        if shot["id"] == shot_id:
                            self._send_file(shot["path"], "image/png")
                            return
                self.send_error(404)
                return
            if path in ("/", "/index.html"):
                self._send_file(os.path.join(WEB_DIR, "index.html"), "text/html; charset=utf-8")
                return
            if path.startswith("/assets/"):
                rel = path[len("/assets/"):]
                target = os.path.normpath(os.path.join(ASSETS_DIR, rel))
                if target.startswith(ASSETS_DIR) and os.path.isfile(target):
                    self._send_file(target, cache="max-age=86400")
                    return
                self.send_error(404)
                return
            if path.startswith("/api/"):
                self.send_error(405, "api is POST only")
                return
            rel = path.lstrip("/")
            target = os.path.normpath(os.path.join(WEB_DIR, rel))
            if target.startswith(WEB_DIR) and os.path.isfile(target):
                self._send_file(target)
                return
            self.send_error(404)
        except KeyError:
            self.send_error(404, "session not found")
        except (ConnectionAbortedError, ConnectionResetError, BrokenPipeError):
            # 视频 seek 时浏览器会主动断开 Range 连接，属正常行为，不写错误日志
            log.debug("客户端提前断开连接 %s", path)
        except Exception:  # pragma: no cover
            log.exception("GET 处理失败 %s", path)
            self.send_error(500)

    # ------------------------------------------------------------------- POST
    def _handle_video_upload(self) -> None:
        """接收前端拖拽进来的录屏文件（原始字节流），落盘后登记成视频 session。"""
        length = int(self.headers.get("Content-Length") or 0)
        raw_name = self.headers.get("X-File-Name") or "recording.mp4"
        try:
            from urllib.parse import unquote  # noqa: PLC0415
            raw_name = unquote(raw_name)
        except Exception:  # noqa: BLE001
            pass
        safe = re.sub(r"[^0-9A-Za-z._\-\u4e00-\u9fff]+", "_", os.path.basename(raw_name)) or "recording.mp4"
        if length <= 0:
            self._send_json({"ok": False, "error": "上传内容为空"})
            return
        upload_dir = os.path.join(paths.data_dir(), "uploads")
        os.makedirs(upload_dir, exist_ok=True)
        target = os.path.join(upload_dir, f"{int(time.time())}_{safe}")
        received = 0
        try:
            with open(target, "wb") as fh:
                while received < length:
                    chunk = self.rfile.read(min(1024 * 1024, length - received))
                    if not chunk:
                        break
                    fh.write(chunk)
                    received += len(chunk)
        except OSError as exc:
            log.warning("上传录屏失败：%s", exc)
            self._send_json({"ok": False, "error": f"写入文件失败：{exc}"})
            return
        if received < length:
            try:
                os.remove(target)
            except OSError:
                pass
            self._send_json({"ok": False, "error": "上传中断，请重试"})
            return
        try:
            result = api.create_video_session(target, raw_name, received)
        except Exception as exc:  # noqa: BLE001
            log.warning("登记上传视频失败：%s", exc)
            self._send_json({"ok": False, "error": str(exc)})
            return
        self._send_json(result)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = urllib.parse.unquote(parsed.path)
        if not path.startswith("/api/"):
            self.send_error(404)
            return
        action = path[len("/api/"):].strip("/")
        if action == "upload_video":
            self._handle_video_upload()
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            payload = json.loads(raw.decode("utf-8") or "{}")
            if not isinstance(payload, dict):
                payload = {"value": payload}
        except (ValueError, UnicodeDecodeError):
            self._send_json({"ok": False, "error": "请求格式错误"}, 400)
            return
        result = api.dispatch_safe(action, payload)
        self._send_json(result)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def create(host: str = "127.0.0.1", port: int = 0) -> Server:
    httpd = Server((host, port), Handler)
    return httpd


def serve_in_thread(httpd: Server) -> int:
    thread = threading.Thread(target=httpd.serve_forever, name="record-toolbox-http", daemon=True)
    thread.start()
    return httpd.server_address[1]
