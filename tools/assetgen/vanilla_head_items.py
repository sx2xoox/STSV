"""
Extracts the vanilla item model definitions of every item that can be worn on the head, for the
cosmetic self-view wrappers (CosmeticViews): their GUI look must stay exactly vanilla (trims, dye).

    python3 vanilla_head_items.py <client.jar of the server's Minecraft version>

Writes plugin/src/main/resources/pack/vanilla_head_items.json. Only the small JSON definitions are
stored (no textures or models); re-run this when the Minecraft version changes.
"""

import json
import sys
import zipfile
from pathlib import Path

HEAD_ITEMS = [
    "leather_helmet", "chainmail_helmet", "copper_helmet", "iron_helmet", "golden_helmet",
    "diamond_helmet", "netherite_helmet", "turtle_helmet", "carved_pumpkin",
    "skeleton_skull", "wither_skeleton_skull", "zombie_head", "creeper_head", "dragon_head",
    "piglin_head", "player_head",
]


def main(jar: Path):
    out = {}
    with zipfile.ZipFile(jar) as z:
        version = json.loads(z.read("version.json"))["id"]
        for name in HEAD_ITEMS:
            out["minecraft:" + name] = json.loads(z.read(f"assets/minecraft/items/{name}.json"))
    target = Path(__file__).resolve().parents[2] / "plugin/src/main/resources/pack/vanilla_head_items.json"
    target.write_text(json.dumps({"version": version, "items": out}, indent=1, ensure_ascii=False) + "\n")
    print(f"{len(out)} definitions ({version}) -> {target}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
