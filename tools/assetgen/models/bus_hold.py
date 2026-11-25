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
  bus_hold_folder         면접 청년: a navy document folder held at the side, papers showing, a clip
  bus_hold_yeot_scissors  엿장수: the big iron 엿가위, blades open in a V above the fist
  bus_hold_map            외국인 여행객: a folded tourist map held open, roads, a river, a red pin
  bus_hold_selfie         유튜버: a phone held up in a ring light, the screen lit
  bus_hold_snack          과자 부스러기 학생: an open crisp packet, crimped top, a crisp peeking out
  bus_hold_bells          무당: a bunch of brass 방울 on a handle with five-colour ribbons
  bus_hold_fan            무당: a folding fan, open, in the five colours
  bus_hold_leash          강아지 할아버지: a red lead looped round the hand, its clip hanging
  bus_hold_towel          멀미하는 아저씨: a pink hand towel bunched in the fist
  bus_hold_plastic_bag    멀미하는 아저씨: a black plastic bag hanging from the fist, just in case
  bus_hold_shoe_bag       초등학생: a blue drawstring indoor-shoe bag (실내화 주머니)
  bus_hold_bouquet        신부: a round bouquet of pink and white roses, ribbon-tied
  bus_hold_wand           마술사: a black wand with white tips
  bus_hold_cards          마술사: a fan of playing cards
  bus_hold_rose           마술사: a single red rose
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


ALL = {
    "bus_hold_chicken": chicken,
    "bus_hold_chicken_flap": chicken_flap,
}
