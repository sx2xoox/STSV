"""
Inventory icons (16x16) of the farm furniture (models/furniture_farm.py): textures/<id>_icon.png in the core pack
(the item's 'icon'; placed furniture shows its 3D model). Drawn with the shared pixelart Canvas and palette
materials like furniture_icons.py (light from the top left, outline in the darkest step).
"""

from stsv_assets.pixelart import Canvas

ICONS = {}

FRONT = (0, 0, 1)         # facing the viewer


def icon(pid):
    def deco(fn):
        ICONS[pid] = fn
        return fn
    return deco


# the scarecrow, pixel by pixel (seen from the front; the hat's brim dips to the left like the model's)
SCARECROW = ["......HHHH......",
             ".....HhHHHh.....",
             ".....bbbbFbh....",
             "....HHHHHHHHHHh.",
             ".HHHHHHKKKKk....",
             "....KKEKKEKk....",
             "....KPKKKKPk....",
             "....KMKKKKMk....",
             ".....KMMMMk.....",
             "..rRRRRSSRRRRr..",
             "SsRrRRRRRRRRrRsS",
             ".SRRrDDRRRRrRRS.",
             "....LLLLLLLL....",
             "....RrRRRRrR....",
             "....S.SWwS.S....",
             ".......Ww......."]
PIXELS = {  # char: (material, step, layer)
    "H": ("straw", 4, 9), "h": ("straw", 3, 9), "b": ("denim", 3, 9), "F": ("gold", 4, 9),
    "K": ("burlap", 4, 5), "k": ("burlap", 3, 5), "E": ("rubber", 1, 6), "P": ("pink", 3, 6), "M": ("wood_dark", 1, 6),
    "R": ("fabric_red", 3, 3), "r": ("fabric_red", 2, 3), "D": ("denim", 4, 4), "L": ("bean", 4, 4),
    "S": ("straw", 4, 2), "s": ("straw", 3, 2), "W": ("wood", 3, 1), "w": ("wood", 2, 1),
}


@icon("scarecrow")
def scarecrow():
    """Seen from the front: the straw hat cocked to one side, the sack head with button eyes and a sewn smile,
    the red plaid shirt with its arms out on the crossbar and straw at the cuffs, the rope belt, the post below."""
    c = Canvas()
    for y, row in enumerate(SCARECROW):
        for x, ch in enumerate(row):
            if ch != ".":
                mat, step, z = PIXELS[ch]
                c.put(x, y, mat, step, z=z)
    return c.render(contact=False)
