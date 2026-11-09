"""
Economy job goods: 16x16 icons for the job finds (gems, rare fish, pearls) and every processed good of
the six job machines (세공대, 가공대, 손질대, 대장간, 조리대, 증류기).

Drawn with stsv_assets.pixelart: each icon is a few lit shapes on the exact 16x16 grid (light from the
top-left, 6-step ramps from the palette formula, darkest step as the outline, contact shadows), so every
icon is shaded and outlined the same way vanilla items are. Long things lie corner to corner and every
icon uses the slot like a vanilla item does. Details are placed by hand on top.
"""

from stsv_assets.pixelart import Canvas, OUTLINE

# pale materials outline with something darker than their own darkest step
OUTLINE.update({"glass": ("stone", 1), "porcelain": ("stone", 1), "pearl": ("stone", 1), "rice": ("stone", 1),
                "cream": ("wood", 1), "paper": ("wood", 1), "snow": ("stone", 1), "cabbage": ("leaf", 1),
                "corn": ("straw", 0), "petal_yellow": ("straw", 0), "garlic": ("stone", 1), "melon_net": ("musk", 0),
                "blade": ("steel", 1)})

ICONS = {}


CANVAS = {}


def icon(fn):
    """Registers fn (returns a Canvas) under its own name."""
    CANVAS[fn.__name__] = fn
    ICONS[fn.__name__] = lambda: fn().render()
    return fn


def grid(c, rows, legend, z=10, x0=0, y0=0):
    """Hand-placed pixels: each letter is (material, step) on the 6-step icon ramps; '.' is empty."""
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                mat, step = legend[ch]
                c.put(x0 + x, y0 + y, mat, step, z)
    return c


# ================================================================================================
# Gems, metals, jewellery (세공대, 대장간)
# ================================================================================================

def _gem(mat):
    """A brilliant cut seen a little from above: crown facets and a table on top, a pavilion to a point."""
    c = Canvas()
    c.poly([(1, 5.5), (8, 5.5), (8, 15)], mat, 1, normal=(-0.55, 0.45, 0.7))
    c.poly([(8, 5.5), (15, 5.5), (8, 15)], mat, 1, normal=(0.6, 0.5, 0.6))
    c.poly([(4.5, 5.5), (11.5, 5.5), (8, 12)], mat, 2, normal=(0.05, 0.2, 1.0))
    c.poly([(1, 5.5), (4.5, 1.5), (6, 1.5), (5, 5.5)], mat, 3, normal=(-0.7, -0.6, 0.4))
    c.poly([(10, 1.5), (11.5, 1.5), (15, 5.5), (11, 5.5)], mat, 3, normal=(0.75, -0.4, 0.5))
    c.poly([(5, 5.5), (6, 1.5), (10, 1.5), (11, 5.5)], mat, 3, normal=(-0.15, -0.55, 0.8))
    c.poly([(6, 2.5), (10, 2.5), (10, 4.5), (6, 4.5)], mat, 4, normal=(-0.3, -0.4, 1.0))
    c.line(2, 5, 13, 5, mat, 4)                       # the girdle catches the light
    c.dots([(6, 2), (7, 2), (6, 3)], mat, 5)
    c.dots([(5, 7), (6, 8)], mat, 4)
    return c


@icon
def ruby():
    return _gem("ruby")


@icon
def sapphire():
    return _gem("sapphire")


def _rough(mat):
    """A raw lump of mottled rock with a crystal breaking out of it, each facet its own tone."""
    c = Canvas()
    c.ellipse(6.5, 10.5, 5.5, 4.0, "stone", 1, dome=0.9)
    c.ellipse(11, 11, 3.8, 3.2, "stone", 1, dome=0.9)
    c.ellipse(4, 12, 3.2, 2.6, "stone", 2, dome=0.9)
    rock = [p for p, v in c.px.items() if v[0] == "stone"]
    c.tone([(x, y) for x, y in rock if (x * 3 + y * 5) % 7 == 0], 1)
    c.tone([(x, y) for x, y in rock if (x * 5 + y * 3) % 9 == 0], -1)
    for pts, st in ((([5, 9], [7, 2], [8, 2.5], [8, 9]), 3), (([8, 9], [8, 2.5], [9.5, 3.5], [10.5, 9]), 1),
                    (([10.5, 9.5], [12.5, 5], [13.2, 5.6], [12.6, 10]), 2), (([12.6, 10], [13.2, 5.6], [14.2, 7], [13.6, 10]), 1),
                    (([3, 9.5], [3.5, 6], [4.6, 6.5], [4.6, 9.5]), 3), (([4.6, 9.5], [4.6, 6.5], [5.8, 7.4], [5.8, 9.5]), 1)):
        c.poly([tuple(p) for p in pts], mat, 3, step=st)
    c.dots([(7, 3), (7, 4), (12, 6), (4, 7)], mat, 5)
    c.dots([(7, 5), (7, 6), (12, 7), (4, 8)], mat, 4)
    return c


@icon
def rough_ruby():
    return _rough("ruby")


@icon
def rough_sapphire():
    return _rough("sapphire")


@icon
def coke():
    """Lumps of coke: dark grey and porous, with a dull metallic sheen."""
    c = Canvas()
    c.ellipse(5.5, 9.5, 4.5, 4.0, "blacksteel", 1, dome=0.8)
    c.ellipse(10.5, 7.5, 4.2, 3.8, "blacksteel", 2, dome=0.8)
    c.ellipse(9, 12, 4.5, 2.8, "blacksteel", 3, dome=0.8)
    c.ellipse(5, 5, 3, 2.5, "blacksteel", 1, dome=0.8)
    c.dots([(4, 9), (6, 11), (10, 6), (12, 8), (8, 12), (11, 13), (5, 5), (3, 10)], "soot", 0)
    c.dots([(9, 5), (4, 7), (7, 10), (11, 11)], "blacksteel", 5)
    return c


def _sheet(mat):
    """A square metal sheet lying at an angle: a lit top with a brushed streak, a dark edge, rivets."""
    c = Canvas()
    c.poly([(6, 2), (15, 2), (10, 11), (1, 11)], mat, 1, normal=(-0.25, -0.5, 0.9), lo=2, hi=3)
    top = c.last()
    c.tone([(x, y) for x, y in top if 9 <= x + y <= 11 or 15 <= x + y <= 16], 1)       # brushed streaks
    c.tone([(x, y) for x, y in top if x + y >= 19], -1)
    c.lit_edge(top, 2)
    c.poly([(1, 11), (10, 11), (10, 13), (1, 13)], mat, 1, normal=(0, 0.7, 0.5), lo=1, hi=2)
    c.poly([(10, 11), (15, 2), (15, 4), (10, 13)], mat, 1, normal=(0.8, 0.3, 0.4), lo=1, hi=1)
    for x, y in ((7, 3), (12, 3), (3, 10), (8, 10)):
        c.put(x, y, mat, 1).put(x - 1, y, mat, 5)
    return c


@icon
def copper_plate():
    return _sheet("copper")


@icon
def iron_plate():
    return _sheet("steel")


@icon
def gold_leaf():
    """A little book of gold leaf: thin gold sheets between paper."""
    c = Canvas()
    c.poly([(1, 12), (5, 5), (15, 5), (11, 12)], "paper", 1, normal=(0, 0.3, 1))
    c.poly([(1, 12), (11, 12), (11, 14), (1, 14)], "paper", 1, normal=(0, 0.7, 0.5))
    c.poly([(3, 10), (6.5, 4), (15, 4), (11.5, 10)], "gold", 2, normal=(-0.3, -0.5, 0.9))
    c.poly([(3, 10), (11.5, 10), (11.5, 11), (3, 11)], "gold", 2, step=2)
    c.poly([(4, 7.5), (6.5, 2), (14, 2), (11.5, 7.5)], "gold", 3, normal=(-0.4, -0.6, 0.9))
    c.poly([(4, 7.5), (11.5, 7.5), (11.5, 8.5), (4, 8.5)], "gold", 3, step=1)
    c.dots([(7, 3), (8, 3), (6, 4)], "gold", 5)
    return c


@icon
def steel_ingot():
    """The ingot shape everyone knows, in blued steel: dark, with bright bevelled edges."""
    c = Canvas()
    c.poly([(1, 10), (5, 4), (15, 4), (11, 10)], "blacksteel", 1, normal=(-0.2, -0.6, 0.8), lo=2, hi=3)
    top = c.last()
    c.tone([(x, y) for x, y in top if 12 <= x + y <= 13], 1)
    c.lit_edge(top, 2)
    c.poly([(1, 10), (11, 10), (11, 13), (1, 13)], "blacksteel", 1, normal=(-0.1, 0.6, 0.6), lo=1, hi=2)
    front = c.last()
    c.tone([(x, y) for x, y in front if y == 10], 2)
    c.poly([(11, 10), (15, 4), (15, 7), (11, 13)], "blacksteel", 1, normal=(0.8, 0.2, 0.4), lo=1, hi=1)
    c.dots([(6, 5), (7, 5)], "blade", 3)
    return c


@icon
def iron_chain():
    """Hanging links, the way vanilla draws a chain: rings seen face-on with a see-through eye,
    joined by links seen edge-on."""
    c = Canvas()
    m = {"a": ("steel", 5), "b": ("steel", 4), "c": ("steel", 3), "d": ("steel", 2), "e": ("steel", 1)}
    face = [".abc.", "ab.cd", "b...d", "c...d", ".cde."]
    edge = ["bc", "cd", "cd"]
    for y0 in (1, 8):
        grid(c, face, m, z=2, x0=5, y0=y0)
    for y0 in (5, 12):
        grid(c, edge, m, z=3, x0=7, y0=y0)
    return c


@icon
def oak_barrel():
    c = Canvas()
    c.cyl(1.5, 2.5, 14.5, 14.9, "wood", 1, lo=1, hi=4)
    c.ellipse(8, 2.6, 6.4, 1.5, "wood_dark", 2, lo=2, hi=3, dome=0.2)
    for x in (4, 8, 12):
        c.line(x, 4, x, 13, "wood", 1)
    for y in (5, 11):
        c.line(1, y, 14, y, "steel", 3)
        c.dots([(2, y), (3, y)], "steel", 5)
    return c


@icon
def steel_toolset():
    """A wooden case with a steel hammer and a spanner standing in it."""
    c = Canvas()
    c.tube([(4, 9), (4, 2.5)], [0.8, 0.8], "wood_dark", 1, lo=2, hi=3)
    c.rect(1, 1, 7, 4, "blacksteel", 2, normal=(-0.3, -0.5, 0.9))
    c.tube([(11, 9), (11, 3.5)], [0.9, 0.9], "steel", 1, lo=2, hi=4)
    c.ellipse(11, 3, 2.6, 2.4, "steel", 2, lo=2, hi=4)
    c.erase([(10, 1), (11, 1), (10, 2), (11, 2)])
    c.rect(1, 8, 15, 15, "wood", 3, normal=(0, 0.2, 1))
    c.rect(1, 8, 15, 10, "wood", 4, normal=(0, -0.5, 0.9))
    c.rect(6, 11, 10, 12, "brass", 5, normal=(-0.3, -0.5, 0.9))
    c.line(1, 14, 14, 14, "wood", 1)
    return c


@icon
def ornate_armor():
    """A steel chestplate with gold trim at the neck, shoulders and hem, a ruby set in the middle."""
    c = Canvas()
    body = [(1, 2), (5, 1), (6.5, 3.5), (9.5, 3.5), (11, 1), (15, 2), (15, 8), (12.5, 8), (12.5, 14.9), (3.5, 14.9), (3.5, 8),
            (1, 8)]
    c.poly(body, "steel", 1, normal=(-0.1, -0.1, 1), lo=2, hi=3)
    c.tube([(8, 4.5), (8, 12.0)], [3.6, 2.8], "steel", 2, lo=2, hi=4, flat=0.85)
    c.line(1, 2, 4, 1, "gold", 4).line(11, 1, 14, 2, "gold", 3).line(5, 2, 6, 3, "gold", 3).line(10, 2, 9, 3, "gold", 3)
    c.line(6, 3, 9, 3, "gold", 4)
    c.line(4, 14, 11, 14, "gold", 3)
    c.line(8, 10, 8, 12, "steel", 1)
    c.ellipse(8, 7.3, 1.9, 1.9, "ruby", 4)
    c.put(7, 6, "ruby", 5)
    c.dots([(5, 9), (10, 9)], "gold", 4)
    return c


@icon
def ruby_ring():
    """A gold band seen at an angle, a ruby set on top."""
    c = Canvas()
    c.ellipse(8, 10.2, 6.6, 4.6, "gold", 1)
    c.hole(8, 10.6, 4.4, 2.6)
    c.rect(6, 4.5, 10, 6.5, "gold", 3, normal=(0, -0.4, 1))
    c.ellipse(8, 3.6, 3.0, 2.6, "ruby", 4)
    c.put(7, 2, "ruby", 5)
    return c


@icon
def pearl_necklace():
    c = Canvas()
    pts = [(2, 2), (2.6, 4.6), (3.6, 7), (5, 9), (6.6, 10.6)][::-1]
    for i, (x, y) in enumerate(pts + [(16 - x, y) for x, y in pts]):
        c.ellipse(x, y, 1.25, 1.25, "pearl", 1 + i % 2)
    c.rect(7, 11, 9, 12, "gold", 3, normal=(0, -0.3, 1))
    c.poly([(5.5, 12.5), (10.5, 12.5), (8, 15.5)], "sapphire", 2, normal=(0.2, 0.3, 1))
    c.poly([(5.5, 12.5), (8, 12.5), (8, 15.5)], "sapphire", 3, normal=(-0.6, -0.3, 0.7))
    c.put(6, 13, "sapphire", 5)
    return c


@icon
def jeweled_crown():
    c = Canvas()
    for x in (3, 8, 13):
        c.poly([(x - 2.0, 9), (x, 2.5), (x + 2.0, 9)], "gold", 1, normal=(-0.2, -0.3, 1))
        c.put(x, 2, "pearl", 4, z=5)
    c.rect(1, 8, 15, 14.5, "gold", 2, normal=(-0.1, -0.2, 1))
    c.line(1, 9, 14, 9, "gold", 4)
    c.line(1, 13, 14, 13, "gold", 1)
    c.ellipse(8, 11, 1.7, 1.6, "ruby", 3)
    c.ellipse(4, 11, 1.4, 1.3, "sapphire", 3)
    c.ellipse(12, 11, 1.4, 1.3, "sapphire", 3)
    c.dots([(7, 10), (3, 10)], "snow", 2)
    return c


@icon
def pearl():
    """A pearl in its opened oyster shell: a rough grey outside, a rainbow-pale inside."""
    c = Canvas()
    c.ellipse(8, 10.0, 7.0, 5.0, "stone", 1, dome=0.7)
    rough = [p for p in c.where("stone")]
    c.tone([(x, y) for x, y in rough if (x * 3 + y) % 5 == 0], 1)
    c.ellipse(8, 9.6, 5.6, 3.8, "pearl", 2, lo=1, hi=3, dome=-0.6)
    inner = c.last()
    c.tone([(x, y) for x, y in inner if x + y < 15], 1)
    for x, y in ((4, 9), (5, 11), (11, 11), (12, 9)):
        c.put(x, y, "seabream", 5, z=3)
    c.ellipse(8.5, 9.0, 2.7, 2.7, "pearl", 4)
    c.dots([(7, 7), (7, 8)], "snow", 2)
    return c


@icon
def polished_pearl():
    """A large polished pearl: creamy white, a bright glint, a faint rosy sheen along its shaded rim."""
    c = Canvas()
    c.ellipse(8, 8, 6.6, 6.6, "pearl", 1)
    c.dots([(5, 4), (4, 5), (5, 5), (6, 4)], "snow", 2)
    body = set(c.where("pearl"))
    inner = [p for p in body if all((p[0] + dx, p[1] + dy) in body for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    rim = [(x, y) for (x, y) in inner if 4.4 <= ((x + 0.5 - 8) ** 2 + (y + 0.5 - 8) ** 2) ** 0.5 <= 5.6
           and (x - 8) + (y - 8) > 3]
    for p in rim:
        c.put(*p, "seabream", 5, z=5)
    return c


# ================================================================================================
# Fish and the 손질대 (rare catches, fillets, sashimi)
# Fish lie corner to corner like vanilla fish: head bottom-left, tail top-right, back to the top-left.
# ================================================================================================

def _tail(c, mat, z=0, step_lo=1, step_hi=3):
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], mat, z,
           normal=(0.35, 0.1, 1), lo=step_lo, hi=step_hi)


@icon
def red_seabream():
    c = Canvas()
    _tail(c, "seabream")
    c.poly([(3.5, 8.5), (7.5, 3), (10, 4), (5.5, 9.5)], "seabream", 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)   # spiny back fin
    c.tube([(3.6, 12.2), (6.6, 9.2), (10.2, 5.6)], [2.3, 3.7, 2.2], "seabream", 2, flat=0.95)
    c.poly([(6, 12.5), (8.5, 14.5), (9.5, 12.5)], "seabream", 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)           # belly fin
    c.dots([(3, 11)], "roe", 1).put(4, 10, "snow", 2)
    c.dots([(6, 9), (8, 7), (9, 9), (6, 11)], "sapphire", 4)
    c.put(2, 13, "seabream", 1)
    return c


@icon
def bluefin_tuna():
    c = Canvas()
    _tail(c, "fish_blue", step_lo=1, step_hi=2)
    c.tube([(3.2, 12.6), (6.8, 9.2), (11, 5)], [2.0, 3.2, 1.7], "fish_silver", 2, flat=0.9)
    c.tube([(2.6, 11.8), (5.9, 8.4), (10.2, 4.2)], [1.3, 2.3, 1.3], "fish_blue", 3, flat=0.9, lo=1, hi=3)
    c.poly([(6, 6.5), (7, 3.5), (8.5, 5.5)], "fish_blue", 4, normal=(0, -0.6, 0.8), lo=1, hi=2)
    c.dots([(11, 8), (12, 7), (13, 6)], "petal_yellow", 1)
    c.dots([(9, 3), (10, 2)], "petal_yellow", 1)
    c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)
    return c


@icon
def sturgeon():
    """Long and grey with a pointed snout, a row of pale bony plates, the upper tail lobe longer."""
    c = Canvas()
    c.poly([(11, 5), (15, 0.6), (14.2, 4.5), (13, 6.8)], "stone", 0, normal=(0.4, -0.2, 1), lo=1, hi=3)
    c.tube([(1.8, 14.0), (3.9, 12.1), (8, 8), (12.2, 3.8)], [0.5, 1.9, 2.6, 1.1], "stone", 1, flat=0.95, lo=1, hi=4)
    c.dots([(3, 10), (5, 8), (7, 6), (9, 4), (11, 2)], "pearl", 3)
    c.dots([(6, 12), (8, 10), (10, 8)], "pearl", 2)
    c.put(3, 12, "roe", 0)
    return c


def _fillet(mat, lines, skin="fish_silver"):
    """A thick boneless slab lying corner to corner, flake lines across it, the skin a dark edge below."""
    c = Canvas()
    c.tube([(4.0, 11.6), (7.8, 8.0), (12.2, 3.8)], [2.8, 3.3, 2.6], skin, 1, flat=0.6, lo=1, hi=2)
    c.tube([(3.7, 11.1), (7.5, 7.5), (11.8, 3.4)], [2.6, 3.1, 2.4], mat, 2, flat=0.6, lo=2, hi=4)
    for x, y in ((3, 9), (5, 7), (7, 5), (9, 3), (11, 2)):
        c.line(x, y, x + 3, y + 3, *lines, z=3)
    c.dots([(5, 6), (6, 5), (7, 4)], mat, 5)
    return c


@icon
def cod_fillet():
    return _fillet("cream", ("cream", 2))


@icon
def salmon_fillet():
    return _fillet("salmon", ("cream", 4))


@icon
def dried_pollack():
    """A whole dried pollack, stiff and golden-brown, its mouth open."""
    c = Canvas()
    c.poly([(11.5, 4.5), (15, 0.6), (15, 4), (13.5, 4.5), (15, 6.5), (12.5, 6)], "doenjang", 0, normal=(0.3, 0, 1), lo=1, hi=3)
    c.tube([(2.8, 13.2), (5.4, 10.6), (12.2, 3.8)], [1.5, 2.2, 1.2], "doenjang", 1, flat=0.95, lo=1, hi=4)
    for x, y in ((5, 9), (7, 7), (9, 5)):
        c.put(x, y, "straw", 4)
    c.erase([(2, 14)])
    c.put(1, 14, "doenjang", 1).put(1, 12, "doenjang", 2)
    c.put(3, 11, "roe", 0)
    return c


@icon
def prepared_puffer():
    """A puffer fish skinned and cleaned: plump white flesh, pale yellow fins, ready for 복어회."""
    c = Canvas()
    c.poly([(11.5, 5.5), (14.8, 2.2), (14.8, 8.0)], "petal_yellow", 0, normal=(0.3, 0, 1), lo=1, hi=2)
    c.ellipse(7.2, 9.0, 5.8, 5.2, "porcelain", 1, lo=1, hi=4)
    c.poly([(5, 4.4), (8, 1.4), (9.5, 4.2)], "petal_yellow", 0.5, normal=(-0.2, -0.5, 1), lo=1, hi=2)
    c.poly([(6, 12.5), (8, 15), (9.6, 13)], "petal_yellow", 0.5, normal=(0.2, 0.6, 1), lo=1, hi=2)
    c.dots([(3, 8), (3, 9)], "roe", 1).put(4, 7, "snow", 2)
    c.dots([(2, 11), (3, 11)], "seabream", 2)
    c.dots([(7, 9), (9, 11), (10, 8)], "glass", 2)
    return c


@icon
def smoked_salmon():
    """Three thin smoky slices laid over one another, white fat lines, darker smoked edges."""
    c = Canvas()
    for k, (x, y) in enumerate(((5.6, 9.6), (8.5, 6.7), (11.4, 3.8))):
        c.tube([(x - 2.4, y - 0.3), (x + 0.3, y + 2.4)], [2.1, 2.1], "salmon", k + 1, flat=0.55, lo=1, hi=4)
        c.line(int(x - 2), int(y), int(x), int(y + 2), "cream", 4)
        c.put(int(x - 2), int(y - 1), "salmon", 5)
    c.line(12, 13, 14, 11, "leaf", 3).dots([(12, 12), (13, 13), (11, 13)], "leaf", 4)
    return c


@icon
def seabream_sashimi():
    """Thick slices of white flesh, each with a rosy skin edge, laid in a row on a green leaf."""
    c = Canvas()
    c.tube([(3.2, 12.6), (12.8, 3.2)], [2.3, 2.3], "leaf", 0, flat=0.45, lo=2, hi=4)
    c.line(3, 12, 12, 3, "leaf", 1, z=0.5)
    s = {"W": ("porcelain", 2), "w": ("porcelain", 1), "H": ("porcelain", 3), "r": ("seabream", 3), "R": ("seabream", 2)}
    for x, y in ((1, 9), (4, 6), (7, 3), (10, 1)):
        grid(c, [".rr..", "rHWr.", "RWWWw", ".wWww", "..ww."], s, z=5 + x, x0=x, y0=y)
    return c


@icon
def tuna_sashimi():
    """Three deep red blocks of tuna, marbled with paler lines, on a wooden board."""
    c = Canvas()
    c.poly([(1, 11), (5, 7), (15, 7), (11, 11)], "wood", 0, normal=(-0.1, -0.6, 0.8), lo=2, hi=4)
    c.poly([(1, 11), (11, 11), (11, 13), (1, 13)], "wood", 0.5, normal=(0, 0.7, 0.5), lo=1, hi=2)
    c.poly([(11, 11), (15, 7), (15, 9), (11, 13)], "wood", 0.5, normal=(0.7, 0.3, 0.5), lo=1, hi=2)
    t = {"T": ("tuna_meat", 4), "t": ("tuna_meat", 3), "F": ("tuna_meat", 2), "f": ("tuna_meat", 1), "m": ("seabream", 4),
         "h": ("tuna_meat", 5)}
    block = ["..hTTT", ".TmTTt", "TTTTt.", "FFmFf.", "FFFff."]
    for k, (x, y) in enumerate(((1, 5), (5, 4), (9, 3))):
        grid(c, block, t, z=5 + k, x0=x, y0=y)
    return c


@icon
def sukhoe():
    """숙회: thick rounds of boiled octopus, purple skin round white flesh, with a little dish of red 초장."""
    c = _plate(Canvas())
    for k, (x, y) in enumerate(((4.4, 9.0), (8.0, 7.6), (11.4, 9.6))):
        c.ellipse(x, y, 2.7, 2.1, "aubergine", 2 + k * 0.1, lo=1, hi=4, dome=0.6)
        c.ellipse(x - 0.3, y - 0.4, 1.6, 1.2, "porcelain", 2.05 + k * 0.1, lo=2, hi=4)
        c.put(int(x + 1), int(y + 1), "aubergine", 5, z=3 + k)
    c.ellipse(12.8, 3.8, 2.3, 1.5, "porcelain", 4, lo=1, hi=3)
    c.ellipse(12.8, 3.6, 1.5, 0.8, "pepper", 4.2, lo=2, hi=4)
    c.dots([(3, 5), (4, 4), (5, 4)], "leaf", 3)
    return c


@icon
def fishcake():
    """꼬치 어묵: a fish-cake ribbon folded back and forth on a wooden skewer."""
    c = Canvas()
    f = {"A": ("straw", 5), "B": ("straw", 4), "C": ("straw", 2), "D": ("doenjang", 3), "E": ("doenjang", 2),
         "s": ("wood", 3), "S": ("wood", 2)}
    grid(c, [
        ".....s.....",
        "..ABBBBBC..",
        ".ABBBBBBBC.",
        "..DEEEEED..",
        "...ABBBBC..",
        "..ABBBBBBC.",
        "...DEEEED..",
        "..ABBBBBC..",
        ".ABBBBBBBC.",
        "..DEEEEED..",
        ".....S.....",
        ".....S.....",
        ".....S.....",
    ], f, x0=2, y0=1)
    return c


@icon
def caviar():
    """An open round tin brimming with glossy black roe, a little mother-of-pearl spoon on top."""
    c = Canvas()
    c.cyl(1.2, 7.5, 14.8, 12.6, "gold", 1, lo=1, hi=3)
    c.ellipse(8, 12.6, 6.8, 2.2, "gold", 0.8, lo=1, hi=2)
    c.ellipse(8, 7.5, 6.8, 4.0, "gold", 2, lo=3, hi=4, dome=0.1)
    c.ellipse(8, 7.6, 5.7, 3.1, "roe", 3, lo=1, hi=2, dome=0.7)
    c.dots([(4, 7), (6, 6), (8, 8), (10, 6), (6, 9), (9, 9), (11, 8)], "sapphire", 4)
    c.dots([(5, 6), (7, 5)], "snow", 1)
    c.tube([(9.6, 4.6), (14.0, 1.6)], [0.55, 0.55], "pearl", 6, lo=3, hi=4)
    c.ellipse(9.0, 5.2, 1.4, 1.0, "pearl", 6, lo=3, hi=5)
    return c


# ================================================================================================
# 가공대 (rice, flour, tofu, powders, juice, sauce, pastes)
# ================================================================================================

@icon
def rice():
    """An open cloth sack, a red stamp on it, white rice heaped in the top."""
    c = Canvas()
    c.ellipse(8, 10.2, 6.8, 5.2, "cream", 1, lo=1, hi=4)
    c.tube([(2.4, 6.2), (13.6, 6.2)], [1.2, 1.2], "cream", 2, lo=2, hi=5)
    c.ellipse(8, 5.0, 5.6, 3.2, "rice", 3, lo=2, hi=4, dome=0.8)
    c.dots([(5, 3), (8, 2), (10, 4), (6, 5), (11, 3), (9, 5), (4, 5)], "rice", 5)
    c.ellipse(8.5, 10.5, 2.3, 2.3, "fabric_red", 4, lo=3, hi=4)
    c.hole(8.5, 10.5, 1.0, 1.0)
    c.put(8, 10, "cream", 3, z=6)
    c.dots([(2, 13), (13, 12), (5, 14), (10, 14)], "cream", 1)
    return c


def _bag(print_, edge):
    """A paper bag with its top folded over, a crop printed on the front."""
    c = Canvas()
    c.poly([(2, 4), (11, 4), (11, 15), (2, 15)], "paper", 1, normal=(-0.15, -0.1, 1), lo=2, hi=4)
    c.poly([(11, 4), (14, 2.5), (14, 13.5), (11, 15)], "paper", 1, normal=(0.85, -0.1, 0.4), lo=1, hi=2)
    c.poly([(2, 4), (5, 1), (14, 1), (11, 4)], edge[0], 2, normal=(-0.2, -0.8, 0.6), lo=2, hi=4)
    c.line(2, 4, 10, 4, edge[0], edge[1], z=3)
    print_(c)
    return c


def _wheat_print(c):
    c.line(6, 6, 6, 13, "straw", 2, z=5)
    for y in (7, 9, 11):
        c.put(5, y, "straw", 3, z=5).put(7, y, "straw", 3, z=5).put(6, y - 1, "straw", 4, z=5)


def _corn_print(c):
    c.ellipse(6.5, 9, 2.0, 3.6, "corn", 5, lo=2, hi=4)
    c.dots([(6, 7), (7, 8), (6, 9), (7, 10)], "corn", 1, z=6)
    c.poly([(3.5, 13.5), (5.5, 10), (6.5, 13.5)], "sprout", 6, normal=(-0.4, -0.2, 1), lo=2, hi=4)
    c.poly([(6.5, 13.5), (7.5, 10), (9.5, 13.5)], "sprout", 6, normal=(0.4, -0.2, 1), lo=2, hi=3)


@icon
def flour():
    return _bag(_wheat_print, ("paper", 1))


@icon
def corn_flour():
    return _bag(_corn_print, ("corn", 2))


@icon
def chili_powder():
    """A heap of red pepper powder in a small white dish."""
    c = Canvas()
    c.ellipse(8, 7.6, 5.6, 4.6, "pepper", 2, lo=1, hi=4, dome=0.9)
    c.poly([(1, 10.5), (15, 10.5), (12.5, 14.5), (3.5, 14.5)], "porcelain", 3, normal=(0, 0.5, 0.85), lo=2, hi=4)
    c.tube([(1.6, 10.6), (14.4, 10.6)], [0.9, 0.9], "porcelain", 4, lo=2, hi=5)
    c.dots([(6, 5), (9, 4), (8, 7), (5, 8), (11, 7)], "pepper", 5)
    c.dots([(7, 6), (10, 8), (4, 9)], "pepper", 1)
    return c


@icon
def tofu():
    """A soft white block, faintly marked by its cloth, wet and bright on top."""
    c = Canvas()
    c.poly([(1, 7), (5, 3), (15, 3), (11, 7)], "cream", 1, normal=(-0.2, -0.7, 0.8), lo=4, hi=5)
    c.poly([(1, 7), (11, 7), (11, 14), (1, 14)], "cream", 1, normal=(-0.1, 0.1, 1), lo=3, hi=4)
    c.poly([(11, 7), (15, 3), (15, 10), (11, 14)], "cream", 1, normal=(0.8, 0.1, 0.5), lo=2, hi=3)
    c.dots([(3, 9), (6, 9), (9, 9), (4, 12), (7, 12)], "cream", 2)
    c.dots([(6, 4), (7, 4), (8, 5)], "snow", 2)
    return c


def _flask(liquid, glass="glass", cork="wood"):
    """A round glass flask with a cork, the liquid filling its belly."""
    c = Canvas()
    c.ellipse(8, 10.1, 5.6, 4.7, glass, 1, lo=1, hi=4)
    c.ellipse(8, 10.8, 4.5, 3.7, liquid, 2, lo=1, hi=4)
    c.cyl(6.4, 3.4, 9.6, 6.5, glass, 1, lo=2, hi=4)
    c.rect(6.5, 1.2, 9.5, 3.4, cork, 3, normal=(-0.3, -0.5, 1))
    c.dots([(5, 8), (5, 9), (6, 7)], glass, 5)
    return c


@icon
def grape_juice():
    return _flask("grape")


@icon
def dried_zucchini():
    """호박고지: rings of dried zucchini threaded on a cord."""
    c = Canvas()
    c.line(1, 14, 14, 1, "wood_dark", 3, z=0)
    for x, y in ((3.6, 12.4), (8.0, 8.0), (12.4, 3.6)):
        c.ellipse(x, y, 2.6, 2.6, "zucchini", 2, lo=1, hi=4)
        c.ellipse(x, y, 1.6, 1.6, "straw", 3, lo=3, hi=4, dome=-0.5)
        c.put(int(x), int(y), "wood_dark", 2, z=9)
    return c


@icon
def tomato_sauce():
    """A glass jar of tomato sauce with a metal lid."""
    c = Canvas()
    c.cyl(2, 4.5, 14, 14.9, "glass", 1, lo=2, hi=4, rounded=1.5)
    c.cyl(3, 6.0, 13, 14.0, "tomato", 2, lo=1, hi=4, rounded=1.2)
    c.cyl(2.5, 1.2, 13.5, 4.5, "steel", 3, lo=2, hi=4)
    c.line(3, 3, 12, 3, "steel", 2, z=4)
    c.dots([(3, 7), (3, 8), (3, 9)], "glass", 5)
    c.dots([(6, 9), (9, 11), (11, 8)], "tomato", 5)
    return c


def _crock(paste, top=None):
    """An onggi crock: wide glazed shoulders, a narrower foot, the paste heaped in its open mouth."""
    c = Canvas()
    c.ellipse(8, 9.6, 6.8, 5.4, "onggi", 1, lo=1, hi=4)
    c.rect(4, 3.5, 12, 5.5, "onggi", 2, normal=(-0.2, -0.4, 1), lo=2, hi=4)
    c.ellipse(8, 3.6, 4.6, 1.6, "onggi", 3, lo=1, hi=1, dome=0.0)
    c.ellipse(8, 3.0, 3.8, 1.7, paste, 4, lo=2, hi=4, dome=0.8)
    c.dots([(3, 7), (3, 8), (4, 6)], "terracotta", 4)
    c.line(2, 10, 13, 10, "onggi", 2, z=3)
    if top:
        top(c)
    return c


@icon
def doenjang():
    return _crock("doenjang")


@icon
def gochujang():
    return _crock("pepper")


@icon
def jang_set():
    """전통 장 세트: three small crocks (된장, 고추장, 간장) packed in a wooden gift crate."""
    c = Canvas()
    for x, paste in ((4.0, "doenjang"), (8.0, "pepper"), (12.0, "roe")):
        c.ellipse(x, 6.8, 2.1, 3.0, "onggi", 1, lo=1, hi=4)
        c.ellipse(x, 4.1, 1.3, 0.9, paste, 2, lo=2, hi=4, dome=0.8)
        c.put(int(x - 1.1), 5, "terracotta", 4, z=3)
    c.rect(1, 8.5, 15, 15, "wood", 4, normal=(-0.1, -0.1, 1), lo=2, hi=4)
    c.line(1, 9, 14, 9, "wood", 4, z=5).line(1, 14, 14, 14, "wood", 1, z=5)
    c.rect(5, 10.5, 11, 13, "paper", 6, normal=(0, 0, 1), lo=3, hi=3)
    c.ellipse(8, 11.75, 1.0, 1.0, "fabric_red", 7, lo=3, hi=3)
    return c

# ================================================================================================
# 조리대 (dishes). Plates and bowls are seen a little from above, like vanilla stews.
# ================================================================================================

def _plate(c, z=0):
    """A white plate seen at an angle, its rim catching the light."""
    c.ellipse(8, 11.4, 6.9, 3.3, "porcelain", z, lo=1, hi=3, dome=0.3)
    c.ellipse(8, 11.0, 5.4, 2.3, "porcelain", z + 0.1, lo=2, hi=2, dome=-0.3)
    return c


def _bowl(c, mat="porcelain", z=0, top=7.5):
    """A deep bowl: the body below its rim; whatever is inside is drawn on the rim's ellipse."""
    c.poly([(1.2, top), (14.8, top), (12.6, 13.6), (3.4, 13.6)], mat, z, normal=(-0.1, 0.55, 0.85), lo=1, hi=4)
    c.ellipse(8, 13.6, 4.6, 1.2, mat, z - 0.1, lo=1, hi=2)
    c.tube([(1.6, top + 0.4), (14.4, top + 0.4)], [0.75, 0.75], mat, z + 0.2, lo=2, hi=5)
    return c


@icon
def kimchi():
    """Wedges of napa cabbage, pale ribs and green leaf tips under a red coat, heaped on a plate."""
    c = _plate(Canvas())
    c.ellipse(8, 7.8, 6.0, 4.6, "pepper", 2, lo=1, hi=4, dome=0.9)
    for (x0, y0), (x1, y1) in (((4, 10), (6, 5)), ((7, 10), (8, 4)), ((10, 10), (10, 5)), ((12, 9), (13, 6))):
        c.line(x0, y0, x1, y1, "cabbage", 4, z=3)
    c.dots([(6, 4), (8, 3), (10, 4), (5, 5), (12, 5)], "leaf", 3)
    c.dots([(5, 8), (9, 7), (11, 9)], "pepper", 5)
    return c


@icon
def kkakdugi():
    """Chunky cubes of radish, red dressing on top, the white cut sides showing, on a plate."""
    c = _plate(Canvas())
    cube = {"T": ("pepper", 4), "t": ("pepper", 3), "h": ("pepper", 5), "W": ("cream", 4), "w": ("cream", 3),
            "S": ("cream", 2), "r": ("pepper", 2)}
    block = ["hTTTt", "TTTtt", "WWWSr", "WwWSS", "wwwSS"]
    for k, (x, y) in enumerate(((1, 6), (9, 6), (5, 3))):
        grid(c, block, cube, z=3 + k, x0=x, y0=y)
    return c


@icon
def oi_sobagi():
    """Cucumbers split in a cross and stuffed with red filling, standing on a plate."""
    c = _plate(Canvas())
    for k, x in enumerate((4.0, 8.0, 12.0)):
        c.cyl(x - 1.9, 3.5 + (k % 2), x + 1.9, 11.0, "cucumber", 2 + k * 0.1, lo=1, hi=4)
        c.ellipse(x, 3.6 + (k % 2), 1.9, 1.0, "pepper", 3 + k * 0.1, lo=2, hi=4)
        c.put(int(x), int(4.6 + (k % 2)), "pepper", 2, z=4).put(int(x), int(6.6 + (k % 2)), "pepper", 2, z=4)
        c.put(int(x) - 1, int(8 + (k % 2)), "cucumber", 5, z=4)
    return c


@icon
def hobakjeon():
    """Golden egg-battered rounds of zucchini, the green ring showing through, on a plate."""
    c = _plate(Canvas())
    for k, (x, y) in enumerate(((4.6, 8.4), (11.2, 8.2), (7.8, 5.6))):
        c.ellipse(x, y, 3.6, 2.5, "corn", 2 + k, lo=2, hi=4, dome=0.5)
        c.ellipse(x, y, 2.1, 1.3, "zucchini", 2.5 + k, lo=2, hi=3, dome=0.2)
        c.ellipse(x, y, 1.0, 0.6, "cabbage", 2.6 + k, lo=3, hi=3)
    return c


@icon
def eggplant_stirfry():
    """Half-moons of eggplant, pale flesh and glossy purple skin, in a brown sauce on a plate."""
    c = _plate(Canvas())
    c.ellipse(8, 9.0, 5.4, 2.4, "doenjang", 1, lo=2, hi=3, dome=0.5)
    half = {"P": ("aubergine", 3), "p": ("aubergine", 2), "h": ("aubergine", 5), "F": ("cabbage", 4), "f": ("cabbage", 3),
            "s": ("cream", 4)}
    piece = [".PPP.", "hFFFP", "FFsFp", "fFFfp"]
    for k, (x, y) in enumerate(((2, 6), (9, 6), (5, 3), (6, 8))):
        grid(c, piece, half, z=3 + k, x0=x, y0=y)
    c.put(12, 4, "leaf", 4).put(13, 4, "leaf", 3)
    return c


@icon
def sundubu_jjigae():
    """A black clay pot of bubbling red stew: soft tofu, an egg, scallions, and a wisp of steam."""
    c = Canvas()
    c.poly([(1.2, 8.0), (14.8, 8.0), (13.2, 14.0), (2.8, 14.0)], "soot", 0, normal=(-0.1, 0.5, 0.85), lo=1, hi=3)
    c.tube([(1.6, 8.3), (14.4, 8.3)], [0.8, 0.8], "soot", 0.2, lo=2, hi=3)
    c.ellipse(8, 8.0, 6.2, 2.4, "pepper", 1, lo=2, hi=4, dome=0.3)
    c.ellipse(5.2, 7.7, 1.8, 1.1, "cream", 2, lo=3, hi=4)
    c.ellipse(10.3, 7.8, 1.5, 1.0, "snow", 2, lo=2, hi=2)
    c.ellipse(10.3, 7.8, 0.75, 0.6, "petal_yellow", 3, lo=1, hi=1)
    c.dots([(8, 7), (7, 9), (12, 8)], "leaf", 3)
    c.dots([(6, 4), (7, 3), (7, 2), (10, 4), (11, 3)], "snow", 1)
    return c


@icon
def tuna_sushi():
    """Two pieces of tuna nigiri side by side on a little wooden board."""
    c = Canvas()
    c.poly([(1, 12), (3.5, 9.5), (15, 9.5), (12.5, 12)], "wood", 0, normal=(-0.1, -0.6, 0.8), lo=2, hi=4)
    c.poly([(1, 12), (12.5, 12), (12.5, 14), (1, 14)], "wood", 0.2, normal=(0, 0.7, 0.5), lo=1, hi=2)
    c.poly([(12.5, 12), (15, 9.5), (15, 11.5), (12.5, 14)], "wood", 0.2, normal=(0.7, 0.3, 0.5), lo=1, hi=2)
    for k, (x, y) in enumerate(((4.6, 8.6), (10.8, 7.6))):
        c.ellipse(x, y + 0.8, 3.0, 1.9, "rice", 1 + k, lo=2, hi=4, dome=0.6)
        c.tube([(x - 2.6, y - 0.6), (x + 2.6, y - 1.2)], [1.2, 1.2], "tuna_meat", 1.5 + k, flat=0.6, lo=2, hi=4)
        c.put(int(x - 1), int(y - 2), "tuna_meat", 5, z=5)
        c.line(int(x - 1), int(y - 1), int(x + 1), int(y - 1), "seabream", 4, z=5)
    return c


@icon
def strawberry_cake():
    """A slice of layered cake: sponge and cream, a strawberry on top, strawberry slices inside."""
    c = Canvas()
    c.poly([(1.5, 7), (12, 3.5), (14.5, 6.5), (4.5, 10)], "cream", 1, normal=(-0.2, -0.8, 0.6), lo=3, hi=5)
    c.poly([(1.5, 7), (4.5, 10), (4.5, 14.5), (1.5, 11.5)], "cream", 1, normal=(-0.8, 0.1, 0.5), lo=2, hi=3)
    c.poly([(4.5, 10), (14.5, 6.5), (14.5, 11), (4.5, 14.5)], "corn", 1, normal=(0.2, 0.2, 1), lo=2, hi=4)
    c.line(4, 12, 14, 8, "cream", 4, z=3)
    c.dots([(7, 12), (10, 11), (13, 10)], "toadstool", 3)
    c.ellipse(9.4, 3.6, 2.2, 2.1, "toadstool", 4, lo=2, hi=4)
    c.dots([(8, 1), (9, 1), (10, 1)], "leaf", 3)
    c.put(8, 3, "toadstool", 5)
    return c


@icon
def tomato_pasta():
    """A nest of golden noodles under tomato sauce and a basil leaf, on a white plate."""
    c = _plate(Canvas())
    c.ellipse(8, 8.6, 5.6, 3.4, "corn", 2, lo=2, hi=4, dome=0.7)
    for (x0, y0), (x1, y1) in (((3, 9), (7, 6)), ((5, 11), (12, 7)), ((9, 11), (13, 9)), ((4, 7), (8, 5))):
        c.line(x0, y0, x1, y1, "corn", 5, z=3)
    c.ellipse(8.2, 7.6, 3.0, 1.9, "tomato", 4, lo=2, hi=4, dome=0.6)
    c.dots([(9, 6), (10, 6)], "leaf", 3).put(9, 5, "leaf", 4)
    return c


@icon
def corn_bread():
    """옥수수빵: a golden domed corn muffin in a paper cup, kernels baked into the top."""
    c = Canvas()
    c.poly([(2.5, 9), (13.5, 9), (11.8, 15), (4.2, 15)], "paper", 1, normal=(-0.1, 0.4, 0.9), lo=2, hi=4)
    for x in (4, 6, 8, 10, 12):
        c.line(x, 10, x, 14, "paper", 1, z=2)
    c.ellipse(8, 7.6, 6.8, 5.6, "corn", 3, lo=1, hi=4, dome=0.9)
    c.erase([(x, y) for x in range(16) for y in range(10, 16) if (x, y) in c.px and c.px[(x, y)][0] == "corn" and y > 9])
    for x, y in ((5, 4), (8, 3), (11, 5), (4, 7), (7, 6), (10, 7), (12, 8), (6, 8)):
        c.put(x, y, "topaz", 3, z=5)
    c.dots([(6, 3), (5, 4)], "corn", 5)
    return c


@icon
def bibimbap():
    """비빔밥 seen from above in a hot stone bowl: coloured toppings around a fried egg."""
    c = Canvas()
    c.ellipse(8, 8, 7.0, 7.0, "soot", 0, lo=1, hi=3)
    c.ellipse(8, 8, 5.7, 5.7, "rice", 1, lo=2, hi=2, dome=0.0)
    sec = {"g": ("leaf", 3), "G": ("leaf", 2), "o": ("onion", 4), "O": ("onion", 3), "b": ("doenjang", 3),
           "c": ("cucumber", 3), "r": ("tuna_meat", 3), "R": ("pepper", 3), "s": ("cream", 4)}
    grid(c, [
        "..ggGoo..",
        ".gggGooO.",
        "bbgg.ooOO",
        "bbb...ccc",
        "bbb...ccc",
        "ssb...ccc",
        "ssRRrrrc.",
        ".sRRRrr..",
        "..RRRr...",
    ], sec, z=3, x0=4, y0=4)
    c.ellipse(8, 8, 1.9, 1.7, "snow", 5, lo=2, hi=2)
    c.ellipse(8, 8, 1.0, 0.9, "petal_yellow", 6, lo=1, hi=1)
    return c


@icon
def grilled_fish_set():
    """생선구이 정식: a grilled mackerel on a long white plate, a bowl of rice and kimchi beside it."""
    c = Canvas()
    c.poly([(1.2, 12.0), (10.6, 2.6), (14.8, 6.6), (5.4, 15.0)], "porcelain", 0, normal=(-0.2, -0.3, 1), lo=2, hi=4)
    c.tube([(3.6, 11.8), (6.8, 8.6), (11.0, 4.6)], [1.6, 2.2, 1.3], "fish_silver", 2, flat=0.9, lo=1, hi=4)
    c.tube([(3.0, 11.0), (6.2, 7.8), (10.4, 3.9)], [0.9, 1.3, 0.8], "fish_blue", 2.5, flat=0.9, lo=1, hi=3)
    c.poly([(10.6, 4.8), (13.8, 2.0), (13.6, 6.8)], "fish_blue", 2.2, normal=(0.4, 0, 1), lo=1, hi=2)
    for x, y in ((5, 9), (7, 7), (9, 5)):
        c.line(x, y, x + 2, y + 2, "soot", 2, z=6)
    c.put(3, 11, "roe", 0, z=7).put(4, 10, "snow", 2, z=7)
    c.ellipse(3.2, 3.6, 2.2, 1.6, "rice", 3, lo=2, hi=4, dome=0.8)
    c.poly([(1.0, 3.8), (5.4, 3.8), (4.6, 6.2), (1.8, 6.2)], "porcelain", 3.5, normal=(0, 0.5, 0.85), lo=2, hi=4)
    c.ellipse(13.0, 12.4, 1.9, 1.3, "pepper", 3, lo=2, hi=4, dome=0.7)
    c.poly([(11.2, 12.6), (14.8, 12.6), (14.2, 14.6), (11.8, 14.6)], "porcelain", 3.5, normal=(0, 0.5, 0.85), lo=2, hi=4)
    return c


@icon
def braised_mackerel():
    """고등어조림: mackerel pieces and radish chunks braised in red sauce, in a black pan with a handle."""
    c = Canvas()
    c.line(13, 7, 15, 5, "wood", 1, z=0).line(13, 8, 15, 6, "wood", 2, z=0)
    c.ellipse(7.6, 9.8, 7.0, 4.8, "soot", 0, lo=1, hi=3)
    c.ellipse(7.6, 9.4, 5.8, 3.7, "pepper", 1, lo=1, hi=3, dome=0.4)
    cube = {"W": ("cream", 4), "w": ("cream", 3), "S": ("cream", 2), "r": ("pepper", 4)}
    for k, (x, y) in enumerate(((2, 8), (9, 9))):
        grid(c, ["rWW", "WwS", "wSS"], cube, z=3 + k, x0=x, y0=y)
    for k, (a, b) in enumerate((((4.6, 8.8), (8.0, 6.4)), ((8.6, 9.6), (11.6, 7.4)))):
        c.tube([a, b], [1.5, 1.5], "fish_silver", 5 + k, flat=0.8, lo=1, hi=4)
        c.tube([(a[0] - 0.4, a[1] - 0.6), (b[0] - 0.4, b[1] - 0.6)], [0.8, 0.8], "fish_blue", 5.5 + k, flat=0.8, lo=1, hi=3)
    c.dots([(9, 6), (6, 11)], "leaf", 3)
    c.dots([(5, 3), (6, 2), (9, 3), (10, 2)], "snow", 1)
    return c


@icon
def maeuntang():
    """매운탕: a two-handled pot of red spicy broth with a piece of fish, radish, scallions and chilli, steaming."""
    c = Canvas()
    c.poly([(1.8, 8.0), (14.2, 8.0), (13.0, 14.2), (3.0, 14.2)], "steel", 0, normal=(-0.1, 0.5, 0.85), lo=1, hi=4)
    c.line(0, 9, 1, 9, "steel", 2, z=0.1).line(14, 9, 15, 9, "steel", 1, z=0.1)
    c.tube([(2.0, 8.3), (14.0, 8.3)], [0.8, 0.8], "steel", 0.2, lo=2, hi=5)
    c.ellipse(8, 8.0, 5.9, 2.3, "pepper", 1, lo=2, hi=4, dome=0.3)
    c.ellipse(6.0, 7.6, 2.0, 1.1, "fish_silver", 2, lo=2, hi=4)
    c.put(5, 7, "fish_blue", 2, z=3).put(6, 7, "fish_blue", 3, z=3)
    grid(c, ["WW", "wS"], {"W": ("cream", 4), "w": ("cream", 3), "S": ("cream", 2)}, z=3, x0=10, y0=7)
    c.dots([(8, 8), (9, 7), (12, 8)], "leaf", 3)
    c.put(8, 9, "pepper", 5, z=3).put(4, 8, "pepper", 5, z=3)
    c.dots([(5, 4), (6, 3), (6, 2), (10, 4), (11, 3), (11, 2)], "snow", 1)
    return c


@icon
def steamed_crab():
    """게찜: a whole steamed crab, bright orange-red, on a plate with scallions."""
    c = _plate(Canvas())
    for (x0, y0), (x1, y1) in (((5, 10), (2, 12)), ((5, 11), (3, 13)), ((11, 10), (14, 12)), ((11, 11), (13, 13))):
        c.line(x0, y0, x1, y1, "seabream", 2, z=1)
    for x in (3.4, 12.6):
        c.line(int(x + (1 if x < 8 else -1)), 8, int(x), 6, "seabream", 3, z=1.5)
        c.ellipse(x, 5.0, 1.6, 1.3, "seabream", 2, lo=1, hi=4, dome=0.7)
    c.ellipse(8, 9.0, 4.6, 3.1, "seabream", 3, lo=1, hi=4, dome=0.9)
    c.dots([(6, 7), (7, 7)], "seabream", 5)
    c.dots([(7, 6), (9, 6)], "soot", 4)
    c.dots([(2, 9), (14, 9), (13, 14)], "leaf", 3)
    return c


@icon
def grilled_eel():
    """장어구이: three glazed pieces of eel, grill marks across them, on a wooden board."""
    c = Canvas()
    c.poly([(1, 11), (5, 7), (15, 7), (11, 11)], "wood", 0, normal=(-0.1, -0.6, 0.8), lo=2, hi=4)
    c.poly([(1, 11), (11, 11), (11, 13), (1, 13)], "wood", 0.5, normal=(0, 0.7, 0.5), lo=1, hi=2)
    c.poly([(11, 11), (15, 7), (15, 9), (11, 13)], "wood", 0.5, normal=(0.7, 0.3, 0.5), lo=1, hi=2)
    e = {"D": ("doenjang", 3), "d": ("doenjang", 2), "h": ("doenjang", 5), "g": ("pepper", 4), "s": ("soot", 1), "u": ("doenjang", 1)}
    piece = [".hgDd", "gDsDd", "DsDdd", "uuuu."]
    for k, (x, y) in enumerate(((1, 6), (6, 5), (11, 4))):
        grid(c, piece, e, z=5 + k, x0=x, y0=y)
    c.dots([(2, 10), (8, 10), (13, 8)], "leaf", 3)
    return c


@icon
def hanjeongsik():
    """한정식 seen from above: a low table crowded with rice, soup, fish and small side dishes."""
    c = Canvas()
    c.rect(1, 1, 15, 15, "wood", 0, normal=(-0.2, -0.2, 1), lo=2, hi=4)
    c.line(1, 14, 14, 14, "wood", 1, z=0.5).line(14, 1, 14, 14, "wood", 1, z=0.5)
    dishes = [((4, 4), 2.1, "rice"), ((8.2, 4), 2.1, "pepper"), ((12, 4), 1.8, "leaf"),
              ((4.2, 11.6), 2.2, "doenjang"), ((8.8, 11.8), 2.0, "cabbage"), ((12.4, 11.8), 1.7, "onion")]
    for (x, y), r, food in dishes:
        c.ellipse(x, y, r + 0.8, r + 0.8, "porcelain", 1, lo=2, hi=4)
        c.ellipse(x, y, r, r, food, 2, lo=2, hi=4)
    c.tube([(3, 8), (13, 8)], [1.6, 1.6], "porcelain", 1, lo=2, hi=4)
    c.tube([(4, 8), (11.5, 8)], [1.0, 1.0], "doenjang", 2, lo=2, hi=4)
    c.put(4, 8, "roe", 0, z=5).put(12, 8, "doenjang", 1, z=5)
    return c


# ================================================================================================
# 증류기 (drinks)
# ================================================================================================

def _long_bottle(c, glass, x0=4.2, x1=11.8, shoulder=6.8, neck=(6.5, 9.5), top=1.6):
    """A bottle seen from the side: a round body, a sloping shoulder, a long neck."""
    c.cyl(x0, shoulder, x1, 14.9, glass, 1, lo=1, hi=4, rounded=1.2)
    c.ellipse((x0 + x1) / 2, shoulder, (x1 - x0) / 2, 1.9, glass, 1.1, lo=2, hi=4)
    c.cyl(neck[0], top, neck[1], shoulder, glass, 1.2, lo=2, hi=4)
    return c


@icon
def soju():
    """소주: the small green bottle with a white label."""
    c = _long_bottle(Canvas(), "bottle_green")
    c.cyl(6.3, 0.8, 9.7, 2.6, "bottle_green", 2, lo=1, hi=2)
    c.cyl(4.2, 9.2, 11.8, 12.6, "paper", 3, lo=2, hi=4)
    c.line(6, 10, 9, 10, "sapphire", 2, z=4).line(6, 11, 8, 11, "sapphire", 3, z=4)
    c.dots([(5, 7), (5, 8), (7, 3)], "bottle_green", 5)
    return c


@icon
def makgeolli():
    """막걸리: a white bottle of milky rice wine, a green cap and a green label."""
    c = _long_bottle(Canvas(), "porcelain")
    c.cyl(6.3, 0.8, 9.7, 2.6, "bottle_green", 2, lo=1, hi=3)
    c.cyl(4.2, 9.4, 11.8, 12.4, "bottle_green", 3, lo=2, hi=4)
    c.line(6, 10, 9, 10, "paper", 3, z=4)
    c.dots([(5, 7), (5, 8)], "snow", 2)
    return c


@icon
def wine():
    """포도주: a tall dark bottle with a red foil top and a cream label."""
    c = _long_bottle(Canvas(), "wine", shoulder=7.2, top=1.8)
    c.cyl(6.3, 0.8, 9.7, 3.4, "fabric_red", 2, lo=2, hi=4)
    c.cyl(4.2, 9.6, 11.8, 13.0, "cream", 3, lo=2, hi=4)
    c.line(6, 11, 9, 11, "wine", 2, z=4)
    c.dots([(5, 8), (5, 9), (7, 4)], "wine", 5)
    return c


@icon
def vintage_wine():
    """빈티지 포도주: an old bottle under a red wax seal, a yellowed label, dust and a cobweb."""
    c = _long_bottle(Canvas(), "wine", shoulder=7.2, top=1.8)
    c.cyl(6.0, 0.8, 10.0, 3.6, "crimson", 2, lo=2, hi=4)
    c.put(10, 3, "crimson", 2, z=3).put(5, 3, "crimson", 3, z=3)
    c.cyl(4.2, 9.2, 11.8, 13.2, "straw", 3, lo=2, hi=4)
    c.line(6, 10, 9, 10, "doenjang", 1, z=4).line(6, 12, 9, 12, "doenjang", 1, z=4)
    c.put(7, 11, "crimson", 2, z=4)
    c.dots([(5, 8), (10, 14), (6, 14), (11, 7)], "stone", 3)
    c.dots([(11, 9), (11, 8), (10, 8)], "snow", 0)
    return c


@icon
def melon_syrup():
    """멜론 시럽: a jar of bright green syrup, a checked cloth tied over the lid."""
    c = Canvas()
    c.cyl(2.2, 5.0, 13.8, 14.9, "glass", 1, lo=2, hi=4, rounded=1.5)
    c.cyl(3.2, 6.6, 12.8, 14.0, "sprout", 2, lo=2, hi=5, rounded=1.2)
    c.ellipse(8, 4.0, 6.6, 2.2, "fabric_red", 3, lo=2, hi=4, dome=0.6)
    c.dots([(3, 3), (5, 4), (7, 3), (9, 4), (11, 3), (13, 4), (6, 2), (10, 2)], "snow", 1)
    c.line(2, 5, 13, 5, "wood", 2, z=4)
    c.dots([(3, 7), (3, 8), (3, 9)], "glass", 5)
    c.ellipse(9.5, 10.5, 2.0, 1.6, "melon_net", 4, lo=1, hi=2)
    return c


@icon
def melon_liqueur():
    """멜론 리큐어: a round decanter of bright green liqueur with a gold stopper."""
    c = Canvas()
    c.ellipse(8, 10.2, 6.0, 4.8, "glass", 1, lo=1, hi=4)
    c.ellipse(8, 10.9, 4.9, 3.8, "sprout", 2, lo=2, hi=5)
    c.cyl(6.5, 3.4, 9.5, 6.4, "glass", 1, lo=2, hi=4)
    c.ellipse(8, 2.4, 1.8, 1.6, "gold", 3, lo=2, hi=4)
    c.dots([(4, 8), (4, 9), (5, 7)], "glass", 5)
    return c


@icon
def pearl_perfume():
    """진주 향수: a faceted glass bottle of pale violet scent, a gold collar and a pearl stopper."""
    c = Canvas()
    c.poly([(2.2, 7.0), (13.8, 7.0), (13.8, 14.9), (2.2, 14.9)], "glass", 1, normal=(-0.15, -0.1, 1), lo=2, hi=4)
    c.poly([(2.2, 7.0), (5.0, 7.0), (5.0, 14.9), (2.2, 14.9)], "glass", 1.1, normal=(-0.8, -0.1, 0.6), lo=3, hi=5)
    c.poly([(11.0, 7.0), (13.8, 7.0), (13.8, 14.9), (11.0, 14.9)], "glass", 1.1, normal=(0.8, 0.1, 0.5), lo=1, hi=2)
    c.rect(3.2, 8.4, 12.8, 14.0, "petal_purple", 2, normal=(-0.1, 0, 1), lo=1, hi=2)
    c.rect(5.6, 5.0, 10.4, 7.0, "gold", 3, normal=(-0.3, -0.4, 1), lo=2, hi=4)
    c.ellipse(8, 3.0, 2.4, 2.2, "pearl", 4, lo=2, hi=4)
    c.put(7, 2, "snow", 2, z=5)
    c.dots([(3, 8), (3, 9), (3, 10)], "glass", 5)
    return c


# ================================================================================================
# 추가 생산품 (세공대, 가공대, 손질대, 대장간, 조리대, 증류기) and the 압착기's goods
# ================================================================================================

@icon
def cut_emerald():
    return _gem("bottle_green")


@icon
def cut_diamond():
    c = _gem("ice")
    c.dots([(11, 3), (12, 4)], "snow", 2)
    return c


@icon
def lapis_beads():
    """청금석 구슬: a loop of deep blue beads with a little gold clasp and a tassel."""
    c = Canvas()
    import math
    for i in range(12):
        a = math.pi * 2 * i / 12
        x, y = 8 + 5.4 * math.cos(a), 7.2 + 4.6 * math.sin(a)
        c.ellipse(x, y, 1.2, 1.2, "sapphire", 1 + (i % 2) * 0.1, lo=1, hi=3)
    c.rect(7, 11, 9, 12.6, "gold", 3, normal=(0, -0.3, 1), lo=2, hi=4)
    c.line(8, 13, 8, 15, "fabric_red", 3, z=3).line(7, 14, 7, 15, "fabric_red", 2, z=3).line(9, 14, 9, 15, "fabric_red", 2, z=3)
    c.dots([(4, 4), (11, 4)], "sapphire", 5)
    return c


def _ring(gem=None):
    c = Canvas()
    c.ellipse(8, 10.2, 6.6, 4.6, "gold", 1)
    c.hole(8, 10.6, 4.4, 2.6)
    if gem:
        c.rect(6, 4.5, 10, 6.5, "gold", 3, normal=(0, -0.4, 1))
        c.ellipse(8, 3.6, 3.0, 2.6, gem, 4)
    else:
        c.line(3, 8, 6, 7, "gold", 4, z=3).line(10, 7, 13, 8, "gold", 4, z=3)
    return c


@icon
def gold_ring():
    return _ring()


@icon
def diamond_ring():
    """A gold band, four prongs holding a big brilliant-cut diamond on top."""
    c = _ring()
    c.rect(6, 4.6, 10, 6.6, "gold", 3, normal=(0, -0.4, 1))
    c.poly([(3.6, 3.4), (12.4, 3.4), (8, 8.0)], "ice", 4, normal=(0.1, 0.4, 1), lo=1, hi=3)
    c.poly([(5.0, 1.0), (11.0, 1.0), (12.4, 3.4), (3.6, 3.4)], "ice", 4.5, normal=(-0.3, -0.6, 0.8), lo=3, hi=4)
    c.line(4, 3, 11, 3, "ice", 5, z=6)
    c.dots([(6, 1), (7, 2)], "snow", 2)
    c.dots([(5, 4), (11, 4)], "gold", 4)
    return c


@icon
def sapphire_brooch():
    """An oval brooch: a sapphire in a gold frame ringed with little gold beads."""
    c = Canvas()
    c.ellipse(8, 8, 6.4, 5.4, "gold", 1, lo=1, hi=4)
    c.ellipse(8, 8, 4.6, 3.8, "gold", 1.5, lo=3, hi=4, dome=-0.3)
    c.ellipse(8, 8, 3.6, 3.0, "sapphire", 2, lo=1, hi=4)
    c.dots([(6, 6), (7, 6), (6, 7)], "snow", 2)
    for x, y in ((2, 8), (13, 8), (8, 3), (8, 13), (4, 4), (12, 4), (4, 12), (12, 12)):
        c.put(x, y, "gold", 5, z=3)
    return c


def _dish_heap(mat, dots=()):
    c = Canvas()
    c.ellipse(8, 7.6, 5.6, 4.6, mat, 2, lo=1, hi=4, dome=0.9)
    c.poly([(1, 10.5), (15, 10.5), (12.5, 14.5), (3.5, 14.5)], "porcelain", 3, normal=(0, 0.5, 0.85), lo=2, hi=4)
    c.tube([(1.6, 10.6), (14.4, 10.6)], [0.9, 0.9], "porcelain", 4, lo=2, hi=5)
    for (pts, m, st) in dots:
        c.dots(pts, m, st)
    return c


def _potato_print(c):
    c.ellipse(6.5, 9.6, 3.0, 2.4, "plush", 5, lo=2, hi=4)
    c.dots([(5, 9), (7, 10), (6, 11)], "plush", 0, z=6)
    c.dots([(4, 13), (6, 13), (8, 13)], "flour", 4, z=6)


@icon
def potato_starch():
    """감자 전분: a paper bag of fine white starch, a potato printed on it."""
    return _bag(_potato_print, ("flour", 2))


@icon
def brown_sugar():
    """흑설탕: dark crystals in a glass jar with a wooden lid."""
    c = Canvas()
    c.cyl(2.5, 4.5, 13.5, 14.9, "glass", 1, lo=2, hi=4, rounded=1.5)
    c.cyl(3.5, 7.0, 12.5, 14.0, "plush", 2, lo=1, hi=4, rounded=1.2)
    c.dots([(5, 9), (8, 8), (10, 10), (6, 12), (9, 12), (11, 8)], "plush", 5)
    c.cyl(3, 1.5, 13, 4.5, "wood", 3, lo=2, hi=4)
    c.dots([(3, 7), (3, 8), (3, 9)], "glass", 5)
    return c


@icon
def apple_juice():
    """사과즙: a glass bottle full of golden juice, a small apple label."""
    c = _long_bottle(Canvas(), "glass")
    c.cyl(4.7, 7.6, 11.3, 14.4, "topaz", 2, lo=1, hi=3, rounded=1.0)
    c.cyl(6.8, 4.0, 9.2, 7.6, "topaz", 2.1, lo=1, hi=3)
    c.cyl(6.3, 0.8, 9.7, 2.6, "leaf", 3, lo=2, hi=4)
    c.ellipse(8, 11.2, 2.0, 1.8, "cream", 4, lo=3, hi=4)
    c.ellipse(8, 11.4, 1.2, 1.1, "tomato", 5, lo=2, hi=4)
    c.put(8, 9, "leaf", 3, z=6)
    c.dots([(5, 8), (5, 9), (5, 10)], "topaz", 5)
    return c


@icon
def jocheong():
    """조청: a jar of dark amber grain syrup, a wooden dipper dripping into it."""
    c = Canvas()
    c.cyl(2, 5.0, 14, 14.9, "glass", 1, lo=2, hi=4, rounded=1.5)
    c.cyl(3, 6.5, 13, 14.0, "brass", 2, lo=1, hi=4, rounded=1.2)
    c.tube([(9.5, 7.5), (13.5, 1.5)], [0.55, 0.55], "wood", 3, lo=2, hi=4)
    c.ellipse(9.0, 6.6, 1.6, 1.2, "wood", 3, lo=2, hi=4)
    c.line(9, 8, 9, 9, "topaz", 4, z=4)
    c.dots([(4, 8), (4, 9), (4, 10)], "glass", 5)
    c.dots([(6, 9), (10, 12)], "topaz", 5)
    return c


@icon
def soy_sauce():
    """간장: a dark bottle with a red cap and a white label."""
    c = _long_bottle(Canvas(), "soot")
    c.cyl(6.3, 0.8, 9.7, 2.6, "fabric_red", 2, lo=1, hi=3)
    c.cyl(4.2, 9.2, 11.8, 12.6, "paper", 3, lo=2, hi=4)
    c.line(6, 10, 9, 10, "roe", 1, z=4).line(6, 11, 8, 11, "roe", 2, z=4)
    c.dots([(5, 7), (5, 8)], "soot", 5)
    return c


@icon
def shrimp_meat():
    """새우살: one big peeled shrimp curled into a C, orange bands on pale flesh, a red tail fan."""
    c = Canvas()
    pts = [(12.0, 4.2), (8.6, 2.8), (5.2, 4.0), (3.4, 7.2), (4.0, 10.6), (6.6, 12.8), (9.8, 13.0)]
    c.tube(pts, [2.4, 2.6, 2.6, 2.4, 2.1, 1.7, 1.1], "salmon", 1, lo=2, hi=5, flat=0.85)
    c.poly([(9.4, 11.6), (14.2, 10.6), (13.0, 14.8), (10.2, 14.2)], "tomato", 2, normal=(0.3, 0.2, 1), lo=2, hi=4)
    c.line(11, 12, 13, 13, "tomato", 1, z=3)
    for (x0, y0, x1, y1) in ((8, 1, 9, 4), (5, 3, 6, 5), (2, 7, 4, 7), (3, 11, 5, 10), (6, 13, 7, 11)):
        c.line(x0, y0, x1, y1, "fabric_red", 3, z=4)
    c.ellipse(12.4, 4.4, 1.2, 1.4, "cream", 5, lo=3, hi=4)
    return c


@icon
def gwamegi():
    """과메기: two dried saury halves, dark red flesh with a silver skin edge, on laver on a plate,
    with a slice of garlic and a green chili."""
    c = _plate(Canvas())
    c.poly([(2.0, 11.0), (4.6, 6.0), (13.6, 6.0), (11.0, 11.0)], "leaf_dark", 1, normal=(-0.1, -0.3, 1), lo=1, hi=2)
    for k, ((x0, y0), (x1, y1)) in enumerate((((3.6, 9.4), (11.6, 4.0)), ((5.6, 11.2), (13.8, 5.8)))):
        c.tube([(x0, y0), (x1, y1)], [1.6, 1.2], "wine", 2 + k, lo=1, hi=4, flat=0.55)
        c.line(int(x0) + 1, int(y0) + 1, int(x1), int(y1) + 1, "fish_silver", 4, z=2.5 + k)
        c.put(int(x1) - 1, int(y1) - 1, "wine", 5, z=2.6 + k)
    c.ellipse(3.6, 12.2, 1.2, 0.9, "garlic", 6, lo=3, hi=4)
    c.tube([(11.0, 12.2), (13.8, 11.4)], [0.55, 0.45], "leaf", 6, lo=2, hi=4)
    return c


@icon
def gulbi():
    """굴비: two golden-brown dried croakers hanging head-down from a knot of straw rope."""
    c = Canvas()
    c.tube([(0.8, 1.8), (15.2, 1.8)], [0.65, 0.65], "straw", 0, lo=2, hi=4)
    for k, x in enumerate((4.8, 11.2)):
        c.poly([(x - 2.1, 2.2), (x, 4.6), (x + 2.1, 2.2)], "straw", 1 + k, normal=(0, -0.4, 1), lo=1, hi=3)
        c.tube([(x, 4.4), (x - 0.2, 8.6), (x, 13.8)], [0.9, 2.3, 1.6], "straw", 1.5 + k, lo=2, hi=5)
        c.poly([(x + 1.6, 6.5), (x + 3.0, 8.0), (x + 1.8, 10.0)], "straw", 1.2 + k, normal=(0.4, 0, 1), lo=1, hi=2)
        c.dots([(int(x) - 1, 7), (int(x), 9), (int(x) - 1, 11)], "corn", 4, z=3 + k)
        c.put(int(x) - 1, 12, "roe", 0, z=3 + k).put(int(x), 14, "wood_dark", 1, z=3 + k)
        c.put(int(x), 3, "burlap", 1, z=6)
    return c


@icon
def hongeo():
    """삭힌 홍어: pale pink slices of fermented skate fanned on a plate."""
    c = _plate(Canvas())
    for k, (x, y) in enumerate(((4.4, 9.2), (7.8, 8.0), (11.2, 9.0))):
        c.ellipse(x, y, 2.8, 2.0, "seabream", 2 + k * 0.1, lo=3, hi=4, dome=0.5)
        c.line(int(x - 1), int(y - 1), int(x + 1), int(y + 1), "cream", 4, z=3 + k)
    c.dots([(12, 4), (13, 5)], "leaf", 3)
    return c


@icon
def jeotgal():
    """젓갈: an onggi crock of salted fish paste, little pale shrimp showing in it."""
    c = _crock("salmon")
    c.dots([(6, 2), (8, 3), (10, 2)], "pink", 4, z=6)
    c.dots([(7, 3), (9, 2)], "snow", 1, z=6)
    return c


@icon
def iron_nails():
    """쇠못: two steel nails lying side by side, flat heads at the bottom left, points at the top right,
    drawn pixel by pixel (a 1 px shaft with its lit and shaded side, a 5 px head)."""
    c = Canvas()
    for (hx, hy) in ((2, 11), (5, 14)):
        for i in range(1, 10):
            x, y = hx + i, hy - i
            c.put(x, y, "steel", 4 if i < 9 else 5, z=5)
            if i < 9:
                c.put(x + 1, y, "steel", 2, z=4)
        for d in (-2, -1, 0, 1, 2):
            c.put(hx + d, hy + d, "steel", 4 if d <= 0 else 2, z=6)
    return c


@icon
def copper_pot():
    """구리 냄비: a bright copper pot with a long handle, a darker rim."""
    c = Canvas()
    c.tube([(10.5, 7.2), (15, 3.5)], [0.8, 0.7], "wood_dark", 0, lo=2, hi=3)
    c.cyl(1.5, 6.5, 12.5, 14.5, "copper", 1, lo=1, hi=4, rounded=1.4)
    c.ellipse(7, 6.6, 5.6, 1.8, "copper", 1.5, lo=1, hi=2, dome=-0.3)
    c.tube([(1.6, 6.8), (12.4, 6.8)], [0.6, 0.6], "copper", 2, lo=3, hi=5)
    c.dots([(3, 9), (3, 10), (4, 8)], "copper", 5)
    return c


@icon
def gamasot():
    """가마솥: a black cast-iron cauldron with a wide rim, the heavy lid and its knob."""
    c = Canvas()
    c.ellipse(8, 10.6, 6.0, 4.6, "blacksteel", 1, lo=1, hi=3)
    c.tube([(0.8, 8.6), (15.2, 8.6)], [1.0, 1.0], "blacksteel", 2, lo=1, hi=4)
    c.ellipse(8, 6.6, 6.0, 2.6, "blacksteel", 3, lo=2, hi=4, dome=0.6)
    c.ellipse(8, 3.8, 1.4, 1.2, "blacksteel", 4, lo=2, hi=5)
    c.dots([(4, 6), (5, 5)], "blade", 3)
    return c


@icon
def iron_lantern():
    """철 랜턴: an iron lantern with a ring on top, warm light behind its panes."""
    c = Canvas()
    c.ellipse(8, 1.8, 1.6, 1.4, "steel", 0, lo=2, hi=4)
    c.hole(8, 1.8, 0.6, 0.5)
    c.poly([(4, 5), (8, 3), (12, 5)], "blacksteel", 1, normal=(0, -0.6, 0.8), lo=2, hi=4)
    c.rect(4, 5, 12, 13, "lamp", 2, normal=(-0.2, 0, 1), lo=2, hi=5)
    for x in (4, 8, 11):
        c.line(x, 5, x, 12, "blacksteel", 1, z=3)
    c.line(4, 5, 11, 5, "blacksteel", 2, z=3).line(4, 9, 11, 9, "blacksteel", 1, z=3)
    c.rect(3, 13, 13, 14.6, "blacksteel", 3, normal=(0, 0.4, 0.9), lo=1, hi=3)
    return c


@icon
def ornamental_sword():
    """장식용 도검: a straight bright blade, a gold guard, a red-wrapped grip and a gold pommel."""
    c = Canvas()
    c.tube([(5.6, 10.4), (14.4, 1.6)], [1.15, 0.45], "blade", 1, lo=2, hi=5, flat=0.5)
    c.line(7, 8, 13, 2, "snow", 2, z=2)
    c.tube([(3.0, 8.6), (7.4, 13.0)], [0.85, 0.85], "gold", 3, lo=2, hi=5)
    c.tube([(4.6, 11.4), (2.6, 13.4)], [0.75, 0.75], "crimson", 2, lo=2, hi=4)
    c.dots([(3, 12), (4, 11)], "gold", 4, z=4)
    c.ellipse(1.9, 14.1, 1.2, 1.2, "gold", 4, lo=2, hi=4)
    return c


@icon
def gyeranmari():
    """계란말이: three slices of rolled omelette standing on a plate, the yellow layers rolled up inside."""
    c = _plate(Canvas())
    for k, x in enumerate((4.0, 8.0, 12.0)):
        y = 7.8 if k != 1 else 7.0
        c.ellipse(x, y, 2.0, 2.8, "corn", 2 + k * 0.1, lo=2, hi=4, dome=0.4)
        c.ellipse(x, y, 1.2, 1.8, "petal_yellow", 2.05 + k * 0.1, lo=3, hi=5, dome=0.2)
        c.put(int(x), int(y), "corn", 1, z=3 + k)
        c.dots([(int(x) - 1, int(y) - 2), (int(x) + 1, int(y) + 1)], "leaf", 3, z=3 + k)
    return c


@icon
def hotteok():
    """호떡: a flat round pancake fried golden, a bite taken out showing the dark brown-sugar syrup and
    seeds inside, a drip of syrup running down."""
    c = Canvas()
    c.ellipse(7.6, 9.0, 7.0, 5.6, "straw", 1, lo=1, hi=3, dome=0.5)
    c.ellipse(7.2, 8.6, 5.2, 4.0, "straw", 1.2, lo=2, hi=4, dome=0.3)
    c.dots([(4, 7), (7, 6), (9, 9), (5, 10), (8, 11)], "straw", 1, z=3)
    bite = [(x, y) for x in range(9, 16) for y in range(2, 9) if (x + 0.5 - 13.4) ** 2 + (y + 0.5 - 5.0) ** 2 < 9.5]
    c.erase(bite)
    c.tube([(9.6, 5.6), (11.0, 7.6), (13.4, 8.6)], [0.75, 0.75, 0.75], "plush", 4, lo=1, hi=3)
    c.dots([(10, 6), (12, 8)], "cream", 4, z=5)
    c.line(11, 9, 11, 12, "plush", 2, z=5)
    return c


@icon
def gamjajeon():
    """감자전: a crisp golden potato pancake, browner at the edge, on a plate."""
    c = _plate(Canvas())
    c.ellipse(8, 8.4, 6.2, 3.4, "straw", 2, lo=1, hi=3, dome=0.4)
    c.ellipse(8, 8.2, 4.6, 2.4, "corn", 2.5, lo=2, hi=4, dome=0.3)
    c.dots([(5, 8), (7, 7), (10, 8), (9, 9), (6, 9)], "cream", 4)
    c.dots([(12, 7), (3, 9)], "leaf", 3)
    return c


@icon
def bulgogi():
    """불고기: glossy brown slices of beef with onion and scallion on a plate."""
    c = _plate(Canvas())
    for k, (x, y) in enumerate(((4.6, 8.8), (8.2, 7.4), (11.4, 9.2), (7.4, 9.8))):
        c.ellipse(x, y, 2.9, 1.7, "leather", 2 + k * 0.1, lo=1, hi=4, dome=0.6)
    c.dots([(6, 7), (10, 8), (5, 10)], "garlic", 4)
    c.dots([(9, 6), (12, 8), (4, 8)], "leaf", 3)
    c.dots([(7, 8), (11, 10)], "leather", 5)
    return c


@icon
def jeyuk():
    """제육볶음: pork stir-fried red and spicy, onion and scallion through it, on a plate."""
    c = _plate(Canvas())
    for k, (x, y) in enumerate(((4.6, 8.8), (8.2, 7.4), (11.4, 9.2), (7.4, 9.8))):
        c.ellipse(x, y, 2.9, 1.7, "terracotta", 2 + k * 0.1, lo=1, hi=4, dome=0.6)
    c.dots([(6, 7), (10, 8), (5, 10)], "garlic", 4)
    c.dots([(9, 6), (12, 8), (4, 8)], "leaf", 3)
    c.dots([(7, 8), (11, 10), (5, 9)], "pepper", 4)
    return c


def _ttukbaegi(c, broth):
    """뚝배기: a black clay pot seen a little from above, its broth on the rim's ellipse."""
    c.poly([(1.2, 8.0), (14.8, 8.0), (13.2, 14.0), (2.8, 14.0)], "soot", 0, normal=(-0.1, 0.5, 0.85), lo=1, hi=3)
    c.tube([(1.6, 8.3), (14.4, 8.3)], [0.8, 0.8], "soot", 0.2, lo=2, hi=3)
    c.ellipse(8, 8.0, 6.2, 2.4, broth, 1, lo=2, hi=4, dome=0.3)
    return c


@icon
def samgyetang():
    """삼계탕: a whole young chicken in milky broth in a black pot, a jujube and scallion on top."""
    c = _ttukbaegi(Canvas(), "rice")
    c.ellipse(8, 6.8, 4.0, 2.6, "cream", 2, lo=2, hi=4, dome=0.8)
    c.ellipse(5.0, 6.2, 1.1, 1.6, "cream", 2.5, lo=2, hi=4)
    c.ellipse(11.0, 6.2, 1.1, 1.6, "cream", 2.5, lo=2, hi=4)
    c.put(9, 5, "fabric_red", 3, z=4).put(6, 8, "leaf", 3, z=4).put(11, 8, "leaf", 4, z=4)
    c.dots([(6, 3), (7, 2), (10, 3)], "snow", 1)
    return c


@icon
def doenjang_jjigae():
    """된장찌개: brown soybean-paste stew with tofu cubes, zucchini and chili in a black pot."""
    c = _ttukbaegi(Canvas(), "doenjang")
    for x, y in ((5, 7), (9, 8), (11, 7)):
        c.rect(x - 1, y - 1, x + 1, y + 0.6, "cream", 2, normal=(-0.2, -0.4, 1), lo=3, hi=4)
    c.ellipse(7.2, 8.6, 1.3, 0.8, "zucchini", 2, lo=2, hi=4)
    c.dots([(8, 7), (12, 8)], "pepper", 4)
    c.dots([(6, 4), (7, 3), (10, 4)], "snow", 1)
    return c


@icon
def kimchi_jjigae():
    """김치찌개: red stew with kimchi, tofu and pork in a black pot, steaming."""
    c = _ttukbaegi(Canvas(), "pepper")
    for x, y in ((5, 7), (10, 8)):
        c.rect(x - 1, y - 1, x + 1, y + 0.6, "cream", 2, normal=(-0.2, -0.4, 1), lo=3, hi=4)
    c.dots([(7, 7), (8, 8), (11, 7)], "cabbage", 4)
    c.dots([(6, 9), (9, 7)], "seabream", 4)
    c.dots([(6, 4), (7, 3), (10, 4), (11, 3)], "snow", 1)
    return c


@icon
def tteokbokki():
    """떡볶이: three white rice-cake sticks and a fish-cake triangle in red sauce, in a shallow black pan;
    each stick sits in a dark line of sauce so they read apart."""
    c = Canvas()
    c.ellipse(8, 10.0, 7.4, 4.6, "soot", 0, lo=1, hi=3, dome=0.3)
    c.ellipse(8, 9.4, 6.4, 3.8, "pepper", 1, lo=2, hi=4, dome=0.4)
    for k, (x, y) in enumerate(((4.6, 8.4), (9.4, 7.6), (7.6, 11.0))):
        c.tube([(x - 2.0, y), (x + 2.0, y)], [1.25, 1.25], "crimson", 2 + k * 0.2, lo=1, hi=1)
        c.tube([(x - 1.7, y - 0.15), (x + 1.7, y - 0.15)], [0.8, 0.8], "rice", 2.1 + k * 0.2, lo=3, hi=4)
    c.poly([(11.8, 12.0), (14.0, 11.0), (12.8, 9.0)], "straw", 3, normal=(0, -0.4, 1), lo=2, hi=4)
    c.put(3, 11, "leaf", 3, z=5).put(12, 6, "leaf", 4, z=5)
    return c


@icon
def flower_tea():
    """꽃잎 차: a white teacup of pale pink tea on its saucer, a little flower floating, steam."""
    c = Canvas()
    c.ellipse(8, 13.0, 7.0, 2.2, "porcelain", 0, lo=1, hi=3, dome=0.3)
    c.poly([(3.0, 7.2), (13.0, 7.2), (11.4, 13.0), (4.6, 13.0)], "porcelain", 1, normal=(-0.15, 0.3, 0.95), lo=2, hi=4)
    c.ellipse(8, 7.2, 5.0, 1.7, "pink", 2, lo=2, hi=4, dome=-0.2)
    c.tube([(12.6, 8.2), (14.6, 9.4), (12.2, 11.2)], [0.6, 0.6, 0.6], "porcelain", 1.5, lo=2, hi=4)
    for (x, y) in ((7, 6), (9, 6), (8, 7), (8, 5)):
        c.put(x, y, "pink", 5, z=4)
    c.put(8, 6, "petal_yellow", 2, z=5)
    c.dots([(5, 3), (6, 2), (10, 3), (11, 2)], "snow", 1)
    return c


@icon
def cider():
    """사과주: a stout green bottle of apple wine, an apple on the label."""
    c = _long_bottle(Canvas(), "bottle_green", x0=3.6, x1=12.4, shoulder=7.6)
    c.cyl(6.3, 0.8, 9.7, 2.6, "straw", 2, lo=2, hi=4)
    c.cyl(3.6, 9.4, 12.4, 13.2, "cream", 3, lo=2, hi=4)
    c.ellipse(8, 11.3, 1.4, 1.3, "tomato", 4, lo=2, hi=4)
    c.put(8, 9, "leaf", 3, z=5)
    c.dots([(5, 8), (5, 9)], "bottle_green", 5)
    return c


@icon
def strawberry_wine():
    """딸기주: a slim bottle of rosy strawberry wine, a strawberry on the label."""
    c = _long_bottle(Canvas(), "pink")
    c.cyl(6.3, 0.8, 9.7, 2.6, "fabric_red", 2, lo=2, hi=4)
    c.cyl(4.2, 9.4, 11.8, 12.8, "paper", 3, lo=2, hi=4)
    c.poly([(6.8, 10), (9.2, 10), (8, 12.4)], "tomato", 4, normal=(0, -0.2, 1), lo=2, hi=4)
    c.put(8, 9, "leaf", 3, z=5)
    c.dots([(5, 7), (5, 8)], "pink", 5)
    return c


@icon
def mead():
    """꿀술: a round clay jug of honey wine, a honeycomb label, a cork."""
    c = Canvas()
    c.ellipse(8, 10.0, 6.0, 5.2, "clay", 1, lo=1, hi=4)
    c.cyl(6.2, 2.6, 9.8, 5.6, "clay", 1.2, lo=2, hi=4)
    c.tube([(10.4, 4.2), (13.6, 6.0), (13.0, 9.6)], [0.6, 0.6, 0.6], "clay", 0.5, lo=2, hi=3)
    c.rect(6.6, 1.0, 9.4, 2.8, "wood", 2, normal=(-0.3, -0.5, 1))
    c.ellipse(8, 10.4, 2.6, 2.2, "topaz", 3, lo=2, hi=4)
    c.dots([(7, 10), (9, 10), (8, 11)], "brass", 2)
    return c


@icon
def cheongju():
    """청주: a white porcelain bottle with blue bands, clear rice wine inside."""
    c = _long_bottle(Canvas(), "porcelain", x0=4.0, x1=12.0, shoulder=7.4)
    c.cyl(6.3, 0.8, 9.7, 2.4, "sapphire", 2, lo=2, hi=3)
    c.line(4, 10, 11, 10, "sapphire", 3, z=4).line(4, 13, 11, 13, "sapphire", 2, z=4)
    c.dots([(6, 11), (8, 12), (10, 11)], "sapphire", 4)
    c.dots([(5, 8), (5, 9)], "snow", 2)
    return c


@icon
def seed_oil():
    return _flask("corn")


@icon
def compost():
    """퇴비: a dark crumbly heap with bits of leaf and twig in it."""
    c = Canvas()
    c.ellipse(8, 10.4, 6.8, 4.4, "soil", 1, lo=1, hi=4, dome=0.9)
    c.ellipse(7, 7.4, 4.4, 3.0, "soil", 2, lo=1, hi=4, dome=0.9)
    c.dots([(4, 9), (9, 7), (11, 11), (6, 12), (8, 5)], "leaf", 3)
    c.dots([(5, 7), (10, 9), (12, 12)], "wood", 3)
    c.dots([(3, 11), (7, 10), (10, 12), (6, 6)], "soil", 0)
    return c


def _cube(mat, cracks):
    """A pressed block seen from the corner: lit top, two shaded sides, crack lines across it."""
    c = Canvas()
    c.poly([(8, 1.5), (14.6, 4.8), (8, 8.1), (1.4, 4.8)], mat, 1, normal=(0, -1, 0.5), lo=3, hi=4)
    c.poly([(1.4, 4.8), (8, 8.1), (8, 15.2), (1.4, 11.9)], mat, 1, normal=(-0.8, 0.2, 0.6), lo=2, hi=3)
    c.poly([(8, 8.1), (14.6, 4.8), (14.6, 11.9), (8, 15.2)], mat, 1, normal=(0.8, 0.2, 0.4), lo=1, hi=2)
    for (x0, y0, x1, y1) in cracks:
        c.line(x0, y0, x1, y1, mat, 0, z=3)
    return c


@icon
def compressed_stone():
    return _cube("stone", ((3, 7, 5, 11), (10, 9, 12, 12), (6, 4, 9, 5)))


@icon
def compressed_dirt():
    c = _cube("soil", ((3, 8, 5, 10), (11, 8, 12, 11)))
    c.dots([(6, 3), (10, 4), (9, 6)], "leaf", 3)
    return c


@icon
def refined_oil():
    """정제유: a tin oil can with a spout, an amber drop on its cream label."""
    c = Canvas()
    c.rect(2.5, 5, 12, 15, "steel", 1, normal=(-0.2, -0.1, 1), lo=2, hi=4)
    c.poly([(12, 5), (14, 3.8), (14, 13.8), (12, 15)], "steel", 1, normal=(0.85, 0, 0.4), lo=1, hi=2)
    c.poly([(2.5, 5), (4.5, 3.8), (14, 3.8), (12, 5)], "steel", 1.2, normal=(-0.2, -0.8, 0.6), lo=3, hi=4)
    c.tube([(10, 4), (12.5, 0.8)], [0.7, 0.5], "steel", 2, lo=2, hi=4)
    c.rect(3.5, 7.5, 11, 13, "cream", 2, normal=(-0.1, 0, 1), lo=3, hi=4)
    c.ellipse(7.2, 11.2, 2.4, 2.2, "topaz", 3, lo=0, hi=2)
    c.poly([(5.0, 10.6), (9.4, 10.6), (7.2, 7.0)], "topaz", 3.1, normal=(-0.3, -0.5, 1), lo=1, hi=2)
    c.put(6, 10, "snow", 2, z=4).put(6, 11, "topaz", 4, z=4)
    return c


@icon
def soap():
    """비누: a pale green bar of soap with a pressed leaf on it, bubbles around."""
    c = Canvas()
    c.poly([(1.5, 9), (5.5, 5), (14.5, 5), (10.5, 9)], "musk", 1, normal=(-0.2, -0.7, 0.8), lo=3, hi=5)
    c.poly([(1.5, 9), (10.5, 9), (10.5, 13.5), (1.5, 13.5)], "musk", 1, normal=(-0.1, 0.2, 1), lo=2, hi=4)
    c.poly([(10.5, 9), (14.5, 5), (14.5, 9.5), (10.5, 13.5)], "musk", 1, normal=(0.8, 0.1, 0.5), lo=1, hi=2)
    c.line(6, 7, 9, 6, "musk", 2, z=3).put(7, 6, "leaf", 3, z=3)
    for (x, y, r) in ((3.0, 3.2, 1.4), (6.0, 1.8, 0.9), (12.6, 12.6, 1.2)):
        c.ellipse(x, y, r, r, "glass", 4, lo=3, hi=5, dome=-0.4)
    return c


def _sprout_print(c):
    c.line(6, 9, 6, 13, "leaf", 2, z=5)
    c.ellipse(4.6, 8.6, 1.6, 1.0, "leaf", 5, lo=2, hi=4)
    c.ellipse(7.6, 7.8, 1.6, 1.0, "leaf", 5, lo=2, hi=4)


@icon
def fertilizer():
    """비료: a paper sack with a sprout printed on it, the top folded in green."""
    return _bag(_sprout_print, ("leaf", 2))


@icon
def scented_candle():
    """향초: a glass jar of pink wax, a lit wick, a pressed flower on the jar."""
    c = Canvas()
    c.cyl(2.5, 6.0, 13.5, 14.9, "glass", 1, lo=2, hi=4, rounded=1.4)
    c.cyl(3.5, 8.0, 12.5, 14.0, "pink", 2, lo=2, hi=4, rounded=1.0)
    c.ellipse(8, 8.0, 4.5, 1.2, "pink", 2.2, lo=3, hi=4)
    c.line(8, 5, 8, 7, "soot", 1, z=3)
    c.ellipse(8, 3.2, 1.2, 2.0, "fire", 4, lo=3, hi=5)
    c.put(8, 3, "fire", 5, z=5)
    c.ellipse(8, 11.4, 1.6, 1.6, "petal_purple", 4, lo=1, hi=3)
    c.put(8, 11, "petal_yellow", 2, z=5)
    c.dots([(3, 9), (3, 10), (3, 11)], "glass", 5)
    return c


@icon
def stone_lantern():
    """석등: a stone lantern, a warm light in its window, a curved stone roof and a knob on top."""
    c = Canvas()
    c.rect(4, 13.4, 12, 15, "stone", 1, normal=(0, -0.2, 1), lo=1, hi=3)
    c.rect(6.4, 10.4, 9.6, 13.4, "stone", 1.2, normal=(-0.3, 0, 1), lo=2, hi=3)
    c.rect(4.4, 9.4, 11.6, 10.6, "stone", 1.4, normal=(0, -0.5, 1), lo=2, hi=4)
    c.rect(5.0, 5.6, 11.0, 9.4, "stone", 2, normal=(-0.2, 0, 1), lo=2, hi=3)
    c.rect(6.4, 6.4, 9.6, 8.8, "lamp", 3, normal=(0, 0, 1), lo=3, hi=5)
    c.line(8, 6, 8, 8, "stone", 1, z=4)
    c.poly([(1.6, 5.8), (4.4, 3.2), (11.6, 3.2), (14.4, 5.8)], "stone", 4, normal=(-0.2, -0.7, 0.7), lo=2, hi=4)
    c.ellipse(8, 2.2, 1.4, 1.2, "stone", 5, lo=2, hi=4)
    return c


# ================================================================================================
# 원재료만으로 만드는 물건 (no other machine's goods)
# ================================================================================================

@icon
def copper_bracelet():
    """구리 팔찌: a wide copper band seen at an angle, an emerald set in it."""
    c = Canvas()
    c.ellipse(8, 9.2, 7.0, 5.0, "copper", 1, lo=1, hi=4)
    c.hole(8, 9.8, 4.8, 2.8)
    c.line(2, 9, 4, 7, "copper", 5, z=3)
    c.ellipse(8, 4.6, 2.0, 1.6, "bottle_green", 3, lo=2, hi=5)
    c.dots([(7, 4)], "snow", 2)
    return c


@icon
def amethyst_pendant():
    """자수정 펜던트: a gold chain hanging in a V, a pointed amethyst crystal at the bottom."""
    c = Canvas()
    for i in range(6):
        c.put(2 + i, 1 + i, "gold", 3 + i % 2, z=1).put(13 - i, 1 + i, "gold", 3 + i % 2, z=1)
    c.rect(7, 6.6, 9, 8, "gold", 2, normal=(0, -0.3, 1), lo=2, hi=4)
    c.poly([(5.4, 8.6), (10.6, 8.6), (10.0, 12.0), (8, 15.2), (6.0, 12.0)], "crystal", 3, normal=(-0.2, -0.1, 1), lo=1, hi=4)
    c.poly([(8, 8.6), (10.6, 8.6), (10.0, 12.0), (8, 15.2)], "crystal", 3.1, normal=(0.7, 0, 0.6), lo=1, hi=2)
    c.dots([(6, 9), (7, 10)], "magic", 5)
    return c


@icon
def quartz_figurine():
    """석영 조각상: a little white quartz bird carved and polished, on a dark wooden stand."""
    c = Canvas()
    c.rect(2.5, 12.6, 13.5, 15, "wood_dark", 0, normal=(0, -0.3, 1), lo=1, hi=3)
    c.ellipse(8.2, 9.2, 4.6, 3.4, "porcelain", 1, lo=2, hi=4)
    c.ellipse(4.6, 5.6, 2.4, 2.2, "porcelain", 2, lo=2, hi=4)
    c.poly([(2.0, 5.6), (0.6, 6.4), (2.2, 6.8)], "petal_yellow", 3, normal=(0, 0, 1), lo=2, hi=3)
    c.poly([(11.6, 8.0), (15.2, 5.0), (13.6, 9.6)], "porcelain", 1.5, normal=(0.4, -0.2, 1), lo=2, hi=4)
    c.put(4, 5, "roe", 1, z=4)
    c.line(6, 9, 10, 8, "glass", 3, z=3)
    return c


@icon
def gangnaengi():
    """강냉이: a heap of puffed corn kernels in a paper cone."""
    c = Canvas()
    for (x, y) in ((5, 4), (8, 3), (11, 4), (4, 6), (7, 5.5), (10, 6), (12, 7), (6, 7.6), (9, 7.8)):
        c.ellipse(x, y, 1.6, 1.4, "rice", 1 + (x + y) % 3 * 0.1, lo=3, hi=4)
        c.put(int(x), int(y) + 1, "corn", 4, z=3)
    c.poly([(2.4, 8.4), (13.6, 8.4), (9.0, 15.4), (7.0, 15.4)], "paper", 2, normal=(-0.2, 0.2, 1), lo=2, hi=4)
    c.line(4, 10, 11, 10, "fabric_red", 3, z=3).line(6, 12, 10, 12, "fabric_red", 2, z=3)
    return c


@icon
def onion_juice():
    """양파즙: a round flask of brown onion juice, an onion drawn on it."""
    c = _flask("onion")
    c.ellipse(8, 11.0, 1.8, 1.6, "garlic", 5, lo=2, hi=4)
    c.line(8, 9, 8, 9, "leaf", 3, z=6)
    return c


@icon
def mumallaengi():
    """무말랭이: pale curly strips of dried radish spread on a dark bamboo tray."""
    c = Canvas()
    c.ellipse(8, 10.0, 7.4, 4.8, "wood_dark", 0, lo=1, hi=3, dome=0.2)
    c.ellipse(8, 9.6, 6.4, 3.8, "wood", 0.2, lo=1, hi=2, dome=-0.2)
    for k, ((x0, y0), (x1, y1)) in enumerate((((3.4, 9.6), (7.0, 7.0)), ((6.0, 11.8), (10.4, 8.6)), ((9.0, 10.6), (12.8, 8.0)),
                                               ((5.0, 7.6), (9.0, 7.0)), ((8.6, 12.4), (12.0, 11.0)))):
        c.tube([(x0, y0), (x1, y1)], [0.55, 0.55], "garlic", 2 + k * 0.1, lo=3, hi=4)
    c.dots([(5, 9), (9, 9), (11, 10)], "petal_yellow", 2, z=4)
    return c


@icon
def salted_mackerel():
    """간고등어: a whole salted mackerel (drawn like the other fish), dark wavy stripes on its blue-green
    back, a silver belly, white salt crystals sprinkled on it."""
    c = Canvas()
    _tail(c, "fish_blue", step_lo=1, step_hi=2)
    c.tube([(3.2, 12.6), (6.8, 9.2), (11, 5)], [2.0, 3.0, 1.6], "fish_silver", 2, flat=0.9)
    c.tube([(2.6, 11.8), (5.9, 8.4), (10.2, 4.2)], [1.2, 2.1, 1.2], "bottle_green", 3, flat=0.9, lo=1, hi=3)
    for (x, y) in ((5, 7), (7, 5), (9, 4), (6, 9), (8, 7)):
        c.put(x, y, "fish_blue", 0, z=4)
    c.dots([(5, 11), (8, 9), (10, 7), (7, 11), (4, 13), (11, 6)], "snow", 2, z=5)
    c.put(3, 11, "roe", 0, z=5).put(4, 10, "snow", 1, z=5)
    return c


@icon
def fish_sauce():
    """까나리 액젓: a glass bottle of dark amber fish sauce with a red cap and a fish on the label."""
    c = _long_bottle(Canvas(), "glass")
    c.cyl(4.7, 7.6, 11.3, 14.4, "doenjang", 2, lo=1, hi=3, rounded=1.0)
    c.cyl(6.8, 4.0, 9.2, 7.6, "doenjang", 2.1, lo=1, hi=3)
    c.cyl(6.3, 0.8, 9.7, 2.6, "fabric_red", 3, lo=2, hi=4)
    c.cyl(4.2, 9.6, 11.8, 12.6, "paper", 4, lo=2, hi=4)
    c.tube([(6.0, 11.1), (9.4, 11.1)], [0.7, 0.5], "fish_blue", 5, lo=2, hi=4)
    c.put(10, 11, "fish_blue", 2, z=6)
    return c


@icon
def homi():
    """호미: the Korean hand hoe, a curved pointed iron blade on a short wooden handle."""
    c = Canvas()
    c.tube([(2.0, 14.0), (8.4, 7.6)], [1.0, 0.9], "wood", 1, lo=2, hi=4)
    c.tube([(8.4, 7.6), (10.2, 5.8)], [0.5, 0.5], "steel", 2, lo=2, hi=4)
    c.poly([(9.0, 4.2), (12.6, 1.0), (15.2, 3.4), (13.0, 7.4), (11.2, 6.4)], "steel", 3, normal=(-0.3, -0.5, 1), lo=2, hi=4)
    c.line(12, 2, 14, 4, "blade", 4, z=4)
    return c


@icon
def sickle():
    """낫: a curved steel blade on a wooden handle."""
    c = Canvas()
    c.tube([(3.0, 15.0), (7.6, 9.2)], [1.0, 0.9], "wood", 1, lo=2, hi=4)
    c.tube([(7.6, 9.2), (7.4, 5.0), (9.6, 2.0), (13.2, 1.6), (15.0, 3.2)], [0.9, 1.3, 1.3, 1.0, 0.5], "steel", 2, lo=2, hi=5, flat=0.6)
    c.line(9, 3, 13, 2, "blade", 4, z=3)
    return c


@icon
def wind_chime():
    """풍경: a temple wind bell of copper on a cord, a little fish-shaped clapper plate hanging below."""
    c = Canvas()
    c.line(8, 0, 8, 2, "straw", 2, z=0)
    c.ellipse(8, 3.0, 1.2, 1.0, "copper", 1, lo=2, hi=4)
    c.poly([(4.4, 4.0), (11.6, 4.0), (12.4, 9.6), (3.6, 9.6)], "copper", 2, normal=(-0.3, 0, 1), lo=1, hi=4)
    c.tube([(3.6, 9.8), (12.4, 9.8)], [0.7, 0.7], "copper", 2.2, lo=2, hi=4)
    c.line(8, 10, 8, 12, "straw", 2, z=3)
    c.poly([(6.0, 13.6), (8, 12.0), (10.0, 13.6), (8, 15.2)], "bottle_green", 3, normal=(0, 0, 1), lo=1, hi=3)
    c.poly([(10.0, 13.6), (11.4, 12.6), (11.4, 14.6)], "bottle_green", 3.1, normal=(0, 0, 1), lo=1, hi=2)
    c.dots([(5, 6), (5, 7)], "copper", 5)
    return c


@icon
def godeungeo_gui():
    """고등어구이: a grilled mackerel on a plate, dark grill stripes on its blue-silver skin, lemon wedge."""
    c = _plate(Canvas())
    c.tube([(3.0, 9.6), (7.0, 8.4), (11.6, 7.6)], [1.4, 2.0, 1.2], "fish_silver", 2, lo=2, hi=4)
    c.poly([(11.2, 7.6), (14.4, 5.6), (14.2, 9.4)], "fish_blue", 2.2, normal=(0.3, 0, 1), lo=1, hi=3)
    for x in (5, 7, 9):
        c.line(x, 7, x + 1, 10, "soot", 1, z=3)
    c.line(4, 8, 10, 7, "fish_blue", 2, z=2.5)
    c.put(3, 9, "roe", 0, z=3)
    c.poly([(10.0, 11.2), (12.8, 10.4), (12.4, 12.4)], "petal_yellow", 4, normal=(0, 0, 1), lo=2, hi=4)
    return c


@icon
def oi_muchim():
    """오이무침: crisp cucumber rounds tossed in red chili and garlic on a plate."""
    c = _plate(Canvas())
    for k, (x, y) in enumerate(((4.6, 8.8), (8.0, 7.6), (11.4, 8.8), (6.4, 10.2), (9.8, 10.2))):
        c.ellipse(x, y, 1.8, 1.2, "cucumber", 2 + k * 0.1, lo=2, hi=4)
        c.ellipse(x, y, 1.0, 0.6, "cabbage", 2.05 + k * 0.1, lo=3, hi=4)
    c.dots([(5, 8), (8, 7), (11, 8), (7, 10), (10, 9), (6, 9)], "pepper", 4, z=5)
    c.dots([(9, 8), (4, 10)], "garlic", 4, z=5)
    return c


@icon
def beoseot_bokkeum():
    """버섯볶음: sliced brown mushrooms stir-fried with onion on a plate."""
    c = _plate(Canvas())
    for k, (x, y) in enumerate(((4.6, 8.4), (8.4, 7.4), (11.6, 8.8), (7.0, 10.0))):
        c.ellipse(x, y - 0.6, 1.9, 1.2, "plush", 2 + k * 0.1, lo=1, hi=4)
        c.rect(x - 0.6, y - 0.2, x + 0.6, y + 1.2, "cream", 2.05 + k * 0.1, normal=(0, 0, 1), lo=3, hi=4)
    c.dots([(10, 10), (5, 10)], "garlic", 4, z=5)
    c.dots([(9, 6), (12, 7)], "leaf", 3, z=5)
    return c


@icon
def gamja_jorim():
    """감자조림: cubes of potato glazed glossy brown in soy, sesame on top, on a plate."""
    c = _plate(Canvas())
    for k, (x, y) in enumerate(((4.4, 8.6), (7.8, 7.4), (11.2, 8.6), (6.2, 10.4), (9.6, 10.4))):
        c.rect(x - 1.4, y - 1.4, x + 1.4, y + 1.0, "plush", 2 + k * 0.1, normal=(-0.3, -0.4, 1), lo=1, hi=4)
        c.put(int(x) - 1, int(y) - 1, "straw", 5, z=3 + k)
    c.dots([(6, 8), (9, 9), (11, 7)], "cream", 4, z=6)
    return c


@icon
def pajeon():
    """파전: a round flat pancake fried golden, long green scallions laid through it, on a plate."""
    c = _plate(Canvas())
    c.ellipse(8, 8.4, 6.0, 3.4, "straw", 2, lo=1, hi=4, dome=0.4)
    for y in (7, 8, 9):
        c.line(3, y + 1, 12, y - 1, "leaf", 3, z=3)
    c.dots([(5, 7), (10, 9), (7, 10)], "straw", 5, z=4)
    return c


@icon
def miyeokguk():
    """미역국: a bowl of seaweed soup, dark green seaweed and a bit of beef in clear brown broth."""
    c = _bowl(Canvas(), "porcelain", 0)
    c.ellipse(8, 7.8, 6.0, 1.9, "doenjang", 1, lo=2, hi=4, dome=0.2)
    c.dots([(4, 7), (5, 8), (7, 7), (9, 8), (10, 7), (12, 8), (6, 7)], "leaf_dark", 1, z=3)
    c.dots([(8, 7), (11, 8)], "terracotta", 3, z=3)
    c.dots([(6, 4), (7, 3), (10, 4)], "snow", 1)
    return c


@icon
def kalguksu():
    """칼국수: a bowl of thick hand-cut noodles in a light broth with zucchini strips."""
    c = _bowl(Canvas(), "porcelain", 0)
    c.ellipse(8, 7.8, 6.0, 1.9, "bean", 1, lo=1, hi=2, dome=0.2)
    for x in (3, 5, 7, 9, 11):
        c.line(x, 7, x + 2, 8, "rice", 4, z=3)
        c.put(x + 1, 7, "rice", 5, z=3.5)
    c.dots([(6, 7), (10, 8), (12, 7)], "zucchini", 2, z=4)
    c.dots([(6, 4), (7, 3), (10, 4)], "snow", 1)
    return c


@icon
def injeolmi():
    """인절미: soft rice-cake squares rolled in yellow roasted-bean powder, on a plate."""
    c = _plate(Canvas())
    c.dots([(3, 10), (13, 10), (8, 12), (5, 12), (11, 12)], "corn", 3, z=1.5)
    for k, (x, y) in enumerate(((4.6, 8.2), (8.4, 7.0), (11.6, 8.6), (7.6, 10.4))):
        c.rect(x - 1.6, y - 1.4, x + 1.6, y + 1.2, "corn", 2 + k * 0.1, normal=(-0.2, -0.5, 1), lo=2, hi=4)
        c.put(int(x) - 1, int(y) - 1, "petal_yellow", 2, z=3 + k)
    return c


@icon
def dakbokkeumtang():
    """닭볶음탕: chicken pieces and potatoes braised in red sauce in a wide black pan."""
    c = Canvas()
    c.ellipse(8, 10.0, 7.4, 4.6, "soot", 0, lo=1, hi=3, dome=0.3)
    c.ellipse(8, 9.4, 6.4, 3.8, "pepper", 1, lo=1, hi=3, dome=0.4)
    for k, (x, y) in enumerate(((5.0, 9.0), (10.4, 8.4))):
        c.ellipse(x, y, 2.0, 1.4, "terracotta", 2 + k * 0.1, lo=2, hi=4)
    for k, (x, y) in enumerate(((8.0, 10.8), (7.4, 7.6))):
        c.ellipse(x, y, 1.4, 1.1, "corn", 2.5 + k * 0.1, lo=3, hi=4)
    c.tube([(1.0, 9.0), (-0.5, 7.5)], [0.7, 0.7], "soot", 0.5, lo=1, hi=2)
    c.dots([(12, 10), (4, 11)], "leaf", 3, z=5)
    return c


@icon
def gamjatang():
    """감자탕: a black clay pot of red pork-bone stew, a big bone and a potato showing, steaming."""
    c = _ttukbaegi(Canvas(), "pepper")
    c.tube([(4.0, 8.0), (9.0, 6.8)], [0.9, 0.9], "garlic", 2, lo=3, hi=4)
    c.ellipse(4.0, 8.0, 1.2, 1.2, "garlic", 2.1, lo=3, hi=4)
    c.ellipse(11.0, 7.8, 1.6, 1.2, "corn", 2.2, lo=3, hi=4)
    c.dots([(7, 8), (12, 9)], "leaf", 3, z=4)
    c.dots([(6, 4), (7, 3), (10, 4), (11, 3)], "snow", 1)
    return c


@icon
def pumpkin_makgeolli():
    """단호박 막걸리: a white bottle of creamy orange rice wine, a pumpkin-orange cap and label."""
    c = _long_bottle(Canvas(), "porcelain")
    c.cyl(6.3, 0.8, 9.7, 2.6, "fox", 2, lo=1, hi=3)
    c.cyl(4.2, 9.4, 11.8, 12.4, "fox", 3, lo=2, hi=4)
    c.line(6, 10, 9, 10, "paper", 3, z=4)
    c.ellipse(8, 11.2, 1.0, 0.8, "leaf", 4, lo=2, hi=3)
    c.dots([(5, 7), (5, 8)], "snow", 2)
    return c


@icon
def bokbunja_wine():
    """복분자주: a dark berry-red bottle with a black cap and a berry on the label."""
    c = _long_bottle(Canvas(), "wine", shoulder=7.2, top=1.8)
    c.cyl(6.3, 0.8, 9.7, 3.0, "soot", 2, lo=1, hi=3)
    c.cyl(4.2, 9.6, 11.8, 12.8, "cream", 3, lo=2, hi=4)
    for (x, y) in ((7, 11), (8, 10), (9, 11), (8, 11)):
        c.put(x, y, "crimson", 3, z=5)
    c.put(8, 12, "leaf", 3, z=5)
    c.dots([(5, 8), (5, 9)], "wine", 5)
    return c


@icon
def rum():
    """럼: a squat round bottle of dark amber rum with a cork and a sugar-cane label."""
    c = Canvas()
    c.ellipse(8, 10.6, 6.2, 4.4, "glass", 1, lo=1, hi=4)
    c.ellipse(8, 11.0, 5.2, 3.6, "doenjang", 1.5, lo=0, hi=3)
    c.cyl(6.6, 3.0, 9.4, 6.6, "glass", 1.2, lo=2, hi=4)
    c.rect(6.8, 1.0, 9.2, 3.2, "wood", 2, normal=(-0.3, -0.5, 1))
    c.rect(5.6, 9.8, 10.4, 12.4, "paper", 3, normal=(-0.1, 0, 1), lo=3, hi=4)
    c.line(8, 10, 8, 12, "sprout", 2, z=4).put(9, 10, "sprout", 3, z=4)
    c.dots([(3, 9), (3, 10)], "glass", 5)
    return c


@icon
def corn_whiskey():
    """옥수수 위스키: a square bottle of amber whiskey, a black cap and a corn-yellow label."""
    c = Canvas()
    c.rect(3.2, 5.6, 12.8, 15.0, "glass", 1, normal=(-0.2, -0.1, 1), lo=2, hi=4)
    c.rect(4.0, 7.0, 12.0, 14.4, "topaz", 1.5, normal=(-0.2, -0.1, 1), lo=0, hi=2)
    c.cyl(6.4, 2.6, 9.6, 5.6, "glass", 1.2, lo=2, hi=4)
    c.cyl(6.0, 0.8, 10.0, 2.8, "soot", 2, lo=1, hi=3)
    c.rect(4.6, 9.4, 11.4, 12.8, "corn", 3, normal=(-0.1, 0, 1), lo=3, hi=4)
    c.line(6, 11, 9, 11, "straw", 1, z=4)
    c.dots([(4, 7), (4, 8), (4, 9)], "glass", 5)
    return c


@icon
def potato_vodka():
    """감자 보드카: a tall clear bottle, a white label with a potato and a blue band."""
    c = _long_bottle(Canvas(), "glass", x0=4.6, x1=11.4, shoulder=6.2, neck=(6.8, 9.2), top=1.0)
    c.cyl(6.6, 0.4, 9.4, 1.8, "sapphire", 2, lo=1, hi=3)
    c.cyl(4.6, 8.8, 11.4, 12.8, "snow", 3, lo=1, hi=2)
    c.line(5, 9, 10, 9, "sapphire", 3, z=4)
    c.ellipse(8, 11.0, 1.4, 1.0, "bean", 4, lo=2, hi=4)
    c.dots([(5, 7), (5, 8)], "glass", 5)
    return c


@icon
def saengseonjeon():
    """생선전: oval slices of white fish dipped in egg and fried golden, the pale flesh showing at the cut, on a plate."""
    c = _plate(Canvas())
    for k, (x, y) in enumerate(((4.4, 9.4), (11.6, 9.2), (8.0, 6.2))):
        c.ellipse(x, y, 3.4, 1.9, "corn", 2 + k, lo=2, hi=4, dome=0.5)
        c.ellipse(x - 0.6, y - 0.3, 2.0, 0.9, "cream", 2.5 + k, lo=3, hi=5, dome=0.2)
        c.put(int(x + 2), int(y + 1), "straw", 2, z=3 + k)
    c.dots([(7, 9), (12, 6)], "pepper", 3).dots([(8, 9), (3, 7)], "leaf", 3)
    return c


@icon
def seafood_feast():
    """해물 한상: a round dark lacquer tray from above: a gold tin of caviar, slices of smoked salmon, a fishcake skewer."""
    c = Canvas()
    c.ellipse(8, 8.3, 7.5, 6.8, "wood_dark", 0, lo=1, hi=3, dome=0.25)
    c.ellipse(8, 8.1, 6.3, 5.6, "wood_dark", 0.2, lo=2, hi=2, dome=-0.2)
    c.ellipse(5.0, 5.0, 2.7, 2.2, "gold", 1, lo=2, hi=4, dome=0.3)
    c.ellipse(5.0, 5.0, 1.9, 1.4, "roe", 1.2, lo=1, hi=2, dome=0.7)
    c.dots([(4, 5), (5, 4)], "sapphire", 4)
    for k, (x, y) in enumerate(((11.0, 5.4), (11.4, 8.6))):
        c.tube([(x - 1.8, y - 0.4), (x + 1.6, y + 0.6)], [1.3, 1.3], "salmon", 1 + k * 0.1, flat=0.55, lo=2, hi=4)
        c.line(int(x - 1), int(y), int(x + 1), int(y), "cream", 4, z=2 + k)
    f = {"A": ("straw", 5), "B": ("straw", 4), "C": ("straw", 2), "D": ("doenjang", 3), "s": ("wood", 3)}
    grid(c, ["ABBBC.", ".DDD..", "ABBBC.", ".DDD.."], f, z=3, x0=3, y0=9)
    c.dots([(5, 13), (5, 14)], "wood", 3, z=3)
    c.dots([(9, 12), (10, 12)], "leaf", 3)
    return c


# the goods redrawn by hand one by one (goods_art.py) replace the template drawings above
import goods_art as _GA   # noqa: E402

ICONS.update(_GA.ICONS)
# special materials and the goods made from them (goods_special.py)
import goods_special as _GS   # noqa: E402

ICONS.update(_GS.ICONS)


# ================================================================================================
# Names and the preview sheet (docs/previews/goods.png)
# ================================================================================================

GROUPS = [
    ("발견품 (광석·낚시)", {"rough_ruby": "루비 원석", "rough_sapphire": "사파이어 원석", "red_seabream": "참돔", "pearl": "진주",
                     "bluefin_tuna": "참다랑어", "sturgeon": "철갑상어"}),
    ("세공대 · 광부", {"ruby": "루비", "sapphire": "사파이어", "coke": "코크스", "copper_plate": "구리판", "ruby_ring": "루비 반지",
                  "pearl_necklace": "진주 목걸이", "jeweled_crown": "보석 왕관"}),
    ("가공대 · 농부", {"rice": "쌀", "flour": "밀가루", "tofu": "두부", "chili_powder": "고춧가루", "corn_flour": "옥수수 가루",
                  "grape_juice": "포도즙", "dried_zucchini": "호박고지", "tomato_sauce": "토마토 소스", "doenjang": "된장",
                  "gochujang": "고추장", "jang_set": "전통 장 세트"}),
    ("손질대 · 어부", {"cod_fillet": "대구 필렛", "salmon_fillet": "연어 필렛", "dried_pollack": "북어", "prepared_puffer": "손질한 복어",
                  "smoked_salmon": "훈제 연어", "seabream_sashimi": "참돔 회", "tuna_sashimi": "참치 회",
                  "polished_pearl": "연마 진주", "fishcake": "어묵", "caviar": "캐비아", "seafood_feast": "해물 한상"}),
    ("대장간 · 대장장이", {"iron_plate": "철판", "iron_chain": "쇠사슬", "gold_leaf": "금박", "steel_ingot": "강철괴",
                    "oak_barrel": "참나무통", "steel_toolset": "강철 공구 세트", "ornate_armor": "장식용 갑옷"}),
    ("조리대 · 요리사", {"kimchi": "김치", "kkakdugi": "깍두기", "oi_sobagi": "오이소박이", "hobakjeon": "호박전",
                   "eggplant_stirfry": "가지볶음", "sundubu_jjigae": "순두부찌개", "tuna_sushi": "참치 초밥",
                   "strawberry_cake": "딸기 케이크", "tomato_pasta": "토마토 파스타", "corn_bread": "옥수수빵", "bibimbap": "비빔밥",
                   "grilled_fish_set": "생선구이 정식", "hanjeongsik": "한정식", "saengseonjeon": "생선전"}),
    ("증류기 · 연금술사", {"makgeolli": "막걸리", "soju": "소주", "melon_syrup": "멜론 시럽", "wine": "포도주",
                    "melon_liqueur": "멜론 리큐어", "pearl_perfume": "진주 향수", "vintage_wine": "빈티지 포도주"}),
    ("추가 · 세공대", {"cut_emerald": "세공 에메랄드", "lapis_beads": "청금석 구슬", "gold_ring": "금반지", "cut_diamond": "세공 다이아몬드",
                   "sapphire_brooch": "사파이어 브로치", "diamond_ring": "다이아 반지"}),
    ("추가 · 가공대", {"potato_starch": "감자 전분", "brown_sugar": "흑설탕", "apple_juice": "사과즙", "jocheong": "조청", "soy_sauce": "간장"}),
    ("추가 · 손질대", {"shrimp_meat": "새우살", "gwamegi": "과메기", "gulbi": "굴비", "hongeo": "삭힌 홍어", "jeotgal": "젓갈"}),
    ("추가 · 대장간", {"iron_nails": "쇠못", "copper_pot": "구리 냄비", "gamasot": "가마솥", "iron_lantern": "철 랜턴",
                   "ornamental_sword": "장식용 도검"}),
    ("추가 · 조리대", {"gyeranmari": "계란말이", "hotteok": "호떡", "gamjajeon": "감자전", "bulgogi": "불고기", "jeyuk": "제육볶음",
                   "samgyetang": "삼계탕", "doenjang_jjigae": "된장찌개", "kimchi_jjigae": "김치찌개", "tteokbokki": "떡볶이"}),
    ("추가 · 증류기", {"flower_tea": "꽃잎 차", "cider": "사과주", "strawberry_wine": "딸기주", "mead": "꿀술", "cheongju": "청주"}),
    ("원재료만 · 세공대", {"copper_bracelet": "구리 팔찌", "amethyst_pendant": "자수정 펜던트", "quartz_figurine": "석영 조각상"}),
    ("원재료만 · 가공대", {"gangnaengi": "강냉이", "onion_juice": "양파즙", "mumallaengi": "무말랭이"}),
    ("원재료만 · 손질대", {"salted_mackerel": "간고등어", "fish_sauce": "까나리 액젓"}),
    ("원재료만 · 대장간", {"homi": "호미", "sickle": "낫", "wind_chime": "풍경"}),
    ("원재료만 · 조리대", {"godeungeo_gui": "고등어구이", "oi_muchim": "오이무침", "beoseot_bokkeum": "버섯볶음", "gamja_jorim": "감자조림",
                     "pajeon": "파전", "miyeokguk": "미역국", "kalguksu": "칼국수", "injeolmi": "인절미", "dakbokkeumtang": "닭볶음탕",
                     "gamjatang": "감자탕"}),
    ("원재료만 · 증류기", {"pumpkin_makgeolli": "단호박 막걸리", "bokbunja_wine": "복분자주", "rum": "럼", "corn_whiskey": "옥수수 위스키",
                     "potato_vodka": "감자 보드카"}),
    ("압착기 · 공용", {"seed_oil": "씨앗 기름", "compost": "퇴비", "compressed_stone": "압축 석재", "compressed_dirt": "압축 흙",
                   "refined_oil": "정제유", "soap": "비누", "fertilizer": "비료", "scented_candle": "향초", "stone_lantern": "석등"}),
    ("특별 재료 (농부·어부·광부가 자주 얻음)", {k: _GS.NAMES[k] for k in ("wild_ginseng", "golden_ear", "silk_cocoon", "red_coral",
                                                     "ambergris", "abalone", "meteoric_iron", "rough_crystal")}),
    ("특별 재료·도매 생선 레시피", {k: v for k, v in _GS.NAMES.items() if k not in ("wild_ginseng", "golden_ear", "silk_cocoon", "red_coral",
                                                                         "ambergris", "abalone", "meteoric_iron", "rough_crystal")}),
]
NAMES = {k: v for _, g in GROUPS for k, v in g.items()}


def sheet(path, scale=6, cols=7):
    from PIL import Image, ImageDraw, ImageFont
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 15)
        head = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 19)
    except OSError:
        font = head = ImageFont.load_default()
    cell = 16 * scale
    cw, ch = cell + 34, cell + 30
    rows = sum(1 + (len(g) + cols - 1) // cols for _, g in GROUPS)
    heads = len(GROUPS)
    im = Image.new("RGBA", (cols * cw + 20, rows * ch - heads * (ch - 34) + 20), (46, 48, 56, 255))
    d = ImageDraw.Draw(im)
    y = 10
    for title, items in GROUPS:
        d.text((14, y + 6), title, fill=(255, 226, 150, 255), font=head)
        y += 34
        for k, (item, name) in enumerate(items.items()):
            x = 14 + (k % cols) * cw
            yy = y + (k // cols) * ch
            d.rectangle([x, yy, x + cell, yy + cell], fill=(139, 139, 139, 255))
            ic = ICONS[item]()
            im.alpha_composite(ic.resize((cell, cell), Image.NEAREST), (x, yy))
            d.rectangle([x + cell + 2, yy + cell - 33, x + cell + 33, yy + cell - 1], fill=(139, 139, 139, 255))
            im.alpha_composite(ic.resize((32, 32), Image.NEAREST), (x + cell + 2, yy + cell - 33))
            d.text((x, yy + cell + 4), name, fill=(240, 240, 240, 255), font=font)
        y += ((len(items) + cols - 1) // cols) * ch
    im.crop((0, 0, im.width, y + 10)).save(path)
