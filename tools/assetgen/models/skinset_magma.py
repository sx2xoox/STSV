"""
용암 시리즈 (magma series): a premium tool skin set. Volcanic obsidian blades and heads split by glowing lava.

    magma_sword  magma_pickaxe  magma_shovel  magma_axe  magma_hoe  magma_fishing_rod (+ magma_fishing_rod_cast)  magma_bobber

Same frame, grip and display transforms as the toy series (models/toys.py: built upright on the axis x = z = 8, the hand
on GRIP_Y, _held() puts the sprite's 45 degree tilt into the transforms).

Looks: black-violet obsidian rock (its faces painted with bright orange-yellow crack lines, a texel a model unit), and
on top of it the lava itself as separate glowing boxes (light_emission 15, unshaded): a molten fissure down each
blade, hot ember-red edges, a molten gem in every guard or socket, drips hanging off the fittings. Dark iron fittings,
a charred leather grip bound with two iron bands, an obsidian pommel with a glowing core, small horn spikes.
"""

import random
import zlib

from PIL import Image

from economy_machines import MModel, Mat, cyl
from stsv_assets import presets
from stsv_assets import textures as T
from stsv_assets.palette import ramp

from models.toys import C, GRIP_Y, SIZES, _arm, _held, round_y  # noqa: F401  (GRIP_Y, SIZES: the frame this set shares)

S = 16


# volcanic glass: near black with a violet sheen (darker and less blue than the palette's "obsidian" metal)
OBSIDIAN = [(16, 11, 24, 255), (27, 18, 38, 255), (41, 27, 57, 255), (61, 41, 82, 255), (88, 62, 112, 255)]


class LavaRock(Mat):
    """Obsidian painted per face at the face's size: a dark violet speckle with a 1 px bevel, split by one or two
    jagged crack lines of bright lava (orange with yellow-hot pixels) along the face's longer side."""

    def __init__(self, base=1, cracks=True):
        super().__init__("obsidian", "plain", base, bevel=(1, -1), noise=0)
        self.cracks = cracks

    def __call__(self, **changes):
        m = LavaRock(self.base, self.cracks)
        m.bevel = changes.get("bevel", self.bevel)
        return m

    def face(self, w, h, face, seed):
        pw, ph = max(1, round(w + 0.49)), max(1, round(h + 0.49))
        tile = pw > S or ph > S
        if tile:
            pw = ph = S
        rnd = random.Random(zlib.crc32(f"lava|{self.base}|{self.cracks}|{bool(self.bevel)}|{pw}x{ph}|{face}|{seed}".encode()))
        b = self.base
        grid = [[b] * S for _ in range(S)]
        for y in range(ph):
            for x in range(pw):
                r = rnd.random()
                g = b - 1 if r < 0.16 else b + 1 if r < 0.24 else b
                if not tile:
                    if self.bevel and ((y == 0 and ph > 1) or (x == 0 and pw > 1)):
                        g = b + 1
                    if self.bevel and ((y == ph - 1 and ph > 1) or (x == pw - 1 and pw > 1)):
                        g = b - 1
                grid[y][x] = max(0, min(len(OBSIDIAN) - 1, g))
        hot = {}
        if self.cracks and min(pw, ph) >= 4 and max(pw, ph) >= 6:
            vertical = ph >= pw or face not in ("up", "down")      # on the sides a crack runs down the height
            L, A = (ph, pw) if vertical else (pw, ph)
            n = 1
            for k in range(n):
                a = rnd.randrange(1, A - 1) if n == 1 else (rnd.randrange(1, max(2, A // 2)) if k == 0 else rnd.randrange(A // 2, A - 1))
                start = rnd.randrange(0, max(1, L // 4))
                end = L - rnd.randrange(0, max(1, L // 4))
                for t in range(start, end):
                    if t > start and rnd.random() < 0.45:
                        a = max(1, min(A - 2, a + rnd.choice((-1, 1))))
                    p = (a, t) if vertical else (t, a)
                    hot[p] = 3 if rnd.random() < 0.25 else 2
                    # a short branch now and then
                    if rnd.random() < 0.12:
                        bx = max(1, min(A - 2, a + rnd.choice((-1, 1))))
                        q = (bx, t) if vertical else (t, bx)
                        hot.setdefault(q, 1)
        im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        px = im.load()
        for y in range(S):
            for x in range(S):
                px[x, y] = ramp("fire", hot[(x, y)]) if (x, y) in hot else OBSIDIAN[grid[y][x]]
        key = "lava_" + format(zlib.crc32(im.tobytes()), "08x")
        if key not in T.SURFACES:
            T.SURFACES[key] = (lambda img=im: img.copy())
        return key, (None if tile else [0, 0, round(w, 4), round(h, 4)])


ROCK = LavaRock(1)                                   # the cracked obsidian of the blades and heads
ROCK_PLAIN = LavaRock(1, cracks=False)               # small rock parts (tips, horns)
ROCK_LIGHT = LavaRock(2, cracks=False)
LAVA = Mat("fire", "plain", 4, bevel=(1, -1), noise=0, glow=True)        # the molten core: yellow-hot
LAVA_DEEP = Mat("fire", "plain", 3, bevel=(1, -1), noise=0, glow=True)   # flowing lava: orange
EMBER = Mat("signal_red", "plain", 1, bevel=None, noise=0, glow=True)    # hot edges: ember orange-red
EMBER_RED = Mat("signal_red", "plain", 0, bevel=None, noise=0, glow=True)   # the cooling red rim
IRON = Mat("iron", "metal", 2, bevel=(1, -1), noise=0)
IRON_DARK = Mat("iron", "metal", 1, bevel=(1, -1), noise=0)
IRON_BAND = Mat("blacksteel", "metal", 3, bevel=(1, -1), noise=0)
LEATHER = Mat("soot", "weave", 1, bevel=(0, -1), noise=0.05)              # charred leather wrap
ROD = Mat("iron", "metal", 1, bevel=(1, -1), noise=0)
LINE = Mat("glow_amber", "plain", 1, bevel=None, noise=0)


# ================================================================================================
# Shared parts
# ================================================================================================

def handle(m):
    """Obsidian pommel with a glowing core and a spike, charred leather grip with two iron bands, an iron collar
    with a hot rim (y -4.75 .. 6.5)."""
    m.group("handle")
    m.box("pommel_spike", (7.25, -4, 7.25), (8.75, -3.25, 8.75), IRON_DARK)
    m.box("pommel_core", (7.5, -4.75, 7.5), (8.5, -4, 8.5), LAVA)
    round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, -3.25, -1.25, ROCK, 0.75)
    m.box("pommel_glow", (6.2, -2.75, 6.2), (9.8, -1.75, 9.8), EMBER)
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, -1.25, 5.25, LEATHER, 0.5)
    for y in (0.25, 3.0):
        round_y(m, "grip_band", 6.5, 9.5, 6.5, 9.5, y, y + 0.75, IRON_BAND, 0.5)
    round_y(m, "collar", 6, 10, 6, 10, 5.25, 6.5, IRON, 1)
    m.box("collar_rim", (6.5, 6.25, 6.5), (9.5, 6.75, 9.5), EMBER_RED)


def shaft(m, top):
    """The iron haft above the collar, up to y = top, with a glowing crack ring a third of the way up."""
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, IRON_DARK, 0.5)
    mid = 6.5 + (top - 6.5) * 0.42
    m.box("shaft_ring", (6.625, mid, 6.625), (9.375, mid + 0.75, 9.375), ROCK_PLAIN)
    m.box("shaft_ring_glow", (6.45, mid + 0.75, 6.45), (9.55, mid + 1.0, 9.55), EMBER)


def drip(m, x, y, z=C, big=True):
    """A molten drip hanging from y: a bead under a thin neck."""
    m.box("drip", (x - 0.5, y - 1, z - 0.5), (x + 0.5, y, z + 0.5), LAVA_DEEP)
    if big:
        m.box("drip", (x - 0.375, y - 1.75, z - 0.375), (x + 0.375, y - 1, z + 0.375), LAVA)


# ================================================================================================
# The tools
# ================================================================================================

def fissure(m, segs, z0, z1, mat=None):
    """A zigzag lava crack: segments (x0, x1, y0, y1), each a little off the last, glowing through the rock."""
    for x0, x1, y0, y1 in segs:
        m.box("fissure", (x0, y0, z0), (x1, y1, z1), mat or LAVA_DEEP)


def magma_sword():
    """용암 검: a tapering, stepped obsidian blade split by a zigzag lava crack, its edges glowing ember red; an iron guard
    whose ends rise into obsidian horns, a molten gem in the guard and drips hanging under it."""
    m = MModel("magma_sword")
    handle(m)
    m.group("guard")
    m.box("guard", (3, 6.5, 6.75), (13, 8.75, 9.25), IRON)
    m.box("guard_hot", (3.25, 8.75, 7), (12.75, 9.25, 9), EMBER_RED)
    for side in (1, -1):
        x0 = C + side * 5
        lo, hi = sorted((x0, x0 + side * 1.75))
        m.box("horn", (lo, 6.25, 6.875), (hi, 9.75, 9.125), ROCK_PLAIN)
        lo, hi = sorted((x0 + side * 0.5, x0 + side * 1.75))
        m.box("horn", (lo, 9.75, 7.125), (hi, 11, 8.875), ROCK_PLAIN)
        lo, hi = sorted((x0 + side * 1.0, x0 + side * 1.75))
        m.box("horn_tip", (lo, 11, 7.375), (hi, 11.75, 8.625), EMBER)
    m.box("guard_gem", (6.75, 6.75, 6.5), (9.25, 8.5, 9.5), LAVA)
    drip(m, 5, 6.5)
    drip(m, 11.25, 6.5, big=False)
    m.group("blade")
    # stepped rows, narrowing to the point: (y0, y1, half width)
    rows = ((9.25, 14, 3.0), (14, 18.5, 2.75), (18.5, 22, 2.5), (22, 24.5, 2.0))
    for y0, y1, hw in rows:
        m.box("blade", (C - hw, y0, 7), (C + hw, y1, 9), ROCK)
        m.box("edge", (C - hw - 0.5, y0 + 0.25, 7.5), (C - hw, y1 - 0.25, 8.5), EMBER_RED)
        m.box("edge", (C + hw, y0 + 0.25, 7.5), (C + hw + 0.5, y1 - 0.25, 8.5), EMBER_RED)
    m.box("blade_tip", (6.75, 24.5, 7.125), (9.25, 26, 8.875), ROCK)
    m.box("blade_tip", (7.25, 26, 7.375), (8.75, 27, 8.625), ROCK_PLAIN)
    m.box("blade_tip", (7.625, 27, 7.625), (8.375, 27.75, 8.375), EMBER)
    fissure(m, ((7.5, 8.5, 9.25, 12.5), (7, 8, 12.5, 15.5), (7.75, 8.75, 15.5, 19), (7.25, 8.25, 19, 22), (7.75, 8.5, 22, 24.5)), 6.85, 9.15)
    m.box("fissure_hot", (7.625, 9.3, 6.8), (8.375, 11, 9.2), LAVA)
    return _held(m, "sword")


def magma_pickaxe():
    """용암 곡괭이: obsidian arms bending down in two steps from an iron socket with a molten gem, glowing points, a horn
    on top and a drip under the socket."""
    m = MModel("magma_pickaxe")
    handle(m)
    shaft(m, 16)
    m.group("head")
    m.box("socket", (5.25, 15.25, 6.5), (10.75, 20.75, 9.5), IRON)
    m.box("socket_gem", (6.75, 16.75, 6.25), (9.25, 19.25, 9.75), LAVA)
    m.box("socket_hot", (5.5, 20.75, 6.75), (10.5, 21.25, 9.25), EMBER_RED)
    m.box("horn", (7, 21.25, 7.25), (9, 22.5, 8.75), ROCK_PLAIN)
    m.box("horn_tip", (7.5, 22.5, 7.5), (8.5, 23.5, 8.5), EMBER)
    for side in (1, -1):
        p = (C + side * 3.25, 18.25)
        p0 = p
        p = _arm(m, "arm_a", side, p, 4.5, 1.875, 6.75, 9.25, 22.5, ROCK)
        _arm(m, "arm_vein", side, p0, 4.5, 0.375, 6.6, 9.4, 22.5, LAVA_DEEP, back=0)
        p1 = p
        p = _arm(m, "arm_b", side, p, 4.0, 1.5, 7, 9, 45, ROCK)
        _arm(m, "arm_vein", side, p1, 3.5, 0.375, 6.85, 9.15, 45, LAVA_DEEP, back=0)
        px, py = p
        if side > 0:
            frm, to = (px + 0.5, py - 1, 7.25), (px + 2.25, py + 1, 8.75)
        else:
            frm, to = (px - 2.25, py - 1, 7.25), (px - 0.5, py + 1, 8.75)
        m.box("arm_tip", frm, to, LAVA_DEEP, rotation=("z", -45 * side, (px, py, C)))
    drip(m, 6, 15.25)
    return _held(m, "pickaxe")


def magma_shovel():
    """용암 삽: a cracked obsidian spade (wide shoulders, a pointed end) on an iron socket, a molten seam down its face,
    ember edges round the point."""
    m = MModel("magma_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15.5, 17, IRON, 0.75)
    m.box("socket_hot", (6.5, 17, 6.5), (9.5, 17.5, 9.5), EMBER_RED)
    rows = ((17.5, 18.5, 3.75), (18.5, 23.5, 4.5), (23.5, 25.5, 3.75), (25.5, 27, 2.75))
    for y0, y1, hw in rows:
        m.box("blade", (C - hw, y0, 7), (C + hw, y1, 9), ROCK)
    m.box("point", (6.5, 27, 7.25), (9.5, 28.25, 8.75), ROCK_PLAIN)
    m.box("point", (7.25, 28.25, 7.5), (8.75, 29, 8.5), EMBER)
    for side in (1, -1):        # the cutting rim round the point glows
        lo, hi = sorted((C + side * 2.75, C + side * 3.25))
        m.box("rim", (lo, 25.5, 7.5), (hi, 27, 8.5), EMBER_RED)
        lo, hi = sorted((C + side * 3.75, C + side * 4.25))
        m.box("rim", (lo, 23.5, 7.5), (hi, 25.5, 8.5), EMBER_RED)
        lo, hi = sorted((C + side * 4.5, C + side * 5))
        m.box("rim", (lo, 18.75, 7.5), (hi, 23.25, 8.5), EMBER_RED)
    fissure(m, ((7.5, 8.5, 18, 20.5), (7, 8, 20.5, 23), (7.5, 8.5, 23, 25.5), (7.75, 8.5, 25.5, 26.95)), 6.85, 9.15)
    return _held(m, "shovel")


def magma_axe():
    """용암 도끼: an iron head with a molten gem, a wide obsidian bit (towards -x) with a glowing edge and a lava vein, a
    horn spike behind and a spiked cap on the haft."""
    m = MModel("magma_axe")
    handle(m)
    shaft(m, 21.5)
    m.group("head")
    round_y(m, "shaft_cap", 6.5, 9.5, 6.5, 9.5, 21.5, 22.5, IRON, 0.75)
    m.box("cap_spike", (7.25, 22.5, 7.25), (8.75, 23.75, 8.75), ROCK_PLAIN)
    m.box("cap_tip", (7.625, 23.75, 7.625), (8.375, 24.5, 8.375), EMBER)
    m.box("head", (5, 14, 6.5), (10, 20.5, 9.5), IRON)
    m.box("head_gem", (6.5, 16, 6.25), (8.5, 18.5, 9.75), LAVA)
    m.box("bit", (2, 13, 6.875), (5, 21.5, 9.125), ROCK)
    m.box("bit", (0, 12, 7.25), (2, 22.5, 8.75), ROCK)
    m.box("vein", (1.25, 16.75, 7.05), (4.25, 17.5, 8.95), LAVA_DEEP)
    m.box("edge", (-1.25, 12.5, 7.5), (0, 22, 8.5), LAVA)
    m.box("edge", (-0.75, 11.5, 7.5), (0, 12.5, 8.5), EMBER)
    m.box("edge", (-0.75, 22, 7.5), (0, 23, 8.5), EMBER)
    m.box("poll", (10, 15, 6.75), (11.5, 19.5, 9.25), ROCK_PLAIN)
    m.box("poll_spike", (11.5, 16, 7.125), (12.75, 18.5, 8.875), ROCK_PLAIN)
    m.box("poll_tip", (12.75, 16.75, 7.5), (13.75, 17.75, 8.5), EMBER)
    drip(m, 3, 13)
    return _held(m, "axe")


def magma_hoe():
    """용암 괭이: the vanilla hoe's L: a straight cracked obsidian head standing out to one side (-x) from an iron socket on
    the haft's top, ending flat in a glowing lava edge; a lava vein along it, an ember rim under it, drips hanging from
    it, a horn on the socket. Nothing droops or hooks: the head is one straight bar."""
    m = MModel("magma_hoe")
    handle(m)
    shaft(m, 19.5)
    m.group("head")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 19, 23.25, IRON, 0.75)
    m.box("socket_ring", (6, 21.25, 6), (10, 21.75, 10), EMBER_RED)
    m.box("horn", (7.25, 23.25, 7.25), (8.75, 24.5, 8.75), ROCK_PLAIN)
    m.box("horn_tip", (7.625, 24.5, 7.625), (8.375, 25.25, 8.375), EMBER)
    # the head: a long straight bar, flat top and bottom, as wide across (z) as a hoe's blade
    m.box("head", (-1, 19.75, 6.25), (6.25, 22.75, 9.75), ROCK)
    m.box("head_top", (-0.75, 22.75, 6.75), (6.25, 23.25, 9.25), ROCK_PLAIN)
    fissure(m, ((3.25, 6.2, 20.75, 21.5), (0, 3.25, 21.25, 22)), 6.1, 9.9)
    m.box("rim", (-1, 19.5, 6.5), (6.25, 19.75, 9.5), EMBER_RED)
    m.box("edge", (-1.75, 19.75, 6.5), (-1, 22.75, 9.5), LAVA)
    drip(m, 0.5, 19.5)
    drip(m, 3.5, 19.5, big=False)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    # a small obsidian drum beside the rod's foot (+x), a molten hub, an iron crank with a glowing knob (+z)
    m.box("reel_foot", (9, 7.5, 7.25), (10, 9.5, 8.75), IRON_DARK)
    cyl(m, "reel", "z", (12.5, 8.5), 5, 6.5, 9.5, ROCK_PLAIN, cap=ROCK_PLAIN)
    m.box("reel_hub", (11.5, 7.5, 6.25), (13.5, 9.5, 9.75), LAVA)
    m.box("crank", (12, 8, 9.75), (13, 11, 10.5), IRON)
    m.box("crank_knob", (11.875, 10.5, 10.5), (13.125, 11.75, 11.5), EMBER)
    m.group("rod")
    round_y(m, "rod", 6.625, 9.375, 6.625, 9.375, 6.5, 14, ROD, 0.75)
    m.box("rod", (6.875, 14, 6.875), (9.125, 23, 9.125), ROD)
    m.box("rod", (7.125, 23, 7.125), (8.875, 30.25, 8.875), ROD)
    m.box("rod_tip", (7, 30.25, 7), (9, 31.25, 9), LAVA_DEEP)
    m.box("rod_tip", (7.375, 31.25, 7.375), (8.625, 32, 8.625), LAVA)
    # lava-crack rings: an obsidian ring with a glowing band through it
    for y, r in ((13.25, 3.25), (22.25, 2.75), (28, 2.25)):
        lo, hi = C - r / 2 - 0.125, C + r / 2 + 0.125
        m.box("ring", (lo, y, lo), (hi, y + 1.5, hi), ROCK_PLAIN)
        m.box("ring_glow", (lo - 0.125, y + 0.5, lo - 0.125), (hi + 0.125, y + 1, hi + 0.125), LAVA_DEEP)
    m.box("guide", (9.125, 17.75, 7.375), (10.25, 18.75, 8.625), IRON)
    m.box("guide", (8.875, 25.5, 7.375), (10, 26.5, 8.625), IRON)
    if not cast:
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.25, 20, 7.75), (8.75, 31, 8.25), LINE, rotation=rot)
        # a magma rock float on the line: a glowing ring, a cracked rock body, an ember drop below
        m.box("float_eye", (7.75, 19.25, 7.5), (9.25, 20, 8.5), IRON, rotation=rot)
        m.box("float_top", (6.75, 17.75, 6.75), (10.25, 19.25, 9.25), ROCK_PLAIN, rotation=rot)
        m.box("float_band", (6.5, 17, 6.5), (10.5, 17.75, 9.5), LAVA, rotation=rot)
        m.box("float_bottom", (6.75, 15.25, 6.75), (10.25, 17, 9.25), ROCK_PLAIN, rotation=rot)
        m.box("float_drop", (7.75, 14.25, 7.75), (9.25, 15.25, 8.25), EMBER, rotation=rot)
    return _held(m, "rod")


def magma_fishing_rod():
    """용암 낚싯대: a black iron rod with three lava-crack rings, a glowing ember tip, an obsidian reel with a molten hub,
    a magma rock float on the line."""
    return _rod("magma_fishing_rod", False)


def magma_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the float."""
    m = _rod("magma_fishing_rod_cast", True)
    m.display = magma_fishing_rod().display
    return m


BOBBER_WATERLINE = 4.0        # the glowing band: where the water's surface is (model y)


def magma_bobber():
    """용암 찌: a small magma rock float for an item display on the hook. A rough cracked obsidian lump, a glowing lava
    band at the waterline, a molten crown with an iron eye on top. Stands on y = 0 about x = z = 8."""
    m = MModel("magma_bobber")
    m.group("float")
    cyl(m, "keel", "y", (C, C), 2, 0, 1, EMBER_RED, cap=EMBER_RED)
    cyl(m, "belly", "y", (C, C), 4, 1, 2, ROCK, cap=ROCK)
    cyl(m, "belly", "y", (C, C), 6, 2, 3.5, ROCK, cap=ROCK)
    cyl(m, "band", "y", (C, C), 6, 3.5, 4.5, LAVA, cap=LAVA)
    cyl(m, "top", "y", (C, C), 6, 4.5, 7, ROCK, cap=ROCK)
    cyl(m, "top", "y", (C, C), 4, 7, 8.25, ROCK, cap=ROCK)
    # glowing cracks on four sides of the rock, a molten crown on top
    for face, frm, to in (("south", (7.5, 4.5, 11), (8.5, 7, 11.2)), ("north", (7.5, 4.5, 4.8), (8.5, 7, 5)),
                          ("east", (11, 4.5, 7.5), (11.2, 7, 8.5)), ("west", (4.8, 4.5, 7.5), (5, 7, 8.5))):
        m.box("crack", frm, to, LAVA_DEEP)
    m.box("crown", (7, 8.25, 7), (9, 9, 9), LAVA)
    m.box("eye", (7.25, 9, 7.625), (8.75, 10, 8.375), IRON)
    m.display = presets.furniture(gui_scale=1.3, gui_translation=(0, 3.25, 0))
    m.kind = "bobber"
    return m


ALL = {f.__name__: f for f in (magma_sword, magma_pickaxe, magma_shovel, magma_axe, magma_hoe, magma_fishing_rod,
                               magma_fishing_rod_cast, magma_bobber)}

ROWS = [
    ("magma_sword", "용암 검"), ("magma_pickaxe", "용암 곡괭이"), ("magma_shovel", "용암 삽"), ("magma_axe", "용암 도끼"),
    ("magma_hoe", "용암 괭이"), ("magma_fishing_rod", "용암 낚싯대"), ("magma_fishing_rod_cast", "낚싯대 (던진 뒤)"),
    ("magma_bobber", "용암 찌 (찌에 태우는 모델)"),
]

TITLE = "용암 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("magma_sword", "magma_pickaxe", "magma_shovel", "magma_axe", "magma_hoe", "magma_bobber")}
    out["magma_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/magma_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/magma_fishing_rod_cast"},
    }}
    return out
