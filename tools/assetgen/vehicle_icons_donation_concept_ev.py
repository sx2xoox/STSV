"""
The inventory icon (16x16) of the donation car 콘셉트 EV (concept_ev): seen from its left side, front to the left,
like the other vehicle icons. A pebble: a low nose with its white light bar, one long arc of dark glass over the
cabin falling to the tail and its red light bar, the pearl body over a satin black lower half with the cyan line
between them, flush aero wheels (pale discs, a cyan ring).
"""

import vehicle_icons
from vehicle_icons import icon, _draw


@icon("concept_ev")
def concept_ev():
    rows = ["................",
            "................",
            "................",
            "................",
            "......ppppp.....",
            "....pgGggggqpp..",
            "...pgGggggggqqp.",
            "..pwwwwwwwwwwwwp",
            ".yWWWWWWWWWWWWWt",
            "yVVVVVVVVVVVVVVt",
            "cccckkcccccckkcc",
            ".bkdrdkbbbbkdrdb",
            "..krhrk....krhrk",
            "...kkk......kkk.",
            "................",
            "................"]
    colours = {"p": ("pearl", 3), "w": ("pearl", 4), "W": ("pearl", 2), "V": ("pearl", 1), "g": ("tint", 1),
               "G": ("glass", 4), "q": ("tint", 2), "y": ("lamp", 5), "t": ("signal_red", 4),
               "c": ("glow_cyan", 3), "b": ("rubber", 1), "k": ("rubber", 0), "d": ("pearl", 2),
               "r": ("glow_cyan", 2), "h": ("tint", 1)}
    return _draw(rows, colours).render()


ICONS = {"concept_ev": vehicle_icons.ICONS["concept_ev"]}
