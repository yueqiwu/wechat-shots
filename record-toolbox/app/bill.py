# -*- coding: utf-8 -*-
"""交易明细：解析微信支付 / 支付宝交易明细证明 PDF，并按模板导出 DOCX。

解析思路：PDF 是文字型表格，按「文字片段坐标」还原表格：
1. 每页用 pypdf 的 visitor 收集 (文字, x, y)；
2. 在第一页找到表头行，记录各列锚点 x；
3. 每页用「交易时间」列里的日期作为行锚点，把落在该行范围内的片段归到最近列；
4. 拼接被换行拆开的单元格文字，得到结构化明细。

导出思路：模板本身是一个 .docx（默认模板随应用附带，可自定义上传）：
- 含 {{序号}} 的段落 = 每条明细重复一次的行模板；
- 其余段落只渲染一次；
- 兼容 {{笔数}} {{总金额}} {{平台}} {{时间}} {{金额}} {{交易对方}} {{交易单号}} {{商品说明}} {{交易方式}} {{交易类型}} {{开始日期}} {{结束日期}} 占位符。
渲染时直接改写模板 docx 的 word/document.xml（其余部件原样保留），因此自定义模板的字体、字号、页边距都会被完整保留。
"""
from __future__ import annotations

import copy
import os
import re
import shutil
import time
import zipfile
from datetime import datetime
from decimal import Decimal, InvalidOperation
from io import BytesIO

import pypdf
from lxml import etree

from . import paths, settings

W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
W = "{%s}" % W_NS
XML_NS = "http://www.w3.org/XML/1998/namespace"

PLATFORM_WECHAT = "微信支付"
PLATFORM_ALIPAY = "支付宝"

DIR_INCOME = "income"
DIR_EXPENSE = "expense"
DIR_OTHER = "other"

TEMPLATE_KINDS = {
    DIR_INCOME: "收入明细模板.docx",
    DIR_EXPENSE: "支出明细模板.docx",
}

DATE_RE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})$")
TIME_RE = re.compile(r"^(\d{2}):(\d{2}):(\d{2})$")
FOOTER_RE = re.compile(r"^第\s*\d+\s*页\s*/\s*共\s*\d+\s*页$")
# 表格下方的说明/备注段落：出现在明细之后、通常从最左列开始的长句
NOTE_STRONG_RE = re.compile(r"^(?:说明|注[:：]|特别提示|备注[:：])")
NOTE_WEAK_RE = re.compile(r"^\d{1,2}[.、]\s*\S")
NOTE_MIN_LEN = 15


class BillError(Exception):
    """可展示给用户的错误。"""


# --------------------------------------------------------------------------
# 表头定义
# --------------------------------------------------------------------------

FORMATS = {
    "wechat": {
        "markers": ("微信支付交易明细证明", "交易单号"),
        "headers": [
            "交易单号", "交易时间", "交易类型", "收/支/其他",
            "交易方式", "金额(元)", "交易对方", "商户单号",
        ],
        "date_col": "交易时间",
        "fields": {
            "order_id": "交易单号",
            "time": "交易时间",
            "type": "交易类型",
            "direction": "收/支/其他",
            "method": "交易方式",
            "amount": "金额(元)",
            "counterparty": "交易对方",
            "merchant_order": "商户单号",
        },
    },
    "alipay": {
        "markers": ("交易流水证明", "收/付款方式"),
        "headers": [
            "收/支", "交易对方", "商品说明", "收/付款方式",
            "金额", "交易订单号", "商家订单号", "交易时间",
        ],
        "date_col": "交易时间",
        "fields": {
            "direction": "收/支",
            "counterparty": "交易对方",
            "product": "商品说明",
            "method": "收/付款方式",
            "amount": "金额",
            "order_id": "交易订单号",
            "merchant_order": "商家订单号",
            "time": "交易时间",
        },
    },
}


# --------------------------------------------------------------------------
# PDF 还原为表格
# --------------------------------------------------------------------------

def _page_fragments(page):
    out = []

    def visitor(text, cm, tm, font_dict, font_size):
        text = text.strip()
        if not text:
            return
        try:
            x = float(tm[4])
            y = float(tm[5])
        except (TypeError, IndexError):
            return
        out.append({"text": text, "x": x, "y": y})

    page.extract_text(visitor_text=visitor)
    return out


def _detect_format(pages_text):
    for key, spec in FORMATS.items():
        if all(marker in pages_text for marker in spec["markers"]):
            return key
    raise BillError("无法识别该 PDF：请上传「微信支付交易明细证明」或「支付宝交易流水证明」原文件。")


def _find_header(fragments, spec):
    """找到表头行，返回 (header_y, {列名: x})。"""
    by_y = {}
    for frag in fragments:
        key = round(frag["y"], 1)
        by_y.setdefault(key, []).append(frag)
    wanted = set(spec["headers"])
    for y in sorted(by_y, reverse=True):
        texts = {f["text"] for f in by_y[y]}
        if len(wanted & texts) >= len(wanted) - 1:
            anchors = {}
            for frag in by_y[y]:
                if frag["text"] in wanted:
                    anchors[frag["text"]] = frag["x"]
            missing = wanted - set(anchors)
            if missing:
                raise BillError("表头解析失败，缺少列：%s" % "、".join(sorted(missing)))
            return y, anchors
    raise BillError("PDF 中没有找到明细表头，可能不是受支持的交易明细证明。")


def _nearest_anchor(x, anchors):
    return min(anchors, key=lambda label: abs(anchors[label] - x))


def _join_cell(parts):
    """把换行拆开的单元格片段拼起来（按行从上到下、同一行从左到右）。"""
    if not parts:
        return ""
    parts = sorted(parts, key=lambda p: (-p["y"], p["x"]))
    return "".join(p["text"] for p in parts).strip()


def _to_amount(text):
    cleaned = text.replace(",", "").replace("￥", "").replace("元", "").strip()
    if not cleaned:
        return Decimal("0")
    try:
        return Decimal(cleaned)
    except InvalidOperation:
        raise BillError("金额格式无法识别：%r" % text)


def _split_datetime(text):
    """从交易时间单元格里拆出日期与时间（PDF 换行后两段会直接拼在一起）。"""
    m = re.search(r"(\d{4}-\d{2}-\d{2})", text)
    if not m:
        raise BillError("交易时间解析失败：%r" % text)
    date = m.group(1)
    m2 = re.search(r"(\d{2}:\d{2}:\d{2})", text)
    time = m2.group(1) if m2 else "00:00:00"
    return "%s %s" % (date, time)


def _page_rows(page, spec, anchors):
    fragments = _page_fragments(page)
    header_y = None
    if anchors is None:
        header_y, anchors = _find_header(fragments, spec)
    bottom = None
    for frag in fragments:
        if FOOTER_RE.match(frag["text"]):
            bottom = frag["y"] if bottom is None else max(bottom, frag["y"])
    date_col = spec["date_col"]

    usable = []
    for frag in fragments:
        if header_y is not None and frag["y"] >= header_y - 1.5:
            continue
        if bottom is not None and frag["y"] <= bottom + 1e-6:
            continue
        frag["col"] = _nearest_anchor(frag["x"], anchors)
        usable.append(frag)

    # 表格底部：明细之后若出现「说明：…」「1. 本证明…」等长段落，则整段不算表格内容
    note_top = None
    for frag in usable:
        text = frag["text"]
        matched = NOTE_STRONG_RE.match(text) or (len(text) >= NOTE_MIN_LEN and NOTE_WEAK_RE.match(text))
        if matched:
            if note_top is None or frag["y"] > note_top:
                note_top = frag["y"]
    if note_top is not None:
        usable = [frag for frag in usable if frag["y"] > note_top + 0.5]

    date_anchors = sorted(
        (f["y"] for f in usable if f["col"] == date_col and DATE_RE.match(f["text"])),
        reverse=True,
    )
    rows = []
    for idx, y0 in enumerate(date_anchors):
        y1 = date_anchors[idx + 1] if idx + 1 < len(date_anchors) else float("-inf")
        cells = {}
        for frag in usable:
            if y1 < frag["y"] <= y0 + 1.0:
                cells.setdefault(frag["col"], []).append(frag)
        rows.append({label: _join_cell(parts) for label, parts in cells.items()})
    return anchors, rows


def parse_pdf(path, platform=None):
    """解析单个 PDF，返回 (平台, 行列表)。"""
    try:
        reader = pypdf.PdfReader(path)
    except Exception as exc:  # noqa: BLE001
        raise BillError("PDF 打开失败：%s" % exc)
    if not reader.pages:
        raise BillError("PDF 内容为空。")
    first_text = reader.pages[0].extract_text() or ""
    fmt = platform or _detect_format(first_text)
    spec = FORMATS[fmt]
    anchors = None
    raw_rows = []
    for page in reader.pages:
        anchors, page_rows = _page_rows(page, spec, anchors)
        raw_rows.extend(page_rows)

    rows = []
    f = spec["fields"]
    for raw in raw_rows:
        direction = normalize_direction(raw.get(f["direction"], ""))
        time_text = _split_datetime(raw.get(f["time"], ""))
        amount = _to_amount(raw.get(f["amount"], ""))
        row = {
            "platform": PLATFORM_WECHAT if fmt == "wechat" else PLATFORM_ALIPAY,
            "time": time_text,
            "direction": direction,
            "amount": amount,
            "counterparty": raw.get(f["counterparty"], "").strip(),
            "method": raw.get(f["method"], "").strip(),
            "order_id": raw.get(f["order_id"], "").strip(),
            "merchant_order": raw.get(f["merchant_order"], "").strip(),
            "product": raw.get(f.get("product", ""), "").strip(),
            "type": raw.get(f.get("type", ""), "").strip(),
        }
        if not row["counterparty"] and not row["order_id"]:
            continue
        rows.append(row)
    rows.sort(key=lambda r: r["time"])
    deduped = []
    seen = set()
    for row in rows:
        key = (row["platform"], row["order_id"] or "%s|%s|%s" % (row["time"], row["amount"], row["counterparty"]))
        if key in seen:
            continue
        seen.add(key)
        deduped.append(row)
    return fmt, deduped


def normalize_direction(text):
    t = (text or "").replace(" ", "")
    if t in ("收入", "收"):
        return DIR_INCOME
    if t in ("支出", "支"):
        return DIR_EXPENSE
    if "不计" in t or t in ("其他", "不计收支"):
        return DIR_OTHER
    if not t:
        return DIR_OTHER
    return DIR_OTHER


def direction_label(direction):
    return {DIR_INCOME: "收入", DIR_EXPENSE: "支出", DIR_OTHER: "不计收支"}[direction]


# --------------------------------------------------------------------------
# 会话：解析结果（预览 / 导出）
# --------------------------------------------------------------------------

class BillSession:
    """当前会话里导入的明细：按来源（微信/支付宝）分别保存，重新导入会替换旧来源。"""

    def __init__(self):
        self.sources = {}  # kind -> {"name": str, "format": str, "rows": [row]}

    def clear(self):
        self.sources = {}

    def set_source(self, kind, name, fmt, rows):
        self.sources[kind] = {"name": name, "format": fmt, "rows": rows}

    def remove_source(self, kind):
        self.sources.pop(kind, None)

    @property
    def rows(self):
        merged = []
        for kind in ("wechat", "alipay"):
            source = self.sources.get(kind)
            if source:
                merged.extend(source["rows"])
        merged.sort(key=lambda r: r["time"])
        return merged

    def source_list(self):
        out = []
        for kind in ("wechat", "alipay"):
            source = self.sources.get(kind)
            if not source:
                continue
            out.append({
                "kind": kind,
                "name": source["name"],
                "format": source["format"],
                "rows": len(source["rows"]),
            })
        return out

    def summary(self):
        income = [r for r in self.rows if r["direction"] == DIR_INCOME]
        expense = [r for r in self.rows if r["direction"] == DIR_EXPENSE]
        other = [r for r in self.rows if r["direction"] == DIR_OTHER]
        return {
            "total": len(self.rows),
            "incomeCount": len(income),
            "incomeAmount": str(sum((r["amount"] for r in income), Decimal("0"))),
            "expenseCount": len(expense),
            "expenseAmount": str(sum((r["amount"] for r in expense), Decimal("0"))),
            "otherCount": len(other),
        }

    def preview(self):
        rows = []
        for idx, row in enumerate(self.rows):
            item = dict(row)
            item["id"] = idx
            item["amount"] = format_amount(row["amount"])
            item["directionLabel"] = direction_label(row["direction"])
            item["timeDisplay"] = format_time_display(row["time"])
            rows.append(item)
        return rows


SESSION = BillSession()


def format_amount(value):
    amount = value if isinstance(value, Decimal) else Decimal(str(value))
    text = format(amount, "f")
    if "." not in text:
        text += ".00"
    return text


def format_time_display(text):
    try:
        dt = datetime.strptime(text, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return text
    return "%d年%d月%d日%02d:%02d:%02d" % (dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second)


def format_time_cn(text):
    try:
        dt = datetime.strptime(text, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return text
    return "%d年%d月%d日%02d时%02d分%02d秒" % (dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second)


# --------------------------------------------------------------------------
# DOCX 模板渲染
# --------------------------------------------------------------------------

def _read_paragraph_text(p):
    chunks = []
    for node in p.iter(W + "t"):
        chunks.append(node.text or "")
    return "".join(chunks)


def _set_paragraph_text(p, text):
    """保留段落属性与第一个 run 的样式，把文字整体替换掉。"""
    runs = p.findall(W + "r")
    rpr = None
    if runs:
        first_rpr = runs[0].find(W + "rPr")
        if first_rpr is not None:
            rpr = copy.deepcopy(first_rpr)
        for run in runs:
            p.remove(run)
    for child in list(p):
        if child.tag in (W + "hyperlink", W + "fldSimple"):
            p.remove(child)
    run = etree.SubElement(p, W + "r")
    if rpr is not None:
        run.append(rpr)
    t = etree.SubElement(run, W + "t")
    if text.startswith(" ") or text.endswith(" "):
        t.set("{%s}space" % XML_NS, "preserve")
    t.text = text


def _fill_template_content(template_path, header_values, item_values):
    with zipfile.ZipFile(template_path) as zf:
        try:
            doc_xml = zf.read("word/document.xml")
        except KeyError:
            raise BillError("模板不是有效的 DOCX 文件：缺少 word/document.xml")
    root = etree.fromstring(doc_xml)
    body = root.find(W + "body")
    if body is None:
        raise BillError("模板 DOCX 结构异常：缺少 w:body")

    children = list(body)
    sect_pr = children[-1] if children and children[-1].tag == W + "sectPr" else None
    paragraphs = [c for c in children if c.tag == W + "p"]

    item_index = None
    for idx, p in enumerate(paragraphs):
        if "{{序号}}" in _read_paragraph_text(p):
            item_index = idx
            break
    if item_index is None:
        raise BillError("模板里缺少明细行占位符：请在模板中放一行包含 {{序号}} 的段落，用于逐条生成明细。")

    new_body = etree.Element(W + "body")
    nsmap = body.nsmap

    def append_header(p):
        text = _read_paragraph_text(p)
        clone = copy.deepcopy(p)
        rendered = _render_text(text, header_values)
        new_body.append(clone)
        if rendered != text:
            _set_paragraph_text(clone, rendered)

    for p in paragraphs[:item_index]:
        append_header(p)
    item_template = [p for p in paragraphs[item_index:] if "{{序号}}" in _read_paragraph_text(p)]
    if not item_template:
        raise BillError("模板里缺少明细行占位符。")
    item_p = item_template[0]
    for values in item_values:
        clone = copy.deepcopy(item_p)
        text = _read_paragraph_text(item_p)
        new_body.append(clone)
        _set_paragraph_text(clone, _render_text(text, values))
    for p in paragraphs[item_index:]:
        if p is item_p:
            continue
        append_header(p)
    if sect_pr is not None:
        new_body.append(copy.deepcopy(sect_pr))
    for key, value in nsmap.items():
        if key is not None:
            pass
    root.replace(body, new_body)
    new_doc = etree.tostring(root, xml_declaration=True, encoding="UTF-8", standalone=True)

    buffer = BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as out:
        with zipfile.ZipFile(template_path) as src:
            for info in src.infolist():
                data = src.read(info.filename)
                if info.filename == "word/document.xml":
                    data = new_doc
                out.writestr(info, data)
    return buffer.getvalue()


def _render_text(text, values):
    rendered = text
    for key, value in values.items():
        rendered = rendered.replace("{{%s}}" % key, value)
    return rendered


def build_docx(template_path, direction, rows, out_path):
    income = direction == DIR_INCOME
    total = sum((r["amount"] for r in rows), Decimal("0"))
    header_values = {
        "笔数": str(len(rows)),
        "总金额": format_amount(total),
        "开始日期": format_time_display(rows[0]["time"]).split(" ")[0] if rows else "",
        "结束日期": format_time_display(rows[-1]["time"]).split(" ")[0] if rows else "",
    }
    item_values = []
    for idx, row in enumerate(rows, start=1):
        values = {
            "序号": str(idx),
            "平台": row["platform"],
            "平台简称": PLATFORM_SHORT.get(row["platform"], row["platform"]),
            "时间": format_time_cn(row["time"]),
            "时间日期": row["time"][:10],
            "金额": format_amount(row["amount"]),
            "交易对方": row["counterparty"],
            "交易单号": row["order_id"],
            "商品说明": row["product"] or row["type"] or "—",
            "交易方式": row["method"],
            "交易类型": row["type"],
        }
        item_values.append(values)
    data = _fill_template_content(template_path, header_values, item_values)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "wb") as fh:
        fh.write(data)
    return out_path


# --------------------------------------------------------------------------
# 模板路径管理
# --------------------------------------------------------------------------
# --------------------------------------------------------------------------
# 模板与导出（给 api.py / 前端用）
# --------------------------------------------------------------------------

def _assets_template_dir():
    return os.path.join(paths.resource_path("assets"), "bill-templates")


def _custom_template_dir():
    return os.path.join(paths.data_dir(), "bill-templates")


def ensure_templates():
    """确保随附的默认模板存在（打包时 assets 里应已附带）。"""
    target = _assets_template_dir()
    os.makedirs(target, exist_ok=True)
    for kind, filename in TEMPLATE_KINDS.items():
        path = os.path.join(target, filename)
        if not os.path.isfile(path):
            _write_default_template(kind, path)
    return target


def _validate_template(path):
    try:
        with zipfile.ZipFile(path) as zf:
            data = zf.read("word/document.xml")
    except (KeyError, zipfile.BadZipFile):
        raise BillError("模板不是有效的 DOCX 文件，请上传 .docx 模板。")
    try:
        root = etree.fromstring(data)
    except etree.XMLSyntaxError:
        raise BillError("模板 DOCX 结构异常。")
    body = root.find(W + "body")
    if body is None:
        raise BillError("模板 DOCX 结构异常：缺少 w:body")
    for p in body.iter(W + "p"):
        if "{{序号}}" in _read_paragraph_text(p):
            return True
    raise BillError("模板里缺少明细行占位符 {{序号}}，请参考默认模板修改后再上传。")


def template_state():
    ensure_templates()
    out = {}
    for kind, filename in TEMPLATE_KINDS.items():
        builtin_path = os.path.join(_assets_template_dir(), filename)
        custom_path = os.path.join(_custom_template_dir(), filename)
        custom = os.path.isfile(custom_path)
        out[kind] = {
            "builtin": os.path.isfile(builtin_path),
            "custom": custom,
            "path": custom_path if custom else builtin_path,
            "updatedAt": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(os.path.getmtime(custom_path))) if custom else "",
        }
    return out


def resolve_template(direction):
    ensure_templates()
    custom_path = os.path.join(_custom_template_dir(), TEMPLATE_KINDS[direction])
    if os.path.isfile(custom_path):
        return custom_path, True
    return os.path.join(_assets_template_dir(), TEMPLATE_KINDS[direction]), False


def install_custom_template(direction, src_path):
    if direction not in TEMPLATE_KINDS:
        raise BillError("未知的模板类型")
    if not src_path or not os.path.isfile(src_path):
        raise BillError("模板文件不存在或已被移动")
    _validate_template(src_path)
    os.makedirs(_custom_template_dir(), exist_ok=True)
    shutil.copyfile(src_path, os.path.join(_custom_template_dir(), TEMPLATE_KINDS[direction]))
    return template_state()[direction]


def reset_custom_template(direction):
    if direction not in TEMPLATE_KINDS:
        raise BillError("未知的模板类型")
    path = os.path.join(_custom_template_dir(), TEMPLATE_KINDS[direction])
    if os.path.isfile(path):
        try:
            os.remove(path)
        except OSError as exc:
            raise BillError("恢复默认模板失败：%s" % exc)
    return template_state()[direction]


def custom_template_dir():
    os.makedirs(_custom_template_dir(), exist_ok=True)
    return _custom_template_dir()


def copy_builtin_template(direction, target_dir):
    if direction not in TEMPLATE_KINDS:
        raise BillError("未知的模板类型")
    ensure_templates()
    os.makedirs(target_dir, exist_ok=True)
    src = os.path.join(_assets_template_dir(), TEMPLATE_KINDS[direction])
    dst = os.path.join(target_dir, TEMPLATE_KINDS[direction])
    shutil.copyfile(src, dst)
    return dst


def _output_dir(payload):
    explicit = (payload.get("dir") or "").strip()
    if explicit and os.path.isdir(explicit):
        return explicit
    configured = (settings.load().get("output_dir") or "").strip()
    if configured and os.path.isdir(configured):
        return configured
    fallback = os.path.join(paths.data_dir(), "exports")
    os.makedirs(fallback, exist_ok=True)
    return fallback


def import_pdf(path, name, kind=None):
    """解析一份 PDF 并放进会话；kind=wechat/alipay 用于覆盖同来源的旧数据。"""
    fmt, rows = parse_pdf(path)
    if kind is None:
        kind = fmt
    if kind not in ("wechat", "alipay"):
        raise BillError("无法识别该 PDF：请上传微信支付或支付宝的交易明细证明。")
    if fmt != kind:
        raise BillError("上传的文件与所选来源不一致：这是「%s」的明细。" % (
            "微信支付" if fmt == "wechat" else "支付宝"))
    SESSION.set_source(kind, name or os.path.basename(path), fmt, rows)
    return {"kind": kind, "format": fmt, "name": name or os.path.basename(path), "rows": len(rows)}


def state():
    ensure_templates()
    rows = SESSION.preview()
    return {
        "ok": True,
        "hasData": bool(rows),
        "sources": SESSION.source_list(),
        "summary": SESSION.summary(),
        "rows": rows,
        "templates": template_state(),
        "outputDir": _output_dir({}),
        "placeholders": [
            "{{笔数}}", "{{总金额}}", "{{开始日期}}", "{{结束日期}}", "{{序号}}", "{{平台}}", "{{平台简称}}",
            "{{时间}}", "{{时间日期}}", "{{金额}}", "{{交易对方}}", "{{交易单号}}",
            "{{商品说明}}", "{{交易方式}}", "{{交易类型}}",
        ],
        "templateTip": "模板里包含 {{序号}} 的段落会按每条明细重复；其余段落只出现一次。",
    }


def export(payload):
    rows = SESSION.rows
    if not rows:
        raise BillError("还没有导入交易明细，请先上传微信/支付宝的明细证明 PDF。")
    exclude_ids = set()
    for value in payload.get("excludeIds") or []:
        try:
            exclude_ids.add(int(value))
        except (TypeError, ValueError):
            continue
    kept = [row for idx, row in enumerate(rows) if idx not in exclude_ids]
    directions = payload.get("directions") or [DIR_EXPENSE, DIR_INCOME]
    out_dir = _output_dir(payload)
    stamp = time.strftime("%Y%m%d_%H%M%S")
    files = []
    custom_used = False
    for direction in directions:
        if direction not in (DIR_INCOME, DIR_EXPENSE):
            continue
        subset = [row for row in kept if row["direction"] == direction]
        label = "收入" if direction == DIR_INCOME else "支出"
        if not subset:
            files.append({
                "direction": direction, "label": label, "count": 0, "amount": "0.00",
                "path": "", "name": "", "skipped": True,
            })
            continue
        template_path, is_custom = resolve_template(direction)
        custom_used = custom_used or is_custom
        name = "%s明细_%s.docx" % (label, stamp)
        target = os.path.join(out_dir, name)
        index = 1
        while os.path.exists(target):
            index += 1
            name = "%s明细_%s_%d.docx" % (label, stamp, index)
            target = os.path.join(out_dir, name)
        build_docx(template_path, direction, subset, target)
        files.append({
            "direction": direction,
            "label": label,
            "count": len(subset),
            "amount": format_amount(sum((row["amount"] for row in subset), Decimal("0"))),
            "path": target,
            "name": name,
            "skipped": False,
        })
    if not files:
        raise BillError("没有可导出的明细。")
    settings.save({"output_dir": out_dir})
    opened = False
    if not payload.get("noOpen"):
        try:
            os.startfile(out_dir)  # type: ignore[attr-defined]
            opened = True
        except OSError:
            pass
    exported = [item for item in files if not item["skipped"]]
    return {
        "ok": True,
        "dir": out_dir,
        "files": files,
        "opened": opened,
        "template": "custom" if custom_used else "builtin",
        "message": "已导出 %d 个文档" % len(exported),
    }


def read_template_text(path):
    """读取模板段落文字（测试/预览用）。"""
    with zipfile.ZipFile(path) as zf:
        data = zf.read("word/document.xml")
    root = etree.fromstring(data)
    body = root.find(W + "body")
    if body is None:
        return []
    return [_read_paragraph_text(p) for p in body.iter(W + "p")]


# --------------------------------------------------------------------------
# 模板与导出结束
# --------------------------------------------------------------------------


def builtin_template_dir(assets_dir):
    return os.path.join(assets_dir, "bill-templates")


def custom_template_path(kind, custom_dir):
    return os.path.join(custom_dir, TEMPLATE_KINDS[kind])


def ensure_builtin_templates(assets_dir):
    """默认模板缺失时自动生成（正常发布包里应已附带）。"""
    target = builtin_template_dir(assets_dir)
    os.makedirs(target, exist_ok=True)
    for kind, filename in TEMPLATE_KINDS.items():
        path = os.path.join(target, filename)
        if not os.path.isfile(path):
            _write_default_template(kind, path)
    return target


DEFAULT_TEXTS = {
    DIR_INCOME: [
        "我一共收到{{笔数}}笔，共计{{总金额}}元。",
        "{{序号}}、{{时间}}，对方向我{{平台简称}}转账{{金额}}元，交易对方：{{交易对方}}，交易单号：{{交易单号}}。",
    ],
    DIR_EXPENSE: [
        "我一共转账{{笔数}}笔，共计{{总金额}}元。",
        "{{序号}}、{{时间}}，我通过{{平台简称}}向对方转账{{金额}}元，交易对方：{{交易对方}}，交易单号：{{交易单号}}。",
    ],
}

PLATFORM_SHORT = {
    PLATFORM_WECHAT: "微信",
    PLATFORM_ALIPAY: "支付宝",
}


def _write_default_template(kind, path):
    """用极简 docx（宋体小四、1.5 倍行距、首行缩进 2 字符）生成默认模板。"""
    texts = DEFAULT_TEXTS[kind]
    paragraphs = []
    for text in texts:
        escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        paragraphs.append(
            '<w:p><w:pPr><w:spacing w:line="360" w:lineRule="auto"/>'
            '<w:ind w:firstLineChars="200"/><w:jc w:val="both"/></w:pPr>'
            '<w:r><w:rPr><w:rFonts w:ascii="宋体" w:eastAsia="宋体" w:hAnsi="宋体"/>'
            '<w:sz w:val="24"/><w:szCs w:val="24"/></w:rPr>'
            '<w:t xml:space="preserve">%s</w:t></w:r></w:p>' % escaped
        )
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="%s"><w:body>%s'
        '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
        '<w:pgMar w:top="1440" w:right="1800" w:bottom="1440" w:left="1800" '
        'w:header="851" w:footer="992" w:gutter="0"/></w:sectPr>'
        '</w:body></w:document>' % (W_NS, "".join(paragraphs))
    )
    content_types = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/word/document.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        '</Types>'
    )
    rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="word/document.xml"/></Relationships>'
    )
    doc_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>'
    )
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("[Content_Types].xml", content_types)
        zf.writestr("_rels/.rels", rels)
        zf.writestr("word/document.xml", document)
        zf.writestr("word/_rels/document.xml.rels", doc_rels)
