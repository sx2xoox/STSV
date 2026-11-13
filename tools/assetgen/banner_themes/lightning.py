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
# The chat banner (banner_themes/chat.py: one chat line). A band of storm cloud; lightning strikes down through it here and there.
# ------------------------------------------------------------------------------------------------

from . import chat as C


def chat_geo(Geo):
    return C.bar(Geo, TITLE, "scatter")


CHAT_VOLT = C.ramp(((0.08, (30, 44, 104)), (0.30, (50, 90, 210)), (0.60, (84, 156, 255)), (0.90, (180, 230, 255)),
                    (1.20, (250, 252, 255))))
CHAT_GEM = {"#": (200, 240, 255), "a": (110, 200, 255), "w": (240, 252, 255), "b": (40, 120, 245), "c": (14, 32, 110)}
# a strike, frame by frame: the flash, a flicker, the second flash, the afterglow dying
CHAT_STRIKE = (1.0, 0.85, 0.12, 0.95, 0.62, 0.44, 0.31, 0.21, 0.14, 0.09, 0.05, 0.02)


def chat_strike(xl, y, rows, s):
    """The light of a tile's lightning at tile column xl (0 .. 63), inner row y, s frames into its strike: a bolt
    from the top of the plate to its bottom, a thinner second one five frames later, the cloud lit around them.
    None of it reaches the tile's sides, so the tiles (each struck at its own time) show no seams."""
    light = 0.0
    side = C.smooth(xl / 12) * C.smooth((63 - xl) / 12)
    for centre, lean, seed, late, power in ((21, 0.45, 91, 0, 1.0), (45, -0.35, 95, 5, 0.7)):
        if s < late:
            continue
        b = CHAT_STRIKE[s - late] * power
        path = centre + lean * (y - rows / 2) + 3.2 * (2 * C.noise(y, 0, 3, 4, 1 << 10, 16, seed) - 1) \
            + 1.2 * (2 * C.noise(y, 0, 1, 4, 1 << 10, 16, seed + 1) - 1)
        d = abs(xl - path)
        light = max(light, (1.5 * b if d < 0.8 and s - late < 6 else 0.0) + b * 1.0 * math.exp(-d / 2.0) + b * 0.42 * math.exp(-d / 13))
    return light * side


def chat_frame(G, i):
    im, px = G.canvas()
    top, base = G.PY0 + 2, G.PY1 - 2
    rows = base - top + 1
    s = i - C.CALM                                           # frames 0-3: only the cloud
    for (x, y) in G.inside:
        v = (y - G.PY0) / (G.PY1 - G.PY0)
        xt = x - G.TILE0
        cloud = 0.6 * C.noise(xt, y, 16, 6, 64, 24, 35) + 0.4 * C.noise(xt, y, 8, 3, 64, 24, 37)
        c = C.lerp(C.lerp((30, 38, 76), (12, 15, 38), v), (70, 84, 136), 0.55 * cloud * cloud)
        if s >= 0 and G.TILE0 <= x < G.R0:
            c = CHAT_VOLT(chat_strike(xt % 64, y - top, rows, s), c)
        # no frame: the cloud's upper and lower edge are soft, its ends drift apart
        a = 255
        if y in (G.PY0, G.PY1): a = int(120 + 120 * C.noise(xt, y, 6, 4, 64, 24, 39))
        d, edge = C.end_depth(G, x, y, 51, shallow=1.0, deep=9.0)
        a = int(a * C.smooth((d - edge + 5.5) / 7))
        if a <= 0: continue
        px[x, y] = tuple(c) + (a,)
    C.gem7(px, G, CHAT_GEM)
    return im
