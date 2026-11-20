"""
The 16x16 inventory icon of 후원 · 레트로 캠핑밴 (retro_camper): seen from its left side, front to the left, like the
other vehicle icons (vehicle_icons.py). Short and round: the yellow surfboard on the cream lid of the pop-up tent
(orange-and-cream striped canvas), the cream upper rolling over at the roof's ends, the windows (the cab's, then
three with red curtains), the bright rib at the belt, the turquoise lower with a round headlamp on the nose, white-wall
wheels with chrome caps, the bumpers, the spare wheel on the back.
"""

import vehicle_icons as VI
from vehicle_icons import icon, _draw, TIRES
import models.vehicles_donation_retro_camper  # noqa: F401  (registers the rc_* ramps)


@icon("retro_camper")
def retro_camper():
    rows = ["................",
            "....bbbbbbbbB...",
            "...LLLLLLLLLLL..",
            "....oOoOoOoOoO..",
            "..cccccccccccc..",
            ".cGgcrgrcrgrccc.",
            ".cggcrgrcrgrccc.",
            ".dcccccccccccccd",
            ".wwwwwwwwwwwwwwK",
            "yttttttttttttttK",
            "YttnkkknttnkkknK",
            "BttkwhwkttkwhwkB",
            "...kwHwk..kwHwk.",
            "....kkk....kkk..",
            "................",
            "................"]
    colours = {"b": ("rc_board", 3), "B": ("rc_chrome", 5), "L": ("rc_cream", 4), "o": ("rc_canvas", 2),
               "O": ("rc_cream", 6), "c": ("rc_cream", 5), "d": ("rc_cream", 3), "G": ("glass", 3), "g": ("tint", 1),
               "r": ("rc_gingham", 2), "w": ("rc_cream", 6), "t": ("rc_turq", 3), "n": ("rc_turq", 1),
               "y": ("lamp", 4), "Y": ("rc_chrome", 4), "K": ("rc_tyre", 1),
               **TIRES, "w": ("rc_cream", 6)}
    return _draw(rows, colours).render()


ICONS = {"retro_camper": VI.ICONS["retro_camper"]}
