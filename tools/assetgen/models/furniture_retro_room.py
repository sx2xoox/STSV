"""
Retro Korean furniture, 방·거실 (content/stsv/items/furniture_retro_room.yml): ten pieces of an 80s-90s Korean home,
built as baked models (stsv_assets/bake.py) with the same conventions as models/furniture_japanese.py (16 px a
block, front = north (z 0), anchor cell x 0..16 / z 0..16, y 0 its floor; model +x is the viewer's LEFT; every
piece centred in its cells; collision from the model: a cell gets a barrier when the model fills 30% of it).
No real brand, logo or product design is copied.

Faces read from the front use col = (right edge x) - x (left to right as seen) and row = (top y) - y.
Animated textures (no entities): the wardrobe's mother-of-pearl shimmers in a sweep of light, the TV's screen
glows and a soft bright band rolls down it, the clock's pendulum swings, the fan's blades turn.

PIECES: id -> (builder, cells as (w, h, d) for the previews, floor offset)
"""

import math

from economy_machines import display
from stsv_assets.bake import BModel, Paint, cyl, h3, ring, voxels

# ================================================================================================
# Paints
# ================================================================================================

LACQ = Paint("rubber", 1, "lacquer", noise=0.02)               # 옻칠: black lacquer
WOOD = Paint("wood", 2, "grain", axis="y", noise=0)
WALNUT = Paint("plush", 1, "grain", axis="y", noise=0)          # glossy brown furniture wood
DARK = Paint("wood_dark", 1, "grain", axis="long", noise=0.03)
BRASS = Paint("brass", 2, "metal")
CHROME = Paint("blade", 2, "metal")
IRON = Paint("iron", 2, "metal")
CREAM = Paint("cream", 2, noise=0.02)


def flat(ramp_name, step, **kw):
    """A plain paint without pattern or noise (small parts, cloth)."""
    return Paint(ramp_name, step, noise=0, **kw)


CLEAR = ("raw", (0, 0, 0, 0))


def lace(x0, z0, x1, z1, cell=2.0):
    """Decal of a crocheted lace doily lying flat: a scalloped edge and a grid of little holes (cut-out)."""
    def decal(c):
        if c.face not in ("up", "down"):
            return ("feather", 1)
        x, z = c.p[0], c.p[2]
        ex, ez = min(x - x0, x1 - x), min(z - z0, z1 - z)
        e = min(ex, ez)
        if e < 0.5:
            # the scallops: every other texel along the edge
            along = c.i[0] if ez < ex else c.i[2]
            return ("feather", 2) if along % 2 == 0 else CLEAR
        if e < 1.0:
            return ("feather", 2)
        i, k = c.i[0], c.i[2]
        if (i % 3 == 1) and (k % 3 == 1):
            return CLEAR                                   # the little holes of the crochet
        if (i + k) % 6 == 0:
            return ("feather", 1)
        return ("feather", 2)
    return decal


def lace_flap(axis, lo, hi, top):
    """Decal of lace hanging over an edge (a flap on a vertical plane): scalloped bottom, holes in a row."""
    def decal(c):
        if c.face in ("up", "down"):
            return ("feather", 2)
        y = c.p[1]
        along = c.i[0] if axis == "x" else c.i[2]
        depth = top - y
        if depth > hi - lo - 0.5:
            return ("feather", 2) if along % 2 == 0 else CLEAR
        return ("feather", 1) if along % 2 == 0 and depth > hi - lo - 1 else ("feather", 2)
    return decal


def floral(base_ramp, base_step, flower_ramp, flower_step, centre_ramp, centre_step, period=4, seed=0, leaf=None):
    """Decal of a printed floral cloth: a small five-pixel flower every `period` texels (rows offset by half),
    with an optional leaf pixel beside it."""
    def decal(c):
        i, j, k = c.i
        if c.face in ("up", "down"):
            u, v = i, k
        elif c.face in ("north", "south"):
            u, v = i, j
        else:
            u, v = k, j
        row = math.floor(v / period)
        cu = period * math.floor((u + (row % 2) * period / 2) / period) - (row % 2) * period / 2
        du, dv = u - cu - period // 2, v - row * period - period // 2
        if du == 0 and dv == 0:
            return (centre_ramp, centre_step)
        if abs(du) + abs(dv) == 1:
            return (flower_ramp, flower_step)
        if leaf and du == 1 and dv == 1 and h3(i, j, k, seed) < 0.6:
            return leaf
        return (base_ramp, base_step)
    return decal


# ================================================================================================
# 자개 (mother-of-pearl inlay): pictures in shell on black lacquer, shimmering in a sweep of light
# ================================================================================================

SHELL_FRAMES, SHELL_TIME = 16, 3

# The doors' pictures, 6 x 16 each (p pearl, k pink shell, a abalone, c brown shell, y gold shell)
DOOR_ART = [
    [   # a crane under the moon, standing in the grass
        "....pp",
        "...ppp",
        "....pp",
        "......",
        "..k...",
        "..ppp.",
        "..p...",
        "..p...",
        ".ppp..",
        "ppppp.",
        ".pppp.",
        "..ppp.",
        "..p.p.",
        "..p.p.",
        "aaa.aa",
        "aaaaaa",
    ],
    [   # a pine
        "......",
        "..aa..",
        ".aaaa.",
        "aaaaaa",
        "...c..",
        ".aaaa.",
        "aaaaaa",
        "..c...",
        "..ca..",
        ".aaaaa",
        "aaaaaa",
        "...c..",
        "..cc..",
        "..c...",
        ".ccc..",
        "cc.cc.",
    ],
    [   # peonies
        "......",
        "..k...",
        ".kpk..",
        "kpkpk.",
        ".kpk..",
        "..a...",
        ".a.a..",
        "a..a.a",
        "...aa.",
        "..kkk.",
        ".kpppk",
        ".kpkpk",
        "..kkk.",
        "...a..",
        "..aaa.",
        "...a..",
    ],
    [   # butterflies over bamboo
        "y.y...",
        ".k....",
        "y.y...",
        "....a.",
        "...aa.",
        "....a.",
        "....a.",
        "...aaa",
        "....a.",
        "..p.a.",
        ".ppaa.",
        "..p.a.",
        "....a.",
        "...aa.",
        "....a.",
        "....a.",
    ],
]
FLOWER = [".k.", "kpk", ".k."]
# the blanket chest's two doors, 5 x 8 each
CHEST_ART = [
    ["..k..", ".kpk.", "..k..", "..a..", ".aaa.", "a.a.a", "..a..", "....."],
    ["p.p..", ".k...", "p.p..", "...k.", "..kpk", "...k.", "...a.", "..aaa"],
]


def shell(ch, col, row, frame):
    """(ramp, step) of one inlay texel: iridescent by a fixed per-texel hash, brighter in the passing sweep."""
    s = (col + row * 0.7) - (frame / SHELL_FRAMES) * 70 + 18
    sweep = abs(s) < 1.6
    r = h3(col, row, ord(ch), 7)
    if ch == "p":
        if sweep:
            return ("feather", 3)
        return ("ice", 3) if r < 0.18 else ("sakura", 3) if r < 0.32 else ("pearl", 2)
    if ch == "k":
        return ("sakura", 3 if sweep else 2)
    if ch == "a":
        if sweep:
            return ("spectral", 3)
        return ("spectral", 1) if r < 0.5 else ("celadon", 3)
    if ch == "c":
        return ("whetstone", 4 if sweep else 3)
    if ch == "y":
        return ("gold", 3)
    return None


# The wardrobe's doors, 6 x 16 each, drawn in fine lines of nacre: p bright pearl (petals, the crane's body),
# s a 1 px line (stems, branches, the moon, the crane's neck and legs), k a pale pink petal, l a pale green leaf
NACRE_ART = [
    [   # a crane under a crescent moon, reeds at its feet
        "...ss.",
        "..s...",
        "..s...",
        "...ss.",
        "......",
        ".k....",
        ".p....",
        ".s....",
        ".s....",
        ".pp...",
        "ppps..",
        ".pp.s.",
        "..s...",
        "..s...",
        "s.ss.s",
        "ls..sl",
    ],
    [   # a pine: three tiers of needles on a crooked trunk
        "......",
        ".lppl.",
        "l.pp.l",
        "...s..",
        "..s...",
        "lppl..",
        ".pp.l.",
        "..s...",
        "...s..",
        ".lppl.",
        "l.pp.l",
        "...s..",
        "...s..",
        "..ss..",
        ".s..s.",
        "......",
    ],
    [   # two peonies on one stem
        "......",
        "..pp..",
        ".pkkp.",
        ".pkkp.",
        "..pp..",
        "...s..",
        "..ss..",
        ".l.s..",
        "ll.s.l",
        "...sll",
        "..pp..",
        ".pkkp.",
        "..pp..",
        "..s...",
        ".ls...",
        "..s...",
    ],
    [   # a butterfly over bamboo
        "p.p...",
        ".s....",
        "k.k...",
        "....s.",
        "...ls.",
        "....s.",
        "....p.",
        "..l.s.",
        ".ll.s.",
        "....s.",
        "....p.",
        "....sl",
        "....sl",
        "....s.",
        "....p.",
        "....s.",
    ],
]
NACRE_FLOWER = [".k.", "kpk", ".s."]
NACRE_TINTS = [("pearl", 2), ("sakura", 3), ("porcelain", 2), ("crystal", 4), ("musk", 4), ("ice", 3)]


def nacre(ch, col, row, frame):
    """(ramp, step) of one texel of mother-of-pearl: milky white to pale silver, soft pastel tints (pink, green,
    violet, ice) sliding across it as the light moves, and a white glint in the passing sweep."""
    s = (col + row * 0.7) - (frame / SHELL_FRAMES) * 70 + 18
    if abs(s) < 1.2:
        return ("feather", 3)                                  # the glint of the sweep
    band = (col * 0.5 + row * 0.35 - frame * 0.75) % 6
    tint = NACRE_TINTS[int(band)]
    soft = h3(col, row, 3, 11) < 0.7                           # most texels keep their own pearl colour
    if ch == "p":
        return ("pearl", 2) if soft else tint
    if ch == "s":
        return ("porcelain", 1) if soft else ("pearl", 1) if tint[0] == "pearl" else (tint[0], tint[1] - 1)
    if ch == "k":
        return ("sakura", 3) if soft else ("pink", 3)
    if ch == "l":
        return ("musk", 4) if soft else ("musk", 3)
    return None


def picture(rows, col, row, c0, r0):
    """The character of a bitmap at (col, row) placed with its top-left at (c0, r0), or None."""
    j, i = row - r0, col - c0
    if 0 <= j < len(rows) and 0 <= i < len(rows[0]):
        ch = rows[j][i]
        return ch if ch != "." else None
    return None


def border(col, row, c0, r0, c1, r1, frame, dotted=True):
    """A thin inlaid border line on the rectangle (c0, r0)-(c1, r1) (inclusive), or None."""
    on = (row in (r0, r1) and c0 <= col <= c1) or (col in (c0, c1) and r0 <= row <= r1)
    if not on:
        return None
    if dotted and (col + row) % 2:
        return None
    return shell("p", col, row, frame)


# ================================================================================================
# 방 · 거실
# ================================================================================================

def jagae_wardrobe():
    """자개장 2x1, 높이 2: a black lacquered wardrobe of four framed doors over two drawers on short feet, inlaid
    with mother-of-pearl (a crane under the moon, a pine, peonies, butterflies over bamboo; flowers on the drawers;
    a dotted shell border round it all and a shell line round each side); it shimmers as a sweep of light crosses
    it. A gilt line round the cornice, brass hinges, round handle plates with drop rings, a lock plate, drawer
    pulls."""
    m = BModel("jagae_wardrobe")
    m.group("body")
    # front face: x 2..30 (col 0..27 left to right), y 3..29.5 (row 0..26 top to bottom)
    X1, Y1 = 30, 29.5

    def front(c):
        if c.face != "north":
            return ("rubber", 1)
        col, row = math.floor(X1 - c.p[0]), math.floor(Y1 - c.p[1])
        k = c.frame
        if row == 20 or col in (6, 13, 14, 21):
            return ("rubber", 0)                                   # the gaps round the doors and drawers
        on = row in (0, 26) or col in (0, 27)
        if on:
            return nacre("s", col, row, k) if (col + row) % 2 == 0 else ("rubber", 1)   # a dotted nacre border
        if row < 20:
            for d, c0 in enumerate((0, 7, 15, 22)):
                if c0 <= col < c0 + 6:
                    if row in (1, 18) or col in (c0, c0 + 5) and 1 <= row <= 18:
                        return ("rubber", 2)                       # each door's raised edge
                    ch = picture(NACRE_ART[d], col, row, c0, 2)
                    if ch:
                        return nacre(ch, col, row, k)
            return ("rubber", 1)
        for c0 in (2, 10, 16, 23):                                 # a flower either side of each pull
            ch = picture(NACRE_FLOWER, col, row, c0, 22)
            if ch:
                return nacre(ch, col, row, k)
        return ("rubber", 1)
    # 0.66 block deep (z 2.75..13.25), the cornice overhanging front and back
    m.box("carcass", (2, 3, 2.75), (30, 29.5, 13.25), LACQ)
    m.box("front", (2, 3, 2.5), (30, 29.5, 2.75), Paint("rubber", 1, noise=0, rim=0.5, decal=front,
                                                        frames=SHELL_FRAMES, frametime=SHELL_TIME),
          faces={f: LACQ for f in ("south", "east", "west", "up", "down")})

    m.box("cornice", (1, 29.75, 2), (31, 31.5, 14), LACQ)
    m.box("gilt_rim", (0.75, 29.5, 1.75), (31.25, 29.75, 14.25), Paint("gold", 1, "metal", rim=0.4))   # a thin gilt rim
    m.box("plinth", (1.5, 2, 2.5), (30.5, 3, 13.5), LACQ)

    def side(c):
        if c.face in ("east", "west"):
            z, y = c.p[2], c.p[1]
            if min(z - 4, 12 - z) < 0.5 and 5 < y < 28 or min(y - 5, 28 - y) < 0.5 and 4 < z < 12:
                return nacre("s", c.i[2], c.i[1], 0)            # a nacre line framing the sides
        return None
    m.box("carcass_side", (1.75, 3, 3.25), (2, 29.5, 12.75), LACQ(decal=side))
    m.box("carcass_side", (30, 3, 3.25), (30.25, 29.5, 12.75), LACQ(decal=side))
    m.group("feet")
    foot = LACQ(rim=1.2)
    for (x0, x1) in ((1.5, 4.5), (27.5, 30.5)):
        for (z0, z1) in ((2.5, 5), (11, 13.5)):
            m.box("foot", (x0, 0.75, z0), (x1, 2, z1), foot)
            m.box("foot_toe", (x0 + 0.5, 0, z0 + 0.5), (x1 - 0.5, 0.75, z1 - 0.5), foot)
    m.group("brass")
    bright = Paint("brass", 3, "metal", rim=0.6)
    for (x0, x1) in ((28.5, 29.5), (2.5, 3.5)):                     # hinges on the outer doors
        for y in (11.5, 25.5):
            m.box("hinge", (x0, y, 2.25), (x1, y + 1.5, 2.5), BRASS)
    for x in (18.25, 13.75):                                        # round handle plates by the middle seam
        m.box("plate", (x - 1.25, 16.5, 2.25), (x + 1.25, 19, 2.5), BRASS)
        m.box("plate_rim", (x - 0.75, 19, 2.25), (x + 0.75, 19.25, 2.5), BRASS)
        m.box("plate_rim", (x - 0.75, 16.25, 2.25), (x + 0.75, 16.5, 2.5), BRASS)
        m.box("ring", (x - 0.75, 15, 2), (x + 0.75, 15.25, 2.25), bright)
        m.box("ring", (x - 0.75, 15.25, 2), (x - 0.5, 16.75, 2.25), bright)
        m.box("ring", (x + 0.5, 15.25, 2), (x + 0.75, 16.75, 2.25), bright)
    m.box("lock", (15, 21.5, 2.25), (17, 24.5, 2.5), BRASS(decal=lambda c: ("rubber", 0) if c.face == "north" and 15.75 < c.p[0] < 16.25
                                                                   and 22.25 < c.p[1] < 23.5 else None))
    for x in (22.5, 9.5):                                           # drawer pulls
        m.box("pull_plate", (x - 1.5, 5.5, 2.25), (x + 1.5, 6.75, 2.5), BRASS)
        m.box("pull", (x - 1.25, 4.75, 2), (x + 1.25, 5.25, 2.25), bright)
    return display(m, (2, 2, 1), 0)


def crt_tv():
    """브라운관 TV 1x1, 높이 2: a bulging cathode-ray TV in a wood-grain case on a little cabinet with sliding doors
    and splayed legs: the glass glows (a soft picture of hills and sky under scan lines, a brighter band rolling
    down it, a faint flicker), a channel dial and a volume knob, a speaker grille, a lace doily over its top and
    a pair of rabbit-ear aerials. No broadcast, logo or brand."""
    m = BModel("crt_tv")
    m.group("cabinet")
    top = Paint("wood", 2, "planks", axis="x", width=3)
    m.box("cabinet", (2, 4, 3.25), (14, 13, 12.75), WOOD)
    m.box("cabinet_top", (1.5, 13, 3), (14.5, 13.5, 13), top)

    def door(x0, x1):
        def decal(c):
            if c.face != "north":
                return None
            if abs(c.p[1] - 8.5) < 0.75 and abs(c.p[0] - (x0 + x1) / 2 - (1.5 if x0 > 7 else -1.5)) < 0.75:
                return ("wood_dark", 0)                            # the round finger pull
            if min(c.p[0] - x0, x1 - c.p[0], c.p[1] - 4.5, 12.5 - c.p[1]) < 0.75:
                return ("wood", 1)                                 # the door's frame
            return None
        return decal
    m.box("door", (7.75, 4.5, 3), (13.5, 12.5, 3.25), WOOD(decal=door(7.75, 13.5)))
    m.box("door", (2.5, 4.5, 3.125), (8.25, 12.5, 3.25), WOOD(decal=door(2.5, 8.25)))
    leg = Paint("wood_dark", 1, "grain", axis="y")
    for (x, z) in ((2.25, 3.75), (12.25, 3.75), (2.25, 10.75), (12.25, 10.75)):
        m.box("leg", (x, 1.5, z), (x + 1.5, 4, z + 1.5), leg)
        m.box("leg", (x + 0.25, 0.5, z + 0.25), (x + 1.25, 1.5, z + 1.25), leg)
        m.box("ferrule", (x + 0.125, 0, z + 0.125), (x + 1.375, 0.5, z + 1.375), Paint("brass", 2, "metal"))
    m.group("tv")
    case = Paint("wood_dark", 2, "grain", axis="z", noise=0)
    m.box("case", (1.5, 13.5, 3.5), (14.5, 25.5, 9.5), case)
    m.box("bell", (3, 14.5, 9.5), (13, 24.5, 11.5), Paint("rubber", 2, noise=0.02))
    m.box("bell", (4.5, 15.5, 11.5), (11.5, 23.5, 13.5), Paint("rubber", 2, noise=0.02))

    # the front: a silver bezel; the screen on the left (x 5..14), the controls on the right (x 1.5..5)
    def bezel(c):
        if c.face != "north":
            return None
        x, y = c.p[0], c.p[1]
        if x < 5:
            if 15 < y < 17.5 and 1.75 < x < 4.75:
                return ("rubber", 1) if c.i[1] % 2 == 0 else ("test_grey", 2)     # speaker slits
            return ("test_grey", 3)
        return ("test_grey", 2)
    m.box("bezel", (1.75, 13.75, 3.25), (14.25, 25.25, 3.5), Paint("test_grey", 3, "metal", decal=bezel, rim=0.6))
    N = 16

    def screen(c):
        x, y = c.p[0], c.p[1]
        col, row = 13.5 - x, 23.75 - y                 # 0..8.5 across, 0..8.5 down
        k = c.frame
        # a soft picture: sky, a sun, hills, a field
        if row < 4.5:
            ramp, v = "ice", 2.6 - row * 0.25
            if math.hypot(col - 6.2, row - 1.8) < 1.1:
                ramp, v = "glow_amber", 2.5
        elif row < 6 - 0.9 * math.sin(col * 0.9 + 0.6):
            ramp, v = "musk", 2.4
        else:
            ramp, v = "leaf", 2.6
        # the CRT's dark corners and its scan lines
        v -= 0.7 * max(0.0, math.hypot(col - 4.25, row - 4.25) - 3.6)
        if c.i[1] % 2:
            v -= 0.45
        band = (row - (k / N) * 13 + 2) % 13
        if band < 1.2:
            v += 0.8                                      # a brighter band rolling down
        v += (0.25, 0, 0, -0.2, 0, 0.1, 0, 0, -0.15, 0, 0.2, 0, 0, -0.1, 0, 0)[k]
        return (ramp, v)
    m.box("screen", (5, 15, 3), (13.5, 23.5, 3.25), Paint("ice", 2, glow=True, light=False, noise=0, decal=screen,
                                                               frames=N, frametime=3),
          faces={f: Paint("rubber", 0, noise=0) for f in ("south", "east", "west", "up", "down")})

    def dial(c):
        if c.face == "north":
            a = math.atan2(c.p[1] - 21.25, c.p[0] - 3.25)
            if math.hypot(c.p[0] - 3.25, c.p[1] - 21.25) > 1.0 and int((a + math.pi) / (2 * math.pi) * 12) % 2 == 0:
                return ("feather", 2)                      # the channel numbers round the dial
        return None
    cyl(m, "dial", (3.25, 21.25), 3, 2.5, 3.25, Paint("rubber", 2, "metal", decal=dial), axis="z")
    m.box("dial_grip", (2.75, 20.25, 2.25), (3.75, 22.25, 2.5), flat("rubber", 0))
    cyl(m, "knob", (3.25, 18.5), 2, 2.75, 3.25, Paint("blade", 2, "metal"), axis="z")
    m.box("power", (2.25, 23.5, 3), (4.25, 24.25, 3.25), flat("signal_red", 1))
    m.group("doily")
    m.box("doily", (3, 25.5, 4), (13, 25.75, 11.5), Paint("feather", 2, noise=0, light=False, decal=lace(3, 4, 13, 11.5)))
    m.box("doily_flap", (5, 24.5, 3), (11, 25.75, 3.25), Paint("feather", 2, noise=0, light=False,
                                                                    decal=lace_flap("x", 24.5, 25.75, 25.75)))
    m.group("aerial")
    m.box("aerial_base", (6.75, 25.75, 8.25), (9.25, 26.75, 10.25), Paint("rubber", 2, "metal"))
    rod = Paint("blade", 3, "metal", rim=0.4)
    for ang in (22.5, -22.5):
        m.box("aerial", (7.75, 26.5, 9), (8.25, 31.5, 9.5), rod, rotation=("z", ang, (8, 26.75, 9.25)))
        m.box("aerial_tip", (7.625, 31.5, 8.875), (8.375, 32, 9.625), rod, rotation=("z", ang, (8, 26.75, 9.25)))
    return display(m, (1, 2, 1), 0)


def dial_phone():
    """다이얼 전화기 1x1: a little telephone table on four slender legs with a phone book on its shelf, a crocheted
    lace doily over the top and a red rotary phone on it: the dial with its finger holes, the handset in its cradle,
    a coiled cord, and a memo pad with a pen."""
    m = BModel("dial_phone")
    m.group("table")
    top = Paint("wood", 2, "planks", axis="x", width=3)
    m.box("top", (3, 9.25, 3), (13, 10.25, 13), top)
    leg = Paint("wood_dark", 1, "grain", axis="y")
    for (x, z) in ((3.5, 3.5), (11.25, 3.5), (3.5, 11.25), (11.25, 11.25)):
        m.box("leg", (x, 0.5, z), (x + 1.25, 9.25, z + 1.25), leg)
        m.box("leg_foot", (x + 0.125, 0, z + 0.125), (x + 1.125, 0.5, z + 1.125), leg)
    m.box("apron", (4.75, 8.25, 3.5), (11.25, 9.25, 4), DARK)
    m.box("shelf", (4.75, 3, 4.75), (11.25, 3.5, 11.25), top)
    m.box("book", (5.5, 3.5, 5.5), (10.5, 5.5, 10.5), flat("petal_yellow", 1, rim=0.7),
          faces={"north": flat("paper", 2), "east": flat("paper", 2)})
    m.group("doily")
    m.box("doily", (2.5, 10.25, 2.5), (13.5, 10.5, 13.5), Paint("feather", 2, noise=0, light=False, decal=lace(2.5, 2.5, 13.5, 13.5)))
    flap = Paint("feather", 2, noise=0, light=False, decal=lace_flap("x", 8.5, 10.5, 10.5))
    m.box("doily_flap", (4, 8.5, 2.25), (12, 10.5, 2.5), flap)
    m.box("doily_flap", (4, 8.5, 13.5), (12, 10.5, 13.75), flap)
    m.group("phone")
    # about 22 cm wide on a 50 cm table: the body 6 px wide, its front sloping up 45 degrees under the dial
    red = Paint("fabric_red", 2, "lacquer", noise=0.02)
    m.box("base", (4.75, 10.5, 5.75), (11.25, 11, 11.75), Paint("fabric_red", 1, "lacquer", noise=0.02))
    for k in range(3):                                    # the body under the slope, stepped behind it
        m.box("slope", (5, 11 + k, 7 + k), (11, 12 + k, 9.75), red)
    m.box("housing", (5, 11, 9.75), (11, 14.75, 11.5), red)
    m.box("housing_top", (5.5, 14.75, 10), (10.5, 15.25, 11.25), red)
    tip = ("x", 45, (8, 11, 6.5))                         # the slope's plate and everything on it
    m.box("slope_plate", (5, 11, 6), (11, 16.5, 6.5), red, rotation=tip)
    # the dial: a round pale disc (built from half-pixel rows), ten finger holes in a ring round its edge with the
    # gap at four o'clock where the silver finger stop is, the red label in the middle
    cy = 13.75
    pale = flat("porcelain", 2)
    for (y0, y1, hw) in ((-2.5, -2, 1), (-2, -1.5, 1.75), (-1.5, -1, 2.25), (-1, 1, 2.5),
                         (1, 1.5, 2.25), (1.5, 2, 1.75), (2, 2.5, 1)):
        m.box("dial", (8 - hw, cy + y0, 5.75), (8 + hw, cy + y1, 6), pale, rotation=tip)
    hole = flat("rubber", 1)
    for n in range(10):                                   # viewer's angle, counter-clockwise from 1 o'clock
        ang = math.radians(30 + 30 * n)
        hx, hy = 8 - 1.75 * math.cos(ang), cy + 1.75 * math.sin(ang)
        m.box("finger_hole", (round(hx - 0.25, 3), round(hy - 0.25, 3), 5.625),
              (round(hx + 0.25, 3), round(hy + 0.25, 3), 5.75), hole, rotation=tip)
    m.box("label", (7.625, cy - 0.375, 5.625), (8.375, cy + 0.375, 5.75), flat("fabric_red", 1), rotation=tip)
    sa = math.radians(-25)
    sx, sy = 8 - 2.25 * math.cos(sa), cy + 2.25 * math.sin(sa)
    m.box("finger_stop", (round(sx - 0.375, 3), round(sy - 0.25, 3), 5.5), (round(sx + 0.375, 3), round(sy + 0.25, 3), 5.75),
          Paint("blade", 2, "metal"), rotation=tip)
    # the handset lying across the cradle on top, its two ends out past the sides
    for x0 in (5.25, 10):
        m.box("cradle", (x0, 15.25, 10.25), (x0 + 0.75, 15.75, 11), red)
    m.box("handset", (4.75, 15.75, 10.25), (11.25, 16.25, 11), red)
    for x0 in (3.75, 11):
        m.box("handset_end", (x0, 15.25, 10), (x0 + 1.25, 16.5, 11.25), red)
    coil = Paint("fabric_red", 1, noise=0)
    n = 9
    for k in range(n):                                    # the coiled cord from the handset down to the table
        y1 = 15.25 - k * (4.75 / n)
        y0 = 15.25 - (k + 1) * (4.75 / n)
        dz = 0.25 if k % 2 else 0
        m.box("cord", (4, round(y0, 3), 10.5 + dz), (4.75, round(y1, 3), 11 + dz), coil)
    m.group("memo")

    def lines(c):
        if c.face == "up" and c.i[2] % 2 == 1 and c.p[2] > 4.25:
            return ("denim", 3)                            # ruled lines
        return None
    m.box("memo", (11.25, 10.5, 3.75), (13, 10.75, 6.25), flat("paper", 2, decal=lines))
    m.box("pen", (11.25, 10.5, 6.75), (13.5, 10.75, 7.25), flat("sapphire", 1),
          faces={"east": flat("rubber", 2)})
    return display(m, (1, 1, 1), 0)


def wall_clock():
    """괘종시계 1x1, 높이 2: a wooden pendulum wall clock hung high on the wall (like the scroll): an arched crown
    with a finial, a cream dial in a brass bezel with the hands at three o'clock (an L that reads as a clock even this
    small), a glazed door over the brass pendulum swinging behind (and the chime rods), and a carved base with a
    drop. Pass under it."""
    m = BModel("wall_clock", floor=None, planes=(("z>", 16),))
    case = Paint("plush", 1, "grain", axis="y", noise=0.04)
    trim = Paint("plush", 0, "grain", axis="x", noise=0.03)
    m.group("case")
    # the head with the dial (y 22..28.5): b brass bezel, c cream, k the hands at ten past ten, o the centre
    DIAL = [".bbbb.", "bckccb", "bckkkb", "bccccb", "bccccb", ".bbbb."]

    def face(c):
        if c.face != "north":
            return None
        col, row = math.floor(11 - c.p[0]), math.floor(28 - c.p[1])
        ch = picture(DIAL, col, row, 0, 0)
        return {"b": ("brass", 2), "c": ("dial", 1), "k": ("rubber", 0)}.get(ch)
    m.box("head", (4, 22, 12.5), (12, 28.5, 16), case(decal=face))
    m.box("head_mould", (3.5, 21.5, 12.25), (12.5, 22, 16), trim)
    m.box("crown", (3.5, 28.5, 12.25), (12.5, 29.25, 16), trim)
    m.box("crown", (4.25, 29.25, 12.75), (11.75, 30, 16), case)
    m.box("crown", (5.5, 30, 13.25), (10.5, 30.75, 16), case)
    m.box("finial", (7.25, 30.75, 13.75), (8.75, 31.5, 15.25), Paint("brass", 2, "metal"))
    m.box("finial", (7.625, 31.5, 14.125), (8.375, 32, 14.875), Paint("brass", 3, "metal"))
    for x in (3.5, 11.5):                                  # turned columns either side of the door
        m.box("column", (x, 15.5, 12.5), (x + 1, 21.5, 13.5), Paint("plush", 2, "grain", axis="y"))
    # the waist with the pendulum window (y 15.5..21.5)
    m.box("stile", (4, 15.5, 13), (5, 21.5, 16), case)
    m.box("stile", (11, 15.5, 13), (12, 21.5, 16), case)
    m.box("rail", (5, 20.75, 13), (11, 21.5, 16), case)
    m.box("rail", (5, 15.5, 13), (11, 16.25, 16), case)
    N = 16

    def pendulum(c):
        if c.face != "north":
            return None
        th = 0.32 * math.sin(2 * math.pi * c.frame / N)
        px, py = 8.0, 20.75                               # the pivot
        L = 3.3
        bx, by = px + L * math.sin(th), py - L * math.cos(th)
        dx, dy = c.p[0] - px, c.p[1] - py
        along = dx * math.sin(th) - dy * math.cos(th)
        across = abs(dx * math.cos(th) + dy * math.sin(th))
        if math.hypot(c.p[0] - bx, c.p[1] - by) < 1.15:
            return ("brass", 3 if c.p[0] > bx else 2)     # the bob, lit from the left
        if 0 <= along <= L and across < 0.3:
            return ("brass", 1)
        if abs(c.p[0] - 5.75) < 0.3 or abs(c.p[0] - 10.25) < 0.3:
            return ("gold", 2) if c.p[1] > 17.5 else ("wood_dark", 0)    # the chime rods
        return ("wood_dark", 0 if (c.i[0] % 3 == 0) else 1)
    m.box("pendulum", (5, 16.25, 15), (11, 20.75, 16), Paint("wood_dark", 1, noise=0, decal=pendulum, frames=N, frametime=2),
          faces={f: Paint("wood_dark", 0, noise=0) for f in ("south", "east", "west", "up", "down")})

    def glass(c):
        if c.face not in ("north", "south"):
            return ("glass", 2)
        d = c.p[0] + c.p[1]
        if 23 < d < 24 or 25.5 < d < 26:
            return ("glass", 3)                            # light on the glass
        if c.p[1] < 17 and c.i[0] % 2 == 0:
            return ("gold", 2)                             # a gilt band etched along the bottom
        return CLEAR
    m.box("glass", (5, 16.25, 13), (11, 20.75, 13.25), Paint("glass", 2, noise=0, light=False, decal=glass))
    # the base (y 13..15.5) and its drop
    m.box("base", (3.5, 15, 12.25), (12.5, 15.5, 16), trim)
    m.box("base", (4.25, 14, 12.75), (11.75, 15, 16), case)
    m.box("base", (5.5, 13.25, 13.25), (10.5, 14, 16), case)
    m.box("drop", (7.25, 12.5, 13.75), (8.75, 13.25, 15.25), Paint("brass", 2, "metal"))
    return display(m, (1, 2, 1), 0)


def blanket_chest():
    """이불장 1x1, 높이 2: a black lacquered chest with two mother-of-pearl doors below and an open shelf above,
    piled with folded quilts (pink silk with flowers, red and green brocade, a cream one with blue sprigs) and two
    round-ended pillows with embroidered ends on top."""
    m = BModel("blanket_chest")
    m.group("chest")

    def doors(c):
        if c.face != "north":
            return ("rubber", 1)
        col, row = math.floor(14.5 - c.p[0]), math.floor(13.5 - c.p[1])   # 13 x 12
        if col == 6:
            return ("rubber", 0)
        b = border(col, row, 0, 0, 12, 11, c.frame)
        if b:
            return b
        for d, c0 in enumerate((1, 7)):
            if c0 <= col < c0 + 5:
                ch = picture(CHEST_ART[d], col, row, c0, 2)
                if ch:
                    return shell(ch, col, row, c.frame)
        return ("rubber", 1)
    m.box("carcass", (1.5, 1.5, 3.25), (14.5, 14, 13), LACQ)
    m.box("front", (1.5, 1.5, 3), (14.5, 13.5, 3.25), Paint("rubber", 1, noise=0, rim=0.5, decal=doors,
                                                             frames=SHELL_FRAMES, frametime=SHELL_TIME),
          faces={f: LACQ for f in ("south", "east", "west", "up", "down")})
    for x in (7, 9):
        m.box("knob", (x - 0.5, 7, 2.75), (x + 0.5, 8, 3), BRASS)
    for x0 in (1.5, 13):
        for z0 in (2.75, 11.75):
            m.box("foot", (x0, 0, z0), (x0 + 1.5, 1.5, z0 + 1.5), LACQ)
    m.group("shelf")
    m.box("side", (1.5, 14, 3), (2.5, 28.25, 13), LACQ)
    m.box("side", (13.5, 14, 3), (14.5, 28.25, 13), LACQ)
    m.box("back", (2.5, 14, 12), (13.5, 28.25, 13), LACQ)
    m.box("roof", (1.25, 28.25, 2.75), (14.75, 29.25, 13.25), LACQ(decal=lambda c: ("gold", 1) if c.face == "north" and c.i[0] % 2 == 0 else None))
    m.box("shelf_lip", (2.5, 13.75, 2.75), (13.5, 14.25, 3.25), BRASS)
    m.group("quilts")
    layers = [  # y0, y1, inset, (base ramp, step, flower ramp, step, centre ramp, step)
        (14.25, 17, 0, ("pink", 2, "porcelain", 2, "gold", 2)),
        (17, 19.5, 0.25, ("sign_green", 2, "fabric_red", 2, "gold", 2)),
        (19.5, 22.25, 0, ("fabric_red", 2, "gold", 2, "pink", 3)),
        (22.25, 24.5, 0.5, ("cream", 2, "denim", 2, "sakura", 2)),
    ]
    for k, (y0, y1, inset, (br, bs, fr, fs, cr, cs)) in enumerate(layers):
        flowers = floral(br, bs, fr, fs, cr, cs, period=4, seed=k)

        def cloth(c, y0=y0, y1=y1, br=br, bs=bs, flowers=flowers):
            if c.face == "north" and abs(c.p[1] - (y0 + y1) / 2) < 0.3:
                return (br, bs - 1)                        # the fold along the front
            return flowers(c)
        m.box("quilt", (2.75 + inset, y0, 3.25 + inset), (13.25 - inset, y1, 11.75), Paint(br, bs, noise=0, rim=0.7, decal=cloth))
    m.group("pillows")

    def pillow_end(c):
        if c.face in ("east", "west"):
            r = math.hypot(c.p[1] - 26, c.p[2] - (6.25 if c.p[2] < 8.5 else 10.25))
            if r < 0.6:
                return ("gold", 3)
            if r < 1.2:
                return ("leaf", 2) if c.i[1] % 2 else ("fabric_red", 2)
            return ("sakura", 2)
        if c.i[0] % 3 == 0:
            return ("pink", 2)                             # a satin stripe
        return None
    for z in (6.25, 10.25):
        cyl(m, "pillow", (26, z), 3, 3.5, 12.5, Paint("sakura", 3, noise=0, rim=0.6, decal=pillow_end), axis="x")
    return display(m, (1, 2, 1), 0)


def meal_table():
    """밥상 1x1: a round folding table of stamped aluminium (a printed flower in the middle, a raised rim) with a
    meal laid on it: a steel bowl heaped with rice, a bowl of doenjang soup with spring onion, kimchi on a plate,
    a fried egg, a stack of laver, and a spoon and chopsticks."""
    m = BModel("meal_table")
    m.group("table")

    def stamped(c):
        if c.face != "up":
            return None
        r = math.hypot(c.p[0] - 8, c.p[2] - 8)
        if abs(r - 3.0) < 0.35 or abs(r - 5.6) < 0.35:
            return ("fish_silver", 2)                      # pressed rings
        if r < 2.4:
            a = math.atan2(c.p[2] - 8, c.p[0] - 8)
            petal = 1.5 + 0.8 * math.cos(5 * a)
            if r < 0.7:
                return ("gold", 2)
            if r < petal:
                return ("pink", 2)                         # the printed flower in the middle
        return ("fish_silver", 3) if (c.i[0] + c.i[2]) % 5 else ("fish_silver", 4)
    cyl(m, "top", (8, 8), 15, 5, 5.5, Paint("fish_silver", 3, "metal", decal=stamped))
    ring(m, "rim", (8, 8), 15, 13.5, 5.5, 6, Paint("fish_silver", 3, "metal", rim=1.2))
    leg = Paint("steel", 3, "metal")
    for (x, z) in ((3.75, 3.75), (11.25, 3.75), (3.75, 11.25), (11.25, 11.25)):
        m.box("leg", (x - 0.5, 0, z - 0.5), (x + 0.5, 5, z + 0.5), leg)
    m.box("brace", (4.25, 2, 3.5), (10.75, 2.5, 4), leg)
    m.box("brace", (4.25, 2, 12), (10.75, 2.5, 12.5), leg)
    m.group("meal")
    steel = Paint("blade", 3, "metal", rim=0.8)

    def rice(c):
        return ("rice", 2) if c.face != "up" or (c.i[0] + c.i[2]) % 3 else ("rice", 1)
    cyl(m, "rice_bowl", (10.5, 11), 4, 5.5, 7.75, steel)
    cyl(m, "rice", (10.5, 11), 4, 7.75, 8.25, flat("rice", 2, decal=rice))
    cyl(m, "rice", (10.5, 11), 3, 8.25, 8.75, flat("rice", 2, decal=rice))

    def soup(c):
        if c.face == "up":
            if h3(c.i[0], 0, c.i[2], 3) < 0.22:
                return ("sprout", 3)                       # spring onion
            if h3(c.i[0], 1, c.i[2], 4) < 0.15:
                return ("bean", 2)                         # tofu
            return ("doenjang", 2)
        return None
    cyl(m, "soup_bowl", (5.5, 11), 4, 5.5, 7.25, steel)
    ring(m, "soup_rim", (5.5, 11), 4, 3, 7.25, 7.75, steel)
    cyl(m, "soup", (5.5, 11), 3, 7.25, 7.5, flat("doenjang", 2, decal=soup))
    cyl(m, "plate", (11, 7), 4, 5.5, 6.25, Paint("porcelain", 2, noise=0))

    def kimchi(c):
        return ("pepper", 2) if h3(c.i[0], c.i[1], c.i[2], 9) < 0.7 else ("cabbage", 2)
    m.box("kimchi", (9.75, 6.25, 6), (12.25, 6.75, 8), flat("pepper", 2, decal=kimchi))
    m.box("kimchi", (10.25, 6.75, 6.5), (11.75, 7.25, 7.5), flat("pepper", 2, decal=kimchi))
    cyl(m, "egg_plate", (5, 7), 4, 5.5, 6.25, Paint("porcelain", 2, noise=0))
    m.box("egg_white", (3.5, 6.25, 6), (6.5, 6.5, 8), flat("feather", 2))
    m.box("egg_white", (4, 6.25, 5.5), (6, 6.5, 6), flat("feather", 2))
    m.box("egg_white", (4, 6.25, 8), (6, 6.5, 8.5), flat("feather", 2))
    m.box("yolk", (4.5, 6.5, 6.5), (5.75, 7, 7.75), flat("corn", 2))
    m.box("laver", (7.25, 5.5, 6), (8.75, 6.25, 8), flat("felt", 0, rim=0.8))
    m.box("spoon", (5, 5.5, 3.25), (9.5, 5.75, 3.75), steel)
    m.box("spoon_bowl", (3.75, 5.5, 3), (5, 5.875, 4), steel)
    for z in (4.125, 4.625):
        m.box("chopstick", (5, 5.5, z), (11, 5.75, z + 0.25), steel)
    return display(m, (1, 1, 1), 0)


def retro_sofa():
    """꽃무늬 소파 2x1: a two-seat sofa in wine-red velvet printed with gold and pink flowers, piped cushions and a
    tufted back, carved wooden arms on tapered legs, and a crocheted lace cover over each back cushion. Sit on it."""
    m = BModel("retro_sofa")
    flowers = floral("wine", 2, "gold", 2, "pink", 3, period=5, seed=1, leaf=("sign_green", 2))
    velvet = Paint("wine", 2, noise=0.02, rim=0.7, decal=flowers)
    m.group("frame")
    leg = Paint("wood_dark", 1, "grain", axis="y")
    for (x, z) in ((3, 3.5), (27.5, 3.5), (3, 11.25), (27.5, 11.25)):
        m.box("leg", (x, 0, z), (x + 1.5, 2.25, z + 1.25), leg)
    m.box("base", (3, 2.25, 3), (29, 5, 13.25), velvet)

    def apron(c):
        if c.face == "north" and (c.i[0] % 4 == 0) and c.p[1] < 3:
            return ("wood_dark", 0)                        # the carved scallops of the front rail
        return None
    m.box("apron", (3.5, 2, 2.75), (28.5, 3.25, 3), DARK(decal=apron))
    m.group("seat")

    def piped(x0, x1, top):
        def decal(c):
            if c.face == "up" and min(c.p[0] - x0, x1 - c.p[0], c.p[2] - 3, 11.25 - c.p[2]) < 0.5:
                return ("gold", 1)
            if c.face == "north" and abs(c.p[1] - top) < 0.4:
                return ("gold", 1)                         # the piping along the edge
            return flowers(c)
        return decal
    for (x0, x1) in ((3.5, 15.75), (16.25, 28.5)):
        m.box("cushion", (x0, 5, 2.75), (x1, 7.5, 11.5), velvet)
        m.box("cushion_top", (x0, 7.5, 2.75), (x1, 7.75, 11.5), Paint("wine", 2, noise=0, decal=piped(x0, x1, 7.6)))
        m.box("cushion_face", (x0, 5, 2.5), (x1, 7.75, 2.75), velvet(decal=lambda c: ("gold", 1) if abs(c.p[1] - 7.5) < 0.3 else flowers(c)))
    m.group("back")
    m.box("back", (3, 5, 11.5), (29, 13.75, 13.25), velvet)
    for (x0, x1) in ((3.75, 15.75), (16.25, 28.25)):
        def tufts(c, x0=x0, x1=x1):
            if c.face == "north":
                u, v = c.p[0] - x0, c.p[1] - 7.75
                if abs(u % 4 - 2) < 0.5 and abs(v % 3 - 1.5) < 0.5:
                    return ("wine", 0)                     # buttons pulled into the velvet
            return flowers(c)
        m.box("back_cushion", (x0, 7.75, 10.25), (x1, 13.25, 11.5), velvet(decal=tufts))
        m.box("back_round", (x0 + 0.5, 13.25, 10.5), (x1 - 0.5, 14, 13), velvet)
    m.group("arms")
    arm = Paint("wood", 1, "grain", axis="z")
    for (x0, x1) in ((1, 3.5), (28.5, 31)):
        m.box("arm_side", (x0 + 0.5, 2.25, 3), (x1 - 0.5, 9.5, 13.25), velvet)
        m.box("arm_rest", (x0, 9.5, 2.25), (x1, 10.5, 13.75), arm)
        m.box("arm_scroll", (x0 + 0.25, 7.25, 2.25), (x1 - 0.25, 9.5, 3), arm)
        m.box("arm_post", (x0 + 0.5, 2.25, 2.5), (x1 - 0.5, 7.25, 3), DARK)
    m.group("lace")
    for (x0, x1) in ((6, 13.5), (18.5, 26)):
        m.box("lace", (x0, 14, 10.5), (x1, 14.25, 13), Paint("feather", 2, noise=0, light=False, decal=lace(x0, 10.5, x1, 13)))
        m.box("lace_flap", (x0, 12.25, 10), (x1, 14.25, 10.25), Paint("feather", 2, noise=0, light=False,
                                                                          decal=lace_flap("x", 12.25, 14.25, 14.25)))
    return display(m, (2, 1, 1), 0)


def electric_fan():
    """선풍기 1x1: a standing electric fan in ivory: a round base with a row of piano-key switches (one pressed),
    a pole with a chrome collar, and the head tipped a little up on its neck: a round chrome wire cage (the rim, a
    domed front grille of a ring and eight spokes round a blue badge, the back grille) round three blue blades
    turning (a spin blur), the rounded motor behind with its oscillation knob on top."""
    # drawn round x 8.5 (so the cage's spokes fall on one pixel column), then moved back half a pixel
    m = BModel("electric_fan", shift=(-0.5, 0, 0))
    CX, CY = 8.5, 12.5                                     # the head's centre
    m.group("base")
    cyl(m, "base", (CX, 8), 9, 0, 1.25, CREAM)
    cyl(m, "base", (CX, 8), 7, 1.25, 1.75, CREAM)
    m.box("keys_bank", (5.5, 0.5, 4.25), (11.5, 2, 6.5), CREAM)
    for k, (tip, down) in enumerate((("sapphire", False), ("leaf", True), ("pepper", False), ("rubber", False))):
        x = 10.25 - k * 1.5
        y1 = 2.25 if down else 2.75
        m.box("key", (x, 2, 4.5), (x + 1.25, y1, 6.25), flat("cream", 3, rim=0.6),
              faces={"north": flat(tip, 2)})
    m.group("pole")
    m.box("pole", (7.75, 1.75, 7.5), (9.25, 7.5, 9), CREAM)
    m.box("collar", (7.5, 5, 7.25), (9.5, 5.75, 9.25), CHROME)
    m.box("neck", (7.5, 7.5, 7.25), (9.5, 9, 9.25), CREAM)

    # everything above the neck tips up together round the top of the neck
    plain_box = m.box
    tilt = ("x", 22.5, (CX, 9, 8.25))

    def head_box(name, frm, to, paint, faces=None, rotation=None, **kw):
        return plain_box(name, frm, to, paint, faces=faces, rotation=tilt, **kw)
    m.box = head_box
    m.group("motor")
    m.box("yoke", (7.75, 9, 7.75), (9.25, 10, 9.75), CREAM)
    cyl(m, "motor", (CX, CY), 6, 7.5, 10, CREAM, axis="z")                   # the rounded motor housing
    cyl(m, "motor_back", (CX, CY), 4, 10, 11, CREAM, axis="z")
    cyl(m, "motor_cap", (CX, CY), 2, 11, 11.5, Paint("cream", 1), axis="z")
    m.box("knob_stem", (8.25, 15.5, 8.75), (8.75, 16, 9.25), flat("cream", 1))
    m.box("knob", (8, 16, 8.5), (9, 16.75, 9.5), flat("cream", 3))
    m.box("hub", (8, 12, 6), (9, 13, 7.75), Paint("blade", 2, "metal"))
    m.group("blades")
    N = 12

    def blades(c):
        dx, dy = c.p[0] - CX, c.p[1] - CY
        r = math.hypot(dx, dy)
        if c.face not in ("north", "south") or r > 5.3:
            return CLEAR
        if r < 0.8:
            return ("sapphire", 1)
        a = math.atan2(dy, dx) + (2 * math.pi / 3) * c.frame / N
        s = (a % (2 * math.pi / 3)) / (2 * math.pi / 3)    # 0..1 round each blade's sector
        if s < 0.42:
            return ("water", 3 if s < 0.22 else 2)         # the blade
        if s < 0.58:
            return ("water", 1)                            # its blur
        return CLEAR
    m.box("blades", (CX - 5.25, CY - 5.25, 5.5), (CX + 5.25, CY + 5.25, 6),
          Paint("water", 2, noise=0, light=False, decal=blades, frames=N, frametime=1))
    m.group("cage")
    wire = ("blade", 2)

    def spoke(dx, dy):
        return abs(dx) < 0.1 or abs(dy) < 0.1 or abs(abs(dx) - abs(dy)) < 0.1

    def grille(part):
        def decal(c):
            if c.face not in ("north", "south"):
                return CLEAR
            dx, dy = c.p[0] - CX, c.p[1] - CY
            r = math.hypot(dx, dy)
            if part == "dome":                             # the front of the dome: the badge, a ring, the inner spokes
                if r < 1.2:
                    return ("sapphire", 2) if r < 0.5 else ("blade", 3)
                if 2.5 <= r < 3.5:
                    return wire
                return CLEAR
            if part == "front":                            # the outer spokes out to the rim
                if 3.5 <= r < 5.5 and spoke(dx, dy):
                    return wire
                return CLEAR
            if 3 <= r < 5.5 and (abs(dx) < 0.1 or abs(dy) < 0.1):  # the back grille: four spokes
                return wire
            return CLEAR
        return decal
    m.box("grille_dome", (CX - 3.5, CY - 3.5, 3.75), (CX + 3.5, CY + 3.5, 4.25),
          Paint("blade", 3, noise=0, light=False, decal=grille("dome")))
    m.box("grille_front", (CX - 5.4, CY - 5.4, 4.5), (CX + 5.4, CY + 5.4, 5),
          Paint("blade", 3, noise=0, light=False, decal=grille("front")))
    m.box("grille_back", (CX - 5.4, CY - 5.4, 7), (CX + 5.4, CY + 5.4, 7.5),
          Paint("blade", 3, noise=0, light=False, decal=grille("back")))
    rim = {}
    for i in range(1, 16):
        for j in range(5, 20):
            if 5.5 <= math.hypot(i + 0.5 - CX, j + 0.5 - CY) < 6.5:
                for k in (5, 6):
                    rim[(i, j, k)] = "rim"
    voxels(m, "rim", rim, lambda _l: CHROME)
    m.box("badge", (8, 12, 3.25), (9, 13, 3.75), flat("sapphire", 2))
    m.box = plain_box
    return display(m, (1, 1, 1), 0)


def dresser():
    """삼면경 화장대 1x1, 높이 2: a glossy walnut dressing table on short legs with drawers and gilt handles, a lace
    runner, perfume bottles, a pink lotion bottle, a powder compact and a comb on top, and a three-way mirror:
    the arched middle glass and two side wings turned in on their hinges."""
    m = BModel("dresser")
    m.group("table")
    for (x, z) in ((2, 4.5), (12.75, 4.5), (2, 11), (12.75, 11)):
        m.box("leg", (x, 0, z), (x + 1.25, 2, z + 1.25), DARK)

    def drawers(c):
        if c.face != "north":
            return None
        i, j = c.i[0], c.i[1]
        if j == 6 or i in (5, 10) or j == 2:
            return ("plush", 0)                            # the gaps between the drawers
        if j == 9:
            return ("plush", 2)                            # the lit top edge of each drawer
        return None
    m.box("cabinet", (1.5, 2, 4), (14.5, 10.25, 12.5), WALNUT(decal=drawers))
    m.box("top", (1, 10.25, 3.25), (15, 11, 12.75), Paint("plush", 2, "lacquer", noise=0.02))
    gold = Paint("gold", 2, "metal")
    for (x, y) in ((12.5, 8.25), (12.5, 4.25), (3.25, 8.25), (3.25, 4.25), (8, 8.25), (8, 4.25)):
        m.box("handle", (x - 0.75, y - 0.25, 3.5), (x + 0.75, y + 0.25, 4), gold)
    m.group("runner")
    m.box("runner", (3.5, 11, 3.75), (12.5, 11.25, 9.5), Paint("feather", 2, noise=0, light=False, decal=lace(3.5, 3.75, 12.5, 9.5)))
    m.box("runner_flap", (3.5, 9.75, 3.5), (12.5, 11.25, 3.75), Paint("feather", 2, noise=0, light=False,
                                                                       decal=lace_flap("x", 9.75, 11.25, 11.25)))
    m.group("things")
    m.box("perfume", (10.5, 11.25, 6), (12, 13, 7.5), Paint("sakura", 2, noise=0, rim=1.3))
    m.box("perfume_cap", (10.75, 13, 6.25), (11.75, 13.75, 7.25), gold)
    m.box("perfume", (12.5, 11.25, 7), (13.5, 12.5, 8), Paint("aurora", 3, noise=0, rim=1.3))
    m.box("perfume_cap", (12.75, 12.5, 7.25), (13.25, 13, 7.75), gold)
    m.box("lotion", (4, 11.25, 6.5), (5.5, 14.25, 8), flat("porcelain", 2))
    m.box("lotion_cap", (4.25, 14.25, 6.75), (5.25, 15.25, 7.75), flat("pink", 2))
    cyl(m, "compact", (7, 5.5), 3, 11.25, 11.75, Paint("gold", 2, "metal", decal=lambda c: ("pink", 2) if c.face == "up" and
                                                                     math.hypot(c.p[0] - 7, c.p[2] - 5.5) < 0.8 else None))
    m.box("comb", (7.5, 11.25, 7.5), (10.5, 11.75, 8.25), flat("crimson", 2, decal=lambda c: ("crimson", 0) if c.face == "north" and c.i[0] % 2 and c.p[1] < 11.5 else None))
    m.group("mirror")
    frame = Paint("plush", 1, "lacquer", noise=0.02)

    def mirror(x0, x1, y0, y1):
        def decal(c):
            if c.face != "north":
                return None
            v = (c.p[1] - y0) / (y1 - y0)
            d = (c.i[0] + c.i[1]) - (x0 + y0 + (x1 - x0) * 0.5 + (y1 - y0) * 0.55)
            if round(d) in (0, 2):
                return ("ice", 3)                          # light across the glass
            return ("glass", 2 if v > 0.5 else 1)
        return decal
    m.box("stand", (3.5, 11, 10.25), (12.5, 12, 12.5), frame)
    m.box("frame", (4.5, 12, 10.75), (11.5, 26, 11.75), frame)
    m.box("glass", (5.25, 12.75, 10.5), (10.75, 25.25, 10.75), Paint("glass", 2, noise=0, decal=mirror(5.25, 10.75, 12.75, 25.25)))
    m.box("arch", (5, 26, 10.75), (11, 27, 11.75), frame)
    m.box("arch", (6, 27, 10.75), (10, 27.75, 11.75), frame)
    m.box("crest", (7.25, 27.75, 10.75), (8.75, 28.75, 11.5), gold)
    for (x0, ang, hinge) in ((11.5, -22.5, 11.5), (1.5, 22.5, 4.5)):
        x1 = x0 + 3
        rot = ("y", ang, (hinge, 18, 11.25))
        m.box("wing", (x0, 13, 10.75), (x1, 24.5, 11.5), frame, rotation=rot)
        m.box("wing_glass", (x0 + 0.5, 13.75, 10.5), (x1 - 0.5, 23.75, 10.75), Paint("glass", 2, noise=0,
                                                                                       decal=mirror(x0 + 0.5, x1 - 0.5, 13.75, 23.75)),
              rotation=rot)
    return display(m, (1, 2, 1), 0)


def sewing_machine():
    """발틀 재봉틀 1x1: a treadle sewing machine: openwork cast-iron sides, the treadle plate, the flywheel with its
    belt, an oak table with a small drawer, and the black machine head with gilt scrolls (no maker's name), a chrome
    hand wheel, the needle and presser foot, and a spool of red thread."""
    m = BModel("sewing_machine")
    m.group("stand")
    iron = Paint("blacksteel", 2, "metal", rim=0.8)

    def openwork(c):
        if c.face not in ("east", "west"):
            return ("blacksteel", 2)
        z, y = c.p[2], c.p[1]
        u, v = (z - 3) / 10, y / 7.5                      # 0..1 across and up
        # two legs, a top bar, a foot bar and a ring of scrolls between them
        if v > 0.88 or v < 0.12:
            return ("blacksteel", 2)
        if u < 0.16 or u > 0.84:
            return ("blacksteel", 2)
        r = math.hypot(u - 0.5, v - 0.5)
        if 0.18 < r < 0.26:
            return ("blacksteel", 3)
        if abs(u - 0.5) < 0.05 and (v > 0.7 or v < 0.3):
            return ("blacksteel", 2)
        return CLEAR
    for x in (2, 13.5):
        m.box("side", (x, 0.75, 3), (x + 0.5, 7.5, 13), Paint("blacksteel", 2, noise=0, decal=openwork))
        m.box("foot", (x - 0.25, 0, 2.75), (x + 0.75, 0.75, 4.5), iron)
        m.box("foot", (x - 0.25, 0, 11.5), (x + 0.75, 0.75, 13.25), iron)

    def grid(c):
        if c.face in ("up", "down") and (c.i[0] % 2 == 0 or c.i[2] % 2 == 0):
            return ("blacksteel", 2)
        if c.face in ("up", "down"):
            return CLEAR
        return ("blacksteel", 2)
    m.box("treadle", (4.5, 1.25, 5), (13, 1.75, 11), Paint("blacksteel", 2, noise=0, decal=grid))
    m.box("treadle_axle", (2.5, 1, 7.75), (13.5, 1.5, 8.25), iron)
    m.box("crossbar", (2.5, 5.5, 7.5), (13.5, 6.25, 8.5), iron)

    def spokes(c):
        if c.face not in ("east", "west"):
            return ("blacksteel", 2)
        dy, dz = c.p[1] - 4, c.p[2] - 8
        r = math.hypot(dy, dz)
        if r > 1.9 or r < 0.6:
            return ("blacksteel", 2)
        a = math.atan2(dy, dz)
        return ("blacksteel", 2) if abs(((a / (2 * math.pi) * 6) + 0.5) % 1 - 0.5) * r < 0.3 else CLEAR
    cyl(m, "flywheel", (4, 8), 5, 3, 3.5, Paint("blacksteel", 2, noise=0, decal=spokes), axis="x")
    m.box("pitman", (3.5, 1.75, 7.75), (4, 4, 8.25), iron)
    m.box("belt", (3.125, 8.5, 10.25), (3.375, 10, 10.5), flat("leather", 1))
    m.group("table")
    m.box("top", (1, 7.5, 3), (15, 8.5, 13), Paint("wood", 3, "planks", axis="x", width=4))
    m.box("apron", (2.5, 6.5, 3.5), (13.5, 7.5, 12.5), WOOD)
    m.box("drawer", (6, 6.5, 3.25), (10, 7.5, 3.5), WOOD(decal=lambda c: ("wood_dark", 0) if c.face == "north" and abs(c.p[0] - 8) < 0.6 else None))
    m.group("head")
    black = Paint("rubber", 2, "lacquer", noise=0.02)

    def scrolls(c):
        if c.face == "north":
            x, y = c.p[0], c.p[1]
            if c.i[1] == 12 and c.i[0] % 3 != 0 and 5.5 < x < 10.5:
                return ("gold", 2)                         # the gilt line along the arm
            if c.i[1] == 13 and c.i[0] % 3 == 1 and 5.5 < x < 10.5:
                return ("gold", 1)
        return None
    m.box("bed", (3, 8.5, 6), (13, 9.25, 11.5), black)
    m.box("slide_plate", (10.25, 9.25, 7), (12.25, 9.375, 9.5), CHROME)
    m.box("pillar", (3.5, 9.25, 7.25), (5.5, 14, 10.25), black)
    m.box("arm", (5.5, 12, 7.25), (10.5, 14, 10.25), black(decal=scrolls))
    m.box("arm_top", (4, 14, 7.5), (12, 14.5, 10), black)
    m.box("head", (10.5, 10.5, 7), (12.75, 14, 10.5), black)
    m.box("face_plate", (10.75, 10.5, 6.75), (12.5, 13.5, 7), CHROME)
    m.box("needle_bar", (11.375, 9.25, 7.75), (11.875, 10.5, 8.25), CHROME)
    m.box("presser_foot", (11, 9.25, 7.25), (12.25, 9.5, 8.5), CHROME)
    cyl(m, "hand_wheel", (12, 8.75), 4, 2.75, 3.5, Paint("blade", 2, "metal"), axis="x")
    m.box("spool", (7.5, 14.75, 8.25), (8.5, 15.75, 9.25), flat("pepper", 2, decal=lambda c: ("pepper", 1) if c.face != "up" and c.i[1] % 2 else None))
    m.box("spool_rim", (7.375, 14.5, 8.125), (8.625, 14.75, 9.375), flat("wood", 2))
    m.box("spool_rim", (7.375, 15.75, 8.125), (8.625, 16, 9.375), flat("wood", 2))
    return display(m, (1, 1, 1), 0)


# ================================================================================================
# Registry
# ================================================================================================

PIECES = {
    # id: (builder, cells as (w, h, d) blocks, floor)
    "jagae_wardrobe": (jagae_wardrobe, (2, 2, 1), 0),
    "crt_tv": (crt_tv, (1, 2, 1), 0),
    "dial_phone": (dial_phone, (1, 1, 1), 0),
    "wall_clock": (wall_clock, (1, 2, 1), 0),
    "blanket_chest": (blanket_chest, (1, 2, 1), 0),
    "meal_table": (meal_table, (1, 1, 1), 0),
    "retro_sofa": (retro_sofa, (2, 1, 1), 0),
    "electric_fan": (electric_fan, (1, 1, 1), 0),
    "dresser": (dresser, (1, 2, 1), 0),
    "sewing_machine": (sewing_machine, (1, 1, 1), 0),
}

ALL = {k: v[0] for k, v in PIECES.items()}
SIZES = {k: v[1] for k, v in PIECES.items()}

# the real thing each piece stands for, roughly (1 block ~ 1 m, a player 1.8 m)
REAL = {
    "jagae_wardrobe": "폭 1.8 m, 높이 1.9 m", "crt_tv": "20인치 TV, 장 포함 높이 1.6 m", "dial_phone": "전화대 높이 65 cm",
    "wall_clock": "폭 50 cm, 길이 1.1 m (벽 높이)", "blanket_chest": "폭 85 cm, 높이 1.8 m", "meal_table": "지름 90 cm, 높이 35 cm",
    "retro_sofa": "폭 1.9 m, 높이 85 cm", "electric_fan": "날개 지름 45 cm, 높이 1 m", "dresser": "폭 85 cm, 거울 위 1.75 m",
    "sewing_machine": "폭 85 cm, 높이 1 m",
}
