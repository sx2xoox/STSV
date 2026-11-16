"""
은하수 세트 (galaxy) v2: nine pieces that fill the body, painted by the donation rules (premium_art.py, docs/ART_STYLE.md
"후원 전용 고급 규칙": 32 texels a block, painted shading, cut-out edges, animated glowing parts), with awakened looks for a
whole set and model effects instead of particles. A night sky in navy, violet and gold: elegant, not noisy.

  별관 (머리 위, spin)          · 망토 (등, sway)             · 초승달 목걸이 (목)        · 별 부엉이 (어깨)
  별자리 허리띠 (허리)          · 아기 별고래 (펫, floats)     · 별빛 발자국 (발자취: footprint models on the ground)
  행성 고리 (오라: a tilted ring of dust with planets circling the chest, orbit) · 초신성 (처치: a star bursting)
  세트 각성: the crown, the cape, the ring and the prints change to their awakened models; a spiral galaxy turns at the
  feet (cosmetic_sets.yml awaken), and a supernova rises round the body the moment the set completes.

Same frames as models/cosmetics.py (attached: the model's (8, 8, 8) on the slot's anchor, north = the player's front,
+x their right; pets stand on y = 0 facing north; prints lie on y = 8 facing north; bursts stand on y = 8).
"""

import math
import random
import zlib

import economy_models  # noqa: F401
from economy_machines import MModel
from models.cosmetics import _display, BACK_GUI, HEAD_GUI
from models.cosmetics_azure import cutout, ybox, at, seg_xy, seg_yz, ring_path, CLEAR, ALL_FACES
from premium_art import HMat, hd_canvas, hd_surface, shade, HS, D
from stsv_assets import textures as T

# ================================================================================================
# materials (HMat: 32 texels a block, painted)
# ================================================================================================

NAVY = HMat("indigo", "smooth", 1.2)
NAVY_DARK = HMat("indigo", "smooth", 0.55)
DUSK = HMat("obsidian", "smooth", 1.6)
VIOLET = HMat("crystal", "smooth", 1.6)
VIOLET_LIGHT = HMat("crystal", "smooth", 2.8)
VIOLET_GEM = HMat("magic", "gem", 2.2, glow=True)
GOLD = HMat("gold", "metal", 1.9)
GOLD_LIGHT = HMat("gold", "metal", 2.6)
GOLD_GLOW = HMat("lamp", "metal", 2.5, glow=True)
STAR_GEM = HMat("glow_amber", "gem", 2.7, glow=True)
ICE_GEM = HMat("ice", "gem", 2.7, glow=True)
EYE = HMat("glow_amber", "gem", 2.9, glow=True)
PUPIL = HMat("obsidian", "smooth", 0.3)
BELLY = HMat("crystal", "horn", 3.2)
FEATHER = HMat("crystal", "horn", 2.4)
DUST = HMat("crystal", "smooth", 3.0, glow=True, anim=8)
CHEEK = HMat("pink", "smooth", 1.6)
DUST_GOLD = HMat("lamp", "smooth", 2.6, glow=True, anim=8)


class SkyMat(HMat):
    """Night sky painted per face at 32 texels a block: deep navy lighter towards the top, faint violet nebula wisps,
    scattered stars that twinkle in turn (8 frames) and a few that flare into a cross; a soft light rim on top. glow:
    drawn at full light (the awakened looks)."""

    def __init__(self, base=1.0, glow=False, nebula=1.0, density=1.0):
        super().__init__("indigo", "smooth", base, glow=glow, anim=8)
        self.nebula, self.density = nebula, density

    def _sky(self, pw, ph, face, seed, k):
        rnd = random.Random(seed)
        im = hd_canvas()
        px = im.load()
        lit = {"up": 0.35, "north": 0.1, "west": 0.15, "south": -0.05, "east": -0.15, "down": -0.4}.get(face, 0)
        ox, oy = rnd.random() * 9, rnd.random() * 9
        for y in range(HS):
            for x in range(HS):
                xx, yy = min(x, pw - 1), min(y, ph - 1)
                u, v = (xx + 0.5) / pw, (yy + 0.5) / ph
                b = self.base + lit + 0.55 * (1 - v) - 0.25 * u
                neb = (math.sin(xx * 0.31 + ox) + math.sin((xx + yy) * 0.21 + oy) + 0.8 * math.cos(yy * 0.37 - ox)) * self.nebula
                if yy == 0 and ph > 2:
                    b += 0.45
                elif yy == ph - 1 and ph > 2:
                    b -= 0.3
                # the nebula: a soft veil of muted violet over the navy (a stippled edge, no hard blobs), a few
                # brighter violet hearts where it is thickest
                haze = max(0.0, min(1.0, (neb - 1.2) / 1.5))
                if rnd.random() < haze * 0.75:
                    px[x, y] = shade("crystal", haze * 0.9 + lit * 0.5 + (rnd.random() - 0.5) * 0.3)
                else:
                    px[x, y] = shade("indigo", b + haze * 0.6 + (rnd.random() - 0.5) * 0.12)
        n = max(1, int(pw * ph / 26 * self.density))
        for i in range(n):
            sx, sy = rnd.randrange(pw), rnd.randrange(ph)
            phase = rnd.randrange(8)
            b = (k + phase) % 8
            tint = rnd.random()
            if b == 0:
                c = shade("glow_cyan", 3.0)
            elif b in (1, 7):
                c = shade("glow_amber", 2.6) if tint > 0.5 else shade("ice", 2.8)
            elif b in (2, 6):
                c = shade("ice", 1.6) if tint > 0.5 else shade("crystal", 3.4)
            else:
                c = shade("indigo", 2.6)
            px[sx, sy] = c
        for i in range(max(0, int(pw * ph / 300))):
            sx, sy = 1 + rnd.randrange(max(1, pw - 2)), 1 + rnd.randrange(max(1, ph - 2))
            phase = rnd.randrange(8)
            b = (k + phase) % 8
            px[sx, sy] = shade("glow_amber", 3.0)
            if b in (0, 1, 7):
                arm = shade("glow_amber", 2.4) if b == 0 else shade("crystal", 3.6)
                for (dx, dy) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    px[sx + dx, sy + dy] = arm
        return im

    def face(self, w, h, face, seed):
        pw, ph = max(1, math.ceil(w * D - 1e-6)), max(1, math.ceil(h * D - 1e-6))
        big = pw > HS or ph > HS
        if big:
            pw, ph = min(pw, HS), min(ph, HS)
        frames = [self._sky(pw, ph, face, seed, k) for k in range(8)]
        key = "hd_" + format(zlib.crc32(b"".join(f.tobytes() for f in frames)), "08x")
        if key not in T.SURFACES:
            first = frames[0]
            T.SURFACES[key] = (lambda img=first: img.copy())
            T.ANIMATED[key] = (lambda fs=frames: [f.copy() for f in fs], 3)
        return key, [0, 0, pw, ph] if not big else [0, 0, HS, HS]


SKY = SkyMat(1.0)
SKY_DEEP = SkyMat(0.55, nebula=1.1)
SKY_GLOW = SkyMat(1.2, glow=True, density=1.4)
SKY_BRIGHT = SkyMat(2.1, density=2.8, nebula=1.2)          # the whale: a lighter, starrier sky
BELLY_LIGHT = HMat("crystal", "horn", 3.7)                    # its lavender belly, groove lines across it


# ================================================================================================
# HD cut-out and animated textures
# ================================================================================================

def _star_shape(x, y, cx=15.5, cy=16.5, r_out=15.0, r_in=6.2, turn=0.0):
    """Signed distance-ish of (x, y) to a five-point star (negative inside), points up."""
    a = math.atan2(x - cx, cy - y) - turn
    r = math.hypot(x - cx, y - cy)
    seg = math.tau / 5
    t = (a % seg) / seg
    edge = r_in + (r_out - r_in) * abs(1 - 2 * t) ** 1.6
    return r - edge


def _star_tex(k, hue="gold"):
    """A five-point star, 32 px, cut out: a white-hot heart, gold (or violet) towards the points, a darker gold rim so it
    reads at a distance; the heart pulses (8 frames)."""
    im = hd_canvas()
    px = im.load()
    pulse = 0.25 * math.sin(k * math.tau / 8)
    for y in range(HS):
        for x in range(HS):
            d = _star_shape(x + 0.5, y + 0.5)
            if d > 0:
                continue
            r = math.hypot(x + 0.5 - 15.5, y + 0.5 - 16.5) / 15
            if d > -1.3:
                px[x, y] = shade("gold", 0.6) if hue == "gold" else shade("crystal", 1.2)
                continue
            v = 3.0 - 2.2 * r + pulse
            if hue == "gold":
                px[x, y] = shade("glow_amber", v + 0.4) if v > 2.2 else shade("lamp", v + 0.3)
            else:
                px[x, y] = shade("ice", v + 0.4) if v > 2.4 else shade("crystal", v + 1.4)
    return im


def _twinkle_tex(k):
    """A four-point sparkle, 32 px, cut out, breathing (8 frames): long thin rays, a round soft heart."""
    im = hd_canvas()
    px = im.load()
    s = 0.8 + 0.2 * math.sin(k * math.tau / 8)
    for y in range(HS):
        for x in range(HS):
            dx, dy = abs(x + 0.5 - 16) / s, abs(y + 0.5 - 16) / s
            ray = max(0.0, 1 - dx / 15) * max(0.0, 1 - dy * 2.6 / (1 + dx * 0.15)) + \
                max(0.0, 1 - dy / 15) * max(0.0, 1 - dx * 2.6 / (1 + dy * 0.15))
            core = max(0.0, 1 - math.hypot(dx, dy) / 4.2)
            v = max(ray, core * 1.4)
            if v < 0.18:
                continue
            px[x, y] = shade("glow_amber", 1.8 + 1.3 * min(1.0, v)) if v < 0.9 else shade("glow_cyan", 3.0)
    return im


def _moon_tex():
    """A crescent moon, 32 px, cut out, opening to the right: soft pale gold shaded from its inner edge to a lit rim,
    a gold outline, two faint craters."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            ox, oy = x + 0.5 - 16, y + 0.5 - 16
            d_out = math.hypot(ox, oy) - 14.5
            d_in = math.hypot(ox - 7.5, oy + 2.0) - 12.0
            if d_out > 0 or d_in < 0:
                continue
            edge = min(-d_out, d_in)
            if edge < 1.2:
                px[x, y] = shade("gold", 1.3)
                continue
            v = 2.0 + min(1.0, d_in / 7.0) * 0.9 - 0.3 * (oy / 16)
            px[x, y] = shade("glow_amber", v + 0.3) if v > 2.6 else shade("pearl", v)
            for (cx, cy, r) in ((-8.5, 3.5, 2.0), (-6.0, -6.5, 1.4)):
                if math.hypot(ox - cx, oy - cy) < r:
                    px[x, y] = shade("pearl", 1.4)
    return im


def _burst_tex(k):
    """초신성 rays, 32 px, cut out: sixteen rays of uneven length round a white-hot heart in violet, gold and white;
    the rays shimmer outwards (8 frames)."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            dx, dy = x + 0.5 - 16, y + 0.5 - 16
            r = math.hypot(dx, dy) / 16
            a = math.atan2(dy, dx)
            ray = 0.55 + 0.45 * abs(math.cos(a * 8)) ** 6 * (0.7 + 0.3 * math.sin(a * 3 + 1))
            if r > ray:
                continue
            t = r / ray
            wave = 0.35 * math.sin(r * 14 - k * math.tau / 8)
            if t < 0.28:
                px[x, y] = shade("glow_cyan", 3.0)
            elif t < 0.55:
                px[x, y] = shade("glow_amber", 2.9 - t + wave)
            else:
                px[x, y] = shade("magic", 3.2 - 2.0 * t + wave) if abs(math.cos(a * 4)) > 0.5 else shade("lamp", 3.0 - 1.6 * t + wave)
    return im


def _galaxy_tex(k):
    """A spiral galaxy seen from above, 32 px, cut out: a bright gold core, two violet arms with white star clusters
    winding out to a soft edge; the arms turn (8 frames, one eighth of a half turn each)."""
    im = hd_canvas()
    px = im.load()
    turn = k * math.pi / 8
    for y in range(HS):
        for x in range(HS):
            dx, dy = x + 0.5 - 16, y + 0.5 - 16
            r = math.hypot(dx, dy) / 16
            if r > 1:
                continue
            a = math.atan2(dy, dx) + turn
            arm = math.cos(2 * (a - 3.4 * math.log(0.12 + r)))
            core = max(0.0, 1 - r / 0.28)
            dens = core * 1.6 + max(0.0, arm) ** 2 * (1 - r) * 1.4
            if dens < 0.22 and r > 0.3:
                continue
            if core > 0.2:
                px[x, y] = shade("glow_amber", 2.0 + core * 1.2)
            elif dens > 0.9:
                px[x, y] = shade("ice", 2.2 + dens * 0.5)
            elif dens > 0.45:
                px[x, y] = shade("magic", 1.6 + dens)
            else:
                px[x, y] = shade("crystal", 0.8 + dens * 2)
    rnd = random.Random(7)
    for i in range(14):
        r, a = 0.3 + 0.65 * rnd.random(), rnd.random() * math.tau
        sx, sy = int(16 + 15 * r * math.cos(a)), int(16 + 15 * r * math.sin(a))
        if (k + i) % 4 == 0 and 0 <= sx < HS and 0 <= sy < HS and px[sx, sy][3]:
            px[sx, sy] = shade("glow_cyan", 3.0)
    return im


def _print_tex(variant):
    """별빛 발자국, 32 px seen from above, cut out: a footprint (heel, sole and four toes) of deep violet starlight with
    a glowing gold rim, tiny stars inside; variant moves the stars (the print flickers between two)."""
    im = hd_canvas()
    px = im.load()
    blobs = [(16, 22, 7.0), (16, 15, 5.8), (9.5, 7.0, 2.8), (14, 5.0, 2.9), (19, 5.2, 2.7), (23, 7.8, 2.4)]
    rnd = random.Random(31 + variant)
    for y in range(HS):
        for x in range(HS):
            d = min(math.hypot(x - bx, y - by) - r for (bx, by, r) in blobs)
            if d > 1.1:
                continue
            if d > -0.4:
                px[x, y] = shade("glow_amber", 2.4 + 0.6 * (1.1 - d))
            elif d > -1.6:
                px[x, y] = shade("lamp", 1.6)
            else:
                v = 1.0 + max(-1.0, d * 0.12) + 0.4 * math.cos(x * 0.5 + y * 0.3)
                px[x, y] = shade("crystal", 0.4 + v * 0.8) if (x * 7 + y * 3) % 11 else shade("magic", 1.4)
    for i in range(10):
        sx, sy = rnd.randrange(6, 26), rnd.randrange(4, 28)
        if px[sx, sy][3] and px[sx, sy] != shade("glow_amber", 3.0):
            px[sx, sy] = shade("glow_cyan", 3.0) if i % 3 == 0 else shade("ice", 3.0)
    return im


def _ring_tex(k):
    """A band of the planetary ring, 32 px, cut out: fine gold and violet bands of dust with gaps, glints drifting
    along it (8 frames)."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        band = math.sin(y * 0.9) + 0.6 * math.sin(y * 2.3 + 1)
        if y < 3 or y > 28 or band < -0.9:
            continue
        for x in range(HS):
            glint = math.exp(-(((x - k * 4) % 32 - 8) / 2.5) ** 2)
            v = 1.6 + 0.5 * band + 1.2 * glint
            px[x, y] = shade("lamp", v) if (y // 4) % 2 == 0 else shade("crystal", v + 0.6)
    return im


OWL_ROWS = 21          # the owl's face textures use the top 32 x 21 texels (the face is 5 x 3.3 units)


def _owl_face_tex(eyes_only=False):
    """The owl's face, 32 x 21 px seen from the front, cut out: a heart-shaped facial disc of pale violet feathers
    (two overlapping rounds, a darker rim, fine feather lines radiating from each eye), navy brows sweeping up into a
    V, a small gold beak; eyes_only: just the two big eyes (gold rings, amber glow, dark pupils with a white glint),
    drawn on a separate glowing plate."""
    im = hd_canvas()
    px = im.load()
    eyes = ((9.0, 10.0), (23.0, 10.0))
    for y in range(OWL_ROWS):
        for x in range(HS):
            fx, fy = x + 0.5, y + 0.5
            de = min(math.hypot(fx - ex, fy - ey) for (ex, ey) in eyes)
            if eyes_only:
                if de < 3.3:
                    px[x, y] = shade("obsidian", 0.2)
                    if min(math.hypot(fx - ex + 1.0, fy - ey + 1.1) for (ex, ey) in eyes) < 1.0:
                        px[x, y] = shade("glow_cyan", 3.0)
                elif de < 5.3:
                    px[x, y] = shade("glow_amber", 1.6 + (5.3 - de) * 0.5)
                elif de < 6.2:
                    px[x, y] = shade("gold", 1.0)
                continue
            disc = min(math.hypot((fx - ex) / 1.0, (fy - ey) / 1.12) for (ex, ey) in eyes) - 8.4
            if disc > 0 and not (13 < fx < 19 and fy > 14):
                continue
            if disc > -1.2:
                px[x, y] = shade("crystal", 1.3)
                continue
            # feather lines radiating from the nearer eye
            ex, ey = min(eyes, key=lambda e: math.hypot(fx - e[0], fy - e[1]))
            a = math.atan2(fy - ey, fx - ex)
            line = abs(math.sin(a * 9)) < 0.22
            v = 3.6 - 0.12 * de + (-0.5 if line else 0)
            px[x, y] = shade("crystal", v)
            # brows: a navy V over the eyes
            if fy < 6.5 and abs(fy - (2.0 + abs(fx - 16) * 0.3)) < 1.3:
                px[x, y] = shade("indigo", 1.4)
            # the beak
            if 14.2 < fx < 17.8 and 11.5 < fy < 17.5 and abs(fx - 16) < (17.5 - fy) * 0.38:
                px[x, y] = shade("gold", 2.6 - (fy - 11.5) * 0.15)
    return im


def _fluke_tex():
    """The whale's tail flukes seen from above, 32 px, cut out: two broad lobes swept back from the stock with a notch
    between them, night sky with a few stars, a fine gold rim."""
    im = hd_canvas()
    px = im.load()
    rnd = random.Random(5)
    for y in range(HS):
        for x in range(HS):
            u, v = (x + 0.5 - 16) / 16, (y + 0.5) / HS
            half = abs(u)
            # each lobe: wide in the middle of its half, rounded, swept back (v grows backwards)
            front = 0.18 + 0.55 * half ** 1.6
            back = 0.95 - 0.35 * (half - 0.55) ** 2 - (0.25 if half < 0.12 else 0)
            if half > 0.97 or v < front or v > back:
                continue
            edge = min(v - front, back - v, 0.97 - half)
            if edge < 0.06:
                px[x, y] = shade("gold", 2.2)
            else:
                px[x, y] = shade("indigo", 1.6 - 0.8 * v + 0.4 * (1 - half))
                if rnd.random() < 0.05:
                    px[x, y] = shade("ice", 3.0)
    return im


def _fin_tex():
    """A rounded paddle fin, 32 px, cut out: navy, lighter towards the root, a gold rim."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            dx, dy = (x + 0.5 - 16) / 13, (y + 0.5 - 12) / 18
            r = dx * dx + dy * dy
            if r > 1 or y < 2:
                continue
            px[x, y] = shade("gold", 2.2) if r > 0.78 else shade("indigo", 2.4 - 1.4 * (y / HS))
    return im


def _polestar_tex(k):
    """The pole star, 32 px, cut out: four long rays and four short diagonal ones from a white-hot heart, a soft violet
    halo round it; it breathes (8 frames)."""
    im = hd_canvas()
    px = im.load()
    s_ = 0.85 + 0.15 * math.sin(k * math.tau / 8)
    for y in range(HS):
        for x in range(HS):
            dx, dy = (x + 0.5 - 16) / s_, (y + 0.5 - 16) / s_
            ax, ay = abs(dx), abs(dy)
            long_ray = max(max(0.0, 1 - ax / 15.5) * max(0.0, 1 - ay / (1.4 + ax * 0.1)),
                           max(0.0, 1 - ay / 15.5) * max(0.0, 1 - ax / (1.4 + ay * 0.1)))
            u, v = (dx + dy) / 1.414, (dx - dy) / 1.414
            short_ray = max(max(0.0, 1 - abs(u) / 8) * max(0.0, 1 - abs(v) / (0.5 + abs(u) * 0.08)),
                            max(0.0, 1 - abs(v) / 8) * max(0.0, 1 - abs(u) / (0.5 + abs(v) * 0.08)))
            r = math.hypot(dx, dy)
            core = max(0.0, 1 - r / 5.0)
            halo = 0.35 if 4.5 < r < 6.0 else 0.0
            val = max(long_ray, short_ray * 0.85, core * 1.5)
            if val > 0.12:
                px[x, y] = shade("glow_cyan", 3.0) if val > 0.95 else shade("glow_amber", 1.9 + 1.2 * min(1.0, val))
            elif halo:
                px[x, y] = shade("crystal", 3.2)
    return im


hd_surface("gx_polestar", _polestar_tex, 8, 2)

BAND_ROWS = 10        # the dust ribbon uses the top 32 x 10 texels (a segment is about 4 x 1.25 units)


def _hash(x, y, salt=0):
    return ((x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)) % 1000 / 1000


def _dust_band_tex(k, gold=False):
    """The ring's ribbon of stardust, 32 x 10 px, cut out and see-through in places: a bright thread through the
    middle, violet dust above shading to gold below (all gold for the awakened outer ring), thinning to scattered
    grains at both edges, glints drifting along it and grains twinkling in turn (8 frames)."""
    im = hd_canvas()
    px = im.load()
    for y in range(BAND_ROWS):
        t = (y + 0.5) / BAND_ROWS                       # 0 top .. 1 bottom
        dens = 1 - abs(t - 0.5) * 2                      # thickest in the middle
        for x in range(HS):
            glint = math.exp(-((((x - k * 4) % 32) - 16) / 2.2) ** 2) * (1 if abs(t - 0.5) < 0.2 else 0.4)
            keep = dens * 1.5 + 0.05 + glint
            if _hash(x, y, 7) > keep:
                continue
            tw = (int(_hash(x, y, 3) * 8) + k) % 8 == 0
            if tw or glint > 0.7:
                px[x, y] = shade("glow_cyan", 3.0)
            elif abs(t - 0.5) < 0.12:
                px[x, y] = shade("glow_amber", 2.7 + 0.3 * glint)
            elif gold or t > 0.5:
                px[x, y] = shade("lamp", 2.0 + dens + glint)
            else:
                px[x, y] = shade("magic", 1.6 + dens * 1.4 + glint)
    return im


def _planet_tex(k, kind):
    """A planet's face, 32 px (a ball's six faces each show it): violet with wavy bands and a pale polar cap, gold with
    bands and a turning storm spot (animated), or an icy moon with craters; lit from the top-left, a soft dark limb."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            u, v = (x + 0.5) / HS, (y + 0.5) / HS
            lit = 0.7 - 0.9 * (u + v) / 2
            limb = -0.6 * max(0.0, max(abs(u - 0.5), abs(v - 0.5)) * 2 - 0.75) * 4
            wave = 0.06 * math.sin(u * 9 + k * 0.4)
            if kind == "violet":
                band = int((v + wave) * 7) % 3
                c = shade("magic", 1.5 + band * 0.6 + lit + limb) if band != 1 else shade("crystal", 2.2 + lit + limb)
                if v < 0.13:
                    c = shade("crystal", 3.6 + lit)
            elif kind == "gold":
                band = int((v + wave) * 8) % 4
                c = shade("lamp", 1.4 + band * 0.45 + lit + limb)
                sx, sy = 0.62 + 0.08 * math.cos(k * math.tau / 8), 0.6
                if (u - sx) ** 2 / 0.012 + (v - sy) ** 2 / 0.004 < 1:
                    c = shade("fire", 2.0 + lit)
            else:
                c = shade("ice", 2.4 + lit + limb)
                for (cx, cy, r) in ((0.3, 0.35, 0.09), (0.7, 0.6, 0.12), (0.45, 0.78, 0.07)):
                    d = math.hypot(u - cx, v - cy)
                    if d < r:
                        c = shade("ice", 1.2 + lit) if d > r * 0.6 else shade("ice", 1.8 + lit)
            px[x, y] = c
    return im


def _saturn_ring_tex():
    """The ringed planet's ring seen from above, 32 px, cut out: an annulus of gold and cream bands with a dark gap."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            r = math.hypot(x + 0.5 - 16, y + 0.5 - 16) / 16
            if r < 0.56 or r > 0.98:
                continue
            if 0.74 < r < 0.79:
                continue
            band = int(r * 22) % 3
            px[x, y] = shade("lamp", 2.0 + band * 0.4) if band != 2 else shade("glow_amber", 2.4)
    return im


def _comet_tex(k):
    """A little comet, 32 x 10 px, cut out: a white-hot head on the left, a tail of ice and violet dust fanning out
    and fading to the right, grains drifting along it (8 frames)."""
    im = hd_canvas()
    px = im.load()
    for y in range(BAND_ROWS):
        for x in range(HS):
            d = x / HS
            half = 0.8 + d * 4.2
            off = abs(y + 0.5 - 5.0)
            head = math.hypot(x - 3.5, y + 0.5 - 5.0) < 2.6
            if head:
                px[x, y] = shade("glow_cyan", 3.0)
                continue
            if off > half or x < 3:
                continue
            fade = 1 - d
            if _hash(x - k * 2, y, 11) > fade * 1.2 - off / half * 0.4:
                continue
            px[x, y] = shade("ice", 2.0 + fade) if off < half * 0.45 else shade("magic", 1.6 + fade * 1.4)
    return im


def _whale_eye_tex():
    """The whale's eye and cheek, 32 px, cut out: a big glossy dark eye (navy to violet at the bottom) in a pale rim,
    a large white highlight top-left and a small one bottom-right, and a soft pink blush below it."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            ex, ey = x + 0.5 - 15, y + 0.5 - 12
            r = math.hypot(ex, ey)
            if r < 8.2:
                px[x, y] = shade("obsidian", 0.2 + max(0.0, ey / 8.2) * 1.6)
                if math.hypot(ex + 3.2, ey + 3.2) < 2.7:
                    px[x, y] = shade("glow_cyan", 3.0)
                elif math.hypot(ex - 3.0, ey - 2.8) < 1.3:
                    px[x, y] = shade("ice", 3.0)
            elif r < 9.6:
                px[x, y] = shade("crystal", 3.8)
            bx, by = (x + 0.5 - 16) / 7.5, (y + 0.5 - 26.5) / 3.2
            if bx * bx + by * by < 1 and _hash(x, y, 5) < 0.8:
                px[x, y] = shade("pink", 2.2)
    return im


def _sphere_light(u, v):
    """Light on a ball's face texture: brighter towards the top-left, a soft dark limb at the edges."""
    lit = 0.6 - 0.9 * (u + v) / 2
    edge = max(abs(u - 0.5), abs(v - 0.5)) * 2
    return lit - (0.9 * (edge - 0.72) / 0.28 if edge > 0.72 else 0.0)


def _blob(u, v, seed, n=6, size=0.16):
    """Soft random blobs (continents, patches, clouds): how much (u, v) is inside them (0..)."""
    rnd = random.Random(seed)
    best = 0.0
    for _ in range(n):
        cx, cy, r = rnd.random(), rnd.random(), size * (0.6 + rnd.random())
        d = math.hypot((u - cx) * 1.0, (v - cy) * 1.25) / r
        best = max(best, 1 - d)
    return best


def _solar_tex(k, kind):
    """A planet of our solar system, 32 px (each of a ball's faces shows it), cute and instantly recognisable:
    mercury grey and cratered, venus pale cream veiled in soft clouds, earth blue oceans, green and brown continents and
    white clouds drifting over them (animated), the moon pale grey with craters, mars rusty red with dark patches and a
    white polar cap, jupiter cream, orange and brown bands slowly shifting (animated) with the Great Red Spot, saturn
    soft gold bands, uranus pale cyan, neptune deep blue with a dark spot and a white streak."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            u, v = (x + 0.5) / HS, (y + 0.5) / HS
            L = _sphere_light(u, v)
            if kind in ("mercury", "moon"):
                base = 1.6 if kind == "mercury" else 2.3
                c = shade("stone", base + L)
                for (cx, cy, r) in ((0.3, 0.3, 0.11), (0.68, 0.55, 0.14), (0.42, 0.78, 0.08), (0.8, 0.2, 0.07)):
                    d = math.hypot(u - cx, v - cy)
                    if d < r:
                        c = shade("stone", base - 0.9 + L) if d > r * 0.55 else shade("stone", base - 0.4 + L)
            elif kind == "venus":
                swirl = math.sin(u * 7 + math.sin(v * 5) * 1.5 + k * 0.3) * 0.5 + 0.5
                c = shade("cream", 1.4 + swirl * 1.2 + L) if swirl > 0.35 else shade("straw", 2.2 + L)
            elif kind == "earth":
                land = _blob(u, v, 11, 5, 0.2)
                c = shade("water", 1.4 + L * 1.2)
                if land > 0.15:
                    c = shade("leaf", 1.8 + L) if _blob(u, v, 13, 4, 0.12) < 0.3 else shade("soil", 2.2 + L)
                cu = (u + k / 8) % 1.0
                cloud = _blob(cu, v, 17, 6, 0.11)
                if cloud > 0.35:
                    c = shade("snow", 1.6 + L)
                if v < 0.08 or v > 0.93:
                    c = shade("snow", 2.0 + L)
            elif kind == "mars":
                c = shade("terracotta", 1.9 + L)
                if _blob(u, v, 21, 5, 0.13) > 0.2:
                    c = shade("brick", 1.2 + L)
                if v < 0.16:
                    c = shade("snow", 1.8 + L)
            elif kind == "jupiter":
                vv = v + 0.025 * math.sin(u * 8 + k * 0.6)
                band = int(vv * 11) % 4
                c = (shade("cream", 1.8 + L), shade("straw", 2.2 + L), shade("fox", 2.0 + L * 0.8), shade("straw", 1.6 + L))[band]
                sx = (0.62 + k * 0.03) % 1.0
                if (u - sx) ** 2 / 0.018 + (v - 0.64) ** 2 / 0.006 < 1:
                    c = shade("brick", 2.2 + L)
            elif kind == "saturn":
                band = int((v + 0.02 * math.sin(u * 6)) * 8) % 3
                c = (shade("straw", 2.6 + L), shade("lamp", 2.0 + L), shade("cream", 1.9 + L))[band]
            elif kind == "uranus":
                c = shade("spectral", 2.6 + L * 0.8 + 0.15 * math.sin(v * 9))
            else:  # neptune
                c = shade("sapphire", 1.7 + L + 0.15 * math.sin(v * 10))
                if (u - 0.4) ** 2 / 0.012 + (v - 0.58) ** 2 / 0.005 < 1:
                    c = shade("sapphire", 0.3 + L)
                if abs(v - 0.42) < 0.03 and 0.5 < u < 0.8:
                    c = shade("snow", 2.0)
            px[x, y] = c
    return im


def _saturn_ring_band_tex(k):
    """Saturn's ring seen from above, 32 px, cut out: bands of gold, cream and tan with the dark Cassini gap, a glint
    shimmering round it (8 frames)."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            dx, dy = x + 0.5 - 16, y + 0.5 - 16
            r = math.hypot(dx, dy) / 16
            if r < 0.52 or r > 0.99 or 0.76 < r < 0.8:
                continue
            a = math.atan2(dy, dx)
            glint = 0.8 * math.exp(-((((a - k * math.tau / 8) + math.pi) % math.tau - math.pi) / 0.35) ** 2)
            band = int(r * 24) % 3
            px[x, y] = (shade("straw", 2.6 + glint), shade("lamp", 2.1 + glint), shade("doenjang", 2.6 + glint))[band]
    return im


def _uranus_ring_tex():
    """Uranus' faint ring, 32 px, cut out: a thin pale cyan circle."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            r = math.hypot(x + 0.5 - 16, y + 0.5 - 16) / 16
            if 0.86 < r < 0.97:
                px[x, y] = shade("spectral", 3.0 if (x + y) % 3 else 2.2)
    return im


def _asteroids_tex(k):
    """The asteroid belt, 32 x 10 px, cut out: tiny grey-brown rocks of a few texels scattered along a band, sparkles
    twinkling between them (8 frames)."""
    im = hd_canvas()
    px = im.load()
    rnd = random.Random(41)
    for i in range(16):
        cx, cy = rnd.randrange(1, 31), rnd.randrange(2, BAND_ROWS - 2)
        size = rnd.choice((1, 1, 2))
        for dx in range(-size, size + 1):
            for dy in range(-size, size + 1):
                if abs(dx) + abs(dy) <= size and 0 <= cx + dx < HS and 0 <= cy + dy < BAND_ROWS:
                    px[cx + dx, cy + dy] = shade("stone" if i % 3 else "doenjang", 1.6 + 0.4 * (dx + dy < 0))
    for i in range(10):
        sx, sy = rnd.randrange(HS), rnd.randrange(BAND_ROWS)
        if (i + k) % 4 == 0:
            px[sx, sy] = shade("glow_cyan", 3.0)
        elif (i + k) % 4 == 1:
            px[sx, sy] = shade("magic", 3.0)
    return im


for _kind in ("mercury", "venus", "mars", "saturn", "uranus", "neptune"):
    hd_surface(f"gx_{_kind}", (lambda k, kd=_kind: _solar_tex(k, kd)))
hd_surface("gx_luna", lambda k: _solar_tex(k, "moon"))     # (gx_moon is the crescent)
hd_surface("gx_earth", lambda k: _solar_tex(k, "earth"), 8, 4)
hd_surface("gx_jupiter", lambda k: _solar_tex(k, "jupiter"), 8, 6)
hd_surface("gx_saturn_band", _saturn_ring_band_tex, 8, 3)
hd_surface("gx_uranus_ring", lambda k: _uranus_ring_tex())
hd_surface("gx_asteroids", _asteroids_tex, 8, 3)
hd_surface("gx_dust_band", _dust_band_tex, 8, 2)
hd_surface("gx_dust_band_gold", lambda k: _dust_band_tex(k, gold=True), 8, 2)
hd_surface("gx_planet_violet", lambda k: _planet_tex(k, "violet"), 8, 4)
hd_surface("gx_planet_gold", lambda k: _planet_tex(k, "gold"), 8, 4)
hd_surface("gx_planet_ice", lambda k: _planet_tex(0, "ice"))
hd_surface("gx_saturn_ring", lambda k: _saturn_ring_tex())
hd_surface("gx_comet", _comet_tex, 8, 2)
hd_surface("gx_whale_eye", lambda k: _whale_eye_tex())
hd_surface("gx_owl_face", lambda k: _owl_face_tex())
hd_surface("gx_owl_eyes", lambda k: _owl_face_tex(True))
hd_surface("gx_fluke", lambda k: _fluke_tex())
hd_surface("gx_fin", lambda k: _fin_tex())
hd_surface("gx_star", _star_tex, 8, 3)
hd_surface("gx_star_violet", lambda k: _star_tex(k, "violet"), 8, 3)
hd_surface("gx_twinkle", _twinkle_tex, 8, 2)
hd_surface("gx_moon", lambda k: _moon_tex())
hd_surface("gx_burst", _burst_tex, 8, 2)
hd_surface("gx_galaxy", _galaxy_tex, 8, 3)
hd_surface("gx_print_a", lambda k: _print_tex(0))
hd_surface("gx_print_b", lambda k: _print_tex(1))
hd_surface("gx_ring", _ring_tex, 8, 2)


def cut(m, name, frm, to, tex, plane, glow=True, rotation=None, rect=(0, 0, HS, HS)):
    """A thin cut-out plate showing rect of a 32 px texture on its two big faces (plane 'x': faces north/south, 'z':
    east/west, 'y': up/down), its edges a clear texel; rotation: the box's (one-axis) turn."""
    u0, v0, u1, v1 = rect
    front, back = [u0, v0, u1, v1], [u1, v0, u0, v1]
    big = {"x": ("north", "south"), "z": ("east", "west"), "y": ("up", "down")}[plane]
    uv = {f: CLEAR for f in ALL_FACES}
    uv[big[0]], uv[big[1]] = (front, back) if plane != "y" else (front, [u0, v1, u1, v0])
    return m.box(name, frm, to, tex, glow=glow, uv=uv, shade=not glow, rotation=rotation)


def plate(m, name, c, w, h, tex, th=0.0):
    """A standing cut-out plate w wide and h tall, its bottom centre on c, facing north/south turned th degrees
    (-45..45) about the vertical."""
    x, y, z = c
    rot = ("y", th, (x, y + h / 2, z)) if th else None
    return m.box(name, (x - w / 2, y, z - 0.05), (x + w / 2, y + h, z + 0.05), tex, glow=True, shade=False, rotation=rot,
                 uv={"north": [0, 0, HS, HS], "south": [HS, 0, 0, HS], "east": CLEAR, "west": CLEAR, "up": CLEAR, "down": CLEAR})


def star(m, name, c, d, tex="gx_star", plane="x"):
    """A five-point star cut-out of size d centred on c, facing north/south (plane x) or east/west (plane z)."""
    x, y, z = c
    if plane == "x":
        cutout(m, name, (x - d / 2, y - d / 2, z - 0.05), (x + d / 2, y + d / 2, z + 0.05), tex, "x", glow=True)
    else:
        cutout(m, name, (x - 0.05, y - d / 2, z - d / 2), (x + 0.05, y + d / 2, z + d / 2), tex, "z", glow=True)


def twinkle(m, name, c, d, cross=True):
    """A four-point sparkle: two crossed cut-out planes (or one facing north)."""
    x, y, z = c
    cutout(m, name, (x - d / 2, y - d / 2, z - 0.01), (x + d / 2, y + d / 2, z + 0.01), "gx_twinkle", "x", glow=True)
    if cross:
        cutout(m, name, (x - 0.01, y - d / 2, z - d / 2), (x + 0.01, y + d / 2, z + d / 2), "gx_twinkle", "z", glow=True)


def orb(m, name, c, d, mat, glow=False):
    """A round-looking ball of diameter d: a cube and three crossing slabs (no two faces in one plane)."""
    x, y, z = c
    r, s, t = d * 0.4, d * 0.5, d * 0.25
    m.box(name, (x - r, y - r, z - r), (x + r, y + r, z + r), mat, glow=glow)
    for (ax, ay, az) in ((s, t, t), (t, s, t), (t, t, s)):
        m.box(name, (x - ax, y - ay, z - az), (x + ax, y + ay, z + az), mat, glow=glow)


def orb_tex(m, name, c, d, tex, glow=True):
    """A round-looking ball of diameter d (a cube and three crossing slabs) showing a whole 32 px texture on every face."""
    x, y, z = c
    r, s_, t = d * 0.4, d * 0.5, d * 0.25
    m.box(name, (x - r, y - r, z - r), (x + r, y + r, z + r), tex, glow=glow, full_uv=ALL_FACES)
    for (ax, ay, az) in ((s_, t, t), (t, s_, t), (t, t, s_)):
        m.box(name, (x - ax, y - ay, z - az), (x + ax, y + ay, z + az), tex, glow=glow, full_uv=ALL_FACES)


def ball(m, name, c, d, tex, parts=3, glow=False):
    """A planet of diameter d showing a whole 32 px texture on each face, in 1 to 3 boxes: a cube (1), with an
    equator slab round it (2), and a slab through the poles (3): rounder the more parts."""
    x, y, z = c
    r, s_, t = (d * 0.37, d * 0.5, d * 0.31) if parts >= 3 else (d * 0.42, d * 0.5, d * 0.27)
    m.box(name, (x - r, y - r, z - r), (x + r, y + r, z + r), tex, glow=glow, full_uv=ALL_FACES)
    if parts >= 2:
        m.box(name, (x - s_, y - t, z - s_), (x + s_, y + t, z + s_), tex, glow=glow, full_uv=ALL_FACES)
    if parts >= 3:
        m.box(name, (x - t, y - s_, z - t), (x + t, y + s_, z + t), tex, glow=glow, full_uv=ALL_FACES)


def ribbon(m, name, p, th, length, height, tex, rect):
    """A standing cut-out plate centred on p, length along the circle and height tall, facing out from the centre at th
    degrees (a ring's segment seen from the side as a ribbon)."""
    k = round(th / 90)
    rem = th - 90 * k
    x, y, z = p
    if k % 2 == 0:
        frm, to, plane = (x - length / 2, y - height / 2, z - 0.05), (x + length / 2, y + height / 2, z + 0.05), "x"
    else:
        frm, to, plane = (x - 0.05, y - height / 2, z - length / 2), (x + 0.05, y + height / 2, z + length / 2), "z"
    return cut(m, name, frm, to, tex, plane, rotation=("y", rem, p) if abs(rem) > 1e-6 else None, rect=rect)


def gem(m, name, c, d, mat):
    """A cut gem: one cube of side d turned 45 degrees (a diamond seen from the side), glowing."""
    x, y, z = c
    h = d / 2
    return m.box(name, (x - h, y - h, z - h), (x + h, y + h, z + h), mat, glow=True, rotation=("y", 45, (x, y, z)))


def ring_bars(m, name, c, radius, y, n, w, t, h, mat, phase=0.0, tilt=0.0, faces=None):
    """A ring of n turned bars round c at radius, y the height; tilt (units): how far the ring rises on one side and
    falls on the other (a tilted ring drawn in steps), phase: where it is highest (degrees)."""
    step = 360 / n
    for i in range(n):
        th = i * step
        r = math.radians(th + phase)
        yy = y + tilt * math.cos(r)
        ybox(m, name, at((c[0], yy, c[2]), th, radius), t, h, w, th, mat, faces=faces)


# ================================================================================================
# 별관 (above, spin)
# ================================================================================================

def _crown(m, awake):
    """36 parts (awakened 45): the band of sky between two gold rims (the awakened band glows and loses the lower rim to
    its halo), four big gold stars standing on the rim, small gold points and violet gems on the corners, sparkles."""
    Y = -1.6           # sits just over the head
    for i in range(8):
        th = i * 45
        ybox(m, "band", at((8, 11.3 + Y, 8), th, 5.0), 0.8, 1.9, 4.3, th, SKY_GLOW if awake else SKY)
        ybox(m, "rim_top", at((8, 12.35 + Y, 8), th, 5.15), 1.0, 0.4, 4.5, th, GOLD)
        if not awake:
            ybox(m, "rim_low", at((8, 10.25 + Y, 8), th, 5.15), 1.0, 0.4, 4.5, th, GOLD)
    for i in range(8):
        th = i * 45
        base = at((8, 12.55 + Y, 8), th, 5.05)
        if i % 2 == 0:
            # a big gold star standing on the rim, facing out, on a small gold mount
            ybox(m, "mount", (base[0], base[1] + 0.35, base[2]), 0.7, 0.7, 1.2, th, GOLD_LIGHT)
            star(m, "star", (base[0], base[1] + (2.3 if awake else 2.0), base[2]), 3.9 if awake else 3.4,
                 plane="x" if i % 4 == 0 else "z")
        else:
            # a small gold point with a violet gem on it
            ybox(m, "point", (base[0], base[1] + 0.5, base[2]), 0.6, 1.0, 0.8, th, GOLD)
            gem(m, "corner_gem", (base[0], base[1] + 1.35, base[2]), 0.9 if not awake else 1.15, VIOLET_GEM)
            twinkle(m, "twinkle", (base[0], base[1] + 2.7 + 0.5 * (i % 4 == 1), base[2]), 1.4, cross=False)
    if awake:
        # a crescent moon floating over the front and a halo of eight violet stars above
        cut(m, "moon", (6.4, 17.0 + Y, 2.55), (9.6, 20.2 + Y, 2.65), "gx_moon", "x")
        for i in range(8):
            th = i * 45 + 22.5
            p = at((8, 0, 8), th, 6.4)
            star(m, "halo_star", (p[0], 18.0 + Y + 0.5 * math.sin(i * 1.7), p[2]), 1.4, tex="gx_star_violet",
                 plane="x" if i % 2 == 0 else "z")


def galaxy_star_crown():
    """은하수 별관: an octagonal band of twinkling night sky between gold rims, gold spires with violet gems on the four
    sides and pearl gems on the corners, four gold stars standing round it and sparkles drifting over it. Turns (spin)."""
    m = MModel("galaxy_star_crown")
    _crown(m, False)
    _display(m, (30, 225, 0), 0.95, -6)
    return m


def galaxy_star_crown_awakened():
    """각성 별관: the band glows, bigger stars, a crescent moon crowning the front and a halo of violet stars on a ring of
    gold dust floating above."""
    m = MModel("galaxy_star_crown_awakened")
    _crown(m, True)
    _display(m, (30, 225, 0), 0.8, -8)
    return m


# ================================================================================================
# 망토 (back, sway)
# ================================================================================================

CAPE_Z = 8.1          # the cape's inside face, just behind the back
LOWER = ("x", -22.5, (8, 2.0, 8.3))           # the lower cape swept back about the hips


def _cape(m, awake):
    sky = SKY_GLOW if awake else SKY
    # upper and lower panels: sky outside (south), violet lining inside (north); the sides angled forward a step so
    # the cape wraps the body instead of a flat board
    for (y0, y1, rot) in ((2.0, 11.7, None), (-7.4 if not awake else -10.2, 2.3, LOWER)):
        m.box("cape", (4.0, y0, CAPE_Z), (12.0, y1, CAPE_Z + 0.45), sky, rotation=rot,
              faces={"north": VIOLET, "east": GOLD, "west": GOLD, "up": GOLD, "down": GOLD})
        for side in (-1, 1):
            # a side drape: a narrow panel turned 22.5 degrees towards the front at each edge
            x = 8 + side * 4.85
            m.box("drape", (x - 0.95, y0, CAPE_Z - 0.75), (x + 0.95, y1, CAPE_Z - 0.3), sky,
                  rotation=("y", -side * 22.5, (x, (y0 + y1) / 2, CAPE_Z - 0.5)) if rot is None else rot,
                  faces={"north": VIOLET, "up": GOLD, "down": GOLD})
            # gold edging down both outer edges
            ex = 8 + side * 5.75
            m.box("edge", (ex - 0.3, y0, CAPE_Z - 1.0), (ex + 0.3, y1, CAPE_Z - 0.5), GOLD_LIGHT, rotation=rot)
    # the hem: scalloped gold points with a star on each (cut-out stars along the lower edge)
    hem_y = -7.6 if not awake else -10.4
    m.box("hem", (3.3, hem_y, CAPE_Z - 0.6), (12.7, hem_y + 0.7, CAPE_Z + 0.55), GOLD, rotation=LOWER)
    for x in (4.6, 6.9, 9.1, 11.4):
        cut(m, "hem_star", (x - 1.0, hem_y - 1.6, CAPE_Z + 0.6), (x + 1.0, hem_y + 0.4, CAPE_Z + 0.7), "gx_star", "x",
            rotation=LOWER)
    # a constellation on the back: five stars joined by fine gold lines (the 국자: the dipper)
    pts = [(5.4, 9.6), (7.2, 8.6), (8.6, 6.6), (10.4, 7.2), (10.9, 4.6), (9.0, 3.9)]
    for p0, p1 in zip(pts, pts[1:]):
        seg_xy(m, "line", p0, p1, 0.18, 0.08, CAPE_Z + 0.52, GOLD_GLOW, ext=0.0)
    for i, p in enumerate(pts):
        twinkle(m, "con_star", (p[0], p[1], CAPE_Z + 0.6), 1.5 if i in (2, 4) else 1.1, cross=False)
    # the pole star the dipper points at: a big four-point star glowing high on the back
    cut(m, "pole_star", (3.9, 3.5, CAPE_Z + 0.58), (8.1, 7.7, CAPE_Z + 0.68), "gx_polestar", "x")
    if awake:
        # a spiral galaxy glowing low on the cape, sparkles trailing off the hem
        cut(m, "galaxy", (4.6, -5.2, CAPE_Z + 0.5), (11.4, 1.6, CAPE_Z + 0.6), "gx_galaxy", "x", rotation=LOWER)
        for (x, y, d) in ((4.2, -12.0, 1.5), (8.0, -12.8, 1.9), (11.6, -11.8, 1.4), (6.2, -13.8, 1.1), (10.0, -14.0, 1.0)):
            twinkle(m, "falling", (x, y, CAPE_Z + 4.4 + (y + 12) * -0.35), d, cross=False)
    # the mantle over the shoulders: navy with a gold edge, falling a little over the front
    m.box("mantle", (3.6, 11.6, 4.0), (12.4, 12.3, 8.7), NAVY, faces={"up": sky, "down": NAVY_DARK})
    m.box("mantle_back", (3.5, 10.4, 8.0), (12.5, 12.22, 8.75), GOLD)
    for side in (-1, 1):
        x0 = 3.45 if side < 0 else 11.95
        m.box("mantle_side", (x0, 9.6, 4.1), (x0 + 0.6, 12.2, 8.6), NAVY, faces={"west" if side < 0 else "east": sky})
    # the clasps on the chest: gold crescent clasps with a star gem, joined by a fine chain
    m.box("chain", (4.8, 10.4, 3.75), (11.2, 10.65, 3.95), GOLD_LIGHT)
    for x in (4.1, 11.9):
        m.box("clasp", (x - 0.9, 9.6, 3.5), (x + 0.9, 11.4, 4.05), GOLD)
        orb(m, "clasp_gem", (x, 10.5, 3.35), 0.9, STAR_GEM, glow=True)


def galaxy_cape():
    """은하수 망토: a long cape of twinkling night sky painted with nebulae, its lower half swept back, side drapes
    wrapping the body, a violet lining, gold edges and a hem of gold stars, the dipper stitched on the back in gold
    stars and lines, a navy mantle over the shoulders and two gold clasps with star gems on the chest. Swings (sway)."""
    m = MModel("galaxy_cape")
    _cape(m, False)
    _display(m, BACK_GUI, 0.4, 1)
    return m


def galaxy_cape_awakened():
    """각성 망토: the sky glows, the cape is longer, a spiral galaxy turns low on its back and stars fall off the hem."""
    m = MModel("galaxy_cape_awakened")
    _cape(m, True)
    _display(m, BACK_GUI, 0.36, 1)
    return m


# ================================================================================================
# 초승달 목걸이 (neck)
# ================================================================================================

def galaxy_moon_necklace():
    """초승달 목걸이: a gold chain of fine links hanging in a V on the chest, violet gem beads along it, and a glowing
    crescent moon rimmed in gold hanging from a gold bail, a little star sparkling in its curve. On the neck anchor."""
    m = MModel("galaxy_moon_necklace")
    zf = 6.1                         # the front of the chest (model z); the body is x 4.25..11.75
    for side in (1, -1):
        prev = (8 + side * 3.6, 9.2)
        for i in range(1, 6):
            q = (8 + side * (3.6 - i * 0.62), 9.2 - i * 0.72)
            seg_xy(m, "chain", prev, q, 0.32, 0.32, zf - 0.2, GOLD_LIGHT if i % 2 else GOLD, ext=0.12)
            prev = q
        orb(m, "bead", (8 + side * 2.4, 7.6, zf - 0.35), 0.8, VIOLET_GEM, glow=True)
        orb(m, "bead_small", (8 + side * 1.2, 6.75, zf - 0.3), 0.55, ICE_GEM, glow=True)
    for x0 in (3.95, 11.75):         # round the sides and across the upper back
        m.box("chain_side", (x0, 8.85, 6.0), (x0 + 0.3, 9.2, 10.0), GOLD)
    m.box("chain_back", (4.0, 8.9, 9.85), (12.0, 9.15, 10.15), GOLD)
    m.box("bail", (7.6, 5.0, zf - 0.6), (8.4, 6.1, zf - 0.1), GOLD_LIGHT)
    orb(m, "bail_gem", (8.0, 5.4, zf - 0.75), 0.6, STAR_GEM, glow=True)
    # the crescent: a cut-out moon (both faces), a gold rim plate behind it a hair larger for depth
    cutout(m, "moon", (5.8, 0.8, zf - 0.7), (10.2, 5.2, zf - 0.6), "gx_moon", "x", glow=True)
    twinkle(m, "moon_star", (8.9, 3.2, zf - 0.85), 1.5, cross=False)
    _display(m, HEAD_GUI, 1.35, 4)
    return m


# ================================================================================================
# 별 부엉이 (shoulder)
# ================================================================================================

def galaxy_star_owl():
    """별 부엉이: a little round owl woven of night sky perched on the shoulder: an egg-round body with a pale violet
    breast and a crescent on it, a wide head with a painted heart-shaped face (feather lines, a navy V of brows, a gold
    beak) and big glowing gold eyes, two short feathered ear tufts, folded wings edged in gold, a fan of tail with a
    star and gold talons gripping the shoulder. Built at full size round (8, Y0, 8) and drawn at K of it (it fits on
    the shoulder, clear of the head)."""
    m = MModel("galaxy_star_owl")
    K, Y0 = 0.74, 7.6

    def P(x, y, z):
        return (8 + (x - 8) * K, Y0 + (y - 7.2) * K, 8 + (z - 8) * K)

    def B(name, a, b, mat, **kw):
        return m.box(name, P(*a), P(*b), mat, **kw)

    y0 = 7.2
    # the body: an egg of crossing boxes (round from every side), the breast a paler plate in front
    B("body", (5.7, y0, 6.4), (10.3, y0 + 3.8, 10.0), SKY, faces={"down": NAVY_DARK})
    B("body_round", (5.3, y0 + 0.6, 6.8), (10.7, y0 + 3.2, 9.6), SKY)
    B("breast", (6.3, y0 + 0.4, 6.15), (9.7, y0 + 3.4, 6.45), BELLY)
    cut(m, "breast_moon", P(7.2, y0 + 0.8, 6.0), P(8.8, y0 + 2.4, 6.1), "gx_moon", "x")
    # the head: wide and round, the face a painted plate in front, the eyes a glowing plate over it
    B("head", (5.3, y0 + 3.6, 6.2), (10.7, y0 + 6.9, 9.8), SKY, faces={"down": NAVY_DARK})
    B("head_round", (4.9, y0 + 4.1, 6.6), (11.1, y0 + 6.4, 9.4), SKY)
    B("head_top", (5.8, y0 + 6.85, 6.7), (10.2, y0 + 7.3, 9.3), SKY)
    rect = (0, 0, HS, OWL_ROWS)
    cut(m, "face", P(5.5, y0 + 3.7, 6.05), P(10.5, y0 + 7.0, 6.15), "gx_owl_face", "x", glow=False, rect=rect)
    cut(m, "eyes", P(5.5, y0 + 3.7, 5.9), P(10.5, y0 + 7.0, 6.0), "gx_owl_eyes", "x", rect=rect)
    for side in (-1, 1):
        # ear tufts: short feathered points leaning out, navy with a violet tip
        tx = 8 + side * 2.3
        B("tuft", (tx - 0.55, y0 + 6.8, 7.3), (tx + 0.55, y0 + 7.9, 8.5), NAVY,
          rotation=("z", -side * 22.5, P(tx, y0 + 7.0, 7.9)))
        B("tuft_tip", (tx - 0.35 + side * 0.4, y0 + 7.8, 7.5), (tx + 0.35 + side * 0.4, y0 + 8.5, 8.3), VIOLET_LIGHT,
          rotation=("z", -side * 45, P(tx + side * 0.4, y0 + 7.9, 7.9)))
        # folded wings down the sides, turned in at the back, edged with gold
        wx = 8 + side * 2.75
        rot = ("y", -side * 22.5, P(wx, y0 + 1.9, 8.2))
        B("wing", (wx - 0.3, y0 + 0.3, 6.6), (wx + 0.3, y0 + 3.6, 10.2), SKY, rotation=rot)
        B("wing_edge", (wx - 0.35, y0 + 3.3, 6.7), (wx + 0.35, y0 + 3.7, 10.1), GOLD, rotation=rot)
        # talons gripping the shoulder
        B("talon", (8 + side * 1.0 - 0.45, y0 - 0.35, 6.3), (8 + side * 1.0 + 0.45, y0 + 0.25, 7.5), GOLD_LIGHT)
    twinkle(m, "sparkle", P(11.6, y0 + 8.4, 8.0), 1.0, cross=False)
    # the tail fanned out behind, a gold star at its end
    a, b = P(8.0, y0 + 0.8, 9.8), P(8.0, y0 - 0.6, 11.4)
    seg_yz(m, "tail", (a[1], a[2]), (b[1], b[2]), 2.6 * K, 0.45, 8.0, SKY, ext=0.2)
    star(m, "tail_star", P(8.0, y0 - 1.0, 11.6), 1.2)
    _display(m, HEAD_GUI, 1.3, -2)
    return m


# ================================================================================================
# 별자리 허리띠 (waist)
# ================================================================================================

def galaxy_star_belt():
    """별자리 허리띠: a sash of night sky round the waist between gold trims, a round gold buckle holding a glowing
    star, a gold chain swagged across the front, and charms on the left hip (a crescent, two stars) hanging on fine
    chains; a constellation of tiny stars along the band."""
    m = MModel("galaxy_star_belt")
    m.box("band", (3.55, 5.5, 5.55), (12.45, 7.45, 10.45), SKY, faces={"down": NAVY_DARK})
    m.box("trim_low", (3.45, 5.25, 5.45), (12.55, 5.55, 10.55), GOLD)
    m.box("trim_top", (3.45, 7.4, 5.45), (12.55, 7.7, 10.55), GOLD_LIGHT)
    # the buckle: an octagonal gold frame (four turned bars round the centre) with the star in it
    # the buckle: two gold squares crossed at 45 degrees (an eight-pointed frame), a dark sky disc and the star on it
    m.box("buckle", (6.85, 5.35, 5.0), (9.15, 7.65, 5.45), GOLD)
    m.box("buckle", (6.85, 5.35, 4.95), (9.15, 7.65, 5.4), GOLD_LIGHT, rotation=("z", 45, (8.0, 6.5, 5.2)))
    m.box("buckle_sky", (7.2, 5.7, 4.85), (8.8, 7.3, 5.0), SKY_DEEP)
    star(m, "buckle_star", (8.0, 6.5, 4.7), 2.2)
    # a gold chain swagged across the front, under the buckle
    swag = [(4.6, 6.6), (5.6, 5.4), (6.8, 4.8), (9.2, 4.8), (10.4, 5.4), (11.4, 6.6)]
    for p0, p1 in zip(swag, swag[1:]):
        seg_xy(m, "swag", p0, p1, 0.3, 0.3, 5.25, GOLD_LIGHT, ext=0.1)
    # charms on the left hip: three fine chains of different lengths, a crescent and two stars at their ends
    for (x, ln, kind) in ((3.9, 2.4, "moon"), (4.9, 3.4, "star"), (5.9, 1.8, "violet")):
        m.box("charm_chain", (x - 0.1, 5.4 - ln, 5.2), (x + 0.1, 5.4, 5.4), GOLD)
        c = (x, 5.4 - ln - 0.6, 5.0 if kind == "moon" else 4.8)
        if kind == "moon":
            cutout(m, "charm_moon", (x - 0.75, c[1] - 0.75, 4.95), (x + 0.75, c[1] + 0.75, 5.05), "gx_moon", "x", glow=True)
        else:
            star(m, "charm_star", c, 1.3, tex="gx_star" if kind == "star" else "gx_star_violet")
    # tiny stars along the band at the back and sides
    for (x, y) in ((5.2, 6.6), (8.3, 6.1), (10.8, 6.8)):
        twinkle(m, "band_star", (x, y, 10.6), 0.9, cross=False)
    for (z, y) in ((7.0, 6.4), (9.3, 6.7)):
        star(m, "band_star", (12.6, y, z), 0.9, plane="z")
    _display(m, HEAD_GUI, 0.9, 0)
    return m


# ================================================================================================
# 아기 별고래 (pet, floats)
# ================================================================================================

def galaxy_star_whale():
    """아기 별고래 (a pet that floats): a round baby whale of starry sky swimming through the air. A big bulbous head
    (four crossing boxes and a domed forehead) over a clearly smaller body that tapers into a tail lifting to tilted,
    gold-rimmed cut-out flukes; a lavender belly with groove lines under the chin and the body; big glossy eyes with
    highlights and a pink blush on each side; gold-rimmed paddle fins; stars along its back and a sparkling spout."""
    m = MModel("galaxy_star_whale")
    y = 7.0
    sky = SKY_BRIGHT
    # the head: tall, core and wide boxes crossing (an eight-sided section) behind a lower front, the corner between
    # them rounded off by a brow turned 45 degrees, a domed forehead and a small round snout: a curved profile
    m.box("head_tall", (6.0, y - 3.2, 3.8), (10.0, y + 3.4, 8.2), sky, faces={"down": BELLY_LIGHT})
    m.box("head", (4.9, y - 2.6, 3.6), (11.1, y + 2.7, 8.6), sky, faces={"down": BELLY_LIGHT})
    m.box("head_front", (5.25, y - 2.3, 2.2), (10.75, y + 1.6, 3.8), sky, faces={"down": BELLY_LIGHT})
    m.box("brow", (5.0, y + 1.05, 2.05), (11.0, y + 2.95, 3.95), sky, rotation=("x", 45, (8.0, y + 2.0, 3.0)))
    m.box("head_wide", (4.2, y - 1.6, 3.0), (11.8, y + 1.8, 8.0), sky, faces={"down": BELLY_LIGHT})
    m.box("forehead", (5.8, y + 2.6, 4.2), (10.2, y + 3.8, 7.6), sky)
    m.box("snout", (5.6, y - 1.9, 1.5), (10.4, y + 1.0, 2.4), sky, faces={"down": BELLY_LIGHT})
    # the lavender belly under the chin and under the body, its grooves painted across it
    m.box("belly", (5.1, y - 3.1, 2.6), (10.9, y - 1.4, 8.7), BELLY_LIGHT)
    m.box("belly_body", (6.1, y - 2.05, 8.4), (9.9, y - 1.0, 11.9), BELLY_LIGHT)
    # the body, clearly smaller than the head, tapering into the tail stock
    m.box("body", (5.9, y - 1.7, 8.4), (10.1, y + 1.7, 11.8), sky, faces={"down": BELLY_LIGHT})
    m.box("body_wide", (5.3, y - 1.0, 8.8), (10.7, y + 1.1, 11.4), sky, faces={"down": BELLY_LIGHT})
    m.box("taper", (6.6, y - 1.0, 11.6), (9.4, y + 1.3, 14.0), sky, faces={"down": BELLY_LIGHT})
    seg_yz(m, "tail", (y + 0.2, 13.6), (y + 1.3, 16.4), 2.0, 1.6, 8.0, sky, ext=0.25)
    seg_yz(m, "tail_end", (y + 1.3, 16.4), (y + 2.3, 18.2), 1.3, 1.0, 8.0, sky, ext=0.15)
    # the flukes: a wide cut-out, tilted up a little so it shows from the side too
    cut(m, "flukes", (3.0, y + 2.25, 17.0), (13.0, y + 2.35, 22.2), "gx_fluke", "y", rotation=("x", -22.5, (8.0, y + 2.3, 17.8)))
    for side in (-1, 1):
        # the eye and blush: a painted plate just outside the head's widest side
        ex = 8 + side * 3.86
        cut(m, "eye", (ex - 0.05, y - 2.3, 2.9), (ex + 0.05, y + 1.9, 7.1), "gx_whale_eye", "z")
        # paddle fins: gold-rimmed cut-outs swept down and out
        fx = 8 + side * 5.1
        cut(m, "fin", (fx - 0.05, y - 4.4, 6.6), (fx + 0.05, y - 1.0, 10.0), "gx_fin", "z",
            rotation=("z", -side * 45, (fx, y - 1.4, 8.3)))
    # stars along the back and the sparkling spout
    for (x, z, d) in ((7.0, 5.0, 1.0), (9.2, 7.0, 0.9), (7.9, 9.8, 0.9)):
        twinkle(m, "back_star", (x, y + 4.25 if z < 8 else y + 2.25, z), d, cross=False)
    twinkle(m, "spout", (8.0, y + 5.5, 4.8), 2.0)
    twinkle(m, "spout", (6.8, y + 6.9, 4.2), 1.1, cross=False)
    twinkle(m, "spout", (9.2, y + 7.3, 5.0), 0.9, cross=False)
    _display(m, (30, 225, 0), 0.75, 0)
    return m


# ================================================================================================
# 별빛 발자국 (trail prints) and the trail's card
# ================================================================================================

def _print(name, tex, awake=False):
    m = MModel(name)
    cutout(m, "print", (2.5, 8.0, 1.0), (13.5, 8.1, 15.0), tex, "y", glow=True)
    # a little height: a sparkle standing over the heel and one over the toes
    twinkle(m, "sparkle", (11.4, 9.4, 12.6), 1.6)
    twinkle(m, "sparkle", (4.4, 9.0, 3.8), 1.1)
    if awake:
        # a shooting star rising out of the print: a gold star over a trail of violet dust
        star(m, "rising", (8.0, 14.4, 8.0), 2.6)
        cutout(m, "trail", (7.2, 8.4, 7.95), (8.8, 13.2, 8.05), "gx_burst", "x", glow=True)
        cutout(m, "trail", (7.95, 8.4, 7.2), (8.05, 13.2, 8.8), "gx_burst", "z", glow=True)
    m.display = {"head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    return m


def galaxy_star_print_a():
    """별빛 발자국 (frame a): a footprint of violet starlight with a gold rim left on the ground."""
    return _print("galaxy_star_print_a", "gx_print_a")


def galaxy_star_print_b():
    """별빛 발자국 (frame b): the same with the stars moved (the print flickers between a and b)."""
    return _print("galaxy_star_print_b", "gx_print_b")


def galaxy_star_print_awakened():
    """각성 발자국: a shooting star rises out of each print."""
    return _print("galaxy_star_print_awakened", "gx_print_a", awake=True)


def galaxy_stardust_trail():
    """Card for 별빛 발자국: two starlight footprints walking forward under a sparkle."""
    m = MModel("galaxy_stardust_trail")
    cutout(m, "print", (1.0, 4.0, 1.0), (8.0, 4.1, 10.0), "gx_print_a", "y", glow=True)
    cutout(m, "print", (8.5, 4.0, 6.0), (15.5, 4.1, 15.0), "gx_print_b", "y", glow=True)
    star(m, "star", (11.5, 9.0, 4.0), 3.0)
    twinkle(m, "sparkle", (3.5, 7.5, 12.0), 2.0)
    _display(m, (55, 225, 0), 0.95, 0)
    return m


# ================================================================================================
# 행성 고리 (aura: worn, orbit) and the aura's card
# ================================================================================================

# the planets round the orbit (degrees from the front, diameter in units, boxes, texture), in their real order going
# round; earth at the front (the star of the set), its moon beside it
PLANETS = [("earth", 0, 2.7, 3), ("mars", 45, 1.9, 3), ("jupiter", 90, 3.7, 3), ("saturn", 135, 3.0, 3),
           ("uranus", 180, 2.3, 3), ("neptune", 225, 2.2, 3), ("mercury", 270, 1.3, 1), ("venus", 315, 2.1, 3)]


def _planet_ring(m, radius, awake, card=False):
    """41 parts (awakened 50): our solar system round the chest. A thin, see-through ribbon of twinkling stardust
    (violet over gold) marks a gently tilted orbit, and on it the eight planets, each its real look in miniature:
    earth with its moon, mars, jupiter (the biggest, its bands shifting, the Great Red Spot), saturn with its tilted
    ring, uranus with a thin upright ring, neptune, mercury, venus. Awakened: the planets glow, the asteroid belt
    circles outside (rocks and sparkles) and a comet flies along it with a glowing tail."""
    n = 16
    tilt = 1.1
    band = (0, 0, HS, BAND_ROWS)
    k = 0.72 if card else 1.0          # the card's smaller planets

    def on_ring(th, lift=0.0, r=radius, t=tilt, y0=10.0):
        a = math.radians(th)
        return at((8, y0 + t * math.cos(a) + lift, 8), th, r)

    for i in range(n):
        th = i * 22.5
        ribbon(m, "orbit", on_ring(th), th, 2 * math.pi * radius / n + 0.35, 1.2 if not card else 1.0, "gx_dust_band", band)
    for i, (kind, th, d, parts) in enumerate(PLANETS):
        c = on_ring(th, 0.25 * math.sin(i * 2.1))
        ball(m, kind, c, d * k, f"gx_{kind}", parts, glow=awake)
        if kind == "earth":
            ball(m, "moon", on_ring(th + 14, 1.5), 0.9 * k, "gx_luna", 1, glow=awake)
        elif kind == "saturn":
            rr = d * k * 1.05
            cut(m, "saturn_ring", (c[0] - rr, c[1] - 0.05, c[2] - rr), (c[0] + rr, c[1] + 0.05, c[2] + rr), "gx_saturn_band", "y",
                rotation=("x", -22.5, c))
        elif kind == "uranus":
            rr = d * k * 0.95
            # a thin ring standing nearly upright round it, turned to face along the orbit
            ribbon(m, "uranus_ring", c, th + 90, 2 * rr, 2 * rr, "gx_uranus_ring", (0, 0, HS, HS))
    if awake:
        # the asteroid belt outside the orbit (eight long bands of rocks and sparkles) and a comet flying along it
        for i in range(8):
            th = i * 45 + 22.5
            ribbon(m, "asteroids", on_ring(th, -0.4, r=radius + 2.6), th, 2 * math.pi * (radius + 2.6) / 8 + 0.6, 1.4,
                   "gx_asteroids", band)
        ribbon(m, "comet", on_ring(315, 1.3, r=radius + 2.6), 315, 5.0, 1.5, "gx_comet", band)


def galaxy_planet_ring():
    """은하수 행성 고리 (worn): a tilted ring of glittering gold and violet dust round the chest with a ringed violet
    planet, a gold one, an icy moon, a crescent and stars on it; turned round the body by the orbit motion."""
    m = MModel("galaxy_planet_ring")
    _planet_ring(m, 10.2, False)
    _display(m, HEAD_GUI, 0.5, 0)
    return m


def galaxy_planet_ring_awakened():
    """각성 행성 고리: a second ring crossing the first, a blue and a rose world on it and a comet."""
    m = MModel("galaxy_planet_ring_awakened")
    _planet_ring(m, 10.2, True)
    _display(m, HEAD_GUI, 0.45, 0)
    return m


def galaxy_constellation_aura():
    """Card for 은하수 행성 고리: the ring and its planets seen from above."""
    m = MModel("galaxy_constellation_aura")
    _planet_ring(m, 6.0, False, card=True)
    _display(m, (60, 225, 0), 1.0, 0)
    return m


# ================================================================================================
# 초신성 (kill burst) and its card
# ================================================================================================

def galaxy_supernova_burst():
    """은하수 초신성 (a kill burst): a white-hot star bursting where the victim fell, rays of violet and gold shooting out
    in every direction, a ring of gold star shards flying out round it and a spiral galaxy turning under it; grows and
    rises, then fades."""
    m = MModel("galaxy_supernova_burst")
    # the rays: four planes crossing round the vertical (0, 45, 90 and 135 degrees)
    for th in (0, 45, -45):
        plate(m, "rays", (8, 7.0, 8), 13.0, 13.0, "gx_burst", th)
    cutout(m, "rays", (7.95, 7.0, 8 - 6.5), (8.05, 20.0, 8 + 6.5), "gx_burst", "z", glow=True)
    # the heart: a white-gold orb
    orb(m, "heart", (8, 13.5, 8), 3.2, STAR_GEM, glow=True)
    # the shards: eight gold stars flying out on a ring round the heart
    for i in range(8):
        th = i * 45 + 22.5
        p = at((8, 13.5 + (1.5 if i % 2 else -1.5), 8), th, 7.4)
        star(m, "shard", p, 1.8, tex="gx_star" if i % 2 else "gx_star_violet", plane="x" if i % 4 in (0, 3) else "z")
    # the galaxy turning under it
    cutout(m, "galaxy", (1.5, 8.0, 1.5), (14.5, 8.1, 14.5), "gx_galaxy", "y", glow=True)
    m.display = {"head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
                 "gui": {"rotation": [20, 205, 0], "translation": [0, -4, 0], "scale": [0.55] * 3}}
    return m


def galaxy_supernova():
    """Card for 은하수 초신성: the burst."""
    m = galaxy_supernova_burst()
    m.name = "galaxy_supernova"
    return m


# ================================================================================================
# 세트 각성 고리 (cosmetic_sets.yml awaken.model): at the feet, worn at the aura anchor
# ================================================================================================

def galaxy_set_ring():
    """세트 각성 고리: a spiral galaxy turning flat at the feet (14.2 units under the aura anchor), a rim of gold dust round
    it and four small stars rising at its edge; turned slowly round (orbit)."""
    m = MModel("galaxy_set_ring")
    y = 8.0 - 14.2
    cutout(m, "galaxy", (-1.0, y + 0.15, -1.0), (17.0, y + 0.25, 17.0), "gx_galaxy", "y", glow=True)
    for i in range(16):
        th = i * 22.5
        ybox(m, "rim", at((8, y + 0.3, 8), th, 9.3), 0.5, 0.3, 3.9, th, DUST_GOLD)
    for i in range(4):
        th = i * 90 + 45
        p = at((8, y + 2.2 + i % 2, 8), th, 8.6)
        star(m, "rising", p, 1.6, tex="gx_star" if i % 2 else "gx_star_violet", plane="x" if i % 2 == 0 else "z")
    m.display = {"head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    return m


SLOTS = {"galaxy_star_crown": "above", "galaxy_cape": "back", "galaxy_moon_necklace": "neck",
         "galaxy_star_owl": "shoulder", "galaxy_star_belt": "waist", "galaxy_star_whale": "pet",
         "galaxy_stardust_trail": "trail", "galaxy_constellation_aura": "aura", "galaxy_supernova": "kill",
         # models shown by the engine, not items
         "galaxy_star_crown_awakened": "above", "galaxy_cape_awakened": "back",
         "galaxy_planet_ring": "aura", "galaxy_planet_ring_awakened": "aura",
         "galaxy_star_print_a": "print", "galaxy_star_print_b": "print", "galaxy_star_print_awakened": "print",
         "galaxy_supernova_burst": "burst", "galaxy_set_ring": "aura"}

ALL = {f.__name__: f for f in (galaxy_star_crown, galaxy_star_crown_awakened, galaxy_cape, galaxy_cape_awakened,
                               galaxy_moon_necklace, galaxy_star_owl, galaxy_star_belt, galaxy_star_whale,
                               galaxy_star_print_a, galaxy_star_print_b, galaxy_star_print_awakened, galaxy_stardust_trail,
                               galaxy_planet_ring, galaxy_planet_ring_awakened, galaxy_constellation_aura,
                               galaxy_supernova_burst, galaxy_supernova, galaxy_set_ring)}
