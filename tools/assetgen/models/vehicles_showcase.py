"""
The showcase car (content/stsv/items/vehicles.yml `phantom_gt`, 팬텀 GT): one car drawn with everything the pipeline
can give, no budget held back. An original design (no real make, no badge): a long-nosed, low front-engined GT coupe.

What it does differently from the other cars (models/vehicles.py, vehicles_premium.py):
  * twice the detail: drawn at 32 px a block (the others 16). The body is drawn at twice its real size and shrunk by
    4 (BModel scale 4), shown at body-scale 2; the wheel drawn at twice its size and shrunk by 2, shown at 1. So a
    texel is 1/32 block everywhere on it.
  * sculpted, not boxed: the body is a field of 1 px cells (bake.voxels) from smooth profiles: a plan that swells over
    each wheel, a hood that rises to the screen, fenders standing above the hood, a shoulder rounded over, a fastback
    falling to a ducktail, the sides tucked in at the sill. Cells of one material merge into few boxes.
  * its own colours: candy red, gloss black, carbon, a cool LED white, a hot tail red (ramps registered here, still
    made by the palette's formula, with an accent's chroma).
  * no face budget (RenderBudget.EXCEPTIONS lists it): a showcase, one of a kind on the server.

Drawn coordinates (like every vehicle, at twice the scale): 32 px a block, the car's ground centre at (0, 0, 0), front
= north (-z), +x = the car's RIGHT side; the driver sits on the left (-x).

Its parts: door_l, door_r (the side between the two door seams with its window: scissor doors, swinging up about their
front top corner, vehicles.yml movers).
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, voxels
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp
from stsv_assets import presets


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("gt_red", 24, 0.215, 0.50, 0.52, 8, True)        # candy red: deep in the shade, hot pink-red on the light
_ramp("gt_black", 270, 0.014, 0.30, 0.30, 6, False)    # gloss black: blue-grey reflections
_ramp("gt_carbon", 250, 0.010, 0.27, 0.16, 4, False)
_ramp("gt_steel", 240, 0.012, 0.66, 0.46, 7, False)    # machined wheels, chrome tips
_ramp("gt_led", 215, 0.040, 0.92, 0.10, 3, True)       # cool white LEDs
_ramp("gt_tail", 22, 0.240, 0.56, 0.30, 4, True)       # the tail light bar
_ramp("gt_leather", 30, 0.030, 0.30, 0.18, 4, False)   # dark cabin leather
_ramp("gt_stitch", 24, 0.200, 0.55, 0.10, 2, True)     # red stitching, red callipers
_ramp("gt_screen", 205, 0.110, 0.70, 0.30, 3, True)    # the dash screens

# ================================================================================================
# Shape (drawn px, 32 a block)
# ================================================================================================

NOSE, TAIL = -78, 78           # z of the nose and the tail
WF, WR = -48, 47               # front / rear axle (z)
WY, WRAD = 12.5, 12.5          # wheel centre height, wheel radius (a 0.78 block wheel)
ARCH = WRAD + 1.4              # the arch's radius round the wheel
TRACK = 26                     # wheel's inner face (x): the wheel stands from TRACK out to ~34
GROUND = 3                     # the body's lowest cells
D0, D1 = -27, 9                # the door, between its two seams (z)
CAB0, CAB1 = -24, 58           # the glasshouse: from the screen's foot to the rear glass's foot


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def bump(z, c, w):
    """1 at c, falling smoothly to 0 at c +- w."""
    return smooth(1 - abs(z - c) / w)


def plan(z):
    """The body's half width at its widest (the shoulder) at z."""
    w = 30.5
    w += 2.4 * bump(z, WF, 24)                 # the front fender swelling over the wheel
    w += 3.4 * bump(z, WR + 2, 28)             # the rear haunch, broader
    if z < -60:                                # the nose rounds in
        t = (z - NOSE) / 18
        w = 22 + (w - 22) * smooth(t) ** 0.6
    if z > 68:                                 # the tail rounds in
        t = (TAIL - z) / 10
        w = 28 + (w - 28) * smooth(t) ** 0.5
    return w


def deck(z):
    """The height of the hood / rear deck along the middle (the glasshouse stands on it between CAB0 and CAB1)."""
    if z < -30:
        t = (z - NOSE) / 48
        return 13 + 11.5 * smooth(t) ** 0.8               # the hood rises from the thin nose to the screen
    if z < 56:
        return 24.5 + 2.5 * (z + 30) / 86                  # the belt rises a little to the rear
    if z < 72:
        return 27.2
    return 27.2 + 1.6 * smooth((z - 72) / 4)               # the ducktail lip


def fender(z):
    """How far the fenders stand above the deck, at the outer edge."""
    return 6.5 * bump(z, WF, 22) + 3.6 * bump(z, WR, 24)


def top(z, ax):
    """The body's top at z, |x| = ax: the deck, the fenders rising towards the outside, the shoulder rounded over."""
    w = plan(z)
    h = deck(z) + fender(z) * smooth((ax - 12) / 14)
    h += 0.8 * (1 - (ax / w) ** 2)                         # a gentle crown across the hood
    edge = w - ax                                          # the shoulder rolls over in its last 4 px
    if edge < 4:
        h -= (4 - edge) ** 1.6 * 0.55
    return h


def half_width(z, y):
    """The body's half width at height y: tucked in at the sill."""
    w = plan(z)
    if y < 9:
        w -= (9 - y) * 0.55
    return w


ROOF = 42.5


T = math.tan(math.radians(22.5))   # the slopes the tilted panes follow: a model element turns only in 22.5° steps
FB0 = 16                           # where the fastback starts falling from the roof
TILT_X, TILT_Z = -1, 1             # which way a positive turn goes about x and about z (checked on the preview)


def roof(z):
    """The glasshouse's top: the raked screen, a short roof, then a fastback falling at exactly 22.5° to the deck (a
    tilted pane lies on it). High enough for a sitting player (head 1.275 block over the seat)."""
    if z < -4:
        return max(ROOF - (-4 - z), deck(z) + 0.3)      # the screen: straight at 45° (one tilted pane)
    if z < FB0:
        return ROOF + 0.4 * bump(z, 5, 10)
    return max(ROOF - (z - FB0) * T, deck(z) + 0.3)


def fastback_end():
    z = FB0
    while ROOF - (z - FB0) * T > deck(z) + 0.3:
        z += 0.25
    return z


def glass_w(z, y):
    """The glasshouse's half width at height y: its sides lean in at exactly 22.5° (a tilted pane lies on each),
    its top rounded over."""
    base = plan(z) - 5.5
    r = roof(z)
    d = deck(z)
    if r <= d:
        return -1
    w = base - (y - d) * T
    if r - y < 2.5:
        w -= (2.5 - (r - y)) * 2.2
    return w


def in_arch(z, y, ax):
    """Inside the cut round a wheel (only the outer part of the body is cut: the wheel stands there)."""
    if ax < TRACK - 1.5:
        return False
    for wz in (WF, WR):
        if math.hypot(z - wz, y - WY) < ARCH:
            return True
    return False


# ================================================================================================
# The cells
# ================================================================================================

def _cells():
    """(x, y, z) -> label of every body cell (outer skin labels carry the trim), plus the door cells apart."""
    body, doors = {}, {-1: {}, 1: {}}
    for z in range(NOSE, TAIL):
        zc = z + 0.5
        wmax = plan(zc)
        for x in range(-int(wmax) - 1, int(wmax) + 1):
            xc = x + 0.5
            ax = abs(xc)
            t = top(zc, ax)
            for y in range(GROUND, int(math.ceil(max(t, roof(zc) if CAB0 <= zc <= CAB1 else 0))) + 1):
                yc = y + 0.5
                lower = yc <= t and ax <= half_width(zc, yc)
                upper = CAB0 <= zc <= CAB1 and yc > deck(zc) - 0.5 and ax <= glass_w(zc, yc) and yc <= roof(zc)
                if not (lower or upper):
                    continue
                if lower and in_arch(zc, yc, ax):
                    continue
                label = _label(xc, yc, zc, ax, lower)
                if label is None:
                    continue
                side = -1 if xc < 0 else 1
                if _is_door(xc, yc, zc, ax, lower):
                    doors[side][(x, y, z)] = label
                else:
                    body[(x, y, z)] = label
    return body, doors


def _is_door(x, y, z, ax, lower):
    """The door: the outer 3 px of the side between its seams, from the sill to the roof rail."""
    if not (D0 <= z < D1):
        return False
    if lower:
        return y >= 6 and ax > half_width(z, y) - 3
    return ax > glass_w(z, y) - 2.5 and y < roof(z) - 1.5


def _label(x, y, z, ax, lower):
    """What a cell is made of. Cells deep inside get 'core' (never seen: merged into a few big boxes)."""
    if lower:
        w = half_width(z, y)
        t = top(z, ax)
        skin = (w - ax) < 2.5 or (t - y) < 2.5 or y < GROUND + 2 or z < NOSE + 3 or z > TAIL - 4
        # the cabin: a tub hollowed out under the glasshouse (the floor at y 7, sills either side)
        if CAB0 + 8 < z < D1 + 14 and ax < w - 3.5 and y > 4.5:
            return None
        # a wheel well: the cells round the arch cut, dark
        for wz in (WF, WR):
            if TRACK - 4 < ax < TRACK - 1.5 and math.hypot(z - wz, y - WY) < ARCH + 1 and y < t - 2:
                return "well"
        if not skin:
            return "core"
        # the front: a black intake across the lower nose, the splitter under it
        if z < NOSE + 6 and y < 9.5:
            return "carbon" if y < 4.5 else "mesh"
        # the headlights: a thin LED blade on each front corner, sweeping up the fender
        # the headlights: on each front corner a black housing swept back up the fender, an LED blade along its top
        # and a projector dot in it
        if z < -56 and 14 < ax < w - 1.2 and (t - y) < 3:
            reach = NOSE + 17 - (ax - 14) * 0.35            # the housing sweeps back towards the outside
            if z < reach:
                if z > reach - 1.6 or ax < 15.6:
                    return "led"                            # an LED line along its back and inner edge
                return "lamp"
        # the tail: a full-width light bar under the ducktail, a black panel round the plate, the diffuser
        # the tail: a light bar the whole width under the ducktail (wrapping round the corners), a gloss black band
        # under it with the plate, the carbon diffuser at the bottom
        dk = deck(z)
        if z > TAIL - 4 or (z > TAIL - 10 and (w - ax) < 2.5):
            if dk - 8 < y < dk - 4 and ax < w - 0.3:
                return "tail"
            if (dk - 9 < y <= dk - 8 or dk - 4 <= y < dk - 3) and ax < w - 0.3:
                return "black"                              # the light bar's black frame
        if z > TAIL - 4:
            if y < 9:
                return "carbon"
            if 11 <= y <= 17.5 and ax < 11:
                return "black"                              # the plate's recess
        # the side: a carbon skirt along the sill, a black gill behind each front wheel
        if (w - ax) < 2.5:
            if y < 6.5 and WF + ARCH < z < WR - ARCH:
                return "carbon"
            if WF + ARCH + 1 < z < WF + ARCH + 9 and 9 < y < 17 and (z - (WF + ARCH + 1)) > (y - 9) * 0.5:
                return "gill"
        # hood vents: two black louvre panels either side of the middle
        if (t - y) < 2.5 and -54 < z < -38 and 6 < ax < 14:
            return "vent"
        return "paint"
    # the glasshouse: a gloss black roof and pillars, the windows open (their glass is planes, added apart)
    w = glass_w(z, y)
    r = roof(z)
    inner = (w - ax) > 1.6 and (r - y) > 1.6
    if inner:
        return None                                         # the cabin's air
    # window openings: the screen, the side windows, the rear glass
    if (r - y) > 1.6 and deck(z) + 1.2 < y:
        if z < -8 and ax < w - 1.6 and (w - ax) > 0:
            pass
        side_window = -24 < z < 46 and y < r - 2.2 and (w - ax) < 1.6
        if side_window and not (D1 - 1 <= z <= D1 + 1):     # the B pillar between door and quarter glass
            return "tint"                                   # tinted side glass
    if z < -7 and (r - y) < 1.7 and ax < w - 2.2:
        return None                                         # the screen: open, its glass is a plane
    if z > 18 and (r - y) < 1.7 and ax < w - 3.5 and z < CAB1 - 3:
        return "tint"                                       # the rear glass, tinted
    return "roof"


# ================================================================================================
# Paints
# ================================================================================================

def _body_decal(c):
    """The candy red: lit from above, a bright crease along the side, a reflection band on the hood and deck, panel
    seams, darker towards the sill. The 1 px risers of the stepped top take the top's colour (no stripes)."""
    x, y, z = c.p
    ax = abs(x)
    f = c.face
    if f in ("north", "south", "east", "west") and top(z, ax) - y < 1.2 and y > 12:
        f = "up"
    if f == "down":
        return ("gt_black", 0)
    if f == "up":
        # a soft reflection band down the hood and deck, the shoulder's highlight at the outer edge
        w = plan(z)
        if w - ax < 1.2:
            return ("gt_red", 7)
        if 9 < ax < 12:
            return ("gt_red", 6.2)
        if (D0 - 0.5 < z < D0 + 0.5 or D1 - 0.5 < z < D1 + 0.5) and ax > 20:
            return ("gt_red", 2.5)
        return ("gt_red", 5.6)
    if f in ("east", "west"):
        if (D0 <= z < D0 + 0.6 or D1 - 0.6 <= z < D1) and y > 6:
            return ("gt_red", 1.0)                         # door seams
        if 17.5 < y < 18.5 and -60 < z < 66:
            return ("gt_red", 6.4)                         # the crease along the side, catching the light
        if 16.5 < y <= 17.5 and -60 < z < 66:
            return ("gt_red", 2.4)                         # its shadow under it
        return ("gt_red", 1.6 + 3.2 * smooth((y - 5) / 20))
    if f == "north":
        return ("gt_red", 3.4 + 2.4 * smooth((y - 6) / 16))
    if f == "south":
        return ("gt_red", 3.0 + 2.2 * smooth((y - 8) / 16))
    return None


def _black_decal(c):
    x, y, z = c.p
    if c.face == "up":
        # the roof: gloss black with a long diagonal reflection
        k = (z + abs(x) * 0.6) % 22
        return ("gt_black", 4.6 if k < 2 else 3.4 if k < 3.2 else 2.3)
    return ("gt_black", 1.6 + 1.6 * smooth((y - 20) / 16))


def _tint_decal(c):
    """Dark tinted glass: deep blue-black, two pale reflection streaks running back and down."""
    x, y, z = c.p
    k = math.floor(z * 0.8 - y * 1.3) % 30
    if k in (0, 1):
        return ("gt_steel", 3)
    if k == 4:
        return ("gt_steel", 1)
    return ("gt_black", 0.6 + 0.8 * smooth((y - 26) / 14))


def _carbon_decal(c):
    x, y, z = c.p
    a, b = (x, z) if c.face in ("up", "down") else ((z, y) if c.face in ("east", "west") else (x, y))
    return ("gt_carbon", 2.4 if (math.floor(a) + math.floor(b)) % 2 else 1.4)


def _mesh_decal(c):
    x, y, z = c.p
    a = x if c.face in ("north", "south", "up", "down") else z
    return ("gt_black", 0) if (math.floor(a) + math.floor(y)) % 2 else ("gt_black", 2.2)


def _vent_decal(c):
    x, y, z = c.p
    if c.face != "up":
        return ("gt_black", 1)
    return ("gt_black", 0) if math.floor(z) % 2 else ("gt_black", 2.6)


PAINTS = {
    "core": Paint("gt_red", 2, noise=0, light=False),
    "paint": Paint("gt_red", 4, noise=0, grad=0, rim=0, ao=0, decal=_body_decal),
    "tint": Paint("gt_black", 1, noise=0, grad=0, rim=0, ao=0.3, decal=lambda c: _tint_decal(c)),
    "roof": Paint("gt_black", 3, noise=0, grad=0, rim=0.4, ao=0.5, decal=_black_decal),
    "well": Paint("gt_black", 0, noise=0, grad=0, rim=0, ao=0.4),
    "carbon": Paint("gt_carbon", 2, noise=0, grad=0, rim=0.2, ao=0.4, decal=_carbon_decal),
    "mesh": Paint("gt_black", 1, noise=0, grad=0, rim=0, decal=_mesh_decal),
    "gill": Paint("gt_black", 1, noise=0, grad=0, rim=0, decal=_mesh_decal),
    "vent": Paint("gt_black", 1, noise=0, grad=0, rim=0.2, decal=_vent_decal),
    "black": Paint("gt_black", 2, noise=0, grad=0, rim=0.3),
    "led": Paint("gt_led", 2, noise=0, light=False, glow=True),
    "lamp": Paint("gt_black", 1, noise=0, grad=0, rim=0.5),
    "tail": Paint("gt_tail", 2, noise=0, light=False, glow=True,
                  decal=lambda c: ("raw", (255, 40, 52, 255)) if abs(c.p[0]) > 3 else ("raw", (150, 10, 24, 255))),
}


def _pane_decal(c):
    """A see-through pane, painted by where it is (the screen is many thin risers: one sheet): two reflection streaks
    across it, the rest clear."""
    x, y, z = c.p
    d = math.floor(x * 0.9 + y * 1.4 - z * 0.3) % 40
    if d in (0, 1, 5):
        return ("raw", (230, 240, 255, 255)) if d != 5 else ("raw", (170, 190, 220, 255))
    return ("raw", (0, 0, 0, 0))


PANE = Paint("gt_black", 1, noise=0, light=False, decal=_pane_decal)


# ================================================================================================
# The car
# ================================================================================================

class Kit:
    def __init__(self, name, parts):
        self.body = BModel(name, shift=(8, 8, 8), scale=4.0)
        self.parts = {}
        for part in parts:
            m = BModel(f"{name}_{part}", shift=(8, 8, 8), scale=4.0)
            m.display = presets.furniture(gui_scale=0.4)
            m.group(part)
            self.parts[part] = m

    def __getitem__(self, part):
        return self.parts[part]

    def all(self):
        return {self.body.name: self.body, **{m.name: m for m in self.parts.values()}}


_KIT = {}


def gt_kit(name="phantom_gt"):
    if name in _KIT:
        return _KIT[name]
    kit = Kit(name, ("door_l", "door_r"))
    m = kit.body
    m.group("body")
    body, doors = _cells()
    for target, cells in ((m, body), (kit["door_l"], doors[-1]), (kit["door_r"], doors[1])):
        _flat_voxels(target, cells)
    _glass(m, kit)
    _tilted(m, kit)
    _cabin(m)
    _tail_details(m)
    _mirrors(kit)
    m.display = presets.furniture(gui_scale=0.4)
    _KIT[name] = kit
    return kit


FLAT = ("paint", "roof", "tint", "core")      # stepped surfaces: their light is painted, the game's face shading is off


def _flat_voxels(m, cells):
    """bake.voxels, with the game's face shading off on the painted body: a stepped surface reads as one smooth
    panel (each 1 px riser would otherwise be shaded darker than the tread above it, striping the hood)."""
    box = m.box

    def flat_box(name, frm, to, paint, **kw):
        if paint in [PAINTS[k] for k in FLAT]:
            kw["shade"] = False
        return box(name, frm, to, paint, **kw)
    m.box = flat_box
    try:
        voxels(m, "shell", cells, lambda label: PAINTS[label])
    finally:
        del m.box


def _glass(m, kit):
    """The windows as thin see-through planes: the screen (stepped up its rake), the side glass in the doors, the rear
    glass down the fastback."""
    # the screen: one clear pane at 45° from the cowl to the roof's front edge (its streaks painted by position)
    zs = -4 - (ROOF - deck(-14))                         # about where the 45° line meets the cowl
    while ROOF - (-4 - zs) > deck(zs) + 0.3:
        zs -= 0.25
    length = (-4 - zs) / math.cos(math.radians(45))
    half = glass_w(-10, (ROOF + deck(-10)) / 2) - 2.2
    y0 = deck(zs) + 0.3
    m.box("screen", (-half, y0, zs), (half, y0 + 0.5, zs + length), PANE,
          rotation=("x", -45 * TILT_X, (0, y0, zs)))
    # side glass: a pane in each door (it rises with the door) and a quarter glass behind it
    for side, part in ((-1, "door_l"), (1, "door_r")):
        for z0, z1, target in ((D0 + 4, D1 - 1, kit[part]), (D1 + 1, 42, m)):
            for z in range(z0, z1):
                zc = z + 0.5
                yb, yt = deck(zc) + 1.4, roof(zc) - 2.2
                if yt - yb < 1:
                    continue
                x = glass_w(zc, (yb + yt) / 2) - 0.8
                xa = min(side * x, side * (x - 0.5)), max(side * x, side * (x - 0.5))
                target.box("side_glass", (xa[0], yb, z), (xa[1], yt, z + 1), PANE)




def _tilted(m, kit):
    """Panes turned 22.5° laid over the stepped glasshouse, so it reads as smooth sheets: the fastback's rear glass
    (one pane down its middle) and the side glass (a pane per 2 px strip, leaning in; the door's strips ride with the
    door)."""
    tint = PAINTS["tint"]
    # the fastback: one pane from the roof's end down to the deck, its middle (the buttresses either side stay stepped)
    z0, z1 = FB0, fastback_end()
    length = (z1 - z0) / math.cos(math.radians(22.5))
    y0 = ROOF + 0.15
    m.box("rear_glass", (-13, y0, z0), (13, y0 + 0.6, z0 + length), tint,
          rotation=("x", -22.5 * TILT_X, (0, y0, z0)))
    for sx in (-1, 1):
        lo, hi = sorted((sx * 13, sx * 18))
        m.box("buttress", (lo, y0 - 0.05, z0), (hi, y0 + 0.55, z0 + length), PAINTS["roof"],
              rotation=("x", -22.5 * TILT_X, (0, y0 - 0.05, z0)))
    # the side glass: leaning in, a strip at a time (its top follows the roof)
    for side, part in ((-1, "door_l"), (1, "door_r")):
        for z in range(-22, 44, 2):
            if D1 - 1 <= z <= D1:
                continue                                    # the B pillar
            yb = deck(z + 1) + 1.4
            yt = min(roof(z), roof(z + 2)) - 2.4
            if yt - yb < 1.5:
                continue
            wb = glass_w(z + 1, yb) + 0.35
            h = (yt - yb) / math.cos(math.radians(22.5))
            target = kit[part] if D0 <= z < D1 else m
            lo, hi = sorted((side * wb, side * (wb + 0.6)))
            target.box("side_pane", (lo, yb, z), (hi, yb + h, z + 2), tint,
                       rotation=("z", side * 22.5 * TILT_Z, (side * wb, yb, z)))


def _cabin(m):
    """What shows through the glass: two bucket seats in black leather with red stitching, a dash with lit screens,
    a steering wheel, a centre tunnel."""
    leather = Paint("gt_leather", 2, noise=0, grad=0, rim=0.4,
                    decal=lambda c: ("gt_stitch", 1) if (c.face in ("north", "south") and abs(abs(c.p[0]) % 7 - 3.5) < 0.4) else None)
    for sx in (-10.9, 10.9):
        m.box("seat", (sx - 5, 7, -2), (sx + 5, 10.5, 9), leather)                 # cushion
        m.box("seat", (sx - 5, 10, 8), (sx + 5, 26, 11), leather)                  # back
        m.box("seat", (sx - 6, 8, 0), (sx - 4.5, 15, 10), leather)                 # bolsters
        m.box("seat", (sx + 4.5, 8, 0), (sx + 6, 15, 10), leather)
    m.box("tunnel", (-2.5, 7, -24), (2.5, 13, 8), Paint("gt_carbon", 2, noise=0, decal=_carbon_decal))
    dash = Paint("gt_leather", 1, noise=0, grad=0, rim=0.3)
    m.box("dash", (-22, 14, -27), (22, 19, -21), dash)
    screen = Paint("gt_screen", 1, noise=0, light=False, glow=True,
                   decal=lambda c: ("gt_screen", 2 if (math.floor(c.p[0]) + math.floor(c.p[1])) % 5 else 0))
    m.box("screen_l", (-17, 16, -21), (-6, 19, -20.5), screen)
    m.box("screen_c", (-3, 13, -21), (3, 18, -20.5), screen)
    # the steering wheel (driver on the left): a rim of small boxes round a hub, on a column
    cx, cy, cz = -10.9, 17, -18
    rim = Paint("gt_leather", 1, noise=0, grad=0, rim=0.6)
    for k in range(16):
        a = k * math.pi / 8
        y, x = cy + math.cos(a) * 3.6, cx + math.sin(a) * 3.6
        m.box("wheel", (x - 0.6, y - 0.6, cz), (x + 0.6, y + 0.6, cz + 1), rim)
    m.box("wheel", (cx - 1.2, cy - 1.2, cz), (cx + 1.2, cy + 1.2, cz + 0.8), Paint("gt_steel", 4, noise=0))
    m.box("column", (cx - 0.6, cy - 1.6, cz - 3), (cx + 0.6, cy - 0.4, cz), rim)


def _tail_details(m):
    """Four exhaust tips in the diffuser and the third brake light."""
    tip = Paint("gt_steel", 4, noise=0, grad=0, rim=0.6,
                decal=lambda c: ("gt_black", 0) if c.face == "south" and math.hypot(c.p[0] - round(c.p[0] / 6) * 6, c.p[1] - 7.5) < 1.1 else None)
    for x in (-16, -10, 10, 16):
        cyl(m, "tip", (x, 7.5), 4, TAIL - 4, TAIL + 1.5, tip, axis="z")


def _mirrors(kit):
    """A mirror on each door near the screen's foot: a body-coloured shell on a short black arm from the door top."""
    for side, part in ((-1, "door_l"), (1, "door_r")):
        d = kit[part]
        z = D0 + 6
        y = deck(z) + 0.5
        x = plan(z) - 2.5
        lo, hi = sorted((side * x, side * (x + 3)))
        d.box("mirror_arm", (lo, y, z + 1), (hi, y + 1.5, z + 2.5), PAINTS["black"])
        lo, hi = sorted((side * (x + 2), side * (x + 6.5)))
        d.box("mirror", (lo, y + 1, z), (hi, y + 4, z + 3.5), PAINTS["paint"])
        lo, hi = sorted((side * (x + 2.5), side * (x + 6)))
        d.box("mirror_glass", (lo, y + 1.5, z + 3.5), (hi, y + 3.5, z + 3.8), Paint("gt_steel", 5, noise=0, light=False))


def phantom_gt():
    return gt_kit().body


def _part(name):
    return lambda: gt_kit().all()[name]


# ================================================================================================
# The wheel: drawn at twice its size (28 px = 0.875 block across), shrunk by 2
# ================================================================================================

def wheel_phantom():
    """A 20-spoke forged wheel in dark graphite with a bright machined lip, a drilled disc and a red calliper behind
    the spokes, a low-profile tyre."""
    m = BModel("wheel_phantom", shift=(8, 8, 8), floor=None, scale=2.0)
    d, width = 25, 10
    r = d / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            if rr > r - 1.2:
                return ("gt_black", 0)
            return ("gt_black", 1 if rr > r - 2.6 else 2)
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.2)
        return ("gt_black", 0 if k % 4 == 0 else 1)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, Paint("gt_black", 1, noise=0, rim=0.2, decal=tire), axis="x")
    rim_d = d - 6

    def face(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        rc = rim_d / 2
        if c.face not in ("east", "west"):
            return ("gt_steel", 2)
        if rr > rc - 1:
            return ("gt_steel", 6)                         # the machined lip
        if rr < 1.6:
            return ("gt_steel", 5) if rr < 0.8 else ("gt_black", 1)   # the centre lock
        a = math.atan2(z, y)
        t = (a * 10 / (2 * math.pi)) % 1.0
        spoke = t < 0.28 or 0.5 < t < 0.78                 # ten pairs of thin spokes
        if not spoke and 2.2 < rr < rc - 1.2:
            # between the spokes: the drilled disc, and over its top the red calliper
            if 0.8 < y and abs(z) < rc * 0.55 and rr > rc - 4.2:
                return ("gt_stitch", 1)
            return ("gt_steel", 1) if (math.floor(rr * 2) + math.floor(a * 6)) % 3 else ("gt_black", 0)
        lit = y > 0 and z < 0.5
        return ("gt_carbon", 3.6 if lit else 2.6)          # graphite spokes, the half towards the light catches it
    p = Paint("gt_steel", 3, noise=0, rim=0.2, ao=0.3, decal=face)
    w2 = width / 2
    cyl(m, "hub", (0, 0), rim_d, w2 - 0.5, w2 + 0.5, p, axis="x")
    cyl(m, "hub", (0, 0), rim_d, -w2 - 0.5, -w2 + 0.5, p, axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


ALL = {
    "phantom_gt": phantom_gt,
    "wheel_phantom": wheel_phantom,
    "phantom_gt_door_l": _part("phantom_gt_door_l"),
    "phantom_gt_door_r": _part("phantom_gt_door_r"),
}
