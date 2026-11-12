"""The fisher's traps and pens (fish.yml trap / pen) and what they bring: the 손질대's new prepared fish that cooks buy (광어회,
건어물, 새우젓, 어묵 반죽), the 조리대's dishes made from them, the mill's 생선 사료, and the inventory icons of the 통발 and the
가두리 (placed, they show their 3D model: models/fishing_gear.py). 16x16 icons on the same pixelart Canvas and palette materials as
economy_goods.py (light from the top left, the darkest step as the outline). economy_goods.ICONS takes these in."""

from economy_goods import _bowl, _plate
from stsv_assets.pixelart import Canvas

ICONS = {}


def icon(fn):
    ICONS[fn.__name__] = lambda: fn().render()
    return fn


# ================================================================================================
# 가공대: 생선 사료
# ================================================================================================

@icon
def fish_feed():
    """생선 사료: a tied burlap sack with a blue fish printed on it, brown pellets showing at the mouth and spilt beside it."""
    c = Canvas()
    c.poly([(3.0, 5.0), (11.0, 4.2), (12.6, 14.6), (2.2, 14.6)], "burlap", 1, normal=(-0.25, 0.15, 1), lo=1, hi=4)
    c.ellipse(7.0, 4.8, 4.2, 1.5, "burlap", 1.2, lo=2, hi=4)
    c.ellipse(7.0, 4.6, 2.9, 0.8, "doenjang", 1.3, lo=1, hi=3)
    c.tube([(3.6, 6.4), (10.8, 5.6)], [0.5, 0.5], "straw", 1.4, lo=2, hi=4)
    # the fish on the sack
    c.tube([(4.6, 10.4), (8.6, 10.0)], [0.9, 0.6], "fish_blue", 1.5, lo=2, hi=4)
    c.poly([(8.4, 10.0), (10.0, 8.8), (10.0, 11.2)], "fish_blue", 1.55, normal=(0.3, 0, 1), lo=2, hi=3)
    c.put(5, 10, "soot", 1, z=4)
    c.dots([(13, 13), (14, 14), (13, 15), (15, 15), (14, 12)], "doenjang", 2)
    c.dots([(4, 8), (9, 13), (6, 13)], "burlap", 3)
    return c


# ================================================================================================
# 손질대: 어부의 손질감
# ================================================================================================

@icon
def flatfish_hoe():
    """광어회: translucent white slices of flatfish fanned on a plate, each with a pink edge, a perilla leaf at the side."""
    c = _plate(Canvas())
    c.ellipse(3.6, 8.8, 2.2, 1.6, "leaf", 1.5, lo=1, hi=4, dome=0.5)
    c.line(2, 9, 5, 8, "leaf_dark", 2, z=2)
    for k, x in enumerate((5.2, 7.6, 10.0, 12.4)):
        y = 9.4 - (1.2 - abs(k - 1.5)) * 0.5
        c.ellipse(x, y, 1.6, 2.6, "rice", 2 + k * 0.1, lo=2, hi=4, dome=0.5)
        c.line(round(x - 1), round(y - 2), round(x + 1), round(y - 2), "salmon", 3, z=3 + k * 0.1)
    c.dots([(6, 9), (11, 10)], "pearl", 4)
    return c


@icon
def dried_fish():
    """건어물: two flat sheets of dried filefish (쥐포), amber and fibrous, one lying over the other, a darker toasted edge."""
    c = Canvas()
    c.poly([(1.5, 5.0), (9.5, 2.5), (11.5, 9.5), (3.5, 12.0)], "topaz", 1, normal=(-0.2, -0.3, 1), lo=2, hi=4)
    back = c.last()
    c.poly([(5.0, 8.0), (13.0, 6.0), (14.5, 13.5), (6.5, 15.0)], "topaz", 2, normal=(-0.1, -0.2, 1), lo=1, hi=4)
    front = c.last()
    c.tone([(x, y) for x, y in back if (x * 2 + y) % 5 == 0], -1)
    c.tone([(x, y) for x, y in front if (x * 2 + y) % 5 == 1], -1)
    c.lit_edge(front)
    c.dots([(13, 13), (7, 14), (14, 11)], "doenjang", 1)
    return c


@icon
def saeujeot():
    """새우젓: a little glass jar of tiny pink salted shrimp in brine, a red screw lid."""
    c = Canvas()
    c.cyl(3.4, 4.6, 12.6, 15.0, "glass", 1, lo=1, hi=4, rounded=1.2)
    c.rect(4.4, 7.0, 11.6, 14.2, "salmon", 1.5, normal=(-0.2, -0.2, 1), lo=2, hi=3)
    c.dots([(5, 8), (7, 9), (9, 8), (10, 10), (6, 11), (8, 12), (11, 12), (5, 13), (9, 13)], "pink", 4)
    c.dots([(6, 9), (10, 11), (7, 13)], "snow", 1)
    c.cyl(4.0, 2.0, 12.0, 4.8, "fabric_red", 2, lo=1, hi=4)
    c.line(5, 3, 11, 3, "fabric_red", 4, z=3)
    c.dots([(4, 6), (4, 7), (4, 10)], "glass", 5)
    return c


@icon
def fish_paste():
    """어묵 반죽: a smooth pale mound of ground fish paste on a wooden board, a steel scraper resting against it."""
    c = Canvas()
    c.poly([(0.8, 11.0), (11.5, 8.4), (15.2, 12.0), (4.2, 15.0)], "wood", 0, normal=(0, 0.6, 0.8), lo=1, hi=4)
    c.ellipse(7.6, 9.6, 4.8, 3.0, "cream", 1, lo=2, hi=4, dome=0.8)
    c.dots([(6, 8), (8, 8), (9, 9)], "pearl", 4)
    c.poly([(10.0, 3.0), (14.0, 2.0), (13.6, 6.2), (10.6, 7.0)], "blade", 2, normal=(0.2, -0.3, 1), lo=2, hi=4)
    c.tube([(11.0, 7.4), (12.0, 9.6)], [0.6, 0.6], "wood_dark", 2.1, lo=2, hi=4)
    return c


# ================================================================================================
# 조리대: 어부의 손질감으로 만드는 요리
# ================================================================================================

@icon
def mulhoe():
    """물회: icy red broth in a white bowl, white slices of raw fish, green cucumber strips and an ice cube on top."""
    c = _bowl(Canvas())
    c.ellipse(8, 7.7, 6.4, 2.2, "pepper", 1, lo=2, hi=4, dome=0.3)
    for x, y in ((5.0, 7.2), (8.2, 6.8), (11.0, 7.4)):
        c.ellipse(x, y, 1.6, 0.9, "rice", 2, lo=2, hi=4, dome=0.5)
    c.line(4, 8, 6, 9, "cucumber", 3, z=3).line(10, 8, 12, 9, "cucumber", 3, z=3)
    c.rect(7, 4.4, 9, 6.4, "ice", 2.5, normal=(-0.3, -0.4, 1), lo=2, hi=4)
    c.dots([(6, 3), (9, 2), (11, 3)], "snow", 1)
    return c


@icon
def eomuktang():
    """어묵탕: clear broth in a black pot, two skewers of folded fish cake standing out of it, green onion floating."""
    c = Canvas()
    c.poly([(1.2, 9.0), (14.8, 9.0), (13.2, 14.6), (2.8, 14.6)], "soot", 0, normal=(-0.1, 0.5, 0.85), lo=1, hi=3)
    c.tube([(1.6, 9.3), (14.4, 9.3)], [0.8, 0.8], "soot", 0.2, lo=2, hi=3)
    c.ellipse(8, 9.0, 6.2, 2.0, "water", 1, lo=2, hi=4, dome=0.3)
    for x0, top in ((5.0, 1.0), (10.5, 2.0)):
        c.line(round(x0), round(top), round(x0 + 1.5), 9, "wood", 3, z=2)
        for k in range(3):
            y = top + 1.6 + k * 2.0
            c.ellipse(x0 + 0.4 * k + (0.9 if k % 2 else -0.6), y, 1.6, 1.0, "doenjang", 3 + k * 0.1, lo=2, hi=5, dome=0.7)
    c.dots([(4, 10), (8, 10), (12, 9)], "leaf", 3)
    return c


@icon
def bossam():
    """보쌈: slices of boiled pork fanned on a plate, a heap of red kimchi and a little dish of pink salted shrimp."""
    c = _plate(Canvas())
    for k, x in enumerate((3.8, 6.0, 8.2)):
        c.rect(x - 1.1, 7.2, x + 1.1, 12.0, "cream", 2 + k * 0.1, normal=(-0.3, -0.3, 1), lo=2, hi=4)
        c.line(round(x - 1), 7, round(x + 1), 7, "doenjang", 2, z=3 + k * 0.1)
    c.ellipse(11.6, 8.6, 2.8, 2.4, "pepper", 3, lo=1, hi=4, dome=0.9)
    c.dots([(10, 8), (12, 7), (13, 9)], "cabbage", 4)
    c.ellipse(11.8, 12.4, 2.2, 1.1, "porcelain", 3.5, lo=2, hi=4)
    c.dots([(11, 12), (12, 12), (13, 12)], "pink", 4)
    return c


@icon
def dried_fish_stirfry():
    """건어물 볶음: glossy strips of dried fish stir-fried in soy and syrup, heaped on a plate, sesame on top."""
    c = _plate(Canvas())
    c.ellipse(8, 8.4, 5.8, 3.6, "topaz", 1, lo=1, hi=4, dome=0.8)
    for (x0, y0), (x1, y1) in (((4, 9), (7, 7)), ((7, 10), (10, 8)), ((9, 7), (12, 9)), ((5, 7), (8, 6)), ((10, 10), (12, 8))):
        c.line(x0, y0, x1, y1, "doenjang", 2, z=3)
    c.dots([(6, 6), (9, 6), (11, 7), (8, 9), (5, 9)], "lamp", 4)
    c.dots([(7, 8), (10, 9), (6, 10)], "cream", 4)
    return c


# ================================================================================================
# 통발 · 가두리 (inventory icons; placed, they show their 3D model)
# ================================================================================================

@icon
def fish_trap_icon():
    """통발: a dark green net cylinder on orange hoops lying corner to corner, its funnel mouth dark, a rope up to a red float."""
    c = Canvas()
    c.tube([(2.8, 12.0), (11.6, 6.6)], [3.4, 3.4], "leaf_dark", 1, lo=1, hi=4)
    net = c.last()
    c.tone([(x, y) for x, y in net if (x + y) % 2 == 0], -1)
    # three hoops across the net (perpendicular to its length)
    for t in (0.08, 0.5, 0.92):
        cx, cy = 2.8 + 8.8 * t, 12.0 - 5.4 * t
        c.line(round(cx - 1.8), round(cy - 2.9), round(cx + 1.8), round(cy + 2.9), "straw", 3, z=2)
    c.ellipse(11.9, 6.4, 1.4, 2.4, "soot", 2.5, lo=0, hi=1)
    c.line(12, 4, 14, 1, "straw", 3, z=3)
    c.ellipse(14.0, 1.6, 1.3, 1.2, "fabric_red", 4, lo=1, hi=4)
    return c


@icon
def fish_pen_icon():
    """가두리: a square floating frame of black pipe seen from above, orange floats at its corners, a net full of water and
    fish inside."""
    c = Canvas()
    c.poly([(8.0, 2.6), (15.4, 7.6), (8.0, 12.6), (0.6, 7.6)], "rubber", 1, normal=(0, -0.6, 0.8), lo=1, hi=3)
    c.poly([(8.0, 4.6), (12.4, 7.6), (8.0, 10.6), (3.6, 7.6)], "water", 2, normal=(0, -0.5, 0.9), lo=1, hi=3)
    water = c.last()
    c.tone([(x, y) for x, y in water if (x + y) % 3 == 0], -1)
    c.poly([(0.6, 7.6), (8.0, 12.6), (8.0, 14.6), (0.6, 9.6)], "leaf_dark", 0.5, normal=(-0.4, 0.2, 1), lo=1, hi=3)
    c.poly([(8.0, 12.6), (15.4, 7.6), (15.4, 9.6), (8.0, 14.6)], "leaf_dark", 0.5, normal=(0.5, 0.2, 1), lo=1, hi=3)
    for x, y in ((8, 2), (15, 7), (8, 12), (1, 7)):
        c.ellipse(x + 0.5, y + 0.5, 1.3, 1.0, "vermilion", 4, lo=2, hi=4)
    c.line(6, 7, 8, 7, "fish_silver", 4, z=5).line(8, 9, 10, 9, "fish_silver", 4, z=5)
    c.put(9, 6, "fish_silver", 3, z=5)
    return c


NAMES = {
    "fish_feed": "생선 사료", "flatfish_hoe": "광어회", "dried_fish": "건어물", "saeujeot": "새우젓", "fish_paste": "어묵 반죽",
    "mulhoe": "물회", "eomuktang": "어묵탕", "bossam": "보쌈", "dried_fish_stirfry": "건어물 볶음",
    "fish_trap_icon": "통발", "fish_pen_icon": "가두리",
}
