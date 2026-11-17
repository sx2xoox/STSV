"""
Inventory icons (16x16) of the 특별 탈것 (content/stsv/items/vehicles_fun.yml): textures/<id>_icon.png in the core pack (the
item's 'icon'; placed, each shows its 3D model). Pixel art on the shared Canvas like vehicle_icons.py, each from the side
that tells it best: the kick scooter and the skateboard side on (front to the left), the pogo stick upright, the broom
across with its straw to the right, the banana boat curved on its blue tubes, the segway from the front quarter, the
duck side on, the sled side on with its curled runners, the balloon chair under its bunch, the bull on its mat.
"""

from vehicle_icons import _draw

ICONS = {}


def icon(vid):
    def deco(fn):
        ICONS[vid] = fn
        return fn
    return deco


@icon("kickboard")
def kickboard():
    rows = ["................",
            "..kkKKkk........",
            "....sS..........",
            "....sS..........",
            "....tT..........",
            "....sS..........",
            "....sS..........",
            "....sS..........",
            "....sS..........",
            "....sS..........",
            "...jsS........f.",
            "...jgggggggggfF.",
            "..wwWdddddddwwW.",
            "..wWw.......wWw.",
            "...w.........w..",
            "................"]
    colours = {"k": ("rubber", 1), "K": ("blade", 3), "s": ("blade", 3), "S": ("blade", 2), "t": ("spectral", 3),
               "T": ("spectral", 2), "b": ("rubber", 2), "p": ("blade", 2), "j": ("spectral", 2), "g": ("rubber", 2),
               "d": ("blade", 2), "f": ("blade", 3), "F": ("signal_red", 1), "w": ("spectral", 2), "W": ("blade", 3)}
    return _draw(rows, colours).render()


@icon("pogo_stick")
def pogo_stick():
    rows = ["................",
            "..kkkk.cc.kkkk..",
            "..kkkkMccMkkkk..",
            "......OoO.......",
            "......OoO.......",
            "......OyO.......",
            "......OoO.......",
            "......OwO.......",
            "......OoO.......",
            ".....kkkkk......",
            "......sSs.......",
            "......SsS.......",
            "...rrrBBBrrr....",
            ".......p........",
            "......RRR.......",
            "......RRR......."]
    colours = {"k": ("rubber", 2), "c": ("rubber", 1), "M": ("blade", 3), "O": ("fox", 2), "o": ("fox", 4),
               "y": ("petal_yellow", 2), "w": ("car_white", 3), "s": ("blade", 3), "S": ("blade", 1),
               "r": ("rubber", 2), "B": ("steel", 3), "p": ("blade", 3), "R": ("rubber", 1)}
    return _draw(rows, colours).render()


@icon("skateboard")
def skateboard():
    rows = ["................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "kk..........kk..",
            ".kkkkkkkkkkkkk..",
            ".wWwWwWwWwWwWw..",
            "..rRfRrRfRrRf...",
            "....tt....tt....",
            "...cCc...cCc....",
            "...cCc...cCc....",
            "................",
            "................",
            "................"]
    colours = {"k": ("rubber", 1), "w": ("wood", 3), "W": ("wood", 2), "r": ("pepper", 2), "R": ("pepper", 3),
               "f": ("fire", 4), "t": ("blade", 2), "c": ("cream", 3), "C": ("steel", 2)}
    return _draw(rows, colours).render()


@icon("broom")
def broom():
    rows = ["................",
            "................",
            "................",
            "...........yYy..",
            "..........yYyYy.",
            "b.......rryYyYyY",
            "BhhhhhhhrrryYyYY",
            ".hhhhhhhrryYyYyY",
            "...g.mm..yYyYyY.",
            "...g.MM...yYyY..",
            "..ggg.......y...",
            "..gGg...........",
            "................",
            "................",
            "................",
            "................"]
    colours = {"b": ("brass", 3), "B": ("brass", 2), "h": ("wood_dark", 2), "r": ("fabric_red", 2),
               "y": ("straw", 2), "Y": ("straw", 4), "g": ("gold", 3), "G": ("gold", 2),
               "m": ("magic", 3), "M": ("magic", 1)}
    return _draw(rows, colours).render()


@icon("banana_boat")
def banana_boat():
    rows = ["................",
            "................",
            "................",
            "................",
            "gw.............d",
            "gyk....k......yd",
            ".yYy..kk..k..yY.",
            ".yYYyyyyyykyyYy.",
            "..yYYYYYYYYYYy..",
            "...oyyyyyyyyo...",
            "..bBbBbBbBbBb...",
            "..BbBbBbBbBbB...",
            ".wWwWwWwWwWwWw..",
            "................",
            "................",
            "................"]
    colours = {"g": ("leaf", 2), "d": ("doenjang", 1), "y": ("petal_yellow", 1), "Y": ("petal_yellow", 2), "w": ("car_white", 3),
               "k": ("rubber", 1), "o": ("corn", 1), "b": ("sapphire", 2), "B": ("sapphire", 3),
               "w": ("water", 2), "W": ("water", 3)}
    return _draw(rows, colours).render()


@icon("segway")
def segway():
    rows = ["................",
            "..kkkkLLkkkk....",
            ".....wWw........",
            ".....wWw........",
            ".....wWw........",
            ".....wWw........",
            ".....wWw........",
            ".....wWw........",
            ".....wWw........",
            "..ww.wWw.ww.....",
            ".wWWw.....wWw...",
            "tTttbbbbbbtTtt..",
            "tTTtggggggtTTt..",
            "tTttsssssstTtt..",
            ".tt........tt...",
            "................"]
    colours = {"k": ("rubber", 1), "L": ("glow_cyan", 2), "w": ("car_white", 2), "W": ("car_white", 3),
               "s": ("steel", 2), "t": ("rubber", 1), "T": ("steel", 3), "b": ("rubber", 2),
               "g": ("glow_cyan", 1)}
    return _draw(rows, colours).render()


@icon("toy_duck")
def toy_duck():
    rows = ["................",
            "....yyy.........",
            "...yYYYy........",
            "..yYeYYYy.......",
            "ooyYYYYYy.......",
            "OOOyYpYYy....yy.",
            ".ooyYYYy....yYy.",
            "...yYYYyyyyyYYy.",
            "..yYYYYYYYYYYYy.",
            "..yYYYwwwYYYYYy.",
            "..yYYYYwwwYYYYy.",
            "..dyYYYYYYYYYyd.",
            "...ddyyyyyyydd..",
            "....dddddddd....",
            "................",
            "................"]
    colours = {"y": ("corn", 1), "Y": ("corn", 2), "e": ("rubber", 0), "o": ("fox", 3), "O": ("fox", 2),
               "p": ("pink", 2), "w": ("corn", 3), "d": ("corn", 0)}
    c = _draw(rows, colours)
    return c.render()


@icon("sled")
def sled():
    rows = ["................",
            "................",
            "................",
            "................",
            "...............b",
            ".bw...........rb",
            "y.w.rrrrrrrrrrrr",
            ".bw.RRRgRRgRRgRR",
            "..wwwwwwwwwwwwww",
            "...p...p....p...",
            ".s.p...p....p...",
            "..sssssssssssss.",
            "................",
            "................",
            "................",
            "................"]
    colours = {"b": ("wood", 2), "w": ("wood", 3), "y": ("gold", 3), "r": ("pepper", 3), "R": ("pepper", 2),
               "g": ("gold", 3), "p": ("wood", 2), "s": ("blade", 3)}
    return _draw(rows, colours).render()


@icon("balloon_chair")
def balloon_chair():
    rows = ["....pp..rr......",
            "...pPpprRrr.....",
            "..bbpprrRryy....",
            ".bBbbgrrryYy....",
            ".bbbgGgryyyy....",
            "..b.ggg..yy.....",
            "....l.l.l.......",
            ".....lll........",
            "......l.........",
            "...h..l..h......",
            "...hhhhhhh......",
            "...wcccccw......",
            "...wwcccww......",
            "...wWwWwWw......",
            "....wWwWw.......",
            "................"]
    colours = {"p": ("sakura", 2), "P": ("sakura", 3), "r": ("ruby", 2), "R": ("ruby", 3), "b": ("sapphire", 2),
               "B": ("sapphire", 3), "g": ("slime", 1), "G": ("slime", 3), "y": ("petal_yellow", 1),
               "Y": ("petal_yellow", 2), "l": ("pearl", 2), "h": ("brass", 3), "w": ("straw", 2), "W": ("straw", 3),
               "c": ("fabric_red", 2)}
    return _draw(rows, colours).render()


@icon("rodeo_bull")
def rodeo_bull():
    rows = ["................",
            "................",
            "..c.......c.....",
            "..cc.....cc.....",
            "...hhhhhhh......",
            "..mhehhhhehh....",
            "..mmhhssshhhhhh.",
            "..mmhhSSShhhhhht",
            "...hhhhhhhhhhh.t",
            "....bbbbbbbbbb..",
            ".....hh..hh.....",
            "........p.......",
            "........p.......",
            "..vvvvvvvvvvvv..",
            ".wVVVVVVVVVVVVw.",
            "..wwwwwwwwwwww.."]
    colours = {"c": ("cream", 3), "h": ("leather", 2), "e": ("rubber", 0), "m": ("bean", 3), "s": ("wood_dark", 2),
               "S": ("burlap", 3), "t": ("bark", 1), "b": ("plush", 2), "p": ("steel", 3), "v": ("vermilion", 3),
               "V": ("vermilion", 2), "w": ("car_white", 3)}
    return _draw(rows, colours).render()
