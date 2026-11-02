"""
TEST boss "test_executioner": an armoured knight with a 2.6-block greatsword. Bones and pivots come
from content/stsv/bosses/executioner.yml; geometry is written in assembly space (origin on the ground
between the feet, front = -z, the boss's right = +x).

The sword is built as a straight continuation of the right arm (blade pointing down, edge facing
forward/back), so "sword rot X = +90" holds it out level, and arm + sword angles add up intuitively.
Its blade is split at the 1.5-block bone limit into sword + sword_tip.

Phase looks: *_lit (blade edges glow red) and head_alert (red eye slit).
"""

from stsv_assets.rig import Bone, boss

SPEC = boss("executioner.yml", "test_executioner")
PIVOTS = {name: b["pivot"] for name, b in SPEC["model"]["bones"].items()}


def _bone(model, bone):
    return Bone(f"test_executioner_{model}", PIVOTS[bone])


def _body():
    m = _bone("body", "body")
    m.at("belt", (-7, 19, -4), (7, 24, 4), "dev_grey")
    m.at("buckle", (-2, 20, -5), (2, 23, -4), "dev_orange")
    m.at("tasset_l", (-6.5, 14, -4.5), (-0.5, 20, -3.5), "dev_purple")
    m.at("tasset_r", (0.5, 14, -4.5), (6.5, 20, -3.5), "dev_purple")
    m.at("tasset_back", (-6.5, 14, 3.5), (6.5, 20, 4.5), "dev_purple")
    m.at("torso", (-8, 24, -4.5), (8, 34, 4.5), "dev_grey")
    m.at("chest", (-9, 34, -5), (9, 43, 5), "dev_grey")
    m.at("plastron", (-6, 29, -5.5), (6, 41, -5), "dev_orange")
    m.at("gorget", (-6, 43, -4), (6, 46, 4), "dev_grey")
    return m


def _head(alert):
    m = _bone("head_alert" if alert else "head", "head")
    eyes = "dev_visor_alert" if alert else "dev_visor"
    m.at("helm", (-4.5, 46, -5), (4.5, 56, 4), "dev_grey")
    m.at("faceplate", (-4, 47, -5.5), (4, 53, -5), "dev_orange")
    m.at("slit", (-3.5, 50.5, -6), (3.5, 51.5, -5.5), eyes, glow=True, uv={"north": [4.5, 7, 11.5, 8]})
    m.at("crest", (-1, 56, -4), (1, 60, 3), "dev_orange")
    m.at("plume", (-1, 54, 4), (1, 59, 7), "dev_purple")
    return m


def _cape():
    m = _bone("cape", "cape")
    m.at("cape", (-7, 21, 5.5), (7, 44, 6.5), "dev_purple")
    m.at("clasp_l", (-7.5, 41.5, 5), (-5.5, 43.5, 7), "dev_orange")
    m.at("clasp_r", (5.5, 41.5, 5), (7.5, 43.5, 7), "dev_orange")
    return m


def _arm(side):
    s = 1 if side == "r" else -1
    m = _bone(f"arm_{side}", f"arm_{side}")
    m.sided("pauldron", (7.5, 15), (38, -5), (44, 5), s, "dev_grey")
    m.sided("pauldron_rim", (7, 15.5), (44, -5.5), (45.5, 5.5), s, "dev_orange")
    m.sided("upper", (8.5, 13.5), (30, -2.5), (38, 2.5), s, "dev_purple")
    return m


def _forearm(side):
    s = 1 if side == "r" else -1
    m = _bone(f"forearm_{side}", f"forearm_{side}")
    m.sided("vambrace", (8, 14), (22, -3), (31, 3), s, "dev_grey")
    m.sided("cuff", (7.5, 14.5), (21.5, -3.5), (24, 3.5), s, "dev_orange")
    m.sided("fist", (8.5, 13.5), (17, -2.5), (22, 2.5), s, "dev_grey")
    return m


def _sword(lit):
    m = _bone("sword_lit" if lit else "sword", "sword")
    edge = "dev_screen_alert" if lit else "blade_steel"
    m.at("pommel", (10, 25, -1), (12, 27, 1), "dev_orange")
    m.at("grip", (10.5, 17, -0.75), (11.5, 25, 0.75), "dev_purple")
    m.at("guard", (9.5, 15, -4), (12.5, 17, 4), "dev_orange")
    m.at("blade", (10.25, -4, -2.5), (11.75, 15, 2.5), "dev_grey")
    m.at("edge_f", (10.5, -4, -3), (11.5, 15, -2.5), edge, glow=lit)
    m.at("edge_b", (10.5, -4, 2.5), (11.5, 15, 3), edge, glow=lit)
    return m


def _sword_tip(lit):
    m = _bone("sword_tip_lit" if lit else "sword_tip", "sword_tip")
    edge = "dev_screen_alert" if lit else "blade_steel"
    m.at("blade", (10.25, -22, -2.5), (11.75, -4, 2.5), "dev_grey")
    m.at("edge_f", (10.5, -22, -3), (11.5, -4, -2.5), edge, glow=lit)
    m.at("edge_b", (10.5, -22, 2.5), (11.5, -4, 3), edge, glow=lit)
    m.at("tip", (10.5, -25, -1.5), (11.5, -22, 1.5), edge, glow=lit)
    m.at("point", (10.75, -27, -0.5), (11.25, -25, 0.5), edge, glow=lit)
    return m


def _leg(side):
    s = 1 if side == "r" else -1
    m = _bone(f"leg_{side}", f"leg_{side}")
    m.sided("thigh", (2, 7), (12, -2.75), (21, 2.75), s, "dev_purple")
    m.sided("knee", (1.5, 7.5), (10, -3.75), (14, -2.75), s, "dev_orange")
    m.sided("greave", (1.75, 7.25), (3, -3), (12, 3), s, "dev_grey")
    m.sided("boot", (1.5, 7.5), (0, -4.5), (3, 3), s, "dev_grey")
    m.sided("toe", (2, 7), (0.5, -5), (2.5, -4.5), s, "dev_orange")
    return m


ALL = {
    "test_executioner_body": _body,
    "test_executioner_head": lambda: _head(False),
    "test_executioner_head_alert": lambda: _head(True),
    "test_executioner_cape": _cape,
    "test_executioner_arm_r": lambda: _arm("r"),
    "test_executioner_arm_l": lambda: _arm("l"),
    "test_executioner_forearm_r": lambda: _forearm("r"),
    "test_executioner_forearm_l": lambda: _forearm("l"),
    "test_executioner_sword": lambda: _sword(False),
    "test_executioner_sword_lit": lambda: _sword(True),
    "test_executioner_sword_tip": lambda: _sword_tip(False),
    "test_executioner_sword_tip_lit": lambda: _sword_tip(True),
    "test_executioner_leg_r": lambda: _leg("r"),
    "test_executioner_leg_l": lambda: _leg("l"),
}


def model_of(bone, lit=False):
    if lit and bone in ("sword", "sword_tip"):
        return f"test_executioner_{bone}_lit"
    return f"test_executioner_{bone}"
