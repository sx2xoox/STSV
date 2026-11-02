"""Potted shrub (decor). 1 x 1 block footprint, foliage pokes ~half a block above."""

from stsv_assets.model import Model

FURNITURE_CELLS = [[0, 0, 0]]


def build() -> Model:
    m = Model("potted_plant")

    m.group("pot")
    m.box("pot_foot", (5, 0, 5), (11, 1, 11), "terracotta")
    m.box("pot_body", (4, 1, 4), (12, 7, 12), "terracotta", faces={"up": None})
    m.box("pot_rim", (3.5, 7, 3.5), (12.5, 8.5, 12.5), "terracotta", faces={"up": None})
    m.box("soil", (4.5, 7.6, 4.5), (11.5, 8, 11.5), "soil")

    m.group("trunk")
    m.box("trunk", (7.25, 8, 7.25), (8.75, 14, 8.75), "wood_dark_grain")
    m.box("branch_l", (5, 12, 7.5), (7.25, 13, 8.5), "wood_dark_grain")
    m.box("branch_r", (8.75, 13.5, 7.5), (11, 14.5, 8.5), "wood_dark_grain")

    m.group("foliage")
    m.box("crown", (4, 14, 4), (12, 20, 12), "leaves")
    m.box("crown_top", (5.5, 20, 5.5), (10.5, 22.5, 10.5), "leaves")
    m.box("tuft_l", (2, 11.5, 5), (6, 15, 10), "leaves")
    m.box("tuft_r", (10, 13, 6), (14, 16.5, 11), "leaves")
    m.box("tuft_back", (6, 16, 10.5), (10, 19, 13.5), "leaves", rotation=("y", 22.5, (8, 17, 12)))
    m.box("tuft_front", (6.5, 15, 2.5), (9.5, 18, 5), "leaves", rotation=("x", -22.5, (8, 16, 4)))

    m.display = {
        "gui": {"rotation": [30, 225, 0], "translation": [0, -2, 0], "scale": [0.6, 0.6, 0.6]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    }
    return m
