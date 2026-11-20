"""
The inventory icon (16x16) of the donation car 콘셉트 EV (concept_ev): seen from its left side, front to the left,
like the other vehicle icons. A low wedge: the knife-edge nose with its white LED bar in a black visor, the hood
rising straight into one long canopy of black glass, the floating C-pillar falling to the tail with light under it,
the red bar across the tail, the satin blade along the side with the cyan line under it, big aero wheels (pale
turbine covers, a cyan ring).
"""

import vehicle_icons
from vehicle_icons import icon, _draw
import models.vehicles_donation_concept_ev  # noqa: F401  (registers the ev_* ramps the icon paints with)


@icon("concept_ev")
def concept_ev():
    rows = ["................",
            "................",
            "................",
            "................",
            "......ggggggg...",
            "....gGgggggggWW.",
            "..wgGggggggq..WW",
            "wWWWWWWWWWWWWWWW",
            "LVVVVVVVVVVVVVVt",
            "VkkkkkVVVVkkkkkV",
            "scdddcsssscdddcs",
            ".cdrdcaaaacdrdc.",
            ".cdddc....cdddc.",
            "..ccc......ccc..",
            "................",
            "................"]
    colours = {"w": ("ev_pearl", 7), "W": ("ev_pearl", 6), "V": ("ev_pearl", 4), "g": ("ev_glass", 1),
               "G": ("ev_glass", 4), "q": ("ev_glass", 0), "L": ("ev_led", 2), "t": ("ev_tail", 3),
               "s": ("ev_satin", 1), "a": ("ev_cyan", 4), "b": ("ev_satin", 0), "k": ("ev_satin", 0),
               "c": ("ev_satin", 0), "d": ("ev_pearl", 4), "r": ("ev_cyan", 3)}
    return _draw(rows, colours).render()


ICONS = {"concept_ev": vehicle_icons.ICONS["concept_ev"]}
