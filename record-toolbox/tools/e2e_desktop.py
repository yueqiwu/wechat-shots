"""笔录工具箱 · 端到端自动化测试（无第三方依赖：urllib + websocket-client + Edge/Chromium CDP）。

流程：
  1. 用 `python run.py --headless --print-port` 启动本地服务，拿到端口；
  2. 用 Edge（Chromium 内核，和 WebView2 同源）以 --headless=new --remote-debugging-port 打开页面；
  3. 通过 CDP 在页面内执行 JS：点击按钮、读取 window.__RT 状态、截图；
  4. 覆盖三个场景：首页/路由、录屏转截图全流程、USB 截图导出全流程（需要 emulator 设备）。

用法：
  python tools\\e2e_desktop.py                 # 全部场景
  python tools\\e2e_desktop.py --only home
  python tools\\e2e_desktop.py --only video
  python tools\\e2e_desktop.py --only adb
  python tools\\e2e_desktop.py --keep          # 结束后保留服务/浏览器（便于观察）
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.request

import websocket  # pip install websocket-client

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "test-artifacts", "e2e")
PY = sys.executable
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
EDGE_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]


def log(msg: str) -> None:
    print(f"[e2e {time.strftime('%H:%M:%S')}] {msg}", flush=True)


def find_edge() -> str:
    for p in EDGE_CANDIDATES:
        if os.path.isfile(p):
            return p
    raise SystemExit("未找到 Edge（msedge.exe）")


# ---------------------------------------------------------------- CDP 客户端
class CDP:
    def __init__(self, ws_url: str):
        self.ws = websocket.create_connection(ws_url, timeout=30)
        self.nid = 0

    def send(self, method: str, params: dict | None = None, timeout: float = 60):
        self.nid += 1
        nid = self.nid
        self.ws.send(json.dumps({"id": nid, "method": method, "params": params or {}}))
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.ws.settimeout(max(1.0, deadline - time.time()))
            raw = self.ws.recv()
            try:
                msg = json.loads(raw)
            except Exception:
                continue
            if msg.get("id") == nid:
                if "error" in msg:
                    raise RuntimeError(f"CDP {method} 失败: {msg['error']}")
                return msg.get("result") or {}
        raise TimeoutError(f"CDP {method} 超时")

    def js(self, expression: str, timeout: float = 120):
        res = self.send(
            "Runtime.evaluate",
            {"expression": expression, "returnByValue": True, "awaitPromise": True, "userGesture": True},
            timeout=timeout,
        )
        if "exceptionDetails" in res:
            raise RuntimeError(f"页面 JS 异常: {res['exceptionDetails'].get('text')} :: {expression[:160]}")
        return (res.get("result") or {}).get("value")

    def wait_js(self, expression: str, timeout: float = 30, interval: float = 0.4):
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            try:
                last = self.js(expression, timeout=15)
                if last:
                    return last
            except Exception as exc:  # 页面刷新/暂时取不到
                last = str(exc)
            time.sleep(interval)
        raise TimeoutError(f"等待条件超时：{expression}（最后结果：{last}）")

    def shot(self, name: str) -> str:
        res = self.send("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": False})
        path = os.path.join(ART, name)
        with open(path, "wb") as f:
            f.write(base64.b64decode(res["data"]))
        log(f"截图 {path}")
        return path

    def close(self) -> None:
        try:
            self.ws.close()
        except Exception:
            pass


def wait_json(url: str, timeout: float = 30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception:
            time.sleep(0.3)
    raise TimeoutError(f"等待 {url} 超时")


def post(url: str, payload: dict, timeout: float = 120) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


# ---------------------------------------------------------------- 场景
def scenario_adb_preview(cdp: CDP) -> None:
    """真机（USB）回归：设备列表能列出真实 serial、切换导出页能自动读到当前画面。"""
    log("场景：手机截图导出（真机 USB 预览）")
    cdp.js("window.__E2E = true; true")
    cdp.js(
        """(() => { const el=document.querySelector('[data-testid="home-card-adb"]');
        if(el){el.click();return true;} return false; })()"""
    )
    time.sleep(1.5)
    cdp.js(
        """(() => { const b=document.querySelector('[data-testid="adb-refresh"]');
        if(b){b.click();return true;} return false; })()"""
    )
    listed = cdp.wait_js("/3B65CB029KV00000|PLR110|一加/.test(document.body.innerText)", 40)
    if not listed:
        raise RuntimeError("设备列表里没有找到真机 serial（3B65CB029KV00000 / PLR110）")
    cdp.shot("20-real-adb-devices.png")
    cdp.js(
        """(() => { const b=document.querySelector('[data-testid="adb-tab-export"]');
        if(b){b.click();return true;} return false; })()"""
    )
    ok = False
    for _ in range(40):
        time.sleep(1.0)
        txt = cdp.js("document.body.innerText.slice(0, 4000)") or ""
        if "读取画面失败" in txt:
            cdp.shot("21-real-adb-preview-fail.png")
            raise RuntimeError("真机预览失败：" + txt[:800])
        has_img = cdp.js(
            "!!(document.querySelector('.preview-frame img') && "
            "document.querySelector('.preview-frame img').complete && "
            "document.querySelector('.preview-frame img').naturalWidth > 0)"
        )
        if has_img:
            ok = True
            break
    cdp.shot("21-real-adb-preview.png")
    if not ok:
        raise RuntimeError("真机预览图片没有加载出来")
    log("真机预览通过（读取到当前画面）")


def scenario_home(cdp: CDP) -> None:
    log("场景：首页与路由")
    cdp.wait_js("document.body && document.body.innerText.length > 0", 20)
    cdp.wait_js("/笔录工具箱|导出聊天记录/.test(document.body.innerText)", 20)
    cdp.shot("01-home.png")
    # 点击「录屏转截图」卡片
    cdp.js(
        """(() => { const el = document.querySelector('[data-testid="home-card-video"]');
        if (el) { el.click(); return true; }
        const all = [...document.querySelectorAll('button,div')];
        const hit = all.find(e => e.innerText && e.innerText.includes('录屏转截图'));
        if (hit) { hit.click(); return true; } return false; })()"""
    )
    time.sleep(0.8)
    cdp.shot("02-video-page.png")
    # 回到首页
    cdp.js("""(() => { const t=[...document.querySelectorAll('*')].find(e=>e.getAttribute&&e.getAttribute('data-tab')==='home'); if(t){t.click();return true;} window.history.back(); return false; })()""")
    time.sleep(0.6)


def scenario_video(cdp: CDP, port: int, out_dir: str) -> None:
    log("场景：录屏转截图全流程")
    video = os.environ.get("RTB_E2E_VIDEO") or os.path.join(ROOT, "..", "wechat-shots", "input.mp4")
    video = os.path.abspath(video)
    if not os.path.isfile(video):
        raise SystemExit(f"缺少测试视频：{video}")
    post(f"http://127.0.0.1:{port}/api/settings.save", {"settings": {"output_dir": out_dir}})
    cdp.js(f"window.__RT_E2E_VIDEO = {json.dumps(video)}; window.__E2E = true; true")
    cdp.js("""(() => { const el=document.querySelector('[data-testid="home-card-video"]'); if(el){el.click();return true;}
      const hit=[...document.querySelectorAll('button,div')].find(e=>e.innerText&&e.innerText.includes('录屏转截图')); if(hit){hit.click();return true;} return false; })()""")
    cdp.wait_js("!!document.querySelector('[data-testid=\"video-pick\"]') || /录屏转截图/.test(document.body.innerText)", 15)
    cdp.shot("03-video-empty.png")
    cdp.js("""(() => { const b=document.querySelector('[data-testid="video-pick"]'); if(b){b.click();return true;} return false; })()""")
    cdp.wait_js("/input\\.mp4|23\\.8|00:2[0-9]/.test(document.body.innerText) || !!document.querySelector('[data-testid=\"video-start\"]')", 30)
    cdp.shot("04-video-picked.png")
    cdp.js("""(() => { const b=document.querySelector('[data-testid="video-start"]'); if(b){b.click();return true;} return false; })()""")
    t0 = time.time()
    while time.time() - t0 < 420:
        time.sleep(3)
        txt = cdp.js("document.body.innerText.slice(0, 4000)") or ""
        body_text = cdp.js("document.body.innerText") or ""
        has_result = ("共生成" in body_text) and ("渲染第" not in body_text)
        if has_result:
            break
        if "失败" in txt or "错误" in txt:
            cdp.shot("05-video-error.png")
            raise RuntimeError("录屏流程出现错误：" + txt[:1000])
        log(f"录屏处理中… {int(time.time()-t0)}s")
    cdp.shot("06-video-result.png")
    log(f"录屏流程耗时 {time.time()-t0:.1f}s")
    # 保存并校验
    cdp.js("""(() => { const b=document.querySelector('[data-testid="video-save"]'); if(b){b.click();return true;} return false; })()""")
    time.sleep(3)
    cdp.shot("07-video-saved.png")
    dirs = [os.path.join(out_dir, n) for n in os.listdir(out_dir) if os.path.isdir(os.path.join(out_dir, n))]
    if not dirs:
        raise RuntimeError("录屏保存后输出目录为空")
    newest = max(dirs, key=os.path.getmtime)
    pages = sorted(n for n in os.listdir(newest) if n.lower().endswith(".png"))
    if len(pages) < 5:
        try:
            dbg = cdp.js(
                "JSON.stringify({analyze: window.__RT_ANALYZE_ROUTE||null,"
                " opts: window.__RT_ANALYZE_OPTS||null,"
                " video: window.__RT_VIDEO_DEBUG||null,"
                " render: window.__RT_RENDER_DEBUG||null,"
                " body: document.body.innerText.slice(0,600)})"
            )
            log("页面诊断：" + str(dbg)[:4000])
        except Exception as err:
            log("读取页面诊断失败：" + str(err))
        raise RuntimeError(f"录屏保存页面过少：{len(pages)}（{newest}）")
    log(f"录屏保存 {len(pages)} 页 → {newest}")


def prep_emulator() -> str:
    log("准备模拟器（mock 微聊长会话）")
    def run(*args, timeout=60):
        return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace")
    out = run("devices")
    if "emulator-5554" not in out.stdout:
        raise RuntimeError("模拟器未启动（adb devices 无 emulator-5554）")
    run("-s", "emulator-5554", "shell", "am", "start", "-n", "com.tencent.mm/.MockActivity")
    time.sleep(2.5)
    xml = run("-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/_e2e_ui.xml")
    raw = run("-s", "emulator-5554", "shell", "cat", "/sdcard/_e2e_ui.xml").stdout
    import re
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', raw):
        text, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
        if text in ("张三", "李四"):
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            run("-s", "emulator-5554", "shell", "input", "tap", str(cx), str(cy))
            time.sleep(1.5)
            log(f"进入会话：{text} ({cx},{cy})")
            return text
    raise RuntimeError("模拟器 mock 微聊里没找到 张三/李四 会话")


def scenario_adb(cdp: CDP, port: int, out_dir: str) -> None:
    log("场景：手机截图导出（模拟器）")
    who = prep_emulator()
    post(f"http://127.0.0.1:{port}/api/settings.save", {"settings": {"output_dir": out_dir}})
    cdp.js("window.__E2E = true; true")
    cdp.js("""(() => { const el=document.querySelector('[data-testid="home-card-adb"]'); if(el){el.click();return true;}
      const hit=[...document.querySelectorAll('button,div')].find(e=>e.innerText&&e.innerText.includes('手机截图导出')); if(hit){hit.click();return true;} return false; })()""")
    time.sleep(1.5)
    cdp.shot("07-adb-page.png")
    # 刷新设备
    cdp.js("""(() => { const b=document.querySelector('[data-testid="adb-refresh"]'); if(b){b.click();return true;} return false; })()""")
    cdp.wait_js("/emulator-5554|sdk_gphone|已连接|在线/.test(document.body.innerText)", 30)
    cdp.shot("08-adb-device.png")
    # 切到导出页
    cdp.js("""(() => { const b=document.querySelector('[data-testid="adb-tab-export"]'); if(b){b.click();return true;}
      const hit=[...document.querySelectorAll('button,div,span')].find(e=>e.innerText&&e.innerText.trim()==='导出截图'); if(hit){hit.click();return true;} return false; })()""")
    time.sleep(1.0)
    cdp.shot("09-adb-export.png")
    cdp.js("""(() => { const b=document.querySelector('[data-testid="export-start"]'); if(b){b.click();return true;} return false; })()""")
    t0 = time.time()
    last = ""
    while time.time() - t0 < 300:
        time.sleep(2.5)
        txt = cdp.js("document.body.innerText.slice(0, 4000)") or ""
        st = cdp.js("window.__RT && window.__RT.state ? JSON.stringify(window.__RT.state) : ''") or ""
        if txt != last:
            log("进度：" + " | ".join([l.strip() for l in txt.splitlines() if l.strip()][:12]))
            last = txt
        if "已保存" in txt or "保存到文件夹" in txt or "完成" in txt:
            break
        if "失败" in txt and "重试" in txt:
            cdp.shot("10-adb-error.png")
            raise RuntimeError("截图导出失败：" + txt[:1500])
        if st:
            log("状态：" + st[:300])
    cdp.shot("11-adb-result.png")
    log(f"截图导出耗时 {time.time()-t0:.1f}s")
    # 保存
    cdp.js("""(() => { const b=document.querySelector('[data-testid="export-save"]'); if(b){b.click();return true;} return false; })()""")
    time.sleep(4)
    cdp.shot("12-adb-saved.png")


def scenario_static(cdp: CDP, port: int, out_dir: str) -> None:
    """静态方案（手机截图）全流程：用合成截图绕过真实设备，验证拼接→结果→保存。"""
    log("场景：手机截图导出（合成截图旁路）")
    from PIL import Image  # 只在 E2E 里用于校验输出尺寸

    shots_dir = os.path.join(ART, "shots")
    paths = sorted(
        os.path.join(shots_dir, n) for n in os.listdir(shots_dir) if n.lower().endswith(".png")
    )
    if len(paths) < 3:
        raise RuntimeError(f"合成截图不足：{shots_dir}（先运行 tools/make_e2e_shots.py）")
    mode = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "test-artifacts", "e2e", "shots-master.png")
    expected_h = 0
    meta_path = os.path.join(shots_dir, "meta.txt")
    if os.path.isfile(meta_path):
        with open(meta_path, encoding="utf-8") as f:
            for line in f:
                if line.startswith("coverage="):
                    expected_h = int(line.split("=", 1)[1])
    elif os.path.isfile(mode):
        with Image.open(mode) as im:
            expected_h = im.height

    urls = []
    for path in paths:
        with open(path, "rb") as f:
            urls.append("data:image/png;base64," + base64.b64encode(f.read()).decode("ascii"))
    log(f"注入 {len(urls)} 张合成截图（长图预计 {expected_h}px）")

    post(f"http://127.0.0.1:{port}/api/settings.save", {"settings": {"output_dir": out_dir}})
    cdp.js("window.__E2E = true; true")
    cdp.js("""(() => { const el=document.querySelector('[data-testid="home-card-adb"]'); if(el){el.click();return true;}
      const hit=[...document.querySelectorAll('button,div')].find(e=>e.innerText&&e.innerText.includes('手机截图导出')); if(hit){hit.click();return true;} return false; })()""")
    time.sleep(1.2)
    cdp.js("""(() => { const b=document.querySelector('[data-testid="adb-tab-export"]'); if(b){b.click();return true;}
      const hit=[...document.querySelectorAll('button,div,span')].find(e=>e.innerText&&e.innerText.trim()==='导出截图'); if(hit){hit.click();return true;} return false; })()""")
    time.sleep(0.8)
    cdp.js(f"window.__RT_E2E_SHOTS = {json.dumps(urls)}; true")
    cdp.shot("s1-e2e-ready.png")
    cdp.js("""(() => { const b=document.querySelector('[data-testid="export-start"]'); if(b){b.click();return true;} return false; })()""")
    t0 = time.time()
    txt = ""
    while time.time() - t0 < 300:
        time.sleep(1.5)
        txt = cdp.js("document.body.innerText.slice(0, 4000)") or ""
        if ('保存到文件夹' in txt or '已生成' in txt or '导出结果' in txt or
                cdp.js("!!document.querySelector('[data-testid=\"export-result\"]') || !!document.querySelector('[data-testid=\"export-save\"]')")):
            break
        if '失败' in txt and '重试' in txt:
            cdp.shot("s2-e2e-error.png")
            raise RuntimeError("静态方案出现错误：" + txt[:1500])
        log("静态拼接中… " + " | ".join([l.strip() for l in txt.splitlines() if l.strip()][:8]))
    cdp.shot("s2-e2e-result.png")
    elapsed = time.time() - t0
    log(f"静态拼接完成 {elapsed:.1f}s")

    cdp.js("""(() => { const b=document.querySelector('[data-testid="export-save"]'); if(b){b.click();return true;} return false; })()""")
    time.sleep(4)
    cdp.shot("s3-e2e-saved.png")

    # 校验输出目录：最新文件夹里应有页面 PNG + export.json + 说明.txt，页高总和接近原始长图
    dirs = [os.path.join(out_dir, n) for n in os.listdir(out_dir) if os.path.isdir(os.path.join(out_dir, n))]
    if not dirs:
        raise RuntimeError("保存后输出目录为空")
    newest = max(dirs, key=os.path.getmtime)
    pages = sorted(n for n in os.listdir(newest) if n.lower().endswith(".png"))
    if len(pages) < 3:
        raise RuntimeError(f"输出页面过少：{len(pages)}（{newest}）")
    total_h = 0
    for name in pages:
        with Image.open(os.path.join(newest, name)) as im:
            total_h += im.height
    log(f"输出 {len(pages)} 页，页高合计 {total_h}px（原始长图 {expected_h}px）→ {newest}")
    if expected_h and abs(total_h - expected_h) > expected_h * 0.12:
        raise RuntimeError(f"拼接高度异常：{total_h} vs 期望 {expected_h}")
    for required in ("export.json", "说明.txt"):
        if not os.path.isfile(os.path.join(newest, required)):
            raise RuntimeError(f"缺少输出文件：{required}")
    with open(os.path.join(newest, "export.json"), encoding="utf-8") as f:
        meta = json.loads(f.read())
    log("export.json：" + json.dumps(meta, ensure_ascii=False)[:400])


# ---------------------------------------------------------------- 主流程
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["home", "video", "adb", "static", "preview", "all"], default="all")
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--debug-port", type=int, default=9333)
    args = ap.parse_args()
    os.makedirs(ART, exist_ok=True)
    out_dir = os.path.join(ART, "output")
    os.makedirs(out_dir, exist_ok=True)

    log("启动本地服务…")
    srv = subprocess.Popen(
        [PY, os.path.join(ROOT, "run.py"), "--headless", "--print-port"],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    port = None
    t0 = time.time()
    while time.time() - t0 < 30:
        line = srv.stdout.readline()
        if not line:
            break
        line = line.strip()
        if line.startswith("PORT "):
            port = int(line.split()[1])
            break
    if not port:
        srv.kill()
        raise SystemExit("无法启动本地服务（没读到 PORT）")
    url = f"http://127.0.0.1:{port}/?e2e=1"
    log(f"服务端口 {port}")

    edge = find_edge()
    import tempfile

    profile = tempfile.mkdtemp(prefix="rt-edge-")
    edge_proc = subprocess.Popen(
        [
            edge,
            "--headless=new",
            f"--remote-debugging-port={args.debug_port}",
            f"--user-data-dir={profile}",
            "--no-first-run",
            "--disable-extensions",
            "--window-size=1280,900",
            url,
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        targets = wait_json(f"http://127.0.0.1:{args.debug_port}/json/list", 30)
        page = next(
            (t for t in targets if t.get("type") == "page" and "127.0.0.1" in (t.get("url") or "")),
            None,
        )
        if page is None:
            page = next((t for t in targets if t.get("type") == "page"), None)
        if page is None:
            raise RuntimeError("没有找到页面 target")
        cdp = CDP(page["webSocketDebuggerUrl"])
        cdp.send("Page.enable")
        cdp.send("Runtime.enable")
        time.sleep(1.0)
        try:
            if args.only in ("all", "home"):
                scenario_home(cdp)
            if args.only in ("all", "video"):
                scenario_video(cdp, port, out_dir)
            if args.only in ("static", "all"):
                scenario_static(cdp, port, out_dir)
            if args.only in ("all", "adb"):
                scenario_adb(cdp, port, out_dir)
            if args.only == "preview":
                scenario_adb_preview(cdp)
        finally:
            cdp.close()
        log("E2E 通过 ✅")
        return 0
    except Exception as exc:
        log(f"E2E 失败：{exc}")
        return 1
    finally:
        if not args.keep:
            try:
                edge_proc.kill()
            except Exception:
                pass
            try:
                srv.kill()
            except Exception:
                pass


if __name__ == "__main__":
    sys.exit(main())
