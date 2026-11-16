"""
Cosmetics, batch B (content/stsv/items/cosmetics_batch_b.yml): six more tails and the first eight neck pieces.

Frames (as models/cosmetics.py: the model's (8, 8, 8) sits on the slot's anchor, north (-z) = the player's
front, south (+z) behind them, 1 unit = 1/16 block, about one skin pixel):
  tail  anchor on the lower back (cosmetics.anchors.tail): hangs from z 8 backwards, like fox_tail / cat_tail
  neck  anchor [0, -0.47, 0] below the top of the hitbox: the player's neck (where the head meets the body) is at
        model y 9.2, the body's top face runs x 4.25..11.75 and z 6.1..9.9 under it, the chest is the body's
        north face (z 6.1) from about y 2 up to 9.2, the arms start outside x 4.25 / 11.75. A collar wraps the
        body's top just under the head (never above y 9.6: the head turns on its own and would cut through it),
        anything hanging down lies on the chest just in front of z 6.1.
"""

import economy_models  # noqa: F401  (registers the shared surfaces the machine materials may use)
from economy_machines import MModel, Mat

from models.cosmetics import BLACK, EYE, GOLD, GOLD_LIGHT, PINK, WHITE, _display, mirror

TAIL_GUI = (30, 135, 0)       # the side that shows: behind and to the side, like fox_tail
NECK_GUI = (30, 225, 0)       # the front 3/4 (north side)


# ================================================================================================
# 꼬리 (lower back, hanging backwards)
# ================================================================================================

def puppy_tail():
    """강아지 꼬리: a shiba's tail curled up over the back, fox-orange with a cream underside and tip."""
    m = MModel("puppy_tail")
    fur, fur_light, cream = Mat("fox", "speckle", 3), Mat("fox", "plain", 4), Mat("cream", "plain", 3)
    m.box("tail", (6.75, 6, 8), (9.25, 8.5, 10.5), fur)
    m.box("tail", (6.5, 8, 10), (9.5, 11.5, 12.5), fur_light)
    m.box("under", (6.9, 8.25, 12.5), (9.1, 11, 12.9), cream, faces={"north": None})
    m.box("tail", (6.75, 11.25, 9.25), (9.25, 13.5, 12), fur)
    m.box("tip", (7, 12.5, 7.75), (9, 14.25, 9.5), cream)
    m.box("tip", (7.25, 11.5, 7.25), (8.75, 12.75, 8.25), cream)
    _display(m, TAIL_GUI, 0.9, -2)
    return m


def bunny_tail():
    """토끼 솜꼬리: a round white cotton puff on the lower back, built up in layers so it reads as a ball."""
    m = MModel("bunny_tail")
    puff, shade, light = Mat("feather", "speckle", 3), Mat("feather", "plain", 2), Mat("snow", "plain", 3)
    m.box("puff", (6, 5, 9.5), (10, 9, 13), puff)
    m.box("puff", (5.25, 5.75, 10.25), (10.75, 8.25, 12.25), shade)
    m.box("puff", (6.75, 4.25, 10.25), (9.25, 9.75, 12.25), shade)
    m.box("puff", (6.75, 5.75, 13), (9.25, 8.25, 13.75), light, faces={"north": None})
    m.box("puff", (7.25, 6.25, 13.75), (8.75, 7.75, 14.1), light, faces={"north": None})
    _display(m, TAIL_GUI, 1.2, -1)
    return m


def dino_tail():
    """공룡 꼬리: a thick green tail tapering back and down to the ground, a cream underside and orange spikes."""
    m = MModel("dino_tail")
    scale, scale_dark, belly = Mat("leaf", "speckle", 2), Mat("leaf_dark", "plain", 2), Mat("cream", "plain", 2)
    spike = Mat("fire", "plain", 3)
    # (x half width, y0, y1, z0, z1): each segment thinner and lower
    segs = [(2.25, 3.5, 9, 8, 11.5), (1.85, 1.75, 7, 11.25, 14.75), (1.45, 0.25, 4.75, 14.5, 18), (1.0, -1.0, 2.5, 17.75, 21),
            (0.6, -2.0, 0.75, 20.75, 23.5)]
    for k, (hw, y0, y1, z0, z1) in enumerate(segs):
        m.box("tail", (8 - hw, y0, z0), (8 + hw, y1, z1), scale if k % 2 == 0 else scale_dark)
        m.box("belly", (8 - hw + 0.4, y0 - 0.3, z0 + 0.3), (8 + hw - 0.4, y0, z1 - 0.3), belly, faces={"up": None})
    for (y, z, h) in ((9, 8.75, 1.75), (7, 12, 1.5), (4.75, 15.25, 1.25), (2.5, 18.5, 1.0)):
        m.box("spike", (7.4, y, z), (8.6, y + h, z + 1.5), spike)
    _display(m, TAIL_GUI, 0.6, 1)
    return m


def raccoon_tail():
    """너구리 꼬리: a bushy grey-brown tail with dark rings and a black tip, rising in a soft curve."""
    m = MModel("raccoon_tail")
    fur, ring_, tip = Mat("millstone", "speckle", 2), Mat("bark", "plain", 1), Mat("soot", "plain", 1)
    # long light bands and short dark rings, rising in a soft curve and fattening in the middle
    bands = [(5.0, 8.5, 8.0, 10.25, 2.2, fur), (4.5, 8.75, 10.25, 11.0, 2.35, ring_), (4.25, 9.25, 11.0, 13.25, 2.5, fur),
             (4.75, 10.0, 13.25, 14.0, 2.35, ring_), (5.5, 11.25, 14.0, 16.0, 2.3, fur), (7.0, 12.5, 16.0, 16.75, 2.1, ring_),
             (8.5, 13.75, 16.75, 18.25, 1.9, fur), (10.0, 14.5, 18.25, 19.25, 1.5, tip)]
    for (y0, y1, z0, z1, hw, mat) in bands:
        m.box("tail", (8 - hw, y0, z0), (8 + hw, y1, z1), mat)
    _display(m, TAIL_GUI, 0.65, -1)
    return m


def devil_tail():
    """악마 꼬리: a thin black-red tail in an S curve ending in a glowing red heart-shaped spade."""
    m = MModel("devil_tail")
    skin, skin_dark = Mat("crimson", "plain", 1), Mat("crimson", "plain", 0)
    heart = Mat("ruby", "plain", 3, glow=True, noise=0)
    path = [((5.5, 8), (7, 10.75)), ((3.75, 10.25), (5.75, 12)), ((3, 11.75), (4.5, 14.5)), ((4.25, 14.25), (6.25, 15.75)),
            ((6, 15.5), (9.25, 16.75)), ((8.75, 16.25), (11.25, 17.5))]
    for k, ((y0, z0), (y1, z1)) in enumerate(path):
        w = 0.7 if k % 2 == 0 else 0.55
        d = 0 if k % 2 == 0 else 0.1
        m.box("tail", (8 - w, y0 + d, z0 + d), (8 + w, y1 - d, z1 - d), skin if k % 2 == 0 else skin_dark)
    # the spade: a heart, point down into the tail's end
    rows = [".hh.hh.",
            "hhhhhhh",
            "hhhhhhh",
            ".hhhhh.",
            "..hhh..",
            "...h..."]
    u = 0.75
    for r, row in enumerate(rows):
        x = 0
        while x < len(row):
            if row[x] != "h":
                x += 1
                continue
            e = x
            while e < len(row) and row[e] == "h":
                e += 1
            y_top = 10.75 + (len(rows) - r) * u
            m.box("spade", (8 - 3.5 * u + x * u, y_top - u, 16.5), (8 - 3.5 * u + e * u, y_top, 17.25), heart)
            x = e
    _display(m, TAIL_GUI, 0.6, -3)
    return m


def mermaid_tail():
    """인어 꼬리지느러미: a teal scaled tail sweeping back and down into a wide, pale see-through fluke."""
    m = MModel("mermaid_tail")
    scales, scales_dark = Mat("celadon", "speckle", 2), Mat("glow_cyan", "plain", 1)
    fin, fin_edge = Mat("pearl", "plain", 3), Mat("glow_cyan", "plain", 3)
    segs = [(2.0, 4, 8.5, 8, 11), (1.75, 2.25, 6.5, 10.75, 13.75), (1.4, 0.75, 4.5, 13.5, 16.25), (1.0, -0.25, 2.75, 16, 18.25)]
    for k, (hw, y0, y1, z0, z1) in enumerate(segs):
        m.box("tail", (8 - hw, y0, z0), (8 + hw, y1, z1), scales if k % 2 == 0 else scales_dark)
    # the fluke: two lobes spreading sideways from the tail's end, raised at the tips
    m.box("fluke", (6.5, 0.5, 18.25), (9.5, 2.25, 19.75), fin)
    mirror(m, "fluke", (9.5, 0.75, 18.5), (12, 2, 20.5), fin)
    mirror(m, "fluke", (11.5, 1.5, 19.75), (13.5, 3.25, 21.25), fin_edge)
    mirror(m, "fluke", (9.5, 0.4, 20.5), (11.5, 1.25, 21.5), fin_edge)
    # a small side fin halfway down
    mirror(m, "fin", (9.75, 3, 12), (10.75, 5.25, 13.5), fin)
    _display(m, TAIL_GUI, 0.6, 0)
    return m


# ================================================================================================
# 목 (round the neck: the collar band at y 7.6..9.6, things hanging on the chest in front of z 6.1)
# ================================================================================================

def _collar(m, name, mat, y0=7.75, y1=9.25, out=0.5, front_gap=None):
    """A band round the top of the body under the head: x 4.25 - out .. 11.75 + out, z 6.1 - out .. 9.9 + out.
    front_gap: (x0, x1) left open on the front (where a knot, bow or pendant covers it)."""
    x0, x1, z0, z1 = 4.25 - out, 11.75 + out, 6.1 - out, 9.9 + out
    if front_gap:
        m.box(name, (x0, y0, z0), (front_gap[0], y1, 6.1), mat)
        m.box(name, (front_gap[1], y0, z0), (x1, y1, 6.1), mat)
    else:
        m.box(name, (x0, y0, z0), (x1, y1, 6.1), mat)
    m.box(name, (x0, y0, 9.9), (x1, y1, z1), mat)
    m.box(name, (x0, y0, 6.1), (4.25, y1, 9.9), mat)
    m.box(name, (11.75, y0, 6.1), (x1, y1, 9.9), mat)


def red_scarf():
    """빨간 목도리: a knitted red scarf wrapped twice round the neck, one end hanging down the chest with a fringe."""
    m = MModel("red_scarf")
    knit, knit_dark, stripe = Mat("fabric_red", "weave", 2), Mat("fabric_red", "weave", 1), Mat("feather", "plain", 3)
    _collar(m, "wrap", knit, 7.5, 9.5, out=0.75)
    _collar(m, "wrap", knit_dark, 6.75, 7.5, out=0.6)
    # the end hanging on the chest (the wearer's right), slightly off the body
    m.box("end", (5.25, 1.5, 4.85), (8.25, 7.5, 5.35), knit)
    m.box("stripe", (5.25, 3.25, 4.6), (8.25, 4, 4.85), stripe, faces={"south": None})
    for x in (5.4, 6.4, 7.4):
        m.box("fringe", (x, 0.25, 4.9), (x + 0.6, 1.5, 5.3), knit_dark)
    m.box("knot", (5, 6.75, 4.6), (8.5, 8.25, 5.45), knit_dark)
    _display(m, NECK_GUI, 1.0, -4)
    return m


def bow_tie():
    """나비넥타이: a crisp white collar edge and a red bow tie with a knot, at the front of the neck."""
    m = MModel("bow_tie")
    collar, bow, bow_dark = Mat("feather", "plain", 3), Mat("fabric_red", "plain", 2), Mat("fabric_red", "plain", 1)
    _collar(m, "collar", collar, 8.25, 9.25, out=0.35, front_gap=(7.25, 8.75))
    m.box("knot", (7.25, 7.25, 5.0), (8.75, 8.75, 5.75), bow_dark)
    mirror(m, "wing", (8.75, 6.75, 5.2), (10.75, 9.25, 5.75), bow)
    mirror(m, "wing_tip", (10.75, 7.25, 5.3), (11.25, 8.75, 5.75), bow_dark)
    _display(m, NECK_GUI, 1.5, -6)
    return m


def bell_collar():
    """방울 목걸이: a soft red collar with a gold bell (a slit and a little ball) hanging at the front."""
    m = MModel("bell_collar")
    band, buckle = Mat("crimson", "plain", 2), GOLD_LIGHT
    _collar(m, "collar", band, 8.0, 9.25, out=0.4)
    m.box("ring", (7.6, 7.25, 5.2), (8.4, 8.0, 5.7), GOLD)
    m.box("bell", (6.75, 4.75, 4.5), (9.25, 7.25, 5.6), buckle)
    m.box("bell", (7.0, 5.0, 4.2), (9.0, 7.0, 4.5), GOLD, faces={"south": None})
    m.box("slit", (7.85, 5.0, 4.15), (8.15, 6.0, 4.2), EYE, faces={"south": None})
    m.box("rim", (6.5, 4.5, 4.35), (9.5, 5.0, 5.55), GOLD)
    _display(m, NECK_GUI, 1.6, -6)
    return m


def pearl_necklace():
    """진주 목걸이: a single string of round pearls dipping in a U on the chest, a bigger drop pearl at the bottom."""
    m = MModel("pearl_necklace")
    pearl, pearl_lit = Mat("pearl", "plain", 3, noise=0), Mat("snow", "plain", 3, noise=0)
    # behind and at the sides: a thin string of small pearls on the collar line
    _collar(m, "string", pearl, 8.5, 9.1, out=0.3, front_gap=(4.0, 12.0))
    # the front U: pearls stepping down to the middle, mirrored
    beads = [(4.3, 8.3), (5.2, 7.2), (6.1, 6.3), (7.05, 5.75)]
    for (x, y) in beads:
        mirror(m, "pearl", (x, y, 5.3), (x + 0.9, y + 0.9, 6.1), pearl)
    m.box("drop", (7.4, 4.25, 5.1), (8.6, 5.75, 6.1), pearl_lit)
    _display(m, NECK_GUI, 1.4, -5)
    return m


def flower_lei():
    """꽃목걸이: a ring of big tropical flowers (pink, yellow, white) round the neck, dipping down the chest."""
    m = MModel("flower_lei")
    pink, yellow, white = Mat("sakura", "plain", 2), Mat("petal_yellow", "plain", 2), Mat("feather", "plain", 3)
    centre = Mat("fire", "plain", 3)
    leaf = Mat("leaf", "plain", 2)
    cols = [pink, yellow, white]
    # back and sides: flowers as chunky blocks on the collar
    k = 0
    for (a, b) in ((3.5, 5.5), (5.5, 7.5), (7.5, 9.5), (9.5, 11.5), (11.5, 13.5)):
        m.box("lei", (a, 7.25, 9.9), (b, 9.5, 10.75), cols[k % 3])
        k += 1
    for (a, b) in ((6.1, 8.0), (8.0, 9.9)):
        m.box("lei", (3.25, 7.25, a), (4.25, 9.5, b), cols[k % 3])
        m.box("lei", (11.75, 7.25, a), (12.75, 9.5, b), cols[(k + 1) % 3])
        k += 2
    # the front: a dip of flowers with yellow centres and a few leaves
    # side by side down to the middle (no two overlap: they only touch), each a little deeper than the last
    front = [(3.75, 5.5, 7.5, pink, 5.05), (5.5, 7.25, 6.0, yellow, 4.95)]
    for (x0, x1, y, mat, z0) in front:
        mirror(m, "flower", (x0, y, z0), (x1, y + 1.75, 6.05), mat)
        cx = (x0 + x1) / 2
        mirror(m, "centre", (cx - 0.3, y + 0.6, z0 - 0.2), (cx + 0.3, y + 1.15, z0), centre, faces={"south": None})
    m.box("flower", (7.25, 4.5, 4.85), (8.75, 6.25, 6.0), white)
    m.box("centre", (7.7, 5.1, 4.65), (8.3, 5.65, 4.85), centre, faces={"south": None})
    mirror(m, "leaf", (5.0, 4.75, 5.4), (5.75, 5.75, 5.9), leaf)
    _display(m, NECK_GUI, 1.1, -4)
    return m


def silk_scarf():
    """스카프: a blue silk neckerchief with white polka dots, knotted at the side, its point hanging on the chest."""
    m = MModel("silk_scarf")
    silk, silk_dark = Mat("sapphire", "plain", 2), Mat("sapphire", "plain", 1)

    _collar(m, "band", silk_dark, 8.0, 9.4, out=0.45)
    # the triangle on the chest: rows narrowing downwards
    rows = [(4.75, 11.25), (5.5, 10.5), (6.25, 9.75), (7.0, 9.0), (7.6, 8.4)]
    y = 8.0
    for (a, b) in rows:
        m.box("point", (a, y - 1.0, 5.2), (b, y, 5.65), silk)
        y -= 1.0
    for (x, yy) in ((5.75, 7.4), (7.5, 7.4), (9.75, 7.4), (6.6, 6.4), (8.9, 6.4), (7.75, 5.4)):
        m.box("dot", (x, yy - 0.5, 5.05), (x + 0.5, yy, 5.2), WHITE, faces={"south": None})
    # the knot at the wearer's left with two short ends
    m.box("knot", (10.75, 7.5, 4.9), (12.25, 9.0, 6.0), silk_dark)
    m.box("end", (11.25, 5.5, 5.1), (12.0, 7.5, 5.7), silk)
    m.box("end", (12.0, 6.0, 5.2), (12.75, 7.5, 5.75), silk_dark)
    _display(m, NECK_GUI, 1.1, -4)
    return m


def gold_medal():
    """금메달: a red-and-white ribbon in a V round the neck and a big gold medal with a raised, glowing star."""
    m = MModel("gold_medal")
    red, white = Mat("fabric_red", "plain", 2), Mat("feather", "plain", 3)
    star = Mat("lamp", "plain", 3, glow=True, noise=0)
    _collar(m, "ribbon", red, 8.25, 9.25, out=0.35, front_gap=(5.25, 10.75))
    # the V of the ribbon on the chest: two strips stepping in to the medal, a white stripe down each
    for k, (x, y) in enumerate(((4.5, 7.75), (5.5, 6.75), (6.5, 5.75))):
        mirror(m, "ribbon", (x, y, 5.45), (x + 1.0, y + 1.0, 6.0), red)
        mirror(m, "stripe", (x + 0.35, y, 5.3), (x + 0.65, y + 1.0, 5.45), white, faces={"south": None})
    m.box("ring", (7.5, 5.0, 5.3), (8.5, 5.75, 5.9), GOLD)
    m.box("medal", (6.25, 1.75, 5.1), (9.75, 5.0, 5.9), GOLD_LIGHT)
    m.box("medal", (6.75, 1.25, 5.2), (9.25, 5.5, 5.8), GOLD)
    m.box("medal", (5.75, 2.25, 5.2), (10.25, 4.5, 5.8), GOLD)
    rows = ["..s..",
            ".sss.",
            "sssss",
            ".s.s."]
    u = 0.5
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            if ch == "s":
                m.box("star", (8 - 2.5 * u + c * u, 4.4 - (r + 1) * u, 4.95), (8 - 2.5 * u + (c + 1) * u, 4.4 - r * u, 5.1), star,
                      faces={"south": None})
    _display(m, NECK_GUI, 1.2, -4)
    return m


def crystal_pendant():
    """마법 수정 목걸이: a fine silver chain and a long glowing violet crystal set in a silver claw on the chest."""
    m = MModel("crystal_pendant")
    silver, silver_dark = Mat("steel", "metal", 3), Mat("steel", "metal", 2)
    gem, gem_core = Mat("magic", "plain", 3, glow=True, noise=0), Mat("crystal", "plain", 4, glow=True, noise=0)
    _collar(m, "chain", silver_dark, 8.6, 9.1, out=0.25, front_gap=(5.0, 11.0))
    for (x, y) in ((4.5, 8.1), (5.4, 7.2), (6.3, 6.3)):
        mirror(m, "chain", (x, y, 5.6), (x + 0.75, y + 0.75, 6.05), silver)
    m.box("bail", (7.5, 5.75, 5.4), (8.5, 6.75, 6.05), silver)
    m.box("claw", (7.0, 5.0, 5.2), (9.0, 5.75, 6.0), silver_dark)
    m.box("gem", (7.25, 2.25, 5.0), (8.75, 5.0, 5.95), gem)
    m.box("gem", (7.6, 1.25, 5.15), (8.4, 2.25, 5.8), gem)
    m.box("core", (7.65, 2.75, 4.85), (8.35, 4.5, 5.0), gem_core, faces={"south": None})
    mirror(m, "sparkle", (6.0, 3.0, 5.4), (6.4, 3.4, 5.8), gem_core)
    _display(m, NECK_GUI, 1.3, -4)
    return m


SLOTS = {"puppy_tail": "tail", "bunny_tail": "tail", "dino_tail": "tail", "raccoon_tail": "tail", "devil_tail": "tail",
         "mermaid_tail": "tail",
         "red_scarf": "neck", "bow_tie": "neck", "bell_collar": "neck", "pearl_necklace": "neck", "flower_lei": "neck",
         "silk_scarf": "neck", "gold_medal": "neck", "crystal_pendant": "neck"}

ALL = {f.__name__: f for f in (puppy_tail, bunny_tail, dino_tail, raccoon_tail, devil_tail, mermaid_tail,
                               red_scarf, bow_tie, bell_collar, pearl_necklace, flower_lei, silk_scarf, gold_medal,
                               crystal_pendant)}
