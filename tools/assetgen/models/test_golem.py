"""
TEST boss "test_golem": one model per bone. Geometry is written in the boss's assembly space
(model units, origin = ground centre between the feet, front = -z) and each bone model is shifted so its
pivot lands on (8, 8, 8), the point the plugin rotates it around. The pivots come from the boss
definition (the test fixture plugin/src/test/fixtures/stsv/bosses/test.yml), so model and animation can never
disagree.

A bone can reach 24 units (1.5 blocks) from its pivot in every direction (the -16..32 model limit), so
the golem is split where a real joint is anyway: legs, body, head, upper arms, forearms.
Texel size stays 1/16 block, the same as every other STSV model.
"""

from pathlib import Path

import yaml

from stsv_assets import presets
from stsv_assets.model import Model

BOSS_FILE = Path(__file__).resolve().parents[3] / "plugin" / "src" / "test" / "fixtures" / "stsv" / "bosses" / "test.yml"


def _bones():
    data = yaml.safe_load(BOSS_FILE.read_text(encoding="utf-8"))
    return {name: b["pivot"] for name, b in data["test_golem"]["model"]["bones"].items()}


PIVOTS = _bones()


class Bone(Model):
    """A Model whose boxes are given in assembly space; stored relative to the bone pivot."""

    def __init__(self, name, bone):
        super().__init__(f"test_golem_{name}")
        self.pivot = PIVOTS[bone]
        self.bone = bone
        self.display = presets.furniture(gui_scale=0.4)

    def at(self, name, frm, to, tex, **kw):
        px, py, pz = self.pivot
        f = (frm[0] - px + 8, frm[1] - py + 8, frm[2] - pz + 8)
        t = (to[0] - px + 8, to[1] - py + 8, to[2] - pz + 8)
        rot = kw.pop("rotation", None)
        if rot:
            axis, angle, origin = rot
            rot = (axis, angle, (origin[0] - px + 8, origin[1] - py + 8, origin[2] - pz + 8))
        return self.box(name, f, t, tex, rotation=rot, **kw)

    def sided(self, name, x, rest_from, rest_to, s, tex, **kw):
        """Box on one side: x = (inner, outer) distance from the centre line, s = +1 right / -1 left."""
        a, b = s * x[0], s * x[1]
        return self.at(name, (min(a, b),) + tuple(rest_from), (max(a, b),) + tuple(rest_to), tex, **kw)


# ------------------------------------------------------------------------------------------------

def _body(alert):
    m = Bone("body_alert" if alert else "body", "body")
    core = "dev_screen_alert" if alert else "dev_screen"
    m.at("pelvis", (-9, 17, -5), (9, 23, 5), "dev_grey")
    m.at("waist", (-7, 23, -4.5), (7, 28, 4.5), "dev_stripe")
    m.at("belly_plate", (-8, 24, -5.5), (8, 27, -4.5), "dev_orange")
    m.at("chest", (-14, 28, -7), (14, 45, 7), "dev_orange")
    m.at("chest_top", (-13, 45, -6), (13, 46.5, 6), "dev_grey")
    m.at("collar", (-7, 46.5, -5), (7, 47.5, 4), "dev_grey")
    # glowing core in a bezel
    m.at("bezel_t", (-6, 42, -8), (6, 43, -7), "dev_grey")
    m.at("bezel_b", (-6, 32, -8), (6, 33, -7), "dev_grey")
    m.at("bezel_l", (-6, 33, -8), (-5, 42, -7), "dev_grey")
    m.at("bezel_r", (5, 33, -8), (6, 42, -7), "dev_grey")
    m.at("core", (-5, 33, -7.5), (5, 42, -7), core, glow=True, full_uv=["north"])
    # back: vent plate and two exhaust stacks
    m.at("vent", (-8, 30, 7), (8, 38, 8), "dev_stripe")
    for s in (1, -1):
        m.sided(f"stack_{'r' if s > 0 else 'l'}", (5, 9), (36, 7), (48, 11), s, "dev_grey")
        m.sided(f"stack_cap_{'r' if s > 0 else 'l'}", (4.5, 9.5), (48, 6.5), (50, 11.5), s, core, glow=True)
    return m


def _head(alert):
    m = Bone("head_alert" if alert else "head", "head")
    visor = "dev_visor_alert" if alert else "dev_visor"
    tip = "dev_screen_alert" if alert else "dev_screen"
    m.at("neck", (-4, 46, -4), (4, 48, 3), "dev_stripe")
    m.at("skull", (-6.5, 48, -7.5), (6.5, 58, 4.5), "dev_grey")
    m.at("brow", (-7, 55, -8.5), (7, 57, -7.5), "dev_orange")
    m.at("chin", (-5.5, 48.5, -8.5), (5.5, 49.5, -7.5), "dev_orange")
    m.at("visor", (-4.5, 50, -8), (4.5, 54, -7.5), visor, glow=True, uv={"north": [3.5, 6, 12.5, 10]})
    for s in (1, -1):
        m.sided(f"ear_{'r' if s > 0 else 'l'}", (6.5, 7.5), (50, -4), (54, 0), s, "dev_orange")
    m.at("antenna", (3, 58, -1), (4, 62, 0), "dev_grey")
    m.at("antenna_tip", (2.5, 62, -1.5), (4.5, 64, 0.5), tip, glow=True, full_uv=["north", "south", "east", "west", "up"])
    return m


def _arm(side):
    s = 1 if side == "r" else -1
    m = Bone(f"arm_{side}", f"arm_{side}")
    m.sided("pad", (13, 24), (40, -6.5), (49, 6.5), s, "dev_orange")
    m.sided("pad_band", (13, 24), (45, -7), (47, -6.5), s, "dev_stripe")
    m.sided("pad_rim", (12.5, 23.5), (49, -6), (50, 6), s, "dev_grey")
    m.sided("upper", (14.5, 20.5), (29, -3), (40, 3), s, "dev_grey")
    return m


def _forearm(side):
    s = 1 if side == "r" else -1
    m = Bone(f"forearm_{side}", f"forearm_{side}")
    m.sided("elbow", (14.5, 21.5), (27, -3.5), (31, 3.5), s, "dev_stripe")
    m.sided("forearm", (13.5, 22.5), (17, -4.5), (27, 4.5), s, "dev_orange")
    m.sided("ridge", (13, 23), (20, -5), (23, 5), s, "dev_grey")
    m.sided("fist", (13, 23), (6, -5.5), (17, 5.5), s, "dev_grey")
    m.sided("knuckles", (13.5, 22.5), (7, -6.5), (13, -5.5), s, "dev_orange")
    return m


def _leg(side):
    s = 1 if side == "r" else -1
    m = Bone(f"leg_{side}", f"leg_{side}")
    m.sided("hip", (3.5, 10.5), (17, -3.5), (21, 3.5), s, "dev_stripe")
    m.sided("thigh", (3, 11), (10, -4), (17, 4), s, "dev_grey")
    m.sided("knee", (3, 11), (9, -5), (15, -4), s, "dev_orange")
    m.sided("shin", (2.5, 11.5), (3, -4.5), (10, 4.5), s, "dev_orange")
    m.sided("foot", (2, 12), (0, -7), (3, 4), s, "dev_grey")
    m.sided("toe", (2.5, 11.5), (0.5, -7.5), (2.5, -7), s, "dev_orange")
    return m


def test_golem_rock():
    """Thrown boulder, centred on (8, 8, 8) so it tumbles around its middle."""
    m = Model("test_golem_rock")
    m.box("core", (3, 3, 3), (13, 13, 13), "dev_grey")
    m.box("bump_up", (4, 13, 5), (11, 15, 11), "dev_grey")
    m.box("bump_e", (13, 5, 4), (14.5, 11, 10), "dev_grey")
    m.box("bump_w", (1.5, 6, 6), (3, 12, 12), "dev_grey")
    m.box("bump_down", (5, 1.5, 4), (12, 3, 11), "dev_grey")
    m.box("crack_n", (5, 6, 2.5), (11, 8, 3), "dev_orange", glow=True)
    m.box("crack_s", (6, 8, 13), (10, 10, 13.5), "dev_orange", glow=True)
    m.display = presets.furniture(gui_scale=0.6)
    return m


ALL = {
    "test_golem_body": lambda: _body(False),
    "test_golem_body_alert": lambda: _body(True),
    "test_golem_head": lambda: _head(False),
    "test_golem_head_alert": lambda: _head(True),
    "test_golem_arm_r": lambda: _arm("r"),
    "test_golem_arm_l": lambda: _arm("l"),
    "test_golem_forearm_r": lambda: _forearm("r"),
    "test_golem_forearm_l": lambda: _forearm("l"),
    "test_golem_leg_r": lambda: _leg("r"),
    "test_golem_leg_l": lambda: _leg("l"),
    "test_golem_rock": test_golem_rock,
}

# bones in their rest pose, for the assembled preview: (model, bone)
ASSEMBLY = [("test_golem_body", "body"), ("test_golem_head", "head"), ("test_golem_arm_r", "arm_r"),
            ("test_golem_arm_l", "arm_l"), ("test_golem_forearm_r", "forearm_r"),
            ("test_golem_forearm_l", "forearm_l"), ("test_golem_leg_r", "leg_r"), ("test_golem_leg_l", "leg_l")]
