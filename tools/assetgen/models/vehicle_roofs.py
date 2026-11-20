"""
루프 장식 (the tuning shop's kind "roof") for the 초밥 카니발 (sushi_carnival): the NORMAL options, real-world roof
gear. Each option replaces the van's stock roof part (sushi_carnival_roof: the three cross bars, their clamps and the
long roof box, models/vehicles_donation_sushi_carnival.py) and is drawn exactly like it: in the BODY's space (the same
frame, scale and origin as the body model, a static mover at rest), standing on the same roof rails, bringing its own
cross bars / clamps / straps. Options are not painted with the body.

  sushi_carnival_roof_r_box_black   the stock box's shape in gloss black (graphite) on the stock bars
  sushi_carnival_roof_r_bars        the bare cross bars (rubber T-slot strips, end caps)
  sushi_carnival_roof_r_bikes       two bicycles upright on tray carriers, a frame arm and wheel straps each
  sushi_carnival_roof_r_ski         a ski & snowboard carrier (two clamp jaws) with two pairs of skis and a board
  sushi_carnival_roof_r_tent        a folded hard-shell rooftop tent, its telescopic ladder clipped along the side
  sushi_carnival_roof_r_kayak       a sit-in touring kayak on foam saddles, two cam straps over it
  sushi_carnival_roof_r_surf        two surfboards (deck down, fins up) on foam bar pads, strapped
  sushi_carnival_roof_r_basket      a steel roof basket with strapped luggage and a spare jerry can

The approved 16 px "angled panels" style (see vehicles_donation_muscle_car.py and the carnival module): big clean
boxes, slopes as 22.5/45-degree panels (plank()), detail painted in world-space decals, real 3D only where it shows.
What is thin in life (a bicycle's frame and wheels, a ladder's rungs, a basket's mesh floor, the fins) is a see-through
plane 0.5 px thick with the shape painted on it (see-through texels are only allowed on such planes). Everything stays
behind the windscreen's top (z >= -16: the screen meets the roof at z -19) and under the model's ceiling (drawn y 48).

Drawn coordinates as the carnival's: 16 px a block, the van's ground centre at (0, 0, 0), front = north (-z), +x the
van's right. The rails' top is y 31.5, the bars' top 32.25, the bars at z -14, 9, 29 (1.5 deep), x -13..13.

Imported by build.py like the other model modules (ALL).
"""

import math

from stsv_assets.bake import BModel, Paint
from stsv_assets import presets
from models import vehicles_donation_sushi_carnival as SC
from models.vehicles_donation_sushi_carnival import plank, tone, smooth, _x, _lo, _hi, _chrome, _ramp, CLEAR, T

RAIL_Y, BAR_Y, BARS = SC.RAIL_Y, SC.BAR_Y, SC.BARS
BODY = "sushi_carnival"

# ================================================================================================
# Ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

_ramp("rf_alu", 240, 0.010, 0.72, 0.44, 6, False)      # anodised aluminium (carriers, ladder, basket lock)
_ramp("rf_red", 27, 0.170, 0.54, 0.32, 6, False)       # a red bike frame, the suitcase, ski tops
_ramp("rf_teal", 195, 0.100, 0.58, 0.30, 6, False)     # a teal bike frame, ski tops
_ramp("rf_kayak", 62, 0.165, 0.72, 0.30, 6, False)     # the kayak: sunny yellow-orange
_ramp("rf_shell", 85, 0.020, 0.78, 0.30, 6, False)     # the tent's light warm-grey shell
_ramp("rf_fabric", 150, 0.030, 0.38, 0.20, 4, False)   # the tent's canvas band
_ramp("rf_olive", 122, 0.060, 0.47, 0.26, 5, False)    # the jerry can
_ramp("rf_navy", 262, 0.070, 0.36, 0.24, 5, False)     # the duffel bag, the snowboard
_ramp("rf_blue", 232, 0.110, 0.64, 0.30, 6, False)     # a surfboard
_ramp("rf_strap", 52, 0.160, 0.64, 0.22, 4, False)     # orange ratchet / cam straps
_ramp("rf_white", 95, 0.008, 0.90, 0.16, 4, False)     # white (a surfboard, ski tops)
_ramp("rf_foam", 250, 0.050, 0.34, 0.18, 4, False)     # foam pads and saddles: dark blue-grey
_ramp("rf_tarp", 210, 0.080, 0.52, 0.24, 5, False)     # the tarp bundle
_ramp("rf_coral", 38, 0.130, 0.70, 0.26, 5, False)     # a coral surfboard

BLACK = SC.BLACK


def _alu(lit=0.95, side=0.7, low=0.42):
    def d(c):
        if c.face == "up":
            return tone("rf_alu", lit)
        if c.face == "down":
            return tone("rf_alu", 0.2)
        return tone("rf_alu", side if c.p[1] > (c.lo[1] + c.hi[1]) / 2 else low)
    return Paint("rf_alu", 3, noise=0, grad=0, rim=0.35, ao=0.2, decal=d)


def _flat(ramp, up=0.8, side=0.55, down=0.2, **kw):
    """A plain part: lit on top, its sides a little darker."""
    return Paint(ramp, 2, noise=0, grad=0, rim=0.3, ao=0.3,
                 decal=lambda c: tone(ramp, up if c.face == "up" else down if c.face == "down" else side), **kw)


def _model(oid):
    m = BModel(f"{BODY}_roof_r_{oid}", shift=(8, 8, 8), scale=2.0)
    m.display = presets.furniture(gui_scale=0.4)
    m.group("roof")
    return m


def _seg(p, a, b):
    """Distance from p to the segment a-b (2D)."""
    (px, py), (ax, ay), (bx, by) = p, a, b
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy or 1)))
    return math.hypot(px - ax - t * dx, py - ay - t * dy)


def bars(m, top=None):
    """The three cross bars on the rails, clamped round them (as the stock part's); top: their top face's paint
    (default: a rubber T-slot strip down the middle)."""
    def bar(c):
        if c.face == "up":
            if top is not None:
                return top(c)
            return tone("sc_black", 0.05) if math.floor(c.p[2] * 2) % 3 == 1 else tone("sc_black", 0.5)
        return tone("sc_black", 0.3)
    bp = Paint("sc_black", 1, noise=0, grad=0, rim=0.3, ao=0.5, decal=bar)
    for zb in BARS:
        m.box("cross_bar", (-13, RAIL_Y, zb), (13, BAR_Y, zb + 1.5), bp)
        for s in (-1, 1):
            m.box("bar_clamp", _lo(s, 11.25, 13.25, RAIL_Y - 0.5, zb - 0.25), _hi(s, 11.25, 13.25, BAR_Y + 0.25, zb + 1.75), BLACK)


# ================================================================================================
# 1. box_black: the stock box in gloss black
# ================================================================================================

def box_black():
    m = _model("box_black")
    bars(m)
    SC._roof_box(m, {"B": "sc_box"})
    return m


# ================================================================================================
# 2. bars: bare cross bars
# ================================================================================================

def bars_only():
    m = _model("bars")
    bars(m)
    # end caps a little proud of the clamps (rounded at their outer end, painted)
    cap = Paint("sc_black", 1, noise=0, grad=0, rim=0.4, ao=0.2,
                decal=lambda c: tone("sc_black", 0.55 if c.face == "up" else 0.3))
    for zb in BARS:
        for s in (-1, 1):
            m.box("bar_cap", _lo(s, 13.25, 13.75, RAIL_Y - 0.25, zb - 0.1), _hi(s, 13.25, 13.75, BAR_Y + 0.1, zb + 1.6), cap)
    return m


# ================================================================================================
# 3. bikes: two bicycles on tray carriers
# ================================================================================================

TRAY_Y = BAR_Y + 0.75          # the trays' top: the tyres stand on it
WHEEL_R = 5                    # a 0.63-block wheel


def _bike_decal(zf, frame, tyre_y):
    """A bicycle drawn on a plane (side view, (z, y)): two wheels (tyre, rim, a few spokes, hub), the frame (a
    diamond: top, down and seat tubes, stays, fork), the chainring and crank. Clear elsewhere."""
    zr = zf + 17
    yc = tyre_y + WHEEL_R
    bb = (zr - 7, yc - 0.6)
    st = (bb[0] + 2, yc + 6.8)
    h1 = (zf + 3.2, yc + 6.6)
    h2 = (zf + 2.4, yc + 4.2)
    tubes = [(st, h1, 0.6), (bb, h2, 0.7), (bb, st, 0.6), (st, (zr, yc), 0.45), (bb, (zr, yc), 0.45),
             (h2, (zf, yc), 0.5), (h1, h2, 0.7)]

    def d(c):
        x, y, z = c.p
        q = (z, y)
        for hz in (zf, zr):
            r = math.hypot(z - hz, y - yc)
            if WHEEL_R - 1 <= r < WHEEL_R:
                return tone("sc_black", 0.12 if y < yc else 0.25)            # the tyre
            if WHEEL_R - 1.6 <= r < WHEEL_R - 1:
                return tone("rf_alu", 0.85 if y > yc else 0.6)              # the rim
            if r < 0.8:
                return tone("rf_alu", 0.9)                                  # the hub
            if r < WHEEL_R - 1.6 and (abs(z - hz) < 0.35 or abs(y - yc) < 0.35):
                return ("raw", (150, 156, 166, 255))                         # spokes
        for a, b, w in tubes:
            if _seg(q, a, b) < w:
                return tone(frame, 0.85 if y > a[1] - 0.1 and y > b[1] - 0.1 else 0.6)
        r = math.hypot(z - bb[0], y - bb[1])
        if r < 1.6:
            return tone("rf_alu", 0.8) if r > 1.0 else tone("sc_black", 0.2)  # the chainring
        if _seg(q, (bb[0] - 1.6, bb[1] - 1.4), (bb[0] + 1.6, bb[1] + 1.4)) < 0.45:
            return tone("sc_black", 0.35)                                   # the crank
        return CLEAR
    return d, dict(zr=zr, yc=yc, bb=bb, st=st, h1=h1)


def _bike(m, xc, zf, frame, side):
    """One bicycle in the plane x = xc, its front wheel's hub at z = zf; side: which way the van's middle is (-1/1)."""
    d, k = _bike_decal(zf, frame, TRAY_Y)
    zr, yc, st, h1, bb = k["zr"], k["yc"], k["st"], k["h1"], k["bb"]
    m.box("bike", (xc - 0.25, TRAY_Y, zf - WHEEL_R - 0.25), (xc + 0.25, st[1] + 0.5, zr + WHEEL_R + 0.25),
          Paint("sc_black", 1, noise=0, light=False, decal=d))
    # real 3D where it shows: the saddle on its post, the stem and the handlebar with its grips, the pedals
    dark = _flat("sc_black", 0.45, 0.25)
    m.box("seat_post", (xc - 0.3, st[1] + 0.5, st[0] - 0.3), (xc + 0.3, st[1] + 1.5, st[0] + 0.3), _alu())
    m.box("saddle", (xc - 0.8, st[1] + 1.5, st[0] - 1.2), (xc + 0.8, st[1] + 2.2, st[0] + 2.2), dark)
    m.box("stem", (xc - 0.3, h1[1] + 0.5, h1[0] - 0.3), (xc + 0.3, h1[1] + 1.6, h1[0] + 0.3), _alu())
    m.box("handlebar", (xc - 3, h1[1] + 1.6, h1[0] - 0.9), (xc + 3, h1[1] + 2.1, h1[0] - 0.3), _flat("sc_black", 0.5, 0.3))
    for s in (-1, 1):
        g0, g1 = sorted((xc + s * 2.4, xc + s * 3.4))
        m.box("grip", (g0, h1[1] + 1.5, h1[0] - 1), (g1, h1[1] + 2.2, h1[0] - 0.2), _flat(frame, 0.6, 0.4))
    # the pedals at the crank's two ends (the outer one low and forward, the inner one high and back)
    lo_end, hi_end = (bb[0] - 1.6, bb[1] - 1.4), (bb[0] + 1.6, bb[1] + 1.4)
    for s, (pz, py) in ((-side, lo_end), (side, hi_end)):
        m.box("pedal", (min(xc + s * 0.25, xc + s * 1.75), py - 0.25, pz - 0.75), (max(xc + s * 0.25, xc + s * 1.75), py + 0.25, pz + 0.75), dark)
    return k


def bikes():
    m = _model("bikes")
    bars(m)
    zf = -5
    for xc, frame in ((-6, "rf_red"), (6, "rf_teal")):
        side = 1 if xc < 0 else -1                                    # towards the middle
        m.box("tray", (xc - 0.9, BAR_Y, -11), (xc + 0.9, TRAY_Y, 24), _alu(0.9, 0.62, 0.4))
        m.box("tray_cap", (xc - 1.1, BAR_Y - 0.25, -11.75), (xc + 1.1, TRAY_Y + 0.25, -11), BLACK)
        m.box("tray_cap", (xc - 1.1, BAR_Y - 0.25, 24), (xc + 1.1, TRAY_Y + 0.25, 24.75), BLACK)
        k = _bike(m, xc, zf, frame, side)
        cup = _flat("sc_black", 0.45, 0.22)
        for wz in (zf, k["zr"]):
            # a wheel cup each side of the tyre's foot, an orange strap over the rim
            m.box("wheel_cup", (xc - 0.9, TRAY_Y, wz - 2.25), (xc - 0.25, TRAY_Y + 1.25, wz + 2.25), cup)
            m.box("wheel_cup", (xc + 0.25, TRAY_Y, wz - 2.25), (xc + 0.9, TRAY_Y + 1.25, wz + 2.25), cup)
            m.box("wheel_strap", (xc - 0.5, TRAY_Y + 1.25, wz - 2.6), (xc + 0.5, TRAY_Y + 2.25, wz - 2.25), _flat("rf_strap", 0.8, 0.6))
        # the frame arm: up from the tray beside the down tube's middle, a jaw round the tube
        mz, my = zf + 6.2, k["yc"] + 1.8
        a0, a1 = sorted((xc + side * 0.3, xc + side * 1.1))
        m.box("arm", (a0, TRAY_Y, mz - 0.4), (a1, my - 0.8, mz + 0.4), _alu(0.8, 0.6, 0.45))
        m.box("arm_jaw", (xc - 0.9, my - 0.8, mz - 0.9), (xc + 0.9, my + 0.8, mz + 0.9), _flat("sc_black", 0.55, 0.3))
    return m


# ================================================================================================
# 4. ski: a ski & snowboard carrier
# ================================================================================================

SKI_Y = BAR_Y + 1.0            # the skis' bases (they lie on the lower jaw's rubber)
SKI_Z = (-13, 15)              # their ends (flat part); the tips curl up ahead of SKI_Z[0]


def _ski(m, x0, x1, top, z0, z1, stripe, t=0.5, tip=3.0):
    """A ski (or a board, wide): a flat strip x0..x1 from z0 to z1 at SKI_Y, base dark, the top in its colours, the
    front tip turned up 22.5 degrees (a see-through panel: its corners rounded) and a small kick at the tail."""
    def top_decal(c):
        x, y, z = c.p
        if c.face == "down":
            return tone("sc_black", 0.1)
        if c.face != "up":
            return tone("sc_black", 0.35)                             # the edges
        u = (x - x0) / (x1 - x0)
        if 0.35 <= u <= 0.65 and stripe:
            return tone(stripe, 0.85)
        return tone(top, 0.75 if math.floor(z) % 9 else 0.55)
    p = Paint(top, 2, noise=0, grad=0, rim=0.2, ao=0.2, decal=top_decal)
    m.box("ski", (x0, SKI_Y, z0), (x1, SKI_Y + t, z1), p)
    w = (x1 - x0) / 2
    xm = (x0 + x1) / 2

    def tip_decal(c):
        x, y, z = c.p
        # rounded: clear beyond a half-disc at the tip's far end (the tip runs from z0 forward)
        if c.face in ("up",) or c.face in ("north", "south", "east", "west", "down"):
            pass
        return top_decal(c) if c.face == "up" else tone("sc_black", 0.2)
    # the tip: rising 22.5 degrees ahead of z0
    rise = tip * T
    plank(m, "ski_tip", "x", (z0 - tip, SKI_Y + t + rise), (z0, SKI_Y + t), (x0 + 0.05, x1 - 0.05), min(t, 0.5),
          Paint(top, 2, noise=0, grad=0, rim=0.2, ao=0.2), decal=tip_decal)
    # the tail's small kick
    plank(m, "ski_tail", "x", (z1, SKI_Y + t), (z1 + 1.5, SKI_Y + t + 1.5 * T), (x0 + 0.05, x1 - 0.05), min(t, 0.5),
          Paint(top, 2, noise=0, grad=0, rim=0.2, ao=0.2), decal=tip_decal)


def ski():
    m = _model("ski")
    bars(m)
    # the two carriers (front and middle bar): a lower jaw with a rubber top, an upper jaw hinged over the skis,
    # a lock at the right end
    jaw = Paint("sc_black", 1, noise=0, grad=0, rim=0.35, ao=0.3,
                decal=lambda c: tone("sc_black", 0.08 if c.face == "up" and math.floor(c.p[0]) % 2 else 0.4 if c.face == "up" else 0.25))
    for zb in BARS[:2]:
        z0, z1 = zb - 0.75, zb + 2.25
        m.box("jaw_low", (-11.5, BAR_Y, z0), (11.5, SKI_Y, z1), jaw)
        m.box("jaw_top", (-11.5, SKI_Y + 1.25, z0), (11.5, SKI_Y + 2.25, z1), jaw)
        m.box("jaw_hinge", (-12.5, BAR_Y, z0), (-11.5, SKI_Y + 2.25, z1), _flat("sc_black", 0.5, 0.3))
        m.box("jaw_lock", (11.5, BAR_Y, z0), (12.75, SKI_Y + 2.25, z1), _alu(0.9, 0.7, 0.5))
    # two pairs of skis (each pair base to base would be taller: here side by side, tops up) and a snowboard
    _ski(m, -10.75, -9.25, "rf_red", SKI_Z[0], SKI_Z[1], "rf_white")
    _ski(m, -8.75, -7.25, "rf_red", SKI_Z[0], SKI_Z[1], "rf_white")
    _ski(m, -5.75, -4.25, "rf_white", SKI_Z[0] + 1, SKI_Z[1] + 1, "rf_teal")
    _ski(m, -3.75, -2.25, "rf_white", SKI_Z[0] + 1, SKI_Z[1] + 1, "rf_teal")
    # the board: wider, both ends turned up, its bindings up (between the jaws)
    bx0, bx1 = 0.5, 6.5
    _ski(m, bx0, bx1, "rf_navy", -12.5, 12.5, "rf_strap", tip=2.5)
    bind = _flat("sc_black", 0.55, 0.3)
    for bz in (-5.5, 3):
        m.box("binding", (bx0 + 0.5, SKI_Y + 0.5, bz), (bx1 - 0.5, SKI_Y + 1.0, bz + 3), bind)
        m.box("binding_back", (bx0 + 0.75, SKI_Y + 1.0, bz + 2.25), (bx1 - 0.75, SKI_Y + 2.0, bz + 3), bind)
        m.box("binding_strap", (bx0 + 0.75, SKI_Y + 1.0, bz + 0.5), (bx1 - 0.75, SKI_Y + 1.5, bz + 1.25), _flat("rf_strap", 0.8, 0.55))
    # ski poles: a pair laid along beside the skis, their baskets at the back
    pole = _alu(0.9, 0.7, 0.5)
    for px in (7.75, 8.75):
        m.box("pole", (px, SKI_Y, -14), (px + 0.5, SKI_Y + 0.5, 12), pole)
        m.box("pole_grip", (px - 0.1, SKI_Y, -15.5), (px + 0.6, SKI_Y + 0.7, -13), _flat("sc_black", 0.5, 0.3))
        m.box("pole_basket", (px - 0.25, SKI_Y, 10.5), (px + 0.75, SKI_Y + 0.75, 11.5), _flat("sc_black", 0.5, 0.3))
    return m


# ================================================================================================
# 5. tent: a folded hard-shell rooftop tent, its ladder clipped along the side
# ================================================================================================

TX, TZ0, TZ1 = 11, -13, 26      # the tent's half width, its front and back
T_BASE, T_FAB, T_SHELL = BAR_Y + 0.75, BAR_Y + 2.5, BAR_Y + 3.5   # the base tray's foot, its top, the canvas band's top
T_TOP = T_SHELL + 3             # the shell's crown


def tent():
    m = _model("tent")
    bars(m)
    # brackets on the bars
    for zb in BARS:
        if TZ0 < zb < TZ1:
            for s in (-1, 1):
                m.box("bracket", _lo(s, 7, 9.5, BAR_Y, zb - 0.5), _hi(s, 7, 9.5, T_BASE, zb + 2), BLACK)
    # the base tray: dark grey, a lit lip along its top
    base = Paint("sc_black", 2, noise=0, grad=0, rim=0.3, ao=0.3,
                 decal=lambda c: tone("sc_black", 0.62) if c.face != "down" and c.p[1] > T_FAB - 0.5 else
                 tone("sc_black", 0.1) if c.face == "down" else tone("sc_black", 0.4))
    m.box("tent_base", (-TX, T_BASE, TZ0), (TX, T_FAB, TZ1), base)

    # the canvas band, a hair in, zips painted
    def fab(c):
        if c.face in ("east", "west") and math.floor(c.p[2]) % 6 == 0:
            return tone("rf_fabric", 0.15)
        return tone("rf_fabric", 0.55 if c.face == "up" else 0.4)
    m.box("tent_canvas", (-(TX - 0.5), T_FAB, TZ0 + 0.5), (TX - 0.5, T_SHELL, TZ1 - 0.5), Paint("rf_fabric", 2, noise=0, grad=0, rim=0, ao=0.2, decal=fab))

    # the shell: a light warm grey lid, a 22.5-degree nose sloping to the front, the long top edges chamfered 45
    def shell(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face == "down":
            return tone("rf_shell", 0.2)
        if c.face == "up":
            if 2.5 <= ax < 3.25:
                return tone("rf_shell", 0.55)                             # two moulded ribs along the lid
            if 3.25 <= ax < 3.75:
                return tone("rf_shell", 0.95)
            return tone("rf_shell", 0.82 if -4 < x < -1 else 0.72)
        if c.face in ("east", "west"):
            if abs(z - 0) < 0.75 or abs(z - 17) < 0.75:
                return tone("sc_black", 0.3)                              # the latches
            return tone("rf_shell", 0.45 + 0.25 * smooth((y - T_SHELL) / 3))
        return tone("rf_shell", 0.55 + 0.2 * smooth((y - T_SHELL) / 3))
    sp = Paint("rf_shell", 3, noise=0, grad=0, rim=0, ao=0, decal=shell)
    nose = (T_TOP - T_SHELL - 0.5) / T                                    # the nose's run (22.5 degrees)
    ch = 1.25                                                             # the 45-degree chamfer
    m.box("shell", (-TX, T_SHELL, TZ0 + nose), (TX, T_TOP - ch, TZ1), sp)
    m.box("shell_crown", (-(TX - ch), T_TOP - ch, TZ0 + nose), (TX - ch, T_TOP, TZ1), sp)
    m.box("shell_nose", (-TX, T_SHELL, TZ0), (TX, T_SHELL + 0.5, TZ0 + nose), sp)
    for s in (-1, 1):
        plank(m, "shell_edge", "z", (s * (TX - ch), T_TOP), (s * TX, T_TOP - ch), (TZ0 + nose, TZ1), 0.8, sp, decal=shell)
    plank(m, "shell_nose_top", "x", (TZ0, T_SHELL + 0.5), (TZ0 + nose, T_TOP), (-(TX - ch), TX - ch), 1.2, sp, decal=shell)
    for s in (-1, 1):
        plank(m, "shell_nose_side", "x", (TZ0, T_SHELL + 0.5), (TZ0 + nose * (T_TOP - ch - T_SHELL - 0.5) / (T_TOP - T_SHELL - 0.5),
              T_TOP - ch), _x(s, TX - ch, TX), 1.2, sp, decal=shell)
    # the hinge rail along the back, the handle strap at the front
    m.box("hinge", (-(TX - 1), T_SHELL - 0.5, TZ1), (TX - 1, T_SHELL + 1, TZ1 + 0.5), _flat("sc_black", 0.5, 0.3))
    m.box("pull_strap", (-1.5, T_FAB - 0.5, TZ0 - 0.25), (1.5, T_SHELL + 0.75, TZ0), _flat("rf_strap", 0.8, 0.6))

    # the telescopic ladder, folded, clipped along the right side: two aluminium stiles, the rungs between them on
    # a see-through plane, two black clips over it
    lx = TX + 0.25
    ly0, ly1 = T_BASE + 0.25, T_SHELL + 1.75
    lz0, lz1 = -6, 17
    stile = _alu(0.95, 0.75, 0.5)
    m.box("ladder_stile", (lx, ly0, lz0), (lx + 0.75, ly0 + 0.75, lz1), stile)
    m.box("ladder_stile", (lx, ly1 - 0.75, lz0), (lx + 0.75, ly1, lz1), stile)

    def rungs(c):
        z = c.p[2]
        if (math.floor(z - lz0) % 3) == 1:
            return tone("rf_alu", 0.8 if c.face != "down" else 0.4)
        return CLEAR
    m.box("ladder_rungs", (lx + 0.15, ly0 + 0.75, lz0 + 0.5), (lx + 0.6, ly1 - 0.75, lz1 - 0.5),
          Paint("rf_alu", 3, noise=0, light=False, decal=rungs))
    m.box("ladder_foot", (lx - 0.1, ly0 - 0.1, lz1), (lx + 0.85, ly1 + 0.1, lz1 + 0.75), BLACK)
    for cz in (-3, 13):
        m.box("ladder_clip", (lx - 0.25, ly0 - 0.25, cz), (lx + 1.0, ly1 + 0.25, cz + 1.25), _flat("sc_black", 0.5, 0.28))
    return m


# ================================================================================================
# 6. kayak: a sit-in kayak on foam saddles, strapped
# ================================================================================================

K_Y0 = BAR_Y + 1.25            # the keel's bottom (on the saddles)
K_W = 5                        # half beam
K_KEEL = 3.5                   # the keel strip's half width (45-degree bilges out to K_W)
K_SIDE = K_Y0 + 1.5            # the sides from here ...
K_DECK = K_Y0 + 4.25           # ... up to here; the deck's crown over it
K_CROWN = 3                    # the crown's half width (22.5-degree shoulders out to K_W)
K_TOP = K_DECK + (K_W - K_CROWN) * T
KZ0, KZ1 = -1, 25              # the full-beam part
K_END = K_W / T                # each end's run (22.5 degrees in plan): about 12 px


def _kayak_decal(c):
    x, y, z = c.p
    ax = abs(x)
    f = c.face
    if f == "down":
        return tone("rf_kayak", 0.15)
    if y < K_SIDE - 0.2:
        return tone("rf_kayak", 0.35)                                     # the hull's bottom, in its own shade
    if f == "up" or y > K_DECK - 0.3:
        # the deck: lighter, deck lines (black bungees) fore and aft of the cockpit
        if f == "up" and ax < 2.4 and (math.floor(z * 2) in (-9, -3, 3, 45, 51, 57) or (ax < 0.25 and (-5 < z < 2 or 22 < z < 29))):
            return tone("sc_black", 0.2)                                  # the deck lines (bungees) fore and aft
        return tone("rf_kayak", 0.92 if ax < K_CROWN else 0.8)
    if math.floor(y) == math.floor(K_DECK - 1):
        return tone("rf_kayak", 0.95)                                     # the sheer line catching the light
    return tone("rf_kayak", 0.55 + 0.2 * smooth((y - K_SIDE) / 2.5))


def kayak():
    m = _model("kayak")
    bars(m)
    hull = Paint("rf_kayak", 3, noise=0, grad=0, rim=0, ao=0, decal=_kayak_decal)
    # foam saddles on the bars it lies on (front tip and tail overhang the end bars)
    sad = _flat("rf_foam", 0.6, 0.4)
    for zb in BARS[1:]:
        for s in (-1, 1):
            m.box("saddle", _lo(s, 1.5, 4.5, BAR_Y, zb - 1), _hi(s, 1.5, 4.5, K_Y0 + 0.5, zb + 2.5), sad)
    # the full-beam middle: the keel strip, the 45-degree bilges, the sides, the crown and its 22.5-degree shoulders
    m.box("keel", (-K_KEEL, K_Y0, KZ0), (K_KEEL, K_SIDE, KZ1), hull)
    m.box("hull", (-K_W, K_SIDE, KZ0), (K_W, K_DECK, KZ1), hull)
    m.box("deck", (-K_CROWN, K_DECK, KZ0), (K_CROWN, K_TOP, KZ1), hull)
    for s in (-1, 1):
        plank(m, "bilge", "z", (s * K_KEEL, K_Y0), (s * K_W, K_SIDE), (KZ0, KZ1), 0.7, hull, decal=_kayak_decal, below=False)
        plank(m, "shoulder", "z", (s * K_CROWN, K_TOP), (s * K_W, K_DECK), (KZ0, KZ1), 0.8, hull, decal=_kayak_decal)
    # the ends: the sides turned 22.5 degrees in plan to a point, a see-through deck plane over them (the triangle)
    ye0, ye1 = K_SIDE - 0.25, K_DECK
    for zbase, e in ((KZ0, -1), (KZ1, 1)):
        ztip = zbase + e * K_END
        for s in (-1, 1):
            plank(m, "end_side", "y", (ztip, 0), (zbase, s * K_W), (ye0, ye1), 0.7, hull, decal=_kayak_decal,
                  below=(s * e) < 0)

        def end_deck(c, zbase=zbase, ztip=ztip):
            x, y, z = c.p
            half = K_W * (ztip - z) / (ztip - zbase)
            if abs(x) <= half + 0.05:
                return _kayak_decal(c) if c.face == "up" else tone("rf_kayak", 0.3)
            return CLEAR
        zz = sorted((zbase, ztip))
        m.box("end_deck", (-K_W, ye1, zz[0]), (K_W, ye1 + 0.5, zz[1]), Paint("rf_kayak", 3, noise=0, light=False, decal=end_deck))

        def end_floor(c, zbase=zbase, ztip=ztip):
            x, y, z = c.p
            half = K_W * (ztip - z) / (ztip - zbase)
            return tone("rf_kayak", 0.2) if abs(x) <= half + 0.05 else CLEAR
        m.box("end_floor", (-K_W, ye0 - 0.5, zz[0]), (K_W, ye0, zz[1]), Paint("rf_kayak", 3, noise=0, light=False, decal=end_floor))
        # a toggle handle at the tip
        m.box("toggle", (-0.5, ye1 - 0.5, ztip - (0.75 if e < 0 else 0)), (0.5, ye1 + 0.75, ztip + (0.75 if e > 0 else 0)), BLACK)

    # the cockpit: a raised coaming round a dark opening, the seat back in it
    cz0, cz1 = 7, 17

    def coaming(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 2.1 and cz0 + 1.2 < z < cz1 - 1:
                if z > cz1 - 3 and abs(x) < 1.6:
                    return tone("rf_foam", 0.5)                           # the seat back
                return tone("sc_black", 0.05)                             # the opening
            return tone("sc_black", 0.45)
        return tone("sc_black", 0.3)
    m.box("coaming", (-2.75, K_TOP, cz0), (2.75, K_TOP + 0.6, cz1), Paint("sc_black", 1, noise=0, grad=0, rim=0.3, ao=0.2, decal=coaming))
    # two cam straps over the hull to the bars, a buckle on the right
    strap = _flat("rf_strap", 0.85, 0.6)
    for zb in BARS[1:]:
        z0, z1 = zb + 2.6, zb + 3.6
        m.box("strap", (-K_CROWN, K_TOP + 0.01, z0), (K_CROWN, K_TOP + 0.26, z1), strap)
        for s in (-1, 1):
            m.box("strap", _lo(s, K_W + 0.01, K_W + 0.26, BAR_Y - 0.25, z0), _hi(s, K_W + 0.01, K_W + 0.26, K_DECK, z1), strap)
            plank(m, "strap", "z", (s * K_CROWN, K_TOP + 0.26), (s * K_W, K_DECK + 0.26), (z0, z1), 0.25, strap)
            m.box("strap_foot", _lo(s, K_W, 7.5, BAR_Y - 0.25, z0), _hi(s, K_W, 7.5, BAR_Y, z1), strap)
        m.box("buckle", (K_W + 0.26, K_SIDE - 0.5, z0 - 0.25), (K_W + 0.6, K_SIDE + 0.75, z1 + 0.25), _alu(0.95, 0.8, 0.6))
    return m


# ================================================================================================
# 7. surf: two surfboards on foam pads
# ================================================================================================

SB_Y = BAR_Y + 1.0             # the boards' (deck-down) top face ... they lie on the pads
SB_Z0, SB_Z1 = -15, 19         # tail (forward) and where the nose's rocker starts (back)
SB_NOSE = 6                    # the nose's run, turned up 22.5 degrees


def _board_half(u):
    """A shortboard's outline: half width (px) at u = 0 (the tail) .. 1 (the nose's point)."""
    if u < 0.0 or u > 1.0:
        return -1
    w = 4.4 * math.sin(math.pi * min(1.0, 0.18 + u * 0.95)) ** 0.7
    return max(w, 1.6 if u < 0.05 else 0)


def surf():
    m = _model("surf")
    bars(m)
    L = (SB_Z1 - SB_Z0) + SB_NOSE * math.cos(math.radians(22.5))
    # foam pads round the front and middle bars
    pad = Paint("rf_foam", 2, noise=0, grad=0, rim=0.3, ao=0.3,
                decal=lambda c: tone("rf_foam", 0.35 if math.floor(c.p[0]) % 3 == 0 and c.face == "up" else 0.6 if c.face == "up" else 0.4))
    for zb in BARS[:2]:
        m.box("pad", (-11, RAIL_Y - 0.5, zb - 0.75), (11, SB_Y - 0.5, zb + 2.25), pad)
    for xc, base, rail in ((-5.25, "rf_blue", "rf_white"), (5.25, "rf_coral", "rf_white")):
        def outline(c, xc=xc, base=base, rail=rail):
            x, y, z = c.p
            # u along the board from the tail (the nose's rocker unrolled: z past SB_Z1 counts 1/cos 22.5)
            zz = z if z <= SB_Z1 else SB_Z1 + (z - SB_Z1) / math.cos(math.radians(22.5))
            u = (zz - SB_Z0) / L
            half = _board_half(u)
            dx = abs(x - xc)
            if dx > half:
                return CLEAR
            if c.face != "up":
                return tone(base, 0.4)
            if dx > half - 0.75:
                return tone(rail, 0.75)                                    # the rail band
            if dx < 0.3:
                return tone("rf_shell", 0.5)                              # the stringer
            return tone(base, 0.8 if x < xc else 0.7)
        bp = Paint(base, 3, noise=0, light=False, decal=outline)
        m.box("board", (xc - 4.6, SB_Y - 0.5, SB_Z0), (xc + 4.6, SB_Y, SB_Z1), bp)
        plank(m, "board_nose", "x", (SB_Z1, SB_Y), (SB_Z1 + SB_NOSE * math.cos(math.radians(22.5)), SB_Y + SB_NOSE * math.sin(math.radians(22.5))),
              (xc - 4.6, xc + 4.6), 0.5, Paint(base, 3, noise=0, light=False), decal=outline)
        # a core under the middle (the board's thickness, seen from the side)
        m.box("board_core", (xc - 3.2, SB_Y - 1.0, SB_Z0 + 4), (xc + 3.2, SB_Y - 0.5, SB_Z1 - 3), _flat(base, 0.6, 0.45))
        # three fins near the tail (forward), standing up: see-through planes with a swept fin painted

        for fx, fz0 in ((xc, SB_Z0 + 1.5), (xc - 2.6, SB_Z0 + 4), (xc + 2.6, SB_Z0 + 4)):
            m.box("fin", (fx - 0.25, SB_Y, fz0), (fx + 0.25, SB_Y + 2.25, fz0 + 3),
                  Paint("sc_black", 1, noise=0, light=False, decal=lambda c, fz0=fz0: fin_at(c, fz0)))
    # two cam straps round both boards and the bars (dark grey webbing, a bright buckle)
    strap = _flat("sc_black", 0.55, 0.4)
    for zb in BARS[:2]:
        z0, z1 = zb + 2.4, zb + 3.2
        m.box("strap", (-10.25, SB_Y + 0.01, z0), (10.25, SB_Y + 0.26, z1), strap)
        for s in (-1, 1):
            m.box("strap", _lo(s, 10.25, 10.5, RAIL_Y - 0.5, z0), _hi(s, 10.25, 10.5, SB_Y + 0.26, z1), strap)
        m.box("buckle", (-1, SB_Y + 0.26, z0 - 0.25), (1, SB_Y + 0.6, z1 + 0.25), _alu(0.95, 0.8, 0.6))
    return m


def fin_at(c, fz0):
    x, y, z = c.p
    h = y - SB_Y
    fz = z - fz0
    if 0 <= h <= 2.25 and 0 <= fz <= 3 - h * 0.9:
        return tone("sc_black", 0.4 if c.face in ("east", "west") else 0.2)
    return CLEAR


# ================================================================================================
# 8. basket: a roof basket with luggage and a jerry can
# ================================================================================================

BK_X, BK_Z0, BK_Z1 = 11, -13, 25          # the basket's half width, its front (the fairing ahead of it) and back
BK_Y0 = BAR_Y + 0.25                       # the mesh floor
BK_Y1 = BK_Y0 + 3                          # the top rail


def _soft(ramp, lit=0.8, side=0.55):
    """A soft bag: lit on top, its sides darker towards the bottom, a seam round its middle."""
    def d(c):
        x, y, z = c.p
        if c.face == "up":
            return tone(ramp, lit)
        if c.face == "down":
            return tone(ramp, 0.1)
        h = (y - c.lo[1]) / max(0.01, c.hi[1] - c.lo[1])
        if abs(h - 0.5) < 0.12:
            return tone(ramp, side - 0.25)
        return tone(ramp, side - 0.15 + 0.25 * h)
    return Paint(ramp, 2, noise=0, grad=0, rim=0.25, ao=0.3, decal=d)


def _bevelled(m, name, x0, x1, y0, y1, z0, z1, paint, b=1.0):
    """A box with its two long top edges (along z) bevelled at 45 degrees."""
    m.box(name, (x0, y0, z0), (x1, y1 - b, z1), paint)
    m.box(name, (x0 + b, y1 - b, z0), (x1 - b, y1, z1), paint)
    plank(m, name, "z", (x0 + b, y1), (x0, y1 - b), (z0, z1), 0.6, paint, decal=paint.decal)
    plank(m, name, "z", (x1 - b, y1), (x1, y1 - b), (z0, z1), 0.6, paint, decal=paint.decal)


def basket():
    m = _model("basket")
    bars(m)
    tube = Paint("sc_black", 1, noise=0, grad=0, rim=0.45, ao=0.2,
                 decal=lambda c: tone("sc_black", 0.6) if c.face == "up" else tone("sc_black", 0.3))

    # the mesh floor: a see-through plane with the bars painted on it, the frame's lower tubes round it
    def mesh(c):
        x, y, z = c.p
        if math.floor(x * 2) % 6 == 0 or math.floor(z * 2) % 8 == 0:
            return tone("sc_black", 0.45 if c.face == "up" else 0.2)
        return CLEAR
    m.box("floor", (-BK_X, BK_Y0, BK_Z0), (BK_X, BK_Y0 + 0.5, BK_Z1 - 0.75), Paint("sc_black", 1, noise=0, light=False, decal=mesh))
    for s in (-1, 1):
        m.box("rail_low", _lo(s, BK_X, BK_X + 0.75, BK_Y0, BK_Z0), _hi(s, BK_X, BK_X + 0.75, BK_Y0 + 0.75, BK_Z1), tube)
        m.box("rail_top", _lo(s, BK_X, BK_X + 0.75, BK_Y1, BK_Z0), _hi(s, BK_X, BK_X + 0.75, BK_Y1 + 0.75, BK_Z1), tube)
        for pz in (BK_Z0, -4, 6, 16, BK_Z1 - 0.75):
            m.box("post", _lo(s, BK_X, BK_X + 0.75, BK_Y0 + 0.75, pz), _hi(s, BK_X, BK_X + 0.75, BK_Y1, pz + 0.75), tube)
    m.box("rail_back", (-BK_X, BK_Y1, BK_Z1 - 0.75), (BK_X, BK_Y1 + 0.75, BK_Z1), tube)
    m.box("rail_back_low", (-BK_X, BK_Y0, BK_Z1 - 0.75), (BK_X, BK_Y0 + 0.75, BK_Z1), tube)
    # the wind fairing at the front: a 45-degree panel from the floor up to the top rail
    fair = Paint("sc_black", 1, noise=0, grad=0, rim=0.3, ao=0.2,
                 decal=lambda c: tone("sc_black", 0.55 if c.face == "up" and math.floor(c.p[0]) % 4 else 0.35))
    h = BK_Y1 + 0.75 - BK_Y0
    plank(m, "fairing", "x", (BK_Z0 - h + 0.75, BK_Y0), (BK_Z0 + 0.75, BK_Y1 + 0.75), (-(BK_X + 0.75), BK_X + 0.75), 0.6, fair, below=False)

    # the luggage: a navy duffel (bevelled), a red hard case lying flat, a blue tarp bundle, an olive jerry can
    _bevelled(m, "duffel", -9.75, -1.5, BK_Y0 + 0.5, BK_Y0 + 5, -10, 4, _soft("rf_navy"), b=1.5)
    for e in (-1, 1):
        m.box("duffel_end", (-8.5, BK_Y0 + 1.5, (-10 if e < 0 else 4) - (0.25 if e < 0 else 0)),
              (-2.75, BK_Y0 + 3.5, (-10 if e < 0 else 4) + (0.25 if e > 0 else 0)), _flat("sc_black", 0.4, 0.25))

    def case(c):
        x, y, z = c.p
        if c.face == "up":
            if math.floor(x) % 3 == 0:
                return tone("rf_red", 0.55)                               # the moulded ribs
            return tone("rf_red", 0.8)
        if abs(y - (BK_Y0 + 2.25)) < 0.3:
            return tone("sc_black", 0.3)                                  # the zip line
        return tone("rf_red", 0.5)
    m.box("suitcase", (0, BK_Y0 + 0.5, -11), (9.5, BK_Y0 + 4, 2.5), Paint("rf_red", 3, noise=0, grad=0, rim=0.3, ao=0.3, decal=case))
    m.box("case_handle", (3.5, BK_Y0 + 4, -5.5), (6, BK_Y0 + 4.5, -4.75), _flat("sc_black", 0.5, 0.3))
    _bevelled(m, "tarp", -9.75, 4.5, BK_Y0 + 0.5, BK_Y0 + 4.25, 6, 22, _soft("rf_tarp", 0.75, 0.6), b=1.25)

    # the jerry can: upright at the back right, its X pressed into the sides, three handles on top, the spout
    jx0, jx1, jy0, jy1, jz0, jz1 = 6, 9.5, BK_Y0 + 0.5, BK_Y0 + 8, 15.5, 21.5

    def jerry(c):
        x, y, z = c.p
        if c.face == "up":
            return tone("rf_olive", 0.75)
        if c.face in ("east", "west"):
            u, v = (z - jz0) / (jz1 - jz0), (y - jy0) / (jy1 - jy0)
            if 0.12 < u < 0.88 and 0.1 < v < 0.9:
                if abs(u - v) < 0.07 or abs(u - (1 - v)) < 0.07:
                    return tone("rf_olive", 0.85)                         # the X, catching the light
                if abs(u - v) < 0.14 or abs(u - (1 - v)) < 0.14:
                    return tone("rf_olive", 0.3)
            if u <= 0.12 or u >= 0.88 or v <= 0.1 or v >= 0.9:
                return tone("rf_olive", 0.62)                             # the rim round the panel
            return tone("rf_olive", 0.5)
        return tone("rf_olive", 0.45 + 0.2 * smooth((y - jy0) / (jy1 - jy0)))
    m.box("jerry_can", (jx0, jy0, jz0), (jx1, jy1, jz1), Paint("rf_olive", 2, noise=0, grad=0, rim=0.3, ao=0.3, decal=jerry))
    for hz in (16.5, 18.25, 20):
        m.box("jerry_handle", (jx0 + 0.75, jy1, hz), (jx1 - 0.75, jy1 + 1.25, hz + 0.6), _flat("rf_olive", 0.8, 0.55))
    m.box("jerry_spout", (jx0 + 0.75, jy1 - 1.25, jz0 - 1), (jx1 - 0.75, jy1 + 0.25, jz0), _flat("rf_olive", 0.75, 0.5))
    m.box("jerry_cap", (jx0 + 1, jy1 - 1, jz0 - 1.5), (jx1 - 1, jy1, jz0 - 1), _flat("sc_black", 0.5, 0.3))

    # two orange ratchet straps across the luggage, hooked to the side rails
    strap = _flat("rf_strap", 0.85, 0.6)
    for z0 in (-4.5, 13):
        z1 = z0 + 1
        tops = [(-9.75, -1.5, BK_Y0 + 5), (0, 9.5, BK_Y0 + 4)] if z0 < 0 else [(-9.75, 4.5, BK_Y0 + 4.25)]
        for a, b, y in tops:
            m.box("strap", (a, y + 0.01, z0), (b, y + 0.26, z1), strap)
        m.box("strap", (-(BK_X - 0.01), BK_Y0 + 0.75, z0), (-9.76, BK_Y1 + 0.75, z1), strap)
        m.box("ratchet", (-BK_X + 0.25, BK_Y0 + 2, z0 - 0.25), (-9.5, BK_Y0 + 3.5, z1 + 0.25), _alu(0.95, 0.75, 0.55))
    return m


ALL = {
    f"{BODY}_roof_r_box_black": box_black,
    f"{BODY}_roof_r_bars": bars_only,
    f"{BODY}_roof_r_bikes": bikes,
    f"{BODY}_roof_r_ski": ski,
    f"{BODY}_roof_r_tent": tent,
    f"{BODY}_roof_r_kayak": kayak,
    f"{BODY}_roof_r_surf": surf,
    f"{BODY}_roof_r_basket": basket,
}
