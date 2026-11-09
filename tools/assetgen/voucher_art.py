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


# ------------------------------------------------------------------------------------------------
# every cosmetic voucher: one layout, made from the cosmetic itself
# ------------------------------------------------------------------------------------------------
#
# The ticket of the cosmetic's rarity (stub stone common, sapphire rare, crystal epic; legendary: the dark card
# with the gold stub and perforation) with a small picture of the cosmetic peeking over its top edge:
#   - a cosmetic with a model (content/stsv/models/<model>.bbmodel): the model as the menu shows it (its own gui
#     view, stsv_assets/minirender.py), fitted into PEEK x PEEK, snapped to the palette, dark outline
#   - a particle cosmetic (발자취, 처치 효과): the particle's glyph (_P, by the cosmetic's particle); a 발자취
#     trails two small steps of its colour down to the left, a 처치 효과 bursts four sparks around it
# so a new cosmetic gets a matching voucher with no drawing (a voucher's texture is the voucher's own id).

from pathlib import Path   # noqa: E402

import yaml                 # noqa: E402
from PIL import Image       # noqa: E402

from stsv_assets import minirender   # noqa: E402
from stsv_assets.palette import PALETTE   # noqa: E402

CONTENT = Path(__file__).resolve().parents[2] / "content" / "stsv"
STUB = {"common": "stone", "rare": "sapphire", "epic": "crystal", "legendary": "gold"}
PEEK = 11                     # the picture's box (without its outline)
PEEK_AT = (3, 0)              # its outline's top-left corner: over the ticket's right part, out of its top edge
FLAT = True                   # faces in flat colours: a model's texture speckle is noise at this size
MIN_LUMA = 62                 # black cosmetics (gat, cat ears) lifted to charcoal so the dark outline still shows

# particle glyphs (7 x 7 at most): legend letter -> (ramp, step); the first letter is the glyph's main colour
_P = {
    "heart": ([".RR.RR.", "RHrRRrr", "RrrrrrQ", ".rrrrQ.", "..rrQ..", "...Q..."],
              {"r": ("pink", 2), "R": ("pink", 3), "H": ("feather", 3), "Q": ("ruby", 1)}),
    "note": (["...GGG.", "...GgG.", "...G...", "...G...", ".ggG...", "gGGg...", ".gg...."],
             {"G": ("slime", 3), "g": ("slime", 1)}),
    "cherry_leaves": (["...SS..", "..SsS..", ".SssS.S", "SssSSsS", ".SSsssS", "..SssS.", "...S..."],
                      {"S": ("sakura", 2), "s": ("sakura", 0)}),
    "flame": (["...Y...", "..YY...", "..YOY..", ".YOOY..", ".OoOOY.", "OooFoO.", ".OFFFO."],
              {"O": ("fire", 3), "Y": ("fire", 4), "o": ("fire", 2), "F": ("lamp", 3)}),
    "small_flame": (["..Y..", ".YO..", ".OoY.", "OoFO.", ".FFO."],
                    {"O": ("fire", 3), "Y": ("fire", 4), "o": ("fire", 2), "F": ("lamp", 3)}),
    "soul_fire_flame": (["...C...", "..CC...", "..CsC..", ".CssC..", ".sScsC.", "sSSWSs.", ".sWWWs."],
                        {"C": ("glow_cyan", 2), "s": ("glow_cyan", 1), "S": ("glow_cyan", 0), "c": ("glow_cyan", 3),
                         "W": ("feather", 3)}),
    "snowflake": (["...I...", ".I.I.I.", "..III..", "IIIWIII", "..III..", ".I.I.I.", "...I..."],
                  {"I": ("ice", 2), "W": ("feather", 3)}),
    "firework": (["Y..Y..Y", ".Y.Y.Y.", "..WWW..", "YYWWWYY", "..WWW..", ".Y.Y.Y.", "Y..Y..Y"],
                 {"Y": ("gold", 3), "W": ("feather", 3)}),
    "electric_spark": (["....LL.", "...LL..", "..LLL..", ".LLLLL.", "...LL..", "..LL...", ".LL...."],
                       {"L": ("lamp", 3)}),
    "end_rod": (["...W...", "...W...", ".WWWWW.", "...W...", "...W..."], {"W": ("feather", 3)}),
}
# dust: dots in the cosmetic's colour (rainbow: one of each)
_RAINBOW = [("ruby", 3), ("fire", 3), ("lamp", 3), ("slime", 3), ("sapphire", 3), ("magic", 3)]


def items():
    """Every item of the content's items/*.yml: id -> section."""
    out = {}
    for f in sorted((CONTENT / "items").glob("*.yml")):
        data = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        out.update({k: v for k, v in data.items() if isinstance(v, dict)})
    return out


def vouchers():
    """voucher id -> (cosmetic id, the cosmetic's section)"""
    every = items()
    out = {}
    for key, sec in every.items():
        target = (sec.get("voucher") or {}).get("cosmetic")
        if target:
            target = target.split(":", 1)[-1]
            out[key] = (target, every[target])
    return out


_SNAP = [c for name, steps in PALETTE.items() if not name.startswith("test_") for c in steps]
_EDGE = PALETTE["iron"][0] + (255,)


def _snap(c):
    return min(_SNAP, key=lambda p: (p[0] - c[0]) ** 2 + (p[1] - c[1]) ** 2 + (p[2] - c[2]) ** 2)


def model_peek(model_name, size=PEEK, flat=FLAT):
    """The cosmetic's model in a size x size box, palette colours only, with a dark outline ((size + 2) square)."""
    im = minirender.render(minirender.load(CONTENT / "models" / f"{model_name}.bbmodel"), size, fit=True, flat=flat)
    px = im.load()
    for y in range(size):
        for x in range(size):
            if px[x, y][3]:
                c = px[x, y][:3]
                lift = max(0, MIN_LUMA - (0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]))
                px[x, y] = _snap(tuple(min(255, int(v + lift)) for v in c)) + (255,)
    pad = Image.new("RGBA", (size + 2, size + 2), (0, 0, 0, 0))
    pad.alpha_composite(im, (1, 1))
    return minirender.outlined(pad, _EDGE)


BADGE = (10.0, 7.0, 6.0)      # the particle badge: centre x, y and radius (x 4-15, y 1-12, like a model's picture)


def _particle_picture(ic, cosmetic):
    """A 발자취 or 처치 효과: its particle on a round night-sky badge (pale particles read on it, and it fills
    the same place as a model's picture). 처치 효과: the glyph in the middle with four sparks bursting out;
    발자취: the glyph up to the right and two small steps of its colour trailing down to the left."""
    cx, cy, r = BADGE
    disc = {(x, y) for y in range(16) for x in range(16) if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r}
    for (x, y) in disc:
        rim = any((x + a, y + b) not in disc for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        ic.put(x, y, "iron", 0 if rim else 1 if y > cy else 2)
    particle = cosmetic.get("particle", "")
    kill = cosmetic.get("slot") == "kill"
    if particle == "dust":
        dots = _RAINBOW if cosmetic.get("color") == "rainbow" else [("feather", 3)] * 6
        spots = [(7, 5), (10, 3), (13, 5), (13, 9), (10, 10), (7, 9)] if kill else [(12, 3), (9, 4), (12, 6), (8, 7), (10, 9), (6, 10)]
        for (x, y), c in zip(spots, dots):
            for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
                ic.put(x + dx, y + dy, *c)
        return
    name = particle if particle in _P else "end_rod"
    rows, legend = _P[name]
    w, h = max(len(row) for row in rows), len(rows)
    main = next(iter(legend.values()))
    if kill:
        gx, gy = int(cx) - w // 2, int(cy) - h // 2
        _core_paint(ic, rows, legend, gx, gy)
        spark = ("lamp", 3) if name in ("flame", "small_flame", "firework", "electric_spark") else ("feather", 3)
        for x, y in ((gx - 1, gy - 1), (gx + w, gy - 1), (gx - 1, gy + h), (gx + w, gy + h)):
            ic.put(x, y, *spark)
    else:
        _core_paint(ic, rows, legend, int(cx) - w // 2 + 1, int(cy) - h // 2 - 1)
        for x, y in ((5, 8), (7, 10)):
            for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
                ic.put(x + dx, y + dy, *main)


# pictures drawn by hand on the same ticket (same stub, perforation and picture place): they win over the
# generated picture of their cosmetic (the masks and most legendary ones, drawn before the generator existed)
HAND_DRAWN = {
    "tomato_voucher": icon_tomato_voucher,
    "eggplant_voucher": icon_eggplant_voucher,
    "mushroom_voucher": icon_mushroom_voucher,
    "crown_voucher": icon_crown_voucher,
    "halo_voucher": icon_halo_voucher,
    "angel_wings_voucher": icon_angel_wings_voucher,
    "demon_wings_voucher": icon_demon_wings_voucher,
    "haetae_voucher": icon_haetae_voucher,
    "phoenix_voucher": icon_phoenix_voucher,
}


def voucher(cosmetic):
    """The generated voucher icon of a cosmetic (its section in cosmetics.yml)."""
    c = cosmetic.get("cosmetic") or {}
    rarity = c.get("rarity", "common")
    ic = _core_ticket(STUB[rarity], dark=rarity == "legendary")
    if cosmetic.get("model"):
        im = _core_finish(ic)
        im.alpha_composite(model_peek(cosmetic["model"]), PEEK_AT)
        return im
    _particle_picture(ic, c)
    return _core_finish(ic)


def voucher_icons():
    """texture name (= the voucher's id) -> generator, for every voucher in the content"""
    return {key: HAND_DRAWN.get(key, lambda sec=sec: voucher(sec)) for key, (_, sec) in vouchers().items()}


ICONS = {"cheque": icon_cheque}
ICONS.update(voucher_icons())

SLOT_KO = {"head": "머리", "above": "머리 위", "shoulder": "어깨", "back": "등", "tail": "꼬리", "pet": "펫",
           "trail": "발자취", "kill": "처치 효과"}
_SLOTS = list(SLOT_KO)
_RARITIES = list(STUB)


def contact_sheet(path, font_path="/usr/share/fonts/opentype/unifont/unifont.otf"):
    """docs/previews/vouchers.png: every voucher at 16 px (as in a slot) and at 4x, by category then rarity."""
    import re
    from PIL import ImageDraw, ImageFont
    every = sorted(vouchers().items(), key=lambda kv: (_SLOTS.index(kv[1][1]["cosmetic"]["slot"]),
                                                       _RARITIES.index(kv[1][1]["cosmetic"]["rarity"]), kv[0]))
    cols, cell_w, cell_h = 8, 120, 100
    rows = (len(every) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cell_w + 16, rows * cell_h + 16), (32, 34, 40, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype(font_path, 16)
    except OSError:
        font = ImageFont.load_default()
    for i, (key, (_, sec)) in enumerate(every):
        x, y = 8 + (i % cols) * cell_w, 8 + (i // cols) * cell_h
        im = ICONS[key]()
        sheet.alpha_composite(im.resize((64, 64), Image.NEAREST), (x + 2, y + 2))
        sheet.alpha_composite(im, (x + 72, y + 26))
        name = re.sub(r"<[^>]+>", "", sec.get("name", key))
        c = sec["cosmetic"]
        draw.text((x + 2, y + 66), name[:7], font=font, fill=(236, 232, 222))
        draw.text((x + 2, y + 82), SLOT_KO[c["slot"]], font=font, fill=(150, 156, 170))
    sheet.save(path)
