"""
숨은 비밀 (관리자 문서) 의 물건 다섯: four damaged records a player holds, and a lantern that stands by a monument stone
(items/newcomer.yml names these models; python3 tools/assetgen/newcomer_assets.py writes only these).

  worn_ticket    an old bus ticket gone soft in water: a perforated stub bent forward, a faded green title band,
                         a conductor's punch hole, a torn corner; the lower right is a tide-marked stain where the ink
                         has run down and most of the print is gone.
  worn_receipt   a thermal receipt, its torn top serrated, its end rolled up towards the reader: a header
                         block, item lines, a total, a barcode; the middle of the print came out as blocks, a white
                         line runs down it where the print head dropped out, the roll's pink end stripe on its edge.
  worn_letter    a returned envelope: grubby, its corners worn round, one dog-eared; an address nobody can
                         read, a cancelled postage stamp, a red return stamp impression pressed askew over it, half
                         worn off; the flap open, a sheet with run ink showing over the edge.
  worn_bottle    a small sea-worn glass bottle, corked and waxed, twine round its neck; inside, a rolled note
                         tied with thread, leaning, its ink run in drips.
  lantern_iron    a small standing lantern: an old iron frame on four feet (rust, soot round the vent, moss at
                         a foot), four panes with a guard bar, a stepped cap with a carrying ring; a warm steady
                         flame on a brass burner (lit: the flame, the panes' glow and the lit floor give light).

The four shards share a faint mark: a few small pixel blocks out of line (two rows of the print pushed sideways, a
cyan, a magenta and a dark block off the grid, two of them floating just off the surface). None carries readable
secret text: the print is runs of texels.

Shards: drawn at 4 texels a px (BModel scale 4: 64 a block) round the model's middle (8, 8, 8), the face to the south
(+z: what an inventory slot and a first-person hand show, like a vanilla flat item), held and dropped with the vanilla
"generated" transforms, their own "gui" (a little turned, fitted to the slot). Paper is planes without thickness
(cut-out pictures: torn edges, holes), bent in 22.5 degree steps (strip).

Lantern: drawn at 2 texels a px (32 a block), the same placing as the monument stones (models/monument.py): its middle
at x = z = 8, its feet on the ground at model y 0, 9 px wide and just under a block tall. "fixed" is the identity
(presets.furniture): an item display shows the model's (8, 8, 8) on the entity, so one lifted 0.5 stands it on the
block under it.

Its own ramps (nc_*) are added to the palette here (like the monument's mn_*).
"""

import cmath
import math

from stsv_assets import presets
from stsv_assets.bake import FACES, BModel, Paint, _turn, h3, vnoise
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp

import gamble_art as GA
from store_art import Pic, _rgba


def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


# (hue, chroma, centre lightness, span, steps, accent)
_ramp("nc_paper", 82, 0.050, 0.80, 0.26, 6, False)      # old paper: foxed tan to pale cream
_ramp("nc_thermal", 240, 0.010, 0.88, 0.16, 5, False)   # thermal paper: a cool grey white
_ramp("nc_print", 265, 0.020, 0.50, 0.40, 5, False)     # thermal print and pencil: soft grey black, fading
_ramp("nc_ink", 258, 0.045, 0.54, 0.50, 6, False)       # blue-black ink, down to a pale wash where it ran
_ramp("nc_stamp", 22, 0.130, 0.60, 0.30, 5, False)      # red stamp ink, worn to pink
_ramp("nc_band", 165, 0.055, 0.62, 0.28, 5, False)      # the ticket's faded green print
_ramp("nc_seaglass", 185, 0.045, 0.74, 0.30, 6, False)  # sea-worn bottle glass
_ramp("nc_cork", 68, 0.070, 0.60, 0.28, 5, False)       # cork
_ramp("nc_wax", 25, 0.120, 0.42, 0.24, 4, False)        # sealing wax, dark red
_ramp("nc_iron", 250, 0.012, 0.30, 0.26, 6, False)      # old wrought iron
_ramp("nc_rust", 48, 0.070, 0.40, 0.22, 4, False)       # rust
_ramp("nc_flame", 78, 0.150, 0.84, 0.26, 5, True)       # the flame: deep amber to nearly white
_ramp("nc_glitch_c", 205, 0.090, 0.80, 0.14, 3, True)   # the mark's cyan block
_ramp("nc_glitch_m", 345, 0.110, 0.72, 0.14, 3, True)   # the mark's magenta block

T22 = math.tan(math.radians(22.5))
SNAP = (-45.0, -22.5, 0.0, 22.5, 45.0)


def C(x, y, z):
    """A point x, y, z texels from the model's middle -> drawn."""
    return (8 + x, 8 + y, 8 + z)


def _mul(c, k):
    return (min(255, int(c[0] * k)), min(255, int(c[1] * k)), min(255, int(c[2] * k)), c[3])


def _snap(a):
    b = min(SNAP, key=lambda v: abs(v - a))
    if abs(b - a) > 0.01:
        raise ValueError(f"angle {a}")
    return b


# ================================================================================================
# Paper: planes without thickness wearing a cut-out picture, bent in 22.5 degree steps
# ================================================================================================

def _picker(front, back, col, row, shade):
    """A decal: the front picture on the front face, the back picture (drawn in the front's frame) on the other."""
    def decal(c, front_face):
        i, j = col(c), row(c)
        pic = front if c.face == front_face else back
        if 0 <= i < pic.w and 0 <= j < pic.h:
            v = pic.px[i, j]
            if v[3]:
                return ("raw", _mul(v, shade[0] if c.face == front_face else shade[1]))
        return ("raw", (0, 0, 0, 0))
    return decal


def vstrip(m, name, front, back, x0, top, z, segs, light=None):
    """A sheet front.w wide hanging from (x0, top, z) (texels from the middle), its rows going down, in segments
    [(rows, angle)]: angle 0 hangs straight, more curls the end towards the reader (+z) and on up: 90 lies flat,
    180 has come right over. Only the first segment may be straight (the others turn about a hinge)."""
    H = complex(top, z)                 # (y, z) as a complex number: _turn("x") is a plain rotation in it
    r0 = 0
    for k, (L, phi) in enumerate(segs):
        d = cmath.rect(1, math.radians(180 - phi))
        if phi <= 45:
            u0, face = complex(-1, 0), "south"
        elif phi <= 135:
            u0, face = complex(0, 1), "up"
        else:
            u0, face = complex(1, 0), "north"
        a = _snap(math.degrees(cmath.phase(d / u0)))
        if a:
            S = complex(round(H.real), round(H.imag))
            r = cmath.rect(1, math.radians(a))
            O = (H - r * S) / (1 - r)
        else:
            S = O = H
        if face == "south":
            frm, to = C(x0, S.real - L, S.imag), C(x0 + front.w, S.real, S.imag)
            row = lambda c, S=S, r0=r0: r0 + math.floor(S.real - (c.p[1] - 8))
        elif face == "up":
            frm, to = C(x0, S.real, S.imag), C(x0 + front.w, S.real, S.imag + L)
            row = lambda c, S=S, r0=r0: r0 + math.floor((c.p[2] - 8) - S.imag)
        else:
            frm, to = C(x0, S.real, S.imag), C(x0 + front.w, S.real + L, S.imag)
            row = lambda c, S=S, r0=r0: r0 + math.floor((c.p[1] - 8) - S.real)
        # the light: a face turned up is brighter, one turned down or away darker; the inside of the curl in shadow
        up = math.sin(math.radians(phi))
        sh = light[k] if light else (1.0 + 0.10 * up if phi <= 90 else 1.0 - 0.18 * (phi - 90) / 90, 0.80 - 0.10 * up)
        dec = _picker(front, back, lambda c: math.floor(c.p[0] - 8 - x0), row, sh)
        m.box(name, frm, to, Paint("nc_paper", 3, noise=0, light=False, decal=lambda c, f=face, dec=dec: dec(c, f)),
              rotation=("x", a, C(0, O.real, O.imag)) if a else None)
        H += L * d
        r0 += L


def xstrip(m, name, front, back, x_at, col_at, top, z, segs, dirn=1):
    """The columns of a sheet front.h tall from column col_at at x_at, going right (dirn 1) or left (-1), in segments
    [(columns, angle)]: angle more than 0 bends the end towards the reader. Only the first may be straight."""
    H = complex(x_at, z)                # (x, z)
    c0 = col_at
    for (L, phi) in segs:
        # _turn("y"): x' = x c + z s, z' = -x s + z c: the conjugate rotation in (x, z)
        a = _snap(-phi * dirn)
        d = complex(dirn, 0) * cmath.rect(1, math.radians(-a))
        if a:
            S = complex(round(H.real), round(H.imag))
            r = cmath.rect(1, math.radians(-a))
            O = (H - r * S) / (1 - r)
        else:
            S = O = H
        if dirn > 0:
            frm, to = C(S.real, top - front.h, S.imag), C(S.real + L, top, S.imag)
            col = lambda c, S=S, c0=c0: c0 + math.floor((c.p[0] - 8) - S.real)
        else:
            frm, to = C(S.real - L, top - front.h, S.imag), C(S.real, top, S.imag)
            col = lambda c, S=S, c0=c0: c0 - math.ceil(S.real - (c.p[0] - 8))
        sh = (1.0 - 0.10 * (phi > 0), 0.80)
        dec = _picker(front, back, col, lambda c: math.floor(top - (c.p[1] - 8)), sh)
        m.box(name, frm, to, Paint("nc_paper", 3, noise=0, light=False, decal=lambda c, dec=dec: dec(c, "south")),
              rotation=("y", a, C(O.real, 0, O.imag)) if a else None)
        H += L * d
        c0 += L * dirn


def backing(front, fn):
    """The sheet's other side, in the front's frame (so the outline is the same): fn(x, y) -> colour."""
    b = Pic(front.w, front.h)
    for y in range(front.h):
        for x in range(front.w):
            if front.px[x, y][3]:
                b.p(x, y, fn(x, y))
    return b


def cut(P, x, y):
    if 0 <= x < P.w and 0 <= y < P.h:
        P.px[int(x), int(y)] = (0, 0, 0, 0)


def inked(P, x, y):
    return 0 <= x < P.w and 0 <= y < P.h and P.px[x, y][3] > 0


# ================================================================================================
# The shared mark: a few pixel blocks out of line
# ================================================================================================

def slip(P, y0, y1, dx):
    """Rows y0..y1-1 pushed dx texels sideways, the edge with them (what they leave is a hole)."""
    for y in range(max(0, y0), min(P.h, y1)):
        row = [P.px[x, y] for x in range(P.w)]
        for x in range(P.w):
            s = x - dx
            P.px[x, y] = row[s] if 0 <= s < P.w else (0, 0, 0, 0)


# the mark's blocks: (dx, dy, w, h, colour) from its corner
MARK = ((0, 0, 3, 3, ("nc_glitch_c", 1)), (4, -2, 2, 2, ("nc_ink", 0)), (2, 4, 4, 2, ("nc_glitch_m", 1)),
        (7, 1, 2, 2, ("nc_glitch_c", 2)), (-3, 3, 2, 1, ("nc_ink", 1)))


def mark(P, x, y, flip=False):
    """The mark painted into a picture at (x, y), over the edge too."""
    for (dx, dy, w, h, col) in MARK:
        bx = x - dx - w + 1 if flip else x + dx
        P.rect(bx, y + dy, bx + w, y + dy + h, col)


def floaters(m, x, y, z, flip=False):
    """Two of the mark's blocks as planes of their own just off the surface, half a texel off the grid."""
    for (dx, dy, w, col) in ((5.5, 5.5, 3, ("nc_glitch_c", 1)), (-2.5, -3.5, 2, ("nc_glitch_m", 1))):
        bx = x - dx - w if flip else x + dx
        rgba = _rgba(col)
        m.box("mark", C(bx, y - dy - w, z), C(bx + w, y - dy, z),
              Paint("nc_ink", 2, noise=0, light=False, decal=lambda c, v=rgba: ("raw", v)))


# ================================================================================================
# Displays
# ================================================================================================

def _corners(m):
    out = []
    for e in m.elements:
        f, t = e["from"], e["to"]
        for x in (f[0], t[0]):
            for y in (f[1], t[1]):
                for z in (f[2], t[2]):
                    p = (x, y, z)
                    if e["rotation"]:
                        axis, ang, o = e["rotation"]
                        p = _turn(p, axis, ang, o)
                    out.append(p)
    return out


def shard_display(m, gui_rot, fill=14.5, hand=1.0):
    """Held and dropped like a vanilla flat item (the "generated" transforms: the face to the reader in first person);
    in a slot a little turned (gui_rot) so its bends show, its longer side `fill` px, centred."""
    r = presets._rot_xyz(gui_rot)
    pts = [[sum(r[i][j] * (p[j] - 8) for j in range(3)) for i in range(3)] for p in _corners(m)]
    lo = [min(p[i] for p in pts) for i in range(3)]
    hi = [max(p[i] for p in pts) for i in range(3)]
    s = round(fill / max(hi[0] - lo[0], hi[1] - lo[1]), 3)
    d = {k: dict(v) for k, v in presets.GENERATED.items()}
    for k in ("thirdperson_righthand", "firstperson_righthand", "ground"):
        d[k]["scale"] = [round(v * hand, 3) for v in d[k]["scale"]]
    d["firstperson_righthand"]["translation"] = [0.0, 4.4, 1.13]
    d["gui"] = {"rotation": list(gui_rot), "scale": [s, s, s],
                "translation": [round(-(lo[0] + hi[0]) / 2 * s, 2), round(-(lo[1] + hi[1]) / 2 * s, 2), 0]}
    m.display = d
    return m


# ================================================================================================
# 번진 승차권
# ================================================================================================

def _paper(P, ramp, base, seed, edge=True):
    """Old paper: fibres, foxing, the edge handled dark."""
    for y in range(P.h):
        for x in range(P.w):
            v = vnoise(x + 0.5, y + 0.5, seed, 5.0, seed)
            s = base + (1 if v > 0.62 else -1 if v < 0.34 else 0)
            if h3(x, y, 0, seed + 1) < 0.05:
                s -= 1
            e = min(x, y, P.w - 1 - x, P.h - 1 - y)
            if edge and (e == 0 or (e == 1 and h3(x, y, 1, seed + 2) < 0.45)):
                s -= 1
            P.p(x, y, (ramp, s))


def _runs(P, x0, y, x1, col, seed, keep=1.0):
    """A line of print too small to read; keep < 1 drops words (print lost)."""
    x, k = x0, seed
    while x < x1:
        n = 2 + (k * 7 + 3) % 4
        if h3(k, y, 0, seed + 40) < keep:
            for i in range(n):
                if x + i < x1:
                    P.p(x + i, y, col)
        x += n + 1
        k += 1


def _wet(x, y, cx, cy, rx, ry, seed):
    """How deep (x, y) is in a water stain round (cx, cy): > 0 inside, about 0 on its tide line."""
    d = math.hypot((x + 0.5 - cx) / rx, (y + 0.5 - cy) / ry)
    return 1.0 - d + (vnoise(x + 0.5, y + 0.5, 0, 4.0, seed) - 0.5) * 0.7


def stain(P, paper, ink_ramp, cx, cy, rx, ry, seed, lose=0.6, base=3):
    """A water stain: the paper a step darker inside a darker tide line; the print in it mostly gone, what is left
    run down the sheet in a paling wash."""
    ink = {}
    plain = {_rgba((paper, k)) for k in range(-2, 9)}
    for y in range(P.h):
        for x in range(P.w):
            if not P.px[x, y][3]:
                continue
            w = _wet(x, y, cx, cy, rx, ry, seed)
            if w <= 0:
                continue
            if P.px[x, y] not in plain:                             # print
                ink[(x, y)] = w
    for y in range(P.h):
        for x in range(P.w):
            if not P.px[x, y][3]:
                continue
            w = _wet(x, y, cx, cy, rx, ry, seed)
            if w <= 0:
                continue
            v = vnoise(x + 0.5, y + 0.5, 3, 3.0, seed + 5)
            P.p(x, y, (paper, base - 2 if w < 0.09 else base if v > 0.55 else base - 1))
    for (x, y), w in sorted(ink.items()):
        gone = h3(x // 2, y // 2, 0, seed + 9) < lose
        if not gone:
            P.p(x, y, (ink_ramp, 2))
        n = 2 + int(h3(x, 0, 0, seed + 11) * 6)
        for k in range(1, n):
            if inked(P, x, y + k) and (x, y + k) not in ink and h3(x // 2, 0, 0, seed + 12) < 0.5:
                P.p(x, y + k, (ink_ramp, 4 if k < 3 and not gone else 5))


def ticket_front():
    W, H = 56, 28
    P = Pic(W, H)
    _paper(P, "nc_paper", 4, 3)
    ink, band = ("nc_ink", 1), ("nc_band", 1)
    # the stub: a serial block, print, the perforation
    P.rect(2, 3, 11, 7, ("nc_band", 2))
    _runs(P, 3, 5, 10, ("nc_paper", 5), 2)
    for k, y in enumerate((10, 14, 18)):
        _runs(P, 2, y, 11, ("nc_ink", 2), 5 + k)
    P.rect(3, 22, 9, 25, ("nc_stamp", 2))
    P.rect(4, 23, 8, 24, ("nc_paper", 5))
    for y in range(H):
        if y % 3 != 2:
            P.p(13, y, ("nc_paper", 1))
    # the title band and the word 승차권 (ticket)
    P.rect(16, 2, W - 2, 4, band)
    P.rect(16, 18, W - 2, 19, band)
    word = GA.word_mask("승차권", bold=False)
    P.mask(word, 16 + (W - 18 - len(word[0])) // 2, 5, ("nc_band", 0))
    # under it: from - to with an arrow, the fare box, small print
    P.rect(17, 21, 23, 25, ("nc_ink", 2))
    P.rect(18, 22, 22, 24, ("nc_paper", 5))
    for x in range(25, 33):
        P.p(x, 23, ink)
    P.p(31, 22, ink)
    P.p(31, 24, ink)
    P.rect(35, 21, 41, 25, ("nc_ink", 2))
    P.rect(36, 22, 40, 24, ("nc_paper", 5))
    _runs(P, 43, 22, W - 3, ink, 9)
    _runs(P, 43, 24, W - 3, ink, 13)
    # the conductor's punch, the torn corner, a nick in the edge
    for y in range(H):
        for x in range(W):
            if math.hypot(x + 0.5 - 49.5, y + 0.5 - 10.5) < 2.3:
                cut(P, x, y)
            elif math.hypot(x + 0.5 - 49.5, y + 0.5 - 10.5) < 3.2:
                P.p(x, y, ("nc_paper", 2))
            if x + y < 5 + (2 if (x * 3 + y) % 4 == 0 else 0):
                cut(P, x, y)
    for (x, y) in ((13, 0), (13, H - 1), (W - 1, 16), (W - 2, 16), (W - 1, 17), (30, H - 1), (31, H - 1)):
        cut(P, x, y)
    # the water: the right half's lower part, and a smaller ring on the stub
    stain(P, "nc_paper", "nc_ink", 45, 23, 14, 9, 21, lose=0.7)
    stain(P, "nc_paper", "nc_ink", 3, 17, 5, 5, 33, lose=0.5)
    # the mark
    slip(P, 8, 10, 3)
    slip(P, 12, 13, -2)
    mark(P, 22, 12)
    return P


def worn_ticket():
    m = BModel("worn_ticket", floor=None, scale=4)
    front = ticket_front()
    back = backing(front, lambda x, y: ("nc_paper", 3 if vnoise(x, y, 1, 5.0, 8) > 0.45 else 2)
                   if (x * 5 + y * 3) % 11 else ("nc_band", 3))
    m.group("ticket")
    xstrip(m, "ticket", front, back, -14, 14, 14, 0, [(26, 0), (8, 0), (8, 22.5)])
    xstrip(m, "stub", front, back, -14, 14, 14, 0, [(14, 22.5)], dirn=-1)
    m.group("mark")
    floaters(m, -6, 2, 1.0)
    return shard_display(m, (14, -22, -7))


# ================================================================================================
# 깨져 인쇄된 영수증
# ================================================================================================

def receipt_front():
    W, H = 28, 72
    P = Pic(W, H)
    for y in range(H):
        for x in range(W):
            v = vnoise(x + 0.5, y + 0.5, 0, 6.0, 41)
            P.p(x, y, ("nc_thermal", 3 if v > 0.4 else 2))
    pr = lambda y: ("nc_print", 0 if y < 30 else 1 if y < 50 else 2)       # the print fades down the roll
    # the header: the shop's block, two centred lines
    P.rect(8, 5, 20, 10, pr(0))
    _runs(P, 10, 7, 18, ("nc_thermal", 4), 1)
    _runs(P, 6, 12, 22, pr(0), 3)
    _runs(P, 8, 14, 20, ("nc_print", 1), 4)
    for x in range(3, W - 3, 2):
        P.p(x, 17, pr(0))
    # the lines: a name left, a price right
    for k in range(9):
        y = 20 + k * 3
        _runs(P, 3, y, 14 + (k * 5) % 5, pr(y), 20 + k)
        n = 3 + (k * 3) % 3
        P.rect(W - 3 - n, y, W - 3, y + 1, pr(y))
    for x in range(3, W - 3, 2):
        P.p(x, 47, pr(47))
    # the total, heavy
    P.rect(3, 50, 10, 53, pr(40))
    P.rect(16, 50, W - 3, 53, pr(40))
    P.p(19, 51, ("nc_thermal", 3))
    P.p(22, 51, ("nc_thermal", 3))
    # the barcode and the last lines
    for x in range(5, W - 5):
        if (x * 7 + x // 3) % 3:
            P.rect(x, 56, x + 1, 62, pr(60))
    _runs(P, 6, 64, 22, pr(64), 31)
    _runs(P, 8, 67, 20, pr(64), 33)
    # the print gone to blocks: 4 x 3 cells, some solid, some chequered, some empty, some pushed over
    for cy in range(4):
        for cx in range(6):
            x0, y0 = 2 + cx * 4 + (cy % 2), 23 + cy * 3
            r = h3(cx, cy, 0, 71)
            if r < 0.22:
                continue
            for y in range(y0, y0 + 3):
                for x in range(x0, x0 + 4):
                    if x >= W - 1:
                        continue
                    if r < 0.5:
                        P.p(x, y, pr(0) if (x + y) % 2 else ("nc_thermal", 3))
                    elif r < 0.8:
                        P.p(x, y, pr(0))
                    else:
                        P.p(x, y, ("nc_print", 2) if y % 2 else ("nc_thermal", 3))
    # the print head's dead dot: a white line down everything
    for y in range(4, 63):
        P.p(18, y, ("nc_thermal", 3))
    # the roll's end: a pink stripe down the right edge, low
    for y in range(40, H):
        if y % 7 < 5:
            P.p(W - 2, y, ("nc_stamp", 3))
            P.p(W - 1, y, ("nc_stamp", 4))
    # the torn top (the cutter's teeth), a worn bottom corner
    for x in range(W):
        for y in range((0, 1, 2, 1)[x % 4] + (1 if x in (9, 10, 11) else 0)):
            cut(P, x, y)
    cut(P, 0, H - 1)
    cut(P, 1, H - 1)
    cut(P, 0, H - 2)
    # a coffee-coloured drop that took some print with it
    stain(P, "nc_thermal", "nc_print", 7, 38, 5, 5, 53, lose=0.6, base=2)
    # the mark
    slip(P, 26, 28, 3)
    slip(P, 31, 32, -2)
    mark(P, 15, 13)
    return P


def worn_receipt():
    m = BModel("worn_receipt", floor=None, scale=4)
    front = receipt_front()
    back = backing(front, lambda x, y: ("nc_thermal", 3 if (x + y * 2) % 9 else 2))
    m.group("receipt")
    vstrip(m, "receipt", front, back, -14, 30, -2,
           [(44, 0), (5, 22.5), (4, 45), (4, 67.5), (3, 90), (4, 112.5), (4, 135), (4, 157.5)])
    m.group("mark")
    floaters(m, -1, 14, -1.0)
    return shard_display(m, (12, 28, 8))


# ================================================================================================
# 반송된 편지
# ================================================================================================

def letter_front():
    W, H = 60, 40
    P = Pic(W, H)
    _paper(P, "nc_paper", 4, 5)
    ink = ("nc_ink", 1)
    # grubby: a thumbed lower edge, a crease across
    for y in range(H):
        for x in range(W):
            if vnoise(x + 0.5, y + 0.5, 2, 7.0, 61) > 0.66 and (y > H - 10 or x < 8):
                P.p(x, y, ("nc_paper", 2))
    for x in range(W):
        if x % 5 != 3:
            P.shade(x, 22 + (x // 17) % 2, 0.93)
    # the address: lines somebody wrote, some of them rubbed out
    for k, (x0, x1, keep) in enumerate(((30, 44, 0.9), (30, 40, 0.5), (30, 43, 0.3))):
        y = 22 + k * 5
        _runs(P, x0, y, x1, ink, 3 + k, keep)
        _runs(P, x0, y + 1, x1, ("nc_ink", 2), 3 + k, keep * 0.6)
        # the postage stamp: perforated, a little picture, the wavy cancel running off it
    P.rect(46, 4, 56, 16, ("nc_paper", 5))
    for k in range(46, 56, 2):
        cut_c = ("nc_paper", 3)
        P.p(k, 4, cut_c)
        P.p(k, 15, cut_c)
    for k in range(4, 16, 2):
        P.p(46, k, ("nc_paper", 3))
        P.p(55, k, ("nc_paper", 3))
    P.rect(48, 6, 54, 14, ("nc_band", 2))
    P.ell(51, 9, 2.2, 2.2, ("nc_paper", 5))
    P.rect(48, 12, 54, 14, ("nc_band", 1))
    for x in range(40, 58):
        yy = 9 + round(1.4 * math.sin(x * 0.9))
        for dy in (0, 4):
            if h3(x, dy, 0, 66) < 0.8:
                P.p(x, yy + dy, ("nc_print", 1))
    # the return stamp: a red frame pressed askew, 수취인 없음 (addressee unknown), half worn off
    a, b = GA.word_mask("수취인", bold=False), GA.word_mask("없음", bold=False)
    sw, sh = len(a[0]) + 8, len(a) + len(b) + 9
    sx, sy = 3, 5

    def press(i, j, col):
        x, y = sx + i, sy + j - i // 12
        v = vnoise(x + 0.5, y + 0.5, 4, 5.0, 77)
        if v > 0.30 and h3(x, y, 0, 78) < 0.94 and inked(P, x, y):
            P.p(x, y, col if v > 0.40 else ("nc_stamp", 3))
    for j in range(sh):
        for i in range(sw):
            if min(i, j, sw - 1 - i, sh - 1 - j) < 2 and (i, j) not in ((0, 0), (sw - 1, 0), (0, sh - 1), (sw - 1, sh - 1)):
                press(i, j, ("nc_stamp", 1))
    for j, row in enumerate(a):
        for i, v in enumerate(row):
            if v:
                press(4 + i, 4 + j, ("nc_stamp", 1))
    for j, row in enumerate(b):
        for i, v in enumerate(row):
            if v:
                press(4 + (len(a[0]) - len(b[0])) // 2 + i, 5 + len(a) + j, ("nc_stamp", 1))
    # the corners worn round, one dog-eared (the back's paler paper folded over, a shadow under the fold)
    for y in range(H):
        for x in range(W):
            for (cx, cy, r) in ((0, 0, 3.2), (W, 0, 2.4), (0, H, 2.6)):
                if abs(x + 0.5 - cx) + abs(y + 0.5 - cy) < r + (h3(x, y, 0, 81) < 0.3):
                    cut(P, x, y)
            d = (W - 1 - x) + (H - 1 - y)
            if d < 7:
                cut(P, x, y)
            elif d < 14 and x >= W - 8 and y >= H - 8:
                P.p(x, y, ("nc_paper", 5 if d > 8 else 4))
            elif d == 14 and x >= W - 9 and y >= H - 9:
                P.p(x, y, ("nc_paper", 1))
    for (x, y) in ((24, H - 1), (25, H - 1), (0, 19), (0, 20)):
        cut(P, x, y)
    # a damp corner, bottom left
    stain(P, "nc_paper", "nc_ink", 44, 37, 9, 6, 91, lose=0.6)
    # the mark
    slip(P, 6, 8, 3)
    slip(P, 30, 31, -2)
    mark(P, 47, 22)
    return P


def letter_back(front):
    """The back, in the front's frame: the seams' V, the gum line."""
    W, H = front.w, front.h

    def fn(x, y):
        if abs(abs(x - W / 2) * 0.62 - (H - 3 - y)) < 0.6 and y > 12:
            return ("nc_paper", 2)
        return ("nc_paper", 4 if vnoise(x + 0.5, y + 0.5, 0, 5.0, 95) > 0.4 else 3)
    return backing(front, fn)


def letter_flap(W):
    """The open flap, hinged on its bottom row: a triangle, its inside paler, the gum's shine along the edges."""
    H = 15
    P = Pic(W, H)
    for y in range(H):
        hw = 2 + (W / 2 - 3) * (y + 1) / H
        for x in range(W):
            d = hw - abs(x + 0.5 - W / 2)
            if d > 0:
                P.p(x, y, ("nc_paper", 1) if d < 1 else ("nc_paper", 5) if d < 3 and (x + y) % 3 else ("nc_paper", 3))
    return P


def letter_sheet():
    """The sheet's top showing over the envelope's edge: ruled, a few words, the ink run."""
    W, H = 46, 16
    P = Pic(W, H)
    for y in range(H):
        for x in range(W):
            P.p(x, y, ("nc_thermal", 4 if vnoise(x + 0.5, y + 0.5, 0, 6.0, 97) > 0.35 else 3))
    for y in (3, 6, 9, 12):
        _runs(P, 4, y, W - 6, ("nc_ink", 1), y, 0.55)
    stain(P, "nc_thermal", "nc_ink", 30, 6, 12, 7, 99, lose=0.55, base=3)
    for (x, y) in ((0, 0), (1, 0), (0, 1), (W - 1, 0), (W - 2, 0), (W - 1, 1), (W - 1, 2), (17, 0), (18, 0)):
        cut(P, x, y)
    slip(P, 4, 6, 2)
    return P


def worn_letter():
    m = BModel("worn_letter", floor=None, scale=4)
    front = letter_front()
    m.group("envelope")
    xstrip(m, "envelope", front, letter_back(front), -30, 0, 16, 1, [(60, 0)])
    m.group("letter")
    sheet = letter_sheet()
    xstrip(m, "sheet", sheet, backing(sheet, lambda x, y: ("nc_paper", 4)), -21, 0, 23, 0, [(sheet.w, 0)])
    m.group("flap")
    flap = letter_flap(60)
    # the flap stands up from the top edge, leaning back
    S = C(-30, 16, -1)
    dec = _picker(flap, backing(flap, lambda x, y: ("nc_paper", 3 if (x + y) % 7 else 2)),
                  lambda c: math.floor(c.p[0] - 8 + 30), lambda c: flap.h - 1 - math.floor(c.p[1] - 8 - 16), (0.86, 0.80))
    m.box("flap", S, C(30, 16 + flap.h, -1), Paint("nc_paper", 3, noise=0, light=False, decal=lambda c: dec(c, "south")),
          rotation=("x", -22.5, C(0, 16, -1)))
    m.group("mark")
    floaters(m, 11, -8, 2.0)
    return shard_display(m, (10, -20, 5))


# ================================================================================================
# 번진 쪽지가 든 병
# ================================================================================================

def _octa_frame(f, ang):
    """A side face of an octagon's box turned ang about y: (side k: 0 = south, 1 = south-east ..., its tangent)."""
    n = _turn(FACES[f][0], "y", ang, (0, 0, 0)) if ang else FACES[f][0]
    th = math.atan2(n[0], n[2])
    k = round(th / (math.pi / 4)) % 8
    return k, (math.cos(k * math.pi / 4), -math.sin(k * math.pi / 4))


def octa(m, name, r, y0, y1, paint, side=None, top=None, down=False, c=(0, 0)):
    """A solid eight-sided prism round the y axis (inradius r texels, y0..y1 from the middle): four boxes, two of
    them turned 45 degrees; only the faces that show are kept (the eight sides, the tops; the bottoms with down).
    side(U, y, k) paints the sides: U texels round from the front's (south's) middle to the reader's right, y texels
    up, k the side; top(x, z) the tops."""
    s = 2 * r * T22
    cx, cz = 8 + c[0], 8 + c[1]
    e = 0.03
    for n, (wide, ang) in enumerate((("x", 0), ("z", 0), ("x", 45), ("z", 45))):
        a, b = ((cx - r, cz - s / 2), (cx + r, cz + s / 2)) if wide == "x" else ((cx - s / 2, cz - r), (cx + s / 2, cz + r))
        keep = ("east", "west") if wide == "x" else ("north", "south")

        def dec(ct, ang=ang):
            if ct.face == "up":
                p = _turn(ct.p, "y", ang, (cx, 0, cz)) if ang else ct.p
                return top(p[0] - cx, p[2] - cz) if top else None
            if ct.face == "down" or not side:
                return None
            k, t = _octa_frame(ct.face, ang)
            p = _turn(ct.p, "y", ang, (cx, 0, cz)) if ang else ct.p
            U = k * s + (p[0] - cx) * t[0] + (p[2] - cz) * t[1]
            if U >= 4 * s:
                U -= 8 * s
            return side(U, ct.p[1] - 8, k)
        sp = paint(vrim=0, decal=dec)
        tp = Paint(paint.ramp, paint.base, noise=0, light=False, decal=dec)
        faces = {f: None for f in FACES if f not in keep and f != "up" and not (f == "down" and down)}
        faces["up"] = tp
        m.box(name, (a[0], 8 + y0 + n * e, a[1]), (b[0], 8 + y1 - n * e, b[1]), sp, faces=faces,
              rotation=("y", ang, (cx, 8 + y0, cz)) if ang else None)
        m.elements[-1]["open"] = tuple(faces)
    return s


def shell(m, name, r, y0, y1, fn):
    """Eight planes without thickness round the y axis (a glass wall): fn(U, y, k) -> a colour, or None for
    see-through; both faces of a plane wear the same picture."""
    s = 2 * r * T22
    out = {"south": (0, 0, 1), "north": (0, 0, -1), "east": (1, 0, 0), "west": (-1, 0, 0)}
    for ang in (0, 45):
        for f, n0 in out.items():
            if n0[2]:
                frm, to = (8 - s / 2, 8 + y0, 8 + n0[2] * r), (8 + s / 2, 8 + y1, 8 + n0[2] * r)
            else:
                frm, to = (8 + n0[0] * r, 8 + y0, 8 - s / 2), (8 + n0[0] * r, 8 + y1, 8 + s / 2)
            k, t = _octa_frame(f, ang)

            def dec(ct, ang=ang, k=k, t=t):
                p = _turn(ct.p, "y", ang, (8, 0, 8)) if ang else ct.p
                U = k * s + (p[0] - 8) * t[0] + (p[2] - 8) * t[1]
                if U >= 4 * s:
                    U -= 8 * s
                got = fn(U, ct.p[1] - 8, k)
                return ("raw", _rgba(got)) if got else ("raw", (0, 0, 0, 0))
            m.box(name, frm, to, Paint("nc_seaglass", 3, noise=0, light=False, decal=dec),
                  rotation=("y", ang, (8, 8 + y0, 8)) if ang else None)
    return s


def worn_bottle():
    m = BModel("worn_bottle", floor=None, scale=4)
    Y0 = -26                    # the bottle's foot
    R = 13.0
    G = "nc_seaglass"

    def glass_side(r):
        s = 2 * r * T22

        def fn(U, y, k):
            u = U / s                                          # sides from the front's middle
            if -1.25 < u < -0.95:
                return (G, 6)                                  # the shine down the front left
            if 1.6 < u < 1.75:
                return (G, 4)
            if h3(math.floor(U), math.floor(y), 0, 5) < 0.06:
                return (G, 1)                                  # the sea's frosting: pits
            return None
        return fn
    glass = Paint(G, 3, "lacquer", noise=0.03, rim=1.2)
    m.group("bottle")
    octa(m, "foot", R - 1, Y0, Y0 + 1, glass, side=glass_side(R - 1), down=True)
    octa(m, "foot", R, Y0 + 1, Y0 + 3, glass, side=glass_side(R), top=lambda x, z: (G, 1 if math.hypot(x, z) < 9 else 3))

    def wall(U, y, k):
        yy = y - Y0
        s = 2 * R * T22
        u = U / s
        iu, iy = math.floor(U), math.floor(yy)
        if yy >= 30 or yy < 4:
            return (G, 4 if yy >= 31 else 2)                    # the rims
        if -1.3 < u < -0.95 and 6 < yy < 28 and not (17 < yy < 19):
            return (G, 6)                                       # the shine
        if -0.9 < u < -0.78 and 20 < yy < 27:
            return (G, 5)
        if abs(abs(u) - 4) < 0.08:
            return (G, 2)                                       # the mould seam at the back
        if h3(iu // 2, iy // 2, 0, 7) < 0.10:
            return (G, 4)                                       # scuffs
        if abs(u) > 1.5:                                        # the glass round the back, what the note shows against
            return (G, (2 if abs(u) > 2.5 else 3) + (1 if vnoise(U, yy, 0, 4.0, 9) > 0.6 else 0))
        if abs(abs(u) - 1.5) < 0.07:
            return (G, 4)                                       # the front panes' edges catching the light
        if (iu + 2 * iy) % 5 == 0 and iy % 2 == 0:
            return (G, 4)                                       # the glass in front: a thin veil
        return None
    shell(m, "wall", R, Y0 + 3, Y0 + 32, wall)
    for (r, a, b) in ((R - 2, 32, 34), (R - 5, 34, 36), (R - 8, 36, 38)):
        octa(m, "shoulder", r, Y0 + a, Y0 + b, glass, side=glass_side(r), top=lambda x, z: (G, 4))
    octa(m, "neck", 4.25, Y0 + 38, Y0 + 45, glass, side=glass_side(4.25))
    octa(m, "lip", 5.25, Y0 + 45, Y0 + 47, glass(base=4), side=glass_side(5.25), top=lambda x, z: (G, 5), down=True)
    m.group("cork")

    def cork_side(U, y, k):
        r = h3(math.floor(U), math.floor(y), 0, 15)
        return ("nc_cork", 1) if r < 0.16 else ("nc_cork", 3) if r > 0.84 else None

    def wax(U, y, k):
        # sealing wax over the cork's top, run down in drips of different lengths
        drip = (2, 5, 1, 3, 6, 2, 4, 1)[k]
        if y - (Y0 + 53) > -drip * 0.6:
            return ("nc_wax", 2 if h3(math.floor(U), math.floor(y), 0, 17) > 0.2 else 3)
        return cork_side(U, y, k)
    octa(m, "cork", 4.0, Y0 + 47, Y0 + 53, Paint("nc_cork", 2, noise=0.05), side=wax,
         top=lambda x, z: ("nc_wax", 3 if math.hypot(x + 0.8, z - 0.8) < 1.6 else 2))
    # twine round the neck, a knot and two ends at the front
    twine = Paint("burlap", 2, noise=0.05, decal=lambda c: ("burlap", 3 if (c.i[0] + c.i[1] + c.i[2]) % 2 else 1))
    octa(m, "twine", 5.0, Y0 + 40, Y0 + 42.5, twine, top=lambda x, z: ("burlap", 2), down=True)
    m.box("knot", C(-1.5, Y0 + 39.5, 5), C(1.5, Y0 + 43, 6.5), twine)
    m.box("twine_end", C(-2.5, Y0 + 35.5, 6), C(-1.25, Y0 + 40, 6.75), twine, rotation=("z", -22.5, C(-1.5, Y0 + 40, 6)))
    m.box("twine_end", C(1.25, Y0 + 36.5, 6), C(2.5, Y0 + 40, 6.75), twine, rotation=("z", 22.5, C(1.5, Y0 + 40, 6)))
    # the note: a roll of paper leaning in the bottle, tied with red thread, its ink run down in drips
    m.group("note")

    def note(c):
        x, y, z = c.p[0] - 8, c.p[1] - 8 - Y0, c.p[2] - 8
        ix, iy, iz = math.floor(x), math.floor(y), math.floor(z)
        if c.face in ("up", "down"):
            d = math.hypot(x + 0.5, z)
            return ("nc_paper", 1 if 1.2 < d < 2.2 or d < 0.6 else 5)          # the roll's end: its turns
        w = ix if c.face in ("north", "south") else iz
        if 14 <= y < 16:
            return ("nc_wax", 2 if (w + iy) % 3 else 3)                         # the thread
        if c.face == "south" and w == 2 and h3(0, iy, 0, 31) < 0.85:
            return ("nc_paper", 1)                                             # the sheet's edge
        if y < 5.5 + 2.5 * vnoise(w + 0.5, 0, iz, 2.0, 33):
            return ("nc_ink", 4 if h3(w, iy, iz, 35) < 0.6 else 3)             # where the ink pooled at its foot
        col = h3(w, 0, iz if c.face in ("east", "west") else 0, 37)
        if col < 0.42:
            n = 3 + int(col * 30)
            for row in (10, 19, 23):
                if row - n < y <= row:
                    return ("nc_ink", 1 if row - y < 1 else 3 if row - y < n - 2 else 4)   # a word, and its run
        if iy in (10, 19, 23) and h3(w, iy, 1, 39) < 0.5:
            return ("nc_ink", 2)
        if 20 <= y < 22 and c.face == "south":
            return None if (w + 1) % 5 else ("nc_glitch_c", 1)
        return None
    paper = Paint("nc_paper", 4, noise=0.04, decal=note, grad=0.4)
    for (hx, hz) in ((3.5, 2.25), (2.25, 3.5)):
        m.box("note", C(-hx - 1, Y0 + 3.2, -hz), C(hx - 1, Y0 + 29, hz), paper, rotation=("z", -22.5, C(-3, Y0 + 3.2, 0)))
    # the mark: three blocks off the grid by the bottle's side, a row of the wall out of line
    m.group("mark")
    for (x, y, w, col) in ((R + 1.5, Y0 + 19.5, 3, ("nc_glitch_c", 1)), (R + 0.5, Y0 + 24.5, 2, ("nc_glitch_m", 1)),
                           (R + 4.5, Y0 + 16.5, 2, ("nc_ink", 0))):
        rgba = _rgba(col)
        m.box("mark", C(x, y, 3), C(x + w, y + w, 3), Paint("nc_ink", 2, noise=0, light=False, decal=lambda c, v=rgba: ("raw", v)))
    return shard_display(m, (14, -24, 0), fill=14.0)


# ================================================================================================
# 꺼지지 않는 등불
# ================================================================================================

G0 = -8                         # the ground (drawn y: 2 texels a px, the model's middle at 8)


def _flame_pic():
    """The flame, 10 x 18: a steady teardrop, nearly white at its heart, amber at its edge, blue at the wick."""
    W, H = 10, 18
    P = Pic(W, H)
    for y in range(H):
        t = (H - 1 - y) / (H - 1)                     # 0 at the foot, 1 at the tip
        hw = 4.7 * (1 - t) ** 0.7 * min(1.0, (t + 0.12) * 4) ** 0.5 + 0.4
        for x in range(W):
            d = abs(x + 0.5 - W / 2) / max(hw, 0.01)
            if d >= 1:
                continue
            if t < 0.12:
                P.p(x, y, ("glow_cyan", 1) if d < 0.35 else ("nc_flame", 1))
            else:
                P.p(x, y, ("nc_flame", 4 if d < 0.36 and t < 0.6 else 3 if d < 0.66 and t < 0.8 else 2 if d < 0.9 else 1))
    return P


def lantern_iron():
    m = BModel("lantern_iron", floor=G0, scale=2)

    def L(x, y, z):
        return (8 + x, G0 + y, 8 + z)

    def rusty(seed, soot=None):
        def d(c):
            x, y, z = c.p[0], c.p[1] - G0, c.p[2]
            v = vnoise(x + 0.5, y * 1.4, z + 0.5, 2.4, seed)
            if soot is not None and y > soot and h3(c.i[0], c.i[1], c.i[2], seed + 3) < 0.55:
                return ("soot", 0)
            if v > 0.76 or (y < 3.2 and v > 0.58):
                return ("nc_rust", 2 if v > 0.80 else 1)
            if c.side and h3(c.i[0], 0, c.i[2], seed + 1) < 0.14 and h3(c.i[0], c.i[1], c.i[2], seed + 2) < 0.7 and 4 < y < 20:
                return ("nc_rust", 0)                              # a rust streak down a post
            return None
        return d
    iron = Paint("nc_iron", 2, "metal", noise=0.05, rim=1.25, decal=rusty(3))
    dark = Paint("nc_iron", 1, "metal", noise=0.05, rim=1.2, decal=rusty(5))
    m.group("base")
    for (x, z) in ((-8, -8), (6, -8), (-8, 6), (6, 6)):
        m.box("foot", L(x, 0, z), L(x + 2, 1.5, z + 2), dark)

    def floor(c):
        # the floor inside the panes, lit by the flame: warm in the middle, falling off to the iron
        if c.face != "up":
            return rusty(7)(c)
        d = math.hypot(c.p[0] - 8, c.p[2] - 8)
        if max(abs(c.p[0] - 8), abs(c.p[2] - 8)) > 6:
            return rusty(7)(c)
        return ("raw", _rgba(("nc_flame", 2) if d < 3.2 else ("nc_rust", 3) if d < 4.8 else ("nc_rust", 2) if d < 6.2 else ("nc_rust", 1)))
    m.box("plate", L(-8.5, 1.5, -8.5), L(8.5, 3, 8.5), iron)
    m.box("step", L(-7.5, 3, -7.5), L(7.5, 4, 7.5), iron(decal=floor))
    m.group("frame")
    for (x, z) in ((-7.5, -7.5), (6, -7.5), (-7.5, 6), (6, 6)):
        m.box("post", L(x, 4, z), L(x + 1.5, 20, z + 1.5), iron)
    for (a, b) in ((L(-6, 11.5, -7.25), L(6, 12.5, -6.75)), (L(-6, 11.5, 6.75), L(6, 12.5, 7.25)),
                   (L(-7.25, 11.5, -6), L(-6.75, 12.5, 6)), (L(6.75, 11.5, -6), L(7.25, 12.5, 6))):
        m.box("guard", a, b, dark)
    # the cap: a plate, three steps, the vent, a knob and the carrying ring
    m.group("cap")
    m.box("eave", L(-8.5, 20, -8.5), L(8.5, 21.5, 8.5), iron)
    sooty = Paint("nc_iron", 2, "metal", noise=0.05, rim=1.25, decal=rusty(9, soot=25.2))
    m.box("roof", L(-7, 21.5, -7), L(7, 23, 7), sooty)
    m.box("roof", L(-5.5, 23, -5.5), L(5.5, 24.5, 5.5), sooty)
    m.box("roof", L(-3.5, 24.5, -3.5), L(3.5, 26, 3.5), sooty)

    def vent(c):
        if c.side and math.floor(c.p[1] - G0 - 26) == 0 and math.floor(c.p[0] + c.p[2]) % 2 == 0:
            return ("raw", _rgba(("nc_flame", 1)))                 # the vent's slits, the light in them
        return ("soot", 0) if h3(c.i[0], c.i[1], c.i[2], 12) < 0.5 else None
    m.box("vent", L(-2, 26, -2), L(2, 27.5, 2), dark(decal=vent))
    m.box("vent_cap", L(-3, 27.5, -3), L(3, 28.5, 3), sooty)
    m.box("ring", L(-2.5, 28.5, -0.5), L(-1.5, 31, 0.5), dark)
    m.box("ring", L(1.5, 28.5, -0.5), L(2.5, 31, 0.5), dark)
    m.box("ring", L(-2.5, 31, -0.5), L(2.5, 32, 0.5), dark)
    # the burner: a brass font, its collar, the wick
    m.group("burner")
    brass = Paint("brass", 2, "metal", noise=0.04, rim=1.3)
    m.box("font", L(-3, 4, -3), L(3, 6.5, 3), brass)
    m.box("collar", L(-1.5, 6.5, -1.5), L(1.5, 7.5, 1.5), brass(base=1))
    # the flame: two crossed planes (steady: no frames), lit
    m.group("flame")
    pic = _flame_pic()

    def flame(c):
        w = (c.p[0] - 8) if c.face in ("north", "south") else (c.p[2] - 8)
        i, j = math.floor(w + pic.w / 2), pic.h - 1 - math.floor(c.p[1] - G0 - 7.5)
        if 0 <= i < pic.w and 0 <= j < pic.h and pic.px[i, j][3]:
            return ("raw", pic.px[i, j])
        return ("raw", (0, 0, 0, 0))
    lit = Paint("nc_flame", 3, noise=0, light=False, glow=True, decal=flame)
    m.box("flame", L(-5, 7.5, 0), L(5, 25.5, 0), lit)
    m.box("flame", L(0, 7.5, -5), L(0, 25.5, 5), lit)
    # the panes: old glass lit from inside (a warm veil thick round the flame, thinning to the corners), a streak of
    # shine, grime in the corners, a crack in one
    m.group("panes")

    def pane(k):
        def d(c):
            w = (c.p[0] - 8) if c.face in ("north", "south") else (c.p[2] - 8)
            y = c.p[1] - G0
            iw, iy = math.floor(w), math.floor(y)
            r = math.hypot(w / 6.0, (y - 12.5) / 8.0)
            if k == 1 and iw == round(-3 + (y - 14) * 0.5) and 14 <= y < 19.5:
                return ("raw", _rgba(("nc_iron", 4)))               # the crack
            corner = (6 - abs(w + 0.0)) + min(y - 4, 20 - y)
            if corner < 2.2 + 1.5 * h3(iw, iy, k, 21) and h3(iw, iy, k, 22) < 0.75:
                return ("raw", _rgba(("nc_rust", 0) if h3(iw, iy, k, 23) < 0.5 else ("soot", 1)))      # grime
            if k == 0 and iw - iy in (-17, -18) and 13 < y < 18.5:
                return ("raw", _rgba(("nc_flame", 4)))              # the shine
            n = (iw + iy) % 2 == 0
            if 0.5 < r < 0.95 and n and (iw // 2 + iy // 2) % 2 == 0:
                return ("raw", _rgba(("nc_flame", 2)))
            if 0.95 <= r and n and (iw // 2 + iy // 2) % 2:
                return ("raw", _rgba(("nc_flame", 1 if r < 1.2 else 0)))
            return ("raw", (0, 0, 0, 0))
        return Paint("nc_flame", 2, noise=0, light=False, glow=True, decal=d)
    m.box("pane", L(-6, 4, -7), L(6, 20, -7), pane(0))
    m.box("pane", L(-6, 4, 7), L(6, 20, 7), pane(1))
    m.box("pane", L(-7, 4, -6), L(-7, 20, 6), pane(2))
    m.box("pane", L(7, 4, -6), L(7, 20, 6), pane(3))
    # moss at a foot and on the plate's corner (the stones' own)
    from models import monument  # noqa: F401  (the mn_moss ramp)
    m.group("moss")
    moss = Paint("mn_moss", 2, "speckle", seed=5)
    m.box("moss", L(-10, 0.05, 5), L(-7.5, 1.5, 9.5), moss)
    m.box("moss", L(5.5, 3, 6.5), L(8.5, 3.75, 8.5), moss)
    m.box("moss", L(6.5, 0.05, -10), L(9.5, 1, -7), moss)
    m.display = presets.furniture(gui_scale=0.9, gui_translation=(0, -0.2, 0))
    return m


ALL = {
    "worn_ticket": worn_ticket,
    "worn_receipt": worn_receipt,
    "worn_letter": worn_letter,
    "worn_bottle": worn_bottle,
    "lantern_iron": lantern_iron,
}
