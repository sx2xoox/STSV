"""
숨은 비밀 기념비 stones (economy: /관리자 비밀 비석 세우기; plugin dev.stsv.economy.secret.Monument): one stone per secret,
ten in an arc. Each stone is ONE item display of one of these models, a text display above it (the secret's name, the
solver, the date, or "???").

  secret_stone          unsolved: an old weathered tablet with a round arched top, a chipped shoulder and a nick in its
                        side, moss on the top and round its foot, a pale lichen stain, rain streaks down the face; a
                        worn carved frame and, faintly, a "?" groove (no words: nobody has solved it yet); a rough
                        stone plinth with moss lumps.
  secret_stone_solved   solved: the same silhouette in polished dark stone (blackstone / obsidian-like, gloss streaks),
                        a thin gold inlay border following the arch, a small gold star engraved at the top over a short
                        gold rule; a clean two-step plinth with a gold trim line. The generic fallback.
  secret_stone_solved_<id>  the same, with that secret's own gold emblem in place of the star (EMBLEMS: bus sunglasses,
                        dongbang anchor, letter envelope, designer gear, usher keyhole, fish fish, vein heart, lostkey
                        key, doll a four-hole button, scarecrow a wheat ear).

    drawn at real size (16 px a block): the stone's middle at x = z = 8, the plinth on the ground at y = 0, the face to
    the north (-z: what the item display turns towards the way the entity looks, so Monument gives each stone the yaw
    that faces the arc's centre); the tablet 16 wide, 24 tall over a 4 px plinth (top at y 28 = 1.75 blocks), 5 deep.
    It fits -16..32 as drawn (scale 1): Monument shows it at display scale 1, lifted 0.5 (FIXED draws model (8, 8, 8)
    on the entity), so y 0 is the entity's feet.

Painted with the bake helpers (one shared pixel grid, baked light; the pictures are world-space decals). Its own ramps
(mn_*) are added to the palette here (like the donation cars' rd_*).
"""

import math

from stsv_assets import presets
from stsv_assets.bake import BModel, Paint, h3, vnoise
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp


def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


# (hue, chroma, centre lightness, span, steps, accent)
_ramp("mn_stone", 105, 0.014, 0.58, 0.30, 6, False)    # weathered grey granite, a touch warm
_ramp("mn_moss", 128, 0.095, 0.50, 0.30, 5, False)     # moss: dark to sunlit green
_ramp("mn_lichen", 100, 0.060, 0.68, 0.16, 3, False)    # pale yellow-green lichen
_ramp("mn_black", 285, 0.022, 0.32, 0.30, 6, False)    # polished black stone, a hint of violet

# the stone's numbers (drawn px)
X0, X1 = 0, 16               # tablet width
Z0, Z1 = 6, 11               # tablet depth: the face at z 6
FOOT, TOP = 4, 28            # tablet bottom (on the plinth) and top
ARCH = 20                    # the arch's springing line: a semicircle of radius 8 above it


def _arch_hw(y):
    """Half width of the tablet's row y (its texel row y .. y + 1)."""
    if y < ARCH:
        return 8
    d = y + 0.5 - ARCH
    return max(1, min(8, round(math.sqrt(max(0.0, 64 - d * d)))))


def _rows(chips):
    """Per row y: (x0, x1) of the tablet; chips: {y: (dx0, dx1)} bites out of the edges."""
    out = {}
    for y in range(FOOT, TOP):
        hw = _arch_hw(y)
        a, b = 8 - hw, 8 + hw
        da, db = chips.get(y, (0, 0))
        out[y] = (a + da, b - db)
    return out


def _tablet(m, rows, paint):
    """The tablet as boxes: runs of rows with the same span merged."""
    ys = sorted(rows)
    i = 0
    while i < len(ys):
        j = i
        while j + 1 < len(ys) and rows[ys[j + 1]] == rows[ys[i]]:
            j += 1
        a, b = rows[ys[i]]
        m.box("tablet", (a, ys[i], Z0), (b, ys[j] + 1, Z1), paint)
        i = j + 1


def _inside(rows, x, y):
    r = rows.get(y)
    return r is not None and r[0] <= x < r[1]


def _inset(rows, x, y, k):
    """Whether the face texel (x, y) is at least k texels in from the silhouette (every neighbour within k inside)."""
    return all(_inside(rows, x + dx, y + dy) for dx in range(-k, k + 1) for dy in range(-k, k + 1))


def _face_xy(c):
    """A front-face texel's cell (x, y)."""
    return int(math.floor(c.p[0])), int(math.floor(c.p[1]))


# --- the unsolved stone ------------------------------------------------------------------------------------------------
# the viewer's right is -x: the chipped shoulder is on the right, the nick in the left side
OLD_CHIPS = {24: (1, 0), 25: (1, 0), 26: (1, 0), 11: (0, 1), 12: (0, 1)}

# the "?" groove, 7 x 10, top row first (as the viewer sees it: left to right)
QMARK = (
    ".#####.",
    "##...##",
    "##...##",
    ".....##",
    "....##.",
    "...##..",
    "...##..",
    ".......",
    "...##..",
    "...##..",
)
Q_TOP = 21                   # the glyph's top row (y of its upper texel row + 1)


def _qmark(x, y):
    """Whether face cell (x, y) is in the "?" groove (centred, as the viewer sees it: u = 15 - x)."""
    u = 15 - x - 4
    v = Q_TOP - 1 - y
    if 0 <= v < len(QMARK) and 0 <= u < 7:
        return QMARK[v][u] == "#"
    return False


def _moss(c, x, y, z):
    """Moss: on what faces up (the arch, the plinth), creeping up from the foot, a little in the face's hollows."""
    v = vnoise(x + 0.5, y + 0.5, z + 0.5, 2.6, 7)
    w = vnoise(x + 0.5, y + 0.5, z + 0.5, 1.3, 8)
    if c.face == "up":
        t = 0.50 if y >= FOOT else 0.56
    elif y >= 25:                # round the arch: a little down its edge
        t = 0.66
    elif FOOT <= y < FOOT + 3:   # up from the plinth
        t = 0.56 + 0.06 * (y - FOOT)
    elif y < 1:
        t = 0.60
    else:
        t = 0.95
    s = v * 0.7 + w * 0.3
    if s > t:
        step = 3 if c.face == "up" and w > 0.55 else 2 if w > 0.45 else 1
        if s > t + 0.12 and c.face == "up":
            step += 1
        return ("mn_moss", step)
    return None


def _lichen(x, y):
    """A pale lichen stain low on the face's left (as the viewer sees it): a blotch with a speckled edge."""
    dx, dy = (x - 11.5) / 3.4, (y - 8.0) / 2.6
    r = math.sqrt(dx * dx + dy * dy) + (vnoise(x, y, 0, 2.2, 9) - 0.5) * 1.0
    if r < 0.65:
        return ("mn_lichen", 1 if h3(x, y, 0, 10) < 0.7 else 2)
    if r < 1.15 and h3(x, y, 0, 11) < 0.5:
        return ("mn_lichen", 0)
    return None


def old_decal(rows):
    def decal(c):
        x, y = _face_xy(c)
        z = int(math.floor(c.p[2]))
        mo = _moss(c, x, y, z)
        if mo:
            return mo
        if c.face != "north":
            # rain streaks run down the sides too, faintly
            if c.side and h3(x + z, 0, 0, 13) < 0.12 and y > 10 + 10 * h3(x + z, 0, 0, 14):
                return ("mn_stone", 2)
            return None
        li = _lichen(x, y)
        if li:
            return li
        # the "?" groove: worn (some texels filled in), its lower edge catching the light
        if _qmark(x, y):
            if h3(x, y, 0, 15) < 0.22:
                return ("mn_stone", 2)
            return ("mn_stone", 1)
        if _qmark(x, y + 1) and h3(x, y, 0, 16) < 0.7:
            return ("mn_stone", 4)
        # a carved frame 2 in from the edge, mostly worn away
        if _inset(rows, x, y, 2) and not _inset(rows, x, y, 3) and y > FOOT + 2:
            if h3(x, y, 0, 17) < 0.55:
                return ("mn_stone", 2)
        # rain streaks down the face from the top
        col = h3(x, 0, 0, 18)
        if col < 0.22 and y > 12 + 12 * h3(x, 0, 0, 19) and h3(x, y, 0, 20) < 0.85:
            return ("mn_stone", 2)
        # pits, and a few soft patches where the weather has darkened it
        r = h3(x, y, 0, 21)
        if r < 0.04:
            return ("mn_stone", 1)
        if vnoise(x + 0.5, y + 0.5, 0, 4.0, 23) < 0.2:
            return ("mn_stone", 2)
        return None
    return decal


def old_plinth_decal(c):
    x, y = int(math.floor(c.p[0])), int(math.floor(c.p[1]))
    z = int(math.floor(c.p[2]))
    mo = _moss(c, x, y, z)
    if mo:
        return mo
    if c.side and y == 3 and h3(x + z, y, 0, 22) < 0.35:    # chipped top edge
        return ("mn_stone", 1)
    return None


def secret_stone():
    m = BModel("secret_stone", floor=0)
    m.display = presets.furniture(gui_scale=0.4)
    rows = _rows(OLD_CHIPS)
    stone = Paint("mn_stone", 3, noise=0.10, decal=old_decal(rows), grad=0.6)
    m.group("tablet")
    _tablet(m, rows, stone)
    m.group("plinth")
    rough = Paint("mn_stone", 2, "stone", decal=old_plinth_decal, cell=2.0, seed=3)
    m.box("plinth", (-2, 0, 3), (18, 3, 14), rough)
    m.box("plinth_top", (-1, 3, 4), (17, 4, 13), rough)
    m.group("moss")
    moss = Paint("mn_moss", 2, "speckle", seed=5)
    m.box("moss", (10, 4, 4), (15, 5, 6), moss)
    m.box("moss", (0, 4, 11), (4, 5, 13), moss)
    return m


# --- the solved stone --------------------------------------------------------------------------------------------------
GOLD = "gold"

# a small five-pointed star, 7 x 7 (top row first)
STAR = (
    "...#...",
    "..###..",
    "#######",
    ".#####.",
    "..###..",
    ".##.##.",
    ".#...#.",
)
STAR_TOP = 25                # the star's top row (y of its upper texel row + 1)


# --- the per-secret emblems ---------------------------------------------------------------------------------------------
# Each solved secret's stone carries its own gold emblem in place of the star (economy:model/secret_stone_solved_<id>;
# Monument picks it by the stone's id). Glyphs are drawn as the viewer sees them, top row first, at most 10 x 10, centred
# in the face between the inlay border; "#" gold (shaded by its edges: lit above, darker below and on the right), "+" a
# bright glint, "o" dark gold (a line inside the gold), "." bare stone.
EMBLEMS = {
    # 선글라스 (the bus secret): two dark lenses in a gold frame, a bridge and the arms' ends
    "bus": (
        "#........#",
        "##########",
        "#oooo#oooo",
        "#+ooo#+ooo",
        "#oooo#oooo",
        ".####.####",
    ),
    # 닻
    "dongbang": (
        "...####...",
        "...#..#...",
        "...####...",
        ".########.",
        "....##....",
        "....##....",
        "#...##...#",
        "##..##..##",
        ".###########"[:10],
        "..######..",
    ),
    # 봉투: the edge and the flap's V
    "letter": (
        "##########",
        "##+....+##",
        "#.##..##.#",
        "#..####..#",
        "#...##...#",
        "#........#",
        "#........#",
        "##########",
    ),
    # 톱니: eight teeth round a ring with a hole
    "designer": (
        "....##....",
        ".##.##.##.",
        ".########.",
        "..##..##..",
        "####..####",
        "####..####",
        "..##..##..",
        ".########.",
        ".##.##.##.",
        "....##....",
    ),
    # 열쇠 구멍: a gold escutcheon with the keyhole cut through it
    "usher": (
        "..######..",
        ".########.",
        "####..####",
        "###....###",
        "###....###",
        "####..####",
        "####..####",
        "###....###",
        ".########.",
        "..######..",
    ),
    # 물고기: swimming to the viewer's left, an eye, a forked tail
    "fish": (
        "..####...#",
        ".######.##",
        "#.#######.",
        "#########.",
        ".######.##",
        "..####...#",
    ),
    # 심장
    "vein": (
        ".###..###.",
        "#+########",
        "#+########",
        "##########",
        ".########.",
        "..######..",
        "...####...",
        "....##....",
    ),
    # 열쇠: the bow on top, the bit at the foot
    "lostkey": (
        "..####..",
        ".##..##.",
        ".##..##.",
        "..####..",
        "...##...",
        "...##...",
        "...####.",
        "...##...",
        "...####.",
        "...###..",
    ),
    # 단추: the doll's button eye, a rim and four holes
    "doll": (
        "..######..",
        ".#oooooo#.",
        "#o######o#",
        "#o#.##.#o#",
        "#o######o#",
        "#o######o#",
        "#o#.##.#o#",
        "#o######o#",
        ".#oooooo#.",
        "..######..",
    ),
    # 밀 이삭: grains in pairs up a stalk, an awn on top
    "scarecrow": (
        "....#.....",
        "....##....",
        "..#.##.#..",
        ".##.##.##.",
        "..#.##.#..",
        ".##.##.##.",
        "..#.##.#..",
        ".##.##.##.",
        "....##....",
        "....##....",
    ),
}
EM_TOP = 24                  # an emblem's 10-row box: rows y 23 .. 14 (its glyph centred in it)
EM_RULE = 12                 # the short gold rule under it


def _glyph_at(glyph, top, box_w, box_h):
    """A lookup (x, y) -> the glyph's char (or None) for a glyph centred in a box_w x box_h box whose top row is top - 1."""
    h, w = len(glyph), max(len(r) for r in glyph)
    v0, u0 = (box_h - h) // 2, (box_w - w) // 2
    left = 8 + box_w // 2 - 1                  # the box's viewer-left column (x is mirrored: u = left - x)

    def at(x, y):
        u, v = left - x - u0, top - 1 - y - v0
        if 0 <= v < h and 0 <= u < len(glyph[v]):
            ch = glyph[v][u]
            return None if ch == "." else ch
        return None
    return at


def _gold(at, x, y):
    """The gold step of an emblem texel: "#" shaded by its neighbours (as the viewer sees it, left is +x)."""
    ch = at(x, y)
    if ch == "+":
        return 3
    if ch == "o":
        return 0
    up, down, left, right = at(x, y + 1), at(x, y - 1), at(x + 1, y), at(x - 1, y)
    if (down is None and up is not None) or (right is None and left is not None):
        return 1
    if up is None and down is not None:
        return 3
    return 2


def new_decal(rows, emblem=None):
    """The solved face: the gold inlay, the emblem (the star when none) over a short gold rule, a gloss band."""
    if emblem is None:
        at, rule = _glyph_at(STAR, STAR_TOP, 7, 7), STAR_TOP - 9
        flat = True                            # the generic star: flat gold as before
    else:
        at, rule = _glyph_at(EMBLEMS[emblem], EM_TOP, 10, 10), EM_RULE
        flat = False

    def decal(c):
        x, y = _face_xy(c)
        if c.face != "north":
            return None
        # the gold inlay: a ring 1 texel wide, 1 in from the edge, round the arch
        if _inset(rows, x, y, 1) and not _inset(rows, x, y, 2) and y > FOOT:
            return (GOLD, 2 if y > 14 else 1)
        if at(x, y):
            return (GOLD, 2 if flat else _gold(at, x, y))
        if at(x, y + 1):                   # the cut's shadow under the emblem
            return ("mn_black", 0)
        if y == rule and 5 <= x <= 10:     # a short rule under it
            return (GOLD, 1 if x in (5, 10) else 2)
        # a soft gloss: the polish catches the light in one short diagonal band, high on the viewer's left
        s = x - y * 0.9
        if -12.5 <= s < -10.5 and 10 <= y <= 19 and _inset(rows, x, y, 3):
            return ("mn_black", 3)
        return None
    return decal


def new_plinth_decal(c):
    if c.side and 2 <= c.p[1] < 2.75:
        return (GOLD, 2)
    return None


def secret_stone_solved(emblem=None):
    m = BModel("secret_stone_solved" + (f"_{emblem}" if emblem else ""), floor=0)
    m.display = presets.furniture(gui_scale=0.4)
    rows = _rows({})
    polish = Paint("mn_black", 2, "lacquer", noise=0.0, decal=new_decal(rows, emblem), grad=0.6)
    m.group("tablet")
    _tablet(m, rows, polish)
    m.group("plinth")
    base = Paint("mn_black", 1, "lacquer", noise=0.0, seed=3)
    m.box("plinth", (-2, 0, 3), (18, 2, 14), base)
    m.box("plinth_top", (-1, 2, 4), (17, 4, 13), Paint("mn_black", 1, "lacquer", noise=0.0, seed=4, decal=new_plinth_decal))
    return m


ALL = {"secret_stone": secret_stone, "secret_stone_solved": secret_stone_solved}
ALL.update({f"secret_stone_solved_{k}": (lambda k=k: secret_stone_solved(k)) for k in EMBLEMS})
