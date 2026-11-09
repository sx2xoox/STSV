"""
The resource-pack HUD of the core (hud.mode: pack): the glyph sheets the plugin turns into fonts.

    python3 hud.py                                  # the sheets (also run by build.py)
    python3 hud.py --preview <draws.json> <out.png> # docs/previews/hud.png from the core's draw list

The draw list is written by the core's tests (cd plugin && ./gradlew test --tests '*HudPreviewTest' ->
plugin/build/hud-preview.json): every glyph where the game would put it, read from the generated fonts.

Writes plugin/src/main/resources/pack/hud/: one PNG per sheet and sheets.json (cell size, columns, the
name of every cell, the height the game draws a cell at). The plugin (dev.stsv.core.hud.HudSheets)
reads the same files to build the fonts (one bitmap provider per sheet and height on screen) and to
measure every glyph exactly as the game does (rightmost visible column), so layout and pictures can
never disagree. Nothing here is a Mojang texture:
  - hangul: the Hangul syllables of KS X 1001 plus any other syllable the repository's texts use,
    rasterised from GNU Unifont (dual GPLv2+ with font embedding exception / SIL OFL 1.1, the same
    font the game itself uses for Hangul; see pack/hud/LICENSE-unifont.txt). Our own copy is needed
    because the game's unifont cannot be moved up or down, and the HUD measures every width itself.
  - symbols: the few non-ASCII signs of the messages (· → × ★ ...), drawn here.
  - digits: the small outlined numbers on the health bars.
  - bar / fill / stripe / icons: the health, hunger and armor bars and the 9 x 9 icons.
  - panel_*: the sidebar panel in the menus' charcoal and gold (stsv_assets/menu_theme.py), cut in
    strips (left edge, right edge, fills of 1, 2, 4 ... 128 px) so the plugin can size it to its text.
ASCII is the game's own ascii.png, referenced (not copied) by the generated fonts.
"""

import json
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from stsv_assets import menu_theme as T

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "plugin" / "src" / "main" / "resources" / "pack" / "hud"
UNIFONT = "/usr/share/fonts/opentype/unifont/unifont.otf"
TEXT_SOURCES = ["plugin/src/main/resources/messages.yml", "economy/src/main/resources/messages.yml",
                "content/**/*.yml", "economy/content/**/*.yml"]

# ------------------------------------------------------------------------------------------------
# Colours
# ------------------------------------------------------------------------------------------------

OUTLINE = (16, 18, 22)
PANEL_ALPHA = 232
BAR_BACK = (24, 26, 31, 210)
BAR_EDGE = (10, 11, 14, 255)

FILLS = {   # light top row, body, dark bottom row
    "red": ((255, 120, 108), (222, 54, 54), (150, 28, 38)),
    "poison": ((200, 226, 104), (128, 172, 44), (74, 112, 26)),
    "wither": ((122, 112, 124), (70, 62, 74), (36, 30, 40)),
    "frozen": ((214, 244, 255), (124, 200, 238), (70, 140, 192)),
    "food": ((255, 204, 128), (222, 142, 54), (150, 86, 30)),
    "hunger": ((186, 196, 106), (124, 144, 62), (80, 92, 38)),
    "armor": ((218, 230, 244), (150, 170, 196), (92, 108, 134)),
}
STRIPES = {"abs": (255, 214, 84), "sat": (255, 236, 150)}
FILL_W, FILL_H = 69, 7          # inside of the 71 x 9 bar frame

# ------------------------------------------------------------------------------------------------
# Small drawings (9 x 9 icons, 3 x 5 digits, 8 x 8 symbols)
# ------------------------------------------------------------------------------------------------

ICON_COLOURS = {
    "r": (232, 60, 60), "R": (160, 30, 40), "h": (255, 170, 160),          # heart
    "p": (140, 184, 50), "P": (80, 118, 30), "q": (214, 240, 140),         # poisoned heart
    "x": (86, 78, 90), "X": (44, 38, 48), "z": (150, 140, 152),            # withered heart
    "i": (140, 210, 245), "I": (76, 146, 196), "j": (226, 248, 255),       # frozen heart
    "m": (214, 132, 60), "M": (150, 84, 34), "n": (246, 186, 120),         # meat
    "u": (136, 152, 70), "U": (88, 100, 44), "v": (186, 200, 112),         # spoiled meat
    "w": (238, 232, 216), "W": (178, 170, 154),                            # bone, paper
    "s": (176, 192, 214), "S": (110, 126, 152), "t": (232, 240, 250),      # steel
    "g": (246, 198, 78), "G": (184, 132, 42), "y": (255, 238, 160),        # gold
    "b": (122, 86, 52), "B": (84, 58, 36),                                 # wood handle
    "c": (96, 200, 120), "e": (240, 112, 72), "E": (180, 64, 40),          # check, flame
    "k": (34, 37, 44),
}

ICONS = {
    "heart": ["",
              ".rr.rr.", "rhrrrrr", "rhrrrrr", "rrrrrrR", ".rrrrR.", "..rrR..", "...R..."],
    "food": ["",
             "....mmm", "...mnmmM", "...mmmmM", "...mmmMM", "..wMMMM.", ".w.....", "ww.....", ".w....."],
    "armor": ["",
              "sssssss", "tsSsSss", "tsssssS", "tssssSS", ".sssSS.", "..sSS..", "...S..."],
    "coin": ["",
             "..ggg..", ".gyggG.", "gyGGGgG", "ggGggGG", "ggGGGgG", ".ggggG.", "..GGG.."],
    "job": ["",
            ".sssss.", "sS...Ss", "....b..", "...b...", "..b....", ".b.....", "B......"],
    "mission": ["",
                ".wwwww.", ".wkkkW.", ".wwwwW.", ".wkkwW.", ".wwwccW", ".wwccW.", ".Wc.WW."],
    "event": ["",
              "...e...", "..eeE..", ".eeyeE.", ".eyyeE.", "eeyygEE", ".eggeE.", "..EEE.."],
    "star": ["",
             "...g...", "...g...", "gggyggG", ".gyygG.", ".ggGgG.", ".gG.GG.", ".G...G."],
    "clock": ["",
              "..www..", ".wwkwW.", "wwwkwwW", "wwwkkwW", "wwwwwwW", ".wwwwW.", "..WWW.."],
}
# heart variants: same shape, other colours
for name, (a, b, c) in {"heart_poison": "pPq", "heart_wither": "xXz", "heart_frozen": "iIj"}.items():
    ICONS[name] = [row.replace("r", a).replace("R", b).replace("h", c) for row in ICONS["heart"]]
ICONS["food_hunger"] = [row.replace("m", "u").replace("M", "U").replace("n", "v") for row in ICONS["food"]]

DIGITS = {
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["###", "..#", "###", "#..", "###"],
    "3": ["###", "..#", ".##", "..#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#"],
    "5": ["###", "#..", "###", "..#", "###"],
    "6": ["###", "#..", "###", "#.#", "###"],
    "7": ["###", "..#", ".#.", ".#.", ".#."],
    "8": ["###", "#.#", "###", "#.#", "###"],
    "9": ["###", "#.#", "###", "..#", "###"],
    "/": ["..#", "..#", ".#.", "#..", "#.."],
    "+": ["...", ".#.", "###", ".#.", "..."],
    "-": ["...", "...", "###", "...", "..."],
    "%": ["#.#", "..#", ".#.", "#..", "#.#"],
    ".": ["...", "...", "...", "...", ".#."],
}
DIGIT_FACE = (250, 248, 240)
DIGIT_EDGE = (22, 16, 18)

# 8 x 8 cells, baseline under row 6 like the game's ASCII (row 7 = below the line)
SYMBOLS = {
    "·": ["", "", "", "##", "##"],
    "•": ["", "", ".##", "####", "####", ".##"],
    "→": ["", "", "...#", "....#", "######", "....#", "...#"],
    "←": ["", "", "..#", ".#", "######", ".#", "..#"],
    "×": ["", "", "#...#", ".#.#", "..#", ".#.#", "#...#"],
    "◀": ["", "...#", "..##", ".###", "####", ".###", "..##", "...#"],
    "▶": ["", "#", "##", "###", "####", "###", "##", "#"],
    "▲": ["", "", "..#", ".###", "#####", "", ""],
    "▼": ["", "", "#####", ".###", "..#", "", ""],
    "◆": ["", "..#", ".###", "#####", ".###", "..#"],
    "◇": ["", "..#", ".#.#", "#...#", ".#.#", "..#"],
    "★": ["", "...#", "...#", "#######", ".#####", "..###", ".##.##", "#.....#"],
    "☆": ["", "...#", "..#.#", "##...##", ".#...#", "..#.#", ".#.#.#", "#.....#"],
    "✔": ["", ".....#", "....#", "#..##", "##.#", ".##", "..#"],
    "…": ["", "", "", "", "", "", "#.#.#"],
    "±": ["", "..#", "..#", "#####", "..#", "..#", "#####"],
    "»": ["", "", "#.#", ".#.#", "..#.#", ".#.#", "#.#"],
    "«": ["", "", "..#.#", ".#.#", "#.#", ".#.#", "..#.#"],
    "─": ["", "", "", "", "########"],
    "⚠": ["", "...#", "..#.#", "..#.#", ".#.#.#", ".#...#", "#..#..#", "#######"],
    "■": ["", "", "#####", "#####", "#####", "#####", "#####"],
    "❤": ["", ".##.##", "#######", "#######", ".#####", "..###", "...#"],
}


def _outlined(rows, size, colours, outline=OUTLINE):
    """rows of colour letters ('.' = empty) inside a size x size cell, with a 1 px dark outline."""
    w, h = size
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    filled = {(x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch != "." and x < w and y < h}
    for (x, y) in filled:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q not in filled and 0 <= q[0] < w and 0 <= q[1] < h:
                im.putpixel(q, outline + (255,))
    for (x, y) in filled:
        im.putpixel((x, y), colours[rows[y][x]] + (255,))
    return im


def icon(name):
    rows = [("." + r) for r in ICONS[name]]          # one column for the left outline
    return _outlined(rows, (9, 9), ICON_COLOURS)


def digit(ch):
    rows = ["....."] + ["." + r.replace("#", "f") + "." for r in DIGITS[ch]] + ["....."]
    return _outlined(rows, (5, 7), {"f": DIGIT_FACE}, DIGIT_EDGE)


def symbol(ch):
    im = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
    for y, row in enumerate(SYMBOLS[ch]):
        for x, c in enumerate(row):
            if c == "#":
                im.putpixel((x, y), (255, 255, 255, 255))
    return im


def hangul_set():
    """KS X 1001 (2350 syllables, EUC-KR two-byte codes) plus every syllable the repository's texts use."""
    out = set()
    for cp in range(0xAC00, 0xD7A4):
        try:
            if len(chr(cp).encode("euc_kr")) == 2:
                out.add(chr(cp))
        except UnicodeEncodeError:
            pass
    for pattern in TEXT_SOURCES:
        for f in REPO.glob(pattern):
            out.update(ch for ch in f.read_text(encoding="utf-8") if 0xAC00 <= ord(ch) <= 0xD7A3)
    return sorted(out)


def hangul_glyph(font, ch):
    """One syllable in a 16 x 16 cell as Unifont draws it, moved left onto column 0 (the game trims the
    empty columns on the left of a Unifont glyph too)."""
    im = Image.new("L", (16, 16), 0)
    ImageDraw.Draw(im).text((0, 0), ch, font=font, fill=255)
    box = im.getbbox()
    out = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    if box:
        mask = im.point(lambda v: 255 if v >= 128 else 0)
        white = Image.new("RGBA", (16, 16), (255, 255, 255, 255))
        shifted = Image.new("L", (16, 16), 0)
        shifted.paste(mask.crop((box[0], 0, 16, 16)), (0, 0))
        out.paste(white, (0, 0), shifted)
    return out


# ------------------------------------------------------------------------------------------------
# Bars and panel
# ------------------------------------------------------------------------------------------------

def bar_frame(w):
    """A w x 9 bar: a dark rounded rim around a dim inside (the fill is drawn over the inside)."""
    im = Image.new("RGBA", (w, 9), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, w - 1, 8], fill=BAR_EDGE)
    d.rectangle([1, 1, w - 2, 7], fill=BAR_BACK)
    for x, y in ((0, 0), (w - 1, 0), (0, 8), (w - 1, 8)):
        im.putpixel((x, y), (0, 0, 0, 0))
    return im


def fill(colours, k):
    light, base, shade = colours
    im = Image.new("RGBA", (FILL_W, FILL_H), (0, 0, 0, 0))
    for x in range(k):
        for y in range(FILL_H):
            c = light if y == 0 else shade if y == FILL_H - 1 else base
            if y in (1, 2) and x < k - 1:
                c = tuple(min(255, int(v * 0.6 + l * 0.4)) for v, l in zip(base, light)) if y == 1 else base
            im.putpixel((x, y), c + (255,))
    if k >= 3:   # a darker end so a part-filled bar reads as a level
        for y in range(1, FILL_H - 1):
            im.putpixel((k - 1, y), tuple(int(v * 0.8) for v in base) + (255,))
    return im


def stripe(colour, k):
    im = Image.new("RGBA", (FILL_W, 1), (0, 0, 0, 0))
    for x in range(k):
        im.putpixel((x, 0), colour + (255,))
    return im


PANEL_FILL = T.PANEL + (PANEL_ALPHA,)
CAP = 4
SEGMENTS = (1, 2, 4, 8, 16, 32, 64, 128)
STRIPS = {"panel_head": 18, "panel_row": 11, "panel_div": 11, "panel_foot": 5}
GOLD_ACCENT = 28
HEAD_LINE = 14       # row of the gold line in the head strip


def _strip_column(kind, part):
    """The colours of one column of a strip, top to bottom. part: 'l0'..'l3', 'f' (fill), 'r0'..'r3'."""
    h = STRIPS[kind]
    col = [PANEL_FILL] * h
    top = kind == "panel_head"
    bottom = kind == "panel_foot"
    if top:
        col[0] = T.EDGE_OUT + (255,)
        col[1] = T.EDGE_LIGHT + (255,)
    if bottom:
        col[h - 1] = T.EDGE_OUT + (255,)
        col[h - 2] = T.EDGE_DARK + (255,)
    if part == "l0":
        col = [T.EDGE_OUT + (255,)] * h
    elif part == "l1":
        col = [T.EDGE_LIGHT + (255,)] * h
        if top:
            col[0] = T.EDGE_OUT + (255,)
        if bottom:
            col[h - 1] = T.EDGE_OUT + (255,)
            col[h - 2] = T.EDGE_DARK + (255,)
    elif part == "r3":
        col = [T.EDGE_OUT + (255,)] * h
    elif part == "r2":
        col = [T.EDGE_DARK + (255,)] * h
        if top:
            col[0] = T.EDGE_OUT + (255,)
            col[1] = T.EDGE_LIGHT + (255,)
        if bottom:
            col[h - 1] = T.EDGE_OUT + (255,)
    if part == "f":
        if top:
            col[HEAD_LINE] = T.GOLD_DIM + (255,)
        if kind == "panel_div":
            col[0] = (64, 69, 80, 255)
    if top and part in ("l0", "r3"):
        col[0] = (0, 0, 0, 0)
        col[1] = col[1] if part == "l0" else col[1]
    if bottom and part in ("l0", "r3"):
        col[h - 1] = (0, 0, 0, 0)
    return col


def strip_sheet(kind):
    """Cells of 128 x h: 'l' (left edge, 4 px), 'r' (right edge, 4 px), fills of 1..128 px."""
    h = STRIPS[kind]
    names = ["l", "r"] + [str(s) for s in SEGMENTS]
    if kind == "panel_head":
        names.append("gold")
    cells = []
    for n in names:
        im = Image.new("RGBA", (128, h), (0, 0, 0, 0))
        if n in ("l", "r"):
            for x in range(CAP):
                for y, c in enumerate(_strip_column(kind, f"{n}{x}")):
                    im.putpixel((x, y), c)
            if kind == "panel_head":   # round the outer corner: no light pixel on the corner itself
                if n == "l":
                    im.putpixel((1, 1), T.EDGE_LIGHT + (255,))
                    im.putpixel((0, 1), T.EDGE_OUT + (255,))
        elif n == "gold":
            for x in range(GOLD_ACCENT):
                im.putpixel((x, HEAD_LINE), T.GOLD + (255,))
        else:
            col = _strip_column(kind, "f")
            for x in range(int(n)):
                for y, c in enumerate(col):
                    im.putpixel((x, y), c)
        cells.append(im)
    return names, cells


# ------------------------------------------------------------------------------------------------
# Writing
# ------------------------------------------------------------------------------------------------

def _sheet(cells, cell, cols):
    rows = (len(cells) + cols - 1) // cols
    im = Image.new("RGBA", (cols * cell[0], rows * cell[1]), (0, 0, 0, 0))
    for k, c in enumerate(cells):
        im.alpha_composite(c, ((k % cols) * cell[0], (k // cols) * cell[1]))
    return im


def sheets():
    """name -> (cell (w, h), cols, height on screen, cell names, cell images)."""
    out = {}
    font = ImageFont.truetype(UNIFONT, 16)
    syll = hangul_set()
    out["hangul"] = ((16, 16), 64, 8, syll, [hangul_glyph(font, ch) for ch in syll])
    sy = list(SYMBOLS)
    out["symbols"] = ((8, 8), 16, 8, sy, [symbol(ch) for ch in sy])
    dg = list(DIGITS)
    out["digits"] = ((5, 7), 16, 7, dg, [digit(ch) for ch in dg])
    out["icons"] = ((9, 9), 8, 9, list(ICONS), [icon(n) for n in ICONS])
    out["bar"] = ((71, 9), 2, 9, ["frame", "frame_short"], [bar_frame(71), bar_frame(41)])
    names, cells = [], []
    for colour, ramp in FILLS.items():
        for k in range(1, FILL_W + 1):
            names.append(f"{colour}/{k}")
            cells.append(fill(ramp, k))
    out["fill"] = ((FILL_W, FILL_H), FILL_W, FILL_H, names, cells)
    names, cells = [], []
    for colour, c in STRIPES.items():
        for k in range(1, FILL_W + 1):
            names.append(f"{colour}/{k}")
            cells.append(stripe(c, k))
    out["stripe"] = ((FILL_W, 1), FILL_W, 1, names, cells)
    for kind, h in STRIPS.items():
        names, cells = strip_sheet(kind)
        out[kind] = ((128, h), 4, h, names, cells)
    return out


def write(out_dir: Path = OUT):
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = {}
    built = sheets()
    for f in out_dir.glob("*.png"):
        if f.stem not in built:
            f.unlink()
    for name, (cell, cols, height, names, cells) in built.items():
        _sheet(cells, cell, cols).save(out_dir / f"{name}.png", optimize=True)
        manifest[name] = {"file": f"{name}.png", "cell": list(cell), "cols": cols, "height": height, "names": names}
    (out_dir / "sheets.json").write_text(json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n",
                                         encoding="utf-8")
    (out_dir / "LICENSE-unifont.txt").write_text(
        "hangul.png holds Hangul syllables rasterised from GNU Unifont (https://unifoundry.com/unifont/).\n"
        "Copyright: Jungshik Shin (1998-), Roman Czyborra (1998-2014), Qianqian Fang (2004-2013),\n"
        "Luis Alejandro Gonzalez Miranda (2005), Paul Hardy (2007-), Andrew Miller (2013-2014),\n"
        "David Corbett (2017-), Johnnie Weaver (2018) and the other Unifont contributors.\n"
        "Unifont is dual-licensed under the GNU GPL v2 or later with the GNU font embedding exception, and\n"
        "the SIL Open Font License 1.1 (https://openfontlicense.org). The other HUD pictures are STSV's own.\n",
        encoding="utf-8")
    return {k: len(v[3]) for k, v in built.items()}


# ------------------------------------------------------------------------------------------------
# Preview: the draw list the core writes (HudPreviewTest) on a mock 1920 x 1080 screen
# ------------------------------------------------------------------------------------------------

# pen advance of the game's ASCII letters (the same table as dev.stsv.core.hud.HudText), for the preview only
ASCII_WIDTHS = dict(zip((chr(c) for c in range(0x21, 0x7F)),
                        (int(v) for v in "2466662444626266666666666225656766666666466666666666666666464663666665662653666666646666664247")))


def _scene(w, h):
    """A plain daytime backdrop: sky gradient, a horizon of hills and grass (no game textures)."""
    im = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(im)
    for y in range(h):
        t = y / h
        if t < 0.55:
            c = (int(120 + 60 * t), int(170 + 50 * t), 235)
        else:
            u = (t - 0.55) / 0.45
            c = (int(92 - 30 * u), int(140 - 40 * u), int(64 - 20 * u))
        d.line([(0, y), (w, y)], fill=c + (255,))
    for k in range(7):
        x = k * w // 6
        d.ellipse([x - w // 5, int(h * 0.48), x + w // 5, int(h * 0.7)], fill=(70 + k * 4, 112, 70, 255))
    return im


def _mock_vanilla(screen, gw, gh, s):
    """Stand-ins for what the game still draws: hotbar, experience bar, level (plain shapes)."""
    d = ImageDraw.Draw(screen)
    cx = gw // 2

    def rect(x0, y0, x1, y1, fill, outline=None):
        d.rectangle([x0 * s, y0 * s, x1 * s - 1, y1 * s - 1], fill=fill, outline=outline)

    rect(cx - 91, gh - 22, cx + 91, gh, (20, 20, 22, 200))
    for i in range(9):
        rect(cx - 90 + i * 20, gh - 21, cx - 90 + i * 20 + 20, gh - 1, None, (110, 110, 110, 255))
    rect(cx - 91, gh - 22, cx - 91 + 22, gh, None, (240, 240, 240, 255))
    rect(cx - 91, gh - 29, cx + 91, gh - 24, (30, 30, 30, 255))
    rect(cx - 91, gh - 29, cx + 20, gh - 24, (128, 255, 32, 255))


def preview(draws_path: Path, out: Path):
    """draws.json: {"scales": [{"scale": s, "draws": [...]}]} written by the core's HudPreviewTest:
    every glyph as the game places it (gui x/y of its top-left, sheet, cell) for a 1920 x 1080 window."""
    data = json.loads(Path(draws_path).read_text(encoding="utf-8"))
    manifest = json.loads((OUT / "sheets.json").read_text(encoding="utf-8"))
    imgs = {k: Image.open(OUT / v["file"]).convert("RGBA") for k, v in manifest.items()}
    uni = ImageFont.truetype(UNIFONT, 16)
    W, H = 1920, 1080
    panels = []
    for entry in data["scales"]:
        s = entry["scale"]
        gw, gh = -(-W // s), -(-H // s)
        screen = _scene(W, H)
        _mock_vanilla(screen, gw, gh, s)
        for dr in entry["draws"]:
            x, y = dr["x"] + gw / 2, dr["y"] + (gh if dr["anchor"] == "bottom" else 0)
            colour = tuple(int(dr.get("color", "ffffff")[i:i + 2], 16) for i in (0, 2, 4))
            if dr["sheet"] == "ascii":
                # the game's ascii.png is not in the repository: the preview draws ASCII with Unifont
                # (narrow 8 px Unifont letters stretched to the game's letter width, so the spacing reads right)
                g = Image.new("L", (16, 16), 0)
                ImageDraw.Draw(g).text((0, 0), dr["cell"], font=uni, fill=255)
                box = g.getbbox() or (0, 0, 1, 16)
                width = max(1, ASCII_WIDTHS.get(dr["cell"], 6) - 1)
                g = g.crop((box[0], 2, box[2], 16)).resize((width * s, 7 * s), Image.NEAREST)
                glyph = Image.new("RGBA", g.size, colour + (255,))
                glyph.putalpha(g)
                yy = y + 0.5   # Unifont's ASCII sits one half pixel lower than its row grid here
            else:
                m = manifest[dr["sheet"]]
                k = m["names"].index(dr["cell"])
                cw, ch = m["cell"]
                cell = imgs[dr["sheet"]].crop(((k % m["cols"]) * cw, (k // m["cols"]) * ch,
                                               (k % m["cols"]) * cw + cw, (k // m["cols"]) * ch + ch))
                scale = m["height"] / ch
                glyph = cell.resize((max(1, round(cw * scale * s)), max(1, round(ch * scale * s))), Image.NEAREST)
                if colour != (255, 255, 255):
                    glyph = _multiply(glyph, colour)   # the game multiplies a glyph by its text colour
                yy = y
            if dr.get("shadow"):
                sh = Image.new("RGBA", glyph.size, tuple(v // 4 for v in colour) + (255,))
                sh.putalpha(glyph.split()[3])
                screen.alpha_composite(sh, (int((x + 1) * s), int((yy + 1) * s)))
            screen.alpha_composite(glyph, (int(x * s), int(yy * s)))
        label = ImageDraw.Draw(screen)
        label.rectangle([0, 0, 640, 34], fill=(0, 0, 0, 170))
        label.text((10, 6), f"1920 x 1080 · GUI {s} ({gw} x {gh}) · {entry.get('label', '')}",
                   font=ImageFont.truetype(UNIFONT, 16), fill=(255, 255, 255, 255))
        panels.append(screen.resize((W // 2, H // 2), Image.LANCZOS) if "--half" in sys.argv else screen)
    w = max(p.width for p in panels)
    sheet = Image.new("RGBA", (w, sum(p.height for p in panels) + 8 * (len(panels) - 1)), (20, 20, 24, 255))
    y = 0
    for p in panels:
        sheet.alpha_composite(p, (0, y))
        y += p.height + 8
    sheet.convert("RGB").save(out, optimize=True)


def _multiply(glyph, colour):
    r, g, b, a = glyph.split()
    r = r.point(lambda v: v * colour[0] // 255)
    g = g.point(lambda v: v * colour[1] // 255)
    b = b.point(lambda v: v * colour[2] // 255)
    return Image.merge("RGBA", (r, g, b, a))


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "--preview":
        preview(Path(sys.argv[2]), Path(sys.argv[3]))
    else:
        print(write())
