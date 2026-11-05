"""
A tiny lit pixel renderer for 16x16 item icons, so every icon is shaded the same way vanilla items are.

Each icon is built from a few shapes (ellipsoids, tapered tubes, flat or faceted polygons), each with a
palette material. A shape knows its surface direction at every pixel; the pixel's brightness is the light
from the top-left falling on it, quantised to the material's ramp. On top of that, the same three rules
for every icon:

  * the silhouette is cut on the exact 16x16 grid (a pixel is in if most of it is covered),
  * a shape lying on another casts a one-pixel contact shadow down-right onto it,
  * the outline is the darkest step of the material it surrounds (or a darker material for pale ones).

Ramps here come from the same palette formula (palette.MATERIALS) but with 6 steps and a little more
range, which is what small icons need: step 0 is the outline, 1..4 the shading, 5 the specular glint.
"""

import math

from PIL import Image

from . import palette as P

W = H = 16
STEPS = 6
LIGHT = (-0.58, -0.68, 0.46)                      # from the top-left, a little in front
_n = math.sqrt(sum(c * c for c in LIGHT))
LIGHT = tuple(c / _n for c in LIGHT)

_RAMPS = {}

# pale materials whose own darkest step is still too light to read as an outline
OUTLINE = {}


# Materials that shine (metal, gems, glass, glaze): like vanilla ingots and gems their ramp runs from a deep,
# still-coloured shadow up to a near-white glint. Measured on 26.3 items: iron/gold/copper ingots, diamond,
# emerald and amethyst all span lightness ~0.35 -> 1.0; even raw ore lumps reach ~0.95.
SHINY = {"steel", "blacksteel", "gold", "copper", "brass", "blade", "ruby", "sapphire", "topaz", "crystal", "pearl",
         "glass", "bottle_green", "porcelain", "obsidian", "aubergine", "fish_silver", "fish_blue"}
# dark shiny materials keep a dark range, with only a soft top (blued steel, netherite-like)
TOP = {"blacksteel": 0.80, "obsidian": 0.70, "fish_blue": 0.80, "aubergine": 0.82, "copper": 0.88}
# bright metals stay bright through the mids (vanilla gold is buttery yellow, not mustard)
CURVE = {"gold": (0.46, 0.85), "brass": (0.40, 1.0), "pearl": (0.55, 1.0)}     # material -> (lowest lightness, curve)


def ramp(name):
    """6-step icon ramp of a palette material: the palette formula (hue, chroma, hue shift towards the
    light and shadow colours), with the wider range small icons need."""
    if name not in _RAMPS:
        hue, chroma, center, span, _steps, accent = P.MATERIALS[name]
        shiny = name in SHINY
        lo = max(0.22, center - span * (0.9 if shiny else 0.8))
        hi = TOP.get(name, 0.985) if shiny else min(0.95, center + span * 0.95)
        gamma = 1.45 if shiny else 1.0
        if name in CURVE:
            lo, gamma = CURVE[name]
        out = []
        for i in range(STEPS):
            t = i / (STEPS - 1)
            # shiny: the lower steps stay deep and saturated, only the top steps climb to the glint
            L = lo + (hi - lo) * t ** gamma
            h = hue[0] + (hue[1] - hue[0]) * t if isinstance(hue, tuple) else hue
            edge = abs(t - 0.5) * 2
            h = P._toward(h, P.LIGHT_HUE if t > 0.5 else P.SHADOW_HUE, P.HUE_SHIFT * edge * (0.5 if accent else 1.0))
            boost = (P.ACCENT_CHROMA_BOOST if accent else P.CHROMA_BOOST) * 1.12
            if shiny:
                fade = 1.0 if t < 0.7 else 1.0 - (t - 0.7) / 0.3 * 0.7     # colour held to the mids, the glint goes white
            else:
                fade = 1.0 - 0.15 * edge
            c = chroma * boost * fade
            if chroma < 0.02:
                c = max(c, 0.008)
            out.append(P.oklch_to_rgb(L, c, h % 360))
        _RAMPS[name] = out
    return _RAMPS[name]


def color(name, step):
    r = ramp(name)
    return (*r[max(0, min(len(r) - 1, step))], 255)


def _norm(v):
    n = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / n for c in v)


def shade_step(normal, lo=1, hi=4, gloss=0.0, bias=0.0):
    """Brightness of a surface facing `normal` as a ramp step in lo..hi; above `gloss` it gets the glint."""
    nx, ny, nz = _norm(normal)
    b = nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2] + bias         # -1 .. 1
    if gloss and b > gloss:
        return STEPS - 1
    t = max(0.0, min(1.0, (b + 0.35) / 1.25))
    return lo + int(round(t * (hi - lo)))


class Canvas:
    def __init__(self):
        self.px = {}          # (x, y) -> [material, step, z, shape id]
        self._id = 0
        self.ink = {}         # material -> outline colour (material, step) override

    # -- shapes ------------------------------------------------------------------------------------

    def _shape(self, inside, normal, mat, z, lo, hi, gloss, bias, sub=4):
        self._id += 1
        sid = self._id
        for y in range(H):
            for x in range(W):
                hits = 0
                for sy in range(sub):
                    for sx in range(sub):
                        if inside(x + (sx + 0.5) / sub, y + (sy + 0.5) / sub):
                            hits += 1
                if hits * 2 < sub * sub:
                    continue
                cur = self.px.get((x, y))
                if cur and cur[2] > z:
                    continue
                n = normal(x + 0.5, y + 0.5)
                step = n if isinstance(n, int) else shade_step(n, lo, hi, gloss, bias)
                self.px[(x, y)] = [mat, step, z, sid]
        return self

    def ellipse(self, cx, cy, rx, ry, mat, z=0, lo=1, hi=4, gloss=0.86, dome=1.0, bias=0.0):
        """An ellipsoid seen from the front (a ball, a berry, a bun when `dome` < 1)."""
        def inside(x, y):
            return ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1.0

        def normal(x, y):
            u, v = (x - cx) / rx, (y - cy) / ry
            return (u * dome, v * dome, math.sqrt(max(0.02, 1 - u * u - v * v)))
        return self._shape(inside, normal, mat, z, lo, hi, gloss, bias)

    def tube(self, pts, radii, mat, z=0, lo=1, hi=4, gloss=0.88, flat=1.0, bias=0.0, caps=True):
        """A tube along a polyline with a radius at each point (a fish, a loaf, a bottle neck, a cucumber).
        Shaded round across its width; `flat` < 1 makes it flatter (a fillet, a slab)."""
        segs = list(zip(pts, pts[1:], radii, radii[1:]))

        def near(x, y):
            best = None
            for (ax, ay), (bx, by), ra, rb in segs:
                dx, dy = bx - ax, by - ay
                ln = dx * dx + dy * dy or 1e-9
                t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / ln))
                if not caps and (t <= 0.0 or t >= 1.0) and len(segs) == 1:
                    pass
                qx, qy = ax + dx * t, ay + dy * t
                r = ra + (rb - ra) * t
                d = math.hypot(x - qx, y - qy)
                if best is None or d / max(r, 1e-6) < best[0]:
                    best = (d / max(r, 1e-6), (x - qx) / max(r, 1e-6), (y - qy) / max(r, 1e-6))
            return best

        def inside(x, y):
            return near(x, y)[0] <= 1.0

        def normal(x, y):
            k, u, v = near(x, y)
            return (u * flat, v * flat, math.sqrt(max(0.02, 1 - min(1.0, k) ** 2)))
        return self._shape(inside, normal, mat, z, lo, hi, gloss, bias)

    def cyl(self, x0, y0, x1, y1, mat, z=0, lo=1, hi=4, gloss=0.86, bias=0.0, rounded=0.0):
        """An upright cylinder seen from the side (a jar, a barrel, a tin, a bottle): round across, straight
        up and down. `rounded` > 0 rounds the bottom corners by that many pixels."""
        cx, r = (x0 + x1) / 2, (x1 - x0) / 2

        def inside(x, y):
            if not (x0 <= x <= x1 and y0 <= y <= y1):
                return False
            if rounded and y > y1 - rounded:
                dy = (y - (y1 - rounded)) / rounded
                return abs(x - cx) / r <= math.sqrt(max(0.0, 1 - dy * dy)) * 0.35 + 0.65 * (1 - dy * dy) ** 0.5
            return True

        def normal(x, y):
            u = max(-1.0, min(1.0, (x - cx) / r))
            return (u, -0.15, math.sqrt(max(0.02, 1 - u * u)))
        return self._shape(inside, normal, mat, z, lo, hi, gloss, bias)

    def poly(self, pts, mat, z=0, normal=(0, 0, 1), step=None, lo=1, hi=4, gloss=0.0, bias=0.0):
        """A flat polygon: one surface direction (a facet, a plate, the side of a box), or one fixed step."""
        def inside(x, y):
            c = False
            j = len(pts) - 1
            for i in range(len(pts)):
                (xi, yi), (xj, yj) = pts[i], pts[j]
                if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                    c = not c
                j = i
            return c
        n = step if step is not None else normal
        return self._shape(inside, lambda x, y: n, mat, z, lo, hi, gloss, bias)

    def rect(self, x0, y0, x1, y1, mat, z=0, **kw):
        return self.poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], mat, z, **kw)

    # -- pixels ------------------------------------------------------------------------------------

    def put(self, x, y, mat, step, z=99):
        if 0 <= x < W and 0 <= y < H:
            self._id += 1
            self.px[(x, y)] = [mat, step, z, self._id]
        return self

    def dots(self, pts, mat, step, z=99):
        for x, y in pts:
            self.put(x, y, mat, step, z)
        return self

    def line(self, x0, y0, x1, y1, mat, step, z=99):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for k in range(n + 1):
            self.put(round(x0 + (x1 - x0) * k / n), round(y0 + (y1 - y0) * k / n), mat, step, z)
        return self

    def last(self):
        """Pixels of the most recently drawn shape (to add streaks, bands and edges to it)."""
        sid = self._id
        return [p for p, v in self.px.items() if v[3] == sid]

    def lit_edge(self, pts, delta=1):
        """Brightens the pixels of `pts` that face the light (their top or left neighbour is not in `pts`)."""
        s = set(pts)
        return self.tone([(x, y) for x, y in pts if (x - 1, y) not in s or (x, y - 1) not in s], delta)

    def hole(self, cx, cy, rx, ry):
        """Cuts an elliptical hole (a ring, a chain link, the inside of a handle)."""
        return self.erase([(x, y) for x in range(W) for y in range(H)
                           if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1.0])

    def erase(self, pts):
        for p in pts:
            self.px.pop(p, None)
        return self

    def tone(self, pts, delta):
        """Shift existing pixels lighter (+) or darker (-) within their ramp."""
        for p in pts:
            if p in self.px:
                self.px[p][1] = max(0, min(STEPS - 1, self.px[p][1] + delta))
        return self

    def where(self, mat=None):
        return [p for p, v in self.px.items() if mat is None or v[0] == mat]

    # -- finishing ---------------------------------------------------------------------------------

    def clipped(self):
        """Shape pixels on the edge of the 16x16 grid: there is no room for their outline."""
        return sorted(p for p in self.px if p[0] in (0, W - 1) or p[1] in (0, H - 1))

    def render(self, outline=True, contact=True):
        px = {k: list(v) for k, v in self.px.items()}
        if contact:
            # a pixel lying just down-right of a pixel of a higher shape is in its shadow
            for (x, y), v in list(px.items()):
                for dx, dy in ((-1, 0), (0, -1), (-1, -1)):
                    o = self.px.get((x + dx, y + dy))
                    if o and o[2] > v[2] and o[3] != v[3] and o[0] != v[0]:
                        v[1] = max(1, v[1] - 1)
                        break
        if outline:
            # only the outside gets an outline: enclosed holes (a ring, a chain link) stay see-through
            outside, todo = set(), [(x, y) for x in range(W) for y in (0, H - 1)] + [(x, y) for y in range(H) for x in (0, W - 1)]
            while todo:
                q = todo.pop()
                if q in outside or q in px or not (0 <= q[0] < W and 0 <= q[1] < H):
                    continue
                outside.add(q)
                todo += [(q[0] + 1, q[1]), (q[0] - 1, q[1]), (q[0], q[1] + 1), (q[0], q[1] - 1)]
            border = {}
            for (x, y), v in px.items():
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    p = (x + dx, y + dy)
                    if p not in outside:
                        continue
                    if p not in border or v[2] > border[p][1]:
                        border[p] = (self.ink.get(v[0]) or OUTLINE.get(v[0]) or (v[0], 0), v[2])
            for p, (ink, _z) in border.items():
                px[p] = [ink[0], ink[1], -1, 0]
        im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        for (x, y), v in px.items():
            im.putpixel((x, y), color(v[0], v[1]))
        return im
