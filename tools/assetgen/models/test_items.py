"""
TEST-ONLY 3D models: one of every kind the plugin supports, in orange/grey "dev texture" colours so
nobody mistakes them for real content. Each one exercises a different feature:

  test_machine     multi-cell furniture (2x2x1) + glowing animated screen + spinning fan part
  test_fan_blade   the spinning part of test_machine (separate display entity)
  test_bench       furniture with two seats
  test_rug         non-solid furniture (walk-through; clicked through an interaction entity)
  test_food        3D food item (held like a vanilla item)
  test_sword       3D sword (held like a vanilla sword)
  test_sword_skin  a different 3D sword look, applied to real swords by the skin book
  test_rod(_cast)  3D fishing rod with its cast state
  test_shield(_blocking)  3D shield with its blocking pose
  tomato_mask      cosmetic: a tomato costume head with an opening for the face
"""

from stsv_assets import presets
from stsv_assets.model import Model

SWORD_TILT = ("z", -45, (8, 8, 8))  # build swords/rods upright, then tilt so the tip points top-right


def test_machine():
    m = Model("test_machine")
    m.group("body")
    m.box("base", (0, 0, 0), (32, 2, 16), "dev_grey")
    m.box("body", (1, 2, 1), (31, 26, 15), "dev_orange", faces={"north": "dev_grey"})
    m.box("top", (0, 26, 0), (32, 28, 16), "dev_grey")
    m.group("screen")
    m.box("bezel_t", (5, 18, 0), (27, 19, 1), "dev_grey")
    m.box("bezel_b", (5, 7, 0), (27, 8, 1), "dev_grey")
    m.box("bezel_l", (5, 8, 0), (6, 18, 1), "dev_grey")
    m.box("bezel_r", (26, 8, 0), (27, 18, 1), "dev_grey")
    m.box("screen", (6, 8, 0.5), (26, 18, 1), "dev_screen", glow=True, full_uv=["north"])
    m.box("label", (12, 21, 0.5), (20, 24, 1), "dev_stripe")
    m.group("fan_housing")
    for name, frm, to in (("ring_n", (8, 28, 0.5), (24, 30, 1.5)), ("ring_s", (8, 28, 14.5), (24, 30, 15.5)),
                          ("ring_w", (8, 28, 1.5), (9, 30, 14.5)), ("ring_e", (23, 28, 1.5), (24, 30, 14.5))):
        m.box(name, frm, to, "dev_grey")
    m.box("hub", (15, 28, 7), (17, 29, 9), "dev_orange")
    m.display = presets.furniture(gui_scale=0.36, gui_translation=(3.5, -3, 0))
    return m


def test_fan_blade():
    """Centred on (8, 8, 8) so it spins around its own middle."""
    m = Model("test_fan_blade")
    m.box("blade_a", (1.5, 7.5, 7), (14.5, 8.5, 9), "dev_fan")
    m.box("blade_b", (7, 7.5, 1.5), (9, 8.5, 7), "dev_fan")
    m.box("blade_c", (7, 7.5, 9), (9, 8.5, 14.5), "dev_fan")
    m.box("cap", (7, 8.5, 7), (9, 9, 9), "dev_orange")
    m.display = presets.furniture()
    return m


def test_bench():
    m = Model("test_bench")
    for i, (x, z) in enumerate(((1, 2), (29, 2), (1, 12), (29, 12))):
        m.box(f"leg_{i}", (x, 0, z), (x + 2, 7, z + 2), "dev_grey")
    m.box("seat", (0, 7, 1), (32, 9, 15), "dev_orange")
    m.box("back_l", (1, 9, 13), (3, 20, 15), "dev_grey")
    m.box("back_r", (29, 9, 13), (31, 20, 15), "dev_grey")
    m.box("back", (3, 13, 13.5), (29, 19, 14.5), "dev_stripe")
    m.display = presets.furniture(gui_scale=0.36, gui_translation=(3.5, 0, 0))
    return m


def test_rug():
    m = Model("test_rug")
    m.box("rug", (0.5, 0, 0.5), (15.5, 0.5, 15.5), "dev_stripe")
    m.box("edge_n", (0, 0, 0), (16, 0.75, 0.5), "dev_orange")
    m.box("edge_s", (0, 0, 15.5), (16, 0.75, 16), "dev_orange")
    m.display = presets.furniture(gui_scale=0.6)
    return m


def test_food():
    """A jelly cube on a plate."""
    m = Model("test_food")
    m.box("plate", (3, 0, 3), (13, 1, 13), "dev_grey")
    m.box("jelly", (4.5, 1, 4.5), (11.5, 7, 11.5), "dev_orange")
    m.box("topping", (6.5, 7, 6.5), (9.5, 8, 9.5), "dev_stripe")
    m.display = {**presets.GENERATED,
                 "gui": {"rotation": [30, 225, 0], "translation": [0, 2, 0], "scale": [1, 1, 1]}}
    return m


def _sword(name, blade, edge, guard):
    m = Model(name)
    m.front_gui_light = True
    t = SWORD_TILT
    m.box("pommel", (7, -1.5, 7), (9, 0, 9), guard, rotation=t)
    m.box("grip", (7.25, 0, 7.25), (8.75, 4.5, 8.75), "dev_grey", rotation=t)
    m.box("guard", (4, 4.5, 7), (12, 6, 9), guard, rotation=t)
    m.box("blade", (6.75, 6, 7.5), (9.25, 17.5, 8.5), blade, rotation=t)
    m.box("edge_l", (6.25, 6.5, 7.75), (6.75, 17, 8.25), edge, rotation=t)
    m.box("edge_r", (9.25, 6.5, 7.75), (9.75, 17, 8.25), edge, rotation=t)
    m.box("tip", (7.25, 17.5, 7.6), (8.75, 19, 8.4), blade, rotation=t)
    m.display = {**presets.HANDHELD, "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]}}
    return m


def test_sword():
    return _sword("test_sword", "dev_grey", "dev_orange", "dev_orange")


def test_sword_skin():
    return _sword("test_sword_skin", "dev_purple", "dev_screen", "dev_grey")


# grip of every upright-then-tilted sword: centre of the handle (y≈2.25) after the -45° tilt
SWORD_GRIP = (-4.07, -4.07, 0.0)


def test_greatsword():
    """'Cash item' greatsword: winged gold guard, ruby gems, a polished blade with flowing energy in
    its core. Built as long as the model space allows (to y = 32) and shown 1.35x larger in third
    person / 1.15x in first person, with the grip kept in the hand (presets.enlarged)."""
    return _greatsword(False)


def test_greatsword_charged():
    """Shown while the right-click charge is full: the whole blade turns to glowing energy."""
    return _greatsword(True)


def _greatsword(charged):
    m = Model("test_greatsword_charged" if charged else "test_greatsword")
    blade = "energy" if charged else "blade_steel"
    m.front_gui_light = True
    t = SWORD_TILT
    m.group("hilt")
    m.box("pommel_gem", (7.1, -2.4, 7.1), (8.9, -0.6, 8.9), "gem_ruby", glow=True, rotation=t)
    m.box("pommel_cap", (6.75, -0.6, 6.75), (9.25, 0, 9.25), "premium_gold", rotation=t)
    m.box("grip", (7.25, 0, 7.25), (8.75, 4.5, 8.75), "obsidian_wrap", rotation=t)
    m.box("grip_ring_a", (7, 1.25, 7), (9, 1.75, 9), "premium_gold", rotation=t)
    m.box("grip_ring_b", (7, 3, 7), (9, 3.5, 9), "premium_gold", rotation=t)
    m.group("guard")
    m.box("guard_core", (5, 4.5, 6.75), (11, 6.5, 9.25), "premium_gold", rotation=t)
    m.box("guard_gem", (7, 4.75, 6.5), (9, 6.25, 9.5), "gem_ruby", glow=True, rotation=t)
    for side, x0, x1, x2, x3 in (("l", 2, 5, 0.5, 2), ("r", 11, 14, 14, 15.5)):
        m.box(f"wing_{side}_1", (x0, 5.5, 7.25), (x1, 6.75, 8.75), "premium_gold", rotation=t)
        m.box(f"wing_{side}_2", (x2, 6.75, 7.4), (x3, 8.75, 8.6), "premium_gold", rotation=t)
        tip = (x2, 8.75, 7.6) if side == "l" else (x3 - 1, 8.75, 7.6)
        m.box(f"wing_{side}_tip", tip, (tip[0] + 1, 10.25, 8.4), "obsidian_wrap", rotation=t)
    m.box("collar", (6.5, 6.5, 7.25), (9.5, 7.5, 8.75), "obsidian_wrap", rotation=t)
    m.group("blade")
    m.box("blade", (6.25, 7.5, 7.5), (9.75, 28, 8.5), blade, glow=charged, rotation=t)
    m.box("core", (7.4, 8, 7.35), (8.6, 27, 8.65), "energy", glow=True, rotation=t)
    m.box("edge_l", (5.75, 8.5, 7.75), (6.25, 27, 8.25), blade, glow=charged, rotation=t)
    m.box("edge_r", (9.75, 8.5, 7.75), (10.25, 27, 8.25), blade, glow=charged, rotation=t)
    m.box("tip_1", (6.75, 28, 7.6), (9.25, 30, 8.4), blade, glow=charged, rotation=t)
    m.box("tip_2", (7.25, 30, 7.7), (8.75, 31.25, 8.3), blade, glow=charged, rotation=t)
    m.box("tip_3", (7.6, 31.25, 7.8), (8.4, 32, 8.2), "energy", glow=True, rotation=t)
    m.display = {
        **presets.enlarged(presets.HANDHELD, {"thirdperson_righthand": 1.35, "thirdperson_lefthand": 1.35,
                                              "firstperson_righthand": 1.15, "firstperson_lefthand": 1.15},
                           SWORD_GRIP),
        "gui": {"rotation": [0, 0, 0], "translation": [-1.5, -1.5, 0], "scale": [0.55, 0.55, 0.55]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]},
    }
    return m


def test_slash():
    """Sword-swing effect: a glowing crescent lying flat (XZ plane) in front of the centre, made of
    seven 3px segments on a 7px radius rotated 22.5° apart. Centred on (8, 8, 8): the plugin spins and
    grows it around that point while it sweeps."""
    m = Model("test_slash")
    for i, angle in enumerate((-45, -22.5, 0, 22.5, 45)):
        thick = 0.5 if abs(angle) == 45 else 1.0
        m.box(f"seg_{i}", (6.25, 8 - thick / 2, 0.5), (9.75, 8 + thick / 2, 1.75), "energy",
              glow=True, rotation=("y", angle, (8, 8, 8)))
    m.display = {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    return m


def _rod(name, cast):
    m = Model(name)
    m.front_gui_light = True
    t = SWORD_TILT
    m.box("handle", (7.25, -2, 7.25), (8.75, 3, 8.75), "dev_orange", rotation=t)
    m.box("reel", (8.75, 1, 7), (10.5, 3, 9), "dev_grey", rotation=t)
    m.box("rod", (7.5, 3, 7.5), (8.5, 18, 8.5), "dev_grey", rotation=t)
    m.box("tip", (7.6, 18, 7.6), (8.4, 18.5, 8.4), "dev_orange", rotation=t)
    if not cast:
        # line hanging straight down from the tip (the tip ends at about (15.1, 15.1))
        m.box("line", (14.75, 3, 7.75), (15.25, 15, 8.25), "dev_stripe")
        m.box("hook", (14, 2, 7.75), (15.25, 3, 8.25), "dev_orange")
    m.display = {**presets.HANDHELD_ROD, "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]}}
    return m


def test_rod():
    return _rod("test_rod", False)


def test_rod_cast():
    return _rod("test_rod_cast", True)


def _shield(blocking):
    """Same space as the vanilla shield renderer: plate centred on x/y = 0, front towards +Z."""
    import special
    m = Model("test_shield_blocking" if blocking else "test_shield")
    m.box("plate", (-5, -10, 1), (5, 10, 2), "dev_orange")
    m.box("rim_top", (-6, 10, 0.75), (6, 11, 2.5), "dev_grey")
    m.box("rim_bottom", (-6, -11, 0.75), (6, -10, 2.5), "dev_grey")
    m.box("rim_left", (-6, -10, 0.75), (-5, 10, 2.5), "dev_grey")
    m.box("rim_right", (5, -10, 0.75), (6, 10, 2.5), "dev_grey")
    m.box("mark", (-3, -3, 2), (3, 3, 2.5), "dev_stripe")
    m.box("grip", (-1, -3, -5), (1, 3, 1), "dev_grey")
    m.display = special.SHIELD_BLOCKING_DISPLAY if blocking else special.SHIELD_DISPLAY
    return m


def test_shield():
    return _shield(False)


def test_shield_blocking():
    return _shield(True)


def _head_shell(m, tex, top=14.5, face=(4, 12, 3, 11)):
    """The shared mask shell: worn like a carved pumpkin (model 0..16, face on the north side).
    The head fits in the 1.5-thick walls; the opening on the front shows the player's face.
    face = (x0, x1, y0, y1) of the opening."""
    fx0, fx1, fy0, fy1 = face
    m.group("shell")
    m.box("front_l", (0, 0, 0), (fx0, top, 1.5), tex)
    m.box("front_r", (fx1, 0, 0), (16, top, 1.5), tex)
    m.box("front_b", (fx0, 0, 0), (fx1, fy0, 1.5), tex)
    m.box("front_t", (fx0, fy1, 0), (fx1, top, 1.5), tex)
    m.box("back", (0, 0, 14.5), (16, top, 16), tex)
    m.box("left", (0, 0, 1.5), (1.5, top, 14.5), tex)
    m.box("right", (14.5, 0, 1.5), (16, top, 14.5), tex)


def tomato_mask():
    m = Model("tomato_mask")
    _head_shell(m, "tomato_skin")
    m.box("top", (0.5, 14.5, 0.5), (15.5, 16, 15.5), "tomato_skin")
    m.group("calyx")
    m.box("leaf_x", (4, 16, 7), (12, 16.75, 9), "calyx")
    m.box("leaf_z", (7, 16, 4), (9, 16.5, 12), "calyx")
    m.box("stem", (7.5, 16.75, 7.5), (8.5, 19, 8.5), "calyx")
    m.display = {**presets.BLOCK}   # 'head' stays identity -> sits on the head like a pumpkin
    return m


def eggplant_mask():
    """A tall glossy eggplant: the body narrows upwards into a green cap with drooping sepals."""
    m = Model("eggplant_mask")
    _head_shell(m, "eggplant_skin")
    m.group("body")
    m.box("shoulder", (0.5, 14.5, 0.5), (15.5, 17, 15.5), "eggplant_skin")
    m.box("neck_1", (2, 17, 2), (14, 20, 14), "eggplant_skin")
    m.box("neck_2", (3.5, 20, 3.5), (12.5, 22.5, 12.5), "eggplant_skin")
    m.group("cap")
    m.box("cap", (3, 22.5, 3), (13, 24, 13), "calyx")
    # sepals hanging down over the neck on four sides
    m.box("sepal_n", (6, 19.5, 2.75), (10, 22.5, 3.5), "calyx")
    m.box("sepal_s", (6, 19.5, 12.5), (10, 22.5, 13.25), "calyx")
    m.box("sepal_w", (2.75, 19.5, 6), (3.5, 22.5, 10), "calyx")
    m.box("sepal_e", (12.5, 19.5, 6), (13.25, 22.5, 10), "calyx")
    m.box("stem", (7, 24, 7), (9, 27, 9), "calyx")
    m.box("stem_tip", (7, 27, 6), (9, 28, 8), "calyx")
    m.display = {**presets.BLOCK, "gui": {"rotation": [30, 225, 0], "translation": [0, -2.5, 0], "scale": [0.5, 0.5, 0.5]}}
    return m


def mushroom_mask():
    """Red toadstool: the cream stalk is the face shell, a wide spotted cap overhangs the head."""
    m = Model("mushroom_mask")
    _head_shell(m, "mushroom_stalk", top=13)
    m.group("cap")
    under = {"down": "gills"}
    m.box("brim", (-2.5, 13, -2.5), (18.5, 16, 18.5), "toadstool_cap", faces=under)
    m.box("dome", (-0.5, 16, -0.5), (16.5, 19, 16.5), "toadstool_cap")
    m.box("crown", (3, 19, 3), (13, 21, 13), "toadstool_cap")
    # the stalk's skirt: four strips just outside the walls, above the face opening
    m.box("ring_n", (-0.5, 11.5, -0.5), (16.5, 12.5, 0), "mushroom_stalk")
    m.box("ring_s", (-0.5, 11.5, 16), (16.5, 12.5, 16.5), "mushroom_stalk")
    m.box("ring_w", (-0.5, 11.5, 0), (0, 12.5, 16), "mushroom_stalk")
    m.box("ring_e", (16, 11.5, 0), (16.5, 12.5, 16), "mushroom_stalk")
    m.display = {**presets.BLOCK, "gui": {"rotation": [30, 225, 0], "translation": [0, -1.5, 0], "scale": [0.48, 0.48, 0.48]}}
    return m


ALL = {f.__name__: f for f in (test_machine, test_fan_blade, test_bench, test_rug, test_food, test_sword, test_greatsword,
                               test_greatsword_charged, test_slash,
                               test_sword_skin, test_rod, test_rod_cast, test_shield, test_shield_blocking,
                               tomato_mask, eggplant_mask, mushroom_mask)}
