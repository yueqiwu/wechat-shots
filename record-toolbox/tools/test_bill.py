# -*- coding: utf-8 -*-
"""交易明细格式化功能自测：解析真实 PDF、模板渲染、导出、HTTP 接口全链路。

运行：
    cd record-toolbox
    python tools/test_bill.py

真实 PDF 不存在时（例如换一台机器）自动跳过 PDF 解析相关断言，只测模板与导出。
"""
from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import threading
import urllib.request
from urllib.parse import quote

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from app import bill, server  # noqa: E402
from app import api  # noqa: E402

WECHAT_PDF = (
    r"E:\wechatrecord\xwechat_files\wxid_gn19xayz7un121_2fe9\msg\file\2026-11"
    r"\微信支付交易明细证明(20260101-20261129)_20261129005557.pdf"
)
ALIPAY_PDF = (
    r"E:\wechatrecord\xwechat_files\wxid_gn19xayz7un121_2fe9\msg\file\2026-11"
    r"\支付宝交易明细(20260101-20261129).pdf"
)
SAMPLE_TEMPLATE = r"C:\Users\Administrator\Desktop\交易明细模板.docx"

passed = 0
failed = 0


def check(name, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print("  [ok] %s%s" % (name, (" " + detail) if detail else ""))
    else:
        failed += 1
        print("  [FAIL] %s %s" % (name, detail))


def test_templates():
    print("[1] 默认模板")
    folder = bill.ensure_templates()
    check("模板目录存在", os.path.isdir(folder), folder)
    for kind, filename in bill.TEMPLATE_KINDS.items():
        path = os.path.join(folder, filename)
        check("默认模板 %s" % filename, os.path.isfile(path) and os.path.getsize(path) > 500,
              "%d bytes" % (os.path.getsize(path) if os.path.isfile(path) else 0))
        texts = bill.read_template_text(path)
        check("模板 %s 有说明段" % filename, len(texts) >= 2 and "{{笔数}}" in texts[0])
        check("模板 %s 有明细行" % filename, any("{{序号}}" in t for t in texts))
    state = bill.template_state()
    check("template_state 两项", set(state) == {"income", "expense"})
    check("默认未启用自定义模板", not state["income"]["custom"] and not state["expense"]["custom"])


def test_parse_pdfs():
    print("[2] 解析真实 PDF")
    if not (os.path.isfile(WECHAT_PDF) and os.path.isfile(ALIPAY_PDF)):
        print("  [skip] 本机没有样例 PDF，跳过")
        return False
    fmt, rows = bill.parse_pdf(WECHAT_PDF)
    check("微信格式识别", fmt == "wechat")
    check("微信行数 301", len(rows) == 301, "got %d" % len(rows))
    check("微信方向统计", sum(1 for r in rows if r["direction"] == "expense") == 269)
    check("微信收入条数 31", sum(1 for r in rows if r["direction"] == "income") == 31)
    check("微信最后一行未被说明污染",
          "说明" not in rows[-1]["order_id"] and rows[-1]["order_id"].isdigit())
    check("微信按时间升序", all(rows[i]["time"] <= rows[i + 1]["time"] for i in range(len(rows) - 1)))

    fmt, rows = bill.parse_pdf(ALIPAY_PDF)
    check("支付宝格式识别", fmt == "alipay")
    check("支付宝行数 192", len(rows) == 192, "got %d" % len(rows))
    check("支付宝有收入", any(r["direction"] == "income" for r in rows))
    check("支付宝有支出", any(r["direction"] == "expense" for r in rows))
    check("支付宝有商品说明", any(r["product"] for r in rows))
    return True


def test_export():
    print("[3] 导出 DOCX")
    bill.SESSION.clear()
    bill.SESSION.set_source("wechat", "微信支付交易明细证明.pdf", "wechat", [
        {"platform": "微信支付", "time": "2026-01-01 07:31:18", "direction": "income",
         "amount": bill.Decimal("21.50"), "counterparty": "中铁网络", "order_id": "5030280598",
         "merchant_order": "", "product": "", "type": "退款", "method": "零钱"},
        {"platform": "微信支付", "time": "2026-01-02 09:40:03", "direction": "expense",
         "amount": bill.Decimal("8.00"), "counterparty": "旅途易购", "order_id": "4200002918",
         "merchant_order": "", "product": "", "type": "商户消费", "method": "零钱"},
        {"platform": "微信支付", "time": "2026-01-03 10:00:00", "direction": "other",
         "amount": bill.Decimal("100.00"), "counterparty": "零钱通", "order_id": "4200003000",
         "merchant_order": "", "product": "", "type": "零钱通", "method": "零钱通"},
    ])
    out = os.path.join(tempfile.gettempdir(), "rtb-bill-test")
    os.makedirs(out, exist_ok=True)
    result = bill.export({"directions": ["expense", "income"], "dir": out, "noOpen": True})
    check("导出成功", result.get("ok") and len([f for f in result["files"] if not f["skipped"]]) == 2)
    expense = [f for f in result["files"] if f["direction"] == "expense"][0]
    income = [f for f in result["files"] if f["direction"] == "income"][0]
    check("支出文档存在", os.path.isfile(expense["path"]) and os.path.getsize(expense["path"]) > 500)
    check("支出只含支出 1 条", expense["count"] == 1 and expense["amount"] == "8.00")
    check("不计收支未导出", all("零钱通" not in t for t in bill.read_template_text(expense["path"])))
    texts = bill.read_template_text(expense["path"])
    check("支出说明段", "我一共转账1笔，共计8.00元。" in texts[0], texts[0])
    check("支出明细行含平台", "我通过微信向对方转账8.00元" in texts[1], texts[1])
    check("支出明细行含交易单号", "4200002918" in texts[1])
    itexts = bill.read_template_text(income["path"])
    check("收入说明段", "我一共收到1笔，共计21.50元。" in itexts[0], itexts[0])
    check("收入明细行", "对方向我微信转账21.50元" in itexts[1], itexts[1])
    check("时间格式带补零", "2026年1月2日09时40分03秒" in texts[1], texts[1])

    # excludeIds：未勾选的行不导出
    result = bill.export({"directions": ["expense"], "dir": out, "noOpen": True, "excludeIds": [1]})
    check("excludeIds 生效", result["files"][0]["skipped"] is True)
    return result


def test_custom_template():
    print("[4] 自定义模板")
    builtin_dir = bill.ensure_templates()
    src = os.path.join(builtin_dir, bill.TEMPLATE_KINDS["expense"])
    info = bill.install_custom_template("expense", src)
    check("安装自定义模板", info["custom"] is True, info.get("updatedAt", ""))
    state = bill.template_state()
    check("template_state 反映自定义", state["expense"]["custom"] is True)

    # 默认模板里文案改成可断言的自定义文案
    custom_dir = bill.custom_template_dir()
    target = os.path.join(custom_dir, bill.TEMPLATE_KINDS["expense"])
    texts = bill.read_template_text(target)
    texts[0] = "【自定义】共{{笔数}}笔合计{{总金额}}元"
    # 直接改 XML 文字：用 bill 内部函数重建
    import zipfile  # noqa: PLC0415
    from lxml import etree  # noqa: PLC0415
    with zipfile.ZipFile(target) as zf:
        data = zf.read("word/document.xml")
    root = etree.fromstring(data)
    body = root.find(bill.W + "body")
    paragraphs = [p for p in body.iter(bill.W + "p")]
    texts_all = [bill._read_paragraph_text(p) for p in paragraphs]
    for p, old_text in zip(paragraphs, texts_all):
        if "{{笔数}}" in old_text:
            bill._set_paragraph_text(p, texts[0])
            break
    new_doc = etree.tostring(root, xml_declaration=True, encoding="UTF-8", standalone=True)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as out_zf:
        with zipfile.ZipFile(target) as src_zf:
            for info in src_zf.infolist():
                blob = src_zf.read(info.filename)
                if info.filename == "word/document.xml":
                    blob = new_doc
                out_zf.writestr(info, blob)
    with open(target, "wb") as fh:
        fh.write(buffer.getvalue())

    result = bill.export({"directions": ["expense"], "dir": os.path.join(tempfile.gettempdir(), "rtb-bill-test"),
                          "noOpen": True})
    check("自定义模板被使用", result["template"] == "custom")
    custom_texts = bill.read_template_text(result["files"][0]["path"])
    check("自定义说明段生效", custom_texts[0] == "【自定义】共1笔合计8.00元", custom_texts[0])

    reset = bill.reset_custom_template("expense")
    check("恢复默认模板", reset["custom"] is False)
    if os.path.isfile(SAMPLE_TEMPLATE):
        try:
            bill.install_custom_template("expense", SAMPLE_TEMPLATE)
            check("样例模板应被拒绝", False)
        except bill.BillError as exc:
            check("样例模板被拒绝", "{{序号}}" in str(exc), str(exc))


def test_http():
    print("[5] HTTP 接口")
    bill.SESSION.clear()
    httpd = server.create("127.0.0.1", 0)
    port = server.serve_in_thread(httpd)
    base = "http://127.0.0.1:%d" % port
    try:
        def post(action, payload):
            req = urllib.request.Request(
                base + "/api/" + action,
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))

        state = post("bill.state", {})
        check("空状态 hasData=false", state["hasData"] is False)
        check("空状态返回模板信息", state["templates"]["income"]["builtin"] is True)

        if os.path.isfile(WECHAT_PDF) and os.path.isfile(ALIPAY_PDF):
            for kind, path in (("wechat", WECHAT_PDF), ("alipay", ALIPAY_PDF)):
                with open(path, "rb") as fh:
                    raw = fh.read()
                req = urllib.request.Request(
                    base + "/api/upload_bill",
                    data=raw,
                    headers={"X-Bill-Kind": kind, "X-File-Name": quote(os.path.basename(path))},
                    method="POST",
                )
                with urllib.request.urlopen(req, timeout=120) as resp:
                    payload = json.loads(resp.read().decode("utf-8"))
                check("上传 %s 成功" % kind, payload.get("ok") is True, payload.get("error", ""))
                check("上传 %s 行数" % kind, payload["imported"]["rows"] == (301 if kind == "wechat" else 192),
                      str(payload["imported"]["rows"]))
            state = post("bill.state", {})
            check("状态含两个来源", len(state["sources"]) == 2)
            check("合计 493 条", state["summary"]["total"] == 493, str(state["summary"]["total"]))
            out = os.path.join(tempfile.gettempdir(), "rtb-bill-http")
            result = post("bill.export", {"directions": ["expense", "income"], "dir": out, "noOpen": True})
            check("HTTP 导出成功", result.get("ok") and len([f for f in result["files"] if not f["skipped"]]) == 2)
            exported = sum(f["count"] for f in result["files"])
            check("HTTP 导出条数（不含不计收支）", exported == 469, "%s" % exported)
        else:
            print("  [skip] 无样例 PDF，跳过上传链路")

        cleared = post("bill.clear", {})
        check("清空会话", cleared["state"]["hasData"] is False)
    finally:
        httpd.shutdown()
        httpd.server_close()


def main():
    print("=" * 60)
    print("交易明细格式化 · 自测")
    print("=" * 60)
    test_templates()
    has_pdf = test_parse_pdfs()
    test_export()
    test_custom_template()
    test_http()
    print("=" * 60)
    print("PASSED %d / FAILED %d" % (passed, failed))
    if failed:
        print("BILL TESTS FAILED")
        return 1
    print("ALL BILL TESTS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
