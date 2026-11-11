"""
Inventory icons (16x16) of the 통통배 and the 지게: textures/<id>_icon.png in the core pack (the item's 'icon'; the boat
on the water and the 지게 on a back show their 3D models). Pixel art on the shared Canvas like vehicle_icons.py: the boat
from its left side (bow to the left), the white hull on its blue band and red bottom, the wheelhouse with its windows,
the stack and the flag; the 지게 from the front, its posts splayed into an A, a bundle of firewood lashed on.
"""

from vehicle_icons import _draw

ICONS = {}


def icon(vid):
    def deco(fn):
        ICONS[vid] = fn
        return fn
    return deco


@icon("fishing_boat")
def fishing_boat():
    rows = ["................",
            "..........fy....",
            "..........fbg...",
            "......k...m.....",
            "......k.RRRRRR..",
            "......kwGgwGgw..",
            "......KwgGwgGw..",
            "a.....SwwwwwwwS.",
            "aannoo.wwwwwwwSS",
            ".aBBBBBBBBBBBBBB",
            ".aHHHHtHHHHHtHHH",
            "..HHHHttHHHttHH.",
            "...hhhhhhhhhhh..",
            "....rrrrrrrrr...",
            "................",
            "................"]
    colours = {"f": ("fabric_red", 3), "y": ("petal_yellow", 4), "b": ("denim", 3), "g": ("leaf", 3),
               "k": ("rubber", 1), "K": ("rubber", 2), "S": ("wood", 3), "m": ("wood", 3),
               "R": ("water", 4), "w": ("car_white", 4), "W": ("car_white", 3), "G": ("glass", 4), "g2": ("tint", 2),
               "a": ("wood_dark", 3), "n": ("leaf_dark", 3), "o": ("fox", 4),
               "B": ("water", 3), "H": ("car_white", 4), "h": ("car_white", 3), "t": ("rubber", 1), "r": ("fabric_red", 2)}
    colours["g"] = ("tint", 2)
    c = _draw(rows, colours)
    c.put(11, 2, "leaf", 3, z=1)
    return c.render()


@icon("jige")
def jige():
    rows = ["................",
            ".....p....p.....",
            ".....pxxxxp.....",
            ".....p....p.....",
            "....pxxxxxxp....",
            "...LLLLLLLLLL...",
            "...lBBBsBBBBl...",
            "...LLLLLLLLLL...",
            "...lBBBsBBBBl...",
            "....pxxxxxxxp...",
            "...pq......qp...",
            "...p........p...",
            "...p.xxxxxx.p...",
            "..p..........p..",
            "..p..........p..",
            "..d..........d.."]
    colours = {"p": ("wood", 3), "x": ("wood", 2), "L": ("bark", 2), "B": ("bark", 3), "l": ("hinoki", 4),
               "s": ("straw", 4), "q": ("straw", 3), "d": ("bark", 1)}
    return _draw(rows, colours).render()
