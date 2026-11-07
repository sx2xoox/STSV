"""Hand-drawn crop item icons: the produce and seed packets redrawn where economy_crops.py's pictures read badly
at inventory size (pale outlines, packets told apart only by a band colour). economy_crops.ICONS uses these over
the original drawings. The packets keep economy_crops' packet frame so all 15 stay one family.
"""
import economy_crops
import math
from PIL import Image
from stsv_assets import icons


# ------------------------------------------------------------------------------------------------
# crops1 (round 1)
# ------------------------------------------------------------------------------------------------


_crops1_GLINT = ("snow", 2)


def _crops1_icon(rows, extra=None, outline=None):
    """economy_crops.icon with per-ramp outline colours (outline maps ramp -> (ramp, step))."""
    C = economy_crops
    legend = dict(C.BASE, **(extra or {}))
    ic = icons.Icon(16 + 2 * C.PAD, 16 + 2 * C.PAD)
    for (x, y), ch in C._cells(rows).items():
        r, i = legend[ch]
        ic.put(x + C.PAD, y + C.PAD, r, i)
    drawn = set(ic.px)
    ic.outline()
    omap = dict(C.OUTLINE, **(outline or {}))
    for k in set(ic.px) - drawn:
        r, _ = ic.px[k]
        if r in omap:
            ic.px[k] = omap[r]
    im = ic.image()
    box = im.getbbox()
    w, h = box[2] - box[0], box[3] - box[1]
    if w > 16 or h > 16:
        raise ValueError(f"icon too big: {w}x{h}")
    out = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    out.paste(im.crop(box), ((16 - w) // 2, (16 - h) // 2))
    return out


def icon_chili_pepper():
    """Korean red pepper: slender 3-4 px pod from the green cap, tapering through the bend to a fine
    point that turns up (the old one swelled to 4 rows at the bend and read as a fat hook)."""
    return economy_crops.shade([
        "..2...........",
        ".32...........",
        "4343..........",
        "#VF#..........",
        "#F##..........",
        ".#F#..........",
        ".#F##.........",
        "..#F#.........",
        "..#F##........",
        "...#F##.......",
        "....#F##......",
        ".....#F###...#",
        "......#####.#.",
        "........###S..",
    ], "pepper", {"V": _crops1_GLINT, "F": ("pepper", 4), "S": ("pepper", 1)}, lo=0)


def icon_garlic():
    """Squat bulb with a flat base: concave shoulders into a papery twisted neck, four cloves split by
    grooves that curve from neck to base (light on each clove's left), one faint lilac streak rising
    from the root plate, a root tuft. Replaces a diamond with speckled ridges (highlight and groove
    pixels alternating) and two loose dark-purple pixels that read as a stain."""
    G = {"A": ("garlic", 3), "B": ("garlic", 2), "C": ("garlic", 1), "D": ("garlic", 0),
         "P": ("petal_purple", 2), "Q": ("petal_purple", 1)}
    return _crops1_icon([
        "......k......",
        ".....kj......",
        "......jk.....",
        ".....AAB.....",
        "....AACBC....",
        "..ACABDABDC..",
        ".ACAABDABBDC.",
        "ABCAABDABBDBC",
        "ABCAABDABBDBC",
        "BBCABBDBBBDCC",
        "BBCBBBDBPBDCC",
        ".BCBBBDBPCDC.",
        "...CCCDQDD...",
        "....j.k.j....",
    ], G, {"straw": ("wood", 0)})


def icon_napa_cabbage():
    """배추: an upright, elongated head. Pale yellow heart at the top, green outer leaves wrapping the
    sides, and the thick white ribs of three outer leaves running up into the green and merging into a
    white base. The old green oval with one white line read as lettuce."""
    WH = {"V": ("garlic", 3), "W": ("garlic", 2), "X": ("garlic", 1),
          "M": ("cabbage", 3), "L": ("cabbage", 2), "K": ("cabbage", 1)}
    return economy_crops.icon([
        "...5MM5...",
        "..5MLLM4..",
        ".45MLMLK3.",
        "454LMLKK32",
        "4554VLK432",
        "45V4VVK432",
        "45V4VVW332",
        "45V4VVW3W2",
        "4VV4VVW3W2",
        "4VVWVVWWW2",
        "4VVWVVWWX2",
        ".4VWVVWWX.",
        ".3VWVVWX2.",
        "..WWWWWX..",
    ], WH)


def icon_paddy():
    """벼: a ripe yellow-green stalk rises, bends over at the top and the golden ear hangs down on the
    right, as on the plant in the field. The ear is a column of separate grains (light tip, darker
    base) with dark gaps, so it reads as grain instead of the old smooth brown horseshoe."""
    rows = [
        ".....edjkl....",
        "....ed..jlk...",
        "...ed...l.kl..",
        "...dc...kl.kl.",
        "..ed...l.kjl..",
        "..dc...k.lk.l.",
        "..dc....lk.kl.",
        ".dc.....k.lk..",
        ".dc......l.k..",
        ".cb......kl...",
        "dc........k...",
        "cb............",
    ]
    # gaps between two grains on the same row are the ear's dark straw, not see-through
    grain = {(x, y) for y, r in enumerate(rows) for x, c in enumerate(r) if c in "jklh"}
    rows = ["".join("h" if c == "." and (x - 1, y) in grain and (x + 1, y) in grain else c
                    for x, c in enumerate(r)) for y, r in enumerate(rows)]
    return _crops1_icon(rows, {}, {"straw": ("wood_dark", 1)})


def icon_green_onion():
    """대파: thick white shaft (3 px, lit top-left) from a cream root tuft, a pale green collar, then two
    blue-green leaves that split into a V at the top, each with a fresh green edge. The root tuft gets
    a brown outline (bean's own darkest step vanishes on the slot grey). The old leaves were near-black
    at in-game size and the split was a single gap pixel."""
    GO = {"V": ("garlic", 3), "W": ("garlic", 2), "X": ("garlic", 1),
          "R": ("bean", 3), "S": ("bean", 2), "G": ("leaf", 4)}
    return _crops1_icon([
        "........G...Gy",
        ".......Gy..Gyx",
        ".......Gx.Gyx.",
        "......Gx.Gyx..",
        "......GxGyx...",
        "......dGyx....",
        ".....edcx.....",
        "....VVdc......",
        "...VVVW.......",
        "..VVVW........",
        ".VVVW.........",
        "VVVWX.........",
        "SRSX..........",
        "R.S...........",
    ], GO, {"bean": ("wood", 0)})


# ------------------------------------------------------------------------------------------------
# crops2 (round 1)
# ------------------------------------------------------------------------------------------------


def _crops2_fit(ic, omap):
    """economy_crops._fit with extra outline colours (ramp -> outline ramp/step)."""
    drawn = set(ic.px)
    ic.outline()
    for k in set(ic.px) - drawn:
        r, _ = ic.px[k]
        if r in omap:
            ic.px[k] = omap[r]
        elif r in economy_crops.OUTLINE:
            ic.px[k] = economy_crops.OUTLINE[r]
    im = ic.image()
    box = im.getbbox()
    w, h = box[2] - box[0], box[3] - box[1]
    out = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    out.paste(im.crop(box), ((16 - w) // 2, (16 - h) // 2))
    return out


def icon_lettuce():
    return economy_crops.icon([
        '..ed..ed..dc..',
        '.edde.eddcdcc.',
        '.deeeddVddcc4.',
        '54deeddVddcc32',
        '544deedVddc332',
        '5W44dedVdc4K32',
        '44W44dedc4K332',
        '444W44dd4K3332',
        '3444W44dK33322',
        '.3444W4K33322.',
        '..3344WK3322..',
        '...334WK322...',
        '.....VWWK.....',
    ], {"V": ("cabbage", 3), "W": ("cabbage", 2), "K": ("cabbage", 1)})


_crops2_MELON_ROWS = {2: (4, 8), 3: (2, 10), 4: (1, 11), 5: (1, 11), 6: (0, 12), 7: (0, 12), 8: (0, 12), 9: (0, 12),
                      10: (1, 11), 11: (1, 11), 12: (2, 10), 13: (4, 8)}


_crops2_MELON_NET = {
    (4, 3), (3, 4), (3, 5), (2, 6), (2, 7), (2, 8), (3, 9), (3, 10), (3, 11), (4, 12),              # left meridian
    (6, 3), (6, 4), (7, 5), (7, 6), (7, 7), (6, 8), (6, 9), (7, 10), (7, 11), (6, 12),              # centre
    (8, 2), (9, 3), (10, 4), (10, 5), (10, 6), (10, 7), (9, 8), (9, 9), (10, 10), (9, 11), (8, 12),  # right
    (4, 6), (5, 6), (6, 6), (4, 10), (5, 10),                                                        # cross lines, offset
    (8, 4), (9, 4), (8, 8), (8, 11),                                                                 # like bricks
    (0, 6), (1, 6), (1, 10), (2, 10), (11, 6), (12, 6), (11, 9), (12, 9),
}


def icon_muskmelon():
    """Round mint-green muskmelon lit from the top-left; the raised net is cream in the light and only a
    lighter green in the shade, so it wraps round the ball; dark green outline; the T-shaped stalk on top."""
    P = economy_crops.PAD
    cells = {(x, y) for y, (a, b) in _crops2_MELON_ROWS.items() for x in range(a, b + 1)}
    cx, cy, rx, ry = 6.5, 8.0, 6.5, 6.0
    light = (-0.55, -0.62, 0.56)
    n = math.sqrt(sum(c * c for c in light))
    lx, ly, lz = (c / n for c in light)
    ic = icons.Icon(16 + 2 * P, 16 + 2 * P)
    for (x, y) in cells:
        u, v = (x + 0.5 - cx) / rx, (y + 0.5 - cy) / ry
        b = u * lx + v * ly + math.sqrt(max(0.0, 1 - u * u - v * v)) * lz
        s = 0 if b < 0.0 else 1 if b < 0.42 else 2 if b < 0.78 else 3
        if (x, y) in _crops2_MELON_NET:
            ic.put(x + P, y + P, *(("musk", 2), ("musk", 3), ("melon_net", 0), ("melon_net", 1))[s])
        else:
            ic.put(x + P, y + P, "musk", s)
    for x, i in ((5, 2), (6, 3), (7, 2)):                     # the T: a bit of dry vine across the stalk
        ic.put(x + P, P, "straw", i)
    ic.put(6 + P, 1 + P, "straw", 2)
    ic.put(6 + P, 2 + P, "straw", 0)
    ic.put(4 + P, 4 + P, "snow", 2)                           # shine
    ic.put(5 + P, 4 + P, "melon_net", 2)
    return _crops2_fit(ic, {"musk": ("cucumber", 0), "melon_net": ("cucumber", 0)})


_crops2_MINI = {
    "tomato": (["..33..", ".Q43S.", "QVTTTS", "QTTTSR", "TTTSSR", ".SSRR."],
               {"T": ("tomato", 2), "Q": ("tomato", 3), "V": ("tomato", 4), "S": ("tomato", 1), "R": ("tomato", 0)}),
    "chili": (["3.....", "434...", ".4GH..", ".GHHJ.", "..HJJ.", "....JK"],
              {"G": ("pepper", 3), "H": ("pepper", 2), "J": ("pepper", 1), "K": ("pepper", 0)}),
    "strawberry": ([".3443.", "PQZQSR", "QZQSZR", ".QSZR.", "..SR..", "......"],
                   {"P": ("toadstool", 4), "Q": ("toadstool", 3), "S": ("toadstool", 2), "R": ("toadstool", 1),
                    "Z": ("petal_yellow", 2)}),
    "onion": (["..3...", "..AB..", ".ABBC.", "ABBCCD", "ABBCCD", ".iBCi."],
              {"A": ("onion", 3), "B": ("onion", 2), "C": ("onion", 1), "D": ("onion", 0)}),
    "cabbage": (["..54..", ".5443.", ".4KK3.", ".KMK2.", ".KMK2.", "..KK.."],
                {"M": ("cabbage", 3), "L": ("cabbage", 2), "K": ("cabbage", 1)}),
}


_crops2_BAND = {"tomato": (("tomato", 3), ("tomato", 2)), "chili": (("pepper", 2), ("pepper", 1)),
                "strawberry": (("pink", 2), ("pink", 1)), "onion": (("onion", 3), ("onion", 2)),
                "cabbage": (("leaf", 4), ("leaf", 3))}


def _crops2_packet(crop):
    pic, letters = _crops2_MINI[crop]
    legend = dict(economy_crops.BASE, **letters)
    P = economy_crops.PAD
    ic = icons.Icon(16 + 2 * P, 16 + 2 * P)
    put = lambda x, y, r, i: ic.put(x + P, y + P, r, i)   # noqa: E731
    x0, x1, y0, y1 = 2, 13, 0, 13
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if y == y0:
                put(x, y, "paper", 0 if x % 2 else 2)
            elif y == y0 + 1:
                put(x, y, "paper", 1)
            elif x == x0 or y == y0 + 2:
                put(x, y, "paper", 2)
            elif x == x1 or y == y1:
                put(x, y, "paper", 0)
            else:
                put(x, y, "paper", 2 if x < x0 + 3 else 1)
    for y in range(3, 11):
        for x in range(4, 12):
            if y in (3, 10) or x in (4, 11):
                put(x, y, "paper", 0 if x == 11 or y == 10 else 2)
    for (dx, dy), ch in economy_crops._cells(pic).items():
        r, i = legend[ch]
        put(5 + dx, 4 + dy, r, i)
    (r1, i1), (r2, i2) = _crops2_BAND[crop]
    for x in range(x0 + 1, x1):
        put(x, 11, r1, i1)
        put(x, 12, r2, i2)
    return economy_crops._fit(ic)


def icon_tomato_seeds():
    return _crops2_packet("tomato")


def icon_chili_seeds():
    return _crops2_packet("chili")


def icon_strawberry_seeds():
    return _crops2_packet("strawberry")


def icon_onion_seeds():
    return _crops2_packet("onion")


def icon_cabbage_seeds():
    return _crops2_packet("cabbage")


# ------------------------------------------------------------------------------------------------
# crops3 (round 1)
# ------------------------------------------------------------------------------------------------


_crops3_MINI = {
    "rice": (["..4kj.", ".3.lki", ".3.kji", "3..lji", "3...ih", "2...h."], {}),
    "green_onion": (["..4.yx", "...4xw", "..Vyw.", ".VTS..", "VTS...", "n....."],
                    {"V": ("snow", 2), "T": ("snow", 1), "S": ("stone", 3)}),
    "radish": (["....44", "..4d43", ".Vdcb.", "VTTcQ.", "VTRQ..", "RQ...."],
               {"V": ("snow", 2), "T": ("snow", 1), "R": ("snow", 0), "Q": ("stone", 2)}),
    "lettuce": ([".edde.", "eedddc", "4edVc3", "44dVc2", ".43V2.", "..VW.."],
                {"V": ("cabbage", 3), "W": ("cabbage", 2)}),
    "cucumber": (["2.....", "AEB...", "EABC..", ".EABC.", "..ABCD", "...CDY"],
                 {"A": ("cucumber", 3), "B": ("cucumber", 2), "C": ("cucumber", 1), "D": ("cucumber", 0),
                  "E": ("sprout", 4), "Y": ("petal_yellow", 1)}),
    "eggplant": ([".2....", "3433..", ".3BC..", ".BVCD.", "..CDDE", "...DE."],
                 {"B": ("aubergine", 4), "C": ("aubergine", 2), "D": ("aubergine", 1), "E": ("aubergine", 0),
                  "V": ("petal_purple", 2)}),
    "zucchini": (["....yx", "...edw", "..edcb", ".edcb.", "edcbb.", "dcbb.."], {}),
    "muskmelon": ([".jij..", ".BiCC.", "BnCnCD", "BCnCND", "CnCNDE", ".DDEE."],
                  {"B": ("musk", 3), "C": ("musk", 2), "D": ("musk", 1), "E": ("musk", 0), "n": ("melon_net", 2),
                   "N": ("melon_net", 1)}),
}


_crops3_BAND = {"rice": (("straw", 2), ("straw", 1)), "green_onion": (("leaf_dark", 2), ("leaf_dark", 1)),
                "radish": (("slime", 1), ("slime", 0)), "lettuce": (("sprout", 4), ("sprout", 3)),
                "cucumber": (("cucumber", 2), ("cucumber", 1)), "eggplant": (("aubergine", 3), ("aubergine", 2)),
                "zucchini": (("zucchini", 2), ("zucchini", 1)), "muskmelon": (("musk", 3), ("musk", 2))}


def _crops3_packet(crop):
    pic, letters = _crops3_MINI[crop]
    legend = dict(economy_crops.BASE, **letters)
    P = economy_crops.PAD
    ic = icons.Icon(16 + 2 * P, 16 + 2 * P)
    put = lambda x, y, r, i: ic.put(x + P, y + P, r, i)   # noqa: E731
    x0, x1, y0, y1 = 2, 13, 0, 13
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if y == y0:
                put(x, y, "paper", 0 if x % 2 else 2)
            elif y == y0 + 1:
                put(x, y, "paper", 1)
            elif x == x0 or y == y0 + 2:
                put(x, y, "paper", 2)
            elif x == x1 or y == y1:
                put(x, y, "paper", 0)
            else:
                put(x, y, "paper", 2 if x < x0 + 3 else 1)
    for y in range(3, 11):
        for x in range(4, 12):
            if y in (3, 10) or x in (4, 11):
                put(x, y, "paper", 0 if x == 11 or y == 10 else 2)
    for (dx, dy), ch in economy_crops._cells(pic).items():
        r, i = legend[ch]
        put(5 + dx, 4 + dy, r, i)
    (r1, i1), (r2, i2) = _crops3_BAND[crop]
    for x in range(x0 + 1, x1):
        put(x, 11, r1, i1)
        put(x, 12, r2, i2)
    return economy_crops._fit(ic)


def icon_rice_seeds():
    return _crops3_packet("rice")


def icon_green_onion_seeds():
    return _crops3_packet("green_onion")


def icon_radish_seeds():
    return _crops3_packet("radish")


def icon_lettuce_seeds():
    return _crops3_packet("lettuce")


def icon_cucumber_seeds():
    return _crops3_packet("cucumber")


def icon_zucchini_seeds():
    return _crops3_packet("zucchini")


def icon_muskmelon_seeds():
    return _crops3_packet("muskmelon")


# ------------------------------------------------------------------------------------------------
# r2_c_seed_2 (round 2)
# ------------------------------------------------------------------------------------------------


_r2c_seed2_EGGPLANT = (
    ["3.....",
     "332...",
     ".3VC..",
     "..BCD.",
     "..CDDD",
     "...DE."],
    {"B": ("aubergine", 4), "C": ("aubergine", 2), "D": ("aubergine", 1), "E": ("aubergine", 0),
     "V": ("petal_purple", 2)},
)


def _r2c_seed2_packet(pic, letters, band):
    """The shared seed packet (same drawing as _crops3_packet) with an explicit window picture and band."""
    legend = dict(economy_crops.BASE, **letters)
    P = economy_crops.PAD
    ic = icons.Icon(16 + 2 * P, 16 + 2 * P)
    put = lambda x, y, r, i: ic.put(x + P, y + P, r, i)   # noqa: E731
    x0, x1, y0, y1 = 2, 13, 0, 13
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if y == y0:
                put(x, y, "paper", 0 if x % 2 else 2)
            elif y == y0 + 1:
                put(x, y, "paper", 1)
            elif x == x0 or y == y0 + 2:
                put(x, y, "paper", 2)
            elif x == x1 or y == y1:
                put(x, y, "paper", 0)
            else:
                put(x, y, "paper", 2 if x < x0 + 3 else 1)
    for y in range(3, 11):
        for x in range(4, 12):
            if y in (3, 10) or x in (4, 11):
                put(x, y, "paper", 0 if x == 11 or y == 10 else 2)
    for (dx, dy), ch in economy_crops._cells(pic).items():
        r, i = legend[ch]
        put(5 + dx, 4 + dy, r, i)
    (r1, i1), (r2, i2) = band
    for x in range(x0 + 1, x1):
        put(x, 11, r1, i1)
        put(x, 12, r2, i2)
    return economy_crops._fit(ic)


def icon_eggplant_seeds():
    pic, letters = _r2c_seed2_EGGPLANT
    return _r2c_seed2_packet(pic, letters, (("aubergine", 2), ("aubergine", 1)))


ICONS = {
    "chili_pepper": icon_chili_pepper,
    "garlic": icon_garlic,
    "green_onion": icon_green_onion,
    "napa_cabbage": icon_napa_cabbage,
    "paddy": icon_paddy,
    "cabbage_seeds": icon_cabbage_seeds,
    "chili_seeds": icon_chili_seeds,
    "lettuce": icon_lettuce,
    "muskmelon": icon_muskmelon,
    "onion_seeds": icon_onion_seeds,
    "strawberry_seeds": icon_strawberry_seeds,
    "tomato_seeds": icon_tomato_seeds,
    "cucumber_seeds": icon_cucumber_seeds,
    "green_onion_seeds": icon_green_onion_seeds,
    "lettuce_seeds": icon_lettuce_seeds,
    "muskmelon_seeds": icon_muskmelon_seeds,
    "radish_seeds": icon_radish_seeds,
    "rice_seeds": icon_rice_seeds,
    "zucchini_seeds": icon_zucchini_seeds,
    "eggplant_seeds": icon_eggplant_seeds,
}
