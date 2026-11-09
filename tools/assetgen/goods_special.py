"""Special materials (jobs.yml specials: found by any job, far more often by their own) and the goods made from them or from the
fish the fishers land in bulk (the 2026-11 job income rebalance). 16x16 icons on the same pixelart Canvas and palette materials
as economy_goods.py (light from the top left, the darkest step as the outline). economy_goods.ICONS takes these in."""
import math

from economy_goods import _bowl, _long_bottle, _plate, _ttukbaegi
from stsv_assets.pixelart import Canvas

ICONS = {}


def icon(fn):
    ICONS[fn.__name__] = lambda: fn().render()
    return fn


# ================================================================================================
# Special materials
# ================================================================================================

@icon
def wild_ginseng():
    """산삼: a pale forked root with fine hairs, a thin neck up to three five-leaf sprigs and a red berry cluster."""
    c = Canvas()
    c.tube([(7.5, 7.5), (8.0, 10.5), (6.2, 13.8)], [1.5, 1.6, 0.6], "cream", 2, lo=1, hi=4)
    c.tube([(8.2, 10.0), (11.0, 13.6)], [1.1, 0.5], "cream", 2.2, lo=1, hi=4)
    c.tube([(7.6, 10.6), (4.4, 11.4)], [0.8, 0.4], "cream", 2.1, lo=1, hi=3)
    c.dots([(5, 14), (12, 14), (3, 12), (10, 15)], "doenjang", 3)
    for (x, y) in ((7, 9), (8, 11), (7, 12)):
        c.put(x, y, "cream", 1, z=4)
    c.line(7, 7, 8, 3, "leaf", 1, z=3)
    for (x0, y0), (x1, y1) in (((8, 3), (5, 1)), ((8, 3), (11, 1)), ((8, 3), (4, 4)), ((8, 3), (12, 4))):
        c.tube([(x0, y0), (x1, y1)], [0.4, 0.9], "leaf", 3, lo=2, hi=4)
    c.dots([(8, 2), (9, 2)], "fabric_red", 3)
    c.put(8, 1, "fabric_red", 4, z=6)
    return c


@icon
def golden_ear():
    """황금 이삭: one heavy ear of grain glowing gold, its long awns fanning up, a curl of stalk below."""
    c = Canvas()
    c.line(5, 15, 7, 11, "straw", 1, z=1)
    for k in range(6):
        y = 10.4 - k * 1.45
        x = 7.6 + k * 0.62
        c.ellipse(x - 1.0, y, 1.35, 0.95, "gold", 3 + k * 0.01, lo=2, hi=5)
        c.ellipse(x + 1.0, y - 0.3, 1.35, 0.95, "gold", 3.005 + k * 0.01, lo=1, hi=4)
    for x0, y0, x1, y1 in ((8, 3, 6, 0), (10, 2, 10, 0), (11, 4, 14, 1), (9, 6, 13, 4)):
        c.line(x0, y0, x1, y1, "straw", 4, z=2)
    c.dots([(6, 9), (8, 5)], "lamp", 5)
    return c


@icon
def silk_cocoon():
    """누에고치: two oval cocoons of soft white silk with a few loose threads around them."""
    c = Canvas()
    c.ellipse(5.8, 10.4, 4.6, 3.0, "pearl", 1, lo=1, hi=4, dome=0.9)
    a = c.last()
    c.ellipse(10.6, 6.2, 4.4, 2.8, "pearl", 2, lo=1, hi=4, dome=0.9)
    b = c.last()
    # the silk wound round and round: faint diagonal bands across each cocoon, a waist in the middle
    c.tone([(x, y) for x, y in a if (x + y) % 3 == 0], -1)
    c.tone([(x, y) for x, y in b if (x + y) % 3 == 1], -1)
    c.dots([(6, 9), (5, 11), (6, 12)], "pearl", 1)
    c.dots([(10, 5), (11, 7), (10, 6)], "pearl", 2)
    c.dots([(4, 9), (9, 5)], "snow", 1)
    c.line(0, 14, 2, 12, "cream", 3, z=1).line(14, 8, 15, 11, "cream", 3, z=1)
    return c


@icon
def red_coral():
    """붉은 산호: a branching red coral, round-tipped branches spreading up from a short trunk."""
    c = Canvas()
    branches = [((8, 15), (8, 10)), ((8, 10), (4, 6)), ((4, 6), (3, 2)), ((4, 6), (6, 3)), ((8, 10), (11, 5)), ((11, 5), (13, 2)),
                ((11, 5), (9, 2)), ((8, 12), (13, 10)), ((13, 10), (14, 7))]
    for (x0, y0), (x1, y1) in branches:
        c.tube([(x0, y0), (x1, y1)], [1.2, 0.9], "crimson", 1, lo=1, hi=4)
    for x, y in ((3, 2), (6, 3), (13, 2), (9, 2), (14, 7)):
        c.ellipse(x + 0.5, y + 0.5, 1.2, 1.2, "crimson", 2, lo=2, hi=5)
    c.dots([(4, 7), (8, 11), (11, 6)], "pink", 4)
    return c


@icon
def ambergris():
    """용연향: a waxy grey-amber lump, mottled, with darker streaks and a glossy amber face."""
    c = Canvas()
    c.ellipse(8.2, 9.2, 6.6, 5.0, "doenjang", 1, lo=1, hi=4, dome=0.8)
    lump = c.last()
    c.ellipse(5.4, 7.6, 3.2, 2.4, "glow_amber", 2, lo=2, hi=4, dome=0.7)
    c.tone([(x, y) for x, y in lump if (x * 5 + y * 3) % 7 == 0], -1)
    c.tone([(x, y) for x, y in lump if (x * 3 + y * 7) % 11 == 0], 1)
    c.line(9, 11, 13, 9, "stone", 2, z=3).line(7, 12, 9, 13, "stone", 2, z=3)
    c.dots([(4, 6), (5, 6)], "snow", 1)
    return c


@icon
def abalone():
    """전복: an ear-shaped shell, rough brown outside rim, the rainbow mother-of-pearl inside and its row of holes."""
    c = Canvas()
    c.ellipse(8, 9, 7.0, 5.4, "bark", 1, lo=1, hi=3, dome=0.7)
    c.ellipse(8.6, 9.4, 5.6, 4.1, "pearl", 2, lo=1, hi=4, dome=-0.5)
    inner = c.last()
    c.tone([(x, y) for x, y in inner if x + y < 15], 1)
    for (x, y), m in zip(((6, 8), (8, 10), (10, 8), (11, 11), (7, 11)), ("glow_cyan", "pink", "sapphire", "sprout", "petal_purple")):
        c.put(x, y, m, 3, z=4)
    for x in (3, 5, 7, 9):
        c.put(x, 5 if x < 8 else 4, "soot", 1, z=4)
    return c


@icon
def meteoric_iron():
    """운철: a dark pitted iron lump fallen from the sky, a bright cut face showing the crossed metal pattern."""
    c = Canvas()
    c.ellipse(8, 9, 6.8, 5.6, "blacksteel", 1, lo=1, hi=3, dome=0.8)
    lump = c.last()
    c.tone([(x, y) for x, y in lump if (x * 7 + y * 3) % 6 == 0], -1)
    c.poly([(8.5, 4.5), (13.5, 6.5), (12.5, 12.0), (8.0, 10.5)], "steel", 2, normal=(0.3, -0.4, 0.9), lo=2, hi=5)
    for k in range(3):
        c.line(9 + k * 1.5, 5 + k * 0.5, 8 + k * 1.5, 10 + k * 0.5, "blade", 2, z=3)
    c.line(9, 7, 13, 8, "blade", 4, z=4)
    c.dots([(4, 7), (5, 11), (6, 6)], "soot", 0)
    c.dots([(11, 5)], "snow", 2)
    return c


@icon
def rough_crystal():
    """수정 원석: clear six-sided quartz points growing out of a grey rock."""
    c = Canvas()
    c.ellipse(8, 13.2, 7.0, 2.6, "stone", 0, lo=1, hi=3, dome=0.8)
    rock = c.last()
    c.tone([(x, y) for x, y in rock if (x * 3 + y * 5) % 7 == 0], -1)
    # three points leaning apart, the middle one in front; each a lit left face and a shaded right face
    for (bx, by, tx, ty, w, z) in ((4.0, 12.5, 2.4, 4.4, 1.6, 1), (12.2, 12.5, 13.8, 5.2, 1.6, 1.5), (7.8, 13.0, 8.2, 1.0, 2.2, 2)):
        c.poly([(bx - w, by), (tx - w * 0.8, ty + 1.6), (tx, ty), (tx, by + 0.4)], "ice", z, normal=(-0.6, -0.3, 0.75), lo=2, hi=5)
        c.poly([(tx, ty), (tx + w * 0.8, ty + 1.6), (bx + w, by), (tx, by + 0.4)], "ice", z + 0.1, normal=(0.7, 0.1, 0.6), lo=1, hi=3)
    c.line(7, 3, 7, 10, "snow", 1, z=3)
    c.dots([(2, 6), (13, 7)], "snow", 1)
    return c


# ================================================================================================
# 세공대 (광부)
# ================================================================================================

@icon
def cut_crystal():
    """세공 수정: a long clear cut crystal standing on its point, facets catching light."""
    c = Canvas()
    c.poly([(8, 0.8), (12.6, 4.2), (12.6, 11.0), (8, 15.2), (3.4, 11.0), (3.4, 4.2)], "ice", 1, normal=(0, 0, 1), lo=2, hi=4)
    c.poly([(8, 0.8), (3.4, 4.2), (3.4, 11.0), (8, 8)], "ice", 1.1, normal=(-0.7, -0.4, 0.6), lo=2, hi=5)
    c.poly([(8, 0.8), (12.6, 4.2), (12.6, 11.0), (8, 8)], "ice", 1.1, normal=(0.7, 0.2, 0.6), lo=1, hi=3)
    c.poly([(3.4, 11.0), (8, 8), (12.6, 11.0), (8, 15.2)], "ice", 1.2, normal=(0, 0.7, 0.6), lo=1, hi=3)
    c.dots([(5, 4), (5, 5), (6, 3)], "snow", 1)
    return c


@icon
def coral_brooch():
    """산호 브로치: a round gold setting holding a polished red coral cabochon, little gold beads around it."""
    c = Canvas()
    c.ellipse(8, 8.4, 6.8, 6.4, "gold", 1, lo=1, hi=4)
    c.ellipse(8, 8.4, 4.6, 4.2, "crimson", 2, lo=2, hi=5)
    for a in range(0, 360, 45):
        x = 8 + math.cos(math.radians(a)) * 5.7
        y = 8.4 + math.sin(math.radians(a)) * 5.4
        c.put(int(x), int(y), "gold", 4, z=4)
    c.dots([(6, 6), (6, 7)], "pink", 4)
    return c


@icon
def silk_pearl_necklace():
    """명주 진주 목걸이: pearls strung on a pale silk cord tied in a knot, a sapphire drop in the middle."""
    c = Canvas()
    pts = [(1.8, 1.6), (2.4, 4.4), (3.5, 6.9), (5.0, 9.0), (6.6, 10.5)]
    for i, (x, y) in enumerate(pts + [(16 - x, y) for x, y in pts]):
        c.ellipse(x, y, 1.15, 1.15, "pearl", 1 + i % 2)
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        c.line(x0, y0, x1, y1, "cream", 3, z=0.5)
    c.ellipse(8, 11.4, 1.6, 1.0, "cream", 3, lo=2, hi=4)
    c.line(7, 12, 6, 14, "cream", 3, z=3).line(9, 12, 10, 14, "cream", 3, z=3)
    c.poly([(6.4, 12.4), (9.6, 12.4), (8, 15.6)], "sapphire", 2, normal=(0.2, 0.3, 1))
    c.put(7, 13, "sapphire", 5)
    return c


@icon
def ambergris_norigae():
    """용연향 노리개: a gold knot ornament holding an amber bead of ambergris, long silk tassels hanging below."""
    c = Canvas()
    c.line(8, 0, 8, 2, "fabric_red", 3, z=1)
    c.ellipse(8, 3.6, 2.6, 1.8, "fabric_red", 2, lo=2, hi=4)
    c.ellipse(8, 7.2, 3.2, 2.8, "glow_amber", 2, lo=1, hi=4)
    c.ellipse(8, 7.2, 3.6, 3.2, "gold", 1.5, lo=1, hi=4)
    c.hole(8, 7.2, 2.6, 2.2)
    c.ellipse(8, 7.2, 2.4, 2.1, "glow_amber", 3, lo=2, hi=5)
    for x in (5.5, 7, 8.5, 10):
        c.tube([(x + 0.5, 10.4), (x + (0.5 if x < 8 else 1.0), 15.2)], [0.55, 0.45], "fabric_red", 1.8, lo=1, hi=4)
    c.rect(5, 10, 11, 11, "gold", 3, normal=(0, -0.3, 1))
    c.put(7, 6, "snow", 2, z=5)
    return c


# ================================================================================================
# 대장간 (대장장이)
# ================================================================================================

@icon
def iron_pan():
    """무쇠 팬: a round black cast-iron pan lying a little tilted, a wooden handle to the lower left."""
    c = Canvas()
    c.tube([(5.2, 10.2), (1.2, 14.2)], [0.9, 0.9], "wood", 1, lo=1, hi=4)
    c.ellipse(9.6, 7.0, 6.2, 5.4, "blacksteel", 2, lo=1, hi=3, dome=0.4)
    c.ellipse(9.8, 7.0, 4.8, 4.0, "blacksteel", 2.1, lo=1, hi=2, dome=-0.4)
    c.line(6, 3, 9, 2, "blade", 3, z=4)
    c.dots([(2, 13), (3, 12)], "wood", 4)
    return c


@icon
def iron_brazier():
    """무쇠 화로: a squat black cast-iron brazier on three short legs, glowing coals heaped in its bowl."""
    c = Canvas()
    for x in (3, 8, 12):
        c.line(x, 12, x, 14, "blacksteel", 1, z=0)
    c.poly([(1.4, 6.6), (14.6, 6.6), (12.4, 12.6), (3.6, 12.6)], "blacksteel", 1, normal=(-0.1, 0.5, 0.85), lo=1, hi=3)
    c.tube([(1.8, 6.8), (14.2, 6.8)], [0.8, 0.8], "blacksteel", 1.2, lo=2, hi=4)
    c.ellipse(8, 6.2, 5.6, 2.0, "fire", 2, lo=2, hi=5, dome=0.6)
    for x, y in ((5, 5), (8, 4), (11, 5), (7, 6), (10, 6)):
        c.put(x, y, "soot", 1, z=4)
    c.dots([(6, 4), (9, 3), (10, 4)], "lamp", 5)
    c.dots([(7, 1), (9, 2)], "fire", 4)
    return c


@icon
def sheath():
    """칼집: a long brown leather sheath lying corner to corner, iron nails along its seam and an iron tip."""
    c = Canvas()
    c.tube([(3.0, 13.0), (13.0, 3.0)], [1.7, 1.3], "leather", 1, lo=1, hi=4, flat=0.6)
    for k in range(5):
        x, y = 5 + k * 1.8, 11 - k * 1.8
        c.put(round(x) + 1, round(y) + 1, "steel", 4, z=4)
    c.tube([(1.6, 14.4), (3.4, 12.6)], [1.3, 1.3], "steel", 2, lo=2, hi=4)
    c.ellipse(13.4, 2.6, 1.6, 1.6, "brass", 2, lo=2, hi=4)
    return c


@icon
def padlock():
    """금박 자물쇠: a heavy iron padlock, gold leaf worked over its face, a keyhole in the middle."""
    c = Canvas()
    c.ellipse(8, 5.0, 4.2, 4.0, "steel", 1, lo=2, hi=4)
    c.hole(8, 5.6, 2.6, 3.0)
    c.rect(2.6, 6.6, 13.4, 14.6, "blacksteel", 2, normal=(-0.2, -0.1, 1), lo=1, hi=3)
    c.rect(3.6, 7.6, 12.4, 13.6, "gold", 2.5, normal=(-0.2, -0.2, 1), lo=2, hi=4)
    c.ellipse(8, 9.8, 1.1, 1.1, "soot", 3, lo=0, hi=1)
    c.line(8, 10, 8, 12, "soot", 0, z=4)
    c.dots([(4, 8), (5, 8)], "lamp", 5)
    return c


@icon
def crystal_lantern():
    """수정 랜턴: an iron lantern with a clear crystal glowing cold-blue inside its frame."""
    c = Canvas()
    c.ellipse(8, 1.8, 1.6, 1.4, "steel", 0, lo=2, hi=4)
    c.hole(8, 1.8, 0.6, 0.5)
    c.poly([(4, 5), (8, 3), (12, 5)], "blacksteel", 1, normal=(0, -0.6, 0.8), lo=2, hi=4)
    c.rect(4, 5, 12, 13, "glow_cyan", 2, normal=(-0.2, 0, 1), lo=2, hi=5)
    c.poly([(8, 6), (10, 8.5), (8, 12), (6, 8.5)], "ice", 3, normal=(-0.3, -0.3, 1), lo=3, hi=5)
    for x in (4, 11):
        c.line(x, 5, x, 12, "blacksteel", 1, z=4)
    c.line(4, 5, 11, 5, "blacksteel", 2, z=4)
    c.rect(3, 13, 13, 14.6, "blacksteel", 3, normal=(0, 0.4, 0.9), lo=1, hi=3)
    return c


@icon
def coral_inlay_box():
    """산호 자개함: a black lacquer box, its lid inlaid with shimmering mother-of-pearl and a red coral flower."""
    c = Canvas()
    c.poly([(1, 7), (15, 7), (15, 14.6), (1, 14.6)], "soot", 1, normal=(0, 0.3, 1), lo=1, hi=3)
    c.poly([(1, 3.4), (15, 3.4), (15, 7), (1, 7)], "soot", 1.2, normal=(0, -0.6, 0.8), lo=2, hi=4)
    c.line(1, 7, 14, 7, "gold", 3, z=3)
    for (x, y), m in zip(((3, 5), (5, 4), (11, 5), (13, 4), (4, 11), (12, 11), (7, 12)),
                         ("pearl", "glow_cyan", "pearl", "pink", "glow_cyan", "pearl", "pink")):
        c.put(x, y, m, 3, z=4)
    c.ellipse(8, 10.6, 2.2, 2.0, "crimson", 3, lo=2, hi=5)
    c.put(8, 10, "pink", 4, z=5)
    c.rect(7, 6, 9, 8, "gold", 4, normal=(0, 0, 1))
    return c


@icon
def meteor_sword():
    """운철검: a dark blade with the silver crossed pattern of meteoric iron, a gold guard, a grip bound in white silk."""
    c = Canvas()
    c.tube([(5.6, 10.4), (14.4, 1.6)], [1.2, 0.45], "blacksteel", 1, lo=2, hi=4, flat=0.5)
    for k in range(4):
        x, y = 7.5 + k * 1.7, 8.3 - k * 1.7
        c.put(round(x), round(y), "blade", 3, z=3).put(round(x + 1), round(y - 1), "steel", 4, z=3)
    c.line(7, 8, 13, 2, "snow", 1, z=2)
    c.tube([(3.0, 8.6), (7.4, 13.0)], [0.85, 0.85], "gold", 3, lo=2, hi=5)
    c.tube([(4.6, 11.4), (2.6, 13.4)], [0.75, 0.75], "pearl", 2, lo=2, hi=4)
    c.dots([(3, 12), (4, 11)], "cream", 4, z=4)
    c.ellipse(1.9, 14.1, 1.2, 1.2, "gold", 4, lo=2, hi=4)
    return c


# ================================================================================================
# 조리대 (요리사)
# ================================================================================================

@icon
def fish_platter():
    """모둠 생선구이: three small grilled fish side by side on a plate, grill marks and a lemon-yellow wedge."""
    c = _plate(Canvas())
    for k, (y, mat) in enumerate(((6.4, "fish_silver"), (8.8, "fish_blue"), (11.0, "fish_silver"))):
        c.tube([(3.0, y + 0.6), (12.2, y - 0.4)], [0.9, 1.2], mat, 2 + k * 0.1, lo=1, hi=4)
        c.poly([(12.0, y - 0.4), (14.2, y - 1.6), (14.2, y + 0.8)], mat, 2.05 + k * 0.1, normal=(0.5, 0, 0.8), lo=1, hi=3)
        for x in (5, 8, 10):
            c.put(x, round(y), "soot", 1, z=4)
    c.put(3, 6, "soot", 0, z=5).put(3, 9, "soot", 0, z=5)
    return c


@icon
def bogeo_jiri():
    """복어 지리: clear puffer-fish soup in a pot, white fish pieces, green onion and a slice of radish."""
    c = _ttukbaegi(Canvas(), "water")
    for x, y in ((5.4, 7.6), (9.6, 7.2), (7.4, 8.8)):
        c.ellipse(x, y, 1.8, 1.1, "cream", 2, lo=2, hi=4, dome=0.6)
    c.ellipse(11.4, 8.6, 1.5, 0.9, "snow", 2.1, lo=1, hi=3)
    c.dots([(4, 9), (12, 7), (8, 7)], "leaf", 3)
    c.dots([(6, 4), (8, 3), (10, 4)], "snow", 1)
    return c


@icon
def pollack_stew():
    """동태찌개: red pollack stew with chunks of fish, tofu and radish, green onion on top, in a black pot."""
    c = _ttukbaegi(Canvas(), "pepper")
    c.ellipse(5.8, 7.4, 2.0, 1.2, "fish_silver", 2, lo=2, hi=4, dome=0.6)
    c.ellipse(10.2, 7.8, 1.9, 1.1, "fish_silver", 2, lo=2, hi=4, dome=0.6)
    c.rect(7, 7, 9, 9, "cream", 2.2, normal=(-0.3, -0.3, 1), lo=3, hi=4)
    c.dots([(4, 9), (12, 9), (8, 6)], "leaf", 3)
    c.dots([(6, 4), (9, 3)], "snow", 1)
    return c


@icon
def agujjim():
    """아귀찜: a heap of red-sauced monkfish and bean sprouts on a plate, sesame and green onion over it."""
    c = _plate(Canvas())
    c.ellipse(8, 8.0, 6.0, 4.4, "pepper", 2, lo=1, hi=4, dome=0.9)
    for (x0, y0), (x1, y1) in (((4, 8), (6, 6)), ((9, 10), (11, 8)), ((6, 10), (7, 8)), ((11, 6), (12, 9))):
        c.line(x0, y0, x1, y1, "cream", 3, z=3)
    c.ellipse(7.2, 6.6, 1.5, 1.0, "fish_silver", 3, lo=2, hi=4)
    c.dots([(5, 5), (9, 4), (12, 7)], "leaf", 3)
    c.dots([(8, 9), (10, 6), (6, 7)], "cream", 4)
    return c


@icon
def golden_injeolmi():
    """황금 인절미: soft rice-cake squares rolled in golden powder, stacked on a plate, a golden glint."""
    c = _plate(Canvas())
    for k, (x, y) in enumerate(((4.6, 9.4), (8.2, 9.8), (11.6, 9.2), (6.4, 6.8), (10.0, 6.6))):
        c.rect(x - 1.8, y - 1.6, x + 1.8, y + 1.5, "gold", 2 + k * 0.1, normal=(-0.3, -0.4, 1), lo=2, hi=4)
    c.dots([(4, 8), (9, 9), (7, 6), (11, 5)], "lamp", 5)
    c.dots([(13, 3)], "lamp", 4)
    return c


@icon
def ginseng_samgyetang():
    """산삼 삼계탕: a whole chicken in milky broth in a black pot, a wild ginseng root lying across it."""
    c = _ttukbaegi(Canvas(), "rice")
    c.ellipse(8, 7.0, 4.0, 2.4, "cream", 2, lo=2, hi=4, dome=0.8)
    c.tube([(3.6, 5.6), (11.6, 3.4)], [0.8, 0.5], "straw", 3, lo=2, hi=4)
    c.line(11, 3, 13, 1, "leaf", 3, z=4).line(12, 3, 14, 3, "leaf", 2, z=4)
    c.put(9, 6, "fabric_red", 3, z=4).put(6, 8, "leaf", 3, z=4)
    return c


@icon
def abalone_porridge():
    """전복죽: green-tinged rice porridge in a white bowl, slices of abalone and a sprinkle of sesame on top."""
    c = _bowl(Canvas())
    c.ellipse(8, 7.6, 6.4, 2.2, "sprout", 1, lo=2, hi=3, dome=0.3)
    for x, y in ((5, 7), (8, 6.6), (11, 7.2)):
        c.ellipse(x + 0.5, y + 0.4, 1.4, 0.8, "doenjang", 2, lo=2, hi=4)
    c.dots([(6, 8), (9, 8), (10, 6)], "cream", 4)
    c.dots([(6, 3), (8, 2), (10, 3)], "snow", 1)
    return c


@icon
def japchae():
    """잡채: glossy glass noodles tossed with carrot, onion and green onion, heaped on a plate, sesame on top."""
    c = _plate(Canvas())
    c.ellipse(8, 8.4, 6.0, 3.8, "doenjang", 2, lo=2, hi=4, dome=0.8)
    for (x0, y0), (x1, y1) in (((3, 9), (8, 6)), ((5, 10), (12, 7)), ((7, 11), (13, 9)), ((4, 7), (10, 5)), ((9, 10), (12, 6))):
        c.line(x0, y0, x1, y1, "glass", 2, z=3)
    c.dots([(5, 8), (10, 7), (8, 10)], "terracotta", 4)
    c.dots([(7, 7), (11, 9)], "leaf", 3)
    c.dots([(6, 6), (9, 8), (12, 8)], "cream", 4)
    return c


# ================================================================================================
# 증류기 (연금술사)
# ================================================================================================

@icon
def sikhye():
    """식혜: a bowl of pale sweet rice punch, a few grains of rice floating on top, a pine nut in the middle."""
    c = _bowl(Canvas(), "brass")
    c.ellipse(8, 7.6, 6.4, 2.2, "cream", 1, lo=2, hi=4, dome=0.3)
    c.dots([(5, 7), (7, 8), (10, 7), (11, 8), (6, 8)], "rice", 4)
    c.put(8, 7, "doenjang", 4, z=5)
    c.dots([(6, 3), (8, 2), (10, 3)], "snow", 1)
    return c



@icon
def ginseng_liquor():
    """인삼주: a tall glass jar of amber spirit with a whole ginseng root floating inside, a cloth over the lid."""
    c = Canvas()
    c.cyl(3.0, 3.6, 13.0, 14.9, "glass", 1, lo=2, hi=4, rounded=1.5)
    c.cyl(3.8, 5.4, 12.2, 14.2, "glow_amber", 2, lo=1, hi=3, rounded=1.2)
    c.tube([(7.0, 6.6), (8.0, 10.0), (6.2, 13.2)], [1.2, 1.3, 0.6], "pearl", 3, lo=2, hi=5)
    c.tube([(8.2, 9.8), (10.4, 12.8)], [0.8, 0.5], "pearl", 3.1, lo=2, hi=5)
    c.dots([(7, 5), (8, 4)], "leaf", 3)
    c.ellipse(8, 2.6, 5.6, 1.7, "fabric_red", 3, lo=2, hi=4, dome=0.6)
    c.line(3, 3, 12, 3, "straw", 2, z=4)
    c.dots([(4, 6), (4, 7), (4, 8)], "glass", 5)
    return c


@icon
def golden_cheongju():
    """황금 청주: a white porcelain bottle of clear rice wine, a gold label and a golden ear tied at the neck."""
    c = _long_bottle(Canvas(), "porcelain")
    c.cyl(6.3, 0.8, 9.7, 2.6, "gold", 2, lo=2, hi=4)
    c.cyl(4.2, 9.2, 11.8, 12.8, "gold", 3, lo=2, hi=4)
    c.line(6, 10, 9, 10, "fabric_red", 2, z=4).line(6, 11, 8, 11, "fabric_red", 3, z=4)
    c.line(10, 4, 13, 2, "gold", 4, z=4).line(11, 5, 14, 4, "straw", 3, z=4)
    c.dots([(5, 7), (5, 8)], "snow", 2)
    return c


@icon
def meteor_elixir():
    """운철 단약: three dark round elixir pills with a starry silver glint, on a small gold dish."""
    c = Canvas()
    c.ellipse(8, 12.6, 6.6, 2.4, "gold", 0, lo=1, hi=4, dome=0.4)
    for k, (x, y) in enumerate(((5.2, 9.4), (10.8, 9.6), (8.0, 6.4))):
        c.ellipse(x, y, 2.7, 2.6, "blacksteel", 1 + k * 0.1, lo=1, hi=4)
        c.put(int(x) - 1, int(y) - 1, "steel", 4, z=5)
        c.put(int(x), int(y) + 1, "glow_cyan", 3, z=5)
    c.dots([(2, 3), (13, 2), (12, 5)], "lamp", 4)
    return c


@icon
def ambergris_perfume():
    """용연향 향수: a round crystal flacon of warm amber scent, a gold collar and a tasselled atomiser bulb."""
    c = Canvas()
    c.ellipse(7.4, 10.4, 5.8, 4.6, "glass", 1, lo=1, hi=4)
    c.ellipse(7.4, 11.0, 4.6, 3.6, "glow_amber", 2, lo=2, hi=4)
    c.cyl(5.6, 4.0, 9.2, 6.4, "gold", 2, lo=2, hi=4)
    c.tube([(9.2, 4.6), (12.4, 3.0)], [0.4, 0.4], "gold", 2.5, lo=2, hi=4)
    c.ellipse(13.4, 4.2, 1.6, 1.8, "crimson", 3, lo=2, hi=4)
    c.line(13, 6, 13, 8, "crimson", 3, z=4)
    c.dots([(4, 8), (4, 9), (5, 7)], "glass", 5)
    return c


@icon
def crystal_vintage():
    """수정병 빈티지: deep red wine in a cut-crystal decanter with a faceted crystal stopper."""
    c = Canvas()
    c.poly([(3.0, 7.2), (13.0, 7.2), (14.0, 14.9), (2.0, 14.9)], "glass", 1, normal=(-0.1, 0, 1), lo=2, hi=4)
    c.poly([(3.6, 9.0), (12.4, 9.0), (13.2, 14.2), (2.8, 14.2)], "wine", 2, normal=(-0.1, 0, 1), lo=1, hi=3)
    for x in (5, 8, 11):
        c.line(x, 9, x, 14, "ice", 3, z=3)
    c.cyl(6.4, 4.0, 9.6, 7.2, "glass", 1.5, lo=2, hi=4)
    c.poly([(8, 0.6), (10.6, 2.4), (8, 4.4), (5.4, 2.4)], "ice", 2, normal=(-0.3, -0.4, 1), lo=2, hi=5)
    c.dots([(3, 8), (3, 9)], "snow", 1)
    return c


NAMES = {
    "wild_ginseng": "산삼", "golden_ear": "황금 이삭", "silk_cocoon": "누에고치", "red_coral": "붉은 산호", "ambergris": "용연향",
    "abalone": "전복", "meteoric_iron": "운철", "rough_crystal": "수정 원석",
    "cut_crystal": "세공 수정", "coral_brooch": "산호 브로치", "silk_pearl_necklace": "명주 진주 목걸이", "ambergris_norigae": "용연향 노리개",
    "iron_pan": "무쇠 팬", "iron_brazier": "무쇠 화로", "sheath": "칼집", "padlock": "금박 자물쇠", "crystal_lantern": "수정 랜턴", "coral_inlay_box": "산호 자개함", "meteor_sword": "운철검",
    "fish_platter": "모둠 생선구이", "bogeo_jiri": "복어 지리", "pollack_stew": "동태찌개", "agujjim": "아귀찜", "golden_injeolmi": "황금 인절미",
    "ginseng_samgyetang": "산삼 삼계탕", "abalone_porridge": "전복죽", "japchae": "잡채", "sikhye": "식혜",
    "ginseng_liquor": "인삼주", "golden_cheongju": "황금 청주", "meteor_elixir": "운철 단약", "ambergris_perfume": "용연향 향수",
    "crystal_vintage": "수정병 빈티지",
}
