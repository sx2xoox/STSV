"""
Procedural pixel-art textures for STSV.

Every texture is drawn from the shared PALETTE with a fixed seed, so:
  - rebuilding always gives the exact same pixels (no external generator, no credits)
  - new textures automatically share the same colours and shading rules (consistent art style)

Shading rules used everywhere:
  - 16x16, one ramp (dark -> light) per material
  - light comes from the top-left: top/left edges +1 step, bottom/right edges -1 step
  - small per-pixel jitter of at most one ramp step
"""

import math
import random
from pathlib import Path

from PIL import Image

SIZE = 16

PALETTE = {
    "brick": [(66, 24, 22), (98, 36, 30), (128, 50, 38), (156, 68, 50), (184, 94, 68)],
    "mortar": [(40, 34, 33), (56, 48, 46)],
    "steel": [(26, 28, 32), (40, 43, 48), (56, 60, 66), (76, 81, 88), (104, 110, 118)],
    "rubber": [(16, 16, 18), (26, 26, 30), (38, 38, 44), (54, 54, 62)],
    "fire": [(56, 10, 8), (124, 26, 12), (196, 66, 16), (238, 134, 28), (255, 204, 76), (255, 244, 168)],
    "stone": [(64, 64, 68), (88, 88, 92), (112, 112, 116), (138, 138, 142)],
    "gold": [(128, 82, 18), (190, 140, 36), (244, 202, 74), (255, 242, 164)],
    "brass": [(110, 80, 32), (170, 130, 56), (212, 176, 92)],
    "dial": [(200, 194, 176), (230, 224, 206)],
    "needle": [(196, 32, 28)],
}


def ramp(name, i):
    r = PALETTE[name]
    return (*r[max(0, min(len(r) - 1, i))], 255)


def jitter(rnd, i, chance=0.25):
    if rnd.random() < chance:
        return i + rnd.choice((-1, 1))
    return i


def new():
    return Image.new("RGBA", (SIZE, SIZE))


# ---------------------------------------------------------------------------------------------


def brick(seed=11):
    rnd = random.Random(seed)
    im, px = new(), None
    px = im.load()
    row_h, brick_w = 4, 8
    brick_base = {}
    for y in range(SIZE):
        row = y // row_h
        offset = (brick_w // 2) * (row % 2)
        for x in range(SIZE):
            in_row_y = y % row_h
            in_brick_x = (x + offset) % brick_w
            if in_row_y == row_h - 1 or in_brick_x == brick_w - 1:
                px[x, y] = ramp("mortar", 0 if in_row_y == row_h - 1 else 1)
                continue
            key = (row, (x + offset) // brick_w % (SIZE // brick_w))
            base = brick_base.setdefault(key, rnd.choice((1, 2, 2, 3)))
            i = base
            if in_row_y == 0:
                i += 1  # top highlight
            elif in_row_y == row_h - 2:
                i -= 1  # bottom shade
            if in_brick_x == 0:
                i += 0 if in_row_y else 0
            px[x, y] = ramp("brick", jitter(rnd, i, 0.2))
    return im


def steel(seed=21):
    rnd = random.Random(seed)
    im = new()
    px = im.load()
    for y in range(SIZE):
        for x in range(SIZE):
            i = 2
            if y in (5, 10):
                i = 1 if rnd.random() < 0.6 else 2  # faint brushed lines
            if x == 0 or y == 0:
                i = 3
            if x == SIZE - 1 or y == SIZE - 1:
                i = 0
            px[x, y] = ramp("steel", jitter(rnd, i, 0.12) if 0 < x < 15 and 0 < y < 15 else i)
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        px[x, y] = ramp("steel", 4)
        px[x + 1, y + 1] = ramp("steel", 0)
        px[x + 1, y] = ramp("steel", 3)
    return im


def fire(seed=31):
    rnd = random.Random(seed)
    im = new()
    px = im.load()
    phase = [rnd.random() * math.tau for _ in range(3)]
    for y in range(SIZE):
        for x in range(SIZE):
            t = x / SIZE * math.tau  # periodic in x -> tiles horizontally
            tongue = (math.sin(t * 2 + phase[0]) * 0.5 + math.sin(t * 3 + phase[1]) * 0.3
                      + math.sin(t * 5 + phase[2]) * 0.2)
            heat = (y / (SIZE - 1)) * 1.25 + tongue * 0.28 - 0.1
            i = int(heat * (len(PALETTE["fire"]) - 1))
            px[x, y] = ramp("fire", jitter(rnd, i, 0.18))
    return im


def gold_ore(seed=41):
    rnd = random.Random(seed)
    im = new()
    px = im.load()
    for y in range(SIZE):
        for x in range(SIZE):
            px[x, y] = ramp("stone", jitter(rnd, rnd.choice((1, 2, 2)), 0.3))
    nugget = [(0, 0, 2), (1, 0, 3), (0, 1, 1), (1, 1, 2), (2, 1, 1), (1, 2, 0)]
    for (cx, cy) in ((2, 3), (9, 1), (11, 9), (4, 11), (8, 6)):
        for dx, dy, i in nugget:
            px[(cx + dx) % SIZE, (cy + dy) % SIZE] = ramp("gold", i)
    return im


def belt(seed=51):
    rnd = random.Random(seed)
    im = new()
    px = im.load()
    for y in range(SIZE):
        for x in range(SIZE):
            i = {0: 3, 1: 2, 2: 1, 3: 0}[y % 4]  # ridges running across the belt
            px[x, y] = ramp("rubber", jitter(rnd, i, 0.1))
    return im


def dark(seed=61):
    """Opening into the furnace: near-black with a faint ember glow at the bottom."""
    rnd = random.Random(seed)
    im = new()
    px = im.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if y >= 12 and rnd.random() < (y - 11) * 0.18:
                px[x, y] = ramp("fire", rnd.choice((0, 0, 1)))
            else:
                px[x, y] = ramp("rubber", jitter(rnd, 0, 0.3))
    return im


def gauge():
    im = Image.new("RGBA", (SIZE, SIZE), ramp("steel", 1))
    px = im.load()
    c = 7.5
    for y in range(SIZE):
        for x in range(SIZE):
            d = math.hypot(x - c, y - c)
            if d < 5.6:
                px[x, y] = ramp("dial", 1 if (x + y) < 15 else 0)
            elif d < 7.3:
                px[x, y] = ramp("brass", 2 if (x + y) < 15 else 0)
    for (x, y) in ((4, 10), (3, 7), (4, 4), (7, 3), (11, 4), (12, 7), (11, 10)):
        px[x, y] = ramp("steel", 1)
    for (x, y) in ((8, 8), (9, 7), (10, 6), (11, 5)):
        px[x, y] = ramp("needle", 0)
    px[7, 8] = px[8, 8] = ramp("steel", 0)
    return im


GENERATORS = {
    "brick": brick,
    "iron": steel,
    "lava": fire,
    "ore": gold_ore,
    "belt": belt,
    "dark": dark,
    "gauge": gauge,
}


def generate_all(out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, fn in GENERATORS.items():
        fn().save(out_dir / f"{name}.png")
