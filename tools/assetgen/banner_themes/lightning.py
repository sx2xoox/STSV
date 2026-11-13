"""2 lightning: a live arc over the plate with forks and sky strikes, tesla orbs at the ends (approved art t02)."""
import math, random
from .lib_a import *
TITLE, SLUG = "번개의 지배자", "lightning"
GEO = dict(side=15)
STATIC = 0
CORE, HOT, GLOW, DIM, DEEPB = (250, 252, 255), (175, 228, 255), (70, 145, 255), (44, 80, 200), (30, 44, 120)

def zz(path, rnd, amp, step=3.0):
    """resample path every ~step px and offset alternately left/right of it -> crisp zigzag."""
    pts = [path[0]]; acc = 0
    dense = []
    for p, q in zip(path, path[1:]):
        L = math.hypot(q[0] - p[0], q[1] - p[1]); n = max(1, int(L * 2))
        for k in range(n): dense.append((p[0] + (q[0] - p[0]) * k / n, p[1] + (q[1] - p[1]) * k / n))
    dense.append(path[-1])
    out = [dense[0]]; last = dense[0]; sgn = rnd.choice((-1, 1)); st = step * rnd.uniform(0.7, 1.3)
    for k in range(1, len(dense) - 1):
        p = dense[k]
        if math.hypot(p[0] - last[0], p[1] - last[1]) >= st:
            q = dense[min(len(dense) - 1, k + 1)]; dx, dy = q[0] - p[0], q[1] - p[1]; L = math.hypot(dx, dy) or 1
            o = sgn * rnd.uniform(0.3, amp) * (1.8 if rnd.random() < 0.2 else 1); sgn = -sgn if rnd.random() < 0.8 else sgn
            out.append((p[0] - dy / L * o, p[1] + dx / L * o)); last = p; st = step * rnd.uniform(0.5, 1.5)
    out.append(dense[-1])
    return out

def raster(pts):
    S = []
    for p, q in zip(pts, pts[1:]):
        for c in line(p[0], p[1], q[0], q[1]):
            if not S or S[-1] != c: S.append(c)
    return S

def draw_bolt(px, G, cells, core, glow, ga, outer=None):
    cs = set(cells)
    if outer:
        for x, y in cells:
            for a, b in ((1, 1), (-1, 1), (1, -1), (-1, -1), (2, 0), (-2, 0)):
                q = (x + a, y + b)
                if q not in cs and q not in G.inside and empty(px, G, *q): put(px, G, q[0], q[1], outer, 90)
    for x, y in cells:
        for a, b in DIRS4:
            q = (x + a, y + b)
            if q not in cs and q not in G.inside and (empty(px, G, *q) or px[q][3] < ga): put(px, G, q[0], q[1], glow, ga)
    for x, y in cells:
        if (x, y) not in G.inside: put(px, G, x, y, core)

def big_arc(G, rnd, peak, lo_x, hi_x, y0):
    W1, W2 = rnd.uniform(1.5, 3.0), rnd.uniform(0.6, 1.4); P1, P2 = rnd.uniform(0, 6.3), rnd.uniform(0, 6.3)
    n = 40; path = []
    for k in range(n + 1):
        s = k / n
        x = lo_x + (hi_x - lo_x) * s
        env = math.sin(math.pi * s)
        y = y0 - (y0 - peak) * env ** 0.45 + env * (W1 * math.sin(2 * math.pi * s * 2 + P1) + W2 * math.sin(2 * math.pi * s * 5 + P2))
        path.append((x, y))
    return raster(zz(path, rnd, 2.6, 4.0))

def frame(G, i):
    t = 2 * math.pi * i / N
    im, px = G.canvas()
    oxL, oxR = G.PX0 - 7, G.PX1 + 7
    flash = 2 if i % 8 == 0 else 1 if i % 8 == 1 else 0
    hits = []
    # afterimage of the previous arc, then the live arc with forks down to the plate
    for age, seed in ((1, (i - 1) % N), (0, i)):
        rnd = random.Random(seed * 97 + 13)
        peak = G.PY0 - 17 + 3 * (0.5 + 0.5 * math.sin(2 * math.pi * seed / N * 2)) + rnd.uniform(-1, 1)
        cells = big_arc(G, rnd, peak, oxL, oxR, G.MID - 2)
        forks = []
        for f in range(3):
            cut = rnd.randint(len(cells) // 6, len(cells) * 5 // 6)
            x, y = cells[cut]
            if y > G.PY0 - 6: continue
            reach = rnd.random() < 0.65
            ty = G.PY0 - 1 if reach else y + rnd.randint(4, 7)
            tx = x + rnd.randint(-5, 5)
            fc = raster(zz([(x, y), (tx, ty)], rnd, 1.6, 2.6))
            forks.append(fc)
            if reach and age == 0: hits.append((tx, rnd.randint(0, 99)))
        if age == 1:
            for c in [cells] + forks: draw_bolt(px, G, c, DIM, DEEPB, 120)
        else:
            draw_bolt(px, G, cells, CORE, GLOW, 220, outer=DEEPB)
            for c in forks: draw_bolt(px, G, c, HOT, GLOW, 190)
    # a sky strike every half loop
    if flash:
        r2 = random.Random(i // 8 * 5 + 3)
        x1 = G.PX0 + 12 + r2.randint(0, G.PW - 24)
        cells = raster(zz([(x1 + r2.randint(-8, 8), G.PY0 - 20), (x1, G.PY0 - 1)], r2, 2.4, 3))
        if flash == 2:
            draw_bolt(px, G, cells, CORE, GLOW, 235, outer=DEEPB); hits.append((x1, 7))
        else:
            draw_bolt(px, G, cells, HOT, DIM, 150)
    glow(px, G, (60, 120, 255), 110 + 70 * (flash == 2))
    def bev(x, y):
        p = 0.5 + 0.5 * math.sin(x * 0.45 - t * 3) * math.sin(x * 0.13 + t)
        c = lerp((24, 50, 140), (110, 190, 255), p)
        for hx, _ in hits:
            if abs(x - hx) < 5 and y < G.MID: c = lerp(c, (235, 248, 255), 1 - abs(x - hx) / 5)
        return lerp(c, (220, 240, 255), 0.45) if flash == 2 else c
    def trim(x, y):
        if any(abs(x - hx) < 4 for hx, _ in hits) and y < G.MID: return CORE
        if flash == 2: return (235, 245, 255) if y < G.MID else (120, 150, 220)
        return (196, 222, 255) if y < G.MID else (76, 104, 184)
    plate(px, G, lambda x, y, g: lerp((30, 34, 64), (6, 7, 16), g), None, None, bevel=bev, trim=trim)
    rivets(px, G, (210, 235, 255), (80, 110, 180))
    # end ornaments: rods ending in tesla orbs (arc anchors)
    for side, ox in ((-1, oxL), (1, oxR)):
        x0 = G.PX0 if side < 0 else G.PX1
        for k in range(1, 5): put(px, G, x0 + side * k, G.MID, (150, 180, 230) if k < 4 else (110, 140, 200))
        for a in (-1, 0, 1):
            for b in (-1, 0, 1):
                d = abs(a) + abs(b)
                put(px, G, ox + a, G.MID - 2 + b, CORE if d == 0 else HOT if d == 1 else GLOW)
        blend(px, G, ox, G.MID - 4, GLOW, 160); blend(px, G, ox - 2, G.MID - 2, GLOW, 160); blend(px, G, ox + 2, G.MID - 2, GLOW, 160)
    gem(px, G, G.DIV, G.MID, (240, 252, 255), (110, 200, 255), (40, 120, 245), (26, 66, 190), (14, 32, 110), top=(200, 240, 255))
    texts(px, G, i,
          ramp((0, (255, 255, 255)), (0.2, (228, 240, 255)), (0.7, (150, 178, 235)), (1, (96, 116, 186))),
          ramp((0, (255, 255, 255)), (0.35, (165, 234, 255)), (0.75, (76, 166, 255)), (1, (56, 108, 236))),
          (2, 4, 16))
    # sparks bursting from the hit points
    for hx, sd in hits:
        r3 = random.Random(sd + i)
        for s in range(5):
            d = r3.randint(1, 4); vx = r3.uniform(-1.8, 1.8); vy = r3.uniform(-2.0, -0.8)
            x = hx + vx * d; y = G.PY0 - 1 + vy * d + 0.5 * d * d
            if (int(x), int(y)) in G.inside or y >= G.PY0: continue
            put(px, G, x, y, CORE if d < 3 else HOT)
    return im


# ------------------------------------------------------------------------------------------------
# The chat banner (banner_themes/chat.py: one chat line, 18 rows of half pixels): this banner's plate in small, and
# what the full banner shows over its plate (cut off by a chat line) inside it. The current lives in the frame: charge
# runs round it and jumps off it in sparks; the arc between the orbs and the strike only flash by, so the words stay
# readable (a steady arc over them hid the nickname).
# ------------------------------------------------------------------------------------------------

from . import chat as C


def chat_geo(Geo):
    return C.framed(Geo, TITLE)


def chat_arc(G, x, seed):
    """The row of the big arc at column x: a zigzag through the plate that crosses its middle every 16 columns (so it
    goes on unbroken through tiles, slices and both ends), a different swing in every cell (the same every 64)."""
    mid = (G.PY0 + G.PY1) / 2
    if x <= G.PX0 or x >= G.PX1:
        return mid
    xt = x - (G.R0 if x >= G.R0 else G.TILE0)
    cell, f = (xt // 16) % 4 if x < G.R0 else 5, (xt % 16) / 16
    swing = 5.2 * (2 * C.lattice(cell, seed, 201) - 1)
    return mid + (swing + 1.6 * (2 * C.lattice(xt % 64, seed, 203) - 1)) * math.sin(math.pi * f)


def chat_bolt(px, G, cells, core, glow_c, ga, outer=None):
    """a bolt as the full banner draws it (draw_bolt), kept to the inside of the plate"""
    S = set(cells)
    if outer:
        for x, y in S:
            for a, b in ((2, 0), (-2, 0), (0, 2), (0, -2), (1, 1), (-1, 1), (1, -1), (-1, -1)):
                q = (x + a, y + b)
                if q in G.core and q not in S: C.blend(px, G, q[0], q[1], outer, 90)
    for x, y in S:
        for a, b in DIRS4:
            q = (x + a, y + b)
            if q in G.core and q not in S: C.blend(px, G, q[0], q[1], glow_c, ga)
    for x, y in S:
        if (x, y) in G.core: px[x, y] = tuple(core) + (255,)


def chat_charge(G, x, y, i):
    """How much charge is in the frame's channel at a pixel (0 .. 1): packets running to the right along the top and
    to the left along the bottom, 4 columns a frame, two to a tile; they keep their place round the pointed ends."""
    along = (x - G.TILE0 - 4 * i) if y <= G.MID else (-(x - G.TILE0) - 4 * i)
    return max(0.0, math.cos(2 * math.pi * along / 32)) ** 6


def chat_frame(G, i):
    t = 2 * math.pi * i / N
    im, px = G.canvas()
    oxL, oxR = G.PX0 - 8, G.PX1 + 8                          # the orbs
    arc = 2 if i == 0 else 1 if i == 1 else 0                # the arc between the orbs: a flash, then its afterimage
    strike = 2 if i == 8 else 1 if i == 9 else 0             # the strike across the plate, half a loop later
    flash = arc == 2 or strike == 2
    top, base = G.PY0 + 2, G.PY1 - 2
    cell = int(C.lattice(i // 8, 0, 207) * 4)
    strike_x = lambda x0: x0 + cell * 16 + 8
    hits = {strike_x(x0) for x0 in range(G.TILE0, G.R0 - 15, 64) if strike_x(x0) < G.R0 - 4} if strike == 2 else set()

    # the frame: a rail of blued steel in segments (a seam every 16 columns, a rivet beside it), and inside it a
    # channel the charge runs through
    def trim(x, y):
        seam = (x - G.TILE0) % 16 == 0 and G.TILE0 <= x < G.R0
        if any(abs(x - hx) < 4 for hx in hits): return CORE
        c = (196, 222, 255) if y <= G.MID else (76, 104, 184)
        if flash: c = (235, 245, 255) if y <= G.MID else (120, 150, 220)
        if seam: c = lerp(c, (20, 30, 70), 0.6)
        elif (x - G.TILE0) % 16 == 1 and G.TILE0 <= x < G.R0: c = lerp(c, (255, 255, 255), 0.5)
        return c

    def channel(x, y):
        q = chat_charge(G, x, y, i)
        c = lerp(lerp((16, 34, 100), (40, 84, 190), 0.5 + 0.5 * math.sin(x * math.pi / 8 - t * 2)), (236, 248, 255), q)
        if any(abs(x - hx) < 5 for hx in hits): c = lerp(c, (235, 248, 255), 1 - min(abs(x - hx) for hx in hits) / 5)
        return lerp(c, (220, 240, 255), 0.5) if flash else c

    for (x, y) in G.edge:                                    # a glow round the pointed ends
        for a, b in DIRS4:
            q = (x + a, y + b)
            if q not in G.inside and G.PY0 <= q[1] <= G.PY1: C.blend(px, G, q[0], q[1], (60, 120, 255), 110 + 90 * flash)
    C.plate(px, G, lambda x, y, g: lerp(lerp((30, 34, 64), (6, 7, 16), g), (60, 90, 180), 0.22 if flash else 0.0), trim, channel)
    # sparks jumping off the charge into the plate: short, only where a packet is, gone the next frame
    for x0 in range(G.TILE0, G.R0 - 15, 16):
        k = ((x0 - G.TILE0) // 16) % 4
        for edge_y, way in ((top, 1), (base, -1)):
            xs = x0 + 4 + int(8 * C.lattice(k, i, 221 + way))
            if chat_charge(G, xs, edge_y - way, i) < 0.5 or C.lattice(k, i, 223 + way) < 0.35: continue
            reach = 2 + int(3 * C.lattice(k, i, 225 + way))
            xe = xs + int(round(4 * (C.lattice(k, i, 227 + way) - 0.5)))
            chat_bolt(px, G, C.line(xs, edge_y, xe, edge_y + way * reach), HOT, DIM, 130)
    if arc:
        seed = 0
        cells, prev = [], None
        for x in range(G.PX0 + 1, G.PX1):
            y = int(round(chat_arc(G, x, seed)))
            cells += C.line(x - 1, prev, x, y) if prev is not None else [(x, y)]
            prev = y
        if arc == 2: chat_bolt(px, G, cells, CORE, GLOW, 220, outer=DEEPB)
        else: chat_bolt(px, G, cells, DIM, DEEPB, 110)
    if strike:
        for x0 in range(G.TILE0, G.R0 - 15, 64):
            xs = strike_x(x0)
            if xs >= G.R0 - 4: continue
            r2 = random.Random(i // 8 * 5 + 3)
            pts, y, x = [], top, xs + r2.randint(-3, 3)
            while y < base:
                ny = min(base, y + r2.randint(2, 4)); nx = xs + r2.randint(-3, 3)
                pts += C.line(x, y, nx, ny); x, y = nx, ny
            if strike == 2: chat_bolt(px, G, pts, CORE, GLOW, 235, outer=DEEPB)
            else: chat_bolt(px, G, pts, DIM, DEEPB, 110)
    # the ends: a rod off each tip with a tesla orb (the arc's anchors), brighter when it flashes
    for side, ox in ((-1, oxL), (1, oxR)):
        x0 = G.PX0 if side < 0 else G.PX1
        for k in range(1, 6):
            for y in (G.MID, G.MID + 1): C.put(px, G, x0 + side * k, y, (150, 180, 230) if k < 5 else (110, 140, 200))
        for a in range(-2, 3):
            for b in range(-2, 3):
                d = abs(a) + abs(b)
                if d <= 3: C.put(px, G, ox + a, G.MID + b, CORE if d <= 1 else HOT if d == 2 else GLOW)
        pulse = 0.5 + 0.5 * math.sin(t * 2)
        for a, b in ((0, -4), (-4, 0), (4, 0), (0, 4), (-3, -3), (3, -3), (-3, 3), (3, 3)):
            C.blend(px, G, ox + a, G.MID + b, GLOW, 240 if flash else int(90 + 80 * pulse))
    C.gem7(px, G, {"#": (200, 240, 255), "a": (110, 200, 255), "w": (240, 252, 255), "b": (40, 120, 245), "c": (14, 32, 110)})
    return im
