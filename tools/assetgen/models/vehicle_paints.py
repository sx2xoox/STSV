"""
What the tuning shop (튜닝샵, content/stsv/vehicle_tuning.yml) shows on a vehicle instead of its own parts:

  <body>_p_<paint>        the body in another paint (and, for a car with moving parts, each part: <body>_p_<paint>_<part>)
  <wheel>_w_<design>      a wheel of the same size as the vehicle's own, another design

Nothing here is drawn anew. A body in another paint is its own generator run again: the cars that take their colour
as an argument get it as one, the others name their body colour themselves and are drawn with that ramp remapped
(palette.remapped). So a variant can never drift from its body: redraw the body and its paints follow. A wheel
variant is the same builder (models.vehicles._wheel) at the stock wheel's size.

Not every vehicle is here: the hand cart, the ox cart and the boat are bare wood.

Imported by build.py like the other model modules (ALL).
"""

import functools
import math

from stsv_assets import palette, presets
from stsv_assets.bake import BModel, Paint
from models import vehicle_lights as L
from models import vehicles as V
from models import vehicles_road as R
from models import vehicles_farm as F
from models import vehicles_premium as P

# paint id -> the ramp it is drawn in (ids, names and prices: content/stsv/vehicle_tuning.yml)
PAINTS = {"white": "car_white", "pearl": "pearl", "silver": "steel", "black": "iron", "red": "tomato", "wine": "wine",
          "orange": "fox", "yellow": "corn", "green": "sign_green", "blue": "sapphire", "navy": "indigo", "purple": "grape",
          "pink": "pink"}


def _args(fn, **fixed):
    """A body whose generator takes its colour: (colour, name) -> the models it makes (the body, then its parts)."""
    def make(colour, name):
        made = fn(colour=colour, name=name, **fixed)
        return made.all() if isinstance(made, P.Kit) else {made.name: made}
    return make


def _remap(fn, own):
    """A body whose generator names its colour itself (own): drawn again with that ramp remapped."""
    def make(colour, name):
        with palette.remapped({own: colour}):
            m = fn()
            m.name = name
            m.bake()                    # painted now, while the ramp is remapped
        return {name: m}
    return make


# body model -> (its own colour, how it is drawn in another)
BODIES = {
    "police_car": ("car_white", _remap(V.police_car, "car_white")),
    "light_truck": ("truck_blue", _remap(V.light_truck, "truck_blue")),
    "golf_cart": ("cream", _remap(V.golf_cart, "cream")),
    "sports_car": ("tomato", _args(V.sports_car)),
    "tuner_car": ("grape", _args(V.tuner_car)),
    "hyper_car": ("sapphire", _args(P.hyper_kit)),
    "grand_sedan": ("pearl", _args(P.grand_kit)),
    "luxury_suv": ("leaf_dark", _args(P.suv_kit)),
    "cargo_bicycle": ("blacksteel", _remap(R.ALL["cargo_bicycle"], "blacksteel")),
    "delivery_scooter": ("vermilion", _remap(R.ALL["delivery_scooter"], "vermilion")),
    "atv": ("fox", _remap(R.ALL["atv"], "fox")),
    "cargo_van": ("car_white", _remap(R.ALL["cargo_van"], "car_white")),
    "box_truck": ("denim", _remap(R.ALL["box_truck"], "denim")),
    "power_tiller": ("vermilion", _remap(F.ALL["power_tiller"], "vermilion")),
    "tractor": ("fox", _remap(F.ALL["tractor"], "fox")),
}


def paints_of(body):
    """The paints a body is offered in: every paint but its own colour."""
    own = BODIES[body][0]
    return [p for p, ramp in PAINTS.items() if ramp != own]


@functools.lru_cache(maxsize=2)
def painted(body, paint):
    """{model name: model} of a body in a paint: the body first, then its moving parts."""
    return BODIES[body][1](PAINTS[paint], f"{body}_p_{paint}")


# wheel design id -> how models.vehicles._wheel draws it
WHEELS = {
    "five": dict(cap="blade", spokes=5),                          # a bright five-spoke
    "split": dict(cap="blade", spokes=10, lip="blade"),           # bright split spokes in a machined lip
    "turbine": dict(cap="steel", spokes=12, lip="blade"),         # a dark turbine in a bright lip
    "black": dict(cap="blacksteel", spokes=6, lip="blade"),       # black six-spoke, a bright lip
    "gold": dict(cap="gold", spokes=6),                           # gold six-spoke
    "white": dict(cap="car_white", spokes=5),                     # rally white
    "disc": dict(cap="blade", plain=True),                        # a plain polished disc
}
# stock wheel model -> (diameter, width, the rim's diameter): the size every design of it is drawn at
WHEEL_SIZES = {"wheel_sport": (12, 5, 9), "wheel_tuner": (12, 5, 10), "wheel_hyper": (12, 5, 10), "wheel_grand": (16, 5, 13),
               "wheel_suv": (26, 10, 13)}


def wheel(stock, design):
    d, width, rim = WHEEL_SIZES[stock]
    return V._wheel(f"{stock}_w_{design}", d, width, cap_d=rim, **WHEELS[design])


def _one(body, paint, name):
    return lambda: painted(body, paint)[name]


def _all():
    out = {}
    for body in BODIES:
        for paint in paints_of(body):
            # the names are known without drawing: the body, and a kit's parts as the stock kit names them
            base = f"{body}_p_{paint}"
            names = [base] + [base + n[len(body):] for n in V.ALL if n.startswith(body + "_") and n[len(body) + 1:] in PARTS.get(body, ())]
            for n in names:
                out[n] = _one(body, paint, n)
    for stock in WHEEL_SIZES:
        for design in WHEELS:
            out[f"{stock}_w_{design}"] = (lambda stock=stock, design=design: wheel(stock, design))
    for body in LAMP_BODIES:
        for colour in LAMP_COLOURS:
            out[f"{body}_lamp_head_c_{colour}"] = (lambda body=body, colour=colour: headlamps(body, colour))
    for colour in GLOWS:
        out[f"underglow_{colour}"] = (lambda colour=colour: underglow(colour))
    return out


# the moving parts of the bodies that have them (vehicles.yml movers), as their kits name them
# (the sedan's mascot is bright metal in every paint: the stock one is shown)
PARTS = {"hyper_car": ("door_l", "door_r", "wing"), "grand_sedan": ("door_fl", "door_fr", "door_rl", "door_rr"),
         "luxury_suv": ("door_fl", "door_fr", "door_rl", "door_rr")}

# ------------------------------------------------------------------------------------------------
# Lights: the headlamps in another colour, and a glow on the ground under the car
# ------------------------------------------------------------------------------------------------

# colour id -> (the hot middle, the colour round it and of the glow and the beam)
LAMP_COLOURS = {"ice": ((236, 250, 255), (150, 215, 255)), "gold": ((255, 244, 190), (255, 196, 70)),
                "violet": ((244, 226, 255), (186, 120, 255)), "green": ((226, 255, 226), (110, 240, 130)),
                "red": ((255, 226, 220), (255, 90, 80))}
# the cars whose headlamps can be changed (the event and the premium cars)
LAMP_BODIES = ("sports_car", "tuner_car", "hyper_car", "grand_sedan", "luxury_suv")


def headlamps(body, colour):
    hot, warm = LAMP_COLOURS[colour]
    return L._model(body, "head", name=f"{body}_lamp_head_c_{colour}", hot=hot, warm=warm)


# colour id -> the colour of the glow under the car
GLOWS = {"cyan": (70, 230, 255), "blue": (70, 120, 255), "purple": (170, 90, 255), "pink": (255, 80, 200), "red": (255, 60, 60),
         "orange": (255, 150, 40), "green": (80, 255, 110), "white": (240, 245, 255)}
GLOW_PX = 32          # the glow's plane is this many px square; the plugin stretches it to the car (VehicleTuning)


def underglow(colour):
    """A square of light on the ground, stretched by the plugin to a little more than the car's footprint: brightest
    in a band under the car's edge, fading out to the plane's edge and (less) in towards the middle, see-through."""
    rgb = GLOWS[colour]
    half = GLOW_PX / 2

    def decal(c):
        # how far in from the plane's edge, 0 (the edge) .. 1 (the middle)
        d = min(half - abs(c.p[0]), half - abs(c.p[2])) / half
        band = 0.3                                    # the car's edge lies about here
        a = (d / band) ** 1.5 if d < band else max(0.28, 1 - (d - band) / (1 - band) * 0.9)
        return ("raw", (*rgb, int(round(255 * 0.62 * max(0.0, min(1.0, a))))))
    m = BModel(f"underglow_{colour}", shift=(8, 8, 8), scale=2.0, floor=None)
    m.box("glow", (-half, 0, -half), (half, 0, half), Paint("lamp", 3, noise=0, light=False, glow=True, decal=decal),
          faces={f: None for f in ("north", "south", "east", "west")})
    m.display = presets.furniture(gui_scale=0.4)
    return m

ALL = _all()
