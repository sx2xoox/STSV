"""
Boss rigs: bone models built in assembly space, and posed previews of boss animations.

The boss definition (content/<pack>/bosses/*.yml) is the single source of truth for pivots and
animations. The maths here mirrors the plugin's Rig.java exactly (model space part):

    W_bone = W_parent * T(pivot - parent_pivot + pos) * Rx(rx) * Ry(ry) * Rz(rz)
    bone-local q (relative to the pivot) is shown at W_bone * q

so a pose sheet rendered here is what the game shows (minus the 180° display turn, which only
re-expresses the same picture in the display entity's frame).
"""

import math
from pathlib import Path

import yaml

from . import presets
from .model import Model

BOSSES = Path(__file__).resolve().parents[3] / "content" / "stsv" / "bosses"


def boss(file, boss_id):
    data = yaml.safe_load((BOSSES / file).read_text(encoding="utf-8"))
    return data[boss_id]


class Bone(Model):
    """A Model whose boxes are given in assembly space; stored relative to the bone pivot (8, 8, 8)."""

    def __init__(self, model_name, pivot):
        super().__init__(model_name)
        self.pivot = pivot
        self.display = presets.furniture(gui_scale=0.4)

    def at(self, name, frm, to, tex, **kw):
        px, py, pz = self.pivot
        f = (frm[0] - px + 8, frm[1] - py + 8, frm[2] - pz + 8)
        t = (to[0] - px + 8, to[1] - py + 8, to[2] - pz + 8)
        rot = kw.pop("rotation", None)
        if rot:
            axis, angle, origin = rot
            rot = (axis, angle, (origin[0] - px + 8, origin[1] - py + 8, origin[2] - pz + 8))
        return self.box(name, f, t, tex, rotation=rot, **kw)

    def sided(self, name, x, rest_from, rest_to, s, tex, **kw):
        """Box on one side: x = (inner, outer) distance from the centre line, s = +1 right / -1 left."""
        a, b = s * x[0], s * x[1]
        return self.at(name, (min(a, b),) + tuple(rest_from), (max(a, b),) + tuple(rest_to), tex, **kw)


# ------------------------------------------------------------------------------------------------
# Matrices (4x4, row-major lists)
# ------------------------------------------------------------------------------------------------

def _mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def _t(x, y, z):
    return [[1, 0, 0, x], [0, 1, 0, y], [0, 0, 1, z], [0, 0, 0, 1]]


def _rx(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return [[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]]


def _ry(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return [[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]]


def _rz(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return [[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]


def _sample(keys, t):
    """Same easing as BossSpec.Animation.sample: hold outside, smoothstep between keys."""
    if not keys:
        return None
    ks = sorted((int(k), v) for k, v in keys.items())
    if t <= ks[0][0]:
        return ks[0][1]
    if t >= ks[-1][0]:
        return ks[-1][1]
    for (t0, v0), (t1, v1) in zip(ks, ks[1:]):
        if t0 <= t <= t1:
            k = (t - t0) / (t1 - t0)
            k = k * k * (3 - 2 * k)
            return [a + (b - a) * k for a, b in zip(v0, v1)]
    return ks[-1][1]


def pose(spec, anim_name, t):
    """{bone: (rot, pos)} of an animation at tick t."""
    anim = spec["animations"][anim_name]
    out = {}
    for bone, track in (anim.get("bones") or {}).items():
        out[bone] = (_sample(track.get("rot"), t) or [0, 0, 0], _sample(track.get("pos"), t) or [0, 0, 0])
    return out


def model_space(spec, p):
    bones = spec["model"]["bones"]
    out = {}
    for name, b in bones.items():
        rot, pos = p.get(name, ([0, 0, 0], [0, 0, 0]))
        parent = b.get("parent")
        base = out[parent] if parent else _t(0, 0, 0)
        pp = bones[parent]["pivot"] if parent else [0, 0, 0]
        m = _mul(base, _t(*[b["pivot"][i] - pp[i] + pos[i] for i in range(3)]))
        m = _mul(m, _mul(_rx(rot[0]), _mul(_ry(rot[1]), _rz(rot[2]))))
        out[name] = m
    return out


def posed_elements(spec, built, p, model_of, offset=(0, 0, 0)):
    """Preview elements of every bone, each carrying its pose matrix (bone file coords -> world)."""
    ms = model_space(spec, p)
    elements, textures = [], {}
    for bone, m in ms.items():
        data = built[model_of(bone)].to_preview()
        textures.update(data["textures"])
        full = _mul(_t(*offset), _mul(m, _t(-8, -8, -8)))
        col_major = [full[r][c] for c in range(4) for r in range(4)]
        for e in data["elements"]:
            e = dict(e)
            e["matrix"] = col_major
            elements.append(e)
    return elements, textures


def pose_sheet(spec, built, frames, model_of, path, spacing=80, az=60, el=14, k=1.9):
    """frames: [(animation, tick)] rendered left to right, seen from the boss's front-right side
    (az = camera angle from the front, towards the boss's right). Frames are laid out along the
    screen's horizontal, so they never hide each other."""
    import json
    a = math.radians(az)
    right = (-math.cos(a), 0, -math.sin(a))
    elements, textures = [], {}
    for i, (anim, t) in enumerate(frames):
        off = tuple(right[k] * i * spacing for k in range(3))
        e, tx = posed_elements(spec, built, pose(spec, anim, t), model_of, offset=off)
        elements += e
        textures.update(tx)
    out = {"textures": textures, "elements": elements, "grid": False,
           "views": [{"x": 0, "y": 0, "w": 1600, "h": 1000, "az": az, "el": el, "k": k}]}
    path.write_text(json.dumps(out))
