"""
Inventory icons (16x16) of the retro Korean furniture, 방·거실: textures/<id>_icon.png in the core pack, drawn like
furniture_icons.py (the shared pixelart Canvas and palette materials, light from the top left, outline in the
darkest step, each piece seen about the way its 3D model is).
"""

from stsv_assets.pixelart import Canvas

ICONS = {}

UP = (0, -1, 0.35)        # a surface facing up (lit)
FRONT = (0, 0, 1)         # facing the viewer
SIDE = (0.8, 0.2, 0.55)   # facing right, away from the light


def icon(pid):
    def deco(fn):
        ICONS[pid] = fn
        return fn
    return deco


def _box(c, x0, y0, x1, y1, d, mat, z=0, front=None, top=None, side=None):
    """A box seen from the front, a little from above and the right: its front (x0..x1, y0..y1), a top `d` deep
    leaning right, and the right side."""
    c.poly([(x0, y0), (x0 + d, y0 - d), (x1 + d, y0 - d), (x1, y0)], top or mat, z, normal=UP)
    c.poly([(x1, y0), (x1 + d, y0 - d), (x1 + d, y1 - d), (x1, y1)], side or mat, z, normal=SIDE)
    c.rect(x0, y0, x1, y1, front or mat, z + 1, normal=FRONT)


@icon("jagae_wardrobe")
def jagae_wardrobe():
    c = Canvas()
    _box(c, 0.5, 2.5, 14, 14, 1.5, "rubber", 0)
    c.rect(0, 1.5, 15.5, 2.5, "gold", 3, normal=FRONT)               # the gilt cornice
    for x in (3.5, 7, 10.5):
        c.line(x, 3, x, 11, "rubber", 0, z=5)                        # the door seams
    c.line(1, 11.5, 13.5, 11.5, "rubber", 0, z=5)                     # over the drawers
    shell = [  # a crane, a pine, a peony and a bamboo spray in mother-of-pearl
        (2, 4, "pearl", 5), (2, 5, "pearl", 4), (1, 6, "pearl", 4), (2, 6, "pearl", 5), (3, 6, "pearl", 4), (2, 7, "pearl", 3),
        (2, 8, "pearl", 3), (2, 3, "sakura", 3),
        (5, 4, "spectral", 3), (4, 5, "spectral", 3), (5, 5, "spectral", 4), (6, 5, "spectral", 3), (5, 7, "spectral", 3),
        (4, 8, "spectral", 3), (6, 8, "spectral", 3), (5, 9, "whetstone", 3), (5, 10, "whetstone", 3),
        (9, 4, "sakura", 3), (8, 5, "sakura", 4), (9, 5, "pearl", 5), (10, 5, "sakura", 4), (9, 6, "sakura", 3),
        (9, 7, "spectral", 3), (8, 8, "spectral", 3), (9, 9, "sakura", 3),
        (12, 4, "gold", 4), (12, 6, "spectral", 3), (12, 7, "spectral", 3), (13, 8, "spectral", 3), (12, 9, "spectral", 3),
        (12, 10, "spectral", 3),
        (3, 13, "sakura", 3), (6, 13, "sakura", 3), (9, 13, "sakura", 3), (12, 13, "sakura", 3),
    ]
    for (x, y, m, s) in shell:
        c.put(x, y, m, s, z=6)
    for (x, y) in ((7, 8), (8, 8), (4, 12), (10, 12)):
        c.put(x, y, "brass", 4, z=7)                                 # handle plates and drawer pulls
    c.rect(1, 14, 3, 15, "rubber", 1, normal=FRONT)
    c.rect(11.5, 14, 13.5, 15, "rubber", 1, normal=FRONT)
    return c.render()


@icon("crt_tv")
def crt_tv():
    c = Canvas()
    _box(c, 3, 10, 12, 14, 1.5, "wood", 0)                           # the cabinet
    c.line(7.5, 10.5, 7.5, 13.5, "wood", 1, z=5)
    c.rect(3.5, 14, 4.5, 15.5, "wood_dark", 1, normal=FRONT)
    c.rect(10.5, 14, 11.5, 15.5, "wood_dark", 1, normal=FRONT)
    _box(c, 2, 3, 13, 10, 1.5, "wood_dark", 2, front="test_grey")    # the set
    c.rect(2.5, 3.5, 10, 9.5, "ice", 6, step=4)                      # the glowing screen
    c.rect(2.5, 7, 10, 9.5, "leaf", 7, step=3)                       # hills on it
    c.rect(2.5, 6, 6, 7, "leaf", 7, step=4)
    c.put(8, 4, "glow_amber", 4, z=8)
    c.put(11, 5, "rubber", 1, z=8)                                   # the dial and a knob
    c.put(11, 7, "blade", 3, z=8)
    c.put(11, 9, "rubber", 2, z=8)
    c.line(7, 2, 4, 0, "blade", 4, z=9)                              # rabbit ears
    c.line(8, 2, 11, 0, "blade", 4, z=9)
    c.rect(5, 2, 10, 3, "feather", 8, step=4)                        # the lace doily
    return c.render()


@icon("dial_phone")
def dial_phone():
    c = Canvas()
    for (x, y0, y1) in ((2, 8, 15), (12.5, 8, 15), (6, 9, 15.5)):
        c.rect(x, y0, x + 1.5, y1, "wood_dark", 0, normal=FRONT)
    _box(c, 1, 7.5, 13, 9, 2, "feather", 1)                          # table under its lace
    c.rect(3.5, 12, 11, 13, "petal_yellow", 1, normal=FRONT)         # the phone book below
    c.ellipse(8, 5.5, 5, 2.6, "fabric_red", 3, dome=0.5)             # the phone
    c.ellipse(7.5, 5.5, 2.3, 1.4, "cream", 4, dome=0.3, gloss=0)    # its dial
    for (x, y) in ((6, 5), (7, 4), (9, 5), (8, 6)):
        c.put(x, y, "rubber", 1, z=6)
    c.tube([(3.5, 2.5), (12.5, 2.5)], [1.1, 1.1], "fabric_red", 7)   # the handset
    c.ellipse(3.5, 3, 1.4, 1.2, "fabric_red", 8)
    c.ellipse(12.5, 3, 1.4, 1.2, "fabric_red", 8)
    return c.render()


@icon("wall_clock")
def wall_clock():
    c = Canvas()
    c.rect(4, 2, 12, 15, "plush", 0, normal=FRONT)                   # the case
    c.poly([(4.5, 2), (8, 0), (11.5, 2)], "plush", 1, normal=UP)     # its crown
    c.ellipse(8, 5.5, 3.3, 3.3, "brass", 2, dome=0.3)                # the bezel
    c.ellipse(8, 5.5, 2.4, 2.4, "dial", 3, dome=0.1, gloss=0)        # the dial
    c.line(8, 3.5, 8, 5, "rubber", 0, z=5)                           # hands at three o'clock
    c.line(8, 5, 9.5, 5, "rubber", 0, z=5)
    c.rect(5.5, 9, 10.5, 14, "wood_dark", 3, normal=FRONT)           # the pendulum window
    c.line(8, 9, 8, 12, "brass", 3, z=5)
    c.ellipse(8, 12.5, 1.2, 1.2, "brass", 6)
    c.put(6, 10, "glass", 4, z=6)
    return c.render()


@icon("blanket_chest")
def blanket_chest():
    c = Canvas()
    _box(c, 2, 1.5, 12.5, 15, 1.5, "rubber", 0)
    c.rect(3, 2.5, 11.5, 8.5, "rubber", 2, normal=FRONT)             # the open shelf
    for (y0, y1, m) in ((7, 8.5, "pink"), (5.5, 7, "sign_green"), (4, 5.5, "fabric_red"), (3, 4, "cream")):
        c.rect(3.5, y0, 11, y1, m, 3, normal=FRONT)                  # folded quilts
    for (x, y, m) in ((5, 8, "porcelain"), (8, 7, "porcelain"), (6, 6, "fabric_red"), (9, 4, "gold"), (5, 3, "denim")):
        c.put(x, y, m, 4, z=6)
    c.ellipse(5.5, 2.5, 1.8, 1, "sakura", 7, dome=0.5)               # the pillows on top
    c.ellipse(9, 2.5, 1.8, 1, "sakura", 7, dome=0.5)
    c.line(7.5, 9.5, 7.5, 14, "rubber", 0, z=5)                      # the doors and their shell
    for (x, y, m, s) in ((5, 11, "pearl", 5), (5, 12, "spectral", 3), (4, 11, "sakura", 3), (10, 11, "pearl", 5),
                         (10, 12, "spectral", 3), (11, 11, "sakura", 3), (7, 12, "brass", 4), (8, 12, "brass", 4)):
        c.put(x, y, m, s, z=6)
    return c.render()


@icon("meal_table")
def meal_table():
    c = Canvas()
    for (x, y0, y1) in ((3, 9, 13.5), (12, 9, 13.5)):
        c.rect(x, y0, x + 1, y1, "steel", 2, normal=FRONT)
    c.ellipse(8, 9, 7.6, 3.8, "fish_silver", 1, dome=0.2)
    c.ellipse(8, 8.2, 7.6, 3.6, "fish_silver", 3, dome=0.25, gloss=0)
    c.cyl(9, 4.5, 12.5, 7.5, "blade", 4)                             # the rice bowl
    c.ellipse(10.75, 4.6, 1.9, 1.2, "rice", 6, dome=0.5)
    c.cyl(4, 5.5, 7.5, 8, "blade", 4)                                # the soup
    c.ellipse(5.75, 5.6, 1.6, 0.8, "doenjang", 6, dome=0.1, gloss=0)
    c.ellipse(11, 9.5, 1.8, 1, "porcelain", 5)                       # kimchi
    c.put(10, 9, "pepper", 3, z=7)
    c.put(11, 9, "pepper", 4, z=7)
    c.put(12, 9, "pepper", 3, z=7)
    c.ellipse(5, 10, 1.8, 1, "feather", 5, dome=0.3)                 # a fried egg
    c.put(5, 10, "corn", 4, z=7)
    c.line(5, 12, 10, 11, "blade", 4, z=8)                           # the spoon
    return c.render()


@icon("retro_sofa")
def retro_sofa():
    c = Canvas()
    c.rect(2, 3, 14, 9, "wine", 2, normal=FRONT)                     # the back
    c.rect(2, 8.5, 14, 12.5, "wine", 3, normal=UP)                   # the seat
    c.rect(2, 12, 14, 14, "wine", 1, normal=FRONT)
    c.line(8, 4, 8, 13.5, "wine", 0, z=5)                            # the two cushions
    for (x, y) in ((4, 5), (11, 6), (6, 10), (12, 13), (4, 13)):
        c.put(x, y, "gold", 4, z=5)                                  # flowers on the velvet
    c.rect(3.5, 3, 7, 5, "feather", 6, step=4)                       # lace covers
    c.rect(9, 3, 12.5, 5, "feather", 6, step=4)
    for x in (0.5, 13.5):
        c.rect(x, 7, x + 2, 14, "wood", 3, normal=FRONT)             # the wooden arms
        c.rect(x, 6.5, x + 2, 7.5, "wood", 4, normal=UP)
    c.rect(2.5, 14, 3.5, 15.5, "wood_dark", 1, normal=FRONT)
    c.rect(12.5, 14, 13.5, 15.5, "wood_dark", 1, normal=FRONT)
    return c.render()


@icon("electric_fan")
def electric_fan():
    c = Canvas()
    c.ellipse(8, 14, 5, 1.6, "cream", 1, dome=0.4)                   # the base
    c.rect(5.5, 13, 10.5, 14.5, "cream", 2, normal=FRONT)
    for (x, m) in ((6, "sapphire"), (7.5, "leaf"), (9, "pepper")):
        c.put(int(x), 13, m, 3, z=3)
    c.rect(7.5, 9, 8.5, 13, "cream", 2, normal=FRONT)                # the pole
    c.ellipse(8, 5.5, 5.6, 5.6, "blade", 3, dome=0.1, gloss=0)      # the wire cage
    c.ellipse(8, 5.5, 4.6, 4.6, "water", 2, dome=0.1, gloss=0)      # the blades turning
    for (x, y) in ((6, 3), (10, 4), (7, 8), (9, 7), (5, 6), (11, 6)):
        c.put(x, y, "water", 4, z=6)
    c.line(4, 2, 12, 9, "blade", 4, z=7)                             # the grille's spokes
    c.line(12, 2, 4, 9, "blade", 4, z=7)
    c.ellipse(8, 5.5, 1.2, 1.2, "sapphire", 8)                       # the badge
    return c.render()


@icon("dresser")
def dresser():
    c = Canvas()
    _box(c, 2, 9, 13, 14.5, 1.5, "plush", 0)                         # the table
    c.line(2.5, 11.5, 12.5, 11.5, "plush", 0, z=5)
    c.line(7.5, 9.5, 7.5, 14, "plush", 0, z=5)
    for (x, y) in ((5, 10), (10, 10), (5, 13), (10, 13)):
        c.put(x, y, "gold", 4, z=6)
    c.rect(5.5, 1.5, 10, 8.5, "plush", 1, normal=FRONT)              # the middle mirror
    c.rect(6.5, 2.5, 9, 8.5, "glass", 3, normal=FRONT)
    c.poly([(1.5, 3), (5, 2.5), (5, 8.5), (1.5, 8)], "glass", 2, normal=(-0.4, 0, 1))   # the side wings
    c.poly([(10.5, 2.5), (14, 3), (14, 8), (10.5, 8.5)], "glass", 2, normal=SIDE)
    c.put(7, 4, "feather", 3, z=6)
    c.put(8, 3, "feather", 3, z=6)
    c.put(3, 8, "sakura", 4, z=7)                                    # bottles on the top
    c.put(12, 8, "aurora", 4, z=7)
    return c.render()


@icon("sewing_machine")
def sewing_machine():
    c = Canvas()
    for x in (2, 12.5):
        c.rect(x, 9, x + 1.5, 15.5, "blacksteel", 2, normal=FRONT)   # the iron sides
    c.rect(3.5, 13.5, 12.5, 14.5, "blacksteel", 1, normal=UP)        # the treadle
    _box(c, 1, 8, 14, 9.5, 1.5, "wood", 2)                           # the table
    c.rect(3, 7, 13, 8, "rubber", 2, normal=FRONT)                   # the bed
    c.rect(10, 2.5, 12.5, 7, "rubber", 2, normal=FRONT)              # the pillar
    c.rect(4, 2.5, 12, 4.5, "rubber", 3, normal=FRONT)               # the arm
    c.rect(3, 2.5, 5, 6.5, "rubber", 2, normal=FRONT)                # the needle head
    c.line(5, 3.5, 10, 3.5, "gold", 4, z=5)                          # gilt line
    c.put(4, 7, "blade", 4, z=6)                                     # the needle
    c.ellipse(13.3, 3.8, 1.2, 1.5, "blade", 5)                       # the hand wheel
    c.rect(7, 1, 8, 2.5, "pepper", 6, normal=FRONT)                  # a spool of red thread
    return c.render()
