"""
Retro Korean furniture (content/stsv/items/furniture_retro_*.yml): thirty pieces of an 80s-90s Korean home and
alley, in three groups of ten: the room (furniture_retro_room), the kitchen (furniture_retro_kitchen) and the yard
(furniture_retro_yard). Each group's module builds its pieces; this one gathers them for build.py and the
preview sheets (python3 furniture_preview.py --set retro). ROOM (a furnished scene for the room sheet) and REAL
(the real thing's size, for the scale sheet) are optional.
"""

from models import furniture_retro_kitchen as _kitchen
from models import furniture_retro_room as _room
from models import furniture_retro_yard as _yard

PIECES = {**_room.PIECES, **_kitchen.PIECES, **_yard.PIECES}
ALL = {k: v[0] for k, v in PIECES.items()}
SIZES = {k: v[1] for k, v in PIECES.items()}
REAL = {**getattr(_room, "REAL", {}), **getattr(_kitchen, "REAL", {}), **getattr(_yard, "REAL", {})}
