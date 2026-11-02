"""
STSV palette v2: every ramp is GENERATED from a few numbers by one shared formula.

Why: hand-picked ramps drift (each new material ends up with its own brightness steps and saturation).
Here a material only chooses hue / chroma / lightness band; the formula applies the same rules to all:

  1. Lightness ladder in OKLCH (perceptually even), so step i of leaves is as bright as step i of bricks.
  2. Shared light colour: shadows lean towards blue-violet, highlights towards warm yellow (hue shift),
     so unrelated objects look lit by the same sun.
  3. Chroma fades at the extremes (dark shadows and bright highlights are less saturated).

Targets were measured from vanilla 26.3 block textures (see docs/ART_STYLE.md):
  natural materials  L 0.40-0.75, chroma 0.05-0.10   -> "vanilla-friendly" base
  dyed / metals      chroma 0.11-0.16
  gems / light       chroma 0.16-0.21                 -> reserved for ACCENT materials (fire, lamp, magic, gold)
"""

from .color import oklch_to_rgb

SHADOW_HUE = 285.0   # blue-violet
LIGHT_HUE = 85.0     # warm yellow
HUE_SHIFT = 10.0     # max degrees a ramp end rotates towards those hues

# v3 "clean & vivid": the v2 ramps read muddy in game. Saturation is raised for every material,
# shadows keep more of their colour, and texture noise is halved (textures.NOISE).
CHROMA_BOOST = 1.35          # natural / structural materials
ACCENT_CHROMA_BOOST = 1.15   # accents were already loud
EDGE_CHROMA_FALLOFF = 0.18   # was 0.35: dark/light ends lose less colour
MIN_LIGHTNESS = 0.24         # darkest step never goes below this (no muddy near-blacks)

# name: (hue, chroma, center lightness, lightness span, steps, accent)
#   hue may be a (start, end) tuple for ramps that change colour along the ladder (fire)
MATERIALS = {
    # --- structural ---------------------------------------------------------------------------
    "brick":       (36, 0.090, 0.54, 0.28, 5, False),
    "soot":        (32, 0.035, 0.33, 0.20, 4, False),
    "mortar":      (55, 0.015, 0.40, 0.12, 3, False),
    "stone":       (95, 0.006, 0.58, 0.22, 4, False),
    "steel":       (250, 0.014, 0.50, 0.40, 6, False),
    "rubber":      (260, 0.010, 0.26, 0.14, 4, False),
    # --- wood & nature -------------------------------------------------------------------------
    "wood":        (72, 0.080, 0.60, 0.26, 4, False),   # oak-like
    "wood_dark":   (62, 0.060, 0.42, 0.22, 4, False),   # spruce-like
    "leaf":        (134, 0.118, 0.54, 0.32, 5, False),
    "soil":        (55, 0.060, 0.42, 0.20, 4, False),
    "terracotta":  (44, 0.090, 0.56, 0.24, 4, False),
    "water":       (235, 0.090, 0.56, 0.26, 4, False),
    "snow":        (240, 0.010, 0.92, 0.10, 3, False),
    # --- metals & crafted -----------------------------------------------------------------------
    "copper":      (42, 0.115, 0.60, 0.32, 5, False),
    "brass":       (85, 0.100, 0.68, 0.30, 4, False),
    "fabric_red":  (25, 0.140, 0.47, 0.24, 4, False),
    "dial":        (90, 0.020, 0.88, 0.08, 2, False),
    # --- accents (allowed to be loud: light sources, magic, valuables, warnings) ------------------
    "gold":        (92, 0.160, 0.78, 0.32, 4, True),
    "hazard":      (95, 0.160, 0.80, 0.10, 2, True),
    "fire":        ((28, 100), 0.170, 0.62, 0.70, 6, True),
    "lamp":        ((72, 98), 0.140, 0.80, 0.36, 4, True),
    "crystal":     (300, 0.140, 0.60, 0.42, 5, True),
    "magic":       ((296, 312), 0.180, 0.66, 0.30, 4, True),
    "signal_red":  (28, 0.170, 0.52, 0.10, 2, True),
    "signal_green": (145, 0.150, 0.62, 0.10, 2, True),
    # --- vegetables (cosmetics) -----------------------------------------------------------------
    "tomato":      (30, 0.170, 0.58, 0.30, 5, False),
    "eggplant":    (305, 0.120, 0.42, 0.30, 5, False),
    "toadstool":   (25, 0.180, 0.55, 0.28, 5, False),   # red mushroom cap
    "cream":       (85, 0.030, 0.88, 0.14, 4, False),   # mushroom stalk / spots
    "paper":       (85, 0.025, 0.90, 0.16, 3, False),   # vouchers
    # --- premium (cash-item) accents -------------------------------------------------------------
    "blade":       (240, 0.025, 0.80, 0.30, 4, False),   # bright polished steel
    "obsidian":    (285, 0.060, 0.30, 0.18, 4, False),   # dark violet-black metal
    "aurora":      ((292, 205), 0.170, 0.72, 0.40, 5, True),  # violet -> cyan energy
    "ruby":        (12, 0.190, 0.55, 0.30, 4, True),
    # --- boss: the executioner (blackened armour, blood-red cloth, spectral eyes) ----------------
    "blacksteel":  (255, 0.022, 0.38, 0.30, 5, False),  # blackened plate armour
    "crimson":     (18, 0.130, 0.36, 0.22, 4, False),    # deep red cloth (cape, tabard)
    "leather":     (55, 0.060, 0.36, 0.20, 4, False),    # straps, grips
    "spectral":    (200, 0.120, 0.80, 0.30, 4, True),    # cold glowing eyes
    # --- economy server (job products, menus) -------------------------------------------------
    "sapphire":    (258, 0.150, 0.55, 0.32, 4, True),
    "topaz":       (70, 0.160, 0.74, 0.30, 4, True),
    "pearl":       (95, 0.020, 0.86, 0.20, 3, False),
    "felt":        (158, 0.070, 0.33, 0.20, 4, False),   # dark green ledger felt (economy menus)
    "salmon":      (40, 0.120, 0.68, 0.24, 4, False),
    "rice":        (90, 0.012, 0.92, 0.10, 3, False),
    # --- TEST-ONLY materials: "dev texture" colours, never used by real content -----------------
    "test_orange": (55, 0.150, 0.70, 0.30, 4, True),
    "test_grey":   (250, 0.012, 0.62, 0.30, 4, False),
    "test_teal":   (195, 0.120, 0.64, 0.30, 4, True),
    "test_purple": (305, 0.150, 0.58, 0.30, 4, True),
}


def _toward(h, target, amount):
    d = (target - h + 540) % 360 - 180
    return h + max(-abs(d), min(abs(d), amount)) * (1 if d >= 0 else -1)


def build_ramp(hue, chroma, center, span, steps, accent=False):
    out = []
    for i in range(steps):
        t = i / (steps - 1) if steps > 1 else 0.5
        L = center + span * (t - 0.5)
        if isinstance(hue, tuple):
            h = hue[0] + (hue[1] - hue[0]) * t
        else:
            h = hue
        edge = abs(t - 0.5) * 2                     # 0 at the middle, 1 at both ends
        shift = HUE_SHIFT * edge * (0.5 if accent else 1.0)
        h = _toward(h, LIGHT_HUE if t > 0.5 else SHADOW_HUE, shift)
        c = chroma * (ACCENT_CHROMA_BOOST if accent else CHROMA_BOOST) * (1 - EDGE_CHROMA_FALLOFF * edge)
        if chroma < 0.02:                           # near-greys still pick up a hint of the light colour
            c = max(c, 0.010)
        out.append(oklch_to_rgb(max(L, MIN_LIGHTNESS), c, h % 360))
    return out


PALETTE = {name: build_ramp(*spec) for name, spec in MATERIALS.items()}
PALETTE["signal"] = PALETTE["signal_red"] + PALETTE["signal_green"][1:]  # [dark red, red, green]


def ramp(name: str, i: int):
    r = PALETTE[name]
    return (*r[max(0, min(len(r) - 1, i))], 255)


def size(name: str) -> int:
    return len(PALETTE[name])
