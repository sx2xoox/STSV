"""화투방 (README "화투방"): the 20 섯다 cards and the card back, economy:hwatu_<month><a|b> and economy:hwatu_back. 16x16 icons
drawn with the shared palette ramps (stsv_assets.icons.Icon), shown in the 화투방 table screen (hwatu/HwatuModule.java).

Every card: a cream card with a dark red frame, the month number in the top-left corner (3x5 digits, readable in a slot) and a
simple motif of the month at the bottom (1 pine, 2 plum, 3 cherry, 4 black bush clover, 5 iris, 6 peony, 7 red bush clover,
8 moon over the hill, 9 chrysanthemum, 10 maple). The first card of a month (a) is its 광 (1, 3, 8: a red disc with a light
centre in the top-right corner) or 열끗 (a small gold diamond there); the second (b) a 띠 (a ribbon across: red 1~3,
plain red 4·5·7, blue 6·9·10), except 8월 whose second card is the 열끗 기러기 (three geese). economy_icons.ITEMS takes
these in.

    python3 tools/assetgen/hwatu_icons.py [out_dir]    # writes only these icons (and a preview sheet when out_dir is given)
"""

from stsv_assets.icons import Icon

ICONS = {}

DIGITS = {
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "1": [".#", "##", ".#", ".#", ".#"],
    "2": ["###", "..#", "###", "#..", "###"],
    "3": ["###", "..#", "###", "..#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#"],
    "5": ["###", "#..", "###", "..#", "###"],
    "6": ["###", "#..", "###", "#.#", "###"],
    "7": ["###", "..#", ".#.", ".#.", ".#."],
    "8": ["###", "#.#", "###", "#.#", "###"],
    "9": ["###", "#.#", "###", "..#", "###"],
}

GWANG = {1, 3, 8}
RED_RIBBON = {1, 2, 3, 4, 5, 7}       # 홍단 1~3, 초단 4 · 5 · 7 (plain red)
BLUE_RIBBON = {6, 9, 10}               # 청단


def frame(ic, body="paper", body_i=1):
    """The card: x 2..13, y 0..15, a dark red frame round a cream face."""
    for x in range(2, 14):
        for y in range(0, 16):
            if x in (2, 13) or y in (0, 15):
                ic.put(x, y, "crimson", 0)
            else:
                ic.put(x, y, body, body_i)
    for x in range(3, 13):                 # light from the top-left: the face's top row and left column a step lighter
        ic.put(x, 1, body, min(body_i + 1, 2))
    for y in range(1, 15):
        ic.put(3, y, body, min(body_i + 1, 2))
    return ic


def number(ic, month):
    s = str(month)
    x = 4
    for ch in s:
        rows = DIGITS[ch]
        for dy, row in enumerate(rows):
            for dx, c in enumerate(row):
                if c == "#":
                    ic.put(x + dx, 2 + dy, "iron", 1)
        x += len(rows[0]) + 1


def gwang_mark(ic):
    """광: a red disc with a light centre, top-right."""
    for x, y in [(10, 2), (11, 2), (9, 3), (10, 3), (11, 3), (12, 3), (10, 4), (11, 4), (9, 4), (12, 4), (10, 5), (11, 5)]:
        ic.put(x, y, "signal_red", 1)
    ic.put(10, 3, "lamp", 3).put(11, 4, "signal_red", 0).put(10, 4, "lamp", 2)


def yeol_mark(ic):
    """열끗: a small gold diamond, top-right."""
    for x, y in [(11, 2), (10, 3), (11, 3), (12, 3), (11, 4)]:
        ic.put(x, y, "gold", 2)
    ic.put(11, 3, "gold", 3).put(10, 3, "gold", 3)


def ribbon(ic, month):
    """띠: a ribbon hanging across the middle (y 7..8), a short tail down."""
    r = "denim" if month in BLUE_RIBBON else "fabric_red"
    for x in range(4, 12):
        ic.put(x, 7, r, 2).put(x, 8, r, 1)
    ic.put(10, 9, r, 1).put(10, 10, r, 0).put(11, 9, r, 1)


def geese(ic):
    """8월 열끗 기러기: three small dark V shapes in the sky."""
    for bx, by in [(5, 8), (8, 7), (10, 9)]:
        ic.put(bx, by, "iron", 1).put(bx + 1, by + 1, "iron", 1).put(bx + 2, by, "iron", 1)


# the month motifs: the lower half of the face (y 9..14)

def pine(ic):
    for y, (x0, x1) in zip(range(9, 14), [(7, 8), (6, 9), (5, 10), (4, 11), (6, 9)]):
        for x in range(x0, x1 + 1):
            ic.put(x, y, "leaf", 2 if x < 8 else 1)
    ic.put(7, 14, "bark", 2).put(8, 14, "bark", 1)


def plum(ic):
    ic.line((4, 14), (11, 10), "wood_dark", 1)
    for x, y in [(5, 11), (8, 10), (10, 12), (11, 9)]:
        ic.put(x, y, "pepper", 3).put(x + 1, y, "pepper", 2).put(x, y + 1, "pepper", 2)


def cherry(ic):
    for cx, cy in [(5, 11), (8, 10), (10, 12), (6, 13)]:
        for dx, dy in [(0, 0), (1, 0), (0, 1), (1, 1)]:
            ic.put(cx + dx, cy + dy, "sakura", 2 if dx == 0 else 1)
        ic.put(cx, cy, "sakura", 3)


def bush(ic, ramp):
    for x in (5, 7, 9, 11):
        for y in range(9, 14):
            if (x + y) % 2 == 0:
                ic.put(x, y, ramp, 2)
            else:
                ic.put(x, y, ramp, 1)
    ic.line((4, 9), (12, 9), "leaf_dark", 1)


def iris(ic):
    ic.put(7, 9, "petal_purple", 2).put(8, 9, "petal_purple", 1).put(6, 10, "petal_purple", 2).put(9, 10, "petal_purple", 1)
    ic.put(7, 10, "petal_purple", 0).put(8, 10, "petal_purple", 0)
    for y in range(11, 15):
        ic.put(7, y, "leaf", 2).put(5, y + (1 if y < 14 else 0), "leaf", 1).put(10, y, "leaf", 1)


def peony(ic):
    for x, y in [(6, 10), (7, 9), (8, 9), (9, 10), (6, 11), (7, 11), (8, 11), (9, 11), (7, 12), (8, 12), (7, 10), (8, 10)]:
        ic.put(x, y, "fabric_red", 2 if x < 8 else 1)
    ic.put(7, 10, "pink", 3)
    for x, y in [(5, 13), (4, 12), (10, 13), (11, 12), (6, 14), (9, 14)]:
        ic.put(x, y, "leaf", 1)


def moon(ic, gwang):
    """공산: a dark hill; the 광 card adds a big pale moon."""
    if gwang:
        for x, y in [(6, 8), (7, 8), (8, 8), (5, 9), (6, 9), (7, 9), (8, 9), (9, 9), (5, 10), (6, 10), (7, 10), (8, 10), (9, 10), (6, 11), (7, 11), (8, 11)]:
            ic.put(x, y, "lamp", 3 if (x + y) % 3 else 2)
    for y, (x0, x1) in zip(range(11, 15), [(8, 12), (6, 12), (4, 12), (3, 12)]):
        for x in range(x0, x1 + 1):
            ic.put(x, y, "iron", 2 if y == 11 else 1)


def chrysanthemum(ic):
    for x, y in [(7, 9), (8, 9), (6, 10), (9, 10), (6, 11), (9, 11), (7, 12), (8, 12), (7, 10), (8, 10), (7, 11), (8, 11)]:
        ic.put(x, y, "petal_yellow", 2 if (x + y) % 2 else 1)
    ic.put(7, 10, "fox", 2).put(8, 11, "fox", 1)
    ic.line((8, 13), (8, 14), "leaf", 1)
    ic.put(6, 13, "leaf", 2).put(10, 14, "leaf", 1)


def maple(ic):
    for cx, cy in [(5, 10), (9, 9), (8, 12)]:
        for dx, dy in [(0, 0), (1, 0), (-1, 0), (0, -1), (0, 1), (1, -1), (-1, -1)]:
            ic.put(cx + dx, cy + dy, "vermilion", 2 if dx <= 0 else 1)
    ic.put(10, 14, "wood_dark", 1).put(9, 13, "wood_dark", 1)


MOTIF = {1: pine, 2: plum, 3: cherry, 4: lambda ic: bush(ic, "aubergine"), 5: iris, 6: peony, 7: lambda ic: bush(ic, "pepper"),
         9: chrysanthemum, 10: maple}


def card(month, first):
    ic = frame(Icon())
    if month == 8:
        moon(ic, first and month in GWANG)
    else:
        MOTIF[month](ic)
    if first:
        (gwang_mark if month in GWANG else yeol_mark)(ic)
    elif month == 8:
        geese(ic)
        yeol_mark(ic)
    else:
        ribbon(ic, month)
    number(ic, month)
    return ic.image()


def back():
    """The back: a dark red card with a lighter diamond lattice."""
    ic = Icon()
    for x in range(2, 14):
        for y in range(0, 16):
            edge = x in (2, 13) or y in (0, 15)
            if edge:
                ic.put(x, y, "crimson", 0)
            elif (x + y) % 4 == 0 or (x - y) % 4 == 0:
                ic.put(x, y, "crimson", 3)
            else:
                ic.put(x, y, "crimson", 1)
    for x, y in [(7, 7), (8, 7), (7, 8), (8, 8)]:
        ic.put(x, y, "gold", 2)
    return ic.image()


for _m in range(1, 11):
    for _first in (True, False):
        ICONS[f"hwatu_{_m}{'a' if _first else 'b'}"] = (lambda m=_m, f=_first: card(m, f))
ICONS["hwatu_back"] = back


def main(out=None):
    """Writes only these icons into the economy pack's textures (never touches the others), and a 6x preview sheet to `out`."""
    from pathlib import Path

    from PIL import Image
    tex = Path(__file__).resolve().parents[2] / "economy" / "content" / "economy" / "textures"
    names = list(ICONS)
    for n in names:
        ICONS[n]().save(tex / f"{n}.png")
    if out:
        out = Path(out)
        out.mkdir(parents=True, exist_ok=True)
        s, cols = 6, 7
        rows = (len(names) + cols - 1) // cols
        im = Image.new("RGBA", (cols * 18 * s, rows * 18 * s), (54, 54, 62, 255))
        for k, n in enumerate(names):
            im.alpha_composite(ICONS[n]().resize((16 * s, 16 * s), Image.NEAREST), ((k % cols) * 18 * s + s, (k // cols) * 18 * s + s))
        im.save(out / "hwatu_cards.png")
    return names


if __name__ == "__main__":
    import sys
    print(main(sys.argv[1] if len(sys.argv) > 1 else None))
