"""
Tiny modelling DSL -> Blockbench project (.bbmodel) + preview data.

The .bbmodel is the single source of truth handed to the STSV plugin (which converts it to a vanilla model),
and it can always be opened in Blockbench for hand edits.

Coordinates: Minecraft model units, 16 = 1 block. Front of a model = north face (-Z).
"""

import base64
import io
import json
import uuid

from . import textures as T

FACES = ("north", "east", "south", "west", "up", "down")


def _span(a, b):
    if b - a >= 16:
        return 0.0, 16.0
    shift = (a // 16) * 16
    a2, b2 = a - shift, b - shift
    if b2 > 16:
        a2, b2 = 16 - (b - a), 16.0
    return a2, b2


def auto_uv(face, frm, to):
    """Blockbench-style auto UV, wrapped into 0..16 so tileable textures continue across parts."""
    x1, y1, z1 = frm
    x2, y2, z2 = to
    if face == "north":
        u, v = _span(16 - x2, 16 - x1), _span(16 - y2, 16 - y1)
    elif face == "south":
        u, v = _span(x1, x2), _span(16 - y2, 16 - y1)
    elif face == "east":
        u, v = _span(16 - z2, 16 - z1), _span(16 - y2, 16 - y1)
    elif face == "west":
        u, v = _span(z1, z2), _span(16 - y2, 16 - y1)
    elif face == "up":
        u, v = _span(x1, x2), _span(z1, z2)
    else:
        u, v = _span(x1, x2), _span(16 - z2, 16 - z1)
    return [round(u[0], 4), round(v[0], 4), round(u[1], 4), round(v[1], 4)]


class Model:
    def __init__(self, name: str):
        self.name = name
        self.elements = []
        self._group = "root"
        self.display = {}

    def group(self, name: str):
        self._group = name
        return self

    def box(self, name, frm, to, tex, faces=None, full_uv=(), glow=False, rotation=None, uv=None):
        """
        tex:      texture used on every face
        faces:    {face: texture | None}  override / remove faces (None = no face, e.g. hidden sides)
        full_uv:  faces that show the whole texture (dials, lamps, signs) instead of a tiled slice
        glow:     emits light (light_emission 15) and renders unshaded
        rotation: (axis, angle, origin)  axis in "xyz", angle in 22.5° steps between -45 and 45
        uv:       {face: [u1, v1, u2, v2]} explicit UVs
        """
        fs = {f: tex for f in FACES}
        if faces:
            fs.update(faces)
        fs = {f: t for f, t in fs.items() if t is not None}
        frm, to = [float(v) for v in frm], [float(v) for v in to]
        for a in range(3):
            assert frm[a] <= to[a], f"{name}: from > to"
            assert -16 <= frm[a] and to[a] <= 32, f"{name}: outside -16..32"
        if rotation:
            axis, angle, _ = rotation
            assert axis in "xyz" and angle in (-45, -22.5, 0, 22.5, 45), f"{name}: invalid rotation"
        self.elements.append({
            "name": name, "group": self._group, "from": frm, "to": to, "faces": fs,
            "full_uv": set(full_uv), "glow": glow, "rotation": rotation, "uv": uv or {},
        })
        return self

    def mirror_x(self, center_x: float, names_suffix="_r"):
        """Duplicates the current elements of the active group mirrored around x = center_x."""
        mirrored = []
        for e in [e for e in self.elements if e["group"] == self._group]:
            m = dict(e)
            m["name"] = e["name"] + names_suffix
            m["from"] = [2 * center_x - e["to"][0], e["from"][1], e["from"][2]]
            m["to"] = [2 * center_x - e["from"][0], e["to"][1], e["to"][2]]
            if e["faces"].get("east") or e["faces"].get("west"):
                f = dict(e["faces"])
                f["east"], f["west"] = e["faces"].get("west"), e["faces"].get("east")
                m["faces"] = {k: v for k, v in f.items() if v is not None}
            mirrored.append(m)
        self.elements.extend(mirrored)
        return self

    # ------------------------------------------------------------------------------------------

    def textures_used(self):
        seen = []
        for e in self.elements:
            for t in e["faces"].values():
                if t not in seen:
                    seen.append(t)
        return seen

    def _face_uv(self, e, f):
        if f in e["uv"]:
            return e["uv"][f]
        if f in e["full_uv"]:
            return [0, 0, 16, 16]
        return auto_uv(f, e["from"], e["to"])

    @staticmethod
    def _png(tex):
        buf = io.BytesIO()
        T.SURFACES[tex]().save(buf, "PNG")
        return buf.getvalue()

    def to_bbmodel(self) -> dict:
        tex_names = self.textures_used()
        idx = {t: i for i, t in enumerate(tex_names)}
        textures = []
        for i, t in enumerate(tex_names):
            textures.append({
                "path": "", "name": f"{t}.png", "folder": "item", "namespace": "",
                "id": str(i), "width": 16, "height": 16, "uv_width": 16, "uv_height": 16,
                "particle": i == 0, "render_mode": "default", "render_sides": "auto",
                "frame_time": 1, "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False,
                "visible": True, "internal": True, "saved": False,
                "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{self.name}/tex/{t}")),
                "relative_path": "",
                "source": "data:image/png;base64," + base64.b64encode(self._png(t)).decode(),
            })
        elements, groups = [], {}
        for e in self.elements:
            uid = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{self.name}/el/{e['name']}"))
            faces = {}
            for f in FACES:
                if f in e["faces"]:
                    faces[f] = {"uv": self._face_uv(e, f), "texture": idx[e["faces"][f]]}
                else:
                    faces[f] = {"uv": [0, 0, 0, 0], "texture": None}
            rot, origin = [0, 0, 0], [8, 8, 8]
            if e["rotation"]:
                axis, angle, origin = e["rotation"]
                rot["xyz".index(axis)] = angle
            el = {
                "name": e["name"], "box_uv": False, "rescale": False, "locked": False,
                "render_order": "default", "allow_mirror_modeling": True,
                "from": e["from"], "to": e["to"], "autouv": 0, "color": len(groups) % 8,
                "rotation": rot, "origin": list(origin), "faces": faces, "type": "cube", "uuid": uid,
            }
            if e["glow"]:
                el["light_emission"] = 15
                el["shade"] = False
            elements.append(el)
            groups.setdefault(e["group"], []).append(uid)
        outliner = [{
            "name": g, "origin": [8, 8, 8], "color": 0,
            "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{self.name}/grp/{g}")),
            "export": True, "mirror_uv": False, "isOpen": False, "locked": False, "visibility": True,
            "autouv": 0, "children": kids,
        } for g, kids in groups.items()]
        return {
            "meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False},
            "name": self.name, "parent": "", "ambientocclusion": True, "front_gui_light": False,
            "visible_box": [1, 1, 0], "variable_placeholders": "", "variable_placeholder_buttons": [],
            "unhandled_root_fields": {}, "resolution": {"width": 16, "height": 16},
            "elements": elements, "outliner": outliner, "textures": textures,
            "display": self.display,
        }

    def to_preview(self) -> dict:
        return {
            "textures": {t: "data:image/png;base64," + base64.b64encode(self._png(t)).decode()
                         for t in self.textures_used()},
            "elements": [{
                "from": e["from"], "to": e["to"], "glow": e["glow"],
                "rotation": list(e["rotation"][:2]) + [list(e["rotation"][2])] if e["rotation"] else None,
                "faces": {f: {"uv": self._face_uv(e, f), "texture": t} for f, t in e["faces"].items()},
            } for e in self.elements],
        }

    def write(self, bbmodel_path, preview_path):
        bbmodel_path.write_text(json.dumps(self.to_bbmodel()))
        preview_path.write_text(json.dumps(self.to_preview()))
