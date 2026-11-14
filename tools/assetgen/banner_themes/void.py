"""6 void: a black hole turning over the middle, tentacles off both ends, runes (approved art t06).

chat_frame: its chat banner (banner_themes/chat.py), drawn for one chat line."""
import math, random
from .lib_a import *
from . import chat as C
TITLE, SLUG = "심연의 방랑자", "void"
GEO = dict(side=13)
STATIC = 3
CORE, BODY, RIM, RIM2, HOT, PALE = (14, 2, 24), (40, 10, 70), (156, 76, 240), (96, 40, 170), (210, 146, 255), (244, 228, 255)
RUNES = [["#.#.#", ".###.", "..#..", "..#..", "..#.."], ["..#..", ".#.#.", "..#..", ".#.#.", "#...#"], ["#...#", "##.##", "#.#.#", "##.##", "#...#"],
         ["..#..", ".#.#.", "#.#.#", ".#.#.", "..#.."], ["#....", "##...", "#.#..", "##...", "#...."]]

def tentacle(px, G, bx, by, h, w0, ph, t, lean=0.0, curl=1):
    rows = []
    for r in range(int(h) + 1):
        f = r / h
        x = bx + lean * r + math.sin(r * 0.28 - t + ph) * 3.0 * f + curl * 3.0 * f ** 3 * (1 + 0.3 * math.sin(t + ph))
        rows.append((x, by - r, max(0.5, w0 * (1 - f) ** 0.8)))
    S = set()
    for x, y, w in rows:
        for xx in range(int(math.floor(x - w + 0.5)), int(math.floor(x + w + 0.5)) + 1):
            S.add((xx, y))
    x, y, _ = rows[-1]
    for a, b in ((curl, -1), (2 * curl, -1), (3 * curl, 0), (3 * curl, 1)): S.add((round(x) + a, y + b))
    for (x, y) in S:
        if (x, y) in G.inside: continue
        l, r_ = (x - 1, y) in S, (x + 1, y) in S
        l2, r2 = (x - 2, y) in S, (x + 2, y) in S
        c = PALE if not l and not r_ else HOT if not l else (72, 24, 128) if not r_ else RIM if not l2 else (96, 38, 168) if not r2 else (124, 54, 210)
        if (x, y - 1) not in S and l and r_: c = HOT
        put(px, G, x, y, c)

def frame(G, i):
    t = 2 * math.pi * i / N
    im, px = G.canvas()
    vx, vy, RX, RY = G.CX, G.PY0 - 9, 17.0, 7.5
    # the rift: a rotating three-armed vortex with a black core
    rot = 2 * math.pi / 3 * i / N
    for y in range(int(vy - RY - 1), int(vy + RY + 2)):
        for x in range(int(vx - RX - 1), int(vx + RX + 2)):
            dx, dy = (x - vx) / RX, (y - vy) / RY
            r = math.hypot(dx, dy)
            if r > 1.0 or not (0 <= x < G.W and 0 <= y < G.H): continue
            a = math.atan2(dy, dx)
            s = math.sin(3 * (a - rot) + r * 7.5)
            if r < 0.22: c = (4, 0, 8)
            elif r < 0.32: c = HOT if s > -0.2 else RIM
            elif s > 0.55: c = RIM if r < 0.7 else RIM2
            elif s > 0.0: c = (70, 26, 130) if r < 0.85 else (50, 16, 92)
            else: c = (24, 6, 42) if r < 0.9 else None
            if c is None: continue
            if r > 0.9 and s <= 0.55: continue
            put(px, G, x, y, c)
    # motes spiralling into the rift
    for k in range(12):
        ph = ((i + k * 16 / 12) % N) / N
        r = 1.6 - 1.3 * ph
        a = k * 2.4 + 5.0 * ph + rot * 3
        x = vx + math.cos(a) * RX * r; y = vy + math.sin(a) * RY * r
        if r > 1.0 and empty(px, G, round(x), round(y)):
            put(px, G, round(x), round(y), PALE if k % 3 == 0 else HOT)
            hx, hy = round(x), round(y)
            tx_ = hx + (1 if x > vx else -1) if abs(x - vx) / RX > abs(y - vy) / RY else hx
            ty_ = hy if tx_ != hx else hy + (1 if y > vy else -1)
            if empty(px, G, tx_, ty_): put(px, G, tx_, ty_, RIM2)
    # tentacles from the ends
    for side in (-1, 1):
        ex = G.PX0 + 5 if side < 0 else G.PX1 - 5
        tentacle(px, G, ex, G.PY0 + 4, 19, 3.4, side * 1.3, t, lean=side * 0.38, curl=-side)
        tentacle(px, G, ex - side * 10, G.PY0 + 2, 12, 2.4, side * 2.4 + 1, t + 1.5, lean=side * 0.15, curl=side)
    # runes fading in and out beside the rift
    spots = [(G.CX - 32, G.PY0 - 12), (G.CX + 28, G.PY0 - 14), (G.CX - 46, G.PY0 - 8), (G.CX + 42, G.PY0 - 9)]
    for k, (rx, ry) in enumerate(spots):
        ph = (i + k * 4) % N
        if ph >= 10: continue
        col = ((70, 26, 130), RIM2, RIM, HOT, PALE, PALE, HOT, RIM, RIM2, (70, 26, 130))[ph]
        ry -= ph // 4
        for r, row in enumerate(RUNES[k]):
            for c, ch in enumerate(row):
                if ch == "#":
                    if 3 <= ph <= 6:
                        for a, b in DIRS4:
                            if empty(px, G, rx + c + a, ry + r + b): put(px, G, rx + c + a, ry + r + b, (120, 50, 210), 100)
                    put(px, G, rx + c, ry + r, col)
    glow(px, G, (150, 60, 255), 150, ring=2)
    def bev(x, y):
        p = 0.5 + 0.5 * math.sin(x * 0.25 - t * 2 + y)
        return lerp((60, 16, 120), (190, 110, 255), p ** 2)
    plate(px, G, lambda x, y, g: lerp((40, 12, 62), (6, 2, 12), g), (196, 140, 255), (84, 36, 138), bevel=bev)
    rivets(px, G, (220, 170, 255), (110, 50, 180))
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for k in range(1, 5): put(px, G, x0 + side * k, G.MID, RIM if k < 4 else PALE)
        put(px, G, x0 + side * 3, G.MID - 1, RIM2); put(px, G, x0 + side * 3, G.MID + 1, RIM2)
    # the eye gem: violet iris, slit pupil, blinks
    # the gem (recorded in the gem pass: it becomes a glyph of its own)
    cx, cy = G.DIV, G.MID
    if i in (11, 12):
        for a in range(-2, 3): gem_put(px, G, cx + a, cy, RIM if abs(a) < 2 else RIM2)
    else:
        for a, b, c in ((-2, 0, RIM2), (2, 0, RIM2), (-1, -1, RIM), (0, -1, PALE), (1, -1, RIM), (-1, 1, (110, 40, 190)), (0, 1, (160, 80, 240)), (1, 1, (110, 40, 190)),
                        (-1, 0, HOT), (1, 0, HOT), (0, 0, (10, 0, 20)), (0, -2, (70, 30, 120)), (0, 2, (70, 30, 120))):
            gem_put(px, G, cx + a, cy + b, c)
    texts(px, G, i,
          ramp((0, (246, 232, 255)), (0.3, (206, 160, 255)), (0.75, (140, 80, 220)), (1, (100, 50, 170))),
          ramp((0, (255, 255, 255)), (0.4, (226, 176, 255)), (1, (170, 96, 255))),
          (10, 0, 20), shine_col=(250, 236, 255))
    return im


# ------------------------------------------------------------------------------------------------
# The chat banner: a rift into the void. A bar of near-black violet in which a dim nebula turns slowly (two layers of
# cloud sliding against each other, once round in a loop), held in a frame of obsidian whose outline is toothed:
# the outer edge of each rail is a bright violet line (the row that does not touch the words), broken by a dark
# cartouche with a rune in it every 16 columns and by a fang between two cartouches (a notch either side of it); a
# light passes from rune to rune along the upper rail and back along the lower. Each end of the bar is closed by a
# thick obsidian cap with a violet stone set in it, and a thorn stands out from every corner. The abyss looks out
# at both ends: off the left end an eye in a thorned socket, as high as the line, its slit pupil wandering; off the
# right end a smaller one that sleeps, a glowing slit, and opens for the moment in which the great eye blinks.
# A few faint stars wink. The gem between the words is the black hole itself: a dark disc in a bright ring. Behind
# the words the void stays dark (it is the darkest of all the banners).
# ------------------------------------------------------------------------------------------------
CHAT_RUNES = ("#.#", ".#.", "#.#"), (".#.", "###", ".#."), ("##.", ".#.", ".##"), ("#.#", "###", "#.#")
CHAT_STARS = ((5, 4, 1), (13, 11, 6), (22, 6, 11), (30, 13, 3), (39, 5, 14), (47, 10, 8), (55, 7, 0), (61, 12, 5))
CHAT_LOOK = (0, 1, 2, 2, 2, 1, 0, 0, 0, -1, -2, -2, -2, -1, 0, 0)       # where the pupil is, frame by frame
CHAT_BLINK = {13: 0.55, 14: 1.0, 15: 0.55}                              # how far the lids are shut
CHAT_PEEK = {12: 0.5, 13: 0.2, 14: 0.15, 15: 0.45}                      # the sleeping eye's lids, while the other blinks
CHAT_EDGE = (150, 92, 232)                                              # the rails' outer line
CHAT_CAP = 5                                                            # the cap at each end, in columns
CHAT_METAL = ((96, 52, 164), (56, 26, 104), (36, 14, 68), (14, 3, 26))  # obsidian: edge light, face, shade, pit


def chat_geo(Geo):
    return Geo(TITLE, tile=C.TILE, pointed=False, grow=16)


def _phase(G, x):
    """the column of the 16-column rhythm a plate column is on: the tile's own up to the right end, which starts a
    cell of its own (the plate grows by whole cells, so the rhythm runs on into it)"""
    return x - G.TILE0 if x < G.R0 else x - G.R0


def _eye(px, G, cx, cy, look, shut, wide=10.4, tall=5.6, thorns=(0, 4, 7)):
    """an eye in its socket around (cx, cy), cy between two rows, `wide` to each side and `tall` up and down: lids
    of obsidian, thorns above and below, a violet iris, a slit pupil at `look` columns from the middle"""
    edge, face, shade, pit = CHAT_METAL
    k, reach = tall / 5.6, int(wide)
    for y in range(G.PY0, G.PY1 + 1):
        for x in range(cx - reach, cx + reach + 1):
            if not 0 <= x < G.W or (x, y) in G.inside: continue
            dx, dy = x - cx, y + 0.5 - cy
            span = max(0.0, 1 - (abs(dx) / wide) ** 2) ** 0.75
            open_, a = tall * span * (1 - shut), abs(dy)
            full = tall * span                                           # the socket keeps its shape while the lids shut
            c = None
            if a <= open_ and span > 0:
                r = math.hypot(dx - look, dy)
                if abs(dx - look) < 0.9 and a < 3.7 * k: c = (6, 0, 12)                   # the slit
                elif r < 3.3 * k: c = C.lerp(PALE, HOT, r / (3.3 * k)) if dy < 0 else C.lerp(HOT, RIM, r / (3.3 * k))
                elif r < 4.5 * k: c = RIM2
                else: c = (24, 7, 46)
            elif a <= full + 0.2 and span > 0: c = shade if dy < 0 else pit              # the shut lid
            elif a <= full + 1.3 and abs(dx) <= reach: c = CHAT_EDGE if dy < 0 else face   # the socket's rim
            elif a <= full + 2.3 and abs(dx) <= reach: c = pit
            if abs(dx) in thorns and full + 1.3 < a <= min(8.0, full + 3.4 + (1 if dx == 0 else 0)):   # thorns
                c = HOT if a > full + 2.4 + (1 if dx == 0 else 0) else RIM2
            if c: px[x, y] = tuple(c) + (255,)
    if shut < 0.5: C.put(px, G, cx + look - round(2 * k), int(cy) - round(2 * k), PALE)


def chat_frame(G, i):
    im, px = G.canvas()
    ph = i % N
    t = TAU * ph / N if "TAU" in globals() else 2 * math.pi * ph / N
    edge, face, shade, pit = CHAT_METAL

    def nebula(x, y):
        xt = x - G.TILE0
        g = (y - G.PY0 - 2) / 13
        c = C.lerp((20, 6, 40), (7, 1, 16), g)
        a = C.noise(xt + 4 * ph, y, 8, 4, 64, 16, 601)                  # one layer slides right, the other left
        b = C.noise(xt - 4 * ph, y + 5, 16, 4, 64, 16, 603)
        cloud = C.smooth((a * b - 0.2) / 0.3)
        return C.lerp(c, (88, 36, 150), 0.55 * cloud)

    for (x, y) in G.inside:
        xt = x - G.TILE0
        p = _phase(G, x)
        cell, kth = p % 16, (xt // 16) % 4
        d = min(x - G.PX0, G.PX1 - x)
        top = y <= G.MID
        ry = (y - G.PY0) if top else (G.PY1 - y)                         # rows in from the nearer rail
        # the light that passes along the runes: over rune k in frames 4 k .. 4 k + 3 (the lower rail the other way)
        lit = 0.0 if x >= G.R0 else max(0.0, math.cos(2 * math.pi * ((xt - 4 * ph) if top else (-xt - 4 * ph)) / 64)) ** 6
        plate = 5 <= cell <= 9                                           # the cartouche a rune stands in
        dim = 0.0 if top else 0.15
        row = y - G.PY0
        if ry == 0:                                                      # the outline: cartouches, studs and fangs
            if plate: c = edge if cell in (5, 9) else pit
            elif cell in (15, 0): c = C.lerp(PALE if cell == 15 else HOT, (255, 255, 255), 0.6 * lit)
            elif cell in (1, 14) and d >= CHAT_CAP: continue             # a notch either side of a fang
            else: c = C.lerp(CHAT_EDGE, PALE, 0.8 * lit)                 # the bright outer line
            c = C.lerp(c, (0, 0, 0), dim)
        elif ry == 1:                                                    # the band: dark, it lies against the words
            c = pit if 5 < cell < 9 else shade if plate or cell in (15, 0) else (78, 38, 138)
            c = C.lerp(c, (0, 0, 0), dim)
        else:
            c = C.hush(nebula(x, y), 0.04)
            if ry == 2: c = C.lerp(c, (4, 0, 8), 0.6)                    # the rail's shadow
        # the runes: three rows high, in the cartouche and on the row inside the rail; lit as the light passes
        if ry < 3 and 6 <= cell < 9 and G.TILE0 <= x < G.R0:
            if CHAT_RUNES[kth][ry][cell - 6] == "#": c = C.lerp(RIM2 if ry == 2 else RIM, PALE, lit)
        if d < CHAT_CAP:                                                 # the cap: obsidian lit from the left, a stone in it
            k = x - G.PX0 if x - G.PX0 < CHAT_CAP else CHAT_CAP - 1 - (G.PX1 - x)
            c = CHAT_EDGE if row == 0 else shade if row == 17 else (edge, face, face, shade, pit)[k]
            if row in (4, 13) and 0 < k < 4: c = pit                     # its grooves
            stone = {(2, 7): HOT, (1, 8): PALE, (2, 8): HOT, (3, 8): RIM, (1, 9): HOT, (2, 9): RIM, (3, 9): RIM2, (2, 10): RIM2}.get((k, row))
            if stone: c = C.lerp(stone, PALE, 0.35 * (0.5 + 0.5 * math.sin(t)))
        px[x, y] = tuple(c) + (255,)

    # faint stars: each winks for a few frames of the loop (the same every 64 columns)
    for x0 in range(G.TILE0, G.R0 - 7, 64):
        for col, row, off in CHAT_STARS:
            k = (ph + off) % N
            if k >= 5 or x0 + col >= G.R0 - 1: continue
            C.blend(px, G, x0 + col, G.PY0 + 2 + row, (200, 160, 255), (70, 130, 170, 130, 70)[k])

    # a thorn out of every corner: a wedge four rows deep, its point outwards
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for ry in range(4):
            for k in range(1, 7 - 2 * ry + (1 if ry == 0 else 0)):
                c = (HOT if k >= 6 else CHAT_EDGE) if ry == 0 else face if ry == 1 else shade
                C.put(px, G, x0 + side * k, G.PY0 + ry, c)
                C.put(px, G, x0 + side * k, G.PY1 - ry, C.lerp(c, (0, 0, 0), 0.15))

    # the ends: the great eye on the left, the sleeping one on the right
    _eye(px, G, G.PX0 - 11, G.PY0 + 9.0, CHAT_LOOK[ph], CHAT_BLINK.get(ph, 0.0))
    _eye(px, G, G.PX1 + 10, G.PY0 + 9.0, 0, CHAT_PEEK.get(ph, 0.72), wide=7.8, tall=4.4, thorns=(0, 3, 5))
    # the gem: a black hole, a dark disc in a bright ring
    cx, cy = G.DIV, G.MID
    gem = {}
    for a in range(-3, 4):
        for b in range(-3, 4):
            r = math.hypot(a, b)
            if r <= 1.5: gem[(cx + a, cy + b)] = (6, 0, 12)
            elif r <= 2.6: gem[(cx + a, cy + b)] = PALE if (a + b) < 0 else HOT
            elif r <= 3.3 and abs(b) <= 1: gem[(cx + a, cy + b)] = RIM
    C.gem_pixels(px, gem)
    return im
