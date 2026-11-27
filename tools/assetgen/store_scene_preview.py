"""
"Game screen" previews of the GS26 편의점 mid-shift (nobody can launch a client here), for the 전면 점검
(docs/plans/편의점_점검.md): the store as StoreBuilder builds it and everything StoreModule shows on a shift, seen from
where the players really stand.

    python3 tools/assetgen/store_scene_preview.py            # docs/previews/store_scene_*.png
                                                             # (needs: cd tools/assetgen/render && npm install)

What is drawn as the game draws it (read-only: nothing in the repository is changed but the pictures):
  * the building: StoreLayout.ground / shell / inside / outside, ported below (plain-coloured blocks as gamble_hall_preview
    draws them; glass see-through; the iron door's slab where a door facing north has it; bell, lever, cauldron, lanterns).
  * the furniture: economy/content/economy/models/store_*.bbmodel as they ship, placed as FurnitureModule places them
    (StoreLayout.fit ported: the anchor block from the cells of items/store.yml, the display at the anchor's centre,
    modelToWorld = the item display's half turn then the yaw, the furniture's scale).
  * the shift's displays, at the numbers read from StoreLayout.java / StoreModule.java (regex; the script stops when the
    source no longer matches): the counter's things (slots, scale 0.75), where they go when scanned, the notes, the card,
    the POS keys (BUTTON 0.6), the till's screen (billboard CENTER, 0.5), the customer display's text (FIXED, 0.22), the
    green answer buttons (0.45), the ID card panel (0.38), the microwave's text (0.45), the 폐기 tag (0.5), the delivery
    boxes, a spill, the cups on the eat bar, the 이달의 점장 line, the built labels.
  * text displays: gamble_scene_preview's (the 26.3 client's rules: 0.025 block a text px at scale 1, lines 10 px, the
    background's bottom on the display's position; billboards turned with each camera). Lines wrap at 200 px as the
    game wraps them.
  * customers: the vanilla player model in bus skins, standing at the layout's nodes (queue, browse spots, eat bar);
    their speech bubbles where Bubbles puts them (2.35 over the feet, raised over the till's screen / the buttons).
  * cameras: a standing player's eye 1.62 over the floor, FOV 70 (the client's default), 1600 x 900.

The coloured rings and names over the pictures are this tool's (the anchors the Java uses), not in the game.
"""

import base64
import io
import json
import math
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
import yaml
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import dock_preview as DP          # noqa: E402
import gamble_scene_preview as GSP  # noqa: E402

REPO = HERE.parent.parent
ECO = REPO / "economy"
SRC = ECO / "src" / "main" / "java" / "dev" / "stsv" / "economy" / "store"
ITEMS = ECO / "content" / "economy" / "items" / "store.yml"
OUT = REPO / "docs" / "previews"
BUILD = HERE / "build"
LABEL_FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
PX = 16.0
W, H = 1600, 900
EYE = 1.62

# ------------------------------------------------------------------------------------------------
# StoreLayout, ported (the plan's numbers) and read (the anchors)
# ------------------------------------------------------------------------------------------------

LOT, HALF, FRONT, BACK, ROOF = 8, 6, 3, 15, 3
TOP = 15.25 / 16
DOOR0, DOOR1 = 4, 5


def layout_blocks():
    b = {}

    def put(x, y, z, name):
        b[(x, y, z)] = name
    for x in range(-LOT, LOT + 1):
        for z in range(0, BACK + 2):
            walk = z < FRONT
            shop = abs(x) <= HALF and FRONT <= z <= BACK
            if walk:
                g = "smooth_stone" if x % 3 == 0 else "polished_andesite"
            elif shop:
                g = "white_concrete" if (x + z) % 2 == 0 else "light_gray_concrete"
            else:
                g = "grass_block"
            put(x, -1, z, g)
    for x in range(-LOT, LOT + 1):
        put(x, -1, 0, "stone_bricks")
    for z in range(FRONT, BACK + 1):
        for x in (-HALF, HALF):
            for y in range(ROOF):
                put(x, y, z, "green_concrete" if y == ROOF - 1 else "white_concrete")
    for x in range(-HALF, HALF + 1):
        for y in range(ROOF):
            put(x, y, BACK, "green_concrete" if y == ROOF - 1 else "white_concrete")
    for x in range(-HALF + 1, HALF):
        for y in range(ROOF):
            put(x, y, FRONT, "glass")
    for x in (DOOR0, DOOR1):
        b.pop((x, 0, FRONT), None)
        b.pop((x, 1, FRONT), None)
        put(x, 2, FRONT, "green_concrete")
    for x in (-HALF, HALF):
        for y in range(ROOF):
            put(x, y, FRONT, "green_concrete")
    for x in range(-HALF, HALF + 1):
        for z in range(FRONT, BACK + 1):
            lamp = abs(x) < HALF and FRONT < z < BACK and (x + 5) % 3 == 1 and (z - 4) % 3 == 1
            put(x, ROOF, z, "sea_lantern" if lamp else "white_concrete")
        put(x, ROOF, FRONT, "green_concrete")
        put(x, ROOF + 1, FRONT, "green_concrete" if abs(x) == HALF else "white_concrete")
    put(DOOR1, 0, BACK, "iron_door_lower")
    put(DOOR1, 1, BACK, "iron_door_upper")
    put(DOOR0, 2, FRONT + 1, "bell")
    put(-3, 0, 4, "white_concrete")
    put(-3, 0, 11, "white_concrete")
    put(-3, 0, 12, "white_concrete")
    put(-4, 1, BACK - 1, "lever")
    put(2, 0, 2, "cauldron")
    put(-7, 0, 2, "lantern")
    put(7, 0, 2, "lantern")
    return b


# (role, item, x0, z0, x1, z1, y, yaw): StoreLayout.inside / outside / shell
PIECES = [
    ("sign", "store_sign", -2, FRONT - 1, 2, FRONT - 1, ROOF, 180),
    ("cig", "store_cig_shelf", -5, 6, -5, 8, 0, 90),
    ("counter", "store_counter", -3, 6, -3, 8, 0, 90),
    ("kettle", "store_kettle", -3, 5, -3, 5, 0, 90),
    ("microwave", "store_microwave", -3, 9, -3, 9, 0, 90),
    ("hanger", "store_hanger", -5, 10, -5, 10, 0, 90),
    ("waste", "store_waste", -3, 10, -3, 10, 0, 90),
    ("mop", "store_mop", -5, 13, -5, 13, 0, 90),
    ("order", "store_order_tablet", -5, 9, -5, 9, 0, 90),
    ("shelf_snack", "store_shelf_snack_full", 0, 6, 0, 8, 0, 0),
    ("shelf_noodle", "store_shelf_noodle_half", 0, 10, 0, 12, 0, 0),
    ("shelf_daily", "store_shelf_daily_full", 3, 6, 3, 8, 0, 0),
    ("shelf_meal", "store_shelf_meal_full", 3, 10, 3, 12, 0, 0),
    ("fridge_0", "store_fridge_full", -1, 14, 0, 14, 0, 180),
    ("fridge_1", "store_fridge_full", 1, 14, 2, 14, 0, 180),
    ("fridge_2", "store_fridge_full", 3, 14, 4, 14, 0, 180),
    ("freezer", "store_freezer", 1, 4, 2, 4, 0, 0),
    ("eatbar", "store_eat_bar", -2, 4, 0, 4, 0, 0),
    ("parasol_0", "store_parasol", -6, 1, -4, 1, 0, 0),
    ("parasol_1", "store_parasol", -2, 1, 0, 1, 0, 0),
]

NODES = {"out": (5.0, 1.0, 0), "door": (5.0, 4.5, 0), "fr": (4.5, 5.5, 0), "fm": (1.5, 5.5, 0), "fl": (-0.5, 5.5, 0),
         "mr": (4.5, 9.5, 0), "mm": (1.5, 9.5, 0), "ml": (-0.5, 9.5, 0), "br": (4.5, 13.5, 0), "bm": (1.5, 13.5, 0),
         "bl": (-0.5, 13.5, 0), "snack_a": (-0.5, 7.5, 90), "snack_b": (1.5, 7.5, 270), "noodle_a": (-0.5, 11.5, 90),
         "noodle_b": (1.5, 11.5, 270), "daily_a": (2.5, 7.5, 90), "daily_b": (4.5, 7.5, 270), "meal_a": (2.5, 11.5, 90),
         "meal_b": (4.5, 11.5, 270), "fridge_a": (0.0, 13.5, 0), "fridge_b": (2.0, 13.5, 0), "fridge_c": (4.0, 13.5, 0),
         "freezer_a": (2.0, 5.5, 180), "eat_0": (-1.5, 5.2, 180), "eat_1": (-0.5, 5.2, 180), "eat_2": (0.5, 5.2, 180)}
QUEUE = [(-1.5, 7.5, 270), (-1.5, 8.7, 180), (-1.5, 9.9, 180), (-1.5, 11.1, 180), (-1.5, 12.3, 180), (-1.5, 13.5, 180)]


def read_anchors():
    """The numbers the shift's displays use, from the Java (so the pictures follow the source)."""
    lay = (SRC / "StoreLayout.java").read_text(encoding="utf-8")
    mod = (SRC / "StoreModule.java").read_text(encoding="utf-8")

    def need(src, pat, what):
        m = re.search(pat, src, re.S)
        if not m:
            raise SystemExit(f"source changed, not found: {what}")
        return m

    f = lambda s: float(s)   # noqa: E731
    A = {}
    m = need(lay, r"slots = List\.of\((.*?)\);\s*List<Point> scanned", "slots")
    A["slots"] = [(f(a), TOP, f(b), f(c)) for a, b, c in re.findall(r"new Point\((-?[\d.]+), y, (-?[\d.]+), (\d+)\)", m.group(1))]
    m = need(lay, r"scanned\.add\(new Point\((-?[\d.]+), y, ([\d.]+) \+ i \* ([\d.]+), (\d+)\)\)", "scanned")
    A["scanned"] = [(f(m.group(1)), TOP, f(m.group(2)) + i * f(m.group(3)), f(m.group(4))) for i in range(5)]
    m = need(lay, r"change\.put\(top\[i\], new Point\((-?[\d.]+), ([\d.]+), ([\d.]+) \+ i \* ([\d.]+), (\d+)\)\);\s*"
                  r"change\.put\(low\[i\], new Point\((-?[\d.]+), ([\d.]+), ", "change buttons")
    x, yt, z0, dz, yaw, _, yl = m.groups()
    A["change"] = {}
    for i, (t, lo) in enumerate(((50000, 1000), (10000, 500), (5000, 100))):
        A["change"][t] = (f(x), f(yt), f(z0) + i * f(dz), f(yaw))
        A["change"][lo] = (f(x), f(yl), f(z0) + i * f(dz), f(yaw))
    m = need(lay, r"slots, List\.copyOf\(scanned\),\s*new Point\(([^)]*)\),\s*new Point\(([^)]*)\),\s*new Point\(([^)]*)\),\s*"
                  r"new Point\(([^)]*)\),\s*new Point\(([^)]*)\),\s*change,\s*new Point\(([^)]*)\),\s*new Point\(([^)]*)\),\s*"
                  r"new Point\(([^)]*)\),\s*List\.of\(((?:new Point\([^)]*\),?\s*)+)\),\s*new Point\(([^)]*)\)", "the layout's points")

    def pt(s):
        v = [eval(t.replace("y", str(TOP)), {}) for t in s.split(",")]   # "y + 0.005" and plain numbers
        return tuple(float(t) for t in v)
    for k, g in zip(("screen", "customerScreen", "card", "terminal", "cardKey", "give", "clear", "oven"), m.groups()[:8]):
        A[k] = pt(g)
    A["boxes"] = [pt(s) for s in re.findall(r"new Point\(([^)]*)\)", m.group(9))]
    A["worker"] = pt(m.group(10))
    A["button"] = f(need(mod, r"static final float BUTTON = ([\d.]+)f", "BUTTON").group(1))
    m = need(mod, r"sh\.shop\.at\((-?[\d.]+), ([\d.]+), ([\d.]+), 0\) : g\.m\.getLocation\(\)\.add\(0, ([\d.]+), 0\)", "the answer buttons")
    A["ask_till"] = (f(m.group(1)), f(m.group(2)), f(m.group(3)))
    A["ask_head"] = f(m.group(4))
    A["ask_step"] = f(need(mod, r"base\.clone\(\)\.add\(0, ([\d.]+) \* i\+\+, 0\)", "the buttons' step").group(1))
    # the ID: a text panel 0.3 over the counter (0.38); with the store_id_card model lying under it, 0.12 over (0.26)
    m = re.search(r"sh\.shop\.at\((-?[\d.]+), StoreLayout\.TOP \+ \(card \? ([\d.]+) : ([\d.]+)\), ([\d.]+), 270\)", mod)
    if m:
        A["id_card"] = (f(m.group(1)), TOP + f(m.group(2)), f(m.group(4)))
        A["id_scale"], A["id_model"] = 0.26, (f(m.group(1)), TOP, f(m.group(4)), 270.0)
    else:
        m = need(mod, r"sh\.shop\.at\((-?[\d.]+), StoreLayout\.TOP \+ ([\d.]+), ([\d.]+), 270\)", "the ID card")
        A["id_card"] = (f(m.group(1)), TOP + f(m.group(2)), f(m.group(3)))
        A["id_scale"], A["id_model"] = 0.38, None
    m = need(mod, r"return new double\[\]\{(-?[\d.]+) \+ seat, ([\d.]+), ([\d.]+)\};", "cupSpot")
    A["cup"] = (f(m.group(1)), f(m.group(2)), f(m.group(3)))
    m = need(mod, r"shelf\.x0\(\) \+ ([\d.]+), ([\d.]+), \(shelf\.z0\(\) \+ shelf\.z1\(\)\) / 2\.0 \+ ([\d.]+), 0\)", "the 폐기 tag")
    A["waste_tag"] = (3 + f(m.group(1)), f(m.group(2)), 11 + f(m.group(3)))
    bub = (SRC / "Bubbles.java").read_text(encoding="utf-8")
    A["bubble"] = f(need(bub, r"OVER_FEET = ([\d.]+);", "OVER_FEET").group(1))
    return A


# ------------------------------------------------------------------------------------------------
# drawing
# ------------------------------------------------------------------------------------------------

COLOURS = {"smooth_stone": (158, 158, 158), "polished_andesite": (132, 134, 133), "white_concrete": (207, 213, 214),
           "light_gray_concrete": (125, 125, 115), "grass_block": (106, 150, 64), "stone_bricks": (122, 121, 122),
           "green_concrete": (73, 91, 36), "glass": (196, 226, 236), "sea_lantern": (232, 244, 236), "iron": (198, 198, 198),
           "bell": (250, 212, 77), "lever": (110, 90, 60), "cauldron": (74, 72, 76), "lantern": (236, 190, 100),
           "road": (62, 62, 66), "dark": (40, 40, 44)}
GLOW = {"sea_lantern", "lantern"}


def P(lx, ly, lz):
    """A layout point (x right, y up, z forward) in the picture's world (blocks): x mirrored (a store facing south)."""
    return np.array([-lx, ly, lz], float)


def el(frm, to, tex, glow=False, opacity=None):
    e = DP._el(frm, to, tex, glow or tex in GLOW)
    if opacity is not None:
        e["opacity"] = opacity
    return e


def block_els(blocks, cut=None):
    els, full = [], {}
    for (x, y, z), name in blocks.items():
        if cut is not None and y >= cut:
            continue
        x0, y0, z0 = -(x + 1) * 16, y * 16, z * 16
        if name == "glass":
            els.append(el((x0, y0, z0 + 7), (x0 + 16, y0 + 16, z0 + 9), "glass", opacity=0.22))   # (drawn thin: one clear pane)
        elif name.startswith("iron_door"):
            els.append(el((x0, y0, z0 + 13), (x0 + 16, y0 + 16, z0 + 16), "iron"))   # facing=north, shut: the slab on the south edge
        elif name == "bell":
            els.append(el((x0 + 5, y0 + 4, z0 + 5), (x0 + 11, y0 + 11, z0 + 11), "bell"))
            els.append(el((x0 + 7, y0 + 11, z0 + 7), (x0 + 9, y0 + 16, z0 + 9), "dark"))
        elif name == "lever":
            els.append(el((x0 + 5, y0 + 4, z0 + 13), (x0 + 11, y0 + 12, z0 + 16), "smooth_stone"))
            els.append(el((x0 + 7, y0 + 9, z0 + 8), (x0 + 9, y0 + 11, z0 + 13), "lever"))
        elif name == "cauldron":
            els.append(el((x0, y0 + 3, z0), (x0 + 16, y0 + 16, z0 + 16), "cauldron"))
        elif name == "lantern":
            els.append(el((x0 + 5, y0, z0 + 5), (x0 + 11, y0 + 7, z0 + 11), "lantern"))
        else:
            full[(x, y, z)] = name
    for (x, y, z), name in sorted(full.items()):
        if full.get((x - 1, y, z)) == name:
            continue
        n = 1
        while full.get((x + n, y, z)) == name:
            n += 1
        x0 = -(x + n) * 16
        els.append(el((x0, y * 16, z * 16), (x0 + 16 * n, y * 16 + 16, z * 16 + 16), name))
    # the road in front, grass round the lot
    els.append(el((-30 * 16, -16.2, -8 * 16), (30 * 16, -16, 0), "road"))
    els.append(el((-30 * 16, -16.2, 17 * 16), (30 * 16, -16, 30 * 16), "grass_block"))
    els.append(el((9 * 16, -16.2, 0), (30 * 16, -16, 17 * 16), "grass_block"))
    els.append(el((-30 * 16, -16.2, 0), (-8 * 16, -16, 17 * 16), "grass_block"))
    return els


def offset(cell, yaw):
    """StoreLayout.offset."""
    r = math.radians(yaw)
    x, z = -cell[0], -cell[2]
    wx = x * math.cos(r) - z * math.sin(r)
    wz = x * math.sin(r) + z * math.cos(r)
    return (round(-wx), round(wz))


def fit(cells, p):
    """StoreLayout.fit: (anchor x, y, z, yaw, exact)."""
    _, _, x0, z0, x1, z1, y, yaw = p
    for t in (yaw, yaw + 90, yaw + 270, yaw + 180):
        o = [offset(c, t) for c in cells]
        bx0, bx1 = min(a for a, _ in o), max(a for a, _ in o)
        bz0, bz1 = min(a for _, a in o), max(a for _, a in o)
        if bx1 - bx0 == x1 - x0 and bz1 - bz0 == z1 - z0:
            return (x0 - bx0, y, z0 - bz0, t % 360, True)
    raise SystemExit(f"{p[0]} does not fit")


def place_model(name, pos, yaw, k, textures, out):
    """An item display's model (FIXED): its (8, 8, 8) on pos (picture world), its north side turned to yaw."""
    try:
        GSP.place(name, GSP.display_matrix(pos, GSP.facing(yaw), k), textures, out)
        return True
    except Exception as e:   # noqa: BLE001 - a model another tool is rewriting right now must not stop the pictures
        print(f"  (model {name}: {e})")
        return False


def furniture(textures, out, skip=()):
    items = yaml.safe_load(ITEMS.read_text(encoding="utf-8"))
    fits = {}
    for p in PIECES:
        role, item = p[0], p[1]
        if role in skip:
            continue
        spec = items[item.replace("_half", "_full") if item not in items else item]["furniture"]
        ax, ay, az, yaw, _ = fit(spec["cells"], p)
        k = (spec.get("scale") or [1])[0]
        place_model(item, P(ax + 0.5, ay + 0.5, az + 0.5), yaw, k, textures, out)
        fits[role] = (ax, ay, az, yaw)
    return fits


FONT = None


def wrap(text, limit=200.0):
    """TextDisplay's line width: a line longer than 200 px breaks at a space (plain text only)."""
    global FONT
    FONT = FONT or GSP.DefaultFont()
    GSP.FONT = FONT
    out = []
    for line in text.split("\n"):
        plain = re.sub(r"<[^>]*>", "", line)
        if "<" in line or FONT.width([(c, 0, False) for c in plain]) <= limit:
            out.append(line)
            continue
        cur = ""
        for word in plain.split(" "):
            t = (cur + " " + word).strip()
            if cur and FONT.width([(c, 0, False) for c in t]) > limit:
                out.append(cur)
                cur = word
            else:
                cur = t
        out.append(cur)
    return "\n".join(out)


class Scene:
    """Elements that do not turn with the camera, and the texts that do (made per camera)."""

    def __init__(self):
        self.textures = {k: DP._tex(v, edge=k not in ("grass_block", "road", "glass")) for k, v in COLOURS.items()}
        self.els = []
        self.texts = []     # (key, text, pos, scale, background, lift)
        self.fixed = []     # (key, text, pos, scale, background, yaw, shift)
        self.marks = []     # (name, pos, colour)
        self.n = 0

    def text(self, text, pos, scale, bg=(0, 0, 0, 64), lift=0.0):
        self.n += 1
        self.texts.append((f"t{self.n}", wrap(text), np.array(pos, float), scale, bg, lift))

    def fixed_text(self, text, pos, scale, bg, yaw, shift=(0, 0, 0)):
        self.n += 1
        self.fixed.append((f"f{self.n}", wrap(text), np.array(pos, float), scale, bg, yaw, shift))

    def mark(self, name, pos, colour=(255, 80, 80)):
        self.marks.append((name, np.array(pos, float), colour))

    def person(self, skin, lx, lz, yaw, **kw):
        GSP.seated_player(skin, P(lx, 0, lz), yaw, self.textures, self.els, standing=True, head_pitch=kw.pop("head_pitch", 0.0),
                          arms=kw.pop("arms", 0.0), **kw)

    def band(self, lx, lz, lo, hi):
        """Something a bubble keeps clear of (Bubbles.Band): the till's screen, the answer buttons, another bubble."""
        self.bands = getattr(self, "bands", []) + [(lx, lz, lo, hi)]

    def bubble(self, lx, lz, text, lift=None, over=2.35):
        """A customer's line where StoreBubbles puts it (Bubbles.place: over the head, raised clear of every band within
        2 blocks, under the ceiling 3.0); a line with no room is not shown (it waits 3 s, then is dropped)."""
        text = wrap(text)
        tall = 0.7 * (len(text.split("\n")) * 0.25 + 0.05)
        if lift is None:
            near = [b for b in getattr(self, "bands", []) if math.hypot(b[0] - lx, b[1] - lz) < 2.0]
            tries = sorted([over] + [b[3] + 0.08 for b in near if b[3] + 0.08 > over])
            y = next((t for t in tries if t + tall <= 3.0 + 1e-9 and not any(t < b[3] + 0.08 and t + tall + 0.08 > b[2] for b in near)), None)
            if y is None:
                print(f"  (no room for the bubble at ({lx}, {lz}): {text!r})")
                self.mark("말풍선 자리 없음 (3초 뒤 버려짐)", P(lx, 2.2, lz), (255, 60, 60))
                return
            lift = y - over
        self.band(lx, lz, over + lift, over + lift + tall)
        self.text("<black>" + text, P(lx, over, lz), 0.7, (255, 255, 255, 230), lift)

    def build(self, cam):
        textures = dict(self.textures)
        els = list(self.els)
        for key, text, pos, k, bg, yaw, shift in self.fixed:
            im, w, n = GSP.text_texture(text, background=bg)
            b = io.BytesIO()
            im.save(b, "PNG")
            textures[key] = "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()
            R = GSP.rot_y(-yaw)
            tp = 0.025 * k * PX
            M = GSP.trans(*(pos * PX)) @ R @ GSP.trans(shift[0] * PX - w / 2 * tp, shift[1] * PX, shift[2] * PX)
            els.append({"from": [0, 0, 0], "to": [(w + 1) * tp, 10 * n * tp, 0.001], "rotation": None, "glow": True, "opacity": 0.999,
                        "faces": {"south": {"uv": [0, 0, 16, 16], "texture": key}}, "matrix": [float(v) for v in M.reshape(-1)]})
        bills = []
        for key, text, pos, k, bg, lift in self.texts:
            im, w, n = GSP.text_texture(text, background=bg)
            bills.append(GSP.billboard(key, im, w, n, pos, k, cam, textures, lift=lift))
        bills.sort(key=lambda e: -e.pop("_depth"))
        return els + bills, textures


def render(name, scene, cam, title, sub, marks=True, size=(W, H)):
    els, textures = scene.build(cam)
    im = GSP.render("store_" + name, els, textures, cam, *size).convert("RGB")
    d = ImageDraw.Draw(im, "RGBA")
    small = ImageFont.truetype(LABEL_FONT, 17)
    if marks:
        f = np.array(cam["at"], float) - np.array(cam["eye"], float)
        f /= np.linalg.norm(f)
        placed = []
        for label, pos, colour in scene.marks:
            if (pos - np.array(cam["eye"])) @ f < 0.15:
                continue
            x, y = GSP.project(cam, pos, *size)
            if not (10 < x < size[0] - 10 and 10 < y < size[1] - 10):
                continue
            d.ellipse((x - 6, y - 6, x + 6, y + 6), outline=colour + (255,), width=2)
            tw = small.getlength(label)
            tx, ty = x + 9, y - 10
            while any(abs(ty - py) < 19 and tx < px + pw and px < tx + tw for px, py, pw in placed):
                ty += 19
            placed.append((tx, ty, tw))
            d.rectangle((tx - 2, ty - 1, tx + tw + 2, ty + 19), fill=(0, 0, 0, 150))
            d.text((tx, ty), label, fill=colour + (255,), font=small)
    big = ImageFont.truetype(LABEL_FONT, 28)
    sheet = Image.new("RGB", (size[0], size[1] + 84), (27, 29, 34))
    sheet.paste(im, (0, 84))
    d = ImageDraw.Draw(sheet)
    d.text((16, 8), title, fill=(245, 245, 245), font=big)
    d.text((16, 48), sub, fill=(185, 190, 200), font=ImageFont.truetype(LABEL_FONT, 19))
    out = OUT / f"store_scene_{name}.png"
    sheet.save(out, optimize=True)
    print("store scene", out)
    return sheet


def look(lx, lz, yaw, pitch, fov=70.0, up=EYE):
    """A camera at a standing player's eye over layout (lx, lz), looking a layout yaw (0 = +z, 90 = +x) and pitch (down +)."""
    eye = P(lx, up, lz)
    r, p = math.radians(yaw), math.radians(pitch)
    d = np.array([-math.sin(r) * math.cos(p), -math.sin(p), math.cos(r) * math.cos(p)])   # (the picture's x is mirrored)
    return GSP.camera(eye, eye + d * 4, fov=fov)


# ------------------------------------------------------------------------------------------------
# the shift
# ------------------------------------------------------------------------------------------------

GREEN = (31, 157, 85, 225)
SCREEN = (20, 40, 30, 200)


def base(A, cut=None, skip=()):
    s = Scene()
    s.els += block_els(layout_blocks(), cut)
    s.fits = furniture(s.textures, s.els, skip)
    # the built labels (FIXED)
    s.fixed_text("<#1f9d55><b>24시</b></#1f9d55><dark_gray> 연중무휴", P(2.5, 1.55, FRONT - 0.03), 0.6, (255, 255, 255, 192), 180)
    s.fixed_text("<white><b>STAFF ONLY", P(DOOR1 + 0.5, 1.2, BACK - 0.03), 0.5, (48, 48, 48, 192), 180)
    s.fixed_text("<white><b>차단기</b>", P(-3.5, 1.75, BACK - 0.03), 0.4, (48, 48, 48, 192), 180)
    s.fixed_text("<white><b>계산대", P(-4.4, 2.55, 8.2), 0.55, (31, 157, 85, 208), 90)
    s.fixed_text("<white>이달의 점장 <b>도토리</b>", P(0.5, 2.6, FRONT - 0.08), 0.5, (31, 157, 85, 200), 180)
    return s


def chores(s, A):
    """What stands about on a shift whatever the till does: the boxes, a spill, cups, the 폐기 tag, the microwave's text."""
    for i, kind in enumerate(("snack", "noodle", "daily", "meal", "drink")):
        x, y, z, yaw = A["boxes"][i]
        place_model(f"store_box_{kind}", P(x, y, z), yaw, 1.0, s.textures, s.els)
        s.mark(f"상자{i + 1}", P(x, y + 0.25, z), (255, 170, 60))
    place_model("store_spill", P(-0.5, 0.01, 9.5), 0, 1.0, s.textures, s.els)
    s.mark("쏟은 음료 (ml)", P(-0.5, 0.05, 9.5), (120, 200, 255))
    for seat in (0, 2):
        c = (A["cup"][0] + seat, A["cup"][1], A["cup"][2])
        place_model("store_trash_cup", P(*c), 180, 0.8, s.textures, s.els)
        s.mark(f"컵{seat}", P(c[0], c[1] + 0.1, c[2]), (120, 200, 255))
    s.text("<white><b>폐기 2", P(*A["waste_tag"]), 0.5, (200, 30, 30, 220))
    s.mark("폐기 딱지", P(*A["waste_tag"]), (255, 90, 90))
    s.mark("전자레인지 글자", P(*A["oven"][:3]), (255, 210, 80))
    s.mark("차단기", P(-3.5, 1.5, 14.8), (200, 200, 200))
    s.mark("옷걸이", P(-4.5, 1.3, 10.5), (120, 255, 160))
    s.mark("발주 단말기", P(-4.5, 1.1, 9.5), (120, 255, 160))
    s.mark("대걸레 통", P(-4.5, 0.6, 13.5), (120, 255, 160))
    s.mark("폐기 바구니", P(-2.5, 0.5, 10.5), (120, 255, 160))
    s.mark("온수기", P(-2.5, 1.4, 5.5), (120, 255, 160))
    s.mark("전자레인지", P(-2.5, 1.15, 9.5), (120, 255, 160))


def people(s, A, line=3, eaters=True, browsers=True, bubbles=True):
    skins = ["office_1.png", "student_1.png", "grandma_1.png", "farmer_1.png", "fisher_1.png", "drunk_1.png"]
    for i in range(line + 1):
        x, z, yaw = QUEUE[i]
        s.person(skins[i % len(skins)], x, z, yaw)
        s.mark(f"q{i}", P(x, 0.05, z), (255, 255, 120))
    if browsers:
        s.person("camper_1.png", *NODES["snack_b"])
        s.person("chatty_1.png", *NODES["meal_a"])
        s.person("courier_1.png", *NODES["fridge_b"])
    if eaters:
        s.person("drunk_2.png", *NODES["eat_0"])
        s.person("student_2.png", *NODES["eat_1"])
        for i in range(3):
            s.mark(f"eat_{i}", P(NODES[f"eat_{i}"][0], 0.05, NODES[f"eat_{i}"][1]), (255, 255, 120))


def till_scan(s, A, items=("ramen_red", "samgak_tuna", "banana_milk", "dosirak", "chips"), scanned=0):
    for i, it in enumerate(items):
        x, y, z, yaw = A["scanned"][i] if i < scanned else A["slots"][i]
        place_model(f"store_item_{it}", P(x, y, z), yaw, 0.75, s.textures, s.els)
        s.mark(f"찍음{i}" if i < scanned else f"물건{i}", P(x, y + 0.12, z), (255, 120, 220) if i < scanned else (255, 80, 80))
    x, y, z, _ = A["screen"]
    s.mark("계산대 화면", P(x, y, z), (90, 230, 140))
    x, y, z, yaw = A["customerScreen"]
    s.fixed_text("<#7dffb0>4,200원", P(x, y, z), 0.22, (0, 0, 0, 0), yaw, (0, -0.06, 0.01))
    s.mark("손님 화면 글자", P(x, y, z), (125, 255, 176))


def screen(s, A, text):
    x, y, z, _ = A["screen"]
    s.text(text, P(x, y, z), 0.5, SCREEN)
    s.band(x, z, y, y + 0.5 * (3 * 0.25 + 0.05))   # (StoreModule.fixedBands: three lines, always)


def cash(s, A):
    notes = [10000, 1000, 1000, 1000, 1000]
    for i, n in enumerate(notes):
        x, y, z, yaw = A["slots"][i]
        place_model(f"store_cash_{n}", P(x, y + 0.01 * i, z), yaw + 8 * i, 1.0, s.textures, s.els)
    for amount, (x, y, z, yaw) in A["change"].items():
        place_model(f"store_btn_change_{amount}", P(x, y, z), yaw, A["button"], s.textures, s.els)
        s.mark(f"{amount:,}", P(x, y, z), (255, 220, 90))
    x, y, z, yaw = A["give"]
    place_model("store_btn_give", P(x, y, z), yaw, A["button"], s.textures, s.els)
    s.mark("건네기", P(x, y, z), (90, 230, 140))
    x, y, z, yaw = A["clear"]
    place_model("store_btn_no", P(x, y, z), yaw, A["button"], s.textures, s.els)
    s.mark("다시 (store_btn_no)", P(x, y, z), (255, 120, 120))


def card(s, A):
    x, y, z, yaw = A["card"]
    place_model("store_card", P(x, y, z), yaw, 1.0, s.textures, s.els)
    s.mark("손님 카드", P(x, y + 0.02, z), (120, 220, 255))
    x, y, z, yaw = A["cardKey"]
    place_model("store_btn_card", P(x, y, z), yaw, A["button"], s.textures, s.els)
    s.mark("[카드] 버튼", P(x, y, z), (255, 220, 90))
    x, y, z, yaw = A["terminal"]
    s.mark("카드 단말기 (누르는 곳)", P(x, y, z), (120, 220, 255))


def ask_till(s, A, labels):
    x, y, z = A["ask_till"]
    for i, lab in enumerate(labels):
        s.text(f"<white>▶ {lab}", P(x, y + A["ask_step"] * i, z), 0.45, GREEN)
        s.band(x, z, y + A["ask_step"] * i, y + A["ask_step"] * i + 0.45 * 0.3)
        s.mark(f"응대 버튼 {i}", P(x, y + A["ask_step"] * i + 0.07, z), (60, 255, 150))


def worker_marks(s, A):
    x, _, z, _ = A["worker"]
    s.mark("근무 자리", P(x, 0.05, z), (255, 255, 255))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    A = read_anchors()
    wx, _, wz, _ = A["worker"]

    # ---- 1. the worker behind the counter, scanning
    s = base(A)
    chores(s, A)
    people(s, A)
    till_scan(s, A, scanned=2)
    screen(s, A, "<white>합계 <b>2,500원</b>\n<gray>2/5개 찍음")
    s.bubble(-1.5, 7.5, "이거 계산이요~")
    render("worker_front", s, look(wx, wz, 90, 8), "1. 계산 자리에서 정면 (눈높이 1.62, 시야 70, 고개 8° 숙임)",
           "StoreLayout.worker (-3.5, 7.5) · 손님 q0 · 계산대 화면 (-2.6, 2.1, 7.4) 은 화면 위쪽 밖 · 물건은 왼쪽 아래")
    render("worker_mat", s, look(wx, wz, 55, 32), "2. 물건을 찍으려고 왼쪽 아래를 봄 (왼쪽 35°, 아래 32°)",
           "물건 5칸 (slots) · 찍은 것이 가는 곳 (scanned, x -2.88) · 합계는 이 시야에 안 들어옴")
    render("worker_screen", s, look(wx, wz, 90, -30), "3. 합계를 읽으려고 고개를 듦 (위 30°)",
           "계산대 화면은 TextDisplay (0.5, 빌보드) 가 계산대 위 2.1칸 허공에: POS 모니터 (모델) 의 그림과 따로")

    # ---- 2. cash: the change buttons
    s = base(A)
    people(s, A, browsers=False, eaters=False)
    till_scan(s, A, scanned=5)
    cash(s, A)
    screen(s, A, "<white>합계 9,400원 · 받은 돈 14,000원\n<gold><b>거스름돈 4,600원</b>\n<gray>지금 쥔 돈 1,000원")
    s.bubble(-1.5, 7.5, "현금이요~")
    render("worker_cash_front", s, look(wx, wz, 90, 0), "4. 현금 계산: 정면 (고개 그대로)",
           "거스름돈 버튼 6 + 건네기 + 다시 (x -2.95, y 1.52 / 1.80) 가 얼굴 앞 0.55칸 · 손님 얼굴과 POS 를 가림")
    render("worker_cash_right", s, look(wx, wz, 140, 0), "5. 거스름돈 버튼을 보려고 오른쪽 50° 돌림",
           "50,000 · 1,000 버튼 (z 6.15) 은 근무 자리에서 오른쪽 68°: 버튼끼리 0.42 간격 < 너비 0.45 (겹침)")
    render("worker_cash_back", s, look(-4.0, wz, 110, 4), "6. 한 발 물러서서 (x -4.0, 담배 진열장에 등을 붙임)",
           "물러서도 버튼 줄 전체 + 화면 + 손님이 한 화면에 안 들어옴")

    # ---- 3. card + questions at the till
    s = base(A)
    people(s, A, browsers=False, eaters=False)
    till_scan(s, A, items=("beer", "chips", "soju"), scanned=3)
    card(s, A)
    screen(s, A, "<white>합계 <b>6,400원</b>\n<aqua>카드 → 단말기 우클릭")
    render("worker_card", s, look(wx, wz, 115, 14), "7. 카드 계산: 단말기 쪽 (오른쪽 25°, 아래 14°)",
           "손님 카드 (l.card -2.15, 6.4) 는 숙취해소제 선반 속 · [카드] 버튼 (-2.95, 1.52, 6.57) 은 POS 앞 허공")
    render("card_close", s, GSP.camera(P(-1.2, 1.75, 5.6), P(-2.3, 1.0, 6.6), fov=40), "7.-b 손님 쪽에서 본 계산대 끝 (확대)",
           "카드 · 단말기 · 숙취해소제 선반")
    s = base(A)
    people(s, A, browsers=False, eaters=False)
    till_scan(s, A, items=("beer", "chips"), scanned=0)
    ask_till(s, A, ["판매", "거절"])
    screen(s, A, "<yellow>손님 응대 중\n<gray>머리 위 버튼")
    x, y, z = A["id_card"]
    s.text("<dark_gray><b>주민등록증</b>\n<black>김민준\n<black>2009년생 <dark_gray>(올해 17살)\n<dark_gray>사진: 손님 얼굴 그대로", P(x, y, z), A["id_scale"],
           (228, 236, 246, 150 if A["id_model"] else 240))
    if A["id_model"]:
        place_model("store_id_card", P(*A["id_model"][:3]), A["id_model"][3], 1.0, s.textures, s.els)
    s.mark("신분증 글자판", P(x, y, z), (228, 236, 246))
    s.bubble(-1.5, 7.5, "(신분증을 꺼낸다) 여기요…")
    render("worker_id", s, look(wx, wz, 80, 4), "8. 신분증 확인: 응대 버튼 (계산대 끝 위) · 신분증 글자판 · 화면",
           "버튼 (-2.2, 1.75 + 0.3i, 8.85) · 신분증 TextDisplay (-2.45, 1.25, 6.95) · store_id_card 모델은 안 쓰임")

    # ---- 4. the customer's side, the door, outside
    s = base(A)
    chores(s, A)
    people(s, A)
    till_scan(s, A, scanned=5)
    cash(s, A)
    screen(s, A, "<white>합계 9,400원 · 받은 돈 14,000원\n<gold><b>거스름돈 4,600원</b>\n<gray>지금 쥔 돈 0원")
    s.person("univ_1.png", wx, wz, 90) if (GSP.SKINS / "univ_1.png").exists() else s.person("student_3.png", wx, wz, 90)
    worker_marks(s, A)
    s.bubble(-1.5, 7.5, "현금이요~")
    s.bubble(-1.5, 8.7, "줄이 안 줄어드네…")
    s.bubble(-1.5, 9.9, "아이고 다리야… 괜찮다 천천히 해")
    render("customer_side", s, look(-0.45, 5.9, 300, 4), "9. 손님 쪽 (과자 진열대 앞 통로에서 계산대를 봄)",
           "근무자 · 거스름돈 버튼 뒷면 · 계산대 화면 · 줄 (q0..q3) 말풍선")
    render("door", s, look(4.6, 3.2, 20, 4), "10. 문으로 들어서며",
           "문 (x 4..5, 문짝 없음) · 종 · 생활용품 / 도시락 진열대 · 오른쪽 통로")
    render("door_left", s, look(4.6, 4.6, 290, 4), "11. 문 안에서 왼쪽 (시식대 · 냉동고 · 계산대 쪽)",
           "시식대 손님은 의자 뒤 (z 5.2) 에 서 있음 · 컵 (y 1.0, z 4.55)")
    render("eatbar", s, GSP.camera(P(1.15, 1.3, 5.05), P(-1.5, 0.8, 4.45), fov=60), "12. 시식대 가까이",
           "컵라면 컵 cupSpot (-1.5 + n, 1.0, 4.55): 상판은 y 0.875, z 4.0..4.25")
    render("outside", s, look(1.5, -6.0, 0, -6), "13. 바깥 정면 (길 건너에서)",
           "간판 · 24시 · 이달의 점장 · 파라솔 2 · 휴지통 · 손님이 생기는 곳 out (5, 1)", marks=False)
    render("outside_door", s, look(7.5, -1.5, 335, 0), "14. 바깥 오른쪽 앞 (문 쪽)", "문 · 통유리 너머 계산대", marks=False)

    # ---- 5. the stocker
    for name, node, yaw, t in (("snack", "snack_a", 90, "과자 (왼쪽 통로)"), ("noodle", "noodle_a", 90, "라면 (왼쪽 통로, 줄 옆)"),
                               ("daily", "daily_b", 270, "생활용품 (오른쪽 통로)"), ("meal", "meal_b", 270, "도시락 (오른쪽 통로, 폐기 딱지)"),
                               ("fridge", "fridge_b", 0, "냉장고 (뒤 통로)")):
        x, z, _ = NODES[node]
        render(f"stock_{name}", s, look(x, z, yaw, 0), f"15. 진열: {t}", f"{node} ({x}, {z}) 에서 진열대를 봄 · 눈높이 1.62", marks=False)
    render("stock_boxes", s, look(4.4, 12.0, 28, 24), "16. 배송 상자 (창고 문 앞)", "상자 5개 (5.5, 0 / 0.5 / 1.0, 13.5 · 14.5) · STAFF ONLY · 냉장고 3")
    s = base(A)
    chores(s, A)
    people(s, A, eaters=False, browsers=False)
    till_scan(s, A, scanned=5)
    screen(s, A, "<white>결제 완료\n<gold>데우는 중 14초")
    s.text("<gold>데우는 중 14초", P(*A["oven"][:3]), 0.45, (0, 0, 0, 64))
    render("lane_back", s, look(-3.5, 6.2, 0, 10), "17. 근무 통로에서 뒤쪽 (전자레인지 · 단말기 · 옷걸이 · 폐기 · 대걸레 · 차단기)",
           "전자레인지 · 폐기 바구니 · 온수기는 yaw 90 = 앞면이 손님 쪽: 근무자는 뒷면을 봄")
    render("lane_microwave", s, look(-3.5, 9.5, 90, 12), "18. 전자레인지 앞 (근무 통로 x -4, z 9)", "데우기 글자 (-2.5, 1.95, 9.5)")
    render("queue_microwave", s, look(-0.6, 9.5, 270, 6), "19. 손님 줄 쪽에서 본 전자레인지 · 폐기 바구니", "앞면 (문 · 버튼 · 폐기 글자) 은 이쪽")

    # ---- 6. the secret's last beat: the figure by the parasol, from the till
    s2 = base(A)
    s2.person("worker_1.png" if (GSP.SKINS / "worker_1.png").exists() else "office_1.png", -1.0, 2.3, 0)
    s2.mark("새벽 손님 (-1.0, 2.3)", P(-1.0, 1.9, 2.3), (200, 200, 255))
    s2.person("nurse_1.png" if (GSP.SKINS / "nurse_1.png").exists() else "student_1.png", *QUEUE[0])
    render("secret_figure", s2, GSP.camera(P(wx, EYE, wz), P(-1.0, 1.4, 2.3), fov=70), "20. (비밀) 계산 자리에서 파라솔의 그를 봄",
           "StoreDay.figure (-1.0, 0, 2.3): 근무 자리에서는 온수기 뒤")

    # ---- 6b. a question in the aisle: the buttons over the customer's head, their name tag, their bubble
    s3 = base(A)
    tx, tz, tyaw = NODES["daily_a"]
    s3.person("tourist_1.png" if (GSP.SKINS / "tourist_1.png").exists() else "camper_1.png", tx, tz, tyaw)
    # the vanilla name tag: its top 0.5 over the entity's height (1.8), 9 text px down (assumed: check in game)
    s3.text("<white>외국인 여행객", P(tx, 2.3 - 0.25, tz), 1.0, (0, 0, 0, 64))
    for i, lab in enumerate(("생활용품 진열대", "라면 진열대")):
        y = A["ask_head"] + A["ask_step"] * i
        s3.text(f"<white>▶ {lab}", P(tx, y, tz), 0.45, GREEN)
        s3.band(tx, tz, y, y + 0.135)
        s3.mark(f"응대 버튼 {i} (y {y:.2f})", P(tx + 0.45, y + 0.07, tz), (60, 255, 150))
    s3.mark("이름표 (y 2.08~2.30)", P(tx - 0.5, 2.2, tz), (255, 255, 255))
    s3.bubble(tx, tz, "이거… 어디… 있어요? (사진을 보여 준다)")
    render("aisle_ask", s3, GSP.camera(P(2.5, EYE, 10.4), P(tx, 1.9, tz), fov=70), "20.-b 통로에서 응대 버튼 (관광객 · 취객 등 계산대 밖 손님)",
           "버튼 0 = 발 위 2.05, 버튼 1 = 2.35, 말풍선 2.35+ : 이름표 (머리 위 0.5) 와 겹치는 높이")

    # ---- 7. from above, roof off, every anchor named
    s = base(A, cut=ROOF)
    chores(s, A)
    people(s, A)
    till_scan(s, A, scanned=2)
    cash(s, A)
    card(s, A)
    ask_till(s, A, ["신분증 확인", "판매", "거절"])
    worker_marks(s, A)
    for k, (x, z, _) in NODES.items():
        if k[:2] not in ("ea",) and not k.startswith("q"):
            s.mark(k, P(x, 0.05, z), (150, 200, 255))
    cam = GSP.camera(P(0, 26, 6.2), P(0, 0, 8.4), fov=42)
    render("top", s, cam, "21. 지붕을 걷어 본 가게 (모든 기준점)", "노랑 = 줄 · 하늘 = 걷는 점 · 빨강 = 계산대 물건 · 초록 = 가구", size=(1600, 1300))
    cam = GSP.camera(P(-1.0, 3.6, 7.6), P(-2.6, 1.1, 7.4), fov=60)
    render("counter_top", s, cam, "22. 계산대 위 (비스듬히 위에서)", "매트 칸 · 찍은 줄 · 지폐 · 카드 · 버튼 · 응대 버튼이 한꺼번에 (실제로는 같이 뜨지 않음)")


if __name__ == "__main__":
    main()
