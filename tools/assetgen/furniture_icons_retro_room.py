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
    c.rect(0, 1.5, 15.5, 2.5, "rubber", 1, normal=FRONT)             # the cornice
    c.line(0, 2, 15, 2, "gold", 4, z=5)                              # its thin gilt rim
    for x in (3.5, 7, 10.5):
        c.line(x, 3, x, 11, "rubber", 0, z=5)                        # the door seams
    c.line(1, 11.5, 13.5, 11.5, "rubber", 0, z=5)                     # over the drawers
    # mother-of-pearl in fine lines: milky white, pale silver, soft pink, green and violet
    nacre = [
        # a crane under a crescent moon, a reed
        (1, 3, "ice", 4), (3, 4, "pearl", 5), (2, 5, "porcelain", 3), (1, 6, "pearl", 4), (2, 6, "pearl", 5),
        (3, 6, "crystal", 4), (2, 7, "porcelain", 2), (2, 8, "porcelain", 2), (1, 9, "musk", 4), (3, 10, "musk", 4),
        # a pine
        (5, 3, "musk", 4), (4, 4, "musk", 4), (5, 4, "pearl", 4), (6, 4, "musk", 4), (5, 5, "porcelain", 2),
        (4, 6, "musk", 4), (5, 6, "pearl", 4), (6, 6, "musk", 4), (5, 7, "porcelain", 2), (5, 8, "porcelain", 2),
        (4, 9, "porcelain", 2), (6, 9, "porcelain", 2),
        # a peony on its stem
        (9, 3, "pearl", 5), (8, 4, "sakura", 4), (9, 4, "pearl", 4), (10, 4, "sakura", 4), (9, 5, "sakura", 3),
        (9, 6, "porcelain", 2), (8, 7, "musk", 4), (9, 7, "porcelain", 2), (9, 8, "porcelain", 2), (10, 8, "musk", 4),
        (9, 9, "sakura", 4),
        # a butterfly over bamboo
        (11, 3, "crystal", 4), (12, 4, "pearl", 5), (13, 3, "crystal", 4), (13, 5, "porcelain", 3),
        (13, 6, "porcelain", 3), (12, 7, "musk", 4), (13, 7, "ice", 4), (13, 8, "porcelain", 3), (13, 9, "porcelain", 3),
        (12, 10, "musk", 4),
        # a flower on each drawer
        (2, 13, "sakura", 4), (5, 13, "pearl", 5), (9, 13, "ice", 4), (12, 13, "crystal", 4),
    ]
    for (x, y, m, st) in nacre:
        c.put(x, y, m, st, z=6)
    for (x, y) in ((6, 8), (8, 8), (4, 12), (10, 12)):
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
    for (x, y0, y1) in ((2, 11, 16), (12.5, 11, 16)):
        c.rect(x, y0, x + 1.5, y1, "wood_dark", 0, normal=FRONT)       # the table's legs
    c.rect(3.5, 13, 12.5, 14.5, "petal_yellow", 1, normal=FRONT)     # the phone book on its shelf
    _box(c, 0.5, 10.5, 14.5, 11.5, 1, "feather", 1)                  # the top under its lace
    # the red phone, its front sloping up to the cradle
    c.poly([(4, 2.5), (12, 2.5), (13.5, 10.5), (2.5, 10.5)], "fabric_red", 2, normal=(0, -0.45, 1))
    c.rect(2.5, 9.5, 13.5, 10.5, "fabric_red", 1, normal=FRONT)
    # the dial: a round pale disc, the finger holes in a ring round its edge, the red label in the middle and the
    # silver finger stop in the gap at four o'clock
    for dx in range(-3, 4):
        for dy in range(-3, 4):
            if dx * dx + dy * dy <= 10:
                c.put(8 + dx, 6 + dy, "porcelain", 4 if dx + dy < -1 else 3 if dx + dy < 2 else 2, z=6)
    for (dx, dy) in ((2, -1), (1, -2), (-1, -2), (-2, -1), (-2, 1), (-1, 2), (1, 2)):
        c.put(8 + dx, 6 + dy, "rubber", 1, z=7)
    c.put(8, 6, "fabric_red", 2, z=7)
    c.put(10, 7, "blade", 1, z=7)
    # the handset lying across the cradle on top, its two ends standing out, the coiled cord down the left
    for x in (5, 11):
        c.put(x, 2, "fabric_red", 1, z=7)                          # the cradle's prongs
    c.tube([(4, 1.2), (12, 1.2)], [0.7, 0.7], "fabric_red", 8)
    c.ellipse(2.8, 1.8, 1.5, 1.6, "fabric_red", 9, dome=0.6)
    c.ellipse(13.2, 1.8, 1.5, 1.6, "fabric_red", 9, dome=0.6)
    for (x, y) in ((1, 4), (2, 5), (1, 6), (2, 7), (1, 8), (2, 9), (1, 10)):
        c.put(x, y, "fabric_red", 1, z=9)
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
    import math
    c = Canvas()
    c.ellipse(8, 14, 5, 1.6, "cream", 1, dome=0.4)                   # the base
    c.rect(5.5, 13, 10.5, 14.5, "cream", 2, normal=FRONT)
    for (x, m) in ((6, "sapphire"), (7, "leaf"), (9, "pepper")):
        c.put(x, 13, m, 3, z=3)
    c.rect(7.5, 10.5, 8.5, 13, "cream", 2, normal=FRONT)             # the pole
    c.rect(7, 9.5, 9, 11, "cream", 3, normal=FRONT)                  # the neck
    # the round cage (centre pixel 8, 5): a chrome rim, three blue blades inside, a ring and eight spokes, the badge
    for y in range(0, 12):
        for x in range(1, 16):
            dx, dy = x - 8, y - 5
            r = math.hypot(dx, dy)
            if 4.6 <= r < 5.6:
                c.put(x, y, "blade", 4 if dx + dy < -1 else 3 if dx + dy < 2 else 2, z=6)
            elif r < 4.6:
                a = (math.atan2(dy, dx) + 0.3) % (2 * math.pi / 3) / (2 * math.pi / 3)
                c.put(x, y, "water", (4 if a < 0.2 else 3) if a < 0.45 else 2 if a < 0.6 else 1, z=5)
                if r >= 1.5 and (dx == 0 or dy == 0 or abs(dx) == abs(dy)) and r >= 2.8:
                    c.put(x, y, "blade", 3, z=7)                     # the spokes
                elif 2 <= r < 2.9 and (dx + dy) % 2 == 0:
                    c.put(x, y, "blade", 3, z=7)                     # the inner ring
    c.put(8, 5, "sapphire", 4, z=8)                                  # the badge
    for (x, y) in ((7, 5), (9, 5), (8, 4), (8, 6)):
        c.put(x, y, "blade", 4, z=8)
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
