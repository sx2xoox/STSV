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
# 아기 오리 가족 (duck_family): models/cosmetics_duck.py (v2, registered in build.py on its own)
# ================================================================================================


# slot of every model (the item yml says the same); the trail and aura models are only their cards
SLOTS = {
}

ALL = {}

# 청룡 v2 (models/cosmetics_azure.py): its pieces and the engine's models (awakened looks, prints, the burst, the ring)
from models import cosmetics_azure as _azure   # noqa: E402

SLOTS.update(_azure.SLOTS)
ALL.update(_azure.ALL)

# 은하수 v2 (models/cosmetics_galaxy.py): its pieces and the engine's models
from models import cosmetics_galaxy as _galaxy   # noqa: E402

SLOTS.update(_galaxy.SLOTS)
ALL.update(_galaxy.ALL)
