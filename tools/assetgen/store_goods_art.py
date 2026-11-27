"""
편의점 상품 33종의 인쇄 (docs/plans/편의점_기획안.md §17 "계산대에 올리는 상품 33종 모델 다시 깎기"): the printed
packaging the goods of models/store_goods.py wear. All original designs (no real brand is copied: made-up marks, colour
blocks, print too small to read).

Two kinds of picture:
  wrap pictures   1 texel a drawn unit (64 a block): the print round an eight-sided body, unrolled. x = 0 is the back
                  seam, the front's middle is x = W / 2 (models/store_goods.octa maps them on), y = 0 the top row.
                  Transparent texels leave the body's own paint.
  flat pictures   2 texels a drawn unit (128 a block): bag and box fronts, lids, tops, laid on a box face with
                  HModel.skin / plate.

    python3 tools/assetgen/store_goods_art.py out_dir     # writes every picture there, 4x, to look at
"""

import math

from store_art import Pic, GREEN, mask

INK = ("iron", 0)
WHITE = ("snow", 2)


def _band_text(P, x0, x1, y, col, seed=0, rows=1, gap=2):
    for r in range(rows):
        P.text(x0, y + r * gap, x1, col, seed=seed + r)


def _bubble(P, cx, cy, r, col):
    P.ell(cx, cy, r, r, col)
    P.p(cx - r * 0.4, cy - r * 0.4, WHITE)


def _barcode(P, x0, y0, w, h):
    P.rect(x0, y0, x0 + w, y0 + h, WHITE)
    for x in range(x0 + 1, x0 + w - 1):
        if (x * 7 + x // 3) % 3:
            P.rect(x, y0 + 1, x + 1, y0 + h - 1, INK)


def _star(P, cx, cy, r, col, ring):
    for y in range(int(cy - r - 2), int(cy + r + 3)):
        for x in range(int(cx - r - 2), int(cx + r + 3)):
            a = math.atan2(y + 0.5 - cy, x + 0.5 - cx)
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            rr = r + 1.0 * math.cos(a * 7)
            if d < rr:
                P.p(x, y, col if d < rr - 1 else ring)


# ================================================================================================
# wrap pictures (eight-sided bodies)
# ================================================================================================

def ramen_wrap(kind, W, H):
    """A cup ramen's print round the cup (H rows from its rim down): a dark band under the lid, the brand panel and a
    bowl of noodles on the front, flames (red) or green onion leaves (mild) round the sides, nutrition print and a
    barcode at the back."""
    P = Pic(W, H)
    cx = W / 2
    if kind == "ramen_red":
        body, band, accent, soup = ("pepper", 2), ("iron", 0), ("corn", 2), ("tomato", 3)
    elif kind == "ramen_mild":
        body, band, accent, soup = ("fox", 3), (GREEN, 1), (GREEN, 3), ("corn", 3)
    else:
        body, band, accent, soup = ("iron", 1), ("pepper", 2), ("corn", 2), ("tomato", 2)
    P.rect(0, 0, W, H, body)
    P.rect(0, 0, W, 3, band)
    P.rect(0, 3, W, 4, accent)
    # round the sides: flames / leaves in a band
    for x in range(W):
        hh = 3 + int(2 * abs(math.sin(x * 0.7)))
        if kind == "ramen_mild":
            if x % 5 < 2:
                P.rect(x, H - 4 - hh, x + 1, H - 3, accent)
        else:
            P.rect(x, H - 3 - hh, x + 1, H - 3, accent if x % 4 else ("fox", 3))
    P.rect(0, H - 3, W, H, band)
    # the back: print and a barcode
    _band_text(P, 2, int(cx - 12), 6, WHITE, seed=3, rows=3)
    _barcode(P, int(W - cx * 0.55), 6, 7, 5)
    # the front: a white brand panel, a big mark on it, the bowl under it
    pw = 16 if kind != "ramen_big" else 20
    x0 = int(cx - pw / 2)
    P.rect(x0, 5, x0 + pw, 13, WHITE)
    P.rect(x0, 12, x0 + pw, 13, ("car_white", 1))
    if kind == "ramen_red":
        # a bold black mark with a red flame through it
        P.rect(x0 + 3, 6, x0 + pw - 3, 11, INK)
        for k in range(pw - 8):
            P.p(x0 + 4 + k, 8 + (k % 3 == 1), ("pepper", 3))
        P.rect(int(cx) - 1, 6, int(cx) + 1, 11, ("tomato", 3))
    elif kind == "ramen_mild":
        P.rect(x0 + 3, 6, x0 + pw - 3, 11, (GREEN, 2))
        P.text(x0 + 4, 8, x0 + pw - 4, WHITE, seed=2)
        P.ell(x0 + pw - 2.5, 6.5, 2, 2, ("corn", 2))
    else:
        P.rect(x0 + 2, 6, x0 + pw - 2, 11, ("pepper", 2))
        P.text(x0 + 3, 7, x0 + pw - 3, WHITE, seed=6)
        P.text(x0 + 4, 9, x0 + pw - 5, ("corn", 2), seed=1)
    # the bowl: a white bowl, soup, a nest of noodles, green onion and an egg slice
    by = 13 + (H - 13) * 0.45
    bw = pw / 2 + 1
    P.ell(cx, by, bw, 3.2, ("car_white", 3))
    P.ell(cx, by - 1, bw - 1.5, 2.2, soup)
    for k in range(int(bw * 2) - 4):
        P.p(cx - bw + 3 + k, by - 1 + (k % 3 == 0) - (k % 4 == 1), ("corn", 3) if k % 2 else ("corn", 2))
    P.p(cx + 2, by - 2, ("leaf", 3))
    P.p(cx + 3, by - 1, ("leaf", 2))
    P.p(cx - 3, by - 2, ("leaf", 3))
    P.ell(cx - 1, by - 1.5, 1.2, 1, ("corn", 3))
    if kind == "ramen_red":
        for k in range(3):                               # the hot peppers: how hot
            P.p(x0 + 1 + k * 2, 14, ("tomato", 3))
            P.p(x0 + 1 + k * 2, 15, ("pepper", 3))
    return P.im


def ramen_lid(kind, D):
    """The paper lid seen from above (2 texels a drawn unit, round: the corners cut out): the brand mark in a ring,
    'pull here' with an arrow at the tab (north = the picture's bottom)."""
    W = D * 2
    P = Pic(W, W)
    c = W / 2
    if kind == "ramen_red":
        ring, mid, mark = ("iron", 0), WHITE, ("pepper", 2)
    elif kind == "ramen_mild":
        ring, mid, mark = (GREEN, 1), ("cream", 3), (GREEN, 2)
    else:
        ring, mid, mark = ("pepper", 2), WHITE, ("iron", 0)
    P.ell(c, c, c, c, ring)
    P.ell(c, c, c - 3, c - 3, mid)
    for a in range(0, 360, 15):                          # the paper's pressed rim
        t = math.radians(a)
        P.p(c + (c - 1.5) * math.cos(t), c + (c - 1.5) * math.sin(t), ("car_white", 2))
    P.ell(c, c - 3, c * 0.45, c * 0.3, mark)
    P.text(int(c - c * 0.35), int(c - 3), int(c + c * 0.35), WHITE, seed=4)
    P.text(int(c - c * 0.5), int(c + c * 0.25), int(c + c * 0.5), ("iron", 2), seed=2)
    P.text(int(c - c * 0.4), int(c + c * 0.25) + 2, int(c + c * 0.4), ("iron", 2), seed=5)
    # the tab's arrow (towards the bottom = north)
    for k in range(4):
        P.rect(c - k, W - 7 + k, c + k + 1, W - 6 + k, ("pepper", 2) if kind != "ramen_big" else ("corn", 2))
    return P.im


def bottle_label(kind, W, H):
    """The label round a bottle (H rows): soda = white and green with bubbles and a lime, ion = white with a blue
    wave, water = blue with white peaks, soju = white with a green bird mark and a blue band, beer = gold oval with a
    red ribbon on dark gold, hangover = navy with a gold slash."""
    P = Pic(W, H)
    cx = W / 2
    if kind == "soda":
        P.rect(0, 0, W, H, (GREEN, 2))
        P.rect(0, 2, W, H - 2, WHITE)
        for k in range(0, W, 3):
            _bubble(P, k + 1, 3 + (k * 5) % (H - 6), 0.9, ("cucumber", 3))
        P.rect(int(cx - 8), 3, int(cx + 8), H - 3, (GREEN, 2))
        P.text(int(cx - 6), 5, int(cx + 5), WHITE, seed=1)
        P.ell(cx + 4, H - 6, 2.5, 2.5, ("cucumber", 4), ring=("cucumber", 2))
        P.p(cx + 4, H - 6, ("corn", 3))
        P.text(int(cx - 7), H - 6, int(cx), ("corn", 3), seed=2)
        _barcode(P, 2, 4, 6, 4)
    elif kind == "ion":
        P.rect(0, 0, W, H, WHITE)
        for x in range(W):
            w = int(H * 0.55 + 2.5 * math.sin(x * 0.35))
            P.rect(x, w, x + 1, H, ("sapphire", 2))
            P.p(x, w, ("sapphire", 3))
        P.rect(int(cx - 6), 3, int(cx + 6), 8, ("sapphire", 2))
        P.text(int(cx - 5), 5, int(cx + 5), WHITE, seed=3)
        P.ell(cx + 6, 3.5, 2, 2, ("ice", 3), ring=("sapphire", 3))
        _band_text(P, int(cx - 6), int(cx + 6), H - 4, WHITE, seed=1)
        _barcode(P, 1, 2, 6, 4)
    elif kind == "water":
        P.rect(0, 0, W, H, ("sapphire", 2))
        for x in range(W):                               # snowy peaks
            pk = abs(((x + 3) % 14) - 7)
            P.rect(x, 3 + pk, x + 1, H - 3, ("sapphire", 3))
            if pk < 3:
                P.rect(x, 3 + pk, x + 1, 6, WHITE)
        P.rect(0, H - 3, W, H, ("sapphire", 1))
        P.rect(int(cx - 5), H - 8, int(cx + 5), H - 4, WHITE)
        P.text(int(cx - 4), H - 6, int(cx + 4), ("sapphire", 2), seed=2)
        _barcode(P, 1, 3, 6, 4)
    elif kind == "soju":
        P.rect(0, 0, W, H, WHITE)
        P.rect(0, 0, W, 1, (GREEN, 2))
        P.rect(0, H - 3, W, H - 1, ("sapphire", 2))
        # a green bird in flight (an original mark)
        for k in range(-4, 5):
            P.p(cx + k, 5 - abs(k) // 2 + (abs(k) > 2), (GREEN, 2))
            P.p(cx + k, 6 - abs(k) // 2 + (abs(k) > 2), (GREEN, 2) if abs(k) < 3 else WHITE)
        P.text(int(cx - 5), 9, int(cx + 5), (GREEN, 1), seed=4)
        P.text(int(cx - 3), 11, int(cx + 3), ("iron", 2), seed=1)
        P.rect(int(cx - 2), H - 3, int(cx + 3), H - 1, WHITE)
        P.digits("1", int(cx - 1), H - 3, ("sapphire", 2), font={"1": ["#", "#"]})
        _barcode(P, 1, 3, 6, 4)
    elif kind == "beer":
        P.ell(cx, H / 2, 8, H / 2 - 0.5, ("gold", 2), ring=("gold", 1))
        P.ell(cx, H / 2, 6, H / 2 - 2.5, ("cream", 3))
        P.rect(int(cx - 9), int(H / 2) - 1, int(cx + 10), int(H / 2) + 2, ("pepper", 2))
        P.text(int(cx - 7), int(H / 2), int(cx + 7), WHITE, seed=5)
        P.ell(cx, 4, 1.5, 1.5, ("pepper", 2))
        P.text(int(cx - 4), H - 5, int(cx + 4), ("doenjang", 1), seed=3)
        P.rect(1, 3, 8, H - 3, ("gold", 2))              # the back label
        _barcode(P, 2, 5, 5, 4)
    elif kind == "hangover":
        P.rect(0, 0, W, H, ("indigo", 0))
        for y in range(H):
            x = int(cx - 6 + y * 0.8)
            P.rect(x, y, x + 3, y + 1, ("gold", 2))
        P.ell(cx - 2, H / 2 - 1, 3, 3, WHITE)
        P.p(cx - 2, H / 2 - 1, ("indigo", 1))
        P.p(cx - 3, H / 2 - 1, ("indigo", 1))
        P.p(cx - 2, H / 2 - 2, ("indigo", 1))
        P.text(int(cx + 1), 3, int(cx + 7), WHITE, seed=2)
        P.text(int(cx - 6), H - 3, int(cx + 6), ("gold", 3), seed=1)
    return P.im


def can_wrap(W, H):
    """캔커피: dark roast brown, a gold band, a cream oval with a steaming cup, beans, print."""
    P = Pic(W, H)
    cx = W / 2
    P.rect(0, 0, W, H, ("doenjang", 1))
    P.rect(0, 3, W, 6, ("gold", 2))
    P.rect(0, 4, W, 5, ("gold", 3))
    P.rect(0, H - 4, W, H - 3, ("gold", 2))
    P.ell(cx, 12, 5.5, 4.5, ("cream", 3), ring=("gold", 2))
    P.rect(int(cx - 2), 11, int(cx + 2), 15, ("doenjang", 2))
    P.rect(int(cx - 2), 11, int(cx + 2), 12, ("doenjang", 3))
    P.p(cx + 2, 12, ("doenjang", 2))
    P.p(cx - 1, 9, ("car_white", 2))
    P.p(cx, 8, ("car_white", 2))
    for k in range(4):
        P.ell(3 + k * 4, H - 7 + (k % 2), 1.2, 0.8, ("doenjang", 3))
    P.text(int(cx - 5), H - 9, int(cx + 5), ("gold", 3), seed=2)
    _band_text(P, 1, int(cx - 7), 8, ("cream", 2), seed=1, rows=3)
    return P.im


def cup_coffee_wrap(W, H):
    """컵커피: a clear cup showing the coffee (its level near the top), a cream label round the middle with a latte
    heart and a brown band."""
    P = Pic(W, H)
    cx = W / 2
    P.rect(0, 0, W, H, ("doenjang", 2))
    P.rect(0, 0, W, 2, ("bean", 3))                      # the milky top of the coffee
    P.rect(0, 4, W, H - 3, ("cream", 3))
    P.rect(0, 5, W, 7, ("doenjang", 1))
    P.text(2, 6, W - 2, ("cream", 3), seed=1)
    P.ell(cx, H * 0.62, 4, 3.4, ("doenjang", 2), ring=("doenjang", 1))
    P.ell(cx, H * 0.62 - 0.5, 3, 2.4, ("bean", 3))
    P.p(cx - 1, H * 0.62 - 1, ("bean", 1))
    P.p(cx + 1, H * 0.62 - 1, ("bean", 1))
    P.p(cx, H * 0.62, ("bean", 1))
    P.text(int(cx - 6), H - 5, int(cx + 6), ("doenjang", 1), seed=3)
    _barcode(P, 2, 9, 6, 4)
    return P.im


def cup_coffee_lid(D):
    """The peel lid: silver foil, a brown printed ring, the pull tab corner."""
    W = D * 2
    P = Pic(W, W)
    c = W / 2
    P.ell(c, c, c, c, ("fish_silver", 4))
    P.ell(c, c, c - 3, c - 3, ("doenjang", 1))
    P.ell(c, c, c - 5, c - 5, ("cream", 3))
    P.ell(c, c - 1, 3, 2.5, ("doenjang", 2))
    P.text(int(c - 4), int(c + 3), int(c + 4), ("doenjang", 1), seed=2)
    return P.im


def banana_wrap(W, H):
    """바나나우유 (an original design): the jar's own pale banana yellow shows; printed on it a green bow-shaped banner
    with print, a little banana, and a white band near the base."""
    P = Pic(W, H)
    cx = W / 2
    P.rect(0, H - 4, W, H - 3, ("petal_yellow", 3))
    for x in range(int(cx - 11), int(cx + 12)):
        d = abs(x - cx)
        top = 3 + int(d * 0.15)
        P.rect(x, top, x + 1, top + 6, (GREEN, 2))
        P.p(x, top, (GREEN, 3))
    P.text(int(cx - 8), 6, int(cx + 8), ("corn", 3), seed=2)
    P.text(int(cx - 6), 8, int(cx + 6), WHITE, seed=4)
    # the banana
    for k in range(8):
        P.p(cx - 4 + k, 13 + abs(k - 3.5) * 0.45, ("corn", 2))
        P.p(cx - 4 + k, 14 + abs(k - 3.5) * 0.45, ("corn", 1))
    P.p(cx - 5, 12, ("doenjang", 1))
    P.p(cx + 4, 13, ("doenjang", 1))
    return P.im


def cone_wrap(W, H):
    """콘 아이스크림 sleeve: white paper, red diagonal bands, a chocolate-dipped cone drawn on the front."""
    P = Pic(W, H)
    cx = W / 2
    P.rect(0, 0, W, H, WHITE)
    for y in range(H):
        for x in range(W):
            if (x + y) % 9 < 3:
                P.p(x, y, ("pepper", 2))
    P.rect(0, 0, W, 3, ("pepper", 2))
    P.rect(int(cx - 5), 4, int(cx + 5), 13, WHITE)
    P.ell(cx, 6, 3.5, 2.2, ("doenjang", 1))
    for k in range(4):
        P.rect(int(cx - 3 + k * 0.7), 8 + k, int(cx + 3 - k * 0.7) + 1, 9 + k, ("bean", 2) if k % 2 else ("bean", 3))
    P.text(int(cx - 4), 4, int(cx + 4), ("pepper", 2), seed=1)
    return P.im


# ================================================================================================
# flat pictures (2 texels a drawn unit)
# ================================================================================================

def dosirak_lid(W, H):
    """The lunch box lid seen from above (north = the bottom): through the clear lid rice with black sesame and a
    pickled plum, kimchi, rolled egg, bulgogi, a sausage and greens in their dividers; the green paper sleeve's edges
    and a price sticker are the model's own."""
    P = Pic(W, H, ("iron", 1))
    P.rect(1, 1, W - 1, H - 1, ("iron", 2))
    mid = int(W * 0.46)
    P.rect(2, 2, mid - 1, H - 2, ("rice", 2))
    for k in range(40):
        P.p(3 + (k * 7) % (mid - 5), 3 + (k * 11) % (H - 6), ("rice", 1) if k % 3 else ("rice", 3))
    for k in range(8):
        P.p(4 + (k * 5) % (mid - 8), 4 + (k * 7) % (H - 8), INK)
    P.ell(mid * 0.5, H * 0.5, 2.5, 2.5, ("wine", 2))
    P.p(mid * 0.5 - 1, H * 0.5 - 1, ("pink", 2))
    q = (W - mid - 4) // 2
    a0, a1, b0 = mid + 1, mid + 2 + q, H // 2
    # kimchi (top left)
    P.rect(a0, 2, a0 + q, b0 - 1, ("pepper", 3))
    for k in range(10):
        P.p(a0 + 1 + (k * 3) % (q - 2), 3 + (k * 5) % (b0 - 5), ("pepper", 2) if k % 2 else ("cream", 3))
    # rolled egg (top right)
    P.rect(a1, 2, W - 2, b0 - 1, ("corn", 2))
    for x in range(a1, W - 2, 4):
        P.rect(x, 2, x + 1, b0 - 1, ("corn", 1))
    P.rect(a1, 2, W - 2, 3, ("corn", 3))
    # bulgogi (bottom left)
    P.rect(a0, b0, a0 + q, H - 2, ("doenjang", 1))
    for k in range(12):
        P.p(a0 + 1 + (k * 5) % (q - 2), b0 + 1 + (k * 3) % (H - b0 - 3), ("doenjang", 2) if k % 2 else ("leaf", 3))
    # sausage + greens (bottom right)
    P.rect(a1, b0, W - 2, H - 2, ("leaf", 2))
    for k in range(8):
        P.p(a1 + (k * 3) % (W - a1 - 2), b0 + 1 + (k * 5) % (H - b0 - 3), ("leaf", 3))
    P.ell(a1 + (W - a1) / 2 - 1, b0 + (H - b0) / 2, 3.2, 1.6, ("salmon", 3), ring=("salmon", 2))
    # the dividers
    P.rect(mid - 1, 1, mid + 1, H - 1, ("iron", 1))
    P.rect(mid, b0 - 1, W - 1, b0, ("iron", 1))
    P.rect(a0 + q, 1, a1, H - 1, ("iron", 1))
    # a glint on the lid
    P.line(4, H - 4, 12, H - 12, ("snow", 1))
    P.line(5, H - 4, 13, H - 12, ("snow", 2))
    return P.im


def sleeve_top(W, H):
    """The dosirak's paper sleeve where it crosses the lid (W x H at 2 a unit, north = the bottom): green, a white
    name panel with print, a red 'new' burst and the yellow price sticker."""
    P = Pic(W, H, (GREEN, 2))
    P.rect(0, 0, W, 1, (GREEN, 3))
    P.rect(0, H - 1, W, H, (GREEN, 1))
    P.rect(2, int(H * 0.2), W - 2, int(H * 0.62), WHITE)
    P.text(3, int(H * 0.3), W - 3, (GREEN, 1), seed=2)
    P.text(3, int(H * 0.3) + 3, W - 5, ("iron", 2), seed=5)
    P.text(4, int(H * 0.3) + 6, W - 4, ("iron", 2), seed=1)
    _star(P, W - 5, int(H * 0.12) + 3, 3, ("pepper", 2), ("corn", 2))
    P.rect(2, int(H * 0.7), W - 2, int(H * 0.88), ("corn", 2))
    P.digits("4500", 4, int(H * 0.72), INK, font={**_D35})
    return P.im


_D35 = {"0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"], "2": ["###", "..#", "###", "#..", "###"],
        "3": ["###", "..#", "###", "..#", "###"], "4": ["#.#", "#.#", "###", "..#", "..#"], "5": ["###", "#..", "###", "..#", "###"],
        "6": ["###", "#..", "###", "#.#", "###"], "7": ["###", "..#", "..#", ".#.", ".#."], "8": ["###", "#.#", "###", "#.#", "###"],
        "9": ["###", "#.#", "###", "..#", "###"]}


def bag_front(kind, W, H):
    """A pillow bag's printed front: chips (golden crisps, a red band), shrimp (white, a red band, a curled shrimp
    and crackers), jelly (a stand-up pouch: purple, a window of gummy bears), candy (pink, a window of wrapped sweets).
    The bag's crimps are the model's."""
    P = Pic(W, H)
    cx = W / 2
    if kind == "chips":
        P.rect(0, 0, W, H, ("corn", 2))
        for y in range(H):                               # a sunburst behind the crisps
            for x in range(W):
                a = math.atan2(y - H * 0.62, x - cx)
                if int(a * 5) % 2:
                    P.p(x, y, ("corn", 3))
        P.rect(0, 0, W, int(H * 0.3), ("pepper", 2))
        P.rect(0, int(H * 0.3), W, int(H * 0.3) + 2, ("pepper", 1))
        m = mask("칩")
        _blit(P, m, int(cx - len(m[0]) / 2), 2, WHITE, shadow=("pepper", 0))
        P.text(3, int(H * 0.3) - 3, W - 3, ("corn", 3), seed=1)
        for (fx, fy, rx, ry) in ((0.32, 0.6, 7, 5), (0.62, 0.66, 8, 5.5), (0.45, 0.8, 7, 4.5), (0.72, 0.85, 5, 3.5)):
            P.ell(W * fx, H * fy, rx, ry, ("corn", 3), ring=("onion", 3))
            P.ell(W * fx - 1.5, H * fy - 1.5, rx * 0.5, ry * 0.4, ("cream", 3))
            P.p(W * fx + 2, H * fy + 1, ("onion", 3))
        _star(P, W - 8, int(H * 0.42), 5, ("tomato", 3), ("pepper", 2))
        P.text(W - 12, int(H * 0.42), W - 4, WHITE, seed=3)
        P.rect(2, H - 4, W - 2, H - 3, ("pepper", 2))
    elif kind == "shrimp":
        P.rect(0, 0, W, H, WHITE)
        P.rect(0, 0, W, int(H * 0.28), ("pepper", 2))
        P.rect(0, int(H * 0.28), W, int(H * 0.28) + 3, ("sapphire", 2))
        m = mask("새우")
        _blit(P, m, int(cx - len(m[0]) / 2), 2, WHITE, shadow=("pepper", 0))
        P.text(3, int(H * 0.28) + 1, W - 3, WHITE, seed=2)
        # a curled shrimp
        sx, sy, r = cx + 3, H * 0.62, H * 0.16
        for a in range(-40, 230, 4):
            t = math.radians(a)
            for dr in range(-2, 3):
                seg = (a // 25) % 2
                P.p(sx + (r + dr * 0.8) * math.cos(t), sy + (r + dr * 0.8) * math.sin(t), ("fox", 3) if seg else ("fox", 4))
        P.rect(sx - r - 4, sy - 3, sx - r, sy + 2, ("fox", 2))   # the tail fan
        P.p(sx + r + 1, sy - 3, INK)
        for k in range(5):                                      # crackers
            x = 4 + k * 5
            P.rect(x, H - 9 + (k % 2), x + 4, H - 6 + (k % 2), ("corn", 3))
            P.p(x + 1, H - 8 + (k % 2), ("fox", 3))
        P.rect(0, H - 3, W, H, ("pepper", 2))
    elif kind == "jelly":
        P.rect(0, 0, W, H, ("crystal", 3))
        P.rect(0, 0, W, 5, ("crystal", 2))
        m = mask("젤리")
        _blit(P, m, int(cx - len(m[0]) / 2), 4, WHITE, shadow=("grape", 1))
        P.ell(cx, H * 0.66, W * 0.38, H * 0.25, ("crystal", 4), ring=WHITE)
        cols = [("tomato", 3), ("corn", 2), (GREEN, 3), ("pink", 2), ("fox", 3)]
        k = 0
        for y in range(int(H * 0.47), int(H * 0.86), 5):
            for x in range(int(cx - W * 0.3), int(cx + W * 0.3), 5):
                if ((x + 0.5 - cx) / (W * 0.34)) ** 2 + ((y + 2 - H * 0.66) / (H * 0.22)) ** 2 < 1:
                    c = cols[k % 5]
                    k += 1
                    P.rect(x, y + 1, x + 3, y + 4, c)                # a gummy bear: body, ears
                    P.p(x, y, c)
                    P.p(x + 2, y, c)
                    P.p(x + 1, y + 2, WHITE)
        _star(P, W - 6, 15, 4, ("corn", 2), ("fox", 3))
    elif kind == "candy":
        P.rect(0, 0, W, H, ("pink", 2))
        for y in range(0, H, 6):
            for x in range((y // 6) % 2 * 3, W, 6):
                P.p(x, y, ("pink", 3))
        P.rect(0, 0, W, int(H * 0.26), ("pepper", 3))
        m = mask("사탕")
        _blit(P, m, int(cx - len(m[0]) / 2), 2, WHITE, shadow=("wine", 1))
        P.rect(4, int(H * 0.36), W - 4, H - 5, WHITE)
        P.rect(5, int(H * 0.36) + 1, W - 5, H - 6, ("ice", 3))
        for (fx, fy, col) in ((0.3, 0.48, ("tomato", 3)), (0.66, 0.52, ("sapphire", 3)), (0.42, 0.72, ("corn", 2)), (0.72, 0.76, (GREEN, 3)),
                              (0.25, 0.82, ("grape", 3))):
            x, y = W * fx, H * fy
            P.ell(x, y, 3, 3, WHITE)
            P.line(x - 2, y - 1, x + 1, y + 2, col)
            P.line(x - 1, y - 2, x + 2, y + 1, col)
            P.rect(x - 6, y - 1, x - 3, y + 1, col)
            P.rect(x + 3, y - 1, x + 6, y + 1, col)
    return P.im


def bag_back(kind, W, H):
    """The bag's back: its colour, nutrition print, a barcode."""
    col = {"chips": ("corn", 2), "shrimp": WHITE, "jelly": ("crystal", 3), "candy": ("pink", 2)}[kind]
    ink = {"chips": ("pepper", 1), "shrimp": ("pepper", 2), "jelly": ("grape", 1), "candy": ("wine", 2)}[kind]
    P = Pic(W, H, col)
    P.rect(3, 4, W - 3, int(H * 0.6), WHITE)
    for k in range(int(H * 0.5) // 3):
        P.text(5, 6 + k * 3, W - 5, ink if k == 0 else ("iron", 2), seed=k)
    _barcode(P, W - 16, H - 12, 12, 8)
    return P.im


def _blit(P, m, x0, y0, col, shadow=None):
    if shadow:
        for j, row in enumerate(m):
            for i, v in enumerate(row):
                if v:
                    P.p(x0 + i + 1, y0 + j + 1, shadow)
    for j, row in enumerate(m):
        for i, v in enumerate(row):
            if v:
                P.p(x0 + i, y0 + j, col)


def box_front(kind, W, H):
    """Box fronts: choco_snack (red: two chocolate cream pies, one cut open), chocolate (a brown wrapper, a gold band,
    squares of chocolate), gum (green stripes, mint leaves), bandage (white, a red cross, a plaster), cigarette (a
    made-up crest over navy, the health warning picture under it)."""
    P = Pic(W, H)
    cx = W / 2
    if kind == "choco_snack":
        P.rect(0, 0, W, H, ("pepper", 2))
        P.rect(0, 0, W, int(H * 0.3), WHITE)
        m = mask("초코")
        _blit(P, m, 4, int(H * 0.15 - len(m) / 2), ("pepper", 2))
        P.text(len(m[0]) + 8, int(H * 0.12), W - 4, ("doenjang", 1), seed=1)
        P.text(len(m[0]) + 8, int(H * 0.12) + 3, W - 8, ("iron", 2), seed=3)
        for k, fx in enumerate((0.3, 0.68)):
            x, y, r = W * fx, H * 0.64, H * 0.24
            P.ell(x, y + 2, r, r * 0.55, ("doenjang", 0))
            P.ell(x, y, r, r * 0.55, ("doenjang", 1), ring=("doenjang", 0))
            P.ell(x - r * 0.3, y - r * 0.15, r * 0.35, r * 0.15, ("doenjang", 2))
            if k:
                P.rect(x - r * 0.2, y - r * 0.6, x + r, y + r * 0.5, ("pepper", 2))
                P.rect(x - r * 0.2, y - r * 0.2, x + r * 0.9, y + 1, ("doenjang", 1))
                P.rect(x - r * 0.2, y + 1, x + r * 0.9, y + 3, ("cream", 3))   # the marshmallow layer
                P.rect(x - r * 0.2, y + 3, x + r * 0.9, y + 5, ("bean", 3))
        P.rect(0, H - 4, W, H - 2, ("corn", 2))
        P.text(3, H - 3, W - 3, ("pepper", 2), seed=2)
    elif kind == "chocolate":
        P.rect(0, 0, W, H, ("doenjang", 1))
        P.rect(0, 0, W, 2, ("doenjang", 2))
        P.rect(int(W * 0.08), 0, int(W * 0.08) + 4, H, ("gold", 2))
        for gx in range(3):
            for gy in range(2):
                x0, y0 = int(W * 0.18) + gx * 9, 4 + gy * 7
                P.rect(x0, y0, x0 + 8, y0 + 6, ("doenjang", 0))
                P.rect(x0, y0, x0 + 7, y0 + 5, ("doenjang", 2))
                P.rect(x0 + 1, y0 + 1, x0 + 6, y0 + 4, ("doenjang", 1))
        P.rect(int(W * 0.5), 3, W - 3, H - 3, ("cream", 3))
        P.rect(int(W * 0.5) + 1, 4, W - 4, H - 4, ("doenjang", 2))
        _band_text(P, int(W * 0.5) + 3, W - 5, 6, ("gold", 3), seed=2, rows=3, gap=4)
    elif kind == "gum":
        P.rect(0, 0, W, H, WHITE)
        for x in range(0, W, 8):
            P.rect(x, 0, x + 3, H, (GREEN, 3))
        P.rect(int(cx - W * 0.32), 2, int(cx + W * 0.32), H - 2, (GREEN, 1))
        P.text(int(cx - W * 0.28), int(H / 2) - 2, int(cx + W * 0.2), WHITE, seed=1)
        for k in range(2):
            lx = cx + W * 0.2 + k * 3
            P.ell(lx, H / 2 + (k * 2 - 1), 2.2, 1.3, (GREEN, 3))
    elif kind == "bandage":
        P.rect(0, 0, W, H, WHITE)
        P.rect(0, 0, W, int(H * 0.28), ("pink", 2))
        P.text(3, int(H * 0.12), W - 12, WHITE, seed=6)
        P.rect(W - 9, 1, W - 6, int(H * 0.28) - 1, ("pepper", 2))
        P.rect(W - 10.5, 2.5, W - 4.5, int(H * 0.28) - 2.5, ("pepper", 2))
        y = int(H * 0.58)
        P.rect(4, y - 4, W - 4, y + 5, ("salmon", 3))
        P.rect(4, y - 4, W - 4, y - 3, ("salmon", 4))
        P.rect(int(cx - 5), y - 4, int(cx + 5), y + 5, ("bean", 3))
        for x in range(6, W - 6, 3):
            if abs(x - cx) > 6:
                P.p(x, y, ("salmon", 2))
                P.p(x + 1, y + 2, ("salmon", 2))
        P.text(3, H - 4, W - 3, ("pink", 1), seed=2)
    elif kind == "cigarette":
        P.rect(0, 0, W, H, WHITE)
        top = int(H * 0.44)
        P.rect(0, 0, W, top, ("indigo", 1))
        P.rect(0, top - 1, W, top, ("gold", 2))
        P.rect(0, int(H * 0.2), W, int(H * 0.2) + 1, ("indigo", 0))      # the lid's line
        # a made-up crest: a gold ring, a star
        P.ell(cx, H * 0.24, 4.5, 4.5, ("gold", 2))
        P.ell(cx, H * 0.24, 3.2, 3.2, ("indigo", 1))
        P.p(cx, H * 0.24 - 1, ("gold", 3))
        P.rect(cx - 1, H * 0.24, cx + 1, H * 0.24 + 1, ("gold", 3))
        P.text(3, top - 4, W - 3, ("gold", 3), seed=4)
        # the health warning: a grey picture and a black band with white print
        P.rect(1, top + 1, W - 1, H - 6, ("stone", 2))
        P.ell(cx - 2, top + (H - top) * 0.4, 5, 4, ("salmon", 1))
        P.ell(cx - 2, top + (H - top) * 0.4, 2.5, 2, ("wine", 2))
        P.rect(0, H - 6, W, H, INK)
        P.text(2, H - 4, W - 2, WHITE, seed=1)
        P.text(2, H - 2, W - 4, WHITE, seed=3)
    return P.im


def cig_side(W, H):
    P = Pic(W, H, WHITE)
    P.rect(0, 0, W, int(H * 0.44), ("indigo", 1))
    P.rect(0, int(H * 0.2), W, int(H * 0.2) + 1, ("indigo", 0))
    P.text(1, int(H * 0.6), W - 1, ("iron", 2), seed=1)
    P.text(1, int(H * 0.7), W - 1, ("iron", 2), seed=2)
    P.rect(0, H - 6, W, H, INK)
    return P.im


def ice_bar_top(W, H):
    """The ice bar's wrapper from above (north = the bottom): icy blue, a red ice pop on its stick, white frost
    waves, a yellow '2+1'-style burst without the words."""
    P = Pic(W, H, ("sapphire", 3))
    for y in range(H):
        w = int(3 * math.sin(y * 0.35))
        P.rect(0, y, 4 + w, y + 1, WHITE)
        P.rect(W - 4 + w, y, W, y + 1, WHITE)
    cx = W / 2
    P.rect(cx - 6, 8, cx + 6, H - 14, ("pepper", 2))
    P.rect(cx - 6, 8, cx + 6, 10, ("tomato", 3))
    P.rect(cx - 6, 8, cx - 4, H - 14, ("tomato", 3))
    P.rect(cx - 1.5, H - 14, cx + 1.5, H - 6, ("hinoki", 3))
    P.text(int(cx - 8), 4, int(cx + 8), WHITE, seed=1)
    _star(P, W - 8, H - 9, 4, ("corn", 2), ("fox", 3))
    return P.im


def battery_card(W, H):
    """The blister card from above (north = the bottom): black and orange, a lightning mark, print, the hang hole at
    the top."""
    P = Pic(W, H, ("iron", 1))
    P.rect(0, int(H * 0.72), W, H, ("fox", 3))
    P.rect(0, int(H * 0.72), W, int(H * 0.72) + 1, ("corn", 2))
    for k in range(5):                                   # a lightning bolt
        P.rect(W - 10 + k, int(H * 0.76) + k * 2, W - 7 + k, int(H * 0.76) + k * 2 + 2, ("corn", 2))
    P.text(3, int(H * 0.8), W - 12, WHITE, seed=2)
    P.text(3, int(H * 0.8) + 3, W - 16, ("iron", 1), seed=4)
    P.ell(W / 2, 4, 3, 2, INK)
    P.text(3, 9, W - 3, ("iron", 3), seed=1)
    return P.im


def tissue_top(W, H):
    """The soft pack from above: white film with pink flowers and green leaves, a blue band, the oval opening."""
    P = Pic(W, H, WHITE)
    for k in range(14):
        x, y = (k * 13 + 5) % W, (k * 7 + 3) % H
        P.ell(x, y, 1.6, 1.6, ("pink", 2))
        P.p(x, y, ("corn", 2))
        P.p(x + 2, y + 1, ("leaf", 3))
    P.rect(0, H - 6, W, H, ("sapphire", 3))
    P.text(3, H - 4, W - 3, WHITE, seed=5)
    P.ell(W / 2, H * 0.42, W * 0.3, H * 0.16, ("car_white", 2), ring=("car_white", 1))
    return P.im


def lottery_face():
    """48 x 64 (a 6 x 8 px scratch card): a gold-starred red header with 즉석복권, three silver panels (the top one
    half scratched, showing the symbols), the rules print."""
    W, H = 48, 64
    P = Pic(W, H, ("petal_yellow", 2))
    for y in range(H):
        for x in range(W):
            if min(x, y, W - 1 - x, H - 1 - y) < 2:
                P.p(x, y, ("fabric_red", 2))
    P.rect(2, 2, W - 2, 18, ("fabric_red", 2))
    for k in range(6):
        P.p(5 + k * 7, 4 + (k % 2) * 10, ("gold", 3))
    m = mask("복권")
    _blit(P, m, (W - len(m[0])) // 2, 3, ("corn", 2), shadow=("fabric_red", 0))
    for k in range(3):
        y0 = 22 + k * 12
        P.rect(5, y0, W - 5, y0 + 9, ("fish_silver", 4))
        for x in range(5, W - 5):
            if (x + y0) % 5 == 0:
                P.rect(x, y0, x + 1, y0 + 9, ("fish_silver", 3))
        if k == 0:
            P.rect(5, y0, 27, y0 + 9, ("cream", 3))
            for i, col in enumerate((("fabric_red", 2), ("sapphire", 2), ("fabric_red", 2))):
                P.rect(8 + i * 6, y0 + 2, 12 + i * 6, y0 + 7, col)
            for x in range(27, 31):                       # the ragged scratched edge
                P.rect(x, y0 + (x % 3), x + 1, y0 + 9 - (x % 2), ("cream", 3))
    P.text(5, H - 5, W - 5, ("fabric_red", 1), seed=1)
    return P.im


def main(out):
    from pathlib import Path
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    pics = {
        "ramen_red": ramen_wrap("ramen_red", 66, 24), "ramen_mild": ramen_wrap("ramen_mild", 66, 24),
        "ramen_big": ramen_wrap("ramen_big", 80, 15), "lid_red": ramen_lid("ramen_red", 21),
        "soda": bottle_label("soda", 40, 16), "ion": bottle_label("ion", 40, 18), "water": bottle_label("water", 40, 14),
        "soju": bottle_label("soju", 40, 16), "beer": bottle_label("beer", 40, 14), "hangover": bottle_label("hangover", 33, 12),
        "can": can_wrap(33, 21), "cup": cup_coffee_wrap(46, 18), "banana": banana_wrap(60, 18), "cone": cone_wrap(40, 14),
        "dosirak": dosirak_lid(52, 36), "sleeve": sleeve_top(14, 40), "chips": bag_front("chips", 38, 40),
        "shrimp": bag_front("shrimp", 34, 36), "jelly": bag_front("jelly", 30, 34), "candy": bag_front("candy", 34, 34),
        "choco_snack": box_front("choco_snack", 48, 36), "chocolate": box_front("chocolate", 44, 20),
        "gum": box_front("gum", 36, 14), "bandage": box_front("bandage", 36, 24), "cigarette": box_front("cigarette", 22, 34),
        "ice_bar": ice_bar_top(32, 48), "battery": battery_card(40, 52), "tissue": tissue_top(48, 32), "lottery": lottery_face(),
    }
    for k, im in pics.items():
        im.resize((im.width * 4, im.height * 4), 0).save(out / f"{k}.png")


if __name__ == "__main__":
    import sys
    main(sys.argv[1])
