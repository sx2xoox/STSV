"""Wooden armchair with a quilted cushion (furniture). 1 x 1 block. Front = north."""

from stsv_assets.model import Model

FURNITURE_CELLS = [[0, 0, 0]]


def build() -> Model:
    m = Model("chair")

    m.group("frame")
    for i, (x, z) in enumerate(((2, 2), (12.5, 2), (2, 12.5), (12.5, 12.5))):
        m.box(f"leg_{i}", (x, 0, z), (x + 1.5, 7, z + 1.5), "wood_dark_grain")
    m.box("stretcher_l", (2.25, 2, 3.5), (3.25, 3, 12.5), "wood_dark_grain")
    m.box("stretcher_r", (12.75, 2, 3.5), (13.75, 3, 12.5), "wood_dark_grain")
    m.box("seat", (1.5, 7, 1.5), (14.5, 8.5, 14.5), "wood")
    m.box("apron", (3.5, 6, 1.75), (12.5, 7, 2.25), "wood_dark_grain")  # between the legs

    m.group("back")
    m.box("post_l", (2, 8.5, 12.5), (3.5, 22, 14), "wood_dark_grain")
    m.box("post_r", (12.5, 8.5, 12.5), (14, 22, 14), "wood_dark_grain")
    m.box("crest", (1.5, 20, 12.25), (14.5, 22.5, 14.25), "wood")
    m.box("crest_cap", (1, 22.5, 12), (15, 23, 14.5), "wood_dark_grain")
    for i, x in enumerate((5, 7.25, 9.5, 11.75)):
        m.box(f"slat_{i}", (x - 0.75, 11, 12.75), (x + 0.75, 20, 13.75), "wood_grain")
    m.box("rail", (3.5, 10, 12.75), (12.5, 11, 13.75), "wood_dark_grain")

    m.group("arms")
    for side, x in (("l", 1.5), ("r", 12.5)):
        m.box(f"arm_{side}", (x, 13, 2), (x + 2, 14, 12.5), "wood_grain")  # stops at the back post
        m.box(f"arm_post_{side}", (x + 0.5, 8.5, 2.5), (x + 1.5, 13, 3.5), "wood_dark_grain")

    m.group("cushion")
    # side_v keeps the horizontal seam rows (7-8) off the thin sides; the vertical seam continues down
    m.box("cushion", (3.5, 8.5, 3), (12.5, 10, 13), "fabric", side_v=(2, 3.5))
    m.box("button", (7.5, 10, 7), (8.5, 10.25, 8), "brass")

    m.display = {
        "gui": {"rotation": [30, 225, 0], "translation": [0, -1, 0], "scale": [0.55, 0.55, 0.55]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.35, 0.35, 0.35]},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    }
    return m
