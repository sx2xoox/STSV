"""
The resource-pack HUD of the core (hud.mode: pack): the glyph sheets the plugin turns into fonts.

    python3 hud.py                                  # the sheets (also run by build.py)
    python3 hud.py --preview <draws.json> <outdir>  # docs/previews/hud*.png from the core's draw list

The draw list is written by the core's tests (cd plugin && ./gradlew test --tests '*HudPreviewTest' ->
plugin/build/hud-preview.json): every glyph where the game would put it, read from the generated fonts.

Writes plugin/src/main/resources/pack/hud/: one PNG per sheet and sheets.json (cell size, columns, the
name of every cell, the height the game draws a cell at). The plugin (dev.stsv.core.hud.HudSheets)
reads the same files to build the fonts (one bitmap provider per sheet and height on screen) and to
measure every glyph exactly as the game does (rightmost visible column), so layout and pictures can
never disagree. Nothing here is a Mojang texture:
  - text / hangul: the HUD's letters, drawn pixel for pixel (one font pixel = one GUI pixel, like the
    game's own letters) from Galmuri9 (SIL OFL 1.1, fonts/LICENSE-galmuri.txt, data: galmuri.py):
    ASCII, the signs the messages use and the Hangul jamo in `text`, the 2,350 syllables of KS X 1001
    plus any other syllable the repository's texts use in `hangul`. Every cell's rightmost visible
    column gives the game Galmuri's own advance; where a glyph ends earlier, one pixel of alpha 1 marks
    it (the game measures it, its text shader discards anything under alpha 0.1, so it never shows).
    The game's text is not used: the letters are moved up and down per panel row, which a font
    reference cannot do, and the HUD measures every width itself.
  - digits: the small outlined numbers next to the bars.
  - frame / fill / tick / line / icons: the bars and the 9 x 9 icons.
  - panel_*: the sidebar panel in the menus' charcoal and gold (stsv_assets/menu_theme.py), cut in
    strips (left cap, right cap with its drop shadow, fills of 1, 2, 4 ... 64 px) so the plugin can size
    it to its text, and the server mark in its title strip.
The fonts cost the client memory per (sheet, height on screen): every bitmap provider keeps its whole
sheet as RGBA. Sheets are therefore packed tightly (a 9 x 9 Hangul cell, one row of strip pieces).
"""

import json
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import galmuri
from stsv_assets import menu_theme as T

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "plugin" / "src" / "main" / "resources" / "pack" / "hud"
TEXT_SOURCES = ["plugin/src/main/resources/messages.yml", "economy/src/main/resources/messages.yml",
                "content/**/*.yml", "economy/content/**/*.yml"]

# ------------------------------------------------------------------------------------------------
# Letters (Galmuri9): 9 px above the baseline, 1 below
# ------------------------------------------------------------------------------------------------

ABOVE, BELOW = 9, 1
TEXT_CELL = (9, ABOVE + BELOW)
HANGUL_CELL = (9, ABOVE)
MARK = (255, 255, 255, 1)        # an invisible pixel that sets a glyph's advance

# signs always on the sheet (besides those the repository's texts use)
BASE_SIGNS = "·•…→←↑↓⇄×÷±°²‘’“”«»★☆◆◇▲▼◀▶■□○●─%✔✦❤⚠♪"

# signs Galmuri9 lacks or draws unlike the menus: our own, in its 9 px style (rows from the top, 9 above
# the baseline; the advance is the width + 1)
OWN_SIGNS = {
    "✔": ["", "", "......#", ".....##", "#...##", "##.##", ".###", "..#", ""],
    "✦": ["", "...#", "...#", "..###", "#######", "..###", "...#", "...#", ""],
    "❤": ["", ".##.##.", "#######", "#######", "#######", ".#####", "..###", "...#", ""],
    "⚠": ["....#", "...###", "...#.#", "..##.##", "..##.##", ".###.###", ".#######", "####.####", "#########"],
}


def _hangul_set():
    """KS X 1001 (2350 syllables, EUC-KR two-byte codes) plus every syllable the repository's texts use."""
    out = set()
    for cp in range(0xAC00, 0xD7A4):
        try:
            if len(chr(cp).encode("euc_kr")) == 2:
                out.add(chr(cp))
        except UnicodeEncodeError:
            pass
    for ch in _repo_chars():
        if 0xAC00 <= ord(ch) <= 0xD7A3:
            out.add(ch)
    return sorted(out)


def _repo_chars():
    chars = set()
    for pattern in TEXT_SOURCES:
        for f in REPO.glob(pattern):
            chars.update(f.read_text(encoding="utf-8"))
    return chars


def _text_set(font):
    """ASCII, the signs (base set + whatever the texts use and we can draw), the Hangul jamo."""
    out = [chr(c) for c in range(0x21, 0x7F)]
    signs = set(BASE_SIGNS)
    for ch in _repo_chars():
        o = ord(ch)
        if o > 0x7E and not (0xAC00 <= o <= 0xD7A3) and o <= 0xFFFF and (o in font or ch in OWN_SIGNS):
            signs.add(ch)
    out += sorted(s for s in signs if s not in out)
    out += [chr(c) for c in range(0x3131, 0x3164) if c in font]
    return out


def letter(font, ch, cell):
    """One letter in a cell whose top is ABOVE px over the baseline, white, with its advance marked."""
    w, h = cell
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    if ch in OWN_SIGNS:
        rows = OWN_SIGNS[ch]
        ink = [(x, y) for y, r in enumerate(rows) for x, c in enumerate(r) if c == "#"]
        advance = max(x for x, _ in ink) + 2
    else:
        advance, xo, yo, bits = font[ord(ch)]
        top = ABOVE - (yo + len(bits))
        ink = [(xo + x, top + y) for y, r in enumerate(bits) for x, b in enumerate(r) if b]
    last = advance - 2                                  # the game: advance = rightmost column + 2
    for x, y in ink:
        if 0 <= x <= min(last, w - 1) and 0 <= y < h:
            im.putpixel((x, y), (255, 255, 255, 255))
    if 0 <= last < w and all(im.getpixel((last, y))[3] == 0 for y in range(h)):
        im.putpixel((last, h - 1), MARK)
    return im


# ------------------------------------------------------------------------------------------------
# Colours
# ------------------------------------------------------------------------------------------------

def mix(a, b, t):
    return tuple(int(round(x + (y - x) * t)) for x, y in zip(a, b))


FILLS = {   # light, body, shade
    "red": ((255, 130, 116), (220, 52, 56), (138, 24, 36)),
    "poison": ((214, 236, 120), (134, 176, 46), (70, 106, 26)),
    "wither": ((136, 124, 140), (78, 68, 82), (36, 30, 40)),
    "frozen": ((226, 248, 255), (128, 204, 240), (62, 132, 186)),
    "abs": ((255, 238, 140), (240, 186, 52), (166, 112, 22)),
    "food": ((255, 206, 132), (218, 136, 52), (134, 76, 28)),
    "hunger": ((198, 208, 112), (128, 146, 60), (76, 88, 34)),
    "armor": ((230, 238, 250), (156, 174, 200), (88, 102, 130)),
    "air": ((214, 240, 255), (96, 176, 236), (40, 104, 176)),
    "mount": ((255, 176, 132), (206, 104, 70), (122, 52, 36)),
    "lost": ((255, 255, 255), (255, 226, 222), (226, 170, 170)),
    "gain": ((246, 255, 238), (196, 246, 176), (120, 196, 110)),
}
SAT = (255, 228, 92)
FILL_H = 7
INNER = 50                     # the inside of a bar (the frame is 52 x 9)
PIECES = (1, 2, 4, 8, 16, 32)

OUTLINE = (16, 18, 22)
BAR_RIM = (9, 10, 13)
BAR_BACK = (22, 24, 30, 214)
BAR_BACK_TOP = (12, 13, 17, 214)
RIMS = {"frame": BAR_RIM, "frame_hit": (178, 46, 46), "frame_heal": (70, 170, 76)}

# ------------------------------------------------------------------------------------------------
# Icons (9 x 9: 7 x 7 art and a 1 px outline)
# ------------------------------------------------------------------------------------------------

ICON_COLOURS = {
    "r": (232, 56, 60), "R": (150, 26, 38), "h": (255, 168, 160),          # heart
    "p": (140, 184, 50), "P": (76, 114, 28), "q": (214, 240, 140),         # poisoned heart
    "x": (90, 80, 94), "X": (44, 38, 48), "z": (156, 146, 158),            # withered heart
    "i": (140, 210, 245), "I": (70, 140, 196), "j": (232, 250, 255),       # frozen heart
    "m": (214, 132, 60), "M": (148, 82, 34), "n": (248, 190, 124),         # meat
    "u": (136, 152, 70), "U": (86, 98, 42), "v": (190, 204, 116),          # spoiled meat
    "w": (240, 234, 218), "W": (176, 168, 152),                            # bone, paper
    "s": (170, 186, 210), "S": (104, 120, 148), "t": (232, 240, 250),      # steel
    "g": (246, 198, 78), "G": (184, 130, 40), "y": (255, 240, 168),        # gold
    "b": (126, 88, 52), "B": (84, 58, 36),                                 # wood handle
    "c": (96, 200, 120), "e": (242, 110, 70), "E": (176, 60, 38),          # check, flame
    "a": (132, 200, 250), "A": (56, 118, 196), "o": (236, 248, 255),       # bubble
    "d": (150, 102, 64), "D": (98, 64, 38), "f": (190, 140, 92),           # horse
    "k": (34, 37, 44),
}

ICONS = {
    "heart": [".rr.rr.", "rhhrrrr", "rhrrrrR", "rrrrrrR", ".rrrrR.", "..rrR..", "...R..."],
    "food": ["....mmm", "...mnnmM", "...mnmmM", "...mmmMM", "..wMMMM.", ".w.....", "ww.....", ".w....."],
    "armor": ["tt...sS", "ttsssSS", ".tsssS.", ".tssSS.", ".tsssS.", ".tssSS.", ".SSSSS."],
    "air": ["..AAA..", ".AooaA.", "Aoaaa.A", "Aaaaa.A", "Aaaa..A", ".A...A.", "..AAA.."],
    "mount": ["....D.D", "...ddDD", "..dkddD", ".ddddfD", "ddd.dfD", "dd..dfD", "...ddfD"],
    "coin": ["..ggg..", ".gyygG.", "gygGGgG", "gyGggGG", "ggGGGgG", ".gggGG.", "..GGG.."],
    "job": [".sssss.", "sS...Ss", "....b..", "...b...", "..b....", ".b.....", "B......"],
    "mission": [".wwwww.", ".wkkkW.", ".wwwwW.", ".wkkwW.", ".wwwccW", ".wwccW.", ".Wc.WW."],
    "event": ["...e...", "..eeE..", ".eeyeE.", ".eyyeE.", "eeyygEE", ".eggeE.", "..EEE.."],
    "star": ["...g...", "...g...", "gggyggG", ".gyygG.", ".ggGgG.", ".gG.GG.", ".G...G."],
    "clock": ["..www..", ".wwkwW.", "wwwkwwW", "wwwkkwW", "wwwwwwW", ".wwwwW.", "..WWW.."],
}
for _name, (_a, _b, _c) in {"heart_poison": "pPq", "heart_wither": "xXz", "heart_frozen": "iIj"}.items():
    ICONS[_name] = [row.replace("r", _a).replace("R", _b).replace("h", _c) for row in ICONS["heart"]]
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
DIGIT_EDGE = (20, 16, 18)


def _outlined(rows, size, colours, outline=OUTLINE):
    """rows of colour letters ('.' = empty) inside a size cell, with a 1 px dark outline around them."""
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
    rows = [""] + [("." + r) for r in ICONS[name]]      # one row / column for the outline
    return _outlined(rows, (9, 9), ICON_COLOURS)


def digit(ch):
    rows = ["....."] + ["." + r.replace("#", "f") + "." for r in DIGITS[ch]] + ["....."]
    return _outlined(rows, (5, 7), {"f": DIGIT_FACE}, DIGIT_EDGE)


# ------------------------------------------------------------------------------------------------
# Bars: a 52 x 9 frame, fills of 1 .. 32 px (7 px tall) and a leading edge, segment ticks
# ------------------------------------------------------------------------------------------------

def frame(rim):
    im = Image.new("RGBA", (INNER + 2, 9), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, INNER + 1, 8], fill=rim + (255,))
    d.rectangle([1, 1, INNER, 7], fill=BAR_BACK)
    d.line([1, 1, INNER, 1], fill=BAR_BACK_TOP)
    for x, y in ((0, 0), (INNER + 1, 0), (0, 8), (INNER + 1, 8)):
        im.putpixel((x, y), (0, 0, 0, 0))
    return im


def _fill_column(colours):
    light, base, shade = colours
    return [light, mix(light, base, 0.5), base, base, base, mix(base, shade, 0.5), shade]


def fill_piece(colours, k):
    im = Image.new("RGBA", (32, FILL_H), (0, 0, 0, 0))
    col = _fill_column(colours)
    for x in range(k):
        for y in range(FILL_H):
            im.putpixel((x, y), col[y] + (255,))
    return im


def fill_edge(colours):
    """The leading edge of a fill: a bright column, so a part-filled bar reads as a level."""
    light, base, shade = colours
    im = Image.new("RGBA", (32, FILL_H), (0, 0, 0, 0))
    col = [mix(light, (255, 255, 255), 0.35)] * 2 + [mix(base, light, 0.7)] * 3 + [mix(base, light, 0.3), shade]
    for y in range(FILL_H):
        im.putpixel((0, y), col[y] + (255,))
    return im


def tick(on):
    im = Image.new("RGBA", (1, FILL_H), (0, 0, 0, 0))
    for y in range(FILL_H):
        if on:
            im.putpixel((0, y), (0, 0, 0, 96 if y in (0, FILL_H - 1) else 120))
        elif 0 < y < FILL_H - 1:
            im.putpixel((0, y), (255, 255, 255, 30))
    return im


def line_piece(colour, k):
    im = Image.new("RGBA", (32, 1), (0, 0, 0, 0))
    for x in range(k):
        im.putpixel((x, 0), colour + (255,))
    return im


# ------------------------------------------------------------------------------------------------
# Panel: drawn whole for a sample size, then cut into strips
# ------------------------------------------------------------------------------------------------

PANEL_ALPHA = 236
HEAD_BG = (33, 36, 44)
HEAD_H, ROW_H, FOOT_H = 19, 13, 3     # strips: title (to the gold line and the top padding), a row, the bottom
GOLD_ROW = 15
CAP_L, CAP_R = 4, 4                   # the right cap is one column wider for the drop shadow
STRIP_PIECES = (1, 2, 4, 8, 16, 32, 64)
SHADOW = (0, 0, 0, 84)
DIVIDER = (24, 26, 32)
MARK_LEFT = 5                          # the server mark's x in the title strip


def panel_picture(w, rows):
    """A whole panel w px wide (plus 1 px shadow right and below) with `rows` body rows."""
    h = HEAD_H + ROW_H * rows + FOOT_H - 1           # the bottom outline is the strip's last visible row
    im = Image.new("RGBA", (w + 1, h + 1), (0, 0, 0, 0))
    px = im.load()
    body = T.PANEL + (PANEL_ALPHA,)
    head = HEAD_BG + (244,)
    for y in range(h):
        for x in range(w):
            px[x, y] = head if 2 <= y < GOLD_ROW else body
    for x in range(2, w - 2):
        px[x, GOLD_ROW] = T.GOLD_DIM + (255,)
    for x in range(w):                                 # bevel: light top/left, dark bottom/right
        px[x, 1] = T.EDGE_LIGHT + (255,)
        px[x, h - 2] = T.EDGE_DARK + (255,)
    for y in range(1, h - 1):
        px[1, y] = T.EDGE_LIGHT + (255,)
        px[w - 2, y] = T.EDGE_DARK + (255,)
    px[w - 2, 1] = mix(T.EDGE_LIGHT, T.EDGE_DARK, 0.5) + (255,)
    px[1, h - 2] = mix(T.EDGE_LIGHT, T.EDGE_DARK, 0.5) + (255,)
    for x in range(w):                                 # outline with rounded corners
        px[x, 0] = T.EDGE_OUT + (255,)
        px[x, h - 1] = T.EDGE_OUT + (255,)
    for y in range(h):
        px[0, y] = T.EDGE_OUT + (255,)
        px[w - 1, y] = T.EDGE_OUT + (255,)
    for x, y in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        px[x, y] = (0, 0, 0, 0)
    for y in range(2, h + 1):                          # drop shadow
        px[w, y] = SHADOW
    for x in range(2, w):
        px[x, h] = SHADOW
    return im


def strip_sheet(kind):
    """Cells of 64 x height in one row: 'l' (left cap), 'r' (right cap + shadow), fills of 1 .. 64 px,
    and in the title strip the server mark and its gold accent."""
    sample = panel_picture(CAP_L + 64 + CAP_R, 1)
    y0, hh = {"panel_head": (0, HEAD_H), "panel_row": (HEAD_H, ROW_H),
              "panel_foot": (HEAD_H + ROW_H, FOOT_H)}[kind]
    band = sample.crop((0, y0, sample.width, y0 + hh))
    names, cells = [], []

    def cell(part):
        im = Image.new("RGBA", (64, hh), (0, 0, 0, 0))
        im.alpha_composite(part, (0, 0))
        return im

    names.append("l")
    cells.append(cell(band.crop((0, 0, CAP_L, hh))))
    names.append("r")
    cells.append(cell(band.crop((sample.width - CAP_R - 1, 0, sample.width, hh))))
    column = band.crop((CAP_L + 8, 0, CAP_L + 9, hh))
    for k in STRIP_PIECES:
        im = Image.new("RGBA", (64, hh), (0, 0, 0, 0))
        for x in range(k):
            im.paste(column, (x, 0))
        names.append(str(k))
        cells.append(im)
    if kind == "panel_head":
        logo = server_mark()
        im = Image.new("RGBA", (64, hh), (0, 0, 0, 0))
        im.alpha_composite(logo, (0, 4))
        names.append("mark")
        cells.append(im)
        im = Image.new("RGBA", (64, hh), (0, 0, 0, 0))
        for x in range(logo.width - 1):
            im.putpixel((x, GOLD_ROW), T.GOLD + (255,))
        names.append("accent")
        cells.append(im)
    return names, cells


def divider_sheet():
    names, cells = [], []
    for k in STRIP_PIECES:
        im = Image.new("RGBA", (64, 1), (0, 0, 0, 0))
        for x in range(k):
            im.putpixel((x, 0), DIVIDER + (255,))
        names.append(str(k))
        cells.append(im)
    return names, cells


# The server mark: a gold gem and "STSV" in bold 2 px strokes, a warm gradient, a dark outline (9 px tall)
MARK_LETTERS = {
    "S": [".#####", "##....", "##....", ".####.", "....##", "....##", "#####."],
    "T": ["######", "..##..", "..##..", "..##..", "..##..", "..##..", "..##.."],
    "V": ["##..##", "##..##", "##..##", "##..##", ".####.", ".####.", "..##.."],
}
GEM = ["...#...", "..#h#..", ".#hh##.", "#hh###G", ".##GGG.", "..#GG..", "...G..."]


def server_mark():
    top, bottom = (255, 222, 132), (255, 112, 58)
    rows = [""] * 9
    x = 0
    cells = {}
    for y, r in enumerate(GEM):
        for i, c in enumerate(r):
            if c != ".":
                cells[(x + 1 + i, y + 1)] = {"#": T.GOLD, "h": (255, 236, 160), "G": T.GOLD_DIM}[c]
    x += 7 + 3
    for ch in "STSV":
        for y, r in enumerate(MARK_LETTERS[ch]):
            for i, c in enumerate(r):
                if c == "#":
                    cells[(x + 1 + i, y + 1)] = mix(top, bottom, y / 6)
        x += 6 + 1
    w = max(p[0] for p in cells) + 2
    im = Image.new("RGBA", (w, 9), (0, 0, 0, 0))
    for (cx, cy) in cells:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                q = (cx + dx, cy + dy)
                if q not in cells and 0 <= q[0] < w and 0 <= q[1] < 9 and (dx == 0 or dy == 0):
                    im.putpixel(q, (40, 22, 14, 255))
    for (cx, cy), c in cells.items():
        im.putpixel((cx, cy), c + (255,))
    return im


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
    font = galmuri.load()
    out = {}
    tx = _text_set(font)
    out["text"] = (TEXT_CELL, 16, TEXT_CELL[1], tx, [letter(font, ch, TEXT_CELL) for ch in tx])
    syll = _hangul_set()
    out["hangul"] = (HANGUL_CELL, 64, HANGUL_CELL[1], syll, [letter(font, ch, HANGUL_CELL) for ch in syll])
    dg = list(DIGITS)
    out["digits"] = ((5, 7), 16, 7, dg, [digit(ch) for ch in dg])
    out["icons"] = ((9, 9), 8, 9, list(ICONS), [icon(n) for n in ICONS])
    out["frame"] = ((INNER + 2, 9), 3, 9, list(RIMS), [frame(c) for c in RIMS.values()])
    names, cells = [], []
    for colour, ramp in FILLS.items():
        for k in PIECES:
            names.append(f"{colour}/{k}")
            cells.append(fill_piece(ramp, k))
        names.append(f"{colour}/edge")
        cells.append(fill_edge(ramp))
    out["fill"] = ((32, FILL_H), len(PIECES) + 1, FILL_H, names, cells)
    out["tick"] = ((1, FILL_H), 2, FILL_H, ["on", "off"], [tick(True), tick(False)])
    out["line"] = ((32, 1), len(PIECES), 1, [f"sat/{k}" for k in PIECES], [line_piece(SAT, k) for k in PIECES])
    for kind in ("panel_head", "panel_row", "panel_foot"):
        names, cells = strip_sheet(kind)
        out[kind] = ((64, cells[0].height), len(names), cells[0].height, names, cells)
    names, cells = divider_sheet()
    out["panel_div"] = ((64, 1), len(names), 1, names, cells)
    return out


def write(out_dir: Path = OUT):
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = {}
    built = sheets()
    for f in out_dir.glob("*.png"):
        if f.stem not in built:
            f.unlink()
    old = out_dir / "LICENSE-unifont.txt"
    if old.exists():
        old.unlink()
    for name, (cell, cols, height, names, cells) in built.items():
        _sheet(cells, cell, cols).save(out_dir / f"{name}.png", optimize=True)
        manifest[name] = {"file": f"{name}.png", "cell": list(cell), "cols": cols, "height": height, "names": names}
    (out_dir / "sheets.json").write_text(json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n",
                                         encoding="utf-8")
    (out_dir / "LICENSE-galmuri.txt").write_text(
        "text.png and hangul.png hold letters drawn from Galmuri9 (https://galmuri.quiple.dev), version 2.40.3.\n"
        "The other HUD pictures are STSV's own.\n\n" + galmuri.LICENSE.read_text(encoding="utf-8"),
        encoding="utf-8")
    return {k: len(v[3]) for k, v in built.items()}


# ------------------------------------------------------------------------------------------------
# Preview: the draw list the core writes (HudPreviewTest) on mock screens
# ------------------------------------------------------------------------------------------------

def _scene(w, h, water=False):
    """A plain daytime backdrop: sky gradient, a horizon of hills and grass (no game textures)."""
    im = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(im)
    for y in range(h):
        t = y / h
        if water:
            c = (int(40 + 30 * (1 - t)), int(90 + 50 * (1 - t)), int(160 + 40 * (1 - t)))
        elif t < 0.55:
            c = (int(120 + 60 * t), int(170 + 50 * t), 235)
        else:
            u = (t - 0.55) / 0.45
            c = (int(92 - 30 * u), int(140 - 40 * u), int(64 - 20 * u))
        d.line([(0, y), (w, y)], fill=c + (255,))
    if not water:
        for k in range(7):
            x = k * w // 6
            d.ellipse([x - w // 5, int(h * 0.48), x + w // 5, int(h * 0.7)], fill=(70 + k * 4, 112, 70, 255))
    return im


class Glyphs:
    def __init__(self):
        self.manifest = json.loads((OUT / "sheets.json").read_text(encoding="utf-8"))
        self.imgs = {k: Image.open(OUT / v["file"]).convert("RGBA") for k, v in self.manifest.items()}
        self.cache = {}

    def cell(self, sheet, name):
        key = (sheet, name)
        if key not in self.cache:
            m = self.manifest[sheet]
            k = m["names"].index(name)
            cw, ch = m["cell"]
            self.cache[key] = self.imgs[sheet].crop(((k % m["cols"]) * cw, (k // m["cols"]) * ch,
                                                     (k % m["cols"]) * cw + cw, (k // m["cols"]) * ch + ch))
        return self.cache[key]


def _put(screen, glyph, x, y, s, colour=(255, 255, 255), shadow=False):
    """A glyph at GUI (x, y), scaled by s, multiplied by colour, alpha under 0.1 dropped like the game."""
    g = glyph.resize((glyph.width * s, glyph.height * s), Image.NEAREST)
    a = g.split()[3].point(lambda v: 0 if v < 26 else v)
    if shadow:
        sh = Image.new("RGBA", g.size, tuple(v // 4 for v in colour) + (255,))
        sh.putalpha(a)
        screen.alpha_composite(sh, (int((x + 1) * s), int((y + 1) * s)))
    if colour != (255, 255, 255):
        r, gg, b, _ = g.split()
        g = Image.merge("RGBA", (r.point(lambda v: v * colour[0] // 255), gg.point(lambda v: v * colour[1] // 255),
                                 b.point(lambda v: v * colour[2] // 255), a))
    else:
        g.putalpha(a)
    screen.alpha_composite(g, (int(x * s), int(y * s)))


def _mock_vanilla(screen, glyphs, gw, gh, s, level, effects):
    """Stand-ins for what the game still draws: hotbar, experience bar and level, status effect icons."""
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
    if level:
        # the level number: the game's font in green with a black outline (drawn here with the HUD's letters)
        text = str(level)
        w = sum(_advance(glyphs, ch) for ch in text) - 1
        x0, y0 = (gw - w) // 2, gh - 35 - 2
        for dx, dy, col in ((1, 0, (0, 0, 0)), (-1, 0, (0, 0, 0)), (0, 1, (0, 0, 0)), (0, -1, (0, 0, 0)), (0, 0, (128, 255, 32))):
            x = x0 + dx
            for ch in text:
                _put(screen, glyphs.cell("text", ch), x, y0 + dy, s, col)
                x += _advance(glyphs, ch)
    for i in range(effects):
        x = gw - 25 * (i + 1)
        rect(x, 1, x + 24, 25, (54, 58, 66, 230), (140, 140, 150, 255))
        rect(x + 3, 4, x + 21, 22, [(200, 80, 220, 255), (90, 170, 255, 255), (240, 200, 60, 255)][i % 3])


def _advance(glyphs, ch):
    im = glyphs.cell("text", ch)
    w = 0
    for x in range(im.width - 1, -1, -1):
        if any(im.getpixel((x, y))[3] for y in range(im.height)):
            w = x + 1
            break
    return w + 1


def render(scene, glyphs):
    W, H, s = scene["width"], scene["height"], scene["scale"]
    gw, gh = -(-W // s), -(-H // s)
    screen = _scene(W, H, scene.get("water", False))
    _mock_vanilla(screen, glyphs, gw, gh, s, scene.get("level", 0), scene.get("effects", 0))
    for dr in scene["draws"]:
        x, y = dr["x"] + gw // 2, dr["y"] + (gh if dr["anchor"] == "bottom" else 0)
        colour = tuple(int(dr.get("color", "ffffff")[i:i + 2], 16) for i in (0, 2, 4))
        _put(screen, glyphs.cell(dr["sheet"], dr["cell"]), x, y, s, colour, dr.get("shadow", False))
    return screen


def _label(im, text, size=16):
    d = ImageDraw.Draw(im)
    font = ImageFont.truetype("/usr/share/fonts/opentype/unifont/unifont.otf", size)
    w = int(d.textlength(text, font=font)) + 20
    d.rectangle([0, 0, w, size + 14], fill=(0, 0, 0, 190))
    d.text((10, 6), text, font=font, fill=(255, 255, 255, 255))


def _caption(im, text, size=16):
    """The picture with a caption strip above it."""
    out = Image.new("RGBA", (im.width, im.height + size + 12), (12, 12, 14, 255))
    font = ImageFont.truetype("/usr/share/fonts/opentype/unifont/unifont.otf", size)
    ImageDraw.Draw(out).text((8, 5), text, font=font, fill=(235, 235, 235, 255))
    out.alpha_composite(im, (0, size + 12))
    return out


def _stack(panels, out, width=None):
    w = width or max(p.width for p in panels)
    sheet = Image.new("RGBA", (w, sum(p.height for p in panels) + 8 * (len(panels) - 1)), (20, 20, 24, 255))
    y = 0
    for p in panels:
        sheet.alpha_composite(p, (0, y))
        y += p.height + 8
    sheet.convert("RGB").save(out, optimize=True)


def preview(draws_path: Path, out_dir: Path):
    """draws.json (HudPreviewTest): {"pages": {page: [scene, ...]}}; a scene: width, height, scale, label,
    optional crop [gui x0, gui y0 from the bottom, gui w, gui h] and zoom, and every glyph as the game places
    it (gui x from the middle, y from the top or the bottom, sheet, cell, colour, shadow)."""
    data = json.loads(Path(draws_path).read_text(encoding="utf-8"))
    glyphs = Glyphs()
    for page, scenes in data["pages"].items():
        panels = []
        for sc in scenes:
            im = render(sc, glyphs)
            if "crop" in sc:
                s = sc["scale"]
                gw, gh = -(-sc["width"] // s), -(-sc["height"] // s)
                cx, cy, cw, ch = sc["crop"]
                box = ((gw // 2 + cx) * s, (gh - cy) * s, (gw // 2 + cx + cw) * s, (gh - cy + ch) * s)
                im = im.crop(box)
                z = sc.get("zoom", 1)
                im = im.resize((im.width * z, im.height * z), Image.NEAREST)
                im = _caption(im, sc["label"])
            else:
                if sc.get("thumb"):
                    im = im.resize((sc["thumb"], im.height * sc["thumb"] // im.width), Image.LANCZOS)
                _label(im, f"{sc['width']} x {sc['height']} · GUI {sc['scale']} · {sc['label']}")
            panels.append(im)
        if page.endswith("states"):
            cols = 2
            cw = max(p.width for p in panels)
            rows = [panels[i:i + cols] for i in range(0, len(panels), cols)]
            lines = []
            for r in rows:
                line = Image.new("RGBA", (cw * cols + 8 * (cols - 1), max(p.height for p in r)), (20, 20, 24, 255))
                for i, p in enumerate(r):
                    line.alpha_composite(p, (i * (cw + 8), 0))
                lines.append(line)
            _stack(lines, out_dir / f"{page}.png")
        else:
            _stack(panels, out_dir / f"{page}.png")


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "--preview":
        preview(Path(sys.argv[2]), Path(sys.argv[3]))
    else:
        print(write())
