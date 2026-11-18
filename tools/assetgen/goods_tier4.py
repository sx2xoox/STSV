"""4세대 (4강) icons: the 4세대 materials (made only on a 4강 job machine, not bought by the shop: 승급 · 장신구 · 4세대 goods) and
the 4세대 goods (two per job, sold like every machine good). 16x16 on the same pixelart Canvas and palette materials as
economy_goods.py (light from the top left, the darkest step as the outline). economy_goods.ICONS takes these in."""
import math

from economy_goods import _bowl, _plate
from stsv_assets.pixelart import Canvas

ICONS = {}


def icon(fn):
    ICONS[fn.__name__] = lambda: fn().render()
    return fn


# ================================================================================================
# 4세대 materials
# ================================================================================================

@icon
def gem_dust():
    """보석 가루: a heap of glittering ruby-and-sapphire powder on a folded white paper, a few sparkles over it."""
    c = Canvas()
    c.poly([(0.6, 11.6), (8.2, 8.0), (15.4, 11.0), (8.0, 15.2)], "paper", 0, normal=(-0.1, -0.2, 1), lo=2, hi=4)
    c.line(8, 9, 8, 14, "paper", 2, z=1)                               # the fold
    c.ellipse(8.0, 10.2, 5.2, 3.6, "ruby", 1, lo=1, hi=4, dome=0.9)
    heap = c.last()
    blue = [(x, y) for x, y in heap if (x * 5 + y * 3) % 4 == 0]
    for x, y in blue:
        c.put(x, y, "sapphire", 3 if x + y < 17 else 2, z=2)
    c.dots([(x, y) for x, y in heap if (x * 3 + y * 7) % 9 == 0], "snow", 1, z=3)
    c.dots([(6, 8), (9, 7)], "ruby", 5, z=3)
    for x, y in ((3, 3), (12, 2), (14, 6)):                            # sparkles
        c.put(x, y, "lamp", 5).put(x - 1, y, "lamp", 3).put(x + 1, y, "lamp", 3).put(x, y - 1, "lamp", 3).put(x, y + 1, "lamp", 3)
    return c


@icon
def hanji():
    """한지: a stack of thick off-white mulberry sheets with ragged fibre edges, the top one curling, a red cord round them."""
    c = Canvas()
    for k in range(3):
        y = 12.6 - k * 1.6
        c.poly([(1.0, y - 3.0), (11.6, y - 5.6), (15.2, y - 1.8), (4.6, y + 1.0)], "paper", k, normal=(-0.15, -0.4, 1), lo=2, hi=4)
    top = c.last()
    c.tone([(x, y) for x, y in top if (x * 7 + y * 5) % 11 == 0], -1)      # the fibres in the sheet
    c.dots([(5, 6), (9, 5), (11, 8), (7, 9)], "cream", 2, z=4)
    c.tube([(10.4, 4.4), (13.4, 4.0), (14.6, 6.6)], [0.8, 0.9, 0.7], "paper", 3.5, lo=2, hi=5)   # the curling corner
    c.line(3, 7, 12, 12, "fabric_red", 2, z=5)
    c.dots([(7, 9), (8, 10)], "fabric_red", 4, z=6)
    c.dots([(1, 10), (2, 12), (15, 11)], "cream", 1, z=6)                   # ragged deckle edge
    return c


@icon
def fish_glue():
    """부레풀: a squat onggi pot of amber fish-bladder glue, a wooden spatula standing in it with a sticky drip down the side."""
    c = Canvas()
    c.ellipse(7.6, 11.0, 6.0, 4.2, "onggi", 1, lo=1, hi=4)
    c.rect(2.6, 6.2, 12.6, 9.4, "onggi", 1.5, normal=(-0.2, -0.3, 1), lo=2, hi=4)
    c.ellipse(7.6, 6.4, 5.0, 1.6, "onggi", 2, lo=1, hi=1, dome=0.0)
    c.ellipse(7.6, 6.2, 4.2, 1.3, "glow_amber", 3, lo=2, hi=5, dome=0.7)
    c.tube([(4.6, 7.0), (5.0, 10.6)], [0.7, 0.5], "glow_amber", 4, lo=2, hi=4)     # the drip
    c.tube([(9.0, 6.0), (13.6, 0.6)], [0.75, 0.6], "wood", 3, lo=1, hi=4)
    c.ellipse(9.2, 5.6, 1.3, 0.8, "glow_amber", 5, lo=3, hi=5)
    c.dots([(6, 6), (7, 5)], "snow", 2, z=6)
    c.line(2, 11, 13, 11, "onggi", 2, z=3)
    return c


@icon
def tempering_stone():
    """단련석: a long dark whetstone bound in a steel collar, flecked with meteoric silver and a set crystal that glows at its heart."""
    c = Canvas()
    c.poly([(1.0, 9.6), (11.6, 2.4), (15.0, 6.2), (4.4, 13.6)], "blacksteel", 0, normal=(-0.2, -0.5, 0.9), lo=1, hi=3)
    face = c.last()
    c.poly([(4.4, 13.6), (15.0, 6.2), (15.0, 7.8), (4.6, 15.2)], "blacksteel", 0.1, normal=(0.3, 0.7, 0.5), lo=1, hi=1)
    c.tone([(x, y) for x, y in face if (x * 5 + y * 3) % 7 == 0], -1)
    c.dots([(x, y) for x, y in face if (x * 3 + y * 11) % 13 == 0], "blade", 3, z=2)        # meteoric flecks
    c.poly([(5.4, 6.0), (7.6, 4.6), (10.8, 8.6), (8.6, 10.2)], "steel", 2, normal=(-0.3, -0.4, 0.9), lo=2, hi=4)  # the collar
    c.poly([(7.2, 6.4), (8.6, 5.6), (9.6, 7.6), (8.2, 8.4)], "glow_cyan", 3, normal=(-0.3, -0.3, 1), lo=2, hi=5)
    c.put(8, 6, "snow", 1, z=5)
    c.line(2, 9, 10, 3, "stone", 4, z=2)                                       # the honed edge catches the light
    c.dots([(13, 2), (14, 3), (2, 13)], "glow_cyan", 4)
    return c


@icon
def gosa_tteok():
    """고사떡: a square block of steamed 시루떡 seen from the corner, white rice layers between brown bean-powder layers, on a dish."""
    c = Canvas()
    _plate(c)
    # the block: top face (bean powder), left face and right face showing the layers
    c.poly([(2.0, 6.0), (8.0, 3.2), (14.0, 6.0), (8.0, 8.8)], "doenjang", 2, normal=(-0.1, -0.6, 0.8), lo=3, hi=4)
    top = c.last()
    c.dots([(x, y) for x, y in top if (x * 3 + y * 5) % 4 == 0], "bean", 2, z=3)
    c.poly([(2.0, 6.0), (8.0, 8.8), (8.0, 14.0), (2.0, 11.2)], "rice", 1, normal=(-0.7, 0.1, 0.7), lo=3, hi=4)
    c.poly([(8.0, 8.8), (14.0, 6.0), (14.0, 11.2), (8.0, 14.0)], "rice", 1, normal=(0.7, 0.2, 0.6), lo=1, hi=2)
    for y in (8.0, 10.4):                                    # the bean-powder layers in between
        for x in range(2, 14):
            yy = y + (x - 2) * 0.47 if x < 8 else y + (14 - x) * 0.47
            c.put(x, round(yy), "doenjang", 3 if x < 8 else 2, z=3)
    c.line(8, 9, 8, 13, "rice", 0, z=3)
    c.dots([(4, 6), (7, 4), (11, 5)], "doenjang", 5, z=4)
    return c


@icon
def mana_ink():
    """마력 잉크: a squat cut-glass ink pot of glowing deep-blue ink, a brush dipped in it, sparks rising from the ink."""
    c = Canvas()
    c.poly([(2.0, 9.0), (12.0, 9.0), (13.2, 14.8), (0.8, 14.8)], "glass", 0, normal=(-0.1, 0, 1), lo=2, hi=4)
    c.poly([(2.6, 10.0), (11.4, 10.0), (12.4, 14.2), (1.6, 14.2)], "sapphire", 1, normal=(-0.1, 0, 1), lo=1, hi=3)
    c.poly([(3.4, 10.4), (6.4, 10.4), (5.0, 13.6), (2.6, 13.6)], "magic", 2, normal=(-0.3, -0.2, 1), lo=2, hi=4)
    c.cyl(4.6, 6.6, 9.4, 9.2, "glass", 1, lo=2, hi=4)
    c.ellipse(7.0, 6.8, 2.0, 0.7, "magic", 2, lo=3, hi=5)
    c.tube([(7.6, 7.0), (13.4, 1.2)], [0.55, 0.7], "bamboo", 3, lo=1, hi=4)
    c.ellipse(7.4, 7.4, 1.0, 1.0, "soot", 4, lo=0, hi=2)
    c.dots([(4, 3), (6, 1), (10, 4)], "magic", 4)
    c.dots([(5, 4), (3, 2)], "glow_cyan", 3)
    c.dots([(2, 10), (2, 11)], "snow", 1, z=4)
    return c


@icon
def guard_charm():
    """보호 부적: a yellow paper talisman with red seal strokes in glowing ink, hung from a small tempering-stone bead and a red tassel."""
    c = Canvas()
    c.rect(4.0, 3.0, 12.0, 14.8, "petal_yellow", 1, normal=(-0.15, -0.1, 1), lo=2, hi=4)
    c.line(5, 4, 10, 4, "fabric_red", 2, z=3)                                 # the seal frame and strokes
    c.line(8, 5, 8, 12, "fabric_red", 2, z=3).line(6, 7, 10, 7, "fabric_red", 3, z=3)
    c.line(5, 9, 7, 11, "fabric_red", 2, z=3).line(11, 9, 9, 11, "fabric_red", 2, z=3)
    c.dots([(6, 13), (8, 13), (10, 13)], "fabric_red", 3, z=3)
    c.dots([(7, 6), (9, 8), (8, 11)], "magic", 4, z=4)                          # the mana ink glints in the strokes
    c.ellipse(8.0, 1.6, 1.8, 1.5, "blacksteel", 2, lo=1, hi=4)                 # the tempering-stone bead
    c.put(8, 1, "glow_cyan", 4, z=5)
    c.tube([(12.2, 13.0), (14.4, 15.4)], [0.6, 0.8], "fabric_red", 2, lo=2, hi=4)
    c.dots([(4, 3), (4, 4)], "snow", 1, z=4)
    return c


# ================================================================================================
# 4세대 goods: 세공대 (광부)
# ================================================================================================

@icon
def najeon_jewel_box():
    """자개 보석함: an open black-lacquer box, its lid swung up and shimmering with mother-of-pearl flowers, pearls and a ruby inside."""
    c = Canvas()
    c.poly([(2.6, 1.0), (13.4, 1.0), (14.4, 7.6), (1.6, 7.6)], "soot", 0, normal=(0, -0.1, 1), lo=1, hi=3)   # the lid, open
    lid = c.last()
    for (x, y), m in zip(((5, 3), (6, 4), (4, 4), (10, 3), (11, 4), (9, 5), (7, 6), (12, 6), (3, 6)),
                         ("pearl", "glow_cyan", "pink", "pearl", "petal_purple", "glow_cyan", "pearl", "pink", "glow_cyan")):
        c.put(x, y, m, 3, z=2)
    c.line(2, 1, 13, 1, "gold", 4, z=2)
    c.lit_edge(lid, 1)
    c.poly([(1.0, 8.0), (15.0, 8.0), (15.0, 14.8), (1.0, 14.8)], "soot", 1, normal=(0, 0.35, 1), lo=1, hi=3)  # the body
    c.rect(2, 8, 14, 10, "fabric_red", 2, normal=(0, -0.6, 0.8), lo=2, hi=4)   # velvet lining seen inside
    for k, x in enumerate((3.2, 5.0, 6.8, 8.6, 10.4)):
        c.ellipse(x, 9.0, 0.95, 0.95, "pearl", 3 + k * 0.01, lo=2, hi=5)
    c.ellipse(12.4, 9.0, 1.2, 1.1, "ruby", 3.1, lo=2, hi=5)
    for (x, y), m in zip(((3, 12), (6, 13), (9, 12), (12, 13)), ("pearl", "glow_cyan", "pink", "pearl")):
        c.put(x, y, m, 3, z=4)
    c.line(1, 11, 14, 11, "gold", 3, z=4)
    c.rect(7, 11, 9, 12.6, "gold", 5, normal=(0, 0, 1))
    return c


@icon
def gilded_spoon_set():
    """금박 은수저 세트: a long Korean silver spoon and a pair of silver chopsticks side by side, their handles inlaid with gold leaf."""
    c = Canvas()
    c.tube([(1.6, 13.0), (10.6, 4.0)], [0.6, 0.45], "steel", 1, lo=2, hi=5)              # chopsticks
    c.tube([(3.4, 14.6), (12.4, 5.6)], [0.6, 0.45], "steel", 1.1, lo=2, hi=5)
    for x, y in ((2, 12), (3, 11), (4, 13), (5, 12)):
        c.put(x, y, "gold", 4, z=3)
    c.tube([(6.0, 15.0), (12.0, 7.6)], [0.75, 0.6], "steel", 2, lo=2, hi=5)              # the spoon's handle
    c.ellipse(13.0, 4.6, 2.2, 3.2, "steel", 2.2, lo=2, hi=5, dome=-0.4)                 # its round bowl
    c.ellipse(13.2, 4.4, 1.4, 2.2, "steel", 2.3, lo=3, hi=5, dome=0.6)
    c.dots([(7, 13), (8, 12), (9, 11)], "gold", 4, z=4)                                 # gold leaf on the handle
    c.dots([(7, 14), (8, 13)], "gold", 2, z=4)
    c.put(12, 3, "snow", 1, z=5)
    c.dots([(14, 12), (13, 13)], "ruby", 3)                                             # a ruby set at the spoon's end
    return c


# ================================================================================================
# 4세대 goods: 가공대 (농부)
# ================================================================================================

@icon
def royal_jang_set():
    """궁중 장 세트: three glazed crocks, their mouths sealed with 한지 and red cord, on a red-lacquer tray with a gold rim."""
    c = Canvas()
    c.poly([(0.4, 11.4), (15.6, 11.4), (14.4, 15.0), (1.6, 15.0)], "crimson", 0, normal=(0, 0.4, 0.9), lo=1, hi=3)
    c.line(1, 11, 14, 11, "gold", 4, z=1).line(2, 14, 13, 14, "gold", 2, z=1)
    for k, (x, w, h) in enumerate(((3.6, 2.6, 4.2), (8.0, 3.2, 5.4), (12.4, 2.6, 4.2))):
        cy = 11.0 - h * 0.6
        c.ellipse(x, cy, w, h * 0.62, "onggi", 1 + k * 0.1 + (0.5 if k == 1 else 0), lo=1, hi=4)
        top = cy - h * 0.55
        c.ellipse(x, top, w * 0.72, 1.05, "paper", 2 + k * 0.1 + (0.5 if k == 1 else 0), lo=2, hi=4, dome=0.9)
        c.line(int(x - w * 0.6), int(top + 1), int(x + w * 0.6), int(top + 1), "fabric_red", 2, z=3 + k)
        c.put(int(x - w * 0.5), int(cy), "terracotta", 4, z=3 + k)
    c.dots([(8, 1), (7, 2)], "snow", 1)
    return c


@icon
def ginseng_jeonggwa():
    """인삼 정과: three ginseng roots candied in syrup, glossy amber and see-through, on a small white dish with a drop of 조청."""
    c = Canvas()
    _plate(c)
    for k, ((x0, y0), (x1, y1)) in enumerate((((2.6, 10.0), (9.8, 5.4)), ((5.4, 11.8), (13.2, 7.6)), ((4.2, 7.6), (10.6, 3.0)))):
        c.tube([(x0, y0), ((x0 + x1) / 2, (y0 + y1) / 2 - 0.4), (x1, y1)], [0.9, 1.3, 0.7], "glow_amber", 2 + k * 0.1, lo=1, hi=4)
        c.line(int(x1), int(y1), int(x1 + 1), int(y1 - 1), "doenjang", 2, z=3 + k)
    c.dots([(5, 8), (8, 9), (7, 5), (10, 9)], "lamp", 5, z=6)                     # syrup gloss
    c.dots([(12, 3), (13, 4)], "leaf", 3)
    c.ellipse(12.6, 12.2, 1.2, 0.7, "glow_amber", 3, lo=3, hi=5)
    return c


# ================================================================================================
# 4세대 goods: 손질대 (어부)
# ================================================================================================

@icon
def royal_eoran():
    """진상 어란: a pair of cured roe sacs, deep amber-red and glossy, lying on folded 한지 tied with a red cord for the court."""
    c = Canvas()
    c.poly([(0.8, 9.0), (9.4, 3.8), (15.2, 8.6), (6.6, 14.6)], "paper", 0, normal=(-0.1, -0.2, 1), lo=2, hi=4)
    for k, ((x0, y0), (x1, y1)) in enumerate((((3.0, 9.6), (11.6, 4.8)), ((5.0, 12.2), (13.4, 7.2)))):
        c.tube([(x0, y0), ((x0 + x1) / 2, (y0 + y1) / 2), (x1, y1)], [1.0, 1.6, 0.9], "salmon", 1 + k * 0.2, lo=1, hi=4)
        sac = c.last()
        c.tone([(x, y) for x, y in sac if (x * 3 + y) % 5 == 0], -1)
        c.put(int((x0 + x1) / 2) - 1, int((y0 + y1) / 2) - 1, "pink", 5, z=4 + k)
    c.line(6, 4, 10, 13, "fabric_red", 2, z=6)
    c.dots([(8, 8), (8, 9)], "fabric_red", 4, z=7)
    return c


@icon
def gulbi_dureum():
    """굴비 두름: golden dried croakers bound one above the other on a straw rope, hung from a loop."""
    c = Canvas()
    c.line(8, 0, 8, 15, "straw", 2, z=0)
    for k, y in enumerate((2.4, 6.4, 10.4)):
        for side, dx in ((0, -1), (1, 1)):
            xh = 8 + dx * 1.2
            xt = 8 + dx * 6.6
            c.tube([(xh, y + 0.4), ((xh + xt) / 2, y + 1.2), (xt, y + 2.2)], [1.1, 1.5, 0.6], "gold", 1 + k * 0.2 + side * 0.1,
                   lo=1, hi=4, flat=0.6)
            c.poly([(xt, y + 2.0), (xt + dx * 1.6, y + 1.0), (xt + dx * 1.6, y + 3.6)], "doenjang", 1.05 + k * 0.2 + side * 0.1,
                   normal=(0.3 * dx, 0, 0.9), lo=1, hi=3)
            c.put(int(xh + dx * 0.6), int(y), "soot", 0, z=5)               # eye
        c.line(6, int(y + 1), 10, int(y + 1), "straw", 4, z=5)               # the straw binding
    c.dots([(7, 0), (9, 0)], "straw", 3)
    return c


# ================================================================================================
# 4세대 goods: 대장간 (대장장이)
# ================================================================================================

@icon
def master_jangdo():
    """명장 장도: a small knife half drawn from a silver sheath chased with gold, a jade bead and a red silk tassel hanging off it."""
    c = Canvas()
    c.tube([(3.0, 13.0), (10.4, 5.6)], [1.3, 1.1], "steel", 1, lo=2, hi=5)              # the silver sheath
    sheath = c.last()
    c.dots([(x, y) for x, y in sheath if (x + 2 * y) % 5 == 0], "gold", 4, z=2)
    c.tube([(10.6, 5.4), (14.8, 1.2)], [0.8, 0.35], "blade", 1.5, lo=2, hi=5, flat=0.5)   # the drawn blade
    c.line(11, 4, 14, 1, "snow", 1, z=3)
    c.tube([(9.6, 6.6), (11.4, 4.6)], [0.9, 0.9], "gold", 2, lo=2, hi=5)                # the gold collar
    c.ellipse(2.4, 13.8, 1.4, 1.4, "gold", 2, lo=2, hi=5)
    c.line(4, 12, 3, 9, "fabric_red", 3, z=3)                                          # the cord to the bead and tassel
    c.ellipse(3.0, 8.4, 1.1, 1.1, "celadon", 3, lo=2, hi=5)
    c.tube([(1.4, 10.2), (1.0, 15.2)], [0.5, 0.8], "fabric_red", 2.5, lo=2, hi=4)
    c.dots([(5, 11), (7, 9)], "snow", 2)
    return c


@icon
def bangjja_yugi():
    """방짜 유기 세트: hand-hammered bronze tableware, a lidded rice bowl on a nested bowl and a soup spoon, warm gold with hammer marks."""
    c = Canvas()
    c.ellipse(8.0, 13.0, 7.2, 2.4, "brass", 0, lo=1, hi=3, dome=0.4)                    # a flat dish under it all
    c.poly([(2.0, 8.4), (14.0, 8.4), (12.0, 13.6), (4.0, 13.6)], "brass", 1, normal=(-0.1, 0.5, 0.85), lo=1, hi=4)
    c.tube([(2.2, 8.6), (13.8, 8.6)], [0.7, 0.7], "brass", 1.2, lo=2, hi=5)
    c.poly([(3.4, 4.4), (12.6, 4.4), (11.6, 8.4), (4.4, 8.4)], "gold", 2, normal=(-0.1, 0.4, 0.9), lo=1, hi=4)   # the lidded bowl
    c.ellipse(8.0, 4.4, 4.8, 1.6, "gold", 2.2, lo=2, hi=4, dome=0.7)                       # its domed lid
    c.ellipse(8.0, 2.6, 1.2, 0.9, "gold", 2.4, lo=2, hi=5)
    for x, y in ((5, 6), (7, 7), (10, 6), (6, 11), (9, 12), (11, 10)):                   # hammer marks
        c.put(x, y, "brass", 4, z=5)
    c.tube([(12.4, 12.0), (15.2, 6.4)], [0.4, 0.4], "brass", 3, lo=2, hi=5)              # the spoon leaning on it
    c.ellipse(12.4, 12.4, 1.4, 1.0, "brass", 3.1, lo=2, hi=5)
    c.dots([(5, 4), (6, 3)], "snow", 1)
    return c


# ================================================================================================
# 4세대 goods: 조리대 (요리사)
# ================================================================================================

@icon
def royal_surasang():
    """궁중 수라상: a round red-lacquer table seen from above, set with the king's dishes: rice, soup, abalone, 고사떡 and banchan."""
    c = Canvas()
    c.ellipse(8.0, 8.6, 7.6, 6.6, "crimson", 0, lo=1, hi=3, dome=0.25)
    c.ellipse(8.0, 8.6, 6.6, 5.6, "crimson", 0.1, lo=2, hi=2, dome=-0.2)
    for x, y in ((1, 9), (15, 9), (8, 2), (8, 15)):
        c.put(x, y, "gold", 4, z=1)
    dishes = ((5.0, 6.0, "rice"), (10.6, 6.0, "pepper"), (8.0, 9.6, "pearl"), (4.4, 11.2, "leaf"), (11.6, 11.2, "doenjang"))
    for k, (x, y, food) in enumerate(dishes):
        c.ellipse(x, y, 2.2, 1.7, "porcelain", 2 + k * 0.1, lo=2, hi=4)
        c.ellipse(x, y - 0.3, 1.4, 1.0, food, 2.05 + k * 0.1, lo=2, hi=4, dome=0.8)
    c.put(7, 9, "petal_purple", 4, z=4).put(9, 9, "glow_cyan", 3, z=4)                      # the abalone's shine
    c.dots([(3, 3), (13, 3)], "gold", 5)
    return c


@icon
def gujeolpan():
    """구절판: an octagonal black-lacquer dish of nine compartments, eight colourful fillings round a stack of thin white crepes."""
    c = Canvas()
    oct_ = [(8 + 7.4 * math.cos(math.radians(22.5 + 45 * k)), 8.4 + 6.6 * math.sin(math.radians(22.5 + 45 * k))) for k in range(8)]
    c.poly(oct_, "soot", 0, normal=(-0.1, -0.1, 1), lo=1, hi=3)
    fills = ("tomato", "leaf", "doenjang", "petal_yellow", "bean", "pepper", "cucumber", "terracotta")
    for k, mat in enumerate(fills):
        a = math.radians(45 * k)
        cx, cy = 8 + 4.6 * math.cos(a), 8.4 + 4.0 * math.sin(a)
        c.ellipse(cx, cy, 1.5, 1.3, mat, 1 + k * 0.01, lo=2, hi=4)
    c.ellipse(8.0, 8.4, 2.2, 2.0, "rice", 2, lo=2, hi=5)
    c.ellipse(8.0, 8.0, 2.0, 1.6, "porcelain", 2.1, lo=3, hi=5)
    c.line(1, 6, 1, 10, "gold", 3, z=2)
    for p in oct_:
        c.put(int(p[0]), int(p[1]), "gold", 4, z=2)
    return c


# ================================================================================================
# 4세대 goods: 증류기 (연금술사)
# ================================================================================================

@icon
def century_yakju():
    """백년 약주: a round celadon jar of long-aged medicinal wine, a red cloth tied over its mouth, a wild ginseng root on its gold label."""
    c = Canvas()
    c.ellipse(8.0, 10.0, 6.8, 5.6, "celadon", 0, lo=1, hi=4)
    c.cyl(5.4, 2.8, 10.6, 5.4, "celadon", 0.5, lo=2, hi=4)
    c.ellipse(8.0, 2.6, 4.2, 1.6, "fabric_red", 1, lo=2, hi=4, dome=0.8)
    c.line(4, 4, 11, 4, "gold", 3, z=2)
    c.dots([(4, 5), (12, 5), (12, 6)], "fabric_red", 2)                                 # the cloth's tails
    c.rect(5.0, 8.0, 11.0, 13.0, "petal_yellow", 1.5, normal=(-0.1, 0, 1), lo=2, hi=3)
    c.tube([(7.0, 9.0), (8.2, 10.8), (6.8, 12.4)], [0.6, 0.7, 0.3], "cream", 2, lo=2, hi=4)
    c.tube([(8.3, 10.6), (9.8, 12.2)], [0.4, 0.3], "cream", 2.1, lo=2, hi=4)
    c.dots([(8, 9), (9, 8)], "leaf", 3, z=4)
    c.dots([(3, 7), (3, 8), (4, 6)], "snow", 2, z=4)
    c.dots([(2, 12), (13, 13)], "celadon", 1, z=4)
    return c


@icon
def gyeongokgo():
    """경옥고: a white porcelain jar of dark glossy herbal paste, its lid set beside it, a wooden spoon and a few rolled pills."""
    c = Canvas()
    c.cyl(1.8, 5.6, 11.2, 14.8, "porcelain", 0, lo=1, hi=4, rounded=1.4)
    c.ellipse(6.5, 5.8, 4.6, 1.5, "porcelain", 0.5, lo=1, hi=1, dome=0)
    c.ellipse(6.5, 5.6, 3.8, 1.2, "wine", 1, lo=1, hi=4, dome=0.8)                     # the dark paste
    c.rect(3.0, 9.0, 10.0, 12.0, "celadon", 1, normal=(-0.2, 0, 1), lo=2, hi=3)          # a blue band
    c.dots([(5, 10), (7, 11), (8, 10)], "porcelain", 4, z=3)
    c.tube([(7.4, 5.4), (13.4, 0.8)], [0.5, 0.7], "wood", 2, lo=1, hi=4)
    c.ellipse(13.2, 11.6, 2.6, 1.4, "porcelain", 0.2, lo=1, hi=4, dome=0.5)              # the lid
    c.ellipse(13.2, 10.6, 0.9, 0.7, "porcelain", 0.3, lo=2, hi=5)
    for x, y in ((12.4, 14.4), (14.6, 14.6)):
        c.ellipse(x, y, 0.9, 0.9, "wine", 0.4, lo=1, hi=4)
    c.dots([(3, 7), (3, 8)], "snow", 1, z=4)
    return c


MATERIALS = ("gem_dust", "hanji", "fish_glue", "tempering_stone", "gosa_tteok", "mana_ink", "guard_charm")
NAMES = {
    "gem_dust": "보석 가루", "hanji": "한지", "fish_glue": "부레풀", "tempering_stone": "단련석", "gosa_tteok": "고사떡",
    "mana_ink": "마력 잉크", "guard_charm": "보호 부적",
    "najeon_jewel_box": "자개 보석함", "gilded_spoon_set": "금박 은수저 세트", "royal_jang_set": "궁중 장 세트",
    "ginseng_jeonggwa": "인삼 정과", "royal_eoran": "진상 어란", "gulbi_dureum": "굴비 두름", "master_jangdo": "명장 장도",
    "bangjja_yugi": "방짜 유기 세트", "royal_surasang": "궁중 수라상", "gujeolpan": "구절판", "century_yakju": "백년 약주",
    "gyeongokgo": "경옥고",
}
