"""
Inventory icons (16x16) of the articulated farm vehicles (content/stsv/items/vehicles_farm.yml): textures/<id>_icon.png
in the core pack, like vehicle_icons.py. Each is seen from its left side, the pulling unit in front (to the left): the
ox with its yoke before the loaded cart and its big spoked wheel; the tiller's cream hopper, flywheel and handlebars
before the red trailer; the orange tractor with its big rear wheel, canopy and stack before a wagon of hay.
"""

from vehicle_icons import _draw

ICONS = {}


def icon(vid):
    def deco(fn):
        ICONS[vid] = fn
        return fn
    return deco


@icon("ox_cart")
def ox_cart():
    # the ox to the left (horns, the yoke over its withers, a brass bell, pale shins), the rope to the cart; the cart's
    # deck with straw sheaves and a straw sack on it, its big spoked wheel (iron tyre, wooden spokes, a dark hub)
    rows = ["................",
            "................",
            "...........sSs..",
            ".H.Y......sSSSs.",
            ".HYyY.....gGgGg.",
            "hhhyCCCCC.gGgGgG",
            "ehhCCCCCCCWWWWWW",
            "hhhcCCCCCrwwwwww",
            "mhbccCCCc.ooooo.",
            "mm..cccc.oDxDxDo",
            "....LL.LLoxDDDxo",
            "....LL.LLoDDODDo",
            "....LL.LLoxDDDxo",
            "....kk.kkoDxDxDo",
            "..........ooooo.",
            "................"]
    colours = {"H": ("bean", 4), "Y": ("wood_dark", 3), "y": ("wood_dark", 2), "h": ("plush", 3), "e": ("iron", 0),
               "m": ("bean", 3), "b": ("brass", 4), "C": ("plush", 3), "c": ("plush", 2), "L": ("plush", 2), "k": ("onggi", 1),
               "r": ("burlap", 3), "s": ("straw", 3), "S": ("straw", 4), "g": ("straw", 2), "G": ("straw", 3),
               "W": ("wood", 3), "w": ("wood", 2), "o": ("iron", 3), "D": ("wood", 3), "x": ("wood_dark", 0), "O": ("iron", 1)}
    return _draw(rows, colours).render()


@icon("power_tiller")
def power_tiller():
    # the tiller to the left: the tall exhaust, the cream water hopper over the dark engine, the red tank, the flywheel
    # (a red disc in a pale rim), its lugged wheel; the handlebars rising back to the grips over the trailer's seat;
    # the red trailer with white fertilizer sacks and its own wheel
    rows = ["................",
            "......e.........",
            "......e.........",
            ".cccc.E.........",
            ".cCCc.tt........",
            ".cccc.tT....ss..",
            ".IIIIoooo.b.sSsS",
            "yIIIofOfo.gRRRRR",
            ".IIIoOOOogRRrrrr",
            "..kkkoooo.RrrrrR",
            ".kWWWk.....kkk..",
            ".kWOWk....kWOWk.",
            ".kWWWk....kWWWk.",
            "..kkk......kkk..",
            "................",
            "................"]
    colours = {"e": ("soot", 2), "E": ("soot", 1), "c": ("cream", 3), "C": ("cream", 1), "t": ("vermilion", 3), "T": ("vermilion", 2),
               "I": ("iron", 2), "y": ("hazard", 1), "o": ("blade", 3), "f": ("vermilion", 3), "O": ("vermilion", 2), "b": ("rubber", 3),
               "g": ("steel", 4), "R": ("vermilion", 3), "r": ("vermilion", 2), "s": ("rice", 2), "S": ("rice", 3),
               "k": ("rubber", 1), "W": ("vermilion", 2)}
    return _draw(rows, colours).render()


@icon("tractor_trailer")
def tractor_trailer():
    # the orange tractor to the left: canopy on its roll bar, the stack, the long hood and the grille, a small front
    # wheel and the big rear wheel with its cream rim; behind it a wagon with hay bales
    rows = ["................",
            "....cccccc......",
            ".....p...p......",
            "..e..p...p......",
            "..e..p...p......",
            "GOOOOOOOFFF.....",
            "GOOoOOOOFFF.SsSs",
            "GOoOOOOkkkkkSsSs",
            "dddddkkkCCCkwwww",
            "dddddkkCCCCCkdwd",
            ".kkk.kkCCrCCkd.d",
            "kcck.kkCCCCCk.kk",
            ".kk...kkCCCkk.kc",
            ".......kkkkk....",
            "................",
            "................"]
    colours = {"c": ("cream", 3), "p": ("iron", 2), "e": ("soot", 2), "O": ("fox", 3), "o": ("fox", 1),
               "F": ("fox", 2), "G": ("cream", 2), "d": ("iron", 2), "k": ("rubber", 1), "C": ("cream", 2), "r": ("steel", 4),
               "S": ("straw", 4), "s": ("straw", 3), "w": ("wood_dark", 2)}
    return _draw(rows, colours).render()
