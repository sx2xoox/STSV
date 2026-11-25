"""
도박장 (docs/plans/도박장_기획.md, economy README "도박장"): the flat pictures the gambling hall's display models wear at
more than 16 px a block (models/gamble.py puts them on): the 화투 card faces and back, the die's six faces, the yellow
safety-sign buttons and the "공사중 · 관계자 외 출입금지" board.

  card_face(month, first) / card_back()   24 x 32: the 화투방 card of hwatu_icons.py (the same design: cream face, dark
                                           red frame, month number top-left, 광 disc / 열끗 diamond / 띠, the month's
                                           motif) blown up 2x with Scale2x (EPX), so its staircases round off instead
                                           of turning into blocks; the card is 6 x 8 px on the table (4 texels a px)
  card_atlas(month, first) / back_atlas()  48 x 32: face | back side by side (one texture a card model; textures are
                                           multiples of 16 wide and high so the pack keeps all its mipmap levels)
  die_atlas()                              96 x 64: the six faces, 32 x 32 each, in the order of DIE_TILES
  button(key)                              160 x 80: a yellow safety-sign plate, black frame, stencil Korean text
                                           (Neo둥근모), a black and yellow hazard strip along the bottom
  sign_face()                              192 x 96: the site entrance board

Colours come from the palette ramps (stsv_assets.palette) like every STSV texture. The words are Neo둥근모, the pack's
own 16 px Korean pixel font (neodgm.py, fonts/neodgm.txt), made bold where there is room and scaled up whole; on the
buttons every round counter gets the bridges of a spray stencil.

    python3 tools/assetgen/gamble_art.py out_dir     # writes the pictures there, 4x, to look at
"""

from pathlib import Path

from PIL import Image

from stsv_assets.palette import ramp

HERE = Path(__file__).parent


def _c(name, i):
    r, g, b = ramp(name, i)[:3]
    return (r, g, b, 255)


# ------------------------------------------------------------------------------------------------
# 화투 cards
# ------------------------------------------------------------------------------------------------

def scale2x(im):
    """EPX / Scale2x: each pixel becomes 2 x 2, a corner taking its two equal neighbours' colour, so diagonal
    staircases come out as smooth diagonals and no new colour appears."""
    w, h = im.size
    src = im.load()
    out = Image.new("RGBA", (w * 2, h * 2))
    dst = out.load()

    def p(x, y):
        return src[min(max(x, 0), w - 1), min(max(y, 0), h - 1)]

    for y in range(h):
        for x in range(w):
            P, A, B, C, D = p(x, y), p(x, y - 1), p(x + 1, y), p(x - 1, y), p(x, y + 1)
            e0 = A if (C == A and C != D and A != B) else P
            e1 = B if (A == B and A != C and B != D) else P
            e2 = C if (D == C and D != B and C != A) else P
            e3 = D if (B == D and B != A and D != C) else P
            dst[2 * x, 2 * y], dst[2 * x + 1, 2 * y], dst[2 * x, 2 * y + 1], dst[2 * x + 1, 2 * y + 1] = e0, e1, e2, e3
    return out


def _finish_card(big):
    """The 2x card: the frame (EPX keeps it 2 texels) gets the face's light from the top-left on its inner line and
    the four corners rounded with the frame's darkest red (a card's corners are cut round)."""
    px = big.load()
    w, h = big.size
    edge = _c("crimson", 0)
    deep = _c("wine", 0)
    for (x, y) in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        px[x, y] = deep
    for (x, y) in ((1, 0), (0, 1), (w - 2, 0), (w - 1, 1), (0, h - 2), (1, h - 1), (w - 1, h - 2), (w - 2, h - 1)):
        px[x, y] = edge
    return big


def card_face(month, first):
    """24 x 32: the 화투방 icon card (hwatu_icons.card), its 12 x 16 card cut out and doubled with Scale2x."""
    import hwatu_icons as H
    ic = H.card(month, first)
    return _finish_card(scale2x(ic.crop((2, 0, 14, 16))))


def card_back():
    import hwatu_icons as H
    return _finish_card(scale2x(H.back().crop((2, 0, 14, 16))))


def card_atlas(month, first):
    """48 x 32: the face (x 0..24) and the back (x 24..48) of one card."""
    out = Image.new("RGBA", (48, 32))
    out.paste(card_face(month, first), (0, 0))
    out.paste(card_back(), (24, 0))
    return out


def back_atlas():
    out = Image.new("RGBA", (48, 32))
    out.paste(card_back(), (0, 0))
    out.paste(card_back(), (24, 0))
    return out


# ------------------------------------------------------------------------------------------------
# the die
# ------------------------------------------------------------------------------------------------

# where each face's tile sits in die_atlas() (DIE_TILE x DIE_TILE texels): face number -> (column, row)
DIE_TILE = 32
DIE_TILES = {1: (0, 0), 2: (1, 0), 3: (2, 0), 4: (0, 1), 5: (1, 1), 6: (2, 1)}
# pip centres on the face (0..1 of the face): a 3 x 3 grid at 1/4, 1/2, 3/4
_PIPS = {1: [(2, 2)], 2: [(1, 1), (3, 3)], 3: [(1, 1), (2, 2), (3, 3)], 4: [(1, 1), (3, 1), (1, 3), (3, 3)],
         5: [(1, 1), (3, 1), (2, 2), (1, 3), (3, 3)], 6: [(1, 1), (1, 2), (1, 3), (3, 1), (3, 2), (3, 3)]}


def die_face(n):
    """A 32 x 32 ivory face (the die's 4 px face at 8 texels a px; its rounded edges are the model's), the outer texel
    a step darker where the face meets the bevel, round drilled pips with a shadowed top-left inside and a lit lower-
    right lip (light from the top-left); the one a big red pip, as on Korean / Chinese dice."""
    S = DIE_TILE
    im = Image.new("RGBA", (S, S))
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = _c("pearl", 1) if min(x, y, S - 1 - x, S - 1 - y) < 1 else _c("pearl", 2)
    big = n == 1
    r = 6.2 if big else 3.6
    ink, ink_lo = (_c("pepper", 2), _c("pepper", 0)) if big else (_c("iron", 1), _c("iron", 0))
    for (gx, gy) in _PIPS[n]:
        cx, cy = gx * S / 4, gy * S / 4
        for y in range(S):
            for x in range(S):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                d = (dx * dx + dy * dy) ** 0.5
                if d <= r:
                    px[x, y] = ink_lo if (dx + dy) < -r * 0.55 else ink
                elif d <= r + 1.0 and dx + dy > r * 0.8:
                    px[x, y] = _c("pearl", 2) if big else _c("snow", 2)
    return im


def die_atlas():
    out = Image.new("RGBA", (3 * DIE_TILE, 2 * DIE_TILE))
    for n, (c, r) in DIE_TILES.items():
        out.paste(die_face(n), (c * DIE_TILE, r * DIE_TILE))
    return out


# ------------------------------------------------------------------------------------------------
# words: Neo둥근모 (the pack's 16 px Korean pixel font, neodgm.py), made bold, cut like a stencil, scaled up whole
# ------------------------------------------------------------------------------------------------

_GLYPHS = None


def _glyphs():
    global _GLYPHS
    if _GLYPHS is None:
        import neodgm
        _GLYPHS = neodgm.load()
    return _GLYPHS


def word_mask(text, bold=True, spacing=1, space=5):
    """The word in Neo둥근모 as rows of bools (1 px a font pixel): each syllable's empty columns trimmed, `spacing`
    px between syllables, `space` px for a space; bold = every stroke 2 px where there is room (embolden), as signs
    are painted. Rows cover the 16 px em (cropped to the ink afterwards)."""
    g = _glyphs()
    cols = []                       # list of columns (16 + 1 bools)
    for k, ch in enumerate(text):
        if ch == " ":
            cols += [[False] * 17 for _ in range(space)]
            continue
        adv, rows = g[ord(ch)]
        rows = [r + [False] for r in rows] + [[False] * (adv + 1)]
        if bold:
            rows = embolden(rows)
        used = [x for x in range(adv + 1) if any(rows[y][x] for y in range(17))]
        if not used:
            continue
        if cols and k and text[k - 1] != " ":
            cols += [[False] * 17 for _ in range(spacing)]
        for x in range(used[0], used[-1] + 1):
            cols.append([rows[y][x] for y in range(17)])
    h = 17
    m = [[cols[x][y] for x in range(len(cols))] for y in range(h)]
    ys = [y for y in range(h) if any(m[y])]
    return m[ys[0]:ys[-1] + 1]


def embolden(m):
    """Every 1 px stroke made 2 px (right, then down) wherever that keeps a clear pixel to the next stroke, so the
    tight parts of a syllable (홀's ㅎ over ㅗ over ㄹ) do not run together."""
    h, w = len(m), len(m[0])
    out = [r[:] for r in m]

    def ink(g, x, y):
        return 0 <= x < w and 0 <= y < h and g[y][x]
    for y in range(h):
        for x in range(w):
            if m[y][x] and x + 1 < w and not any(ink(m, x + a, y + b) for (a, b) in ((1, 0), (2, 0), (2, -1), (2, 1))):
                out[y][x + 1] = True
    mid = [r[:] for r in out]
    for y in range(h):
        for x in range(w):
            if mid[y][x] and y + 1 < h and not any(ink(mid, x + a, y + b) for (a, b) in ((0, 1), (0, 2), (-1, 2), (1, 2))):
                out[y + 1][x] = True
    return out


def scale_mask(m, k):
    return [[v for v in row for _ in range(k)] for row in m for _ in range(k)]


def stencil(mask, unit=2):
    """Bridges the word like a spray stencil, which has to hold its islands: every round counter (the hole of ㅇ, ㅎ's
    ring, ㅁ) gets a bridge one texel wide (half a font pixel, so the letters stay whole) down through its ring at
    the middle column, top and bottom. `unit` = how much the word was scaled up (counters smaller than a scaled font
    pixel square are left alone)."""
    h, w = len(mask), len(mask[0])
    m = [row[:] for row in mask]
    seen = [[False] * w for _ in range(h)]
    stack = [(x, y) for x in range(w) for y in (0, h - 1)] + [(x, y) for y in range(h) for x in (0, w - 1)]
    stack = [(x, y) for (x, y) in stack if not m[y][x]]
    for (x, y) in stack:
        seen[y][x] = True
    while stack:
        x, y = stack.pop()
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < w and 0 <= ny < h and not seen[ny][nx] and not m[ny][nx]:
                seen[ny][nx] = True
                stack.append((nx, ny))
    holes = []
    for y0 in range(h):
        for x0 in range(w):
            if m[y0][x0] or seen[y0][x0]:
                continue
            comp, st = [], [(x0, y0)]
            seen[y0][x0] = True
            while st:
                a, b = st.pop()
                comp.append((a, b))
                for nx, ny in ((a + 1, b), (a - 1, b), (a, b + 1), (a, b - 1)):
                    if 0 <= nx < w and 0 <= ny < h and not seen[ny][nx] and not m[ny][nx]:
                        seen[ny][nx] = True
                        st.append((nx, ny))
            holes.append(comp)
    for comp in holes:
        xs, ys = [a for a, _ in comp], [b for _, b in comp]
        bw, bh = max(xs) - min(xs) + 1, max(ys) - min(ys) + 1
        if bw < 2 * unit or bh < 2 * unit or not (0.5 <= bw / bh <= 2.0):
            continue
        cx = (min(xs) + max(xs)) // 2
        for (start, step) in ((min(ys) - 1, -1), (max(ys) + 1, 1)):
            y = start
            while 0 <= y < h and m[y][cx]:
                m[y][cx] = False
                y += step
    return m


def _put_mask(px, mask, ox, oy, col, shadow=None):
    for y, row in enumerate(mask):
        for x, v in enumerate(row):
            if v:
                px[ox + x, oy + y] = col
                # overspray: the paint a touch heavier at the bottom-right edge of a stroke
                if shadow and (x + 1 >= len(row) or not row[x + 1]) and (y + 1 >= len(mask) or not mask[y + 1][x]):
                    px[ox + x, oy + y] = shadow


# ------------------------------------------------------------------------------------------------
# the buttons: yellow safety-sign plates
# ------------------------------------------------------------------------------------------------

BUTTON_WORDS = {"die": "다이", "check": "체크", "ping": "삥", "call": "콜", "ddadang": "따당", "half": "하프",
                "odd": "홀", "even": "짝", "roll": "굴리기", "ready": "준비", "leave": "일어나기"}


def _hazard(px, x0, y0, x1, y1, period=8, phase=0):
    """Black and yellow diagonal stripes in a box."""
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = _c("iron", 1) if ((x + (y1 - y) + phase) // (period // 2)) % 2 else _c("hazard", 1)


def _bolt(px, cx, cy):
    """A round-headed bolt, 3 x 3, lit from the top-left."""
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if abs(dx) + abs(dy) == 2:
                continue
            px[cx + dx, cy + dy] = _c("steel", 3)
    px[cx - 1, cy] = _c("steel", 4)
    px[cx, cy - 1] = _c("steel", 5)
    px[cx + 1, cy] = _c("steel", 2)
    px[cx, cy + 1] = _c("steel", 1)


def _wear(px, w, h, seed, inside, n=26):
    """Scuffs on painted steel: a few one or two texel chips a step darker (and one or two to bare metal)."""
    from stsv_assets.bake import h3
    k = 0
    for i in range(400):
        x, y = int(h3(i, 1, seed) * w), int(h3(i, 2, seed) * h)
        if not inside(x, y):
            continue
        px[x, y] = _c("hazard", 0) if h3(i, 3, seed) < 0.93 else _c("steel", 3)
        if h3(i, 4, seed) < 0.4 and inside(x + 1, y):
            px[x + 1, y] = _c("hazard", 0)
        k += 1
        if k >= n:
            break


def button(key):
    """160 x 80 (a 12 x 6 px plate, 13 1/3 texels a px): yellow face, a black frame 5 texels wide with a bolt in each
    top corner, the word in black stencil (Neo둥근모, bold, cut, at 2x) in the middle, a hazard strip along the bottom."""
    W, H, F, S = 160, 80, 5, 13
    im = Image.new("RGBA", (W, H), _c("hazard", 1))
    px = im.load()
    for y in range(H):
        for x in range(W):
            if min(x, y, W - 1 - x, H - 1 - y) < F:
                px[x, y] = _c("iron", 1)
    # the face's light: a lighter line inside the frame at the top and left, a darker one at the bottom and right
    for x in range(F, W - F):
        px[x, F] = _c("gold", 3)
    for y in range(F, H - F):
        px[F, y] = _c("gold", 3)
        px[W - F - 1, y] = _c("hazard", 0)
    _hazard(px, F, H - F - S, W - F, H - F, period=12)
    for x in range(F, W - F):
        px[x, H - F - S - 1] = _c("iron", 1)
    _wear(px, W, H, sum(map(ord, key)), lambda x, y: F + 2 <= x < W - F - 2 and F + 2 <= y < H - F - S - 2, n=22)
    word = stencil(scale_mask(word_mask(BUTTON_WORDS[key]), 2))
    mh, mw = len(word), len(word[0])
    top, bottom = F + 1, H - F - S - 1
    _put_mask(px, word, (W - mw) // 2, top + (bottom - top - mh) // 2, _c("iron", 1), _c("iron", 0))
    for (cx, cy) in ((F + 5, F + 5), (W - F - 6, F + 5)):
        _bolt(px, cx, cy)
    return im


def button_back():
    """16 x 16: the plate's back and edges: plain painted steel, a little darker yellow."""
    im = Image.new("RGBA", (16, 16), _c("hazard", 0))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if (x * 7 + y * 5) % 11 == 0:
                px[x, y] = _c("brass", 2)
    return im


# ------------------------------------------------------------------------------------------------
# the site entrance board
# ------------------------------------------------------------------------------------------------

def sign_face():
    """192 x 96 (a 32 x 16 px board at 6 texels a px): a black and yellow hazard border, a white panel, a red
    warning triangle with a black "!", "공사중" big in red (Neo둥근모 bold at 2x) and "관계자 외 출입금지" in black under
    it (1x), a rivet in each corner."""
    W, H, b = 192, 96, 8
    im = Image.new("RGBA", (W, H))
    px = im.load()
    _hazard(px, 0, 0, W, H, period=16)
    for y in range(b, H - b):
        for x in range(b, W - b):
            px[x, y] = _c("car_white", 3) if (x * 3 + y * 7) % 23 else _c("car_white", 2)
    for x in range(b, W - b):
        px[x, b] = _c("car_white", 2)
        px[x, H - b - 1] = _c("car_white", 1)
    for y in range(b, H - b):
        px[W - b - 1, y] = _c("car_white", 2)
    big = scale_mask(word_mask("공사중"), 2)
    small = word_mask("관계자 외 출입금지", spacing=1, space=4)
    gap = 8
    total = len(big) + gap + len(small)
    y0 = b + (H - 2 * b - total) // 2
    # the warning triangle, as tall as the big word, then the word
    ts = len(big)
    tw = int(ts * 1.15)
    x_big = (W - (tw + 8 + len(big[0]))) // 2
    for y in range(ts):
        half = (y + 1) * tw / ts / 2
        for x in range(tw):
            dx = x - tw / 2 + 0.5
            if abs(dx) <= half:
                edge = abs(dx) > half - 4 or y >= ts - 4
                px[x_big + x, y0 + y] = _c("pepper", 2) if edge else _c("hazard", 1)
    cx = x_big + tw // 2
    for y in range(y0 + ts // 3, y0 + ts - 11):
        px[cx - 2, y] = px[cx - 1, y] = px[cx, y] = px[cx + 1, y] = _c("iron", 1)
    for y in range(y0 + ts - 9, y0 + ts - 6):
        px[cx - 2, y] = px[cx - 1, y] = px[cx, y] = px[cx + 1, y] = _c("iron", 1)
    _put_mask(px, big, x_big + tw + 8, y0, _c("pepper", 2), _c("pepper", 1))
    _put_mask(px, small, (W - len(small[0])) // 2, y0 + len(big) + gap, _c("iron", 1))
    for (cx, cy) in ((b + 3, b + 3), (W - b - 4, b + 3), (b + 3, H - b - 4), (W - b - 4, H - b - 4)):
        _bolt(px, cx, cy)
    return im


def sign_back():
    """16 x 16: the board's galvanised back."""
    im = Image.new("RGBA", (16, 16), _c("steel", 3))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if (x + y * 3) % 9 == 0:
                px[x, y] = _c("steel", 4)
            elif (x * 5 + y) % 13 == 0:
                px[x, y] = _c("steel", 2)
    return im


def stencil_print(text, w, h, ramp_step=("pepper", 1), seed=0, wear=0.18, scale=1):
    """w x h, transparent: the word in Neo둥근모 (not bold, scaled up by `scale`) centred, painted as a worn stencil
    (some texels missing), for a cut-out plane laid over a surface (the crate's 취급주의, the cement sacks' print)."""
    from stsv_assets.bake import h3
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = im.load()
    m = scale_mask(word_mask(text, bold=False, spacing=2, space=4), scale)
    mh, mw = len(m), len(m[0])
    ox, oy = (w - mw) // 2, (h - mh) // 2
    for y in range(mh):
        for x in range(mw):
            if m[y][x] and 0 <= ox + x < w and 0 <= oy + y < h and h3(x, y, seed, 13) > wear:
                px[ox + x, oy + y] = _c(*ramp_step)
    return im


def arrows_print(w, h):
    """w x h, transparent: two "this way up" arrows in faded red stencil, for the crate's side."""
    from stsv_assets.bake import h3
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = im.load()
    for cx in (w // 4, 3 * w // 4):
        for y in range(2, h - 2):
            for x in range(w):
                dx = x - cx + 0.5
                head = y < 7 and abs(dx) <= (y - 1) * 0.9
                shaft = y >= 7 and abs(dx) <= 1.5
                if (head or shaft) and h3(x, y, cx, 5) > 0.15:
                    px[x, y] = _c("pepper", 1)
    return im


def main(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    s = 4
    pics = {f"card_{m}{'a' if f else 'b'}": card_face(m, f) for m in range(1, 11) for f in (True, False)}
    pics["card_back"] = card_back()
    pics["die"] = die_atlas()
    for k in BUTTON_WORDS:
        pics[f"btn_{k}"] = button(k)
    pics["sign"] = sign_face()
    for n, im in pics.items():
        im.resize((im.width * s, im.height * s), Image.NEAREST).save(out / f"{n}.png")
    return list(pics)


if __name__ == "__main__":
    import sys
    sys.path.insert(0, str(HERE))
    print(main(sys.argv[1]))
