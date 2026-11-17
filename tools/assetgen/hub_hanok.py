"""
The 통합 메뉴's 한옥 look (`theme: hanok` in a menu's yml): a tiled roof with a 단청 beam and a name board (현판)
over the panel, red pillars on both sides with 청사초롱 lanterns, picture cards and a wooden bar (마루) of
quick buttons. Pictures by PixelLab (tools/assetgen/hub_art: the roof, a lantern, the pillar's body and base
cut from one generated panel; the card scenes generated one by one and picked by eye), put together here on
the menu's own slot grid so every card and slot sits exactly where the game puts the items.

The picture is drawn at 2 texels per screen pixel (menu `frame: {scale: 2}`): the pixel art is doubled, the words
(the board's title, the card names) are written in Sunflower (tools/assetgen/fonts, SIL OFL) at the finer grid.

    frame:   left 30, right 30, top 20, scale 2   (the roof, pillars and lanterns reach beyond the 176 px panel)
    plaque:  the words on the board (the title line itself stays empty)
    buttons: `scene` + `label` = a tall picture card (4+ rows), `art` + `label` = a small card (an icon),
             in the `dock` row: `art` = an icon in its socket, no art = the gold socket for the player's head
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from stsv_assets import menu_theme as T

HERE = Path(__file__).resolve().parent
ART = HERE / "hub_art"
FONT = HERE / "fonts" / "Sunflower-Medium.ttf"

LEFT, RIGHT, TOP, SCALE = 30, 30, 20, 2
INK = (14, 12, 18)
PAPER, LATTICE = (34, 30, 36), (40, 36, 44)
WOOD, WOOD_L, WOOD_D = (92, 58, 38), (130, 86, 54), (54, 32, 22)
GOLD, GOLD_L, GOLD_D = (226, 180, 82), (255, 226, 140), (150, 108, 44)
BOARD = (40, 22, 18)
DAN_G, DAN_GL, DAN_R, DAN_B, DAN_W = (46, 130, 112), (90, 176, 150), (196, 52, 48), (52, 88, 170), (236, 230, 206)
TEXT, TEXT_EDGE = (255, 242, 210), (30, 12, 8)
TITLE, TITLE_EDGE = (255, 226, 140), (36, 14, 6)


def size(rows):
    return (T.W + LEFT + RIGHT, T.height(rows) + TOP)


def _rect(im, x0, y0, x1, y1, c):
    """x1, y1 inclusive."""
    if x1 >= x0 and y1 >= y0:
        ImageDraw.Draw(im).rectangle([x0, y0, x1, y1], fill=tuple(c[:3]) + (255,))


def _png(name):
    return Image.open(ART / f"{name}.png").convert("RGBA")


def _dancheong(im, x0, x1, y, h=4):
    _rect(im, x0, y, x1, y + h - 1, DAN_G)
    _rect(im, x0, y, x1, y, DAN_GL)
    _rect(im, x0, y + h - 1, x1, y + h - 1, (26, 84, 70))
    for x in range(x0 + 2, x1 - 4, 10):
        _rect(im, x, y + 1, x + 4, y + h - 2, DAN_W)
        _rect(im, x + 1, y + 1, x + 3, y + h - 2, DAN_R)
        im.putpixel((x + 2, y + h // 2), DAN_B + (255,))
    _rect(im, x0, y - 1, x1, y - 1, INK)
    _rect(im, x0, y + h, x1, y + h, INK)


def _frame_box(im, x, y, w, h):
    """A wooden frame with a light top-left and a dark bottom-right edge."""
    _rect(im, x, y, x + w - 1, y + h - 1, INK)
    _rect(im, x + 1, y + 1, x + w - 2, y + h - 2, WOOD)
    _rect(im, x + 1, y + 1, x + w - 2, y + 1, WOOD_L)
    _rect(im, x + 1, y + 1, x + 1, y + h - 2, WOOD_L)
    _rect(im, x + 1, y + h - 2, x + w - 2, y + h - 2, WOOD_D)
    _rect(im, x + w - 2, y + 1, x + w - 2, y + h - 2, WOOD_D)


def _gold_corners(im, x0, y0, x1, y1):
    for (cx, cy, sx, sy) in ((x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1)):
        for k in range(4):
            im.putpixel((cx + sx * k, cy), GOLD + (255,))
            im.putpixel((cx, cy + sy * k), GOLD + (255,))
        im.putpixel((cx + sx, cy + sy), GOLD_L + (255,))


def _board(im, x0, y0, x1, y1, accent=None):
    _rect(im, x0, y0, x1, y1, GOLD_D)
    _rect(im, x0 + 1, y0 + 1, x1 - 1, y1 - 1, BOARD)
    _rect(im, x0 + 1, y0 + 1, x1 - 1, y0 + 1, (70, 40, 30))
    if accent:
        im.putpixel((x0 + 2, (y0 + y1) // 2), accent + (255,))
        im.putpixel((x1 - 2, (y0 + y1) // 2), accent + (255,))


def _cells(menu, ch):
    cells = [(r, c) for r, row in enumerate(menu["layout"]) for c, x in enumerate(row) if x == ch]
    r0, r1 = min(r for r, _ in cells), max(r for r, _ in cells)
    c0, c1 = min(c for _, c in cells), max(c for _, c in cells)
    if len(cells) != (r1 - r0 + 1) * (c1 - c0 + 1):
        raise ValueError(f"card {ch} is not a rectangle of cells")
    return c0, r0, c1 - c0 + 1, r1 - r0 + 1


def _hex(s):
    s = str(s or "").lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4)) if len(s) == 6 else None


def background(menu, art):
    """The 2x picture of one hub menu. art: icon id -> 16 x 16 image."""
    rows = menu["rows"]
    W, H = size(rows)
    ox, oy = LEFT, TOP
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    words = []                                   # (centre x, centre y, text, font size in texels, fill, edge)

    # the panel: ink rim, dark paper with a faint 창살 lattice
    _rect(im, ox - 1, oy, ox + T.W, oy + T.height(rows) - 1, INK)
    _rect(im, ox, oy + 1, ox + T.W - 1, oy + T.height(rows) - 2, PAPER)
    for y in range(oy + 6, oy + T.height(rows) - 3, 8):
        _rect(im, ox + 1, y, ox + T.W - 2, y, LATTICE)
    for x in range(ox + 4, ox + T.W - 2, 8):
        _rect(im, x, oy + 2, x, oy + T.height(rows) - 3, LATTICE)

    # pillars (left as cut, right mirrored) from under the beam to the stone bases
    mid, base = _png("pillar_mid"), _png("pillar_base")
    base_y = H - base.height
    for flip in (False, True):
        m = mid.transpose(Image.FLIP_LEFT_RIGHT) if flip else mid
        b = base.transpose(Image.FLIP_LEFT_RIGHT) if flip else base
        mx = W - 23 - m.width if flip else 23
        bx = W - 21 - b.width if flip else 21
        y = oy + 14
        while y < base_y:
            im.alpha_composite(m.crop((0, 0, m.width, min(m.height, base_y - y))), (mx, y))
            y += m.height
        im.alpha_composite(b, (bx, base_y))

    # the roof (with the beam and the board) over the title strip; its last row sits on the panel's row 16
    roof = _png("roof")
    im.alpha_composite(roof, (0, oy + 16 - (roof.height - 1)))
    # lanterns hanging from the eaves
    lantern = _png("lantern").crop((0, 12, 10, 51))
    for flip in (False, True):
        ln = lantern.transpose(Image.FLIP_LEFT_RIGHT) if flip else lantern
        lx = W - 14 - ln.width if flip else 14
        sx = lx + (4 if flip else 5)
        _rect(im, sx, oy + 6, sx, oy + 21, (60, 44, 30))
        im.alpha_composite(ln, (lx, oy + 21))
    # the board's words
    roof_y = oy + 16 - (roof.height - 1)
    _rect(im, 90, roof_y + 4, 146, roof_y + 15, (52, 28, 20))
    if menu.get("plaque"):
        words.append((118, roof_y + 9.5, menu["plaque"], 20, TITLE, TITLE_EDGE))

    buttons = menu.get("buttons", {}) or {}
    dock = menu.get("dock")
    for ch, b in buttons.items():
        if not isinstance(b, dict) or not (b.get("scene") or b.get("art")):
            continue
        c0, r0, cw, rh = _cells(menu, ch)
        if dock is not None and r0 == dock:
            continue
        x, y, w, h = ox + 7 + 18 * c0, oy + 17 + 18 * r0, 18 * cw, 18 * rh
        accent = _hex(b.get("accent"))
        if b.get("scene"):
            # a tall picture card: the scene in a gold-cornered window, the name on a board under it
            _frame_box(im, x, y, w, h)
            pic = _png(f"scenes/{b['scene']}")
            win_h = h - 26
            pic = pic.crop((0, (pic.height - win_h) // 2, pic.width, (pic.height - win_h) // 2 + win_h))
            _rect(im, x + 2, y + 2, x + w - 3, y + 3 + win_h, INK)
            im.alpha_composite(pic, (x + (w - pic.width) // 2, y + 3))
            _gold_corners(im, x + 1, y + 1, x + w - 2, y + 3 + win_h)
            by0 = y + 5 + win_h
            _board(im, x + 3, by0, x + w - 4, y + h - 4, accent)
            words.append((x + w / 2, (by0 + y + h - 4) / 2 + 0.5, b.get("label", ""), 22, TEXT, TEXT_EDGE))
        else:
            # a small card: the icon (24 px PixelLab art, or a 16 px one) in a dark window, the name on a board
            _frame_box(im, x, y, w, h)
            _rect(im, x + 3, y + 2, x + w - 4, y + 25, BOARD)
            _gold_corners(im, x + 2, y + 2, x + w - 3, y + 25)
            if art:
                pic = art(b["art"])
                im.alpha_composite(pic, (x + (w - pic.width) // 2, y + 2 + (24 - pic.height) // 2))
            _board(im, x + 3, y + 26, x + w - 4, y + h - 2, accent)
            words.append((x + w / 2, y + (26 + h - 2) / 2 + 0.5, b.get("label", ""), 13, TEXT, TEXT_EDGE))

    if dock is not None:
        y = oy + 17 + 18 * dock
        row = menu["layout"][dock]
        used = [c for c, ch in enumerate(row) if ch != "."]
        x0 = ox + 7 + 18 * min(used)
        _rect(im, x0, y, ox + 7 + 18 * (max(used) + 1) - 1, y + 17, INK)
        for c in used:
            # each socket a pale 한지 panel in a wooden lattice, so the dark-outlined icons stand out
            cx = ox + 7 + 18 * c
            _rect(im, cx + 1, y + 1, cx + 16, y + 16, WOOD)
            _rect(im, cx + 2, y + 2, cx + 15, y + 15, (226, 210, 176))
            _rect(im, cx + 2, y + 2, cx + 15, y + 2, (196, 176, 138))
            _rect(im, cx + 2, y + 2, cx + 2, y + 15, (196, 176, 138))
            _rect(im, cx + 3, y + 15, cx + 15, y + 15, (240, 228, 200))
            if c != max(used):
                _rect(im, cx + 17, y + 1, cx + 17, y + 16, (54, 34, 22))
            b = buttons.get(row[c]) or {}
            if b.get("art"):
                if art:
                    im.alpha_composite(art(b["art"]), (cx + 1, y + 1))
            else:
                _rect(im, cx, y, cx + 17, y + 17, GOLD)
                _rect(im, cx, y, cx + 17, y, GOLD_L)
                _rect(im, cx + 1, y + 1, cx + 16, y + 16, (226, 210, 176))

    # under the dock: a beam, then the player's inventory slots where the game puts them
    _dancheong(im, ox + 4, ox + T.W - 5, oy + 18 * rows + 21, 4)
    inv = oy + 30 + 18 * rows
    for r in range(3):
        for c in range(9):
            T.slot(im, ox + 7 + 18 * c, inv + 18 * r)
    for c in range(9):
        T.slot(im, ox + 7 + 18 * c, inv + 58)

    # the 2x picture: pixel art doubled, then the words at the finer grid
    out = im.resize((W * SCALE, H * SCALE), Image.NEAREST)
    for (cx, cy, text, px, fill, edge) in words:
        _write(out, cx * SCALE, cy * SCALE, text, px, fill, edge)
    # the client measures a glyph to its last non-empty column: keep the far right column non-empty
    out.putpixel((out.width - 1, 0), (0, 0, 0, 1))
    return out


_FONTS = {}


def _write(im, cx, cy, text, px, fill, edge):
    """Words in Sunflower with a 1 texel dark edge, centred on (cx, cy). Sunflower has no middle dot: a '·' is
    drawn as a small round dot between the words."""
    if not text:
        return
    f = _FONTS.get(px) or _FONTS.setdefault(px, ImageFont.truetype(str(FONT), px))
    parts = text.split("·")
    gap = max(6, px // 3)
    boxes = [f.getbbox(p) if p else (0, 0, 0, 0) for p in parts]
    asc = min(b[1] for b in boxes if b[3])
    desc = max(b[3] for b in boxes if b[3])
    widths = [b[2] - b[0] for b in boxes]
    w = sum(widths) + gap * (len(parts) - 1) + 6
    h = desc - asc + 6
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x = 3
    dots = []
    for k, (p, b) in enumerate(zip(parts, boxes)):
        if p:
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1), (1, 1), (-1, 1), (1, -1), (-1, -1)):
                d.text((x - b[0] + dx, 3 - asc + dy), p, font=f, fill=edge)
            d.text((x - b[0], 3 - asc), p, font=f, fill=fill)
        x += widths[k]
        if k < len(parts) - 1:
            dots.append(x + gap / 2)
            x += gap
    r = max(1.5, px / 11)
    cyd = 3 + (desc - asc) / 2
    for dx in dots:
        d.ellipse([dx - r - 1, cyd - r - 1, dx + r + 1, cyd + r + 1], fill=edge)
        d.ellipse([dx - r, cyd - r, dx + r, cyd + r], fill=fill)
    im.alpha_composite(layer, (int(round(cx - w / 2)), int(round(cy - h / 2))))
