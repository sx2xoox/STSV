"""The chat banner: a theme drawn again for one chat line, at twice the resolution.

A chat line is 9 GUI px high and a Galmuri9 letter alone is 9 rows, so the full banner's text cannot share a line
with a plate, and the full banner at half size has letters of 4.5 px. The chat banner is drawn like the game draws
its own Hangul: 1 texel = half a GUI pixel. A line is 18 rows of texels: a plate of exactly these 18 rows (an edge,
a bevel, 14 rows for the text, a bevel, an edge), nothing over or under it. The text is drawn at this resolution
(glyph): Hangul in Neo둥근모 (13 rows, 6.5 px), Latin, digits and signs in Galmuri14 made bold (capitals 14 rows,
7 px: the chat's own height), title and nickname alike.

A theme module gives chat_frame(G, i) (the 16 frames, G a ChatGeo) next to its frame(G, i).

What the chat banners have in common, and what they do not. Like the animated name plates of chat programs,
every theme is its own little scene with its own motion, its own edges and its own ends: no shared frame.
  * common: the words are the full banner's (same colours, no outline) and stay plain to read most of the time;
    smooth shading (colour ramps, glows that fade out) instead of flat bands and outlines; nothing over or under
    the chat line; where the plate is tiled the picture repeats every TILE columns.
  * how a theme moves is its own choice of timeline (title_banners.py reads it from the geometry):
      - a loop: frames 0-15 in turn, 70 ms a frame, the whole banner in step (ChatGeo flow=0): a scene that keeps
        going (drifting, scrolling, pulsing);
      - a gust: the plate idles (frames 0-3) and every two seconds frames 4-15 play once, part after part from the
        left, 50 ms a step (flow=FLOW): something that runs along the whole banner (drawn by ChatGeo.flow_at);
      - scattered: as a gust, but every part at a time of its own (scatter=True): things that happen here and
        there (each part's frames 4-15 are its own little event; it must die away towards the part's sides).
The helpers under ChatGeo are what the themes share.
"""
import math

from PIL import Image

import galmuri
import neodgm
from . import state

ROWS = 18                     # the plate: rows 0 .. 17 of the picture, the chat line's 9 px
TEXT_ROW = 2                  # the text's first row; 14 rows (the letters: glyph)
SLOPE = 8                     # the plate's pointed ends: columns from its tip to its full height
PAD, GAP, SPACE = 17, 15, 7   # tip to text (the slope and 9 more), title - gem - name, a space in the title
NAME = "xozne"

TEXT_BASE = 14                # the cell row under the letters' baseline: capitals are rows 0 .. 13

# Galmuri14's letters that hang under the baseline (g j p q y three rows, Q and the comma two) would cross the
# plate's whole bottom frame and leave it. Texel rows are taken out of each (out of a stretch of upright strokes,
# so its shape stays; of p and q the stem's last rows) and what is under them moves up: they hang a single row, on
# the frame's bevel, and the edge stays whole.
SQUEEZE = {"g": (9, 7), "j": (10, 8), "p": (16, 15), "q": (16, 15), "y": (11, 8), "Q": (6,), ",": (14,)}

_F14 = _NEO = None


def glyph(ch, nick=False):
    """The pixels of a character of the chat banner's text, in cell rows (0 = the text's first row, plate row
    TEXT_ROW), or None when the font has no picture for it. Title and nickname are written alike:

    Hangul in Neo둥근모, the font the pack's default font writes Korean with (default_font.py), so a banner's
    Hangul is the chat's own: 13 rows (6.5 px) on the baseline, a little lower than a capital, as Hangul fills its
    whole square. Upright strokes two texels, level ones one. The top row of its em square is cell row 0.

    Latin, digits and signs in Galmuri14 made bold (every pixel doubled to its right): capitals and digits 14 rows
    (7 px, the chat's own height), small letters 10, drawn at the plate's resolution, so their curves are twice
    as fine as a doubled 5 x 7 letter's."""
    global _F14, _NEO
    if 0xAC00 <= ord(ch) <= 0xD7A3:
        if _NEO is None:
            _NEO = neodgm.load()
        g = _NEO.get(ord(ch))
        return sorted((x, y) for y, row in enumerate(g[1]) for x, b in enumerate(row) if b) or None if g else None
    if _F14 is None:
        _F14 = galmuri.load(14)
    g = _F14.get(ord(ch))
    if not g or not g[3]:
        return None
    adv, xo, yo, bits = g
    top = TEXT_BASE - (yo + len(bits))
    pix = {(xo + x, top + y) for y, r in enumerate(bits) for x, b in enumerate(r) if b}
    for out in SQUEEZE.get(ch, ()):                              # from the lowest row up
        pix = {(x, y - (y > out)) for x, y in pix if y != out}
    return sorted(pix | {(x + 1, y) for x, y in pix})


def outline(pix):
    """the ring of pixels around a letter (its 8 neighbours, less the letter): drawn dark under any theme, it keeps
    the letter readable over whatever the plate shows; a glyph of its own, so it never covers its letter"""
    ink = set(pix)
    return sorted({(x + a, y + b) for x, y in ink for a in (-1, 0, 1) for b in (-1, 0, 1)} - ink)


def advance(pix):
    """ink and two texels (1 GUI px) of air"""
    return max(x for x, _ in pix) + 3


def width(s, nick=False):
    w = 0
    for ch in s:
        if ch == " ":
            w += SPACE
            continue
        pix = glyph(ch, nick)
        if pix:
            w += advance(pix)
    return w


class ChatGeo:
    """The plate for the canonical words (as the full banner's Geo: what a theme's frame needs to draw it).
    end: columns of plate after the text's right padding that stay free of text (a theme's effect burns there);
    they belong to the right end, which is never tiled, so what is drawn there need not repeat."""

    def __init__(self, title, side=22, end=0, tile=16, flow=0, pointed=True, scatter=False, grow=0, centre=0):
        """tile: the plate's tile (title_banners.py cuts it at TILE0). flow: columns a step of a gust that runs
        along the banner from left to right (0: none), see flow_at. pointed: the plate's ends come to a point
        (else it is a plain bar: the theme shapes its ends itself, within the 16 columns of each end part)."""
        self.title = title
        self.END = end
        self.TILE, self.FLOW, self.SCATTER = tile, flow, scatter
        # grow: the plate only grows by whole steps of this many columns (so a slice of the tile never cuts what
        # is drawn in cells of that width). centre: half the width of a centre ornament (its own part, at CX,
        # between a left and a right fill; 0: none)
        self.GROW, self.CENTRE = grow, centre
        self.TT, self.TW = width(title), width(NAME, nick=True)
        self.PW = PAD + self.TT + GAP + self.TW + PAD + end
        if centre:                                               # room for both ends, a tile each side and the centre
            self.PW = max(self.PW, 2 * (16 + tile + centre) + end + 2)
        self.W, self.H = self.PW + 2 * side, ROWS
        self.PY0, self.PY1, self.MID = 0, ROWS - 1, ROWS // 2 - 1
        self.PX0 = side
        self.PX1 = side + self.PW - 1
        self.TX0 = self.PX0 + PAD
        self.DIV = self.TX0 + self.TT + GAP // 2
        self.CX = (self.PX0 + self.PX1) // 2
        # the plate: both ends come to a point over SLOPE columns (rows 8 and 9 are the longest)
        self.inset = {r: int(abs(r - (ROWS - 1) / 2)) if pointed else 0 for r in range(ROWS)}
        self.inside = {(x, r) for r in range(ROWS) for x in range(self.PX0 + self.inset[r], self.PX1 - self.inset[r] + 1)}
        near = ((1, 0), (-1, 0), (0, 1), (0, -1))
        self.edge = {p for p in self.inside if any((p[0] + a, p[1] + b) not in self.inside for a, b in near)}
        self.bevel = {p for p in self.inside - self.edge if any((p[0] + a, p[1] + b) in self.edge for a, b in near)}
        self.core = self.inside - self.edge - self.bevel

        # the banner's parts as the plugin lays them out (title_banners.py cut): the left end up to TILE0, the tile
        # (repeated and sliced for the plate's length), and the right end from R0 (it keeps 16 plate columns and END)
        self.TILE0 = self.PX0 + 16
        self.R0 = self.PX1 + 1 - 16 - end
        self.FLOW0 = self.PX0 - 16                               # where a gust starts: the left end's step 0

    def part(self, x):
        """the column a part starts at, for the part column x is in (the tile: the copy of it x is in)"""
        if x < self.TILE0:
            return self.FLOW0
        if x >= self.R0:
            return self.R0
        return self.TILE0 + (x - self.TILE0) // self.TILE * self.TILE

    def flow_at(self, x, step):
        """How far behind a gust's front column x is at a step of its part (negative: the front has not come). The
        front starts at its part's first column at step 0 and moves FLOW columns a step. The plugin plays every part
        as many steps late as it starts after the banner's left end (BannerText.delay), so put together the front
        runs along the whole banner, whatever its length: draw a gust as a function of this number only."""
        return self.FLOW * step - (x - self.part(x))

    def canvas(self):
        im = Image.new("RGBA", (self.W, self.H), (0, 0, 0, 0))
        return im, im.load()


# ------------------------------------------------------------------------------------------------
# What the themes' chat banners share
# ------------------------------------------------------------------------------------------------

TILE, FLOW = 64, 16           # the plate's tile, and how many columns a step an effect runs along the banner
GUST = 108                    # how long an effect may be (columns): it has passed a 64-column part in the 12 steps
CALM = 4                      # frames 0 .. 3: the idle plate; the others: the steps of the effect
GEM7 = ("...#...", "..#a#..", ".#aaab.", "#aawabc", ".bbabc.", "..bcc..", "...c...")


def bar(Geo, title, timeline="loop", end=0):
    """the geometry of a chat banner as the themes draw it: a plain bar with a 64-column tile. timeline: "loop",
    "gust" or "scatter" (see the top). end: columns after the text that the right end keeps for itself."""
    return Geo(title, tile=TILE, flow=0 if timeline == "loop" else FLOW, pointed=False, scatter=timeline == "scatter", end=end)


def corner(G, x, y, radius=3.0):
    """0 .. 1: how much of a pixel is inside the plate with rounded corners (its alpha), for a soft rounded bar"""
    cx = min(x - G.PX0, G.PX1 - x)
    cy = min(y - G.PY0, G.PY1 - y)
    if cx >= radius or cy >= radius:
        return 1.0
    d = math.hypot(radius - cx - 0.5, radius - cy - 0.5)
    return max(0.0, min(1.0, radius - d + 0.5))


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def lattice(ix, iy, seed):
    """a fixed pseudo-random number in [0, 1) for a lattice point"""
    h = (ix * 374761393 + iy * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65536.0


def noise(x, y, cell_x, cell_y, wrap_x, wrap_y, seed):
    """smooth value noise that repeats every wrap_x columns and wrap_y rows"""
    fx, fy = x / cell_x, y / cell_y
    ix, iy = math.floor(fx), math.floor(fy)
    tx, ty = fx - ix, fy - iy
    tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    nx, ny = wrap_x // cell_x, wrap_y // cell_y
    v = lambda a, b: lattice(a % nx, b % ny, seed)
    top = v(ix, iy) + (v(ix + 1, iy) - v(ix, iy)) * tx
    bot = v(ix, iy + 1) + (v(ix + 1, iy + 1) - v(ix, iy + 1)) * tx
    return top + (bot - top) * ty


def fbm(x, y, seed):
    """three octaves of smooth noise, 0 .. 1 (not repeating: for an effect, drawn by how far behind its front)"""
    return (0.55 * noise(x, y, 8, 5, 1 << 20, 1 << 12, seed) + 0.30 * noise(x, y, 4, 3, 1 << 20, 3 << 10, seed + 1)
            + 0.15 * noise(x, y, 2, 2, 1 << 20, 1 << 12, seed + 2))


def strength(u, rise=14, body=48, total=GUST):
    """how strong an effect is u columns behind its front: up over rise, full to body, a tail to total (smooth)"""
    if u <= 0 or u >= total:
        return 0.0
    return smooth(u / rise) * (1.0 if u < body else 1 - smooth((u - body) / (total - body)))


def ramp(stops):
    """((value, colour), ...) -> f(value, base): the colour for a value, a smooth ramp; under the first stop it
    fades into base (the plate's own colour there)"""
    def f(h, base):
        if h <= stops[0][0]:
            return lerp(base, stops[0][1], max(0.0, h) / stops[0][0])
        for (h0, c0), (h1, c1) in zip(stops, stops[1:]):
            if h <= h1:
                return lerp(c0, c1, (h - h0) / (h1 - h0))
        return stops[-1][1]
    return f


def end_depth(G, x, y, seed, shallow=3.5, deep=11.0):
    """For shaping a plate's end: (columns from the nearer end, how deep the end is eaten away in this row). The
    depth differs from row to row (smoothly) and between the two ends; with deep under 13 the shaped end and three
    columns behind it stay inside the 16 columns of the end part."""
    left = x - G.PX0 < G.PX1 - x
    d = x - G.PX0 if left else G.PX1 - x
    return d, shallow + (deep - shallow) * noise(y, 0, 3, 4, 1 << 10, 16, seed if left else seed + 8)


def gem7(px, G, colours):
    """the gem between title and nickname (7 x 7, colours for # a w b c): only in the gem pass, where it is recorded
    (title_banners.py makes it glyphs of the title font)"""
    if state.GEM["mode"] != "gem":
        return
    for r, row in enumerate(GEM7):
        for c, v in enumerate(row):
            if v != ".":
                q = (G.DIV - 3 + c, G.MID - 3 + r)
                state.GEM["pix"].add(q)
                px[q] = tuple(colours[v]) + (255,)


def framed(Geo, title, grow=16, centre=0):
    """the geometry of a chat banner drawn like the full banner's plate: pointed ends, edge and bevel, a loop of 16
    frames; the plate grows in steps of grow columns"""
    return Geo(title, tile=TILE, grow=grow, centre=centre)


def put(px, G, x, y, c, a=255):
    x, y = int(x), int(y)
    if 0 <= x < G.W and 0 <= y < G.H:
        px[x, y] = tuple(c[:3]) + (a,)


def blend(px, G, x, y, c, a):
    """colour c with alpha a (0 .. 255) over the pixel"""
    x, y = int(x), int(y)
    if not (0 <= x < G.W and 0 <= y < G.H) or a <= 0:
        return
    o = px[x, y]
    oa, na = o[3] / 255, a / 255
    ra = na + oa * (1 - na)
    px[x, y] = tuple(int(round((c[k] * na + o[k] * oa * (1 - na)) / ra)) for k in range(3)) + (int(round(ra * 255)),)


def line(x0, y0, x1, y1):
    """the pixels of a line (Bresenham)"""
    x0, y0, x1, y1 = int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1))
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err, out = dx + dy, []
    while True:
        out.append((x0, y0))
        if x0 == x1 and y0 == y1:
            return out
        e2 = 2 * err
        if e2 >= dy:
            err += dy; x0 += sx
        if e2 <= dx:
            err += dx; y0 += sy


def plate(px, G, fill, trim, bevel):
    """the plate as the full banners draw it: trim(x, y) on its edge, bevel(x, y) on the ring inside it,
    fill(x, y, g) inside (g: 0 at its top row .. 1 at its bottom row); a function may give (colour, alpha)"""
    for (x, y) in G.inside:
        c = trim(x, y) if (x, y) in G.edge else bevel(x, y) if (x, y) in G.bevel else fill(x, y, (y - G.PY0) / (G.PY1 - G.PY0))
        if c is None:
            continue
        px[x, y] = (tuple(c[0]) + (int(c[1]),)) if isinstance(c[0], (tuple, list)) else tuple(c[:3]) + (255,)


def gem_pixels(px, pixels):
    """a gem of the theme's own shape: {(x, y): colour}; only in the gem pass, where it is recorded"""
    if state.GEM["mode"] != "gem":
        return
    for q, c in pixels.items():
        state.GEM["pix"].add(q)
        px[q] = tuple(c) + (255,)
