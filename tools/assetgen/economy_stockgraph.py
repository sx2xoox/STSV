"""
The stock chart font (economy:stockgraph): the candle chart drawn in the /주식 window title, no client mod.

Candles: for every top row t (0..H-1) of the chart and every height h (1..H) there is a glyph, a bar h
pixels tall whose top is row t. Four sets: up body (5 px, red), up wick (1 px), down body (5 px, blue),
down wick (Korean stock colours). All providers of a set share one strip image (bars of height 1..H); only
the ascent differs. Glyph code = 0xE000 + (set * H + t) * H + h - 1.

The last price: a dashed line across the chart at its row and a tag with the price at the right end.
Codes from 0xF100: dash (3 px on, up / down colour) at 0xF100 + t and 0xF120 + t, a 1 px column of the
tag (7 px tall, top row t) at 0xF140 + t and 0xF160 + t, the tag's white digits and comma (3 x 5, top
row t) at 0xF200 + t * 16 + d (d 0..9, 10 = comma).

Spaces 0xF001.. move the pen by +-1..128 px. The layout must match
economy/src/main/java/dev/stsv/economy/stock/StockGraph.java.
"""

import json
from pathlib import Path

from PIL import Image

H, TOP, BASE, SPACE, EXTRA = 32, 20, 0xE000, 0xF000, 0xF100
TAG_H = 7
BODY = 5

# Korean stock colours: red up, blue down. A body is lit on its left, shaded on its right, with a lighter
# top and a darker bottom edge once it is tall enough to show them.
UP = {"light": (255, 134, 124), "base": (232, 72, 66), "shade": (192, 50, 50), "edge": (150, 34, 40), "wick": (232, 96, 92),
      "dash": (214, 88, 84), "tag": (220, 62, 58)}
DOWN = {"light": (134, 188, 255), "base": (72, 140, 246), "shade": (50, 106, 204), "edge": (32, 72, 150), "wick": (100, 158, 240),
        "dash": (92, 144, 236), "tag": (58, 124, 228)}
WHITE = (250, 250, 246)

SETS = [("up_body", BODY, UP), ("up_wick", 1, UP), ("down_body", BODY, DOWN), ("down_wick", 1, DOWN)]

DIGITS = {
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["##.", "..#", ".#.", "#..", "###"],
    "3": ["##.", "..#", ".#.", "..#", "##."],
    "4": ["#.#", "#.#", "###", "..#", "..#"],
    "5": ["###", "#..", "##.", "..#", "##."],
    "6": [".##", "#..", "###", "#.#", "###"],
    "7": ["###", "..#", ".#.", ".#.", ".#."],
    "8": ["###", "#.#", "###", "#.#", "###"],
    "9": ["###", "#.#", "###", "..#", "##."],
    ",": ["...", "...", "...", "#..", "#.."],   # one pixel wide, so its advance is 2
}
CHARS = "0123456789,"


def strip(width, c):
    """Bars of height 1..H side by side, each `width` wide."""
    im = Image.new("RGBA", (H * width, H), (0, 0, 0, 0))
    for h in range(1, H + 1):
        x0 = (h - 1) * width
        for y in range(h):
            for k in range(width):
                if width == 1:
                    col = c["wick"]
                else:
                    col = c["light"] if k == 0 else c["shade"] if k == width - 1 else c["base"]
                    if h >= 3 and y == 0:
                        col = c["light"]
                    elif h >= 3 and y == h - 1:
                        col = c["edge"]
                im.putpixel((x0 + k, y), col + (255,))
    return im


def build(assets: Path):
    font_dir = assets / "economy" / "font"
    tex_dir = assets / "economy" / "textures" / "font"
    font_dir.mkdir(parents=True, exist_ok=True)
    tex_dir.mkdir(parents=True, exist_ok=True)
    for old in tex_dir.glob("stockgraph_*.png"):
        old.unlink()
    advances = {}
    for bit in range(8):
        advances[chr(SPACE + 1 + bit)] = 1 << bit
        advances[chr(SPACE + 17 + bit)] = -(1 << bit)
    providers = [{"type": "space", "advances": advances}]

    def bitmap(file, top, height, chars):
        # the title is drawn at y 6 with a 7 px ascent: a glyph's top is at 13 - ascent
        providers.append({"type": "bitmap", "file": f"economy:font/{file}.png", "ascent": 13 - (TOP + top),
                          "height": height, "chars": [chars]})

    # candles
    for s, (name, width, colours) in enumerate(SETS):
        strip(width, colours).save(tex_dir / f"stockgraph_{name}.png")
        for t in range(H):
            bitmap(f"stockgraph_{name}", t, H, "".join(chr(BASE + (s * H + t) * H + h - 1) for h in range(1, H + 1)))
    # the last price: dashes, the tag's columns, its digits
    for k, (name, c) in enumerate((("up", UP), ("down", DOWN))):
        dash = Image.new("RGBA", (3, 1), c["dash"] + (255,))
        dash.save(tex_dir / f"stockgraph_dash_{name}.png")
        column = Image.new("RGBA", (1, TAG_H), c["tag"] + (255,))
        column.save(tex_dir / f"stockgraph_tag_{name}.png")
        for t in range(H):
            bitmap(f"stockgraph_dash_{name}", t, 1, chr(EXTRA + k * 32 + t))
        for t in range(H - TAG_H + 1):
            bitmap(f"stockgraph_tag_{name}", t, TAG_H, chr(EXTRA + 64 + k * 32 + t))
    digits = Image.new("RGBA", (3 * len(CHARS), 5), (0, 0, 0, 0))
    for i, ch in enumerate(CHARS):
        for y, row in enumerate(DIGITS[ch]):
            for x, v in enumerate(row):
                if v == "#":
                    digits.putpixel((i * 3 + x, y), WHITE + (255,))
    digits.save(tex_dir / "stockgraph_digits.png")
    for t in range(H - 4):
        bitmap("stockgraph_digits", t, 5, "".join(chr(EXTRA + 0x100 + t * 16 + d) for d in range(len(CHARS))))
    (font_dir / "stockgraph.json").write_text(json.dumps({"providers": providers}, ensure_ascii=True))
    return len(providers)


if __name__ == "__main__":
    print(build(Path(__file__).resolve().parents[2] / "economy" / "content" / "economy" / "assets"))
