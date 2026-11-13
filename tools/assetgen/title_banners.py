"""
Title banners (칭호 배너, admin test feature): an animated nameplate "칭호 ◆ 닉네임" whose plate hugs the text.

    python3 title_banners.py            # glyph sheets, fonts, banners.json (also run by build.py)
    python3 title_banners.py --preview  # + docs/previews/title_banners*.png

The approved art (banner_themes/: one module per theme, 16 frames, procedural) is drawn without its text, at its
own canonical size, then cut into parts the plugin glues together with space glyphs at any width:

    L (left end + ornaments) | left fill | C (centre ornament, may be empty) | right fill | R (right end)

A fill is whole tiles of P columns taken from the plate where the tile joins itself best (searched per theme),
plus aligned slices of 1, 2, 4, 8 ... columns of the same tile for the rest, so any width is exact and nothing
overlaps (overlapping glass would blend twice). The gem between title and nickname is a glyph of its own.

Every part exists in 16 frames. Frame 0 is an ordinary glyph. Frames 1-15 are "ghosts": their alpha is stored
as 1..24 (under the game's text cut-off 0.1), so a client without the pack's text shader draws frame 0 only, and
the shader (plugin resources pack/title_banners/shaders) decodes them and shows one frame at a time by game time.
The plugin marks banner glyphs by their text colour (see TitleBannerPack.java). For the server-side fallback
the plugin decodes the ghosts back to plain alpha when it builds the pack.

Two sizes: "big" (1 texel = 1 GUI pixel, the name tag above the head) and "mini" (the plate band, 18 rows, drawn
at half size so it fits a 9 px chat / tab list / lore line).

Text: the title in Galmuri9 (ASCII, signs, KS X 1001 Hangul + every syllable the repository's texts use), the
nickname in the tall 5 x 7 face (banner_themes/letters.py) with Hangul from Galmuri9. Glyphs are white; the plugin
tints them per character with the theme's colours (the game multiplies a glyph by its text colour), so one sheet
serves every theme, and gives the full size the game's drop shadow.

Draw order: the game (26.3) sorts the glyphs of one GUI layer by pipeline, then by atlas texture (an arbitrary
order), so a letter over a plate glyph from another atlas page could end up under it. The text sheets and the gem
are one-channel PNGs: the game draws those with its grayscale text pipeline, which is created after the coloured
one and so always sorts after it. Plate parts are coloured; text and gem always land on top.

Partial alpha: the game discards text pixels under alpha 0.1; faint glows (alpha under 26) are dithered to
alpha 26 or nothing (4 x 4 Bayer), so their average stays.

Output: plugin/src/main/resources/pack/title_banners/ (pack files under assets/, banners.json for the layout).
"""

import json
import math
import shutil
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import galmuri  # noqa: E402
from banner_themes import state  # noqa: E402
from banner_themes.letters import TALL, CHARSET  # noqa: E402

REPO = HERE.parent.parent
OUT = REPO / "plugin" / "src" / "main" / "resources" / "pack" / "title_banners"
PREVIEWS = REPO / "docs" / "previews"
NS = "stsv"
N = 16                        # frames
GHOST_LEVELS = 24             # ghost alpha 1..24 (24/255 < 0.1)
MIN_ALPHA = 26                # the game's text cut-off (0.1) in 0..255, rounded up
PAD, GAP = 8, 11              # plate padding around the text, title-gem-name gap (as the approved mock-ups)
SPACE = 4                     # a space in the title
TEXT_CELL_H = 12              # text glyph cells: rows 0..8 above the baseline, shadow and descenders below
MINI_ROWS = (-8, 10)          # the mini band: rows MID-8 .. MID+9

# theme list (data driven: add a module in banner_themes/ and a line here). tile = P, centre = half width of the
# centre ornament slice (0 = none); tints: title / nickname colours, left to right, multiplied into the white glyphs
THEMES = [
    dict(id="fire", module="fire", name="화염", tile=16, centre=0,
         title=((255, 222, 128), (255, 176, 64)), nick=((255, 244, 200), (255, 150, 40))),
    dict(id="lightning", module="lightning", name="번개", tile=32, centre=0,
         title=((236, 244, 255), (168, 196, 246)), nick=((206, 244, 255), (96, 176, 255))),
    dict(id="neon", module="neon", name="네온", tile=16, centre=4,
         title=((200, 250, 255), (80, 214, 255)), nick=((255, 180, 238), (255, 70, 206))),
    dict(id="bloodmoon", module="bloodmoon", name="혈월", tile=16, centre=26,
         title=((244, 234, 238), (204, 184, 194)), nick=((255, 150, 150), (226, 36, 48))),
    dict(id="prism", module="prism", name="프리즘", tile=16, centre=0,
         title=((255, 255, 255), (224, 228, 248)), nick=((246, 242, 255), (204, 190, 252))),
]

BAYER4 = ((0, 8, 2, 10), (12, 4, 14, 6), (3, 11, 1, 9), (15, 7, 13, 5))

# space glyphs (every font has them): advances +-0.5, 1, 2, ... 1024 GUI pixels
SPACE_STEPS = [0.5 * 2 ** k for k in range(12)]
SPACE_BASE = 0xF800


def space_chars():
    out = {}
    for k, v in enumerate(SPACE_STEPS):
        out[chr(SPACE_BASE + 2 * k)] = v
        out[chr(SPACE_BASE + 2 * k + 1)] = -v
    out[" "] = SPACE
    return out


# ------------------------------------------------------------------------------------------------
# Drawing a theme without text, cutting it
# ------------------------------------------------------------------------------------------------

def fix_alpha(im):
    """drop alpha under the game's cut-off by dithering it to MIN_ALPHA or nothing"""
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if 0 < a < MIN_ALPHA:
                keep = a / MIN_ALPHA * 16 > BAYER4[y % 4][x % 4] + 0.5
                px[x, y] = (r, g, b, MIN_ALPHA) if keep else (0, 0, 0, 0)
    return im


def drop_orphans(im):
    px = im.load()
    W, H = im.size
    kill = [(x, y) for y in range(H) for x in range(W) if px[x, y][3] and not any(
        0 <= x + a < W and 0 <= y + b < H and px[x + a, y + b][3] for a in (-1, 0, 1) for b in (-1, 0, 1) if a or b)]
    for q in kill:
        px[q] = (0, 0, 0, 0)


def render(theme):
    import importlib
    m = importlib.import_module("banner_themes." + theme["module"])
    G = m.Geo(m.TITLE, **m.GEO)
    bg, gems = [], []
    for i in range(N):
        state.GEM["mode"] = "bg"
        f = m.frame(G, i)
        drop_orphans(f)
        bg.append(fix_alpha(f))
        state.GEM["mode"] = "gem"
        state.GEM["pix"] = set()
        g = m.frame(G, i)
        gem = Image.new("RGBA", g.size, (0, 0, 0, 0))
        gp, sp = gem.load(), g.load()
        for q in state.GEM["pix"]:
            if 0 <= q[0] < g.width and 0 <= q[1] < g.height:
                gp[q] = sp[q]
        gems.append(fix_alpha(gem))
    state.GEM["mode"] = "bg"
    box = None
    for f in bg + gems:
        b = f.getbbox()
        if b:
            box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
    box = (max(0, box[0] - 1), max(0, box[1] - 1), min(bg[0].width, box[2] + 1), min(bg[0].height, box[3] + 1))
    bg = [f.crop(box) for f in bg]
    gems = [f.crop(box) for f in gems]
    ox, oy = box[0], box[1]
    div = getattr(G, "DIV")
    geo = dict(px0=G.PX0 - ox, px1=G.PX1 - ox, mid=G.MID - oy, cx=int(round(G.CX)) - ox, div=div - ox, title=m.TITLE)
    return bg, gems, geo


def column(frames, x):
    return [f.getpixel((x, y)) for f in frames for y in range(f.height)]


def seam_cost(frames, a, b):
    """how unlike columns a and b are over all frames (what a tile seam a -> b looks like)"""
    ca, cb = column(frames, a), column(frames, b)
    cost = 0
    for p, q in zip(ca, cb):
        if p[3] == 0 and q[3] == 0:
            continue
        cost += abs(p[3] - q[3]) + sum(abs(p[k] - q[k]) for k in range(3)) * (min(p[3], q[3]) / 255)
    return cost


def cut(theme, bg, gems, geo):
    P = theme["tile"]
    hc = theme["centre"]
    W = bg[0].width
    px0, px1, cx = geo["px0"], geo["px1"], geo["cx"]
    c0, c1 = (cx - hc, cx + hc) if hc else (cx, cx)
    # left tile: somewhere between the left end (its slope, rivets) and the centre slice
    lo, hi = px0 + 10, c0 - P
    left = min(range(lo, max(lo, hi) + 1), key=lambda t: (seam_cost(bg, t, t + P), abs(t - lo)))
    if hc:
        lo, hi = c1, px1 - 9 - P
        right = min(range(lo, max(lo, hi) + 1), key=lambda t: (seam_cost(bg, t, t + P), abs(t - hi)))
    else:
        # no centre ornament: one fill (the left tile) up to the right end, which keeps 10 plate columns
        right = px1 + 1 - 10 - P
    gem_box = None
    for g in gems:
        b = g.getbbox()
        if b:
            gem_box = b if gem_box is None else (min(gem_box[0], b[0]), min(gem_box[1], b[1]), max(gem_box[2], b[2]), max(gem_box[3], b[3]))
    return dict(P=P, left=left, right=right, c0=c0, c1=c1, W=W, gem_box=gem_box, fills=2 if hc else 1)


def slices(P):
    """every (offset, width) piece a fill of any remainder 1..P-1 needs: prefixes for the left fill (largest
    first), suffixes for the right fill (smallest first). Mirrors TitleBannerLayout.prefix / suffix."""
    out = set()
    for r in range(1, P):
        out.update(prefix(r))
        out.update(suffix(r, P))
    return sorted(out, key=lambda s: (s[1], s[0]))


def prefix(r):
    out, o = [], 0
    for w in sorted((1 << k for k in range(10) if r & (1 << k)), reverse=True):
        out.append((o, w))
        o += w
    return out


def suffix(r, P):
    out, o = [], P - r
    for w in sorted(1 << k for k in range(10) if r & (1 << k)):
        out.append((o, w))
        o += w
    return out


# ------------------------------------------------------------------------------------------------
# Sheets
# ------------------------------------------------------------------------------------------------

def measured(im):
    """the game's glyph width: rightmost column with any alpha, + 1 (0 when empty)"""
    a = im.getchannel("A").load()
    for x in range(im.width - 1, -1, -1):
        if any(a[x, y] for y in range(im.height)):
            return x + 1
    return 0


def ghost(im):
    """frames 1..15: alpha stored as 1..24 so the game's text cut-off hides them without the shader"""
    out = im.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a:
                px[x, y] = (r, g, b, max(1, math.ceil(a * GHOST_LEVELS / 255)))
    return out


def band(im, mid):
    y0 = mid + MINI_ROWS[0]
    out = Image.new("RGBA", (im.width, MINI_ROWS[1] - MINI_ROWS[0]), (0, 0, 0, 0))
    out.alpha_composite(im.crop((0, max(0, y0), im.width, min(im.height, y0 + out.height))), (0, max(0, -y0)))
    return out


class Fonts:
    """collects bitmap providers per font and the metadata of every glyph"""

    def __init__(self):
        self.files = {}           # pack path -> PIL image / bytes / json
        self.fonts = {}           # font name -> providers

    def provider(self, font, path, cells, height, ascent, gray=False):
        """cells: rows (frames) of (char, image); one PNG, equal cells. gray: a one-channel PNG of the cells' alpha
        (white ink): the game draws such glyphs with its grayscale text pipeline, after every coloured glyph of the
        same layer, so text on a plate is never hidden by it (see the module docstring)"""
        cw = max(1, max(im.width for row in cells for _, im in row))
        ch = max(im.height for row in cells for _, im in row)
        sheet = Image.new("RGBA", (cw * len(cells[0]), ch * len(cells)), (0, 0, 0, 0))
        chars = []
        for r, row in enumerate(cells):
            chars.append("".join(c for c, _ in row))
            for k, (_, im) in enumerate(row):
                sheet.alpha_composite(im, (k * cw, r * ch))
        self.files[f"assets/{NS}/textures/{path}.png"] = sheet.getchannel("A") if gray else sheet
        self.fonts.setdefault(font, []).append({"type": "bitmap", "file": f"{NS}:{path}.png", "height": height,
                                                "ascent": ascent, "chars": chars})

    def finish(self):
        adv = {c: v for c, v in space_chars().items()}
        for font, providers in self.fonts.items():
            self.files[f"assets/{NS}/font/title_banner/{font}.json"] = {
                "providers": [{"type": "space", "advances": adv}] + providers}


class Codes:
    def __init__(self, base=0xE000):
        self.next = base

    def take(self):
        c = chr(self.next)
        self.next += 1
        if self.next >= SPACE_BASE:
            raise RuntimeError("out of private-use code points")
        return c


def theme_sheets(theme, fonts):
    bg, gems, geo = render(theme)
    c = cut(theme, bg, gems, geo)
    H, W, P = bg[0].height, c["W"], c["P"]
    mid = geo["mid"]
    codes = Codes()
    parts = {
        "L": (0, c["left"]),
        "C": (c["c0"], c["c1"]),
        "R": (c["right"] + P, W),
        "TL": (c["left"], c["left"] + P),
        "TR": (c["right"], c["right"] + P) if c["fills"] == 2 else (0, 0),
    }
    sl = slices(P)
    meta = dict(id=theme["id"], name=theme["name"], height=H, mid=mid, frames=N,
                plate=dict(left=geo["px0"], right=geo["px1"]), tile=P,
                left=c["left"], right=c["right"], c0=c["c0"], c1=c["c1"], width=W, fills=c["fills"],
                tint=dict(title=[list(theme["title"][0]), list(theme["title"][1])],
                          nick=[list(theme["nick"][0]), list(theme["nick"][1])]),
                glyphs={})
    gx0, gy0, gx1, gy1 = c["gem_box"]
    meta["gem"] = dict(x=gx0 - geo["div"], width=gx1 - gx0)

    def cells_for(images_per_frame):
        """[(name, [16 images])] -> rows of (char, image) and the glyph metadata"""
        rows = [[] for _ in range(N)]
        for name, ims in images_per_frame:
            chars = ""
            wb, wm = [], []
            for f in range(N):
                ch = codes.take()
                chars += ch
                big = ims[f] if f == 0 else ghost(ims[f])
                rows[f].append((ch, big))
                wb.append(measured(ims[f]))
                wm.append(measured(band(ims[f], mid)))
            meta["glyphs"][name] = dict(chars=chars, big=wb, mini=wm)
        return rows

    groups = {}
    for name, (x0, x1) in parts.items():
        if x1 <= x0:
            continue
        groups.setdefault(name, []).append((name, [f.crop((x0, 0, x1, H)) for f in bg]))
    for side, x0 in (("TL", c["left"]), ("TR", c["right"]))[:c["fills"]]:
        for (o, w) in sl:
            groups.setdefault(f"S{w}", []).append((f"{side}:{o}:{w}", [f.crop((x0 + o, 0, x0 + o + w, H)) for f in bg]))

    for group, entries in groups.items():
        rows = cells_for(entries)
        fonts.provider(theme["id"], f"font/title_banner/{theme['id']}/{group.lower()}", rows,
                       height=H, ascent=mid + 4)
        # mini: the plate band, drawn at half size
        mini_rows = []
        for f, row in enumerate(rows):
            mini_rows.append([])
            for (ch, im), (name, ims) in zip(row, entries):
                src = ims[f] if f == 0 else ghost(ims[f])
                mini_rows[-1].append((ch, band(src, mid)))
        fonts.provider(theme["id"] + "_mini", f"font/title_banner/{theme['id']}/mini_{group.lower()}", mini_rows,
                       height=(MINI_ROWS[1] - MINI_ROWS[0]) // 2, ascent=-MINI_ROWS[0] // 2 + 4)
    meta["min_plate"] = (c["left"] - geo["px0"]) + (c["c1"] - c["c0"]) + (geo["px1"] + 1 - c["right"] - P)
    meta["gem"]["y"] = gy0
    meta["gem"].update(gem_glyph(theme, gems[0].crop((gx0, gy0, gx1, gy1)), gy0, mid, fonts))
    return meta, bg, gems


GEM_BASE = 0xE100


def gem_glyph(theme, im, top, mid, fonts):
    """The gem between title and nickname: frame 0 as a grayscale glyph (its brightness and alpha in one channel),
    tinted with its own colour by the plugin. Grayscale like the text, so it is drawn over the plate (module
    docstring). A bitmap's ascent may not exceed its height: the cells reach down to the baseline."""
    k = [t["id"] for t in THEMES].index(theme["id"])
    ch = chr(GEM_BASE + k)
    px = im.load()
    wsum, acc = 0.0, [0.0, 0.0, 0.0]
    lum = Image.new("L", im.size, 0)
    lp = lum.load()
    peak = max(1, max(max(px[x, y][:3]) for y in range(im.height) for x in range(im.width) if px[x, y][3]))
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a:
                v = max(r, g, b) / peak
                lp[x, y] = max(MIN_ALPHA, round(255 * v * a / 255))
                for j, c in enumerate((r, g, b)):
                    acc[j] += c * a
                wsum += a
    mean = [c / wsum for c in acc]
    top_c = max(mean) or 1
    tint = [round(c * 255 / top_c) for c in mean]
    white = Image.merge("RGBA", (lum.point(lambda v: 255 if v else 0),) * 3 + (lum,))
    base = mid + 4                         # the baseline row (the plate's mid row is 4 px above it)
    big = Image.new("RGBA", (im.width, max(base, top + im.height) - top), (0, 0, 0, 0))
    big.alpha_composite(white, (0, 0))
    fonts.provider("title", f"font/title_banner/gem/{theme['id']}", [[(ch, big)]], height=big.height,
                   ascent=base - top, gray=True)
    # mini: row y is 4 + (mid - y) / 2 px above the baseline, as on the plate's band
    t2 = top - ((mid - top) % 2)
    mini = Image.new("RGBA", (im.width, mid + 8 - t2), (0, 0, 0, 0))
    mini.alpha_composite(white, (0, top - t2))
    fonts.provider("title_mini", f"font/title_banner/gem/mini_{theme['id']}", [[(ch, mini)]], height=mini.height // 2,
                   ascent=4 + (mid - t2) // 2, gray=True)
    return dict(char=ch, tint=tint, big=measured(big), mini=measured(mini))


# ------------------------------------------------------------------------------------------------
# Text sheets
# ------------------------------------------------------------------------------------------------

SHADOW = ((1, 0), (0, 1), (1, 1))
SHADE = (44, 44, 48)


def text_glyph(ink, cell_w):
    """ink: [(x, y)] rows 0..8 above the baseline, white (a grayscale glyph: the plugin tints it per letter and gives
    it the game's own drop shadow in a dark theme colour)"""
    im = Image.new("RGBA", (cell_w, TEXT_CELL_H), (0, 0, 0, 0))
    px = im.load()
    for x, y in ink:
        if 0 <= x < cell_w and 0 <= y < TEXT_CELL_H:
            px[x, y] = (255, 255, 255, 255)
    return im


def galmuri_ink(font, ch):
    adv, xo, yo, bits = font[ord(ch)]
    top = 9 - (yo + len(bits))
    return [(xo + x, top + y) for y, r in enumerate(bits) for x, b in enumerate(r) if b]


def text_sheets(fonts):
    import hud
    F9 = galmuri.load(9)
    hangul = hud._hangul_set()
    title_latin = [chr(c) for c in range(0x21, 0x7F)] + [s for s in "·•…→←★☆◆◇♪❤" if ord(s) in F9]
    title_latin = [ch for ch in title_latin if ord(ch) in F9 and F9[ord(ch)][3]]
    meta = {}

    def sheet(name, chars, ink_of, cell_w, cols):
        cells, widths = [], {}
        for ch in chars:
            im = text_glyph(ink_of(ch), cell_w)
            cells.append((ch, im))
            widths[ch] = [measured(im) + 1, measured(im)]
        rows = [cells[k:k + cols] for k in range(0, len(cells), cols)]
        if len(rows) > 1 and len(rows[-1]) < cols:
            pad = cols - len(rows[-1])
            rows[-1] = rows[-1] + [("\u0000", Image.new("RGBA", (cell_w, TEXT_CELL_H)))] * pad
        return rows, widths

    tl_rows, tl_w = sheet("title_latin", title_latin, lambda ch: galmuri_ink(F9, ch), 9, 16)
    hg_rows, hg_w = sheet("hangul", hangul, lambda ch: galmuri_ink(F9, ch), 11, 64)
    nl_rows, nl_w = sheet("nick_latin", list(CHARSET),
                          lambda ch: [(x, y + 1) for y, r in enumerate(TALL[ch]) for x, v in enumerate(r) if v == "#"], 7, 16)
    for rows in (tl_rows, hg_rows, nl_rows):      # padding cells: no glyph
        for row in rows:
            for k, (ch, im) in enumerate(row):
                if ch == "\u0000":
                    row[k] = (" ", im)
    # the "chars" of a provider may not repeat a code point: drop the padding ones
    def clean(rows):
        return [[(ch if ch != " " else None, im) for ch, im in row] for row in rows]

    for font, sheets in (("title", (("latin", tl_rows), ("hangul", hg_rows))), ("nick", (("nick_latin", nl_rows), ("hangul", hg_rows)))):
        for size, h, asc in (("", TEXT_CELL_H, 8), ("_mini", TEXT_CELL_H // 2, 6)):
            for sname, rows in sheets:
                cells = [[(ch if ch is not None else "\u0000", im) for ch, im in row] for row in clean(rows)]
                fonts.provider(font + size, f"font/title_banner/text/{sname}", cells, height=h, ascent=asc, gray=True)
    meta["title"] = {**tl_w, **hg_w}
    meta["nick"] = {**nl_w, **hg_w}
    meta["space"] = SPACE
    return meta


# ------------------------------------------------------------------------------------------------
# Write
# ------------------------------------------------------------------------------------------------

def write(out_dir: Path = OUT):
    fonts = Fonts()
    themes, renders = [], {}
    for t in THEMES:
        meta, bg, gems = theme_sheets(t, fonts)
        themes.append(meta)
        renders[t["id"]] = (bg, gems)
    text = text_sheets(fonts)
    fonts.finish()
    if out_dir.exists():
        shutil.rmtree(out_dir / "assets", ignore_errors=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    total = 0
    for path, data in sorted(fonts.files.items()):
        f = out_dir / path
        f.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, Image.Image):
            data.save(f, optimize=True)
        else:
            f.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        total += f.stat().st_size
    banners = dict(frames=N, ghost_levels=GHOST_LEVELS, pad=PAD, gap=GAP, mini_rows=list(MINI_ROWS),
                   space=dict(base=SPACE_BASE, steps=SPACE_STEPS), text=text, themes=themes,
                   ghosts=sorted(p for p in fonts.files if p.endswith(".png") and "/text/" not in p),
                   files=sorted(fonts.files))
    (out_dir / "banners.json").write_text(json.dumps(banners, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    total += (out_dir / "banners.json").stat().st_size
    return f"{len(themes)} themes, {len(fonts.files)} files, {total / 1024:.0f} KB", renders


# ------------------------------------------------------------------------------------------------
# Preview: the same layout the plugin makes (TitleBannerLayout), composed from the written sheets
# ------------------------------------------------------------------------------------------------

def layout(meta, text, title, nick):
    """[(kind, name, x)] in big pixels and the banner width; mirrors TitleBannerLayout.java"""
    def width(table, s):
        return sum(SPACE if ch == " " else table[ch][0] for ch in s if ch == " " or ch in table)
    tw, nw = width(text["title"], title), width(text["nick"], nick)
    content = tw + GAP + nw if title else nw
    pw = max(meta["min_plate"], content + 2 * PAD)
    P = meta["tile"]
    pl, pr = meta["plate"]["left"], meta["plate"]["right"]
    cw = meta["c1"] - meta["c0"]
    extra = pw - meta["min_plate"]
    fl = max(0, min(extra, pl + (pw - cw) // 2 - meta["left"])) if meta["fills"] == 2 else extra
    fr = extra - fl
    out = [("G", "L", 0)]
    x = meta["left"]

    def fill(side, w, x, left):
        full, rest = divmod(w, P)
        seq = []
        pieces = [(f"T{side}", P)] * full
        rem = [(f"{'TL' if left else 'TR'}:{o}:{s}", s) for o, s in (prefix(rest) if left else suffix(rest, P))] if rest else []
        for name, s in (pieces + rem if left else rem + pieces):
            seq.append(("G", name, x))
            x += s
        return seq, x

    seq, x = fill("L", fl, x, True)
    out += seq
    if cw:
        out.append(("G", "C", x))
        x += cw
    seq, x = fill("R", fr, x, False)
    out += seq
    out.append(("G", "R", x))
    plate_left = pl
    start = plate_left + (pw - content) // 2
    if title:
        out.append(("T", title, start))
        out.append(("G", "G", start + tw + GAP // 2 + meta["gem"]["x"]))
    out.append(("N", nick, start + (tw + GAP if title else 0)))
    total = x + (meta["width"] - meta["right"] - P)
    return out, total


def preview():
    import io
    from PIL import ImageDraw
    banners = json.loads((OUT / "banners.json").read_text(encoding="utf-8"))
    text = banners["text"]

    def sheet_glyph(font, ch):
        for prov in json.loads((OUT / f"assets/{NS}/font/title_banner/{font}.json").read_text(encoding="utf-8"))["providers"]:
            if prov["type"] != "bitmap":
                continue
            for r, row in enumerate(prov["chars"]):
                if ch in row:
                    im = Image.open(OUT / f"assets/{NS}/textures/{prov['file'].split(':', 1)[1]}")
                    cw, chh = im.width // len(row), im.height // len(prov["chars"])
                    k = row.index(ch)
                    cell = im.crop((k * cw, r * chh, (k + 1) * cw, (r + 1) * chh))
                    if cell.mode == "L":      # grayscale glyph: the game uses the value as colour and alpha
                        cell = Image.merge("RGBA", (cell,) * 4)
                    return cell.convert("RGBA"), prov["ascent"], prov["height"] / chh
        return None, 0, 1

    def tint(im, col):
        px = im.load()
        out = im.copy()
        q = out.load()
        for y in range(im.height):
            for x in range(im.width):
                r, g, b, a = px[x, y]
                q[x, y] = (r * col[0] // 255, g * col[1] // 255, b * col[2] // 255, a)
        return out

    def unghost(im):
        out = im.copy()
        px = out.load()
        for y in range(out.height):
            for x in range(out.width):
                r, g, b, a = px[x, y]
                if a:
                    px[x, y] = (r, g, b, min(255, round(a * 255 / GHOST_LEVELS)))
        return out

    def compose(meta, title, nick, frame, size=""):
        """in texels: the mini size is drawn at its own resolution (one texel = half a GUI pixel), as a client
        at GUI scale 2 or more shows it"""
        seq, total = layout(meta, text, title, nick)
        base = 40   # baseline row on the canvas
        canvas = Image.new("RGBA", (total + 8, 64), (0, 0, 0, 0))

        def put(im, asc, x, s, dy=0):
            if im is not None:
                canvas.alpha_composite(im, (int(round(x)) + 4, int(round(base - asc / s)) + dy))

        g = meta["glyphs"]
        tints = meta["tint"]

        shadow_px = 1 if size == "" else 0       # the game's drop shadow (1 GUI pixel), at full size only
        for kind, name, x in seq:
            if kind == "G" and name == "G":
                im, asc, s = sheet_glyph("title" + size, meta["gem"]["char"])
                put(tint(im, meta["gem"]["tint"]), asc, x, s)
            elif kind == "G":
                ch = g[name]["chars"][frame]
                im, asc, s = sheet_glyph(meta["id"] + size, ch)
                if frame:
                    im = unghost(im)
                put(im, asc, x, s)
            else:
                table = text["title" if kind == "T" else "nick"]
                cols = tints["title" if kind == "T" else "nick"]
                n = max(1, len(name) - 1)
                for k, ch in enumerate(name):
                    if ch == " ":
                        x += SPACE
                        continue
                    if ch not in table:
                        continue
                    t = k / n
                    col = tuple(int(round(cols[0][j] + (cols[1][j] - cols[0][j]) * t)) for j in range(3))
                    im, asc, s = sheet_glyph(("title" if kind == "T" else "nick") + size, ch)
                    if shadow_px:
                        put(tint(im, tuple(c // 4 for c in col)), asc, x + shadow_px, s, shadow_px)
                    put(tint(im, col), asc, x, s)
                    x += table[ch][0]
        return canvas

    bgc = (24, 26, 32, 255)
    samples = [("{title}", "xozne"), ("명장 대장장이", "Steve_2024"), ("단골", "홍길동")]
    rows = []
    for meta in banners["themes"]:
        for title, nick in samples:
            t = title.replace("{title}", next(m for m in THEMES if m["id"] == meta["id"])["name"] + " 칭호")
            rows.append((meta, t, nick))
    cells = [compose(m, t, n, 0) for m, t, n in rows]
    S = 3
    W = max(c.width for c in cells) * S + 20
    Hh = sum(c.height * S + 8 for c in cells) + 20
    im = Image.new("RGBA", (W, Hh), bgc)
    y = 10
    for c in cells:
        im.alpha_composite(c.resize((c.width * S, c.height * S), Image.NEAREST), (10, y))
        y += c.height * S + 8
    PREVIEWS.mkdir(parents=True, exist_ok=True)
    im.save(PREVIEWS / "title_banners.png")
    # every frame of every theme (what the shader cycles), and the mini (chat, tab list) size
    strips = []
    for meta in banners["themes"]:
        frames = [compose(meta, meta["name"] + " 칭호", "xozne", f) for f in range(N)]
        strips.append(frames)
    fw = max(f.width for s in strips for f in s)
    fh = max(f.height for s in strips for f in s)
    S = 2
    im = Image.new("RGBA", (4 * (fw * S + 6) + 10, len(strips) * 4 * (fh * S + 6) + 10), bgc)
    for t, frames in enumerate(strips):
        for k, f in enumerate(frames):
            x = 10 + (k % 4) * (fw * S + 6)
            yy = 10 + (t * 4 + k // 4) * (fh * S + 6)
            im.alpha_composite(f.resize((f.width * S, f.height * S), Image.NEAREST), (x, yy))
    im.save(PREVIEWS / "title_banners_frames.png")
    minis = [compose(meta, meta["name"] + " 칭호", "xozne", 0, "_mini") for meta in banners["themes"]]
    S = 6
    W = max(c.width for c in minis) * S + 20
    im = Image.new("RGBA", (W, sum(c.height * S + 8 for c in minis) + 20), bgc)
    y = 10
    for c in minis:
        im.alpha_composite(c.resize((c.width * S, c.height * S), Image.NEAREST), (10, y))
        y += c.height * S + 8
    im.save(PREVIEWS / "title_banners_mini.png")


def main():
    summary, _ = write()
    print(f"title banners (plugin/src/main/resources/pack/title_banners): {summary}")
    if "--preview" in sys.argv:
        preview()
        print("previews docs/previews/title_banners*.png")


if __name__ == "__main__":
    main()
