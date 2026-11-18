"""
The 장신구 screens' own look (`theme: jewel` in a menu's yml): an open 자개 보석함, a black-lacquer jewellery box
inlaid with mother-of-pearl. The lid stands up behind the screen's top with the name board (현판 글자 in Sunflower),
the tray is red velvet with a cushion for every accessory slot (its name written under it), the buttons sit in round
brass settings, and the player's inventory is the box's lacquered lower drawer.

Drawn at 2 texels a screen pixel past the panel (menu `frame: {left: 12, right: 12, top: 16, scale: 2}`), like the
hanok hub (tools/assetgen/hub_hanok.py), whose Sunflower writer it borrows.

    plaque:       the words on the lid's board (the title line itself stays empty)
    slot-labels:  a name under each list cell, in order
    showcase:     true = the list's one cell is a gold setting on a pedestal (the workshop's accessory)
    every other button letter gets a brass setting (its icon is the button's own item, so it can change)
"""

from PIL import Image

from stsv_assets import menu_theme as T
import hub_hanok as H

LEFT, RIGHT, TOP, SCALE = 12, 12, 16, 2

INK = (10, 6, 8)
LACQ, LACQ_L, LACQ_D = (26, 16, 20), (46, 30, 36), (16, 9, 12)
VELVET, VELVET_L, VELVET_D = (92, 18, 34), (124, 34, 52), (58, 10, 22)
GOLD, GOLD_L, GOLD_D = H.GOLD, H.GOLD_L, H.GOLD_D
PEARL = [(206, 232, 238), (238, 206, 228), (196, 236, 214), (244, 238, 216), (182, 204, 242), (230, 222, 246)]
TEXT, TEXT_EDGE = (255, 236, 196), (30, 8, 10)


def size(rows):
    return (T.W + LEFT + RIGHT, T.height(rows) + TOP)


def _r(im, x0, y0, x1, y1, c):
    H._rect(im, x0, y0, x1, y1, c)


def _px(im, x, y, c):
    if 0 <= x < im.width and 0 <= y < im.height:
        im.putpixel((x, y), tuple(c[:3]) + (255,))


def _pearl_line(im, x0, x1, y, seed=0):
    """A row of mother-of-pearl chips: little lozenges in shifting pastel colours."""
    for k, x in enumerate(range(x0, x1 + 1, 3)):
        c = PEARL[(k + seed) % len(PEARL)]
        _px(im, x, y, c)
        if k % 2 == 0:
            _px(im, x + 1, y, tuple(min(255, v + 10) for v in c))


def _pearl_col(im, x, y0, y1, seed=0):
    for k, y in enumerate(range(y0, y1 + 1, 3)):
        _px(im, x, y, PEARL[(k + seed) % len(PEARL)])


def _flower(im, cx, cy, seed=0):
    """A small mother-of-pearl 국화 (five chips round a gold dot)."""
    for k, (dx, dy) in enumerate(((0, -2), (2, -1), (1, 2), (-1, 2), (-2, -1))):
        c = PEARL[(k + seed) % len(PEARL)]
        _px(im, cx + dx, cy + dy, c)
    _px(im, cx, cy, GOLD_L)


def _bracket(im, x, y, sx, sy):
    """A brass corner fitting (장석): an L of gold with a rivet."""
    for k in range(6):
        _px(im, x + sx * k, y, GOLD if k < 5 else GOLD_D)
        _px(im, x, y + sy * k, GOLD if k < 5 else GOLD_D)
        _px(im, x + sx * k, y + sy, GOLD_D)
        _px(im, x + sx, y + sy * k, GOLD_D)
    _px(im, x + sx * 2, y + sy * 2, GOLD_L)


def _cushion(im, x, y):
    """A velvet cushion in a thin gold rim, for one 18 x 18 slot (a little puffed: light centre, dark edge)."""
    _r(im, x, y, x + 17, y + 17, GOLD_D)
    _r(im, x + 1, y + 1, x + 16, y + 16, GOLD)
    _r(im, x + 1, y + 1, x + 16, y + 1, GOLD_L)
    _r(im, x + 2, y + 2, x + 15, y + 15, VELVET_D)
    _r(im, x + 3, y + 3, x + 14, y + 14, VELVET)
    _r(im, x + 5, y + 4, x + 12, y + 8, VELVET_L)
    _r(im, x + 4, y + 13, x + 14, y + 14, VELVET_D)


def _setting(im, x, y):
    """A button's place: no rim, the icon sits right on the velvet. Only a soft round hollow (a shade darker) under
    it, so the button reads as pressed into the cloth."""
    for yy in range(y + 2, y + 16):
        for xx in range(x + 2, x + 16):
            d = ((xx - (x + 8.5)) / 7.5) ** 2 + ((yy - (y + 9.5)) / 6.5) ** 2
            if d < 1:
                c = (60, 10, 22) if d < 0.55 else (66, 12, 25)
                _px(im, xx, yy, c)


def _showcase(im, x, y):
    """The workshop's stand: a gold frame with corner flourishes and pearls round the one slot, on a little shelf."""
    _r(im, x - 7, y - 7, x + 24, y + 24, GOLD_D)
    _r(im, x - 6, y - 6, x + 23, y + 23, GOLD)
    _r(im, x - 6, y - 6, x + 23, y - 6, GOLD_L)
    _r(im, x - 6, y - 6, x - 6, y + 23, GOLD_L)
    _r(im, x - 5, y - 5, x + 22, y + 22, VELVET_D)
    _r(im, x - 4, y - 4, x + 21, y + 21, (110, 26, 44))
    for k, (px_, py_) in enumerate(((x - 3, y - 3), (x + 20, y - 3), (x - 3, y + 20), (x + 20, y + 20))):
        _px(im, px_, py_, PEARL[k])
        _px(im, px_ + (1 if k % 2 == 0 else -1), py_, PEARL[k + 1])
        _px(im, px_, py_ + (1 if k < 2 else -1), PEARL[k + 2])
    # flourishes: small gold scrolls out of each side's middle
    cx, cy = x + 8, y + 8
    for (dx, dy) in ((0, -1), (0, 1), (-1, 0), (1, 0)):
        bx, by = cx + dx * 16, cy + dy * 16
        for t in range(-3, 5):
            px_, py_ = (bx + t, by) if dy else (bx, by + t)
            _px(im, px_, py_, GOLD)
        _px(im, bx + (dy and -4 or 0) + (dx and 0), by + (dx and -4 or 0), GOLD_L)
        _px(im, bx + dx * 2, by + dy * 2, PEARL[(dx + 2 * dy) % len(PEARL)])
    # the shelf under it
    _r(im, x - 10, y + 27, x + 27, y + 28, GOLD_D)
    _r(im, x - 10, y + 27, x + 27, y + 27, GOLD)
    _cushion(im, x, y)


def _slot(im, x, y):
    """An inventory slot in the lacquered drawer: recessed, a faint gold lip at the bottom right."""
    _r(im, x, y, x + 17, y + 17, LACQ_L)
    _r(im, x, y, x + 16, y + 16, INK)
    _r(im, x + 1, y + 1, x + 16, y + 16, (34, 22, 26))
    _r(im, x + 1, y + 1, x + 16, y + 1, (22, 13, 16))
    _r(im, x + 1, y + 1, x + 1, y + 16, (22, 13, 16))
    _r(im, x + 1, y + 17, x + 17, y + 17, (92, 66, 40))
    _r(im, x + 17, y + 1, x + 17, y + 17, (92, 66, 40))


def background(menu, art=None):
    rows = menu["rows"]
    W, Hh = size(rows)
    ox, oy = LEFT, TOP
    ph = T.height(rows)
    im = Image.new("RGBA", (W, Hh), (0, 0, 0, 0))
    words = []

    # the box: lacquer body, ink rim, a lighter lip; mother-of-pearl borders
    _r(im, 1, 0, W - 2, Hh - 1, INK)
    _r(im, 2, 1, W - 3, Hh - 2, LACQ)
    _r(im, 2, 1, W - 3, 1, LACQ_L)
    _r(im, 2, 1, 2, Hh - 2, LACQ_L)
    _pearl_line(im, 6, W - 7, 4)
    _pearl_line(im, 6, W - 7, Hh - 5, 3)
    _pearl_col(im, 5, 5, Hh - 6, 1)
    _pearl_col(im, W - 6, 5, Hh - 6, 4)
    for (x, y, sx, sy) in ((2, 1, 1, 1), (W - 3, 1, -1, 1), (2, Hh - 2, 1, -1), (W - 3, Hh - 2, -1, -1)):
        _bracket(im, x, y, sx, sy)

    # the lid: the name board between two mother-of-pearl flowers, gold hinges where it meets the tray
    lid_y1 = oy + 13
    bx0, bx1 = ox + 40, ox + T.W - 41
    _r(im, bx0 - 1, 7, bx1 + 1, lid_y1 - 1, GOLD_D)
    _r(im, bx0, 8, bx1, lid_y1 - 2, GOLD)
    _r(im, bx0 + 1, 9, bx1 - 1, lid_y1 - 3, (34, 12, 16))
    _r(im, bx0 + 1, 9, bx1 - 1, 9, (60, 26, 30))
    if menu.get("plaque"):
        words.append(((bx0 + 3, 10, bx1 - 3, lid_y1 - 4), menu["plaque"], 20, H.TITLE, H.TITLE_EDGE))
    for k, fx in enumerate((ox + 16, ox + 28, ox + T.W - 29, ox + T.W - 17)):
        _flower(im, fx, (8 + lid_y1) // 2, k)
    _r(im, 8, lid_y1, W - 9, lid_y1, INK)
    for hx in (ox + 20, ox + T.W - 26):
        _r(im, hx, lid_y1 - 2, hx + 5, lid_y1 + 2, GOLD_D)
        _r(im, hx + 1, lid_y1 - 1, hx + 4, lid_y1 + 1, GOLD)

    # the velvet tray over the menu's rows, a gold line round it
    ty0, ty1 = oy + 15, oy + 17 + 18 * rows + 2
    _r(im, ox + 3, ty0, ox + T.W - 4, ty1, GOLD_D)
    _r(im, ox + 4, ty0 + 1, ox + T.W - 5, ty1 - 1, VELVET_D)
    _r(im, ox + 5, ty0 + 2, ox + T.W - 6, ty1 - 2, (74, 14, 28))
    # a faint nap: every other row a shade lighter
    for y in range(ty0 + 3, ty1 - 2, 2):
        _r(im, ox + 5, y, ox + T.W - 6, y, (80, 16, 30))

    buttons = menu.get("buttons", {}) or {}
    lst = menu.get("list") or ""
    cells = [(r, c, ch) for r, row in enumerate(menu["layout"]) for c, ch in enumerate(row) if ch != "."]
    list_cells = [(r, c) for r, c, ch in cells if ch == lst]
    for r, c, ch in cells:
        x, y = ox + 7 + 18 * c, oy + 17 + 18 * r
        if ch == lst:
            if menu.get("showcase"):
                _showcase(im, x, y)
            else:
                _cushion(im, x, y)
        else:
            _setting(im, x, y)
            b = buttons.get(ch) or {}
            if b.get("art") and art:
                pic = art(b["art"])
                im.alpha_composite(pic, (x + 1 + (16 - pic.width) // 2, y + 1 + (16 - pic.height) // 2))
    for (r, c), text in zip(list_cells, menu.get("slot-labels") or []):
        x, y = ox + 7 + 18 * c, oy + 17 + 18 * r
        words.append(((x - 8, y + 19, x + 25, y + 27), text, 16, TEXT, TEXT_EDGE))

    # the drawer: the player's inventory where the game puts it, a pearl line above it
    _pearl_line(im, ox + 6, ox + T.W - 7, ty1 + 4, 2)
    inv = oy + 30 + 18 * rows
    for r in range(3):
        for c in range(9):
            _slot(im, ox + 7 + 18 * c, inv + 18 * r)
    for c in range(9):
        _slot(im, ox + 7 + 18 * c, inv + 58)

    out = im.resize((W * SCALE, Hh * SCALE), Image.NEAREST)
    for (box, text, px, fill, edge) in words:
        x0, y0, x1, y1 = box
        H._write(out, (x0 * SCALE, y0 * SCALE, (x1 + 1) * SCALE - 1, (y1 + 1) * SCALE - 1), text, px, fill, edge)
    # tiles of at most 256 x 256 (MenuSpec.tiles); every tile keeps its last column non-empty
    cols, rows_ = -(-out.width // 256), -(-out.height // 256)
    tw, th = out.width // cols, out.height // rows_
    for r in range(rows_):
        for c in range(cols):
            x, y = (c + 1) * tw - 1, r * th
            if out.getpixel((x, y))[3] == 0:
                out.putpixel((x, y), (0, 0, 0, 1))
    return out
