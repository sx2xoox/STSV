"""
The fishing boats of the ladder round the 통통배 (content/stsv/items/vehicles_misc.yml; the 통통배 itself, 3세대, is in
models/vehicles_misc.py). Boats are for fishing from, together: every generation seats more people at its rail, holds more
of the catch, and from the 통통배 on drags a net (vehicle_tools.yml).

  1세대 뗏목 (raft)             pine logs lashed over three bamboo cross poles, a plank deck, a straw mat to sit on and a
                                stump for the one who sculls, a rack of fish drying, a paper lantern, a bamboo rod in its
                                rest, the punting pole (삿대) along the edge. No engine: a stern sweep (노).
      body   raft                 keel bottom middle at (0, 0, 0), bow = north (-z); drawn at its real size, shrunk by 2
      wheel  raft_scull           the sweep: its pivot at model (8, 8, 8), the loom and blade trailing aft and down; the
                                  plugin turns a boat's "wheel" about its length, so the blade sweeps round as it goes
      cargo  raft_catch_1..4      wicker creels and a string of fish on the deck behind the stump
  2세대 나룻배 (rowboat)         a planked wooden skiff: tarred bottom, a dark wale, three thwarts, a covered bow with a
                                lantern and a fish trap (통발), a bait bucket, a rod in a holder on each quarter.
      body   rowboat
      parts  rowboat_oar_r / _l   the oars (spinners): the thole pin at model (8, 8, 8), the oar pointing outboard and
                                  22.5 degrees down; turning about the boat's width it dips, pulls aft, lifts, comes forward
      cargo  rowboat_catch_1..4   red rubber basins (고무 다라이) of fish, a wicker basket, a straw mat over the lot
  4세대 연안 어선 (coastal_boat)  a white FRP coastal boat: a flared bow with a stainless pulpit, a cabin amidships (glass
                                all round, open aft under an awning), racks of squid lamps (집어등) down both sides, a
                                radar arch, lockers to sit on along the rail, rod holders with rods, a live well.
      body   coastal_boat         shrunk by 2.5
      wheel  boat_propeller       (the 통통배's)
      cargo  coastal_catch_1..4   fish crates stacked on the aft deck
  5세대 트롤 어선 (trawler)       a steel stern trawler: a navy hull with a red bottom and draught marks, green decks, a
                                white deckhouse and bridge forward, a funnel, a mast with a radar, a fish hatch and a
                                winch on the working deck, an orange gantry over the stern ramp with the trawl doors hung
                                on it, benches along the rail.
      body   trawler              shrunk by 3.5
      wheel  trawler_propeller    a bigger screw
      cargo  trawler_catch_1..4   pallets of fish crates on the working deck

Detail is painted, not modelled (docs/ART_STYLE.md, 렌더 예산): plank seams, weld lines, rust, rivets, the weave of the
baskets, the portholes of the trawler's deckhouse.
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, h3
from stsv_assets import presets
from models.vehicles import CLEAR, _grain, _held_display, _tarp, glass
from models.vehicles_misc import _fish_crate, _ice_box, _net_heap, _buoy, _rope_coil


def _flat(ramp, top=2.8, side=1.8, under=1.2):
    """A plain part: lit on top, darker on its sides, darkest underneath."""
    return Paint(ramp, 2, noise=0, rim=0.5, decal=lambda c: (ramp, top if c.face == "up" else under if c.face == "down" else side))


def _pole_paint(ramp, axis, seed=0, top=2.6, side=1.8):
    """A pole: grain along it, lit on top."""
    return Paint(ramp, 2, noise=0, rim=0.5, decal=lambda c: (ramp, (top if c.face == "up" else side) + _grain(c, axis, seed) * 0.6))


def _rod(m, x, y, z, length=22, out=1, back=True, reel=True, holder=True):
    """A fishing rod in its holder: the butt at (x, y, z), leaning 22.5 degrees outboard (out = +1 right, -1 left) from
    upright, or aft (back) over the stern; a cork grip, a reel, a red tip."""
    def decal(c):
        u = c.p[1] - y
        if u < 4:
            return ("leather", 2.2 if math.floor(u) % 2 else 1.6)      # the cork grip
        if u > length - 2:
            return ("fabric_red", 3)                                   # the tip
        return ("rubber", 1.6 if c.face in ("east", "south") else 2.4)
    p = Paint("rubber", 2, noise=0, rim=0.3, decal=decal)
    rot = ("x", 22.5, (x, y, z)) if back else ("z", -22.5 * out, (x, y, z))
    m.box("rod", (x - 0.4, y, z - 0.4), (x + 0.4, y + length, z + 0.4), p, rotation=rot)
    if reel:
        m.box("reel", (x - 0.9, y + 4, z - 0.9), (x + 0.9, y + 5.5, z + 0.9), _flat("blade", 3.2, 2.2), rotation=rot)
    if holder:
        m.box("rod_holder", (x - 0.9, y - 2.5, z - 0.9), (x + 0.9, y + 1, z + 0.9), _flat("steel", 3.4, 2.2), rotation=rot)


def _lantern(m, x, z, y0, y1):
    """A paper lantern on a bamboo pole: lit (it glows), dark wooden caps, the ribs showing through the paper."""
    m.box("lantern_pole", (x - 0.5, y0, z - 0.5), (x + 0.5, y1 - 4.5, z + 0.5), _pole_paint("bamboo", "y", 5))

    def paper(c):
        u = c.p[1] - (y1 - 4)
        if c.face in ("up", "down"):
            return ("raw", (58, 40, 30, 255))
        if u < 0.5 or u > 3.5:
            return ("raw", (58, 40, 30, 255))                          # the caps
        along = c.p[0] if c.face in ("north", "south") else c.p[2]
        return ("washi_lit", 2 if math.floor(along * 2) % 3 == 0 else 3 if u > 1 else 2)
    m.box("lantern", (x - 1.5, y1 - 4, z - 1.5), (x + 1.5, y1, z + 1.5), Paint("washi_lit", 3, noise=0, light=False, glow=True, decal=paper))


def _bucket(m, x, z, y, ramp="signal_blue"):
    """A bait bucket: a plastic pail with a wire handle laid over, water and a few bait fish in it."""
    def decal(c):
        if c.face == "up":
            r = math.hypot(c.p[0] - x, c.p[2] - z)
            if r > 1.6:
                return (ramp, 3.2)
            return ("fish_silver", 2.4) if h3(*map(math.floor, (c.p[0] * 2, 3, c.p[2] * 2)), 5) < 0.3 else ("water", 1.6)
        u = c.p[1] - y
        return (ramp, 2.8 if u > 3 else 2.0 if math.floor(u) != 1 else 1.5)
    cyl(m, "bucket", (x, z), 5, y, y + 4, Paint(ramp, 2, noise=0, rim=0.5, decal=decal))


def _basket(m, x, z, y, d=8, h=5, fish=True):
    """A wicker creel (대바구니): woven bamboo strips, a thick rim, the catch showing in it."""
    def decal(c):
        px, py, pz = c.p
        u = py - y
        if c.face == "up":
            r = math.hypot(px - x, pz - z)
            if r > d / 2 - 1.2:
                return ("bamboo", 3.2)                                 # the rim
            if not fish:
                return ("bamboo", 0.8)
            k = (math.floor(px) + 2 * math.floor(pz)) % 5
            return ("fish_silver", 2.2) if k in (0, 1) else ("fish_blue", 1.4) if k == 2 else ("rubber", 0) if k == 3 and h3(math.floor(px), 1, math.floor(pz), 7) < 0.4 else ("fish_silver", 1.4)
        if u > h - 1:
            return ("bamboo", 2.8)
        a = math.atan2(pz - z, px - x)
        w = (math.floor(a * d * 0.9) + math.floor(u)) % 2
        return ("bamboo", 2.4 if w else 1.5)
    cyl(m, "basket", (x, z), d, y, y + h, Paint("bamboo", 2, noise=0, rim=0.5, decal=decal))


def _hung_fish(m, x, y, z, n=4, pitch=4, seed=0):
    """Fish hung by the tail to dry on a line along z from (x, y, z): thin plates cut to a fish's outline, split open and
    pale inside, a dark back."""
    def decal(c):
        if c.face not in ("east", "west"):
            return ("raw", CLEAR)
        k = math.floor((c.p[2] - z) / pitch)
        w = round((c.p[2] - z) - k * pitch - 1.5)                      # -1, 0, 1 across the fish, 2.. the gap
        d = math.floor(y - c.p[1])                                     # rows down from the line
        long = 6 + (h3(k, 1, seed, 9) > 0.5)                           # the row of its head
        if abs(w) > 1 or d > long:
            return ("raw", CLEAR)
        if d == 0:
            return ("straw", 2.6) if w == 0 else ("raw", CLEAR)        # the string
        if d == 1:
            return ("fish_blue", 1.4) if w != 0 else ("raw", CLEAR)    # the forked tail
        if d == 2:
            return ("fish_blue", 2.0) if w == 0 else ("raw", CLEAR)
        if d == long:
            return ("fish_blue", 1.2) if w == 0 else ("raw", CLEAR)    # the snout
        if d == long - 1:
            return ("rubber", 0) if w == (1 if c.face == "west" else -1) else ("fish_silver", 1.6)   # the eye
        return ("fish_silver", 3.2 if w == 0 else 2.2) if d % 2 else ("fish_silver", 2.6 if w == 0 else 1.8)
    m.box("hung_fish", (x - 0.2, y - 8, z), (x + 0.2, y, z + n * pitch), Paint("fish_silver", 2, noise=0, light=False, decal=decal))


# ================================================================================================
# 1세대 뗏목: 3.4 x 1.9 blocks, six logs. The water is at y 3 (water.draft 0.1875)
# ================================================================================================

RAFT_WL = 3
RAFT_LOGS = [(-12.5, -27, 25), (-7.5, -25, 27), (-2.5, -28, 26), (2.5, -26, 28), (7.5, -27, 25), (12.5, -25, 26)]
RAFT_DECK = 7.5              # the top of the plank deck


def _log_paint(k):
    """A pine log with its bark on: furrows along it, a knot or two, darker where it is wet; the sawn ends pale with rings."""
    def decal(c):
        x, y, z = c.p
        cx = RAFT_LOGS[k][0]
        if c.face in ("north", "south"):
            r = math.hypot(x - cx, y - 2.5)
            return ("hinoki", 1.2 if r > 1.9 else 3.0 if math.floor(r * 1.7) % 2 == 0 else 2.2)
        g = _grain(c, "z", 20 + k)
        if y < RAFT_WL + 0.5 and c.face != "up":
            return ("bark", 0.8 + g * 0.4)                             # wet
        if h3(math.floor(z / 2), k, 3, 21) < 0.06:
            return ("hinoki", 2.0)                                     # the bark knocked off
        return ("bark", (2.4 if c.face == "up" else 1.6) + g * 0.7)
    return Paint("bark", 2, noise=0, rim=0.5, decal=decal)


def raft():
    m = BModel("raft", shift=(8, 8, 8), scale=2.0, floor=None)
    m.group("logs")
    for k, (cx, z0, z1) in enumerate(RAFT_LOGS):
        cyl(m, "log", (cx, 2.5), 5, z0, z1, _log_paint(k), axis="z")
    # three bamboo cross poles over the logs, lashed to each with straw rope
    def cross(c):
        x = c.p[0]
        if abs(((x + 15) % 5) - 2.5) < 0.6:
            return ("straw", 2.8 if c.face == "up" else 1.8)           # a lashing over each log
        if math.floor(x + 16) % 8 == 0:
            return ("bamboo", 1.2)                                     # a node
        return ("bamboo", (3.0 if c.face == "up" else 2.0) + _grain(c, "x", 31) * 0.4)
    bp = Paint("bamboo", 2, noise=0, rim=0.5, decal=cross)
    for z in (-21, 0, 21):
        m.box("cross_pole", (-16, 4.75, z - 1), (16, 6.5, z + 1), bp)

    m.group("deck")

    def planks(c):
        x, z = c.p[0], c.p[2]
        if c.face != "up":
            return ("wood", 1.4 + _grain(c, "z", 33) * 0.5)
        board = math.floor((x + 11) / 3.67)
        if abs(((x + 11) % 3.67)) < 0.6 and x > -10.5:
            return ("wood", 0.8)                                       # the gap between boards
        if math.floor(z) in (-15, 15) and math.floor(x + 11) % 4 == 1:
            return ("iron", 1.6)                                       # nail heads
        wet = h3(math.floor(x / 2), 3, math.floor(z / 3), 34)
        return ("wood", 2.3 + 0.4 * (board % 2) + _grain(c, "z", 35 + board) * 0.6 - (0.6 if wet < 0.07 else 0))
    m.box("deck", (-11, 6.5, -17), (11, RAFT_DECK, 20), Paint("wood", 2, noise=0, rim=0.5, decal=planks))

    # a straw mat to sit on at the bow, the sculler's stump amidships
    def mat(c):
        x, z = c.p[0], c.p[2]
        if c.face != "up":
            return ("straw", 1.6)
        if abs(x) > 5.2 or z < -14.2 or z > -6.8:
            return ("leather", 1.8)                                    # the bound edge
        return ("straw", 2.8 if (math.floor(x * 2) + math.floor(z)) % 2 else 2.0)
    m.box("mat", (-6, RAFT_DECK, -15), (6, RAFT_DECK + 0.5, -6), Paint("straw", 2, noise=0, rim=0.3, decal=mat))

    def stump(c):
        x, y, z = c.p
        if c.face == "up":
            r = math.hypot(x, z - 7.5)
            return ("hinoki", 3.2 if math.floor(r * 1.4) % 2 == 0 else 2.4)
        return ("bark", 1.4 + _grain(c, "y", 37) * 0.8 + (0.6 if c.face in ("north", "west") else 0))
    cyl(m, "stump", (0, 7.5), 7, RAFT_DECK, RAFT_DECK + 4.5, Paint("bark", 2, noise=0, rim=0.5, decal=stump))

    m.group("gear")
    # the punting pole (삿대) along the right edge, on the cross poles
    m.box("punt_pole", (13.5, 6.5, -30), (14.5, 7.5, 24), _pole_paint("bamboo", "z", 41, 3.0, 2.0)(
        decal=lambda c: ("bamboo", 1.2) if math.floor(c.p[2] + 30) % 9 == 0 else ("iron", 2.0) if c.p[2] < -28 else ("bamboo", 3.0 if c.face == "up" else 2.0)))
    # a rack of fish drying along the left edge: two forked sticks, a pole across, fish hung by the tail
    for z in (-13, 13):
        m.box("rack_post", (-13.25, 5, z - 0.75), (-11.75, 22, z + 0.75), _pole_paint("wood_dark", "y", 43))
        m.box("rack_fork", (-13.25, 22, z - 1.5), (-11.75, 24, z - 0.75), _pole_paint("wood_dark", "y", 44))
        m.box("rack_fork", (-13.25, 22, z + 0.75), (-11.75, 24, z + 1.5), _pole_paint("wood_dark", "y", 45))
    m.box("rack_pole", (-13, 22.5, -15.5), (-12, 23.5, 15.5), _pole_paint("bamboo", "z", 46, 3.0, 2.0))
    _hung_fish(m, -12.5, 22.5, -11, n=3, pitch=4, seed=1)
    _hung_fish(m, -12.5, 22.5, 2, n=2, pitch=4.5, seed=2)
    # a paper lantern on a pole at the bow, a bamboo rod in a forked rest beside it, its line in the water
    _lantern(m, 9, -19, 5, 25)
    m.box("rod_rest", (4, RAFT_DECK, -17), (5, RAFT_DECK + 6, -16), _pole_paint("wood_dark", "y", 47))

    def bamboo_rod(c):
        z = c.p[2]
        return ("bamboo", 1.2) if math.floor(z + 40) % 7 == 0 else ("bamboo", 3.0 if c.face == "up" else 2.0)
    m.box("bamboo_rod", (4.1, 9, -39), (4.9, 9.8, -6), Paint("bamboo", 2, noise=0, rim=0.3, decal=bamboo_rod), rotation=("x", 22.5, (4.5, 9.4, -6)))
    m.box("line", (4.35, 2, -36.9), (4.65, 21.6, -36.6), Paint("feather", 3, noise=0, light=False,
                                                              decal=lambda c: ("raw", (236, 60, 48, 255)) if 4 <= c.p[1] < 6 else ("raw", (250, 250, 250, 255)) if c.p[1] < 7 else ("raw", (225, 228, 235, 160))))
    # the thole post of the sweep at the stern
    m.box("thole", (-1, 6.5, 22.2), (1, 10.5, 24), _pole_paint("wood_dark", "y", 48))
    m.display = _held_display(m)
    return m


def raft_scull():
    """The sweep (노): the pivot at (0, 0, 0), the loom running aft and 22.5 degrees down to a broad blade, a short handle
    forward and up. The plugin turns it about z as the raft moves."""
    m = BModel("raft_scull", shift=(8, 8, 8), floor=None)

    def loom(c):
        z = c.p[2]
        if z > 15:
            return ("wood", 2.6 if c.face in ("up", "west") else 1.6)  # the blade
        if abs(z) < 1:
            return ("straw", 2.6 if math.floor(c.p[0] * 2 + c.p[1] * 2) % 2 else 1.8)   # the lashing on the thole
        return ("wood_dark", (2.6 if c.face == "up" else 1.8) + _grain(c, "z", 51) * 0.5)
    p = Paint("wood_dark", 2, noise=0, rim=0.4, decal=loom)
    rot = ("x", 22.5, (0, 0, 0))
    m.box("loom", (-0.6, -0.6, -8), (0.6, 0.6, 15), p, rotation=rot)
    m.box("blade", (-0.4, -2, 15), (0.4, 2, 23), p, rotation=rot)
    m.box("grip", (-1, -1, -9.5), (1, 1, -8), _flat("wood", 2.8, 2.0), rotation=rot)
    m.display = presets.furniture(gui_scale=0.6)
    return m


def raft_catch(stage):
    """What the raft carries (deck floor middle at model (8, 8, 8); 20 x 9 px behind the stump)."""
    m = BModel(f"raft_catch_{stage}", shift=(8, 8, 8), floor=None)
    _basket(m, -5, 0, 0, d=8, h=5, fish=True)
    if stage >= 2:
        _basket(m, 4.5, 0.5, 0, d=7, h=4, fish=True)
    if stage >= 3:
        _basket(m, -5, 0, 5, d=7, h=4, fish=True)
        _rope_coil(m, 4.5, 0.5, y=4)
    if stage >= 4:
        _basket(m, 4.5, 0.5, 4, d=7, h=4, fish=True)

        def cover(c):
            x, z = c.p[0], c.p[2]
            if c.face == "up":
                return ("straw", 2.8 if (math.floor(x * 2) + math.floor(z)) % 2 else 2.0)
            return ("straw", 1.2 if math.floor(x + z) % 2 else 2.0)   # the frayed edge
        m.box("cover", (-9.5, 9, -4.5), (-0.5, 9.75, 4.5), Paint("straw", 2, noise=0, rim=0.3, decal=cover))
    m.display = presets.furniture(gui_scale=0.6)
    return m


# ================================================================================================
# 2세대 나룻배: 4.5 x 1.5 blocks. The water is at y 3 (water.draft 0.1875); the bottom boards are above it
# ================================================================================================

ROW_WL = 3
ROW_FLOOR = 4.5
# (z from, z to, half width at the gunwale, the gunwale's top): the sheer rises to the bow
ROW_SEGS = [(22, 34, 10.5, 11), (-12, 22, 12, 11), (-21, -12, 10.5, 12), (-27, -21, 8.5, 13.5), (-32, -27, 6.5, 15), (-36, -32, 4.5, 16.5)]
ROW_WALL = 2


def _row_hull_paint(top):
    """The planked side of the skiff: tar below the water and a hand above it, three strakes with their seams and a nail
    at every frame, the dark wale along the top."""
    def decal(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "down":
            return ("soot", 1.2)
        outward = (c.face in ("east", "west") and x * c.n[0] > 0) or (c.face in ("north", "south") and z * c.n[2] > 0)
        if c.face == "up":
            return ("wood_dark", 2.8 + _grain(c, "z", 61) * 0.5)       # the gunwale's top
        along = z if c.face in ("east", "west") else x
        if not outward:
            # inside: paler boards, the frames (ribs) every 8 px
            if c.face in ("east", "west") and math.floor(z + 40) % 8 == 0:
                return ("wood_dark", 1.8)
            return ("hinoki", 1.8 + (0.5 if fy % 3 == 1 else 0) + _grain(c, "z", 62) * 0.5)
        if fy >= top - 2:
            return ("wood_dark", 2.2 if fy >= top - 1 else 1.6)        # the wale
        if y < ROW_WL + 1.5:
            weed = h3(math.floor(along), fy, 3, 63)
            if fy == ROW_WL and weed < 0.2:
                return ("leaf_dark", 1.4)
            return ("soot", 1.0 + 0.3 * fy)                            # tarred
        if fy % 3 == 2:
            return ("wood", 1.2 if math.floor(along) % 7 else 0.8)     # a seam, a nail on each frame
        if h3(math.floor(along / 4), fy // 3, 1, 64) < 0.12:
            return ("wood", 1.8)                                       # a darker board
        return ("wood", 2.5 + _grain(c, "z" if c.face in ("east", "west") else "x", 65) * 0.6)
    return Paint("wood", 2, noise=0, rim=0.5, decal=decal)


def _row_floor(c):
    x, z = c.p[0], c.p[2]
    if c.face != "up":
        return ("soot", 1.2)
    if math.floor(x + 12) % 4 == 0:
        return ("hinoki", 0.8)                                         # between the bottom boards
    wet = h3(math.floor(x / 2), 5, math.floor(z / 3), 66)
    return ("hinoki", 2.0 + _grain(c, "z", 67) * 0.6 - (0.7 if wet < 0.1 else 0))


def rowboat():
    m = BModel("rowboat", shift=(8, 8, 8), scale=2.0, floor=None)
    m.group("hull")
    fp = Paint("hinoki", 2, noise=0, rim=0.3, decal=_row_floor)
    for (za, zb, hw, top) in ROW_SEGS:
        p = _row_hull_paint(top)
        # the bottom: a narrower plank under the chine, then the floor
        if hw > 5:
            m.box("bottom", (-(hw - 3.5), 0, max(za, -30)), (hw - 3.5, 1.5, min(zb, 32)), p)
        m.box("bottom", (-(hw - 1), 1.5, za), (hw - 1, ROW_FLOOR, zb), p, faces={"up": fp})
        for s in (1, -1):
            xa, xb = sorted((s * (hw - ROW_WALL), s * hw))
            m.box("side", (xa, ROW_FLOOR, za), (xb, top, zb), p)
    # the stem post, the transom, the keel
    kp = Paint("wood_dark", 2, noise=0, rim=0.5, decal=lambda c: ("soot", 1.0) if c.p[1] < ROW_WL + 1 else ("wood_dark", 1.8 + _grain(c, "y", 68)))
    m.box("stem", (-2.5, 1.5, -38), (2.5, 16.5, -36), _row_hull_paint(16.5))
    m.box("stem_post", (-1, 2, -39.5), (1, 20, -38), kp)
    m.box("transom", (-10.5, ROW_FLOOR, 34), (10.5, 11, 36), _row_hull_paint(11))
    m.box("keel", (-0.75, -0.75, -30), (0.75, 0, 33), kp)

    m.group("fittings")
    # the covered bow: a little deck with a mooring post, a coil of rope, a fish trap and the lantern
    def bow_deck(c):
        if c.face != "up":
            return ("wood_dark", 1.6)
        return ("wood", 2.4 + (0.4 if math.floor(c.p[0] + 9) % 3 == 0 else 0) + _grain(c, "z", 69) * 0.6)
    bd = Paint("wood", 2, noise=0, rim=0.5, decal=bow_deck)
    m.box("bow_deck", (-6.5, 12, -27), (6.5, 13.5, -21), bd)
    m.box("bow_deck", (-4.5, 13.5, -32), (4.5, 15, -27), bd)
    m.box("bow_deck", (-2.5, 15, -36), (2.5, 16.5, -32), bd)
    m.box("bitt", (-0.75, 15, -31), (0.75, 18, -29.5), _pole_paint("wood_dark", "y", 70))
    _lantern(m, 0, -34.5, 16.5, 29)

    def trap(c):
        # 통발: a net stretched over hoops, the funnel mouth dark at its end
        x, y, z = c.p
        if c.face in ("east", "west"):
            r = math.hypot(y - 16, z + 24)
            return ("rubber", 0.4) if r < 1 else ("leaf_dark", 2.0)
        return ("fox", 2.6) if math.floor(x + 6) % 3 == 0 else ("leaf_dark", 1.4 if (math.floor(x * 2) + math.floor(y + z)) % 2 else 2.4)
    cyl(m, "fish_trap", (16, -24), 5, -5.5, 1.5, Paint("leaf_dark", 2, noise=0, rim=0.3, decal=trap), axis="x")
    _rope_coil(m, 4, -24, y=13.5)

    # three thwarts: plank benches worn pale in the middle, each on a cleat along the side
    def board(seed):
        def decal(c):
            if c.face == "up":
                return ("wood", 2.7 + _grain(c, "x", seed) * 0.6 - (0.5 if abs(c.p[0]) < 3 else 0))
            return ("wood", 1.5 + _grain(c, "x", seed) * 0.5)
        return Paint("wood", 2, noise=0, rim=0.5, decal=decal)
    for k, (z, hw) in enumerate(((-17, 8.5), (-5, 10), (11, 10))):
        m.box("thwart", (-hw, 8, z - 2), (hw, 9.5, z + 2), board(71 + k))
    # the thole pins the oars turn on
    for s in (1, -1):
        xa, xb = sorted((s * 10.25, s * 12))
        m.box("thole", (xa, 11, -1), (xb, 12, 3), _flat("wood_dark", 2.6, 1.6))
        m.box("thole_pin", (s * 11.25 - 0.4, 12, 0.6), (s * 11.25 + 0.4, 13.5, 1.4), _flat("iron", 2.8, 1.8))
    # a bait bucket by the rower's feet, a rod in a holder on each quarter leaning out over the water
    _bucket(m, -5.5, 3, ROW_FLOOR)
    for s in (1, -1):
        _rod(m, s * 9.5, 11.5, 28, length=17, out=s, back=False)
    # the name board and a ring bolt on the transom
    def plate(c):
        if c.face != "south":
            return ("hinoki", 1.2)
        col, row = math.floor(c.p[0] + 5), math.floor(c.p[1])
        if col in (0, 9) or row in (6, 8):
            return ("hinoki", 1.0)
        return ("soot", 1.4) if col % 2 == 0 and 2 <= col <= 7 else ("hinoki", 2.8)
    m.box("name_board", (-5, 6, 36), (5, 9, 36.4), Paint("hinoki", 2, noise=0, rim=0.3, decal=plate))
    m.display = _held_display(m)
    return m


def rowboat_oar(side):
    """An oar on its thole pin (model (8, 8, 8)): the loom outboard and 22.5 degrees down to the blade, the grip inboard
    and up. side = +1 the right-hand oar (+x), -1 the left. Turned about x by the plugin (a spinner)."""
    m = BModel("rowboat_oar_r" if side > 0 else "rowboat_oar_l", shift=(8, 8, 8), floor=None)

    def loom(c):
        x = c.p[0] * side
        if x > 15:
            return ("wood", 2.8 if c.face in ("up", "north") else 1.8)   # the blade
        if abs(x) < 1.2:
            return ("leather", 2.0)                                      # the leather where it bears on the pin
        if x < -4:
            return ("wood", 2.6)                                         # the grip, worn pale
        return ("wood_dark", (2.6 if c.face == "up" else 1.8) + _grain(c, "x", 81) * 0.5)
    p = Paint("wood_dark", 2, noise=0, rim=0.4, decal=loom)
    rot = ("z", -22.5 * side, (0, 0, 0))

    def span(a, b):
        return tuple(sorted((a * side, b * side)))
    xa, xb = span(-6.5, 15)
    m.box("loom", (xa, -0.6, -0.6), (xb, 0.6, 0.6), p, rotation=rot)
    xa, xb = span(15, 23.5)
    m.box("blade", (xa, -0.4, -2), (xb, 0.4, 2), p, rotation=rot)
    m.display = presets.furniture(gui_scale=0.6)
    return m


def _basin(m, x, z, y=0, d=9, h=4, catch="fish"):
    """A red rubber basin (고무 다라이) of the fish market: a rolled rim, the catch in a little water."""
    def decal(c):
        px, py, pz = c.p
        if c.face == "up":
            r = math.hypot(px - x, pz - z)
            if r > d / 2 - 1.1:
                return ("terracotta", 3.2)
            if catch == "ice":
                return ("ice", 3 if h3(math.floor(px), 1, math.floor(pz), 91) > 0.3 else 2)
            k = (math.floor(px * 0.9 + pz * 0.5)) % 4
            if catch == "squid":
                return ("porcelain", 2.6) if k in (0, 1) else ("terracotta", 1.6) if k == 2 else ("water", 1.2)
            return ("fish_silver", 2.4) if k == 0 else ("fish_blue", 1.4) if k == 1 else ("fish_silver", 1.6) if k == 2 else ("water", 1.4)
        u = py - y
        return ("terracotta", 2.8 if u > h - 1 else 1.4 if u < 1 else 2.0)
    cyl(m, "basin", (x, z), d, y, y + h, Paint("terracotta", 2, noise=0, rim=0.5, decal=decal))


def rowboat_catch(stage):
    """What the skiff carries in its stern (floor middle at model (8, 8, 8); 18 x 14 px inside the sides)."""
    m = BModel(f"rowboat_catch_{stage}", shift=(8, 8, 8), floor=None)
    _basin(m, -4, 2, 0, d=9)
    if stage >= 2:
        _basket(m, 5, -2.5, 0, d=7, h=5)
    if stage >= 3:
        _basin(m, 5, 4.5, 0, d=7, catch="squid")
        _net_heap(m, -8.5, 0, -6.5, -2.5, h=3)
    if stage >= 4:
        _basin(m, -4, 2, 4, d=8, catch="ice")
        _basket(m, 5, -2.5, 5, d=6, h=4)
        _buoy(m, -8, -6, y=3)
    m.display = presets.furniture(gui_scale=0.6)
    return m


# ================================================================================================
# Hulls with a closed deck (연안 어선, 트롤 어선): stepped layers like the 통통배's
# ================================================================================================

def _solid_hull(m, segs, layers, hull_paint, deck_paint, deck):
    """The hull below the deck: each segment along z (from, to, half width at the deck) in the layers (y0, y1, how much
    narrower than the deck, how far aft the bow starts, how far forward the stern ends); the top layer's top is the deck."""
    dp = Paint("stone", 2, noise=0, decal=deck_paint)
    for (za, zb, hw) in segs:
        for (y0, y1, inset, bow, stern) in layers:
            a, b = max(za, bow), min(zb, stern)
            w = hw - inset
            if b <= a or w <= 0.5:
                continue
            m.box("hull", (-w, y0, a), (w, y1, b), hull_paint(hw), faces={"up": dp} if y1 == deck else None)


def _life_ring(m, x, y, z, face):
    """A life ring hung flat on a wall facing `face` ("east"/"west"/"south"): orange with four white bands."""
    def decal(c):
        if c.face != face:
            return ("raw", CLEAR)
        a = c.p[2] - z if face in ("east", "west") else c.p[0] - x
        b = c.p[1] - y
        r = math.hypot(a, b)
        if r > 3 or r < 1.2:
            return ("raw", CLEAR)
        return ("feather", 3) if abs(abs(a) - abs(b)) < 0.9 else ("fox", 3.0 if b > 0 else 2.2)
    p = Paint("fox", 2, noise=0, light=False, decal=decal)
    if face in ("east", "west"):
        m.box("life_ring", (x - 0.2, y - 3, z - 3), (x + 0.2, y + 3, z + 3), p)
    else:
        m.box("life_ring", (x - 3, y - 3, z - 0.2), (x + 3, y + 3, z + 0.2), p)


def _squid_lamp(m, x, y, z):
    """A squid lamp (집어등) hanging under its rail at (x, y, z): a big bare bulb, lit (it glows), its socket above."""
    def decal(c):
        u = y - c.p[1]
        if u < 0.8:
            return ("raw", (92, 98, 110, 255))                         # the socket
        if c.face == "down" or u > 2.6:
            return ("raw", (255, 236, 170, 255))
        return ("raw", (255, 250, 222, 255))
    m.box("squid_lamp", (x - 1.25, y - 3.5, z - 1.25), (x + 1.25, y, z + 1.25), Paint("lamp", 3, noise=0, light=False, glow=True, decal=decal))


# ================================================================================================
# 4세대 연안 어선: 7.2 x 2.9 blocks, drawn at its real size and shrunk by 2.5. The water is at y 7 (water.draft 0.4375)
# ================================================================================================

C_WL, C_DECK, C_FORE, C_RAIL = 7, 15, 21, 20
C_SEGS = [(42, 54, 21), (-14, 42, 23), (-22, -14, 21.5), (-26, -22, 21.5), (-36, -26, 19), (-44, -36, 15.5), (-50, -44, 11), (-56, -50, 6.5)]
C_LAYERS = [(0, 2.5, 10, -34, 46), (2.5, 6, 5.5, -42, 50), (6, 10, 2, -50, 53), (10, C_DECK, 0, -56, 54)]
C_HOUSE = {"z0": -20, "z1": 8, "hw": 12, "top": 42}
C_GREEN = "sign_green"


def _frp_paint(top, wl=C_WL, bottom="bottle_green", stripe=C_GREEN, stripe_y=None, cap="rubber"):
    """A moulded white hull: green antifouling to the waterline with a little weed, a dark boot line, glossy white above
    with a mould seam now and then, a green stripe under the rub rail, the registration marks on the bow."""
    def decal(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "down":
            return (bottom, 0.8)
        if c.face == "up":
            return None
        along = z if c.face in ("east", "west") else x
        if y < wl - 0.5:
            if fy == wl - 1 and h3(math.floor(along), fy, 3, 101) < 0.2:
                return ("leaf_dark", 1.4)
            return (bottom, 1.2 + 0.25 * fy)
        if y < wl + 0.5:
            return ("rubber", 1.6)                                     # the boot line
        if stripe_y is not None and stripe_y <= fy < stripe_y + 2:
            return (stripe, 2.6 if fy == stripe_y + 1 else 2.0)        # the stripe
        if cap and y >= top - 1:
            return (cap, 2.0 if cap == "rubber" else 3.0)              # the rub rail / the capping
        if c.face in ("east", "west") and z < -38 and wl + 2 <= fy <= wl + 4 and math.floor(-z - 38) % 3 != 2 and z > -50:
            return ("police_blue", 1.6)                                # the registration marks
        if math.floor(along + 60) % 19 == 0:
            return ("car_white", 2.6)                                  # a mould seam
        grime = h3(math.floor(along), 2, 1 if x > 0 else 2, 102) < 0.05 and fy < wl + 3
        return ("car_white", 3.3 - (0.5 if grime else 0) - (0.3 if fy < wl + 2 else 0))
    return Paint("car_white", 3, noise=0, rim=0.5, decal=decal)


def _nonskid(c):
    """A light grey non-skid deck: a fine speckle, a painted hatch outline where the live well is."""
    x, z = c.p[0], c.p[2]
    if c.face != "up":
        return None
    if 16 <= z <= 30 and abs(x) <= 7:
        edge = z < 17 or z > 29 or abs(x) > 6
        if edge:
            return ("steel", 1.6)                                      # the live well's hatch
        if abs(x) < 1.5 and 22 <= z <= 24:
            return ("steel", 3.6)                                      # its handle
        return ("steel", 2.6)
    k = h3(math.floor(x), 4, math.floor(z), 103)
    return ("stone", 3.4 + (0.4 if k > 0.8 else -0.4 if k < 0.12 else 0))


def _white_wall(skirt, y0, seed=0, ramp="car_white"):
    """A white cabin wall: a coloured skirting, panel joints, a little grime low down."""
    def decal(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "up":
            return (ramp, 3.4)
        if fy < y0 + 2:
            return (skirt, 2.0)
        along = z if c.face in ("east", "west") else x
        if math.floor(along + 40) % 11 == 5:
            return (ramp, 2.6)
        return (ramp, 3.3 - (0.4 if fy < y0 + 4 else 0) - (0.4 if h3(math.floor(along), fy, seed, 104) < 0.04 else 0))
    return Paint(ramp, 3, noise=0, rim=0.6, decal=decal)


def _glazed_house(m, z0, z1, hw, y0, top, win0, win1, wp, name="house", back_door=5, posts_front=(), posts_side=()):
    """A wheelhouse you can see into: walls under and over a window band, posts, see-through panes on the front and the
    sides, an open doorway in the back between two panels."""
    t = 1
    for (ya, yb) in ((y0, win0), (win1, top)):
        m.box(f"{name}_front", (-hw, ya, z0), (hw, yb, z0 + t), wp)
        for s in (1, -1):
            xa, xb = sorted((s * (hw - t), s * hw))
            m.box(f"{name}_side", (xa, ya, z0 + t), (xb, yb, z1 - t), wp)
    for x in (-hw, hw - t) + tuple(posts_front):
        m.box(f"{name}_post", (x, win0, z0), (x + t, win1, z0 + t), wp)
    for s in (1, -1):
        xa, xb = sorted((s * (hw - t), s * hw))
        for z in (z1 - t - 1,) + tuple(posts_side):
            m.box(f"{name}_post", (xa, win0, z), (xb, win1, z + 1), wp)
    gl = glass(sun=1, streak=0.35)
    m.box(f"{name}_glass", (-hw + t, win0, z0 + 0.25), (hw - t, win1, z0 + 0.75), gl, faces={"up": None, "down": None})
    for s in (1, -1):
        x = s * (hw - 0.5)
        m.box(f"{name}_glass", (x - 0.25, win0, z0 + t), (x + 0.25, win1, z1 - t - 1), gl, faces={"up": None, "down": None})
    for s in (1, -1):
        xa, xb = sorted((s * hw, s * back_door))
        m.box(f"{name}_back", (xa, y0, z1 - t), (xb, top, z1), wp)
    m.box(f"{name}_back", (-back_door, win1 - 2, z1 - t), (back_door, top, z1), wp)


def _helm(m, x, y, z):
    """A small wheel on the console's face at (x, y, z) (facing aft)."""
    def wheel(c):
        r = math.hypot(c.p[0] - x, c.p[1] - y)
        if c.face in ("north", "south"):
            if r > 2.2 or r < 0.7:
                return ("wood", 2.6)
            a = math.atan2(c.p[1] - y, c.p[0] - x)
            return ("wood", 2.2) if math.floor(a / (math.pi / 4) + 0.5) % 2 == 0 else ("raw", CLEAR)
        return ("wood", 1.8)
    m.box("helm", (x - 3, y - 3, z), (x + 3, y + 3, z + 0.5), Paint("wood", 2, noise=0, rim=0, decal=wheel))


def coastal_boat():
    m = BModel("coastal_boat", shift=(8, 8, 8), scale=2.5, floor=None)
    m.group("hull")
    _solid_hull(m, C_SEGS, C_LAYERS, lambda hw: _frp_paint(C_DECK, stripe_y=12), _nonskid, C_DECK)
    kp = Paint("bottle_green", 1, noise=0, rim=0.4)
    m.box("keel", (-1, -0.75, -33), (1, 0.5, 45.5), kp)
    # the raised foredeck, the flaring bow above it, the bulwarks round the working deck
    tops = {-26: C_FORE, -36: C_FORE, -44: C_FORE, -50: C_FORE + 1.5, -56: C_FORE + 3}
    dp = Paint("stone", 2, noise=0, decal=_nonskid)
    for (za, zb, hw) in C_SEGS:
        if za < -22:
            top = tops[za]
            m.box("foredeck", (-hw, C_DECK, za), (hw, top, zb), _frp_paint(top, wl=-99, cap=None), faces={"up": dp})
            for s in (1, -1):
                xa, xb = sorted((s * (hw - 1), s * hw))
                m.box("toe_rail", (xa, top, za), (xb, top + 1.5, zb), _frp_paint(top + 1.5, wl=-99, cap=C_GREEN))
        else:
            bp = _frp_paint(C_RAIL, wl=-99, cap="steel")
            for s in (1, -1):
                xa, xb = sorted((s * (hw - 1.5), s * hw))
                m.box("bulwark", (xa, C_DECK, za), (xb, C_RAIL, zb), bp)
    m.box("transom", (-19.5, C_DECK, 52.5), (19.5, C_RAIL, 54), _frp_paint(C_RAIL, wl=-99, cap="steel"))
    # the prow: the stem carried up and forward over the water
    m.box("prow", (-3, 10, -59), (3, C_FORE + 6, -56), _frp_paint(C_FORE + 6, stripe_y=12, cap=C_GREEN))

    m.group("house")
    H = C_HOUSE
    z0, z1, hw, top = H["z0"], H["z1"], H["hw"], H["top"]
    wp = _white_wall(C_GREEN, C_DECK, 1)
    _glazed_house(m, z0, z1, hw, C_DECK, top, 29, 39, wp, posts_front=(-0.5,), posts_side=(-7,))

    def roof(c):
        x, z = c.p[0], c.p[2]
        if c.face == "up":
            return ("car_white", 2.6) if math.floor(z + 30) % 6 == 0 else ("car_white", 3.5)
        if c.face == "down":
            return ("car_white", 1.6)
        return (C_GREEN, 2.4)
    m.box("roof", (-hw - 2, top, z0 - 3), (hw + 2, top + 2, z1 + 3), Paint("car_white", 3, noise=0, rim=0.7, decal=roof))
    # inside: the console and the wheel, a bench for the skipper and the mate
    m.box("console", (-hw + 1, C_DECK, z0 + 1), (hw - 1, 25, z0 + 4), _flat("tint", 2.4, 1.6))
    _helm(m, 5.5, 27, z0 + 4)

    def cushion(ramp, topy):
        def decal(c):
            if c.face == "up":
                return (ramp, 2.2 if math.floor(c.p[0] + 30) % 6 == 0 else 3.0)
            return ("car_white", 2.6) if c.p[1] < topy - 1.2 else (ramp, 2.2)
        return Paint("car_white", 3, noise=0, rim=0.5, decal=decal)
    m.box("bench", (-hw + 1, C_DECK, -4), (hw - 1, 19.5, 2), cushion("denim", 19.5))
    # the awning aft of the cabin on two posts
    def awning(c):
        x, z = c.p[0], c.p[2]
        if c.face == "up":
            return ("water", 2.2 if math.floor(x + 20) % 5 == 0 else 3.2)
        return ("water", 1.6 if c.face == "down" else 2.4)
    m.box("awning", (-hw - 1, top + 0.25, z1 + 3), (hw + 1, top + 1.25, 27), Paint("water", 3, noise=0, rim=0.6, decal=awning))
    steel = _flat("steel", 3.6, 2.4)
    for s in (1, -1):
        xa, xb = sorted((s * (hw - 0.5), s * (hw + 0.5)))
        m.box("awning_post", (xa, C_DECK, 25.5), (xb, top + 0.25, 26.5), steel)

    m.group("rig")
    # the radar arch on the roof: the dome, the running lights, the exhaust pipe
    m.box("arch", (-13, top + 2, 0), (-12, top + 9, 1.5), steel)
    m.box("arch", (12, top + 2, 0), (13, top + 9, 1.5), steel)
    m.box("arch", (-14, top + 9, 0), (14, top + 10.5, 1.5), steel)
    cyl(m, "radar", (0, 0.75), 7, top + 10.5, top + 13, Paint("car_white", 3, noise=0, rim=0.6,
                                                             decal=lambda c: ("car_white", 3.6 if c.face == "up" else 2.4 if c.p[1] > top + 11.2 else 1.6)))
    for s, col in ((1, (60, 230, 120, 255)), (-1, (240, 60, 50, 255))):
        xa, xb = sorted((s * 9, s * 10.5))
        m.box("running_light", (xa, top + 10.5, 0), (xb, top + 12, 1.5), Paint("lamp", 3, noise=0, light=False, glow=True, decal=lambda c, col=col: ("raw", col)))
    m.box("exhaust", (8, top + 2, 6.5), (10, top + 7, 8.5), Paint("rubber", 1, noise=0, rim=0.5,
                                                                 decal=lambda c: ("soot", 0.6) if c.p[1] > top + 5.5 else ("rubber", 1.6)))
    # the squid lamps: a rail down each side from the arch, forward over the roof and aft over the awning to a post on
    # the deck, five bulbs under each
    for s in (1, -1):
        x = s * 13.5
        m.box("lamp_rail", (x - 0.5, top + 8, -19), (x + 0.5, top + 9, 38), steel)
        m.box("lamp_post", (x - 0.5, C_DECK, 36), (x + 0.5, top + 8, 37), steel)
        for z in (-14, -5, 10, 19, 28):
            _squid_lamp(m, x, top + 8, z)

    m.group("deck")
    # the foredeck: a locker to sit on against the cabin, the windlass and the anchor on the stem, the pulpit rail
    m.box("fore_locker", (-10, C_FORE, -31), (10, 25.5, -24), cushion("fox", 25.5))
    m.box("windlass", (-2.5, C_FORE, -46), (2.5, C_FORE + 3, -42), Paint("iron", 2, noise=0, rim=0.5,
                                                                    decal=lambda c: ("terracotta", 1.8) if h3(*map(math.floor, c.p), 105) < 0.15 else ("iron", 2.6 if c.face == "up" else 1.7)))
    # the pulpit: a stainless rail each side of the foredeck and round the bow, on posts
    for s in (1, -1):
        m.box("pulpit_post", (s * 18 - 0.4, C_FORE + 1.5, -27), (s * 18 + 0.4, C_FORE + 7.2, -26.2), steel)
        m.box("pulpit_post", (s * 18 - 0.4, C_FORE + 1.5, -36.8), (s * 18 + 0.4, C_FORE + 7.2, -36), steel)
        xa, xb = sorted((s * 17.6, s * 18.4))
        m.box("pulpit_rail", (xa, C_FORE + 7.2, -36.8), (xb, C_FORE + 8, -26.2), steel)
        m.box("pulpit_post", (s * 10 - 0.4, C_FORE + 3, -45.8), (s * 10 + 0.4, C_FORE + 7.2, -45), steel)
        xa, xb = sorted((s * 9.6, s * 10.4))
        m.box("pulpit_rail", (xa, C_FORE + 7.2, -53), (xb, C_FORE + 8, -45), steel)
    m.box("pulpit_rail", (-10.4, C_FORE + 7.2, -53.8), (10.4, C_FORE + 8, -53), steel)
    # the working deck: a locker to sit on along each rail, rods in the gunwale at the quarters and over the stern
    for s in (1, -1):
        xa, xb = sorted((s * 12.5, s * 21.5))
        m.box("rail_locker", (xa, C_DECK, 10), (xb, 20.5, 26), cushion("denim", 20.5))
        _rod(m, s * 22.2, C_RAIL, 40, length=19, out=s, back=False, reel=False, holder=False)
        _rod(m, s * 15, C_RAIL, 53.2, length=19, back=True, holder=False)
        _life_ring(m, s * (hw + 0.25), 24, 2, "east" if s > 0 else "west")
    # the rudder, the shaft, the name board on the transom
    rp = Paint("bottle_green", 1, noise=0, rim=0.4)
    m.box("rudder", (-0.75, 0.5, 54), (0.75, 9, 58), rp)
    m.box("shaft", (-0.5, 3, 50), (0.5, 4, 51), _flat("blade", 2.6, 2.0))

    def plate(c):
        if c.face != "south":
            return ("car_white", 2)
        col, row = math.floor(c.p[0] + 8), math.floor(c.p[1])
        return ("police_blue", 1.6) if row in (11, 12) and 2 <= col <= 13 and col % 3 != 1 else ("car_white", 3.4)
    m.box("name_board", (-8, 10, 54), (8, 14, 54.4), Paint("car_white", 3, noise=0, rim=0.3, decal=plate))
    m.display = _held_display(m)
    return m


def coastal_catch(stage):
    """The catch on the coastal boat's aft deck (deck middle at model (8, 8, 8); 36 x 20 px inside the bulwarks)."""
    m = BModel(f"coastal_catch_{stage}", shift=(8, 8, 8), floor=None)
    _fish_crate(m, -16, -3)
    _fish_crate(m, -5, -3, catch="bream", ramp="fabric_red")
    if stage >= 2:
        _fish_crate(m, 6, -3)
        _ice_box(m, -15, -9.5, w=10, d=6)
        _fish_crate(m, -16, -3, y=4)
    if stage >= 3:
        _fish_crate(m, -5, -3, y=4)
        _fish_crate(m, 6, -3, y=4, ramp="fabric_red", catch="bream")
        _net_heap(m, -4, 8, -9.5, -3.5, h=4)
        _buoy(m, 10, -9, y=0)
    if stage >= 4:
        _fish_crate(m, -16, -3, y=8, catch="ice")
        _fish_crate(m, -5, -3, y=8)
        _tarp(m, 5.5, 16.5, 8, -3.5, 4.5, 4)
        _ice_box(m, -15, -9.5, y=5, w=10, d=6)
        _buoy(m, -3, -9, y=4)
    m.display = presets.furniture(gui_scale=0.4)
    return m


# ================================================================================================
# 5세대 트롤 어선: 9.6 x 3.75 blocks, drawn at its real size and shrunk by 3.5. The water is at y 10 (water.draft 0.625)
# ================================================================================================

T_WL, T_DECK, T_FOC, T_RAIL = 10, 20, 32, 27
T_SEGS = [(62, 76, 27), (-20, 62, 30), (-40, -20, 28.5), (-50, -40, 26), (-58, -50, 22), (-66, -58, 17), (-72, -66, 11), (-77, -72, 5.5)]
T_LAYERS = [(0, 3, 13, -48, 62), (3, 8, 7, -59, 68), (8, 14, 3, -68, 73), (14, T_DECK, 0, -77, 76)]
T_NAVY, T_RED, T_ORANGE = "police_blue", "fabric_red", "fox"
T_BOAT, T_BRIDGE = 36, 62    # the deckhouse's top (the bridge's floor), the bridge's top under its roof


def _steel_paint(top, wl=T_WL, inside="car_white", cap=True, marks=False):
    """A welded steel side: red antifouling to the waterline, a white boot line, navy plates with their weld seams,
    rust weeping from the scuppers, draught marks and a white name on the bow; white inside the bulwark."""
    def decal(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "down":
            return (T_RED, 0.6)
        if c.face == "up":
            return ("rubber", 2.2) if cap else None
        outward = (c.face in ("east", "west") and x * c.n[0] > 0) or (c.face in ("north", "south") and z * c.n[2] > 0)
        along = z if c.face in ("east", "west") else x
        if not outward:
            scuff = h3(math.floor(along), fy, 3, 111)
            return (inside, 2.8 - (0.8 if scuff < 0.1 else 0) - (0.5 if fy < T_DECK + 2 else 0))
        if y < wl - 0.5:
            if fy == wl - 1 and h3(math.floor(along), fy, 3, 112) < 0.2:
                return ("leaf_dark", 1.2)
            return (T_RED, 0.9 + 0.22 * fy)
        if y < wl + 0.5:
            return ("car_white", 2.8)                                  # the boot line
        if cap and y >= top - 1:
            return ("car_white", 3.0)                                  # the white line along the rail
        col = math.floor(along)
        if c.face in ("east", "west"):
            if marks and -74 < z < -66 and wl + 1 <= fy < wl + 9 and fy % 2 == 0 and math.floor(z) % 3 == 0:
                return ("car_white", 3.0)                              # draught marks
            if marks and -62 < z < -44 and T_FOC - 5 <= fy <= T_FOC - 3 and math.floor(-z) % 3 != 0:
                return ("car_white", 3.2)                              # the name
            if h3(col, 3, 1 if x > 0 else 2, 113) < 0.035 and fy < T_DECK:
                run = 3 + math.floor(h3(col, 5, 1, 113) * 6)
                if T_DECK - run <= fy:
                    return ("terracotta", 1.2 + 0.25 * (T_DECK - fy))  # rust from a scupper
        if col % 23 == 0 or fy == T_DECK - 5:
            return (T_NAVY, 1.0)                                       # weld seams
        return (T_NAVY, 1.7 + (0.3 if h3(math.floor(along / 4), fy // 2, 2, 114) > 0.7 else 0) - (0.3 if fy < wl + 2 else 0))
    return Paint(T_NAVY, 2, noise=0, rim=0.5, decal=decal)


def _green_deck(c):
    """Deck paint: ship's green on the steel, pale planks on the working deck aft, a few scuffs."""
    x, z = c.p[0], c.p[2]
    if c.face != "up":
        return None
    if z > 8 and abs(x) < 20.5:
        if math.floor(x + 21) % 3 == 0:
            return ("hinoki", 1.0)
        wet = h3(math.floor(x / 3), 6, math.floor(z / 4), 115)
        return ("hinoki", 2.4 + (0.4 if math.floor((x + 21) / 3) % 2 else 0) - (0.7 if wet < 0.08 else 0))
    k = h3(math.floor(x), 7, math.floor(z), 116)
    return ("sign_green", 2.2 + (0.4 if k > 0.85 else -0.4 if k < 0.08 else 0))


def _house_paint(y0, ports=None, doors=(), seed=0):
    """A white steel deckhouse: a navy skirting, weld lines, rust under the fittings; ports: (y, pitch) a row of
    portholes with brass rims; doors: (face, centre) watertight doors drawn on a wall."""
    def decal(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "up":
            return None
        along = z if c.face in ("east", "west") else x
        if fy < y0 + 2:
            return (T_NAVY, 2.0)
        for (face, ctr) in doors:
            if c.face == face and abs(along - ctr) < 4 and y0 + 2 <= y < y0 + 15:
                edge = abs(along - ctr) > 3 or y > y0 + 14 or y < y0 + 3
                if edge:
                    return ("steel", 1.6)
                if math.hypot(along - ctr, y - (y0 + 10.5)) < 1.5:
                    return ("glass", 1.6)                              # the door's port
                if abs(along - ctr - 1.5) < 0.6 and abs(y - (y0 + 7)) < 1:
                    return ("brass", 2.8)                              # its handle
                return ("car_white", 2.6)
        if ports and c.face in ("east", "west"):
            py, pitch = ports
            k = round((z + 40) / pitch)
            d = math.hypot(z + 40 - k * pitch, y - py)
            if d < 1.6:
                return ("glass", 1.4 if y < py else 2.4)
            if d < 2.5:
                return ("brass", 2.4)
            if abs(z + 40 - k * pitch) < 0.7 and py - 7 < y < py - 2.5 and h3(k, 1, seed, 117) < 0.5:
                return ("terracotta", 2.2)                             # a rust streak under the port
        if math.floor(along + 50) % 13 == 0:
            return ("car_white", 2.5)
        return ("car_white", 3.3 - (0.4 if fy < y0 + 4 else 0) - (0.5 if h3(math.floor(along), fy, seed, 118) < 0.03 else 0))
    return Paint("car_white", 3, noise=0, rim=0.6, decal=decal)


def trawler():
    m = BModel("trawler", shift=(8, 8, 8), scale=3.5, floor=None)
    m.group("hull")
    _solid_hull(m, T_SEGS, T_LAYERS, lambda hw: _steel_paint(T_DECK, cap=False, marks=True), _green_deck, T_DECK)
    m.box("keel", (-1.25, -1, -46), (1.25, 0.5, 61.5), Paint(T_RED, 1, noise=0, rim=0.4))
    gd = Paint("sign_green", 2, noise=0, decal=_green_deck)
    tops = {-50: T_FOC, -58: T_FOC, -66: T_FOC + 1.5, -72: T_FOC + 3, -77: T_FOC + 4.5}
    for (za, zb, hw) in T_SEGS:
        if za < -40:
            # the forecastle: the side carried up to the fore deck, a solid bulwark round it rising to the stem
            top = tops[za]
            m.box("forecastle", (-hw, T_DECK, za), (hw, T_FOC, zb), _steel_paint(T_FOC, wl=-99, cap=False, marks=True), faces={"up": gd})
            for s in (1, -1):
                xa, xb = sorted((s * (hw - 1.5), s * hw))
                m.box("fore_bulwark", (xa, T_FOC, za), (xb, top + 3, zb), _steel_paint(top + 3, wl=-99))
        else:
            for s in (1, -1):
                xa, xb = sorted((s * (hw - 2), s * hw))
                m.box("bulwark", (xa, T_DECK, za), (xb, T_RAIL, zb), _steel_paint(T_RAIL, wl=-99))
    m.box("stem", (-2.5, 14, -80), (2.5, T_FOC + 9, -77), _steel_paint(T_FOC + 9, wl=-99))
    # the transom either side of the stern ramp, the ramp's roller
    for s in (1, -1):
        xa, xb = sorted((s * 9, s * 25))
        m.box("transom", (xa, T_DECK, 74), (xb, T_RAIL, 76), _steel_paint(T_RAIL, wl=-99))
    cyl(m, "ramp_roller", (T_DECK + 0.5, 76), 3, -9, 9, Paint("steel", 2, noise=0, rim=0.4,
                                                              decal=lambda c: ("steel", 3.2 if math.floor(c.p[0]) % 4 else 1.8)), axis="x")

    m.group("house")
    # the deckhouse behind the forecastle: portholes down its sides, two doors onto the working deck
    B0, BT = T_BOAT, T_BRIDGE           # the boat deck (the bridge's floor) and the bridge's top
    m.box("deckhouse", (-21, T_DECK, -38), (21, B0, 6), _house_paint(T_DECK, ports=(29, 10), doors=(("south", -11), ("south", 11)), seed=1),
          faces={"up": gd})
    # the bridge on it: glass all round, an open door aft, a roof with a visor (tall enough for the two who sit in it)
    wp = _house_paint(B0, seed=2)
    _glazed_house(m, -34, -10, 17, B0, BT, B0 + 9, B0 + 20, wp, name="bridge", back_door=5, posts_front=(-6, 5), posts_side=(-23,))

    def roof(c):
        if c.face == "up":
            return ("car_white", 2.6) if math.floor(c.p[2] + 40) % 7 == 0 else ("car_white", 3.5)
        return ("car_white", 1.6) if c.face == "down" else (T_NAVY, 2.0)
    m.box("bridge_roof", (-19, BT, -38), (19, BT + 2, -7), Paint("car_white", 3, noise=0, rim=0.7, decal=roof))
    m.box("console", (-15, B0, -33), (15, B0 + 7, -29), _flat("tint", 2.4, 1.6))
    _helm(m, 5.5, B0 + 10, -29)

    def seat(c):
        if c.face == "up":
            return ("denim", 2.2 if math.floor(c.p[0] + 30) % 6 == 0 else 3.0)
        return ("tint", 1.8) if c.p[1] < B0 + 2.3 else ("denim", 2.2)
    m.box("bridge_bench", (-12, B0, -19), (12, B0 + 3.5, -14), Paint("denim", 2, noise=0, rim=0.5, decal=seat))
    for s in (1, -1):
        _life_ring(m, s * 17.25, B0 + 4.5, -16, "east" if s > 0 else "west")
    # the funnel: navy with the company's red band between white lines, sooted round the top
    def funnel(c):
        y = c.p[1]
        if c.face == "up":
            return ("soot", 0.4)
        if y > BT - 2:
            return ("soot", 0.8)
        if BT - 11 <= y < BT - 6:
            return (T_RED, 2.6)
        if BT - 12 <= y < BT - 11 or BT - 6 <= y < BT - 5:
            return ("car_white", 3.2)
        return (T_NAVY, 2.0 if c.face in ("north", "west") else 1.4)
    m.box("funnel", (-5.5, B0, -5), (5.5, BT, 4), Paint(T_NAVY, 2, noise=0, rim=0.6, decal=funnel))
    m.box("funnel_pipe", (-2, BT, -2), (0, BT + 2.5, 0), _flat("soot", 0.8, 0.6))
    m.box("funnel_pipe", (1, BT, 0), (3, BT + 1.5, 2), _flat("soot", 0.8, 0.6))

    m.group("rig")
    steel = _flat("steel", 3.6, 2.4)
    white = _flat("car_white", 3.5, 2.6)
    R = BT + 2                          # the bridge roof's top
    # the mast on the bridge roof: a radar scanner, a cross tree with the running lights, the masthead lamp
    m.box("mast", (-1.5, R, -23), (1.5, R + 16, -20), white)
    m.box("crosstree", (-9, R + 10, -22), (9, R + 11, -21), white)
    m.box("radar_post", (-0.75, R, -29), (0.75, R + 4, -27.5), steel)
    m.box("radar", (-6, R + 4, -29.25), (6, R + 5.5, -27.25), Paint("car_white", 3, noise=0, rim=0.6,
                                                                 decal=lambda c: (T_NAVY, 2.2) if c.face == "north" else ("car_white", 3.4 if c.face == "up" else 2.6)))
    for s, col in ((1, (60, 230, 120, 255)), (-1, (240, 60, 50, 255))):
        xa, xb = sorted((s * 8, s * 9.5))
        m.box("running_light", (xa, R + 11, -22.25), (xb, R + 12.5, -20.75), Paint("lamp", 3, noise=0, light=False, glow=True, decal=lambda c, col=col: ("raw", col)))
    m.box("mast_lamp", (-1, R + 16, -22.5), (1, R + 18, -20.5), Paint("lamp", 3, noise=0, light=False, glow=True, decal=lambda c: ("raw", (255, 246, 214, 255))))
    m.box("aerial", (-13.4, R, -12.4), (-12.6, R + 12, -11.6), _flat("rubber", 1.8, 1.2))
    m.box("searchlight", (9, R, -37), (13, R + 4, -33), Paint("blade", 2, noise=0, rim=0.6,
                                                             decal=lambda c: ("lamp", 4.6) if c.face == "north" and math.hypot(c.p[0] - 11, c.p[1] - R - 2) < 1.6 else ("blade", 2.6 if c.face == "up" else 1.8)))
    cyl(m, "life_raft", (R + 1.75, -12), 3.5, -16, -8, Paint("car_white", 3, noise=0, rim=0.5,
                                                            decal=lambda c: (T_ORANGE, 2.6) if abs(c.p[0] + 12) < 1 else ("car_white", 3.2 if c.face == "up" else 2.4)), axis="x")
    # the stern gantry over the ramp: two posts and a beam, the blocks, two floodlights on the deck, the trawl doors
    # hung outboard of it
    def gantry(c):
        x, y, z = c.p
        if h3(math.floor(x), math.floor(y), math.floor(z), 119) < 0.05:
            return ("terracotta", 1.8)
        return (T_ORANGE, 2.9 if c.face in ("up", "north") else 2.0 if c.face != "down" else 1.4)
    gp = Paint(T_ORANGE, 2, noise=0, rim=0.6, decal=gantry)
    for s in (1, -1):
        xa, xb = sorted((s * 21.5, s * 25))
        m.box("gantry_post", (xa, T_DECK, 67), (xb, 57, 70.5), gp)
        xa, xb = sorted((s * 14, s * 16.5))
        m.box("gantry_block", (xa, 53.5, 67.75), (xb, 57, 69.75), _flat("iron", 2.6, 1.7))
        xa, xb = sorted((s * 8, s * 11))
        m.box("floodlight", (xa, 54.5, 65.5), (xb, 57, 67), Paint("lamp", 3, noise=0, light=False, glow=True,
                                                                 decal=lambda c: ("raw", (255, 246, 214, 255)) if c.face == "north" else ("raw", (70, 76, 88, 255))))

        def door(c, s=s):
            y, z = c.p[1], c.p[2]
            if c.face in ("east", "west"):
                if abs(y - 31) < 0.6 or z < 59 or z > 69:
                    return ("iron", 2.8)
                return ("hazard", 2.4) if (math.floor(z + y) // 2) % 2 else ("rubber", 1.0)
            return ("iron", 1.8)
        xa, xb = sorted((s * 27, s * 28.5))
        m.box("trawl_door", (xa, 24, 58), (xb, 38, 70), Paint("iron", 2, noise=0, rim=0.5, decal=door))
    m.box("gantry_beam", (-25, 57, 67), (25, 60.5, 70.5), gp)

    m.group("deck")
    # the forecastle: the windlass, two bitts, a locker to sit on, the fore mast with its lamp
    m.box("windlass", (-7, T_FOC, -50), (7, T_FOC + 4.5, -45), Paint("iron", 2, noise=0, rim=0.5,
                                                                    decal=lambda c: ("terracotta", 1.8) if h3(*map(math.floor, c.p), 120) < 0.12 else ("signal_red", 2.2) if abs(abs(c.p[0]) - 4.5) < 1.2 and c.face != "up" else ("iron", 2.6 if c.face == "up" else 1.7)))

    def locker(topy, ramp=T_ORANGE):
        def decal(c):
            if c.face == "up":
                return (ramp, 2.2 if math.floor(c.p[2] + 90) % 8 == 0 else 3.0)
            return ("car_white", 2.7) if c.p[1] < topy - 1.2 else (ramp, 2.2)
        return Paint("car_white", 3, noise=0, rim=0.5, decal=decal)
    m.box("fore_locker", (-11, T_FOC, -58.5), (11, T_FOC + 5, -52.5), locker(T_FOC + 5))
    m.box("fore_mast", (-1, T_FOC, -68), (1, T_FOC + 16, -66), white)
    m.box("fore_lamp", (-1.25, T_FOC + 16, -68.25), (1.25, T_FOC + 18, -65.75), Paint("lamp", 3, noise=0, light=False, glow=True, decal=lambda c: ("raw", (255, 246, 214, 255))))
    # the working deck: the trawl winch behind the house, the fish hatch, a locker to sit on along each rail
    def drum(c):
        x, y, z = c.p
        if c.face in ("east", "west"):
            return ("signal_red", 2.4) if math.hypot(y - 26, z - 13) > 2.5 else ("iron", 2.0)
        return ("iron", 2.4 if (math.floor(x * 1.5) + math.floor(math.atan2(y - 26, z - 13) * 3)) % 2 else 1.5)   # the warp wound on
    dp = Paint("iron", 2, noise=0, rim=0.3, decal=drum)
    for s in (1, -1):
        xa, xb = sorted((s * 4, s * 14))
        cyl(m, "winch_drum", (26, 13), 9, xa, xb, dp, axis="x")
        xa, xb = sorted((s * 14, s * 15.5))
        m.box("winch_cheek", (xa, T_DECK, 9), (xb, 30, 17), _flat("signal_green", 2.6, 1.8))
    m.box("winch_gear", (-4, T_DECK, 9.5), (4, 29, 16.5), _flat("signal_green", 2.6, 1.8))

    def hatch(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) > 7 or z < 23 or z > 37:
                return ("steel", 2.0)
            return ("fox", 2.0) if math.floor(z + 1) % 5 == 0 else ("fox", 3.0)         # the hatch boards
        return ("steel", 2.2 if y > T_DECK + 3 else 1.6)
    m.box("fish_hatch", (-8, T_DECK, 22), (8, T_DECK + 4, 38), Paint("steel", 2, noise=0, rim=0.5, decal=hatch))
    for s in (1, -1):
        xa, xb = sorted((s * 21, s * 28))
        m.box("rail_locker", (xa, T_DECK, 18), (xb, 25.5, 46), locker(25.5, "denim"))
        _rod(m, s * 29, T_RAIL, 52, length=20, out=s, back=False, reel=False, holder=False)
    # the rudder and the shaft
    m.box("rudder", (-1, 1, 76), (1, 9, 82), Paint(T_RED, 1, noise=0, rim=0.4))
    m.box("shaft", (-0.6, 4, 68), (0.6, 5, 69.6), _flat("blade", 2.6, 2.0))
    m.display = _held_display(m)
    return m


def trawler_propeller():
    """The trawler's screw: four broad bronze blades on a hub (diameter 7 px), axis along z."""
    m = BModel("trawler_propeller", shift=(8, 8, 8), floor=None)

    def blade(c):
        r = max(abs(c.p[0]), abs(c.p[1]))
        return ("brass", 3.0 - 0.25 * r + (0.6 if c.face == "north" else 0))
    bp = Paint("brass", 2, noise=0, rim=0.6, decal=blade)
    m.box("blade", (-1, -3.5, -0.4), (1, 3.5, 0.4), bp)
    m.box("blade", (-3.5, -1, -0.4), (3.5, 1, 0.4), bp)
    m.box("hub", (-1.25, -1.25, -1.5), (1.25, 1.25, 2), Paint("brass", 2, noise=0, rim=0.6))
    m.display = presets.furniture(gui_scale=0.8)
    return m


def trawler_catch(stage):
    """The catch on the trawler's working deck (deck middle at model (8, 8, 8); 40 x 22 px ahead of the ramp)."""
    m = BModel(f"trawler_catch_{stage}", shift=(8, 8, 8), floor=None)

    def pallet(x, z, w=22, d=9):
        m.box("pallet", (x, 0, z), (x + w, 1.5, z + d), Paint("wood", 2, noise=0, rim=0.4,
                                                              decal=lambda c: ("wood", 0.8) if c.face != "up" and math.floor(c.p[0]) % 4 == 0 else ("wood", 2.4 if c.face == "up" else 1.6)))
    pallet(-22, 1)
    _fish_crate(m, -21.5, 1.5, y=1.5)
    _fish_crate(m, -10.5, 1.5, y=1.5, catch="bream", ramp="fabric_red")
    if stage >= 2:
        pallet(1, 1)
        _fish_crate(m, 1.5, 1.5, y=1.5)
        _fish_crate(m, 12.5, 1.5, y=1.5, catch="ice")
        _fish_crate(m, -21.5, 1.5, y=5.5)
        _fish_crate(m, -10.5, 1.5, y=5.5)
    if stage >= 3:
        pallet(-22, -10)
        _fish_crate(m, -21.5, -9.5, y=1.5, ramp="fabric_red", catch="bream")
        _fish_crate(m, -10.5, -9.5, y=1.5)
        _fish_crate(m, 1.5, 1.5, y=5.5)
        _fish_crate(m, 12.5, 1.5, y=5.5, ramp="fabric_red", catch="bream")
        _ice_box(m, 2, -9.5, w=10, d=7, h=6)
        _net_heap(m, 13, 22, -9.5, -2.5, h=5)
    if stage >= 4:
        _fish_crate(m, -21.5, 1.5, y=9.5, catch="ice")
        _fish_crate(m, -10.5, 1.5, y=9.5)
        _fish_crate(m, -21.5, -9.5, y=5.5)
        _fish_crate(m, -10.5, -9.5, y=5.5, catch="ice")
        _tarp(m, 1, 23.5, 9.5, 1, 9, 5)
        _ice_box(m, 2, -9.5, y=6, w=10, d=7, h=5)
        _buoy(m, 15, -8.5, y=5)
    m.display = presets.furniture(gui_scale=0.35)
    return m


ALL = {
    "raft": raft,
    "raft_scull": raft_scull,
    **{f"raft_catch_{k}": (lambda k=k: raft_catch(k)) for k in range(1, 5)},
    "rowboat": rowboat,
    "rowboat_oar_r": lambda: rowboat_oar(1),
    "rowboat_oar_l": lambda: rowboat_oar(-1),
    **{f"rowboat_catch_{k}": (lambda k=k: rowboat_catch(k)) for k in range(1, 5)},
    "coastal_boat": coastal_boat,
    **{f"coastal_catch_{k}": (lambda k=k: coastal_catch(k)) for k in range(1, 5)},
    "trawler": trawler,
    "trawler_propeller": trawler_propeller,
    **{f"trawler_catch_{k}": (lambda k=k: trawler_catch(k)) for k in range(1, 5)},
}
