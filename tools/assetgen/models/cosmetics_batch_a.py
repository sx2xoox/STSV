"""
Cosmetics, batch A (content/stsv/items/cosmetics_batch_a.yml): four more things above the head, five more
friends on the shoulder and two more for the back, so those slots hold as many cosmetics as the others.
Same frames, materials and helpers as models/cosmetics.py:

  above     anchor on top of the head: the thing floats above y 8
  shoulder  anchor on top of the shoulder: a pet stands on y 8, facing north (the player's front)
  back      anchor on the upper back: hugs the plane z 8 and reaches backwards (+z)

1 unit = 1/16 block (about one skin pixel); north (-z) = the player's front, +x = the player's right.
"""

import math

from economy_machines import MModel, Mat, cyl, ring
from models.cosmetics import BACK_GUI, BLACK, EYE, GOLD, PINK, WHITE, _display, mirror, pix

ABOVE_GUI = (30, 225, 0)
SHOULDER_GUI = (20, 210, 0)


# ================================================================================================
# 머리 위 (floats above the head)
# ================================================================================================

def little_sun():
    """꼬마 해님: a round yellow sun with orange rays, sleepy-happy eyes and rosy cheeks, facing front."""
    m = MModel("little_sun")
    yellow, light, orange = Mat("petal_yellow", "plain", 1), Mat("petal_yellow", "plain", 2), Mat("topaz", "plain", 2)
    mats = {"Y": yellow, "L": light, "O": orange, "K": EYE, "P": PINK}
    rows = ["......O......",
            "..O...O...O..",
            "....LLYYY....",
            "...LYYYYYY...",
            "..LYYYYYYYY..",
            "O.YYKYYYKYY.O",
            "..YPYYYYYPY..",
            "..YYKYYYKYY..",
            "...YYKKKYY...",
            "....YYYYY....",
            "..O...O...O..",
            "......O......"]
    m.group("sun")
    pix(m, "sun", rows, 1.5, 22.5, 7.25, 8.75, mats)
    # a thinner back plate so the sun is round from behind too (no face there)
    pix(m, "back", [r.replace("K", "Y").replace("P", "Y") for r in rows[2:10]], 1.5, 20.5, 8.75, 9.25,
        {"Y": yellow, "L": yellow, "O": orange, "K": yellow, "P": yellow})
    _display(m, ABOVE_GUI, 1.15, -8)
    return m


def exclaim_bubble():
    """느낌표 말풍선: a white speech bubble with a big red "!" (something just occurred to its owner)."""
    m = MModel("exclaim_bubble")
    white, shade, red = Mat("feather", "plain", 3), Mat("snow", "plain", 1), Mat("ruby", "plain", 2)
    rows = [".WWWWWWW.",
            "WWWWWWWWW",
            "WWWWWWWWW",
            "WWWWWWWWW",
            "WWWWWWWWW",
            "WWWWWWWWW",
            "WWWWWWWWW",
            ".SSSSSSS.",
            ".SS......",
            "SS......."]
    m.group("bubble")
    pix(m, "bubble", rows, 3.5, 23, 7.25, 8.75, {"W": white, "S": shade})
    # the "!" stands a little proud of the bubble on both faces
    for z0, z1 in ((6.9, 7.25), (8.75, 9.1)):
        m.box("mark", (7.4, 18.25, z0), (8.6, 22.0, z1), red)
        m.box("dot", (7.4, 16.25, z0), (8.6, 17.4, z1), red)
    _display(m, ABOVE_GUI, 1.1, -8)
    return m


def rainbow_arc():
    """무지개: a little seven-colour rainbow arching over the head, a fluffy cloud at each foot."""
    m = MModel("rainbow_arc")
    bands = [Mat("ruby", "plain", 2), Mat("topaz", "plain", 2), Mat("petal_yellow", "plain", 1), Mat("slime", "plain", 2),
             Mat("sapphire", "plain", 2), Mat("indigo", "plain", 3), Mat("magic", "plain", 1)]
    u = 0.75
    cx, r_out = 8.5, 8.5            # in grid cells: 17 wide, half a circle
    rows = []
    for j in range(9):
        row = ""
        for i in range(17):
            d = math.hypot(i + 0.5 - cx, (9 - j) - 0.5)
            k = int(r_out - d) if d <= r_out else -1
            row += str(k) if 0 <= k < 7 else "."
        rows.append(row)
    m.group("rainbow")
    pix(m, "rainbow", rows, 8 - 17 * u / 2, 11.0 + 9 * u, 7.5, 8.5, {str(i): b for i, b in enumerate(bands)}, unit=u)
    m.group("clouds")
    cloud, cloud_light = Mat("snow", "plain", 1), Mat("snow", "plain", 2)
    for x in (1.25, 12.0):
        m.box("cloud", (x, 10.5, 6.5), (x + 3.25, 12.5, 9.5), cloud)
        m.box("cloud", (x + 0.5, 12.5, 7.0), (x + 2.75, 13.75, 9.0), cloud_light)
        m.box("cloud", (x - 0.75, 10.75, 7.25), (x + 4.0, 12.0, 8.75), cloud)
    _display(m, ABOVE_GUI, 1.0, -8)
    return m


def _star(m, name, cx, cy, z0, z1, mat, u=0.5):
    """A five-pointed star (pixel art) centred on (cx, cy), facing north and south."""
    rows = ["..X..",
            "..X..",
            "XXXXX",
            ".XXX.",
            "X...X"]
    pix(m, name, rows, cx - 2.5 * u, cy + 2.5 * u, z0, z1, {"X": mat}, unit=u)


def star_ring():
    """별 고리: a violet ring floating flat over the head with three glowing gold stars riding on it."""
    m = MModel("star_ring")
    band, band_light = Mat("magic", "plain", 1), Mat("magic", "plain", 3)
    star = Mat("lamp", "plain", 2, glow=True)
    m.group("ring")
    ring(m, "ring", (8, 8), 12, 10, 10.5, 11.25, band)
    ring(m, "ring_top", (8, 8), 11, 10, 11.25, 11.5, band_light)
    m.group("stars")
    # front left, front right and behind, standing on the ring
    for (x, z) in ((3.25, 4.5), (12.75, 4.5), (8.0, 13.25)):
        _star(m, "star", x, 13.0, z - 0.4, z + 0.4, star, u=0.75)
    # a few sparkles between them
    spark = Mat("feather", "plain", 3, glow=True)
    for (x, y, z) in ((8.0, 12.5, 2.75), (2.5, 12.0, 10.5), (13.5, 12.25, 10.0)):
        m.box("sparkle", (x - 0.25, y - 0.25, z - 0.25), (x + 0.25, y + 0.25, z + 0.25), spark)
    _display(m, (45, 0, 0), 1.15, -6)
    return m


# ================================================================================================
# 어깨 (a friend standing on the shoulder, on y 8, facing north)
# ================================================================================================

def hamster():
    """햄스터: a round golden hamster with a white belly and puffed cheeks, nibbling a sunflower seed."""
    m = MModel("hamster")
    fur, fur_light, belly = Mat("topaz", "speckle", 2), Mat("topaz", "plain", 3), Mat("cream", "plain", 3)
    seed, seed_stripe = Mat("rubber", "plain", 3), Mat("cream", "plain", 2)
    m.box("body", (5.75, 8, 6.75), (10.25, 11.5, 11), fur)
    m.box("belly", (6.5, 8.25, 6.5), (9.5, 10.75, 6.75), belly, faces={"south": None})
    m.box("head", (6.0, 11.0, 6.25), (10.0, 14.0, 9.75), fur_light)
    mirror(m, "cheek", (5.5, 11.25, 6.5), (6.25, 12.75, 8.5), belly)
    mirror(m, "ear", (6.25, 14.0, 7.75), (7.25, 14.75, 8.5), PINK)
    mirror(m, "eye", (6.9, 12.75, 6.05), (7.6, 13.5, 6.25), EYE, faces={"south": None})
    m.box("nose", (7.75, 12.0, 6.05), (8.25, 12.5, 6.25), PINK, faces={"south": None})
    mirror(m, "paw", (6.4, 10.1, 5.75), (7.0, 10.85, 6.5), PINK)
    # the seed held up to its mouth
    m.box("seed", (7.0, 10.1, 5.5), (9.0, 10.85, 6.25), seed)
    m.box("seed_stripe", (7.0, 10.35, 5.4), (9.0, 10.6, 5.5), seed_stripe, faces={"south": None})
    mirror(m, "foot", (6.5, 7.75, 6.5), (7.25, 8.25, 7.25), PINK)
    m.box("tail", (7.75, 8.75, 11.0), (8.25, 9.25, 11.5), PINK)
    _display(m, SHOULDER_GUI, 1.6, -8)
    return m


def squirrel():
    """다람쥐: a striped chipmunk sitting up with an acorn, its big bushy tail curled up behind."""
    m = MModel("squirrel")
    fur, fur_light, stripe = Mat("fox", "speckle", 2), Mat("fox", "plain", 4), Mat("bark", "plain", 1)
    belly, acorn, cap = Mat("cream", "plain", 3), Mat("wood", "plain", 2), Mat("wood_dark", "plain", 2)
    m.box("body", (6.5, 8, 7.25), (9.5, 11.5, 10.25), fur)
    m.box("belly", (7.0, 8.25, 7.0), (9.0, 11.0, 7.25), belly, faces={"south": None})
    m.box("head", (6.25, 11.25, 6.5), (9.75, 14.25, 9.5), fur)
    m.box("muzzle", (7.25, 11.5, 6.25), (8.75, 12.5, 6.5), belly, faces={"south": None})
    m.box("nose", (7.75, 12.0, 6.05), (8.25, 12.5, 6.25), EYE, faces={"south": None})
    mirror(m, "eye", (6.75, 12.75, 6.3), (7.4, 13.5, 6.5), EYE, faces={"south": None})
    mirror(m, "ear", (6.5, 14.25, 7.75), (7.25, 15.25, 8.5), fur_light)
    # the 다람쥐 stripes: down the back and over the head
    for x in (7.0, 8.5):
        m.box("stripe", (x, 8.5, 10.25), (x + 0.5, 11.25, 10.35), stripe, faces={"north": None})
    m.box("stripe", (7.75, 14.25, 7.0), (8.25, 14.35, 9.5), stripe, faces={"down": None})
    # the acorn in its paws
    m.box("acorn", (7.25, 9.75, 6.0), (8.75, 11.0, 7.0), acorn)
    m.box("acorn_cap", (7.0, 11.0, 5.75), (9.0, 11.5, 7.25), cap)
    mirror(m, "paw", (6.75, 10.25, 6.5), (7.25, 11.0, 7.0), fur_light)
    # the tail: up behind the back and curling over
    m.box("tail", (6.75, 8.0, 10.25), (9.25, 10.5, 12.0), fur)
    m.box("tail", (6.5, 10.5, 11.0), (9.5, 14.5, 13.0), fur_light)
    m.box("tail", (6.75, 14.5, 10.0), (9.25, 16.5, 12.5), fur)
    m.box("tail", (7.0, 15.5, 9.0), (9.0, 17.0, 10.0), fur_light)
    mirror(m, "foot", (6.25, 7.75, 6.5), (7.25, 8.25, 7.5), fur_light)
    _display(m, SHOULDER_GUI, 1.4, -9)
    return m


def parrot():
    """앵무새: a bright macaw: red head, green body, blue and yellow wings, a long tail hanging behind."""
    m = MModel("parrot")
    red, red_light = Mat("tomato", "plain", 2), Mat("tomato", "plain", 3)
    green, blue, yellow = Mat("slime", "plain", 1), Mat("sapphire", "plain", 2), Mat("petal_yellow", "plain", 1)
    beak, beak_dark, face = Mat("cream", "plain", 2), Mat("rubber", "plain", 3), Mat("feather", "plain", 3)
    m.box("body", (6.5, 8.5, 7.0), (9.5, 12.5, 10.0), green)
    m.box("chest", (6.75, 9.0, 6.75), (9.25, 12.0, 7.0), red, faces={"south": None})
    m.box("head", (6.5, 12.5, 6.5), (9.5, 15.25, 9.25), red_light)
    mirror(m, "face", (6.3, 13.25, 6.75), (6.5, 14.5, 8.25), face, faces={"east": None})
    mirror(m, "eye", (6.2, 13.75, 7.25), (6.3, 14.25, 7.75), EYE, faces={"east": None})
    m.box("beak", (7.25, 13.0, 5.5), (8.75, 14.75, 6.5), beak, faces={"south": None})
    m.box("beak_tip", (7.5, 12.25, 5.5), (8.5, 13.0, 6.25), beak_dark)
    # wings folded along the sides: yellow coverts, blue flight feathers
    mirror(m, "wing", (6.1, 9.0, 7.25), (6.5, 12.25, 9.75), yellow)
    mirror(m, "wing", (6.0, 8.25, 8.0), (6.5, 8.9, 11.25), blue)
    # the long tail: down behind the shoulder, blue then red at the tip
    m.box("tail", (7.25, 6.5, 10.0), (8.75, 9.0, 10.75), blue)
    m.box("tail", (7.5, 3.5, 10.5), (8.5, 6.5, 11.25), blue)
    m.box("tail", (7.5, 1.5, 10.75), (8.5, 3.5, 11.5), red)
    mirror(m, "foot", (6.75, 7.75, 7.0), (7.75, 8.5, 8.25), beak_dark)
    _display(m, SHOULDER_GUI, 1.45, -7)
    return m


def hedgehog():
    """고슴도치: a round little hedgehog: spiky brown back, cream face with a black button nose, an apple on its spines."""
    m = MModel("hedgehog")
    spines, spines_tip, spines_dark = Mat("bark", "speckle", 2), Mat("cream", "plain", 1), Mat("bark", "plain", 1)
    face, apple, leaf = Mat("cream", "plain", 3), Mat("tomato", "plain", 2), Mat("leaf", "plain", 3)
    m.box("body", (5.75, 8, 6.75), (10.25, 11.75, 11.25), spines)
    m.box("body_top", (6.25, 11.75, 7.25), (9.75, 12.5, 10.75), spines)
    m.box("face", (6.5, 8, 6.0), (9.5, 11.0, 6.75), face)
    m.box("snout", (7.25, 8.75, 5.25), (8.75, 10.0, 6.0), face)
    m.box("nose", (7.6, 9.25, 5.0), (8.4, 10.0, 5.25), EYE, faces={"south": None})
    mirror(m, "eye", (6.9, 10.0, 5.95), (7.5, 10.6, 6.0), EYE, faces={"south": None})
    mirror(m, "ear", (6.25, 11.0, 6.4), (7.0, 12.0, 6.9), PINK)
    # spines: little pale-tipped tufts poking out of the top, the back and both sides (never flush with a face)
    spikes = [((6.6, 12.3, 7.6), "up"), ((8.35, 12.3, 8.1), "up"), ((7.1, 12.3, 9.6), "up"), ((8.85, 12.3, 10.1), "up"),
              ((6.1, 12.3, 9.1), "up"), ((9.35, 12.3, 8.85), "up"), ((7.85, 12.3, 10.35), "up"),
              ((6.6, 9.1, 11.05), "back"), ((8.1, 10.35, 11.05), "back"), ((9.35, 8.6, 11.05), "back"),
              ((6.1, 10.85, 11.05), "back"), ((7.35, 8.35, 11.05), "back"), ((9.1, 10.85, 11.05), "back"),
              ((5.45, 10.1, 8.35), "side"), ((5.45, 8.85, 10.1), "side"), ((10.05, 10.35, 7.85), "side"),
              ((10.05, 8.85, 9.6), "side"), ((5.45, 11.1, 10.35), "side"), ((10.05, 11.1, 10.1), "side")]
    for k, ((x, y, z), _) in enumerate(spikes):
        m.box("spine", (x, y, z), (x + 0.55, y + 0.55, z + 0.55), spines_tip if k % 2 else spines_dark)
    # an apple stuck on the spines
    m.box("apple", (7.25, 12.5, 8.75), (9.25, 14.25, 10.5), apple)
    m.box("apple_stem", (8.0, 14.25, 9.5), (8.5, 15.0, 9.75), Mat("bark", "plain", 2))
    m.box("apple_leaf", (8.5, 14.5, 9.25), (9.5, 14.75, 9.75), leaf)
    mirror(m, "foot", (6.5, 7.75, 6.75), (7.25, 8.25, 7.5), PINK)
    _display(m, SHOULDER_GUI, 1.5, -8)
    return m


def shoulder_dragon():
    """꼬마 붉은 용: a tiny red dragon perched with its wings half open, gold belly, cream horns and a warm
    ember glowing in its mouth."""
    m = MModel("shoulder_dragon")
    scale, scale_light, belly = Mat("vermilion", "speckle", 2), Mat("vermilion", "plain", 3), Mat("gold", "plain", 2)
    horn, wing, wing_bone = Mat("cream", "plain", 2), Mat("crimson", "plain", 3), Mat("crimson", "plain", 1)
    ember = Mat("fire", "plain", 4, glow=True)
    m.box("body", (6.5, 8, 7.0), (9.5, 11.5, 10.5), scale)
    m.box("belly", (7.0, 8.25, 6.75), (9.0, 11.0, 7.0), belly, faces={"south": None})
    m.box("neck", (7.0, 11.0, 6.75), (9.0, 12.5, 8.5), scale)
    m.box("head", (6.5, 12.25, 5.75), (9.5, 14.75, 8.75), scale_light)
    m.box("snout", (7.0, 12.5, 4.5), (9.0, 13.75, 5.75), scale_light, faces={"south": None})
    mirror(m, "nostril", (7.25, 13.25, 4.4), (7.75, 13.5, 4.5), EYE, faces={"south": None})
    m.box("ember", (7.5, 12.6, 4.35), (8.5, 13.0, 4.5), ember, faces={"south": None})
    mirror(m, "eye", (6.75, 13.75, 5.6), (7.5, 14.5, 5.75), Mat("lamp", "plain", 2), faces={"south": None})
    mirror(m, "horn", (6.75, 14.75, 7.25), (7.5, 16.0, 8.0), horn)
    mirror(m, "horn_tip", (6.75, 16.0, 7.75), (7.25, 16.75, 8.25), horn)
    m.box("spike", (7.75, 11.5, 9.5), (8.25, 12.25, 10.25), horn)
    m.box("spike", (7.75, 14.75, 8.25), (8.25, 15.25, 8.75), horn)
    # wings half open, up and out to both sides
    for (x0, x1, y0, y1) in ((4.5, 6.5, 10.5, 11.25), (2.5, 4.5, 11.0, 14.0), (3.0, 4.25, 14.0, 15.0)):
        mirror(m, "wing_bone", (x0, y0, 9.0), (x1, y1, 9.5), wing_bone)
    for (x0, x1, y0, y1) in ((4.5, 6.5, 11.25, 12.75), (3.0, 4.5, 9.75, 11.0), (4.25, 6.25, 12.75, 13.75)):
        mirror(m, "wing", (x0, y0, 9.1), (x1, y1, 9.4), wing)
    # tail curling round the shoulder
    m.box("tail", (7.25, 8.0, 10.5), (8.75, 9.25, 12.5), scale)
    m.box("tail", (8.75, 8.0, 11.5), (10.75, 9.0, 12.5), scale_light)
    m.box("tail_tip", (10.75, 8.0, 11.25), (11.75, 9.5, 12.0), horn)
    mirror(m, "foot", (6.25, 7.75, 6.75), (7.25, 8.25, 7.75), belly)
    _display(m, SHOULDER_GUI, 1.35, -8)
    return m


# ================================================================================================
# 등 (hugs the plane z 8 on the upper back, reaches backwards)
# ================================================================================================

def turtle_shell():
    """거북 등껍질 가방: a domed green turtle shell with darker scutes and a yellow rim, on brown straps."""
    m = MModel("turtle_shell")
    shell, scute, scute_light = Mat("leaf", "plain", 2), Mat("leaf_dark", "plain", 1), Mat("leaf", "plain", 3)
    rim, strap = Mat("petal_yellow", "plain", 1), Mat("leather", "plain", 2)
    m.group("shell")
    # the dome in three steps out from the back
    m.box("rim", (2.5, 1.0, 8.0), (13.5, 12.5, 9.25), rim)
    m.box("dome", (3.0, 1.5, 9.25), (13.0, 12.0, 11.0), shell)
    m.box("dome", (4.0, 2.5, 11.0), (12.0, 11.0, 12.5), shell)
    m.box("dome", (5.5, 4.0, 12.5), (10.5, 9.5, 13.25), scute_light)
    # scutes: a central row of plates and the side plates, each a darker frame on the dome
    for (x0, x1, y0, y1, z) in ((6.0, 10.0, 8.75, 9.5, 13.25), (6.0, 10.0, 4.0, 4.75, 13.25),
                                (5.5, 6.0, 4.0, 9.5, 13.25), (10.0, 10.5, 4.0, 9.5, 13.25),
                                (6.0, 10.0, 6.5, 7.0, 13.25)):
        m.box("scute", (x0, y0, z), (x1, y1, z + 0.25), scute, faces={"north": None})
    for (x0, x1, y0, y1) in ((4.0, 5.5, 3.0, 6.5), (10.5, 12.0, 3.0, 6.5), (4.0, 5.5, 7.0, 10.5), (10.5, 12.0, 7.0, 10.5)):
        m.box("scute_side", (x0, y0, 12.5), (x1, y1, 12.75), scute, faces={"north": None})
    m.group("straps")
    mirror(m, "strap", (3.75, 1.5, 7.5), (5.0, 12.0, 8.0), strap, faces={"south": None})
    m.box("handle", (6.5, 12.5, 9.5), (9.5, 13.25, 10.5), strap)
    _display(m, BACK_GUI, 0.75, -0.5)
    return m


def jetpack():
    """로켓 배낭: two silver tanks with red nose cones and fins, nozzles at the bottom breathing blue-white
    flame (glowing), strapped to a dark harness plate."""
    m = MModel("jetpack")
    metal, metal_light, metal_dark = Mat("steel", "metal", 4), Mat("steel", "metal", 5), Mat("steel", "metal", 2)
    red, plate, gauge = Mat("tomato", "plain", 2), Mat("iron", "plain", 2), Mat("lamp", "plain", 2, glow=True)
    flame_out, flame_in = Mat("aurora", "plain", 3, glow=True), Mat("feather", "plain", 3, glow=True)
    m.group("harness")
    m.box("plate", (5.0, 2.5, 8.0), (11.0, 11.5, 9.0), plate)
    m.box("gauge", (7.25, 8.5, 9.0), (8.75, 10.0, 9.25), gauge, faces={"north": None})
    mirror(m, "strap", (4.0, 2.0, 7.5), (5.0, 12.0, 8.0), Mat("rubber", "plain", 3))
    m.group("tanks")
    for cx in (4.75, 11.25):
        cyl(m, "tank", "y", (cx, 10.75), 4, 2.0, 11.0, metal, cap=metal_light)
        cyl(m, "nose", "y", (cx, 10.75), 3, 11.0, 12.5, red, cap=red)
        m.box("nose_tip", (cx - 0.5, 12.5, 10.25), (cx + 0.5, 13.25, 11.25), red)
        ring(m, "band", (cx, 10.75), 4.5, 3.5, 6.0, 6.75, metal_dark)
        cyl(m, "nozzle", "y", (cx, 10.75), 3, 0.5, 2.0, metal_dark, cap=metal_dark)
        fx = (cx + 2.0, cx + 3.25) if cx > 8 else (cx - 3.25, cx - 2.0)
        m.box("fin", (fx[0], 0.5, 10.5), (fx[1], 3.5, 11.0), red)
        # the flame: a glowing blue-white cone under each nozzle
        m.box("flame", (cx - 1.0, -2.5, 9.75), (cx + 1.0, 0.5, 11.75), flame_out)
        m.box("flame_core", (cx - 0.5, -1.5, 10.25), (cx + 0.5, 0.5, 11.25), flame_in)
        m.box("flame_tip", (cx - 0.5, -4.0, 10.25), (cx + 0.5, -2.5, 11.25), flame_out)
    _display(m, BACK_GUI, 0.7, 0)
    return m


SLOTS = {
    "little_sun": "above", "exclaim_bubble": "above", "rainbow_arc": "above", "star_ring": "above",
    "hamster": "shoulder", "squirrel": "shoulder", "parrot": "shoulder", "hedgehog": "shoulder", "shoulder_dragon": "shoulder",
    "turtle_shell": "back", "jetpack": "back",
}

ALL = {f.__name__: f for f in (little_sun, exclaim_bubble, rainbow_arc, star_ring,
                               hamster, squirrel, parrot, hedgehog, shoulder_dragon,
                               turtle_shell, jetpack)}
