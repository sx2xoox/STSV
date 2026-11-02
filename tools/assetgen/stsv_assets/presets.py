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


# ------------------------------------------------------------------------------------------------
# Bigger-than-vanilla held items that still sit in the hand
# ------------------------------------------------------------------------------------------------

def _rot_xyz(deg):
    """Minecraft display rotation = JOML rotationXYZ(x, y, z) -> matrix Rx * Ry * Rz."""
    import math
    ax, ay, az = (math.radians(a) for a in deg)
    cx, sx, cy, sy, cz, sz = math.cos(ax), math.sin(ax), math.cos(ay), math.sin(ay), math.cos(az), math.sin(az)
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    mul = lambda a, b: [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    return mul(mul(rx, ry), rz)


def enlarged(base, factor, grip, contexts=("thirdperson_righthand", "thirdperson_lefthand",
                                           "firstperson_righthand", "firstperson_lefthand")):
    """
    Scales the held views by `factor` (per context: float or dict) while keeping the grip point where the
    hand is. Display order is translate -> rotate -> scale around the model centre, so the grip moves
    by R * ((s - s') * g); the translation is corrected by exactly that.

    grip: grip point relative to the model centre (8, 8, 8), in model pixels.
    """
    out = {k: dict(v) for k, v in base.items()}
    for ctx in contexts:
        if ctx not in out:
            continue
        f = factor[ctx] if isinstance(factor, dict) else factor
        t, r, s = out[ctx]["translation"], out[ctx]["rotation"], out[ctx]["scale"]
        s2 = [round(v * f, 4) for v in s]
        d = [(s[i] - s2[i]) * grip[i] for i in range(3)]
        left = "lefthand" in ctx
        # the game mirrors left-hand transforms when applying them: rotation y/z and translation x flip
        m = _rot_xyz([r[0], -r[1], -r[2]] if left else r)
        shift = [sum(m[i][j] * d[j] for j in range(3)) for i in range(3)]
        if left:
            shift[0] = -shift[0]
        out[ctx] = {"rotation": r, "translation": [round(t[i] + shift[i], 3) for i in range(3)], "scale": s2}
    return out
