"""
Boss "executioner": a knight in blackened plate with a blood-red tabard and cape, gold trim and cold
glowing eyes, carrying a 2.6-block greatsword. Bones and pivots come
from content/stsv/bosses/executioner.yml; geometry is written in assembly space (origin on the ground
between the feet, front = -z, the boss's right = +x).

The sword is built as a straight continuation of the right arm (blade pointing down, edge facing
forward/back), so "sword rot X = +90" holds it out level, and arm + sword angles add up intuitively.
Its blade is split at the 1.5-block bone limit into sword + sword_tip.

Phase looks: *_lit (blade edges smoulder like embers) and head_alert (eyes turn blood red).
Materials: blacksteel (plate), chainmail, crimson cloth, leather, gold trim (accent, small areas),
polished blade with a dark fuller.
"""

from stsv_assets.rig import Bone, boss

SPEC = boss("executioner.yml", "executioner")
PIVOTS = {name: b["pivot"] for name, b in SPEC["model"]["bones"].items()}


def _bone(model, bone):
    return Bone(f"executioner_{model}", PIVOTS[bone])


def _body():
    m = _bone("body", "body")
    m.at("belt", (-7, 19, -4), (7, 24, 4), "leather_wrap")
    m.at("buckle", (-2, 20, -5), (2, 23, -4), "gold_trim")
    m.at("tasset_l", (-6.5, 14, -4.5), (-0.5, 20, -3.5), "crimson_cloth")
    m.at("tasset_r", (0.5, 14, -4.5), (6.5, 20, -3.5), "crimson_cloth")
    m.at("tasset_back", (-6.5, 14, 3.5), (6.5, 20, 4.5), "crimson_cloth")
    m.at("torso", (-8, 24, -4.5), (8, 34, 4.5), "chainmail")
    m.at("chest", (-9, 34, -5), (9, 43, 5), "blacksteel_plate")
    m.at("plastron", (-6, 29, -5.5), (6, 41, -5), "crimson_cloth")
    m.at("gorget", (-6, 43, -4), (6, 46, 4), "blacksteel_dark")
    return m


def _head(alert):
    m = _bone("head_alert" if alert else "head", "head")
    eyes = "eyes_rage" if alert else "eyes_spectral"
    m.at("helm", (-4.5, 46, -5), (4.5, 56, 4), "blacksteel_plate")
    m.at("faceplate", (-4, 47, -5.5), (4, 53, -5), "blacksteel_dark")
    m.at("slit", (-3.5, 50.5, -6), (3.5, 51.5, -5.5), eyes, glow=True, uv={"north": [4.5, 7, 11.5, 8]})
    m.at("crest", (-1, 56, -4), (1, 60, 3), "gold_trim")
    m.at("plume", (-1, 54, 4), (1, 59, 7), "crimson_cloth")
    return m


def _cape():
    m = _bone("cape", "cape")
    m.at("cape", (-7, 21, 5.5), (7, 44, 6.5), "crimson_cloth")
    m.at("clasp_l", (-7.5, 41.5, 5), (-5.5, 43.5, 7), "gold_trim")
    m.at("clasp_r", (5.5, 41.5, 5), (7.5, 43.5, 7), "gold_trim")
    return m


def _arm(side):
    s = 1 if side == "r" else -1
    m = _bone(f"arm_{side}", f"arm_{side}")
    m.sided("pauldron", (7.5, 15), (38, -5), (44, 5), s, "blacksteel_plate")
    m.sided("pauldron_rim", (7, 15.5), (44, -5.5), (45.5, 5.5), s, "gold_trim")
    m.sided("upper", (8.5, 13.5), (30, -2.5), (38, 2.5), s, "chainmail")
    return m


def _forearm(side):
    s = 1 if side == "r" else -1
    m = _bone(f"forearm_{side}", f"forearm_{side}")
    m.sided("vambrace", (8, 14), (22, -3), (31, 3), s, "blacksteel_plate")
    m.sided("cuff", (7.5, 14.5), (21.5, -3.5), (24, 3.5), s, "blacksteel_dark")
    m.sided("fist", (8.5, 13.5), (17, -2.5), (22, 2.5), s, "blacksteel_dark")
    return m


def _sword(lit):
    m = _bone("sword_lit" if lit else "sword", "sword")
    edge = "ember_edge" if lit else "blade_steel"
    m.at("pommel", (10, 25, -1), (12, 27, 1), "gold_trim")
    m.at("grip", (10.5, 17, -0.75), (11.5, 25, 0.75), "leather_wrap")
    m.at("guard", (9.5, 15, -4), (12.5, 17, 4), "blacksteel_dark")
    m.at("blade", (10.25, -4, -2.5), (11.75, 15, 2.5), "greatsword_blade")
    m.at("edge_f", (10.5, -4, -3), (11.5, 15, -2.5), edge, glow=lit)
    m.at("edge_b", (10.5, -4, 2.5), (11.5, 15, 3), edge, glow=lit)
    return m


def _sword_tip(lit):
    m = _bone("sword_tip_lit" if lit else "sword_tip", "sword_tip")
    edge = "ember_edge" if lit else "blade_steel"
    m.at("blade", (10.25, -22, -2.5), (11.75, -4, 2.5), "greatsword_blade")
    m.at("edge_f", (10.5, -22, -3), (11.5, -4, -2.5), edge, glow=lit)
    m.at("edge_b", (10.5, -22, 2.5), (11.5, -4, 3), edge, glow=lit)
    m.at("tip", (10.5, -25, -1.5), (11.5, -22, 1.5), edge, glow=lit)
    m.at("point", (10.75, -27, -0.5), (11.25, -25, 0.5), edge, glow=lit)
    return m


def _leg(side):
    s = 1 if side == "r" else -1
    m = _bone(f"leg_{side}", f"leg_{side}")
    m.sided("thigh", (2, 7), (12, -2.75), (21, 2.75), s, "chainmail")
    m.sided("knee", (1.5, 7.5), (10, -3.75), (14, -2.75), s, "blacksteel_plate")
    m.sided("greave", (1.75, 7.25), (3, -3), (12, 3), s, "blacksteel_plate")
    m.sided("boot", (1.5, 7.5), (0, -4.5), (3, 3), s, "blacksteel_dark")
    m.sided("toe", (2, 7), (0.5, -5), (2.5, -4.5), s, "blacksteel_dark")
    return m


ALL = {
    "executioner_body": _body,
    "executioner_head": lambda: _head(False),
    "executioner_head_alert": lambda: _head(True),
    "executioner_cape": _cape,
    "executioner_arm_r": lambda: _arm("r"),
    "executioner_arm_l": lambda: _arm("l"),
    "executioner_forearm_r": lambda: _forearm("r"),
    "executioner_forearm_l": lambda: _forearm("l"),
    "executioner_sword": lambda: _sword(False),
    "executioner_sword_lit": lambda: _sword(True),
    "executioner_sword_tip": lambda: _sword_tip(False),
    "executioner_sword_tip_lit": lambda: _sword_tip(True),
    "executioner_leg_r": lambda: _leg("r"),
    "executioner_leg_l": lambda: _leg("l"),
}


def model_of(bone, lit=False, alert=False):
    """Bone -> model name; lit = phase 2 sword, alert = phase 3 head."""
    if lit and bone in ("sword", "sword_tip"):
        return f"executioner_{bone}_lit"
    if alert and bone == "head":
        return "executioner_head_alert"
    return f"executioner_{bone}"
