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


@icon("raft")
def raft():
    # the bow to the left: logs (their sawn ends pale) under a plank deck, the paper lantern at the bow, a creel, the stump,
    # the sweep trailing over the stern
    rows = ["................",
            "................",
            "................",
            "..L.............",
            "..L..........o..",
            "..g.........o...",
            "..g..bb.ss.o....",
            "..g..bb.sso.....",
            ".ddddddddddddd..",
            "eEEeEEeEEeEEeEo.",
            "EEEEEEEEEEEEEE.o",
            ".kkkkkkkkkkkk..O",
            "...............O",
            "................",
            "................",
            "................"]
    colours = {"L": ("washi_lit", 3), "g": ("bamboo", 3), "b": ("bamboo", 4), "s": ("bark", 2), "d": ("wood", 4),
               "e": ("hinoki", 4), "E": ("bark", 3), "k": ("bark", 1), "o": ("wood_dark", 2), "O": ("wood", 4)}
    return _draw(rows, colours).render()


@icon("rowboat")
def rowboat():
    # the bow to the left, raised, with the lantern on it; the planked side with its dark wale and tarred bottom, an
    # oar down into the water amidships, two rods on the stern quarter
    rows = ["................",
            "................",
            ".L...........r..",
            ".L..........R...",
            ".g.........R.r..",
            ".g........R.R...",
            "aa.........R....",
            "aAaa.....o.R....",
            ".aAAaaaaaoaaaaa.",
            ".awwwwwwwwowwww.",
            "..wWwwwWwwwoWw..",
            "..ttttttttttoo..",
            "...tttttttt.OO..",
            "................",
            "................",
            "................"]
    colours = {"L": ("washi_lit", 3), "g": ("bamboo", 3), "a": ("wood_dark", 3), "A": ("wood_dark", 2), "w": ("wood", 4),
               "W": ("wood", 2), "t": ("soot", 1), "o": ("wood_dark", 2), "O": ("wood", 4), "R": ("rubber", 2), "r": ("fabric_red", 3)}
    return _draw(rows, colours).render()


@icon("coastal_boat")
def coastal_boat():
    # the flared white bow to the left with its rail, the glazed cabin, the row of lit squid lamps over it, the radar,
    # the green stripe and the green bottom
    rows = ["................",
            "......ddd.......",
            ".....sssssssss..",
            ".....y.y.y.y.y..",
            ".....RRRRRR..s..",
            ".ss..wGGwGw..s..",
            ".s.s.wGGwGw..s..",
            "wWs.swwwwOwbbs..",
            "wwwwwwwwwwwwwww.",
            ".ggggggggggggggw",
            ".wwwwwwwwwwwwww.",
            "..hhhhhhhhhhhh..",
            "..nnnnnnnnnnn...",
            "...nnnnnnnnn....",
            "................",
            "................"]
    colours = {"d": ("car_white", 4), "s": ("steel", 3), "y": ("lamp", 4), "R": ("sign_green", 3), "w": ("car_white", 5),
               "W": ("car_white", 3), "G": ("glass", 3), "O": ("fox", 4), "b": ("denim", 3), "g": ("sign_green", 3),
               "h": ("car_white", 3), "n": ("bottle_green", 2)}
    return _draw(rows, colours).render()


@icon("trawler")
def trawler():
    # the high bow to the left, the white bridge forward with the mast and the funnel behind it, the working deck and
    # the orange gantry over the stern; the navy hull on its red bottom
    rows = ["......m.........",
            ".....mmm........",
            "....RRRRR.......",
            "....wGGGw.f...oo",
            "....wGGGw.F..oo.",
            "....wwwww.f..o..",
            "n..wwpwpwwf..o..",
            "nn.wwwwwwwwc.o..",
            "nNnnnnnnnnnnnnnn",
            "nnNnnnnnnnnnnnnn",
            ".nnnnNnnnnnNnnn.",
            ".bbbbbbbbbbbbbb.",
            "..rrrrrrrrrrrr..",
            "...rrrrrrrrrr...",
            "................",
            "................"]
    colours = {"m": ("car_white", 4), "R": ("police_blue", 3), "w": ("car_white", 5), "G": ("glass", 3), "f": ("police_blue", 2),
               "F": ("fabric_red", 3), "o": ("fox", 4), "p": ("glass", 2), "c": ("denim", 3), "n": ("police_blue", 2),
               "N": ("police_blue", 1), "b": ("car_white", 4), "r": ("fabric_red", 2)}
    return _draw(rows, colours).render()


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
