"""
사탕 시리즈 (candy series): a light tool skin set (gold shop), sweets instead of the toy series' plastic.

    candy_sword  candy_pickaxe  candy_shovel  candy_axe  candy_hoe  candy_fishing_rod (+ candy_fishing_rod_cast)

Same frame, grip and display transforms as models/toys.py (built upright about x = z = 8, the hand at GRIP_Y, views
from toys._held), about three quarters of the elements of a toy piece: simple shapes, the detail is in the paint.
Pastel pink / mint / lemon, white candy stripes, and on every piece a chocolate-dipped grip with sugar sprinkles,
a glossy candy ball for a pommel and a satin ribbon bow on the collar (on +z: the side the inventory and the
player's own eyes see).

    sword    a pink-and-white striped candy-stick blade on a round mint lollipop (shaded swirl on both faces)
    pickaxe  two striped wrapped hard candies (pink and lemon) drooping off a sugared mint ball, crinkled wrapper
             twists and fans for tips
    shovel   a big wafer scoop (raised waffle grid, cream layers along its edges), its end dipped in sprinkled
             strawberry icing with drips, a cherry on top
    axe      a glossy chocolate bar with a bite out of its edge (the bite shows the caramel layer inside), half in a
             crinkled pink wrapper with a printed label and a crimped end, a lemon foil rim
    hoe      a straight pink candy-stick head out to one side, ending flat in a lemon candy end, a translucent-looking
             mint gummy bear sitting on it
    rod      a mint-striped candy stick with candy ring guides, a lemon lollipop for a reel, a sugared pink-and-lemon
             gummy worm on the line

Paint: Candy (below) paints each face at its own pixel size like Mat does (one texel a model unit), with its own
styles: gloss (shading and a highlight), sugar (gloss and sprinkles), stripe, gummy, wrapper, ribbon. The swirl,
waffle, wafer-layer and chocolate pictures are 16 x 16 surfaces.
"""

import math
import zlib

from PIL import Image

from economy_machines import MModel, Mat, cyl
from stsv_assets import textures as T
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp, ladder
from models.toys import C, _held, round_y


# ================================================================================================
# Ramps of its own (hue, chroma, centre lightness, span, steps, accent): pastel, lighter than the toys' primaries
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("candy_pink", 0, 0.080, 0.80, 0.24, 4, False)       # strawberry candy, icing, wrappers
_ramp("candy_mint", 168, 0.065, 0.84, 0.22, 4, False)     # mint candy, the gummy bear
_ramp("candy_lemon", 100, 0.080, 0.90, 0.16, 4, False)    # lemon drops, foil
_ramp("candy_lilac", 305, 0.060, 0.80, 0.20, 4, False)    # sprinkles, ribbons
_ramp("candy_choc", 45, 0.050, 0.40, 0.22, 4, False)      # milk chocolate: the grips, the bar
_ramp("candy_wafer", 75, 0.070, 0.77, 0.20, 4, False)     # wafer biscuit, caramel
_ramp("candy_white", 20, 0.010, 0.94, 0.08, 3, False)     # sugar white: stripes, sticks, cream
_ramp("candy_cherry", 15, 0.130, 0.55, 0.26, 4, False)    # the cherry


def col(name, i):
    return ladder(name, i)


SPRINKLES = (("candy_white", 2), ("candy_pink", 1), ("candy_mint", 1), ("candy_lemon", 1), ("candy_lilac", 1))


def _hash(*v):
    return zlib.crc32(repr(v).encode()) % 1000


# ================================================================================================
# Candy paint: a Mat whose faces are painted by its own styles
# ================================================================================================

class Candy(Mat):
    """style: gloss | sugar | stripe | ring | gummy | wrapper | ribbon | stick. opt: second (the stripes' other ramp),
    sprinkles (density 0..1), steep (stripe slope), face (the bear's face on north / south)."""

    def __init__(self, ramp_name, style="gloss", base=2, **opt):
        super().__init__(ramp_name, style, base, bevel=None, noise=0, **opt)

    def __call__(self, **changes):          # cyl asks for side(bevel=None): the paint has no bevel anyway
        changes.pop("bevel", None)
        if not changes:
            return self
        return Candy(self.ramp, changes.pop("style", self.kind), changes.pop("base", self.base), **{**self.opt, **changes})

    def _shade(self, x, y, pw, ph, face):
        if face == "up":
            return 1
        if face == "down":
            return -1
        d = 0
        if ph > 2 and y == 0:
            d += 1
        if ph > 2 and y == ph - 1:
            d -= 1
        if pw > 2 and x == pw - 1:
            d -= 1
        return d

    def _pixel(self, x, y, pw, ph, face, seed):
        r, b, k, o = self.ramp, self.base, self.kind, self.opt
        side = face not in ("up", "down")
        d = self._shade(x, y, pw, ph, face)
        big = pw >= 3 and ph >= 3 and side
        if k in ("gloss", "sugar", "stick"):
            c = col(r, b + d)
            if big and k != "stick":
                if (x, y) == (1, 1):
                    return col("candy_white", 3)
                if (x, y) in ((2, 1), (1, 2)):
                    c = col(r, b + 2)
            elif k == "stick" and pw >= 2 and x == 0 and side:
                c = col(r, b + 1)
            if k == "sugar" and 0 < x < pw - 1 and 0 < y < ph - 1 or k == "sugar" and not side:
                h = _hash(seed, x, y)
                if h < 1000 * o.get("sprinkles", 0.14) and (x, y) not in ((1, 1), (2, 1), (1, 2)):
                    sr, si = SPRINKLES[h % len(SPRINKLES)]
                    if sr != r:
                        return col(sr, si + (1 if h % 2 else 0))
                    return col("candy_white", 2)
            return c
        if k == "ring":
            # a thin stick's spiral: thick colour bands, a thin white one, climbing one texel every two across
            s = (y + x // 2) % 6
            if s < 4:
                return col(r, b + d + (1 if s == 0 else 0) - (1 if s == 3 else 0))
            return col("candy_white", 2 + min(0, d))
        if k == "stripe":
            steep = o.get("steep", 1)
            s = (x + steep * y) % 8
            if s < 4:
                c = col(r, b + d + (1 if s == 0 else 0))
            elif s == 4:
                c = col(r, b - 1 + d)
            else:
                c = col("candy_white", 2 + min(0, d))
            if side and pw >= 4 and x == 1 and s >= 5:
                c = col("candy_white", 3)
            return c
        if k == "gummy":
            if face in ("north", "south") and o.get("face"):
                if y == 1 and x in (1, pw - 2):
                    return col("candy_choc", 0)
                if y == 2 and x == pw // 2:
                    return col("candy_pink", 1)
                if 1 <= y <= 2 and 1 <= x <= pw - 2:
                    return col(r, b + 1)
            rim = x == 0 or y == 0 or x == pw - 1 or y == ph - 1
            if not side:
                return col(r, b + (1 if face == "up" else -1))
            if rim and pw > 2 and ph > 2:
                return col(r, b - 1 + (1 if y == 0 else 0))
            cx, cy = (pw - 1) / 2, (ph - 1) / 2
            if abs(x - cx) <= pw * 0.22 and abs(y - cy) <= ph * 0.25:
                return col(r, b + 1)
            if big and (x, y) == (1, 1):
                return col("candy_white", 3)
            return col(r, b)
        if k == "wrapper":
            c = col(r, b + d)
            if side and (x * 3 + y * 5) % 7 == 0:
                c = col(r, b + 1 + d)
            elif side and (x * 5 + y * 2) % 9 == 0:
                c = col(r, b - 1 + d)
            label = o.get("label")
            if label and side and pw >= 4 and abs(y - (ph - 1) / 2) <= 1.0 and 0 < x < pw - 1:
                c = col("candy_white", 2)
                if abs(y - (ph - 1) / 2) <= 0.5 and x == pw // 2:
                    c = col("candy_cherry", 2)
            return c
        if k == "pleat":
            along = y if ph >= pw else x
            return col(r, b + d + (1 if along % 2 == 0 else 0))
        if k == "ribbon":
            mid = (ph - 1) / 2
            c = col(r, b + d)
            if side and abs(y - mid) < 0.6 and ph >= 2:
                c = col(r, b + 1)
            return c
        return col(r, b)

    def face(self, w, h, face, seed):
        pw, ph = max(1, math.ceil(w - 1e-6)), max(1, math.ceil(h - 1e-6))
        pw, ph = min(pw, 16), min(ph, 16)
        im = Image.new("RGBA", (16, 16), col(self.ramp, self.base))
        px = im.load()
        for y in range(ph):
            for x in range(pw):
                px[x, y] = self._pixel(x, y, pw, ph, face, seed)
        key = "candy_" + format(zlib.crc32(im.tobytes()), "08x")
        if key not in T.SURFACES:
            T.SURFACES[key] = (lambda img=im: img.copy())
        return key, [0, 0, round(min(w, 16), 4), round(min(h, 16), 4)]


PINK = Candy("candy_pink")
PINK_SUGAR = Candy("candy_pink", "sugar", sprinkles=0.16)
PINK_PAPER = Candy("candy_pink", "wrapper")
PINK_LABEL = Candy("candy_pink", "wrapper", label=True)
PINK_PLEAT = Candy("candy_pink", "pleat", 3)
PINK_STRIPE = Candy("candy_pink", "stripe")
PINK_RING = Candy("candy_pink", "ring")
MINT = Candy("candy_mint")
MINT_SUGAR = Candy("candy_mint", "sugar", sprinkles=0.12)
MINT_RING = Candy("candy_mint", "ring")
GUMMY = Candy("candy_mint", "gummy")
GUMMY_FACE = Candy("candy_mint", "gummy", face=True)
LEMON = Candy("candy_lemon")
LEMON_SUGAR = Candy("candy_lemon", "sugar", sprinkles=0.12)
LEMON_STRIPE = Candy("candy_lemon", "stripe", 1)
LEMON_PLEAT = Candy("candy_lemon", "pleat", 2)
FOIL = Candy("candy_lemon", "wrapper", 1)
CHOC_GRIP = Candy("candy_choc", "sugar", 1, sprinkles=0.2)
CHOC = Candy("candy_choc", "gloss", 1)
WAFER = Candy("candy_wafer", "gloss", 2)
CREAM = Candy("candy_white", "gloss", 2)
STICK = Candy("candy_white", "stick", 1)
CHERRY = Candy("candy_cherry", "gloss", 2)
STEM = Candy("candy_choc", "stick", 2)
RIBBON = {"pink": Candy("candy_pink", "ribbon", 1), "mint": Candy("candy_mint", "ribbon", 1),
          "lemon": Candy("candy_lemon", "ribbon", 1), "lilac": Candy("candy_lilac", "ribbon", 1)}


# ================================================================================================
# Surfaces
# ================================================================================================

def _swirl(name):
    """A lollipop's face, drawn over the whole round end: a two-colour spiral lit from the top left, a highlight
    arc, a darker rim."""
    def make():
        im = Image.new("RGBA", (16, 16))
        px = im.load()
        for y in range(16):
            for x in range(16):
                dx, dy = x - 7.5, y - 7.5
                r = math.hypot(dx, dy)
                ang = math.degrees(math.atan2(dy, dx)) % 360
                a = ang / 360
                band = int(r / 2.6 + a * 2) % 2
                light = 1 if dx + dy < -5 else -1 if dx + dy > 6 else 0
                c = col(name, 2 + light) if band == 0 else col("candy_white", 2 + min(0, light) + (1 if light > 0 else 0))
                if 4.2 < r < 5.6 and 200 < ang < 245:
                    c = col("candy_white", 3)
                if r > 6.6:
                    c = col(name, 1)
                px[x, y] = c
        return im
    return make


def _waffle():
    """Wafer face: raised ridges (light) round square pockets, each pocket in shadow along its top and left."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            cx, cy = x % 4, y % 4
            if cx == 3 or cy == 3:
                c = col("candy_wafer", 3)
            elif cx == 0 or cy == 0:
                c = col("candy_wafer", 1)
            else:
                c = col("candy_wafer", 2)
            px[x, y] = c
    return im


WAFER_EDGE = (("candy_wafer", 3), ("candy_white", 2), ("candy_wafer", 2), ("candy_white", 2), ("candy_wafer", 2), ("candy_wafer", 1))


def _wafer_layers(vertical):
    """The wafer's edge, six texels across (mapped onto its 3 units): sheets with cream between."""
    def make():
        im = Image.new("RGBA", (16, 16))
        px = im.load()
        for y in range(16):
            for x in range(16):
                k, along = (x, y) if vertical else (y, x)
                n, i = WAFER_EDGE[k % 6]
                if n == "candy_wafer" and along % 4 == 3:
                    i -= 1
                px[x, y] = col(n, i)
        return im
    return make


CHOC_INSIDE = (("candy_choc", 2), ("candy_choc", 1), ("candy_wafer", 3), ("candy_wafer", 2), ("candy_choc", 1), ("candy_choc", 0))


def _choc_inside(vertical):
    """Where the bar is bitten or broken: chocolate, a caramel layer, chocolate (six texels across its 2.5 units)."""
    def make():
        im = Image.new("RGBA", (16, 16))
        px = im.load()
        for y in range(16):
            for x in range(16):
                k, along = (x, y) if vertical else (y, x)
                n, i = CHOC_INSIDE[k % 6]
                if n == "candy_choc" and (along * 7 + k * 3) % 5 == 0:
                    i = max(0, i - 1)          # a rough bitten texture
                px[x, y] = col(n, i)
        return im
    return make


def _choc_bar():
    """A chocolate bar's segments, period 4: a light top left bevel, a groove along bottom and right, a glint."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            cx, cy = x % 4, y % 4
            if cx == 3 or cy == 3:
                c = col("candy_choc", 0)
            elif cx == 0 or cy == 0:
                c = col("candy_choc", 2)
            else:
                c = col("candy_choc", 1)
            if (cx, cy) == (1, 1):
                c = col("candy_choc", 3)
            px[x, y] = c
    return im


T.SURFACES["candy_swirl_mint"] = _swirl("candy_mint")
T.SURFACES["candy_swirl_lemon"] = _swirl("candy_lemon")
T.SURFACES["candy_waffle"] = _waffle
T.SURFACES["candy_wafer_v"] = _wafer_layers(True)
T.SURFACES["candy_wafer_h"] = _wafer_layers(False)
T.SURFACES["candy_choc_bar"] = _choc_bar
T.SURFACES["candy_choc_in_v"] = _choc_inside(True)
T.SURFACES["candy_choc_in_h"] = _choc_inside(False)


# ================================================================================================
# Shared parts
# ================================================================================================

def handle(m, ball, ribbon):
    """A glossy candy ball for a pommel, a chocolate-dipped grip with sprinkles, a satin ribbon round the collar
    tied in a bow on +z (y -3.5 .. 7): eight boxes."""
    m.group("handle")
    round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, -3.5, -1.25, ball, 0.75)
    m.box("grip", (6.75, -1.25, 6.75), (9.25, 5.25, 9.25), CHOC_GRIP)
    rib = RIBBON[ribbon]
    m.box("collar", (6.5, 5.25, 6.5), (9.5, 6.5, 9.5), rib)
    m.group("bow")
    m.box("bow_loop", (4.75, 4.75, 9.25), (7.25, 7, 10), rib)
    m.box("bow_loop", (8.75, 4.75, 9.25), (11.25, 7, 10), rib)
    m.box("bow_knot", (7.25, 5.2, 9.25), (8.75, 6.55, 10.5), rib(base=0))


def stick(m, top):
    """The white lollipop stick above the collar, up to y = top."""
    m.group("stick")
    m.box("stick", (7, 6.5, 7), (9, top, 9), STICK)


# ================================================================================================
# The tools
# ================================================================================================

def candy_sword():
    """사탕 검: a pink-and-white striped candy-stick blade with a pointed tip, on a round mint lollipop for a guard."""
    m = MModel("candy_sword")
    handle(m, PINK, "lilac")
    m.group("guard")
    cyl(m, "lollipop", "z", (C, 10), 8, 6.75, 9.25, MINT, cap="candy_swirl_mint")
    m.group("blade")
    m.box("blade", (5.75, 14, 7), (10.25, 25, 9), PINK_STRIPE)
    m.box("blade_edge", (5.25, 14.5, 7.5), (5.75, 24.5, 8.5), PINK_STRIPE)
    m.box("blade_edge", (10.25, 14.5, 7.5), (10.75, 24.5, 8.5), PINK_STRIPE)
    m.box("blade_tip", (6.5, 25, 7.25), (9.5, 26.5, 8.75), PINK_STRIPE)
    m.box("blade_tip", (7.25, 26.5, 7.5), (8.75, 27.5, 8.5), PINK)
    return _held(m, "sword")


def _candy(m, side, body, pleat):
    """A wrapped hard candy for one arm of the pickaxe: drooping 22.5 degrees from the middle ball, a rounded outer
    end, the wrapper's crinkled twist and pleated fan beyond it (the pick's tip)."""
    px, py = C + side * 1.75, 18.0
    rot = ("z", -22.5 * side, (px, py, C))

    def box(name, a, b, y0, y1, z0, z1, mat):
        x0, x1 = (px + a, px + b) if side > 0 else (px - b, px - a)
        m.box(name, (x0, y0, z0), (x1, y1, z1), mat, rotation=rot)

    box("candy", 0, 4.25, 16.25, 19.75, 6.75, 9.25, body)
    box("candy_end", 4.25, 5, 16.75, 19.25, 7, 9, body)
    box("twist", 5, 6, 17.25, 18.75, 7.25, 8.75, pleat)
    box("fan", 6, 7.5, 15, 21, 7.5, 8.5, pleat)


def candy_pickaxe():
    """사탕 곡괭이: a pink and a lemon striped wrapped candy drooping off a sugared mint ball, crinkled wrapper fans for tips."""
    m = MModel("candy_pickaxe")
    handle(m, LEMON, "pink")
    stick(m, 17)
    m.group("head")
    _candy(m, 1, LEMON_STRIPE, LEMON_PLEAT)
    _candy(m, -1, PINK_STRIPE, PINK_PLEAT)
    round_y(m, "ball", 5.75, 10.25, 6.5, 9.5, 15.75, 20.25, MINT_SUGAR, 1)
    return _held(m, "pickaxe")


def candy_shovel():
    """사탕 삽: a big wafer scoop on a white stick, its end dipped in sprinkled pink icing with drips, a cherry on top."""
    m = MModel("candy_shovel")
    handle(m, MINT, "lemon")
    stick(m, 15.5)
    m.group("blade")
    edge = [0, 0, 6, 16]
    wafer = {"north": "candy_waffle", "south": "candy_waffle", "east": "candy_wafer_v", "west": "candy_wafer_v",
             "up": "candy_wafer_h", "down": "candy_wafer_h"}
    m.box("neck", (6.25, 15.5, 7), (9.75, 16.75, 9), WAFER)
    m.box("wafer", (3.75, 16.75, 6.5), (12.25, 23.5, 9.5), WAFER, faces=wafer,
          uv={"east": [0, 0, 6, 6.75], "west": [0, 0, 6, 6.75], "up": [0, 0, 8.5, 6], "down": [0, 0, 8.5, 6]})
    m.group("icing")
    m.box("icing", (3.5, 23.5, 6.25), (12.5, 26.25, 9.75), PINK_SUGAR)
    m.box("icing_end", (5, 26.25, 6.5), (11, 27.5, 9.5), PINK_SUGAR)
    for x0, y0 in ((4.25, 22), (7, 21.25), (10.25, 22.5)):
        m.box("drip", (x0, y0, 9.5), (x0 + 1.25, 23.5, 9.75), PINK)
        m.box("drip", (x0, y0 + 0.5, 6.25), (x0 + 1.25, 23.5, 6.5), PINK)
    m.group("cherry")
    m.box("cherry", (6.75, 27.5, 7), (9.25, 29.5, 9), CHERRY)
    m.box("cherry_stem", (8.25, 29.5, 7.75), (8.75, 31, 8.25), STEM, rotation=("z", -22.5, (8.5, 29.5, C)))
    return _held(m, "shovel")


def candy_axe():
    """사탕 도끼: a chocolate bar for a head, a bite out of its edge (towards -x) showing the caramel layer, its back half
    in a crinkled pink wrapper with a label and a crimped end, a lemon foil rim."""
    m = MModel("candy_axe")
    handle(m, PINK, "mint")
    stick(m, 22)
    m.group("head")
    inside = {"north": "candy_choc_bar", "south": "candy_choc_bar", "up": "candy_choc_in_h", "west": "candy_choc_in_v"}
    # the bitten edge: shorter columns at the top left, a round dent
    for x0, x1, top in ((0, 1.25, 19.25), (1.25, 2.5, 20.75), (2.5, 3.75, 22), (3.75, 8, 23)):
        f = dict(inside)
        u = {"up": [0, 0, x1 - x0, 6], "west": [0, 0, 6, min(16, top - 13)]}
        m.box("bar", (x0, 13, 6.75), (x1, top, 9.25), CHOC, faces=f, uv=u)
    m.box("bar_bottom", (0.5, 12.55, 7), (7.5, 13, 9), CHOC)
    m.group("wrapper")
    m.box("foil", (6.5, 12.5, 6.5), (7.25, 23.5, 9.5), FOIL)
    m.box("wrapper", (7.25, 12.25, 6.25), (11.5, 23.5, 9.75), PINK_LABEL)
    m.box("wrapper_end", (7.5, 23.5, 6.75), (11.25, 24.5, 9.25), PINK_PLEAT)
    m.box("wrapper_end", (7.5, 11.5, 6.75), (11.25, 12.25, 9.25), PINK_PLEAT)
    return _held(m, "axe")


def candy_hoe():
    """사탕 괭이: like a vanilla hoe, a straight head out to one side (-x) at the top of the handle, ending flat: a pink
    candy-stick bar with a lemon candy end, and a translucent-looking mint gummy bear sitting on it near the end, facing
    out (+z), its feet over the bar's front."""
    m = MModel("candy_hoe")
    handle(m, LEMON, "pink")
    stick(m, 22.25)
    m.group("head")
    m.box("bar", (1, 19.5, 6.875), (9.25, 22.5, 9.125), PINK_RING)
    m.box("bar_end", (0, 19.25, 6.625), (1, 22.75, 9.375), LEMON)
    m.group("bear")
    m.box("body", (1.25, 22.5, 7), (5.25, 25.25, 9), GUMMY)
    m.box("head", (0.75, 25.25, 6.875), (5.75, 29, 9.125), GUMMY, faces={"north": GUMMY_FACE, "south": GUMMY_FACE})
    m.box("ear", (0.75, 29, 7.375), (2, 30, 8.625), GUMMY)
    m.box("ear", (4.5, 29, 7.375), (5.75, 30, 8.625), GUMMY)
    m.box("feet", (1.75, 21.5, 9), (3, 23, 10), GUMMY)
    m.box("feet", (3.5, 21.5, 9), (4.75, 23, 10), GUMMY)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    handle(m, PINK, "lemon")
    m.group("reel")
    # a lemon lollipop for a reel beside the rod's foot (+x, where the line hangs), its swirl on both faces
    m.box("reel_foot", (9, 7.75, 7.5), (10.25, 9.25, 8.5), STICK)
    cyl(m, "reel", "z", (12.25, 8.5), 5, 6.75, 9.25, LEMON, cap="candy_swirl_lemon")
    m.group("rod")
    m.box("rod", (6.75, 6.5, 6.75), (9.25, 14, 9.25), MINT_RING)
    m.box("rod", (7, 14, 7), (9, 23, 9), MINT_RING)
    m.box("rod", (7.25, 23, 7.25), (8.75, 30.25, 8.75), MINT_RING)
    m.box("guide", (6.75, 18, 6.75), (9.25, 18.75, 9.25), PINK)
    m.box("guide", (7, 26, 7), (9, 26.75, 9), PINK)
    m.box("rod_tip", (6.75, 30.25, 6.75), (9.25, 31.75, 9.25), PINK)
    if not cast:
        # the line hangs from the tip (45 degrees in this frame = straight down on the sprite's diagonal), a sugared
        # two-colour gummy worm wriggling on its end
        m.group("worm")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.125, 22, 7.625), (8.875, 31, 8.375), STICK, rotation=rot)
        m.box("worm", (7.5, 20, 7.25), (10, 22, 8.75), PINK_SUGAR, rotation=rot)
        m.box("worm", (6.25, 18.25, 7.25), (8.5, 20.25, 8.75), LEMON_SUGAR, rotation=rot)
        m.box("worm", (7.5, 16.25, 7.25), (9.75, 18.5, 8.75), PINK_SUGAR, rotation=rot)
        m.box("worm", (6.75, 15, 7.5), (8.5, 16.25, 8.5), LEMON_SUGAR, rotation=rot)
    return _held(m, "rod")


def candy_fishing_rod():
    """사탕 낚싯대: a mint-striped candy stick, a lemon lollipop reel, a sugared gummy worm on the line."""
    return _rod("candy_fishing_rod", False)


def candy_fishing_rod_cast():
    """The rod while its line is out: the same without the line and the worm."""
    m = _rod("candy_fishing_rod_cast", True)
    m.display = candy_fishing_rod().display     # framed as the rod with its worm: the inventory picture does not jump
    return m


ALL = {f.__name__: f for f in (candy_sword, candy_pickaxe, candy_shovel, candy_axe, candy_hoe, candy_fishing_rod,
                               candy_fishing_rod_cast)}

ROWS = [("candy_sword", "사탕 검"), ("candy_pickaxe", "사탕 곡괭이"), ("candy_shovel", "사탕 삽"), ("candy_axe", "사탕 도끼"),
        ("candy_hoe", "사탕 괭이"), ("candy_fishing_rod", "사탕 낚싯대"), ("candy_fishing_rod_cast", "낚싯대 (던진 뒤)")]

TITLE = "사탕 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("candy_sword", "candy_pickaxe", "candy_shovel", "candy_axe", "candy_hoe")}
    out["candy_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/candy_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/candy_fishing_rod_cast"},
    }}
    return out
