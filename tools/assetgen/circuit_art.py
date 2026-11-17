"""
The 서킷's pictures (minigame · circuit, plugin dev.stsv.core.minigame.circuit):

  * the race's screen pictures: two resource-pack fonts in content/stsv/assets/stsv
      stsv:circuit_title  the titles' big pictures: 3, 2, 1, GO!, FINISH!, a lap banner, 1위 .. 12위, 역주행, 새 기록!
      stsv:circuit        the boss bar's small icons: laps, place, clock, boost cells, the gauge's segments, a kart, a bolt
    (characters: CircuitHud.java; CircuitArtTest checks both agree)
  * the 통합 메뉴's pictures for the minigame pages (hub_art, `theme: hanok`): the 서킷 scene card, the 준비 중 card, the
    dock's 미니게임 icon, the 서킷 page's card icons, and one mini map icon a track (drawn from the track's own file)
  * the waiting room's background (menus/circuit.yml, `theme: circuit`): an asphalt panel with kerbs and a chequered
    title strip, a pit lane of five kart bays, nine paint pots, the start grid of racers and five buttons

    python3 circuit_art.py            # all of it (gui.py draws the menus' backgrounds afterwards: python3 -c "import gui; gui.write(...)")
    python3 circuit_art.py --preview  # docs/previews/circuit_hud.png (every picture on a dark board)
"""

import json
import math
import sys
from pathlib import Path

import yaml
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
CONTENT = REPO / "content" / "stsv"
ASSETS = CONTENT / "assets" / "stsv"
HUB_ART = HERE / "hub_art"
FONT = HERE / "fonts" / "Sunflower-Medium.ttf"

INK = (16, 14, 20, 255)
WHITE = (255, 255, 255, 255)
GOLD = (255, 211, 107, 255)

# ================================================================================================
# The title pictures
# ================================================================================================

TITLE = {"three": 0xE001, "two": 0xE002, "one": 0xE003, "go": 0xE004, "finish": 0xE005, "wrong": 0xE006, "record": 0xE007, "lap": 0xE008}
PLACE = 0xE010
ICONS = {"flag": 0xE100, "cup": 0xE101, "clock": 0xE102, "cell": 0xE103, "cell_empty": 0xE104, "seg": 0xE105, "seg_empty": 0xE106,
         "kart": 0xE107, "bolt": 0xE108}


def _font(px):
    return ImageFont.truetype(str(FONT), px)


def big_text(text, px, top, bottom, edge=INK, outline=5, shadow=6, italic=0.18):
    """Words in Sunflower filled with a top-to-bottom gradient, a thick dark outline and a drop shadow, leaning forward
    (a racing look)."""
    f = _font(px)
    box = f.getbbox(text)
    w, h = box[2] - box[0], box[3] - box[1]
    pad = outline + shadow + 6
    W, H = w + pad * 2 + int(h * italic) + 4, h + pad * 2
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).text((pad - box[0], pad - box[1]), text, font=f, fill=255)
    # lean: shift rows to the right the higher they are
    lean = Image.new("L", (W, H), 0)
    for y in range(H):
        dx = int((H - y) * italic)
        lean.paste(mask.crop((0, y, W - dx, y + 1)), (dx, y))
    mask = lean
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))

    def grow(m, r):
        from PIL import ImageFilter
        return m.filter(ImageFilter.MaxFilter(r * 2 + 1)) if r else m
    sh = grow(mask, outline)
    shadow_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shadow_layer.paste(Image.new("RGBA", (W, H), (0, 0, 0, 150)), (shadow // 2, shadow), sh)
    out.alpha_composite(shadow_layer)
    edge_layer = Image.new("RGBA", (W, H), edge)
    out.paste(edge_layer, (0, 0), sh)
    fill = Image.new("RGBA", (W, H))
    fd = ImageDraw.Draw(fill)
    ys = [y for y in range(H) if any(mask.getpixel((x, y)) for x in range(0, W, 2))]
    y0, y1 = (min(ys), max(ys)) if ys else (0, H)
    for y in range(H):
        t = max(0.0, min(1.0, (y - y0) / max(1, y1 - y0)))
        c = tuple(int(top[k] + (bottom[k] - top[k]) * t) for k in range(3)) + (255,)
        fd.line([(0, y), (W, y)], fill=c)
    out.paste(fill, (0, 0), mask)
    # a light shine across the upper third
    band = Image.new("L", (W, H), 0)
    ImageDraw.Draw(band).rectangle([0, y0, W, y0 + max(1, (y1 - y0) // 4)], fill=80)
    from PIL import ImageChops
    shine = Image.new("RGBA", (W, H), (255, 255, 255, 0))
    shine.putalpha(ImageChops.multiply(band, mask))
    out.alpha_composite(shine)
    return out.crop(out.getbbox())


def title_pictures():
    out = {
        "three": big_text("3", 150, (255, 120, 90), (210, 30, 40)),
        "two": big_text("2", 150, (255, 196, 90), (235, 110, 20)),
        "one": big_text("1", 150, (255, 246, 140), (240, 190, 30)),
        "go": big_text("GO!", 140, (190, 255, 140), (40, 190, 70)),
        "finish": big_text("FINISH!", 120, (255, 246, 190), (255, 170, 40)),
        "lap": big_text("LAP", 96, (200, 236, 255), (70, 150, 255)),
        "wrong": big_text("역주행!", 104, (255, 160, 160), (220, 30, 40)),
        "record": big_text("새 기록!", 104, (255, 250, 200), (255, 180, 40)),
    }
    for k in range(1, 13):
        top, bottom = ((255, 246, 170), (255, 175, 30)) if k == 1 else ((236, 240, 255), (160, 175, 210)) if k == 2 \
            else ((255, 214, 170), (205, 120, 60)) if k == 3 else ((230, 230, 236), (150, 150, 165))
        out[f"place{k}"] = big_text(f"{k}위", 120, top, bottom)
    return out


def chequer_band(w, h, sq):
    """A strip of chequered squares, fading towards its ends."""
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for x in range(0, w, sq):
        for y in range(0, h, sq):
            fade = min(1.0, min(x, w - x) / (w * 0.25))
            c = (250, 250, 250) if (x // sq + y // sq) % 2 == 0 else (24, 24, 28)
            d.rectangle([x, y, x + sq - 1, y + sq - 1], fill=c + (int(255 * fade),))
    return im


def write_fonts():
    folder = ASSETS / "textures" / "font" / "circuit"
    folder.mkdir(parents=True, exist_ok=True)
    for f in folder.glob("*.png"):
        f.unlink()
    providers = []
    pics = title_pictures()
    # titles are drawn 4x: a glyph 16 units high is a big picture; the pictures are scaled to 64 px high
    for name, im in pics.items():
        h = 64 if not name.startswith("place") else 56
        if name in ("finish", "record", "wrong"):
            h = 56
        scale = h / im.height
        im = im.resize((max(1, int(im.width * scale)), h), Image.LANCZOS)
        im.save(folder / f"title_{name}.png")
        char = TITLE.get(name, PLACE + int(name[5:]) - 1 if name.startswith("place") else None)
        providers.append({"type": "bitmap", "file": f"stsv:font/circuit/title_{name}.png", "ascent": 12, "height": 16 if h == 64 else 14,
                          "chars": [chr(char)]})
    (ASSETS / "font").mkdir(parents=True, exist_ok=True)
    (ASSETS / "font" / "circuit_title.json").write_text(json.dumps({"providers": providers}, ensure_ascii=True, indent=1))
    icon_providers = []
    for name, im in icons().items():
        im.save(folder / f"icon_{name}.png")
        icon_providers.append({"type": "bitmap", "file": f"stsv:font/circuit/icon_{name}.png", "ascent": 7, "height": 8, "chars": [chr(ICONS[name])]})
    (ASSETS / "font" / "circuit.json").write_text(json.dumps({"providers": icon_providers}, ensure_ascii=True, indent=1))
    print(f"fonts   {len(providers)} title pictures, {len(icon_providers)} icons (assets/stsv/font/circuit*.json)")


# ================================================================================================
# The boss bar's icons (8 x 8 pixel art, outlined)
# ================================================================================================

def _pix(rows, colours):
    h, w = len(rows), max(len(r) for r in rows)
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colours:
                im.putpixel((x, y), colours[ch])
    return im


def icons():
    K, W, G, D, O, Y, R, B, S = INK, WHITE, (150, 150, 160, 255), (60, 60, 70, 255), (255, 154, 60, 255), (255, 211, 80, 255), \
        (230, 60, 50, 255), (90, 170, 255, 255), (200, 200, 210, 255)
    pal = {"k": K, "w": W, "g": G, "d": D, "o": O, "y": Y, "r": R, "b": B, "s": S}
    return {
        "flag": _pix(["kkkkkkk.", "kwkwkwk.", "kkwkwkk.", "kwkwkwk.", "kkkkkkk.", "k.......", "k.......", "k......."],
                     {"k": K, "w": W}),
        "cup": _pix(["kkkkkkkk", "kyyyyyyk", "kyyyyyyk", ".kyyyyk.", "..kyyk..", "...kk...", "..kyyk..", ".kkkkkk."], pal),
        "clock": _pix([".kkkkk..", "kwwkwwk.", "kwwkwwk.", "kwwkkkk.", "kwwwwwk.", "kwwwwwk.", ".kkkkk..", "........"], pal),
        "cell": _pix(["..kkk...", ".kyyok..", "kyywook.", "kyowook.", "kyooook.", ".koook..", "..kkk...", "........"], pal),
        "cell_empty": _pix(["..kkk...", ".kdddk..", "kddgddk.", "kdddddk.", "kdddddk.", ".kdddk..", "..kkk...", "........"], pal),
        "seg": _pix(["kkk", "kwk", "kwk", "kwk", "kwk", "kwk", "kkk", "..."], pal),
        "seg_empty": _pix(["kkk", "kdk", "kdk", "kdk", "kdk", "kdk", "kkk", "..."], pal),
        "kart": _pix(["........", "...kkk..", "..krrrk.", "kkrrrrrk", "krrrrrrk", "kkdkkdkk", ".kgk.kgk", "..k...k."], pal),
        "bolt": _pix(["....kk..", "...kyk..", "..kyk...", ".kyyykk.", ".kkyyk..", "...kyk..", "..kyk...", "..kk...."], pal),
    }


# ================================================================================================
# The hub's pictures (hub_art: scenes 48 x 64, card icons 24 x 24, dock icons 16 x 16)
# ================================================================================================

def _lerp(a, b, t):
    return tuple(int(a[k] + (b[k] - a[k]) * t) for k in range(3)) + (255,)


def scene_circuit():
    """A race at dusk: a sky, hills, a grandstand, the track curving in from the bottom with kerbs, two karts racing,
    a chequered flag on its pole."""
    W, H = 48, 64
    im = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(im)
    for y in range(H):
        d.line([(0, y), (W, y)], fill=_lerp((255, 168, 110), (110, 120, 200), 1 - y / 30) if y < 30 else (70, 140, 70, 255))
    # sun and hills
    d.ellipse([30, 8, 42, 20], fill=(255, 226, 150, 255))
    for x in range(W):
        hy = 24 + int(3 * math.sin(x * 0.25) + 2 * math.sin(x * 0.11 + 1))
        d.line([(x, hy), (x, 31)], fill=(80, 120, 120, 255))
    # grandstand with a striped roof and a crowd
    d.rectangle([2, 22, 22, 31], fill=(70, 74, 86, 255))
    for x in range(2, 23, 2):
        d.rectangle([x, 20, x + 1, 21], fill=(230, 60, 50, 255) if (x // 2) % 2 else (245, 245, 245, 255))
    for x in range(3, 22):
        for y in (24, 26, 28):
            if (x * 7 + y * 3) % 4:
                im.putpixel((x, y), [(255, 211, 80, 255), (90, 170, 255, 255), (240, 120, 160, 255), (120, 220, 120, 255)][(x + y) % 4])
    # the field and the track: a wide curve from the bottom left to the right
    d.rectangle([0, 31, W, H], fill=(84, 160, 74, 255))
    for y in range(32, H):
        t = (y - 32) / (H - 32)
        cx = 14 + 20 * (1 - (1 - t) ** 2)
        half = 4 + 12 * t
        x0, x1 = int(cx - half), int(cx + half)
        d.line([(x0, y), (x1, y)], fill=(78, 80, 88, 255))
        for k, x in enumerate((x0 - 1, x0 - 2, x1 + 1, x1 + 2)):
            if 0 <= x < W:
                im.putpixel((x, y), (230, 60, 50, 255) if (y // 2) % 2 else (245, 245, 245, 255))
        if (y // 3) % 2 == 0:
            im.putpixel((int(cx), y), (240, 240, 240, 255))
    # two karts: red in front, blue behind
    for (kx, ky, body) in ((26, 42, (226, 52, 44)), (16, 54, (70, 140, 240))):
        d.rectangle([kx - 4, ky - 1, kx + 4, ky + 2], fill=body + (255,))
        d.rectangle([kx - 2, ky - 4, kx + 2, ky - 1], fill=(240, 200, 150, 255))   # the driver
        d.rectangle([kx - 2, ky - 5, kx + 2, ky - 4], fill=(40, 40, 50, 255))     # helmet
        for wx in (kx - 5, kx + 4):
            d.rectangle([wx, ky + 1, wx + 1, ky + 3], fill=(20, 20, 24, 255))
        d.line([(kx - 9, ky + 1), (kx - 6, ky + 1)], fill=(255, 255, 255, 120))   # speed lines
    # the chequered flag on its pole, top right
    d.line([(44, 30), (44, 46)], fill=(40, 40, 46, 255))
    for x in range(36, 44):
        for y in range(30, 36):
            im.putpixel((x, y), (250, 250, 250, 255) if ((x - 36) // 2 + (y - 30) // 2) % 2 == 0 else (20, 20, 24, 255))
    return im


def scene_soon():
    """A shut pit garage: a ribbed shutter, a striped header, a gold question mark on a sign."""
    W, H = 48, 64
    im = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(im)
    for y in range(H):
        d.line([(0, y), (W, y)], fill=_lerp((60, 64, 84), (30, 32, 44), y / H))
    d.rectangle([4, 12, 43, 60], fill=(48, 50, 60, 255))
    for y in range(16, 60, 3):
        d.line([(6, y), (41, y)], fill=(120, 126, 140, 255))
        d.line([(6, y + 1), (41, y + 1)], fill=(84, 88, 100, 255))
    for x in range(4, 44, 4):
        d.rectangle([x, 10, x + 1, 13], fill=(255, 211, 80, 255))
        d.rectangle([x + 2, 10, x + 3, 13], fill=(30, 30, 34, 255))
    d.rectangle([15, 28, 32, 46], fill=(30, 32, 40, 255), outline=(255, 211, 80, 255))
    f = _font(16)
    d.text((18, 27), "?", font=f, fill=(255, 211, 80, 255))
    d.rectangle([20, 54, 27, 60], fill=(200, 160, 60, 255))
    return im


def _outline(im):
    """A dark 1 px outline round whatever is drawn (the hub's PixelLab icons are outlined)."""
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            if px[x, y][3]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                xx, yy = x + dx, y + dy
                if 0 <= xx < im.width and 0 <= yy < im.height and px[xx, yy][3]:
                    out.putpixel((x, y), (24, 16, 14, 255))
                    break
    out.alpha_composite(im)
    return out


def icon_minigame():
    """16 px: a chequered flag waving on a gold pole (the dock's 미니게임)."""
    im = Image.new("RGBA", (16, 16))
    d = ImageDraw.Draw(im)
    d.line([(3, 2), (3, 14)], fill=(220, 170, 70, 255))
    for x in range(4, 13):
        wave = int(round(math.sin((x - 4) * 0.7) * 1))
        for y in range(3, 9):
            yy = y + wave
            im.putpixel((x, yy), (250, 250, 250, 255) if ((x - 4) // 2 + (y - 3) // 2) % 2 == 0 else (30, 30, 36, 255))
    d.ellipse([2, 1, 4, 3], fill=(255, 226, 140, 255))
    return _outline(im)


def icon_kart(colour=(226, 52, 44)):
    """24 px: a little kart from the side, going right, speed lines behind."""
    im = Image.new("RGBA", (24, 24))
    d = ImageDraw.Draw(im)
    d.rectangle([5, 12, 19, 15], fill=colour + (255,))
    d.rectangle([17, 13, 21, 15], fill=colour + (255,))
    d.rectangle([9, 8, 13, 12], fill=(240, 200, 150, 255))
    d.rectangle([9, 6, 13, 8], fill=(255, 211, 80, 255))
    d.rectangle([4, 10, 6, 12], fill=(40, 40, 46, 255))
    for wx in (6, 17):
        d.ellipse([wx - 2, 14, wx + 2, 18], fill=(24, 24, 28, 255))
        im.putpixel((wx, 16), (170, 170, 180, 255))
    for y in (9, 12, 15):
        d.line([(0, y), (3, y)], fill=(255, 255, 255, 200))
    return _outline(im)


def icon_trophy():
    im = Image.new("RGBA", (24, 24))
    d = ImageDraw.Draw(im)
    d.rectangle([7, 4, 16, 11], fill=(255, 205, 70, 255))
    d.ellipse([7, 6, 16, 14], fill=(255, 205, 70, 255))
    d.line([(5, 6), (5, 9)], fill=(255, 205, 70, 255))
    d.line([(18, 6), (18, 9)], fill=(255, 205, 70, 255))
    d.rectangle([10, 14, 13, 17], fill=(220, 160, 50, 255))
    d.rectangle([7, 18, 16, 20], fill=(150, 100, 60, 255))
    d.line([(9, 5), (9, 10)], fill=(255, 240, 180, 255))
    for x in range(10, 15):
        for y in range(7, 10):
            im.putpixel((x, y), (250, 250, 250, 255) if (x + y) % 2 else (40, 40, 46, 255))
    return _outline(im)


def icon_exit():
    im = Image.new("RGBA", (24, 24))
    d = ImageDraw.Draw(im)
    d.rectangle([6, 3, 15, 20], fill=(120, 80, 50, 255))
    d.rectangle([7, 4, 14, 19], fill=(60, 40, 30, 255))
    d.polygon([(13, 11), (20, 6), (20, 16)], fill=(230, 70, 60, 255))
    d.rectangle([10, 10, 14, 12], fill=(230, 70, 60, 255))
    return _outline(im)


THEME_COLOURS = {"village": ((92, 168, 76), (150, 150, 158)), "canyon": ((200, 110, 60), (120, 116, 112)),
                 "glacier": ((228, 240, 250), (120, 170, 230)), "forest": ((70, 130, 60), (130, 96, 70)),
                 "city": ((40, 40, 56), (210, 90, 230))}


def track_points(spec):
    """The centre line as the plugin draws it (TrackPath: Catmull-Rom through the points), coarse."""
    pts = []
    for p in spec["points"]:
        if isinstance(p, dict):
            pts.append((p["at"][0], p["at"][1]))
        else:
            pts.append((p[0], p[1]))
    n = len(pts)
    out = []
    for i in range(n):
        p0, p1, p2, p3 = pts[(i - 1) % n], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
        for k in range(12):
            t = k / 12
            t2, t3 = t * t, t * t * t
            out.append(tuple(0.5 * (2 * p1[a] + (-p0[a] + p2[a]) * t + (2 * p0[a] - 5 * p1[a] + 4 * p2[a] - p3[a]) * t2
                                    + (-p0[a] + 3 * p1[a] - 3 * p2[a] + p3[a]) * t3) for a in (0, 1)))
    return out


def icon_track(spec):
    """24 px: the track's shape from above in its theme's colours, the start marked."""
    ground, road = THEME_COLOURS.get(spec.get("theme"), ((90, 160, 80), (150, 150, 158)))
    S = 4
    big = Image.new("RGBA", (24 * S, 24 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    d.rounded_rectangle([1 * S, 1 * S, 23 * S - 1, 23 * S - 1], radius=3 * S, fill=ground + (255,))
    pts = track_points(spec)
    xs, zs = [p[0] for p in pts], [p[1] for p in pts]
    cx, cz = (max(xs) + min(xs)) / 2, (max(zs) + min(zs)) / 2
    span = max(max(xs) - min(xs), max(zs) - min(zs))
    k = 17 * S / span
    pp = [((x - cx) * k + 12 * S, (z - cz) * k + 12 * S) for x, z in pts]
    d.line(pp + [pp[0]], fill=(24, 24, 28, 255), width=int(3.4 * S), joint="curve")
    d.line(pp + [pp[0]], fill=road + (255,), width=int(2.0 * S), joint="curve")
    sx, sz = pp[0]
    d.rectangle([sx - 1.6 * S, sz - 1.6 * S, sx + 1.6 * S, sz + 1.6 * S], fill=(250, 250, 250, 255), outline=(20, 20, 24, 255))
    im = big.resize((24, 24), Image.LANCZOS)
    a = im.getchannel("A").point(lambda v: 255 if v > 100 else 0)
    im.putalpha(a)
    return _outline(im)


def write_hub_art():
    (HUB_ART / "scenes").mkdir(exist_ok=True)
    scene_circuit().save(HUB_ART / "scenes" / "circuit.png")
    scene_soon().save(HUB_ART / "scenes" / "soon.png")
    icon_minigame().save(HUB_ART / "icons" / "minigame.png")
    icon_kart().save(HUB_ART / "icons" / "circuit_join.png")
    icon_trophy().save(HUB_ART / "icons" / "circuit_rank.png")
    icon_exit().save(HUB_ART / "icons" / "circuit_leave.png")
    tracks = sorted((CONTENT / "circuit" / "tracks").glob("*.yml"))
    for f in tracks:
        icon_track(yaml.safe_load(f.read_text())).save(HUB_ART / "icons" / f"track_{f.stem}.png")
    print(f"hub     circuit scene, soon scene, 미니게임 icon, 3 card icons, {len(tracks)} track icons (hub_art)")


# ================================================================================================
# The waiting room's background (`theme: circuit`)
# ================================================================================================

def lobby_background(menu, art=None):
    from stsv_assets import menu_theme as T
    rows = menu["rows"]
    Wd, Hd = T.W, T.height(rows)
    im = Image.new("RGBA", (Wd, Hd), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    ASPH, ASPH_D, ASPH_L = (52, 54, 62), (40, 42, 48), (66, 69, 79)
    # the panel: asphalt with a fine grain, a dark rim, rounded corners
    d.rounded_rectangle([0, 0, Wd - 1, Hd - 1], radius=4, fill=(14, 15, 18, 255))
    d.rounded_rectangle([1, 1, Wd - 2, Hd - 2], radius=3, fill=ASPH + (255,))
    for y in range(2, Hd - 2):
        for x in range(2, Wd - 2):
            h = (x * 73856093 ^ y * 19349663) & 0xff
            if h < 14:
                im.putpixel((x, y), ASPH_D + (255,))
            elif h > 244:
                im.putpixel((x, y), ASPH_L + (255,))
    # the title strip: darker, a chequered flag band on the right, a kerb along its bottom
    d.rectangle([2, 2, Wd - 3, 14], fill=(30, 31, 37, 255))
    band = chequer_band(56, 9, 3)
    im.alpha_composite(band, (Wd - 62, 4))
    for x in range(2, Wd - 2):
        d.point((x, 15), fill=(226, 52, 44, 255) if (x // 4) % 2 else (240, 240, 240, 255))
    d.line([(2, 16), (Wd - 3, 16)], fill=(14, 15, 18, 255))
    layout = menu["layout"]
    buttons = menu.get("buttons", {}) or {}
    lst = menu.get("list")
    words = []

    def cell(r, c):
        return 7 + 18 * c, 17 + 18 * r

    for r, row in enumerate(layout):
        for c, ch in enumerate(row):
            x, y = cell(r, c)
            if ch == ".":
                continue
            b = buttons.get(ch, {}) or {}
            act = b.get("action", "")
            if act.startswith("circuit:kart:"):
                # a pit bay: a lit floor with yellow bay lines
                d.rectangle([x, y, x + 17, y + 17], fill=(24, 25, 30, 255))
                d.rectangle([x + 1, y + 1, x + 16, y + 16], fill=(64, 68, 80, 255))
                d.line([(x + 1, y + 16), (x + 16, y + 16)], fill=(255, 211, 80, 255))
                d.line([(x + 1, y + 1), (x + 1, y + 16)], fill=(255, 211, 80, 255))
                d.line([(x + 16, y + 1), (x + 16, y + 16)], fill=(255, 211, 80, 255))
            elif act.startswith("circuit:colour:"):
                # a paint pot rim
                T.slot(im, x, y)
                d.rectangle([x + 2, y + 2, x + 15, y + 3], fill=(90, 94, 108, 255))
            elif lst and ch == lst:
                # a grid box: a slot with white corner marks (the start grid)
                T.slot(im, x, y)
                for (px, py, dx, dy) in ((x + 1, y + 1, 1, 1), (x + 16, y + 1, -1, 1), (x + 1, y + 16, 1, -1), (x + 16, y + 16, -1, -1)):
                    d.point((px, py), fill=(240, 240, 240, 255))
                    d.point((px + dx, py), fill=(240, 240, 240, 255))
                    d.point((px, py + dy), fill=(240, 240, 240, 255))
            else:
                T.tile(im, x, y)
                accent = {"circuit:ready": (90, 210, 110), "circuit:start": (255, 211, 80), "circuit:leave": (230, 70, 60)}.get(act)
                if accent:
                    d.line([(x + 3, y + 2), (x + 14, y + 2)], fill=accent + (255,))
    # the row labels, in the rows the layout leaves empty
    for r, row in enumerate(layout):
        if row.strip(".") == "":
            label = (menu.get("labels") or {}).get(r)
            y = 17 + 18 * r
            # a dashed white centre line across the empty row, the label on a dark plate in the middle
            for x in range(9, Wd - 9, 8):
                d.line([(x, y + 9), (x + 3, y + 9)], fill=(150, 154, 166, 255))
            if label:
                words.append(((Wd // 2 - 40, y + 2, Wd // 2 + 39, y + 15), label))
    for r, row in enumerate(layout):
        # labels beside a row's buttons (`side-labels: {row: [left, right]}`): in the free cells at its ends
        sides = (menu.get("side-labels") or {}).get(r)
        if not sides:
            continue
        used = [c for c, ch in enumerate(row) if ch != "."]
        y = 17 + 18 * r
        left_end, right_start = min(used), max(used) + 1
        if sides[0] and left_end > 0:
            words.append(((8, y + 2, 7 + 18 * left_end - 2, y + 15), sides[0]))
        if len(sides) > 1 and sides[1] and right_start < 9:
            words.append(((7 + 18 * right_start + 1, y + 2, Wd - 8, y + 15), sides[1]))
    # the player's inventory below: the groove and the slots where the client puts the items
    g = 18 * rows + 23
    d.rectangle([7, g, Wd - 8, g], fill=(14, 15, 18, 255))
    d.rectangle([7, g + 1, Wd - 8, g + 1], fill=(240, 240, 240, 255))
    inv = 30 + 18 * rows
    for r in range(3):
        for c in range(9):
            T.slot(im, 7 + 18 * c, inv + 18 * r)
    for c in range(9):
        T.slot(im, 7 + 18 * c, inv + 58)
    scale = (menu.get("frame") or {}).get("scale", 1)
    out = im.resize((Wd * scale, Hd * scale), Image.NEAREST)
    import hub_hanok
    for (x0, y0, x1, y1), text in words:
        plate = Image.new("RGBA", ((x1 - x0 + 1) * scale, (y1 - y0 + 1) * scale), (22, 23, 28, 230))
        out.alpha_composite(plate, (x0 * scale, y0 * scale))
        hub_hanok._write(out, (x0 * scale, y0 * scale + 1, (x1 + 1) * scale - 1, (y1 + 1) * scale - 2), text, 11 * scale,
                         (255, 226, 150), (20, 12, 8))
    # every glyph tile keeps its last column non-empty (the client measures a glyph to its last column)
    cols, rows_ = -(-out.width // 256), -(-out.height // 256)
    tw, th = out.width // cols, out.height // rows_
    for r in range(rows_):
        for c in range(cols):
            x, y = (c + 1) * tw - 1, r * th
            if out.getpixel((x, y))[3] == 0:
                out.putpixel((x, y), (0, 0, 0, 1))
    return out


# ================================================================================================

def preview(path):
    pics = title_pictures()
    W = 1400
    rows = [list(pics.items())[:8], list(pics.items())[8:]]
    H = 40 + sum(max(im.height for _, im in r) // 2 + 20 for r in rows) + 80
    board = Image.new("RGBA", (W, H), (34, 36, 44, 255))
    y = 20
    for r in rows:
        x = 20
        for _, im in r:
            small = im.resize((im.width // 2, im.height // 2), Image.LANCZOS)
            if x + small.width > W - 20:
                x = 20
                y += small.height + 10
            board.alpha_composite(small, (x, y))
            x += small.width + 16
        y += max(im.height for _, im in r) // 2 + 20
    x = 20
    for name, im in icons().items():
        board.alpha_composite(im.resize((im.width * 6, im.height * 6), Image.NEAREST), (x, y))
        x += im.width * 6 + 16
    board.convert("RGB").save(path)
    print("preview", path)


if __name__ == "__main__":
    sys.path.insert(0, str(HERE))
    if "--preview" in sys.argv:
        preview(REPO / "docs" / "previews" / "circuit_hud.png")
    else:
        write_fonts()
        write_hub_art()
