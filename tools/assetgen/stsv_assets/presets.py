"""
Display transforms (how a model sits in hands / GUI / ground / head), copied from vanilla 26.3 models
so 3D items are held exactly like their 2D vanilla counterparts.
"""

_GENERATED = {
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}

# minecraft:item/handheld (swords, tools)
HANDHELD = {**_GENERATED,
    "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]},
    "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
}

# minecraft:item/handheld_rod (fishing rods)
HANDHELD_ROD = {**HANDHELD,
    "thirdperson_righthand": {"rotation": [0, 90, 55], "translation": [0, 4.0, 2.5], "scale": [0.85, 0.85, 0.85]},
    "thirdperson_lefthand": {"rotation": [0, -90, -55], "translation": [0, 4.0, 2.5], "scale": [0.85, 0.85, 0.85]},
    "firstperson_righthand": {"rotation": [0, 90, 25], "translation": [0, 1.6, 0.8], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, -90, -25], "translation": [0, 1.6, 0.8], "scale": [0.68, 0.68, 0.68]},
}

# minecraft:item/generated (food and other held objects)
GENERATED = dict(_GENERATED)

# block-like objects: furniture icons, and hats worn like a carved pumpkin (identity 'head')
BLOCK = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
}


def furniture(gui_scale=0.4, gui_translation=(0, 0, 0)):
    """Furniture: 'fixed' must stay identity (that is how the item display shows it in the world)."""
    d = dict(BLOCK)
    d["gui"] = {"rotation": [30, 225, 0], "translation": list(gui_translation), "scale": [gui_scale] * 3}
    d["fixed"] = {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}
    return d
