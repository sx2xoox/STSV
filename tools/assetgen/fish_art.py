"""Hand-drawn fish icons: the species whose look the shared templates in economy_fish.py cannot give
(their own silhouette, marks, props). economy_fish.ICONS uses these over the template; every other fish is
drawn by economy_fish.icon(). Each icon is a 16x16 image, lit from the top left, with a dark outline in the
fish's own hue. Diagonal fish lie like vanilla cod (head bottom left, tail top right); tall and round fish,
rays, crabs and turtles are drawn side-on or from above.
"""
from economy_fish import mat
from stsv_assets.pixelart import Canvas, color
from stsv_assets import palette as _fish2_P
from stsv_assets import pixelart as _fish2_px
from stsv_assets.color import rgb_to_oklch as _fish2_oklch
from stsv_assets.pixelart import Canvas
import math as _fish3_math
from PIL import Image as _fish3_Image
from stsv_assets import palette as _fish3_P
from stsv_assets import pixelart as _fish3_px
from stsv_assets.color import rgb_to_oklch as _fish3_oklch
import math as _fish4_math
from PIL import Image as _fish4_Image
from stsv_assets import palette as _fish4_P
from stsv_assets import pixelart as _fish4_px
from stsv_assets.color import rgb_to_oklch as _fish4_oklch


# ------------------------------------------------------------------------------------------------
# 바다 1: 고등어 ~ 탱
# ------------------------------------------------------------------------------------------------


def _fish1_ink(c, a, *pale):
    """pale bellies get the back's darkest step as outline (their own darkest step is slot grey)"""
    for m in pale:
        c.ink[m] = (a, 0)


def _fish1_closed(c, ink):
    """render, then fill the specks of background shut in between arms with the outline colour"""
    im = c.render()
    px = im.load()
    seen = set()
    for y0 in range(16):
        for x0 in range(16):
            if px[x0, y0][3] or (x0, y0) in seen:
                continue
            comp, todo, edge = [], [(x0, y0)], False
            while todo:
                x, y = todo.pop()
                if (x, y) in seen or not (0 <= x < 16 and 0 <= y < 16) or px[x, y][3]:
                    continue
                seen.add((x, y))
                comp.append((x, y))
                edge |= x in (0, 15) or y in (0, 15)
                todo += [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]
            if not edge and len(comp) <= 3:
                for q in comp:
                    px[q] = color(ink, 0)
    return im


def _fish1_std(c, a, b, stout=3.6, back=2.4, tailmat=None, dorsal=True, fins=True, eye=True):
    """the shared standard fish (economy_fish._fish) with a few knobs: tail top right, head bottom left"""
    _fish1_ink(c, a, b)
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], tailmat or a, 0,
           normal=(0.35, 0.1, 1), lo=1, hi=3)
    if dorsal:
        c.poly([(4.5, 8.5), (7.5, 4), (9.5, 4.5), (6, 9)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.tube([(3.4, 12.4), (6.6, 9.2), (10.4, 5.4)], [2.2, stout, 2.1], b, 2, flat=0.9, lo=1, hi=4)
    c.tube([(2.9, 11.7), (6.0, 8.5), (9.8, 4.8)], [1.4, back, 1.3], a, 3, flat=0.9, lo=1, hi=4)
    if fins:
        c.poly([(6, 12.6), (8.5, 14.5), (9.4, 12.4)], a, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    if eye:
        c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)


# ---- 고등어: blue-green back with the dark wavy bars of a mackerel, silver belly ----
def icon_mackerel():
    c = Canvas()
    a, b = mat("#3f7a8a"), mat("#c9d6dc")
    _fish1_std(c, a, b)
    for pts in (((4, 8), (5, 9)), ((5, 6), (6, 7), (7, 8)), ((7, 5), (8, 6), (9, 7)), ((10, 5),)):
        c.dots(pts, a, 1, z=4)
    return c.render()


# ---- 방어: blue back, silver belly, the yellow stripe and yellow tail it is named for ----
def icon_yellowtail():
    c = Canvas()
    a, b, y = mat("#3a6a9a"), mat("#e8eef4"), mat("#e8c030", 0.08)
    _fish1_std(c, a, b, tailmat=y)
    c.line(5, 10, 9, 6, y, 4, z=4)
    return c.render()


# ---- 청새치: spear bill, tall swept dorsal, crescent tail, pale stripes: a legend, not a mackerel ----
def icon_marlin():
    c = Canvas()
    a, b = mat("#2a3a8a"), mat("#d8e0ee")
    _fish1_ink(c, a, b)
    c.poly([(10.8, 5.2), (11.2, 0.0), (13.0, 3.2), (16.0, 4.8), (11.8, 6.2)], a, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(4.8, 8.8), (4.6, 3.0), (8.4, 6.2)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=4)
    c.tube([(4.4, 11.4), (7.2, 8.6), (11.2, 4.8)], [1.9, 3.0, 1.2], b, 2, flat=0.9, lo=1, hi=4)
    c.tube([(4.0, 10.8), (6.8, 8.0), (10.8, 4.4)], [1.2, 2.0, 0.8], a, 3, flat=0.9, lo=1, hi=4)
    c.poly([(7.5, 11.5), (9.5, 13.5), (9.8, 11)], a, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    c.line(0, 15, 3, 12, "steel", 3, z=5)
    c.dots([(6, 7), (7, 8), (8, 5), (9, 6)], "ice", 3, z=4)
    c.put(4, 11, "roe", 0)
    return c.render()


# ---- 흰동가리: orange all over, white bands behind the eye, mid body and at the tail ----
def icon_clownfish():
    c = Canvas()
    a, b = mat("#f06a10"), mat("#ff9a40")
    _fish1_std(c, a, b, stout=3.8, back=2.6)
    _fish1_ink(c, a, "snow")
    body = set(c.px)
    band = [(4, 8), (5, 9), (6, 10), (7, 11), (6, 5), (7, 6), (8, 7), (9, 8), (10, 9), (7, 5), (8, 6), (9, 7),
            (10, 8), (9, 3), (10, 4), (11, 5)]
    c.dots([p for p in band if p in body], "snow", 4, z=4)
    c.put(3, 11, "roe", 0)
    return c.render()


# ---- 자리돔: a small deep-bodied reef fish, dark slate blue, forked tail, a pale spot at the back ----
def icon_damselfish():
    c = Canvas()
    a, b = mat("#3a5080"), mat("#8a9ab8")
    _fish1_ink(c, a, b, "snow")
    c.poly([(9.5, 6.5), (12.6, 0.6), (12.8, 4.2), (15.4, 3.4), (11, 8.6)], a, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(3.5, 8.5), (6.6, 3.0), (10, 4.2), (6, 9)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.poly([(6, 12.5), (10.5, 12.8), (11, 8)], a, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    c.tube([(4.0, 11.6), (6.8, 8.8), (10.0, 5.6)], [2.3, 4.0, 1.6], b, 2, flat=0.9, lo=1, hi=4)
    c.tube([(3.6, 11.0), (6.3, 8.1), (9.5, 5.0)], [1.4, 2.8, 1.0], a, 3, flat=0.9, lo=1, hi=4)
    c.put(9, 5, "snow", 4)
    c.put(4, 10, "roe", 0).put(5, 9, "snow", 1)
    return c.render()


# ---- 쥐치: a lens-shaped deep body, the single file spine over the eye, a small pointed snout ----
def icon_filefish():
    c = Canvas()
    a, b = mat("#8e8050"), mat("#d8cca0")
    _fish1_ink(c, a, b)
    c.poly([(10.4, 5.6), (14.8, 1.4), (15.6, 6.4), (12, 7.4)], a, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.line(5, 5, 6, 0, a, 2, z=1)
    c.tube([(2.0, 13.6), (6.6, 9.0), (11.2, 5.0)], [0.9, 4.8, 1.2], b, 2, flat=0.85, lo=1, hi=4)
    c.tube([(2.0, 13.0), (6.0, 8.4), (10.8, 4.6)], [0.6, 3.4, 0.8], a, 3, flat=0.85, lo=1, hi=4)
    c.dots([(5, 8), (7, 6), (6, 10), (8, 8), (9, 6), (9, 10)], a, 1, z=4)
    c.put(4, 11, "roe", 0)
    return c.render()


# ---- 광어: a flounder seen from above, fin fringe all round, both eyes on the top side ----
def icon_flatfish():
    c = Canvas()
    a, f = mat("#7a6a50"), mat("#8a7a5a")
    _fish1_ink(c, mat("#5a4a36"), f, "cream")
    c.poly([(11, 5), (14.8, 0.8), (15.4, 5.4), (12, 6.4)], f, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.tube([(3.4, 12.6), (7.4, 8.4), (11.4, 4.8)], [2.8, 5.0, 1.6], f, 1, flat=0.6, lo=1, hi=3)
    c.tube([(3.6, 12.2), (7.2, 8.6), (11.0, 5.0)], [2.0, 3.8, 1.0], a, 2, flat=0.8, lo=2, hi=4)
    c.dots([(7, 7), (9, 8), (7, 10), (10, 5)], "cream", 2, z=4)
    c.dots([(3, 10), (5, 12)], "roe", 0, z=5).dots([(3, 9), (5, 11)], "cream", 4, z=5)
    return c.render()


# ---- 아귀: a wide head with a gaping mouth full of teeth, a short tapering body, a lure ----
def icon_monkfish():
    c = Canvas()
    a, b = mat("#6a5038"), mat("#a08a68")
    _fish1_ink(c, a, "snow", b)
    c.poly([(11.5, 8.5), (15.5, 5), (15.5, 12)], a, 0, normal=(0.4, 0, 1), lo=1, hi=3)
    c.tube([(5.5, 8.5), (9.5, 8.5), (12.5, 8.5)], [5.6, 3.4, 1.4], a, 1, flat=0.7, lo=1, hi=4, gloss=0.9)
    c.poly([(-0.5, 6.0), (5.5, 9.2), (-0.5, 12.4)], "roe", 2, step=1)
    c.dots([(1, 6), (3, 7), (1, 12), (3, 11)], "snow", 4, z=4)
    c.dots([(7, 9), (9, 7), (6, 6)], b, 3, z=4)
    c.line(6, 3, 4, 1, a, 2, z=3)
    c.put(3, 0, b, 5)
    c.put(7, 5, "roe", 0)
    return c.render()


# ---- 홍어: a skate from above, curved wings, raised body, eyes, long thin tail ----
def icon_skate():
    c = Canvas()
    a = mat("#8a6a5a")
    c.line(8, 12, 12, 15, a, 2, z=0)
    c.poly([(8, 0.4), (10, 3), (13.5, 5.2), (15.6, 8.2), (12, 9.6), (9, 11.8), (8, 12.4), (7, 11.8), (4, 9.6),
            (0.4, 8.2), (2.5, 5.2), (6, 3)], a, 1, normal=(-0.2, -0.4, 1), lo=1, hi=3)
    c.ellipse(8, 7.5, 2.4, 4.0, a, 2, lo=2, hi=4, gloss=0.8)
    c.put(7, 4, "roe", 0).put(9, 4, "roe", 0)
    c.dots([(4, 7), (12, 7)], a, 1, z=3)
    return c.render()


# ---- 나비고기: tall yellow disc, pointed snout, black bar through the eye, black spot near the tail ----
def icon_butterflyfish():
    c = Canvas()
    y = mat("#ffd84a")
    _fish1_ink(c, mat("#7a5a10"), y, "snow")
    c.poly([(12, 8), (15.4, 5), (15.4, 11)], y, 0, normal=(0.4, 0, 1), lo=1, hi=3)
    c.ellipse(8.2, 8, 5.2, 6.0, y, 1, lo=1, hi=4, gloss=0.75)
    c.poly([(0.6, 8.4), (3.6, 6.8), (3.6, 10.2)], y, 2, normal=(-0.3, -0.2, 1), lo=2, hi=4)
    c.line(5, 4, 5, 12, "roe", 1, z=4)
    c.line(6, 3, 6, 13, "snow", 3, z=4)
    c.put(5, 8, "snow", 5)
    c.dots([(10, 5), (11, 5), (10, 6), (11, 6)], "roe", 1, z=4)
    return c.render()


# ---- 쥐돔: blue oval, black palette mark, a bright yellow tail ----
def icon_tang():
    c = Canvas()
    bl, y = mat("#2a5ad8"), mat("#ffd84a")
    _fish1_ink(c, bl, "snow", y)
    c.poly([(11.5, 8), (15.4, 4.5), (14.4, 8), (15.4, 11.5)], y, 0, normal=(0.4, 0, 1), lo=2, hi=4)
    c.ellipse(7, 8, 5.6, 4.8, bl, 1, lo=1, hi=4, gloss=0.8)
    c.dots([(4, 6), (5, 5), (6, 5), (7, 5), (8, 5), (9, 6), (10, 6), (11, 7), (11, 8), (10, 9), (9, 9), (8, 9),
            (7, 9), (6, 8)], "roe", 1, z=3)
    c.put(3, 7, "roe", 0).put(2, 7, "snow", 3)
    return c.render()


# ---- 문어: round head with eyes, six arms splaying out (the outer two curl up), pale tips ----
def icon_octopus():
    c = Canvas()
    a, b = mat("#b0563a"), mat("#e8a080")
    _fish1_ink(c, a, b)
    arms = [
        [(5, 10), (4, 11), (3, 12), (2, 13), (1, 13), (1, 12)],
        [(6, 11), (5, 12), (4, 13), (4, 14), (3, 15)],
        [(7, 11), (7, 12), (7, 13), (6, 14), (6, 15)],
        [(9, 11), (9, 12), (9, 13), (10, 14), (10, 15)],
        [(10, 11), (11, 12), (12, 13), (12, 14), (13, 15)],
        [(11, 10), (12, 11), (13, 12), (14, 13), (15, 13), (15, 12)],
    ]
    for arm in arms:
        for k, (x, y) in enumerate(arm):
            c.put(x, y, a, 3 if k < 3 else 2, z=1)
        c.put(*arm[-1], b, 4, z=1)
    c.ellipse(8, 5.4, 4.8, 5.0, a, 2, lo=1, hi=4, gloss=0.75)
    c.rect(5, 8.5, 11, 10.5, a, 2, normal=(0, 0.3, 1), lo=1, hi=3)
    c.put(6, 8, "roe", 0).put(10, 8, "roe", 0)
    return _fish1_closed(c, a)


# ------------------------------------------------------------------------------------------------
# 바다 2 · 북방 · 민물 1: 쏠배감펭 ~ 빙어
# ------------------------------------------------------------------------------------------------


def _fish2_m(hexcol, L=None, C=None, span=0.34):
    """A material from a colour: its lightness (or L) in the middle of the ramp, its chroma (or C)."""
    name = f"f2_{hexcol[1:]}_{L}_{C}_{span}"
    if name not in _fish2_P.MATERIALS:
        l, ch, h = _fish2_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        _fish2_P.MATERIALS[name] = (h, ch / 1.5 if C is None else C, min(0.9, max(0.3, l)) if L is None else L,
                                    span, 5, False)
    return name


def _fish2_ink(hexcol, L=0.26, C=None):
    """A dark outline (material, step 0) in the fish's own hue."""
    name = f"f2ink_{hexcol[1:]}_{int(L * 100)}"
    if name not in _fish2_P.MATERIALS:
        l, ch, h = _fish2_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        _fish2_P.MATERIALS[name] = (h, min(ch / 1.5, 0.06) if C is None else C, L + 0.272, 0.34, 5, False)
    return (name, 0)


def _fish2_canvas(ink, *mats):
    c = Canvas()
    for m in mats:
        c.ink[m] = ink
    return c


def _fish2_paint(im, pts, mat, step):
    """Pixels painted after the outline pass (thin whiskers, a bill, spines): they are their own outline."""
    col = _fish2_px.color(mat, step)
    for x, y in pts:
        if 0 <= x < 16 and 0 <= y < 16:
            im.putpixel((x, y), col)
    return im


def _fish2_recolor(c, pts, mat, step, only=None):
    """Recolours body pixels only, so a pattern never sticks out of the silhouette."""
    for p in pts:
        v = c.px.get(p)
        if v and v[2] < 90 and (only is None or v[0] in only):
            v[0], v[1] = mat, step
    return c


def _fish2_bands(c, ks, mat, step, only):
    """Bands across a corner-to-corner fish: the pixels with x - y == k (perpendicular to its axis)."""
    return _fish2_recolor(c, [(x, y) for x in range(16) for y in range(16) if x - y in ks], mat, step, only)


def _fish2_std(c, a, b, fin=None, dorsal=True, pelvic=True, eye=True, stout=3.6):
    """The set's standard fish body (economy_fish._fish), fins optionally in their own colour."""
    fin = fin or a
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], fin, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    if dorsal:
        c.poly([(4.5, 8.5), (7.5, 4), (9.5, 4.5), (6, 9)], fin, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.tube([(3.4, 12.4), (6.6, 9.2), (10.4, 5.4)], [2.2, stout, 2.1], b, 2, flat=0.9, lo=1, hi=4)
    c.tube([(2.9, 11.7), (6.0, 8.5), (9.8, 4.8)], [1.4, 2.4, 1.3], a, 3, flat=0.9, lo=1, hi=4)
    if pelvic:
        c.poly([(6, 12.6), (8.5, 14.5), (9.4, 12.4)], fin, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    if eye:
        c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    return c


def _fish2_spiny(c, mat, z=4, lo=1, hi=3):
    """A spiny dorsal fin (perch, mandarin fish): a saw edge standing up off the back."""
    c.poly([(3.2, 8.6), (2.0, 6.8), (3.8, 7.0), (3.2, 5.0), (5.2, 5.6), (5.0, 3.6), (6.8, 4.4), (7.2, 2.6), (8.4, 4.0),
            (9.4, 3.4), (8.6, 5.6), (5.0, 8.6)], mat, z, normal=(-0.4, -0.6, 0.7), lo=lo, hi=hi)
    return c


def icon_sunfish():
    a = _fish2_m("#7a8898")
    b = _fish2_m("#d0d8e0")
    c = _fish2_canvas(_fish2_ink("#56606c", 0.28), a, b)
    c.poly([(7.6, 5), (10, 1.0), (12.0, 1.3), (11.8, 5.5)], a, 0, normal=(-0.2, -0.6, 0.8), lo=1, hi=3)
    c.poly([(7.6, 11), (10, 15.0), (12.0, 14.7), (11.8, 10.5)], a, 0, normal=(0.1, 0.5, 0.85), lo=1, hi=3)
    c.poly([(10.5, 4.8), (13, 4.4), (14.6, 5.6), (14, 7), (15.2, 8), (14, 9.2), (14.6, 10.4), (13, 11.6), (10.5, 11.2)],
           a, 0, normal=(0.3, 0, 1), lo=1, hi=3)
    c.ellipse(7, 8, 5.7, 4.8, b, 1, lo=1, hi=4, gloss=0.92)
    c.ellipse(6.8, 6.8, 5.1, 3.4, a, 2, lo=1, hi=4, gloss=0.92)
    c.poly([(5, 9), (7.5, 8), (7.5, 10.5)], a, 3, normal=(0.2, 0.3, 0.9), lo=1, hi=3)
    c.put(3, 7, "roe", 0)
    return c.render()


def icon_snow_crab():
    a = _fish2_m("#d27a3e")
    c = _fish2_canvas(_fish2_ink("#6a2e14", 0.26), a)
    for s in (-1, 1):
        for (y0, yk, xt, yt) in ((7.0, 4.6, 7.3, 6.6), (8.6, 8.2, 7.4, 11.2), (10.2, 11.8, 6.4, 15.0)):
            c.tube([(7.5 + s * 3.0, y0), (7.5 + s * 5.4, yk), (7.5 + s * xt, yt)], [0.62, 0.62, 0.5], a, 0, flat=0.9, lo=1, hi=3)
        c.tube([(7.5 + s * 2.0, 6.0), (7.5 + s * 3.2, 3.4), (7.5 + s * 2.4, 1.6)], [0.7, 0.75, 0.6], a, 1, flat=0.9, lo=2, hi=4, gloss=0)
    c.ellipse(7.5, 8.4, 3.6, 3.0, a, 2, lo=1, hi=4, gloss=0.85)
    c.dots([(6, 6), (9, 6)], "roe", 0)
    return c.render()


def icon_king_crab():
    a = _fish2_m("#b03438")
    c = _fish2_canvas(_fish2_ink("#4a0c14", 0.22), a)
    for s in (-1, 1):
        for pts in (((4.1, 7.4), (1.6, 6.4), (0.8, 9.6)), ((4.4, 9.8), (2.2, 11.6), (2.0, 14.8))):
            c.tube([(7.5 + s * (7.5 - x), y) for x, y in pts], [1.0, 0.95, 0.7], a, 0, flat=0.8, lo=1, hi=3)
    c.tube([(10.0, 5.6), (11.6, 3.8)], [0.9, 0.9], a, 1, lo=1, hi=3)
    c.ellipse(12.5, 2.4, 2.2, 1.7, a, 2, lo=1, hi=4, gloss=0.9)
    c.erase([(14, 1)])
    c.tube([(5.0, 5.6), (3.8, 4.0)], [0.75, 0.75], a, 1, lo=1, hi=3)
    c.ellipse(3.2, 2.8, 1.5, 1.3, a, 2, lo=1, hi=4, gloss=0.9)
    c.ellipse(7.5, 8.2, 3.8, 3.4, a, 3, lo=1, hi=4, gloss=0.92)
    c.dots([(7, 4), (8, 4), (3, 6), (12, 6), (4, 11), (11, 11)], a, 2, z=4)
    c.dots([(6, 6), (9, 6)], "roe", 0)
    return c.render()


def _fish2_shark(c, back, belly, snout=1.6, girth=2.3, fin=((4.0, 8.6), (6.0, 1.8), (7.0, 2.0), (7.0, 4.4), (8.6, 5.8)),
                 pect=((4.8, 11.6), (8.2, 14.8), (8.8, 14.0), (7.4, 9.8)), fin2=False):
    c.poly([(10.2, 5.2), (11.6, 3.2), (14.2, 0.3), (15.3, 0.7), (13.2, 4.4), (12.0, 5.8)], back, 0, normal=(0.35, -0.2, 1), lo=1, hi=3)
    c.poly([(11.6, 5.0), (14.9, 5.6), (14.4, 7.0), (11.2, 6.6)], back, 0, normal=(0.35, 0.3, 1), lo=1, hi=2)
    c.poly(list(fin), back, 1, normal=(-0.5, -0.4, 0.75), lo=1, hi=3)
    if fin2:
        c.poly([(9.0, 5.0), (9.8, 2.6), (10.6, 4.0)], back, 1, normal=(-0.3, -0.6, 0.75), lo=1, hi=3)
    c.poly(list(pect), back, 1, normal=(0.3, 0.5, 0.8), lo=1, hi=2)
    c.tube([(2.2, 13.0), (5.0, 10.2), (8.6, 6.8), (11.6, 4.2)], [snout, girth, girth - 0.3, 0.8], belly, 2, flat=0.9, lo=1, hi=4)
    c.tube([(1.9, 12.6), (4.6, 9.8), (8.2, 6.4), (11.3, 3.9)], [snout - 0.35, girth - 0.5, girth - 0.75, 0.5], back, 3,
           flat=0.9, lo=1, hi=3, gloss=0.95)


def icon_whale_shark():
    """고래상어: blue-grey with rows of white spots, a broad blunt head."""
    a = _fish2_m("#3e5c7a")
    b = _fish2_m("#dce4ea")
    c = _fish2_canvas(_fish2_ink("#1e3044", 0.22), a, b)
    _fish2_shark(c, a, b, snout=1.9, girth=2.4, fin=((4.0, 8.6), (5.6, 1.8), (6.8, 1.8), (7.0, 4.4), (8.8, 5.8)))
    _fish2_recolor(c, [(2, 11), (4, 9), (6, 7), (8, 5), (4, 11), (6, 9), (8, 7), (10, 5), (3, 13)], b, 4, only={a})
    c.put(2, 12, "roe", 0)
    return c.render()


def _fish2_line(x0, y0, x1, y1):
    n = max(abs(x1 - x0), abs(y1 - y0), 1)
    return [(round(x0 + (x1 - x0) * k / n), round(y0 + (y1 - y0) * k / n)) for k in range(n + 1)]


def icon_mandarin_fish():
    """쏘가리: golden olive with dark leopard blotches and a spiny dorsal fin."""
    a = _fish2_m("#b09a50")
    b = _fish2_m("#ecdfa4")
    d = _fish2_m("#4a3418", L=0.36)
    c = _fish2_canvas(_fish2_ink("#4a3a18", 0.25), a, b, d)
    _fish2_std(c, a, b, dorsal=False)
    _fish2_spiny(c, a, z=1)
    _fish2_recolor(c, [(4, 9), (5, 9), (6, 7), (7, 7), (8, 9), (9, 8), (5, 11), (6, 11), (9, 5), (10, 5), (7, 10), (3, 12)], d, 2)
    return c.render()


def icon_carp():
    """잉어: bronze back, gold belly and the carp's orange-red fins and tail (붕어 stays plain olive)."""
    a = _fish2_m("#94742e")
    b = _fish2_m("#e4c070")
    f = _fish2_m("#cc5a30")
    c = _fish2_canvas(_fish2_ink("#4a3210", 0.25), a, b, f)
    _fish2_std(c, a, b, fin=f)
    return c.render()


def icon_kkeokji():
    """꺽지: olive green perch with dark bars across the body and a spiny dorsal fin."""
    a = _fish2_m("#66784a")
    b = _fish2_m("#cad09a")
    c = _fish2_canvas(_fish2_ink("#28321a", 0.22), a, b)
    _fish2_std(c, a, b, dorsal=False)
    _fish2_spiny(c, a, z=1)
    _fish2_bands(c, (-6, -3, 0, 3), a, 1, only={a, b})
    return c.render()


def icon_atka_mackerel():
    """임연수어: sandy yellow with broad dark bands (the old icon's white bands stuck out of the body)."""
    a = _fish2_m("#7c7046")
    b = _fish2_m("#e4daa8")
    d = _fish2_m("#3e3a24", L=0.34)
    c = _fish2_canvas(_fish2_ink("#3a321a", 0.24), a, b, d)
    _fish2_std(c, a, b, stout=3.2)
    _fish2_bands(c, (-7, -6, -3, -2, 1, 2), d, 2, only={a, b})
    return c.render()


def icon_yellowfin_tuna():
    """황다랑어: dark blue back, silver belly (not yellow), a gold side stripe, long yellow sickle fins and finlets."""
    a = _fish2_m("#264a86")
    b = _fish2_m("#d6dee6")
    y = _fish2_m("#f0c430", L=0.80)
    c = _fish2_canvas(_fish2_ink("#14284a", 0.22), a, b, y)
    c.poly([(9.8, 6.2), (13.4, 0.8), (14.4, 1.0), (12.4, 6.4), (15.0, 9.2), (14.6, 10.2), (10.8, 8.4)], a, 0,
           normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(7.0, 5.6), (8.4, 2.4), (11.6, 0.6), (9.4, 4.2)], y, 1, normal=(-0.3, -0.7, 0.6), lo=2, hi=4)
    c.poly([(8.8, 9.6), (12.0, 10.0), (14.4, 12.8), (10.6, 11.0)], y, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=3)
    c.tube([(3.4, 12.4), (6.6, 9.2), (10.6, 5.4)], [2.1, 3.2, 1.6], b, 2, flat=0.9, lo=1, hi=4)
    c.tube([(2.9, 11.7), (6.0, 8.5), (10.0, 4.8)], [1.4, 2.3, 1.1], a, 3, flat=0.9, lo=1, hi=3)
    c.poly([(6, 12.6), (8.0, 14.4), (8.8, 12.4)], a, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    _fish2_recolor(c, [(4, 11), (5, 10), (6, 9), (7, 8), (8, 7), (9, 6)], y, 3)
    _fish2_recolor(c, [(11, 3), (12, 4)], y, 3)
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    return c.render()


def icon_sailfish():
    """돛새치: a slender blue body under a tall ribbed sail, and the spear of a bill."""
    a = _fish2_m("#2c5ca0")
    b = _fish2_m("#d4e0ec")
    s = _fish2_m("#3a44b0")
    ink = _fish2_ink("#101c48", 0.22)
    c = _fish2_canvas(ink, a, b, s)
    c.poly([(10.4, 5.6), (13.6, 0.8), (14.6, 1.0), (12.6, 5.8), (15.0, 8.4), (14.6, 9.4), (11.2, 7.6)], a, 0,
           normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(4.8, 10.0), (3.0, 6.4), (3.4, 3.4), (5.4, 1.6), (8.0, 1.2), (9.8, 2.4), (10.4, 5.6)], s, 1,
           normal=(-0.3, -0.6, 0.75), lo=2, hi=3)
    c.tube([(3.8, 12.2), (7.2, 8.8), (11.0, 5.2)], [1.6, 2.3, 1.2], b, 2, flat=0.9, lo=1, hi=4)
    c.tube([(3.4, 11.7), (6.8, 8.3), (10.6, 4.8)], [1.0, 1.5, 0.8], a, 3, flat=0.9, lo=1, hi=4)
    for (x0, y0, x1, y1) in ((5, 9, 3, 5), (6, 8, 5, 2), (8, 6, 8, 1), (9, 6, 10, 3)):
        _fish2_recolor(c, _fish2_line(x0, y0, x1, y1), s, 1, only={s})
    c.put(4, 11, "roe", 0)
    im = c.render()
    _fish2_paint(im, [(2, 13), (1, 14), (0, 15)], ink[0], 1)
    return im


def _fish2_catbody(c, a, b, forked=False):
    if forked:
        c.poly([(12.0, 3.8), (14.6, 0.4), (15.2, 2.4), (13.8, 3.8), (15.4, 5.2), (13.0, 5.4)], a, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    else:
        c.ellipse(13.6, 2.2, 1.3, 1.4, a, 0, lo=1, hi=3)
    c.poly([(8.4, 10.2), (13.0, 4.4), (14.0, 5.0), (9.8, 11.0)], a, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    c.tube([(5.8, 10.0), (8.2, 7.6), (11.0, 4.8), (13.4, 2.6)], [2.3, 1.9, 1.3, 0.7], b, 2, flat=0.9, lo=1, hi=3)
    c.tube([(5.4, 9.6), (7.8, 7.2), (10.6, 4.4), (13.0, 2.3)], [2.0, 1.5, 0.9, 0.4], a, 3, flat=0.9, lo=1, hi=4)
    c.ellipse(5.2, 10.6, 2.6, 2.2, a, 3, lo=1, hi=4)
    c.poly([(8.0, 6.6), (8.0, 4.6), (9.4, 5.6)], a, 1, normal=(-0.3, -0.6, 0.7), lo=1, hi=3)
    c.put(5, 9, "roe", 0)


def icon_catfish():
    """메기: dark olive, a wide flat head, a long whisker on each side of the mouth."""
    a = _fish2_m("#4c4a3a")
    b = _fish2_m("#a8a288")
    ink = _fish2_ink("#1e1e14", 0.20)
    c = _fish2_canvas(ink, a, b)
    _fish2_catbody(c, a, b)
    _fish2_recolor(c, [(7, 7), (9, 5), (11, 3)], a, 1, only={a})
    im = c.render()
    _fish2_paint(im, [(1, 10), (0, 9), (0, 8), (1, 12), (0, 13), (0, 14)], ink[0], 0)   # whiskers splayed from the snout
    return im


def icon_bagrid_catfish():
    """동자개: yellow with dark brown saddles, a forked tail, short whiskers."""
    a = _fish2_m("#b08a34")
    b = _fish2_m("#ecd890")
    d = _fish2_m("#4a3416", L=0.36)
    ink = _fish2_ink("#3a2a0c", 0.22)
    c = _fish2_canvas(ink, a, b, d)
    _fish2_catbody(c, a, b, forked=True)
    _fish2_recolor(c, [(5, 9), (6, 9), (5, 10), (8, 7), (9, 7), (9, 6), (11, 4), (12, 4), (11, 5)], d, 2, only={a, b})
    im = c.render()
    _fish2_paint(im, [(1, 10), (0, 9), (1, 12), (0, 13)], ink[0], 0)
    return im


def _fish2_glow(im, hexcol, alpha=110):
    """economy_fish's faint pale rim of light around glowing fish."""
    from PIL import Image
    px = im.load()
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    r, g, b = (int(hexcol[i:i + 2], 16) for i in (1, 3, 5))
    for y in range(16):
        for x in range(16):
            if px[x, y][3] == 0 and any(0 <= x + dx < 16 and 0 <= y + dy < 16 and px[x + dx, y + dy][3] > 0
                                        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                out.putpixel((x, y), (r, g, b, alpha))
    out.alpha_composite(im)
    return out


def icon_dragon_carp():
    """용잉어: bright gold with a red dragon crest and red fins, long whiskers, the glow of a legend
    (황금 붕어 is the plain glowing gold one)."""
    a = _fish2_m("#e0a020")
    b = _fish2_m("#fff0a0")
    f = _fish2_m("#e04a2a")
    c = _fish2_canvas(_fish2_ink("#6a3a08", 0.26), a, b, f)
    _fish2_std(c, a, b, fin=f, dorsal=False)
    _fish2_spiny(c, f, z=1, lo=1, hi=3)
    im = _fish2_glow(c.render(), "#fff0a0")
    _fish2_paint(im, [(0, 11), (0, 10), (1, 9), (1, 14), (2, 15), (3, 15)], f, 2)
    return im


def icon_sandfish():
    """도루묵: yellow-brown back with dark wavy marks over a silvery white belly (it read the same as 명태)."""
    a = _fish2_m("#8c7a52")
    b = _fish2_m("#eeeae2")
    d = _fish2_m("#4a3a22", L=0.36)
    c = _fish2_canvas(_fish2_ink("#3e3220", 0.25), a, b, d)
    _fish2_std(c, a, b)
    _fish2_recolor(c, [(4, 9), (5, 8), (6, 9), (7, 6), (8, 7), (9, 6), (9, 4), (10, 5)], d, 2, only={a})
    return c.render()


def icon_pale_chub():
    """피라미: silver-blue with the orange-red fins of the breeding male (it read the same as 빙어)."""
    a = _fish2_m("#6e8c9c")
    b = _fish2_m("#e8eef2")
    f = _fish2_m("#e8704e")
    c = _fish2_canvas(_fish2_ink("#2a3a48", 0.25), a, b, f)
    _fish2_std(c, a, b, fin=f, stout=3.2)
    return c.render()


# ------------------------------------------------------------------------------------------------
# 민물 2 · 갯벌 · 열대: 열목어 ~ 엔젤피시
# ------------------------------------------------------------------------------------------------


def _fish3_m(hexcol, L=None, C=None, span=0.34):
    """A material from a colour: its lightness (or L) in the middle of the ramp, its chroma (or C)."""
    name = f"f3_{hexcol[1:]}_{L}_{C}_{span}"
    if name not in _fish3_P.MATERIALS:
        l, ch, h = _fish3_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        _fish3_P.MATERIALS[name] = (h, ch / 1.5 if C is None else C, min(0.9, max(0.3, l)) if L is None else L,
                                    span, 5, False)
    return name


def _fish3_ink(hexcol, L=0.26, C=None):
    """A dark outline (material, step 0) in the fish's own hue."""
    name = f"f3ink_{hexcol[1:]}_{int(L * 100)}"
    if name not in _fish3_P.MATERIALS:
        l, ch, h = _fish3_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        _fish3_P.MATERIALS[name] = (h, min(ch / 1.5, 0.06) if C is None else C, L + 0.272, 0.34, 5, False)
    return (name, 0)


def _fish3_canvas(ink, *mats):
    c = Canvas()
    for m in mats:
        c.ink[m] = ink
    return c


def _fish3_paint(im, pts, mat, step):
    """Pixels painted after the outline pass (whiskers, threads): they are their own outline."""
    col = _fish3_px.color(mat, step)
    for x, y in pts:
        if 0 <= x < 16 and 0 <= y < 16:
            im.putpixel((x, y), col)
    return im


def _fish3_recolor(c, pts, mat, step, only=None):
    """Recolours body pixels only, so a pattern never sticks out of the silhouette."""
    for p in pts:
        v = c.px.get(p)
        if v and v[2] < 90 and (only is None or v[0] in only):
            v[0], v[1] = mat, step
    return c


def _fish3_fan(cx, cy, r, ang, half, n=8):
    """A fan (round tail, sail fin) as a polygon: from (cx, cy), radius r, centred on angle ang (degrees,
    screen coordinates: 0 right, -90 up), opening +-half."""
    pts = [(cx, cy)]
    for k in range(n + 1):
        t = _fish3_math.radians(ang - half + 2 * half * k / n)
        pts.append((cx + r * _fish3_math.cos(t), cy + r * _fish3_math.sin(t)))
    return pts


def _fish3_part(c, rows, chars, mat, z, lo=1, hi=4, k=1.3, gloss=0.0, bias=0.0):
    """A hand-drawn part: the cells of rows whose character is in chars, shaded like a soft pillow (each
    cell faces away from the part's middle, so the part is lit from the top-left like the toolkit's shapes)."""
    cells = {(x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch in chars}
    out = [(x, y) for x in range(-1, 17) for y in range(-1, 17) if (x, y) not in cells]
    d = {p: min(_fish3_math.hypot(p[0] - q[0], p[1] - q[1]) for q in out) for p in cells}
    for (x, y) in cells:
        gx = d.get((x + 1, y), 0) - d.get((x - 1, y), 0)
        gy = d.get((x, y + 1), 0) - d.get((x, y - 1), 0)
        c.put(x, y, mat, _fish3_px.shade_step((-gx, -gy, k), lo, hi, gloss, bias), z)
    return c


def _fish3_body(c, a, b, pts=((3.4, 12.4), (6.6, 9.2), (10.4, 5.4)), belly=(2.2, 3.6, 2.1), back=(1.4, 2.4, 1.3),
                shift=(-0.5, -0.7), gloss=0.93):
    """The set's standard diagonal body: a belly tube with the back tube lying on its upper-left half (only
    the back gets a wet glint, and only on its brightest pixel or two)."""
    c.tube(list(pts), list(belly), b, 2, flat=0.9, lo=1, hi=4, gloss=0)
    c.tube([(x + shift[0], y + shift[1]) for x, y in pts], list(back), a, 3, flat=0.9, lo=1, hi=4, gloss=gloss)
    return c


def icon_masu_salmon():
    """산천어: a narrow olive back over pinkish silver flanks crossed by the dark slate parr marks that tell it
    from every other trout (송어 next to it is the same body, plain)."""
    a, b = _fish3_m("#5e7052"), _fish3_m("#e2c6cc")
    mk = _fish3_m("#3e4258", L=0.4)
    c = _fish3_canvas(_fish3_ink("#2c3a28", 0.24), a, b)
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], a, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(4.5, 8.5), (7.5, 4), (9.5, 4.5), (6, 9)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    _fish3_body(c, a, b, back=(1.1, 1.8, 1.0), shift=(-0.8, -1.0))
    c.poly([(6, 12.6), (8.5, 14.5), (9.4, 12.4)], a, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    _fish3_recolor(c, [(x + k, y + k) for x, y in ((4, 9), (6, 7), (8, 5)) for k in range(3)], mk, 2)
    _fish3_recolor(c, [(4, 9), (6, 7), (8, 5)], mk, 3)
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    return c.render()


def icon_taimen():
    """타이멘: the legend of the cold rivers: a long, heavy salmonid with a broad head, an olive back peppered
    with small black spots over coppery flanks, and a blood-red tail and fins."""
    a, b = _fish3_m("#5a5636"), _fish3_m("#d08a62")
    red = _fish3_m("#c8342a", C=0.13)
    c = _fish3_canvas(_fish3_ink("#2c1c10", 0.2), a, b, red)
    c.poly([(9.8, 6.2), (14.4, 0.2), (15.2, 4.6), (13.0, 6.8), (15.8, 9.6), (11.2, 8.4)], red, 0,
           normal=(0.35, 0.1, 1), lo=1, hi=4)
    c.poly([(4.2, 8.6), (6.8, 4.4), (8.8, 5.0), (5.8, 9.2)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.poly([(9.8, 4.4), (10.8, 3.2), (11.4, 4.4)], red, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.poly([(5.6, 12.8), (8.0, 15.0), (9.2, 12.6)], red, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=3)
    c.poly([(9.4, 10.0), (11.4, 11.8), (11.8, 9.0)], red, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=3)
    _fish3_body(c, a, b, [(2.6, 13.0), (6.2, 9.4), (10.6, 5.6)], belly=(2.5, 3.8, 2.2), back=(1.5, 2.3, 1.2),
                shift=(-0.8, -0.9))
    _fish3_recolor(c, [(5, 8), (7, 6), (9, 5), (6, 10), (8, 8)], _fish3_m("#2a2418", L=0.3), 1)
    c.put(2, 11, "roe", 0).put(3, 10, "snow", 2)
    return c.render()


def icon_swamp_eel():
    """드렁허리: a finless, snake-like eel bent in an S, golden brown with dark speckles, tapering to a point."""
    a, b = _fish3_m("#7e5a24"), _fish3_m("#dcb060")
    sp = _fish3_m("#3e2a10", L=0.38)
    c = _fish3_canvas(_fish3_ink("#40280c", 0.22), a, b)
    pts = [(1.2, 12.2), (3.8, 12.8), (6.2, 11.6), (7.8, 9.0), (8.8, 6.4), (10.8, 4.0), (13.2, 3.0), (15.4, 2.8)]
    c.tube(pts, [1.5, 1.6, 1.7, 1.7, 1.6, 1.4, 0.9, 0.4], b, 1, flat=0.95, lo=1, hi=4, gloss=0)
    c.tube([(x - 0.4, y - 0.55) for x, y in pts], [1.0, 1.1, 1.1, 1.1, 1.0, 0.9, 0.6, 0.25], a, 2, flat=0.95, lo=1,
           hi=4, gloss=0.95)
    _fish3_recolor(c, [(4, 12), (6, 11), (8, 8), (9, 6), (11, 4), (13, 3), (7, 10)], sp, 2)
    c.put(1, 11, "roe", 0)
    return c.render()


def icon_eel():
    """뱀장어: long and sleek in a gentle wave, black-green back over a pale yellow belly, a small pectoral fin
    behind the head and the fin fringe that runs round its pointed tail."""
    a, b = _fish3_m("#2e3c2a"), _fish3_m("#d8d49c")
    fin = _fish3_m("#5e6a40")
    c = _fish3_canvas(_fish3_ink("#121a0e", 0.2), a, b, fin)
    pts = [(1.4, 14.2), (3.8, 11.8), (6.8, 9.8), (9.4, 6.6), (12.2, 4.0), (14.8, 1.0)]
    c.tube(pts[2:], [1.7, 2.4, 2.1, 0.8], fin, 0, flat=0.9, lo=1, hi=3, gloss=0)
    c.tube(pts, [1.1, 1.6, 1.7, 1.6, 1.0, 0.3], b, 1, flat=0.95, lo=1, hi=4, gloss=0)
    c.tube([(x - 0.45, y - 0.55) for x, y in pts], [0.75, 1.1, 1.15, 1.1, 0.65, 0.2], a, 2, flat=0.95, lo=1, hi=4,
           gloss=0.9)
    c.poly([(4.4, 12.2), (6.6, 13.8), (6.4, 11.4)], fin, 3, normal=(0.4, 0.6, 0.7), lo=1, hi=3)
    c.put(2, 12, "roe", 0)
    return c.render()


def _fish3_snakehead(c, a, b, fin, pts, belly, back, tail=4.4):
    """가물치 body: a thick cylinder, one long dorsal fin and one long anal fin, a round tail fan."""
    c.poly(_fish3_fan(pts[-1][0], pts[-1][1] + 0.3, tail, -45, 60), fin, 0, normal=(0.3, -0.2, 1), lo=1, hi=3)
    (x1, y1), (x2, y2) = pts[1], pts[-1]
    r1, r2 = belly[1] + 1.1, belly[-1] + 1.0
    c.poly([(x1, y1), (x1 - r1 * 0.71, y1 - r1 * 0.71), (x2 - r2 * 0.71, y2 - r2 * 0.71), (x2, y2)], fin, 0,
           normal=(-0.3, -0.6, 0.75), lo=1, hi=3)
    xm, ym = (x1 + x2) / 2 - 0.4, (y1 + y2) / 2 + 0.4
    c.poly([(xm, ym), (xm + r1 * 0.66, ym + r1 * 0.66), (x2 + r2 * 0.71, y2 + r2 * 0.71), (x2, y2)], fin, 0,
           normal=(0.3, 0.5, 0.8), lo=1, hi=2)
    _fish3_body(c, a, b, pts, belly, back, shift=(-0.5, -0.6))
    return c


def icon_snakehead():
    """가물치: a thick olive cylinder with black python blotches, a dorsal fin along the whole back, a round
    tail and the flat snake-like head."""
    a, b = _fish3_m("#5a5636"), _fish3_m("#b4ac80")
    fin = _fish3_m("#4a4630")
    blot = _fish3_m("#26241a", L=0.32)
    c = _fish3_canvas(_fish3_ink("#1a1a0e", 0.2), a, b, fin)
    _fish3_snakehead(c, a, b, fin, [(2.2, 13.2), (4.8, 10.6), (8.6, 6.8), (11.4, 4.0)], (1.9, 2.5, 2.4, 1.4),
                     (1.3, 1.7, 1.6, 0.8))
    _fish3_recolor(c, [(4, 10), (5, 9), (6, 9), (7, 7), (8, 6), (9, 6), (10, 4), (6, 11), (8, 9), (9, 8)], blot, 1)
    c.put(2, 12, "roe", 0).put(3, 11, "snow", 3)
    return c.render()


def icon_giant_snakehead():
    """거대 가물치: the swamp legend: a huge near-black snakehead with a fiery orange stripe from the eye to the
    tail, a pale jaw line and an ember eye."""
    a, b = _fish3_m("#2a2e2c"), _fish3_m("#646a5a")
    fin = _fish3_m("#22261f")
    st = _fish3_m("#ec6a1c", C=0.15)
    c = _fish3_canvas(_fish3_ink("#0e100c", 0.16), a, b, fin)
    _fish3_snakehead(c, a, b, fin, [(2.4, 13.0), (5.0, 10.4), (8.8, 6.6), (12.0, 3.6)], (2.2, 2.9, 2.7, 1.6),
                     (1.4, 1.9, 1.8, 1.0))
    _fish3_recolor(c, [(4, 10), (5, 9), (6, 8), (7, 7), (8, 6), (9, 5), (10, 4)], st, 3)
    c.dots([(1, 13), (2, 13)], b, 3, z=6)
    c.put(2, 12, st, 4).put(3, 12, "roe", 0)
    return c.render()


def icon_softshell_turtle():
    """자라 from above: a flat round leathery shell, darker in the middle with a few dark freckles and a
    paler soft rim; the pointed head with its snorkel snout, four pale flippers."""
    sh, rim = _fish3_m("#56663a"), _fish3_m("#7c8a50")
    skin = _fish3_m("#ccc49a")
    c = _fish3_canvas(_fish3_ink("#262c14", 0.22), sh, rim)
    c.ink[skin] = _fish3_ink("#5a5030", 0.3)
    for (px, py) in ((2.2, 5.2), (12.8, 5.2), (2.6, 12.6), (12.4, 12.6)):
        c.ellipse(px, py, 1.5, 1.25, skin, 0, lo=1, hi=3, gloss=0)
    c.ellipse(7.5, 2.8, 1.5, 1.9, skin, 0, lo=1, hi=4, gloss=0)
    c.put(7, 0, skin, 3, z=0)
    c.ellipse(7.5, 8.6, 5.7, 5.1, rim, 1, lo=1, hi=4, gloss=0)
    c.ellipse(7.4, 8.4, 4.5, 3.9, sh, 2, lo=1, hi=4, gloss=0.9)
    _fish3_recolor(c, [(6, 9), (9, 7), (9, 10), (5, 6)], sh, 1)
    c.dots([(6, 2), (8, 2)], "roe", 0)
    return c.render()


def icon_mullet():
    """숭어: a blunt-headed silver torpedo, two small separate dorsal fins, a forked tail and the dark lines
    running along its flanks."""
    a, b = _fish3_m("#4c5e6e"), _fish3_m("#e2e8ec")
    ln = _fish3_m("#7a8a98", L=0.6)
    c = _fish3_canvas(_fish3_ink("#1e2a36", 0.24), a, b)
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], a, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(5.6, 7.6), (6.4, 5.0), (7.8, 6.0)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.poly([(8.6, 5.0), (9.4, 2.8), (10.6, 3.8)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    _fish3_body(c, a, b, belly=(2.3, 3.2, 1.9), back=(1.6, 2.2, 1.2))
    c.poly([(6, 12.6), (8.5, 14.5), (9.4, 12.4)], a, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    _fish3_recolor(c, [(5, 11), (6, 10), (7, 9), (8, 8), (9, 7), (10, 6), (6, 12), (7, 11), (8, 10), (9, 9)], ln, 2,
                   only={b})
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    return c.render()


def icon_barramundi():
    """바라문디: a big silver perch: pointed head with a dipped forehead and wide jaw, spiny and soft dorsal
    fins, a broad round tail, the glowing amber eye."""
    a, b = _fish3_m("#56665e"), _fish3_m("#e4ecea")
    fin = _fish3_m("#4c5a52")
    eye = _fish3_m("#e87830", C=0.14)
    c = _fish3_canvas(_fish3_ink("#1c2620", 0.22), a, b, fin)
    c.poly(_fish3_fan(11.4, 4.4, 4.4, -45, 60), fin, 0, normal=(0.3, -0.2, 1), lo=1, hi=3)
    c.poly([(4.6, 8.6), (4.2, 5.6), (5.8, 5.8), (5.8, 4.2), (7.4, 4.6), (7.8, 3.0), (9.2, 4.6), (6.4, 8.8)], fin, 1,
           normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.poly([(9.0, 5.8), (9.6, 2.4), (11.8, 2.6), (10.8, 5.6)], fin, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    pts = [(1.6, 13.8), (4.8, 10.6), (8.6, 7.0), (11.4, 4.4)]
    _fish3_body(c, a, b, pts, belly=(1.4, 3.1, 3.0, 1.4), back=(0.7, 2.1, 1.9, 0.8), shift=(-0.4, -0.7))
    c.poly([(6, 12.8), (8.4, 14.8), (9.2, 12.2)], fin, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    c.line(1, 14, 3, 13, fin, 1, z=6)
    c.put(3, 11, eye, 3).put(3, 12, "roe", 0)
    return c.render()


def icon_mud_crab():
    """머드크랩 from above: a broad smooth dark olive shell, two massive claws held in front with dark pincer
    tips, walking legs and the flat swimming paddles at the back."""
    a = _fish3_m("#56663a")
    tip = _fish3_m("#3a3222", L=0.36)
    c = _fish3_canvas(_fish3_ink("#18200c", 0.2), a, tip)
    for s in (-1, 1):
        X = lambda x: 7.5 + s * (x - 7.5)
        for pts in (((11.6, 8.0), (14.0, 8.2), (15.0, 10.4)), ((11.4, 10.0), (13.4, 11.4), (13.8, 13.6))):
            c.tube([(X(x), y) for x, y in pts], [0.75, 0.7, 0.55], a, 0, flat=0.9, lo=1, hi=3, gloss=0)
        c.tube([(X(10.2), 11.6), (X(11.4), 13.6)], [0.7, 0.6], a, 0, flat=0.9, lo=1, hi=3, gloss=0)
        c.ellipse(X(11.8), 14.4, 1.3, 0.9, a, 0, lo=1, hi=3, gloss=0)
        c.tube([(X(10.6), 6.8), (X(12.2), 4.6)], [0.9, 1.0], a, 1, lo=1, hi=3, gloss=0)
        c.tube([(X(10.6), 1.6), (X(9.4), 1.6)], [0.75, 0.6], a, 2, lo=1, hi=3, gloss=0)
        c.tube([(X(10.6), 3.8), (X(9.6), 3.6)], [0.7, 0.55], a, 2, lo=1, hi=3, gloss=0)
        c.dots([(round(X(9.4) - 0.5), 1), (round(X(9.6) - 0.5), 3)], tip, 2)
        c.ellipse(X(12.6), 2.8, 2.3, 1.9, a, 3, lo=1, hi=4, gloss=0.9)
    c.ellipse(7.5, 9.0, 5.4, 3.5, a, 4, lo=1, hi=4, gloss=0.93)
    c.dots([(6, 6), (9, 6)], "roe", 0)
    return c.render()


def icon_paradise_fish():
    """버들붕어 side on, head left: a small olive-brown fish with dark bars, long pointed dorsal and anal fins
    trailing back past a lyre tail, its fins red tipped in blue."""
    a, b = _fish3_m("#76723e"), _fish3_m("#c8c290")
    fin = _fish3_m("#b8563a", C=0.09)
    edge = _fish3_m("#4a9ad8", L=0.66, C=0.11)
    bar = _fish3_m("#4a4626", L=0.4)
    c = _fish3_canvas(_fish3_ink("#2a1c10", 0.2), a, b, fin, edge)
    c.poly([(10.4, 8.0), (15.6, 3.6), (14.0, 8.0), (15.6, 12.4)], fin, 0, normal=(0.4, 0, 1), lo=1, hi=4)
    c.poly([(4.6, 5.8), (8.8, 4.0), (13.8, 1.0), (11.0, 6.6)], fin, 1, normal=(-0.2, -0.6, 0.8), lo=1, hi=4)
    c.poly([(4.6, 10.2), (8.8, 12.0), (13.8, 15.0), (11.0, 9.4)], fin, 1, normal=(0.2, 0.6, 0.8), lo=1, hi=3)
    c.tube([(1.6, 8.0), (5.2, 8.0), (11.2, 8.0)], [1.6, 3.2, 1.3], a, 2, flat=0.9, lo=1, hi=4, gloss=0.93)
    body = set(c.last())
    _fish3_recolor(c, [(x, y) for (x, y) in body if (x, y + 1) not in body or (x, y + 2) not in body and x < 9], b, 2)
    _fish3_recolor(c, [(x, y) for (x, y) in body if x in (5, 7, 9) and 0 < y - 5 < 6], bar, 2)
    c.dots([(13, 1), (12, 2), (15, 4), (15, 11), (13, 14), (12, 13)], edge, 3, z=6)
    c.put(2, 7, "roe", 0).put(3, 8, edge, 2)
    return c.render()


def icon_angelfish():
    """엔젤피시: side on, a tall silver diamond swept up and down into long dorsal and anal fins, black bars
    through the eye, the middle and the tail root, thread-like ventral fins."""
    sv, fin = _fish3_m("#e4e0cc"), _fish3_m("#c8c4b0")
    bar = _fish3_m("#303034", L=0.34)
    c = _fish3_canvas(_fish3_ink("#2a2a26", 0.22), sv, fin, bar)
    rows = [
        "...........d....",
        "..........dd....",
        ".........ddd....",
        "........ddd.....",
        "......bbddd.....",
        ".....bbbbdd...t.",
        "....bbbbbbb..tt.",
        "..bbbbbbbbbbttt.",
        ".bbbbbbbbbbbttt.",
        "..bbbbbbbbbbttt.",
        "....bbbbbbb..tt.",
        ".....bbbbdd...t.",
        "......bbddd.....",
        "........ddd.....",
        ".........ddd....",
        "..........dd....",
    ]
    _fish3_part(c, rows, "dt", fin, 1, lo=1, hi=4, k=0.9)
    _fish3_part(c, rows, "b", sv, 2, lo=1, hi=4, k=1.6)
    _fish3_recolor(c, [(4, y) for y in range(6, 11)] + [(7, y) for y in range(4, 13)] +
                   [(8, 3), (9, 2), (10, 1), (8, 13), (9, 14), (10, 15)] + [(10, y) for y in range(6, 11)], bar, 1)
    _fish3_recolor(c, [(4, 6), (7, 4), (8, 3)], bar, 2)
    c.put(3, 7, "roe", 0).put(2, 7, "snow", 3)
    im = c.render()
    return _fish3_paint(im, [(5, 13), (5, 14), (4, 15)], fin, 2)


# ------------------------------------------------------------------------------------------------
# 열대 · 동굴 · 버섯섬 · 특별: 피라냐 ~ 달빛 금붕어
# ------------------------------------------------------------------------------------------------


def _fish4_m(hexcol, L=None, C=None, span=0.34):
    """A material from a colour: its lightness (or L) in the middle of the ramp, its chroma (or C)."""
    name = f"f4_{hexcol[1:]}_{L}_{C}_{span}"
    if name not in _fish4_P.MATERIALS:
        l, ch, h = _fish4_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        _fish4_P.MATERIALS[name] = (h, ch / 1.5 if C is None else C, min(0.9, max(0.3, l)) if L is None else L,
                                    span, 5, False)
    return name


def _fish4_ink(hexcol, L=0.26, C=None):
    """A dark outline (material, step 0) in the fish's own hue."""
    name = f"f4ink_{hexcol[1:]}_{int(L * 100)}"
    if name not in _fish4_P.MATERIALS:
        l, ch, h = _fish4_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        _fish4_P.MATERIALS[name] = (h, min(ch / 1.5, 0.06) if C is None else C, L + 0.272, 0.34, 5, False)
    return (name, 0)


def _fish4_canvas(ink, *mats):
    c = Canvas()
    for m in mats:
        c.ink[m] = ink
    return c


def _fish4_paint(im, pts, mat, step, alpha=255):
    """Pixels painted after the outline pass (whiskers, barbels, teeth): they are their own outline."""
    col = _fish4_px.color(mat, step)
    for x, y in pts:
        if 0 <= x < 16 and 0 <= y < 16:
            im.putpixel((x, y), col[:3] + (alpha,))
    return im


def _fish4_recolor(c, pts, mat, step=None, only=None, delta=0):
    """Recolours body pixels only, so a pattern never sticks out of the silhouette; step None keeps the
    pixel's shading (plus delta)."""
    for p in pts:
        v = c.px.get(p)
        if v and v[2] < 90 and (only is None or v[0] in only):
            v[0] = mat
            v[1] = max(0, min(5, (v[1] if step is None else step) + delta))
    return c


def _fish4_glow(im, hexcol, alpha=110):
    """The generator's glow for glowing fish: a faint pale rim of light around the outline."""
    px = im.load()
    r, g, b = (int(hexcol[i:i + 2], 16) for i in (1, 3, 5))
    out = _fish4_Image.new("RGBA", im.size, (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            if px[x, y][3] == 0 and any(0 <= x + dx < 16 and 0 <= y + dy < 16 and px[x + dx, y + dy][3] > 0
                                        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                out.putpixel((x, y), (r, g, b, alpha))
    out.alpha_composite(im)
    return out


def _fish4_fan(cx, cy, r, ang, half, n=8):
    """A fan (round tail, veil fin) as a polygon: from (cx, cy), radius r, centred on angle ang (degrees,
    screen coordinates: 0 right, -90 up), opening +-half."""
    pts = [(cx, cy)]
    for k in range(n + 1):
        t = _fish4_math.radians(ang - half + 2 * half * k / n)
        pts.append((cx + r * _fish4_math.cos(t), cy + r * _fish4_math.sin(t)))
    return pts


def _fish4_body(c, a, b, pts=((3.4, 12.4), (6.6, 9.2), (10.4, 5.4)), belly=(2.2, 3.6, 2.1), back=(1.4, 2.4, 1.3),
                shift=(-0.5, -0.7), gloss=0.93, z=2):
    """The set's standard diagonal body: a belly tube with the back tube lying on its upper-left half (only
    the back gets a wet glint, and only on its brightest pixel or two)."""
    c.tube(list(pts), list(belly), b, z, flat=0.9, lo=1, hi=4, gloss=0)
    c.tube([(x + shift[0], y + shift[1]) for x, y in pts], list(back), a, z + 1, flat=0.9, lo=1, hi=4, gloss=gloss)
    return c


def _fish4_std_fins(c, fin, tail=None, dorsal=True, pelvic=True):
    """The standard fish's tail, dorsal and pelvic fins (economy_fish._fish)."""
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], tail or fin, 0,
           normal=(0.35, 0.1, 1), lo=1, hi=3)
    if dorsal:
        c.poly([(4.5, 8.5), (7.5, 4), (9.5, 4.5), (6, 9)], fin, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    if pelvic:
        c.poly([(6, 12.6), (8.5, 14.5), (9.4, 12.4)], fin, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    return c


def _fish4_over(c, rows, key, z=50):
    """Hand-placed pixels over the shapes: rows of characters, '.' leaves a pixel alone, key maps the
    others to (material, step), or to None to cut the pixel away."""
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            v = key[ch]
            if v is None:
                c.erase([(x, y)])
            else:
                c.put(x, y, v[0], v[1], z)
    return c


def icon_piranha():
    """피라냐: side on (deep-bodied), silver-grey back, the blood-red throat and belly, a forked grey tail and
    the jutting underbite with two rows of white teeth."""
    s, red = _fish4_m("#7e8a96", L=0.62), _fish4_m("#d63c2c", C=0.13)
    fin = _fish4_m("#6a7078")
    ink = _fish4_ink("#1e2228", 0.2)
    c = _fish4_canvas(ink, s, red, fin)
    k = {"1": (s, 1), "2": (s, 2), "3": (s, 3), "4": (s, 4), "5": (s, 5),
         "a": (red, 1), "b": (red, 2), "c": (red, 3), "d": (red, 4),
         "f": (fin, 1), "g": (fin, 2), "h": (fin, 3), "W": ("snow", 4), "k": ink, "e": ("roe", 0), "i": (red, 4)}
    _fish4_over(c, ["................",
                    ".......gh.......",
                    ".....4433g......",
                    "....443332.....h",
                    "...44333222...hg",
                    "..44e3322221.hgg",
                    "..44i32222211gf.",
                    ".4533222222211f.",
                    ".W3W22222222211.",
                    "kkkkbb2222221gf.",
                    "dWdWcbbbb2211hgg",
                    ".ddccbbbbba1..hg",
                    "..dccbbbaaa.....",
                    "....cbbaa.ba....",
                    "..........a.....",
                    "................"], k)
    return c.render()


def icon_arowana():
    """아로와나: the jungle legend: a long, blade-like golden dragon fish, the body deepening towards the tail
    where the long dorsal and anal fins fan out beside it, a long pectoral fin, the steep upturned jaw with
    its two chin barbels."""
    a, b = _fish4_m("#d8a02a", C=0.13), _fish4_m("#f8e08a", C=0.11)
    fin = _fish4_m("#e0802a", C=0.13)
    ink = _fish4_ink("#4a2406", 0.22)
    c = _fish4_canvas(ink, a, b, fin)
    c.poly(_fish4_fan(12.4, 3.6, 3.4, -45, 58), fin, 0, normal=(0.3, -0.2, 1), lo=1, hi=4)
    c.poly([(6.6, 6.6), (8.6, 2.8), (11.0, 1.2), (11.2, 3.8)], fin, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=4)
    c.poly([(9.4, 9.6), (12.8, 8.0), (14.8, 5.0), (12.2, 5.0)], fin, 1, normal=(0.4, 0.6, 0.7), lo=2, hi=4)
    c.poly([(3.6, 12.8), (4.4, 15.4), (6.0, 12.0)], fin, 1, normal=(0.4, 0.6, 0.7), lo=2, hi=4)
    pts = [(1.6, 13.6), (4.0, 11.2), (7.6, 7.6), (11.2, 4.2)]
    _fish4_body(c, a, b, pts, belly=(0.9, 1.9, 2.4, 1.7), back=(0.6, 1.2, 1.6, 0.9), shift=(-0.45, -0.6))
    _fish4_recolor(c, [(5, 9), (7, 7), (9, 5), (6, 10), (8, 8), (10, 6)], a, None, only={a, b}, delta=-1)
    c.put(2, 12, "roe", 0).put(3, 11, "snow", 3)
    im = c.render()
    return _fish4_paint(im, [(0, 15), (1, 15)], ink[0], 0)


def icon_arapaima():
    """피라루쿠: the giant of the Amazon: a thick olive-grey torpedo whose scales turn blood red towards the
    tail, dorsal and anal fins set far back by the round red tail."""
    a, b = _fish4_m("#4e5646"), _fish4_m("#a4a48e")
    red = _fish4_m("#c8382a", C=0.13)
    fin = _fish4_m("#8a2a20")
    c = _fish4_canvas(_fish4_ink("#181c12", 0.2), a, b, red, fin)
    c.poly(_fish4_fan(12.4, 3.6, 3.6, -45, 75), red, 0, normal=(0.3, -0.2, 1), lo=1, hi=3)
    c.poly([(7.6, 5.6), (9.6, 2.2), (12.0, 1.0), (11.4, 3.6)], fin, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.poly([(10.4, 8.4), (13.8, 6.4), (15.0, 4.0), (12.4, 4.4)], fin, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=3)
    pts = [(2.2, 13.2), (4.8, 10.6), (8.4, 7.0), (11.6, 4.0)]
    _fish4_body(c, a, b, pts, belly=(2.0, 3.1, 3.0, 1.8), back=(1.4, 2.2, 2.1, 1.0), shift=(-0.5, -0.6))
    _fish4_recolor(c, [(x, y) for x in range(16) for y in range(16) if x - y >= 1 or (x - y >= -3 and x + y >= 17)],
                   red, None, only={a, b})
    _fish4_recolor(c, [(x, y) for x in range(16) for y in range(16) if x - y == -1 and x + y < 17 and x + y > 13], red, None, only={a})
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    return c.render()


def icon_cavefish():
    """장님물고기: blind: no eye at all, pinkish white like an albino tetra with a rose outline, the red gill
    showing through the pale skin behind the head."""
    a, b = _fish4_m("#dcb8bc"), _fish4_m("#f4e6e4")
    fin = _fish4_m("#e2bcc0")
    gill = _fish4_m("#e0566a", C=0.12)
    ink = _fish4_ink("#6a3040", 0.3)
    c = _fish4_canvas(ink, a, b, fin, gill)
    _fish4_std_fins(c, fin)
    _fish4_body(c, a, b, belly=(2.1, 3.3, 2.0), back=(1.4, 2.2, 1.2))
    _fish4_recolor(c, [(4, 9), (5, 10), (6, 11), (6, 12)], gill, 2, only={a, b})
    c.put(3, 11, a, 2)
    return c.render()


def icon_cave_shrimp():
    """동굴 새우: side on, a pale translucent pink shrimp: round carapace with a spiky rostrum, banded tail
    curling down to a fan, thin legs and long antennae sweeping back over the body."""
    a = _fish4_m("#ecd0cc", C=0.045)
    org = _fish4_m("#e88a6a", C=0.1)
    ink = _fish4_ink("#7a3436", 0.28)
    c = _fish4_canvas(ink, a, org)
    c.poly([(11.4, 11.4), (9.4, 14.6), (12.2, 14.4), (14.6, 13.2), (13.8, 11.0)], a, 0, normal=(0.3, 0.3, 1), lo=1, hi=3)
    c.tube([(6.0, 7.6), (9.4, 6.6), (12.0, 8.0), (12.8, 11.0)], [2.3, 2.0, 1.7, 1.2], a, 1, flat=0.9, lo=1, hi=4, gloss=0.9)
    c.ellipse(4.4, 8.6, 3.0, 2.3, a, 2, lo=1, hi=4, gloss=0.9)
    c.poly([(1.6, 7.4), (0.4, 7.4), (3.0, 6.6)], a, 2, normal=(0, -0.4, 1), lo=2, hi=3)
    # segment bands across the tail
    _fish4_recolor(c, [(8, 5), (8, 6), (8, 7), (8, 8), (10, 5), (10, 6), (10, 7), (10, 8), (12, 7), (11, 8), (13, 8),
                       (12, 9), (13, 9), (11, 9)], a, None, delta=-1)
    c.put(3, 8, "roe", 0)
    im = c.render()
    leg = _fish4_m("#c08480")
    _fish4_paint(im, [(4, 11), (3, 12), (6, 11), (6, 12), (8, 10), (8, 11), (10, 10)], leg, 1)
    _fish4_paint(im, [(2, 6), (3, 5), (4, 4), (6, 3), (5, 3), (7, 2), (8, 2), (9, 2), (10, 2), (11, 3)], leg, 1)
    _fish4_paint(im, [(1, 9), (0, 10)], leg, 1)
    return im


def icon_cave_crayfish():
    """동굴 가재: from above, a ghost-white albino crayfish: two big split pincers up front, a carapace
    with a pointed rostrum, walking legs on both sides, a banded tail and its tail fan, antennae between the claws."""
    a = _fish4_m("#cfdce6", C=0.025)
    cl = _fish4_m("#c4d6e6", C=0.04)
    ink = _fish4_ink("#34465a", 0.26)
    c = _fish4_canvas(ink, a, cl)
    for s in (-1, 1):
        def X(x):
            return 7.5 + s * (x - 7.5)
        c.tube([(X(4.0), 5.4), (X(6.0), 7.6)], [0.9, 0.9], cl, 1, flat=0.9, lo=1, hi=3)
        c.ellipse(X(2.9), 3.4, 2.0, 2.9, cl, 2, lo=1, hi=4, gloss=0.9)
    c.erase([(2, 0), (2, 1), (2, 2), (13, 0), (13, 1), (13, 2)])
    c.ellipse(7.5, 8.6, 2.3, 2.7, a, 3, lo=1, hi=4, gloss=0.9)
    c.poly([(6.4, 6.6), (7.5, 4.8), (8.6, 6.6)], a, 3, normal=(-0.2, -0.6, 0.8), lo=1, hi=4)
    c.tube([(7.5, 10.8), (7.5, 12.8)], [1.7, 1.3], a, 2, flat=0.9, lo=1, hi=4)
    c.poly([(7.5, 12.6), (4.6, 14.7), (7.5, 14.4), (10.4, 14.7)], a, 1, normal=(0, 0.3, 1), lo=1, hi=3)
    _fish4_recolor(c, [(x, y) for x in range(5, 11) for y in (11, 13)], a, None, only={a}, delta=-1)
    im = c.render()
    leg = _fish4_m("#6a8094")
    _fish4_paint(im, [(4, 8), (3, 9), (4, 10), (3, 11), (11, 8), (12, 9), (11, 10), (12, 11), (5, 12), (10, 12)], leg, 1)
    _fish4_paint(im, [(6, 4), (6, 3), (5, 2), (5, 1), (9, 4), (9, 3), (10, 2), (10, 1)], leg, 1)
    return im


def icon_moss_minnow():
    """이끼 피라미: a slim olive minnow overgrown with bright lime moss: soft moss tufts in place of a dorsal fin
    and moss patches along the back, a pale green belly."""
    a, b = _fish4_m("#4e7a34"), _fish4_m("#d4e4a0")
    moss = _fish4_m("#94cc4a", C=0.13)
    fin = _fish4_m("#5a8038")
    ink = _fish4_ink("#16280c", 0.2)
    c = _fish4_canvas(ink, a, b, fin, moss)
    _fish4_std_fins(c, fin, dorsal=False)
    for (x, y, r) in ((5.4, 7.6, 1.4), (7.2, 5.8, 1.5), (9.0, 4.4, 1.2)):
        c.ellipse(x, y, r, r, moss, 1, lo=1, hi=4, gloss=0)
    _fish4_body(c, a, b, belly=(2.0, 3.2, 1.9), back=(1.4, 2.2, 1.2))
    _fish4_recolor(c, [(5, 9), (6, 8), (7, 7), (8, 6), (9, 5), (6, 7), (8, 5), (4, 10), (10, 4)], moss, None, only={a})
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    return c.render()


def icon_crystal_trout():
    """수정 송어: an ice-blue trout with three crystal shards growing out of its back in place of a
    dorsal fin, crystal-tinted fins and tail, the faint glow of the cave."""
    a, b = _fish4_m("#6ab8e0", C=0.08), _fish4_m("#e2f4fc")
    cr = _fish4_m("#9ae4ff", C=0.09)
    ink = _fish4_ink("#163a5a", 0.24)
    c = _fish4_canvas(ink, a, b, cr)
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], cr, 0, normal=(0.35, 0.1, 1), lo=1, hi=4)
    c.poly([(6, 12.6), (8.5, 14.5), (9.4, 12.4)], cr, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=3)
    for pts in (((4.4, 9.0), (4.4, 5.0), (6.6, 7.4)), ((6.4, 7.4), (7.6, 2.2), (9.0, 5.6)), ((8.8, 5.6), (11.0, 2.4), (10.6, 5.2))):
        c.poly(list(pts), cr, 1, normal=(-0.5, -0.5, 0.7), lo=2, hi=5)
    _fish4_body(c, a, b, belly=(2.1, 3.4, 2.0), back=(1.4, 2.3, 1.2))
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 3)
    return _fish4_glow(c.render(), "#c8f4ff")


def icon_glow_jellyfish():
    """발광 해파리: a glowing cyan bell with a scalloped rim and the four-ring pattern inside, thin
    glowing tentacles and a pale frilly oral arm hanging below, the cyan glow."""
    d = _fish4_m("#48d0ec", C=0.1)
    ink = _fish4_ink("#0e4a5e", 0.26)
    c = _fish4_canvas(ink, d)
    c.ellipse(8.0, 6.8, 6.2, 5.6, d, 2, lo=1, hi=4, gloss=0.8, dome=1.2)
    c.erase([(x, y) for x in range(16) for y in range(16) if y >= 9 or (y == 8 and x % 3 == 0)])
    c.dots([(5, 5), (10, 5), (6, 6), (9, 6)], d, 4, z=5)
    im = c.render()
    t = _fish4_m("#7ae0f8", C=0.09)
    arm = _fish4_m("#b8f4ff", C=0.07)
    _fish4_paint(im, [(3, 10), (2, 11), (2, 12), (3, 13),
                      (5, 10), (5, 11), (4, 12), (5, 13), (5, 14),
                      (10, 10), (10, 11), (11, 12), (10, 13), (10, 14),
                      (12, 10), (13, 11), (13, 12), (12, 13)], t, 3, 230)
    _fish4_paint(im, [(7, 10), (8, 10), (7, 11), (8, 12), (7, 12), (8, 13), (7, 14), (8, 15)], arm, 3)
    return _fish4_glow(im, "#b0f4ff")


def icon_abyssfish():
    """심연어: the cave legend: a black dragonfish of the deep, a big head with jaws open on long white fangs,
    a glowing violet eye, a row of violet lights along its flank and a violet glow."""
    a, b = _fish4_m("#2a2844", L=0.38), _fish4_m("#433868", L=0.44)
    vio = _fish4_m("#b060ff", C=0.17)
    maw = _fish4_m("#4a1460", L=0.3, C=0.1)
    ink = _fish4_ink("#0c0a16", 0.14)
    c = _fish4_canvas(ink, a, b, maw)
    c.poly(_fish4_fan(12.8, 3.0, 2.8, -45, 60), a, 0, normal=(0.3, -0.2, 1), lo=1, hi=3)
    c.poly([(9.4, 4.4), (10.8, 1.6), (11.8, 3.0)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.tube([(5.0, 10.0), (8.6, 6.6), (12.2, 3.4)], [2.4, 1.6, 0.8], b, 2, flat=0.9, lo=1, hi=4, gloss=0)
    c.tube([(4.6, 9.4), (8.2, 6.0), (11.8, 3.0)], [1.7, 1.0, 0.5], a, 3, flat=0.9, lo=1, hi=4, gloss=0.95)
    c.ellipse(4.0, 11.2, 3.3, 3.3, a, 4, lo=1, hi=4, gloss=0.95)
    c.poly([(-0.5, 15.6), (3.8, 12.0), (-0.5, 11.4)], maw, 5, step=1)
    c.dots([(6, 12), (8, 10), (10, 8), (12, 6)], vio, 4, z=7)
    c.put(4, 9, vio, 5, z=7).put(3, 9, vio, 3, z=7)
    c.dots([(1, 12), (2, 13), (1, 14)], "snow", 4, z=7)
    return _fish4_glow(c.render(), "#a050ff", 120)


def icon_petal_carp():
    """꽃잎 잉어: the cave legend: a white koi with bold cherry-blossom pink patches, long flowing pink fins and
    a forked tail whose lobes are notched like petals, barbels at the mouth."""
    a, b = _fish4_m("#f6f0f0"), _fish4_m("#fffafa")
    pk = _fish4_m("#e8508a", C=0.15)
    fin = _fish4_m("#f8b8d4", C=0.08)
    ink = _fish4_ink("#7a2a4a", 0.28)
    c = _fish4_canvas(ink, a, b, pk, fin)
    c.ink[fin] = _fish4_ink("#a03a6a", 0.36)
    c.poly([(9.6, 6.6), (12.0, 0.6), (15.4, 0.4), (13.0, 4.4), (15.6, 3.6), (15.4, 7.6), (11.2, 8.8)], fin, 0,
           normal=(0.3, -0.2, 1), lo=2, hi=4)
    c.poly([(4.4, 8.6), (6.8, 3.4), (9.6, 2.6), (8.0, 6.4)], fin, 1, normal=(-0.3, -0.7, 0.6), lo=2, hi=4)
    c.poly([(4.8, 12.2), (3.6, 15.6), (6.6, 15.4), (7.4, 12.6)], fin, 1, normal=(0.4, 0.6, 0.7), lo=2, hi=4)
    c.poly([(8.0, 11.4), (10.4, 13.6), (11.0, 10.6)], fin, 1, normal=(0.4, 0.6, 0.7), lo=2, hi=4)
    _fish4_body(c, a, b, belly=(2.1, 3.3, 2.0), back=(1.4, 2.3, 1.2))
    _fish4_recolor(c, [(5, 8), (6, 8), (5, 9), (6, 7), (8, 6), (9, 6), (8, 7), (9, 5), (10, 4), (7, 9), (4, 10)], pk,
                   None, only={a, b})
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    im = c.render()
    return _fish4_paint(im, [(1, 13), (2, 14)], ink[0], 0)


def icon_mushroom_puffer():
    """버섯 복어: side on, a round cream puffer wearing a red toadstool cap with white spots over its
    back; the face, eye, lips and fan tail stay clear of the cap."""
    cap, body = "toadstool", "cream"
    fin = _fish4_m("#d8c4a8")
    ink = _fish4_ink("#3a1a14", 0.22)
    c = _fish4_canvas(ink, cap, body, fin)
    c.ellipse(8.2, 6.4, 5.4, 4.4, cap, 2, lo=1, hi=4, gloss=0.85)
    c.erase([(x, y) for x in range(16) for y in range(7, 16)])
    c.poly(_fish4_fan(11.4, 9.6, 4.2, 0, 38), fin, 0, normal=(0.4, 0, 1), lo=1, hi=3)
    c.ellipse(6.8, 9.6, 5.6, 4.8, body, 1, lo=1, hi=4, gloss=0.9)
    c.dots([(6, 3), (9, 3), (11, 5), (7, 5)], "snow", 4, z=5)
    c.poly([(6.4, 10.6), (9.4, 12.0), (7.0, 12.6)], fin, 3, normal=(0.4, 0.6, 0.7), lo=1, hi=3)
    c.put(3, 8, "roe", 0).put(2, 8, "snow", 4)
    c.put(1, 10, cap, 2, z=5)
    c.dots([(4, 12), (6, 13), (9, 13)], body, 1, z=5)
    return c.render()


def icon_mycelium_ray():
    """균사 가오리: from above, a ray in mycelium purple-grey with pale specks, a red and a brown mushroom
    growing on its back (told apart from the brown 홍어 by colour, specks and mushrooms)."""
    a = _fish4_m("#8a7090")
    sp = _fish4_m("#dac8dc")
    st = _fish4_m("#e8dcc8")
    ink = _fish4_ink("#2a1e2c", 0.22)
    c = _fish4_canvas(ink, a, sp, st)
    c.line(9, 12, 12, 15, a, 2, z=0)
    c.poly([(8, 2.4), (10, 4), (13.5, 5.6), (15.6, 9.0), (12, 10.0), (9, 12.4), (8, 12.8), (7, 12.4), (4, 10.0),
            (0.4, 9.0), (2.5, 5.6), (6, 4)], a, 1, normal=(-0.2, -0.4, 1), lo=1, hi=3)
    c.ellipse(8, 8.0, 2.4, 3.6, a, 2, lo=2, hi=4, gloss=0.8)
    c.dots([(4, 7), (12, 7), (3, 8), (11, 9), (5, 9), (13, 8), (10, 6), (6, 6)], sp, 3, z=5)
    # two little mushrooms growing on its back
    c.rect(5.6, 3.0, 6.4, 5.6, st, 6, lo=2, hi=3)
    c.ellipse(6.0, 2.4, 1.7, 1.1, "toadstool", 7, lo=1, hi=4, gloss=0.9)
    c.rect(9.6, 3.6, 10.4, 6.2, st, 6, lo=2, hi=3)
    c.ellipse(10.0, 3.2, 1.6, 1.0, _fish4_m("#a07050"), 7, lo=1, hi=4, gloss=0.9)
    c.dots([(7, 6), (9, 6)], "roe", 0, z=6)
    return c.render()


def icon_sweetfish():
    """은어: a slim silvery olive fish with the yellow spot behind the gill that names the 은어, yellowish
    pelvic and anal fins and a small adipose fin."""
    a, b = _fish4_m("#7a8a6a"), _fish4_m("#e6ece0")
    fin = _fish4_m("#c8b450", C=0.09)
    y = _fish4_m("#f0c83a", C=0.14)
    ink = _fish4_ink("#26301c", 0.22)
    c = _fish4_canvas(ink, a, b, fin)
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], a, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(5.0, 8.4), (7.0, 3.8), (9.4, 4.2), (7.0, 7.6)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.poly([(10.4, 5.6), (11.4, 3.8), (11.8, 5.4)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    _fish4_body(c, a, b, belly=(2.0, 3.0, 1.8), back=(1.3, 2.1, 1.1))
    c.poly([(6, 12.6), (8.5, 14.5), (9.4, 12.4)], fin, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=3)
    c.poly([(9.0, 10.6), (11.6, 11.8), (11.6, 9.2)], fin, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=3)
    _fish4_recolor(c, [(5, 10), (5, 9), (6, 10)], y, 3)
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    return c.render()


def icon_pike_conger():
    """갯장어: a grey-brown eel bent in an S, a dark fin fringe along the back, the long pointed head with
    its jaw line and fangs."""
    a, b = _fish4_m("#6e6a58"), _fish4_m("#dcd4c0")
    fin = _fish4_m("#4a4638")
    ink = _fish4_ink("#1e1c14", 0.2)
    c = _fish4_canvas(ink, a, b, fin)
    pts = [(4.4, 11.0), (6.6, 10.8), (8.8, 9.0), (9.4, 6.2), (11.6, 3.6), (14.6, 2.2)]
    c.tube(pts, [2.3, 2.3, 2.2, 2.0, 1.3, 0.6], fin, 0, flat=0.9, lo=1, hi=3, gloss=0)
    c.tube(pts, [1.7, 1.7, 1.6, 1.5, 1.0, 0.3], b, 1, flat=0.95, lo=1, hi=4, gloss=0)
    c.tube([(x - 0.4, y - 0.55) for x, y in pts], [1.2, 1.2, 1.1, 1.0, 0.6, 0.2], a, 2, flat=0.95, lo=1, hi=4, gloss=0.92)
    c.tube([(4.8, 10.6), (2.6, 12.4), (0.8, 14.2)], [1.7, 1.3, 0.7], b, 3, flat=0.9, lo=1, hi=4, gloss=0)
    c.tube([(4.4, 10.0), (2.2, 11.8), (0.6, 13.4)], [1.2, 0.9, 0.4], a, 4, flat=0.9, lo=1, hi=4, gloss=0.92)
    c.line(1, 14, 4, 12, "roe", 1, z=6)
    c.dots([(2, 13), (3, 13)], "snow", 4, z=7)
    c.put(4, 10, "roe", 0).put(5, 9, "snow", 3)
    return c.render()


def icon_golden_crucian():
    """황금 붕어: the plain bright-gold crucian carp with the pale glow of a dawn fish, outlined in deep
    brown so the pale belly does not melt into the slot (용잉어 is the gold one with red fins and whiskers)."""
    a, b = _fish4_m("#e8b028", C=0.13), _fish4_m("#fff0a8", C=0.1)
    fin = _fish4_m("#e0a02a", C=0.12)
    ink = _fish4_ink("#5a3606", 0.24)
    c = _fish4_canvas(ink, a, b, fin)
    _fish4_std_fins(c, fin)
    _fish4_body(c, a, b, belly=(2.3, 3.8, 2.1), back=(1.5, 2.6, 1.3))
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    return _fish4_glow(c.render(), "#fff4b0")


def icon_sunset_bream():
    """노을 도미: a deep-bodied bream coloured like a sunset: violet back, orange flanks, a golden belly,
    a spiny dorsal fin and red tail (참돔 stays pink with blue spots)."""
    top, mid, low = _fish4_m("#7e3a78", C=0.1), _fish4_m("#f07a3a", C=0.14), _fish4_m("#ffd080", C=0.1)
    fin = _fish4_m("#d8604a", C=0.12)
    ink = _fish4_ink("#3a1020", 0.2)
    c = _fish4_canvas(ink, top, mid, low, fin)
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], fin, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(3.6, 8.6), (3.8, 6.0), (5.0, 6.4), (5.4, 4.0), (6.6, 4.6), (7.4, 2.4), (8.4, 3.4), (9.6, 1.8), (10.2, 4.4), (5.4, 9.2)],
           fin, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    pts = [(3.2, 12.6), (6.4, 9.4), (10.4, 5.4)]
    c.tube(pts, [2.4, 4.0, 2.2], low, 2, flat=0.9, lo=1, hi=4, gloss=0)
    c.tube([(x - 0.4, y - 0.5) for x, y in pts], [1.8, 3.0, 1.6], mid, 3, flat=0.9, lo=1, hi=4, gloss=0)
    c.tube([(x - 0.9, y - 1.1) for x, y in pts], [1.0, 1.8, 0.9], top, 4, flat=0.9, lo=1, hi=4, gloss=0.93)
    c.poly([(6, 12.8), (8.6, 14.8), (9.6, 12.4)], fin, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    return c.render()


def icon_rainbow_trout():
    """무지개송어: olive-green back with black specks, the pink-magenta stripe along the flank, a silver
    white belly."""
    a, b = _fish4_m("#5a7a4e"), _fish4_m("#eef0ea")
    pk = _fish4_m("#e85a8a", C=0.13)
    ink = _fish4_ink("#1c2a16", 0.22)
    c = _fish4_canvas(ink, a, b, pk)
    _fish4_std_fins(c, a)
    _fish4_body(c, a, b)
    _fish4_recolor(c, [(x, y) for x in range(16) for y in range(16) if x + y in (14, 15) and 3 < x < 12], pk, None, only={a, b})
    c.dots([(5, 7), (7, 6), (8, 4), (9, 5), (6, 8), (11, 2), (12, 4), (13, 2)], "roe", 1, z=6)
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    return c.render()


def icon_moonlight_ray():
    """달빛 가오리: from above, a night-blue manta with swept wings and the two horn fins at the head, a
    pale crescent moon and stars on its back, a soft moonlight glow."""
    a = _fish4_m("#3a4890", C=0.09)
    m = _fish4_m("#fff2c0", C=0.06)
    ink = _fish4_ink("#0e1236", 0.18)
    c = _fish4_canvas(ink, a, m)
    c.tube([(8.0, 10.0), (8.6, 13.0), (10.0, 15.4)], [0.8, 0.5, 0.4], a, 0, lo=1, hi=3)
    c.poly([(8.0, 3.0), (11.4, 4.0), (15.6, 4.4), (13.8, 7.4), (9.6, 11.0), (8.0, 11.6), (6.4, 11.0), (2.2, 7.4),
            (0.4, 4.4), (4.6, 4.0)], a, 1, normal=(-0.2, -0.4, 1), lo=1, hi=4)
    c.poly([(6.4, 4.2), (5.8, 1.2), (7.4, 3.0)], a, 1, normal=(-0.3, -0.6, 0.8), lo=1, hi=4)
    c.poly([(9.6, 4.2), (10.2, 1.2), (8.6, 3.0)], a, 1, normal=(-0.3, -0.6, 0.8), lo=1, hi=4)
    c.ellipse(8.0, 7.0, 2.2, 3.6, a, 2, lo=2, hi=4, gloss=0.8)
    c.dots([(7, 5), (6, 6), (6, 7), (6, 8), (7, 9)], m, 4, z=6)
    c.dots([(3, 5), (13, 5), (11, 7)], m, 3, z=6)
    return _fish4_glow(c.render(), "#c8d4ff")


def icon_thunder_catfish():
    """천둥 메기: the catfish's body (wide flat head) in storm navy with a yellow lightning bolt across
    its flank and electric-yellow whiskers."""
    a, b = _fish4_m("#3e4258"), _fish4_m("#8a8ea8")
    bolt = _fish4_m("#ffe040", C=0.15)
    ink = _fish4_ink("#10121e", 0.18)
    c = _fish4_canvas(ink, a, b)
    c.poly([(12.0, 3.8), (14.6, 0.4), (15.2, 2.4), (13.8, 3.8), (15.4, 5.2), (13.0, 5.4)], a, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(8.4, 10.2), (13.0, 4.4), (14.0, 5.0), (9.8, 11.0)], a, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    c.tube([(5.8, 10.0), (8.2, 7.6), (11.0, 4.8), (13.4, 2.6)], [2.3, 1.9, 1.3, 0.7], b, 2, flat=0.9, lo=1, hi=3)
    c.tube([(5.4, 9.6), (7.8, 7.2), (10.6, 4.4), (13.0, 2.3)], [2.0, 1.5, 0.9, 0.4], a, 3, flat=0.9, lo=1, hi=4)
    c.ellipse(5.2, 10.6, 2.6, 2.2, a, 3, lo=1, hi=4)
    c.poly([(8.0, 6.6), (8.0, 4.6), (9.4, 5.6)], a, 1, normal=(-0.3, -0.6, 0.7), lo=1, hi=3)
    _fish4_recolor(c, [(9, 4), (10, 4), (8, 5), (9, 5), (7, 6), (8, 6), (9, 6), (10, 6), (8, 7), (9, 7), (7, 8), (8, 8)],
                   bolt, None, delta=0)
    c.put(5, 9, "roe", 0)
    im = c.render()
    _fish4_paint(im, [(1, 10), (0, 9), (0, 8), (1, 12), (0, 13), (0, 14), (2, 10), (2, 12)], bolt, 3)
    return im


ICONS = {
    "butterflyfish": icon_butterflyfish,
    "clownfish": icon_clownfish,
    "damselfish": icon_damselfish,
    "filefish": icon_filefish,
    "flatfish": icon_flatfish,
    "mackerel": icon_mackerel,
    "marlin": icon_marlin,
    "monkfish": icon_monkfish,
    "octopus": icon_octopus,
    "skate": icon_skate,
    "tang": icon_tang,
    "yellowtail": icon_yellowtail,
    "atka_mackerel": icon_atka_mackerel,
    "bagrid_catfish": icon_bagrid_catfish,
    "carp": icon_carp,
    "catfish": icon_catfish,
    "dragon_carp": icon_dragon_carp,
    "king_crab": icon_king_crab,
    "kkeokji": icon_kkeokji,
    "mandarin_fish": icon_mandarin_fish,
    "pale_chub": icon_pale_chub,
    "sailfish": icon_sailfish,
    "sandfish": icon_sandfish,
    "snow_crab": icon_snow_crab,
    "sunfish": icon_sunfish,
    "whale_shark": icon_whale_shark,
    "yellowfin_tuna": icon_yellowfin_tuna,
    "angelfish": icon_angelfish,
    "barramundi": icon_barramundi,
    "eel": icon_eel,
    "giant_snakehead": icon_giant_snakehead,
    "masu_salmon": icon_masu_salmon,
    "mud_crab": icon_mud_crab,
    "mullet": icon_mullet,
    "paradise_fish": icon_paradise_fish,
    "snakehead": icon_snakehead,
    "softshell_turtle": icon_softshell_turtle,
    "swamp_eel": icon_swamp_eel,
    "taimen": icon_taimen,
    "abyssfish": icon_abyssfish,
    "arapaima": icon_arapaima,
    "arowana": icon_arowana,
    "cave_crayfish": icon_cave_crayfish,
    "cave_shrimp": icon_cave_shrimp,
    "cavefish": icon_cavefish,
    "crystal_trout": icon_crystal_trout,
    "glow_jellyfish": icon_glow_jellyfish,
    "golden_crucian": icon_golden_crucian,
    "moonlight_ray": icon_moonlight_ray,
    "moss_minnow": icon_moss_minnow,
    "mushroom_puffer": icon_mushroom_puffer,
    "mycelium_ray": icon_mycelium_ray,
    "petal_carp": icon_petal_carp,
    "pike_conger": icon_pike_conger,
    "piranha": icon_piranha,
    "rainbow_trout": icon_rainbow_trout,
    "sunset_bream": icon_sunset_bream,
    "sweetfish": icon_sweetfish,
    "thunder_catfish": icon_thunder_catfish,
}
