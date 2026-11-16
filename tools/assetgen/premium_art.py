"""
후원 전용 고급 규칙 (docs/ART_STYLE.md "후원 전용 고급 규칙"): the painted, finer textures of donation cosmetics.

- 32 texels a block (two a model unit), twice vanilla: HMat paints each face at that density (a 32 x 32 texture whose
  UV covers 2 texels a unit; the pack converter scales UVs by the texture's own size).
- Colour still comes from the palette ramps, extended with in-between steps: a painted value is a fractional ramp index
  and is drawn as the mix of its two neighbouring steps in quarters (ramp i, then 1/4, 1/2, 3/4 of the way to i + 1).
  So every colour is a ramp colour or one of three steps between two neighbours, never anything off the ramp.
- Painted shading on every face: light from the top-left-front (up faces lightest, down faces darkest), a gradient
  along the face (lighter at the top), soft ambient occlusion along the bottom edge where parts meet, a 1-texel rim
  light on the top and left edges and a soft dark rim on the bottom and right, and per-material detail (scales with a
  specular glint each, metal with an anisotropic highlight band, cloud with soft round lumps, horn with growth bands).
- Glowing parts can shimmer: HMat(anim=n) makes an n-frame animated texture (a band of light sweeping across).
"""

import math
import random
import zlib

from PIL import Image

from economy_machines import Mat
from stsv_assets import textures as T
from stsv_assets.palette import ramp, size

D = 2                 # texels per model unit (32 a block)
HS = 16 * D           # one HD texture's size
QUARTERS = 4          # in-between steps of a ramp interval: 0, 1/4, 1/2, 3/4


def hd_canvas():
    return Image.new("RGBA", (HS, HS), (0, 0, 0, 0))


def shade(name, v):
    """A fractional ramp index v drawn as a ramp step or one of the three steps between two neighbours (RGBA)."""
    top = size(name) - 1
    v = max(0.0, min(float(top), v))
    i = int(math.floor(v))
    f = round((v - i) * QUARTERS) / QUARTERS
    if f >= 1:
        i, f = i + 1, 0.0
    if i >= top:
        return ramp(name, top)
    a, b = ramp(name, i), ramp(name, i + 1)
    return tuple(int(round(a[k] + (b[k] - a[k]) * f)) for k in range(3)) + (255,)


# face light (light from the top-left-front): added to the base step
FACE_LIGHT = {"up": 0.55, "north": 0.15, "west": 0.25, "south": -0.15, "east": -0.25, "down": -0.85}


class HMat(Mat):
    """
    A donation-grade material: painted per face at 32 texels a block with gradients, rim light, ambient occlusion and
    the kind's detail. kind: smooth, scales, metal, cloud, horn, gem. glow: drawn at full light (emissive); anim: frames
    of a shimmer sweeping across (glowing parts only).
    """

    def __init__(self, ramp_name, kind="smooth", base=None, glow=False, anim=0, contrast=1.0, ao=True, **opt):
        super().__init__(ramp_name, kind, base, None, 0.0, glow, None, **opt)
        self.anim, self.contrast, self.ao = anim, contrast, ao

    def _value(self, x, y, pw, ph, face, rnd):
        """The painted ramp value of texel (x, y) of a face pw x ph texels (x right, y down)."""
        b = self.base + FACE_LIGHT.get(face, 0) * self.contrast
        u, v = (x + 0.5) / pw, (y + 0.5) / ph
        side = face not in ("up", "down")
        if side:
            b += (0.35 - 0.7 * v) * self.contrast                  # lighter at the top of a side
        else:
            b += (0.2 - 0.4 * (u + v) / 2) * self.contrast         # top faces: lighter to the top-left
        k = self.kind
        if k == "scales":
            # overlapping scales, rows offset by half: each a rounded plate lit at its upper-left, dark under its edge
            sw, sh = 6, 5
            row = y // sh
            cx = (x + (sw // 2 if row % 2 else 0)) % sw
            cy = y % sh
            dx, dy = (cx - sw / 2 + 0.5) / (sw / 2), (cy - 0.5) / sh
            r = dx * dx + dy * dy * 1.6
            b += 0.6 - 1.25 * min(1.0, r)
            if cy == sh - 1:
                b -= 0.8                                          # the shadow under each scale's edge
            if cx in (1, 2) and cy == 1:
                b += 1.4                                          # the glint
        elif k == "metal":
            band = math.exp(-((v - 0.32) / 0.12) ** 2)            # anisotropic highlight band
            b += 1.0 * band - 0.2
            if (x + y) % 7 == 0:
                b += 0.15
        elif k == "cloud":
            # soft round lumps, a bright crest on top of each side, a blue shadow underneath
            lump = math.cos(u * math.pi * 3.2 + 0.7) * math.cos(v * math.pi * 2.4) * 0.6
            b += lump + (0.45 if side and v < 0.3 else 0) - (0.35 if side and v > 0.75 else 0)
        elif k == "horn":
            b += 0.35 if int(v * ph / 3) % 2 == 0 else -0.1
            b += 0.4 * (1 - abs(u - 0.35) * 2)
        elif k == "gem":
            r = math.hypot(u - 0.38, v - 0.36)
            b += 1.1 - 2.2 * r
            if r < 0.08:
                b += 1.0
        # rims: a light line on the top and left edges, a soft dark one on the bottom and right
        if y == 0 and ph > 2:
            b += 0.7
        elif x == 0 and pw > 2:
            b += 0.35
        if y == ph - 1 and ph > 2:
            b -= 0.45
        elif x == pw - 1 and pw > 2:
            b -= 0.3
        # ambient occlusion: the bottom 1.5 units of a side, where it meets what is below
        if self.ao and side and ph > 4:
            near = (ph - 1 - y) / (1.5 * D)
            if near < 1:
                b -= 0.55 * (1 - near) ** 2
        return b + (rnd.random() - 0.5) * 0.18

    def _paint(self, pw, ph, face, seed, sweep=None):
        rnd = random.Random(seed)
        im = hd_canvas()
        px = im.load()
        # the whole texture is painted (texels past the face repeat its edge): no clear texel on a solid box
        for y in range(HS):
            for x in range(HS):
                v = self._value(min(x, pw - 1), min(y, ph - 1), pw, ph, face, rnd)
                if sweep is not None:
                    d = (x + y) / (pw + ph) - sweep
                    v += 1.3 * math.exp(-(d / 0.07) ** 2)
                px[x, y] = shade(self.ramp, v)
        return im

    def face(self, w, h, face, seed):
        if self.top is not None and face in ("up", "down"):
            return self.top.face(w, h, face, seed)
        pw, ph = max(1, math.ceil(w * D - 1e-6)), max(1, math.ceil(h * D - 1e-6))
        big = pw > HS or ph > HS
        if big:
            pw, ph = min(pw, HS), min(ph, HS)
        frames = [self._paint(pw, ph, face, seed)]
        if self.anim:
            frames = [self._paint(pw, ph, face, seed, sweep=k / self.anim * 1.6 - 0.3) for k in range(self.anim)]
        key = "hd_" + format(zlib.crc32(b"".join(f.tobytes() for f in frames)), "08x")
        if key not in T.SURFACES:
            first = frames[0]
            T.SURFACES[key] = (lambda img=first: img.copy())
            if self.anim:
                T.ANIMATED[key] = (lambda fs=frames: [f.copy() for f in fs], 2)
        return key, [0, 0, pw, ph] if not big else [0, 0, HS, HS]


def hd_surface(name, painter, frames=0, frame_time=2):
    """Registers a hand-painted HD texture (32 x 32) under name; painter(k) paints frame k."""
    T.SURFACES[name] = lambda: painter(0)
    if frames:
        T.ANIMATED[name] = (lambda: [painter(k) for k in range(frames)], frame_time)
