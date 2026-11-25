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
                       the pot in the middle, a padded rail and bolts round its rim (painted on). Six seats round it, one cell each, on a ring of
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
  gamble_cup           a leather dice cup (주사위 통), upturned over a player's dice: 8 px across at its brass lip, 8.5 tall,
                       stitched, its bottom (mouth) on the display's position; turned over (mouth up) when set aside.
  gamble_btn_<die|check|ping|call|ddadang|half|odd|even|roll|ready|leave>
                       a yellow safety-sign plate 12 x 6 px, 1 px thick, standing up, CENTRED on the display's position,
                       its face (north) gamble_art.button: 다이 체크 삥 콜 따당 하프 홀 짝 굴리기 준비 일어나기 in stencil. Lit
                       (light emission): readable in the dim hall. (레이즈 is retired: 섯다 bets 삥 · 체크 · 콜 · 따당 · 하프.)
  gamble_whiteboard    a wall whiteboard 2.5 x 1.5 blocks in an aluminium frame, marker tray with markers and an eraser,
                       old smudges, half-wiped marker scribbles (a blue scrawl, a red ring) and a tally (正), three magnets, a receipt under a magnet top left (as seen from in
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
    """A stackable plastic chair (등받이 의자) round the cell centre (ox, oz), all inside x/z +-4, in few boxes (the
    render budget: a round table has six seats): a dished seat with four drain holes, its rolled lip a darker band
    round its edge, four legs with dark rubber feet, and the backrest on the `back` side ('n' = -z, 's' = +z), leaning
    back in two steps: two uprights and three slats with the gaps between them painted dark, a rounded top rail."""
    m.group(name)
    x0, x1, z0, z1 = ox - 4, ox + 4, oz - 4, oz + 4

    def seat(c):
        if c.face != "up":
            return (paint.ramp, paint.base - 1)            # the rolled lip
        dx, dz = c.p[0] - ox, c.p[2] - oz
        if abs(abs(dx) - 1.5) < 0.1 and abs(abs(dz) - 1.5) < 0.1:
            return (paint.ramp, paint.base - 2)            # drain holes
        if abs(dx) < 2.6 and abs(dz) < 2.6:
            return (paint.ramp, paint.base - 1 if abs(dx) < 1.6 and abs(dz) < 1.6 else paint.base)   # the dish
        return None
    m.box("seat", (x0, 7, z0), (x1, 8, z1), paint(decal=seat))
    feet = paint(rim=0.8, decal=lambda c: ("rubber", 2) if c.p[1] < 1 else None)
    for (lx, lz) in ((x0 + 0.5, z0 + 0.5), (x1 - 1.5, z0 + 0.5), (x0 + 0.5, z1 - 1.5), (x1 - 1.5, z1 - 1.5)):
        m.box("leg", (lx, 0, lz), (lx + 1, 7, lz + 1), feet)
    sgn = -1 if back == "n" else 1
    bz = z0 if back == "n" else z1 - 1
    lean = 0.5 * sgn

    def slats(c):
        col = math.floor(c.p[0] - x0)
        if c.face in ("north", "south") and 9 <= c.p[1] < 15 and col in (1, 3, 4, 6):
            return (paint.ramp, paint.base - 2)            # the gaps between the slats and the uprights
        if c.p[1] >= 15:
            return (paint.ramp, paint.base + 1 if c.p[1] >= 16.5 else paint.base)   # the rounded top rail
        return None
    back_paint = paint(decal=slats)
    m.box("back", (x0, 8, bz), (x1, 12, bz + 1), back_paint)
    m.box("back", (x0, 12, bz + lean), (x1, 17.5, bz + 1 + lean), back_paint(rim=1.4))


def plastic_chair_full(m, name, ox, oz, back, paint):
    """The 1:1 table's chair, with room in its budget for every part (plastic_chair is the round table's, in few
    boxes). A stackable plastic chair (등받이 의자) round the cell centre (ox, oz), all inside x/z +-4: a dished seat with
    four drain holes and a rolled lip under its front and sides, legs that splay a little towards rubber feet, and the
    backrest on the `back` side ('n' = -z, 's' = +z): two uprights, three slats with gaps, a rounded top rail,
    leaning back in two steps."""
    m.group(name)
    x0, x1, z0, z1 = ox - 4, ox + 4, oz - 4, oz + 4
    front_z = (z1 - 0.5, z1) if back == "n" else (z0, z0 + 0.5)

    def dish(c):
        if c.face != "up":
            return None
        dx, dz = c.p[0] - ox, c.p[2] - oz
        if abs(abs(dx) - 1.5) < 0.1 and abs(abs(dz) - 1.5) < 0.1:
            return (paint.ramp, paint.base - 2)            # drain holes
        if abs(dx) < 2.6 and abs(dz) < 2.6:
            return (paint.ramp, paint.base - 1 if abs(dx) < 1.6 and abs(dz) < 1.6 else paint.base)   # the dish
        return None
    m.box("seat", (x0, 7, z0), (x1, 8, z1), paint(decal=dish))
    # the rolled lip under the front edge and down both sides (not under the back)
    m.box("lip", (x0, 6.25, front_z[0]), (x1, 7, front_z[1]), paint)
    sz = (z0, z1 - 0.5) if back == "n" else (z0 + 0.5, z1)
    m.box("lip", (x0, 6.25, sz[0]), (x0 + 0.5, 7, sz[1]), paint)
    m.box("lip", (x1 - 0.5, 6.25, sz[0]), (x1, 7, sz[1]), paint)
    for (lx, lz, sx, sz_) in ((x0, z0, 1, 1), (x1 - 1, z0, -1, 1), (x0, z1 - 1, 1, -1), (x1 - 1, z1 - 1, -1, -1)):
        # upper leg tucked in under the lip, the lower one at the very corner (the splay), a darker rubber foot
        ux, uz = lx + 0.5 * sx, lz + 0.5 * sz_
        m.box("leg", (ux, 3.5, uz), (ux + 1, 6.25, uz + 1), paint(rim=0.8))
        m.box("leg", (lx + 0.25 * sx, 0, lz + 0.25 * sz_), (lx + 0.25 * sx + 1, 3.5, lz + 0.25 * sz_ + 1),
              paint(rim=0.8, decal=lambda c: ("rubber", 2) if c.p[1] < 1 else None))   # its rubber foot painted on
    sgn = -1 if back == "n" else 1
    bz = z0 if back == "n" else z1 - 1
    lean = 0.5 * sgn
    m.box("back_rail", (x0 + 1, 8, bz), (x1 - 1, 8.5, bz + 1), paint)
    for (a, b) in ((x0, x0 + 1), (x1 - 1, x1)):
        m.box("upright", (a, 8, bz), (b, 12, bz + 1), paint)
        m.box("upright", (a, 12, bz + lean), (b, 15.5, bz + 1 + lean), paint)
    for k in range(3):
        a = x0 + 1.75 + k * 1.75
        m.box("slat", (a, 8.5, bz + 0.125), (a + 1, 12, bz + 0.875), paint(rim=0.7))
        m.box("slat", (a, 12, bz + 0.125 + lean), (a + 1, 15.5, bz + 0.875 + lean), paint(rim=0.7))
    m.box("top_rail", (x0, 15.5, bz + lean), (x1, 17.25, bz + 1 + lean), paint(rim=1.4))
    m.box("top_rail", (x0 + 0.5, 17.25, bz + lean + 0.125), (x1 - 0.5, 17.75, bz + 0.875 + lean), paint(rim=1.4))


def stool(m, name, ox, oz, paint):
    """A 포장마차 plastic stool: a round top 8 across with a hand slot through the middle and a ridge round its edge, the
    skirt under it a darker band, four legs out towards the corners."""
    m.group(name)

    def top(c):
        dx, dz = c.p[0] - ox, c.p[2] - oz
        if c.face != "up":
            return (paint.ramp, paint.base - 1) if c.p[1] < 7 else None    # the skirt
        if abs(dx) < 1.6 and abs(dz) < 0.6:
            return (paint.ramp, paint.base - 2)          # the hand slot
        if math.hypot(dx, dz) > 3.3:
            return (paint.ramp, paint.base + 1)          # the rounded edge catching the light
        if (c.i[0] * 3 + c.i[2]) % 5 == 0:
            return (paint.ramp, paint.base - 1)          # the moulded grip texture
        return None
    coarse_cyl(m, "top", (ox, oz), 8, 6, 8, paint(decal=top))
    for (sx, sz) in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
        lx, lz = ox + sx * 3, oz + sz * 3
        m.box("leg", (lx - 0.5, 0, lz - 0.5), (lx + 0.5, 6, lz + 0.5), paint(rim=0.8))


def bucket(m, name, ox, oz, label=None, drips=None, cardboard=False, seed=0):
    """An 18 l paint pail (페인트 통) turned into a seat: white plastic 6 across, its lid on (a rim proud of the body,
    the raised centre and the stiffening ridge painted), a printed label band (label = the ramp) and/or old paint run
    down from the rim (drips = (ramp, step)), the wire bail painted down its sides from its two ears to a black grip
    hanging in front; or a flattened cardboard box on the lid for a cushion."""
    m.group(name)

    def side(c):
        if c.face in ("up", "down"):
            return None
        y = c.p[1]
        dx, dz = c.p[0] - ox, c.p[2] - oz
        a = math.atan2(dz, dx)
        if c.face in ("east", "west") and abs(dz) < 0.5 and 4 <= y < 7:
            return ("car_white", 1) if y >= 5 else ("steel", 3)       # an ear and the bail down from it
        if c.face in ("east", "west", "north") and dz < 0 and 4 <= y < 5:
            return ("steel", 3)                            # the bail round the front
        if drips:
            k = math.floor((a + math.pi) / (2 * math.pi) * 22)
            run = 6.0 - h3(k, 0, 0, seed) * 4.5
            if h3(k, 1, 0, seed) < 0.6 and y > run:
                return (drips[0], drips[1] + (1 if math.floor(y) == math.floor(run) else 0))
        if label and 2 <= y < 5:
            k = math.floor((a + math.pi) / (2 * math.pi) * 24)
            if 3 <= math.floor(y) <= 3 and k % 6 in (1, 2, 3, 4) and h3(k, 3, 0, seed + 3) < 0.75:
                return ("car_white", 3)                    # the printed words
            if math.floor(y) == 2 and k % 12 in (2, 3):
                return ("pepper", 2)                       # a red logo block
            return (label, 1)
        if y < 1 and h3(c.i[0], c.i[1], c.i[2], seed + 9) < 0.4:
            return ("car_white", 1)                        # scuffed foot
        return None
    body = Paint("car_white", 2, "lacquer", noise=0.03, decal=side)
    cyl(m, "pail", (ox, oz), 6, 0, 6.5, body)

    def lid(c):
        r = math.hypot(c.p[0] - ox, c.p[2] - oz)
        if c.face != "up":
            return ("car_white", 1) if c.p[1] < 7 else None    # the ridge under the lid
        if drips and h3(c.i[0], c.i[2], 0, seed) < 0.18 and r < 3:
            return (drips[0], drips[1])
        if r < 3:
            return ("car_white", 2)                        # the raised centre
        if r < 3.5:
            return ("car_white", 3)                        # its lit edge
        return ("car_white", 1)
    coarse_cyl(m, "lid", (ox, oz), 8, 6.5, 8, Paint("car_white", 1, noise=0.02, rim=1.2, decal=lid))
    m.box("bail_grip", (ox - 1.5, 3.5, oz - 4), (ox + 1.5, 4.5, oz - 3), flat("rubber", 2))
    if cardboard:
        def kraft(c):
            if c.face == "up" and abs(c.p[0] - ox + 0.25) < 0.3:
                return ("bean", 1)                         # the fold
            if c.face == "up" and 1 < c.p[2] - oz < 2.5 and -2 < c.p[0] - ox < 1 and h3(c.i[0], c.i[2], 0, 8) < 0.6:
                return ("pepper", 1)                       # a printed arrow from its box days
            if c.face == "east":
                return ("bean", 1)                         # the flap folded down
            return None
        m.box("cardboard", (ox - 4, 8, oz - 3.5), (ox + 3.5, 8.5, oz + 3.5), Paint("bean", 2, "paper", decal=kraft))


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
    """The drum's last turns of black cable: one turn a texel row, laid on a slant so the turns read as a helix, each
    turn's top edge catching the light and the groove under it dark; now and then a printed white length mark."""
    if c.face in ("up", "down"):
        return None
    row = math.floor(c.p[1])
    a = math.atan2(c.p[2] - 8, c.p[0] - 8)
    t = ((a + math.pi) / (2 * math.pi) * 40 + row * 3) % 8
    if row % 3 == 1 and 0 <= t < 0.6:
        return ("car_white", 1)                            # the printed metre marks
    if t < 1.5:
        return ("rubber", 3)
    if t > 6.5:
        return ("rubber", 0)
    return ("rubber", 2 if row % 2 else 1)


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


def coarse_bands(d, step=2):
    """bands() of a big circle in rows `step` px deep (d a whole number of steps), the half widths r less whole steps:
    a round edge of few steps (each a box and its faces), its corners on one grid, smooth enough a few blocks across."""
    r = d / 2
    out = []
    for k in range(int(round(d / step))):
        v = -r + (k + 0.5) * step
        hw = r - round((r - math.sqrt(max(0.0, r * r - v * v))) / step) * step
        a, b = -r + k * step, -r + (k + 1) * step
        hw = max(step / 2, hw)
        if out and out[-1][2] == hw:
            out[-1] = (out[-1][0], b, hw)
        else:
            out.append((a, b, hw))
    return out


def coarse_cyl(m, name, c, d, lo, hi, paint, step=2):
    for (a, b, hw) in coarse_bands(d, step):
        m.box(name, (c[0] - hw, lo, c[1] + a), (c[0] + hw, hi, c[1] + b), paint(vrim=0))


def table_round():
    """The cable drum, drawn for the render budget (440 faces; six of these stand in the hall at once). Shrunk by 2, its
    faces hold 2 texels a model unit, which the pack's face reducer only culls, never merges: every box drawn here is
    paid for. So its discs are stepped in 2 px steps (coarse_cyl), the top flange and the felt one disc, every small
    part (the bolts, the felt's padded rail and its seam, the cable's tag) painted on, and the seats are few boxes."""
    m = BModel("gamble_table_round", floor=0, scale=2)
    cx = cz = 8
    m.group("drum")

    def polar(c):
        return math.hypot(c.p[0] - cx, c.p[2] - cz), math.atan2(c.p[2] - cz, c.p[0] - cx)

    def nut(c, r0, n, off):
        """A square nut on a washer, n round at radius r0 (the up face): ('iron'|'steel', step) or None."""
        r, a = polar(c)
        k = round(a / (2 * math.pi) * n - off)
        aa = 2 * math.pi * (k + off) / n
        nx, nz = cx + r0 * math.cos(aa), cz + r0 * math.sin(aa)
        dx, dz = abs(c.p[0] - nx), abs(c.p[2] - nz)
        if dx < 0.6 and dz < 0.6:
            return ("iron", 4 if c.p[0] < nx and c.p[2] < nz else 3)
        if dx < 1.1 and dz < 1.1:
            return ("steel", 2)
        return None

    def flange_paint(seed):
        def boards(c):
            # boards across the flange, a dark joint between them, weathered grey towards the edge, a painted
            # yellow stencil arrow ("roll this way") on the side of the lower one; the bolts through both flanges
            r, a = polar(c)
            if c.face in SIDES:
                if seed == 1 and -0.45 < a < -0.05 and math.floor(c.p[1]) == 1:
                    return ("hazard", 1)
                if seed == 2:
                    y = math.floor(c.p[1])
                    if y == 13:
                        return ("felt", 1)                 # the felt pulled down over the edge
                    if y == 12 and (a * 24 / (2 * math.pi)) % 2 < 0.25:
                        return ("iron", 3)                 # the bolt heads' ends through the rim
                    return ("wood", 0 if y == 12 else 1)
                return ("wood", 0 if math.floor(c.p[1]) == 1 else 1)
            if c.face == "up" and seed == 1:
                n = nut(c, 20, 8, 0)
                if n:
                    return n
            if c.face == "up" or c.face == "down":
                if math.floor(c.p[0]) % 5 == 0:
                    return ("wood_dark", 1)
                if r > 22:
                    return ("wood", 0)
            return None
        return Paint("wood", 1, "grain", axis="z", noise=0.06, decal=boards, seed=seed)
    coarse_cyl(m, "flange_low", (cx, cz), 46, 0, 2, flange_paint(1))
    coarse_cyl(m, "core", (cx, cz), 34, 2, 11, Paint("rubber", 2, noise=0.0, decal=cable_wound, rim=0.5))

    # the top flange with the felt on it in one disc: wood up to y 13, the felt from there, its top the playing felt
    # inside a padded rail (a lighter ring with a stitched seam), the wooden rim round it with its bolts
    burns = [(-9, -5, 1.4), (12.5, 4, 1.1), (3, 14, 1.3), (-15, 9, 1.0), (6.5, -15.5, 1.2), (-4, 2.5, 0.9), (15, -8, 1.0)]
    tapes = [("silver", (-13, -14), (-5, -6), 2.2), ("silver", (-13, -6), (-5, -14), 2.2),
             ("blue", (8, 9), (17, 12.5), 2.0)]
    felt = felt_top(cx, cz, 21, burns, tapes, pot=7)
    top_wood = flange_paint(2)

    def top(c):
        if c.face != "up":
            return top_wood.decal(c)
        r, a = polar(c)
        if r > 22.5:
            return nut(c, 23, 12, 0.5) or ("wood", 0 if math.floor(c.p[0]) % 5 else 1)
        if r > 20.5:
            # the padded rail: lit on its outer half, the seam stitched along its middle
            if abs(r - 21.5) < 0.5 and math.floor(a * 24) % 2 == 0:
                return ("felt", 3)
            return ("felt", 3 if r > 21.5 else 2)
        if r > 19.5:
            return ("felt", 0)                             # the shadow under the rail
        return felt(c)
    coarse_cyl(m, "flange_top", (cx, cz), 48, 11, 14, Paint("wood", 1, "grain", axis="z", noise=0.06, decal=top, seed=2))
    # the loose end of the cable: out of the winding on the east side, over the lower flange, down to the floor, a
    # yellow tag on it, its copper showing
    m.group("cable")

    def cable(c):
        if 29 <= c.p[0] < 30 and c.p[1] > 5:
            return ("hazard", 1)                           # the tag
        if c.p[0] >= 37:
            return ("copper", 3)                           # the bare copper
        return None
    rub = Paint("rubber", 2, noise=0, rim=0.6, decal=cable)
    m.box("cable_end", (25, 6, 6), (34, 7, 7), rub)
    m.box("cable_end", (33, 1, 6), (34, 6, 7), rub)
    m.box("cable_end", (34, 0, 6), (38, 1, 7), rub)
    # the seats, seat 0 in front, clockwise
    blue, red, white = plastic("signal_blue", 1), plastic("pepper", 2), plastic("car_white", 2)
    for i, (sx, sz) in enumerate(ROUND_SEATS):
        ox, oz = sx * 16 + 8, sz * 16 + 8
        if i == 0:
            plastic_chair(m, "seat0_chair", ox, oz, "n", blue)
        elif i == 3:
            plastic_chair(m, "seat3_chair", ox, oz, "s", white)
        elif i == 1:
            bucket(m, "seat1_bucket", ox, oz, label="sapphire", drips=("sapphire", 2), seed=1)
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
    """1:1 탁자: a slatted lumber crate (각목 상자): four square corner posts, three boards a side nailed over them with
    gaps between (the dark inside showing), "취급주의" stencilled in faded red on the front, a plywood top with its plies
    on the edge, a square of felt tacked on; a plastic chair each side."""
    m = HModel("gamble_table_duel", floor=0)
    m.group("crate")
    post = Paint("wood", 1, "grain", axis="y", noise=0.05)
    for (x, z) in ((1.25, 1.25), (12.75, 1.25), (1.25, 12.75), (12.75, 12.75)):
        m.box("post", (x, 0, z), (x + 2, 11, z + 2), post)
    m.box("inside", (3.25, 0.5, 3.25), (12.75, 10.5, 12.75), flat("wood_dark", 0))

    def nails(axis):
        def decal(c):
            u = c.p[0] if axis == "x" else c.p[2]
            v = c.p[1] - math.floor(c.p[1] / 3.5) * 3.5
            if c.face in SIDES and (abs(u - 2.25) < 0.5 or abs(u - 13.75) < 0.5) and 0.9 < (c.p[1] % 3.5) < 2.1 and (c.i[1] % 2 == 1):
                return ("iron", 3)                         # nail heads over the posts
            if c.face in SIDES and h3(c.i[0], c.i[1], c.i[2], 5) < 0.04:
                return ("wood_dark", 2)                    # knots
            return None
        return decal
    for i, y in enumerate((0.5, 4, 7.5)):
        m.box("board_n", (1, y, 0.5), (15, y + 3, 1.25), Paint("wood", 2, "grain", axis="x", seed=i, decal=nails("x")))
        m.box("board_s", (1, y, 14.75), (15, y + 3, 15.5), Paint("wood", 2, "grain", axis="x", seed=i + 3, decal=nails("x")))
        m.box("board_w", (0.5, y, 1.25), (1.25, y + 3, 14.75), Paint("wood", 2, "grain", axis="z", seed=i + 6, decal=nails("z")))
        m.box("board_e", (14.75, y, 1.25), (15.5, y + 3, 14.75), Paint("wood", 2, "grain", axis="z", seed=i + 9, decal=nails("z")))
    stencil = _surface("gamble_crate_stencil", lambda: ART.stencil_print("취급주의", 80, 16, seed=3))
    _print_plate(m, "stencil", (1.5, 4.25, 0.48), (14.5, 6.75, 0.48), stencil, 80, 16)
    arrows = _surface("gamble_crate_arrows", lambda: ART.arrows_print(32, 16))
    m.plate("arrows", (15.52, 7.75, 3), (15.52, 10.25, 13), {"east": (arrows, [0, 0, 32, 16]), "west": (arrows, [32, 0, 0, 16])})

    def plywood(c):
        if c.face in SIDES:
            return ("wood", 3 if math.floor(c.p[1] * 2) % 2 else 1)   # the plies
        return None
    m.box("top", (0.5, 11, 0.5), (15.5, 12, 15.5), Paint("hinoki", 2, "grain", axis="x", decal=plywood))
    m.group("felt")
    m.box("felt", (2, 12, 2), (14, 12.25, 14), Paint("felt", 2, "weave", noise=0.02, rim=0.4,
                                                       decal=felt_top(8, 8, 9, [(3.5, -3, 1.0), (-4, 4, 0.8)],
                                                                      [("silver", (-6, -6), (-2, -6), 1.6)])))
    for (x, z) in ((2.25, 2.25), (13.25, 2.25), (2.25, 13.25), (13.25, 13.25), (7.75, 2.25), (7.75, 13.25), (2.25, 7.75), (13.25, 7.75)):
        m.box("tack", (x, 12.25, z), (x + 0.5, 12.5, z + 0.5), flat("steel", 4))
    plastic_chair_full(m, "seat0_chair", 8, -8, "n", plastic("signal_blue", 1))
    plastic_chair_full(m, "seat1_chair", 8, 24, "s", plastic("pepper", 2))
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

CHIP = {"R": ("pepper", 2, ("car_white", 3)), "B": ("signal_blue", 1, ("car_white", 3)), "W": ("car_white", 3, ("pepper", 2)),
        "K": ("iron", 3, ("gold", 2))}


def chip_paint(cx, cz, colour, edge=True):
    """One casino chip round (cx, cz), lying flat: on its edge six white inserts round it (the stripes); on its faces a
    rim with the same inserts, a fine ring inside it and a plain centre inlay a step lighter. On edge (edge=False:
    standing on its rim, axis z) the same seen turned."""
    ramp, step, spot = CHIP[colour]

    def decal(c):
        if edge:
            dx, dz = c.p[0] - cx, c.p[2] - cz
            cap = c.face in ("up", "down")
        else:
            dx, dz = c.p[0] - cx, c.p[1] - cz
            cap = c.face in ("north", "south")
        a = math.atan2(dz, dx)
        r = math.hypot(dx, dz)
        sector = math.floor((a + math.pi) / (2 * math.pi) * 16 + 0.5) % 4 == 0
        if cap:
            if r > 2.0:
                return spot if sector else (ramp, step)
            if r > 1.3:
                return (ramp, step - 1)
            return (ramp, step + 1) if colour != "W" else ("car_white", 2)
        return spot if sector else (ramp, step)
    return Paint(ramp, step, noise=0, rim=0.6, decal=decal)


# pile -> stacks [(gx, gz, height, colour)], fanned-out runs [(gx, gz, count, colour, (dx, dz) step)], chips standing on
# their edge [(gx, gz, colour)], loose chips [(gx, gz, colour)], on a grid of GRID px: no two chips' boxes overlap


# pile -> stacks [(gx, gz, height, colour)], fanned-out runs [(gx, gz, count, colour, (dx, dz) step)], chips standing on
# their edge [(gx, gz, colour)], loose chips [(gx, gz, colour)], on a grid of GRID px: no two chips' boxes overlap


def _mix(n, seed, bias="RBW"):
    return "".join(bias[int(h3(i, seed, 0, 77) * len(bias))] for i in range(n))


# pile -> stacks [(gx, gz, height, colour[, (dx, dz) the upper half's offset in quarter px])], fanned-out runs, chips on
# their edge, loose chips, on a grid of GRID px (rows may sit half a step over): no two chips' boxes overlap, so no two
# faces share a place. Render budget (300 faces, display models): a straight stack is one solid to the pack's face
# reducer (about 12 faces, its chips' edges kept as painted), each offset part, fanned or loose chip another, so the
# biggest piles have tall stacks rather than many loose chips, and only a few stacks are knocked off true.
GRID = 6.5


PILES = {
    1: ([(0, 0, 3, "R"), (1, 0, 2, "B")], [], [], [(0.5, -1, "W")]),
    2: ([(0, 0, 5, "R"), (1, 0, 3, "B"), (0.5, 1, 4, "W")], [(0.25, -1.1, 3, "R", (1.25, 0.25))], [], [(2, -1, "B")]),
    3: ([(0, 0, 7, "R", (1, 0)), (1, 0, 5, "B"), (0.5, 1, 6, "W", (-1, 1)), (1.5, 1, 4, "R")], [(0, -1.1, 4, "B", (1.25, 0.25))], [],
        [(2, 0, "W"), (1.5, -1, "R")]),
    4: ([(0, 0, 10, "R", (-1, 0)), (1, 0, 8, "B"), (2, 0, 9, "W", (1, -1)), (0.5, 1, 6, "R"), (1.5, 1, 7, "B", (1, 1)), (2.5, 1, 5, "K")],
        [(-0.25, -1.1, 5, "W", (1.25, 0.25)), (2.25, -1.1, 3, "R", (-1.0, 0.5))], [(-0.5, 1, "B")], [(1.15, -1, "K"), (1, 2, "R")]),
    5: ([(0, 0, 14, "R", (-1, 0)), (1, 0, 12, "B"), (2, 0, 13, "W", (1, 0)), (-0.5, 1, 9, "K"), (0.5, 1, 11, "R"), (1.5, 1, 10, "B"),
         (2.5, 1, 8, "W"), (0, 2, 6, "R"), (1, 2, 7, "K")],
        [(-0.5, -1.1, 3, "B", (1.25, 0.25)), (2.25, -1.1, 3, "R", (-1.0, 0.5)), (2.25, 2.2, 2, "W", (1.0, 0.75))],
        [(3, 0, "R"), (-1, 0, "W")], [(1, -1.1, "K"), (-1, 2, "B"), (3.5, 0.9, "R")]),
}


def _pile_centre(n):
    stacks, fans, edges, loose = PILES[n]
    pts = [(a, b) for (a, b, *_r) in stacks + fans + edges + loose]
    xs, zs = [a for a, _ in pts], [b for _, b in pts]
    return (min(xs) + max(xs)) / 2, (min(zs) + max(zs)) / 2


def chips(n):
    """Neat casino-chip stacks, each chip a disc 6 across and 1 thick, one colour a stack, the upper half of some tall
    stacks sitting a hair off the lower (a hand stacked them); from the third pile up a stack knocked into a fanned-out run, then chips
    standing on their edge leaning on a stack, and loose chips. Black chips (K, gold inserts) from the fourth."""
    m = BModel(f"gamble_chips_{n}", floor=0, shift=(0, 8, 0))
    stacks, fans, edges, loose = PILES[n]
    gx0, gz0 = _pile_centre(n)
    bx, bz = round((8 - gx0 * GRID) * 4) / 4, round((8 - gz0 * GRID) * 4) / 4

    def at(gx, gz):
        # on the quarter pixel (the pile's middle snapped, then whole and half steps stay exact): off it the pack's
        # face reducer cannot join a stack's chips into one solid
        return round((bx + gx * GRID) * 4) / 4, round((bz + gz * GRID) * 4) / 4
    m.group("stacks")
    for i, (gx, gz, hgt, col, *step) in enumerate(stacks):
        x, z = at(gx, gz)
        for k in range(hgt):
            # hand stacked: the upper half sits a hair off the lower (one offset, not one a chip: each costs a solid)
            up = bool(step) and k >= (hgt + 1) // 2
            jx, jz = (step[0][0] * 0.25, step[0][1] * 0.25) if up else (0, 0)
            cyl(m, "chip", (x + jx, z + jz), 6, k, k + 1, chip_paint(x + jx, z + jz, col))
    m.group("fans")
    for i, (gx, gz, cnt, col, (sx, sz)) in enumerate(fans):
        x, z = at(gx, gz)
        for k in range(cnt):
            # a run knocked over: each chip a little further along and a layer up, shingled
            px, pz = x + sx * k - sx * (cnt - 1) / 2, z + sz * k - sz * (cnt - 1) / 2
            px, pz = round(px * 4) / 4, round(pz * 4) / 4      # on the quarter pixel (see at)
            cyl(m, "fan", (px, pz), 6, k * 0.5, k * 0.5 + 0.5, chip_paint(px, pz, col))
    m.group("edges")
    for i, (gx, gz, col) in enumerate(edges):
        x, z = at(gx, gz)
        cyl(m, "edge", (x, 3), 6, z - 0.5, z + 0.5, chip_paint(x, 3, col, edge=False), axis="z")
    m.group("loose")
    for i, (gx, gz, col) in enumerate(loose):
        x, z = at(gx, gz)
        cyl(m, "chip", (x, z), 6, 0, 1, chip_paint(x, z, col))
    return gui(m, fill=0.8)


# ================================================================================================
# The die (plain model: 32 texels a face)
# ================================================================================================

# the die's faces: model face -> number
DIE_FACES = {"up": 1, "down": 6, "north": 2, "south": 5, "west": 3, "east": 4}


def die():
    """The die with rounded edges: three slabs crossed (each face's 3.5 x 3.5 middle from its own slab, a quarter px
    proud of the edge strips), so every edge and corner is cut round; each face shows the middle 28 x 28 of its tile."""
    tex = _surface("gamble_die_faces", ART.die_atlas)
    m = Model("gamble_die")

    T_ = ART.DIE_TILE

    def tile(n, inset=2):
        c, r = ART.DIE_TILES[n]
        return [c * T_ + inset, r * T_ + inset, c * T_ + T_ - inset, r * T_ + T_ - inset]

    bev = [0, 15, 1, 16]        # a bevel texel: the edge column of the 1's tile
    m.group("die")
    lo, hi, a, b = 6, 10, 6.25, 9.75
    m.box("slab_y", (a, lo, a), (b, hi, b), tex, uv={"up": tile(1), "down": tile(6), "north": bev, "south": bev, "east": bev, "west": bev})
    m.box("slab_x", (lo, a, a), (hi, b, b), tex, uv={"east": tile(4), "west": tile(3), "north": bev, "south": bev, "up": bev, "down": bev})
    m.box("slab_z", (a, a, lo), (b, b, hi), tex, uv={"north": tile(2), "south": tile(5), "east": bev, "west": bev, "up": bev, "down": bev})
    return gui(m, fill=0.7)


# ================================================================================================
# 사발 (upturned)
# ================================================================================================

def bowl():
    """An upturned white porcelain rice bowl (사발): a rim 11 across rolled at the bottom, the body narrowing up to an
    unglazed foot ring on top; under the glaze a double cobalt line above the rim and a band of little blue sprays
    round the body, a fine line round the foot; the glaze runs thick and greenish at the bottom edge, with a glossy
    streak and a fine crackle; and a chip out of the rim."""
    m = BModel("gamble_bowl", floor=0, shift=(0, 8, 0))

    def glaze(c):
        y = c.p[1]
        a = math.atan2(c.p[2] - 8, c.p[0] - 8)
        if c.face in SIDES:
            row = math.floor(y)
            if y < 0.75 and -2.0 < a < -1.7:
                return ("clay", 3)                         # the chip out of the rim
            if y < 0.5:
                return ("celadon", 3)                      # the glaze pooled at the edge
            if row == 1:
                return ("indigo", 2)
            if 1.75 <= y < 2.0 or row == 4 and y > 4.75:
                return ("indigo", 3)
            if row in (2, 3):
                k = math.floor((a + math.pi) / (2 * math.pi) * 8)
                f = (a + math.pi) / (2 * math.pi) * 8 - k
                if abs(f - 0.5) < 0.09 and row == 3 or abs(f - 0.5) < 0.22 and row == 2 and h3(k, row, 0, 3) < 0.8:
                    return ("sapphire", 1)                 # a little cobalt spray, eight round
            if abs(a - 2.3) < 0.15 and 1.5 < y < 4:
                return ("porcelain", 2)                    # the glossy streak (lit later by its own step)
            if (c.i[0] * 7 + c.i[1] * 3 + c.i[2] * 5) % 23 == 0:
                return ("porcelain", 1)                    # fine crackle
        return None
    p = Paint("porcelain", 2, "lacquer", noise=0.02, rim=1.3, decal=glaze)
    for (d, lo, hi) in ((11, 0, 1.5), (10.5, 1.5, 2.5), (10, 2.5, 3.5), (9, 3.5, 4.5), (7, 4.5, 5.25)):
        cyl(m, "bowl", (8, 8), d, lo, hi, p)
    foot = Paint("clay", 3, noise=0.05, rim=1.0,
                 decal=lambda c: ("clay", 2) if c.face == "up" and math.hypot(c.p[0] - 8, c.p[2] - 8) < 1.6 else None)
    cyl(m, "foot", (8, 8), 5, 5.25, 6.25, foot)
    return gui(m, fill=0.8)


# ================================================================================================
# 주사위 컵 (upturned)
# ================================================================================================

def cup():
    """A leather dice cup (주사위 통), upturned over a player's two dice: 8 across at the rim, a brass-bound lip, the
    body narrowing a little up to its closed end; two rows of pale stitching (above the lip and under the top), a worn
    patch where the hand grips it, a little embossed die on the top. 8.5 tall; bottom (its mouth) on the display's
    position."""
    m = BModel("gamble_cup", floor=0, shift=(0, 8, 0))

    def hide(c):
        y = c.p[1]
        a = math.atan2(c.p[2] - 8, c.p[0] - 8)
        if c.face in SIDES:
            k = math.floor((a + math.pi) / (2 * math.pi) * 24)
            if (1.6 < y < 1.9 or 6.6 < y < 6.9) and k % 2 == 0:
                return ("paper", 3)                         # the stitches
            if 2.5 < y < 5.5 and abs(a - 0.6) < 0.45 and h3(c.i[0], c.i[1], c.i[2], 7) < 0.7:
                return ("leather", 4)                      # rubbed shiny where it is held
            if h3(c.i[0], c.i[1], c.i[2], 9) < 0.08:
                return ("leather", 1)                      # scuffs
        if c.face == "up":
            dx, dz = c.p[0] - 8, c.p[2] - 8
            if max(abs(dx), abs(dz)) < 1.6 and (abs(dx) > 1.1 or abs(dz) > 1.1):
                return ("leather", 1)                      # the embossed die's edge
            if abs(dx) < 0.4 and abs(dz) < 0.4:
                return ("leather", 1)
        return None
    hide_p = Paint("leather", 2, noise=0.05, rim=1.1, decal=hide)
    lip = Paint("brass", 3, "metal", noise=0.03, rim=1.3)
    cyl(m, "lip", (8, 8), 8, 0, 1, lip)
    for (d, lo, hi) in ((7.5, 1, 4), (7, 4, 7.25), (6.5, 7.25, 8.5)):
        cyl(m, "cup", (8, 8), d, lo, hi, hide_p)
    return gui(m, fill=0.75)


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
        # half-wiped blue marker: an old scrawled line along the bottom (viewer's left), a red ring with a tick
        if -11 < x < 4 and -3 < y < 0.5:
            wave = -1.3 + 0.9 * math.sin(x * 0.9)
            if abs(y - wave) < 0.55 and h3(c.i[0], c.i[1], 0, 41) < 0.6:
                return ("sapphire", 3)
        if 13 < x < 19 and 12 < y < 18.5:
            r = math.hypot(x - 16, y - 15.25)
            if abs(r - 2.4) < 0.5 and h3(c.i[0], c.i[1], 0, 42) < 0.55:
                return ("pepper", 3)
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
    cyl(m, "magnet", (4.5, 17.5), 2, 14.35, 15, flat("sapphire", 2, rim=1.0), axis="z")
    cyl(m, "magnet", (-9, 1), 2, 14.35, 15, flat("hazard", 1, rim=1.0), axis="z")
    return gui(m)


# ================================================================================================
# Props (furniture)
# ================================================================================================

def _sack(m, name, x0, y0, z0, x1, y1, z1, seed, along="x", rot=None, sag=0.0):
    """A 40 kg cement sack, a pillow of kraft paper: a full core, rounded top and bottom (caps inset), the sewn ends
    pinched into a seam strip, the middle sagging by `sag` (a sack lying over the gap between two); a red band
    printed across it with white specks for the words. rot: the same turn for all its parts."""
    def print_(c):
        x, z = c.p[0], c.p[2]
        if c.face == "up":
            u = x if along == "z" else z
            mid = (x0 + x1) / 2 if along == "z" else (z0 + z1) / 2
            w = (x1 - x0) if along == "z" else (z1 - z0)
            along_u = z if along == "z" else x
            lo, hi = (z0, z1) if along == "z" else (x0, x1)
            if abs(along_u - (lo + hi) / 2) < 1.5:
                return ("car_white", 3) if h3(c.i[0], c.i[2], 0, seed) < 0.15 else ("pepper", 2)
            if abs(u - mid) > w / 2 - 0.9:
                return ("bean", 1)                         # the crease along the sides
        if c.face in SIDES and h3(c.i[0], c.i[1], c.i[2], seed + 1) < 0.08:
            return ("stone", 3)                            # cement dust on the paper
        return None
    p = Paint("bean", 2, "paper", noise=0.05, decal=print_, seed=seed)
    seam = Paint("bean", 1, "paper", noise=0.05)
    L = (x1 - x0) if along == "x" else (z1 - z0)
    parts = [(0, 0.3, 0), (0.3, 0.7, sag), (0.7, 1.0, 0)] if sag else [(0, 1, 0)]
    for (a, b, dy) in parts:
        if along == "x":
            ax0, ax1 = x0 + 0.5 + (L - 1) * a, x0 + 0.5 + (L - 1) * b
            m.box(name, (ax0, y0 + 0.5 - dy, z0), (ax1, y1 - 0.5 - dy, z1), p, rotation=rot)
            m.box(name + "_top", (ax0, y1 - 0.5 - dy, z0 + 0.75), (ax1, y1 - dy, z1 - 0.75), p, rotation=rot)
            m.box(name + "_bot", (ax0, y0 - dy, z0 + 0.75), (ax1, y0 + 0.5 - dy, z1 - 0.75), p, rotation=rot)
        else:
            az0, az1 = z0 + 0.5 + (L - 1) * a, z0 + 0.5 + (L - 1) * b
            m.box(name, (x0, y0 + 0.5 - dy, az0), (x1, y1 - 0.5 - dy, az1), p, rotation=rot)
            m.box(name + "_top", (x0 + 0.75, y1 - 0.5 - dy, az0), (x1 - 0.75, y1 - dy, az1), p, rotation=rot)
            m.box(name + "_bot", (x0 + 0.75, y0 - dy, az0), (x1 - 0.75, y0 + 0.5 - dy, az1), p, rotation=rot)
    if along == "x":
        for (sx0, sx1) in ((x0, x0 + 0.5), (x1 - 0.5, x1)):
            m.box(name + "_seam", (sx0, y0 + 0.75, z0 + 0.5), (sx1, y1 - 0.75, z1 - 0.5), seam, rotation=rot)
    else:
        for (sz0, sz1) in ((z0, z0 + 0.5), (z1 - 0.5, z1)):
            m.box(name + "_seam", (x0 + 0.5, y0 + 0.75, sz0), (x1 - 0.5, y1 - 0.75, sz1), seam, rotation=rot)


def prop_cement():
    m = HModel("gamble_prop_cement", floor=0)
    m.group("sacks")
    _sack(m, "sack", 2.5, 0, 1.75, 13.5, 2.5, 7.75, 1)
    _sack(m, "sack", 2.5, 0, 8.25, 13.5, 2.5, 14.25, 2)
    _sack(m, "sack", 2, 2.5, 2.5, 7.75, 5, 13.5, 3, along="z")
    _sack(m, "sack", 8.25, 2.5, 2.5, 14, 5, 13.5, 4, along="z")
    m.group("top")
    _sack(m, "sack_top", 3, 5.25, 5, 13, 7.5, 11, 9, rot=("y", 22.5, (8, 6, 8)), sag=0.5)
    m.group("print")
    label = _surface("gamble_cement_print", lambda: ART.stencil_print("시멘트", 48, 16, ("pepper", 2), seed=7, wear=0.05))
    _print_plate(m, "print", (4.75, 0.5, 1.73), (11.25, 2.25, 1.73), label, 48, 16)
    m.group("spill")
    powder = Paint("stone", 3, "speckle", noise=0.05)
    m.box("powder", (9, 0, 0.25), (14, 0.5, 1.5), powder)
    m.box("powder", (10, 0.5, 0.5), (13, 1, 1.5), powder)
    m.box("powder_tear", (9.5, 2.5, 1.25), (12.5, 2.75, 2.5), powder)
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
    """안전 고깔: a square black rubber base with cut corners, an orange cone tapering in half px steps, two wide
    retro-reflective bands (silvery white with a diagonal glint), scuffed and dirty near the bottom, an open top."""
    m = BModel("gamble_prop_cone", floor=0)
    m.group("base")

    def base_d(c):
        if c.face == "up" and h3(c.i[0], c.i[2], 0, 6) < 0.15:
            return ("rubber", 3)
        return None
    rub = Paint("rubber", 2, noise=0.04, decal=base_d)
    m.box("base", (2.5, 0, 2), (13.5, 1, 14), rub)
    m.box("base", (2, 0, 2.5), (2.5, 1, 13.5), rub)
    m.box("base", (13.5, 0, 2.5), (14, 1, 13.5), rub)
    m.box("base_ring", (4, 1, 4), (12, 1.5, 12), flat("rubber", 2))
    m.group("cone")

    def grime(c):
        if c.face in SIDES and c.p[1] < 4 and h3(c.i[0], c.i[1], c.i[2], 3) < 0.4 - c.p[1] * 0.08:
            return ("fox", 0)
        return None

    def glint(c):
        if c.face in SIDES and (math.floor(c.p[0] + c.p[2] + c.p[1] * 1.5) % 5 == 0):
            return ("snow", 2)
        if c.face in SIDES and h3(c.i[0], c.i[1], c.i[2], 4) < 0.15:
            return ("car_white", 2)
        return None
    orange = Paint("fox", 2, noise=0.0, rim=0.5, grad=0.3, decal=grime)
    band = Paint("car_white", 3, noise=0.0, rim=0.5, grad=0.3, decal=glint)
    y = 1.5
    while y < 15:
        d = max(2.5, round((9.5 - (y - 1.5) * 0.52) * 2) / 2)
        p = band if (5 <= y < 7.5 or 9.5 <= y < 11) else orange
        cyl(m, "cone", (8, 8), d, y, y + 0.5 if y < 14.5 else 15, p)
        y += 0.5
    cyl(m, "top", (8, 8), 2, 15, 15.25, flat("rubber", 1))
    return gui(m)


def prop_worklight():
    """삼각대 작업등: a telescopic tripod (three legs from a collar, rubber feet), a yellow mast with a clamp knob, a T
    bar with two halogen floodlights tilted down to the front: yellow die-cast housings with a carry handle, cooling
    fins behind, the lit glass behind a wire guard; their cables into a junction box on the mast and down to a coil and
    an orange plug on the ground."""
    m = BModel("gamble_prop_worklight", floor=0)
    steel = Paint("steel", 3, "metal", rim=1.2)
    m.group("stand")
    m.box("leg", (7.5, 0, 7.5), (8.5, 10, 8.5), steel, rotation=("x", -22.5, (8, 10, 8)))
    m.box("leg", (7.5, 0, 9.75), (8.5, 10, 10.75), steel, rotation=("z", 22.5, (8, 10, 10.25)))
    m.box("leg", (7.5, 0, 9.75), (8.5, 10, 10.75), steel, rotation=("z", -22.5, (8, 10, 10.25)))
    m.box("foot", (7.25, -0.0, 3.5), (8.75, 0.5, 5), flat("rubber", 2))
    m.box("foot", (3.5, 0, 9.5), (5, 0.5, 11), flat("rubber", 2))
    m.box("foot", (11, 0, 9.5), (12.5, 0.5, 11), flat("rubber", 2))
    m.box("collar", (7, 9, 7), (9, 11, 11), Paint("iron", 3, "metal", rim=1.2))
    m.box("mast", (7.25, 1, 7.25), (8.75, 17, 8.75), Paint("hazard", 1, "metal", rim=1.1))
    m.box("mast_up", (7.5, 17, 7.5), (8.5, 26.5, 8.5), steel)
    m.box("clamp", (6, 16, 7.5), (7.25, 17.5, 8.5), flat("rubber", 2))
    m.box("knob", (5.5, 16.25, 7.75), (6, 17.25, 8.25), flat("rubber", 3))
    m.box("junction", (8.75, 19, 7.25), (10.25, 21.5, 8.75), Paint("hazard", 1, "metal", rim=1.2))
    m.box("junction_lid", (8.75, 21.5, 7.5), (10, 21.75, 8.5), flat("iron", 2))
    m.group("lamps")
    m.box("tbar", (4, 26.5, 7.25), (12, 27.5, 8.75), steel)
    tilt = -22.5
    for (x0, x1) in ((4, 7.5), (8.5, 12)):
        rot = ("x", tilt, ((x0 + x1) / 2, 28, 8))
        m.box("housing", (x0, 27.5, 6.5), (x1, 31, 9), Paint("hazard", 1, "metal", rim=1.3), rotation=rot)
        m.box("glass", (x0 + 0.5, 28, 6.25), (x1 - 0.5, 30.5, 6.5), glow("lamp", 3), rotation=rot)
        for k in range(3):
            gx = x0 + 0.75 + k * (x1 - x0 - 1.75) / 2
            m.box("guard", (gx, 27.75, 5.75), (gx + 0.25, 30.75, 6), flat("iron", 2), rotation=rot)
        m.box("guard", (x0 + 0.5, 29.125, 5.75), (x1 - 0.5, 29.375, 6), flat("iron", 2), rotation=rot)
        m.box("fins", (x0 + 0.5, 28, 9), (x1 - 0.5, 30.5, 9.75), Paint("iron", 2, "metal",
              decal=lambda c: ("iron", 0) if c.face == "south" and math.floor(c.p[1] * 2) % 2 else None), rotation=rot)
        m.box("handle", (x0 + 0.5, 31, 7.5), (x0 + 1, 31.75, 8), flat("rubber", 2), rotation=rot)
        m.box("handle", (x1 - 1, 31, 7.5), (x1 - 0.5, 31.75, 8), flat("rubber", 2), rotation=rot)
        m.box("handle", (x0 + 0.5, 31.75, 7.5), (x1 - 0.5, 32, 8), flat("rubber", 2), rotation=rot)
    m.group("cable")
    m.box("lead", (6.5, 26, 9.5), (7, 27.5, 10), flat("rubber", 2))
    m.box("lead", (9, 26, 9.5), (9.5, 27.5, 10), flat("rubber", 2))
    m.box("lead", (7, 26, 9.5), (9, 26.5, 10), flat("rubber", 2))
    m.box("cable", (9.25, 1, 8.75), (9.75, 26, 9.25), flat("rubber", 2))
    ring(m, "coil", (4.5, 13.5), 5, 3, 0, 0.75, flat("rubber", 2, rim=0.6))
    ring(m, "coil", (4.5, 13.5), 4, 3, 0.75, 1.25, flat("rubber", 2, rim=0.6))
    m.box("cable", (7, 0, 13.25), (9.25, 0.5, 13.75), flat("rubber", 2))
    m.box("cable", (9.25, 0, 9.25), (9.75, 1, 13.75), flat("rubber", 2))
    m.box("plug", (9.75, 0, 13), (11.25, 1.25, 14.5), Paint("fox", 2, "lacquer", rim=1.2))
    m.box("plug_pin", (11.25, 0.5, 13.5), (11.75, 0.75, 14), flat("brass", 2))
    return gui(m)


def prop_drumfire():
    """드럼통 모닥불: a 200 l steel drum gone rusty under its old blue paint (rust streaks running down from the rolling
    hoops, the bottom eaten through), two rows of holes punched round its foot glowing orange, the rim blackened; split
    logs crossed in it on a bed of embers, flames over the rim (animated), sparks over them; ash and soot on the
    ground round it, a spare log leaning on it."""
    m = BModel("gamble_prop_drumfire", floor=0)

    def rust(c):
        if c.face == "down":
            return None
        y = c.p[1]
        a = math.atan2(c.p[2] - 8, c.p[0] - 8)
        k = math.floor((a + math.pi) / (2 * math.pi) * 36)
        if c.face in SIDES and (math.floor(y) == 1 and k % 4 == 0 or math.floor(y) == 3 and k % 4 == 2):
            return ("raw", tuple(ramp_rgb("fire", 3)) + (255,))   # the punched holes, the fire seen through
        v = vnoise(c.p[0], y * 1.5, c.p[2], 2.2, 13)
        streak = h3(k, 0, 0, 77) < 0.14 and (5.5 < y < 9 or 10 < y < 12)
        if v > 0.82 or (y < 1 and v > 0.5) or (streak and h3(k, math.floor(y), 0, 78) < 0.8):
            return ("copper", 1)
        if v > 0.7:
            return ("soil", 2)
        if y > 11.5 and c.face in SIDES and h3(c.i[0], c.i[1], c.i[2], 2) < 0.6:
            return ("soot", 0 if y > 13 else 1)
        return None
    drum = Paint("police_blue", 2, "metal", noise=0.04, decal=rust)
    m.group("drum")
    for (d, lo, hi) in ((12, 0, 4.5), (13, 4.5, 5.5), (12, 5.5, 9), (13, 9, 10), (12, 10, 13.5)):
        cyl(m, "drum", (8, 8), d, lo, hi, drum)
    ring(m, "rim", (8, 8), 12, 10, 13.5, 14, Paint("soot", 1, "metal"))
    m.group("fire")
    embers = glow("fire", 3, decal=lambda c: ("fire", 4 if h3(c.i[0], c.i[2], 0, 4) < 0.3 else 2 if h3(c.i[0], c.i[2], 1, 4) < 0.3 else 3))
    cyl(m, "embers", (8, 8), 10, 12, 12.5, embers)
    log = Paint("bark", 1, "grain", axis="long", noise=0.06,
                decal=lambda c: ("soot", 0) if h3(c.i[0], c.i[1], c.i[2], 9) < 0.35 else None)
    m.box("log", (3.5, 12.5, 7), (12.5, 14, 8.5), log, rotation=("y", 22.5, (8, 13, 8)))
    m.box("log", (7, 12.5, 3.5), (8.5, 14, 12.5), log, rotation=("y", -22.5, (8, 13, 8)))
    m.box("log", (4.5, 13.25, 4.5), (11.5, 14.5, 5.75), log, rotation=("y", 45, (8, 13, 8)))
    m.box("char", (11.5, 12.75, 7.25), (12.5, 13.75, 8.25), glow("fire", 3), rotation=("y", 22.5, (8, 13, 8)))
    m.box("char", (7.25, 12.75, 3.5), (8.25, 13.75, 4.5), glow("fire", 4), rotation=("y", -22.5, (8, 13, 8)))

    def flame(c):
        t = (c.p[1] - 12.5) / 10 + 0.15 * math.sin(c.frame * 1.7 + c.p[0] * 0.9 + c.p[2] * 1.3)
        return ("fire", 5 if t < 0.25 else 4 if t < 0.55 else 3 if t < 0.8 else 2)
    fl = glow("fire", 4, decal=flame, frames=4, frametime=3)
    m.box("flame", (5.5, 13, 6), (10.5, 16.5, 10), fl)
    m.box("flame", (6.5, 16.5, 6.75), (9.5, 19.5, 9.25), fl)
    m.box("flame", (7.5, 19.5, 7.5), (8.5, 22, 8.5), fl)
    m.box("flame", (4.75, 14, 8.75), (6, 17.5, 9.75), fl)
    m.box("flame", (10, 14, 6.5), (11.25, 18, 7.5), fl)
    m.box("flame", (8.75, 16, 9.25), (9.75, 18.5, 10.25), fl)
    for (x, y, z) in ((6.25, 23, 7.5), (9.25, 24.5, 8.75), (7.75, 26, 6.75), (8.5, 22.75, 10)):
        m.box("spark", (x, y, z), (x + 0.25, y + 0.25, z + 0.25), glow("fire", 5))
    m.group("holes")
    hole = glow("fire", 4)
    for (x0, z0, x1, z1) in ((7.5, 1.75, 8.5, 2), (4.5, 2.75, 5.5, 3), (10.5, 2.75, 11.5, 3), (7.5, 14, 8.5, 14.25),
                             (1.75, 7.5, 2, 8.5), (14, 7.5, 14.25, 8.5)):
        m.box("hole", (x0, 1, z0), (x1, 2.5, z1), hole)
    m.group("ground")
    ring(m, "ash", (8, 8), 16, 12, 0, 0.25, Paint("stone", 1, "speckle", noise=0.05,
                                                  decal=lambda c: ("soot", 1) if h3(c.i[0], c.i[2], 0, 3) < 0.35 else None))
    m.box("spare_log", (13, 0.25, 3), (14.5, 9, 4.5), Paint("bark", 2, "grain", axis="y", noise=0.06), rotation=("z", 22.5, (13.75, 0.25, 3.75)))
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
    """자재 팔레트: a block pallet of raw pine (three bottom boards, nine blocks with a burnt-in stamp on the middle one,
    three stringer boards, five deck boards, nail heads), stacked with red bricks in courses, every other one
    shifted, strapped both ways with blue PP bands crimped with steel seals; one brick left lying askew on top."""
    m = BModel("gamble_prop_pallet", floor=0)
    wood = Paint("hinoki", 2, "grain", axis="x", noise=0.06,
                 decal=lambda c: ("iron", 3) if c.face == "up" and (abs(c.p[0] - 1.5) < 0.5 or abs(c.p[0] - 8) < 0.5 or abs(c.p[0] - 14.5) < 0.5)
                 and (c.i[2] % 3 == 1) else None)
    m.group("pallet")
    for (z0, z1) in ((0.5, 3), (6.75, 9.25), (13, 15.5)):
        m.box("bottom", (0.5, 0, z0), (15.5, 0.75, z1), wood)

    def stamp(c):
        if c.face == "north" and 6.75 < c.p[0] < 9.25 and (abs(c.p[0] - 8) > 0.9 or abs(c.p[1] - 1.5) > 0.4):
            return ("wood_dark", 1)
        return None
    for x0 in (0.5, 6.75, 13):
        for (z0, z1) in ((0.5, 3), (6.75, 9.25), (13, 15.5)):
            m.box("block", (x0, 0.75, z0), (x0 + 2.5, 2.25, z1), Paint("hinoki", 1, "grain", axis="y", noise=0.06,
                                                                         decal=stamp if (x0, z0) == (6.75, 0.5) else None))
    for (x0, x1) in ((0.5, 3), (6.75, 9.25), (13, 15.5)):
        m.box("stringer", (x0, 2.25, 0.5), (x1, 2.5, 15.5), Paint("hinoki", 2, "grain", axis="z", noise=0.06))
    for (z0, z1) in ((0.5, 3), (3.5, 6), (6.75, 9.25), (10, 12.5), (13, 15.5)):
        m.box("deck", (0.5, 2.5, z0), (15.5, 3, z1), wood)
    m.group("bricks")

    def bond(c):
        # bricks 4 texels long and 2 high, every other course shifted by 2: a dark joint texel between bricks, each
        # brick its own shade, each course's top row a touch lighter (the bed of mortar under the next one shows dark)
        if c.face == "up":
            row, along, sub = c.i[2] // 2, c.i[0], 1
        elif c.face in ("north", "south"):
            row, along, sub = (c.i[1] - 3) // 2, c.i[0], (c.i[1] - 3) % 2
        else:
            row, along, sub = (c.i[1] - 3) // 2, c.i[2], (c.i[1] - 3) % 2
        off = 2 * (row % 2)
        k = (along + off) // 4
        if (along + off) % 4 == 0:
            return ("brick", 0)
        shade = 1 + int(h3(row, k, c.i[1] if c.face == "up" else 0, 8) * 3)
        return ("brick", min(4, shade + (1 if sub == 1 and c.face != "up" else 0)))
    m.box("stack", (1.5, 3, 1.5), (14.5, 11, 14.5), Paint("brick", 2, noise=0, decal=bond, rim=0.8))
    m.box("loose_brick", (4, 11.25, 9), (8, 12.25, 11), Paint("brick", 2, noise=0.05, rim=1.0), rotation=("y", 22.5, (6, 11.5, 10)))
    m.group("straps")
    band = flat("signal_blue", 1, rim=0.8)
    for z in (4.5, 10.5):
        m.box("strap", (1.25, 3, z), (1.5, 11, z + 1), band)
        m.box("strap", (14.5, 3, z), (14.75, 11, z + 1), band)
        m.box("strap", (1.25, 11, z), (14.75, 11.25, z + 1), band)
        m.box("seal", (7, 11.25, z - 0.125), (8.5, 11.5, z + 1.125), flat("steel", 4, rim=1.2))
    x = 4.5
    m.box("strap", (x, 3, 1.25), (x + 1, 11, 1.5), band)
    m.box("strap", (x, 3, 14.5), (x + 1, 11, 14.75), band)
    m.box("strap", (x, 11.25, 1.25), (x + 1, 11.5, 14.75), band)
    m.box("strap", (x, 11, 1.25), (x + 1, 11.25, 4.5), band)
    m.box("strap", (x, 11, 5.5), (x + 1, 11.25, 10.5), band)
    m.box("strap", (x, 11, 11.5), (x + 1, 11.25, 14.75), band)
    return gui(m)


def _print_plate(m, name, frm, to, tex, w, h):
    """A cut-out print (stencil paint, sack print) a hair in front of a surface facing north (or up when frm/to are
    flat in y), its picture on both sides."""
    if frm[1] == to[1]:
        m.plate(name, frm, to, {"up": (tex, [0, 0, w, h]), "down": (tex, [0, 0, w, h])})
    else:
        m.plate(name, frm, to, {"north": (tex, [0, 0, w, h]), "south": (tex, [w, 0, 0, h])})


def ramp_rgb(name, i):
    from stsv_assets.palette import ramp
    return ramp(name, i)[:3]


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
DISPLAY["gamble_cup"] = cup
for _k in ART.BUTTON_WORDS:
    DISPLAY[f"gamble_btn_{_k}"] = (lambda k=_k: button_model(k))
DISPLAY["gamble_whiteboard"] = whiteboard

ALL = {**FURNITURE, **DISPLAY}
