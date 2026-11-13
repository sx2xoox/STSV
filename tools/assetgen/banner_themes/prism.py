"""21 prism: translucent glass plate, spectral bands sliding through it, specular glints on the bevel, rainbow rays
fanning out of both points, crisp sparkles (approved art t21)."""
import math, random
from .lib_b import *

TITLE = "빛의 굴절"
SIL_L = (250, 252, 255); SIL = (206, 214, 228); SIL_D = (150, 160, 182)
SPECTRUM = [(255, 120, 130), (255, 184, 110), (255, 240, 130), (130, 236, 160), (120, 200, 255), (180, 150, 255)]


def draw(G, i):
    t = TAU * (i % N) / N
    ph = i % N
    cv = Cv(G)
    # ---- rainbow rays fanning out of both points (behind the plate)
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for k, col in enumerate(SPECTRUM):
            ang = (k - 2.5) * 0.26
            L = 11 + 1.8 * math.sin(t + k * 0.7) - abs(k - 2.5) * 0.8
            dx, dy = side * math.cos(ang), math.sin(ang)
            pts = line_pts(x0 + side * 2, G.MID + dy * 1, x0 + side * (2 + L * math.cos(ang)), G.MID + L * dy)
            n = len(pts)
            for j, (x, y) in enumerate(pts):
                f = j / max(1, n - 1)
                # a brighter pulse running out along the rays
                pulse = math.exp(-((f - (ph / N)) * 6) ** 2) + math.exp(-((f - (ph / N) + 1) * 6) ** 2)
                a = int(255 * min(1.0, (1 - f) / 0.35) * (0.7 + 0.3 * pulse))
                c = lerp(col, (255, 255, 255), 0.35 * pulse)
                cv.blend(x, y, c, a)
        cv.put(x0 + side * 1, G.MID, (255, 255, 255))
    # ---- glass plate: partial alpha, spectral bands slide through it
    for (x, y) in G.inside:
        g = (y - G.PY0) / 16
        if (x, y) in G.edge:
            c = SIL_L if y < G.MID - 3 else SIL if y <= G.MID + 3 else SIL_D
            cv.put(x, y, c); continue
        # refraction streaks: narrow diagonal spectra drifting through clear glass
        sx = (x - G.PX0) + (y - G.PY0) * 0.8 - 30 * ph / N
        q = sx % 30
        band = hsv(0.95 - q / 10 * 0.82, 0.5, 1.0) if q < 10 else None
        bandA = math.sin(math.pi * (q + 0.5) / 11) if q < 10 else 0
        if (x, y) in G.bevel:
            if y < G.MID:
                d = abs(x - (G.PX0 - 10 + (G.PW + 20) * ph / N))
                cv.put(x, y, (255, 255, 255) if d < 5 else SIL_L, 255 if d < 5 else 210)
            else:
                cv.put(x, y, band or (220, 230, 255), 170)
            continue
        if band:
            cv.put(x, y, lerp((196, 212, 255), band, min(1, bandA * 1.4)), int(60 + 100 * bandA - 20 * g))
        else:
            cv.put(x, y, (196, 212, 255), 52 - int(14 * g))
    # inner refraction line: a thin pastel caustic under the top bevel
    for x in range(G.PX0 + 7, G.PX1 - 6):
        y = G.PY0 + 2
        if (x, y) in G.text_near: continue
        cv.put(x, y, (255, 255, 255), 120)
    gem(cv, G.DIV, G.MID, (255, 255, 255), (230, 236, 255), (190, 200, 255), (150, 160, 230), (110, 110, 190), top=(255, 255, 255))
    texts(cv, G, i, ramp((0, (255, 255, 255)), (0.35, (240, 244, 255)), (0.75, (200, 206, 232)), (1, (160, 166, 204))),
          ramp((0, (255, 255, 255)), (0.5, (236, 230, 255)), (1, (190, 176, 250))), (28, 28, 52), shine_col=(255, 255, 255))
    # ---- sparkles at the glass corners and around
    spots = [(G.PX0 + 6, G.PY0, 0), (G.PX1 - 6, G.PY1, 8), (G.CX - 20, G.PY0 - 3, 4), (G.CX + 24, G.PY1 + 3, 12),
             (G.PX1 - 6, G.PY0, 6), (G.PX0 + 6, G.PY1, 14)]
    for (x, y, off) in spots:
        p = (ph + off) % N
        if p < 4:
            sparkle(cv, int(round(x)), int(round(y)), (1, 2, 2, 1)[p], (220, 230, 255))
    return cv.im


SLUG = "prism"
GEO = {}
frame = draw


# ------------------------------------------------------------------------------------------------
# The chat banner (banner_themes/chat.py: one chat line, 18 rows of half pixels): this banner's plate in small, and
# what the full banner shows over its plate (cut off by a chat line) inside it. The spectral bands slide through the glass, the glint runs along its bevel, rays fan out of both points, sparkles.
# ------------------------------------------------------------------------------------------------

from . import chat as C


def chat_geo(Geo):
    return C.framed(Geo, TITLE)


# The chat banner: a shard of dark crystal. Cut to a point at both ends, deep indigo inside (opaque: the words are
# white, and a name tag may hang before a bright sky), a faint facet line every 16 columns. The light is in its
# edges: the cut edge is a spectrum that flows along it (to the right along the top, to the left along the bottom:
# once round in a loop), the bevel inside it a pale glass with a glint running along. A soft sheen crosses the
# crystal once a loop, and stars twinkle on the edge. Off both points the light leaves as a fan of coloured rays.
# Behind the words the crystal stays dark (C.hush).
CHAT_STARS = ((0, 5, 1, 0), (1, 11, -1, 9), (2, 3, -1, 4), (3, 9, 1, 13))     # cell of 16 columns, column, top (1) / bottom (-1), frame offset
CHAT_GEM = {"#": (255, 255, 255), "a": (230, 236, 255), "w": (255, 255, 255), "b": (170, 180, 250), "c": (110, 110, 190)}


def chat_hue(h):
    """a colour of the spectrum, h 0 .. 1 round the wheel: full and bright"""
    return hsv(h % 1.0, 0.62, 1.0)


def chat_frame(G, i):
    im, px = G.canvas()
    ph = i % N
    # rays fanning out of both points (inside the chat line's height), each its own colour, twinkling
    for side in (-1, 1):
        tip = G.PX0 if side < 0 else G.PX1
        for k, (dy, reach) in enumerate(((-7, 9), (-4, 12), (-1, 14), (2, 14), (5, 12), (8, 9))):
            col = chat_hue(k / 6 + ph / N)
            lit = (ph + k * 3 + (side > 0) * 5) % 8
            for n, (x, y) in enumerate(C.line(tip + side * 3, G.MID + (0 if dy < 1 else 1), tip + side * (3 + reach), G.MID + dy)):
                if not G.PY0 <= y <= G.PY1: continue
                a = int((235 if lit < 5 else 120) * (1 - n / (reach + 4)))
                C.blend(px, G, x, y, col, a)

    def body(x, y, g):
        xt = x - G.TILE0
        c = lerp((24, 20, 54), (9, 8, 24), g)
        if (xt + (y - G.PY0)) % 16 == 0: c = lerp(c, (70, 62, 140), 0.45)          # a facet line, leaning
        sheen = max(0.0, math.cos(2 * math.pi * (xt - 4 * ph + 2 * (y - G.MID)) / 64)) ** 12
        c = lerp(c, (120, 110, 220), 0.5 * sheen)
        depth = min(y - G.PY0, G.PY1 - y) - 2
        if depth < 2: c = lerp(c, chat_hue((xt / 64 + (1 if y < G.MID else -1) * ph / N) + (0 if y < G.MID else 0.5)), (0.22, 0.09)[depth])
        return C.hush(c, 0.05)

    def edge(x, y):
        xt = x - G.TILE0
        up = y <= G.MID
        c = chat_hue(xt / 64 - ph / N if up else -xt / 64 - ph / N + 0.5)
        return lerp(c, (255, 255, 255), 0.25) if up else lerp(c, (40, 30, 90), 0.25)

    def bevel(x, y):
        xt = x - G.TILE0
        up = y <= G.MID
        glint = max(0.0, math.cos(2 * math.pi * ((xt - 4 * ph) if up else (-xt - 4 * ph)) / 64)) ** 16
        base = lerp((92, 96, 150), (56, 58, 110), 0.0 if up else 1.0)
        return lerp(base, (255, 255, 255), 0.9 * glint)
    C.plate(px, G, body, edge, bevel)

    # stars on the edge: four to a tile, each flaring for four frames of the loop; one on each point
    spots = [(G.PX0 + 1, G.MID, 2), (G.PX1 - 1, G.MID + 1, 10)]
    for x0 in range(G.TILE0, G.R0 - 15, 64):
        spots += [(x0 + cell * 16 + col, G.PY0 if way > 0 else G.PY1, off) for cell, col, way, off in CHAT_STARS if x0 + cell * 16 + 15 < G.R0]
    for x, y, off in spots:
        k = (ph + off) % N
        if k >= 4: continue
        size = (1, 2, 2, 1)[k]
        for d in range(-size, size + 1):
            for q in ((x + d, y), (x, y + d)):
                if G.PY0 <= q[1] <= G.PY1 and (q in G.edge or q in G.bevel or q not in G.inside):
                    C.put(px, G, q[0], q[1], (255, 255, 255) if d == 0 else (226, 232, 255))
    C.gem7(px, G, CHAT_GEM)
    return im
