"""
Preview sheet of the motor vehicles' lamps (탈것의 느낌: models/vehicle_lights.py, content/stsv/vehicle_feel.yml; needs:
cd render && npm install, or a global playwright):

    python3 vehicle_lights_preview.py [out.png]          # docs/previews/vehicle_lights.png

Each vehicle as the plugin shows it (body at its body-scale, wheels, the lamp part over the body: drawn LAMP_SCALE
times smaller and shown that much larger), at night (the world darkened, the lamps full-bright as the plugin draws
them) from the front with its headlamps, from behind with its tail lamps, braking, and backing up (the trucks' and the
van's reverse lamps); then by day from close, braking, to check the lenses sit on the body's own lamps.
"""

import base64
import io
import sys
import types
from pathlib import Path

import yaml
from PIL import Image, ImageDraw, ImageEnhance, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import vehicle_preview as VP                # noqa: E402
from models import vehicles, vehicles_road, vehicles_farm, vehicle_lights as VL   # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
ITEMS = REPO / "content" / "stsv" / "items"
FEEL = REPO / "content" / "stsv" / "vehicle_feel.yml"
ORDER = ["delivery_scooter", "atv", "power_tiller", "tractor_trailer", "cargo_van", "light_truck", "box_truck", "police_car", "golf_cart"]


def items():
    out = {}
    for f in ("vehicles.yml", "vehicles_road.yml", "vehicles_farm.yml"):
        out.update(yaml.safe_load((ITEMS / f).read_text()))
    return out


ALL = {**vehicles.ALL, **vehicles_road.ALL, **vehicles_farm.ALL, **VL.ALL}
# the farm vehicles' item ids draw their towing unit (its body model)
ALL["tractor_trailer"] = vehicles_farm.ALL["tractor"]
VP.items = items
VP.V = types.SimpleNamespace(ALL=ALL)


def _darken(src, k=0.28):
    """A texture (data URL) as at night: darker and a little blue."""
    im = Image.open(io.BytesIO(base64.b64decode(src.split(",", 1)[1]))).convert("RGBA")
    r, g, b, a = im.split()
    r, g, b = (ch.point(lambda v, f=f: int(v * k * f)) for ch, f in ((r, 0.85), (g, 0.95), (b, 1.25)))
    out = io.BytesIO()
    Image.merge("RGBA", (r, g, b, a)).save(out, "PNG")
    return "data:image/png;base64," + base64.b64encode(out.getvalue()).decode()


def lamp_part(model, spec, ox):
    """A lamp model drawn like the body: shown at body-scale x LAMP_SCALE about the vehicle's ground centre."""
    data = ALL[model]().to_preview()
    k = spec.get("body-scale", 1) * VL.LAMP_SCALE
    els = VP._xf(data["elements"], lambda p: [(p[0] - 8) * k + ox[0], (p[1] - 8) * k, (p[2] - 8) * k + ox[2]])
    for e in els:
        if any(f["texture"].endswith("_cutout") for f in e["faces"].values()):
            e["opacity"] = 0.999     # the glows and beams: see-through (the game draws items translucent)
    return data["textures"], els


def whole(vid, ox, lamps, night):
    t, e, spec = VP.vehicle(vid, ox, riders=True, steer=0)
    t = dict(t)
    for p in spec.get("train", {}).get("parts", []):
        if p.get("spin") == "roll":     # the tractor's big rear wheels
            pt, pe = VP.part(p["model"], p["at"], ox)
            t.update(pt)
            e = e + pe
    t.update({"ref_rider": VP._solid((222, 152, 60)), "ref_passenger": VP._solid((120, 160, 120))})
    if night:
        t = {k: _darken(v) for k, v in t.items()}
    for name in lamps:
        lt, le = lamp_part(name, spec, ox)
        t.update(lt)
        e = e + le
    return t, e, spec


def main(out):
    feel = yaml.safe_load(FEEL.read_text())["vehicles"]
    names = {k: v["name"].split(">")[-1] for k, v in items().items()}
    cols = [("밤 · 앞 (전조등)", -32, 14, "head", True), ("밤 · 뒤 (미등)", 150, 16, "tail", True),
            ("밤 · 뒤 (브레이크)", 205, 14, "brake", True), ("밤 · 뒤 (후진)", 160, 22, "reverse", True),
            ("낮 · 뒤 (브레이크)", 180, 8, "brake", False)]
    cw, ch, head, top = 320, 230, 30, 46
    W = cw * len(cols)
    sheet = Image.new("RGB", (W, top + len(ORDER) * (ch + head)), (20, 22, 27))
    d = ImageDraw.Draw(sheet)
    font, small = ImageFont.truetype(VP.FONT, 19), ImageFont.truetype(VP.FONT, 15)
    d.text((12, 12), "탈것 불빛 (vehicle_feel.yml lights) · 밤: 주변을 어둡게, 불빛 부품은 늘 가장 밝게 · 전조등 = 어두우면, "
           "브레이크등 = 브레이크, 후진등 = 후진 (트럭·밴·탑차는 삐-삐-)", font=small, fill=(230, 228, 220))
    for r, vid in enumerate(ORDER):
        lamps = feel[vid]["lights"]
        for c, (label, az, el, kind, night) in enumerate(cols):
            y0 = top + r * (ch + head)
            d.rectangle((c * cw, y0, c * cw + cw - 2, y0 + head - 2), fill=(38, 41, 50))
            d.text((c * cw + 6, y0 + 5), (f"{names[vid]} · " if c == 0 else "") + label, font=small if c else font, fill=(240, 238, 230))
            show = []
            if kind == "head":
                show = [lamps["head"]] + ([lamps["tail"]] if "tail" in lamps else [])
            elif kind in lamps:
                show = [lamps[kind]] + ([lamps["head"]] if night else [])
            if not show or kind not in lamps and kind != "head":
                d.text((c * cw + 20, y0 + head + ch // 2 - 10), "없음", font=font, fill=(110, 112, 120))
                continue
            t, e, spec = whole(vid, (0, 0, 0), show, night)
            L, Wd, H = spec["size"]
            data = {"textures": t, "elements": e, "grid": False, "size": [cw, ch],
                    "views": [{"x": 0, "y": 0, "w": cw, "h": ch, "az": az, "el": el, "k": 2.5 if night else 1.9,
                               "radius": max(L, 1.6 * H, 2.4) * 8, "fov": 30, "far": 4000,
                               "center": [0, H * 6, (-L if kind == "head" else L) * 3]}]}
            im = VP.render(data, f"lamps_{vid}_{kind}_{int(night)}")
            sheet.paste(im, (c * cw, y0 + head))
    sheet.save(out, optimize=True)
    print(f"sheet   {out}")


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else PREVIEWS / "vehicle_lights.png")
