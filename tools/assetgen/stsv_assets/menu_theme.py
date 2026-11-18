"""
The one look of every STSV menu (core and contents): charcoal, flat, a thin gold line under the title.
Draws the 176 x (114 + 18 rows) background of a menu from its own layout (menus/*.yml), so drawing and
menu always agree:
  - list cells (the paged area) and `inset` cells: recessed slots
  - buttons: raised tiles with a dark rim; `tabs` buttons (category tabs) with a dim gold cap
  - `graph: true`: a dark chart screen over rows 0-1 with guides (the stock chart is drawn there by the
    title font)
  - `flow: true`: a small arrow in an empty cell between two used cells of a row, one of them a slot
    (machine lanes: inputs -> state -> result)
  - `wheel: "abcdefg"`: those buttons sit on a round dial (the emote wheel): a dark disc with a gold rim, a spoke
    between two neighbours, a round socket under each button and a hub round the last letter (the middle one)
  - big buttons: a button with `art` (an icon id) and `label` covers a rectangle of cells (its letter repeated) and is
    drawn as one big raised tile with that icon (twice its size when the tile is 3 rows high or more) and the label
    under it in Galmuri9; the slots themselves hold an invisible item (stsv:gui/blank) that only gives the tooltip
  - empty cells: plain panel, nothing drawn
No light strip for the client's "Inventory" label: the core resource pack blanks container.inventory
(content/stsv/assets/minecraft/lang), and a divider marks where the player's inventory starts.
Slot geometry of the large chest: container slot (r, c) frame at x = 7 + 18c, y = 17 + 18r (its item at +1, +1); the
player's inventory items at y = 31 + 18 rows, so their frames at y = 30 + 18 rows (+ 18 per row), hotbar 58 below that.
"""

from PIL import Image, ImageDraw

W = 176

PANEL = (42, 46, 54)
EDGE_OUT = (12, 13, 16)
EDGE_LIGHT = (75, 81, 93)
EDGE_DARK = (22, 24, 28)
SLOT = (28, 31, 37)
SLOT_TOP = (18, 20, 24)
SLOT_BOTTOM = (61, 66, 77)
TILE = (58, 63, 74)
TILE_LIGHT = (89, 96, 112)
TILE_DARK = (35, 38, 45)
TILE_RIM = (18, 20, 24)
GOLD = (224, 176, 74)
GOLD_DIM = (138, 106, 44)
SCREEN = (23, 25, 30)
SCREEN_FRAME_DARK = (8, 9, 11)
SCREEN_FRAME_LIGHT = (92, 99, 114)
GRID = (52, 57, 68)
ARROW = (82, 88, 101)


def height(rows):
    return 114 + 18 * rows


def _rect(im, x0, y0, x1, y1, c):
    if x1 > x0 and y1 > y0:
        ImageDraw.Draw(im).rectangle([x0, y0, x1 - 1, y1 - 1], fill=c + (255,) if len(c) == 3 else c)


def _bevel(im, x, y, w, h, light, dark, fill=None):
    if fill is not None:
        _rect(im, x + 1, y + 1, x + w - 1, y + h - 1, fill)
    _rect(im, x, y, x + w - 1, y + 1, light)
    _rect(im, x, y, x + 1, y + h - 1, light)
    _rect(im, x + 1, y + h - 1, x + w, y + h, dark)
    _rect(im, x + w - 1, y + 1, x + w, y + h, dark)


def slot(im, x, y):
    """A recessed 18 x 18 item slot (shadow top/left, light bottom/right, like the game's)."""
    _bevel(im, x, y, 18, 18, SLOT_TOP, SLOT_BOTTOM, fill=SLOT)


def tile(im, x, y):
    """A raised 18 x 18 button tile."""
    _rect(im, x, y, x + 18, y + 18, TILE_RIM)
    _bevel(im, x + 1, y + 1, 16, 16, TILE_LIGHT, TILE_DARK, fill=TILE)


def tab(im, x, y):
    """A category tab: a button tile with a dim gold cap (the open tab glows in the game)."""
    tile(im, x, y)
    _rect(im, x + 2, y + 1, x + 16, y + 2, GOLD_DIM)


def _arrow(im, x, y):
    """A small right arrow centred in the 18 x 18 cell at (x, y)."""
    cx, cy = x + 9, y + 9
    for dx in range(-4, 2):
        im.putpixel((cx + dx, cy), ARROW + (255,))
        im.putpixel((cx + dx, cy - 1), ARROW + (255,))
    for k in range(4):
        for t in (cy - 1 - k, cy + k):
            im.putpixel((cx + 2 - k + 1, t), ARROW + (255,))


def _disc(im, cx, cy, r, colour):
    """A filled circle of radius r round the pixel centre (cx, cy) (half pixels allowed)."""
    px = im.load()
    for y in range(int(cy - r) - 1, int(cy + r) + 2):
        for x in range(int(cx - r) - 1, int(cx + r) + 2):
            if 0 <= x < im.width and 0 <= y < im.height and (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r:
                px[x, y] = colour + (255,)


def wheel(im, cells):
    """The dial under the wheel buttons. cells: the (row, column) of each, the last one the middle."""
    import math
    centres = [(7 + 18 * c + 9, 17 + 18 * r + 9) for r, c in cells]
    cx, cy = centres[-1]
    rim = centres[:-1]
    reach = max(math.hypot(x - cx, y - cy) for x, y in rim)
    R = reach + 11.5
    # the dial: a dark rim line, a gold ring, the dark face
    _disc(im, cx, cy, R + 1, EDGE_OUT)
    _disc(im, cx, cy, R, GOLD_DIM)
    _disc(im, cx, cy, R - 1, EDGE_DARK)
    _disc(im, cx, cy, R - 2, SCREEN)
    # a spoke between two neighbours, from the hub to the rim
    angles = sorted(math.atan2(y - cy, x - cx) for x, y in rim)
    px = im.load()
    for k, a in enumerate(angles):
        b = angles[(k + 1) % len(angles)] + (2 * math.pi if k == len(angles) - 1 else 0)
        mid = (a + b) / 2
        t = 14.0
        while t < R - 3:
            x, y = int(cx + math.cos(mid) * t), int(cy + math.sin(mid) * t)
            px[x, y] = GRID + (255,)
            t += 0.5
    # the sockets: a lit lower edge, a shadowed upper edge, the recessed face
    for x, y in rim:
        _disc(im, x, y + 0.5, 10.5, SLOT_BOTTOM)
        _disc(im, x, y - 0.5, 10.5, SLOT_TOP)
        _disc(im, x, y, 9.5, SLOT)
    # the hub: a gold ring round the middle place
    _disc(im, cx, cy, 12.5, EDGE_OUT)
    _disc(im, cx, cy, 11.5, GOLD)
    _disc(im, cx, cy, 10.5, EDGE_DARK)
    _disc(im, cx, cy, 9.5, SLOT)


LABEL = (240, 232, 206)
LABEL_SHADOW = (22, 24, 28)
_FONT = None


def _font():
    global _FONT
    if _FONT is None:
        import galmuri
        _FONT = galmuri.load(9)
    return _FONT


def text_width(s):
    f = _font()
    return sum(f[ord(ch)][0] for ch in s) - 1 if s else 0


def text(im, x, y, s, colour, shadow=None):
    """Galmuri9 text, its cap line at y (Hangul fills about y .. y + 9)."""
    f = _font()
    base = y + 9
    px = im.load()
    for ox, oy, c in (((1, 1, shadow),) if shadow else ()) + ((0, 0, colour),):
        cx = x
        for ch in s:
            advance, xo, yo, bits = f[ord(ch)]
            top = base - (yo + len(bits))
            for r, row in enumerate(bits):
                for k, on in enumerate(row):
                    if on:
                        X, Y = cx + xo + k + ox, top + r + oy
                        if 0 <= X < im.width and 0 <= Y < im.height:
                            px[X, Y] = c + (255,)
            cx += advance


def big_tile(im, x, y, w, h, art, label):
    """A big button over w x h px of cells: a raised tile with a gold cap line, the icon centred (2x when the tile
    is 54 px high or more) and the label under it."""
    _rect(im, x, y, x + w, y + h, TILE_RIM)
    _bevel(im, x + 1, y + 1, w - 2, h - 2, TILE_LIGHT, TILE_DARK, fill=TILE)
    _rect(im, x + 3, y + 2, x + w - 3, y + 3, GOLD_DIM)
    scale = 2 if h >= 54 else 1
    icon = art.resize((art.width * scale, art.height * scale), Image.NEAREST) if art is not None else None
    ih = icon.height if icon else 0
    gap = 3 if scale == 2 else 2
    th = 10 if label else 0
    top = y + (h - ih - (gap + th if label else 0)) // 2 + 1
    if icon is None:
        # no drawn icon: an item sits in the middle cell of the tile, the label goes under that cell
        top, ih, gap = y + 18 * ((h // 18) // 2 + 1) + 3, 0, 0
    if icon:
        # a soft dark plate behind the icon so it reads on the tile
        im.alpha_composite(icon, (x + (w - icon.width) // 2, top))
    if label:
        tw = text_width(label)
        text(im, x + (w - tw) // 2, top + ih + gap, label, LABEL, LABEL_SHADOW)


def _big_cells(menu):
    """letter -> (column, row, columns, rows) of every big button (one with `art`), checked to be a full rectangle."""
    out = {}
    buttons = menu.get("buttons", {}) or {}
    for ch, b in buttons.items():
        if not isinstance(b, dict) or not (b.get("art") or b.get("label")):
            continue
        # `with: "P"`: other letters inside the tile (a real item shown in it, e.g. the player's head)
        cells = [(r, c) for r, row in enumerate(menu["layout"]) for c, x in enumerate(row) if x == ch or x in b.get("with", "")]
        if not cells:
            continue
        r0, r1 = min(r for r, _ in cells), max(r for r, _ in cells)
        c0, c1 = min(c for _, c in cells), max(c for _, c in cells)
        if len(cells) != (r1 - r0 + 1) * (c1 - c0 + 1):
            raise ValueError(f"big button {ch} is not a rectangle of cells")
        out[ch] = (c0, r0, c1 - c0 + 1, r1 - r0 + 1)
    return out


def background(menu, art=None):
    """menu: rows, layout, list, buttons (keys), inset (chars), graph, flow. art: icon id -> 16 x 16 image, for
    big buttons."""
    if menu.get("theme") == "hub":
        return hub_background(menu, art)
    if menu.get("theme") == "hanok":
        import hub_hanok
        return hub_hanok.background(menu, art)
    if menu.get("theme") == "jewel":
        import accessory_theme      # the 장신구 screens' 자개 보석함 (tools/assetgen/accessory_theme.py)
        return accessory_theme.background(menu, art)
    if menu.get("theme") == "circuit":
        import circuit_art          # the 서킷's waiting room (tools/assetgen/circuit_art.py)
        return circuit_art.lobby_background(menu, art)
    rows = menu["rows"]
    H = height(rows)
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    _rect(im, 0, 0, W, H, EDGE_OUT)
    _bevel(im, 1, 1, W - 2, H - 2, EDGE_LIGHT, EDGE_DARK, fill=PANEL)
    for (x, y) in ((0, 0), (1, 0), (0, 1)):
        for (fx, fy) in ((x, y), (W - 1 - x, y), (x, H - 1 - y), (W - 1 - x, H - 1 - y)):
            im.putpixel((fx, fy), (0, 0, 0, 0))
    # the title line: gold under the first words, dim gold on
    _rect(im, 7, 15, W - 7, 16, GOLD_DIM)
    _rect(im, 7, 15, 40, 16, GOLD)
    if menu.get("graph"):
        # the stock chart's screen (economy StockGraph: rows y 20..51, candles x 12..139, the price tag
        # right of x 141): a crisp frame, dotted guides at the top, quarters and bottom of the chart, and a
        # dotted line before the price column
        _bevel(im, 7, 17, W - 14, 36, SCREEN_FRAME_DARK, SCREEN_FRAME_LIGHT, fill=SCREEN)
        for gy in (20, 28, 36, 43, 51):
            for gx in range(9, W - 9, 2):
                im.putpixel((gx, gy), GRID + (255,))
        for gy in range(19, 52, 2):
            im.putpixel((141, gy), GRID + (255,))
    lst = menu.get("list")
    buttons = set(menu.get("buttons", {}) or {})
    inset = set(menu.get("inset", "") or "")
    tabs = set(menu.get("tabs", "") or "")
    secret = set(menu.get("secret", "") or "")   # buttons only some players get: no cell drawn
    layout = menu["layout"]
    dial = menu.get("wheel") or ""
    if dial:
        where = {ch: (r, c) for r, row in enumerate(layout) for c, ch in enumerate(row)}
        wheel(im, [where[ch] for ch in dial if ch in where])
    big = _big_cells(menu)
    for ch, (c0, r0, cw, rh) in big.items():
        b = menu["buttons"][ch]
        icon = art(b["art"]) if art and b.get("art") else None
        big_tile(im, 7 + 18 * c0, 17 + 18 * r0, 18 * cw, 18 * rh, icon, b.get("label", ""))
    covered = set(big) | {x for ch in big for x in (menu["buttons"][ch].get("with", "") or "")}
    for r, row in enumerate(layout):
        for c, ch in enumerate(row):
            x, y = 7 + 18 * c, 17 + 18 * r
            if ch in secret or ch in dial or ch in covered:
                continue
            if (lst and ch == lst) or ch in inset:
                slot(im, x, y)
            elif ch in buttons and ch in tabs:
                tab(im, x, y)
            elif ch in buttons:
                tile(im, x, y)
            elif ch == "." and menu.get("flow") and 0 < c < 8 and row[c - 1] != "." and row[c + 1] != "." \
                    and (row[c - 1] in inset or row[c + 1] in inset or row[c - 1] == lst or row[c + 1] == lst):
                _arrow(im, x, y)
    # where the player's inventory starts: a groove in place of the label
    g = 18 * rows + 23
    _rect(im, 7, g, W - 7, g + 1, EDGE_DARK)
    _rect(im, 7, g + 1, W - 7, g + 2, SLOT_BOTTOM)
    inv = 30 + 18 * rows   # the frame, 1 px above the client's item position 31 + 18 rows
    for r in range(3):
        for c in range(9):
            slot(im, 7 + 18 * c, inv + 18 * r)
    for c in range(9):
        slot(im, 7 + 18 * c, inv + 58)
    return im


def lang_files(assets_root):
    """Blank the client's "Inventory" label above the player's slots (every container), so the dark
    menus need no light strip behind it. Language files merge key by key with the game's own."""
    import json
    folder = assets_root / "minecraft" / "lang"
    folder.mkdir(parents=True, exist_ok=True)
    for lang in ("ko_kr", "en_us", "en_gb"):
        (folder / f"{lang}.json").write_text(json.dumps({"container.inventory": ""}, ensure_ascii=False) + "\n")


# ------------------------------------------------------------------------------------------------
# The hub's own look (`theme: hub`): a deep gradient panel, cards instead of tiles, and a dock.
#   - a button with `art` covering 4+ rows is a tall card: its top row holds the name (Galmuri9) above the card,
#     the card has the button's `accent` colour (#rrggbb) washed over it, a glow behind the icon (twice its size)
#     and `sub` (Galmuri7) at the bottom
#   - a smaller button with `art` is a small card: the icon and the name (Galmuri7) under it
#   - `dock: <row>`: that row is one long bar of sockets; each button there has its `art` drawn in its socket,
#     a button without `art` (the player's head) sits in a gold-ringed socket
# ------------------------------------------------------------------------------------------------

HUB_TOP = (36, 40, 54)
HUB_BOTTOM = (22, 24, 32)
HUB_EDGE = (10, 11, 15)
HUB_RIM = (64, 70, 88)
HUB_TEXT = (240, 236, 226)
HUB_SUB = (176, 180, 192)
DOCK = (16, 18, 24)
DOCK_RIM = (58, 64, 80)
DOCK_LINE = (36, 40, 50)


def _mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def _hex(s, default=(200, 200, 210)):
    s = str(s or "").lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4)) if len(s) == 6 else default


def _font_of(size):
    import galmuri
    key = f"_F{size}"
    if key not in globals():
        globals()[key] = galmuri.load(size)
    return globals()[key]


def hub_text_width(s, size=9):
    f = _font_of(size)
    return sum(f[ord(ch)][0] for ch in s) - 1 if s else 0


def hub_text(im, x, y, s, colour, size=9, shadow=(10, 11, 15)):
    f = _font_of(size)
    base = y + size
    px = im.load()
    for ox, oy, c in (((1, 1, shadow),) if shadow else ()) + ((0, 0, colour),):
        cx = x
        for ch in s:
            advance, xo, yo, bits = f[ord(ch)]
            top = base - (yo + len(bits))
            for r, row in enumerate(bits):
                for k, on in enumerate(row):
                    if on:
                        X, Y = cx + xo + k + ox, top + r + oy
                        if 0 <= X < im.width and 0 <= Y < im.height:
                            px[X, Y] = c + (255,)
            cx += advance


def _round_box(im, x0, y0, x1, y1, fill_top, fill_bottom, rim, highlight=None):
    """A box with 2 px rounded corners: a rim, a vertical gradient inside, a highlight line under the top rim."""
    px = im.load()
    h = y1 - y0
    for y in range(y0, y1):
        for x in range(x0, x1):
            dx = min(x - x0, x1 - 1 - x)
            dy = min(y - y0, y1 - 1 - y)
            if dx + dy < 2 and dx < 2 and dy < 2:
                continue                                   # the cut corner
            edge = dx == 0 or dy == 0 or (dx + dy == 2 and dx < 2 and dy < 2)
            if edge:
                px[x, y] = rim + (255,)
            elif highlight and y == y0 + 1:
                px[x, y] = highlight + (255,)
            else:
                px[x, y] = _mix(fill_top, fill_bottom, (y - y0) / max(1, h - 1)) + (255,)


def _glow(im, cx, cy, r, colour, strength):
    px = im.load()
    for y in range(int(cy - r), int(cy + r) + 1):
        for x in range(int(cx - r), int(cx + r) + 1):
            if not (0 <= x < im.width and 0 <= y < im.height):
                continue
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            if d < r:
                t = strength * (1 - d / r) ** 2
                p = px[x, y]
                px[x, y] = _mix(p[:3], colour, t) + (255,)


def _cells_of(menu, ch):
    cells = [(r, c) for r, row in enumerate(menu["layout"]) for c, x in enumerate(row) if x == ch]
    r0, r1 = min(r for r, _ in cells), max(r for r, _ in cells)
    c0, c1 = min(c for _, c in cells), max(c for _, c in cells)
    if len(cells) != (r1 - r0 + 1) * (c1 - c0 + 1):
        raise ValueError(f"card {ch} is not a rectangle of cells")
    return c0, r0, c1 - c0 + 1, r1 - r0 + 1


def hub_card(im, x, y, w, h, art, label, sub, accent, tall):
    base = (30, 33, 44)
    top, bottom = _mix(base, accent, 0.30), _mix(base, accent, 0.07)
    rim, light = _mix(base, accent, 0.62), _mix(accent, (255, 255, 255), 0.25)
    if tall:
        # the name above the card, a short accent line under it
        tw = hub_text_width(label)
        hub_text(im, x + (w - tw) // 2, y + 3, label, HUB_TEXT)
        _rect(im, x + w // 2 - 6, y + 15, x + w // 2 + 6, y + 16, accent)
        bx0, by0, bx1, by1 = x + 2, y + 19, x + w - 2, y + h - 3
        _round_box(im, bx0, by0, bx1, by1, top, bottom, rim, light)
        icon = art.resize((32, 32), Image.NEAREST) if art is not None else None
        cx, cy = (bx0 + bx1) / 2, by0 + 26
        _glow(im, cx, cy, 22, accent, 0.55)
        if icon:
            im.alpha_composite(icon, (int(cx) - 16, int(cy) - 16))
        if sub:
            sw = hub_text_width(sub, 7)
            hub_text(im, int(cx) - sw // 2, by1 - 15, sub, _mix(HUB_SUB, accent, 0.25), 7)
    else:
        bx0, by0, bx1, by1 = x + 1, y + 1, x + w - 1, y + h - 1
        _round_box(im, bx0, by0, bx1, by1, top, bottom, rim, light)
        cx = (bx0 + bx1) // 2
        _glow(im, cx, by0 + 12, 12, accent, 0.40)
        if art is not None:
            im.alpha_composite(art, (cx - 8, by0 + 4))
        if label:
            tw = hub_text_width(label, 7)
            hub_text(im, cx - tw // 2, by1 - 11, label, HUB_TEXT, 7)


def hub_background(menu, art=None):
    rows = menu["rows"]
    H = height(rows)
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    # the panel: a dark rim, a vertical gradient, a faint dot grid
    _rect(im, 0, 0, W, H, HUB_EDGE)
    px = im.load()
    for y in range(1, H - 1):
        c = _mix(HUB_TOP, HUB_BOTTOM, min(1, y / (17 + 18 * rows)))
        for x in range(1, W - 1):
            px[x, y] = c + (255,)
    for y in range(20, 17 + 18 * rows, 6):
        for x in range(10, W - 10, 6):
            px[x, y] = _mix(px[x, y][:3], (255, 255, 255), 0.04) + (255,)
    _rect(im, 1, 1, W - 1, 2, HUB_RIM)
    for (x, y) in ((0, 0), (1, 0), (0, 1)):
        for (fx, fy) in ((x, y), (W - 1 - x, y), (x, H - 1 - y), (W - 1 - x, H - 1 - y)):
            im.putpixel((fx, fy), (0, 0, 0, 0))
    # the title line: gold fading out to the right
    for x in range(7, W - 7):
        t = (x - 7) / (W - 14)
        px[x, 15] = _mix(GOLD, HUB_TOP, min(1, t * 1.6)) + (255,)
    buttons = menu.get("buttons", {}) or {}
    dock = menu.get("dock")
    done = set()
    for ch, b in buttons.items():
        if not isinstance(b, dict) or not b.get("art") or ch in done:
            continue
        c0, r0, cw, rh = _cells_of(menu, ch)
        if dock is not None and r0 == dock:
            continue
        done.add(ch)
        icon = art(b["art"]) if art else None
        hub_card(im, 7 + 18 * c0, 17 + 18 * r0, 18 * cw, 18 * rh, icon, b.get("label", ""), b.get("sub", ""),
                 _hex(b.get("accent")), rh >= 4)
    if dock is not None:
        y = 17 + 18 * dock
        row = menu["layout"][dock]
        used = [c for c, ch in enumerate(row) if ch != "."]
        x0, x1 = 7 + 18 * min(used), 7 + 18 * (max(used) + 1)
        _round_box(im, x0, y, x1, y + 18, DOCK, DOCK, DOCK_RIM)
        for c in used[1:]:
            if row[c - 1] != ".":
                _rect(im, 7 + 18 * c, y + 3, 7 + 18 * c + 1, y + 15, DOCK_LINE)
        for c in used:
            b = buttons.get(row[c]) or {}
            cx = 7 + 18 * c
            if b.get("art"):
                if art:
                    im.alpha_composite(art(b["art"]), (cx + 1, y + 1))
            else:
                # the player's head: a gold ring round its socket
                _round_box(im, cx - 1, y - 1, cx + 19, y + 19, DOCK, DOCK, GOLD)
    # where the player's inventory starts, then its slots (as every menu)
    g = 18 * rows + 23
    _rect(im, 7, g, W - 7, g + 1, EDGE_DARK)
    _rect(im, 7, g + 1, W - 7, g + 2, SLOT_BOTTOM)
    inv = 30 + 18 * rows
    for r in range(3):
        for c in range(9):
            slot(im, 7 + 18 * c, inv + 18 * r)
    for c in range(9):
        slot(im, 7 + 18 * c, inv + 58)
    return im
