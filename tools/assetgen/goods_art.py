"""Hand-drawn goods icons: the job finds and machine goods redrawn one by one where the shared templates in
economy_goods.py (one flask, one bag, one crock for many items) made items look alike. economy_goods.ICONS uses
these over the template drawings. Each icon is a 16x16 image built with the same pixelart Canvas and palette
materials (light from the top left, darkest step as the outline); helpers carry their batch name so the
drawings stay independent.
"""
import math
from economy_fish import mat
from economy_goods import _bowl, _plate, grid
from stsv_assets.pixelart import Canvas, shade_step


# ------------------------------------------------------------------------------------------------
# goods1 (round 1)
# ------------------------------------------------------------------------------------------------


def _goods1_crease(c, back, front, step=0):
    """Pixels of a back shape that touch a front shape of the same material become a dark crease,
    so same-coloured crystals and lumps stay separate (the contact shadow only works across materials)."""
    fr = set(front)
    for x, y in back:
        if (x, y) in fr or (x, y) not in c.px:
            continue
        if any((x + dx, y + dy) in fr for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            c.px[(x, y)][1] = step


def icon_coke():
    """Coke: three porous lumps, dull blue-grey with a soft metallic sheen, each pit a dark hole with a lit lip."""
    c = Canvas()
    m = "blacksteel"
    c.ellipse(11.2, 5.0, 3.9, 3.6, m, 0, dome=0.8)
    A = {p for p in c.last()}
    c.ellipse(11.8, 11.6, 3.4, 3.0, m, 1, dome=0.8)
    B = {p for p in c.last()}
    c.ellipse(5.6, 9.6, 4.9, 4.6, m, 2, dome=0.8)
    C = {p for p in c.last()}
    A = {p for p in A if c.px[p][2] == 0}
    B = {p for p in B if c.px[p][2] == 1}
    _goods1_crease(c, A, B | C)
    _goods1_crease(c, B, C)
    for x, y in ((3, 11), (6, 13), (7, 9), (10, 4), (12, 6), (11, 12)):
        c.put(x, y, m, 0, z=5)
        if (x + 1, y + 1) in c.px:
            c.px[(x + 1, y + 1)][1] = min(5, c.px[(x + 1, y + 1)][1] + 1)
    return c.render()


def icon_pearl():
    """A pearl in its opened oyster: the ribbed lid tipped up behind, the pink mother-of-pearl inside,
    a big creamy pearl sitting in it."""
    c = Canvas()
    c.ellipse(8, 4.6, 6.0, 3.6, "millstone", 0, lo=0, hi=2, dome=0.8)          # lid, its rough outside
    lid = set(c.last())
    for k in range(5):                                                       # ribs fanning from the hinge
        a = math.radians(-165 + k * 37.5)
        for t in [i * 0.5 for i in range(4, 20)]:
            p = (int(8 + math.cos(a) * t), int(9.5 + math.sin(a) * t * 0.85))
            if p in lid:
                c.px[p][1] = 3
    c.ellipse(8, 10.4, 7.2, 4.4, "mortar", 1, lo=1, hi=3, dome=0.8)            # lower shell, rough lip in front
    c.ellipse(8, 9.6, 5.8, 3.2, "pink", 2, lo=3, hi=4, dome=-0.7)              # mother-of-pearl inside
    c.tone([(x, y) for x, y in c.last() if x + y >= 20], -1)
    c.ellipse(8.2, 8.6, 2.9, 2.9, "pearl", 3, lo=2, hi=4)                      # the pearl
    c.dots([(7, 7)], "snow", 3).put(6, 8, "pearl", 5)
    return c.render()


def icon_red_seabream():
    """Same fish as before, minus the blown-out white head: the body tops out at a pale coral (no glint),
    and the iridescent spots are a saturated blue so they read at 1x instead of as pale speckle."""
    c = Canvas()
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], "seabream", 0,
           normal=(0.35, 0.1, 1), lo=1, hi=3)                                                      # tail
    c.poly([(3.5, 8.5), (7.5, 3), (10, 4), (5.5, 9.5)], "seabream", 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)   # spiny back fin
    c.tube([(3.6, 12.2), (6.6, 9.2), (10.2, 5.6)], [2.3, 3.7, 2.2], "seabream", 2, flat=0.95, gloss=0)
    c.poly([(6, 12.5), (8.5, 14.5), (9.5, 12.5)], "seabream", 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)           # belly fin
    c.dots([(3, 11)], "roe", 1).put(4, 10, "snow", 2)
    c.dots([(6, 9), (8, 7), (9, 9), (6, 11)], "sapphire", 3)
    c.put(2, 13, "seabream", 1)
    return c.render()


def icon_sturgeon():
    """Long and armoured: slate back with a saw ridge of white bony plates, pale belly, pointed snout,
    a shark tail with the upper lobe much longer."""
    c = Canvas()
    b = "iron"
    c.tube([(11.4, 4.6), (14.9, 0.9)], [1.2, 0.35], b, 0, lo=2, hi=4)                       # long upper lobe
    c.tube([(12.0, 5.2), (14.2, 7.2)], [1.0, 0.45], b, 0, lo=2, hi=4)                       # short lower lobe
    c.poly([(5.8, 12.2), (6.6, 14.8), (7.8, 11.6)], b, 1, normal=(0.4, 0.6, 0.7), lo=2, hi=3)  # pectoral fin
    c.tube([(1.2, 14.6), (3.2, 12.8), (5.4, 10.6), (8.8, 7.2), (12.4, 4.0)], [0.5, 1.8, 2.6, 2.3, 0.9],
           "pearl", 2, flat=0.95, lo=1, hi=3)                                                 # belly
    c.tube([(1.0, 14.4), (2.9, 12.4), (5.0, 10.2), (8.4, 6.8), (12.0, 3.6)], [0.4, 1.3, 2.0, 1.7, 0.6],
           b, 3, flat=0.95, lo=2, hi=4)                                                       # back
    for x, y in ((3, 8), (5, 6), (7, 4), (9, 2)):                                             # bony plates
        c.put(x, y, "pearl", 4, z=4)
    c.put(3, 12, "roe", 0)
    return c.render()


def icon_bluefin_tuna():
    """A fat torpedo: deep blue back, bright silver belly, yellow finlets in front of a crescent tail."""
    c = Canvas()
    c.tube([(12.0, 4.0), (10.8, 1.0)], [0.9, 0.35], "fish_blue", 0, lo=1, hi=2)          # crescent tail
    c.tube([(12.0, 4.0), (15.0, 5.2)], [0.9, 0.35], "fish_blue", 0, lo=1, hi=2)
    c.tube([(2.8, 13.0), (5.2, 10.6), (8.6, 7.4), (11.8, 4.2)], [1.7, 3.5, 2.8, 0.7], "fish_silver", 1, flat=0.9, lo=3, hi=5)
    c.tube([(2.4, 12.4), (4.8, 10.0), (8.2, 6.8), (11.4, 3.8)], [1.1, 2.4, 1.9, 0.5], "fish_blue", 2, flat=0.9, lo=1, hi=3)
    c.poly([(5.2, 7.4), (5.6, 4.0), (7.8, 6.0)], "fish_blue", 3, normal=(0, -0.6, 0.8), lo=1, hi=2)   # first dorsal
    c.dots([(9, 4), (10, 3)], "petal_yellow", 2)                                             # finlets, back
    c.dots([(11, 7), (12, 6)], "petal_yellow", 2)                                            # finlets, belly
    c.put(3, 12, "roe", 0).put(4, 11, "snow", 2)
    return c.render()


# ------------------------------------------------------------------------------------------------
# goods2 (round 1)
# ------------------------------------------------------------------------------------------------


def icon_pearl_necklace():
    """진주 목걸이: a strand of round pearls hanging in a deep U, each pearl its own bead with a glint, a larger
    pearl with a rosy sheen at the bottom (no gem pendant: the 자수정 펜던트 is the chain-and-gem one)."""
    c = Canvas()
    p = {"a": ("pearl", 5), "b": ("pearl", 4), "c": ("pearl", 3), "d": ("pearl", 2), "e": ("pearl", 1),
         "r": ("seabream", 5)}
    grid(c, [
        "................",
        "..ab........ab..",
        "..cd........cd..",
        "................",
        "..ab........ab..",
        "..cd........cd..",
        "................",
        "...ab......ab...",
        "...cd......cd...",
        "................",
        "....ab....ab....",
        "....cd.ab.cd....",
        "......abbc......",
        "......bcrd......",
        ".......dd.......",
        "................",
    ], p)
    return c.render()


def icon_jeweled_crown():
    """보석 왕관: three pointed gold spikes tipped with pearls, a band set with two sapphires and a big ruby."""
    c = Canvas()
    g = {"A": ("gold", 5), "B": ("gold", 4), "C": ("gold", 3), "D": ("gold", 2), "E": ("gold", 1),
         "p": ("pearl", 4), "P": ("pearl", 3), "q": ("pearl", 5),
         "r": ("ruby", 3), "R": ("ruby", 4), "t": ("ruby", 2), "w": ("ruby", 5),
         "s": ("sapphire", 3), "S": ("sapphire", 4), "u": ("sapphire", 2)}
    grid(c, [
        "................",
        ".qp....qp....qp.",
        ".pP....pP....pP.",
        ".BC....BC....CD.",
        ".BCC...BC...CCD.",
        ".BCC..BCCD..CCD.",
        ".BCCC.BCCD.CCCD.",
        ".BCCCCBCCDCCCCD.",
        ".BCCCCCCCCCCCCD.",
        ".BBBBBBBBBBBBBD.",
        ".BCSsCCwRCCSsCD.",
        ".BCsuCRrrtCsuCD.",
        ".BCCCCCrtCCCCCD.",
        ".DDDDDDDDDDDDDE.",
        "................",
        "................",
    ], g)
    return c.render()


def icon_rice():
    """쌀: an open burlap sack, its mouth rolled down into a thick rim, heaped high with white rice."""
    c = Canvas()
    c.ellipse(8, 4.9, 4.9, 3.6, "rice", 0.5, lo=2, hi=4, dome=0.75)
    heap = [(x, y) for x, y in c.last()]
    c.ellipse(8, 11.6, 6.6, 3.6, "burlap", 1, lo=1, hi=4)
    c.poly([(3.0, 7.0), (13.0, 7.0), (14.5, 11.6), (1.5, 11.6)], "burlap", 1, normal=(-0.2, -0.1, 1), lo=1, hi=4)
    c.tube([(1.9, 7.4), (3.8, 6.4), (12.2, 6.4), (14.1, 7.4)], [1.15, 1.3, 1.3, 1.15], "burlap", 2, lo=2, hi=4)
    c.line(5, 9, 4, 12, "burlap", 2, z=3).line(11, 9, 12, 12, "burlap", 1, z=3)
    grains = [(5, 4), (8, 3), (10, 4), (7, 4), (9, 2)]
    c.dots([p for p in grains if p in heap], "rice", 2, z=5)
    c.dots([(6, 2), (7, 2), (6, 3)], "rice", 5, z=5)
    return c.render()


def icon_flour():
    """밀가루: a white cloth sack tied at the neck with twine, its top fanned out, a golden wheat ear printed on it
    (a sack, not the paper bag 감자 전분 and 비료 come in)."""
    c = Canvas()
    c.ellipse(8, 10.8, 6.4, 4.6, "cream", 1, lo=2, hi=4)
    c.poly([(3.4, 8.0), (12.6, 8.0), (9.8, 5.0), (6.2, 5.0)], "cream", 1, normal=(-0.2, -0.5, 0.8), lo=2, hi=4)
    c.poly([(6.6, 5.0), (9.4, 5.0), (12.8, 1.6), (8.0, 2.2), (3.2, 1.6)], "cream", 2, normal=(-0.3, -0.4, 0.9), lo=3, hi=4)
    top = c.last()
    c.tone([(x, y) for x, y in top if x >= 9], -1)
    c.line(6, 4, 9, 4, "wood", 3, z=4).dots([(10, 5), (10, 6)], "wood", 2, z=4)
    c.line(8, 8, 8, 13, "straw", 2, z=5)
    for y in (9, 11):
        c.put(7, y, "straw", 4, z=5).put(9, y, "straw", 3, z=5)
    c.put(8, 7, "straw", 4, z=5)
    return c.render()


def icon_corn_flour():
    """옥수수 가루: an open paper bag, its top turned down, heaped with yellow corn meal, a corn cob leaning on it."""
    c = Canvas()
    c.poly([(1.5, 6), (4.5, 4.4), (13.5, 4.4), (10.5, 6)], "paper", 0.5, step=1)
    c.ellipse(7.2, 4.9, 4.6, 2.8, "corn", 1, lo=2, hi=4, dome=0.7)
    c.poly([(1.5, 6), (10.5, 6), (10.5, 15), (1.5, 15)], "paper", 2, normal=(-0.15, -0.1, 1), lo=2, hi=4)
    c.poly([(10.5, 6), (13.5, 4.4), (13.5, 13.6), (10.5, 15)], "paper", 2, normal=(0.85, -0.1, 0.4), lo=1, hi=2)
    c.rect(1.5, 6, 10.5, 8, "paper", 2.5, normal=(-0.3, -0.6, 0.8), lo=3, hi=4)
    c.line(2, 8, 10, 8, "paper", 1, z=3)
    c.tube([(10.4, 12.4), (13.6, 6.2)], [1.7, 1.5], "corn", 4, lo=2, hi=4)
    cob = c.last()
    c.tone([(x, y) for x, y in cob if (x + y) % 2 == 0], -1)
    c.poly([(8.4, 14.7), (8.8, 10.6), (10.6, 8.8), (11.4, 12.0), (11.2, 14.7)], "leaf", 5, normal=(-0.5, -0.2, 1), lo=3, hi=4)
    c.poly([(10.8, 14.7), (12.0, 12.0), (14.4, 9.2), (13.8, 12.6), (12.6, 14.7)], "leaf", 5.1, normal=(0.6, -0.1, 0.9),
           lo=2, hi=3)
    return c.render()


def icon_chili_powder():
    """고춧가루: a peaked heap of coarse red pepper powder in a small white dish, lit from the left, a few dark flakes."""
    c = Canvas()
    c.poly([(1.6, 11), (4.8, 6.0), (6.8, 3.6), (9.2, 3.6), (11.2, 6.0), (14.4, 11)], "pepper", 2, normal=(-0.1, -0.2, 1),
           lo=2, hi=3)
    heap = c.last()
    c.tone([(x, y) for x, y in heap if x - 0.6 * y < 1.2], 1)
    c.tone([(x, y) for x, y in heap if x + 0.6 * y > 15.5], -1)
    c.poly([(1, 10.5), (15, 10.5), (12.5, 14.5), (3.5, 14.5)], "porcelain", 3, normal=(0, 0.5, 0.85), lo=2, hi=4)
    c.tube([(1.6, 10.6), (14.4, 10.6)], [0.9, 0.9], "porcelain", 4, lo=2, hi=5)
    c.dots([(7, 6), (10, 7), (5, 9), (8, 9), (12, 9)], "pepper", 1, z=5)
    c.dots([(6, 5), (4, 8)], "pepper", 5, z=5)
    return c.render()


def icon_grape_juice():
    """포도즙: a straight tumbler of deep purple juice with a red straw, a bunch of grapes leaning on it
    (the round corked flask is 양파즙, 씨앗 기름 and 멜론 리큐어)."""
    c = Canvas()
    g = {"W": ("glass", 5), "g": ("glass", 4), "h": ("glass", 3), "k": ("glass", 2),
         "J": ("grape", 4), "j": ("grape", 3), "P": ("grape", 2), "p": ("grape", 1),
         "S": ("fabric_red", 4), "s": ("fabric_red", 3)}
    grid(c, [
        "................",
        ".........S......",
        ".gggggggSg......",
        ".WhhhhhShk......",
        ".WJJJJsJJk......",
        ".WjPPPPPpk......",
        ".WjPPPPPpk......",
        ".gjPPPPPpk......",
        ".gjPPPPPpk......",
        ".gjPPPPPpk......",
        ".gjPPPPPpk......",
        ".gjPPPPPpk......",
        ".gjPPPPPpk......",
        ".ghhhhhhhk......",
        "..kkkkkkk.......",
        "................",
    ], g)
    for k, (x, y) in enumerate(((11.6, 8.4), (13.6, 9.2), (11.0, 10.9), (13.2, 11.6), (11.8, 13.4))):
        c.ellipse(x, y, 1.45, 1.45, "grape", 5 + k * 0.1, lo=2, hi=4)
        c.put(int(x) - 1, int(y) - 1, "grape", 5, z=7)
    c.line(12, 7, 12, 6, "wood", 2, z=6)
    c.poly([(12.4, 6.6), (12.6, 4.0), (14.6, 4.4), (14.0, 6.8)], "leaf", 6, normal=(-0.3, -0.5, 0.9), lo=2, hi=4)
    return c.render()


# ------------------------------------------------------------------------------------------------
# goods3 (round 1)
# ------------------------------------------------------------------------------------------------


def icon_tomato_sauce():
    """토마토 소스: a glass jar full of deep red sauce, a ridged metal screw lid, a paper label with a tomato on it."""
    c = Canvas()
    t = {"L": ("steel", 5), "l": ("steel", 4), "M": ("steel", 3), "m": ("steel", 2), "n": ("steel", 1),
         "g": ("glass", 3), "G": ("glass", 2), "H": ("glass", 5), "h": ("glass", 4),
         "T": ("tomato", 3), "t": ("tomato", 4), "u": ("tomato", 2), "k": ("tomato", 1),
         "p": ("paper", 4), "P": ("paper", 3), "q": ("paper", 2),
         "r": ("tomato", 4), "R": ("tomato", 3), "e": ("leaf", 3), "E": ("leaf", 2)}
    grid(c, [
        "................",
        "....lLLLLLLl....",
        "...lMlMlMlMlm...",
        "...lMlMlMlMlm...",
        "...nnnnnnnnnn...",
        "..gHtTTTTTTukG..",
        "..gHTTTTTTTukG..",
        "..gpppPeEPPPqG..",
        "..gppPrRRuPPqG..",
        "..gppPRRRuPPqG..",
        "..gpppPuuPPPqG..",
        "..ghTTTTTTTukG..",
        "..ghTTTTTTTukG..",
        "..ghTTTTTTukkG..",
        "...gGGGGGGGGG...",
        "................",
    ], t)
    return c.render()


def icon_gochujang():
    """고추장: a tall onggi jar, glossy red pepper paste heaped in its mouth, a red chili lying against it."""
    c = Canvas()
    c.ellipse(7.2, 9.4, 5.6, 5.4, "onggi", 1, lo=1, hi=4)
    c.ellipse(7.2, 4.0, 3.6, 2.0, "pepper", 2, lo=2, hi=4, dome=0.8)
    c.tube([(3.6, 4.9), (10.8, 4.9)], [0.9, 0.9], "onggi", 3, lo=2, hi=4)
    c.dots([(6, 3), (7, 3)], "pepper", 5, z=4)
    c.tube([(9.0, 14.2), (12.0, 12.0), (14.0, 8.4)], [1.1, 1.3, 0.9], "pepper", 5, lo=1, hi=4)
    c.tube([(14.0, 8.4), (14.6, 6.6)], [0.5, 0.5], "leaf", 6, lo=2, hi=3)
    c.dots([(3, 7), (3, 8), (4, 6)], "terracotta", 4)
    return c.render()


def icon_dried_pollack():
    """북어: a whole pollack dried stiff: pale beige flesh, a brown back, dark ragged fins and forked tail, a gaping
    mouth (paler and drier than the golden 굴비, and lying corner to corner instead of hanging)."""
    c = Canvas()
    c.poly([(11.0, 4.8), (12.4, 0.9), (13.8, 1.2), (13.2, 3.6), (14.9, 2.8), (14.8, 4.4), (12.6, 6.0)], "doenjang", 0,
           normal=(0.3, 0, 1), lo=2, hi=3)                                                     # forked tail
    c.poly([(4.6, 9.0), (5.6, 5.8), (7.0, 5.6), (8.4, 6.8)], "doenjang", 0.5, normal=(0, -0.6, 0.8), lo=2, hi=3)
    c.poly([(8.4, 10.4), (10.4, 11.4), (10.2, 9.2)], "doenjang", 0.5, normal=(0.3, 0.6, 0.8), lo=1, hi=2)
    c.tube([(2.8, 12.8), (4.8, 10.8), (8.4, 7.4), (12.0, 4.2)], [1.6, 2.6, 2.0, 0.8], "bean", 1, flat=0.9, lo=1, hi=4)
    c.tube([(2.6, 12.2), (4.6, 10.2), (8.0, 6.9), (11.6, 3.9)], [0.8, 1.4, 1.1, 0.4], "doenjang", 1.5, flat=0.9,
           lo=3, hi=5)                                                                          # brown back
    c.erase([(1, 13), (2, 14), (1, 14)])                                                       # gaping mouth
    c.put(2, 13, "doenjang", 1, z=5)
    c.put(3, 11, "roe", 0, z=5)
    c.line(4, 13, 5, 12, "doenjang", 2, z=5)                                                   # gill cover
    c.ink["bean"] = ("doenjang", 0)                                # a dark outline, like the rest of the set
    return c.render()


def icon_prepared_puffer():
    """손질한 복어: a puffer skinned and cleaned: a plump smooth white body with no spots, a fan tail, the little
    dorsal and anal fins set far back by the tail as on a real puffer, a big eye and a small beak."""
    c = Canvas()
    c.poly([(11.0, 7.6), (13.8, 4.8), (14.9, 6.4), (14.9, 11.0), (13.8, 12.4), (11.0, 9.8)], "petal_yellow", 0,
           normal=(0.3, 0, 1), lo=1, hi=3)
    c.line(13, 7, 14, 6, "petal_yellow", 1, z=0.5).line(13, 10, 14, 11, "petal_yellow", 1, z=0.5)
    c.ellipse(6.6, 8.6, 5.6, 5.0, "porcelain", 1, lo=1, hi=4)
    c.poly([(8.0, 4.6), (9.8, 2.0), (11.4, 5.4)], "petal_yellow", 0.5, normal=(-0.2, -0.5, 1), lo=1, hi=3)
    c.poly([(8.2, 12.8), (10.0, 15.0), (11.2, 12.0)], "petal_yellow", 0.5, normal=(0.2, 0.6, 1), lo=1, hi=2)
    c.put(3, 7, "roe", 0, z=5).put(3, 6, "snow", 2, z=5)
    c.put(1, 9, "pearl", 3, z=5)
    return c.render()


def icon_seabream_sashimi():
    """참돔 회: three thick slices of white flesh, each with its red-pink skin edge, shingled on a flat dark slate
    plate, a green shiso leaf standing behind them."""
    c = Canvas()
    b = {"W": ("porcelain", 5), "w": ("porcelain", 4), "v": ("porcelain", 3), "u": ("porcelain", 2),
         "x": ("porcelain", 1), "r": ("seabream", 4), "R": ("seabream", 3), "q": ("seabream", 2),
         "g": ("leaf", 3), "G": ("leaf", 4), "h": ("leaf", 2)}
    c.ellipse(8, 11.8, 7.4, 3.4, "iron", 0, lo=1, hi=3, dome=0.3)
    c.ellipse(8, 11.5, 6.0, 2.3, "iron", 0.1, lo=2, hi=2, dome=-0.2)
    grid(c, [
        "....Gg",
        "...GGg",
        "..GGgh",
        ".GGghh",
        "GGghh.",
        "Gghh..",
    ], b, z=1, x0=9, y0=2)
    sl = ["..rrr.", ".rRRWw", "rRWWwv", "qWwwvu", "xwwvu.", ".uuu.."]
    for k, (x, y) in enumerate(((1, 6), (4, 7), (7, 8))):
        grid(c, sl, b, z=5 + k, x0=x, y0=y)
    return c.render()


# ------------------------------------------------------------------------------------------------
# goods4 (round 1)
# ------------------------------------------------------------------------------------------------


def _goods4_roe(c, cx, cy, rx, ry, z):
    """black roe heaped in the tin: a staggered lattice of bead glints, bright towards the light"""
    c.ellipse(cx, cy, rx, ry, "roe", z, lo=1, hi=3, dome=0.8)
    r = c.last()
    rs = set(r)
    for x, y in r:
        if not all(q in rs for q in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1))):
            continue
        if ((x + (y % 2) * 2) % 4 == 0 and y % 2 == 0) or ((x + 2) % 4 == 0 and y % 2 == 1):
            d = (x + 0.5 - cx) / rx + (y + 0.5 - cy) / ry
            c.put(x, y, *(("steel", 4) if d < -0.6 else ("steel", 3) if d < 0.7 else ("roe", 5)), z=z + 0.5)
    return r


def icon_caviar():
    """캐비아: an open round gold tin seen from above, a navy label band round its side, brimming with glossy black
    roe dotted with bead glints (the old tin was seen from the side, so the roe was a thin black band that read as
    a face)."""
    c = Canvas()
    cy, ry, depth, rx = 6.6, 4.8, 2.6, 7.0
    c.cyl(8 - rx, cy, 8 + rx, cy + depth, "gold", 1, lo=1, hi=3)
    c.ellipse(8, cy + depth, rx, ry, "gold", 0.9, lo=1, hi=3)
    for (x, y), v in list(c.px.items()):
        top = cy + ry * math.sqrt(max(0, 1 - ((x + 0.5 - 8) / rx) ** 2))
        if v[0] == "gold" and top + 1 <= y <= top + depth - 0.5:
            c.px[(x, y)][0] = "denim"
    c.ellipse(8, cy, rx, ry, "gold", 2, lo=3, hi=4, dome=0.1)
    _goods4_roe(c, 8, cy + 0.1, rx - 1.1, ry - 1.0, 3)
    return c.render()


def icon_iron_chain():
    """쇠사슬: three heavy round links lying corner to corner, joined by edge-on links, in dark blued iron so the
    see-through eyes of the links read against the slot (the old light-grey links had their holes vanish into
    the grey slot and read as a string of beads; it also copied the vanilla chain's vertical strip)."""
    c = Canvas()
    m = {"a": ("blacksteel", 5), "b": ("blacksteel", 3), "c": ("blacksteel", 2), "d": ("blacksteel", 1),
         "e": ("blacksteel", 4), "f": ("blacksteel", 2)}
    grid(c, [
        "................",
        "................",
        "...........abb..",
        "..........a...c.",
        "..........b...d.",
        "..........c..fd.",
        ".......abbecdd..",
        "......a..fc.....",
        "......b...d.....",
        "......c...d.....",
        "...abbecdd......",
        "..a..fc.........",
        "..b...d.........",
        "..c...d.........",
        "...cdd..........",
        "................",
    ], m)
    return c.render()


def icon_steel_ingot():
    """강철괴: the ingot shape everyone knows (big lit top, bright front and left top edges, a lit left bevel,
    a darker front), in blue-grey steel: darker and bluer than the vanilla iron ingot, lighter and colder than
    netherite. (The old flat slab had the same parallelogram silhouette as the 철판.)"""
    c = Canvas()
    L = {"E": 5, "T": 4, "L": 3, "l": 2, "F": 2, "f": 1, "R": 3}
    grid(c, [
        "................",
        "................",
        "................",
        "..........TTT...",
        ".......TTTTTTR..",
        "....TTTTTTTTTTR.",
        ".ETTTTTTTTTTEER.",
        ".LETTTTTTEEETFR.",
        ".LLETTEEEFFFFRR.",
        ".LLLEEFFFFFFRRR.",
        ".lLLLFFFFFRRf...",
        "..lLLFFff.......",
        "...lLf..........",
        "................",
        "................",
        "................",
    ], {k: ("blacksteel", v) for k, v in L.items()})
    return c.render()


def icon_oak_barrel():
    """참나무통: a bellied oak cask: staves that bow out at the middle, two dark iron hoops that curve with
    the round (seen a little from above), a lid with a lit rim. (The old one was a straight-sided box.)"""
    c = Canvas()
    top, bot, rmid, rend, cx, lid_ry = 2.2, 14.6, 6.8, 5.0, 8.0, 1.9
    mid, half = (top + bot) / 2, (bot - top) / 2

    def w(y):
        t = (y - mid) / half
        return rend + (rmid - rend) * (1 - t * t)
    for y in range(16):
        for x in range(16):
            yy = y + 0.5
            if not (top <= yy <= bot):
                continue
            u = (x + 0.5 - cx) / w(yy)
            if abs(u) > 1 or (yy > bot - lid_ry and ((yy - (bot - lid_ry)) / lid_ry) ** 2 + u * u > 1):
                continue
            n = (u, -0.25 * (yy - mid) / half, math.sqrt(max(0.02, 1 - u * u)))
            c.px[(x, y)] = ["wood", shade_step(n, 1, 4), 1, 1]
    for s in (-0.66, -0.22, 0.22, 0.66):                         # stave seams bowing with the belly
        for y in range(16):
            yy = y + 0.5
            if top + 1.0 <= yy <= bot - 0.5:
                p = (int(round(cx + s * w(yy) - 0.5)), y)
                if p in c.px and c.px[p][0] == "wood":
                    c.px[p][1] = max(0, c.px[p][1] - 1)
    for hy in (3.8, 11.0):                                       # iron hoops, 2 px, dipping in the middle
        for x in range(16):
            u = (x + 0.5 - cx) / w(hy + 0.5)
            if abs(u) > 1:
                continue
            y0 = int(hy + 0.8 * math.sqrt(max(0, 1 - u * u)))
            for k in range(2):
                if (x, y0 + k) in c.px:
                    n = (u, 0.5 * k - 0.2, math.sqrt(max(0.02, 1 - u * u)))
                    c.px[(x, y0 + k)] = ["iron", shade_step(n, 2, 5), 2, 2]
    c.ellipse(cx, top + 0.5, w(top) - 0.1, lid_ry, "wood", 3, lo=4, hi=4, dome=0.2)
    c.ellipse(cx, top + 0.65, w(top) - 1.3, lid_ry - 0.6, "wood_dark", 3.1, lo=2, hi=3, dome=-0.5)
    return c.render()


def icon_steel_toolset():
    """강철 공구 세트: an open wooden tool box seen from its front corner (dark inside, a side, a plank seam and
    a brass latch) with a claw hammer and an open-jawed spanner standing in it; the spanner in polished
    blue steel so the two tools part at a glance."""
    c = Canvas()
    c.poly([(1.0, 8.6), (3.6, 6.6), (15.0, 6.6), (12.4, 8.6)], "wood_dark", 0, step=1)
    c.tube([(4.4, 8.2), (4.0, 3.4)], [0.8, 0.8], "wood", 1, lo=2, hi=4)
    grid(c, [
        "bbbbbbc",
        "abbbbcc",
        "ccccd.d",
    ], {"a": ("steel", 5), "b": ("steel", 4), "c": ("steel", 3), "d": ("steel", 2)}, z=2, x0=1, y0=1)
    c.tube([(11.2, 8.2), (11.6, 4.6)], [0.8, 0.8], "blade", 1, lo=2, hi=4)
    grid(c, [
        "ab..c",
        "bb..d",
        "bbccd",
        ".bcd.",
    ], {"a": ("blade", 5), "b": ("blade", 4), "c": ("blade", 3), "d": ("blade", 2)}, z=2, x0=9, y0=1)
    c.poly([(12.4, 8.6), (15.0, 6.6), (15.0, 12.6), (12.4, 14.6)], "wood", 3, step=2)
    c.poly([(1.0, 8.6), (12.4, 8.6), (12.4, 14.6), (1.0, 14.6)], "wood", 3, step=3)
    f = c.last()
    c.tone([(x, y) for x, y in f if y == 8], 1)
    c.tone([(x, y) for x, y in f if y == 11], -1)
    c.dots([(6, 11), (7, 11)], "brass", 4)
    return c.render()


# ------------------------------------------------------------------------------------------------
# goods5 (round 1)
# ------------------------------------------------------------------------------------------------


def _goods5_rows(c, rows, legend, z=10, x0=0, y0=0):
    """rows of letters -> pixels on the 6-step ramps; '.' is empty"""
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                mat, step = legend[ch]
                c.put(x0 + x, y0 + y, mat, step, z)
    return c


def icon_ornate_armor():
    """A chestplate in the vanilla silhouette (shoulders, short sleeves, torso): polished steel lit from the
    left, a centre ridge, gold trim on the shoulders, collar, cuffs, belt and hem, a ruby in a gold bezel."""
    c = Canvas()
    L = {"w": ("steel", 5), "L": ("steel", 4), "M": ("steel", 3), "m": ("steel", 2),
         "Y": ("gold", 5), "G": ("gold", 4), "g": ("gold", 3), "o": ("gold", 2),
         "Q": ("ruby", 4), "R": ("ruby", 3), "r": ("ruby", 2)}
    rows = [
        "................",
        "..YGGg....gGgo..",
        ".GLwLLG..gMMmmo.",
        ".gLLLLLGgMMMmmm.",
        ".LLLLLGQRgMMmmm.",
        ".LLLLLgRrgMMmmm.",
        ".GgLLMMggMMmmoo.",
        "...LLLMLmMmmm...",
        "...LwLMLmMmmm...",
        "...LLMMLmMmmm...",
        "...GGGGgggggo...",
        "...LLLMLmMmmm...",
        "...LLMMLmMmmm...",
        "...MMMMLmmmmm...",
        "....GGGggggo....",
        "................",
    ]
    return _goods5_rows(c, rows, L).render()


def icon_kimchi():
    """Cut napa-cabbage kimchi heaped on a plate: folded red-orange pieces, the pale cabbage stems showing as
    bands through the chili paste, dark creases between the pieces, a little olive leaf."""
    c = _plate(Canvas())
    L = {"O": ("tomato", 4), "R": ("tomato", 3), "r": ("tomato", 2), "k": ("tomato", 1),
         "Y": ("cabbage", 5), "y": ("cabbage", 4), "q": ("cabbage", 1)}
    rows = [
        "................",
        "................",
        "................",
        ".....rOOOr......",
        "....rYYYYRr.rq..",
        "...rOyyRRrrOOr..",
        "..rOYRrkrrOYYRq.",
        ".rOYyRrkrOYyyRr.",
        ".rYyRRkrOYyRRrk.",
        ".rRRrrkkrRRrrk..",
        "..kk....kkkk....",
    ]
    return _goods5_rows(c, rows, L).render()


def icon_kkakdugi():
    """Radish cubes stained red-orange with chili, heaped in a small white bowl: each cube a pale lit top,
    a red front and a dark side, chili flakes and a few scallion bits."""
    c = Canvas()
    L = {"S": ("salmon", 5), "O": ("salmon", 4), "o": ("salmon", 3), "R": ("tomato", 3), "r": ("tomato", 2),
         "k": ("tomato", 1), "p": ("pepper", 1)}
    c1 = [".SSSO", "SOOOo", "RRRRr", "RpRRr", "RRRRr", "rrrrk"]
    c2 = [".SSSO", "SOOOo", "RRRRr", "RRRpr", "RRRRr", "rrrrk"]
    for k, (x, y, cube) in enumerate(((5, 2, c2), (1, 5, c1), (10, 5, c2), (5, 6, c1))):
        _goods5_rows(c, cube, L, z=10 + k, x0=x, y0=y)
    c.put(8, 3, "leaf", 4).put(3, 6, "leaf", 4).put(12, 6, "leaf", 3)
    _bowl(c, "porcelain", 30, top=10.0)
    return c.render()


def icon_oi_sobagi():
    """Two short cucumber pieces standing on a plate, cross-cut from the top and stuffed: the red filling with
    chives heaps out of the cut and runs down the slit; dark warty skin, pale flesh at the rim."""
    c = _plate(Canvas())
    L = {"R": ("pepper", 3), "H": ("pepper", 4), "r": ("pepper", 2), "c": ("leaf", 4),
         "s": ("zucchini", 5), "t": ("zucchini", 4),
         "L": ("cucumber", 4), "G": ("cucumber", 3), "g": ("cucumber", 2), "q": ("cucumber", 1), "b": ("cucumber", 5)}
    piece = [
        "..HcR.",
        ".HHRRr",
        "sHRRrt",
        "LGHRgq",
        "LGRrgq",
        "LbRrgq",
        "LGGrgq",
        "LGbGgq",
        "LGGGgq",
        ".LGgq.",
    ]
    for k, (x, y) in enumerate(((8, 1), (2, 3))):
        _goods5_rows(c, piece, L, z=10 + k, x0=x, y0=y)
    return c.render()


def icon_hobakjeon():
    """Three rounds of zucchini dipped in egg and pan-fried, overlapping on a plate: a golden egg rim, the
    green skin ring, pale egg-coated flesh, a red and green chili garnish on the front one."""
    c = _plate(Canvas())
    L = {"W": ("petal_yellow", 5), "Y": ("petal_yellow", 4), "y": ("petal_yellow", 3), "o": ("corn", 2),
         "b": ("corn", 1), "G": ("zucchini", 2), "g": ("zucchini", 1), "f": ("petal_yellow", 5), "F": ("cabbage", 5)}
    disc = [
        "..YYYY..",
        ".YgGGGy.",
        "YGfWffGy",
        "YGfffFGo",
        "yGffFFGo",
        ".oGGGgo.",
        "..oboo..",
    ]
    for k, (x, y) in enumerate(((7, 1), (1, 2), (4, 5))):
        _goods5_rows(c, disc, L, z=10 + k, x0=x, y0=y)
    c.put(7, 8, "pepper", 3).put(8, 8, "leaf", 4)
    return c.render()


def _goods5_pot(c, cy=8.0):
    """뚝배기 with its rim showing: glazed black-brown body, a lit rim ring, the stew inside the ring"""
    c.poly([(1.0, cy), (15.0, cy), (13.2, 14.6), (2.8, 14.6)], "soot", 0, normal=(-0.35, 0.3, 0.85), lo=1, hi=3)
    body = c.last()
    c.tone([(x, y) for x, y in body if x == 3 and 10 <= y <= 12], 1)
    c.tone([(x, y) for x, y in body if x >= 12], -1)
    c.ellipse(8, cy, 7.0, 3.1, "soot", 1, lo=2, hi=4, dome=0.25)
    c.ellipse(8, cy - 0.15, 6.0, 2.4, "pepper", 2, lo=2, hi=4, dome=0.3)
    return c


def icon_sundubu_jjigae():
    """Soft-tofu stew bubbling in a black 뚝배기: red broth, white curds of silken tofu, a raw egg with an orange
    yolk in the middle, scallion, steam. The tofu clouds and the yolk tell it from 김치찌개."""
    c = _goods5_pot(Canvas())
    L = {"W": ("snow", 5), "w": ("snow", 4), "v": ("snow", 3), "Y": ("petal_yellow", 5), "y": ("topaz", 4),
         "o": ("topaz", 3), "g": ("leaf", 4), "h": ("pepper", 5)}
    rows = [
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "......h..g......",
        "...WWw.WWw..h...",
        "...wwv.WYyw.WW..",
        "....g..wow.Wwv..",
        "................",
    ]
    _goods5_rows(c, rows, L, z=5)
    c.dots([(4, 4), (5, 3), (5, 2), (10, 4), (11, 3), (11, 2)], "snow", 2, z=5)
    return c.render()


def icon_tuna_sushi():
    """Two pieces of tuna nigiri, one behind the other: a deep-red slice with pale fat lines draped over a white
    rice block. No board, so it never reads as the 참치 회 platter."""
    c = Canvas()
    L = {"P": ("tuna_meat", 5), "T": ("tuna_meat", 4), "t": ("tuna_meat", 3), "d": ("tuna_meat", 2),
         "k": ("tuna_meat", 1), "W": ("rice", 5), "R": ("rice", 4), "r": ("rice", 3), "q": ("rice", 2)}
    nigiri = [
        "..TTTTTt.",
        ".TPTTTPtd",
        "TPTTTPttd",
        "dttttttdk",
        "WRWRRRRrq",
        ".RRRRrrq.",
    ]
    for k, (x, y) in enumerate(((6, 2), (1, 7))):
        _goods5_rows(c, nigiri, L, z=10 + k, x0=x, y0=y)
    return c.render()


def icon_strawberry_cake():
    """A slice of strawberry shortcake, the cut face to the front: sponge, cream with strawberry halves, sponge,
    the cream top narrowing to the tip on the left, a whole strawberry on the back."""
    c = Canvas()
    L = {"C": ("cream", 5), "c": ("cream", 4), "e": ("cream", 3), "Y": ("corn", 4), "y": ("corn", 3),
         "o": ("corn", 2), "R": ("pepper", 3), "H": ("pepper", 4), "r": ("pepper", 2), "g": ("leaf", 3),
         "G": ("leaf", 4)}
    rows = [
        "................",
        "...........gG...",
        "..........HgGR..",
        ".........HHRRRr.",
        ".......cCCRRRrc.",
        ".....cCCCCCrrCe.",
        "...cCCCCCCCCCCe.",
        ".cCCCCCCCCCCCCe.",
        ".YYYYYYYYYYYYye.",
        ".YyyyyyyyyyyyYe.",
        ".CRRCCCRRCCCRCe.",
        ".CHrCCCHrCCCHCe.",
        ".YYYYYYYYYYYYye.",
        ".yyyyyyyyyyyyye.",
        ".oooooooooooooe.",
    ]
    return _goods5_rows(c, rows, L).render()


def icon_tomato_pasta():
    """A nest of spaghetti on a white plate, tomato sauce ladled on top with a basil leaf, a fork stuck in."""
    c = _plate(Canvas())
    L = {"W": ("petal_yellow", 5), "Y": ("petal_yellow", 4), "y": ("corn", 4), "o": ("corn", 3), "b": ("corn", 2),
         "H": ("tomato", 4), "R": ("tomato", 3), "r": ("tomato", 2), "h": ("tomato", 5),
         "g": ("leaf", 4), "G": ("leaf", 3), "S": ("steel", 4), "s": ("steel", 3), "w": ("steel", 5)}
    rows = [
        "................",
        "...........w....",
        "..........Ss....",
        "........gSs.....",
        "......HRgsr.....",
        "...WYhHRRRRrYo..",
        "..WYyRRRRRRryYo.",
        ".WYooYrRRrrYooy.",
        ".YyWWyoYyyoWWob.",
        ".oYyyoWWyoWyyob.",
        "..bbobbbbobbbb..",
    ]
    return _goods5_rows(c, rows, L).render()


# ------------------------------------------------------------------------------------------------
# goods6 (round 1)
# ------------------------------------------------------------------------------------------------


def icon_hanjeongsik():
    """한정식: a low wooden table (상) seen from the front, crowded with a full Korean spread: a black pot of red
    stew steaming at the back left, a brass bowl of rice at the back right, and a front row of side dishes
    (green namul, yellow jeon, red kimchi) on white plates; two short legs under the apron. The old top-down tray
    read as a shelf or a framed cabinet, its long middle dish like a plank."""
    c = Canvas()
    top, fr = 6.4, 11.8
    c.poly([(1.4, top), (14.6, top), (15.2, fr), (0.8, fr)], "wood", 0, normal=(-0.1, -0.6, 0.8), lo=2, hi=4)
    c.poly([(0.8, fr), (15.2, fr), (15.2, fr + 1.2), (0.8, fr + 1.2)], "wood", 0.2, normal=(0, 0.6, 0.6), lo=1, hi=2)
    for x in (1.0, 13.4):
        c.rect(x, fr + 1.2, x + 1.6, 14.9, "wood", 0.1, normal=(0, 0.4, 0.8), lo=1, hi=1)
    c.poly([(1.4, 5.4), (7.8, 5.4), (7.0, 8.6), (2.2, 8.6)], "soot", 1, normal=(-0.1, 0.5, 0.85), lo=1, hi=3)
    c.tube([(1.7, 5.6), (7.5, 5.6)], [0.6, 0.6], "soot", 1.1, lo=2, hi=3)
    c.ellipse(4.6, 5.4, 2.6, 0.9, "pepper", 1.2, lo=2, hi=4, dome=0.3)
    c.put(3, 5, "cream", 4, z=2).put(5, 5, "leaf", 3, z=2)
    c.dots([(3, 2), (4, 1), (5, 3), (6, 2)], "snow", 1)                # steam
    c.poly([(8.4, 5.0), (14.6, 5.0), (13.6, 8.6), (9.4, 8.6)], "brass", 1, normal=(-0.1, 0.5, 0.85), lo=1, hi=4)
    c.tube([(8.7, 5.2), (14.3, 5.2)], [0.55, 0.55], "brass", 1.1, lo=3, hi=5)
    c.ellipse(11.5, 4.3, 2.9, 2.4, "rice", 1.2, lo=2, hi=4, dome=0.8)
    for k, (x, food, h) in enumerate(((3.0, "leaf", 1.3), (8.0, "corn", 0.9), (12.9, "pepper", 1.3))):
        c.ellipse(x, 10.2, 2.4, 1.2, "porcelain", 2 + k * 0.01, lo=1, hi=4, dome=0.3)
        c.ellipse(x, 9.4, 1.8, h, food, 2.005 + k * 0.01, lo=2, hi=4, dome=0.8)
    c.put(7, 9, "pepper", 3, z=3)
    return c.render()


def icon_makgeolli():
    """막걸리: the plastic makgeolli bottle: a straight milky-white body, a short conical shoulder, a big green screw
    cap, a green wrap label and a moulded ridge near the foot. It used to share 소주's round-shouldered long-necked
    silhouette, so the two differed only by swapped colours."""
    c = Canvas()
    c.cyl(4.4, 5.6, 11.6, 14.9, "rice", 1, lo=2, hi=4, rounded=0.8)
    c.poly([(4.4, 6.0), (6.4, 3.6), (9.6, 3.6), (11.6, 6.0)], "rice", 1.1, normal=(-0.2, -0.6, 0.8), lo=2, hi=4)
    c.cyl(6.4, 2.8, 9.6, 4.0, "rice", 1.2, lo=2, hi=4)
    c.cyl(5.6, 0.8, 10.4, 3.0, "bottle_green", 2, lo=1, hi=3)
    c.line(6, 2, 9, 2, "bottle_green", 1, z=3)
    c.cyl(4.4, 8.4, 11.6, 12.2, "bottle_green", 3, lo=1, hi=3)
    c.line(6, 10, 9, 10, "snow", 3, z=4)
    c.dots([(5, 6), (5, 7), (5, 13)], "snow", 3)
    c.tone([(x, 13) for x in range(16)], -1)
    return c.render()


def icon_soju():
    """소주: the small green bottle with a silver screw cap and a white label. Same bottle as before; the label's
    outline was brown (paper's ink), which showed as two brown tabs sticking out of the green glass, and the cap
    was the same green as the neck."""
    c = Canvas()
    c.cyl(4.2, 6.8, 11.8, 14.9, "bottle_green", 1, lo=1, hi=4, rounded=1.2)
    c.ellipse(8, 6.8, 3.8, 1.9, "bottle_green", 1.1, lo=2, hi=4)
    c.cyl(6.5, 1.6, 9.5, 6.8, "bottle_green", 1.2, lo=2, hi=4)
    c.ink["paper"] = ("bottle_green", 0)
    c.cyl(6.3, 0.8, 9.7, 2.6, "steel", 2, lo=2, hi=4)
    c.cyl(4.2, 9.2, 11.8, 12.6, "paper", 3, lo=2, hi=4)
    c.line(6, 10, 9, 10, "sapphire", 2, z=4).line(6, 11, 8, 11, "sapphire", 3, z=4)
    c.dots([(5, 7), (5, 8), (7, 3)], "bottle_green", 5)
    return c.render()


def icon_melon_syrup():
    """멜론 시럽: a wide jar of bright melon-green syrup under a red gingham cloth tied with string, a paper label
    with a netted muskmelon on it. The old cloth was red with white dots (a toadstool cap), the syrup a pale
    lemon yellow and its 'melon' a beige smudge."""
    c = Canvas()
    c.cyl(2.6, 5.0, 13.4, 14.9, "glass", 1, lo=2, hi=4, rounded=1.5)
    c.cyl(3.6, 6.4, 12.4, 14.0, "slime", 2, lo=2, hi=4, rounded=1.2)
    c.ellipse(8, 4.2, 6.4, 2.6, "fabric_red", 3, lo=2, hi=4, dome=0.6)
    c.poly([(1.6, 4.0), (14.4, 4.0), (14.0, 6.4), (2.0, 6.4)], "fabric_red", 3, normal=(-0.1, 0.1, 1), lo=2, hi=3)
    for x, y in [(x, y) for (x, y), v in c.px.items() if v[0] == "fabric_red"]:
        if x % 2 and y % 2:
            c.px[(x, y)][0], c.px[(x, y)][1] = "snow", 4 if x + y < 14 else 3
        elif x % 2 or y % 2:
            c.px[(x, y)][0], c.px[(x, y)][1] = "pink", 4 if x + y < 14 else 3
    c.line(2, 5, 13, 5, "straw", 2, z=4)
    c.dots([(3, 8), (3, 9), (3, 10)], "glass", 5)
    c.ink["paper"] = ("wood", 1)
    c.rect(5.0, 8.6, 11.8, 13.6, "paper", 3, normal=(-0.1, 0, 1), lo=4, hi=4)
    grid(c, ["..s..", ".mNm.", "mMmNm", "mNmmm", ".mmm."],
         {"m": ("musk", 1), "M": ("musk", 3), "N": ("melon_net", 5), "s": ("straw", 2)}, z=5, x0=6, y0=8)
    return c.render()


def icon_wine():
    """포도주: a slim Bordeaux bottle of dark red wine (straight sides, high square shoulders, a long neck), a red
    foil capsule, a cream label with a bunch of grapes. It was the same round-shouldered bottle, cream label and
    red mark as 복분자주 and 빈티지 포도주."""
    c = Canvas()
    c.cyl(4.6, 7.2, 11.4, 14.9, "wine", 1, lo=1, hi=4, rounded=0.8)
    c.ellipse(8, 7.2, 3.4, 1.1, "wine", 1.1, lo=1, hi=4)
    c.cyl(6.7, 1.0, 9.3, 7.2, "wine", 1.2, lo=1, hi=4)
    c.cyl(6.5, 0.8, 9.5, 3.6, "fabric_red", 2, lo=2, hi=4)
    c.ink["cream"] = ("wine", 0)
    c.cyl(4.6, 8.6, 11.4, 12.6, "cream", 3, lo=2, hi=4)
    c.put(8, 9, "leaf", 3, z=5).put(9, 9, "leaf", 4, z=5)
    for x, y in ((7, 10), (8, 10), (9, 10), (7, 11), (8, 11), (9, 11), (8, 12)):
        c.put(x, y, "grape", 3 if (x + y) % 2 else 2, z=5)
    c.dots([(5, 13), (7, 4), (7, 5)], "wine", 5)
    return c.render()


def icon_melon_liqueur():
    """멜론 리큐어: a ship's-decanter of bright green liqueur (wide flat base, sides sloping up to a narrow neck,
    lit and shaded glass facets), a gold collar and ball stopper, a gold band. It was the round corked potion flask
    that 포도즙, 씨앗 기름 and 양파즙 also use."""
    c = Canvas()
    c.poly([(6.0, 5.2), (10.0, 5.2), (13.8, 14.9), (2.2, 14.9)], "glass", 1, normal=(-0.2, -0.2, 1), lo=2, hi=4)
    c.poly([(6.0, 5.2), (7.0, 5.2), (4.2, 14.9), (2.2, 14.9)], "glass", 1.05, normal=(-0.8, -0.3, 0.5), lo=3, hi=5)
    c.poly([(9.0, 5.2), (10.0, 5.2), (13.8, 14.9), (11.8, 14.9)], "glass", 1.05, normal=(0.8, 0.1, 0.5), lo=1, hi=2)
    c.poly([(6.4, 7.4), (9.6, 7.4), (12.4, 14.2), (3.6, 14.2)], "slime", 2, normal=(-0.1, -0.1, 1), lo=2, hi=4)
    lq = c.last()
    c.tone([(x, y) for x, y in lq if x >= 10 or (x >= 9 and y <= 9)], -1)
    c.tone([(x, y) for x, y in lq if x <= 5 or (x <= 6 and y <= 9)], 1)
    c.cyl(6.9, 3.0, 9.1, 5.4, "glass", 1.2, lo=2, hi=4)
    c.rect(6.4, 2.6, 9.6, 3.6, "gold", 2, normal=(-0.2, -0.5, 1), lo=2, hi=4)
    c.ellipse(8, 2.0, 1.5, 1.15, "gold", 3, lo=2, hi=4)
    c.line(5, 12, 10, 12, "gold", 3, z=4)
    return c.render()


# ------------------------------------------------------------------------------------------------
# goods7 (round 1)
# ------------------------------------------------------------------------------------------------


def _goods7_stepcut(mat, x0, y0, x1, y1, k, steps, table_step, glints=()):
    """An emerald (step) cut seen face-up: an octagon (a rectangle with its corners cut by k px) made of
    concentric bevel rings, each side of a ring lit by how it faces the top-left light, a flat table inside."""
    c = Canvas()
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            dl, dr, dt, db = x - x0, x1 - x, y - y0, y1 - y
            d = {"t": dt, "b": db, "l": dl, "r": dr,
                 "tl": dl + dt - k, "tr": dr + dt - k, "bl": dl + db - k, "br": dr + db - k}
            if min(d.values()) < 0:
                continue
            ring = min(d.values())
            if ring >= len(steps):
                c.put(x, y, mat, table_step, z=1)
                continue
            side = min(d, key=lambda s: (d[s], len(s) == 1))      # ties go to the corner facet
            c.put(x, y, mat, steps[ring][side], z=1)
    for x, y, st in glints:
        c.put(x, y, mat, st, z=2)
    return c


def icon_cut_emerald():
    """세공 에메랄드: the emerald's own step cut seen face-up (a tall octagon: a lit outer bevel, a dark step line,
    an inner bevel and the flat table), deep green, so it reads apart from the round-cut ruby and sapphire and
    the diamond's profile."""
    ring0 = {"t": 4, "tl": 5, "l": 4, "tr": 3, "r": 2, "bl": 3, "b": 2, "br": 1}
    ring1 = {"t": 2, "tl": 2, "l": 2, "tr": 1, "r": 1, "bl": 1, "b": 1, "br": 1}
    ring2 = {"t": 4, "tl": 4, "l": 3, "tr": 3, "r": 2, "bl": 2, "b": 2, "br": 2}
    c = _goods7_stepcut("bottle_green", 3, 1, 12, 14, 2, [ring0, ring1, ring2], 3,
                        glints=((6, 4, 5), (7, 4, 4), (6, 5, 4), (9, 10, 2), (8, 11, 2), (9, 11, 2)))
    return c.render()


def _goods7_brilliant(c, mat, x0=1.0, x1=15.0, top=2.0, girdle=6.0, tip=15.0, z=1, tw=0.5714,
                      steps=(4, 5, 4, 3, 2, 3, 2, 1, 4, 2)):
    """A brilliant cut seen from the side (the classic diamond shape) as fixed-step facets: on top the crown's
    alternating triangles (lit on the left, in shadow on the right), a one-pixel girdle, then the pavilion's
    left, middle and right facets running to the point. A filled silhouette underneath closes raster gaps."""
    w = x1 - x0
    cx = (x0 + x1) / 2
    t0, t2 = cx - w * tw / 2, cx + w * tw / 2
    g1, g2 = x0 + w * 0.32, x1 - w * 0.32
    gb = girdle + 1.0
    s = steps
    c.poly([(t0, top), (t2, top), (x1, girdle), (x1, gb), (cx, tip), (x0, gb), (x0, girdle)], mat, z - 0.5, step=s[2])
    c.poly([(x0, girdle), (t0, top), (g1, girdle)], mat, z, step=s[0])
    c.poly([(t0, top), (cx, top), (g1, girdle)], mat, z, step=s[1])
    c.poly([(cx, top), (g1, girdle), (g2, girdle)], mat, z, step=s[2])
    c.poly([(cx, top), (t2, top), (g2, girdle)], mat, z, step=s[3])
    c.poly([(t2, top), (x1, girdle), (g2, girdle)], mat, z, step=s[4])
    c.poly([(x0, gb), (g1, gb), (cx, tip)], mat, z, step=s[5])
    c.poly([(g1, gb), (g2, gb), (cx, tip)], mat, z, step=s[6])
    c.poly([(g2, gb), (x1, gb), (cx, tip)], mat, z, step=s[7])
    c.rect(x0, girdle, cx, gb, mat, z + 0.1, step=s[8])
    c.rect(cx, girdle, x1, gb, mat, z + 0.1, step=s[9])
    return c


def icon_cut_diamond():
    """세공 다이아몬드: the brilliant cut in profile, cool cyan like the vanilla diamond, a crisp crown of facets
    over a pavilion that runs to a point, the top-left facet catching a white glint."""
    c = Canvas()
    _goods7_brilliant(c, "spectral")
    return c.render()


def _goods7_oval_gem(c, cx, cy, rx, ry, mat, z=3, n=10, table=0.5):
    """A faceted oval stone seen face-up (as the cut sapphire): crown facets around a flat table, every other
    facet a step darker so it sparkles."""
    m = math
    pts = [(cx + rx * m.cos(m.radians(-90 + 360 / n * k)), cy + ry * m.sin(m.radians(-90 + 360 / n * k))) for k in range(n)]
    inner = [(cx + (x - cx) * table, cy + (y - cy) * table) for x, y in pts]
    for k in range(n):
        o0, o1, i0, i1 = pts[k], pts[(k + 1) % n], inner[k], inner[(k + 1) % n]
        mx, my = (o0[0] + o1[0]) / 2 - cx, (o0[1] + o1[1]) / 2 - cy
        L = m.hypot(mx, my) or 1
        c.poly([i0, i1, o1, o0], mat, z, normal=(mx / L * 0.8, my / L * 0.8, 0.6))
        if k % 2:
            c.tone(c.last(), -1)
    c.poly(inner, mat, z + 0.5, step=2)
    return c


def icon_sapphire_brooch():
    """사파이어 브로치: a wide oval gold brooch, its rim beaded (milgrain: every other rim pixel catches the light),
    a sunken bezel and a big faceted oval sapphire, so it reads as a jewelled brooch rather than a ring or a coin;
    no loose sparkle pixels."""
    c = Canvas()
    c.ellipse(8, 8, 6.6, 5.6, "gold", 1, lo=1, hi=4, gloss=0)
    frame = set(c.last())
    rim = [(x, y) for x, y in frame if any(q not in frame for q in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)))]
    c.tone([(x, y) for x, y in rim if (x + y) % 2 == 0], 1)
    c.ellipse(8, 8, 4.8, 3.9, "gold", 1.5, lo=1, hi=3, dome=-0.6, gloss=0)
    _goods7_oval_gem(c, 8, 8, 3.9, 3.0, "sapphire", z=3)
    c.dots([(6, 6), (7, 6)], "sapphire", 5)
    return c.render()


def _goods7_band(c):
    """The gold band of the ring family (as the ruby ring): an oval seen at an angle with its hole cut out."""
    c.ellipse(8, 10.2, 6.6, 4.6, "gold", 1)
    c.hole(8, 10.6, 4.4, 2.6)
    return c


def icon_diamond_ring():
    """다이아 반지: the gold band of the ring family (as the ruby ring), a gold head with prongs at the girdle and a
    brilliant-cut diamond in profile on top (the same cyan cut as the cut diamond), big enough to read at 1x."""
    c = Canvas()
    _goods7_band(c)
    c.rect(6, 5.5, 10, 7.5, "gold", 3, normal=(0, -0.4, 1))
    _goods7_brilliant(c, "spectral", x0=4.0, x1=12.0, top=1.0, girdle=3.0, tip=7.6, z=4, tw=0.5)
    c.dots([(4, 3), (11, 3)], "gold", 4, z=6)
    return c.render()


def _goods7_lying_bottle(glass, R=3.1, rn=1.25, body=7.0, shoulder=8.7, neck=12.4, B=(3.1, 12.9)):
    """A bottle laid down corner to corner (flat base bottom-left, neck top-right): a round body, a short
    shoulder and the neck. Returns the canvas and su(x, y) -> (distance along the bottle, offset across it,
    + towards the lower right) for placing a label, a shine or a capsule."""
    c = Canvas()
    r = 0.70711

    def P(s):
        return (B[0] + s * r, B[1] - s * r)

    def su(x, y):
        px, py = x + 0.5 - B[0], y + 0.5 - B[1]
        return px * r - py * r, px * r + py * r
    c.tube([P(0), P(body)], [R, R], glass, 1, lo=1, hi=4, gloss=0)
    c.tube([P(body), P(shoulder)], [R, rn], glass, 1, lo=1, hi=4, gloss=0)
    c.tube([P(shoulder), P(neck)], [rn, rn], glass, 1.1, lo=1, hi=4, gloss=0)
    c.erase([p for p in list(c.px) if su(*p)[0] < 0])
    return c, su, P


def icon_vintage_wine():
    """빈티지 포도주: an old bottle laid down as in a cellar (corner to corner, unlike every upright bottle): dark
    green glass with a shine along its top, a cream label right across it with a line of text, bright red
    sealing wax over the mouth dripping down the neck."""
    R = 3.1
    c, su, P = _goods7_lying_bottle("felt", R=R)
    for p, v in c.px.items():
        s, u = su(*p)
        if 2.0 <= s <= 5.2:                                                    # the label, lit across its width
            v[0], v[1] = "cream", (4 if u < -0.9 else (3 if u < 0.9 else 2))
            if abs(s - 3.6) <= 0.4 and abs(u) <= 1.6:
                v[0], v[1] = "doenjang", 2
        elif -R + 0.1 <= u <= -R + 1.1 and (0.4 <= s <= 1.4 or 5.9 <= s <= 7.6):
            v[1] = 4                                                           # shine along the top of the glass
    c.tube([P(11.2), P(14.0)], [1.6, 1.6], "pepper", 3, lo=1, hi=3, gloss=0)  # the wax capsule
    c.dots([(10, 6), (10, 7), (12, 5)], "pepper", 2, z=4)                     # and its drips
    return c.render()


def icon_potato_starch():
    """감자 전분: a wooden scoop heaped with fine pure-white starch, its dark handle to the corner, and the potato it
    came from lying in front (no bag: the paper-bag template is the fertilizer's, the sacks are flour and rice)."""
    c = Canvas()
    c.tube([(1.6, 14.4), (5.0, 11.0)], [1.0, 1.0], "wood_dark", 1, lo=1, hi=4)
    c.ellipse(8.4, 8.0, 5.6, 3.4, "wood", 2, lo=1, hi=4)
    c.ellipse(8.4, 7.0, 4.4, 1.6, "wood_dark", 2.5, lo=1, hi=2, dome=-0.5)
    c.ellipse(8.4, 6.6, 4.6, 2.0, "flour", 3, lo=1, hi=4, dome=0.9, gloss=0)        # the heap: a wide base
    c.ellipse(8.0, 4.6, 2.6, 2.2, "flour", 3.1, lo=1, hi=4, dome=0.9, gloss=0)      # and its soft peak
    c.ellipse(11.6, 12.2, 3.6, 2.7, "whetstone", 5, lo=1, hi=4, gloss=0)             # a yellow-tan potato
    c.dots([(10, 11), (13, 12), (11, 13)], "whetstone", 1, z=6)                      # its eyes
    return c.render()


def icon_brown_sugar():
    """흑설탕: a round glass jar with a cork stopper, filled with dark molasses-brown sugar (its heaped top lit, a
    few crystals in an even scatter catching the light): darker than the amber 조청, no lid like the sauces."""
    c = Canvas()
    sugar = "wood_dark"
    c.cyl(2.5, 5.5, 13.5, 14.9, "glass", 1, lo=2, hi=4, rounded=2.0)
    c.cyl(3.5, 7.4, 12.5, 14.0, sugar, 2, lo=1, hi=3, rounded=1.6)
    c.ellipse(8, 7.4, 4.5, 1.4, sugar, 2.2, lo=3, hi=4)
    c.cyl(5.0, 3.6, 11.0, 5.6, "glass", 1.5, lo=2, hi=4)
    c.cyl(5.5, 1.4, 10.5, 4.0, "bean", 3, lo=2, hi=4)
    c.dots([(5, 9), (9, 9), (7, 11), (11, 11), (5, 13), (9, 13)], sugar, 4, z=4)
    c.dots([(3, 8), (3, 9), (3, 10)], "glass", 5)
    return c.render()


def icon_apple_juice():
    """사과즙: a glass pitcher of golden apple juice (a pouring lip on the left, a looped handle on the right, no
    foam), a shiny red apple with its stem and leaf in front: a pitcher, not a bottle, since bottles are liquors."""
    c = Canvas()
    c.cyl(3.0, 3.6, 11.0, 14.9, "glass", 1, lo=2, hi=4, rounded=1.2)
    c.cyl(4.0, 5.4, 10.0, 14.0, "topaz", 2, lo=1, hi=4, rounded=1.0)
    c.line(4, 5, 9, 5, "topaz", 4, z=3)
    c.tube([(10.6, 5.0), (13.4, 6.0), (13.4, 10.6), (10.6, 11.8)], [0.85, 0.85, 0.85, 0.85], "glass", 0.5, lo=2, hi=4)
    c.poly([(1.2, 2.0), (4.0, 3.0), (4.0, 4.6)], "glass", 1.1, normal=(-0.5, -0.6, 0.7), lo=2, hi=4)
    c.ellipse(5.2, 11.9, 3.2, 3.0, "tomato", 4, lo=1, hi=4)
    c.line(5, 8, 5, 9, "wood_dark", 2, z=5)
    c.put(6, 8, "leaf", 3, z=5)
    c.dots([(4, 6), (4, 7)], "glass", 5)
    return c.render()


# ------------------------------------------------------------------------------------------------
# goods8 (round 1)
# ------------------------------------------------------------------------------------------------


def icon_soy_sauce():
    """간장: a squat dark-brown soy sauce jug with a molded carry handle on its shoulder, a short neck, a red cap and a
    cream label with a red band. It was the long-necked bottle that 소주, 막걸리, 청주 and 포도주 also use, told apart
    only by colour, and its label had a black slab on it."""
    c = Canvas()
    c.tube([(10.4, 6.6), (13.6, 7.4), (13.6, 10.2), (11.0, 11.4)], [0.75, 0.75, 0.75, 0.75], "soot", 0, lo=1, hi=3)
    c.cyl(2.5, 6.4, 11.5, 15.0, "soot", 1, lo=1, hi=4, rounded=1.4)
    c.ellipse(7, 6.6, 4.5, 2.2, "soot", 1.1, lo=2, hi=4)
    c.cyl(5.4, 2.6, 8.6, 5.6, "soot", 1.2, lo=2, hi=4)
    c.cyl(5.0, 1.0, 9.0, 3.2, "fabric_red", 2, lo=1, hi=3)
    c.ink["paper"] = ("soot", 0)
    c.cyl(2.5, 9.2, 11.5, 12.8, "paper", 3, lo=2, hi=4)
    c.line(3, 10, 10, 10, "fabric_red", 2, z=4)
    c.line(5, 12, 8, 12, "soot", 2, z=4)
    c.dots([(4, 7), (4, 8), (5, 6)], "soot", 5)
    return c.render()


def icon_shrimp_meat():
    """새우살: the same big peeled shrimp curled into a C (pale flesh, red stripes, the pale cut end, a red tail fan),
    its upper arm moved down half a pixel: the old curl ran into the top edge of the slot, where three pixels of flesh
    had no outline; the cut end's outline was a stray brown pixel and is now the shrimp's own red."""
    c = Canvas()
    c.ink["cream"] = ("salmon", 0)
    pts = [(12.0, 4.8), (8.6, 3.4), (5.2, 4.6), (3.4, 7.56), (4.0, 10.78), (6.6, 12.8), (9.8, 13.0)]
    c.tube(pts, [2.35, 2.45, 2.45, 2.4, 2.1, 1.7, 1.1], "salmon", 1, lo=2, hi=5, flat=0.85)
    c.poly([(9.4, 11.6), (14.2, 10.6), (13.0, 14.6), (10.2, 14.0)], "tomato", 2, normal=(0.3, 0.2, 1), lo=2, hi=4)
    c.line(11, 12, 13, 13, "tomato", 1, z=3)
    for (x0, y0, x1, y1) in ((8, 2, 9, 5), (5, 4, 6, 6), (2, 7, 4, 7), (3, 11, 5, 10), (6, 13, 7, 11)):
        c.line(x0, y0, x1, y1, "fabric_red", 3, z=4)
    c.ellipse(12.4, 5.0, 1.2, 1.4, "cream", 5, lo=3, hi=4)
    return c.render()


_goods8_jeot = {"w": ("pearl", 4), "W": ("pearl", 5), "g": ("pearl", 3), "S": ("salmon", 4), "k": ("soot", 1)}


def icon_jeotgal():
    """젓갈 (새우젓): an onggi jar with two little lug handles on its shoulders, heaped with tiny salted shrimp: pale
    pearly bodies, pink-orange tails and their black dot eyes. The old icon was the 된장/고추장 crock again with a
    salmon paste, i.e. the same template told apart only by hue."""
    c = Canvas()
    c.ink["pearl"] = ("onggi", 0)
    c.ellipse(1.9, 6.6, 1.1, 1.0, "onggi", 0.5, lo=2, hi=3)
    c.ellipse(14.1, 6.6, 1.1, 1.0, "onggi", 0.5, lo=1, hi=2)
    c.ellipse(8.0, 9.8, 5.0, 5.4, "onggi", 1, lo=1, hi=4)
    c.tube([(3.8, 5.6), (12.2, 5.6)], [0.9, 0.9], "onggi", 3, lo=2, hi=4)
    grid(c, ["...wSWw...",
             "..wSkwSkg.",
             ".SkwSwkSg.",
             ".gSkgSkgg."], _goods8_jeot, z=2, x0=3, y0=1)
    c.dots([(4, 8), (4, 9), (5, 7)], "terracotta", 4)
    return c.render()


def icon_copper_pot():
    """구리 냄비: the copper saucepan with its wooden handle, now seen a little from above (lit rim, dark inside), with
    a base band; the handle ends inside the slot (it ran off the right edge with no outline) and the rim is no longer
    a flat blown-out stripe."""
    c = Canvas()
    c.tube([(11.4, 7.6), (14.2, 3.0)], [0.8, 0.7], "wood_dark", 0, lo=2, hi=4)
    c.cyl(1.5, 6.8, 12.5, 14.8, "copper", 1, lo=1, hi=4, rounded=1.6)
    c.ellipse(7, 6.8, 5.6, 1.9, "copper", 2, lo=2, hi=4, dome=0.5)
    c.ellipse(7, 6.9, 4.4, 1.0, "copper", 2.1, lo=1, hi=1, dome=0)
    c.line(2, 12, 12, 12, "copper", 1, z=3)
    c.dots([(3, 9), (3, 10), (4, 8)], "copper", 5)
    return c.render()


def icon_gamasot():
    """가마솥: a black iron cauldron built like the real thing: domed lid with a knob, a short wall under it, the wide
    flange (전) and the round belly below. The old flange ran to both slot edges with no outline and the lid sat
    straight on it, so it read as a dark ball with a band."""
    c = Canvas()
    c.ellipse(8, 11.2, 5.4, 3.8, "blacksteel", 1, lo=1, hi=3)
    c.cyl(3.4, 6.6, 12.6, 9.4, "blacksteel", 1.5, lo=1, hi=3)
    c.tube([(1.7, 9.2), (14.3, 9.2)], [0.95, 0.95], "blacksteel", 2, lo=1, hi=4)
    c.ellipse(8, 6.2, 5.2, 2.6, "blacksteel", 3, lo=2, hi=5, dome=0.7)
    c.cyl(7.0, 2.8, 9.0, 4.4, "blacksteel", 3.5, lo=2, hi=4)
    c.ellipse(8, 2.6, 1.8, 1.0, "blacksteel", 4, lo=2, hi=5)
    return c.render()


# ------------------------------------------------------------------------------------------------
# goods9 (round 1)
# ------------------------------------------------------------------------------------------------


def _goods9_crease(c, back, front, step=1):
    """Pixels of a back shape that touch a front shape of the same material become a dark crease, so
    overlapping slices of one material read apart."""
    fr = set(front)
    for x, y in back:
        if (x, y) in fr or (x, y) not in c.px:
            continue
        if any((x + dx, y + dy) in fr for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            c.px[(x, y)][1] = step


def _goods9_shingles(c, slots, mat, rx=2.6, ry=1.3, lo=1, hi=4, z=2):
    """Thin slices of meat lying over each other like shingles, back to front, each lit along its top
    and parted from the one behind by a dark crease."""
    done = []
    for k, (x, y) in enumerate(slots):
        m = mat[k % len(mat)] if isinstance(mat, (list, tuple)) else mat
        c.ellipse(x, y, rx, ry, m, z + k * 0.1, lo=lo, hi=hi, dome=0.7, gloss=0.6)
        me = c.last()
        for prev in done:
            _goods9_crease(c, prev, me, 1)
        done.append(me)
    return c


def _goods9_pot(c, broth, cy=8.0, lo=2, hi=4):
    """뚝배기 with its rim showing (the same pot as 순두부찌개): glazed black-brown body, a lit rim ring,
    the stew inside the ring."""
    c.poly([(1.0, cy), (15.0, cy), (13.2, 14.6), (2.8, 14.6)], "soot", 0, normal=(-0.35, 0.3, 0.85), lo=1, hi=3)
    body = c.last()
    c.tone([(x, y) for x, y in body if x == 3 and 10 <= y <= 12], 1)
    c.tone([(x, y) for x, y in body if x >= 12], -1)
    c.ellipse(8, cy, 7.0, 3.1, "soot", 1, lo=2, hi=4, dome=0.25)
    c.ellipse(8, cy - 0.15, 6.0, 2.4, broth, 2, lo=lo, hi=hi, dome=0.3)
    return c


def _goods9_steam(c):
    """two wisps of steam, as on 순두부찌개"""
    return c.dots([(4, 4), (5, 3), (5, 2), (10, 4), (11, 3), (11, 2)], "snow", 2, z=5)


def icon_iron_lantern():
    """An iron lantern, symmetric about its middle: a ring on top, a stepped cap with an overhanging eave,
    four amber panes in a dark iron frame (brightest next to the flame behind the middle post), a wide foot."""
    c = Canvas()
    L = {"S": ("steel", 4), "s": ("steel", 3), "t": ("steel", 2),
         "F": ("iron", 4), "f": ("iron", 3), "d": ("iron", 2), "k": ("iron", 1),
         "W": ("lamp", 5), "Y": ("lamp", 4), "y": ("lamp", 3), "o": ("lamp", 2)}
    rows = [
        "................",
        "......sSSs......",
        "......S..t......",
        ".....FFffd......",
        "....FFfffdd.....",
        "..FFFffffffddd..",
        "...FyYYfdYyok...",
        "...FYWWfdWYyk...",
        "...FyYWfdWYyk...",
        "...FFfffffddk...",
        "...FyYWfdWYyk...",
        "...FyYYfdYyok...",
        "...Foyyfdyook...",
        "...FFfffffddk...",
        "..FFfffffffddk..",
        "................",
    ]
    return grid(c, rows, L).render()


def icon_gyeranmari():
    """Two thick slices of rolled omelette standing on a plate, one behind the other: each cut face shows the
    egg rolled up in a spiral with carrot and scallion flecks, its golden side shows how thick it is."""
    c = _plate(Canvas())
    L = {"C": ("straw", 4), "c": ("straw", 3), "k": ("straw", 2), "Y": ("petal_yellow", 4), "y": ("petal_yellow", 3),
         "s": ("corn", 2), "g": ("leaf", 4), "r": ("onion", 4)}
    face = [".CCCCC.",
            "CYYYYYy",
            "CYsssYy",
            "CYsYsYy",
            "CYsYYsy",
            "CYrsssy",
            "CYyYgyy",
            ".kkkkk."]
    side = ["CC",
            "cc",
            "cc",
            "cc",
            "cc",
            "cc",
            "k."]
    grid(c, side, L, z=3, x0=13, y0=3)
    grid(c, face, L, z=3.5, x0=7, y0=2)
    grid(c, side, L, z=4, x0=7, y0=5)
    grid(c, face, L, z=4.5, x0=1, y0=4)
    return c.render()


def icon_gamjajeon():
    """A crisp potato pancake on a plate: a dark golden lacy rim, a paler middle with shreds of potato, a slice
    of red and of green chili on top."""
    c = _plate(Canvas())
    c.ellipse(8, 8.4, 6.6, 3.6, "plush", 2, lo=2, hi=4, dome=0.4)
    c.ellipse(7.8, 8.0, 5.0, 2.5, "straw", 2.5, lo=3, hi=5, dome=0.3)
    c.line(5, 7, 6, 7, "straw", 5, z=4).line(9, 6, 10, 6, "straw", 5, z=4).line(8, 9, 9, 9, "straw", 5, z=4)
    c.line(6, 9, 7, 9, "straw", 2, z=4).line(10, 8, 11, 8, "straw", 2, z=4)
    c.put(4, 8, "pepper", 3, z=5).put(5, 8, "pepper", 4, z=5).put(11, 7, "leaf", 4, z=5).put(12, 7, "leaf", 3, z=5)
    return c.render()


def icon_jeyuk():
    """제육볶음 on a plate with a lettuce leaf for wrapping: slices of pork stir-fried deep red with chili paste,
    lying over each other, onion, scallion and chili flakes; the frilly green leaf tells it from 불고기 and 김치."""
    c = _plate(Canvas())
    L = {"V": ("sprout", 4), "v": ("leaf", 5), "l": ("leaf", 4), "L": ("leaf", 3), "d": ("leaf", 2)}
    rows = [
        "..ll.ll.........",
        ".lvvlvvl........",
        ".lvVvlvvl.......",
        ".dLvlLvvl.......",
        "..dLlLvl........",
        "..dLdLl.........",
        "...ddd..........",
    ]
    grid(c, rows, L, z=1, y0=2)
    _goods9_shingles(c, [(9.6, 5.4), (6.8, 7.4), (11.6, 7.6), (8.8, 9.2), (5.6, 9.8), (12.0, 9.8)], "brick",
                     rx=2.4, ry=1.3, lo=1, hi=3)
    c.line(9, 8, 10, 8, "garlic", 5, z=6).put(6, 9, "garlic", 5, z=6)
    c.line(12, 7, 13, 6, "leaf", 4, z=6).put(8, 10, "leaf", 4, z=6)
    c.dots([(10, 5), (7, 7), (12, 9)], "pepper", 3, z=6)
    return c.render()


def icon_doenjang_jjigae():
    """된장찌개 in a black 뚝배기: brown soybean-paste broth, white tofu cubes, pale-green zucchini half moons,
    green and red chili, steam."""
    c = _goods9_pot(Canvas(), "doenjang")
    L = {"W": ("snow", 5), "w": ("snow", 4), "v": ("snow", 3), "Z": ("zucchini", 5), "z": ("zucchini", 2),
         "g": ("leaf", 4), "h": ("pepper", 4)}
    rows = [
        "...WWv...zZz....",
        "...wvv..zZZZz.h.",
        "..g.....WWv.....",
        "...zZz..wvv.....",
    ]
    grid(c, rows, L, z=5, y0=6)
    return _goods9_steam(c).render()


# ------------------------------------------------------------------------------------------------
# goods10 (round 1)
# ------------------------------------------------------------------------------------------------


def _goods10_crease(c, back, front, step=1):
    """Pixels of a back shape that touch a front shape of the same material become a dark crease, so
    overlapping pieces of one material read apart."""
    fr = set(front)
    for x, y in back:
        if (x, y) in fr or (x, y) not in c.px:
            continue
        if any((x + dx, y + dy) in fr for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            c.px[(x, y)][1] = step


def icon_cider():
    """사과주: a dark green swing-top bottle (a white porcelain stopper over a red rubber ring), a shiny red apple
    with its stem and leaf leaning on its foot. It was the same wide bottle as 딸기주 with a cream label, so it read
    as 소주 in a different green."""
    c = Canvas()
    c.cyl(3.6, 6.8, 10.2, 14.9, "bottle_green", 1, lo=1, hi=4, rounded=1.2)
    c.ellipse(6.9, 6.8, 3.3, 2.0, "bottle_green", 1.1, lo=2, hi=4)
    c.cyl(5.6, 2.8, 8.2, 6.8, "bottle_green", 1.2, lo=2, hi=4)
    c.ellipse(6.9, 1.9, 1.6, 1.1, "porcelain", 2, lo=3, hi=5)
    c.line(6, 3, 7, 3, "fabric_red", 3, z=3)
    c.ellipse(11.8, 12.2, 2.7, 2.6, "tomato", 4, lo=1, hi=4)
    c.put(11, 11, "tomato", 5, z=5)
    c.put(12, 9, "wood_dark", 2, z=5).put(13, 9, "leaf", 4, z=5)
    c.dots([(4, 8), (4, 9), (4, 10), (6, 4)], "bottle_green", 5)
    return c.render()


def icon_strawberry_wine():
    """딸기주: a stemmed wine glass of rosy strawberry wine, a whole strawberry (green calyx, pale seeds) hooked
    on its rim. It was 사과주's wide bottle in pink with a cream label: the two differed only by colour."""
    c = Canvas()
    c.ellipse(6.5, 4.4, 4.7, 5.0, "glass", 1, lo=2, hi=4)
    c.erase([(x, y) for (x, y), v in list(c.px.items()) if y < 3])
    c.ellipse(6.5, 4.6, 3.8, 4.1, "pink", 2, lo=1, hi=4)
    for (x, y), v in list(c.px.items()):
        if v[0] == "pink" and y < 3:
            del c.px[(x, y)]
        elif v[0] == "pink" and y < 4:
            v[0], v[1] = "glass", 4
    c.line(3, 4, 9, 4, "pink", 4, z=3)
    c.rect(6.0, 9.2, 7.0, 13.2, "glass", 0.8, normal=(-0.4, 0, 1), lo=3, hi=3)
    c.ellipse(6.5, 13.6, 3.2, 0.9, "glass", 0.9, lo=2, hi=4)
    c.poly([(9.4, 2.6), (13.8, 2.6), (13.2, 5.6), (11.6, 7.8), (10.0, 5.6)], "pepper", 4, normal=(-0.3, -0.2, 1), lo=2, hi=4)
    c.dots([(10, 3), (12, 4), (11, 5)], "petal_yellow", 4, z=6)
    grid(c, [".gG.", "gGgg"], {"g": ("leaf", 3), "G": ("leaf", 4)}, z=6, x0=10, y0=1)
    c.dots([(3, 5), (3, 6)], "glass", 5)
    return c.render()


def icon_cheongju():
    """청주: a blue-and-white porcelain bottle (청화백자 옥호춘병): a flared lip, a slim neck swelling into a
    pear-shaped belly on a short foot, cobalt bands at the lip, the shoulder and a ring of lotus petals round the
    foot. The old one was a straight milk bottle with blue stripes, like 감자 보드카's clear bottle with its blue cap."""
    c = Canvas()
    c.ellipse(8, 10.8, 5.3, 4.1, "porcelain", 1, lo=3, hi=4)
    c.tube([(8, 9.0), (8, 6.6), (8, 4.2), (8, 2.8)], [4.2, 2.5, 1.2, 1.1], "porcelain", 1.1, lo=3, hi=4)
    c.ellipse(8, 1.9, 2.4, 0.95, "porcelain", 1.2, lo=3, hi=4)
    c.rect(5.6, 13.9, 10.4, 14.9, "porcelain", 0.9, normal=(0, 0.3, 1), lo=3, hi=3)
    c.line(7, 3, 8, 3, "sapphire", 2, z=5)
    c.line(5, 7, 10, 7, "sapphire", 3, z=5)
    for y in (12, 13):                                   # lotus petals round the foot, on the body only
        for x in range(16):
            if (x, y) in c.px and (y == 13 or x % 2 == 0):
                c.px[(x, y)][0], c.px[(x, y)][1], c.px[(x, y)][2] = "sapphire", 3 if (x + y) % 2 else 2, 5
    c.dots([(5, 9), (4, 10), (7, 5)], "porcelain", 5)
    return c.render()


def icon_copper_bracelet():
    """구리 팔찌: a wide copper bangle lying flat, seen from above at an angle (a lit rim, the shaded inner wall at
    the back, the slot showing through its middle, its band's front face), an emerald set in a bezel on the front.
    The old one was the ring family's upright oval with a green stone on top: a copper ruby ring, and at 1x a
    tomato with its calyx."""
    c = Canvas()
    cy, rx, ry, irx, iry, h = 6.4, 7.0, 4.6, 5.6, 3.4, 2.0
    c.ellipse(8, cy + h, rx, ry, "copper", 1, lo=1, hi=3, dome=0.0)
    for (x, y), v in list(c.px.items()):
        u = (x + 0.5 - 8) / rx
        v[1] = shade_step((u, 0.25, math.sqrt(max(0.02, 1 - u * u))), 1, 4)
    c.cyl(8 - rx, cy, 8 + rx, cy + h, "copper", 1, lo=1, hi=4)
    c.ellipse(8, cy, rx, ry, "copper", 2, lo=3, hi=4, dome=0.2)
    c.ellipse(8, cy, irx, iry, "copper", 3, lo=1, hi=2, dome=-0.8)
    c.erase([(x, y) for x in range(16) for y in range(16)
             if ((x + 0.5 - 8) / irx) ** 2 + ((y + 0.5 - cy) / iry) ** 2 <= 1
             and ((x + 0.5 - 8) / irx) ** 2 + ((y + 0.5 - cy - h) / iry) ** 2 <= 1])
    grid(c, ["kLLLk", "LGgmK", "LgmdK", ".KKK."], {"L": ("copper", 4), "k": ("copper", 2), "K": ("copper", 0),
                                                 "G": ("signal_green", 5), "g": ("signal_green", 4),
                                                 "m": ("signal_green", 3), "d": ("signal_green", 1)}, z=6, x0=6, y0=10)
    return c.render()


def icon_amethyst_pendant():
    """자수정 펜던트: the same gold chain hanging in a V to a gold bail, now holding a symmetric amethyst point (a lilac
    lit face with a glint, a mid face, a dark face, tapering to a point). The old crystal was small, lopsided (a
    one-pixel bump on its left) and half of it nearly black."""
    c = Canvas()
    for i in range(6):
        c.put(2 + i, 1 + i, "gold", 3 + i % 2, z=1).put(13 - i, 1 + i, "gold", 3 + i % 2, z=1)
    c.rect(7, 6.6, 9, 8, "gold", 2, normal=(0, -0.3, 1), lo=2, hi=4)
    grid(c, [
        ".LLMD.",
        "LHLMDd",
        "LLLMDd",
        "LLMMDd",
        ".LMMD.",
        "..MD..",
    ], {"H": ("crystal", 5), "L": ("crystal", 4), "M": ("crystal", 3), "D": ("crystal", 2), "d": ("crystal", 1)}, z=3,
        x0=5, y0=8)
    return c.render()


def icon_quartz_figurine():
    """석영 조각상: a little white quartz rabbit (옥토끼) sitting up, long ears raised (the far one behind a
    crease), a carved eye, a faint rose blush in the ear and nose, a round tail, on a dark wooden plinth with a lit
    top. The old bird read as a live duck: a yellow beak, a dark eye, its beak and tail cut off at the slot edges."""
    c = Canvas()
    mat = "snow"
    c.poly([(1.6, 11.8), (14.4, 11.8), (13.4, 13.0), (2.6, 13.0)], "wood_dark", 0, normal=(0, -0.8, 0.6), lo=3, hi=4)
    c.rect(2.6, 13.0, 13.4, 14.9, "wood_dark", 0, normal=(0, 0.2, 1), lo=1, hi=2)
    c.ellipse(9.4, 9.0, 4.4, 3.2, mat, 1, lo=1, hi=4)
    c.ellipse(13.4, 9.6, 1.2, 1.2, mat, 1.05, lo=2, hi=4)
    c.tube([(7.0, 4.6), (8.6, 1.9)], [0.95, 0.8], mat, 1.5, lo=1, hi=3)
    back = c.last()
    c.ellipse(5.4, 6.6, 2.7, 2.3, mat, 2, lo=1, hi=4)
    c.tube([(5.6, 4.6), (6.2, 1.8)], [1.0, 0.85], mat, 2.1, lo=2, hi=4)
    _goods10_crease(c, back, c.last(), 1)
    c.dots([(6, 2), (6, 3), (3, 7)], "pink", 4, z=5)
    c.put(4, 6, "stone", 1, z=5)
    c.ellipse(6.0, 11.0, 1.5, 0.9, mat, 2.2, lo=2, hi=4)
    return c.render()


def icon_gangnaengi():
    """강냉이: a red-and-white striped paper carton heaped over its rim with puffed corn, each puff a lit white
    knob parted from its neighbours by a crease, a golden hull bit under some. The old paper cone with crosswise
    red bands read as an ice-cream cone."""
    c = Canvas()
    top, bot, ht, hb, stripes = 7.4, 14.9, 5.4, 3.6, 7
    c.poly([(8 - ht, top), (8 + ht, top), (8 + hb, bot), (8 - hb, bot)], "paper", 1, normal=(-0.1, 0.1, 1), lo=3, hi=4)
    for x, y in c.last():
        f = (y + 0.5 - top) / (bot - top)
        t = (x + 0.5 - 8) / (ht + (hb - ht) * f)
        if int((t + 1) / 2 * stripes) % 2 == 0:
            c.px[(x, y)][0], c.px[(x, y)][1] = "fabric_red", 3 if t < 0.3 else 2
        else:
            c.px[(x, y)][1] = 4 if t < 0.3 else 3
    puffs = [(3.4, 7.0), (12.6, 7.0), (5.0, 5.0), (11.0, 5.0), (8.0, 3.6), (6.4, 7.0), (9.6, 7.0), (8.0, 6.0),
             (5.6, 2.8), (10.4, 2.8)]
    done = []
    for k, (x, y) in enumerate(puffs):
        c.ellipse(x, y, 1.7, 1.5, "rice", 2 + k * 0.01, lo=2, hi=4)
        me = c.last()
        for prev in done:
            _goods10_crease(c, prev, me, 1)
        done.append(me)
    for (x, y), v in c.px.items():
        if v[0] == "rice" and v[1] == 1:
            v[0], v[1] = "bean", 3                      # tan creases between the puffs
    c.dots([(4, 7), (10, 4), (11, 7), (7, 5)], "corn", 3, z=3)
    return c.render()


# ------------------------------------------------------------------------------------------------
# goods11 (round 1)
# ------------------------------------------------------------------------------------------------


def _goods11_fish(c, head, tail, r, a, b, z, lobe=2.8):
    """A small fish from head to tail base along any line: silver belly, a darker back on the upper-left side,
    a forked tail."""
    hx, hy = head
    tx, ty = tail
    n = math.hypot(tx - hx, ty - hy)
    dx, dy = (tx - hx) / n, (ty - hy) / n
    nx, ny = dy, -dx
    if nx > 0:
        nx, ny = -nx, -ny
    c.poly([(tx - dx * 0.6, ty - dy * 0.6),
            (tx + dx * lobe + nx * lobe * 0.9, ty + dy * lobe + ny * lobe * 0.9),
            (tx + dx * lobe * 0.45, ty + dy * lobe * 0.45),
            (tx + dx * lobe - nx * lobe * 0.9, ty + dy * lobe - ny * lobe * 0.9)], a, z, normal=(0.35, 0.1, 1), lo=1, hi=3)
    m = (hx + dx * n * 0.42, hy + dy * n * 0.42)
    c.tube([(hx, hy), m, (tx, ty)], [r * 0.55, r, r * 0.4], b, z + 0.2, flat=0.9, lo=1, hi=4)
    o = r * 0.3
    c.tube([(hx + nx * o * 0.6, hy + ny * o * 0.6), (m[0] + nx * o, m[1] + ny * o), (tx + nx * o * 0.4, ty + ny * o * 0.4)],
           [r * 0.35, r * 0.62, r * 0.28], a, z + 0.3, flat=0.9, lo=1, hi=4)
    return c


def _goods11_half_onion(c, x0, y0, z=6):
    """half an onion seen on its cut face: white rings parted by thin pale lines, a pale green heart,
    the golden-brown skin round the edge drawn up to the dry pointed neck"""
    L = {"s": ("onion", 1), "S": ("onion", 3), "W": ("garlic", 5), "g": ("garlic", 2), "P": ("cabbage", 4)}
    rows = ["...S...",
            "..sWs..",
            ".sWgWs.",
            "sWgWgWs",
            "sWgPgWs",
            "sWgWgWs",
            ".sWgWs.",
            "..sss.."]
    return grid(c, rows, L, z=z, x0=x0, y0=y0)


def icon_onion_juice():
    """양파즙: a tumbler of brown onion juice (a lit surface line, the glass rim and a glint), half an onion beside
    it on the right showing its rings (사과즙 has its apple on the left of a pitcher). The old icon was the round
    flask that 씨앗 기름 also uses, told apart only by the colour of the liquid, with a pale blob for an onion."""
    c = Canvas()
    c.poly([(1.4, 1.6), (11.4, 1.6), (10.6, 14.9), (2.2, 14.9)], "glass", 1, normal=(-0.15, -0.1, 1), lo=2, hi=4)
    glass = c.last()
    c.tone([(x, y) for x, y in glass if x >= 10], -1)
    c.poly([(2.4, 4.4), (10.4, 4.4), (9.8, 14.0), (3.0, 14.0)], "plush", 2, normal=(-0.15, -0.1, 1), lo=2, hi=3)
    juice = c.last()
    c.tone([(x, y) for x, y in juice if x >= 9], -1)
    c.line(3, 4, 10, 4, "plush", 4, z=3)
    c.dots([(3, 2), (3, 3)], "glass", 5, z=3)
    c.dots([(4, 6), (4, 7)], "plush", 5, z=3)
    _goods11_half_onion(c, 8, 7)
    return c.render()


def icon_salted_mackerel():
    """간고등어: the same mackerel as 고등어 (blue back with dark wavy bars, silver belly, fins, an eye) trussed with
    two straw cords the way salted fish are sold, a few grains of coarse salt on its back. The old icon had a
    bottle-green back, a navy tail and a spray of white specks."""
    c = Canvas()
    a, b = mat("#3f7a8a"), mat("#c9d6dc")
    c.ink[b] = (a, 0)
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], a, 0,
           normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(4.5, 8.5), (7.5, 4), (9.5, 4.5), (6, 9)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.tube([(3.4, 12.4), (6.6, 9.2), (10.4, 5.4)], [2.2, 3.4, 2.1], b, 2, flat=0.9, lo=1, hi=4)
    c.tube([(2.9, 11.7), (6.0, 8.5), (9.8, 4.8)], [1.4, 2.3, 1.3], a, 3, flat=0.9, lo=1, hi=4)
    c.poly([(6, 12.6), (8.5, 14.5), (9.4, 12.4)], a, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    for pts in (((4, 8), (5, 9)), ((7, 6), (8, 7)), ((9, 4), (10, 5))):
        c.dots(pts, a, 1, z=4)
    for x, y in ((1.8, 6.4), (6.0, 2.2)):
        c.poly([(x, y), (x + 1.4, y), (x + 7.2, y + 5.8), (x + 5.8, y + 5.8)], "straw", 5, normal=(-0.3, -0.3, 1),
               lo=1, hi=3)
    c.dots([(6, 7), (8, 5)], "snow", 5, z=6)
    c.put(3, 11, "roe", 0, z=6).put(4, 10, "snow", 2, z=6)
    return c.render()


def icon_fish_sauce():
    """까나리 액젓: a clear bottle of red-amber fish sauce (red cap, white label), a slim silver 까나리 (sand lance)
    lying in front of it, its forked tail out to the right. The old icon was the long liquor bottle with a fish too
    small to see on its label."""
    c = Canvas()
    c.cyl(1.4, 6.0, 10.4, 14.9, "glass", 1, lo=1, hi=4, rounded=1.2)
    c.ellipse(5.9, 6.0, 4.5, 1.9, "glass", 1.1, lo=2, hi=4)
    c.cyl(4.2, 2.8, 7.6, 6.0, "glass", 1.2, lo=2, hi=4)
    c.cyl(2.0, 7.0, 9.8, 14.4, "onion", 2, lo=1, hi=3, rounded=1.0)
    c.cyl(3.8, 1.0, 8.0, 3.0, "fabric_red", 3, lo=1, hi=4)
    c.cyl(1.4, 8.4, 10.4, 11.0, "paper", 3, lo=3, hi=4)
    c.dots([(2, 7), (2, 12)], "onion", 4, z=4)
    a, b = mat("#4f8f8a"), "fish_silver"
    c.ink[b] = (a, 0)
    _goods11_fish(c, (6.4, 14.4), (11.8, 9.0), 1.9, a, b, 6, lobe=3.0)
    c.put(7, 13, "soot", 0, z=9)
    return c.render()


def icon_homi():
    """호미: a round wooden handle, a steel ferrule, a short iron neck bent over like a goose's neck, and the broad
    pointed triangular blade hanging from it, its tip turned back down toward the handle, a bright honed edge
    down its inside. The old icon was a flat blade set straight on the handle that read as a hatchet."""
    c = Canvas()
    c.tube([(2.3, 13.7), (6.0, 10.0)], [1.2, 1.1], "wood", 1, lo=2, hi=4)
    c.tube([(6.0, 10.0), (7.2, 8.8)], [1.25, 1.25], "steel", 2, lo=2, hi=4)
    c.tube([(7.2, 8.8), (8.2, 6.2), (9.8, 4.6), (11.6, 4.2)], [0.75, 0.62, 0.62, 0.62], "steel", 1.5, lo=2, hi=4)
    c.poly([(10.8, 3.4), (15.2, 3.8), (14.4, 6.2), (10.8, 13.4)], "steel", 3, normal=(-0.4, -0.3, 1), lo=2, hi=4)
    b = c.last()
    s = set(b)
    c.dots([(x, y) for x, y in b if (x - 1, y) not in s and (x, y - 1) in s], "blade", 4, z=4)
    return c.render()


def icon_sickle():
    """낫: a wooden handle with a steel ferrule and a broad crescent blade sweeping up and over, narrowing to its
    point, a dark back and a bright honed edge all round its inside. The old blade was a thin hook with no edge."""
    c = Canvas()
    c.tube([(2.9, 13.8), (6.0, 10.0)], [1.1, 1.0], "wood", 1, lo=2, hi=4)
    c.tube([(5.8, 10.4), (6.6, 9.4)], [1.2, 1.2], "steel", 2, lo=1, hi=3)
    c.tube([(6.6, 9.2), (5.8, 6.2), (7.2, 3.4), (10.4, 2.2), (13.4, 2.8), (14.6, 5.0), (14.2, 7.6)],
           [1.2, 1.4, 1.4, 1.2, 1.0, 0.7, 0.3], "steel", 3, lo=1, hi=3, flat=0.5)
    blade = c.last()
    s = set(blade)
    cx, cy = 10.4, 6.6
    for x, y in blade:
        dx, dy = cx - (x + 0.5), cy - (y + 0.5)
        n = max(abs(dx), abs(dy)) or 1
        if (x + round(dx / n), y + round(dy / n)) not in s:
            c.px[(x, y)][0], c.px[(x, y)][1] = "blade", 4
    return c.render()


def icon_wind_chime():
    """풍경: a temple wind bell of green-patinated bronze (a cord, a domed crown, a raised band, a flared lip) and,
    hanging below it on a cord, the flat gilt fish that catches the wind (an eye, a forked tail). The old fish
    was a green diamond and the bell a flat orange trapezoid."""
    c = Canvas()
    br = mat("#5c6a3c")
    c.put(8, 1, "wood_dark", 2, z=0)
    c.ellipse(8, 4.4, 3.0, 2.4, br, 2, lo=1, hi=4)
    c.poly([(5.0, 4.4), (11.0, 4.4), (12.3, 8.0), (3.7, 8.0)], br, 2.1, normal=(-0.3, 0, 1), lo=1, hi=4)
    for x, y in c.last():
        c.px[(x, y)][1] = max(1, min(4, round(3.0 - 2.4 * (x + 0.5 - 8) / 4.3)))
    c.tube([(3.4, 8.1), (12.6, 8.1)], [0.7, 0.7], br, 2.2, lo=2, hi=4)
    c.line(5, 6, 11, 6, br, 1, z=3)
    c.line(8, 9, 8, 10, "wood_dark", 2, z=3)
    L = {"F": ("brass", 3), "f": ("brass", 2), "G": ("brass", 4), "e": ("soot", 1), "T": ("brass", 2)}
    grid(c, ["..GGGF....T",
             ".FeFFFFF.TT",
             "FFFFFFFFFT.",
             ".fffffff.TT"], L, z=4, x0=3, y0=11)
    return c.render()


def icon_godeungeo_gui():
    """고등어구이: a whole grilled mackerel lying on a white plate, outlined where it lies on it: a pointed head
    with a cooked white eye and a gill line, a blue-grey back charred in short bars, a golden crisp belly, a
    forked tail. The old fish was a pale blue-white tube with a yellow speck beside it."""
    c = _plate(Canvas())
    back, belly = mat("#5a8090"), mat("#e0c48a")
    c.poly([(11.6, 8.0), (14.9, 5.2), (14.2, 8.0), (14.9, 10.8)], back, 2, normal=(0.3, 0, 1), lo=1, hi=3)
    c.tube([(1.2, 8.6), (3.4, 8.0), (7.0, 7.8), (10.6, 8.0), (12.2, 8.0)], [0.7, 2.0, 2.2, 1.4, 0.6], belly, 3,
           lo=1, hi=4, flat=0.9)
    body = c.last()
    for p in body:
        if p[1] <= 7:
            c.px[p][0] = back
    c.tone([p for p in body if p[1] == 7], -1)
    for x, y in ((5, 6), (7, 6), (9, 6), (6, 9), (8, 9)):
        c.put(x, y, "soot", 1, z=4)
    c.dots([(4, 7), (4, 8), (4, 9)], back, 1, z=4)
    c.put(2, 7, "snow", 5, z=5)
    fish = {p for p, v in c.px.items() if v[2] >= 2}
    for (x, y), v in list(c.px.items()):
        if v[2] < 2 and any((x + dx, y + dy) in fish for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            v[0], v[1] = back, 0
    return c.render()


def icon_beoseot_bokkeum():
    """버섯볶음: three sliced button mushrooms (each slice the mushroom's own shape: a brown-edged cap over a short
    stem, cream flesh) glossy from the pan in a little soy sauce on a white plate, green onion among them. The old
    icon was brown blobs with cream bars that did not read as mushrooms."""
    c = _plate(Canvas())
    c.ellipse(8, 10.8, 5.6, 2.0, "plush", 1, lo=1, hi=2, dome=0.5)
    L = {"b": ("plush", 1), "B": ("plush", 3), "C": ("cream", 4), "c": ("cream", 3), "d": ("cream", 2)}
    slice_ = [".BBBB.",
              "BCCCCb",
              "BCCccb",
              "bbCcbb",
              ".bcdb.",
              ".bbbb."]
    for k, (x, y) in enumerate(((1, 6), (9, 5), (5, 8))):
        grid(c, slice_, L, z=3 + k, x0=x, y0=y)
    c.dots([(7, 7), (13, 10), (3, 12)], "leaf", 3, z=9)
    c.put(8, 7, "leaf", 4, z=9)
    return c.render()


# ------------------------------------------------------------------------------------------------
# goods12 (round 1)
# ------------------------------------------------------------------------------------------------


def _goods12_steam(c, pts=((4, 4), (5, 3), (5, 2), (10, 4), (11, 3), (11, 2))):
    """wisps of steam, as on 순두부찌개"""
    return c.dots(list(pts), "snow", 2, z=5)


def _goods12_pot(c, broth, cy=8.0, lo=2, hi=4):
    """뚝배기 with its rim showing (the same pot as 순두부찌개)"""
    c.poly([(1.0, cy), (15.0, cy), (13.2, 14.6), (2.8, 14.6)], "soot", 0, normal=(-0.35, 0.3, 0.85), lo=1, hi=3)
    body = c.last()
    c.tone([(x, y) for x, y in body if x == 3 and 10 <= y <= 12], 1)
    c.tone([(x, y) for x, y in body if x >= 12], -1)
    c.ellipse(8, cy, 7.0, 3.1, "soot", 1, lo=2, hi=4, dome=0.25)
    c.ellipse(8, cy - 0.15, 6.0, 2.4, broth, 2, lo=lo, hi=hi, dome=0.3)
    return c


def _goods12_bowl(c, mat_="porcelain", lo=1, hi=4):
    """a deep bowl seen a little from above: the body, a foot, a lit rim ring"""
    c.poly([(1.2, 7.4), (14.8, 7.4), (12.6, 13.8), (3.4, 13.8)], mat_, 0, normal=(-0.1, 0.55, 0.85), lo=lo, hi=hi)
    c.ellipse(8, 13.8, 4.6, 1.2, mat_, -0.1, lo=1, hi=2)
    c.ellipse(8, 7.4, 7.0, 2.6, mat_, 0.5, lo=2, hi=5, dome=0.2)
    return c


def _goods12_contour(c, back, front, ink):
    """pixels of a back shape that touch a front shape become a dark line, so pieces of one material read apart"""
    fr = set(front)
    for x, y in back:
        if (x, y) in fr or (x, y) not in c.px:
            continue
        if any((x + dx, y + dy) in fr for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            c.px[(x, y)][0], c.px[(x, y)][1] = ink


def _goods12_pieces(c, sprite, legend, slots, ink, z=10):
    """the same small sprite placed at each slot back to front, each parted from those behind by a dark line"""
    done = []
    for k, (x, y) in enumerate(slots):
        grid(c, sprite, legend, z=z + k, x0=x, y0=y)
        me = [(x + i, y + j) for j, row in enumerate(sprite) for i, ch in enumerate(row) if ch != "."]
        for prev in done:
            _goods12_contour(c, prev, me, ink)
        done.append(me)
    return done


def _goods12_clip(c, rows, legend, keep, z, x0=0, y0=0):
    """rows of letters placed only where `keep` has a pixel (food that must stay inside its broth)"""
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != "." and (x0 + x, y0 + y) in keep:
                c.put(x0 + x, y0 + y, *legend[ch], z=z)
    return c


def icon_gamja_jorim():
    """감자조림: five cubes of potato braised glossy in soy, each lit on its top face with a glint and parted from
    the one behind by a dark line, heaped in a pool of soy glaze on a white plate, sesame and scallion on top. The old
    cubes ran together into a tan heap with cream specks that read as a pie."""
    c = _plate(Canvas())
    c.ellipse(8, 10.9, 5.8, 2.0, "leather", 1, lo=1, hi=2, dome=0.5)
    G = mat("#a86224")
    L = {"H": (G, 5), "T": (G, 4), "t": (G, 3), "F": (G, 2), "f": (G, 1)}
    cube = ["HTTt",
            "TTtt",
            "FFFf",
            "FFff"]
    _goods12_pieces(c, cube, L, ((2, 7), (10, 7), (6, 5), (4, 9), (8, 9)), (G, 0))
    c.dots([(7, 6), (11, 8)], "cream", 5, z=40)
    c.dots([(9, 10), (10, 10)], "leaf", 4, z=40)
    return c.render()


def icon_pajeon():
    """파전: a big round pancake fried golden on a plate with two long scallions laid across it, each a dark-green
    stalk with a lit edge and its white root end at the left. The old scallions were three blotchy diagonal bands
    that turned the pancake into a green-yellow smear."""
    c = _plate(Canvas())
    c.ellipse(8, 8.6, 7.0, 3.9, "corn", 2, lo=2, hi=4, dome=0.3)
    pan = set(c.last())
    for x0, y0 in ((1, 7), (1, 10)):
        for x in range(x0, 15):
            y = y0 - (x - x0) // 4
            for dy, step in ((0, 3), (1, 1)):
                if (x, y + dy) in pan:
                    c.put(x, y + dy, "leaf", step, z=4)
        for x in range(x0, 15):
            if (x, y0 - (x - x0) // 4) in pan:
                c.put(x, y0 - (x - x0) // 4, "garlic", 5, z=5).put(x, y0 - (x - x0) // 4 + 1, "garlic", 3, z=5)
                break
    return c.render()


def icon_kalguksu():
    """칼국수: a brown earthenware bowl of light broth with wavy cream strands of hand-cut noodle and zucchini,
    a pair of wooden chopsticks lifting a strand out of it. The old icon was a white bowl of white noodles on white
    broth that read as a bowl of mush; the dark bowl, the strands and the chopsticks say noodles and tell it from
    미역국's white bowl."""
    c = _goods12_bowl(Canvas(), "onggi", lo=1, hi=3)
    c.ellipse(8, 7.3, 6.0, 2.0, "doenjang", 1, lo=4, hi=4, dome=0.2)
    broth = set(c.last())
    L = {"N": ("rice", 5), "n": ("rice", 3), "z": ("zucchini", 2), "Z": ("zucchini", 4), "k": ("soot", 1)}
    _goods12_clip(c, ["...NNNn..NNNn...",
                      "..n...NNNn..NNn.",
                      "...NNn..Zz.Nn...",
                      ".....nNNNn......"], L, broth, 5, y0=6)
    S = {"A": ("wood", 4), "B": ("wood", 2), "N": ("rice", 5), "n": ("rice", 3)}
    grid(c, ["...............A",
             ".............AA.",
             "...........AA..B",
             ".........AA..BB.",
             "........N..BB...",
             "........N.B.....",
             "........n.......",
             ], S, z=8)
    return c.render()


def icon_injeolmi():
    """인절미: a neat pyramid of six soft rice-cake cubes rolled in yellow roasted-bean powder (lit tops,
    shaded sides, each parted from the one behind by a fine line) on a dark wooden plate dusted with the powder. The
    old cubes were bright corn-yellow on a white plate with yellow specks, laid out like the potato cubes of 감자조림."""
    c = Canvas()
    c.ellipse(8, 11.4, 6.9, 3.3, "wood_dark", 0, lo=1, hi=3, dome=0.3)
    c.ellipse(8, 11.0, 5.4, 2.3, "wood_dark", 0.1, lo=2, hi=2, dome=-0.3)
    K = mat("#d6b45e")
    c.ellipse(8, 11.2, 5.0, 1.6, K, 0.5, lo=4, hi=4)
    L = {"H": (K, 5), "T": (K, 4), "F": (K, 3), "f": (K, 2)}
    cube = [".HTT.",
            "HTTTT",
            "FFFFf",
            "FFFff",
            ".fff."]
    _goods12_pieces(c, cube, L, ((5, 2), (3, 5), (8, 5), (1, 8), (5, 8), (9, 8)), (K, 1))
    return c.render()


def icon_dakbokkeumtang():
    """닭볶음탕: a wide black two-handled braising pan of red sauce with a chicken drumstick (glazed meat,
    white bone end like the vanilla chicken), potato chunks, carrot and scallion in it. The old icon was a red disc in
    a black rim, with ovals that did not read as chicken."""
    c = Canvas()
    c.rect(0.0, 7.6, 2.0, 9.4, "iron", 0, normal=(0, -0.5, 0.8), lo=1, hi=3)
    c.rect(14.0, 7.6, 16.0, 9.4, "iron", 0, normal=(0, -0.5, 0.8), lo=1, hi=3)
    c.ellipse(8, 9.8, 7.0, 4.4, "iron", 0.5, lo=1, hi=3, dome=0.6)
    c.ellipse(8, 8.6, 7.0, 3.4, "iron", 1, lo=2, hi=4, dome=0.2)
    c.ellipse(8, 8.6, 6.0, 2.6, "pepper", 2, lo=2, hi=4, dome=0.3)
    P = mat("#e8c870")
    L = {"M": ("plush", 4), "m": ("plush", 3), "d": ("plush", 2), "K": ("snow", 5), "k": ("snow", 3), "B": ("snow", 4),
         "Y": (P, 5), "y": (P, 4), "o": ("fox", 4), "g": ("leaf", 4)}
    rows = ["...........KK...",
            "..........kKk...",
            ".........kB.....",
            "...Y..MMMB......",
            "..Yy.MMmmd...o..",
            "..yy.dmmd..YY...",
            ".....g.....yy...",
            "........o.......",
            ]
    grid(c, rows, L, z=5, x0=0, y0=3)
    _goods12_contour(c, [p for p, v in c.px.items() if v[0] == "pepper"],
                     [p for p, v in c.px.items() if v[0] == "plush"], ("plush", 0))
    return c.render()


def icon_gamjatang():
    """감자탕: the black 뚝배기 of the other stews with orange-red broth, a big pork bone with meat on it
    standing up to the upper left (a knobbed end), a potato, perilla leaves and steam. The bone leans left so it
    does not repeat 삼계탕's drumsticks pointing up-right; the old bone was a flat white bar."""
    c = _goods12_pot(Canvas(), "terracotta", lo=2, hi=4)
    c.tube([(8.6, 8.2), (5.2, 3.4)], [1.1, 0.9], "garlic", 3, lo=2, hi=4)
    c.ellipse(3.9, 3.0, 1.2, 1.1, "garlic", 3.1, lo=2, hi=5)
    c.ellipse(6.1, 2.2, 1.2, 1.1, "garlic", 3.1, lo=2, hi=5)
    c.ellipse(8.6, 7.2, 2.2, 1.6, "leather", 3.2, lo=2, hi=4)
    c.ellipse(5.0, 7.2, 1.4, 1.0, "leather", 3.15, lo=2, hi=4)
    c.ellipse(11.8, 8.0, 1.8, 1.3, "corn", 3.3, lo=2, hi=4)
    grid(c, [".PP", "pPp", "pp."], {"P": ("leaf_dark", 4), "p": ("leaf_dark", 2)}, z=6, x0=2, y0=7)
    return _goods12_steam(c, ((11, 4), (12, 3), (12, 2))).render()


def icon_pumpkin_makgeolli():
    """단호박 막걸리: the plastic 막걸리 bottle (straight body, conical shoulder, big screw cap) in a
    pale pumpkin-cream with an orange cap and label, a ribbed orange pumpkin with its stem leaning on its foot. It
    was the old long-necked liquor bottle in orange, differing from 막걸리 only by colour."""
    c = Canvas()
    B = mat("#ecd29a")
    c.cyl(2.4, 5.6, 9.6, 14.9, B, 1, lo=2, hi=4, rounded=0.8)
    c.poly([(2.4, 6.0), (4.4, 3.6), (7.6, 3.6), (9.6, 6.0)], B, 1.1, normal=(-0.2, -0.6, 0.8), lo=2, hi=4)
    c.cyl(4.4, 2.8, 7.6, 4.0, B, 1.2, lo=2, hi=4)
    c.cyl(3.6, 0.8, 8.4, 3.0, "fox", 2, lo=1, hi=3)
    c.line(4, 2, 7, 2, "fox", 1, z=3)
    c.cyl(2.4, 8.4, 9.6, 12.2, "fox", 3, lo=1, hi=3)
    c.line(4, 10, 7, 10, "snow", 3, z=4)
    c.dots([(3, 6), (3, 7), (3, 13)], "snow", 3)
    bottle = c.where()
    c.ellipse(11.8, 12.2, 3.6, 2.8, "fox", 5, lo=2, hi=4, dome=0.8)
    pk = c.last()
    c.tone([(x, y) for x, y in pk if x in (10, 13) and 10 <= y <= 14], -1)
    _goods12_contour(c, bottle, pk, ("fox", 0))
    c.put(12, 9, "leaf", 2, z=6).put(13, 8, "leaf", 3, z=6)
    return c.render()


def icon_bokbunja_wine():
    """복분자주: a dark berry-red bottle with sloping shoulders (a Burgundy, so it is not 포도주's
    straight Bordeaux), a gold cap and a cream label, a black raspberry (dark drupelets, a green calyx) leaning on its
    foot. The old icon was the long liquor bottle with a red cross on its label, like a medicine bottle."""
    c = Canvas()
    c.cyl(1.8, 8.0, 9.8, 14.9, "wine", 1, lo=1, hi=4, rounded=1.0)
    c.poly([(1.8, 8.6), (4.4, 4.6), (7.2, 4.6), (9.8, 8.6)], "wine", 1.05, normal=(-0.25, -0.55, 0.8), lo=1, hi=4)
    c.tone([(x, y) for x, y in c.last() if x >= 7], -1)
    c.cyl(4.6, 1.4, 7.0, 5.0, "wine", 1.2, lo=1, hi=4)
    c.cyl(4.4, 0.4, 7.2, 2.6, "gold", 2, lo=2, hi=4)
    c.ink["cream"] = ("wine", 0)
    c.cyl(1.8, 9.6, 9.8, 12.8, "cream", 3, lo=3, hi=4)
    c.dots([(3, 8), (3, 13), (5, 3)], "wine", 5)
    bottle = c.where()
    B = mat("#5a1a3a")
    L = {"A": (B, 4), "a": (B, 3), "d": (B, 2), "k": (B, 1), "H": (B, 5), "g": ("leaf", 3), "G": ("leaf", 4)}
    grid(c, ["...gGg..",
             "..gHAag.",
             ".HaAdAa.",
             ".AdAdAd.",
             ".aAdAda.",
             "..dAdk..",
             "...dk..."], L, z=6, x0=8, y0=8)
    _goods12_contour(c, bottle, [p for p, v in c.px.items() if v[2] == 6], (B, 0))
    return c.render()


def icon_rum():
    """럼: a dark-brown glass rum bottle with a cork, a lip ring and a cream label with a dark band, two stalks of sugar
    cane (its raw material, with dark joints) standing behind it. The old icon was the round corked potion flask with
    a sprout on its label."""
    c = Canvas()
    for cx in (12.6, 14.2):
        c.tube([(cx - 1.0, 14.3), (cx, 1.6)], [0.7, 0.7], "sprout", 0.5, lo=2, hi=4)
        cane = c.last()
        c.tone([(x, y) for x, y in cane if y in (4, 8, 12)], -2)
    G = mat("#5a3214")
    c.cyl(2.6, 6.8, 10.4, 14.9, G, 1, lo=1, hi=4, rounded=1.2)
    c.ellipse(6.5, 6.8, 3.9, 2.0, G, 1.1, lo=2, hi=4)
    c.cyl(5.1, 2.4, 7.9, 6.8, G, 1.2, lo=1, hi=4)
    c.cyl(4.7, 2.2, 8.3, 3.2, G, 1.3, lo=2, hi=4)
    c.rect(5.1, 0.3, 7.9, 2.4, "wood", 2, normal=(-0.3, -0.5, 1))
    c.ink["paper"] = (G, 0)
    c.rect(2.6, 9.6, 10.4, 12.6, "paper", 3, normal=(-0.1, 0, 1), lo=3, hi=4)
    c.line(4, 11, 8, 11, "wood_dark", 1, z=4)
    c.dots([(3, 8), (3, 13)], G, 5)
    return c.render()


# ------------------------------------------------------------------------------------------------
# goods13 (round 1)
# ------------------------------------------------------------------------------------------------


def icon_corn_whiskey():
    """옥수수 위스키: a flat-shouldered square bottle full of deep amber whiskey, a wooden T-cork, a black label
    with a yellow corn cob in its green husk."""
    c = Canvas()
    g = {"K": ("wood", 4), "k": ("wood", 3), "j": ("wood", 2),
         "W": ("glass", 5), "g": ("glass", 4), "h": ("glass", 3), "i": ("glass", 2),
         "T": ("topaz", 4), "A": ("topaz", 3), "a": ("topaz", 2), "d": ("topaz", 1),
         "P": ("soot", 2), "q": ("soot", 1),
         "C": ("corn", 4), "c": ("corn", 3), "L": ("leaf", 4), "l": ("leaf", 3)}
    grid(c, [
        "................",
        ".....KKKKkj.....",
        "......kkjj......",
        "......Wghi......",
        "......Wghi......",
        "....WWgghhii....",
        "...ATAAaaaaad...",
        "...ATaaaaaaad...",
        "...APPPPCPPqd...",
        "...APPPCcPPqd...",
        "...APPPCcPPqd...",
        "...APPLlcLPqd...",
        "...APPPlLPPqd...",
        "...ATqqqqqqqd...",
        "...aaaaaaaddd...",
        "................",
    ], g)
    return c.render()


def icon_potato_vodka():
    """감자 보드카: a tall slim bottle of clear frosted glass with a red cap and a red label, a potato at its foot."""
    c = Canvas()
    g = {"R": ("fabric_red", 5), "r": ("fabric_red", 4), "s": ("fabric_red", 3), "t": ("fabric_red", 2),
         "W": ("glass", 5), "g": ("glass", 4), "h": ("glass", 3), "i": ("glass", 2), "j": ("glass", 1),
         "S": ("snow", 5), "n": ("snow", 3)}
    grid(c, [
        "................",
        "....Rrst........",
        "....rsst........",
        "....Wghi........",
        "....Wghi........",
        "...Wgghhi.......",
        "..WgghhhhJ......",
        "..WgghhhhJ......",
        "..rRrrrssJ......",
        "..rSSSSnsJ......",
        "..rrrrsstJ......",
        "..WgghhhhJ......",
        "..WgghhhhJ......",
        "..WgghhhhJ......",
        "..ghhhiiij......",
        "................",
    ], dict(g, J=("glass", 2)))
    p = {"A": ("bean", 4), "a": ("bean", 3), "b": ("bean", 2), "d": ("bean", 1), "e": ("doenjang", 3)}
    grid(c, [
        "...........Aa...",
        "..........AAab..",
        ".........Aeaabd.",
        ".........aaabed.",
        "..........bbdd..",
    ], p, z=20, y0=10)
    c.ink["bean"] = ("wood", 0)
    return c.render()


def icon_seed_oil():
    """씨앗 기름: a round glass cruet of golden oil, a cork with a brass pour spout (the round flasks of 럼 and
    진주 향수 have none), a few pale seeds like vanilla pumpkin seeds at its foot."""
    c = Canvas()
    c.ellipse(6.4, 10.4, 5.0, 4.4, "glass", 1, lo=1, hi=4)
    c.ellipse(6.4, 11.0, 4.0, 3.5, "corn", 2, lo=1, hi=4)
    c.cyl(4.9, 3.6, 7.9, 6.6, "glass", 1, lo=2, hi=4)
    c.rect(4.6, 2.2, 8.2, 3.8, "wood", 3, normal=(-0.3, -0.6, 1), lo=2, hi=4)
    c.tube([(7.0, 2.6), (10.6, 1.0)], [0.5, 0.45], "brass", 4, lo=2, hi=4)
    c.dots([(3, 8), (3, 9), (4, 7)], "glass", 5)
    sd = {"A": ("bean", 5), "a": ("bean", 4), "b": ("bean", 3), "d": ("bean", 2)}
    grid(c, [
        "............Aa..",
        "............ab..",
        "..........Aa....",
        "..........abAa..",
        "............bd..",
    ], sd, z=20, y0=10)
    c.ink["bean"] = ("wood", 1)
    return c.render()


def icon_compost():
    """퇴비: dark compost heaped over a wooden bin of upright slats (like the vanilla composter), the compost
    showing through the gaps, leaf bits and straw on top (not the solid labelled crate of 전통 장 세트)."""
    c = Canvas()
    c.ellipse(8, 8.6, 6.2, 5.0, "soil", 1, lo=1, hi=4, dome=0.9)
    b = {"P": ("wood", 4), "q": ("wood", 2), "r": ("wood", 1),
         "S": ("wood", 4), "s": ("wood", 3), "t": ("wood", 2), "g": ("soil", 1), "G": ("soil", 0)}
    grid(c, [
        ".Pq..........Pq.",
        ".PSSSSSSSSSSSSq.",
        ".PqgPsgPsgPsgPq.",
        ".PqgPsgPsgPsgPq.",
        ".PqGPsGPsGPsGPq.",
        ".PqGPsGPsGPsGPq.",
        ".rttttttttttttr.",
    ], b, z=5, y0=8)
    c.dots([(5, 5), (10, 6)], "leaf", 3, z=6).dots([(6, 5), (11, 6)], "leaf", 4, z=6)
    c.dots([(9, 5), (10, 4)], "straw", 4, z=6)
    c.dots([(4, 7), (12, 8)], "soil", 0, z=6)
    return c.render()


def icon_refined_oil():
    """정제유: a square steel tin with a red screw cap, a cream label with a big golden drop of oil."""
    c = Canvas()
    c.rect(2.0, 5.0, 11.5, 15.0, "steel", 1, normal=(-0.2, -0.1, 1), lo=3, hi=4)
    c.poly([(11.5, 5.0), (14.5, 2.5), (14.5, 12.5), (11.5, 15.0)], "steel", 1, normal=(0.85, 0, 0.4), lo=2, hi=2)
    c.poly([(2.0, 5.0), (5.0, 2.5), (14.5, 2.5), (11.5, 5.0)], "steel", 1.2, normal=(-0.2, -0.8, 0.6), lo=4, hi=4)
    c.cyl(9.6, 0.8, 12.4, 3.4, "fabric_red", 2, lo=2, hi=4)
    c.rect(3.0, 6.5, 10.5, 14.0, "cream", 2, normal=(-0.1, 0, 1), lo=4, hi=4)
    d = {"o": ("topaz", 1), "A": ("topaz", 3), "a": ("topaz", 2), "T": ("topaz", 5)}
    grid(c, [
        "......o.........",
        ".....oAo........",
        "....oATao.......",
        "....oAaao.......",
        ".....ooo........",
    ], d, z=4, y0=8)
    return c.render()


def icon_soap():
    """비누: a bar of pale green soap seen from above, an oval pressed into its top, foam bubbles on its corner."""
    c = Canvas()
    m = {"H": ("musk", 5), "T": ("musk", 4), "e": ("musk", 2), "E": ("musk", 5), "t": ("musk", 3),
         "F": ("musk", 3), "f": ("musk", 2), "g": ("musk", 1)}
    grid(c, [
        "...HHHHHHHHHT...",
        "..HTTTTTTTTTTt..",
        ".HTTTeeeeeeTTTt.",
        ".TTTeTTTTTTETTt.",
        ".TTTTEEEEEETTTt.",
        ".TTTTTTTTTTTTtt.",
        ".FFFFFFFFFFFFff.",
        ".ffffffffffffgg.",
        "..gggggggggggg..",
    ], m, z=1, y0=5)
    for (x, y, r) in ((3.6, 3.8, 2.1), (7.4, 2.2, 1.3), (12.4, 4.0, 1.2)):
        c.ellipse(x, y, r, r, "glass", 4 + y * 0.01, lo=3, hi=4, dome=-0.4)
        c.put(int(x - r * 0.45), int(y - r * 0.45), "glass", 5, z=6)
    return c.render()


def icon_fertilizer():
    """비료: a stitched white sack with ears at its top corners, a green sprout printed on it over a green band."""
    c = Canvas()
    g = {"W": ("snow", 5), "w": ("snow", 4), "v": ("snow", 3), "u": ("snow", 2), "x": ("snow", 1),
         "G": ("leaf", 4), "g": ("leaf", 3), "h": ("leaf", 2), "k": ("leaf", 1),
         "s": ("stone", 3)}
    grid(c, [
        "................",
        ".ww..........vu.",
        "..wwwwwwwwwwvu..",
        "..svsvsvsvsvsu..",
        "..Wwwwwwwwwwvu..",
        ".Wwwwwg.Gwwwvux.",
        ".WwwwGgGgwwwvux.",
        ".Wwwwwggwwwwvux.",
        ".Wwwwwwgwwwwvux.",
        ".Wwwwwwgwwwwvux.",
        ".GGGGGGGGGGGghk.",
        ".Ggggggggggghhk.",
        ".Ggggggggggghhk.",
        "..hhhhhhhhhhhk..",
        "................",
        "................",
    ], g)
    c.ink["snow"] = ("stone", 1)
    return c.render()


def icon_scented_candle():
    """향초: a glass jar of pink wax with a pressed violet on it, a bright teardrop flame on its wick."""
    c = Canvas()
    c.cyl(2.5, 6.0, 13.5, 14.9, "glass", 1, lo=2, hi=4, rounded=1.4)
    c.cyl(3.5, 8.0, 12.5, 14.0, "pink", 2, lo=2, hi=4, rounded=1.0)
    c.ellipse(8, 8.0, 4.5, 1.2, "pink", 2.2, lo=3, hi=4)
    c.ellipse(8, 11.4, 1.6, 1.6, "petal_purple", 4, lo=1, hi=3)
    c.put(8, 11, "petal_yellow", 2, z=5)
    c.dots([(3, 9), (3, 10), (3, 11)], "glass", 5)
    f = {"Y": ("fire", 5), "y": ("fire", 4), "o": ("fire", 3), "w": ("soot", 1)}
    grid(c, [
        "........o.......",
        ".......oy.......",
        ".......yYo......",
        ".......oyo......",
        "........w.......",
    ], f, z=6, y0=1)
    c.ink["fire"] = ("fire", 2)
    return c.render()


_goods13_CUBE = [
    "................",
    ".......TT.......",
    ".....TTTTTT.....",
    "...TTTTTTTTTT...",
    ".TTTTTTTTTTTTTT.",
    ".LLTTTTTTTTTTRR.",
    ".LLLLTTTTTTRRRR.",
    ".LLLLLLTTRRRRRR.",
    ".LLLLLLLRRRRRRR.",
    ".LLLLLLLRRRRRRR.",
    ".LLLLLLLRRRRRRR.",
    ".LLLLLLLRRRRRRR.",
    "...LLLLLRRRRR...",
    ".....LLLRRR.....",
    ".......LR.......",
    "................",
]


def _goods13_cube(mat, top=4, left=3, right=2, marks=()):
    """A block seen from the corner with clean 2:1 edges like a vanilla block in the inventory: a lit top, a
    mid left face, a shaded right face. `marks` are (x, y, delta) tweaks on top of that (its texture)."""
    c = Canvas()
    for y, row in enumerate(_goods13_CUBE):
        for x, ch in enumerate(row):
            if ch != ".":
                c.put(x, y, mat, {"T": top, "L": left, "R": right}[ch], z=1)
    for x, y, d in marks:
        c.tone([(x, y)], d)
    return c


def icon_compressed_stone():
    """압축 석재: a dense grey block with clean edges like a vanilla block, short darker streaks of pressed stone."""
    c = _goods13_cube("stone", top=4, left=3, right=1, marks=[
        (5, 3, -1), (6, 3, -1), (9, 5, -1), (10, 5, -1), (11, 3, -1), (8, 2, 1),
        (2, 7, -1), (3, 7, -1), (5, 9, -1), (6, 9, -1), (3, 11, -1), (4, 11, -1), (6, 13, -1),
        (10, 8, 1), (11, 8, 1), (12, 10, 1), (13, 10, 1), (9, 12, 1), (10, 12, 1)])
    return c.render()


def icon_compressed_dirt():
    """압축 흙: a dense block of brown earth, the same block as 압축 석재, dark clods and pale grit in it."""
    c = _goods13_cube("soil", top=4, left=3, right=1, marks=[
        (5, 3, -1), (6, 3, -1), (10, 2, 1), (9, 5, -1), (10, 5, -1), (3, 4, 1), (12, 4, -1), (7, 4, 1),
        (2, 7, -1), (3, 7, -1), (5, 8, 1), (4, 10, -1), (5, 10, -1), (3, 12, 1), (6, 12, -1), (2, 9, 1),
        (10, 7, 1), (13, 9, 1), (9, 10, 1), (10, 10, 1), (11, 12, 1), (12, 6, 1), (12, 8, -1)])
    return c.render()


# ------------------------------------------------------------------------------------------------
# goods14 (round 1)
# ------------------------------------------------------------------------------------------------


def _goods14_row(c, y, x0, steps, mat="millstone", z=1):
    """one row of fixed ramp steps starting at x0 (None leaves a pixel empty)"""
    for k, s in enumerate(steps):
        if s is not None:
            c.put(x0 + k, y, mat, s, z)


def icon_stone_lantern():
    """석등: a Korean granite lantern on a post: jewel finial, wide roof with upturned eaves, a fire chamber
    with one glowing window, a lotus slab, a short octagonal post and a stepped base."""
    c = Canvas()
    R = _goods14_row
    # finial (보주)
    R(c, 1, 7, [5, 3])
    R(c, 2, 6, [4, 4, 3, 2])
    # roof (옥개석): lit sloped top, upturned eave tips, dark front edge
    R(c, 3, 4, [5, 4, 4, 4, 4, 4, 3, 3])
    R(c, 4, 1, [4, None, 4, 4, 4, 4, 4, 4, 3, 3, 3, 3, None, 2])
    R(c, 5, 1, [3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 1])
    # fire chamber (화사석): shadow under the roof, three faces, the window in the front one
    R(c, 6, 4, [2, 1, 1, 1, 1, 1, 1, 1])
    for y in (7, 8, 9):
        R(c, y, 4, [4, 3, None, None, None, None, 2, 1])
    R(c, 7, 6, [3, 4, 4, 3], "lamp", 2)
    R(c, 8, 6, [4, 5, 5, 3], "lamp", 2)
    R(c, 9, 6, [3, 4, 3, 2], "lamp", 2)
    # lotus slab (상대석)
    R(c, 10, 3, [5, 4, 4, 4, 4, 4, 3, 3, 3, 2])
    # post (간주석)
    R(c, 11, 6, [4, 3, 3, 2])
    R(c, 12, 6, [4, 3, 3, 2])
    # lower lotus and plinth (하대석, 지대석)
    R(c, 13, 4, [5, 4, 4, 4, 3, 3, 3, 2])
    R(c, 14, 2, [3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 2, 1])
    return c.render()


ICONS = {
    "bluefin_tuna": icon_bluefin_tuna,
    "coke": icon_coke,
    "pearl": icon_pearl,
    "red_seabream": icon_red_seabream,
    "sturgeon": icon_sturgeon,
    "chili_powder": icon_chili_powder,
    "corn_flour": icon_corn_flour,
    "flour": icon_flour,
    "grape_juice": icon_grape_juice,
    "jeweled_crown": icon_jeweled_crown,
    "pearl_necklace": icon_pearl_necklace,
    "rice": icon_rice,
    "dried_pollack": icon_dried_pollack,
    "gochujang": icon_gochujang,
    "prepared_puffer": icon_prepared_puffer,
    "seabream_sashimi": icon_seabream_sashimi,
    "tomato_sauce": icon_tomato_sauce,
    "caviar": icon_caviar,
    "iron_chain": icon_iron_chain,
    "oak_barrel": icon_oak_barrel,
    "steel_ingot": icon_steel_ingot,
    "steel_toolset": icon_steel_toolset,
    "hobakjeon": icon_hobakjeon,
    "kimchi": icon_kimchi,
    "kkakdugi": icon_kkakdugi,
    "oi_sobagi": icon_oi_sobagi,
    "ornate_armor": icon_ornate_armor,
    "strawberry_cake": icon_strawberry_cake,
    "sundubu_jjigae": icon_sundubu_jjigae,
    "tomato_pasta": icon_tomato_pasta,
    "tuna_sushi": icon_tuna_sushi,
    "hanjeongsik": icon_hanjeongsik,
    "makgeolli": icon_makgeolli,
    "melon_liqueur": icon_melon_liqueur,
    "melon_syrup": icon_melon_syrup,
    "soju": icon_soju,
    "wine": icon_wine,
    "apple_juice": icon_apple_juice,
    "brown_sugar": icon_brown_sugar,
    "cut_diamond": icon_cut_diamond,
    "cut_emerald": icon_cut_emerald,
    "diamond_ring": icon_diamond_ring,
    "potato_starch": icon_potato_starch,
    "sapphire_brooch": icon_sapphire_brooch,
    "vintage_wine": icon_vintage_wine,
    "copper_pot": icon_copper_pot,
    "gamasot": icon_gamasot,
    "jeotgal": icon_jeotgal,
    "shrimp_meat": icon_shrimp_meat,
    "soy_sauce": icon_soy_sauce,
    "doenjang_jjigae": icon_doenjang_jjigae,
    "gamjajeon": icon_gamjajeon,
    "gyeranmari": icon_gyeranmari,
    "iron_lantern": icon_iron_lantern,
    "jeyuk": icon_jeyuk,
    "amethyst_pendant": icon_amethyst_pendant,
    "cheongju": icon_cheongju,
    "cider": icon_cider,
    "copper_bracelet": icon_copper_bracelet,
    "gangnaengi": icon_gangnaengi,
    "quartz_figurine": icon_quartz_figurine,
    "strawberry_wine": icon_strawberry_wine,
    "beoseot_bokkeum": icon_beoseot_bokkeum,
    "fish_sauce": icon_fish_sauce,
    "godeungeo_gui": icon_godeungeo_gui,
    "homi": icon_homi,
    "onion_juice": icon_onion_juice,
    "salted_mackerel": icon_salted_mackerel,
    "sickle": icon_sickle,
    "wind_chime": icon_wind_chime,
    "bokbunja_wine": icon_bokbunja_wine,
    "dakbokkeumtang": icon_dakbokkeumtang,
    "gamja_jorim": icon_gamja_jorim,
    "gamjatang": icon_gamjatang,
    "injeolmi": icon_injeolmi,
    "kalguksu": icon_kalguksu,
    "pajeon": icon_pajeon,
    "pumpkin_makgeolli": icon_pumpkin_makgeolli,
    "rum": icon_rum,
    "compost": icon_compost,
    "compressed_dirt": icon_compressed_dirt,
    "compressed_stone": icon_compressed_stone,
    "corn_whiskey": icon_corn_whiskey,
    "fertilizer": icon_fertilizer,
    "potato_vodka": icon_potato_vodka,
    "refined_oil": icon_refined_oil,
    "scented_candle": icon_scented_candle,
    "seed_oil": icon_seed_oil,
    "soap": icon_soap,
    "stone_lantern": icon_stone_lantern,
}
