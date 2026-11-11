"""
Transport that is neither a car nor a cart (content/stsv/items/vehicles_misc.yml):

  통통배 (fishing_boat)   a small Korean wooden fishing boat, driven on water only (plugin: BoatPhysics). White planked
                          hull with a sea-blue sheer band and red antifouling under the waterline, a raised bow with a
                          fore peak, the anchor and a heap of net, a thwart for two, a little wheelhouse (windows all
                          round, an open back, a mast with a 오색 풍어기 and a lamp), the inboard engine's cover behind
                          it with the "통통통" exhaust stack, old tyres hung as fenders, two orange buoys, an open aft
                          deck for the catch.
      body      fishing_boat            drawn at its real size (5 x 2 blocks) and shrunk by 2 (body-scale 2), keel
                                        bottom middle at (0, 0, 0), front = north (-z), +x = the boat's right
      wheel     boat_propeller          its "wheel" is the propeller: hub at model (8, 8, 8), spun about z (the plugin
                                        spins a floating vehicle's wheels about its length)
      cargo     boat_catch_1..4         on the aft deck by how full the hold is: blue fish crates of fish on ice, a
                                        styrofoam ice box, green net, orange buoys; full: a tarp over the stack

  지게 (jige)            the traditional A-frame back carrier, worn (chest slot) and drawn on the back by the cosmetics'
                          back slot: model (8, 8, 8) on the upper back, +z backwards, +x the wearer's right.
      jige                              the empty frame (also the item's 3D model): two pine posts splayed into an A,
                                        four cross bars, two prongs jutting back, a woven straw back pad, straw shoulder
                                        straps and the prop stick (작대기) hooked on
      jige_load_1..3                    with a load on the prongs: a couple of bundles of firewood; a stack of them and a
                                        sack of grain; a full load towering over the head under a straw mat (거적) tied
                                        down with rope, a sickle tucked in

Detail is painted, not modelled (docs/ART_STYLE.md, 렌더 예산): plank seams and nail rows, the stripes, the window
seals, the louvres, rust under the fittings, the weave of the straw, the bark and the cut ends of the firewood.
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, h3
from stsv_assets import presets
from models.vehicles import CLEAR, _crate, _grain, _held_display, _tarp, glass, raw

# ================================================================================================
# 통통배: hull plan (drawn px, keel bottom middle at the origin, bow = -z)
# ================================================================================================

WL = 6                      # the waterline (vehicles_misc.yml water.draft 0.375 = 6 px)
DECK = 13                   # the deck inside the bulwarks
# hull segments along z (from, to) and the half width of the top of the hull there; the layers below are narrower
SEGS = [(30, 38, 13.5), (-12, 30, 15), (-20, -12, 14), (-27, -20, 12.5), (-32, -27, 9.5), (-36, -32, 6.5), (-39, -36, 3.5)]
FOC = 19                    # the raised fore deck (forecastle) over the bow, forward of z -27
# layers (y0, y1, how much narrower than the top, how far aft the bow starts, how far forward the stern ends)
LAYERS = [(0, 2, 7.5, -27, 28), (2, 5, 4, -31, 31), (5, 9, 1.5, -35, 37.5), (9, DECK, 0, -39, 38)]
# the bulwark's top by segment (the sheer rises to the bow)
SHEER = {30: 16.5, -12: 16.5, -20: 17, -27: 18}
BULWARK = 1.5
HOUSE = {"z0": -6, "z1": 10, "hw": 10.5, "top": 38}
HULL, BAND, BOTTOM, TRIM = "car_white", "water", "fabric_red", "wood_dark"


def _hull_paint(seg_hw):
    """The planked hull: red antifouling up to the waterline, a white boot stripe, white planks with their seams and a
    nail row at each frame, a sea-blue band under the gunwale with a thin white pin stripe, rust weeping from the
    fittings, the hull number painted on the bow. Gets darker towards the keel (in the shade of the bilge)."""
    def decal(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "down":
            return (BOTTOM, 0.6)
        if c.face == "up":
            return None
        if y < WL - 0.5:
            weed = h3(math.floor(x), fy, math.floor(z), 41)
            if fy == WL - 1 and weed < 0.25:
                return ("leaf_dark", 1.2)                         # a little weed at the waterline
            return (BOTTOM, 0.9 + 0.25 * fy + (0.4 if (fy % 2) else 0))
        if y < WL + 1:
            return ("police_blue", 1.8)                           # the dark boot stripe along the waterline
        along = z if c.face in ("east", "west") else x
        # planks: a seam every 2 rows, nail heads at the frames, the planks' ends staggered
        if fy % 2 == 0 and math.floor(along + 3 * (fy // 2)) % 13 == 0:
            return (HULL, 1.2)                                    # a butt joint
        if fy % 2 == 1:
            return (HULL, 1.6 if math.floor(along) % 6 else 1.0)  # the seam under the plank, a nail on each frame
        if c.face in ("east", "west") and z < -27 and 7 <= fy <= 9 and math.floor(-z - 27) % 4 in (1, 2):
            return ("police_blue", 1.4)                           # the hull number's marks on the bow
        streak = h3(math.floor(along), 3, 1, 43)
        if streak < 0.012 and fy < 11:
            return ("terracotta", 1.8)                            # rust weeping from a nail
        return (HULL, 2.8 + (0.3 if h3(math.floor(along / 3), fy, 2, 44) > 0.6 else 0))
    return Paint(HULL, 3, noise=0, rim=0.5, decal=decal)


def _bulwark_paint(top):
    """The bulwark boards above the deck: the blue band outside, scuffed white inside, a varnished capping rail."""
    def decal(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "up":
            return (TRIM, 2.8 + _grain(c, "z", 3) * 0.5)          # the capping rail, worn pale
        outward = (c.face in ("east", "west") and x * c.n[0] > 0) or (c.face in ("north", "south") and z * c.n[2] > 0)
        if fy >= top - 1:
            return (TRIM, 2.0 + _grain(c, "z", 3))                # the rail's edge
        if not outward:
            scuff = h3(math.floor(x + z), fy, 3, 45)
            return (HULL, 2.4 - (0.8 if scuff < 0.12 else 0))
        along = z if c.face in ("east", "west") else x
        if fy >= top - 2:
            return (BAND, 2.8)                                    # the band's lit upper board
        return (BAND, 2.2 if math.floor(along) % 9 else 1.7)
    return Paint(BAND, 2, noise=0, rim=0.6, decal=decal)


def _deck_paint(c):
    x, y, z = c.p
    if c.face != "up":
        return None
    board = math.floor((x + 15) / 3)
    if math.floor(x + 15) % 3 == 2:
        return ("wood", 0.8)                                      # the caulked seam between deck boards
    wet = h3(math.floor(x / 2), 2, math.floor(z / 3), 46)
    return ("wood", 2.2 + (0.4 if board % 2 else 0) + _grain(c, "z", board) - (0.7 if wet < 0.08 else 0))


def fishing_boat():
    m = BModel("fishing_boat", shift=(8, 8, 8), scale=2.0, floor=None)
    m.group("hull")
    for (za, zb, hw) in SEGS:
        for (y0, y1, inset, bow, stern) in LAYERS:
            a, b = max(za, bow), min(zb, stern)
            w = hw - inset
            if b <= a or w <= 0.5:
                continue
            m.box("hull", (-w, y0, a), (w, y1, b), _hull_paint(hw), faces={"up": Paint("wood", 2, noise=0, decal=_deck_paint)})
    # the keel and the stem post rising at the bow, the transom's oak frame at the stern
    keel = Paint(TRIM, 2, noise=0, decal=lambda c: (BOTTOM, 0.6) if c.p[1] < WL else (TRIM, 1.8 + _grain(c, "z", 9)))
    m.box("keel", (-1, -0.5, -28), (1, 0.5, 29), keel)
    m.box("stem", (-1, 4, -37), (1, 9, -35), keel)
    m.box("stem", (-1, 9, -40.5), (1, 25.5, -39), keel)
    # the rubbing strake: a dark oak rail along each side, where the hull meets the bulwark
    rub = Paint(TRIM, 2, noise=0, rim=0.6, decal=lambda c: (TRIM, 2.6 + _grain(c, "z", 11)) if c.face == "up" else (TRIM, 1.6 + _grain(c, "z", 11)))
    for (za, zb, hw) in SEGS:
        for s in (1, -1):
            xa, xb = sorted((s * hw, s * (hw + 0.75)))
            m.box("strake", (xa, 11.5, za), (xb, 13, zb), rub)

    m.group("bulwark")

    def bulkhead(c):
        x, y = c.p[0], c.p[1]
        if c.face == "south" and abs(x) < 3 and DECK + 1 <= y < FOC - 1:
            return (TRIM, 1.4 if abs(x) > 2.4 or y < DECK + 1.6 or y > FOC - 1.6 else 2.2 + _grain(c, "y", 5))
        return None
    fd = Paint("wood", 2, noise=0, decal=_deck_paint)
    rail = Paint(TRIM, 2, noise=0, rim=0.6, decal=lambda c: (TRIM, 2.8 + _grain(c, "z", 3) * 0.5) if c.face == "up" else (TRIM, 1.8 + _grain(c, "z", 3)))
    for k, (za, zb, hw) in enumerate(SEGS):
        if za < -27:
            # the forecastle: the hull carried up solid to the raised fore deck, a low rail round its edge; a little
            # hatch into the fore peak in its aft wall
            fc = _bulwark_paint(FOC + 1)
            m.box("forecastle", (-hw, DECK, za), (hw, FOC, zb), fc(decal=lambda c, fc=fc: bulkhead(c) or fc.decal(c)), faces={"up": fd})
            for s in (1, -1):
                xa, xb = sorted((s * (hw - 1), s * hw))
                m.box("rail", (xa, FOC, za), (xb, FOC + 1.5, zb), rail)
            continue
        top = SHEER[za]
        p = _bulwark_paint(top)
        for s in (1, -1):
            xa, xb = sorted((s * (hw - BULWARK), s * hw))
            m.box("bulwark", (xa, DECK, za), (xb, top, zb), p)
    m.box("transom", (-12, DECK, 36.5), (12, 16, 38), _bulwark_paint(16))

    m.group("fittings")
    _thwart(m)
    _wheelhouse(m)
    _engine(m)
    _bow_gear(m)
    _fenders(m)
    _stern(m)
    m.display = _held_display(m)
    return m


def _thwart(m):
    """A plank bench across the bow deck for two (the passengers fish from it), on two knees."""
    def board(c):
        if c.face == "up":
            return ("wood", 2.6 + _grain(c, "x", 21) - (0.6 if abs(c.p[0]) < 1 else 0))
        return ("wood", 1.6 + _grain(c, "x", 21))
    m.box("thwart", (-13.5, 15, -19), (13.5, 16.5, -15), Paint("wood", 2, noise=0, rim=0.6, decal=board))
    for x in (-8, 6):
        m.box("thwart_leg", (x, DECK, -18), (x + 2, 15, -16), Paint(TRIM, 2, noise=0))


def _wheelhouse(m):
    """The wheelhouse: white walls on a blue skirting, a window band all round (seals, a sun strip, a reflection),
    a door opening at the back, a pale blue roof with a lip, the mast with a lamp and the 풍어기, a searchlight, the
    wheel inside."""
    H = HOUSE
    z0, z1, hw, top = H["z0"], H["z1"], H["hw"], H["top"]
    win0, win1 = 27, 35

    def wall(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "up":
            return (HULL, 3)
        if fy < DECK + 2:
            return (BAND, 1.8)                                    # the skirting
        if fy == win0 - 1 or fy == win1:
            return ("rubber", 1.4)                                # the window seals' sills
        if fy >= top - 1:
            return ("water", 3)                                   # the roof's edge seen under the lip
        along = z if c.face in ("east", "west") else x
        if math.floor(along) % 7 == 3 and fy < win0 - 1:
            return (HULL, 2.4)                                    # the joints between the wall panels
        rust = h3(math.floor(along), fy, 7, 47)
        return (HULL, 3.2 - (0.5 if rust < 0.05 else 0) - (0.4 if fy < DECK + 4 else 0))
    wp = Paint(HULL, 3, noise=0, rim=0.6, decal=wall)
    t = 1
    # front wall (north) and the sides: solid under and over the window band
    for (ya, yb) in ((DECK, win0), (win1, top)):
        m.box("house_front", (-hw, ya, z0), (hw, yb, z0 + t), wp)
        for s in (1, -1):
            xa, xb = sorted((s * (hw - t), s * hw))
            m.box("house_side", (xa, ya, z0 + t), (xb, yb, z1 - t), wp)
    # window posts between the panes, and the panes (see-through planes)
    for x in (-hw, -3.5, 2.5, hw - 1):
        m.box("house_post", (x, win0, z0), (x + 1, win1, z0 + t), wp)
    for s in (1, -1):
        xa, xb = sorted((s * (hw - t), s * hw))
        for z in (z0 + t, 2, z1 - 2):
            m.box("house_post", (xa, win0, z), (xb, win1, z + 1), wp)
    gl = glass(sun=1, streak=0.35)
    for (xa, xb) in ((-hw + 1, -3.5), (-2.5, 2.5), (3.5, hw - 1)):
        m.box("house_glass", (xa, win0, z0 + 0.25), (xb, win1, z0 + 0.75), gl, faces={"up": None, "down": None})
    for s in (1, -1):
        x = s * (hw - 0.5)
        for (za, zb) in ((z0 + t, 2), (3, z1 - 2)):
            m.box("house_glass", (x - 0.25, win0, za), (x + 0.25, win1, zb), gl, faces={"up": None, "down": None})
    # the back: a door opening between two narrow panels and a lintel
    for s in (1, -1):
        xa, xb = sorted((s * hw, s * 5))
        m.box("house_back", (xa, DECK, z1 - t), (xb, top, z1), wp)
    m.box("house_back", (-5, 33, z1 - t), (5, top, z1), wp)

    def roof(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 9 and abs(z - 2) < 7 and math.floor(x + 12) % 4 == 0:
                return ("water", 2.4)                             # the roof's ribs
            return ("water", 3.2 + (0.4 if h3(math.floor(x), 1, math.floor(z), 48) > 0.85 else 0))
        if c.face == "down":
            return ("water", 1.4)
        return ("water", 2.2)
    m.box("roof", (-hw - 1.5, top, z0 - 2), (hw + 1.5, top + 2, z1 + 1.5), Paint("water", 3, noise=0, rim=0.8, decal=roof))

    # inside: the wheel on the front wall, the console under it, the helmsman's stool
    def wheel(c):
        x, y, z = c.p
        r = math.hypot(x, y - 25)
        if c.face in ("north", "south"):
            if r > 2.2 or r < 0.7:
                return ("wood", 2.6)                              # the rim and the hub
            a = math.atan2(y - 25, x)
            return ("wood", 2.2) if math.floor(a / (math.pi / 4) + 0.5) % 2 == 0 else ("raw", CLEAR)
        return ("wood", 1.8)
    m.box("console", (-5, DECK, z0 + t), (5, 21, z0 + 4), Paint(TRIM, 2, noise=0, rim=0.5))
    m.box("helm", (-3, 22, z0 + 3.5), (3, 28, z0 + 4), Paint("wood", 2, noise=0, rim=0, decal=wheel))
    m.box("helm_shaft", (-0.5, 24.5, z0 + 1), (0.5, 25.5, z0 + 3.5), Paint("iron", 2, noise=0))
    m.box("stool", (-2.5, DECK, 2), (2.5, 16, 7), Paint("wood", 2, noise=0, rim=0.6,
                                                            decal=lambda c: ("wood", 3) if c.face == "up" else ("wood", 1.6)))

    # on the roof: the mast with a lamp and the five-coloured flag, a searchlight at the front
    def mast(c):
        if math.floor(c.p[1]) in (45, 46):
            return ("lamp", 4)                                    # the masthead lamp
        return ("wood", 2.0 + _grain(c, "y", 31))
    m.box("mast", (-0.75, top + 2, 6), (0.75, 47, 7.5), Paint("wood", 2, noise=0, rim=0.5, decal=mast))

    def flag(c):
        if c.nu <= 1 or c.nv <= 1:
            return ("fabric_red", 2)
        y, z = c.p[1], c.p[2]
        band = math.floor(46.5 - y)
        stripes = [("fabric_red", 2.6), ("petal_yellow", 2.4), ("denim", 2.8), ("leaf", 2.6), ("feather", 3)]
        if z > 17 - abs(y - 44) * 1.2 and z > 14.5:
            return ("raw", CLEAR)                                 # the swallow-tailed fly
        return stripes[min(4, max(0, band))]
    m.box("flag", (-0.25, 41.5, 7.5), (0.25, 46.5, 17), Paint("fabric_red", 2, noise=0, light=False, decal=flag))

    def light(c):
        if c.face == "north":
            return ("lamp", 4.6) if math.hypot(c.p[0] + 6, c.p[1] - top - 3.5) < 1.2 else ("blade", 2.4)
        return ("blade", 2.6 if c.face == "up" else 1.8)
    m.box("searchlight", (-7.5, top + 2, z0 - 1), (-4.5, top + 5, z0 + 2), Paint("blade", 2, noise=0, rim=0.6, decal=light))
    # the air horn: a little trumpet on the roof (the boat's horn)
    m.box("horn", (4, top + 2, z0 - 0.5), (6, top + 3.5, z0 + 4), Paint("blade", 2, noise=0, rim=0.6,
                                                                          decal=lambda c: ("rubber", 0) if c.face == "north" else None))


def _engine(m):
    """The inboard engine's wooden cover behind the wheelhouse (louvres, a lifting handle) and the exhaust stack rising
    beside the house: a black pipe, a rain cap, soot round the top."""
    def cover(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(z - 13) < 0.5 and abs(x - 2) < 2:
                return ("iron", 2.8)                              # the handle
            return ("wood", 2.4 + _grain(c, "x", 51))
        if c.face in ("east", "west") and 14.5 <= y < 17.5 and math.floor(z) % 2 == 0:
            return ("rubber", 0.8)                                # the louvres
        return ("wood", 1.8 + _grain(c, "x", 51))
    m.box("engine_cover", (-6, DECK, 10), (10, 18, 16), Paint("wood", 2, noise=0, rim=0.6, decal=cover))

    def pipe(c):
        y = c.p[1]
        if y > 41:
            return ("soot", 0.8 if c.face != "up" else 0.2)       # sooted round the mouth
        if math.floor(y) in (24, 32):
            return ("iron", 2.8)                                  # the pipe clamps
        return ("rubber", 1.8 if c.face in ("north", "west") else 1.2)
    m.box("stack", (5, 18, 11.5), (8, 44, 14.5), Paint("rubber", 1, noise=0, rim=0.6, decal=pipe))
    m.box("stack_cap", (4, 44.5, 10.5), (9, 45.5, 15.5), Paint("rubber", 1, noise=0, rim=0.6))
    m.box("stack_cap_post", (6, 44, 12.5), (7, 44.5, 13.5), Paint("rubber", 1, noise=0))
    m.box("stack_stay", (7.5, 34, 12.5), (10.5, 35, 13.5), Paint("iron", 2, noise=0))


def _bow_gear(m):
    """On the fore peak: a heap of green net with its orange float line, the anchor lashed on, a mooring bitt; the
    running light on the stem."""
    def net(c):
        x, y, z = c.p
        u = math.floor(x * 1.3 + z * 0.7) % 3 == 0 or math.floor(z * 1.1 - x * 0.6 + y) % 3 == 0
        if c.face == "up" and math.floor(x + 7) % 5 == 0 and math.floor(z) % 2 == 0:
            return ("fox", 3)                                     # the floats along the float line
        return ("leaf_dark", 1.4 if u else 2.6)
    np = Paint("leaf_dark", 2, noise=0, rim=0.4, decal=net)
    m.box("net", (-7.5, FOC, -32), (2.5, FOC + 2.5, -27.5), np)
    m.box("net", (-6, FOC + 2.5, -31), (1, FOC + 4, -28.5), np)

    def anchor(c):
        return ("iron", 2.4 if c.face == "up" else 1.6) if h3(*map(math.floor, c.p), 52) > 0.15 else ("terracotta", 1.6)
    ap = Paint("iron", 2, noise=0, rim=0.6, decal=anchor)
    m.box("anchor", (3, FOC, -34), (4, FOC + 1, -28.5), ap)
    m.box("anchor", (1.5, FOC, -34.5), (5, FOC + 0.75, -33.5), ap)
    m.box("anchor_ring", (3, FOC, -28.5), (4, FOC + 1.5, -28), ap)
    m.box("bitt", (-1, FOC, -37.5), (1, FOC + 2.5, -36), Paint(TRIM, 2, noise=0, rim=0.6))
    m.box("bow_lamp", (-1.5, 23, -40), (1.5, 25, -38.5), Paint("blade", 2, noise=0, rim=0.6,
                                                                       decal=lambda c: ("glow_amber", 4) if c.face == "north" else None))


def _fenders(m):
    """Two old tyres hung on ropes over each side as fenders, and an orange buoy each side by the wheelhouse."""
    def tyre(zc):
        def decal(c):
            y, z = c.p[1], c.p[2]
            d = math.hypot(y - 9.5, z - zc)
            if c.face in ("east", "west"):
                if d < 1.6:
                    return ("rubber", 0)                          # the hole
                if d > 3.2:
                    return ("rubber", 0.8)                        # the tread's worn edge
                return ("rubber", 1.4 + (0.6 if math.floor(math.atan2(y - 9.5, z - zc) * 3) % 2 else 0))
            return ("rubber", 1.0 if math.floor(y) % 2 else 1.6)  # the tread
        return Paint("rubber", 1, noise=0, rim=0.4, decal=decal)
    for s in (1, -1):
        for zc in (-23.5, 22):
            tp = tyre(zc)
            hw = (12.5 if zc < 0 else 15) + 0.75
            xa, xb = sorted((s * hw, s * (hw + 1.5)))
            m.box("fender", (xa, 5.5, zc - 3), (xb, 12.5, zc + 3), tp)
            ra, rb = sorted((s * hw, s * (hw + 0.5)))
            m.box("fender_rope", (ra, 12.5, zc - 0.5), (rb, 17, zc + 0.5), Paint("straw", 2, noise=0))

    def buoy(c):
        y = c.p[1]
        if 10 <= y < 11:
            return ("feather", 3)                                 # its white band
        return ("fox", 3.2 if c.face == "up" else 2.6)
    bp = Paint("fox", 2, noise=0, rim=0.5, decal=buoy)
    for s in (1, -1):
        xa, xb = sorted((s * 15, s * 19))
        m.box("buoy", (xa, 8, 12), (xb, 12, 16), bp)
        ra, rb = sorted((s * 15, s * 15.5))
        m.box("buoy_rope", (ra, 12, 13.5), (rb, 16.5, 14.5), Paint("straw", 2, noise=0))


def _stern(m):
    """The rudder hung on the transom (its tiller lines run in board), the propeller shaft out of the keel."""
    rp = Paint(TRIM, 2, noise=0, rim=0.5, decal=lambda c: (BOTTOM, 0.8) if c.p[1] < WL else (TRIM, 1.8 + _grain(c, "y", 61)))
    m.box("rudder", (-0.75, 0.5, 37.5), (0.75, 8, 41), rp)
    m.box("rudder_stock", (-0.5, 8, 39), (0.5, 16.5, 40), Paint("iron", 2, noise=0))
    m.box("shaft", (-0.5, 2.5, 28), (0.5, 3.5, 32), Paint("blade", 2, noise=0))
    # the boat's name board on the transom: a pale plate with a few painted marks (no real name)
    def plate(c):
        if c.face != "south":
            return ("hinoki", 1)
        col = math.floor(c.p[0] + 6)
        row = math.floor(c.p[1])
        if col in (0, 11) or row in (8, 11):
            return ("hinoki", 0.8)
        return ("police_blue", 1.2) if (row == 10 and 2 <= col <= 9 and col % 2 == 0) else ("hinoki", 2.8)   # a few letter marks
    m.box("name_board", (-6, 8, 38), (6, 12, 38.5), Paint("hinoki", 2, noise=0, rim=0.4, decal=plate))


def boat_propeller():
    """A small bronze four-bladed propeller on its hub (diameter 6 px), axis along z; the plugin spins it about z."""
    m = BModel("boat_propeller", shift=(8, 8, 8), floor=None)

    def blade(c):
        x, y = c.p[0], c.p[1]
        r = max(abs(x), abs(y))
        return ("brass", 3.0 - 0.3 * r + (0.6 if c.face == "north" else 0))
    bp = Paint("brass", 2, noise=0, rim=0.6, decal=blade)
    m.box("blade", (-0.5, -3, -0.25), (0.5, 3, 0.25), bp)
    m.box("blade", (-3, -0.5, -0.25), (3, 0.5, 0.25), bp)
    m.box("hub", (-1, -1, -1), (1, 1, 1.5), Paint("brass", 2, noise=0, rim=0.6))
    m.display = presets.furniture(gui_scale=0.8)
    return m


# ================================================================================================
# The catch on the aft deck (deck floor middle at model (8, 8, 8)): 25 x 19 px inside the bulwarks and the engine cover
# ================================================================================================

def _fish_crate(m, x, z, y=0, w=10, d=7, h=4, ramp="denim", catch="fish"):
    """A shallow plastic fish box (the kind piled at every Korean harbour): ribbed sides, a hand hole in each end, open
    on top with the catch on crushed ice (silver fish, a red bream, a squid)."""
    def decal(c):
        px, py, pz = c.p
        u = py - y
        if c.face in ("north", "south", "east", "west"):
            along = px - x if c.face in ("north", "south") else pz - z
            span = w if c.face in ("north", "south") else d
            if u > h - 1:
                return (ramp, 3.2)                                # the lip
            if c.face in ("east", "west") and 1 <= u < 2.5 and abs(along - span / 2) < 1.5:
                return (ramp, 0.4)                                # the hand hole
            if math.floor(along) % 3 == 1:
                return (ramp, 1.6)                                # the ribs
            return (ramp, 2.4 - (0.4 if u < 1 else 0))
        if c.face == "up":
            a, b = px - x, pz - z
            if a < 0.75 or a > w - 0.75 or b < 0.75 or b > d - 0.75:
                return (ramp, 3.0)
            if catch == "ice":
                return ("ice", 3 if h3(math.floor(px), 1, math.floor(pz), 71) > 0.3 else 2)
            # fish lying head to tail across the box: silver backs, a dark eye, ice between them
            k = math.floor(b) % 3
            if k == 2:
                return ("ice", 3 if h3(math.floor(px), 2, math.floor(pz), 72) > 0.4 else 2)
            if catch == "bream" and math.floor(b) // 3 == 0:
                return ("seabream", 2.6 if k == 0 else 2.0)
            head = (math.floor(a) + 3 * (math.floor(b) // 3)) % 9
            if head == 0:
                return ("rubber", 0)                              # an eye
            if head == 8:
                return ("fish_silver", 1.2)                       # the tail fin
            return ("fish_blue", 1.2) if k == 0 else ("fish_silver", 1.8)   # a dark back, a silver belly
        return (ramp, 1.4)
    m.box("fish_crate", (x, y, z), (x + w, y + h, z + d), Paint(ramp, 2, noise=0, rim=0.5, decal=decal))


def _ice_box(m, x, z, y=0, w=9, d=7, h=5):
    """A white styrofoam ice box with its lid on: a blue printed band, the lid's lip."""
    def decal(c):
        u = c.p[1] - y
        if c.face in ("north", "south", "east", "west"):
            if u > h - 1.5:
                return ("feather", 2.6 if u > h - 1 else 2.0)     # the lid's lip
            if 1.5 <= u < 2.5:
                return ("denim", 2.4)                             # the printed band
            return ("feather", 3.0)
        if c.face == "up":
            return ("feather", 3.4 if h3(math.floor(c.p[0]), 3, math.floor(c.p[2]), 73) > 0.2 else 3.0)
        return ("feather", 2)
    m.box("ice_box", (x, y, z), (x + w, y + h, z + d), Paint("feather", 3, noise=0, rim=0.4, decal=decal))


def _net_heap(m, x0, x1, z0, z1, y=0, h=4):
    """Green nylon net in a heap: a mesh of light and dark strands, its orange float line and lead line on top."""
    def decal(c):
        px, py, pz = c.p
        if c.face == "up" and abs(pz - (z0 + z1) / 2 - math.sin(px / 3) * 1.5) < 0.6:
            return ("fox", 3) if math.floor(px) % 3 == 0 else ("straw", 2.4)   # the float line
        u = math.floor(px * 1.3 + pz * 0.7 + py) % 3 == 0 or math.floor(pz * 1.1 - px * 0.6 + py) % 3 == 0
        return ("leaf_dark", 1.2 if u else 2.6)
    p = Paint("leaf_dark", 2, noise=0, rim=0.4, decal=decal)
    m.box("net", (x0, y, z0), (x1, y + h - 1.5, z1), p)
    m.box("net", (x0 + 1.5, y + h - 1.5, z0 + 1), (x1 - 1.5, y + h, z1 - 1), p)


def _buoy(m, x, z, y=0):
    """An orange buoy lying on its side (a fat cylinder along x, a white band, the rope eye)."""
    def decal(c):
        if abs(c.p[0] - (x + 2.5)) < 0.5:
            return ("feather", 3)
        return ("fox", 3.2 if c.face == "up" else 2.4)
    cyl(m, "buoy", (y + 2.5, z + 2.5), 5, x, x + 5, Paint("fox", 2, noise=0, rim=0.5, decal=decal), axis="x")


def _rope_coil(m, x, z, y=0):
    def decal(c):
        r = math.hypot(c.p[0] - x, c.p[2] - z)
        if c.face == "up":
            return ("straw", 0.8) if r < 1 else ("straw", 2.6 if math.floor(r * 1.5) % 2 else 1.8)
        return ("straw", 2.2 if math.floor(c.p[1] * 2) % 2 else 1.6)
    cyl(m, "rope", (x, z), 5, y, y + 1.5, Paint("straw", 2, noise=0, rim=0.4, decal=decal))


def boat_catch(stage):
    m = BModel(f"boat_catch_{stage}", shift=(8, 8, 8), floor=None)
    if stage >= 1:
        _fish_crate(m, -12, 1)
        _fish_crate(m, -1.5, 1, catch="bream", ramp="fabric_red")
        if stage == 1:
            _rope_coil(m, 9, -5)                                  # (stowed once the hold fills)
    if stage >= 2:
        _fish_crate(m, -12, 1, y=4)
        _net_heap(m, -12, 0, -9, 0, h=4)
        _ice_box(m, 2, -8.5)
    if stage >= 3:
        _fish_crate(m, -1.5, 1, y=4)
        _fish_crate(m, -12, 1, y=8, catch="ice")
        _buoy(m, 1, -9, y=5)
    if stage >= 4:
        _fish_crate(m, -1.5, 1, y=8, ramp="fabric_red", catch="bream")
        _tarp(m, -12.5, 9, 12, -1, 9, 4)
        _buoy(m, 6, -9, y=5)
        _net_heap(m, -12, 0, -9, 0, y=4, h=3)
    m.display = presets.furniture(gui_scale=0.4)
    return m


# ================================================================================================
# 지게 (worn: model (8, 8, 8) on the upper back, +z backwards, +x the wearer's right)
# ================================================================================================

PINE = "wood"


def _pole(axis, seed):
    """A peeled pine pole: grain along it, a knot or two, the bark left in patches, the light on its upper side."""
    def decal(c):
        g = _grain(c, axis, seed)
        if h3(*map(math.floor, c.p), seed + 90) < 0.06:
            return ("bark", 1.6)                                  # bark left on
        lit = 0.5 if c.face in ("up", "west") else -0.3 if c.face in ("down", "east") else 0
        return (PINE, 2.0 + g + lit)
    return Paint(PINE, 2, noise=0, rim=0.5, decal=decal)


def _frame(m):
    m.group("frame")
    # two posts splayed into an A (each in three straight lengths, stepping out towards the bottom)
    for s in (1, -1):
        for (y0, y1, x) in ((-5, 2, 6.0), (2, 9, 5.25), (9, 17, 4.5)):
            xa, xb = sorted((8 + s * x, 8 + s * (x - 1.5)))
            m.box("post", (xa, y0, 9), (xb, y1, 10.5), _pole("y", 1 if s > 0 else 2))
        # the post's foot, worn and darkened from being set down
        xa, xb = sorted((8 + s * 6.0, 8 + s * 4.5))
        m.box("post_foot", (xa, -6, 9), (xb, -5, 10.5), Paint("bark", 2, noise=0))
    # the cross bars (세장) between the posts, lashed with straw cord at both ends
    pole = _pole("x", 3)
    for (y, half) in ((-2, 5.75), (3.5, 5.0), (8.5, 4.25), (14.5, 3.5)):
        def bar(c, half=half):
            if abs(abs(c.p[0] - 8) - (half - 0.5)) < 0.75:
                return ("straw", 2.6 if math.floor(c.p[1] * 2) % 2 else 1.8)   # the lashing
            return pole.decal(c)
        m.box("crossbar", (8 - half, y, 9.25), (8 + half, y + 1, 10.25), pole(decal=bar))
    # the prongs (가지) jutting back and up from the posts, where the load sits
    for s in (1, -1):
        x = 8 + s * 4.75
        m.box("prong", (x - 0.75, 0.5, 10.5), (x + 0.75, 1.75, 19), _pole("z", 4 if s > 0 else 5),
              rotation=("x", -22.5, (x, 1, 10.5)))
    # the back pad (등태): a mat of woven straw against the back
    def pad(c):
        u = math.floor(c.p[0] * 2) + math.floor(c.p[1] * 2)
        return ("straw", 2.6 if u % 2 else 1.8) if c.face in ("north", "south") else ("straw", 1.6)
    m.box("pad", (4.5, 0, 8.25), (11.5, 11, 9), Paint("straw", 2, "weave", noise=0, rim=0.4, decal=pad))
    # the straw shoulder straps (밀삐): from the top bar over each shoulder and down the chest
    def strap(c):
        return ("straw", 2.4 if math.floor(c.p[1] + c.p[2]) % 2 else 1.6)
    sp = Paint("straw", 2, noise=0, rim=0.4, decal=strap)
    for s in (1, -1):
        x = 8 + s * 2.75
        m.box("strap", (x - 0.75, 11.5, 3.5), (x + 0.75, 12.5, 9.25), sp)
        m.box("strap", (x - 0.75, 4, 3.25), (x + 0.75, 11.5, 3.75), sp)
        m.box("strap", (x - 0.75, -1, 8.25), (x + 0.75, 0, 9.25), sp)
    # the prop stick (작대기) tucked upright beside the right post, its fork over the top bar
    sp2 = _pole("y", 6)
    m.box("stick", (15.5, -7, 10), (16.5, 16, 11), sp2)
    m.box("stick_fork", (14.5, 16, 10), (15.5, 18, 11), sp2)
    m.box("stick_fork", (16.5, 16, 10), (17.5, 18, 11), sp2)


def _bundle(m, x0, x1, y, z, d=5, seed=0):
    """A bundle of firewood (나뭇단) lying across the prongs: sticks along x, their pale cut ends with rings, bark on
    the sides, two straw ties."""
    def decal(c):
        px, py, pz = c.p
        if c.face in ("east", "west"):
            # the sawn ends of the sticks: little 2 px rounds stacked in staggered rows, each lit at its top left
            row = math.floor(py - y)
            col = math.floor(pz - z + (row % 2))
            k = (row % 2, col % 2)
            if k == (1, 1) or (k[0] == 1 and h3(col, row, seed, 81) < 0.2):
                return ("bark", 0.8)                              # the shadow between the rounds
            return ("hinoki", 2.9 if k == (0, 0) else 2.2)
        for t in (x0 + 2, x1 - 2):
            if abs(px - t) < 0.6:
                return ("straw", 2.8 if c.face == "up" else 2.0)  # the ties
        stick = math.floor(pz if c.face in ("up", "down") else py) % 2
        return ("bark", 1.6 + stick * 0.8 + (0.6 if h3(math.floor(px / 2), math.floor(py), math.floor(pz), seed + 82) > 0.85 else 0))
    p = Paint("bark", 2, noise=0, rim=0.5, decal=decal)
    m.box("bundle", (x0, y, z), (x1, y + 3, z + d), p)
    m.box("bundle", (x0 + 0.5, y + 3, z + 0.5), (x1 - 0.5, y + 4, z + d - 0.5), p)


def _grain_sack(m, x0, x1, y, z, d=5, h=5):
    """A tied sack of grain set on top: burlap, a red stripe, the neck tied with straw."""
    def decal(c):
        px, py, pz = c.p
        if c.face in ("north", "south", "east", "west") and math.floor(py - y) == 2:
            return ("fabric_red", 1.6)
        return None
    p = Paint("burlap", 2, "weave", noise=0.02, rim=0.4, decal=decal, decal_pattern=True)
    m.box("sack", (x0, y, z), (x1, y + h - 1, z + d), p)
    m.box("sack", (x0 + 1, y + h - 1, z + 1), (x1 - 1, y + h, z + d - 1), p)
    cx = (x0 + x1) / 2
    m.box("sack_tie", (cx - 1, y + h, z + d / 2 - 1), (cx + 1, y + h + 1, z + d / 2 + 1), Paint("straw", 2, noise=0))


def _mat_cover(m, x0, x1, y0, y1, z0, z1):
    """A straw mat (거적) thrown over the top of the load and down its back: a cap over the top bundles and a flap down
    the back, the weave running across, a frayed hem, two ropes round it (the firewood ends show at the sides)."""
    def decal(c):
        px, py, pz = c.p
        if abs(py - (y1 - 3)) < 0.6 or abs(py - (y0 + 3)) < 0.6:
            return ("leather", 2.0 if math.floor(px + pz) % 2 else 1.4)   # the ropes round the load
        if c.face == "south" and abs(px - (x0 + x1) / 2 - (py - (y0 + y1) / 2) * 0.35) < 0.6:
            return ("leather", 1.6)                               # a rope down the back, a little askew
        if math.floor(py) == math.floor(y0) and c.face != "up":
            return ("straw", 1.2 if math.floor(px) % 2 else 2.2)  # the frayed hem
        w = (math.floor(px * 2) + math.floor(py if c.face != "up" else pz)) % 4
        return ("straw", (2.6 if w < 2 else 2.0) + (0.4 if h3(math.floor(px), math.floor(py), math.floor(pz), 91) > 0.85 else 0))
    p = Paint("straw", 2, noise=0, rim=0.5, decal=decal)
    m.box("mat", (x0, y1 - 2.5, z0), (x1, y1, z1), p)                 # over the top
    m.box("mat", (x0 + 0.25, y0, z1 - 1), (x1 - 0.25, y1 - 2.5, z1), p)   # down the back


def _full_load(m):
    """Firewood stacked high on the prongs (five bundles, the top ones smaller), a straw mat over it, a sickle tucked in."""
    for (k, (x0, x1, y, d)) in enumerate(((1.5, 14.5, 2, 5), (2, 14, 6, 5), (1.5, 14.5, 10, 5), (2, 14, 14, 5), (3, 13, 18, 4.5))):
        _bundle(m, x0, x1, y, 11, d=d, seed=k + 1)
    _mat_cover(m, 1, 15, 9, 23.5, 10.5, 16.75)
    _sickle(m)


def _sickle(m):
    """A sickle (낫) tucked under the ropes, handle up."""
    m.box("sickle", (14.5, 11, 13), (15.5, 18.5, 14), Paint("wood", 2, noise=0, decal=lambda c: ("leather", 1.4) if c.p[1] < 13 else None))
    m.box("sickle_blade", (14.75, 17.5, 14), (15.25, 18.5, 17.5), Paint("blade", 2, noise=0, rim=0.6))


def jige(stage=0):
    name = "jige" if stage == 0 else f"jige_load_{stage}"
    m = BModel(name, floor=None)
    _frame(m)
    m.group("load")
    if stage == 1:
        _bundle(m, 2.5, 13.5, 2, 11.5, seed=1)
        _bundle(m, 3, 13, 6, 12, d=4.5, seed=2)
    elif stage == 2:
        _bundle(m, 2, 14, 2, 11.5, seed=1)
        _bundle(m, 2.5, 13.5, 6, 11.5, seed=2)
        _bundle(m, 2, 14, 10, 12, d=4.5, seed=3)
        _grain_sack(m, 4, 12, 14, 11.5)
    elif stage == 3:
        _full_load(m)
    m.display = _jige_display()
    return m


def _jige_display():
    """Held: a small frame in the hand; in an item frame / on the ground as it stands."""
    d = presets.furniture(gui_scale=0.55)
    for ctx in ("thirdperson_righthand", "firstperson_righthand", "firstperson_lefthand"):
        d[ctx] = dict(d[ctx])
        d[ctx]["scale"] = [round(v * 0.8, 3) for v in d[ctx]["scale"]]
    return d


ALL = {
    "fishing_boat": fishing_boat,
    "boat_propeller": boat_propeller,
    **{f"boat_catch_{k}": (lambda k=k: boat_catch(k)) for k in range(1, 5)},
    "jige": jige,
    **{f"jige_load_{k}": (lambda k=k: jige(k)) for k in range(1, 4)},
}
