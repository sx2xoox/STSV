"""
Light far-away models of the worn cosmetics (README "치장 거리별 단순화"): models/<name>_lod.bbmodel next to every
heavy model a wearer shows (the plugin sends it to viewers more than cosmetics.lod.near blocks away).

Which models: every model a donation piece shows while worn (its model, awakened look, parts, the aura's worn model,
the set's awakened model) and every other worn cosmetic model with more than HEAVY faces. Footprints and finishing
bursts live a second or two and keep their full model.

    python3 build.py            # builds them after the models
    python3 cosmetics_lod.py    # only these, from the models already in content/stsv/models
"""

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).parent
REPO = HERE.parent.parent
CONTENT = REPO / "content" / "stsv"
DONATION_ITEMS = "cosmetics_donation.yml"
HEAVY = 150          # faces: gacha models above this get a light model
SHARE = 0.25         # a light model: at most this share of the full model's faces (as the pack sends them) ...
CAP = 120            # ... and at most this many
FLOOR = 6            # (a single box, for the smallest parts)
# the pack's FaceReducer merges neighbouring boxes too: it sends down to about this share of lod.seen_faces
SENT = 0.78
SUFFIX = "_lod"
SKIP_SLOTS = ("trail", "kill")     # their item models show only in menus; their prints / bursts are short-lived


def _name(m):
    """'name' or 'ns:model/name' -> 'name' (this pack's model)."""
    return str(m).split(":")[-1].replace("model/", "")


def worn_models(content=CONTENT):
    """-> (every model a worn cosmetic shows, the donation ones among them)"""
    shown, donation = set(), set()
    for f in sorted((content / "items").glob("*.yml")):
        data = yaml.safe_load(f.read_text()) or {}
        for item in data.values():
            if not isinstance(item, dict) or not isinstance(item.get("cosmetic"), dict):
                continue
            c = item["cosmetic"]
            slot = c.get("slot")
            if slot in SKIP_SLOTS:
                continue
            names = []
            if slot == "aura":
                names += [c.get("worn")]
            elif isinstance(item.get("model"), str):
                names += [item["model"]]
            names += [c.get("awakened")]
            for part in c.get("parts") or []:
                if isinstance(part, dict):
                    names += [part.get("model"), part.get("awakened")]
            names = {_name(n) for n in names if n}
            shown |= names
            if f.name == DONATION_ITEMS:
                donation |= names
    sets = content / "cosmetic_sets.yml"
    if sets.is_file():
        for s in (yaml.safe_load(sets.read_text()) or {}).get("sets", {}).values():
            m = (s.get("awaken") or {}).get("model")
            if m:
                shown.add(_name(m))
                donation.add(_name(m))
    return shown, donation


def budget(seen):
    """Faces a light model may have, for a full model with `seen` faces (lod.seen_faces)."""
    return max(FLOOR, min(CAP, int(seen * SENT * SHARE)))


def wanted(content=CONTENT):
    """name -> its face budget, for every model that gets a light one."""
    from stsv_assets import lod
    shown, donation = worn_models(content)
    out = {}
    for n in sorted(shown):
        p = content / "models" / f"{n}.bbmodel"
        if not p.is_file():
            continue
        faces = lod.seen_faces(json.loads(p.read_text()))
        if n in donation or faces > HEAVY:
            out[n] = budget(faces)
    return out


def write(content=CONTENT, log=print):
    sys.path.insert(0, str(HERE))
    from stsv_assets import lod
    want = wanted(content)
    models = content / "models"
    for stale in models.glob(f"*{SUFFIX}.bbmodel"):
        if stale.stem[: -len(SUFFIX)] not in want:
            stale.unlink()
    total = [0, 0]
    for n, b in want.items():
        bb = json.loads((models / f"{n}.bbmodel").read_text())
        light = lod.light_model(bb, b, name=n + SUFFIX)
        (models / f"{n}{SUFFIX}.bbmodel").write_text(json.dumps(light))
        full, few = lod.faces_of(bb), lod.faces_of(light)
        total[0] += full
        total[1] += few
        log(f"lod     {n}: {full} -> {few} faces")
    log(f"lod     {len(want)} light models, {total[0]} -> {total[1]} faces")
    return want


if __name__ == "__main__":
    sys.path.insert(0, str(HERE))
    write()
