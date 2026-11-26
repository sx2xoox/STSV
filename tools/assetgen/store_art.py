"""
편의점 알바 (docs/plans/편의점_기획안.md §15): the flat pictures the store's models wear at more than 16 px a block
(models/store.py puts them on as plates), and the vest's armour layer.

  button(key)              160 x 80: a POS key (a 12 x 6 px plate, 13 1/3 texels a px): a dark bezel, a green key with
                           its word in white (or a white key with the word in green for the "no / not now" keys),
                           Neo둥근모 bold at 2x, a lit top edge and a shadowed bottom one
  change_button(n)         160 x 80: the change keys: a white key, a stripe in the note's (or the coin's) colour down
                           its left, the amount in green (5만원 1만원 5천원 1천원 500원 100원)
  sign_face()              320 x 64: the GS26 lightbox (80 x 16 px at 4 texels a px). An original design: a deep green
                           field, a sun-yellow disc with a white leaf, "GS" in white and "26" in sun yellow in our own
                           rounded block letters, a white panel with "편의점" and "24시간"
  header(text, w, h, ...)  a shelf / fridge header strip: the word centred in Neo둥근모 (white on green, or lit)
  note(n)                  64 x 32 (a 7 x 3.5 px banknote at 8 texels a px, in the left 56 x 28): game money, not a
                           copy of a real note: the colour of the value (1000 blue, 5000 orange, 10000 green, 50000
                           gold), a frame, a hanok roof medallion, the value in digits
  card()                   64 x 32 (a 5.5 x 3.5 px card, 8 texels a px, in the left 44 x 28): a green card, a gold
                           chip, the contactless waves, a number row
  lottery()                48 x 64 (a 6 x 8 px scratch card, 8 texels a px): a bright ticket, three silver scratch
                           panels, "즉석복권"
  vest_layer()             64 x 32: the humanoid armour layer of store_vest (equipment economy:store_vest): the body
                           only (a vest: the arms stay bare), a V neck open to the shirt under it, a zip, a white
                           name tag on the chest, a white GS26 band on the back

    python3 tools/assetgen/store_art.py out_dir     # writes the pictures there, 4x, to look at
"""

from PIL import Image

from stsv_assets.palette import ramp

import gamble_art as GA


def _c(name, i):
    r, g, b = ramp(name, i)[:3]
    return (r, g, b, 255)


GREEN = "sign_green"


# ------------------------------------------------------------------------------------------------
# POS keys
# ------------------------------------------------------------------------------------------------

BUTTON_WORDS = {"card": "카드", "cash": "현금", "give": "건네기", "bag": "봉투", "nobag": "봉투없음", "warm": "데우기",
                "yes": "네", "no": "아니요", "wait": "잠시만요", "id": "신분증", "refuse": "거절", "sell": "판매",
                "wake": "깨우기", "point": "가리키기"}
# white keys with green words: the "no / not now" answers
LIGHT_KEYS = {"nobag", "no", "wait", "refuse"}

CHANGE = (50000, 10000, 5000, 1000, 500, 100)
CHANGE_WORDS = {50000: "5만원", 10000: "1만원", 5000: "5천원", 1000: "1천원", 500: "500원", 100: "100원"}
# the note / coin colours (store_cash_* and store_coin_* wear the same)
MONEY = {50000: ("gold", 2), 10000: ("bottle_green", 2), 5000: ("fox", 3), 1000: ("sapphire", 2),
         500: ("fish_silver", 3), 100: ("fish_silver", 2)}


def _key(W, H, face, face_hi, face_lo):
    """A rounded POS key: a dark bezel 4 texels wide, the key face with a lit top edge and a shadowed bottom."""
    im = Image.new("RGBA", (W, H), _c("iron", 1))
    px = im.load()
    B = 4
    for y in range(H):
        for x in range(W):
            edge = min(x, y, W - 1 - x, H - 1 - y)
            corner = min(x, W - 1 - x) < B + 3 and min(y, H - 1 - y) < B + 3 and \
                (min(x, W - 1 - x) - B) + (min(y, H - 1 - y) - B) < 3
            if edge < B or corner:
                px[x, y] = _c("iron", 1) if edge >= 1 else _c("iron", 0)
            elif y < B + 3:
                px[x, y] = face_hi
            elif y >= H - B - 4:
                px[x, y] = face_lo
            else:
                px[x, y] = face
    return im


# digits for the words (Neo둥근모 here holds only Hangul): 4 x 7, drawn 2x so they stand as tall as a syllable (14)
DIG7 = {
    "0": [".##.", "#..#", "#..#", "#..#", "#..#", "#..#", ".##."], "1": [".#..", "##..", ".#..", ".#..", ".#..", ".#..", "###."],
    "2": [".##.", "#..#", "...#", "..#.", ".#..", "#...", "####"], "3": ["###.", "...#", "...#", ".##.", "...#", "...#", "###."],
    "4": ["..#.", ".##.", "#.#.", "#.#.", "####", "..#.", "..#."], "5": ["####", "#...", "###.", "...#", "...#", "#..#", ".##."],
    "6": [".##.", "#...", "###.", "#..#", "#..#", "#..#", ".##."], "7": ["####", "...#", "..#.", "..#.", ".#..", ".#..", ".#.."],
    "8": [".##.", "#..#", "#..#", ".##.", "#..#", "#..#", ".##."], "9": [".##.", "#..#", "#..#", "#..#", ".###", "...#", ".##."],
}


def mask(text):
    """word_mask with digits: the Hangul from Neo둥근모 (bold), the digits from DIG7 at 2x, bottoms aligned."""
    parts = []
    run = ""
    for ch in text + "\0":
        if ch == " ":
            if run:
                parts.append(GA.word_mask(run))
                run = ""
            parts.append([[False] * 3 for _ in range(14)])
            continue
        if ch in DIG7 or ch == "\0":
            if run:
                parts.append(GA.word_mask(run))
                run = ""
            if ch in DIG7:
                parts.append(GA.scale_mask([[c == "#" for c in r] for r in DIG7[ch]], 2))
        else:
            run += ch
    H = max(len(p) for p in parts)
    rows = [[] for _ in range(H)]
    for k, p in enumerate(parts):
        off = H - len(p)
        for y in range(H):
            if k:
                rows[y] += [False, False]
            rows[y] += p[y - off] if y >= off else [False] * len(p[0])
    return rows


def _word(px, text, W, H, col, shadow, x0=0, scale=2):
    m = GA.scale_mask(mask(text), scale)
    mh, mw = len(m), len(m[0])
    if mw > W - x0 - 12:
        m = mask(text)
        mh, mw = len(m), len(m[0])
    ox = x0 + (W - x0 - mw) // 2
    oy = (H - mh) // 2
    for y, row in enumerate(m):
        for x, v in enumerate(row):
            if v:
                if shadow and not (y + 2 < mh and x + 2 < mw and m[y + 2][x + 2]):
                    px[ox + x + 2, oy + y + 2] = shadow if px[ox + x + 2, oy + y + 2] != col else col
    for y, row in enumerate(m):
        for x, v in enumerate(row):
            if v:
                px[ox + x, oy + y] = col


def button(key):
    W, H = 160, 80
    if key in LIGHT_KEYS:
        im = _key(W, H, _c("car_white", 3), _c("snow", 2), _c("car_white", 1))
        _word(im.load(), BUTTON_WORDS[key], W, H, _c(GREEN, 1), _c("car_white", 1))
    else:
        im = _key(W, H, _c(GREEN, 2), _c(GREEN, 3), _c(GREEN, 1))
        _word(im.load(), BUTTON_WORDS[key], W, H, _c("snow", 2), _c(GREEN, 0))
    return im


def change_button(n):
    W, H = 160, 80
    im = _key(W, H, _c("car_white", 3), _c("snow", 2), _c("car_white", 1))
    px = im.load()
    r, s = MONEY[n]
    for y in range(8, H - 8):
        for x in range(8, 26):
            px[x, y] = _c(r, s + (1 if y < 14 else -1 if y > H - 15 else 0))
    if n < 1000:                       # a coin: a round disc on the stripe
        for y in range(H):
            for x in range(W):
                d = ((x - 17) ** 2 + (y - 40) ** 2) ** 0.5
                if d < 7:
                    px[x, y] = _c("fish_silver", 4 if d < 3 else 3)
                elif d < 8.5:
                    px[x, y] = _c("fish_silver", 1)
    _word(px, CHANGE_WORDS[n], W, H, _c(GREEN, 1), _c("car_white", 1), x0=26)
    return im


def plate_back():
    """16 x 16: a key's sides and back: dark grey plastic."""
    im = Image.new("RGBA", (16, 16), _c("iron", 2))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if (x * 5 + y * 3) % 13 == 0:
                px[x, y] = _c("iron", 1)
    return im


# ------------------------------------------------------------------------------------------------
# The GS26 lightbox
# ------------------------------------------------------------------------------------------------

# our own rounded block letters, 6 x 9 (scaled 4x on the sign)
LETTERS = {
    "G": [".####.", "##..##", "##....", "##....", "##.###", "##..##", "##..##", "##..##", ".####."],
    "S": [".####.", "##..##", "##....", ".###..", "...##.", "....##", "....##", "##..##", ".####."],
    "2": [".####.", "##..##", "....##", "...##.", "..##..", ".##...", "##....", "##....", "######"],
    "6": ["..###.", ".##...", "##....", "#####.", "##..##", "##..##", "##..##", "##..##", ".####."],
}


def sign_face():
    W, H = 320, 64
    im = Image.new("RGBA", (W, H))
    px = im.load()
    split = 214                                    # green field | white panel
    for y in range(H):
        for x in range(W):
            if min(x, y, W - 1 - x, H - 1 - y) < 3:
                px[x, y] = _c("steel", 4) if y < H // 2 else _c("steel", 3)
            elif x < split - (y - 3) // 6:         # a slanted seam between the field and the panel
                # the lit field: brighter towards the middle, as a lightbox is
                t = abs(y - H / 2) / (H / 2)
                px[x, y] = _c(GREEN, 3) if t < 0.55 else _c(GREEN, 2)
            elif x < split + 4 - (y - 3) // 6:
                px[x, y] = _c("corn", 3)           # a sun-yellow stripe on the seam
            else:
                px[x, y] = _c("snow", 2) if (x + y) % 17 else _c("snow", 1)
    # the emblem: a sun-yellow disc with a white leaf on it
    cx, cy, R = 30, 32, 21
    for y in range(H):
        for x in range(W):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d < R:
                px[x, y] = _c("corn", 2) if d < R - 3 else _c("corn", 1)
                # the leaf: an almond tilted up to the right, lit on its upper half, its vein
                u, v = (x - cx) * 0.75 - (y - cy) * 0.66, (x - cx) * 0.66 + (y - cy) * 0.75
                if (abs(u) / 14) ** 1.6 + (abs(v) / 8) ** 1.6 < 1 - 0.25 * (u / 14) ** 2:
                    px[x, y] = _c(GREEN, 3) if v < 0 else _c(GREEN, 2)
                    if abs(v) < 0.8 and -12 < u < 12:
                        px[x, y] = _c("snow", 2)
            elif d < R + 2:
                px[x, y] = _c("snow", 2)
    # the stalk of the leaf
    for k in range(5):
        px[cx - 10 - k, cy + 9 + k] = _c(GREEN, 1)
        px[cx - 9 - k, cy + 9 + k] = _c(GREEN, 1)
    # GS (white) 26 (sun yellow), 4x, with a dark green drop shadow
    S = 4
    x = 62
    for ch in "GS26":
        rows = LETTERS[ch]
        col = _c("snow", 2) if ch in "GS" else _c("corn", 2)
        for j, row in enumerate(rows):
            for i, c in enumerate(row):
                if c != "#":
                    continue
                for a in range(S):
                    for b in range(S):
                        X, Y = x + i * S + a, 14 + j * S + b
                        if px[X + 3, Y + 3] not in (_c("snow", 2), _c("corn", 2)):
                            px[X + 3, Y + 3] = _c(GREEN, 0)
        for j, row in enumerate(rows):
            for i, c in enumerate(row):
                if c != "#":
                    continue
                for a in range(S):
                    for b in range(S):
                        px[x + i * S + a, 14 + j * S + b] = col
        x += 6 * S + (5 if ch == "S" else 3)
    # the panel: 편의점 in green, 24시간 in orange under it
    m = GA.scale_mask(GA.word_mask("편의점"), 2)
    ox, oy = 226 + (W - 230 - len(m[0])) // 2, 10
    for j, row in enumerate(m):
        for i, v in enumerate(row):
            if v:
                px[ox + i, oy + j] = _c(GREEN, 1)
    digits = {"2": [".##.", "#..#", "..#.", ".#..", "####"], "4": ["#.#.", "#.#.", "####", "..#.", "..#."]}
    small = GA.word_mask("시간")
    # "24" in a little block font, then 시간
    ox, oy = 236, 44
    for k, ch in enumerate("24"):
        for j, row in enumerate(digits[ch]):
            for i, c in enumerate(row):
                if c == "#":
                    for a in range(2):
                        for b in range(2):
                            px[ox + k * 10 + i * 2 + a, oy - 2 + j * 2 + b] = _c("fox", 2)
    for j, row in enumerate(small):
        for i, v in enumerate(row):
            if v and oy - 4 + j < H - 2:
                px[ox + 22 + i, oy - 4 + j] = _c("fox", 2)
    return im


def sign_back():
    im = Image.new("RGBA", (16, 16), _c("steel", 3))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if y % 4 == 0:
                px[x, y] = _c("steel", 2)
    return im


def header(text, w, h, bg=(GREEN, 2), fg=("snow", 2), edge=(GREEN, 1), scale=1):
    """A header strip w x h texels: the word centred, a darker line top and bottom."""
    im = Image.new("RGBA", (w, h), _c(*bg))
    px = im.load()
    for x in range(w):
        for y in (0, 1, h - 2, h - 1):
            px[x, y] = _c(*edge)
    m = mask(text)
    if scale > 1 and len(m[0]) * scale > w - 4:
        scale = 1
    if scale > 1:
        m = GA.scale_mask(m, scale)
    ox, oy = (w - len(m[0])) // 2, (h - len(m)) // 2
    for j, row in enumerate(m):
        for i, v in enumerate(row):
            if v and 0 <= ox + i < w and 0 <= oy + j < h:
                px[ox + i, oy + j] = _c(*fg)
    return im


# ------------------------------------------------------------------------------------------------
# Money, the card, the scratch card
# ------------------------------------------------------------------------------------------------

DIGITS = {
    "0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"],
    "5": ["###", "#..", "###", "..#", "###"], "2": ["###", "..#", "###", "#..", "###"],
}


def _digits(px, text, x, y, col, k=1):
    for n, ch in enumerate(text):
        for j, row in enumerate(DIGITS[ch]):
            for i, c in enumerate(row):
                if c == "#":
                    for a in range(k):
                        for b in range(k):
                            px[x + n * 4 * k + i * k + a, y + j * k + b] = col


def note(n):
    """A banknote in the left 56 x 28 of a 64 x 32 picture (the right part unused)."""
    r, s = MONEY[n]
    im = Image.new("RGBA", (64, 32), _c(r, s))
    px = im.load()
    W, H = 56, 28
    for y in range(H):
        for x in range(W):
            e = min(x, y, W - 1 - x, H - 1 - y)
            if e < 2:
                px[x, y] = _c(r, s - 1)
            elif e == 2:
                px[x, y] = _c(r, s + 1)
            elif (x * 3 + y * 5) % 9 == 0:
                px[x, y] = _c(r, s + 1)            # the fine guilloche pattern
    # the watermark window (paper) on the right, a hanok roof in it
    for y in range(6, 22):
        for x in range(38, 51):
            d = ((x - 44.5) / 6.5) ** 2 + ((y - 14) / 8) ** 2
            if d < 1:
                px[x, y] = _c("paper", 2)
                if 11 <= y <= 13 and abs(x - 44.5) < 6 - (y - 11):
                    px[x, y] = _c(r, s - 1)        # the roof, its eaves turned up
                if y == 14 and abs(x - 44.5) < 6:
                    px[x, y] = _c(r, s - 1)
                if 15 <= y <= 18 and abs(x - 44.5) < 3.5 and (x % 2 == 0):
                    px[x, y] = _c(r, s - 1)        # the posts
    _digits(px, str(n), 5, 5, _c(r, max(0, s - 2)), k=1 if n >= 10000 else 2)
    _digits(px, str(n), 5, 20, _c(r, s - 1), k=1)
    return im


def card():
    im = Image.new("RGBA", (64, 32), _c(GREEN, 1))
    px = im.load()
    W, H = 44, 28
    for y in range(H):
        for x in range(W):
            corner = min(x, W - 1 - x) + min(y, H - 1 - y) < 3
            if corner:
                px[x, y] = _c(GREEN, 0)
            elif x + y * 1.4 > 40:
                px[x, y] = _c(GREEN, 3) if (x + y) % 11 else _c(GREEN, 2)
            else:
                px[x, y] = _c(GREEN, 2)
    for y in range(9, 16):                         # the chip
        for x in range(5, 13):
            px[x, y] = _c("gold", 3) if (x in (8, 9) or y == 12) else _c("gold", 2)
    for k, rr in enumerate((3, 5, 7)):             # contactless waves
        for y in range(8, 18):
            x = 17 + rr - int(abs(y - 12.5) ** 2 / (rr + 2))
            if abs(y - 12.5) < rr:
                px[x, y] = _c("snow", 1)
    for g in range(4):                             # the number row
        for i in range(4):
            px[5 + g * 9 + i * 2, 21] = _c("snow", 2)
    for x in range(30, 40):                        # a little logo bar
        px[x, 5] = px[x, 6] = _c("corn", 3)
    return im


def lottery():
    W, H = 48, 64
    im = Image.new("RGBA", (W, H), _c("petal_yellow", 2))
    px = im.load()
    for y in range(H):
        for x in range(W):
            e = min(x, y, W - 1 - x, H - 1 - y)
            if e < 2:
                px[x, y] = _c("fabric_red", 2)
            elif y < 18:
                px[x, y] = _c("fabric_red", 3) if (x // 4 + y // 4) % 2 else _c("salmon", 3)
    m = GA.word_mask("복권", spacing=1)
    ox = (W - len(m[0])) // 2
    for j, row in enumerate(m):
        for i, v in enumerate(row):
            if v and 0 <= ox + i < W:
                px[ox + i, 2 + j] = _c("snow", 2)
    for k in range(3):                             # the silver scratch panels, one scratched off
        y0 = 22 + k * 13
        for y in range(y0, y0 + 10):
            for x in range(6, W - 6):
                if k == 0 and x < 26:
                    px[x, y] = _c("paper", 2)
                    if y0 + 3 <= y <= y0 + 6 and x in (10, 11, 15, 16, 20, 21):
                        px[x, y] = _c("fabric_red", 2)       # the uncovered symbols
                else:
                    px[x, y] = _c("fish_silver", 4) if (x + y) % 5 else _c("fish_silver", 3)
    return im


# ------------------------------------------------------------------------------------------------
# The vest's armour layer
# ------------------------------------------------------------------------------------------------

def vest_layer():
    """64 x 32 humanoid layer: only the body box (16, 16, 8 x 12 x 4) is painted."""
    im = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = im.load()
    G = lambda s: _c(GREEN, s)

    def fill(x0, y0, w, h, fn):
        for y in range(h):
            for x in range(w):
                c = fn(x, y)
                if c:
                    px[x0 + x, y0 + y] = c
    # top (shoulders): the straps over the shoulders, the neck hole in the middle
    fill(20, 16, 8, 4, lambda x, y: G(3) if x < 2 or x > 5 else None)
    fill(28, 16, 8, 4, lambda x, y: G(1) if x < 2 or x > 5 else None)

    # front: a V neck (the shirt shows), a zip, the name tag on the wearer's left chest (the picture's right)
    def front(x, y):
        if y < 6 and 2 <= x <= 5 and abs(x - 3.5) < 2.2 - y * 0.35:
            return None
        if y == 11:
            return G(0)                            # the hem
        if x in (3, 4) and y >= 4:
            return _c("steel", 4) if (y % 2) else _c("steel", 3)   # the zip
        if x in (5, 6) and y in (4, 5):
            return _c("snow", 2) if y == 4 else _c("snow", 1)      # the name tag
        if x == 0 or x == 7:
            return G(1)
        if y == 8:
            return _c("corn", 3) if x not in (3, 4) else None      # a sun-yellow band (the brand's)
        return G(2)
    fill(20, 20, 8, 12, front)
    # the sides and back
    fill(16, 20, 4, 12, lambda x, y: G(0) if y == 11 else (_c("corn", 3) if y == 8 else G(1)))
    fill(28, 20, 4, 12, lambda x, y: G(0) if y == 11 else (_c("corn", 3) if y == 8 else G(1)))

    def back(x, y):
        if y == 11:
            return G(0)
        if 2 <= y <= 4:
            return _c("snow", 2) if not (y == 3 and x % 2) else G(3)   # a white band, "GS26" in it as dots
        if y == 8:
            return _c("corn", 3)
        return G(2)
    fill(32, 20, 8, 12, back)
    # bottom of the body box
    fill(28, 16, 8, 4, lambda x, y: px[28 + x, 16 + y] if px[28 + x, 16 + y][3] else None)
    return im


def main(out):
    from pathlib import Path
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    pics = {f"btn_{k}": button(k) for k in BUTTON_WORDS}
    pics.update({f"change_{n}": change_button(n) for n in CHANGE})
    pics.update({f"note_{n}": note(n) for n in (1000, 5000, 10000, 50000)})
    pics.update(sign=sign_face(), card=card(), lottery=lottery(), vest=vest_layer())
    for k, im in pics.items():
        im.resize((im.width * 4, im.height * 4), Image.NEAREST).save(out / f"{k}.png")


if __name__ == "__main__":
    import sys
    main(sys.argv[1])
