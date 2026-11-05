"""
Economy farming art: growth stages (crossed planes, like vanilla crops), produce icons and seed packets
for the 15 crops of contents/economy/crops/crops.yml.

Hand-drawn pixel art. Every pixel names a palette ramp and a step (docs/ART_STYLE.md: colours only from
ramps, light from the top-left). Plants have no outline (like vanilla crops); icons get the usual
outline from the darkest step of the part's own ramp.

A picture is a 16-wide character grid. Pictures are composed from hand-drawn sprites (leaves, fruit,
stakes) placed by hand, so one leaf drawing is reused instead of redrawn in every stage.

Legend shared by every drawing (a drawing may add its own letters):
  1-5  leaf (dark -> light)         a-e  sprout, yellow-green new growth
  v-y  leaf_dark (deep glossy)      h-l  straw (ripe grain, dry leaves)
  m-o  wood (stakes, vines)         W V  white petals    Y Z  yellow petals    U  purple petals
"""

from PIL import Image

from stsv_assets import icons as I
from stsv_assets.palette import ramp

BASE = {
    "1": ("leaf", 0), "2": ("leaf", 1), "3": ("leaf", 2), "4": ("leaf", 3), "5": ("leaf", 4),
    "a": ("sprout", 0), "b": ("sprout", 1), "c": ("sprout", 2), "d": ("sprout", 3), "e": ("sprout", 4),
    "v": ("leaf_dark", 0), "w": ("leaf_dark", 1), "x": ("leaf_dark", 2), "y": ("leaf_dark", 3),
    "h": ("straw", 0), "i": ("straw", 1), "j": ("straw", 2), "k": ("straw", 3), "l": ("straw", 4),
    "m": ("wood", 0), "n": ("wood", 1), "o": ("wood", 2),
    "W": ("snow", 1), "V": ("snow", 2), "Y": ("petal_yellow", 1), "Z": ("petal_yellow", 2), "U": ("petal_purple", 1),
}

# ------------------------------------------------------------------------------------------------
# Composing
# ------------------------------------------------------------------------------------------------


class Grid:
    """A character picture. Sprites are pasted with their top-left corner at (x, y)."""

    def __init__(self, h=16):
        self.h = h
        self.c = [["."] * 16 for _ in range(h)]

    def put(self, x, y, ch):
        if 0 <= x < 16 and 0 <= y < self.h and ch != ".":
            self.c[y][x] = ch
        return self

    def sprite(self, rows, x, y, recolor=None):
        for dy, row in enumerate(rows):
            for dx, ch in enumerate(row):
                if ch != ".":
                    self.put(x + dx, y + dy, (recolor or {}).get(ch, ch))
        return self

    def line(self, pts, ch):
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            n = max(abs(x1 - x0), abs(y1 - y0), 1)
            for k in range(n + 1):
                self.put(round(x0 + (x1 - x0) * k / n), round(y0 + (y1 - y0) * k / n), ch)
        return self

    def col(self, x, y0, y1, chars):
        """A vertical run from y0 to y1; chars = top, middle, bottom letters."""
        for y in range(y0, y1 + 1):
            self.put(x, y, chars[0] if y == y0 else chars[2] if y == y1 else chars[1])
        return self

    def rows(self):
        return ["".join(r) for r in self.c]


def image(rows, extra=None):
    legend = dict(BASE, **(extra or {}))
    im = Image.new("RGBA", (16, len(rows)), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == 16, f"row of {len(row)}: {row!r}"
        for x, ch in enumerate(row):
            if ch != ".":
                r, i = legend[ch]
                im.putpixel((x, y), ramp(r, i))
    return im


def icon(rows, extra=None):
    """A 16x16 item icon: the drawing plus the outline of the darkest step of each part's ramp."""
    legend = dict(BASE, **(extra or {}))
    ic = I.Icon()
    for y, row in enumerate(rows):
        assert len(row) == 16, f"row of {len(row)}: {row!r}"
        for x, ch in enumerate(row):
            if ch != ".":
                r, i = legend[ch]
                ic.put(x, y, r, i)
    return ic.outline().image()


def pad(rows, top=0):
    rows = ["." * 16] * top + list(rows)
    return [r.ljust(16, ".") for r in rows] + ["." * 16] * (16 - len(rows))


STAGES = {}   # crop -> [Image per stage]  (corn: tall stages are (bottom, top) pairs)
ICONS = {}    # item -> callable returning the 16x16 icon

# ------------------------------------------------------------------------------------------------
# Sprites shared by many plants
# ------------------------------------------------------------------------------------------------

# two seed leaves on a short stem: the first stage of every broad-leaved crop
COTYLEDON = [
    ".ed.ed.",
    "edc.dcb",
    "..cbc..",
    "...b...",
    "...a...",
]

# leaf clusters (light from the top-left; mostly mid and dark steps, like vanilla foliage)
LEAF = {
    "s": ["43", "21"],
    "m": [".43.", "4321", ".21."],
    "w": [".443.", "43321", ".221."],
    "r": ["443.", "3321", ".21."],
    "t": [".4.", "432", "21."],
    "top": [".54.", "5432", ".32."],
}
DARKER = {"5": "4", "4": "3", "3": "2", "2": "1", "1": "1"}
GLOSSY = {"5": "y", "4": "y", "3": "x", "2": "w", "1": "v"}     # leaf -> leaf_dark
YOUNG = {"5": "e", "4": "d", "3": "c", "2": "b", "1": "a"}      # leaf -> sprout
DRY = {"5": "l", "4": "k", "3": "j", "2": "i", "1": "h"}        # leaf -> straw

# thin blades: the first stage of onions, garlic, green onions, rice and corn
BLADES = [
    "d...e",
    "c..d.",
    ".c.d.",
    ".cc..",
    "..b..",
]

FLOWER_Y = [".Z.", "ZYZ", ".Z."]
FLOWER_W = [".V.", "VYV", ".V."]


def seedlings(sprite=COTYLEDON, xs=(1, 9), bottom=15, g=None):
    g = g or Grid()
    for k, x in enumerate(xs):
        g.sprite(sprite, x, bottom - len(sprite) + 1 + (k % 2))
    return g


def leaves(g, spots, recolor=None):
    for name, x, y in spots:
        g.sprite(LEAF[name], x, y, recolor)
    return g


# ================================================================================================
# 토마토: a staked bush, yellow flowers, green then red fruit
# ================================================================================================

TOMATO = {"R": ("tomato", 0), "S": ("tomato", 1), "T": ("tomato", 2), "Q": ("tomato", 3), "P": ("tomato", 4),
          "J": ("cabbage", 0), "K": ("cabbage", 1), "L": ("cabbage", 2), "M": ("cabbage", 3)}
TOMATO_RIPE = ["PQ.", "QTS", ".SR"]
TOMATO_GREEN = ["ML.", "LKJ", ".JJ"]


def _tomato_bush(fruit=None, flowers=False):
    g = Grid()
    g.col(8, 1, 15, "onm")                                   # the stake
    g.line([(8, 15), (7, 11), (8, 7), (7, 3)], "2")          # stem winding up it
    g.line([(7, 11), (3, 9)], "2").line([(8, 7), (12, 5)], "2").line([(7, 12), (12, 11)], "2").line([(8, 8), (3, 5)], "2")
    leaves(g, [("top", 5, 1), ("w", 9, 2), ("m", 1, 3), ("r", 12, 4), ("m", 5, 5), ("w", 0, 7), ("w", 9, 6),
               ("m", 4, 8), ("r", 12, 8), ("w", 1, 11), ("m", 9, 10), ("t", 5, 12), ("s", 12, 12)])
    if flowers:
        for x, y in ((2, 5), (12, 1), (13, 6)):
            g.sprite(FLOWER_Y, x, y)
    if fruit:
        for x, y in ((3, 6), (10, 9), (5, 11), (11, 4), (1, 10)):
            g.sprite(fruit, x, y)
    return g


def _young(g, x0, top, recolor=None):
    g.line([(x0 + 3, 15), (x0 + 3, top + 2)], (recolor or {}).get("2", "2"))
    leaves(g, [("t", x0 + 2, top), ("m", x0, top + 3), ("r", x0 + 3, top + 4)], recolor)
    return g


STAGES["tomato"] = [
    image(seedlings().rows()),
    image(_young(_young(Grid(), 1, 7), 9, 8).rows()),
    image(_tomato_bush(flowers=True).rows(), TOMATO),
    image(_tomato_bush(fruit=TOMATO_GREEN, flowers=True).rows(), TOMATO),
    image(_tomato_bush(fruit=TOMATO_RIPE).rows(), TOMATO),
]

# ================================================================================================
# 고추: an upright branching bush of slender leaves, white flowers, hanging pods (green, then red)
# ================================================================================================

CHILI = {"F": ("pepper", 4), "G": ("pepper", 3), "H": ("pepper", 2), "J": ("pepper", 1), "K": ("pepper", 0)}
POD_RED = ["2.", "GH", "GJ", ".H", ".K"]
POD_GREEN = ["2.", "dc", "db", ".c", ".a"]
LANCE_R = ["..44", ".432", "321."]       # slender leaf pointing up and right
LANCE_L = ["44..", "234.", ".123"]


def _chili_bush(pods=None, flowers=False):
    g = Grid()
    g.line([(7, 15), (7, 10), (4, 6), (3, 3)], "2").line([(7, 11), (10, 7), (11, 3)], "2")
    g.line([(7, 13), (3, 11)], "2").line([(7, 13), (12, 11)], "2").line([(5, 8), (8, 5)], "2")
    for spr, x, y in ((LANCE_L, 0, 2), (LANCE_R, 3, 1), (LANCE_L, 7, 1), (LANCE_R, 11, 1), (LANCE_L, 0, 6), (LANCE_R, 4, 4),
                      (LANCE_R, 10, 5), (LANCE_L, 7, 6), (LANCE_L, 0, 9), (LANCE_R, 11, 9), (LANCE_R, 5, 9), (LANCE_L, 8, 10),
                      (LANCE_R, 12, 6)):
        g.sprite(spr, x, y)
    if flowers:
        for x, y in ((2, 4), (11, 4), (6, 7)):
            g.sprite(FLOWER_W, x, y)
    if pods:
        for x, y in ((3, 6), (11, 7), (2, 11), (12, 11), (7, 9), (5, 3)):
            g.sprite(pods, x, y)
    return g


def _chili_young(g, x0, top):
    g.line([(x0 + 3, 15), (x0 + 3, top + 2)], "2")
    for spr, x, y in ((LANCE_L, x0, top), (LANCE_R, x0 + 3, top), (LANCE_L, x0, top + 3), (LANCE_R, x0 + 4, top + 3)):
        g.sprite(spr, x, y)
    return g


STAGES["chili"] = [
    image(seedlings().rows()),
    image(_chili_young(_chili_young(Grid(), 0, 8), 8, 9).rows()),
    image(_chili_bush(flowers=True).rows()),
    image(_chili_bush(pods=POD_GREEN, flowers=True).rows()),
    image(_chili_bush(pods=POD_RED).rows(), CHILI),
]

# ================================================================================================
# 딸기: low rosettes, white flowers, red berries near the ground
# ================================================================================================

STRAW = {"P": ("toadstool", 4), "Q": ("toadstool", 3), "S": ("toadstool", 2), "R": ("toadstool", 1), "T": ("toadstool", 0)}
TRIO = [".4.4.", "43234", ".212.", "..1.."]
BERRY = [".3.", "QPS", "SZR", ".R."]
BERRY_WHITE = [".3.", "VVW", "WVW", ".W."]


def _strawberry(flowers=False, berries=None, big=True):
    g = Grid()
    spots = [(0, 11), (4, 10), (9, 10), (12, 11), (2, 12), (7, 12)] if big else [(1, 12), (5, 11), (10, 12)]
    for x, y in spots:
        g.sprite(TRIO, x, y)
    if flowers:
        for x, y in ((2, 8), (8, 8), (13, 9)):
            g.sprite(FLOWER_W, x, y)
    if berries:
        for x, y in ((1, 12), (6, 11), (11, 12), (13, 12)):
            g.sprite(berries, x, y)
    return g


STAGES["strawberry"] = [
    image(seedlings(TRIO, xs=(2, 9)).rows()),
    image(_strawberry(big=False).rows()),
    image(_strawberry(flowers=True).rows()),
    image(_strawberry(berries=BERRY).rows(), STRAW),
]

# ================================================================================================
# 양파: hollow leaves, a bulb swelling at the ground, leaves falling over when ripe
# ================================================================================================

ONION = {"A": ("onion", 3), "B": ("onion", 2), "C": ("onion", 1), "D": ("onion", 0), "E": ("garlic", 3), "F": ("garlic", 2)}


def _tubes(g, x, top, chars="432", lean=0):
    """A clump of three hollow leaves from (x, 15) up to top."""
    g.line([(x, 15), (x - 2 + lean, top + 2), (x - 3 + lean, top)], chars[1])
    g.line([(x + 1, 15), (x + 1 + lean, top - 1)], chars[0])
    g.line([(x + 2, 15), (x + 4 + lean, top + 3), (x + 5 + lean, top + 2)], chars[2])
    return g


STAGES["onion"] = [
    image(seedlings(BLADES, xs=(2, 10)).rows()),
    image(_tubes(_tubes(Grid(), 3, 9), 10, 10).rows()),
    image(_tubes(_tubes(Grid(), 3, 4), 10, 5).sprite(["ECE", "FDF"], 3, 14).sprite(["ECE", "FDF"], 10, 14).rows(), ONION),
    image(Grid()
          .line([(4, 13), (1, 9), (0, 8)], "i").line([(5, 13), (8, 10), (10, 10)], "j").line([(4, 13), (5, 8), (4, 6)], "k")
          .line([(11, 13), (14, 10), (15, 10)], "i").line([(11, 13), (11, 9), (12, 7)], "j")
          .sprite([".AB.", "ABBC", "BBCD", ".CD."], 3, 12).sprite([".AB.", "ABBC", "BBCD", ".CD."], 10, 12).rows(), ONION),
]

# ================================================================================================
# 마늘: flat leaves in a fan, a curled scape, white bulbs when the leaves dry
# ================================================================================================

GARLIC = {"A": ("garlic", 3), "B": ("garlic", 2), "C": ("garlic", 1), "D": ("garlic", 0), "U": ("petal_purple", 0)}


def _fan(g, x, top, recolor=None):
    c = recolor or {}
    g.line([(x, 15), (x - 3, top + 3), (x - 4, top + 2)], c.get("3", "3"))
    g.line([(x, 15), (x - 1, top)], c.get("4", "4"))
    g.line([(x + 1, 15), (x + 2, top + 1)], c.get("2", "2"))
    g.line([(x + 1, 15), (x + 4, top + 4), (x + 5, top + 4)], c.get("3", "3"))
    return g


STAGES["garlic"] = [
    image(seedlings(BLADES, xs=(3, 10)).rows()),
    image(_fan(_fan(Grid(), 4, 9), 11, 10).rows()),
    image(_fan(_fan(Grid(), 4, 4), 11, 5).line([(5, 15), (6, 3), (8, 1), (9, 2)], "2").sprite(["W"], 9, 2).rows()),
    image(_fan(_fan(Grid(), 4, 7, DRY), 11, 8, DRY).sprite([".AB.", "ABBC", "BUBD", ".CD."], 3, 12)
          .sprite([".AB.", "ABBC", "BUBD", ".CD."], 10, 12).rows(), GARLIC),
]

# ================================================================================================
# 배추: broad leaves with pale ribs, closing into a tall head
# ================================================================================================

CABBAGE = {"A": ("cabbage", 3), "B": ("cabbage", 2), "C": ("cabbage", 1), "D": ("cabbage", 0), "V": ("garlic", 3), "W": ("garlic", 2)}
UPRIGHT = [".44.", "4V43", "4V32", "3W32", "3W21", ".W1."]    # an upright leaf, white midrib
LOW = [".443.", "44V32", "3W321", ".W1.."]                    # a leaf lying out


def _cabbage_leaves(g, big):
    if big:
        for spr, x, y in ((LOW, 0, 11), (LOW, 11, 11), (UPRIGHT, 1, 8), (UPRIGHT, 11, 8)):
            g.sprite(spr, x, y)
    else:
        for spr, x, y in ((LOW, 0, 12), (UPRIGHT, 4, 10), (UPRIGHT, 8, 10), (LOW, 11, 12)):
            g.sprite(spr, x, y)
    return g


STAGES["cabbage"] = [
    image(seedlings().rows()),
    image(_cabbage_leaves(Grid(), False).rows(), CABBAGE),
    image(_cabbage_leaves(Grid(), True).sprite(UPRIGHT, 4, 7).sprite(UPRIGHT, 8, 7).sprite(UPRIGHT, 6, 9).rows(), CABBAGE),
    image(_cabbage_leaves(Grid(), True).sprite([
        "..AAB...",
        ".AABBC..",
        "4AABBCC2",
        "44ABBC32",
        "44ABBC32",
        "43ABCC32",
        "43ABC322",
        "433BC321",
        "4433C321",
        ".433C21.",
        "..4321..",
    ], 4, 4).rows(), CABBAGE),
]

# ================================================================================================
# 벼: tufts of blades, then drooping panicles, green then golden
# ================================================================================================


def _tuft(g, x, top, c="dcb", lean=1):
    """A clump of thin blades from (x, 15): the middle ones upright, the outer ones arching out."""
    g.line([(x, 15), (x - 2, top + 3), (x - 4, top + 4)], c[2])
    g.line([(x, 15), (x - 1, top + 1)], c[1])
    g.line([(x + 1, 15), (x + 1, top)], c[0])
    g.line([(x + 1, 15), (x + 3, top + 2), (x + 4 + lean, top + 3)], c[1])
    return g


def _panicle(g, x, top, grain):
    """A head of grain on its stem, arching over to the right and hanging down."""
    g.line([(x, top + 6), (x, top + 1), (x + 1, top)], grain[2])
    for dx, dy, k in ((1, 0, 0), (2, 0, 1), (3, 1, 0), (4, 2, 1), (4, 3, 0), (5, 4, 1), (3, 3, 1), (2, 1, 0)):
        g.put(x + dx, top + dy, grain[k])
    return g




def _rice(top_shift, blades, heads=None):
    g = Grid()
    for x, top in ((3, 4), (8, 3), (12, 5)):
        _tuft(g, x, top + top_shift, blades)
    if heads:
        for x, top, order in ((1, 1, 0), (7, 0, 1), (11, 2, 0)):
            _panicle(g, x, top + top_shift, heads if order == 0 else heads[1] + heads[0] + heads[2])
    return g


STAGES["rice"] = [
    image(seedlings(BLADES, xs=(2, 10)).rows()),
    image(_tuft(_tuft(_tuft(Grid(), 3, 9, "432"), 8, 8, "432"), 12, 10, "432").rows()),
    image(_rice(0, "432").rows()),
    image(_rice(0, "432", "ed4").rows()),
    image(_rice(1, "dcb", "lkj").rows()),
]

# ================================================================================================
# 콩: trifoliate leaves, little purple flowers, fuzzy pods as the leaves turn yellow
# ================================================================================================

SOY = {"A": ("bean", 3), "B": ("bean", 2), "C": ("bean", 1), "D": ("bean", 0)}
POD_DRY = ["AB", "BC", "BC", ".D"]


def _soy(pods=False, flowers=False, recolor=None):
    g = Grid()
    g.line([(5, 15), (3, 9)], "1").line([(6, 15), (9, 8)], "1").line([(11, 15), (12, 10)], "1")
    for x, y in ((0, 7), (4, 6), (8, 6), (11, 8), (1, 10), (6, 9), (10, 11), (3, 12)):
        g.sprite(TRIO, x, y, recolor)
    if flowers:
        for x, y in ((4, 9), (10, 11), (8, 10)):
            g.put(x, y, "U")
    if pods:
        for x, y in ((3, 10), (8, 11), (13, 11), (6, 12)):
            g.sprite(POD_DRY, x, y)
    return g


STAGES["soybean"] = [
    image(seedlings().rows()),
    image(Grid().sprite(TRIO, 0, 11).sprite(TRIO, 4, 10).sprite(TRIO, 9, 11).sprite(TRIO, 12, 12).rows()),
    image(_soy(flowers=True).rows()),
    image(_soy(pods=True, recolor={"4": "k", "3": "j", "2": "c", "1": "b"}).rows(), SOY),
]

# ================================================================================================
# 옥수수: a tall stalk (two blocks when grown), tassel on top, cobs in husks with silk
# ================================================================================================

CORN = {"A": ("corn", 3), "B": ("corn", 2), "C": ("corn", 1), "D": ("corn", 0), "E": ("soil", 2), "F": ("soil", 3)}


def _stalk(g, x, top, bottom=15, c="432"):
    g.col(x, top, bottom, c[0] + c[1] + c[1])
    g.col(x + 1, top + 1, bottom, c[2] + c[2] + c[2])
    return g


def _blade(g, x, y, right=True, length=6, light="4", dark="2"):
    """A long corn leaf, two pixels wide: rising from the stalk, arching out and drooping at the tip."""
    d = 1 if right else -1
    for k in range(length):
        rise = 0 if k == 0 else 1 if k < 3 else 2 if k < length - 1 else 1
        g.put(x + d * k, y - rise, light)
        if k < length - 1:
            g.put(x + d * k, y - rise + 1, dark)
    return g


STAGES["corn"] = [
    image(seedlings(BLADES, xs=(2, 10)).rows()),
    image(_blade(_blade(_blade(_stalk(Grid(), 7, 10), 7, 13, False, 5), 8, 11, True, 6), 7, 10, False, 4, "4", "3").rows()),
    image(_blade(_blade(_blade(_blade(_blade(_stalk(Grid(), 7, 2), 7, 13, False, 7), 8, 10, True, 7), 7, 7, False, 6), 8, 4, True, 6),
                 7, 3, False, 4, "4", "3").line([(7, 2), (7, 0)], "4").rows()),
]


def _corn_tall(ripe):
    """(bottom block, top block) of a full-grown plant: 32 rows, drawn as one."""
    g = Grid(32)
    _stalk(g, 7, 6, 31, "432")
    low_l, low_d = ("k", "i") if ripe else ("4", "2")      # the lowest leaves dry first
    for x, y, right, n, li, da in ((7, 29, False, 7, low_l, low_d), (8, 26, True, 7, low_l, low_d), (7, 22, False, 7, "4", "2"),
                                   (8, 18, True, 7, "4", "2"), (7, 14, False, 6, "4", "2"), (8, 11, True, 5, "4", "3"),
                                   (7, 8, False, 4, "4", "3")):
        _blade(g, x, y, right, n, li, da)
    # tassel
    t1, t2, t3 = ("k", "j", "i") if ripe else ("d", "c", "b")
    g.line([(7, 6), (7, 0)], t1).line([(7, 3), (5, 1), (4, 2)], t2).line([(8, 3), (10, 1), (11, 2)], t3).put(6, 4, t3).put(9, 5, t2)
    # cobs in husks with silk on top; ripe ones open a little at the tip
    cob = ["E.", "FE", "kA", "kB", "jA", "jB", "ji", ".i"] if ripe else ["c.", "dc", "dc", "dc", "cb", "cb", "cb", ".b"]
    g.sprite(cob, 9, 15).sprite(cob, 5, 20)
    rows = g.rows()
    return image(rows[16:], CORN), image(rows[:16], CORN)


STAGES["corn"] += [_corn_tall(False), _corn_tall(True)]

# ================================================================================================
# 파: dense clumps of dark hollow leaves on white stems
# ================================================================================================


def _scallion(g, x, top):
    for dx, t, ch in ((0, top + 2, "x"), (1, top, "y"), (2, top + 1, "x"), (3, top + 3, "w")):
        g.line([(x + dx, 15 - 3), (x + dx + (dx - 1.5) * 0.6, t)], ch)
        g.col(x + dx, 14, 15, "VW" + "W")
    return g


STAGES["green_onion"] = [
    image(seedlings(BLADES, xs=(2, 10)).rows()),
    image(Grid().line([(3, 15), (3, 10)], "x").line([(4, 15), (5, 9)], "y").line([(11, 15), (11, 9)], "x").line([(12, 15), (13, 10)], "w").rows()),
    image(_scallion(_scallion(Grid(), 2, 6), 10, 7).rows()),
    image(_scallion(_scallion(_scallion(Grid(), 1, 3), 6, 2), 11, 4).rows()),
]

# ================================================================================================
# 무: a rosette of rough leaves; the white root with a green shoulder pushes out of the ground
# ================================================================================================

RADISH = {"A": ("garlic", 3), "B": ("garlic", 2), "C": ("garlic", 1), "D": ("garlic", 0)}
ROUGH = [".44.", "4432", "4321", ".21."]



def _rosette(g, spots, crown=(7, 15)):
    for x, y in spots:
        g.line([crown, (x + 1, y + 3)], "2")
    for x, y in spots:
        g.sprite(ROUGH, x, y)
    return g


STAGES["radish"] = [
    image(seedlings().rows()),
    image(_rosette(Grid(), [(1, 11), (5, 10), (9, 10), (12, 11)]).rows()),
    image(_rosette(Grid(), [(0, 8), (3, 6), (8, 6), (12, 8), (2, 10), (10, 10)]).rows()),
    image(_rosette(Grid(), [(0, 5), (3, 3), (8, 3), (12, 5), (1, 8), (11, 8)], crown=(7, 11))
          .sprite([".cdc.", "cddcb", "ABBBC", "ABBCD", "ABBCD"], 5, 11).rows(), RADISH),
]

# ================================================================================================
# 상추: a low rosette of ruffled light leaves
# ================================================================================================

RUFFLE = [".e.d.", "edddc", "dcccb", ".bcb."]

STAGES["lettuce"] = [
    image(seedlings().rows()),
    image(Grid().sprite(RUFFLE, 1, 12).sprite(RUFFLE, 9, 12).rows()),
    image(Grid().sprite(RUFFLE, 0, 11).sprite(RUFFLE, 5, 10).sprite(RUFFLE, 10, 11).sprite(RUFFLE, 3, 12).sprite(RUFFLE, 8, 12).rows()),
    image(Grid().sprite(RUFFLE, 0, 10).sprite(RUFFLE, 4, 8).sprite(RUFFLE, 8, 8).sprite(RUFFLE, 11, 10).sprite(RUFFLE, 2, 11)
          .sprite(RUFFLE, 6, 11).sprite(RUFFLE, 10, 12).sprite(["43.", "321"], 6, 10, None).rows()),
]

# ================================================================================================
# 오이: a vine climbing a stake, yellow flowers, long bumpy fruit hanging down
# ================================================================================================

CUKE = {"A": ("cucumber", 3), "B": ("cucumber", 2), "C": ("cucumber", 1), "D": ("cucumber", 0), "E": ("sprout", 3)}
CUCUMBER = ["3.", "EB", "AC", "EB", "AC", "BD", ".D"]     # dark skin, pale bumps
CUKE_SMALL = ["Y.", "EB", "AC", ".C"]
BIG = [".443.", "44332", "43321", ".321."]     # big rough vine leaf


def _vine(fruit=None, flowers=False):
    g = Grid()
    g.col(7, 0, 15, "onm").col(8, 1, 15, "nnm")                       # the stake
    g.line([(7, 15), (6, 12), (9, 9), (6, 6), (9, 3), (8, 1)], "2")   # the vine winding up
    for x, y in ((0, 6), (10, 4), (2, 2), (10, 0), (1, 9), (11, 8), (4, 5)):
        g.sprite(BIG, x, y)
    g.put(5, 10, "c").put(4, 11, "c").put(12, 12, "c")                # tendrils
    if flowers:
        for x, y in ((4, 2), (12, 6), (2, 8)):
            g.sprite(FLOWER_Y, x, y)
    if fruit:
        for x, y in ((2, 10), (12, 9), (10, 11)):
            g.sprite(fruit, x, y)
    return g


STAGES["cucumber"] = [
    image(seedlings().rows()),
    image(Grid().sprite(BIG, 0, 10).sprite(BIG, 9, 9).line([(4, 15), (5, 12), (9, 11)], "2").sprite(BIG, 4, 11).rows()),
    image(_vine().rows()),
    image(_vine(fruit=CUKE_SMALL, flowers=True).rows(), CUKE),
    image(_vine(fruit=CUCUMBER).rows(), CUKE),
]

# ================================================================================================
# 포도: a woody vine on a post with a crossbar, lobed leaves, bunches (green, then purple)
# ================================================================================================

GRAPE = {"A": ("grape", 4), "B": ("grape", 3), "C": ("grape", 2), "D": ("grape", 1), "E": ("grape", 0),
         "J": ("cabbage", 3), "K": ("cabbage", 2), "L": ("cabbage", 1), "M": ("cabbage", 0)}
BUNCH_PURPLE = ["ABAB", "BCBC", ".CD.", ".DE.", "..E."]
BUNCH_GREEN = ["JKJK", "KLKL", ".LM.", ".KM.", "..M."]
LOBED = [".4.4.", "44432", "43332", ".321."]


def _trellis(bunches=None):
    g = Grid()
    g.col(7, 1, 15, "onm").col(1, 1, 1, "o").line([(0, 1), (15, 1)], "n")          # post and crossbar
    g.line([(7, 15), (7, 4), (4, 2), (1, 2)], "m").line([(7, 4), (11, 2), (15, 2)], "m")   # the vine along the bar
    for x, y in ((0, 2), (4, 3), (9, 2), (12, 4), (2, 6), (10, 7), (5, 8)):
        g.sprite(LOBED, x, y)
    if bunches:
        for x, y in ((2, 5), (10, 5), (6, 7)):
            g.sprite(bunches, x, y)
    return g


STAGES["grape"] = [
    image(Grid().col(5, 10, 15, "onm").col(11, 11, 15, "onm").sprite(["4.", "32"], 6, 10).sprite([".4", "32"], 9, 11).rows()),
    image(Grid().line([(5, 15), (5, 8), (3, 6)], "m").line([(5, 10), (8, 8)], "m").sprite(LOBED, 1, 5).sprite(LOBED, 6, 6)
          .line([(11, 15), (11, 9)], "m").sprite(LOBED, 9, 8).rows()),
    image(_trellis().rows()),
    image(_trellis(BUNCH_GREEN).rows(), GRAPE),
    image(_trellis(BUNCH_PURPLE).rows(), GRAPE),
]

# ================================================================================================
# 차나무: a small bush of glossy dark leaves; light new shoots on top to pick
# ================================================================================================

SHOOT = [".e", "ed", ".c"]


def _tea(size, shoots=False):
    g = Grid()
    top = 16 - size
    g.line([(7, 15), (7, 13)], "m").line([(8, 15), (8, 13)], "n")
    spots = [("m", 5, top), ("w", 1, top + 2), ("w", 9, top + 2), ("m", 4, top + 3), ("m", 11, top + 5), ("w", 0, top + 5),
             ("w", 6, top + 5), ("m", 3, top + 7), ("m", 9, top + 7)]
    for name, x, y in spots:
        if y + 2 <= 14:
            g.sprite(LEAF[name], x, y, GLOSSY)
    if shoots:
        for x, y in ((2, top), (6, top - 2), (10, top), (13, top + 3)):
            g.sprite(SHOOT, x, y)
    return g


STAGES["tea"] = [
    image(seedlings([".xy.", "wxyx", ".mw.", ".m.."], xs=(2, 10)).rows()),
    image(_tea(7).rows()),
    image(_tea(11).rows()),
    image(_tea(11, shoots=True).rows()),
]

# ================================================================================================
# Icons
# ================================================================================================

from stsv_assets.palette import size as _size   # noqa: E402


def _dist(cells, x, y, dx, dy):
    n = 0
    while (x + dx * (n + 1), y + dy * (n + 1)) in cells:
        n += 1
    return n


def shade(rows, body, extra=None, lo=1, hi=None):
    """An icon whose '#' cells are one rounded body in ramp `body`, lit from the top-left (the step of each
    cell comes from how far it is from the edges on each side); other letters are hand-placed details."""
    legend = dict(BASE, **(extra or {}))
    n = _size(body)
    hi = n - 1 if hi is None else hi
    cells = {(x, y) for y, r in enumerate(rows) for x, ch in enumerate(r) if ch == "#"}
    ic = I.Icon()
    for y, row in enumerate(rows):
        assert len(row) == 16, f"row of {len(row)}: {row!r}"
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            if ch != "#":
                r, i = legend[ch]
                ic.put(x, y, r, i)
                continue
            le, ri = _dist(cells, x, y, -1, 0), _dist(cells, x, y, 1, 0)
            up, dn = _dist(cells, x, y, 0, -1), _dist(cells, x, y, 0, 1)
            nx = (le - ri) / max(1, le + ri)
            ny = (up - dn) / max(1, up + dn)
            lit = (-nx - ny) / 2                    # +1 facing the top-left light, -1 facing away
            mid = (lo + hi) / 2
            i = round(mid + lit * (hi - lo) / 2 * 1.15)
            ic.put(x, y, body, max(lo, min(hi, i)))
    return ic.outline().image()


def _x(rows):
    return [r.ljust(16, ".")[:16] for r in rows] + ["." * 16] * (16 - len(rows))


ICONS["tomato"] = lambda: shade(_x([
    "",
    ".......2",
    "......343",
    "....3443443",
    "...##4###4##",
    "..############",
    "..##P#########",
    ".##PP##########",
    ".##P###########",
    ".##############",
    ".##############",
    "..############",
    "..############",
    "...##########",
    ".....######",
]), "tomato", {"P": ("tomato", 4)}, lo=0)

ICONS["chili_pepper"] = lambda: shade(_x([
    "",
    "..22",
    "...2",
    "..434",
    "..####",
    "..#F###",
    "...#F###",
    "....#####",
    ".....#####",
    "......#####",
    ".......#####",
    "........#####",
    ".........####",
    "...........###",
    ".............#",
]), "pepper", {"F": ("pepper", 4)}, lo=0)

ICONS["strawberry"] = lambda: shade(_x([
    "",
    "......4.4",
    "....434343",
    "...4#33#34",
    "..##########",
    "..#Z##Z##Z##",
    "..##########",
    "..#P#Z##Z#Z#",
    "...########",
    "...#Z##Z#Z#",
    "....######",
    "....#Z##Z#",
    ".....####",
    "......#Z",
    "......#",
]), "toadstool", {"P": ("toadstool", 4)}, lo=0)

ICONS["onion"] = lambda: shade(_x([
    "",
    ".......k",
    ".......j",
    "......###",
    ".....#####",
    "....##L####",
    "...##L######",
    "..##L##D##D##",
    "..##L##D##D##",
    "..###########",
    "..#####D##D##",
    "...########",
    "....#######",
    "......###",
    ".....W.W.W",
]), "onion", {"L": ("onion", 3), "D": ("onion", 1), "W": ("garlic", 1)})

ICONS["garlic"] = lambda: shade(_x([
    "",
    ".......j",
    ".......i",
    "......###",
    ".....#####",
    "....##V####",
    "...##V#####D",
    "..##V##D##D##",
    "..##V##D##D##",
    "..#####D##D##",
    "..##U##D##U##",
    "...##U###U##",
    "....#######",
    ".....W.W.W",
]), "garlic", {"V": ("garlic", 3), "D": ("garlic", 1), "U": ("petal_purple", 0), "W": ("garlic", 1)})

ICONS["napa_cabbage"] = lambda: shade(_x([
    "",
    ".....5445",
    "....544433",
    "...44####32",
    "...4######3",
    "..44######32",
    "..4###V###32",
    "..4###V###32",
    "..4###V###32",
    "..44##V###2",
    "...4##V##32",
    "...44#V#322",
    "....4WVW32",
    ".....WWW",
]), "cabbage", {"V": ("garlic", 3), "W": ("garlic", 2)})

ICONS["paddy"] = lambda: icon(_x([
    "",
    "..........lk",
    "........lkjkl",
    "......lk.kl.k",
    ".....kj.jkl..l",
    "....kj.jk..k.k",
    "...jj.ij...l",
    "..ji.ij.....k",
    "..i.ii",
    ".ii.i",
    ".hiihh",
    ".mnmn",
    "ihi",
    "ih",
    "h",
]), {})

ICONS["soybean"] = lambda: shade(_x([
    "",
    "",
    "........cd",
    ".......cdcb",
    "......cdcbb.",
    ".....cdcbb",
    "....###.cbb",
    "...#####.b",
    "...#####",
    "...#####.###",
    "....###.#####",
    "..###...#####",
    ".#####..#####",
    ".#####...###",
    ".#####",
    "..###",
]), "bean")

ICONS["corn"] = lambda: icon(_x([
    "",
    "...........ih",
    "..........ii",
    "........ABAB",
    ".......ABCBC",
    "......ABCBCD",
    ".....ABCBCD",
    "....ABCBCD",
    "...cABCBCD",
    "..cdcBCD",
    "..cdcdD",
    ".cdccb",
    ".cdcb",
    "..cb.b",
    "..b..",
]), {"A": ("corn", 3), "B": ("corn", 2), "C": ("corn", 1), "D": ("corn", 0)})

ICONS["green_onion"] = lambda: icon(_x([
    ".............y",
    "............yx",
    "..........y.xw",
    ".........yxyw",
    "........yxyw.",
    ".......yxyw",
    "......yxyw",
    ".....yxw",
    "....VVW",
    "...VVW",
    "..VVW",
    ".VVW",
    "VWW",
    "WW.W",
    ".W",
]), {"V": ("garlic", 3), "W": ("garlic", 2)})

ICONS["radish"] = lambda: shade(_x([
    "...........4.4",
    "..........43434",
    ".........34343",
    "........cdc32",
    ".......#cdc#",
    "......#####",
    ".....######",
    "....######",
    "...######",
    "..######",
    "..#####",
    "..####",
    "..###",
    "..##",
    ".#",
]), "garlic", lo=0)

ICONS["lettuce"] = lambda: icon(_x([
    "",
    "....e.e.e",
    "...edededde",
    "..eddcdcddcd",
    ".eddcVcdcccdc",
    ".dccdVccccccd",
    ".dcccVcccbccd",
    "..dccVcccccb",
    "..dbccVccbcb",
    "...bcbVcbcb",
    "....bbcVbb",
    "......bbVb",
    "........Vb",
    ".........V",
]), {"V": ("cabbage", 3)})

ICONS["cucumber"] = lambda: icon(_x([
    "",
    "............YZ",
    "...........AAY",
    "..........ABB",
    ".........AECC",
    "........ABCB",
    ".......AECCD",
    "......ABCBD",
    ".....AECCD",
    "....ABCBD",
    "...AECCD",
    "..ABCBD",
    "..BCCD",
    "..2CD",
    ".2",
]), {"A": ("cucumber", 3), "B": ("cucumber", 2), "C": ("cucumber", 1), "D": ("cucumber", 0), "E": ("sprout", 3)})

ICONS["grapes"] = lambda: icon(_x([
    "........m",
    ".......m.443",
    "......m.44321",
    "...ABBAB.321",
    "..ABCDABCD",
    "..BCDDBCDD",
    "...ABBABBA",
    "..ABCDACDBC",
    "..BCDDBCDCD",
    "...ABBABBA",
    "....ACDBCD",
    "....BDDCDD",
    ".....ABBA",
    ".....BCDD",
    "......CD",
]), {"A": ("grape", 4), "B": ("grape", 3), "C": ("grape", 2), "D": ("grape", 1)})

ICONS["tea_leaves"] = lambda: icon(_x([
    "",
    "..........ed",
    ".........edc",
    "........edcb",
    "....yy..dcb",
    "...yyxw.cb",
    "..yxxxw.b",
    "..yxxww.b",
    "...xwwv.b..yy",
    "....wv..byyxxw",
    "........mxxxww",
    ".......m.wwvv",
    "......m",
    ".....m",
    "....m",
]), {})

# ------------------------------------------------------------------------------------------------
# Seed packets: a paper packet showing the crop, a band in the crop's colour
# ------------------------------------------------------------------------------------------------

MINI = {   # 6 x 6 pictures for the packet window (legend: BASE + the crop's letters)
    "tomato": ([".343..", "TQTTS.", "QPTTSR", "TTTTSR", "TTTSSR", ".SSRR."], {"T": ("tomato", 2), "Q": ("tomato", 3), "P": ("tomato", 4), "S": ("tomato", 1), "R": ("tomato", 0)}),
    "chili": (["3.....", "43....", ".GH...", "..GH..", "...GJ.", "....JK"], {"G": ("pepper", 3), "H": ("pepper", 2), "J": ("pepper", 1), "K": ("pepper", 0)}),
    "strawberry": ([".4343.", "QPQSS.", "QZQSZ.", ".QSZR.", ".SSR..", "..R..."], {"P": ("toadstool", 4), "Q": ("toadstool", 3), "S": ("toadstool", 2), "R": ("toadstool", 1), "Z": ("petal_yellow", 2)}),
    "onion": (["..j...", ".ABB..", "ABBCC.", "ABBCD.", ".BCD..", "..W..."], {"A": ("onion", 3), "B": ("onion", 2), "C": ("onion", 1), "D": ("onion", 0), "W": ("garlic", 1)}),
    "cabbage": ([".454..", "4AAB3.", "4ABB3.", "4AVB3.", ".4V32.", "..W..."], {"A": ("cabbage", 3), "B": ("cabbage", 2), "V": ("garlic", 3), "W": ("garlic", 2)}),
    "rice": (["...lk.", "..lk.k", ".kj.l.", ".ij...", "ji....", "h....."], {}),
    "corn": (["...AB.", "..ABC.", ".ABCD.", ".cBD..", "cdc...", "cb...."], {"A": ("corn", 3), "B": ("corn", 2), "C": ("corn", 1), "D": ("corn", 0)}),
    "green_onion": (["....yx", "...yxw", "..yxw.", ".VW...", "VW....", "W....."], {"V": ("garlic", 3), "W": ("garlic", 2)}),
    "radish": (["...434", "..cdc3", ".VVW..", ".VWD..", "VWD...", "W....."], {"V": ("garlic", 3), "W": ("garlic", 2), "D": ("garlic", 1)}),
    "lettuce": ([".e.e..", "edcdd.", "dcVcd.", "dcVcb.", ".bVb..", "..V..."], {"V": ("cabbage", 3)}),
    "cucumber": (["....AY", "...ABC", "..AEC.", ".AEC..", "ABC...", "BC...."], {"A": ("cucumber", 3), "B": ("cucumber", 2), "C": ("cucumber", 1), "E": ("sprout", 3)}),
    "grape": ([".m.43.", "ABAB2.", "BCBC..", ".ACD..", ".BD...", "..D..."], {"A": ("grape", 4), "B": ("grape", 3), "C": ("grape", 2), "D": ("grape", 1)}),
    "tea": (["...ed.", ".yy.c.", "yxxwb.", ".xwv..", "..m...", ".m...."], {}),
}
BAND = {"tomato": ("tomato", 2), "chili": ("pepper", 2), "strawberry": ("toadstool", 2), "onion": ("onion", 2), "cabbage": ("leaf", 3),
        "rice": ("straw", 2), "corn": ("corn", 2), "green_onion": ("leaf_dark", 2), "radish": ("leaf", 3), "lettuce": ("sprout", 3),
        "cucumber": ("cucumber", 2), "grape": ("grape", 2), "tea": ("leaf_dark", 2)}


def packet(crop):
    pic, letters = MINI[crop]
    legend = dict(BASE, **letters)
    ic = I.Icon()
    for y in range(1, 15):
        for x in range(3, 13):
            if y == 1:
                ic.put(x, y, "paper", 0 if x % 2 else 1)        # crimped top
            elif x == 3:
                ic.put(x, y, "paper", 2)                         # lit left edge
            elif x == 12 or y == 14:
                ic.put(x, y, "paper", 0)
            else:
                ic.put(x, y, "paper", 1)
    for y in range(3, 10):                                      # the picture's frame
        for x in range(4, 12):
            if y in (3, 9) or x in (4, 11):
                ic.put(x, y, "paper", 2)
    for dy, row in enumerate(pic):
        for dx, ch in enumerate(row):
            if ch != ".":
                r, i = legend[ch]
                ic.put(5 + dx, 3 + dy, r, i)
    band, step = BAND[crop]
    for x in range(4, 12):
        ic.put(x, 11, band, step + 1)
        ic.put(x, 12, band, step)
    return ic.outline().image()


SEEDS = {"tomato_seeds": "tomato", "chili_seeds": "chili", "strawberry_seeds": "strawberry", "onion_seeds": "onion",
         "cabbage_seeds": "cabbage", "rice_seeds": "rice", "corn_seeds": "corn", "green_onion_seeds": "green_onion",
         "radish_seeds": "radish", "lettuce_seeds": "lettuce", "cucumber_seeds": "cucumber", "tea_seeds": "tea"}
for _item, _crop in SEEDS.items():
    ICONS[_item] = (lambda c=_crop: packet(c))

ICONS["grape_sapling"] = lambda: icon(_x([
    "",
    ".........443",
    "........44332",
    ".......4433321",
    "........33221",
    "...443...m21",
    "..44332.m",
    "..43321m",
    "...321m",
    ".....m",
    "....nm",
    "...nm",
    "..nm",
    ".nm",
    ".m",
]), {})


# ------------------------------------------------------------------------------------------------
# Preview sheet (docs/previews/crops.png): each crop's harvest, seed and growth stages on farmland
# ------------------------------------------------------------------------------------------------

NAMES = {"tomato": ("토마토", "tomato", "tomato_seeds"), "chili": ("고추", "chili_pepper", "chili_seeds"),
         "strawberry": ("딸기", "strawberry", "strawberry_seeds"), "onion": ("양파", "onion", "onion_seeds"),
         "garlic": ("마늘", "garlic", "garlic"), "cabbage": ("배추", "napa_cabbage", "cabbage_seeds"),
         "rice": ("벼", "paddy", "rice_seeds"), "soybean": ("콩", "soybean", "soybean"), "corn": ("옥수수", "corn", "corn_seeds"),
         "green_onion": ("파", "green_onion", "green_onion_seeds"), "radish": ("무", "radish", "radish_seeds"),
         "lettuce": ("상추", "lettuce", "lettuce_seeds"), "cucumber": ("오이", "cucumber", "cucumber_seeds"),
         "grape": ("포도", "grapes", "grape_sapling"), "tea": ("차나무", "tea_leaves", "tea_seeds")}


def sheet(path, scale=5):
    from PIL import ImageDraw
    s = scale
    cell = 16 * s
    gap = 10
    left = 2 * cell + 3 * gap
    width = left + 5 * (cell + gap) + gap
    row_h = 2 * cell + 2 * gap
    im = Image.new("RGBA", (width, len(STAGES) * row_h), (34, 38, 46, 255))
    d = ImageDraw.Draw(im)
    farmland = (110, 72, 44, 255)
    for r, (crop, stages) in enumerate(STAGES.items()):
        y0 = r * row_h
        d.rectangle([0, y0 + 1, width, y0 + row_h - 2], fill=(150, 192, 232, 255))
        _, produce, seed = NAMES[crop]
        for k, item in enumerate((produce, seed)):
            x = gap + k * (cell + gap)
            d.rectangle([x - 2, y0 + cell // 2 - 2, x + cell + 1, y0 + cell // 2 + cell + 1], fill=(198, 198, 198, 255))
            im.alpha_composite(ICONS[item]().resize((cell, cell), Image.NEAREST), (x, y0 + cell // 2))
        for k, st in enumerate(stages):
            x = left + k * (cell + gap)
            base = y0 + row_h - gap - s
            d.rectangle([x, base, x + cell - 1, base + s], fill=farmland)
            if isinstance(st, tuple):
                im.alpha_composite(st[0].resize((cell, cell), Image.NEAREST), (x, base - cell))
                im.alpha_composite(st[1].resize((cell, cell), Image.NEAREST), (x, base - 2 * cell))
            else:
                im.alpha_composite(st.resize((cell, cell), Image.NEAREST), (x, base - cell))
    im.save(path)
