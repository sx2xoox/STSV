"""
Inventory icons (16x16) of the retro Korean furniture, 마당·골목: textures/<id>_icon.png in the core pack, drawn like
furniture_icons.py (the shared pixelart Canvas and palette materials, light from the top left, outline in the
darkest step, each piece seen about the way its 3D model is).
"""

from furniture_icons import FRONT, SIDE, UP, _diamond
from stsv_assets.pixelart import Canvas

ICONS = {}


def icon(pid):
    def deco(fn):
        ICONS[pid] = fn
        return fn
    return deco


@icon("pyeongsang")
def pyeongsang():
    c = Canvas()
    # the legs under the front edges
    for (x, y) in ((1.5, 9.5), (7.2, 12.6), (14, 9)):
        c.rect(x, y, x + 1.3, y + 3, "wood_dark", 0, normal=FRONT)
    # the platform: the front sides, then the top
    a, b, cc, d = (0.5, 8), (8, 4), (15.5, 8), (8, 12)
    c.poly([a, d, (d[0], d[1] + 1.5), (a[0], a[1] + 1.5)], "wood", 0, normal=(-0.3, 0.2, 1))
    c.poly([d, cc, (cc[0], cc[1] + 1.5), (d[0], d[1] + 1.5)], "wood", 0, normal=SIDE)
    c.poly([a, b, cc, d], "wood_dark", 1, normal=UP)
    _diamond(c, 8, 8, 6.2, 3.1, "straw", 2)                 # the mat
    # watermelon: a slice (red, green rind) and the fan
    c.ellipse(7, 7.2, 2.4, 1.3, "cucumber", 3, dome=0.3, gloss=0)
    c.ellipse(7, 6.8, 1.9, 0.9, "tomato", 4, dome=0.2, gloss=0)
    c.put(6, 7, "rubber", 1, z=5)
    c.put(8, 7, "rubber", 1, z=5)
    c.ellipse(11.2, 8.4, 1.7, 1.0, "straw", 4, dome=0.2, gloss=0)
    c.line(10, 9.4, 9, 10.2, "wood_dark", 2, z=5)
    return c.render()


@icon("red_postbox")
def red_postbox():
    c = Canvas()
    c.rect(3.5, 13, 12.5, 15, "iron", 0, normal=FRONT)
    c.rect(4, 5, 12, 13.5, "pepper", 1, normal=FRONT, lo=2, hi=4)
    c.ellipse(8, 5.5, 4, 3.5, "pepper", 1, dome=0.6, gloss=0.9)
    c.erase([p for p in c.where("pepper") if p[1] > 13])
    c.rect(5, 4.5, 11, 5.5, "rubber", 2, step=1)            # the slot
    c.rect(4.5, 6, 11.5, 10.5, "cream", 3, step=4)          # the plate
    c.rect(5, 7, 11, 10, "porcelain", 4, step=4)            # a short, wide white envelope, a shallow flap at its top
    c.line(5, 7, 7, 8, "porcelain", 1, z=6)
    c.line(8, 8, 10, 7, "porcelain", 1, z=6)
    c.rect(6, 11, 10, 13, "cream", 4, step=3)              # the collection-times plate
    c.line(7, 12, 9, 12, "steel", 3, z=6)
    c.put(4, 12, "fox", 2, z=5)                             # rust at the foot
    return c.render()


@icon("public_phone")
def public_phone():
    c = Canvas()
    c.rect(7, 10, 9, 15.5, "steel", 0, normal=FRONT)        # the post
    c.rect(5.5, 14.5, 10.5, 16, "stone", 0, normal=FRONT)
    # the hood: a cream shell, darker inside, an orange stripe
    c.ellipse(8, 3.5, 6.5, 3, "cream", 1, dome=0.5, gloss=0)
    c.rect(1.5, 3.5, 14.5, 11, "cream", 1, normal=FRONT, lo=2, hi=3)
    c.rect(3, 4, 13, 11, "cream", 2, step=1)
    c.rect(1.5, 2.5, 14.5, 3.5, "fox", 3, normal=FRONT)
    # the green phone, its keypad and the black handset at the left
    c.rect(5, 4.5, 10.5, 10.5, "sign_green", 4, normal=FRONT)
    c.rect(6, 5, 9, 6, "glow_amber", 5, step=2)
    for (x, y) in ((6, 7), (8, 7), (6, 9), (8, 9)):
        c.put(x, y, "porcelain", 4, z=6)
    c.tube([(11.5, 5), (11.5, 10)], [1.0, 1.0], "rubber", 6, gloss=0.8)
    c.line(11, 11, 10, 12, "rubber", 2, z=6)
    return c.render()


@icon("dalgona_stand")
def dalgona_stand():
    c = Canvas()
    a, b, cc, d = (0.5, 9), (7.5, 5.5), (15.5, 9), (8.5, 12.5)
    c.poly([a, d, (d[0], d[1] + 3), (a[0], a[1] + 3)], "wood", 0, normal=(-0.3, 0.2, 1))
    c.poly([d, cc, (cc[0], cc[1] + 3), (d[0], d[1] + 3)], "wood", 0, normal=SIDE)
    c.poly([a, b, cc, d], "wood", 1, normal=UP)
    # the brazier with its glowing coals, the ladle on it
    c.cyl(9.5, 5, 14, 9, "iron", 2)
    c.ellipse(11.75, 5.2, 2.2, 0.9, "fire", 3, dome=0.2, gloss=0)
    c.put(11, 5, "fire", 5, z=5)
    c.put(12, 5, "fire", 4, z=5)
    c.ellipse(11.75, 4, 1.4, 0.8, "steel", 6, dome=0.4)
    c.line(9.5, 3, 6.5, 1.5, "wood_dark", 2, z=7)
    # the tray of dalgona: two caramel discs, a star and a heart pressed in pale
    _diamond(c, 5, 9, 3.6, 1.8, "steel", 3)
    c.ellipse(3.8, 8.5, 1.7, 0.9, "onion", 1, dome=0.2, gloss=0)
    c.ellipse(6.4, 9.7, 1.7, 0.9, "onion", 1, dome=0.2, gloss=0)
    c.put(3, 8, "onion", 5, z=6)
    c.put(4, 8, "onion", 5, z=6)
    c.put(4, 9, "onion", 5, z=6)
    c.put(6, 9, "onion", 5, z=6)
    c.put(7, 9, "onion", 5, z=6)
    c.put(6, 10, "onion", 5, z=6)
    return c.render()


@icon("capsule_machine")
def capsule_machine():
    c = Canvas()
    c.rect(3, 9, 13, 15.5, "pepper", 0, normal=FRONT, lo=2, hi=4)
    c.rect(2.5, 8.5, 13.5, 9.5, "steel", 1, normal=FRONT)
    c.ellipse(8, 12, 1.8, 1.8, "steel", 3, gloss=0.8)      # the turning knob
    c.line(6.5, 12, 9.5, 12, "steel", 5, z=4)
    c.rect(10.5, 13.5, 12, 14.5, "rubber", 4, step=1)       # the exit
    # the open glass box: pale corner posts, a chrome top rim, the capsules heaped inside (two-tone little balls)
    c.rect(3, 1.5, 13, 8.5, "tint", 2, normal=FRONT)         # the glass, seen through to the dark back
    for (x, y, mat, white_right) in ((3, 6, "pepper", True), (6, 6, "slime", False), (9, 6, "magic", True),
                                     (11, 6, "fox", False), (4, 4, "topaz", False), (7, 4, "denim", True),
                                     (10, 4, "pepper", False), (6, 2, "fox", True)):
        cx, wx = (x, x + 1) if white_right else (x + 1, x)
        c.put(cx, y, mat, 4, z=3)
        c.put(cx, y + 1, mat, 2, z=3)
        c.put(wx, y, "porcelain", 4, z=3)
        c.put(wx, y + 1, "porcelain", 3, z=3)
    c.rect(2, 1, 3, 8.5, "ice", 4, normal=FRONT)            # the corner posts
    c.rect(13, 1, 14, 8.5, "ice", 4, normal=FRONT)
    c.rect(2, 0.5, 14, 1.5, "steel", 3, normal=FRONT)        # the top rim
    c.put(4, 2, "ice", 5, z=5)                               # a glint in the glass
    c.put(5, 1, "ice", 5, z=5)
    c.rect(6.5, -0.5, 9.5, 0.5, "pepper", 3, normal=FRONT)
    return c.render()


@icon("arcade_machine")
def arcade_machine():
    c = Canvas()
    # the black cabinet, its side at the right with the red and yellow stripe
    c.poly([(10.5, 1), (14, 2.5), (14, 15.5), (10.5, 15.5)], "rubber", 0, normal=SIDE)
    c.line(10.5, 10, 14, 6, "pepper", 3, z=2)
    c.line(10.5, 11, 14, 7, "hazard", 2, z=2)
    c.rect(2, 1, 10.5, 15.5, "rubber", 1, normal=FRONT, lo=2, hi=3)
    c.rect(2.5, 1, 10.5, 3, "lamp", 2, step=4)              # the lit marquee
    c.put(4, 2, "toadstool", 3, z=3)
    c.put(8, 2, "toadstool", 3, z=3)
    c.rect(3, 4, 10, 8.5, "obsidian", 2, step=1)           # the screen and its little shooter
    c.put(6, 8, "spectral", 4, z=4)
    c.put(5, 8, "spectral", 3, z=4)
    c.put(7, 8, "spectral", 3, z=4)
    c.put(6, 6, "hazard", 4, z=4)
    c.put(4, 5, "toadstool", 4, z=4)
    c.put(8, 5, "slime", 4, z=4)
    c.poly([(1.5, 9.5), (10.5, 9.5), (11, 11), (1, 11)], "obsidian", 2, normal=UP)    # the control panel
    c.ellipse(4, 9, 1.0, 1.0, "toadstool", 4, gloss=0.7)
    c.put(7, 10, "hazard", 4, z=5)
    c.put(9, 10, "denim", 4, z=5)
    c.rect(5, 12, 8, 14, "steel", 2, normal=FRONT)
    c.put(6, 13, "fox", 4, z=4)
    return c.render()


@icon("tub_garden")
def tub_garden():
    c = Canvas()
    c.cyl(2, 10, 14, 15.5, "rubber", 0, lo=1, hi=3)
    c.ellipse(8, 10, 6.3, 1.8, "rubber", 1, dome=0.2, gloss=0)
    c.ellipse(8, 10, 5.3, 1.3, "soil", 2, dome=0.2, gloss=0)
    # two pepper bushes and the lettuce in front
    c.ellipse(5, 5.5, 3.3, 3.6, "leaf", 2, dome=0.8, gloss=0)
    c.ellipse(11, 6, 3.0, 3.3, "leaf", 2.5, dome=0.8, gloss=0)
    c.line(13, 1, 13, 9, "bamboo", 3, z=1)
    for (x, y) in ((3, 7), (6, 8), (10, 8), (12, 6), (5, 4)):
        c.line(x, y, x, y + 1, "pepper", 3, z=6)
    c.put(9, 5, "cucumber", 4, z=6)
    c.ellipse(5.5, 10, 2.2, 1.4, "wine", 4, dome=0.6, gloss=0)
    c.ellipse(10, 10.2, 2.2, 1.4, "sprout", 5, dome=0.6, gloss=0)
    return c.render()


@icon("street_lamp")
def street_lamp():
    c = Canvas()
    c.rect(9, 1, 11, 15.5, "millstone", 3, normal=FRONT, lo=2, hi=4)  # the concrete pole
    c.rect(7.5, 14, 12.5, 16, "stone", 0, normal=FRONT)
    c.rect(4, 2, 16, 3, "steel", 2, normal=FRONT)                    # the crossarm near the top, its insulators
    c.rect(8.5, 1.5, 11.5, 3.5, "steel", 1, normal=FRONT)
    for x in (4, 6, 14, 15):
        c.put(x, 1, "porcelain", 4, z=4)
    c.rect(9.5, 11, 10.5, 13, "cream", 4, step=4)                    # the flyer
    # the arm curving out of the pole and the big, wide, shallow green shade, white inside, its bulb below
    c.line(9, 5, 6, 5, "steel", 3, z=4)
    c.put(5, 6, "steel", 3, z=4)
    c.poly([(0, 9), (3, 6.5), (7, 6.5), (10, 9)], "sign_green", 5, normal=(0, -0.6, 0.8))
    c.line(1, 9, 9, 9, "porcelain", 4, z=5)
    c.ellipse(5, 10.2, 1.3, 1.0, "lamp", 6, dome=0.3, gloss=0.5)
    return c.render()


@icon("clothesline")
def clothesline():
    c = Canvas()
    for x in (0.5, 14):
        c.rect(x, 2, x + 1.5, 16, "wood", 0, normal=FRONT)
    c.line(1, 3, 14, 3, "rice", 3, z=2)
    # the undershirt, the floral blanket, the socks
    c.rect(2, 3.5, 5.5, 10, "rice", 3, normal=FRONT, lo=3, hi=4)
    c.erase([(3, 4), (4, 4), (3, 5), (4, 5)])
    c.rect(6.5, 3, 11.5, 13, "pink", 3, normal=FRONT, lo=2, hi=4)
    for (x, y) in ((7, 5), (10, 6), (8, 8), (10, 10), (7, 11)):
        c.put(x, y, "fabric_red", 3, z=4)
    c.rect(6.5, 12, 11.5, 13, "fabric_red", 2, step=2)
    c.rect(12, 3.5, 13, 7, "steel", 4, step=2)
    c.put(11, 7, "steel", 2, z=4)
    c.rect(2.5, 13, 6.5, 15.5, "pepper", 3, normal=FRONT)    # the red laundry basket under the line
    c.put(3, 14, "pepper", 1, z=5)
    c.put(5, 14, "pepper", 1, z=5)
    return c.render()


@icon("jangdokdae")
def jangdokdae():
    c = Canvas()
    c.rect(0.5, 13, 15.5, 15.5, "millstone", 0, normal=FRONT)
    c.ellipse(8, 13, 7.5, 1.4, "millstone", 1, dome=0.3, gloss=0)
    # the big crock, the middle one, the little one: round bellies, a lid like an upturned dish
    c.ellipse(5, 9.2, 4.3, 3.8, "onggi", 2, gloss=0.75, dome=0.9)
    c.rect(3, 5, 7, 5.6, "onggi", 2, step=2)                 # the neck
    c.ellipse(5, 4.6, 3.6, 0.9, "onggi", 3, dome=0.3, gloss=0)    # the lid's wide rim
    c.ellipse(5, 3.8, 2.2, 0.8, "onggi", 4, dome=0.5, gloss=0.3)
    c.put(5, 3, "onggi", 4, z=4)
    c.ellipse(11, 10.2, 3.1, 2.9, "onggi", 3, gloss=0.75, dome=0.9)
    c.ellipse(11, 7.2, 2.6, 0.7, "onggi", 4, dome=0.3, gloss=0)
    c.ellipse(11, 6.6, 1.5, 0.6, "onggi", 4, dome=0.5, gloss=0.3)
    c.ellipse(14, 12, 1.6, 1.8, "onggi", 5, gloss=0.75, dome=0.9)
    c.ellipse(8.5, 13.6, 1.4, 0.7, "straw", 3, dome=0.2, gloss=0)  # the gourd dipper
    c.put(8, 13, "straw", 1, z=5)
    return c.render()
