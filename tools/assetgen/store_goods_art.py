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
    """바나나우유 (an original design), round the jar's belly (H rows): the pale jar shows; printed on it a green
    banner with print and a little banana on the front, a thin green line all round."""
    P = Pic(W, H)
    cx = W / 2
    P.rect(0, H - 1, W, H, (GREEN, 2))
    for x in range(int(cx - 9), int(cx + 10)):
        d = abs(x + 0.5 - cx)
        top = int(d * 0.12)
        P.rect(x, top, x + 1, top + 5, (GREEN, 2))
        P.p(x, top, (GREEN, 3))
    P.text(int(cx - 7), 2, int(cx + 7), ("corn", 3), seed=2)
    for k in range(6):
        P.p(cx - 3 + k, H - 3 + (k in (0, 5)) * -1, ("corn", 1))
    P.p(cx - 4, H - 4, ("doenjang", 1))
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
    P.rect(3, int(H * 0.72), W - 3, int(H * 0.72) + 7, ("corn", 2))
    P.text(4, int(H * 0.72) + 3, W - 4, INK, seed=2)
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
        m = mask("칩")
        bh = len(m) + 8
        P.rect(0, 0, W, bh, ("pepper", 2))
        P.rect(0, bh, W, bh + 2, ("pepper", 1))
        _blit(P, m, int(cx - len(m[0]) / 2) - 6, 3, WHITE, shadow=("pepper", 0))
        P.text(int(cx + len(m[0]) / 2), 6, W - 3, ("corn", 3), seed=1)
        P.text(int(cx + len(m[0]) / 2), 9, W - 5, WHITE, seed=4)
        P.text(4, bh - 3, W - 4, ("corn", 3), seed=1)
        for (fx, fy, rx, ry) in ((0.32, 0.6, 7, 5), (0.62, 0.66, 8, 5.5), (0.45, 0.8, 7, 4.5), (0.72, 0.85, 5, 3.5)):
            P.ell(W * fx, H * fy, rx, ry, ("corn", 3), ring=("onion", 3))
            P.ell(W * fx - 1.5, H * fy - 1.5, rx * 0.5, ry * 0.4, ("cream", 3))
            P.p(W * fx + 2, H * fy + 1, ("onion", 3))
        _star(P, W - 8, int(H * 0.42), 5, ("tomato", 3), ("pepper", 2))
        P.text(W - 12, int(H * 0.42), W - 4, WHITE, seed=3)
        P.rect(2, H - 4, W - 2, H - 3, ("pepper", 2))
    elif kind == "shrimp":
        P.rect(0, 0, W, H, WHITE)
        m = mask("새우")
        bh = len(m) + 5
        P.rect(0, 0, W, bh, ("pepper", 2))
        P.rect(0, bh, W, bh + 4, ("sapphire", 2))
        _blit(P, m, int(cx - len(m[0]) / 2), 3, WHITE, shadow=("pepper", 0))
        P.text(3, bh + 1, W - 3, WHITE, seed=2)
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
        _blit(P, m, int(cx - len(m[0]) / 2), 6, WHITE, shadow=("grape", 1))
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
        m = mask("사탕")
        bh = len(m) + 5
        P.rect(0, 0, W, bh, ("pepper", 3))
        _blit(P, m, int(cx - len(m[0]) / 2), 3, WHITE, shadow=("wine", 1))
        P.rect(4, int(H * 0.4), W - 4, H - 5, WHITE)
        P.rect(5, int(H * 0.4) + 1, W - 5, H - 6, ("ice", 3))
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
        m = mask("초코")
        P.rect(0, 0, W, len(m) + 6, WHITE)
        _blit(P, m, 5, 3, ("pepper", 2), shadow=("pepper", 0))
        P.text(len(m[0]) + 10, 5, W - 6, ("doenjang", 1), seed=1)
        P.text(len(m[0]) + 10, 8, W - 10, ("iron", 2), seed=3)
        P.text(len(m[0]) + 10, 11, W - 8, ("iron", 2), seed=5)
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


# ================================================================================================
# §17 행동을 눈으로: the furniture states' screens and windows, the held things' pictures, the signs
# ================================================================================================

ORDER_GROUPS = {"snack": ("과자", ("fox", 3), [("corn", 2), ("pepper", 2), ("crystal", 3), ("pink", 2)]),
                "noodle": ("라면", ("pepper", 2), [("pepper", 2), ("fox", 3), ("wine", 3), ("corn", 2)]),
                "daily": ("생활", ("sapphire", 2), [("snow", 1), ("iron", 2), ("pink", 2), ("ice", 3)]),
                "meal": ("도시락", (GREEN, 2), [("rice", 2), ("iron", 1), ("fox", 3), ("bean", 3)]),
                "drink": ("음료", ("water", 2), [("petal_yellow", 2), ("doenjang", 2), ("cucumber", 3), ("bottle_green", 2)])}


def tablet_order(group):
    """88 x 64, lit: the 발주 app on one group: the header in the group's colour with 발주 and the group's word, four
    lines of its goods (their colours), the line being ordered picked out in yellow with its count going up (+1), the
    green send key."""
    import gamble_art as GAM
    word, col, chips = ORDER_GROUPS[group]
    W, H = 88, 64
    P = Pic(W, H, WHITE)
    P.rect(0, 0, W, 15, col)
    P.rect(0, 15, W, 16, (col[0], col[1] - 1))
    m = GAM.word_mask("발주", bold=False, spacing=1)
    _blit(P, m, 4, 1, WHITE)
    P.rect(4 + len(m[0]) + 3, 3, 4 + len(m[0]) + 4, 12, WHITE)
    m2 = GAM.word_mask(word, bold=False, spacing=1)
    _blit(P, m2, 4 + len(m[0]) + 8, 1, ("corn", 2))
    counts = ["12", "8", "24", "6"]
    for r in range(4):
        y0 = 18 + r * 9
        pick = r == 1
        if pick:
            P.rect(1, y0 - 1, W - 1, y0 + 8, ("corn", 2))
            P.rect(2, y0, W - 2, y0 + 7, ("cream", 3))
        P.rect(2, y0 + 8, W - 2, y0 + 9, ("snow", 0))
        P.rect(4, y0 + 1, 10, y0 + 7, chips[r])
        P.rect(4, y0 + 1, 10, y0 + 2, (chips[r][0], chips[r][1] + 1))
        P.text(13, y0 + 3, 13 + 20 + r * 4, ("iron", 3), seed=r)
        P.text(13, y0 + 5, 13 + 12 + r * 3, ("iron", 3), seed=r + 3)
        P.rect(55, y0 + 1, 61, y0 + 7, ("car_white", 1))
        P.rect(56, y0 + 4, 60, y0 + 5, ("iron", 2))
        P.rect(77, y0 + 1, 83, y0 + 7, (GREEN, 2) if not pick else ("pepper", 2))
        P.rect(78, y0 + 4, 82, y0 + 5, WHITE)
        P.rect(79.5, y0 + 2, 80.5, y0 + 6, WHITE)
        c = counts[r] if not pick else "9"
        P.digits(c, 69 - (len(c) * 4 - 1) // 2, y0 + 1, ("iron", 1) if not pick else ("pepper", 2), font=_D35)
    P.rect(4, 55, W - 4, 63, (GREEN, 2))
    P.rect(4, 61, W - 4, 63, (GREEN, 1))
    m3 = GAM.word_mask("보내기", bold=False, spacing=1)
    if len(m3) <= 8:
        _blit(P, m3, (W - len(m3[0])) // 2, 55, WHITE)
    else:
        for k in range(4):
            P.rect(40 + k, 57 + k, 41 + k, 59 + k, WHITE)
        for k in range(6):
            P.rect(44 + k, 59 - k, 45 + k, 61 - k, WHITE)
    return P.im


def tablet_sent():
    """88 x 64, lit: 발주 sent: the green header, a big green disc with a white check, a delivery truck driving off
    under it, print (tomorrow's arrival)."""
    import gamble_art as GAM
    W, H = 88, 64
    P = Pic(W, H, WHITE)
    P.rect(0, 0, W, 15, (GREEN, 2))
    P.rect(0, 15, W, 16, (GREEN, 0))
    m = GAM.word_mask("발주 완료", bold=False, spacing=1)
    _blit(P, m, 4, 1, WHITE)
    P.ell(24, 36, 12, 12, (GREEN, 2), ring=(GREEN, 1))
    for k in range(5):
        P.rect(17 + k, 35 + k, 19 + k, 37 + k, WHITE)
    for k in range(9):
        P.rect(21 + k, 39 - k, 23 + k, 41 - k, WHITE)
    # the truck
    P.rect(44, 30, 70, 44, ("car_white", 2))
    P.rect(44, 30, 70, 32, (GREEN, 2))
    P.rect(70, 34, 80, 44, (GREEN, 2))
    P.rect(72, 36, 78, 39, ("ice", 3))
    for wx in (50, 74):
        P.ell(wx, 45, 3, 3, ("iron", 1))
        P.ell(wx, 45, 1.2, 1.2, ("iron", 3))
    for k in range(3):
        P.rect(34 + k * 2, 34 + k * 4, 41, 35 + k * 4, ("iron", 3))      # speed lines
    P.text(44, 51, 82, ("iron", 2), seed=3)
    P.text(8, 55, 40, ("iron", 2), seed=1)
    P.rect(4, 58, W - 4, 63, ("snow", 0))
    P.text(8, 60, W - 8, ("iron", 2), seed=4)
    return P.im


def micro_window(state):
    """The microwave door's window (56 x 48, 8 texels a px, read from in front): run = lit warm inside, the turning
    plate and a lunch box on it, the mesh over it all; done = the same dimmed (the lamp off), steam over the box."""
    W, H = 56, 48
    lit = state == "run"
    P = Pic(W, H, ("corn", 3) if lit else ("iron", 1))
    if lit:
        for y in range(H):
            for x in range(W):
                d = math.hypot(x - W / 2, y - H * 0.4) / (W * 0.6)
                if d > 0.55:
                    P.p(x, y, ("corn", 2))
                if d > 0.8:
                    P.p(x, y, ("fox", 3))
    P.ell(W / 2, H - 9, 22, 3.5, ("fish_silver", 4) if lit else ("iron", 3))
    P.ell(W / 2, H - 9, 20, 2.5, ("cream", 3) if lit else ("iron", 2))
    # a lunch box on the plate
    P.rect(W / 2 - 12, H - 19, W / 2 + 12, H - 10, ("iron", 0))
    P.rect(W / 2 - 11, H - 21, W / 2 + 11, H - 17, ("rice", 2) if lit else ("stone", 2))
    P.rect(W / 2 - 3, H - 21, W / 2 + 3, H - 10, (GREEN, 2) if lit else (GREEN, 0))
    if not lit:
        for k in range(3):                              # steam
            x = W / 2 - 8 + k * 8
            for y in range(H - 34, H - 23):
                P.p(x + int(2 * math.sin((y + k * 3) * 0.6)), y, ("stone", 3))
    for y in range(H):
        for x in range(W):
            if (x + y) % 3 == 0:
                P.shade(x, y, 0.82)
    if lit:
        P.line(4, 4, 14, 4, ("cream", 3))
    return P.im


def micro_display(state):
    """The clock display (17 x 9 texels, lit): run = 1:00 counting down, done = End, open = 0:00 (the lamp's on)."""
    W, H = 17, 9
    P = Pic(W, H, INK)
    g = (110, 240, 150)
    font = {**_D35, ":": [".", "#", ".", "#", "."], "E": ["###", "#..", "##.", "#..", "###"], "n": ["...", "##.", "#.#", "#.#", "#.#"],
            "d": ["..#", "..#", "###", "#.#", "###"]}
    text = {"run": "1:00", "done": "End", "open": "0:00"}[state]
    P.digits(text, 2, 2, g, font=font)
    return P.im


def micro_inside():
    """The microwave's inside walls (16 x 16): pale enamel, lit by its lamp, a few vent dots."""
    P = Pic(16, 16, ("cream", 3))
    for y in range(16):
        for x in range(16):
            if (x * 3 + y * 5) % 11 == 0:
                P.p(x, y, ("car_white", 2))
    return P.im


def wet_sign_face():
    """60 x 84 (a 10 x 14 px panel of the A-frame wet floor sign, 6 texels a px): safety yellow, a black caution
    triangle with a figure slipping on a puddle, 미끄럼 / 주의 under it (주의 in red), a black band at the foot; the
    handle slot at the top."""
    import gamble_art as GAM
    W, H = 60, 84
    P = Pic(W, H, ("corn", 2))
    P.rect(0, 0, W, 3, ("corn", 3))
    P.rect(W / 2 - 8, 5, W / 2 + 8, 10, INK)
    cx, top, bot = W / 2, 14, 44
    for y in range(top, bot):
        hw = (y - top) * 0.62 + 1.5
        P.rect(cx - hw, y, cx + hw, y + 1, INK)
        if top + 4 < y < bot - 3:
            P.rect(cx - hw + 4, y, cx + hw - 4, y + 1, ("corn", 2))
    fx, fy = cx, 33
    P.ell(fx + 4, fy - 9, 2.4, 2.4, INK)
    for d in (-0.5, 0, 0.5, 1):
        P.line(fx + 3 + d, fy - 6, fx - 1 + d, fy + 1, INK)
    for d in (0, 1):
        P.line(fx - 1, fy + 1 + d, fx + 6, fy + 5 + d, INK)
        P.line(fx - 1, fy + 1 + d, fx - 7, fy + 4 + d, INK)
        P.line(fx + 2, fy - 4 + d, fx + 8, fy - 3 + d, INK)
        P.line(fx + 2, fy - 4 + d, fx - 5, fy - 7 + d, INK)
    P.rect(cx - 10, fy + 7, cx + 10, fy + 8, ("water", 2))
    P.rect(cx - 7, fy + 8, cx + 4, fy + 9, ("water", 2))
    m1 = GAM.word_mask("미끄럼", bold=True, spacing=1)
    m2 = GAM.word_mask("주의", bold=True, spacing=1)
    _blit(P, m1, int((W - len(m1[0])) / 2), 49, INK)
    _blit(P, m2, int((W - len(m2[0])) / 2), 64, ("pepper", 2))
    P.rect(0, H - 4, W, H, INK)
    return P.im


def box_goods(kind):
    """64 x 52 (an open delivery box's goods seen from above, 12 x ~10 px at ~5 texels a px, north = the bottom): rows
    of the kind's goods packed in: snack = crisp bags and choco pie boxes, noodle = cup ramen lids, daily = tissue
    packs and bandage boxes, meal = triangle kimbap and lunch boxes, drink = bottle caps and cans."""
    W, H = 64, 52
    P = Pic(W, H, ("bean", 0))
    if kind == "snack":
        for i in range(3):
            x0 = 2 + i * 21
            col = [("corn", 2), ("pepper", 2), ("crystal", 3)][i]
            for j in range(4):
                y0 = 2 + j * 12
                P.rect(x0, y0, x0 + 19, y0 + 11, col)
                P.rect(x0, y0, x0 + 19, y0 + 2, (col[0], col[1] - 1))
                P.rect(x0 + 3, y0 + 4, x0 + 16, y0 + 8, WHITE if i != 1 else ("doenjang", 1))
                P.text(x0 + 4, y0 + 6, x0 + 15, (col[0], col[1] - 1), seed=j)
    elif kind == "noodle":
        for i in range(4):
            for j in range(3):
                cx, cy = 9 + i * 15.5, 9 + j * 17
                col = [("pepper", 2), ("fox", 3)][(i + j) % 2]
                P.ell(cx, cy, 7.5, 7.5, ("car_white", 3), ring=col)
                P.ell(cx, cy - 1, 3.5, 2.5, col)
                P.rect(cx - 1, cy + 5, cx + 1, cy + 8, ("car_white", 2))
    elif kind == "daily":
        for i in range(2):
            for j in range(3):
                x0, y0 = 2 + i * 31, 2 + j * 16
                P.rect(x0, y0, x0 + 29, y0 + 15, WHITE)
                if (i + j) % 2:
                    P.rect(x0, y0 + 11, x0 + 29, y0 + 15, ("sapphire", 3))
                    P.ell(x0 + 14, y0 + 6, 8, 3, ("car_white", 1))
                else:
                    P.rect(x0, y0, x0 + 29, y0 + 4, ("pink", 2))
                    P.rect(x0 + 8, y0 + 7, x0 + 21, y0 + 11, ("salmon", 3))
                    P.rect(x0 + 12, y0 + 7, x0 + 17, y0 + 11, ("bean", 3))
    elif kind == "meal":
        for i in range(5):
            x0 = 2 + i * 12
            for j in range(2):
                y0 = 2 + j * 13
                P.rect(x0, y0, x0 + 11, y0 + 11, ("rice", 2))
                P.rect(x0, y0 + 7, x0 + 11, y0 + 11, ("roe", 1))
                P.rect(x0 + 5, y0, x0 + 6, y0 + 11, ("pepper", 2))
                P.rect(x0 + 2, y0 + 3, x0 + 9, y0 + 6, [("sapphire", 2), ("pepper", 2), ("doenjang", 1)][(i + j) % 3])
        for i in range(2):
            x0 = 2 + i * 31
            P.rect(x0, 29, x0 + 29, 50, ("iron", 1))
            P.rect(x0 + 1, 30, x0 + 14, 49, ("rice", 2))
            P.rect(x0 + 15, 30, x0 + 28, 39, ("pepper", 3))
            P.rect(x0 + 15, 40, x0 + 28, 49, ("corn", 2))
            P.rect(x0 + 13, 29, x0 + 16, 50, (GREEN, 2))
    elif kind == "drink":
        for i in range(5):
            for j in range(4):
                cx, cy = 7 + i * 12.5, 7 + j * 12.5
                col = [("petal_yellow", 2), (GREEN, 2), ("sapphire", 2), ("snow", 2), ("doenjang", 1)][(i + 2 * j) % 5]
                P.ell(cx, cy, 5.5, 5.5, ("ice", 3), ring=("glass", 2))
                P.ell(cx, cy, 3, 3, col)
                P.p(cx - 1, cy - 1, WHITE)
    return P.im


def id_back():
    """64 x 40: the ID card's back (what the customer sees while the worker holds it): pale blue guilloche, print, a
    small barcode."""
    P = Pic(64, 40, ("ice", 3))
    for x in range(64):
        y = int(20 + 8 * math.sin(x * 0.25))
        P.p(x, y, ("ice", 2))
        P.p(x, 40 - y, ("ice", 2))
    for k in range(4):
        P.text(6, 6 + k * 4, 46 - k * 5, ("indigo", 2), seed=k)
    _barcode(P, 44, 28, 14, 7)
    return P.im


def id_held_face():
    """The ID card's face for the held card (128 x 80): store_art.id_card with its text area printed (a name and a
    birth year as print too small to read), so it reads as a filled-in card in the hand."""
    import store_art as SA
    im = SA.id_card()
    P = Pic(im.width, im.height)
    P.im, P.px = im, im.load()
    for k in range(2):
        P.text(13, 31 + k, 52, ("indigo", 1), seed=7)
    for k in range(3):
        P.text(14, 46 + k * 8, 66 - k * 10, ("indigo", 1), seed=k)
        P.text(14, 47 + k * 8, 66 - k * 10, ("indigo", 1), seed=k)
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
