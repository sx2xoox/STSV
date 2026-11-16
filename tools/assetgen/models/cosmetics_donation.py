"""
후원 상점 전용 치장 세트 (content/stsv/items/cosmetics_donation.yml, content/stsv/cosmetic_sets.yml): two sets of
five pieces, sold only for 별 (cosmetic.exclusive: donation), every model piece worn at full light (cosmetic.glow)
and most of them moving (cosmetic.motion). Same frames as models/cosmetics.py (attached: the model's (8, 8, 8) on
the slot's anchor, north = the player's front, +z behind them, +x their right). The trail and aura pieces are
particles on the player; their models are only the cards in the menus.

  청룡 (azure_dragon)   여의주 구름 (머리 위, spin) · 청룡 날개 (등, flap) · 청룡 꼬리 (꼬리, sway)
                         · 구름길 (발자취, cloud) · 뇌광 (오라, azure dust)   — set ring: electric sparks at the chest
  은하수 (galaxy)        별빛 관 (머리 위, spin) · 은하수 망토 (등, sway) · 초승달 목걸이 (목)
                         · 별가루 (발자취, end rod) · 별자리 (오라, violet dust) — set ring: end rod sparkles at the feet

Their own animated textures (don_*): a twinkling starfield (the cape, the crown's band), a pearl with light running
round it, and a blue flame (the pearl's flames, the dragon tail's tip).
"""

import math

import economy_models  # noqa: F401  (registers the shared surfaces the machine materials may use)
from economy_machines import MModel, Mat, _canvas, S
from models.cosmetics import _display, pix, mirror, BACK_GUI, HEAD_GUI
from stsv_assets import textures as T
from stsv_assets.palette import ramp


# ================================================================================================
# animated surfaces
# ================================================================================================

def _starfield_frame(k):
    """Night sky that tiles: deep indigo with faint violet nebula wisps; small stars that twinkle in turn and
    two that flare into a cross. 8 frames."""
    im = _canvas()
    px = im.load()
    tau = math.tau
    for y in range(S):
        for x in range(S):
            v = math.sin(tau * x / 16) + math.sin(tau * (x + y) / 16 + 1.3) + 0.8 * math.cos(tau * 2 * y / 16 + 0.4)
            px[x, y] = ramp("test_purple", 0) if v > 2.1 else ramp("indigo", 1) if v > 0.7 else ramp("indigo", 0)
    stars = ((2, 3, 0), (9, 1, 3), (13, 7, 5), (5, 9, 2), (10, 12, 6), (1, 13, 4), (7, 5, 7), (14, 14, 1))
    for (x, y, ph) in stars:
        b = (k + ph) % 8
        px[x, y] = (ramp("glow_cyan", 3) if b == 0 else ramp("lamp", 3) if b in (1, 7) else ramp("indigo", 3) if b in (2, 6)
                    else ramp("indigo", 2))
    for (x, y, ph) in ((5, 12, 0), (12, 3, 4)):         # the flaring ones
        b = (k + ph) % 8
        px[x, y] = ramp("glow_cyan", 3)
        if b in (0, 1, 7):
            arm = ramp("lamp", 3) if b == 0 else ramp("indigo", 3)
            for (dx, dy) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                px[(x + dx) % S, (y + dy) % S] = arm
    return im


def _pearl_frame(k):
    """여의주: milky white-blue with a band of light running round it. 8 frames."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            dx, dy = x - 7.5, y - 7.5
            r = math.hypot(dx, dy)
            a = math.atan2(dy, dx)
            band = math.sin(a + r * 0.55 - k * math.tau / 8)
            if band > 0.75:
                c = ramp("glow_cyan", 3)
            elif band > 0.2:
                c = ramp("ice", 3)
            elif r < 4:
                c = ramp("ice", 2)
            else:
                c = ramp("glow_cyan", 1) if band < -0.6 else ramp("ice", 1)
            px[x, y] = c
    return im


def _azure_flame_frame(k):
    """Cut-out blue flame (crossed planes, like vanilla fire): three tongues that rise and sway, 8 frames."""
    im = _canvas()
    px = im.load()
    for x in range(S):
        h = 8 + 4 * math.sin(x * 0.9 + k * 0.8) + 3 * math.sin(x * 0.37 - k * 0.55)
        h = max(2.0, min(15.0, h * (1 - abs(x - 7.5) / 10)))
        for y in range(S):
            up = S - 1 - y
            if up < h:
                t = up / h
                px[x, y] = (ramp("glow_cyan", 3) if t < 0.3 else ramp("glow_cyan", 2) if t < 0.55
                            else ramp("glow_cyan", 1) if t < 0.8 else ramp("sapphire", 3))
    return im


T.SURFACES["don_starfield"] = lambda: _starfield_frame(0)
T.ANIMATED["don_starfield"] = (lambda: [_starfield_frame(k) for k in range(8)], 4)
T.SURFACES["don_pearl"] = lambda: _pearl_frame(0)
T.ANIMATED["don_pearl"] = (lambda: [_pearl_frame(k) for k in range(8)], 3)
T.SURFACES["don_azure_flame"] = lambda: _azure_flame_frame(0)
T.ANIMATED["don_azure_flame"] = (lambda: [_azure_flame_frame(k) for k in range(8)], 2)


# ================================================================================================
# materials and helpers
# ================================================================================================

SCALE = Mat("sapphire", "weave", 2)
SCALE_DARK = Mat("sapphire", "weave", 1)
BELLY = Mat("ice", "band", 2)
CLOUD = Mat("snow", "plain", 2)
CLOUD_LIGHT = Mat("snow", "plain", 2, bevel=(1, 0))
CLOUD_SHADE = Mat("ice", "plain", 1)
CLOUD_BLUE = Mat("ice", "plain", 0)
HORN = Mat("pearl", "plain", 2)
CYAN = Mat("glow_cyan", "plain", 2, glow=True)
CYAN_WHITE = Mat("glow_cyan", "plain", 3, glow=True, noise=0)
GOLD = Mat("gold", "metal", 2)
GOLD_LIGHT = Mat("gold", "metal", 3)
GOLD_GLOW = Mat("lamp", "plain", 2, glow=True)
STAR_GLOW = Mat("lamp", "plain", 3, glow=True, noise=0)
NAVY = Mat("indigo", "plain", 1)
LINING = Mat("test_purple", "plain", 1)
VIOLET_GLOW = Mat("petal_purple", "plain", 2, glow=True)
MOON = Mat("lamp", "plain", 3, glow=True)
MOON_SHADE = Mat("lamp", "plain", 2, glow=True)


def flames(m, name, cx, y0, cz, w, h, tex="don_azure_flame"):
    """Two crossed cut-out planes of an animated flame, glowing, unshaded."""
    m.box(name, (cx - w / 2, y0, cz), (cx + w / 2, y0 + h, cz), tex, glow=True, shade=False,
          faces={"east": None, "west": None, "up": None, "down": None}, full_uv=("north", "south"))
    m.box(name, (cx, y0, cz - w / 2), (cx, y0 + h, cz + w / 2), tex, glow=True, shade=False,
          faces={"north": None, "south": None, "up": None, "down": None}, full_uv=("east", "west"))


def orb(m, name, c, d, tex, glow=True):
    """A round-looking ball of diameter d: a cube and three crossing slabs (no two faces in one plane)."""
    x, y, z = c
    r, s, t = d * 0.4, d * 0.5, d * 0.24
    every = ("north", "south", "east", "west", "up", "down")
    m.box(name, (x - r, y - r, z - r), (x + r, y + r, z + r), tex, glow=glow, full_uv=every)
    for (ax, ay, az) in ((s, t, t), (t, s, t), (t, t, s)):
        m.box(name, (x - ax, y - ay, z - az), (x + ax, y + ay, z + az), tex, glow=glow, full_uv=every)


def octagon(m, name, a, t, y0, y1, mat, c=(8, 8), faces=None):
    """An upright octagonal band round the y axis: outer faces a from the centre, t thick (inwards), y0..y1.
    The four straight sides and the four corners turned 45 degrees (cleaner than a pixel ring)."""
    cx, cz = c
    half = a * math.tan(math.pi / 8) * 1.04
    for (dx, dz) in ((0, -1), (0, 1), (-1, 0), (1, 0)):
        if dx == 0:
            z0, z1 = (cz - a, cz - a + t) if dz < 0 else (cz + a - t, cz + a)
            m.box(name, (cx - half, y0, z0), (cx + half, y1, z1), mat, faces=faces)
        else:
            x0, x1 = (cx - a, cx - a + t) if dx < 0 else (cx + a - t, cx + a)
            m.box(name, (x0, y0, cz - half), (x1, y1, cz + half), mat, faces=faces)
    r = (a - t / 2) / math.sqrt(2)
    for (sx, sz) in ((1, 1), (-1, -1), (1, -1), (-1, 1)):
        px, pz = cx + sx * r, cz + sz * r
        m.box(name, (px - half, y0, pz - t / 2), (px + half, y1, pz + t / 2), mat, faces=faces,
              rotation=("y", 45 if sx * sz > 0 else -45, (px, (y0 + y1) / 2, pz)))


def cloud_puffs(m, puffs, top=CLOUD, under=CLOUD_SHADE):
    """Cloud lumps (x0, y0, z0, x1, y1, z1): white tops, a blue-grey underside."""
    for (x0, y0, z0, x1, y1, z1) in puffs:
        m.box("cloud", (x0, y0, z0), (x1, y1, z1), top, faces={"down": under})


STAR_ROWS = ["...#...",
             "...#...",
             "..#o#..",
             "##ooo##",
             ".#ooo#.",
             "..#o#..",
             ".##.##.",
             ".#...#."]


def _star_texture():
    """don_star: the five-point star of STAR_ROWS in the top-left 7 x 8 texels (gold, a pale heart), the rest clear."""
    im = _canvas()
    px = im.load()
    for v, row in enumerate(STAR_ROWS):
        for u, ch in enumerate(row):
            if ch != ".":
                px[u, v] = ramp("lamp", 2) if ch == "#" else ramp("lamp", 3)
    return im


T.SURFACES["don_star"] = _star_texture
CLEAR = [15, 15, 16, 16]          # a clear texel of the cut-out textures, for the edges of their thin plates


def _star(m, name, cx, y0, d0, d1, unit, plane="x"):
    """A five-point star standing on y0, centred on cx along its width: one thin cut-out plate (d1 - d0 <= 0.5)
    showing don_star, one texel = unit. plane "x": the star faces north/south and d0..d1 is its z depth; plane "z":
    it faces east/west, cx is a z centre and d0..d1 its x depth."""
    w, h = 7 * unit, 8 * unit
    front, back = [0, 0, 7, 8], [7, 0, 0, 8]
    if plane == "x":
        uv = {"north": back, "south": front, "east": CLEAR, "west": CLEAR, "up": CLEAR, "down": CLEAR}
        m.box(name, (cx - w / 2, y0, d0), (cx + w / 2, y0 + h, d1), "don_star", glow=True, uv=uv)
    else:
        uv = {"east": back, "west": front, "north": CLEAR, "south": CLEAR, "up": CLEAR, "down": CLEAR}
        m.box(name, (d0, y0, cx - w / 2), (d1, y0 + h, cx + w / 2), "don_star", glow=True, uv=uv)


# the dragon wing (right side, before it is swept back): the arm's centre line, the fingers (x, tip y) and the
# membrane panels between them (x from, x to, bottom, top), each drawn as one cut-out plate
def _wing_arm_y(x):
    return 10.0 + (x - 9.5) * 0.36


WING_FINGERS = [(12.5, 6.25), (16.0, 4.0), (19.5, 2.25), (22.5, 0.75)]
_WING_EDGES = [(9.6, 7.75)] + WING_FINGERS
WING_PANELS = [(xa, xb, min(ya, yb), _wing_arm_y(xb) + 0.3) for (xa, ya), (xb, yb) in zip(_WING_EDGES, _WING_EDGES[1:])]


def _web_texture(n):
    """don_wing_web_<n>: panel n of the membrane, texel (u, v) = the point (x from + u, top - v): clear below its
    scalloped edge (rising between the finger tips) and above the arm's line, a glowing cyan rim, a deep band
    over it, sapphire getting lighter towards the arm and a dark vein down the middle."""
    (xa, ya), (xb, yb) = _WING_EDGES[n], _WING_EDGES[n + 1]
    _, _, ybot, ytop = WING_PANELS[n]
    im = _canvas()
    px = im.load()
    vein = int((xb - xa) / 2)
    for v in range(S):
        for u in range(S):
            x, y = xa + u + 0.5, ytop - v - 0.5
            if x > xb + 0.5 or y < ybot - 0.5:
                continue
            t = min(1.0, max(0.0, (x - xa) / (xb - xa)))
            bottom = ya + (yb - ya) * t + 1.5 * math.sin(math.pi * t)
            line = _wing_arm_y(x) + 0.3
            if y < bottom or y > line:
                continue
            if y < bottom + 1:
                c = ramp("glow_cyan", 2)
            elif y < bottom + 2:
                c = ramp("sapphire", 1)
            else:
                f = (y - bottom) / max(0.1, line - bottom)
                c = ramp("sapphire", 3) if f > 0.6 else ramp("sapphire", 2)
                if u == vein and f < 0.9:
                    c = ramp("sapphire", 1)
            px[u, v] = c
    return im


for _n in range(len(WING_PANELS)):
    T.SURFACES[f"don_wing_web_{_n}"] = (lambda n=_n: _web_texture(n))


# ================================================================================================
# 청룡 (azure_dragon)
# ================================================================================================

# 청룡 (azure_dragon) v2 lives in models/cosmetics_azure.py (the donation art rules, nine pieces, awakened looks).


# ================================================================================================
# 은하수 (galaxy)
# ================================================================================================

# 은하수 (galaxy) v2 lives in models/cosmetics_galaxy.py (the donation art rules, nine pieces, awakened looks).


# ================================================================================================
# 아기 오리 가족 (duck_family)
# ================================================================================================

FLUFF = Mat("corn", "plain", 2, noise=0.04)              # warm duckling yellow, soft
FLUFF_LIGHT = Mat("corn", "plain", 3, noise=0.03)
FLUFF_SHADE = Mat("corn", "plain", 1, noise=0.04)
BILL = Mat("fox", "plain", 3, noise=0)
BILL_DARK = Mat("fox", "plain", 2, noise=0)
DUCK_EYE = Mat("rubber", "plain", 0, bevel=None, noise=0)
SHINE = Mat("feather", "plain", 3, bevel=None, noise=0)
BLUSH = Mat("pink", "plain", 2, bevel=None, noise=0)
SAILOR = Mat("snow", "plain", 2)
SAILOR_BAND = Mat("sapphire", "plain", 2)
BUBBLE = Mat("ice", "plain", 3, bevel=None, noise=0)
WATER = Mat("water", "plain", 2, noise=0.05)
WATER_LIGHT = Mat("water", "plain", 3, noise=0.03)
RING_STRIPE = Mat("snow", "plain", 2, noise=0.02)
Z_MARK = Mat("snow", "plain", 2, bevel=None, noise=0)
PET_GUI_D = (25, 210, 0)


def duckling(m, name, o, s=1.0, eyes="open", hat=False, sit=False, slim=False, lite=False):
    """One round duckling facing north (-z): fluffy body, a big head with a tuft, orange bill, shiny eyes and blush,
    stubby wings, a tail flick, flat orange feet. o = (x centre, ground y, z centre of the body), s = scale.
    eyes "open" (shiny) or "shut" (sleeping arcs); hat: a tiny sailor cap; sit: no legs (resting on its belly).
    For a pet's group (its render budget is tight): slim = no middle slab, no lower bill, no legs (the body sits on
    its feet); lite = also no tail flick, tuft, blush or wings (the little ones behind the leader)."""
    ox, oy, oz = o
    lift = -1.0 if sit else -0.8 if slim else 0.0

    def B(a, b, mat, faces=None):
        frm = (ox + a[0] * s, oy + (a[1] + lift) * s, oz + a[2] * s)
        to = (ox + b[0] * s, oy + (b[1] + lift) * s, oz + b[2] * s)
        m.box(name, frm, to, mat, faces=faces)

    # body: a box with a slightly wider middle slab (reads round), a tail flick at the back
    B((-2.5, 1.2, -2.2), (2.5, 4.6, 3.0), FLUFF, faces={"down": FLUFF_SHADE})
    if not slim:
        B((-2.75, 1.8, -1.6), (2.75, 4.0, 2.4), FLUFF)
    if not lite:
        B((-1.0, 3.6, 2.9), (1.0, 5.2, 4.1), FLUFF_LIGHT)
    # head: big, set forward on the body, a tuft on top
    B((-2.1, 4.3, -3.6), (2.1, 8.3, 0.4), FLUFF)
    if not lite:
        B((-0.35, 8.3, -2.2), (0.35, 9.4, -1.4), FLUFF_LIGHT, faces={"down": None})
    # bill: a flat upper bill over a smaller lower one
    B((-1.25, 5.2, -4.8), (1.25, 6.05, -3.55), BILL, faces={"south": None})
    if not slim:
        B((-0.95, 4.75, -4.5), (0.95, 5.2, -3.55), BILL_DARK, faces={"south": None, "up": None})
    # eyes and blush on the head's front (z -3.6)
    for sx in (1, -1):
        x0, x1 = (0.75, 1.65) if sx == 1 else (-1.65, -0.75)
        if eyes == "open":
            B((x0, 6.25, -3.68), (x1, 7.35, -3.6), DUCK_EYE, faces={"south": None})
            B((x0 + 0.1, 6.85, -3.72), (x0 + 0.45, 7.2, -3.68), SHINE, faces={"south": None})
        else:                               # shut: a little arc
            B((x0, 6.5, -3.68), (x1, 6.75, -3.6), DUCK_EYE, faces={"south": None})
            B((x0 - 0.1, 6.75, -3.68), (x0 + 0.2, 7.0, -3.6), DUCK_EYE, faces={"south": None})
            B((x1 - 0.2, 6.75, -3.68), (x1 + 0.1, 7.0, -3.6), DUCK_EYE, faces={"south": None})
        if not lite:
            bx0, bx1 = (1.15, 1.95) if sx == 1 else (-1.95, -1.15)
            B((bx0, 5.35, -3.66), (bx1, 5.85, -3.6), BLUSH, faces={"south": None})
    if not lite:                            # stubby wings on the sides
        w = 2.5 if slim else 2.6            # against the body side (slim) or the middle slab
        B((w, 2.2, -1.0), (3.05, 3.9, 2.2), FLUFF_LIGHT, faces={"west": None})
        B((-3.05, 2.2, -1.0), (-w, 3.9, 2.2), FLUFF_LIGHT, faces={"east": None})
    if slim:                                # flat webbed feet right under the body (their tops hidden by it)
        for sx in (1, -1):
            x0, x1 = (0.5, 1.9) if sx == 1 else (-1.9, -0.5)
            B((x0, 0.8, -1.6), (x1, 1.2, 0.5), BILL, faces={"up": None})
    elif not sit:                           # legs and flat webbed feet
        for sx in (1, -1):
            x0, x1 = (0.5, 1.9) if sx == 1 else (-1.9, -0.5)
            B((x0 + 0.4, 0.4, -0.2), (x1 - 0.4, 1.2, 0.4), BILL_DARK, faces={"up": None, "down": None})
            B((x0, 0.0, -1.6), (x1, 0.4, 0.5), BILL)
    if hat:                                 # a tiny sailor cap
        B((-1.6, 8.3, -2.9), (1.6, 8.75, 0.0), SAILOR_BAND, faces={"down": None})
        B((-1.3, 8.75, -2.6), (1.3, 9.75, -0.3), SAILOR, faces={"down": None})
        B((-0.3, 9.75, -1.75), (0.3, 10.15, -1.15), SAILOR_BAND, faces={"down": None})


def duckling_parade():
    """졸졸 아기 오리들 (a pet): three ducklings in a line, the leader in a sailor cap, the others waddling after it,
    each a little off the line so the row reads from any side."""
    m = MModel("duckling_parade")
    duckling(m, "lead", (8.0, 0, 3.0), 1.0, hat=True, slim=True)
    duckling(m, "second", (6.6, 0, 10.0), 0.9, slim=True, lite=True)
    duckling(m, "third", (9.2, 0, 16.4), 0.82, slim=True, lite=True)
    _display(m, PET_GUI_D, 0.75, -1)
    return m


def sleepy_duckling():
    """꾸벅 아기 오리 (above the head): a duckling sitting on the head fast asleep, eyes shut, a bubble growing from
    its bill and a little 'z' over it. Bobs with the breathing."""
    m = MModel("sleepy_duckling")
    duckling(m, "duck", (8.0, 9.0, 8.6), 1.15, eyes="shut", sit=True)
    orb(m, "bubble", (10.1, 13.6, 2.7), 1.9, BUBBLE, glow=False)
    m.box("bubble_shine", (9.7, 14.1, 1.75), (10.1, 14.5, 1.85), SHINE)
    # 'z's drawn mirrored in x so they read as z from the front (the model's north faces the viewer there)
    pix(m, "z", ["zzz", "z..", ".z.", "..z", "zzz"], 4.4, 21.0, 7.9, 8.3, {"z": Z_MARK}, unit=0.45)
    pix(m, "z", ["zz", "z.", ".z", "zz"], 3.2, 23.4, 7.9, 8.3, {"z": Z_MARK}, unit=0.32)
    _display(m, HEAD_GUI, 0.9, -7)
    return m


def duck_float_ring():
    """오리 튜브 (the waist): a fat yellow swim ring round the hips with white stripes, a duck's head rising at the
    front (orange bill, shiny eyes) and its tail flicking up at the back."""
    m = MModel("duck_float_ring")
    y0, y1 = 3.6, 6.8
    octagon(m, "ring", 6.4, 2.6, y0, y1, FLUFF, faces={"down": FLUFF_SHADE, "up": FLUFF_LIGHT})
    octagon(m, "ring_top", 5.9, 1.6, y1, y1 + 0.5, FLUFF_LIGHT, faces={"down": None})
    # white stripes on the four straight sides' outer faces
    for (x0, z0, x1, z1) in ((7.2, 1.48, 8.8, 1.6), (7.2, 14.4, 8.8, 14.52), (1.48, 7.2, 1.6, 8.8), (14.4, 7.2, 14.52, 8.8)):
        m.box("stripe", (x0, y0 + 0.2, z0), (x1, y1 + 0.3, z1), RING_STRIPE)
    # the duck's head at the front, rising off the ring
    m.box("neck", (6.6, y1, 1.0), (9.4, y1 + 2.4, 3.4), FLUFF, faces={"up": None})
    m.box("head", (6.0, y1 + 2.0, 0.4), (10.0, y1 + 5.6, 3.8), FLUFF)
    m.box("tuft", (7.7, y1 + 5.6, 1.6), (8.3, y1 + 6.4, 2.3), FLUFF_LIGHT, faces={"down": None})
    m.box("bill", (6.8, y1 + 2.8, -0.8), (9.2, y1 + 3.6, 0.4), BILL, faces={"south": None})
    m.box("bill", (7.1, y1 + 2.4, -0.5), (8.9, y1 + 2.8, 0.4), BILL_DARK, faces={"south": None, "up": None})
    for (x0, x1) in ((6.55, 7.45), (8.55, 9.45)):
        m.box("eye", (x0, y1 + 3.9, 0.32), (x1, y1 + 4.9, 0.4), DUCK_EYE, faces={"south": None})
        m.box("eye_shine", (x0 + 0.1, y1 + 4.45, 0.27), (x0 + 0.45, y1 + 4.8, 0.32), SHINE, faces={"south": None})
    for (x0, x1) in ((6.15, 6.85), (9.15, 9.85)):
        m.box("blush", (x0, y1 + 3.0, 0.34), (x1, y1 + 3.45, 0.4), BLUSH, faces={"south": None})
    # tail at the back
    m.box("tail", (7.0, y1 - 0.4, 14.0), (9.0, y1 + 1.4, 15.6), FLUFF_LIGHT)
    m.box("tail", (7.4, y1 + 1.4, 14.8), (8.6, y1 + 2.4, 15.8), FLUFF_LIGHT)
    _display(m, HEAD_GUI, 0.75, 0)
    return m


# one baby wing seen from the side: the root (column 0) against the upper back, the wing running back from it.
# A teardrop: tall at the root, a soft point at the tip a little lower; the yellow coverts over two stepped rows of
# feather tips (pale, then cream) along the lower edge. l light edge, w yellow, p pale tips, c cream tips.
WING_ROWS = ["lll......",
             "wwwwl....",
             "wwwwwwl..",
             "wwwwwwwwl",
             "pwwwwwwpp",
             ".ppwwpp..",
             "..cc.cc.."]
WING_PALE = Mat("petal_yellow", "plain", 2, noise=0.02)
WING_CREAM = Mat("cream", "plain", 3, noise=0.02)


def pix_zy(m, name, rows, x0, x1, ytop, z0, mats, unit=1.0, rotation=None):
    """Pixel art drawn in the z-y plane (a plate x0..x1 thick, seen from the side): rows top to bottom, the
    columns running towards +z (behind the player). Runs of one character in a row become one box; '.' empty."""
    for r, row in enumerate(rows):
        c = 0
        while c < len(row):
            ch = row[c]
            if ch == ".":
                c += 1
                continue
            e = c
            while e < len(row) and row[e] == ch:
                e += 1
            m.box(name, (x0, ytop - (r + 1) * unit, z0 + c * unit), (x1, ytop - r * unit, z0 + e * unit), mats[ch],
                  rotation=rotation)
            c = e


def duckling_wings():
    """파닥 아기 날개 (the back): two short stubby duckling wings on the upper back, below the shoulders, each a
    flat feathered teardrop running back from the shoulder blade and turned out a little (the tips show past the
    arms from the front, nothing rises above the shoulders); a soft lump at each root. Mirrored. Flutter fast
    (flap: the engine squeezes the model in x, so the outturned tips beat in and out)."""
    m = MModel("duckling_wings")
    mats = {"l": FLUFF_LIGHT, "w": FLUFF, "p": WING_PALE, "c": WING_CREAM}
    u, back, top = 0.8, 8.3, 9.6
    for side in (1, -1):
        xr = 8 + side * 2.4                          # the root: on the shoulder blade
        x0, x1 = (xr, xr + 0.7) if side == 1 else (xr - 0.7, xr)
        rot = ("y", 45 * side, (xr, top - 2, back))
        pix_zy(m, "wing", WING_ROWS, x0, x1, top, back, mats, unit=u, rotation=rot)
        # the root's soft lump (wider at the root): a short thicker pad over the first columns
        a, b = (xr - 0.35, xr + 1.05) if side == 1 else (xr - 1.05, xr + 0.35)
        m.box("root", (a, top - 3.2 * u, back - 0.2), (b, top - 0.6 * u, back + 2.2 * u), FLUFF, rotation=rot)
    _display(m, BACK_GUI, 0.6, 1)
    return m


def puddle_trail():
    """Card for 첨벙 발자국 (a trail): a round puddle with a ripple ring and splashing drops, and a little duckling
    sitting in it."""
    m = MModel("puddle_trail")
    octagon(m, "puddle", 6.2, 1.8, 3.0, 3.5, WATER, faces={"up": WATER_LIGHT})          # the shore band
    m.box("puddle_fill", (3.2, 2.9, 3.2), (12.8, 3.4, 12.8), WATER_LIGHT, faces={"down": WATER})
    octagon(m, "ripple", 4.4, 0.6, 3.4, 3.6, Mat("ice", "plain", 3, bevel=None, noise=0), faces={"down": None})
    for (x, y, z, d) in ((2.6, 5.6, 6.0, 0.8), (13.0, 6.4, 9.5, 0.7), (4.4, 7.2, 11.6, 0.6), (11.8, 5.2, 3.4, 0.6)):
        m.box("drop", (x - d / 2, y - d / 2, z - d / 2), (x + d / 2, y + d / 2, z + d / 2), WATER_LIGHT)
    duckling(m, "duck", (8.4, 2.6, 8.4), 0.8, sit=True)
    _display(m, (35, 225, 0), 0.9, -1)
    return m


# slot of every model (the item yml says the same); the trail and aura models are only their cards
SLOTS = {
    "duckling_parade": "pet", "sleepy_duckling": "above", "duck_float_ring": "waist", "duckling_wings": "back",
    "puddle_trail": "trail",
}

ALL = {f.__name__: f for f in (duckling_parade, sleepy_duckling, duck_float_ring, duckling_wings, puddle_trail)}

# 청룡 v2 (models/cosmetics_azure.py): its pieces and the engine's models (awakened looks, prints, the burst, the ring)
from models import cosmetics_azure as _azure   # noqa: E402

SLOTS.update(_azure.SLOTS)
ALL.update(_azure.ALL)

# 은하수 v2 (models/cosmetics_galaxy.py): its pieces and the engine's models
from models import cosmetics_galaxy as _galaxy   # noqa: E402

SLOTS.update(_galaxy.SLOTS)
ALL.update(_galaxy.ALL)
