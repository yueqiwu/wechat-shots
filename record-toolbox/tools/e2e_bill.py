"""笔录工具箱 · 交易明细格式化端到端测试（Edge headless + CDP）。

流程：启动本地服务 → 打开页面 → 进入交易明细页 → 通过 CDP 给隐藏文件输入框塞入两真实 PDF
      → 校验解析条数/统计/不计收支禁选/搜索筛选 → 取消勾选一条 → 导出支出与收入 Word
      → 校验生成文件存在、大小合理、文档文本包含模板首句。
用法：python tools\\e2e_bill.py
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

import websocket  # pip install websocket-client
from docx import Document  # 仅测试环境校验 docx；生产不依赖

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "test-artifacts", "bill")
OUT_DIR = os.path.join(ART, "output")
PY = sys.executable

WECHAT_PDF = r"E:\wechatrecord\xwechat_files\wxid_gn19xayz7un121_2fe9\msg\file\2026-11\微信支付交易明细证明(20260101-20261129)_20261129005557.pdf"
ALIPAY_PDF = r"E:\wechatrecord\xwechat_files\wxid_gn19xayz7un121_2fe9\msg\file\2026-11\支付宝交易明细(20260101-20261129).pdf"

EDGE_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

failures = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"  [ok] {name}", flush=True)
    else:
        failures.append(name + (("：" + detail) if detail else ""))
        print(f"  [FAIL] {name} {detail}", flush=True)


def log(msg: str) -> None:
    print(f"[bill-e2e {time.strftime('%H:%M:%S')}] {msg}", flush=True)


def find_edge() -> str:
    for p in EDGE_CANDIDATES:
        if os.path.isfile(p):
            return p
    raise SystemExit("未找到 Edge（msedge.exe）")


class CDP:
    def __init__(self, ws_url: str):
        self.ws = websocket.create_connection(ws_url, timeout=60)
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
                last = self.js(expression, timeout=20)
                if last:
                    return last
            except Exception as exc:  # 页面渲染期间可能短暂异常
                last = str(exc)
            time.sleep(interval)
        raise TimeoutError(f"等待条件超时：{expression}（最后结果：{last}）")

    def shot(self, name: str) -> str:
        res = self.send("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": False})
        path = os.path.join(ART, name)
        with open(path, "wb") as fh:
            import base64

            fh.write(base64.b64decode(res["data"]))
        log(f"截图 {path}")
        return path

    def set_file(self, selector: str, path: str) -> None:
        doc = self.send("DOM.getDocument", {"depth": 1})
        node = self.send("DOM.querySelector", {"nodeId": doc["root"]["nodeId"], "selector": selector})
        node_id = node.get("nodeId")
        if not node_id:
            raise RuntimeError(f"找不到文件输入框：{selector}")
        self.send("DOM.setFileInputFiles", {"files": [path], "nodeId": node_id})

    def close(self) -> None:
        try:
            self.ws.close()
        except Exception:
            pass


def wait_json(url: str, timeout: float = 30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception:
            time.sleep(0.3)
    raise TimeoutError(f"等待 {url} 超时")


def post(url: str, payload: dict, timeout: float = 120) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def docx_text(path: str) -> str:
    document = Document(path)
    parts = [p.text for p in document.paragraphs]
    for table in document.tables:
        for row in table.rows:
            for cell in row.cells:
                parts.append(cell.text)
    return "\n".join(parts)


def chapter(name: str) -> None:
    log("=" * 20 + " " + name)


def main() -> int:
    if os.path.isdir(ART):
        shutil.rmtree(ART, ignore_errors=True)
    os.makedirs(OUT_DIR, exist_ok=True)
    for path, label in ((WECHAT_PDF, "微信"), (ALIPAY_PDF, "支付宝")):
        if not os.path.isfile(path):
            raise SystemExit(f"缺少{label}测试 PDF：{path}")

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
    while time.time() - t0 < 40:
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
    base = f"http://127.0.0.1:{port}"
    log(f"服务端口 {port}")
    post(base + "/api/settings.save", {"settings": {"output_dir": OUT_DIR}})

    edge = find_edge()
    import tempfile

    profile = tempfile.mkdtemp(prefix="rt-edge-bill-")
    edge_proc = subprocess.Popen(
        [
            edge,
            "--headless=new",
            "--remote-debugging-port=9444",
            f"--user-data-dir={profile}",
            "--no-first-run",
            "--disable-extensions",
            "--window-size=1280,940",
            base + "/?e2e=1",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        targets = wait_json("http://127.0.0.1:9444/json/list", 30)
        page = next((t for t in targets if t.get("type") == "page" and "127.0.0.1" in (t.get("url") or "")), None)
        if page is None:
            raise RuntimeError("没有找到页面 target")
        cdp = CDP(page["webSocketDebuggerUrl"])
        cdp.send("Page.enable")
        cdp.send("Runtime.enable")
        time.sleep(1.2)

        chapter("进入交易明细页")
        cdp.wait_js("!!document.querySelector('[data-testid=\"home-card-bill\"]')", 30)
        cdp.js("document.querySelector('[data-testid=\"home-card-bill\"]').click(); true")
        cdp.wait_js("/交易明细格式化/.test(document.body.innerText)", 20)
        cdp.wait_js("!!window.__RT_E2E_BILL", 20)
        cdp.shot("10-bill-empty.png")

        chapter("导入微信支付明细")
        cdp.set_file('[data-testid="bill-file-wechat"]', WECHAT_PDF)
        cdp.wait_js("(() => { const s = window.__RT_E2E_BILL.state(); return s.rows > 0 && !s.busy; })()", 60)
        time.sleep(0.5)

        chapter("导入支付宝明细")
        cdp.set_file('[data-testid="bill-file-alipay"]', ALIPAY_PDF)
        cdp.wait_js("window.__RT_E2E_BILL.state().rows >= 493", 90)
        time.sleep(0.5)
        state = cdp.js("window.__RT_E2E_BILL.state()")
        log(f"页面状态：{state}")
        check("解析出 493 条明细（微信 301 + 支付宝 192）", state["rows"] == 493, str(state["rows"]))
        body = cdp.js("document.body.innerText") or ""
        check("统计卡显示支出 429 条", "429" in body, "")
        check("统计卡显示收入 40 条", "40" in body, "")
        check("统计卡显示不计收支 24 条", "24" in body, "")
        check("不计收支共 24 条", state.get("other") == 24, str(state.get("other")))
        disabled = cdp.js(
            "(() => { const rows=[...document.querySelectorAll('.bill-row-other .bill-check')];"
            " return rows.length && rows.every(el => el.disabled); })()"
        )
        check("渲染出的不计收支条目全部禁用", bool(disabled))
        cdp.shot("11-bill-preview.png")

        chapter("筛选与搜索")
        tabs = cdp.js(
            "(() => { const items=[...document.querySelectorAll('.segmented-item')];"
            " const hit=items.find(e=>e.innerText.trim()==='支出'); if(!hit) return false; hit.click(); return true; })()"
        )
        check("切换到「支出」筛选", bool(tabs))
        time.sleep(0.3)
        view_text = cdp.js("document.querySelector('.bill-view-count') ? document.querySelector('.bill-view-count').innerText : ''")
        check("支出筛选后列表为 429 条", "429" in (view_text or ""), view_text or "")
        cdp.js(
            """(() => { const input=document.querySelector('.bill-toolbar-search input');
            if(!input) return false; input.value='旅途易购';
            input.dispatchEvent(new Event('input',{bubbles:true})); return true; })()"""
        )
        time.sleep(0.4)
        searching = cdp.js("document.body.innerText.includes('旅途易购')")
        check("搜索关键词命中明细", bool(searching))
        cdp.shot("12-bill-filter.png")
        cdp.js(
            """(() => { const input=document.querySelector('.bill-toolbar-search input');
            if(!input) return false; input.value='';
            input.dispatchEvent(new Event('input',{bubbles:true})); return true; })()"""
        )
        cdp.js(
            "(() => { const items=[...document.querySelectorAll('.segmented-item')];"
            " const hit=items.find(e=>e.innerText.trim()==='全部'); if(hit) hit.click(); return true; })()"
        )
        time.sleep(0.4)

        chapter("取消勾选一条支出")
        before = cdp.js("window.__RT_E2E_BILL.state().selected")
        cdp.js(
            """(() => { const row=document.querySelector('.bill-row[data-direction="expense"] .bill-check');
            if(!row) return false; row.click(); return true; })()"""
        )
        time.sleep(0.3)
        after = cdp.js("window.__RT_E2E_BILL.state().selected")
        check("取消勾选后选中数减 1", after == before - 1, f"{before} -> {after}")

        chapter("导出支出 Word")
        cdp.js("document.querySelector('[data-testid=\"bill-export-expense\"]').click(); true")
        cdp.wait_js(
            "(() => { const s=window.__RT_E2E_BILL.state();"
            " return s.resultError || (s.result && s.result.files.some(f=>f.direction==='expense')); })()",
            240,
        )
        err = cdp.js("window.__RT_E2E_BILL.state().resultError")
        if err:
            raise RuntimeError(f"导出支出明细失败：{err}")
        result = cdp.js("window.__RT_E2E_BILL.state().result")
        log(f"支出导出：{result.get('dir')} files={[(f.get('name'), f.get('count')) for f in result.get('files', [])]}")
        files = result.get("files") or []
        check("支出导出 1 个文件", len(files) == 1, str(len(files)))
        expense = files[0] if files else {}
        check("支出条数 428（493 - 24 不计收支 - 1 取消勾选 - 40 收入）", expense.get("count") == 428, str(expense.get("count")))
        check("支出文件存在", bool(expense.get("path")) and os.path.isfile(expense.get("path") or ""))
        size = os.path.getsize(expense["path"]) if expense.get("path") and os.path.isfile(expense["path"]) else 0
        check("支出 docx 大于 10KB", size > 10 * 1024, str(size))
        text = docx_text(expense["path"]) if size else ""
        check("支出文档包含模板首句「我一共转账」", "我一共转账" in text)
        cdp.js("(() => { const el=document.querySelector('[data-testid=\"bill-result\"]'); if(el) el.scrollIntoView({block:'center'}); return true; })()")
        time.sleep(0.3)
        cdp.shot("13-bill-export-expense.png")

        chapter("导出收入 Word")
        cdp.js("document.querySelector('[data-testid=\"bill-export-income\"]').click(); true")
        cdp.wait_js(
            "(() => { const s=window.__RT_E2E_BILL.state();"
            " return s.resultError || (s.result && s.result.files.some(f=>f.direction==='income')); })()",
            240,
        )
        err2 = cdp.js("window.__RT_E2E_BILL.state().resultError")
        if err2:
            raise RuntimeError(f"导出收入明细失败：{err2}")
        result2 = cdp.js("window.__RT_E2E_BILL.state().result")
        files2 = (result2 or {}).get("files") or []
        income = next((f for f in files2 if f.get("direction") == "income"), None)
        log(f"收入导出：{[(f.get('name'), f.get('count')) for f in files2]}")
        check("收入导出文件存在", bool(income) and os.path.isfile(income.get("path") or ""))
        check("收入条数 40", bool(income) and income.get("count") == 40, str(income.get("count") if income else None))
        if income:
            text2 = docx_text(income["path"])
            check("收入文档包含模板首句「我一共收到」", "我一共收到" in text2)
        cdp.shot("14-bill-export-income.png")

        chapter("模板区与清空按钮存在")
        has_template = cdp.js("!!document.querySelector('[data-testid=\"bill-template\"]')")
        check("自定义模板折叠区存在", bool(has_template))
        cdp.js("(() => { const b=document.querySelector('[data-testid=\"bill-template\"] .collapse-head'); if(b){b.click(); return true;} return false; })()")
        time.sleep(0.3)
        tpl_text = cdp.js("document.body.innerText") or ""
        check("模板说明包含 {{序号}}", "{{序号}}" in tpl_text)
        cdp.js("(() => { const el=document.querySelector('[data-testid=\"bill-template\"]'); if(el) el.scrollIntoView({block:'center'}); return true; })()")
        time.sleep(0.3)
        cdp.shot("15-bill-template.png")

        cdp.close()
        log("交易明细 E2E 完成")
        if failures:
            log(f"失败 {len(failures)} 项：" + "；".join(failures))
            return 1
        log("全部通过 ✅")
        return 0
    finally:
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
