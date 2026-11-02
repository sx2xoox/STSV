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
        self.front_gui_light = False   # true for flat-ish held items (lit from the front in the GUI)

    def group(self, name: str):
        self._group = name
        return self

    def box(self, name, frm, to, tex, faces=None, full_uv=(), glow=False, rotation=None, uv=None, side_v=None):
        """
        tex:      texture used on every face
        faces:    {face: texture | None}  override / remove faces (None = no face, e.g. hidden sides)
        full_uv:  faces that show the whole texture (dials, lamps, signs) instead of a tiled slice
        glow:     emits light (light_emission 15) and renders unshaded
        rotation: (axis, angle, origin)  axis in "xyz", angle in 22.5° steps between -45 and 45
        uv:       {face: [u1, v1, u2, v2]} explicit UVs
        side_v:   (v1, v2) texture rows used on the four side faces (keeps e.g. a seam row off thin sides)
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
            "side_v": side_v,
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
    # Validation: catches the classic in-game artefacts before they ship
    # ------------------------------------------------------------------------------------------

    # face -> (axis, outward sign)
    _AXIS = {"west": (0, -1), "east": (0, 1), "down": (1, -1), "up": (1, 1), "north": (2, -1), "south": (2, 1)}

    def validate(self):
        """Returns a list of problems. Rotated elements are skipped (checked by eye in the preview)."""
        problems = []
        plain = [e for e in self.elements if not e["rotation"]]

        def plane(e, f):
            ax, sign = self._AXIS[f]
            return ax, sign, (e["to"][ax] if sign > 0 else e["from"][ax])

        for e in plain:
            for f in FACES:
                ax, sign, p = plane(e, f)
                a1, a2 = [a for a in range(3) if a != ax]
                # 1) a removed face must be completely covered from outside, else you can see into the box
                #    (Minecraft draws faces one-sided, so the inside of a box is invisible)
                if f not in e["faces"]:
                    step = 0.25
                    u = e["from"][a1] + step / 2
                    while u < e["to"][a1]:
                        v = e["from"][a2] + step / 2
                        while v < e["to"][a2]:
                            if not any(self._occupies_beyond(o, ax, sign, p, a1, u, a2, v) for o in plain if o is not e):
                                problems.append(f"{e['name']}: '{f}' 면이 지워졌는데 가려지지 않음 → 안쪽이 투명하게 보임")
                                break
                            v += step
                        else:
                            u += step
                            continue
                        break
                    continue
                # 2) two visible faces on the same plane, same direction, overlapping -> flicker
                for o in plain:
                    if o is e or f not in o["faces"] or id(o) < id(e):
                        continue
                    _, _, q = plane(o, f)
                    if abs(p - q) > 1e-6:
                        continue
                    ou = min(e["to"][a1], o["to"][a1]) - max(e["from"][a1], o["from"][a1])
                    ov = min(e["to"][a2], o["to"][a2]) - max(e["from"][a2], o["from"][a2])
                    if ou > 1e-6 and ov > 1e-6 and self._visible_overlap(plain, e, o, ax, sign, p, a1, a2):
                        problems.append(f"{e['name']} / {o['name']}: '{f}' 면이 같은 위치에 겹침 → 깜빡임")

        # 4) see-through pixels on a solid box show the background straight through the whole part
        for e in self.elements:
            thick = min(e["to"][a] - e["from"][a] for a in range(3))
            for f, t in e["faces"].items():
                if thick > 0.5 and T.has_transparency(t):
                    problems.append(f"{e['name']}: 투명 픽셀이 있는 텍스처 '{t}' 를 두께 있는 상자에 사용 → 뒤가 뚫려 보임 "
                                    "(투명 텍스처는 두께 0.5 이하의 판에만)")
                    break

        # 3) textures whose lines must continue across faces have to be mirror-symmetric,
        #    because auto UV mirrors columns between top and north/east faces
        for t in self.textures_used():
            if t in T.ALIGNED:
                im = T.SURFACES[t]()
                for y in range(16):
                    for x in range(8):
                        if T.line_mask(t, im, x, y) != T.line_mask(t, im, 15 - x, y):
                            problems.append(f"텍스처 '{t}' 의 이어지는 무늬가 좌우 대칭이 아님 → 면 사이에서 어긋남")
                            break
                    else:
                        continue
                    break
        return problems

    def _visible_overlap(self, plain, e, o, ax, sign, p, a1, a2, step=0.25):
        """True if part of the shared face area is not hidden by a third element."""
        u0, u1 = max(e["from"][a1], o["from"][a1]), min(e["to"][a1], o["to"][a1])
        v0, v1 = max(e["from"][a2], o["from"][a2]), min(e["to"][a2], o["to"][a2])
        u = u0 + step / 2
        while u < u1:
            v = v0 + step / 2
            while v < v1:
                if not any(self._occupies_beyond(t, ax, sign, p, a1, u, a2, v)
                           for t in plain if t is not e and t is not o):
                    return True
                v += step
            u += step
        return False

    @staticmethod
    def _occupies_beyond(o, ax, sign, p, a1, u, a2, v):
        if not (o["from"][a1] <= u <= o["to"][a1] and o["from"][a2] <= v <= o["to"][a2]):
            return False
        if sign > 0:
            return o["from"][ax] <= p < o["to"][ax]
        return o["from"][ax] < p <= o["to"][ax]

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
        uv = auto_uv(f, e["from"], e["to"])
        if e.get("side_v") and f in ("north", "south", "east", "west"):
            uv[1], uv[3] = e["side_v"]
        return uv

    @staticmethod
    def _png(tex, strip=False):
        """strip=True returns all animation frames stacked vertically (for animated textures)."""
        buf = io.BytesIO()
        if strip and tex in T.ANIMATED:
            T.animation_strip(tex).save(buf, "PNG")
        else:
            T.SURFACES[tex]().save(buf, "PNG")
        return buf.getvalue()

    def to_bbmodel(self) -> dict:
        tex_names = self.textures_used()
        idx = {t: i for i, t in enumerate(tex_names)}
        textures = []
        for i, t in enumerate(tex_names):
            frames, frame_time = (len(T.ANIMATED[t][0]()), T.ANIMATED[t][1]) if t in T.ANIMATED else (1, 1)
            textures.append({
                "path": "", "name": f"{t}.png", "folder": "item", "namespace": "",
                "id": str(i), "width": 16, "height": 16 * frames, "uv_width": 16, "uv_height": 16,
                "particle": i == 0, "render_mode": "default", "render_sides": "auto",
                "frame_time": frame_time, "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False,
                "visible": True, "internal": True, "saved": False,
                "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{self.name}/tex/{t}")),
                "relative_path": "",
                "source": "data:image/png;base64," + base64.b64encode(self._png(t, strip=True)).decode(),
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
            "name": self.name, "parent": "", "ambientocclusion": True, "front_gui_light": self.front_gui_light,
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
        problems = self.validate()
        if problems:
            raise ValueError(f"모델 '{self.name}' 검사 실패:\n  - " + "\n  - ".join(problems))
        bbmodel_path.write_text(json.dumps(self.to_bbmodel()))
        preview_path.write_text(json.dumps(self.to_preview()))
