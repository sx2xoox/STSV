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


def background(menu):
    """menu: rows, layout, list, buttons (keys), inset (chars), graph, flow."""
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
    for r, row in enumerate(layout):
        for c, ch in enumerate(row):
            x, y = 7 + 18 * c, 17 + 18 * r
            if ch in secret or ch in dial:
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
