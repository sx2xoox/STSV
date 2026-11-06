"""
Cosmetics (content/stsv/items/cosmetics.yml): hats and masks on the head, and things that ride on the
player (above the head, on the left shoulder, on the back, as a tail).

Frames
  head      worn in the helmet slot: model 0..16 is the cube around the head (the head itself is about
            1.6..14.4), front = north (z 0), like the masks.
  attached  drawn by an item display that rides the player (core CosmeticModule), item transform NONE:
            the model's (8, 8, 8) sits on the slot's anchor, north (-z) = the player's front, south (+z) =
            behind them, +x = the player's right, 1 unit = 1/16 block (about one skin pixel).
            above     anchor on top of the head: the thing floats above y 8
            shoulder  anchor on top of the left shoulder: a pet stands on y 8, facing north
            back      anchor on the upper back: hugs the plane z 8 and reaches backwards (+z)
            tail      anchor on the lower back: hangs from z 8 backwards
Materials are painted per face from the shared palette (the machines' Mat), so everything matches.
"""

import economy_models  # noqa: F401  (registers the shared surfaces the machine materials may use)
from economy_machines import MModel, Mat, cyl, ring

FEATHER = Mat("feather", "grain", 2)
FEATHER_LIGHT = Mat("feather", "grain", 3)
STRAW = Mat("straw", "weave", 2)
STRAW_LIGHT = Mat("straw", "weave", 3)
RED_CLOTH = Mat("fabric_red", "plain", 2)
BLACK = Mat("rubber", "plain", 1)
BLACK_WEAVE = Mat("rubber", "weave", 2)
GOLD = Mat("gold", "metal", 2)
GOLD_LIGHT = Mat("gold", "metal", 3)
PINK = Mat("pink", "plain", 2)
WHITE = Mat("feather", "plain", 3)
EYE = Mat("rubber", "plain", 0, bevel=None, noise=0)


def pix(m, name, rows, x0, ytop, z0, z1, mats, unit=1.0, rotation=None):
    """Pixel art drawn in the x-y plane (rows top to bottom), each character one unit, z0..z1 deep.
    Runs of the same character in a row become one box; '.' is empty."""
    for r, row in enumerate(rows):
        x = 0
        while x < len(row):
            ch = row[x]
            if ch == ".":
                x += 1
                continue
            e = x
            while e < len(row) and row[e] == ch:
                e += 1
            m.box(name, (x0 + x * unit, ytop - (r + 1) * unit, z0), (x0 + e * unit, ytop - r * unit, z1), mats[ch],
                  rotation=rotation)
            x = e


def mirror(m, name, frm, to, mat, faces=None, **kw):
    """A box and its mirror image across x = 8 (pairs: ears, wings, eyes); east and west swap."""
    m.box(name, frm, to, mat, faces=faces, **kw)
    swap = {"east": "west", "west": "east"}
    flipped = {swap.get(f, f): t for f, t in faces.items()} if faces else None
    m.box(name, (16 - to[0], frm[1], frm[2]), (16 - frm[0], to[1], to[2]), mat, faces=flipped, **kw)


def _display(m, gui_rot, gui_scale, gui_y=0.0):
    m.display = {
        "gui": {"rotation": list(gui_rot), "translation": [0, gui_y, 0], "scale": [gui_scale] * 3},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [gui_scale] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [gui_scale * 0.5] * 3},
        "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    }


HEAD_GUI = (30, 225, 0)      # front 3/4 view (north side)
BACK_GUI = (30, 45, 0)       # the side that shows on the player: south (behind them)


# ================================================================================================
# 머리 (helmet slot)
# ================================================================================================

def straw_hat():
    """밀짚모자: a wide woven brim, a red band and a little daisy."""
    m = MModel("straw_hat")
    m.group("hat")
    cyl(m, "brim", "y", (8, 8), 26, 12, 13, STRAW, cap=STRAW)
    cyl(m, "crown", "y", (8, 8), 14, 12.5, 19, STRAW_LIGHT, cap=STRAW_LIGHT)
    ring(m, "band", (8, 8), 15, 13, 13, 15, RED_CLOTH)
    m.group("daisy")
    # flat on the band's right side
    m.box("petal", (15.5, 14.5, 7.5), (15.9, 15.25, 8.5), WHITE)
    m.box("petal", (15.5, 12.75, 7.5), (15.9, 13.5, 8.5), WHITE)
    m.box("petal", (15.5, 13.5, 6.75), (15.9, 14.5, 7.5), WHITE)
    m.box("petal", (15.5, 13.5, 8.5), (15.9, 14.5, 9.25), WHITE)
    m.box("daisy_eye", (15.5, 13.5, 7.5), (16.0, 14.5, 8.5), Mat("petal_yellow", "plain", 1))
    _display(m, HEAD_GUI, 0.42, 1.5)
    return m


def cat_ears():
    """고양이 귀 머리띠: a black headband with two pointed ears, pink inside."""
    m = MModel("cat_ears")
    m.group("band")
    m.box("band_top", (1.4, 14.4, 7), (14.6, 15.2, 8.5), BLACK)
    mirror(m, "band_side", (0.6, 9, 7), (1.4, 15.2, 8.5), BLACK)
    m.group("ears")
    for (y0, y1, x0, x1) in ((15.2, 16.7, 1.5, 6.5), (16.7, 18.2, 2, 6), (18.2, 19.7, 2.5, 5.5), (19.7, 21.0, 3, 5), (21.0, 21.8, 3.5, 4.5)):
        mirror(m, "ear", (x0, y0, 6.25), (x1, y1, 9), BLACK_WEAVE)
    for (y0, y1, x0, x1) in ((15.5, 17.0, 2.5, 5.5), (17.0, 18.5, 3, 5), (18.5, 19.75, 3.5, 4.5)):
        mirror(m, "ear_inside", (x0, y0, 6.0), (x1, y1, 6.25), PINK, faces={"south": None})
    _display(m, HEAD_GUI, 0.62, -1.5)
    return m


def wizard_hat():
    """마법사 모자: a tall purple cone bending back, a gold band, glowing stars and a moon."""
    m = MModel("wizard_hat")
    purple, purple_light = Mat("aubergine", "plain", 2), Mat("aubergine", "plain", 3)
    m.group("hat")
    cyl(m, "brim", "y", (8, 8), 24, 12, 13, purple, cap=purple)
    for (d, y0, y1, cz) in ((14, 12.5, 16.5, 8), (11, 16.5, 20, 8.5), (8, 20, 23.5, 9.5), (5, 23.5, 26.5, 11), (3, 26.5, 29, 13)):
        cyl(m, "cone", "y", (8, cz), d, y0, y1, purple_light, cap=purple_light)
    m.box("tip", (7.25, 28.5, 14), (8.75, 30, 15.5), purple_light)
    ring(m, "band", (8, 8), 15, 13, 13, 15, GOLD)
    m.group("stars")
    star = Mat("lamp", "plain", 3, glow=True, bevel=None)
    for (x, y, z) in ((4.5, 17.5, 2.75), (10, 21.5, 5.25)):
        m.box("star", (x, y, z - 0.25), (x + 1, y + 1, z), star)
        m.box("star", (x - 0.5, y + 0.25, z - 0.2), (x + 1.5, y + 0.75, z - 0.05), star)
        m.box("star", (x + 0.25, y - 0.5, z - 0.2), (x + 0.75, y + 1.5, z - 0.05), star)
    # a crescent moon on the front of the cone
    for (x0, y0, x1, y1) in ((10, 13.5, 11, 16), (9.25, 13, 10, 13.75), (9.25, 15.75, 10, 16.5)):
        m.box("moon", (x0, y0, 0.75), (x1, y1, 1.0), star)
    _display(m, HEAD_GUI, 0.42, -3.5)
    return m


def crown():
    """왕관: a gold crown with three points a side, a red velvet cap and glowing jewels."""
    m = MModel("crown")
    m.group("crown")
    m.box("velvet", (1.6, 14, 1.6), (14.4, 16.5, 14.4), Mat("crimson", "weave", 2))
    m.box("wall_n", (0.6, 12.5, 0.6), (15.4, 15.5, 1.6), GOLD)
    m.box("wall_s", (0.6, 12.5, 14.4), (15.4, 15.5, 15.4), GOLD)
    m.box("wall_w", (0.6, 12.5, 1.6), (1.6, 15.5, 14.4), GOLD)
    m.box("wall_e", (14.4, 12.5, 1.6), (15.4, 15.5, 14.4), GOLD)
    m.box("rim", (0.4, 12, 0.4), (15.6, 12.75, 15.6), GOLD_LIGHT)
    # three points on the front and back, one in the middle of each side (the corners belong to the front/back)
    for x0 in (1.0, 6.5, 12.0):
        for (y0, y1, a, b) in ((15.5, 17, 0, 3), (17, 18.25, 0.75, 2.25)):
            m.box("point", (x0 + a, y0, 0.6), (x0 + b, y1, 1.6), GOLD)
            m.box("point", (x0 + a, y0, 14.4), (x0 + b, y1, 15.4), GOLD)
            if x0 == 6.5:
                m.box("point", (0.6, y0, x0 + a), (1.6, y1, x0 + b), GOLD)
                m.box("point", (14.4, y0, x0 + a), (15.4, y1, x0 + b), GOLD)
    m.group("jewels")
    ruby, sapphire = Mat("ruby", "plain", 2, glow=True, noise=0), Mat("sapphire", "plain", 2, glow=True, noise=0)
    m.box("ruby", (6.75, 13, 0.25), (9.25, 14.75, 0.6), ruby, faces={"south": None})
    m.box("ruby", (6.75, 13, 15.4), (9.25, 14.75, 15.75), ruby, faces={"north": None})
    m.box("sapphire", (0.25, 13, 6.75), (0.6, 14.75, 9.25), sapphire, faces={"east": None})
    m.box("sapphire", (15.4, 13, 6.75), (15.75, 14.75, 9.25), sapphire, faces={"west": None})
    m.box("cross", (7.5, 16.5, 7.5), (8.5, 19.5, 8.5), GOLD_LIGHT)
    m.box("cross", (6.5, 17.75, 7.6), (9.5, 18.75, 8.4), GOLD_LIGHT)
    _display(m, HEAD_GUI, 0.62, -1.0)
    return m


def gat():
    """갓: the Korean horsehair hat, a wide thin brim and a tall crown, amber bead strings to the chin."""
    m = MModel("gat")
    m.group("hat")
    # the gat sits high: the brim just above the head, the narrow crown on top
    cyl(m, "brim", "y", (8, 8), 28, 14.5, 15, BLACK_WEAVE, cap=BLACK_WEAVE)
    cyl(m, "crown", "y", (8, 8), 9, 15, 22, BLACK, cap=BLACK)
    ring(m, "band", (8, 8), 10, 8, 15, 16.25, Mat("rubber", "plain", 2))
    m.group("strings")
    bead = Mat("topaz", "plain", 2, noise=0)
    for x0 in (0.4, 15.0):
        m.box("string", (x0, 2.2, 7.7), (x0 + 0.6, 14.5, 8.3), Mat("rubber", "plain", 2))
        for y in (3.0, 5.0, 7.0, 9.0, 11.0):
            m.box("bead", (x0 - 0.2, y, 7.5), (x0 + 0.8, y + 1, 8.5), bead)
        m.box("string", (x0, 1.6, 1.4), (x0 + 0.6, 2.2, 8.3), Mat("rubber", "plain", 2))
    m.box("tie", (1.0, 1.6, 1.4), (15.0, 2.2, 2.0), Mat("rubber", "plain", 2))
    _display(m, HEAD_GUI, 0.4, 0.0)
    return m


def frog_hat():
    """개구리 모자: a green cap with two big eyes on top and pink cheeks."""
    m = MModel("frog_hat")
    green, green_light = Mat("leaf", "plain", 2), Mat("leaf", "plain", 3)
    m.group("cap")
    m.box("cap", (0.6, 12, 0.6), (15.4, 15.5, 15.4), green)
    m.box("cap_top", (2, 15.5, 2), (14, 16.5, 14), green_light)
    m.group("eyes")
    mirror(m, "socket", (1.5, 15.5, 1.5), (6.5, 20, 6.5), green)
    mirror(m, "eyeball", (2.5, 16.5, 1.2), (5.5, 19.5, 1.5), WHITE, faces={"south": None})
    mirror(m, "pupil", (3.5, 17, 0.95), (4.75, 19, 1.2), EYE, faces={"south": None})
    m.group("face")
    mirror(m, "cheek", (1.5, 12.75, 0.35), (3.5, 14, 0.6), PINK, faces={"south": None})
    m.box("mouth", (5, 13, 0.35), (11, 13.5, 0.6), Mat("leaf", "plain", 0), faces={"south": None})
    _display(m, HEAD_GUI, 0.6, -2.0)
    return m


# ================================================================================================
# 머리 위 (floats above the head)
# ================================================================================================

def halo():
    """천사 고리: a glowing gold ring."""
    m = MModel("halo")
    ring(m, "halo", (8, 8), 10, 7, 10.5, 11.5, Mat("lamp", "plain", 3, glow=True))
    _display(m, (60, 0, 0), 1.2, -6)
    return m


def rain_cloud():
    """먹구름: a little grey cloud with drops falling on its owner."""
    m = MModel("rain_cloud")
    grey, grey_dark, grey_light = Mat("stone", "plain", 2), Mat("stone", "plain", 1), Mat("snow", "plain", 1)
    m.group("cloud")
    m.box("puff", (4, 13, 5), (12, 16, 11), grey)
    m.box("puff", (2, 13.5, 6), (4, 15.5, 10), grey)
    m.box("puff", (12, 13.5, 6), (14, 15.5, 10), grey)
    m.box("puff", (5, 16, 6), (9, 18, 10), grey_light)
    m.box("puff", (9, 16, 6.5), (11.5, 17.5, 9.5), grey_light)
    m.box("belly", (4.5, 12.5, 5.5), (11.5, 13, 10.5), grey_dark)
    m.group("rain")
    drop = Mat("water", "plain", 3, noise=0)
    for (x, y, z) in ((5, 10.5, 6.5), (8, 11.0, 8.5), (10.5, 10, 7), (6.5, 9.25, 9), (9.5, 9.0, 6)):
        m.box("drop", (x, y, z), (x + 0.5, y + 1.5, z + 0.5), drop)
    _display(m, (30, 225, 0), 0.95, -9)
    return m


def heart():
    """하트: a floating pink heart."""
    m = MModel("heart")
    pink, light, dark = Mat("pink", "plain", 2), Mat("pink", "plain", 3), Mat("pink", "plain", 1)
    pix(m, "heart", [".LP.PP.",
                     "LPPPPPP",
                     "PPPPPPD",
                     ".PPPPD.",
                     "..PPD..",
                     "...D..."], 3.625, 18, 6.5, 9.5, {"P": pink, "L": light, "D": dark}, unit=1.25)
    _display(m, (0, 200, 0), 1.25, -7)
    return m


def sprout():
    """새싹: a sprout growing out of the head."""
    m = MModel("sprout")
    stem, leaf, leaf_light = Mat("sprout", "plain", 1), Mat("sprout", "plain", 2), Mat("sprout", "plain", 3)
    m.box("stem", (7.5, 8, 7.5), (8.5, 12, 8.5), stem)
    m.box("leaf", (4.5, 11.5, 7.5), (7.5, 12.5, 8.5), leaf)
    m.box("leaf", (3.5, 12.5, 7.5), (6, 13.5, 8.5), leaf_light)
    m.box("leaf", (8.5, 12, 7.5), (11.5, 13, 8.5), leaf)
    m.box("leaf", (10, 13, 7.5), (12.5, 14, 8.5), leaf_light)
    _display(m, (20, 200, 0), 1.6, -10)
    return m


# ================================================================================================
# 어깨 (a pet on the left shoulder, standing on y 8, facing north)
# ================================================================================================

def chick():
    """병아리."""
    m = MModel("chick")
    yellow, yellow_light = Mat("petal_yellow", "plain", 1), Mat("petal_yellow", "plain", 2)
    beak = Mat("fox", "plain", 3)
    m.box("body", (6, 8, 6.5), (10, 11.5, 10.5), yellow)
    m.box("head", (6.5, 11.5, 6), (9.5, 14, 9), yellow_light)
    m.box("tuft", (7.5, 14, 7), (8.5, 14.75, 8), yellow_light)
    m.box("beak", (7.5, 12, 5.4), (8.5, 12.75, 6), beak, faces={"south": None})
    mirror(m, "eye", (6.75, 12.75, 5.9), (7.25, 13.25, 6), EYE, faces={"south": None})
    mirror(m, "wing", (5.6, 9, 7.5), (6, 10.75, 9.75), yellow, faces={"east": None})
    m.box("tail", (7.25, 10.25, 10.5), (8.75, 11.25, 11.25), yellow, faces={"north": None})
    mirror(m, "foot", (6.75, 7.5, 6.75), (7.5, 8, 7.75), beak)
    _display(m, (20, 210, 0), 1.6, -8)
    return m


def shoulder_cat():
    """고양이: an orange tabby sitting on the shoulder, tail curled round."""
    m = MModel("shoulder_cat")
    fur, fur_light, fur_dark = Mat("fox", "speckle", 3), Mat("fox", "plain", 4), Mat("fox", "plain", 2)
    m.box("body", (6, 8, 7.5), (10, 12, 11), fur)
    m.box("chest", (6.5, 8.5, 7.25), (9.5, 11.5, 7.5), fur_light, faces={"south": None})
    m.box("head", (5.75, 12, 6.25), (10.25, 15.5, 9.75), fur)
    mirror(m, "ear", (6, 15.5, 7.25), (7.25, 16.75, 8.25), fur_dark)
    mirror(m, "eye", (6.75, 13.5, 6.0), (7.5, 14.25, 6.25), Mat("leaf", "plain", 3, noise=0), faces={"south": None})
    m.box("nose", (7.75, 12.75, 6.0), (8.25, 13.25, 6.25), PINK, faces={"south": None})
    mirror(m, "paw", (6.25, 8, 6.5), (7.25, 9, 7.5), fur_light)
    m.box("tail", (10, 8, 9.5), (11.25, 9.25, 11.5), fur_dark)
    m.box("tail", (8.5, 8, 11.5), (11, 9, 12.5), fur_dark)
    m.box("stripe", (5.9, 11, 9), (10.1, 11.5, 9.5), fur_dark)
    _display(m, (20, 210, 0), 1.45, -8)
    return m


def slime():
    """슬라임: a bouncy green cube with a face."""
    m = MModel("slime")
    body, dark = Mat("slime", "plain", 2), Mat("slime", "plain", 0)
    m.box("body", (5.5, 8, 5.5), (10.5, 13, 10.5), body)
    mirror(m, "eye", (6.5, 10.5, 5.3), (7.5, 11.75, 5.5), dark, faces={"south": None})
    m.box("mouth", (7.5, 9.25, 5.3), (8.5, 9.75, 5.5), dark, faces={"south": None})
    _display(m, (30, 225, 0), 1.6, -8)
    return m


# ================================================================================================
# 등 (hugs the plane z 8 on the upper back, reaches backwards)
# ================================================================================================

def _wing(m, name, feathers, arm, edge, swept=-22.5):
    """One wing on the player's right (+x), swept backwards; the left one is its mirror."""
    for side in (1, -1):
        def X(x0, x1):
            return (x0, x1) if side == 1 else (16 - x1, 16 - x0)
        rot = ("y", swept * side, (8 + side * 1.5, 8, 9))
        for (x0, x1, y0, y1, z0, z1, mat) in arm + feathers + edge:
            a, b = X(x0, x1)
            m.box(name, (a, y0, z0), (b, y1, z1), mat, rotation=rot)


def angel_wings():
    """천사 날개: white feathered wings in three tiers (coverts, secondaries, long primaries), swept back."""
    m = MModel("angel_wings")
    edge, covert = Mat("feather", "plain", 3), Mat("feather", "grain", 3)
    shade_a, shade_b = Mat("feather", "grain", 2), Mat("feather", "grain", 1)
    arm = [(9.5, 13.5, 9.5, 11.5, 9, 11, edge), (13, 18.5, 10.5, 13, 9, 11, edge), (18, 23, 11.5, 14.5, 9, 11, edge)]
    coverts = [(10, 18, 8.5, 11, 9.4, 10.9, covert), (17.5, 22.5, 9, 12, 9.4, 10.9, covert)]
    secondaries = [(10.5, 12, 6, 9, 9.2, 10.6, shade_a), (12, 13.5, 5.5, 9, 9.2, 10.6, shade_b),
                   (13.5, 15, 5, 9, 9.2, 10.6, shade_a), (15, 16.5, 4, 9, 9.2, 10.6, shade_b)]
    primaries = [(16.5, 18, 3, 9.5, 9.1, 10.4, shade_a), (18, 19.5, 2, 9.5, 9.1, 10.4, shade_b),
                 (19.5, 21, 1, 10, 9.1, 10.4, shade_a), (21, 22.5, 0.5, 10.5, 9.1, 10.4, shade_b),
                 (22.5, 23.5, 2, 11, 9.1, 10.4, shade_a)]
    _wing(m, "wing", secondaries + primaries, arm, coverts)
    _display(m, BACK_GUI, 0.42, -1)
    return m


def demon_wings():
    """악마 날개: black bones and a dark red membrane with a scalloped edge."""
    m = MModel("demon_wings")
    bone, skin = Mat("obsidian", "metal", 2), Mat("crimson", "plain", 2)
    arm = [(9.5, 14, 9, 11, 9, 10.5, bone), (13.5, 19.5, 10.5, 12.5, 9, 10.5, bone), (19, 24, 12, 14.5, 9, 10.5, bone),
           (23, 24.5, 14.5, 16, 9, 10.5, bone)]
    fingers = [(22.5, 23.5, 2, 12, 9.1, 10.4, bone), (17.5, 18.5, 4, 10.5, 9.1, 10.4, bone), (13, 14, 6, 9, 9.1, 10.4, bone)]
    membrane = [(18.5, 22.5, 4, 12, 9.4, 10.1, skin), (14, 17.5, 6, 10.5, 9.4, 10.1, skin), (10.5, 13, 7, 9, 9.4, 10.1, skin),
                (19.5, 21.5, 3, 4, 9.4, 10.1, skin), (15, 16.5, 5, 6, 9.4, 10.1, skin)]
    _wing(m, "wing", membrane, arm, fingers)
    _display(m, BACK_GUI, 0.4, -1)
    return m


def butterfly_wings():
    """나비 날개: blue wings with black edges and white spots, softly glowing."""
    m = MModel("butterfly_wings")
    k, b, c, w = BLACK, Mat("sapphire", "plain", 2, glow=True), Mat("glow_cyan", "plain", 2, glow=True), WHITE
    upper = ["...kkkkk",
             "..kbbbbwk",
             ".kbbccbbk",
             "kbbcccbbk",
             "kbbccbbk.",
             "kbbbbbk..",
             ".kkbbk...",
             "...kk...."]
    lower = ["kbbbk.",
             "kbcbbk",
             "kbcbk.",
             ".kbk..",
             "..k..."]
    for side in (1, -1):
        rows_u = upper if side == 1 else [r[::-1] for r in upper]
        rows_l = lower if side == 1 else [r[::-1] for r in lower]
        rot = ("y", -22.5 * side, (8 + side * 1, 8, 9))
        u = 1.35
        x_u = 9 if side == 1 else 16 - 9 - len(upper[0]) * u
        x_l = 9 if side == 1 else 16 - 9 - len(lower[0]) * u
        mats = {"k": k, "b": b, "c": c, "w": w}
        pix(m, "wing", rows_u, x_u, 20, 9.25, 10, mats, unit=u, rotation=rot)
        pix(m, "wing", rows_l, x_l, 9.5, 9.25, 10, mats, unit=u, rotation=rot)
    _display(m, BACK_GUI, 0.55, -1)
    return m


def bear_backpack():
    """곰 인형 가방: a teddy bear clinging to the back, looking behind its owner."""
    m = MModel("bear_backpack")
    fur, fur_light, fur_dark = Mat("plush", "speckle", 2), Mat("plush", "plain", 3), Mat("plush", "plain", 1)
    cream = Mat("cream", "plain", 2)
    m.group("bear")
    m.box("body", (4, 1, 8), (12, 9, 12), fur)
    m.box("belly", (5.5, 2, 12), (10.5, 6.5, 12.3), fur_light, faces={"north": None})
    m.box("head", (4.5, 8.5, 8.5), (11.5, 14.5, 13), fur)
    mirror(m, "ear", (4, 13.5, 9.5), (6, 15.5, 11.5), fur_dark)
    mirror(m, "ear_inside", (4.4, 13.9, 11.5), (5.6, 15.1, 11.75), PINK, faces={"north": None})
    m.box("muzzle", (6.5, 9.25, 13), (9.5, 11.5, 14), cream, faces={"north": None})
    m.box("nose", (7.5, 10.75, 14), (8.5, 11.5, 14.4), EYE, faces={"north": None})
    mirror(m, "eye", (5.5, 12, 13), (6.5, 13, 13.3), EYE, faces={"north": None})
    mirror(m, "arm", (2.5, 7, 8.5), (4, 10.5, 11), fur_dark)
    mirror(m, "leg", (4.5, 0, 9), (7, 2, 11.5), fur_dark)
    _display(m, BACK_GUI, 0.75, -1)
    return m


def bunny_backpack():
    """토끼 인형 가방: a white bunny with long ears and a pink nose."""
    m = MModel("bunny_backpack")
    fur, fur_shade = Mat("feather", "speckle", 3), Mat("feather", "plain", 2)
    m.group("bunny")
    m.box("body", (4.5, 1, 8), (11.5, 8.5, 11.5), fur)
    m.box("head", (5, 8, 8.5), (11, 13, 12.5), fur)
    mirror(m, "ear", (5.5, 13, 9.5), (7, 20, 11), fur_shade)
    mirror(m, "ear_inside", (5.75, 13.5, 11), (6.75, 19.5, 11.25), PINK, faces={"north": None})
    mirror(m, "eye", (6, 10.5, 12.5), (7, 11.5, 12.75), EYE, faces={"north": None})
    m.box("nose", (7.5, 9.75, 12.5), (8.5, 10.5, 12.75), PINK, faces={"north": None})
    mirror(m, "cheek", (5.5, 9, 12.5), (6.5, 9.75, 12.7), Mat("pink", "plain", 3), faces={"north": None})
    mirror(m, "arm", (3, 5.5, 8.5), (4.5, 8.5, 10.5), fur_shade)
    mirror(m, "foot", (5, 0, 9), (7.5, 1.5, 12), fur_shade)
    _display(m, BACK_GUI, 0.65, -1.5)
    return m


def school_bag():
    """책가방: a navy school backpack with a front pocket, a handle and gold buckles."""
    m = MModel("school_bag")
    blue, blue_dark, blue_light = Mat("denim", "plain", 1), Mat("denim", "plain", 0), Mat("denim", "plain", 2)
    m.group("bag")
    m.box("body", (3, 1, 8), (13, 11, 12), blue)
    m.box("flap", (3, 11, 8), (13, 12.5, 12.5), blue_dark)
    m.box("flap_front", (3, 8, 12), (13, 11, 12.5), blue_dark, faces={"north": None})
    m.box("pocket", (4.5, 2, 12), (11.5, 6.5, 13.25), blue_light, faces={"north": None})
    m.box("pocket_lid", (4.5, 6.5, 12), (11.5, 7.25, 13.5), blue_dark, faces={"north": None})
    mirror(m, "buckle", (4.5, 8.5, 12.5), (5.5, 9.5, 12.75), GOLD, faces={"north": None})
    m.box("handle", (6.5, 12.5, 9.75), (9.5, 13.25, 10.75), Mat("leather", "plain", 2))
    mirror(m, "strap", (3.5, 1.5, 7.5), (5, 11, 8), Mat("leather", "plain", 2), faces={"south": None})
    _display(m, BACK_GUI, 0.75, -0.5)
    return m


# ================================================================================================
# 꼬리 (lower back, hanging backwards)
# ================================================================================================

def fox_tail():
    """여우 꼬리: a bushy orange tail that dips and sweeps up, a white tip."""
    m = MModel("fox_tail")
    fur, fur_light, tip = Mat("fox", "speckle", 3), Mat("fox", "speckle", 4), Mat("feather", "speckle", 3)
    m.box("tail", (6.5, 5, 8), (9.5, 8, 10.75), fur)
    m.box("tail", (5.75, 2.5, 10.5), (10.25, 7, 13.5), fur_light)
    m.box("tail", (5.25, 2, 13.25), (10.75, 7.5, 16.5), fur)
    m.box("tail", (5.75, 3.5, 16.25), (10.25, 9, 18.75), fur_light)
    m.box("tip", (6.25, 6, 18.5), (9.75, 11, 20.5), tip)
    m.box("tip", (6.75, 8, 20.25), (9.25, 12, 21.5), tip)
    _display(m, (30, 135, 0), 0.7, -1)
    return m


def cat_tail():
    """고양이 꼬리: a thin black tail curving up at the end."""
    m = MModel("cat_tail")
    fur = Mat("rubber", "plain", 2)
    # alternate segments a hair thinner, so the overlaps never share a face
    for k, ((y0, z0), (y1, z1)) in enumerate((((5.5, 8), (7, 11)), ((3.5, 10.5), (5.5, 12)), ((2.5, 11.5), (4, 14.5)),
                                             ((3.5, 14), (6, 15.5)), ((5.5, 15), (9, 16.5)), ((9, 15.25), (10.5, 16.25)))):
        w = 0.75 if k % 2 == 0 else 0.6
        m.box("tail", (8 - w, y0 + (0 if k % 2 == 0 else 0.1), z0 + (0 if k % 2 == 0 else 0.1)),
              (8 + w, y1 - (0 if k % 2 == 0 else 0.1), z1 - (0 if k % 2 == 0 else 0.1)), fur)
    _display(m, (30, 135, 0), 0.8, 0)
    return m


# slot of every cosmetic model (the item yml says the same; the preview places them by it)
SLOTS = {
    "straw_hat": "head", "cat_ears": "head", "wizard_hat": "head", "crown": "head", "gat": "head", "frog_hat": "head",
    "halo": "above", "rain_cloud": "above", "heart": "above", "sprout": "above",
    "chick": "shoulder", "shoulder_cat": "shoulder", "slime": "shoulder",
    "angel_wings": "back", "demon_wings": "back", "butterfly_wings": "back",
    "bear_backpack": "back", "bunny_backpack": "back", "school_bag": "back",
    "fox_tail": "tail", "cat_tail": "tail",
}

ALL = {f.__name__: f for f in (straw_hat, cat_ears, wizard_hat, crown, gat, frog_hat,
                               halo, rain_cloud, heart, sprout,
                               chick, shoulder_cat, slime,
                               angel_wings, demon_wings, butterfly_wings, bear_backpack, bunny_backpack, school_bag,
                               fox_tail, cat_tail)}
