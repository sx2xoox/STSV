"""
목각 공방 시리즈 (woodcraft series): hand-carved wooden looks for held tools, bought with in-game gold.

    woodcraft_sword  woodcraft_pickaxe  woodcraft_shovel  woodcraft_axe  woodcraft_hoe
    woodcraft_fishing_rod (+ woodcraft_fishing_rod_cast)

A "light" set: the toy series' frame, sizes and display transforms (models/toys.py: upright, the grip at GRIP_Y on the
axis x = z = 8, _held() for the views), with about three quarters of a toy piece's boxes. Simple in concept, rich in
detail: the detail is in the paint, not in more boxes.

Every face here is painted by this module, at two texels a model unit (32 x 32 surfaces, UVs in their pixels, like the
donation art), by what it is made of and which way its grain runs:
  - side grain: wavy grain lines along the part, chisel facets across it (the facet nearest the light a step lighter,
    the far one a step darker), a polished highlight streak, a lit top/left edge and a dark bottom/right edge;
  - end grain (a face across the grain: the pommel's butt, the shaft caps, the pegs, the arms' tips): growth rings
    about the part's own axis, a pith dot, a darker rim;
  - pyrography: a burnt vine with little leaves running along the blades and heads;
  - jute twine: twisted strands slanting across each wrap; a knot on the grip's upper wrap with two loose ends;
  - the carved leaves: a painted green with a midrib.
Woods: light polished oak (heads, blades), honey oak (grips, shafts), walnut (joins, guard, acorn caps, pegs), dark
walnut (pommel), dried bamboo (the rod). Every piece has a carved leaf or an acorn; the rod has a carved fish on its tip
and an acorn sinker on its line. No float of its own.
"""

import json
import math
import random
import zlib
from pathlib import Path

from PIL import Image

from economy_machines import MModel
from models.toys import C, _held
from stsv_assets import textures as T

D = 2           # texels a model unit
CANVAS = 32

# ================================================================================================
# Woods and fibres: five shades each, darkest (a grain line) to lightest (the polish)
# ================================================================================================

WOODS = {
    "oak":    [(150, 106, 58), (190, 146, 90), (214, 176, 120), (232, 200, 146), (246, 224, 178)],
    "honey":  [(108, 68, 30), (140, 94, 44), (166, 116, 58), (188, 140, 78), (210, 166, 104)],
    "walnut": [(56, 32, 16), (80, 48, 26), (102, 64, 36), (126, 84, 50), (150, 106, 68)],
    "dark":   [(34, 20, 10), (50, 30, 16), (68, 42, 24), (88, 58, 34), (110, 76, 46)],
    "bamboo": [(146, 108, 46), (178, 138, 64), (204, 168, 90), (224, 194, 118), (240, 216, 150)],
    "twine":  [(110, 86, 52), (150, 124, 82), (190, 162, 114), (214, 190, 144), (232, 214, 174)],
    "leaf":   [(56, 88, 26), (84, 122, 38), (120, 158, 56), (154, 188, 80), (186, 212, 112)],
}
BURN = (70, 38, 18)
BURN_SOFT = (124, 76, 40)

# the face's two texture axes, as model axes (0 x, 1 y, 2 z)
FACE_AXES = {"north": (0, 1), "south": (0, 1), "east": (2, 1), "west": (2, 1), "up": (0, 2), "down": (0, 2)}
FACE_NORMAL = {"north": 2, "south": 2, "east": 0, "west": 0, "up": 1, "down": 1}


def _register(im):
    key = "wc_" + format(zlib.crc32(im.tobytes()), "08x")
    if key not in T.SURFACES:
        T.SURFACES[key] = (lambda img=im: img.copy())
    return key


def _paint(kind, wood, pw, ph, along_u, seed, burn=False, ring=None):
    """A face of pw x ph texels. kind: 'side' (grain along u if along_u else along v), 'end' (growth rings about ring =
    (cu, cv) in texels), 'twine', 'leaf', 'flat'."""
    rnd = random.Random(seed)
    sh = WOODS[wood]
    lv = [[2] * pw for _ in range(ph)]
    if kind == "side":
        L, A = (pw, ph) if along_u else (ph, pw)          # along the grain, across it
        cols = []
        for a in range(A):
            cols.append(rnd.choice((0, 0, 0, 0, 1, -1)))
        lines = [a for a in range(A) if rnd.random() < 0.28]
        phase = rnd.random() * 6
        facets = 3 if A >= 7 else 2 if A >= 4 else 1
        for l in range(L):
            for a in range(A):
                v = 2 + cols[a]
                if facets > 1:
                    f = a * facets // A
                    v += 1 if f == 0 else (-1 if f == facets - 1 else 0)
                for g in lines:
                    if a == g + round(math.sin(l * 0.45 + phase + g) * 0.7):
                        v = min(v, 1) - (1 if rnd.random() < 0.3 else 0)
                if A >= 5 and a == max(1, A // 4) and 0.1 * L < l < 0.75 * L:
                    v = 4                                     # the polish
                x, y = (l, a) if along_u else (a, l)
                lv[y][x] = v
        # a knot now and then on a big face
        if L >= 14 and A >= 6 and rnd.random() < 0.5:
            kl, ka = rnd.randrange(3, L - 3), rnd.randrange(2, A - 2)
            for dl in (-1, 0, 1):
                for da in (-1, 0, 1):
                    x, y = (kl + dl, ka + da) if along_u else (ka + da, kl + dl)
                    lv[y][x] = 0 if dl == da == 0 else 1
    elif kind == "end":
        cu, cv = ring
        for y in range(ph):
            for x in range(pw):
                r = math.hypot(x + 0.5 - cu, y + 0.5 - cv)
                v = 3 if int(r + 0.3) % 2 == 0 else 2
                if r < 0.8:
                    v = 0
                lv[y][x] = v
    elif kind == "twine":
        for y in range(ph):
            for x in range(pw):
                k = (x + y) % 4
                lv[y][x] = (1, 2, 3, 2)[k]
                if ph >= 3 and y in (0, ph - 1):
                    lv[y][x] -= 1
    elif kind == "leaf":
        mid = (pw - 1) / 2
        for y in range(ph):
            for x in range(pw):
                lv[y][x] = 1 if abs(x - mid) < 0.6 and y < ph - 1 else 3 if x < mid - 1 else 2
    # the chisel's bevel: lit top and left, dark bottom and right
    if kind in ("side", "end", "leaf") and pw > 2 and ph > 2:
        for y in range(ph):
            for x in range(pw):
                d = (1 if y == 0 or x == 0 else 0) - (1 if y == ph - 1 or x == pw - 1 else 0)
                if d:
                    lv[y][x] += d
    im = Image.new("RGBA", (CANVAS, CANVAS), sh[2] + (255,))
    px = im.load()
    for y in range(ph):
        for x in range(pw):
            px[x, y] = sh[max(0, min(4, lv[y][x]))] + (255,)
    if kind == "end":          # the bark-dark rim
        for y in range(ph):
            for x in range(pw):
                if x in (0, pw - 1) or y in (0, ph - 1):
                    px[x, y] = sh[1] + (255,)
    if burn and kind == "side":
        _vine(px, pw, ph, along_u, seed)
    return _register(im)


def _vine(px, pw, ph, along_u, seed):
    """A burnt vine along the face with little leaves on alternate sides."""
    L, A = (pw, ph) if along_u else (ph, pw)
    if A < 6 or L < 8:
        return
    phase = (seed % 7) * 0.9

    def put(l, a, c):
        if 1 <= a < A - 1 and 1 <= l < L - 1:
            x, y = (l, a) if along_u else (a, l)
            px[x, y] = c + (255,)

    mid = (A - 1) / 2
    amp = max(0.6, A / 6)
    side = 1
    for l in range(2, L - 2):
        a = round(mid + math.sin(l * 0.55 + phase) * amp)
        put(l, a, BURN)
        if l % 4 == 0:
            # a leaf: two texels out to the side, a soft scorch round it
            put(l - 1, a + side, BURN)
            put(l - 2, a + 2 * side, BURN)
            put(l - 1, a + 2 * side, BURN_SOFT)
            put(l - 2, a + side, BURN_SOFT)
            side = -side


def wbox(m, name, frm, to, wood, along=1, burn=(), ring_c=None, kind="side", rotation=None, faces=None):
    """A box painted per face: grain along model axis `along`, faces across it in growth rings about ring_c (the part's
    axis, model coordinates on the face's two axes; the box's own middle by default). burn: faces with the vine."""
    fs, uv = {}, {}
    for f in ("north", "east", "south", "west", "up", "down"):
        if faces is not None and f not in faces:
            continue
        a1, a2 = FACE_AXES[f]
        w, h = to[a1] - frm[a1], to[a2] - frm[a2]
        pw, ph = max(1, math.ceil(w * D - 1e-6)), max(1, math.ceil(h * D - 1e-6))
        assert pw <= CANVAS and ph <= CANVAS, (name, f, w, h)
        # faces of the same size and kind look alike (one texture: a smaller pack); the vine's faces differ by name
        seed = zlib.crc32(f"{name if f in burn else ''}/{f in ('up', 'down')}/{round(w, 2)}x{round(h, 2)}".encode())
        k = kind
        if kind == "side" and FACE_NORMAL[f] == along:
            k = "end"
        ring = None
        if k == "end":
            c = ring_c or ((frm[a1] + to[a1]) / 2, (frm[a2] + to[a2]) / 2)
            ring = ((c[0] - frm[a1]) * D, (c[1] - frm[a2]) * D)
        fs[f] = _paint(k, wood, pw, ph, along == a1, seed, burn=f in burn, ring=ring)
        uv[f] = [0, 0, pw, ph]
    m.box(name, frm, to, fs[next(iter(fs))], faces=fs, uv=uv, rotation=rotation)


FRONT = ("north", "south")


# ================================================================================================
# Shared parts
# ================================================================================================

def handle(m):
    """Every piece's handle: a dark walnut pommel (growth rings on its butt), a honey-oak grip with two jute wraps, a
    knot with two loose ends on the upper one (y -3.25 .. 6.5)."""
    m.group("handle")
    wbox(m, "pommel", (6.25, -3.25, 6.25), (9.75, -1.25, 9.75), "dark", ring_c=(C, C))
    wbox(m, "grip", (6.75, -1.25, 6.75), (9.25, 6.5, 9.25), "honey")
    for y in (0.25, 3.5):
        wbox(m, "twine", (6.5, y, 6.5), (9.5, y + 1, 9.5), "twine", kind="twine")
    wbox(m, "knot", (7.375, 3.375, 9.5), (8.625, 4.625, 10.125), "twine", kind="twine")
    wbox(m, "twine_end", (7.5, 1.5, 9.625), (7.875, 3.5, 10), "twine", kind="twine", rotation=("z", -22.5, (7.6875, 3.5, 9.8)))
    wbox(m, "twine_end", (8.125, 1.75, 9.625), (8.5, 3.5, 10), "twine", kind="twine", rotation=("z", 22.5, (8.3125, 3.5, 9.8)))


def shaft(m, top, lo=6.5):
    m.group("shaft")
    wbox(m, "shaft", (6.875, lo, 6.875), (9.125, top, 9.125), "honey", ring_c=(C, C))


def peg(m, x, y, z0, z1, s=0.5):
    """A walnut dowel through a part, its end grain proud of both faces."""
    wbox(m, "peg", (x - s, y - s, z0), (x + s, y + s, z1), "walnut", along=2)


def leaf(m, cx, cy, z0, z1, r=1.5):
    """A carved leaf through the part, proud of both faces: a body and a pointed tip along y (the views turn the
    model 45 degrees, so it shows as a slanted leaf)."""
    wbox(m, "leaf", (cx - r * 0.67, cy - r, z0), (cx + r * 0.67, cy + r, z1), "leaf", kind="leaf")
    wbox(m, "leaf_tip", (cx - r * 0.33, cy + r, z0 + 0.125), (cx + r * 0.33, cy + r * 1.5, z1 - 0.125), "leaf", kind="leaf")


def acorn(m, cx, cy, z0, z1):
    """A carved acorn hanging down: a walnut cap over a polished oak nut."""
    wbox(m, "acorn_cap", (cx - 1.25, cy, z0 - 0.125), (cx + 1.25, cy + 1, z1 + 0.125), "walnut", along=0)
    wbox(m, "acorn", (cx - 0.875, cy - 1.75, z0 + 0.25), (cx + 0.875, cy, z1 - 0.25), "oak")


# ================================================================================================
# The tools
# ================================================================================================

def woodcraft_sword():
    """목각 검: a polished oak blade with a burnt vine down it and bright chiselled edges, a walnut guard with acorn ends,
    a leaf on the guard."""
    m = MModel("woodcraft_sword")
    handle(m)
    m.group("guard")
    wbox(m, "guard", (3.5, 6.5, 6.75), (12.5, 8.5, 9.25), "walnut", along=0)
    acorn(m, 2.75, 7.75, 6.75, 9.25)
    acorn(m, 13.25, 7.75, 6.75, 9.25)
    leaf(m, C, 7.375, 6.25, 9.75, 1.25)
    m.group("blade")
    wbox(m, "blade", (5.75, 8.5, 7), (10.25, 23, 9), "oak", burn=FRONT)
    wbox(m, "blade_edge", (4.75, 9.5, 7.375), (5.75, 22.25, 8.625), "oak")
    wbox(m, "blade_edge", (10.25, 9.5, 7.375), (11.25, 22.25, 8.625), "oak")
    wbox(m, "blade_tip", (6.5, 23, 7.25), (9.5, 24.75, 8.75), "oak")
    wbox(m, "blade_tip", (7.25, 24.75, 7.5), (8.75, 26, 8.5), "oak")
    return _held(m, "sword")


def woodcraft_pickaxe():
    """목각 곡괭이: a walnut core pegged to the shaft and bound with twine, two drooping oak arms with a burnt vine,
    growth rings on their tips, a leaf on the core."""
    m = MModel("woodcraft_pickaxe")
    handle(m)
    shaft(m, 16)
    m.group("head")
    wbox(m, "head", (5.5, 15.5, 6.5), (10.5, 21, 9.5), "walnut")
    wbox(m, "twine", (6.5, 14.25, 6.5), (9.5, 15.5, 9.5), "twine", kind="twine")
    leaf(m, C, 17.25, 6.25, 9.75, 1.25)
    peg(m, C, 20, 6.25, 9.75, 0.4)
    for side in (1, -1):
        # the arm: one box drooping 22.5, its tip a box drooping 45 further out
        if side > 0:
            wbox(m, "arm", (10, 16.875, 6.875), (17, 19.625, 9.125), "oak", along=0, burn=FRONT,
                 rotation=("z", -22.5, (10.5, 18.25, C)))
            wbox(m, "arm_tip", (15.5, 12.5, 7.25), (17.5, 16.75, 8.75), "oak", rotation=("z", -45, (16.5, 15.75, C)))
        else:
            wbox(m, "arm", (-1, 16.875, 6.875), (6, 19.625, 9.125), "oak", along=0, burn=FRONT,
                 rotation=("z", 22.5, (5.5, 18.25, C)))
            wbox(m, "arm_tip", (-1.5, 12.5, 7.25), (0.5, 16.75, 8.75), "oak", rotation=("z", 45, (-0.5, 15.75, C)))
    return _held(m, "pickaxe")


def woodcraft_shovel():
    """목각 삽: a wide oak spade with a burnt vine, a rounded point, a twine-bound walnut socket pegged through, a leaf on
    its face."""
    m = MModel("woodcraft_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    wbox(m, "socket", (6.25, 15.5, 6.5), (9.75, 17.25, 9.5), "walnut")
    wbox(m, "twine", (6.5, 14.25, 6.5), (9.5, 15.5, 9.5), "twine", kind="twine")
    wbox(m, "blade", (4, 17.25, 7.125), (12, 24, 8.875), "oak", burn=FRONT)
    wbox(m, "blade", (4.75, 24, 7.25), (11.25, 26, 8.75), "oak")
    wbox(m, "point", (5.75, 26, 7.375), (10.25, 27.5, 8.625), "oak")
    wbox(m, "point", (7, 27.5, 7.5), (9, 28.5, 8.5), "oak")
    leaf(m, C, 23.25, 6.875, 9.125, 1.25)
    peg(m, C, 16.375, 6.25, 9.75, 0.4)
    return _held(m, "shovel")


def woodcraft_axe():
    """목각 도끼: a pegged walnut eye on the shaft, a broad oak bit with a burnt vine and a bright edge (towards -x),
    twine below, a capped shaft end with growth rings, a leaf on the bit."""
    m = MModel("woodcraft_axe")
    handle(m)
    shaft(m, 21.5)
    m.group("head")
    wbox(m, "cap", (6.5, 21.5, 6.5), (9.5, 22.5, 9.5), "walnut", ring_c=(C, C))
    wbox(m, "head", (5.5, 14.5, 6.5), (10.5, 20.5, 9.5), "walnut")
    wbox(m, "twine", (6.5, 13.25, 6.5), (9.5, 14.5, 9.5), "twine", kind="twine")
    wbox(m, "bit", (1.5, 13, 7), (5.5, 22, 9), "oak", burn=FRONT)
    wbox(m, "bit_edge", (-0.25, 12, 7.25), (1.5, 23, 8.75), "oak")
    wbox(m, "poll", (10.5, 15.5, 6.875), (12, 19.5, 9.125), "oak", along=0)
    peg(m, C, 17.5, 6.25, 9.75, 0.5)
    leaf(m, 3.5, 15, 6.75, 9.25, 1.0)
    return _held(m, "axe")


def woodcraft_hoe():
    """목각 괭이: a walnut bar on the shaft's top, an oak plate with a burnt vine hanging from its end, twine at the joint,
    a peg through the bar, a leaf on the bar."""
    m = MModel("woodcraft_hoe")
    handle(m)
    shaft(m, 20)
    m.group("head")
    wbox(m, "bar", (2.5, 20, 6.75), (10, 22.5, 9.25), "walnut", along=0)
    wbox(m, "twine", (6.5, 18.75, 6.5), (9.5, 20, 9.5), "twine", kind="twine")
    wbox(m, "plate", (0.25, 15, 5.25), (2.5, 22.75, 10.75), "oak", burn=("east", "west"))
    wbox(m, "plate_edge", (0.5, 14, 5.5), (2.25, 15, 10.5), "oak")
    peg(m, C, 21.25, 6.5, 9.5, 0.5)
    leaf(m, 4.75, 21.25, 6.5, 9.5, 1.0)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    # a small wooden spool beside the rod's foot, wound with twine
    wbox(m, "spool", (9.25, 7, 6.5), (12.25, 10.5, 9.5), "walnut", along=2)
    wbox(m, "spool_line", (9.5, 7.75, 6.25), (12, 9.75, 9.75), "twine", kind="twine")
    m.group("rod")
    wbox(m, "rod", (6.75, 6.5, 6.75), (9.25, 14, 9.25), "bamboo", ring_c=(C, C))
    wbox(m, "rod", (7, 14, 7), (9, 22, 9), "bamboo", ring_c=(C, C))
    wbox(m, "rod", (7.25, 22, 7.25), (8.75, 29.25, 8.75), "bamboo", ring_c=(C, C))
    wbox(m, "node", (6.5, 13.5, 6.5), (9.5, 14.5, 9.5), "twine", kind="twine")
    wbox(m, "node", (6.75, 21.5, 6.75), (9.25, 22.5, 9.25), "twine", kind="twine")
    wbox(m, "guide", (8.75, 25.5, 7.625), (9.75, 26.25, 8.375), "twine", kind="twine")
    m.group("fish")
    # the carved fish on the tip: nose towards +x (where the line hangs), its tail fork a diamond behind
    wbox(m, "fish", (7, 29.25, 7.125), (10.75, 31.5, 8.875), "oak", along=0, burn=FRONT)
    wbox(m, "fish_nose", (10.75, 29.75, 7.375), (11.5, 31, 8.625), "oak", along=0)
    wbox(m, "fish_tail", (5.5, 29.5, 7.375), (7.25, 31.25, 8.625), "oak", along=0, rotation=("z", 45, (6.375, 30.375, C)))
    wbox(m, "fish_eye", (9.5, 30.5, 6.875), (10.25, 31.25, 9.125), "dark", along=2)
    if not cast:
        m.group("line")
        top = (11, 30, C)
        rot = ("z", 45, top)
        wbox(m, "line", (10.625, 20, 7.625), (11.375, 30, 8.375), "twine", kind="twine", rotation=rot)
        # a carved acorn as the sinker
        wbox(m, "acorn_cap", (9.75, 19, 6.75), (12.25, 20, 9.25), "walnut", along=0, rotation=rot)
        wbox(m, "acorn", (10.125, 17, 7), (11.875, 19, 9), "oak", rotation=rot)
    return _held(m, "rod")


def woodcraft_fishing_rod():
    """목각 낚싯대: a dried-bamboo rod with twine nodes, a wooden spool, a carved fish on the tip, an acorn sinker."""
    return _rod("woodcraft_fishing_rod", False)


def woodcraft_fishing_rod_cast():
    """The rod while its line is out: the same without the line and the acorn."""
    m = _rod("woodcraft_fishing_rod_cast", True)
    m.display = woodcraft_fishing_rod().display
    return m


ALL = {f.__name__: f for f in (woodcraft_sword, woodcraft_pickaxe, woodcraft_shovel, woodcraft_axe, woodcraft_hoe,
                               woodcraft_fishing_rod, woodcraft_fishing_rod_cast)}

ROWS = [
    ("woodcraft_sword", "목각 검"), ("woodcraft_pickaxe", "목각 곡괭이"), ("woodcraft_shovel", "목각 삽"),
    ("woodcraft_axe", "목각 도끼"), ("woodcraft_hoe", "목각 괭이"), ("woodcraft_fishing_rod", "목각 낚싯대"),
    ("woodcraft_fishing_rod_cast", "낚싯대 (던진 뒤)"),
]

TITLE = "목각 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("woodcraft_sword", "woodcraft_pickaxe", "woodcraft_shovel", "woodcraft_axe", "woodcraft_hoe")}
    out["woodcraft_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/woodcraft_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/woodcraft_fishing_rod_cast"},
    }}
    return out


def write_item_definitions(content: Path):
    d = content / "assets" / "stsv" / "items"
    d.mkdir(parents=True, exist_ok=True)
    for name, definition in item_definitions().items():
        (d / f"{name}.json").write_text(json.dumps(definition, indent=2) + "\n")
    return sorted(item_definitions())
