"""
Rule-based 16x16 item icons (tools, weapons, armor) using the shared palette.

Same rules as every other STSV texture:
  - colours only from palette ramps
  - light from the top-left (upper/left edge of a stroke is lighter)
  - outline = darkest step of the neighbouring part's ramp, drawn automatically

Orientation follows vanilla so the vanilla parent models (bow, crossbow, handheld, handheld_rod)
hold them at the right angle: blades/rods point to the top-right, bows shoot to the top-left.
"""

import math

from PIL import Image

from .palette import ramp

S = 16


class Icon:
    def __init__(self, w=S, h=S):
        self.w, self.h = w, h
        self.px = {}          # (x, y) -> (ramp_name, index)

    def put(self, x, y, r, i):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[(x, y)] = (r, i)
        return self

    def line(self, p0, p1, r, i):
        """Bresenham line."""
        (x0, y0), (x1, y1) = p0, p1
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.put(x0, y0, r, i)
            if (x0, y0) == (x1, y1):
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy
        return self

    def diagonal(self, x0, y0, length, r, light, dark, width=2, direction=(1, -1)):
        """A stroke along a 45° direction; the upper-left side gets `light`, the other `dark`."""
        dx, dy = direction
        for k in range(length):
            x, y = x0 + dx * k, y0 + dy * k
            self.put(x, y, r, light)
            if width >= 2:
                self.put(x + 1, y, r, dark)  # right neighbour = lower-right side of a / stroke
            if width >= 3:
                self.put(x + 1, y + 1, r, dark - 1)
        return self

    def arc(self, cx, cy, radius, a0, a1, r, i, steps=80):
        for k in range(steps + 1):
            a = math.radians(a0 + (a1 - a0) * k / steps)
            self.put(round(cx + radius * math.cos(a)), round(cy + radius * math.sin(a)), r, i)
        return self

    def outline(self):
        filled = dict(self.px)
        for (x, y), (r, _) in filled.items():
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                n = (x + dx, y + dy)
                if n not in filled and 0 <= n[0] < self.w and 0 <= n[1] < self.h and n not in self.px:
                    self.px[n] = (r, 0)
        return self

    def image(self):
        im = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
        for (x, y), (r, i) in self.px.items():
            im.putpixel((x, y), ramp(r, i))
        return im


# ------------------------------------------------------------------------------------------------
# Weapons & tools
# ------------------------------------------------------------------------------------------------

def knight_sword():
    ic = Icon()
    # blade: from just above the guard to the tip at the top-right, 2 px wide
    for k in range(9):
        x, y = 6 + k, 9 - k
        ic.put(x, y, "steel", 5 if k > 6 else 4)      # edge catching the light
        ic.put(x + 1, y, "steel", 3)
        if k < 7:
            ic.put(x + 1, y + 1, "steel", 2)          # spine shadow
    ic.put(15, 0, "steel", 5)
    # guard: perpendicular bar
    for k in range(-2, 3):
        ic.put(5 + k, 9 + k, "brass", 3 if k < 0 else 2)
    ic.put(3, 7, "brass", 1)
    ic.put(7, 11, "brass", 1)
    # grip with leather wrap and pommel
    for k, (x, y) in enumerate(((4, 11), (3, 12), (2, 13))):
        ic.put(x, y, "fabric_red", 2 if k % 2 else 1)
        ic.put(x + 1, y, "fabric_red", 1)
    ic.put(1, 14, "brass", 3)
    ic.put(2, 14, "brass", 2)
    ic.put(1, 15, "brass", 1)
    return ic.outline().image()


def _bow(pull):
    """pull: None (standby) or 0..2. The arc bulges to the top-left; the string runs bottom-left -> top-right."""
    ic = Icon()
    flex = 0 if pull is None else pull          # limbs bend a little more when drawn
    r = 11.5 - flex * 0.5
    ic.arc(14, 14, r, 180, 270, "wood", 2)
    ic.arc(14, 14, r - 1, 182, 268, "wood", 3)
    # grip wrap at the middle of the arc
    for a in (220, 225, 230):
        x = round(14 + (r - 0.5) * math.cos(math.radians(a)))
        y = round(14 + (r - 0.5) * math.sin(math.radians(a)))
        ic.put(x, y, "fabric_red", 2)
    top = (14, round(14 - r))
    bottom = (round(14 - r), 14)
    if pull is None:
        ic.line(bottom, top, "stone", 3)
    else:
        p = (9 + pull, 9 + pull)
        ic.line(bottom, p, "stone", 3).line(p, top, "stone", 3)
        # arrow: shaft from the string towards the top-left, steel head, fletching at the nock
        for k in range(1, 7 + pull):
            ic.put(p[0] - k, p[1] - k, "wood_dark", 3)
        hx, hy = p[0] - 7 - pull, p[1] - 7 - pull
        ic.put(hx, hy, "steel", 5).put(hx + 1, hy, "steel", 3).put(hx, hy + 1, "steel", 3)
        ic.put(p[0] - 1, p[1], "fabric_red", 2).put(p[0], p[1] - 1, "fabric_red", 2)
    return ic.outline().image()


def hunter_bow():
    return _bow(None)


def hunter_bow_pulling(n):
    return _bow(n)


def _crossbow(state):
    """state: 'standby' | 'pull0'..'pull2' | 'arrow' | 'rocket'. Shoots to the top-left.
    A wide steel prod (D-shaped bow) across the front, the wooden stock running to the bottom-right."""
    ic = Icon()
    # stock from the butt (bottom-right) to the prod (top-left), 2 px
    for k in range(11):
        x, y = 14 - k, 14 - k
        ic.put(x, y, "wood_dark", 3)
        ic.put(x + 1, y, "wood_dark", 2)
    ic.put(15, 15, "wood_dark", 1)
    ic.put(11, 13, "brass", 2).put(12, 14, "brass", 1)      # trigger
    # prod: a wide arc bulging to the top-left, tips at (1, 9) and (9, 1)
    ic.arc(10, 10, 9, 183, 267, "steel", 4)
    ic.arc(10, 10, 8, 186, 264, "steel", 2)
    tips = ((1, 9), (9, 1))
    # string: straight between the tips at rest, pulled back along the stock when spanned
    pull = {"standby": None, "pull0": 6, "pull1": 7, "pull2": 8, "arrow": 8, "rocket": 8}[state]
    if pull is None:
        ic.line(tips[0], tips[1], "stone", 3)
    else:
        hook = (pull, pull)
        ic.line(tips[0], hook, "stone", 3).line(hook, tips[1], "stone", 3)
        # the bolt lies on the stock, from the string to just past the prod
        if state == "arrow":
            for k in range(1, 6):
                ic.put(hook[0] - k, hook[1] - k, "wood", 3)
            ic.put(hook[0] - 6, hook[1] - 6, "steel", 5).put(hook[0] - 7, hook[1] - 7, "steel", 4)
        elif state == "rocket":
            for k in range(0, 6):
                ic.put(hook[0] - k, hook[1] - k, "fabric_red", 2 if k % 2 else 1)
            ic.put(hook[0] - 6, hook[1] - 6, "dial", 1)
    return ic.outline().image()


def iron_crossbow(state="standby"):
    return _crossbow(state)


def _rod(cast):
    ic = Icon()
    # rod from grip (bottom-left) to tip (top-right)
    for k in range(13):
        x, y = 1 + k, 14 - k
        ramp_name, light = ("fabric_red", 2) if k < 3 else ("wood", 3) if k < 9 else ("steel", 4)
        ic.put(x, y, ramp_name, light)
    ic.put(4, 12, "brass", 3).put(5, 12, "brass", 1)         # reel
    if not cast:
        for y in range(2, 12):
            ic.put(14, y, "stone", 3 if y % 3 else 2)          # line
        ic.put(14, 12, "steel", 4).put(13, 13, "steel", 3).put(14, 13, "steel", 2)  # hook
    return ic.outline().image()


def fishing_rod():
    return _rod(False)


def fishing_rod_cast():
    return _rod(True)


# ------------------------------------------------------------------------------------------------
# Armor icons
# ------------------------------------------------------------------------------------------------

def _mask_icon(rows, base="steel", trim="brass", trim_chars="T"):
    ic = Icon()
    h = len(rows)
    oy = (S - h) // 2
    for y, row in enumerate(rows):
        ox = (S - len(row)) // 2
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            if ch in trim_chars:
                ic.put(ox + x, oy + y, trim, 3 if y < h / 2 else 2)
                continue
            up = y == 0 or x >= len(rows[y - 1]) or rows[y - 1][x] == "."
            left = x == 0 or row[x - 1] == "."
            down = y == h - 1 or x >= len(rows[y + 1]) or rows[y + 1][x] == "."
            right = x == len(row) - 1 or row[x + 1] == "."
            i = 3 + (1 if (up or left) else 0) - (1 if (down or right) else 0)
            if up and left:
                i = 5
            ic.put(ox + x, oy + y, base, i)
    return ic.outline().image()


def steel_helmet():
    return _mask_icon([
        "...######...",
        ".##########.",
        "############",
        "TTTTTTTTTTTT",
        "###......###",
        "##........##",
        "##........##",
    ])


def steel_chestplate():
    return _mask_icon([
        "###......###",
        "####....####",
        "############",
        "#####TT#####",
        ".####TT####.",
        "..###TT###..",
        "..###TT###..",
        "..########..",
        "..TTTTTTTT..",
    ])


def steel_leggings():
    return _mask_icon([
        "TTTTTTTTTT",
        "##########",
        "##########",
        "####..####",
        "###....###",
        "###....###",
        "###....###",
        "###....###",
    ])


def steel_boots():
    return _mask_icon([
        "###....###",
        "###....###",
        "###....###",
        "####...####",
        "TTTT...TTTT",
    ])


# ------------------------------------------------------------------------------------------------
# Worn armor (64x32 humanoid equipment layout)
# ------------------------------------------------------------------------------------------------

def _box_faces(u, v, w, h, d):
    """Entity box-UV rectangles (x0, y0, x1, y1) for a cube with texture offset (u, v)."""
    return {
        "top": (u + d, v, u + d + w, v + d),
        "bottom": (u + d + w, v, u + d + 2 * w, v + d),
        "right": (u, v + d, u + d, v + d + h),
        "front": (u + d, v + d, u + d + w, v + d + h),
        "left": (u + d + w, v + d, u + 2 * d + w, v + d + h),
        "back": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
    }


HEAD = _box_faces(0, 0, 8, 8, 8)
BODY = _box_faces(16, 16, 8, 12, 4)
ARM = _box_faces(40, 16, 4, 12, 4)
LEG = _box_faces(0, 16, 4, 12, 4)


def _plate(im, rect, rows=None, trim_rows=(), trim_cols=(), rivets=True, skip=None):
    """Paint a steel plate on a face rect. rows=(first, last) limits it vertically (relative)."""
    x0, y0, x1, y1 = rect
    r0, r1 = rows if rows else (0, y1 - y0)
    for y in range(y0 + r0, y0 + r1):
        for x in range(x0, x1):
            ry, rx = y - y0, x - x0
            if skip and skip(rx, ry):
                continue
            i = 3
            if y == y0 + r0 or x == x0:
                i = 4
            if y == y0 + r1 - 1 or x == x1 - 1:
                i = 2
            c = ramp("steel", i)
            if ry in trim_rows or rx in trim_cols:
                c = ramp("brass", 2 if i <= 3 else 3)
            im.putpixel((x, y), c)
    if rivets and (x1 - x0) >= 4 and (r1 - r0) >= 4:
        for x, y in ((x0 + 1, y0 + r0 + 1), (x1 - 2, y0 + r0 + 1)):
            im.putpixel((x, y), ramp("steel", 5))


def steel_humanoid():
    """Layer used by helmet, chestplate and boots."""
    im = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    # helmet: full top, upper part of the sides, a visor opening on the front
    _plate(im, HEAD["top"], rivets=False)
    _plate(im, HEAD["front"], rows=(0, 8), trim_rows=(2,),
           skip=lambda rx, ry: 3 <= ry <= 6 and 1 <= rx <= 6)
    for f in ("right", "left", "back"):
        _plate(im, HEAD[f], rows=(0, 7), trim_rows=(2,))
    # chestplate: body + shoulders
    _plate(im, BODY["top"], rivets=False)
    _plate(im, BODY["front"], rows=(0, 10), trim_cols=(3, 4), trim_rows=(9,))
    _plate(im, BODY["back"], rows=(0, 10), trim_rows=(9,))
    for f in ("right", "left"):
        _plate(im, BODY[f], rows=(0, 10), rivets=False)
    _plate(im, ARM["top"], rivets=False)
    for f in ("right", "front", "left", "back"):
        _plate(im, ARM[f], rows=(0, 5), trim_rows=(4,), rivets=False)
    # boots: lower part of the legs + sole
    _plate(im, LEG["bottom"], rivets=False)
    for f in ("right", "front", "left", "back"):
        _plate(im, LEG[f], rows=(8, 12), trim_rows=(8,), rivets=False)
    return im


def steel_humanoid_leggings():
    im = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    for f in ("front", "back", "right", "left"):
        _plate(im, BODY[f], rows=(8, 12), trim_rows=(8,), rivets=False)
    _plate(im, LEG["top"], rivets=False)
    for f in ("right", "front", "left", "back"):
        _plate(im, LEG[f], rows=(0, 9), rivets=False)
    return im
