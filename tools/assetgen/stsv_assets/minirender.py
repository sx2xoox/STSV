"""
A tiny software renderer for .bbmodel cubes, the way the game draws an item in a menu slot: orthographic,
the model's own `gui` display transform, one sample per pixel centre (no smoothing), textures point-sampled
and faces shaded by direction. No node, no GPU: build.py uses it for the voucher pictures (a small look of
each cosmetic, voucher_art.py) and the menu previews use it for the cosmetics in the list.

    im = render(load("content/stsv/models/puppy.bbmodel"))             # 16 x 16, as in a slot
    im = render(model, size=10, fit=True)                              # filling a 10 x 10 box
"""

import base64
import io
import json
import math
from pathlib import Path

from PIL import Image

FACES = ("north", "south", "east", "west", "up", "down")
# towards the light in view space (x right, y up, z to the viewer): top left, in front, like the game's GUI light
LIGHT = (-0.35, 0.75, 0.56)


class Model:
    def __init__(self, elements, textures, gui):
        self.elements, self.textures, self.gui = elements, textures, gui


def load(path):
    d = json.loads(Path(path).read_text())
    textures = []
    for t in d["textures"]:
        src = t.get("source", "")
        im = Image.open(io.BytesIO(base64.b64decode(src.split(",", 1)[1]))).convert("RGBA") if src.startswith("data:") else None
        textures.append((im, float(t.get("uv_width", t.get("width", 16))), float(t.get("uv_height", t.get("height", 16)))))
    gui = (d.get("display") or {}).get("gui") or {}
    return Model(d["elements"], textures, gui)


def _rot(v, axis, deg, o=(0.0, 0.0, 0.0)):
    if not deg:
        return v
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    x, y, z = v[0] - o[0], v[1] - o[1], v[2] - o[2]
    if axis == 0:
        y, z = y * c - z * s, y * s + z * c
    elif axis == 1:
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + o[0], y + o[1], z + o[2])


def _corner(face, f, t, s, u):
    """Point of a face at (s, u) in 0..1 along its texture's u and v (the game's face UV layout)."""
    (x0, y0, z0), (x1, y1, z1) = f, t
    return {
        "north": (x1 - s * (x1 - x0), y1 - u * (y1 - y0), z0),
        "south": (x0 + s * (x1 - x0), y1 - u * (y1 - y0), z1),
        "east": (x1, y1 - u * (y1 - y0), z1 - s * (z1 - z0)),
        "west": (x0, y1 - u * (y1 - y0), z0 + s * (z1 - z0)),
        "up": (x0 + s * (x1 - x0), y1, z0 + u * (z1 - z0)),
        "down": (x0 + s * (x1 - x0), y0, z1 - u * (z1 - z0)),
    }[face]


def _quads(model, rotation):
    """Every visible face as (origin, edge s, edge u, texture, uv, shade) in view space before scaling."""
    rx, ry, rz = rotation
    out = []
    for e in model.elements:
        f, t = tuple(e["from"]), tuple(e["to"])
        er = e.get("rotation") or [0, 0, 0]
        eo = tuple(e.get("origin") or (8, 8, 8))

        def world(p):
            for axis in (2, 1, 0):        # element rotation (one axis in practice)
                p = _rot(p, axis, er[axis], eo)
            p = (p[0] - 8, p[1] - 8, p[2] - 8)
            # display rotation, the game's rotationXYZ: z first, then y, then x
            p = _rot(p, 2, rz)
            p = _rot(p, 1, ry)
            p = _rot(p, 0, rx)
            return p

        for name, face in (e.get("faces") or {}).items():
            if face.get("texture") is None:
                continue
            o, a, b = world(_corner(name, f, t, 0, 0)), world(_corner(name, f, t, 1, 0)), world(_corner(name, f, t, 0, 1))
            es = tuple(a[i] - o[i] for i in range(3))
            eu = tuple(b[i] - o[i] for i in range(3))
            n = (es[1] * eu[2] - es[2] * eu[1], es[2] * eu[0] - es[0] * eu[2], es[0] * eu[1] - es[1] * eu[0])
            ln = math.sqrt(sum(c * c for c in n)) or 1.0
            n = tuple(c / ln for c in n)
            # the face's outward normal: from the element's centre to the face's centre
            centre = world(tuple((f[i] + t[i]) / 2 for i in range(3)))
            mid = tuple(o[i] + (es[i] + eu[i]) / 2 for i in range(3))
            if sum((mid[i] - centre[i]) * n[i] for i in range(3)) < 0:
                n = tuple(-c for c in n)
            if n[2] <= 1e-6 and abs(sum((mid[i] - centre[i]) ** 2 for i in range(3))) > 1e-9:
                continue                  # faces away from the viewer
            light = max(0.0, n[0] * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2])
            shade = 0.62 + 0.38 * light
            out.append((o, es, eu, model.textures[face["texture"]], face.get("uv") or [0, 0, 16, 16], shade))
    return out


def _mean(tex, uw, vh, uv):
    """The average colour of a face's texture region (opaque texels), or None."""
    tw, th = tex.size
    xa, xb = sorted((uv[0] / uw * tw, uv[2] / uw * tw))
    ya, yb = sorted((uv[1] / vh * th, uv[3] / vh * th))
    px = tex.load()
    acc, n = [0, 0, 0], 0
    for y in range(int(ya), max(int(ya) + 1, int(math.ceil(yb)))):
        for x in range(int(xa), max(int(xa) + 1, int(math.ceil(xb)))):
            r, g, b, a = px[min(tw - 1, x), min(th - 1, y)]
            if a >= 128:
                acc[0] += r
                acc[1] += g
                acc[2] += b
                n += 1
    return tuple(c // n for c in acc) if n else None


def render(model, size=16, fit=False, rotation=None, margin=0, flat=False):
    """The model as a size x size picture. fit=False: the game's own slot framing (display translation and
    scale, 1 model unit = size/16 px); fit=True: scaled to fill the picture (less `margin` px each side).
    flat=True: each face in its texture's average colour (no speckle: reads better a few pixels small)."""
    gui = model.gui
    rotation = tuple(rotation if rotation is not None else gui.get("rotation", (0, 0, 0)))
    quads = _quads(model, rotation)
    if fit:
        pts = []
        for o, es, eu, *_ in quads:
            for s, u in ((0, 0), (1, 0), (0, 1), (1, 1)):
                pts.append((o[0] + s * es[0] + u * eu[0], o[1] + s * es[1] + u * eu[1]))
        if not pts:
            return Image.new("RGBA", (size, size), (0, 0, 0, 0))
        x0, x1 = min(p[0] for p in pts), max(p[0] for p in pts)
        y0, y1 = min(p[1] for p in pts), max(p[1] for p in pts)
        k = (size - 2 * margin) / max(x1 - x0, y1 - y0, 1e-6)
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        to_px = lambda p: (size / 2 + (p[0] - cx) * k, size / 2 - (p[1] - cy) * k)   # noqa: E731
    else:
        sc = (gui.get("scale") or [1, 1, 1])[0] * size / 16
        tr = gui.get("translation") or [0, 0, 0]
        to_px = lambda p: (size / 2 + (p[0] * sc + tr[0] * size / 16), size / 2 - (p[1] * sc + tr[1] * size / 16))  # noqa: E731
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    depth = [[-1e9] * size for _ in range(size)]
    px = im.load()
    for o, es, eu, (tex, uw, vh), uv, shade in quads:
        if tex is None:
            continue
        p0 = to_px(o)
        ps = to_px((o[0] + es[0], o[1] + es[1]))
        pu = to_px((o[0] + eu[0], o[1] + eu[1]))
        ax, ay = ps[0] - p0[0], ps[1] - p0[1]
        bx, by = pu[0] - p0[0], pu[1] - p0[1]
        det = ax * by - ay * bx
        if abs(det) < 1e-9:
            continue
        xs = [p0[0], ps[0], pu[0], ps[0] + bx]
        ys = [p0[1], ps[1], pu[1], ps[1] + by]
        tw, th = tex.size
        tpx = tex.load()
        mean = _mean(tex, uw, vh, uv) if flat else None
        if flat and mean is None:
            continue
        for y in range(max(0, int(math.floor(min(ys)))), min(size, int(math.ceil(max(ys))) + 1)):
            for x in range(max(0, int(math.floor(min(xs)))), min(size, int(math.ceil(max(xs))) + 1)):
                qx, qy = x + 0.5 - p0[0], y + 0.5 - p0[1]
                s = (qx * by - qy * bx) / det
                u = (ax * qy - ay * qx) / det
                if not (-1e-6 <= s <= 1 + 1e-6 and -1e-6 <= u <= 1 + 1e-6):
                    continue
                z = o[2] + s * es[2] + u * eu[2]
                if z <= depth[y][x]:
                    continue
                tu = uv[0] + s * (uv[2] - uv[0])
                tv = uv[1] + u * (uv[3] - uv[1])
                tx = min(tw - 1, max(0, int(tu / uw * tw)))
                ty = min(th - 1, max(0, int(tv / vh * th)))
                r, g, b, a = tpx[tx, ty]
                if a < 128:
                    continue
                if mean:
                    r, g, b = mean
                depth[y][x] = z
                px[x, y] = (min(255, int(r * shade)), min(255, int(g * shade)), min(255, int(b * shade)), 255)
    return im


def outlined(im, colour=(16, 18, 22, 255)):
    """A 1-px dark outline around the picture (inside its own canvas)."""
    out = im.copy()
    src = im.load()
    dst = out.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            if src[x, y][3]:
                continue
            if any(0 <= x + dx < w and 0 <= y + dy < h and src[x + dx, y + dy][3]
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                dst[x, y] = colour
    return out
