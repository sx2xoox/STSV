"""Geometry, plate and gem helpers of the banner themes blood moon and prism (the approved art's lib2).

As lib_a: no text (the game draws it), the gem only in the gem pass (state.GEM).
"""
import math, colorsys
from PIL import Image
import galmuri
from . import state
from .letters import tall_pts

F9 = galmuri.load(9)
N = 16
NAME = "xozne"
TAU = 2 * math.pi
D4 = ((1, 0), (-1, 0), (0, 1), (0, -1))
D8 = D4 + ((1, 1), (1, -1), (-1, 1), (-1, -1))
BG = (24, 26, 32, 255)
SHADOW = ((0, 1), (1, 1), (1, 0))
OUTLINE = ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1))
SLOPE = [0, 1, 2, 3, 3, 4, 5, 6, 6]          # plate end: x inset per row away from MID


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


def clamp(v, a=0.0, b=1.0):
    return a if v < a else b if v > b else v


def lerp(a, b, t):
    t = clamp(t)
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(min(len(a), len(b))))


def ramp(*stops):
    def f(g):
        g = clamp(g)
        for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
            if g <= p1:
                return lerp(c0, c1, (g - p0) / max(1e-6, p1 - p0))
        return stops[-1][1]
    return f


def hsv(h, s, v):
    r, g, b = colorsys.hsv_to_rgb(h % 1.0, clamp(s), clamp(v))
    return (int(r * 255 + .5), int(g * 255 + .5), int(b * 255 + .5))


def tri(x):
    """triangle wave, period 1, 0..1..0"""
    x = x % 1.0
    return 2 * x if x < .5 else 2 - 2 * x


class Geo:
    def __init__(self, title, M=34, gap=11, pad=8):
        self.title = title
        self.TT, self.TP = text_pts(title)
        self.TW, self.NP = tall_pts(NAME)
        self.PW = pad + self.TT + gap + self.TW + pad
        self.M = M
        self.W = self.PW + 2 * M
        self.H = 17 + 2 * M
        self.PX0 = M; self.PX1 = M + self.PW - 1
        self.PY0 = M; self.PY1 = M + 16; self.MID = M + 8
        self.MX = self.PX0 + self.PX1          # mirror: x -> MX - x
        self.CX = self.MX / 2
        self.TX0 = self.PX0 + pad; self.TX1 = self.TX0 + self.TT - 1
        self.DIV = self.TX1 + gap // 2 + 1
        self.NX0 = self.DIV + gap // 2 + 1
        ins = set()
        self.rowx = {}
        for y in range(self.PY0, self.PY1 + 1):
            o = SLOPE[abs(y - self.MID)]
            self.rowx[y] = (self.PX0 + o, self.PX1 - o)
            for x in range(self.PX0 + o, self.PX1 - o + 1):
                ins.add((x, y))
        self.inside = ins
        self.edge = {p for p in ins if any((p[0] + a, p[1] + b) not in ins for a, b in D4)}
        self.bevel = {p for p in ins - self.edge if any((p[0] + a, p[1] + b) in self.edge for a, b in D4)}
        self.core = ins - self.edge - self.bevel
        tys = [y for _, y in self.TP]; nys = [y for _, y in self.NP]
        ty = self.MID - (min(tys) + max(tys)) // 2
        ny = self.MID - (min(nys) + max(nys)) // 2
        self.title_px = [(self.TX0 + x, ty + y) for x, y in self.TP]
        self.name_px = [(self.NX0 + x, ny + y) for x, y in self.NP]
        self.text = set(self.title_px) | set(self.name_px)
        self.text_near = {(x + a, y + b) for x, y in self.text for a in (-1, 0, 1, 2) for b in (-1, 0, 1, 2)}

    def mx(self, x):
        return self.MX - x


class Cv:
    def __init__(self, G):
        self.G = G
        self.im = Image.new("RGBA", (G.W, G.H), (0, 0, 0, 0))
        self.px = self.im.load()

    def ok(self, x, y):
        return 0 <= x < self.G.W and 0 <= y < self.G.H

    def put(self, x, y, c, a=None):
        x, y = int(round(x)), int(round(y))
        if self.ok(x, y):
            self.px[x, y] = tuple(c[:3]) + ((a if a is not None else (c[3] if len(c) > 3 else 255)),)

    def get(self, x, y):
        x, y = int(round(x)), int(round(y))
        return self.px[x, y] if self.ok(x, y) else (0, 0, 0, 0)

    def empty(self, x, y):
        return self.get(x, y)[3] == 0

    def under(self, x, y, c, a=255):
        if self.empty(x, y):
            self.put(x, y, c, a)

    def blend(self, x, y, c, a):
        x, y = int(round(x)), int(round(y))
        if not self.ok(x, y) or a <= 0:
            return
        o = self.px[x, y]
        oa = o[3] / 255; na = clamp(a / 255)
        ra = na + oa * (1 - na)
        if ra <= 0:
            return
        col = tuple(int(round((c[i] * na + o[i] * oa * (1 - na)) / ra)) for i in range(3))
        self.px[x, y] = col + (int(round(ra * 255)),)

    def sprite(self, x0, y0, rows, pal, flip=False, only_empty=False):
        """rows: list of strings; pal: char -> colour; x0,y0 top-left (flip mirrors around the sprite)."""
        w = max(len(r) for r in rows)
        for r, row in enumerate(rows):
            for c, ch in enumerate(row):
                if ch in pal and pal[ch] is not None:
                    xx = x0 + (w - 1 - c if flip else c)
                    if only_empty:
                        self.under(xx, y0 + r, pal[ch])
                    else:
                        self.put(xx, y0 + r, pal[ch])


def plate(cv, G, fill, trim, bevel=None):
    for (x, y) in G.inside:
        if (x, y) in G.edge:
            c = trim(x, y)
        elif (x, y) in G.bevel and bevel:
            c = bevel(x, y)
        else:
            c = fill(x, y)
        if c is not None:
            cv.put(x, y, c)


def texts(*_args, **_kw):
    """the game draws the text (title_banners.py fonts)"""


def gem(cv, cx, cy, spark, light, mid, dark, deep, top=None):
    top = top or lerp(light, (255, 255, 255), 0.4)
    for a, b, c in ((0, -2, top), (-1, -1, mid), (0, -1, light), (1, -1, dark),
                    (-2, 0, mid), (-1, 0, light), (0, 0, spark), (1, 0, dark), (2, 0, deep),
                    (-1, 1, dark), (0, 1, mid), (1, 1, deep), (0, 2, deep)):
        if state.GEM["mode"] == "gem":
            state.GEM["pix"].add((int(cx + a), int(cy + b)))
            cv.put(cx + a, cy + b, c)


def line_pts(x0, y0, x1, y1):
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


def glow(cv, G, col, a=140, rows=None):
    """1px halo just outside the plate on empty pixels (rows: predicate on y)."""
    for (x, y) in G.edge:
        for dx, dy in D4:
            q = (x + dx, y + dy)
            if q not in G.inside and (rows is None or rows(q[1])):
                cv.under(q[0], q[1], col, a)


def sparkle(cv, x, y, size, col, core=(255, 255, 255), a=255):
    """4-point star; size 0 = 1px+dim cross, 1 = plus, 2 = long plus."""
    cv.put(x, y, core)
    if size >= 1:
        for dx, dy in D4:
            cv.put(x + dx, y + dy, col, a)
    if size >= 2:
        for dx, dy in D4:
            cv.put(x + 2 * dx, y + 2 * dy, col, int(a * 0.6))


def orphans(im):
    px = im.load(); W, H = im.size; out = []
    for y in range(H):
        for x in range(W):
            if px[x, y][3] and not any(0 <= x + a < W and 0 <= y + b < H and px[x + a, y + b][3] for a, b in D8):
                out.append((x, y))
    return out


def tube(path):
    """path: [(x, y, r)] samples. -> {pixel: (s, side, d)}; side > 0 is to the right of travel
    (for a path going right that is below the centre line)."""
    out = {}
    n = len(path)
    for k, (x, y, r) in enumerate(path):
        k0, k1 = max(0, k - 1), min(n - 1, k + 1)
        dx, dy = path[k1][0] - path[k0][0], path[k1][1] - path[k0][1]
        L = math.hypot(dx, dy) or 1; dx, dy = dx / L, dy / L
        for yy in range(int(math.floor(y - r - 1)), int(math.ceil(y + r + 2))):
            for xx in range(int(math.floor(x - r - 1)), int(math.ceil(x + r + 2))):
                d = math.hypot(xx - x, yy - y)
                if d <= r and ((xx, yy) not in out or out[(xx, yy)][2] > d):
                    side = (xx - x) * (-dy) + (yy - y) * dx
                    out[(xx, yy)] = (k / max(1, n - 1), side, d)
    return out


def outline(cv, pix, col, diag=True):
    S = set(pix)
    for p in S:
        for a, b in (D8 if diag else D4):
            q = (p[0] + a, p[1] + b)
            if q not in S:
                cv.under(q[0], q[1], col)


def catmull(pts, per=6):
    """smooth open curve through pts."""
    out = []
    P = [pts[0]] + list(pts) + [pts[-1]]
    for k in range(1, len(P) - 2):
        p0, p1, p2, p3 = P[k - 1], P[k], P[k + 1], P[k + 2]
        for j in range(per):
            u = j / per
            out.append(tuple(0.5 * ((2 * p1[d]) + (-p0[d] + p2[d]) * u + (2 * p0[d] - 5 * p1[d] + 4 * p2[d] - p3[d]) * u * u
                                    + (-p0[d] + 3 * p1[d] - 3 * p2[d] + p3[d]) * u ** 3) for d in range(2)))
    out.append(pts[-1])
    return out


def poly_pixels(poly):
    """pixels whose centres are inside the polygon (even-odd)."""
    xs = [p[0] for p in poly]; ys = [p[1] for p in poly]
    out = set()
    n = len(poly)
    for y in range(int(math.floor(min(ys))), int(math.ceil(max(ys))) + 1):
        cross = []
        for k in range(n):
            (x0, y0), (x1, y1) = poly[k], poly[(k + 1) % n]
            if (y0 <= y < y1) or (y1 <= y < y0):
                cross.append(x0 + (y - y0) * (x1 - x0) / (y1 - y0))
        cross.sort()
        for a, b in zip(cross[::2], cross[1::2]):
            for x in range(int(math.ceil(a - 0.5)), int(math.floor(b - 0.5)) + 1):
                out.add((x, y))
    return out


def dist_to(pts, x, y):
    return min(math.hypot(x - a, y - b) for a, b in pts)


def leaf(cv, ax, ay, ang, L=5.0, W=2.6, light=(176, 236, 100), mid=(110, 190, 60), dark=(50, 116, 38), skip=None):
    """lens-shaped leaf from (ax, ay) pointing at angle ang (radians, screen coords)."""
    dx, dy = math.cos(ang), math.sin(ang)
    pix = {}
    for yy in range(int(ay - L - 2), int(ay + L + 3)):
        for xx in range(int(ax - L - 2), int(ax + L + 3)):
            u = (xx - ax) * dx + (yy - ay) * dy
            v = -(xx - ax) * dy + (yy - ay) * dx
            if 0.3 <= u <= L:
                half = W / 2 * math.sin(math.pi * u / L) ** 0.75
                if abs(v) <= half + 0.15:
                    pix[(xx, yy)] = v
    for (x, y), v in pix.items():
        if skip and (x, y) in skip: continue
        edge = any((x + a, y + b) not in pix for a, b in D4)
        c = light if v < -0.3 else mid
        if edge and v > 0.3: c = dark
        cv.put(x, y, c)
    return pix
