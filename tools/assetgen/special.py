"""
Items with special motions (bow pull, crossbow charge, rod cast, shield block) and wearable armor.

Strategy: never re-invent the motion. Each model inherits the vanilla parent that defines how the
item is held and animated (minecraft:item/bow, crossbow, handheld_rod), and each item definition
mirrors the vanilla one property-for-property (using_item, use_duration, crossbow/pull, charge_type,
fishing_rod/cast). Only our textures/geometry differ. check_vanilla.py verifies the mirroring.

Writes raw resource-pack files to content/stsv/assets/stsv/... (merged as-is by the plugin) and the
shield geometry to content/stsv/models/*.bbmodel.
"""

import json
from pathlib import Path

from stsv_assets import icons as I
from stsv_assets.model import Model

NS = "stsv"

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
        "hunter_bow": {"model": {
            "type": "minecraft:condition",
            "property": "minecraft:using_item",
            "on_false": _model("hunter_bow"),
            "on_true": {
                "type": "minecraft:range_dispatch",
                "property": "minecraft:use_duration",
                "scale": 0.05,
                "entries": [
                    {"threshold": 0.65, "model": _model("hunter_bow_pulling_1")},
                    {"threshold": 0.9, "model": _model("hunter_bow_pulling_2")},
                ],
                "fallback": _model("hunter_bow_pulling_0"),
            },
        }},
        "iron_crossbow": {"model": {
            "type": "minecraft:select",
            "property": "minecraft:charge_type",
            "cases": [
                {"when": "arrow", "model": _model("iron_crossbow_arrow")},
                {"when": "rocket", "model": _model("iron_crossbow_firework")},
            ],
            "fallback": {
                "type": "minecraft:condition",
                "property": "minecraft:using_item",
                "on_false": _model("iron_crossbow"),
                "on_true": {
                    "type": "minecraft:range_dispatch",
                    "property": "minecraft:crossbow/pull",
                    "entries": [
                        {"threshold": 0.58, "model": _model("iron_crossbow_pulling_1")},
                        {"threshold": 1.0, "model": _model("iron_crossbow_pulling_2")},
                    ],
                    "fallback": _model("iron_crossbow_pulling_0"),
                },
            },
        }},
        "angler_rod": {"model": {
            "type": "minecraft:condition",
            "property": "minecraft:fishing_rod/cast",
            "on_false": _model("angler_rod"),
            "on_true": _model("angler_rod_cast"),
        }},
        "knight_shield": {"model": {
            "type": "minecraft:condition",
            "property": "minecraft:using_item",
            "on_false": _model("knight_shield"),
            "on_true": _model("knight_shield_blocking"),
        }},
    }


# texture name -> (generator, parent model)
TEXTURED_MODELS = {
    "hunter_bow": (I.hunter_bow, "minecraft:item/bow"),
    "hunter_bow_pulling_0": (lambda: I.hunter_bow_pulling(0), f"{NS}:item/hunter_bow"),
    "hunter_bow_pulling_1": (lambda: I.hunter_bow_pulling(1), f"{NS}:item/hunter_bow"),
    "hunter_bow_pulling_2": (lambda: I.hunter_bow_pulling(2), f"{NS}:item/hunter_bow"),
    "iron_crossbow": (lambda: I.iron_crossbow("standby"), "minecraft:item/crossbow"),
    "iron_crossbow_pulling_0": (lambda: I.iron_crossbow("pull0"), f"{NS}:item/iron_crossbow"),
    "iron_crossbow_pulling_1": (lambda: I.iron_crossbow("pull1"), f"{NS}:item/iron_crossbow"),
    "iron_crossbow_pulling_2": (lambda: I.iron_crossbow("pull2"), f"{NS}:item/iron_crossbow"),
    "iron_crossbow_arrow": (lambda: I.iron_crossbow("arrow"), f"{NS}:item/iron_crossbow"),
    "iron_crossbow_firework": (lambda: I.iron_crossbow("rocket"), f"{NS}:item/iron_crossbow"),
    "angler_rod": (I.fishing_rod, "minecraft:item/handheld_rod"),
    "angler_rod_cast": (I.fishing_rod_cast, "minecraft:item/handheld_rod"),
}

# plain 2D icons (wrapped automatically by the plugin: textures/<name>.png)
ICONS = {
    "knight_sword": I.knight_sword,
    "steel_helmet": I.steel_helmet,
    "steel_chestplate": I.steel_chestplate,
    "steel_leggings": I.steel_leggings,
    "steel_boots": I.steel_boots,
}


def shield(blocking: bool) -> Model:
    """Geometry in the same space as the vanilla shield renderer (plate centred on x/y = 0,
    front face towards +Z, grip towards -Z), so the vanilla display transforms fit."""
    m = Model("knight_shield_blocking" if blocking else "knight_shield")
    m.group("plate")
    m.box("planks", (-5, -10, 1), (5, 10, 2), "wood")
    m.box("rim_top", (-6, 10, 0.75), (6, 11, 2.5), "steel")
    m.box("rim_bottom", (-6, -11, 0.75), (6, -10, 2.5), "steel")
    m.box("rim_left", (-6, -10, 0.75), (-5, 10, 2.5), "steel")
    m.box("rim_right", (5, -10, 0.75), (6, 10, 2.5), "steel")
    m.box("band_v", (-0.5, -10, 2), (0.5, 10, 2.25), "brass")
    m.box("band_h", (-5, -0.5, 2), (-2, 0.5, 2.25), "brass")
    m.box("band_h2", (2, -0.5, 2), (5, 0.5, 2.25), "brass")
    m.box("boss", (-2, -2, 2), (2, 2, 3), "steel")
    m.box("boss_cap", (-1, -1, 3), (1, 1, 3.5), "brass")
    m.group("grip")
    m.box("grip", (-1, -3, -5), (1, 3, 1), "wood_dark_grain")
    m.display = SHIELD_BLOCKING_DISPLAY if blocking else SHIELD_DISPLAY
    return m


def equipment():
    return {"layers": {
        "humanoid": [{"texture": f"{NS}:steel"}],
        "humanoid_leggings": [{"texture": f"{NS}:steel"}],
    }}


def write(content_pack: Path, build_dir: Path):
    assets = content_pack / "assets" / NS
    for sub in ("items", "models/item", "textures/item", "equipment",
                "textures/entity/equipment/humanoid", "textures/entity/equipment/humanoid_leggings"):
        (assets / sub).mkdir(parents=True, exist_ok=True)

    for name, definition in item_definitions().items():
        (assets / "items" / f"{name}.json").write_text(json.dumps(definition, indent=2))
    for name, (gen, parent) in TEXTURED_MODELS.items():
        gen().save(assets / "textures" / "item" / f"{name}.png")
        model = {"parent": parent, "textures": {"layer0": f"{NS}:item/{name}"}}
        (assets / "models" / "item" / f"{name}.json").write_text(json.dumps(model, indent=2))
    for name, gen in ICONS.items():
        gen().save(content_pack / "textures" / f"{name}.png")

    (assets / "equipment" / "steel.json").write_text(json.dumps(equipment(), indent=2))
    I.steel_humanoid().save(assets / "textures/entity/equipment/humanoid/steel.png")
    I.steel_humanoid_leggings().save(assets / "textures/entity/equipment/humanoid_leggings/steel.png")

    for blocking in (False, True):
        s = shield(blocking)
        s.write(content_pack / "models" / f"{s.name}.bbmodel", build_dir / f"{s.name}.preview.json")
    return list(item_definitions()) + list(ICONS)
