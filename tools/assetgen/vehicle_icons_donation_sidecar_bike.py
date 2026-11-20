"""
The inventory icon (16x16) of the donation vehicle 사이드카 오토바이 (sidecar_bike), drawn like the other vehicle
icons (pixel art on the shared Canvas, palette materials, the outline added round the outside), seen from its left
side with the front to the left: the bike in front (two wire wheels, the round chrome headlamp, the springer fork, the
maroon teardrop tank with its cream flash, the tan sprung saddle, the finned V-twin, the chrome pipe to a fishtail),
the sidecar's torpedo tub behind it and higher (seen a little from above, it is the far side): its chrome nose, the
cream upper half over the chrome spear, the little windscreen, the spare wheel standing on its tail.
"""

import vehicle_icons
from vehicle_icons import icon
from vehicle_icons_road import box, wheel
from stsv_assets.pixelart import Canvas
import models.vehicles_donation_sidecar_bike  # noqa: F401  (registers the sc_ ramps)


@icon("sidecar_bike")
def sidecar_bike():
    c = Canvas()
    P, T, L, CH = "sc_maroon", "sc_cream", "sc_leather", "sc_chrome"
    # the sidecar's tub, behind: a torpedo with a chrome nose, the cream upper half, the chrome spear, maroon below
    box(c, 3, 3, 12, 3, T, 4, 1)
    box(c, 2, 4, 12, 4, T, 3, 1)
    box(c, 2, 5, 12, 5, CH, 4, 1)             # the spear
    box(c, 3, 6, 12, 6, P, 1, 1)
    box(c, 4, 7, 11, 7, P, 1, 1)
    c.put(1, 4, CH, 5, 1)                     # the chrome nose
    c.put(1, 5, CH, 2, 1)
    c.put(0, 5, CH, 3, 1)
    c.put(2, 3, CH, 3, 1)
    c.put(1, 6, CH, 1, 1)
    c.put(2, 6, CH, 2, 1)
    box(c, 7, 2, 10, 2, CH, 4, 1)             # the cockpit's chrome roll
    box(c, 9, 1, 10, 1, L, 2, 1)              # the passenger's seat back
    c.put(5, 1, "sc_glass", 2, 1)             # the windscreen, leaning back
    c.put(6, 1, CH, 3, 1)
    c.put(5, 2, CH, 2, 1)
    box(c, 13, 1, 14, 7, "sc_rubber", 2, 1)   # the spare wheel on the tail
    box(c, 15, 2, 15, 6, "sc_rubber", 1, 1)
    c.put(14, 4, CH, 3, 1)
    c.put(13, 4, CH, 2, 1)
    box(c, 9, 14, 11, 14, "sc_rubber", 2, 1)  # the sidecar's own wheel, under the far side
    c.put(10, 15, "sc_rubber", 1, 1)
    # the bike, in front
    wheel(c, 3, 12, 3, tyre=("sc_rubber", 1), rim=(CH, 4), hub=(CH, 5), spokes=False)
    wheel(c, 12, 12, 3, tyre=("sc_rubber", 1), rim=(CH, 4), hub=(CH, 5), spokes=False)
    box(c, 0, 8, 4, 8, P, 4, 3)               # the front fender, its cream stripe
    c.put(2, 8, T, 2, 3)
    c.put(0, 9, P, 1, 3)
    box(c, 11, 8, 15, 8, P, 4, 3)             # the rear fender
    c.put(13, 8, T, 2, 3)
    c.put(15, 9, P, 1, 3)
    c.put(15, 7, "sc_red", 3, 4)              # the tail lamp
    c.line(3, 7, 4, 11, "sc_black", 3, 4)     # the springer fork
    c.put(3, 8, CH, 3, 5)                     # its spring
    box(c, 0, 5, 2, 7, CH, 2, 4)              # the headlamp bucket
    c.put(0, 6, "sc_lamp", 3, 5)
    c.put(1, 6, "sc_lamp", 2, 5)
    c.put(1, 5, CH, 4, 5)
    c.line(3, 4, 5, 4, CH, 3, 5)              # the handlebar, a grip
    c.put(6, 4, "sc_rubber", 3, 5)
    box(c, 5, 6, 8, 7, P, 2, 4)               # the teardrop tank
    box(c, 5, 5, 7, 5, P, 4, 4)
    c.put(4, 6, P, 3, 4)
    box(c, 6, 7, 7, 7, T, 2, 5)               # its cream flash
    c.put(6, 5, CH, 4, 5)                     # the crown strip
    box(c, 9, 6, 11, 6, L, 3, 4)              # the saddle
    c.put(11, 5, L, 2, 4)
    c.put(10, 7, CH, 3, 4)                    # its spring
    c.line(6, 10, 6, 8, "sc_iron", 3, 4)      # the V-twin: the finned front barrel
    c.line(8, 10, 9, 8, "sc_iron", 3, 4)      # the rear barrel
    c.put(6, 9, "sc_alloy", 4, 5)
    c.put(9, 9, "sc_alloy", 4, 5)
    box(c, 6, 11, 9, 11, "sc_alloy", 2, 4)    # the crankcase
    c.put(7, 10, "sc_alloy", 3, 4)
    c.put(5, 11, CH, 4, 5)                    # the primary cover
    box(c, 5, 13, 10, 13, CH, 4, 5)           # the pipe and its fishtail
    box(c, 11, 13, 14, 13, CH, 5, 5)
    c.put(15, 12, CH, 2, 5)
    c.put(15, 14, CH, 2, 5)
    return c.render()


ICONS = vehicle_icons.ICONS
