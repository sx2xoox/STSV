"""
The inventory icon (16x16) of the donation vehicle 사이드카 오토바이 (sidecar_bike), drawn like the other vehicle
icons (pixel art on the shared Canvas, palette materials, the outline added round the outside), seen from its left
side with the front to the left: the bike in front (two spoked wheels, the round headlamp, the burgundy teardrop tank
with its cream panel, the leather saddle, the chrome exhaust), the sidecar's bullet tub behind it and higher (seen a
little from above, it is the far side): its windscreen, its cream spear, its spare wheel on the tail.
"""

import vehicle_icons
from vehicle_icons import icon, TIRES
from vehicle_icons_road import box, wheel
from stsv_assets.pixelart import Canvas


@icon("sidecar_bike")
def sidecar_bike():
    c = Canvas()
    P, S, L = "wine", "cream", "leather"
    # the sidecar's tub, behind and darker: a bullet with a rounded nose, the cream spear, a chrome rim round the
    # cockpit, the passenger's seat back, the windscreen
    box(c, 3, 3, 12, 6, P, 2, 1)
    box(c, 2, 4, 2, 6, P, 2, 1)
    c.put(1, 5, P, 1, 1)
    c.put(1, 6, P, 1, 1)
    box(c, 4, 2, 12, 2, P, 4, 1)              # its lit top
    c.put(3, 3, P, 4, 1)
    box(c, 2, 4, 12, 4, S, 5, 1)              # the spear
    box(c, 7, 1, 11, 1, "blade", 5, 1)        # the cockpit's chrome rim
    box(c, 10, 0, 11, 0, L, 4, 1)             # the passenger's seat back
    c.line(5, 0, 5, 1, "glass", 5, 1)         # the windscreen, leaning back
    c.put(6, 0, "glass", 4, 1)
    c.put(6, 1, "tint", 3, 1)
    box(c, 13, 2, 14, 6, "rubber", 1, 1)      # the spare wheel on the tail
    box(c, 15, 3, 15, 5, "rubber", 1, 1)
    c.put(14, 4, "blade", 5, 1)
    c.put(13, 4, "blade", 3, 1)
    box(c, 8, 14, 10, 14, "rubber", 2, 1)     # the sidecar's own wheel, under the far side
    c.put(9, 15, "rubber", 1, 1)
    # the bike, in front
    wheel(c, 3, 12, 3, spokes=True)
    wheel(c, 12, 12, 3, spokes=True)
    box(c, 0, 8, 4, 8, P, 4, 3)               # the front fender
    c.put(0, 9, P, 3, 3)
    box(c, 11, 8, 15, 8, P, 4, 3)             # the rear fender
    c.put(15, 9, P, 3, 3)
    c.put(15, 8, "signal_red", 4, 4)          # the tail lamp
    c.line(3, 7, 3, 11, "blade", 4, 2)        # the fork
    box(c, 1, 5, 2, 7, "blade", 3, 4)         # the headlamp bucket
    c.put(1, 6, "lamp", 5, 5)
    c.put(1, 5, "lamp", 4, 5)
    c.line(3, 4, 5, 4, "blade", 4, 5)         # the handlebar
    c.put(6, 4, "rubber", 2, 5)
    box(c, 5, 6, 8, 8, P, 4, 4)               # the teardrop tank
    c.put(4, 7, P, 4, 4)
    box(c, 5, 5, 7, 5, P, 5, 4)
    box(c, 6, 7, 7, 7, S, 5, 5)               # its cream panel
    box(c, 10, 6, 12, 6, L, 4, 4)             # the saddle
    c.put(10, 7, L, 2, 4)
    c.put(12, 7, "blade", 4, 4)               # its spring
    box(c, 5, 9, 8, 11, "steel", 3, 4)        # the engine, finned
    c.line(5, 10, 8, 10, "steel", 1, 4)
    c.put(6, 9, "blade", 5, 5)
    box(c, 5, 13, 9, 13, "blade", 4, 5)       # the exhaust and its long muffler
    box(c, 10, 13, 13, 13, "blade", 5, 5)
    return c.render()


ICONS = vehicle_icons.ICONS
