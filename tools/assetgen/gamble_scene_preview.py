"""
"Game screen" previews of a 도박장 섯다 table mid-hand (nobody can launch a client here), drawn from the shipped models,
the hall layout, the scene's own numbers and the real HUD layout:

    cd plugin && ./gradlew test --tests '*GambleHudPreviewTest'   # plugin/build/gamble-hud-preview.json (HudLayout's draws)
    cd economy && ./gradlew test --tests '*HallLayout*'           # economy/build/docks/gamble_hall.json (the hall, if missing)
    python3 tools/assetgen/gamble_scene_preview.py                # docs/previews/gamble_table_scene.png, gamble_hud.png
                                                                  # (needs: cd tools/assetgen/render && npm install)

The hand (README "도박장", 섯다 초보방 = the hall's table 0, ante 10): five players, 곰돌이 (seat 2) 삥 10, 치킨왕 (seat 3) 하프
+45, 달빛여우 (seat 4) 하프 +120 (Betting.amount: 45 + max(10, (105 + 45) / 2)); now the viewer's turn (도토리, seat 0, 38광땡):
pot 225, 120 to call, buttons 다이 · 콜 +120 · 따당 +240 · 하프 +292; 새벽낚시 (seat 1) still to act; seat 5 empty.

What is drawn as the game draws it:
  * models: economy/content/economy/models/gamble_*.bbmodel as they ship (the furniture at its display: anchor block
    centre, furniture scale; the table's displays by TableScene: position, TableArt.facing(yaw) then the item display's
    half turn (FIXED), scale; buttons tilted BUTTON_TILT). The hall: economy/build/docks/gamble_hall.json drawn by
    gamble_hall_preview (plain-coloured blocks).
  * text displays (billboard CENTER, background ARGB 110/0/0/0, shadow): the 26.3 client's rules (emotes.py header, read
    from DisplayRenderer): a text pixel is 0.025 block at scale 1, lines 10 px apart, the background (width + 1) x 10n px
    with its bottom edge on the display's position, the text centred one pixel right of it. The quad is turned with the
    camera (parallel to the screen). Letters: the pack's default font: Hangul from Neo둥근모 (16 px, drawn at half: 8 text
    px, advance width / 2 + 1, shadow and bold half a pixel), digits and signs in Minecraft's 5 x 7 style (approximated by
    hand: the game's ascii.png is not in the repository), ▶ ▮ from Unifont as the game falls back to it.
  * the positions and scales are read from TableScene.java / TableArt.java (regex) and hwatu.yml (hall.top), so they follow
    the source.
  * the seated players: the vanilla player model (0.9375 scale, riding pose: legs forward 81 degrees and 18 out, arms 36
    forward), feet 0.6 under the seat (the player's vehicle attachment), skins from the bus passengers; the viewer's eye 1.62
    over their feet = 1.52 over the floor on a 0.5 seat. FOV 70 (vertical), 1920 x 1080.
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
import gamble_hall_preview as GHP  # noqa: E402
import hud as HUD                  # noqa: E402
import neodgm                      # noqa: E402

REPO = HERE.parent.parent
ECO = REPO / "economy"
MODELS = ECO / "content" / "economy" / "models"
SKINS = ECO / "content" / "economy" / "assets" / "economy" / "textures" / "entity" / "bus"
SCENE_SRC = ECO / "src" / "main" / "java" / "dev" / "stsv" / "economy" / "hwatu" / "TableScene.java"
ART_SRC = ECO / "src" / "main" / "java" / "dev" / "stsv" / "economy" / "hwatu" / "TableArt.java"
HWATU_YML = ECO / "content" / "economy" / "hwatu.yml"
MESSAGES = ECO / "src" / "main" / "resources" / "messages.yml"
HUD_JSON = REPO / "plugin" / "build" / "gamble-hud-preview.json"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
OUT = REPO / "docs" / "previews"
LABEL_FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
UNIFONT = "/usr/share/fonts/opentype/unifont/unifont.otf"
PX = 16.0   # preview units a block


# ------------------------------------------------------------------------------------------------
# the numbers the plugin uses, read from its source
# ------------------------------------------------------------------------------------------------

def _num(src, pattern, n=1):
    m = re.search(pattern, src, re.S)
    if not m:
        raise SystemExit(f"not found in source: {pattern}")
    return [float(m.group(i + 1)) for i in range(n)] if n > 1 else float(m.group(1))


def source_numbers():
    s, a = SCENE_SRC.read_text(encoding="utf-8"), ART_SRC.read_text(encoding="utf-8")
    N = {}
    N["card"], N["button"], _, N["chips"] = _num(s, r"CARD_SCALE = ([\d.]+)f, BUTTON_SCALE = ([\d.]+)f, DIE_SCALE = ([\d.]+)f, CHIPS_SCALE = ([\d.]+)f", 4)
    N["button_half_k"] = _num(s, r"BUTTON_HALF = ([\d.]+) \* BUTTON_SCALE")
    N["tilt"] = _num(s, r"BUTTON_TILT = (-?[\d.]+)f")
    N["sign_scale"] = _num(s, r"SIGN_SCALE = ([\d.]+)f")
    N["sign_up"] = _num(a, r"SIGN_UP = ([\d.]+);")   # over the FLOOR (the table's anchor block), not its top
    N["pot_up"], N["pot_scale"] = _num(s, r"potLabel = text\(middle\(([\d.]+)\), label, ([\d.]+)f", 2)
    N["timer_up"] = _num(s, r"reach.timer\(\), 0, true\);\s*Location l = at\(s, ([\d.]+)\);")
    N["timer_scale"] = _num(s, r"timer = text\(l, c, ([\d.]+)f")
    N["pop_up"], N["pop_allin"], N["pop_scale"] = _num(s, r"TextDisplay pop = text\(at\(s, ([\d.]+)\), what, b.allIn\(\) \? ([\d.]+)f : ([\d.]+)f", 3)
    N["pop_rise"] = _num(s, r"new Transformation\(new Vector3f\(0, ([\d.]+)f, 0\), base.getLeftRotation\(\), base.getScale\(\), base.getRightRotation\(\)\), 30\)")
    N["label_up"], N["label_min"], N["label_scale"] = _num(s, r"text\(at\(s, lift \+ 2 \* half \+ \(chip \? [\d.]+ : ([\d.]+)\)\), label\(.*?Math.max\(([\d.]+), ([\d.]+) \* slot.scale\(\)\)", 3)
    N["btn_lift"] = _num(s, r"Location l = at\(s, chip \? [\d.]+ : half \+ ([\d.]+) \+ lift\);")
    r = _num(a, r"ROUND_REACH = new Reach\(([\d.]+), ([\d.]+), ([\d.]+), ([\d.]+), ([\d.]+)\)", 5)
    N["reach"] = dict(card=r[0], button=r[1], dice=r[2], timer=r[3], width=r[4])
    N["card_gap"], N["button_gap"] = _num(a, r"CARD_GAP = ([\d.]+), BUTTON_GAP = ([\d.]+)", 2)
    hall = yaml.safe_load(HWATU_YML.read_text(encoding="utf-8"))["hall"]
    N["top_round"], N["top_duel"] = float(hall["top"]["round"]), float(hall["top"]["duel"])
    N["top_poker"], N["top_baccarat"] = float(hall["top"]["poker"]), float(hall["top"]["baccarat"])
    # 세븐 포커 · 바카라 (TableArt)
    r = _num(a, r"POKER_REACH = new Reach\(([\d.]+), ([\d.]+), ([\d.]+), ([\d.]+), ([\d.]+)\)", 5)
    N["poker_reach"] = dict(card=r[0], button=r[1], dice=r[2], timer=r[3], width=r[4])
    r = _num(a, r"BACCARAT_REACH = new Reach\(([\d.]+), ([\d.]+), ([\d.]+), ([\d.]+), ([\d.]+)\)", 5)
    N["bac_reach"] = dict(card=r[0], button=r[1], dice=r[2], timer=r[3], width=r[4])
    N["poker_open"], N["poker_hidden_gap"], N["poker_open_gap"] = _num(a, r"POKER_OPEN = ([\d.]+), POKER_HIDDEN_GAP = ([\d.]+), POKER_OPEN_GAP = ([\d.]+);", 3)
    N["pcard"] = _num(a, r"PCARD_SCALE = ([\d.]+)f")
    (N["dealer_out"], N["hand_in"], N["hand_side"], N["hand_gap"], N["shoe_in"], N["shoe_side"]) = _num(
        a, r"DEALER_OUT = ([\d.]+), HAND_IN = ([\d.]+), HAND_SIDE = ([\d.]+), HAND_GAP = ([\d.]+), SHOE_IN = ([\d.]+), SHOE_SIDE = (-?[\d.]+)", 6)
    N["bac_card"] = _num(a, r"BAC_CARD_SCALE = ([\d.]+)f")
    N["bet_player"], N["bet_banker"], N["bet_tie"] = _num(a, r"BET_PLAYER = ([\d.]+), BET_BANKER = ([\d.]+), BET_TIE = ([\d.]+);", 3)
    N["chip_reach"] = _num(a, r"CHIP_REACH = ([\d.]+);")
    N["poker_lift"] = _num(a, r"POKER_BUTTON_LIFT = ([\d.]+);")
    N["poker_pile"] = _num(s, r"kind == TableKind.POKER \? CHIPS_SCALE \* ([\d.]+)f")
    N["bet_scale"] = _num(s, r"BET_SCALE = ([\d.]+)f")
    return N


def chip_level(pot, ante):
    """TableArt.chipLevel / chipScale."""
    if pot <= 0:
        return 0, 0.0
    antes = pot / max(1, ante)
    level = 1 if antes <= 2 else 2 if antes <= 4 else 3 if antes <= 7 else 4 if antes <= 12 else 5
    b = [0, 2, 4, 7, 12, 20]
    f = min(1.0, max(0.0, (antes - b[level - 1]) / (b[level] - b[level - 1])))
    return level, 1.0 + 0.15 * f


def yaw_toward(dx, dz):
    """TableArt.yawToward (Minecraft: yaw 0 = +z, 90 = -x)."""
    return (math.degrees(math.atan2(-dx, dz)) % 360 + 360) % 360


def along(center, seat, reach, side):
    """TableArt.along(..., towardSeat = true)."""
    dx, dz = seat[0] - center[0], seat[1] - center[1]
    ln = math.hypot(dx, dz) or 1.0
    ux, uz = dx / ln, dz / ln
    rx, rz = uz, -ux
    return (center[0] + ux * ln * reach + rx * side, center[1] + uz * ln * reach + rz * side, yaw_toward(ux, uz))


def row(n, gap):
    return [(i - (n - 1) / 2.0) * gap for i in range(n)]


# ------------------------------------------------------------------------------------------------
# matrices (blocks, Minecraft = three.js axes: +x east, +y up, +z south)
# ------------------------------------------------------------------------------------------------

def rot_y(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1.0]])


def rot_x(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1.0]])


def rot_z(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1.0]])


def trans(x, y, z):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def scale(k):
    return np.diag([k, k, k, 1.0])


def display_matrix(pos, left_rot, k):
    """An item display (FIXED, no translation): model px -> preview px. pos in blocks; the display's left rotation, scale,
    then the item display's half turn, then the model's (8, 8, 8) to the origin, 16 px a block."""
    return trans(*(np.array(pos) * PX)) @ left_rot @ scale(k) @ rot_y(180) @ trans(-8, -8, -8)


def facing(yaw):
    """TableArt.facing: rotationY(-yaw)."""
    return rot_y(-yaw)


# ------------------------------------------------------------------------------------------------
# the shipped models (.bbmodel, java_block)
# ------------------------------------------------------------------------------------------------

_bb = {}


def bbmodel(name):
    """(textures {key: data uri}, elements in model px) of economy/content/economy/models/<name>.bbmodel."""
    if name in _bb:
        return _bb[name]
    d = json.loads((MODELS / f"{name}.bbmodel").read_text(encoding="utf-8"))
    texs, sizes = {}, []
    for i, t in enumerate(d["textures"]):
        key = f"{name}#{i}"
        texs[key] = t["source"]
        sizes.append((t.get("uv_width") or t["width"], t.get("uv_height") or t["height"]))
    els = []
    for e in d["elements"]:
        if e.get("visibility") is False:
            continue
        faces = {}
        for f, fc in e["faces"].items():
            if fc.get("texture") is None:
                continue
            uw, uh = sizes[fc["texture"]]
            u1, v1, u2, v2 = fc["uv"]
            faces[f] = {"uv": [u1 / uw * 16, v1 / uh * 16, u2 / uw * 16, v2 / uh * 16], "texture": f"{name}#{fc['texture']}"}
        if not faces:
            continue
        rot = None
        r = e.get("rotation") or [0, 0, 0]
        for axis, ang in zip("xyz", r):
            if ang:
                rot = [axis, ang, list(e.get("origin", [8, 8, 8]))]
        els.append({"from": e["from"], "to": e["to"], "rotation": rot, "faces": faces,
                    "glow": bool(e.get("light_emission")), "shade": e.get("shade", True)})
    _bb[name] = (texs, els)
    return _bb[name]


def place(name, matrix, textures, out):
    tx, els = bbmodel(name)
    textures.update(tx)
    flat = [float(v) for v in matrix.reshape(-1)]
    for e in els:
        e = dict(e)
        e["matrix"] = flat
        out.append(e)


# ------------------------------------------------------------------------------------------------
# seated players (the vanilla player model in the riding pose)
# ------------------------------------------------------------------------------------------------

# (texture u, v), size (w, h, d) in skin px, the box in the player's own frame (x = the player's left, z forward)
PARTS = {
    "head": ((0, 0), (32, 0), (8, 8, 8), (-4, 24, -4), (0, 24, 0), 0.5),
    "body": ((16, 16), (16, 32), (8, 12, 4), (-4, 12, -2), None, 0.25),
    "rarm": ((40, 16), (40, 32), (4, 12, 4), (-8, 12, -2), (-5, 22, 0), 0.25),
    "larm": ((32, 48), (48, 48), (4, 12, 4), (4, 12, -2), (5, 22, 0), 0.25),
    "rleg": ((0, 16), (0, 32), (4, 12, 4), (-4, 0, -2), (-2, 12, 0), 0.25),
    "lleg": ((16, 48), (0, 48), (4, 12, 4), (0, 0, -2), (2, 12, 0), 0.25),
}


def _box_faces(u, v, w, h, d, key):
    q = lambda a, b, c, e: [a / 4, b / 4, c / 4, e / 4]   # 64 px skin -> 16 uv units
    return {
        "south": {"uv": q(u + d, v + d, u + d + w, v + d + h), "texture": key},              # front
        "north": {"uv": q(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h), "texture": key},  # back
        "west": {"uv": q(u, v + d, u + d, v + d + h), "texture": key},                       # the player's right
        "east": {"uv": q(u + d + w, v + d, u + 2 * d + w, v + d + h), "texture": key},       # the player's left
        "up": {"uv": q(u + d, v, u + d + w, v + d), "texture": key},
        "down": {"uv": q(u + d + w, v + d, u + d + 2 * w, v), "texture": key},
    }


def seated_player(skin_file, seat_pos, yaw, textures, out, head_pitch=18.0, head_turn=0.0, arms=-36.0, standing=False, rarm=None):
    """A player riding a seat display at seat_pos (blocks), facing yaw (Minecraft); standing: on their feet at seat_pos
    (the 바카라 dealer, a mannequin), rarm: the right arm's own swing (dealing)."""
    key = "skin:" + skin_file
    im = Image.open(SKINS / skin_file).convert("RGBA")
    if im.height == 32:
        im2 = Image.new("RGBA", (64, 64))
        im2.paste(im, (0, 0))
        im = im2
    b = io.BytesIO()
    im.save(b, "PNG")
    textures[key] = "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()
    feet = np.array(seat_pos, dtype=float) - np.array([0, 0 if standing else 0.6, 0])
    world = trans(*(feet * PX)) @ rot_y(-yaw) @ scale(0.9375)
    poses = {
        "head": rot_y(head_turn) @ rot_x(head_pitch),
        "rarm": (rarm if rarm is not None else rot_x(arms)) @ rot_z(4), "larm": rot_x(arms) @ rot_z(-4),
        "rleg": np.eye(4) if standing else rot_y(-18) @ rot_x(-81), "lleg": np.eye(4) if standing else rot_y(18) @ rot_x(-81),
    }
    for name, (base, over, (w, h, d), lo, pivot, inflate) in PARTS.items():
        m = world
        if pivot is not None:
            m = m @ trans(*pivot) @ poses[name] @ trans(*(-np.array(pivot)))
        flat = [float(v) for v in m.reshape(-1)]
        for layer, (u, v) in enumerate((base, over)):
            g = inflate if layer else 0
            out.append({"from": [lo[0] - g, lo[1] - g, lo[2] - g], "to": [lo[0] + w + g, lo[1] + h + g, lo[2] + d + g],
                        "rotation": None, "faces": _box_faces(u, v, w, h, d, key), "matrix": flat})


# ------------------------------------------------------------------------------------------------
# text displays: MiniMessage -> the default font's glyphs -> a billboard quad
# ------------------------------------------------------------------------------------------------

NAMED = {"black": 0x000000, "dark_blue": 0x0000AA, "dark_green": 0x00AA00, "dark_aqua": 0x00AAAA, "dark_red": 0xAA0000,
         "dark_purple": 0xAA00AA, "gold": 0xFFAA00, "gray": 0xAAAAAA, "dark_gray": 0x555555, "blue": 0x5555FF,
         "green": 0x55FF55, "aqua": 0x55FFFF, "red": 0xFF5555, "light_purple": 0xFF55FF, "yellow": 0xFFFF55, "white": 0xFFFFFF}


def minimessage(s, colour=0xFFFFFF):
    """Lines of (char, rgb, bold) from the few tags the 도박장 texts use (colours, #hex, bold / b; unclosed tags last)."""
    stack = [("", colour, False)]
    lines, cur = [], []
    pos = 0
    for m in re.finditer(r"<(/?)(#[0-9a-fA-F]{6}|[a-z_]+)>|\n", s):
        for ch in s[pos:m.start()]:
            cur.append((ch, stack[-1][1], stack[-1][2]))
        pos = m.end()
        if m.group(0) == "\n":
            lines.append(cur)
            cur = []
            continue
        close, tag = m.group(1), m.group(2)
        name = "bold" if tag in ("b", "bold") else tag
        if close:
            for i in range(len(stack) - 1, 0, -1):
                if stack[i][0] == name:
                    del stack[i:]
                    break
        elif name == "bold":
            stack.append(("bold", stack[-1][1], True))
        else:
            c = int(tag[1:], 16) if tag.startswith("#") else NAMED[tag]
            stack.append((name, c, stack[-1][2]))
    for ch in s[pos:]:
        cur.append((ch, stack[-1][1], stack[-1][2]))
    lines.append(cur)
    return lines


# Minecraft's ascii.png style (rows from the top of the line, 7 above the baseline): drawn by hand for the few signs used
MC = {
    "0": [".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."],
    "1": ["..#..", ".##..", "..#..", "..#..", "..#..", "..#..", "#####"],
    "2": [".###.", "#...#", "....#", "..##.", ".#...", "#...#", "#####"],
    "3": [".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."],
    "4": ["...##", "..#.#", ".#..#", "#...#", "#####", "....#", "....#"],
    "5": ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
    "6": ["..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."],
    "7": ["#####", "#...#", "....#", "...#.", "..#..", "..#..", "..#.."],
    "8": [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
    "9": [".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."],
    "+": [".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."],
    "-": [".....", ".....", ".....", "#####", ".....", ".....", "....."],
    "!": ["#", "#", "#", "#", "#", ".", "#"],
    "/": ["....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#...."],
    "(": ["..##", ".#..", "#...", "#...", "#...", ".#..", "..##"],
    ")": ["##..", "..#.", "...#", "...#", "...#", "..#.", "##.."],
    ",": [".", ".", ".", ".", ".", "#", "#", "#"],
    ".": [".", ".", ".", ".", ".", ".", "#"],
    ":": [".", "#", "#", ".", ".", "#", "#"],
    "·": [".", ".", ".", "#", ".", ".", "."],
    "?": [".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."],
}


class DefaultFont:
    """Glyphs in half text pixels: (bitmap rows as bools on a 2x grid, advance in text px, shadow / bold offset)."""

    def __init__(self):
        self.hangul = neodgm.load()
        self.uni = ImageFont.truetype(UNIFONT, 16)
        self.cache = {}

    def glyph(self, ch):
        if ch in self.cache:
            return self.cache[ch]
        if ch == " ":
            g = ([], 4.0, 1.0)
        elif ch in MC:
            rows = MC[ch]
            w = max(len(r) for r in rows)
            grid = [[c == "#" for c in r.ljust(w, ".") for _ in (0, 1)] for r in rows for _ in (0, 1)]
            g = (grid, w + 1.0, 1.0)
        else:
            cp = ord(ch)
            if cp in self.hangul:
                _, rows = self.hangul[cp]
                grid = [list(r) for r in rows]
            else:   # Unifont, the game's last fallback
                im = Image.new("L", (16, 16))
                ImageDraw.Draw(im).text((0, 0), ch, font=self.uni, fill=255)
                grid = [[im.getpixel((x, y)) > 127 for x in range(16)] for y in range(16)]
            cols = [x for x in range(len(grid[0])) if any(r[x] for r in grid)]
            if not cols:
                g = ([], 4.0, 0.5)
            else:
                left, right = cols[0], cols[-1]
                grid = [r[left:right + 1] for r in grid]
                g = (grid, float((right - left + 1) // 2 + 1), 0.5)
        self.cache[ch] = g
        return g

    def width(self, line):
        return sum(self.glyph(ch)[1] + (self.glyph(ch)[2] if bold else 0) for ch, _, bold in line)


FONT = None


def text_texture(text, K=8, background=(0, 0, 0, 110)):
    """The text display's picture (its background box, (w + 1) x 10n text px, K texels a text px) and (w, n)."""
    global FONT
    FONT = FONT or DefaultFont()
    lines = minimessage(text)
    widths = [FONT.width(l) for l in lines]
    w = max(widths) if widths else 0
    n = len(lines)
    W, H = int(math.ceil((w + 1) * K)), 10 * n * K
    im = Image.new("RGBA", (W, H), background)
    px = im.load()
    h2 = K // 2

    def blit(grid, x, y, rgb):
        # grid in half text px; (x, y) text px from the background's top-left
        for gy, r in enumerate(grid):
            for gx, on in enumerate(r):
                if on:
                    x0, y0 = int(round((x + gx / 2) * K)), int(round((y + gy / 2) * K))
                    for yy in range(y0, min(H, y0 + h2)):
                        for xx in range(x0, min(W, x0 + h2)):
                            px[xx, yy] = rgb + (255,)
    for li, line in enumerate(lines):
        x = 1 + (w - widths[li]) / 2   # the text is one px right of the background's left edge
        for pass_ in ("shadow", "text"):
            pen = x
            for ch, c, bold in line:
                grid, adv, off = FONT.glyph(ch)
                rgb = ((c >> 16) & 255, (c >> 8) & 255, c & 255)
                if pass_ == "shadow":
                    rgb = tuple(v // 4 for v in rgb)
                    dx = dy = off
                else:
                    dx = dy = 0
                for b in ((0, off) if bold else (0,)):
                    blit(grid, pen + dx + b, li * 10 + dy, rgb)
                pen += adv + (off if bold else 0)
    return im, w, n


def billboard(key, im, w, n, pos, k, cam, textures, lift=0.0):
    """The text display at pos (blocks), scale k, turned with the camera (billboard CENTER): an element over its
    background box (x -w/2 .. 1 + w/2, y 0 .. 10n text px, 0.025 block each at scale 1)."""
    b = io.BytesIO()
    im.save(b, "PNG")
    textures[key] = "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()
    f = np.array(cam["at"], float) - np.array(cam["eye"], float)
    f /= np.linalg.norm(f)
    r = np.cross(f, [0, 1, 0])
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    nrm = -f
    tp = 0.025 * k * PX
    origin = np.array(pos, float) * PX + u * lift * PX
    x0 = -w / 2 * tp
    M = np.eye(4)
    M[:3, 0], M[:3, 1], M[:3, 2] = r, u, nrm
    M[:3, 3] = origin + r * x0
    return {"from": [0, 0, 0], "to": [(w + 1) * tp, 10 * n * tp, 0.001], "rotation": None, "glow": True, "opacity": 0.999,
            "faces": {"south": {"uv": [0, 0, 16, 16], "texture": key}}, "matrix": [float(v) for v in M.reshape(-1)],
            "_depth": float(np.dot(origin - np.array(cam["eye"]) * PX, f))}


# ------------------------------------------------------------------------------------------------
# the scene
# ------------------------------------------------------------------------------------------------

def P(lx, ly, lz):
    """A hall layout point (block space, mirrored: gamble_hall_preview) in world blocks."""
    return np.array([-lx, ly, lz], float)


def messages():
    return yaml.safe_load(MESSAGES.read_text(encoding="utf-8"))


def raw(msgs, key, **kw):
    s = msgs
    for k in key.split("."):
        s = s[k]
    for k, v in kw.items():
        s = s.replace("{" + k + "}", str(v))
    return s


HAND_LATE = {
    "ante": 10, "pot": 225, "me": 0, "bettor": 4, "turn_left": 14, "turn_total": 20,
    # seat -> (name, skin); seat 0 is the viewer
    "seats": {0: ("도토리", "student_1.png"), 1: ("새벽낚시", "camper_1.png"), 2: ("곰돌이", "office_1.png"),
              3: ("치킨왕", "chicken_1.png"), 4: ("달빛여우", "chatty_1.png")},
    "buttons": [("die", 0), ("call", 120), ("ddadang", 240), ("half", 292)],
    "cards": ("3a", "8a"), "street": 2,
}
# the same table at the first turn of the hand (the viewer first to act): one card each (the first street), only the
# antes in (5 x 10), nothing bet yet
HAND_EARLY = dict(HAND_LATE, pot=50, bettor=None, turn_left=18, street=1,
                  buttons=[("die", 0), ("check", 0), ("ping", 10), ("half", 25)])   # 하프: 0 + max(10, 50 / 2)


KINDS = {"economy:gamble_table_round": "round", "economy:gamble_table_duel": "duel", "economy:poker_table": "poker",
         "economy:baccarat_table": "baccarat"}


def build(N, msgs, hall, cam_for_text, viewer_body=False, cut=None, hand=None, top_fix=None, extra=None, busy=()):
    """busy: tables (index) the `extra` hand draws itself (no waiting sign); extra(N, msgs, tables, text, els, textures)."""
    """Elements and textures of the whole scene, billboards turned for this camera."""
    HAND = hand or HAND_LATE
    textures = {k: DP._tex(v, edge=k not in ("water", "grass", "road", "felt", "light", "fire")) for k, v in GHP.COLOURS.items()}
    els = GHP.blocks(hall, 0, cut) + GHP.surroundings(0) + GHP.labels(hall, 0, textures, cut)
    # the props of the hall (the shipped models, as HallBuilder spawns them: entity yaw, FIXED (a half turn), translation
    # 0.5 x scale up so the model's floor is on the point)
    for x, y, z, item, k, yaw in hall["props"]:
        if cut is not None and y >= cut:
            continue
        name = item.split(":")[1].removeprefix("model/")
        if not (MODELS / f"{name}.bbmodel").exists():
            els += GHP.prop(item, x, y, z, yaw, 0, {})
            continue
        at = P(x, y, z) * PX
        m = trans(*at) @ rot_y(180 - yaw) @ scale(k) @ trans(-8, 0, -8)
        place(name, m, textures, els)
    texts = []
    tables = []
    for t in hall["tables"]:
        kind = KINDS[t["item"]]
        seats = [P(sx + 0.5, t["y"], sz + 0.5) for sx, sz in t["seats"]]
        heights = {"round": [0.5, 0.5, 0.5, 0.5, 0.52, 0.5], "duel": [0.5, 0.5], "poker": [0.5] * 6,
                   "baccarat": [0.5, 0.5, 0.5, 0.5, 0.5, 0.52]}[kind]
        for s, h in zip(seats, heights):
            s[1] += h
        anchor = P(t["x"] + 0.5, t["y"] + 0.5, t["z"] + 0.5)
        k = 1 if kind == "duel" else 2
        # the core furniture's display: entity yaw = the table's yaw (180 here), then the item display's half turn
        place(t["item"].split(":")[1], display_matrix(anchor, rot_y(-t["yaw"]), k), textures, els)
        # TableScene's middle: the chairs' middle, the 바카라 table's own anchor (its chairs stand round one side)
        centre = np.array([anchor[0], anchor[2]]) if kind == "baccarat" else np.mean([[s[0], s[2]] for s in seats], axis=0)
        top = t["y"] + N["top_" + kind]
        tables.append(dict(t=t, kind=kind, seats=seats, centre=centre, top=top, anchor=anchor))

    def text(key, mm, pos, k, lift=0.0):
        im, w, n = text_texture(mm)
        texts.append(billboard(key, im, w, n, pos, k, cam_for_text, textures, lift))
        return pos, k, w, n

    # every other table: its sign (waiting), a few people at the back
    games = {"sutda": "섯다", "dice": "주사위", "holjjak": "홀짝", "seven_poker": "세븐 포커", "baccarat": "바카라"}
    rooms = {"beginner": "초보방", "normal": "일반방", "high": "큰손방"}
    antes = {"beginner": 1, "normal": 10, "high": 100}
    maxes = {"sutda": 6, "dice": 6, "holjjak": 2, "seven_poker": 6, "baccarat": 6}
    waiting = {1: (2, "starting"), 2: (1, "alone"), 3: (3, "starting"), 4: (0, "alone"), 5: (2, "starting")}
    extra_skins = ["office_2.png", "courier_1.png", "student_2.png", "camper_2.png", "chatty_2.png", "office_3.png", "courier_2.png"]
    si = 0
    for i, T in enumerate(tables):
        if i == 0 or i in busy:
            continue
        n, state = waiting.get(i, (0, "alone"))
        st = raw(msgs, "hwatu.sign-alone") if state == "alone" else raw(msgs, "hwatu.sign-starting", seconds=7)
        if T["t"]["game"] == "baccarat":
            sign = raw(msgs, "hwatu.bac.sign", game="바카라", min=10, max="1,000", n=n, **{"max-seats": 6}, state=raw(msgs, "hwatu.bac.sign-waiting"),
                       road=raw(msgs, "hwatu.bac.road-empty"), count=raw(msgs, "hwatu.bac.road-count", p=0, b=0, t=0))
        else:
            limit = "" if T["t"]["game"] not in ("sutda", "seven_poker") else raw(msgs, "hwatu.sign-limit", n=f"{antes[T['t']['room']] * 100:,}")
            sign = raw(msgs, "hwatu.sign", game=games[T["t"]["game"]], room=rooms[T["t"]["room"]], ante=antes[T["t"]["room"]],
                       n=n, max=maxes[T["t"]["game"]], state=st, limit=limit)
        c = T["centre"]
        text(f"sign{i}", sign, (c[0], T["t"]["y"] + N["sign_up"], c[1]), N["sign_scale"])
        for s in T["seats"][:n]:
            seated_player(extra_skins[si % len(extra_skins)], s, yaw_toward(c[0] - s[0], c[1] - s[2]), textures, els, head_pitch=12)
            si += 1

    # table 0: the hand
    T = tables[0]
    if top_fix is not None:   # what-if: hall.top.round set to another height
        T["top"] = T["t"]["y"] + top_fix
    c, top, seats = T["centre"], T["top"], T["seats"]
    seat_xz = lambda k: (seats[k][0], seats[k][2])
    R = N["reach"]
    for k, (name, skin) in HAND["seats"].items():
        if k == HAND["me"] and not viewer_body:
            continue
        s = seats[k]
        seated_player(skin, s, yaw_toward(c[0] - s[0], c[1] - s[2]), textures, els,
                      head_pitch=10 if k != HAND["me"] else 30, head_turn=0)
    # cards: the viewer's faces, everyone else's backs (as the viewer sees them)
    for k in HAND["seats"]:
        for j, side in enumerate(row(2, N["card_gap"])[:HAND["street"]]):   # a card a street
            x, z, yaw = along(c, seat_xz(k), R["card"], side)
            model = "gamble_card_" + HAND["cards"][j] if k == HAND["me"] else "gamble_card_back"
            place(model, display_matrix((x, top + 0.002 + j * 0.004, z), facing(yaw), N["card"]), textures, els)
    # the pile and its label
    level, sc = chip_level(HAND["pot"], HAND["ante"])
    place(f"gamble_chips_{level}", display_matrix((c[0], top + 0.001, c[1]), np.eye(4), N["chips"] * sc), textures, els)
    pot = text("pot", raw(msgs, "hwatu.pot-label", pot=HAND["pot"]), (c[0], top + N["pot_up"], c[1]), N["pot_scale"])
    # the viewer's buttons (only they see them) and their labels
    nb = len(HAND["buttons"])
    gap = min(N["button_gap"], R["width"] / nb)
    bscale = gap / N["button_gap"]
    btns = []
    for (key, amount), side in zip(HAND["buttons"], row(nb, gap)):
        x, z, yaw = along(c, seat_xz(HAND["me"]), R["button"], side)
        half = N["button_half_k"] * N["button"] * bscale
        rot = facing(yaw) @ rot_x(N["tilt"])
        place(f"gamble_btn_{key}", display_matrix((x, top + half + N["btn_lift"], z), rot, N["button"] * bscale), textures, els)
        name = raw(msgs, f"hwatu.btn.{key}")
        label = name if key == "die" or amount == 0 else raw(msgs, "hwatu.btn-label", name=name,
                                                                amount=raw(msgs, "hwatu.btn-amount", n=f"{amount:,}"))
        btns.append(text(f"btn_{key}", label, (x, top + 2 * half + N["label_up"], z), max(N["label_min"], N["label_scale"] * bscale)))
    # the turn timer over the viewer's seat
    left, total = HAND["turn_left"], HAND["turn_total"]
    full = int(math.floor(8.0 * min(left, total) / total + 0.5))
    bar = "▮" * full + "<dark_gray>" + "▮" * (8 - full) + "</dark_gray>"
    x, z, _ = along(c, seat_xz(HAND["me"]), R["timer"], 0)
    timer = text("timer", raw(msgs, "hwatu.timer-hurry" if left <= 5 else "hwatu.timer", player=HAND["seats"][HAND["me"]][0],
                              seconds=left, bar=bar), (x, top + N["timer_up"], z), N["timer_scale"])
    # the last bet's pop over the bettor (rising 0.35 over 30 ticks: drawn a third of the way up)
    pop = None
    if HAND["bettor"] is not None:
        act = raw(msgs, "hwatu.act.half", n="120")
        x, z, _ = along(c, seat_xz(HAND["bettor"]), R["timer"], 0)
        pop = text("pop", raw(msgs, "hwatu.bet-pop", player=HAND["seats"][HAND["bettor"]][0], act=act),
                   (x, top + N["pop_up"], z), N["pop_scale"], lift=N["pop_rise"] / 3)
        state = raw(msgs, "hwatu.sign-bet", player=HAND["seats"][HAND["bettor"]][0], act=act)
    else:
        state = raw(msgs, "hwatu.sign-playing")
    # the sign over the table
    if HAND["bettor"] is None:
        state = raw(msgs, "hwatu.sign-street", n=HAND["street"])
    sign_text = raw(msgs, "hwatu.sign", game="섯다", room="초보방", ante=HAND["ante"], n=len(HAND["seats"]), max=6, state=state,
                    limit=raw(msgs, "hwatu.sign-limit", n=f"{HAND['ante'] * 100:,}"))
    sign = text("sign0", sign_text, (c[0], T["t"]["y"] + N["sign_up"], c[1]), N["sign_scale"])
    marks = dict(centre=c, top=top, seats=seats, pot=pot, timer=timer, pop=pop, sign=sign, buttons=btns)
    if extra is not None:
        marks["extra"] = extra(N, msgs, tables, text, els, textures)
    texts.sort(key=lambda e: -e.pop("_depth"))
    return els + texts, textures, marks


# ------------------------------------------------------------------------------------------------
# cameras and rendering
# ------------------------------------------------------------------------------------------------

def camera(eye, at, fov=70.0):
    return {"eye": [float(v) for v in eye], "at": [float(v) for v in at], "fov": fov}


def view_of(cam, W, H):
    e, a = np.array(cam["eye"]) * PX, np.array(cam["at"]) * PX
    d = e - a
    dist = float(np.linalg.norm(d))
    el = math.degrees(math.asin(d[1] / dist))
    az = math.degrees(math.atan2(d[0], -d[2]))
    return {"x": 0, "y": 0, "w": W, "h": H, "az": az, "el": el, "k": 1.0, "radius": dist, "center": [float(v) for v in a],
            "fov": cam["fov"], "near": 0.05 * PX, "far": 3000}


def project(cam, p, W, H):
    """A world point (blocks) to picture px for this camera (three.js PerspectiveCamera.lookAt)."""
    e, a = np.array(cam["eye"], float), np.array(cam["at"], float)
    f = a - e
    f /= np.linalg.norm(f)
    r = np.cross(f, [0, 1, 0])
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    v = np.array(p, float) - e
    z = v @ f
    t = math.tan(math.radians(cam["fov"]) / 2)
    return (W / 2 + (v @ r) / (z * t) * H / 2, H / 2 - (v @ u) / (z * t) * H / 2)


def render(name, els, textures, cam, W, H):
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / f"{name}.preview.json", BUILD / f"{name}.png"
    src.write_text(json.dumps({"textures": textures, "elements": els, "grid": False, "views": [view_of(cam, W, H)], "size": [W, H]}))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, stdout=subprocess.DEVNULL)
    return Image.open(png).convert("RGBA")


def eye_camera(N, hall, pitch=-14.0):
    t = hall["tables"][0]
    seat = P(t["seats"][0][0] + 0.5, t["y"], t["seats"][0][1] + 0.5)
    seats = [P(sx + 0.5, t["y"], sz + 0.5) for sx, sz in t["seats"]]
    c = np.mean([[s[0], s[2]] for s in seats], axis=0)
    eye = np.array([seat[0], t["y"] + 0.5 - 0.6 + 1.62, seat[2]])
    yaw = yaw_toward(c[0] - seat[0], c[1] - seat[2])
    d = np.array([-math.sin(math.radians(yaw)) * math.cos(math.radians(pitch)), math.sin(math.radians(pitch)),
                  math.cos(math.radians(yaw)) * math.cos(math.radians(pitch))])
    return camera(eye, eye + d * 4)


def over_camera(hall):
    t = hall["tables"][0]
    seats = [P(sx + 0.5, t["y"], sz + 0.5) for sx, sz in t["seats"]]
    c = np.mean([[s[0], s[2]] for s in seats], axis=0)
    at = np.array([c[0] + 0.2, t["y"] + 1.2, c[1] + 0.1])
    eye = at + np.array([-2.5, 2.7, -3.3])
    return camera(eye, at, fov=60.0)


# ------------------------------------------------------------------------------------------------
# sheets
# ------------------------------------------------------------------------------------------------

def _font(size):
    return ImageFont.truetype(LABEL_FONT, size)


def callout(im, text, anchor, box_xy, colour=(255, 214, 90)):
    """A label box at box_xy with a line to anchor (picture px)."""
    d = ImageDraw.Draw(im)
    f = _font(22)
    tw = d.textlength(text, font=f)
    x, y = box_xy
    bx = (x, y, x + tw + 16, y + 34)
    ax = min(max(anchor[0], bx[0]), bx[2])
    ay = bx[1] if anchor[1] < bx[1] else bx[3] if anchor[1] > bx[3] else (bx[1] + bx[3]) / 2
    d.line((ax, ay, anchor[0], anchor[1]), fill=colour, width=3)
    d.ellipse((anchor[0] - 5, anchor[1] - 5, anchor[0] + 5, anchor[1] + 5), outline=colour, width=3)
    d.rectangle(bx, fill=(18, 18, 22, 225), outline=colour, width=2)
    d.text((x + 8, y + 4), text, font=f, fill=(250, 246, 235))


def titled(im, title, sub):
    f, s = _font(30), _font(20)
    out = Image.new("RGBA", (im.width, im.height + 92), (27, 29, 34, 255))
    out.alpha_composite(im, (0, 92))
    d = ImageDraw.Draw(out)
    d.text((18, 10), title, font=f, fill=(245, 245, 245))
    d.text((18, 54), sub, font=s, fill=(185, 190, 200))
    return out


def stack(panels, path, width=None):
    if width:
        panels = [p.resize((width, p.height * width // p.width), Image.LANCZOS) for p in panels]
    W = max(p.width for p in panels)
    out = Image.new("RGB", (W, sum(p.height for p in panels) + 10 * (len(panels) - 1)), (20, 20, 24))
    y = 0
    for p in panels:
        out.paste(p.convert("RGB"), (0, y))
        y += p.height + 10
    out.save(path, optimize=True)
    print("wrote", path)


def top_of_text(mark):
    pos, k, w, n = mark
    return (pos[0], pos[1] + 10 * n * 0.025 * k, pos[2])


def felt_top(name="gamble_table_round", k=2):
    """The round table model's top over its floor (blocks): the highest face over the middle of the drum."""
    _, els = bbmodel(name)
    tops = [e["to"][1] for e in els if e["from"][0] <= 8 <= e["to"][0] and e["from"][2] <= 4 <= e["to"][2]]
    return 0.5 + k * (max(tops) - 8) / 16


def card_point(N, marks, seat, side=0.0):
    c, s = marks["centre"], marks["seats"][seat]
    x, z, _ = along(c, (s[0], s[2]), N["reach"]["card"], side)
    return (x, marks["top"], z)


def scene_sheet(N, msgs, hall, more=()):
    W, H = 1920, 1080
    shots, panels = {}, []
    felt = felt_top()
    for name, cam, hand, body, cut, fix in (("eye", eye_camera(N, hall), HAND_LATE, False, None, None),
                                            ("over", over_camera(hall), HAND_LATE, True, GHP.ROOF, None),
                                            ("early", eye_camera(N, hall), HAND_EARLY, False, None, None),
                                            ("early_over", over_camera(hall), HAND_EARLY, True, GHP.ROOF, None)):
        els, tex, marks = build(N, msgs, hall, cam, viewer_body=body, cut=cut, hand=hand, top_fix=fix)
        im = render(f"gamble_scene_{name}", els, tex, cam, W, H)
        shots[name] = (im.copy(), cam, marks)
        pj = lambda p: project(cam, p, W, H)
        c, top = marks["centre"], marks["top"]
        lvl, sc = chip_level(hand["pot"], hand["ante"])
        if name == "eye":
            callout(im, f"턴 타이머 · 내 자리 쪽 위 (크기 {N['timer_scale']:g}, 윗면 + {N['timer_up']:g})", pj(top_of_text(marks["timer"])), (40, 120))
            callout(im, f"베팅 팝업 · 달빛여우 (크기 {N['pop_scale']:g})", pj(top_of_text(marks["pop"])), (1330, 300))
            callout(im, f"칩 더미 gamble_chips_{lvl} × {sc:.2f} (판 {hand['pot']}칩 = 판돈 {hand['pot'] // hand['ante']}배)",
                    pj((c[0] - 0.3, top + 0.5, c[1])), (40, 560))
            callout(im, "내 패 두 장 (3광 · 8광 = 38광땡, 나만 앞면)", pj(card_point(N, marks, 0)), (1250, 730))
            callout(im, f"내 버튼 4개 (나만 보임) + 금액 라벨 (크기 {N['label_scale']:g})", pj(marks["buttons"][0][0]), (40, 1010))
            panels.append(titled(im, "(가) 0번 자리의 눈 · 두 번째 장의 베팅, 내 차례 (달빛여우 하프 +120 다음, 판 225칩)",
                                 "섯다 = 한 장 → 베팅 → 한 장 더 → 베팅 → 패 공개 · 1920×1080 · 시야 70 · 눈높이 바닥 + 1.52 · 내려다봄 14°"))
        elif name == "over":
            callout(im, f"테이블 표지판 (크기 {N['sign_scale']:g}, 바닥 + {N['sign_up']:g}: 전구 위 · 천장 아래)", pj(top_of_text(marks["sign"])), (40, 60))
            callout(im, f"턴 타이머 (차례인 0번 쪽, 크기 {N['timer_scale']:g})", pj(top_of_text(marks["timer"])), (40, 330))
            callout(im, f"베팅 팝업 '달빛여우 / 하프! +120칩' (4번 자리)", pj(top_of_text(marks["pop"])), (1150, 120))
            callout(im, "0번 자리 버튼 + 금액 라벨 (0번 사람만 보임)", pj(marks["buttons"][-1][0]), (1100, 960))
            callout(im, "0번 · 나 (도토리): 앞면 두 장", pj(marks["seats"][0] + np.array([0, 1.1, 0])), (40, 760))
            callout(im, "5번 빈 자리", pj(marks["seats"][5] + np.array([0, 0.1, 0])), (40, 980))
            panels.append(titled(im, "(나) 같은 순간, 비스듬히 위에서 (0번 자리 사람에게 보이는 대로: 내 패 앞면 · 남의 패 뒷면 · 내 버튼만)",
                                 "섯다 초보방 = 도박장 1층 앞줄 왼쪽 테이블 (지붕 · 2층 걷어 냄) · 0 도토리(나) · 1 새벽낚시 · 2 곰돌이(삥) · 3 치킨왕(하프) · 4 달빛여우(하프 +120) · 5 빈 자리"))
        elif name == "early":
            callout(im, "턴 타이머 (18초)", pj(top_of_text(marks["timer"])), (40, 120))
            callout(im, f"판 라벨 '판 {hand['pot']}칩' (크기 {N['pot_scale']:g})", pj(top_of_text(marks["pot"])), (1330, 300))
            callout(im, "내 첫 장 (3광): 두 번째 장은 첫 베팅이 끝나면", pj(card_point(N, marks, 0, -0.16)), (40, 600))
            callout(im, "버튼: 다이 · 체크 · 삥 +10 · 하프 +25 (아무도 안 걸었을 때)", pj(marks["buttons"][0][0]), (40, 1010))
            panels.append(titled(im, "(다) 같은 자리, 판 첫 차례 · 첫 장 (판 50칩 = 판돈만): 한 장씩만 받고 베팅",
                                 "체크는 아무도 안 걸었을 때 (두 번째 장에서도 다시 체크로 시작할 수 있음)"))
        else:
            callout(im, "자리마다 한 장 (남의 것은 뒷면)", pj(card_point(N, marks, 1)), (40, 420))
            callout(im, f"테이블 표지판 '진행 중 · 1번째 장'", pj(top_of_text(marks["sign"])), (40, 60))
            panels.append(titled(im, "(라) 첫 장을 돌린 직후, 위에서", "카드는 한 장씩 가운데에서 미끄러져 옴 · 두 번째 장도 같은 순서로"))
    stack(panels + list(more), OUT / "gamble_table_scene.png", width=1600)
    return shots


# ------------------------------------------------------------------------------------------------
# 세븐 포커 · 바카라 (the 별관's tables): a hand at each, drawn as TableScene draws it (TableArt's numbers from source)
# ------------------------------------------------------------------------------------------------

def table_index(hall, game):
    return next(i for i, t in enumerate(hall["tables"]) if t["game"] == game)


def poker_slot(k, opened):
    """TableArt.pokerSlot: (row, slot) of card k (0 hidden row, 1 open row)."""
    if k < 3:
        return (1, 0) if k == opened else (0, k)
    if k <= 5:
        return (1, k - 2)
    return (0, 2 if opened < 0 else opened)


def poker_spot(N, centre, seat, row_, slot):
    R = N["poker_reach"]
    side = row(3, N["poker_hidden_gap"])[slot] if row_ == 0 else row(4, N["poker_open_gap"])[slot]
    return along(centre, seat, R["card"] if row_ == 0 else N["poker_open"], side)


# 세븐 포커 초보방 (ante 1), the 5th card's betting round: four players; the viewer (seat 0) holds K♠ K♥ hidden and
# opened 7♦, then 7♣ and 2♠ came open: 투페어 K·7; 하프 by seat 2 (+19), now the viewer's turn: pot 38, 19 to call
POKER_HAND = {
    "ante": 1, "pot": 38, "me": 0, "bettor": 2, "turn_left": 13, "turn_total": 20,
    "seats": {0: ("도토리", "student_1.png", ["ks", "7d", "kh", "7c", "2s"], 1), 1: ("새벽낚시", "camper_1.png", ["9h", "qs", "4d", "qd", "jc"], 1),
              2: ("곰돌이", "office_1.png", ["ad", "3c", "8s", "ah", "5h"], 0), 3: ("치킨왕", "chicken_1.png", ["6c", "10d", "6s", "kd", "9c"], 2)},
    "buttons": [("die", 0), ("call", 19), ("ddadang", 38), ("half", 47)],
}


def poker_extra(view):
    def draw(N, msgs, tables, text, els, textures):
        i = table_index(view["hall"], "seven_poker")
        T = tables[i]
        H = POKER_HAND
        c, top, seats = T["centre"], T["top"], T["seats"]
        xz = lambda k: (seats[k][0], seats[k][2])
        for k, (name, skin, _cards, _o) in H["seats"].items():
            if k == H["me"] and not view["body"]:
                continue
            s = seats[k]
            seated_player(skin, s, yaw_toward(c[0] - s[0], c[1] - s[2]), textures, els, head_pitch=10 if k != H["me"] else 30)
        marks = {"centre": c, "top": top, "seats": seats, "cards": {}}
        for k, (name, skin, cards, opened) in H["seats"].items():
            for j, cid in enumerate(cards):
                r, sl = poker_slot(j, opened)
                x, z, yaw = poker_spot(N, c, xz(k), r, sl)
                shown = k == H["me"] or r == 1           # mine all face up, everyone's open row face up
                y = top + 0.002 + (0.006 if r == 1 else 0) + j * 0.0015
                place("pcard_" + cid if shown else "pcard_back", display_matrix((x, y, z), facing(yaw), N["pcard"]), textures, els)
                marks["cards"][(k, j)] = (x, top, z)
        lvl, sc = chip_level(H["pot"], H["ante"])
        place(f"gamble_chips_{lvl}", display_matrix((c[0], top + 0.001, c[1]), np.eye(4), N["chips"] * N["poker_pile"] * sc), textures, els)
        marks["pot"] = text("p_pot", raw(msgs, "hwatu.pot-label", pot=H["pot"]), (c[0], top + N["pot_up"], c[1]), N["pot_scale"])
        R = N["poker_reach"]
        nb = len(H["buttons"])
        gap = min(N["button_gap"], R["width"] / nb)
        bscale = gap / N["button_gap"]
        btns = []
        for (key, amount), side in zip(H["buttons"], row(nb, gap)):
            x, z, yaw = along(c, xz(H["me"]), R["button"], side)
            half = N["button_half_k"] * N["button"] * bscale
            lift = N["poker_lift"]
            place(f"gamble_btn_{key}", display_matrix((x, top + half + N["btn_lift"] + lift, z), facing(yaw) @ rot_x(N["tilt"]), N["button"] * bscale), textures, els)
            name = raw(msgs, f"hwatu.btn.{key}")
            label = name if amount == 0 else raw(msgs, "hwatu.btn-label", name=name, amount=raw(msgs, "hwatu.btn-amount", n=f"{amount:,}"))
            btns.append(text(f"p_btn_{key}", label, (x, top + lift + 2 * half + N["label_up"], z), max(N["label_min"], N["label_scale"] * bscale)))
        marks["buttons"] = btns
        left, total = H["turn_left"], H["turn_total"]
        full = int(math.floor(8.0 * left / total + 0.5))
        bar = "▮" * full + "<dark_gray>" + "▮" * (8 - full) + "</dark_gray>"
        x, z, _ = along(c, xz(H["me"]), R["timer"], 0)
        marks["timer"] = text("p_timer", raw(msgs, "hwatu.timer", player=H["seats"][H["me"]][0], seconds=left, bar=bar), (x, top + N["timer_up"], z), N["timer_scale"])
        act = raw(msgs, "hwatu.act.half", n="19")
        x, z, _ = along(c, xz(H["bettor"]), R["timer"], 0)
        marks["pop"] = text("p_pop", raw(msgs, "hwatu.bet-pop", player=H["seats"][H["bettor"]][0], act=act), (x, top + N["pop_up"], z), N["pop_scale"],
                            lift=N["pop_rise"] / 3)
        state = raw(msgs, "hwatu.sign-bet", player=H["seats"][H["bettor"]][0], act=act)
        sign = raw(msgs, "hwatu.sign", game="세븐 포커", room="초보방", ante=1, n=len(H["seats"]), max=6, state=state,
                   limit=raw(msgs, "hwatu.sign-limit", n="100"))
        marks["sign"] = text("p_sign", sign, (c[0], T["t"]["y"] + N["sign_up"], c[1]), N["sign_scale"])
        return marks
    return draw


# 바카라 일반방: three players; (eye) the bets are open, the viewer (seat 2) has 100 on 플레이어 and 10 on 타이, the 50 chip
# picked; (over) the coup is face up: 플레이어 7♠ K♦ (7) : 뱅커 4♥ 2♣ 9♦ (5): 플레이어 승
BAC_HAND = {
    "me": 2, "seats": {0: ("새벽낚시", "camper_1.png", {"banker": 50}), 2: ("도토리", "student_1.png", {"player": 100, "tie": 10}),
                       4: ("달빛여우", "chatty_1.png", {"player": 500})},
    "player": ["7s", "kd"], "banker": ["4h", "2c", "9d"], "chips": [10, 50, 100, 500], "picked": 1, "left": 9, "road": "PBBPTPBBPPB",
}


def baccarat_extra(view):
    def draw(N, msgs, tables, text, els, textures):
        i = table_index(view["hall"], "baccarat")
        T = tables[i]
        H = BAC_HAND
        c, top, seats = T["centre"], T["top"], T["seats"]
        dl = T["t"]["dealer"]
        dealer = P(dl[0], dl[1], dl[2])
        d = np.array([dealer[0] - c[0], dealer[2] - c[1]])
        d /= np.linalg.norm(d)
        right = np.array([-d[1], d[0]])
        bac = lambda inn, side, up=0.0: (c[0] + d[0] * inn + right[0] * side, top + up, c[1] + d[1] * inn + right[1] * side)
        marks = {"centre": c, "top": top, "seats": seats}
        xz = lambda k: (seats[k][0], seats[k][2])
        for k, (name, skin, _b) in H["seats"].items():
            if k == H["me"] and not view["body"]:
                continue
            s = seats[k]
            seated_player(skin, s, yaw_toward(c[0] - s[0], c[1] - s[2]), textures, els, head_pitch=10 if k != H["me"] else 30)
        # the dealer: a mannequin standing at its spot, facing the players, the dealing arm out
        dyaw = yaw_toward(c[0] - dealer[0], c[1] - dealer[2])
        seated_player("dealer.png", (dealer[0], dealer[1], dealer[2]), dyaw, textures, els, head_pitch=22, arms=-10, standing=True,
                      rarm=rot_x(-55) if view["dealt"] else None)
        marks["dealer"] = (dealer[0], dealer[1] + 1.9, dealer[2])
        marks["speech"] = text("b_speech", raw(msgs, "hwatu.dealer.won-player" if view["dealt"] else "hwatu.dealer.place-bets", player=7, banker=5),
                               (dealer[0], dealer[1] + 2.35, dealer[2]), 0.45)
        # the bets on the printed rings
        rings = {"player": N["bet_player"], "banker": N["bet_banker"], "tie": N["bet_tie"]}
        shorts = {"player": "P", "banker": "B", "tie": "T"}
        for k, (name, skin, b) in H["seats"].items():
            sx, sz = xz(k)
            ln = math.hypot(sx - c[0], sz - c[1])
            for zone, n in b.items():
                x, z, _ = along(c, (sx, sz), rings[zone] / ln, 0)
                sc = N["bet_scale"] * min(1.4, 1 + 0.1 * math.log2(n / 10))
                place(f"baccarat_chip_{zone}", display_matrix((x, top + 0.002, z), np.eye(4), sc), textures, els)
                lab = raw(msgs, "hwatu.bac.bet-label", spot=raw(msgs, f"hwatu.bac.spot-short.{zone}"), n=f"{n:,}")
                marks[f"bet_{k}_{zone}"] = text(f"b_bet_{k}_{zone}", lab, (x, top + 0.3, z), 0.3)
        flat = facing(yaw_toward(-d[0], -d[1]))
        if view["dealt"]:
            for hand, cards in (("player", H["player"]), ("banker", H["banker"])):
                for j, cid in enumerate(cards):
                    side = (-N["hand_side"] if hand == "player" else N["hand_side"]) + row(3, N["hand_gap"])[j]
                    p = bac(N["hand_in"], side, 0.002 + j * 0.002)
                    rot = flat @ rot_y(90) if j == 2 else flat
                    place("pcard_" + cid, display_matrix(p, rot, N["bac_card"]), textures, els)
            marks["ptotal"] = text("b_pt", raw(msgs, "hwatu.bac.total-player", n=7), bac(N["hand_in"], -N["hand_side"], 0.42), 0.42)
            marks["btotal"] = text("b_bt", raw(msgs, "hwatu.bac.total-banker", n=5), bac(N["hand_in"], N["hand_side"], 0.42), 0.42)
            sx, sz = xz(H["me"])
            x, z, _ = along(c, (sx, sz), 0.7, 0)
            marks["gain"] = text("b_gain", raw(msgs, "hwatu.gain", n="90", player=H["seats"][H["me"]][0]), (x, top + 0.95, z), 0.4)
            state = raw(msgs, "hwatu.bac.sign-result", won=raw(msgs, "hwatu.bac.won.player"), player=7, banker=5)
            road = H["road"] + "P"
        else:
            # the viewer's chips (a row of their own, nearer them) and the betting buttons, the bets' timer
            R = N["bac_reach"]
            me = xz(H["me"])
            nb = 4
            gap = min(N["button_gap"], R["width"] / nb)
            bscale = gap / N["button_gap"]
            btns = []
            for j, side in enumerate(row(4, gap)):
                x, z, _ = along(c, me, N["chip_reach"], side)
                on = j == H["picked"]
                place(f"gamble_chips_{j + 1}", display_matrix((x, top + 0.002, z), np.eye(4), (0.36 if on else 0.27) * bscale), textures, els)
                lab = raw(msgs, "hwatu.btn-chip-on" if on else "hwatu.btn-chip", n=f"{H['chips'][j]:,}")
                btns.append(text(f"b_chip{j}", lab, (x, top + 0.13 + 0.1, z), max(N["label_min"], N["label_scale"] * bscale)))
            mine = H["seats"][H["me"]][2]
            for (key, zone), side in zip((("player", "player"), ("banker", "banker"), ("tie", "tie"), ("bet", None)), row(nb, gap)):
                x, z, yaw = along(c, me, R["button"], side)
                half = N["button_half_k"] * N["button"] * bscale
                place(f"gamble_btn_{key}", display_matrix((x, top + half + N["btn_lift"], z), facing(yaw) @ rot_x(N["tilt"]), N["button"] * bscale), textures, els)
                if zone is None:
                    label = raw(msgs, "hwatu.btn.bet")
                else:
                    on = raw(msgs, "hwatu.btn-spot-on", n=f"{mine[zone]:,}") if zone in mine else ""
                    label = raw(msgs, "hwatu.btn-spot", name=raw(msgs, f"hwatu.btn.{key}"), n=f"{H['chips'][H['picked']]:,}", on=on)
                btns.append(text(f"b_btn_{key}", label, (x, top + 2 * half + N["label_up"], z), max(N["label_min"], N["label_scale"] * bscale)))
            marks["buttons"] = btns
            full = int(math.floor(8.0 * H["left"] / 15 + 0.5))
            bar = "▮" * full + "<dark_gray>" + "▮" * (8 - full) + "</dark_gray>"
            marks["timer"] = text("b_timer", raw(msgs, "hwatu.timer-bets", seconds=H["left"], bar=bar), bac(0.1, 0, 0.9), 0.4)
            state = raw(msgs, "hwatu.bac.sign-bets", seconds=H["left"])
            road = H["road"]
        letters = "".join(raw(msgs, "hwatu.bac.road." + {"P": "player", "B": "banker", "T": "tie"}[ch]) for ch in road)
        count = raw(msgs, "hwatu.bac.road-count", p=road.count("P"), b=road.count("B"), t=road.count("T"))
        sign = raw(msgs, "hwatu.bac.sign", game="바카라", min=10, max="1,000", n=len(H["seats"]), **{"max-seats": 6}, state=state, road=letters, count=count)
        marks["sign"] = text("b_sign", sign, (c[0], T["t"]["y"] + N["sign_up"], c[1]), N["sign_scale"])
        return marks
    return draw


def seat_camera(hall, game, seat, pitch=-16.0, back=0.0):
    """A seated player's eyes at that table, looking at its middle (바카라: the anchor)."""
    t = hall["tables"][table_index(hall, game)]
    seats = [P(sx + 0.5, t["y"], sz + 0.5) for sx, sz in t["seats"]]
    s = seats[seat]
    if game == "baccarat":
        a = P(t["x"] + 0.5, t["y"], t["z"] + 0.5)
        c = (a[0], a[2])
    else:
        c = np.mean([[x[0], x[2]] for x in seats], axis=0)
    yaw = yaw_toward(c[0] - s[0], c[1] - s[2])
    fwd = np.array([-math.sin(math.radians(yaw)), 0, math.cos(math.radians(yaw))])
    eye = np.array([s[0], t["y"] + 0.5 - 0.6 + 1.62, s[2]]) - fwd * back
    d = np.array([fwd[0] * math.cos(math.radians(pitch)), math.sin(math.radians(pitch)), fwd[2] * math.cos(math.radians(pitch))])
    return camera(eye, eye + d * 4)


def above_camera(hall, game, off=(-2.6, 3.2, -3.0)):
    t = hall["tables"][table_index(hall, game)]
    at = P(t["x"] + 0.5, t["y"] + 1.0, t["z"] + 0.5)
    return camera(at + np.array(off), at, fov=60.0)


def casino_sheet(N, msgs, hall):
    W, H = 1920, 1080
    shots, panels = {}, []
    pi, bi = table_index(hall, "seven_poker"), table_index(hall, "baccarat")
    views = (
        ("poker_eye", seat_camera(hall, "seven_poker", 0), poker_extra, dict(body=False), None),
        ("poker_over", above_camera(hall, "seven_poker"), poker_extra, dict(body=True), GHP.ROOF),
        ("bac_eye", seat_camera(hall, "baccarat", 2, pitch=-20), baccarat_extra, dict(body=False, dealt=False), None),
        ("bac_over", above_camera(hall, "baccarat", off=(2.4, 3.0, -3.2)), baccarat_extra, dict(body=True, dealt=True), GHP.ROOF),
    )
    for name, cam, maker, view, cut in views:
        view = dict(view, hall=hall)
        els, tex, marks = build(N, msgs, hall, cam, viewer_body=False, cut=cut, extra=maker(view), busy=(pi, bi))
        im = render(f"gamble_scene_{name}", els, tex, cam, W, H)
        shots[name] = im.copy()
        m = marks["extra"]
        pj = lambda p: project(cam, p, W, H)
        if name == "poker_eye":
            callout(im, "내 숨긴 패 K♠ K♥ (나만 앞면, 남에게는 뒷면)", pj(m["cards"][(0, 0)]), (40, 760))
            callout(im, "내 오픈 패 7♦ 7♣ 2♠ (모두 앞면) → 투페어 K·7", pj(m["cards"][(0, 3)]), (1150, 640))
            callout(im, "남의 오픈 줄 (앞면) · 숨긴 줄 (뒷면)", pj(m["cards"][(2, 3)]), (1200, 240))
            callout(im, "내 버튼: 다이 · 콜 +19 · 따당 +38 · 하프 +47", pj(m["buttons"][0][0]), (40, 1010))
            callout(im, "곰돌이 하프! +19칩", pj(top_of_text(m["pop"])), (1300, 120))
            panels.append(titled(im, "(마) 세븐 포커 · 0번 자리의 눈 · 다섯 번째 장 베팅, 내 차례 (판 38칩)",
                                 "숨긴 줄(가까이) = 처음 세 장 중 둘 + 7번째 장 · 오픈 줄(안쪽) = 고른 한 장 + 4 · 5 · 6번째 장 · poker_table"))
        elif name == "poker_over":
            callout(im, "테이블 표지판 '세븐 포커 · 초보방 · 한도 100칩'", pj(top_of_text(m["sign"])), (40, 60))
            callout(im, "자리마다 두 줄 (숨긴 줄 뒷면 · 오픈 줄 앞면)", pj(m["cards"][(1, 2)]), (40, 700))
            callout(im, "차례 시간 (0번)", pj(top_of_text(m["timer"])), (1300, 300))
            panels.append(titled(im, "(바) 같은 순간, 위에서 (0번 사람에게 보이는 대로: 내 숨긴 패만 앞면)",
                                 "도박장 별관 세븐 포커 초보방 (지붕 · 2층 걷어 냄) · 0 도토리(나) · 1 새벽낚시 · 2 곰돌이(하프) · 3 치킨왕"))
        elif name == "bac_eye":
            callout(im, "딜러 (마네킹, 스킨 economy:entity/bus/dealer): '베팅해 주세요.'", pj(m["dealer"]), (40, 80))
            callout(im, "내 칩 고르기 (10 · 50 · 100 · 500, 고른 칩은 크게 · ▶)", pj(m["buttons"][1][0]), (40, 1010))
            callout(im, "플레이어 · 뱅커 · 타이 (+고른 칩) · 베팅 끝", pj(m["buttons"][4][0]), (1150, 1010))
            callout(im, "내 베팅: 플레이어 고리 100 · 타이 고리 10", pj(top_of_text(m["bet_2_player"])), (1250, 560))
            callout(im, "팻말: 최근 결과 (P · B · T)", pj(top_of_text(m["sign"])), (1250, 60))
            panels.append(titled(im, "(사) 바카라 · 2번 자리의 눈 · 베팅 시간 (9초 남음)",
                                 "하우스(딜러)와 · 한 사람 한 판 1,000칩까지 · 하루 5,000칩까지 · 모두 [베팅 끝] 이면 바로 돌림 · baccarat_table"))
        else:
            callout(im, "플레이어 7♠ K♦ = 7", pj(top_of_text(m["ptotal"])), (40, 320))
            callout(im, "뱅커 4♥ 2♣ 9♦ = 5 (셋째 장은 가로)", pj(top_of_text(m["btotal"])), (1250, 320))
            callout(im, "딜러: '플레이어 승! 7 : 5'", pj(m["dealer"]), (40, 80))
            callout(im, "+90칩 (플레이어 100 → 200, 타이 10 잃음)", pj(top_of_text(m["gain"])), (1250, 700))
            panels.append(titled(im, "(아) 바카라 · 카드를 다 뒤집은 뒤, 위에서",
                                 "슈(빨강) → 자리 앞 카드 상자로 한 장씩 미끄러져 와서 뒤집힘 · 플레이어 1:1 · 뱅커 0.95:1 · 타이 8:1"))
    return panels, shots


# ------------------------------------------------------------------------------------------------
# the HUD over the eye view
# ------------------------------------------------------------------------------------------------

def hud_layer(screen, scene, glyphs):
    W, H, s = scene["width"], scene["height"], scene["scale"]
    gw, gh = -(-W // s), -(-H // s)
    HUD._mock_vanilla(screen, glyphs, gw, gh, s, 23, 0)
    d = ImageDraw.Draw(screen)
    # the crosshair (inverted colours in the game: drawn white here)
    cx, cy = W // 2, H // 2
    d.rectangle((cx - 9, cy - 1, cx + 9, cy + 1), fill=(235, 235, 235, 255))
    d.rectangle((cx - 1, cy - 9, cx + 1, cy + 9), fill=(235, 235, 235, 255))
    for dr in scene["draws"]:
        x, y = dr["x"] + gw // 2, dr["y"] + (gh if dr["anchor"] == "bottom" else 0)
        colour = tuple(int(dr["color"][i:i + 2], 16) for i in (0, 2, 4))
        HUD._put(screen, glyphs.cell(dr["sheet"], dr["cell"]), x, y, s, colour, dr["shadow"], dr["scale"])
    return gw, gh


def hud_sheet(eye_shot, by_label=None):
    data = json.loads(HUD_JSON.read_text(encoding="utf-8"))
    glyphs = HUD.Glyphs()
    panels = []
    for sc in data["scenes"]:
        W, H, s = sc["width"], sc["height"], sc["scale"]
        if sc["kind"] == "hwatu":
            shot = next((v for k, v in (by_label or {}).items() if sc["label"].startswith(k)), eye_shot)
            screen = shot.copy().resize((W, H))
        else:
            screen = HUD._scene(W, H, water=False)
        gw, gh = hud_layer(screen, sc, glyphs)
        # the board's box (GUI px) and the hotbar, outlined on the whole screen
        bd = [dr for dr in sc["draws"] if dr["part"] == "board"]
        ln = [dr for dr in sc["draws"] if dr["part"] == "line"]
        x0 = min(dr["x"] for dr in bd) + gw // 2
        x1 = max(dr["x"] + 9 * dr["scale"] for dr in bd) + gw // 2
        y0 = min(dr["y"] for dr in bd) + gh
        full = screen.copy()
        d = ImageDraw.Draw(full)
        d.rectangle(((x0 - 2) * s, (y0 - 2) * s, (x1 + 2) * s, gh * s - 1), outline=(255, 214, 90, 255), width=4)
        d.rectangle(((gw // 2 - 91) * s - 3, (gh - 22) * s - 3, (gw // 2 + 91) * s + 3, gh * s - 1), outline=(120, 200, 255, 255), width=3)
        f = _font(40)
        d.text(((gw // 2 - 91) * s - 200, (gh - 22) * s - 56), "단축바 (가운데 182px)", font=f, fill=(120, 200, 255), stroke_width=3, stroke_fill=(0, 0, 0))
        d.text(((x0 - 2) * s, (y0 - 2) * s - 56), "보드", font=f, fill=(255, 214, 90), stroke_width=3, stroke_fill=(0, 0, 0))
        if ln:
            lx0 = min(dr["x"] for dr in ln) + gw // 2
            lx1 = max(dr["x"] + 9 * dr["scale"] for dr in ln) + gw // 2
            ly0 = min(dr["y"] for dr in ln) + gh
            d.rectangle(((lx0 - 2) * s, (ly0 - 2) * s, (lx1 + 2) * s, (ly0 + 11) * s), outline=(150, 255, 150, 255), width=3)
            d.text(((lx0 - 2) * s, (ly0 - 2) * s - 50), "액션바 한 줄", font=f, fill=(150, 255, 150), stroke_width=3, stroke_fill=(0, 0, 0))
        small = full.resize((W // 2, H // 2), Image.LANCZOS)
        # the corner, enlarged 2x (4 screen px a GUI px), from the hotbar's right end to the board's right edge
        cx0 = (gw // 2 - 110) * s
        cx1 = min(W, (x1 + 10) * s)
        cy0 = (y0 - 14) * s
        crop = screen.crop((cx0, cy0, cx1, H))
        crop = crop.resize((crop.width * 3 // 2, crop.height * 3 // 2), Image.NEAREST)
        # side by side: the screen (half size) and the corner (2x)
        hgt = max(small.height, crop.height)
        row_im = Image.new("RGBA", (small.width + 16 + crop.width, hgt), (20, 20, 24, 255))
        row_im.alpha_composite(small, (0, 0))
        row_im.alpha_composite(crop, (small.width + 16, 0))
        kept = len(sc["rowsKept"])
        note = f"화면 {W}×{H} · GUI {s} ({gw}×{gh}) · 보드 오른쪽 끝 = 가운데 +{sc['boardRight']} · 폭 {sc['boardWidth']}px"
        if sc["kind"] == "hwatu":
            note += f" · 보드: 제목 + {kept}줄 · 액션바 줄 폭 {sc['lineWidth']}px (≤ 212)"
        panels.append(titled(row_im, sc["label"], note + " · 오른쪽: 아래쪽을 1.5배로"))
    stack(panels, OUT / "gamble_hud.png")


def main():
    N = source_numbers()
    print("from source:", {k: v for k, v in N.items() if k not in ("reach", "poker_reach", "bac_reach")}, N["reach"])
    msgs = messages()
    hall = json.loads(GHP.DATA.read_text(encoding="utf-8"))
    more, casino = casino_sheet(N, msgs, hall)
    shots = scene_sheet(N, msgs, hall, more)
    if HUD_JSON.exists():
        hud_sheet(shots["eye"][0], {"세븐 포커": casino["poker_eye"], "바카라": casino["bac_eye"]})
    else:
        print("no", HUD_JSON, "(run the plugin's GambleHudPreviewTest): gamble_hud.png not drawn")


if __name__ == "__main__":
    main()
