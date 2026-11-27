"""生成静态方案 E2E 用的合成截图序列。

思路：从旧的网页版样例输出（连续模式_129张截图.zip）取若干相邻页，
先把它们拼成一张「完整长图」，再按 90% 步长裁出高度 H 的窗口，
得到一组相邻重叠约 10% 的截图 —— 与真机滚动截图的数据形态一致。
"""

from __future__ import annotations

import io
import os
import zipfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ZIP = os.path.join(os.path.dirname(ROOT), "wechat-shots", "样例输出", "连续模式_129张截图.zip")
OUT = os.path.join(ROOT, "test-artifacts", "e2e", "shots")


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    with zipfile.ZipFile(ZIP) as z:
        pages = []
        for i in range(3, 9):  # 取中间连续 6 页，内容较丰富
            with z.open(f"shot_{i:03d}.png") as f:
                pages.append(Image.open(io.BytesIO(f.read())).convert("RGB"))
    width = pages[0].width
    total_h = sum(p.height for p in pages)
    master = Image.new("RGB", (width, total_h), (255, 255, 255))
    y = 0
    for p in pages:
        master.paste(p, (0, y))
        y += p.height

    cap_h = 1200
    step = int(round(cap_h * 0.9))  # 保留约 10% 重叠
    count = 0
    y = 0
    while y + cap_h <= total_h and count < 12:
        shot = master.crop((0, y, width, y + cap_h))
        shot.save(os.path.join(OUT, f"shot_{count + 1:02d}.png"))
        count += 1
        y += step

    master.save(os.path.join(ROOT, "test-artifacts", "e2e", "shots-master.png"))
    coverage = (count - 1) * step + cap_h
    with open(os.path.join(OUT, "meta.txt"), "w", encoding="utf-8") as f:
        f.write(f"page_count={count}\nwidth={width}\nheight={total_h}\ncap_h={cap_h}\nstep={step}\ncoverage={coverage}\n")
    print(f"生成 {count} 张合成截图 -> {OUT}（长图 {width}x{total_h}，截图覆盖 {coverage}px）")


if __name__ == "__main__":
    main()
