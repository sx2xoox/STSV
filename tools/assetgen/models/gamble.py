"""
도박장 (docs/plans/도박장_기획.md §2, economy README "도박장"): the gambling hall's models, a construction-site den in a
site office (공사장 가설 사무소), not a hanok. All are economy models (economy/content/economy/models/<id>.bbmodel, item
model "economy:model/<id>"), and each is also an item of the same id (economy/content/economy/items/gamble.yml).

Furniture (drawn like the core furniture: 16 px a block, front = north (-z), the anchor cell x 0..16 / z 0..16, y 0 its
floor, model +x the viewer's LEFT, every piece centred in its cells; collision from the model: a cell gets a barrier when
the model fills 30% of it, in voxels of 1/4 block):

  gamble_table_round   a cable drum on its end (wooden flanges, the last of a black cable still wound on its core,
                       the loose end hanging out) as a round table 3 blocks across, its top covered with green felt:
                       cigarette burns, a silver duct-tape X and a strip of 청테이프 over a tear, a worn chalk ring for
                       the pot in the middle, tacks round its edge. Six seats round it, one cell each, on a ring of
                       radius 2 (cells (0,-2) (2,-1) (2,1) (0,2) (-2,1) (-2,-1): a hexagon on the block grid), every one
                       facing the middle: plastic chairs front and back, paint buckets and 포장마차 stools between.
                       5 x 5 cells (the table the middle 3 x 3, the seats round it), drawn at real size and shrunk by
                       2 (BModel scale 2, furniture scale 2): the same 1/16 block texels as everything else.
  gamble_table_duel    a slatted lumber crate (각목 상자) with a plywood top and a square of felt, a plastic chair on
                       each side facing it. 1 x 3 cells (z -1, 0, 1): seat 0 in front, seat 1 behind.

  Seats sit in cells the model fills under 30% (no barrier where someone sits): each chair, stool and bucket keeps
  inside the middle 8 x 8 px of its cell, on the 4 px voxel grid.

  gamble_prop_cement     시멘트 포대 더미: kraft cement sacks stacked crosswise, the top one askew and torn, grey powder
                         spilled in front. 1 cell.
  gamble_prop_rebar      철근 다발: a bundle of rusty deformed bars on two timber sleepers, tie wire, red safety caps on
                         the bar ends. 3 x 1 cells (x -1..1), low (walk over it: no barrier).
  gamble_prop_cone       안전 고깔: an orange traffic cone, two reflective bands, a black rubber base. 1 cell.
  gamble_prop_worklight  삼각대 작업등: a telescopic tripod stand with two halogen floodlights on a T bar, tilted down
                         towards the front, lit; the cable down the mast to a coil on the ground. 1 x 2 cells, its light
                         (15) in the upper cell, which holds only the mast and the lamps (no barrier).
  gamble_prop_drumfire   드럼통 모닥불: a rusty blue 200 l drum, holes punched round its foot glowing, logs and embers in
                         it and flames flickering over the rim (animated). 1 x 2 cells, light 14 in the upper cell.
  gamble_prop_sign       "공사중 · 관계자 외 출입금지": a board on two red and white striped posts with rubber feet (a
                         sandbag on one), the words a 192 x 96 picture (gamble_art.sign_face). 2 x 2 cells (x 0..1).
  gamble_prop_pallet     자재 팔레트: a wooden pallet stacked with red bricks, strapped with blue PP bands. 1 cell.

Display models (shown by an item display with no transform or "fixed": scale 1 = 16 px a block, the model's (8, 8, 8)
at the display's position). Front = north (-z) here too: a card reads upright, a button's word faces, for a viewer on
the model's north side (the same yaw that turns a sign to a player turns these to them).

  gamble_card_<1..10><a|b>, gamble_card_back
                       a 화투 card lying flat, 6 x 8 px (x 5..11, z 4..12), 0.5 px thick, its bottom ON the display's
                       position (model y 8..8.5): place the display on the table's surface. The face (top) is
                       gamble_art.card_face at 4 texels a px (the 화투방 icon card, the same design), the back under it
                       (gamble_card_back: the back on top). The month number corner is the far-left one for that viewer.
  gamble_chips_1 .. 5  a pile of red, blue and white chips (5 px across, 1 px thick, edge spots), more and taller
                       stacks each step (1: two short stacks; 5: nine stacks up to 14 high, a toppled row, loose chips),
                       its bottom on the display's position, centred on it.
  gamble_die           a 4 px die CENTRED on the display's position (it can spin about its middle): 1 up, 6 down, 2 north,
                       5 south, 3 west, 4 east (opposite faces add to 7, 1-2-3 counter-clockwise round their corner as on
                       Western and Korean dice); the one a big red pip. README "도박장" has the turn for each face up.
  gamble_bowl          an upturned white porcelain rice bowl (사발) with a blue line, 11 px across, 6.25 tall, a chip in
                       its rim; bottom on the display's position.
  gamble_btn_<call|die|raise|odd|even|ready|leave>
                       a yellow safety-sign plate 12 x 6 px, 1 px thick, standing up, CENTRED on the display's position,
                       its face (north) gamble_art.button: 콜 다이 레이즈 홀 짝 준비 일어나기 in stencil. Lit (light
                       emission): readable in the dim hall.
  gamble_whiteboard    a wall whiteboard 2.5 x 1.5 blocks in an aluminium frame, marker tray with markers and an eraser,
                       old smudges and a wiped tally (正) bottom right, a receipt under a magnet top left (as seen from in
                       front). Centred on the display's position side to side and up and down. Drawn with its back at z 16
                       and moved 11.5 px forward (shift), so in the model: the writing surface is x -11.5..27.5,
                       y -3.5..19.5 at z 3.5 (2.44 x 1.44 blocks, 0.28 block IN FRONT of the display's position: a text
                       display 0.3 block in front of the position sits just on it, as HallLayout places it), the back at
                       z 4.5 (0.22 block in front of the position; against a wall when the position is 0.22 block out
                       from it). Model +x is the viewer's left; the top-left 5 x 6 px (x 22..27, y 13..19) is held by the
                       receipt. The words are text displays.
"""

import math

from stsv_assets import presets
from stsv_assets import textures as T
from stsv_assets.bake import BModel, Paint, cyl, h3, ring, vnoise
from stsv_assets.model import Model

import gamble_art as ART

SIDES = ("north", "south", "east", "west")
FACES6 = ("north", "east", "south", "west", "up", "down")


def flat(ramp, step, **kw):
    return Paint(ramp, step, noise=0, **kw)


def glow(ramp, step, decal=None, frames=None, frametime=3):
    """A lit surface (lamp glass, embers, flames): no shading, its own colours."""
    return Paint(ramp, step, glow=True, light=False, noise=0, decal=decal, frames=frames, frametime=frametime)


class HModel(BModel):
    """A baked model plus plates wearing a picture of their own at more than 16 px a block (the sign's words): added
    as plain elements once the rest is baked, so they keep their texture and UVs."""

    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self._plates = []

    def plate(self, name, frm, to, faces):
        """faces: {face: (texture name in textures.SURFACES, [u0, v0, u1, v1] in that texture's pixels)}; the other
        faces are left out (they must be covered, like any removed face)."""
        self._plates.append((self._group, name, frm, to, faces))

    def bake(self):
        if self._baked:
            return
        super().bake()
        keep = self._group
        for group, name, frm, to, faces in self._plates:
            self._group = group
            Model.box(self, name, self.to_model(frm), self.to_model(to), None,
                      faces={f: (faces[f][0] if f in faces else None) for f in FACES6}, uv={f: faces[f][1] for f in faces})
        self._group = keep


def gui(m, fill=0.62):
    """Inventory look: the model's bounds fitted into the slot, centred (economy_machines.display for any bounds)."""
    lo = [min(e["from"][i] for e in m.elements) for i in range(3)]
    hi = [max(e["to"][i] for e in m.elements) for i in range(3)]
    big = max(hi[i] - lo[i] for i in range(3)) / 16
    scale = round(min(fill / big, 3.5), 3)
    off = [(lo[i] + hi[i]) / 2 - 8 for i in range(3)]
    rot = presets._rot_xyz([30, 225, 0])
    t = [-sum(rot[i][j] * off[j] * scale for j in range(3)) for i in range(3)]
    m.display = presets.furniture(gui_scale=scale, gui_translation=tuple(round(v, 3) for v in t))
    for ctx in ("thirdperson_righthand", "firstperson_righthand", "firstperson_lefthand", "ground"):
        m.display[ctx] = dict(m.display[ctx])
        m.display[ctx]["scale"] = [round(min(v / max(big, 1), 4), 3) for v in m.display[ctx]["scale"]]
    return m


def _surface(name, fn):
    """Registers a picture as a texture (textures.SURFACES) once and returns its name."""
    if name not in T.SURFACES:
        T.SURFACES[name] = fn
        T._SIZE_CACHE.pop(name, None)
        T._ALPHA_CACHE.pop(name, None)
    return name


# ================================================================================================
# Seats: every one inside the middle 8 x 8 px of its cell (x/z 4..12 of the cell), sitting surface 8 px up
# ================================================================================================

def plastic(ramp, step):
    """Moulded outdoor plastic: glossy streaks, a little grime."""
    return Paint(ramp, step, "lacquer", noise=0.03, rim=1.2)


def plastic_chair(m, name, ox, oz, back, paint):
    """A stackable plastic chair (등받이 의자) round the cell centre (ox, oz): an 8 x 8 seat with a rolled front edge
    on four legs, the backrest on the `back` side ('n' = -z, 's' = +z) in two steps leaning back, slots across it."""
    m.group(name)
    x0, x1, z0, z1 = ox - 4, ox + 4, oz - 4, oz + 4
    m.box("seat", (x0, 7, z0), (x1, 8, z1), paint)
    for (lx, lz) in ((x0, z0), (x1 - 1, z0), (x0, z1 - 1), (x1 - 1, z1 - 1)):
        m.box("leg", (lx, 0, lz), (lx + 1, 7, lz + 1), paint(rim=0.8))
    # the front lip under the seat edge (the side away from the back)
    lip = (x0 + 0.5, 6.25, z1 - 0.75, x1 - 0.5, 7, z1 - 0.25) if back == "n" else (x0 + 0.5, 6.25, z0 + 0.25, x1 - 0.5, 7, z0 + 0.75)
    m.box("lip", lip[:3], lip[3:], paint)
    sgn = -1 if back == "n" else 1
    bz = z0 if back == "n" else z1 - 1

    def slots(c):
        # three slots across the upper back, darker
        if c.face in ("north", "south") and 13 <= c.p[1] < 16 and abs(c.p[0] - ox) < 2.6 and math.floor(c.p[1]) in (13, 15):
            return (paint.ramp, paint.base - 1)
        return None
    m.box("back_low", (x0, 8, bz), (x1, 12, bz + 1), paint)
    lean = 0.5 * sgn
    m.box("back_high", (x0, 12, bz + lean), (x1, 17, bz + 1 + lean), paint(decal=slots))
    m.box("back_top", (x0 + 0.5, 17, bz + lean), (x1 - 0.5, 17.5, bz + 1 + lean), paint)


def stool(m, name, ox, oz, paint):
    """A 포장마차 plastic stool: a round top 8 across with a hand slot in the middle, a skirt under its edge, four
    legs and a ring of stretchers low down."""
    m.group(name)

    def top(c):
        if c.face == "up" and abs(c.p[0] - ox) < 1.6 and abs(c.p[2] - oz) < 0.6:
            return (paint.ramp, paint.base - 2)          # the hand slot
        return None
    cyl(m, "top", (ox, oz), 8, 7, 8, paint(decal=top))
    for (dx, dz) in ((-2.5, -2.5), (1.5, -2.5), (-2.5, 1.5), (1.5, 1.5)):
        m.box("leg", (ox + dx, 0, oz + dz), (ox + dx + 1, 7, oz + dz + 1), paint(rim=0.8))
    m.box("stretcher", (ox - 1.5, 2, oz - 2.25), (ox + 1.5, 2.75, oz - 1.75), paint)
    m.box("stretcher", (ox - 1.5, 2, oz + 1.75), (ox + 1.5, 2.75, oz + 2.25), paint)
    m.box("stretcher", (ox - 2.25, 2, oz - 1.5), (ox - 1.75, 2.75, oz + 1.5), paint)
    m.box("stretcher", (ox + 1.75, 2, oz - 1.5), (ox + 2.25, 2.75, oz + 1.5), paint)


def bucket(m, name, ox, oz, label=None, drips=None, cardboard=False, seed=0):
    """An 18 l paint pail (페인트 통) turned into a seat: white plastic, its lid on, a printed label band (label = the
    ramp) or old paint run down its side (drips = (ramp, step)); the wire handle folded over the lid, or a flattened
    cardboard box on it for a cushion."""
    m.group(name)

    def side(c):
        if c.face in ("up", "down"):
            return None
        y = c.p[1]
        if drips:
            a = math.atan2(c.p[2] - oz, c.p[0] - ox)
            k = math.floor((a + math.pi) / (2 * math.pi) * 22)
            run = 5.5 - h3(k, 0, 0, seed) * 5.0
            if h3(k, 1, 0, seed) < 0.55 and y > run:
                return drips
        if label and 2.5 <= y < 5.5:
            a = math.atan2(c.p[2] - oz, c.p[0] - ox)
            k = math.floor((a + math.pi) / (2 * math.pi) * 24)
            if 3 <= math.floor(y) <= 4 and k % 6 in (1, 2, 3) and h3(k, math.floor(y), 0, seed + 3) < 0.7:
                return ("car_white", 3)                    # the printed words
            return (label, 1)
        if math.floor(y) == 6:
            return ("car_white", 1)                        # the ridge under the lid
        return None
    body = Paint("car_white", 2, "lacquer", noise=0.03, decal=side)
    cyl(m, "pail", (ox, oz), 8, 0, 7.5, body)
    cyl(m, "lid", (ox, oz), 8, 7.5, 8, Paint("car_white", 1, noise=0.02))
    if cardboard:
        def kraft(c):
            if c.face == "up" and abs(c.p[0] - ox + 0.25) < 0.3:
                return ("bean", 1)                         # the fold
            return None
        m.box("cardboard", (ox - 3.75, 8, oz - 3.5), (ox + 3.25, 8.25, oz + 3.5), Paint("bean", 2, "paper", decal=kraft))
    else:
        ring(m, "lid_ring", (ox, oz), 5, 4, 8, 8.25, flat("car_white", 1))
        m.box("handle", (ox - 3.75, 8, oz - 0.25), (ox - 2.5, 8.25, oz + 0.25), flat("steel", 2))
        m.box("handle", (ox + 2.5, 8, oz - 0.25), (ox + 3.75, 8.25, oz + 0.25), flat("steel", 2))
        m.box("handle_grip", (ox - 1.5, 8, oz - 0.375), (ox + 1.5, 8.375, oz + 0.375), flat("rubber", 2))


# ================================================================================================
# The felt
# ================================================================================================

def felt_top(cx, cz, radius, burns, tapes, pot=None, seed=0):
    """Green felt seen from above (relative to the centre): mottled wear, cigarette burns (a black hole in a scorched
    ring, burns = [(dx, dz, r)]), tape (tapes = [(kind, (x0, z0), (x1, z1), width)]: 'silver' duct tape or 'blue'
    청테이프, a straight strip between two points, a dark tear line under the blue one), a chalk ring for the pot."""
    def decal(c):
        if c.face != "up":
            return ("felt", 1)
        x, z = c.p[0] - cx, c.p[2] - cz
        for (kind, (ax, az), (bx, bz), w) in tapes:
            vx, vz = bx - ax, bz - az
            L = math.hypot(vx, vz)
            t = ((x - ax) * vx + (z - az) * vz) / (L * L)
            d = abs((x - ax) * vz - (z - az) * vx) / L
            if -0.02 <= t <= 1.02 and d <= w / 2:
                edge = d > w / 2 - 0.6 or t < 0.06 or t > 0.94
                if kind == "silver":
                    return ("steel", 4 if edge else 5)
                return ("celadon", 0 if edge else 1)
        for (bx, bz, br) in burns:
            d = math.hypot(x - bx, z - bz)
            if d < br * 0.55:
                return ("soot", 0)
            if d < br:
                return ("soot", 2)
            if d < br + 0.7 and h3(c.i[0], c.i[2], 0, seed) < 0.5:
                return ("felt", 0)
        r = math.hypot(x, z)
        if pot and pot - 0.5 <= r < pot + 0.5 and h3(c.i[0], c.i[2], 1, seed) < 0.8:
            return ("felt", 3)
        v = vnoise(x + 40, 0, z + 40, 3.5, seed + 5)
        step = 2
        if v > 0.84:
            step = 3                                       # rubbed shiny where elbows go
        elif v < 0.1:
            step = 1
        if r > radius - 1.2:
            step = 1                                       # the edge, tucked over the flange
        return ("felt", step)
    return decal


def cable_wound(c):
    """The core's last turns of black cable: one turn a texel row, each a slightly different shade, a dull highlight on
    its upper edge."""
    if c.face in ("up", "down"):
        return None
    row = math.floor(c.p[1])
    return ("rubber", 2 if h3(row, 0, 0, 31) < 0.6 else 1)


# ================================================================================================
# 원형 테이블 (케이블 드럼) · 6 seats
# ================================================================================================

# seat index -> cell (x, z): seat 0 the front, then clockwise seen from above (north up, east right)
ROUND_SEATS = [(0, -2), (2, -1), (2, 1), (0, 2), (-2, 1), (-2, -1)]
DUEL_SEATS = [(0, -1), (0, 1)]


def facing_to_centre(cell):
    """The furniture seat 'facing' (degrees from the piece's front: 0 = -z, 90 = +x, 180 = +z) looking at the middle."""
    x, z = -cell[0], -cell[1]
    return round(math.degrees(math.atan2(x, -z)), 2)


def table_round():
    m = BModel("gamble_table_round", floor=0, scale=2)
    cx = cz = 8
    flange = Paint("wood", 1, "planks", axis="x", width=3, noise=0.06)
    m.group("drum")
    cyl(m, "flange_low", (cx, cz), 44, 0, 2, flange)
    cyl(m, "core", (cx, cz), 26, 2, 11, Paint("rubber", 2, noise=0.02, decal=cable_wound, rim=0.6))
    cyl(m, "flange_top", (cx, cz), 48, 11, 13, flange)
    # the loose end of the cable hanging out of the winding on the east side, its copper showing
    m.box("cable_end", (20.5, 6, 6), (25.5, 7, 7), flat("rubber", 2, rim=0.6))
    m.box("cable_end", (24.5, 3, 6), (25.5, 6, 7), flat("rubber", 2, rim=0.6))
    m.box("cable_tip", (24.75, 2.25, 6.25), (25.25, 3, 6.75), flat("copper", 3))
    # bolts through the top flange: hex nuts on the wooden rim round the felt
    for k in range(10):
        a = 2 * math.pi * (k + 0.5) / 10
        x, z = cx + 23 * math.cos(a), cz + 23 * math.sin(a)
        m.box("nut", (x - 0.5, 13, z - 0.5), (x + 0.5, 13.5, z + 0.5), flat("iron", 3, rim=1.2))
    m.group("felt")
    burns = [(-9, -5, 1.4), (12.5, 4, 1.1), (3, 14, 1.3), (-15, 9, 1.0), (6.5, -15.5, 1.2), (-4, 2.5, 0.9), (16, -8, 1.0)]
    tapes = [("silver", (-13, -14), (-5, -6), 2.2), ("silver", (-13, -6), (-5, -14), 2.2),
             ("blue", (8, 9), (18, 13), 2.0)]
    cyl(m, "felt", (cx, cz), 44, 13, 13.5, Paint("felt", 2, "weave", noise=0.02, rim=0.4, decal=felt_top(cx, cz, 22, burns, tapes, pot=7)))
    for k in range(16):
        a = 2 * math.pi * k / 16
        x, z = cx + 21.25 * math.cos(a), cz + 21.25 * math.sin(a)
        m.box("tack", (x - 0.25, 13.5, z - 0.25), (x + 0.25, 13.75, z + 0.25), flat("steel", 4))
    # the seats, seat 0 in front, clockwise
    blue, red, white = plastic("signal_blue", 1), plastic("pepper", 2), plastic("car_white", 2)
    for i, (sx, sz) in enumerate(ROUND_SEATS):
        ox, oz = sx * 16 + 8, sz * 16 + 8
        if i == 0:
            plastic_chair(m, "seat0_chair", ox, oz, "n", blue)
        elif i == 3:
            plastic_chair(m, "seat3_chair", ox, oz, "s", white)
        elif i == 1:
            bucket(m, "seat1_bucket", ox, oz, label="sapphire", seed=1)
        elif i == 4:
            bucket(m, "seat4_bucket", ox, oz, drips=("leaf", 2), cardboard=True, seed=4)
        elif i == 2:
            stool(m, "seat2_stool", ox, oz, red)
        else:
            stool(m, "seat5_stool", ox, oz, plastic("signal_blue", 0))
    return gui(m)


# ================================================================================================
# 1:1 테이블 (각목 상자) · 2 seats
# ================================================================================================

def table_duel():
    m = BModel("gamble_table_duel", floor=0)
    m.group("crate")
    post = Paint("wood", 2, "grain", axis="y", noise=0.05)
    for (x, z) in ((1, 1), (13, 1), (1, 13), (13, 13)):
        m.box("post", (x, 0, z), (x + 2, 11, z + 2), post)

    for i, y in enumerate((0.5, 4, 7.5)):
        m.box("board_n", (3, y, 1.25), (13, y + 3, 2.25), Paint("wood", 2, "grain", axis="x", seed=i))
        m.box("board_s", (3, y, 13.75), (13, y + 3, 14.75), Paint("wood", 2, "grain", axis="x", seed=i + 3))
        m.box("board_w", (1.25, y, 3), (2.25, y + 3, 13), Paint("wood", 2, "grain", axis="z", seed=i + 6))
        m.box("board_e", (13.75, y, 3), (14.75, y + 3, 13), Paint("wood", 2, "grain", axis="z", seed=i + 9))
    m.box("inside", (3, 0.5, 3), (13, 10.5, 13), flat("wood_dark", 0))

    def plywood(c):
        if c.face in SIDES:
            return ("wood", 3 if math.floor(c.p[1] * 2) % 2 else 1)   # the plies
        return None
    m.box("top", (0.5, 11, 0.5), (15.5, 12, 15.5), Paint("hinoki", 2, "grain", axis="x", decal=plywood))
    m.group("felt")
    m.box("felt", (2, 12, 2), (14, 12.25, 14), Paint("felt", 2, "weave", noise=0.02, rim=0.4,
                                                       decal=felt_top(8, 8, 9, [(3.5, -3, 1.0), (-4, 4, 0.8)],
                                                                      [("silver", (-6, -6), (-2, -6), 1.6)])))
    for (x, z) in ((2.25, 2.25), (13.25, 2.25), (2.25, 13.25), (13.25, 13.25)):
        m.box("tack", (x, 12.25, z), (x + 0.5, 12.5, z + 0.5), flat("steel", 4))
    plastic_chair(m, "seat0_chair", 8, -8, "n", plastic("signal_blue", 1))
    plastic_chair(m, "seat1_chair", 8, 24, "s", plastic("pepper", 2))
    return gui(m)


# ================================================================================================
# 화투 cards (plain model: the picture at 4 texels a px)
# ================================================================================================

def card_model(name, atlas_name, atlas):
    """A card lying flat, 6 x 8 px, 0.5 thick, its bottom on y 8. The top shows the atlas's left half (turned so it
    reads upright from the north), the bottom its right half, the edges the dark red of the back's frame."""
    tex = _surface(atlas_name, atlas)
    m = Model(name)
    m.group("card")
    m.box("card", (5, 8, 4), (11, 8.5, 12), tex,
          uv={"up": [24, 32, 0, 0], "down": [24, 0, 48, 32],
              "north": [24, 0, 25, 1], "south": [24, 0, 25, 1], "east": [24, 0, 25, 1], "west": [24, 0, 25, 1]})
    return gui(m, fill=0.9)


def _cards():
    out = {}
    for month in range(1, 11):
        for first in (True, False):
            key = f"gamble_card_{month}{'a' if first else 'b'}"
            out[key] = (lambda k=key, mo=month, f=first: card_model(k, f"{k}_tex", lambda mo=mo, f=f: ART.card_atlas(mo, f)))
    out["gamble_card_back"] = lambda: card_model("gamble_card_back", "gamble_card_back_tex", ART.back_atlas)
    return out


# ================================================================================================
# Chips
# ================================================================================================

CHIP = {"R": ("pepper", 2, ("car_white", 3)), "B": ("signal_blue", 1, ("car_white", 3)), "W": ("car_white", 3, ("pepper", 2))}


def chip_paint(cx, cz, colours, axis="y", lo=0.0):
    """Chips stacked along `axis` from `lo`, one a px, colours[i] the i-th from the bottom (or the end): its colour all
    round with edge spots (every other eighth of the way round), the top chip's face with spots at its rim."""
    def decal(c):
        if axis == "y":
            k = math.floor(c.p[1] - lo - (0.01 if c.face == "up" else 0))
            a = math.atan2(c.p[2] - cz, c.p[0] - cx)
            r = math.hypot(c.p[2] - cz, c.p[0] - cx)
        else:
            k = math.floor(c.p[0] - lo - (0.01 if c.face == "east" else 0))
            a = math.atan2(c.p[2] - cz, c.p[1] - cx)
            r = math.hypot(c.p[2] - cz, c.p[1] - cx)
        k = max(0, min(len(colours) - 1, k))
        ramp, step, spot = CHIP[colours[k]]
        cap = (axis == "y" and c.face in ("up", "down")) or (axis == "x" and c.face in ("east", "west"))
        if cap:
            if r > 1.6 and math.floor((a + math.pi) / (2 * math.pi) * 4 + 0.5) % 2 == 0 and abs(math.sin(2 * a)) < 0.5:
                return spot
            if r < 0.8:
                return (ramp, step + 1)
            return (ramp, step)
        if math.floor((a + math.pi) / (2 * math.pi) * 6 + (k % 2) * 0.5) % 3 == 0:
            return spot
        return (ramp, step)
    return Paint("pepper", 2, noise=0, rim=0.5, decal=decal)


def _mix(n, seed, bias="RBW"):
    return "".join(bias[int(h3(i, seed, 0, 77) * len(bias))] for i in range(n))


# pile -> stacks [(gx, gz, height, colour bias)], knocked-over rows [(gx, gz, length)], loose chips [(gx, gz, colour)], on a
# grid of GRID px (rows may sit half a step over): no two chips' boxes overlap, so no two faces share a place
GRID = 5.25
PILES = {
    1: ([(0, 0, 3, "RRB"), (1, 0, 2, "WB")], [], [(0.5, -1, "R")]),
    2: ([(0, 0, 5, "RRB"), (1, 0, 3, "BBW"), (0.5, 1, 4, "WWR")], [], [(-0.5, 1, "B"), (1.5, 1, "R")]),
    3: ([(0, 0, 7, "RRB"), (1, 0, 5, "BBW"), (0.5, 1, 6, "WRW"), (1.5, 1, 3, "RB")], [(0, -1, 4)], [(1, -1, "R"), (2, 0, "W")]),
    4: ([(0, 0, 10, "RRB"), (1, 0, 8, "BBW"), (2, 0, 9, "WRW"), (0.5, 1, 6, "RB"), (1.5, 1, 7, "BWB"), (2.5, 1, 4, "RRW")],
        [(-0.5, 1, 5)], [(0.5, -1, "B"), (2, -1, "R"), (1, 2, "W")]),
    5: ([(0, 0, 14, "RRB"), (1, 0, 12, "BBW"), (2, 0, 13, "WRW"), (-0.5, 1, 9, "RB"), (0.5, 1, 11, "BRR"), (1.5, 1, 10, "WWB"),
         (2.5, 1, 8, "RRW"), (0, 2, 6, "BWB"), (1, 2, 5, "RWR")],
        [(2, 2, 5), (-1, 0, 4)], [(0.5, -1, "B"), (1.5, -1, "R"), (3, 0, "W"), (-1, 2, "R"), (3, 2, "W")]),
}


def _pile_centre(n):
    pts = [(a, b) for (a, b, *_r) in PILES[n][0] + PILES[n][1] + PILES[n][2]]
    xs, zs = [a for a, _ in pts], [b for _, b in pts]
    return (min(xs) + max(xs)) / 2, (min(zs) + max(zs)) / 2


def chips(n):
    m = BModel(f"gamble_chips_{n}", floor=0, shift=(0, 8, 0))
    stacks, spills, loose = PILES[n]
    gx0, gz0 = _pile_centre(n)

    def at(gx, gz):
        return 8 + (gx - gx0) * GRID, 8 + (gz - gz0) * GRID
    m.group("stacks")
    for i, (gx, gz, hgt, bias) in enumerate(stacks):
        x, z = at(gx, gz)
        cyl(m, "stack", (x, z), 5, 0, hgt, chip_paint(x, z, _mix(hgt, n * 10 + i, bias)))
    m.group("spill")
    for i, (gx, gz, length) in enumerate(spills):
        # a stack knocked over: chips lying on their edges in a row along x
        x, z = at(gx, gz)
        x0 = x - length / 2
        cyl(m, "row", (2.5, z), 5, x0, x0 + length, chip_paint(2.5, z, _mix(length, n * 20 + i), axis="x", lo=x0), axis="x")
    m.group("loose")
    for i, (gx, gz, col) in enumerate(loose):
        x, z = at(gx, gz)
        cyl(m, "chip", (x, z), 5, 0, 1, chip_paint(x, z, col))
    return gui(m, fill=0.8)


# ================================================================================================
# The die (plain model: 16 texels a face)
# ================================================================================================

# the die's faces: model face -> number
DIE_FACES = {"up": 1, "down": 6, "north": 2, "south": 5, "west": 3, "east": 4}


def die():
    tex = _surface("gamble_die_faces", ART.die_atlas)
    m = Model("gamble_die")
    uv = {}
    for f, n in DIE_FACES.items():
        c, r = ART.DIE_TILES[n]
        uv[f] = [c * 16, r * 16, c * 16 + 16, r * 16 + 16]
    m.box("die", (6, 6, 6), (10, 10, 10), tex, uv=uv)
    return gui(m, fill=0.7)


# ================================================================================================
# 사발 (upturned)
# ================================================================================================

def bowl():
    m = BModel("gamble_bowl", floor=0, shift=(0, 8, 0))

    def glaze(c):
        y = c.p[1]
        if c.face in SIDES:
            if math.floor(y) == 1 or (3 <= y < 3.5):
                return ("indigo", 2)                       # the blue lines round it
            a = math.atan2(c.p[2] - 8, c.p[0] - 8)
            if y < 1 and -2.0 < a < -1.75:
                return ("clay", 3)                         # a chip out of the rim
            if 1.5 < y < 4.5 and abs(a + 1.6) < 0.05:
                return ("stone", 1)                        # a hairline crack up from it
        return None
    p = Paint("porcelain", 2, "lacquer", noise=0.02, rim=1.3, decal=glaze)
    for (d, lo, hi) in ((11, 0, 2.5), (10, 2.5, 3.5), (9, 3.5, 4.5), (7, 4.5, 5.25)):
        cyl(m, "bowl", (8, 8), d, lo, hi, p)
    ring(m, "foot", (8, 8), 5, 3, 5.25, 6.25, p)
    cyl(m, "foot_in", (8, 8), 3, 5.25, 5.5, Paint("clay", 3, noise=0.05))
    return gui(m, fill=0.8)


# ================================================================================================
# Buttons (plain model: the plate's face at 13 1/3 texels a px)
# ================================================================================================

def button_model(key):
    face = _surface(f"gamble_btn_{key}_face", lambda k=key: ART.button(k))
    back = _surface("gamble_btn_back", ART.button_back)
    m = Model(f"gamble_btn_{key}")
    m.group("plate")
    m.box("face", (2, 5, 7.5), (14, 11, 7.75), back, faces={"north": face, "south": None}, uv={"north": [0, 0, 160, 80]}, glow=True)
    m.box("plate", (2, 5, 7.75), (14, 11, 8.5), back, glow=True)
    return gui(m, fill=0.9)


# ================================================================================================
# 화이트보드
# ================================================================================================

def whiteboard():
    m = BModel("gamble_whiteboard", floor=None, planes=(("z>", 16),), shift=(0, 0, -11.5))

    def board(c):
        if c.face != "north":
            return None
        x, y = c.p[0], c.p[1]
        # old marker not quite wiped: soft grey streaks along the eraser's strokes
        v = vnoise(x * 0.5, y * 2.0, 0, 2.5, 91)
        if v > 0.78:
            return ("car_white", 2)
        # a ghost tally (正) bottom right, wiped but still there
        u, w = c.i[0] + 10, c.i[1] + 2
        if 0 <= u < 5 and 0 <= w < 5 and (w in (0, 4) or u == 2 or (w == 2 and u > 2) or (u == 0 and w <= 2)):
            return ("car_white", 1)
        return None
    m.group("board")
    m.box("surface", (-11.5, -3.5, 15), (27.5, 19.5, 16), Paint("car_white", 3, noise=0.015, rim=0.4, decal=board))
    m.group("frame")
    alu = Paint("steel", 4, "metal", noise=0.03, rim=1.3)
    m.box("frame_top", (-12.5, 19.5, 14.5), (28.5, 20.5, 16), alu)
    m.box("frame_bottom", (-12.5, -4.5, 14.5), (28.5, -3.5, 16), alu)
    m.box("frame_left", (-12.5, -3.5, 14.5), (-11.5, 19.5, 16), alu)
    m.box("frame_right", (27.5, -3.5, 14.5), (28.5, 19.5, 16), alu)
    for (x, y) in ((-12.75, 19.25), (27.25, 19.25), (-12.75, -4.75), (27.25, -4.75)):
        m.box("corner", (x, y, 14.25), (x + 1.5, y + 1.5, 15.75), flat("iron", 3, rim=1.0))
    m.group("tray")
    m.box("tray", (-6, -5.75, 12.5), (22, -4.5, 14.5), alu)
    m.box("tray_lip", (-6, -4.5, 12.5), (22, -4, 13), alu)
    for (x0, ramp_, cap) in ((-3, "iron", "iron"), (1, "pepper", "iron"), (5, "sapphire", "iron")):
        m.box("marker", (x0, -4.5, 13.25), (x0 + 3, -3.75, 14), flat("car_white", 3))
        m.box("marker_cap", (x0 + 3, -4.5, 13.25), (x0 + 4, -3.75, 14), flat(ramp_, 2))
    m.box("eraser_felt", (14, -4.5, 13.1), (18, -4, 14.4), flat("felt", 1))
    m.box("eraser", (14, -4, 13.1), (18, -3, 14.4), flat("sapphire", 2, rim=1.0))
    m.group("receipt")

    def receipt(c):
        if c.face == "north" and math.floor(c.p[1]) % 2 == 0 and c.p[0] > 23.5 and h3(c.i[0], c.i[1], 0, 5) < 0.75:
            return ("stone", 3)                            # printed lines
        return None
    m.box("receipt", (22.25, 13, 14.85), (26.75, 18.75, 15), Paint("paper", 2, "paper", decal=receipt, rim=0.3))
    cyl(m, "magnet", (24.5, 17.25), 2, 14.35, 14.85, flat("pepper", 2, rim=1.0), axis="z")
    return gui(m)


# ================================================================================================
# Props (furniture)
# ================================================================================================

def _sack(m, name, x0, y0, z0, x1, y1, z1, seed, along="x", torn=False):
    """A cement sack: kraft paper, puffy (a core and two caps a little smaller), a red band printed across its top
    with white marks for the words, the sewn ends darker."""
    def print_(c):
        x, z = c.p[0], c.p[2]
        if c.face == "up":
            u = x if along == "z" else z
            mid = (x0 + x1) / 2 if along == "z" else (z0 + z1) / 2
            if abs(u - mid) < 1.3:
                return ("car_white", 3) if h3(c.i[0], c.i[2], 0, seed) < 0.35 else ("pepper", 2)
        if torn and c.face == "north" and 1 <= c.p[0] - x0 < 4 and c.p[1] > y0 + 0.5:
            return ("stone", 2)                            # the tear, cement showing
        ends = ("east", "west") if along == "x" else ("north", "south")
        if c.face in ends:
            return ("bean", 1)
        return None
    p = Paint("bean", 2, "paper", noise=0.05, decal=print_, seed=seed)
    m.box(name, (x0, y0 + 0.5, z0), (x1, y1 - 0.5, z1), p)
    m.box(name + "_top", (x0 + 0.5, y1 - 0.5, z0 + 0.5), (x1 - 0.5, y1, z1 - 0.5), p)


def prop_cement():
    m = BModel("gamble_prop_cement", floor=0)
    m.group("sacks")
    _sack(m, "sack", 2.5, 0, 1.75, 13.5, 2.5, 8, 1)
    _sack(m, "sack", 2.5, 0, 8, 13.5, 2.5, 14.25, 2)
    _sack(m, "sack", 2, 2.5, 2.5, 8, 5, 13.5, 3, along="z")
    _sack(m, "sack", 8, 2.5, 2.5, 14, 5, 13.5, 4, along="z")
    m.group("top")
    turn = ("y", 22.5, (8, 6, 8))
    p = Paint("bean", 2, "paper", noise=0.05, seed=9)
    m.box("sack_askew", (3, 5.5, 5), (13, 7, 11), p, rotation=turn)
    m.box("sack_askew_top", (3.5, 7, 5.5), (12.5, 7.5, 10.5), p, rotation=turn)
    m.box("band", (7, 7.5, 5.5), (9, 7.6, 10.5), flat("pepper", 2), rotation=turn)
    m.box("sack_askew_low", (3.5, 5, 5.5), (12.5, 5.5, 10.5), p, rotation=turn)
    m.group("spill")
    powder = Paint("stone", 3, "speckle", noise=0.05)
    m.box("powder", (9, 0, 0.5), (14, 0.5, 1.75), powder)
    m.box("powder", (10, 0.5, 0.75), (13, 1, 1.75), powder)
    m.box("powder_tear", (9.5, 2.5, 1.25), (12.5, 3, 2.5), powder)
    return gui(m)


def prop_rebar():
    m = BModel("gamble_prop_rebar", floor=0)
    m.group("sleepers")
    for x in (-9, 23):
        m.box("sleeper", (x, 0, 2), (x + 2, 2, 14), Paint("wood_dark", 2, "grain", axis="z", noise=0.06))

    def deformed(c):
        # ribs every 2 px along the bar, rust blotches, bright cut ends
        if c.face in ("east", "west"):
            return ("steel", 2)
        if math.floor(c.p[0]) % 2 == 0:
            return ("soil", 3)
        v = vnoise(c.p[0], c.p[1] * 3, c.p[2] * 3, 3, 21)
        if v > 0.7:
            return ("copper", 0)
        return None
    bar = Paint("soil", 2, noise=0.06, decal=deformed, rim=0.6)
    rows = [(2, 7, 3.0), (3, 6, 3.75), (4, 5, 4.5), (5, 3, 6.0)]
    m.group("bars")
    for r, (y, n, z0) in enumerate(rows):
        for i in range(n):
            z = z0 + i * 1.5
            a = round(h3(r, i, 0, 51) * 3) * 0.5
            b = round(h3(r, i, 1, 51) * 3) * 0.5
            x0, x1 = -14 + a, 30 - b
            m.box("bar", (x0, y, z), (x1, y + 1, z + 1), bar)
            if r % 2 == 0 and i % 2 == 0:
                m.box("cap", (x0 - 0.75, y - 0.125, z - 0.125), (x0, y + 1.125, z + 1.125), flat("pepper", 2, rim=1.0))
    m.group("wire")
    for x in (-3, 19):
        m.box("tie", (x, 1.75, 2.5), (x + 0.5, 6.25, 3), flat("iron", 3))
        m.box("tie", (x, 1.75, 13), (x + 0.5, 6.25, 13.5), flat("iron", 3))
        m.box("tie", (x, 6, 3), (x + 0.5, 6.25, 13), flat("iron", 3))
    return gui(m)


def prop_cone():
    m = BModel("gamble_prop_cone", floor=0)
    m.group("base")
    rub = Paint("rubber", 2, noise=0.04)
    m.box("base", (2.5, 0, 2), (13.5, 1, 14), rub)
    m.box("base", (2, 0, 2.5), (2.5, 1, 13.5), rub)
    m.box("base", (13.5, 0, 2.5), (14, 1, 13.5), rub)
    m.group("cone")

    def grime(c):
        if c.face in SIDES and c.p[1] < 3 and h3(c.i[0], c.i[1], c.i[2], 3) < 0.35:
            return ("fox", 1)
        return None
    orange = Paint("fox", 2, "lacquer", noise=0.03, rim=1.1, decal=grime)
    band = Paint("car_white", 3, noise=0.0, rim=1.3)
    for (d, lo, hi, p) in ((9, 1, 3, orange), (8, 3, 5, orange), (7, 5, 7, band), (6, 7, 8.5, orange), (5, 8.5, 10, band),
                           (4, 10, 12, orange), (3, 12, 13.5, orange), (2, 13.5, 14, orange)):
        cyl(m, "cone", (8, 8), d, lo, hi, p)
    return gui(m)


def prop_worklight():
    m = BModel("gamble_prop_worklight", floor=0)
    steel = Paint("steel", 3, "metal", rim=1.2)
    m.group("stand")
    # three legs: one forward, two back to the sides, from a collar on the mast
    m.box("leg", (7.5, 0, 7.5), (8.5, 10, 8.5), steel, rotation=("x", -22.5, (8, 10, 8)))
    m.box("leg", (7.5, 0, 9.75), (8.5, 10, 10.75), steel, rotation=("z", 22.5, (8, 10, 10.25)))
    m.box("leg", (7.5, 0, 9.75), (8.5, 10, 10.75), steel, rotation=("z", -22.5, (8, 10, 10.25)))
    m.box("collar", (7, 9, 7), (9, 11, 11), Paint("iron", 3, "metal", rim=1.2))
    m.box("mast", (7.25, 1, 7.25), (8.75, 17, 8.75), Paint("hazard", 1, "metal", rim=1.1))
    m.box("mast_up", (7.5, 17, 7.5), (8.5, 27, 8.5), steel)
    m.box("clamp", (6, 16, 7.5), (7.25, 17.5, 8.5), flat("rubber", 2))
    m.group("lamps")
    m.box("tbar", (4, 26.5, 7.25), (12, 27.5, 8.75), steel)
    tilt = -22.5
    for (x0, x1) in ((4, 7.5), (8.5, 12)):
        rot = ("x", tilt, ((x0 + x1) / 2, 28, 8))
        m.box("housing", (x0, 27.5, 6.5), (x1, 31, 9), Paint("hazard", 1, "metal", rim=1.2), rotation=rot)
        m.box("glass", (x0 + 0.5, 28, 6.25), (x1 - 0.5, 30.5, 6.5), glow("lamp", 3), rotation=rot)
        m.box("fins", (x0 + 0.5, 28, 9), (x1 - 0.5, 30.5, 9.75), Paint("iron", 2, "metal"), rotation=rot)
    m.group("cable")
    m.box("cable", (8.75, 1, 8.25), (9.25, 26.5, 8.75), flat("rubber", 2))
    ring(m, "coil", (11.5, 12), 5, 3, 0, 0.75, flat("rubber", 2, rim=0.6))
    return gui(m)


def prop_drumfire():
    m = BModel("gamble_prop_drumfire", floor=0)

    def rust(c):
        if c.face == "down":
            return None
        v = vnoise(c.p[0], c.p[1] * 1.5, c.p[2], 2.2, 13)
        if v > 0.74 or (c.p[1] < 2 and v > 0.45):
            return ("copper", 0)
        if v > 0.62:
            return ("soil", 1)
        if c.p[1] > 12 and c.face in SIDES and h3(c.i[0], c.i[1], c.i[2], 2) < 0.4:
            return ("soot", 1)                             # soot licked up over the rim
        return None
    drum = Paint("police_blue", 2, "metal", noise=0.04, decal=rust)
    m.group("drum")
    for (d, lo, hi) in ((12, 0, 4.5), (13, 4.5, 5.5), (12, 5.5, 9), (13, 9, 10), (12, 10, 13.5)):
        cyl(m, "drum", (8, 8), d, lo, hi, drum)
    ring(m, "rim", (8, 8), 12, 10, 13.5, 14, Paint("police_blue", 1, "metal", decal=rust))
    m.group("fire")
    embers = glow("fire", 3, decal=lambda c: ("fire", 4 if h3(c.i[0], c.i[2], 0, 4) < 0.3 else 2 if h3(c.i[0], c.i[2], 1, 4) < 0.3 else 3))
    cyl(m, "embers", (8, 8), 10, 12, 12.5, embers)
    log = Paint("bark", 1, "grain", axis="long", noise=0.06)
    m.box("log", (3.5, 12.5, 7), (12.5, 14, 8.5), log, rotation=("y", 22.5, (8, 13, 8)))
    m.box("log", (7, 12.5, 3.5), (8.5, 14, 12.5), log, rotation=("y", -22.5, (8, 13, 8)))
    m.box("char", (11.5, 12.75, 7.25), (12.5, 13.75, 8.25), glow("fire", 3), rotation=("y", 22.5, (8, 13, 8)))

    def flame(c):
        # the flames' colours rise through the fire ramp, each frame a step on: the tips flicker
        t = (c.p[1] - 12.5) / 10 + 0.15 * math.sin(c.frame * 1.7 + c.p[0] * 0.9 + c.p[2] * 1.3)
        return ("fire", 5 if t < 0.25 else 4 if t < 0.55 else 3 if t < 0.8 else 2)
    fl = glow("fire", 4, decal=flame, frames=4, frametime=3)
    m.box("flame", (5.5, 13, 6), (10.5, 16.5, 10), fl)
    m.box("flame", (6.5, 16.5, 6.75), (9.5, 19.5, 9.25), fl)
    m.box("flame", (7.5, 19.5, 7.5), (8.5, 22, 8.5), fl)
    m.box("flame", (4.75, 14, 8.75), (6, 17.5, 9.75), fl)
    m.box("flame", (10, 14, 6.5), (11.25, 18, 7.5), fl)
    m.group("holes")
    hole = glow("fire", 4)
    for (x0, z0, x1, z1) in ((7.5, 1.75, 8.5, 2), (4.5, 2.75, 5.5, 3), (10.5, 2.75, 11.5, 3), (7.5, 14, 8.5, 14.25),
                             (1.75, 7.5, 2, 8.5), (14, 7.5, 14.25, 8.5)):
        m.box("hole", (x0, 1, z0), (x1, 2.5, z1), hole)
    return gui(m)


def prop_sign():
    m = HModel("gamble_prop_sign", floor=0)
    face = _surface("gamble_sign_face", ART.sign_face)
    back = _surface("gamble_sign_back", ART.sign_back)
    m.group("board")
    m.box("backing", (0, 12, 6.5), (32, 28, 7.25), Paint("steel", 3, "metal", rim=1.2))
    m.plate("face", (0, 12, 6.25), (32, 28, 6.5),
            {"north": (face, [0, 0, 192, 96]), "up": (back, [0, 0, 16, 1]), "down": (back, [0, 0, 16, 1]),
             "east": (back, [0, 0, 1, 16]), "west": (back, [0, 0, 1, 16])})
    m.group("posts")

    def stripes(c):
        if c.face in SIDES and c.p[1] < 30:
            return ("pepper", 2) if math.floor(c.p[1] / 3) % 2 == 0 else ("car_white", 3)
        return None
    post = Paint("car_white", 3, "metal", noise=0.03, decal=stripes)
    for x in (2, 28.5):
        m.box("post", (x, 1.5, 7.25), (x + 1.5, 30, 8.75), post)
        m.box("post_cap", (x - 0.25, 30, 7), (x + 1.75, 30.5, 9), flat("iron", 2))
        for y in (14, 25):
            m.box("clamp", (x - 0.5, y, 7.25), (x + 2, y + 1, 9), flat("steel", 2))
    m.group("feet")
    rub = Paint("rubber", 2, noise=0.04)
    m.box("foot", (0.25, 0, 5), (5.25, 1.5, 11), rub)
    m.box("foot", (26.75, 0, 5), (31.75, 1.5, 11), rub)
    m.box("sandbag", (0.75, 1.5, 5.5), (4.75, 3, 10.5), Paint("burlap", 2, "weave", noise=0.05))
    m.box("sandbag_top", (1.25, 3, 6), (4.25, 3.5, 10), Paint("burlap", 2, "weave", noise=0.05))
    return gui(m)


def prop_pallet():
    m = BModel("gamble_prop_pallet", floor=0)
    wood = Paint("hinoki", 2, "grain", axis="x", noise=0.06)
    m.group("pallet")
    for (z0, z1) in ((0.5, 3), (6.75, 9.25), (13, 15.5)):
        m.box("bottom", (0.5, 0, z0), (15.5, 0.75, z1), wood)
    for (x0, x1) in ((0.5, 2.5), (7, 9), (13.5, 15.5)):
        m.box("stringer", (x0, 0.75, 0.5), (x1, 2.25, 15.5), Paint("hinoki", 1, "grain", axis="z", noise=0.06))
    for (z0, z1) in ((0.5, 3), (3.5, 6), (6.75, 9.25), (10, 12.5), (13, 15.5)):
        m.box("deck", (0.5, 2.25, z0), (15.5, 3, z1), wood)
    m.group("bricks")

    def bond(c):
        # bricks 4 px long and 2 high, every other course shifted 2, a dark joint round each, each brick its own shade
        if c.face == "up":
            row, along = math.floor(c.p[2] / 2), c.p[0]
            sub = c.p[2] - row * 2
        elif c.face in ("north", "south"):
            row, along = math.floor(c.p[1] / 2), c.p[0]
            sub = c.p[1] - row * 2
        else:
            row, along = math.floor(c.p[1] / 2), c.p[2]
            sub = c.p[1] - row * 2
        off = 2 * (row % 2)
        k = math.floor((along + off) / 4)
        if (along + off) % 4 < 0.5 or sub < 0.5:
            return ("brick", 0)
        return ("brick", 1 + int(h3(row, k, 0, 8) * 3))
    m.box("stack", (1.5, 3, 1.5), (14.5, 11, 14.5), Paint("brick", 2, noise=0, decal=bond, rim=0.8))
    m.group("straps")
    band = flat("signal_blue", 1, rim=0.8)
    for z in (4.5, 10.5):
        m.box("strap", (1.25, 3, z), (14.75, 3.25, z + 1), band)
        m.box("strap", (1.25, 11, z), (14.75, 11.25, z + 1), band)
        m.box("strap", (1.25, 3.25, z), (1.5, 11, z + 1), band)
        m.box("strap", (14.5, 3.25, z), (14.75, 11, z + 1), band)
    return gui(m)


# ================================================================================================

FURNITURE = {
    "gamble_table_round": table_round,
    "gamble_table_duel": table_duel,
    "gamble_prop_cement": prop_cement,
    "gamble_prop_rebar": prop_rebar,
    "gamble_prop_cone": prop_cone,
    "gamble_prop_worklight": prop_worklight,
    "gamble_prop_drumfire": prop_drumfire,
    "gamble_prop_sign": prop_sign,
    "gamble_prop_pallet": prop_pallet,
}

DISPLAY = {}
DISPLAY.update(_cards())
for _n in range(1, 6):
    DISPLAY[f"gamble_chips_{_n}"] = (lambda n=_n: chips(n))
DISPLAY["gamble_die"] = die
DISPLAY["gamble_bowl"] = bowl
for _k in ART.BUTTON_WORDS:
    DISPLAY[f"gamble_btn_{_k}"] = (lambda k=_k: button_model(k))
DISPLAY["gamble_whiteboard"] = whiteboard

ALL = {**FURNITURE, **DISPLAY}
