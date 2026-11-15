"""
Inventory icons (16x16) of the retro Korean furniture, 부엌·살림: textures/<id>_icon.png in the core pack, drawn like
furniture_icons.py (the shared pixelart Canvas and palette materials, light from the top left, outline in the
darkest step, each piece seen about the way its 3D model is).
"""

ICONS = {}


def icon(pid):
    def deco(fn):
        ICONS[pid] = fn
        return fn
    return deco
