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


# tyres: a dark ring round a chrome hub cap with a glint (k tyre, h hub, H glint)
TIRES = {"k": ("rubber", 1), "h": ("blade", 3), "H": ("blade", 5)}


@icon("police_car")
def police_car():
    rows = ["................",
            "................",
            "................",
            "......rRUu......",
            ".....wmmmmw.....",
            "....wgGggwgGw...",
            "...wgGgggwggGw..",
            ".wWWWWWWWWWWWWw.",
            ".yVVVVVVVVVVVVr.",
            "sVbbbbbbbbbbbbVs",
            ".VVkkVVVVVVVkkVV",
            ".NkhHkNNNNNkhHkN",
            "..khhk.....khhk.",
            "...kk.......kk..",
            "................",
            "................"]
    colours = {"w": ("car_white", 5), "W": ("car_white", 4), "V": ("car_white", 3), "g": ("tint", 2),
               "G": ("glass", 4), "m": ("blade", 4), "b": ("police_blue", 3),
               "N": ("police_blue", 1), "s": ("blade", 3),
               "r": ("signal_red", 2), "R": ("signal_red", 4), "u": ("signal_blue", 2), "U": ("signal_blue", 4),
               "y": ("lamp", 5), **TIRES}
    c = _draw(rows, colours)
    c.put(14, 8, "signal_red", 3, z=1)
    return c.render()


@icon("light_truck")
def light_truck():
    rows = ["................",
            "................",
            "vvvvvv..........",
            ".gGggT..........",
            ".ggGgT..........",
            ".tttttiiiiiiiiii",
            ".TTTTTaaAaaaaaAa",
            ".yTTTTbbAbbbbbAr",
            ".TTTTTffffffffff",
            "sddkkdFAFFkkFFM.",
            ".dkhHkd...khHkM.",
            "..khhk....khhk..",
            "...kk......kk...",
            "................",
            "................",
            "................"]
    colours = {"v": ("car_white", 5), "t": ("truck_blue", 4), "T": ("truck_blue", 3), "d": ("truck_blue", 2),
               "g": ("tint", 2), "G": ("glass", 4), "i": ("steel", 5), "a": ("steel", 4), "b": ("steel", 3),
               "A": ("steel", 2), "f": ("iron", 2), "F": ("iron", 1), "y": ("lamp", 5), "r": ("signal_red", 3),
               "s": ("blade", 3), "M": ("rubber", 2), **TIRES}
    return _draw(rows, colours).render()


@icon("golf_cart")
def golf_cart():
    rows = ["................",
            "................",
            ".gggggGGgggggg..",
            ".EEEEEEEEEEEEE..",
            "..l.......l.nN..",
            "..l.......l.RR..",
            "..l......Ll.WW..",
            "..l......Ll.RR..",
            ".ccc.....Ll.RR..",
            ".cpcl.SSSSSlRR..",
            ".yccccccccccccr.",
            ".CCkkCCCCCCkkCC.",
            "..khhk....khhk..",
            "...kk......kk...",
            "................",
            "................"]
    colours = {"g": ("leaf_dark", 3), "G": ("leaf_dark", 4), "E": ("cream", 5), "l": ("blade", 4),
               "L": ("leaf_dark", 3), "S": ("leaf_dark", 4), "R": ("fabric_red", 3), "W": ("car_white", 5),
               "n": ("denim", 4), "N": ("crimson", 4), "c": ("cream", 4), "C": ("cream", 3), "p": ("leaf_dark", 1),
               "y": ("lamp", 5), "r": ("signal_red", 3), "k": ("rubber", 1), "h": ("car_white", 4),
               "H": ("car_white", 5)}
    return _draw(rows, colours).render()


@icon("hand_cart")
def hand_cart():
    # the flared wooden bed seen from its left side: a dark rim rail with iron corner caps over two warm boards, iron
    # brackets at both ends, the rope coil hung on the side; a crate of beans and a tied sack over the rim; the lantern
    # hanging in front; the shaft running out to the left to its dark grip and steel ferrule, the prop stand under the
    # front; one chunky wheel (black tyre, steel rim, spokes, a bright hub) under the middle
    rows = ["................",
            "................",
            "...........ee...",
            ".....cvVvc.rr...",
            ".....pwwwpyYYy..",
            "...aIRRRRRRRRRRI",
            "...n.JUUUUUoOoJ.",
            "...L.juuuuuOxOj.",
            "...n.JBBBBBoOoJ.",
            ".....jqqqzzzqqj.",
            "fggHHtSSzmmmzSS.",
            ".....t.zmdsdmz..",
            ".....t.zmshsmz..",
            "....tt.zmdsdmz..",
            "........zmmmz...",
            ".........zzz...."]
    colours = {"e": ("burlap", 4), "r": ("straw", 3), "y": ("burlap", 3), "Y": ("burlap", 4),
               "c": ("wood", 4), "v": ("bean", 4), "V": ("bean", 3), "p": ("wood", 2), "w": ("wood", 3),
               "a": ("iron", 3), "n": ("iron", 2), "L": ("glow_amber", 4), "l": ("glow_amber", 5),
               "I": ("steel", 3), "R": ("wood_dark", 3), "J": ("steel", 3), "j": ("steel", 2),
               "U": ("plush", 4), "u": ("plush", 3), "B": ("plush", 4), "q": ("plush", 2),
               "o": ("burlap", 3), "O": ("burlap", 4), "x": ("wood_dark", 1),
               "f": ("steel", 4), "g": ("leather", 2), "H": ("wood_dark", 3), "S": ("wood_dark", 2), "t": ("steel", 2),
               "z": ("rubber", 1), "m": ("steel", 4), "d": ("iron", 1), "s": ("steel", 3), "h": ("blade", 5)}
    return _draw(rows, colours).render()
