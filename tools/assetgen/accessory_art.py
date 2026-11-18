"""장신구 (accessories, docs/plans/장신구_시스템_기획안.md): an icon for every effect, the five slot icons, the box, the
fragment and the sea-palace chest, and the 장신구 menu button icons.

    economy:acc/<effect>           one design per effect (accessories.yml `effects:`), written under assets/economy
                                   (textures/item/acc, models/item/acc, items/acc): the accessory item's item_model.
                                   Grades are told apart by the item name's colour, so an effect has one icon.
    acc_<slot>_<grade>             a plain piece of each slot (the empty-slot placeholders; the same for every grade);
    acc_box, acc_fragment,         economy_goods.ICONS takes these in, so economy_build.py writes them to
    dragon_chest                   economy/content/economy/textures/<name>.png
    economy:gui/<name>             the menu buttons (economy_icons.GUI takes these in)

Items: 16x16 on the pixelart Canvas like economy_goods.py (light from the top left, the darkest step as the outline;
small motifs are hand-drawn masks shaded as a bevel). Every effect has its own design that says what it does, in the
materials that suit it (LOOK); the slot is the kind of piece it is:

    ring       a band seen at an angle, the motif set on top of it like a stone
    necklace   a chain hanging in a V from the corners, the motif as the pendant
    earring    one drop earring: a hook at the top, the motif hanging under it
    belt       a strap across the middle, the motif as the buckle (가방: a pouch hanging from it)
    bracelet   a bangle seen from above, the motif as a charm hanging at its front

Menu buttons: the shared UI look (stsv_assets/ui_icons.py: a light two-grey glyph, a 1-px dark outline, colour only where it
means something), drawn with a local copy of its colours (the shared letters are never redefined).

    python3 accessory_art.py [preview.png]     # writes everything and, if given, a preview sheet
"""
import json
import math
import sys
from pathlib import Path

import yaml
from PIL import Image

from stsv_assets.pixelart import Canvas, color

SLOTS = ("ring", "necklace", "earring", "belt", "bracelet")
GRADES = ("common", "fine", "masterwork", "treasure")
SLOT_NAMES = {"ring": "반지", "necklace": "목걸이", "earring": "귀걸이", "belt": "허리띠", "bracelet": "팔찌"}
GRADE_NAMES = {"common": "보통", "fine": "진품", "masterwork": "명품", "treasure": "국보"}

PACK = Path(__file__).resolve().parents[2] / "economy" / "content" / "economy"
NS = "economy"


class Style:
    """A design's materials: metal, stone, second stone, cloth (straps, purses, book covers)."""

    def __init__(self, m, g, a, s="leather"):
        self.m, self.g, self.a, self.s = m, g, a, s


ICONS = {}            # acc_<slot>_<grade>, acc_box, acc_fragment, dragon_chest -> textures/<name>.png
ACC = {}              # <effect> -> economy:item/acc/<effect>


# ================================================================================================
# Helpers
# ================================================================================================

def spark(c, pts, mat="lamp", step=5):
    """Loose light (sparks, rays, wind, motion lines): drawn after the outline, so it floats without one."""
    if not hasattr(c, "post"):
        c.post = []
    c.post += [(x, y, mat, step) for x, y in pts]
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


def _rim(c, pts):
    """Darkens the band pixels round a hole (its inner wall), so the hole reads even on a grey slot."""
    holes = {(x, y) for x in range(16) for y in range(16) if (x, y) not in c.px}
    for x, y in pts:
        if (x, y) in c.px and 1 <= x <= 14 and 1 <= y <= 14:
            inner = [(x + dx, y + dy) for dx, dy in ((0, 1), (0, -1), (1, 0), (-1, 0)) if (x + dx, y + dy) in holes]
            if inner and all(_enclosed(c, q) for q in inner):
                c.px[(x, y)][1] = 0 if y < 10 else 1
    return c


def gem(c, cx, cy, rx, ry, mat, z, lo=2, hi=4):
    """A cabochon with its glint at the top left."""
    c.ellipse(cx, cy, rx, ry, mat, z, lo=lo, hi=hi, gloss=0.8)
    c.put(round(cx - rx * 0.45 - 0.2), round(cy - ry * 0.45 - 0.2), mat, 5, z=z + 0.5)
    return c


def shape(c, rows, mat, z, ox=0, oy=0, lo=2, mid=3, hi=4, sunken=False):
    """A hand-drawn shape: 'X' pixels are shaded like a bevel (lit on the top and left edges, dark on the bottom and
    right edges; reversed when `sunken`), a digit sets that pixel's step outright. '.' and ' ' are left alone."""
    pts = {(ox + x, oy + y): ch for y, row in enumerate(rows) for x, ch in enumerate(row) if ch not in ". "}
    for (x, y), ch in pts.items():
        if ch.isdigit():
            step = int(ch)
        else:
            lit = (x, y - 1) not in pts or (x - 1, y) not in pts
            dark = (x, y + 1) not in pts or (x + 1, y) not in pts
            if sunken:
                lit, dark = dark, lit
            step = hi if lit and not dark else lo if dark and not lit else mid
        c.put(x, y, mat, step, z=z)
    return c


# ================================================================================================
# The pieces every design of a slot shares
# ================================================================================================

def ring_band(c, P, cy=10.7):
    """The band of a ring seen at an angle, filling the bottom of the icon."""
    c.ellipse(8, cy, 6.5, 4.0, P.m, 1, lo=1, hi=4)
    band = c.last()
    c.hole(8, cy + 0.4, 4.3, 2.2)
    _rim(c, band)
    c.dots([(2, 10), (2, 11)], P.m, 5, z=1.5)          # light on the band's left side
    return band


def collet(c, P, x0=5.6, x1=10.4, y0=5.4, y1=7.6, z=3):
    """The metal mount a ring's motif sits on."""
    c.rect(x0, y0, x1, y1, P.m, z, normal=(0, -0.4, 1), lo=2, hi=4)
    return c


def chain(c, P, n=5):
    """A fine chain hanging in a V from the two top corners to the pendant's bail."""
    for i in range(n):
        c.put(2 + i, 1 + i, P.m, 4 if i % 2 == 0 else 2, z=1).put(13 - i, 1 + i, P.m, 4 if i % 2 == 0 else 2, z=1)
    return c


def bail(c, P, y=5.0):
    """The little loop the pendant hangs from, a bead of the second stone on it."""
    c.rect(6.6, y, 9.4, y + 1.8, P.m, 2, normal=(0, -0.4, 1), lo=2, hi=4)
    return c


def hook(c, P, x=7):
    """An earring's French hook: up from the bead, over the top and down behind, then the bead under it."""
    for i, p in enumerate(((x, 3), (x, 2), (x + 1, 1), (x + 2, 1), (x + 3, 2), (x + 3, 3), (x + 3, 4))):
        c.put(*p, P.m, (4, 4, 5, 4, 3, 2, 2)[i], z=1)
    c.ellipse(x + 0.5, 4.5, 0.95, 0.95, P.a, 2, lo=2, hi=5)
    return c


def strap(c, P, y0=5.6, y1=10.4, holes=True):
    """A belt strap right across the icon, a stitched edge, holes at the tip."""
    c.rect(0.6, y0, 15.4, y1, P.s, 0, normal=(0, -0.25, 1), lo=1, hi=3)
    s = c.last()
    top, bot = min(y for _, y in s), max(y for _, y in s)
    c.tone([(x, y) for x, y in s if y == top], 1)
    c.tone([(x, y) for x, y in s if y == bot], -1)
    c.tone([(x, y) for x, y in s if y == top + 1 and x % 2 == 1], 1)
    if holes:
        c.tone([(13, (top + bot) // 2 + 1)], -2)
    return s


def bangle(c, P, cy=6.0):
    """A bracelet seen from above at an angle, its far side in shadow; charms hang from its front."""
    c.ellipse(8, cy, 6.6, 4.2, P.m, 1, lo=1, hi=4)
    band = c.last()
    c.hole(8, cy + 0.3, 4.6, 2.3)
    _rim(c, band)
    c.tone([(x, y) for x, y in band if y <= cy - 2.5], -1)
    c.line(2, round(cy), 3, round(cy) + 2, P.m, 5, z=1.5)
    return band


# ================================================================================================
# 반지 rings
# ================================================================================================

def mining_speed(P):
    """채굴 속도: a little pickaxe set on a silver ring, its head cut from a diamond, sparks flying off it."""
    c = Canvas()
    ring_band(c, P)
    shape(c, ["",
              ".....XXXXXX",
              "...XX5XXXXXXX",
              "..XXX......XXX",
              "..XX........XX",
              "..X..........X"], P.g, 5)
    shape(c, ["", ".......45", ".......43", "......4432"], P.m, 6)
    shape(c, ["", "", "", "", ".......43", ".......43", ".......32", ".......32", ".......32"], "wood", 5.5)
    spark(c, [(14, 0), (15, 1), (0, 1), (1, 0)], "lamp", 4)
    spark(c, [(14, 1), (1, 1)], "lamp", 5)
    return c


def vein_burst(P):
    """광맥 폭발: a lump of ore on a gold ring, split by a glowing crack, a burst of light behind it."""
    c = Canvas()
    ring_band(c, P)
    shape(c, ["",
              "......XXXX",
              ".....XXXXXX",
              "....XXXXXXXX",
              "....XXXXXXXX",
              "....XXXXXXXX",
              ".....XXXXXX",
              "......XXXX"], "stone", 4)
    c.dots([(5, 4), (10, 5), (9, 2)], P.g, 3, z=5).dots([(6, 4), (10, 4)], P.g, 5, z=5).dots([(6, 5), (9, 6)], P.g, 2, z=5)
    c.dots([(8, 1), (8, 2), (7, 3), (7, 4), (8, 5), (8, 6), (7, 7)], "lamp", 5, z=6)
    spark(c, [(2, 1), (3, 2), (13, 1), (12, 2), (1, 4), (2, 4), (13, 4), (14, 4), (2, 7), (13, 7)], "glow_amber", 5)
    spark(c, [(1, 0), (14, 0), (0, 4), (15, 4)], "glow_amber", 4)
    return c


def harvest_wind(P):
    """풍년 바람: a sheaf of golden wheat tied with a green ribbon, gusts of wind blowing past it."""
    c = Canvas()
    ring_band(c, P)
    shape(c, ["",
              "..4....45....4",
              "..43...43...43",
              "...42..42..42",
              "...43..43..43",
              "....2..32..2"], "corn", 5)
    shape(c, ["", "", "", "", "", "", "", ".....2.22.2"], "straw", 4)
    shape(c, ["", "", "", "", "", "", "......4432"], P.a, 6)
    spark(c, [(0, 4), (1, 3), (2, 3), (13, 6), (14, 6), (15, 5)], "snow", 4)
    return c


def master_hand(P):
    """장인의 손: a gold signet ring, a silver smith's hammer standing out on its sapphire face."""
    c = Canvas()
    ring_band(c, P)
    shape(c, ["",
              "...XXXXXXXXXX",
              "..XXXXXXXXXXXX",
              "..XXXXXXXXXXXX",
              "..XXXXXXXXXXXX",
              "..XXXXXXXXXXXX",
              "..XXXXXXXXXXXX",
              "..XXXXXXXXXXXX",
              "...XXXXXXXXXX"], P.m, 3)
    shape(c, ["", "",
              "....XXXXXXXX",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "....XXXXXXXX"], P.g, 4, lo=1, mid=2, hi=3, sunken=True)
    shape(c, ["", "", "", "....55554", "....33332"], "fish_silver", 5)
    shape(c, ["", "", "", "", "", "......43", ".......43", "........43", ".........43"], "wood", 5)
    return c


def rich_vein(P):
    """풍부한 광맥: two emerald crystals grown side by side on a silver ring, one taller behind."""
    c = Canvas()
    ring_band(c, P)
    shape(c, ["", "",
              "....4",
              "...442",
              "...4321",
              "...4321",
              "...4321",
              "...4321"], P.g, 4)
    shape(c, ["",
              ".........5",
              "........542",
              ".......45321",
              ".......45321",
              ".......44321",
              ".......44321",
              ".......44321"], P.g, 5)
    shape(c, ["", "", "", "", "", "", "", "", "..XXXXXXXXXXX"], P.m, 6)
    return c


def magnet(P):
    """자석: a red horseshoe magnet set on a silver ring, its two bright poles, the field curling round it."""
    c = Canvas()
    ring_band(c, P)
    shape(c, ["", "", "",
              "....XXX..XXX",
              "....XXX..XXX",
              "....XXXXXXXX",
              ".....XXXXXX",
              "......XXXX"], "signal_red", 4)
    shape(c, ["", "....455..454", "....332..332"], P.m, 5)
    shape(c, ["", "", "", "", "", "", ".......53"], P.a, 5)
    spark(c, [(2, 1), (1, 2), (1, 3), (1, 4), (2, 5), (13, 1), (14, 2), (14, 3), (14, 4), (13, 5)], "glow_cyan", 4)
    return c


def blessing(P):
    """대장장이의 축복: a dark anvil set on a gold ring, a blessed star of light over it."""
    c = Canvas()
    ring_band(c, P)
    c.poly([(1.8, 4.4), (4.6, 3.4), (13.2, 3.4), (13.2, 5.6), (4.8, 5.6), (3.0, 5.0)], "blacksteel", 4, normal=(-0.2, -0.7, 0.7), lo=2, hi=4)
    c.rect(6.0, 5.6, 10.0, 7.0, "blacksteel", 3.9, normal=(-0.3, 0, 1), lo=1, hi=3)
    c.poly([(4.4, 8.6), (5.8, 6.8), (10.2, 6.8), (11.6, 8.6)], "blacksteel", 3.8, normal=(-0.2, -0.3, 1), lo=1, hi=3)
    c.line(5, 4, 12, 4, "fish_silver", 4, z=5)                                       # the bright working face
    c.put(5, 4, "fish_silver", 5, z=5)
    spark(c, [(8, 0), (7, 1), (9, 1), (8, 2)], P.a, 4)
    spark(c, [(8, 1)], "lamp", 5)
    spark(c, [(3, 1), (13, 1), (2, 2), (12, 2)], "lamp", 4)
    return c


# ================================================================================================
# 목걸이 necklaces
# ================================================================================================

def bite(P):
    """손맛: a barbed silver fishing hook as the pendant, a red float bead at its eye."""
    c = Canvas()
    chain(c, P)
    bail(c, P, 4.6)
    shape(c, ["", "", "", "", "", "", "", "",
              ".......XX",
              "...5...XX",
              "..445..XX",
              "..XX...XX",
              "..XX...XX",
              "...XXXXXX",
              "....XXXX"], P.m, 3)
    shape(c, ["", "", "", "", "", "", "......5432", ".......32"], P.a, 4)
    spark(c, [(12, 11), (11, 12), (12, 13), (13, 12)], "water", 4)
    spark(c, [(12, 12)], "water", 5)
    return c


def dragon_treasure(P):
    """용궁 보물: a dragon's gold three-taloned claw clutching a big pearl, a jade bead at the wrist."""
    c = Canvas()
    chain(c, P)
    c.ellipse(7.9, 11.0, 3.0, 2.9, "pearl", 3, lo=2, hi=4)
    c.put(6, 9, "pearl", 5, z=3.5)
    shape(c, ["", "", "", "", "",
              "......4554",
              "......3443",
              ".....434332",
              "....43.32.32",
              "...43......32",
              "...42......31",
              "...32......21",
              "....3......2"], P.m, 5)
    shape(c, ["", "", "", "", "", "", "", "", "", ".......44", "", "", "", ".....3....3"], "snow", 5.5)
    shape(c, ["", "", "", "", "", ".......53"], P.a, 6)
    return c


def full_net(P):
    """만선: a fishing net bulging with its catch, a fish's tail sticking out at the top, a float at its mouth."""
    c = Canvas()
    chain(c, P)
    shape(c, ["", "", "", "", "", "", "", "", ".............54", "............43", "............32", ".............21"], "fish_blue", 2.5)
    bag = ["", "", "", "", "", "", "", "",
           "....XXXXXXXX",
           "...XXXXXXXXXX",
           "...XXXXXXXXXX",
           "...XXXXXXXXXX",
           "....XXXXXXXX",
           ".....XXXXXX",
           "......XXXX"]
    pts = [(x, y) for y, row in enumerate(bag) for x, ch in enumerate(row) if ch == "X"]
    for x, y in pts:
        if (x + y) % 3 == 0 or (x - y) % 3 == 0:
            c.put(x, y, "burlap", 4 if y < 11 else 3, z=3.2)
        elif ((x - 7.6) / 3.4) ** 2 + ((y - 10.8) / 1.7) ** 2 <= 1:
            c.put(x, y, "fish_silver", 4 if y < 11 else 2, z=3.1)
        else:
            c.put(x, y, "burlap", 1, z=3)
    shape(c, ["", "", "", "", "", "", "", "...XXXXXXXXXX"], "burlap", 4, lo=3, mid=4, hi=5)
    shape(c, ["", "", "", "", "", "......5432", ".......32"], P.a, 5)
    return c


def rare_find(P):
    """귀한 발견: a wild ginseng root with arms and legs, red berries and leaves on its head."""
    c = Canvas()
    chain(c, P, 4)
    shape(c, ["", "", "", "", "",
              "..XX........XX",
              ".XXXX......XXXX",
              "..XXXX....XXXX",
              "....XX....XX"], "leaf", 4)
    shape(c, ["", "", "", "", ".......XX", "......XXXX", ".......XX"], "tomato", 5, lo=2, mid=3, hi=4)
    c.put(7, 4, "tomato", 5, z=6)
    shape(c, ["", "", "", "", "", "", "",
              ".......XX",
              "......XXXX",
              ".....XXXXXX",
              "....XXXXXXXX",
              "....X.XXXX.X",
              "......XXXX",
              "......XXXX",
              ".....XX..XX"], "whetstone", 3, lo=2, mid=3, hi=4)
    c.dots([(7, 9), (8, 12), (7, 13)], "whetstone", 1, z=3.5)
    return c


def appraiser(P):
    """감정사의 눈: a jeweller's loupe, its sapphire lens with an eye looking out of it."""
    c = Canvas()
    chain(c, P)
    bail(c, P)
    c.tube([(10.2, 12.4), (12.8, 14.6)], [0.95, 0.8], "wood_dark", 2, lo=2, hi=4)
    c.ellipse(7.6, 10.4, 4.0, 3.9, P.m, 3, lo=1, hi=4)
    c.ellipse(7.6, 10.4, 2.8, 2.7, P.g, 4, lo=2, hi=4, gloss=0)
    c.ellipse(7.8, 10.6, 1.2, 1.2, P.g, 4.5, lo=0, hi=0)
    c.put(8, 11, "soot", 0, z=5).put(6, 9, "snow", 5, z=5).put(7, 9, "snow", 3, z=5)
    return c


GEAR = ["....54....",
        ".54.43.43.",
        ".44443332.",
        "..443332..",
        "5443..3332",
        "4432..3221",
        "..432221..",
        ".43322211.",
        ".32.21.21.",
        "....21...."]


def cog(c, mat, hub, ox, oy, z):
    """The 10x10 cog, shaded by hand, a stone in its hub."""
    shape(c, [""] * oy + ["." * ox + r for r in GEAR], mat, z)
    shape(c, [""] * (oy + 4) + ["." * (ox + 4) + "54", "." * (ox + 4) + "32"], hub, z + 1)
    return c


def remote_machine(P):
    """원격 기계: a key whose bow is a cog, the signal it sends going out from its tip."""
    c = Canvas()
    chain(c, P, 4)
    cog(c, P.m, P.a, 1, 5, 3)
    shape(c, ["", "", "", "", "", "", "", "", "",
              "...........4444",
              "...........3332",
              ".............32",
              ".............21"], P.m, 2.5)
    spark(c, [(12, 6), (13, 7), (14, 4), (15, 5), (15, 6)], "glow_cyan", 4)
    spark(c, [(11, 7)], "glow_cyan", 5)
    return c


# ================================================================================================
# 귀걸이 earrings
# ================================================================================================

def focus(P):
    """장인의 집중: an hourglass hanging from a gold hook, its amber sand running through."""
    c = Canvas()
    hook(c, P)
    c.poly([(5.6, 6.4), (10.4, 6.4), (8.7, 10.0), (7.3, 10.0)], "glass", 3, normal=(-0.4, -0.3, 1), lo=2, hi=4)
    c.poly([(7.3, 10.0), (8.7, 10.0), (10.4, 13.6), (5.6, 13.6)], "glass", 3, normal=(-0.4, -0.3, 1), lo=2, hi=4)
    c.poly([(6.4, 7.6), (9.6, 7.6), (8.4, 9.6), (7.6, 9.6)], P.g, 4, normal=(-0.3, -0.3, 1), lo=2, hi=4)
    c.dots([(8, 10), (8, 11)], P.g, 4, z=4)
    c.poly([(5.8, 13.6), (10.2, 13.6), (8, 11.6)], P.g, 4, normal=(-0.3, -0.5, 1), lo=2, hi=4)
    for y0 in (5.2, 13.4):
        c.rect(4.6, y0, 11.4, y0 + 1.4, P.m, 5, normal=(-0.2, -0.5, 1), lo=2, hi=4)
    c.put(6, 7, "snow", 5, z=6)
    return c


def machine_haste(P):
    """기계 가속: a brass cog hanging from the hook, spinning so fast it leaves streaks behind it."""
    c = Canvas()
    hook(c, P, 7)
    cog(c, P.m, P.a, 4, 5, 3)
    spark(c, [(0, 7), (1, 7), (2, 7), (0, 10), (1, 10), (0, 13), (1, 13), (2, 13)], "snow", 4)
    spark(c, [(1, 10)], "snow", 5)
    return c


def timber(P):
    """벌목 회오리: a little axe hanging from a silver hook, leaves whirling round it."""
    c = Canvas()
    hook(c, P)
    c.tube([(5.0, 14.4), (9.6, 6.2)], [0.75, 0.75], "wood", 2, lo=2, hi=4)
    c.poly([(8.0, 5.8), (11.4, 5.0), (13.6, 6.0), (13.8, 10.0), (12.0, 10.4), (10.4, 8.6)], P.m, 3, normal=(-0.3, -0.3, 1), lo=2, hi=4)
    c.line(13, 6, 13, 9, P.m, 5, z=4)
    for (x, y, k) in ((3.2, 8.4, 1), (12.4, 13.2, -1)):
        c.poly([(x - 1.6, y + k * 0.8), (x, y - 1.0), (x + 1.6, y - k * 0.6), (x, y + 1.2)], "leaf", 4, normal=(-0.3, -0.4, 1), lo=2, hi=4)
    spark(c, [(1, 11), (2, 12), (14, 10), (15, 11)], "sprout", 4)
    return c


def quake(P):
    """땅울림: a clod of earth with grass on top hanging from the hook, cracked through with light, the ground shaking."""
    c = Canvas()
    hook(c, P)
    shape(c, ["", "", "", "", "", "",
              ".....XXXXXX",
              "....XXXXXXXX"], "leaf", 4)
    shape(c, ["", "", "", "", "", "", "", "",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "....XXXXXXXX",
              "....XXXXXXXX",
              ".....XXXXXX",
              ".......XX"], "soil", 3, lo=1, mid=2, hi=3)
    c.dots([(8, 7), (8, 8), (7, 9), (7, 10), (8, 11), (9, 12), (6, 11), (5, 12)], P.g, 5, z=5)
    c.dots([(9, 8), (8, 10)], P.g, 3, z=5)
    spark(c, [(1, 8), (0, 9), (1, 10), (14, 8), (15, 9), (14, 10)], "snow", 4)
    spark(c, [(2, 5), (13, 4), (1, 13), (14, 13)], "soil", 3)
    return c


def vein_sight(P):
    """광맥 투시: an emerald eye in gold lids hanging from the hook, rays of sight shining out of it."""
    c = Canvas()
    hook(c, P)
    c.rect(7.0, 5.0, 9.0, 7.6, P.m, 2.5, normal=(0, -0.4, 1), lo=2, hi=4)
    c.poly([(2.6, 10.4), (5.4, 7.6), (10.6, 7.6), (13.4, 10.4), (10.6, 13.2), (5.4, 13.2)], P.m, 3, normal=(-0.2, -0.3, 1), lo=1, hi=4)
    c.poly([(4.2, 10.4), (6.0, 8.8), (10.0, 8.8), (11.8, 10.4), (10.0, 12.0), (6.0, 12.0)], "snow", 4, normal=(-0.2, -0.3, 1), lo=2, hi=3)
    c.ellipse(8, 10.4, 1.9, 1.9, P.g, 5, lo=2, hi=4, gloss=0)
    c.put(8, 10, "soot", 0, z=6).put(7, 9, "snow", 5, z=6)
    spark(c, [(3, 6), (2, 5), (13, 6), (14, 5), (3, 14), (13, 14), (0, 10), (15, 10)], "glow_cyan", 4)
    return c


def refine_hand(P):
    """제련의 손: a cut ruby hanging from the hook, a little hammer tapping it, a spark where it strikes."""
    c = Canvas()
    hook(c, P, 6)
    shape(c, ["", "", "", "", "", "......XXX", "......XXX"], P.m, 2.5)
    shape(c, ["", "", "", "", "", "", "",
              "....4544333",
              "...444443322",
              "...322222211",
              "....4332221",
              ".....43221",
              "......421",
              ".......1"], P.g, 3)
    shape(c, ["", "", "", "", "", "", "..........5443", "..........3221"], "blacksteel", 5)
    shape(c, ["", "", "", "", "", "", "", "", "...........43", "...........43", "...........32", "...........32"], "wood", 4.5)
    spark(c, [(9, 5), (10, 5), (9, 4)], "lamp", 5)
    return c


# ================================================================================================
# 허리띠 belts
# ================================================================================================

def move_speed(P):
    """이동 속도: a winged gold buckle, white wings spreading over an indigo strap."""
    c = Canvas()
    strap(c, P)
    for k in (-1, 1):
        pts = [(6.4, 6.0), (2.4, 2.2), (1.0, 2.8), (1.4, 5.0), (2.6, 7.0), (4.2, 8.8), (6.4, 9.8)]
        c.poly([(8 + k * (8 - x), y) for x, y in pts], "feather", 3, normal=(-0.3, -0.5, 1), lo=2, hi=4)
        w = c.last()
        c.tone([(x, y) for x, y in w if (x * k + y) % 3 == 0 and y > 3], -1)
    c.ellipse(8, 8, 2.4, 3.2, P.m, 5, lo=1, hi=4)
    gem(c, 8, 8, 1.2, 1.8, P.g, 6)
    return c


def vehicle_speed(P):
    """탈것 가속: the buckle is a wheel, a black tyre on a bright rim, spokes and a ruby hub, speed lines behind."""
    c = Canvas()
    strap(c, P)
    c.ellipse(8, 8, 5.2, 5.2, "rubber", 3, lo=1, hi=4)
    c.ellipse(8, 8, 3.6, 3.6, P.m, 4, lo=2, hi=4)
    c.ellipse(8, 8, 2.6, 2.6, "rubber", 4.5, lo=0, hi=1)
    for (x0, y0, x1, y1) in ((8, 5, 8, 11), (5, 8, 11, 8), (6, 6, 10, 10), (10, 6, 6, 10)):
        c.line(x0, y0, x1, y1, P.m, 3, z=5)
    gem(c, 8, 8, 1.2, 1.2, P.g, 6)
    spark(c, [(0, 3), (1, 3), (2, 3), (0, 12), (1, 12), (2, 12)], "snow", 4)
    return c


def cargo(P):
    """짐 더 싣기: the buckle is a wooden crate with a brace across it, bound at the corners in iron."""
    c = Canvas()
    strap(c, P)
    for y in range(3, 13):
        for x in range(3, 13):
            edge = x in (3, 12) or y in (3, 12)
            if edge:
                c.put(x, y, "wood_dark", 4 if (x == 3 or y == 3) else 2, z=3)
            elif x - 3 == 12 - y or x - 3 == 11 - y:
                c.put(x, y, "wood_dark", 3, z=3)
            else:
                c.put(x, y, "wood", 2 if y in (6, 9) else 4 if y in (4, 7, 10) else 3, z=3)
    for (x, y) in ((3, 3), (11, 3), (3, 11), (11, 11)):
        shape(c, [""] * y + ["." * x + "54", "." * x + "32"], P.m, 4)
    shape(c, [""] * 7 + ["......." + "54", "......." + "32"], P.g, 4)
    return c


def bag(P):
    """가방: a canvas pouch hanging from the belt, its leather flap shut with a gold clasp and an emerald."""
    c = Canvas()
    strap(c, P, 1.6, 5.4, holes=False)
    shape(c, ["", "", "", "", "", "",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "....XXXXXXXX"], "burlap", 3, lo=2, mid=3, hi=4)
    c.tone([(3, y) for y in range(6, 14)], 1)
    shape(c, ["", "", "", "", "",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "...XXXXXXXXXX",
              "....XXXXXXXX",
              ".....XXXXXX",
              "......XXXX"], "wine", 4)
    shape(c, ["", "", "", "", "", "", "", "", "", ".......54", ".......32"], P.m, 5)
    c.dots([(7, 10)], P.g, 4, z=5.5).dots([(8, 10)], P.g, 2, z=5.5)
    shape(c, ["", "", "", "....5........5", "....3........3", "....2........2"], P.m, 3.5)
    return c


def fortune_find(P):
    """행운의 발견: the buckle is a round gold plate with a four-leaf clover on it."""
    c = Canvas()
    strap(c, P)
    c.ellipse(8, 8, 5.0, 5.0, P.m, 3, lo=1, hi=4)
    c.ellipse(8, 8, 3.8, 3.8, "leaf_dark", 3.5, lo=1, hi=1)
    for (x, y) in ((6.4, 6.4), (9.6, 6.4), (6.4, 9.6), (9.6, 9.6)):
        c.ellipse(x, y, 1.7, 1.7, "leaf", 4, lo=2, hi=4, gloss=0.9)
    c.line(9, 10, 11, 12, "leaf_dark", 4, z=3.8)
    gem(c, 8, 8, 0.9, 0.9, P.a, 5)
    return c


# ================================================================================================
# 팔찌 bracelets
# ================================================================================================

def dock_bonus(P):
    """하역 웃돈: two old gold coins (엽전, square holes) hanging from a silver bangle."""
    c = Canvas()
    bangle(c, P)
    for ox, oy, z in ((8, 7, 3), (3, 9, 4)):
        shape(c, [""] * oy + ["." * ox + r for r in (".XXXX.", "XXXXXX", "XX11XX", "XX11XX", "XXXXXX", ".XXXX.")], "gold", z)
        c.put(ox + 1, oy + 1, "gold", 5, z=z)
    return c


def abundance(P):
    """풍작: a bangle strung from golden grains, an emerald bead at the front, an ear of rice hanging from it."""
    c = Canvas()
    n = 12
    beads = sorted(((8 + 5.6 * math.cos(2 * math.pi * k / n), 5.6 + 3.6 * math.sin(2 * math.pi * k / n), k) for k in range(n)),
                   key=lambda b: b[1])
    for x, y, k in beads:
        if k == 3:
            continue
        c.ellipse(x, y, 1.3, 1.05, "corn", y, lo=1 if y < 5 else 2, hi=4)
    gem(c, 8, 9.2, 1.4, 1.2, P.g, 10)
    c.line(8, 10, 8, 11, "straw", 2, z=9)
    for k, y in enumerate((11.4, 12.6, 13.8)):
        for side in (-1, 1):
            c.ellipse(8 + side * 1.0, y, 0.9, 0.8, "corn", 8 + k * 0.1, lo=2, hi=4)
    c.put(8, 14, "corn", 4, z=9)
    return c


def thrift(P):
    """알뜰한 손: a little red 복주머니 purse tied at the neck with a gold cord, hanging from a gold bangle."""
    c = Canvas()
    bangle(c, P)
    c.ellipse(8, 12.0, 3.6, 2.8, P.s, 3, lo=1, hi=4)
    c.poly([(5.6, 7.6), (10.4, 7.6), (9.2, 10.0), (6.8, 10.0)], P.s, 2.9, normal=(-0.2, -0.4, 1), lo=2, hi=4)
    c.rect(5.8, 9.4, 10.2, 10.6, P.m, 4, normal=(0, -0.4, 1), lo=2, hi=4)
    c.dots([(4, 10), (11, 10), (4, 11), (11, 11)], P.m, 3, z=4.5)
    gem(c, 8, 12.2, 1.0, 1.0, P.a, 5)
    return c


def request_bonus(P):
    """의뢰 웃돈: a rolled-up commission scroll with gold ends, tied with a ribbon and sealed with red wax."""
    c = Canvas()
    bangle(c, P)
    shape(c, [""] * 8 + [".........", "...XXXXXXXXXX", "...XXXXXXXXXX", "...XXXXXXXXXX", "...XXXXXXXXXX"], "paper", 3, lo=2, mid=3, hi=4)
    c.tone([(x, 9) for x in range(3, 13)], 1)
    c.tone([(x, 12) for x in range(3, 13)], -1)
    shape(c, [""] * 8 + ["..X", "..X", "..X", "..X", "..X", "..X"], P.m, 4)
    shape(c, [""] * 8 + [".............X", ".............X", ".............X", ".............X", ".............X", ".............X"], P.m, 4)
    shape(c, [""] * 9 + ["......XX", "......XX", "......XX", "......XX"], P.s, 4.5)
    shape(c, [""] * 11 + [".......XXX", "......XXXXX", ".......XXX"], "crimson", 5, lo=2, mid=3, hi=4)
    c.put(8, 12, "crimson", 5, z=5.5)
    return c


def craft_learning(P):
    """장인의 배움: a book of craft notes lying open, a writing brush across its pages."""
    c = Canvas()
    bangle(c, P)
    shape(c, [""] * 13 + ["..XXXXXXXXXXXX", "..XXXXXXXXXXXX"], P.s, 3)
    shape(c, [""] * 8 + ["...XXX....XXX", "...XXXXXXXXXX", "...XXXXXXXXXX", "...XXXXXXXXXX", "...XXXXXXXXXX"],
          "paper", 4, lo=3, mid=4, hi=4)
    for y in (10, 12):
        c.dots([(4, y), (5, y), (6, y), (9, y), (10, y), (11, y)], "paper", 1, z=4.2)
    c.dots([(7, y) for y in range(9, 13)], "paper", 2, z=4.2)
    c.dots([(8, y) for y in range(9, 13)], "paper", 3, z=4.2)
    c.tube([(14.2, 6.4), (10.6, 10.0)], [0.55, 0.55], "bamboo", 5, lo=2, hi=4)
    c.tube([(10.6, 10.0), (8.8, 12.0)], [0.8, 0.3], "soot", 5.1, lo=1, hi=3)
    c.put(11, 9, P.m, 4, z=5.2)
    return c


# ================================================================================================
# The plain pieces (acc_<slot>_<grade>: the empty-slot placeholders, the same for every grade)
# ================================================================================================

def plain_ring(P):
    c = Canvas()
    ring_band(c, P)
    collet(c, P)
    gem(c, 8, 4.2, 2.8, 2.4, P.g, 4)
    return c


def plain_necklace(P):
    c = Canvas()
    chain(c, P)
    bail(c, P)
    c.poly([(5.0, 8.4), (11.0, 8.4), (10.4, 11.6), (8, 14.6), (5.6, 11.6)], P.g, 3, normal=(-0.3, -0.2, 1), lo=2, hi=4)
    c.poly([(8, 8.4), (11.0, 8.4), (10.4, 11.6), (8, 14.6)], P.g, 3.1, normal=(0.7, 0, 0.6), lo=1, hi=3)
    c.rect(6.0, 7.0, 10.0, 8.6, P.m, 4, normal=(0, -0.4, 1), lo=2, hi=4)
    c.put(6, 9, "snow", 5, z=5)
    return c


def plain_earring(P):
    c = Canvas()
    hook(c, P)
    c.rect(6.6, 5.4, 9.4, 7.4, P.m, 2.5, normal=(0, -0.4, 1), lo=2, hi=4)
    c.ellipse(8, 10.8, 3.0, 3.6, P.m, 3, lo=1, hi=4)
    gem(c, 8, 10.8, 2.0, 2.6, P.g, 4)
    return c


def plain_belt(P):
    c = Canvas()
    strap(c, P)
    c.rect(4.4, 3.0, 11.6, 13.0, P.m, 3, normal=(-0.3, -0.3, 1), lo=1, hi=4)
    c.rect(6.0, 4.6, 10.0, 11.4, P.s, 3.5, normal=(0, -0.3, 1), lo=1, hi=2)
    gem(c, 8, 8, 1.3, 2.0, P.g, 4)
    return c


def plain_bracelet(P):
    c = Canvas()
    bangle(c, P)
    c.rect(7.0, 8.0, 9.0, 9.6, P.m, 3, normal=(0, -0.4, 1), lo=2, hi=4)
    gem(c, 8, 11.6, 2.4, 2.2, P.g, 4)
    return c


# each design's metal, stone, second stone and cloth
S = Style
LOOK = {
    "mining_speed": S("fish_silver", "glow_cyan", "glow_cyan"),
    "vein_burst": S("gold", "ruby", "ruby"),
    "harvest_wind": S("fish_silver", "bottle_green", "bottle_green"),
    "master_hand": S("gold", "sapphire", "sapphire"),
    "rich_vein": S("fish_silver", "bottle_green", "bottle_green"),
    "magnet": S("fish_silver", "sapphire", "sapphire"),
    "blessing": S("gold", "sapphire", "glow_cyan"),
    "bite": S("fish_silver", "sapphire", "signal_red"),
    "dragon_treasure": S("gold", "pearl", "bottle_green"),
    "full_net": S("fish_silver", "sapphire", "signal_red"),
    "rare_find": S("gold", "ruby", "ruby"),
    "appraiser": S("gold", "sapphire", "sapphire"),
    "remote_machine": S("brass", "sapphire", "glow_cyan"),
    "focus": S("gold", "topaz", "ruby"),
    "machine_haste": S("brass", "sapphire", "ruby"),
    "timber": S("fish_silver", "bottle_green", "bottle_green"),
    "quake": S("gold", "topaz", "topaz"),
    "vein_sight": S("gold", "bottle_green", "ruby"),
    "refine_hand": S("fish_silver", "ruby", "sapphire"),
    "move_speed": S("gold", "sapphire", "sapphire", "indigo"),
    "vehicle_speed": S("fish_silver", "ruby", "ruby", "leather"),
    "cargo": S("steel", "topaz", "topaz", "leather"),
    "bag": S("gold", "bottle_green", "bottle_green", "leather"),
    "fortune_find": S("gold", "topaz", "topaz", "fabric_red"),
    "dock_bonus": S("fish_silver", "sapphire", "sapphire"),
    "abundance": S("gold", "bottle_green", "bottle_green"),
    "thrift": S("gold", "bottle_green", "bottle_green", "fabric_red"),
    "request_bonus": S("gold", "ruby", "ruby", "indigo"),
    "craft_learning": S("fish_silver", "sapphire", "sapphire", "wine"),
}
PLAIN_LOOK = S("fish_silver", "sapphire", "sapphire", "leather")

PLAIN = {"ring": plain_ring, "necklace": plain_necklace, "earring": plain_earring, "belt": plain_belt, "bracelet": plain_bracelet}
DESIGNS = {fn.__name__: fn for fn in (
    mining_speed, vein_burst, harvest_wind, master_hand, rich_vein, magnet, blessing,
    bite, dragon_treasure, full_net, rare_find, appraiser, remote_machine,
    focus, machine_haste, timber, quake, vein_sight, refine_hand,
    move_speed, vehicle_speed, cargo, bag, fortune_find,
    dock_bonus, abundance, thrift, request_bonus, craft_learning)}


def finish(c):
    """Renders a canvas: the outline, then the loose sparks on top."""
    im = c.render()
    for x, y, mat, step in getattr(c, "post", ()):
        if 0 <= x < 16 and 0 <= y < 16:
            im.putpixel((x, y), color(mat, step))
    return im


def effects():
    """The effects of accessories.yml: id -> (Korean name, slot)."""
    data = yaml.safe_load((PACK / "accessories.yml").read_text(encoding="utf-8"))
    return {k: (v["name"], v["slot"]) for k, v in data["effects"].items()}


for _eff in DESIGNS:
    ACC[_eff] = (lambda e=_eff: finish(DESIGNS[e](LOOK[e])))
for _slot in SLOTS:
    for _grade in GRADES:
        ICONS[f"acc_{_slot}_{_grade}"] = (lambda s=_slot: finish(PLAIN[s](PLAIN_LOOK)))


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


NAMES = {f"acc_{s}_{g}": f"{GRADE_NAMES[g]} {SLOT_NAMES[s]}" for s in SLOTS for g in GRADES}
NAMES.update({"acc_box": "장신구 상자", "acc_fragment": "장신구 조각", "dragon_chest": "용궁 상자"})


# ================================================================================================
# Writing them out (economy_build.py does the same through economy_goods.ICONS / economy_icons.GUI / write_acc) and the preview
# ================================================================================================

ACC_DIRS = ("textures/item/acc", "models/item/acc", "items/acc")


def write_acc(assets):
    """economy:acc/<effect>_<grade>: the texture, its model and its item definition (the accessory's item_model).
    The three acc folders are this module's alone, so anything in them that is no longer made is removed."""
    folders = [assets / d for d in ACC_DIRS]
    for f in folders:
        f.mkdir(parents=True, exist_ok=True)
        for old in f.iterdir():
            if old.is_file():
                old.unlink()
    tex, model, item = folders
    for name, fn in ACC.items():
        fn().save(tex / f"{name}.png")
        (model / f"{name}.json").write_text(json.dumps(
            {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/acc/{name}"}}, indent=2))
        (item / f"{name}.json").write_text(json.dumps(
            {"model": {"type": "minecraft:model", "model": f"{NS}:item/acc/{name}"}}, indent=2))
    return len(ACC)


def write():
    missing = set(effects()) - set(DESIGNS)
    assert not missing, f"accessories.yml effects without a design: {sorted(missing)}"
    for name, fn in ICONS.items():
        fn().save(PACK / "textures" / f"{name}.png")
    assets = PACK / "assets" / NS
    write_acc(assets)
    for name, fn in GUI.items():
        fn().save(assets / "textures/item/gui" / f"{name}.png")
        (assets / "models/item/gui" / f"{name}.json").write_text(json.dumps(
            {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/gui/{name}"}}, indent=2))
        (assets / "items/gui" / f"{name}.json").write_text(json.dumps(
            {"model": {"type": "minecraft:model", "model": f"{NS}:item/gui/{name}"}}, indent=2))


def _font(size):
    from PIL import ImageFont
    try:
        return ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", size)
    except OSError:
        return ImageFont.load_default()


def sheet(path, scale=6):
    """The 29 effect icons, one row per slot with the Korean names, each on an inventory-grey tile with a 1x and 2x copy
    beside it; then the plain slot pieces, the box, fragment and chest, and the menu buttons. Dark background."""
    from PIL import ImageDraw
    font, head, small = _font(16), _font(20), _font(12)
    eff = effects()
    order = [(s, [e for e, (_n, sl) in eff.items() if sl == s]) for s in SLOTS]
    cell = 16 * scale
    cw, ch = cell + 58, cell + 48
    left = 110
    per = 8
    im = Image.new("RGBA", (left + per * cw + 10, 60 + (len(order) + 2) * (ch + 10) + 40), (30, 32, 38, 255))
    d = ImageDraw.Draw(im)

    def tile(x, y, ic, name=None, sub=None, bg=(139, 139, 139, 255)):
        d.rectangle([x, y, x + cell - 1, y + cell - 1], fill=bg)
        im.alpha_composite(ic.resize((cell, cell), Image.NEAREST), (x, y))
        d.rectangle([x + cell + 4, y + cell - 52, x + cell + 39, y + cell - 1], fill=bg)
        im.alpha_composite(ic.resize((32, 32), Image.NEAREST), (x + cell + 6, y + cell - 34))
        im.alpha_composite(ic, (x + cell + 6, y + cell - 51))
        if name:
            d.text((x, y + cell + 4), name, fill=(240, 240, 240, 255), font=font)
        if sub:
            d.text((x, y + cell + 24), sub, fill=(150, 155, 170, 255), font=small)

    d.text((14, 14), "장신구 · 효과별 아이콘 (economy:acc/<효과>)", fill=(255, 226, 150, 255), font=head)
    y = 56
    for s, es in order:
        d.text((14, y + cell // 2 - 12), f"{SLOT_NAMES[s]}", fill=(255, 226, 150, 255), font=head)
        d.text((14, y + cell // 2 + 14), f"{len(es)}종", fill=(150, 155, 170, 255), font=small)
        for j, e in enumerate(es):
            tile(left + j * cw, y, ACC[e](), eff[e][0], e)
        y += ch + 10
    d.text((14, y + cell // 2 - 12), "칸 기본", fill=(255, 226, 150, 255), font=head)
    for j, s in enumerate(SLOTS):
        tile(left + j * cw, y, ICONS[f"acc_{s}_common"](), SLOT_NAMES[s], f"acc_{s}_*")
    y += ch + 10
    d.text((14, y + cell // 2 - 12), "기타", fill=(255, 226, 150, 255), font=head)
    items = [(ICONS[n](), NAMES[n], (139, 139, 139, 255)) for n in ("acc_box", "acc_fragment", "dragon_chest")]
    items += [(GUI[n](), GUI_NAMES[n], (58, 60, 68, 255)) for n in GUI]
    for j, (ic, name, bg) in enumerate(items):
        tile(left + j * cw, y, ic, name, bg=bg)
    y += ch + 10
    im.crop((0, 0, im.width, y)).save(path)


if __name__ == "__main__":
    write()
    print(f"accessory art: {len(ACC)} effect icons, {len(ICONS)} item icons, {len(GUI)} menu buttons")
    if len(sys.argv) > 1:
        sheet(sys.argv[1])
