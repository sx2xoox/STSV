"""
The inventory icon (16x16) of the donation car 클래식 로드스터 (classic_roadster; models/vehicles_donation_classic_roadster.py),
registered in vehicle_icons.ICONS like the others: seen from its left side, front to the left. A long racing-green
body: the front fender's crown over the front wheel, the cut-down door, the rear fender rising again over the rear
wheel; the low chrome-framed windscreen, a tan bucket seat and the folded black top behind it, the chrome strip along
the flank, a round headlamp and a red tail lamp, chrome bumpers, wire wheels in whitewall tyres.
"""

import vehicle_icons as VI
import models.vehicles_donation_classic_roadster  # noqa: F401 (registers its ramps: rd_green, rd_chrome ...)
from vehicle_icons import icon, _draw


@icon("classic_roadster")
def classic_roadster():
    rows = ["................",
            "................",
            "................",
            "................",
            "................",
            "......m.........",
            "......mg..T.....",
            "..UUUUmg.tTKKU..",
            ".UVVVVVuuuuVVVVU",
            "lVoooVsssssoooVr",
            "BowwwovvvvowwwoB",
            ".owhwo....owhwo.",
            ".owwwo....owwwo.",
            "..ooo......ooo..",
            "................",
            "................"]
    colours = {"U": ("rd_green", 4), "u": ("rd_green", 3), "V": ("rd_green", 2), "v": ("rd_green", 1),
               "m": ("rd_chrome", 7), "g": ("glass", 3), "T": ("rd_tan", 4), "t": ("rd_tan", 2), "K": ("rd_canvas", 1),
               "l": ("rd_lens", 3), "r": ("rd_tail", 3), "B": ("rd_chrome", 6), "s": ("rd_chrome", 7),
               "o": ("rd_tyre", 0), "w": ("rd_white", 2), "h": ("rd_chrome", 6)}
    return _draw(rows, colours).render()


ICONS = {"classic_roadster": VI.ICONS["classic_roadster"]}
