"""
The Korean names of the vanilla items the economy content names (shop, requests, machines ...), for text the server draws
itself (the resource-pack sidebar and HUD cannot show the game's translatable names: they would show "item.minecraft.diamond").

    python3 -I tools/names/vanilla_ko.py <ko_kr.json>    # -> economy/src/main/resources/vanilla-names-ko.yml

ko_kr.json is the game's own language file (assets index of the client version, minecraft/lang/ko_kr.json). Only the ids
that appear in economy/content (and config.yml) are written.
"""
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "economy" / "src" / "main" / "resources" / "vanilla-names-ko.yml"


def main(lang_path):
    lang = json.loads(Path(lang_path).read_text(encoding="utf-8"))
    ids = set()
    for p in list((REPO / "economy" / "content").rglob("*.yml")) + [REPO / "economy" / "src" / "main" / "resources" / "config.yml"]:
        ids.update(re.findall(r"minecraft:([a-z_0-9]+)", p.read_text(encoding="utf-8")))
    lines = ["# 서버가 직접 그리는 글자(사이드바 · HUD)에 쓰는 바닐라 아이템의 한국어 이름. tools/names/vanilla_ko.py 가 만듦 (손으로 고치지 말 것)"]
    missing = []
    for i in sorted(ids):
        name = lang.get("item.minecraft." + i) or lang.get("block.minecraft." + i)
        if name is None:
            missing.append(i)
            continue
        lines.append(f'{i}: "{name}"')
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(len(lines) - 1, "names;", "no name:", missing)


if __name__ == "__main__":
    main(sys.argv[1])
