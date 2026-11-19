"""
The inventory icon (16x16) of the donation car 클래식 로드스터 (classic_roadster; models/vehicles_donation_classic_roadster.py),
registered in vehicle_icons.ICONS like the others: seen from its left side, front to the left. The low chrome-framed
screen, the red bench and the folded black top over a long two-tone body (cherry red above the chrome spear, a cream
cove below), the wing crowns over the wheels, a round lamp at each end, chrome bumpers and whitewall tyres.
"""

import vehicle_icons as VI
from vehicle_icons import icon, _draw, TIRES


@icon("classic_roadster")
def classic_roadster():
    rows = ["................",
            "................",
            "................",
            "................",
            "................",
            "......mm........",
            "......Gm...rr...",
            ".uuu..gm..rRRss.",
            "UuuuUUUUUUUUUuuu",
            "yVVVVVVVVVVVVVVt",
            "rrrkkkCCCCrkkkrr",
            "BrkwHwkrrrkwHwkB",
            "..kwhwk...kwhwk.",
            "...kkk.....kkk..",
            "................",
            "................"]
    colours = {"u": ("ruby", 3), "U": ("ruby", 2), "V": ("ruby", 1), "r": ("cream", 2), "C": ("blade", 5),
               "B": ("blade", 4), "m": ("blade", 5), "G": ("glass", 4), "g": ("tint", 2), "R": ("crimson", 2),
               "s": ("rubber", 0), "y": ("lamp", 5), "t": ("signal_red", 4), "w": ("cream", 4),
               **TIRES}
    return _draw(rows, colours).render()


ICONS = {"classic_roadster": VI.ICONS["classic_roadster"]}
