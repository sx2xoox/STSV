"""
The 16x16 inventory icon of 후원 · 레트로 캠핑밴 (retro_camper): seen from its left side, front to the left, like the
other vehicle icons (vehicle_icons.py). A tall box: the surfboard on top, the orange pop-up tent band, the cream upper
with its windows (curtains in the back ones), the wave between cream and mint, round white-wall wheels, the spare
wheel on the back.
"""

import vehicle_icons as VI
from vehicle_icons import icon, _draw, TIRES


@icon("retro_camper")
def retro_camper():
    rows = ["................",
            "...bbbbbbbbbB...",
            "...oOoOoOoOoo...",
            ".cccccccccccccc.",
            ".gGgcgGgcgfgfgc.",
            ".gGgcggGcffgffc.",
            ".gggcgggcfgggfc.",
            "yccccccccccccccr",
            ".mmmsssmmmmmssmK",
            "smmmmmmmmmmmmmmK",
            ".mmkkkmmmmmkkkmK",
            "sNkwhwkNNNNkwhwk",
            "..kwHwk...kwHwk.",
            "...kkk.....kkk..",
            "................",
            "................"]
    colours = {"b": ("water", 3), "B": ("cream", 3), "o": ("fox", 3), "O": ("fox", 2), "c": ("cream", 3),
               "g": ("tint", 2), "G": ("glass", 4), "f": ("fox", 3), "m": ("celadon", 2), "N": ("celadon", 0),
               "s": ("fox", 3), "y": ("lamp", 5), "r": ("signal_red", 3), "K": ("rubber", 1),
               **TIRES, "w": ("cream", 3)}
    return _draw(rows, colours).render()


ICONS = {"retro_camper": VI.ICONS["retro_camper"]}
