"""
The easel (그림 그리기, content/stsv/items/furniture_easel.yml; README "그림 그리기 (이젤)"), built as baked models
(stsv_assets/bake.py) with the conventions of models/furniture_japanese.py: 16 px a block, front = north (z 0), the
anchor cell x 0..16 / z 0..16, y 0 its floor; model +x is the viewer's LEFT.

Cells [0,0,0], [0,1,0] (the easel, two blocks tall) and [0,1,-1]: the block in front of its upper half, where the
plugin hangs the canvas (a map in an invisible, fixed glow item frame against the easel's front face, z 0, y 16..32;
dev.stsv.core.painting.PaintingModule). So the picture is exactly the upper block's front face: nothing of the easel
may stand in front of z 0 above y 16.

  easel          the empty easel: a beech tripod (two front legs splayed into a Λ under the tray, a back leg hinged to
                 the mast), the tray with a lip, a few paint tubes and a brush on it, the mast and its top clamp
  easel_canvas   the same with a canvas on it (shown while one is up): the board behind the map, its sides wrapped
                 linen with tacks, its back the stretcher bars

PIECES: id -> (builder, cells as (w, h, d) for the previews, floor offset)
"""

from economy_machines import display
from stsv_assets.bake import BModel, Paint, h3

# ================================================================================================
# Paints
# ================================================================================================

BEECH = Paint("hinoki", 2, "grain", axis="long", noise=0.02)
BEECH_DARK = Paint("hinoki", 1, "grain", axis="long", noise=0.02)
BRASS = Paint("brass", 2, "metal")

T = 22.5      # the legs' slant (a model allows 22.5° steps)


def flat(ramp_name, step, **kw):
    return Paint(ramp_name, step, noise=0, **kw)


def spattered(c):
    """Decal: old paint dripped on the tray's top and front, a few dots of colour."""
    if c.face not in ("up", "north"):
        return None
    r = h3(c.i[0], c.i[1], c.i[2], 41)
    if r < 0.035:
        return ("vermilion", 2)
    if r < 0.06:
        return ("signal_blue", 1)
    if r < 0.085:
        return ("petal_yellow", 2)
    if r < 0.10:
        return ("leaf", 2)
    return None


TRAY = Paint("hinoki", 2, "grain", axis="x", noise=0.02, decal=spattered)


def _easel(m):
    # --- the front legs: a Λ from the floor up under the tray ----------------------------------------
    m.group("legs")
    # viewer's left leg (+x) leans towards -x (+22.5 about z), the right one mirrored; their tops meet behind the tray
    m.box("leg", (13.5, -0.5, 1), (15, 16, 2.5), BEECH, rotation=("z", T, (14.25, 0, 1.75)))
    m.box("leg", (1, -0.5, 1), (2.5, 16, 2.5), BEECH, rotation=("z", -T, (1.75, 0, 1.75)))
    m.box("foot", (13.25, 0, 0.75), (15.25, 0.75, 2.75), BEECH_DARK)
    m.box("foot", (0.75, 0, 0.75), (2.75, 0.75, 2.75), BEECH_DARK)
    m.box("brace", (4, 4.5, 1.25), (12, 5.5, 2.25), BEECH_DARK)               # the crossbar holding the legs apart
    # the back leg, hinged to the mast: from the floor 14.5 behind up to the hinge at y 26.75
    m.box("back_leg", (7.25, -0.5, 13.75), (8.75, 29, 15.25), BEECH, rotation=("x", -T, (8, 0, 14.5)))
    m.box("back_foot", (7, 0, 13.5), (9, 0.75, 15.5), BEECH_DARK)

    # --- the mast and the tray ----------------------------------------------------------------------
    m.group("mast")
    m.box("mast", (7.25, 13, 2.25), (8.75, 31.75, 3.75), BEECH)
    m.box("hinge", (6.75, 25.5, 3.5), (9.25, 27.75, 5), BRASS)
    m.box("clamp", (4.5, 30.5, 1.25), (11.5, 32, 2.5), BEECH_DARK)            # holds the canvas's top edge
    m.box("clamp_knob", (7.5, 29, 3.75), (8.5, 30, 4.75), BRASS)
    m.box("tray", (0.5, 13.5, -2), (15.5, 15, 4), TRAY)
    m.box("lip", (0.5, 15, -2), (15.5, 16, -1.25), TRAY)                       # the canvas stands behind it
    m.box("tray_block", (6.5, 12, 1.75), (9.5, 13.5, 3.5), BEECH_DARK)           # where the legs and the mast meet

    # --- on the tray: paint tubes and a brush (low: nothing above y 16 in front of the canvas) --------
    m.group("props")
    for (x, ramp, step) in ((12.5, "vermilion", 2), (10.25, "signal_blue", 1), (3.5, "petal_yellow", 2)):
        m.box("tube", (x - 1.5, 15, -1.0), (x, 15.75, -0.25), flat(ramp, step, rim=0.6))
        m.box("tube_cap", (x, 15.1, -0.85), (x + 0.5, 15.65, -0.4), flat("porcelain", 2, ao=0))
    m.box("brush_handle", (4.5, 15, -0.9), (8.75, 15.5, -0.4), flat("wood_dark", 2, rim=0.6))
    m.box("brush_ferrule", (3.75, 15, -0.95), (4.5, 15.55, -0.35), flat("brass", 3, ao=0))
    m.box("brush_tip", (3, 15.05, -0.8), (3.75, 15.45, -0.5), flat("vermilion", 2, ao=0))
    return m


def easel():
    """이젤 1x2 (+ the canvas's cell in front of its upper half): a beech tripod easel with a paint tray."""
    m = _easel(BModel("easel"))
    return display(m, (1, 2, 1), 0)


def _canvas_decal(c):
    """Decal of the canvas board: white at the front (under the map), the linen wrapped round its sides with a row of
    tacks, the stretcher bars (an edge frame and a cross bar) on its back over the linen."""
    x, y, z = c.p
    if c.face == "north":
        return ("snow", 2)
    if c.face == "south":
        e = min(x, 16 - x, y - 16, 32 - y)
        if e < 1.5 or abs(x - 8) < 0.75 or abs(y - 24) < 0.75:
            return ("hinoki", 2 if (c.i[0] + c.i[1]) % 5 else 1)
        return ("cream", 2)
    along = c.i[1] if c.face in ("east", "west") else c.i[0]
    if c.face in ("east", "west", "up", "down") and along % 4 == 2 and abs(z - 0.7) < 0.3:
        return ("iron", 2)                                               # tacks
    return ("cream", 3)


def easel_canvas():
    """이젤 + 캔버스: the easel with a canvas board standing on its tray (the picture itself is the map in front)."""
    m = _easel(BModel("easel_canvas"))
    m.group("canvas")
    m.box("canvas", (0, 16, 0.125), (16, 32, 1.25), Paint("cream", 3, noise=0, decal=_canvas_decal, ao=0.5, rim=0.5))
    return display(m, (1, 2, 1), 0)


# ================================================================================================
# Registry
# ================================================================================================

PIECES = {
    # id: (builder, cells as (w, h, d) blocks, floor)
    "easel": (easel, (1, 2, 1), 0),
    "easel_canvas": (easel_canvas, (1, 2, 1), 0),
}

ALL = {k: v[0] for k, v in PIECES.items()}
SIZES = {k: v[1] for k, v in PIECES.items()}
REAL = {"easel": "높이 2 m, 폭 1 m (캔버스 1×1칸 = 지도 한 장 128×128)"}
