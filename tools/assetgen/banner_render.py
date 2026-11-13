"""
Offline renderer of the title banners, laid out exactly like the Minecraft 26.3 client draws text, from the pack's
own files (plugin/src/main/resources/pack/title_banners) and the plugin's own components (written by the plugin test
BannerClientLayoutTest to plugin/build/banner-preview/components.json):

    cd plugin && ./gradlew test --offline -q --tests 'dev.stsv.core.banner.*'
    python3 -I tools/assetgen/banner_render.py [--freetype DIR]     # -> docs/previews/title_banners*.png

The client's rules it follows (read from the 26.3 client):
  * every PNG is read as RGBA (stb_image, 4 channels: a PNG without alpha is opaque);
  * bitmap provider: scale = height / cell height; advance = (int) (0.5 + width * scale) + 1, width = the cell's
    rightmost column with any alpha + 1; the glyph is the whole cell, drawn from y = 7 - ascent at that scale;
  * ttf provider: FreeType at round(size * oversample) px a em, transform delta (shift.x, -shift.y) * oversample;
    metrics with FT_LOAD_NO_BITMAP | FT_LOAD_BITMAP_METRICS_ONLY, bitmap with FT_LOAD_RENDER; advance = advance.x /
    64 / oversample, box from bitmap_left / oversample, 7 - bitmap_top / oversample; drawn grayscale (the value is
    colour and alpha) by the grayscale text pipeline, which sorts after the coloured one: on top;
  * space provider: the pen moves by the advance;
  * text shadow: the glyph again 1 GUI px right and down in the colour x 0.25, before the glyph;
  * the pack's text shader: a glyph in a banner marker colour is drawn white only in its frame (ghost alpha x
    255 / 24 for frames 1..15); everything under alpha 0.1 is discarded; translucent blending.

freetype-py is needed (pip install freetype-py, or --freetype DIR with an unpacked wheel).
"""

import json
import math
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
PACK = REPO / "plugin" / "src" / "main" / "resources" / "pack" / "title_banners"
COMPONENTS = REPO / "plugin" / "build" / "banner-preview" / "components.json"
PREVIEWS = REPO / "docs" / "previews"
MARK_RED = 253
GHOST_LEVELS = 24
FT_LOAD_RENDER = 0x4
FT_METRICS = 0x400008          # FT_LOAD_NO_BITMAP | FT_LOAD_BITMAP_METRICS_ONLY, as the client loads metrics


def freetype_module(path=None):
    if path:
        sys.path.insert(0, str(path))
    import freetype
    return freetype


class Glyph:
    """advance; box (left, top, texel size) and texels (RGBA image), gray: drawn by the grayscale pipeline"""

    def __init__(self, advance, left=0.0, top=0.0, texel=1.0, image=None, gray=False):
        self.advance, self.left, self.top, self.texel, self.image, self.gray = advance, left, top, texel, image, gray


class Pack:
    def __init__(self, root=PACK, ft=None):
        self.root = Path(root)
        self.ft = ft
        self.fonts = {}
        self.cache = {}
        self.images = {}
        self.faces = {}

    def providers(self, font):
        if font not in self.fonts:
            ns, name = font.split(":", 1)
            data = json.loads((self.root / "assets" / ns / "font" / f"{name}.json").read_text(encoding="utf-8"))
            self.fonts[font] = data["providers"]
        return self.fonts[font]

    def image(self, ident):
        if ident not in self.images:
            ns, path = ident.split(":", 1)
            self.images[ident] = Image.open(self.root / "assets" / ns / "textures" / path).convert("RGBA")
        return self.images[ident]

    def glyph(self, font, cp):
        key = (font, cp)
        if key not in self.cache:
            self.cache[key] = self._glyph(font, cp)
        return self.cache[key]

    def _glyph(self, font, cp):
        ch = chr(cp)
        for p in self.providers(font):
            if p["type"] == "space":
                if ch in p["advances"]:
                    return Glyph(float(p["advances"][ch]))
            elif p["type"] == "bitmap":
                for r, row in enumerate(p["chars"]):
                    if ch not in row:
                        continue
                    k = row.index(ch)
                    im = self.image(p["file"])
                    cw, chh = im.width // len(row), im.height // len(p["chars"])
                    cell = im.crop((k * cw, r * chh, (k + 1) * cw, (r + 1) * chh))
                    scale = p["height"] / chh
                    a = cell.getchannel("A").load()
                    width = 0
                    for x in range(cw - 1, -1, -1):
                        if any(a[x, y] for y in range(chh)):
                            width = x + 1
                            break
                    adv = int(0.5 + width * scale) + 1
                    return Glyph(adv, 0.0, 7 - p.get("ascent", 7), scale, cell, False)
            elif p["type"] == "ttf":
                g = self._ttf(p, cp)
                if g is not None:
                    return g
        raise KeyError(f"no glyph U+{cp:04X} in {font}")

    def _ttf(self, p, cp):
        ft = self.ft
        key = (p["file"], p["size"], p["oversample"], tuple(p.get("shift", (0, 0))))
        if key not in self.faces:
            ns, path = p["file"].split(":", 1)
            face = ft.Face(str(self.root / "assets" / ns / "font" / path))
            face.select_charmap(ft.FT_ENCODING_UNICODE)
            over = float(p["oversample"])
            ppem = int(math.floor(p["size"] * over + 0.5))
            face.set_pixel_sizes(ppem, ppem)
            sx, sy = p.get("shift", (0, 0))
            delta = ft.Vector(round(sx * over * 64), round(-sy * over * 64))
            face.set_transform(ft.Matrix(0x10000, 0, 0, 0x10000), delta)
            self.faces[key] = (face, over)
        face, over = self.faces[key]
        index = face.get_char_index(cp)
        if index == 0:
            return None
        face.load_glyph(index, FT_METRICS)
        slot = face.glyph
        adv = slot.advance.x / 64 / over
        left, top = slot.bitmap_left, slot.bitmap_top
        if slot.bitmap.width <= 0 or slot.bitmap.rows <= 0:
            return Glyph(adv, gray=True)
        face.load_glyph(index, FT_LOAD_RENDER)
        b = face.glyph.bitmap
        lum = Image.new("L", (b.width, b.rows))
        lum.putdata([b.buffer[y * b.pitch + x] for y in range(b.rows) for x in range(b.width)])
        return Glyph(adv, left / over, 7 - top / over, 1 / over, Image.merge("RGBA", (lum,) * 4), True)


class Canvas:
    """a GUI area drawn at GUI scale k (screen px per GUI px); x, y in GUI px from its origin"""

    def __init__(self, w, h, k, bg=(0, 0, 0, 0), ox=0.0, oy=0.0):
        self.k, self.ox, self.oy = k, ox, oy
        self.im = Image.new("RGBA", (int(math.ceil(w * k)), int(math.ceil(h * k))), bg)
        self.px = self.im.load()

    def blend(self, X, Y, c):
        r, g, b, a = c
        if a <= 0:
            return
        dr, dg, db, da = self.px[X, Y]
        sa = a / 255
        self.px[X, Y] = (round(r * sa + dr * (1 - sa)), round(g * sa + dg * (1 - sa)), round(b * sa + db * (1 - sa)),
                         round(a + da * (1 - sa)))

    def fill(self, x0, y0, x1, y1, rgba):
        k = self.k
        for Y in range(max(0, round((y0 + self.oy) * k)), min(self.im.height, round((y1 + self.oy) * k))):
            for X in range(max(0, round((x0 + self.ox) * k)), min(self.im.width, round((x1 + self.ox) * k))):
                self.blend(X, Y, rgba)

    def quad(self, x0, y0, texel, image, colour, gray, frame_alpha=1.0):
        """image texels from (x0, y0), each texel GUI px wide; nearest sampling at pixel centres"""
        k = self.k
        w, h = image.width * texel, image.height * texel
        src = image.load()
        X0 = max(0, math.floor((x0 + self.ox) * k))
        X1 = min(self.im.width, math.ceil((x0 + self.ox + w) * k))
        Y0 = max(0, math.floor((y0 + self.oy) * k))
        Y1 = min(self.im.height, math.ceil((y0 + self.oy + h) * k))
        cr, cg, cb = colour
        for Y in range(Y0, Y1):
            gy = (Y + 0.5) / k - self.oy
            ty = math.floor((gy - y0) / texel)
            if not 0 <= ty < image.height:
                continue
            for X in range(X0, X1):
                gx = (X + 0.5) / k - self.ox
                tx = math.floor((gx - x0) / texel)
                if not 0 <= tx < image.width:
                    continue
                r, g, b, a = src[tx, ty]
                if gray:
                    r = g = b = a = r
                a = min(255, a * frame_alpha)
                if a < 0.1 * 255:
                    continue
                self.blend(X, Y, (r * cr // 255, g * cg // 255, b * cb // 255, a))


def marker_frame(colour):
    r, g, b = (colour >> 16) & 255, (colour >> 8) & 255, colour & 255
    if r == MARK_RED and g >= 252 and b >= 252:
        return (g - 252) + 4 * (b - 252)
    return -1


def draw_text(pack, canvas, runs, x, y, frame=0):
    """the client's text drawing: runs [{font, color, shadow, text}] from (x, y); returns the pen. Coloured glyphs
    first, then grayscale ones (pipeline order); shadows before their glyphs."""
    pen = x
    shadows, coloured, gray = [], [], []
    for run in runs:
        colour = run["color"]
        f = marker_frame(colour)
        rgb = (255, 255, 255) if f >= 0 else ((colour >> 16) & 255, (colour >> 8) & 255, colour & 255)
        for ch in run["text"]:
            g = pack.glyph(run["font"], ord(ch))
            if g.image is not None and (f < 0 or f == frame):
                fa = 255 / GHOST_LEVELS if f > 0 else 1.0
                item = (pen + g.left, y + g.top, g.texel, g.image, rgb, g.gray, fa)
                if run.get("shadow"):
                    shadows.append((g.gray, (pen + g.left + 1, y + g.top + 1, g.texel, g.image,
                                             tuple(int(c * 0.25) for c in rgb), g.gray, fa)))
                (gray if g.gray else coloured).append(item)
            pen += g.advance
    for item in [s for gr, s in shadows if not gr] + coloured + [s for gr, s in shadows if gr] + gray:
        canvas.quad(*item)
    return pen


# ------------------------------------------------------------------------------------------------
# Plain text of the context lines (Galmuri9, as a stand-in for the chat font)
# ------------------------------------------------------------------------------------------------

def plain_runs(text, colour=0xFFFFFF):
    return [{"font": "plain", "color": colour, "shadow": True, "text": text}]


class PlainFont:
    """Galmuri9 bitmap glyphs as Glyphs (rows 0..8 above the baseline drawn from y - 1, like the game's 8 px font)"""

    def __init__(self):
        sys.path.insert(0, str(HERE))
        import galmuri
        self.f = galmuri.load(9)
        self.cache = {}

    def glyph(self, cp):
        if cp not in self.cache:
            if cp == 32 or cp not in self.f:
                self.cache[cp] = Glyph(4.0)
            else:
                dw, xo, yo, bits = self.f[cp]
                top = 9 - (yo + len(bits))
                im = Image.new("RGBA", (max(1, xo + (len(bits[0]) if bits else 0)), 11), (0, 0, 0, 0))
                for y, r in enumerate(bits):
                    for x, b in enumerate(r):
                        if b:
                            im.putpixel((xo + x, top + y), (255, 255, 255, 255))
                self.cache[cp] = Glyph(float(dw), 0.0, -1.0, 1.0, im, False)
        return self.cache[cp]


class WithPlain:
    def __init__(self, pack):
        self.pack, self.plain = pack, PlainFont()

    def glyph(self, font, cp):
        return self.plain.glyph(cp) if font == "plain" else self.pack.glyph(font, cp)


# ------------------------------------------------------------------------------------------------
# Previews
# ------------------------------------------------------------------------------------------------

THEME_NAMES = {"fire": "화염", "lightning": "번개", "neon": "네온", "bloodmoon": "혈월", "prism": "프리즘"}


def find(comps, theme, title, nick, size):
    for c in comps:
        if c["theme"] == theme and c["title"] == title and c["nick"] == nick and c["size"] == size:
            return c
    raise KeyError((theme, title, nick, size))


CHAT = [("거상", "예주니0", "ㅎㅇ"), ("거상", "상원", "안녕하세요"), ("", "xozne", "hi"), ("명장 대장장이", "Steve_2024", "gg"),
        ("아주 아주 긴 칭호 이름", "Long_Name_12345", "길어도 한 줄")]


def chat_panel(fonts, comps, theme, frame=0, k=2):
    """a chat box: lines 9 GUI px apart, each line's text drawn 8 px over its bottom, its background [-1, 8)"""
    lines = [plain_runs(f"STSV » {THEME_NAMES[theme]} 배너 (채팅)", 0xFFE070)]
    for title, nick, msg in CHAT:
        c = find(comps, theme, title, nick, "chat")
        lines.append(c["runs"] + plain_runs(" » ", 0xA0A0A0) + plain_runs(msg))
    lines.append(plain_runs("STSV » 다음 줄과 겹치지 않음", 0x80FF80))
    W, H = 220, 9 * len(lines) + 2
    cv = Canvas(W, H, k, (36, 40, 34, 255), ox=4, oy=1)
    for i, runs in enumerate(lines):
        bottom = 9 * (i + 1)
        cv.fill(-4, bottom - 9, W - 4, bottom, (0, 0, 0, 127))
        draw_text(fonts, cv, runs, 0, bottom - 8, frame)
    return cv.im


def tab_panel(fonts, comps, theme, frame=0, k=2):
    """tab list rows: 9 GUI px apart, background [0, 8) of each row, the name drawn at the row's top"""
    rows = [find(comps, theme, "거상", "상원", "chat")["runs"], plain_runs("Steve"),
            find(comps, theme, "최고 부자", "xozne", "chat")["runs"]]
    W, H = 100, 9 * len(rows) + 2
    cv = Canvas(W, H, k, (36, 40, 34, 255), ox=2, oy=1)
    for i, runs in enumerate(rows):
        y = 9 * i
        cv.fill(-1, y, W - 2, y + 8, (255, 255, 255, 32))
        draw_text(fonts, cv, runs, 0, y, frame)
    return cv.im


def label(im, text, pad=6):
    """im with a caption over it (Galmuri9, 1 px a pixel)"""
    out = Image.new("RGBA", (im.width, im.height + 18), (20, 22, 26, 255))
    out.alpha_composite(im, (0, 18))
    pf = PlainFont()
    x = pad
    for ch in text:
        g = pf.glyph(ord(ch))
        if g.image is not None:
            out.alpha_composite(g.image, (x, 4))
        x += int(g.advance)
    return out


def stack(images, gap=10, bg=(20, 22, 26, 255), horizontal=False):
    if horizontal:
        W = sum(i.width for i in images) + gap * (len(images) + 1)
        H = max(i.height for i in images) + 2 * gap
    else:
        W = max(i.width for i in images) + 2 * gap
        H = sum(i.height for i in images) + gap * (len(images) + 1)
    out = Image.new("RGBA", (W, H), bg)
    p = gap
    for i in images:
        out.alpha_composite(i, (p, gap) if horizontal else (gap, p))
        p += (i.width if horizontal else i.height) + gap
    return out


def up(im, n):
    return im.resize((im.width * n, im.height * n), Image.NEAREST)


def nametag(fonts, comps, theme, title, nick, frame=0, k=2):
    c = find(comps, theme, title, nick, "big")
    W = c["width"] + 4
    cv = Canvas(W, 48, k, (0, 0, 0, 0), ox=2, oy=34)
    draw_text(fonts, cv, c["runs"], 0, 0, frame)
    box = cv.im.getbbox()
    return cv.im.crop(box) if box else cv.im


def main():
    args = sys.argv[1:]
    ftdir = args[args.index("--freetype") + 1] if "--freetype" in args else None
    out = Path(args[args.index("--out") + 1]) if "--out" in args else PREVIEWS
    pack = Pack(ft=freetype_module(ftdir))
    fonts = WithPlain(pack)
    comps = json.loads(COMPONENTS.read_text(encoding="utf-8"))
    themes = list(dict.fromkeys(c["theme"] for c in comps))
    # chat and tab list, frame 0 and frame 8, GUI scale 2, shown x2
    blocks = []
    for t in themes:
        row = [label(up(chat_panel(fonts, comps, t, 0), 2), f"{THEME_NAMES[t]}: 채팅 (GUI x2, 프레임 0)"),
               label(up(tab_panel(fonts, comps, t, 8), 2), "탭 목록 (프레임 8)")]
        blocks.append(stack(row, horizontal=True))
    out.mkdir(parents=True, exist_ok=True)
    stack(blocks).save(out / "title_banners_chat.png")
    # the chat size's animation: frames 0, 4, 8, 12 of every theme
    rows = []
    for t in themes:
        c = find(comps, t, "거상", "상원", "chat")
        cells = []
        for f in (0, 4, 8, 12):
            cv = Canvas(c["width"] + 4, 10, 2, (24, 26, 32, 255), ox=2, oy=1)
            draw_text(fonts, cv, c["runs"], 0, 0, f)
            cells.append(up(cv.im, 3))
        rows.append(stack(cells, horizontal=True))
    stack(rows).save(out / "title_banners_frames.png")
    # name tags (full size)
    tags = []
    for t in themes:
        tags.append(stack([up(nametag(fonts, comps, t, title, nick), 2) for title, nick in
                           (("최고 부자", "xozne"), ("거상", "예주니0"), ("명장 대장장이", "Steve_2024"))], horizontal=True))
    stack(tags).save(out / "title_banners.png")
    print("previews:", ", ".join(str(out / n) for n in ("title_banners_chat.png", "title_banners_frames.png", "title_banners.png")))


if __name__ == "__main__":
    main()
