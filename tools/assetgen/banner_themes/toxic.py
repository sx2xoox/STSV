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
# and burst before they reach the words' middle. The vat is plumbed in corroded brass: a pipe runs along the top
# (lit above, dark below, verdigris on it), bolted together by a flange every 32 columns, and things stand up from
# it so that its outline is never straight: the flanges, rivets, the handle of a stop valve, and a blob of slime
# that has forced its way out of the pipe, runs down it and hangs over the bar as an uneven lip, from which a drop
# swells and falls now and then. A second pipe lies along the bottom. Each end is a bolted brass plate. Off the
# left end stands a round flask as high as the line, corked, the poison heaving and bubbling in it, a glass tube
# feeding it into the vat; off the right end a pressure gauge on a stub of pipe, its needle trembling in the red.
# Behind the words the glass stays dark.
# ------------------------------------------------------------------------------------------------
CHAT_GEM = {"#": (236, 255, 196), "a": (184, 255, 84), "w": (255, 255, 240), "b": (58, 150, 26), "c": (30, 86, 16)}
CHAT_BUBBLES = ((4, 0), (13, 9), (21, 4), (29, 13), (38, 2), (46, 11), (54, 6), (61, 15))      # column in the tile, frame offset
CHAT_DROPS = ((10, 0), (42, 8))                                                            # column in the tile, frame offset
BRASS, BRASS_D = (150, 126, 60), (84, 68, 34)
BRASS_L, BRASS_W, BRASS_S, BRASS_P = (204, 176, 92), (240, 220, 150), (104, 86, 42), (46, 36, 18)   # lit, glint, shade, pit
VERD, VERD_D = (86, 158, 126), (50, 102, 82)
RED_L, RED, RED_D = (238, 112, 82), (198, 62, 46), (118, 30, 28)


def chat_geo(Geo):
    return Geo(TITLE, tile=C.TILE, pointed=False, grow=16)


def _phase(G, x):
    """the column of the 16-column rhythm a plate column is on: the tile's own up to the right end, which starts a
    cell of its own (the plate grows by whole cells, so the rhythm runs on into it)"""
    return x - G.TILE0 if x < G.R0 else x - G.R0


def _flask(px, G, cx, cy, t, ph):
    """a round-bottomed flask about (cx, cy), seven pixels in radius, its neck and cork above: glass lit from the
    left, the liquid heaving, bubbles rising in it"""
    level = cy - 2 + round(0.8 * math.sin(t))                            # the liquid's top row
    for y in range(G.PY0, G.PY1 + 1):
        for x in range(cx - 8, cx + 9):
            if not 0 <= x < G.W or (x, y) in G.inside: continue
            dx, dy = x - cx, y - cy
            r = math.hypot(dx, dy)
            c = None
            if dy < -7:                                                  # the cork, the neck's rim under it
                if abs(dx) <= 2 and dy < -8: c = CORK if dx < 2 else (100, 64, 36)
                elif abs(dx) <= 3 and dy == -8: c = GLASS if dx < 2 else GLASS_D
            elif abs(dx) <= 1 and dy <= -6: c = (22, 46, 32)             # the neck's bore
            elif abs(dx) == 2 and dy <= -6: c = GLASS if dx < 0 else GLASS_D
            elif r <= 7.3:
                if r > 6.2: c = GLASS if (dx < 0 and dy < 4) or dy < -4 else GLASS_D
                elif y < level: c = (22, 46, 32)
                elif y == level: c = S_HI if dx < 0 else S_LT
                else: c = S_LT if dx <= -3 and dy < 4 else S_DK if dx + dy > 5 else S_MD
            if c: px[x, y] = tuple(c) + (255,)
    for a, b in ((-4, -4), (-5, -3), (-5, -2)): C.put(px, G, cx + a, cy + b, (250, 255, 250))   # the gloss
    for k, a in enumerate((-2, 2, 0)):
        q = (ph + k * 5) % N
        if q < 6 and cy + 5 - q > level: C.put(px, G, cx + a, cy + 5 - q, S_HI)


def _gauge(px, G, cx, cy, turn):
    """a pressure gauge seen from the front about (cx, cy), both between two pixels: a brass rim, a pale dial with
    two ticks and a red zone, a needle `turn` degrees round from pointing right (it stands in the red)"""
    for y in range(G.PY0, G.PY1 + 1):
        for x in range(int(cx) - 8, int(cx) + 9):
            if not 0 <= x < G.W or (x, y) in G.inside: continue
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            r = math.hypot(dx, dy)
            if r > 7.6: continue
            if r > 5.4: c = BRASS_W if dx + dy < -7 else BRASS_L if dx + dy < -1 else BRASS if dx + dy < 5 else BRASS_D
            elif r > 3.6 and dx > 0.9 and dy < 0: c = RED if dx + dy < 3 else RED_D                 # the red zone
            elif r > 3.9 and (abs(dx) < 1 and dy < 0 or abs(dy) < 1 and dx < 0): c = (70, 76, 60)  # ticks
            else: c = (238, 240, 218) if dx + dy < 2 else (204, 210, 184)
            px[x, y] = tuple(c) + (255,)
    a = math.radians(turn)
    for k in range(11):
        C.put(px, G, int(math.floor(cx + 0.45 * k * math.cos(a))), int(math.floor(cy + 0.45 * k * math.sin(a))), (40, 30, 26))
    for a_, b_ in ((-1, -1), (0, -1), (-1, 0), (0, 0)):
        C.put(px, G, int(cx) + a_, int(cy) + b_, BRASS_P if a_ + b_ else BRASS_S)


def chat_frame(G, i):
    im, px = G.canvas()
    ph = i % N
    t = 2 * math.pi * ph / N
    base = G.PY1 - 2

    def pool(xt):
        """rows of liquid over the bar's floor at a column: it heaves (the same every 64 columns and every loop)"""
        return 0.9 + 0.6 * math.sin(2 * math.pi * xt / 32 + t) + 0.35 * math.sin(2 * math.pi * xt / 16 - 2 * t)

    def lip(xt):
        """rows of slime hanging under the upper pipe"""
        return 0.6 + 2.0 * C.noise(xt, 0, 4, 4, 64, 16, 701) ** 2

    for (x, y) in G.inside:
        xt = x - G.TILE0
        c32 = _phase(G, x) % 32
        d = min(x - G.PX0, G.PX1 - x)
        ry = y - G.PY0
        g = (y - G.PY0 - 2) / 13
        live = G.TILE0 <= x < G.R0                                       # what stands on the pipe stands in the tile only
        rust = C.noise(xt, 0, 4, 2, 64, 16, 703) > 0.6                   # verdigris
        if d < 3:                                                        # the end plate: bolted brass, lit from the left
            if d == 0 and ry in (0, 17): continue
            c = (BRASS_L, BRASS, BRASS_D)[x - G.PX0 if x - G.PX0 < 3 else 2 - (G.PX1 - x)]
            if ry == 0: c = BRASS_L
            if ry == 17: c = BRASS_D
            if d == 1 and ry in (3, 9, 14): c = BRASS_P
            if d == 1 and ry in (2, 8, 13): c = BRASS_W
        elif c32 < 4 and (ry < 4 or ry > 13):                            # a flange: four columns, four rows deep
            side = c32 in (0, 3)
            k = ry if ry < 4 else ry - 14
            if ry < 4: c = ((BRASS, BRASS_L), (BRASS, BRASS_W if c32 == 1 else BRASS_L), (BRASS_D, BRASS_S), (BRASS_P, BRASS_D))[k][0 if side else 1]
            else: c = ((BRASS_P, BRASS_D), (BRASS_D, BRASS_S), (BRASS, BRASS_L), (BRASS_D, BRASS))[k][0 if side else 1]
        elif ry == 0:                                                    # what stands up from the pipe; air between
            if live and 8 <= c32 <= 12: c = S_HI if c32 == 9 else S_LT if c32 == 10 else S_MD       # the slime's blob
            elif live and 22 <= c32 <= 26: c = RED_L if c32 == 22 else RED_D if c32 == 26 else RED    # the valve's handle
            elif c32 in (6, 18): c = BRASS                               # a rivet
            else: continue
        elif ry == 1:                                                    # the pipe, lit
            c = VERD if rust else BRASS if c32 % 8 == 7 else BRASS_L
            if live and 9 <= c32 <= 11: c = S_LT if c32 == 10 else S_MD
            elif live and c32 == 24: c = BRASS_P
        elif ry == 2:                                                    # its underside (the words' first row: dark)
            c = VERD_D if rust else BRASS_S
            if live and c32 in (10, 11): c = C.hush(S_MD, 0.16) if c32 == 10 else S_DK
        elif ry == 16:
            c = VERD if C.noise(xt, 5, 4, 2, 64, 16, 707) > 0.66 else BRASS_L if c32 % 8 == 2 else BRASS
        elif ry == 17:
            c = BRASS_D
        else:
            c = C.lerp((12, 30, 10), (5, 14, 5), g)
            depth = base - y                                       # 0 on the bar's lowest row
            h = pool(xt)
            if depth < h:
                # the pool: a lit surface over a dark body (the letters' feet stand in it: it may not be bright)
                c = C.hush(S_MD, 0.16) if depth > h - 1 else C.hush(S_DK, 0.08)
            elif y - (G.PY0 + 3) < lip(xt):
                c = C.hush(C.lerp(S_DK, S_MD, 0.5 if y == G.PY0 + 3 else 0.0), 0.12)      # the slime's lip
            else:
                glow = max(0.0, 1 - (depth - h) / 5)               # the pool's light on the glass over it
                c = C.hush(C.lerp(c, (60, 150, 30), 0.35 * glow), 0.05)
        px[x, y] = tuple(c) + (255,)

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
        # drops off the slime under each blob: one swells for half the loop, then falls and is gone before the pool
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

    # the left end: the flask, and the glass tube that feeds it into the vat
    fx, fy = G.PX0 - 13, G.PY0 + 10
    for x in range(fx + 7, G.PX0):
        for b, c in ((-1, GLASS), (0, S_LT if (x + ph // 2) % 3 else S_HI), (1, GLASS_D)): C.put(px, G, x, fy + b, c)
    _flask(px, G, fx, fy, t, ph)
    # the right end: a pressure gauge on a stub of pipe (its needle trembles in the red), slime gathering under it
    for x in range(G.PX1 + 1, G.PX1 + 8):
        for b, c in ((-1, BRASS_L), (0, BRASS), (1, BRASS_D)): C.put(px, G, x, G.PY0 + 9 + b, c)
    for b in range(-2, 3): C.put(px, G, G.PX1 + 3, G.PY0 + 9 + b, BRASS_W if b < 0 else BRASS if b == 0 else BRASS_D)
    for b in range(-2, 3): C.put(px, G, G.PX1 + 4, G.PY0 + 9 + b, BRASS_D if b else BRASS_P)
    _gauge(px, G, G.PX1 + 14.0, G.PY0 + 9.0, -52 + (6, 0, -8, -2, 8, 2, -6, 0)[ph % 8])
    wx = G.PX1 + 13
    for a, b, c in ((0, 16, S_MD), (1, 16, S_DK), (-1, 16, S_MD)): C.put(px, G, wx + a, G.PY0 + b, c)
    if 4 <= ph < 12: C.put(px, G, wx, G.PY0 + 17, S_LT if ph >= 8 else S_MD)
    C.gem7(px, G, CHAT_GEM)
    return im
