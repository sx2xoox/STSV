"""Inventory icons of the retro Korean furniture: the three groups' icons together (build.py, the preview sheets)."""

import furniture_icons_retro_kitchen as _kitchen
import furniture_icons_retro_room as _room
import furniture_icons_retro_yard as _yard

ICONS = {**_room.ICONS, **_kitchen.ICONS, **_yard.ICONS}
