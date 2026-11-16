"""
Light far-away models (LOD): a .bbmodel -> a version of it with few boxes and the same outline.

Worn cosmetics are drawn face by face every frame by everyone who sees the wearer; a viewer more than a few blocks
away cannot see their small parts anyway (README "치장 거리별 단순화"). The light model:

  1. groups the parts: the two groups whose joint box wastes the least empty space are joined, again and again,
     until the boxes are few enough for the face budget (a part turned at an angle counts as the box round it);
  2. paints each new box's faces by looking at the old model from outside that face: every texel shows what a ray
     straight into the box meets first among the group's old parts (their own texels, cut-outs see-through);
     where it meets nothing the texel is see-through. So the joint box shows the old outline on each face, not a
     bigger lump: from straight on it looks like the old parts, from an angle about so;
  3. drops faces with nothing on them, and puts every new face's picture in one atlas (one texel a model unit).

Faces that glow (light_emission) make their box glow when most of what it shows glows.
"""

import base64
import io
import math
import uuid

import numpy as np
from PIL import Image

# face -> (normal axis, normal sign, u axis, u sign, v axis, v sign): the way the game lays a face's uv on it
DIR = {
    "north": (2, -1, 0, -1, 1, -1),
    "east": (0, 1, 2, -1, 1, -1),
    "south": (2, 1, 0, 1, 1, -1),
    "west": (0, -1, 2, 1, 1, -1),
    "up": (1, 1, 0, 1, 2, 1),
    "down": (1, -1, 0, 1, 2, -1),
}
FACE_OF = {(a, s): f for f, (a, s, *_) in DIR.items()}
EPS = 1e-4
# a face of a new box showing fewer texels than this share of its box's fullest face is left out
SLIVER = 0.12


# ------------------------------------------------------------------------------------------------
# reading a .bbmodel
# ------------------------------------------------------------------------------------------------

class Tex:
    """A texture of the model: its first frame and the scale from uv units to its pixels."""

    def __init__(self, t):
        src = t["source"]
        im = Image.open(io.BytesIO(base64.b64decode(src.split(",", 1)[1]))).convert("RGBA")
        uw = float(t.get("uv_width") or 16)
        uh = float(t.get("uv_height") or 16)
        self.s = im.width / uw
        frame_h = max(1, int(round(uh * self.s)))
        self.px = np.asarray(im, dtype=np.uint8)[:frame_h]     # (h, w, 4)


def _rot_matrix(axis, angle):
    a = math.radians(angle)
    c, s = math.cos(a), math.sin(a)
    if axis == 0:
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == 1:
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


class Part:
    """One cube of the old model."""

    def __init__(self, e, textures):
        inf = float(e.get("inflate", 0) or 0)
        self.lo = np.array(e["from"], dtype=float) - inf
        self.hi = np.array(e["to"], dtype=float) + inf
        self.rot = None
        rot = e.get("rotation") or [0, 0, 0]
        for a in range(3):
            if abs(rot[a]) > 1e-9:
                self.rot = (a, float(rot[a]), np.array(e.get("origin", [8, 8, 8]), dtype=float))
                break
        self.faces = {}
        for f, fd in (e.get("faces") or {}).items():
            if fd and fd.get("texture") is not None and f in DIR:
                self.faces[f] = (list(map(float, fd["uv"])), textures[int(fd["texture"])])
        self.glow = int(e.get("light_emission", 0) or 0) > 0
        self.shade = e.get("shade", True)
        if self.rot:
            a, ang, o = self.rot
            self.m = _rot_matrix(a, ang)           # local -> model (about o)
            self.mi = self.m.T
        corners = np.array([[x, y, z] for x in (self.lo[0], self.hi[0]) for y in (self.lo[1], self.hi[1])
                            for z in (self.lo[2], self.hi[2])])
        if self.rot:
            corners = (corners - self.rot[2]) @ self.m.T + self.rot[2]
        # the box round it in model space (inside the range a model may use)
        self.wlo, self.whi = np.clip(corners.min(axis=0), -16, 32), np.clip(corners.max(axis=0), -16, 32)

    def nfaces(self):
        return len(self.faces)

    def hit(self, o, d):
        """Rays (o, d: (N, 3)) -> (t, rgba (N, 4) uint8, ok (N,)): where each ray first meets a face of this part that
        faces it and shows a texel there that is not see-through."""
        if self.rot:
            c = self.rot[2]
            o = (o - c) @ self.mi.T + c
            d = d @ self.mi.T
        n = len(o)
        t0 = np.full(n, -np.inf)
        t1 = np.full(n, np.inf)
        enter_axis = np.zeros(n, dtype=int)
        enter_sign = np.zeros(n, dtype=int)
        ok = np.ones(n, dtype=bool)
        for a in range(3):
            da = d[:, a]
            par = np.abs(da) < 1e-12
            inside = (o[:, a] >= self.lo[a] - EPS) & (o[:, a] <= self.hi[a] + EPS)
            ok &= ~par | inside
            with np.errstate(divide="ignore", invalid="ignore"):
                ta = (self.lo[a] - o[:, a]) / da
                tb = (self.hi[a] - o[:, a]) / da
            near = np.where(da > 0, ta, tb)
            far = np.where(da > 0, tb, ta)
            near = np.where(par, -np.inf, near)
            far = np.where(par, np.inf, far)
            better = near > t0
            enter_axis = np.where(better, a, enter_axis)
            enter_sign = np.where(better, np.where(da > 0, -1, 1), enter_sign)   # the face it enters by points back at it
            t0 = np.maximum(t0, near)
            t1 = np.minimum(t1, far)
        ok &= (t0 <= t1 + EPS) & (t0 > 0) & np.isfinite(t0)
        rgba = np.zeros((n, 4), dtype=np.uint8)
        if not ok.any():
            return t0, rgba, ok
        p = o + d * t0[:, None]
        for f, (uv, tex) in self.faces.items():
            na, ns, ua, us, va, vs = DIR[f]
            m = ok & (enter_axis == na) & (enter_sign == ns)
            if not m.any():
                continue
            lu, lv = self.hi[ua] - self.lo[ua], self.hi[va] - self.lo[va]
            if lu <= 0 or lv <= 0:
                continue
            q = p[m]
            fu = (q[:, ua] - self.lo[ua]) / lu if us > 0 else (self.hi[ua] - q[:, ua]) / lu
            fv = (q[:, va] - self.lo[va]) / lv if vs > 0 else (self.hi[va] - q[:, va]) / lv
            fu, fv = np.clip(fu, 0, 1 - 1e-9), np.clip(fv, 0, 1 - 1e-9)
            u = uv[0] + fu * (uv[2] - uv[0])
            v = uv[1] + fv * (uv[3] - uv[1])
            h, w = tex.px.shape[:2]
            x = np.clip(np.floor(u * tex.s).astype(int), 0, w - 1)
            y = np.clip(np.floor(v * tex.s).astype(int), 0, h - 1)
            rgba[m] = tex.px[y, x]
        # faces it has not got (or a see-through texel): the ray goes on
        ok &= rgba[:, 3] >= 128
        return t0, rgba, ok


def parts_of(bb):
    textures = [Tex(t) for t in bb.get("textures", [])]
    out = []
    for e in bb["elements"]:
        if e.get("type", "cube") != "cube" or e.get("export") is False:
            continue
        p = Part(e, textures)
        if p.faces:
            out.append(p)
    return out


def faces_of(bb):
    """Faces of a .bbmodel as authored (a face with a texture)."""
    return sum(sum(1 for fd in (e.get("faces") or {}).values() if fd and fd.get("texture") is not None) for e in bb["elements"])


def seen_faces(bb):
    """About the faces the pack sends for a .bbmodel: faces with an area that are not buried inside another closed part
    turned the same way. The pack's FaceReducer also merges neighbours, so it sends a few fewer still."""
    parts = parts_of(bb)

    def key(p):
        return None if not p.rot else (p.rot[0], round(p.rot[1], 3), tuple(np.round(p.rot[2], 3)))

    solids = {}
    for p in parts:
        if len(p.faces) == 6 and (p.hi - p.lo).min() > 0.01:
            solids.setdefault(key(p), []).append(p)
    n = 0
    for p in parts:
        same = [q for q in solids.get(key(p), []) if q is not p]
        lo = np.array([q.lo for q in same]).reshape(-1, 3)
        hi = np.array([q.hi for q in same]).reshape(-1, 3)
        for f in p.faces:
            na, ns, ua, _, va, _ = DIR[f]
            if p.hi[ua] - p.lo[ua] < 1e-6 or p.hi[va] - p.lo[va] < 1e-6:
                continue
            if not same:
                n += 1
                continue
            pts = []
            for cu in (0.02, 0.5, 0.98):
                for cv in (0.02, 0.5, 0.98):
                    q = np.zeros(3)
                    q[ua] = p.lo[ua] + cu * (p.hi[ua] - p.lo[ua])
                    q[va] = p.lo[va] + cv * (p.hi[va] - p.lo[va])
                    q[na] = (p.hi[na] if ns > 0 else p.lo[na]) + ns * 0.01
                    pts.append(q)
            pts = np.array(pts)
            inside = ((pts[:, None, :] > lo[None] + 1e-4) & (pts[:, None, :] < hi[None] - 1e-4)).all(axis=2).any(axis=1)
            if not inside.all():
                n += 1
    return n


# ------------------------------------------------------------------------------------------------
# grouping
# ------------------------------------------------------------------------------------------------

def _vol(lo, hi):
    """Volume with every side at least one unit (a flat plane still takes room)."""
    return float(np.prod(np.maximum(hi - lo, 1.0)))


def _est_faces(lo, hi):
    """Faces a box will have: 6, or 2 for a flat one."""
    return 2 if (hi - lo).min() < 0.01 else 6


def group(parts, budget):
    """Joins parts into groups until the groups' boxes have at most `budget` faces. -> [(lo, hi, [parts])]"""
    gs = [[p.wlo.copy(), p.whi.copy(), [p]] for p in parts]
    total = sum(_est_faces(lo, hi) for lo, hi, _ in gs)
    while total > budget and len(gs) > 1:
        lo = np.array([g[0] for g in gs])
        hi = np.array([g[1] for g in gs])
        vol = np.prod(np.maximum(hi - lo, 1.0), axis=1)
        ulo = np.minimum(lo[:, None, :], lo[None, :, :])
        uhi = np.maximum(hi[:, None, :], hi[None, :, :])
        uvol = np.prod(np.maximum(uhi - ulo, 1.0), axis=2)
        cost = uvol - vol[:, None] - vol[None, :]
        # prefer joining small things: the waste counts against the bigger of the two less
        cost = cost / np.maximum(np.minimum(vol[:, None], vol[None, :]), 1.0) ** 0.25
        np.fill_diagonal(cost, np.inf)
        i, j = np.unravel_index(np.argmin(cost), cost.shape)
        i, j = (i, j) if i < j else (j, i)
        a, b = gs[i], gs[j]
        before = _est_faces(a[0], a[1]) + _est_faces(b[0], b[1])
        a[0], a[1], a[2] = np.minimum(a[0], b[0]), np.maximum(a[1], b[1]), a[2] + b[2]
        del gs[j]
        total += _est_faces(a[0], a[1]) - before
    return gs


# ------------------------------------------------------------------------------------------------
# painting the new boxes
# ------------------------------------------------------------------------------------------------

def _paint(lo, hi, f, parts, texel, ss=3):
    """The picture of face f of the box (lo, hi): what rays straight into it meet first. -> (rgba image, glow share)
    Each texel looks with ss x ss rays: it shows where at least two of them meet something (in their mean colour), so
    parts thinner than a texel (a ring's thin line) stay whole instead of breaking into dots, while specks scattered
    deep behind each other (an asteroid belt seen edge-on) do not fill up into a solid band."""
    na, ns, ua, us, va, vs = DIR[f]
    lu, lv = hi[ua] - lo[ua], hi[va] - lo[va]
    w, h = max(1, int(round(lu / texel))), max(1, int(round(lv / texel)))
    W, H = w * ss, h * ss
    jj, ii = np.mgrid[0:H, 0:W]
    fu, fv = (ii.ravel() + 0.5) / W, (jj.ravel() + 0.5) / H
    n = W * H
    o = np.zeros((n, 3))
    o[:, ua] = lo[ua] + fu * lu if us > 0 else hi[ua] - fu * lu
    o[:, va] = lo[va] + fv * lv if vs > 0 else hi[va] - fv * lv
    o[:, na] = (hi[na] if ns > 0 else lo[na]) + ns * 0.5
    d = np.zeros((n, 3))
    d[:, na] = -ns
    best = np.full(n, np.inf)
    rgba = np.zeros((n, 4), dtype=np.uint8)
    glow = np.zeros(n, dtype=bool)
    for p in parts:
        t, c, ok = p.hit(o, d)
        m = ok & (t < best)
        best = np.where(m, t, best)
        rgba[m] = c[m]
        glow[m] = p.glow
    hit = (rgba[:, 3] >= 128).reshape(h, ss, w, ss)
    col = rgba[:, :3].astype(float).reshape(h, ss, w, ss, 3)
    k = hit.sum(axis=(1, 3))
    rgb = (col * hit[..., None]).sum(axis=(1, 3)) / np.maximum(k, 1)[..., None]
    out = np.zeros((h, w, 4), dtype=np.uint8)
    out[..., :3] = np.clip(np.round(rgb), 0, 255).astype(np.uint8)
    out[..., 3] = np.where(k >= 2, 255, 0)
    g = glow.reshape(h, ss, w, ss)
    gk = (g & hit).sum(axis=(1, 3))
    seen = k >= 2
    share = float((gk[seen] / k[seen]).mean()) if seen.any() else 0.0
    return Image.fromarray(out, "RGBA"), share


def _gutter(im, g=1):
    """The picture with its edge pixels repeated g pixels outwards (no colour bleeds in from neighbours)."""
    return Image.fromarray(np.pad(np.asarray(im), ((g, g), (g, g), (0, 0)), mode="edge"), "RGBA")


def _pack(images, g=1):
    """Shelf-packs pictures into one atlas (width a multiple of 16, never taller than wide). -> (atlas, [(x, y)])"""
    sizes = [(im.width + 2 * g, im.height + 2 * g) for im in images]
    area = sum(w * h for w, h in sizes)
    widest = max(w for w, _ in sizes)
    W = 16
    while W < widest or W * W < area * 1.15:
        W += 16
    while True:
        order = sorted(range(len(images)), key=lambda k: -sizes[k][1])
        pos = [None] * len(images)
        x = y = shelf = 0
        for k in order:
            w, h = sizes[k]
            if x + w > W:
                x, y, shelf = 0, y + shelf, 0
            pos[k] = (x + g, y + g)
            x += w
            shelf = max(shelf, h)
        H = y + shelf
        H = ((H + 15) // 16) * 16
        if H <= W:
            break
        W += 16
    atlas = Image.new("RGBA", (W, W if W == H else H))
    for k, im in enumerate(images):
        x, y = pos[k]
        atlas.paste(_gutter(im, g), (x - g, y - g))
    return atlas, pos


def light_model(bb, budget, texel=1.0, name=None):
    """The light version of a .bbmodel (dict) with at most `budget` faces before the pack's own reduction."""
    parts = parts_of(bb)
    groups = group(parts, budget)
    boxes = []      # (lo, hi, {face: image}, glow, shade)
    for lo, hi, members in groups:
        pics = {}
        glow_votes = {}
        for f in DIR:
            na, _, ua, _, va, _ = DIR[f]
            if hi[ua] - lo[ua] < 0.01 or hi[va] - lo[va] < 0.01:
                continue                       # no area (the edge of a flat box)
            im, share = _paint(lo, hi, f, members, texel)
            if im.getchannel("A").getextrema()[1] == 0:
                continue                       # nothing seen through this face
            pics[f] = im
            glow_votes[f] = share
        # a face that shows only a sliver next to its box's main faces (a wing seen edge-on) would hang in the air
        # as a thin line on the box's far side: it goes (the old flat part was next to invisible from there too)
        seen = {f: int((np.asarray(im)[:, :, 3] > 0).sum()) for f, im in pics.items()}
        if seen:
            most = max(seen.values())
            for f in [f for f, n in seen.items() if n < SLIVER * most]:
                del pics[f]
                del glow_votes[f]
        if pics:
            shade = all(p.shade for p in members)
            boxes.append((lo, hi, pics, bool(glow_votes) and np.mean(list(glow_votes.values())) > 0.5, shade))
    images = [im for (_, _, pics, _, _) in boxes for im in pics.values()]
    atlas, pos = _pack(images)
    buf = io.BytesIO()
    atlas.save(buf, "PNG")
    name = name or (bb.get("name", "model") + "_lod")
    tex = {
        "path": "", "name": f"{name}.png", "folder": "item", "namespace": "",
        "id": "0", "width": atlas.width, "height": atlas.height, "uv_width": atlas.width, "uv_height": atlas.height,
        "particle": True, "render_mode": "default", "render_sides": "auto",
        "frame_time": 1, "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False,
        "visible": True, "internal": True, "saved": False,
        "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{name}/tex")), "relative_path": "",
        "source": "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode(),
    }
    elements, k = [], 0
    for bi, (lo, hi, pics, glow, shade) in enumerate(boxes):
        faces = {}
        for f in DIR:
            if f in pics:
                x, y = pos[k]
                im = pics[f]
                faces[f] = {"uv": [x, y, x + im.width, y + im.height], "texture": 0}
                k += 1
            else:
                faces[f] = {"uv": [0, 0, 0, 0], "texture": None}
        el = {
            "name": f"lod_{bi}", "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
            "allow_mirror_modeling": True, "from": [round(float(v), 4) for v in lo], "to": [round(float(v), 4) for v in hi],
            "autouv": 0, "color": bi % 8, "rotation": [0, 0, 0], "origin": [8, 8, 8], "faces": faces, "type": "cube",
            "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{name}/el/{bi}")),
        }
        if glow:
            el["light_emission"] = 15
            el["shade"] = False
        elif not shade:
            el["shade"] = False
        elements.append(el)
    out = {k2: v for k2, v in bb.items() if k2 not in ("elements", "outliner", "textures", "name")}
    out["name"] = name
    out["elements"] = elements
    out["textures"] = [tex]
    out["outliner"] = [{
        "name": "lod", "origin": [8, 8, 8], "color": 0, "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{name}/grp")),
        "export": True, "mirror_uv": False, "isOpen": False, "locked": False, "visibility": True, "autouv": 0,
        "children": [e["uuid"] for e in elements],
    }]
    return out
