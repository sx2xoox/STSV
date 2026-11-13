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


# sparkles inside the glass, the same every 64 columns: its cell of 16 columns, column in the cell, row, frame offset
CHAT_SPARKS = ((0, 9, 4, 0), (2, 6, 12, 8), (3, 10, 6, 4))


def chat_sparkle(px, G, x, y, size, col):
    for d in range(-size, size + 1):
        for q in ((x + d, y), (x, y + d)):
            if G.PY0 <= q[1] <= G.PY1: C.put(px, G, q[0], q[1], (255, 255, 255) if d == 0 else col)


def chat_frame(G, i):
    t = TAU * (i % N) / N
    ph = i % N
    im, px = G.canvas()
    # rainbow rays fanning out of both points (they stay inside the chat line's height)
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for k, col in enumerate(SPECTRUM):
            ang = (k - 2.5) * 0.22
            L = 13 + 1.8 * math.sin(t + k * 0.7) - abs(k - 2.5) * 0.8
            dy = math.sin(ang)
            pts = C.line(x0 + side * 2, G.MID + 0.5 + dy, x0 + side * (2 + L * math.cos(ang)), G.MID + 0.5 + L * dy)
            n = len(pts)
            for j, (x, y) in enumerate(pts):
                if not G.PY0 <= y <= G.PY1: continue
                f = j / max(1, n - 1)
                pulse = math.exp(-((f - (ph / N)) * 6) ** 2) + math.exp(-((f - (ph / N) + 1) * 6) ** 2)
                C.blend(px, G, x, y, lerp(col, (255, 255, 255), 0.35 * pulse), int(255 * min(1.0, (1 - f) / 0.35) * (0.7 + 0.3 * pulse)))
        for y in (G.MID, G.MID + 1): C.put(px, G, x0 + side, y, (255, 255, 255))

    # the glass plate: partial alpha, narrow spectra drifting through clear glass (the same every 32 columns)
    def glass(x, y):
        sx = (x - G.TILE0) + (y - G.PY0) * 0.8 - 2 * ph
        q = sx % 32
        return (hsv(0.95 - q / 11 * 0.82, 0.5, 1.0), math.sin(math.pi * (q + 0.5) / 12)) if q < 11 else (None, 0.0)

    def fill(x, y, g):
        band, a = glass(x, y)
        if y == G.PY0 + 2 and band is None: return (255, 255, 255), 120          # the caustic under the top bevel
        if band: return lerp((196, 212, 255), band, min(1, a * 1.4)), int(60 + 100 * a - 20 * g)
        return (196, 212, 255), 52 - int(14 * g)

    def bev(x, y):
        if y <= G.MID:
            near = (x - G.TILE0 - 4 * ph) % 64 < 8                               # the glint running along the bevel
            return ((255, 255, 255), 255) if near else (SIL_L, 210)
        return (glass(x, y)[0] or (220, 230, 255)), 170
    C.plate(px, G, fill, lambda x, y: SIL_L if y < G.MID - 3 else SIL if y <= G.MID + 4 else SIL_D, bev)
    # sparkles: at the glass's corners and inside it
    spots = [(G.PX0 + 9, G.PY0 + 1, 0), (G.PX1 - 9, G.PY1 - 1, 8), (G.PX1 - 9, G.PY0 + 1, 6), (G.PX0 + 9, G.PY1 - 1, 14)]
    for x0 in range(G.TILE0, G.R0 - 15, 64):
        spots += [(x0 + cell * 16 + col, G.PY0 + row, off) for cell, col, row, off in CHAT_SPARKS if x0 + cell * 16 + 15 < G.R0]
    for x, y, off in spots:
        p = (ph + off) % N
        if p < 4: chat_sparkle(px, G, x, y, (1, 2, 2, 1)[p], (220, 230, 255))
    C.gem7(px, G, {"#": (255, 255, 255), "a": (230, 236, 255), "w": (255, 255, 255), "b": (170, 180, 250), "c": (110, 110, 190)})
    return im
