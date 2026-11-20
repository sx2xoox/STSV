"""
The 초밥 카니발's inventory icon (16x16, textures/sushi_carnival_icon.png): seen from its left side like the other cars
(front to the left): a long, tall pearl-white minivan (dark glass, black pillars, a bright crease, a slim white lamp in
front and a red one at the back, silver alloys) with the giant salmon nigiri on its roof rails: an orange slice with
white fat lines over a white rice block, the black nori band round the middle.
"""

from vehicle_icons import icon, _draw
import models.vehicles_donation_sushi_carnival  # noqa: F401  (registers the sc_ ramps)


@icon("sushi_carnival")
def sushi_carnival():
    rows = ["................",
            "...fFffnnfFff...",
            "..fffFfnnffFfff.",
            "...rRrrnnrrRr...",
            "...uuuRnnrRuu...",
            "..sksssssssksss.",
            "..pppppppppppppp",
            ".gqggkggggkgggkp",
            "gqgggkggggkgggkp",
            "WWWWWWWWWWWWWWWW",
            "LppppppppppppppT",
            "Gpkkkkpppppkkkkp",
            "bbkhHkbbbbbkhHkb",
            "..khhk.....khhk.",
            "...kk.......kk..",
            "................"]
    colours = {"f": ("sc_salmon", 3), "F": ("sc_fat", 2), "n": ("sc_nori", 1), "r": ("sc_rice", 3),
               "R": ("sc_rice", 4), "u": ("sc_rice", 1), "s": ("sc_chrome", 5), "k": ("sc_black", 0),
               "p": ("sc_pearl", 5), "W": ("sc_pearl", 7), "g": ("sc_black", 1), "q": ("sc_chrome", 2),
               "L": ("sc_led", 2), "T": ("sc_tail", 3), "G": ("sc_chrome", 6), "b": ("sc_black", 1),
               "h": ("sc_rim", 4), "H": ("sc_chrome", 7)}
    return _draw(rows, colours).render()
