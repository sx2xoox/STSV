"""
Inventory icons (16x16) of the vehicles: textures/<id>_icon.png in the core pack (the item's 'icon'; the placed
vehicle shows its 3D models). Pixel art on the shared Canvas, palette materials, the outline added round the
outside like every other icon. Each vehicle is seen from its left side (front to the left), slightly from above,
the way it is recognised fastest: the patrol car's light bar and band, the truck's flat cab and open bed, the
cart's canopy and bag.
"""

from stsv_assets.pixelart import Canvas

ICONS = {}


def icon(vid):
    def deco(fn):
        ICONS[vid] = fn
        return fn
    return deco


def _draw(rows, colours):
    c = Canvas()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colours:
                mat, step = colours[ch]
                c.put(x, y, mat, step, z=1)
    return c


TIRES = {"k": ("rubber", 1), "K": ("rubber", 2), "s": ("steel", 4), "S": ("steel", 2)}


@icon("police_car")
def police_car():
    rows = ["................",
            "................",
            "................",
            "................",
            "......ru........",
            ".....wwwwww.....",
            "....wGggwggw....",
            "...wgGggwgggw...",
            ".wwwwwwwwwwwwww.",
            ".ywbbwbbwwwwwwr.",
            ".WbbbbbbbbbbbbW.",
            ".VVkkkVVVVkkkVV.",
            ".BkSskBBBBkSskB.",
            "..kkk.....kkk...",
            "................",
            "................"]
    colours = {"w": ("car_white", 4), "W": ("car_white", 3), "V": ("car_white", 2), "g": ("tint", 2), "G": ("glass", 4),
               "b": ("police_blue", 2), "B": ("police_blue", 1), "r": ("signal_red", 3), "u": ("signal_blue", 3),
               "y": ("lamp", 4), **TIRES}
    return _draw(rows, colours).render()


@icon("light_truck")
def light_truck():
    rows = ["................",
            "................",
            "................",
            ".ttttt..........",
            ".gGggT..........",
            ".gGggT.i...i....",
            ".gggTT.i...i....",
            ".TTTTTiiiiiiiii.",
            ".TTTTTaaaaaaaaa.",
            ".yTTTTAAAAAAAAr.",
            ".TTTTTfffffffff.",
            ".TkkkTfffkkkfff.",
            "..kSsk...kSsk...",
            "..kkk....kkk....",
            "................",
            "................"]
    colours = {"t": ("truck_blue", 4), "T": ("truck_blue", 3), "g": ("tint", 2), "G": ("glass", 4), "i": ("steel", 3),
               "a": ("blade", 4), "A": ("steel", 5), "f": ("iron", 2), "y": ("lamp", 4), "r": ("signal_red", 3), **TIRES}
    return _draw(rows, colours).render()


@icon("golf_cart")
def golf_cart():
    rows = ["................",
            "................",
            ".ggggggggggggg..",
            ".GGGGGGGGGGGGG..",
            "..l.......l.....",
            "..l.......l.RR..",
            "..l......Ll.RR..",
            "..l......Ll.rr..",
            ".ccc.....Ll.RR..",
            ".cccl.LLLLLlRR..",
            ".yccccccccccccc.",
            ".CCkkCCCCCCkkCC.",
            "..kssk....kssk..",
            "...kk......kk...",
            "................",
            "................"]
    colours = {"g": ("leaf_dark", 3), "G": ("cream", 4), "l": ("steel", 4), "L": ("leather", 3), "R": ("fabric_red", 3),
               "r": ("car_white", 4), "c": ("cream", 4), "C": ("cream", 3), "y": ("lamp", 4), **TIRES}
    return _draw(rows, colours).render()
