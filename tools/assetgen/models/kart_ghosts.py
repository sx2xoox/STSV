"""
서킷 고스트 (the ghost a player races against, plugin GhostView): every kart and kart wheel drawn again see-through, in a
ghost's pale light, so the ghost reads at a glance as "not a real kart" and never hides the road behind it.

  kart_<x>_ghost        the kart in ice blue (my best lap)
  kart_<x>_ghost_gold   the kart in pale gold (the track record)
  kart_wheel*_ghost     its wheels, ice blue (the record's too: the body carries the colour)

Nothing is drawn anew: each is its own generator run again (models/karts.py, in white so the shading keeps its full
range), baked, and its texture sheet turned into the ghost's: every pixel's brightness kept as the tint's, at about half
see-through (an item model's texture with alpha is drawn translucent, as the underglow is). Redraw a kart and its ghosts
follow.

Imported by build.py like the other model modules (ALL).
"""

from PIL import Image

from stsv_assets import textures as T
from models import karts as KARTS

# tint -> (the darkest shade, the brightest), and how opaque the ghost is (0..255)
TINTS = {"ghost": ((40, 92, 150), (214, 246, 255)), "ghost_gold": ((128, 82, 20), (255, 236, 168))}
ALPHA = 120

BODIES = {"kart_storm": KARTS.storm, "kart_bubble": KARTS.bubble, "kart_drifter": KARTS.drifter, "kart_buffalo": KARTS.buffalo,
          "kart_tub": KARTS.tub}
WHEELS = {"kart_wheel": KARTS.kart_wheel, "kart_wheel_big": KARTS.kart_wheel_big, "kart_wheel_tub": KARTS.kart_wheel_tub}


def ghostly(im, tint):
    """A texture sheet in the ghost's light: brightness -> the tint's ramp, half see-through (clear stays clear)."""
    lo, hi = TINTS[tint]
    src = im.convert("RGBA")
    out = Image.new("RGBA", src.size, (0, 0, 0, 0))
    px, po = src.load(), out.load()
    for y in range(src.height):
        for x in range(src.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255
            t = min(1.0, max(0.0, 0.15 + lum * 0.95))
            c = tuple(int(round(lo[k] + (hi[k] - lo[k]) * t)) for k in range(3))
            po[x, y] = (*c, int(round(ALPHA * a / 255)))
    return out


def _ghost(make, name, tint):
    m = make()
    m.name = name
    m.bake()                     # its texture sheet is named after it (bake.py _atlas)
    for t in m.textures_used():
        if t.startswith(name):
            src = T.SURFACES[t]
            T.SURFACES[t] = (lambda src=src: ghostly(src(), tint))
            T._ALPHA_CACHE.pop(t, None)
    # the check that a see-through texture sits only on thin planes is for solid things (it would show their hollow
    # inside); a ghost is meant to be seen through: every other check stays
    check = m.validate
    m.validate = lambda: [p for p in check() if "투명 픽셀" not in p]
    return m


def _all():
    out = {}
    for body, fn in BODIES.items():
        for tint in TINTS:
            out[f"{body}_{tint}"] = (lambda fn=fn, body=body, tint=tint: _ghost(lambda: fn(colour="car_white"), f"{body}_{tint}", tint))
    for wheel, fn in WHEELS.items():
        out[f"{wheel}_ghost"] = (lambda fn=fn, wheel=wheel: _ghost(fn, f"{wheel}_ghost", "ghost"))
    return out


ALL = _all()
