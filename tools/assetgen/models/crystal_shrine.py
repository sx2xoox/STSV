"""Glowing crystal on a stone pedestal (decor / objective marker). 1 x 1 block, ~1.6 blocks tall."""

from stsv_assets.model import Model

FURNITURE_CELLS = [[0, 0, 0], [0, 1, 0]]


def build() -> Model:
    m = Model("crystal_shrine")

    m.group("pedestal")
    m.box("base", (1, 0, 1), (15, 2, 15), "stone_bricks")
    m.box("base_trim", (1.5, 2, 1.5), (14.5, 2.5, 14.5), "gold_trim")
    m.box("column", (3, 2.5, 3), (13, 8, 13), "stone_bricks")
    m.box("cap", (2, 8, 2), (14, 9.5, 14), "stone_bricks")
    m.box("cap_trim", (2.5, 9.5, 2.5), (13.5, 10, 13.5), "gold_trim")
    for i, (x, z) in enumerate(((2, 2), (12, 2), (2, 12), (12, 12))):
        m.box(f"stud_{i}", (x, 9.5, z), (x + 2, 10.5, z + 2), "gold_trim")

    m.group("crystal")
    # the main crystal is the light source; side shards stay unlit so the facets read
    m.box("main", (6, 10, 6), (10, 24, 10), "magic", glow=True, rotation=("y", 45, (8, 16, 8)))
    m.box("tip", (6.75, 24, 6.75), (9.25, 27, 9.25), "magic", glow=True, rotation=("y", 45, (8, 25, 8)))
    m.box("shard_n", (6.5, 10, 3.5), (8.5, 17, 5.5), "crystal", rotation=("x", -22.5, (7.5, 10, 4.5)))
    m.box("shard_s", (7.5, 10, 10.5), (9.5, 18, 12.5), "crystal", rotation=("x", 22.5, (8.5, 10, 11.5)))
    m.box("shard_e", (10.5, 10, 7), (12.5, 15, 9), "crystal", rotation=("z", -22.5, (11.5, 10, 8)))
    m.box("shard_w", (3.5, 10, 7.5), (5.5, 16, 9.5), "crystal", rotation=("z", 22.5, (4.5, 10, 8.5)))

    m.display = {
        "gui": {"rotation": [30, 225, 0], "translation": [0, -3, 0], "scale": [0.45, 0.45, 0.45]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.3, 0.3, 0.3]},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    }
    return m
