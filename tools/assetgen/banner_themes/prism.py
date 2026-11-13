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
