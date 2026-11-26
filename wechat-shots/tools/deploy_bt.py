"""Push the static app to the Baota-hosted site.

Usage:  set BT_KEY=<panel api key>  &&  python tools/deploy_bt.py
Target: https://qipaclub.top/wechat-shots/  (root C:/wwwroot/qipaclub.top)
"""
import os, sys, json, time, hashlib, urllib.request, urllib.parse

BASE = os.environ.get("BT_URL", "http://43.142.178.233:8888")
KEY = os.environ.get("BT_KEY", "")
ROOT = "C:/wwwroot/qipaclub.top/wechat-shots"

FILES = [
    ("app/index.html", "index.html"),
    ("app/style.css", "style.css"),
    ("app/js/core.js", "js/core.js"),
    ("app/js/pipeline.js", "js/pipeline.js"),
    ("app/js/app.js", "js/app.js"),
    ("app/js/zip.js", "js/zip.js"),
]

# the sample-video link only works locally (input.mp4 is not deployed)
SAMPLE_LINK = ('    <p class="dz-next"><a href="#" id="btnSample">直接载入示例录屏试试</a></p>\r\n',
               '    <p class="dz-next"><a href="#" id="btnSample">直接载入示例录屏试试</a></p>\n')


def call(path, params=None, data=None, timeout=40):
    if not KEY:
        sys.exit("BT_KEY is not set")
    p = dict(params or {})
    t = str(int(time.time()))
    p["request_time"] = t
    p["request_token"] = hashlib.md5((t + hashlib.md5(KEY.encode()).hexdigest()).encode()).hexdigest()
    url = BASE + path + ("&" if "?" in path else "?") + urllib.parse.urlencode(p)
    body = None
    if data is not None:
        q = dict(data)
        q["request_time"] = t
        q["request_token"] = p["request_token"]
        body = urllib.parse.urlencode(q).encode()
    req = urllib.request.Request(url, data=body, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def main():
    for src, dst in FILES:
        text = open(src, "rb").read().decode("utf-8")
        if dst == "index.html":
            for cand in SAMPLE_LINK:
                if cand in text:
                    text = text.replace(cand, "")
                    break
        remote = ROOT + "/" + dst
        call("/files?action=CreateFile", {"path": remote}, {"path": remote, "content": ""})
        call("/files?action=SaveFileBody", {"path": remote}, {"path": remote, "data": text, "encoding": "utf-8"})
        print("uploaded %-16s %6d bytes" % (dst, len(text.encode("utf-8"))))


if __name__ == "__main__":
    main()
