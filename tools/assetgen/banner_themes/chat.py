"""The chat banner: a theme drawn again for one chat line, at twice the resolution.

A chat line is 9 GUI px high and a Galmuri9 letter alone is 9 rows, so the full banner's text cannot share a line
with a plate, and the full banner at half size has letters of 4.5 px. The chat banner is drawn like the game draws
its own Hangul: 1 texel = half a GUI pixel. A line is 18 rows of texels: a plate of exactly these 18 rows (an edge,
a bevel, 14 rows of text, a bevel, an edge), nothing over or under it, the text as high as the chat's own (7 px):
the title in Galmuri14, made bold (every pixel doubled to its right: one more column), the nickname in the tall
5 x 7 face with every pixel doubled both ways (the same letters as on the full banner, the same 7 px).

A theme module gives chat_frame(G, i) (the 16 frames, G a ChatGeo) next to its frame(G, i).
"""
from PIL import Image

import galmuri
from .letters import TALL

ROWS = 18                     # the plate: rows 0 .. 17 of the picture, the chat line's 9 px
TEXT_ROW = 2                  # the text's first row (a capital's top); 14 rows
TEXT_ROWS = 14
SLOPE = 8                     # the plate's pointed ends: columns from its tip to its full height
PAD, GAP, SPACE = 17, 15, 7   # tip to text (the slope and 9 more), title - gem - name, a space in the title
NAME = "xozne"

_F14 = None


def f14():
    global _F14
    if _F14 is None:
        _F14 = galmuri.load(14)
    return _F14


def bold14(ch):
    """Galmuri14's pixels of a character, bold, in cell rows (0 = the top row of a capital, 14 = the first under the
    baseline), or None when the font has no picture for it"""
    g = f14().get(ord(ch))
    if not g or not g[3]:
        return None
    adv, xo, yo, bits = g
    top = TEXT_ROWS - (yo + len(bits))
    pix = {(xo + x, top + y) for y, r in enumerate(bits) for x, b in enumerate(r) if b}
    return sorted(pix | {(x + 1, y) for x, y in pix})


def tall2(ch):
    """the nickname face of a character with every pixel doubled both ways, in cell rows 0 .. 13"""
    return sorted((2 * c + dx, 2 * r + dy) for r, row in enumerate(TALL[ch]) for c, v in enumerate(row) if v == "#"
                  for dx in (0, 1) for dy in (0, 1))


def advance(pix):
    """ink and two texels (1 GUI px) of air"""
    return max(x for x, _ in pix) + 3


def width(s, nick=False):
    w = 0
    for ch in s:
        if ch == " ":
            w += SPACE
            continue
        pix = tall2(ch) if nick and ch in TALL else bold14(ch)
        if pix:
            w += advance(pix)
    return w


class ChatGeo:
    """The plate for the canonical words (as the full banner's Geo: what a theme's frame needs to draw it).
    end: columns of plate after the text's right padding that stay free of text (a theme's effect burns there);
    they belong to the right end, which is never tiled, so what is drawn there need not repeat."""

    def __init__(self, title, side=22, end=0):
        self.title = title
        self.END = end
        self.TT, self.TW = width(title), width(NAME, nick=True)
        self.PW = PAD + self.TT + GAP + self.TW + PAD + end
        self.W, self.H = self.PW + 2 * side, ROWS
        self.PY0, self.PY1, self.MID = 0, ROWS - 1, ROWS // 2 - 1
        self.PX0 = side
        self.PX1 = side + self.PW - 1
        self.TX0 = self.PX0 + PAD
        self.DIV = self.TX0 + self.TT + GAP // 2
        self.CX = (self.PX0 + self.PX1) // 2
        # the plate: both ends come to a point over SLOPE columns (rows 8 and 9 are the longest)
        self.inset = {r: int(abs(r - (ROWS - 1) / 2)) for r in range(ROWS)}
        self.inside = {(x, r) for r in range(ROWS) for x in range(self.PX0 + self.inset[r], self.PX1 - self.inset[r] + 1)}
        near = ((1, 0), (-1, 0), (0, 1), (0, -1))
        self.edge = {p for p in self.inside if any((p[0] + a, p[1] + b) not in self.inside for a, b in near)}
        self.bevel = {p for p in self.inside - self.edge if any((p[0] + a, p[1] + b) in self.edge for a, b in near)}
        self.core = self.inside - self.edge - self.bevel

    def canvas(self):
        im = Image.new("RGBA", (self.W, self.H), (0, 0, 0, 0))
        return im, im.load()
