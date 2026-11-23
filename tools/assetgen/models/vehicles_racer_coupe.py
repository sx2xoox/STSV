"""
미완의 질주 (content/stsv/items/vehicles.yml racer_coupe): the car of 한도윤, the dead street racer of the hidden secret
폐차고의 주인 (economy secret/garage). Never sold: the secret shows it in his garage, lets the player drive it once
beside his ghost, and hands one over at the end, bound to the player, in the look he drove it in.

An original drawing with the flavour of a 1990s Korean street racer's car (no make, no badge, no real car's lines): a
low two-door liftback coupe that was never finished. Slim rectangular headlamps with amber corners over a deep bumper
(the intercooler showing in its mouth, two yellow fog lamps, a red tow hook, a black lip), a vented hood held by two
pins, bolted-on over-fenders round all four wheels, black skirts, the race number 07 on a board on each door, a long
raked screen under a dark sun strip, a fastback hatch, a wide tail lamp band, a duck tail and over it a big wing on
two alloy uprights, one fat exhaust can with a burnt tip. Inside (the glass is only lightly tinted so it shows): two
bucket seats with red harnesses, a white roll cage (a hoop behind the seats, a harness bar, two stays down into the
hatch, a bar along each door), a bare floor, a small wheel, a tall gear lever, a rev counter on the dash; a lucky
charm hangs from the mirror, a small photograph leans on the dash in front of the empty seat, and his toolbox still
lies in the hatch under a strut brace.

Its own colour is a worn primer grey (rc_primer): flat, with a red oxide patch on a fender and the roof, filler
sanded smooth on the hood and a door, and sanding scratches. It was waiting for its paint: in any paint of the tuning
shop (vehicle_paints.py: every paint differs from primer, so all of them are offered, purple too) the patches are gone
and the paint shines. The wheels it stands on are plain black steel ones (wheel_racer); the tuning shop's designs
replace them (wheel_racer_w_<design>).

Drawn like the 2026-11 donation cars and the black sedan (vehicles_agent_sedan.py, whose panel helpers it uses): 16 px
a block, the body at its real size shrunk by 2 (Kit scale 2) and shown at vehicle.body-scale 2; every slope a flat
panel turned 22.5 or 45 degrees; the detail painted on the big faces in world space; only what should stand out is
real 3D (lamps, flares, wing, exhaust, mirrors, the cabin's things).

Drawn coordinates: 16 px a block, the car's ground centre at (0, 0, 0), front = north (-z), +x = the car's RIGHT side;
the driver sits on the left (-x). About 4.5 x 2.1 x 1.5 blocks.

Its parts: door_l, door_r (each hinged at its front edge, swinging out about a vertical axis: vehicles.yml movers).
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, h3
from stsv_assets.palette import MATERIALS, PALETTE, REMAP, build_ramp
from stsv_assets import presets
from models import vehicles as _vehicles  # noqa: F401 (vehicles_premium is imported through it)
from models.vehicles_premium import Kit
from models.vehicles_agent_sedan import CLEAR, _held_display, _hi, _inner, _lo, _x, plank, raw, smooth, tone


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("rc_primer", 85, 0.012, 0.62, 0.22, 8, False)    # worn primer grey: warm, flat (a short ramp: nothing shines)
_ramp("rc_oxide", 35, 0.085, 0.43, 0.14, 4, False)     # red oxide primer under it
_ramp("rc_filler", 95, 0.030, 0.76, 0.10, 3, False)    # body filler, sanded
_ramp("rc_trim", 260, 0.008, 0.25, 0.12, 4, False)     # black plastic and rubber
_ramp("rc_metal", 235, 0.012, 0.72, 0.52, 8, False)    # bare alloy, chrome
_ramp("rc_seat", 265, 0.018, 0.30, 0.16, 5, False)     # the buckets' cloth
_ramp("rc_red", 25, 0.185, 0.54, 0.30, 5, True)        # harnesses, the tow hook, the toolbox
_ramp("rc_cage", 240, 0.010, 0.82, 0.26, 5, False)     # the roll cage's white paint
_ramp("rc_amber", 68, 0.165, 0.74, 0.24, 4, True)      # turn signals, fog lamps
_ramp("rc_tail", 22, 0.225, 0.48, 0.34, 5, True)       # tail lamps
_ramp("rc_steel", 255, 0.010, 0.36, 0.26, 5, False)    # the black steel wheels

PRIMER = "rc_primer"
LIGHT_PAINTS = ("car_white", "pearl", "corn")           # paints a white number board would not show on

# ================================================================================================
# Shape (drawn px, 16 a block)
# ================================================================================================

T = math.tan(math.radians(22.5))
NOSE, TAIL = -35, 37           # z of the nose's face and the tail's
W = 15                         # the body's half width (the flares stand FLARE proud of it)
GROUND, FLOOR = 1.5, 2         # the body's bottom, the cabin floor
SILL = 4                       # the sills' top (the doors stand on them)
SIDE, BELT = 13, 14            # the flank's top (a 45 degree crease from here up to BELT): the hood, the deck, the belt
GLASS_TOP, ROOF = 23, 24       # the side glass's top (the headliner), the roof's top
CAB = 12                       # the doors' inner faces; the side glass from CAB to CAB + 0.5
WF, WR = -22, 23               # front / rear axle (z)
WY, WRAD = 6, 6                # wheel centre height, its radius (a 0.75 block wheel)
ARCH, ARCH_TOP, CHAMF = 7, 12.5, 3.0
TRACK = 10.5                   # the wells' inner walls (x): a 5 px wheel from 11.25 to 16.25 (its middle at 13.75)
FLARE = 1.5                    # the bolted-on over-fenders
BROW = 2.5                     # the nose's 45 degree brow: from (NOSE, BELT - BROW) to (NOSE + BROW, BELT)
ZS0 = -13                      # the screen's foot (at BELT)
KNEE = ZS0 + 5                 # where the screen bends from 45 to 22.5 degrees
ZR0 = KNEE + (ROOF - (BELT + 5)) / T          # the screen's top (the roof's front edge)
ZR1 = 13                       # the roof's back edge (the hatch glass falls from here)
RKNEE = ZR1 + (ROOF - (BELT + 5)) / T         # where the hatch glass bends from 22.5 to 45 degrees
ZD = RKNEE + 5                 # the hatch glass's foot on the deck (at BELT)
D0, D1 = ZS0, 9                # the door (z): one long door a side, from the screen's foot
HATCH = 20                     # the hatch floor's front edge (behind the seats and the hoop)
LAMP = (8.5, 14.25, 9.0, 11.25)       # the headlamp: |x| from, to; y from, to (its face at NOSE - 0.4)
TAILLAMP = (7.0, 14.25, 9.5, 12.5)    # the tail lamp (its face at TAIL + 0.4)
SEAT_X, SEAT_Z, SEAT_Y = 5.76, 6, 2.56   # the seats (vehicles.yml seats at [0.36, 0.16, -0.375])
BOARD = (-9, 5)                # the number board on a door: its first column (z), its bottom row (y); 11 x 8 px
HOOP = 11                      # the roll hoop's front face (z)
EXHAUST = (8.5, 3.6)           # the exhaust can's middle (x, y)

DIGITS = {"0": (".##.", "#..#", "#..#", "#..#", "#..#", ".##."),
          "7": ("####", "...#", "..#.", "..#.", ".#..", ".#..")}


def screen_y(z):
    """The screen's surface (y) at z (45 degrees to the knee, then 22.5 to the roof)."""
    if z < KNEE:
        return BELT + (z - ZS0)
    return min(ROOF, BELT + 5 + (z - KNEE) * T)


def rear_y(z):
    """The hatch glass's surface (y) at z (22.5 degrees from the roof to its knee, then 45 to the deck)."""
    if z < RKNEE:
        return min(ROOF, ROOF - (z - ZR1) * T)
    return max(BELT, BELT + 5 - (z - RKNEE))


def arch_at(z):
    for wz in (WF, WR):
        if abs(z - wz) < ARCH + 0.01:
            return wz
    return None


def in_arch(z, y):
    """Inside an arch's opening (seen from the side)."""
    wz = arch_at(z)
    if wz is None or y >= ARCH_TOP:
        return False
    d = abs(z - wz)
    return y < ARCH_TOP - CHAMF + (ARCH - d) if d > ARCH - CHAMF else True


def number(side, y, z):
    """The door's number board at (y, z) on one side (-1: the left): None off it, True on a digit's ink, False on the
    board. Read from outside on both sides: 07."""
    col = math.floor(z) - BOARD[0]
    if side > 0:
        col = 10 - col
    row = math.floor(y) - BOARD[1]
    if not (0 <= col <= 10 and 0 <= row <= 7):
        return None
    for k, digit in enumerate("07"):
        dc, dr = col - 1 - 5 * k, 6 - row
        if 0 <= dc < 4 and 0 <= dr < 6:
            return DIGITS[digit][dr][dc] == "#"
    return False


def _blob(a, b, ca, cb, ra, rb, seed):
    """Inside a ragged patch round (ca, cb) (a sanded edge: the radius wavers from texel to texel)."""
    d = ((a - ca) / ra) ** 2 + ((b - cb) / rb) ** 2
    return d < 0.72 + 0.5 * h3(math.floor(a), math.floor(b), seed, 7)


# ================================================================================================
# Paints
# ================================================================================================

def _make_paints(C):
    """The coupe's paints for body colour C (PRIMER: the worn stock look, anything else a finished paint)."""
    stock = REMAP.get(C, C) == PRIMER
    seams = (D0, D1 - 1)
    lit = 0.0 if stock else 0.12            # a finished paint's highlights stand out more than flat primer's
    dark_board = REMAP.get(C, C) in LIGHT_PAINTS

    def worn_side(x, y, z):
        """Primer only: what shows through on the flanks."""
        if x < 0 and _blob(z, y, -27.5, 9.5, 4.2, 3.4, 3):
            return tone("rc_oxide", 0.55 if y > 9 else 0.35)              # red oxide on the left front fender
        if x > 0 and _blob(z, y, 31.5, 8.5, 3.8, 3.6, 5):
            return tone("rc_oxide", 0.5 if y > 8 else 0.3)                # and on the right rear corner
        if x > 0 and _blob(z, y, 4.5, 6.5, 3.6, 2.2, 9) and number(1, y, z) is None:
            return tone("rc_filler", 0.6)                                 # filler low on the right door
        if x < 0 and _blob(z, y, 13.5, 9.0, 2.6, 2.8, 11):
            return tone("rc_filler", 0.5)                                 # and on the left quarter
        if (math.floor(z) + 2 * math.floor(y)) % 13 == 0 and h3(math.floor(z), math.floor(y), 1 if x < 0 else 2, 21) < 0.45:
            return tone(C, 0.72)                                          # sanding scratches
        return None

    def side_colour(x, y, z):
        """The flanks: a soft gradient, light under the crease, the door seams, the arch lips, the number board."""
        fz, fy = math.floor(z), math.floor(y)
        s = -1 if x < 0 else 1
        if y < SILL and arch_at(z) is None:
            return tone(C, 0.16 if y < GROUND + 1 else 0.26)              # the sill, its foot in shadow
        wz = arch_at(z)
        if wz is not None and not in_arch(z, y):
            if any(in_arch(z + dz, y + dy) for dz, dy in ((0, -1), (-1, 0), (1, 0), (-1, -1), (1, -1))):
                return tone("rc_trim", 0.2)                               # the arch's lip
        n = number(s, y, z) if D0 <= z < D1 else None
        if n is not None:
            if dark_board:
                return tone("rc_cage", 0.9) if n else tone("rc_trim", 0.15)
            return tone("rc_trim", 0.05) if n else tone("rc_cage", 0.92 if fy > BOARD[1] + 3 else 0.78)
        if fz in seams and SILL - 0.5 < y < SIDE:
            return tone(C, 0.04)                                          # the door seams
        if stock:
            got = worn_side(x, y, z)
            if got is not None:
                return got
        if fy == SIDE - 1:
            return tone(C, 0.74 + lit)                                    # light under the crease
        if 23 <= fz <= 25 and 10 <= fy <= 11 and x < 0 and math.hypot(z - 24.5, y - 11) < 1.6:
            return tone("rc_trim", 0.35)                                  # the fuel cap (left rear quarter)
        return tone(C, 0.22 + 0.3 * smooth((y - 4) / 9))

    def worn_top(x, y, z):
        if y > ROOF - 1:
            if _blob(x, z, 4.5, 8.5, 4.0, 3.2, 13):
                return tone("rc_oxide", 0.75)                             # a red oxide patch on the roof
            return None
        if z < ZS0 and _blob(x, z, -7.0, -24.0, 5.2, 4.2, 15):
            return tone("rc_filler", 0.9)                                 # filler sanded smooth on the hood
        if z > ZD and _blob(x, z, 6.0, 33.5, 4.0, 2.0, 17):
            return tone("rc_filler", 0.8)
        if (math.floor(x) + 2 * math.floor(z)) % 11 == 0 and h3(math.floor(x), math.floor(z), 4, 23) < 0.4:
            return tone(C, 0.5)                                           # sanding scratches, dull
        return None

    def top_colour(x, y, z, ax):
        """Hood, deck, roof: lit from above, a band of sky down each, shut lines; the hood's vents and pins."""
        fx, fz = math.floor(x), math.floor(z)
        if ZS0 <= z <= ZD and ax < CAB and y < ROOF - 1:
            return tone("rc_trim", 0.3 if fz % 3 else 0.2)                # the hatch's carpet under the glass
        if stock:
            got = worn_top(x, y, z)
            if got is not None:
                return got
        if y > ROOF - 1:
            if 3 <= ax < 6:
                return tone(C, 0.8 + lit)                                 # the sky's band down the roof
            return tone(C, 0.64)
        if z < ZS0:
            if ZS0 - 1 <= z and ax < CAB:
                return tone("rc_trim", 0.1)                               # the cowl
            if 5 <= ax < 9 and -25 <= fz <= -19:
                return tone("rc_trim", 0.0) if fz % 2 else tone(C, 0.36)  # two slatted vents
            if fz == NOSE + BROW + 1 and math.floor(ax) == 9:
                return tone("rc_metal", 0.95)                             # the hood pins
            if fz == NOSE + BROW + 2 and math.floor(ax) == 9:
                return tone("rc_trim", 0.1)
            if math.floor(ax) == 12:
                return tone(C, 0.26)                                      # the hood's shut lines
            if ax < 3:
                return tone(C, 0.76 + lit)                                # the bulge down its middle
            if math.floor(ax) == 3:
                return tone(C, 0.44)
            return tone(C, 0.6)
        if z > ZD:
            if fz == math.floor(ZD) + 1 and ax < 12:
                return tone(C, 0.28)                                      # the hatch's shut line
            if math.floor(ax) == 12 and z < TAIL - 1:
                return tone(C, 0.28)
            return tone(C, 0.6)
        if 3 <= ax < 6:
            return tone(C, 0.74 + lit)
        return tone(C, 0.6)

    def body_decal(c):
        x, y, z = c.p
        ax = abs(x)
        f = c.face
        if f == "down":
            if y > GLASS_TOP - 0.6:
                return tone("rc_seat", 0.7)                               # the headliner
            return tone("rc_trim", 0.0)
        if arch_at(z) is not None and y < ARCH_TOP + 0.01 and f in ("east", "west", "north", "south"):
            if ax < W - 0.5 or in_arch(z, y) or f in ("north", "south"):
                return tone("rc_trim", 0.0)                               # the wheel wells
        cabin = ZS0 - 0.1 < z < ZD and ax < CAB + 0.1
        if f == "up":
            if cabin and y < BELT - 0.5:
                # a stripped floor: bare painted metal, pressed ribs along it
                return tone(C, 0.12 if math.floor(ax) % 4 == 2 else 0.24)
            return top_colour(x, y, z, ax)
        if f in ("east", "west"):
            if _inner(c) and ax < CAB + 0.6:
                # the door cards: a bare black panel, a red pull strap
                if math.floor(y) == 10 and -4 <= z < 3:
                    return tone("rc_red", 0.55)
                if y > 12.5:
                    return tone("rc_trim", 0.45)
                return tone("rc_trim", 0.25 if math.floor(z) % 5 else 0.12)
            if _inner(c):
                return tone("rc_trim", 0.05)
            return side_colour(x, y, z)
        if f == "north":
            if cabin and y < BELT:
                return tone("rc_trim", 0.15)
            return tone(C, 0.3 + 0.2 * smooth((y - 4) / 10))
        if f == "south":
            if cabin and y < BELT:
                return tone("rc_trim", 0.3)
            return tone(C, 0.26 + 0.18 * smooth((y - 4) / 10))
        return None

    def chamfer_decal(c):
        """The shoulder's 45 degree crease: one bright line (dull on primer), the door seams across it."""
        if c.face != "up":
            return body_decal(c)
        if math.floor(c.p[2]) in seams:
            return tone(C, 0.1)
        return tone(C, 0.8 if stock else 0.95)

    def bright(c):
        if c.face == "up":
            return tone(C, 0.78 + lit)
        return body_decal(c)

    flat = dict(noise=0, grad=0, rim=0, ao=0)
    return dict(
        body=Paint(C, 2, **flat, decal=body_decal),
        angled=Paint(C, 2, **flat),
        body_decal=body_decal, chamfer_decal=chamfer_decal, bright=bright, stock=stock, lit=lit,
    )


def _metal(lit=0.95, side=0.7, low=0.4):
    """A bare alloy or chrome part: bright on top, a sky band over a dark ground reflection down its sides."""
    def decal(c):
        if c.face == "up":
            return tone("rc_metal", lit)
        if c.face == "down":
            return tone("rc_metal", 0.15)
        if c.hi[1] - c.lo[1] >= 1.5:
            return tone("rc_metal", side if c.p[1] > (c.lo[1] + c.hi[1]) / 2 else low)
        return tone("rc_metal", side)
    return Paint("rc_metal", 5, noise=0, grad=0, rim=0.4, ao=0.2, decal=decal)


METAL = _metal()
BLACK = Paint("rc_trim", 1, noise=0, grad=0, rim=0.35, ao=0.4,
              decal=lambda c: tone("rc_trim", 0.8) if c.face == "up" else tone("rc_trim", 0.0) if c.face == "down" else tone("rc_trim", 0.32))
CAGE = Paint("rc_cage", 3, noise=0, grad=0, rim=0.2, ao=0.2,
             decal=lambda c: tone("rc_cage", 1.0) if c.face == "up" else tone("rc_cage", 0.35) if c.face == "down" else tone("rc_cage", 0.75))


def _glass(frame, inside=56):
    """The lightly tinted glass, painted in world space: frame(c) -> None (glass) or a colour (pillars, seals). Seen
    from outside it is see-through enough to show the cage and the seats (a band of sky across it); from inside
    lighter still."""
    def d(c):
        x, y, z = c.p
        got = frame(c)
        if got is not None:
            return got if got[0] == "raw" else raw(*got)
        if _inner(c):
            return ("raw", (20, 24, 30, inside))
        if 19.5 <= y < 20.25:
            return ("raw", (150, 166, 190, 150))                          # the horizon's reflection: one clean line
        if y >= 20.25:
            return ("raw", (58, 68, 86, 128))                             # the sky over it
        return ("raw", (24, 28, 36, 118))
    return d


# ================================================================================================
# The car
# ================================================================================================

_KIT = {}
PARTS = ("door_l", "door_r")


def racer_coupe(colour=PRIMER, name="racer_coupe"):
    return coupe_kit(colour, name).body


def coupe_kit(colour=PRIMER, name="racer_coupe"):
    key = (colour, name, tuple(sorted(REMAP.items())))
    if key in _KIT:
        return _KIT[key]
    kit = Kit(name, PARTS, cabin=(0, 19, 4))      # the riders' eyes: one-sided glass
    m = kit.body
    m.group("body")
    ctx = dict(C=colour, P=_make_paints(colour))
    _shell(m, kit, ctx)
    _flares(m, ctx)
    _front(m, ctx)
    _rear(m, ctx)
    _wing(m, ctx)
    _glasshouse(m, kit, ctx)
    _cabin(m, ctx)
    _cage(m)
    _door_parts(kit, ctx)
    _skirts(m)
    m.display = _held_display()
    _KIT[key] = kit
    return kit


def _door(kit, s):
    return kit["door_l" if s < 0 else "door_r"]


def _shell(m, kit, ctx):
    """The body's boxes: the nose and the engine bay under the hood, the floor and the wells, the flanks round the
    arches, the two doors, the hatch's floor; the crease along the shoulders, the nose's brow, the four bevelled
    corners, the arches' corners."""
    P = ctx["P"]
    body, ang, bd = P["body"], P["angled"], P["body_decal"]
    box = m.box
    # --- the nose (behind the fascia), the engine bay between the front wheels, the hood over them
    box("nose", (-CAB, GROUND, NOSE + 1), (CAB, BELT - BROW, WF - ARCH), body)
    box("bay", (-(TRACK - 1), GROUND, WF - ARCH), (TRACK - 1, SIDE, ZS0), body)
    box("hood", (-(W - 1), SIDE, NOSE + BROW), (W - 1, BELT, ZS0), body)
    box("nose_top", (-CAB, BELT - BROW, NOSE + BROW), (CAB, SIDE, WF - ARCH), body)
    # --- the cabin's floor, the hatch's floor behind the hoop
    box("floor", (-(TRACK - 1), GROUND, ZS0), (TRACK - 1, FLOOR, TAIL - 1), body)
    box("boot", (-(TRACK - 1), FLOOR, HATCH), (TRACK - 1, BELT, TAIL - 1), body)
    for s in (-1, 1):
        box("floor", _lo(s, TRACK - 1, CAB, GROUND, WF + ARCH), _hi(s, TRACK - 1, CAB, FLOOR, WR - ARCH), body)
        box("boot_side", _lo(s, TRACK - 1, CAB, ARCH_TOP, HATCH), _hi(s, TRACK - 1, CAB, BELT, TAIL - 1), body)
        # the wheel wells' inner walls and roofs (the cabin must not show through the arches)
        for wz in (WF, WR):
            box("well", _lo(s, TRACK - 1, TRACK - 0.5, GROUND, wz - ARCH), _hi(s, TRACK - 1, TRACK - 0.5, ARCH_TOP, wz + ARCH), body)
            box("well_top", _lo(s, TRACK - 0.5, CAB, ARCH_TOP - 0.5, wz - ARCH), _hi(s, TRACK - 0.5, CAB, ARCH_TOP, wz + ARCH), body)
            box("well_end", _lo(s, TRACK - 0.5, CAB, GROUND, wz - ARCH), _hi(s, TRACK - 0.5, CAB, ARCH_TOP - CHAMF, wz - ARCH + 0.5), body)
            box("well_end", _lo(s, TRACK - 0.5, CAB, GROUND, wz + ARCH - 0.5), _hi(s, TRACK - 0.5, CAB, ARCH_TOP - CHAMF, wz + ARCH), body)
    # --- the flanks
    lip = Paint("rc_trim", 1, noise=0, grad=0, rim=0, ao=0,
                decal=lambda c: tone("rc_trim", 0.0) if c.face == "down" else tone("rc_trim", 0.25))
    for s in (-1, 1):
        door = _door(kit, s)
        box("fender", _lo(s, CAB, W, GROUND, NOSE + 1), _hi(s, CAB, W, SIDE, WF - ARCH), body)
        box("arch_top", _lo(s, CAB, W, ARCH_TOP, WF - ARCH), _hi(s, CAB, W, SIDE, WF + ARCH), body)
        box("sill", _lo(s, CAB, W, GROUND, WF + ARCH), _hi(s, CAB, W, SILL, WR - ARCH), body)
        box("fender_tail", _lo(s, CAB, W, SILL, WF + ARCH), _hi(s, CAB, W, SIDE, D0), body)
        door.box("door", _lo(s, CAB, W, SILL, D0), _hi(s, CAB, W, SIDE, D1), body)
        box("quarter", _lo(s, CAB, W, SILL, D1), _hi(s, CAB, W, SIDE, WR - ARCH), body)
        box("arch_top", _lo(s, CAB, W, ARCH_TOP, WR - ARCH), _hi(s, CAB, W, SIDE, WR + ARCH), body)
        box("rear_side", _lo(s, CAB, W, GROUND, WR + ARCH), _hi(s, CAB, W, SIDE, TAIL - 1), body)
        # the arches' 45 degree corners (filled in steps a hair inside the flank, a slim panel along the edge)
        for wz in (WF, WR):
            for e in (-1, 1):
                z0 = wz + e * ARCH
                z1 = wz + e * (ARCH - CHAMF)
                for k in range(int(CHAMF) - 1):
                    za, zb = sorted((z0 - e * k, z0 - e * (k + 1)))
                    box("arch_step", _lo(s, W - 2, W - 0.05, ARCH_TOP - CHAMF + k + 1, za),
                        _hi(s, W - 2, W - 0.05, ARCH_TOP, zb), body)
                plank(m, "arch_corner", "x", (z0, ARCH_TOP - CHAMF), (z1, ARCH_TOP), _x(s, CAB, W + 0.02),
                      0.75, lip, below=False)
    # --- the shoulder (the glass stands on it) and its 45 degree crease, split at the doors' seams
    cd = P["chamfer_decal"]
    for s in (-1, 1):
        door = _door(kit, s)
        for target, z0, z1 in ((m, NOSE + BROW, D0), (door, D0, D1), (m, D1, TAIL - 1)):
            plank(target, "crease", "z", (s * (W - 1), BELT), (s * W, SIDE), (z0, z1), 0.75, ang, decal=cd)
        for target, z0, z1 in ((door, D0, D1), (m, D1, TAIL - 1)):
            target.box("shoulder", _lo(s, CAB, W - 1, SIDE, z0), _hi(s, CAB, W - 1, BELT, z1), body)
    # the nose's brow: a 45 degree bevel along the front of the hood
    plank(m, "brow", "x", (NOSE, BELT - BROW), (NOSE + BROW, BELT), (-W + 0.02, W - 0.02), 1.8, ang, decal=P["bright"])
    # the four vertical corners: a 45 degree bevel each (1 px)
    for s in (-1, 1):
        plank(m, "corner_f", "y", (NOSE, s * (W - 1)), (NOSE + 1, s * W), (GROUND, BELT - BROW), 0.75, ang, decal=bd,
              below=s > 0)
        plank(m, "corner_r", "y", (TAIL - 1, s * W), (TAIL, s * (W - 1)), (GROUND, SIDE), 0.75, ang, decal=bd,
              below=s > 0)


def _flares(m, ctx):
    """The bolted-on over-fenders: a band standing FLARE proud of the flank round each arch (a flat top over the
    wheel, a 45 degree corner each side, a leg down each end), a row of rivets along it."""
    C, P = ctx["C"], ctx["P"]
    lit = P["lit"]

    def flare(c):
        x, y, z = c.p
        fy, fz = math.floor(y), math.floor(z)
        if c.face == "up":
            return tone(C, 0.82 + lit)
        if c.face == "down":
            return tone("rc_trim", 0.0)
        if c.face in ("east", "west"):
            wz = WF if z < 0 else WR
            if abs(z - wz) < ARCH - CHAMF + 0.6:
                if fy == 13 and fz % 3 == 0:
                    return tone("rc_trim", 0.1)                           # rivets along the top
                return tone(C, 0.66 if y > 13.25 else 0.46)
            if fy % 3 == 0 and fy < 9:
                return tone("rc_trim", 0.1)                               # and down the legs
            return tone(C, 0.32 + 0.14 * smooth((y - 2) / 8))
        return tone(C, 0.3)
    fp = Paint(C, 2, noise=0, grad=0, rim=0, ao=0, decal=flare)
    corner = Paint(C, 2, noise=0, grad=0, rim=0, ao=0,
                   decal=lambda c: tone(C, 0.72 + lit) if c.face == "up" else tone("rc_trim", 0.0) if c.face == "down" else tone(C, 0.5))
    for s in (-1, 1):
        for wz in (WF, WR):
            flat = ARCH - CHAMF
            m.box("flare_top", _lo(s, W, W + FLARE, ARCH_TOP, wz - flat - 0.6), _hi(s, W, W + FLARE, ARCH_TOP + 1.5, wz + flat + 0.6), fp)
            for e in (-1, 1):
                za, zb = sorted((wz + e * ARCH, wz + e * (ARCH + 1.5)))
                m.box("flare_leg", _lo(s, W, W + FLARE, GROUND + 0.5, za), _hi(s, W, W + FLARE, ARCH_TOP - CHAMF + 0.4, zb), fp)
                plank(m, "flare_corner", "x", (wz + e * ARCH, ARCH_TOP - CHAMF), (wz + e * flat, ARCH_TOP), _x(s, W, W + FLARE),
                      1.5, corner, below=False)


def _front(m, ctx):
    """The fascia: slim rectangular headlamps with amber corners, a thin slot between them, a deep bumper (the
    intercooler in its wide mouth, a round yellow fog lamp each side, a small plate off to one side), a red tow hook,
    a black lip."""
    C = ctx["C"]
    bd = ctx["P"]["body_decal"]
    x0, x1, y0, y1 = LAMP

    def fascia(c):
        x, y, z = c.p
        ax, fx, fy = abs(x), math.floor(x), math.floor(y)
        if c.face != "north":
            return bd(c)
        if y < GROUND + 0.75:
            return tone("rc_trim", 0.15)
        if 2.5 <= y < 6.5 and ax < 9:
            # the mouth: the intercooler's fins between two end tanks, a dark frame
            if fy in (2, 6) and (y < 2.75 or y >= 6.25):
                return tone("rc_trim", 0.0)
            if ax >= 7.5:
                return tone("rc_metal", 0.34)
            if y < 3 or y >= 6:
                return tone("rc_trim", 0.0)
            return tone("rc_metal", 0.5) if fx % 2 == 0 else tone("rc_metal", 0.14)
        if 2.5 <= y < 6.5 and 9 <= ax < 9.5:
            return tone("rc_trim", 0.0)
        if math.hypot(ax - 12, y - 4.5) < 1.45:
            return tone("rc_amber", 0.95 if y > 4.5 else 0.6)             # the fog lamps
        if math.hypot(ax - 12, y - 4.5) < 2.0:
            return tone("rc_trim", 0.05)
        if 7 <= y < 8.5 and -13 <= x < -8:
            if fy == 7 and y < 7.25 or x < -12.75 or x >= -8.25:
                return tone("rc_trim", 0.3)
            return tone("rc_cage", 0.3 if fx in (-12, -10) and y < 8 else 0.95)   # a small plate, off to the left
        if y0 + 0.5 <= y < y1 - 0.25 and ax < x0 - 1:
            return tone("rc_trim", 0.0) if fx % 2 else tone("rc_trim", 0.4)       # the slot between the lamps
        if y0 - 0.5 <= y < y1 + 0.25 and ax < x1 + 0.5:
            return tone("rc_trim", 0.1)                                   # the lamps' dark surround
        if fy == 6 or (fy == 8 and ax > 13):
            return tone(C, 0.2)                                           # the bumper's seam
        return tone(C, 0.32 + 0.22 * smooth((y - 2) / 9))
    fp = Paint(C, 2, noise=0, grad=0, rim=0, ao=0, decal=fascia)
    m.box("fascia", (-(W - 1), GROUND, NOSE), (W - 1, BELT - BROW, NOSE + 1), fp)
    # the headlamps: a clear lens over two square reflectors, the amber turn signal wrapping the corner
    m.group("lights")

    def lamp(c):
        ax, y = abs(c.p[0]), c.p[1]
        if c.face != "north":
            return ("raw", (36, 38, 46, 255)) if ax < x1 - 1.5 else ("raw", (236, 150, 40, 255))
        if ax >= x1 - 1.5:
            return ("raw", (255, 176, 52, 255)) if y > y0 + 0.75 else ("raw", (206, 120, 30, 255))
        if y >= y1 - 0.5 or y < y0 + 0.5 or ax < x0 + 0.5:
            return ("raw", (52, 56, 66, 255))
        k = math.floor((ax - x0 - 0.5) / 2)
        inner = (ax - x0 - 0.5) % 2
        if inner < 0.5:
            return ("raw", (96, 104, 122, 255))
        return ("raw", (252, 250, 238, 255)) if k % 2 == 0 else ("raw", (206, 216, 232, 255))
    for s in (-1, 1):
        m.box("headlamp", _lo(s, x0, x1, y0, NOSE - 0.4), _hi(s, x0, x1, y1, NOSE),
              Paint("rc_cage", 2, noise=0, light=False, glow=True, decal=lamp))
    m.group("body")
    # the lip: a black splitter under the bumper, turned up a little at its ends
    m.box("lip", (-(W - 0.5), GROUND - 0.5, NOSE - 1.75), (W - 0.5, GROUND + 0.25, NOSE + 1), BLACK)
    for s in (-1, 1):
        m.box("lip_end", _lo(s, W - 1.5, W + 0.25, GROUND + 0.25, NOSE - 1.5), _hi(s, W - 1.5, W + 0.25, GROUND + 1.5, NOSE - 1), BLACK)
    # the tow hook (the car's right), red
    hook = Paint("rc_red", 2, noise=0, grad=0, rim=0.4, ao=0.2,
                 decal=lambda c: tone("rc_red", 0.9) if c.face == "up" else tone("rc_red", 0.55) if c.face != "down" else tone("rc_red", 0.2))
    m.box("tow_hook", (5.5, 6.75, NOSE - 1.6), (6.25, 8.25, NOSE), hook)
    m.box("tow_hook", (5.5, 8.25, NOSE - 1.6), (7.5, 8.9, NOSE - 1.0), hook)
    # wipers at the screen's foot
    wip = Paint("rc_trim", 0, noise=0, rim=0.4)
    for xa, xb in ((-11, -1.5), (1.5, 10)):
        m.box("wiper", (xa, BELT, ZS0 - 0.9), (xb, BELT + 0.4, ZS0 - 0.4), wip)


def _rear(m, ctx):
    """The tail: a band of lamp across it (red lamps with an amber and a white cell at the corners, a dark garnish
    between them), the plate's recess under it, a black valance cut away round one fat exhaust can with a burnt tip,
    a duck tail along the hatch's back edge."""
    C = ctx["C"]
    P = ctx["P"]
    bd = P["body_decal"]
    lx0, lx1, ly0, ly1 = TAILLAMP

    def panel(c):
        x, y, z = c.p
        ax, fx, fy = abs(x), math.floor(x), math.floor(y)
        if c.face != "south":
            return bd(c)
        if ly0 <= y < ly1 and ax < lx0:
            if ly0 + 0.75 <= y < ly1 - 0.75:
                return ("rc_tail", 1.0) if fx % 2 else ("rc_tail", 0.4)   # the garnish: a dark red reflector
            return tone("rc_trim", 0.1)
        if ly0 - 0.5 <= y < ly1 + 0.5 and ax < lx1 + 0.5:
            return tone("rc_trim", 0.1)
        if 5 <= y < 8.5 and ax < 5:
            if y < 5.25 or y >= 8.25 or ax >= 4.75:
                return tone("rc_trim", 0.2)
            return tone("rc_cage", 0.3 if fx in (-3, -1, 1, 2) and 6 <= y < 7.5 else 0.95)   # the plate
        if fy == 8 and ax >= 5:
            return tone(C, 0.2)                                           # the bumper's seam
        return tone(C, 0.26 + 0.22 * smooth((y - 3) / 10))
    m.box("tail", (-(W - 1), GROUND, TAIL - 1), (W - 1, BELT, TAIL), Paint(C, 2, noise=0, grad=0, rim=0, ao=0, decal=panel))
    m.group("lights")

    def lamp(c):
        ax, y = abs(c.p[0]), c.p[1]
        if c.face != "south":
            return ("raw", (104, 16, 22, 255))
        if y >= ly1 - 0.5 or y < ly0 + 0.5 or ax >= lx1 - 0.5:
            return ("raw", (120, 18, 26, 255))
        if ax < lx0 + 1.75:
            return ("raw", (236, 240, 244, 255)) if y < ly0 + 1.5 else ("raw", (250, 168, 48, 255))   # reverse, turn
        return ("raw", (240, 44, 52, 255)) if math.floor(ax) % 2 else ("raw", (196, 26, 38, 255))
    for s in (-1, 1):
        m.box("tail_lamp", _lo(s, lx0, lx1, ly0, TAIL), _hi(s, lx0, lx1, ly1, TAIL + 0.4),
              Paint("rc_tail", 2, noise=0, light=False, glow=True, decal=lamp))
    m.group("body")
    # the valance, cut away round the exhaust (the car's right)
    ex, ey = EXHAUST
    val = Paint("rc_trim", 1, noise=0, grad=0, rim=0.3,
                decal=lambda c: tone("rc_trim", 0.5) if c.face == "up" else tone("rc_trim", 0.2))
    m.box("valance", (-(W - 0.5), GROUND - 0.25, TAIL - 0.75), (ex - 2.75, GROUND + 3, TAIL + 0.75), val)
    m.box("valance", (ex + 2.75, GROUND - 0.25, TAIL - 0.75), (W - 0.5, GROUND + 3, TAIL + 0.75), val)

    # the exhaust: one fat can, its tip burnt blue and straw
    def can(c):
        x, y, z = c.p
        if c.face == "south":
            r = math.hypot(x - ex, y - ey)
            if r < 1.35:
                return ("raw", (10, 10, 14, 255))
            return tone("rc_metal", 0.82 if y > ey else 0.5)
        if z > TAIL + 2.0:
            return ("raw", (96, 110, 190, 255)) if y > ey else ("raw", (70, 74, 128, 255))     # blue at the lip
        if z > TAIL + 1.25:
            return ("raw", (206, 170, 96, 255)) if y > ey else ("raw", (150, 118, 70, 255))    # straw behind it
        return tone("rc_metal", 0.86 if y > ey + 0.6 else 0.6 if y > ey - 0.6 else 0.34)
    cyl(m, "exhaust", EXHAUST, 4.5, TAIL - 2, TAIL + 2.75, Paint("rc_metal", 5, noise=0, rim=0.2, ao=0.2, decal=can), axis="z")
    # the duck tail: a lip along the hatch's back edge
    lit = P["lit"]
    lip = Paint(C, 2, noise=0, grad=0, rim=0.3, ao=0,
                decal=lambda c: tone(C, 0.82 + lit) if c.face == "up" else tone(C, 0.3) if c.face != "down" else tone("rc_trim", 0.0))
    m.box("ducktail", (-(W - 1.5), BELT, TAIL - 2.5), (W - 1.5, BELT + 0.75, TAIL + 0.25), lip)


def _wing(m, ctx):
    """The big wing: a blade the width of the car on two drilled alloy uprights, black end plates, a gurney flap."""
    C = ctx["C"]
    lit = ctx["P"]["lit"]
    y0 = BELT + 0.75

    def upright(c):
        y, z = c.p[1], c.p[2]
        if c.face in ("east", "west") and math.floor(y) in (16, 18) and 32 <= z < 33:
            return tone("rc_trim", 0.0)                                   # lightening holes
        if c.face == "up":
            return tone("rc_metal", 0.95)
        return tone("rc_metal", 0.72 if c.face in ("east", "west") else 0.45)
    up = Paint("rc_metal", 5, noise=0, grad=0, rim=0.3, ao=0.2, decal=upright)
    for s in (-1, 1):
        m.box("wing_upright", _lo(s, 8.5, 9.25, y0 + 0.5, 31), _hi(s, 8.5, 9.25, 20.5, 34), up)
        m.box("wing_foot", _lo(s, 7.75, 10, y0, 30.5), _hi(s, 7.75, 10, y0 + 0.5, 34.5), METAL)

    def blade(c):
        x, z = c.p[0], c.p[2]
        if c.face == "up":
            if z < 31:
                return tone(C, 0.9 + lit)                                 # the leading edge catches the light
            return tone(C, 0.68)
        if c.face == "down":
            return tone("rc_trim", 0.05)
        return tone(C, 0.42)
    m.box("wing_blade", (-15.25, 20.5, 30), (15.25, 21.5, 36.5), Paint(C, 2, noise=0, grad=0, rim=0.3, ao=0, decal=blade))
    m.box("wing_gurney", (-15.25, 21.5, 36), (15.25, 22.25, 36.5), BLACK)

    def plate(c):
        y, z = c.p[1], c.p[2]
        if c.face in ("east", "west"):
            if math.floor(y) == 19:
                return tone("rc_red", 0.55)                               # one red stripe along it
            return tone("rc_trim", 0.4 if y > 21 else 0.22)
        return tone("rc_trim", 0.7 if c.face == "up" else 0.15)
    ep = Paint("rc_trim", 1, noise=0, grad=0, rim=0.3, ao=0, decal=plate)
    for s in (-1, 1):
        m.box("wing_plate", _lo(s, 15.25, 16, 18, 29.5), _hi(s, 15.25, 16, 22.75, 37), ep)


def _glasshouse(m, kit, ctx):
    """The long screen (45 then 22.5 degrees) under a dark sun strip, the roof with chamfered edges, the hatch glass
    (22.5 then 45), the frameless door glass and a quarter light behind it, the body-coloured pillars."""
    P = ctx["P"]
    C = ctx["C"]
    ang = P["angled"]
    lit = P["lit"]

    def pane(strip):
        def d(c):
            """The screen and the hatch glass: lightly tinted, black edges, one line of sky; the screen's sun strip."""
            if c.v in (0, c.nv - 1) or c.u in (0, c.nu - 1):
                return ("raw", (22, 24, 30, 255))
            if c.face == "down":
                return ("raw", (20, 24, 32, 52))                          # from inside: seen through
            y = c.p[1]
            if strip and y >= 22.1:
                return ("raw", (16, 17, 22, 255))                         # the sun strip
            if 20.0 <= y < 20.75:
                return ("raw", (150, 166, 190, 150))
            if y >= 20.75:
                return ("raw", (58, 68, 86, 128))
            return ("raw", (24, 28, 36, 112))
        return d
    gl = Paint("rc_trim", 1, noise=0, light=False)
    yk = screen_y(KNEE)
    half = CAB - 0.5
    plank(m, "screen", "x", (ZS0 + 0.25, BELT), (KNEE + 0.25, yk), (-half, half), 0.5, gl, decal=pane(True))
    plank(m, "screen", "x", (KNEE + 0.25, yk), (ZR0 + 0.25, ROOF), (-half, half), 0.5, gl, decal=pane(True))
    yr = rear_y(RKNEE)
    plank(m, "rear_glass", "x", (ZR1 - 0.25, ROOF), (RKNEE - 0.25, yr), (-half, half), 0.5, gl, decal=pane(False))
    plank(m, "rear_glass", "x", (RKNEE - 0.25, yr), (ZD - 0.25, BELT), (-half, half), 0.5, gl, decal=pane(False))
    # the pillars: body colour, following the glass's edges
    pil = Paint(C, 2, noise=0, grad=0, rim=0.3, ao=0,
                decal=lambda c: tone(C, 0.7 + lit) if c.face == "up" else tone(C, 0.32))
    for s in (-1, 1):
        plank(m, "a_pillar", "x", (ZS0, BELT), (KNEE, yk), _x(s, CAB - 0.75, CAB + 0.52), 0.9, pil)
        plank(m, "a_pillar", "x", (KNEE, yk), (ZR0, ROOF), _x(s, CAB - 0.75, CAB + 0.52), 0.9, pil)
        plank(m, "c_pillar", "x", (ZR1, ROOF), (RKNEE, yr), _x(s, CAB - 1, CAB + 0.52), 0.9, pil)
        plank(m, "c_pillar", "x", (RKNEE, yr), (ZD, BELT), _x(s, CAB - 1, CAB + 0.52), 0.9, pil)
    # the roof, its long edges chamfered at 45 degrees
    roofp = Paint(C, 2, noise=0, grad=0, rim=0.3, ao=0, decal=P["body_decal"])
    m.box("roof", (-(CAB - 1), GLASS_TOP, ZR0), (CAB - 1, ROOF, ZR1), roofp)
    for s in (-1, 1):
        plank(m, "roof_edge", "z", (s * (CAB - 1), ROOF), (s * CAB, GLASS_TOP), (ZR0 - 0.3, ZR1 + 0.3), 0.75, ang,
              decal=lambda c: tone(C, 0.84 + lit) if c.face == "up" else tone(C, 0.4))
    # a stub antenna on the roof's back edge
    m.box("antenna", (-0.4, ROOF, ZR1 - 2.5), (0.4, ROOF + 0.5, ZR1 - 1), BLACK)

    def frame(c):
        x, y, z = c.p
        if y > screen_y(z) - 0.6 or y > rear_y(z) - 0.6:
            return CLEAR                                                  # above the pillars' lines (their planks
                                                                          # cover the glass's stepped edge)
        if y < BELT + 0.5 or y > GLASS_TOP - 0.5 or y > screen_y(z) - 1.3 or y > rear_y(z) - 1.6:
            return ("rc_trim", 0.15)                                      # the seals, the pillars' edges
        if D1 - 0.5 <= z < D1 + 0.75:
            return ("rc_trim", 0.2)                                       # the post behind the door glass
        if z >= 22:
            return (C, 0.36) if not _inner(c) else ("rc_trim", 0.1)       # the C pillar's flank (body colour)
        if 17 <= z < 21 and 16 <= y < 19 and c.p[0] > 0 and not _inner(c):
            # a sticker in the right quarter light: a white rectangle, a red bar
            return ("rc_red", 0.6) if math.floor(y) == 17 else ("rc_cage", 0.9)
        return None
    gp = Paint("rc_trim", 1, noise=0, light=False, decal=_glass(frame))
    for s in (-1, 1):
        lo, hi = _x(s, CAB, CAB + 0.5)
        door = _door(kit, s)
        door.box("side_glass", (lo, BELT, D0 + 0.25), (hi, GLASS_TOP, D1), gp)
        m.box("quarter_glass", (lo, BELT, D1), (hi, GLASS_TOP, ZD), gp)


def _cabin(m, ctx):
    """Two bucket seats with red harnesses, the dash with a rev counter and the small photograph, the wheel, a tall
    gear lever, the mirror with its charm, and in the hatch a strut brace and his toolbox."""
    C = ctx["C"]

    def bucket(c):
        x, y, z = c.p
        ax = abs(abs(x) - SEAT_X)
        if c.face == "up":
            return tone("rc_seat", 0.75 if ax < 2 else 0.5)
        if c.face in ("north", "south"):
            if 0.9 <= ax < 1.7 and y > 6:
                return tone("rc_red", 0.62 if math.floor(y) % 4 else 0.4)     # the harness's two shoulder straps
            if 17 <= y < 18.5 and ax < 1.2 and c.face == "north":
                return tone("rc_trim", 0.0)                                   # the slot under the head rest
            return tone("rc_seat", 0.62 if ax < 2.2 else 0.36)
        return tone("rc_seat", 0.3)
    seat = Paint("rc_seat", 2, noise=0, grad=0, rim=0.5, ao=0.35, decal=bucket)
    for sx in (-SEAT_X, SEAT_X):
        z0 = SEAT_Z - 3.5
        m.box("cushion", (sx - 3, FLOOR, z0), (sx + 3, SEAT_Y + 0.25, z0 + 6), seat)
        for e in (-1, 1):
            # the thigh and the side bolsters: the bucket's high sides
            xa, xb = sorted((sx + e * 2.5, sx + e * 3.25))
            m.box("bolster", (xa, FLOOR, z0 + 0.5), (xb, SEAT_Y + 2.25, z0 + 6), seat)
            m.box("wing", (xa, 9, z0 + 5), (xb, 16.5, z0 + 6.25), seat)
        m.box("seat_back", (sx - 2.75, FLOOR, z0 + 6), (sx + 2.75, 18.5, z0 + 7.5), seat)
        m.box("seat_head", (sx - 2, 18.5, z0 + 6.25), (sx + 2, 21.25, z0 + 7.5), seat)

    def dash(c):
        x, y = c.p[0], c.p[1]
        if c.face == "up":
            return tone("rc_trim", 0.35)
        if c.face == "south":
            if math.hypot(x + SEAT_X - 1.4, y - 11.9) < 1.2 or math.hypot(x + SEAT_X + 1.4, y - 11.9) < 1.2:
                return tone("rc_cage", 0.9)                               # two dials behind the wheel
            if 0 <= x < 3 and 10 <= y < 12:
                return tone("rc_trim", 0.0)                               # the hole where the radio was
            if 5 <= x < 10 and math.floor(y) == 11:
                return tone("rc_trim", 0.5)                               # the glove box's lid
        return tone("rc_trim", 0.2)
    dp = Paint("rc_trim", 1, noise=0, grad=0, rim=0.3, ao=0.35, decal=dash)
    m.box("dash", (-(TRACK - 1), 9.5, ZS0), (TRACK - 1, BELT - 0.25, D0), dp)
    m.box("dash", (-CAB, 9.5, D0), (CAB, BELT - 0.25, -9.5), dp)

    # the rev counter on the dash top: a white dial, a red needle, the shift light over it
    def tach(c):
        if c.face != "south":
            return tone("rc_trim", 0.3)
        u, v = c.u, c.v
        if c.v == 0 and c.u == c.nu - 1:
            return ("raw", (255, 70, 60, 255))
        if u in (0, c.nu - 1) or v in (0, c.nv - 1):
            return tone("rc_trim", 0.1)
        return ("raw", (236, 64, 50, 255)) if u == v else ("raw", (240, 240, 232, 255))
    m.box("tach", (-SEAT_X - 2.0, BELT - 0.25, -11.5), (-SEAT_X + 0.5, BELT + 2.25, -10.5), Paint("rc_trim", 1, noise=0, light=False, decal=tach))

    # the photograph leaning on the dash in front of the empty seat: a white border, two small figures by a car
    def photo(c):
        if c.face == "north":
            return ("raw", (206, 198, 180, 255))                          # its back
        if c.face != "south":
            return ("raw", (236, 230, 214, 255))
        u, v = c.u, c.v
        if u in (0, c.nu - 1) or v in (0, c.nv - 1):
            return ("raw", (240, 234, 218, 255))
        if v == c.nv - 2:
            return ("raw", (120, 124, 120, 255))                          # the road
        if u in (1, 2):
            return ("raw", (150, 156, 164, 255)) if v > 1 else ("raw", (120, 170, 210, 255))   # the car
        if u == c.nu - 2:
            return ("raw", (214, 150, 110, 255)) if v == 1 else ("raw", (70, 96, 150, 255))    # someone
        if u == c.nu - 3:
            return ("raw", (224, 164, 124, 255)) if v == 1 else ("raw", (196, 70, 60, 255))    # and someone else
        return ("raw", (120, 170, 210, 255))                              # sky
    m.box("photo", (3.75, BELT - 0.25, -10.75), (6.75, BELT + 2.25, -10.5), Paint("rc_cage", 2, noise=0, light=False, decal=photo))

    # the console, the tall gear lever with a white knob, the handbrake
    con = Paint("rc_trim", 1, noise=0, rim=0.4, decal=lambda c: tone("rc_trim", 0.3))
    m.box("console", (-1.5, FLOOR, -9.5), (1.5, 5.5, 5), con)
    m.box("gear_lever", (-0.3, 5.5, -3.3), (0.3, 10, -2.7), METAL)
    m.box("gear_knob", (-0.75, 10, -3.75), (0.75, 11.25, -2.25), CAGE)
    m.box("handbrake", (-0.4, 5.5, 0.5), (0.4, 6.5, 4), BLACK)
    # the small wheel (driver on the left): a thin rim, three spokes, a bare alloy hub
    cx, cy, cz = -SEAT_X, 13.25, -7.5
    rim = Paint("rc_trim", 1, noise=0, grad=0, rim=0.6, decal=lambda c: tone("rc_red", 0.5) if c.p[1] > cy + 1.9 and abs(c.p[0] - cx) < 0.5 else tone("rc_trim", 0.55 if c.p[1] > cy else 0.25))
    for xa, ya, xb, yb in ((-2.25, 1.75, 2.25, 2.25), (-2.25, -2.25, 2.25, -1.75), (-2.25, -1.75, -1.75, 1.75), (1.75, -1.75, 2.25, 1.75)):
        m.box("wheel", (cx + xa, cy + ya, cz), (cx + xb, cy + yb, cz + 0.5), rim)
    m.box("wheel_spoke", (cx - 1.75, cy - 0.3, cz + 0.1), (cx + 1.75, cy + 0.3, cz + 0.4), METAL)
    m.box("wheel_spoke", (cx - 0.3, cy - 1.75, cz + 0.1), (cx + 0.3, cy - 0.3, cz + 0.4), METAL)
    m.box("column", (cx - 0.4, cy - 0.9, -9.5), (cx + 0.4, cy - 0.1, cz), BLACK)
    # the mirror at the screen's top, and the charm hanging from it: a red knot under a gold bead, a red tassel
    m.box("mirror_stalk", (-0.3, 22.2, 2.1), (0.3, GLASS_TOP, 2.6), BLACK)

    def mirror(c):
        if c.face == "south" and 0 < c.u < c.nu - 1:
            return tone("rc_metal", 0.85)
        return tone("rc_trim", 0.2)
    m.box("mirror", (-2.5, 21, 2.1), (2.5, 22.3, 2.6), Paint("rc_trim", 1, noise=0, grad=0, rim=0.3, ao=0, decal=mirror))
    flatp = dict(noise=0, light=False)
    m.box("charm_string", (-0.12, 19.25, 2.23), (0.12, 21, 2.47), Paint("rc_cage", 2, decal=lambda c: ("raw", (214, 206, 190, 255)), **flatp))
    m.box("charm_bead", (-0.4, 18.75, 2.0), (0.4, 19.4, 2.7), Paint("gold", 2, decal=lambda c: ("raw", (244, 200, 72, 255)), **flatp))
    m.box("charm", (-0.7, 17.4, 1.9), (0.7, 18.75, 2.8),
          Paint("rc_red", 2, decal=lambda c: ("raw", (236, 62, 50, 255)) if c.face != "down" and (c.u + c.v) % 2 == 0 else ("raw", (186, 34, 34, 255)), **flatp))
    m.box("charm_tassel", (-0.2, 16.2, 2.15), (0.2, 17.4, 2.55), Paint("rc_red", 2, decal=lambda c: ("raw", (210, 44, 40, 255)), **flatp))
    # in the hatch: a strut brace across it, and his red toolbox (a black handle, a steel latch)
    m.box("strut_brace", (-(TRACK - 1), BELT + 1.25, 21), (TRACK - 1, BELT + 2, 21.75), METAL)
    for s in (-1, 1):
        m.box("strut_top", _lo(s, TRACK - 2.5, TRACK - 1, BELT, 20.5), _hi(s, TRACK - 2.5, TRACK - 1, BELT + 1.25, 22.25), METAL)

    def toolbox(c):
        x, y, z = c.p
        if c.face == "up":
            return tone("rc_red", 0.85)
        if math.floor(y) == 15:
            return tone("rc_red", 0.25)                                   # the lid's seam
        if c.face in ("north", "south") and 3.5 <= x < 4.5 and y > 15:
            return tone("rc_metal", 0.8)                                  # the latch
        return tone("rc_red", 0.6 if y > 16 else 0.45)
    m.box("toolbox", (1.5, BELT, 23.5), (6.5, BELT + 2.75, 26.5), Paint("rc_red", 2, noise=0, grad=0, rim=0.3, ao=0.3, decal=toolbox))
    m.box("toolbox_handle", (3, BELT + 2.75, 24.75), (5, BELT + 3.25, 25.25), BLACK)
    del C


def _cage(m):
    """The roll cage, white: a hoop behind the seats, a harness bar across it, two stays falling at 45 degrees into the
    hatch, a bar along each door."""
    x = TRACK + 0.25                                   # the hoop's legs stand just inside the glass
    z0, z1 = HOOP, HOOP + 0.9
    top = GLASS_TOP - 0.35
    for s in (-1, 1):
        m.box("cage_leg", _lo(s, x - 0.9, x, FLOOR, z0), _hi(s, x - 0.9, x, top - 0.9, z1), CAGE)
        m.box("cage_door_bar", _lo(s, x - 0.9, x, 8.5, -9.5), _hi(s, x - 0.9, x, 9.4, z0), CAGE)
        # a stay from the hoop's top corner down and back to the hatch floor
        drop = top - 0.2 - BELT
        plank(m, "cage_stay", "x", (z1, top - 0.2), (z1 + drop, BELT), _x(s, x - 0.9, x), 0.9, CAGE, below=True)
    m.box("cage_top", (-x, top - 0.9, z0), (x, top, z1), CAGE)
    m.box("cage_harness_bar", (-(x - 0.9), 15, z0), (x - 0.9, 15.9, z1), CAGE)


def _door_parts(kit, ctx):
    """On each door: a small black mirror on a stalk, a flush handle, a net pocket's bar on the card."""
    for s in (-1, 1):
        door = _door(kit, s)
        z = D0 + 1.5

        def mirror(c):
            if c.face == "south":
                if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
                    return tone("rc_trim", 0.2)
                return tone("rc_metal", 0.8)
            if c.face == "up":
                return tone("rc_trim", 0.9)
            if c.face == "down":
                return tone("rc_trim", 0.0)
            return tone("rc_trim", 0.45)
        door.box("mirror_foot", _lo(s, W - 1, W + 0.75, BELT + 0.3, z + 0.5), _hi(s, W - 1, W + 0.75, BELT + 1.0, z + 1.5), BLACK)
        door.box("mirror", _lo(s, W + 0.5, W + 2.75, BELT + 0.5, z), _hi(s, W + 0.5, W + 2.75, BELT + 2.5, z + 1.75),
                 Paint("rc_trim", 1, noise=0, grad=0, rim=0.3, ao=0, decal=mirror))
        door.box("handle", _lo(s, W, W + 0.3, 11.5, D1 - 4.5), _hi(s, W, W + 0.3, 12.1, D1 - 1.5), BLACK)
        door.box("card_bar", _lo(s, CAB - 0.75, CAB, 7, D0 + 6), _hi(s, CAB - 0.75, CAB, 7.75, D1 - 3), BLACK)


def _skirts(m):
    """The black side skirts between the flares."""
    for s in (-1, 1):
        m.box("skirt", _lo(s, W, W + 0.75, GROUND - 0.25, WF + ARCH + 1.5), _hi(s, W, W + 0.75, SILL - 0.5, WR - ARCH - 1.5), BLACK)


def _part(name):
    return lambda: coupe_kit().all()[name]


# ================================================================================================
# The wheel: 12 px (0.75 block) across, 5 px wide
# ================================================================================================

def wheel_racer():
    """What it stands on while it waits for its real wheels: a plain black steel wheel (eight round holes, four bare
    lug nuts round a dusty hub, a little rust at the rim) in a worn tyre."""
    m = BModel("wheel_racer", shift=(8, 8, 8), floor=None)
    d, width = 12, 5
    r = d / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            if rr > r - 0.8:
                return tone("rc_trim", 0.0)
            if r - 1.6 < rr <= r - 0.8:
                return tone("rc_trim", 0.36)
            return tone("rc_trim", 0.14)
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.4)
        return tone("rc_trim", 0.0 if k % 3 == 0 else 0.2)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, Paint("rc_trim", 1, noise=0, rim=0.2, decal=tire), axis="x")
    rim_d = 8

    def face(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        rc = rim_d / 2
        if c.face not in ("east", "west"):
            return tone("rc_steel", 0.5)
        if rr > rc - 0.6:
            a = math.atan2(z, y)
            return tone("rc_oxide", 0.4) if math.floor(a * 3) % 9 == 0 else tone("rc_steel", 1.0 if y > 0 else 0.7)   # the rim, rust in places
        if rr < 0.7:
            return tone("rc_steel", 0.2)                                  # the bare hub
        if rr < 1.9:
            a = math.atan2(z, y)
            t = (a * 4 / (2 * math.pi) + 0.125) % 1.0
            if abs(t - 0.5) < 0.16 and 0.9 < rr < 1.7:
                return tone("rc_metal", 0.7)                              # four lug nuts
            return tone("rc_steel", 0.42)
        a = math.atan2(z, y)
        t = (a * 8 / (2 * math.pi)) % 1.0
        if 2.2 < rr < 3.2 and abs(t - 0.5) < 0.22:
            return tone("rc_trim", 0.0)                                   # the holes
        return tone("rc_steel", 0.8 if y > 0.5 else 0.55)
    p = Paint("rc_steel", 3, noise=0, rim=0.2, ao=0.3, decal=face)
    w2 = width / 2
    cyl(m, "hub", (0, 0), rim_d, w2, w2 + 0.25, p, axis="x")
    cyl(m, "hub", (0, 0), rim_d, -w2 - 0.25, -w2, p, axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


ALL = {
    "racer_coupe": racer_coupe,
    "wheel_racer": wheel_racer,
    **{f"racer_coupe_{p}": _part(f"racer_coupe_{p}") for p in PARTS},
}
