"""5 royal: a jewelled crown over the middle, a row of gold points along the top, finials, a sweep of light over the
gold (approved art t05).

chat_frame: its chat banner (banner_themes/chat.py), drawn for one chat line."""
import math, random
from .lib_a import *
from . import chat as C
TITLE, SLUG = "황금의 군주", "royal"
GEO = dict(side=15)
STATIC = 6
G_W, G_L, G_M, G_D, G_DD, OUT = (255, 250, 214), (255, 226, 120), (236, 176, 52), (182, 118, 26), (120, 70, 16), (60, 26, 8)
RUBY = ((255, 150, 150), (230, 30, 50), (140, 8, 30))
SAPH = ((170, 210, 255), (40, 90, 230), (20, 40, 130))
EMER = ((170, 255, 190), (30, 190, 90), (10, 100, 50))

def gold_x(x, x0, x1):
    f = (x - x0) / max(1, x1 - x0)
    return G_L if f < 0.3 else G_M if f < 0.75 else G_D

def spiky(px, G, cx, by, tips, dips, band, jewels, pearl_tips, outline=True):
    """gold rim with faceted points: tips [(dx, h)], dips h, band rows; drawn bottom at row by (inclusive)."""
    from PIL import Image as _I, ImageDraw as _D
    xs = [d for d, _ in tips]; x0, x1 = min(xs), max(xs)
    poly = [(x0, 0)]
    for k, (d, h) in enumerate(tips):
        poly.append((d, -h))
        if k < len(tips) - 1: poly.append(((d + tips[k + 1][0]) / 2, -dips))
    poly.append((x1, 0))
    off = 40
    m = _I.new("L", (120, 60), 0); _D.Draw(m).polygon([(p[0] + off, p[1] + 50) for p in poly], fill=255); mk = m.load()
    S = {(x - off, y - 50) for y in range(60) for x in range(120) if mk[x, y]}
    S |= {(x, -y) for x in range(x0, x1 + 1) for y in range(band)}
    if outline:
        for (x, y) in S:
            for a, b in ((1, 0), (-1, 0), (0, -1), (1, -1), (-1, -1)):
                q = (x + a, y + b)
                if q not in S:
                    Q = (cx + q[0], by + q[1])
                    if Q not in G.inside: put(px, G, Q[0], Q[1], OUT)
    for (x, y) in S:
        X, Y = cx + x, by + y
        if -y < band:
            r = band - 1 + y            # 0 = top band row
            c = G_W if -y == band - 1 else G_DD if y == 0 else G_M if r == band - 2 else G_D
            if (X, Y) in G.inside: continue
        else:
            d = min(tips, key=lambda p: abs(p[0] - x))[0]
            c = G_L if x < d else G_M if x == d else G_D
            if x == d and -y > band + 1: c = G_W if x < 0 else G_L
        put(px, G, X, Y, c)
    for dx, J in jewels:
        put(px, G, cx + dx, by - band + 2, J[1]); put(px, G, cx + dx + 1, by - band + 2, J[2])
    for d, h in tips:
        if pearl_tips:
            put(px, G, cx + d, by - h - 1, (255, 255, 255))
            for a, b in ((-1, -1), (1, -1), (0, -2)):
                if empty(px, G, cx + d + a, by - h + b): put(px, G, cx + d + a, by - h + b, OUT)

def crown(px, G, cx, by, t):
    spiky(px, G, cx, by - 1, [(-14, 11), (-7, 9), (0, 14), (7, 9), (14, 11)], 4, 4, [(-9, SAPH), (-1, RUBY), (8, EMER)], True)
    put(px, G, cx, by - 16, RUBY[0]); put(px, G, cx, by - 15, RUBY[1])

def star(px, G, x, y, r):
    put(px, G, x, y, G_W)
    for k in range(1, r + 1):
        for a, b in DIRS4:
            if empty(px, G, x + a * k, y + b * k): put(px, G, x + a * k, y + b * k, G_L if k < r else G_D, 255 if k < r else 200)

def frame(G, i):
    t = 2 * math.pi * i / N
    im, px = G.canvas()
    cx, by = G.CX, G.PY0
    glow(px, G, (255, 196, 60), 120)
    # plate: crimson velvet, quilted, with a polished gold band and inset jewels
    def fill(x, y, g):
        c = lerp((156, 22, 46), (58, 6, 20), g)
        if (x, y) not in G.text_near and ((x + y) % 8 == 0 or (x - y) % 8 == 0): c = lerp(c, (40, 2, 12), 0.35)
        return c
    def bev(x, y):
        return G_L if y < G.MID - 3 else G_D if y > G.MID + 3 else G_M
    plate(px, G, fill, (255, 240, 170), (170, 108, 24), bevel=bev)
    js = [RUBY, SAPH, EMER]
    for k, x in enumerate(range(G.PX0 + 12, G.PX1 - 10, 12)):
        J = js[k % 3]
        for y in (G.PY0 + 1, G.PY1 - 1):
            if (x, y) in G.bevel:
                put(px, G, x, y, J[1]); put(px, G, x + 1, y, J[2])
    for sx, J in ((G.PX0 + 4, SAPH), (G.PX1 - 4, SAPH)):
        put(px, G, sx - 1, G.MID - 1, J[0]); put(px, G, sx, G.MID - 1, J[1]); put(px, G, sx - 1, G.MID, J[1]); put(px, G, sx, G.MID, J[2])
    # sceptre tips at both ends
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for k in range(1, 4): put(px, G, x0 + side * k, G.MID, G_M)
        put(px, G, x0 + side * 4, G.MID - 1, G_L); put(px, G, x0 + side * 4, G.MID + 1, G_D)
        for k, c in ((4, RUBY[1]), (5, G_L), (6, G_M), (7, G_W)): put(px, G, x0 + side * k, G.MID, c)
        put(px, G, x0 + side * 5, G.MID - 1, G_M); put(px, G, x0 + side * 5, G.MID + 1, G_D)
        put(px, G, x0 + side * 4, G.MID - 2, G_D); put(px, G, x0 + side * 4, G.MID + 2, G_DD)
    for side in (-1, 1):
        for k, d in enumerate(range(21, (G.PW // 2) - 6, 7)):
            h = max(4, 8 - k)
            spiky(px, G, cx + side * d, by - 1, [(-2, 1), (0, h), (2, 1)], 1, 1, [], False)
            put(px, G, cx + side * d, by - 1 - h - 1, (255, 255, 255))
    crown(px, G, cx, by, t)
    gem(px, G, G.DIV, G.MID, (255, 255, 255), (220, 240, 255), (160, 200, 240), (110, 140, 200), (70, 90, 150), top=(255, 255, 255))
    texts(px, G, i,
          ramp((0, (255, 255, 236)), (0.2, (255, 232, 140)), (0.65, (240, 178, 56)), (1, (176, 104, 22))),
          ramp((0, (255, 255, 255)), (0.4, (255, 240, 180)), (1, (255, 200, 80))),
          (44, 4, 12), shine_col=(255, 255, 255))
    # light sweep over all the gold once per loop
    sw = -20 + (G.W + 40) * i / N
    for y in range(G.H):
        for x in range(G.W):
            c = px[x, y]
            if c[3] == 255 and c[0] > 150 and c[0] > c[2] + 60 and abs(x - sw + y * 0.6) < 2.5:
                px[x, y] = lerp(c, (255, 255, 240), 0.75) + (255,)
    # twinkles
    for k, (x, y) in enumerate(((cx - 13, by - 9), (cx + 14, by - 12), (G.PX0 + 6, G.PY0 - 4), (G.PX1 - 7, G.PY0 - 6), (cx - 26, by - 13), (cx + 28, by - 8), (cx + 3, by - 17))):
        ph = (i + k * 5) % 12
        if ph < 5: star(px, G, x, y, (1, 2, 3, 2, 1)[ph])
    dr = random.Random(2)
    for k in range(10):
        x = dr.randint(G.PX0, G.PX1); y0 = dr.randint(0, 15)
        y = G.PY0 - 2 - (y0 + i) % 16
        x += round(math.sin(t + k))
        if empty(px, G, x, y) and empty(px, G, x, y + 1) and (y0 + i) % 16 < 13:
            put(px, G, x, y, (255, 246, 200) if (y0 + i) % 16 < 8 else (255, 220, 120)); put(px, G, x, y + 1, (200, 140, 40))
    return im


# ------------------------------------------------------------------------------------------------
# The chat banner: a royal seal. A bar of deep crimson velvet (a faint diamond pattern woven in it) between two
# heavy rails of gold: a bright upper edge, an engraved channel, and a jewel set in the upper rail every 32 columns
# (ruby, sapphire, emerald in turn). The left end carries a small crown, both ends are gold caps with a finial.
# While it idles the jewels glint in turn. Every two seconds a bar of light sweeps along the gold from left to
# right: the rails flash white where it is, every jewel sparkles as it passes, and the velvet under it warms a
# little. Behind the words the velvet stays dark (C.hush).
# ------------------------------------------------------------------------------------------------
CHAT_GEM = {"#": (255, 255, 255), "a": (220, 240, 255), "w": (255, 255, 255), "b": (160, 200, 240), "c": (70, 90, 150)}
CHAT_JEWELS = (RUBY, SAPH, EMER)
CHAT_CROWN = ("#...#...#", "#...#...#", "##.###.##", "#########", "#########", "oooooooo.")    # 9 x 6, o: its band


def chat_geo(Geo):
    return C.bar(Geo, TITLE, "gust")


def chat_frame(G, i):
    im, px = G.canvas()
    step = i - C.CALM
    idle = i % C.CALM
    behind = (lambda x: G.flow_at(x, step)) if step >= 0 else (lambda x: -1)

    def velvet(x, y):
        xt = x - G.TILE0
        g = (y - G.PY0 - 2) / 13
        c = C.lerp((66, 9, 20), (30, 4, 10), g)
        if (xt + y) % 8 == 0 or (xt - y) % 8 == 0: c = C.lerp(c, (104, 20, 34), 0.5)     # the woven diamonds
        return c

    for (x, y) in G.inside:
        xt = x - G.TILE0
        cell = xt % 32
        u = behind(x)
        e = C.strength(u, rise=6, body=14, total=40)         # the bar of light on the gold
        warm = C.strength(u, rise=10, body=24, total=70)
        d = min(x - G.PX0, G.PX1 - x)
        jewel = y in (G.PY0, G.PY0 + 1) and cell in (15, 16) and G.TILE0 <= x < G.R0
        if jewel:
            J = CHAT_JEWELS[(xt // 32) % 3]
            lit = (idle == (xt // 32) % 4) if step < 0 else 0 < u < 26
            c = J[0] if (lit and cell == 15 and y == G.PY0) else J[1] if y == G.PY0 else J[2]
            if lit and y == G.PY0: c = C.lerp(c, (255, 255, 255), 0.45)
        elif y == G.PY0:
            c = C.lerp(G_L if cell % 8 else G_M, (255, 255, 255), 0.85 * e)
        elif y == G.PY0 + 1:
            c = C.lerp(G_D if cell % 4 else G_DD, G_W, 0.8 * e)                           # the engraved channel
        elif y == G.PY1 - 1:
            c = C.lerp(G_M if cell % 4 else G_D, G_W, 0.7 * e)
        elif y == G.PY1:
            c = C.lerp(G_DD if cell % 8 else OUT, G_L, 0.6 * e)
        else:
            c = C.lerp(velvet(x, y), (150, 40, 44), 0.35 * warm)
            c = C.hush(c, 0.045 + 0.03 * warm)
        if d < 3:                                            # the gold caps
            c = C.lerp((G_L, G_M, G_D)[d], (255, 255, 255), 0.7 * e)
            if y in (G.PY0, G.PY1): c = G_DD if d else OUT
        k = C.corner(G, x, y, 2.0)
        if k > 0: px[x, y] = tuple(c) + (int(255 * k),)

    # the crown in the left end: gold, a jewel on each point; it catches the light as the sweep starts
    flash = C.strength(behind(G.PX0) + 14, rise=6, body=16, total=44) if step >= 0 else 0.0
    cx0, cy0 = G.PX0 + 4, G.MID - 2
    for r, row in enumerate(CHAT_CROWN):
        for k, v in enumerate(row):
            if v == ".": continue
            c = G_D if v == "o" else (G_L if k < 4 else G_M if k < 7 else G_D)
            if r == 0 and v == "#": c = (RUBY[1], SAPH[1], EMER[1])[k // 4]
            C.put(px, G, cx0 + k, cy0 + r, C.lerp(c, (255, 255, 255), 0.6 * flash))
    # a finial off each cap: a gold bead on a short stem
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        e = C.strength(behind(x0) + (6 if side < 0 else 0), rise=6, body=14, total=40) if step >= 0 else 0.0
        for k in (1, 2): 
            for y in (G.MID, G.MID + 1): C.put(px, G, x0 + side * k, y, C.lerp(G_M, G_W, e))
        for a in range(-1, 3):
            for b in range(-1, 3):
                if (a in (-1, 2)) and (b in (-1, 2)): continue
                C.put(px, G, x0 + side * 4 + (a if side > 0 else -a + 1) - (1 if side < 0 else 0), G.MID + b,
                      C.lerp(G_L if b <= 0 else G_D, (255, 255, 255), 0.8 * e))
    C.gem7(px, G, CHAT_GEM)
    return im
