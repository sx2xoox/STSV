"""
Checks that our special items animate exactly like vanilla, by comparing item definitions
structurally (same model types, properties, thresholds, scales, cases; only model names differ)
and checking every model parent chain ends in the vanilla model that defines the hand pose.

    python3 check_vanilla.py <path to extracted vanilla assets/minecraft>

The vanilla files come from the official client jar and are only read locally, never committed.
"""

import json
import sys
from pathlib import Path

import special

PAIRS = {"test_bow": "bow", "test_crossbow": "crossbow", "test_rod": "fishing_rod"}
POSE_PARENTS = {"test_bow": "minecraft:item/bow", "test_crossbow": "minecraft:item/crossbow"}


def normalise(node):
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            if k == "model" and isinstance(v, str):
                out[k] = "<model>"
            else:
                out[k] = normalise(v)
        return out
    if isinstance(node, list):
        return [normalise(v) for v in node]
    return node


def main(vanilla: Path):
    ours = special.item_definitions()
    problems = []
    for name, vname in PAIRS.items():
        v = json.loads((vanilla / "items" / f"{vname}.json").read_text())
        if normalise(ours[name]) != normalise(v):
            problems.append(f"{name}: 바닐라 {vname} 와 구조가 다름")
        else:
            print(f"OK  {name} == vanilla {vname} (속성/기준값/분기 동일)")
    # shield: vanilla uses a special renderer; the switch must still be using_item
    v = json.loads((vanilla / "items" / "shield.json").read_text())
    if v["model"]["property"] != ours["test_shield"]["model"]["property"]:
        problems.append("test_shield: 방어 전환 속성이 바닐라와 다름")
    else:
        print("OK  test_shield 방어 전환 = vanilla (minecraft:using_item)")
    # 3D hand items: display transforms must equal the vanilla parents they replace
    sys.path.insert(0, str(Path(__file__).parent))
    from models.test_items import ALL
    for name, vparent in (("test_sword", "handheld"), ("test_rod", "handheld_rod"), ("test_rod_cast", "handheld_rod")):
        chain, d = vparent, {}
        while chain:
            j = json.loads((vanilla / "models" / "item" / f"{chain.split('/')[-1]}.json").read_text())
            d = {**j.get("display", {}), **d}
            chain = j.get("parent", "").replace("minecraft:", "").replace("item/", "") if "builtin" not in j.get("parent", "builtin") else ""
        ours_d = ALL[name]().display
        full = lambda t: {"rotation": t.get("rotation", [0, 0, 0]), "translation": t.get("translation", [0, 0, 0]),
                          "scale": t.get("scale", [1, 1, 1])}  # vanilla omits default values
        diff = [k for k in d if k in ours_d and full(ours_d[k]) != full(d[k])]
        if diff:
            problems.append(f"{name}: 손 자세가 바닐라 {vparent} 와 다름: {diff}")
        else:
            print(f"OK  {name} 손 자세 = vanilla {vparent} ({len(d)}개 시점 동일)")
    # every textured model must reach the vanilla pose parent
    for name, (_, parent) in special.TEXTURED_MODELS.items():
        chain, p = [name], parent
        while p.startswith("stsv:"):
            p = special.TEXTURED_MODELS[p.split("/", 1)[1]][1]
            chain.append(p)
        base = next(b for k, b in POSE_PARENTS.items() if name.startswith(k))
        vanilla_model = vanilla / "models" / (p.split(":", 1)[1] + ".json")
        if p != base or not vanilla_model.exists():
            problems.append(f"{name}: 자세를 정하는 부모가 {base} 가 아님 ({p})")
    if not problems:
        print(f"OK  모델 {len(special.TEXTURED_MODELS)}개 모두 바닐라 자세 모델을 상속")
    for p in problems:
        print("FAIL", p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1])))
