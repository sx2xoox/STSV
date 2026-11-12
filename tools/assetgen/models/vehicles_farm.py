"""
Farm vehicles, articulated (content/stsv/items/vehicles_farm.yml): a driven or pulling unit and towed cargo units
behind it (plugin: dev.stsv.core.vehicle.TrainSpec / TrainPhysics / TrainView). Original designs, no brand, no logo.

  소달구지 (ox cart, 2nd generation)  ox_cart        the ox (Korean native cattle, golden tan) with its yoke and the
                                                     rope traces back to a swingletree behind it (the cart's pole
                                                     hangs on its middle: the hitch)
                                      ox_leg_fore, ox_leg_hind   one leg each, swung about the hip (model (8, 8, 8))
                                      ox_head        head and neck, pivoting at the withers (bobs while it walks)
                                      ox_cart_wagon  the wooden cart: a slatted deck on two big spoked wheels, a
                                                     driver's bench across its front, a single pole forward
                                      wheel_oxcart   its wheel (wooden, iron tyre, twelve spokes)
                                      ox_cart_cargo_1..4  rice straw bundles, straw sacks (가마니), crocks (항아리)
  경운기 + 짐칸 (3rd generation)      power_tiller   two-wheeled walking tractor: single-cylinder engine with a
                                                     water hopper, a belt to the gearbox, long handlebars reaching
                                                     back to the driver on the trailer's front seat
                                      power_tiller_flywheel  the spoked flywheel on its right (spins while running)
                                      wheel_tiller   lugged tyre (the tiller's and its trailer's)
                                      power_tiller_trailer   steel trailer: plank floor, drop sides, the seat box
                                      power_tiller_cargo_1..4  fertilizer sacks, cabbages, a plastic tub, crates
  트랙터 + 트레일러 (5th generation)  tractor        a farm tractor: big lugged rear wheels (a rolling part), small
                                                     steered front wheels, hood, exhaust stack, roll bar with a sun
                                                     canopy, a fender seat
                                      wheel_tractor_front, wheel_tractor_rear
                                      tractor_wagon  a farm wagon (two of them in a chain), wooden sides on a steel
                                                     frame, a drawbar in front and a hitch behind
                                      wheel_wagon    its wheel
                                      tractor_wagon_cargo_1..4  hay bales, apple crates, sacks, a tarp

Every towed unit is drawn with its axle (the middle of its wheels) at (0, 0, 0) and its pole / drawbar reaching
forward to the hitch (vehicles_farm.yml train.trailers.tongue). The driven units as in models/vehicles.py: ground
centre at (0, 0, 0), front = north (-z), model +x = the vehicle's right. Bodies are drawn at real size and shrunk
by 2 (BModel scale 2: the item display scales them back up); legs, head, wheels and loads are small enough to be
drawn as they are. As everywhere the detail is painted (decals), not modelled (docs/ART_STYLE.md, 렌더 예산).
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, h3, vnoise
from stsv_assets import presets
from models.vehicles import (_held_display, _grain, _cart_crate, _cart_sack, _sack, _crate, _rope_net, _folded_tarp,
                             _tarp, lerp, raw, CLEAR, IRON, DARK)

# ================================================================================================
# The ox (한우: golden tan, a paler belly and muzzle, dark hooves)
# ================================================================================================

HIDE = "plush"                  # the coat: golden brown
OZ = 6                          # the torso's middle lies this far behind the ox's centre (px): the footprint is centred
LEG_X, LEG_Y = 3.5, 14          # legs: x either side, hip height (px)
FORE_Z, HIND_Z = OZ - 9, OZ + 9
HEAD_AT = (0, 21, OZ - 14)      # where the head model's pivot sits (the neck's root, drawn px)
RING = (0, 12.8, OZ + 15.5)     # the swingletree's middle: the cart's pole hangs here (the hitch)


def _coat(c, base=2.2):
    """The hide: a darker line along the spine, paler towards the belly, now and then a hair streak."""
    x, y, z = c.p
    if c.face == "up":
        return (HIDE, base + (0.1 if abs(x) < 1 else 0.5))
    if c.face == "down":
        return ("bean", 1.6)
    t = lerp(y, 13, 26, -0.4, 0.5)
    streak = h3(math.floor(x * 0.5), math.floor(y / 3), math.floor(z / 2), 41)
    s = -0.25 if streak < 0.06 else 0.2 if streak > 0.95 else 0
    return (HIDE, base + t + s)


def ox_body():
    """The ox's torso with hump, rump, brisket and tail, a straw blanket (덕석) over its back; the wooden yoke (멍에)
    over the withers on a straw collar, the rope traces down its sides to the swingletree behind it (the cart's pole hangs on the ring in its middle). The
    head and the four legs are separate models (they move)."""
    m = BModel("ox_cart", shift=(8, 8, 8), scale=2.0)

    def hide(c):
        x, y, z = c.p
        rel = z - OZ
        if c.face in ("east", "west"):
            # muscles: a lit shoulder over the fore leg, a lit haunch over the hind leg, the flank's hollow between
            sh = math.hypot((rel + 8) / 4.5, (y - 20.5) / 4)
            hn = math.hypot((rel - 8.5) / 4.5, (y - 20.5) / 4.5)
            if y < 15.5:
                return ("bean", 2.2 if y > 14 else 1.6)          # the pale belly
            if sh < 1 or hn < 1:
                return (HIDE, 2.7 + (0.35 if (sh < 0.5 or hn < 0.5) else 0))
            if -2 < rel < 4 and 16.5 < y < 20:
                return (HIDE, 1.8)                               # the flank's hollow
            if y > 24:
                return (HIDE, 2.9)                               # the back's lit edge
        if c.face == "south":
            if abs(x) < 1 and y > 15:
                return (HIDE, 1.7)                               # between the hips
            return (HIDE, 2.3)
        if c.face == "north":
            return (HIDE, 2.5 if y > 18 else 2.0)
        return _coat(c)
    p = Paint(HIDE, 2, noise=0, rim=0.5, ao=0.6, decal=hide)

    m.group("body")
    m.box("torso", (-6, 15, OZ - 13), (6, 25, OZ + 13), p)
    m.box("belly", (-5, 13, OZ - 10), (5, 15, OZ + 9), p)
    m.box("back", (-5, 25, OZ - 10), (5, 26, OZ + 13), p)
    m.box("hump", (-3.5, 26, OZ - 11), (3.5, 28, OZ - 5), p)
    m.box("brisket", (-4.5, 13.5, OZ - 14.5), (4.5, 24, OZ - 13), p)
    m.box("rump", (-5, 16, OZ + 13), (5, 25.5, OZ + 14), p)
    for s in (1, -1):
        xa, xb = sorted((6 * s, 6.5 * s))
        m.box("hips", (xa, 22, OZ + 6), (xb, 25, OZ + 12), p)
        m.box("shoulder", (xa, 18, OZ - 11), (xb, 23, OZ - 5), p)

    # the tail: hanging from the rump, a dark tuft at its end
    def tail(c):
        if c.p[1] < 12.5:
            return ("soot", 1.4 + (0.7 if h3(math.floor(c.p[0] * 2), math.floor(c.p[1]), 3, 5) > 0.5 else 0))
        return (HIDE, 1.8 + (0.6 if c.face in ("south", "up") else 0))
    tp = Paint(HIDE, 2, noise=0, rim=0.3, decal=tail)
    m.box("tail", (-0.75, 12.5, OZ + 14), (0.75, 25, OZ + 15), tp)
    m.box("tail_tuft", (-1.25, 9, OZ + 13.75), (1.25, 12.5, OZ + 15.25), tp)

    # a woven straw blanket (덕석) over the back (a diagonal weave, lit on top), a red cloth hem round it
    def mat(c):
        x, y, z = c.p
        rel = z - OZ
        if (c.face == "up" and (abs(x) > 5.7 or rel < -3.4 or rel > 4.4)) or (c.face != "up" and (y < 22 or rel < -3.4 or rel > 4.4)):
            return ("crimson", 2.2 if c.face == "up" or y > 22 else 1.6)          # the hem
        if c.face == "down":
            return ("straw", 0.8)
        u = math.floor(z * 2) if c.face in ("up", "east", "west") else math.floor(x * 2)
        v = math.floor(y * 2) if c.face != "up" else math.floor(x * 2)
        k = (u + v) % 4
        base = 2.6 if c.face == "up" else lerp(y, 21, 26, 1.6, 2.4)
        return ("straw", base + (0.5 if k == 0 else -0.4 if k == 2 else 0))
    mp = Paint("straw", 2, noise=0, rim=0.4, ao=0.5, decal=mat)
    m.box("mat", (-6.5, 21, OZ - 4), (6.5, 26.5, OZ + 5), mp)

    m.group("yoke")

    # the yoke: a bent beam of dark wood over the withers, worn pale on top, iron rings at its ends; two pegs down
    # either side of the neck, a padded straw collar under the beam
    def yoke(c):
        x, y, z = c.p
        if abs(abs(x) - 8.3) < 0.6:
            return ("iron", 2.6)
        if c.face == "up":
            return ("wood_dark", 3.0 + (0.5 if abs(x) < 3 else 0))
        return ("wood_dark", 1.8 + _grain(c, "x", 2))
    yp = Paint("wood_dark", 2, noise=0, rim=0.6, decal=yoke)
    YZ = (OZ - 13.5, OZ - 11.5)
    m.box("yoke", (-9, 27, YZ[0]), (9, 28.5, YZ[1]), yp)
    m.box("yoke_arch", (-4, 28.5, YZ[0]), (4, 29.5, YZ[1]), yp)
    for s in (1, -1):
        xa, xb = sorted((6.5 * s, 7.5 * s))
        m.box("yoke_peg", (xa, 19, YZ[0] + 0.5), (xb, 27, YZ[1] - 0.5), Paint("wood_dark", 2, "grain", noise=0))

    def collar(c):
        k = (math.floor(c.p[0] * 1.5) + math.floor(c.p[1] * 1.5)) % 3
        return ("straw", 1.8 + (0.8 if k == 0 else 0) - (0.6 if c.face == "down" else 0))
    m.box("collar", (-6.5, 25, YZ[0] + 0.25), (6.5, 27, YZ[1] - 0.25), Paint("straw", 2, noise=0, decal=collar))

    # the traces: a rope from each yoke peg along the ox's side, down at the back to the swingletree
    def rope(c):
        k = math.floor(c.p[2] * 1.4 + c.p[1] * 1.4) % 2
        return ("burlap", 2.6 if k else 1.5)
    rp = Paint("burlap", 2, noise=0, rim=0, ao=0.3, decal=rope)
    TY = 19.5
    drop = TY - RING[1] + 0.6
    for s in (1, -1):
        xa, xb = sorted((7 * s, 7.6 * s))
        m.box("trace", (xa, TY, YZ[1] - 0.5), (xb, TY + 0.6, RING[2] - 1.75 - drop), rp)
        # down to the swingletree's end at 45 degrees
        z0 = RING[2] - 1.75 - drop
        m.box("trace", (xa, TY + 0.6 - drop * math.sqrt(2), z0), (xb, TY + 0.6, z0 + 0.6), rp,
              rotation=("x", -45, (xa, TY + 0.3, z0 + 0.3)))

    def tree(c):
        x = c.p[0]
        if abs(x) < 1.2:
            return ("iron", 2.8 if c.face == "up" else 1.8)     # the ring the pole hangs on
        if abs(abs(x) - 7.3) < 0.6:
            return ("iron", 2.2)
        return ("wood_dark", (2.6 if c.face == "up" else 1.6) + _grain(c, "x", 4))
    m.box("swingletree", (-8, RING[1] - 1, RING[2] - 2.25), (8, RING[1] + 0.75, RING[2] - 1)
          , Paint("wood_dark", 2, noise=0, rim=0.5, decal=tree))
    m.box("ring", (-1.5, RING[1] - 1.5, RING[2] - 1), (1.5, RING[1] + 1, RING[2] + 0.5),   # wider than the pole in it (and the tail)
          IRON(decal=lambda c: ("iron", 2.8 if c.face == "up" else 1.8)))
    m.display = _held_display(m)
    return m


def _hoof(c, y_top):
    """Hooves: dark horn, cloven down the front, a lit rim at the top."""
    x = c.p[0]
    if c.face == "north" and abs(x) < 0.3:
        return ("iron", 0.6)
    return ("onggi", 1.6 if c.p[1] > y_top - 0.6 else 0.9)


def _leg(name, hind):
    """One leg, hip at the model's (8, 8, 8) (the pivot), hoof on the ground 14 px below it. Fore: a straight column
    with a knee; hind: a broad thigh, the hock bent backwards. The coat darkens down the leg to dark hooves."""
    m = BModel(name, shift=(8, 8, 8), floor=None)
    top = 3.5

    def skin(c):
        x, y, z = c.p
        if y < -12.5:
            return _hoof(c, -12.5)
        lit = 0.4 if c.face in ("north", "up") else 0
        if y < -9:
            return (HIDE, 1.4 + lit)                             # the shin, darker
        return (HIDE, 2.0 + lit + lerp(y, -9, 2, -0.3, 0.4))
    p = Paint(HIDE, 2, noise=0, rim=0.4, ao=0.4, decal=skin)
    if hind:
        m.box("thigh", (-2.25, -5, -2), (2.25, top, 3), p)
        m.box("gaskin", (-1.75, -8.5, -1), (1.75, -5, 2.5), p)
        m.box("hock", (-1.5, -9.5, 0), (1.5, -8.5, 3), p)
        m.box("cannon", (-1.5, -12.5, -0.5), (1.5, -9.5, 2.5), p)
        m.box("hoof", (-1.75, -14, -1), (1.75, -12.5, 2.5), p)
    else:
        m.box("forearm", (-2, -6, -2.25), (2, top, 2.25), p)
        m.box("knee", (-1.75, -8.5, -1.75), (1.75, -6, 1.75), p)
        m.box("cannon", (-1.5, -12.5, -1.5), (1.5, -8.5, 1.5), p)
        m.box("hoof", (-1.75, -14, -2.25), (1.75, -12.5, 1.5), p)
    m.display = presets.furniture(gui_scale=0.6)
    return m


def ox_leg_fore():
    return _leg("ox_leg_fore", hind=False)


def ox_leg_hind():
    return _leg("ox_leg_hind", hind=True)


def ox_head():
    """The head and neck, pivoting at the neck's root (the model's (8, 8, 8)): a long face held low, a pale muzzle
    with a dark nose, dark eyes, a curly forelock, short horns out and up (pale, dark tips), ears out to the sides,
    the dewlap under the throat, a wooden nose ring (코뚜레) with the lead rope, and a brass cowbell (워낭) on a
    leather strap round the neck."""
    m = BModel("ox_head", shift=(8, 8, 8), floor=None)

    def face(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face in ("east", "west"):
            if -11.5 <= z < -9.5 and -2 <= y < 0:
                # the eye: dark, a glint, a pale lid over it
                if math.floor(z) == -11 and math.floor(y) == -1:
                    return ("car_white", 3)
                return ("iron", 0.6)
            if -12 <= z < -9 and 0 <= y < 1:
                return (HIDE, 1.4)                               # the brow's shadow
            if z < -12:
                return (HIDE, 2.4 + lerp(y, -7, 1, -0.5, 0.3))  # the cheek towards the muzzle
            return _coat(c, 2.1)
        if c.face == "north":
            if y > 0 and ax < 2.5:
                return (HIDE, 3.0 if (math.floor(x) + math.floor(y)) % 2 else 2.4)   # the curly forelock
            if ax < 1:
                return (HIDE, 1.9)                               # the bridge of the nose
            return (HIDE, 2.4)
        if c.face == "up":
            return (HIDE, 2.8 if z < -7 else 2.4)
        if c.face == "down":
            return ("bean", 1.4)
        return _coat(c, 2.0)
    p = Paint(HIDE, 2, noise=0, rim=0.5, ao=0.5, decal=face)
    m.group("neck")
    m.box("neck", (-3.5, -5, -7), (3.5, 4, 1), p)
    m.box("dewlap", (-1.5, -8, -6.5), (1.5, -5, 0), Paint(HIDE, 2, noise=0, decal=lambda c: (HIDE, 2.0 if c.face != "down" else 1.2)))
    m.group("head")
    m.box("skull", (-4, -7, -13.5), (4, 2, -7), p)
    m.box("poll", (-3, 2, -11), (3, 3, -7.5), p)

    def muzzle(c):
        x, y, z = c.p
        if c.face == "north":
            if -6 <= y < -4.5 and abs(abs(x) - 1.8) < 0.7:
                return ("soot", 0.6)                             # the nostrils
            if y < -3:
                return ("terracotta", 1.0 if y < -6 else 1.5)    # the wet nose
            return ("bean", 2.4)
        if c.face == "down":
            return ("bean", 1.2)
        if c.face == "up":
            return ("bean", 2.6)
        return ("bean", 2.2 if y > -4 else 1.6)
    m.box("muzzle", (-3.5, -7.5, -16), (3.5, -2, -13.5), Paint("bean", 2, noise=0, rim=0.4, decal=muzzle))

    # horns: out from the sides of the poll, then up and a little forward; pale, dark tips
    def horn(c):
        tip = c.p[1] > 4.5
        return ("onggi", 1.4) if tip else ("bean", 3.0 if c.face in ("up", "north") else 2.3)
    hp = Paint("bean", 3, noise=0, rim=0.3, decal=horn)
    for s in (1, -1):
        xa, xb = sorted((4 * s, 6.5 * s))
        m.box("horn", (xa, 0.5, -10), (xb, 2, -8.5), hp)
        xa, xb = sorted((6 * s, 7.5 * s))
        m.box("horn", (xa, 2, -10), (xb, 4, -8.5), hp)
        xa, xb = sorted((6 * s, 7 * s))
        m.box("horn", (xa, 4, -10.5), (xb, 5.5, -9.5), hp)

    # ears: flat, out to the sides under the horns, darker inside
    def ear(c):
        if c.face == "north":
            return ("bean", 1.4)
        return (HIDE, 2.0 if c.face != "up" else 2.6)
    for s in (1, -1):
        xa, xb = sorted((4 * s, 7.5 * s))
        m.box("ear", (xa, -2.5, -8.5), (xb, -1, -7), Paint(HIDE, 2, noise=0, decal=ear))

    # the nose ring (wood) through the septum and the lead rope back along the neck's left side
    wood = Paint("hinoki", 1, noise=0, decal=lambda c: ("hinoki", 1.2 if c.face != "up" else 2))
    m.box("nose_ring", (-2.5, -7, -16.5), (2.5, -6, -16), wood)
    for s in (1, -1):
        xa, xb = sorted((3.5 * s, 4 * s))
        m.box("nose_ring", (xa, -7, -16.5), (xb, -4.5, -13.5), wood)
    m.box("lead", (-4.5, -5, -13.5), (-4, -4.5, 1), Paint("burlap", 2, noise=0, decal=lambda c: ("burlap", 2.4 if math.floor(c.p[2]) % 2 else 1.4)))

    # the cowbell on a strap round the neck
    def strap(c):
        return ("leather", 2.2 if c.face == "up" else 1.6)
    sp = Paint("leather", 2, noise=0, decal=strap)
    # (half a pixel thick: thinner flickers or vanishes in the game)
    m.box("strap", (-4, 4, -4), (4, 4.5, -3), sp)
    m.box("strap", (-4, -5.5, -4), (4, -5, -3), sp)
    for s_ in (1, -1):
        xa, xb = sorted((3.5 * s_, 4 * s_))
        m.box("strap", (xa, -5, -4), (xb, 4, -3), sp)

    def bell(c):
        if c.p[1] < -10.5:
            return ("brass", 1.2)
        return ("brass", 3.0 if c.face in ("north", "west") else 2.2)
    m.box("bell", (-1.5, -11, -5), (1.5, -8, -2), Paint("brass", 2, noise=0, rim=0.6, decal=bell))
    m.box("bell_top", (-0.5, -8, -4), (0.5, -7, -3), Paint("brass", 2, noise=0))
    m.display = presets.furniture(gui_scale=0.6)
    return m


# ================================================================================================
# The ox cart (소달구지): a wooden cart on two big spoked wheels
# ================================================================================================

OXCART = {"deck": (12, 13.5), "half_x": 10.5, "half_z": 17, "pole_tip": -28, "wheel_x": 13.5}


def wheel_oxcart():
    """A big wooden cart wheel, 18 px across: an iron tyre with nail heads, a felloe of six wooden segments, eight
    turned spokes (painted on the round faces: lit on their upper side) and a thick iron-banded hub."""
    m = BModel("wheel_oxcart", shift=(8, 8, 8), floor=None)
    d, w2 = 18, 1.25

    def side(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        lit = y - 0.5 * z
        a = math.atan2(z, y) + math.pi
        if c.face not in ("east", "west"):
            # the running face: the iron tyre, a nail head now and then
            k = math.floor(a / (2 * math.pi) * 28)
            return ("iron", 1.6 if k % 4 else 3.0)
        if rr > 8.0:
            return ("iron", 2.4 if lit > 0 else 1.6)               # the tyre's edge
        if rr > 6.5:
            u = a / (2 * math.pi) * 6
            if abs(u - round(u)) * (2 * math.pi / 6) * rr < 0.5:
                return ("wood_dark", 0.6)                          # a joint between felloe segments
            return ("wood", 1.8 + lerp(lit, -6, 6, -0.4, 0.8))     # the felloe, lit on its upper front
        if rr < 2.0:
            return ("iron", 2.6 if rr > 1.2 else 1.6)              # the hub's iron band and the axle's end
        ph = a % (math.pi / 4)
        dist = rr * math.sin(min(ph, math.pi / 4 - ph))
        if dist < 0.6:
            return ("wood", 2.8 if lit > 0 else 2.0)               # a spoke
        return ("wood_dark", -1.2 if lit > 2 else -0.8)            # the dark gap between spokes
    cyl(m, "wheel", (0, 0), d, -w2, w2, Paint("wood", 2, noise=0, rim=0.2, ao=0.2, decal=side), axis="x")

    def hub(c):
        if c.face in ("east", "west"):
            return ("iron", 2.8) if abs(c.p[1]) < 0.6 and abs(c.p[2]) < 0.6 else ("wood_dark", 2.4)
        return ("iron", 2.2 if c.face == "up" else 1.6)
    hp = Paint("wood_dark", 2, noise=0, rim=0.3, decal=hub)
    m.box("hub", (w2, -1.5, -1.5), (w2 + 1.5, 1.5, 1.5), hp)
    m.box("hub", (-w2 - 1.5, -1.5, -1.5), (-w2, 1.5, 1.5), hp)
    m.display = presets.furniture(gui_scale=0.6)
    return m


def ox_cart_wagon():
    """The cart, its axle at (0, 0, 0): a deck of planks on two long beams, low slatted sides, the driver's bench
    across the front on two posts, a single pole forward to the ox's swingletree (hitch: vehicles_farm.yml), the axle
    with its bolsters, a lantern hook, a coiled rope and a water gourd. Wheels: wheel_oxcart (separate)."""
    m = BModel("ox_cart_wagon", shift=(8, 8, 8), scale=2.0)
    C = OXCART
    Y0, Y1 = C["deck"]
    HX, HZ = C["half_x"], C["half_z"]

    m.group("deck")

    def plank(c):
        x, y, z = c.p
        if c.face == "up":
            board = math.floor((x + HX) / 3.5)
            edge = ((x + HX) % 3.5) < 0.5
            if edge:
                return ("wood_dark", 1.0)
            if any(abs(z - k) < 0.5 for k in (-14, 0, 14)) and abs(((x + HX) % 3.5) - 1.75) < 0.6:
                return ("iron", 2.0)                             # pegs into the cross beams
            stain = h3(math.floor(x / 2), 1, math.floor(z / 3), 33)
            return ("wood", 2.0 + (0.4 if board % 2 else -0.1) + _grain(c, "z", board) - (0.6 if stain < 0.07 else 0))
        if c.face in ("north", "south"):
            return ("wood", 1.4 if ((x + HX) % 3.5) > 0.5 else 0.6)  # the planks' end grain
        return ("wood", 1.6 + _grain(c, "z", 3))
    m.box("deck", (-HX, Y0, -HZ), (HX, Y1, HZ), Paint("wood", 2, noise=0, rim=0.5, decal=plank))

    def beam(c):
        return ("wood_dark", (2.2 if c.face == "up" else 1.4) + _grain(c, "z", 6))
    bp = Paint("wood_dark", 2, noise=0, decal=beam)
    for s in (1, -1):
        xa, xb = sorted((6 * s, 8.5 * s))
        m.box("beam", (xa, 10.5, -HZ), (xb, Y0, HZ + 1), bp)
    for z in (-14.5, 13.5):
        m.box("cross", (-6, 10.5, z), (6, Y0, z + 1.5), bp)

    # the sides: a top rail on short spindles, the gaps between them open (dark against the deck)
    def side(c):
        x, y, z = c.p
        if y > 16:
            if c.face == "up":
                return ("wood_dark", 2.8 + (0.4 if h3(math.floor(z), 2, 1, 9) < 0.2 else 0))
            return ("wood_dark", 2.0 + _grain(c, "z", 7))
        if c.face in ("east", "west"):
            k = (z + HZ) % 4
            if k < 1.25:
                return ("wood_dark", 2.2 if k < 0.6 else 1.6)    # a spindle
            return ("raw", CLEAR) if c.nu > 1 and c.nv > 1 else ("wood_dark", 1.4)
        return ("wood_dark", 1.6)
    for s in (1, -1):
        xa, xb = sorted((HX * s, (HX + 0.5) * s))
        m.box("side", (xa, Y1, -HZ + 6), (xb, 17.5, HZ), Paint("wood_dark", 2, noise=0, rim=0.3, ao=0.3, decal=side))
    # stakes at the side's ends, a low tail board
    for s in (1, -1):
        for z0 in (-HZ + 5, HZ - 1.5):
            xa, xb = sorted(((HX - 0.5) * s, (HX + 1) * s))
            m.box("stake", (xa, Y0 - 1, z0), (xb, 18, z0 + 1), Paint("wood_dark", 2, "grain", noise=0))
    m.box("tailboard", (-HX + 0.5, Y1, HZ - 1), (HX - 0.5, 15, HZ), Paint("wood", 2, noise=0, decal=lambda c: ("wood", 1.8 + _grain(c, "x", 8))))

    m.group("bench")

    # the driver's bench: a thick plank across the front on two posts, a straw cushion
    def bench(c):
        if c.face == "up":
            return ("wood", 2.8 + _grain(c, "x", 9) * 0.6)
        return ("wood", 1.6 + _grain(c, "x", 9))
    m.box("bench", (-HX + 0.5, 15, -HZ + 1), (HX - 0.5, 16, -HZ + 5), Paint("wood", 2, noise=0, rim=0.6, decal=bench))
    for s in (1, -1):
        xa, xb = sorted(((HX - 2.5) * s, (HX - 1) * s))
        m.box("bench_post", (xa, Y1, -HZ + 2), (xb, 15, -HZ + 3.5), Paint("wood_dark", 2, "grain", noise=0))

    def cushion(c):
        k = (math.floor(c.p[0]) + math.floor(c.p[2] * 2)) % 3
        return ("straw", 2.0 + (0.8 if k == 0 else 0))
    m.box("cushion", (-8, 16, -HZ + 1.5), (8, 16.5, -HZ + 4.5), Paint("straw", 2, noise=0, rim=0.2, decal=cushion))

    m.group("pole")

    # the pole: forward from under the deck to the ox's ring, an iron hook at its tip, rawhide lashings
    def pole(c):
        z = c.p[2]
        if z < C["pole_tip"] + 1.5:
            return ("iron", 2.4 if c.face == "up" else 1.6)
        if abs(z - (-HZ - 1)) < 0.75 or abs(z - (-HZ - 6)) < 0.75:
            return ("leather", 1.8 if c.face != "up" else 2.4)
        return ("wood_dark", (2.6 if c.face == "up" else 1.6) + _grain(c, "z", 11))
    m.box("pole", (-1, 11, C["pole_tip"]), (1, 13, -14.5), Paint("wood_dark", 2, noise=0, rim=0.5, decal=pole))
    m.box("pole_brace", (-4, 10.5, -HZ - 3), (4, 11.5, -HZ), bp)

    m.group("axle")
    m.box("axle", (-13, 8, -1), (13, 10, 1), IRON(decal=lambda c: ("iron", 2.4 if c.face == "up" else 1.6)))
    for s in (1, -1):
        xa, xb = sorted((6.5 * s, 9 * s))
        m.box("bolster", (xa, 10, -2.5), (xb, 10.5, 2.5), bp)

    m.group("extras")
    # a rope coil tied to the right side, a gourd hanging at the back, a small lantern hook by the bench
    def coil(c):
        k = math.floor(math.hypot(c.p[1] - 15.5, c.p[2] - 6) * 1.5)
        return ("burlap", 2.6 if k % 2 else 1.6)
    cyl(m, "rope", (15.5, 6), 5, HX + 0.5, HX + 1.5, Paint("burlap", 2, noise=0, rim=0.3, decal=coil), axis="x")   # clear of the wheel

    def gourd(c):
        y = c.p[1]
        if y > 15:
            return ("leaf_dark", 1.6)
        return ("straw", 3.0 if c.face in ("north", "west", "up") else 2.2)
    gp = Paint("straw", 3, noise=0, rim=0.5, decal=gourd)
    m.box("gourd", (-HX - 2.5, 10, 9), (-HX - 0.5, 12.5, 11), gp)
    m.box("gourd", (-HX - 2.25, 12.5, 9.25), (-HX - 0.75, 14, 10.75), gp)
    m.box("gourd", (-HX - 2, 14, 9.5), (-HX - 1, 15.5, 10.5), gp)
    m.box("gourd_cord", (-HX - 1.75, 15.5, 9.75), (-HX - 1.25, 17.5, 10.25), Paint("burlap", 1, noise=0))
    m.display = _held_display(m)
    return m


# ================================================================================================
# Loads (by how full the beds are): see the cargo builders at the end
# ================================================================================================

def _straw_bundle(m, x, y, z, length=10, d=4, axis="z", band=("burlap", 1.0)):
    """A bundle of rice straw (볏단) lying down: stalks along it with darker cut ends, tied twice."""
    def decal(c):
        px, py, pz = c.p
        along = pz if axis == "z" else px
        start = z if axis == "z" else x
        end_face = c.face in (("north", "south") if axis == "z" else ("east", "west"))
        if end_face:
            return ("straw", 0.8 + (0.8 if (math.floor(px) + math.floor(py) + math.floor(pz)) % 2 else 0))   # the cut stalks
        u = along - start
        if abs(u - length * 0.3) < 0.6 or abs(u - length * 0.7) < 0.6:
            return band
        across = px if axis == "z" else pz
        k = h3(math.floor(across * 2), math.floor(py * 2), 3, 41)
        return ("straw", 2.4 + (0.8 if k > 0.75 else -0.6 if k < 0.2 else 0))
    p = Paint("straw", 3, noise=0, rim=0.4, decal=decal)
    if axis == "z":
        m.box("straw", (x, y, z), (x + d, y + d - 0.5, z + length), p)
    else:
        m.box("straw", (x, y, z), (x + length, y + d - 0.5, z + d), p)


def _gamani(m, x, y, z, w=7, d=10, h=5):
    """A straw-mat rice sack (가마니) lying flat: a woven texture, its sewn ends, a rope tie round it."""
    def decal(c):
        px, py, pz = c.p
        if abs(pz - (z + d / 2)) < 0.6 and c.face != "down":
            return ("burlap", 1.2)
        if c.face in ("north", "south"):
            return ("straw", 1.4 + (0.8 if math.floor(px) % 2 else 0))
        k = (math.floor(px) + math.floor(pz)) % 2
        return ("straw", 1.8 + (0.6 if k else 0) + (0.4 if c.face == "up" else 0))
    p = Paint("straw", 2, noise=0, rim=0.5, decal=decal)
    m.box("gamani", (x, y, z), (x + w, y + h - 1, z + d), p)
    m.box("gamani", (x + 0.75, y + h - 1, z + 0.75), (x + w - 0.75, y + h, z + d - 0.75), p)


def _crock(m, x, y, z, d=6, h=7):
    """An earthenware crock (항아리): dark brown glaze, a lit shoulder, a lid of straw tied over its mouth."""
    def glaze(c):
        px, py, pz = c.p
        u = py - y
        if c.face == "up":
            return ("onggi", 3.0)
        lit = (px - x) < 0 or (pz - z) < 0
        shine = c.face in ("north", "west") and abs(u - h * 0.65) < 0.6 and abs((px + pz) - (x + z)) < 1.5
        if shine:
            return ("onggi", 4.2)
        return ("onggi", 2.0 + (0.6 if lit else 0) + lerp(u, 0, h, -0.5, 0.6))
    p = Paint("onggi", 2, noise=0, rim=0.4, decal=glaze)
    cyl(m, "crock", (x, z), d - 2, y, y + 1, p)
    cyl(m, "crock", (x, z), d, y + 1, y + h - 2, p)
    cyl(m, "crock", (x, z), d - 2, y + h - 2, y + h - 1, p)
    lid = Paint("straw", 2, noise=0, decal=lambda c: ("straw", 2.6 if (math.floor(c.p[0]) + math.floor(c.p[2])) % 2 else 1.8))
    cyl(m, "crock_lid", (x, z), d - 2, y + h - 1, y + h, lid)
    m.box("crock_tie", (x - (d - 2) / 2 - 0.01, y + h - 1.5, z - 0.5), (x + (d - 2) / 2 + 0.01, y + h - 1, z + 0.5), Paint("burlap", 1, noise=0))


def ox_cart_cargo(stage):
    """What lies on the ox cart's deck behind the bench (21 x 26 px; floor middle at the model's (8, 8, 8)): sheaves of
    rice straw, straw-mat rice sacks, crocks of soy sauce; full: straw piled high under a rope net."""
    m = BModel(f"ox_cart_cargo_{stage}", shift=(8, 8, 8))
    if stage >= 1:
        _gamani(m, -10, 0, 2, 7, 10, 5)
        _crock(m, 7, 0, 9)
    if stage >= 2:
        _gamani(m, -3, 0, 2, 7, 10, 5)
        _crock(m, 7, 0, 3)
        _straw_bundle(m, -10, 0, -12, 12, 4)
    if stage >= 3:
        _gamani(m, -9.5, 5, 1.5, 7, 10, 5)
        for bx in (-6, -2, 2):
            _straw_bundle(m, bx, 0, -12, 12, 4)
    if stage >= 4:
        _gamani(m, -2.5, 5, 1.5, 7, 10, 5)
        for bx, by in ((-10, 3.5), (-6, 3.5), (-2, 3.5), (-8, 7), (-4, 7)):
            _straw_bundle(m, bx, by, -12, 12, 4)
        _straw_bundle(m, 2, 3.5, -11.5, 8, 3.5, axis="x")
        _rope_net(m, -10.75, 10.75, -12.5, 12.5, 0, 11, rope=("burlap", 1.2))   # its sides off the deck's edges
    m.display = presets.furniture(gui_scale=0.4)
    return m


# ================================================================================================
# Lugged farm tyres
# ================================================================================================

def _lug_wheel(name, d, width, rim_ramp, rim_step, lugs, hub_d, chevron=True, disc="steel"):
    """A farm tyre: deep lugs across the tread (a chevron: the two halves staggered), a bulging sidewall with the
    lugs' ends showing round its edge, a painted steel rim (rim_ramp) with bolt holes, a hub cap."""
    m = BModel(name, shift=(8, 8, 8), floor=None)
    r = d / 2
    rim_r = d / 2 - 2.5

    def side(c):
        x, y, z = c.p
        rr = math.hypot(y, z)
        lit = y - 0.5 * z
        a = math.atan2(z, y) + math.pi
        k = a / (2 * math.pi) * lugs
        if c.face not in ("east", "west"):
            # the tread: a lug every 1/lugs of the way round, its two halves offset half a lug (chevron)
            off = 0.5 if (chevron and x > 0) else 0
            t = (k + off + abs(x) / width * (0.6 if chevron else 0)) % 1.0
            if t < 0.45:
                return ("rubber", 2.6 if c.face != "down" else 1.6)
            return ("rubber", 0.4)
        if rr > r - 0.9:
            return ("rubber", 1.8 if (k % 1.0) < 0.45 else 0.8)      # the lugs' ends round the edge
        if rr > rim_r:
            return ("rubber", lerp(lit, -r, r, 1.2, 2.6))           # the sidewall's bulge, lit on its upper front
        if rr > rim_r - 0.8:
            return (rim_ramp, rim_step + 0.8)                      # the rim's lip
        if rr < hub_d / 2:
            if rr < 0.8:
                return ("steel", 3.6)
            return ("steel", 2.6 if lit > 0 else 2.0)              # the hub cap
        # the disc: bolt holes round it, a darker dish towards the hub
        u = (a / (2 * math.pi) * 6) % 1.0
        if abs(rr - (hub_d / 2 + 1)) < 0.6 and u < 0.3:
            return ("iron", 1.0)
        return (rim_ramp, rim_step + lerp(lit, -rim_r, rim_r, -0.4, 0.6) - (0.4 if rr < hub_d / 2 + 1.5 else 0))
    cyl(m, "tyre", (0, 0), d, -width / 2, width / 2, Paint("rubber", 1, noise=0, rim=0.2, ao=0.2, decal=side), axis="x")

    def cap(c):
        if c.face in ("east", "west"):
            return ("steel", 3.4 if (c.p[1] > 0 and c.p[2] < 0) else 2.4)
        return ("steel", 2.0)
    cp = Paint("steel", 2, noise=0, rim=0.2, decal=cap)
    w2 = width / 2
    m.box("hub", (w2, -1, -1), (w2 + 0.5, 1, 1), cp)
    m.box("hub", (-w2 - 0.5, -1, -1), (-w2, 1, 1), cp)
    m.display = presets.furniture(gui_scale=0.6)
    return m


def wheel_tiller():
    """The tiller's and its trailer's tyre: 13 px across, 3.5 wide, deep chevron lugs, a red rim."""
    return _lug_wheel("wheel_tiller", 13, 3.5, "vermilion", 1.8, 14, 3)


def wheel_tractor_front():
    """The tractor's small front tyre: 12 px, ribbed (lugs straight across), a cream rim."""
    return _lug_wheel("wheel_tractor_front", 12, 3.5, "cream", 1.6, 16, 3, chevron=False)


def wheel_tractor_rear():
    """The tractor's big rear tyre: 22 px across, 6 wide, tall chevron lugs, a cream rim with its bolts."""
    return _lug_wheel("wheel_tractor_rear", 22, 6, "cream", 1.6, 22, 5)


# ================================================================================================
# 경운기 (power tiller): engine, flywheel, belt, gearbox, two wheels, long handlebars
# ================================================================================================

RED = "vermilion"
TILLER_AX = 9           # the tiller's axle lies this far behind its centre (drawn px): the footprint centred on its body


def _paint_metal(ramp, base=2.0, edge=0.6):
    """Painted sheet metal: lit top edges, a darker bottom band of road dust, a scuff now and then."""
    def decal(c):
        x, y, z = c.p
        if c.face == "up":
            return (ramp, base + 0.7)
        if c.face == "down":
            return (ramp, base - 1.2)
        top = c.hi[1] - y
        if top < 0.7:
            return (ramp, base + edge)
        if y - c.lo[1] < 1:
            return (ramp, base - 0.6)
        scuff = h3(math.floor(x), math.floor(y), math.floor(z), 57)
        return (ramp, base + (-0.4 if scuff < 0.04 else 0))
    return decal


def power_tiller():
    """The power tiller (경운기), facing north: a horizontal single-cylinder diesel on a frame ahead of the axle -
    crankcase, finned cylinder head, the cream water hopper with its cap and float (the famous 통통 engine), the red
    fuel tank behind it, an air cleaner, a muffler with a tall pipe and a rain flap, a headlamp; the spoked flywheel
    is a separate model on its right (it spins); a belt under its guard on the left down to the gearbox; the gearbox
    case between the two wheels, the hitch behind it; the long handlebars reaching back to the driver on the trailer,
    their rubber grips and clutch levers."""
    m = BModel("power_tiller", shift=(8, 8, 8), scale=2.0)
    A = TILLER_AX

    def z(v):
        return v + A

    red = Paint(RED, 2, noise=0, rim=0.6, decal=_paint_metal(RED, 2.0))
    iron = Paint("iron", 3, noise=0, rim=0.5, decal=_paint_metal("iron", 2.6, 0.8))

    m.group("gearbox")

    def gearbox(c):
        x, y, zz = c.p
        if c.face in ("east", "west") and abs(zz - z(0)) < 1.5 and abs(y - 6.5) < 1.5:
            return ("iron", 2.0)                                  # the axle's boss
        if c.face in ("north", "south") and abs(x) < 3 and abs(y - 9) < 0.5:
            return ("steel", 3.4)                                 # a bolted seam
        return _paint_metal(RED, 2.0)(c)
    gp = Paint(RED, 2, noise=0, rim=0.6, decal=gearbox)
    m.box("gearbox", (-4.5, 3.5, z(-4.5)), (4.5, 12, z(4.5)), gp)
    m.box("gearbox_top", (-3.5, 12, z(-3.5)), (3.5, 13.5, z(3.5)), gp)
    for s in (1, -1):
        xa, xb = sorted((4.5 * s, 8 * s))
        m.box("axle_housing", (xa, 5, z(-1.5)), (xb, 8, z(1.5)), iron)
    # the hitch: a bracket behind the gearbox with a pin
    m.box("hitch", (-1.5, 6, z(4.5)), (1.5, 8.5, z(7)), iron)
    m.box("hitch_pin", (-0.5, 5, z(5.5)), (0.5, 10, z(6.5)), Paint("steel", 4, noise=0))

    m.group("frame")
    for s in (1, -1):
        xa, xb = sorted((3 * s, 4.5 * s))
        m.box("rail", (xa, 9, z(-25)), (xb, 11, z(-4.5)), iron)
    # the bumper / front weight
    def bumper(c):
        if c.face == "north" and math.floor(c.p[1]) == 11:
            return ("hazard", 0.6) if math.floor(c.p[0]) % 4 < 2 else ("iron", 1.0)
        return _paint_metal("iron", 2.4)(c)
    m.box("bumper", (-6, 8, z(-28)), (6, 13, z(-25)), Paint("iron", 2, noise=0, rim=0.6, decal=bumper))

    m.group("engine")

    def crank(c):
        x, y, zz = c.p
        if c.face in ("east", "west") and math.floor(zz) % 3 == 0 and 12 < y < 17:
            return ("iron", 1.4)                                  # ribs cast in the case
        if c.face in ("east", "west") and math.floor(y) == 17:
            return ("steel", 3.0)                                 # the joint to the cylinder
        return _paint_metal("iron", 2.4, 0.8)(c)
    m.box("crankcase", (-5, 11, z(-24)), (5, 18, z(-8)), Paint("iron", 2, noise=0, rim=0.6, decal=crank))

    def fins(c):
        x, y, zz = c.p
        if c.face in ("east", "west", "north", "south"):
            return ("steel", 3.4 if math.floor(y * 2) % 2 == 0 else 1.8)   # cooling fins
        return ("steel", 3.0)
    m.box("cylinder", (-4, 18, z(-14)), (4, 21, z(-8)), Paint("steel", 3, noise=0, rim=0.4, decal=fins))

    # the water hopper (cream), its filler cap and the float stick that shows it is full
    def hopper(c):
        x, y, zz = c.p
        if c.face == "up":
            return ("cream", 2.8 + (0.4 if abs(x) < 2 and abs(zz - z(-19)) < 2 else 0))
        if c.face == "north" and 20 <= y < 24 and abs(x) < 4:
            k = math.floor(y - 20)
            return ("iron", 1.2) if k % 2 == 0 else ("steel", 3.0)  # the radiator's louvres
        top = c.hi[1] - y
        return ("cream", 2.6 if top < 0.7 else 1.8 + lerp(y, 18, 26, -0.3, 0.4))
    m.box("hopper", (-5.5, 18, z(-24.5)), (5.5, 26, z(-14)), Paint("cream", 2, noise=0, rim=0.6, decal=hopper))
    m.box("hopper_cap", (-1.5, 26, z(-20.5)), (1.5, 27, z(-17.5)), Paint("steel", 4, noise=0, decal=lambda c: ("steel", 4.2 if c.face == "up" else 3.0)))
    m.box("float", (2, 26, z(-22)), (2.5, 29, z(-21.5)), Paint("fox", 3, noise=0, decal=lambda c: ("fox", 3.4 if c.p[1] > 28 else 2.6)))

    # the fuel tank (red) behind the hopper, a cap and a sight strip
    def tank(c):
        x, y, zz = c.p
        if c.face in ("east", "west") and abs(y - 21.5) < 0.5 and abs(zz - z(-10.5)) < 2:
            return ("glass", 2.4)
        return _paint_metal(RED, 2.2)(c)
    m.box("tank", (-4.5, 21, z(-13.5)), (4.5, 25, z(-7)), Paint(RED, 2, noise=0, rim=0.6, decal=tank))
    m.box("tank_cap", (-1, 25, z(-11)), (1, 26, z(-9)), Paint("steel", 4, noise=0))

    # the air cleaner on the left, the muffler and pipe on the right
    def can(c):
        y = c.p[1]
        if abs(y - 17.5) < 0.5:
            return ("steel", 3.8)                                 # its clamp band
        return ("iron", 2.6 if c.face in ("up", "west", "north") else 1.8)
    m.box("air_cleaner", (-8, 19, z(-13)), (-5, 25, z(-9.5)), Paint("iron", 2, noise=0, rim=0.6, decal=can))
    m.box("air_pipe", (-5, 22, z(-12)), (-4.5, 23.5, z(-10.5)), Paint("steel", 2, noise=0))

    def muffler(c):
        y = c.p[1]
        if c.face in ("east", "west", "north", "south") and math.floor(y) % 3 == 0:
            return ("soot", 1.4)                                  # the heat shield's slots
        return ("soot", 2.4 + (0.6 if c.face == "up" else 0))
    m.box("exhaust_pipe", (4, 19, z(-7.5)), (5, 20.5, z(-6)), Paint("iron", 2, noise=0))
    m.box("muffler", (5, 17, z(-8)), (8, 24, z(-4)), Paint("soot", 2, noise=0, rim=0.5, decal=muffler))
    m.box("exhaust", (6, 24, z(-7)), (7.5, 31, z(-5.5)), Paint("iron", 2, noise=0, decal=lambda c: ("soot", 0.8 if c.p[1] > 30 else 2.0)))
    m.box("rain_flap", (5.75, 31, z(-7.25)), (7.75, 31.5, z(-4.75)), Paint("steel", 3, noise=0, decal=lambda c: ("steel", 3.6 if c.face == "up" else 2.4)))

    # a headlamp on the hopper's front corner
    def lamp_px(c):
        if c.face == "north":
            r = math.hypot(c.p[0] - (-3.5), c.p[1] - 27.5)
            return ("lamp", 4) if r < 0.8 else ("lamp", 3) if r < 1.4 else ("blade", 3)
        return ("iron", 2.4)
    m.box("headlamp", (-5, 26, z(-25)), (-2, 29, z(-22.5)), Paint("iron", 2, noise=0, decal=lamp_px, light=True))

    # the flywheel's shaft housing on the right (the flywheel itself spins: power_tiller_flywheel)
    m.box("shaft", (5, 14, z(-18.5)), (6, 16, z(-16.5)), Paint("steel", 3, noise=0))

    # the belt guard on the left: the belt shows in its slots
    def guard(c):
        x, y, zz = c.p
        if c.face == "west":
            if math.floor(zz) % 4 == 1 and 11 < y < 18:
                return ("rubber", 0.8)                            # the belt through a slot
            if math.floor(y) in (10, 18):
                return (RED, 2.6)
        return _paint_metal(RED, 1.8)(c)
    m.box("belt_guard", (-7.5, 9, z(-15)), (-5, 18, z(1)), Paint(RED, 2, noise=0, rim=0.6, decal=guard))

    m.group("handlebars")
    tube = Paint("steel", 3, noise=0, rim=0.3, decal=lambda c: ("steel", 3.6 if c.face == "up" else 2.6))
    for s in (1, -1):
        xa, xb = sorted((4 * s, 5 * s))
        # up from the gearbox at 45 degrees, then back level to the grips
        # up from the gearbox at 45 degrees, out to the sides, then the grips
        m.box("bar", (xa, 11.5, z(2)), (xb, 12.5, z(2) + 7.5 * math.sqrt(2)), tube, rotation=("x", -45, (xa, 12, z(2))))
        xa, xb = sorted((4 * s, 8 * s))
        m.box("bar", (xa, 19, z(9)), (xb, 20, z(10)), tube)
        xa, xb = sorted((7 * s, 8 * s))
        m.box("bar", (xa, 19, z(10)), (xb, 20, z(11)), tube)
        m.box("grip", (xa - 0.25, 18.75, z(11)), (xb + 0.25, 20.25, z(15)),
              Paint("rubber", 1, noise=0, decal=lambda c: ("rubber", 2.4 if math.floor(c.p[2]) % 2 else 1.2)))
        xa, xb = sorted((5 * s, 6 * s))
        m.box("lever", (xa, 20, z(10)), (xb, 20.5, z(14)), Paint("blade", 2, noise=0))
    m.box("bar_cross", (-4, 19, z(7)), (4, 20, z(8)), tube)

    # a small plate on the gearbox: the tiller's number (no brand)
    def plate(c):
        if c.face != "south":
            return ("car_white", 1)
        col, row = math.floor(c.p[0] + 2.5), math.floor(11.99 - c.p[1])
        if row in (0, 3) or col in (0, 4):
            return ("car_white", 1.2)
        return ("iron", 1.0) if (col + row) % 2 else ("car_white", 3)
    m.box("plate", (-2.5, 8, z(4.5)), (2.5, 12, z(5)), Paint("car_white", 2, noise=0, decal=plate))
    m.display = _held_display(m)
    return m


def power_tiller_flywheel():
    """The tiller's flywheel (on its right side, axle along x, the model's (8, 8, 8)): a heavy red disc with six
    spokes and openings between them, a polished rim and a crank dog in the middle. Spins while the engine runs."""
    m = BModel("power_tiller_flywheel", shift=(8, 8, 8), floor=None)

    def disc(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        a = math.atan2(z, y) + math.pi
        lit = y - 0.5 * z
        if c.face not in ("east", "west"):
            return ("blade", 2.4 if c.face != "down" else 1.4)    # the turned rim
        if rr > 5.0:
            return ("blade", 2.0 + lerp(lit, -6, 6, -0.4, 0.8))
        if rr < 1.4:
            return ("steel", 3.6 if rr < 0.7 else 2.4)
        ph = (a / (2 * math.pi) * 6) % 1.0
        if 0.25 < ph < 0.75 and 2 < rr < 4.6:
            return ("iron", -0.6)                                 # an opening between spokes
        return (RED, 2.0 + (0.5 if lit > 0 else 0))
    cyl(m, "flywheel", (0, 0), 12, -0.75, 0.75, Paint(RED, 2, noise=0, rim=0.3, ao=0.2, decal=disc), axis="x")
    m.box("dog", (0.75, -0.75, -0.75), (1.75, 0.75, 0.75), Paint("steel", 3, noise=0))
    m.display = presets.furniture(gui_scale=0.6)
    return m


def power_tiller_trailer():
    """The tiller's trailer, axle at (0, 0, 0): a steel bed with a plank floor and red drop sides (stake pockets,
    hinges, latches), mudguards over the two wheels outside it, the seat box across the front with a vinyl cushion
    and a back rail, a toolbox under it, the A-frame drawbar forward to the tiller's hitch."""
    m = BModel("power_tiller_trailer", shift=(8, 8, 8), scale=2.0)
    HX, HZ, Y0, Y1 = 11, 16, 9, 10.5

    m.group("bed")

    def floor(c):
        x, y, z = c.p
        if c.face == "up":
            board = math.floor((x + HX) / 4)
            if ((x + HX) % 4) < 0.5:
                return ("wood_dark", 1.0)
            return ("wood", 1.8 + (0.4 if board % 2 else 0) + _grain(c, "z", board) - (0.5 if h3(math.floor(x / 2), 1, math.floor(z / 3), 21) < 0.08 else 0))
        return ("iron", 2.0)
    m.box("floor", (-HX, Y0, -HZ), (HX, Y1, HZ), Paint("wood", 2, noise=0, rim=0.4, decal=floor))
    iron = Paint("iron", 2, noise=0, rim=0.5, decal=_paint_metal("iron", 2.4, 0.8))
    for s in (1, -1):
        xa, xb = sorted((5 * s, 7 * s))
        m.box("chassis", (xa, 7, -HZ), (xb, Y0, HZ), iron)

    def side(c):
        x, y, z = c.p
        out = c.face in ("east", "west", "north", "south")
        along = z if c.face in ("east", "west") else x
        if out:
            k = abs(along) % 8
            if k < 1:
                return (RED, 1.2)                                 # a stake pocket / rib
            if math.floor(y) == 13:
                return (RED, 1.4)                                 # a pressed groove
            if math.floor(y) == 15 and abs(abs(along) - 4) < 1:
                return ("steel", 3.2)                             # a latch
            # years of work: paint chipped to rust along the top edge and the bottom, a rust run under each rib
            chip = h3(math.floor(along), math.floor(y), 5 if c.face in ("east", "south") else 6, 58)
            if (y > 15.2 or y < Y0 + 1.5) and chip < 0.18:
                return ("terracotta", 1.0 + chip * 4)
            if 1 <= k < 2 and y < 15 and h3(math.floor(along), 0, 7, 59) < 0.5 and y > 15 - 4 * h3(math.floor(along), 1, 7, 59):
                return ("terracotta", 1.6)
        return _paint_metal(RED, 2.0)(c)
    sp = Paint(RED, 2, noise=0, rim=0.6, decal=side)
    for s in (1, -1):
        xa, xb = sorted((HX * s, (HX + 0.75) * s))
        m.box("side", (xa, Y0, -HZ + 6), (xb, 16, HZ + 0.75), sp)
    m.box("tailgate", (-HX, Y0, HZ), (HX, 16, HZ + 0.75), sp)

    # mudguards over the wheels (outside the bed)
    def guard(c):
        x, y, z = c.p
        if c.face != "up" and h3(math.floor(x * 2), math.floor(y * 2), math.floor(z * 2), 68) < 0.3:
            return ("soil", 1.4 + h3(math.floor(x), math.floor(y), math.floor(z), 69))   # dried mud
        if c.face == "up":
            return ("iron", 2.8)
        return ("iron", 1.8)
    gp = Paint("iron", 2, noise=0, rim=0.5, decal=guard)
    for s in (1, -1):
        xa, xb = sorted(((HX + 0.75) * s, 15.5 * s))
        m.box("mudguard", (xa, 14, -6), (xb, 14.75, 6), gp)
        m.box("mudguard", (xa, 11, -7), (xb, 14.75, -6), gp)
        m.box("mudguard", (xa, 11, 6), (xb, 14.75, 7), gp)
        xa, xb = sorted((7 * s, 9.5 * s))
        m.box("axle", (xa, 5.5, -1), (xb, 7, 1), iron)
    m.box("axle", (-7, 5.5, -1), (7, 7, 1), iron)

    # tail lamps on the tailgate
    for s in (1, -1):
        xa, xb = sorted((8 * s, 10 * s))
        m.box("tail_lamp", (xa, 11, HZ + 0.75), (xb, 12.5, HZ + 1.25), Paint("signal_red", 1, noise=0, light=False, glow=False,
                                                                           decal=lambda c: ("signal_red", 1.6 if c.face == "south" else 0.6)))

    m.group("seat")

    # the seat box: red steel, the driver's cushion and a back rail; a tool box under its front
    def box_px(c):
        x, y, z = c.p
        if c.face == "north" and 11 < y < 14 and abs(x) < 6:
            if abs(x) < 1 and math.floor(y) == 13:
                return ("steel", 3.6)                             # the toolbox latch
            return ("iron", 2.0 if math.floor(y) != 11 else 1.4)
        return _paint_metal(RED, 2.0)(c)
    m.box("seat_box", (-HX, Y1, -HZ), (HX, 15, -HZ + 5), Paint(RED, 2, noise=0, rim=0.6, decal=box_px))

    def cushion(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 0.5:
                return ("leather", 0.6)                           # the seam between the two seats
            return ("leather", 2.0 + (0.6 if math.floor(z) % 2 == 0 and abs(abs(x) - 5) < 3 else 0))
        return ("leather", 1.4)
    m.box("cushion", (-HX + 0.5, 15, -HZ + 0.5), (HX - 0.5, 16.5, -HZ + 4.5), Paint("leather", 2, noise=0, rim=0.5, decal=cushion))
    tube = Paint("steel", 3, noise=0, rim=0.3, decal=lambda c: ("steel", 3.6 if c.face == "up" else 2.4))
    for s in (1, -1):
        xa, xb = sorted(((HX - 1) * s, HX * s))
        m.box("rail_post", (xa, 15, -HZ + 5), (xb, 21, -HZ + 6), tube)
    m.box("rail", (-HX, 21, -HZ + 5), (HX, 22, -HZ + 6), tube)

    m.group("drawbar")
    # the A-frame drawbar: two bars from the bed's front corners to the hitch eye
    T = -30                                                        # the eye (tongue 1.875 blocks ahead of the axle)
    for s in (1, -1):
        x0 = 6 * s
        m.box("drawbar", (min(x0, x0 + 1 * s), 6.5, T + 3), (max(x0, x0 + 1 * s), 8, -HZ), iron)
        xa, xb = sorted((1.25 * s, 6 * s))
        m.box("drawbar", (xa, 6.5, T + 3), (xb, 8, T + 4), iron)
    m.box("eye", (-1.25, 6.5, T), (1.25, 8, T + 3), Paint("steel", 3, noise=0, decal=lambda c: ("iron", 0.6) if c.face == "up" and abs(c.p[0]) < 0.6 and abs(c.p[2] - (T + 1)) < 0.6 else ("steel", 3)))
    m.display = _held_display(m)
    return m


# ================================================================================================
# 트랙터 (farm tractor) and its wagons
# ================================================================================================

ORANGE = "fox"
TRACTOR_AX = 14         # the rear axle lies this far behind the tractor's centre (drawn px)


def tractor():
    """The farm tractor, facing north: a long orange hood with louvred side panels, a dark grille in a cream frame
    with two headlamps, front weights, the front axle (wheels separate: they steer); behind it the dash, a tilted
    steering wheel, the seat between two big fenders (tail lamps on their backs, a fender seat on the left for a
    passenger), a roll bar with a sun canopy, an exhaust stack with a rain cap, the rear drawbar (the hitch). The big
    rear wheels are separate models (they roll)."""
    m = BModel("tractor", shift=(8, 8, 8), scale=2.0)
    A = TRACTOR_AX

    def z(v):
        return v + A

    orange = Paint(ORANGE, 2, noise=0, rim=0.6, decal=_paint_metal(ORANGE, 2.2))
    dark = Paint("iron", 2, noise=0, rim=0.5, decal=_paint_metal("iron", 2.4, 0.8))

    m.group("chassis")

    def block(c):
        # the cast engine block: ribs, the head's joint, an oil filter (a pale canister) and the dipstick on its
        # sides, an oil sump bolted under it, grime and an oil stain low down
        x, y, zz = c.p
        if c.face in ("east", "west"):
            fy = math.floor(y)
            r = math.hypot(zz - z(-22), y - 11.5)
            if r < 2.1:
                return ("blade", 3.2 if r < 1.2 and y > 11.5 else 2.2) if r < 1.6 else ("iron", 1.0)
            if fy == 15:
                return ("iron", 3.2)                              # the head's joint, lit
            if fy == 8:
                return ("iron", 3.0) if math.floor(zz) % 3 == 0 else ("iron", 1.4)   # the sump's bolted flange
            if c.face == "west" and math.floor(zz) == math.floor(z(-15)) and 10 <= y < 15:
                return ("fox", 3.0 if y > 14 else 2.0)            # the dipstick's handle and tube
            if math.floor(zz) % 4 == 0 and 9 < y < 15:
                return ("iron", 1.6)                              # ribs cast in the block
            if y < 10 and h3(math.floor(zz), fy, 2, 65) < 0.3:
                return ("iron", 1.4)                              # oily grime
        return _paint_metal("iron", 2.4, 0.8)(c)
    m.box("engine_block", (-4.5, 7, z(-34)), (4.5, 17, z(-6)), Paint("iron", 2, noise=0, rim=0.5, decal=block))
    m.box("transmission", (-5, 6, z(-6)), (5, 16, z(7)), dark)
    m.box("front_axle", (-8, 5, z(-31)), (8, 7.5, z(-28)), dark)
    m.box("front_axle_pivot", (-2, 7, z(-31)), (2, 8, z(-28)), dark)

    # front weights: a stack of slabs on a bracket ahead of the grille
    def weights(c):
        x, y, zz = c.p
        if c.face in ("north", "east", "west") and math.floor(x + 6) % 3 == 0:
            return ("iron", 0.8)
        return ("iron", 2.6 if c.face == "up" else 1.8)
    m.box("weights", (-6, 7, z(-41)), (6, 13, z(-37.5)), Paint("iron", 2, noise=0, rim=0.6, decal=weights))
    m.box("weight_bracket", (-3, 8, z(-37.5)), (3, 10, z(-35)), dark)

    m.group("hood")

    def hood(c):
        x, y, zz = c.p
        if c.face in ("east", "west"):
            # louvred side panels, a dark seam where the hood lifts
            if 17 < y < 21.5 and z(-30) < zz < z(-14) and math.floor(zz) % 2 == 0:
                return ("iron", 1.4)
            if math.floor(y) == 22:
                return (ORANGE, 1.4)
            if 17 < y < 21.5 and z(-30) < zz < z(-14):
                return (ORANGE, 2.0)
            if math.floor(y) == 16:
                return ("cream", 2.4 if zz < z(-12) else 1.8)   # a cream stripe along the hood's foot
            if math.floor(y) == 17 and zz < z(-30):
                return ("iron", 1.2)                          # ... and a dark pinstripe over it at the front
        if c.face == "up":
            if abs(x) < 0.5:
                return (ORANGE, 1.6)                              # the hood's centre seam
            if 2 <= x < 4:
                return (ORANGE, 3.0)                              # a reflection band
            return (ORANGE, 2.5)
        return _paint_metal(ORANGE, 2.2)(c)
    hp = Paint(ORANGE, 2, noise=0, rim=0.6, decal=hood)
    m.box("hood", (-6, 16, z(-36)), (6, 23, z(-8)), hp)
    m.box("hood_top", (-5, 23, z(-35)), (5, 24, z(-8)), hp)

    def grille(c):
        x, y, zz = c.p
        if c.face != "north":
            return ("cream", 2.0)
        ax = abs(x)
        if 3.5 <= ax < 5.5 and 18 <= y < 20:
            # headlamps in the grille's top corners
            return ("lamp", 4.0) if (math.floor(ax) == 4 and math.floor(y) == 19) else ("lamp", 3.0)
        if ax > 5 or y > 22 or y < 9.5:
            return ("cream", 2.4)                                 # the frame
        if math.floor(y) % 2 == 0:
            return ("iron", 0.6)                                  # horizontal bars, dark between
        return ("steel", 2.2)
    m.box("grille", (-6, 9, z(-37)), (6, 23.5, z(-36)), Paint("cream", 2, noise=0, rim=0.4, decal=grille))
    m.box("radiator_cap", (-1, 24, z(-31)), (1, 25, z(-29)), Paint("steel", 4, noise=0))

    # the exhaust stack on the right: a muffler then a pipe with a rain cap
    m.box("muffler", (4.5, 23, z(-14)), (7.5, 30, z(-11)), Paint("soot", 2, noise=0, rim=0.5, decal=lambda c: ("soot", 1.6 if math.floor(c.p[1]) % 3 == 0 else 2.4)))
    m.box("stack", (5.25, 30, z(-13.25)), (6.75, 38, z(-11.75)), Paint("iron", 2, noise=0, decal=lambda c: ("soot", 0.8 if c.p[1] > 37 else 2.2)))
    m.box("rain_cap", (5, 38, z(-13.5)), (7, 38.5, z(-11.5)), Paint("steel", 3, noise=0, decal=lambda c: ("steel", 3.6 if c.face == "up" else 2.4)))

    m.group("cab")
    # the dash and the steering column, a steering wheel tilted towards the driver
    def dash(c):
        x, y, zz = c.p
        if c.face == "south" and 20 < y < 23 and abs(x) < 3.5:
            if math.floor(x) in (-3, 1) and math.floor(y) == 21:
                return ("glow_amber", 2.0)                        # two gauges
            return ("iron", 0.8)
        return _paint_metal("iron", 2.2, 0.8)(c)
    m.box("dash", (-5, 16, z(-8)), (5, 24, z(-5)), Paint("iron", 2, noise=0, rim=0.5, decal=dash))
    m.box("column", (-0.75, 24, z(-6.5)), (0.75, 29, z(-5)), Paint("iron", 2, noise=0))

    def wheel_px(c):
        x, y, zz = c.p
        if c.face in ("up", "down"):
            r = math.hypot(x, zz - z(-4.5))
            if r < 1:
                return ("iron", 2.4)
            if r < 2.4 and abs(x) > 0.5:
                return ("raw", CLEAR) if c.face else None
            return ("rubber", 2.6 if c.face == "up" else 1.4)
        return ("rubber", 1.4)
    m.box("steering_wheel", (-3.5, 29, z(-8)), (3.5, 29.5, z(-1)), Paint("rubber", 2, noise=0, rim=0.2, decal=lambda c: ("rubber", 2.6 if c.face == "up" else 1.2)),
          rotation=("x", 22.5, (0, 29.25, z(-4.5))))

    # the platform between the fenders
    m.box("platform", (-8.5, 13, z(-8)), (8.5, 14, z(6)), Paint("iron", 2, noise=0, decal=lambda c: ("iron", 2.6 if (math.floor(c.p[0]) + math.floor(c.p[2])) % 3 == 0 and c.face == "up" else 1.8)))

    # the fenders: over the big rear wheels, a flat top, curved ends (stepped), tail lamps on their backs
    def fender(c):
        # dried mud flung up by the tyre along the fenders' lower ends and their inner faces, a lit crest on top
        x, y, zz = c.p
        if c.face == "up" and y > 23.9:
            return (ORANGE, 2.9 if abs(abs(x) - 12) < 3 else 2.5)
        if c.face != "up" and y < 22 and h3(math.floor(x * 2), math.floor(y * 2), math.floor(zz * 2), 66) < 0.35 - 0.1 * (y - 19):
            return ("soil", 1.6 + h3(math.floor(x), math.floor(y), math.floor(zz), 67))
        return _paint_metal(ORANGE, 2.2)(c)
    fp = Paint(ORANGE, 2, noise=0, rim=0.6, decal=fender)
    for s in (1, -1):
        xa, xb = sorted((8.5 * s, 15.5 * s))
        m.box("fender", (xa, 23, z(-10)), (xb, 24, z(8)), fp)
        m.box("fender", (xa, 19, z(-12)), (xb, 23, z(-10)), fp)
        m.box("fender", (xa, 19, z(8)), (xb, 23, z(10)), fp)
        m.box("fender_inner", (min(8.5 * s, 9 * s), 13, z(-10)), (max(8.5 * s, 9 * s), 23, z(8)), dark)
        xa2, xb2 = sorted((12 * s, 14.5 * s))
        m.box("tail_lamp", (xa2, 20, z(10)), (xb2, 22, z(10.5)), Paint("signal_red", 1, noise=0, decal=lambda c: ("signal_red", 1.6 if c.face == "south" else 0.6)))

    # the driver's seat: a sprung pan seat with a backrest, the fender seat on the left
    def seat(c):
        x, y, zz = c.p
        if c.face == "up" and abs(x) < 0.5:
            return ("rubber", 0.8)
        return ("rubber", 2.6 if c.face == "up" else 1.6)
    seat_p = Paint("rubber", 2, noise=0, rim=0.5, decal=seat)
    m.box("seat_post", (-1, 14, z(-1)), (1, 17, z(1)), dark)
    m.box("seat", (-4, 17, z(-3)), (4, 19, z(3)), seat_p)
    m.box("seat_back", (-4, 19, z(3)), (4, 25, z(4.5)), seat_p)
    m.box("fender_seat", (-14.5, 24, z(-6)), (-9.5, 25, z(2)), seat_p)

    m.group("roll bar")
    tube = Paint("iron", 2, noise=0, rim=0.4, decal=lambda c: ("iron", 3.0 if c.face == "up" else 2.2))
    for s in (1, -1):
        xa, xb = sorted((8.5 * s, 10 * s))
        m.box("rops", (xa, 24, z(6)), (xb, 45, z(7.5)), tube)
    m.box("rops_top", (-10, 45, z(6)), (10, 46.5, z(7.5)), tube)
    for s in (1, -1):
        xa, xb = sorted((9 * s, 9.75 * s))
        m.box("canopy_stay", (xa, 45.5, z(-10)), (xb, 46.5, z(6)), tube)

    def canopy(c):
        # the sun canopy's canvas: a sewn hem round its edge, three panels with their seams, sagging a little
        # between the stays (lit at the front, its middle a step darker), sun-faded patches, a few dark spots
        x, y, zz = c.p
        fx, fz = math.floor(x + 12), math.floor(zz - z(-12))
        if c.face == "up":
            if fx in (0, 23) or fz in (0, 20):
                return ("cream", 1.8)                             # the hem
            if fx in (8, 15):
                return ("cream", 1.6)                             # the seams between panels
            sag = -0.6 if 3 <= fz <= 17 and 2 <= fx % 8 <= 5 else 0
            fade = 0.4 if vnoise(x, 0, zz, 4, 63) > 0.7 else 0
            spot = -0.8 if h3(fx, 1, fz, 64) < 0.025 else 0
            return ("cream", 2.8 + sag + fade + spot + (0.3 if fz < 4 else 0))
        if c.face == "down":
            return ("cream", 1.2 if 2 <= fx <= 21 else 1.6)       # the canvas' shaded underside, its hem
        return ("iron", 2.6 if y > 47 else 1.8)                   # the canopy's steel frame round its edge
    m.box("canopy", (-12, 46.5, z(-12)), (12, 47.5, z(9)), Paint("cream", 2, noise=0, rim=0.5, decal=canopy))

    m.group("hitch")
    m.box("drawbar", (-1.5, 7, z(7)), (1.5, 9, z(22)), dark)
    m.box("hitch_pin", (-0.5, 6.5, z(19.5)), (0.5, 10.5, z(20.5)), Paint("steel", 4, noise=0))
    for s in (1, -1):
        xa, xb = sorted((3 * s, 4 * s))
        m.box("lift_arm", (xa, 9, z(7)), (xb, 10.5, z(16)), dark)
    m.display = _held_display(m)
    return m


def tractor_wagon():
    """A farm wagon, axle at (0, 0, 0): wooden side boards on steel stakes over a steel frame, a high headboard,
    a drop tailgate with chains, a plank floor, its two wheels under the bed (separate), the drawbar forward to the
    hitch and a hitch hook behind for the next wagon, reflectors, a plate."""
    m = BModel("tractor_wagon", shift=(8, 8, 8), scale=2.0)
    HX, HZ, Y0, Y1 = 12, 20, 13.5, 15

    m.group("bed")

    def floor(c):
        x, y, z = c.p
        if c.face == "up":
            board = math.floor((x + HX) / 4)
            if ((x + HX) % 4) < 0.5:
                return ("wood_dark", 0.8)
            return ("wood", 1.8 + (0.4 if board % 2 else 0) + _grain(c, "z", board))
        return ("iron", 2.0)
    m.box("floor", (-HX, Y0, -HZ), (HX, Y1, HZ), Paint("wood", 2, noise=0, rim=0.4, decal=floor))
    dark = Paint("iron", 2, noise=0, rim=0.5, decal=_paint_metal("iron", 2.4, 0.8))
    for s in (1, -1):
        xa, xb = sorted((6 * s, 8 * s))
        m.box("frame", (xa, 11.5, -HZ), (xb, Y0, HZ), dark)
    for zc in (-15, 15):
        m.box("cross", (-HX, 12, zc - 1), (HX, Y0, zc + 1), dark)

    def boards(c):
        x, y, z = c.p
        along = z if c.face in ("east", "west") else x
        if c.face in ("east", "west", "north", "south"):
            if abs(abs(along) - 10) < 1 or abs(along) < 1 and c.face in ("east", "west"):
                return ("iron", 2.4 if c.face != "up" else 3.0)   # the steel stakes
            k = math.floor((y - Y1) / 2.5)
            if (y - Y1) % 2.5 < 0.4:
                return ("wood_dark", 0.8)                         # the gap between boards
            return ("wood_dark", 2.0 + (0.4 if k % 2 else 0) + _grain(c, "z" if c.face in ("east", "west") else "x", k))
        if c.face == "up":
            return ("wood_dark", 2.8)
        return ("wood_dark", 1.4)
    bp = Paint("wood_dark", 2, noise=0, rim=0.6, decal=boards)
    for s in (1, -1):
        xa, xb = sorted((HX * s, (HX + 1) * s))
        m.box("side", (xa, Y0, -HZ), (xb, 22.5, HZ), bp)
    m.box("headboard", (-HX, Y0, -HZ - 1), (HX, 25, -HZ), bp)
    m.box("tailgate", (-HX, Y0, HZ), (HX, 22.5, HZ + 1), bp)
    # chains holding the tailgate, reflectors and a plate on it
    for s in (1, -1):
        xa, xb = sorted(((HX + 1) * s, (HX + 1.5) * s))
        m.box("chain", (xa, 18, HZ - 3), (xb, 18.5, HZ + 1), Paint("steel", 3, noise=0, decal=lambda c: ("steel", 3.4 if math.floor(c.p[2]) % 2 else 2.0)))
        xa, xb = sorted((8 * s, 10.5 * s))
        m.box("reflector", (xa, 16, HZ + 1), (xb, 17.5, HZ + 1.5), Paint("signal_red", 1, noise=0, decal=lambda c: ("signal_red", 1.6 if c.face == "south" else 0.6)))

    def plate(c):
        if c.face != "south":
            return ("car_white", 1)
        col, row = math.floor(c.p[0] + 3), math.floor(19.99 - c.p[1])
        if row in (0, 3) or col in (0, 5):
            return ("car_white", 1.2)
        return ("iron", 1.0) if col % 2 == 0 else ("car_white", 3)
    m.box("plate", (-3, 16, HZ + 1), (3, 20, HZ + 1.5), Paint("car_white", 2, noise=0, decal=plate))

    m.group("running gear")
    for s in (1, -1):
        xa, xb = sorted((6 * s, 8 * s))
        m.box("axle_hanger", (xa, 7, -1.5), (xb, 11.5, 1.5), dark)
    m.box("axle", (-11, 5.75, -0.75), (11, 7.25, 0.75), dark)    # thinner than the hubs it runs into (no flicker)

    m.group("drawbar")
    T = -35                                                        # the eye: tongue 2.2 blocks ahead of the axle
    m.box("drawbar", (-1.25, 7.5, T + 2.5), (1.25, 9.5, -HZ - 1), dark)
    for s in (1, -1):
        xa, xb = sorted((1.25 * s, 6 * s))
        m.box("drawbar_brace", (xa, 9, -HZ - 4), (xb, 11.5, -HZ - 1), dark)
    m.box("eye", (-1, 7.5, T), (1, 9.5, T + 2.5), Paint("steel", 3, noise=0, decal=lambda c: ("iron", 0.6) if c.face == "up" and abs(c.p[0]) < 0.6 and abs(c.p[2] - (T + 1.2)) < 0.6 else ("steel", 3)))
    # the hitch behind (the next wagon hangs here)
    m.box("rear_hitch", (-1.5, 7, HZ + 1), (1.5, 9, HZ + 4), dark)
    m.box("hitch_bracket", (-1.25, 7.5, HZ - 3), (1.25, Y0, HZ + 1.5), dark)    # up to the floor
    m.box("rear_pin", (-0.5, 6.5, HZ + 2.5), (0.5, 10, HZ + 3.5), Paint("steel", 4, noise=0))
    m.display = _held_display(m)
    return m


# ------------------------------------------------------------------------------------------------
# Loads of the tiller's trailer and the wagons
# ------------------------------------------------------------------------------------------------

def _fert_bag(m, x, y, z, w=8, d=6, h=3.5, band="leaf"):
    """A woven white fertilizer sack lying flat (no brand): a printed band and a few marks, sewn ends."""
    def decal(c):
        px, py, pz = c.p
        if c.face == "up" and abs(px - (x + w / 2)) < 1.5:
            return (band, 2.0 if math.floor(pz) % 3 else 1.4)
        if c.face in ("north", "south") and abs(px - (x + w / 2)) < 1.5:
            return (band, 1.6)
        if c.face in ("east", "west") and math.floor(py) == math.floor(y + h / 2):
            return ("rice", 0.6)                                  # the sewn end
        return ("rice", 1.8 + (0.4 if (math.floor(px) + math.floor(pz)) % 2 else 0))
    p = Paint("rice", 2, noise=0, rim=0.5, decal=decal)
    m.box("bag", (x, y, z), (x + w, y + h - 0.5, z + d), p)
    m.box("bag", (x + 0.5, y + h - 0.5, z + 0.5), (x + w - 0.5, y + h, z + d - 0.5), p)


def _cabbage(m, x, y, z, s=4):
    """A napa cabbage (배추) lying down: pale ribbed heart, dark green leaf tips at one end."""
    def decal(c):
        px, py, pz = c.p
        u = pz - z
        if u < s * 0.45:
            return ("leaf", 2.6 + (0.6 if math.floor(px + py) % 2 else 0))
        if c.face in ("north", "south"):
            return ("cabbage", 2.0)
        return ("cabbage", 2.2 + (0.6 if math.floor(px * 2) % 2 else -0.2))
    m.box("cabbage", (x, y, z), (x + s * 0.75, y + s * 0.7, z + s * 1.8), Paint("cabbage", 2, noise=0, rim=0.4, decal=decal))


def _tub(m, x, y, z, d=8, h=6, ramp="truck_blue"):
    """A big plastic tub (고무통) with a rolled rim."""
    def decal(c):
        if c.face == "up":
            return ("soil", 1.6)                                  # earth (or feed) in it
        u = c.p[1] - y
        return (ramp, 1.8 + lerp(u, 0, h, -0.4, 0.6) + (0.5 if c.face in ("north", "west") else 0))
    p = Paint(ramp, 2, noise=0, rim=0.4, decal=decal)
    cyl(m, "tub", (x, z), d - 1, y, y + h - 1, p)
    cyl(m, "tub_rim", (x, z), d, y + h - 1, y + h, Paint(ramp, 2, noise=0, decal=lambda c: (ramp, 2.8 if c.face == "up" else 2.2)))


def _bale(m, x, y, z, w=6, h=5, d=10):
    """A small square hay bale, its stalks along it, two twine bands round it."""
    def decal(c):
        px, py, pz = c.p
        u = pz - z
        if abs(u - d * 0.3) < 0.5 or abs(u - d * 0.7) < 0.5:
            return ("leaf_dark", 2.6) if c.face != "down" else ("straw", 1)
        if c.face in ("north", "south"):
            return ("straw", 1.4 + (0.9 if (math.floor(px) * 3 + math.floor(py)) % 4 == 0 else 0))
        k = h3(math.floor(px * 2), math.floor(py * 2), math.floor(pz / 2), 71)
        return ("straw", 2.6 + (0.8 if k > 0.8 else -0.7 if k < 0.2 else 0))
    m.box("bale", (x, y, z), (x + w, y + h, z + d), Paint("straw", 3, noise=0, rim=0.5, decal=decal))


def power_tiller_cargo(stage):
    """The tiller trailer's bed behind the seat box (22 x 26 px, floor middle at the model's (8, 8, 8)): fertilizer
    sacks, cabbages, a tub, a crate of peppers; full: piled up under a folded tarp and a rope net."""
    m = BModel(f"power_tiller_cargo_{stage}", shift=(8, 8, 8))
    if stage >= 1:
        _fert_bag(m, -10.5, 0, 6, 9, 6.5)
        _fert_bag(m, -10.5, 0, -0.5, 9, 6.5, band="denim")
        _tub(m, 6, 0, 8.5)
    if stage >= 2:
        _fert_bag(m, -10.5, 3.5, 6, 9, 6.5, band="denim")
        for i in range(3):
            _cabbage(m, 0.5 + i * 3.2, 0, -12)
        _cart_crate(m, 0.5, -4.5, 10, 8, 6, fill="pepper", heap=stage < 4)
    if stage >= 3:
        _fert_bag(m, -10.5, 3.5, -0.5, 9, 6.5)
        for i in range(3):
            _cabbage(m, -10.5 + i * 3.2, 0, -12)
        for i in range(2):
            _cabbage(m, 2 + i * 3.2, 2.8, -12)
        _tub(m, 6, 6, 8.5, 7, 4, ramp="fabric_red")
    if stage >= 4:
        _fert_bag(m, -10.5, 7, 6, 9, 6.5)
        _fert_bag(m, -10.5, 7, -0.5, 9, 6.5, band="denim")
        for i in range(3):
            _cabbage(m, -10.5 + i * 3.2, 2.8, -12)
        _folded_tarp(m, 0.5, 10.5, -4.5, 3.5, 6, 8)
        _rope_net(m, -11, 11, -12.5, 13, 0, 10.5, rope=("leather", 1.8))
    m.display = presets.furniture(gui_scale=0.4)
    return m


def tractor_wagon_cargo(stage):
    """A wagon's bed (24 x 40 px, floor middle at the model's (8, 8, 8)): hay bales, crates of apples, sacks; full:
    stacked high, the top tier under a tarp."""
    m = BModel(f"tractor_wagon_cargo_{stage}", shift=(8, 8, 8))
    if stage >= 1:
        _bale(m, -11.5, 0, -19.5, 7, 5, 11)
        _bale(m, -4.5, 0, -19.5, 7, 5, 11)
        _cart_crate(m, 3, -19.5, 8.5, 8, 6)
    if stage >= 2:
        _bale(m, -11.5, 0, -8.5, 7, 5, 11)
        _bale(m, -4.5, 0, -8.5, 7, 5, 11)
        _cart_crate(m, 3, -11, 8.5, 8, 6, fill="beans", heap=stage < 4)
        _sack(m, 2.5, 0, 0, 8, 7, 5, band="fabric_red")
    if stage >= 3:
        _bale(m, -11.5, 5, -19.5, 7, 5, 11)
        _bale(m, -4.5, 5, -19.5, 7, 5, 11)
        _bale(m, -11.5, 0, 2.5, 7, 5, 11)
        _cart_crate(m, 3, 7.5, 8.5, 8, 6, fill="pepper")
        _sack(m, 2.5, 5, 0.5, 8, 7, 5, band="denim")
    if stage >= 4:
        _bale(m, -11.5, 5, -8.5, 7, 5, 11)
        _bale(m, -4.5, 5, -8.5, 7, 5, 11)
        _bale(m, -4.5, 0, 2.5, 7, 5, 11)
        _bale(m, -11.5, 5, 2.5, 7, 5, 11)
        _cart_crate(m, 3, -19.5, 8.5, 8, 6, y=6)
        _sack(m, 3, 6, -11, 8, 7, 5, band="leaf_dark")
        _tarp(m, -11.5, 2.5, 10, -19.5, 13.5, 4)
    m.display = presets.furniture(gui_scale=0.4)
    return m


# ================================================================================================

ALL = {
    "ox_cart": ox_body,
    "ox_leg_fore": ox_leg_fore,
    "ox_leg_hind": ox_leg_hind,
    "ox_head": ox_head,
    "ox_cart_wagon": ox_cart_wagon,
    "wheel_oxcart": wheel_oxcart,
    **{f"ox_cart_cargo_{k}": (lambda k=k: ox_cart_cargo(k)) for k in range(1, 5)},
    "power_tiller": power_tiller,
    "power_tiller_flywheel": power_tiller_flywheel,
    "power_tiller_trailer": power_tiller_trailer,
    "wheel_tiller": wheel_tiller,
    **{f"power_tiller_cargo_{k}": (lambda k=k: power_tiller_cargo(k)) for k in range(1, 5)},
    "tractor": tractor,
    "wheel_tractor_front": wheel_tractor_front,
    "wheel_tractor_rear": wheel_tractor_rear,
    "tractor_wagon": tractor_wagon,
    **{f"tractor_wagon_cargo_{k}": (lambda k=k: tractor_wagon_cargo(k)) for k in range(1, 5)},
}
