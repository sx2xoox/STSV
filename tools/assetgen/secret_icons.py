"""숨은 비밀 (docs/admin/숨은_비밀.md, economy/content/economy/items/secrets.yml): what the hidden horror and mystery quests
hand out, economy:<id>. 16x16 on the same pixelart Canvas and palette materials as bus_lost_icons.py (light from the top
left, the darkest step as the outline), but older and eerier: yellowed paper, damp edges, old wax, bone, brass and blood
red. Each still reads at a glance in the hotbar. Pale materials get their outline from the canvas (c.ink), not the shared
OUTLINE table, so the icons render the same alone and in the full build. economy_icons.ITEMS takes these in.

    python3 tools/assetgen/secret_icons.py [out_dir]    # writes only these icons (and a preview sheet when out_dir is given)
"""
import math

from stsv_assets.pixelart import Canvas

ICONS = {}

# outlines for the pale materials, set on each canvas
INK = {"paper": ("wood_dark", 1), "cream": ("wood_dark", 1), "washi_lit": ("wood_dark", 1), "flour": ("soil", 1),
       "porcelain": ("stone", 0), "glass": ("stone", 0), "snow": ("stone", 0)}


def icon(fn):
    def draw():
        c = Canvas()
        c.ink.update(INK)
        return fn(c).render()
    ICONS[fn.__name__] = draw
    return fn


def _edge(pts):
    """The pixels of `pts` on its own border (a neighbour is missing)."""
    s = set(pts)
    return [(x, y) for x, y in pts if any((x + dx, y + dy) not in s for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]


def _grid(c, rows, chars, mat, z, cx, cy, r, lo=1, hi=4, dome=0.7, gloss=0.9):
    """A shape cut from a hand-drawn grid (the pixels whose letter is in `chars`), shaded like a low dome around (cx, cy)."""
    def inside(x, y):
        return 0 <= int(y) < len(rows) and 0 <= int(x) < len(rows[int(y)]) and rows[int(y)][int(x)] in chars

    def normal(x, y):
        u, v = (x - cx) / r, (y - cy) / r
        return (u * dome, v * dome, math.sqrt(max(0.05, 1 - min(1.0, u * u + v * v))))
    return c._shape(inside, normal, mat, z, lo, hi, gloss, 0.0, sub=1)


def _stain(c, pts, step):
    """Re-tints paper pixels with a brown water stain at a fixed step (keeps them under later details)."""
    for p in pts:
        if p in c.px:
            c.px[p][0], c.px[p][1] = "doenjang", step


@icon
def dongbang_notice(c):
    """주주총회 소집 통지서: a yellowed notice folded in three, the creases still in it, a dark heading, faded type and a
    faded red company seal; its edges have soaked up water and dried brown, and a drip has run down from the top."""
    c.rect(2.0, 1.0, 14.0, 15.0, "washi_lit", 1, normal=(-0.2, -0.15, 1), lo=2, hi=4)
    c.rect(2.0, 5.0, 14.0, 10.0, "washi_lit", 1, normal=(0.3, 0.25, 1), lo=2, hi=3)    # the middle panel, folded back a little
    sheet = c.where("washi_lit")
    c.dots([(x, 5) for x in range(2, 14)], "washi_lit", 4, z=2)                           # the creases catch the light
    c.dots([(x, 10) for x in range(2, 14)], "washi_lit", 1, z=2)
    # damp rim: a ragged brown tide line just inside the edge, darkest at the bottom where the water stood
    for x, y in _edge(sheet):
        if y >= 13 or (x * 5 + y * 3) % 4:
            _stain(c, [(x, y)], 3 if y < 12 else 2)
    _stain(c, [(3, 14), (4, 14), (12, 14), (11, 14), (13, 12), (3, 13)], 3)
    # the heading and the type, faded
    c.dots([(x, 3) for x in range(7, 12)], "soot", 2, z=3)
    for y, x0, x1 in ((7, 7, 12), (8, 4, 10), (12, 4, 7)):
        c.dots([(x, y) for x in range(x0, x1 + 1) if (x * 3 + y) % 5], "stone", 2, z=3)
    # the seal: a faded red ring with a mark in it, a bit of it gone
    c.dots([(10, 11), (11, 11), (9, 12), (12, 12), (10, 13)], "brick", 3, z=4)
    c.dots([(11, 13)], "brick", 4, z=4)
    c.dots([(10, 12)], "brick", 2, z=4)
    # the drip from the top edge, a pear-shaped blot at its end
    c.dots([(5, 1), (5, 2), (5, 3), (5, 4)], "doenjang", 3, z=5)
    c.dots([(5, 5), (4, 5), (5, 6), (4, 6)], "doenjang", 2, z=5)
    c.dots([(4, 5)], "doenjang", 4, z=5)
    c.dots([(3, 2), (4, 2), (3, 3)], "washi_lit", 5)
    return c


@icon
def dongbang_certificate(c):
    """마지막 주주 증서: an ornate old share certificate, a blue-grey patterned border with brass corner studs, a little
    ship under sail on waves at its heart, type either side and a signature; its lower right corner is wet and grey."""
    c.rect(1.0, 2.0, 15.0, 14.0, "steel", 1, normal=(-0.2, -0.1, 1), lo=1, hi=3)          # the border
    c.rect(2.0, 3.0, 14.0, 13.0, "paper", 2, normal=(-0.15, -0.1, 1), lo=2, hi=4)
    for x in range(2, 14):                                                                 # the guilloche: light top, dark bottom
        c.put(x, 2, "steel", 4 if x % 2 else 3, z=3).put(x, 13, "steel", 1 if x % 2 else 2, z=3)
    for y in range(3, 13):
        c.put(1, y, "steel", 4 if y % 2 else 3, z=3).put(14, y, "steel", 1 if y % 2 else 2, z=3)
    for x, y in ((1, 2), (14, 2), (1, 13), (14, 13)):
        c.put(x, y, "brass", 4 if (x, y) == (1, 2) else 2, z=4)
    c.dots([(x, 4) for x in range(3, 13)], "steel", 2, z=3)                                # the inner rule
    # the ship
    c.dots([(8, 5), (8, 6), (8, 7), (8, 8)], "wood_dark", 2, z=5)                          # the mast
    c.dots([(9, 6), (9, 7), (10, 7), (9, 8), (10, 8), (11, 8)], "snow", 4, z=5)            # the sail
    c.dots([(7, 7), (7, 8), (6, 8)], "snow", 3, z=5)
    c.dots([(5, 9), (6, 9), (7, 9), (8, 9), (9, 9), (10, 9), (11, 9)], "indigo", 3, z=5)  # the hull
    c.dots([(6, 10), (7, 10), (8, 10), (9, 10), (10, 10)], "indigo", 1, z=5)
    c.dots([(4, 11), (5, 11), (7, 11), (8, 11), (10, 11), (11, 11)], "steel", 3, z=5)      # waves
    c.dots([(3, 6), (4, 6), (3, 8), (4, 8), (12, 6), (11, 6), (12, 8)], "stone", 2, z=4)   # type either side
    # the wet corner: paper gone grey and see-through, the border darker, a drop hanging off it
    for (x, y), v in c.px.items():
        if x + y >= 23:
            if v[0] == "paper":
                v[0], v[1] = "glass", 2 if x + y == 23 else 1
            elif v[0] == "steel":
                v[1] = max(0, v[1] - 1)
    c.put(12, 12, "glass", 3)
    c.ellipse(13.6, 14.9, 0.7, 0.9, "water", 3, lo=2, hi=5)
    return c


@icon
def letter_tomorrow(c):
    """편지: an old cream envelope, the flap shut with a lump of dark wine wax, the address scrawled across in a shaky
    hand and a faint grey fingerprint smudged on its lower corner."""
    c.rect(1.0, 3.0, 15.0, 14.0, "cream", 1, normal=(-0.15, -0.1, 1), lo=1, hi=3)
    env = c.last()
    c.poly([(1.0, 3.0), (15.0, 3.0), (8.0, 9.0)], "cream", 1.4, normal=(-0.1, -0.5, 0.9), lo=2, hi=4)   # the flap
    flap = c.last()
    for x, y in _edge(flap):                                                                # its edge throws a line
        if y > 3:
            c.put(x, y + 1 if (x, y + 1) not in flap else y, "wood", 3, z=1.3)
    for x, y in _edge(env):                                                                 # age at the rim
        if (x * 7 + y * 3) % 3 == 0:
            c.tone([(x, y)], -1)
    # the wax seal
    wax = {(7, 7): 4, (8, 7): 3, (6, 8): 4, (7, 8): 3, (8, 8): 1, (9, 8): 2, (6, 9): 3, (7, 9): 1, (8, 9): 2,
           (9, 9): 1, (7, 10): 2, (8, 10): 1, (8, 11): 1}
    for (x, y), s in wax.items():
        c.put(x, y, "wine", s, z=4)
    c.put(7, 7, "wine", 5, z=4)
    # the address, scrawled in two shaky lines
    for x, y in ((10, 10), (11, 10), (12, 9), (13, 10), (10, 12), (11, 12), (12, 11)):
        c.put(x, y, "soot", 2, z=3)
    # the fingerprint, a grey whorl smudged on the corner
    for (x, y), st in {(3, 10): 3, (4, 10): 3, (2, 11): 3, (5, 11): 3, (2, 12): 3, (4, 12): 3, (5, 12): 4, (3, 12): 4,
                       (4, 11): 4}.items():
        c.put(x, y, "stone", st, z=3)
    c.dots([(2, 4), (3, 4)], "cream", 4)
    return c


@icon
def designer_rib(c):
    """갈비뼈 프레임: a rib cage made as a machine part: four pairs of bone-white ribs sloping out and down from an iron
    spine, the floating pair short, each riveted on where it meets the spine."""
    c.ink["bean"] = INK["flour"]
    c.tube([(8.0, 1.2), (8.0, 14.8)], [1.0, 1.0], "iron", 1, lo=1, hi=4)                   # the spine
    reach = (3, 2, 1, 4)                                                                    # how far out each rib goes
    for k, y0 in enumerate((2, 5, 8, 11)):
        for x in range(6, reach[k] - 1, -1):
            y = y0 + max(0, 4 - x)
            tip = x == reach[k]
            for side in (0, 1):
                px = x if side == 0 else 15 - x
                top = (4 if x > 3 else 3) - side - (1 if tip else 0)
                c.put(px, y, "flour", max(1, top + 1), z=2)
                c.put(px, y + 1, "bean", max(2, top), z=2)
    for y in range(1, 15):                                                                  # vertebrae between the ribs
        if y % 3 == 1:
            c.tone([(7, y), (8, y)], -1)
    for y0 in (2, 5, 8, 11):                                                                # the rivets
        c.put(6, y0, "steel", 5, z=5).put(9, y0, "steel", 3, z=5)
    for x in range(16):                                                                     # shadow in the gaps between the ribs
        for y in range(1, 15):
            if (x, y) not in c.px and (x, y - 1) in c.px and (x, y + 1) in c.px:
                c.put(x, y, *INK["flour"], z=0)
    return c


@icon
def designer_vein(c):
    """핏줄 호스: a red hose coiled flat on itself like a wound vein, two turns with deep shadow between them, swelling
    and narrowing as it goes, a thin dark vine twined along it; its end runs out to a brass nozzle with a drop of blood."""
    cx, cy, half = 6.6, 9.0, 0.9
    rings = (4.9, 2.1)

    def coil(x, y):
        """How far across the hose (0 at its middle, +-1 at its sides), or None off it."""
        d = math.hypot(x - cx, y - cy)
        t = math.atan2(y - cy, x - cx)
        for k, rc in enumerate(rings):
            w = half * (1 + 0.12 * math.sin(t * 4 + k * 2))                                  # it pulses
            if abs(d - rc) <= w:
                return (d - rc) / w
        return None

    def normal(x, y):
        u = coil(x, y) or 0.0
        d = math.hypot(x - cx, y - cy) or 1
        return ((x - cx) / d * u, (y - cy) / d * u, math.sqrt(max(0.05, 1 - u * u)))
    c.ellipse(cx, cy, rings[0] + 0.6, rings[0] + 0.6, "soot", 0, lo=0, hi=0, gloss=2)        # the dark between the turns
    c.hole(cx, cy, 1.2, 1.2)
    c._shape(lambda x, y: coil(x, y) is not None, normal, "crimson", 1, 1, 5, 0.93, 0.15)
    def p(r, a):
        return cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))
    # where the hose steps from the inner turn to the outer one
    c.tube([p(rings[1], 150), p(3.9, 175), p(rings[0], 200)], [0.9, 0.95, 0.9], "crimson", 2, lo=1, hi=5, gloss=0.93, bias=0.15)
    # the end runs out to the nozzle
    ex, ey = p(rings[0], -40)
    c.tube([(ex, ey), (10.6, 4.4), (11.8, 3.4)], [0.95, 0.9, 0.85], "crimson", 1.5, lo=1, hi=5, gloss=0.93, bias=0.15)
    for x, y in c.where("crimson"):                                                         # the vine twined along it
        if (x * 3 + y * 2) % 11 == 0:
            c.put(x, y, "leaf_dark", 1 if c.px[(x, y)][1] < 3 else 2, z=c.px[(x, y)][2] + 0.1)
    c.ellipse(12.8, 2.4, 1.4, 1.3, "brass", 3, lo=1, hi=4)                                 # the nozzle
    c.put(13, 1, "soot", 0, z=6).put(14, 2, "soot", 0, z=6)
    c.put(14, 4, "signal_red", 1, z=6)                                                      # the drop running off it
    c.dots([(14, 6), (14, 7)], "signal_red", 3, z=6).put(14, 6, "signal_red", 5, z=6)
    return c


@icon
def designer_lens(c):
    """눈 렌즈: a thick brass-ringed lens, and behind the dark glass an eye looking back: a yellowed,
    bloodshot white, a dark iris and a glowing spider-eye red pupil, a glint across the glass."""
    c.ellipse(8.0, 8.0, 7.0, 7.0, "brass", 1, lo=1, hi=4, gloss=2)
    c.dots([(3, 4), (4, 3)], "brass", 5).dots([(2, 6), (6, 2)], "brass", 4)
    c.ellipse(8.0, 8.0, 5.1, 5.1, "indigo", 2, lo=0, hi=1, dome=-0.5)                       # the dark glass, dished in
    eye = ["................",
           "................",
           "................",
           "................",
           "................",
           "................",
           ".....WWWWWW.....",
           "...WWWWWWWWWW...",
           "...WWWWWWWWWW...",
           ".....WWWWWW.....",
           "................"]
    _grid(c, eye, "W", "bean", 3, 8.0, 7.0, 5.0, lo=2, hi=4, gloss=2)                      # the white, gone yellow
    c.ellipse(8.0, 8.0, 2.0, 2.0, "wine", 4, lo=1, hi=3, gloss=2)                          # the iris
    c.dots([(7, 7), (8, 7), (7, 8), (8, 8)], "signal_red", 4, z=5)                         # the pupil, glowing
    c.dots([(7, 7)], "signal_red", 5, z=5).dots([(8, 8)], "signal_red", 3, z=5)
    c.dots([(4, 7), (5, 8), (11, 7), (11, 8)], "crimson", 3, z=4)                          # bloodshot
    c.dots([(5, 4), (4, 5)], "ice", 5, z=6).dots([(6, 4)], "ice", 3, z=6)                  # the glint on the glass
    return c


@icon
def designer_heart(c):
    """심장 톱니: a copper gear cut in the shape of a heart, square teeth all round its edge, a brass hub, and a dark
    red vein running through it from the cleft to the point, pulsing bright in places."""
    half = ["........",                                                                       # the left half, mirrored
            "...TT...",
            "..BBBBB.",
            ".TBBBBBB",
            ".TBBBBBB",
            "..BBBBBB",
            "..BBBBBB",
            ".TBBBBBB",
            "..TBBBBB",
            "....BBBB",
            "...TTBBB",
            "......BB",
            ".....TBB",
            ".......T",
            "........",
            "........"]
    rows = [h + h[::-1] for h in half]
    _grid(c, rows, "T", "copper", 0, 8.0, 7.0, 8.0, lo=2, hi=3, gloss=2)
    _grid(c, rows, "B", "copper", 1, 8.0, 6.6, 7.5, lo=1, hi=3, dome=0.8, gloss=2)
    c.lit_edge(c.last(), 1)
    c.ellipse(8.0, 7.0, 2.05, 2.05, "brass", 3, lo=1, hi=4, gloss=2)                       # the hub
    c.hole(8.0, 7.0, 0.9, 0.9)
    c.put(6, 6, "brass", 5, z=4)
    # the vein: in at the cleft, round the hub on the right, down to the point, a branch into the left lobe
    vein = [(8, 3), (9, 4), (10, 5), (10, 6), (10, 7), (10, 8), (9, 9), (9, 10), (8, 11), (8, 12),
            (8, 4), (7, 4), (6, 4), (5, 4), (4, 5), (4, 6), (3, 7), (11, 9), (12, 10)]
    c.dots(vein, "wine", 2, z=4)
    c.dots([(9, 4), (10, 6), (5, 4)], "signal_red", 3, z=4).dots([(10, 6)], "signal_red", 5, z=4)
    c.dots([(4, 3), (3, 4)], "copper", 5).dots([(5, 3)], "copper", 4)
    return c


@icon
def usher_note(c):
    """구겨진 쪽지: a small slip of paper crumpled and smoothed out again, ragged at the edges, its folds catching the light
    in bands, a few lines of dark ink broken across them."""
    rows = ["................",                                                                 # paper steps; k = ink
            "................",
            "..554...3322....",
            ".5554433332222..",
            ".5kkkk4kkk2222..",
            ".5554444333kk11.",
            "..55kkk44kkk211.",
            ".33355444332211.",
            ".2233335544321..",
            ".2kkkk3k55kk43..",
            ".1222233355443..",
            "..112kkk3k55443.",
            "..111222333554..",
            "...11...2233....",
            "................",
            "................"]
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "k":
                c.put(x, y, "soot", 1, z=2)
            elif ch != ".":
                c.put(x, y, "paper", int(ch), z=1)
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
        s, cols = 6, 4
        rows = (len(names) + cols - 1) // cols
        cw = 22 * s                                                                         # wide enough for the longest name
        im = Image.new("RGBA", (cols * cw, rows * 18 * s + rows * 14), (54, 54, 62, 255))
        d = ImageDraw.Draw(im)
        for k, n in enumerate(names):
            x, y = (k % cols) * cw + 3 * s, (k // cols) * (18 * s + 14) + s
            ic = ICONS[n]().resize((16 * s, 16 * s), Image.NEAREST)
            im.alpha_composite(ic, (x, y))
            d.text((x + 8 * s - d.textlength(n) / 2, y + 16 * s + 1), n, fill=(220, 220, 220, 255))
        im.save(out / "secret_icons.png")
        for n in names:
            ICONS[n]().resize((128, 128), Image.NEAREST).save(out / f"{n}.png")
    return names


if __name__ == "__main__":
    import sys
    print(main(sys.argv[1] if len(sys.argv) > 1 else None))
