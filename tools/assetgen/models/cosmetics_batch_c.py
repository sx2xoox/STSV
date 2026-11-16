"""
Cosmetics batch C: the eight 허리 (waist) cosmetics (content/stsv/items/cosmetics_batch_c.yml). The trails, kill
effects and auras of the same batch are particles only (no model).

Frame: like every attached cosmetic (models/cosmetics.py): an item display riding the player, item transform
NONE, the model's (8, 8, 8) on the slot's anchor, north (-z) = the player's front, south (+z) = behind them,
+x = the player's right, 1 unit = 1/16 block. The waist anchor (config cosmetics.anchors.waist [0, -1.00, 0]:
1.00 block below the top of the 1.8 hitbox) is 0.80 block above the feet. The player model is drawn at 15/16,
so its body (torso) runs from 11.25 to 22.5 units above the feet: in this frame the torso's bottom edge (where
the legs start) is at y 6.45, and the torso is x 4.25..11.75 by z 6.125..9.875. Belts here sit on that edge
(y about 5.8..7.6), a little outside the torso (x 4.0..12.0, z 5.85..10.15); things hang from them.

WAIST: id -> slot, for the preview sheet.
"""

from economy_machines import MModel, Mat, cyl
from models.cosmetics import EYE, GOLD, GOLD_LIGHT, _display, mirror

HIP_GUI = (30, 225, 0)        # front 3/4 (the side a waist piece is mostly seen from)

# the belt line: just outside the torso, straddling where the legs start
BX0, BX1, BZ0, BZ1 = 4.0, 12.0, 5.85, 10.15


def belt(m, name, y0, y1, mat, front=None):
    """A strap round the waist, y0..y1 high (one box: its inside is inside the body)."""
    m.box(name, (BX0, y0, BZ0), (BX1, y1, BZ1), mat, faces={"north": front} if front is not None else None)


# ================================================================================================
# 일반
# ================================================================================================

def hip_pouch():
    """허리 가방: a sunny yellow nylon bum bag worn in front on a black strap, a silver zip with a red pull,
    a smaller front pocket and the strap's clip on the right."""
    m = MModel("hip_pouch")
    strap = Mat("rubber", "weave", 1)
    nylon, nylon_dark = Mat("petal_yellow", "plain", 2), Mat("petal_yellow", "plain", 1)
    m.group("strap")
    belt(m, "strap", 6.4, 7.4, strap)
    m.box("clip", (11.6, 6.1, 6.6), (12.6, 7.7, 8.4), Mat("rubber", "plain", 2))
    m.group("bag")
    m.box("bag", (5.0, 4.4, 4.4), (11.0, 8.6, 5.9), nylon)
    m.box("bag_end", (4.4, 4.9, 4.7), (5.0, 8.1, 5.9), nylon_dark)
    m.box("bag_end", (11.0, 4.9, 4.7), (11.6, 8.1, 5.9), nylon_dark)
    m.box("pocket", (5.9, 4.7, 4.0), (10.1, 6.6, 4.4), nylon_dark)
    m.box("zip", (5.2, 7.7, 4.3), (10.8, 8.0, 4.4), Mat("steel", "metal", 4))
    m.box("zip_pull", (9.6, 7.0, 4.0), (10.2, 7.9, 4.3), Mat("signal_red", "plain", 1))
    m.box("pocket_zip", (6.1, 6.3, 3.9), (9.9, 6.5, 4.0), Mat("steel", "metal", 4))
    _display(m, HIP_GUI, 1.1, 1)
    return m


def lucky_pouch():
    """복주머니: a red silk lucky pouch on a braided gold cord, hanging at the right hip: a round bottom, a pinched
    neck, the ruffled top, the knotted drawstring and a gold 복 diamond on its front."""
    m = MModel("lucky_pouch")
    silk, silk_dark, silk_light = Mat("crimson", "plain", 2), Mat("crimson", "plain", 1), Mat("crimson", "plain", 3)
    cord = Mat("gold", "weave", 2)
    m.group("cord")
    belt(m, "cord", 6.7, 7.2, cord)
    m.group("pouch")
    x0, x1, z0, z1 = 8.9, 12.9, 4.0, 7.2
    m.box("pouch", (x0 + 0.5, 1.6, z0 + 0.5), (x1 - 0.5, 2.2, z1 - 0.5), silk_dark)
    m.box("pouch", (x0, 2.2, z0), (x1, 4.6, z1), silk)
    m.box("pouch", (x0 + 0.4, 4.6, z0 + 0.4), (x1 - 0.4, 5.2, z1 - 0.4), silk)
    m.box("neck", (x0 + 1.0, 5.2, z0 + 1.0), (x1 - 1.0, 5.8, z1 - 1.0), silk_dark)
    m.box("frill", (x0 + 0.5, 5.8, z0 + 0.5), (x1 - 0.5, 6.7, z1 - 0.5), silk_light)
    m.box("knot", (10.4, 5.2, 3.8), (11.4, 6.0, 4.6), GOLD_LIGHT)
    m.box("tassel", (10.6, 3.6, 3.7), (11.2, 5.2, 4.1), GOLD)
    m.box("bok", (10.3, 3.1, 3.85), (11.5, 4.3, 4.0), GOLD_LIGHT, rotation=("z", 45, (10.9, 3.7, 3.9)))
    _display(m, HIP_GUI, 1.1, 2)
    return m


def canteen():
    """물통: an olive army canteen in its felt cover, hanging at the right hip from a brown belt: the black screw
    cap on a short chain, the cover's snap and a stitched seam."""
    m = MModel("canteen")
    leather = Mat("leather", "plain", 1)
    cover, cover_dark = Mat("bottle_green", "speckle", 1), Mat("bottle_green", "plain", 0)
    m.group("belt")
    belt(m, "belt", 6.2, 7.3, leather)
    m.box("buckle", (7.2, 6.0, 5.6), (8.8, 7.5, 5.85), Mat("brass", "metal", 2))
    m.group("canteen")
    # a flat oval bottle lying against the hip: its round side faces outward (+x)
    cyl(m, "bottle", "x", (3.9, 8.6), 5.0, 12.1, 14.3, cover)
    m.box("seam", (14.3, 1.6, 8.4), (14.45, 6.2, 8.8), cover_dark)
    m.box("neck", (12.7, 6.4, 7.9), (13.7, 7.4, 9.3), Mat("steel", "metal", 3))
    m.box("cap", (12.5, 7.4, 7.7), (13.9, 8.4, 9.5), Mat("rubber", "plain", 1))
    m.box("chain", (13.9, 6.9, 8.4), (14.2, 8.0, 8.8), Mat("steel", "metal", 4))
    m.box("snap", (14.3, 5.0, 8.3), (14.6, 5.6, 8.9), Mat("brass", "metal", 3))
    m.box("hanger", (12.2, 6.0, 9.6), (13.0, 7.5, 10.4), leather)
    _display(m, (30, 135, 0), 1.0, 2)
    return m


# ================================================================================================
# 희귀
# ================================================================================================

def toy_sword_belt():
    """장난감 칼과 칼집: a red leather belt with a big star buckle and a toy sword in a blue scabbard on the left
    hip, slanting back: a gold cross guard, a striped grip and a round red pommel stick up in front."""
    m = MModel("toy_sword_belt")
    leather, scab, scab_dark = Mat("fabric_red", "plain", 1), Mat("sapphire", "plain", 2), Mat("sapphire", "plain", 1)
    m.group("belt")
    belt(m, "belt", 6.2, 7.4, leather)
    m.box("buckle", (7.0, 5.9, 5.55), (9.0, 7.7, 5.85), GOLD_LIGHT)
    m.box("buckle_star", (7.6, 6.5, 5.45), (8.4, 7.1, 5.55), Mat("ruby", "plain", 3), rotation=("z", 45, (8, 6.8, 5.5)))
    m.group("sword")
    rot = ("x", -22.5, (3.2, 6.8, 7.0))       # tip down and back, hilt up and forward
    m.box("frog", (2.9, 5.9, 6.2), (3.9, 7.7, 7.8), leather)
    m.box("scabbard", (2.5, -1.6, 6.3), (3.7, 6.4, 7.7), scab, rotation=rot)
    m.box("chape", (2.4, -2.4, 6.2), (3.8, -1.6, 7.8), GOLD, rotation=rot)
    m.box("throat", (2.4, 5.8, 6.2), (3.8, 6.6, 7.8), GOLD, rotation=rot)
    m.box("star", (2.35, 2.0, 6.6), (2.5, 3.0, 7.4), Mat("lamp", "plain", 3), rotation=rot)
    m.box("guard", (2.2, 6.6, 5.4), (4.0, 7.2, 8.6), GOLD_LIGHT, rotation=rot)
    m.box("grip", (2.65, 7.2, 6.55), (3.55, 9.4, 7.45), scab_dark, rotation=rot)
    m.box("grip_band", (2.6, 8.0, 6.5), (3.6, 8.4, 7.5), Mat("feather", "plain", 3), rotation=rot)
    m.box("pommel", (2.5, 9.4, 6.4), (3.7, 10.4, 7.6), Mat("ruby", "plain", 2), rotation=rot)
    _display(m, (30, 250, 0), 0.95, 0)
    return m


def key_ring():
    """열쇠 꾸러미: a brown belt with a big brass key ring clipped at the right hip: four keys (brass, silver,
    bronze, a blue-capped one), a little red plastic tag and a smiling round charm."""
    m = MModel("key_ring")
    leather = Mat("leather", "plain", 2)
    brass, silver, bronze = Mat("brass", "metal", 3), Mat("steel", "metal", 4), Mat("copper", "metal", 2)
    m.group("belt")
    belt(m, "belt", 6.3, 7.4, leather)
    m.box("loop", (12.0, 5.8, 6.4), (12.5, 7.9, 7.6), leather)
    m.box("clip", (12.5, 4.6, 6.7), (12.9, 7.6, 7.3), silver)
    m.group("ring")
    rx, ry, rz = 12.7, 3.8, 7.0                 # the ring, upright along the hip (in the y-z plane)
    for (y0, y1, z0, z1) in ((ry + 1.4, ry + 1.8, rz - 1.0, rz + 1.0), (ry - 1.8, ry - 1.4, rz - 1.0, rz + 1.0),
                             (ry - 1.4, ry + 1.4, rz - 1.8, rz - 1.4), (ry - 1.4, ry + 1.4, rz + 1.4, rz + 1.8)):
        m.box("ring", (rx, y0, z0), (rx + 0.4, y1, z1), brass)
    m.group("keys")
    # each key a hair further out than the last so their bows (which overlap a little) never share a face
    for k, (z, top, length, mat, cap) in enumerate(((5.6, 2.55, 3.6, brass, None), (6.5, 2.35, 4.0, silver, None),
                                                    (7.6, 2.45, 3.4, bronze, None),
                                                    (8.5, 2.55, 3.8, silver, Mat("sapphire", "plain", 3)))):
        d = 0.08 * k
        m.box("key_bow", (rx - 0.1 + d, top - 1.2, z - 0.55), (rx + 0.5 + d, top, z + 0.55), cap or mat)
        m.box("key_blade", (rx + 0.05 + d, top - length, z - 0.2), (rx + 0.35 + d, top - 1.2, z + 0.2), mat)
        m.box("key_bit", (rx + 0.05 + d, top - length + 0.3, z + 0.2), (rx + 0.35 + d, top - length + 1.0, z + 0.5), mat)
    m.box("tag", (rx + 0.13, 0.6, 9.0), (rx + 0.43, 2.0, 10.2), Mat("signal_red", "plain", 1))
    m.box("charm", (rx + 0.15, 1.0, 4.2), (rx + 0.55, 2.4, 5.4), Mat("lamp", "plain", 3))
    m.box("charm_eye", (rx + 0.55, 1.9, 4.5), (rx + 0.65, 2.1, 4.7), EYE)
    m.box("charm_eye", (rx + 0.55, 1.9, 4.9), (rx + 0.65, 2.1, 5.1), EYE)
    _display(m, (30, 135, 0), 1.2, 3)
    return m


def tool_belt():
    """공구 벨트: a canvas tool belt: a pouch in front on the left with a yellow screwdriver and a carpenter's
    pencil, a hammer hanging through a loop on the right, and a yellow tape measure clipped at the back."""
    m = MModel("tool_belt")
    canvas, canvas_dark = Mat("burlap", "weave", 2), Mat("burlap", "plain", 1)
    leather = Mat("leather", "plain", 1)
    m.group("belt")
    belt(m, "belt", 6.0, 7.5, canvas)
    m.box("buckle", (7.3, 5.8, 5.6), (8.7, 7.7, 5.85), Mat("steel", "metal", 3))
    m.group("pouch")
    m.box("pouch", (4.2, 3.0, 4.5), (7.2, 6.6, 5.85), canvas_dark)
    m.box("pouch_lip", (4.1, 6.2, 4.3), (7.3, 6.8, 4.6), leather)
    m.box("driver_handle", (4.8, 6.6, 4.9), (5.6, 8.6, 5.6), Mat("petal_yellow", "plain", 2))
    m.box("driver_band", (4.75, 7.4, 4.85), (5.65, 7.7, 5.65), Mat("rubber", "plain", 1))
    m.box("pencil", (6.3, 6.6, 5.0), (6.9, 9.0, 5.5), Mat("signal_red", "plain", 1))
    m.box("pencil_tip", (6.45, 9.0, 5.1), (6.75, 9.4, 5.4), Mat("wood", "plain", 3))
    m.group("hammer")
    m.box("loop", (12.0, 4.8, 6.6), (12.6, 6.4, 8.2), leather)
    m.box("handle", (12.1, 0.4, 7.1), (12.5, 5.6, 7.7), Mat("wood", "grain", 2))
    m.box("head", (11.9, 5.6, 5.9), (12.8, 6.8, 9.1), Mat("steel", "metal", 3))
    m.box("claw", (12.0, 5.8, 9.1), (12.7, 6.4, 9.9), Mat("steel", "metal", 2))
    m.group("tape")
    m.box("tape", (8.6, 4.4, 10.15), (10.8, 6.6, 11.4), Mat("petal_yellow", "plain", 2))
    m.box("tape_face", (9.1, 4.9, 11.4), (10.3, 6.1, 11.55), Mat("rubber", "plain", 1))
    m.box("tape_tab", (10.8, 4.6, 10.4), (11.1, 5.0, 11.2), Mat("steel", "metal", 4))
    _display(m, HIP_GUI, 1.0, 1)
    return m


# ================================================================================================
# 영웅
# ================================================================================================

def hula_skirt():
    """훌라 치마: a grass skirt of long green and straw strands of uneven length all round, on a woven band, with
    a big pink hibiscus at the left hip."""
    m = MModel("hula_skirt")
    green, green_dark, straw = Mat("leaf", "grain", 2), Mat("leaf", "grain", 1), Mat("straw", "grain", 3)
    m.group("band")
    belt(m, "band", 6.3, 7.4, Mat("straw", "weave", 2))
    m.group("strands")
    mats = (green, straw, green_dark, green, straw, green_dark)
    drop = (0.0, 0.8, 0.3, 1.1, 0.5, 0.0, 0.9, 0.4)
    k = 0
    # front and back: strands across x, a little outside the band
    for (z0, z1) in ((5.25, 5.85), (10.15, 10.75)):
        x = BX0
        while x < BX1 - 0.01:
            m.box("strand", (x, -1.4 + drop[k % 8], z0), (x + 1.0, 6.6, z1), mats[k % 6])
            x += 1.0
            k += 1
    # the sides: strands along z
    for (x0, x1) in ((3.4, 4.0), (12.0, 12.6)):
        z = BZ0
        while z < BZ1 - 0.01:
            m.box("strand", (x0, -1.4 + drop[k % 8], z), (x1, 6.6, min(BZ1, z + 1.075)), mats[k % 6])
            z += 1.075
            k += 1
    m.group("flower")
    petal, petal_light = Mat("pink", "plain", 2), Mat("pink", "plain", 3)
    fx, fy, fz = 3.2, 6.8, 6.4
    m.box("petal", (fx - 0.3, fy - 1.6, fz - 1.6), (fx, fy + 1.6, fz + 1.6), petal)
    m.box("petal", (fx - 0.5, fy - 1.0, fz - 1.0), (fx - 0.3, fy + 1.0, fz + 1.0), petal_light)
    m.box("pistil", (fx - 1.3, fy - 0.2, fz - 0.2), (fx - 0.5, fy + 0.2, fz + 0.2), Mat("petal_yellow", "plain", 2))
    m.box("leaf", (fx - 0.2, fy - 2.6, fz + 0.4), (fx + 0.05, fy - 1.4, fz + 2.2), green)
    _display(m, HIP_GUI, 0.9, 1)
    return m


def champion_belt():
    """챔피언 벨트: a wide red leather title belt, a big gold front plate with a silver rim and a ruby in its
    star, smaller gold side plates with laurels, and gold studs along the edges."""
    m = MModel("champion_belt")
    leather, leather_dark = Mat("fabric_red", "plain", 2), Mat("fabric_red", "plain", 1)
    silver = Mat("steel", "metal", 4)
    m.group("belt")
    belt(m, "belt", 5.9, 7.7, leather)
    for y in (5.6, 7.7):
        belt(m, "edge", y, y + 0.3, leather_dark)
    m.group("plate")
    m.box("rim", (4.6, 4.6, 5.45), (11.4, 9.0, 5.85), silver)
    m.box("plate", (5.0, 5.0, 5.25), (11.0, 8.6, 5.45), GOLD)
    m.box("plate_light", (5.6, 5.6, 5.15), (10.4, 8.0, 5.25), GOLD_LIGHT)
    m.box("star", (7.1, 6.0, 5.05), (8.9, 7.8, 5.15), GOLD, rotation=("z", 45, (8, 6.9, 5.1)))
    m.box("ruby", (7.6, 6.5, 4.95), (8.4, 7.3, 5.05), Mat("ruby", "plain", 3))
    m.group("side_plates")
    mirror(m, "side_plate", (3.6, 5.5, 6.4), (4.0, 8.1, 8.6), GOLD)
    mirror(m, "side_gem", (3.5, 6.5, 7.2), (3.6, 7.1, 7.8), Mat("sapphire", "plain", 3))
    m.group("studs")
    mirror(m, "stud", (4.05, 5.75, 5.7), (4.45, 6.25, 5.85), GOLD_LIGHT)
    mirror(m, "stud", (4.05, 7.35, 5.7), (4.45, 7.85, 5.85), GOLD_LIGHT)
    _display(m, HIP_GUI, 1.0, 1)
    return m


WAIST = ("hip_pouch", "lucky_pouch", "canteen", "toy_sword_belt", "key_ring", "tool_belt", "hula_skirt", "champion_belt")
SLOTS = {k: "waist" for k in WAIST}
ALL = {f.__name__: f for f in (hip_pouch, lucky_pouch, canteen, toy_sword_belt, key_ring, tool_belt, hula_skirt, champion_belt)}
