"""장신구 (accessories, docs/plans/장신구_시스템_기획안.md): the item icons of the five slots in the four grades, the box, the
fragment and the sea-palace chest (economy_goods.ICONS takes these in, so economy_build.py writes them to
economy/content/economy/textures/<name>.png), and the 장신구 menu button icons (economy_icons.GUI takes these in:
economy:gui/<name>).

Items: 16x16 on the pixelart Canvas like economy_goods.py (light from the top left, the darkest step as the outline).
Every slot keeps one silhouette through the grades; the grade is the metal and the stone, so it reads at a glance:

    보통 common      plain steel, no stone
    진품 fine        bright silver, a sapphire
    명품 masterwork  gold, an amethyst
    국보 treasure    gold, jade and ruby, more ornament, and the faint pale rim of light the legendary fish have

    ring 가락지 · necklace 노리개 on a bead chain · earring a pair of drops · belt 허리띠 with its buckle · bracelet a bangle

Menu buttons: the shared UI look (stsv_assets/ui_icons.py: a light two-grey glyph, a 1-px dark outline, colour only where it
means something), drawn with a local copy of its colours (the shared letters are never redefined).

    python3 accessory_art.py [preview.png]     # writes the PNGs (and the gui models) and, if given, a preview sheet
"""
import json
import sys
from pathlib import Path

from PIL import Image

from stsv_assets.pixelart import Canvas

SLOTS = ("ring", "necklace", "earring", "belt", "bracelet")
GRADES = ("common", "fine", "masterwork", "treasure")
SLOT_NAMES = {"ring": "반지", "necklace": "목걸이", "earring": "귀걸이", "belt": "허리띠", "bracelet": "팔찌"}
GRADE_NAMES = {"common": "보통", "fine": "진품", "masterwork": "명품", "treasure": "국보"}

# grade -> metal, stone, second stone, cord (tassels), strap (the belt's cloth)
STYLE = {
    "common":     dict(metal="steel", gem=None, accent=None, cord="burlap", strap="leather"),
    "fine":       dict(metal="fish_silver", gem="sapphire", accent=None, cord="indigo", strap="indigo"),
    "masterwork": dict(metal="gold", gem="crystal", accent=None, cord="petal_purple", strap="wine"),
    "treasure":   dict(metal="gold", gem="celadon", accent="ruby", cord="fabric_red", strap="fabric_red"),
}
GLOW = "#fff0a0"      # 국보: the pale rim of light (economy_fish / fish_art glowing fish, alpha 110)

ICONS = {}


def glow(im, hexcol=GLOW, alpha=110):
    """A faint pale rim of light round the outline (the same rim the legendary fish have)."""
    px = im.load()
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    r, g, b = (int(hexcol[i:i + 2], 16) for i in (1, 3, 5))
    for y in range(16):
        for x in range(16):
            if px[x, y][3] == 0 and any(0 <= x + dx < 16 and 0 <= y + dy < 16 and px[x + dx, y + dy][3] > 0
                                        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                out.putpixel((x, y), (r, g, b, alpha))
    out.alpha_composite(im)
    return out


def _rim(c, pts):
    """Darkens the band pixels round a hole (its inner wall), so the hole reads even on a grey slot."""
    holes = {(x, y) for x in range(16) for y in range(16) if (x, y) not in c.px}
    for x, y in pts:
        if (x, y) in c.px and any((x + dx, y + dy) in holes for dx, dy in ((0, 1), (0, -1), (1, 0), (-1, 0))) \
                and 1 <= x <= 14 and 1 <= y <= 14:
            inner = [(x + dx, y + dy) for dx, dy in ((0, 1), (0, -1), (1, 0), (-1, 0)) if (x + dx, y + dy) in holes]
            if inner and all(_enclosed(c, q) for q in inner):
                c.px[(x, y)][1] = 0 if y < 10 else 1
    return c


def _enclosed(c, q):
    """True when the empty pixel q is shut in by the shape (a ring's hole), not part of the outside."""
    seen, todo = set(), [q]
    while todo:
        p = todo.pop()
        if p in seen or p in c.px:
            continue
        if not (0 <= p[0] < 16 and 0 <= p[1] < 16) or p[0] in (0, 15) or p[1] in (0, 15):
            return False
        seen.add(p)
        todo += [(p[0] + 1, p[1]), (p[0] - 1, p[1]), (p[0], p[1] + 1), (p[0], p[1] - 1)]
    return True


def _stone(c, cx, cy, rx, ry, mat, z, facet=False):
    """A cabochon (or a faceted stone) with its glint at the top left."""
    if facet:
        c.poly([(cx - rx, cy - ry * 0.3), (cx - rx * 0.5, cy - ry), (cx + rx * 0.5, cy - ry), (cx + rx, cy - ry * 0.3),
                (cx, cy + ry)], mat, z, normal=(-0.25, -0.2, 1), lo=1, hi=4)
        c.poly([(cx, cy - ry * 0.3), (cx + rx, cy - ry * 0.3), (cx, cy + ry)], mat, z + 0.05, normal=(0.6, 0.3, 0.7), lo=1, hi=2)
        c.put(round(cx - rx * 0.4), round(cy - ry * 0.55), "snow", 1, z=z + 1)
    else:
        c.ellipse(cx, cy, rx, ry, mat, z, lo=2, hi=4, gloss=0.8)
        c.put(round(cx - rx * 0.45), round(cy - ry * 0.45), mat, 5, z=z + 1)
    return c


# ================================================================================================
# The five slots
# ================================================================================================

def ring(grade):
    """가락지: a thick band seen at an angle, its setting on top (a plain boss on the common one)."""
    s = STYLE[grade]
    m = s["metal"]
    c = Canvas()
    c.ellipse(8.0, 9.8, 5.2, 4.0, m, 1, lo=1, hi=4)
    band = c.last()
    c.hole(8.0, 10.1, 3.5, 2.5)
    _rim(c, band)
    c.dots([(4, 9), (4, 10)], m, 4, z=2)                              # light on the band's left side
    if grade == "common":
        c.ellipse(8.0, 5.6, 2.0, 1.4, m, 2, lo=2, hi=5)              # a plain flat boss
        c.dots([(5, 7), (11, 7)], m, 2, z=2)
        return c
    c.ellipse(8.0, 5.8, 2.6, 1.6, m, 2, lo=1, hi=4)                   # the collet
    if grade == "fine":
        _stone(c, 8.0, 4.6, 1.8, 1.6, s["gem"], 3)
    elif grade == "masterwork":
        _stone(c, 8.0, 4.2, 2.6, 2.2, s["gem"], 3, facet=True)
        c.dots([(5, 4), (10, 4)], m, 4, z=4)                         # prongs
    else:
        _stone(c, 8.0, 4.2, 2.4, 1.9, s["gem"], 3)                   # the jade cabochon
        c.dots([(5, 3), (10, 3)], m, 5, z=4)
        for x in (4.2, 11.8):                                          # rubies on the shoulders
            c.ellipse(x, 6.6, 0.95, 0.9, s["accent"], 4, lo=2, hi=5)
        c.tone([(x, y) for x, y in band if y >= 12 and x % 2 == 0], -1)   # chased pattern on the band
    return c


def necklace(grade):
    """노리개 목걸이: a bead chain hanging in a U, a pendant at the bottom and a tassel under it."""
    s = STYLE[grade]
    m = s["metal"]
    c = Canvas()
    pts = [(2, 2), (2, 3), (3, 4), (3, 5), (4, 6), (5, 7), (6, 7), (10, 7), (11, 7), (12, 6), (13, 5), (13, 4), (14, 3), (14, 2)]
    for k, (x, y) in enumerate(pts):
        bead = s["gem"] if grade in ("masterwork", "treasure") and k % 3 == 1 else m
        if grade == "treasure" and k % 3 == 1:
            bead = "pearl"
        c.put(x, y, bead, 4 if k % 2 == 0 else 3, z=1)
    # the tassel (술) first so the pendant sits on it
    c.rect(6.6, 11.2, 9.4, 14.0, s["cord"], 1, normal=(-0.2, 0, 1), lo=1, hi=3)
    c.dots([(7, 12), (7, 13)], s["cord"], 4, z=2)
    c.rect(7.0, 10.6, 9.0, 11.6, s["cord"], 2, normal=(0, -0.5, 1), lo=2, hi=4)    # the knot
    if grade == "common":
        c.ellipse(8.0, 8.8, 2.4, 2.1, m, 3, lo=1, hi=4)               # a plain metal disc
        c.hole(8.0, 8.8, 0.6, 0.6)
    elif grade == "fine":
        c.ellipse(8.0, 8.8, 2.5, 2.2, m, 3, lo=1, hi=4)
        _stone(c, 8.0, 8.8, 1.4, 1.3, s["gem"], 4)
    elif grade == "masterwork":
        c.rect(7.0, 7.0, 9.0, 8.0, m, 3, normal=(0, -0.4, 1), lo=2, hi=4)
        c.poly([(5.6, 8.0), (10.4, 8.0), (9.6, 10.0), (8.0, 11.4), (6.4, 10.0)], s["gem"], 4, normal=(-0.2, -0.1, 1), lo=1, hi=4)
        c.poly([(8.0, 8.0), (10.4, 8.0), (9.6, 10.0), (8.0, 11.4)], s["gem"], 4.1, normal=(0.7, 0, 0.6), lo=1, hi=2)
        c.put(7, 8, "snow", 1, z=5)
    else:
        c.rect(7.0, 6.6, 9.0, 7.8, m, 3, normal=(0, -0.4, 1), lo=2, hi=4)      # the gold cap
        c.ellipse(8.0, 9.2, 3.2, 2.0, s["gem"], 4, lo=2, hi=4)                # a jade butterfly plaque
        c.ellipse(5.4, 8.6, 1.1, 1.0, s["gem"], 4.1, lo=2, hi=4)
        c.ellipse(10.6, 8.6, 1.1, 1.0, s["gem"], 4.1, lo=1, hi=3)
        c.line(8, 8, 8, 10, "gold", 3, z=5)
        c.put(8, 9, s["accent"], 4, z=6)
        c.put(6, 8, s["gem"], 5, z=6)
    return c


def earring(grade):
    """귀걸이: a pair of drop earrings, each a gold or silver hook, a bead and a hanging stone."""
    s = STYLE[grade]
    m = s["metal"]
    c = Canvas()
    for k, (x0, y0) in enumerate(((3.8, 2.6), (9.8, 4.6))):
        z = k * 10
        cx, ty = round(x0 + 1.2), round(y0 - 0.6)
        for i, (x, y) in enumerate(((cx - 2, ty + 2), (cx - 2, ty + 1), (cx - 1, ty), (cx, ty), (cx, ty + 1))):
            c.put(x, y, m, 4 if i < 3 else 3, z=z + 1)                # the ear wire, a little hook
        c.ellipse(x0 + 1.2, y0 + 1.6, 0.9, 0.9, s["accent"] or m, z + 2, lo=2, hi=5)   # the bead under the ear
        if grade == "common":
            c.ellipse(x0 + 1.2, y0 + 4.6, 1.5, 2.0, m, z + 3, lo=1, hi=4)               # a plain metal drop
            c.hole(x0 + 1.2, y0 + 4.6, 0.55, 0.9)
        elif grade == "fine":
            c.ellipse(x0 + 1.2, y0 + 3.0, 0.7, 0.7, m, z + 2.5, lo=2, hi=4)
            _stone(c, x0 + 1.2, y0 + 5.0, 1.5, 1.8, s["gem"], z + 3)
        elif grade == "masterwork":
            c.rect(x0 + 0.4, y0 + 2.4, x0 + 2.0, y0 + 3.4, m, z + 2.5, normal=(0, -0.4, 1), lo=2, hi=4)
            c.poly([(x0 - 0.6, y0 + 3.4), (x0 + 3.0, y0 + 3.4), (x0 + 2.4, y0 + 5.6), (x0 + 1.2, y0 + 7.6), (x0, y0 + 5.6)],
                   s["gem"], z + 3, normal=(-0.2, -0.1, 1), lo=1, hi=4)
            c.put(round(x0), round(y0 + 4), "snow", 1, z=z + 5)
        else:
            c.ellipse(x0 + 1.2, y0 + 4.8, 1.7, 1.9, s["gem"], z + 3, lo=2, hi=4)         # a jade drop in a gold cap
            c.rect(x0 + 0.4, y0 + 2.4, x0 + 2.0, y0 + 3.4, m, z + 4, normal=(0, -0.4, 1), lo=2, hi=5)
            c.put(round(x0 + 0.6), round(y0 + 4.2), s["gem"], 5, z=z + 5)
            c.line(round(x0 + 1.2), round(y0 + 6.8), round(x0 + 1.2), round(y0 + 8.0), s["cord"], 3, z=z + 2)   # a tiny red tassel
    return c


def belt(grade):
    """허리띠: a strap lying across with a big buckle in the middle; 국보 is a 옥대 with jade plaques."""
    s = STYLE[grade]
    m = s["metal"]
    c = Canvas()
    c.poly([(1.6, 6.6), (14.4, 5.6), (14.4, 10.0), (1.6, 11.0)], s["strap"], 0, normal=(-0.1, -0.35, 1), lo=1, hi=4)
    strap = c.last()
    c.tone([(x, y) for x, y in strap if x % 2 == 0 and y in (7, 9) and not 5 <= x <= 10], 1)          # the stitching
    # plaques along the strap (studs on the plain ones)
    for x in (3.0, 13.0):
        y = 8.8 - (x - 2) * 0.08
        if grade == "treasure":
            c.rect(x - 1.0, y - 1.4, x + 1.0, y + 1.4, m, 1, normal=(-0.2, -0.3, 1), lo=2, hi=4)
            c.rect(x - 0.5, y - 0.8, x + 0.5, y + 0.8, s["gem"], 2, normal=(-0.2, -0.3, 1), lo=2, hi=4)
        elif grade == "masterwork":
            c.rect(x - 0.8, y - 1.0, x + 0.8, y + 1.0, m, 1, normal=(-0.2, -0.3, 1), lo=2, hi=4)
        else:
            c.put(round(x), round(y - 0.4), m, 4, z=1)
    # the buckle frame
    for (x0, y0, x1, y1) in ((5.0, 3.6, 11.0, 5.0), (5.0, 11.2, 11.0, 12.6), (5.0, 3.6, 6.6, 12.6), (9.4, 3.6, 11.0, 12.6)):
        c.rect(x0, y0, x1, y1, m, 3, normal=(-0.25, -0.3, 1), lo=1, hi=4)
    c.put(6, 5, m, 5, z=4)
    if grade == "common":
        c.rect(7.4, 5.6, 8.6, 11.0, m, 2, normal=(-0.3, 0, 1), lo=1, hi=3)        # the tongue across the opening
    else:
        _stone(c, 8.0, 8.1, 1.1, 1.7, s["gem"], 4, facet=(grade == "masterwork"))
        if grade == "treasure":
            c.dots([(6, 12), (10, 4)], s["accent"], 4, z=5)
            c.ellipse(8.0, 3.2, 1.0, 0.9, s["accent"], 5, lo=2, hi=5)              # a ruby on the buckle's crown
    return c


def bracelet(grade):
    """팔찌: a wide bangle seen from above at an angle, stones set round its front."""
    s = STYLE[grade]
    m = s["metal"]
    c = Canvas()
    c.ellipse(8.0, 9.6, 6.4, 4.0, m, 1, lo=1, hi=4)
    band = c.last()
    c.hole(8.0, 8.7, 4.5, 2.0)
    _rim(c, band)
    c.tone([(x, y) for x, y in band if y <= 7], -1)                         # the far side of the bangle is in shadow
    c.line(3, 10, 5, 11, m, 5, z=2)
    if grade == "common":
        c.tone([(x, y) for x, y in band if y == 11 and x % 2 == 1], -1)      # a plain engraved line
        return c
    if grade == "fine":
        _stone(c, 8.0, 11.6, 1.4, 1.2, s["gem"], 3)
    elif grade == "masterwork":
        for x in (4.8, 8.0, 11.2):
            _stone(c, x, 11.4 + (0.4 if x == 8.0 else 0), 1.2, 1.0, s["gem"], 3 + x * 0.01)
    else:
        for x in (4.6, 11.4):
            _stone(c, x, 11.2, 1.2, 1.0, s["gem"], 3 + x * 0.01)
        c.ellipse(8.0, 11.8, 1.5, 1.2, s["accent"], 3.5, lo=2, hi=5)
        c.dots([(3, 9), (13, 9), (6, 13), (10, 13)], s["accent"], 3, z=4)
    return c


SHAPES = {"ring": ring, "necklace": necklace, "earring": earring, "belt": belt, "bracelet": bracelet}


def _accessory(slot, grade):
    im = SHAPES[slot](grade).render()
    return glow(im) if grade == "treasure" else im


for _slot in SLOTS:
    for _grade in GRADES:
        ICONS[f"acc_{_slot}_{_grade}"] = (lambda s=_slot, g=_grade: _accessory(s, g))


# ================================================================================================
# 장신구 상자 · 장신구 조각 · 용궁 상자
# ================================================================================================

def acc_box():
    """장신구 상자: a small closed black-lacquer box with a domed lid, mother-of-pearl flowers, brass corners and a lock."""
    c = Canvas()
    c.rect(2.0, 7.0, 14.0, 14.0, "obsidian", 0, normal=(0, 0.1, 1), lo=1, hi=3)                     # the body
    c.rect(1.6, 3.4, 14.4, 7.2, "obsidian", 1, normal=(-0.1, -0.7, 0.7), lo=1, hi=4)                # the domed lid
    lid = c.last()
    c.lit_edge(lid, 1)
    c.line(2, 7, 13, 7, "gold", 2, z=2)                                                             # the seam
    for (x, y), mt in zip(((4, 5), (5, 4), (3, 5), (11, 5), (12, 4), (10, 6), (7, 4)),
                          ("pearl", "glow_cyan", "pink", "pearl", "petal_purple", "glow_cyan", "pearl")):
        c.put(x, y, mt, 3, z=2)
    for (x, y), mt in zip(((4, 10), (3, 11), (5, 11), (4, 12), (12, 10), (11, 11), (13, 11), (12, 12), (4, 11), (12, 11)),
                          ("pearl", "glow_cyan", "pink", "glow_cyan", "pearl", "pink", "glow_cyan", "petal_purple", "petal_yellow", "petal_yellow")):
        c.put(x, y, mt, 3, z=2)                                                                       # two inlaid flowers
    c.put(4, 11, "lamp", 4, z=3).put(12, 11, "lamp", 4, z=3)
    for x0 in (1.6, 12.6):                                                                          # brass corners
        c.rect(x0, 12.4, x0 + 1.8, 14.2, "gold", 2, normal=(-0.2, -0.2, 1), lo=2, hi=4)
    c.rect(6.6, 6.2, 9.4, 10.6, "gold", 3, normal=(-0.2, -0.3, 1), lo=2, hi=4)                       # the lock plate
    c.put(8, 8, "soot", 0, z=4).put(8, 9, "soot", 1, z=4)
    c.put(7, 7, "gold", 5, z=4)
    return c


def acc_fragment():
    """장신구 조각: a broken shard of a cut stone, a bit of gold setting still on its edge, glinting."""
    c = Canvas()
    c.poly([(4.0, 6.0), (9.4, 2.4), (13.0, 5.0), (11.4, 10.0), (7.6, 13.4), (5.2, 11.0), (6.4, 8.4)], "crystal", 1,
           normal=(-0.25, -0.3, 1), lo=1, hi=4)
    c.poly([(9.4, 2.4), (13.0, 5.0), (11.4, 10.0), (9.0, 7.0)], "crystal", 1.1, normal=(0.6, -0.2, 0.7), lo=2, hi=3)
    c.poly([(5.2, 11.0), (7.6, 13.4), (11.4, 10.0), (9.0, 7.0), (6.4, 8.4)], "crystal", 1.2, normal=(0.2, 0.6, 0.7), lo=1, hi=2)
    c.line(7, 4, 9, 7, "crystal", 5, z=3)                                                    # the facet edge
    c.put(6, 6, "snow", 1, z=3)
    c.tube([(3.0, 7.0), (4.4, 5.4), (6.8, 3.8)], [0.75, 0.8, 0.7], "gold", 2, lo=2, hi=4)   # the bit of setting
    c.put(4, 6, "gold", 5, z=4)
    c.dots([(12, 1), (14, 8), (2, 12)], "lamp", 5)                                           # sparkles
    c.dots([(11, 1), (13, 1), (12, 0)], "lamp", 3)
    return c


def dragon_chest():
    """용궁 상자: a sea-palace chest of wet deep-blue wood with gold bands, red coral on its shoulder, a pearl on the lid."""
    c = Canvas()
    c.rect(1.6, 7.6, 14.4, 14.4, "police_blue", 0, normal=(-0.1, 0.1, 1), lo=1, hi=3)                  # the body
    body = c.last()
    c.cyl(1.6, 3.0, 14.4, 7.8, "police_blue", 1, lo=1, hi=4, rounded=0.6)                              # the rounded lid
    c.tone([(x, y) for x, y in body if y == 11], -1)                                                     # the plank seam
    for x0 in (3.4, 11.0):                                                                              # gold bands
        c.rect(x0, 3.0, x0 + 1.6, 14.4, "gold", 2, normal=(-0.2, -0.2, 1), lo=1, hi=4)
    c.line(2, 8, 13, 8, "gold", 2, z=2)
    c.rect(6.8, 7.0, 9.2, 10.4, "gold", 3, normal=(-0.2, -0.3, 1), lo=2, hi=4)                          # the clasp
    c.ellipse(8.0, 8.4, 1.0, 1.0, "pearl", 4, lo=2, hi=5)                                              # a pearl in it
    c.ellipse(8.0, 2.8, 1.6, 1.5, "pearl", 3, lo=2, hi=5)                                              # a big pearl on the lid
    c.tube([(2.0, 8.0), (2.6, 4.6), (1.8, 1.6)], [0.9, 0.7, 0.5], "vermilion", 4, lo=2, hi=4)         # red coral on the shoulder
    c.tube([(2.4, 5.6), (5.0, 3.6), (5.4, 1.6)], [0.6, 0.55, 0.45], "vermilion", 4.1, lo=2, hi=4)
    c.dots([(5, 2), (2, 2)], "salmon", 5, z=5)
    c.tube([(13.4, 14.0), (14.0, 11.0)], [0.6, 0.5], "leaf_dark", 4, lo=2, hi=4)                       # a strand of seaweed
    c.dots([(5, 13), (9, 13), (13, 9)], "water", 5, z=5)                                                # drips and wet glints
    c.dots([(6, 4), (12, 5)], "ice", 5, z=5)
    return c


for _fn in (acc_box, acc_fragment, dragon_chest):
    ICONS[_fn.__name__] = (lambda f=_fn: f().render())

NAMES = {f"acc_{s}_{g}": f"{GRADE_NAMES[g]} {SLOT_NAMES[s]}" for s in SLOTS for g in GRADES}
NAMES.update({"acc_box": "장신구 상자", "acc_fragment": "장신구 조각", "dragon_chest": "용궁 상자"})


# ================================================================================================
# Menu buttons (economy:gui/<name>): the ui_icons.py look
# ================================================================================================

from stsv_assets import ui_icons as _UI   # noqa: E402

_COLOURS = dict(_UI.COLOURS)               # a local copy; the shared letters stay as they are
_COLOURS.update({
    "p": (190, 128, 250), "P": (122, 70, 186),         # amethyst (only here)
    "y": (255, 240, 160),                              # a spark
})


def _draw(rows):
    rows = [r.ljust(16, ".")[:16] for r in rows] + ["." * 16] * (16 - len(rows))
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    filled = {(x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch != "."}
    for (x, y) in filled:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q not in filled and 0 <= q[0] < 16 and 0 <= q[1] < 16:
                im.putpixel(q, _UI.OUTLINE + (255,))
    for (x, y) in filled:
        im.putpixel((x, y), _COLOURS[rows[y][x]] + (255,))
    return im


ENHANCE = [   # 강화: a smith's hammer, its head across the top and the handle down to the lower left, a gold spark where it strikes
    "",
    ".....########",
    "....##########s",
    ".....sssss#ssss",
    "........oO",
    ".......oO",
    "......oO",
    ".....oO.......g",
    "....oO.......gyg",
    "...oO......ggyyyg",
    "..oO.........gyg",
    ".oO...........g",
    "",
]
REFINE = [    # 제련: a cut stone being re-cut, the chisel's spark and a die beside it (the value rolled again)
    "",
    "...bbbbbb",
    "..bbbbbbbB",
    ".bbbbbbbbbB",
    ".bHbbbbbbBB",
    "..bHbbbbBB",
    "...bbbbBB",
    "....bbBB.y",
    ".....bB.yyy",
    "........y.#####",
    ".........#k###ks",
    ".........#######s",
    ".........###k###s",
    ".........#######s",
    ".........#k###ks",
    "..........ssssss",
]

REROLL_SCROLL = [   # 옵션 변경: the 의뢰 scroll of option lines (ui_icons.SCROLL, a little narrower) ...
    "",
    ".ssssssssssss",
    "s############s",
    ".ssssssssssss",
    "..##########s",
    "..#kkkkkkk##s",
    "..##########s",
    "..#kkkkk####s",
    "..##########s",
    "..#kkkk#####s",
    "..##########s",
    ".ssssssssssss",
    "s############s",
    ".ssssssssssss",
]
REROLL_ARROW = [    # ... with a gold turning arrow over its lower right corner (drawn as its own layer, outlined on the scroll)
    "", "", "", "", "", "", "",
    "..........ggg.g",
    ".........g...gg",
    "........g...ggg",
    "........g",
    "........g.....G",
    ".........G...G",
    "..........GGG",
]

SLOT_EMPTY = [  # an empty slot: a dashed rounded square
    "",
    "",
    "...##..##..##",
    "..s..........s",
    "..#..........#",
    "..#..........#",
    "..s..........s",
    "..............",
    "..#..........#",
    "..#..........#",
    "..s..........s",
    "..............",
    "..#..........#",
    "..s..........s",
    "...ss..ss..ss",
]

BAG = [       # 장신구 주머니: a red 복주머니 tied with a gold cord
    "",
    "......rr.rr",
    ".....rRr.rRr",
    "......rrrrR",
    ".....gggggG",
    "......rrrrR",
    "....rrrrrrrrR",
    "...rrrrrrrrrrR",
    "..rrrrrggrrrrrR",
    "..rrrrgGGgrrrrR",
    "..rrrrrggrrrrrR",
    "..rrrrrrrrrrrrR",
    "...rrrrrrrrrrR",
    "....RRRRRRRRR",
    "......g...g",
    "......gG..gG",
]


def _layers(*layers):
    """Glyphs drawn one over another, each with its own outline (so a glyph on top stays apart from the one under it)."""
    im = _draw(layers[0])
    for rows in layers[1:]:
        im.alpha_composite(_draw(rows))
    return im


def gui_icons():
    return {"acc_enhance": _draw(ENHANCE), "acc_refine": _draw(REFINE), "acc_reroll": _layers(REROLL_SCROLL, REROLL_ARROW),
            "acc_slot_empty": _draw(SLOT_EMPTY), "acc_bag": _draw(BAG)}


GUI = {name: (lambda n=name: gui_icons()[n]) for name in ("acc_enhance", "acc_refine", "acc_reroll", "acc_slot_empty", "acc_bag")}
GUI_NAMES = {"acc_enhance": "강화", "acc_refine": "제련", "acc_reroll": "옵션 변경", "acc_slot_empty": "빈 칸", "acc_bag": "장신구 주머니"}


# ================================================================================================
# Writing them out (economy_build.py does the same through economy_goods.ICONS / economy_icons.GUI) and the preview
# ================================================================================================

PACK = Path(__file__).resolve().parents[2] / "economy" / "content" / "economy"
NS = "economy"


def write():
    for name, fn in ICONS.items():
        fn().save(PACK / "textures" / f"{name}.png")
    assets = PACK / "assets" / NS
    for name, fn in GUI.items():
        fn().save(assets / "textures/item/gui" / f"{name}.png")
        (assets / "models/item/gui" / f"{name}.json").write_text(json.dumps(
            {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/gui/{name}"}}, indent=2))
        (assets / "items/gui" / f"{name}.json").write_text(json.dumps(
            {"model": {"type": "minecraft:model", "model": f"{NS}:item/gui/{name}"}}, indent=2))


def sheet(path, scale=6):
    """Slots in rows x grades in columns, then the box, fragment and chest, then the menu buttons (on the menu's charcoal)."""
    from PIL import ImageDraw, ImageFont
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 15)
        head = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 19)
    except OSError:
        font = head = ImageFont.load_default()
    cell = 16 * scale
    cw, ch = cell + 40, cell + 30
    left = 80
    im = Image.new("RGBA", (left + 5 * cw + 20, 60 + 5 * ch + 34 + ch + 34 + ch + 20), (46, 48, 56, 255))
    d = ImageDraw.Draw(im)

    def tile(x, y, ic, name, bg=(139, 139, 139, 255)):
        d.rectangle([x, y, x + cell, y + cell], fill=bg)
        im.alpha_composite(ic.resize((cell, cell), Image.NEAREST), (x, y))
        d.rectangle([x + cell + 2, y + cell - 33, x + cell + 33, y + cell - 1], fill=bg)
        im.alpha_composite(ic.resize((32, 32), Image.NEAREST), (x + cell + 2, y + cell - 33))
        d.text((x, y + cell + 4), name, fill=(240, 240, 240, 255), font=font)

    colours = {"common": (232, 232, 232), "fine": (90, 176, 255), "masterwork": (197, 140, 255), "treasure": (255, 211, 107)}
    d.text((14, 12), "장신구", fill=(255, 226, 150, 255), font=head)
    for j, g in enumerate(GRADES):
        d.text((left + j * cw, 40), GRADE_NAMES[g], fill=colours[g] + (255,), font=head)
    y = 66
    for s in SLOTS:
        d.text((14, y + cell // 2 - 10), SLOT_NAMES[s], fill=(240, 240, 240, 255), font=head)
        for j, g in enumerate(GRADES):
            tile(left + j * cw, y, ICONS[f"acc_{s}_{g}"](), f"acc_{s}_{g}"[4:])
        y += ch
    d.text((14, y + 6), "상자 · 조각", fill=(255, 226, 150, 255), font=head)
    y += 34
    for j, n in enumerate(("acc_box", "acc_fragment", "dragon_chest")):
        tile(left + j * cw, y, ICONS[n](), NAMES[n])
    y += ch
    d.text((14, y + 6), "메뉴 버튼 (economy:gui/…)", fill=(255, 226, 150, 255), font=head)
    y += 34
    for j, n in enumerate(GUI):
        tile(14 + j * cw, y, GUI[n](), GUI_NAMES[n], bg=(58, 60, 68, 255))
    y += ch
    im.crop((0, 0, im.width, y + 10)).save(path)


if __name__ == "__main__":
    write()
    print(f"accessory art: {len(ICONS)} item icons, {len(GUI)} menu buttons")
    if len(sys.argv) > 1:
        sheet(sys.argv[1])
