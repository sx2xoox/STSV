"""
캠핑 시리즈 (camping series): a light tool skin set (bought with in-game gold) in the toy series' frame (models/toys.py).

    camping_sword  camping_pickaxe  camping_shovel  camping_axe  camping_hoe  camping_fishing_rod (+ camping_fishing_rod_cast)

Real outdoor gear, each piece the thing a camper actually packs (original designs, no brands):
    camping_sword        a long machete: a black-coated blade with a ground steel edge, a hooked tip, an orange rubber
                         grip with finger ridges and two rivets, a hook pommel and a paracord lanyard loop
    camping_pickaxe      a folding entrenching pick: a polished steel head (one point, one flat adze) on a hinge collar,
                         an olive shaft, a black grip with orange bands
    camping_shovel       a folding camp shovel: a pointed steel spade with a saw edge on one side, a knurled locking
                         ring over the hinge, an olive shaft and a D-handle at the end
    camping_axe          a hatchet: a polished steel head, a stitched brown leather sheath over the lower half of the edge
                         with a strap and a snap, a wooden handle wrapped in orange paracord
    camping_hoe          a tent-peg mallet hoe: the vanilla hoe's straight ㄱ head in steel with a peg-puller slot and a
                         copper mallet face behind, an orange shaft
    camping_fishing_rod  a telescopic travel rod: graphite sections with ferrules, ring guides, a spinning reel with a
                         crank, an orange cork grip and a little lantern charm on the butt

Detail is painted at two texels a model unit (GMat, on premium_art.HMat's 32 px shading): powder coat with small chips
to bare steel, brushed and polished steel with a highlight band, ground edges with a hot line on the cutting side,
ribbed rubber, woven paracord, wood grain, stitched leather, knurling and cork.

Same frame as the toys: built upright about x = z = 8, the hand on y = GRIP_Y, the views from toys._held(). A tool's
business end faces -x (the hoe's head, the axe's and the machete's edge: down in third person, like vanilla's).
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


_ramp("camp_olive", 118, 0.050, 0.47, 0.30, 5, False)     # olive drab powder coat
_ramp("camp_orange", 46, 0.150, 0.66, 0.34, 5, False)     # safety orange
_ramp("camp_steel", 245, 0.014, 0.70, 0.52, 5, False)     # steel, bare and polished
_ramp("camp_black", 265, 0.010, 0.27, 0.22, 5, False)     # black coat, rubber
_ramp("camp_wood", 62, 0.075, 0.58, 0.32, 5, False)       # ash handle
_ramp("camp_leather", 48, 0.085, 0.42, 0.30, 5, False)    # tan-brown leather
_ramp("camp_copper", 42, 0.120, 0.60, 0.36, 5, False)     # copper mallet face
_ramp("camp_cork", 68, 0.085, 0.68, 0.26, 5, False)       # cork, warm orange-tan
_ramp("camp_amber", 82, 0.140, 0.74, 0.34, 5, False)      # the lantern's glass


class GMat(HMat):
    """
    Painted outdoor-gear materials, two texels a model unit (HMat's shading: face light, rims, a gradient down a side).
    kind:
      coat     matte powder coat: a fine stipple, a few light scuff strokes, chips along the face's edges down to steel
      brushed  steel brushed along the face's longer side: a streak a texel row, a soft highlight band
      polish   polished steel: the brushing finer and a strong highlight band, a dark band under it
      edge     a ground cutting bevel: polished, brighter; opt out="w"/"e": a hot line on that side (model x), the
               grind line on the other
      rubber   grip rubber: horizontal ridges every three texels (sides only)
      cord     woven paracord: a herringbone of strands
      knurl    a knurled ring: a diamond cross-hatch
      grain    wood: long grain lines along the face, a knot now and then
      leather  leather: a soft mottle, a light dashed stitch line one texel in from every edge of a side
      cork     cork: light and dark specks
      glass    the lantern's glass: a warm core, a light streak
    """

    def __init__(self, ramp_name, kind, base, contrast=1.0, **opt):
        super().__init__(ramp_name, kind, base, contrast=contrast, ao=False, **opt)

    def __call__(self, **changes):        # cyl() asks for side(bevel=None): the same paint
        return self

    def _texel(self, x, y, pw, ph, face, rnd):
        v = HMat._value(self, x, y, pw, ph, face, rnd)
        k, side = self.kind, face not in ("up", "down")
        long_y = ph > pw
        if k == "coat":
            if rnd.random() < 0.10:
                v -= 0.25
        elif k in ("brushed", "polish", "edge"):
            v += self._streak[x if long_y else y] * (0.6 if k != "brushed" else 1.0)
            u = ((x + 0.5) / pw) if long_y else ((y + 0.5) / ph)
            if k == "brushed":
                v += 0.45 * math.exp(-((u - 0.3) / 0.18) ** 2)
            else:
                v += 0.9 * math.exp(-((u - 0.28) / 0.13) ** 2) - 0.5 * math.exp(-((u - 0.62) / 0.12) ** 2)
            if k == "edge" and side and pw >= 2:
                out = self.opt.get("out")
                hot_x = 0 if (out == "w" and face == "south") or (out == "e" and face == "north") else pw - 1
                if out is None or face in ("east", "west"):
                    hot_x = None
                if hot_x is not None:
                    if x == hot_x:
                        v += 1.1
                    elif x == pw - 1 - hot_x:
                        v -= 0.9                               # the grind line
        elif k == "rubber" and side:
            r = y % 3
            v += -0.9 if r == 2 else 0.35 if r == 0 else 0
        elif k == "cord":
            band = (y // 2) % 2
            t = (x + y) % 4 if band == 0 else (x - y) % 4
            v += 0.55 if t == 0 else -0.55 if t == 3 else 0.1
            if y % 2 == 1:
                v -= 0.3
        elif k == "knurl":
            if (x + y) % 3 == 0 or (x - y) % 3 == 0:
                v -= 0.75
            else:
                v += 0.3
        elif k == "grain":
            w = self._streak[x if long_y else y]
            v += w * 1.3
            if (x if long_y else y) % 3 == 1:
                v -= 0.35
        elif k == "leather":
            v += (rnd.random() - 0.5) * 0.35
            if side and pw >= 6 and ph >= 6:
                on_edge = x in (1, pw - 2) and 1 <= y <= ph - 2 or y in (1, ph - 2) and 1 <= x <= pw - 2
                if on_edge and (x + y) % 2 == 0:
                    return ("camp_cork", 3.6)                  # light waxed thread
        elif k == "cork":
            r = rnd.random()
            v += -0.8 if r < 0.12 else 0.55 if r > 0.9 else 0
        elif k == "glass":
            v += 0.8 * math.exp(-(((x + 0.5) / pw - 0.3) / 0.15) ** 2)
        return (self.ramp, v)

    def _paint(self, pw, ph, face, seed, sweep=None):
        rnd = random.Random(seed ^ zlib.crc32(f"{self.kind}{self.ramp}{self.base}".encode()))
        self._streak = [rnd.uniform(-0.35, 0.35) for _ in range(HS)]
        g = [[self._texel(x, y, pw, ph, face, rnd) for x in range(pw)] for y in range(ph)]
        chip = self.opt.get("chips", 0.03)
        if self.kind == "coat" and pw >= 4 and ph >= 4 and chip:
            # chips along the edges: bare steel, a darker ring of paint round each
            chips = [(x, y) for x in range(pw) for y in (0, ph - 1) if rnd.random() < chip]
            chips += [(x, y) for y in range(1, ph - 1) for x in (0, pw - 1) if rnd.random() < chip]
            for cx, cy in chips:
                for nx, ny in ((cx + 1, cy), (cx - 1, cy), (cx, cy + 1), (cx, cy - 1)):
                    if 0 <= nx < pw and 0 <= ny < ph and g[ny][nx][0] == self.ramp:
                        g[ny][nx] = (self.ramp, g[ny][nx][1] - 0.4)
            for cx, cy in chips:
                g[cy][cx] = ("camp_steel", 2.6)
        im = hd_canvas()
        px = im.load()
        for y in range(HS):
            for x in range(HS):
                r, v = g[min(y, ph - 1)][min(x, pw - 1)]
                px[x, y] = shade(r, v)
        return im


BLADE = GMat("camp_black", "coat", 1.7, chips=0.035)       # the machete's black coat
BLACK = GMat("camp_black", "coat", 1.4, chips=0)
BLACK_LIGHT = GMat("camp_black", "coat", 2.3, chips=0)
RUBBER = GMat("camp_black", "rubber", 1.8)
ORANGE = GMat("camp_orange", "coat", 2.0, chips=0.02)
ORANGE_DARK = GMat("camp_orange", "coat", 1.3, chips=0)
ORANGE_RUB = GMat("camp_orange", "rubber", 2.1)
OLIVE = GMat("camp_olive", "coat", 2.0, chips=0.02)
OLIVE_CORD = GMat("camp_olive", "cord", 2.2)
CORD = GMat("camp_orange", "cord", 2.1)
KNURL = GMat("camp_orange", "knurl", 2.1)
STEEL = GMat("camp_steel", "polish", 2.3)
STEEL_DARK = GMat("camp_steel", "brushed", 1.4)
EDGE_W = GMat("camp_steel", "edge", 3.0, out="w")         # a ground edge cutting towards -x
EDGE_E = GMat("camp_steel", "edge", 3.0, out="e")
BRIGHT = GMat("camp_steel", "edge", 3.1)
BOLT = GMat("camp_steel", "polish", 2.9, contrast=1.3)
WOOD = GMat("camp_wood", "grain", 2.1)
WOOD_END = GMat("camp_wood", "grain", 2.9)
LEATHER = GMat("camp_leather", "leather", 2.0)
LEATHER_DARK = GMat("camp_leather", "leather", 1.3)
COPPER = GMat("camp_copper", "polish", 2.2)
COPPER_FACE = GMat("camp_copper", "brushed", 2.9)
CORK = GMat("camp_cork", "cork", 2.2)
AMBER = GMat("camp_amber", "glass", 2.6)
LINE = GMat("camp_steel", "brushed", 3.6, contrast=0.4)


def faces_of(m):
    return sum(len(e["faces"]) for e in m.elements)


# ================================================================================================
# Shared bits
# ================================================================================================

def tooth(m, name, x, y, mat, size=1.0, z=(7.625, 8.375)):
    """A saw tooth: a small square turned 45 degrees about its centre (x, y), half sunk into the part beside it."""
    h = size / 2
    m.box(name, (x - h, y - h, z[0]), (x + h, y + h, z[1]), mat, rotation=("z", 45, (x, y, C)))


def rivet(m, name, x, y, z0, z1, mat=BOLT, s=0.5):
    """A rivet through a part, its heads showing on both z faces (z0, z1: just outside the part)."""
    m.box(name, (x - s, y - s, z0), (x + s, y + s, z1), mat)


def gear_grip(m, bands=True, top=5.25):
    """A black ribbed rubber grip (y -1.25 .. top) with two orange bands, a black end cap with a steel lanyard grommet,
    a short olive paracord loop under it."""
    m.group("grip")
    m.box("grip", (6.75, -1.25, 6.75), (9.25, top, 9.25), RUBBER)
    if bands:
        for y in (-0.5, 3.75):
            m.box("band", (6.625, y, 6.625), (9.375, y + 0.75, 9.375), ORANGE)
    m.box("cap", (6.5, -2.25, 6.5), (9.5, -1.25, 9.5), BLACK)
    rivet(m, "grommet", 8, -1.75, 6.375, 9.625, BOLT, 0.375)
    m.box("collar", (6.5, top, 6.5), (9.5, top + 0.75, 9.5), STEEL_DARK)
    m.group("lanyard")
    m.box("loop", (7.25, -4.25, 7.625), (7.75, -2.25, 8.375), OLIVE_CORD)
    m.box("loop", (8.25, -4.25, 7.625), (8.75, -2.25, 8.375), OLIVE_CORD)
    m.box("loop", (7.25, -4.75, 7.625), (8.75, -4.25, 8.375), OLIVE_CORD)


# ================================================================================================
# The tools
# ================================================================================================

def camping_sword():
    """캠핑 마체테: a long machete (a 24 unit blade): black-coated, a ground steel edge widening towards a belly and a
    forward hooked tip (cutting side -x), a raised steel ricasso plate, a thin ridge line by the spine; an orange
    rubber grip with three finger ridges, a palm swell and two rivets, a black finger guard, a hook pommel with a steel
    grommet and an olive paracord lanyard loop."""
    m = MModel("camping_sword")
    m.group("grip")
    m.box("grip", (6.75, -0.75, 6.75), (9.25, 5.75, 9.25), ORANGE_RUB)
    for y in (0.25, 1.875, 3.5):
        m.box("finger", (6.25, y, 7.0), (6.75, y + 0.875, 9.0), ORANGE_DARK)
    m.box("palm", (9.25, 1.0, 7.0), (9.625, 4.75, 9.0), ORANGE_DARK)
    rivet(m, "rivet", 8, 0.75, 6.5, 9.5)
    rivet(m, "rivet", 8, 4.25, 6.5, 9.5)
    m.box("pommel", (6.5, -2.0, 6.625), (9.75, -0.75, 9.375), BLACK)
    m.box("pommel_hook", (5.5, -2.5, 6.875), (6.5, -1.0, 9.125), BLACK)
    rivet(m, "grommet", 8.25, -1.375, 6.5, 9.5, BOLT, 0.375)
    m.box("guard", (6.25, 5.75, 6.625), (9.875, 6.75, 9.375), BLACK)
    m.box("guard_lip", (5.25, 5.5, 6.875), (6.25, 7.0, 9.125), BLACK_LIGHT)
    m.group("lanyard")
    m.box("loop", (7.5, -4.75, 7.625), (8.0, -2.0, 8.375), OLIVE_CORD)
    m.box("loop", (8.5, -4.75, 7.625), (9.0, -2.0, 8.375), OLIVE_CORD)
    m.box("loop", (7.5, -5.25, 7.625), (9.0, -4.75, 8.375), OLIVE_CORD)

    m.group("blade")
    # the edge's x along the blade: narrow at the guard, a belly towards the tip
    segs = [(6.75, 10.5, 6.25), (10.5, 14.5, 5.75), (14.5, 19, 5.25), (19, 23.5, 4.75), (23.5, 27, 4.5)]
    for y0, y1, xe in segs:
        m.box("blade", (xe + 0.75, y0, 7.5), (9.5, y1, 8.5), BLADE)
        m.box("edge", (xe, y0, 7.625), (xe + 1.0, y1, 8.375), EDGE_W)
    # the tip: the spine drops towards the edge, the edge runs on and hooks forward
    m.box("blade", (5.0, 27, 7.5), (9.0, 28.5, 8.5), BLADE)
    m.box("blade", (5.0, 28.5, 7.5), (7.75, 29.75, 8.5), BLADE)
    m.box("blade", (5.0, 29.75, 7.5), (6.5, 30.5, 8.5), BLADE)
    m.box("edge", (4.25, 27, 7.625), (5.25, 30.5, 8.375), EDGE_W)
    m.box("hook", (3.25, 30.0, 7.625), (5.5, 31.25, 8.375), BRIGHT)
    m.box("hook", (3.0, 30.5, 7.69), (3.75, 31.75, 8.31), BRIGHT)
    # a steel ricasso plate by the guard, a ridge line along the spine side
    m.box("ricasso", (7.0, 6.75, 7.375), (9.625, 8.25, 8.625), STEEL)
    m.box("ridge", (8.5, 9.0, 7.44), (8.875, 26.0, 8.56), BLACK_LIGHT)
    m.box("spine", (9.5, 8.25, 7.69), (9.75, 27.0, 8.31), STEEL_DARK)
    return _held(m, "sword")


def camping_pickaxe():
    """접이식 곡괭이: a folding entrenching pick: a polished steel head with a drooping point (+x) and a flat adze (-x)
    with ground edges, a black hinge collar with a steel pivot bolt and a knurled orange lock ring, an olive shaft,
    the black grip with orange bands and a lanyard loop."""
    m = MModel("camping_pickaxe")
    gear_grip(m)
    m.group("shaft")
    m.box("shaft", (7, 6.0, 7), (9, 15, 9), OLIVE)
    m.box("lock", (6.625, 12.5, 6.625), (9.375, 13.75, 9.375), KNURL)
    m.box("hinge", (6.75, 15, 6.5), (9.25, 17.75, 9.5), BLACK)
    rivet(m, "pivot", 8, 16.375, 6.25, 9.75, BOLT, 0.625)
    m.group("head")
    m.box("eye", (6.25, 17.75, 6.75), (9.75, 21.25, 9.25), STEEL)
    m.box("eye_cap", (7.0, 21.25, 7.0), (9.0, 21.75, 9.0), STEEL_DARK)
    # the point: two bends, a ground tip
    p = _arm(m, "pick", 1, (9.5, 19.5), 4.0, 1.125, 7.125, 8.875, 22.5, STEEL)
    p = _arm(m, "pick", 1, p, 3.5, 0.75, 7.375, 8.625, 45, STEEL)
    px, py = p
    m.box("pick_tip", (px + 0.25, py - 0.5, 7.5), (px + 2.0, py + 0.5, 8.5), EDGE_E, rotation=("z", -45, (px, py, C)))
    # the adze: a flat blade, wide in z, drooping a little, a tall ground edge at its end
    p = _arm(m, "adze", -1, (6.5, 19.5), 4.25, 1.0, 6.75, 9.25, 22.5, STEEL)
    px, py = p
    m.box("adze_edge", (px - 1.25, py - 1.75, 6.5), (px + 0.25, py + 1.25, 9.5), EDGE_W, rotation=("z", 22.5, (px, py, C)))
    return _held(m, "pickaxe")


def camping_shovel():
    """접이식 야전삽: a folding camp shovel: a pointed steel spade with a pressed centre rib, a saw edge on -x and a
    ground edge on +x, a steel socket with two rivets, a black hinge with a pivot bolt, a knurled orange locking
    ring, an olive shaft, the black grip and a D-handle at the end (orange rubber bar)."""
    m = MModel("camping_shovel")
    m.group("grip")
    m.box("grip", (6.75, -1.25, 6.75), (9.25, 5.25, 9.25), RUBBER)
    m.box("band", (6.625, 3.75, 6.625), (9.375, 4.5, 9.375), ORANGE)
    m.box("collar", (6.5, 5.25, 6.5), (9.5, 6.0, 9.5), STEEL_DARK)
    m.group("d_handle")
    m.box("d_neck", (6.5, -2.25, 6.75), (9.5, -1.25, 9.25), BLACK)
    m.box("d_side", (5.75, -3.25, 7.25), (6.75, -2.25, 8.75), BLACK, rotation=("z", -45, (6.25, -2.75, C)))
    m.box("d_side", (9.25, -3.25, 7.25), (10.25, -2.25, 8.75), BLACK, rotation=("z", 45, (9.75, -2.75, C)))
    m.box("d_side", (5.0, -6.0, 7.25), (6.0, -3.25, 8.75), BLACK)
    m.box("d_side", (10.0, -6.0, 7.25), (11.0, -3.25, 8.75), BLACK)
    m.box("d_bar", (5.0, -7.25, 7.0), (11.0, -6.0, 9.0), ORANGE_RUB)
    m.group("shaft")
    m.box("shaft", (7, 6.0, 7), (9, 13.5, 9), OLIVE)
    m.box("lock", (6.5, 11.0, 6.5), (9.5, 12.75, 9.5), KNURL)
    m.box("lock_rim", (6.625, 10.5, 6.625), (9.375, 11.0, 9.375), STEEL_DARK)
    m.box("hinge", (6.75, 13.5, 6.5), (9.25, 15.75, 9.5), BLACK)
    rivet(m, "pivot", 8, 14.625, 6.25, 9.75, BOLT, 0.625)
    m.group("blade")
    m.box("socket", (6.75, 15.75, 7.125), (9.25, 18.5, 8.875), STEEL_DARK)
    rivet(m, "rivet", 8, 17.25, 6.875, 9.125, BOLT, 0.375)
    m.box("blade", (4.0, 17.5, 7.625), (12.0, 23.5, 8.375), STEEL)
    m.box("blade", (4.75, 23.5, 7.625), (11.25, 25.0, 8.375), STEEL)
    m.box("blade", (5.75, 25.0, 7.625), (10.25, 26.25, 8.375), STEEL)
    m.box("blade", (6.75, 26.25, 7.625), (9.25, 27.25, 8.375), STEEL)
    m.box("point", (7.375, 27.25, 7.69), (8.625, 28.5, 8.31), BRIGHT)
    m.box("rib", (7.5, 18.5, 7.5), (8.5, 25.5, 8.5), STEEL_DARK)
    m.box("edge", (12.0, 18.0, 7.69), (12.5, 23.5, 8.31), EDGE_E)
    m.box("edge", (11.25, 23.25, 7.69), (11.75, 25.0, 8.31), EDGE_E)
    m.box("shoulder", (4.0, 17.25, 7.5), (12.0, 17.75, 8.5), STEEL_DARK)
    m.group("saw")
    for y in (18.75, 20.25, 21.75, 23.25):
        tooth(m, "saw", 4.0, y, BRIGHT, 1.1)
    return _held(m, "shovel")


def camping_axe():
    """캠핑 손도끼: a hatchet: a polished steel head (eye, poll, a bearded cheek, a ground edge towards -x), a stitched
    brown leather sheath over the lower half of the edge held by a strap round the head with a steel snap; an ash
    handle wrapped in orange paracord at the grip, a steel wedge on top, a flared butt with an olive lanyard loop."""
    m = MModel("camping_axe")
    m.group("handle")
    m.box("handle", (7, -1.75, 7), (9, 16.25, 9), WOOD)
    m.box("wrap", (6.75, -0.75, 6.75), (9.25, 5.75, 9.25), CORD)
    m.box("butt", (6.5, -2.75, 6.75), (9.75, -1.75, 9.25), WOOD)
    m.box("butt_hook", (5.75, -3.25, 7.0), (6.75, -2.0, 9.0), WOOD)
    m.box("lanyard", (8.0, -4.75, 7.625), (8.5, -2.75, 8.375), OLIVE_CORD)
    m.box("lanyard", (8.0, -5.25, 7.625), (9.5, -4.75, 8.375), OLIVE_CORD)
    m.box("lanyard", (9.0, -4.75, 7.625), (9.5, -2.75, 8.375), OLIVE_CORD)
    m.group("head")
    m.box("eye", (6.25, 16.25, 6.5), (9.75, 21.25, 9.5), STEEL)
    m.box("wedge", (7.25, 21.25, 7.25), (8.75, 21.75, 8.75), WOOD_END)
    m.box("poll", (9.75, 16.75, 6.75), (11.5, 20.75, 9.25), STEEL_DARK)
    m.box("cheek", (3.75, 15.5, 6.875), (6.25, 21.75, 9.125), STEEL)
    m.box("cheek", (2.5, 14.5, 7.125), (3.75, 22.25, 8.875), STEEL)
    m.box("edge", (1.5, 13.75, 7.375), (2.5, 22.75, 8.625), EDGE_W)
    m.box("beard", (2.25, 13.25, 7.375), (3.25, 14.5, 8.625), EDGE_W)
    m.group("sheath")
    m.box("sheath", (0.75, 12.5, 6.625), (4.5, 18.0, 9.375), LEATHER)
    m.box("sheath_welt", (0.5, 12.75, 7.25), (0.75, 17.75, 8.75), LEATHER_DARK)
    m.box("strap", (4.5, 17.0, 6.375), (11.75, 18.0, 9.625), LEATHER_DARK)
    rivet(m, "snap", 10.75, 17.5, 6.25, 9.75, BOLT, 0.5)
    return _held(m, "axe")


def camping_hoe():
    """캠핑 괭이: a tent-peg mallet hoe: the vanilla hoe's straight ㄱ head in polished steel to -x, a dark peg-puller
    slot in it, a ground flat end; a round copper mallet behind (+x) with a bright strike face; a steel collar on an
    orange shaft, the black grip with a lanyard loop."""
    m = MModel("camping_hoe")
    gear_grip(m, bands=False)
    m.group("shaft")
    m.box("shaft", (7, 6.0, 7), (9, 19.5, 9), ORANGE)
    m.box("shaft_band", (6.875, 15.5, 6.875), (9.125, 16.25, 9.125), BLACK)
    m.group("head")
    m.box("collar", (6.5, 19.5, 6.625), (9.5, 23.25, 9.375), STEEL_DARK)
    rivet(m, "bolt", 8, 21.375, 6.375, 9.625, BOLT, 0.5)
    m.box("bar", (0.5, 20.0, 7.0), (6.5, 22.75, 9.0), STEEL)
    m.box("slot", (2.25, 20.75, 6.875), (4.25, 21.75, 9.125), BLACK)
    m.box("blade", (-1.25, 19.5, 6.75), (0.5, 23.25, 9.25), STEEL)
    m.box("blade_end", (-2.0, 19.5, 7.0), (-1.25, 23.25, 9.0), EDGE_W)
    m.group("mallet")
    cyl(m, "mallet", "x", (21.375, C), 4, 9.5, 12.25, COPPER, cap=COPPER_FACE)
    cyl(m, "mallet_face", "x", (21.375, C), 3, 12.25, 13.0, COPPER_FACE, cap=COPPER_FACE)
    return _held(m, "hoe")


def _guide(m, y, s, top=False):
    """A ring guide standing off the rod on +x (where the line runs): a foot, a stem, a square ring of side s."""
    x0 = 9.0
    m.box("guide_foot", (8.5, y - 0.25, 7.75), (x0 + 0.25, y + 0.5, 8.25), STEEL_DARK)
    r0, r1 = x0 + 0.25, x0 + 0.25 + s
    t = 0.25
    m.box("guide", (r0, y - s / 2, 7.75), (r0 + t, y + s / 2, 8.25), BRIGHT)
    m.box("guide", (r1 - t, y - s / 2, 7.75), (r1, y + s / 2, 8.25), BRIGHT)
    m.box("guide", (r0 + t, y + s / 2 - t, 7.75), (r1 - t, y + s / 2, 8.25), BRIGHT)
    m.box("guide", (r0 + t, y - s / 2, 7.75), (r1 - t, y - s / 2 + t, 8.25), BRIGHT)


def _rod(name, cast):
    m = MModel(name)
    m.group("grip")
    m.box("butt", (6.625, -1.75, 6.625), (9.375, -1.0, 9.375), BLACK)
    m.box("cork", (6.75, -1.0, 6.75), (9.25, 5.5, 9.25), CORK)
    m.box("cork_ring", (6.625, 2.0, 6.625), (9.375, 2.5, 9.375), ORANGE)
    # reel seat: a black tube with a steel locking nut
    m.box("seat", (7.0, 5.5, 7.0), (9.0, 10.5, 9.0), BLACK)
    m.box("seat_nut", (6.75, 5.5, 6.75), (9.25, 6.5, 9.25), KNURL)
    m.box("fore_cork", (6.875, 10.5, 6.875), (9.125, 12.5, 9.125), CORK)
    m.group("lantern")
    # a little lantern charm hanging from the butt: a ring, a black cap, amber glass in a black cage, a foot
    m.box("charm_ring", (7.75, -2.75, 7.75), (8.25, -1.75, 8.25), BRIGHT)
    m.box("lantern_cap", (7.0, -3.5, 7.0), (9.0, -2.75, 9.0), BLACK)
    m.box("lantern_glass", (7.25, -5.5, 7.25), (8.75, -3.5, 8.75), AMBER)
    m.box("lantern_bar", (6.875, -5.5, 7.875), (7.25, -3.5, 8.125), BLACK)
    m.box("lantern_bar", (8.75, -5.5, 7.875), (9.125, -3.5, 8.125), BLACK)
    m.box("lantern_foot", (7.0, -6.0, 7.0), (9.0, -5.5, 9.0), BLACK)
    m.group("reel")
    # a spinning reel under the rod (+x): a foot on the seat, a stem, a body with its spool along the rod, a crank
    m.box("reel_foot", (9.0, 7.0, 7.5), (9.5, 9.5, 8.5), BLACK)
    m.box("reel_stem", (9.5, 7.75, 7.625), (11.0, 8.75, 8.375), BLACK)
    cyl(m, "reel_body", "y", (12.25, C), 3, 5.0, 8.25, BLACK_LIGHT, cap=BLACK)
    cyl(m, "spool", "y", (12.25, C), 2.5, 8.25, 10.25, ORANGE, cap=ORANGE)
    cyl(m, "spool_lip", "y", (12.25, C), 3, 10.25, 10.75, BRIGHT, cap=BRIGHT)
    m.box("bail", (13.75, 9.5, 7.75), (14.25, 11.25, 8.25), BRIGHT)
    m.box("crank_arm", (11.75, 6.0, 9.5), (12.75, 6.75, 11.25), STEEL)
    m.box("crank_arm", (11.75, 6.75, 10.5), (12.75, 8.25, 11.25), STEEL)
    m.box("crank_knob", (11.5, 8.25, 10.25), (13.0, 9.5, 11.75), ORANGE_DARK)
    m.group("rod")
    # telescopic: graphite sections a step thinner each, steel ferrules with an orange wrap at each joint
    secs = [(12.5, 18.5, 1.0), (18.5, 24.5, 0.75), (24.5, 30.25, 0.5)]
    for y0, y1, h in secs:
        m.box("rod", (C - h, y0, C - h), (C + h, y1, C + h), BLACK_LIGHT)
    for y, h in ((18.5, 1.0), (24.5, 0.75)):
        m.box("ferrule", (C - h - 0.25, y - 0.75, C - h - 0.25), (C + h + 0.25, y, C + h + 0.25), STEEL)
        m.box("wrap", (C - h - 0.125, y - 1.5, C - h - 0.125), (C + h + 0.125, y - 0.75, C + h + 0.125), ORANGE)
    m.box("rod_tip", (7.625, 30.25, 7.625), (8.375, 31.75, 8.375), ORANGE)
    _guide(m, 15.5, 1.75)
    _guide(m, 21.75, 1.25)
    _guide(m, 27.5, 1.0)
    if not cast:
        # the line hangs from the tip (45 degrees in this frame = straight down on the sprite): a spoon lure on it
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.25, 22, 7.75), (8.75, 31, 8.25), LINE, rotation=rot)
        m.box("swivel", (8.0, 21.25, 7.75), (9.0, 22.0, 8.25), BRIGHT, rotation=rot)
        m.box("spoon", (7.5, 18.5, 7.625), (9.5, 21.25, 8.375), ORANGE, rotation=rot)
        m.box("spoon", (7.75, 17.75, 7.625), (9.25, 18.5, 8.375), STEEL, rotation=rot)
        m.box("hook", (8.0, 16.75, 7.75), (9.0, 17.75, 8.25), BRIGHT, rotation=rot)
    return _held(m, "rod")


def camping_fishing_rod():
    """캠핑 낚싯대: a telescopic travel rod: graphite sections with steel ferrules, ring guides, a spinning reel with a
    crank, an orange cork grip and a lantern charm, a spoon lure on the line."""
    return _rod("camping_fishing_rod", False)


def camping_fishing_rod_cast():
    """The rod while its line is out: the same without the line and the lure."""
    m = _rod("camping_fishing_rod_cast", True)
    m.display = camping_fishing_rod().display     # the inventory picture does not jump on a cast
    return m


ALL = {f.__name__: f for f in (camping_sword, camping_pickaxe, camping_shovel, camping_axe, camping_hoe, camping_fishing_rod,
                               camping_fishing_rod_cast)}

ROWS = [
    ("camping_sword", "캠핑 마체테"), ("camping_pickaxe", "접이식 곡괭이"), ("camping_shovel", "접이식 야전삽"),
    ("camping_axe", "캠핑 손도끼"), ("camping_hoe", "캠핑 괭이"), ("camping_fishing_rod", "캠핑 낚싯대"),
    ("camping_fishing_rod_cast", "낚싯대 (던진 뒤)"),
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
