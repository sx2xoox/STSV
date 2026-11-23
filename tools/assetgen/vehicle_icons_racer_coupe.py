"""
The inventory icon (16x16, textures/racer_coupe_icon.png) of 미완의 질주 (racer_coupe, the car of the hidden secret
폐차고의 주인): seen from its left side, front to the left, like the other vehicle icons. A low primer-grey liftback
coupe: the lamp at the nose, the raked screen, the white roll hoop behind the glass, the fastback, the big wing on its
upright, the number board on the door, black steel wheels, the red tail lamp.
"""

import vehicle_icons
from vehicle_icons import icon, _draw
import models.vehicles_racer_coupe  # noqa: F401  (registers the rc_ ramps the icon paints with)


@icon("racer_coupe")
def racer_coupe():
    rows = ["................",
            "................",
            "................",
            "................",
            "......wwww...www",
            ".....pGGgGp...m.",
            "....pGGGgGGp..m.",
            "LwwwwwwwwwwwwwwT",
            "PPPPPPWBWPPPPPPP",
            "PPkkkPWWWPPkkkPP",
            "NkhHhkNNNNkhHhkN",
            ".khhhkddddkhhhk.",
            "..kkk......kkk..",
            "................",
            "................",
            "................"]
    colours = {"w": ("rc_primer", 7), "P": ("rc_primer", 4), "N": ("rc_primer", 1), "p": ("rc_primer", 3),
               "G": ("rc_trim", 1), "g": ("rc_cage", 4), "m": ("rc_metal", 5), "L": ("rc_amber", 3),
               "T": ("rc_tail", 3), "k": ("rc_trim", 0), "h": ("rc_steel", 3), "H": ("rc_metal", 5),
               "W": ("rc_cage", 4), "B": ("rc_trim", 0), "d": ("rc_trim", 1)}
    return _draw(rows, colours).render()


ICONS = {"racer_coupe": vehicle_icons.ICONS["racer_coupe"]}
