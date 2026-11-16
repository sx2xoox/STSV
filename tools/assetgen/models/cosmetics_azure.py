"""
청룡 세트 (azure_dragon) v2: nine pieces that fill the body, painted by the donation rules (premium_art.py, docs/ART_STYLE.md
"후원 전용 고급 규칙": 32 texels a block, painted shading, cut-out edges, animated glowing parts), with awakened looks for a
whole set and model effects instead of particles:

  여의주 (머리 위, spin)       · 날개 (등, flap)          · 꼬리 (꼬리, sway)      · 어깨 용 (어깨)
  구름 띠 (허리)               · 아기 청룡 (펫)            · 구름 발자국 (발자취: footprint models on the ground)
  뇌룡 (오라: a little dragon circling the chest, orbit) · 포효 (처치: a dragon head rising from a cloud)
  세트 각성: the 여의주, the wings and the 뇌룡 change to their awakened models; a ring of cloud and lightning turns at
  the feet (cosmetic_sets.yml awaken).

Same frames as models/cosmetics.py (attached: the model's (8, 8, 8) on the slot's anchor, north = the player's front,
+x their right; pets stand on y = 0 facing north; prints lie on y = 8 facing north; bursts stand on y = 8).
"""

import math

import economy_models  # noqa: F401
from economy_machines import MModel
from models.cosmetics import _display, BACK_GUI, HEAD_GUI
from premium_art import FACE_LIGHT, HMat, hd_canvas, hd_surface, shade, HS
from stsv_assets.palette import ramp

# ================================================================================================
# materials (HMat: 32 texels a block, painted)
# ================================================================================================

SCALE = HMat("sapphire", "scales", 2.1)
SCALE_DARK = HMat("sapphire", "scales", 1.3)
SCALE_GLINT = HMat("sapphire", "scales", 2.4, anim=8, glow=True)
BELLY = HMat("ice", "horn", 2.3)
BONE = HMat("indigo", "scales", 2.0)
BONE_LIGHT = HMat("indigo", "scales", 2.6)
HORN = HMat("pearl", "horn", 1.7)
MANE = HMat("ice", "cloud", 2.7)
CLOUD = HMat("ice", "cloud", 2.5)
CLOUD_SHADE = HMat("ice", "cloud", 1.5)
CLOUD_GLOW = HMat("ice", "cloud", 2.6, glow=True)
GOLD = HMat("gold", "metal", 2.0)
GOLD_LIGHT = HMat("gold", "metal", 2.7)
CYAN = HMat("glow_cyan", "smooth", 2.2, glow=True, anim=8)
CYAN_WHITE = HMat("glow_cyan", "gem", 2.6, glow=True)
EYE = HMat("glow_cyan", "gem", 2.8, glow=True)
TOOTH = HMat("pearl", "smooth", 2.4)
MOUTH = HMat("crimson", "smooth", 0.8)


# ================================================================================================
# HD cut-out and animated textures
# ================================================================================================

def _pearl(k):
    """여의주, 32 px: a milky sphere shaded from the top-left, light running round it in a spiral, a hot glint."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            dx, dy = (x - 15.5) / 16, (y - 15.5) / 16
            r = math.hypot(dx, dy)
            a = math.atan2(dy, dx)
            band = math.sin(a * 2 + r * 7 - k * math.tau / 8)
            v = 2.4 - 1.6 * r + 0.6 * (-dx - dy) * 0.7
            if band > 0.7:
                v += 1.0
            elif band > 0.2:
                v += 0.45
            c = shade("glow_cyan", v) if band > 0.2 else shade("ice", v + 0.3)
            if math.hypot(dx + 0.38, dy + 0.42) < 0.14:
                c = shade("glow_cyan", 3.0)
            px[x, y] = c
    return im


def _flame(k):
    """Blue flame, 32 px, cut out: three tongues that ripple upwards, white-hot core, deep blue tips."""
    im = hd_canvas()
    px = im.load()
    for x in range(HS):
        u = x / (HS - 1)
        h = (0.55 + 0.28 * math.sin(u * 9.0 + k * 0.8) + 0.16 * math.sin(u * 3.7 - k * 0.55)) * (1 - abs(u - 0.5) * 1.3)
        h = max(0.12, min(0.98, h)) * HS
        for y in range(HS):
            up = HS - 1 - y
            if up < h:
                t = up / h
                w = 1 - abs(u - 0.5) * 2
                v = 3.0 - 2.6 * t + 0.6 * w
                px[x, y] = shade("glow_cyan", v) if v > 0.9 else shade("sapphire", 2 + v)
    return im


def _fin():
    """A dragon's fin, 32 px, cut out: a fan of ribs, pale membrane fading to cyan at the scalloped edge."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            u, v = x / (HS - 1), y / (HS - 1)
            edge = 0.92 - 0.12 * abs(math.sin(u * math.pi * 3))
            if v > edge or v < 0.05 + 0.5 * abs(u - 0.5) ** 2:
                continue
            rib = abs(math.sin(u * math.pi * 6)) < 0.18
            t = v / edge
            c = shade("sapphire", 3.0 - 1.4 * t) if not rib else shade("ice", 2.6 - 0.8 * t)
            if t > 0.82:
                c = shade("glow_cyan", 2.6)
            px[x, y] = c
    return im


def _spark():
    """A four-point sparkle, 32 px, cut out."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            dx, dy = abs(x - 15.5), abs(y - 15.5)
            s = max(0, 1 - (dx * dy) / 18 - (dx + dy) / 40)
            if s > 0.35 and (dx < 2.5 or dy < 2.5 or dx + dy < 9):
                px[x, y] = shade("glow_cyan", 1.6 + 1.4 * s)
    return im


def _bolt(k):
    """A zig-zag lightning bolt, 32 px, cut out, flickering brighter and dimmer."""
    im = hd_canvas()
    px = im.load()
    pts = [(14, 0), (20, 9), (13, 13), (21, 22), (12, 31)]
    glow = 2.9 if k % 3 else 2.2
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        n = max(abs(x1 - x0), abs(y1 - y0)) * 2
        for i in range(n + 1):
            x, y = x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n
            for ox in (-2, -1, 0, 1, 2):
                xi = int(round(x + ox))
                if 0 <= xi < HS:
                    px[xi, int(round(y))] = shade("glow_cyan", glow - abs(ox) * 0.5) if abs(ox) < 2 else shade("sapphire", 3.4)
    return im


def _print_tex(variant):
    """구름 발자국, 32 px seen from above, cut out: a cloud in the shape of a foot (heel and four toe puffs), soft white with
    a glowing cyan rim and a few sparks; variant moves the sparks (the print flickers between two)."""
    im = hd_canvas()
    px = im.load()
    blobs = [(16, 21, 7.5), (16, 14, 6.2), (9.5, 6.5, 3.0), (14, 4.5, 3.1), (19, 4.8, 2.9), (23, 7.5, 2.6)]
    for y in range(HS):
        for x in range(HS):
            d = min(math.hypot(x - bx, y - by) - r for (bx, by, r) in blobs)
            if d > 1.2:
                continue
            if d > 0.0:
                px[x, y] = shade("glow_cyan", 2.6)
            else:
                v = 2.6 + max(-1.2, d * 0.25) + 0.3 * math.cos(x * 0.6) * math.cos(y * 0.5)
                px[x, y] = shade("snow", v) if d < -1.5 else shade("ice", 2.6)
    sparks = [(4, 26), (27, 18), (25, 28)] if variant == 0 else [(6, 14), (28, 25), (3, 29)]
    for (sx, sy) in sparks:
        for (dx, dy) in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            px[sx + dx, sy + dy] = shade("glow_cyan", 3.0 if (dx, dy) == (0, 0) else 2.2)
    return im


# the wing (right side; the left is its mirror), drawn flat in the x-y plane behind the back: the arm rises from the
# root by the elbow to the wrist, four fingers radiate from the wrist down and out, the membrane fills between them with
# a scalloped edge from tip to tip
W_ROOT, W_ELBOW, W_WRIST, W_THUMB = (9.4, 10.6), (13.6, 15.4), (18.4, 19.0), (20.2, 21.8)
W_TIPS = [(25.6, 13.4), (24.6, 7.0), (20.6, 2.4), (14.6, 3.2)]
W_BODY = (9.3, 4.6)          # where the membrane meets the back at the bottom
# the membrane is drawn on four plates (x from, x to, y from, y to), each at most 16 units (32 texels) a side
W_PLATES = [(9.0, 17.4, 1.8, 12.2), (16.9, 26.2, 1.8, 12.2), (9.0, 17.4, 11.7, 22.2), (16.9, 26.2, 11.7, 22.2)]


def _bez(p0, p1, toward, k=0.32, n=10):
    """A curve from p0 to p1 bowing in towards `toward` (the scallop between two finger tips)."""
    c = ((p0[0] + p1[0]) / 2 * (1 - k) + toward[0] * k, (p0[1] + p1[1]) / 2 * (1 - k) + toward[1] * k)
    out = []
    for i in range(n + 1):
        t = i / n
        out.append(((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0],
                    (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1]))
    return out


def _wing_outline():
    """The membrane's outline (a closed polygon) and its free edge (the scallops, where the rim glows)."""
    edge = []
    tips = W_TIPS + [W_BODY]
    for p0, p1 in zip(tips, tips[1:]):
        edge += _bez(p0, p1, W_WRIST)[:-1]
    edge.append(W_BODY)
    poly = [W_ROOT, W_ELBOW, W_WRIST] + edge
    return poly, edge


W_POLY, W_EDGE = _wing_outline()


def _inside(x, y, poly):
    c = False
    for (x0, y0), (x1, y1) in zip(poly, poly[1:] + poly[:1]):
        if (y0 > y) != (y1 > y) and x < (x1 - x0) * (y - y0) / (y1 - y0) + x0:
            c = not c
    return c


def _seg_dist(x, y, a, b):
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / max(1e-9, dx * dx + dy * dy)))
    return math.hypot(x - ax - t * dx, y - ay - t * dy)


def _poly_dist(x, y, pts):
    return min(_seg_dist(x, y, a, b) for a, b in zip(pts, pts[1:]))


def _membrane(plate, k, awake=False):
    """One plate of the membrane, 32 px a 16 units (two texels a unit): sapphire lighter towards the arm, darker veins
    along the fingers, a soft dark band then a glowing cyan rim along the scalloped edge that shimmers (8 frames);
    awake: the edge burns, blue flame tongues licking out past it."""
    x0, x1, y0, y1 = plate
    im = hd_canvas()
    px = im.load()
    for v in range(HS):
        for u in range(HS):
            x, y = x0 + (u + 0.5) / 2, y1 - (v + 0.5) / 2
            if x > x1 or y < y0:
                continue
            de = _poly_dist(x, y, W_EDGE)
            inside = _inside(x, y, W_POLY)
            if not inside:
                if awake:
                    lick = 1.1 + 0.9 * math.sin(x * 1.7 + y * 0.9 - k * 0.8)
                    if de < lick and _poly_dist(x, y, [W_ROOT, W_ELBOW, W_WRIST]) > 1.2:
                        px[u, v] = shade("glow_cyan", 3.0 - 1.6 * de / lick)
                continue
            if de < 0.55:
                px[u, v] = shade("glow_cyan", 2.0 + 0.9 * (0.5 + 0.5 * math.sin(de * 2 + (x + y) * 0.9 - k * math.tau / 8)))
                continue
            if de < 1.1:
                px[u, v] = shade("sapphire", 0.4)
                continue
            dw = math.hypot(x - W_WRIST[0], y - W_WRIST[1])
            val = 2.7 - 0.11 * dw + 0.18 * math.sin(x * 2.1 + y * 1.3)
            vein = min(_seg_dist(x, y, W_WRIST, t) for t in W_TIPS)
            if vein < 0.45:
                val -= 0.9
            elif vein < 0.9:
                val += 0.35          # the membrane catches light beside each finger
            if awake:
                val += 0.3
            px[u, v] = shade("sapphire", val)
    return im


hd_surface("az_pearl", _pearl, 8, 3)
hd_surface("az_flame", _flame, 8, 2)
hd_surface("az_fin", lambda k: _fin())
hd_surface("az_spark", lambda k: _spark())
hd_surface("az_bolt", _bolt, 6, 2)
hd_surface("az_print_a", lambda k: _print_tex(0))
hd_surface("az_print_b", lambda k: _print_tex(1))
for _n, _pl in enumerate(W_PLATES):
    hd_surface(f"az_web_{_n}", (lambda k, pl=_pl: _membrane(pl, k)), 8, 3)
    hd_surface(f"az_web_awake_{_n}", (lambda k, pl=_pl: _membrane(pl, k, True)), 8, 2)

CLEAR = [30, 30, 32, 32]      # a clear texel of the 32 px cut-outs (edges of their thin plates)
ALL_FACES = ("north", "south", "east", "west", "up", "down")


def cutout(m, name, frm, to, tex, plane, glow=False):
    """A thin plate showing a whole 32 px cut-out texture on its two big faces (plane: 'x' = faces north/south,
    'z' = faces east/west, 'y' = faces up/down); the edges show a clear texel."""
    full = [0, 0, HS, HS]
    if plane == "x":
        uv = {"north": full, "south": [HS, 0, 0, HS], "east": CLEAR, "west": CLEAR, "up": CLEAR, "down": CLEAR}
    elif plane == "z":
        uv = {"east": full, "west": [HS, 0, 0, HS], "north": CLEAR, "south": CLEAR, "up": CLEAR, "down": CLEAR}
    else:
        uv = {"up": full, "down": [0, HS, HS, 0], "north": CLEAR, "south": CLEAR, "east": CLEAR, "west": CLEAR}
    return m.box(name, frm, to, tex, glow=glow, uv=uv, shade=not glow)


def flames(m, name, cx, y0, cz, w, h):
    """Two crossed cut-out planes of the animated blue flame."""
    cutout(m, name, (cx - w / 2, y0, cz - 0.01), (cx + w / 2, y0 + h, cz + 0.01), "az_flame", "x", glow=True)
    cutout(m, name, (cx - 0.01, y0, cz - w / 2), (cx + 0.01, y0 + h, cz + w / 2), "az_flame", "z", glow=True)


def spark(m, name, c, d):
    x, y, z = c
    cutout(m, name, (x - d / 2, y - d / 2, z - 0.01), (x + d / 2, y + d / 2, z + 0.01), "az_spark", "x", glow=True)
    cutout(m, name, (x - 0.01, y - d / 2, z - d / 2), (x + 0.01, y + d / 2, z + d / 2), "az_spark", "z", glow=True)


# ================================================================================================
# turned boxes and serpents
# ================================================================================================

def _vecs(th):
    r = math.radians(th)
    return (-math.sin(r), 0.0, -math.cos(r)), (math.cos(r), 0.0, -math.sin(r))


def snap(th):
    return round(th / 22.5) * 22.5


def ybox(m, name, c, lf, lu, lr, th, mat, **kw):
    """A box centred on c, lf long along the facing, lu tall, lr wide, turned th degrees (a multiple of 22.5) from north
    towards west (the game's y rotation)."""
    th = snap(th)
    # a hair's breadth bigger each time (by turns), so two parts never share a face plane (no flicker)
    j = 0.025 * (len(m.elements) % 4)
    lf, lu, lr = lf + j, lu + j * 1.3, lr + j * 0.7
    k = round(th / 90)
    rem = th - 90 * k
    sx, sz = (lr, lf) if k % 2 == 0 else (lf, lr)
    frm = (c[0] - sx / 2, c[1] - lu / 2, c[2] - sz / 2)
    to = (c[0] + sx / 2, c[1] + lu / 2, c[2] + sz / 2)
    rot = ("y", rem, tuple(c)) if abs(rem) > 1e-6 else None
    return m.box(name, frm, to, mat, rotation=rot, **kw)


def at(c, th, f=0.0, u=0.0, r=0.0):
    """The point f forward, u up, r right of c for something facing th."""
    F, R = _vecs(snap(th))
    return (c[0] + F[0] * f + R[0] * r, c[1] + u, c[2] + F[2] * f + R[2] * r)


def dragon_head(m, n, c, th, s=1.0, jaw=0.0, glow=True, mane=True, whiskers=True, lite=False, spikes=True, mid=False):
    """An azure dragon's head centred on c facing th, s its size. Silhouette first: wide cheeks under a narrower crown,
    a snout stepping down to a nose bump, brow ridges swept back, cheek spikes and white horns sweeping back in three
    thinning steps, a white mane and beard, glowing eyes, long whiskers trailing back and out. jaw: how far the mouth
    is open (units), with fangs and a dark red mouth inside. lite: the few parts of a small far-away head; mid: a small
    head (no brows, two horn steps, one whisker piece); spikes: the cheek spikes and the beard."""
    def B(name, f, u, r, lf, lu, lr, mat, dth=0.0, **kw):
        ybox(m, f"{n}_{name}", at(c, th, f * s, u * s, r * s), lf * s, lu * s, lr * s, th + dth, mat, **kw)
    B("cheeks", -0.5, -0.3, 0, 3.4, 1.8, 3.4, SCALE)
    B("crown", -0.9, 1.05, 0, 3.0, 1.1, 2.6, SCALE_DARK if lite else SCALE)
    B("snout", 1.9, -0.1, 0, 1.8, 1.8, 2.4, SCALE)
    B("snout_tip", 3.25, -0.35, 0, 1.4, 1.4, 1.9, SCALE)
    B("jaw", 2.0, -1.45 - jaw, 0, 3.0, 0.75, 2.0, BELLY)
    if jaw > 0.2:
        B("mouth", 1.9, -1.0 - jaw / 2, 0, 2.6, jaw, 1.8, MOUTH)
        for side in (-0.6, 0.6):
            B(f"fang{side}", 3.4, -0.9, side, 0.4, 0.7, 0.4, TOOTH)
            B(f"fangl{side}", 3.2, -1.1 - jaw + 0.35, side, 0.4, 0.6, 0.4, TOOTH)
    if not lite:
        B("nose", 3.55, 0.5, 0, 0.9, 0.5, 1.4, SCALE_DARK)
    for side in (-1, 1):
        B(f"eye{side}", 0.9, 0.55, side * 1.72, 0.9, 0.5, 0.1, EYE if glow else SCALE_DARK)
        if not lite and not mid:
            # brow ridges swept back and out over the eyes
            B(f"brow{side}", 0.5, 1.15, side * 1.2, 1.9, 0.5, 0.7, SCALE_DARK, dth=side * 22.5)
        # horns: three steps sweeping up and back, thinning
        B(f"horn_a{side}", -1.6, 1.85, side * 0.95, 1.0, 1.2, 0.8, HORN)
        if not lite:
            B(f"horn_b{side}", -2.5, 2.7, side * 1.1, 1.2, 1.0, 0.65, HORN)
            if not mid:
                B(f"horn_c{side}", -3.5, 3.4, side * 1.25, 1.2, 0.7, 0.5, HORN)
        if spikes and not lite:
            # a spike off each cheek, pointing back and out
            B(f"cheek_spike{side}", -1.6, -0.7, side * 2.0, 1.8, 0.5, 0.4, HORN, dth=side * 45)
        if whiskers:
            # whiskers: from the snout's side, trailing back and out in two turned pieces, drooping
            B(f"whisker_a{side}", 2.6, -0.45, side * 1.85, 1.7, 0.3, 0.3, CYAN, dth=side * 45)
            if not mid:
                B(f"whisker_b{side}", 1.4, -0.85, side * 2.85, 1.6, 0.3, 0.3, CYAN, dth=side * 22.5)
        if mane:
            B(f"mane{side}", -2.4, 0.1, side * 1.75, 1.6, 2.2, 0.7, MANE)
    if mane:
        B("mane_top", -2.6, 1.1, 0, 1.4, 1.3, 2.9, MANE)
        if spikes and not lite:
            B("beard", 0.5, -2.05 - jaw, 0, 1.4, 0.9, 1.5, MANE)


def serpent(m, n, pts, widths, ths, spine_every=2, glow_spines=True, belly=True):
    """A scaled body through pts (centres), each segment widths[i] thick, facing ths[i]; pale belly underneath, glowing
    spines along the back every spine_every segments."""
    for i, (p, w, th) in enumerate(zip(pts, widths, ths)):
        nxt = pts[i + 1] if i + 1 < len(pts) else pts[i - 1]
        ln = max(w * 0.9, math.dist((p[0], p[2]), (nxt[0], nxt[2])) * 1.08)
        mat = SCALE if i % 2 == 0 else SCALE_DARK
        ybox(m, f"{n}_seg", p, ln, w * 0.9, w, th, mat, faces={"down": BELLY} if belly else None)
        if spine_every and i % spine_every == 0 and i > 0:
            ybox(m, f"{n}_spine", (p[0], p[1] + w * 0.45 + 0.35, p[2]), w * 0.45, 0.7, 0.35, th, SCALE_GLINT if glow_spines else HORN)


def _snap_rot(a):
    """An angle (degrees) as k quarter turns and a remainder of at most 45 (a multiple of 22.5)."""
    a = round(a / 22.5) * 22.5
    k = round(a / 90)
    return k, a - 90 * k


def seg_xy(m, name, p0, p1, d, t, z, mat, ext=0.5, **kw):
    """A bar from p0 to p1 (x, y) in the plane z (centred), d wide across and t deep, turned about z (the game turns
    +y towards -x for a positive angle); ext: how far it runs past both ends (hides the joints)."""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    ln = math.hypot(dx, dy) + 2 * ext
    k, rem = _snap_rot(math.degrees(math.atan2(-dx, dy)))
    cx, cy = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
    sx, sy = (d, ln) if k % 2 == 0 else (ln, d)
    rot = ("z", rem, (cx, cy, z)) if abs(rem) > 1e-6 else None
    return m.box(name, (cx - sx / 2, cy - sy / 2, z - t / 2), (cx + sx / 2, cy + sy / 2, z + t / 2), mat, rotation=rot, **kw)


def seg_yz(m, name, p0, p1, w, t, x, mat, ext=0.5, **kw):
    """A bar from p0 to p1 (y, z) in the plane x (centred), w wide in x and t thick, turned about x (the game turns
    +y towards +z for a positive angle)."""
    dy, dz = p1[0] - p0[0], p1[1] - p0[1]
    ln = math.hypot(dy, dz) + 2 * ext
    k, rem = _snap_rot(math.degrees(math.atan2(dz, dy)))
    cy, cz = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
    sy, sz = (ln, t) if k % 2 == 0 else (t, ln)
    rot = ("x", rem, (x, cy, cz)) if abs(rem) > 1e-6 else None
    return m.box(name, (x - w / 2, cy - sy / 2, cz - sz / 2), (x + w / 2, cy + sy / 2, cz + sz / 2), mat, rotation=rot, **kw)


def ring_path(radius, start, steps, y=8.0, wave=0.0, waves=1.0, cx=8.0, cz=8.0, step=22.5, rise=0.0):
    """Points on a circle round (cx, cz): from angle start (degrees) in steps of step, each with the facing of a body
    moving towards smaller angles (the way the orbit turns it)."""
    pts, ths = [], []
    for i in range(steps):
        a = start + i * step
        r = math.radians(a)
        pts.append((cx + radius * math.cos(r), y + wave * math.sin(i * math.pi * waves / max(1, steps - 1)) + rise * i, cz + radius * math.sin(r)))
        ths.append(-a)
    return pts, ths


# ================================================================================================
# 여의주 (above, spin)
# ================================================================================================

def _pearl_cloud(m, awake):
    m.group("cloud")
    lumps = [(3, 8.6, 4.5, 13, 10.6, 11.5, CLOUD), (4.5, 8.8, 3, 11.5, 10.8, 13, CLOUD),
             (2.4, 9.6, 6, 5.6, 12.2, 10.5, CLOUD), (10.4, 9.7, 5.5, 13.6, 11.9, 10, CLOUD),
             (5.5, 9.8, 10.4, 10, 12.0, 13.6, CLOUD), (6, 9.8, 2.4, 10.5, 11.7, 5.6, CLOUD),
             (3.6, 11.3, 7, 5.2, 12.9, 9.5, CLOUD), (10.9, 11.2, 6.4, 12.8, 12.6, 8.8, CLOUD),
             (4, 8.2, 5, 12, 8.8, 11, CLOUD_SHADE)]
    for (x0, y0, z0, x1, y1, z1, mat) in lumps:
        m.box("cloud", (x0, y0, z0), (x1, y1, z1), mat, faces={"down": CLOUD_SHADE})
    # wisps curling off two corners, turned 22.5 so the cloud is not a box
    for th, c in ((22.5, (14.2, 9.9, 9.2)), (202.5, (1.8, 9.9, 6.8))):
        ybox(m, "wisp", c, 3.2, 1.0, 1.4, th, CLOUD)
        ybox(m, "wisp_curl", at(c, th, 1.6, 0.7, 0), 1.2, 1.0, 1.1, th, CLOUD_GLOW if awake else CLOUD)
    m.group("pearl")
    m.box("seat", (6.5, 11.8, 6.5), (9.5, 12.6, 9.5), GOLD)
    for th in (0, 90, 180, 270):
        ybox(m, "claw", at((8, 13.0, 8), th, 1.6), 0.6, 1.8, 0.8, th, GOLD_LIGHT)
        ybox(m, "claw_tip", at((8, 14.2, 8), th, 1.9), 0.5, 0.8, 0.6, th, GOLD_LIGHT)
    d = 5.5 if awake else 4.8      # awakened: the pearl a little bigger (+15%), the halo carries the rest
    x, y, z = 8, 12.4 + d / 2 + 0.2, 8
    m.box("pearl", (x - d * 0.4, y - d * 0.4, z - d * 0.4), (x + d * 0.4, y + d * 0.4, z + d * 0.4), "az_pearl", glow=True, full_uv=ALL_FACES)
    for (ax, ay, az) in ((0.5, 0.25, 0.25), (0.25, 0.5, 0.25), (0.25, 0.25, 0.5)):
        m.box("pearl", (x - d * ax, y - d * ay, z - d * az), (x + d * ax, y + d * ay, z + d * az), "az_pearl", glow=True, full_uv=ALL_FACES)
    m.group("flames")
    pos = ((8, 4.4), (8, 11.6), (4.4, 8), (11.6, 8)) if not awake else \
        ((8, 4.1), (8, 11.9), (4.1, 8), (11.9, 8), (5.2, 5.2), (10.8, 10.8))
    for (fx, fz) in pos:
        flames(m, "flame", fx, 11.2, fz, 3.0, 7.0 if not awake else 7.8)
    for (sx, sy, sz) in ((3.5, 16.0, 4.0), (12.2, 17.2, 11.6), (4.2, 18.0, 11.8), (11.8, 14.8, 3.6))[:2 if awake else 4]:
        spark(m, "spark", (sx, sy, sz), 1.6)
    if awake:
        # a halo of light round the pearl: an octagonal ring of eight turned bars
        for i in range(8):
            th = i * 45
            ybox(m, "halo", at((8, y + 0.2, 8), th, 4.7), 0.55, 0.55, 4.0, th, CYAN)
    # the whole piece about as wide as the head (the cloud a little wider), sitting on it
    shrink(m, 0.7, (8.0, 8.2, 8.0))


def shrink(m, k, pivot):
    """Scales every element of the model by k about pivot (the angles stay: a uniform scale), for a piece built at one
    size and worn smaller. The painted textures keep their texels, so they only get finer."""
    def f(q):
        return tuple(pivot[i] + (q[i] - pivot[i]) * k for i in range(3))
    for e in m.elements:
        e["from"], e["to"] = list(f(e["from"])), list(f(e["to"]))
        if e.get("rotation"):
            axis, angle, o = e["rotation"]
            e["rotation"] = (axis, angle, f(o))


def azure_pearl_cloud():
    """청룡의 여의주: a soft cloud cushion with wisps, a gold four-claw seat, the dragon's pearl with light running round
    it, four blue flames and sparks. Turns (spin)."""
    m = MModel("azure_pearl_cloud")
    _pearl_cloud(m, False)
    _display(m, HEAD_GUI, 1.05, -5)
    return m


def azure_pearl_cloud_awakened():
    """각성 여의주: the same cloud, a pearl 15% bigger inside a halo of light, six flames, glowing wisps."""
    m = MModel("azure_pearl_cloud_awakened")
    _pearl_cloud(m, True)
    _display(m, HEAD_GUI, 0.95, -5)
    return m


# ================================================================================================
# 날개 (back, flap)
# ================================================================================================

def _wings(m, awake):
    Z = 10.6
    for side in (1, -1):
        def X(p):
            return (p[0], p[1]) if side == 1 else (16 - p[0], p[1])
        # the arm: root to elbow to wrist, thinning, dark scales; white spikes along its top; the thumb with a gold claw
        seg_xy(m, "arm", X(W_ROOT), X(W_ELBOW), 2.0, 2.0, Z, BONE)
        seg_xy(m, "arm", X(W_ELBOW), X(W_WRIST), 1.6, 1.7, Z, BONE_LIGHT)
        m.box("wrist", (X(W_WRIST)[0] - 1.1, W_WRIST[1] - 1.1, Z - 1.0), (X(W_WRIST)[0] + 1.1, W_WRIST[1] + 1.1, Z + 1.0), BONE)
        seg_xy(m, "thumb", X(W_WRIST), X(W_THUMB), 0.9, 0.9, Z, BONE_LIGHT, ext=0.2)
        seg_xy(m, "thumb_claw", X(W_THUMB), X((W_THUMB[0] + 0.6, W_THUMB[1] + 1.6)), 0.7, 0.7, Z, GOLD_LIGHT, ext=0.1)
        for (a0, a1) in ((W_ROOT, W_ELBOW), (W_ELBOW, W_WRIST)):
            for t in (0.35, 0.75):
                q = (a0[0] + (a1[0] - a0[0]) * t - 0.55, a0[1] + (a1[1] - a0[1]) * t + 0.75)
                seg_xy(m, "spike", X(q), X((q[0] - 0.6, q[1] + 1.4)), 0.55, 0.55, Z, HORN, ext=0.0)
        # the fingers radiate from the wrist, each tipped with a white claw
        for tip in W_TIPS:
            seg_xy(m, "finger", X(W_WRIST), X(tip), 0.7, 0.7, Z, BONE_LIGHT, ext=0.1)
            dx, dy = tip[0] - W_WRIST[0], tip[1] - W_WRIST[1]
            ln = math.hypot(dx, dy)
            seg_xy(m, "claw", X(tip), X((tip[0] + dx / ln * 1.2, tip[1] + dy / ln * 1.2)), 0.5, 0.5, Z, HORN, ext=0.0)
        # the membrane: four cut-out plates, a little behind the bones, the outer ones a step further back (depth)
        for n, (x0, x1, y0, y1) in enumerate(W_PLATES):
            z = Z - 0.55 - (0.0 if x0 < 12 else 0.3) - (0.12 if y0 > 10 else 0.0)   # in front of the bones (they show from behind)
            wpx, hpx = round((x1 - x0) * 2), round((y1 - y0) * 2)
            front, back = [0, 0, wpx, hpx], [wpx, 0, 0, hpx]
            south, north = (front, back) if side == 1 else (back, front)
            a0, a1 = (x0, x1) if side == 1 else (16 - x1, 16 - x0)
            tex = f"az_web_awake_{n}" if awake else f"az_web_{n}"
            m.box("web", (a0, y0, z), (a1, y1, z + 0.1), tex, glow=awake,
                  uv={"south": south, "north": north, "east": CLEAR, "west": CLEAR, "up": CLEAR, "down": CLEAR})
        if awake:
            # blue fire rising off the arm
            for i, (t, (a0, a1)) in enumerate([(t, seg) for t in (0.3, 0.7) for seg in ((W_ROOT, W_ELBOW), (W_ELBOW, W_WRIST))]):
                q = (a0[0] + (a1[0] - a0[0]) * t, a0[1] + (a1[1] - a0[1]) * t + 1.0)
                xq, zq = X(q)[0], Z + 0.13 * i
                cutout(m, "arm_fire", (xq - 1.4, q[1], zq - 0.05), (xq + 1.4, q[1] + 3.6, zq + 0.05), "az_flame", "x", glow=True)


def azure_dragon_wings():
    """청룡의 날개: arms of dark azure scales tapering to a gold claw, white spikes, four long fingers, a painted
    sapphire membrane lighter near the arm whose scalloped edge glows and shimmers. Beats (flap)."""
    m = MModel("azure_dragon_wings")
    _wings(m, False)
    _display(m, BACK_GUI, 0.34, -2)
    return m


def azure_dragon_wings_awakened():
    """각성 날개: the membrane's edge burns with blue fire, a glowing membrane, flames along the arms."""
    m = MModel("azure_dragon_wings_awakened")
    _wings(m, True)
    _display(m, BACK_GUI, 0.32, -2)
    return m


# ================================================================================================
# 꼬리 (tail, sway)
# ================================================================================================

TAIL_PATH = [(8.0, 10.2), (6.1, 11.7), (4.0, 13.5), (2.0, 15.6), (0.5, 18.0), (-0.3, 20.6), (0.0, 23.0), (1.3, 25.0),
             (3.3, 26.2), (5.6, 26.3)]     # (y, z) control points: down from the back, along the ground, curling up


def _spline(pts, n):
    """n + 1 points along a Catmull-Rom curve through pts, spaced evenly by length."""
    dense = []
    ext = [pts[0]] + list(pts) + [pts[-1]]
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        for k in range(20):
            t = k / 20
            dense.append(tuple(0.5 * (2 * p1[j] + (-p0[j] + p2[j]) * t + (2 * p0[j] - 5 * p1[j] + 4 * p2[j] - p3[j]) * t * t
                                      + (-p0[j] + 3 * p1[j] - 3 * p2[j] + p3[j]) * t ** 3) for j in range(2)))
    dense.append(pts[-1])
    acc = [0.0]
    for a_, b_ in zip(dense, dense[1:]):
        acc.append(acc[-1] + math.dist(a_, b_))
    out, j = [], 0
    for i in range(n + 1):
        want = acc[-1] * i / n
        while j < len(acc) - 2 and acc[j + 1] < want:
            j += 1
        f = 0 if acc[j + 1] == acc[j] else (want - acc[j]) / (acc[j + 1] - acc[j])
        out.append(tuple(dense[j][k] + (dense[j + 1][k] - dense[j][k]) * f for k in range(2)))
    return out


class BodyMat(HMat):
    """Painted scales with no rims, edge shadows or per-face gradients: the light depends only on which way a face
    looks, so a chain of short turned pieces shades as one body (the base steps a little from piece to piece for a
    gradient running down its length)."""

    def _value(self, x, y, pw, ph, face, rnd):
        b = self.base + FACE_LIGHT.get(face, 0) * self.contrast
        sw, sh = 6, 5
        row = y // sh
        cx = (x + (sw // 2 if row % 2 else 0)) % sw
        cy = y % sh
        dx, dy = (cx - sw / 2 + 0.5) / (sw / 2), (cy - 0.5) / sh
        b += 0.45 - 0.9 * min(1.0, dx * dx + dy * dy * 1.6)
        if cy == sh - 1:
            b -= 0.55
        if cx in (1, 2) and cy == 1:
            b += 1.0
        return b + (rnd.random() - 0.5) * 0.14


def azure_dragon_tail():
    """청룡의 꼬리: one smooth tapering body that droops from the back to the ankles and curls up: many short turned
    segments along a curve (the width shrinking a little at each), all of the same painted scales so the shading runs on,
    a pale belly, a row of overlapping back plates (each lying over the next) with a glowing spine on every third, a
    cut-out fin and a blue flame at the tip. Swings (sway)."""
    m = MModel("azure_dragon_tail")
    pts = _spline(TAIL_PATH, 15)
    n = len(pts) - 1
    for i, (p0, p1) in enumerate(zip(pts, pts[1:])):
        t = i / (n - 1)
        w = 4.0 - 2.75 * t
        body = BodyMat("sapphire", "scales", 2.25 - 0.45 * t)
        seg_yz(m, "tail", p0, p1, w, w * 0.86, 8.0, body, ext=0.55)
        # the back plate: on the outer side, a little longer than the segment so each overlaps the next
        dy, dz = p1[0] - p0[0], p1[1] - p0[1]
        ln = math.hypot(dy, dz)
        ny, nz = dz / ln, -dy / ln
        if ny < 0:
            ny, nz = -ny, -nz
        off = w * 0.43 + 0.12
        q0 = (p0[0] + ny * off, p0[1] + nz * off)
        q1 = (p1[0] + ny * off, p1[1] + nz * off)
        seg_yz(m, "plate", q0, q1, w * 0.62, 0.45, 8.0, BodyMat("sapphire", "scales", 1.75 - 0.4 * t), ext=0.35)
        if i % 3 == 1 and i < n - 1:
            mid = ((q0[0] + q1[0]) / 2 + ny * 0.25, (q0[1] + q1[1]) / 2 + nz * 0.25)
            seg_yz(m, "spine", mid, (mid[0] + ny * 1.1, mid[1] + nz * 1.1), 0.45, 0.6, 8.0, SCALE_GLINT, ext=0.0)
    m.group("tip")
    end = pts[-1]
    cutout(m, "fin", (7.95, end[0] - 1.0, end[1] - 3.6), (8.05, end[0] + 3.4, end[1] + 0.6), "az_fin", "z")
    flames(m, "tip_flame", 8, end[0] + 0.4, end[1] + 0.4, 3.4, 6.0)
    _display(m, (30, 135, 0), 0.5, 1)
    return m


# ================================================================================================
# 어깨 용 (shoulder)
# ================================================================================================

def azure_shoulder_dragon():
    """청룡의 어깨 용: a little azure dragon coiled on the shoulder, its body in a loop round the shoulder top, head raised
    looking forward, glowing eyes and whiskers, a white mane."""
    m = MModel("azure_shoulder_dragon")
    pts, ths = ring_path(3.4, 90, 12, y=8.9, wave=0.0, cx=8.0, cz=8.6, step=-27.0)
    # a coil lying on the shoulder: thick at the head end, thin at the tail
    widths = [2.4 - 0.13 * i for i in range(len(pts))]
    serpent(m, "coil", pts, widths, [t + 180 for t in ths], spine_every=3)
    # the neck rising from the coil's front to the head
    ybox(m, "neck", (8.0, 10.6, 6.4), 1.8, 2.6, 2.0, 0, SCALE, faces={"down": BELLY})
    ybox(m, "neck2", (8.0, 12.2, 5.8), 1.6, 1.6, 1.8, 0, SCALE_DARK)
    dragon_head(m, "head", (8.0, 13.6, 4.6), 0, 0.62)
    _display(m, HEAD_GUI, 0.95, -2)
    return m


# ================================================================================================
# 구름 띠 (waist)
# ================================================================================================

def azure_cloud_sash():
    """청룡의 구름 띠: a sapphire sash round the waist with gold trims and cloud puffs on the hips, a gold dragon head
    buckle at the front with a glowing pearl in its jaws, and behind a bow of cloud: two puffed loops and two short
    wide tails flaring out and curling up."""
    m = MModel("azure_cloud_sash")
    # the band: a sapphire sash just round the torso (x 4..12, z 6..10), gold trims above and below
    m.box("band", (3.55, 5.5, 5.55), (12.45, 7.4, 10.45), SCALE_DARK)
    m.box("trim_low", (3.45, 5.25, 5.45), (12.55, 5.55, 10.55), GOLD)
    # cloud clusters on the hips and the back: a big puff, a smaller one riding on it, a wisp trailing out
    for (cx, cz, s_) in ((3.6, 8.0, 1.0), (12.4, 8.0, 1.0)):
        m.box("puff", (cx - 1.5 * s_, 5.6, cz - 1.7 * s_), (cx + 1.5 * s_, 7.6, cz + 1.7 * s_), CLOUD, faces={"down": CLOUD_SHADE})
        m.box("puff_top", (cx - 0.9 * s_, 7.55, cz - 1.1 * s_), (cx + 0.9 * s_, 8.6, cz + 0.6 * s_), CLOUD)
        m.box("puff_low", (cx - 1.0 * s_, 4.7, cz - 0.4 * s_), (cx + 1.0 * s_, 5.65, cz + 1.3 * s_), CLOUD_SHADE)
    # the buckle: a gold dragon face with the pearl in its jaws
    m.box("buckle", (6.4, 5.0, 4.6), (9.6, 8.6, 5.4), GOLD)
    m.box("buckle_brow", (6.0, 7.8, 4.4), (10.0, 8.8, 5.0), GOLD_LIGHT)
    for side in (-1, 1):
        m.box("buckle_horn", (8 + side * 1.5 - 0.4, 8.6, 4.7), (8 + side * 1.5 + 0.4, 10.0, 5.2), GOLD_LIGHT)
        m.box("buckle_eye", (8 + side * 0.9 - 0.35, 7.0, 4.45), (8 + side * 0.9 + 0.35, 7.5, 4.6), EYE)
    m.box("buckle_pearl", (7.2, 5.3, 3.9), (8.8, 6.9, 4.6), "az_pearl", glow=True, full_uv=ALL_FACES)
    # a gold trim along the band's top
    m.box("trim", (3.45, 7.35, 5.45), (12.55, 7.65, 10.55), GOLD_LIGHT)
    # behind: a cloud bow. The knot, two puffed loops tilted up and out (a shadowed hollow in each), and two short wide
    # tails that flare outward and down in turned pieces, waving front and back, ending in a curl that turns up
    m.box("knot", (6.9, 5.4, 10.35), (9.1, 7.7, 11.9), CLOUD, faces={"down": CLOUD_SHADE})
    for side in (-1, 1):
        cx = 8 + side * 2.5
        m.box("loop", (cx - 1.7, 6.0, 10.5), (cx + 1.7, 8.3, 11.75), CLOUD, rotation=("z", side * 22.5, (cx, 7.15, 11.1)),
              faces={"down": CLOUD_SHADE})
        m.box("loop_hollow", (cx - 0.7, 6.75, 11.75), (cx + 0.7, 7.55, 11.85), CLOUD_SHADE, rotation=("z", side * 22.5, (cx, 7.15, 11.8)))
        for i in range(3):
            tx, ty, tz = 8 + side * (1.3 + 1.15 * i), 4.9 - 1.25 * i, 11.15 + (0.35 if i % 2 else 0.0)
            w = 2.0 + 0.35 * i
            m.box("tail", (tx - w / 2, ty - 0.95, tz - 0.35), (tx + w / 2, ty + 0.95, tz + 0.35), CLOUD if i % 2 == 0 else CLOUD_SHADE,
                  rotation=("z", side * (22.5 if i < 2 else 45.0), (tx, ty, tz)))
        m.box("tail_curl", (8 + side * 4.6 - 0.8, 0.9, 11.0), (8 + side * 4.6 + 0.8, 2.3, 12.4), CLOUD_GLOW,
              rotation=("z", side * -22.5, (8 + side * 4.6, 1.6, 11.7)))
    _display(m, HEAD_GUI, 0.9, 0)
    return m


# ================================================================================================
# 아기 청룡 (pet, floats)
# ================================================================================================

def azure_baby_dragon():
    """아기 청룡 (a pet that floats): a little eastern dragon swimming through the air in an S, rising and dipping, its
    body thick at the chest and tapering to the tail, a white mane running down its back, four tiny gold-clawed legs,
    a big head with horns and whiskers, the pearl it carries under its chin and a glowing cloud puff at its tail."""
    m = MModel("azure_baby_dragon")
    pts = [(8.0, 6.6, 7.6), (8.6, 6.0, 9.7), (9.4, 5.4, 11.7), (9.2, 5.6, 13.7), (8.2, 6.4, 15.4), (7.1, 7.4, 16.9), (6.6, 8.3, 18.4)]
    ths = [0, -22.5, 0, 22.5, 45, 22.5, 0]
    widths = [3.0, 2.8, 2.5, 2.2, 1.9, 1.6, 1.3]
    serpent(m, "body", pts, widths, ths, spine_every=0)
    # the mane down the back: white tufts on alternate segments
    for i in (2, 4):
        p = pts[i]
        ybox(m, "mane", (p[0], p[1] + widths[i] * 0.45 + 0.4, p[2]), 1.2, 0.9, 0.9, ths[i], MANE)
    dragon_head(m, "head", (8.0, 8.4, 4.4), 0, 0.9, spikes=False, mid=True)
    for (i, side) in ((0, -1), (0, 1), (3, -1), (3, 1)):
        p = pts[i]
        r = widths[i] * 0.5
        leg = at(p, ths[i], 0, -widths[i] * 0.45 - 0.6, side * r)
        ybox(m, "claw", leg, 0.9, 1.0, 0.8, ths[i], GOLD_LIGHT)
    m.box("tail_puff", (5.4, 7.6, 18.8), (7.8, 9.8, 20.8), CLOUD_GLOW)
    m.box("pearl", (7.25, 3.9, 5.6), (8.75, 5.4, 7.1), "az_pearl", glow=True, full_uv=ALL_FACES)
    _display(m, (30, 225, 0), 0.8, 0)
    return m


# ================================================================================================
# 구름 발자국 (trail prints) and the trail's card
# ================================================================================================

def _print(name, tex, awake=False):
    m = MModel(name)
    # the cloud foot itself: one cut-out plate lying flat (seen from above), toes north
    cutout(m, "print", (2.5, 8.0, 1.0), (13.5, 8.1, 15.0), tex, "y", glow=True)
    # a little height: the heel and the ball as soft puffs under the plate's middle
    m.box("heel", (5.5, 7.6, 9.0), (10.5, 7.98, 13.5), CLOUD)
    m.box("ball", (5.0, 7.6, 4.0), (11.0, 7.98, 8.0), CLOUD)
    if awake:
        cutout(m, "bolt", (7.95, 8.0, 3.0), (8.05, 14.0, 9.0), "az_bolt", "z", glow=True)
        cutout(m, "bolt", (5.0, 8.0, 5.95), (11.0, 14.0, 6.05), "az_bolt", "x", glow=True)
    m.display = {"head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    return m


def azure_cloud_print_a():
    """구름 발자국 (frame a): a glowing cloud footprint left on the ground."""
    return _print("azure_cloud_print_a", "az_print_a")


def azure_cloud_print_b():
    """구름 발자국 (frame b): the same with the sparks moved (the print flickers between a and b)."""
    return _print("azure_cloud_print_b", "az_print_b")


def azure_cloud_print_awakened():
    """각성 발자국: a lightning bolt crackles up out of each print."""
    return _print("azure_cloud_print_awakened", "az_print_a", awake=True)


def azure_cloud_trail():
    """Card for 청룡의 구름 발자국: two cloud footprints walking forward."""
    m = MModel("azure_cloud_trail")
    cutout(m, "print", (1.0, 4.0, 1.0), (8.0, 4.1, 10.0), "az_print_a", "y", glow=True)
    cutout(m, "print", (8.5, 4.0, 6.0), (15.5, 4.1, 15.0), "az_print_b", "y", glow=True)
    m.box("puff", (2.5, 3.6, 4.5), (6.5, 3.98, 8.5), CLOUD)
    m.box("puff", (10.0, 3.6, 9.5), (14.0, 3.98, 13.5), CLOUD)
    spark(m, "spark", (12.0, 8.0, 4.0), 2.4)
    spark(m, "spark", (3.5, 7.0, 12.0), 1.8)
    _display(m, (55, 225, 0), 0.95, 0)
    return m


# ================================================================================================
# 뇌룡 (aura: worn, orbit) and the aura's card
# ================================================================================================

def _aura_dragon(m, radius, segs, awake):
    # the head leads (smallest angle), the body trails round the circle, waving up and down
    pts, ths = ring_path(radius, 0, segs, y=8.0, wave=1.6, waves=2.0)
    widths = [2.3 - 1.4 * i / (segs - 1) for i in range(segs)]
    serpent(m, "body", pts[1:], widths[1:], ths[1:], spine_every=3 if awake else 2)
    head_at = at(pts[0], ths[0], 1.2, 0.4, 0)
    dragon_head(m, "head", head_at, ths[0], 0.75 if not awake else 0.9, spikes=False, mid=True)
    # claws under the front of the body
    for i in ((2,) if awake else (2, 5)):
        p = pts[i]
        ybox(m, "claw", (p[0], p[1] - widths[i] * 0.6, p[2]), 0.6, 1.0, 0.6, ths[i], GOLD_LIGHT)
    tail = pts[-1]
    m.box("tail_puff", (tail[0] - 1.0, tail[1] - 0.8, tail[2] - 1.0), (tail[0] + 1.0, tail[1] + 1.0, tail[2] + 1.0), CLOUD_GLOW)
    if awake:
        # a second, smaller dragon below, half a turn behind: two loops round the body
        pts2, ths2 = ring_path(radius - 1.0, 180, segs - 5, y=4.0, wave=1.2, waves=2.0)
        widths2 = [1.9 - 1.1 * i / (segs - 6) for i in range(segs - 5)]
        serpent(m, "body2", pts2[1:], widths2[1:], ths2[1:], spine_every=0)
        dragon_head(m, "head2", at(pts2[0], ths2[0], 1.0, 0.3, 0), ths2[0], 0.6, mane=False, whiskers=False, lite=True)
        for p in (pts[3], pts2[4]):
            cutout(m, "bolt", (p[0] - 0.05, p[1] + 1.0, p[2] - 1.5), (p[0] + 0.05, p[1] + 4.0, p[2] + 1.5), "az_bolt", "z", glow=True)


def azure_aura_dragon():
    """청룡의 뇌룡 (worn): a little azure dragon circling the chest, head first, its body waving up and down, glowing
    spines; turned round the body by the orbit motion."""
    m = MModel("azure_aura_dragon")
    _aura_dragon(m, 11.0, 12, False)
    _display(m, HEAD_GUI, 0.5, 0)
    return m


def azure_aura_dragon_awakened():
    """각성 뇌룡: wider, with a second dragon looping lower round the body and lightning between them."""
    m = MModel("azure_aura_dragon_awakened")
    _aura_dragon(m, 12.5, 12, True)
    _display(m, HEAD_GUI, 0.45, 0)
    return m


def azure_spark_aura():
    """Card for 청룡의 뇌룡: the circling dragon seen from above."""
    m = MModel("azure_spark_aura")
    _aura_dragon(m, 6.0, 10, False)
    _display(m, (60, 225, 0), 1.0, 0)
    return m


# ================================================================================================
# 포효 (kill burst) and its card
# ================================================================================================

def azure_roar_burst():
    """청룡의 포효 (a kill burst): a dragon's head rising out of a ring of cloud, jaws wide open, lightning on both sides;
    grows and rises where the victim fell, then sinks away."""
    m = MModel("azure_roar_burst")
    for i in range(8):
        th = i * 45
        ybox(m, "cloud_ring", at((8, 8.6, 8), th, 5.0), 2.6, 1.8, 4.6, th, CLOUD, faces={"down": CLOUD_SHADE})
    ybox(m, "neck", (8, 10.6, 8.8), 3.2, 4.2, 3.4, 0, SCALE, faces={"down": BELLY})
    ybox(m, "neck_spine", (8, 13.0, 9.6), 1.2, 1.0, 0.5, 0, SCALE_GLINT)
    dragon_head(m, "head", (8, 14.2, 7.0), 0, 1.35, jaw=1.6)
    for side in (-1, 1):
        cutout(m, "bolt", (8 + side * 6.0 - 0.05, 9.0, 4.0), (8 + side * 6.0 + 0.05, 17.0, 12.0), "az_bolt", "z", glow=True)
    m.display = {"head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
                 "gui": {"rotation": [20, 205, 0], "translation": [0, -4, 0], "scale": [0.55] * 3}}
    return m


def azure_dragon_roar():
    """Card for 청룡의 포효: the roaring head over its cloud ring."""
    m = azure_roar_burst()
    m.name = "azure_dragon_roar"
    return m


# ================================================================================================
# 세트 각성 고리 (cosmetic_sets.yml awaken.model): at the feet, worn at the aura anchor
# ================================================================================================

def azure_set_ring():
    """세트 각성 고리: a ring of glowing cloud at the feet (14.4 units under the aura anchor) with four lightning bolts
    rising from it; turned slowly round (orbit)."""
    m = MModel("azure_set_ring")
    y = 8.0 - 14.2
    for i in range(16):
        th = i * 22.5
        ybox(m, "ring", at((8, y + 0.4, 8), th, 9.0), 1.0, 0.8, 3.7, th, CLOUD_GLOW if i % 2 == 0 else CLOUD)
    for i in range(4):
        th = i * 90 + 45
        p = at((8, y, 8), th, 9.0)
        cutout(m, "bolt", (p[0] - 0.05, y + 0.6, p[2] - 1.5), (p[0] + 0.05, y + 5.6, p[2] + 1.5), "az_bolt", "z", glow=True)
    m.display = {"head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    return m


SLOTS = {"azure_pearl_cloud": "above", "azure_dragon_wings": "back", "azure_dragon_tail": "tail",
         "azure_shoulder_dragon": "shoulder", "azure_cloud_sash": "waist", "azure_baby_dragon": "pet",
         "azure_cloud_trail": "trail", "azure_spark_aura": "aura", "azure_dragon_roar": "kill",
         # models shown by the engine, not items
         "azure_pearl_cloud_awakened": "above", "azure_dragon_wings_awakened": "back",
         "azure_aura_dragon": "aura", "azure_aura_dragon_awakened": "aura",
         "azure_cloud_print_a": "print", "azure_cloud_print_b": "print", "azure_cloud_print_awakened": "print",
         "azure_roar_burst": "burst", "azure_set_ring": "aura"}

ALL = {f.__name__: f for f in (azure_pearl_cloud, azure_pearl_cloud_awakened, azure_dragon_wings, azure_dragon_wings_awakened,
                               azure_dragon_tail, azure_shoulder_dragon, azure_cloud_sash, azure_baby_dragon,
                               azure_cloud_print_a, azure_cloud_print_b, azure_cloud_print_awakened, azure_cloud_trail,
                               azure_aura_dragon, azure_aura_dragon_awakened, azure_spark_aura,
                               azure_roar_burst, azure_dragon_roar, azure_set_ring)}
