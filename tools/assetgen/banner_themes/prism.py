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
# The chat banner (banner_themes/chat.py: one chat line; idle, then the effect runs along it from left to right)
# ------------------------------------------------------------------------------------------------

from . import chat as C


def chat_geo(Geo):
    return C.bar(Geo, TITLE)


CHAT_GEM = {"#": (255, 255, 255), "a": (230, 236, 255), "w": (255, 255, 255), "b": (170, 180, 250), "c": (110, 110, 190)}


def chat_spectrum(s):
    """the colour of a band of light, s 0 .. 1 across it (red .. violet, smooth)"""
    k = max(0.0, min(0.999, s)) * (len(SPECTRUM) - 1)
    return C.lerp(SPECTRUM[int(k)], SPECTRUM[int(k) + 1], k - int(k))


def chat_frame(G, i):
    """a plate of smoked glass you can see through, cut on a slant at both ends; a band of rainbow light slides
    through it, a fainter one after it"""
    im, px = G.canvas()
    top, base = G.PY0 + 2, G.PY1 - 2
    rows = base - top + 1
    step = i - C.CALM
    for (x, y) in G.inside:
        u = G.flow_at(x, step) if step >= 0 else -1
        wave = 0.5 + 0.5 * math.cos(2 * math.pi * (x - G.TILE0) / 64)
        facet = 0.5 + 0.5 * math.sin(2 * math.pi * (x - G.TILE0 + 1.5 * y) / 32)
        if y == G.PY0: c, a = C.lerp((214, 222, 250), (255, 255, 255), wave ** 2), 236
        elif y == G.PY0 + 1: c, a = (190, 200, 244), 130
        elif y == G.PY1 - 1: c, a = (150, 160, 226), 130
        elif y == G.PY1: c, a = C.lerp((170, 178, 232), (206, 212, 250), wave), 210
        else:
            v = (y - top) / (rows - 1)
            c = C.lerp((150, 162, 214), (112, 120, 180), v)
            c = C.lerp(c, (206, 214, 250), 0.25 * facet)
            a = int(96 + 30 * facet)
        if u > 0:
            lean = u - 1.0 * (G.PY1 - y)                     # the band leans forward at the top
            for start, width, weight in ((0, 34, 1.0), (50, 20, 0.45)):
                s = (lean - start) / width
                if 0 <= s < 1:
                    w = weight * C.smooth(s / 0.14) * (1 - C.smooth((s - 0.72) / 0.28))
                    c = C.lerp(c, chat_spectrum(s), 0.82 * w)
                    a = int(a + (238 - a) * 0.85 * w)
        # the ends: the glass is cut on a slant and its edge fades out
        d = min(x - G.PX0, G.PX1 - x)
        cut = d - 0.45 * ((G.PY1 - y) if x - G.PX0 < G.PX1 - x else (y - G.PY0))
        a = int(a * C.smooth((cut - 0.5) / 5))
        if a <= 0: continue
        if cut < 2.5: c = C.lerp(c, (255, 255, 255), 0.6 * (1 - cut / 2.5))
        px[x, y] = tuple(c) + (a,)
    C.gem7(px, G, CHAT_GEM)
    return im
