"""
Custom crops as real blocks: which tripwire state shows which crop stage (plugin: dev.stsv.core.crop.CropBlocks).

    python3 crop_blocks.py              # append new crops / stages to economy/content/economy/crop-blocks.yml
    python3 crop_blocks.py --preview    # docs/previews/crop_blocks.png (after `cd economy && ./gradlew test`)

The file is append-only: an entry is never moved, changed or reused, so a planted crop keeps its look across releases
(economy CropBlocksTest checks the released entries). New stages take the next free state the same way the plugin does
for stages missing from the file: early stages (first half) a thin-outline "attached" state, later ones a tall-outline
state, the other kind when one runs out. States 0 and 64 stay real string. Fails when all 126 states are used.

The preview renders a few crops, every stage, as the item display they were (entity light) and as the block the plugin
now places (block light, darkened face copies), from the resource pack economy's PackTest writes to
economy/build/crop-blocks-pack.zip, with the same lighting maths as the 26.3 client (see CropBlockModels.java).
"""

import sys
from pathlib import Path

import yaml

HERE = Path(__file__).parent
REPO = HERE.parent.parent
PACK = REPO / "economy" / "content" / "economy"
FILE = "crop-blocks.yml"

STATES = 128
ATTACHED, DISARMED, EAST, NORTH, POWERED, SOUTH, WEST = 64, 32, 16, 8, 4, 2, 1
RESERVED = (0, ATTACHED)
NAMES = [("attached", ATTACHED), ("disarmed", DISARMED), ("east", EAST), ("north", NORTH), ("powered", POWERED),
         ("south", SOUTH), ("west", WEST)]


def pool(thin):
    return [c for c in range(STATES) if bool(c & ATTACHED) == thin and c not in RESERVED]


def early(stage, stages):
    return stage < stages // 2


def describe(code):
    on = [n for n, b in NAMES if code & b]
    return ",".join(on) if on else "-"


def crops(pack):
    """(crop id, stages) in the order the plugin reads them: crops/*.yml by file name, crops in file order."""
    meta = pack / "pack.yml"
    ns = (yaml.safe_load(meta.read_text(encoding="utf-8")) or {}).get("namespace", pack.name) if meta.exists() else pack.name
    out = {}
    for f in sorted((pack / "crops").glob("*.yml")):
        for key, spec in (yaml.safe_load(f.read_text(encoding="utf-8")) or {}).items():
            if isinstance(spec, dict):
                out[f"{ns}:{key}"] = int(spec.get("stages", 4))
    return list(out.items())


def read(path):
    if not path.exists():
        return {}
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return dict(data.get("states") or {})


def allocate(existing, crop_list):
    """existing entries kept as they are, then every missing stage from the pools; raises when states run out."""
    states = dict(existing)
    used = set(states.values())
    added = []
    for crop, stages in crop_list:
        for st in range(stages):
            key = f"{crop}/{st}"
            if key in states:
                continue
            free = [c for t in (early(st, stages), not early(st, stages)) for c in pool(t) if c not in used]
            if not free:
                raise SystemExit(f"작물 블록 상태가 모자랍니다: {key} (트립와이어 상태 {STATES - len(RESERVED)}개를 모두 씀)")
            states[key] = free[0]
            used.add(free[0])
            added.append(key)
    return states, added


def write(path, states):
    lines = ["# 커스텀 작물 블록: 작물 단계마다 보여 줄 트립와이어(실) 블록 상태. 자동 생성 (tools/assetgen/crop_blocks.py).",
             "# 덧붙이기만 합니다: 이미 있는 줄은 고치거나 옮기거나 지우지 마세요 (심어 둔 작물의 모습이 바뀜).",
             "# 새 작물·단계는 맨 아래에 붙습니다. 값 = 상태 번호: attached 64, disarmed 32, east 16, north 8, powered 4, south 2, west 1",
             "# (attached = 얇은 선택 테두리, 이른 단계에 씀). 0 과 64 는 진짜 실 모양으로 남겨 둠.",
             "states:"]
    for key, code in states.items():
        lines.append(f'  "{key}": {code}   # {describe(code)}')
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def build(pack=PACK):
    path = pack / FILE
    existing = read(path)
    states, added = allocate(existing, crops(pack))
    if added or not path.exists():
        write(path, states)
    return len(states), added


if __name__ == "__main__":
    if "--preview" in sys.argv:
        sys.path.insert(0, str(HERE))
        import crop_blocks_preview
        crop_blocks_preview.main()
    else:
        n, added = build()
        print(f"crop blocks  {n} states" + (f", added {len(added)}: {', '.join(added)}" if added else ""))
