"""
Baked models: every face is painted on its own, in model (world) space, into one texture atlas per model.

The machines' per-face materials (economy_machines.Mat) give every face size its own 16 x 16 texture, which
is fine for a few big boxes but costs a file per face and cannot know where a face sits. Here the painter
sees the whole model, so a face can show what hand-painted block models show:

  * one shared pixel grid in model space (16 px a block, like vanilla): every face has the same texel size,
    and patterns (grain, planks, stone, ribs, pictures) run on across neighbouring boxes and round corners;
  * baked light: a top rim that catches the light, a dark contact line under overhangs, a little gradient
    up tall parts, and ambient occlusion in creases, where parts meet and near the floor or wall the piece
    stands against (Minecraft's own face shading still darkens the sides: up 1.0, north/south 0.8, east/west 0.6);
  * one atlas PNG per model (plus one for cut-out planes and one for animated faces), sized in multiples of
    16 so mipmaps keep all levels. Faces nobody can see (buried in other parts, the bottom on the floor) all
    share one pixel. When the plugin builds the resource pack, FaceReducer drops the buried ones, merges boxes and
    re-cuts stepped round parts into fewer boxes, texel for texel (faces = frame cost; docs/ART_STYLE.md, 렌더 예산).

All colours are palette ramp steps (palette.ladder continues a ramp one or two steps past its ends for
occlusion and rims), so the baked models stay in the pack's palette.

Usage:
    m = BModel("andon")                       # like MModel: m.group(), m.box(name, frm, to, paint, faces=...)
    m.box("post", (4, 0, 4), (5, 13, 5), Paint("wood_dark", 1, "grain", axis="y"))
    cyl(m, "pot", (8, 8), 9, 0, 5, Paint("indigo", 2))
    return m                                  # baked on write / preview

A Paint's decal(ctx) may paint a picture in world space over any faces: ctx.p is the texel centre (x, y, z),
ctx.face its face; it returns None (the paint's own pattern), (ramp, step) (lit like the rest) or
("raw", (r, g, b, a)) (exact colour, unlit; a = 0 cuts a hole: only on planes 0.5 thick or less).
"""

import math

import numpy as np
from PIL import Image

from . import textures as T
from .model import Model
from .palette import ladder, size as ramp_size

# face -> (normal, (u axis, u sign), (v axis, v sign)): the way Minecraft lays a face's UV on it
FACES = {
    "north": ((0, 0, -1), (0, -1), (1, -1)),
    "south": ((0, 0, 1), (0, 1), (1, -1)),
    "east": ((1, 0, 0), (2, -1), (1, -1)),
    "west": ((-1, 0, 0), (2, 1), (1, -1)),
    "up": ((0, 1, 0), (0, 1), (2, 1)),
    "down": ((0, -1, 0), (0, 1), (2, -1)),
}
SIDES = ("north", "south", "east", "west")
# the light comes from the front-left as seen from in front of the piece (front = north, viewer's left = +x)
LIGHT_H = (0.6, -0.8)       # (x, z) of the light, horizontally


def h3(x, y, z, s=0):
    """Deterministic hash of integer coordinates -> [0, 1)."""
    n = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791) ^ (s * 2654435761)
    n &= 0xffffffff
    n = ((n ^ (n >> 13)) * 1274126177) & 0xffffffff
    return ((n ^ (n >> 16)) & 0xffff) / 65536.0


def vnoise(x, y, z, cell, s=0):
    """Smooth value noise with lattice cells of `cell` units (stone blotches, cloud edges)."""
    fx, fy, fz = x / cell, y / cell, z / cell
    ix, iy, iz = math.floor(fx), math.floor(fy), math.floor(fz)
    tx, ty, tz = fx - ix, fy - iy, fz - iz
    tx, ty, tz = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty), tz * tz * (3 - 2 * tz)
    out = 0.0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                w = (tx if dx else 1 - tx) * (ty if dy else 1 - ty) * (tz if dz else 1 - tz)
                out += w * h3(ix + dx, iy + dy, iz + dz, s)
    return out


class Ctx:
    """What a pattern or decal knows about the texel it paints."""
    __slots__ = ("face", "n", "p", "i", "lo", "hi", "frame", "u", "v", "nu", "nv", "el")

    @property
    def side(self):
        return self.face in SIDES


class Paint:
    """
    ramp, base: palette ramp and its step for the plain surface
    kind:   plain | grain (lines along `axis`) | planks (boards along `axis`, `width` wide) | speckle | stone |
            weave | bamboo (grain plus nodes every `node` units along `axis`) | lacquer (gloss streaks) |
            paper | metal
    noise:  share of texels one step lighter or darker
    light:  False = flat (glowing paper, embers, pictures that must keep their colours): no rims, no occlusion
    ao, rim: strength of the occlusion and of the edge light (1 = default)
    grad:   (option) strength of the light gradient up tall sides (1 = default; 0 for parts shaded by their decal,
            e.g. a round body made of many boxes: see voxels)
    decal:  picture painted over the faces in world space (see the module doc)
    frames: animated paint: decal/pattern get ctx.frame 0..frames-1 (one animated atlas per model)
    """

    def __init__(self, ramp, base=None, kind="plain", axis="y", noise=0.05, light=True, ao=1.0, rim=1.0,
                 glow=False, decal=None, seed=0, frames=None, frametime=2, **opt):
        self.ramp = ramp
        self.base = base if base is not None else (ramp_size(ramp) - 1) // 2
        self.kind, self.axis, self.noise, self.light = kind, axis, noise, light
        self.ao, self.rim, self.glow, self.decal, self.seed = ao, rim, glow, decal, seed
        self.frames, self.frametime, self.opt = frames, frametime, opt

    def __call__(self, **kw):
        p = Paint.__new__(Paint)
        p.__dict__.update(self.__dict__)
        p.opt = dict(self.opt)
        for k, v in kw.items():
            if k in p.__dict__ and k != "opt":
                setattr(p, k, v)
            else:
                p.opt[k] = v
        return p

    # --- pattern: ramp step offset of a texel ---------------------------------------------------
    def pattern(self, c):
        k, s = self.kind, self.seed
        x, y, z = c.i
        if self.axis == "long":      # along the part's longest side (posts, rails, culms)
            a = max(range(3), key=lambda q: (c.hi[q] - c.lo[q], q == 1))
        else:
            a = "xyz".index(self.axis) if self.axis else 1
        across = [q for q in (0, 1, 2) if q != a]
        ii = (x, y, z)
        d = 0.0
        if k == "grain":
            # lines along the axis: each line (texel column across) its own shade, a few knots
            r = h3(ii[across[0]], ii[across[1]], 0, s + 11)
            d = -1 if r < 0.22 else 1 if r > 0.86 else 0
            if h3(x, y, z, s + 12) < 0.012:
                d = -1
        elif k == "planks":
            w = self.opt.get("width", 4)
            # boards run along `axis`; they are `width` wide across the face's other in-plane axis
            cross = self.opt.get("cross")
            if cross is None:
                nax = [q for q in (0, 1, 2) if c.n[q] != 0][0]
                cross = [q for q in (0, 1, 2) if q not in (a, nax)][0]
            else:
                cross = "xyz".index(cross)
            board = math.floor(ii[cross] / w)
            pos = ii[cross] - board * w
            d = (-1 if h3(board, 0, 0, s + 21) < 0.3 else 1 if h3(board, 0, 0, s + 22) < 0.25 else 0) * 0.6
            if pos == w - 1:
                d = -1.2
            # end joints: one per board somewhere along it
            jl = self.opt.get("joint", 0)
            if jl and (ii[a] - math.floor(h3(board, 1, 0, s + 23) * jl)) % jl == 0:
                d = -1
            r = h3(ii[across[0]], ii[across[1]], board, s + 24)
            if pos != w - 1 and r < 0.12:
                d -= 0.6
        elif k == "speckle":
            r = h3(x, y, z, s + 31)
            d = -1 if r < 0.16 else 1 if r < 0.26 else 0
        elif k == "stone":
            # granite: soft blotches (a step either way only where they are strong), a few pits and glints
            v = vnoise(x + 0.5, y + 0.5, z + 0.5, self.opt.get("cell", 2.5), s + 41)
            d = -1 if v < 0.24 else 1 if v > 0.80 else 0
            r = h3(x, y, z, s + 42)
            if r < 0.04:
                d -= 1
            elif r > 0.975:
                d += 1
        elif k == "weave":
            # cloth: a sparse staggered dot of the weave (a full checker reads as a chess board)
            u, v = c.u, c.v
            d = -1 if (u % 4 == 0 and v % 4 == 0) or (u % 4 == 2 and v % 4 == 2) else 0
        elif k == "bamboo":
            # a culm or split slat: the column towards the light lit, the far one shaded (on narrow faces),
            # a node every `node` units: a dark groove with a lit ridge above it
            if 2 <= c.nu <= 4 and c.side:
                d = 1 if c.u == 0 and c.nu > 2 else -1 if c.u == c.nu - 1 else 0
            node, off = self.opt.get("node", 6), self.opt.get("node_off", 0)
            t = (ii[a] - off) % node
            if t == 0:
                d = -1
            elif t == 1 and self.opt.get("ridge", True):
                d = 1
        elif k == "lacquer":
            u = c.p[0] + c.p[2] + c.p[1] * 0.6
            if c.side and math.floor(u) % 9 in (2,):
                d = 0.8
            if c.face == "up" and math.floor(c.p[0] - c.p[2]) % 11 == 3:
                d = 0.8
        elif k == "paper":
            r = h3(x, y, z, s + 61)
            d = -0.6 if r < 0.08 else 0
        elif k == "metal":
            r = h3(x, y, z, s + 71)
            d = -0.8 if r < 0.10 else 0.8 if r > 0.94 else 0
        if self.noise and k not in ("speckle", "stone", "bamboo"):
            r = h3(x, y, z, s + 99)
            if r < self.noise / 2:
                d -= 1
            elif r < self.noise:
                d += 1
        return d


# ================================================================================================
# The model
# ================================================================================================

class BModel(Model):
    """
    A model whose faces are baked (see the module doc). Coordinates as in MModel (dy: drawn from y 0 and moved
    down by dy, e.g. -16 for pieces three blocks tall).
    floor:  y of the floor it stands on (occlusion near the floor; its bottom faces there are never seen), or None
    planes: more surfaces the piece is placed against, e.g. ("y>", 16) a ceiling, ("z>", 16) a wall behind it
    shift:  (dx, dy, dz) the whole drawing moved by this much once painted (dy as above; dx / dz centre a piece
            in its cells without repainting it)
    scale:  > 1 for pieces bigger than a model may be (elements must stay in -16..32): it is drawn and painted at
            its real size (16 px a block), then shrunk by 1/scale around (8, 8, 8) after the shift, and the
            furniture's item display scales it back up (furniture.scale in the item file: the same texel size as
            every other piece, not a stretched one). Model point = 8 + (drawn + shift - 8) / scale.
    """

    def __init__(self, name, dy=0, floor=0, planes=(), shift=None, scale=1.0):
        super().__init__(name)
        self.dy, self.floor, self.planes = dy, floor, list(planes)
        self.shift = tuple(shift if shift is not None else (0, dy, 0))
        if shift is not None and dy:
            raise ValueError(f"{name}: give dy inside shift")
        self.scale = float(scale)
        if floor is not None:
            self.planes.append(("y<", floor))
        self._names = {}
        self._paints = []          # per element: {face: Paint}
        self._baked = False

    def box(self, name, frm, to, paint, faces=None, rotation=None, glow=False, shade=True, **_):
        k = self._names.get(name, 0)
        self._names[name] = k + 1
        if k:
            name = f"{name}#{k}"
        ps = {f: paint for f in FACES}
        ps.update(faces or {})
        ps = {f: p for f, p in ps.items() if p is not None}
        glow = glow or any(p.glow for p in ps.values())
        frm, to = self.to_model(frm), self.to_model(to)
        if rotation:
            axis, angle, o = rotation
            rotation = (axis, angle, self.to_model(o))
        super().box(name, frm, to, "__bake__", faces={f: ("__bake__" if f in ps else None) for f in FACES},
                    glow=glow, rotation=rotation, shade=shade)
        self._paints.append(ps)
        self._baked = False
        return self

    def to_model(self, p):
        """Drawn point -> model point (see shift / scale)."""
        if self.scale == 1:
            return tuple(p[i] + self.shift[i] for i in range(3))
        return tuple(round(8 + (p[i] + self.shift[i] - 8) / self.scale, 6) for i in range(3))

    def to_drawn(self, q):
        """Model point -> drawn point (where it was painted)."""
        return [round((q[i] - 8) * self.scale + 8 - self.shift[i], 6) for i in range(3)]

    # --- baking ------------------------------------------------------------------------------------
    def bake(self):
        if self._baked:
            return
        self._baked = True
        solid = []
        for e in self.elements:
            if not e["rotation"]:
                solid.append((*self.to_drawn(e["from"]), *self.to_drawn(e["to"])))
        boxes = np.array(solid, dtype=float).reshape(-1, 6)

        def occupied(pts):
            """pts (N, 3) -> bool (N,): inside a part (not just touching) or beyond a floor / wall."""
            eps = 1e-4
            inside = np.zeros(len(pts), dtype=bool)
            if len(boxes):
                px, py, pz = pts[:, 0:1], pts[:, 1:2], pts[:, 2:3]
                b = boxes.T
                inside = ((px > b[0] + eps) & (px < b[3] - eps) & (py > b[1] + eps) & (py < b[4] - eps)
                          & (pz > b[2] + eps) & (pz < b[5] - eps)).any(axis=1)
            for (kind, v) in self.planes:
                a = "xyz".index(kind[0])
                inside |= (pts[:, a] < v) if kind[1] == "<" else (pts[:, a] > v)
            return inside

        self._occupied = occupied
        regions = {"opaque": [], "cutout": [], "anim": []}
        cheap = []          # (element, face): faces nobody sees -> one shared pixel
        frames = None
        for ei, e in enumerate(self.elements):
            for f, p in self._paints[ei].items():
                lo, hi = self.to_drawn(e["from"]), self.to_drawn(e["to"])
                geo = _face_grid(f, lo, hi)
                if geo is None:
                    # a face without area (the edges of a flat plane): nothing to draw
                    del e["faces"][f]
                    continue
                if not e["rotation"] and self._hidden(f, lo, hi, geo):
                    cheap.append((ei, f, p))
                    continue
                if p.frames:
                    if frames not in (None, (p.frames, p.frametime)):
                        raise ValueError(f"{self.name}: every animated paint of a model needs the same frames")
                    frames = (p.frames, p.frametime)
                    imgs = [self._paint_face(ei, f, p, lo, hi, geo, k) for k in range(p.frames)]
                    regions["anim"].append((ei, f, geo, imgs))
                    continue
                img = self._paint_face(ei, f, p, lo, hi, geo, 0)
                thin = min(hi[q] - lo[q] for q in range(3)) <= 0.5
                if thin and img.getchannel("A").getextrema()[0] < 255:
                    regions["cutout"].append((ei, f, geo, [img]))
                else:
                    if img.getchannel("A").getextrema()[0] < 255:
                        raise ValueError(f"{self.name}: see-through pixels on a solid part ({e['name']} {f})")
                    regions["opaque"].append((ei, f, geo, [img]))
        # the shared pixel of hidden faces: each paint's own darkest plain colour
        for (ei, f, p) in cheap:
            img = Image.new("RGBA", (1, 1), ladder(p.ramp, p.base - 1))
            regions["opaque"].append((ei, f, ("cheap",), [img]))
        for kind, items in regions.items():
            if not items:
                continue
            name = f"{self.name}{'' if kind == 'opaque' else '_' + kind}"
            self._atlas(name, items, square=(kind == "anim"), frames=frames if kind == "anim" else None)

    def _hidden(self, f, lo, hi, geo):
        """True if something covers the whole face (a part right against it, or the floor under it)."""
        n = FACES[f][0]
        pts = []
        for (cu, cv) in geo["centres"]:
            pts.append(_point(f, lo, hi, cu, cv, 0.05))
        for (cu, cv) in geo["corners"]:
            pts.append(_point(f, lo, hi, cu, cv, 0.05))
        return bool(self._occupied(np.array(pts)).all())

    def _paint_face(self, ei, f, p, lo, hi, geo, frame):
        nu, nv = geo["n"]
        img = Image.new("RGBA", (nu, nv))
        px = img.load()
        n = FACES[f][0]
        # light terms for every texel at once (occlusion needs the geometry)
        centres = geo["texels"]            # list of (j, i, cu, cv)
        pts = np.array([_point(f, lo, hi, cu, cv, 0.0) for (_, _, cu, cv) in centres])
        shade = np.zeros(len(pts))
        if p.light and not p.glow:
            shade += _smooth(self._ao(pts, n), geo["n"]) * p.ao
            shade += self._rims(f, lo, hi, pts, geo, p.opt.get("vrim", 1.0)) * p.rim
            if f == "up":
                shade += 0.35
            elif f == "down":
                shade -= 0.6
            if f in SIDES and hi[1] - lo[1] >= 4:
                t = (pts[:, 1] - lo[1]) / (hi[1] - lo[1])
                shade += 0.5 * (t - 0.5) * p.opt.get("grad", 1.0)
        c = Ctx()
        c.face, c.n, c.lo, c.hi, c.frame, c.nu, c.nv, c.el = f, n, lo, hi, frame, nu, nv, ei
        for k, (j, i, cu, cv) in enumerate(centres):
            pt = pts[k]
            c.p = (float(pt[0]), float(pt[1]), float(pt[2]))
            # the texel's integer cell in model space (just inside the face)
            c.i = tuple(math.floor(c.p[q] - 0.01 * n[q]) for q in range(3))
            c.u, c.v = i, j
            ramp, step = p.ramp, p.base
            got = p.decal(c) if p.decal else None
            if got is not None and got[0] == "raw":
                px[i, j] = got[1]
                continue
            if got is not None:
                ramp, step = got
                d = 0.0
                if p.opt.get("decal_pattern"):
                    d = p.pattern(c)
            else:
                d = p.pattern(c)
            # the pattern in whole steps on top of the rounded light (so occlusion bands stay clean)
            di = math.trunc(d)
            q = di + math.floor(step + shade[k] + (d - di) + 0.5)
            base = math.floor(step + 0.5)
            q = max(base - 3, min(base + 2, q))
            px[i, j] = ladder(ramp, q)
        return img

    def _ao(self, pts, n):
        """Ambient occlusion in ramp steps (<= 0): parts and planes close above the face darken it."""
        n = np.array(n, dtype=float)
        t1 = np.array((0, 1, 0)) if abs(n[1]) < 0.5 else np.array((1, 0, 0))
        t1 = t1 - n * (t1 @ n)
        t1 /= np.linalg.norm(t1)
        t2 = np.cross(n, t1)
        dirs, weights = [], []
        for (a, cnt) in ((0.0, 1), (0.45, 6), (0.85, 9), (1.25, 12)):
            for b in range(cnt):
                ang = 2 * math.pi * (b + 0.5 * (a > 1)) / cnt
                d = n * math.cos(a) + (t1 * math.cos(ang) + t2 * math.sin(ang)) * math.sin(a)
                dirs.append(d)
                weights.append(math.cos(a) + 0.35)
        occ = np.zeros(len(pts))
        tot = 0.0
        base = pts + n * 0.06
        for d, w in zip(dirs, weights):
            hit = np.zeros(len(pts), dtype=bool)
            for dist, fall in ((0.45, 1.0), (1.0, 0.85), (1.8, 0.6), (2.8, 0.35)):
                h = self._occupied(base + d * dist)
                occ += (h & ~hit) * w * fall
                hit |= h
            tot += w
        ao = occ / tot          # 0 .. ~0.8
        # open sky: something straight above (roofs, canopies, a table top) shades what is under it
        sky = np.zeros(len(pts))
        if n[1] >= 0:
            # up faces look straight up; sides look up and out (what sits right on top of a part is no roof)
            up = np.array((0.0, 1.0, 0.0))
            out = n if n[1] < 0.5 else np.zeros(3)
            for lat in ((0, 0, 0), (0.35, 0, 0), (-0.35, 0, 0), (0, 0, 0.35), (0, 0, -0.35)):
                d = up + out * 0.8 + np.array(lat)
                d = d / np.linalg.norm(d)
                hit = np.zeros(len(pts), dtype=bool)
                for dist in (3.5, 5, 7, 10, 14, 20):
                    hit |= self._occupied(base + d * dist)
                sky += hit
            sky /= 5
        # shallow recesses stay clean; creases and contacts darken one step, deep corners two
        return -2.0 * np.clip((ao - 0.18) / 0.55, 0, 1) - 0.7 * sky

    def _rims(self, f, lo, hi, pts, geo, vrim=1.0):
        """Edge light: the top edge of a side catches the light, the free rim of a top face too (stronger on
        the side the light comes from); the free bottom edge of a side is a dark contact line."""
        out = np.zeros(len(pts))
        n = np.array(FACES[f][0], dtype=float)
        tex = geo["texels"]
        if f in SIDES:
            top = np.array([cv == geo["vmin"] for (_, _, _, cv) in tex])      # rows at the top of the face
            bot = np.array([cv == geo["vmax"] for (_, _, _, cv) in tex])
            if hi[1] - lo[1] >= 1.5:
                q = pts.copy()
                q[:, 1] = hi[1] + 0.3
                q -= n * 0.3
                out += np.where(top & ~self._occupied(q), 1.0, 0.0)
                q = pts.copy()
                q[:, 1] = lo[1] - 0.3
                q -= n * 0.3
                out += np.where(bot & ~self._occupied(q), -0.7, 0.0)
            # the vertical edges: lighter on the side towards the light, darker away from it
            ua, us = FACES[f][1]
            if hi[ua] - lo[ua] >= 2.5 and vrim:
                lx = np.array((LIGHT_H[0], 0, LIGHT_H[1]))
                udir = np.zeros(3)
                udir[ua] = us
                first = np.array([cu == geo["umin"] for (_, _, cu, _) in tex])
                last = np.array([cu == geo["umax"] for (_, _, cu, _) in tex])
                for mask, sgn in ((first, -1), (last, 1)):
                    outward = udir * sgn
                    q = pts + outward * 0.3 - n * 0.3
                    free = ~self._occupied(q)
                    out += np.where(mask & free, (0.45 if outward @ lx > 0 else -0.45) * vrim, 0.0)
        elif f == "up" and min(hi[0] - lo[0], hi[2] - lo[2]) >= 1.5:
            for (axis, sgn) in ((0, -1), (0, 1), (2, -1), (2, 1)):
                key = ("umin" if sgn < 0 else "umax") if axis == 0 else ("vmin" if sgn < 0 else "vmax")
                idx = 2 if axis == 0 else 3
                mask = np.array([t[idx] == geo[key] for t in tex])
                outward = np.zeros(3)
                outward[axis] = sgn
                q = pts + outward * 0.3 - n * 0.3
                free = ~self._occupied(q)
                lit = outward[0] * LIGHT_H[0] + outward[2] * LIGHT_H[1] > 0
                out += np.where(mask & free, 1.0 if lit else 0.5, 0.0)
        return np.minimum(out, 1.0)

    def _atlas(self, name, items, square=False, frames=None):
        """Packs the face pictures (1 px gutter each, equal pictures once) and points the faces at them."""
        G = 1
        uniq = {}
        order = []
        for it in items:
            key = b"".join(im.tobytes() for im in it[3]) + bytes(str(it[3][0].size), "ascii")
            if key not in uniq:
                uniq[key] = len(order)
                order.append(it[3])
        sizes = [(ims[0].width + 2 * G, ims[0].height + 2 * G) for ims in order]
        area = sum(w * h for w, h in sizes)
        widest = max(w for w, _ in sizes)
        best = None
        for W in range(16, 257, 16):
            if W < widest:
                continue
            pos = _shelf(sizes, W)
            H = max(y + h for (x, y), (w, h) in zip(pos, sizes))
            Hr = max(16, math.ceil(H / 16) * 16)
            if square:
                side = max(W, Hr)
                if Hr > W:
                    continue
                cand = (side * side, side, side, pos)
            else:
                if Hr > W:
                    continue
                cand = (W * Hr, W, Hr, pos)
            if best is None or cand[0] < best[0]:
                best = cand
            if W * W > 4 * area and best:
                break
        _, W, H, pos = best
        n_frames = frames[0] if frames else 1
        sheets = [Image.new("RGBA", (W, H), (0, 0, 0, 0)) for _ in range(n_frames)]
        for (x, y), ims in zip(pos, order):
            for k, sheet in enumerate(sheets):
                im = ims[k if len(ims) > 1 else 0]
                sheet.paste(_gutter(im, G), (x, y))
        if not name.endswith("_cutout"):
            # opaque atlases: fill the spare space so nothing there is see-through (mipmaps, validation)
            fill = sheets[0].getpixel((pos[0][0] + G, pos[0][1] + G))
            for sheet in sheets:
                bg = Image.new("RGBA", sheet.size, fill)
                bg.alpha_composite(sheet)
                sheet.paste(bg)
        if frames:
            T.SURFACES[name] = (lambda s=sheets[0]: s.copy())
            T.ANIMATED[name] = (lambda ss=sheets: [s.copy() for s in ss], frames[1])
        else:
            T.SURFACES[name] = (lambda s=sheets[0]: s.copy())
        T._SIZE_CACHE.pop(name, None)
        T._ALPHA_CACHE.pop(name, None)
        for it in items:
            ei, f, geo, ims = it
            key = b"".join(im.tobytes() for im in ims) + bytes(str(ims[0].size), "ascii")
            x, y = pos[uniq[key]]
            e = self.elements[ei]
            e["faces"][f] = name
            if isinstance(geo, tuple):
                uv = [x + G + 0.25, y + G + 0.25, x + G + 0.75, y + G + 0.75]
            else:
                (u0, u1), (v0, v1) = geo["uv"]
                uv = [x + G + u0, y + G + v0, x + G + u1, y + G + v1]
            e["uv"][f] = [round(c, 4) for c in uv]

    # --- Model hooks -----------------------------------------------------------------------------
    def validate(self):
        self.bake()
        return super().validate()

    def to_bbmodel(self):
        self.bake()
        return super().to_bbmodel()

    def to_preview(self):
        self.bake()
        return super().to_preview()

    def textures_used(self):
        self.bake()
        return super().textures_used()


def _face_grid(f, lo, hi):
    """The face's texels on the model-space pixel grid: their centres (u, v in face units from its UV origin),
    the UV span inside the face's picture, and the picture size."""
    _, (ua, us), (va, vs) = FACES[f]

    def axis(a, s):
        s0, s1 = sorted((s * lo[a], s * hi[a]))
        if s1 - s0 <= 1e-6:
            return None
        k0 = math.floor(s0 + 1e-6)
        n = max(1, math.ceil(s1 - 1e-6) - k0)
        cells = []
        for k in range(n):
            a0, a1 = max(s0, k0 + k), min(s1, k0 + k + 1)
            cells.append((k, (a0 + a1) / 2 - s0))
        return n, cells, (s0 - k0, s1 - k0), s1 - s0

    U, V = axis(ua, us), axis(va, vs)
    if U is None or V is None:
        return None
    texels = [(j, i, cu, cv) for (j, cv) in V[1] for (i, cu) in U[1]]
    us_, vs_ = [c for _, c in U[1]], [c for _, c in V[1]]
    corners = [(0.02, 0.02), (U[3] - 0.02, 0.02), (0.02, V[3] - 0.02), (U[3] - 0.02, V[3] - 0.02)]
    return {"n": (U[0], V[0]), "texels": texels, "uv": (U[2], V[2]), "centres": [(cu, cv) for (_, _, cu, cv) in texels],
            "corners": corners, "umin": min(us_), "umax": max(us_), "vmin": min(vs_), "vmax": max(vs_)}


def _point(f, lo, hi, cu, cv, out):
    """Model-space point of face f at (cu, cv) face units from its UV origin, `out` units in front of it."""
    n, (ua, us), (va, vs) = FACES[f]
    p = [0.0, 0.0, 0.0]
    for a in range(3):
        if n[a]:
            p[a] = (hi[a] if n[a] > 0 else lo[a]) + n[a] * out
    p[ua] = (lo[ua] + cu) if us > 0 else (hi[ua] - cu)
    p[va] = (lo[va] + cv) if vs > 0 else (hi[va] - cv)
    return p


def _smooth(v, n):
    """A face's per-texel values (row by row, n = (columns, rows)) blurred with their neighbours, so occlusion
    turns into clean bands instead of speckles once it is rounded to ramp steps."""
    nu, nv = n
    a = v.reshape(nv, nu)
    p = np.pad(a, 1, mode="edge")
    b = (p[1:-1, 1:-1] * 4 + p[:-2, 1:-1] + p[2:, 1:-1] + p[1:-1, :-2] + p[1:-1, 2:]) / 8
    return b.reshape(-1)


def _gutter(im, g):
    """The picture with its edge pixels repeated g px all round (no bleeding from neighbours in the atlas)."""
    w, h = im.size
    out = Image.new("RGBA", (w + 2 * g, h + 2 * g))
    out.paste(im, (g, g))
    for k in range(g):
        out.paste(im.crop((0, 0, w, 1)), (g, k))
        out.paste(im.crop((0, h - 1, w, h)), (g, h + g + k))
    for k in range(g):
        out.paste(out.crop((g, 0, g + 1, h + 2 * g)), (k, 0))
        out.paste(out.crop((w + g - 1, 0, w + g, h + 2 * g)), (w + g + k, 0))
    return out


def _shelf(sizes, W):
    """Shelf packing, tallest first; returns the top-left corner of every size (in input order)."""
    order = sorted(range(len(sizes)), key=lambda k: (-sizes[k][1], -sizes[k][0], k))
    pos = [None] * len(sizes)
    x = y = shelf_h = 0
    for k in order:
        w, h = sizes[k]
        if x + w > W:
            y += shelf_h
            x = shelf_h = 0
        pos[k] = (x, y)
        x += w
        shelf_h = max(shelf_h, h)
    return pos


# ================================================================================================
# Shapes
# ================================================================================================

def bands(d):
    """Rows of a pixel circle of diameter d: [(offset0, offset1, half width)] from the centre, equal rows merged."""
    r = d / 2
    rows = []
    for i in range(int(round(d))):
        v = (i + 0.5) - r
        w = 2 * math.sqrt(max(0.0, r * r - v * v))
        w = round((w - d % 2) / 2) * 2 + d % 2
        rows.append(max(1, w) / 2)
    out = []
    for i, hw in enumerate(rows):
        a, b = i - r, i + 1 - r
        if out and out[-1][2] == hw:
            out[-1] = (out[-1][0], b, hw)
        else:
            out.append((a, b, hw))
    return out


def cyl(m, name, c, d, lo, hi, paint, axis="y", faces=None, clip=None):
    """A cylinder of diameter d around the y axis at c = (x, z) (axis x: c = (y, z), axis z: c = (x, y)), from lo
    to hi along its axis, made of stepped boxes. Paints are in world space, so a picture on its round ends (a
    drum skin, a dish) is simply a decal on those faces."""
    for (a, b, hw) in bands(d):
        if clip:
            a, b = max(a, clip[0] - c[1]), min(b, clip[1] - c[1])
            if b - a <= 1e-6:
                continue
        if axis == "y":
            frm, to = (c[0] - hw, lo, c[1] + a), (c[0] + hw, hi, c[1] + b)
        elif axis == "z":
            frm, to = (c[0] - hw, c[1] + a, lo), (c[0] + hw, c[1] + b, hi)
        else:
            frm, to = (lo, c[0] + a, c[1] - hw), (hi, c[0] + b, c[1] + hw)
        # no light on the vertical edges of the steps: a round thing should not look fluted
        m.box(name, frm, to, paint(vrim=0), faces=faces)


def ring(m, name, c, d_out, d_in, lo, hi, paint, faces=None):
    """A flat ring around the y axis on the pixel grid, merged into few boxes."""
    n = int(round(d_out))
    x0, z0 = c[0] - d_out / 2, c[1] - d_out / 2
    cells = [[d_in / 2 <= math.hypot(x0 + i + 0.5 - c[0], z0 + j + 0.5 - c[1]) < d_out / 2 for i in range(n)] for j in range(n)]
    used = [[False] * n for _ in range(n)]
    for j in range(n):
        i = 0
        while i < n:
            if not cells[j][i] or used[j][i]:
                i += 1
                continue
            i1 = i
            while i1 < n and cells[j][i1] and not used[j][i1]:
                i1 += 1
            j1 = j + 1
            while j1 < n and all(cells[j1][k] and not used[j1][k] for k in range(i, i1)) \
                    and (i == 0 or not cells[j1][i - 1] or used[j1][i - 1]) and (i1 == n or not cells[j1][i1]):
                j1 += 1
            for jj in range(j, j1):
                for k in range(i, i1):
                    used[jj][k] = True
            m.box(name, (x0 + i, lo, z0 + j), (x0 + i1, hi, z0 + j1), paint, faces=faces)
            i = i1


def voxels(m, name, cells, paint):
    """A sculpted shape from 1 px cells: cells maps (x, y, z) (the cell x..x+1 etc.) to a label; cells with the same
    label are merged into as few boxes as possible (greedy: along x, then z, then y). A part made this way can be
    round in every direction (a doll's body, a cat's head); its decal paints it by position (ctx.i is the cell
    under the texel, so it can look the label up), paint(label) gives each label's paint."""
    for label in sorted(set(cells.values()), key=str):
        todo = {p for p, v in cells.items() if v == label}
        for p in sorted(todo, key=lambda q: (q[1], q[2], q[0])):
            if p not in todo:
                continue
            x, y, z = p
            x1 = x + 1
            while (x1, y, z) in todo:
                x1 += 1
            z1 = z + 1
            while all((i, y, z1) in todo for i in range(x, x1)):
                z1 += 1
            y1 = y + 1
            while all((i, y1, k) in todo for i in range(x, x1) for k in range(z, z1)):
                y1 += 1
            for i in range(x, x1):
                for j in range(y, y1):
                    for k in range(z, z1):
                        todo.discard((i, j, k))
            m.box(name, (x, y, z), (x1, y1, z1), paint(label))
