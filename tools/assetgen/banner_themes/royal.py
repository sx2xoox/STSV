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
# The chat banner: a royal standard. A bar of deep crimson velvet (a faint diamond pattern woven in it) hung between
# two mouldings of worked gold, and nothing about its outline is a straight line: along the top runs a cresting of
# gold points (one every 8 columns, as on a crown's rim) with a jewel in a claw setting every 32 (ruby, sapphire),
# under it a beaded band; along the bottom a band and a bullion fringe. A line of gold stitching holds the velvet to
# each band (dark gold: it lies on the words' rows). Each end is a fluted gold cap; before the left one a jewelled
# crown rests on its cushion, as large as the line is high, the right one carries a fleur-de-lis finial on its side.
# While it idles the jewels glint in turn. Every two seconds a bar of light sweeps along the gold from left to
# right: the mouldings flash white where it is, every jewel sparkles as it passes, the crown and the finial catch
# it, and the velvet under it warms a little. Behind the words the velvet stays dark (C.hush).
# ------------------------------------------------------------------------------------------------
CHAT_GEM = {"#": (255, 255, 255), "a": (220, 240, 255), "w": (255, 255, 255), "b": (160, 200, 240), "c": (70, 90, 150)}
CHAT_JEWELS = (RUBY, SAPH)
CHAT_PAL = {"W": G_W, "L": G_L, "M": G_M, "D": G_D, "d": G_DD, "o": OUT, "p": (255, 255, 255),
            "r": RUBY[0], "R": RUBY[1], "q": RUBY[2], "s": SAPH[0], "S": SAPH[1], "z": SAPH[2],
            "e": EMER[0], "E": EMER[1], "g": EMER[2], "v": (150, 26, 44), "V": (92, 12, 26)}
# the left end, 18 rows; its column 22 is the plate's first column. A crown (five points with pearls, a jewelled band,
# its velvet cap showing between the arches) on a tasselled cushion, before a fluted cap.
CHAT_LEFT = (
    "..........p.............oMo",
    ".........pWp...........oWLDo",
    "....p....LWL....p.....oLLMDdo",
    "...pWp...LML...pWp....oWLMDdo",
    "p..LWL..oLMLo..LWL..p.oWLMDdo",
    "Wo.LMLo.LLMLL.oLML.oWooLLMDdo",
    "LLoLMLLoLvMvLoLLMLoLLooWLMDdo",
    "LMLLMLvLLVMVLLvLMLLMLooWLMDdo",
    "LMMLMLVVLLMLLVVLMLMMLooLLMDdo",
    "WWLLLLLLLrRLLLLLLLWWWooWLMDdo",
    "LsSLMeELrRRqLMsSLMeEDooWLMDdo",
    "MzSMMgEMRRqqMMzSMMgEDooLLMDdo",
    "dDDDDDDDDDDDDDDDDDDDdooWLMDdo",
    "ovvvvvvvvvvvvvvvvvvvvooWLMDdo",
    "LvVVVVVVVVVVVVVVVVVvLooLLMDdo",
    "DoVVVVVVVVVVVVVVVVVoD.oLLMDdo",
    "d.ooooooooooooooooo.d..oMDdo",
    "........................odo",
)
# the right end, 18 rows; its column 4 is the plate's last column. The cap again, then a fleur-de-lis lying on its
# side (its middle petal the spear point, the side petals curling back) on a collar.
CHAT_RIGHT = (
    "oMo........................",
    "oDMWo.........LL...........",
    "odDMLLo......LWMD..........",
    "odDMLWo.....LWMD...........",
    "odDMLWo....oLMD............",
    "odDMLLooo..LMDo...LWo......",
    "odDMLWoLLo.LMD..oLWMLo.....",
    "odDMLWoLMLoLMDooLWMMMLLo...",
    "odDMLLoWMMLLMLLLWMMMMMMLLp.",
    "odDMLWoLMMDDMDDDMMMMMDDDDo.",
    "odDMLWoDDDoDMDooDMMDDDo....",
    "odDMLLoDDo.DMD..oDMDo......",
    "odDMLWooo..DMDo...Do.......",
    "odDMLWo....oDMD............",
    "odDMLLo.....DMMD...........",
    "odDMLLo......DMMD..........",
    "oDMDo.........DD...........",
    "odo........................",
)
CHAT_GOLD = "WLMDd"


def chat_geo(Geo):
    return C.bar(Geo, TITLE, "gust")


def _phase(G, x):
    """the column of the 16-column rhythm a plate column is on: the tile's own up to the right end, which starts a
    cell of its own (the plate grows by whole cells, so the rhythm runs on into it)"""
    return x - G.TILE0 if x < G.R0 else x - G.R0


def _stamp(px, G, x0, rows, pal, tint=None):
    """a sprite of 18 rows with its first column at x0; '.' leaves what is there, '_' clears it"""
    for r, row in enumerate(rows):
        for k, v in enumerate(row):
            if v == ".": continue
            x, y = x0 + k, G.PY0 + r
            if not (0 <= x < G.W and 0 <= y < G.H): continue
            if v == "_":
                px[x, y] = (0, 0, 0, 0)
                continue
            c = pal[v]
            if tint: c = tint(v, c, x, y)
            px[x, y] = tuple(c[:3]) + (255,)


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
        xt = _phase(G, x)
        c8, c32 = xt % 8, xt % 32
        u = behind(x)
        e = C.strength(u, rise=6, body=14, total=40)         # the bar of light on the gold
        warm = C.strength(u, rise=10, body=24, total=70)
        ry = y - G.PY0
        # a jewel: a lozenge four columns wide whose lower point hangs into the velvet (dark there)
        setting = 10 <= c32 <= 13 and x < G.R0
        jewel = setting and (ry == 1 or (ry in (0, 2) and c32 in (11, 12)))
        claw = setting and ry == 0 and c32 in (10, 13)
        if jewel:
            J = CHAT_JEWELS[(xt // 32) % 2]
            lit = (idle == (xt // 32) % 4) if step < 0 else 0 < u < 26
            c = J[0] if (c32 == 11 and ry < 2) else J[1] if (c32 < 12 or ry == 0) else J[2]
            if lit: c = C.lerp(c, (255, 255, 255), 0.6 if (c32 == 11 and ry == 0) else 0.25)
            if ry == 2: c = C.lerp(J[2], (0, 0, 0), 0.45)
        elif claw:
            c = C.lerp(G_L, (255, 255, 255), 0.8 * e)
        elif ry == 0:                                        # the cresting: a point every 8 columns
            if c8 in (3, 4): c = C.lerp(G_W if c8 == 3 else G_L, (255, 255, 255), 0.85 * e)
            elif c8 in (2, 5): c = C.lerp(G_D, G_W, 0.7 * e)
            else: continue                                   # air between the points
        elif ry == 1:                                        # the beaded band
            c = C.lerp(G_L if c8 % 4 else G_D, (255, 255, 255), 0.85 * e)
            if c8 in (3, 4): c = C.lerp(G_M, (255, 255, 255), 0.85 * e)
        elif ry == 16:
            c = C.lerp(G_M if c8 % 4 != 1 else G_DD, G_W, 0.75 * e)
        elif ry == 17:                                       # the fringe: a thread every other column
            if xt % 2: continue
            c = C.lerp(G_D if c8 else G_L, G_W, 0.6 * e)
        else:
            c = C.lerp(velvet(x, y), (150, 40, 44), 0.35 * warm)
            c = C.hush(c, 0.045 + 0.03 * warm)
            if ry in (2, 15):                                # the band's shadow on the velvet, and the stitching
                c = C.lerp(c, (10, 0, 2), 0.55)
                if xt % 4 == 1: c = C.lerp(G_DD, (150, 96, 30), 0.5 * e)
        px[x, y] = tuple(c) + (255,)

    # the ends: gold catches the sweep as it starts (left) and as it arrives (right)
    def flash(x0, lead):
        return C.strength(behind(x0) + lead, rise=6, body=16, total=44) if step >= 0 else 0.0
    fl, fr = flash(G.PX0, 14), flash(G.PX1, 0)

    def gilt(f):
        return lambda v, c, x, y: C.lerp(c, (255, 255, 255), (0.6 if v in CHAT_GOLD else 0.3 if v != "o" else 0.0) * f)
    _stamp(px, G, G.PX0 - 22, CHAT_LEFT, CHAT_PAL, gilt(fl))
    _stamp(px, G, G.PX1 - 4, CHAT_RIGHT, CHAT_PAL, gilt(fr))
    # the crown's jewels and the finial's point glint in turn while it idles
    if step < 0:
        spots = ((G.PX0 - 22 + 10, G.PY0 + 9), (G.PX0 - 22 + 2, G.PY0 + 10), (G.PX0 - 22 + 15, G.PY0 + 10), (G.PX1 - 4 + 25, G.PY0 + 8))
        x, y = spots[idle]
        C.put(px, G, x, y, (255, 255, 255))
    C.gem7(px, G, CHAT_GEM)
    return im
