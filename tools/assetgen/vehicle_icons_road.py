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
    # a green roadster from its left side: a wire basket with green onions over the front wheel, a crate on the rear
    # rack, a brown saddle, chrome bars and fork, two spoked wheels
    c = Canvas()
    wheel(c, 3, 12, 3, spokes=True)
    wheel(c, 12, 12, 3, spokes=True)
    G = "bottle_green"
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
