"""7 toxic: slime over the plate's edges, bubbles rising and bursting, a flask at each end, drips (approved art t07).

chat_frame: its chat banner (banner_themes/chat.py), drawn for one chat line."""
import math, random
from .lib_a import *
from . import chat as C
TITLE, SLUG = "맹독 연금술사", "toxic"
GEO = dict(side=17, bot=1)
STATIC = 5
S_HI, S_LT, S_MD, S_DK, S_DD = (236, 255, 196), (184, 255, 84), (116, 220, 44), (58, 150, 26), (30, 86, 16)
GLASS, GLASS_D, CORK = (206, 236, 222), (120, 160, 150), (150, 100, 60)

RINGS = {1: [(1, 0), (-1, 0), (0, 1), (0, -1)],
         2: [(-1, -2), (0, -2), (1, -2), (-2, -1), (2, -1), (-2, 0), (2, 0), (-2, 1), (2, 1), (-1, 2), (0, 2), (1, 2)],
         3: [(-1, -3), (0, -3), (1, -3), (-2, -2), (2, -2), (-3, -1), (3, -1), (-3, 0), (3, 0), (-3, 1), (3, 1), (-2, 2), (2, 2), (-1, 3), (0, 3), (1, 3)]}

def bubble(px, G, x, y, r, pop=0):
    x, y = round(x), round(y)
    if pop:
        R = r + pop
        for a, b in ((-1, 0), (1, 0), (0, -1), (-1, -1), (1, -1)):
            for k in (R, R + 1):
                q = (x + a * k, y + b * k)
                if empty(px, G, *q) and q not in G.inside: put(px, G, q[0], q[1], S_LT if pop == 1 else S_MD)
        return
    if r == 0:
        put(px, G, x, y, S_LT); return
    for a, b in RINGS[r]:
        if (x + a, y + b) in G.inside: continue
        put(px, G, x + a, y + b, S_LT if (a < 0 and b <= 0) else S_MD if a <= 0 or b < 0 else S_DK)
    for a in range(-r + 1, r):
        for b in range(-r + 1, r):
            if a * a + b * b < r * r and (x + a, y + b) not in G.inside: put(px, G, x + a, y + b, (60, 140, 30), 90)
    if r >= 2: put(px, G, x - r + 1, y - r + 1, S_HI)
    else: put(px, G, x - 1, y - 1, S_HI)

def flask(px, G, cx, by, t, i, side):
    # round-bottom flask, 7 wide, neck 3, cork
    body = [(a, b) for a in range(-3, 4) for b in range(-6, 1) if (a / 3.4) ** 2 + ((b + 3) / 3.4) ** 2 <= 1]
    S = set(body) | {(a, b) for a in (-1, 0, 1) for b in range(-9, -6)}
    for a, b in S:
        edge = any((a + d, b + e) not in S for d, e in DIRS4)
        if edge: c = GLASS if a < 0 or b < -6 else GLASS_D
        else:
            lvl = -3 + round(0.6 * math.sin(t * 2 + side))
            c = (S_LT if b == lvl else S_MD if a < 1 else S_DK) if b >= lvl else (24, 40, 30)
        put(px, G, cx + a, by + b, c)
    put(px, G, cx - 1, by - 4, S_HI)
    for a in (-1, 0, 1): put(px, G, cx + a, by - 10, CORK if a < 1 else (100, 64, 36))
    put(px, G, cx, by - 11, (110, 70, 40))
    # little bubbles inside, fumes from the cork
    ph = (i + side * 3) % 8
    if ph < 3: put(px, G, cx + (1 if side > 0 else -1), by - 1 - ph, S_HI)
    for k in range(3):
        q = (i + k * 5 + side * 2) % N
        if q < 10:
            y = by - 12 - q; x = cx + round(1.5 * math.sin(q * 0.6 + k * 2 + side))
            if empty(px, G, x, y) and empty(px, G, x + 1, y):
                put(px, G, x, y, (150, 230, 90) if q < 5 else (90, 160, 60), 230 if q < 6 else 160)
                put(px, G, x + 1, y, (100, 180, 60) if q < 5 else (70, 120, 50), 200 if q < 6 else 130)

def frame(G, i):
    t = 2 * math.pi * i / N
    im, px = G.canvas()
    # rising bubbles (behind/above the goo), popping at the top
    br = random.Random(17)
    bubs = []
    for k in range(11):
        x0 = G.PX0 + 8 + (G.PW - 16) * k / 10 + br.randint(-3, 3); off = br.randint(0, N - 1); top = br.randint(7, 12); r = (1, 2, 1, 3, 2, 1, 2, 3, 1, 2, 2)[k]
        ph = (i + off) % N
        y = G.PY0 - 2 - r - min(ph, N - 3) * top / (N - 3)
        x = x0 + 1.2 * math.sin(ph * 0.7 + k)
        bubs.append((x, y, r, ph - (N - 3) if ph > N - 3 else 0))
    glow(px, G, (120, 255, 60), 130, ring=1)
    def bev(x, y):
        p = 0.5 + 0.5 * math.sin(x * 0.5 + t * 2) * math.sin(x * 0.17 - t)
        return lerp((50, 130, 24), (176, 255, 80), p) if y < G.MID else lerp((30, 90, 16), (110, 200, 40), p)
    plate(px, G, lambda x, y, g: lerp((30, 58, 20), (6, 14, 6), g), (210, 255, 130), (80, 140, 30), bevel=bev)
    rivets(px, G, (210, 255, 140), (80, 140, 40))
    # goo spilling over the top edge
    xs = range(G.PX0 + 4, G.PX1 - 3)
    for x in xs:
        h = 1.2 + 1.0 * (0.5 + 0.5 * math.sin(x * 0.42 + 1)) + 0.8 * (0.5 + 0.5 * math.sin(x * 0.13 + t))
        for b in (G.PX0 + 20, G.CX - 6, G.CX + 18, G.PX1 - 22):
            d = abs(x - b)
            if d < 4: h = max(h, 2.2 + 1.6 * math.sqrt(1 - (d / 4) ** 2) * (0.85 + 0.15 * math.sin(t + b)))
        top = G.PY0 - int(round(h)) + 1
        if (x, G.PY0) not in G.inside: continue
        for y in range(top, G.PY0 + 2):
            if (x, y) in G.text_near: continue
            c = S_HI if y == top else S_LT if y == top + 1 else S_MD if y <= G.PY0 else S_DK
            put(px, G, x, y, c)
    for x, y, r, pop in bubs:
        bubble(px, G, x, y, r, pop)
    # goo coating the bottom edge, thick drips forming and falling
    for x in range(G.PX0 + 6, G.PX1 - 5):
        if (x, G.PY1) not in G.inside: continue
        h = 1 + round(1.2 * (0.5 + 0.5 * math.sin(x * 0.37)) + 0.8 * (0.5 + 0.5 * math.sin(x * 0.11 + 2)))
        for y in range(G.PY1 - 1, G.PY1 + h + 1):
            if (x, y) in G.text_near: continue
            put(px, G, x, y, S_LT if y == G.PY1 - 1 else S_DD if y == G.PY1 + h else S_DK if y == G.PY1 + h - 1 else S_MD)
    for k, x in enumerate((G.PX0 + 13, G.PX0 + 34, G.CX + 2, G.CX + 25, G.PX1 - 15)):
        ph = (i + k * 7) % N
        base = G.PY1 + 2
        if ph < 11:
            L = 1 + round(4 * (ph / 10) ** 1.4)
            for r in range(L):
                put(px, G, x, base + r, S_MD); put(px, G, x + 1, base + r, S_DK)
            if L >= 3:
                by_ = base + L
                for a, b, c in ((-1, 0, S_LT), (0, 0, S_MD), (1, 0, S_MD), (2, 0, S_DK), (-1, 1, S_MD), (0, 1, S_MD), (1, 1, S_DK), (2, 1, S_DD), (0, 2, S_DK), (1, 2, S_DD)):
                    put(px, G, x + a, by_ + b - 1, c)
                put(px, G, x, by_ - 1, S_HI)
        else:
            put(px, G, x, base, S_MD); put(px, G, x + 1, base, S_DK)
            y = base + 4 + (ph - 11)
            if ph < 14:
                for a, b, c in ((0, 0, S_HI), (1, 0, S_MD), (0, 1, S_MD), (1, 1, S_DK)): put(px, G, x + a, y + b, c)
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        put(px, G, x0 + side, G.MID, S_LT); put(px, G, x0 + side * 2, G.MID, S_MD)
        flask(px, G, x0 + side * 7, G.MID + 4, t, i, side)
    # gem: a drop of venom
    # the gem (recorded in the gem pass: it becomes a glyph of its own)
    cx, cy = G.DIV, G.MID
    for a, b, c in ((0, -3, S_LT), (-1, -2, S_LT), (0, -2, S_HI), (1, -2, S_MD), (-2, -1, S_LT), (-1, -1, S_HI), (0, -1, S_LT), (1, -1, S_MD), (2, -1, S_DK),
                    (-2, 0, S_MD), (-1, 0, S_LT), (0, 0, S_MD), (1, 0, S_DK), (2, 0, S_DD), (-1, 1, S_DK), (0, 1, S_DK), (1, 1, S_DD)):
        gem_put(px, G, cx + a, cy + b + 1, c)
    texts(px, G, i,
          ramp((0, (244, 255, 220)), (0.3, (196, 248, 110)), (0.75, (112, 196, 44)), (1, (70, 140, 30))),
          ramp((0, (255, 255, 240)), (0.4, (200, 255, 110)), (1, (96, 222, 50))),
          (6, 16, 4), shine_col=(250, 255, 230))
    return im


# ------------------------------------------------------------------------------------------------
# The chat banner: a vat of something that should not be touched. A bar of very dark green glass; along its bottom
# lies a bright pool that heaves (its surface a row or two higher here and there, moving), bubbles rise out of it
# and burst before they reach the words' middle, and from the upper rail, which is coated in slime (an uneven lip
# hanging over the bar), a drop swells and falls now and then. The rails are corroded brass. Off the left end
# stands a small flask that bubbles; off the right end the slime drips. Behind the words the glass stays dark.
# ------------------------------------------------------------------------------------------------
CHAT_GEM = {"#": (236, 255, 196), "a": (184, 255, 84), "w": (255, 255, 240), "b": (58, 150, 26), "c": (30, 86, 16)}
CHAT_BUBBLES = ((4, 0), (13, 9), (21, 4), (29, 13), (38, 2), (46, 11), (54, 6), (61, 15))      # column in the tile, frame offset
CHAT_DROPS = ((9, 0), (41, 8))                                                             # column in the tile, frame offset
BRASS, BRASS_D = (150, 126, 60), (84, 68, 34)


def chat_geo(Geo):
    return Geo(TITLE, tile=C.TILE, pointed=False, grow=16)


def chat_frame(G, i):
    im, px = G.canvas()
    ph = i % N
    t = 2 * math.pi * ph / N
    base = G.PY1 - 2

    def pool(xt):
        """rows of liquid over the bar's floor at a column: it heaves (the same every 64 columns and every loop)"""
        return 0.9 + 0.6 * math.sin(2 * math.pi * xt / 32 + t) + 0.35 * math.sin(2 * math.pi * xt / 16 - 2 * t)

    def lip(xt):
        """rows of slime hanging under the upper rail"""
        return 0.6 + 2.0 * C.noise(xt, 0, 4, 4, 64, 16, 701) ** 2

    for (x, y) in G.inside:
        xt = x - G.TILE0
        d = min(x - G.PX0, G.PX1 - x)
        g = (y - G.PY0 - 2) / 13
        if y == G.PY0:
            c = C.lerp(BRASS, S_MD, 0.5 * C.noise(xt, 0, 4, 4, 64, 16, 703))
        elif y == G.PY0 + 1:
            c = C.lerp(S_MD, S_DK, C.noise(xt, 3, 4, 4, 64, 16, 705))
        elif y == G.PY1 - 1:
            c = S_MD
        elif y == G.PY1:
            c = C.lerp(BRASS_D, S_DD, 0.4)
        else:
            c = C.lerp((12, 30, 10), (5, 14, 5), g)
            depth = base - y                                       # 0 on the bar's lowest row
            h = pool(xt)
            if depth < h:
                # the pool: a lit surface over a dark body (the letters' feet stand in it: it may not be bright)
                c = C.hush(S_MD, 0.16) if depth > h - 1 else C.hush(S_DK, 0.08)
            elif y - (G.PY0 + 2) < lip(xt):
                c = C.hush(C.lerp(S_DK, S_MD, 0.5 if y == G.PY0 + 2 else 0.0), 0.12)      # the slime's lip
            else:
                glow = max(0.0, 1 - (depth - h) / 5)               # the pool's light on the glass over it
                c = C.hush(C.lerp(c, (60, 150, 30), 0.35 * glow), 0.05)
        if d < 2 and G.PY0 < y < G.PY1: c = BRASS if d == 0 else BRASS_D
        k = C.corner(G, x, y, 2.0)
        if k > 0: px[x, y] = tuple(c) + (int(255 * k),)

    for x0 in range(G.TILE0, G.R0 - 7, 64):
        # bubbles: each rises five rows out of the pool and bursts (a ring, then four specks)
        for col, off in CHAT_BUBBLES:
            x, k = x0 + col, (ph + off) % N
            if x >= G.R0 - 2 or k >= 9: continue
            if k < 6:
                y = base - 2 - k
                C.blend(px, G, x, y, S_LT, 190)
                C.blend(px, G, x + 1, y, S_MD, 150)
                C.blend(px, G, x, y - 1, S_HI, 150)
            else:
                y, r = base - 8, k - 5
                for a, b in ((-r, 0), (r, 0), (0, -r), (0, r)): C.blend(px, G, x + a, y + b, S_MD, 150 - 40 * (r - 1))
        # drops off the slime: one swells for half the loop, then falls and is gone before the pool
        for col, off in CHAT_DROPS:
            x, k = x0 + col, (ph + off) % N
            if x >= G.R0 - 2: continue
            if k < 8:
                for r in range(1 + k // 3): C.put(px, G, x, G.PY0 + 3 + r, S_MD if r < k // 3 else S_LT)
            else:
                y = G.PY0 + 6 + (k - 8) * (k - 7) // 2
                if y < base - 2:
                    C.blend(px, G, x, y, S_LT, 200)
                    C.blend(px, G, x, y - 1, S_MD, 140)

    # the flask off the left end: glass, a cork, the liquid in it heaving, a bubble over its mouth
    fx, fy = G.PX0 - 8, G.PY1 - 1
    for a in range(-3, 4):
        for b in range(0, 7):
            r = math.hypot(a, b - 3)
            if r <= 3.3:
                inside = r <= 2.4
                level = 2.0 + 0.6 * math.sin(t + a)
                c = (S_LT if b - 0 >= 5 - level else None) if inside else GLASS_D
                if inside and c is None: c = (30, 60, 40)
                C.put(px, G, fx + a, fy - 6 + b, c if c != S_LT or True else c)
    for b in range(3): C.put(px, G, fx, fy - 9 + b, GLASS if b else CORK)
    C.put(px, G, fx - 1, fy - 7, GLASS_D); C.put(px, G, fx + 1, fy - 7, GLASS_D)
    C.blend(px, G, fx + (ph // 4) % 2, fy - 11 - (ph % 4), S_LT, 200 - 40 * (ph % 4))
    # the slime dripping off the right end
    for k, (h, off) in enumerate(((5, 0), (9, 5), (4, 10))):
        x = G.PX1 + 2 + k * 3
        n = h + ((ph + off) % N) // 5
        for b in range(n):
            y = G.PY0 + b
            if y <= G.PY1: C.put(px, G, x, y, S_HI if b == n - 1 else S_MD if b else S_DK)
        C.put(px, G, x - 1, G.PY0, S_DK); C.put(px, G, x + 1, G.PY0, S_DK)
    C.gem7(px, G, CHAT_GEM)
    return im
