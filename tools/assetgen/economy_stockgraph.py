"""
The stock chart font (economy:stockgraph): candles drawn in the /주식 window title, no client mod.

For every top row t (0..H-1) of the chart and every height h (1..H) there is a glyph: a bar h pixels tall
whose top is row t. Four sets: up body (3 px, red), up wick (1 px), down body (3 px, blue), down wick
(Korean stock colours).
All providers of a set share one strip image (bars of height 1..H); only the ascent differs. Glyph
code = 0xE000 + (set * H + t) * H + h - 1, and spaces 0xF001.. move the pen by +-1..128 px. The layout
must match economy/src/main/java/dev/stsv/economy/stock/StockGraph.java.
"""

import json
from pathlib import Path

from PIL import Image

H, TOP, BASE, SPACE = 32, 20, 0xE000, 0xF000
SETS = [  # (name, width, colours from the lit left column to the shaded right one)
    # Korean stock colours: red up, blue down
    ("up_body", 3, [(255, 128, 118), (232, 72, 66), (170, 40, 44)]),
    ("up_wick", 1, [(232, 96, 92)]),
    ("down_body", 3, [(128, 182, 255), (72, 140, 246), (40, 92, 178)]),
    ("down_wick", 1, [(96, 156, 240)]),
]


def strip(width, colours):
    im = Image.new("RGBA", (H * width, H), (0, 0, 0, 0))
    for h in range(1, H + 1):
        x0 = (h - 1) * width
        for y in range(h):
            for c in range(width):
                r, g, b = colours[c]
                if width > 1 and (y == 0 and h > 2):        # a lit top edge on taller bodies
                    r, g, b = min(255, r + 20), min(255, g + 20), min(255, b + 20)
                im.putpixel((x0 + c, y), (r, g, b, 255))
    return im


def build(assets: Path):
    font_dir = assets / "economy" / "font"
    tex_dir = assets / "economy" / "textures" / "font"
    font_dir.mkdir(parents=True, exist_ok=True)
    tex_dir.mkdir(parents=True, exist_ok=True)
    advances = {}
    for bit in range(8):
        advances[chr(SPACE + 1 + bit)] = 1 << bit
        advances[chr(SPACE + 17 + bit)] = -(1 << bit)
    providers = [{"type": "space", "advances": advances}]
    for s, (name, width, colours) in enumerate(SETS):
        strip(width, colours).save(tex_dir / f"stockgraph_{name}.png")
        for t in range(H):
            chars = "".join(chr(BASE + (s * H + t) * H + h - 1) for h in range(1, H + 1))
            # the title is drawn at y 6 with a 7 px ascent: a glyph's top is at 13 - ascent
            providers.append({"type": "bitmap", "file": f"economy:font/stockgraph_{name}.png",
                              "ascent": 13 - (TOP + t), "height": H, "chars": [chars]})
    (font_dir / "stockgraph.json").write_text(json.dumps({"providers": providers}, ensure_ascii=True))
    return len(providers)


if __name__ == "__main__":
    print(build(Path(__file__).resolve().parents[2] / "economy" / "content" / "economy" / "assets"))
