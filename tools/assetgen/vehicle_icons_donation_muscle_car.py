"""
The 머슬카's inventory icon (16x16, textures/muscle_car_icon.png): seen from its left side like the other cars (front
to the left): the very long hood with the black stripe, the low squared-off cabin set well back with its near-flat
rear glass, the short deck, the bright beltline crease, a white quad-lamp front and a red tail band, chrome bumper
ends, fat tyres on dark rims with a glint and an orange calliper.
"""

from vehicle_icons import icon, _draw
import models.vehicles_donation_muscle_car  # noqa: F401  (registers the mc_ ramps)


@icon("muscle_car")
def muscle_car():
    rows = ["................",
            "................",
            "................",
            "................",
            "................",
            "......OOOOO.....",
            ".....gqgGoggqo..",
            "....gqgggoggqqo.",
            "SSSSSooooooooSSO",
            "LooooooooooooooR",
            "LWWWWWWWWWWWWWWR",
            "mVkkkkVVVVVkkkkm",
            ".NkhHkNNNNNkhHk.",
            "..kchk.....kchk.",
            "...kk.......kk..",
            "................"]
    colours = {"S": ("mc_stripe", 1), "s": ("mc_stripe", 2), "o": ("mc_orange", 3), "O": ("mc_orange", 4),
               "W": ("mc_orange", 4), "V": ("mc_orange", 2), "N": ("mc_orange", 1), "g": ("mc_black", 1),
               "G": ("mc_chrome", 4), "q": ("mc_chrome", 1), "L": ("mc_led", 2), "R": ("mc_tail", 3),
               "m": ("mc_chrome", 6), "k": ("mc_black", 0), "h": ("mc_rim", 2), "H": ("mc_chrome", 6),
               "c": ("mc_caliper", 1)}
    return _draw(rows, colours).render()
