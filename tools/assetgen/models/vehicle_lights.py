"""
Lamps of the motor vehicles (탈것의 느낌, content/stsv/vehicle_feel.yml lights:): parts the plugin draws over a vehicle's
body (dev.stsv.core.vehicle.VehicleFeelView), full-bright, and swaps as the lamps change:

  <v>_lamp_head      headlamps on (it is dark around the vehicle and someone drives): a hot lens over each body lamp,
                     a soft glow round it and a faint beam ahead (two crossed see-through planes fading out)
  <v>_lamp_tail      tail lamps (with the headlamps): a dim red lens over each tail lamp
  <v>_lamp_brake     brake lamps (braking): a bright red lens and a red glow round it
  <v>_lamp_reverse   reverse lamps (backing up: trucks, the van): a white lens beside the red, the tail lamps dim red

Drawn in each body's own space (16 px a block, the vehicle's ground centre at (0, 0, 0), front = north, +x = the
vehicle's right: models/vehicles.py), so the plugin shows them with the body's own transformation and they lean, tip
and bob with it. Only these models are drawn at LAMP_SCALE times the body's shrink (the beams reach past the body's
model bounds) and shown LAMP_SCALE times larger (VehicleFeelView.LAMP_SCALE): the same size in the world.

Where the lamps are is copied from the body generators below (LAMPS: per vehicle the lenses as rectangles on a face,
real px). When a body is redrawn and its lamps move, move them here too: build.py warns when a lens no longer lies
on a face of the body (check()).
"""

import math

from stsv_assets.bake import BModel, Paint
from stsv_assets.palette import ladder
from stsv_assets import presets

# the lamp models are shrunk this many times more than their body (the plugin shows them this much larger)
LAMP_SCALE = 2
# how far in front of the body's lamp face a lens sits, and its glow plane (real px)
PROUD, HALO_OUT = 0.15, 0.5

# ------------------------------------------------------------------------------------------------
# Where the lamps are (real px, the body's drawn space): each lens (x0, x1, y0, y1, z, round) on the face at z
# (front lamps face north: -z, rear ones south: +z). Symmetric ones are listed once with mirror=True.
# ------------------------------------------------------------------------------------------------


def lens(x0, x1, y0, y1, z, round_=False, mirror=True):
    out = [(x0, x1, y0, y1, z, round_)]
    if mirror:
        out.append((-x1, -x0, y0, y1, z, round_))
    return out


LAMPS = {
    # models/vehicles.py police_car: swept headlights (projector + reflector over the running light), taillights
    # with a lit bar along the top (the white reverse lamp at their inner end)
    "police_car": dict(scale=2.0, head=lens(8, 12, 8, 11, -36.5), tail=lens(9, 14.5, 9.5, 12, 36.5),
                       reverse=lens(8, 9, 10, 11, 36.5), body=("vehicles", "police_car")),
    # sports_car: a thin running light low on each corner of the nose, a thin lamp each side in the tail's mesh
    "sports_car": dict(scale=2.0, head=lens(10, 15.5, 4.5, 5.5, -36.25), tail=lens(7, 15, 9.5, 10.5, 36.25),
                       body=("vehicles", "sports_car")),
    # tuner_car: two round projectors a side, twin round tail lamps a side
    "tuner_car": dict(scale=2.0, head=lens(8, 15, 8.5, 11.5, -36.25), tail=lens(7.5, 14.5, 8.5, 11.5, 36.25),
                      body=("vehicles", "tuner_car")),
    # vehicles_premium.py hyper_car: a thin running light low on each corner of the nose, one lamp the width of the tail
    "hyper_car": dict(scale=2.0, head=lens(10, 15.5, 4.5, 5.5, -37.25), tail=lens(-15, 15, 10, 11, 37.25, False, False),
                      body=("vehicles", "hyper_car")),
    # grand_sedan: a slim smoked lamp each side of the radiator (a bar of light along its top), slim tail lamps
    "grand_sedan": dict(scale=2.0, head=lens(8, 14.5, 14, 17, -42.5), tail=lens(7, 14, 12.5, 16.5, 42.5),
                        body=("vehicles", "grand_sedan")),
    # luxury_suv (the monster off-roader: its body stands 16 px higher than it is drawn, lift): round lamps beside
    # the grille, tall narrow tail lamps low on the back's corners
    "luxury_suv": dict(scale=2.5, lift=16, head=lens(9, 14, 11.5, 16.5, -36.5, True), tail=lens(12, 14.5, 12, 18, 36.5),
                       body=("vehicles", "luxury_suv")),
    # light_truck: headlight boxes (amber strip on top), taillights on the bed's back (amber inside: the reverse lamp)
    "light_truck": dict(scale=2.0, head=lens(8.5, 12.5, 9, 12, -39.5), tail=lens(11, 13.5, 7, 9.5, 38.5),
                        reverse=lens(10, 11, 7, 9.5, 38.5), body=("vehicles", "light_truck")),
    # golf_cart: small round-cornered headlights, a thin tail strip
    "golf_cart": dict(scale=2.0, head=lens(5, 8, 10, 12, -19.5), tail=lens(5.5, 8, 8, 9, 19.5), body=("vehicles", "golf_cart")),
    # vehicles_road.py scooter: the round headlamp in the cowl's face, the tail lamp on the rear fender's back
    "delivery_scooter": dict(scale=2.0, head=lens(-1.6, 1.6, 17.4, 20.6, -9.5, True, False),
                             tail=lens(-1.75, 1.75, 11, 12.5, 15, False, False), body=("vehicles_road", "delivery_scooter")),
    # atv: two lamps in the nose and one in the handlebar pod, tail lamps on the tail's back
    "atv": dict(scale=2.0, head=lens(1.5, 4, 7.5, 9.5, -16.5) + lens(-1.4, 1.4, 16.1, 18.9, -8, True, False)
                + lens(5.5, 8.5, 10.75, 12.5, -15.5),
                tail=lens(1.5, 3.5, 8, 9.5, 16), body=("vehicles_road", "atv")),
    # cargo_van: tall tail lamps (amber top, red middle, white reverse at the bottom)
    "cargo_van": dict(scale=2.0, head=lens(6, 10, 9, 12.5, -28.5), tail=lens(9.5, 11.75, 12, 19, 28.5),
                      reverse=lens(9.5, 11.75, 10, 12, 28.5), body=("vehicles_road", "cargo_van")),
    # box_truck: headlamps in the bumper, tail lamps on the underrun bar (the reverse lamps beside the plate)
    "box_truck": dict(scale=2.5, head=lens(10.5, 15, 7.5, 10.5, -53.5), tail=lens(9, 13, 8, 10.5, 52),
                      reverse=lens(5.5, 8, 8, 10.5, 52), body=("vehicles_road", "box_truck")),
    # vehicles_farm.py power_tiller: one round lamp on the hopper's front corner (the trailer's lamps are not drawn here)
    "power_tiller": dict(scale=2.0, head=lens(-4.9, -2.1, 26.1, 28.9, -16, True, False), body=("vehicles_farm", "power_tiller")),
    # tractor: two round lamps standing on the hood's front corners, tail lamps on the fenders' backs
    "tractor": dict(scale=2.0, head=lens(4.25, 6.75, 21.5, 24, -23.5, True), tail=lens(12, 14.5, 20, 22, 24.5),
                    body=("vehicles_farm", "tractor")),
}

# colours (palette ramps, ART_STYLE: lamps glow)
HOT = ladder("lamp", 4)[:3]           # the headlamp's hot core
WARM = ladder("lamp", 3)[:3]
BEAM = ladder("lamp", 3)[:3]
RED_HOT = ladder("toadstool", 4)[:3]
RED = ladder("toadstool", 3)[:3]
RED_DIM = ladder("toadstool", 2)[:3]
WHITE = ladder("glass", 3)[:3]


def _inside(c, l):
    """Is the texel inside the lens (a round one: within its ellipse)?"""
    x0, x1, y0, y1, _, round_ = l
    if not round_:
        return True
    cx, cy, rx, ry = (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2
    return ((c.p[0] - cx) / rx) ** 2 + ((c.p[1] - cy) / ry) ** 2 <= 1.0


def _lens_paint(l, core, rim):
    """A flat glowing lens: hot in its middle, a ring of the rim colour (a round lens is cut round)."""
    x0, x1, y0, y1, _, _ = l
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    half = max(0.5, min(x1 - x0, y1 - y0) / 2)

    def decal(c):
        if not _inside(c, l):
            return ("raw", (0, 0, 0, 0))
        d = max(abs(c.p[0] - cx) / max(0.5, (x1 - x0) / 2), abs(c.p[1] - cy) / max(0.5, (y1 - y0) / 2))
        col = core if d < 0.55 or half < 1 else rim
        return ("raw", (*col, 255))
    return Paint("lamp", 3, noise=0, light=False, glow=True, decal=decal)


def _glow_paint(l, col, reach, peak):
    """A soft glow round a lens: see-through, strongest at the lens' edge, fading out over `reach` px."""
    x0, x1, y0, y1, _, _ = l

    def decal(c):
        dx = max(x0 - c.p[0], 0, c.p[0] - x1)
        dy = max(y0 - c.p[1], 0, c.p[1] - y1)
        d = math.hypot(dx, dy)
        if d <= 0:
            return ("raw", (0, 0, 0, 0))         # the lens itself shows through (it is drawn just behind)
        a = peak * max(0.0, 1 - d / reach) ** 1.6
        return ("raw", (*col, int(round(a * 255))))
    return Paint("lamp", 3, noise=0, light=False, glow=True, decal=decal)


def _face_only(face):
    """The faces of an upright plane across the vehicle (both sides; its edges have no area)."""
    return {f: None for f in ("east", "west", "up", "down")}


def _lens(m, name, l, front, core, rim):
    x0, x1, y0, y1, z, _ = l
    zz = z - PROUD if front else z + PROUD
    m.box(name, (x0, y0, zz), (x1, y1, zz), _lens_paint(l, core, rim), faces=_face_only("north" if front else "south"))


def _halo(m, name, l, front, col, reach, peak):
    x0, x1, y0, y1, z, _ = l
    zz = z - HALO_OUT if front else z + HALO_OUT
    left, right = x0 - reach, x1 + reach
    if x0 > 0:                       # one of a pair: its glow stays on its own side (the two would overlap in the middle)
        left = max(left, 0.05)
    elif x1 < 0:
        right = min(right, -0.05)
    m.box(name, (left, y0 - reach, zz), (right, y1 + reach, zz), _glow_paint(l, col, reach, peak),
          faces=_face_only("north" if front else "south"))


def _beam(m, l, length, spread, peak):
    """A faint beam ahead of a headlamp: a level plane and an upright one along it, widening and fading out."""
    x0, x1, y0, y1, z, _ = l
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    w0, h0 = (x1 - x0) / 2, (y1 - y0) / 2
    z0, z1 = z - HALO_OUT - length, z - HALO_OUT

    def alpha(t, across, half):
        if abs(across) > half:
            return 0
        edge = 1 - abs(across) / max(half, 1e-6)
        return peak * (1 - t) ** 1.4 * min(1.0, edge * 2.5)

    def flat(c):                       # the level plane: across = x
        t = (z1 - c.p[2]) / length
        half = w0 + spread * t
        return ("raw", (*BEAM, int(round(alpha(t, c.p[0] - cx, half) * 255))))

    def upright(c):                    # the upright plane: across = y (it spreads down more than up: aimed low)
        t = (z1 - c.p[2]) / length
        d = c.p[1] - cy
        half = h0 + spread * t * (0.55 if d > 0 else 1.0)
        return ("raw", (*BEAM, int(round(alpha(t, d, half) * 255))))
    reach = spread + max(w0, h0)
    # a side lamp's level plane stays on its own side (the pair's planes would lie in one another in the middle)
    left, right = cx - reach, cx + reach
    if cx > 0.5:
        left = max(left, 0.05)
    elif cx < -0.5:
        right = min(right, -0.05)
    m.box("beam_flat", (left, cy, z0), (right, cy, z1), Paint("lamp", 3, noise=0, light=False, glow=True, decal=flat),
          faces={"north": None, "south": None, "east": None, "west": None})
    m.box("beam_upright", (cx, cy - reach, z0), (cx, cy + reach, z1), Paint("lamp", 3, noise=0, light=False, glow=True, decal=upright),
          faces={"north": None, "south": None, "up": None, "down": None})


def _model(v, kind):
    spec = LAMPS[v]
    # lift: a body that stands higher than it is drawn (vehicles_premium.Kit) takes its lamps up with it
    m = BModel(f"{v}_lamp_{kind}", shift=(8, 8 + spec.get("lift", 0), 8), scale=spec["scale"] * LAMP_SCALE, floor=None)
    if kind == "head":
        for l in spec["head"]:
            _lens(m, "lens", l, True, HOT, WARM)
            _halo(m, "glow", l, True, WARM, 2.5, 0.55)
            # the beam reaches 3 blocks, or as far as the model may (its bounds: model z -16 = drawn z -24 x shrink)
            room = 24 * spec["scale"] * LAMP_SCALE + l[4] - HALO_OUT - 1
            _beam(m, l, min(48.0, room), 7, 0.22)
    elif kind == "tail":
        for l in spec["tail"]:
            _lens(m, "lens", l, False, RED, RED_DIM)
    elif kind == "brake":
        for l in spec["tail"]:
            _lens(m, "lens", l, False, RED_HOT, RED)
            _halo(m, "glow", l, False, RED, 2.0, 0.5)
    elif kind == "reverse":
        for l in spec["tail"]:
            _lens(m, "lens", l, False, RED, RED_DIM)
        for l in spec["reverse"]:
            _lens(m, "white", l, False, WHITE, WHITE)
            _halo(m, "glow", l, False, WHITE, 1.5, 0.4)
    m.display = presets.furniture(gui_scale=0.4)
    return m


def check():
    """Warnings for lenses that no longer lie on a face of their body (the body was redrawn): [str]."""
    import importlib
    out = []
    for v, spec in LAMPS.items():
        mod, name = spec["body"]
        body = getattr(importlib.import_module("models." + mod), "ALL")[name]()
        boxes = [(body.to_drawn(e["from"]), body.to_drawn(e["to"])) for e in body.elements if not e.get("rotation")]
        for kind in ("head", "tail", "reverse"):
            for (x0, x1, y0, y1, z, _) in spec.get(kind, []):
                cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
                hit = any(abs((a[2] if kind == "head" else b[2]) - z) < 0.3 and a[0] - 0.3 <= cx <= b[0] + 0.3 and a[1] - 0.3 <= cy <= b[1] + 0.3
                          for a, b in boxes)
                if not hit:
                    out.append(f"{v} {kind} lamp at ({cx}, {cy}, {z}) is not on the body's face any more: models/vehicle_lights.py LAMPS")
    return out


ALL = {}
for _v, _spec in LAMPS.items():
    for _kind in ("head", "tail", "brake", "reverse"):
        if _kind == "head" and not _spec.get("head"):
            continue
        if _kind in ("tail", "brake") and not _spec.get("tail"):
            continue
        if _kind == "reverse" and not _spec.get("reverse"):
            continue
        ALL[f"{_v}_lamp_{_kind}"] = (lambda v=_v, k=_kind: _model(v, k))
