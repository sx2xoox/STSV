"""
The fisher's water furniture (economy: items/jobs.yml fish_trap / fish_pen, fish.yml trap / pen), baked like the other furniture
(stsv_assets/bake.py: one texel grid at 16 px a block, baked light, one atlas a model). Both stand in water (their cells must be
water blocks); they are not solid, so the water stays and the model shows through it.

    통발 fish_trap 1x1x1: a net cylinder on three hoops lying on the bottom, funnel mouths at both ends, a rope up to a float
    가두리 fish_pen 2x1x2: a square floating frame of black pipe on orange floats, a walkway on two sides, rope rails on posts,
                         net walls hanging down to a net floor, a few fish inside

Coordinates as the machines (economy_machines.py): model units, front = north (-z), the anchor block x 0..16 / z 0..16, the pen
grows to +x and +z (its cells [0..1, 0, 0..1]), floor y 0.
"""

import math

from economy_machines import display
from stsv_assets.bake import BModel, Paint, cyl

NET_GREEN = "leaf_dark"


def _mesh(ramp, step, every=2):
    """Decal: a knotted net, darker cords every `every` units across the face's two axes (world space, so the mesh runs on round
    the hoops and corners)."""
    def decal(c):
        if c.face in ("up", "down"):
            a, b = c.p[0], c.p[2]
        elif c.face in ("north", "south"):
            a, b = c.p[0], c.p[1]
        else:
            a, b = c.p[2], c.p[1]
        if math.floor(a) % every == 0 or math.floor(b) % every == 0:
            return (ramp, step - 1)
        return None
    return decal


NET = Paint(NET_GREEN, 2, decal=_mesh(NET_GREEN, 2), noise=0.04)
HOOP = Paint("vermilion", 2, "plain", noise=0.02)
ROPE = Paint("straw", 2, "weave", noise=0.03, rim=0.5)
FLOAT = Paint("vermilion", 3, noise=0.02)
PIPE = Paint("rubber", 1, "plain", noise=0.02)
PLANK = Paint("wood", 2, "planks", axis="x", width=4)
POST = Paint("wood_dark", 2, "grain", axis="y")
FISH = Paint("fish_silver", 2, noise=0.03)


def fish_trap():
    """통발: a dark green net cylinder (8 across) along x on three orange hoops, the funnel mouths at its ends dark, a rope from
    the middle hoop up to a red float near the top of the block."""
    m = BModel("fish_trap")
    m.group("net")

    def mouth(c):
        # the funnel going in: the round ends are dark towards the middle
        if c.face in ("east", "west") and math.hypot(c.p[1] - 4.5, c.p[2] - 8) < 2.4:
            return ("soot", 1)
        return None
    net = Paint(NET_GREEN, 2, decal=lambda c: mouth(c) or _mesh(NET_GREEN, 2)(c), noise=0.04)
    cyl(m, "net", (4.5, 8), 8, 2, 14, net, axis="x")
    m.group("hoops")
    for x in (2.5, 7.5, 12.5):
        cyl(m, "hoop", (4.5, 8), 9, x, x + 1, HOOP, axis="x")
    m.group("float")
    m.box("rope", (7.75, 9, 7.75), (8.25, 13, 8.25), ROPE)
    m.box("float", (6.5, 13, 6.5), (9.5, 15.5, 9.5), FLOAT)
    m.box("band", (6.4, 14, 6.4), (9.6, 14.5, 9.6), Paint("snow", 2, noise=0))
    return display(m, (1, 1, 1), 0)


def fish_pen():
    """가두리 2x1x2: a floating square of black pipe a block wide all round 32 x 32, orange floats under its corners, plank
    walkways along the front and the left, four posts with a rope rail, net walls down to a net floor and fish swimming inside."""
    m = BModel("fish_pen")
    m.group("net")
    wall = Paint(NET_GREEN, 2, decal=_mesh(NET_GREEN, 2), noise=0.04, ao=0.4)
    m.box("net_floor", (2, 1, 2), (30, 1.5, 30), wall)
    m.box("net_wall", (2, 1.5, 2), (30, 11, 2.5), wall)
    m.box("net_wall", (2, 1.5, 29.5), (30, 11, 30), wall)
    m.box("net_wall", (2, 1.5, 2.5), (2.5, 11, 29.5), wall)
    m.box("net_wall", (29.5, 1.5, 2.5), (30, 11, 29.5), wall)
    m.group("frame")
    m.box("pipe", (0, 11, 0), (32, 13, 2), PIPE)
    m.box("pipe", (0, 11, 30), (32, 13, 32), PIPE)
    m.box("pipe", (0, 11, 2), (2, 13, 30), PIPE)
    m.box("pipe", (30, 11, 2), (32, 13, 30), PIPE)
    m.box("walkway", (0, 13, 0), (32, 14, 4), PLANK)
    m.box("walkway", (28, 13, 4), (32, 14, 32), Paint("wood", 2, "planks", axis="z", width=4))
    for (x, z) in ((0, 0), (30, 0), (0, 30), (30, 30), (15, 0), (15, 30), (0, 15), (30, 15)):
        m.box("float", (x, 8.5, z), (x + 2, 11, z + 2), FLOAT)
    m.group("rail")
    for (x, z) in ((0.5, 0.5), (30, 0.5), (0.5, 30), (30, 30)):
        m.box("post", (x, 13, z), (x + 1.5, 21, z + 1.5), POST)
    m.box("rail", (2, 19.5, 0.75), (30, 20, 1.25), ROPE)
    m.box("rail", (2, 19.5, 30.75), (30, 20, 31.25), ROPE)
    m.box("rail", (0.75, 19.5, 2), (1.25, 20, 30), ROPE)
    m.box("rail", (30.5, 19.5, 2), (31, 20, 30), ROPE)
    m.group("fish")
    for (x, y, z, along) in ((8, 5, 10, "x"), (18, 7, 20, "z"), (21, 4, 9, "x"), (10, 8, 22, "z")):
        if along == "x":
            m.box("fish", (x, y, z), (x + 5, y + 1.5, z + 1.5), FISH)
            m.box("tail", (x + 5, y, z + 0.25), (x + 6.5, y + 1.5, z + 1.25), FISH)
        else:
            m.box("fish", (x, y, z), (x + 1.5, y + 1.5, z + 5), FISH)
            m.box("tail", (x + 0.25, y, z + 5), (x + 1.25, y + 1.5, z + 6.5), FISH)
    return display(m, (2, 1, 2), 0)


ALL = {"fish_trap": fish_trap, "fish_pen": fish_pen}
