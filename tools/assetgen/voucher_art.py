"""Voucher and cheque icons: one paper ticket per voucher with a picture of what it gives. The stub colour
is the rarity (stone common, sapphire rare, crystal epic, gold legendary); legendary tickets are dark with
gold perforation so white and glowing pictures stand out.
"""
import math
from stsv_assets import icons


# ------------------------------------------------------------------------------------------------
# vouchers and cheque
# ------------------------------------------------------------------------------------------------


_CORE_LIGHT = (-0.52, -0.62, 0.59)          # towards the light: top-left, a bit in front


def _core_paint(ic, rows, legend, x0=0, y0=0):
    """Paste a character picture; '.' keeps what is below, '_' erases."""
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch == ".":
                continue
            p = (x0 + dx, y0 + dy)
            if ch == "_":
                ic.px.pop(p, None)
                continue
            r, i = legend[ch]
            ic.put(p[0], p[1], r, i)


def _core_ball(ic, cx, cy, rx, ry, ramp, levels, edge=0, edge_ramp=None, skip=()):
    """A shaded ellipse (sphere-lit from the top-left) with an inner outline. levels: [(min brightness, step)]"""
    pts = set()
    for y in range(16):
        for x in range(16):
            dx, dy = (x + 0.5 - cx) / rx, (y + 0.5 - cy) / ry
            if dx * dx + dy * dy <= 1.0:
                pts.add((x, y))
    for (x, y) in pts:
        if (x, y) in skip:
            continue
        if any((x + a, y + b) not in pts for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            ic.put(x, y, edge_ramp or ramp, edge)
            continue
        nx, ny = (x + 0.5 - cx) / rx, (y + 0.5 - cy) / ry
        nz = math.sqrt(max(0.0, 1 - nx * nx - ny * ny))
        b = nx * _CORE_LIGHT[0] + ny * _CORE_LIGHT[1] + nz * _CORE_LIGHT[2]
        for t, step in levels:
            if b >= t:
                ic.put(x, y, ramp, step)
                break
    return pts


def _core_ticket(stub, dark=False):
    """The shared ticket body: x1-14, rows 4-13, notched at rows 8-9; stub x1-2 behind a perforation at x3.
    Its outline comes from _core_finish (paper gets a brown edge instead of its own pale step 0)."""
    ic = icons.Icon()
    body, lit, mid, shade, perf = ("obsidian", 3, 2, 1, ("gold", 1)) if dark else ("paper", 2, 2, 1, ("paper", 0))
    top = {"gold": 3, "stone": 3, "sapphire": 3, "crystal": 4}[stub]
    for y in range(4, 14):
        for x in range(1, 15):
            if x in (1, 14) and y in (8, 9):
                continue                                # side notches
            if x in (1, 2):
                ic.put(x, y, stub, max(1, top - (x - 1) - (1 if y >= 11 else 0)))
            elif x == 3:
                ic.put(x, y, *(perf if y % 2 == 0 else (body, mid)))
            else:
                ic.put(x, y, body, lit if y == 4 else shade if (y == 13 or x == 14) else mid)
    return ic


def _core_finish(ic):
    """Outline like the toolkit (darkest step of the part's ramp), but only around pixels that are not already
    an edge (step 0), so hand-drawn edges are not doubled; paper gets a brown edge."""
    edge = {"paper": ("burlap", 0), "feather": ("stone", 1), "snow": ("stone", 1), "cream": ("wood", 0)}
    filled = dict(ic.px)
    for (x, y), (r, i) in filled.items():
        if i == 0:
            continue
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, y + dy)
            if 0 <= n[0] < 16 and 0 <= n[1] < 16 and n not in ic.px:
                ic.px[n] = edge.get(r, (r, 0))
    return ic.image()


def icon_tomato_voucher():
    ic = _core_ticket("stone")
    _core_ball(ic, 9.5, 9.0, 4.6, 4.1, "tomato", [(0.80, 4), (0.45, 3), (-0.05, 2), (-9, 1)])
    _core_paint(ic, [
        "....k....",
        "...kGk...",
        ".kkkgkkk.",
        "kGGgGgGGk",
        ".kg.k.gk.",
        "..k...k..",
    ], {"k": ("leaf_dark", 0), "g": ("leaf", 2), "G": ("leaf", 4)}, 5, 2)
    ic.put(7, 7, "pink", 3)
    return _core_finish(ic)


def icon_eggplant_voucher():
    ic = _core_ticket("sapphire")
    # body: a capsule from the cap (top-right) to the fat end (bottom-left)
    ax, ay, bx, by = 11.2, 6.2, 7.6, 10.2
    ra, rb = 1.9, 3.1
    pts, info = set(), {}
    for y in range(16):
        for x in range(16):
            px, py = x + 0.5, y + 0.5
            vx, vy = bx - ax, by - ay
            t = max(0.0, min(1.0, ((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy)))
            qx, qy = ax + vx * t, ay + vy * t
            r = ra + (rb - ra) * t
            d = math.hypot(px - qx, py - qy)
            if d <= r:
                pts.add((x, y))
                info[(x, y)] = ((px - qx) / r, (py - qy) / r)
    for (x, y) in pts:
        if any((x + a, y + b) not in pts for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            ic.put(x, y, "aubergine", 0)
            continue
        nx, ny = info[(x, y)]
        nz = math.sqrt(max(0.0, 1 - nx * nx - ny * ny))
        b = nx * _CORE_LIGHT[0] + ny * _CORE_LIGHT[1] + nz * _CORE_LIGHT[2]
        ic.put(x, y, "aubergine", 4 if b > 0.85 else 3 if b > 0.55 else 2 if b > 0.1 else 1)
    _core_paint(ic, [
        "......k..",
        ".....kGk.",
        "..kkkgk..",
        ".kGGgGgk.",
        "..kgGgGk.",
        "...kkgk..",
        ".....k...",
    ], {"k": ("leaf_dark", 0), "g": ("leaf", 2), "G": ("leaf", 4)}, 8, 1)
    ic.put(7, 9, "magic", 3)
    return _core_finish(ic)


def icon_mushroom_voucher():
    ic = _core_ticket("crystal")
    _core_paint(ic, [
        "...ooo...",
        ".oo4W3oo.",
        "o3WW3W21o",
        "o3W3222Wo",
        "o2222Ww1o",
        ".occcccoo",
        "..kCBbk..",
        "..kCBbk..",
        "..kCBbk..",
        "...kkk...",
    ], {"o": ("toadstool", 0), "1": ("toadstool", 1), "2": ("toadstool", 2), "3": ("toadstool", 3),
        "4": ("toadstool", 4), "W": ("feather", 3), "w": ("feather", 2), "c": ("cream", 1), "C": ("cream", 3),
        "B": ("cream", 2), "b": ("cream", 1), "k": ("wood", 0)}, 5, 3)
    return _core_finish(ic)


_CORE_GOLD = {"o": ("gold", 0), "1": ("gold", 1), "2": ("gold", 2), "3": ("gold", 3)}


def icon_crown_voucher():
    ic = _core_ticket("gold", dark=True)
    _core_paint(ic, [
        "3...3...3",
        "2o.o3o.o2",
        "32o323o21",
        "332332221",
        "322222221",
        "ooooooooo",
        "3R23S32R1",
        "2r12s12r1",
        "ooooooooo",
    ], dict(_CORE_GOLD, R=("ruby", 3), r=("ruby", 1), S=("sapphire", 3), s=("sapphire", 1)), 5, 3)
    return _core_finish(ic)


def icon_halo_voucher():
    ic = _core_ticket("gold", dark=True)
    lamp = {"1": ("lamp", 1), "2": ("lamp", 2), "3": ("lamp", 3)}
    _core_paint(ic, [                              # a glowing ring seen from a little above
        "..122221..",
        ".23211122.",
        "1321..1221",
        "131....121",
        ".23322221.",
        "..133331..",
    ], lamp, 5, 6)
    _core_paint(ic, [".1.", "131", ".1."], lamp, 12, 2)   # twinkle
    return _core_finish(ic)


def _core_pair(ic, rows, legend, y0):
    """a left wing and its mirror image, either side of the ticket's middle column (x9)"""
    _core_paint(ic, rows, legend, 4, y0)
    _core_paint(ic, [r[::-1] for r in rows], legend, 15 - len(rows[0]), y0)


def icon_demon_wings_voucher():
    ic = _core_ticket("gold", dark=True)
    _core_pair(ic, [
        "b....",
        "bb...",
        "bMb..",
        "bMMbb",
        "bMmMM",
        "bmbmM",
        "bmbmm",
        "b.bmb",
        "...b.",
    ], {"b": ("crimson", 0), "m": ("ruby", 1), "M": ("ruby", 2)}, 3)
    return _core_finish(ic)


def icon_haetae_voucher():
    ic = _core_ticket("gold", dark=True)
    _core_paint(ic, [
        ".....3.....",
        "....g2g....",
        "..gGgogGg..",
        ".gGcCCCcGg.",
        "gGcCCCCCcGg",
        "gGcecCcecGg",
        "ggcCCnCCcgg",
        ".gcCwwwCcg.",
        "..gccccccg.",
        "...gg3gg...",
    ], {"g": ("bottle_green", 1), "G": ("bottle_green", 3), "c": ("whetstone", 3), "C": ("whetstone", 4),
        "e": ("iron", 0), "n": ("wood", 0), "w": ("feather", 3), "o": ("gold", 0), "2": ("gold", 2),
        "3": ("gold", 3)}, 4, 2)
    return _core_finish(ic)


def icon_cheque():
    ic = icons.Icon()
    for y in range(4, 13):
        for x in range(1, 15):
            ic.put(x, y, "paper", 1 if (y == 12 or x == 14) else 2)
    for x in range(1, 15):
        ic.put(x, 4, "gold", 3 if x < 8 else 2)
        ic.put(x, 5, "gold", 1)
    for x in range(3, 9):
        ic.put(x, 7, "paper", 0)
    _core_paint(ic, ["..s....", ".s.s.s.", "s...s.s"], {"s": ("denim", 1)}, 3, 9)
    _core_paint(ic, [
        ".rr.",
        "rRrr",
        "rrr1",
        ".11.",
    ], {"r": ("ruby", 2), "R": ("ruby", 3), "1": ("ruby", 1)}, 10, 8)
    return _core_finish(ic)


# ------------------------------------------------------------------------------------------------
# core (round 2)
# ------------------------------------------------------------------------------------------------


"""
Core batch, round 2 (attempt 1): angel wings, gumiho and phoenix vouchers on the shared legendary ticket.

- angel_wings_voucher: the demon-wings footprint (x4-14, mirrored around x9, tips at the top corners) in white
  feathers: arched top rising from the centre, three stepped feather rows split by grey lines, pointed tips
  along the lower edge. Everything outside the ticket gets the ticket's dark-navy edge, so x15 stays navy.
- gumiho_voucher: a white fox head with tall pink-lined ears above the ticket, a V face narrowing to the nose,
  sly slanted eyes and the red forehead mark; its tails (spirit blue, fox-fire tips) fan out behind and below
  the head on both sides, one curling up and one sweeping down each side. Perforation and frame untouched.
- phoenix_voucher: a right-facing 봉황: raised wing in three stepped rows (red coverts, orange, yellow flame
  tips), gold crest swept back, gold beak and breast, and two wide flame plumes curling down-left.
"""


def _r2core1_frame(ic):
    """put the bare legendary ticket's own outline back wherever the picture left it empty, so the frame
    (navy body edge, gold stub corner) stays exactly as on the other vouchers"""
    t = _core_ticket("gold", dark=True)
    for (x, y), (r, i) in t.px.items():
        if i == 0:
            continue
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, y + dy)
            if 0 <= n[0] < 16 and 0 <= n[1] < 16 and n not in t.px and n not in ic.px:
                ic.px[n] = (r, 0)


def _r2core1_edge(ic, ramps, edge, pts=None):
    """outline the given ramps (or the given pixels) with one colour, before _core_finish, so the picture's
    edge matches the ticket's dark edge instead of a pale step of its own ramp"""
    _r2core1_frame(ic)
    filled = dict(ic.px)
    for (x, y), (r, i) in filled.items():
        if (r not in ramps) if pts is None else ((x, y) not in pts):
            continue
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, y + dy)
            if 0 <= n[0] < 16 and 0 <= n[1] < 16 and n not in ic.px:
                ic.px[n] = edge


_r2core1_feather = {"W": ("feather", 3), "w": ("feather", 2), "g": ("feather", 1), "S": ("stone", 3)}


def icon_angel_wings_voucher():
    ic = _core_ticket("gold", dark=True)
    rows = [                   # left wing x4-8 from y2; the right wing is its mirror image (x10-14)
        "W....",               # tip at the top corner
        "WWW..",               # arched top: rises from the centre (x8 at y6) to the tip
        "WWWW.",
        "SWWW.",               # first grey line: coverts / secondaries
        "wSSWW",
        "wwwSW",
        "SwwwS",               # second grey line: secondaries / primaries
        "gSSwg",
        "gggSg",
        "g.gg.",               # pointed feather tips along the lower edge
        "..g..",
    ]
    _core_paint(ic, rows, _r2core1_feather, 4, 2)
    _core_paint(ic, [r[::-1] for r in rows], _r2core1_feather, 10, 2)
    _r2core1_edge(ic, ("feather", "stone"), ("obsidian", 0))
    return _core_finish(ic)


# ------------------------------------------------------------------------------------------------
# r3_core_a (round 3)
# ------------------------------------------------------------------------------------------------


"""
Core batch, round 3 (artist a): gumiho and phoenix vouchers on the shared legendary ticket
(voucher_art._core_ticket / _core_paint / _core_finish, so the stub, gold perforation and dark card stay
exactly as on crown, halo, wings and haetae).

- gumiho_voucher (구미호 꼬리 교환권): an orange fox sitting in profile, facing left (pointed ear with a dark
  inner, slanted eye, long snout with a black nose, white cheek, chest and front paws), and behind it a fan of
  five spectral-cyan fox-fire tails spreading from its back to the upper right: one over its head, one straight
  up, one to the top-right corner, two to the right edge. Each tail is about 2 px wide, darker teal at the root,
  cyan in the middle and a white fox-fire tip, split from the next by obsidian gaps that reach the outer edge.
  Nothing bright on x4, plain ticket in both lower corners, no bow-tie/butterfly symmetry, and the profile snout
  keeps it from reading as a cat.
- phoenix_voucher (불사조 날개 교환권): a red 봉황 flying diagonally up to the right: 3x3 head at x11-13 y3-5
  with a dark eye at (12,4), a gold beak at (14,4) and a short gold crest, a slim red body lit from the top
  left with a gold breast, one wing raised from the back as three pointed feathers (red root, orange, yellow
  tips) split by crimson lines, and a straight tail plume running down-left to a solid 2x2 yellow tip.
  The crest and the wing tips are separated by open background, nothing curls, x4 stays navy.
"""


def _r3corea_paint(rows, legend, edge):
    """Paint full-width rows over the legendary ticket. '.' keeps the ticket (or stays empty above it); the stub
    and perforation (x0-3, rows 3+) are never touched. Every drawn pixel that borders empty space gets a dark
    outline in `edge` (obsidian for the fox, crimson for the bird) before the ticket's own outline is added."""
    ic = _core_ticket("gold", dark=True)
    _core_paint(ic, ["".join(ch if (x >= 4 or y < 3) else "." for x, ch in enumerate(r))
                     for y, r in enumerate(rows)], legend, 0, 0)
    for (x, y), (r, i) in list(ic.px.items()):
        if i == 0 or x <= 3 or r == "obsidian":
            continue
        for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + a, y + b)
            if 0 <= n[0] < 16 and 0 <= n[1] < 16 and n not in ic.px:
                ic.px[n] = edge
    return _core_finish(ic)


_r3corea_bird = {"k": ("crimson", 0), "Q": ("pepper", 0), "q": ("pepper", 1), "r": ("pepper", 2),
                 "R": ("pepper", 3), "H": ("pepper", 4), "O": ("fox", 3), "d": ("fox", 2),
                 "Y": ("fire", 4), "P": ("gold", 3), "G": ("gold", 2), "e": ("iron", 0)}


def icon_phoenix_voucher():
    return _r3corea_paint([
        #0123456789012345
        "................",   # 0
        ".......Y....P...",   # 1  top feather tip | crest tip
        ".....YkYO..GG...",   # 2  second feather tip | gold crest
        ".....YOkOOkRHRk.",   # 3  head
        ".....kOOkdkReHG.",   # 4  eye, gold beak
        ".....YkddRRrrqk.",   # 5  third feather tip; neck
        ".....OOkRRRrrG..",   # 6  body lit from the top left, gold breast
        "......kRRrrqqG..",   # 7
        "......kRrqqQk...",   # 8
        "......Orqqk.....",   # 9  tail plume runs down-left
        ".....OrQk.......",   # 10
        ".....Ork........",   # 11
        ".....YY.........",   # 12 solid 2x2 yellow tip
        ".....YY.........",   # 13
    ], _r3corea_bird, ("crimson", 0))


ICONS = {
    "cheque": icon_cheque,
    "crown_voucher": icon_crown_voucher,
    "demon_wings_voucher": icon_demon_wings_voucher,
    "eggplant_voucher": icon_eggplant_voucher,
    "haetae_voucher": icon_haetae_voucher,
    "halo_voucher": icon_halo_voucher,
    "mushroom_voucher": icon_mushroom_voucher,
    "tomato_voucher": icon_tomato_voucher,
    "angel_wings_voucher": icon_angel_wings_voucher,
    "phoenix_voucher": icon_phoenix_voucher,
}
