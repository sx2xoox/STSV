"""
Title banners (칭호 배너, admin test feature): an animated nameplate "칭호 ◆ 닉네임" whose plate hugs the text.

    python3 -I title_banners.py            # glyph sheets, fonts, banners.json (also run by build.py)
    python3 -I title_banners.py --preview  # + docs/previews/title_banners*.png (banner_render.py: run the plugin's
                                           #   banner tests first, they write the components it draws)

The approved art (banner_themes/: one module per theme, 16 frames, procedural) is drawn without its text, at its
own canonical size, then cut into parts the plugin glues together with space glyphs at any width:

    L (left end + ornaments) | left fill | C (centre ornament, may be empty) | right fill | R (right end)

A fill is whole tiles of P columns taken from the plate where the tile joins itself best (searched per theme),
plus aligned slices of 1, 2, 4, 8 ... columns of the same tile for the rest, so any width is exact and nothing
overlaps (overlapping glass would blend twice).

Every part exists in 16 frames. Frame 0 is an ordinary glyph. Frames 1-15 are "ghosts": their alpha is stored
as 1..24 (under the game's text cut-off 0.1), so a client without the pack's text shader draws frame 0 only, and
the shader (plugin resources pack/title_banners/shader) decodes them and shows one frame at a time by game time.
The plugin marks banner glyphs by their text colour (BannerText.marker). For the server-side fallback the plugin
decodes the ghosts back to plain alpha when it builds the pack.

Two sizes: "big" (1 texel = 1 GUI pixel, the name tag above the head) and the small size ("mini": 16 rows of the
plate, CHAT_ROWS, drawn at half size: 8 GUI px, the top 8 rows of a 9 px chat line or tab list row, so stacked
banners keep a pixel apart): the tab list, and chat for themes without a chat banner. Its letters are 4.5 px.

The chat banner (a theme with chat=True in THEMES; banner_themes/chat.py; fonts <theme>_chat, title_chat,
nick_chat, and title_chat_o, nick_chat_o: every letter's outline, drawn dark so the text reads over any effect):
the theme drawn again for one chat line at twice the resolution (1 texel = half a GUI pixel, as the
game draws its own Hangul): a plate of exactly the line's 18 rows of texels, 14 of them for the text, and nothing
is over or under the line. Its text has its own fonts (Hangul in Neo둥근모, 6.5 px; Latin, digits and signs in
Galmuri14 made bold, capitals 7 px; title and nickname alike) and its own measures (banners.json text.chat).

What the 26.3 client does with these (read from its code; tools/assetgen/banner_render.py and the plugin test
BannerClientLayoutTest replay it):
  * every PNG is read as RGBA (a one-channel PNG is opaque: its whole cell would be drawn), a bitmap glyph is its
    whole cell and advances (int) (0.5 + width * height / cell height) + 1, width = rightmost column with alpha + 1;
  * the glyphs of a GUI layer are sorted by pipeline, then by atlas texture (identity hash: no fixed order), so a
    bitmap letter over a bitmap plate part from another atlas page could end up under it. Only TrueType glyphs use
    the grayscale text pipeline, which sorts after the coloured one: letters and the gem are TrueType fonts of
    pixel squares (pixel_ttf), so they are always over the plate. Their advance is exact (ink + 1 px, half at chat
    size).

Text: the title in Galmuri9 (ASCII, signs, KS X 1001 Hangul + every syllable the repository's texts use), the
nickname in the tall 5 x 7 face (banner_themes/letters.py) with Hangul from Galmuri9. Glyphs are white; the plugin
tints them per character with the theme's colours (the game multiplies a glyph by its text colour), so one font
serves every theme, and gives the full size the game's drop shadow. The gem is one glyph per colour, each tinted
with its colour.

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
import neodgm  # noqa: E402
import terminus  # noqa: E402
from banner_themes import chat as chatlib, state  # noqa: E402
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
# The chat size (chat, tab list, menu lore): a band of 16 rows of the plate drawn at half size, 8 GUI px, the rows
# [0, 8) of a 9 px chat line or tab row (so the next line's banner keeps a pixel of air): one row over the plate
# (rim licks, glow), the plate, two plate-fill rows left out (MID-3 and MID+3) so it fits.
CHAT_ROWS = [d for d in range(-9, 9) if d not in (-3, 3)]
CHAT_ASCENT = 7               # band row 0 at the text's top (GUI y 0 of the line's text)
# Letters and the gem are TrueType (pixel squares, one font pixel = 64 units, 16 a em) so the game draws them with
# its grayscale text pipeline, after every coloured (bitmap) glyph: always over the plate. Cell row r (0 = the top
# row of a Galmuri9 capital) is drawn at GUI y r - 1 at full size (as before) and 2 + r / 2 at chat size (band row
# 4 + r: the plate's middle row MID at band row 8 is row 4 of the cell, as on the big plate).
TTF_UNIT = 64
TTF_BIG = dict(size=16.0, oversample=1.0, shift=[0.0, 0.0])
TTF_CHAT = dict(size=8.0, oversample=2.0, shift=[0.0, -1.0])
# The chat banner (banner_themes/chat.py): its plate's 18 rows are the chat line (GUI y -1 .. 8 around the y of the
# line's text, the game draws a line's text one pixel under the line's top), so its bitmap glyphs are 9 px high with
# ascent 8; its text's first row (plate row 2) is at GUI y 0: a TrueType cell row r is drawn at 3 + r / 2 + shift.
CHAT_HEIGHT, CHAT_ASCENT_HI = 9, 8
TTF_HI = dict(size=8.0, oversample=2.0, shift=[0.0, -3.0])

# theme list (data driven: add a module in banner_themes/ and a line here). icon = the menu item (a Material name),
# tile = P, centre = half width of the
# centre ornament slice (0 = none); tints: title / nickname colours, left to right, multiplied into the white glyphs
THEMES = [
    dict(id="fire", module="fire", icon="BLAZE_POWDER", name="화염", tile=16, centre=0, chat=True,
         title=((255, 222, 128), (255, 176, 64)), nick=((255, 244, 200), (255, 150, 40))),
    dict(id="lightning", module="lightning", icon="LIGHTNING_ROD", name="번개", tile=32, centre=0, chat=True,
         title=((236, 244, 255), (168, 196, 246)), nick=((206, 244, 255), (96, 176, 255))),
    dict(id="neon", module="neon", icon="GLOW_INK_SAC", name="네온", tile=16, centre=4, chat=True,
         title=((200, 250, 255), (80, 214, 255)), nick=((255, 180, 238), (255, 70, 206))),
    dict(id="bloodmoon", module="bloodmoon", icon="REDSTONE", name="혈월", tile=16, centre=26, chat=True,
         title=((244, 234, 238), (204, 184, 194)), nick=((255, 150, 150), (226, 36, 48))),
    dict(id="prism", module="prism", icon="AMETHYST_SHARD", name="프리즘", tile=16, centre=0, chat=True,
         title=((255, 255, 255), (224, 228, 248)), nick=((246, 242, 255), (204, 190, 252))),
    dict(id="royal", module="royal", icon="GOLD_INGOT", name="황금", tile=16, centre=18, chat=True,
         title=((255, 244, 190), (246, 190, 70)), nick=((255, 250, 226), (255, 206, 96))),
    dict(id="frost", module="frost", icon="PACKED_ICE", name="서리", tile=16, centre=0, chat=True,
         title=((248, 252, 255), (150, 208, 246)), nick=((236, 250, 255), (104, 204, 255))),    dict(id="void", module="void", icon="ENDER_EYE", name="심연", tile=16, centre=18, chat=True,
         title=((246, 232, 255), (170, 110, 240)), nick=((255, 244, 255), (190, 120, 255))),
    dict(id="toxic", module="toxic", icon="SLIME_BALL", name="맹독", tile=16, centre=0, chat=True,
         title=((244, 255, 220), (150, 226, 70)), nick=((255, 255, 236), (124, 236, 64))),
    dict(id="blossom", module="blossom", icon="PINK_PETALS", name="벚꽃", tile=16, centre=0, chat=True,
         title=((255, 248, 251), (250, 176, 208)), nick=((255, 240, 246), (246, 140, 186))),
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


def render(theme, chat=False):
    """the 16 frames of a theme without its text, and of its gem alone, cropped to what is drawn; chat: its chat
    banner (every row of the picture is kept: they are the chat line's)"""
    import importlib
    m = importlib.import_module("banner_themes." + theme["module"])
    G = (m.chat_geo(chatlib.ChatGeo) if hasattr(m, "chat_geo") else chatlib.ChatGeo(m.TITLE)) if chat else m.Geo(m.TITLE, **m.GEO)
    draw = m.chat_frame if chat else m.frame
    bg, gems = [], []
    for i in range(N):
        state.GEM["mode"] = "bg"
        f = draw(G, i)
        drop_orphans(f)
        bg.append(fix_alpha(f))
        state.GEM["mode"] = "gem"
        state.GEM["pix"] = set()
        g = draw(G, i)
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
    if chat:
        box = (box[0], 0, box[2], chatlib.ROWS)
    bg = [f.crop(box) for f in bg]
    gems = [f.crop(box) for f in gems]
    ox, oy = box[0], box[1]
    div = getattr(G, "DIV")
    geo = dict(px0=G.PX0 - ox, px1=G.PX1 - ox, mid=G.MID - oy, cx=int(round(G.CX)) - ox, div=div - ox, title=m.TITLE,
               end=getattr(G, "END", 0), tile=G.TILE if chat else theme["tile"])
    if chat:                                                 # the parts are the geometry's (its gust is drawn by part)
        geo.update(tile0=G.TILE0 - ox, r0=G.R0 - ox, flow=G.FLOW, flow0=G.FLOW0 - ox, scatter=G.SCATTER, grow=G.GROW,
                   centre=G.CENTRE)
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
    P = geo["tile"]
    hc = theme["centre"]
    W = bg[0].width
    px0, px1, cx = geo["px0"], geo["px1"], geo["cx"]
    c0, c1 = (cx - hc, cx + hc) if hc else (cx, cx)
    if "tile0" in geo:                                       # a chat banner: its geometry says where the parts are
        hc = geo["centre"]
        c0, c1 = (cx - hc, cx + hc) if hc else (cx, cx)
        if hc and not (geo["tile0"] + P <= c0 and c1 <= geo["r0"] - P):
            raise RuntimeError(f"{theme['id']}: no room for a tile on both sides of the chat banner's centre")
        return dict(P=P, left=geo["tile0"], right=geo["r0"] - P, c0=c0, c1=c1, W=W, gem_box=gem_union(gems),
                    fills=2 if hc else 1)
    # left tile: somewhere between the left end (its slope, rivets) and the centre slice
    lo, hi = px0 + 10, c0 - P
    left = min(range(lo, max(lo, hi) + 1), key=lambda t: (seam_cost(bg, t, t + P), abs(t - lo)))
    if hc:
        lo, hi = c1, px1 - 9 - P
        right = min(range(lo, max(lo, hi) + 1), key=lambda t: (seam_cost(bg, t, t + P), abs(t - hi)))
    else:
        # no centre ornament: one fill (the left tile) up to the right end, which keeps 10 plate columns (and a
        # chat banner's columns after the text, geo end)
        right = px1 + 1 - 10 - geo["end"] - P
    return dict(P=P, left=left, right=right, c0=c0, c1=c1, W=W, gem_box=gem_union(gems), fills=2 if hc else 1)


def gem_union(gems):
    gem_box = None
    for g in gems:
        b = g.getbbox()
        if b:
            gem_box = b if gem_box is None else (min(gem_box[0], b[0]), min(gem_box[1], b[1]), max(gem_box[2], b[2]), max(gem_box[3], b[3]))
    return gem_box


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
    """the chat size's rows (CHAT_ROWS) of a part"""
    out = Image.new("RGBA", (im.width, len(CHAT_ROWS)), (0, 0, 0, 0))
    for k, d in enumerate(CHAT_ROWS):
        y = mid + d
        if 0 <= y < im.height:
            out.paste(im.crop((0, y, im.width, y + 1)), (0, k))
    return out


def lead(im):
    """the first column with any alpha (the transparent columns before it are trimmed off the banner)"""
    b = im.getchannel("A").getbbox()
    return b[0] if b else im.width


class Fonts:
    """collects bitmap providers per font and the metadata of every glyph"""

    def __init__(self):
        self.files = {}           # pack path -> PIL image / bytes / json
        self.fonts = {}           # font name -> providers

    def provider(self, font, path, cells, height, ascent):
        """cells: rows (frames) of (char, image); one RGBA PNG, equal cells (the game reads every PNG as RGBA and
        measures a glyph by its alpha: rightmost column with any alpha + 1)"""
        cw = max(1, max(im.width for row in cells for _, im in row))
        ch = max(im.height for row in cells for _, im in row)
        sheet = Image.new("RGBA", (cw * len(cells[0]), ch * len(cells)), (0, 0, 0, 0))
        chars = []
        for r, row in enumerate(cells):
            chars.append("".join(c for c, _ in row))
            for k, (_, im) in enumerate(row):
                sheet.alpha_composite(im, (k * cw, r * ch))
        self.files[f"assets/{NS}/textures/{path}.png"] = sheet
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


def theme_sheets(theme, fonts, chat=False):
    """a theme's parts as glyphs (fonts <id>, <id>_mini) and its metadata; chat: its chat banner (font <id>_chat,
    half size only)"""
    bg, gems, geo = render(theme, chat)
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
    meta = dict(id=theme["id"], name=theme["name"], icon=theme["icon"], height=H, mid=mid, frames=N,
                plate=dict(left=geo["px0"], right=geo["px1"]), tile=P, text_right=geo["end"],
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
        if chat:
            fonts.provider(theme["id"] + "_chat", f"font/title_banner/{theme['id']}/chat_{group.lower()}", rows,
                           height=CHAT_HEIGHT, ascent=CHAT_ASCENT_HI)
            continue
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
                       height=len(CHAT_ROWS) // 2, ascent=CHAT_ASCENT)
    meta["min_plate"] = (c["left"] - geo["px0"]) + (c["c1"] - c["c0"]) + (geo["px1"] + 1 - c["right"] - P)
    if geo.get("grow"):                                      # the plate grows in whole steps (banner_themes/chat.py)
        meta["grow"] = geo["grow"]
    if geo.get("flow"):                                      # a gust runs along the banner (banner_themes/chat.py flow_at)
        assert (c["left"] - geo["flow0"]) % geo["flow"] == 0 and P % geo["flow"] == 0
        meta["flow"] = dict(step=geo["flow"], origin=geo["flow0"], scatter=bool(geo.get("scatter")))
    # the empty columns before the left end (cut off the banner: it starts where its picture starts)
    left_end = [f.crop((0, 0, c["left"], H)) for f in bg]
    meta["lead"] = dict(big=min(lead(f) for f in left_end), mini=min(lead(band(f, mid)) for f in left_end))
    meta["gem"]["y"] = gy0
    if chat:
        meta["gem"].update(gem_layers(theme, gems[0].crop((gx0, gy0, gx1, gy1)), gy0, chatlib.TEXT_ROW, GEM_BASE_CHAT, GEM_GLYPHS_CHAT))
    else:
        meta["gem"].update(gem_layers(theme, gems[0].crop((gx0, gy0, gx1, gy1)), gy0, mid - 4, GEM_BASE, GEM_GLYPHS))
    return meta, bg, gems


GEM_BASE, GEM_BASE_CHAT = 0xE100, 0xE200
GEM_GLYPHS = {}               # gem code point -> its pixels (cell coordinates), for title.ttf
GEM_GLYPHS_CHAT = {}          # the chat banners' gems, for title14.ttf


def gem_layers(theme, im, top, row0, base, glyphs):
    """The gem between title and nickname: one TrueType glyph per colour of its frame 0, all on the same spot, each
    tinted with its colour by the plugin (white glyph x text colour = the colour), so the game draws it with the
    letters, over the plate. Its rows are cell rows (r = art row - row0, row0 = the art row of the text's first row:
    MID - 4 on the full banner, see TTF_BIG / TTF_CHAT / TTF_HI)."""
    k = [t["id"] for t in THEMES].index(theme["id"])
    px = im.load()
    by_colour = {}
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a:
                by_colour.setdefault((r, g, b), []).append((x, top + y - row0))
    chars, tints, advances = [], [], []
    for j, (col, pix) in enumerate(sorted(by_colour.items(), key=lambda kv: -len(kv[1]))):
        ch = chr(base + 16 * k + j)
        glyphs[ch] = (pix, max(x for x, _ in pix) + 2)
        chars.append(ch)
        tints.append(list(col))
        advances.append(glyphs[ch][1])
    return dict(chars="".join(chars), tints=tints, advances=advances)


# ------------------------------------------------------------------------------------------------
# Text: pixel TrueType fonts
# ------------------------------------------------------------------------------------------------

def pixel_ttf(glyphs, family):
    """A TrueType font of pixel squares: glyphs {char: (pixels [(x, row)], advance in pixels)}; a pixel is TTF_UNIT
    units, 16 to the em, cell row r spans 8 - r .. 7 - r pixels above the baseline. Runs of pixels are one rectangle.
    It carries a (do-nothing) prep program: FreeType auto-hints TrueType fonts without any instructions, which moves
    the strokes of Hangul off the pixel grid; with it the native hinter leaves the grid-aligned outlines alone (it
    has to be longer than seven bytes for FreeType 2.14, see below)."""
    import io
    from fontTools.fontBuilder import FontBuilder
    from fontTools.pens.ttGlyphPen import TTGlyphPen
    from fontTools.ttLib import newTable
    from fontTools.ttLib.tables import ttProgram
    U = TTF_UNIT
    order = [".notdef"] + [f"u{ord(ch):04X}" for ch in sorted(glyphs)]
    fb = FontBuilder(16 * U, isTTF=True)
    fb.setupGlyphOrder(order)
    fb.setupCharacterMap({ord(ch): f"u{ord(ch):04X}" for ch in glyphs})
    outlines, metrics = {".notdef": TTGlyphPen(None).glyph()}, {".notdef": (8 * U, 0)}
    for ch, (pix, adv) in glyphs.items():
        pen = TTGlyphPen(None)
        rows = {}
        for x, r in set(pix):
            rows.setdefault(r, []).append(x)
        for r, xs in sorted(rows.items()):
            xs.sort()
            runs, s0 = [], xs[0]
            for i in range(1, len(xs) + 1):
                if i == len(xs) or xs[i] != xs[i - 1] + 1:
                    runs.append((s0, xs[i - 1] + 1))
                    if i < len(xs):
                        s0 = xs[i]
            for x0, x1 in runs:                       # clockwise (TrueType outer contour)
                yt, yb = (8 - r) * U, (7 - r) * U
                pen.moveTo((x0 * U, yb))
                pen.lineTo((x0 * U, yt))
                pen.lineTo((x1 * U, yt))
                pen.lineTo((x1 * U, yb))
                pen.closePath()
        name = f"u{ord(ch):04X}"
        outlines[name] = pen.glyph()
        metrics[name] = (adv * U, min((x for x, _ in pix), default=0) * U)
    fb.setupGlyf(outlines)
    fb.setupHorizontalMetrics(metrics)
    fb.setupHorizontalHeader(ascent=9 * U, descent=-4 * U)
    ps = family.replace(" ", "")
    fb.setupNameTable({"familyName": family, "styleName": "Regular", "fullName": family, "psName": ps,
                       "uniqueFontIdentifier": ps, "version": "Version 1.0"})
    fb.setupOS2(sTypoAscender=9 * U, sTypoDescender=-4 * U, usWinAscent=9 * U, usWinDescent=4 * U)
    fb.setupPost()
    prep = newTable("prep")
    prep.program = ttProgram.Program()
    # PUSHB 0, POP, three times: FreeType 2.14 ignores a font's own hinting (and auto-hints, which blurs a row of
    # every glyph) when it has no fpgm and a prep of seven bytes or less; 2.13 and 2.14 both leave nine bytes alone
    prep.program.fromBytecode(bytes([0xB0, 0x00, 0x21] * 3))
    fb.font["prep"] = prep
    fb.font["maxp"].maxSizeOfInstructions = 9
    fb.font["head"].created = fb.font["head"].modified = 0x7C259DC0     # fixed (2020-01-01): same bytes every run
    out = io.BytesIO()
    fb.font.recalcTimestamp = False
    fb.save(out)
    return out.getvalue()


def galmuri_ink(font, ch):
    """Galmuri9's pixels of a character in cell rows (0 = the top row of a capital, 9 = the first under the baseline)"""
    adv, xo, yo, bits = font[ord(ch)]
    top = 9 - (yo + len(bits))
    return [(xo + x, top + y) for y, r in enumerate(bits) for x, b in enumerate(r) if b]


def text_fonts(fonts, outlines=False):
    """The title (Galmuri9 Latin and signs, and the gems), nickname (the tall face) and Hangul (Galmuri9) TrueType
    fonts, and the title / nick fonts at both sizes that use them. Advance = ink width + 1 pixel (full size)."""
    import hud
    F9 = galmuri.load(9)
    hangul = hud._hangul_set()
    title_latin = [chr(c) for c in range(0x21, 0x7F)] + [s for s in "·•…→←★☆◆◇♪❤" if ord(s) in F9]
    title_latin = [ch for ch in title_latin if ord(ch) in F9 and F9[ord(ch)][3]]

    def table(chars, ink_of):
        out = {}
        for ch in chars:
            pix = ink_of(ch)
            w = max(x for x, _ in pix) + 1
            out[ch] = (pix, w + 1)
        return out

    title = table(title_latin, lambda ch: galmuri_ink(F9, ch))
    nick = table(CHARSET, lambda ch: [(x, y + 1) for y, r in enumerate(TALL[ch]) for x, v in enumerate(r) if v == "#"])
    hg = table(hangul, lambda ch: galmuri_ink(F9, ch))
    # the chat banners' text (banner_themes/chat.py): Hangul in Neo둥근모, Latin, digits and signs in Terminus Bold
    table14 = lambda chars: {ch: (pix, chatlib.advance(pix)) for ch in chars for pix in [chatlib.glyph(ch)] if pix}
    title14, nick14, hg14 = table14(title_latin), table14(CHARSET), table14(hangul)
    files = {"title": {**title, **GEM_GLYPHS}, "nick": nick, "hangul": hg,
             "title14": {**title14, **GEM_GLYPHS_CHAT}, "nick14": nick14, "hangul14": hg14}
    if outlines:                                             # only when a chat banner outlines its letters
        ring = lambda table: {ch: (chatlib.outline(pix), adv) for ch, (pix, adv) in table.items()}
        files.update({"title14o": ring(title14), "nick14o": ring(nick14), "hangul14o": ring(hg14)})
    for name, glyphs in files.items():
        fonts.files[f"assets/{NS}/font/title_banner/ttf/{name}.ttf"] = pixel_ttf(glyphs, f"STSV banner {name}")
    # the letters are Galmuri's (SIL Open Font License): its notice goes with the fonts made from it
    fonts.files[f"assets/{NS}/font/title_banner/ttf/LICENSE-galmuri.txt"] = galmuri.LICENSE.read_bytes()
    # title14.ttf and nick14.ttf are made from Terminus Bold (SIL Open Font License, a reserved font name): Modified Versions
    fonts.files[f"assets/{NS}/font/title_banner/ttf/LICENSE-terminus.txt"] = (
        "title14.ttf and nick14.ttf hold glyphs converted from Terminus Font, bold 22 px (its printable ASCII, the\n"
        "letters that hang under the baseline pressed to the banner's rows), as TrueType fonts for the STSV title\n"
        "banners. They are Modified Versions under the license below and do not use the Reserved Font Name.\n\n"
        + terminus.LICENSE.read_text(encoding="utf-8")).encode("utf-8")
    # hangul14.ttf is made from Neo둥근모 (SIL Open Font License, reserved font names): a Modified Version
    fonts.files[f"assets/{NS}/font/title_banner/ttf/LICENSE-neodgm.txt"] = (
        "hangul14.ttf holds glyphs converted from Neo둥근모 1.600 (https://neodgm.dalgona.dev): its Hangul\n"
        "syllables, as a TrueType font for the STSV title banners. It is a Modified Version under the license\n"
        "below and does not use the Reserved Font Names.\n\n"
        + neodgm.LICENSE.read_text(encoding="utf-8")).encode("utf-8")
    for font, parts in (("title", ("title", "hangul")), ("nick", ("nick", "hangul"))):
        for suffix, params in (("", TTF_BIG), ("_mini", TTF_CHAT)):
            for part in parts:
                fonts.fonts.setdefault(font + suffix, []).append(
                    {"type": "ttf", "file": f"{NS}:title_banner/ttf/{part}.ttf", **params, "skip": ""})
        for suffix, kind in (("_chat", "14"), ("_chat_o", "14o"))[:2 if outlines else 1]:
            for part in parts:
                fonts.fonts.setdefault(font + suffix, []).append(
                    {"type": "ttf", "file": f"{NS}:title_banner/ttf/{part}{kind}.ttf", **TTF_HI, "skip": ""})
    widths = lambda t: {ch: [adv, adv - 1] for ch, (_, adv) in t.items()}
    return dict(title={**widths(title), **widths(hg)}, nick={**widths(nick), **widths(hg)}, space=SPACE,
                chat=dict(title={**widths(title14), **widths(hg14)}, nick={**widths(nick14), **widths(hg14)},
                          space=chatlib.SPACE, pad=chatlib.PAD, gap=chatlib.GAP),
                ttf=dict(big=TTF_BIG, mini=TTF_CHAT, chat=TTF_HI))


# ------------------------------------------------------------------------------------------------
# Write
# ------------------------------------------------------------------------------------------------

def write(out_dir: Path = OUT):
    fonts = Fonts()
    themes, renders = [], {}
    for t in THEMES:
        meta, bg, gems = theme_sheets(t, fonts)
        if t.get("chat"):
            meta["chat"] = theme_sheets(t, fonts, chat=True)[0]
            import importlib
            module = importlib.import_module("banner_themes." + t["module"])
            if getattr(module, "CHAT_GLITCH", False):        # the shader moves its letters when it glitches
                meta["chat"]["glitch"] = True
            tint = getattr(module, "CHAT_TINT", None)
            if tint:                                         # the chat banner's own text colours and outline
                meta["chat"]["tint"] = dict(title=[list(c) for c in tint["title"]], nick=[list(c) for c in tint["nick"]])
                meta["chat"]["outline"] = list(tint["outline"])
        themes.append(meta)
        renders[t["id"]] = (bg, gems)
    text = text_fonts(fonts, outlines=any("outline" in t.get("chat", {}) for t in themes))
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
        elif isinstance(data, bytes):
            f.write_bytes(data)
        else:
            f.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        total += f.stat().st_size
    banners = dict(frames=N, ghost_levels=GHOST_LEVELS, pad=PAD, gap=GAP, chat_rows=CHAT_ROWS,
                   space=dict(base=SPACE_BASE, steps=SPACE_STEPS), text=text, themes=themes,
                   ghosts=sorted(p for p in fonts.files if p.endswith(".png")),
                   files=sorted(fonts.files))
    (out_dir / "banners.json").write_text(json.dumps(banners, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    total += (out_dir / "banners.json").stat().st_size
    return f"{len(themes)} themes, {len(fonts.files)} files, {total / 1024:.0f} KB", renders


def main():
    summary, _ = write()
    print(f"title banners (plugin/src/main/resources/pack/title_banners): {summary}")
    if "--preview" in sys.argv:
        # the plugin's own components (plugin test BannerClientLayoutTest), drawn like the client: banner_render.py
        import banner_render
        banner_render.main()


if __name__ == "__main__":
    main()
