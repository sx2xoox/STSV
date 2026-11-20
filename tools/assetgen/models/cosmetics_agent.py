"""
The 요원 K set (content/stsv/items/cosmetics_agent.yml): the cosmetics of the 마을버스 secret mission's reward (the
"수상한 선글라스 남자" easter egg), with the black sedan (models/vehicles_agent_sedan.py) and the title 비밀 요원.
Never in the gacha, no voucher, not sold anywhere: the bus module grants them. Legendary.

  agent_sunglasses  머리 (head, the helmet slot): black wayfarer sunglasses. A thick gloss-black brow bar, two
                    dark lenses with a soft blue sheen and one clean glint each, arms back along the head to the ears,
                    a fleck of gold at each hinge.
  agent_earpiece    목 (neck): the coiled earpiece wire of a security detail. A clear coiled tube from just under the
                    right ear down the back of the shoulder to a small black radio clip, a black bud at its top; a tiny
                    lapel mic on the left of the chest. (A neck piece stays under y 9.6, where the head turns on its
                    own: models/cosmetics_batch_b.py.)
  agent_coat        등 (back): the back of a black suit coat with tails. Squared shoulders, a fine back seam, two gold
                    buttons at the waist, the tails (swept back 22.5 degrees about the hips, like the galaxy cape)
                    split by a centre vent that shows the dark navy lining, a fine gold piping along the hem. Sways.

Frames as models/cosmetics.py (head: the cube round the head, front = north; attached: the model's (8, 8, 8) on the
slot's anchor, north = the player's front, +x = the player's right). Each piece stays well under the worn cosmetics'
budget (RenderBudget COSMETIC) and under cosmetics_lod.HEAVY faces, so it needs no far-away model.
"""

import economy_models  # noqa: F401  (registers the shared surfaces the machine materials may use)
from economy_machines import MModel, Mat
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp

from models.cosmetics import GOLD, GOLD_LIGHT, _display, mirror, HEAD_GUI, BACK_GUI


def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("agent_suit", 265, 0.012, 0.30, 0.18, 4, False)     # black wool
_ramp("agent_lining", 262, 0.080, 0.30, 0.16, 4, False)   # dark navy satin
_ramp("agent_lens", 252, 0.030, 0.27, 0.16, 4, False)     # the lenses: black with a cold blue sheen
_ramp("agent_frame", 260, 0.008, 0.26, 0.10, 4, False)    # gloss black frames, clips
_ramp("agent_coil", 205, 0.025, 0.80, 0.26, 4, False)     # the clear coiled tube

FRAME = Mat("agent_frame", "plain", 2, noise=0)
FRAME_DARK = Mat("agent_frame", "plain", 1, noise=0)
LENS = Mat("agent_lens", "plain", 1, noise=0)
GLINT = Mat("agent_coil", "plain", 3, bevel=None, noise=0)
SUIT = Mat("agent_suit", "plain", 1, noise=0.04)
SUIT_LIGHT = Mat("agent_suit", "plain", 2, noise=0.04)
SUIT_DARK = Mat("agent_suit", "plain", 0, noise=0)
LINING = Mat("agent_lining", "plain", 1, noise=0)
COIL = Mat("agent_coil", "plain", 2, noise=0)
COIL_LIGHT = Mat("agent_coil", "plain", 3, noise=0)

NECK_GUI = (30, 225, 0)


# ================================================================================================
# 머리: 선글라스 (helmet slot: the head is about 1.6 .. 14.4, its front face z 1.6; the eyes' row y 6.4 .. 8)
# ================================================================================================

def agent_sunglasses():
    """요원의 선글라스: black wayfarers. A thick brow bar, two dark lenses a little narrower at the bottom, a glint on
    each, arms back to the ears, a gold fleck at each hinge."""
    m = MModel("agent_sunglasses")
    m.group("frame")
    m.box("brow", (2.0, 8.6, 0.7), (14.0, 9.6, 1.45), FRAME)
    m.box("bridge", (7.2, 7.8, 0.85), (8.8, 8.6, 1.4), FRAME_DARK)
    for x0, x1 in ((1.0, 1.6), (14.4, 15.0)):
        m.box("arm", (x0, 8.7, 1.2), (x1, 9.35, 9.6), FRAME_DARK)
        m.box("arm_tip", (x0, 7.9, 9.0), (x1, 8.7, 9.6), FRAME_DARK)
    for x0, x1 in ((1.0, 2.0), (14.0, 15.0)):
        m.box("hinge", (x0, 8.6, 0.7), (x1, 9.6, 1.45), FRAME)
    mirror(m, "stud", (1.15, 8.95, 0.6), (1.85, 9.35, 0.7), GOLD_LIGHT, faces={"south": None})
    m.group("lenses")
    for x0, x1 in ((2.3, 7.2), (8.8, 13.7)):
        m.box("lens", (x0, 6.6, 0.85), (x1, 8.6, 1.4), LENS)
        m.box("lens", (x0 + 0.45, 5.9, 0.9), (x1 - 0.45, 6.6, 1.4), LENS)
    # the glint: one short bright stroke high on each lens
    for x0 in (3.0, 9.5):
        m.box("glint", (x0, 7.85, 0.8), (x0 + 1.2, 8.25, 0.85), GLINT, faces={"south": None})
    _display(m, HEAD_GUI, 0.6, -0.5)
    return m


# ================================================================================================
# 목: 이어피스 (neck anchor: the neck at y 9.2, the body's top x 4.25 .. 11.75, z 6.1 .. 9.9)
# ================================================================================================

def agent_earpiece():
    """요원의 이어피스: a clear coiled tube from a black bud under the right ear, down the back of the shoulder to a
    small black radio clip; a tiny black lapel mic on the left of the chest."""
    m = MModel("agent_earpiece")
    m.group("earpiece")
    m.box("bud", (11.8, 8.7, 7.4), (12.6, 9.6, 8.4), FRAME)
    m.box("bud_tube", (11.95, 8.3, 8.4), (12.45, 8.8, 9.2), COIL)
    # the coil: short rings stacked down and back, each a hair off the last so it reads as a spiral
    n = 9
    for i in range(n):
        t = i / (n - 1)
        y = 8.4 - i * 0.55
        z = 9.4 + 0.9 * t
        x = 11.6 - 0.6 * t + (0.15 if i % 2 else -0.15)
        m.box("coil", (x - 0.45, y - 0.4, z - 0.3), (x + 0.45, y, z + 0.3), COIL_LIGHT if i % 2 else COIL)
    m.box("radio", (10.3, 2.6, 9.95), (12.1, 3.95, 10.6), FRAME)
    m.box("radio_led", (10.6, 3.5, 10.6), (11.0, 3.75, 10.7), Mat("agent_lining", "plain", 3, bevel=None, noise=0),
          faces={"north": None})
    m.group("mic")
    m.box("mic", (5.0, 6.4, 5.6), (5.9, 7.3, 6.1), FRAME)
    m.box("mic_clip", (5.15, 7.3, 5.75), (5.75, 7.7, 6.1), Mat("steel", "metal", 3))
    _display(m, NECK_GUI, 1.4, -4)
    return m


# ================================================================================================
# 등: 연미복 코트 자락 (back anchor: the back's plane at z 8, the shoulders' top at y 11.6)
# ================================================================================================

COAT_Z = 8.1                                  # the coat's inside face, just behind the back
LOWER = ("x", -22.5, (8, 2.0, 8.3))           # the tails swept back about the hips


def agent_coat():
    """요원의 코트: the back of a black suit coat with tails. Squared shoulders, a back seam, two gold buttons at the
    waist, two tails swept back and split by a vent (navy lining inside), a fine gold piping along the hem."""
    m = MModel("agent_coat")
    m.group("coat")
    # the back: one panel from the shoulders to the waist, navy lining on its inside
    m.box("back", (3.6, 2.0, COAT_Z), (12.4, 11.6, COAT_Z + 0.5), SUIT, faces={"north": LINING})
    m.box("seam", (7.85, 2.3, COAT_Z + 0.5), (8.15, 11.3, COAT_Z + 0.55), SUIT_DARK, faces={"north": None})
    # squared shoulders over the top, falling a little round each side
    m.box("shoulders", (3.4, 11.4, 4.4), (12.6, 12.0, COAT_Z + 0.6), SUIT_LIGHT, faces={"down": SUIT_DARK})
    for side in (-1, 1):
        x0 = 3.0 if side < 0 else 12.4
        m.box("shoulder_side", (x0, 9.6, 4.5), (x0 + 0.6, 12.0, COAT_Z + 0.5), SUIT, faces={"west" if side < 0 else "east": SUIT_LIGHT})
    # the waist: a slim band, two gold buttons on it
    m.box("waist", (3.5, 1.6, COAT_Z - 0.05), (12.5, 2.4, COAT_Z + 0.6), SUIT_DARK)
    for x in (6.4, 8.8):
        m.box("button", (x, 2.5, COAT_Z + 0.5), (x + 0.8, 3.3, COAT_Z + 0.8), GOLD, faces={"north": None})
    # the tails: swept back about the hips, split by the vent (its edges and the inside show the lining)
    m.group("tails")
    for x0, x1 in ((3.6, 7.8), (8.2, 12.4)):
        m.box("tail", (x0, -6.4, COAT_Z), (x1, 2.2, COAT_Z + 0.5), SUIT, rotation=LOWER,
              faces={"north": LINING, "east": LINING if x0 < 8 else SUIT, "west": LINING if x0 > 8 else SUIT})
        m.box("hem", (x0, -6.8, COAT_Z - 0.05), (x1, -6.4, COAT_Z + 0.55), GOLD, rotation=LOWER)
    m.box("vent", (7.8, -6.0, COAT_Z + 0.1), (8.2, 1.8, COAT_Z + 0.4), LINING, rotation=LOWER)
    _display(m, BACK_GUI, 0.48, 1)
    return m


SLOTS = {"agent_sunglasses": "head", "agent_earpiece": "neck", "agent_coat": "back"}
ALL = {f.__name__: f for f in (agent_sunglasses, agent_earpiece, agent_coat)}
