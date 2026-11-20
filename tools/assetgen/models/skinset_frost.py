"""
서리 수정 시리즈 (frost crystal series): a premium tool skin set, built on the toy series' frame (models/toys.py).

    frost_sword  frost_pickaxe  frost_shovel  frost_axe  frost_hoe  frost_fishing_rod (+ frost_fishing_rod_cast)  frost_bobber

Same frame, grip point, sizes and display transforms as the toys (toys._held): every model upright on the axis x = z = 8,
the hand closing at GRIP_Y. Only the looks are this set's own:

  ice crystal   blades and heads, cut in facets: every face split on its diagonal into a lit facet (top left) and a
                shadowed one, a white frost rim along its top / left edges, a pale one along the others, and a few
                sparkles. Three tones (deep, mid, light) run from the root of a blade or head out to its tip, and the
                edges are frosted white (CrystalPaint, painted per face at one texel a unit).
  glow core     the greatsword's ridge and the rod's tip crystal: pale cyan, lit from inside (light_emission).
  frost silver  the fittings: collars, sockets, caps, rings, rails (silver-blue, a white glint in each face's corner).
  navy leather  the grip, wrapped in diagonal bands between two silver rings, a crystal pommel under a silver cap.
  emblem        a deep blue snowflake on a pale ice plate, on every piece (the toys' sticker size and place).
"""

import math
import random
import zlib

from PIL import Image

from economy_machines import MModel, Mat, _face_size, cyl
from models.toys import C, GRIP, GRIP_Y, STICKER, STICKER_UV, _arm, _held, round_y, round_z  # noqa: F401
from stsv_assets import presets
from stsv_assets import textures as T

S = 16


def _hex(h):
    return (int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16), 255)


ICE_RAMP = [_hex(h) for h in ("#10285e", "#1a4590", "#2a69c0", "#4a98e2", "#80c8f4", "#c2ecfc", "#ffffff")]
FROST_RAMP = [_hex(h) for h in ("#4f6684", "#7088a8", "#9db3cc", "#c9d9e8", "#eaf3fa", "#ffffff")]
LEATHER_RAMP = [_hex(h) for h in ("#141f40", "#1f3062", "#2c4584", "#4565a8", "#6a8cc8")]
FUR_RAMP = [_hex(h) for h in ("#b7c8da", "#d4e1ee", "#ecf4fa", "#ffffff")]
CORE_RAMP = [_hex(h) for h in ("#5cc4f0", "#8fe0fa", "#c8f4ff", "#ffffff")]

SIDE_SHADE = {"north": 0, "south": 0, "up": 1, "down": -1, "east": -1, "west": 0}


class FrostPaint(Mat):
    """A material that paints each face with a function of its pixel size: fn(w, h, face, rnd) -> rows of colours."""

    def __init__(self, tag, fn, glow=False, **opt):
        super().__init__("ice", "plain", 1, bevel=None, noise=0)
        self.tag, self.fn, self.popt = tag, fn, opt
        self.glow = glow

    def __call__(self, **changes):     # cyl() asks for a copy without the bevel: there is none to take off
        return self

    def face(self, w, h, face, seed):
        pw, ph = max(1, min(S, math.ceil(w - 1e-6))), max(1, min(S, math.ceil(h - 1e-6)))
        rows = self.fn(pw, ph, face, random.Random(seed ^ zlib.crc32(self.tag.encode())), **self.popt)
        im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        px = im.load()
        for y in range(S):          # opaque everywhere: outside the face's own region, the nearest edge colour
            for x in range(S):
                px[x, y] = rows[min(y, ph - 1)][min(x, pw - 1)]
        key = "fz_" + format(zlib.crc32(im.tobytes()), "08x")
        if key not in T.SURFACES:
            T.SURFACES[key] = (lambda img=im: img.copy())
        return key, [0, 0, round(min(w, S), 4), round(min(h, S), 4)]


def _crystal(w, h, face, rnd, tone=0, sparkle=True):
    """Faceted ice: a lit facet above the face's diagonal and a shadowed one below it, a bright glint line where they
    meet, a white frost rim along the top / left edges and a pale one along the bottom / right, a few sparkles."""
    s = SIDE_SHADE[face] + tone
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            if min(w, h) <= 2:                  # a thin face: a rim colour along its length, lit at its first texel
                i = 4 if (x == 0 or y == 0) else 3
            elif y == 0 or x == 0:
                i = 5
            elif y == h - 1 or x == w - 1:
                i = 4
            else:
                u, v = x / (w - 1), y / (h - 1)
                t = u + v
                i = 3 if t < 0.95 else 2
                if abs(t - 0.95) * min(w, h) < 0.55 and min(w, h) >= 4:
                    i = 4
                if sparkle and min(w, h) >= 3 and rnd.random() < 0.05:
                    i = 6 - (0 if t < 0.95 else 1)
            row.append(ICE_RAMP[max(0, min(6, i + s))])
        out.append(row)
    return out


def _core(w, h, face, rnd):
    """The glowing crystal core: pale cyan, white along its middle."""
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            mid = (w >= 3 and x == w // 2) or (h >= 3 and w < 3 and y % 4 == 1)
            i = 3 if mid else 2 if (x == 0 or y == 0) else 1
            row.append(CORE_RAMP[i])
        out.append(row)
    return out


def _frost(w, h, face, rnd, lift=0):
    """Silver-blue metal: a white glint in the top left corner, a light top / left edge and a darker bottom / right one."""
    s = SIDE_SHADE[face] + lift
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            i = 2
            if x + y < min(w, h) * 0.45:
                i = 3
            if (y == 0 and h > 1) or (x == 0 and w > 1):
                i += 1
            if (y == h - 1 and h > 1) or (x == w - 1 and w > 1):
                i -= 1
            if x == 0 and y == 0 and min(w, h) >= 3:
                i = 5
            row.append(FROST_RAMP[max(0, min(5, i + s))])
        out.append(row)
    return out


def _leather(w, h, face, rnd):
    """Navy leather wrapped round the grip: diagonal bands, a light edge on each."""
    s = SIDE_SHADE[face]
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            k = (x + y) % 4
            i = 3 if k == 0 else 1 if k == 3 else 2
            row.append(LEATHER_RAMP[max(0, min(4, i + s))])
        out.append(row)
    return out


def _fur(w, h, face, rnd):
    """Snow: soft speckles, the top a shade lighter."""
    s = SIDE_SHADE[face]
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            r = rnd.random()
            i = 1 if r < 0.25 else 3 if r < 0.45 else 2
            if y == 0:
                i = min(3, i + 1)
            row.append(FUR_RAMP[max(0, min(3, i + s))])
        out.append(row)
    return out


ICE = FrostPaint("ice", _crystal)
ICE_LIGHT = FrostPaint("ice_light", _crystal, tone=1)
ICE_PALE = FrostPaint("ice_pale", _crystal, tone=2, sparkle=False)
ICE_DEEP = FrostPaint("ice_deep", _crystal, tone=-1)
ICE_DARK = FrostPaint("ice_dark", _crystal, tone=-2, sparkle=False)
CORE = FrostPaint("core", _core, glow=True)
FROST = FrostPaint("frost", _frost)
FROST_DIM = FrostPaint("frost_dim", _frost, lift=-1)
WHITE = FrostPaint("white", _frost, lift=2)
LEATHER = FrostPaint("leather", _leather)
FUR = FrostPaint("fur", _fur)
LINE = Mat("feather", "plain", 2, bevel=None, noise=0)


# ================================================================================================
# The emblem: a deep blue snowflake on a pale ice plate
# ================================================================================================

FLAKE = ("....#....",
         ".#..#..#.",
         "..#.#.#..",
         "...###...",
         "#########",
         "...###...",
         "..#.#.#..",
         ".#..#..#.",
         "....#....")


def _emblem():
    """A deep blue snowflake on a pale ice plate, a darker line along its bottom and right."""
    im = Image.new("RGBA", (16, 16), ICE_RAMP[5])
    px = im.load()
    for y, row in enumerate(FLAKE):
        for x, ch in enumerate(row):
            if x == 8 or y == 8:
                px[x, y] = ICE_RAMP[3]
            elif ch == "#":
                px[x, y] = ICE_RAMP[0] if (x == 4 and y == 4) else ICE_RAMP[1] if (x == 4 or y == 4) else ICE_RAMP[2]
            else:
                px[x, y] = ICE_RAMP[6] if (x == 0 or y == 0) else ICE_RAMP[5]
    return im


T.SURFACES["frost_emblem"] = _emblem


def emblem(m, x0, y0, z0, z1):
    m.box("emblem", (x0, y0, z0), (x0 + STICKER, y0 + STICKER, z1), FROST,
          faces={"north": "frost_emblem", "south": "frost_emblem"}, uv={"north": STICKER_UV, "south": STICKER_UV})


# ================================================================================================
# Shared parts
# ================================================================================================

def icicle(m, x, y, length, z=C, mat=ICE_LIGHT):
    """An icicle hanging from y: two steps, thinner towards its point."""
    m.box("icicle", (x, y - length * 0.6, z - 0.5), (x + 0.75, y, z + 0.5), mat)
    m.box("icicle", (x + 0.125, y - length, z - 0.25), (x + 0.625, y - length * 0.6, z + 0.25), ICE_PALE)


def handle(m):
    """A crystal pommel (a diamond) under a silver cap, a navy leather grip between two silver rings, a silver collar."""
    m.group("handle")
    m.box("pommel_gem", (7.125, -5, 7.125), (8.875, -3.25, 8.875), ICE_LIGHT, rotation=("y", 45, (C, -4, C)))
    round_y(m, "pommel_cap", 6.375, 9.625, 6.375, 9.625, -3.5, -1.75, FROST, 0.75)
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, -1.75, 4.75, LEATHER, 0.5)
    m.box("grip_ring", (6.5, -1.25, 6.5), (9.5, -0.75, 9.5), FROST)
    m.box("grip_ring", (6.5, 3.75, 6.5), (9.5, 4.25, 9.5), FROST)
    round_y(m, "collar", 6.25, 9.75, 6.25, 9.75, 4.75, 6.5, FROST, 0.75)


def shaft(m, top, mat=FROST_DIM):
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, mat, 0.5)


# ================================================================================================
# A model built bigger than the -16..32 box allows: shrunk about the grip as it is built, the held views enlarged back
# ================================================================================================

G = (C, GRIP_Y, C)


class Shrunk(MModel):
    """Every box is placed k times as far from the grip point (8, GRIP_Y, 8) as given, so a greatsword fits the model
    box; its faces keep the texel size of the size given (one texel a given unit, like every other piece). _big() then
    enlarges the held views by 1 / k about the grip, so in the hand it is the size it was drawn."""

    def __init__(self, name, k):
        super().__init__(name)
        self.k = k

    def _p(self, p):
        return tuple(G[i] + self.k * (p[i] - G[i]) for i in range(3))

    def box(self, name, frm, to, tex, faces=None, full_uv=(), glow=False, rotation=None, uv=None, side_v=None, shade=True):
        fs = {f: tex for f in ("north", "east", "south", "west", "up", "down")}
        fs.update(faces or {})
        uv = dict(uv or {})
        for f, t in list(fs.items()):
            if isinstance(t, Mat):
                w, h = _face_size(f, frm, to)
                key, region = t.face(w, h, f, zlib.crc32(f"{round(w, 2)}x{round(h, 2)}/{f}".encode()))
                fs[f] = key
                glow = glow or t.glow
                if region and f not in uv:
                    uv[f] = region
        if rotation:
            axis, angle, o = rotation
            rotation = (axis, angle, self._p(o))
        return super().box(name, self._p(frm), self._p(to), tex, faces=fs, full_uv=full_uv, glow=glow, rotation=rotation,
                           uv=uv, side_v=side_v, shade=shade)


def _big(m, kind):
    _held(m, kind)
    held = ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand")
    m.display = presets.enlarged(m.display, 1 / m.k, GRIP, held)
    for ctx in held:
        m.display[ctx]["translation"] = [v + 0.0 for v in m.display[ctx]["translation"]]
    return m


# ================================================================================================
# The tools
# ================================================================================================

SWORD_K = 0.75      # 고드름 대검 is drawn 1.6 times the toy sword's blade above the grip, then shrunk to fit the box


def frost_sword():
    """고드름 대검: a long faceted icicle blade, deep blue at its root to pale at its tip in four steps, white frost edges
    down both sides, a glowing crystal ridge up its middle and a white point; a snow crust at its root with two small
    icicles growing out beside it; a silver crossguard with the emblem, crystal wings rising from its ends, icicles under."""
    m = Shrunk("frost_sword", SWORD_K)
    handle(m)
    m.group("guard")
    m.box("guard_hub", (5.5, 6.25, 6.5), (10.5, 11.5, 9.5), FROST)
    emblem(m, 5.75, 6.5, 6.25, 9.75)
    m.box("guard_bar", (1.75, 7.5, 7), (14.25, 9.25, 9), FROST)
    for side in (-1, 1):
        x = C + side * 6.25
        m.box("guard_wing", (x - 0.875, 8, 7.25), (x + 0.875, 13, 8.75), ICE,
              rotation=("z", -22.5 * side, (x, 8.375, C)))
        m.box("guard_wing_tip", (x - 0.5, 13, 7.5), (x + 0.5, 14.5, 8.5), ICE_PALE,
              rotation=("z", -22.5 * side, (x, 8.375, C)))
        icicle(m, x - 0.375 - side * 1.25, 7.5, 2.25)
        icicle(m, x - 0.375 - side * 3.25, 7.5, 1.5)
    m.group("blade")
    round_y(m, "snow", 4.75, 11.25, 6.5, 9.5, 11.5, 12.75, FUR, 1)
    # (y0, y1, half width, material): deep at the root, pale towards the tip
    rows = ((12.75, 27.5, 3.25, ICE_DEEP), (27.5, 34, 2.75, ICE), (34, 38, 2.0, ICE_LIGHT), (38, 40.5, 1.25, ICE_LIGHT))
    for y0, y1, hw, mat in rows:
        m.box("blade", (C - hw + 0.75, y0, 7), (C + hw - 0.75, y1, 9), mat)
        m.box("blade_edge", (C - hw, y0, 7.5), (C - hw + 0.75, y1, 8.5), ICE_PALE)
        m.box("blade_edge", (C + hw - 0.75, y0, 7.5), (C + hw, y1, 8.5), ICE_PALE)
    m.box("blade_point", (7.5, 40.5, 7.5), (8.5, 41.75, 8.5), WHITE)
    m.box("ridge", (7.625, 13, 6.75), (8.375, 27.5, 9.25), CORE)
    m.box("ridge", (7.625, 27.5, 6.75), (8.375, 39.5, 9.25), CORE)
    m.group("shards")
    m.box("side_icicle", (3.25, 12, 7.375), (4.75, 17, 8.625), ICE, rotation=("z", 22.5, (4, 12, C)))
    m.box("side_icicle", (3.625, 17, 7.625), (4.375, 18.75, 8.375), ICE_PALE, rotation=("z", 22.5, (4, 12, C)))
    m.box("side_icicle", (11.25, 12, 7.375), (12.75, 15.75, 8.625), ICE, rotation=("z", -22.5, (12, 12, C)))
    m.box("side_icicle", (11.625, 15.75, 7.625), (12.375, 17.25, 8.375), ICE_PALE, rotation=("z", -22.5, (12, 12, C)))
    return _big(m, "sword")


def frost_pickaxe():
    """눈송이 곡괭이: a silver snowflake hub with the emblem on the shaft's top, two thick crystal arms sweeping out and down
    in three steps (deep blue at the hub, pale at the white points), a snowflake twig rising from each step, a crystal
    point on top."""
    m = MModel("frost_pickaxe")
    handle(m)
    shaft(m, 15.5)
    m.group("hub")
    round_y(m, "socket", 6.5, 9.5, 6.5, 9.5, 14.5, 15.5, FROST, 0.75)
    round_z(m, "hub", 5.25, 10.75, 15.5, 21.25, 6.5, 9.5, FROST, 1.25)
    emblem(m, 5.75, 16.125, 6.25, 9.75)
    m.box("crown", (7.25, 21.25, 7.25), (8.75, 23.5, 8.75), ICE)
    m.box("crown_point", (7.625, 23.5, 7.625), (8.375, 24.75, 8.375), ICE_PALE)
    m.group("arms")
    for side in (1, -1):
        p = _arm(m, "arm", side, (C + side * 2.25, 18.5), 4.5, 1.375, 7, 9, 0, ICE_DEEP)
        t1 = (C + side * 5.25, 19.5)
        p = _arm(m, "arm", side, p, 3.75, 1.125, 7.125, 8.875, 22.5, ICE)
        t2 = p
        p = _arm(m, "arm", side, p, 3.0, 0.875, 7.25, 8.75, 45, ICE_LIGHT)
        _arm(m, "arm_point", side, p, 1.75, 0.5, 7.5, 8.5, 45, WHITE)
        # snowflake twigs, up and out from the arm's top
        for (tx, ty), ln in ((t1, 2.5), ((t2[0], t2[1] + 0.5), 2.0)):
            lo, hi = (tx, tx + ln) if side > 0 else (tx - ln, tx)
            m.box("twig", (lo, ty - 0.375, 7.5), (hi, ty + 0.375, 8.5), ICE_PALE, rotation=("z", 45 * side, (tx, ty, C)))
    return _held(m, "pickaxe")


def frost_shovel():
    """눈썰매 삽: a spade blade built like a little sled: a deep blue ice bed with pale cyan slats across its face, silver
    runners standing proud down both sides and curling forward over its shoulders' tops, a pull cord between the curls,
    a stepped crystal point, the emblem on the bed, a silver crossbar and socket where it meets the shaft."""
    m = MModel("frost_shovel")
    handle(m)
    shaft(m, 15)
    m.group("socket")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 14.5, 16.5, FROST, 0.75)
    m.box("crossbar", (4.25, 15.75, 7), (11.75, 16.75, 9), FROST)
    m.group("blade")
    m.box("bed", (4.5, 16.75, 7.25), (11.5, 26.25, 8.75), ICE_DEEP)
    m.box("tip", (5.25, 26.25, 7.375), (10.75, 27.75, 8.625), ICE)
    m.box("tip", (6.25, 27.75, 7.5), (9.75, 29, 8.5), ICE_LIGHT)
    m.box("tip", (7.25, 29, 7.625), (8.75, 30, 8.375), ICE_PALE)
    emblem(m, 5.75, 17.25, 7, 9)
    for y0 in (22.25, 24.25):
        m.box("slat", (4.75, y0, 8.75), (11.25, y0 + 1.25, 9.25), ICE_LIGHT)
        m.box("slat", (4.75, y0, 6.75), (11.25, y0 + 1.25, 7.25), ICE)
    m.group("runners")
    for x0 in (3.5, 11.5):
        m.box("runner", (x0, 16.25, 6.75), (x0 + 1, 26.5, 9.75), FROST)
        m.box("runner_curl", (x0, 26.5, 8.25), (x0 + 1, 27.5, 10.5), FROST)
        m.box("runner_curl", (x0, 27.5, 9.75), (x0 + 1, 28.25, 11.25), FROST_DIM)
    m.box("cord", (4.5, 27.75, 10.25), (11.5, 28.25, 10.75), ICE_DARK)
    return _held(m, "shovel")


def frost_axe():
    """빙하 도끼: a broad blade of glacier ice flaring out to -x in three steps (deep blue at the socket, pale towards the
    edge), a frosted white cutting edge, snow lying on its top ledges, icicles under them, the emblem on its cheek, a
    silver socket and cap, a crystal spike behind."""
    m = MModel("frost_axe")
    handle(m)
    shaft(m, 14)
    m.group("socket")
    m.box("socket", (6.25, 13.5, 6.5), (9.75, 23, 9.5), FROST)
    round_y(m, "cap", 6.5, 9.5, 6.5, 9.5, 23, 24.25, FROST, 0.75)
    m.box("cap_point", (7.5, 24.25, 7.5), (8.5, 25, 8.5), ICE_PALE)
    m.group("blade")
    m.box("neck", (2.5, 15, 7), (6.25, 21.5, 9), ICE_DEEP)
    m.box("cheek", (0, 13.5, 7.25), (2.5, 23, 8.75), ICE)
    m.box("flare", (-2, 12, 7.5), (0, 24.5, 8.5), ICE_LIGHT)
    m.box("edge", (-3, 12.5, 7.625), (-2, 24, 8.375), WHITE)
    m.box("horn", (-2.75, 24.5, 7.625), (-0.75, 25.25, 8.375), ICE_PALE)
    m.box("horn", (-2.75, 11.25, 7.625), (-0.75, 12, 8.375), ICE_PALE)
    emblem(m, 1.25, 16, 6.75, 9.25)
    m.box("snow", (2.5, 21.5, 7.125), (6.25, 22.25, 8.875), FUR)
    m.box("snow", (0, 23, 7.375), (2.5, 23.75, 8.625), FUR)
    icicle(m, 4.25, 15, 2.0)
    icicle(m, 1.0, 13.5, 1.5)
    m.group("spike")
    m.box("spike", (9.75, 16.5, 7.25), (11.75, 20, 8.75), ICE)
    m.box("spike", (11.75, 17.25, 7.5), (13.25, 19.25, 8.5), ICE_LIGHT)
    m.box("spike_point", (13.25, 17.875, 7.75), (14, 18.625, 8.25), ICE_PALE)
    return _held(m, "axe")


def frost_hoe():
    """스케이트날 괭이: an ice skate for the straight head: a navy boot out to -x from a silver hub (the emblem on it),
    a snow cuff along its top, white laces across it, two silver posts holding a long steel runner under it, and at the
    far end the hoe's blade: a straight crystal plate hanging down, a white edge at its foot (every end cut flat)."""
    m = MModel("frost_hoe")
    handle(m)
    shaft(m, 20.5)
    m.group("hub")
    m.box("hub", (5.5, 20.5, 6.5), (10.5, 25.5, 9.5), FROST)
    emblem(m, 5.75, 20.75, 6.25, 9.75)
    m.box("hub_cap", (6.5, 25.5, 7), (9.5, 26.25, 9), FROST_DIM)
    m.group("skate")
    m.box("boot", (-4, 22, 6.75), (5.5, 25, 9.25), LEATHER)
    m.box("cuff", (-4.25, 25, 7), (5.5, 26, 9), FUR)
    for x in (-2.5, -0.25, 2):
        m.box("lace", (x, 23, 9.25), (x + 1.25, 23.75, 9.5), WHITE)
        m.box("lace", (x, 23, 6.5), (x + 1.25, 23.75, 6.75), WHITE)
    for x in (-1.25, 2.75):
        m.box("post", (x, 20.75, 7.375), (x + 1.25, 22, 8.625), FROST)
    m.box("runner", (-2, 20, 7.625), (5.25, 20.75, 8.375), FROST)
    m.box("blade", (-4.5, 17.5, 7.375), (-2, 22, 8.625), ICE)
    m.box("blade_edge", (-4.5, 16.75, 7.5), (-2, 17.5, 8.5), WHITE)
    return _held(m, "hoe")


def _rod(name, cast):
    """얼음낚시 대: a short ice-fishing rod: the series' grip, a small silver reel, a crystal rod deep blue at its butt and
    pale at its tip, silver rings and guides, a glowing crystal at its tip; a little ice-hole charm (a white ice disc
    round a dark hole) hanging from the reel."""
    m = MModel(name)
    handle(m)
    m.group("reel")
    m.box("reel_foot", (9.25, 7.5, 7.5), (10.25, 9.25, 8.5), FROST_DIM)
    cyl(m, "reel", "z", (12, 8.5), 4, 6.75, 9.25, FROST, cap=FROST)
    m.box("reel_hub", (11.25, 7.75, 9.25), (12.75, 9.25, 9.75), ICE_LIGHT)
    m.box("crank", (11.75, 9, 9.75), (12.25, 11, 10.25), FROST)
    m.box("crank_knob", (11.5, 10.75, 10.25), (12.5, 11.75, 11), ICE_PALE)
    m.group("charm")
    m.box("charm_chain", (11.75, 4.25, 7.75), (12.25, 6.5, 8.25), FROST_DIM)
    cyl(m, "charm", "z", (12, 2.5), 4, 7.375, 8.625, WHITE, cap=WHITE)
    cyl(m, "charm_hole", "z", (12, 2.5), 2, 8.625, 8.875, ICE_DARK, cap=ICE_DARK)
    m.group("rod")
    round_y(m, "rod", 6.875, 9.125, 6.875, 9.125, 6.5, 12, ICE_DEEP, 0.5)
    m.box("rod", (7.25, 12, 7.25), (8.75, 19, 8.75), ICE)
    m.box("rod", (7.5, 19, 7.5), (8.5, 24.25, 8.5), ICE_LIGHT)
    for y, r in ((11.25, 1.25), (18.5, 1.0), (23.5, 0.75)):
        m.box("rod_ring", (C - r, y, C - r), (C + r, y + 0.75, C + r), FROST)
    m.box("guide", (8.75, 15, 7.625), (9.75, 16, 8.375), FROST_DIM)
    m.box("guide", (8.5, 21.5, 7.625), (9.25, 22.25, 8.375), FROST_DIM)
    m.box("rod_tip", (7.375, 24.25, 7.375), (8.625, 25.5, 8.625), CORE, rotation=("y", 45, (C, 25, C)))
    m.box("rod_tip", (7.625, 25.5, 7.625), (8.375, 26.5, 8.375), WHITE)
    if not cast:
        m.group("line")
        top = (8.25, 26, C)
        rot = ("z", 45, top)
        m.box("line", (7.875, 16.5, 7.625), (8.625, 26, 8.375), LINE, rotation=rot)
        # a small ice jig: a silver eye, a crystal body, a silver band, a deep point
        m.box("jig_top", (7.5, 15.75, 7.5), (9, 16.5, 9), FROST, rotation=rot)
        m.box("jig", (6.75, 13.75, 6.75), (9.75, 15.75, 9.75), ICE_LIGHT, rotation=rot)
        m.box("jig_band", (6.5, 13, 6.5), (10, 13.75, 10), FROST, rotation=rot)
        m.box("jig_low", (7.25, 11.75, 7.25), (9.25, 13, 9.25), ICE, rotation=rot)
        m.box("jig_point", (7.75, 10.75, 7.75), (8.75, 11.75, 8.75), ICE_DEEP, rotation=rot)
    return _held(m, "rod")


def frost_fishing_rod():
    """얼음낚시 대 (with its jig on the line)."""
    return _rod("frost_fishing_rod", False)


def frost_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the jig."""
    m = _rod("frost_fishing_rod_cast", True)
    m.display = frost_fishing_rod().display
    return m


BOBBER_WATERLINE = 5.0        # the float's frost band: where the water's surface is (model y)


def frost_bobber():
    """서리 수정 찌: a small ice crystal float, a deep point below its frost band (the waterline), a pale point above,
    the emblem on four sides. Stands on y = 0 about the axis x = 8, z = 8, like the toy float."""
    m = MModel("frost_bobber")
    m.group("float")
    cyl(m, "point", "y", (C, C), 2, 0, 1.25, ICE_DARK, cap=ICE_DARK)
    cyl(m, "low", "y", (C, C), 4, 1.25, 2.75, ICE_DEEP, cap=ICE_DEEP)
    cyl(m, "belly", "y", (C, C), 6, 2.75, 4.5, ICE, cap=ICE)
    cyl(m, "band", "y", (C, C), 7, 4.5, 5.5, FROST, cap=FROST)
    cyl(m, "top", "y", (C, C), 6, 5.5, 8, ICE_LIGHT, cap=ICE_LIGHT)
    cyl(m, "top", "y", (C, C), 4, 8, 9.5, ICE_PALE, cap=ICE_PALE)
    cyl(m, "top", "y", (C, C), 2, 9.5, 11, WHITE, cap=WHITE)
    m.group("emblems")
    for face, frm, to in (("south", (6.75, 5.5, 11), (9.25, 8, 11.25)), ("north", (6.75, 5.5, 4.75), (9.25, 8, 5)),
                          ("east", (11, 5.5, 6.75), (11.25, 8, 9.25)), ("west", (4.75, 5.5, 6.75), (5, 8, 9.25))):
        m.box("emblem", frm, to, FROST, faces={face: "frost_emblem"}, uv={face: STICKER_UV})
    m.display = presets.furniture(gui_scale=1.3, gui_translation=(0, 3.25, 0))
    m.kind = "bobber"
    return m


ALL = {f.__name__: f for f in (frost_sword, frost_pickaxe, frost_shovel, frost_axe, frost_hoe, frost_fishing_rod,
                               frost_fishing_rod_cast, frost_bobber)}

ROWS = [("frost_sword", "고드름 대검"), ("frost_pickaxe", "눈송이 곡괭이"), ("frost_shovel", "눈썰매 삽"),
        ("frost_axe", "빙하 도끼"), ("frost_hoe", "스케이트날 괭이"), ("frost_fishing_rod", "얼음낚시 대"),
        ("frost_fishing_rod_cast", "낚싯대 (던진 뒤)"), ("frost_bobber", "서리 수정 찌 (찌에 태우는 모델)")]

TITLE = "서리 수정 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("frost_sword", "frost_pickaxe", "frost_shovel", "frost_axe", "frost_hoe", "frost_bobber")}
    out["frost_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/frost_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/frost_fishing_rod_cast"},
    }}
    return out
