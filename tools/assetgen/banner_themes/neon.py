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
# The chat banner (banner_themes/chat.py: one chat line, 18 rows of half pixels): this banner's plate in small, and
# what the full banner shows over its plate (cut off by a chat line) inside it. Circuit traces with travelling pulses run inside the plate, the tubes stutter, the picture glitches, chevrons at the ends.
# ------------------------------------------------------------------------------------------------

from . import chat as C


def chat_geo(Geo):
    # a plain bar (the box cuts its own corners), a loop; it grows by whole cells of the screen's grid
    return Geo(TITLE, tile=C.TILE, pointed=False, grow=16)


# the letters jump with the glitch: the pack's text shader moves them in frames 6 and 13 (and shows a cyan or magenta
# ghost beside them); it has their colours, THEMES' neon tints (text.vsh STSV_GLITCH_FROM / _TO)
CHAT_GLITCH = True

# The chat banner: a neon sign box. A black box in a housing of dark metal: a cap with two screws closes it at
# either end, and inside the caps two tubes run round it, a magenta one outside and a cyan one inside it, bending
# round the cut corners. Every 16 columns a metal clip with a screw sits on the tubes and holds them: the tubes
# run on unbroken, only the clip (three columns) covers them. A lit tube
# has a pale hot core; the tubes' light spills on to the first rows of the box. The current runs round the cyan
# tube as a bright spot (to the right along the top, back along the bottom); the magenta tube breathes and stutters
# now and then. Inside, a dark screen: scan lines, a faint grid, and a soft bar of light that sweeps across it; in
# the left end the sign's emblem, a neon star, and a smaller one in the right end. Twice a loop the picture tears
# (frames 6 and 13, when the shader makes the letters jump): the tubes dip, a band of the screen slips sideways and
# a few short dashes of static cross it. Off each end an arm carries a marquee of two big tube chevrons, blinking.
# Behind the words the screen stays dark (C.hush).
CHAT_CUT = 3                          # the tubes' corners are cut off this deep
CHAT_CAP = 3                          # the housing's end caps: columns of the plate
CHAT_METAL_D, CHAT_METAL, CHAT_METAL_L, CHAT_SCREW = (30, 24, 52), (72, 62, 112), (136, 124, 184), (224, 216, 246)
CHAT_STAR = ("...a...", "...a...", "..awa..", "aawwwaa", "..awa..", "...a...", "...a...")


def chat_ring(G, x, y):
    """which ring of the box a pixel is in: 0 the outer tube, 1 the inner tube, 2 and more the screen; -1 the
    corners the tubes cut off, -2 the end caps (both the housing's metal)"""
    dx, dy = min(x - G.PX0, G.PX1 - x) - CHAT_CAP, min(y - G.PY0, G.PY1 - y)
    if dx < 0: return -2
    dc = dx + dy - CHAT_CUT
    if dc < 0: return -1
    return min(dx, dy, (dc + 1) // 2)


def chat_cell(G, x):
    """a column's place in the frame's repeat of 16 columns: counted from the tile's start and, in the right end,
    from that part's own start (the plate grows by 16 columns a step, so the repeat is whole where the parts meet)"""
    return (x - (G.R0 if x >= G.R0 else G.TILE0)) % 16


def chat_frame(G, i):
    im, px = G.canvas()
    t = 2 * math.pi * i / N
    glitch = 1 if i == 6 else 2 if i == 13 else 0
    dim_m = i in (5, 12) or glitch == 2      # the magenta tube stutters
    dim_c = glitch == 1
    breath = 0.5 + 0.5 * math.sin(t)

    def spot(x, y):
        """the current in the cyan tube: a bright spot every 64 columns, 4 columns a frame"""
        xt = x - G.TILE0
        along = xt - 4 * i if y < G.MID else -xt - 4 * i
        return 0.0 if x >= G.R0 else max(0.0, math.cos(2 * math.pi * along / 64)) ** 14

    def screen(x, y):
        xt = x - G.TILE0
        g = (y - G.PY0 - 2) / 13
        c = lerp((17, 10, 36), (6, 3, 14), g)
        if y % 2 == 0: c = lerp(c, (0, 0, 0), 0.38)                        # scan lines
        if chat_cell(G, x) == 0: c = lerp(c, (44, 26, 86), 0.22)           # the grid
        sweep = max(0.0, math.cos(2 * math.pi * (xt - 4 * i - 2 * (y - G.MID)) / 64)) ** 10
        if x < G.R0: c = lerp(c, (74, 46, 150), 0.55 * sweep)              # the bar of light, leaning (not in the right end:
        #                                                                    it follows a different stretch of the tile in every banner)
        depth = min(y - G.PY0, G.PY1 - y) - 2                              # 0 on the row next to the cyan tube
        if depth < 2 and not dim_c: c = lerp(c, CYA, (0.30, 0.12)[depth])
        return C.hush(c, 0.05)

    for (x, y) in G.inside:
        ring = chat_ring(G, x, y)
        d = min(x - G.PX0, G.PX1 - x)
        k = chat_cell(G, x)
        upper = y <= G.MID
        rail = y in (G.PY0, G.PY0 + 1, G.PY1 - 1, G.PY1)
        outer = y in (G.PY0, G.PY1)
        if ring == -2:                                                     # an end cap
            if d == 0 and outer: continue                                  # its corners, rounded off
            g = (y - G.PY0) / (G.PY1 - G.PY0)
            c = lerp(CHAT_METAL_L, CHAT_METAL, g * 1.6) if d == 0 else lerp(CHAT_METAL, CHAT_METAL_D, g) if d == 1 else CHAT_METAL_D
            if d == 1 and y in (G.PY0 + 3, G.PY1 - 3): c = CHAT_SCREW
            if d == 1 and y in (G.PY0 + 4, G.PY1 - 2): c = CHAT_METAL_D
        elif ring == -1:                                                   # a corner the tubes cut off
            c = lerp(CHAT_METAL_D, MAG, 0.10 if dim_m else 0.34)
        elif rail and k < 3 and d >= CHAT_CAP + CHAT_CUT + 2:              # a bracket and its screw
            lit = (outer and upper) or (not outer and not upper)
            c = (CHAT_SCREW if lit and upper else CHAT_METAL_L if lit or outer else CHAT_METAL) if k == 1 else (CHAT_METAL if lit else CHAT_METAL_D)
        elif ring == 0:
            c = MAG_D if dim_m else lerp(lerp(MAG, (255, 255, 255), 0.30 + 0.16 * breath), MAG, 0.0 if upper else 0.5)
        elif ring == 1:
            c = CYA_D if dim_c else lerp(lerp(CYA, (255, 255, 255), 0.22 if upper else 0.06), (255, 255, 255), 0.75 * spot(x, y))
        else:
            c = screen(x, y)
        px[x, y] = tuple(c) + (255,)

    # the picture tears: a band of the screen slips sideways and static crosses it in short dashes (the same every
    # 64 columns); frame 6 a little, frame 13 more. The words are the game's text and jump by the shader.
    if glitch:
        y0, y1, d = (G.MID - 5, G.MID - 3, 3) if glitch == 1 else (G.MID + 1, G.MID + 5, -4)
        rows = {y: {x: px[x, y] for x in range(G.W)} for y in range(y0, y1 + 1)}
        for y, row in rows.items():
            for x in range(G.PX0 + 12, G.PX1 - 11):
                if chat_ring(G, x, y) >= 2 and chat_ring(G, x - d, y) >= 2: px[x, y] = row[x - d]
        for y in ((y0 - 1, y1 + 1) if glitch == 1 else (y0 - 1, y0 + 2, y1 + 1)):
            for x in range(G.PX0 + 12, G.PX1 - 11):
                xt = (x - G.TILE0) % 64
                if C.lattice(xt // 6, y * 3 + i, 301) > 0.72 and xt % 6 < 4 and chat_ring(G, x, y) >= 2:
                    px[x, y] = (lerp(CYA, (0, 0, 0), 0.25) if xt % 6 < 2 else lerp(MAG, (0, 0, 0), 0.25)) + (255,)

    # the emblem: a neon star in the left end of the screen, a smaller one in the right end; they twinkle in turn
    for side, col, size in ((-1, MAG, 3), (1, CYA, 2)):
        sx = (G.PX0 + 8) if side < 0 else (G.PX1 - 8)
        on = ((i // 4) % 2 == 0) == (side < 0) and not glitch
        for r, row in enumerate(CHAT_STAR):
            for c, v in enumerate(row):
                if v == "." or max(abs(c - 3), abs(r - 3)) > size: continue
                if v == "a": C.put(px, G, sx - 3 + c, G.MID - 3 + r, lerp(col, (255, 255, 255), 0.25) if on else lerp(col, (0, 0, 0), 0.45))
                else: C.put(px, G, sx - 3 + c, G.MID - 3 + r, (255, 255, 255) if on else lerp(col, (255, 255, 255), 0.4))

    # the ends: an arm of metal off each cap and on it a marquee of two tube chevrons, a cyan one and a magenta
    # one, each two columns thick (a pale core, a metal cap on either tip), with their glow round them
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for k in range(1, 19):
            C.put(px, G, x0 + side * k, G.MID, CHAT_METAL_L if k % 4 == 1 else CHAT_METAL)
            C.put(px, G, x0 + side * k, G.MID + 1, CHAT_METAL_D)
        lit = {}
        for n_, tip in enumerate((10, 19)):
            on = (i // 2 + n_) % 3 != 0 and not (glitch and n_ == 1)
            hue = CYA if n_ == 0 else MAG
            for y in range(G.PY0 + 1, G.PY1):
                rr = (G.MID - y) if y <= G.MID else (y - G.MID - 1)
                for w in (0, 1):
                    q = (x0 + side * (tip - rr - w), y)
                    if rr == 7: C.put(px, G, q[0], q[1], CHAT_METAL_L if w == 0 else CHAT_METAL)
                    elif on:
                        lit[q] = hue
                        C.put(px, G, q[0], q[1], hue if w == 0 else lerp(hue, (255, 255, 255), 0.6))
                    else:
                        C.put(px, G, q[0], q[1], lerp(hue, (10, 6, 24), 0.62 if w == 0 else 0.5))
        for (x, y), hue in lit.items():
            for a, b in DIRS4:
                q = (x + a, y + b)
                if 0 <= q[0] < G.W and G.PY0 <= q[1] <= G.PY1 and px[q][3] == 0: C.put(px, G, q[0], q[1], hue, 64)
    # the gem: a hollow neon diamond
    cx, cy = G.DIV, G.MID
    gem = {(cx + a, cy + b): (MAG if not dim_m else MAG_D) for a in range(-3, 4) for b in range(-3, 4) if abs(a) + abs(b) == 3}
    gem.update({(cx + a, cy + b): (255, 190, 240) for a in range(-1, 2) for b in range(-1, 2) if abs(a) + abs(b) == 1})
    gem[(cx, cy)] = (255, 255, 255)
    C.gem_pixels(px, gem)
    return im
