"""마을버스 분실물 (bus.yml events.lost kinds): what passengers leave on the bus, economy:lost_<kind>. 16x16 on the same pixelart
Canvas and palette materials as economy_goods.py (light from the top left, the darkest step as the outline), a little rounder
and brighter than the job goods: they belong to someone and should read at a glance in the hotbar. economy_icons.ITEMS takes these in.

    python3 tools/assetgen/bus_lost_icons.py [out_dir]    # writes only these icons (and a preview sheet when out_dir is given)
"""
import math

from stsv_assets.pixelart import Canvas

ICONS = {}


def icon(fn):
    ICONS["lost_" + fn.__name__] = lambda: fn().render()
    return fn


@icon
def wallet():
    """지갑: a plump brown leather bifold, its flap open a little: a gold coin and a pink card peek out, stitching round the edge."""
    c = Canvas()
    c.rect(1.4, 5.0, 14.6, 13.8, "leather", 1, normal=(-0.15, -0.1, 1), lo=1, hi=4)
    body = c.last()
    c.lit_edge(body, 1)
    c.rect(3.0, 2.4, 9.4, 6.2, "pink", 0.5, normal=(-0.2, -0.2, 1), lo=2, hi=4)           # a card behind
    c.line(4, 4, 8, 4, "pink", 1, z=1)
    c.ellipse(11.4, 4.6, 2.4, 2.4, "gold", 0.6, lo=1, hi=4)                               # a coin
    c.put(11, 4, "gold", 5, z=2).put(12, 5, "gold", 2, z=2)
    c.poly([(1.4, 5.0), (14.6, 5.0), (14.6, 9.0), (9.2, 10.4), (1.4, 9.0)], "leather", 2, normal=(-0.2, -0.45, 0.85), lo=2, hi=5)  # the flap
    c.ellipse(9.2, 9.6, 1.5, 1.4, "brass", 3, lo=2, hi=5)                                 # the snap
    for x in range(3, 14, 2):                                                             # stitches
        c.put(x, 12, "cream", 2, z=4)
    c.put(2, 7, "cream", 2, z=4).put(13, 7, "cream", 2, z=4)
    return c


@icon
def umbrella():
    """우산: an open umbrella, red and cream gores with a scalloped hem, a little knob on top and a wooden crook handle."""
    c = Canvas()
    c.ellipse(8.0, 8.6, 7.3, 6.8, "fabric_red", 1, lo=1, hi=4, dome=0.8)
    c.erase([(x, y) for x in range(16) for y in range(9, 16)])                            # the dome only
    for hx in (3.4, 8.0, 12.6):                                                          # the scallops between the ribs
        c.erase([(x, y) for x in range(16) for y in range(6, 9) if ((x + 0.5 - hx) / 1.9) ** 2 + ((y + 0.5 - 9.4) / 1.3) ** 2 <= 1])
    for x, y in c.where("fabric_red"):                                                    # every other gore cream
        a = math.degrees(math.atan2(y + 0.5 - 1.8, x + 0.5 - 8.0))
        if 55 < a < 80 or 100 < a < 125 or a < 32 or a > 148:
            c.put(x, y, "cream", min(5, c.px[(x, y)][1] + 1), z=1.2)
    c.put(8, 1, "wood", 3, z=2).put(7, 1, "wood", 2, z=2)
    c.line(8, 9, 8, 13, "wood_dark", 3, z=2)                                              # the shaft
    c.tube([(8.0, 12.8), (7.6, 14.4), (5.8, 14.4), (5.2, 12.8)], [0.6, 0.65, 0.65, 0.6], "wood", 2, lo=2, hi=4)
    c.dots([(5, 4), (6, 3)], "fabric_red", 5)
    return c


@icon
def phone():
    """휴대폰: a pink-cased phone, its screen lit sky blue with a chat bubble heart, buzzing (little lines either side)."""
    c = Canvas()
    c.rect(4.0, 1.4, 12.0, 14.6, "pink", 1, normal=(-0.2, -0.1, 1), lo=1, hi=4)
    c.lit_edge(c.last(), 1)
    c.rect(5.0, 2.6, 11.0, 12.2, "signal_blue", 2, normal=(-0.25, -0.25, 1), lo=3, hi=5)
    c.rect(6.0, 4.4, 10.2, 7.8, "snow", 3, normal=(0, 0, 1), lo=4, hi=4)                 # the chat bubble
    c.put(6, 8, "snow", 4, z=3)
    c.dots([(7, 5), (9, 5), (7, 6), (8, 6), (9, 6), (8, 7)], "crimson", 3, z=4)          # a heart in it
    c.put(8, 13, "pink", 0, z=3)                                                          # the button
    for y in (4, 8):                                                                      # buzz
        c.put(2, y, "lamp", 4).put(1, y + 1, "lamp", 3)
        c.put(13, y, "lamp", 4).put(14, y + 1, "lamp", 3)
    return c


def _lunchbox(c, spoiled=False):
    """An open tin lunchbox seen from above at a slant: rice on the left, side dishes on the right."""
    c.poly([(1.0, 5.0), (15.0, 5.0), (15.0, 13.4), (1.0, 13.4)], "steel", 0, normal=(-0.1, 0.4, 0.9), lo=1, hi=3)
    c.poly([(2.0, 4.0), (14.0, 4.0), (14.0, 11.6), (2.0, 11.6)], "steel", 0.5, normal=(-0.2, -0.3, 1), lo=2, hi=4)
    rice, egg, sausage, greens = ("rice", "petal_yellow", "tomato", "leaf") if not spoiled else ("melon_rind", "doenjang", "soil", "leaf_dark")
    c.rect(2.8, 4.8, 8.4, 10.8, rice, 1, normal=(-0.1, -0.2, 1), lo=2 if spoiled else 3, hi=4)
    c.line(9, 5, 9, 10, "steel", 1, z=1.5)                                                # the divider
    c.rect(9.6, 4.8, 13.2, 7.2, egg, 1, normal=(-0.2, -0.3, 1), lo=2, hi=4)               # rolled egg
    c.ellipse(10.6, 9.2, 1.4, 1.2, sausage, 1.2, lo=1, hi=4)
    c.ellipse(12.6, 9.4, 1.1, 1.3, greens, 1.1, lo=1, hi=4)
    if not spoiled:
        c.dots([(3, 5), (7, 5), (3, 9), (7, 10)], "soot", 1, z=2)                         # black sesame
        c.ellipse(5.6, 7.8, 1.3, 1.3, "crimson", 2, lo=2, hi=5)                           # a pickled plum
        c.put(11, 5, "petal_yellow", 5, z=2)
    else:
        c.dots([(4, 6), (6, 9), (7, 6)], "slime", 3, z=2)                                 # mould
        c.dots([(5, 8), (12, 5)], "musk", 2, z=2)
        for x0 in (4.0, 9.0):                                                             # the smell rising
            c.tube([(x0, 3.0), (x0 + 1.0, 2.0), (x0, 1.0), (x0 + 1.0, 0.4)], [0.45, 0.45, 0.45, 0.4], "slime", 3, lo=3, hi=4, caps=False)
        c.put(13, 2, "soot", 1).put(12, 1, "ice", 4).put(14, 1, "ice", 4)                 # a fly
    return c


@icon
def lunchbox():
    """도시락: an open tin lunchbox, white rice with sesame and a pickled plum, a rolled egg, a sausage and greens."""
    return _lunchbox(Canvas())


@icon
def lunchbox_spoiled():
    """쉰 도시락: the same lunchbox gone off: greyish-green rice with mould, brown dishes, green smell lines and a fly."""
    return _lunchbox(Canvas(), spoiled=True)


@icon
def dentures():
    """틀니: a set of dentures grinning open, a band of pink gum top and bottom, square white teeth and the dark mouth between."""
    c = Canvas()
    rows = ["................",
            "................",
            "................",
            "....GGGGGGGG....",
            "..GGGGGGGGGGGG..",
            ".GGTTTTTTTTTTGG.",
            ".GTTTTTTTTTTTTG.",
            ".GTTTTTTTTTTTTG.",
            ".GMTTTTTTTTTTMG.",
            ".GMMMMMMMMMMMMG.",
            ".GGMTTTTTTTTMGG.",
            "..GTTTTTTTTTTG..",
            "..GGGGGGGGGGGG..",
            "....GGGGGGGG....",
            "................",
            "................"]
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "G":
                lit = y in (3, 4) or (x <= 2 and y < 9)
                c.put(x, y, "pink", 4 if lit else 2 if y >= 12 or x >= 13 else 3, z=1)
            elif ch == "T":
                gap = x % 2 == 1                                                          # the line between two teeth
                c.put(x, y, "porcelain", (2 if gap else 4) if y < 9 else (1 if gap else 3), z=2)
            elif ch == "M":
                c.put(x, y, "wine", 0, z=1)
    c.dots([(3, 5), (4, 5)], "porcelain", 5)                                              # the shine on the front teeth
    c.dots([(5, 3), (6, 3)], "pink", 5)
    return c


@icon
def leash():
    """강아지 목줄: a red collar loop with a gold bone tag, its leash coiling away to a looped handle."""
    c = Canvas()
    c.ellipse(6.0, 7.6, 4.8, 4.8, "fabric_red", 1, lo=1, hi=4)
    c.hole(6.0, 7.6, 3.0, 3.0)
    c.tube([(10.0, 10.0), (12.6, 12.6), (14.0, 10.4), (12.0, 8.4), (12.8, 5.0), (13.2, 2.2)], [0.6] * 6, "fabric_red", 0.5, lo=1, hi=4)
    c.ellipse(13.0, 2.4, 1.8, 1.6, "fabric_red", 0.4, lo=1, hi=4)                        # the handle loop
    c.hole(13.0, 2.4, 0.7, 0.6)
    c.ellipse(10.4, 10.2, 1.1, 1.1, "steel", 2, lo=2, hi=5)                               # the clip ring
    # the bone tag hanging below the collar
    c.rect(4.6, 12.0, 7.4, 13.4, "gold", 2, normal=(-0.2, -0.2, 1), lo=2, hi=4)
    for x, y in ((4.2, 12.0), (4.2, 13.4), (7.8, 12.0), (7.8, 13.4)):
        c.ellipse(x, y, 0.9, 0.9, "gold", 2.1, lo=2, hi=4)
    c.put(6, 11, "steel", 3, z=3)
    c.dots([(3, 4), (4, 3)], "fabric_red", 5)
    return c


@icon
def loveletter():
    """연애편지: a pale pink envelope, its flap closed with a red heart seal, two little hearts floating up."""
    c = Canvas()
    c.rect(1.0, 5.0, 15.0, 14.0, "cream", 1, normal=(-0.15, -0.1, 1), lo=2, hi=4)
    c.tone(c.last(), 0)
    c.poly([(1.0, 14.0), (8.0, 9.4), (15.0, 14.0)], "cream", 1.2, normal=(0, 0.5, 0.9), lo=2, hi=3)
    c.poly([(1.0, 5.0), (15.0, 5.0), (8.0, 10.6)], "pink", 1.4, normal=(-0.1, -0.5, 0.9), lo=2, hi=4)   # the flap
    # the heart seal
    c.ellipse(7.0, 9.4, 1.3, 1.3, "crimson", 2, lo=2, hi=4).ellipse(9.0, 9.4, 1.3, 1.3, "crimson", 2, lo=2, hi=4)
    c.poly([(5.8, 9.8), (10.2, 9.8), (8.0, 12.4)], "crimson", 2, normal=(0, 0.2, 1), lo=2, hi=3)
    c.put(7, 9, "pink", 5, z=3)
    for x, y in ((12, 1), (14, 3)):                                                       # hearts floating up
        c.put(x, y, "crimson", 4).put(x + 1, y, "crimson", 4).put(x, y + 1, "crimson", 3).put(x + 1, y + 1, "crimson", 3)
    return c


def _ball(c, cx=8.0, cy=8.0, r=6.4, z=1):
    """A white baseball, red stitched seams curving in on both sides, a blue autograph scrawled across."""
    c.ellipse(cx, cy, r, r, "snow", z, lo=1, hi=4)
    ball = set(c.last())
    for side in (-1, 1):                                                                  # the seams: arcs of a bigger circle
        ox, rr = cx - side * r * 1.45, r * 1.05
        for k in range(-9, 10):
            t = math.radians(k * 5.2)
            x, y = ox + side * rr * math.cos(t), cy + rr * math.sin(t)
            p = (int(x), int(y))
            if p in ball:
                c.put(*p, "fabric_red", 2, z=z + 1)
                q = (p[0] - side, p[1])
                if k % 2 == 0 and q in ball:
                    c.put(*q, "fabric_red", 3, z=z + 1)                                # the V stitches
    sig = [(-3, 1), (-2, 0), (-2, -1), (-1, 0), (0, 1), (1, 0), (1, -1), (2, 0), (3, 0)]
    for dx, dy in sig:
        c.put(round(cx + dx), round(cy + dy), "signal_blue", 1, z=z + 2)
    return c


@icon
def signedball():
    """사인볼: a white baseball with red stitches and a blue autograph across it."""
    c = Canvas()
    _ball(c)
    c.dots([(5, 4), (6, 3)], "snow", 5)
    return c


@icon
def signedball_keepsake():
    """사인볼 (기념품): the signed ball on a little wooden stand with a gold plate, a sparkle over it (its owner never came)."""
    c = Canvas()
    c.poly([(2.0, 12.0), (14.0, 12.0), (15.0, 15.0), (1.0, 15.0)], "wood", 0, normal=(-0.1, -0.4, 0.9), lo=1, hi=4)
    c.rect(5.4, 12.8, 10.6, 14.2, "gold", 0.5, normal=(-0.2, -0.2, 1), lo=2, hi=5)
    c.rect(6.4, 10.6, 9.6, 12.2, "wood_dark", 0.3, normal=(-0.3, 0, 1), lo=1, hi=3)      # the cup it sits in
    _ball(c, 8.0, 6.4, 5.0, 1)
    c.dots([(6, 3)], "snow", 5)
    for x, y in ((13, 2), (2, 4)):                                                        # sparkles
        c.put(x, y, "lamp", 5).put(x - 1, y, "lamp", 3).put(x + 1, y, "lamp", 3).put(x, y - 1, "lamp", 3).put(x, y + 1, "lamp", 3)
    return c


@icon
def hat():
    """모자: a straw sun hat seen a little from the front, a red ribbon round its crown tied in a bow."""
    c = Canvas()
    c.ellipse(8.0, 11.4, 7.5, 2.7, "straw", 0, lo=2, hi=4, dome=0.5)                    # the brim
    c.ellipse(8.0, 8.8, 4.4, 4.8, "straw", 1, lo=1, hi=4)                                # the crown
    c.erase([(x, y) for x in range(16) for y in range(11, 16) if c.px.get((x, y), [0, 0, 0])[2] == 1])
    c.tone([(x, y) for x, y in c.where("straw") if (x * 3 + y * 5) % 7 == 0], -1)          # the weave
    c.rect(3.6, 8.6, 12.4, 10.6, "fabric_red", 2, normal=(-0.2, 0, 1), lo=2, hi=4)       # the ribbon
    c.ellipse(11.6, 9.6, 1.5, 1.3, "fabric_red", 3, lo=2, hi=5)                          # the bow
    c.line(12, 11, 13, 13, "fabric_red", 2, z=3)
    c.dots([(6, 5), (7, 5)], "straw", 5)
    return c


@icon
def key():
    """열쇠: a brass house key on a ring with a little pink pom-pom charm."""
    c = Canvas()
    c.ellipse(4.6, 4.6, 3.6, 3.6, "brass", 1, lo=1, hi=4)                                # the bow
    c.hole(4.6, 4.6, 1.4, 1.4)
    c.tube([(6.6, 6.6), (13.6, 13.6)], [0.95, 0.95], "brass", 1, lo=1, hi=4)            # the blade
    c.dots([(11, 13), (12, 14), (9, 11)], "brass", 3, z=2)                                # the bit's teeth
    c.put(10, 12, "brass", 2, z=2)
    c.ellipse(12.4, 4.2, 2.4, 2.4, "steel", 0.5, lo=1, hi=4)                             # the key ring
    c.hole(12.4, 4.2, 1.5, 1.5)
    c.ellipse(13.0, 8.6, 2.0, 2.0, "pink", 2, lo=2, hi=5)                                 # the pom-pom
    c.tone([(x, y) for x, y in c.last() if (x + y) % 3 == 0], -1)
    c.dots([(3, 2), (2, 3)], "brass", 5)
    return c


def main(out=None):
    """Writes only these icons into the economy pack's textures (never touches the others), and a 6x preview sheet to `out`."""
    from pathlib import Path

    from PIL import Image, ImageDraw
    tex = Path(__file__).resolve().parents[2] / "economy" / "content" / "economy" / "textures"
    names = list(ICONS)
    for n in names:
        ICONS[n]().save(tex / f"{n}.png")
    if out:
        out = Path(out)
        out.mkdir(parents=True, exist_ok=True)
        s, cols = 6, 6
        rows = (len(names) + cols - 1) // cols
        im = Image.new("RGBA", (cols * 18 * s, rows * 18 * s + rows * 14), (54, 54, 62, 255))
        d = ImageDraw.Draw(im)
        for k, n in enumerate(names):
            x, y = (k % cols) * 18 * s + s, (k // cols) * (18 * s + 14) + s
            ic = ICONS[n]().resize((16 * s, 16 * s), Image.NEAREST)
            im.alpha_composite(ic, (x, y))
            d.text((x, y + 16 * s + 1), n.replace("lost_", ""), fill=(220, 220, 220, 255))
        im.save(out / "lost_items.png")
        for n in names:
            ICONS[n]().resize((128, 128), Image.NEAREST).save(out / f"{n}.png")
    return names


if __name__ == "__main__":
    import sys
    print(main(sys.argv[1] if len(sys.argv) > 1 else None))
