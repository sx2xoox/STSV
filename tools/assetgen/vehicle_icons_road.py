"""
Inventory icons (16x16) of the road vehicles (content/stsv/items/vehicles_road.yml): textures/<id>_icon.png in the core
pack, drawn like vehicle_icons.py (pixel art on the shared Canvas, palette materials, the outline added round the
outside), each seen from its left side with the front to the left.
"""

from stsv_assets.pixelart import Canvas

ICONS = {}


def icon(vid):
    def deco(fn):
        ICONS[vid] = fn
        return fn
    return deco


def box(c, x0, y0, x1, y1, mat, step, z=1):
    """A filled rectangle of pixels, both corners included."""
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            c.put(x, y, mat, step, z)


def wheel(c, cx, cy, r, tyre=("rubber", 1), rim=("blade", 3), hub=("blade", 5), spokes=False, knobs=False):
    """A wheel seen from the side: a tyre ring (knobs: every other pixel of it lighter), a rim ring inside it, a hub,
    and (spokes) a few spoke pixels between, the rest left see-through."""
    for x in range(cx - r - 1, cx + r + 2):
        for y in range(cy - r - 1, cy + r + 2):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d > r + 0.45:
                continue
            if d > r - 0.75:
                c.put(x, y, tyre[0], tyre[1] + (1 if knobs and (x + y) % 2 else 0), 3)
            elif d > r - 1.6:
                c.put(x, y, *rim, 3)
            elif d < 0.5:
                c.put(x, y, *hub, 4)
            elif spokes:
                if x == cx or y == cy:
                    c.put(x, y, rim[0], rim[1] - 1, 3)
            else:
                c.put(x, y, rim[0], rim[1] - 1, 3)


@icon("cargo_bicycle")
def cargo_bicycle():
    # a black roadster from its left side: a wire basket with green onions over the front wheel, a crate on the rear
    # rack, a brown saddle, chrome bars and fork, two spoked wheels
    c = Canvas()
    wheel(c, 3, 12, 3, spokes=True)
    wheel(c, 12, 12, 3, spokes=True)
    G = "blacksteel"
    c.line(5, 7, 10, 7, G, 3, 2)            # top tube
    c.line(5, 8, 8, 11, G, 2, 2)            # down tube
    c.line(8, 11, 10, 6, G, 3, 2)           # seat tube
    c.line(8, 12, 12, 12, G, 2, 2)          # chain stay
    c.line(10, 7, 12, 11, G, 2, 2)          # seat stay
    c.line(5, 6, 3, 11, "blade", 4, 2)      # fork
    c.line(5, 4, 5, 6, "blade", 4, 2)       # stem
    c.put(6, 4, "rubber", 2, 5)             # grip
    c.put(4, 4, "blade", 5, 5)
    box(c, 0, 5, 4, 8, "blade", 3, 4)       # the wire basket
    for x in (0, 2, 4):
        c.put(x, 6, "blade", 2, 5)
        c.put(x, 7, "blade", 2, 5)
    box(c, 0, 5, 4, 5, "blade", 4, 5)
    c.line(1, 2, 1, 4, "sprout", 4, 5)      # green onions over the rim
    c.line(2, 3, 2, 4, "sprout", 3, 5)
    box(c, 3, 4, 3, 4, "cabbage", 4, 5)
    box(c, 9, 5, 11, 5, "leather", 4, 5)    # saddle
    box(c, 11, 8, 15, 8, "iron", 3, 4)      # rear rack
    box(c, 12, 5, 15, 7, "wood", 3, 5)      # a crate on it
    box(c, 12, 5, 15, 5, "wood", 4, 5)
    c.put(15, 6, "signal_red", 3, 6)
    c.put(8, 11, "blade", 5, 6)             # crank
    c.put(9, 13, "rubber", 2, 6)            # pedal
    return c.render()


@icon("delivery_scooter")
def delivery_scooter():
    # a red step-through with a white leg shield stripe, a black seat and a big white delivery box with a red band
    c = Canvas()
    R = "vermilion"
    wheel(c, 3, 13, 2)
    wheel(c, 12, 13, 2)
    c.line(5, 7, 3, 12, "blade", 4, 2)      # fork
    box(c, 3, 5, 6, 6, R, 3, 3)             # headlamp cowl
    c.put(2, 6, "lamp", 5, 4)
    c.put(5, 4, "rubber", 2, 4)
    c.put(6, 3, "rubber", 2, 4)             # mirror
    c.put(6, 2, "rubber", 3, 4)
    box(c, 5, 7, 6, 11, R, 3, 3)            # leg shield
    c.put(6, 9, "car_white", 4, 4)
    box(c, 7, 10, 9, 11, R, 2, 3)           # floor tunnel
    box(c, 8, 8, 11, 10, R, 3, 3)           # side cover
    box(c, 9, 9, 10, 9, "car_white", 4, 4)
    box(c, 7, 7, 11, 7, "rubber", 2, 4)     # seat
    box(c, 7, 12, 9, 12, "steel", 3, 3)     # engine
    box(c, 10, 13, 13, 13, "blade", 4, 4)   # muffler
    box(c, 2, 10, 4, 10, R, 3, 4)           # front fender
    box(c, 10, 1, 15, 6, "car_white", 4, 4)  # the delivery box
    box(c, 10, 1, 15, 1, "car_white", 5, 5)
    box(c, 10, 4, 15, 4, R, 3, 5)
    c.put(15, 5, "signal_red", 4, 5)
    return c.render()


@icon("atv")
def atv():
    # an orange quad with fat knobbly tyres, black racks front and back, a green jerrycan on the back rack
    c = Canvas()
    O = "fox"
    wheel(c, 3, 12, 3, knobs=True, rim=("iron", 3))
    wheel(c, 12, 12, 3, knobs=True, rim=("iron", 3))
    box(c, 0, 8, 6, 9, O, 3, 4)             # front fender
    box(c, 9, 8, 15, 9, O, 3, 4)            # rear fender
    box(c, 0, 8, 6, 8, O, 4, 5)
    box(c, 9, 8, 15, 8, O, 4, 5)
    box(c, 6, 9, 9, 11, O, 2, 3)            # the middle
    box(c, 6, 11, 9, 11, "iron", 3, 4)      # footboard
    box(c, 7, 7, 10, 7, "rubber", 2, 5)     # seat
    box(c, 5, 7, 6, 8, O, 3, 5)             # tank
    c.line(5, 7, 5, 4, "iron", 3, 5)        # steering post
    c.put(6, 4, "rubber", 2, 6)
    c.put(4, 4, "blade", 4, 6)
    box(c, 0, 7, 4, 7, "iron", 2, 5)        # front rack
    box(c, 11, 7, 15, 7, "iron", 2, 5)      # rear rack
    box(c, 12, 4, 14, 6, "signal_green", 3, 6)  # jerrycan
    c.put(13, 3, "signal_green", 2, 6)
    c.put(0, 9, "lamp", 5, 6)
    box(c, 0, 10, 0, 11, "iron", 3, 6)      # bumper
    return c.render()


@icon("cargo_van")
def cargo_van():
    # a white one-box van: the raked windscreen at the front, a cab window, a closed cargo box with the sliding door's
    # seams and a parcel sign, a teal band along the side
    c = Canvas()
    box(c, 1, 3, 15, 12, "car_white", 4, 2)
    box(c, 2, 2, 15, 2, "car_white", 5, 3)  # roof
    c.line(0, 6, 1, 3, "glass", 4, 3)       # windscreen
    box(c, 0, 6, 0, 12, "car_white", 4, 2)
    box(c, 2, 4, 5, 6, "tint", 2, 3)        # cab window
    c.put(3, 4, "glass", 4, 4)
    box(c, 0, 9, 15, 9, "celadon", 3, 4)    # band
    box(c, 0, 12, 15, 12, "tint", 2, 4)     # sill
    for x in (6, 10):
        c.line(x, 3, x, 11, "car_white", 2, 4)   # door seams
    box(c, 12, 5, 13, 6, "celadon", 2, 4)   # parcel sign
    c.put(0, 8, "lamp", 5, 5)
    box(c, 15, 6, 15, 8, "signal_red", 3, 5)
    wheel(c, 3, 13, 2)
    wheel(c, 12, 13, 2)
    return c.render()


@icon("box_truck")
def box_truck():
    # a navy cab-over with a wind deflector, a tall ribbed aluminium box with a blue stripe, a side guard, big wheels
    c = Canvas()
    N = "denim"
    box(c, 5, 1, 15, 10, "steel", 4, 2)     # the box
    for x in range(6, 15, 2):
        c.line(x, 2, x, 9, "steel", 3, 3)  # ribs
    box(c, 5, 1, 15, 1, "steel", 5, 3)
    box(c, 5, 7, 15, 7, "sapphire", 3, 4)   # stripe
    box(c, 5, 10, 15, 10, "steel", 3, 3)
    box(c, 0, 5, 4, 12, N, 3, 3)            # cab
    box(c, 1, 3, 4, 4, N, 4, 3)             # deflector
    box(c, 0, 5, 3, 7, "tint", 2, 4)        # windscreen and side window
    c.put(1, 5, "glass", 4, 5)
    box(c, 0, 9, 4, 9, "car_white", 4, 4)   # white band
    box(c, 0, 12, 4, 12, "iron", 3, 4)      # bumper
    c.put(0, 11, "lamp", 5, 5)
    box(c, 5, 11, 15, 11, "iron", 2, 3)     # chassis
    box(c, 6, 12, 9, 12, "blade", 3, 3)     # side guard
    c.put(15, 11, "signal_red", 4, 5)
    wheel(c, 3, 13, 2)
    wheel(c, 12, 13, 2)
    return c.render()


@icon("village_bus")
def village_bus():
    # the country village bus from the front, so its face shows: a cloth bundle and a sack on the roof rack, the
    # ivory roof, the hand-painted route board (red "02"), the windscreen, the ivory band with its red pinstripe, two
    # big round headlamp eyes with dark pupils looking in, rosy cheeks and a little smile, the chrome bumper, the tyres
    c = Canvas()
    G = "bottle_green"
    box(c, 4, 0, 7, 1, "indigo", 2, 6)          # the bundle (white flowers on it)
    c.put(5, 0, "cream", 3, 7)
    c.put(7, 1, "cream", 3, 7)
    box(c, 9, 1, 11, 1, "burlap", 2, 6)         # the sack
    box(c, 2, 2, 13, 2, "cream", 3, 3)          # the roof
    box(c, 1, 3, 14, 12, G, 3, 2)               # the body
    box(c, 4, 3, 11, 3, "cream", 3, 5)          # the route board
    for x in (5, 6, 7):
        c.put(x, 3, "fabric_red", 2, 6)         # "02", painted
    c.put(9, 3, "leaf_dark", 1, 6)
    c.put(10, 3, "leaf_dark", 1, 6)
    box(c, 2, 4, 13, 6, "tint", 2, 4)           # the windscreen
    box(c, 3, 5, 12, 6, "glass", 1, 5)
    c.put(3, 5, "glass", 4, 6)
    c.put(4, 5, "glass", 3, 6)
    box(c, 1, 7, 14, 7, "cream", 3, 4)          # the ivory band
    box(c, 1, 8, 14, 8, "fabric_red", 2, 4)     # the pinstripe
    for x0, pupil in ((2, 4), (10, 11)):        # the eyes: round lamps, a dark pupil looking in, a chrome rim below
        box(c, x0 + 1, 9, x0 + 2, 9, "lamp", 5, 6)
        box(c, x0, 10, x0 + 3, 10, "lamp", 5, 6)
        box(c, x0 + 1, 11, x0 + 2, 11, "blade", 3, 6)
        c.put(pupil, 10, "tint", 0, 6)
    c.put(1, 11, "pink", 2, 6)                  # cheeks
    c.put(14, 11, "pink", 2, 6)
    c.put(6, 11, "leaf_dark", 0, 6)             # the smile
    c.put(9, 11, "leaf_dark", 0, 6)
    c.put(7, 12, "leaf_dark", 0, 6)
    c.put(8, 12, "leaf_dark", 0, 6)
    box(c, 1, 13, 14, 13, "blade", 4, 6)        # the bumper
    box(c, 1, 14, 3, 15, "rubber", 1, 3)        # the tyres
    box(c, 12, 14, 14, 15, "rubber", 1, 3)
    return c.render()


@icon("mini_bus")
def mini_bus():
    # the little orange and cream van-bus from the front, so its face shows: a crate of apples on the roof rack, the
    # cream roof, the orange band, the wide windscreen, the short snub nose with two round eyes (dark pupils looking
    # up), rosy cheeks and a little smile, the chrome bumper, the tyres
    c = Canvas()
    O = "topaz"
    box(c, 5, 0, 10, 1, "wood", 2, 6)           # the crate
    for x in (6, 8):
        c.put(x, 0, "tomato", 3, 7)             # apples
    c.put(9, 0, "tomato", 2, 7)
    box(c, 2, 2, 13, 2, "cream", 3, 3)          # the roof
    box(c, 1, 3, 14, 3, O, 3, 3)                # the band over the windscreen
    box(c, 1, 4, 14, 8, O, 2, 2)                # the cab
    box(c, 2, 4, 13, 7, "tint", 2, 4)           # the windscreen
    box(c, 3, 5, 12, 7, "glass", 1, 5)
    c.put(3, 5, "glass", 4, 6)
    c.put(4, 6, "glass", 3, 6)
    box(c, 1, 8, 14, 8, "cream", 3, 4)          # the bonnet's top
    box(c, 1, 9, 14, 12, O, 3, 3)               # the snub nose
    box(c, 1, 12, 14, 12, "wood_dark", 2, 4)    # the thin brown line
    for x0, pupil in ((2, 4), (10, 11)):        # the eyes: round lamps, a dark pupil looking up and in
        box(c, x0, 9, x0 + 3, 10, "lamp", 5, 6)
        box(c, x0 + 1, 11, x0 + 2, 11, "blade", 3, 6)
        c.put(pupil, 9, "tint", 0, 7)
    c.put(1, 11, "pink", 2, 6)                  # cheeks
    c.put(14, 11, "pink", 2, 6)
    c.put(7, 11, "wood_dark", 0, 6)             # the smile
    c.put(8, 11, "wood_dark", 0, 6)
    c.put(6, 10, "wood_dark", 0, 6)
    c.put(9, 10, "wood_dark", 0, 6)
    box(c, 1, 13, 14, 13, "blade", 4, 6)        # the bumper
    box(c, 1, 14, 3, 15, "rubber", 1, 3)        # the tyres
    box(c, 12, 14, 14, 15, "rubber", 1, 3)
    return c.render()


@icon("limousine_bus")
def limousine_bus():
    # the long, low black coach from its left side, the front to the left: a low air conditioner on the roof, a thin
    # chrome line over the dark tinted window strip, a red trim line under it, the sharp red sweep rising towards the
    # back with a silver line over it, a chrome "rabbit ear" mirror, a slim headlamp, a red tail bar, chrome wheels
    c = Canvas()
    B = "rubber"
    box(c, 6, 2, 10, 2, "steel", 1, 4)          # the air conditioner
    box(c, 1, 3, 15, 12, B, 2, 2)               # the glossy black body
    box(c, 1, 3, 15, 3, B, 3, 3)                # the roof's highlight
    box(c, 1, 4, 15, 4, "blade", 3, 4)          # the chrome line over the windows
    for x0 in (2, 6, 10, 13):                   # the tinted panes between black pillars, a rider's head showing
        box(c, x0, 5, min(x0 + 2, 15), 7, "tint", 2, 4)
    c.put(7, 6, "glass", 2, 5)
    c.put(11, 6, "glass", 2, 5)
    c.put(1, 5, "glass", 2, 5)                  # the windscreen's edge
    c.put(1, 6, "glass", 1, 5)
    box(c, 1, 8, 15, 8, "toadstool", 2, 4)      # the red trim line
    for x in range(2, 16):                      # the red sweep rising towards the back, a silver line over it
        y = 11 - (x - 2) * 2 // 13
        c.put(x, y, "toadstool", 3, 5)
        if x > 6:
            c.put(x, y + 1, "toadstool", 2, 5)
        c.put(x, y - 1, "blade", 3, 5)
    c.put(0, 3, "blade", 3, 6)                  # the mirror's arm and glass
    box(c, 0, 4, 0, 6, "blade", 4, 6)
    box(c, 1, 10, 2, 10, "lamp", 5, 6)          # the slim headlamp
    box(c, 15, 8, 15, 10, "toadstool", 3, 6)    # the red tail bar
    for x0 in (3, 11):                          # the wheels: black tyres, chrome rims, a red cap
        box(c, x0, 12, x0 + 2, 14, "rubber", 1, 6)
        c.put(x0 + 1, 13, "toadstool", 3, 7)
        c.put(x0 + 1, 12, "blade", 4, 7)
        c.put(x0, 13, "blade", 3, 7)
        c.put(x0 + 2, 13, "blade", 3, 7)
    return c.render()
