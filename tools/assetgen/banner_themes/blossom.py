"""4 blossom: a cherry branch arching over the plate, clusters of blossom, petals falling (approved art t04).

chat_frame: its chat banner (banner_themes/chat.py), drawn for one chat line."""
import math, random
from .lib_a import *
from . import chat as C
TITLE, SLUG = "벚꽃의 정령", "blossom"
GEO = dict(side=14, bot=2)
STATIC = 2
BARK_HI, BARK, BARK_LO = (156, 104, 84), (100, 62, 52), (52, 28, 32)
P_WHITE, P_LIGHT, P_MID, P_DARK, P_DEEP, STAMEN = (255, 242, 248), (255, 204, 224), (244, 152, 192), (214, 98, 150), (156, 50, 100), (255, 228, 140)

def limb(mask, pts, w0, w1):
    n = len(pts) - 1
    for k, (p, q) in enumerate(zip(pts, pts[1:])):
        for x, y in line(*p, *q):
            w = w0 + (w1 - w0) * k / max(1, n)
            r = (w - 1) / 2
            for a in range(-2, 3):
                for b in range(-2, 3):
                    if a * a + b * b <= r * r + 0.3: mask.add((x + a, y + b))

def bark(px, G, mask):
    for x, y in mask:
        up, dn = (x, y - 1) in mask, (x, y + 1) in mask
        c = BARK_HI if not up and dn else BARK_LO if not dn else BARK
        if not up and not dn: c = BARK
        put(px, G, x, y, c)

def flower(px, G, x, y, tone):
    """3x3 plus flower; tone 0 = front/bright, 1 = back/darker"""
    x, y = round(x), round(y)
    if any((x + a, y + b) in G.text_near for a in (-1, 0, 1) for b in (-1, 0, 1)): return
    pet = (P_WHITE, P_LIGHT, P_MID)[tone] if tone < 3 else P_DARK
    pet2 = (P_LIGHT, P_MID, P_DARK)[min(tone, 2)]
    put(px, G, x, y - 1, pet); put(px, G, x - 1, y, pet); put(px, G, x + 1, y, pet2); put(px, G, x, y + 1, pet2)
    put(px, G, x, y, P_DEEP if tone else STAMEN)

def cluster(px, G, cx, cy, n, seed, spread=(3.5, 2.5)):
    r = random.Random(seed)
    fl = [(cx + r.uniform(-spread[0], spread[0]), cy + r.uniform(-spread[1], spread[1])) for _ in range(n)]
    for x, y in fl:                      # back layer
        flower(px, G, x + 1, y + 1, 2)
    for k, (x, y) in enumerate(sorted(fl, key=lambda p: p[1])):
        flower(px, G, x, y, 0 if k % 3 else 1)
    if r.random() < 0.8:
        put(px, G, round(cx + spread[0] + 1), round(cy - 1), P_DARK)   # a bud

def petal(px, G, x, y, ph):
    x, y = round(x), round(y)
    if (x, y) in G.text_near or (x, y) in G.inside or (x + 1, y + 1) in G.inside: return
    if ph % 4 == 0: put(px, G, x, y, P_LIGHT); put(px, G, x + 1, y, P_MID)
    elif ph % 4 == 1: put(px, G, x, y, P_LIGHT); put(px, G, x + 1, y + 1, P_DARK)
    elif ph % 4 == 2: put(px, G, x, y, P_MID); put(px, G, x, y + 1, P_DARK)
    else: put(px, G, x, y, P_WHITE); put(px, G, x - 1, y, P_LIGHT)

def frame(G, i):
    t = 2 * math.pi * i / N
    im, px = G.canvas()
    X0, Y0 = G.PX0, G.PY0
    sway = lambda k: round(0.8 * math.sin(t + k))
    CX = G.CX
    MAIN = [(53, 3), (50, -4), (46, -10), (39, -13), (30, -12), (20, -15), (11, -13), (5, -9), (2, -6)]
    UP = [(31, -12), (29, -16), (25, -18)]
    def side_pts(P, side, swk):
        out = []
        for n, (o, dy) in enumerate(P):
            sw_ = sway(swk + (side > 0) * 2) if n >= len(P) - 2 else 0
            out.append((CX + side * o + sw_, Y0 + dy))
        return out
    branches = [(side_pts(MAIN, s_, 0), side_pts(UP, s_, 1), s_) for s_ in (-1, 1)]
    pr = random.Random(21); petals = []
    for k in range(16):
        side = -1 if k % 2 else 1
        xs0 = CX + side * pr.uniform(0, G.PW / 2 + 2); y0 = Y0 - pr.randint(12, 19); off = pr.randint(0, 15); spd = pr.choice((0.6, 0.9))
        p = (i + off) % 16
        if p > 13: continue
        y = y0 + p * 1.25
        x = xs0 - p * spd + 1.4 * math.sin(t * 2 + k)
        petals.append((x, y, (i + k) % 4))
    for x, y, ph in petals[:8]: petal(px, G, x, y, ph)
    glow(px, G, (255, 140, 190), 90)
    mask = set()
    for m_, u_, s_ in branches: limb(mask, m_, 4.4, 1.2); limb(mask, u_, 2.2, 1)
    bark(px, G, mask)
    def bev(x, y):
        p = 0.5 + 0.5 * math.sin(x * 0.15 + y * 0.3 - t)
        return lerp((190, 80, 124), (255, 176, 206), p) if y < G.MID else lerp((140, 50, 92), (214, 112, 156), p)
    plate(px, G, lambda x, y, g: lerp((96, 38, 62), (36, 12, 26), g), (255, 220, 232), (196, 104, 146), bevel=bev)
    rivets(px, G, (255, 226, 236), (196, 110, 150))
    # blossoms
    for m_, u_, s_ in branches:
        sd = 0 if s_ < 0 else 20
        for (x, y), n, k in ((m_[2], 5, 1), (m_[3], 6, 2), (m_[4], 4, 3), (m_[5], 6, 4), (m_[6], 5, 5), (m_[8], 6, 6), (u_[2], 6, 7)):
            cluster(px, G, x, y - 1, n, sd + k)
        for d in range(2):
            x, y = m_[8]
            flower(px, G, x + s_ * (1 - 3 * d), y + 4 + d * 2, 1 if d else 0)
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for k in range(1, 4): put(px, G, x0 + side * k, G.MID, BARK_HI if k < 3 else BARK)
        cx = x0 + side * 5
        for a, b in DIRS4: put(px, G, cx + a, G.MID + b, P_LIGHT if a + b < 0 else P_MID)
        for a, b in ((1, 1), (-1, -1), (1, -1), (-1, 1)): put(px, G, cx + a, G.MID + b, P_DARK)
        put(px, G, cx, G.MID, STAMEN)
    # the gem (recorded in the gem pass: it becomes a glyph of its own)
    cx, cy = G.DIV, G.MID
    for a, b, c in ((0, -2, P_LIGHT), (-1, -1, P_WHITE), (0, -1, P_LIGHT), (1, -1, P_LIGHT), (-2, 0, P_LIGHT), (-1, 0, P_MID), (0, 0, STAMEN),
                    (1, 0, P_MID), (2, 0, P_DARK), (-1, 1, P_MID), (0, 1, P_DEEP), (1, 1, P_DARK), (-1, 2, P_DARK), (1, 2, P_DEEP)):
        gem_put(px, G, cx + a, cy + b, c)
    texts(px, G, i,
          ramp((0, (255, 255, 255)), (0.25, (255, 222, 236)), (0.7, (246, 156, 196)), (1, (206, 100, 152))),
          ramp((0, (255, 255, 255)), (0.4, (255, 196, 220)), (1, (240, 120, 172))),
          (44, 8, 26))
    for x, y, ph in petals[8:]: petal(px, G, x, y, ph)
    return im


# ------------------------------------------------------------------------------------------------
# The chat banner: a spring evening. A bar of dusk (a deep plum sky, lighter low down) hung in a frame that is
# nothing but the season. Along its top lies a cherry bough, two rows thick (lit bark above, dark below), its back
# rising and falling, a knot or a stub of twig standing up from it here and there; where it buds, blossom sits on
# it and hangs a row or two over the bar (a full cluster every 32 columns, a smaller one between). Along its bottom
# runs a red silk cord, tied in a gold knot every 16 columns and sagging between the knots, so that the bar's lower
# edge is a row of swags. A post of bark closes each end. Off the left end opens one great cherry flower, as high
# as the line, with a bud beside it; off the right end the bough runs out into a budding twig, and a tassel hangs
# from it.
# While it idles a petal or two turns in the air. Every two seconds a breath of wind runs along the banner: the
# clusters shake, petals come off them and drift right and down across the bar (pale and small) until they are
# gone, and when the wind reaches the end the tassel swings.
# ------------------------------------------------------------------------------------------------
CHAT_GEM = {"#": (255, 242, 248), "a": (255, 204, 224), "w": (255, 255, 255), "b": (244, 152, 192), "c": (156, 50, 100)}
# a cluster: its flowers' middles (column in its cell of 16, row from the bar's top); a flower is a cross of five
# pixels. The full one stands in the first cell of every 32 columns, the small one in the second.
CHAT_CLUSTERS = (((3, 1), (7, 0), (11, 1), (5, 3), (9, 2)), ((4, 1), (9, 0), (12, 2)))
CHAT_BUDS = (((13, 0),), ((1, 0), (7, 2)))                              # buds beside them: column, row
CHAT_RISE = ((6, 11), (3, 8))                                           # where the bough's back is a row higher, per cell
CHAT_PETALS = ((8, 3, 0.9), (20, 6, 0.55), (31, 2, 0.8), (44, 8, 0.6), (56, 4, 0.75), (70, 7, 0.5), (84, 3, 0.7))   # columns behind the front, row it starts on, how fast it sinks
CORD_L, CORD, CORD_D = (244, 112, 120), (206, 52, 60), (146, 30, 46)
GILT_L, GILT, GILT_D = (255, 244, 190), (255, 228, 140), (206, 150, 64)


def chat_geo(Geo):
    return C.bar(Geo, TITLE, "gust")


def _phase(G, x):
    """the column of the 16-column rhythm a plate column is on: the tile's own up to the right end, which starts a
    cell of its own (the plate grows by whole cells, so the rhythm runs on into it)"""
    return x - G.TILE0 if x < G.R0 else x - G.R0


def _bloom(px, G, cx, cy, R):
    """a cherry flower of five notched petals about (cx, cy), both between two pixels: pale where the light falls
    (upper left), deeper at the rims and the heart, gold stamens"""
    for y in range(G.PY0, G.PY1 + 1):
        for x in range(int(cx - R - 1), int(cx + R + 2)):
            if not 0 <= x < G.W or (x, y) in G.inside: continue
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            r = math.hypot(dx, dy)
            off = (math.degrees(math.atan2(dy, dx)) + 90 + 36) % 72 - 36         # degrees from the nearest petal's axis
            rim = R * (0.46 + 0.54 * max(0.0, math.cos(math.radians(off * 2.5))) ** 0.55)
            if abs(off) < 8: rim -= 1.5 * (1 - abs(off) / 8)                     # the notch in a petal's tip
            if r > rim: continue
            lit = dx + dy < 1
            if r < 1.2: c = STAMEN
            elif r < 2.7: c = P_DEEP if abs(off) > 14 else P_DARK
            elif r < 4.2 and abs(off) < 5: c = STAMEN if r > 3.2 else P_DARK     # a stamen along each petal
            elif rim - r < 1.0: c = P_MID if lit else P_DARK
            elif abs(off) > 24: c = P_MID if lit else P_DARK                     # the fold between two petals
            else: c = P_WHITE if lit and r > 4.4 else P_LIGHT if lit or r > 5 else P_MID
            px[x, y] = tuple(c) + (255,)


def chat_frame(G, i):
    im, px = G.canvas()
    step = i - C.CALM
    idle = i % C.CALM
    behind = (lambda x: G.flow_at(x, step)) if step >= 0 else (lambda x: -1)

    for (x, y) in G.inside:
        xt = x - G.TILE0
        p = _phase(G, x)
        col, kind = p % 16, (p // 16) % 2
        d = min(x - G.PX0, G.PX1 - x)
        ry = y - G.PY0
        g = min(1.0, max(0.0, (ry - 2) / 13))
        lo, hi = CHAT_RISE[kind]
        top = 0 if lo <= col <= hi else 1                                # the bough's upper row here
        knot = col < 3 or G.PX1 - x < 3                                  # a gold knot in the cord
        low = 5 <= col <= 12 and not knot                                # where the cord sags a row
        c = None
        if ry in (top, top + 1):                                         # the bough: lit bark over dark
            n = C.noise(xt, ry, 4, 4, 64, 16, 801)
            c = C.lerp(BARK_HI, BARK, 0.7 * n) if ry == top else C.lerp(BARK, BARK_LO, 0.7 * n)
            if col in (lo - 1, hi + 1) and ry == 1: c = BARK_LO          # the shadow where its back steps
        elif ry == 0:                                                    # a knot or a stub of twig on its back; else air
            if col == (14 if kind else 1): c = BARK
            elif col == (2 if kind == 0 else 13): c = BARK_HI
            else: continue
        elif knot and ry >= 16:
            k = col if col < 3 else 2 - (G.PX1 - x)
            c = ((GILT, GILT_L, GILT_D), (GILT_D, GILT, (150, 100, 40)))[ry - 16][k]
        elif ry == 16 and not low:
            c = CORD_L if col % 2 else CORD
        elif ry == 17:
            if not low: continue                                         # air under the cord where it is drawn up
            c = CORD if col % 2 else CORD_D
            if col in (5, 12): c = CORD_L
        else:
            c = C.hush(C.lerp((34, 12, 34), (62, 22, 46), g ** 2), 0.045)
            if d < 2 and 2 < ry < 16: c = (BARK if ry % 5 else BARK_HI) if d == 0 else BARK_LO   # the posts
        px[x, y] = tuple(c) + (255,)

    def flower(x, y, tone, dim=False):
        """a blossom: four petals round a golden middle (what of it hangs over the words' rows is dimmed)"""
        for a_, b_ in ((0, -1), (-1, 0), (1, 0), (0, 1)):
            c = (P_WHITE, P_LIGHT, P_MID)[(tone + a_ + 2 * b_) % 3]
            yy = y + b_
            if G.PY0 <= yy <= G.PY1: C.put(px, G, x + a_, yy, C.hush(c, 0.30) if dim and yy >= G.PY0 + 2 else c)
        if G.PY0 <= y <= G.PY1: C.put(px, G, x, y, C.hush(STAMEN, 0.30) if dim and y >= G.PY0 + 2 else STAMEN)

    # the clusters of blossom on the bough: a full one and a small one to every 32 columns, shaking as the wind passes
    for x0 in range(G.TILE0, G.R0 - 15, 16):
        kth = ((x0 - G.TILE0) // 16) % 2
        u = behind(x0 + 8)
        shake = (1 if (step + kth) % 2 else -1) if step >= 0 and 0 < u < 40 else 0
        for col, row in CHAT_BUDS[kth]:
            C.put(px, G, x0 + col, G.PY0 + row, P_DARK if row < 2 else C.hush(P_MID, 0.30))
        for n, (col, row) in enumerate(CHAT_CLUSTERS[kth]):
            flower(x0 + col + shake, G.PY0 + row, n + kth, dim=True)

    # petals: one turning in the air while it idles, a drift of them behind the wind's front
    if step < 0:
        for x0 in range(G.TILE0, G.R0 - 15, 64):
            for col, row in ((22, 7), (50, 11)):
                if x0 + col >= G.R0 - 2: continue
                x = x0 + col + (idle % 2)
                C.blend(px, G, x, G.PY0 + row + idle // 2, P_LIGHT, 150)
                C.blend(px, G, x + (1 if idle in (0, 3) else -1), G.PY0 + row + idle // 2, P_MID, 110)
    else:
        for x in range(G.PX0 + 4, G.PX1 - 3):
            u = behind(x)
            for at, row, sink in CHAT_PETALS:
                if u != at: continue
                y = G.PY0 + row + int(sink * step)
                if not G.PY0 + 3 <= y <= G.PY1 - 3: continue
                C.blend(px, G, x, y, P_LIGHT, 190)
                C.blend(px, G, x - 1, y - (1 if (step + at) % 2 else 0), P_MID, 150)

    # the left end: the bough's butt, a bud, and the great flower before them
    for a in (1, 2, 3):
        C.put(px, G, G.PX0 - a, G.PY0 + 1, BARK_HI); C.put(px, G, G.PX0 - a, G.PY0 + 2, BARK_LO)
    for a, b, c in ((-21, 14, P_DARK), (-20, 14, P_MID), (-21, 15, P_DEEP), (-20, 15, P_DARK), (-19, 16, BARK), (-18, 16, BARK_HI), (-17, 17, BARK),
                    (-2, 14, P_MID), (-1, 14, P_LIGHT), (-2, 15, P_DARK), (-1, 15, P_MID)):
        C.put(px, G, G.PX0 + a, G.PY0 + b, c)
    _bloom(px, G, G.PX0 - 10.0, G.PY0 + 9.0, 8.1)
    # the right end: the bough runs out into a twig (two buds, a flower at its tip), and a tassel hangs from it
    for k in range(1, 16):
        y = G.PY0 + (1 if k < 11 else 0)
        C.put(px, G, G.PX1 + k, y, BARK_HI if k % 4 else BARK)
        if k < 11: C.put(px, G, G.PX1 + k, y + 1, BARK if k < 6 else BARK_LO)
    for a, b, c in ((3, 0, P_DARK), (4, 0, P_MID), (10, 0, P_DARK), (15, 2, BARK), (15, 3, BARK_LO)):
        C.put(px, G, G.PX1 + a, G.PY0 + b, c)
    flower(G.PX1 + 13, G.PY0 + 1, 1)
    flower(G.PX1 + 18, G.PY0 + 1, 0)
    flower(G.PX1 + 16, G.PY0 + 4, 2)
    flower(G.PX1 + 20, G.PY0 + 5, 1)
    u = behind(G.PX1) if step >= 0 else -1
    swing = 2 if 0 < u < 14 else 1 if 14 <= u < 28 else 0                # the wind has just come by
    hx = G.PX1 + 7
    for ry in range(2, 17):
        x = hx + (swing * (ry - 3)) // 12
        if ry < 6: C.put(px, G, x, G.PY0 + ry, GILT_D)                   # the thread
        elif ry < 9:                                                     # the knot: a lozenge of cord, gold at its heart
            for a in range(-(1 if ry == 7 else 0), (1 if ry == 7 else 0) + 1): C.put(px, G, x + a, G.PY0 + ry, GILT if a == 0 and ry == 7 else CORD)
        elif ry == 9:
            for a in (-1, 0, 1): C.put(px, G, x + a, G.PY0 + ry, (GILT_L, GILT, GILT_D)[a + 1])
        else:                                                            # the tassel's threads
            for a in (-1, 0, 1):
                if ry == 16 and a == 0: continue
                C.put(px, G, x + a, G.PY0 + ry, (CORD_L, CORD, CORD_D)[a + 1])
    C.gem7(px, G, CHAT_GEM)
    return im
