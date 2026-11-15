"""
Inventory icons (16x16) of the retro Korean furniture, 부엌·살림: textures/<id>_icon.png in the core pack, drawn like
furniture_icons.py (the shared pixelart Canvas and palette materials, light from the top left, outline in the
darkest step, each piece seen about the way its 3D model is).
"""

from furniture_icons import FRONT, UP, _diamond
from stsv_assets.pixelart import Canvas

ICONS = {}


def icon(pid):
    def deco(fn):
        ICONS[pid] = fn
        return fn
    return deco


@icon("yeontan_stove")
def yeontan_stove():
    c = Canvas()
    c.rect(11, 0, 13, 5, "steel", 1, normal=FRONT, lo=2, hi=4)         # the slim flue at the back
    c.rect(10.5, 0, 13.5, 1, "steel", 2, step=2)
    c.cyl(2.5, 6, 13.5, 15, "steel", 2, gloss=0.2)                      # the tall round body
    c.rect(2.5, 8, 13.5, 8.7, "blade", 3, step=3)                       # the bright bands
    c.rect(2.5, 11.3, 13.5, 12, "blade", 3, step=3)
    c.rect(6, 12, 10, 15, "iron", 4, step=0)                            # the ash door
    c.dots([(7, 13), (9, 13)], "fire", 4, z=5)
    c.ellipse(8, 6, 6.5, 1.8, "iron", 5, dome=0.3, gloss=0)             # the iron top plate
    c.ellipse(9.5, 6.2, 2.2, 1.0, "fire", 6, lo=3, hi=5, gloss=0.4)     # the glowing briquette
    c.cyl(2.5, 2.8, 6.5, 5.8, "brass", 7, gloss=0.6)                    # the yellow kettle
    c.ellipse(4.5, 2.8, 1.5, 0.7, "brass", 8, dome=0.3)
    c.put(4, 2, "rubber", 1, z=9)
    c.line(1, 3, 2, 4, "brass", 2, z=9)                                 # its spout
    for (x, y) in ((3, 15), (12, 15)):
        c.put(x, y, "iron", 1, z=1)
    return c.render()


@icon("kerosene_stove")
def kerosene_stove():
    c = Canvas()
    c.cyl(2, 11, 14, 15.5, "bottle_green", 0, gloss=0.6)
    c.rect(2, 12.5, 14, 13.2, "porcelain", 1, step=2)
    c.cyl(4.5, 7, 11.5, 11, "steel", 2)
    c.rect(6.5, 8, 9.5, 10, "fire", 3, step=4)                          # the mica window
    c.ellipse(8, 6.6, 3.8, 1, "sapphire", 4, lo=2, hi=4, gloss=0.2)     # blue flame ring
    c.cyl(2.5, 1.5, 13.5, 6, "brass", 5, gloss=0.8)                     # the pot
    c.ellipse(8, 1.6, 5.5, 1.2, "brass", 6, dome=0.3)
    c.put(8, 1, "rubber", 1, z=7)
    c.put(1, 3, "rubber", 1, z=7)
    c.put(14, 3, "rubber", 1, z=7)
    c.rect(14, 12, 15.5, 14, "rubber", 8, step=1)                       # the wick knob
    return c.render()


@icon("rice_cooker")
def rice_cooker():
    c = Canvas()
    c.cyl(2, 5, 14, 15, "cream", 0, gloss=0.7)
    for (x, y, mat) in ((4, 9, "pink"), (8, 10, "pepper"), (12, 9, "pink"), (6, 12, "pepper"), (10, 12, "pink")):
        c.dots([(x, y), (x + 1, y), (x, y + 1), (x + 1, y + 1)], mat, 2, z=3)
        c.put(x, y, "topaz", 3, z=4)
    for (x, y) in ((5, 11), (9, 11), (12, 11)):
        c.put(x, y, "leaf", 2, z=3)
    c.rect(2, 4.2, 14, 5.4, "blade", 1, step=3)                         # chrome rim
    c.ellipse(8, 3.6, 5.6, 2.3, "cream", 2, dome=0.5)
    c.line(3, 3, 13, 3, "cream", 1, z=3)                                # the folded handle
    c.put(8, 2, "blade", 3, z=4)
    c.put(7, 7, "signal_red", 1, z=5)
    c.rect(13.5, 2, 15, 9, "porcelain", 6, step=2)                      # the rice paddle
    return c.render()


@icon("glass_cupboard")
def glass_cupboard():
    c = Canvas()
    c.rect(2, 0, 14, 16, "wood", 0, normal=FRONT)
    c.rect(1.5, 0, 14.5, 1, "wood_dark", 1, step=1)
    c.rect(3, 1.5, 13, 7.5, "glass", 1, step=0)                          # behind the glass
    c.rect(3, 4.2, 13, 4.8, "wood", 2, step=1)                           # the shelf
    for (x, mat) in ((4, "pink"), (6, "indigo"), (10, "sakura")):
        c.rect(x, 2.5, x + 1.6, 4.2, "porcelain", 3, step=2)
        c.put(x, 2, mat, 2, z=4)
    c.rect(9, 5, 12, 6.5, "porcelain", 3, step=2)
    c.line(4, 7, 7, 2, "glass", 3, z=5)                                  # reflections
    c.line(10, 7, 12, 4, "glass", 3, z=5)
    c.line(8, 1.5, 8, 7.5, "wood_dark", 1, z=5)
    c.rect(1.5, 8, 14.5, 9, "wood_dark", 6, step=1)                      # the counter ledge
    c.rect(3, 9.5, 7.5, 15, "wood", 6, step=2)
    c.rect(8.5, 9.5, 13, 15, "wood", 6, step=2)
    c.dots([(7, 12), (9, 12)], "brass", 3, z=7)
    return c.render()


@icon("ramen_pot")
def ramen_pot():
    c = Canvas()
    _diamond(c, 8, 12.5, 7.5, 3.2, "paper", 0)                           # the newspaper under it
    c.line(3, 12, 6, 10.5, "paper", 0, z=1)
    c.cyl(1.5, 7, 12.5, 14, "brass", 2, gloss=0.8)                       # the pot
    c.rect(0, 8, 1.5, 9, "brass", 3, step=1)                              # its handles
    c.rect(12.5, 8, 14, 9, "brass", 3, step=1)
    c.ellipse(7, 7, 5.6, 2.4, "brass", 3, dome=0.2, gloss=0.3)            # the rim
    c.ellipse(7, 7.2, 4.4, 1.7, "pepper", 4, dome=0.1, gloss=0)           # red broth
    for (x, y) in ((4, 7), (5, 8), (6, 7), (8, 8), (9, 7), (7, 6), (5, 6)):
        c.put(x, y, "corn", 3, z=5)                                      # noodles
    c.dots([(8, 7), (9, 7)], "rice", 2, z=6)                             # the egg
    c.put(8, 7, "corn", 3, z=7)
    c.put(4, 8, "sprout", 3, z=7)
    c.line(10, 6, 15, 1, "blade", 3, z=8)                                # chopsticks resting in the pot
    c.line(11, 7, 15, 3, "blade", 2, z=8)
    c.ellipse(13.5, 13.5, 2.2, 1.1, "porcelain", 9, dome=0.3)            # the kimchi dish
    c.dots([(13, 13), (14, 13)], "pepper", 2, z=10)
    return c.render()


@icon("fridge")
def fridge():
    c = Canvas()
    c.rect(2.5, 0.5, 13.5, 16, "celadon", 0, normal=FRONT, lo=2, hi=4)
    c.put(2, 0, "celadon", 2, z=1)
    c.rect(2.5, 5.3, 13.5, 6, "blade", 1, step=3)                        # chrome trim between the doors
    c.rect(11, 1.5, 12, 4.5, "blade", 2, step=3)
    c.rect(11, 7, 12, 12, "blade", 2, step=3)
    c.rect(4, 1.5, 7, 4.5, "paper", 2, step=2)                           # memo
    c.put(5, 1, "pepper", 2, z=3)
    c.put(8, 2, "corn", 3, z=3)
    c.put(4, 9, "tomato", 2, z=3)
    c.rect(3, 15, 13, 16, "rubber", 3, step=1)
    return c.render()


@icon("briquette_pile")
def briquette_pile():
    c = Canvas()
    for (x, y) in ((3, 2), (8, 2), (3, 6), (8, 6), (3, 10), (8, 10)):
        c.cyl(x, y + 1, x + 5, y + 4.5, "iron", 1, gloss=0.4)
        c.ellipse(x + 2.5, y + 1, 2.5, 0.9, "iron", 2, dome=0.2, gloss=0)
        c.dots([(x + 1, y + 1), (x + 3, y + 1)], "rubber", 0, z=3)
    c.cyl(11, 11, 15.5, 15, "millstone", 4, gloss=0.4)                   # a burnt one
    c.ellipse(13.25, 11, 2.25, 0.8, "millstone", 5, dome=0.2, gloss=0)
    c.line(14, 1, 12, 10, "iron", 2, z=6)                                # the tongs
    c.line(15, 1, 13, 10, "iron", 2, z=6)
    return c.render()


@icon("brass_kettle")
def brass_kettle():
    c = Canvas()
    c.ellipse(8, 14, 7.5, 1.8, "brass", 0, dome=0.3, gloss=0.2)          # the tray
    c.line(6, 1, 11, 1, "brass", 1, z=1)                                 # the bail handle standing up
    c.line(5, 2, 5, 7, "brass", 1, z=1)
    c.line(12, 2, 12, 7, "brass", 1, z=1)
    c.dots([(5, 2), (12, 2)], "brass", 1, z=1)
    c.ellipse(8.5, 11.5, 5, 3, "brass", 3, gloss=0.9)                    # the squat body, widest low
    c.ellipse(8.5, 8.6, 3.6, 1.6, "brass", 4, gloss=0.8)                 # the shoulder
    c.ellipse(8.5, 7.2, 2.2, 1.0, "brass", 5, dome=0.4)                  # the domed lid
    c.put(8, 6, "rubber", 1, z=6)                                        # its knob
    c.line(4, 11, 1, 6, "brass", 2, z=7)                                 # the long spout rising
    c.line(4, 12, 2, 7, "brass", 1, z=6)
    c.cyl(11.5, 12, 14.5, 14.5, "brass", 8, gloss=0.8)                   # a cup in front
    c.put(12, 12, "cream", 3, z=9)
    c.put(13, 12, "cream", 3, z=9)
    return c.render()


@icon("cassette_radio")
def cassette_radio():
    c = Canvas()
    c.line(2, 5, 5, 1, "blade", 3, z=0)                                  # the antenna
    c.rect(4, 3, 12, 4, "rubber", 1, step=1)                             # handle
    c.rect(4, 4, 5, 6, "blade", 1, step=2)
    c.rect(11, 4, 12, 6, "blade", 1, step=2)
    c.rect(0.5, 5.5, 15.5, 14.5, "steel", 2, step=1)
    c.rect(0.5, 5.5, 15.5, 6.3, "rubber", 3, step=1)
    for cx in (3.5, 12.5):
        c.ellipse(cx, 10.5, 3, 3, "blade", 4, lo=2, hi=3, gloss=0.6)      # the bright silver rims
        c.ellipse(cx, 10.5, 1.9, 1.9, "rubber", 5, lo=0, hi=0, gloss=0)   # the dark grilles
        c.dots([(round(cx) - 1, 10), (round(cx) + 1, 10), (round(cx), 9), (round(cx), 11)], "rubber", 2, z=6)
        c.put(round(cx), 10, "blade", 3, z=7)                            # dust cap
    c.rect(6.5, 9, 9.5, 13, "steel", 5, step=3)                          # the cassette window's frame
    c.rect(7, 10, 9, 12, "tint", 6, step=1)
    c.dots([(7, 11), (8, 11)], "porcelain", 2, z=7)                      # two reels
    c.rect(7, 10, 9, 10.5, "paper", 7, step=2)                           # label
    c.rect(6.5, 7, 9.5, 8, "dial", 6, step=1)                            # the dial strip
    c.put(8, 7, "signal_red", 1, z=8)
    return c.render()


@icon("washtub")
def washtub():
    c = Canvas()
    c.rect(10, 1, 14, 9, "wood", 0, normal=FRONT)                        # washboard behind
    for y in range(2, 9, 2):
        c.line(10, y, 13, y, "wood", 1, z=1)
    c.cyl(1, 8, 15, 15.5, "fabric_red", 2, gloss=0.7)
    c.ellipse(8, 8, 7, 2.6, "fabric_red", 3, dome=0.3)
    c.ellipse(8, 8.2, 5.8, 1.9, "water", 4, dome=0.1, gloss=0)
    c.ellipse(6, 8, 2.2, 1, "porcelain", 5, dome=0.2)                    # undershirt
    c.ellipse(10.5, 8.6, 1.6, 0.8, "denim", 5, dome=0.2)
    c.dots([(4, 9), (9, 7), (12, 8)], "porcelain", 3, z=6)              # suds
    c.line(0, 6, 15, 6, "wood_dark", 2, z=7)                             # the laundry bat
    return c.render()
