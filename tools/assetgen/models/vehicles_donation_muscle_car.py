"""
머슬카 (a donation car, content/stsv/items/vehicles.yml muscle_car): a big American muscle coupe in the spirit of the
late-60s / modern two-door pony-car (an original drawing: no make, no badge, no lettering anywhere). 4.95 x 2.1 x 1.47
blocks: long, low, wide and boxy, a very long hood and a short deck.

Drawn like the showcase car (models/vehicles_showcase.py, read its doc first): 32 px a block. The body is drawn at twice
its real size and shrunk by 4 (BModel scale 4), shown at vehicle.body-scale 2; the wheel drawn at twice its size and
shrunk by 2, shown at 1. So a texel is 1/32 block everywhere on it. The body is a field of 1 px cells (bake.voxels) cut
from profiles: a slab side with a sharp, flat beltline, a recessed scallop down the flank, haunches swelling over the
rear wheels, a power bulge down the hood, a squared-off roof with a near-flat rear window.

Drawn coordinates: 32 px a block, the car's ground centre at (0, 0, 0), front = north (-z), +x = the car's RIGHT side;
the driver sits on the left (-x).

Its look:
  * front: a full-width grille set 4 px deep under a thin brow, four round lamps in it (two a side, each a chrome
    reflector round a projector, ringed by a glowing halo), a fine black mesh between them, a chrome blade bumper
    under it, a black valance with an intake and the plate.
  * hood: a raised power bulge with a scoop on it, two chrome hood pins, shut lines; two wipers at the cowl.
  * side: the flat sharp beltline catching the light, the scallop below it, round arches (the rear ones flared), a
    flush chrome handle, bullet mirrors, side markers, a chrome racing fuel filler on the right rear quarter.
  * back: one horizontal red tail lamp band the full width (split in the middle, lit LED outlines), a ducktail lip on
    the short deck, a chrome bumper, a black diffuser with two big round exhaust tips.
  * paint: the body colour (colour), two bold racing stripes over hood, scoop, roof, deck and lip (stripe, or None),
    the cabin's vinyl (trim). All three are arguments (tuning colours later).
  * inside: two high-back vinyl bucket seats (pleated, piped), a rear bench, a dash with two big round dials in a
    hooded binnacle and two small ones in the middle, a three-spoke wheel, a console with a pistol-grip shifter.

The plugin shows ONE wheel model at all four corners (vehicles.yml `wheel`): the "fat rear tyres" are a matter of
stance: the wheel is a fat one (0.81 block across, 0.375 wide) and the rear track is wider (the rear wheels stand 2 px
further out, filling the flared haunches).

Its parts: door_l, door_r (the side between the two door seams with its frameless glass, mirror, handle and door card:
hinged at the front edge, they swing out about a vertical axis: vehicles.yml movers).
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, voxels
from stsv_assets.palette import MATERIALS, PALETTE, REMAP, build_ramp
from stsv_assets import presets


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("mc_orange", 52, 0.185, 0.68, 0.48, 8, True)     # a deep, hot muscle-car orange
_ramp("mc_stripe", 265, 0.012, 0.22, 0.22, 6, False)   # satin black stripes
_ramp("mc_black", 265, 0.012, 0.24, 0.26, 6, False)    # gloss black trim, grille, valance
_ramp("mc_chrome", 235, 0.014, 0.72, 0.56, 8, False)   # bumpers, pins, tips, bezels
_ramp("mc_rim", 250, 0.010, 0.30, 0.30, 6, False)      # dark graphite rims
_ramp("mc_led", 210, 0.050, 0.92, 0.12, 3, True)       # the headlamps' halo rings
_ramp("mc_tail", 24, 0.235, 0.52, 0.34, 5, True)       # the tail lamp band
_ramp("mc_amber", 62, 0.170, 0.74, 0.20, 3, True)      # side markers
_ramp("mc_vinyl", 30, 0.012, 0.24, 0.20, 5, False)     # black vinyl cabin
_ramp("mc_wood", 55, 0.080, 0.42, 0.26, 4, False)      # the pistol grip
_ramp("mc_caliper", 30, 0.190, 0.52, 0.22, 3, True)    # brake callipers


def _n(ramp):
    """Steps in a ramp (followed through palette.remapped, so a tuning paint keeps its lights)."""
    spec = MATERIALS.get(REMAP.get(ramp, ramp))
    return spec[4] if spec else 5


def tone(ramp, t):
    """(ramp, step) at lightness t: 0 the ramp's darkest, 1 its lightest (whatever its length), beyond continues."""
    return (ramp, t * (_n(ramp) - 1))


# ================================================================================================
# Shape (drawn px, 32 a block)
# ================================================================================================

NOSE, TAIL = -77, 77           # z of the nose and the tail panel
WF, WR = -47, 46               # front / rear axle (z)
WY, WRAD = 13, 13              # wheel centre height, its radius (a 0.81 block wheel)
ARCH = 15.0                    # the arch's radius round the wheel
TRACK_F, TRACK_R = 19, 21      # the wheels' inner faces (x): front wheel 19..31, rear 21..33
GROUND = 4                     # the body's lowest cells
FLOOR = 6                      # the cabin floor
D0, D1 = -30, 14               # the door, between its two seams (z)
ZS0 = -33                      # the screen's foot
ROOF = 47                      # the roof's top
ZR = 18                        # the roof's back edge: the rear glass falls from here at 22.5 degrees
T = math.tan(math.radians(22.5))
ZS1 = ZS0 + (ROOF - 30.2)      # the screen's top (it rises at 45 degrees)
TUB0, TUB1 = D0, 44            # the cabin's tub
GRILLE_X, GRILLE_Y0, GRILLE_Y1, GRILLE_D = 29.5, 14, 26, 4
LAMP_D, LAMP_Y = 8, 20
LAMPS_X = (15.5, 24.5)         # the four round lamps: centres (x, either side)
TAIL_Y0, TAIL_Y1 = 20, 27      # the tail lamp band


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def bump(z, c, w):
    return smooth(1 - abs(z - c) / w)


def plan(z):
    """The body's half width at the beltline at z: slab sides, a little swell over the front wheels, broad haunches
    over the rear, the corners squared off with a small chamfer."""
    w = 31.0
    w += 0.8 * bump(z, WF, 22)
    w += 2.6 * bump(z, WR + 2, 30)
    if z < NOSE + 5:
        w -= 3.2 * (1 - smooth((z - NOSE) / 5)) ** 1.4
    if z > TAIL - 5:
        w -= 2.8 * (1 - smooth((TAIL - z) / 5)) ** 1.4
    return w


def deck(z):
    """The height of the hood / belt / deck along the middle."""
    if z < ZS0:
        return 28 + 2.2 * smooth((z - NOSE) / 40) ** 0.7          # the long hood, falling a little to the nose
    if z < 60:
        return 30.2 + 0.8 * (z - ZS0) / 93                        # the beltline, dead flat, a hair higher at the back
    return 31.0


def scallop(z, y):
    """The flank's recess (1 px deep): from behind the front wheel to the rear haunch, its ends swept."""
    if not (12 <= y < 21):
        return False
    z0 = -28 + (21 - y) * 0.7
    z1 = 31 - (y - 12) * 1.3
    return z0 < z < z1


def half_width(z, y):
    """The body's half width at height y: the shoulder flat and sharp, tucked under below y 12, the scallop cut in."""
    w = plan(z)
    if y < 12:
        w -= (12 - y) * 0.33
    if scallop(z, y):
        w -= 1.0
    return w


def bulge(z, ax):
    """The power bulge down the middle of the hood."""
    if not (-71 < z < ZS0 + 1) or ax > 11.5:
        return 0.0
    # two crisp 1 px terraces up its sides and at its front end
    return (1.0 if ax > 10.5 or z < -69 else 2.0)


def top(z, ax):
    """The body's top at z, |x| = ax: flat, the bulge, the ducktail lip, a sharp shoulder (one px rolled)."""
    h = deck(z) + bulge(z, ax)
    w = plan(z)
    if z > TAIL - 5 and ax < w - 1.5:
        h += 1.6 * smooth((z - (TAIL - 5)) / 2)                   # the ducktail lip
    if z < NOSE + 1.5:
        h -= 1.0                                                  # the nose's leading edge rolled
    if w - ax < 1.0:
        h -= 0.9
    return h


def front_lo(z):
    """The chin: the lowest cells pull back under the bumpers at both ends."""
    if z < NOSE + 2:
        return 7 - (z - NOSE)
    if z > TAIL - 2:
        return 7 - (TAIL - z)
    return GROUND


def roof(z):
    """The glasshouse's top: the screen at 45 degrees, a flat squared-off roof, the near-flat rear glass at 22.5."""
    if z < ZS1:
        return max(ROOF - (ZS1 - z), deck(z) + 0.3)
    if z < ZR:
        return ROOF
    return max(ROOF - (z - ZR) * T, deck(z) + 0.3)


def glass_end():
    z = ZR
    while ROOF - (z - ZR) * T > deck(z) + 0.3:
        z += 0.25
    return z


CAB1 = glass_end()


def glass_w(z, y):
    """The glasshouse's half width at height y: its sides lean in at 22.5 degrees, the roof's edge squared off."""
    r, d = roof(z), deck(z)
    if r <= d:
        return -1
    w = plan(z) - 4.0 - (y - d) * T
    if r - y < 1.2:
        w -= (1.2 - (r - y)) * 1.5
    return w


def in_window(z, y, grow=0.0):
    """The side window opening (both door glass and quarter glass), grown by `grow` px for its seal."""
    if y <= deck(z) + 1.6 - grow or y >= roof(z) - 2.2 + grow:
        return False
    return ZS0 + 2 - grow < z < 34 + grow - (y - 31) * 1.1


def in_arch(z, y, ax):
    for wz, tr in ((WF, TRACK_F), (WR, TRACK_R)):
        if ax > tr - 1.5 and math.hypot(z - wz, y - WY) < ARCH:
            return True
    return False


# ================================================================================================
# The cells
# ================================================================================================

def _cells():
    body, doors = {}, {-1: {}, 1: {}}
    for z in range(NOSE, TAIL):
        zc = z + 0.5
        wmax = plan(zc) + 0.5
        lo = front_lo(zc)
        for x in range(-int(wmax) - 1, int(wmax) + 1):
            xc = x + 0.5
            ax = abs(xc)
            t = top(zc, ax)
            cab = ZS0 - 1 <= zc <= CAB1 + 1
            ymax = int(math.ceil(max(t, roof(zc) if cab else 0))) + 1
            for y in range(int(lo), ymax):
                yc = y + 0.5
                lower = yc <= t and ax <= half_width(zc, yc)
                upper = cab and yc > deck(zc) - 0.5 and ax <= glass_w(zc, yc) and yc <= roof(zc)
                if not (lower or upper):
                    continue
                if lower and in_arch(zc, yc, ax):
                    continue
                label = _label(xc, yc, zc, ax, lower)
                if label is None:
                    continue
                side = -1 if xc < 0 else 1
                if lower and D0 <= zc < D1 and yc >= GROUND + 2 and ax > half_width(zc, yc) - 3 and label != "well":
                    doors[side][(x, y, z)] = label
                else:
                    body[(x, y, z)] = label
    return body, doors


def _label(x, y, z, ax, lower):
    if lower:
        w = half_width(z, y)
        t = top(z, ax)
        # the cabin's tub (floor at FLOOR, the doors and quarters its walls)
        if TUB0 < z < TUB1 and ax < w - 3 and y > FLOOR and y < t + 1:
            return None
        for wz, tr in ((WF, TRACK_F), (WR, TRACK_R)):
            if tr - 4.5 < ax < tr - 1.5 and math.hypot(z - wz, y - WY) < ARCH + 1 and y < t - 2.5:
                return "well"
        skin = (w - ax) < 2.5 or (t - y) < 2.5 or y < GROUND + 2.5 or z < NOSE + 6 or z > TAIL - 4
        if not skin:
            return "core"
        # the front: the deep-set grille (open in front of its mesh), the black valance under the bumper
        if z < NOSE + GRILLE_D + 1:
            if ax < GRILLE_X and GRILLE_Y0 <= y < GRILLE_Y1:
                return None if z < NOSE + GRILLE_D else "mesh"
            if z < NOSE + 2.5 and y < 10.5:
                return "black"
        # the back: the lamp band (split in the middle, a black frame), the plate's recess, the diffuser
        if z > TAIL - 2:
            if TAIL_Y0 <= y < TAIL_Y1 and ax < GRILLE_X:
                if ax < 1.5 or y < TAIL_Y0 + 1 or y > TAIL_Y1 - 1 or ax > GRILLE_X - 1:
                    return "black"
                return "tail"
            if 13.5 <= y < 19.5 and ax < 8:
                return None if z > TAIL - 1 else "black"
            if y < 9.5:
                return "black"
        return "paint"
    # the glasshouse
    w = glass_w(z, y)
    r = roof(z)
    if (w - ax) > 1.6 and (r - y) > 1.6:
        return None                                         # the cabin's air
    if (w - ax) <= 1.6 and (r - y) > 1.0:
        if in_window(z, y):
            if 14 <= z < 16:
                return "seal"                               # a slim black B pillar
            return None                                     # the side window: open (its glass is a pane)
        if in_window(z, y, 1.0):
            return "seal"
    if z < ZS1 - 0.5 and (r - y) <= 1.6 and ax < w - 2.2 and y > deck(z) + 1:
        return None                                         # the screen: open (a pane)
    if ZR + 1 < z < CAB1 - 2 and (r - y) <= 1.6 and ax < w - 3.0:
        return "rglass"                                     # the rear glass: dark, under a tilted pane
    return "paint"


# ================================================================================================
# Paints
# ================================================================================================

def _make_paints(C, S, TR):
    """The labels' paints for body colour C, stripe colour S (or None), cabin trim TR."""

    def stripe_at(ax):
        return S is not None and 1.5 <= ax < 8.5

    def up_colour(x, y, z, ax):
        w = plan(z)
        if stripe_at(ax):
            return tone(S, 0.62 if 4 <= ax < 6 else 0.5)
        if (w - ax) < 1.2:
            return tone(C, 0.86)                                      # the shoulder's sharp highlight
        if z < ZS0 and abs(ax - 24.5) < 0.5:
            return tone(C, 0.35)                                      # the hood's shut lines
        if ZS0 - 2 <= z < ZS0 - 1 and ax < 26:
            return tone(C, 0.35)                                      # the cowl's seam
        if 59 <= z < 60 and ax < 27.5:
            return tone(C, 0.35)                                      # the deck lid's seam
        if z > 59 and abs(ax - 27) < 0.5 and z < TAIL - 1:
            return tone(C, 0.35)
        if 12 <= ax < 15:
            return tone(C, 0.72)                                      # a reflection band down hood, roof and deck
        if y > 40:
            return tone(C, 0.66)
        return tone(C, 0.62)

    def body_decal(c):
        x, y, z = c.p
        ax = abs(x)
        f = c.face
        w = half_width(z, y)
        inner = ((f == "east" and x < 0) or (f == "west" and x > 0)) and TUB0 - 1 < z < TUB1 + 1 and y > FLOOR
        if inner and y < deck(z) + 0.2 and ax < w - 2:
            # the door cards and the cabin's sides: vinyl, a chrome strip, a body-coloured panel
            if abs(y - 24.5) < 0.5:
                return tone("mc_chrome", 0.85)
            if 19 <= y < 24:
                return tone(C, 0.45)
            return tone(TR, 0.35 if math.floor(y) % 3 else 0.2)
        if f == "up" and y < deck(z) - 3 and TUB0 < z < TUB1 and ax < w - 2:
            return tone(TR, 0.15 if math.floor(z) % 3 else 0.05)       # carpet
        if f == "down":
            if y > 34:
                return tone(TR, 0.55)                                 # the headliner
            if y > 9:
                return tone(C, 0.12)
            return tone("mc_black", 0)
        if f in ("north", "south", "east", "west") and top(z, ax) - y < 1.2 and y > 20 and not (y > 32 and ax < glass_w(z, y) + 0.5 and ZS0 < z < CAB1):
            f = "up"                                                  # a riser of the stepped top: the top's colour
        if f == "up" and y > deck(z) + 0.8 and ZS0 < z < CAB1 and y < roof(z) - 1.0 and ax > 9:
            return tone(C, 0.56)                                      # the steps of the leaning glasshouse sides
        if f == "up":
            return up_colour(x, y, z, ax)
        if y > 32 and ZS0 < z < CAB1 and ax < plan(z) - 3:
            # the glasshouse: pillars and roof sides
            if f in ("east", "west"):
                if stripe_at(ax):
                    return tone(S, 0.4)
                return tone(C, 0.6 if y > 44 else 0.5)
            if f == "north":
                if stripe_at(ax):
                    return tone(S, 0.45)
                return tone(C, 0.62)
            return tone(C, 0.42) if not stripe_at(ax) else tone(S, 0.3)
        if f in ("east", "west"):
            if (D0 <= z < D0 + 0.7 or D1 - 0.7 <= z < D1) and y > GROUND + 1.5:
                return tone(C, 0.08)                                  # the door seams
            if ZS0 - 8 < z < ZS0 - 7 and y > 22 and z < 0:
                pass
            dk = deck(z)
            if dk - 2 <= y < dk - 1:
                return tone(C, 0.80)                                  # the beltline's crease catching the light
            if dk - 3 <= y < dk - 2:
                return tone(C, 0.34)                                  # its shadow
            if scallop(z, y) or scallop(z, y + 1) and y < 21:
                if 19.5 <= y < 21:
                    return tone(C, 0.28)                              # the scallop's upper lip throws a shadow
                return tone(C, 0.36 + 0.12 * smooth((y - 12) / 8))
            if 21 <= y < 22 and scallop(z, y - 1.5):
                return tone(C, 0.82)                                  # the scallop's top edge, lit
            if NOSE + 3 < z < NOSE + 7 and 21 <= y < 23:
                return tone("mc_amber", 0.7)                          # side marker lamps
            if TAIL - 7 < z < TAIL - 3 and 21 <= y < 23:
                return tone("mc_tail", 0.4)
            for wz in (WF, WR):
                rr = math.hypot(z - wz, y - WY)
                if ARCH <= rr < ARCH + 1.0:
                    return tone(C, 0.30)                              # the arch's rolled lip
            if y < GROUND + 2.5:
                return tone(C, 0.18)
            return tone(C, 0.30 + 0.30 * smooth((y - 6) / 20))
        if f == "north":
            if z < NOSE + GRILLE_D + 0.5 and ax < GRILLE_X + 1 and GRILLE_Y0 - 1 <= y < GRILLE_Y1 + 1 and z < NOSE + 1:
                return tone("mc_chrome", 0.55)                        # a thin satin surround round the grille
            if z > NOSE + 1 and ax < GRILLE_X and GRILLE_Y0 - 1 < y < GRILLE_Y1 + 1:
                return tone("mc_black", 0.1)                          # inside the grille's opening
            if stripe_at(ax) and y > 26:
                return tone(S, 0.4)
            return tone(C, 0.52 + 0.18 * smooth((y - 8) / 18))
        if f == "south":
            if z < TAIL - 1 and ax < GRILLE_X and GRILLE_Y0 < y < GRILLE_Y1 + 1 and z < NOSE + 6:
                return tone("mc_black", 0.1)
            if stripe_at(ax) and y > 27:
                return tone(S, 0.35)
            return tone(C, 0.40 + 0.16 * smooth((y - 8) / 18))
        return None

    def well_decal(c):
        return tone("mc_black", 0.0 if c.face != "up" else 0.1)

    def mesh_decal(c):
        x, y, z = c.p
        if c.face != "north":
            return tone("mc_black", 0.1)
        # a fine black honeycomb, the middle row of cells lighter (light from above)
        fx, fy = math.floor(x), math.floor(y)
        if (fx + (fy // 2) % 2) % 2 == 0 and fy % 2 == 0:
            return tone("mc_black", 0.0)
        if fy % 2 == 1 and (fx + (fy // 2)) % 2 == 0:
            return tone("mc_chrome", 0.32)                            # the honeycomb's lit edges
        return tone("mc_black", 0.5 if fy > 20 else 0.36)

    def black_decal(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face == "north" and y < 9 and ax < 13 and 5 <= y:
            # the lower intake: a mesh
            return tone("mc_black", 0.0) if (math.floor(x) + math.floor(y)) % 2 else tone("mc_black", 0.3)
        if c.face == "south" and y < 9:
            # the diffuser's strakes
            return tone("mc_black", 0.45) if math.floor(ax) % 5 == 0 and ax > 6 else tone("mc_black", 0.15)
        if c.face == "up":
            return tone("mc_black", 0.55)
        return tone("mc_black", 0.3)

    def tail_decal(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face != "south":
            return ("raw", (120, 14, 20, 255))
        # an LED racetrack outline round each half, the inner part darker red, a lit centre line
        u0, u1 = 2.5, GRILLE_X - 1
        v0, v1 = TAIL_Y0 + 1, TAIL_Y1 - 1
        on_ring = (abs(y - (v0 + 0.5)) < 0.5 or abs(y - (v1 - 0.5)) < 0.5) and u0 + 1 <= ax < u1 - 1 \
            or (abs(ax - (u0 + 0.5)) < 0.5 or abs(ax - (u1 - 0.5)) < 0.5) and v0 + 1 <= y < v1 - 1
        if on_ring:
            return ("raw", (255, 66, 70, 255))
        if abs(y - 23.5) < 0.5 and u0 + 3 < ax < u1 - 3:
            return ("raw", (220, 30, 42, 255))
        return ("raw", (140, 12, 24, 255)) if (math.floor(ax) % 2) else ("raw", (158, 16, 28, 255))

    def rglass_decal(c):
        x, y, z = c.p
        k = math.floor(z * 0.6 - x * 0.9) % 26
        if k in (0, 1):
            return tone("mc_chrome", 0.55)
        if k == 4:
            return tone("mc_chrome", 0.3)
        return tone("mc_chrome", 0.06 + 0.10 * smooth((y - 34) / 12))

    def seal_decal(c):
        return tone("mc_black", 0.15 if c.face != "up" else 0.35)

    return {
        "core": Paint(C, 2, noise=0, light=False),
        "paint": Paint(C, 4, noise=0, grad=0, rim=0, ao=0, decal=body_decal),
        "well": Paint("mc_black", 0, noise=0, grad=0, rim=0, ao=0.4, decal=well_decal),
        "mesh": Paint("mc_black", 1, noise=0, grad=0, rim=0, decal=mesh_decal),
        "black": Paint("mc_black", 1, noise=0, grad=0, rim=0.3, decal=black_decal),
        "tail": Paint("mc_tail", 2, noise=0, light=False, glow=True, decal=tail_decal),
        "rglass": Paint("mc_black", 1, noise=0, grad=0, rim=0, ao=0, decal=rglass_decal),
        "seal": Paint("mc_black", 1, noise=0, grad=0, rim=0, decal=seal_decal),
    }


FLAT = ("paint", "core", "rglass")


def _flat_voxels(m, cells, paints):
    """bake.voxels with the game's face shading off on the painted body: a stepped surface reads as one panel."""
    box = m.box
    flat = [paints[k] for k in FLAT]

    def flat_box(name, frm, to, paint, **kw):
        if any(paint is p for p in flat):
            kw["shade"] = False
        return box(name, frm, to, paint, **kw)
    m.box = flat_box
    try:
        voxels(m, "shell", cells, lambda label: paints[label])
    finally:
        del m.box


def _pane_decal(c):
    """A see-through pane: two pale reflection streaks across it, the rest clear (the riders show)."""
    x, y, z = c.p
    d = math.floor(x * 0.7 + y * 1.3 - z * 0.45) % 34
    if d in (0, 1):
        return ("raw", (232, 240, 255, 235))
    if d == 4:
        return ("raw", (176, 192, 222, 200))
    return ("raw", (0, 0, 0, 0))


PANE = Paint("mc_black", 1, noise=0, light=False, decal=_pane_decal)


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


def muscle_car(colour="mc_orange", stripe="mc_stripe", trim="mc_vinyl", name="muscle_car"):
    return muscle_kit(colour, stripe, trim, name).body


def muscle_kit(colour="mc_orange", stripe="mc_stripe", trim="mc_vinyl", name="muscle_car"):
    key = (colour, stripe, trim, name, tuple(sorted(REMAP.items())))
    if key in _KIT:
        return _KIT[key]
    kit = Kit(name, ("door_l", "door_r"))
    m = kit.body
    m.group("body")
    paints = _make_paints(colour, stripe, trim)
    body, doors = _cells()
    for target, cells in ((m, body), (kit["door_l"], doors[-1]), (kit["door_r"], doors[1])):
        _flat_voxels(target, cells, paints)
    ctx = dict(C=colour, S=stripe, TR=trim, P=paints, body=body, doors=doors)
    _front(m, ctx)
    _hood(m, ctx)
    _rear(m, ctx)
    _glass(m, kit, ctx)
    _cabin(m, ctx)
    _door_parts(kit, ctx)
    _side_details(m, ctx)
    m.display = presets.furniture(gui_scale=0.4)
    _KIT[key] = kit
    return kit


def _outer_x(cells, y, z):
    """The outer face (x > 0 side) of the cells at row y, slice z: the drawn x where the skin ends."""
    xs = [p[0] for p in cells if p[1] == y and p[2] == z and p[0] >= 0]
    return max(xs) + 1 if xs else None


def _top_y(cells, x, z):
    ys = [p[1] for p in cells if p[0] == x and p[2] == z]
    return max(ys) + 1 if ys else None


CHROME = Paint("mc_chrome", 5, noise=0, grad=0, rim=0.5, ao=0.3)


def _chrome(lit=0.95, side=0.7, low=0.4):
    """A chrome part: bright on top, a sky band, a dark ground reflection low down."""
    def decal(c):
        if c.face == "up":
            return tone("mc_chrome", lit)
        if c.face == "down":
            return tone("mc_chrome", 0.15)
        return tone("mc_chrome", side if (c.p[1] % 4) > 1.5 else low)
    return Paint("mc_chrome", 5, noise=0, grad=0, rim=0.4, ao=0.2, decal=decal)


def _front(m, ctx):
    """The four round lamps in the grille, the chrome blade bumper, the plate's flat."""
    m.group("lights")
    for sx in (-1, 1):
        for lx in LAMPS_X:
            cx = sx * lx

            def lamp(c, cx=cx):
                if c.face != "north":
                    return ("raw", (64, 68, 78, 255))
                r = math.hypot(c.p[0] - cx, c.p[1] - LAMP_Y)
                if r < 1.1:
                    return ("raw", (255, 252, 236, 255))              # the projector's hot core
                if r < 1.8:
                    return ("raw", (120, 130, 150, 255))              # its black ring
                if r < 2.8:
                    k = math.atan2(c.p[1] - LAMP_Y, c.p[0] - cx)
                    return ("raw", (226, 232, 240, 255)) if k > 0.4 else ("raw", (176, 184, 198, 255))   # the reflector
                if r < 3.5:
                    return ("raw", (206, 236, 255, 255))              # the halo ring
                return ("raw", (118, 124, 138, 255))                  # the bezel
            cyl(m, "headlamp", (cx, LAMP_Y), LAMP_D, NOSE + 1.5, NOSE + GRILLE_D,
                Paint("mc_led", 2, noise=0, light=False, glow=True, decal=lamp), axis="z")
    m.group("body")
    # the chrome blade bumper under the grille, wrapping round the corners
    bump = _chrome()
    m.box("bumper_f", (-28.5, 10, NOSE - 2), (28.5, 13.5, NOSE + 0.5), bump)
    for sx in (-1, 1):
        lo, hi = sorted((sx * 28.5, sx * 30.4))
        m.box("bumper_f_end", (lo, 10, NOSE - 1.2), (hi, 13.5, NOSE + 3), bump)
        # bumperettes' rubber guards
        lo, hi = sorted((sx * 9, sx * 11))
        m.box("bumper_guard", (lo, 9.5, NOSE - 2.6), (hi, 13.8, NOSE - 2), Paint("mc_black", 1, noise=0, rim=0.3))
    # the plate's flat on the valance
    m.box("plate_f", (-7.5, 5.5, NOSE - 0.6), (7.5, 9.3, NOSE + 0.2), Paint("mc_black", 0, noise=0, rim=0.2))


def _hood(m, ctx):
    """The scoop on the bulge, the hood pins, the wipers."""
    C, S, body = ctx["C"], ctx["S"], ctx["body"]

    def scoop(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face == "north":
            return tone(C, 0.62)
        if c.face == "up":
            if S is not None and 1.5 <= ax < 8.5:
                return tone(S, 0.6)
            return tone(C, 0.86)
        if c.face == "down":
            return tone("mc_black", 0)
        return tone(C, 0.55)
    zs0, zs1 = -64, -48
    base = max(_top_y(body, x, z) or 0 for x in (-1, 0) for z in range(zs0, zs1))
    sp = Paint(ctx["C"], 4, noise=0, grad=0, rim=0, decal=scoop)
    m.box("scoop", (-8, base - 0.5, zs0 + 1), (8, base + 3.5, zs1), sp)
    # the mouth: a chrome lip round a black opening, set in the scoop's face
    m.box("scoop_mouth", (-6.5, base + 0.5, zs0 + 0.6), (6.5, base + 3, zs0 + 1), Paint("mc_black", 0, noise=0, light=False,
          decal=lambda c: ("raw", (10, 10, 14, 255)) if c.face == "north" else tone("mc_black", 0)))
    m.box("scoop_lip", (-8, base + 3, zs0), (8, base + 3.6, zs0 + 1.4), _chrome())
    # hood pins: a chrome pin and its cable loop on each front corner of the hood
    for sx in (-1, 1):
        x = sx * 20
        y = _top_y(body, int(math.floor(x)), -70)
        m.box("hood_pin", (x - 1, y, -71), (x + 1, y + 0.6, -69), _chrome())
        m.box("hood_pin", (x - 0.4, y + 0.6, -70.4), (x + 0.4, y + 1.6, -69.6), CHROME)
        lo, hi = sorted((x + sx * 1, x + sx * 3))
        m.box("hood_pin_cable", (lo, y, -70.25), (hi, y + 0.3, -69.75), CHROME)
    # wipers resting at the screen's foot
    wip = Paint("mc_black", 0, noise=0, rim=0.4)
    yw = deck(ZS0 - 1)
    for x0, x1 in ((-22, -3), (3, 20)):
        m.box("wiper", (x0, yw + 0.1, ZS0 - 1.6), (x1, yw + 0.7, ZS0 - 0.8), wip)


def _rear(m, ctx):
    """The chrome bumper, the two big round tips."""
    bump = _chrome()
    m.box("bumper_r", (-29.5, 9.5, TAIL - 0.5), (29.5, 13.5, TAIL + 2), bump)
    for sx in (-1, 1):
        lo, hi = sorted((sx * 29.5, sx * 31.0))
        m.box("bumper_r_end", (lo, 9.5, TAIL - 3), (hi, 13.5, TAIL + 1.2), bump)

    def tip(c, cx):
        if c.face == "south":
            r = math.hypot(c.p[0] - cx, c.p[1] - 6.5)
            if r < 1.6:
                return ("raw", (14, 14, 18, 255))
            if r < 2.2:
                return ("raw", (60, 58, 62, 255))                      # sooty inner wall
            return tone("mc_chrome", 0.95 if c.p[1] > 6.5 else 0.7)
        if c.face == "up":
            return tone("mc_chrome", 0.95)
        return tone("mc_chrome", 0.75 if c.p[1] > 6.5 else 0.35)
    for sx in (-1, 1):
        cx = sx * 20
        cyl(m, "exhaust", (cx, 6.5), 6, TAIL - 4, TAIL + 2.5,
            Paint("mc_chrome", 5, noise=0, grad=0, rim=0.4, decal=lambda c, cx=cx: tip(c, cx)), axis="z")


def _glass(m, kit, ctx):
    """The screen (one pane at 45 degrees), the rear glass (one tinted pane at 22.5 down the stepped glass), the side
    glass (panes leaning in at 22.5 degrees, 2 px strips; the door's ride with the door)."""
    # the screen
    zs = ZS0 + 0.6
    y0 = deck(zs) + 0.4
    length = (ZS1 - zs) * math.sqrt(2) - 0.6
    half = glass_w(ZS0 + 8, (ROOF + deck(ZS0 + 8)) / 2) - 2.0
    m.box("screen", (-half, y0, zs), (half, y0 + 0.5, zs + length), PANE, rotation=("x", 45, (0, y0, zs)))
    # the rear glass: a dark tinted pane down the stepped rear glass
    z0, z1 = ZR + 0.5, CAB1 - 2
    length = (z1 - z0) / math.cos(math.radians(22.5))
    y0 = ROOF - 0.5 * T + 0.15
    rg = Paint("mc_black", 1, noise=0, light=False,
               decal=lambda c: ("raw", (214, 224, 242, 200)) if math.floor(c.p[2] * 0.8 - c.p[0] * 1.1) % 22 in (0, 1)
               else ("raw", (150, 166, 196, 150)) if math.floor(c.p[2] * 0.8 - c.p[0] * 1.1) % 22 == 4
               else ("raw", (40, 50, 68, 120)))
    hw = glass_w(ZR + 8, ROOF - 3) - 3.2
    m.box("rear_glass", (-hw, y0, z0), (hw, y0 + 0.5, z0 + length), rg, rotation=("x", 22.5, (0, y0, z0)))
    # the side glass
    for side, part in ((-1, "door_l"), (1, "door_r")):
        z = math.floor(ZS0 + 2)
        while z < 34:
            if 14 <= z < 16:
                z = 16
                continue
            z1 = z + 2 if z + 2 <= 14 or z >= 16 else 14
            ys = [y + 0.5 for y in range(28, 47) if all(in_window(zz + 0.5, y + 0.5) for zz in range(z, z1))]
            if len(ys) >= 2:
                yb, yt = min(ys) - 0.5, max(ys) + 0.5
                wb = glass_w(z + 1, yb) - 0.6
                h = (yt - yb) / math.cos(math.radians(22.5))
                target = kit[part] if z < D1 else m
                lo, hi = sorted((side * wb, side * (wb + 0.5)))
                target.box("side_glass", (lo, yb, z), (hi, yb + h, z1), PANE,
                           rotation=("z", side * 22.5, (side * wb, yb, z)))
            z = z1


def _cabin(m, ctx):
    """Two high-back bucket seats, a rear bench, the dash with round dials, the wheel, a console with a pistol grip."""
    C, TR = ctx["C"], ctx["TR"]

    def vinyl(c):
        x, y, z = c.p
        if c.face in ("north", "south") and c.hi[1] - c.lo[1] > 6:
            # pleats across the back's insert, piped in the body colour along its edge
            lx = abs(abs(x) - 10.9)
            if abs(lx - 3.5) < 0.5:
                return tone(C, 0.5)
            if lx < 3 and math.floor(y) % 2 == 0:
                return tone(TR, 0.15)
        if c.face == "up" and c.hi[1] - c.lo[1] < 5:
            lx = abs(abs(x) - 10.9)
            if abs(lx - 3.5) < 0.5:
                return tone(C, 0.5)
            if lx < 3 and math.floor(z) % 2 == 0:
                return tone(TR, 0.15)
        return tone(TR, 0.55 if c.face == "up" else 0.35)
    seat = Paint(TR, 2, noise=0, grad=0, rim=0.5, decal=vinyl)
    for sx in (-10.9, 10.9):
        m.box("cushion", (sx - 5, FLOOR, -6), (sx + 5, 8.5, 9), seat)
        m.box("bolster", (sx - 6.5, FLOOR, -5), (sx - 5, 10.5, 9), seat)
        m.box("bolster", (sx + 5, FLOOR, -5), (sx + 6.5, 10.5, 9), seat)
        m.box("seat_back", (sx - 6, 8.5, 9), (sx + 6, 30, 12), seat)
        m.box("seat_head", (sx - 4, 30, 9.5), (sx + 4, 36, 12), seat)
    # the rear bench (for show: the car seats two)
    m.box("bench", (-24, FLOOR, 26), (24, 10, 36), seat)
    m.box("bench_back", (-24, 10, 35), (24, 24, 39), seat)
    m.box("parcel_shelf", (-26, 24, 39), (26, 25, TUB1), Paint(TR, 1, noise=0, rim=0.2))

    # the dash
    def dash(c):
        x, y, z = c.p
        if c.face == "up":
            return tone(TR, 0.25)
        if c.face == "south" and abs(y - 23.5) < 0.5:
            return tone("mc_chrome", 0.8)                              # a bright strip across the dash
        return tone(TR, 0.3)
    m.box("dash", (-26, 17, ZS0 - 0.5), (26, 29.6, -25), Paint(TR, 1, noise=0, grad=0, rim=0.3, decal=dash))
    m.box("binnacle", (-19, 29, -26.5), (-3, 30.5, -24), Paint(TR, 1, noise=0, rim=0.4))

    def dial(cx, cy, rad):
        def decal(c):
            if c.face != "south":
                return tone("mc_chrome", 0.6)
            r = math.hypot(c.p[0] - cx, c.p[1] - cy)
            a = math.atan2(c.p[1] - cy, c.p[0] - cx)
            if r > rad - 0.7:
                return tone("mc_chrome", 0.9)                          # the chrome bezel
            if abs(a - 2.2) < 0.25 and r < rad - 0.7:
                return ("raw", (255, 120, 40, 255))                    # the orange needle
            if r > rad - 1.6 and math.floor((a + math.pi) * 3) % 2 == 0:
                return ("raw", (232, 232, 220, 255))                   # tick marks
            return ("raw", (18, 18, 22, 255))
        return Paint("mc_chrome", 3, noise=0, light=False, glow=True, decal=decal)
    for cx in (-15, -7):
        cyl(m, "dial", (cx, 25.5), 7, -25, -24.5, dial(cx, 25.5, 3.5), axis="z")
    for cx in (1.5, 6.5):
        cyl(m, "dial_small", (cx, 21.5), 4, -25, -24.6, dial(cx, 21.5, 2), axis="z")

    # the three-spoke wheel (driver on the left)
    cx, cy, cz = -10.9, 24, -19
    rim = Paint(TR, 1, noise=0, grad=0, rim=0.6, decal=lambda c: tone(TR, 0.45 if c.p[1] > cy else 0.25))
    for k in range(16):
        a = k * math.pi / 8
        y, x = cy + math.cos(a) * 4.3, cx + math.sin(a) * 4.3
        m.box("wheel", (x - 0.7, y - 0.7, cz), (x + 0.7, y + 0.7, cz + 1), rim)
    spoke = Paint("mc_chrome", 5, noise=0, grad=0, rim=0.3)
    m.box("wheel_spoke", (cx - 3.6, cy - 0.4, cz + 0.2), (cx + 3.6, cy + 0.4, cz + 0.8), spoke)
    m.box("wheel_spoke", (cx - 0.4, cy - 3.6, cz + 0.2), (cx + 0.4, cy, cz + 0.8), spoke)
    m.box("wheel_hub", (cx - 1.2, cy - 1.2, cz - 0.2), (cx + 1.2, cy + 1.2, cz + 0.9), Paint("mc_black", 1, noise=0))
    m.box("column", (cx - 0.7, cy - 1.8, cz - 6), (cx + 0.7, cy - 0.4, cz - 0.2), Paint(TR, 1, noise=0))

    # the console and the pistol-grip shifter
    m.box("console", (-3.5, FLOOR, -25), (3.5, 13, 8), Paint(TR, 1, noise=0, grad=0, rim=0.4,
          decal=lambda c: tone("mc_chrome", 0.75) if c.face == "up" and abs(c.p[0]) > 2.5 else tone(TR, 0.4 if c.face == "up" else 0.25)))
    m.box("shift_boot", (-1.5, 13, -10), (1.5, 13.6, -6), Paint("mc_black", 0, noise=0))
    m.box("shifter", (-0.4, 13.6, -8.4), (0.4, 18, -7.6), _chrome())
    wood = Paint("mc_wood", 2, noise=0, grad=0, rim=0.5,
                 decal=lambda c: tone("mc_wood", 0.7 if math.floor(c.p[1] * 2 + c.p[2]) % 3 else 0.3))
    m.box("pistol_grip", (-1, 17, -9.5), (1, 22, -6.5), wood, rotation=("x", -22.5, (0, 17, -8)))
    m.box("pistol_trigger", (-0.5, 16.5, -10.4), (0.5, 19, -9.6), _chrome())


def _door_parts(kit, ctx):
    """On each door: a bullet mirror, a flush chrome handle, an arm rest on the card."""
    C, TR, doors = ctx["C"], ctx["TR"], ctx["doors"]
    for side, part in ((-1, "door_l"), (1, "door_r")):
        d = kit[part]
        cells = {(abs(p[0]) - (1 if p[0] < 0 else 0), p[1], p[2]): v for p, v in doors[side].items()}
        # the mirror: a body-coloured bullet on a short chrome stalk near the screen's foot
        z = D0 + 5
        y = _top_y(cells, int(plan(z)) - 2, z) or 31
        x = plan(z) - 2.5
        lo, hi = sorted((side * x, side * (x + 3)))
        d.box("mirror_arm", (lo, y, z + 1), (hi, y + 1.2, z + 2.2), CHROME)
        lo, hi = sorted((side * (x + 2.2), side * (x + 6)))

        def mirror(c):
            if c.face == "south":
                if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
                    return tone(C, 0.4)
                return ("raw", (190, 206, 228, 255)) if (c.u + c.v) % 3 else ("raw", (240, 246, 255, 255))
            if c.face == "up":
                return tone(C, 0.9)
            if c.face == "down":
                return tone(C, 0.2)
            return tone(C, 0.6)
        d.box("mirror", (lo, y + 0.8, z), (hi, y + 3.6, z + 4), Paint(C, 4, noise=0, grad=0, rim=0, shade=False, decal=mirror))
        # the handle: a flush chrome bar under the beltline, near the door's back
        for yy in (26,):
            xo = _outer_x(cells, yy, D1 - 7)
            if xo is None:
                continue
            lo, hi = sorted((side * (xo - 0.2), side * (xo + 0.4)))
            d.box("handle", (lo, yy, D1 - 10), (hi, yy + 1.2, D1 - 4), _chrome())
        # the door card's arm rest
        xi = plan(D0 + 20) - 3
        lo, hi = sorted((side * (xi - 2), side * xi))
        d.box("arm_rest", (lo, 16, D0 + 8), (hi, 18, D1 - 4), Paint(TR, 2, noise=0, rim=0.4))


def _side_details(m, ctx):
    """The racing fuel filler on the right rear quarter."""
    body = ctx["body"]
    z, y = 52, 26
    xo = _outer_x(body, y, z)
    if xo is not None:
        def cap(c):
            if c.face == "east":
                r = math.hypot(c.p[1] - (y + 1.5), c.p[2] - (z + 1.5))
                return tone("mc_chrome", 1.0 if r < 0.8 else 0.75 if r < 1.5 else 0.5)
            return tone("mc_chrome", 0.6)
        cyl(m, "filler", (y + 1.5, z + 1.5), 4, xo - 0.2, xo + 0.6, Paint("mc_chrome", 5, noise=0, rim=0.4, decal=cap), axis="x")


def _part(name):
    return lambda: muscle_kit().all()[name]


# ================================================================================================
# The wheel: drawn at twice its size (26 px = 0.81 block across, 12 px = 0.375 wide), shrunk by 2
# ================================================================================================

def wheel_muscle():
    """A fat tyre round a dark five-split-spoke rim with a bright machined lip, a big drilled disc and an orange
    calliper behind the spokes."""
    m = BModel("wheel_muscle", shift=(8, 8, 8), floor=None, scale=2.0)
    d, width = 26, 12
    r = d / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            if rr > r - 1.0:
                return tone("mc_black", 0.0)
            if abs(rr - (r - 2.6)) < 0.5:
                return tone("mc_black", 0.42)                      # a raised ring round the sidewall
            return tone("mc_black", 0.18 if rr > r - 3.6 else 0.26)
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.0)
        x = c.p[0]
        if abs(x) < 1.0:
            return tone("mc_black", 0.0)                           # the centre groove
        return tone("mc_black", 0.05 if k % 3 == 0 else 0.22)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, Paint("mc_black", 1, noise=0, rim=0.2, decal=tire), axis="x")
    rim_d = 19

    def face(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        rc = rim_d / 2
        if c.face not in ("east", "west"):
            return tone("mc_rim", 0.4)
        if rr > rc - 1:
            return tone("mc_chrome", 0.95 if y > 0 else 0.7)       # the machined lip
        if rr < 1.8:
            return tone("mc_chrome", 0.9) if rr < 0.9 else tone("mc_rim", 0.1)   # the centre cap
        a = math.atan2(z, y)
        t = (a * 5 / (2 * math.pi)) % 1.0
        # five spokes, each split in two arms that open towards the lip
        half = 0.035 + 0.075 * (rr / rc)
        arm = 0.07
        on = abs(t - 0.5 - half) < arm or abs(t - 0.5 + half) < arm or rr < 3.2
        if on:
            lit = y > 0
            edge = abs(abs(t - 0.5) - half) > arm - 0.035
            if edge and rr > 4:
                return tone("mc_chrome", 0.62 if lit else 0.42)   # the spoke's machined edge
            return tone("mc_rim", 0.38 if lit else 0.22)
        # between the spokes: the drilled disc, and the calliper over its top
        if rr > rc - 4.4 and y > 1 and abs(z) < rc * 0.6:
            return tone("mc_caliper", 0.6)
        if rr > 3.4:
            return tone("mc_rim", 0.25) if (math.floor(rr * 2) + math.floor(a * 7)) % 3 else tone("mc_black", 0.0)
        return tone("mc_black", 0.0)
    p = Paint("mc_rim", 3, noise=0, rim=0.2, ao=0.3, decal=face)
    w2 = width / 2
    cyl(m, "hub", (0, 0), rim_d, w2 - 0.5, w2 + 0.4, p, axis="x")
    cyl(m, "hub", (0, 0), rim_d, -w2 - 0.4, -w2 + 0.5, p, axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


ALL = {
    "muscle_car": muscle_car,
    "wheel_muscle": wheel_muscle,
    "muscle_car_door_l": _part("muscle_car_door_l"),
    "muscle_car_door_r": _part("muscle_car_door_r"),
}
