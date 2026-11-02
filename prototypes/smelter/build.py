"""
Smelter prototype builder.

Generates, from a single Python description of the model:
  - out/resourcepack/            vanilla resource pack (block model, item model definition, textures)
  - out/smelter.bbmodel          Blockbench project (open in Blockbench to tweak by hand)
  - out/preview_model.json       flattened model used by the preview renderer

Coordinates are Minecraft model units (16 = 1 block). Vanilla models must stay inside -16..32.
"""

import base64
import json
import shutil
import uuid
from pathlib import Path

from PIL import Image

import textures

ROOT = Path(__file__).parent
TEX_DIR = ROOT / "textures"
OUT = ROOT / "out"
NAMESPACE = "stsv"
MODEL_NAME = "smelter"

# --------------------------------------------------------------------------------------------
# Model description
# --------------------------------------------------------------------------------------------

# texture key -> file stem in textures/
TEXTURES = ["brick", "iron", "lava", "ore", "belt", "dark", "gauge"]

GLOW = {"lava"}  # elements whose faces are all this texture render at full brightness


def box(name, group, frm, to, tex, faces=None, glow=False, full_uv=()):
    """faces: optional {face: tex} override; None face value = face omitted."""
    all_faces = {f: tex for f in ("north", "south", "east", "west", "up", "down")}
    if faces:
        all_faces.update(faces)
    return {
        "name": name,
        "group": group,
        "from": list(frm),
        "to": list(to),
        "faces": {k: v for k, v in all_faces.items() if v is not None},
        "glow": glow,
        "full_uv": set(full_uv),  # faces that show the whole texture instead of a tiled slice
    }


E = []

# --- base & frame ----------------------------------------------------------------------------
E.append(box("plinth", "base", (0, 0, 0), (20, 3, 16), "iron"))
for i, (x, z) in enumerate([(0, 0), (18, 0), (0, 14), (18, 14)]):
    E.append(box(f"pillar_{i}", "base", (x, 3, z), (x + 2, 20, z + 2), "iron"))

# --- furnace body (front wall split around the fire mouth) -----------------------------------
E.append(box("body_core", "body", (2, 3, 3), (18, 20, 14), "brick"))
E.append(box("body_back", "body", (2, 3, 14), (18, 20, 15), "brick"))
E.append(box("body_west", "body", (1, 3, 2), (2, 20, 14), "brick"))
E.append(box("body_east", "body", (18, 3, 2), (19, 20, 14), "brick"))
E.append(box("front_left", "body", (2, 3, 1), (6, 20, 3), "brick"))
E.append(box("front_right", "body", (14, 3, 1), (18, 20, 3), "brick"))
E.append(box("front_bottom", "body", (6, 3, 1), (14, 5, 3), "brick"))
E.append(box("front_top", "body", (6, 11, 1), (14, 20, 3), "brick"))

# --- fire mouth ------------------------------------------------------------------------------
E.append(box("fire", "mouth", (6, 5, 2.9), (14, 11, 3), "lava", glow=True))
E.append(box("mouth_frame_bottom", "mouth", (5, 4, 0.5), (15, 5, 1), "iron"))
E.append(box("mouth_frame_top", "mouth", (5, 11, 0.5), (15, 12, 1), "iron"))
E.append(box("mouth_frame_left", "mouth", (5, 5, 0.5), (6, 11, 1), "iron"))
E.append(box("mouth_frame_right", "mouth", (14, 5, 0.5), (15, 11, 1), "iron"))
for i, x in enumerate((7.5, 9.5, 11.5)):
    E.append(box(f"grate_{i}", "mouth", (x, 5, 1.4), (x + 1, 11, 1.9), "iron"))
E.append(box("gauge", "mouth", (14.5, 13.5, 0.3), (17.5, 16.5, 1), "iron", faces={"north": "gauge"}, full_uv=["north"]))

# --- roof & chimney --------------------------------------------------------------------------
E.append(box("roof_plate", "roof", (0, 20, 0), (20, 22, 16), "iron"))
E.append(box("roof_tier", "roof", (3, 22, 3), (17, 25, 13), "brick"))
E.append(box("chimney", "roof", (11, 25, 5), (16, 32, 10), "brick", faces={"up": "dark"}))
E.append(box("chimney_band", "roof", (10.5, 28, 4.5), (16.5, 29, 10.5), "iron"))

# --- side intake (east) ----------------------------------------------------------------------
E.append(box("intake", "intake", (19, 6, 4.5), (19.2, 11, 11.5), "dark"))
E.append(box("intake_hood", "intake", (19, 11, 3.5), (21, 12, 12.5), "iron"))

# --- conveyor --------------------------------------------------------------------------------
E.append(box("belt", "conveyor", (20, 6, 4), (32, 7, 12), "iron", faces={"up": "belt"}))
E.append(box("rail_north", "conveyor", (20, 5, 3), (32, 8, 4), "iron"))
E.append(box("rail_south", "conveyor", (20, 5, 12), (32, 8, 13), "iron"))
for i, (x, z) in enumerate([(22, 3), (22, 12), (30, 3), (30, 12)]):
    E.append(box(f"leg_{i}", "conveyor", (x, 0, z), (x + 1, 5, z + 1), "iron"))
E.append(box("ore_a", "conveyor", (23, 7, 5.5), (25.5, 9.5, 8), "ore"))
E.append(box("ore_b", "conveyor", (27.5, 7, 8), (29.5, 9, 10), "ore"))

for e in E:
    for a in range(3):
        assert -16 <= e["from"][a] <= 32 and -16 <= e["to"][a] <= 32, e["name"]
        assert e["from"][a] <= e["to"][a], e["name"]


# --------------------------------------------------------------------------------------------
# UV mapping (Blockbench-style auto UV, wrapped into 0..16 so textures tile)
# --------------------------------------------------------------------------------------------


def _span(a, b):
    if b - a >= 16:
        return 0.0, 16.0
    shift = (a // 16) * 16
    a2, b2 = a - shift, b - shift
    if b2 > 16:
        a2, b2 = 16 - (b - a), 16.0
    return a2, b2


def face_uv(e, face):
    if face in e["full_uv"]:
        return [0, 0, 16, 16]
    return auto_uv(face, e["from"], e["to"])


def auto_uv(face, frm, to):
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
    else:  # down
        u, v = _span(x1, x2), _span(16 - z2, 16 - z1)
    r = lambda n: round(n, 4)
    return [r(u[0]), r(v[0]), r(u[1]), r(v[1])]


# --------------------------------------------------------------------------------------------
# Writers
# --------------------------------------------------------------------------------------------


def write_resourcepack():
    rp = OUT / "resourcepack"
    if rp.exists():
        shutil.rmtree(rp)
    tex_out = rp / "assets" / NAMESPACE / "textures" / "block" / MODEL_NAME
    model_out = rp / "assets" / NAMESPACE / "models" / "block"
    items_out = rp / "assets" / NAMESPACE / "items"
    for d in (tex_out, model_out, items_out):
        d.mkdir(parents=True)
    for t in TEXTURES:
        shutil.copy(TEX_DIR / f"{t}.png", tex_out / f"{t}.png")

    elements = []
    for e in E:
        el = {
            "__comment": e["name"],
            "from": e["from"],
            "to": e["to"],
            "faces": {
                f: {"uv": face_uv(e, f), "texture": f"#{t}"}
                for f, t in e["faces"].items()
            },
        }
        if e["glow"]:
            el["light_emission"] = 15
            el["shade"] = False
        elements.append(el)

    model = {
        "textures": {
            "particle": f"{NAMESPACE}:block/{MODEL_NAME}/brick",
            **{t: f"{NAMESPACE}:block/{MODEL_NAME}/{t}" for t in TEXTURES},
        },
        "elements": elements,
        "display": {
            # used when shown through an item_display entity (2x1 block footprint)
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            "gui": {"rotation": [30, 225, 0], "translation": [2, -2, 0], "scale": [0.42, 0.42, 0.42]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.25, 0.25, 0.25]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.3, 0.3, 0.3]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.3, 0.3, 0.3]},
        },
    }
    (model_out / f"{MODEL_NAME}.json").write_text(json.dumps(model, indent=2))

    # 1.21.4+ item model definition -> referenced by the item_model component "stsv:smelter"
    item_def = {"model": {"type": "minecraft:model", "model": f"{NAMESPACE}:block/{MODEL_NAME}"}}
    (items_out / f"{MODEL_NAME}.json").write_text(json.dumps(item_def, indent=2))

    (rp / "pack.mcmeta").write_text(json.dumps({
        "pack": {
            "description": "STSV prototype: smelter",
            # 1.21.4 (item_model) and newer; 1.21.9+ reads min/max_format.
            # Bump these to the exact 26.x numbers once the server version is fixed.
            "pack_format": 46,
            "min_format": 46,
            "max_format": 999,
        }
    }, indent=2))


def write_bbmodel():
    tex_entries = []
    for i, t in enumerate(TEXTURES):
        data = base64.b64encode((TEX_DIR / f"{t}.png").read_bytes()).decode()
        w, h = Image.open(TEX_DIR / f"{t}.png").size
        tex_entries.append({
            "path": "", "name": f"{t}.png", "folder": f"block/{MODEL_NAME}", "namespace": NAMESPACE,
            "id": str(i), "width": w, "height": h, "uv_width": 16, "uv_height": 16,
            "particle": t == "brick", "render_mode": "default", "render_sides": "auto",
            "frame_time": 1, "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False,
            "visible": True, "internal": True, "saved": False,
            "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"tex:{t}")),
            "relative_path": "", "source": f"data:image/png;base64,{data}",
        })
    tex_index = {t: i for i, t in enumerate(TEXTURES)}

    elements, groups = [], {}
    for e in E:
        uid = str(uuid.uuid5(uuid.NAMESPACE_URL, f"el:{e['name']}"))
        faces = {}
        for f in ("north", "east", "south", "west", "up", "down"):
            if f in e["faces"]:
                faces[f] = {"uv": face_uv(e, f), "texture": tex_index[e["faces"][f]]}
            else:
                faces[f] = {"uv": [0, 0, 0, 0], "texture": None}
        el = {
            "name": e["name"], "box_uv": False, "rescale": False, "locked": False,
            "render_order": "default", "allow_mirror_modeling": True,
            "from": e["from"], "to": e["to"], "autouv": 0, "color": len(groups) % 8,
            "origin": [8, 8, 8], "faces": faces, "type": "cube", "uuid": uid,
        }
        if e["glow"]:
            el["light_emission"] = 15
            el["shade"] = False
        elements.append(el)
        groups.setdefault(e["group"], []).append(uid)

    outliner = [
        {
            "name": g, "origin": [8, 8, 8], "color": 0, "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"grp:{g}")),
            "export": True, "mirror_uv": False, "isOpen": True, "locked": False, "visibility": True,
            "autouv": 0, "children": kids,
        }
        for g, kids in groups.items()
    ]

    bb = {
        "meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False},
        "name": MODEL_NAME, "parent": "", "ambientocclusion": True, "front_gui_light": False,
        "visible_box": [2, 2, 1], "variable_placeholders": "", "variable_placeholder_buttons": [],
        "unhandled_root_fields": {},
        "resolution": {"width": 16, "height": 16},
        "elements": elements, "outliner": outliner, "textures": tex_entries,
    }
    (OUT / f"{MODEL_NAME}.bbmodel").write_text(json.dumps(bb))


def write_preview_model():
    textures = {t: "data:image/png;base64," + base64.b64encode((TEX_DIR / f"{t}.png").read_bytes()).decode()
                for t in TEXTURES}
    elements = [
        {
            "from": e["from"], "to": e["to"], "glow": e["glow"],
            "faces": {f: {"uv": face_uv(e, f), "texture": t} for f, t in e["faces"].items()},
        }
        for e in E
    ]
    (OUT / "preview_model.json").write_text(json.dumps({"textures": textures, "elements": elements}))


if __name__ == "__main__":
    textures.generate_all(TEX_DIR)
    OUT.mkdir(exist_ok=True)
    write_resourcepack()
    write_bbmodel()
    write_preview_model()
    print(f"{len(E)} elements -> {OUT}")
