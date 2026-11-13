"""3 frost: a ridge of ice crystals along the top, shards off both ends, icicles under the plate, snow drifting down
(approved art t03).

chat_frame: its chat banner (banner_themes/chat.py), drawn for one chat line."""
import math, random
from .lib_a import *
from . import chat as C
TITLE, SLUG = "서리의 여왕", "frost"
STATIC = 0
GEO = dict(side=15, bot=3)
WHITE, LIGHT, MIDC, DARK, EDGE = (250, 254, 255), (200, 238, 255), (120, 196, 240), (66, 136, 206), (40, 92, 160)

def shard(px, G, bx, by, ang, L, w, glint=None, tone=0.0, keep_plate=True):
    """crystal prism from (bx, by) pointing at angle ang (0 = up), length L, half width w."""
    dx, dy = math.sin(ang), -math.cos(ang)        # along
    nx, ny = -dy, dx                               # perpendicular (right side)
    R = int(L + w + 2)
    for y in range(int(by - R), int(by + R) + 1):
        for x in range(int(bx - R), int(bx + R) + 1):
            if not (0 <= x < G.W and 0 <= y < G.H): continue
            if keep_plate and (x, y) in G.inside: continue
            rx, ry = x - bx, y - by
            u = rx * dx + ry * dy; v = rx * nx + ry * ny
            if u < -0.5 or u > L: continue
            hw = w if u < 0.55 * L else w * (L - u) / (0.45 * L)
            if abs(v) > hw + 0.35: continue
            edge = abs(v) > hw - 0.65
            if abs(v) < 0.5: c = WHITE if u > L - 2.2 else LIGHT
            elif v < 0: c = LIGHT if not edge else MIDC
            else: c = MIDC if not edge else DARK
            if u > L - 1.2: c = WHITE
            if tone: c = lerp(c, EDGE, tone)
            if glint is not None and abs(u - glint) < 1.0: c = lerp(c, WHITE, 0.8)
            px[x, y] = c + (255,)

def star(px, G, x, y, r, col=WHITE):
    put(px, G, x, y, col)
    for k in range(1, r + 1):
        a = 255 if k < r else 140
        for ddx, ddy in DIRS4: blend(px, G, x + ddx * k, y + ddy * k, (190, 240, 255), a)

def frame(G, i):
    t = 2 * math.pi * i / N
    im, px = G.canvas()
    by = G.PY0 + 1
    # the crown of ice: tall in the middle, leaning clusters near the ends, small ones between
    C = G.CX
    crown = [(-10, 7, -0.34, 1.4), (10, 7, 0.34, 1.4), (-5, 12, -0.17, 1.7), (5, 12, 0.17, 1.7), (0, 18, 0, 2.2),
             (-2, 9, -0.06, 1.2), (2, 9, 0.06, 1.2)]
    ends = []
    for side in (-1, 1):
        ex = (G.PX0 + 9) if side < 0 else (G.PX1 - 9)
        ends += [(ex - C + side * 2, 13, side * 0.45, 1.7), (ex - C - side * 2, 8, side * 0.2, 1.3), (ex - C + side * 6, 7, side * 0.78, 1.2)]
    small = []
    rnd = random.Random(3)
    for x in list(range(G.PX0 + 16, C - 13, 7)) + list(range(C + 14, G.PX1 - 15, 7)):
        small.append((x - C + rnd.randint(-1, 1), rnd.choice((3, 4, 5)), rnd.uniform(-0.25, 0.25), 1.0))
    allsh = sorted(small + ends + crown, key=lambda s: -s[1])
    for k, (ox, L, ang, w) in enumerate(allsh):
        g = ((i * 1.5 + k * 3.7) % (N * 1.5)) - 4
        shard(px, G, C + ox, by, ang, L, w, glint=g if g < L else None, tone=0.25 if L < 6 else 0)
    # crystals that grow, hold and shatter (staggered)
    gx = [G.PX0 + 22, C - 22, C + 22, G.PX1 - 22]
    for k, x in enumerate(gx):
        ph = (i + k * 4) % N; Lmax = 9; ang = (-0.12, 0.1, -0.1, 0.12)[k]
        if ph < 11:
            L = 1 + Lmax * min(1, ph / 8)
            shard(px, G, x, by, ang, L, 1.3, glint=L - 1 if ph >= 8 else None)
        else:
            d = ph - 10
            rs = random.Random(k * 13)
            for f in range(6):
                fx = x + rs.uniform(-1.5, 1.5) * d * 1.3; fy = by - rs.uniform(3, 9) - rs.uniform(0.5, 1.5) * d + 0.5 * d * d
                if f % 2: continue
                if (round(fx), round(fy)) not in G.inside and (round(fx) + 1, round(fy)) not in G.inside:
                    put(px, G, round(fx), round(fy), (WHITE, LIGHT, MIDC, DARK, DARK)[d - 1])
                    put(px, G, round(fx) + 1, round(fy), (LIGHT, MIDC, DARK, EDGE, EDGE)[d - 1])
    # sparkles on the big tips
    for k, (ox, L, ang, w) in enumerate(crown[2:5] + ends[0:1] + ends[3:4]):
        ph = (i + k * 3) % 16
        if ph < 4:
            tx = C + ox + math.sin(ang) * (L - 0.5); ty = by - math.cos(ang) * (L - 0.5)
            star(px, G, round(tx), round(ty), (1, 2, 2, 1)[ph])
    glow(px, G, (90, 200, 255), 120)
    # end shards pointing outwards
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        shard(px, G, x0 + side * 0, G.MID, side * math.pi / 2, 8, 1.3)
        shard(px, G, x0 + side * 2, G.MID + 2, side * 2.1, 5, 1.0, tone=0.2)
    # plate: frozen glass with a slow cold pulse in the bevel and faint frost streaks
    def fill(x, y, g):
        c = lerp((46, 92, 140), (12, 30, 62), g)
        if (x, y) not in G.text_near and ((x + y * 2) % 23 == 0 or (x - y) % 31 == 0) and 0.1 < g < 0.9:
            c = lerp(c, (120, 190, 235), 0.25)
        return c
    def bev(x, y):
        p = 0.5 + 0.5 * math.sin(x * 0.12 - t)
        return lerp((80, 170, 225), (205, 245, 255), p) if y < G.MID else lerp((40, 110, 180), (120, 200, 245), p)
    plate(px, G, fill, (244, 252, 255), (128, 188, 230), bevel=bev)
    rivets(px, G, (240, 252, 255), (110, 170, 220))
    # icicles under the plate, a drop running down and falling
    ir = random.Random(5); xs = []; x = G.PX0 + 8
    while x < G.PX1 - 7: xs.append(x); x += ir.choice((3, 4, 5, 7))
    for k, x in enumerate(xs):
        L = ir.choice((1, 2, 2, 3, 3))
        if L == 3: put(px, G, x + 1, G.PY1 + 1, (130, 200, 240))
        for r in range(L):
            put(px, G, x, G.PY1 + 1 + r, (200, 240, 255) if r == 0 else (130, 200, 240) if r < L - 1 else (230, 250, 255))
        if k % 5 == 2 and L > 1:
            ph = (i + k * 3) % N
            if ph < 3: put(px, G, x, G.PY1 + L, WHITE)
            elif ph < 5: put(px, G, x, G.PY1 + L + ph - 1, WHITE); put(px, G, x, G.PY1 + L + ph, (130, 200, 240))
    gem(px, G, G.DIV, G.MID, (255, 255, 255), (190, 240, 255), (100, 200, 245), (60, 140, 220), (34, 80, 160), top=(240, 252, 255))
    texts(px, G, i,
          ramp((0, (255, 255, 255)), (0.25, (214, 244, 255)), (0.7, (130, 196, 242)), (1, (86, 146, 214))),
          ramp((0, (255, 255, 255)), (0.4, (176, 240, 255)), (1, (88, 196, 255))),
          (6, 18, 40))
    # snow drifting down around everything (not over the letters)
    sn = random.Random(11)
    for k in range(18):
        x0 = sn.randint(G.PX0 - 4, G.PX1 + 4); y0 = sn.randint(0, 15); big = k % 4 == 0
        y = G.PY0 - 20 + (y0 + i) % 16 + (0 if k % 3 else 2)
        x = x0 + round(1.5 * math.sin(t + k * 1.3))
        if (x, y) in G.inside or (x, y) in G.text_near: continue
        a = 255 if 1 < (y0 + i) % 16 < 14 else 120
        if not empty(px, G, x, y): continue
        put(px, G, x, y, WHITE if big else (225, 245, 255), a)
        for ddx, ddy in DIRS4:
            q = (x + ddx, y + ddy)
            if q not in G.inside and empty(px, G, *q): blend(px, G, q[0], q[1], (170, 225, 255), a * (0.75 if big else 0.4))
    return im


# ------------------------------------------------------------------------------------------------
# The chat banner: a pane of black ice. A bar of deep blue ice, darker towards the bottom, with pale frost creeping
# in from both rails (a different reach in every column, the same every 64) and a few fine cracks. The upper rail
# is clear ice with a bright edge and short icicles under it, the lower one frosted. Off each end stands a cluster
# of crystal shards. The scene loops: snow drifts down across the bar (small, pale, never bright), a glint runs
# along the upper rail, and stars of light flare on the rails and the shards. Behind the words the ice stays dark.
# ------------------------------------------------------------------------------------------------
CHAT_GEM = {"#": (255, 255, 255), "a": (190, 240, 255), "w": (255, 255, 255), "b": (100, 200, 245), "c": (34, 80, 160)}
CHAT_FLAKES = ((3, 0, 0), (11, 5, 1), (20, 11, 0), (27, 2, 1), (36, 8, 0), (44, 13, 1), (52, 4, 0), (59, 10, 1))   # column in the tile, phase, sway
CHAT_STARS = ((0, 6, 1, 0), (1, 12, -1, 7), (2, 4, 1, 11), (3, 10, -1, 3))


def chat_geo(Geo):
    return Geo(TITLE, tile=C.TILE, pointed=False, grow=16)


def chat_frame(G, i):
    im, px = G.canvas()
    ph = i % N

    def ice(x, y):
        xt = x - G.TILE0
        g = (y - G.PY0 - 2) / 13
        c = C.lerp((18, 44, 82), (6, 16, 36), g)
        reach_t = 1.5 + 3.5 * C.noise(xt, 0, 4, 4, 64, 16, 501)          # frost under the upper rail
        reach_b = 1.0 + 2.5 * C.noise(xt, 7, 4, 4, 64, 16, 503)
        dt, db = y - (G.PY0 + 2), (G.PY1 - 2) - y
        if dt < reach_t: c = C.lerp(c, (150, 206, 240), 0.55 * (1 - dt / reach_t))
        if db < reach_b: c = C.lerp(c, (110, 170, 220), 0.45 * (1 - db / reach_b))
        if (xt * 3 + y * 5) % 37 == 0 or (xt * 2 - y * 3) % 41 == 0: c = C.lerp(c, (120, 180, 230), 0.3)      # cracks
        return c

    for (x, y) in G.inside:
        xt = x - G.TILE0
        d = min(x - G.PX0, G.PX1 - x)
        glint = 0.0 if x >= G.R0 else max(0.0, math.cos(2 * math.pi * (xt - 4 * ph) / 64)) ** 18
        if y == G.PY0:
            c = C.lerp(LIGHT, (255, 255, 255), 0.4 + 0.6 * glint)
        elif y == G.PY0 + 1:
            c = C.lerp(MIDC, WHITE, 0.8 * glint)
        elif y == G.PY0 + 2 and xt % 8 in (2, 5) and C.lattice(xt // 8, 0, 505) > 0.35:
            c = LIGHT                                                    # an icicle
        elif y == G.PY0 + 3 and xt % 8 == 2 and C.lattice(xt // 8, 0, 505) > 0.7:
            c = MIDC
        elif y == G.PY1 - 1:
            c = C.lerp(DARK, MIDC, C.noise(xt, 0, 4, 4, 64, 16, 507))
        elif y == G.PY1:
            c = C.lerp(EDGE, DARK, C.noise(xt, 3, 4, 4, 64, 16, 509))
        else:
            c = C.hush(ice(x, y), 0.05)
        if d < 2 and G.PY0 < y < G.PY1: c = LIGHT if d == 0 else MIDC
        k = C.corner(G, x, y, 2.0)
        if k > 0: px[x, y] = tuple(c) + (int(255 * k),)

    # snow drifting down: a flake to every eight columns, each on its own time (the same every 64 columns); small and
    # pale, and paler still on the words' rows
    for x0 in range(G.TILE0, G.R0 - 7, 64):
        for col, off, sway in CHAT_FLAKES:
            fall = (ph + off) % N                                        # 0 .. 15: one row a frame
            y = G.PY0 + 2 + fall
            x = x0 + col + ((fall // 4) % 2 if sway else 0)
            if x >= G.R0 - 1 or not G.PY0 + 2 <= y <= G.PY1 - 2: continue
            C.blend(px, G, x, y, (214, 240, 255), 150 if fall < 13 else 80)

    # shards off both ends, and stars of light on the rails and on the shards' tips
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for dy, reach, w in ((-6, 7, 2), (-2, 11, 3), (3, 12, 3), (7, 6, 2)):
            for k in range(reach):
                half = max(0, round(w * (1 - k / reach)))
                for b in range(-half, half + 1):
                    y = G.MID + dy + b + round(k * dy / 14)
                    if not G.PY0 <= y <= G.PY1: continue
                    c = WHITE if b == -half and k > 1 else LIGHT if b < 0 else MIDC if b == 0 else DARK
                    C.put(px, G, x0 + side * (1 + k), y, c)
    spots = [(G.PX0 - 11, G.MID - 1, 2), (G.PX1 + 12, G.MID + 4, 10)]
    for x0 in range(G.TILE0, G.R0 - 15, 64):
        spots += [(x0 + cell * 16 + col, G.PY0 if way > 0 else G.PY1, off) for cell, col, way, off in CHAT_STARS if x0 + cell * 16 + 15 < G.R0]
    for x, y, off in spots:
        k = (ph + off) % N
        if k >= 4: continue
        size = (1, 2, 2, 1)[k]
        for dd in range(-size, size + 1):
            for q in ((x + dd, y), (x, y + dd)):
                if G.PY0 <= q[1] <= G.PY1 and not (G.PY0 + 2 <= q[1] <= G.PY1 - 2 and G.PX0 < q[0] < G.PX1):
                    C.put(px, G, q[0], q[1], (255, 255, 255) if dd == 0 else (214, 240, 255))
    C.gem7(px, G, CHAT_GEM)
    return im
