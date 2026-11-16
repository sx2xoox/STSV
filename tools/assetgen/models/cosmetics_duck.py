"""
아기 오리 가족 세트 (duck_family) v2: nine pieces that fill the body, painted by the donation rules (premium_art.py,
docs/ART_STYLE.md "후원 전용 고급 규칙": 32 texels a block or finer, painted soft shading, cut-out edges, gentle
animated textures), with awakened looks for a whole set and model effects instead of particles:

  꾸벅 아기 오리 (머리 위, bob)   · 파닥 아기 날개 (등, flap)   · 빼꼼 아기 오리 (어깨)   · 오리 턱받이 (목)
  오리 튜브 (허리)                · 졸졸 아기 오리들 (펫)       · 첨벙 발자국 (발자취: footprint models with a ripple)
  둥둥 고무오리 (오라: rubber ducklings bobbing on a ring of water round the feet, orbit)
  꽥! 고무오리 (처치: a big rubber duck pops up out of a splash)
  세트 각성: the sleeper gets a moon mobile, the wings fluff up with loose down, the ring's duck spouts a fountain with
  a little rainbow in its spray, the parade grows to four with the leader in a captain's cap, the aura gets more ducks, the prints a
  splash crown; lily pads turn round the feet (cosmetic_sets.yml awaken).

A daytime set: cute, round, soft, pastel yellow and orange; nothing glows but the moon and the stars of the mobile.

Same frames as models/cosmetics.py (attached: the model's (8, 8, 8) on the slot's anchor, north = the player's front,
+x their right; pets stand on y = 0 facing north; prints lie on y = 8 facing north; bursts stand on y = 8; the aura
anchor is 14.4 units above the feet).
"""

import math
import random

import economy_models  # noqa: F401
from economy_machines import MModel
from models.cosmetics import _display, BACK_GUI, HEAD_GUI
from premium_art import HMat, hd_canvas, hd_surface, shade, HS, D

# ================================================================================================
# materials: soft down (fluff) and glossy toy plastic (gloss) on top of the painted HD material
# ================================================================================================


class DuckMat(HMat):
    """
    HMat with two more kinds for this set:
      fluff  soft duckling down: rows of small overlapping tufts, each lit at its top and with a soft shade under it,
             a low-contrast gradient (cute, not crunchy)
      gloss  rubber-toy plastic: smooth, a big soft highlight to the top-left of each face and a thin streak
      cloth  a fine woven cotton (the bib, the pillow)
    """

    def _value(self, x, y, pw, ph, face, rnd):
        if self.kind not in ("fluff", "gloss", "cloth"):
            return super()._value(x, y, pw, ph, face, rnd)
        side = face not in ("up", "down")
        u, v = (x + 0.5) / pw, (y + 0.5) / ph
        b = self.base + {"up": 0.45, "north": 0.12, "west": 0.2, "south": -0.12, "east": -0.2, "down": -0.6}.get(face, 0) * self.contrast
        b += ((0.28 - 0.56 * v) if side else (0.16 - 0.32 * (u + v) / 2)) * self.contrast
        if self.kind == "fluff":
            tw, th = 4, 3
            row = y // th
            cx = (x + (tw // 2 if row % 2 else 0)) % tw
            cy = y % th
            b += 0.22 - 0.16 * abs(cx - 1.5) - (0.3 if cy == th - 1 else 0.0) + (0.12 if cy == 0 else 0.0)
            # a few lighter wisps of down
            if rnd.random() < 0.05:
                b += 0.35
        elif self.kind == "gloss":
            hx, hy = (0.3, 0.28) if side else (0.32, 0.3)
            r = math.hypot((u - hx) * 1.2, v - hy)
            b += 0.9 * math.exp(-(r / 0.16) ** 2)
            b += 0.35 * math.exp(-((u + v - 1.25) / 0.06) ** 2) if side else 0.0      # a thin streak
            b -= 0.3 * max(0.0, v - 0.7) / 0.3 if side else 0.0
        else:   # cloth
            b += 0.08 if (x + y) % 2 == 0 else -0.06
            if y % 4 == 3:
                b -= 0.08
        # soft rims (lighter on top and left, a soft shade at the bottom and right)
        if y == 0 and ph > 2:
            b += 0.3
        elif x == 0 and pw > 2:
            b += 0.15
        if y == ph - 1 and ph > 2:
            b -= 0.25
        elif x == pw - 1 and pw > 2:
            b -= 0.15
        if self.ao and side and ph > 4:
            near = (ph - 1 - y) / (1.5 * D)
            if near < 1:
                b -= 0.4 * (1 - near) ** 2
        return b + (rnd.random() - 0.5) * 0.08


FLUFF = DuckMat("corn", "fluff", 2.35)
FLUFF_LIGHT = DuckMat("corn", "fluff", 2.75)
FLUFF_SHADE = DuckMat("corn", "fluff", 1.85)
BILL = DuckMat("fox", "gloss", 2.9)
BILL_DARK = DuckMat("fox", "gloss", 2.0)
RUBBER = DuckMat("corn", "gloss", 2.3, contrast=1.15)
RUBBER_LIGHT = DuckMat("corn", "gloss", 2.8)
RUBBER_BILL = DuckMat("topaz", "gloss", 2.4)
STRIPE = DuckMat("snow", "gloss", 1.6)
SAILOR = DuckMat("snow", "cloth", 1.7)
SAILOR_BAND = DuckMat("sapphire", "cloth", 2.0)
CAPTAIN_PEAK = DuckMat("rubber", "gloss", 1.6)
GOLD = HMat("gold", "metal", 2.0)
PILLOW = DuckMat("cream", "cloth", 2.4)
PILLOW_PIPE = DuckMat("pink", "cloth", 2.5)
NIGHTCAP = DuckMat("sapphire", "cloth", 1.9)
NIGHTCAP_LIGHT = DuckMat("sapphire", "cloth", 2.5)
POMPOM = DuckMat("snow", "fluff", 1.8)
BIB_BAND = DuckMat("corn", "cloth", 2.6)
LILY = HMat("leaf", "smooth", 2.4)
STEM = HMat("leaf", "smooth", 1.6)


# ================================================================================================
# painted HD textures (32 px). Cut-outs keep their bottom-right corner clear (cutout()'s edge texel).
# ================================================================================================

def _blend(c0, c1, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(c0[i] + (c1[i] - c0[i]) * t)) for i in range(3)) + (255,)


def _fluff_base(px, seed, base=2.4, gloss=False):
    """A painted down (or glossy) background for a whole 32 px face: lighter to the top-left, soft tufts."""
    rnd = random.Random(seed)
    for y in range(HS):
        for x in range(HS):
            u, v = x / (HS - 1), y / (HS - 1)
            b = base + 0.32 - 0.42 * v - 0.12 * u
            if gloss:
                b += 0.85 * math.exp(-(math.hypot((u - 0.28) * 1.2, v - 0.24) / 0.17) ** 2)
            else:
                row = y // 3
                cx = (x + (2 if row % 2 else 0)) % 4
                b += 0.16 - 0.12 * abs(cx - 1.5) - (0.2 if y % 3 == 2 else 0)
                b += (rnd.random() - 0.5) * 0.08
            px[x, y] = shade("corn", b)


def _eye(px, cx, cy, rx, ry, small=False):
    """A big shiny eye: a dark oval, a white glint at its upper left and a small one lower right."""
    for y in range(HS):
        for x in range(HS):
            d = ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2
            if d <= 1.0:
                px[x, y] = shade("rubber", 1.6 - 1.2 * d)
    gx, gy = cx - rx * 0.38, cy - ry * 0.38
    for y in range(HS):
        for x in range(HS):
            if math.hypot(x + 0.5 - gx, (y + 0.5 - gy) * 0.95) <= (1.25 if not small else 0.9):
                px[x, y] = shade("feather", 3)
            if not small and math.hypot(x + 0.5 - (cx + rx * 0.42), y + 0.5 - (cy + ry * 0.45)) <= 0.6:
                px[x, y] = shade("feather", 2.6)


def _arc(px, cx, cy, r, up, w=1.1, col=None):
    """A closed eye: half a ring (up=False: a U, content asleep; up=True: an arch, happy)."""
    for y in range(HS):
        for x in range(HS):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            if abs(math.hypot(dx, dy * 1.15) - r) <= w and ((dy < 0.3) if up else (dy > -0.3)):
                px[x, y] = col or shade("rubber", 1.3)


def _blush(px, cx, cy):
    for y in range(HS):
        for x in range(HS):
            d = ((x + 0.5 - cx) / 3.2) ** 2 + ((y + 0.5 - cy) / 1.7) ** 2
            if d <= 1.0:
                px[x, y] = _blend(px[x, y], shade("pink", 2.3), 0.85 - 0.45 * d)


def _face(kind):
    """A duckling's face (the head's front, 32 px): painted down, big shiny eyes (open), sleeping U eyes (shut) or
    happy arches (happy), pink cheeks. The bill is a box of its own in front of it."""
    def paint(k):
        im = hd_canvas()
        px = im.load()
        _fluff_base(px, 11 + len(kind), 2.45)
        if kind == "open":
            _eye(px, 9.5, 13.5, 3.3, 4.2)
            _eye(px, 22.5, 13.5, 3.3, 4.2)
        elif kind == "shut":
            _arc(px, 9.5, 13.0, 3.2, up=False)
            _arc(px, 22.5, 13.0, 3.2, up=False)
        else:
            _arc(px, 9.5, 15.0, 3.2, up=True)
            _arc(px, 22.5, 15.0, 3.2, up=True)
        _blush(px, 5.0, 21.5)
        _blush(px, 27.0, 21.5)
        return im
    return paint


def _rubber_face():
    """A rubber duck's face (glossy toy yellow): round black eyes with a big glint, a hint of a smile line."""
    im = hd_canvas()
    px = im.load()
    _fluff_base(px, 5, 2.45, gloss=True)
    _eye(px, 9.0, 12.5, 3.0, 3.4, small=True)
    _eye(px, 23.0, 12.5, 3.0, 3.4, small=True)
    _blush(px, 5.0, 20.0)
    _blush(px, 27.0, 20.0)
    return im


def _tuft():
    """A little curl of down on a duckling's head (cut-out, seen from the side): three soft wisps curling forward."""
    im = hd_canvas()
    px = im.load()
    wisps = [(16, 31, 4.5, -0.8), (11, 30, 3.4, -1.3), (21, 30, 3.4, -0.3)]
    for (bx, by, w, lean) in wisps:
        for y in range(8, by):
            t = (by - y) / (by - 8)
            cx = bx + lean * 9 * t * t
            half = w * (1 - t) ** 0.6 + 0.6
            for x in range(HS):
                if abs(x + 0.5 - cx) <= half:
                    px[x, y] = shade("corn", 2.2 + 0.9 * t + 0.25 * (cx - x) / max(half, 1))
    return im


def _foot():
    """A webbed duckling foot seen from above (cut-out): three toes fanning north joined by webbing, glossy orange."""
    im = hd_canvas()
    px = im.load()
    heel = (16, 26)
    tips = [(7, 6), (16, 3), (25, 6)]
    for y in range(HS):
        for x in range(HS):
            p = (x + 0.5, y + 0.5)
            if y > 28:
                continue
            # the fan between the outer toes, scalloped at the front
            ang = math.atan2(p[0] - heel[0], heel[1] - p[1])
            r = math.hypot(p[0] - heel[0], p[1] - heel[1])
            reach = 20 - 3.5 * abs(math.sin(ang * 3.0))
            web = abs(ang) < 0.62 and r < reach
            toe = min(math.hypot(p[0] - (heel[0] + (t[0] - heel[0]) * s), p[1] - (heel[1] + (t[1] - heel[1]) * s))
                      for t in tips for s in [i / 12 for i in range(13)])
            if web or toe < 2.0 or r < 4.2:
                v = 2.9 - 0.04 * r + (0.5 if toe < 1.0 else 0.0) - (0.35 if web and toe > 2.5 else 0.0)
                px[x, y] = shade("fox", v)
    return im


def _feet():
    """Both webbed feet side by side (cut-out, from above), toes north: two of _foot() at half width."""
    one = _foot().resize((HS // 2, HS))
    im = hd_canvas()
    im.alpha_composite(one, (0, 0))
    im.alpha_composite(one, (HS // 2, 0))
    px = im.load()
    for y in range(29, HS):
        for x in range(29, HS):
            px[x, y] = (0, 0, 0, 0)
    return im


def _seg(p, a, b):
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((p[0] - ax) * dx + (p[1] - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(p[0] - ax - t * dx, p[1] - ay - t * dy)


def _print_tex(frame, awake=False):
    """첨벙 발자국 (32 px, from above, cut-out): a wet webbed duck print, toes north: a round heel pad, three toes
    spreading from it with the webbing between them curving in between the toe tips (the duck foot everyone knows),
    darker where the water stands with a glossy sheen; round it a soft ripple ring, small (a) or spreading wider and
    fainter (b), and a few droplets."""
    def paint(k):
        im = hd_canvas()
        px = im.load()
        cx, cy = 16, 17
        ring_r = 12.0 if frame == 0 else 14.2
        for y in range(HS):
            for x in range(HS):
                if x >= 29 and y >= 29:
                    continue
                r = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if abs(r - ring_r) <= (1.0 if frame == 0 else 0.7):
                    px[x, y] = shade("ice", 2.9 if frame == 0 else 2.4)
        heel = (16.0, 23.0)
        tips = [(9.0, 9.5), (16.0, 7.0), (23.0, 9.5)]
        for y in range(HS):
            for x in range(HS):
                if x >= 29 and y >= 29:
                    continue
                p = (x + 0.5, y + 0.5)
                toe = min(_seg(p, heel, t) for t in tips)
                # the web: inside the fan of the outer toes, its front edge long at each toe and curving back
                # in a scallop between two toes
                ang = math.atan2(p[0] - heel[0], heel[1] - p[1])
                r = math.hypot(p[0] - heel[0], p[1] - heel[1])
                front = 12.2 + 3.0 * (0.5 + 0.5 * math.cos(ang * math.tau / 0.48))
                web = abs(ang) < 0.5 and r < front
                pad = math.hypot((p[0] - heel[0]) / 2.8, (p[1] - heel[1]) / 2.4) < 1.0
                if toe < 1.1 or pad:
                    px[x, y] = shade("water", 0.9 + 0.4 * toe)
                elif web:
                    px[x, y] = shade("ice", 0.6 - 0.5 * (r / 14))
                else:
                    continue
                if math.hypot(p[0] - 13.5, p[1] - 15.0) < 1.7 or math.hypot(p[0] - 14.5, p[1] - 21.5) < 1.0:
                    px[x, y] = shade("ice", 3.0)                   # the glossy sheen of standing water
        drops = [(5, 10), (26, 12), (7, 26), (24, 27)] if frame == 0 else [(3, 7), (28, 10), (4, 27), (27, 24)]
        for (dx, dy) in drops:
            for (ox, oy) in ((0, 0), (1, 0), (0, 1), (1, 1)):
                if not (dx + ox >= 29 and dy + oy >= 29):
                    px[dx + ox, dy + oy] = shade("ice", 3.0 if (ox, oy) == (0, 0) else 2.4)
        return im
    return paint


def _splash(k):
    """A splash crown seen from the side (cut-out, 6 frames): a low crown of water with round droplets thrown up off
    its points, rising and falling."""
    im = hd_canvas()
    px = im.load()
    t = k / 6
    for y in range(HS):
        for x in range(HS):
            if x >= 29 and y >= 29:
                continue
            u = (x + 0.5) / HS
            crest = 31 - (6 + 4 * abs(math.sin(u * math.pi * 3.5)) * (0.7 + 0.3 * math.sin(t * math.tau)))
            if y + 0.5 > crest:
                px[x, y] = shade("ice", 2.0 + 0.8 * (31 - y) / 10)
    for i in range(4):
        x0 = 5 + i * 7.3
        h = 13 + 6 * math.sin(math.pi * ((t + i * 0.27) % 1.0))
        dy = 31 - h
        for y in range(HS):
            for x in range(HS):
                if x >= 29 and y >= 29:
                    continue
                if math.hypot(x + 0.5 - x0, y + 0.5 - dy) < 1.7:
                    px[x, y] = shade("ice", 3.0 if math.hypot(x + 0.5 - x0 + 0.5, y + 0.5 - dy + 0.5) < 0.8 else 2.4)
    return im


def _water_quarter(q, rin=0.6):
    """One quarter of a ring of water seen from above (cut-out, 8 frames), the quarter q (0: +x+z ... 3) of a ring
    whose centre is the texture's corner: ripples sweeping round it, bright crests, a pale shore line."""
    def paint(k):
        im = hd_canvas()
        px = im.load()
        for y in range(HS):
            for x in range(HS):
                if x >= 30 and y >= 30:
                    continue
                # the centre is the corner (0, 0) of the quarter: map so the ring sits in the texture
                u, v = (x + 0.5) / HS, (y + 0.5) / HS
                r = math.hypot(u, v)
                if not (rin <= r <= 0.98):
                    continue
                a = math.atan2(v, u) + q * math.pi / 2
                w = math.sin(a * 6 - k * math.tau / 8 + r * 14)
                edge = min(r - rin, 0.98 - r)
                val = 1.7 + 0.6 * w
                if edge < 0.04:
                    px[x, y] = shade("ice", 2.6)
                    continue
                if w > 0.82:
                    px[x, y] = shade("ice", 3.0)
                    continue
                px[x, y] = shade("water", val + 0.7 * (1 - abs((r - rin) / (0.98 - rin) - 0.5) * 2))
        return im
    return paint


def _lily(flower):
    """A lily pad from above (cut-out): a round green pad with a notch and veins; flower: a pink water lily on it."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            dx, dy = x + 0.5 - 16, y + 0.5 - 16
            r = math.hypot(dx, dy)
            a = math.atan2(dy, dx)
            if r > 14.5 or (abs(a - 0.6) < 0.22 and r > 3) or (x >= 29 and y >= 29):
                continue
            v = 2.6 - 0.07 * r + (0.3 if abs(math.sin(a * 5)) < 0.08 else 0)
            px[x, y] = shade("leaf", v)
    if flower:
        for y in range(HS):
            for x in range(HS):
                dx, dy = x + 0.5 - 13, y + 0.5 - 14
                r = math.hypot(dx, dy)
                a = math.atan2(dy, dx)
                petal = 6.5 * (0.55 + 0.45 * abs(math.cos(a * 3)))
                if r < petal:
                    px[x, y] = shade("pink", 3.0 - 0.18 * r)
                if r < 1.8:
                    px[x, y] = shade("corn", 3)
    return im


def _bubble(k):
    """A soap bubble (cut-out, 8 frames): a pale rim, a clear middle with a faint rainbow sheen, a moving glint."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            dx, dy = x + 0.5 - 16, y + 0.5 - 16
            r = math.hypot(dx, dy)
            if r > 14 or (x >= 29 and y >= 29):
                continue
            if r > 12.6:
                px[x, y] = shade("ice", 2.9)
            elif r > 11.4:
                a = math.atan2(dy, dx) + k * math.tau / 8
                px[x, y] = shade("magic", 3.0) if math.sin(a * 2) > 0.5 else shade("ice", 2.4)
    a = -2.3 + 0.25 * math.sin(k * math.tau / 8)
    gx, gy = 16 + 8.5 * math.cos(a), 16 + 8.5 * math.sin(a)
    for y in range(HS):
        for x in range(HS):
            if math.hypot(x + 0.5 - gx, y + 0.5 - gy) < 2.3:
                px[x, y] = shade("feather", 3)
    return im


def _z_letters(k):
    """Sleep 'z's (cut-out, 8 frames): three small z's drifting up and out, fading as they rise."""
    im = hd_canvas()
    px = im.load()
    for i in range(3):
        t = ((k / 8) + i / 3) % 1.0
        size = 4 + 3 * t
        cx, cy = 8 + 12 * t + i * 2, 27 - 20 * t
        if t > 0.92:
            continue
        val = 3.0 - 1.0 * t
        n = max(3, int(size * 2))
        for j in range(n + 1):
            s = j / n
            pts = [(cx - size / 2 + size * s, cy - size / 2), (cx + size / 2 - size * s, cy - size / 2 + size * s),
                   (cx - size / 2 + size * s, cy + size / 2)]
            for (x, y) in pts:
                xi, yi = int(x), int(y)
                if 0 <= xi < HS and 0 <= yi < HS and not (xi >= 29 and yi >= 29):
                    px[xi, yi] = shade("snow", val)
    return im


def _moon():
    """A sleepy crescent moon (cut-out, softly glowing) with a little star beside it."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            if x >= 29 and y >= 29:
                continue
            d0 = math.hypot(x + 0.5 - 14, y + 0.5 - 15)
            d1 = math.hypot(x + 0.5 - 19, y + 0.5 - 12)
            if d0 <= 11 and d1 > 9.5:
                px[x, y] = shade("lamp", 3.0 - 0.06 * d0)
    for y in range(HS):
        for x in range(HS):
            dx, dy = abs(x + 0.5 - 26), abs(y + 0.5 - 7)
            if dx + dy < 3.2 or (dx < 0.8 and dy < 4.5) or (dy < 0.8 and dx < 4.5):
                px[x, y] = shade("lamp", 3)
    return im


def _star():
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            dx, dy = abs(x + 0.5 - 16), abs(y + 0.5 - 16)
            a = math.atan2(y + 0.5 - 16, x + 0.5 - 16)
            r = math.hypot(dx, dy)
            if r < 6 + 7 * max(0.0, math.cos(a * 5 + math.pi / 2)) ** 3 and not (x >= 29 and y >= 29):
                px[x, y] = shade("lamp", 3.0 - 0.06 * r)
    return im


def _fountain(k):
    """A little water spout (side view, cut-out, 6 frames): a jet rising from the bottom middle and arcing down to
    both sides in droplets."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        up = 31 - y
        w = 1.6 - up * 0.02
        for x in range(HS):
            if abs(x + 0.5 - 16) <= w and up < 22:
                px[x, y] = shade("ice", 2.4 + 0.6 * ((up + k * 3) % 6 < 3))
    # a little rainbow in the spray
    bands = [("tomato", 3), ("topaz", 3), ("corn", 3), ("sprout", 4), ("sapphire", 3)]
    for y in range(HS):
        for x in range(HS):
            r = math.hypot(x + 0.5 - 16, y + 0.5 - 17)
            i = int((13.5 - r) / 1.1)
            if 0 <= i < len(bands) and y < 15 and abs(x + 0.5 - 16) > 2.2 and not (x >= 29 and y >= 29):
                px[x, y] = shade(*bands[i])
    for side in (-1, 1):
        for i in range(10):
            t = (i / 10 + k / 6 / 2) % 1.0
            x = 16 + side * 13 * t
            y = 9 + 22 * (t - 0.35) ** 2 * 2.1
            for (ox, oy) in ((0, 0), (1, 0), (0, 1), (1, 1)):
                xi, yi = int(x) + ox, int(y) + oy
                if 0 <= xi < HS and 0 <= yi < HS and not (xi >= 29 and yi >= 29):
                    px[xi, yi] = shade("ice", 2.9 if (ox, oy) == (0, 0) else 2.3)
    return im


def _fringe(awake):
    """The lower edge of a baby wing (cut-out, seen from the side): a row of rounded feather tips hanging down, pale
    yellow over cream, each tip with a soft shade line; awake: a second, longer row of creamy tips."""
    def paint(k):
        im = hd_canvas()
        px = im.load()
        rows = [(9, 4.6, "petal_yellow", 2.2), (17, 5.2, "cream", 2.9)] + ([(25, 5.6, "cream", 2.7)] if awake else [])
        for (cy, r, ramp_name, base) in rows:
            n = 6
            for i in range(n):
                cx = 2.5 + i * (27.0 / (n - 1)) + (2.2 if cy == 17 else 0)
                for y in range(HS):
                    for x in range(HS):
                        if x >= 29 and y >= 29:
                            continue
                        d = math.hypot((x + 0.5 - cx) / r, (y + 0.5 - cy) / (r * 1.25))
                        if d < 1.0 and y + 0.5 > cy - r * 1.6:
                            px[x, y] = shade(ramp_name, base - 0.7 * d) if d < 0.82 else shade("corn", 1.6)
        # the top: the wing's yellow, so the fringe hangs from it
        for y in range(0, 6):
            for x in range(HS):
                if not px[x, y][3]:
                    px[x, y] = shade("corn", 2.4 + 0.1 * y)
        return im
    return paint


def _feather():
    """A single loose down feather (cut-out): a soft cream plume on a thin quill."""
    im = hd_canvas()
    px = im.load()
    for y in range(4, 28):
        t = (y - 4) / 24
        cx = 16 + 3 * math.sin(t * 2.5)
        half = 6.5 * math.sin(math.pi * min(1.0, t * 1.15)) + 0.6
        for x in range(HS):
            d = abs(x + 0.5 - cx)
            if d <= half and not (x >= 29 and y >= 29):
                px[x, y] = shade("cream", 2.9 - 0.12 * d) if d > 0.7 else shade("corn", 2.0)
    return im


def _bib():
    """오리 턱받이 (cut-out, the bib's front): a rounded cotton bib with a scalloped yellow edge, a soft duckling face
    embroidered in the middle and a little blue anchor stitch under it."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            if x >= 29 and y >= 29:
                continue
            u, v = (x + 0.5 - 16) / 15.5, (y + 0.5) / HS
            # the bib: straight at the top (the neck), widening into a round bottom
            if v < 0.1:
                if abs(u) > 0.42:
                    continue
            else:
                rr = (u / 0.98) ** 2 + ((v - 0.42) / 0.56) ** 2
                if rr > 1.0 or v > 0.97:
                    continue
                if rr > 0.86:
                    sc = 0.5 + 0.5 * math.cos(math.atan2(v - 0.42, u) * 14)
                    if rr > 0.92 + 0.07 * sc:
                        continue
                    px[x, y] = shade("corn", 2.7 - 0.6 * rr)
                    continue
            b = 2.5 - 0.5 * v + (0.06 if (x + y) % 2 else -0.04)
            px[x, y] = shade("snow", b)
    # the embroidered duckling face: a yellow round head, an orange bill, two dot eyes
    for y in range(HS):
        for x in range(HS):
            d = math.hypot(x + 0.5 - 16, y + 0.5 - 15)
            if d < 6.2:
                px[x, y] = shade("corn", 2.8 - 0.1 * d)
            if abs(x + 0.5 - 16) < 3.2 and 16.5 < y + 0.5 < 19:
                px[x, y] = shade("fox", 2.9)
            for ex in (13.3, 18.7):
                if math.hypot(x + 0.5 - ex, y + 0.5 - 13.5) < 1.1:
                    px[x, y] = shade("rubber", 1)
    # the anchor stitch under it
    for (x, y) in ((16, 23), (16, 24), (16, 25), (15, 23), (17, 23), (14, 25), (18, 25), (15, 26), (17, 26), (16, 26)):
        px[x, y] = shade("sapphire", 2.6)
    return im


def _badge():
    """The captain's cap badge: a little gold anchor in a wreath."""
    im = hd_canvas()
    px = im.load()
    for y in range(HS):
        for x in range(HS):
            r = math.hypot(x + 0.5 - 16, y + 0.5 - 16)
            if 10 < r < 13.5 and y > 6 and not (x >= 29 and y >= 29):
                px[x, y] = shade("gold", 2.6 - 0.3 * ((x + y) % 3 == 0))
    for (x0, y0, x1, y1) in ((15, 6, 17, 24), (10, 10, 22, 12), (10, 20, 22, 22)):
        for y in range(y0, y1):
            for x in range(x0, x1):
                px[x, y] = shade("gold", 3)
    for (x, y) in ((9, 19), (23, 19), (10, 18), (22, 18)):
        px[x, y] = shade("gold", 3)
    return im


for _k in ("open", "shut", "happy"):
    hd_surface(f"dk_face_{_k}", _face(_k))
hd_surface("dk_face_rubber", lambda k: _rubber_face())
hd_surface("dk_tuft", lambda k: _tuft())
hd_surface("dk_feet", lambda k: _feet())
hd_surface("dk_print_a", _print_tex(0))
hd_surface("dk_print_b", _print_tex(1))
hd_surface("dk_splash", _splash, 6, 2)
hd_surface("dk_water_0", _water_quarter(0), 8, 3)
hd_surface("dk_lily", lambda k: _lily(False))
hd_surface("dk_lily_flower", lambda k: _lily(True))
hd_surface("dk_bubble", _bubble, 8, 3)
hd_surface("dk_z", _z_letters, 8, 4)
hd_surface("dk_moon", lambda k: _moon())
hd_surface("dk_star", lambda k: _star())
hd_surface("dk_fountain", _fountain, 6, 2)
hd_surface("dk_fringe", _fringe(False))
hd_surface("dk_fringe_awake", _fringe(True))
hd_surface("dk_feather", lambda k: _feather())
hd_surface("dk_bib", lambda k: _bib())
hd_surface("dk_badge", lambda k: _badge())

FULL = [0, 0, HS, HS]
ALL_FACES = ("north", "south", "east", "west", "up", "down")


CLEAR = [30, 30, 32, 32]      # a clear texel of the cut-outs (their thin edges)


def plate(m, name, frm, to, tex, plane, glow=False, flip=False, rotation=None):
    """A thin cut-out plate showing a whole 32 px texture on its two big faces, a clear texel on its thin edges.
    plane: 'x' (faces north/south), 'z' (east/west) or 'y' (up/down). flip: the texture mirrored."""
    a, b = ([HS, 0, 0, HS], FULL) if flip else (FULL, [HS, 0, 0, HS])
    uv = {f: CLEAR for f in ALL_FACES}
    if plane == "x":
        uv.update({"north": a, "south": b})
    elif plane == "z":
        uv.update({"east": a, "west": b})
    else:
        uv.update({"up": FULL, "down": [0, HS, HS, 0]})
    return m.box(name, frm, to, tex, uv=uv, glow=glow, shade=not glow, rotation=rotation)


def crossed(m, name, c, w, h, tex, glow=False, y0=None):
    """Two crossed upright cut-out plates (x and z planes) centred on c, w wide and h tall from y0 (default c's y)."""
    x, y, z = c
    y0 = y if y0 is None else y0
    plate(m, name, (x - w / 2, y0, z - 0.02), (x + w / 2, y0 + h, z + 0.02), tex, "x", glow=glow)
    plate(m, name, (x - 0.02, y0, z - w / 2), (x + 0.02, y0 + h, z + w / 2), tex, "z", glow=glow)


def octagon(m, name, a, t, y0, y1, mat, c=(8, 8), faces=None):
    """An upright octagonal band round the y axis: outer faces a from the centre, t thick (inwards), y0..y1: four
    straight sides and four corners turned 45 degrees."""
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


# ================================================================================================
# the duckling
# ================================================================================================

def duckling(m, n, o, s=1.0, face="open", hat=None, pose="stand", detail="full", wave=None):
    """
    One round duckling facing north (-z). o = (x centre, ground y, z centre), s = its size.
    Round, soft and cute: a fluffy body with a puffed chest and a little cap on top so it reads round, a big head
    with a painted face (eyes and pink cheeks: dk_face_*), a glossy orange bill, a curl of down on top, stubby wings
    with fluffy tips, a tail flicking up, flat webbed feet. Silhouette first, details painted.
    face: open / shut / happy / rubber. hat: None / sailor / captain / night. pose: stand / sit (no legs, on its
    belly). detail (the render budget): full (worn pieces) / pet (a pet's leader) / lite / tiny (the little ones).
    wave: None / "left" / "right": that wing raised.
    """
    ox, oy, oz = o
    lift = -1.0 if pose == "sit" else 0.0
    mats = (RUBBER, RUBBER_LIGHT, RUBBER, RUBBER_BILL) if face == "rubber" else (FLUFF, FLUFF_LIGHT, FLUFF_SHADE, BILL)
    body, light, under, bill = mats
    full, pet = detail == "full", detail in ("full", "pet")

    def P(a):
        return (ox + a[0] * s, oy + (a[1] + lift) * s, oz + a[2] * s)

    def B(name, a, b, mat, faces=None, **kw):
        m.box(f"{n}_{name}", P(a), P(b), mat, faces=faces, **kw)

    # body: a box, a puffed chest in front and a cap on top (round from every side), shade underneath
    B("body", (-2.5, 1.2, -2.0), (2.5, 4.4, 3.0), body, faces={"down": under})
    if pet:
        B("chest", (-2.0, 1.5, -2.6), (2.0, 3.9, -2.0), light, faces={"south": None})
    if full:
        B("back", (-1.9, 4.4, -1.2), (1.9, 4.9, 2.6), light, faces={"down": None})
    # tail: a flick of down up and back
    if pet:
        B("tail", (-0.9, 3.4, 2.8), (0.9, 4.6, 4.0), light, rotation=("x", 22.5, P((0, 4.0, 3.4))))
    # head: big, forward on the body, its face painted, a cap on top for roundness (under a hat: the hat)
    face_tex = "dk_face_rubber" if face == "rubber" else f"dk_face_{face}"
    B("head", (-2.1, 4.2, -3.7), (2.1, 8.2, 0.3), body, faces={"north": face_tex}, full_uv=("north",))
    if hat is None and detail != "tiny":
        B("crown", (-1.7, 8.2, -3.3), (1.7, 8.65, -0.1), light, faces={"down": None})
    if full:
        for sx in (1, -1):    # round the cheeks
            x0, x1 = (2.1, 2.4) if sx == 1 else (-2.4, -2.1)
            B("cheek", (x0, 4.8, -3.2), (x1, 7.6, -0.2), body, faces={"east" if sx < 0 else "west": None})
    # bill: a flat glossy upper bill over a smaller lower one
    B("bill", (-1.3, 5.0, -4.95), (1.3, 5.85, -3.7), bill, faces={"south": None})
    if full:
        B("bill_low", (-0.95, 4.6, -4.6), (0.95, 5.0, -3.7), BILL_DARK if face != "rubber" else RUBBER_BILL, faces={"south": None, "up": None})
    # the curl of down on top (a cut-out)
    if full and hat is None and face != "rubber":
        plate(m, f"{n}_tuft", P((-1.1, 8.5, -1.75)), P((1.1, 10.7, -1.7)), "dk_tuft", "x")
    # stubby wings with fluffy cream tips (one raised when it waves)
    if detail != "tiny":
        for sx in (1, -1):
            x0, x1 = (2.5, 3.05) if sx == 1 else (-3.05, -2.5)
            raised = wave == ("right" if sx == 1 else "left")
            if raised:
                B("wing", (x0, 3.6, -1.4), (x1, 6.2, 0.4), light, rotation=("z", -22.5 * sx, P((2.7 * sx, 3.6, -0.5))))
            else:
                B("wing", (x0, 2.0, -1.2), (x1, 3.8, 2.2), light, faces={"west" if sx == 1 else "east": None})
                if full:
                    B("wing_tip", (x0, 1.6, 1.0), (x1, 2.0, 2.2), DuckMat("cream", "fluff", 2.6),
                      faces={"west" if sx == 1 else "east": None})
    # feet: both webbed feet on one flat cut-out, little legs when standing (worn pieces only)
    if face != "rubber":
        if pose == "stand":
            plate(m, f"{n}_feet", P((-2.2, 0.02, -2.4)), P((2.2, 0.06, -0.2)), "dk_feet", "y")
            if full:
                for x0 in (0.9, -1.4):
                    B("leg", (x0, 0.06, -0.9), (x0 + 0.5, 1.25, -0.4), BILL_DARK, faces={"up": None})
        elif full:
            plate(m, f"{n}_feet", P((-2.2, 1.02, -3.4)), P((2.2, 1.06, -1.2)), "dk_feet", "y")
    if hat == "sailor":
        B("hat_band", (-1.65, 8.2, -2.9), (1.65, 8.7, 0.0), SAILOR_BAND, faces={"down": None})
        B("hat", (-1.35, 8.7, -2.6), (1.35, 9.8, -0.3), SAILOR, faces={"down": None})
        B("hat_top", (-0.35, 9.8, -1.75), (0.35, 10.2, -1.15), SAILOR_BAND, faces={"down": None})
    elif hat == "captain":
        B("cap_band", (-1.85, 8.2, -3.0), (1.85, 8.85, 0.1), CAPTAIN_PEAK, faces={"down": None})
        B("cap", (-2.1, 8.85, -3.3), (2.1, 9.9, 0.4), SAILOR)
        B("peak", (-1.6, 8.2, -4.3), (1.6, 8.5, -3.0), CAPTAIN_PEAK)
        plate(m, f"{n}_badge", P((-0.75, 8.95, -3.36)), P((0.75, 9.85, -3.31)), "dk_badge", "x")
    elif hat == "night":
        # a floppy nightcap: a band, a cone stepping up and back, the tip drooping to one side with a pom-pom
        B("cap_band", (-1.9, 8.1, -3.3), (1.9, 8.75, 0.1), NIGHTCAP_LIGHT, faces={"down": None})
        B("cap1", (-1.6, 8.75, -2.9), (1.6, 9.8, -0.2), NIGHTCAP, faces={"down": None})
        B("cap2", (-1.0, 9.8, -2.2), (1.2, 10.7, -0.3), NIGHTCAP, faces={"down": None})
        B("cap3", (0.2, 10.4, -1.6), (2.0, 11.1, 0.6), NIGHTCAP_LIGHT, rotation=("z", -22.5, P((0.8, 10.7, -0.5))))
        B("pompom", (1.9, 9.6, -1.0), (3.1, 10.8, 0.2), POMPOM)


# ================================================================================================
# 꾸벅 아기 오리 (above, bob)
# ================================================================================================

def _sleeper(m, awake):
    # a little round pillow with piping, the duckling asleep on it in a nightcap
    m.box("pillow", (5.0, 8.3, 5.4), (11.0, 9.5, 11.8), PILLOW, faces={"down": DuckMat("cream", "cloth", 1.8)})
    m.box("pillow_top", (5.5, 9.5, 5.9), (10.5, 10.0, 11.3), PILLOW, faces={"down": None})
    m.box("piping", (4.85, 8.75, 5.25), (11.15, 9.05, 11.95), PILLOW_PIPE)
    duckling(m, "duck", (8.0, 10.0, 8.6), 1.05, face="shut", hat="night", pose="sit")
    # a snot bubble from the bill and z's drifting up behind (both cut-outs, animated)
    crossed(m, "bubble", (10.2, 13.0, 3.2), 2.4, 2.4, "dk_bubble", y0=12.6)
    plate(m, "z", (8.6, 15.5, 7.95), (14.6, 21.5, 8.0), "dk_z", "x")
    if awake:
        # a moon mobile over the sleeper: a thin arm, the crescent moon (softly glowing) and two stars
        m.box("mobile_arm", (2.0, 22.4, 7.85), (14.0, 22.7, 8.15), GOLD)
        m.box("mobile_hook", (7.85, 22.7, 7.85), (8.15, 24.4, 8.15), GOLD)
        for (x, h) in ((3.0, 2.2), (13.0, 1.6)):
            m.box("mobile_string", (x - 0.06, 22.4 - h, 7.94), (x + 0.06, 22.4, 8.06), DuckMat("snow", "cloth", 2.0))
        crossed(m, "moon", (3.0, 0, 8.0), 4.6, 4.6, "dk_moon", glow=True, y0=22.4 - 2.2 - 4.6)
        crossed(m, "star", (13.0, 0, 8.0), 2.6, 2.6, "dk_star", glow=True, y0=22.4 - 1.6 - 2.6)
        crossed(m, "star", (8.0, 0, 8.0), 1.6, 1.6, "dk_star", glow=True, y0=22.6 - 0.2 - 1.6 + 2.6)


def sleepy_duckling():
    """꾸벅 아기 오리 (머리 위): a duckling fast asleep on a little piped pillow, in a floppy blue nightcap with a
    pom-pom, a snot bubble swelling from its bill and z's drifting up. Bobs with its breathing."""
    m = MModel("sleepy_duckling")
    _sleeper(m, False)
    _display(m, HEAD_GUI, 0.85, -6)
    return m


def sleepy_duckling_awakened():
    """각성 꾸벅 오리: a moon mobile hangs over the sleeper, the crescent moon and stars softly glowing."""
    m = MModel("sleepy_duckling_awakened")
    _sleeper(m, True)
    _display(m, HEAD_GUI, 0.7, -8)
    return m


# ================================================================================================
# 파닥 아기 날개 (back, flap)
# ================================================================================================

def _wings(m, awake):
    s = 1.35 if awake else 1.0
    top, back = 10.4, 8.4
    for side in (1, -1):
        xr = 8 + side * 2.0
        rot = ("y", 45 * side, (xr, top - 3, back))
        x0, x1 = (xr, xr + 1.3) if side == 1 else (xr - 1.3, xr)

        def W(name, z0, z1, y0, y1, mat, **kw):
            m.box(name, (x0, top - y1 * s, back + z0 * s), (x1, top - y0 * s, back + z1 * s), mat, rotation=rot, **kw)

        # a soft teardrop seen from the side: tall at the root, stepping down and in towards a rounded tip
        W("wing", 0.0, 2.6, 0.0, 4.6, FLUFF)
        W("wing", 2.6, 4.6, 0.6, 4.0, FLUFF)
        W("wing", 4.6, 6.2, 1.3, 3.4, FLUFF_LIGHT)
        W("wing_tip", 6.2, 7.2, 1.9, 2.9, FLUFF_LIGHT)
        # the top edge catching the light (coverts)
        W("covert", 0.3, 4.4, -0.35, 0.6, FLUFF_LIGHT)
        # the fringe of rounded feather tips along the lower edge, a hair outside the wing on both sides
        for dx in (-0.05, 1.35):
            xa = x0 + dx if side == 1 else x1 - dx - 0.05
            plate(m, "fringe", (xa, top - 6.2 * s, back + 0.2 * s), (xa + 0.05, top - 3.4 * s, back + 6.6 * s),
                  "dk_fringe_awake" if awake else "dk_fringe", "z", flip=(side < 0) != (dx > 0), rotation=rot)
    if awake:
        # loose down floating round the wings
        for (x, y, z, d) in ((0.5, 14.0, 15.0, 2.2), (15.5, 12.5, 14.5, 2.0), (2.0, 6.5, 17.5, 1.8), (14.0, 5.5, 17.0, 1.6)):
            crossed(m, "down", (x, 0, z), d, d * 1.3, "dk_feather", y0=y)


def duckling_wings():
    """파닥 아기 날개 (등): two soft baby wings on the upper back, painted teardrops of yellow coverts over rows of
    fluffy pale and cream feather tips, running back from fluffy roots and turned out (from the front only the tips
    peek past the arms). Flutter (flap)."""
    m = MModel("duckling_wings")
    _wings(m, False)
    _display(m, BACK_GUI, 0.55, 1)
    return m


def duckling_wings_awakened():
    """각성 날개: the wings fluff up bigger with a third row of creamy tips, loose down floating round them."""
    m = MModel("duckling_wings_awakened")
    _wings(m, True)
    _display(m, BACK_GUI, 0.45, 1)
    return m


# ================================================================================================
# 빼꼼 아기 오리 (shoulder)
# ================================================================================================

def peeking_duckling():
    """빼꼼 아기 오리 (어깨): a duckling sitting on the shoulder, peeking forward with big shiny eyes, one wing raised
    in a little wave, a tiny sailor cap."""
    m = MModel("peeking_duckling")
    duckling(m, "duck", (8.0, 8.0, 8.6), 0.95, face="open", hat="sailor", pose="sit", wave="right")
    _display(m, (20, 210, 0), 1.3, -6)
    return m


# ================================================================================================
# 오리 턱받이 (neck)
# ================================================================================================

def duck_bib():
    """오리 턱받이 (목): a round cotton bib tied at the neck, its edge scalloped in yellow, a duckling face and an
    anchor stitched on the front; a soft yellow band round the neck and a bow at the back."""
    m = MModel("duck_bib")
    # the band round the top of the body (the collar line), soft cotton
    x0, x1, z0, z1 = 3.9, 12.1, 5.75, 10.25
    for (a, b) in (((x0, 7.9, z0), (x1, 9.1, 6.15)), ((x0, 7.9, 9.85), (x1, 9.1, z1)),
                   ((x0, 7.9, 6.15), (4.3, 9.1, 9.85)), ((11.7, 7.9, 6.15), (x1, 9.1, 9.85))):
        m.box("band", a, b, BIB_BAND)
    # the bib hanging on the chest (a painted cut-out, a hair off the body), a little pad behind it for depth
    plate(m, "bib", (3.6, 0.2, 5.5), (12.4, 9.0, 5.55), "dk_bib", "x")
    # a bow at the back
    m.box("bow_knot", (7.3, 8.0, 10.25), (8.7, 9.2, 10.85), BIB_BAND)
    for side in (-1, 1):
        m.box("bow_loop", (8 + side * 0.7 - (1.8 if side < 0 else 0), 7.7, 10.35), (8 + side * 0.7 + (1.8 if side > 0 else 0), 9.5, 10.75),
              BIB_BAND, rotation=("z", 22.5 * side, (8 + side * 1.6, 8.6, 10.55)))
        m.box("bow_tail", (8 + side * 0.4 - 0.4, 5.6, 10.4), (8 + side * 0.4 + 0.4, 8.0, 10.7), BIB_BAND,
              rotation=("z", 22.5 * side, (8 + side * 0.4, 8.0, 10.55)))
    _display(m, (30, 225, 0), 1.0, -4)
    return m


# ================================================================================================
# 오리 튜브 (waist)
# ================================================================================================

def _ring(m, awake):
    # a fat glossy swim ring: a wide middle band between a slimmer top and bottom (reads round), white stripes
    octagon(m, "ring", 6.7, 2.8, 4.3, 6.1, RUBBER)
    octagon(m, "ring_top", 6.3, 2.0, 6.1, 6.9, RUBBER_LIGHT, faces={"down": None})
    octagon(m, "ring_low", 6.3, 2.0, 3.5, 4.3, RUBBER, faces={"up": None})
    for (x0, z0, x1, z1) in ((7.0, 1.25, 9.0, 1.4), (7.0, 14.6, 9.0, 14.75), (1.25, 7.0, 1.4, 9.0), (14.6, 7.0, 14.75, 9.0)):
        m.box("stripe", (x0, 4.1, z0), (x1, 6.5, z1), STRIPE)
    # the duck at the front: a neck rising off the ring, its painted rubber face, glossy bill, a tuft
    m.box("neck", (6.5, 6.9, 0.9), (9.5, 9.2, 3.5), RUBBER)
    m.box("head", (5.9, 8.8, 0.4), (10.1, 12.6, 3.9), RUBBER, faces={"north": "dk_face_rubber"}, full_uv=("north",))
    m.box("crown", (6.3, 12.6, 0.8), (9.7, 13.0, 3.5), RUBBER_LIGHT, faces={"down": None})
    m.box("bill", (6.7, 9.6, -0.9), (9.3, 10.5, 0.4), RUBBER_BILL, faces={"south": None})
    m.box("bill_low", (7.05, 9.2, -0.6), (8.95, 9.6, 0.4), RUBBER_BILL, faces={"south": None, "up": None})
    # the tail flicking up at the back
    m.box("tail", (7.0, 6.4, 13.9), (9.0, 7.9, 15.4), RUBBER_LIGHT)
    m.box("tail_tip", (7.3, 7.9, 14.6), (8.7, 9.3, 15.6), RUBBER_LIGHT, rotation=("x", 22.5, (8.0, 7.9, 15.0)))
    if awake:
        # a little fountain spouting from the duck's head and a rainbow arching over the front
        crossed(m, "fountain", (8.0, 0, 2.15), 7.0, 7.0, "dk_fountain", y0=12.9)


def duck_float_ring():
    """오리 튜브 (허리): a fat glossy yellow swim ring round the hips with white stripes, a rubber duck's head rising at
    the front (shiny eyes, rosy cheeks, orange bill) and its tail flicking up at the back."""
    m = MModel("duck_float_ring")
    _ring(m, False)
    _display(m, HEAD_GUI, 0.75, 0)
    return m


def duck_float_ring_awakened():
    """각성 튜브: the duck spouts a little fountain and a small rainbow arches over the front of the ring."""
    m = MModel("duck_float_ring_awakened")
    _ring(m, True)
    _display(m, HEAD_GUI, 0.6, 0)
    return m


# ================================================================================================
# 졸졸 아기 오리들 (pet)
# ================================================================================================

def duckling_parade():
    """졸졸 아기 오리들 (펫): three ducklings waddling in a line, the leader in a sailor cap, the little ones after it,
    each a little off the line so the row reads from every side."""
    m = MModel("duckling_parade")
    duckling(m, "lead", (8.0, 0, 3.0), 1.0, hat="sailor", detail="pet")
    duckling(m, "second", (6.4, 0, 10.0), 0.85, face="happy", detail="lite")
    duckling(m, "third", (9.4, 0, 16.0), 0.78, detail="lite")
    _display(m, (25, 210, 0), 0.75, -1)
    return m


def duckling_parade_awakened():
    """각성 오리들: the parade grows to four, the leader in a captain's cap with a gold badge."""
    m = MModel("duckling_parade_awakened")
    duckling(m, "lead", (8.0, 0, 2.6), 1.05, hat="captain", detail="pet")
    duckling(m, "second", (6.2, 0, 9.4), 0.85, face="happy", detail="lite")
    duckling(m, "third", (9.6, 0, 15.2), 0.78, detail="lite")
    duckling(m, "fourth", (7.2, 0, 20.6), 0.7, face="happy", detail="tiny")
    _display(m, (25, 210, 0), 0.65, -1)
    return m


# ================================================================================================
# 첨벙 발자국 (trail prints) and the trail's card
# ================================================================================================

def _print(name, tex, awake=False):
    m = MModel(name)
    plate(m, "print", (1.0, 8.02, 1.0), (15.0, 8.1, 15.0), tex, "y")
    if awake:
        # a splash crown thrown up round the print
        crossed(m, "splash", (8.0, 0, 8.0), 9.0, 7.0, "dk_splash", y0=8.05)
    m.display = {"head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    return m


def duck_print_a():
    """첨벙 발자국 (frame a): a wet webbed print in a small splash ring."""
    return _print("duck_print_a", "dk_print_a")


def duck_print_b():
    """첨벙 발자국 (frame b): the ripple spreading wider and fainter (the print flickers between a and b)."""
    return _print("duck_print_b", "dk_print_b")


def duck_print_awakened():
    """각성 발자국: a splash crown leaps up round each print."""
    return _print("duck_print_awakened", "dk_print_a", awake=True)


def puddle_trail():
    """Card for 첨벙 발자국: two wet webbed prints walking forward, a duckling sitting in a little puddle."""
    m = MModel("puddle_trail")
    plate(m, "print", (0.5, 3.0, 0.5), (8.5, 3.08, 8.5), "dk_print_a", "y")
    plate(m, "print", (7.5, 3.12, 7.5), (15.5, 3.2, 15.5), "dk_print_b", "y")
    duckling(m, "duck", (11.4, 2.2, 3.4), 0.6, face="happy", pose="sit", detail="lite")
    crossed(m, "splash", (4.5, 0, 4.5), 5.0, 4.0, "dk_splash", y0=3.05)
    _display(m, (45, 225, 0), 0.95, 0)
    return m


# ================================================================================================
# 둥둥 고무오리 (aura: worn, orbit) and the aura's card
# ================================================================================================

FEET = 8.0 - 14.4          # the feet, under the aura anchor


def rubber_duck(m, n, c, s, th=0):
    """A floating rubber duck (glossy toy yellow) centred on c's x, z with its bottom at c's y, facing th (0 north,
    90 west, 180 south, 270 east: its bill shows which way it swims). Round: a body with a rounded back on top and a
    tail tip, a round head with its painted face and a glossy orange bill."""
    x, y, z = c
    r = math.radians(th)
    fx, fz = -math.sin(r), -math.cos(r)        # forward
    rx, rz = math.cos(r), -math.sin(r)         # right
    k = round(th / 90) % 4

    def at(f, u, side=0.0):
        return (x + fx * f * s + rx * side * s, y + u * s, z + fz * f * s + rz * side * s)

    def box(name, ctr, w, h, d, mat, **kw):
        sx, sz = (w, d) if k % 2 == 0 else (d, w)
        m.box(f"{n}_{name}", (ctr[0] - sx * s / 2, ctr[1], ctr[2] - sz * s / 2),
              (ctr[0] + sx * s / 2, ctr[1] + h * s, ctr[2] + sz * s / 2), mat, **kw)

    box("body", at(0.4, 0), 3.4, 1.9, 4.4, RUBBER)
    box("back", at(0.9, 1.9), 2.3, 0.6, 2.8, RUBBER_LIGHT, faces={"down": None})
    box("tail", at(2.7, 1.5), 1.6, 1.2, 0.9, RUBBER_LIGHT)
    face = ("north", "west", "south", "east")[k]
    box("head", at(-1.2, 1.7), 2.6, 2.5, 2.4, RUBBER, faces={face: "dk_face_rubber"}, full_uv=(face,))
    box("crown", at(-1.2, 4.2), 1.9, 0.4, 1.8, RUBBER_LIGHT, faces={"down": None})
    box("bill", at(-2.85, 2.35), 1.7, 0.6, 0.9, RUBBER_BILL)


def _aura(m, awake):
    # the ring of water at the feet (four painted quarters) and ducks bobbing round on it
    R = 10.5 if not awake else 12.0
    y = FEET + 0.15
    for q, (sx, sz) in enumerate(((1, 1), (-1, 1), (-1, -1), (1, -1))):
        x0, x1 = (8, 8 + R) if sx > 0 else (8 - R, 8)
        z0, z1 = (8, 8 + R) if sz > 0 else (8 - R, 8)
        flip_u, flip_v = sx < 0, sz < 0
        u0, u1 = (HS, 0) if flip_u else (0, HS)
        v0, v1 = (HS, 0) if flip_v else (0, HS)
        m.box("water", (x0, y, z0), (x1, y + 0.05, z1), "dk_water_0", uv={**{f: CLEAR for f in ALL_FACES}, "up": [u0, v0, u1, v1], "down": [u0, v1, u1, v0]})
    ducks = 3 if not awake else 5
    for i in range(ducks):
        a = math.radians(i * 360 / ducks + 20)
        rr = R * 0.8
        c = (8 + rr * math.cos(a), y + 0.1 + (0.4 if i % 2 else 0.0), 8 + rr * math.sin(a))
        # facing along the circle (the orbit carries them round)
        th = (-math.degrees(a)) % 360
        th = round(th / 90) * 90
        rubber_duck(m, f"duck{i}", c, 0.72 if i else 0.88, th)
    if awake:
        for (a, h) in ((60, 6.0), (200, 8.5), (310, 4.5)):
            ar = math.radians(a)
            crossed(m, "bubble", (8 + 6.5 * math.cos(ar), 0, 8 + 6.5 * math.sin(ar)), 2.0, 2.0, "dk_bubble", y0=FEET + h)


def duck_aura_ring():
    """둥둥 고무오리 (worn): a ring of rippling water round the feet with three rubber ducks bobbing on it, carried
    round by the orbit motion."""
    m = MModel("duck_aura_ring")
    _aura(m, False)
    _display(m, HEAD_GUI, 0.5, 0)
    return m


def duck_aura_ring_awakened():
    """각성 고무오리: a wider ring, five ducks and soap bubbles rising round the body."""
    m = MModel("duck_aura_ring_awakened")
    _aura(m, True)
    _display(m, HEAD_GUI, 0.45, 0)
    return m


def duck_aura_card():
    """Card for 둥둥 고무오리: the water ring with its ducks, seen from above."""
    m = MModel("duck_aura_card")
    R, y = 7.5, 4.0
    for q, (sx, sz) in enumerate(((1, 1), (-1, 1), (-1, -1), (1, -1))):
        x0, x1 = (8, 8 + R) if sx > 0 else (8 - R, 8)
        z0, z1 = (8, 8 + R) if sz > 0 else (8 - R, 8)
        u0, u1 = (HS, 0) if sx < 0 else (0, HS)
        v0, v1 = (HS, 0) if sz < 0 else (0, HS)
        m.box("water", (x0, y, z0), (x1, y + 0.05, z1), "dk_water_0", uv={**{f: CLEAR for f in ALL_FACES}, "up": [u0, v0, u1, v1], "down": [u0, v1, u1, v0]})
    for i, a in enumerate((30, 150, 270)):
        ar = math.radians(a)
        rubber_duck(m, f"duck{i}", (8 + 5.6 * math.cos(ar), y + 0.1, 8 + 5.6 * math.sin(ar)), 0.75, (round(-a / 90) * 90) % 360)
    _display(m, (55, 225, 0), 0.95, 0)
    return m


# ================================================================================================
# 꽥! 고무오리 (kill burst) and its card
# ================================================================================================

def duck_squeak_burst():
    """꽥! 고무오리 (a kill burst): a big rubber duck popping up out of a splash crown with soap bubbles round it; grows
    and rises where the victim fell, then sinks away."""
    m = MModel("duck_squeak_burst")
    crossed(m, "splash", (8.0, 0, 8.0), 11.0, 7.0, "dk_splash", y0=8.0)
    octagon(m, "puddle", 5.6, 1.4, 8.0, 8.4, HMat("water", "smooth", 2.4, anim=8))
    duckling(m, "duck", (8.0, 8.6, 8.4), 1.5, face="rubber", pose="sit", detail="lite")
    for (x, y, z, d) in ((2.6, 15.0, 6.0, 2.4), (13.6, 13.2, 9.0, 2.0), (4.4, 19.0, 10.0, 1.6), (12.2, 18.4, 5.0, 1.8)):
        crossed(m, "bubble", (x, 0, z), d, d, "dk_bubble", y0=y)
    m.display = {"head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
                 "gui": {"rotation": [20, 205, 0], "translation": [0, -4, 0], "scale": [0.6] * 3}}
    return m


def duck_squeak():
    """Card for 꽥! 고무오리: the rubber duck popping out of its splash."""
    m = duck_squeak_burst()
    m.name = "duck_squeak"
    return m


# ================================================================================================
# 세트 각성 (cosmetic_sets.yml awaken.model): lily pads turning round the feet
# ================================================================================================

def duck_set_pond():
    """세트 각성 연못: lily pads (some with a pink water lily) and a few bubbles in a wide ring round the feet, turned
    slowly round (orbit); outside the aura's water ring."""
    m = MModel("duck_set_pond")
    y = FEET + 0.08
    for i in range(8):
        a = math.radians(i * 45 + 10)
        r = 14.2 + (0.8 if i % 2 else -0.4)
        cx, cz = 8 + r * math.cos(a), 8 + r * math.sin(a)
        d = 3.4 if i % 2 else 4.2
        plate(m, "lily", (cx - d / 2, y, cz - d / 2), (cx + d / 2, y + 0.05, cz + d / 2), "dk_lily_flower" if i % 3 == 0 else "dk_lily", "y")
    for (a, h, d) in ((30, 2.5, 1.6), (150, 1.6, 1.3), (250, 3.2, 1.8)):
        ar = math.radians(a)
        crossed(m, "bubble", (8 + 14.0 * math.cos(ar), 0, 8 + 14.0 * math.sin(ar)), d, d, "dk_bubble", y0=FEET + h)
    m.display = {"head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    return m


SLOTS = {"sleepy_duckling": "above", "duckling_wings": "back", "peeking_duckling": "shoulder", "duck_bib": "neck",
         "duck_float_ring": "waist", "duckling_parade": "pet", "puddle_trail": "trail", "duck_aura_card": "aura",
         "duck_squeak": "kill",
         # models shown by the engine, not items
         "sleepy_duckling_awakened": "above", "duckling_wings_awakened": "back", "duck_float_ring_awakened": "waist",
         "duckling_parade_awakened": "pet", "duck_aura_ring": "aura", "duck_aura_ring_awakened": "aura",
         "duck_print_a": "print", "duck_print_b": "print", "duck_print_awakened": "print",
         "duck_squeak_burst": "burst", "duck_set_pond": "aura"}

ALL = {f.__name__: f for f in (sleepy_duckling, sleepy_duckling_awakened, duckling_wings, duckling_wings_awakened,
                               peeking_duckling, duck_bib, duck_float_ring, duck_float_ring_awakened,
                               duckling_parade, duckling_parade_awakened,
                               duck_print_a, duck_print_b, duck_print_awakened, puddle_trail,
                               duck_aura_ring, duck_aura_ring_awakened, duck_aura_card,
                               duck_squeak_burst, duck_squeak, duck_set_pond)}
