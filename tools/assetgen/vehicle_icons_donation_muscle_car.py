"""
The 머슬카's inventory icon (16x16, textures/muscle_car_icon.png): seen from its left side like the other cars (front
to the left): the long flat hood with its scoop, the short cabin set back, the fastback falling to the ducktail, the
black stripe over the top, chrome bumpers, fat wheels with bright rims.
"""

from vehicle_icons import icon, _draw, TIRES


@icon("muscle_car")
def muscle_car():
    rows = ["................",
            "................",
            "................",
            "................",
            "................",
            ".......obbo.....",
            "......gGgqqoo...",
            "..wbw.GgggqqoooW",
            "wwwwwwwwwwwwwwww",
            "yWWWWWWWWWWWWWWt",
            "VVVVVVVVlVVVVVVV",
            "mVkkkVVVVVVkkkVm",
            ".NkhHhNNNNNkhHhN",
            "..khhhk...khhhk.",
            "...kkk.....kkk..",
            "................"]
    colours = {"o": ("fox", 3), "w": ("fox", 4), "W": ("fox", 3), "V": ("fox", 2), "N": ("fox", 0), "b": ("rubber", 1),
               "s": ("fox", 4), "g": ("tint", 2), "G": ("glass", 4), "q": ("tint", 1), "y": ("lamp", 5),
               "t": ("signal_red", 4), "m": ("blade", 4), "l": ("blade", 4), **TIRES}
    return _draw(rows, colours).render()
