"""
Neo둥근모 (NeoDunggeunmo), the 16 px Korean pixel font the game's default font takes its Korean from
(tools/assetgen/default_font.py).

Neo둥근모 1.600 by Eunbin Jeong (Dalgona.), from the npm package "@kfonts/neodgm" 0.5.0 (src/neodgm.ttf),
SIL Open Font License 1.1 with the Reserved Font Names "Neo둥근모", "Neo둥근모 Code", "NeoDunggeunmo" and
"NeoDunggeunmo Code" (fonts/LICENSE-neodgm.txt; the same notice ships in the pack). What the pack carries is a
Modified Version (glyphs converted to the game's .hex format, a subset), so it is never named with those names.

fonts/neodgm.txt is the part of the font the pack uses (Hangul syllables, compatibility jamo, CJK symbols and
punctuation), drawn exactly from the outlines (every point of the TTF lies on its 64-unit pixel grid), one glyph
per line, so the pack can be rebuilt without the network or the TTF:

    codepoint(hex) advance rows

advance: 8 or 16 px. rows: the 16 rows of the glyph's em square, top to bottom (the top row is 12 px above the
baseline), each `advance` px wide, as hex with the most significant bit leftmost, joined by '.'.

    python3 neodgm.py <path to neodgm.ttf>     # rewrites fonts/neodgm.txt (needs fontTools)
"""

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE / "fonts" / "neodgm.txt"
LICENSE = HERE / "fonts" / "LICENSE-neodgm.txt"

RANGES = [(0x3000, 0x303F), (0x3131, 0x318E), (0xAC00, 0xD7A3)]
UNIT, ASCENT, EM = 64, 12, 16

HEADER = ("# Neo둥근모 1.600 (npm package \"@kfonts/neodgm\" 0.5.0, src/neodgm.ttf), Copyright (c) 2017-2021 Eunbin Jeong\n"
          "# (Dalgona.). SIL Open Font License 1.1 with Reserved Font Names: see LICENSE-neodgm.txt. Format: neodgm.py\n")


def _rows(contours, width):
    """Exact pixels of grid-aligned outlines (non-zero winding, sampled at pixel centres)."""
    rows = []
    for r in range(EM):
        yc = ASCENT - r - 0.5
        crossings = []
        for pts in contours:
            for (x0, y0), (x1, y1) in zip(pts, pts[1:] + pts[:1]):
                if x0 == x1 and min(y0, y1) < yc < max(y0, y1):
                    crossings.append((x0, 1 if y1 > y0 else -1))
        row = []
        for c in range(width):
            xc = c + 0.5
            row.append(sum(d for x, d in crossings if x > xc) != 0)
        rows.append(row)
    return rows


def vendor(ttf_path):
    from fontTools.pens.recordingPen import DecomposingRecordingPen
    from fontTools.ttLib import TTFont

    font = TTFont(ttf_path)
    assert font["head"].unitsPerEm == UNIT * EM and font["hhea"].ascent == UNIT * ASCENT
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    out = [HEADER]
    for cp in sorted(cmap):
        if not any(a <= cp <= b for a, b in RANGES):
            continue
        name = cmap[cp]
        pen = DecomposingRecordingPen(glyphs)
        glyphs[name].draw(pen)
        contours, cur = [], []
        for op, args in pen.value:
            if op == "moveTo":
                cur = [args[0]]
            elif op == "lineTo":
                cur.append(args[0])
            elif op in ("closePath", "endPath"):
                contours.append([(x / UNIT, y / UNIT) for x, y in cur])
            else:
                raise ValueError(f"U+{cp:04X}: {op} (not a pixel outline)")
        for pts in contours:
            assert all(x == int(x) and y == int(y) for x, y in pts), f"U+{cp:04X} off the pixel grid"
        advance = glyphs[name].width // UNIT
        rows = _rows(contours, advance)
        hexrows = ".".join(format(int("".join("1" if b else "0" for b in r), 2), f"0{advance // 4}x") for r in rows)
        out.append(f"{cp:x} {advance} {hexrows}\n")
    DATA.write_text("".join(out), encoding="utf-8")
    return DATA


def load():
    """codepoint -> (advance, 16 rows of `advance` bools, left to right, the top row 12 px above the baseline)."""
    out = {}
    for line in DATA.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        cp, adv, rows = line.split()
        adv = int(adv)
        out[int(cp, 16)] = (adv, [[(int(r, 16) >> (adv - 1 - x)) & 1 == 1 for x in range(adv)] for r in rows.split(".")])
    return out


if __name__ == "__main__":
    written = vendor(sys.argv[1])
    print(written, written.stat().st_size)
