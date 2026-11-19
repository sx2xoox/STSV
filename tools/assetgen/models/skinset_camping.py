"""
캠핑 시리즈 (camping series): a light tool skin set (bought with in-game gold) in the toy series' frame (models/toys.py).

    camping_sword  camping_pickaxe  camping_shovel  camping_axe  camping_hoe  camping_fishing_rod (+ camping_fishing_rod_cast)

Modern outdoor gear, simple in concept and rich in detail: matte olive-green and safety-orange powder-coated metal, scuffed
and chipped at the edges down to bare metal, brushed aluminium shafts, ground bright bevels on every cutting edge, a black
ribbed rubber grip with two woven orange paracord wraps, a paracord lanyard loop at the pommel with an aluminium carabiner
(orange gate) clipped to it, and orange/black warning-stripe bands. About three quarters of a toy piece's boxes: the
detail is painted, at two texels a model unit (GMat, built on premium_art.HMat's 32 px painting).

Same frame as the toys: built upright about x = z = 8, the hand on y = GRIP_Y, the views from toys._held().
"""

import math
import random
import zlib

from economy_machines import MModel, cyl
from premium_art import HMat, HS, hd_canvas, shade
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp

from models.toys import C, _arm, _held


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent) and materials
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("camp_olive", 118, 0.052, 0.50, 0.34, 5, False)     # olive drab powder coat
_ramp("camp_orange", 46, 0.140, 0.66, 0.32, 5, False)     # safety orange
_ramp("camp_alu", 250, 0.012, 0.74, 0.40, 5, False)       # brushed aluminium, bare metal
_ramp("camp_black", 265, 0.012, 0.32, 0.24, 5, False)     # black rubber, the warning stripes' black


class GMat(HMat):
    """
    Painted outdoor-gear materials, two texels a model unit (HMat's shading: face light, rims, a gradient down a side).
    kind:
      coat     matte powder coat: a fine stipple, a few light scuff strokes, chips along the face's edges showing bare
               aluminium (a darker ring round each chip)
      brushed  aluminium brushed along the face's longer side: a streak a texel row, a soft highlight band
      edge     a ground cutting bevel: brushed, brighter, a hot line along the face's top/left
      rubber   grip rubber: horizontal ridges every three texels (sides only)
      cord     woven paracord: a herringbone of strands, a black tracer fleck now and then
      hazard   warning stripes: diagonal bands of the ramp and black
      gate     the carabiner's gate: the coat with a dark keylock notch at the top and a light spring line
    """

    def __init__(self, ramp_name, kind, base, contrast=1.0, **opt):
        super().__init__(ramp_name, kind, base, contrast=contrast, ao=False, **opt)

    def __call__(self, **changes):        # cyl() asks for side(bevel=None): the same paint
        return self

    def _texel(self, x, y, pw, ph, face, rnd):
        v = HMat._value(self, x, y, pw, ph, face, rnd)
        k, side = self.kind, face not in ("up", "down")
        if k == "coat" or k == "gate":
            if rnd.random() < 0.10:
                v -= 0.3
        elif k in ("brushed", "edge"):
            v += self._streak[y if pw >= ph else x]
            u = (x + 0.5) / pw
            v += 0.5 * math.exp(-((u - 0.3) / 0.18) ** 2) if ph >= pw else 0
            if k == "edge" and pw >= 2 and side:
                v += -0.9 if x == 0 else 0.6 if x == pw - 1 else 0.2      # the grind line, then the hot edge
        elif k == "rubber" and side:
            r = y % 3
            v += -0.9 if r == 2 else 0.35 if r == 0 else 0
        elif k == "cord":
            band = (y // 2) % 2
            t = (x + y) % 4 if band == 0 else (x - y) % 4
            v += 0.55 if t == 0 else -0.55 if t == 3 else 0.1
            if y % 2 == 1 and t == 3:
                v -= 0.25
        elif k == "hazard":
            if ((x + y) // 3) % 2:
                return ("camp_black", 1.0 + (v - self.base) * 0.6)
        if k == "gate" and side:
            if y <= 1 and 0 < x < pw - 1:
                v -= 1.1
            elif x == pw // 2:
                v += 0.6
        return (self.ramp, v)

    def _paint(self, pw, ph, face, seed, sweep=None):
        rnd = random.Random(seed ^ zlib.crc32(f"{self.kind}{self.ramp}{self.base}".encode()))
        self._streak = [rnd.uniform(-0.35, 0.35) for _ in range(HS)]
        g = [[self._texel(x, y, pw, ph, face, rnd) for x in range(pw)] for y in range(ph)]
        if self.kind == "coat" and pw >= 4 and ph >= 4 and self.opt.get("chips", 0.05):
            # scuffs: short light diagonal strokes inside the face
            for _ in range(max(1, pw * ph // 90)):
                x, y = rnd.randrange(1, pw - 1), rnd.randrange(1, ph - 1)
                dx = rnd.choice((1, -1))
                for i in range(rnd.randrange(2, 5)):
                    xx, yy = x + i * dx, y + i
                    if 0 < xx < pw - 1 and 0 < yy < ph - 1 and g[yy][xx][0] == self.ramp:
                        g[yy][xx] = (self.ramp, g[yy][xx][1] + 0.6)
            # chips along the edges: bare metal, a darker ring of paint round each
            chips = []
            for x in range(pw):
                for y in (0, ph - 1):
                    if rnd.random() < self.opt.get("chips", 0.05):
                        chips.append((x, y))
            for y in range(1, ph - 1):
                for x in (0, pw - 1):
                    if rnd.random() < self.opt.get("chips", 0.05):
                        chips.append((x, y))
            for x, y in chips:
                cells = [(x, y)] + ([(x + rnd.choice((-1, 1)), y)] if rnd.random() < 0.5 else [])
                for cx, cy in cells:
                    if 0 <= cx < pw and 0 <= cy < ph:
                        for nx, ny in ((cx + 1, cy), (cx - 1, cy), (cx, cy + 1), (cx, cy - 1)):
                            if 0 <= nx < pw and 0 <= ny < ph and g[ny][nx][0] == self.ramp:
                                g[ny][nx] = (self.ramp, g[ny][nx][1] - 0.45)
                for cx, cy in cells:
                    if 0 <= cx < pw and 0 <= cy < ph:
                        g[cy][cx] = ("camp_alu", 2.3 if (cx + cy) % 2 else 1.9)
        im = hd_canvas()
        px = im.load()
        for y in range(HS):
            for x in range(HS):
                r, v = g[min(y, ph - 1)][min(x, pw - 1)]
                px[x, y] = shade(r, v)
        return im


OLIVE = GMat("camp_olive", "coat", 2.0)
OLIVE_DARK = GMat("camp_olive", "coat", 1.3)
ORANGE = GMat("camp_orange", "coat", 2.0, chips=0.025)
GATE = GMat("camp_orange", "gate", 2.0, chips=0)
ALU = GMat("camp_alu", "brushed", 2.2)
BRIGHT = GMat("camp_alu", "edge", 3.0)            # ground bevels, the carabiner's body
BOLT = GMat("camp_alu", "edge", 2.6, contrast=1.3)
BLACK = GMat("camp_black", "coat", 1.6, chips=0)
RUBBER = GMat("camp_black", "rubber", 1.8)
CORD = GMat("camp_orange", "cord", 2.0)
HAZARD = GMat("camp_orange", "hazard", 2.2)
LINE = GMat("camp_alu", "brushed", 3.4, contrast=0.4)


# ================================================================================================
# What every piece shares
# ================================================================================================

def handle(m):
    """A black ribbed rubber grip (y -1.25 .. 5.25) with two woven orange paracord wraps, an aluminium end cap and collar,
    a paracord lanyard loop out of the cap and an aluminium carabiner with an orange gate clipped to it (a loop in the
    x-y plane, so the inventory sees it open)."""
    m.group("handle")
    m.box("grip", (6.75, -1.25, 6.75), (9.25, 5.25, 9.25), RUBBER)
    for y in (-0.75, 3.5):
        m.box("cord", (6.5, y, 6.5), (9.5, y + 1.5, 9.5), CORD)
    m.box("cap", (6.5, -2.25, 6.5), (9.5, -1.25, 9.5), ALU)
    m.box("collar", (6.5, 5.25, 6.5), (9.5, 6.25, 9.5), ALU)
    m.group("carabiner")
    m.box("lanyard", (7.375, -3.25, 7.5), (8.625, -2.25, 8.5), CORD)
    m.box("clip", (6.5, -3.75, 7.625), (9.5, -3.25, 8.375), BRIGHT)     # the spine the gate closes on
    m.box("clip", (6.5, -6.25, 7.625), (7.25, -3.75, 8.375), BRIGHT)
    m.box("clip", (8.75, -5.75, 7.5), (9.5, -3.75, 8.5), GATE)          # the gate: orange, its keylock notch on top
    m.box("clip", (6.5, -7, 7.625), (9.5, -6.25, 8.375), BRIGHT)


def shaft(m, top, stripe=None, mat=ALU):
    """The brushed shaft above the collar, up to y = top; stripe: y of an orange and black warning band round it."""
    m.group("shaft")
    m.box("shaft", (7, 6.25, 7), (9, top, 9), mat)
    if stripe is not None:
        m.box("stripe", (6.875, stripe, 6.875), (9.125, stripe + 1.5, 9.125), HAZARD)


def tooth(m, name, x, y, mat, size=1.0, z=(7.625, 8.375)):
    """A saw tooth: a small square turned 45 degrees about its centre (x, y), half sunk into the part beside it."""
    h = size / 2
    m.box(name, (x - h, y - h, z[0]), (x + h, y + h, z[1]), mat, rotation=("z", 45, (x, y, C)))


# ================================================================================================
# The tools
# ================================================================================================

def camping_sword():
    """캠핑 마체테: a broad olive machete blade widening to a clipped tip, a ground bright edge on +x, a saw-back of three
    teeth on the spine, an orange guard with a warning stripe."""
    m = MModel("camping_sword")
    handle(m)
    m.group("guard")
    m.box("guard", (5, 6.25, 6.75), (11, 7.5, 9.25), HAZARD)
    m.group("blade")
    m.box("blade", (5.5, 7.5, 7.5), (10.25, 14, 8.5), OLIVE)
    m.box("blade", (5, 14, 7.5), (10.75, 21, 8.5), OLIVE)
    m.box("edge", (10.25, 8.25, 7.625), (11.25, 14, 8.375), BRIGHT)
    m.box("edge", (10.75, 14, 7.625), (11.75, 22.5, 8.375), BRIGHT)
    # the clipped point: the spine (-x) steps away, the edge sweeps up to it
    m.box("tip", (6.5, 21, 7.5), (10.75, 23, 8.5), OLIVE)
    m.box("tip", (9, 22.5, 7.625), (11.25, 25.5, 8.375), BRIGHT)
    m.group("saw")
    for y in (15.25, 17, 18.75):
        tooth(m, "saw", 5, y, BRIGHT, 1.1)
    return _held(m, "sword")


def camping_pickaxe():
    """캠핑 곡괭이: an ice-axe head: an orange block bolted to a brushed shaft (a warning band under it), an olive pick
    drooping to +x with a ground tip, a flat olive adze to -x with a bright edge."""
    m = MModel("camping_pickaxe")
    handle(m)
    shaft(m, 18, stripe=14.5)
    m.group("head")
    m.box("head", (6.25, 17, 6.5), (9.75, 21, 9.5), ORANGE)
    m.box("bolt", (7.375, 18.25, 9.5), (8.625, 19.5, 9.875), BOLT)
    p = _arm(m, "pick", 1, (9.75, 19), 4.5, 1.25, 7, 9, 22.5, OLIVE)
    p = _arm(m, "pick", 1, p, 3.0, 1.0, 7.25, 8.75, 45, OLIVE)
    px, py = p
    m.box("pick_tip", (px + 0.5, py - 0.75, 7.5), (px + 2, py + 0.75, 8.5), BRIGHT, rotation=("z", -45, (px, py, C)))
    m.box("adze", (3, 18.5, 7.25), (6.25, 20.25, 8.75), OLIVE)
    m.box("adze_edge", (1.75, 17.75, 6.5), (3, 20.5, 9.5), BRIGHT)
    return _held(m, "pickaxe")


def camping_shovel():
    """캠핑 삽: a folding camp shovel: an orange hinge collar with two bolt heads on the brushed shaft, a warning band, a
    square-shouldered olive spade with a pointed end, one side ground bright, the other a saw edge."""
    m = MModel("camping_shovel")
    handle(m)
    shaft(m, 15, stripe=8)
    m.group("hinge")
    m.box("hinge", (6.25, 11, 6.75), (9.75, 13.25, 9.25), ORANGE)
    m.box("bolt", (6.75, 11.5, 9.25), (7.75, 12.5, 9.625), BOLT)
    m.box("bolt", (8.25, 11.75, 9.25), (9.25, 12.75, 9.625), BOLT)
    m.group("blade")
    m.box("socket", (6.75, 15, 6.75), (9.25, 16.5, 9.25), OLIVE_DARK)
    m.box("blade", (4, 16.5, 7.5), (12, 23, 8.5), OLIVE)
    m.box("blade", (5.25, 23, 7.5), (10.75, 25, 8.5), OLIVE)
    m.box("blade", (6.5, 25, 7.5), (9.5, 26.5, 8.5), OLIVE)
    m.box("point", (7.25, 26.5, 7.625), (8.75, 27.75, 8.375), BRIGHT)
    m.box("edge", (12, 17, 7.625), (12.5, 23, 8.375), BRIGHT)
    m.group("saw")
    for y in (18, 19.75, 21.5):
        tooth(m, "saw", 4, y, BRIGHT, 1.1)
    return _held(m, "shovel")


def camping_axe():
    """캠핑 손도끼: a hatchet: an orange head on a brushed shaft (a warning band under it), widening to a ground bright
    bearded edge towards -x, an olive poll behind, a bolt through the eye."""
    m = MModel("camping_axe")
    handle(m)
    shaft(m, 21, stripe=12)
    m.group("head")
    m.box("head", (5, 15.5, 6.5), (10, 20.5, 9.5), ORANGE)
    m.box("bolt", (6.875, 17.375, 9.5), (8.125, 18.625, 9.875), BOLT)
    m.box("cheek", (2.25, 14, 6.875), (5, 21, 9.125), ORANGE)
    m.box("edge", (1, 13, 7.25), (2.25, 21.75, 8.75), BRIGHT)
    m.box("edge", (1.5, 12.25, 7.375), (2.25, 13, 8.625), BRIGHT)        # the beard's hook
    m.box("poll", (10, 16.25, 6.75), (11.75, 19.75, 9.25), OLIVE)
    m.box("shaft_cap", (6.75, 21, 6.75), (9.25, 21.75, 9.25), OLIVE_DARK)
    return _held(m, "axe")


def camping_hoe():
    """캠핑 괭이: the vanilla hoe's L: a straight head sticking out to -x at the top of the brushed shaft (a warning band
    under it), ending flat: an olive neck bolted over the shaft, an orange blade, a ground bright flat end."""
    m = MModel("camping_hoe")
    handle(m)
    shaft(m, 22.5, stripe=12)
    m.group("head")
    m.box("neck", (5.25, 19.75, 6.875), (9.5, 22.5, 9.125), OLIVE)
    m.box("bolt", (7.375, 20.5, 9.125), (8.625, 21.75, 9.5), BOLT)
    m.box("blade", (-1, 19.5, 6.5), (5.25, 22.75, 9.5), ORANGE)
    m.box("blade_end", (-2, 19.5, 6.75), (-1, 22.75, 9.25), BRIGHT)
    m.box("shaft_cap", (6.75, 22.5, 6.75), (9.25, 23.25, 9.25), OLIVE_DARK)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    # a small spinning reel on the +x side (where the line hangs): a brushed spool with an orange rim, a black crank
    m.box("reel_foot", (9, 7.5, 7.5), (10.25, 9.5, 8.5), BLACK)
    cyl(m, "spool", "x", (8.5, C), 4, 10.25, 12, ALU, cap=BRIGHT)
    cyl(m, "spool_rim", "x", (8.5, C), 4, 12, 12.75, ORANGE, cap=ORANGE)
    m.box("crank", (11, 8, 10), (11.75, 11.25, 10.75), BLACK)
    m.box("crank_knob", (10.75, 10.5, 10.75), (12, 11.75, 11.75), ORANGE)
    m.group("rod")
    # telescopic: three black sections, each a step thinner, orange ferrules at the joints, an orange tip
    m.box("rod", (6.875, 6.25, 6.875), (9.125, 15, 9.125), BLACK)
    m.box("rod", (7.125, 15, 7.125), (8.875, 23, 8.875), BLACK)
    m.box("rod", (7.375, 23, 7.375), (8.625, 30.25, 8.625), BLACK)
    m.box("ferrule", (6.625, 14.25, 6.625), (9.375, 15.25, 9.375), HAZARD)
    m.box("ferrule", (6.875, 22.5, 6.875), (9.125, 23.5, 9.125), ORANGE)
    m.box("rod_tip", (7.25, 30.25, 7.25), (8.75, 31.75, 8.75), ORANGE)
    m.box("guide", (8.875, 18.5, 7.5), (10, 19.5, 8.5), BRIGHT)
    if not cast:
        # the line hangs from the tip (45 degrees in this frame = straight down on the sprite), a small orange lure on it
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.25, 21, 7.75), (8.75, 31, 8.25), LINE, rotation=rot)
        m.box("lure", (7.25, 18.25, 7.25), (9.75, 21, 8.75), ORANGE, rotation=rot)
        m.box("hook", (7.75, 17, 7.5), (9.25, 18.25, 8.5), BRIGHT, rotation=rot)
    return _held(m, "rod")


def camping_fishing_rod():
    """캠핑 낚싯대: a black telescopic rod with orange ferrules, a small spinning reel, a lure on the line."""
    return _rod("camping_fishing_rod", False)


def camping_fishing_rod_cast():
    """The rod while its line is out: the same without the line and the lure."""
    m = _rod("camping_fishing_rod_cast", True)
    m.display = camping_fishing_rod().display     # the inventory picture does not jump on a cast
    return m


ALL = {f.__name__: f for f in (camping_sword, camping_pickaxe, camping_shovel, camping_axe, camping_hoe, camping_fishing_rod,
                               camping_fishing_rod_cast)}

ROWS = [
    ("camping_sword", "캠핑 마체테"), ("camping_pickaxe", "캠핑 곡괭이"), ("camping_shovel", "캠핑 삽"), ("camping_axe", "캠핑 손도끼"),
    ("camping_hoe", "캠핑 괭이"), ("camping_fishing_rod", "캠핑 낚싯대"), ("camping_fishing_rod_cast", "낚싯대 (던진 뒤)"),
]

TITLE = "캠핑 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("camping_sword", "camping_pickaxe", "camping_shovel", "camping_axe", "camping_hoe")}
    out["camping_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/camping_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/camping_fishing_rod_cast"},
    }}
    return out
