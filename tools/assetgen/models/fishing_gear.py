"""
The fisher's water furniture (economy: items/jobs.yml fish_trap / fish_pen, fish.yml trap / pen), baked like the other furniture
(stsv_assets/bake.py: one texel grid at 16 px a block, baked light, one atlas a model). Both stand in water (their cells must be
water blocks); they are not solid, so the water stays and the model shows through it.

    통발 fish_trap 1x1x1: a Korean cylindrical net trap lying on the bottom: an octagonal tube of knotted net (cut-out cords, see
                         through) on three orange hoops, a net funnel going in at each end (the small mouth's lips white), a
                         perforated bait cup hanging in the middle, two lead weights under it, a rope up to a buoy at the surface
                         (an orange float with a silver reflective band, a short pole and a red flag)
    가두리 fish_pen 2x1x2: a fish-farm pen floating at the surface: a double ring of black HDPE pipe on white foam floats, tied by
                         brackets, plank walkways on two sides (the boards' seams across), a feeding hatch with its lid open and a
                         feed sack, four mooring posts with lines down into the water, net walls and a net floor hanging under the
                         ring (cut-out mesh, sewn seams and a weighted bottom line), fish swimming at different depths

Coordinates as the machines (economy_machines.py): model units, front = north (-z), the anchor block x 0..16 / z 0..16, the pen
grows to +x and +z (its cells [0..1, 0, 0..1]), floor y 0. Water in a block stands 14.2 high: the buoy and the pen's ring float
there. The nets are planes of no thickness whose texels between the cords are cut out (bake.py: holes on thin planes only).
"""

import math

from economy_machines import display
from stsv_assets.bake import BModel, Paint, cyl

NET_GREEN = "leaf_dark"
HOLE = ("raw", (0, 0, 0, 0))
SURFACE = 14.2          # a water block's surface


def _uv(c):
    """A texel's two in-plane world coordinates (the net's own grid runs on in world space)."""
    if c.face in ("up", "down"):
        return c.p[0], c.p[2]
    if c.face in ("north", "south"):
        return c.p[0], c.p[1]
    return c.p[2], c.p[1]


def _net(ramp=NET_GREEN, step=2, cell=3, seam=None):
    """Decal for a net plane: square mesh (cords every `cell` texels both ways, darker knots where they cross), the holes
    between cut out; seam(a, b) -> True paints a sewn rope there instead (the pen's seams and edge ropes)."""
    def decal(c):
        a, b = _uv(c)
        ia, ib = math.floor(a), math.floor(b)
        if seam and seam(a, b):
            return ("straw", 1)
        d1, d2 = ia % cell == 0, ib % cell == 0
        if d1 and d2:
            return (ramp, step - 1)
        if d1 or d2:
            return (ramp, step + (1 if (ia * 7 + ib * 3) % 5 == 0 else 0))
        return HOLE
    return decal


HOOP = Paint("vermilion", 2, "plain", noise=0.03)
ROPE = Paint("straw", 2, "weave", noise=0.04, rim=0.5)
BUOY = Paint("vermilion", 3, noise=0.03)
TAPE = Paint("steel", 4, "metal", noise=0.02, rim=1.4)
FLAG = Paint("fabric_red", 2, noise=0.02, light=False)
LEAD = Paint("iron", 1, "metal", noise=0.08)
PIPE = Paint("rubber", 1, "plain", noise=0.03, rim=1.2)
FOAM = Paint("snow", 3, "speckle", noise=0.12)
POST = Paint("wood_dark", 2, "grain", axis="y", noise=0.06)
FISH = Paint("fish_silver", 2, noise=0.04)
FISH_DARK = Paint("fish_blue", 2, noise=0.04)


def _planks(axis):
    """Walkway boards running `axis`-wise, weathered (a few darker boards and nail heads)."""
    def nails(c):
        a, b = _uv(c)
        if c.face == "up" and (math.floor(a) % 8 == 1) and (math.floor(b) % 8 == 1):
            return ("wood_dark", 1)
        return None
    return Paint("wood", 2, "planks", axis=axis, width=4, noise=0.08, decal=nails)


# ================================================================================================
# 통발
# ================================================================================================

def fish_trap():
    """통발 1x1x1, see the module doc (three hoops: the ends and the middle; 300 faces a 1-cell piece)."""
    m = BModel("fish_trap")
    cy, cz, r = 5.5, 8.0, 4.5                 # the tube's axis (along x) and its apothem
    x0, x1 = 1.5, 14.5
    half = r * math.tan(math.pi / 8) + 0.15     # half a side of the octagon (a little over: the panels meet)
    net = Paint(NET_GREEN, 2, decal=_net(), noise=0.05, ao=0.3, rim=0.3)

    m.group("net")
    # the tube: 4 panels on the axes, 4 turned 45 degrees about the axis
    for sign in (1, -1):
        y = cy + sign * r
        for ang in (0, 45, -45):
            rot = ("x", ang, (8, cy, cz)) if ang else None
            if ang and sign < 0:
                continue
            m.box("net_tube", (x0, y, cz - half), (x1, y, cz + half), net, rotation=rot)
        z = cz + sign * r
        m.box("net_tube", (x0, cy - half, z), (x1, cy + half, z), net)
    for ang in (45, -45):
        m.box("net_tube", (x0, cy - r, cz - half), (x1, cy - r, cz + half), net, rotation=("x", ang, (8, cy, cz)))
    # the funnels: at each end four panels lean in from the rim to a small mouth 3 inside
    lean = 3.4 * math.sqrt(2)
    for end, s in ((x0, 1), (x1, -1)):
        far = end + s * lean
        a, b = sorted((end, far))
        m.box("funnel", (a, cy - half, cz - r), (b, cy + half, cz - r), net, rotation=("y", -45 * s, (end, cy, cz - r)))
        m.box("funnel", (a, cy - half, cz + r), (b, cy + half, cz + r), net, rotation=("y", 45 * s, (end, cy, cz + r)))
        m.box("funnel", (a, cy + r, cz - half), (b, cy + r, cz + half), net, rotation=("z", -45 * s, (end, cy + r, cz)))
        m.box("funnel", (a, cy - r, cz - half), (b, cy - r, cz + half), net, rotation=("z", 45 * s, (end, cy - r, cz)))
        # the mouth: white plastic lips above and below where the funnel ends
        mx = end + s * 3.2
        lo, hi = sorted((mx, mx + s * 0.5))
        ring = Paint("snow", 3, noise=0.02)
        m.box("mouth", (lo, cy + 1.1, cz - 1.5), (hi, cy + 1.6, cz + 1.5), ring)
        m.box("mouth", (lo, cy - 1.6, cz - 1.5), (hi, cy - 1.1, cz + 1.5), ring)

    m.group("hoops")
    t = 0.35                                    # half the hoop's square section
    for x in (x0, 8.0, x1):
        for sign in (1, -1):
            y = cy + sign * r
            m.box("hoop", (x - t, y - t, cz - half), (x + t, y + t, cz + half), HOOP)
            z = cz + sign * r
            m.box("hoop", (x - t, cy - half, z - t), (x + t, cy + half, z + t), HOOP)
        for ang in (45, -45):
            for y in (cy + r, cy - r):
                m.box("hoop", (x - t, y - t, cz - half), (x + t, y + t, cz + half), HOOP, rotation=("x", ang, (x, cy, cz)))

    m.group("bait")
    # a perforated bait cup on a cord from the top of the middle, a bit of fish showing through its holes
    holes = Paint("porcelain", 3, decal=lambda c: ("porcelain", 1) if (math.floor(_uv(c)[0] * 2) + math.floor(_uv(c)[1] * 2)) % 3 == 0 else None)
    m.box("cord", (7.9, 7.4, 7.9), (8.1, cy + r - 0.1, 8.1), ROPE)
    cyl(m, "cup", (8, 8), 3, 4.6, 7.4, holes)
    m.box("bait", (7.3, 5.6, 7.3), (8.7, 6.4, 8.7), Paint("salmon", 3, noise=0.1))

    m.group("weights")
    for x in (4.2, 11.8):
        m.box("lead", (x - 0.8, 0.0, cz - 1.6), (x + 0.8, 1.1, cz + 1.6), LEAD)

    m.group("buoy")
    # the rope from the top of the far hoop, kinked twice on its way up to the float
    m.box("rope", (x1 - 0.6, cy + r + 0.35, cz - 0.15), (x1 - 0.3, 11.6, cz + 0.15), ROPE)
    m.box("rope", (x1 - 0.6, 11.4, cz - 0.15), (x1 - 0.3, 14.2, cz + 0.15), ROPE,
          rotation=("z", 22.5, (x1 - 0.45, 11.5, cz)))
    bx, bz = 12.0, cz
    cyl(m, "float", (bx, bz), 4, SURFACE - 1.6, SURFACE + 1.2, BUOY)
    cyl(m, "tape", (bx, bz), 4.4, SURFACE - 0.1, SURFACE + 0.5, TAPE)
    m.box("pole", (bx - 0.2, SURFACE + 1.2, bz - 0.2), (bx + 0.2, SURFACE + 6.5, bz + 0.2), Paint("wood_dark", 1, "grain", axis="y"))
    m.box("flag", (bx + 0.2, SURFACE + 4.4, bz), (bx + 3.4, SURFACE + 6.4, bz), FLAG)
    return display(m, (1, 1, 1), 0)


# ================================================================================================
# 가두리
# ================================================================================================

def fish_pen():
    """가두리 2x1x2, see the module doc."""
    m = BModel("fish_pen")
    lo, hi = 3.0, 29.0                          # the net's square (inside the inner pipe)
    top, bottom = SURFACE - 0.6, 1.0

    def seams(a, b):
        # sewn ropes down the net every 13 units and along its top edge
        return abs(a - 16.0) < 0.5 or abs(b - top + 0.4) < 0.5

    wall = Paint(NET_GREEN, 2, decal=_net(cell=4, seam=seams), noise=0.05, ao=0.25, rim=0.2)
    floor_ = Paint(NET_GREEN, 1, decal=_net(cell=4), noise=0.05, ao=0.2, rim=0.0)

    m.group("net")
    m.box("net_wall", (lo, bottom, lo), (hi, top, lo), wall)
    m.box("net_wall", (lo, bottom, hi), (hi, top, hi), wall)
    m.box("net_wall", (lo, bottom, lo), (lo, top, hi), wall)
    m.box("net_wall", (hi, bottom, lo), (hi, top, hi), wall)
    m.box("net_floor", (lo, bottom, lo), (hi, bottom, hi), floor_)
    # the weighted bottom line round the floor's edge and the corner ropes
    lead = Paint("rubber", 2, "weave", noise=0.05)
    m.box("leadline", (lo - 0.3, bottom - 0.3, lo - 0.3), (hi + 0.3, bottom + 0.3, lo + 0.3), lead)
    m.box("leadline", (lo - 0.3, bottom - 0.3, hi - 0.3), (hi + 0.3, bottom + 0.3, hi + 0.3), lead)
    m.box("leadline", (lo - 0.3, bottom - 0.3, lo + 0.3), (lo + 0.3, bottom + 0.3, hi - 0.3), lead)
    m.box("leadline", (hi - 0.3, bottom - 0.3, lo + 0.3), (hi + 0.3, bottom + 0.3, hi - 0.3), lead)
    for (x, z) in ((lo, lo), (hi, lo), (lo, hi), (hi, hi)):
        m.box("corner_rope", (x - 0.3, bottom + 0.3, z - 0.3), (x + 0.3, top, z + 0.3), ROPE)

    m.group("frame")
    # two rings of black pipe at the surface: outer 0.5..2.0, inner 2.5..4.0 from the edge
    py0, py1 = SURFACE - 0.9, SURFACE + 0.6
    for (a0, a1) in ((0.5, 2.0), (2.6, 4.1)):
        b0, b1 = 32 - a1, 32 - a0
        m.box("pipe", (a0, py0, a0), (b1, py1, a1), PIPE)
        m.box("pipe", (a0, py0, b0), (b1, py1, b1), PIPE)
        m.box("pipe", (a0, py0, a1), (a1, py1, b0), PIPE)
        m.box("pipe", (b0, py0, a1), (b1, py1, b0), PIPE)
    # brackets tie the two rings together, a few on each side
    br = Paint("rubber", 3, "plain", noise=0.02)
    for k in (9.0, 22.0):
        for (frm, to) in (((k, py1 - 0.2, 0.3), (k + 1.2, py1 + 0.3, 4.3)), ((k, py1 - 0.2, 27.7), (k + 1.2, py1 + 0.3, 31.7)),
                          ((0.3, py1 - 0.2, k), (4.3, py1 + 0.3, k + 1.2)), ((27.7, py1 - 0.2, k), (31.7, py1 + 0.3, k + 1.2))):
            m.box("bracket", frm, to, br)
    # white foam floats under the walkways, half under water
    for (frm, to) in (((1.0, SURFACE - 2.4, 0.6), (8.0, py0, 4.0)), ((12.5, SURFACE - 2.4, 0.6), (19.5, py0, 4.0)),
                      ((24.0, SURFACE - 2.4, 0.6), (31.0, py0, 4.0)), ((0.6, SURFACE - 2.4, 8.0), (4.0, py0, 15.0)),
                      ((0.6, SURFACE - 2.4, 19.0), (4.0, py0, 26.0))):
        m.box("float", frm, to, FOAM)

    m.group("walkway")
    wy0, wy1 = py1 + 0.3, py1 + 1.1
    m.box("walkway", (0.0, wy0, 0.0), (32.0, wy1, 4.6), _planks("z"))
    m.box("walkway", (0.0, wy0, 4.6), (4.6, wy1, 32.0), _planks("x"))
    # the feeding hatch on the front walkway: a frame round an opening, its lid propped open, a feed sack beside it
    hx = 13.0
    m.box("hatch", (hx, wy1, 0.4), (hx + 5.0, wy1 + 0.3, 1.0), Paint("wood_dark", 2))
    m.box("hatch", (hx, wy1, 3.6), (hx + 5.0, wy1 + 0.3, 4.2), Paint("wood_dark", 2))
    m.box("hatch_hole", (hx + 0.6, wy1 - 0.05, 1.0), (hx + 4.4, wy1 + 0.02, 3.6), Paint("soot", 0, light=False))
    m.box("lid", (hx, wy1 + 0.3, 0.4), (hx + 5.0, wy1 + 0.7, 3.8), Paint("wood", 3, "planks", axis="x", width=2),
          rotation=("x", -45, (hx, wy1 + 0.5, 0.4)))
    m.box("sack", (20.0, wy1, 1.0), (22.6, wy1 + 2.6, 3.4), Paint("burlap", 2, "weave", noise=0.08))
    m.box("sack_tie", (20.6, wy1 + 2.6, 1.6), (22.0, wy1 + 3.2, 2.8), Paint("straw", 3))

    m.group("moorings")
    # four posts at the corners, a rope tie on each and a mooring line slanting down into the water
    for (x, z, dx, dz) in ((0.2, 0.2, -1, -1), (30.3, 0.2, 1, -1), (0.2, 30.3, -1, 1), (30.3, 30.3, 1, 1)):
        m.box("post", (x, 3.0, z), (x + 1.5, wy1 + 7.0, z + 1.5), POST)
        m.box("tie", (x - 0.2, wy1 + 1.2, z - 0.2), (x + 1.7, wy1 + 1.8, z + 1.7), ROPE)
        cx, cz_ = x + 0.75, z + 0.75
        m.box("mooring", (cx - 0.2, 2.0, cz_ - 0.2), (cx + 0.2, wy1 + 1.2, cz_ + 0.2), ROPE,
              rotation=("x", -22.5 * dz, (cx, wy1 + 1.2, cz_)))

    m.group("fish")
    # fish at different depths, some turned: body, tail and a dorsal fin
    shoal = ((8.0, 10.5, 9.0, 0, FISH), (18.0, 7.0, 20.0, 22.5, FISH), (21.0, 4.0, 8.5, -22.5, FISH_DARK),
             (9.5, 3.0, 21.0, 0, FISH), (14.5, 8.5, 14.0, -22.5, FISH_DARK), (23.0, 11.0, 22.5, 22.5, FISH))
    for (x, y, z, ang, paint) in shoal:
        rot = ("y", ang, (x + 2.5, y, z + 0.6)) if ang else None
        m.box("fish", (x, y, z), (x + 5.0, y + 1.4, z + 1.2), paint, rotation=rot)
        m.box("tail", (x + 5.0, y - 0.3, z + 0.35), (x + 6.4, y + 1.7, z + 0.85), paint, rotation=rot)
        m.box("fin", (x + 1.5, y + 1.4, z + 0.45), (x + 3.0, y + 2.0, z + 0.75), paint, rotation=rot)
    return display(m, (2, 1, 2), 0)


ALL = {"fish_trap": fish_trap, "fish_pen": fish_pen}
