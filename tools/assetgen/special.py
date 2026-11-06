"""
Items with special motions and wearables (TEST set). Motions are never re-invented: 2D models inherit
the vanilla parent that defines the pose (minecraft:item/bow, crossbow), 3D models copy the vanilla
display transforms, and every item definition mirrors the vanilla one property-for-property
(check_vanilla.py verifies that).

Writes raw resource-pack files to the TEST fixture pack, plugin/src/test/fixtures/stsv/assets/stsv/...
(merged as-is by the plugin; the shipped content has none of the TEST set). The shipped cheque and
voucher icons go to content/stsv/textures (write_icons).
"""

import json
from pathlib import Path

from stsv_assets import icons as I

NS = "stsv"

# test-only colour theme for the 2D icons (same shapes as the real ones, dev colours)
TEST_THEME = {"wood": "test_orange", "wood_dark": "test_orange", "steel": "test_grey", "stone": "test_grey",
              "brass": "test_teal", "fabric_red": "test_purple", "dial": "test_grey"}

# vanilla shield display transforms (how a shield is held / blocks); our geometry uses the same space
SHIELD_DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, 90, 0], "translation": [10, 6, -4], "scale": [1, 1, 1]},
    "thirdperson_lefthand": {"rotation": [0, 90, 0], "translation": [10, 6, 12], "scale": [1, 1, 1]},
    "firstperson_righthand": {"rotation": [0, 180, 5], "translation": [-10, 1.75, -10], "scale": [1.25, 1.25, 1.25]},
    "firstperson_lefthand": {"rotation": [0, 180, 5], "translation": [10, 0, -10], "scale": [1.25, 1.25, 1.25]},
    "gui": {"rotation": [15, -25, -5], "translation": [2, 3, 0], "scale": [0.65, 0.65, 0.65]},
    "fixed": {"rotation": [0, 180, 0], "translation": [-4.5, 4.5, -5], "scale": [0.55, 0.55, 0.55]},
    "ground": {"rotation": [0, 0, 0], "translation": [2, 4, 2], "scale": [0.25, 0.25, 0.25]},
}
SHIELD_BLOCKING_DISPLAY = {
    "thirdperson_righthand": {"rotation": [45, 155, 0], "translation": [-3.49, 11, -2], "scale": [1, 1, 1]},
    "thirdperson_lefthand": {"rotation": [45, 155, 0], "translation": [11.51, 7, 2.5], "scale": [1, 1, 1]},
    "firstperson_righthand": {"rotation": [0, 180, -5], "translation": [-15, 3.25, -11], "scale": [1.25, 1.25, 1.25]},
    "firstperson_lefthand": {"rotation": [0, 180, -5], "translation": [5, 5, -11], "scale": [1.25, 1.25, 1.25]},
    "gui": {"rotation": [15, -25, -5], "translation": [2, 3, 0], "scale": [0.65, 0.65, 0.65]},
}


def _model(t):
    return {"type": "minecraft:model", "model": f"{NS}:item/{t}"}


def item_definitions():
    """Our item definitions, structured exactly like their vanilla counterparts."""
    return {
        "test_bow": {"model": {
            "type": "minecraft:condition",
            "property": "minecraft:using_item",
            "on_false": _model("test_bow"),
            "on_true": {
                "type": "minecraft:range_dispatch",
                "property": "minecraft:use_duration",
                "scale": 0.05,
                "entries": [
                    {"threshold": 0.65, "model": _model("test_bow_pulling_1")},
                    {"threshold": 0.9, "model": _model("test_bow_pulling_2")},
                ],
                "fallback": _model("test_bow_pulling_0"),
            },
        }},
        "test_crossbow": {"model": {
            "type": "minecraft:select",
            "property": "minecraft:charge_type",
            "cases": [
                {"when": "arrow", "model": _model("test_crossbow_arrow")},
                {"when": "rocket", "model": _model("test_crossbow_firework")},
            ],
            "fallback": {
                "type": "minecraft:condition",
                "property": "minecraft:using_item",
                "on_false": _model("test_crossbow"),
                "on_true": {
                    "type": "minecraft:range_dispatch",
                    "property": "minecraft:crossbow/pull",
                    "entries": [
                        {"threshold": 0.58, "model": _model("test_crossbow_pulling_1")},
                        {"threshold": 1.0, "model": _model("test_crossbow_pulling_2")},
                    ],
                    "fallback": _model("test_crossbow_pulling_0"),
                },
            },
        }},
        # 3D rod and shield: the models are .bbmodels (models/test_rod*.bbmodel, test_shield*.bbmodel)
        "test_rod": {"model": {
            "type": "minecraft:condition",
            "property": "minecraft:fishing_rod/cast",
            "on_false": _model("test_rod"),
            "on_true": _model("test_rod_cast"),
        }},
        "test_shield": {"model": {
            "type": "minecraft:condition",
            "property": "minecraft:using_item",
            "on_false": _model("test_shield"),
            "on_true": _model("test_shield_blocking"),
        }},
    }


def _themed(gen):
    def run():
        with I.themed(TEST_THEME):
            return gen()
    return run


# texture name -> (generator, parent model)
TEXTURED_MODELS = {
    "test_bow": (_themed(I.hunter_bow), "minecraft:item/bow"),
    "test_bow_pulling_0": (_themed(lambda: I.hunter_bow_pulling(0)), f"{NS}:item/test_bow"),
    "test_bow_pulling_1": (_themed(lambda: I.hunter_bow_pulling(1)), f"{NS}:item/test_bow"),
    "test_bow_pulling_2": (_themed(lambda: I.hunter_bow_pulling(2)), f"{NS}:item/test_bow"),
    "test_crossbow": (_themed(lambda: I.iron_crossbow("standby")), "minecraft:item/crossbow"),
    "test_crossbow_pulling_0": (_themed(lambda: I.iron_crossbow("pull0")), f"{NS}:item/test_crossbow"),
    "test_crossbow_pulling_1": (_themed(lambda: I.iron_crossbow("pull1")), f"{NS}:item/test_crossbow"),
    "test_crossbow_pulling_2": (_themed(lambda: I.iron_crossbow("pull2")), f"{NS}:item/test_crossbow"),
    "test_crossbow_arrow": (_themed(lambda: I.iron_crossbow("arrow")), f"{NS}:item/test_crossbow"),
    "test_crossbow_firework": (_themed(lambda: I.iron_crossbow("rocket")), f"{NS}:item/test_crossbow"),
}

# plain 2D icons (wrapped automatically by the plugin: textures/<name>.png)
TEST_ICONS = {
    "test_material": _themed(I.test_ingot),
    "test_fuel": _themed(I.test_lump),
    "test_chestplate": _themed(I.steel_chestplate),
    "test_skin_book": _themed(I.skin_book),
}
# icons of the shipped content (content/stsv/textures)
ICONS = {
    "tomato_voucher": lambda: I.voucher("tomato"),
    "eggplant_voucher": lambda: I.voucher("eggplant"),
    "mushroom_voucher": lambda: I.voucher("toadstool", gem="cream"),
    # legendary cosmetics (weekly ranking, 상인 행렬): a gold band
    "crown_voucher": lambda: I.voucher("gold", gem="ruby"),
    "halo_voucher": lambda: I.voucher("gold", gem="lamp"),
    "angel_wings_voucher": lambda: I.voucher("gold", gem="feather"),
    "demon_wings_voucher": lambda: I.voucher("gold", gem="crimson"),
    "cheque": I.cheque,
}


def equipment():
    return {"layers": {"humanoid": [{"texture": f"{NS}:test"}]}}


def write_icons(content_pack: Path):
    """The shipped icons (cheque, vouchers) into content/stsv/textures."""
    (content_pack / "textures").mkdir(parents=True, exist_ok=True)
    for name, gen in ICONS.items():
        gen().save(content_pack / "textures" / f"{name}.png")
    return list(ICONS)


def write(content_pack: Path):
    """The TEST set into the test fixture pack (plugin/src/test/fixtures/stsv), never into shipped content."""
    assets = content_pack / "assets" / NS
    for sub in ("items", "models/item", "textures/item", "equipment", "textures/entity/equipment/humanoid"):
        (assets / sub).mkdir(parents=True, exist_ok=True)
    (content_pack / "textures").mkdir(parents=True, exist_ok=True)

    # remove files from earlier builds that this build no longer produces (stale definitions would ship)
    expected = {
        assets / "items": {f"{n}.json" for n in item_definitions()},
        assets / "models" / "item": {f"{n}.json" for n in TEXTURED_MODELS},
        assets / "textures" / "item": {f"{n}.png" for n in TEXTURED_MODELS},
    }
    for folder, keep in expected.items():
        for f in folder.iterdir():
            if f.is_file() and f.name not in keep:
                f.unlink()
                print(f"removed {f.relative_to(content_pack)} (no longer generated)")

    for name, definition in item_definitions().items():
        (assets / "items" / f"{name}.json").write_text(json.dumps(definition, indent=2))
    for name, (gen, parent) in TEXTURED_MODELS.items():
        gen().save(assets / "textures" / "item" / f"{name}.png")
        model = {"parent": parent, "textures": {"layer0": f"{NS}:item/{name}"}}
        (assets / "models" / "item" / f"{name}.json").write_text(json.dumps(model, indent=2))
    for name, gen in TEST_ICONS.items():
        gen().save(content_pack / "textures" / f"{name}.png")

    (assets / "equipment" / "test.json").write_text(json.dumps(equipment(), indent=2))
    _themed(I.steel_humanoid)().save(assets / "textures/entity/equipment/humanoid/test.png")
    return list(item_definitions()) + list(TEST_ICONS)
