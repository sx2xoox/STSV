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
# The chat banner (banner_themes/chat.py: one chat line; idle, then the effect runs along it from left to right)
# ------------------------------------------------------------------------------------------------

from . import chat as C


def chat_geo(Geo):
    return C.bar(Geo, TITLE)


CHAT_GEM = {"#": (255, 200, 244), "a": (255, 110, 220), "w": (255, 240, 252), "b": (200, 40, 170), "c": (90, 16, 90)}


def chat_frame(G, i):
    """a dark plate between a cyan and a magenta tube; a pulse of light runs along it: both tubes flare, their light
    floods the plate, a slanted beam at its head, streaks of light in its wake"""
    im, px = G.canvas()
    top, base = G.PY0 + 2, G.PY1 - 2
    rows = base - top + 1
    step = i - C.CALM
    for (x, y) in G.inside:
        u = G.flow_at(x, step) if step >= 0 else -1
        e = C.strength(u, rise=6, body=30, total=100)
        wave = 0.5 + 0.5 * math.cos(2 * math.pi * (x - G.TILE0) / 64)
        if y == G.PY0: c = C.lerp(C.lerp((36, 186, 226), (150, 246, 255), wave), (236, 254, 255), 0.85 * e)
        elif y == G.PY1: c = C.lerp(C.lerp((206, 42, 166), (255, 128, 226), wave), (255, 230, 250), 0.85 * e)
        else:
            v = (y - top) / (rows - 1)                       # 0 under the cyan tube .. 1 over the magenta one
            c = C.lerp((22, 11, 38), (11, 6, 22), v)
            reach = 2.2 + 5.5 * e                            # how far the tubes' light gets into the plate
            cy = (0.34 + 0.6 * e) * math.exp(-(y - G.PY0 - 1) / reach)
            mg = (0.34 + 0.6 * e) * math.exp(-(G.PY1 - 1 - y) / reach)
            c = C.lerp(c, CYA, min(0.9, cy))
            c = C.lerp(c, MAG, min(0.9, mg))
            if u > 0:
                lean = u - 0.9 * (base - y)                  # the beam leans forward at the top
                if 0 <= lean < 6: c = C.lerp(c, (232, 250, 255), 0.85 * (1 - lean / 6) * min(1.0, u / 6))
                streak = C.noise(u, y - top, 16, 1, 1 << 20, 1 << 10, 61)
                if streak > 0.66: c = C.lerp(c, C.lerp(CYA_L, (255, 170, 236), v), 0.55 * e * (streak - 0.66) / 0.34)
        # the ends: the light just fades out
        d = min(x - G.PX0, G.PX1 - x)
        a = int(255 * C.smooth((d - 0.5) / 10))
        if a <= 0: continue
        px[x, y] = tuple(c) + (a,)
    C.gem7(px, G, CHAT_GEM)
    return im
