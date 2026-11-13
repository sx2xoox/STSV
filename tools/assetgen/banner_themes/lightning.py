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
    return C.bar(Geo, TITLE, "gust")


# The chat banner: a storm in a conduit. A dark thundercloud between two steel rails, a tesla ball off each end.
# While it idles, small charges crawl along the rails' channels (to the right along the top, to the left along the
# bottom) and the balls crackle. Every two seconds a discharge leaves the left ball and runs the whole banner: both
# rails go white, a bolt snakes along under the top rail and one over the bottom rail, forks jump off them, the
# cloud behind the front lights up and dies away, and the right ball flashes when it arrives. The words' rows stay
# dark (C.hush): only the bolts themselves cross them, for a tenth of a second.
CHAT_RAIL_T, CHAT_RAIL_B = (150, 176, 224), (62, 84, 150)
CHAT_GEM = {"#": (200, 240, 255), "a": (110, 200, 255), "w": (240, 252, 255), "b": (40, 120, 245), "c": (14, 32, 110)}


def chat_cloud(xt, y):
    """the thundercloud at rest (the same every 64 columns): dark, a little lighter where it billows"""
    n = 0.6 * C.noise(xt, y, 8, 4, 64, 16, 301) + 0.4 * C.noise(xt + 9, y, 4, 2, 64, 16, 302)
    g = (y - 2) / 13
    return C.lerp(C.lerp((15, 21, 48), (5, 7, 20), g), (32, 46, 94), 0.6 * C.smooth((n - 0.42) / 0.4))


# the arcs that follow the front, between the rails: (columns behind the front, first row, last row, heat, seed).
# The first is the discharge itself, from rail to rail; the others are what is left of it, shorter and dimmer
CHAT_ARCS = ((6, 2, 15, 1.0, 1), (19, 2, 9, 0.75, 2), (30, 8, 15, 0.7, 3), (45, 2, 15, 0.45, 4), (58, 11, 15, 0.35, 5))


def chat_arc_at(at, y, seed):
    """how far behind the front an arc is in row y: a zigzag through the plate, a sharp kink every two rows, now
    to one side and now to the other, each as far as it likes"""
    k, f = divmod(y, 2)
    def kink(n): return (1 if n % 2 else -1) * (1.0 + 3.2 * C.lattice(n, seed, 331))
    return at + int(round(kink(k) + (kink(k + 1) - kink(k)) * f / 2))


def chat_frame(G, i):
    im, px = G.canvas()
    step = i - C.CALM
    idle = i % C.CALM
    behind = (lambda x: G.flow_at(x, step)) if step >= 0 else (lambda x: -1)

    def charge(x, y):
        """the small charges crawling in the channels while it idles (a loop of the four idle frames)"""
        xt = x - G.TILE0
        along = xt - 4 * idle if y < G.MID else -xt - 4 * idle
        return max(0.0, math.cos(2 * math.pi * along / 16)) ** 8

    cells = {}                                               # the arcs: pixel -> heat (1 the core .. 0)
    if step >= 0:
        for x in range(G.PX0 + 3, G.PX1 - 2):
            u = behind(x)
            if not 0 <= u < 70: continue
            for at, y0, y1, heat, seed in CHAT_ARCS:
                for y in range(y0, y1 + 1):
                    ua, ub = chat_arc_at(at, y, seed), chat_arc_at(at, min(y1, y + 1), seed)
                    if min(ua, ub) <= u <= max(ua, ub): cells[(x, y)] = max(cells.get((x, y), 0), heat)
    for (x, y) in G.inside:
        xt = x - G.TILE0
        u = behind(x)
        e = C.strength(u, rise=5, body=18, total=64)        # the rails' white heat behind the front
        glow = C.strength(u, rise=10, body=20, total=96)    # the cloud lit up, dying away
        seam = xt % 16 == 0
        if y in (G.PY0, G.PY1):
            if (x in (G.PX0, G.PX1)): continue               # the corners, rounded off
            c = CHAT_RAIL_T if y == G.PY0 else CHAT_RAIL_B
            if seam: c = lerp(c, (16, 24, 60), 0.65)
            elif xt % 16 == 1: c = lerp(c, (255, 255, 255), 0.45)
            c = lerp(c, (240, 248, 255), 0.9 * e)
        elif y in (G.PY0 + 1, G.PY1 - 1):
            c = lerp((14, 26, 78), (96, 176, 255), charge(x, y))
            c = lerp(c, (252, 254, 255), e)
        else:
            c = chat_cloud(xt, y)
            c = lerp(c, (64, 98, 196), 0.55 * glow)
            near = min((abs(x - a) + abs(y - b) for (a, b) in ((x + dx, y + dy) for dx in (-1, 0, 1) for dy in (-1, 0, 1)) if (a, b) in cells), default=9)
            h = cells.get((x, y))
            if h is not None:
                c = lerp(lerp(DIM, HOT, min(1.0, h * 1.6)), CORE, max(0.0, h - 0.6) / 0.4)
            else:
                if near <= 2: c = lerp(c, GLOW, 0.5 if near == 1 else 0.22)
                c = C.hush(c, 0.05 + 0.06 * glow)
        # the ends: a steel cap three columns deep
        d = min(x - G.PX0, G.PX1 - x)
        if d < 3 and G.PY0 < y < G.PY1:
            cap = lerp(CHAT_RAIL_T, CHAT_RAIL_B, (y - G.PY0) / (G.PY1 - G.PY0))
            c = lerp(lerp(cap, (20, 28, 70), 0.55 if d == 2 else 0.0), (240, 248, 255), 0.8 * e)
        px[x, y] = tuple(c) + (255,)

    # the tesla balls: a rod off each cap and a ball on it; they crackle at rest and flash when the discharge leaves
    # (the left one) and arrives (the right one)
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        ox = x0 + side * 8
        flash = C.strength(behind(x0) + (10 if side < 0 else 0), rise=4, body=22, total=60) if step >= 0 else 0.0
        for k in range(1, 6):
            for y in (G.MID, G.MID + 1): C.put(px, G, x0 + side * k, y, lerp((120, 146, 200), (240, 248, 255), flash))
        pulse = 0.5 + 0.5 * math.sin(math.pi * idle / 2)
        for a in range(-5, 6):
            for b in range(-5, 6):
                r = math.hypot(a, b - 0.5)
                if r <= 1.2: C.put(px, G, ox + a, G.MID + b, CORE)
                elif r <= 2.3: C.put(px, G, ox + a, G.MID + b, lerp(HOT, CORE, flash))
                elif r <= 3.2: C.put(px, G, ox + a, G.MID + b, lerp(GLOW, HOT, flash))
                elif r <= 5.2: C.blend(px, G, ox + a, G.MID + b, GLOW, int((60 + 50 * pulse + 130 * flash) * (5.2 - r) / 2))
        # a crackle from the ball to the cap's corners
        rnd = random.Random(900 + idle * 7 + (side > 0) + (50 + step if flash > 0.3 else 0))
        for _ in range(1 + (flash > 0.3)):
            ty = rnd.choice((G.PY0 + 1, G.PY1 - 1, G.PY0 + 3, G.PY1 - 3))
            pts, x, y = [], ox - side * 3, G.MID + rnd.randint(-1, 1)
            while (x - x0) * side > 1:
                nx, ny = x - side * rnd.randint(1, 2), y + (1 if ty > y else -1) * rnd.randint(0, 2)
                pts += C.line(x, y, nx, ny); x, y = nx, ny
            for q in pts:
                if G.PY0 <= q[1] <= G.PY1 and (q[0] - x0) * side > 0: C.put(px, G, q[0], q[1], HOT if flash > 0.3 else GLOW)
    C.gem7(px, G, CHAT_GEM)
    return im
