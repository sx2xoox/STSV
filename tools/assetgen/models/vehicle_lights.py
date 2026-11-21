"""
Lamps of the motor vehicles (탈것의 느낌, content/stsv/vehicle_feel.yml lights:): parts the plugin draws over a vehicle's
body (dev.stsv.core.vehicle.VehicleFeelView), full-bright, and swaps as the lamps change:

  <v>_lamp_head      headlamps on (it is dark around the vehicle and someone drives): a hot lens over each body lamp,
                     a soft glow round it and a faint beam ahead (see-through sheets fading out: see _beam)
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

One-sided light (the riders' eyes): a see-through glow or beam face is drawn only where every rider's eye is behind it
(eyes(): from the vehicle's seats in content/stsv/items/vehicles*.yml). Item-display models with half see-through
pixels are drawn in the translucent entity pass, which writes depth before the game draws translucent terrain (ice,
water, stained glass), so a beam between a rider's eye and the world hid the ice and water behind it (like tinted
glass: bake.outward_glass). Faces are culled from behind, so from the seats nothing see-through is drawn ahead, while
others still see the glow from the front and the beam from the side and above. A seat moved in the yml moves this
line too: rebuild the lamp models.
"""

import math
from functools import lru_cache
from pathlib import Path

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
    # vehicles_bus.py village_bus: the two big round headlamps it has for eyes, round tail lamps on the tail (the
    # reverse lamp under each)
    "village_bus": dict(scale=4.0, head=lens(5.5, 12.5, 9, 16, -48.5, True), tail=lens(12, 16, 10, 14, 48.5, True),
                        reverse=lens(12.5, 15.5, 7.5, 9.5, 48.5), body=("vehicles_road", "village_bus")),
    # vehicles_buses.py mini_bus: the round eyes on its snub nose, round tail lamps (the reverse lamp under each)
    "mini_bus": dict(scale=4.0, head=lens(6.5, 12.5, 9, 15, -38, True), tail=lens(11.5, 15.5, 13, 17, 38.5, True),
                     reverse=lens(12, 15, 9, 12, 38.5, True), body=("vehicles_road", "mini_bus")),
    # limousine_bus: a slim sharp headlamp each side, a slim red bar up each back corner (the reverse lamp at its foot)
    "limousine_bus": dict(scale=4.0, head=lens(7.5, 14.5, 10, 12, -68.5), tail=lens(13, 16.5, 10, 21, 68.5),
                          reverse=lens(13, 16.5, 8, 10, 68.5), body=("vehicles_road", "limousine_bus")),
    # vehicles_farm.py power_tiller: one round lamp on the hopper's front corner (the trailer's lamps are not drawn here)
    "power_tiller": dict(scale=2.0, head=lens(-4.9, -2.1, 26.1, 28.9, -16, True, False), body=("vehicles_farm", "power_tiller")),
    # tractor: two round lamps standing on the hood's front corners, tail lamps on the fenders' backs
    "tractor": dict(scale=2.0, head=lens(4.25, 6.75, 21.5, 24, -23.5, True), tail=lens(12, 14.5, 20, 22, 24.5),
                    body=("vehicles_farm", "tractor")),
    # combine: two work lamps under the canopy's front edge, tail lamps on the backs of the thresher and the grain tank
    "combine": dict(scale=2.0, head=lens(1.5, 4, 41.75, 42.75, -19, False, False) + lens(12.5, 15, 41.5, 42.5, -19, False, False),
                    tail=lens(10, 13, 21, 23, 30), body=("vehicles_farm", "combine")),
    # face), round tail lamps in chrome bezels on the rear fenders' ends
    "classic_roadster": dict(scale=2.0, head=lens(10.1, 13.9, 7.1, 10.9, -35.6, True),
                             tail=lens(10.6, 13.4, 5.85, 8.65, 34.5, True),
                             body=("vehicles_donation_classic_roadster", "classic_roadster")),
    # on the back's corners
    "retro_camper": dict(scale=2.0, head=lens(8, 12, 13.5, 17.5, -33.5, True), tail=lens(9.5, 12.5, 13.5, 16.5, 33.25, True),
                         body=("vehicles_donation_retro_camper", "retro_camper")),
    # whole width of the tail panel, split in the middle
    "muscle_car": dict(scale=2.0, head=lens(5, 9, 7.75, 11.75, -37.25, True) + lens(10, 14, 8, 12, -37.55, True),
                       tail=lens(1.25, 14, 10.5, 13, 38.5), body=("vehicles_donation_muscle_car", "muscle_car")),
    # sidecar's mudguard
    "sidecar_bike": dict(scale=2.0, head=lens(-9.35, -5.65, 14.65, 18.35, -14.5, True, False)
                         + lens(13.9, 15.1, 13.4, 14.6, 0.8, True, False),
                         tail=lens(-8.45, -6.55, 10.05, 11.95, 20.7, True, False) + lens(13.95, 15.05, 10.65, 11.75, 12.1, True, False),
                         body=("vehicles_donation_sidecar_bike", "sidecar_bike")),
    # the tail, each on its own 3D bar in front of a black visor / frame
    "concept_ev": dict(scale=2.0, head=lens(-15, 15, 5, 6, -39.5, False, False),
                       tail=lens(-15, 15, 11.5, 12.5, 39.5, False, False),
                       body=("vehicles_donation_concept_ev", "concept_ev")),
    # lamps on the tailgate's corners, joined by a thin light bar across the middle
    "sushi_carnival": dict(scale=2.0, head=lens(10.5, 15.25, 12, 14.25, -41.4),
                           tail=lens(9, 15.25, 18.5, 21.5, 41.4) + lens(-9, 9, 19.5, 20.5, 41.2, False, False),
                           body=("vehicles_donation_sushi_carnival", "sushi_carnival")),
    # vehicles_agent_sedan.py agent_sedan (비밀 임무 보상): slim LED eyes on the nose's upper corners; one thin red bar
    # across the tail with a taller lamp on each corner
    "agent_sedan": dict(scale=2.0, head=lens(9.5, 15.25, 11.5, 13.0, -42.4),
                        tail=lens(11.5, 15.25, 12.5, 15.0, 42.4) + lens(-11.5, 11.5, 13.25, 14.25, 42.3, False, False),
                        body=("vehicles_agent_sedan", "agent_sedan")),
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


# ------------------------------------------------------------------------------------------------
# The riders' eyes (one-sided light: see the module doc)
# ------------------------------------------------------------------------------------------------
ITEMS = Path(__file__).resolve().parents[3] / "content" / "stsv" / "items"
# a seated rider's eye above their seat (blocks): the seat's chair is a marker armor stand on a display, and a player
# sits 0.6 below what they ride (Player vehicle attachment) with their eyes 1.62 above their feet
EYE_OVER_SEAT = 1.02
# how far behind a face every eye must be (px), with the body nodding or leaning this much (degrees, on its springs)
EYE_MARGIN, EYE_TURN = 0.5, 3.0


@lru_cache(maxsize=None)
def _items():
    import yaml
    out = {}
    for f in sorted(ITEMS.glob("vehicles*.yml")):
        for k, v in (yaml.safe_load(f.read_text()) or {}).items():
            if isinstance(v, dict) and isinstance(v.get("vehicle"), dict):
                out[k] = v
    return out


def _turned(p, deg):
    """(right, up, forward) turned deg about the up axis (+ = to the right)."""
    a = math.radians(deg)
    return (p[0] * math.cos(a) + p[2] * math.sin(a), p[1], -p[0] * math.sin(a) + p[2] * math.cos(a))


@lru_cache(maxsize=None)
def eyes(v):
    """The eyes of every rider of the vehicles drawn with body v, in its drawn space (px; front = -z): from the seats
    of every item whose model is v. A seat on a trailer (seats[].unit) swings with it: its eye is taken with the
    trailer straight and turned to either side as far as the train lets it (train.jackknife)."""
    lift = LAMPS.get(v, {}).get("lift", 0)
    out = []
    for item in _items().values():
        if item.get("model") != v:
            continue
        veh = item["vehicle"]
        train = veh.get("train") or {}
        trailers = train.get("trailers") or []
        jk = float(train.get("jackknife", 45))
        for seat in veh.get("seats") or []:
            at = tuple(float(q) for q in seat["at"])
            pts = [at]
            for u in range(int(seat.get("unit", 0)), 0, -1):
                # this unit's axle sits `tongue` behind its hitch, on the unit in front of it
                t = trailers[u - 1]
                hitch, tongue = t["hitch"], float(t["tongue"])
                pts = [(hitch[0] + r[0], r[1], hitch[2] + r[2])
                       for p in pts for ang in (-jk, -jk / 2, 0, jk / 2, jk)
                       for r in [_turned((p[0], p[1], p[2] - tongue), ang)]]
            out += [(16 * p[0], 16 * (p[1] + EYE_OVER_SEAT) - lift, -16 * p[2]) for p in pts]
    if not out:
        raise ValueError(f"{v}: no seats found for its lamps (content/stsv/items/vehicles*.yml model: {v})")
    return tuple(out)


@lru_cache(maxsize=None)
def _seen_from(v):
    """The riders' eyes as the body sees them while it nods or leans (it turns about its ground centre; the seats do not)."""
    o = (0, -LAMPS.get(v, {}).get("lift", 0), 0)
    out = []
    for e in eyes(v):
        for a in (-EYE_TURN, 0, EYE_TURN):
            c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
            x, y, z = e[0] - o[0], e[1] - o[1], e[2] - o[2]
            out.append((e[0], o[1] + y * c - z * s, o[2] + y * s + z * c))     # nodding (about x)
            out.append((o[0] + x * c - y * s, o[1] + x * s + y * c, e[2]))     # leaning (about z)
    return tuple(out)


def _behind(v, normal, point):
    """True if every rider's eye is behind the plane through point facing normal (so they never see its face)."""
    n = math.sqrt(sum(q * q for q in normal))
    n = [q / n for q in normal]
    return all(sum(n[q] * (e[q] - point[q]) for q in range(3)) <= -EYE_MARGIN for e in _seen_from(v))


NORMAL = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}


def _one_sided(m, faces):
    """The light plane just added shows one side only on purpose: its other side is left open (model.validate)."""
    m.elements[-1]["open"] = {f for f, p in faces.items() if p is None}


def _lens(m, name, l, front, core, rim):
    x0, x1, y0, y1, z, _ = l
    zz = z - PROUD if front else z + PROUD
    m.box(name, (x0, y0, zz), (x1, y1, zz), _lens_paint(l, core, rim), faces=_face_only("north" if front else "south"))


def _halo(m, v, name, l, front, col, reach, peak):
    """A see-through glow round a lens, facing out (its face toward the vehicle would hang between a rider's eye and
    the world ahead or behind: dropped, see the module doc)."""
    x0, x1, y0, y1, z, _ = l
    zz = z - HALO_OUT if front else z + HALO_OUT
    left, right = x0 - reach, x1 + reach
    if x0 > 0:                       # one of a pair: its glow stays on its own side (the two would overlap in the middle)
        left = max(left, 0.05)
    elif x1 < 0:
        right = min(right, -0.05)
    paint = _glow_paint(l, col, reach, peak)
    faces = {f: (paint if f in ("north", "south") and _behind(v, NORMAL[f], (x0, y0, zz)) else None) for f in NORMAL}
    if any(faces.values()):
        _one_sided(m.box(name, (left, y0 - reach, zz), (right, y1 + reach, zz), paint, faces=faces), faces)


# the slopes a beam's lit sheet may fall at (model rotations: 22.5 degree steps), gentlest first
RAMPS = (22.5, 45)


def _beam(m, v, l, length, spread, peak):
    """A faint beam ahead of a headlamp, seen only from outside (see the module doc): an upright sheet along each side
    of the lamp that faces away from every rider (the outer side of a lamp at a corner; both sides of a lamp in the
    middle with its rider right behind it), spreading down more than up, and a lit sheet falling from the lamp to
    the ground ahead (the light on the road), facing up and forward, at the gentlest slope that still keeps every
    rider's eye behind it (a high driver over a short nose: buses, trucks, scooters, none). All fade out ahead."""
    x0, x1, y0, y1, z, _ = l
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    w0, h0 = (x1 - x0) / 2, (y1 - y0) / 2
    z0, z1 = z - HALO_OUT - length, z - HALO_OUT

    def alpha(t, across, half):
        if abs(across) > half or t >= 1:
            return 0
        edge = 1 - abs(across) / max(half, 1e-6)
        return peak * (1 - t) ** 1.4 * min(1.0, edge * 2.5)

    def side(c):                       # an upright sheet: across = y (it spreads down more than up: aimed low)
        t = (z1 - c.p[2]) / length
        d = c.p[1] - cy
        half = h0 + spread * t * (0.55 if d > 0 else 1.0)
        return ("raw", (*BEAM, int(round(alpha(t, d, half) * 255))))
    reach = spread + max(w0, h0)
    glow = dict(noise=0, light=False, glow=True)
    for f, edge in (("east", x1), ("west", x0)):
        # at the lens' edge, or up to 2 px further out where a rider sits just behind it (the scooter's)
        for x in (edge + NORMAL[f][0] * k * 0.5 for k in range(5)):
            if _behind(v, NORMAL[f], (x, cy, z1)):
                faces = {g: None for g in NORMAL if g != f}
                _one_sided(m.box("beam_side", (x, cy - reach, z0), (x, cy + reach, z1), Paint("lamp", 3, decal=side, **glow),
                                 faces=faces), faces)
                break
    # the lit sheet: drawn level at the lamp's height, then turned down about its near edge
    ground = -LAMPS[v].get("lift", 0) + 0.25
    for slope in RAMPS:
        s, k = math.sin(math.radians(slope)), math.cos(math.radians(slope))
        if not _behind(v, (0, k, -s), (cx, cy, z1)):
            continue
        run = min(length, (cy - ground) / s)
        if run < 4:
            break

        def road(c, run=run):          # across = x, widening as it goes (still lit where it meets the road)
            t = (z1 - c.p[2]) / run
            return ("raw", (*BEAM, int(round(alpha(0.8 * t, c.p[0] - cx, w0 + spread * t) * 255))))
        left, right = cx - reach, cx + reach
        if cx > 0.5:                   # a side lamp's sheet stays on its own side (the pair's would overlap in the middle)
            left = max(left, 0.05)
        elif cx < -0.5:
            right = min(right, -0.05)
        m.box("beam_road", (left, cy, z1 - run), (right, cy, z1), Paint("lamp", 3, decal=road, **glow),
              faces={g: None for g in NORMAL if g != "up"}, rotation=("x", -slope, (cx, cy, z1)))
        break


def _model(v, kind, name=None, hot=None, warm=None):
    """name, hot, warm: the headlamps in another colour (the tuning shop: models/vehicle_paints.py)."""
    spec = LAMPS[v]
    hot, warm = hot or HOT, warm or WARM
    # lift: a body that stands higher than it is drawn (vehicles_premium.Kit) takes its lamps up with it
    m = BModel(name or f"{v}_lamp_{kind}", shift=(8, 8 + spec.get("lift", 0), 8), scale=spec["scale"] * LAMP_SCALE, floor=None)
    if kind == "head":
        for l in spec["head"]:
            _lens(m, "lens", l, True, hot, warm)
            # reach: a body drawn at 32 px a block (the donation cars) draws its glow and beam twice as big, so they
            # come out as large in the world as the other cars'
            reach = spec.get("reach", 1.0)
            _halo(m, v, "glow", l, True, warm, 2.5 * reach, 0.55)
            # the beam reaches 3 blocks, or as far as the model may (its bounds: model z -16 = drawn z -24 x shrink)
            room = 24 * spec["scale"] * LAMP_SCALE + l[4] - HALO_OUT - 1
            _beam(m, v, l, min(48.0 * reach, room), 7 * reach, 0.22)
    elif kind == "tail":
        for l in spec["tail"]:
            _lens(m, "lens", l, False, RED, RED_DIM)
    elif kind == "brake":
        for l in spec["tail"]:
            _lens(m, "lens", l, False, RED_HOT, RED)
            _halo(m, v, "glow", l, False, RED, 2.0, 0.5)
    elif kind == "reverse":
        for l in spec["tail"]:
            _lens(m, "lens", l, False, RED, RED_DIM)
        for l in spec["reverse"]:
            _lens(m, "white", l, False, WHITE, WHITE)
            _halo(m, v, "glow", l, False, WHITE, 1.5, 0.4)
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
