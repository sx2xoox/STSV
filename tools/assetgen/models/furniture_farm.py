"""
Farm furniture (content/stsv/items/furniture_farm.yml), built as baked models (stsv_assets/bake.py) with the same
conventions as models/furniture_japanese.py: 16 px a block, front = north (z 0), the anchor cell x 0..16 / z 0..16,
y 0 its floor; model +x is the viewer's LEFT; every piece centred in its cells.

허수아비 (scarecrow, a donation item): placed on a player's farm it keeps that chunk's crops growing while nobody is
near (CropModule · Scarecrows). Two blocks tall (cells [0,0,0] and [0,1,0]), not solid: players and animals walk
through it.

PIECES: id -> (builder, cells as (w, h, d) for the previews, floor offset)
"""

import math

from economy_machines import display
from stsv_assets.bake import BModel, Paint, bands, cyl

# ================================================================================================
# Paints
# ================================================================================================

POST = Paint("wood", 1, "grain", axis="y")
SOIL = Paint("soil", 1, "speckle")
SACK = Paint("burlap", 2, "weave", noise=0.03)
STRAW = Paint("straw", 3, "grain", axis="long", noise=0)
STRAW_DARK = Paint("straw", 2, "grain", axis="long", noise=0)
ROPE_RAMP = "bean"


def flat(ramp_name, step, **kw):
    """A plain paint without pattern or noise (small parts, cloth)."""
    return Paint(ramp_name, step, noise=0, **kw)


def twist(c):
    """Decal: a rope's twist, a lighter strand every other texel on a slant."""
    a = c.i[1] + (c.i[0] if c.face in ("north", "south") else c.i[2])
    return (ROPE_RAMP, 2 if a % 2 else 1)


ROPE = Paint(ROPE_RAMP, 1, noise=0, decal=twist, rim=0.6)


def plaid(c):
    """Decal: a two-tone red check, a darker stripe every 4 px each way, darkest where they cross."""
    if c.face in ("north", "south"):
        h, v = c.i[0], c.i[1]
    elif c.face in ("east", "west"):
        h, v = c.i[2], c.i[1]
    else:
        h, v = c.i[0], c.i[2]
    a, b = h % 4 == 1, v % 4 == 1
    if a and b:
        return ("fabric_red", 0)
    if a or b:
        return ("fabric_red", 1)
    return None


SHIRT = Paint("fabric_red", 2, noise=0, decal=plaid)


def patch(ramp_name, step, lo, hi):
    """A cloth patch sewn on: its own colour, light stitches at its four corners (lo, hi: its corners in x/y or
    z/y, whichever the face runs along)."""
    def decal(c):
        h = c.p[0] if c.face in ("north", "south") else c.p[2]
        y = c.p[1]
        if c.face in ("north", "south", "east", "west"):
            near_h = min(abs(h - lo[0]), abs(h - hi[0])) < 1
            near_v = min(abs(y - lo[1]), abs(y - hi[1])) < 1
            if near_h and near_v:
                return ("cream", 3)
        return None
    return Paint(ramp_name, step, noise=0, decal=decal, rim=0.4, ao=0.5)


def rbox(m, name, frm, to, paint, rot):
    m.box(name, frm, to, paint, rotation=rot)


def rcyl(m, name, c, d, lo, hi, paint, rot):
    """cyl() turned as a whole (each stepped band gets the same rotation)."""
    for (a, b, hw) in bands(d):
        m.box(name, (c[0] - hw, lo, c[1] + a), (c[0] + hw, hi, c[1] + b), paint(vrim=0), rotation=rot)


# ================================================================================================
# 허수아비
# ================================================================================================

def scarecrow():
    """허수아비 1x2: a burlap sack head (black button eyes, rosy cheeks, a smile sewn on in dark thread, the sack
    gathered at the neck with twine) under a wide straw hat cocked to one side (a denim band, a little sunflower), a
    red plaid shirt with patches (denim on the chest, green on a sleeve and on the back) stretched on a crossbar,
    straw poking out of the cuffs, the collar and the shirt tail, a rope belt knotted in front, and the post going
    into a small mound of earth. Arms 1.5 blocks across."""
    m = BModel("scarecrow")

    # --- the post in its mound ---------------------------------------------------------------------
    m.group("post")
    m.box("mound", (4, 0, 5), (12, 1, 11), SOIL)
    m.box("mound", (5, 0, 4), (11, 1, 5), SOIL)
    m.box("mound", (5, 0, 11), (11, 1, 12), SOIL)
    m.box("mound_top", (5.5, 1, 5.5), (10.5, 1.5, 10.5), SOIL)
    m.box("post", (6.5, 1.5, 6.5), (9.5, 9, 9.5), POST)

    # --- the shirt -----------------------------------------------------------------------------------
    m.group("shirt")
    m.box("hem", (2.5, 8, 4.5), (13.5, 11, 11.5), SHIRT)                 # the loose shirt tail
    m.box("torso", (3, 12.5, 5), (13, 18, 11), SHIRT)
    m.box("belt", (2, 11, 4), (14, 12.5, 12), ROPE)
    m.box("shoulder", (2.5, 17.5, 5.5), (13.5, 18.5, 10.5), SHIRT)       # the shoulders round off under the collar
    for (x0, x1) in ((13.5, 17), (-1, 2.5)):                              # sleeves on the crossbar
        m.box("sleeve", (x0, 14, 6), (x1, 18, 10), SHIRT)
    m.box("cuff", (17, 13.5, 5.5), (18, 18.5, 10.5), SHIRT)
    m.box("cuff", (-2, 13.5, 5.5), (-1, 18.5, 10.5), SHIRT)
    # the placket down the front: a darker strip with two buttons
    m.box("placket", (7.5, 12.5, 4.75), (8.5, 18, 5), flat("fabric_red", 0, rim=0.4, ao=0.5))
    for y in (13.5, 16):
        m.box("button", (7.5, y, 4.5), (8.5, y + 1, 4.75), flat("cream", 3, ao=0, rim=0))
    # the collar's two points
    m.box("collar", (8.5, 17, 4.25), (11, 18.5, 5), flat("fabric_red", 1, ao=0.5))
    m.box("collar", (5, 17, 4.25), (7.5, 18.5, 5), flat("fabric_red", 1, ao=0.5))
    # patches: denim on the chest (viewer's right), green on the left sleeve and on the back
    m.box("patch", (4, 13, 4.75), (7, 16, 5), patch("denim", 2, (4, 13), (7, 16)))
    m.box("patch", (14, 14.5, 5.75), (16.5, 17, 6), patch("sprout", 2, (14, 14.5), (16.5, 17)))
    m.box("patch", (8, 13, 11), (11.5, 16.5, 11.25), patch("sprout", 2, (8, 13), (11.5, 16.5)))
    # the rope belt's knot in front, its two ends hanging
    m.box("knot", (9.5, 10.75, 3.5), (11.5, 12.75, 4), ROPE)
    m.box("rope_end", (9.5, 8.5, 3.5), (10.5, 10.75, 3.75), ROPE)
    m.box("rope_end", (11, 9, 3.5), (12, 10.75, 3.75), ROPE)

    # --- straw poking out ---------------------------------------------------------------------------
    m.group("straw")
    # out of the cuffs: a bundle and a few loose stalks (mirrored for the other arm)
    cuff_straw = [  # x from the cuff (outwards), y, z ranges
        ((0, 1.25), (14.5, 17.5), (6.5, 9.5), STRAW_DARK),
        ((0, 2.5), (16.25, 17.25), (7, 8), STRAW),
        ((0, 2), (15, 16), (8.25, 9.25), STRAW),
        ((0, 3), (14.75, 15.75), (6.25, 7.25), STRAW),
        ((0, 1.75), (17.5, 18.25), (8, 9), STRAW),
        ((1.25, 2.25), (13, 14.75), (7.75, 8.75), STRAW_DARK),            # one hangs down
    ]
    for (a, b), (y0, y1), (z0, z1), p in cuff_straw:
        m.box("straw", (17.75 + a, y0, z0), (17.75 + b + 0.25, y1, z1), p)
        m.box("straw", (-1.75 - b - 0.25, y0, z0), (-1.75 - a, y1, z1), p)
    # out of the shirt tail: stalks hanging below the hem all round
    tail = [(3, 5, 6.25), (4.5, 4.75, 5.5), (6, 5, 6.5), (9, 5, 6), (10.5, 4.75, 5.25), (12, 5, 6.75)]
    for (x, z, y0) in tail:
        m.box("straw", (x, y0, z), (x + 1, 8, z + 1), STRAW if x % 3 else STRAW_DARK)
        m.box("straw", (x + 0.5, y0 + 0.25, 15.25 - z), (x + 1.5, 8, 16.25 - z), STRAW_DARK if x % 3 else STRAW)
    for (z, y0) in ((6, 6), (8.5, 6.5)):
        m.box("straw", (2.75, y0, z), (3.75, 8, z + 1), STRAW)
        m.box("straw", (12.25, y0 + 0.5, z + 0.5), (13.25, 8, z + 1.5), STRAW)
    # out of the collar, under the head
    for (x0, x1, y1, z0, z1) in ((3.5, 4.5, 20, 6, 7), (4, 5, 19.5, 8.5, 9.5), (11.5, 12.5, 20, 6.5, 7.5),
                                 (11, 12, 19.5, 9, 10), (6, 7, 19.5, 10.25, 11.25), (9, 10, 19.75, 10.25, 11.25)):
        m.box("straw", (x0, 18.5, z0), (x1, y1, z1), STRAW)

    # --- the sack head --------------------------------------------------------------------------------
    m.group("head")
    m.box("neck", (5, 18.5, 5), (11, 19.5, 11), SACK)
    m.box("tie", (4.5, 18.25, 4.5), (11.5, 19.25, 11.5), ROPE)
    # the face (column 0 at the viewer's left, x 12..13; rows from y 26 down): S thread, T a stitch across it,
    # P a rosy cheek (the eyes are buttons standing out of it)
    face = ["..........",   # y 26
            "..........",   # y 25  eyes (buttons)
            "..........",   # y 24  eyes
            ".P......P.",   # y 23
            "..........",   # y 22
            ".S.T..T.S.",   # y 21  the smile's ends turned up, a stitch across it each side
            "..SSSSSS..",   # y 20
            ]
    art = {(12 - col, 26 - row): ch for row, line in enumerate(face) for col, ch in enumerate(line) if ch != "."}

    def face_decal(c):
        if c.face == "north":
            ch = art.get((c.i[0], c.i[1]))
            if ch == "S":
                return ("wood_dark", 0)
            if ch == "T":
                return ("wood_dark", 1)
            if ch == "P":
                return ("pink", 2)
        return None
    head = SACK(decal=face_decal)
    m.box("head", (3, 19.5, 3), (13, 27, 13), head)
    m.box("head", (2, 20, 4), (14, 26.5, 12), SACK)
    m.box("head_top", (3.5, 27, 3.5), (12.5, 28.75, 12.5), SACK)
    m.box("head_ear", (1.5, 21, 5), (2, 25, 11), SACK)              # the sack's corners bulge at the sides
    m.box("head_ear", (14, 21, 5), (14.5, 25, 11), SACK)

    def glint(c):
        if c.face == "north" and c.u == 0 and c.v == 0:
            return ("porcelain", 2)
        return None
    for x in (9, 5):
        m.box("eye", (x, 24, 2.5), (x + 2, 26, 3), Paint("rubber", 1, noise=0, decal=glint, ao=0, rim=0))

    # --- the straw hat, cocked to one side ------------------------------------------------------------
    m.group("hat")
    rot = ("z", -22.5, (8, 28.5, 8))

    def braid(c):
        """Decal: the plaited straw, a darker ring every 3 px round the crown's middle."""
        r = math.hypot(c.p[0] - 8, c.p[2] - 8)
        if c.face == "up" and math.floor(r) % 3 == 0:
            return ("straw", 2)
        if c.face != "up" and c.face != "down" and math.floor(c.p[1]) % 2 == 0 and c.p[1] > 29:
            return ("straw", 2)
        return None
    hat = Paint("straw", 3, noise=0, decal=braid, rim=0.8)
    rcyl(m, "brim", (8, 8), 18, 27.5, 28.5, hat, rot)
    rcyl(m, "band", (8, 8), 10, 28.5, 29.75, flat("denim", 1, rim=0.6), rot)
    rcyl(m, "crown", (8, 8), 10, 29.75, 31.5, hat, rot)
    rcyl(m, "crown_top", (8, 8), 8, 31.5, 32, hat, rot)
    # a little sunflower tucked in the band, on its front
    rbox(m, "flower", (9, 28.25, 2.5), (11.5, 30.25, 3), flat("gold", 1, ao=0), rot)
    rbox(m, "flower_eye", (9.75, 28.75, 2.25), (10.75, 29.75, 2.5), flat("fox", 1, ao=0), rot)
    return display(m, (1, 2, 1), 0)


# ================================================================================================
# Registry
# ================================================================================================

PIECES = {
    # id: (builder, cells as (w, h, d) blocks, floor)
    "scarecrow": (scarecrow, (1, 2, 1), 0),
}

ALL = {k: v[0] for k, v in PIECES.items()}
SIZES = {k: v[1] for k, v in PIECES.items()}
REAL = {"scarecrow": "높이 1.5~2 m, 팔 폭 1.5 m"}
