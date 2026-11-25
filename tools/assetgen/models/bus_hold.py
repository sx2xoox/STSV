"""
마을버스 손님이 손에 든 물건 (economy README "마을버스", bus.yml types.*.hold / hold-off): display-only economy models
(economy/content/economy/models/bus_hold_<id>.bbmodel, item "economy:bus_hold_<id>" in items/bus.yml). A passenger
mannequin holds one in its main hand (hold) and/or its off hand (hold-off); the skins no longer paint these.

Every model is drawn at TWICE its real size (1 drawn unit = half a skin pixel, so a hen's eye or a rose's petals can
be a texel) and shown at scale 0.5 in the hand (the "thirdperson_righthand" transform), front = north (-z).

How a held item lands (ItemInHandLayer, vanilla 26.3): the arm's pivot and turn, then Rx(-90) Ry(180), then
(+-1, 2, -10) px, then the item's display transform (translation, rotationXYZ, scale about the model's (8, 8, 8)).
A seated passenger (on the bus, on the stop's bench) holds both arms 36 degrees forward (riding: -pi/5; an arm with
an item: xRot * 0.5 - pi/10, the same -36), a standing one 18 degrees with the item and 0 without. The transforms
here are solved from where each thing should sit for a SEATED passenger (PLACE below: in the arm's own frame, or for
the hen against the chest); a standing one holds it the same way in the hand, the arm a little lower. The off hand
uses the same transform mirrored (vanilla: thirdperson_lefthand defaults to the right one, mirrored on use).

  bus_hold_chicken        닭 든 할머니: a white hen cradled at the chest, head up and looking forward: a red comb and
                          wattles, an orange beak and feet, an amber eye, layered wing feathers, a cocked tail
  bus_hold_chicken_flap   the same hen with her wings spread and flapping, legs down (BusModule: a hard brake
                          sends her fluttering round the bus for a moment, swapping these two)
  bus_hold_folder         면접 청년: a navy document folder held upright on his lap, papers showing, a clip
  bus_hold_yeot_scissors  엿장수: the big iron 엿가위, blades open in a V above the fist
  bus_hold_map            외국인 여행객: a folded tourist map held open, roads, a river, a red pin
  bus_hold_selfie         유튜버: a phone held up in a ring light, the screen lit
  bus_hold_snack          과자 부스러기 학생: an open crisp packet, crimped top, a crisp peeking out
  bus_hold_bells          무당: a bunch of brass 방울 on a handle with five-colour ribbons
  bus_hold_fan            무당: a folding fan, open, in the five colours
  bus_hold_leash          강아지 할아버지: a red lead, its handle loop above the fist, its clip hanging
  bus_hold_towel          멀미하는 아저씨: a pink hand towel bunched in the fist
  bus_hold_plastic_bag    멀미하는 아저씨: a black plastic bag hanging from the fist, just in case
  bus_hold_shoe_bag       초등학생: a blue drawstring indoor-shoe bag (실내화 주머니)
  bus_hold_bouquet        신부: a round bouquet of pink and white roses, ribbon-tied
  bus_hold_wand           마술사: a black wand with white tips
  bus_hold_cards          마술사: a fan of playing cards
  bus_hold_rose           마술사: a single red rose
  bus_hold_cane           강아지 할아버지: his walking stick, planted between his feet
  bus_hold_pole           등산객: a trekking pole with a wrist strap and a basket
  bus_hold_rod            낚시꾼: a short rod with a spinning reel, the line and a float hanging from the tip
  bus_hold_parcel         택배기사: a taped cardboard parcel with a label and a barcode
  bus_hold_bungeo_bag     붕어빵 장수: a paper bag of 붕어빵, two poking out
  bus_hold_bundle         시장 아줌마: a flowery 보따리 held by its knot
"""

import math

import numpy as np

from stsv_assets import presets
from stsv_assets.bake import BModel, Paint, cyl, h3, voxels

SIDES = ("north", "south", "east", "west")
HALF = 0.5          # every model is drawn at twice its size


# ================================================================================================
# Where a held thing goes (the solver for the "thirdperson_righthand" transform)
# ================================================================================================
# Frames, all in pixels, y up, the passenger facing -z, its right arm on +x ("world" axes):
#   arm frame:  the right arm hanging straight down, its shoulder pivot at the origin; the classic arm is x -1..3,
#               y -10..2, z -2..2 (the fist's middle about (1, -8.5, 0)); the arm turns about x by the pose's angle
#   body frame: the neck (model origin) at the origin; the right shoulder pivot at (5, -2, 0) (slim: (5, -2.5, 0))

SEATED = 36.0       # degrees an arm is held forward on a seat (riding)
STANDING = 18.0     # degrees forward with an item, standing


def _rx(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def _ry(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def _rz(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


RZ180 = np.diag([-1.0, -1.0, 1.0])     # the entity renderer's scale(-1, -1, 1): model space (y down) -> world
# the hand frame (after the arm): Rx(-90) Ry(180) and (1, 2, -10) px, in the arm frame (world axes)
HAND_R = RZ180 @ _rx(-90) @ _ry(180)
HAND_T = RZ180 @ _rx(-90) @ _ry(180) @ np.array([1.0, 2.0, -10.0])


def euler_xyz(r):
    """Degrees (x, y, z) with r = Rx(x) Ry(y) Rz(z) (JOML rotationXYZ, the display transform's rotation)."""
    sy = max(-1.0, min(1.0, r[0, 2]))
    y = math.asin(sy)
    if abs(sy) < 0.99999:
        x = math.atan2(-r[1, 2], r[2, 2])
        z = math.atan2(-r[0, 1], r[0, 0])
    else:
        x = math.atan2(r[2, 1], r[1, 1])
        z = 0.0
    return [round(math.degrees(v), 2) for v in (x, y, z)]


def hold_transform(grip, at, turn, scale=HALF, frame="arm", pose=SEATED):
    """The display transform putting model point `grip` at `at` (frame "arm": the arm frame, e.g. FIST; "body": the
    body frame of a passenger in this pose), the model turned by `turn`: a world rotation for a passenger in this pose
    (the identity holds it upright, its front (north) facing the way the passenger faces)."""
    at = np.array(at, float)
    arm = _rx(pose)
    if frame == "body":
        at = arm.T @ (at - np.array([5.0, -2.0, 0.0]))
    tn = arm.T @ np.array(turn, float)
    # world point of the hand frame q: HAND_R q + HAND_T (arm frame), so q = HAND_R^T (w - HAND_T); with the
    # item frame q = tr + R s (p - 8):
    r = HAND_R.T @ tn
    tr = HAND_R.T @ (at - HAND_T) + r @ (scale * (np.array([8.0, 8.0, 8.0]) - np.array(grip, float)))
    for v in tr:
        assert -80 <= v <= 80, f"translation {tr} out of the game's range"
    return {"rotation": euler_xyz(r), "translation": [round(float(v), 3) for v in tr], "scale": [scale] * 3}


FIST = (1.0, -8.5, 0.0)     # the middle of the (classic) fist in the arm frame


def fist(dx=0.0, dy=0.0, dz=0.0):
    """A point near the fist (arm frame, px): dx out to the side, dy along the arm (+ towards the shoulder), dz back."""
    return (FIST[0] + dx, FIST[1] + dy, FIST[2] + dz)


def turn(x=0.0, y=0.0, z=0.0):
    """A world turn: about z, then x, then y (degrees): roll, pitch (+x tips the top back), yaw (+y turns the
    front towards the passenger's left)."""
    return _ry(y) @ _rx(x) @ _rz(z)


def displays(m, held, gui_fill=0.9):
    """The display transforms: the hand (held), and an inventory look fitted to the bounds; fixed / ground plain."""
    lo = [min(e["from"][i] for e in m.elements) for i in range(3)]
    hi = [max(e["to"][i] for e in m.elements) for i in range(3)]
    big = max(hi[i] - lo[i] for i in range(3)) / 16
    s = round(min(gui_fill / big, 3.5), 3)
    off = [(lo[i] + hi[i]) / 2 - 8 for i in range(3)]
    rot = presets._rot_xyz([30, 225, 0])
    t = [round(-sum(rot[i][j] * off[j] * s for j in range(3)), 3) for i in range(3)]
    m.display = {
        "thirdperson_righthand": held,
        "firstperson_righthand": {"rotation": [0, -20, 0], "translation": [1, 3, 0], "scale": [0.5, 0.5, 0.5]},
        "firstperson_lefthand": {"rotation": [0, 20, 0], "translation": [1, 3, 0], "scale": [0.5, 0.5, 0.5]},
        "gui": {"rotation": [30, 225, 0], "translation": t, "scale": [s] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    }
    return m


# ================================================================================================
# The hen
# ================================================================================================

def _ell(p, c, r):
    return sum(((p[i] - c[i]) / r[i]) ** 2 for i in range(3)) <= 1.0


def _hen_cells(flap=False):
    """(x, y, z) cell -> label. Drawn 2x: the hen 16 long (8 px), the body x 4..12."""
    cells = {}
    for x in range(-4, 21):
        for y in range(-2, 22):
            for z in range(-2, 20):
                p = (x + 0.5, y + 0.5, z + 0.5)
                lab = None
                # beak: short, the upper part over the lower
                if 7 <= x <= 8 and ((y == 15 and z in (1, 2)) or (y == 14 and z == 2)):
                    lab = "beak"
                # comb: a single comb, three points along the crown
                elif 7 <= x <= 8 and ((y == 18 and 3 <= z <= 6) or (y == 19 and z in (3, 5))):
                    lab = "comb"
                # wattles under the beak
                elif 7 <= x <= 8 and y in (12, 13) and z == 3:
                    lab = "wattle"
                elif _ell(p, (8, 15.5, 5), (2.3, 2.5, 2.5)):
                    lab = "head"
                elif _ell(p, (8, 12, 6.5), (2.4, 3.2, 2.3)):
                    lab = "neck"
                # tail: a cocked fan rising at the back, narrow side to side
                elif 5.5 <= p[0] <= 10.5 and 12.5 <= p[2] <= 17.5 and 7 <= p[1] <= 7 + (p[2] - 10) * 1.25 \
                        and p[1] >= 8 + (p[2] - 13) * 0.6 and abs(p[0] - 8) <= 2.5 - max(0, p[1] - 13) * 0.25:
                    lab = "tail"
                elif _ell(p, (8, 8.5, 10), (4.6, 4.2, 6)) or _ell(p, (8, 7.5, 8), (4.2, 4.4, 4.6)):
                    lab = "body"
                # legs and feet (tucked against the belly when held; hanging down when she flaps)
                elif not flap and x in (6, 9) and y in (3,) and 9 <= z <= 10:
                    lab = "leg"
                elif not flap and x in (5, 6, 9, 10) and y == 3 and z == 8:
                    lab = "leg"
                elif flap and x in (6, 9) and 0 <= y <= 3 and z == 10:
                    lab = "leg"
                elif flap and y == 0 and ((x in (6, 9) and 7 <= z <= 9) or (x in (5, 7, 8, 10) and z == 8)):
                    lab = "leg"
                if lab:
                    cells[(x, y, z)] = lab
    # wings: a raised panel on each side of the body, folded (or spread for the flap)
    for side in (-1, 1):
        if not flap:
            for y in range(5, 12):
                for z in range(6, 16):
                    # a teardrop: rounded at the shoulder (front), tapering to the tips at the back
                    top = 11 - max(0, z - 12) * 0.5
                    bot = 5 + max(0, 9 - z) * 0.7 + max(0, z - 12) * 0.9
                    if bot <= y + 0.5 <= top:
                        x = 12 if side > 0 else 3
                        # sit on the body's surface: the outermost body cell + 1 on that side
                        xs = [c[0] for c in cells if c[1] == y and c[2] == z and cells[c] == "body"]
                        if not xs:
                            continue
                        x = max(xs) + 1 if side > 0 else min(xs) - 1
                        cells[(x, y, z)] = "wing"
        else:
            # spread: a fan out to the side and up from the shoulder (y 10, z 8), feathers fingering at the ends
            for k in range(0, 8):          # outwards
                for z in range(5, 14):
                    h = 10 + k * 0.85
                    span = 7 - k * 0.45
                    if not (8 - 2 <= z <= 8 - 2 + span + 2):
                        continue
                    if k >= 5 and (z % 2 == 1):
                        continue           # fingered primaries
                    x = (12 + k) if side > 0 else (3 - k)
                    cells[(x, int(h), z)] = "wing"
                    cells[(x, int(h) + 1, z)] = "wing"
    return cells


def _hen(name, flap=False):
    cells = _hen_cells(flap)
    m = BModel(name, floor=None)

    def feathers(c):
        x, y, z = c.i
        lab = cells.get((x, y, z))
        if lab == "body":
            if c.face in ("east", "west") and y <= 7 and z <= 9:
                return ("feather", 3)                         # the plump breast catches the light
            if c.face in ("east", "west") and z >= 13 and (y + z) % 2 == 0:
                return ("feather", 1)                         # the soft rump
        if lab == "neck" and c.face in SIDES and (y + z) % 3 == 0:
            return ("feather", 1)                             # the hackles
        if lab == "tail":
            if (z + y) % 3 == 0:
                return ("feather", 1)                         # the tail feathers' edges
            if y >= 13:
                return ("garlic", 1)                          # cream tips
        if lab == "wing":
            if flap:
                if (x % 2 == 0) == (y % 2 == 0) and abs(x - 8) >= 7:
                    return ("garlic", 1)
                return ("feather", 2) if (z % 2) else ("feather", 1)
            # folded: coverts at the shoulder, two rows of secondaries, the primaries' tips at the back
            if z >= 13:
                return ("garlic", 0) if (y % 2 == 0) else ("garlic", 1)
            if y == 6 or y == 8:
                return ("feather", 0)                         # the rows' shadowed edges
            if z <= 8 and y >= 9:
                return ("feather", 3)
            return ("feather", 1)
        if lab == "head":
            # the eye: amber round a black pupil on each side, a bare red face patch round it
            if c.face in ("east", "west"):
                p = c.p
                d = math.hypot(p[2] - 4.5, p[1] - 15.5)
                if d < 0.75:
                    return ("raw", (24, 20, 26, 255))
                if d < 1.4 and p[1] >= 15:
                    return ("lamp", 1)
                if d < 1.5 and p[2] < 4.5 and p[1] < 15.6:
                    return ("tomato", 3)                      # the bare red cheek below and before it
        return None

    paints = {
        "body": Paint("feather", 2, noise=0.0, rim=0.7, decal=feathers),
        "neck": Paint("feather", 2, noise=0.0, rim=0.7, decal=feathers),
        "head": Paint("feather", 2, noise=0.0, rim=0.6, decal=feathers),
        "tail": Paint("feather", 2, noise=0.03, decal=feathers),
        "wing": Paint("feather", 2, noise=0.0, decal=feathers, ao=0.6),
        "comb": Paint("tomato", 2, noise=0.0, rim=1.2, decal=lambda c: ("tomato", 3) if c.face == "up" else None),
        "wattle": Paint("tomato", 2, noise=0.0),
        "beak": Paint("corn", 2, noise=0.0, decal=lambda c: ("corn", 1) if c.p[1] < 15 else None),
        "leg": Paint("fox", 3, noise=0.0),
    }
    voxels(m, "hen", cells, lambda lab: paints[lab])
    return m


def chicken():
    m = _hen("bus_hold_chicken")
    # cradled at the chest: her middle in front of the passenger's belly, a little to the right (the right hand
    # under her), turned a touch to the left so the face shows; seated pose
    held = hold_transform(grip=(8, 8, 10), at=(1.0, -7.5, -6.5), turn=turn(y=-20), scale=0.6, frame="body")
    return displays(m, held)


def chicken_flap():
    m = _hen("bus_hold_chicken_flap", flap=True)
    held = hold_transform(grip=(8, 8, 10), at=(1.0, -7.5, -6.5), turn=turn(y=-20), scale=0.6, frame="body")
    return displays(m, held)



# ================================================================================================
# Things held in the fist
# ================================================================================================

def _plain(ramp, step, **kw):
    kw.setdefault("noise", 0.0)
    return Paint(ramp, step, **kw)


def folder():
    """면접 청년: a navy document folder held upright on his lap by its bottom edge, clutched (the papers' edges
    showing above its top, a silver clip, a white label)."""
    m = BModel("bus_hold_folder", floor=None)

    def cover(c):
        x, y, z = c.p
        if c.face in ("north", "south") and 5 <= x <= 11 and 4 <= y <= 6.5:
            return ("paper", 2) if not (5.5 <= x <= 10.5 and y > 5 and y < 5.6) else ("indigo", 3)   # the label
        if c.face in ("north", "south") and y > 11.5:
            return ("indigo", 1)                              # the fold at the top
        return None
    m.box("cover", (3, 0, 7), (13, 12.5, 9), _plain("indigo", 2, rim=1.2, decal=cover))
    m.box("papers", (3.5, 12.5, 7.5), (12.5, 13.5, 8.5), _plain("paper", 2, decal=lambda c: ("paper", 1) if c.face in SIDES and int(c.p[1] * 2) % 2 else None))
    m.box("clip", (9.5, 10.5, 6.5), (11.5, 13, 7), _plain("blade", 2))
    held = hold_transform(grip=(8, 1, 8), at=fist(-0.5, 0, -1), turn=turn(x=8, y=-15))
    return displays(m, held)


def yeot_scissors():
    """엿장수: the big iron 엿가위, the handles in the fist, the blades open in a V above it, a rivet where they cross."""
    m = BModel("bus_hold_yeot_scissors", floor=None)
    iron = _plain("steel", 2, kind="metal", rim=1.4)
    edge = _plain("steel", 4, kind="metal")
    for sgn, z0 in ((1, 7.5), (-1, 8.5)):
        rot = ("z", 22.5 * sgn, (8, 7, 8))
        m.box("blade", (7, 7, z0 - 0.5), (8.5, 13, z0 + 0.5), iron, faces={"west": edge}, rotation=rot)
        m.box("blade", (7.25, 13, z0 - 0.5), (8.25, 17, z0 + 0.5), iron, faces={"west": edge}, rotation=rot)
        m.box("handle", (7, 0, z0 - 0.5), (9, 7, z0 + 0.5), _plain("wood_dark", 1, kind="grain"), rotation=("z", -22.5 * sgn, (8, 7, 8)))
    m.box("rivet", (7.25, 6.25, 6.5), (8.75, 7.75, 9.5), _plain("brass", 2))
    held = hold_transform(grip=(8, 3, 8), at=fist(), turn=turn(x=-15))
    return displays(m, held)


def tourist_map():
    """외국인 여행객: a folded tourist map held open in the fist, tipped back to read: roads, a river, a park, a red pin
    (printed on both sides, as maps are)."""
    m = BModel("bus_hold_map", floor=None)

    def print_(c):
        x, y, _ = c.p
        if c.face not in ("north", "south"):
            return None
        if abs(x - 6) < 0.3 or abs(x - 10) < 0.3 or abs(y - 5) < 0.3:
            return ("paper", 0)                               # the folds
        if abs((y - 1.5) - (x - 2) * 0.6) < 0.6:
            return ("water", 2)                               # a river
        if 9 <= x <= 12 and 6.5 <= y <= 9:
            return ("leaf", 3)                                # a park
        if abs(y - 7.5) < 0.35 or abs(x - 4.5) < 0.35 or abs(y - 2.5) < 0.3 and x > 8:
            return ("fox", 3)                                 # roads
        if math.hypot(x - 7.5, y - 7.8) < 0.7:
            return ("tomato", 2)                              # the pin
        return None
    m.box("sheet", (2, 0, 7.75), (14, 10, 8.25), _plain("paper", 2, decal=print_, light=False))
    held = hold_transform(grip=(12, 1, 8), at=fist(-0.5, 0, -0.5), turn=turn(x=45, y=-10))
    return displays(m, held)


def selfie():
    """유튜버: a selfie stick from the fist up to a phone in a little ring light at her face, the screen and the light
    towards her (the phone's back and the ring's white shell to everyone else)."""
    m = BModel("bus_hold_selfie", floor=None)
    m.box("grip", (7, 0, 7), (9, 6, 9), _plain("rubber", 2, kind="speckle"))
    m.box("pole", (7.5, 6, 7.5), (8.5, 20.5, 8.5), _plain("blade", 1, kind="metal", rim=1.4))
    m.box("clamp", (6.5, 20.5, 7.5), (9.5, 21.5, 8.75), _plain("rubber", 2))
    ring = {}
    for x in range(2, 14):
        for y in range(19, 32):
            if 4.0 <= math.hypot(x + 0.5 - 8, y + 0.5 - 25) < 5.6:
                ring[(x, y, 8)] = "ring"
    voxels(m, "ring", ring, lambda _l: _plain("porcelain", 2, light=False,
                                              decal=lambda c: ("raw", (255, 252, 236, 255)) if c.face == "south" else None))

    def screen(c):
        x, y, _ = c.p
        if c.face == "south":
            if 6.5 <= x <= 9.5 and 22.5 <= y <= 27.5:
                return ("raw", (120, 226, 255, 255)) if (y > 25.5 or x < 7.5) else ("raw", (70, 190, 240, 255))
            return ("raw", (30, 30, 36, 255))
        if c.face == "north" and 6.5 <= x <= 7.5 and 26.5 <= y <= 27.5:
            return ("raw", (10, 10, 14, 255))                 # the camera
        return None
    m.box("phone", (6, 22, 8.75), (10, 28, 9.5), _plain("rubber", 3, decal=screen))
    held = hold_transform(grip=(8, 2, 8), at=fist(), turn=turn(x=-28, y=12))
    return displays(m, held)


def snack():
    """과자 부스러기 학생: an open crisp packet held round its middle, a crimped silver top torn open, a yellow
    starburst on a red bag, a crisp peeking out."""
    m = BModel("bus_hold_snack", floor=None)
    cells = {}
    for x in range(4, 12):
        for y in range(0, 13):
            for z in range(5, 11):
                bulge = 3 - 2.2 * (abs(y - 6) / 6.5) ** 2 - 0.6 * (abs(x + 0.5 - 8) / 4) ** 2
                if abs(z + 0.5 - 8) <= bulge:
                    cells[(x, y, z)] = "bag"
    for x in range(4, 12):
        for z in (7, 8):
            cells[(x, 13, z)] = "crimp"
    for (x, y, z) in ((6, 13, 8), (7, 14, 8), (8, 13, 7), (9, 14, 7)):
        cells[(x, y, z)] = "crisp"

    def face(c):
        x, y, z = c.i
        r = math.hypot(c.p[0] - 8, c.p[1] - 7.5)
        if c.face == "north" and r < 2.2:
            return ("porcelain", 2) if r < 0.8 and c.p[0] < 8 else ("petal_yellow", 1)   # a round yellow badge
        if y in (2, 3):
            return ("petal_yellow", 2)                        # a stripe round the bottom
        return None
    paints = {"bag": _plain("tomato", 2, decal=face, rim=1.2), "crimp": _plain("blade", 2, kind="metal",
              decal=lambda c: ("blade", 1) if int(c.p[0] * 2) % 2 else None), "crisp": _plain("corn", 2)}
    voxels(m, "bag", cells, lambda lab: paints[lab])
    held = hold_transform(grip=(8, 5, 8), at=fist(), turn=turn(x=-10))
    return displays(m, held)


OBANG = (("tomato", 2), ("petal_yellow", 1), ("sapphire", 1), ("porcelain", 2), ("leaf", 3))   # 오방색


def bells():
    """무당: 무구 방울 — a cluster of brass bells (a crown of three, a ring of four) on a short red handle, five-colour
    streamers hanging from its end."""
    m = BModel("bus_hold_bells", floor=None)
    m.box("handle", (7, 0, 7), (9, 9, 9), _plain("fabric_red", 2, kind="grain"))
    m.box("cap", (6.5, 9, 6.5), (9.5, 10, 9.5), _plain("brass", 2))
    brass = _plain("gold", 2, kind="metal", rim=1.5, decal=lambda c: ("brass", 0) if c.face == "down" else None)
    spots = [(8, 15.5, 8)] + [(8 + 2.6 * math.cos(a), 13, 8 + 2.6 * math.sin(a)) for a in (0.5, 2.6, 4.7)] \
        + [(8 + 3.2 * math.cos(a), 10.8, 8 + 3.2 * math.sin(a)) for a in (0.0, 1.57, 3.14, 4.71)]
    for i, (x, y, z) in enumerate(spots):
        m.box("bell", (x - 1.25, y - 1, z - 1.25), (x + 1.25, y + 1, z + 1.25), brass)
        m.box("bell", (x - 0.75, y + 1, z - 0.75), (x + 0.75, y + 1.5, z + 0.75), brass)
        m.box("slit", (x - 1, y - 1.25, z - 0.25), (x + 1, y - 1, z + 0.25), _plain("brass", 0))
    for i, (rmp, st) in enumerate(OBANG):
        x = 6.25 + i * 0.85
        m.box("streamer", (x, -11 + (i % 2) * 2, 7.75), (x + 0.75, 0, 8.25), _plain(rmp, st))
    held = hold_transform(grip=(8, 4, 8), at=fist(), turn=turn(x=-12))
    return displays(m, held)


def fan():
    """무당: a folding fan held open above the fist, its panels in the five colours with a white rim, dark ribs
    gathering to the rivet at the handle."""
    m = BModel("bus_hold_fan", floor=None)
    cells = {}
    R, piv = 12.5, (8, 3)
    for x in range(-6, 22):
        for y in range(0, 17):
            px, py = x + 0.5 - piv[0], y + 0.5 - piv[1]
            r, a = math.hypot(px, py), math.degrees(math.atan2(py, px))
            if 3.5 <= r <= R and 18 <= a <= 162:
                cells[(x, y, 8)] = int((a - 18) // 18)        # 8 panels
    cols = OBANG + (("tomato", 2), ("petal_yellow", 1), ("sapphire", 1))

    def pan(lab):
        rmp, st = cols[lab]

        def d(c):
            r = math.hypot(c.p[0] - piv[0], c.p[1] - piv[1])
            a = math.degrees(math.atan2(c.p[1] - piv[1], c.p[0] - piv[0]))
            if r > R - 1:
                return ("porcelain", 2)                       # the white rim
            if abs(((a - 18) % 18) - 0.0) < 1.6:
                return ("wood_dark", 1)                       # a rib
            return None
        return _plain(rmp, st, decal=d, light=False)
    voxels(m, "panel", cells, pan)
    m.box("handle", (7.25, -4, 7.5), (8.75, 4, 9.5), _plain("wood_dark", 2, kind="grain"))
    m.box("rivet", (7, 2, 7), (9, 3.5, 10), _plain("brass", 2))
    held = hold_transform(grip=(8, 0, 8.5), at=fist(), turn=turn(x=15, z=-10))
    return displays(m, held)


def leash():
    """강아지 할아버지: a red lead, its handle loop showing above the fist, the lead hanging from it with a silver clip
    at its end (the dog walks on its own)."""
    m = BModel("bus_hold_leash", floor=None)
    red = _plain("tomato", 1, rim=0.8)
    loop = {}
    for x in range(5, 11):
        for y in range(8, 15):
            if (x in (5, 10) and y <= 13) or (y == 14 and 6 <= x <= 9) or (y == 13 and x in (6, 9)):
                loop[(x, y, 8)] = "loop"
    voxels(m, "loop", loop, lambda _l: red)
    m.box("lead", (7.5, 0, 7.5), (8.5, 8, 8.5), red)
    m.box("lead", (7.5, -6, 6.5), (8.5, 0, 7.5), red)
    m.box("clip", (7, -9, 6.25), (9, -6, 7.75), _plain("blade", 2, kind="metal"))
    m.box("clip", (7.5, -10, 6.75), (8.5, -9, 7.25), _plain("blade", 1))
    held = hold_transform(grip=(8, 8, 8), at=fist(), turn=turn(x=30))
    return displays(m, held)


def towel():
    """멀미하는 아저씨: a pink hand towel bunched in the fist, an end hanging from it (terry loops, a darker hem)."""
    m = BModel("bus_hold_towel", floor=None)
    cells = {}
    for x in range(2, 14):
        for y in range(-8, 14):
            for z in range(2, 14):
                p = (x + 0.5, y + 0.5, z + 0.5)
                lump = _ell(p, (8, 8, 8), (5, 3.5, 5)) or _ell(p, (6.5, 10.5, 7), (3, 2.5, 3)) or _ell(p, (10, 5.5, 9), (3, 2.5, 3))
                tail = 5 <= x <= 10 and -2 <= y <= 5 and abs(z + 0.5 - (8 - (5 - y) * 0.35)) <= 0.6
                if lump or tail:
                    cells[(x, y, z)] = "towel"
    voxels(m, "towel", cells, lambda _l: _plain("pink", 2, kind="weave", rim=0.7,
                                                decal=lambda c: ("pink", 1) if c.p[1] < -1 else None))
    held = hold_transform(grip=(8, 8, 8), at=fist(), turn=turn(x=35))
    return displays(m, held)


def plastic_bag():
    """멀미하는 아저씨: a black plastic bag hanging from the fist by its handles, just in case (crinkled, a shine)."""
    m = BModel("bus_hold_plastic_bag", floor=None)
    cells = {}
    for x in range(2, 15):
        for y in range(-8, 12):
            for z in range(3, 14):
                p = (x + 0.5, y + 0.5, z + 0.5)
                if _ell(p, (8, -1.5, 8), (5.5, 6.5, 3.8)) and y < 4:
                    cells[(x, y, z)] = "bag"
                elif 4 <= y <= 9 and z in (7, 8) and (abs(x - (8 - (y - 3) * 0.45 - 2.5)) < 0.8 or abs(x - (8 + (y - 3) * 0.45 + 1.5)) < 0.8):
                    pass
    for y in range(4, 10):
        for x in (5 + (y - 4) // 2, 10 - (y - 4) // 2):
            for z in (7, 8):
                cells[(x, y, z)] = "bag"

    def shine(c):
        x, y, z = c.i
        if (x * 5 + y * 3 + z) % 9 == 0:
            return ("iron", 4)                                # crinkles catching the light
        return None
    voxels(m, "bag", cells, lambda _l: _plain("iron", 2, rim=1.6, decal=shine))
    held = hold_transform(grip=(8, 9, 8), at=fist(0, -0.5), turn=turn(x=55))
    return displays(m, held)


def shoe_bag():
    """초등학생: a blue drawstring indoor-shoe bag (실내화 주머니) hanging from its cords in the fist, the top gathered,
    a white patch with a little trainer on it."""
    m = BModel("bus_hold_shoe_bag", floor=None)
    cells = {}
    for x in range(3, 13):
        for y in range(-10, 4):
            for z in range(6, 10):
                if y == 3 and x in (3, 12):
                    continue
                if z in (6, 9) and (x in (3, 12) or y == -10):
                    continue                                  # rounded edges
                cells[(x, y, z)] = "bag"
    for x in range(5, 11):
        for z in (7, 8):
            cells[(x, 4, z)] = "bag"                          # gathered
    for y in range(5, 10):
        cells[(6 + (y - 5) // 3, y, 8)] = "cord"
        cells[(9 - (y - 5) // 3, y, 7)] = "cord"

    def patch(c):
        x, y = c.p[0], c.p[1]
        if c.face == "north" and 5 <= x <= 11 and -7 <= y <= -1:
            if 6 <= x <= 10 and -5 <= y <= -3 and not (x < 7.5 and y > -3.8):
                return ("sapphire", 2) if y > -4 else ("porcelain", 1)   # the little shoe
            return ("porcelain", 2)
        return None
    paints = {"bag": _plain("denim", 2, kind="weave", decal=patch), "cord": _plain("porcelain", 2)}
    voxels(m, "bag", cells, lambda lab: paints[lab])
    held = hold_transform(grip=(7.5, 8.5, 8), at=fist(0, -0.5), turn=turn(x=55))
    return displays(m, held)


def bouquet():
    """신부: a round bouquet of pink and white roses in a ring of leaves, the stems wrapped in white satin with a bow."""
    m = BModel("bus_hold_bouquet", floor=None)
    cells = {}
    for x in range(1, 16):
        for y in range(6, 18):
            for z in range(1, 16):
                p = (x + 0.5, y + 0.5, z + 0.5)
                if _ell(p, (8, 10.5, 8), (6, 5.5, 6)) and y >= 10:
                    cells[(x, y, z)] = "rose"
                elif y == 9 and _ell(p, (8, 9.5, 8), (7, 1, 7)) and not _ell(p, (8, 9.5, 8), (4.5, 1, 4.5)):
                    cells[(x, y, z)] = "leaf"

    def roses(c):
        x, y, z = (math.floor(v / 2) for v in c.p)
        k = h3(x, y, z, 5)
        base = ("pink", 2) if k < 0.45 else ("porcelain", 2) if k < 0.8 else ("sakura", 1)
        fx, fy, fz = (v % 2 for v in c.p)
        if fx < 1 and fz < 1 and fy < 1:
            return (base[0], base[1] - 1)                     # each bloom's dark heart
        return base
    paints = {"rose": _plain("pink", 2, decal=roses, rim=0.6), "leaf": _plain("leaf", 2)}
    voxels(m, "bouquet", cells, lambda lab: paints[lab])
    m.box("wrap", (6.5, 0, 6.5), (9.5, 9, 9.5), _plain("porcelain", 2, kind="lacquer"))
    m.box("bow", (5, 6, 6), (11, 7.5, 6.5), _plain("sakura", 2))
    m.box("stems", (7, -1, 7), (9, 0, 9), _plain("leaf", 1))
    held = hold_transform(grip=(8, 4, 8), at=fist(-0.5), turn=turn(x=-22, y=10))
    return displays(m, held)


def wand():
    """마술사: a black wand with white tips, held near its end, pointing forward."""
    m = BModel("bus_hold_wand", floor=None)
    m.box("wand", (7.4, 2.5, 7.4), (8.6, 16.5, 8.6), _plain("rubber", 2, rim=1.5))
    m.box("tip", (7.4, 16.5, 7.4), (8.6, 19, 8.6), _plain("porcelain", 2))
    m.box("tip", (7.4, 0, 7.4), (8.6, 2.5, 8.6), _plain("porcelain", 2))
    held = hold_transform(grip=(8, 5, 8), at=fist(), turn=turn(x=-30))
    return displays(m, held)


def cards():
    """마술사: a fan of five playing cards held at their corner, faces out to the audience (a red heart, a black
    spade in the middle of each), red backs."""
    m = BModel("bus_hold_cards", floor=None)
    for i, ang in enumerate((-45, -22.5, 0, 22.5, 45)):
        z0 = 7.4 + i * 0.3

        def face(c, i=i):
            x, y = c.p[0], c.p[1]
            if c.face == "north":
                if math.hypot(x - 8, y - 7) < 1.0:
                    return ("tomato", 2) if i % 2 == 0 else ("roe", 1)
                if (x < 6.4 and y > 8.8):
                    return ("tomato", 2) if i % 2 == 0 else ("roe", 1)   # the corner index
                return None
            if c.face == "south":
                return ("tomato", 1) if (int(x * 2) + int(y * 2)) % 2 else ("tomato", 2)
            return None
        m.box("card", (5.5, 3, z0), (10.5, 10, z0 + 0.25), _plain("porcelain", 2, decal=face, light=False), rotation=("z", ang, (8, 2.5, 8)))
    held = hold_transform(grip=(8, 3, 8), at=fist(0, 0, -1), turn=turn(x=-45))
    return displays(m, held)


def rose():
    """마술사: a single red rose, a green stem with a leaf and the sepals under the bloom."""
    m = BModel("bus_hold_rose", floor=None)
    m.box("stem", (7.5, 0, 7.5), (8.5, 11, 8.5), _plain("leaf", 1))
    m.box("leaf", (8.5, 5, 7.75), (11, 6.5, 8.25), _plain("leaf", 3), rotation=("z", 22.5, (8.5, 5.5, 8)))
    m.box("sepal", (6.5, 11, 6.5), (9.5, 12, 9.5), _plain("leaf", 2))
    cells = {}
    for x in range(5, 11):
        for y in range(12, 17):
            for z in range(5, 11):
                if _ell((x + 0.5, y + 0.5, z + 0.5), (8, 14, 8), (2.9, 2.6, 2.9)):
                    cells[(x, y, z)] = "bloom"

    def petals(c):
        if c.face == "up":
            r = math.hypot(c.p[0] - 8, c.p[2] - 8)
            return ("fabric_red", 1) if 0.6 < r < 1.4 or r < 0.3 else None   # the spiral's shadow
        if (c.i[0] + c.i[2] + c.i[1]) % 3 == 0:
            return ("fabric_red", 1)
        return None
    voxels(m, "bloom", cells, lambda _l: _plain("tomato", 1, decal=petals, rim=1.2))
    held = hold_transform(grip=(8, 4, 8), at=fist(), turn=turn(x=-15, z=-10))
    return displays(m, held)


def cane():
    """강아지 할아버지: his walking stick, the crook in the fist, the shaft planted on the floor between his feet (a dark
    varnished wood, a black rubber ferrule)."""
    m = BModel("bus_hold_cane", floor=None)
    wood = _plain("wood_dark", 2, kind="grain", rim=1.3)
    m.box("shaft", (7.25, -16, 7.25), (8.75, 10, 8.75), wood)
    m.box("crook", (7.25, 10, 7.25), (13.5, 11.75, 8.75), wood)
    m.box("crook", (11.75, 7, 7.25), (13.5, 10, 8.75), wood)
    m.box("crook", (12, 6, 7.5), (13.25, 7, 8.5), _plain("wood_dark", 1))
    m.box("collar", (7, 8.5, 7), (9, 9.25, 9), _plain("brass", 2))
    m.box("ferrule", (7, -16, 7), (9, -13.5, 9), _plain("rubber", 1))
    held = hold_transform(grip=(8, 9.5, 8), at=fist(0, 0.5), turn=turn(x=-8))
    return displays(m, held)


POLE_Z = 16


def trekking_pole():
    """등산객: a trekking pole, the grip in the fist (a black handle, a red wrist strap), the shaft slanting down and out to
    the floor beside the boot with a little basket and a carbide tip."""
    m = BModel("bus_hold_pole", floor=None)
    m.box("grip", (6.5, 4, 6.5), (9.5, 12, 9.5), _plain("rubber", 2, kind="speckle"))
    m.box("cap", (6.25, 12, 6.25), (9.75, 13, 9.75), _plain("tomato", 2))
    m.box("strap", (9.5, 5, 7.5), (11, 12.5, 8.5), _plain("tomato", 1))
    m.box("shaft", (7, -10, 7), (9, 4, 9), _plain("tomato", 2, kind="metal", rim=1.4))
    m.box("shaft", (7.25, -16, 7.25), (8.75, -10, 8.75), _plain("blade", 2, kind="metal"))
    m.box("lock", (6.75, -10.5, 6.75), (9.25, -9.5, 9.25), _plain("rubber", 1))
    m.box("basket", (5.25, -14, 5.25), (10.75, -13, 10.75), _plain("rubber", 1))
    held = hold_transform(grip=(8, 8, 8), at=fist(), turn=turn(x=-14, z=POLE_Z))
    return displays(m, held)


def fishing_rod():
    """낚시꾼: a short telescopic rod held up beside him, slanting back over the shoulder: a cork handle, a spinning reel
    under it, the blank tapering to a red tip, the line hanging from the tip with an orange float."""
    m = BModel("bus_hold_rod", floor=None)
    m.box("butt", (7, -4, 7), (9, -1, 9), _plain("rubber", 2))
    m.box("cork", (7, -1, 7), (9, 7, 9), _plain("straw", 2, kind="speckle", noise=0.25))
    m.box("seat", (7.25, 7, 7.25), (8.75, 10, 8.75), _plain("blade", 2, kind="metal"))
    m.box("blank", (7.4, 10, 7.4), (8.6, 22, 8.6), _plain("indigo", 2, rim=1.3))
    m.box("blank", (7.6, 22, 7.6), (8.4, 31, 8.4), _plain("indigo", 3))
    m.box("tip", (7.6, 31, 7.6), (8.4, 32, 8.4), _plain("tomato", 2))
    for y in (14, 20, 26):
        m.box("guide", (7.75, y, 6.5), (8.25, y + 0.75, 7.4), _plain("blade", 3))
    # the reel hanging under the seat (in front of the rod: north)
    m.box("reel_foot", (7.6, 8, 6), (8.4, 9, 7.25), _plain("blade", 2))
    m.box("reel", (6.25, 6.5, 3.5), (9.75, 10.5, 6), _plain("iron", 3, kind="metal", rim=1.3))
    m.box("spool", (6.75, 10.5, 4), (9.25, 11.5, 5.5), _plain("blade", 3))
    m.box("handle", (9.75, 8, 4.25), (11.25, 8.75, 5.25), _plain("rubber", 2))
    m.box("knob", (11.25, 7.25, 4.25), (12, 9.5, 5.25), _plain("rubber", 1))
    # the line down from the tip and its float
    m.box("line", (7.9, 22.5, 7.9), (8.1, 31, 8.1), _plain("porcelain", 2))
    m.box("float", (7.4, 20.5, 7.4), (8.6, 22.5, 8.6), _plain("fox", 3, decal=lambda c: ("porcelain", 2) if c.p[1] > 21.75 else None))
    held = hold_transform(grip=(8, 3, 8), at=fist(), turn=turn(x=28, z=-12))
    return displays(m, held)


def parcel():
    """택배기사: a cardboard parcel held on his lap at his side: brown tape across the top and down the sides, a white
    shipping label with a barcode, a red 취급주의 mark (no lettering, no logo)."""
    m = BModel("bus_hold_parcel", floor=None)

    def box_art(c):
        x, y, z = c.p
        if abs(x - 8) < 0.9 and c.face in ("up", "north", "south"):
            return ("doenjang", 3)                            # the tape
        if c.face == "north" and 3.5 <= x <= 7 and 3 <= y <= 7:
            if 3.75 <= x <= 6.75 and 3.5 <= y <= 4.5 and int(x * 4) % 2:
                return ("raw", (30, 30, 34, 255))             # the barcode
            return ("paper", 3)                               # the label
        if c.face == "north" and 9.5 <= x <= 12 and 5 <= y <= 7.5:
            return ("tomato", 2) if (9.75 <= x <= 11.75 and 5.25 <= y <= 7.25) and not (10.25 < x < 11.25 and 5.75 < y < 6.75) else None
        if c.face == "west" and abs(z - 8) < 0.9:
            return ("doenjang", 3)
        if c.face == "east" and abs(z - 8) < 0.9:
            return ("doenjang", 3)
        return None
    m.box("box", (2, 0, 3.5), (14, 9, 12.5), _plain("doenjang", 2, kind="paper", rim=1.2, decal=box_art))
    held = hold_transform(grip=(8, 0.5, 8), at=fist(-0.5, 0, -0.5), turn=turn(y=-12))
    return displays(m, held)


def bungeo_bag():
    """붕어빵 장수: a brown paper bag of 붕어빵, its top rolled open, two golden fish-breads standing in it (one head up with
    its eye and gills, one tail up), a grease spot, a little fish stamped on the bag."""
    m = BModel("bus_hold_bungeo_bag", floor=None)
    cells = {}
    for x in range(3, 13):
        for y in range(0, 11):
            for z in range(5, 11):
                if x in (3, 12) and z in (5, 10):
                    continue
                cells[(x, y, z)] = "bag"
    for x in range(3, 13):
        for z in range(5, 11):
            if x in (3, 12) or z in (5, 10):
                cells[(x, 11, z)] = "rim"                     # the rolled top
    # the fish-breads in profile (x across, y up), 2 thick: a round head (up) and a forked tail (up)
    head_up = ["..##..", ".####.", "######", "######", "######", "######", "######"]
    tail_up = ["##..##", "##..##", ".####.", "..##..", ".####.", "######", "######"]
    for x0, z0, prof in ((3, 6, head_up), (7, 8, tail_up)):
        for row, line in enumerate(prof):
            y = 16 - row
            for i, ch in enumerate(line):
                if ch == "#":
                    for z in (z0, z0 + 1):
                        cells[(x0 + i, y, z)] = "fish"

    def bag(c):
        x, y, z = c.p
        if c.face == "north" and math.hypot(x - 10, y - 3) < 1.3:
            return ("doenjang", 1)                            # a grease spot
        if c.face == "north" and 4.5 <= x <= 8.5 and 5 <= y <= 7.5 and not (x > 8 and abs(y - 6.25) < 0.5):
            return ("fox", 2)                                 # the stamped fish
        return None

    def fish(c):
        x, y, z = c.p
        if c.face in ("north", "south") and 13.75 <= y <= 14.75 and 6 <= x <= 7 and z < 8:
            return ("raw", (60, 34, 20, 255))                 # the eye
        if c.face in ("north", "south") and 12.25 <= y <= 12.75 and z < 8:
            return ("corn", 0)                                # the gills
        if c.face in ("north", "south") and int(y) % 2 == 0 and y < 12 and (int(x) + int(y)) % 3 == 0:
            return ("corn", 1)                                # the scales
        return None
    paints = {"bag": _plain("doenjang", 3, kind="paper", rim=1.2, decal=bag), "rim": _plain("doenjang", 2, kind="paper"),
              "fish": _plain("corn", 2, rim=1.4, decal=fish)}
    voxels(m, "bag", cells, lambda lab: paints[lab])
    held = hold_transform(grip=(8, 4, 8), at=fist(), turn=turn(x=-8))
    return displays(m, held)


def bundle():
    """시장 아줌마: a 보따리, a square cloth bundle tied at the top, held by the knot: its two
    knotted ears standing up (a blue cloth with little white flowers, so it never melts into her flowery 몸빼)."""
    m = BModel("bus_hold_bundle", floor=None)
    cells = {}
    for x in range(1, 16):
        for y in range(-9, 4):
            for z in range(2, 14):
                if _ell((x + 0.5, y + 0.5, z + 0.5), (8, -3, 8), (7, 6.5, 5.8)):
                    cells[(x, y, z)] = "cloth"
    for y in range(3, 7):
        for x in range(6, 10):
            for z in range(6, 10):
                if y <= 4 or (x in (7, 8) and z in (7, 8)):
                    cells[(x, y, z)] = "knot"
    for (x, z) in ((5, 7), (4, 7), (10, 8), (11, 8)):
        for y in (6, 7):
            cells[(x, y + (1 if x in (4, 11) else 0), z)] = "knot"   # the ears

    def flowers(c):
        x, y, z = (math.floor(v) for v in c.p)
        k = h3(x // 3, y // 3, z // 3, 11)
        fx, fy, fz = (math.floor(v) % 3 for v in c.p)
        if k < 0.55 and (fx, fy) in ((1, 1),) or (k < 0.55 and (fz, fy) == (1, 1)):
            return ("petal_yellow", 1)                        # a flower's middle
        if k < 0.55 and ((fx == 1) != (fy == 1)) and (fx == 1 or fy == 1) and (fz == 1 or c.face in ("north", "south")):
            return ("porcelain", 2)
        return None
    paints = {"cloth": _plain("sapphire", 1, kind="weave", rim=0.9, decal=flowers), "knot": _plain("sapphire", 0, kind="weave", decal=flowers)}
    voxels(m, "bundle", cells, lambda lab: paints[lab])
    held = hold_transform(grip=(8, 5, 8), at=fist(0, 0, 0), turn=turn())
    return displays(m, held)


ALL = {
    "bus_hold_chicken": chicken,
    "bus_hold_chicken_flap": chicken_flap,
    "bus_hold_folder": folder,
    "bus_hold_yeot_scissors": yeot_scissors,
    "bus_hold_map": tourist_map,
    "bus_hold_selfie": selfie,
    "bus_hold_snack": snack,
    "bus_hold_bells": bells,
    "bus_hold_fan": fan,
    "bus_hold_leash": leash,
    "bus_hold_towel": towel,
    "bus_hold_plastic_bag": plastic_bag,
    "bus_hold_shoe_bag": shoe_bag,
    "bus_hold_bouquet": bouquet,
    "bus_hold_wand": wand,
    "bus_hold_cards": cards,
    "bus_hold_rose": rose,
    "bus_hold_cane": cane,
    "bus_hold_pole": trekking_pole,
    "bus_hold_rod": fishing_rod,
    "bus_hold_parcel": parcel,
    "bus_hold_bungeo_bag": bungeo_bag,
    "bus_hold_bundle": bundle,
}
