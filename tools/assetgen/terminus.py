"""
Terminus Bold, the bitmap font the chat banners write Latin letters, digits and signs with
(banner_themes/chat.py): upright strokes two texels wide and level ones one, like the Hangul of Neo둥근모 beside
them, every letter drawn by hand at this size (nothing is thickened by rule, so no stroke comes out uneven).

Terminus Font by Dimitar Toshkov Zhekov, taken from the bold 22 px bitmap source (ter-x22b.bdf) as packaged in
github.com/powerline/fonts (Terminus/BDF), SIL Open Font License 1.1 with the Reserved Font Name "Terminus Font"
(fonts/LICENSE-terminus.txt; the same notice ships in the pack next to the fonts made from it). What the pack
carries is a Modified Version (a subset, squeezed to the banner's rows, as a TrueType font of its own name), so it
is never named with that name.

fonts/terminus_bold22.txt is the part the banners use (printable ASCII), one glyph per line, in the format of
galmuri.py, so the pack can be rebuilt without the network or the BDF:

    codepoint(hex) advance x-offset y-offset width height rows

x-offset, y-offset: the bitmap's lower left corner from the pen on the baseline. rows: top to bottom, each `width`
bits as hex with the most significant bit leftmost, joined by '.' ('-' for no ink).

    python3 terminus.py <path to ter-x22b.bdf>     # rewrites fonts/terminus_bold22.txt
"""

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE / "fonts" / "terminus_bold22.txt"
LICENSE = HERE / "fonts" / "LICENSE-terminus.txt"
FIRST, LAST = 0x21, 0x7E

HEADER = ("# Terminus Font, bold 22 px (ter-x22b.bdf, from github.com/powerline/fonts), Copyright (c) 2012 Dimitar Toshkov\n"
          "# Zhekov. SIL Open Font License 1.1 with Reserved Font Name \"Terminus Font\": see LICENSE-terminus.txt. Format: terminus.py\n")


def vendor(bdf):
    lines, cp, box, rows = [], None, None, None
    for line in Path(bdf).read_text(encoding="latin-1").splitlines():
        t = line.split()
        if not t:
            continue
        if t[0] == "ENCODING":
            cp = int(t[1])
        elif t[0] == "DWIDTH":
            dw = int(t[1])
        elif t[0] == "BBX":
            box = tuple(int(v) for v in t[1:5])
        elif t[0] == "BITMAP":
            rows = []
        elif t[0] == "ENDCHAR":
            if cp is not None and FIRST <= cp <= LAST:
                w, h, xo, yo = box
                bits = [int(r, 16) >> (len(r) * 4 - w) for r in rows]
                # trim empty rows and columns (the source's boxes are the whole cell)
                while bits and bits[0] == 0: bits.pop(0); h -= 1
                while bits and bits[-1] == 0: bits.pop(); h -= 1; yo += 1
                if bits:
                    while all((b >> (w - 1)) & 1 == 0 for b in bits): bits = [b & ((1 << (w - 1)) - 1) for b in bits]; w -= 1; xo += 1
                    while all(b & 1 == 0 for b in bits): bits = [b >> 1 for b in bits]; w -= 1
                digits = (w + 3) // 4
                packed = ".".join(f"{b:0{digits}x}" for b in bits) if bits else "-"
                lines.append(f"{cp:04x} {dw} {xo} {yo} {w} {h} {packed}")
            rows = None
        elif rows is not None:
            rows.append(t[0])
    DATA.write_text(HEADER + "\n".join(lines) + "\n", encoding="utf-8")
    return DATA


def load():
    """codepoint -> (advance, x-offset, y-offset, rows as lists of bools, left to right)."""
    out = {}
    for line in DATA.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        cp, dw, xo, yo, w, h, rows = line.split()
        w = int(w)
        bits = [] if rows == "-" else [[(int(r, 16) >> (w - 1 - x)) & 1 == 1 for x in range(w)] for r in rows.split(".")]
        out[int(cp, 16)] = (int(dw), int(xo), int(yo), bits)
    return out


if __name__ == "__main__":
    written = vendor(sys.argv[1])
    print(written, written.stat().st_size)
