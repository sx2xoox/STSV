"""
Galmuri9 and Galmuri7, the 9 px and 7 px Korean pixel fonts the resource-pack HUD writes with
(tools/assetgen/hud.py: Galmuri9 everywhere, Galmuri7 for the small and large sidebar panel).
Galmuri14 (fonts/galmuri14.txt, the same way from dist/Galmuri14.bdf) is the title banners' chat text
(tools/assetgen/banner_themes/chat.py).

Galmuri 2.40.3 by Lee Minseo (quiple), SIL Open Font License 1.1, no reserved font name
(fonts/LICENSE-galmuri.txt; the same notice ships in the pack next to the sheets made from it).
fonts/galmuri9.txt and fonts/galmuri7.txt are the part of each font's own bitmap source (dist/Galmuri9.bdf,
dist/Galmuri7.bdf of the npm package "galmuri") the HUD can use, one glyph per line, so the pack can be
rebuilt without the network:

    codepoint(hex) advance x-offset y-offset width height rows

rows: the glyph's bitmap rows top to bottom, each as hex (most significant bit = leftmost pixel), joined
by '.'; '-' for a glyph without pixels. Offsets and advance are BDF's (y-offset = bottom row above the
baseline, may be negative). Every Hangul syllable is kept, so any text can be drawn.

    python3 galmuri.py <path to Galmuri9.bdf>     # rewrites fonts/galmuri9.txt (Galmuri7.bdf: galmuri7.txt)
"""

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
FONTS = HERE / "fonts"
LICENSE = HERE / "fonts" / "LICENSE-galmuri.txt"

RANGES = [(0x20, 0x7E), (0xA0, 0xFF), (0x2010, 0x206F), (0x2190, 0x21FF), (0x2200, 0x22FF), (0x2500, 0x25FF),
          (0x2600, 0x26FF), (0x2700, 0x27BF), (0x3000, 0x303F), (0x3131, 0x318E), (0xAC00, 0xD7A3)]

HEADER = ("# Galmuri{size} 2.40.3 (npm package \"galmuri\", dist/Galmuri{size}.bdf), Copyright (c) 2019-2025 Lee Minseo.\n"
          "# SIL Open Font License 1.1: see LICENSE-galmuri.txt. Format: tools/assetgen/galmuri.py\n")


def data(size=9):
    return FONTS / f"galmuri{size}.txt"


def vendor(bdf_path):
    size = int("".join(c for c in Path(bdf_path).stem if c.isdigit()))
    out = [HEADER.format(size=size)]
    with open(bdf_path, encoding="latin-1") as f:
        cur, bitmap = None, None
        for line in f:
            p = line.split()
            if not p:
                continue
            k = p[0]
            if k == "STARTCHAR":
                cur = {}
            elif k == "ENCODING":
                cur["cp"] = int(p[1])
            elif k == "DWIDTH":
                cur["dw"] = int(p[1])
            elif k == "BBX":
                cur["bbx"] = [int(v) for v in p[1:5]]
            elif k == "BITMAP":
                bitmap = []
            elif k == "ENDCHAR":
                cp = cur.get("cp", -1)
                if any(a <= cp <= b for a, b in RANGES):
                    w, h, xo, yo = cur["bbx"]
                    rows = ".".join(format(int(r, 16) >> (len(r) * 4 - w) if w else 0, "x") for r in bitmap) if w and h else "-"
                    out.append(f"{cp:x} {cur['dw']} {xo} {yo} {w} {h} {rows}\n")
                cur, bitmap = None, None
            elif bitmap is not None:
                bitmap.append(k)
    data(size).write_text("".join(out), encoding="utf-8")
    return data(size)


def load(size=9):
    """codepoint -> (advance, x-offset, y-offset, rows as lists of bools, left to right)."""
    out = {}
    for line in data(size).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        cp, dw, xo, yo, w, h, rows = line.split()
        w, h = int(w), int(h)
        bits = []
        if rows != "-":
            for r in rows.split("."):
                v = int(r, 16)
                bits.append([(v >> (w - 1 - x)) & 1 == 1 for x in range(w)])
        out[int(cp, 16)] = (int(dw), int(xo), int(yo), bits)
    return out


if __name__ == "__main__":
    written = vendor(sys.argv[1])
    print(written, written.stat().st_size)
