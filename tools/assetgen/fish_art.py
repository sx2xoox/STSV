"""Hand-drawn fish icons: the species whose look the shared templates in economy_fish.py cannot give
(their own silhouette, marks, props). economy_fish.ICONS uses these over the template; every other fish is
drawn by economy_fish.icon(). Each icon is a 16x16 image, lit from the top left, with a dark outline in the
fish's own hue. Diagonal fish lie like vanilla cod (head bottom left, tail top right); tall and round fish,
rays, crabs and turtles are drawn side-on or from above.
"""
import math
from PIL import Image as _fish4_Image
from economy_fish import mat
from stsv_assets import palette, pixelart
from stsv_assets.color import rgb_to_oklch
from stsv_assets.pixelart import Canvas, color


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
    if name not in palette.MATERIALS:
        l, ch, h = rgb_to_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        palette.MATERIALS[name] = (h, ch / 1.5 if C is None else C, min(0.9, max(0.3, l)) if L is None else L,
                                    span, 5, False)
    return name


def _fish2_ink(hexcol, L=0.26, C=None):
    """A dark outline (material, step 0) in the fish's own hue."""
    name = f"f2ink_{hexcol[1:]}_{int(L * 100)}"
    if name not in palette.MATERIALS:
        l, ch, h = rgb_to_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        palette.MATERIALS[name] = (h, min(ch / 1.5, 0.06) if C is None else C, L + 0.272, 0.34, 5, False)
    return (name, 0)


def _fish2_canvas(ink, *mats):
    c = Canvas()
    for m in mats:
        c.ink[m] = ink
    return c


def _fish2_paint(im, pts, mat, step):
    """Pixels painted after the outline pass (thin whiskers, a bill, spines): they are their own outline."""
    col = pixelart.color(mat, step)
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
    if name not in palette.MATERIALS:
        l, ch, h = rgb_to_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        palette.MATERIALS[name] = (h, ch / 1.5 if C is None else C, min(0.9, max(0.3, l)) if L is None else L,
                                    span, 5, False)
    return name


def _fish3_ink(hexcol, L=0.26, C=None):
    """A dark outline (material, step 0) in the fish's own hue."""
    name = f"f3ink_{hexcol[1:]}_{int(L * 100)}"
    if name not in palette.MATERIALS:
        l, ch, h = rgb_to_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        palette.MATERIALS[name] = (h, min(ch / 1.5, 0.06) if C is None else C, L + 0.272, 0.34, 5, False)
    return (name, 0)


def _fish3_canvas(ink, *mats):
    c = Canvas()
    for m in mats:
        c.ink[m] = ink
    return c


def _fish3_paint(im, pts, mat, step):
    """Pixels painted after the outline pass (whiskers, threads): they are their own outline."""
    col = pixelart.color(mat, step)
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
        t = math.radians(ang - half + 2 * half * k / n)
        pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
    return pts


def _fish3_part(c, rows, chars, mat, z, lo=1, hi=4, k=1.3, gloss=0.0, bias=0.0):
    """A hand-drawn part: the cells of rows whose character is in chars, shaded like a soft pillow (each
    cell faces away from the part's middle, so the part is lit from the top-left like the toolkit's shapes)."""
    cells = {(x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch in chars}
    out = [(x, y) for x in range(-1, 17) for y in range(-1, 17) if (x, y) not in cells]
    d = {p: min(math.hypot(p[0] - q[0], p[1] - q[1]) for q in out) for p in cells}
    for (x, y) in cells:
        gx = d.get((x + 1, y), 0) - d.get((x - 1, y), 0)
        gy = d.get((x, y + 1), 0) - d.get((x, y - 1), 0)
        c.put(x, y, mat, pixelart.shade_step((-gx, -gy, k), lo, hi, gloss, bias), z)
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
    if name not in palette.MATERIALS:
        l, ch, h = rgb_to_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        palette.MATERIALS[name] = (h, ch / 1.5 if C is None else C, min(0.9, max(0.3, l)) if L is None else L,
                                    span, 5, False)
    return name


def _fish4_ink(hexcol, L=0.26, C=None):
    """A dark outline (material, step 0) in the fish's own hue."""
    name = f"f4ink_{hexcol[1:]}_{int(L * 100)}"
    if name not in palette.MATERIALS:
        l, ch, h = rgb_to_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        palette.MATERIALS[name] = (h, min(ch / 1.5, 0.06) if C is None else C, L + 0.272, 0.34, 5, False)
    return (name, 0)


def _fish4_canvas(ink, *mats):
    c = Canvas()
    for m in mats:
        c.ink[m] = ink
    return c


def _fish4_paint(im, pts, mat, step, alpha=255):
    """Pixels painted after the outline pass (whiskers, barbels, teeth): they are their own outline."""
    col = pixelart.color(mat, step)
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
        t = math.radians(ang - half + 2 * half * k / n)
        pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
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


# ------------------------------------------------------------------------------------------------
# fa (round 2, attempt 1)
# ------------------------------------------------------------------------------------------------


def _r2fa1_rows(c, rows, key):
    """hand-placed pixels: rows of characters, '.' leaves a pixel alone, key maps a character to (material, step)"""
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                c.put(x, y, key[ch][0], key[ch][1], z=50)
    return c


# ---- 정어리: a slender blue sardine (much thinner than 고등어), forked tail, the row of dark spots ----
def icon_sardine():
    a, b = _fish3_m("#3c6a9c"), _fish3_m("#e2eaf2")
    sp = _fish3_m("#1c2c4c", L=0.3)
    c = _fish3_canvas(_fish3_ink("#1a2c4a", 0.24), a, b)
    c.poly([(9.8, 5.8), (13.4, 0.9), (14.4, 1.2), (12.6, 5.0), (14.6, 7.4), (14.0, 8.4), (10.8, 7.0)], a, 0,
           normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(5.0, 8.6), (6.6, 5.6), (8.4, 5.8), (6.6, 8.0)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    pts = [(2.6, 13.0), (6.4, 9.2), (11.0, 4.6)]
    c.tube(pts, [1.5, 2.6, 1.3], b, 2, flat=0.9, lo=2, hi=4, gloss=0)
    c.tube([(x - 0.9, y - 0.9) for x, y in pts], [0.9, 1.6, 0.7], a, 3, flat=0.9, lo=1, hi=4, gloss=0.93)
    _fish3_recolor(c, [(5, 11), (7, 9), (9, 7)], sp, 1)
    c.put(2, 12, "roe", 0).put(3, 11, "snow", 2)
    return c.render()


# ---- 전갱이: green-grey jack with the scute ridge along its rear flank, the black gill spot, sickle tail ----
def icon_horse_mackerel():
    a, b = _fish3_m("#5e7c80"), _fish3_m("#e2e8e6")
    sc = _fish3_m("#3a4c50", L=0.36)
    c = _fish3_canvas(_fish3_ink("#1e2c30", 0.24), a, b)
    c.poly([(9.8, 6.2), (13.4, 0.8), (14.4, 1.0), (12.4, 6.4), (15.0, 9.2), (14.6, 10.2), (10.8, 8.4)], a, 0,
           normal=(0.35, 0.1, 1), lo=1, hi=3)
    c.poly([(4.5, 8.5), (6.4, 5.4), (8.0, 5.8), (6, 9)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.poly([(8.0, 6.0), (10.6, 3.2), (11.0, 4.6)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    pts = [(3.4, 12.4), (6.6, 9.2), (10.4, 5.4)]
    c.tube(pts, [2.2, 3.6, 1.8], b, 2, flat=0.9, lo=1, hi=4, gloss=0)
    c.tube([(x - 0.5, y - 0.7) for x, y in pts], [1.4, 2.4, 1.2], a, 3, flat=0.9, lo=1, hi=4, gloss=0.93)
    c.poly([(6, 12.6), (8.5, 14.5), (9.4, 12.4)], a, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    _fish3_recolor(c, [(6, 10), (7, 9), (8, 8), (9, 7), (10, 6), (11, 5)], sc, 1)
    _fish3_recolor(c, [(7, 8), (8, 7), (9, 6), (10, 5)], b, 4)
    c.put(5, 9, sc, 1)                                   # the dark spot on the gill cover
    c.put(3, 11, "roe", 0).put(3, 10, "snow", 2)
    return c.render()


# ---- 명태: slim olive-brown cod with three separate dorsal fins, a square tail, the dark lateral line over the
#      pale belly and the jutting lower jaw ----
def _r2fa1_pollack_band(t):
    """the pollack's body across (s = x + y) at a place along it (t = x - y, head -12 .. tail stalk 7)"""
    if t < -12 or t > 7:
        return None
    return {-12: (14, 14), -11: (13, 15), -10: (12, 16), 2: (12, 17), 3: (12, 16), 4: (12, 16),
            5: (13, 16), 6: (13, 15), 7: (13, 15)}.get(t) or (11, 17)


def icon_pollack():
    a, b = _fish3_m("#5e5a3e"), _fish3_m("#e0ddcc")
    fin = _fish3_m("#6a6446")
    ln = _fish3_m("#34301e", L=0.32)
    c = _fish3_canvas(_fish3_ink("#24200c", 0.22), a, b, fin, ln)
    for x in range(16):
        for y in range(16):
            s, t = x + y, x - y
            band = _r2fa1_pollack_band(t)
            if not band or not band[0] <= s <= band[1]:
                continue
            if s <= 13:
                c.put(x, y, a, {11: 4, 12: 3, 13: 2}[s], z=2)
            elif s == 14:
                c.put(x, y, *((ln, 2) if -8 <= t <= 6 else (a, 2)), z=2)
            else:
                c.put(x, y, b, {15: 3, 16: 3, 17: 2}[s], z=2)
    c.poly([(10.6, 4.4), (12.2, 0.8), (13.4, 1.0), (14.2, 2.6), (14.6, 4.0), (11.6, 5.4)], fin, 0,
           normal=(0.35, 0.1, 1), lo=1, hi=3)
    for cc in (-7, -1, 5):      # three dorsal fins, apart
        for (s, t, st) in ((10, cc - 1, 3), (10, cc + 1, 2), (9, cc, 3), (9, cc + 2, 2)):
            c.put((s + t) // 2, (s - t) // 2, fin, st, z=1)
    c.put(6, 12, fin, 2, z=1).put(7, 12, fin, 2, z=1)              # pelvic fin
    c.dots([(9, 9), (10, 8), (10, 9), (11, 8)], fin, 2, z=1)        # anal fin
    c.put(1, 14, b, 3, z=5).put(2, 14, b, 2, z=5).put(1, 13, "roe", 1, z=5)
    c.put(2, 11, "roe", 0).put(3, 10, "snow", 2)
    return c.render()


# ---- 갈치: a silver ribbon swimming in a gentle wave, the fin fringe along its back, open jaws (the lower one
#      longer) with the big eye behind them, the rear third tapering to a thread that ends inside the slot ----
def icon_hairtail():
    s = "fish_silver"
    fm = _fish3_m("#8a9cb8", C=0.03)
    ink = _fish3_ink("#26303e", 0.24)
    c = _fish3_canvas(ink, s, fm)
    pts = [(2.0, 13.2), (5.0, 11.6), (6.4, 8.6), (8.8, 7.0), (9.8, 5.2), (11.0, 4.2)]
    r = [1.3, 1.8, 1.8, 1.3, 0.7, 0.35]
    fringe = [(x - (rr + 0.25) * 0.72, y - (rr + 0.25) * 0.72) for (x, y), rr in zip(pts[1:5], r[1:5])]
    c.tube(fringe, [0.7, 0.7, 0.6, 0.35], fm, 0, flat=0.9, lo=2, hi=3, gloss=0)
    c.tube(pts, r, s, 1, flat=0.9, lo=1, hi=5, gloss=0.9)
    c.dots([(11, 3), (12, 2), (13, 1)], s, 4, z=1)     # the thread, ending inside the slot
    c.erase([(x, y) for x in range(8) for y in range(10, 16)])
    key = {str(k): (s, k) for k in range(6)}
    key.update({"e": ("roe", 0), "m": (ink[0], 0), "f": (fm, 3), "w": ("snow", 4)})
    _r2fa1_rows(c, ["........"] * 10 + ["...f542.",
                                        "..5w31..",
                                        ".54e2...",
                                        ".mm31...",
                                        ".43....."], key)
    return c.render()


# ---- 대왕오징어: diagonal: the long mantle ending in a closed diamond fin top right, a big eye, four arms
#      splaying out as separate strands ----
def icon_giant_squid():
    a = _fish3_m("#c8645a")
    fin = _fish3_m("#a8483e")
    ink = _fish3_ink("#5a1610", 0.24)
    c = _fish3_canvas(ink, a, fin)
    for p, st in {(12, 1): 3, (11, 2): 3, (12, 2): 3, (13, 2): 2, (10, 3): 3, (11, 3): 3, (12, 3): 2, (13, 3): 2,
                  (14, 3): 1, (11, 4): 2, (12, 4): 2, (13, 4): 1, (12, 5): 1}.items():
        c.put(*p, fin, st, z=1)
    c.tube([(12.0, 3.4), (9.8, 5.2), (7.0, 8.0)], [0.6, 1.5, 2.0], a, 2, flat=0.85, lo=1, hi=4, gloss=0.9)
    c.ellipse(5.2, 10.4, 1.7, 1.6, a, 3, lo=1, hi=4, gloss=0)
    for arm in ([(3, 10), (2, 9), (1, 9)], [(3, 12), (2, 13), (1, 14)], [(5, 12), (5, 13), (4, 14)],
                [(6, 12), (7, 13), (8, 13)]):
        for k, (x, y) in enumerate(arm):
            c.put(x, y, a, 3 if k < 1 else 2, z=1)
    c.put(5, 10, "roe", 0).put(6, 9, "snow", 3)
    return _fish1_closed(c, ink[0])


# ---- 만새기: side on, the bull-headed dolphinfish: a steep blunt forehead, one blue fin running the whole back,
#      green back over golden flanks, a deeply forked blue tail ----
def icon_mahi_mahi():
    gr, yl = _fish3_m("#2a9a6a", C=0.12), _fish3_m("#f0d040", C=0.13)
    fin = _fish3_m("#2a6ac8", C=0.12)
    c = _fish3_canvas(_fish3_ink("#0c3020", 0.22), gr, yl, fin)
    top = {1: 4, 2: 4, 3: 4, 4: 4, 5: 5, 6: 5, 7: 5, 8: 6, 9: 6, 10: 7, 11: 8}
    bot = {1: 10, 2: 11, 3: 12, 4: 12, 5: 12, 6: 12, 7: 11, 8: 11, 9: 10, 10: 10, 11: 9}
    dorsal = {2: (2, 3), 3: (2, 3), 4: (2, 3), 5: (3, 4), 6: (3, 4), 7: (3, 4), 8: (4, 5), 9: (5, 5), 10: (6, 6)}
    anal = {6: (13, 13), 7: (12, 12), 8: (12, 12), 9: (11, 11)}
    tail = [(12, 6), (12, 7), (12, 8), (12, 9), (12, 10), (13, 4), (13, 5), (13, 6), (14, 3), (14, 4), (13, 10),
            (13, 11), (13, 12), (14, 12), (14, 13)]
    g = [["."] * 16 for _ in range(16)]
    for x in top:
        for y in range(top[x], bot[x] + 1):
            g[y][x] = "G" if y <= (top[x] + bot[x]) / 2 else "Y"
    for x, (y0, y1) in list(dorsal.items()) + list(anal.items()):
        for y in range(y0, y1 + 1):
            g[y][x] = "D"
    for x, y in tail:
        g[y][x] = "D"
    rows = ["".join(r) for r in g]
    _fish3_part(c, rows, "GY", gr, 2, lo=1, hi=4, k=1.4, gloss=0.9)
    for (x, y), v in c.px.items():
        if rows[y][x] == "Y":
            v[0], v[1] = yl, min(4, v[1] + 1)
    _fish3_part(c, rows, "D", fin, 1, lo=1, hi=3, k=1.0)
    c.put(2, 7, "roe", 0).put(3, 6, "snow", 3)
    return c.render()


# ---- 그린란드상어: stout and dark brown-grey, small fins, two small dorsal fins, the pale clouded eye ----
def icon_greenland_shark():
    a = _fish3_m("#57534c")
    b = _fish3_m("#9a958a")
    c = _fish3_canvas(_fish3_ink("#25221e", 0.20), a, b)
    c.poly([(10.2, 5.2), (11.6, 3.2), (13.8, 1.0), (14.8, 1.2), (13.2, 4.4), (12.0, 5.8)], a, 0,
           normal=(0.35, -0.2, 1), lo=1, hi=3)
    c.poly([(11.6, 5.0), (14.9, 5.6), (14.4, 7.0), (11.2, 6.6)], a, 0, normal=(0.35, 0.3, 1), lo=1, hi=2)
    c.poly([(4.2, 8.4), (5.6, 2.8), (6.8, 3.0), (7.0, 5.0), (8.6, 6.0)], a, 1, normal=(-0.5, -0.4, 0.75), lo=1, hi=3)
    c.poly([(8.6, 5.4), (9.2, 2.6), (10.8, 3.4), (10.4, 4.8)], a, 1, normal=(-0.3, -0.6, 0.75), lo=1, hi=3)
    c.poly([(4.8, 11.6), (7.6, 14.2), (8.2, 13.4), (7.2, 9.8)], a, 1, normal=(0.3, 0.5, 0.8), lo=1, hi=2)
    c.tube([(2.2, 13.0), (5.0, 10.2), (8.6, 6.8), (11.6, 4.2)], [1.7, 2.5, 2.2, 0.8], b, 2, flat=0.9, lo=1, hi=4)
    c.tube([(1.9, 12.6), (4.6, 9.8), (8.2, 6.4), (11.3, 3.9)], [1.35, 2.0, 1.75, 0.5], a, 3, flat=0.9, lo=1, hi=3,
           gloss=0.95)
    c.put(11, 3, a, 2, z=9)
    c.put(2, 12, _fish3_m("#c8eef2"), 4)
    return c.render()


# ------------------------------------------------------------------------------------------------
# fb (round 2, attempt 1)
# ------------------------------------------------------------------------------------------------


def _r2fb1_paint(c, rows, key, z=50):
    """Hand-placed pixels: each character of rows is a key into (material, step); '.' leaves the pixel alone."""
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                m, s = key[ch]
                c.put(x, y, m, s, z)
    return c


def _r2fb1_key(a, b, fin, **extra):
    """digits 1-5: the back's steps; q r s t: the belly's steps 1-4; f g h j: the fins' steps 1-4."""
    k = {ch: (a, i + 1) for i, ch in enumerate("12345")}
    k.update({ch: (b, i + 1) for i, ch in enumerate("qrst")})
    k.update({ch: (fin, i + 1) for i, ch in enumerate("fghj")})
    k.update({"e": ("roe", 0), "w": ("snow", 4)})
    k.update(extra)
    return k


def _r2fb1_canvas(ink, *mats):
    """A canvas whose parts, eye and eye glint all take the fish's own dark outline."""
    return _fish4_canvas(ink, *mats, "snow", "roe")


# ---- 우럭 ----------------------------------------------------------------------------------------------
_r2fb1_rockfish_rows = [
    "................",
    "............hg..",
    "........j..hhgf.",
    "......j.h..hggf.",
    "...j..hgg3hggf..",
    "...h.gg332gf....",
    "...gg332221s....",
    "..g33r2221sr....",
    "..33w2r21sr.....",
    ".32e2221ssr.....",
    ".322211ssrh.....",
    ".32211srrhg.....",
    ".ooo1srqgf......",
    ".tssrrq.........",
    "..rqp...........",
    "................",
]


def icon_rockfish():
    """우럭: a heavy charcoal rockfish lying corner to corner: the big head with its wide open mouth and pale
    jutting lower jaw, three spines standing up off the back, a square tail and a grey belly (볼락 is the
    red-brown, big-eyed one side on)."""
    a, b = _fish4_m("#4a4842", L=0.44), _fish4_m("#a8a090")
    fin = _fish4_m("#3c3a34", L=0.4)
    ink = _fish4_ink("#141210", 0.16)
    c = _r2fb1_canvas(ink, a, b, fin)
    _r2fb1_paint(c, _r2fb1_rockfish_rows, _r2fb1_key(a, b, fin, o=ink, p=(b, 1)))
    return c.render()


# ---- 볼락 ----------------------------------------------------------------------------------------------
def icon_black_rockfish():
    """볼락: side on, a red-brown rockfish with the huge eyes it is named for, a row of dorsal spines, dark
    bars over the back and a round tail."""
    a, b = _fish4_m("#86483a", C=0.07), _fish4_m("#dab09e", C=0.05)
    fin = _fish4_m("#7a4234", C=0.065)
    bar = _fish4_m("#52261c", L=0.38)
    ink = _fish4_ink("#2a0c06", 0.18)
    c = _r2fb1_canvas(ink, a, b, fin, bar)
    c.poly(_fish4_fan(10.4, 7.8, 4.4, 0, 46), fin, 0, normal=(0.4, 0, 1), lo=1, hi=3)
    c.poly([(2.6, 6.0), (3.6, 3.4), (10.4, 3.8), (11.4, 6.6)], fin, 1, normal=(-0.2, -0.7, 0.7), lo=1, hi=3)
    for (x, y) in ((4, 2), (6, 2), (8, 2), (10, 3)):
        c.put(x, y, fin, 4, z=1).put(x, y + 1, fin, 3, z=1)
    c.poly([(6.4, 10.4), (8.0, 13.4), (9.8, 10.4)], fin, 1, normal=(0.2, 0.6, 0.8), lo=1, hi=2)
    c.poly([(3.2, 10.0), (3.6, 12.8), (5.4, 10.6)], fin, 1, normal=(0.2, 0.6, 0.8), lo=1, hi=2)
    c.ellipse(5.9, 7.7, 5.3, 3.5, a, 2, lo=1, hi=4, gloss=0.9)
    body = set(c.last())
    _fish4_recolor(c, [(x, y) for (x, y) in body if y >= 9], b, None, delta=1)
    _fish4_recolor(c, [(x, y) for (x, y) in body if x in (5, 6, 9) and y <= 8], bar, None, only={a})
    c.poly([(4.4, 8.4), (7.2, 7.8), (6.8, 10.0)], fin, 3, normal=(0.2, 0.3, 0.9), lo=1, hi=3)
    c.dots([(2, 6), (3, 6), (2, 7), (3, 7)], "roe", 0, z=9).put(2, 6, "snow", 4, z=10)
    return c.render()


# ------------------------------------------------------------------------------------------------
# fb (round 2, attempt 2)
# ------------------------------------------------------------------------------------------------


def _r2fb2_paint(c, rows, key, z=50):
    """Hand-placed pixels: each character of rows is a key into (material, step); '.' leaves the pixel alone."""
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                m, s = key[ch]
                c.put(x, y, m, s, z)
    return c


def _r2fb2_closed(im, ink):
    """Background specks shut in by the outline (between two fins and the belly) take the outline colour, so no
    slot-grey pixel shows inside the fish."""
    px = im.load()
    out, todo = set(), [(x, y) for x in range(16) for y in (0, 15)] + [(x, y) for y in range(16) for x in (0, 15)]
    while todo:
        q = todo.pop()
        if q in out or not (0 <= q[0] < 16 and 0 <= q[1] < 16) or px[q][3]:
            continue
        out.add(q)
        todo += [(q[0] + 1, q[1]), (q[0] - 1, q[1]), (q[0], q[1] + 1), (q[0], q[1] - 1)]
    shut = [(x, y) for y in range(16) for x in range(16) if not px[x, y][3] and (x, y) not in out]
    return _fish4_paint(im, shut, ink[0], ink[1])


# ---- 쏠배감펭 ------------------------------------------------------------------------------------------
_r2fb2_lionfish_fins = [
    "................",
    ".......j....hg..",
    ".....j.j...hhgf.",
    ".....j.h.j.hggf.",
    "...j.hRhRh..gf..",
    "...hRhRR........",
    "...hRR..........",
    "................",
    "................",
    "................",
    "..........RR....",
    ".........RRcc...",
    "........RRccRR..",
    ".......RccRRR...",
    "......ccRR......",
    "................",
]


def icon_lionfish():
    """쏠배감펭: a red lionfish with two broad cream bands, a crest of four solid venom spines joined to its
    back by a low membrane, and the pectoral fan with two broad cream stripes; the body keeps its own outline
    against the fan, and the dark eye has a glint and mid-red all round."""
    a = _fish4_m("#c23a2c", C=0.14)
    b = _fish4_m("#e2765e", C=0.11)
    w = _fish4_m("#f6e6d4")
    fin = _fish4_m("#b8402e", C=0.12)
    ink = _fish4_ink("#4a0e08", 0.2)
    c = _fish4_canvas(ink, a, b, w, fin, "snow", "roe")
    _r2fb2_paint(c, _r2fb2_lionfish_fins, {"R": (fin, 2), "c": (w, 3), "f": (fin, 1), "g": (fin, 2), "h": (fin, 3),
                                          "j": (fin, 4)}, z=1)
    c.tube([(4.0, 11.8), (7.0, 9.0), (10.6, 5.6)], [2.0, 2.8, 1.5], b, 2, flat=0.9, lo=1, hi=4, gloss=0)
    c.tube([(3.5, 11.1), (6.4, 8.3), (10.0, 5.0)], [1.4, 2.0, 0.9], a, 3, flat=0.9, lo=1, hi=4, gloss=0.93)
    _fish4_recolor(c, [(x, y) for x in range(16) for y in range(16) if x - y in (-5, -4, -1, 0)], w, None,
                   only={a, b}, delta=1)
    c.px[(4, 11)][:2] = [a, 3]          # the eye's neighbours stay mid-red so the dark eye stands out
    c.px[(3, 12)][:2] = [b, 2]
    c.px[(2, 10)][:2] = [a, 4]          # no wet glint right beside the eye glint
    im = c.render()
    _fish4_paint(im, [(9, 10), (8, 11), (7, 12)], ink[0], 0)     # the body's outline between body and fan
    _fish4_paint(im, [(3, 11)], "roe", 0)
    return _fish4_paint(im, [(3, 10)], "snow", 4)


# ---- 열목어 --------------------------------------------------------------------------------------------
# 1 2: back (bronze rim, gold)  q: belly  f: dorsal, adipose and upper tail lobe  R m: red fins (lit, shaded)
# k: black spot  e w: eye, glint
_r2fb2_lenok_rows = [
    "................",
    "...........f....",
    "..........ff....",
    ".....f..f.ff....",
    "....ff.11222RRR.",
    "...ff1112k22mm..",
    "....112k222.....",
    "...11k2222qmR...",
    "..1122222q.R....",
    "..1k2k22q.......",
    "..12222qmR......",
    ".122w2q.R.......",
    ".22e2q..........",
    ".222q...........",
    ".22q............",
    "................",
]


def icon_lenok():
    """열목어: a golden-bronze trout of the cold rivers lying corner to corner: five crisp single black spots over
    the gold back (each with gold all round), a soft dorsal fin and the little adipose fin of the salmon family,
    small red pelvic and anal fins, and a forked tail of two equal lobes, the upper one bronze, the lower one red
    (타이멘's tail is red all over, 쏘가리 has brown blotches and a spiny fin)."""
    gold = _fish4_m("#bc9450", C=0.07)
    b = _fish4_m("#e8c8a8", C=0.045)
    red = _fish4_m("#c84030", C=0.12)
    sp = _fish4_m("#241a10", L=0.3)
    ink = _fish4_ink("#3a2408", 0.22)
    c = _fish4_canvas(ink, gold, b, red, sp, "snow", "roe")
    _r2fb2_paint(c, _r2fb2_lenok_rows, {"1": (gold, 2), "2": (gold, 3), "f": (gold, 2), "q": (b, 4), "R": (red, 3),
                                       "m": (red, 2), "k": (sp, 1), "e": ("roe", 0), "w": ("snow", 4)})
    return _r2fb2_closed(c.render(contact=False), ink)


# ---- 망둥어 · 포자 망둥어 -------------------------------------------------------------------------------
def _r2fb2_goby(c, a, b, fin, first=True):
    """The goby body lying corner to corner: a big round head (the widest part, with more cheek on the belly
    side), a slim body tapering to a round tail fan, a small round first dorsal just behind the head, the second
    dorsal and the anal fin towards the tail."""
    c.poly(_fish4_fan(11.2, 4.2, 3.4, -45, 62), fin, 0, normal=(0.3, -0.2, 1), lo=2, hi=3)
    if first:
        c.poly(_fish4_fan(6.4, 6.6, 2.4, -135, 36), fin, 1, normal=(-0.3, -0.7, 0.6), lo=2, hi=3)
    c.poly([(8.4, 5.4), (8.8, 3.4), (10.8, 1.8), (10.8, 4.0)], fin, 1, normal=(-0.3, -0.7, 0.6), lo=2, hi=3)
    c.poly([(8.0, 9.6), (11.2, 7.6), (11.0, 5.6), (8.6, 8.0)], fin, 1, normal=(0.4, 0.6, 0.7), lo=2, hi=2)
    pts = [(4.5, 10.6), (8.0, 7.2), (11.0, 4.4)]
    c.tube(pts, [3.6, 1.9, 1.0], b, 2, flat=0.9, lo=3, hi=4, gloss=0)
    c.tube([(x - 0.5, y - 0.6) for x, y in pts], [2.9, 1.3, 0.6], a, 3, flat=0.9, lo=2, hi=3, gloss=0)
    return c


def icon_goby():
    """망둥어: a sandy goby: a big round head with the eye up on top, a small round first dorsal behind the head,
    a round tail, warm brown fins and three dark-brown 2x2 saddles on the back (nape, between the dorsals, tail
    base), each touching the back's edge and parted by sand (미꾸라지 is the slim olive one with barbels)."""
    a, b = _fish4_m("#b09c74", C=0.04), _fish4_m("#ece2c4", C=0.035)
    fin = _fish4_m("#8a6a44", C=0.06)
    dk = _fish4_m("#4a3418", L=0.4, C=0.05)
    ink = _fish4_ink("#261a08", 0.2)
    c = _fish4_canvas(ink, a, b, fin, dk, "snow", "roe")
    _r2fb2_goby(c, a, b, fin)
    for x0, y0 in ((4, 7), (7, 5), (10, 3)):
        _fish4_recolor(c, [(x0, y0), (x0 + 1, y0), (x0, y0 + 1), (x0 + 1, y0 + 1)], dk, 1, only={a, b})
    c.put(3, 10, "roe", 0).put(2, 9, "snow", 4)
    return c.render(contact=False)


def icon_spore_goby():
    """포자 망둥어: the goby's body in mycelium mauve with a red toadstool (domed cap five wide, two white spots,
    thin cream stem) sprouting from its back in place of the first dorsal fin, an outline all round the cap
    and stem, and three pale spores."""
    a, b = _fish4_m("#7a5274", C=0.06), _fish4_m("#d8bcd2", C=0.04)
    fin = _fish4_m("#644460")
    sp = _fish4_m("#f4ecd4", C=0.03)
    ink = _fish4_ink("#2a1428", 0.2)
    c = _fish4_canvas(ink, a, b, fin, "cream")
    c.ink["toadstool"] = _fish4_ink("#4a0c10", 0.2)
    _r2fb2_goby(c, a, b, fin, first=False)
    c.dots([(5, 4), (5, 5), (5, 6)], "cream", 3, z=6)
    cap = [(4, 1), (5, 1), (6, 1), (3, 2), (4, 2), (5, 2), (6, 2), (7, 2), (3, 3), (4, 3), (5, 3), (6, 3), (7, 3)]
    for (x, y) in cap:
        c.put(x, y, "toadstool", 4 if y == 1 else 3 if y == 2 else 2, z=7)
    c.put(3, 2, "toadstool", 4, z=7)
    c.dots([(4, 2), (6, 2)], "snow", 4, z=8)
    c.dots([(7, 8), (9, 6), (5, 10)], sp, 4, z=9)
    c.put(3, 10, "roe", 0).put(2, 9, "snow", 4)
    return _r2fb2_closed(c.render(contact=False), ink)   # the pocket between cap, stem and back is outline


# ---- 미꾸라지 ------------------------------------------------------------------------------------------
# 2 3: back (dark olive, one highlight)  q r: pale yellow belly stripe  f g: fins  k: black dot  e: eye
_r2fb2_loach_rows = [
    "................",
    "............ff..",
    "...........2ffg.",
    "..........2k2gg.",
    ".........222qr..",
    ".......f2k2qr...",
    "......f222qr....",
    "......2k2qr.....",
    ".....222qr......",
    "....2k2qr.......",
    "...322qr........",
    "...2eqq.........",
    "....qqrg........",
    "................",
    "................",
    "................",
]


def icon_loach():
    """미꾸라지: a slim, smooth loach lying corner to corner: a dark olive back with four black dots over a pale
    yellow belly stripe, a blunt round head with short barbels splayed above and below the mouth, a small dorsal
    nub and pectoral fin, and a little round tail no wider than the body (망둥어 is the sandy big-headed one)."""
    a, b = _fish4_m("#5a5226"), _fish4_m("#e4d49a", C=0.055)
    fin = _fish4_m("#6a5c36")
    dk = _fish4_m("#1c1808", L=0.26)
    ink = _fish4_ink("#1e1606", 0.2)
    c = _fish4_canvas(ink, a, b, fin, dk, "snow", "roe")
    _r2fb2_paint(c, _r2fb2_loach_rows, {"2": (a, 2), "3": (a, 3), "q": (b, 4), "r": (b, 3), "f": (fin, 4),
                                       "g": (fin, 3), "k": (dk, 0), "e": ("roe", 0)})
    im = c.render(contact=False)
    return _fish4_paint(im, [(1, 10), (0, 9), (0, 8), (1, 12), (0, 13), (0, 14)], ink[0], 0)


# ------------------------------------------------------------------------------------------------
# fc (round 2, attempt 1)
# ------------------------------------------------------------------------------------------------


def _r2fc1_mask(rows, chars):
    """the cells of a hand-drawn map whose character is in chars"""
    return [(x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch in chars]


# ---- 짱뚱어 / 왕짱뚱어: side on, head left. A tall blunt head with two pale eye domes standing up off the top of
#      the head, the body tapering to a narrow tail stalk and a round tail fan, propped on a small fan of a
#      pectoral fin under the head. ----
_r2fc1_mud_rows = [
    "................",
    "................",
    ".EE.EE..........",
    ".EE.EE..D.......",
    "..BBBBB.DD......",
    ".BBBBBBBBDD.....",
    ".BBBBBBBBBB..T..",
    ".BBBBBBBBBBBTTT.",
    ".BBBBBBBBBBBTTT.",
    ".BBBBBBBBBB.TTT.",
    "..BBBBBBB...TTT.",
    "...PP........T..",
    "..PPPP..........",
    "..PPPP..........",
    "................",
    "................",
]


def _r2fc1_mudskipper(c, a, b, fin, eye, rows=_r2fc1_mud_rows, sail=None, spine=None, eyes=((1, 2), (4, 2))):
    _fish3_part(c, rows, "T", fin, 0, lo=1, hi=3, k=1.0)
    _fish3_part(c, rows, "D", fin, 1, lo=1, hi=3, k=1.0)
    if sail:
        _fish3_part(c, sail, "Ss", fin, 1, lo=1, hi=3, k=1.0)
        c.dots(_r2fc1_mask(sail, "s"), spine or fin, 3, z=1)
    _fish3_part(c, rows, "B", a, 2, lo=1, hi=4, k=1.4)
    body = set(_r2fc1_mask(rows, "B"))
    # pale belly: the bottom two rows under the head, the bottom row further back
    _fish4_recolor(c, [(x, y) for (x, y) in body if (x, y + 1) not in body or ((x, y + 2) not in body and x < 9)],
                   b, None, delta=1)
    _fish3_part(c, rows, "P", fin, 3, lo=1, hi=3, k=1.0)
    for (ex, ey) in eyes:          # eye domes: lit top-left, the dark pupil low and forward
        c.put(ex, ey, eye, 5, 60).put(ex + 1, ey, eye, 4, 60).put(ex + 1, ey + 1, eye, 3, 60)
        c.put(ex, ey + 1, "roe", 0, 60)
    return c


def icon_mudskipper():
    """짱뚱어: grey-brown, two bulging eyes on top of a big blunt head, propped on its pectoral fins, sprinkled
    with bright blue spots."""
    a, b = _fish4_m("#6e6448"), _fish4_m("#bcb490")
    fin = _fish4_m("#7a7058")
    eye = _fish4_m("#e8e0c0")
    spot = _fish4_m("#58b8f0", L=0.72, C=0.12)
    ink = _fish4_ink("#2a2818", 0.22)
    c = _fish4_canvas(ink, a, b, fin, eye)
    _r2fc1_mudskipper(c, a, b, fin, eye)
    c.dots([(3, 6), (6, 5), (8, 7), (5, 8), (10, 7)], spot, 3, z=70)
    c.put(1, 8, ink[0], 0, z=70)
    return c.render()


# ---- 구피: side on, head left: a small silver fish (a 5 px deep body, clear head and eye), a blue flag of a
#      dorsal fin and the huge flame orange fan tail spreading out behind it, dotted dark orange. ----
_r2fc1_guppy_rows = [
    "................",
    "................",
    "................",
    ".......D.....T..",
    "......DD....TTT.",
    ".....DDD...TTTT.",
    "..BBBBBDD.TTTTT.",
    ".BBBBBBBBTTTTTT.",
    ".BBBBBBBBTTTTTT.",
    ".BBBBBBBBTTTTTT.",
    "..BBBBBB..TTTTT.",
    "....FF.....TTTT.",
    ".............T..",
    "................",
    "................",
    "................",
]


def icon_guppy():
    """구피: a small silvery fish with a big eye, a blue dorsal flag and a huge flame orange fan tail with
    dark orange spots."""
    a, b = _fish4_m("#62748a", L=0.6), _fish4_m("#e4eaee")
    tail = _fish4_m("#ff7a30", C=0.15)
    spot = _fish4_m("#c02810", L=0.42, C=0.14)
    blue = _fish4_m("#30a0e8", C=0.13)
    ink = _fish4_ink("#2a2e3a", 0.24)
    c = _fish4_canvas(ink, a, b, tail, blue, spot)
    c.ink[tail] = _fish4_ink("#6a2008", 0.26)
    rows = _r2fc1_guppy_rows
    _fish3_part(c, rows, "T", tail, 0, lo=2, hi=4, k=1.0)
    _fish3_part(c, rows, "D", blue, 1, lo=1, hi=4, k=1.0)
    _fish3_part(c, rows, "F", a, 1, lo=1, hi=3, k=1.0)
    _fish3_part(c, rows, "B", a, 2, lo=1, hi=4, k=1.4)
    body = set(_r2fc1_mask(rows, "B"))
    _fish4_recolor(c, [(x, y) for (x, y) in body if y >= 8], b, None, delta=1)
    c.dots([(12, 5), (13, 7), (11, 9), (13, 10)], spot, 2, z=70)
    c.dots([(6, 8), (7, 8)], tail, 3, z=70)
    c.put(3, 7, "roe", 0, z=70).put(2, 7, "snow", 4, z=70)
    return c.render()


# ---- 디스커스: side on, head left: a round red-orange body crossed by three continuous wavy turquoise lines,
#      a dark eye with a catchlight in the front third and a mouth, ringed by a dark contour and outside it the
#      paler orange fringe of the dorsal and anal fins wrapping round it; a fan tail on a narrow waist. ----
_r2fc1_discus_rows = [
    "................",
    "....FFFF........",
    "...FFFFFFF......",
    "...kkkkkkFF.....",
    "..BBBBBBBkF...T.",
    "..BBBBBBBkFF.TT.",
    ".BBBBBBBBBkF.TT.",
    ".BBBBBBBBBkFTTT.",
    ".BBBBBBBBBkFTTT.",
    ".BBBBBBBBBkF.TT.",
    "..BBBBBBBkFF.TT.",
    "..BBBBBBBkF...T.",
    "...kkkkkkFF.....",
    "...FFFFFFF......",
    "....FFFF........",
    "................",
]


def icon_discus():
    """디스커스: a near-perfect disc, red-orange crossed by the wavy turquoise lines of the turquoise discus,
    its dorsal and anal fins wrapping round it as a paler orange fringe behind a dark contour, a dark eye
    with a catchlight in the front third, the small mouth, a big fan tail on a narrow waist."""
    o = _fish4_m("#e8582a", C=0.16)
    fr = _fish4_m("#f8a060", C=0.12)
    tq = _fish4_m("#30c8e8", L=0.74, C=0.12)
    ink = _fish4_ink("#3a1608", 0.2)
    c = _fish4_canvas(ink, o, fr, tq)
    rows = _r2fc1_discus_rows
    _fish3_part(c, rows, "FT", fr, 0, lo=2, hi=4, k=1.0)
    c.dots(_r2fc1_mask(rows, "k"), ink[0], 0, z=1)
    _fish3_part(c, rows, "B", o, 2, lo=1, hi=4, k=1.6)
    eye = {(3, 6), (2, 6), (4, 6), (3, 5), (2, 5), (3, 7), (2, 7)}
    for y0 in (4, 7, 10):                       # three parallel wavy lines, runs of 3 (no zigzag speckle)
        for x in range(1, 10):
            y = y0 + (0, 1, 1, 1, 0, 0)[x % 6]
            if c.px.get((x, y), [None])[0] == o and (x, y) not in eye:
                c.put(x, y, tq, 3, z=5)
    c.put(3, 6, "roe", 0, z=6).put(2, 6, "snow", 4, z=6)
    c.put(1, 8, ink[0], 0, z=6)                 # the small mouth under the snout
    return c.render()


# ---- 달 금붕어: side on, a fancy goldfish: an egg-shaped body tilted a little nose-down, cream gold right
#      down to the belly, a tall dorsal fin, a small pelvic fin and the split double tail as two
#      moon-white petal lobes fanning up-right and down-right with a gap between them, in its moonlight
#      glow. ----
def icon_moon_goldfish():
    """달 금붕어: the full-moon legend: a fancy goldfish with an egg-shaped cream-gold body tilted a little
    nose-down, a tall dorsal fin, small pelvic fins and a flowing split double tail in moon-white lilac,
    glowing softly."""
    a = _fish4_m("#f2d890", C=0.085)
    fin = _fish4_m("#e6dcf6", C=0.04)
    ink = _fish4_ink("#5a3a10", 0.26)
    c = _fish4_canvas(ink, a, fin)
    c.ink[fin] = _fish4_ink("#5a4a8a", 0.36)
    c.tube([(10.0, 6.2), (11.8, 4.0), (13.2, 1.8)], [0.9, 1.6, 1.2], fin, 0, flat=0.8, lo=2, hi=4, gloss=0)
    c.tube([(10.0, 7.8), (12.4, 9.4), (14.0, 11.8)], [0.9, 1.6, 1.2], fin, 0, flat=0.8, lo=1, hi=3, gloss=0)
    c.poly([(3.6, 6.0), (4.0, 2.6), (5.0, 1.2), (6.8, 1.4), (8.0, 4.6)], fin, 1, normal=(-0.3, -0.7, 0.6), lo=2, hi=4)
    c.poly([(4.6, 12.2), (4.2, 14.6), (6.6, 12.6)], fin, 1, normal=(0.4, 0.6, 0.7), lo=2, hi=3)
    c.tube([(3.2, 9.8), (6.0, 8.4), (9.4, 7.0)], [2.3, 3.9, 1.9], a, 2, flat=0.9, lo=1, hi=4, gloss=0.88)
    c.put(3, 9, "roe", 0).put(2, 9, "snow", 4)
    return _fish4_glow(c.render(), "#fff4d0")


# ------------------------------------------------------------------------------------------------
# fc (round 2, attempt 2)
# ------------------------------------------------------------------------------------------------


def _r2fc2_mask(rows, chars):
    """the cells of a hand-drawn map whose character is in chars"""
    return [(x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch in chars]


# ---- 왕짱뚱어: the mudskipper body (side on, head left, two eye domes on top of the blunt head, a small
#      propped pectoral fan, a round tail fan) in dark slate, with a great rayed sail fanning up just behind
#      the eyes. Map: B body, E eye domes, P pectoral, T tail, R bright ray, M / m membrane (lit / at the
#      base), k the dark back outline under the sail (and the eye's back edge in front of it). The sail
#      starts as a low web behind the eyes (x6), peaks at the front ray (x7), and its ray tips step down toward the
#      tail (x7 row 1, x9 row 2, x11 row 3), each poking one pixel above the membrane on either side, so the
#      edge is scalloped. It ends at x11 with a clear notch (x12-13) above the tail, which hangs a row lower. --
_r2fc2_king_rows = [
    "................",
    ".......R........",
    "......MR.R......",
    "......MRMR.R....",
    ".EE.EEkRMRMR....",
    ".EE.EEkRmRMk....",
    "..BBBBBkkRm.....",
    ".BBBBBBBBkk.....",
    ".BBBBBBBBBB.....",
    ".BBBBBBBBBB..T..",
    ".BBBBBBBBBBBTTT.",
    ".BBBBBBBBBBBTTT.",
    "..BBBBBBBB..TTT.",
    "...PPP......TTT.",
    "..PPPP.......T..",
    "................",
]


def icon_king_mudskipper():
    """왕짱뚱어: the mangrove legend: the mudskipper's body in dark slate with its two bulging eyes on top of
    the head, the first dorsal fin raised into a great blue sail with bright rays, peaking right behind the
    eyes and stepping down toward the tail, slate fins edged teal, electric blue spots and a cold blue glow."""
    rows = _r2fc2_king_rows
    a, b = _fish4_m("#5c6052"), _fish4_m("#a4a690")
    fin = _fish4_m("#666c5e")
    eye = _fish4_m("#e0e8e0")
    mem = _fish4_m("#245a7a", C=0.08)
    ray = _fish4_m("#5ad0ff", L=0.7, C=0.12)
    spot = _fish4_m("#5ad0ff", L=0.8, C=0.13)
    teal = _fish4_m("#3aa8c8")
    ink = _fish4_ink("#16222c", 0.2)
    c = _fish4_canvas(ink, a, b, fin, eye, mem, ray, spot, teal, ink[0])
    # slate tail fan and pectoral, each with a teal rim (the tail's trailing edge, the pectoral's foot)
    _fish3_part(c, rows, "T", fin, 0, lo=1, hi=3, k=1.0)
    _fish3_part(c, rows, "P", fin, 3, lo=1, hi=3, k=1.0)
    tail = set(_r2fc2_mask(rows, "T"))
    for (x, y) in tail:
        if (x + 1, y) not in tail or ((x, y - 1) not in tail and x > 12) or ((x, y + 1) not in tail and x > 12):
            c.put(x, y, teal, 3 if y < 11 else 2, 1)
    pec = set(_r2fc2_mask(rows, "P"))
    for (x, y) in pec:
        if (x, y + 1) not in pec:
            c.put(x, y, teal, 3, 3)
    # the body in flat slate: a lit back and brow, darker toward the tail stalk, one pale belly tone
    body = set(_r2fc2_mask(rows, "B"))
    for (x, y) in body:
        if (x, y + 1) not in body or ((x, y + 2) not in body and x <= 9):
            c.put(x, y, b, 3, 2)
        elif ((x, y - 1) not in body and x <= 10) or ((x - 1, y) not in body and y < 9):
            c.put(x, y, a, 4, 2)
        elif x >= 10 or (x + 1, y) not in body:
            c.put(x, y, a, 2, 2)
        else:
            c.put(x, y, a, 3, 2)
    c.dots([(3, 8), (6, 7), (8, 9), (5, 10), (10, 10)], spot, 4, z=2)
    # the sail: bright rays, blue membrane (darker at its base), the dark back outline under it
    c.dots(_r2fc2_mask(rows, "R"), ray, 4, z=4)
    c.dots(_r2fc2_mask(rows, "M"), mem, 3, z=4)
    c.dots(_r2fc2_mask(rows, "m"), mem, 2, z=4)
    c.dots(_r2fc2_mask(rows, "k"), ink[0], 0, z=4)
    for (ex, ey) in ((1, 4), (4, 4)):       # eye domes: lit top-left, the dark pupil low and forward
        c.put(ex, ey, eye, 5, 60).put(ex + 1, ey, eye, 4, 60).put(ex + 1, ey + 1, eye, 3, 60)
        c.put(ex, ey + 1, "roe", 0, 60)
    return _fish4_glow(c.render(contact=False), "#5ad0ff")


# ---- 틸라피아: corner to corner like cod. Drawn on the diagonal: t = x - y runs along the fish (snout -12,
#      tail rim 11), s = x + y across it (back small, belly large). A deep grey-olive body in a flat fill with a
#      lit back staircase, three clean 2 px dark bars and a pale belly; a long spiny dorsal ridge with three
#      red spine tips that stops two pixels short of the tail; a narrow stalk, then the broad square tail as
#      one solid block: a light base, two dark 1 px cross-stripes with 2 px light between them, and a red-orange
#      trailing rim two pixels thick, outlined beyond. ----
_r2fc2_tilapia_body = {-12: (14, 16), -11: (13, 17), -10: (12, 18), -9: (11, 19), -8: (11, 19), -7: (11, 19),
                       -6: (11, 19), -5: (11, 19), -4: (11, 19), -3: (11, 19), -2: (11, 19), -1: (11, 19),
                       0: (11, 19), 1: (11, 17), 2: (12, 18), 3: (13, 17), 4: (14, 16)}


_r2fc2_tilapia_tail = {5: (14, 16), 6: (13, 17), 7: (12, 18), 8: (11, 19), 9: (11, 19), 10: (12, 18), 11: (13, 17)}


def _r2fc2_diag(spans):
    """{t: (s0, s1)} -> [(x, y, t, s)] for the pixels with s0 <= x + y <= s1 on each diagonal t = x - y"""
    return [(x, y, x - y, x + y) for x in range(16) for y in range(16)
            if x - y in spans and spans[x - y][0] <= x + y <= spans[x - y][1]]


def icon_tilapia():
    """틸라피아: a deep grey-olive cichlid with dark bars, a long spiny dorsal fin with red tips and the broad
    square tail striped across and edged red-orange that marks it."""
    a, b = _fish4_m("#7a8470"), _fish4_m("#dcdecc")
    fin = _fish4_m("#9aa08e")
    red = _fish4_m("#e85a34", C=0.15)
    ink = _fish4_ink("#262c1c", 0.22)
    c = _fish4_canvas(ink, a, b, fin, red)
    # the tail: light base (t 5), dark stripe (6), light (7-8), dark stripe (9), red-orange rim (10-11)
    for (x, y, t, s) in _r2fc2_diag(_r2fc2_tilapia_tail):
        if t >= 10:
            c.put(x, y, red, 3, 0)
        elif t in (6, 9):
            c.put(x, y, a, 1, 0)
        else:
            c.put(x, y, fin, 3 if s < 16 else 2, 0)
    # the long spiny dorsal along the back (s 9-10, t -6..2), red spine tips every 4 pixels
    for (x, y, t, s) in _r2fc2_diag({t: (9, 10) for t in range(-6, 3)}):
        c.put(x, y, fin, 2, 1)
    for (x, y, t, s) in _r2fc2_diag({t: (8, 8) for t in (-6, -2, 2)}):
        c.put(x, y, red, 3, 1)
    # the body: a pale belly, three 2 px bars, a lit back, the flat grey-olive fill
    for (x, y, t, s) in _r2fc2_diag(_r2fc2_tilapia_body):
        if s >= 17:
            c.put(x, y, b, 3, 2)
        elif t in (-6, -5, -2, -1, 2, 3):
            c.put(x, y, a, 1, 2)
        else:
            c.put(x, y, a, 4 if s <= 12 else 3, 2)
    c.poly([(6, 12.8), (8.6, 14.8), (9.6, 12.4)], fin, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    c.put(3, 11, "roe", 0).put(3, 10, "snow", 3)
    return c.render(contact=False)


# ------------------------------------------------------------------------------------------------
# r3_fish_c (round 3)
# ------------------------------------------------------------------------------------------------


"""r3 fish (artist c): 민어 and 노래미, hand-placed pixel by pixel, lying like vanilla cod (head bottom left,
tail top right), lit from the top-left; the canvas adds the dark outline in each fish's own hue round the
outside (4-neighbour outline, so corners stay rounded like vanilla).

croaker    민어: a big deep-bodied croaker: a dusky grey-brown back with a lit rim along the upper-left edge, a
           mid-tone row into a pale cream belly with a 3-px sheen behind the gill, a darker lower-right belly edge,
           a long low dusky dorsal fin (L 0.44, with a small lit spiny hump), pelvic and anal fins at L 0.44, and a
           broad truncate fan tail spread across the axis from (10,1) to (14,5) on a narrow stalk: lit upper lobe,
           one lit middle ray, darker lower lobe, all clearly lighter than the outline.
greenling  노래미: a chestnut-amber greenling: a blunt cod-like head, a pale-orange dorsal fin strip along the whole
           back, three dark-brown saddles (#562605-like) each two diagonals wide from the fin to the midline with two
           diagonals of mid amber (#a8743e-like) between them, a pale belly, a long orange anal fin along the belly,
           a narrow tail stalk and a forked tail with an open V notch.
"""


def _r3fishc_m(tag, hexcol, lo, hi, C=None):
    """A palette material in the hue of hexcol whose six ramp steps run evenly from lightness lo to hi
    (step i = lo + (hi - lo) * i / 5), so each painted step lands on a chosen OKLab lightness."""
    name = f"r3fishc_{tag}"
    if name not in palette.MATERIALS:
        _l, ch, h = rgb_to_oklch(tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5)))
        span = (hi - lo) / 1.75
        chroma = (ch if C is None else C) / 1.512
        palette.MATERIALS[name] = (h, chroma, lo + 0.8 * span, span, 5, False)
    return name


def _r3fishc_draw(rows, key, ink, z=5):
    """Hand-placed shape pixels: each character of rows is a key into (material, step); '.' stays empty.
    Every material gets the fish's outline ink, which the canvas lays round the outside."""
    c = Canvas()
    for m, _s in key.values():
        c.ink[m] = ink
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                m, s = key[ch]
                c.put(x, y, m, s, z)
    return c.render(contact=False)


# ---- 민어 ----------------------------------------------------------------------------------------------
# R back rim (lit)  K back  M mid-tone row  W belly  S sheen  v w lower-right belly edge (shade, edge)
# d D dorsal fin (lit hump, fin)  P pelvic / anal fin  f g F e tail (lit upper lobe, ray, fan, lower lobe)
# E H eye, glint
_r3fishc_CROAKER = [
    "................",
    "..........ffg...",
    "..........fgFg..",
    "..........fFgFF.",
    ".....dd...fgFFe.",
    ".....DDDKKgFFee.",
    "....DRKKMWw.....",
    "...DRKKMWWw.....",
    "...RKKMSWvwP....",
    "..RKKMSSWvwP....",
    ".RKKHWWWWvw.....",
    ".RKEMWWWvw......",
    ".KMMWWWvw.......",
    ".MWWWWvw........",
    "..wwwwwPP.......",
    "................",
]


def icon_croaker():
    """민어: a big deep-bodied croaker: a dusky grey-brown back lit along its upper-left rim, a soft mid-tone
    row into a pale cream belly with a small sheen behind the gill, one long low dusky dorsal fin, small pelvic
    and anal fins, and a broad truncate fan tail on a narrow stalk."""
    back = _r3fishc_m("cro_back", "#6f6257", 0.37, 0.72, C=0.022)       # 0.37 .44 .51 .58 .65 .72
    belly = _r3fishc_m("cro_belly", "#d6cebd", 0.56, 0.91, C=0.024)     # 0.56 .63 .70 .77 .84 .91
    fin = _r3fishc_m("cro_fin", "#5c4f43", 0.30, 0.65, C=0.024)         # 0.30 .37 .44 .51 .58 .65
    ink = (_r3fishc_m("cro_ink", "#261d16", 0.24, 0.49, C=0.02), 0)     # outline 0.24
    key = {"R": (back, 4), "K": (back, 2), "M": (belly, 1), "W": (belly, 3), "S": (belly, 4),
           "v": (belly, 2), "w": (belly, 1), "d": (fin, 3), "D": (fin, 2), "P": (fin, 2),
           "f": (fin, 4), "g": (fin, 3), "F": (fin, 2), "e": (fin, 1), "E": ("roe", 0), "H": ("snow", 2)}
    return _r3fishc_draw(_r3fishc_CROAKER, key, ink)


# ---- 노래미 --------------------------------------------------------------------------------------------
# f g dorsal fin strip (lit edge, fin)  A a amber back (lit rim, back)  S dark saddle  q r belly (pale, shaded
# edge)  h anal fin  t T tail (lit upper lobe, fin)  E H eye, glint
_r3fishc_GREENLING = [
    "................",
    "..........tt....",
    "..........tT....",
    "......ffgaaTT...",
    "....ffgSaaaaTTT.",
    "...fgaaSSaq..TT.",
    "..fgSSaaSqr.....",
    "..gaaSSaqqr.....",
    ".ASSaaSqqrhh....",
    ".AaSSaqqrhh.....",
    ".AHaSqqrh.......",
    ".AEaqqr.........",
    "..aqr...........",
    "................",
    "................",
    "................",
]


def icon_greenling():
    """노래미: a chestnut-amber greenling of the rocky shore lying like vanilla cod: a blunt head, one long low
    orange dorsal fin running the whole back, three dark-brown saddles crossing the amber back from the fin to
    the midline, a pale belly, a long anal fin, a narrow tail stalk and a forked tail."""
    back = _r3fishc_m("grn_back", "#a8743e", 0.39, 0.74, C=0.08)        # 0.39 .46 .53 .60 .67 .74
    belly = _r3fishc_m("grn_belly", "#c8a878", 0.55, 0.90, C=0.06)      # 0.55 .62 .69 .76 .83 .90
    fin = _r3fishc_m("grn_fin", "#b8783e", 0.40, 0.75, C=0.13)          # 0.40 .47 .54 .61 .68 .75
    sad = _r3fishc_m("grn_saddle", "#562605", 0.26, 0.61, C=0.085)      # 0.26 .33 .40 .47 .54 .61
    ink = (_r3fishc_m("grn_ink", "#2a1606", 0.20, 0.45, C=0.04), 0)     # outline 0.20
    key = {"f": (fin, 5), "g": (fin, 4), "A": (back, 4), "a": (back, 3), "S": (sad, 1), "q": (belly, 3),
           "r": (belly, 1), "h": (fin, 2), "t": (fin, 4), "T": (fin, 2), "E": ("roe", 0), "H": ("snow", 2)}
    return _r3fishc_draw(_r3fishc_GREENLING, key, ink)


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
    "giant_squid": icon_giant_squid,
    "greenland_shark": icon_greenland_shark,
    "hairtail": icon_hairtail,
    "horse_mackerel": icon_horse_mackerel,
    "mahi_mahi": icon_mahi_mahi,
    "pollack": icon_pollack,
    "sardine": icon_sardine,
    "black_rockfish": icon_black_rockfish,
    "rockfish": icon_rockfish,
    "goby": icon_goby,
    "lenok": icon_lenok,
    "lionfish": icon_lionfish,
    "loach": icon_loach,
    "spore_goby": icon_spore_goby,
    "discus": icon_discus,
    "guppy": icon_guppy,
    "moon_goldfish": icon_moon_goldfish,
    "mudskipper": icon_mudskipper,
    "king_mudskipper": icon_king_mudskipper,
    "tilapia": icon_tilapia,
    "croaker": icon_croaker,
    "greenling": icon_greenling,
}
