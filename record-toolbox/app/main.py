"""笔录工具箱 · 入口。

启动本地 HTTP 服务（127.0.0.1 随机端口），再用 WebView2 打开界面。
也支持 `--headless` 只起服务（便于自动化测试 / 用 Chrome 调试）。
"""
from __future__ import annotations

import argparse
import logging
import logging.handlers
import os
import sys
import threading
import time
from typing import Any

from . import paths, server

log = logging.getLogger("record_toolbox")


def setup_logging() -> None:
    paths.ensure_dirs()
    log_file = os.path.join(paths.logs_dir(), "app.log")
    handler = logging.handlers.RotatingFileHandler(
        log_file, maxBytes=2 * 1024 * 1024, backupCount=3, encoding="utf-8"
    )
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.addHandler(handler)
    if sys.stderr and sys.stderr.isatty():
        stream = logging.StreamHandler()
        stream.setFormatter(logging.Formatter("%(levelname)s %(name)s: %(message)s"))
        root.addHandler(stream)
    logging.getLogger("record_toolbox").info("启动 %s v%s", paths.APP_NAME, paths.VERSION)


def _set_window_icon(window: Any) -> None:
    """尽量把窗口/任务栏图标换成应用图标（Windows）。"""
    if os.name != "nt":
        return
    try:
        import ctypes

        icon_path = paths.resource_path("assets", "icon.ico")
        if not os.path.isfile(icon_path):
            return
        user32 = ctypes.windll.user32
        hwnd = getattr(window, "hwnd", None)
        if not hwnd:
            # pywebview 的 WinForms 窗口可通过 native handle 获取
            native = getattr(window, "native", None)
            hwnd = getattr(native, "Handle", None) if native is not None else None
        if not hwnd:
            return
        WM_SETICON = 0x0080
        IMAGE_ICON = 1
        LR_LOADFROMFILE = 0x0010
        hicon = user32.LoadImageW(None, icon_path, IMAGE_ICON, 0, 0, LR_LOADFROMFILE)
        if hicon:
            user32.SendMessageW(int(hwnd), WM_SETICON, 0, hicon)
            user32.SendMessageW(int(hwnd), WM_SETICON, 1, hicon)
    except Exception:  # pragma: no cover - 图标只是锦上添花
        log.debug("设置窗口图标失败", exc_info=True)


def run_window(url: str, debug: bool = False) -> None:
    import webview

    window = webview.create_window(
        paths.APP_NAME,
        url,
        width=1200,
        height=840,
        min_size=(1000, 700),
        confirm_close=False,
        background_color="#F2F2F7",
    )
    try:
        _set_window_icon(window)
    except Exception:
        pass
    webview.start(debug=debug, private_mode=False)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=paths.APP_NAME)
    parser.add_argument("--headless", action="store_true", help="只启动本地服务，不打开窗口")
    parser.add_argument("--port", type=int, default=0, help="固定端口（默认随机）")
    parser.add_argument("--debug", action="store_true", help="打开 WebView 调试")
    parser.add_argument("--print-port", action="store_true", help="打印端口后阻塞（供自动化测试）")
    args = parser.parse_args(argv)

    setup_logging()
    paths.ensure_dirs()
    httpd = server.create(port=args.port)
    port = server.serve_in_thread(httpd)
    url = f"http://127.0.0.1:{port}/"
    log.info("本地服务已启动：%s", url)

    if args.print_port:
        print(f"PORT {port}", flush=True)

    if args.headless:
        print(url, flush=True)
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            return 0

    try:
        run_window(url, debug=args.debug)
    finally:
        httpd.shutdown()
        threading.Timer(0.5, lambda: os._exit(0)).start()
    return 0


if __name__ == "__main__":
    sys.exit(main())
