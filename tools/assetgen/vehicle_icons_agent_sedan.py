"""
The inventory icon (16x16, textures/agent_sedan_icon.png) of 검은 세단 · 요원 K (agent_sedan, the 마을버스 secret
mission's reward): seen from its left side, front to the left, like the other vehicle icons. A long low black sedan:
the white LED eye at the nose, a long hood, the dark glasshouse with a thin chrome line round it, the short boot with
its whip antenna, the red tail bar, the blue line along the sill, gunmetal wheels with a bright lip.
"""

import vehicle_icons
from vehicle_icons import icon, _draw
import models.vehicles_agent_sedan  # noqa: F401  (registers the ag_ ramps the icon paints with)


@icon("agent_sedan")
def agent_sedan():
    rows = ["................",
            "..............a.",
            "..............a.",
            "..............a.",
            ".....cccccc...a.",
            "....cgGgqggc..a.",
            "...cgGggqgggc.a.",
            "wwwwwwwwwwwwwwww",
            "LVVVVVVVVVVVVVVt",
            "VVkkkVVVVVVkkkVV",
            "NkhHhkNNNNkhHhkN",
            ".khhhkbbbbkhhhk.",
            "..kkk......kkk..",
            "................",
            "................",
            "................"]
    colours = {"w": ("ag_black", 6), "V": ("ag_black", 3), "N": ("ag_black", 1), "g": ("ag_trim", 0),
               "G": ("ag_trim", 3), "q": ("ag_trim", 1), "c": ("ag_chrome", 6), "a": ("ag_trim", 2),
               "L": ("ag_led", 2), "t": ("ag_tail", 3), "k": ("ag_trim", 0), "h": ("ag_rim", 3),
               "H": ("ag_chrome", 7), "b": ("ag_blue", 4)}
    return _draw(rows, colours).render()


ICONS = {"agent_sedan": vehicle_icons.ICONS["agent_sedan"]}
