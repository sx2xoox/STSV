"""
주방 시리즈 (kitchen series, a gold-price tool skin set): kitchen utensils held as tools.

    kitchen_sword        식칼: a chef's knife; a riveted wooden handle, a brushed blade with a polished edge that sweeps
                         up to the point in two smooth angled strips (22.5 then 45 degrees), a small maker's stamp
    kitchen_pickaxe      고기 망치: a meat tenderiser whose two waffle-faced heads droop like a pickaxe's arms
    kitchen_shovel       국자: a ladle with a raised rim lip, full of red stew (tofu, green onion, chilli, courgette)
    kitchen_axe          중식도: a square cleaver: polished edge bevel, brushed middle, hammered dimples along the spine,
                         a drilled hanging hole (lit far wall) and a round maker's stamp, on a riveted wooden handle
    kitchen_hoe          뒤집개: a slotted turner hanging face on from a bent neck (the hoe's L), its slots shaded deep
    kitchen_fishing_rod  젓가락 낚싯대: a long stainless chopstick with an engraved grip, a pot-lid reel, and a big
                         fish-shaped soy sauce bottle on the line instead of a float (+ kitchen_fishing_rod_cast without
                         it). No bobber model.

Simple concepts, rich detail, still light: at most ~80 % of a toy piece's boxes. Every handle ends in a little cord loop
(a thin see-through plane, like a hanging utensil's). Same frame and machinery as models/toys.py (upright, the grip at
GRIP_Y on the axis x = z = 8, display transforms by toys._held), imported, not copied.

Steel: "Brushed" below, the blade ramp with broken streaks along the length, two diagonal reflection bands and a polished
light bevel on its top and left edges. The big faces (the knife's and the cleaver's blades, the turner's plate, the ladle,
the fish) carry their own 32 x 32 pictures, two texels a model unit like the toy series' stickers.
"""

import math
import random

from PIL import Image

from economy_machines import MModel, Mat, cyl
from models.toys import C, _held
from stsv_assets import textures as T
from stsv_assets.palette import ramp

S = 16


class Brushed(Mat):
    """Brushed stainless: streaks along the face's height, reflection bands across it, the Mat's bevel on its edges."""

    def __call__(self, **changes):
        m = Mat.__call__(self, **changes)
        m.__class__ = Brushed
        return m

    def _index(self, w, h, rnd):
        if self.kind != "brushed":
            return Mat._index(self, w, h, rnd)
        b = self.base
        streak = [rnd.choice((0, 0, 0, -1)) for _ in range(S)]
        grid = [[b] * S for _ in range(S)]
        span = max(1, w + h - 2)
        for y in range(S):
            for x in range(S):
                i = b + streak[x] if (y + 3 * x) % 7 < 5 else b
                t = (x + y) / span
                if 0.28 <= t < 0.42 or 0.58 <= t < 0.64:
                    i = b + 1
                grid[y][x] = i
        return grid


# ================================================================================================
# Materials
# ================================================================================================

STEEL = Brushed("blade", "brushed", 2, bevel=(1, -1), noise=0)
STEEL_DARK = Brushed("blade", "brushed", 1, bevel=(1, -1), noise=0)     # bolsters, necks, arms
POLISH = Mat("blade", "plain", 3, bevel=(0, -1), noise=0)               # sharpened edges, rivets, collars
MINT = Mat("celadon", "metal", 3, bevel=(0, -1), noise=0)
MINT_DARK = Mat("celadon", "plain", 2, bevel=(0, -1), noise=0)
RED = Mat("tomato", "metal", 2, bevel=(0, -1), noise=0)
RED_DARK = Mat("tomato", "plain", 1, bevel=(0, -1), noise=0)
WOOD = Mat("wood", "grain", 2, bevel=(1, -1), noise=0.06)
WOOD_DARK = Mat("wood", "grain", 1, bevel=(0, -1), noise=0.04)
LINE = Mat("feather", "plain", 2, bevel=None, noise=0)
CAP_RED = Mat("tomato", "metal", 2, bevel=(0, -1), noise=0)

S0, S1, S2, S3 = (ramp("blade", i) for i in range(4))
CLEAR = (0, 0, 0, 0)


# ================================================================================================
# Pictures (32 x 32: two texels a model unit; uv in model units)
# ================================================================================================

def _canvas(fill=CLEAR):
    return Image.new("RGBA", (32, 32), fill)


def _brushed_px(px, W, H, seed, edge=True):
    """Fill W x H texels with brushed steel: broken streaks down the length, a diagonal reflection, a light top/left bevel."""
    rnd = random.Random(seed)
    streak = [rnd.choice((S2, S2, S1, S2)) for _ in range(W)]
    for y in range(H):
        for x in range(W):
            c = streak[x] if (y + 3 * x) % 9 < 6 else S2
            t = (x + 0.6 * y) / (W + 0.6 * H)
            if 0.30 <= t < 0.40 or 0.55 <= t < 0.59:
                c = S3
            if edge and (x == W - 1 or y == H - 1):
                c = S1
            px[x, y] = c


def _knife_blade():
    """The knife's main blade (5 x 11.25 units, x = 0 the edge side): brushed, the spine polished, a maker's stamp."""
    im = _canvas()
    px = im.load()
    W, H = 10, 23
    _brushed_px(px, W, H, 11, edge=False)
    for y in range(H):
        px[W - 1, y] = S3                         # the polished spine
        px[W - 2, y] = S1 if y % 5 else S2        # the spine's shadow line
    for x in range(W):
        px[x, H - 1] = S1
    # maker's stamp near the bolster: a small rounded diamond and a bar
    for (x, y) in ((6, 17), (5, 18), (7, 18), (6, 19)):
        px[x, y] = S0
    px[6, 18] = S2
    for x in (4, 5, 6, 7, 8):
        px[x, 21] = S1
    return im


def _cleaver():
    """The cleaver's blade (8 x 12 units, x = 0 the edge): polished bevel, brushed middle, hammered spine zone, the hole."""
    im = _canvas()
    px = im.load()
    W, H = 16, 24
    _brushed_px(px, W, H, 23, edge=False)
    for y in range(H):
        px[0, y] = S3
        px[1, y] = S3 if y % 6 else S2            # the polished bevel band
        px[2, y] = S2
        px[3, y] = S1                             # the bevel's shoulder
    # hammered dimples (tsuchime) along the spine side, staggered rows
    for row, y0 in enumerate(range(1, 17, 3)):
        for x0 in range(9 + (row % 2) * 1, 15, 3):
            if 10 <= x0 <= 13 and y0 <= 6:
                continue                         # leave the hole's corner smooth
            px[x0, y0] = S1
            px[x0 + 1, y0 + 1] = S3
    # the drilled hanging hole: a polished chamfer, a dark inside, its far (lower right) wall lit
    for y in range(2, 7):
        for x in range(10, 15):
            ring = x in (10, 14) or y in (2, 6)
            px[x, y] = S3 if ring and (x == 10 or y == 2) else S1 if ring else ramp("iron", 0)
    px[13, 5] = ramp("steel", 3)
    px[12, 5] = ramp("iron", 2)
    px[13, 4] = ramp("iron", 2)
    # round maker's stamp near the handle
    for (x, y) in ((11, 19), (12, 19), (10, 20), (13, 20), (10, 21), (13, 21), (11, 22), (12, 22)):
        px[x, y] = S0
    px[11, 20] = px[12, 21] = S1
    for y in range(H):
        px[W - 1, y] = S3                         # the spine catching the light
    for x in range(W):
        px[x, H - 1] = S1
    return im


def _turner():
    """The turner's plate (6 x 8 units): brushed, three slots with depth (a dark shadow under their top and left, the far
    wall lit), a polished working edge along the bottom."""
    im = _canvas()
    px = im.load()
    W, H = 12, 16
    _brushed_px(px, W, H, 31)
    for x0 in (2, 5, 8):
        for y in range(3, 13):
            px[x0, y] = ramp("iron", 0)
            px[x0 + 1, y] = ramp("iron", 1)
        for x in (x0, x0 + 1):
            px[x, 2] = S1                         # the chamfer above
            px[x, 13] = S3                        # the lit far wall below
        px[x0 + 1, 12] = ramp("steel", 3)
    for x in range(W):
        px[x, H - 2] = S3                         # the thin polished front edge
        px[x, H - 1] = S1
    for y in range(H):
        px[0, y] = S3
    return im


def _disc_px(px, f):
    for y in range(32):
        for x in range(32):
            d = math.hypot(x + 0.5 - 16, y + 0.5 - 16)
            f(x, y, d)


def _stew():
    """The ladle's front (the whole round end): a steel rim, red stew with oil glints and ingredients."""
    im = _canvas(S2)
    px = im.load()

    def f(x, y, d):
        if d > 13.0:
            px[x, y] = S3 if x + y < 30 else S1
        elif d > 12.0:
            px[x, y] = ramp("pepper", 1)          # the stew's dark edge against the bowl
        else:
            c = ramp("pepper", 3) if x + y < 34 else ramp("pepper", 2)
            if (x * 7 + y * 3) % 23 == 0:
                c = ramp("pepper", 4)              # oil glints
            px[x, y] = c
    _disc_px(px, f)
    cream, cream_d = ramp("cream", 3), ramp("cream", 1)
    for (x0, y0) in ((9, 17), (17, 20), (19, 9)):           # tofu cubes, lit top left
        for y in range(y0, y0 + 4):
            for x in range(x0, x0 + 4):
                px[x, y] = cream_d if (x == x0 + 3 or y == y0 + 3) else cream
    for (cx, cy) in ((12, 10), (22, 16), (14, 23)):           # green onion rings
        for (x, y) in ((cx, cy - 1), (cx + 1, cy - 1), (cx - 1, cy), (cx + 2, cy), (cx - 1, cy + 1), (cx + 2, cy + 1),
                       (cx, cy + 2), (cx + 1, cy + 2)):
            px[x, y] = ramp("leaf_dark", 3)
        px[cx, cy] = px[cx + 1, cy + 1] = ramp("sprout", 3)
    for (cx, cy) in ((8, 13), (20, 24)):                     # chilli slices
        for (x, y) in ((cx, cy), (cx + 1, cy), (cx, cy + 1), (cx + 1, cy + 1)):
            px[x, y] = ramp("pepper", 1)
        px[cx, cy] = ramp("straw", 3)
    for x in range(16, 21):                                  # a courgette half moon
        px[x, 14] = ramp("zucchini", 1)
        if 17 <= x <= 19:
            px[x, 13] = ramp("zucchini", 3)
    return im


def _lip():
    """The ladle's raised rim lip: a see-through ring, polished top left."""
    im = _canvas()
    px = im.load()

    def f(x, y, d):
        if 13.0 < d <= 16.0:
            px[x, y] = S3 if x + y < 26 else S2 if x + y < 36 else S1
        elif 12.4 < d <= 13.0:
            px[x, y] = S1                         # the lip's inner shadow
    _disc_px(px, f)
    return im


def _back():
    im = _canvas()
    px = im.load()

    def f(x, y, d):
        px[x, y] = S3 if (x - 11) ** 2 + (y - 11) ** 2 < 20 else S2 if x + y < 34 else S1
    _disc_px(px, f)
    return im


def _waffle():
    """The tenderiser's striking face: rows of little pyramids, lit top left (16 x 16, one texel a unit's quarter)."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            a, b = x % 4, y % 4
            px[x, y] = S3 if a + b < 2 else S0 if a + b > 4 else S2
    return im


def _engraved():
    """The chopstick's grip end (2 x 8.5 units): engraved diamonds between two bands."""
    im = _canvas()
    px = im.load()
    for y in range(17):
        for x in range(4):
            c = S2
            if y in (0, 16):
                c = S1
            elif y in (1, 15):
                c = S3
            elif (x + y) % 4 == 0 or (y - x) % 4 == 0:
                c = S0
            px[x, y] = c
    return im


FISH_W, FISH_H = 16, 9      # the fish plane: 8 x 4.5 units


def _fish_side():
    """The soy bottle fish's silhouette and see-through fins on a thin plane (only what sticks out past the body shows)."""
    im = _canvas()
    px = im.load()
    soy, soy_d, clear, clear_d = ramp("doenjang", 1), ramp("doenjang", 0), ramp("glass", 2), ramp("glass", 0)
    # tail: a fork at x 0..3, narrowing to the peduncle at x 4..6
    tail = {0: (0, 1, 7, 8), 1: (0, 1, 2, 6, 7, 8), 2: (1, 2, 3, 5, 6, 7), 3: (2, 3, 4, 5, 6), 4: (3, 4, 5), 5: (3, 4, 5), 6: (3, 4, 5)}
    for x, ys in tail.items():
        for y in ys:
            px[x, y] = clear if y < 5 else clear_d
    for x in range(7, 15):                                  # the back's hump and the belly past the body box
        px[x, 1] = clear
        px[x, 7] = soy_d
    for x in (9, 10, 11):                                   # the dorsal fin
        px[x, 0] = clear
    for x in (10, 11):                                      # the pelvic fin
        px[x, 8] = clear_d
    return im


def _fish_body():
    """The fish's body (5 x 2.5 units: 10 x 5 texels, head at +x): clear plastic over soy sauce, an eye, a gill, scales."""
    im = _canvas()
    px = im.load()
    soy, soy_l, soy_d, clear = ramp("doenjang", 1), ramp("doenjang", 2), ramp("doenjang", 0), ramp("glass", 3)
    for y in range(5):
        for x in range(10):
            c = soy
            if y == 0:
                c = clear                         # the air above the sauce, seen through the plastic
            elif y == 1:
                c = soy_l
            elif y == 4:
                c = soy_d
            elif (x + 2 * y) % 4 == 0 and x < 6:
                c = soy_l                         # moulded scales
            px[x, y] = c
    px[6, 1] = px[6, 2] = px[6, 3] = soy_d        # the gill
    px[8, 1] = (245, 245, 240, 255)               # the eye
    px[8, 2] = ramp("iron", 0)
    return im


def _loop(cord, cord_d):
    """A hanging cord loop (16 x 16 on a 3.5 x 4.5 unit plane): a cord down from the top, a knot, a round loop."""
    def make():
        im = Image.new("RGBA", (16, 16), CLEAR)
        px = im.load()
        for y in range(0, 6):
            px[7, y] = cord
            px[8, y] = cord_d
        for x in (6, 7, 8, 9):
            px[x, 5] = cord_d                     # the knot
        for y in range(16):
            for x in range(16):
                d = math.hypot((x + 0.5 - 8) / 5.5, (y + 0.5 - 10.5) / 4.5)
                if 0.72 <= d <= 1.0 and y > 5:
                    px[x, y] = cord if x + y < 18 else cord_d
        return im
    return make


T.SURFACES["kitchen_knife"] = _knife_blade
T.SURFACES["kitchen_cleaver"] = _cleaver
T.SURFACES["kitchen_turner"] = _turner
T.SURFACES["kitchen_stew"] = _stew
T.SURFACES["kitchen_lip"] = _lip
T.SURFACES["kitchen_steel_disc"] = _back
T.SURFACES["kitchen_waffle"] = _waffle
T.SURFACES["kitchen_engraved"] = _engraved
T.SURFACES["kitchen_fish_side"] = _fish_side
T.SURFACES["kitchen_fish_body"] = _fish_body
T.SURFACES["kitchen_loop_red"] = _loop(ramp("tomato", 2), ramp("tomato", 1))
T.SURFACES["kitchen_loop_white"] = _loop(ramp("feather", 3), ramp("feather", 1))


def _pic(m, name, frm, to, mat, pic, w, h, faces=("south", "north")):
    """A box whose front (+z) and back (-z) show a 32 x 32 picture at two texels a unit (the back mirrored, so the
    picture's left stays on -x)."""
    f, uv = {}, {}
    if "south" in faces:
        f["south"], uv["south"] = pic, [0, 0, w, h]
    if "north" in faces:
        f["north"], uv["north"] = pic, [w, 0, 0, h]
    m.box(name, frm, to, mat, faces=f, uv=uv)


def _plane(m, name, frm, to, pic, uv):
    """A see-through picture plane (no thickness to speak of): only its front and back faces."""
    none = {k: None for k in ("east", "west", "up", "down")}
    m.box(name, frm, to, LINE, faces={**none, "south": pic, "north": pic}, uv={"south": uv, "north": [uv[2], uv[1], uv[0], uv[3]]})


# ================================================================================================
# Shared parts
# ================================================================================================

def loop(m, y_top, cord="kitchen_loop_red"):
    """The cord loop hanging from a handle's end at y_top (its cord starts half a unit inside the handle)."""
    m.group("loop")
    _plane(m, "loop", (6.25, y_top - 4.5, 7.95), (9.75, y_top + 0.5, 8.05), cord, [0, 0, 16, 16])


def plastic_handle(m, mat, dark, top=6.5, cord="kitchen_loop_white"):
    """A moulded plastic handle (y -3.5 .. top): a darker end cap, a thumb rest, a polished steel ferrule, a cord loop."""
    m.group("handle")
    m.box("cap", (6.5, -3.5, 6.5), (9.5, -2.5, 9.5), dark)
    m.box("handle", (6.75, -2.5, 6.75), (9.25, top - 0.75, 9.25), mat)
    m.box("thumb_rest", (6.5, 3.75, 6.5), (9.5, 4.5, 9.5), dark)
    m.box("ferrule", (6.5, top - 0.75, 6.5), (9.5, top, 9.5), POLISH)
    loop(m, -3.5, cord)


def wood_handle(m, top=6.5, rivets=(-1.75, 1.25, 4.25)):
    """A flat wooden knife handle (y -3.25 .. top), three polished rivets through it, a dark butt and a red cord loop."""
    m.group("handle")
    m.box("handle", (6.75, -3.25, 7), (9.25, top, 9), WOOD)
    m.box("handle_end", (6.875, -3.75, 7.125), (9.125, -3.25, 8.875), WOOD_DARK)
    for y in rivets:
        m.box("rivet", (7.5, y, 6.875), (8.5, y + 1, 9.125), POLISH)
    loop(m, -3.75, "kitchen_loop_red")


def stem(m, top, y0=6.5):
    m.group("stem")
    m.box("stem", (7.25, y0, 7.25), (8.75, top, 8.75), STEEL)


# ================================================================================================
# The tools
# ================================================================================================

# the knife's edge line, from the straight edge's top (5, 19): 22.5 degrees off the vertical for 4 units, then 45
_KA, _KB = (5.0, 19.0), (5.0 + 4 * math.tan(math.radians(22.5)), 23.0)


def _knife_edge_x(y):
    if y <= 23:
        return _KA[0] + (y - 19) * math.tan(math.radians(22.5))
    return _KB[0] + (y - 23)


def kitchen_sword():
    """식칼: a chef's knife. A long brushed blade, the spine straight on +x; the polished edge on -x runs straight, then
    sweeps up to the point along two angled strips that hide the fill's steps."""
    m = MModel("kitchen_sword")
    wood_handle(m)
    m.group("blade")
    m.box("bolster", (6.5, 6.5, 6.75), (10.25, 7.75, 9.25), STEEL_DARK)
    _pic(m, "blade", (5, 7.75, 7.5), (10, 19, 8.5), STEEL, "kitchen_knife", 5, 11.25)
    # the fill above: each row reaches the edge line at its top, its notch under the edge strip
    for y0, y1 in ((19, 21), (21, 23), (23, 24), (24, 24.9)):
        m.box("blade", (round(_knife_edge_x(y1), 3), y0, 7.5), (10, y1, 8.5), STEEL)
    # the polished edge: straight, then the two angled strips (pivots on the edge line), a touch thicker than the blade
    z0, z1 = 7.45, 8.55
    m.box("edge", (5, 8.25, z0), (6, 19, z1), POLISH)
    la = 4 / math.cos(math.radians(22.5))
    m.box("edge", (_KA[0], _KA[1], z0), (_KA[0] + 1, _KA[1] + la, z1), POLISH, rotation=("z", -22.5, (_KA[0], _KA[1], C)))
    lb = (9.29 - _KB[0]) * math.sqrt(2)
    m.box("edge", (_KB[0], _KB[1], z0), (_KB[0] + 1, _KB[1] + lb, z1), POLISH, rotation=("z", -45, (_KB[0], _KB[1], C)))
    return _held(m, "sword")


def _bar(m, name, side, pivot, length, half_h, z0, z1, angle, mat, faces=None):
    """A box along x from pivot (on its centre line), drooping by angle; returns the far end of its centre line."""
    px, py = pivot
    if side > 0:
        frm, to = (px - 0.5, py - half_h, z0), (px + length, py + half_h, z1)
    else:
        frm, to = (px - length, py - half_h, z0), (px + 0.5, py + half_h, z1)
    m.box(name, frm, to, mat, faces=faces, rotation=("z", -angle * side, (px, py, C)))
    a = math.radians(angle)
    return px + side * (length - 0.5) * math.cos(a), py - (length - 0.5) * math.sin(a)


def kitchen_pickaxe():
    """고기 망치: a meat tenderiser drawn out into a pickaxe. A brushed hub with a red band on a red handle, two arms
    drooping (22.5 then 45 degrees), a polished collar, then a fat head whose outer face is the waffle of pyramids."""
    m = MModel("kitchen_pickaxe")
    plastic_handle(m, RED, RED_DARK)
    stem(m, 16)
    m.group("head")
    m.box("hub", (5.5, 15.5, 6.5), (10.5, 20.5, 9.5), STEEL)
    m.box("hub_band", (5.25, 17.25, 6.25), (10.75, 18.25, 9.75), RED)
    m.box("hub_cap", (6.5, 20.5, 7), (9.5, 21.25, 9), RED)
    for side in (1, -1):
        p = _bar(m, "arm", side, (C + side * 2.5, 18), 4.0, 1.25, 7, 9, 22.5, STEEL_DARK)
        p = _bar(m, "collar", side, p, 1.25, 1.75, 6.75, 9.25, 45, POLISH)
        end = "east" if side > 0 else "west"
        _bar(m, "hammer", side, p, 2.75, 2.25, 6.25, 9.75, 45, STEEL, faces={end: "kitchen_waffle"})
    return _held(m, "pickaxe")


def kitchen_shovel():
    """국자: a deep round ladle full of red stew, a raised polished lip round it, a smaller dome behind; a long brushed
    neck and a mint handle."""
    m = MModel("kitchen_shovel")
    plastic_handle(m, MINT, MINT_DARK, cord="kitchen_loop_red")
    stem(m, 16.5)
    m.box("neck", (7, 15.5, 7), (9, 16.5, 9), POLISH)
    m.group("bowl")
    # -z (north) is the ladle's round back, +z (south: the inventory's and the player's side) its stew
    cyl(m, "bowl", "z", (C, 21), 10, 6.75, 9.25, STEEL, cap="kitchen_steel_disc", cap2="kitchen_stew")
    cyl(m, "dome", "z", (C, 21), 7, 5.75, 6.75, STEEL_DARK, cap="kitchen_steel_disc", cap2="kitchen_steel_disc")
    none = {k: None for k in ("east", "west", "up", "down", "north")}
    m.box("lip", (3, 16, 9.25), (13, 26, 9.5), LINE, faces={**none, "south": "kitchen_lip"}, uv={"south": [0, 0, 16, 16]})
    return _held(m, "shovel")


def kitchen_axe():
    """중식도: a square cleaver beside the top of a riveted wooden handle (its edge on -x, like the sprite's axe blade):
    the hole, the hammered spine zone and the stamp are in its picture."""
    m = MModel("kitchen_axe")
    wood_handle(m, top=10, rivets=(-1.5, 2.5, 6.5))
    m.group("blade")
    m.box("bolster", (6.5, 10, 6.75), (10, 11.25, 9.25), STEEL_DARK)
    _pic(m, "blade", (1.75, 11, 7.5), (9.75, 23, 8.5), STEEL, "kitchen_cleaver", 8, 12)
    m.box("edge", (1, 11.25, 7.625), (1.75, 23, 8.375), POLISH)
    m.box("spine", (2, 23, 7.375), (9.75, 23.5, 8.625), STEEL_DARK)
    return _held(m, "axe")


def kitchen_hoe():
    """뒤집개: a slotted turner. A brushed neck bends off the top of a red-handled stem (towards -x) and the plate hangs
    from it face on, a rivet where they meet."""
    m = MModel("kitchen_hoe")
    plastic_handle(m, RED, RED_DARK)
    stem(m, 21.5)
    m.group("head")
    m.box("neck", (3, 20, 7.25), (8.75, 21.5, 8.75), STEEL)
    _pic(m, "plate", (0, 12.5, 7.625), (6, 20.5, 8.375), STEEL, "kitchen_turner", 6, 8)
    m.box("plate_edge", (0.25, 12, 7.75), (5.75, 12.5, 8.25), POLISH)
    m.box("rivet", (3.75, 20.25, 7.125), (4.75, 21.25, 8.875), POLISH)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    plastic_handle(m, MINT, MINT_DARK, cord="kitchen_loop_red")
    m.group("reel")
    # a little pot lid for a reel, beside the rod's foot (+x, where the line hangs), its red knob towards +z
    m.box("reel_foot", (9.25, 7.75, 7.5), (10.25, 9.25, 8.5), STEEL_DARK)
    cyl(m, "reel", "z", (12.25, 8.5), 5, 6.75, 9.25, STEEL, cap="kitchen_steel_disc", cap2="kitchen_steel_disc")
    m.box("reel_knob", (11.5, 7.75, 9.25), (13, 9.25, 10.5), RED)
    m.group("rod")
    # one long flat stainless chopstick, tapering towards the tip, its grip end engraved, a red band at the handle
    eng = {f: "kitchen_engraved" for f in ("north", "south", "east", "west")}
    m.box("rod", (7, 6.5, 7.25), (9, 15, 8.75), STEEL, faces=eng, uv={f: [0, 0, 2, 8.5] for f in eng})
    m.box("rod", (7.25, 15, 7.375), (8.75, 23.5, 8.625), STEEL)
    m.box("rod", (7.5, 23.5, 7.5), (8.5, 31, 8.5), STEEL)
    m.box("band", (6.75, 7, 7), (9.25, 8, 9), RED)
    m.box("guide", (8.75, 19, 7.625), (9.75, 20, 8.375), POLISH)
    m.box("guide", (8.5, 27, 7.75), (9.5, 28, 8.25), POLISH)
    if not cast:
        # the line hangs from the tip: straight down on the sprite's diagonal = 45 degrees in this frame; the fish
        # bottle hangs level under it (along x here, the same 45 degrees)
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.125, 20.75, 7.625), (8.875, 31, 8.375), LINE, rotation=rot)
        m.box("fish", (5.5, 17.75, 7.25), (10.5, 20.25, 8.75), CAP_RED,
              faces={"south": "kitchen_fish_body", "north": "kitchen_fish_body",
                     "up": Mat("glass", "plain", 3, bevel=None, noise=0), "down": Mat("doenjang", "plain", 0, bevel=None, noise=0),
                     "west": Mat("doenjang", "plain", 1, bevel=None, noise=0), "east": Mat("doenjang", "plain", 1, bevel=None, noise=0)},
              uv={"south": [0, 0, 5, 2.5], "north": [5, 0, 0, 2.5]}, rotation=rot)
        none = {k: None for k in ("east", "west", "up", "down")}
        m.box("fish_fins", (2.5, 16.75, 8), (10.5, 21.25, 8), LINE,
              faces={**none, "south": "kitchen_fish_side", "north": "kitchen_fish_side"},
              uv={"south": [0, 0, 8, 4.5], "north": [8, 0, 0, 4.5]}, rotation=rot)
        m.box("fish_cap", (10.5, 18.25, 7.5), (11.75, 19.75, 8.5), CAP_RED, rotation=rot)
    return _held(m, "rod")


def kitchen_fishing_rod():
    """젓가락 낚싯대: a long steel chopstick for a rod, a pot-lid reel, a fish-shaped soy sauce bottle on the line."""
    return _rod("kitchen_fishing_rod", False)


def kitchen_fishing_rod_cast():
    """The rod while its line is out: the same without the line and the fish bottle."""
    m = _rod("kitchen_fishing_rod_cast", True)
    m.display = kitchen_fishing_rod().display     # framed as the uncast rod: the inventory picture does not jump on a cast
    return m


# ================================================================================================
# Exports
# ================================================================================================

ALL = {f.__name__: f for f in (kitchen_sword, kitchen_pickaxe, kitchen_shovel, kitchen_axe, kitchen_hoe, kitchen_fishing_rod,
                               kitchen_fishing_rod_cast)}

ROWS = [
    ("kitchen_sword", "식칼 검"), ("kitchen_pickaxe", "고기 망치 곡괭이"), ("kitchen_shovel", "국자 삽"),
    ("kitchen_axe", "중식도 도끼"), ("kitchen_hoe", "뒤집개 괭이"), ("kitchen_fishing_rod", "젓가락 낚싯대"),
    ("kitchen_fishing_rod_cast", "낚싯대 (던진 뒤)"),
]

TITLE = "주방 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("kitchen_sword", "kitchen_pickaxe", "kitchen_shovel", "kitchen_axe", "kitchen_hoe",
                                 "kitchen_fishing_rod_cast")}
    out["kitchen_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/kitchen_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/kitchen_fishing_rod_cast"},
    }}
    return out
