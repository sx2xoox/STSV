"""
Industrial smelter (tycoon machine). Footprint 2 x 2 x 1 blocks (x, y, z). Front = north face.

Layout (model space):
  x  0..20  brick furnace with steel frame, fire mouth, half-open door, gauge, work lamp
  x 20..32  conveyor carrying ore into the side intake, fed by a hopper + copper pipe
"""

from stsv_assets.model import Model

FURNITURE_CELLS = [[0, 0, 0], [1, 0, 0], [0, 1, 0], [1, 1, 0]]


def build() -> Model:
    m = Model("smelter")

    # --- base ---------------------------------------------------------------------------------
    m.group("base")
    m.box("plinth", (0, 0, 0), (20, 2, 16), "steel_dark")
    m.box("plinth_trim", (0.5, 2, 0.5), (19.5, 3, 15.5), "steel")
    for i, (x, z) in enumerate(((0, 0), (18, 0), (0, 14), (18, 14))):
        m.box(f"pillar_{i}", (x, 3, z), (x + 2, 20, z + 2), "steel")
    # steel bands holding the brickwork together
    m.box("band_west", (0.6, 10, 2), (1, 11, 14), "steel_dark")
    m.box("band_back", (2, 10, 15), (18, 11, 15.4), "steel_dark")
    m.box("band_east", (19, 14, 2), (19.4, 15, 14), "steel_dark")

    # --- brick body (lower clean, upper soot-stained) -------------------------------------------
    m.group("body")
    for name, frm, to in (
        ("west", (1, 3, 2), (2, 20, 14)),
        ("east", (18, 3, 2), (19, 20, 14)),
        ("back", (2, 3, 14), (18, 20, 15)),
        ("front_left", (2, 3, 1), (6, 20, 3)),
        ("front_right", (14, 3, 1), (18, 20, 3)),
        ("front_bottom", (6, 3, 1), (14, 5, 3)),
        ("front_top", (6, 11, 1), (14, 20, 3)),
    ):
        lo_top = min(to[1], 15)
        if frm[1] < 15:
            m.box(f"{name}_lo", frm, (to[0], lo_top, to[2]), "brick")
        if to[1] > 15:
            m.box(f"{name}_hi", (frm[0], max(frm[1], 15), frm[2]), to, "brick_soot")
    m.box("core", (2, 3, 3), (18, 20, 14), "brick", faces={"north": "opening"})
    # arch corners of the fire mouth
    m.box("arch_l", (6, 10, 1), (7, 11, 3), "brick")
    m.box("arch_r", (13, 10, 1), (14, 11, 3), "brick")

    # --- fire mouth -----------------------------------------------------------------------------
    m.group("mouth")
    m.box("fire", (6, 5, 2.9), (14, 11, 3), "fire", glow=True)
    m.box("frame_bottom", (5, 4, 0.5), (15, 5, 1), "steel")
    m.box("frame_top", (5, 11, 0.5), (15, 12, 1), "steel")
    m.box("frame_left", (5, 5, 0.5), (6, 11, 1), "steel")
    m.box("frame_right", (14, 5, 0.5), (15, 11, 1), "steel")
    for i, x in enumerate((7.5, 9.5, 11.5)):
        m.box(f"grate_{i}", (x, 5, 1.4), (x + 1, 10, 1.9), "steel_dark")
    m.box("ash_tray", (5.5, 3, -1), (14.5, 4, 1), "steel_dark", faces={"up": "opening"})
    m.box("ash_lip", (5.5, 4, -1), (14.5, 4.5, -0.5), "steel")
    # half-open door, hinged on the frame's west edge, swung 45° outwards (keeps the fire visible)
    hinge = ("y", 45, (5, 8, 0.5))
    m.box("door", (5, 4.5, 0), (13, 11.5, 0.6), "steel", rotation=hinge)
    m.box("door_handle", (11, 7.5, -0.6), (12, 8.5, 0), "brass", rotation=hinge)
    m.box("hinge_top", (4.6, 10, -0.2), (5.4, 11, 0.6), "steel_dark")
    m.box("hinge_bottom", (4.6, 5, -0.2), (5.4, 6, 0.6), "steel_dark")
    # instruments
    m.box("gauge", (14.5, 13.5, 0.3), (17.5, 16.5, 1), "steel", faces={"north": "gauge"}, full_uv=["north"])
    m.box("lamp_bracket", (0.5, 15, -1.25), (1.5, 15.5, 0), "steel_dark")
    m.box("lamp", (0.25, 15.5, -1.5), (1.75, 17.5, 0), "lamp", glow=True, full_uv=["north", "east", "west", "south"])

    # --- roof & chimney -----------------------------------------------------------------------
    m.group("roof")
    m.box("roof_lip", (-0.5, 20, -0.5), (20.5, 21, 16.5), "steel_dark")
    m.box("roof_plate", (0, 21, 0), (20, 22, 16), "steel")
    m.box("roof_tier", (3, 22, 3), (17, 25, 13), "brick_soot")
    m.box("chimney", (11, 25, 5), (16, 31, 10), "brick_soot", faces={"up": None})
    m.box("chimney_band", (10.5, 27.5, 4.5), (16.5, 28.5, 10.5), "steel_dark")
    m.box("chimney_crown", (10.5, 31, 4.5), (16.5, 32, 10.5), "steel", faces={"up": None})
    m.box("chimney_hole", (11, 31.5, 5), (16, 31.6, 10), "opening", faces={"up": "opening"})

    # --- copper pipe & hopper -----------------------------------------------------------------
    m.group("pipe")
    m.box("pipe_out", (17, 23, 7), (24, 25, 9), "copper")
    m.box("pipe_elbow", (24, 23, 7), (26, 25, 9), "copper")
    m.box("pipe_down", (24, 13, 7), (26, 23, 9), "copper")
    m.box("flange_a", (23.5, 20, 6.5), (26.5, 21, 9.5), "copper")
    m.box("flange_b", (19.5, 22.5, 6.5), (20.5, 25.5, 9.5), "copper")
    m.box("hopper_top", (22, 12, 5), (28, 13, 11), "steel", faces={"up": "opening"})
    m.box("hopper_mid", (23, 11, 6), (27, 12, 10), "steel_dark")
    m.box("hopper_tip", (24, 10, 7), (26, 11, 9), "steel_dark")

    # --- side intake ----------------------------------------------------------------------------
    m.group("intake")
    m.box("intake_hole", (19, 6, 4.5), (19.2, 11, 11.5), "opening")
    m.box("intake_hood", (19, 11, 3.5), (21, 12, 12.5), "steel")
    for i, z in enumerate((5, 7.2, 9.4)):
        m.box(f"flap_{i}", (19.3, 7.2, z), (19.6, 11, z + 1.8), "belt")

    # --- conveyor -----------------------------------------------------------------------------
    m.group("conveyor")
    m.box("belt", (20, 6, 4), (31, 7, 12), "steel_dark", faces={"up": "belt"})
    m.box("rail_n", (20, 5, 3), (32, 8, 4), "steel_dark", faces={"north": "hazard"})
    m.box("rail_s", (20, 5, 12), (32, 8, 13), "steel_dark", faces={"south": "hazard"})
    m.box("roller", (30.5, 5.5, 4), (32, 7.25, 12), "steel")
    for i, (x, z) in enumerate(((22, 3), (22, 12), (30, 3), (30, 12))):
        m.box(f"leg_{i}", (x, 0, z), (x + 1, 5, z + 1), "steel_dark")
    m.box("brace_a", (22.5, 1.5, 4), (23, 2.5, 12), "steel_dark")
    m.box("brace_b", (30.25, 1.5, 4), (30.75, 2.5, 12), "steel_dark")
    m.box("ore_a", (22.5, 7, 5.5), (25, 9.5, 8), "gold_ore")
    m.box("ore_b", (27.5, 7, 8), (29.5, 9, 10), "gold_ore")
    m.box("ore_c", (24.5, 7, 9), (26, 8.5, 10.5), "gold_ore")

    # inventory icon / item frame framing for a 2x2 block model
    m.display = {
        "gui": {"rotation": [30, 225, 0], "translation": [3.25, -3.5, 0], "scale": [0.4, 0.4, 0.4]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.2, 0.2, 0.2]},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.2, 0.2, 0.2]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.2, 0.2, 0.2]},
    }
    return m
