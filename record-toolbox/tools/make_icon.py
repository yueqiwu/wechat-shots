"""生成笔录工具箱的应用图标（iOS 风蓝色渐变圆角方块 + 白色气泡）。

产出：
  assets/logo.png    512×512 透明背景（用于网页/关于页）
  assets/icon.ico    多尺寸（256/128/64/48/32/16），用于 Windows 窗口与 exe
  assets/icon.png    256×256 预览用
"""
from __future__ import annotations

import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "assets")

S = 1024  # 超采样画布


def _lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient_canvas(size: int, c0=(10, 132, 255), c1=(34, 211, 238)) -> Image.Image:
    """左上 → 右下 的对角渐变。"""
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2.0 * (size - 1))
            px[x, y] = _lerp(c0, c1, t)
    return img


def squircle_mask(size: int, radius_ratio: float = 0.225) -> Image.Image:
    """iOS 风格的圆角方形遮罩（用较大的圆角近似 superellipse）。"""
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    r = int(size * radius_ratio)
    d.rounded_rectangle((0, 0, size - 1, size - 1), radius=r, fill=255)
    return mask


def speech_bubble_alpha(size: int) -> Image.Image:
    """白色对话气泡的 alpha 遮罩：圆角矩形 + 左下尾巴。"""
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    u = size / 100.0  # 便于按百分比定位
    left, top, right, bottom = 16 * u, 25 * u, 84 * u, 73 * u
    d.rounded_rectangle((left, top, right, bottom), radius=15 * u, fill=255)
    # 左下尾巴（保持在圆角遮罩安全区内）
    tail = [
        (left + 5 * u, bottom - 9 * u),
        (left + 5 * u, bottom + 11 * u),
        (left + 24 * u, bottom - 5 * u),
    ]
    d.polygon(tail, fill=255)
    # 尾巴与主体之间的圆角过渡
    d.ellipse((left + 1 * u, bottom - 13 * u, left + 13 * u, bottom - 1 * u), fill=255)
    return mask


def make_logo(size: int = 512) -> Image.Image:
    base = gradient_canvas(S)
    # 气泡
    bubble = speech_bubble_alpha(S)
    base.paste(Image.new("RGB", (S, S), (255, 255, 255)), (0, 0), bubble)
    # 气泡内三条蓝色文字条
    d = ImageDraw.Draw(base)
    u = S / 100.0
    line_color = (10, 132, 255)
    rows = [(28, 36, 0.62), (28, 46, 0.74), (28, 56, 0.46)]  # x, y, 宽度占比
    for x_pct, y_pct, w_pct in rows:
        x0 = x_pct * u
        x1 = (x_pct + 46 * w_pct) * u
        y0 = y_pct * u
        d.rounded_rectangle((x0, y0, x1, y0 + 7.5 * u), radius=4 * u, fill=line_color)
    # 圆角遮罩
    mask = squircle_mask(S)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


def main() -> None:
    os.makedirs(ASSETS, exist_ok=True)
    logo = make_logo(S)
    logo.save(os.path.join(ASSETS, "logo.png"))
    logo.resize((256, 256), Image.LANCZOS).save(os.path.join(ASSETS, "icon.png"))
    logo.save(
        os.path.join(ASSETS, "icon.ico"),
        sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)],
    )
    print("生成完成：")
    for name in ("logo.png", "icon.png", "icon.ico"):
        p = os.path.join(ASSETS, name)
        print(f"  {p} {os.path.getsize(p)} bytes")


if __name__ == "__main__":
    main()
