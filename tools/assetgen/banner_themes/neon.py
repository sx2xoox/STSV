"""9 neon: circuit traces with travelling pulses, a stuttering magenta tube, chevron marquees (approved art t09)."""
import math, random
from .lib_a import *
TITLE, SLUG = "네온 질주자", "neon"
GEO = dict(side=14)
STATIC = 2
MAG, MAG_D, MAG_G = (255, 64, 206), (120, 30, 100), (255, 60, 200)
CYA, CYA_D, CYA_L = (60, 236, 255), (20, 90, 120), (200, 252, 255)
TRACE = (34, 96, 132)

def traces(G):
    """symmetric circuit traces rising from the top edge: lists of points (polyline), each ending in a node."""
    T = []
    for side in (-1, 1):
        for o, up1, diag, up2 in ((8, 3, 4, 4), (19, 5, 3, 2), (31, 2, 5, 5), (43, 4, 3, 1)):
            x, y = G.CX + side * o, G.PY0 - 1
            pts = [(x, y)]
            for _ in range(up1): y -= 1; pts.append((x, y))
            for _ in range(diag): x += side; y -= 1; pts.append((x, y))
            for _ in range(up2): y -= 1; pts.append((x, y))
            T.append(pts)
    # centre antenna
    x, y = G.CX, G.PY0 - 1
    T.append([(x, y - k) for k in range(0, 13)])
    return T

def frame(G, i):
    t = 2 * math.pi * i / N
    im, px = G.canvas()
    flick_m = i in (5, 12)            # magenta tube stutters
    flick_c = i == 13
    glitch = i in (6, 13)
    # circuit traces with travelling pulses, nodes light when the pulse arrives
    for k, pts in enumerate(traces(G)):
        n = len(pts)
        for x, y in pts: put(px, G, x, y, TRACE)
        head = ((i * 2 + k * 5) % 24)
        for d in range(3):
            h = head - d
            if 0 <= h < n: put(px, G, *pts[h], (CYA_L, CYA, (40, 170, 210))[d])
        ex, ey = pts[-1]
        lit = head >= n - 1 and head < n + 6
        col = CYA_L if lit else CYA if k % 2 else MAG
        if k == len(traces(G)) - 1:   # antenna tip blinks magenta
            col = MAG if (i // 2) % 2 == 0 else MAG_D
        for a, b in ((0, -1), (-1, 0), (1, 0), (0, 0), (-1, -1), (1, -1)):
            put(px, G, ex + a, ey - 1 + b, col if (a, b) != (0, 0) else ((10, 20, 30) if not lit else CYA_L))
        if lit:
            for a, b in ((0, -3), (-2, -1), (2, -1)): blend(px, G, ex + a, ey - 1 + b, CYA, 120)
    # neon glow round the plate
    if not flick_m:
        glow(px, G, MAG_G, 150, ring=2)
    else:
        glow(px, G, MAG_G, 50, ring=1)
    def fill(x, y, g):
        c = lerp((16, 10, 34), (4, 2, 10), g)
        if y % 2 == 0: c = lerp(c, (0, 0, 0), 0.35)                 # scanlines
        if y == G.PY0 + 2 + (i * 15 // N) % 13: c = lerp(c, (60, 40, 110), 0.6)   # rolling bright line
        return c
    plate(px, G, fill, None, None,
          bevel=lambda x, y: CYA_D if flick_c else (CYA if y < G.MID else (40, 190, 230)),
          trim=lambda x, y: MAG_D if flick_m else (MAG if y < G.MID else (220, 40, 170)))
    # end chevrons: a marquee of two neon brackets
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for n_, off in enumerate((3, 6)):
            on = (i // 2 + n_) % 3 != 0
            col = (CYA if n_ == 0 else MAG) if on else ((20, 70, 90) if n_ == 0 else (90, 20, 70))
            for r in range(-3, 4):
                put(px, G, x0 + side * (off + 3 - abs(r)), G.MID + r, col)
    # gem: hollow neon diamond
    cx, cy = G.DIV, G.MID
    for a, b in ((0, -2), (-1, -1), (1, -1), (-2, 0), (2, 0), (-1, 1), (1, 1), (0, 2)):
        gem_put(px, G, cx + a, cy + b, MAG if not flick_m else MAG_D)
    gem_put(px, G, cx, cy, (255, 255, 255))
    texts(px, G, i,
          ramp((0, (255, 255, 255)), (0.3, (190, 250, 255)), (0.75, (70, 210, 255)), (1, (40, 140, 240))),
          ramp((0, (255, 255, 255)), (0.35, (255, 170, 236)), (1, (255, 60, 200))),
          (24, 0, 36))
    if glitch:
        # slice a band of the plate sideways with a chromatic split
        y0, y1, d = (G.MID - 3, G.MID, 2) if i == 6 else (G.MID + 1, G.MID + 3, -2)
        rows = {y: [px[x, y] for x in range(G.W)] for y in range(y0, y1 + 1)}
        for y, row in rows.items():
            for x in range(G.PX0 + 3, G.PX1 - 2):
                sx = x - d
                if (x, y) in G.inside and (sx, y) in G.inside:
                    px[x, y] = row[sx]
            for x in range(G.PX0 + 3, G.PX1 - 2):
                if (x, y) in G.text_set or (x - d, y) in G.text_set:
                    c = px[x, y]
                    if sum(c[:3]) > 500:
                        put(px, G, x - 1, y, CYA); put(px, G, x + 1, y, MAG)
    return im


# ------------------------------------------------------------------------------------------------
# The chat banner (banner_themes/chat.py: one chat line). A dark sign in a tube of neon: light chases round the tube, the magenta side stutters.
# ------------------------------------------------------------------------------------------------

from . import chat as C


def chat_geo(Geo):
    return C.bar(Geo, TITLE, "loop")


CHAT_GEM = {"#": (255, 200, 244), "a": (255, 110, 220), "w": (255, 240, 252), "b": (200, 40, 170), "c": (90, 16, 90)}
CHAT_STUTTER = {9: 0.35, 11: 0.5}                            # the frames the magenta half of the tube dims in


def chat_frame(G, i):
    im, px = G.canvas()
    top, base = G.PY0 + 2, G.PY1 - 2
    rows = base - top + 1
    R = 4.0                                                  # the tube goes round the plate: its corners' radius
    for (x, y) in G.inside:
        cx, cy = min(x - G.PX0, G.PX1 - x), min(y - G.PY0, G.PY1 - y)
        rim = R - math.hypot(R - cx - 0.5, R - cy - 0.5) if cx < R and cy < R else min(cx, cy) + 0.5   # into the plate
        if rim <= 0: continue
        v = (y - G.PY0) / (G.PY1 - G.PY0)                    # 0: the cyan half .. 1: the magenta half
        tube = C.lerp(CYA, MAG, C.smooth((v - 0.3) / 0.4))
        # the chase: a brighter stretch moving right along the top and left along the bottom, 4 columns a frame
        along = (x - G.TILE0 - 4 * i) if v < 0.5 else (-(x - G.TILE0) - 4 * i)
        chase = max(0.0, math.cos(2 * math.pi * along / 64)) ** 3
        lit = (0.55 + 0.45 * chase) * (CHAT_STUTTER.get(i, 1.0) if v > 0.5 else 1.0)
        dark = C.lerp((16, 8, 30), (8, 5, 18), v)
        if rim < 1.0:
            c = C.lerp(C.lerp(dark, tube, 0.55), C.lerp(tube, (255, 255, 255), 0.62), lit)
            a = int(255 * min(1.0, rim + 0.35))
        else:
            # inside: the tube's glow falling off, faint bars sliding by behind the words
            c = C.lerp(dark, tube, 0.62 * lit * math.exp(-(rim - 1.0) / 1.5))
            if (x - G.TILE0 + i) % 16 == 0: c = C.lerp(c, (70, 36, 128), 0.30)
            glow = math.exp(-(G.PY1 - 1 - y) / 3.2)
            c = C.lerp(c, MAG, 0.20 * glow * CHAT_STUTTER.get(i, 1.0))
            a = 255
        px[x, y] = tuple(c) + (a,)
    C.gem7(px, G, CHAT_GEM)
    return im
