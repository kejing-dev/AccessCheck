"""WCAG contrast of rendered UI: sample a background box and a foreground box in a screenshot.

    python -I tools/contrast.py shot.png "name:bx0,by0,bx1,by1:fx0,fy0,fx1,fy1" ...

The background is the median colour of its box; the foreground is the pixel of the foreground box that is
farthest in luminance from that background (the glyph / icon stroke core, not its anti-aliased edge).
Coordinates are in the screenshot's own pixels. Prints name, background, foreground and the ratio.
"""
import sys
import numpy as np
from PIL import Image


def lum(rgb):
    c = np.asarray(rgb, dtype=float) / 255.0
    c = np.where(c <= 0.03928, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    return 0.2126 * c[..., 0] + 0.7152 * c[..., 1] + 0.0722 * c[..., 2]


def ratio(a, b):
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


def box(img, s):
    x0, y0, x1, y1 = (int(v) for v in s.split(","))
    return img[y0:y1, x0:x1].reshape(-1, 3)


def main():
    img = np.asarray(Image.open(sys.argv[1]).convert("RGB"))
    for spec in sys.argv[2:]:
        name, bg_s, fg_s = spec.split(":")
        bg = np.median(box(img, bg_s), axis=0)
        fg_px = box(img, fg_s)
        lb = lum(bg)
        lf = lum(fg_px)
        fg = fg_px[int(np.argmax(np.abs(lf - lb)))]
        r = ratio(lb, lum(fg))
        hexc = lambda c: "#%02X%02X%02X" % tuple(int(round(v)) for v in c)
        print(f"{name:28s} bg {hexc(bg)}  fg {hexc(fg)}  {r:5.2f}:1")


if __name__ == "__main__":
    main()
