"""
Retro Korean furniture, 마당·골목 (content/stsv/items/furniture_retro_yard.yml): ten pieces of an 80s-90s Korean home,
built as baked models (stsv_assets/bake.py) with the same conventions as models/furniture_japanese.py (16 px a
block, front = north (z 0), anchor cell x 0..16 / z 0..16, y 0 its floor; model +x is the viewer's LEFT; every
piece centred in its cells; collision from the model: a cell gets a barrier when the model fills 30% of it).
No real brand, logo or product design is copied.

PIECES: id -> (builder, cells as (w, h, d) for the previews, floor offset)
"""

PIECES = {}

ALL = {k: v[0] for k, v in PIECES.items()}
SIZES = {k: v[1] for k, v in PIECES.items()}
