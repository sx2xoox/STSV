"""11 secret_agent (비밀 요원): the banner of the title 비밀 요원, the 마을버스 secret mission's reward (economy
titles.yml secret_agent). Not sold: the bus module unlocks it (unlock kind "banner", id "secret_agent").

Black and gold, sparkly: a plate of deep matte black with a faint diagonal pinstripe, framed by a fine gold rail
(bright on top, deeper below) with a thin dark-gold line inside it; a cut gold diamond stud at each end with a short
gold bar behind it like a tie clip; gold sparkles (four-point stars) twinkle in turn along the rails and just off
them, and once a loop a narrow sweep of light runs along the gold. The gem: a black onyx diamond in a gold setting.

chat_frame: its chat banner (banner_themes/chat.py), drawn for one chat line: the same bar of black between two gold
rails, the sweep and the sparkles on the rails (the 14 rows of the words stay dark: C.hush), gold studs on the ends.
"""
import math
from .lib_a import *
from . import chat as C

TITLE, SLUG = "비밀 요원", "secret_agent"
GEO = dict(side=16, top=14, bot=6)
STATIC = 0

G_W, G_L, G_M, G_D, G_DD = (255, 248, 214), (255, 222, 128), (232, 176, 60), (170, 116, 30), (96, 62, 16)
BLACK_HI, BLACK, BLACK_LO = (34, 34, 42), (16, 16, 22), (6, 6, 10)
SPARK = (255, 255, 240)


def _sparkle(px, G, x, y, size, keep=None):
    """a four-point gold star: a white core, gold arms fading out (size 0: a single bright point)"""
    keep = keep or (lambda q: True)
    if keep((x, y)): put(px, G, x, y, SPARK)
    for k in range(1, size + 1):
        c, a = (G_L, 255) if k < size else (G_M, 190)
        for dx, dy in DIRS4:
            q = (x + dx * k, y + dy * k)
            if keep(q): blend(px, G, q[0], q[1], c, a)


def _sweep(x, y, i, period=16 * 6):
    """0 .. 1: the sweep of light over the gold at column x (it leans with the rows), once a loop"""
    s = (x + 0.6 * y - (i * period / N)) % period
    return max(0.0, 1 - abs(s - 3) / 3)


def frame(G, i):
    im, px = G.canvas()

    def fill(x, y, g):
        c = lerp(BLACK_HI, BLACK_LO, g)
        if (x + y) % 8 == 0: c = lerp(c, (52, 46, 34), 0.5)            # the pinstripe, warm
        return c

    def trim(x, y):
        c = G_L if y < G.MID else G_D
        if y == G.PY0: c = G_W if (x % 16) in (3, 4) else G_L
        return lerp(c, (255, 255, 255), 0.8 * _sweep(x, y, i))

    plate(px, G, fill, None, None, bevel=lambda x, y: lerp(G_DD, G_M, 0.6 * _sweep(x, y, i)), trim=trim)
    glow(px, G, (120, 86, 20), 90, ring=1)
    # the ends: a gold diamond stud and a short bar behind it (a tie clip)
    for side in (-1, 1):
        x0 = G.PX0 - 3 if side < 0 else G.PX1 + 3
        for r in range(-3, 4):
            for k in range(-(3 - abs(r)), 4 - abs(r)):
                d = abs(r) + abs(k)
                c = G_W if d == 0 else G_L if (r < 0 or k * side < 0) and d < 3 else G_M if d < 3 else G_D
                put(px, G, x0 + k, G.MID + r, c)
        for k in range(4, 9):
            put(px, G, x0 + side * k, G.MID - 1, G_L if k % 2 else G_M)
            put(px, G, x0 + side * k, G.MID, G_D)
        ph = (i + (0 if side < 0 else 8)) % N
        if ph < 4: _sparkle(px, G, x0, G.MID - 1, (1, 2, 2, 1)[ph])
    # sparkles along the rails, in turn (positions repeat every 16 columns, so the tile is seamless)
    for x in range(G.PX0 + 8, G.PX1 - 7):
        k = x % 16
        if k not in (5, 13): continue
        top = k == 5
        ph = (i + (x // 16) * 5 + (0 if top else 8)) % N
        if ph >= 5: continue
        y = G.PY0 - 1 if top else G.PY1 + 1
        _sparkle(px, G, x, y, (1, 2, 3, 2, 1)[ph], keep=lambda q: q not in G.inside)
    # the gem: an onyx diamond in a gold setting
    gem(px, G, G.DIV, G.MID, (255, 255, 240), G_L, G_M, (40, 36, 30), (10, 10, 14), top=G_W)
    texts(px, G, i, None, None, None)
    return im


# ------------------------------------------------------------------------------------------------
# The chat banner (one chat line: 18 rows of half pixels)
# ------------------------------------------------------------------------------------------------

def chat_geo(Geo):
    # a plain bar (its ends shaped here), a loop; it grows by whole cells of 16 columns
    return Geo(TITLE, tile=C.TILE, pointed=False, grow=16)


def _cell(G, x):
    """a column's place in the 64-column repeat, counted in the right end from that part's own start"""
    return (x - (G.R0 if x >= G.R0 else G.TILE0)) % 64


def chat_frame(G, i):
    im, px = G.canvas()

    def sweep(x, y):
        s = (_cell(G, x) + 0.5 * (y - G.PY0) - 4 * i) % 64
        return max(0.0, 1 - abs(s - 4) / 4)

    for (x, y) in G.inside:
        d = min(x - G.PX0, G.PX1 - x)                  # columns from the nearer end
        ry = y - G.PY0
        e = sweep(x, y)
        if d < 3 and ry in (0, 17) or d < 2 and ry in (1, 16) or d < 1 and ry in (2, 15):
            continue                                   # the cut corners
        if ry == 0 or (d == 0):
            c = C.lerp(G_L, (255, 255, 255), 0.75 * e)
        elif ry == 17:
            c = C.lerp(G_D, (255, 240, 200), 0.6 * e)
        elif ry == 1 or d == 1:
            c = C.lerp(G_M, (255, 255, 255), 0.6 * e)
        elif ry == 16:
            c = C.lerp(G_DD, G_M, 0.6 * e)
        elif ry in (2, 15) or d == 2:
            c = C.lerp((40, 28, 8), G_D, 0.4 * e)      # the dark-gold line inside the rail
        else:
            g = (ry - 3) / 11
            c = C.lerp(BLACK_HI, BLACK_LO, g)
            if (_cell(G, x) + ry) % 8 == 0: c = C.lerp(c, (52, 46, 34), 0.5)
            c = C.hush(c, 0.04)
        px[x, y] = tuple(c) + (255,)

    # sparkles on the rails: four-point stars twinkling in turn, every 16 columns, top and bottom
    for x in range(G.PX0 + 6, G.PX1 - 5):
        k = _cell(G, x)
        if k % 16 not in (5, 13): continue
        top = k % 16 == 5
        ph = (i + k // 4 + (0 if top else 8)) % N
        if ph >= 5: continue
        y = G.PY0 if top else G.PY1
        size = (0, 1, 2, 1, 0)[ph]
        C.put(px, G, x, y, SPARK)
        for kk in range(1, size + 1):
            for xx in (x - kk, x + kk):
                if (xx, y) in G.inside: C.put(px, G, xx, y, G_W if kk < size else G_L)
            yy = y + kk if top else y - kk
            if (x, yy) in G.inside and (yy - G.PY0) in (1, 2, 15, 16):
                C.put(px, G, x, yy, G_L)

    # the ends: a gold diamond stud just inside each end, glinting in turn
    for side in (-1, 1):
        cx = G.PX0 + 7 if side < 0 else G.PX1 - 7
        lit = (i // 4) % 2 == (0 if side < 0 else 1)
        for r in range(-4, 5):
            for k in range(-(4 - abs(r)), 5 - abs(r)):
                dd = abs(r) + abs(k)
                c = G_W if dd == 0 else G_L if (r < 0 and dd < 4) else G_M if dd < 4 else G_D
                if lit and dd <= 1: c = SPARK
                C.put(px, G, cx + k, G.MID + r, c)
    # the gem: onyx in gold
    C.gem7(px, G, {"#": G_L, "a": (52, 48, 44), "w": SPARK, "b": (22, 20, 20), "c": G_D})
    return im
