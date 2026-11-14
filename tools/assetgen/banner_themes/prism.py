"""21 prism: translucent glass plate, spectral bands sliding through it, specular glints on the bevel, rainbow rays
fanning out of both points, crisp sparkles (approved art t21)."""
import math, random
from .lib_b import *

TITLE = "빛의 굴절"
SIL_L = (250, 252, 255); SIL = (206, 214, 228); SIL_D = (150, 160, 182)
SPECTRUM = [(255, 120, 130), (255, 184, 110), (255, 240, 130), (130, 236, 160), (120, 200, 255), (180, 150, 255)]


def draw(G, i):
    t = TAU * (i % N) / N
    ph = i % N
    cv = Cv(G)
    # ---- rainbow rays fanning out of both points (behind the plate)
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for k, col in enumerate(SPECTRUM):
            ang = (k - 2.5) * 0.26
            L = 11 + 1.8 * math.sin(t + k * 0.7) - abs(k - 2.5) * 0.8
            dx, dy = side * math.cos(ang), math.sin(ang)
            pts = line_pts(x0 + side * 2, G.MID + dy * 1, x0 + side * (2 + L * math.cos(ang)), G.MID + L * dy)
            n = len(pts)
            for j, (x, y) in enumerate(pts):
                f = j / max(1, n - 1)
                # a brighter pulse running out along the rays
                pulse = math.exp(-((f - (ph / N)) * 6) ** 2) + math.exp(-((f - (ph / N) + 1) * 6) ** 2)
                a = int(255 * min(1.0, (1 - f) / 0.35) * (0.7 + 0.3 * pulse))
                c = lerp(col, (255, 255, 255), 0.35 * pulse)
                cv.blend(x, y, c, a)
        cv.put(x0 + side * 1, G.MID, (255, 255, 255))
    # ---- glass plate: partial alpha, spectral bands slide through it
    for (x, y) in G.inside:
        g = (y - G.PY0) / 16
        if (x, y) in G.edge:
            c = SIL_L if y < G.MID - 3 else SIL if y <= G.MID + 3 else SIL_D
            cv.put(x, y, c); continue
        # refraction streaks: narrow diagonal spectra drifting through clear glass
        sx = (x - G.PX0) + (y - G.PY0) * 0.8 - 30 * ph / N
        q = sx % 30
        band = hsv(0.95 - q / 10 * 0.82, 0.5, 1.0) if q < 10 else None
        bandA = math.sin(math.pi * (q + 0.5) / 11) if q < 10 else 0
        if (x, y) in G.bevel:
            if y < G.MID:
                d = abs(x - (G.PX0 - 10 + (G.PW + 20) * ph / N))
                cv.put(x, y, (255, 255, 255) if d < 5 else SIL_L, 255 if d < 5 else 210)
            else:
                cv.put(x, y, band or (220, 230, 255), 170)
            continue
        if band:
            cv.put(x, y, lerp((196, 212, 255), band, min(1, bandA * 1.4)), int(60 + 100 * bandA - 20 * g))
        else:
            cv.put(x, y, (196, 212, 255), 52 - int(14 * g))
    # inner refraction line: a thin pastel caustic under the top bevel
    for x in range(G.PX0 + 7, G.PX1 - 6):
        y = G.PY0 + 2
        if (x, y) in G.text_near: continue
        cv.put(x, y, (255, 255, 255), 120)
    gem(cv, G.DIV, G.MID, (255, 255, 255), (230, 236, 255), (190, 200, 255), (150, 160, 230), (110, 110, 190), top=(255, 255, 255))
    texts(cv, G, i, ramp((0, (255, 255, 255)), (0.35, (240, 244, 255)), (0.75, (200, 206, 232)), (1, (160, 166, 204))),
          ramp((0, (255, 255, 255)), (0.5, (236, 230, 255)), (1, (190, 176, 250))), (28, 28, 52), shine_col=(255, 255, 255))
    # ---- sparkles at the glass corners and around
    spots = [(G.PX0 + 6, G.PY0, 0), (G.PX1 - 6, G.PY1, 8), (G.CX - 20, G.PY0 - 3, 4), (G.CX + 24, G.PY1 + 3, 12),
             (G.PX1 - 6, G.PY0, 6), (G.PX0 + 6, G.PY1, 14)]
    for (x, y, off) in spots:
        p = (ph + off) % N
        if p < 4:
            sparkle(cv, int(round(x)), int(round(y)), (1, 2, 2, 1)[p], (220, 230, 255))
    return cv.im


SLUG = "prism"
GEO = {}
frame = draw


# ------------------------------------------------------------------------------------------------
# The chat banner (banner_themes/chat.py: one chat line, 18 rows of half pixels): this banner's plate in small, and
# what the full banner shows over its plate (cut off by a chat line) inside it. The spectral bands slide through the glass, the glint runs along its bevel, rays fan out of both points, sparkles.
# ------------------------------------------------------------------------------------------------

from . import chat as C


def chat_geo(Geo):
    return C.framed(Geo, TITLE)


# The chat banner: a shard of dark crystal, cut like a gem. Cut to a point at both ends, deep indigo inside
# (opaque: the words are white, and a name tag may hang before a bright sky), a faint facet line every 16 columns.
# The light is in its edges: the cut edge is a spectrum that flows along it (to the right along the top, to the
# left along the bottom: once round in a loop) and is cut into lengths of 16 columns by a nick (the top and bottom
# rows' silhouette); the bevel inside it is ground in facets, a pale one and a dark one in turn, with a glint
# running along. A star sits on each of the four corners where the points begin. A soft sheen crosses the crystal
# once a loop, and stars twinkle on the edge. In the left point the banner's emblem: a prism, a triangle of glass
# with the spectrum in it; a lozenge answers it in the right point. Off both points grows a cluster of crystals,
# three shards each with a lit and a shaded facet, in a colour of the spectrum that moves on with the
# loop; a spark flares at the tip of one shard after another. Behind the words the crystal stays dark
# (C.hush).
CHAT_STARS = ((0, 5, 1, 0), (1, 11, -1, 9), (2, 3, -1, 4), (3, 9, 1, 13))     # cell of 16 columns, column, top (1) / bottom (-1), frame offset
CHAT_GEM = {"#": (255, 255, 255), "a": (230, 236, 255), "w": (255, 255, 255), "b": (170, 180, 250), "c": (110, 110, 190)}
# a cluster's shards, the hindmost first: (columns into the plate from its point, row, degrees off the level
# (up: under 0), length, half its width)
CHAT_SHARDS = ((-1, 7.5, -40, 12.0, 2.4), (-1, 10.5, 40, 12.0, 2.4), (-2, 9.0, 0, 17.0, 3.2))
CHAT_PRISM = ("....#....", "...#.#...", "...#.#...", "..#.a.#..", "..#.a.#..", ".#.aba.#.", ".#.aba.#.", "#.abcba.#", "#########")
CHAT_LOZENGE = ("...#...", "..#a#..", ".#aba#.", "#abcba#", ".#aba#.", "..#a#..", "...#...")


def chat_hue(h):
    """a colour of the spectrum, h 0 .. 1 round the wheel: full and bright"""
    return hsv(h % 1.0, 0.62, 1.0)


def chat_cell(G, x):
    """a column's place in the frame's repeat of 16 columns: counted from the tile's start and, in the right end,
    from that part's own start (the plate grows by 16 columns a step, so the repeat is whole where the parts meet)"""
    return (x - (G.R0 if x >= G.R0 else G.TILE0)) % 16


def chat_shard(px, G, x0, y0, deg, length, half, side, hue):
    """a crystal: a six-sided shard from (x0, y0), pointing deg off the level to one side, its last third cut to a
    point; lit above its ridge, in shade under it, a rim in the colour hue"""
    ca, sa = math.cos(math.radians(deg)) * side, math.sin(math.radians(deg))
    for y in range(G.PY0, G.PY1 + 1):
        for x in range(int(x0 - length - 3), int(x0 + length + 4)):
            dx, dy = x + 0.5 - x0, y + 0.5 - y0
            t, s = dx * ca + dy * sa, (dy * ca - dx * sa) * side      # along the shard, and across it (down: over 0)
            if not 0 <= t <= length: continue
            w = half * min(1.0, (length - t) / (0.36 * length) + 0.12)
            if abs(s) > w: continue
            if abs(s) > w - 0.9 or t < 0.9: c = lerp(hue, (255, 255, 255), 0.15) if s < 0 else lerp(hue, (24, 18, 70), 0.45)
            elif s < 0: c = lerp(hue, (255, 255, 255), 0.62 - 0.25 * t / length)
            else: c = lerp(hue, (36, 28, 96), 0.42 + 0.2 * t / length)
            if t > length - 1.3: c = (255, 255, 255)
            C.put(px, G, x, y, c)


def chat_frame(G, i):
    im, px = G.canvas()
    ph = i % N

    def body(x, y, g):
        xt = x - G.TILE0
        c = lerp((24, 20, 54), (9, 8, 24), g)
        if (chat_cell(G, x) + (y - G.PY0)) % 16 == 0: c = lerp(c, (70, 62, 140), 0.45)          # a facet line, leaning
        sheen = max(0.0, math.cos(2 * math.pi * (xt - 4 * ph + 2 * (y - G.MID)) / 64)) ** 12
        c = lerp(c, (120, 110, 220), 0.5 * sheen)
        depth = min(y - G.PY0, G.PY1 - y) - 2
        if depth < 2: c = lerp(c, chat_hue((xt / 64 + (1 if y < G.MID else -1) * ph / N) + (0 if y < G.MID else 0.5)), (0.22, 0.09)[depth])
        return C.hush(c, 0.05)

    def edge(x, y):
        xt = x - G.TILE0
        up = y <= G.MID
        k = chat_cell(G, x)
        if y in (G.PY0, G.PY1):
            if k == 0: return None                                                 # the nick between two lengths
        c = chat_hue(xt / 64 - ph / N if up else -xt / 64 - ph / N + 0.5)
        return lerp(c, (255, 255, 255), 0.25) if up else lerp(c, (40, 30, 90), 0.25)

    def bevel(x, y):
        xt = x - G.TILE0
        up = y <= G.MID
        k = chat_cell(G, x)
        glint = max(0.0, math.cos(2 * math.pi * ((xt - 4 * ph) if up else (-xt - 4 * ph)) / 64)) ** 16
        pale = (k < 8) == up                                                       # the facets, a pale one and a dark one
        base = (lerp((100, 104, 164), (72, 76, 132), (k % 8) / 7) if pale else lerp((40, 42, 92), (60, 62, 116), (k % 8) / 7))
        if y not in (G.PY0 + 1, G.PY1 - 1): base = lerp((92, 96, 150), (56, 58, 110), 0.0 if up else 1.0)   # the points' slopes
        return lerp(base, (255, 255, 255), 0.9 * glint)
    C.plate(px, G, body, edge, bevel)

    # the emblem in the left point, a prism with the spectrum in it, and the lozenge in the right point
    for art, x0, y0 in ((CHAT_PRISM, G.PX0 + 3, G.PY0 + 4), (CHAT_LOZENGE, G.PX1 - 10, G.PY0 + 5)):
        for r, row in enumerate(art):
            for c, v in enumerate(row):
                if v == ".": continue
                band = chat_hue({"a": 0.0, "b": 0.33, "c": 0.62}.get(v, 0) + ph / N)
                C.put(px, G, x0 + c, y0 + r, (236, 240, 255) if v == "#" else lerp(band, (20, 16, 50), 0.15))

    # the clusters of crystals off both points
    for side in (-1, 1):
        tip = G.PX0 if side < 0 else G.PX1
        for n, (inx, row, deg, length, half) in enumerate(CHAT_SHARDS):
            hue = chat_hue(n / 3 + ph / N + (0.5 if side > 0 else 0.0))
            chat_shard(px, G, tip - side * inx, row, deg, length, half, side, hue)
        # a spark at the tip of one shard after another
        n = (ph // 4 + (1 if side > 0 else 0)) % 3
        inx, row, deg, length, half = CHAT_SHARDS[n]
        sx = int(tip - side * inx + side * math.cos(math.radians(deg)) * (length - 1))
        sy = max(G.PY0 + 1, min(G.PY1 - 1, int(row + math.sin(math.radians(deg)) * (length - 1))))
        size = (1, 2, 2, 1)[ph % 4]
        for d in range(-size, size + 1):
            for q in ((sx + d, sy), (sx, sy + d)):
                if G.PY0 <= q[1] <= G.PY1: C.put(px, G, q[0], q[1], (255, 255, 255) if abs(d) < 2 else (226, 232, 255))

    # stars: one on each corner where a point begins (they breathe), four to a tile on the edge, each flaring for
    # four frames of the loop
    for cx, cy in ((G.PX0 + 8, G.PY0), (G.PX1 - 8, G.PY0), (G.PX0 + 8, G.PY1), (G.PX1 - 8, G.PY1)):
        big = (ph // 2 + 2 * (cx > G.CX) + 4 * (cy > G.MID)) % 8 == 0
        for d in range(-2 if big else -1, 3 if big else 2):
            for q in ((cx + d, cy), (cx, cy + d)):
                if G.PY0 <= q[1] <= G.PY1: C.put(px, G, q[0], q[1], (255, 255, 255) if abs(d) < 2 else (206, 214, 255))
    spots = []
    for x0 in range(G.TILE0, G.R0 - 15, 64):
        spots += [(x0 + cell * 16 + col, G.PY0 if way > 0 else G.PY1, off) for cell, col, way, off in CHAT_STARS if x0 + cell * 16 + 15 < G.R0]
    for x, y, off in spots:
        k = (ph + off) % N
        if k >= 4: continue
        size = (1, 2, 2, 1)[k]
        for d in range(-size, size + 1):
            for q in ((x + d, y), (x, y + d)):
                if G.PY0 <= q[1] <= G.PY1 and (q in G.edge or q in G.bevel or q not in G.inside):
                    C.put(px, G, q[0], q[1], (255, 255, 255) if d == 0 else (226, 232, 255))
    C.gem7(px, G, CHAT_GEM)
    return im
