"""
The 초밥 카니발's inventory icon (16x16, textures/sushi_carnival_icon.png): seen from its left side like the other cars
(front to the left): a long, tall pearl-white minivan (dark glass, black pillars, a bright crease, a slim white lamp in
front and a red one at the back, silver alloys) with the long streamlined black roof box on its rails: a tapered
nose to the front, a gloss line along its top, the shut line, its feet on the bars.
"""

from vehicle_icons import icon, _draw
import models.vehicles_donation_sushi_carnival  # noqa: F401  (registers the sc_ ramps)


@icon("sushi_carnival")
def sushi_carnival():
    rows = ["................",
            "................",
            "......xxxxxx....",
            "...xxXXXXXXXxx..",
            "..zddddddddddd..",
            "...bblbbbbbbb...",
            "...sksssssksss..",
            "..pppppppppppppp",
            ".gqggkggggkgggkp",
            "gqgggkggggkgggkp",
            "WWWWWWWWWWWWWWWW",
            "LppppppppppppppT",
            "Gpkkkkpppppkkkkp",
            "bbkhHkbbbbbkhHkb",
            "..khhk.....khhk.",
            "...kk.......kk.."]
    colours = {"x": ("sc_box", 3), "X": ("sc_box", 6), "z": ("sc_box", 2), "d": ("sc_box", 2), "l": ("sc_box", 0),
               "s": ("sc_chrome", 5), "k": ("sc_black", 0),
               "p": ("sc_pearl", 5), "W": ("sc_pearl", 7), "g": ("sc_black", 1), "q": ("sc_chrome", 2),
               "L": ("sc_led", 2), "T": ("sc_tail", 3), "G": ("sc_chrome", 6), "b": ("sc_box", 1),
               "h": ("sc_rim", 4), "H": ("sc_chrome", 7)}
    return _draw(rows, colours).render()
