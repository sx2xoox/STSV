"""Voucher icons of cosmetics batch C (items/cosmetics_batch_c.yml): the waist pieces use the generated ticket
with a picture of their model (voucher_art.voucher); the trails, kill effects and auras draw their particle on
the same night-sky badge as voucher_art, with glyphs for the particles voucher_art has none of. An aura shows
its glyph with a ring of dots turning round it (the front half of the ring over the glyph).

ICONS: voucher id -> generator (each returns the 16 x 16 icon).
"""
import math

import voucher_art as VA
from voucher_art import _core_finish, _core_paint, _core_ticket, BADGE, STUB, _RAINBOW, _P

# glyphs (7 x 7 at most) for the particles voucher_art.py has none of; the first legend letter is the main colour
GLYPHS = {
    "bubble_pop": ([".....b.", "....b.b", ".....b.", ".BB....", "Bw.B..b", "B..B...", ".BB...."],
                   {"B": ("ice", 2), "w": ("feather", 3), "b": ("ice", 1)}),
    "wax_off": (["...W...", "...W...", ".WWSWW.", "...W...", "...W.w.", "w.....w", ".w...w."],
                {"W": ("feather", 3), "S": ("lamp", 3), "w": ("feather", 1)}),
    "enchant": (["MM.m...", "M..m.M.", "MM.mm..", "...m.MM", "m.....M", "mm.MM..", "m..M..."],
                {"M": ("magic", 3), "m": ("aurora", 3)}),
    "pale_oak_leaves": (["...LL..", "..LlL..", ".LllL.L", "LllLLlL", ".LLlllL", "..LllL.", "...L..."],
                        {"L": ("feather", 1), "l": ("pearl", 0)}),
    "totem_of_undying": (["...Y...", "..YgY..", ".YgGgY.", "YgGWGgY", ".YgGgY.", "..YgY..", "...Y..."],
                         {"Y": ("gold", 2), "g": ("slime", 2), "G": ("lamp", 3), "W": ("feather", 3)}),
    "firefly": (["..Y.Y..", "...d...", ".wYYYw.", "wwYGYww", "..YGY..", "...Y...", "......."],
                {"Y": ("glow_amber", 2), "d": ("iron", 4), "w": ("ice", 3), "G": ("lamp", 3)}),
    "falling_water": (["...D...", "...D...", "..DdD..", ".DdddD.", ".DddwD.", "..DDD..", "......."],
                      {"D": ("water", 3), "d": ("water", 2), "w": ("ice", 3)}),
}
GLYPHS.update({k: v for k, v in _P.items() if k not in GLYPHS})

TICKET_SPARK = {"flame", "small_flame", "firework", "electric_spark", "totem_of_undying", "firefly"}


def _badge(ic):
    cx, cy, r = BADGE
    disc = {(x, y) for y in range(16) for x in range(16) if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r}
    for (x, y) in disc:
        rim = any((x + a, y + b) not in disc for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        ic.put(x, y, "iron", 0 if rim else 1 if y > cy else 2)
    return cx, cy


def _glyph(name):
    return GLYPHS[name if name in GLYPHS else "end_rod"]


def _trail_or_kill(ic, c):
    """voucher_art._particle_picture's layout with the extra glyphs (kill: glyph in the middle, four sparks;
    trail: glyph up to the right, two small steps of its colour down to the left)."""
    cx, cy = _badge(ic)
    rows, legend = _glyph(c.get("particle", ""))
    w, h = max(len(row) for row in rows), len(rows)
    main = next(iter(legend.values()))
    if c.get("slot") == "kill":
        gx, gy = int(cx) - w // 2, int(cy) - h // 2
        _core_paint(ic, rows, legend, gx, gy)
        spark = ("lamp", 3) if c.get("particle") in TICKET_SPARK else ("feather", 3)
        for x, y in ((gx - 1, gy - 1), (gx + w, gy - 1), (gx - 1, gy + h), (gx + w, gy + h)):
            ic.put(x, y, *spark)
    else:
        _core_paint(ic, rows, legend, int(cx) - w // 2 + 1, int(cy) - h // 2 - 1)
        for x, y in ((5, 8), (7, 10)):
            for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
                ic.put(x + dx, y + dy, *main)


def _ring(n, cx, cy, rx, ry, phase=0.3):
    return [(round(cx + rx * math.cos(phase + 2 * math.pi * k / n) - 0.5),
             round(cy + ry * math.sin(phase + 2 * math.pi * k / n) - 0.5),
             math.sin(phase + 2 * math.pi * k / n)) for k in range(n)]


def _aura(ic, c):
    """The glyph a little high in the badge, a tilted ring of dots round it: the back half behind the glyph,
    the front half over it. Ground auras (height < 0.5) put the ring low, chest ones across the glyph."""
    cx, cy = _badge(ic)
    particle = c.get("particle", "")
    ground = float(c.get("height", 1.0)) < 0.5
    ring_y = cy + (3.2 if ground else 1.2)
    if particle == "dust":
        dots = _RAINBOW if c.get("color") == "rainbow" else [("feather", 3)] * 6
        star = ["...W...", "..WWW..", "WWWLWWW", "..WWW..", "...W..."]
        rows, legend = star, {"W": ("feather", 3), "L": ("lamp", 3)}
    else:
        rows, legend = _glyph(particle)
        dots = [next(iter(legend.values()))]
    w, h = max(len(row) for row in rows), len(rows)
    pts = _ring(8, cx, ring_y, 5.0, 1.9)
    back = [p for p in pts if p[2] < 0]
    front = [p for p in pts if p[2] >= 0]
    for k, (x, y, _) in enumerate(back):
        ic.put(x, y, dots[k % len(dots)][0], max(0, dots[k % len(dots)][1] - 1))
    _core_paint(ic, rows, legend, int(cx) - w // 2, int(cy) - h // 2 - (2 if ground else 1))
    for k, (x, y, _) in enumerate(front):
        d = dots[(k + len(back)) % len(dots)]
        ic.put(x, y, *d)
        ic.put(x + 1, y, *d)


def voucher(sec):
    """The icon of one cosmetic's voucher (sec: the cosmetic's item section)."""
    c = sec.get("cosmetic") or {}
    if sec.get("model"):
        return VA.voucher(sec)
    rarity = c.get("rarity", "common")
    ic = _core_ticket(STUB[rarity], dark=rarity == "legendary")
    (_aura if c.get("slot") == "aura" else _trail_or_kill)(ic, c)
    return _core_finish(ic)


def _batch():
    import yaml
    data = yaml.safe_load((VA.CONTENT / "items" / "cosmetics_batch_c.yml").read_text(encoding="utf-8"))
    out = {}
    for key, sec in data.items():
        target = (sec.get("voucher") or {}).get("cosmetic")
        if target:
            out[key] = (lambda s=data[target.split(":", 1)[-1]]: voucher(s))
    return out


ICONS = _batch()
