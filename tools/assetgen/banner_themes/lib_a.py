"""Geometry, plate and gem helpers of the banner themes fire, lightning and neon (the approved art's bannerlib).

Text is not drawn here: the game draws the title and the nickname over the banner at run time (texts() is a no-op;
the geometry still reserves the canonical text, so decorations keep clear of it as in the approved art). The gem
between them is drawn only in the "gem" pass (state.GEM), so it becomes a glyph of its own.
"""
import math, random
from PIL import Image, ImageDraw
import galmuri
from . import state
from .letters import tall_pts

F9 = galmuri.load(9)
N = 16
NAME = "xozne"
DIRS4 = ((1, 0), (-1, 0), (0, 1), (0, -1))


def text_pts(s, font=F9, base=9):
    xs, out = 0, []
    for ch in s:
        g = font.get(ord(ch))
        if not g:
            xs += 3; continue
        dw, xo, yo, bits = g; h = len(bits)
        for r, row in enumerate(bits):
            for c, on in enumerate(row):
                if on:
                    out.append((xs + xo + c, base - h - yo + r))
        xs += dw
    return xs, out


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def ramp(*stops):
    """ramp((0, c0), (0.4, c1), (1, c2)) -> f(g) piecewise linear."""
    def f(g):
        g = max(0.0, min(1.0, g))
        for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
            if g <= p1:
                return lerp(c0, c1, (g - p0) / max(1e-6, p1 - p0))
        return stops[-1][1]
    return f


class Geo:
    def __init__(self, title, side=13, top=20, bot=1, gap=11, pad=8, margin=8, rows=17, chat=None):
        """rows: the plate's height. chat: None, or how many rows over the plate the chat banner may use (a theme
        draws its chat banner, which has to fit two chat lines, when it is set; see title_banners.py CHAT)."""
        side += margin; top += margin; bot += margin
        self.title = title
        self.chat = chat
        self.TT, self.TP = text_pts(title)
        self.TW, self.NP = tall_pts(NAME)
        self.PW = pad + self.TT + gap + self.TW + pad
        self.W = self.PW + 2 * side
        self.PY0 = top; self.PY1 = top + rows - 1; self.H = self.PY1 + 1 + bot
        self.MID = (self.PY0 + self.PY1) // 2
        self.PX0 = side; self.PX1 = self.PX0 + self.PW - 1
        self.TX0 = self.PX0 + pad; self.TX1 = self.TX0 + self.TT - 1
        self.DIV = self.TX1 + gap // 2 + 1
        self.NX0 = self.DIV + gap // 2 + 1
        self.CX = (self.PX0 + self.PX1) // 2
        W, H = self.W, self.H
        m = Image.new("L", (W, H), 0)
        P0, P1, PY0, PY1, MID = self.PX0, self.PX1, self.PY0, self.PY1, self.MID
        ImageDraw.Draw(m).polygon([(P0, MID), (P0 + 6, PY0), (P1 - 6, PY0), (P1, MID), (P1 - 6, PY1), (P0 + 6, PY1)], fill=255)
        mk = m.load()
        self.inside = {(x, y) for y in range(H) for x in range(W) if mk[x, y]}
        self.edge = {p for p in self.inside if any((p[0] + a, p[1] + b) not in self.inside for a, b in DIRS4)}
        self.bevel = {p for p in self.inside - self.edge if any((p[0] + a, p[1] + b) in self.edge for a, b in DIRS4)}
        self.core = self.inside - self.edge - self.bevel
        # text placement (vertically centred on MID)
        ty = self.MID - (min(y for _, y in self.TP) + max(y for _, y in self.TP)) // 2
        ny = self.MID - (min(y for _, y in self.NP) + max(y for _, y in self.NP)) // 2
        self.title_px = [(self.TX0 + x, ty + y) for x, y in self.TP]
        self.name_px = [(self.NX0 + x, ny + y) for x, y in self.NP]
        self.text_set = set(self.title_px) | set(self.name_px)
        # pixels near the text (for keeping plate decoration away from letters)
        self.text_near = {(x + a, y + b) for x, y in self.text_set for a in (-1, 0, 1, 2) for b in (-1, 0, 1, 2)}

    def canvas(self):
        im = Image.new("RGBA", (self.W, self.H), (0, 0, 0, 0))
        return im, im.load()


def put(px, G, x, y, c, a=255):
    x, y = int(x), int(y)
    if 0 <= x < G.W and 0 <= y < G.H:
        px[x, y] = tuple(c[:3]) + (a,)


def blend(px, G, x, y, c, a):
    """alpha-composite colour c with alpha a (0..255) onto the pixel."""
    x, y = int(x), int(y)
    if not (0 <= x < G.W and 0 <= y < G.H) or a <= 0:
        return
    o = px[x, y]
    oa = o[3] / 255; na = a / 255
    ra = na + oa * (1 - na)
    if ra <= 0:
        return
    col = tuple(int(round((c[i] * na + o[i] * oa * (1 - na)) / ra)) for i in range(3))
    px[x, y] = col + (int(round(ra * 255)),)


def empty(px, G, x, y):
    return 0 <= x < G.W and 0 <= y < G.H and px[int(x), int(y)][3] == 0


def line(x0, y0, x1, y1):
    x0, y0, x1, y1 = int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1))
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy; out = []
    while True:
        out.append((x0, y0))
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy; x0 += sx
        if e2 <= dx:
            err += dx; y0 += sy
    return out


def glow(px, G, col, a=150, upper_only=False, ring=1, under=True):
    """soft 1px (or 2px) halo around the plate, only on empty pixels (under=False: none under the plate)."""
    seen = set(G.inside)
    layer = set(G.inside)
    for k in range(ring):
        nxt = set()
        for x, y in layer:
            for dx, dy in DIRS4:
                q = (x + dx, y + dy)
                if q not in seen and 0 <= q[0] < G.W and 0 <= q[1] < G.H:
                    nxt.add(q)
        for q in nxt:
            if upper_only and q[1] > G.MID:
                continue
            if not under and q[1] > G.PY1:
                continue
            if px[q][3] == 0:
                px[q] = tuple(col) + (int(a * (1 - k * 0.45)),)
        seen |= nxt; layer = nxt


def plate(px, G, fill, trim_hi, trim_lo, bevel=None, trim=None):
    """fill(x, y, g) for the core, bevel(x, y) for the inner ring, trim(x, y) or hi/lo halves for the edge."""
    for (x, y) in G.inside:
        g = (y - G.PY0) / (G.PY1 - G.PY0)
        if (x, y) in G.edge:
            c = trim(x, y) if trim else (trim_hi if y < G.MID else trim_lo)
        elif (x, y) in G.bevel and bevel:
            c = bevel(x, y)
        else:
            c = fill(x, y, g)
        if c is not None:
            px[x, y] = tuple(c[:3]) + (255,)


SHADOW = ((0, 1), (1, 1), (1, 0))
OUTLINE = ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1))


def texts(*_args, **_kw):
    """the game draws the text (title_banners.py fonts)"""


def gem_put(px, G, x, y, c):
    """one pixel of the gem: only in the gem pass, where it is also recorded"""
    if state.GEM["mode"] != "gem":
        return
    state.GEM["pix"].add((int(x), int(y)))
    put(px, G, x, y, c)


def gem(px, G, cx, cy, spark, light, mid, dark, deep, top=None):
    """the 5x5 cut gem of make14 (cy = centre row)."""
    top = top or lerp(light, (255, 255, 255), 0.4)
    for a, b, c in ((0, -2, top), (-1, -1, mid), (0, -1, light), (1, -1, dark),
                    (-2, 0, mid), (-1, 0, light), (0, 0, spark), (1, 0, dark), (2, 0, deep),
                    (-1, 1, dark), (0, 1, mid), (1, 1, deep), (0, 2, deep)):
        gem_put(px, G, cx + a, cy + b, c)


def rivets(px, G, hi, lo):
    for sx in (G.PX0 + 4, G.PX1 - 4):
        for a, b in ((0, 0), (1, 0), (0, 1), (1, 1)):
            put(px, G, sx - 1 + a, G.MID - 1 + b, hi if a + b == 0 else lo)


def spikes(px, G, col, tip, n=4):
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for k in range(n):
            put(px, G, x0 + side * (k + 1), G.MID, col if k < n - 1 else tip)


def drop_orphans(im):
    """remove opaque pixels that touch no other opaque pixel (8-neighbourhood)."""
    px = im.load(); W, H = im.size
    kill = []
    for y in range(H):
        for x in range(W):
            if px[x, y][3] == 0: continue
            if not any(0 <= x + a < W and 0 <= y + b < H and px[x + a, y + b][3] for a in (-1, 0, 1) for b in (-1, 0, 1) if a or b):
                kill.append((x, y))
    for q in kill: px[q] = (0, 0, 0, 0)
    return len(kill)


def crop_union(frames, pad=1):
    box = None
    for f in frames:
        b = f.getbbox()
        if b is None: continue
        box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
    box = (max(0, box[0] - pad), max(0, box[1] - pad), min(frames[0].width, box[2] + pad), min(frames[0].height, box[3] + pad))
    return [f.crop(box) for f in frames], box
