"""
Inventory icons (16x16) of the Japanese-style furniture: textures/<id>_icon.png in the core pack (the item's
'icon'; placed furniture shows its 3D model). Drawn with the shared pixelart Canvas and palette materials like
every other icon (light from the top left, outline in the darkest step), each piece seen about the way its 3D
model is: front 3/4 for objects, straight on for flat things on a wall (scroll, screens, curtain).
"""

import math

from stsv_assets.pixelart import Canvas

ICONS = {}

UP = (0, -1, 0.35)        # a surface facing up (lit)
FRONT = (0, 0, 1)         # facing the viewer
SIDE = (0.8, 0.2, 0.55)   # facing right, away from the light


def icon(pid):
    def deco(fn):
        ICONS[pid] = fn
        return fn
    return deco


def _diamond(c, cx, cy, w, h, mat, z, normal=UP, **kw):
    """A flat square seen from above at 3/4: a diamond with half-width w and half-height h."""
    c.poly([(cx - w, cy), (cx, cy - h), (cx + w, cy), (cx, cy + h)], mat, z, normal=normal, **kw)


def _slab(c, cx, cy, w, h, t, top, side, z=0):
    """A flat square slab (cushion, tray, mat) at 3/4: the top diamond and its two front sides, t thick."""
    c.poly([(cx - w, cy), (cx, cy + h), (cx, cy + h + t), (cx - w, cy + t)], side, z, normal=(-0.3, 0.2, 1))
    c.poly([(cx, cy + h), (cx + w, cy), (cx + w, cy + t), (cx, cy + h + t)], side, z, normal=SIDE)
    _diamond(c, cx, cy, w, h, top, z + 1)


# ------------------------------------------------------------------------------------------------
# 바닥 · 앉는 것
# ------------------------------------------------------------------------------------------------

@icon("tatami")
def tatami():
    c = Canvas()
    a, b, cc, d = (0.5, 9.5), (9.5, 3.5), (15.5, 7.5), (6.5, 13.5)
    t = 1.5
    c.poly([a, d, (d[0], d[1] + t), (a[0], a[1] + t)], "tatami", 0, normal=(-0.3, 0.2, 1))
    c.poly([d, cc, (cc[0], cc[1] + t), (d[0], d[1] + t)], "rubber", 0, normal=SIDE)
    c.poly([a, b, cc, d], "tatami", 1, normal=UP)
    top = set(c.last())
    for k in range(1, 4):                     # stitch lines across the mat
        f = k / 4
        c.line(a[0] + (b[0] - a[0]) * f + 0.3, a[1] + (b[1] - a[1]) * f + 0.5,
               d[0] + (cc[0] - d[0]) * f - 0.3, d[1] + (cc[1] - d[1]) * f - 0.5, "tatami", 2, z=2)
    c.line(1, 10, 9, 4, "rubber", 2, z=3)     # the dark cloth borders on the long sides
    c.line(7, 13, 15, 8, "rubber", 2, z=3)
    c.erase([p for p in c.where("tatami") if p not in top and p[1] > 14])
    return c.render()


@icon("zabuton")
def zabuton():
    c = Canvas()
    _slab(c, 8, 8, 7, 4, 2, "crimson", "crimson", 0)
    c.tone(c.where("crimson"), 1)
    c.put(8, 8, "crimson", 1, z=5)
    for (x, y) in ((1, 8), (14, 8), (8, 4), (8, 14)):
        c.put(x, y, "gold", 3, z=6)
    return c.render()


@icon("chabudai")
def chabudai():
    c = Canvas()
    for (x, y0, y1) in ((2.5, 7, 13.5), (12, 7, 13.5), (7, 9, 15)):
        c.rect(x, y0, x + 1.6, y1, "wood_dark", 0, normal=FRONT)
    c.ellipse(8, 7.5, 7.6, 3.6, "wood_dark", 1, dome=0.2)
    c.ellipse(8, 6.6, 7.6, 3.6, "wood", 2, dome=0.25, gloss=0)
    c.cyl(3.6, 4.3, 5.6, 6.6, "celadon", 3)
    c.cyl(10.4, 5.3, 12.4, 7.6, "celadon", 3)
    c.put(4, 4, "bamboo", 3, z=4)
    c.put(11, 5, "bamboo", 3, z=4)
    for (x, mat) in ((6, "pink"), (7, "rice"), (8, "sprout")):    # a skewer of hanami dango
        c.put(x, 6, mat, 2, z=5)
        c.put(x, 5, mat, 3, z=5)
    return c.render()


@icon("kotatsu")
def kotatsu():
    c = Canvas()
    for (x, y) in ((1.6, 11), (14.4, 11)):
        c.ellipse(x, y, 1.8, 1.2, "crimson", 0, dome=0.3)
    c.poly([(1.5, 7.5), (8, 11), (8, 15.5), (1, 12.5)], "fox", 1, normal=(-0.3, 0.2, 1))
    c.poly([(8, 11), (14.5, 7.5), (15, 12.5), (8, 15.5)], "fox", 1, normal=SIDE)
    for (x0, y0, x1, y1) in ((3, 9, 3, 13), (5, 10, 5, 14), (11, 10, 11, 14), (13, 9, 13, 13)):
        c.line(x0, y0, x1, y1, "fox", 2, z=2)
    _diamond(c, 8, 7.2, 7.5, 3.8, "wood", 3)
    c.ellipse(6.5, 6.2, 1.6, 1.4, "fox", 4)
    c.ellipse(9.5, 6.4, 1.6, 1.4, "fox", 4)
    c.ellipse(8, 4.8, 1.6, 1.4, "fox", 5)
    c.put(8, 3, "leaf", 3, z=6)
    return c.render()


@icon("futon")
def futon():
    c = Canvas()
    a, b, cc, d = (1, 10), (8.5, 2.5), (15, 6.5), (7.5, 14)
    c.poly([a, d, (d[0], d[1] + 1.5), (a[0], a[1] + 1.5)], "porcelain", 0, normal=(-0.3, 0.2, 1))
    c.poly([d, cc, (cc[0], cc[1] + 1.5), (d[0], d[1] + 1.5)], "porcelain", 0, normal=SIDE)
    c.poly([a, b, cc, d], "porcelain", 1, normal=UP)
    q = 0.62                                   # the quilt covers the front 62%
    qa, qd = a, d
    qb = (a[0] + (b[0] - a[0]) * q, a[1] + (b[1] - a[1]) * q)
    qc = (d[0] + (cc[0] - d[0]) * q, d[1] + (cc[1] - d[1]) * q)
    c.poly([(qa[0] - 0.3, qa[1]), qb, qc, (qd[0], qd[1] + 0.3)], "indigo", 2, normal=UP)
    for (x, y) in ((4, 10), (6, 8), (8, 11), (10, 9), (6, 12)):
        c.put(x, y, "porcelain", 3, z=3)
    c.line(qb[0], qb[1] + 0.5, qc[0], qc[1] + 0.5, "porcelain", 3, z=3)
    _diamond(c, 11.2, 5.0, 2.6, 1.6, "straw", 4)
    return c.render()


# ------------------------------------------------------------------------------------------------
# 칸막이 · 벽
# ------------------------------------------------------------------------------------------------

@icon("shoji_screen")
def shoji_screen():
    c = Canvas()
    c.rect(1, 1, 15, 14, "hinoki", 0, normal=FRONT)
    for x0 in (2, 9):                          # two panels, each a 2 x 3 grid of paper panes
        for (dx, dy) in ((0, 0), (3, 0), (0, 3), (3, 3), (0, 6), (3, 6)):
            c.rect(x0 + dx, 2 + dy, x0 + dx + 2, 2 + dy + 2, "paper", 1, step=4 if dy < 6 else 3)
    c.rect(2, 11, 14, 13, "hinoki", 1, normal=FRONT, lo=2, hi=3)
    c.rect(1, 14, 4, 15, "wood_dark", 1, normal=UP)
    c.rect(12, 14, 15, 15, "wood_dark", 1, normal=UP)
    return c.render()


@icon("fusuma_panel")
def fusuma_panel():
    c = Canvas()
    c.rect(1, 1, 15, 15, "rubber", 0, normal=FRONT)
    c.rect(2, 2, 7.5, 14, "gold", 1, step=3)
    c.rect(8.5, 2, 14, 14, "gold", 1, step=3)
    c.poly([(3, 14), (8, 6), (13, 14)], "indigo", 2, normal=FRONT, lo=2, hi=4)
    c.poly([(6.3, 8.8), (8, 6), (9.7, 8.8)], "porcelain", 3, step=4)
    c.ellipse(12, 4.5, 1.4, 1.4, "vermilion", 3, gloss=0)
    c.line(2, 10, 5, 10, "gold", 4, z=4)
    c.line(10, 9, 13, 9, "gold", 4, z=4)
    return c.render()


@icon("hanging_scroll")
def hanging_scroll():
    c = Canvas()
    c.line(8, 0, 4, 1, "crimson", 2)
    c.line(8, 0, 12, 1, "crimson", 2)
    c.rect(3, 1, 13, 2.5, "wood_dark", 1, normal=FRONT)
    c.rect(4, 2.5, 12, 14, "celadon", 0, normal=FRONT, hi=3)
    c.rect(4, 2.5, 12, 4, "indigo", 0.5, step=1)                 # brocade bands top and bottom
    c.rect(4, 12, 12, 14, "indigo", 0.5, step=1)
    c.line(6, 2.5, 6, 5, "indigo", 2, z=3)                       # the two futai ribbons
    c.line(10, 2.5, 10, 5, "indigo", 2, z=3)
    c.rect(5, 4, 11, 12, "paper", 1, step=4)
    c.ellipse(9, 5.6, 1.2, 1.2, "cream", 2, gloss=0, lo=2, hi=3)
    c.poly([(5, 11.5), (7, 7.5), (8.5, 9), (9.5, 8), (11, 11.5)], "millstone", 2, normal=FRONT, lo=1, hi=2)
    c.put(6, 5, "rubber", 2, z=3)
    c.put(6, 6, "rubber", 2, z=3)
    c.put(10, 11, "vermilion", 3, z=3)
    c.rect(3, 14, 13, 15.5, "wood_dark", 1, normal=FRONT)
    c.put(2, 14, "rubber", 2, z=2)
    c.put(13, 14, "rubber", 2, z=2)
    return c.render()


@icon("noren")
def noren():
    """Three indigo strips on a bamboo rod, the white mitsu-uroko crest across them, a white hem."""
    c = Canvas()
    c.rect(0, 1, 16, 2.5, "bamboo", 2, normal=FRONT)
    for x0 in (1, 6, 11):
        c.rect(x0, 2.5, x0 + 4, 14, "indigo", 1, normal=FRONT, lo=2, hi=3)
        c.rect(x0, 12, x0 + 4, 13.5, "porcelain", 2, step=3)
    for (x, y) in ((7, 4), (8, 4), (6, 5), (7, 5), (8, 5), (9, 5),
                   (4, 7), (5, 7), (3, 8), (4, 8), (5, 8), (6, 8),
                   (10, 7), (11, 7), (9, 8), (10, 8), (11, 8), (12, 8)):
        c.put(x, y, "porcelain", 4, z=3)
    return c.render()


@icon("bamboo_fence")
def bamboo_fence():
    """Sun-bleached split slats between green rails, dark palm-cord ties, a green cap: a tall screen fence."""
    c = Canvas()
    for x in range(3, 13):
        c.rect(x, 1.5, x + 1, 15.5, "straw", 0, step=3 if x % 2 else 2)
        c.put(x, 4 + (x * 3) % 9, "straw", 1, z=1)          # the nodes, staggered
    c.rect(2, 0, 14, 1.5, "bamboo", 2, normal=UP)
    for y in (4, 8.5, 13):
        c.rect(2, y, 14, y + 1.5, "bamboo", 2, normal=FRONT, lo=2, hi=3)
        c.rect(4, y - 0.5, 5, y + 2, "bark", 3, step=0)
        c.rect(11, y - 0.5, 12, y + 2, "bark", 3, step=0)
    return c.render()


# ------------------------------------------------------------------------------------------------
# 조명
# ------------------------------------------------------------------------------------------------

@icon("andon")
def andon():
    c = Canvas()
    c.rect(3, 4, 13, 14, "washi_lit", 0, normal=FRONT, lo=2, hi=4)
    c.line(3, 9, 12, 9, "washi_lit", 1, z=1)
    c.line(8, 4, 8, 13, "washi_lit", 1, z=1)
    for x in (2, 13):
        c.rect(x, 2, x + 1, 16, "wood_dark", 2, normal=FRONT)
    c.rect(3, 3, 13, 4, "wood_dark", 2, normal=UP)
    c.rect(3, 14, 13, 15, "wood_dark", 2, normal=FRONT)
    c.rect(5, 0, 11, 1, "wood_dark", 2, normal=UP)
    c.rect(5, 1, 6, 3, "wood_dark", 2, normal=FRONT)
    c.rect(10, 1, 11, 3, "wood_dark", 2, normal=FRONT)
    return c.render()


@icon("stone_lantern")
def stone_lantern():
    c = Canvas()
    m = "millstone"
    c.rect(3, 14, 13, 16, m, 0, normal=UP)
    c.cyl(6, 9, 10, 14, m, 1)
    c.rect(4, 8, 12, 9.5, m, 2, normal=UP)
    c.rect(5, 5, 11, 8, m, 2, normal=FRONT)
    c.rect(6.5, 5.5, 9.5, 8, "glow_amber", 3, normal=FRONT, lo=3, hi=4)
    c.poly([(0.5, 5.5), (5, 2.5), (11, 2.5), (15.5, 5.5)], m, 4, normal=UP)
    c.put(0, 4, m, 3, z=5)                                    # the roof's corners curl up
    c.put(15, 4, m, 3, z=5)
    c.ellipse(8, 1.6, 1.4, 1.4, m, 5)
    for (x, y) in ((4, 4), (5, 4), (11, 4), (5, 14), (6, 14)):
        c.put(x, y, "leaf", 3, z=6)                            # moss
    return c.render()


@icon("chochin")
def chochin():
    c = Canvas()
    c.ellipse(8, 8.5, 5.6, 5.2, "vermilion", 0, gloss=0)
    for y in (5, 7, 9, 11, 13):
        for x in range(3, 14):
            if (x, y) in c.px:
                c.px[(x, y)][1] = max(1, c.px[(x, y)][1] - 1)
    c.ellipse(7.5, 8.5, 2.2, 2.2, "porcelain", 3, gloss=0, lo=2, hi=3)   # the white crest
    c.put(7, 8, "vermilion", 1, z=4)
    c.put(8, 8, "vermilion", 1, z=4)
    c.put(7, 9, "vermilion", 1, z=4)
    c.put(8, 9, "vermilion", 1, z=4)
    c.rect(5.5, 2, 10.5, 3.5, "rubber", 2, normal=UP)
    c.rect(5.5, 13.5, 10.5, 15, "rubber", 2, normal=FRONT)
    c.rect(7.5, 0, 8.5, 2, "iron", 3, step=3)
    return c.render()


# ------------------------------------------------------------------------------------------------
# 장식품 · 화분
# ------------------------------------------------------------------------------------------------

@icon("bonsai")
def bonsai():
    c = Canvas()
    c.rect(1.5, 11, 14.5, 14.5, "indigo", 0, normal=FRONT)
    c.rect(2, 10, 14, 11, "leaf", 1, step=3)
    c.rect(2, 14.5, 4, 15.5, "indigo", 0, step=1)
    c.rect(12, 14.5, 14, 15.5, "indigo", 0, step=1)
    c.tube([(6.5, 10.5), (7.5, 7.5), (9, 5)], [1.3, 1.1, 0.9], "bark", 2)
    c.line(7, 7, 4, 6, "bark", 2, z=2)
    c.ellipse(3.8, 6.2, 3.2, 1.8, "leaf_dark", 3, dome=0.6)
    c.ellipse(11.5, 5.8, 3.3, 1.8, "leaf_dark", 3, dome=0.6)
    c.ellipse(8.2, 2.8, 3.0, 1.7, "leaf_dark", 4, dome=0.6)
    return c.render()


@icon("ikebana")
def ikebana():
    c = Canvas()
    c.ellipse(8, 13, 6.5, 2.4, "celadon", 0, dome=0.4)
    c.ellipse(8, 12.4, 4.6, 1.3, "water", 1, gloss=0, lo=3, hi=4)
    c.line(8, 12, 8, 4, "leaf_dark", 2, z=2)
    c.line(9, 12, 10, 8, "leaf_dark", 2, z=2)
    c.line(7, 12, 5, 9, "leaf_dark", 2, z=2)
    c.line(7, 5, 3, 2, "bark", 2, z=2)
    c.ellipse(8, 3.8, 1.8, 1.6, "porcelain", 4)
    c.ellipse(10.5, 7.5, 1.6, 1.5, "vermilion", 4)
    c.ellipse(4.6, 8.6, 1.5, 1.4, "vermilion", 4)
    c.put(3, 2, "sakura", 3, z=5)
    c.put(11, 11, "leaf", 3, z=3)
    c.put(6, 11, "leaf", 3, z=3)
    return c.render()


@icon("bamboo_pot")
def bamboo_pot():
    c = Canvas()
    c.rect(3, 11, 13, 16, "hinoki", 0, normal=FRONT)
    c.rect(3, 12, 13, 13, "rubber", 1, step=2)
    for (x, top) in ((5, 0.5), (8.5, 3), (11, 5)):
        c.rect(x, top, x + 1.6, 11, "bamboo", 2, normal=FRONT)
        y = top + 3
        while y < 10.5:
            c.line(x, round(y), x + 1, round(y), "bamboo", 1, z=3)
            y += 3
    for (x0, y0, x1, y1) in ((1, 3, 5, 1), (7, 4, 10, 2), (12, 7, 15, 5), (3, 6, 5, 5)):
        c.line(x0, y0, x1, y1, "leaf", 3, z=4)
    return c.render()


@icon("sakura_pot")
def sakura_pot():
    c = Canvas()
    c.cyl(4.5, 11, 11.5, 16, "onggi", 0)
    c.rect(4, 10.5, 12, 11.5, "onggi", 1, step=3)
    c.tube([(7.5, 11), (8, 8), (9, 6)], [1.1, 1, 0.8], "bark", 2)
    c.line(7, 8, 4, 7, "bark", 2, z=2)                          # the branches reaching out
    c.line(9, 7, 12, 6, "bark", 2, z=2)
    c.ellipse(4.5, 6, 3.6, 2.8, "sakura", 3, dome=0.7)
    c.ellipse(11.5, 5.2, 3.6, 2.8, "sakura", 3, dome=0.7)
    c.ellipse(8, 3, 4.2, 2.8, "sakura", 4, dome=0.7)
    for (x, y) in ((5, 5), (11, 4), (8, 2), (3, 7), (13, 6)):
        c.put(x, y, "sakura", 5, z=6)
    return c.render()


# ------------------------------------------------------------------------------------------------
# 다구 · 소품
# ------------------------------------------------------------------------------------------------

@icon("tea_set")
def tea_set():
    c = Canvas()
    _slab(c, 8, 10, 7.5, 4.5, 1.5, "wood_dark", "wood_dark", 0)
    c.ellipse(5.5, 7.5, 3.4, 3.0, "terracotta", 3)
    c.rect(3.5, 4, 7.5, 5, "terracotta", 4, normal=UP)
    c.put(5, 3, "terracotta", 2, z=5)
    c.line(1, 6, 2, 7, "terracotta", 3, z=4)
    c.line(8, 9, 10, 9, "terracotta", 2, z=4)
    for (x, y) in ((11.5, 7), (13.5, 10), (10.5, 12)):
        c.cyl(x - 1.5, y - 1.5, x + 1.5, y + 1.5, "celadon", 5, lo=2, hi=4)
        c.line(round(x - 1), round(y - 1.5), round(x), round(y - 1.5), "bamboo", 3, z=6)
    return c.render()


@icon("tea_kettle")
def tea_kettle():
    c = Canvas()
    c.cyl(1.5, 10, 14.5, 16, "porcelain", 0)
    c.rect(1.5, 9.5, 14.5, 10.5, "indigo", 1, step=2)
    c.rect(2, 13, 14, 14, "indigo", 1, step=3)
    for x in (3, 12):
        c.put(x, 9, "fire", 4, z=2)
    c.ellipse(8, 7, 5, 3.4, "iron", 3, gloss=0.75)
    c.rect(6, 3, 10, 4.5, "iron", 4, normal=UP)
    c.put(8, 2, "brass", 3, z=5)
    c.line(3, 6, 1, 4, "iron", 2, z=2)
    c.line(3, 4, 4, 0, "iron", 1, z=1)
    c.line(4, 0, 12, 0, "iron", 1, z=1)
    c.line(12, 0, 13, 4, "iron", 1, z=1)
    for (x, y) in ((6, 7), (8, 8), (10, 7), (7, 9), (9, 9)):
        c.put(x, y, "iron", 4, z=4)
    return c.render()


@icon("sake_set")
def sake_set():
    c = Canvas()
    _slab(c, 8, 11.5, 7.5, 3.2, 1.5, "vermilion", "vermilion", 0)
    c.ellipse(6.5, 9.5, 3.6, 3.4, "porcelain", 2, lo=2, hi=4)          # the tokkuri's round body
    c.rect(5.5, 2.5, 7.5, 6.8, "porcelain", 3, normal=FRONT, lo=2, hi=4)  # its neck
    c.rect(5, 1.5, 8, 2.5, "porcelain", 4, step=4)                      # lip
    c.rect(3, 7, 10, 8, "indigo", 5, step=2)                            # indigo band
    c.erase([p for p in c.where("indigo") if p[0] < 4 or p[0] > 9])
    c.cyl(10.5, 9, 14, 12, "porcelain", 6, lo=2, hi=4)                  # cup
    c.line(11, 9, 13, 9, "rice", 2, z=7)
    return c.render()


@icon("taiko_drum")
def taiko_drum():
    c = Canvas()
    c.line(2, 15, 5, 9, "wood_dark", 2, z=0)
    c.line(14, 15, 11, 9, "wood_dark", 2, z=0)
    c.rect(1, 14, 4, 15.5, "wood_dark", 2, normal=UP)
    c.rect(12, 14, 15, 15.5, "wood_dark", 2, normal=UP)
    c.ellipse(9, 7.5, 6.6, 6.6, "vermilion", 1, gloss=0)
    c.ellipse(7.5, 7.5, 6, 6.4, "cream", 2, gloss=0, lo=2, hi=4)
    c.ellipse(7.5, 7.5, 4.2, 4.4, "vermilion", 3, gloss=0, lo=2, hi=3)
    c.ellipse(7.5, 7.5, 3.0, 3.2, "cream", 4, gloss=0, lo=3, hi=4)
    for k in range(3):
        a = math.radians(90 + 120 * k)
        c.put(round(7.5 + 1.4 * math.cos(a) - 0.5), round(7.5 + 1.4 * math.sin(a) - 0.5), "vermilion", 3, z=5)
    for k in range(10):
        a = math.radians(k * 36)
        c.put(round(7 + 5.6 * math.cos(a)), round(7 + 5.9 * math.sin(a)), "brass", 4, z=6)
    return c.render()


def _pixels(c, rows, colours, x0, y0, z):
    """Pixel art on top of the shapes: rows of characters from (x0, y0); '.' leaves the pixel as it is."""
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch in colours:
                mat, step = colours[ch]
                c.put(x0 + i, y0 + j, mat, step, z=z)


@icon("maneki_neko")
def maneki_neko():
    """The beckoning cat as its 3D model shows it: a big white head with eyes closed in a smile, a pink nose
    over an ω mouth, rosy cheeks and whiskers, the raised paw beside it, a red collar and bell, a gold koban,
    the red cushion."""
    c = Canvas()
    w = "feather"
    c.rect(1, 14, 15, 16, "crimson", 0, normal=UP)              # the red cushion it sits on
    c.put(0, 15, "gold", 3, z=1)
    c.put(15, 15, "gold", 3, z=1)
    c.rect(4, 10, 12, 14.5, w, 1, normal=FRONT)                 # the body
    c.rect(2, 3, 14, 10, w, 2, normal=FRONT)                    # the head
    c.poly([(3, 3), (4, 0.5), (6, 3)], w, 2, step=3)            # the ears
    c.poly([(10, 3), (12, 0.5), (13, 3)], w, 2, step=3)
    c.put(4, 2, "pink", 3, z=3)
    c.put(11, 2, "pink", 3, z=3)
    c.put(12, 1, "fox", 3, z=3)                                 # a calico patch on one ear
    c.put(12, 2, "fox", 2, z=3)
    c.rect(0, 2, 2, 9, w, 3, step=3)                            # the raised paw, its pad to the front
    c.put(0, 3, "pink", 3, z=4)
    c.put(1, 3, "pink", 3, z=4)
    c.put(0, 4, "pink", 2, z=4)
    c.put(1, 4, "pink", 2, z=4)
    _pixels(c, ["............",
                "..KK....KK..",     # eyes closed in a smile (y 4, 5)
                ".K..K..K..K.",
                ".B...PP...B.",     # nose; rosy cheeks
                "...K.KK.K...",     # the mouth: ω
                "....K..K...."],
            {"K": ("rubber", 0), "P": ("pink", 1), "B": ("pink", 3)}, 2, 3, 2)
    c.rect(3, 10, 13, 11, "vermilion", 4, step=3)               # the collar and its bell
    c.put(8, 11, "gold", 4, z=6)
    c.ellipse(5.5, 12.5, 1.6, 1.9, "gold", 6)                   # the koban
    return c.render()


@icon("daruma")
def daruma():
    """The doll as its 3D model shows it: a big white face with bold brows, one eye blank and one painted in,
    a red nose and a curled moustache; gold at the hem."""
    c = Canvas()
    c.ellipse(8, 8.5, 7.2, 7.4, "vermilion", 0)
    c.rect(3, 2, 13, 13, "porcelain", 1, step=4)
    for (x, y) in ((3, 2), (12, 2), (3, 12), (12, 12)):
        c.erase([(x, y)])
    _pixels(c, ["KKK....KKK",       # the brows
                "..........",
                ".KK....KK.",       # the eyes: blank | painted (with a glint)
                "K..K..KWKK",
                ".KK....KK.",
                "....RR....",       # the nose
                ".K......K.",       # the moustache, its ends curled up
                "..KKKKKK.."],
            {"K": ("rubber", 0), "W": ("porcelain", 4), "R": ("vermilion", 1)}, 3, 3, 1)
    for x in range(5, 11):
        c.put(x, 14, "gold", 3, z=3)                 # the gold band at the hem
    c.put(1, 7, "gold", 3, z=3)                      # gold clouds on its sides
    c.put(14, 7, "gold", 2, z=3)
    return c.render()


@icon("omamori_rack")
def omamori_rack():
    c = Canvas()
    c.poly([(0, 3.5), (3, 1), (13, 1), (16, 3.5)], "celadon", 3, normal=UP)      # copper gone green
    for x in (1.5, 13):
        c.rect(x, 3.5, x + 1.5, 15, "vermilion", 2, normal=FRONT)
    c.rect(1, 14.5, 4, 16, "wood_dark", 2, normal=UP)
    c.rect(12, 14.5, 15, 16, "wood_dark", 2, normal=UP)
    c.rect(3, 5, 13, 6, "hinoki", 1, normal=FRONT)
    for k, (x, mat) in enumerate(((3.5, "vermilion"), (6, "indigo"), (8.5, "porcelain"), (11, "sakura"))):
        top = 7 + (k % 2)
        c.rect(x, top, x + 2, top + 4.5, mat, 2, normal=FRONT)
        c.put(round(x), top + 1, "gold", 4, z=4)
        c.put(round(x) + 1, 6, "gold", 3, z=3)
    return c.render()


@icon("wind_chime")
def wind_chime():
    c = Canvas()
    c.line(8, 0, 8, 2, "iron", 3)
    c.ellipse(8, 5.5, 4.4, 3.8, "glass", 1, gloss=0.6)
    c.erase([p for p in c.where("glass") if p[1] > 7])
    c.rect(3.5, 7, 12.5, 8, "glass", 2, step=2)
    for (x, y) in ((6, 5), (7, 5), (10, 4)):
        c.put(x, y, "vermilion", 3, z=3)
    c.line(8, 8, 8, 10, "cream", 2, z=2)
    c.rect(6.5, 10, 9.5, 15.5, "sakura", 2, normal=FRONT)
    c.line(8, 11, 8, 14, "sakura", 1, z=3)
    return c.render()


# ------------------------------------------------------------------------------------------------
# 큰 장식 · 정원
# ------------------------------------------------------------------------------------------------

@icon("wagasa")
def wagasa():
    c = Canvas()
    c.rect(7.4, 6, 8.8, 15, "bamboo", 0, normal=FRONT)
    c.rect(5.5, 14.5, 10.5, 16, "millstone", 0, normal=UP)
    c.poly([(0.5, 7.5), (8, 1.5), (15.5, 7.5), (8, 9)], "vermilion", 1, normal=UP)
    for x in (2.5, 5, 8, 11, 13.5):
        c.line(8, 2, x, 7.5, "vermilion", 2, z=2)
    for (x, y) in ((5, 4), (6, 3), (7, 3), (8, 3), (9, 3), (10, 3), (11, 4)):
        c.put(x, y, "porcelain", 3, z=3)                          # janome: the white ring
    c.rect(7, 0.5, 9, 2, "rubber", 4, step=2)
    return c.render()


@icon("kimono_rack")
def kimono_rack():
    c = Canvas()
    c.rect(0, 1, 16, 2.5, "rubber", 3, normal=UP)
    for x in (1, 13.5):
        c.rect(x, 2.5, x + 1.5, 15, "rubber", 0, normal=FRONT)
    c.rect(0, 14.5, 4, 16, "rubber", 0, normal=UP)
    c.rect(12, 14.5, 16, 16, "rubber", 0, normal=UP)
    c.rect(1.5, 2.5, 14.5, 7, "indigo", 1, normal=FRONT, lo=2, hi=3)
    c.rect(4.5, 7, 11.5, 15, "indigo", 1, normal=FRONT, lo=2, hi=3)
    c.line(6.5, 2.5, 8, 6, "porcelain", 3, z=3)
    c.line(9.5, 2.5, 8, 6, "porcelain", 3, z=3)
    c.rect(4.5, 7.5, 11.5, 9, "vermilion", 2, step=3)
    for (x, y) in ((3, 4), (12, 5), (6, 11), (10, 12), (8, 13)):
        c.put(x, y, "sakura", 3, z=3)
    return c.render()


@icon("torii")
def torii():
    c = Canvas()
    c.rect(2.5, 4, 4.5, 16, "vermilion", 0, normal=FRONT)
    c.rect(11.5, 4, 13.5, 16, "vermilion", 0, normal=FRONT)
    c.rect(0.5, 7, 15.5, 8.5, "vermilion", 1, normal=FRONT, hi=3)
    c.rect(1, 3, 15, 4.5, "vermilion", 1, normal=FRONT, hi=3)
    c.poly([(0, 0.5), (8, 1.5), (16, 0.5), (15, 3), (1, 3)], "rubber", 2, normal=UP)
    c.rect(7, 4.5, 9, 7, "rubber", 3, normal=FRONT)
    c.put(7, 5, "gold", 3, z=4)
    c.put(8, 6, "gold", 3, z=4)
    c.rect(2, 15, 5, 16, "rubber", 3, step=1)
    c.rect(11, 15, 14, 16, "rubber", 3, step=1)
    return c.render()


@icon("stepping_stones")
def stepping_stones():
    c = Canvas()
    for (cx, cy, rx, ry, z) in ((5, 11.5, 4.2, 2.6, 0), (11.5, 6.5, 3.6, 2.2, 1), (12, 13, 2.4, 1.6, 2), (4, 4.5, 2.4, 1.6, 3)):
        c.ellipse(cx, cy + 0.8, rx, ry, "millstone", z, dome=0.2, lo=1, hi=2)
        c.ellipse(cx, cy, rx, ry, "millstone", z + 0.5, dome=0.25, lo=2, hi=4, gloss=0)
    return c.render()


@icon("koi_pond")
def koi_pond():
    c = Canvas()
    c.poly([(0.5, 8), (8, 2.5), (15.5, 8), (8, 13.5)], "millstone", 0, normal=UP)
    c.poly([(0.5, 8), (8, 13.5), (8, 15), (0.5, 9.5)], "millstone", 0, normal=(-0.3, 0.2, 1))
    c.poly([(8, 13.5), (15.5, 8), (15.5, 9.5), (8, 15)], "millstone", 0, normal=SIDE)
    c.poly([(3, 8), (8, 4.5), (13, 8), (8, 11.5)], "water", 1, normal=UP, lo=2, hi=3)
    c.line(5, 8, 7, 7, "fox", 4, z=3)
    c.put(8, 7, "porcelain", 4, z=3)
    c.line(9, 9, 10, 10, "porcelain", 4, z=3)
    c.put(11, 10, "fox", 4, z=3)
    c.ellipse(9.5, 6, 1.4, 0.8, "leaf", 3, gloss=0)
    c.put(10, 6, "sakura", 4, z=4)
    return c.render()
