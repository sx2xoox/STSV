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
    # the bow to the left, raised; the wheelhouse aft with its windows, the stack behind it smoking, the mast and the
    # 오색 flag; green net and an orange buoy on the fore deck; the white hull on its blue band, a tyre fender, red below
    rows = ["................",
            "...........fy...",
            "...........fgb..",
            "........s..m....",
            "........k.RRRRR.",
            "........k.wGwGW.",
            "........k.wqwqW.",
            "a.......kewwwwW.",
            "aannoo..eewwwwW.",
            ".abbbbbbbbbbbbbb",
            ".aHHHHHHHHHHHHHH",
            "..HHtHHHHHHHtHH.",
            "...hhhhhhhhhhhh.",
            "....rrrrrrrrrr..",
            "................",
            "................"]
    colours = {"f": ("fabric_red", 3), "y": ("petal_yellow", 4), "g": ("leaf", 3), "b": ("denim", 3),
               "s": ("stone", 4), "k": ("rubber", 1), "m": ("wood", 3), "R": ("water", 4),
               "w": ("car_white", 5), "W": ("car_white", 3), "G": ("glass", 4), "e": ("wood", 3),
               "a": ("wood_dark", 3), "n": ("leaf_dark", 3), "o": ("fox", 4),
               "H": ("car_white", 4), "h": ("car_white", 3), "t": ("rubber", 1), "r": ("fabric_red", 2), "q": ("tint", 2)}
    c = _draw(rows, colours)
    for x in range(2, 16):
        c.put(x, 9, "water", 3, z=1)                              # the sea-blue band under the rail
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
