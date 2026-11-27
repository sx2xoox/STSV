"""
편의점 알바 (docs/plans/편의점_기획안.md §15 작업 계약): the GS26 convenience store's models. All are economy models
(economy/content/economy/models/<id>.bbmodel, item model "economy:model/<id>"), each also an item of the same id
(economy/content/economy/items/store.yml). Nobody is given one: the store's builder places the furniture, the store's
logic shows the rest with ItemDisplays.

Furniture (drawn like the core furniture and the 도박장's: 16 px a block, front = north (-z), the anchor cell x 0..16 /
z 0..16 / y 0 its floor, model +x the viewer's LEFT; collision from the model: a cell gets a barrier when the model
fills 30% of it, in voxels of 1/4 block):

  store_counter        계산대, 3 x 1 cells (x -1..1), 15 px tall: a laminate cabinet with a green band, a pale stone top
                       overhanging the customer's side. On it, from the customer's right (-x) to left (+x): a ribbed
                       rubber mat where the customer's goods go (x -14..2, z 2..12, its top y 15.25), the customer
                       display on a pole (a dark screen facing the customer, its face x 1.5..8, y 19.5..24.5 at
                       z 1.5: a text display goes there), the POS terminal (its screen tilted to the worker, +z), the
                       card terminal facing the customer (tilted, a keypad, a card slot), the scanner cradle behind it,
                       and a little rack of 숙취해소제 and gum at the left end. A bag hook with a bundle of white bags
                       hangs off the worker's side at the right end.
  store_cig_shelf      담배 진열장 behind the counter, 3 x 1 cells, 2 tall, against a wall at +z (depth 10 px): a lower
                       cabinet with doors and a "19세 미만 판매 금지" plate, on it a gum rack and a lottery ticket
                       dispenser (복권), above them three rows of cigarette packs (made-up brands) behind white price
                       rails, a lit "담배" header.
  store_shelf_<snack|noodle|daily|meal>_<full|half|empty>
                       양면 진열대, 1 x 3 cells (z -1..1), 2 tall: a white gondola (plinth, pegboard spine, three shelves
                       and the deck a side, end panels), the category on a header along the top (both long sides) and
                       on both end panels. The goods are baked in: each shelf holds rows of facings (16 px a block
                       pictures: chip bags, cup ramen, tissue boxes, triangle kimbap ...) behind a white price rail
                       with a yellow tag under each kind. half: some kinds gone, the rest fewer and shallower;
                       empty: bare shelves, the tags, one or two left behind. The two shopping sides face -x and +x.
  store_fridge_<full|half|empty>
                       벽 냉장 쇼케이스, 2 x 1 cells (x 0..1), 2 tall: two glass doors (cut-out glass with glare
                       streaks) on a white case, the lit interior (its back wall glows), wire shelves of drinks
                       (banana milk, cans and cups of coffee, cider, ion drink, water, soju, beer), a lit header.
  store_freezer        아이스크림 냉동고, 2 x 1 cells, 14 px tall: a chest freezer, two sliding glass lids over
                       baskets of ice bars and cones, a blue front with "아이스크림".
  store_microwave      전자레인지 on a steel cabinet, 1 cell: the door's dark window, the keypad, the handle.
  store_kettle         온수기 on a steel cabinet, 1 cell: a hot water dispenser (a red 온수 tap, a water window, a
                       drip tray), a stack of paper cups beside it.
  store_eat_bar        창가 시식대, 3 x 1 cells: a narrow bar along the window (+z) on three legs, napkins, a
                       chopstick cup, and three round stools in front of it, one a cell (seats 0 1 2 from -x).
  store_parasol        바깥 파라솔 테이블, 3 x 1 cells: a round white table under a green and white square parasol,
                       a plastic chair either side (seat 0 at x -1, seat 1 at x +1), drawn at real size, shrunk by 2
                       (scale 2) for the parasol's reach.
  store_hanger         조끼 걸이, 1 cell, 2 tall: a coat stand with the green work vest (name tag, yellow band) on a
                       hanger and a cap.
  store_waste          폐기 바구니, 1 cell: a blue crate of expired kimbap and lunch boxes with red stickers, a
                       "폐기" label.
  store_mop            대걸레 통, 1 cell: a yellow mop bucket on casters with its wringer, the mop standing in it.
  store_sign           간판 GS26, 5 x 1 cells (x -2..2), a lightbox 5 x 1 blocks against a wall at +z (store_art.sign_face,
                       an original design), lit; drawn at real size, shrunk by 2.

디테일 (모델링 디테일 강화): the fine print rides on pictures at more than 16 px a block, not on more boxes. HModel.skin
draws a box without one face and lays a one-sided plate carrying a store_art picture in that face's plane (no face
more than the plain box), HModel.plate(one_sided=True) a sticker on a surface. So: the goods' fronts on the shelves and
in the fridges (store_art.facings_hd: 4 texels a px, each kind's own pack art, the gap between packs in shadow), the
electronic price rails (store_art.price_rail: an e-paper tag with the price under each kind, red promo tags), 1+1 / 2+1
shelf talkers, the end panels' promo posters, the counter's POS app screen, card terminal keypad, worker-side cubbies
(rolls of bags, receipt paper, binders, a safe, a first aid kit), a receipt printer, a money tray, a promo poster and a
봉투 유료 sign, the microwave's and the water heater's fronts, the freezer's baskets from above, the cigarette packs,
the parasol's GS26 print and stretchers, the eat bar's napkin holder, sauces and card, the boxes' shipping labels and
handling marks, the goods' printed fronts (bags, boxes, the 도시락 lid), the bag's print.

Display models (an ItemDisplay with no transform: the model's (8, 8, 8) at the display's position, front = north):
the goods, money and props have their BOTTOM on the display's position (model y 8), centred on it; the buttons and
the scanner are centred on it.

  store_item_<id>      the 33 goods (§15 상품): drawn at twice their size and shrunk by 2 (BModel scale 2: 32 texels
                       a block), a bit larger than life so they read on the counter (a cup ramen 0.3 blocks across);
                       each also held in a hand (thirdperson_righthand: upright in the fist of a standing customer).
  store_box_<snack|noodle|daily|meal|drink>
                       배송 상자: a taped cardboard box 12 x 8 x 10 px, a label with the kind (과자 라면 생활용품 도시락
                       음료) in its colour.
  store_bag            봉투: a white plastic bag, full, its handles up, a green GS26 print.
  store_spill          엎지른 음료: a puddle of cola (cut-out) on the floor and the cup it came from.
  store_trash_cup      다 먹은 컵라면: an empty cup, its lid peeled back, broth left in it, chopsticks across.
  store_card           손님 카드, lying flat (5.5 x 3.5 px), reading from the north.
  store_cash_<1000|5000|10000|50000>  지폐 (made-up notes, 7 x 3.5 px), lying flat.
  store_coin_<100|500> 동전, lying flat.
  store_btn_<card|cash|give|bag|nobag|warm|yes|no|wait|id|refuse|sell|wake|point>
                       a POS key 12 x 6 px, 1 px thick, standing up, CENTRED on the display's position, its face
                       (north) store_art.button: 카드 현금 건네기 봉투 봉투없음 데우기 네 아니요 잠시만요 신분증 거절 판매
                       깨우기 가리키기. Lit.
  store_btn_change_<50000|10000|5000|1000|500|100>   the same, 5만원 1만원 5천원 1천원 500원 100원.
  store_scanner        바코드 스캐너 (hand item): a dark grey gun with a green stripe, a red window at its nose.
  store_vest           근무 조끼 item (folded flat vest); worn in the chest slot it shows the armour layer
                       economy:store_vest (store_art.vest_layer, assets/economy/equipment/store_vest.json).

3차 (§16 새 모델):

  store_order_tablet   발주 단말기 (furniture, 1 cell, beside the counter): a round foot, a steel post, a POS tablet
                       tilted back 45 degrees with the 발주 app lit on it (store_art.tablet_screen), a cradle on the
                       post holding the handheld 발주기 (PDA, a small lit screen).
  store_license_<alley|standard|large>
                       권리증 (display + held; the items are in items/store_extra.yml): a 10 x 13 px certificate lying
                       face up, reading from the north (store_art.license_face: the tier's border, ribbon banner and
                       stars), a bronze / silver / gold rosette seal with ribbon tails on its bottom left. Held up in
                       the fist facing out; the inventory shows its face.
  store_id_card        신분증, lying flat (6.4 x 4 px, store_art.id_card): the text area left plain for a TextDisplay
                       (model x 7.2..10.8, z 6.5..8.7, top y 8.25), the photo on the right.
  store_vest_manager · store_vest_night
                       점장 · 야간 조끼 items (folded, like store_vest); worn in the chest slot they show the layers
                       economy:store_vest_manager / economy:store_vest_night.
"""

import math

from stsv_assets import presets
from stsv_assets import textures as T
from stsv_assets.bake import BModel, Paint, cyl, h3, ring, vnoise, voxels
from stsv_assets.model import Model
from stsv_assets.palette import ladder

import store_art as ART

SIDES = ("north", "south", "east", "west")
FACES6 = ("north", "east", "south", "west", "up", "down")
GREEN = "sign_green"


def flat(ramp, step, **kw):
    return Paint(ramp, step, noise=0, **kw)


def glow(ramp, step, decal=None):
    return Paint(ramp, step, glow=True, light=False, noise=0, decal=decal)


def raw(ramp, step):
    return ("raw", ladder(ramp, step))


class HModel(BModel):
    """A baked model plus plates wearing a picture of their own at more than 16 px a block (words, the sign)."""

    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self._plates = []

    def plate(self, name, frm, to, faces, glow=False, rotation=None, one_sided=False):
        """faces: {face: (texture, [u0, v0, u1, v1] in its pixels)}. A flat plate (no thickness) given one side gets
        the other side too, the picture's corner texel (it is seen only edge on or from behind a wall), unless
        one_sided (a picture laid on a box's open face, see skin)."""
        faces = dict(faces)
        for a, b in (("north", "south"), ("east", "west"), ("up", "down")):
            ax = {"north": 2, "east": 0, "up": 1}[a]
            if frm[ax] == to[ax] and not one_sided:
                for f, o in ((a, b), (b, a)):
                    if f in faces and o not in faces:
                        faces[o] = (faces[f][0], [0, 0, 1, 1])
        self._plates.append((self._group, name, frm, to, faces, glow, rotation, one_sided))

    def skin(self, name, frm, to, paint, face, tex, uv=None, glow=False, rotation=None):
        """A box whose `face` wears a picture at more than 16 px a block instead of its baked paint: the box is drawn
        without that face (left open on purpose) and a one-sided plate in the face's plane carries the picture, so
        the detail costs no face more than the plain box. uv: the picture's pixels (default all of it)."""
        self.box(name, frm, to, paint, faces={face: None}, rotation=rotation)
        self.elements[-1]["open"] = (face,)
        ax, hi = {"north": (2, False), "south": (2, True), "west": (0, False), "east": (0, True),
                  "down": (1, False), "up": (1, True)}[face]
        a, b = list(frm), list(to)
        a[ax] = b[ax] = (to if hi else frm)[ax]
        if uv is None:
            w, h = T.size_of(tex)
            uv = [0, 0, w, h]
        self.plate(name + "_face", tuple(a), tuple(b), {face: (tex, uv)}, glow=glow, rotation=rotation, one_sided=True)

    def bake(self):
        if self._baked:
            return
        super().bake()
        keep = self._group
        back = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "down", "down": "up"}
        for group, name, frm, to, faces, lit, rot, one in self._plates:
            self._group = group
            if rot:
                rot = (rot[0], rot[1], self.to_model(rot[2]))
            Model.box(self, name, self.to_model(frm), self.to_model(to), None, glow=lit, rotation=rot,
                      faces={f: (faces[f][0] if f in faces else None) for f in FACES6}, uv={f: faces[f][1] for f in faces})
            if one:
                # a picture laid on (or just off) a surface: its back is never seen, left out on purpose
                self.elements[-1]["open"] = tuple(back[f] for f in faces)
        self._group = keep


def _surface(name, fn):
    if name not in T.SURFACES:
        T.SURFACES[name] = fn
        T._SIZE_CACHE.pop(name, None)
        T._ALPHA_CACHE.pop(name, None)
    return name


def _bounds(m):
    lo = [min(e["from"][i] for e in m.elements) for i in range(3)]
    hi = [max(e["to"][i] for e in m.elements) for i in range(3)]
    return lo, hi


def gui(m, fill=0.62):
    """Inventory look: the model's bounds fitted into the slot, centred; held like a block (furniture preset)."""
    lo, hi = _bounds(m)
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


def held(m, grip_y=None, fill=0.9, tilt=0.0):
    """A thing a customer holds: upright in the fist of a standing mannequin (bus_hold's solver), front forward;
    an inventory look fitted to its bounds."""
    from models import bus_hold as BH
    lo, hi = _bounds(m)
    g = (8.0, grip_y if grip_y is not None else lo[1] + (hi[1] - lo[1]) * 0.35, 8.0)
    tr = BH.hold_transform(grip=g, at=BH.fist(), turn=BH.turn(x=tilt), scale=1.0, pose=BH.STANDING)
    BH.displays(m, tr, gui_fill=fill)
    return m


# ================================================================================================
# Pixel pictures of goods on shelves (16 px a block): rows top to bottom, a letter a texel
# ================================================================================================

PAL = {
    "Y": ("corn", 2), "y": ("corn", 3), "d": ("corn", 1), "R": ("pepper", 2), "r": ("pepper", 3),
    "O": ("onion", 2), "o": ("onion", 3), "W": ("car_white", 3), "w": ("car_white", 2), "K": ("iron", 1),
    "k": ("iron", 3), "B": ("sapphire", 2), "b": ("sapphire", 3), "G": (GREEN, 2), "g": (GREEN, 3),
    "P": ("grape", 3), "p": ("pink", 2), "M": ("doenjang", 2), "m": ("doenjang", 3), "S": ("fish_silver", 3),
    "s": ("fish_silver", 2), "L": ("bottle_green", 2), "l": ("bottle_green", 3), "A": ("salmon", 3),
    "C": ("rice", 2), "N": ("roe", 1), "E": ("bean", 2), "e": ("bean", 3), "F": ("fox", 3), "f": ("fox", 2),
    "U": ("glass", 2), "u": ("ice", 2), "V": ("wine", 3), "v": ("wine", 2), "Q": ("gold", 2), "q": ("gold", 3),
    "Z": ("crystal", 3), "z": ("crystal", 2), "I": ("petal_yellow", 2), "T": ("tomato", 2), "H": ("hinoki", 2),
    "c": ("rice", 1), "n": ("leaf", 3),
}

# kind -> (rows, stack): w = len(row), h = len(rows) * stack ('.' = the background behind)
FACINGS = {
    # 과자
    "chips": (["dyyd", "RRRR", "YyoY", "YooY", "YYYY", "RrRR"], 1),
    "shrimp": (["wWWw", "RRRR", "WFfW", "WfFW", "WWWW", "RRRR"], 1),
    "choco": (["MmmM", "rRRr", "RMmR", "RRRR"], 1),
    "jelly": (["ZzZ", "ZpZ", "TZY", "zZz"], 1),
    "chocolate": (["QqqMM", "MMmMM"], 2),
    "candy": (["pAp", "pwp", "TpI", "pBp", "ppp"], 1),
    # 라면
    "ramen_red": (["wWWw", "RKKR", "RwRR", "RRKR", "rRRr"], 1),
    "ramen_mild": (["wWWw", "FffF", "FGwF", "FFFF", "fFFf"], 1),
    "ramen_big": (["wWWWw", "VRRRV", "VwKRV", "vVVVv"], 1),
    "bagramen": (["FFFFFF", "FRwwRF", "fFFFFf"], 2),
    # 생활용품
    "tissue": (["WbbW", "WWWW", "bWWb"], 2),
    "battery": (["KkK", "KSK", "FSF", "KSK", "KKK"], 1),
    "bandage": (["WpWW", "AAAA"], 2),
    "umbrella": (["kK", "uU", "uU", "Uu", "uU", "Kk"], 1),
    # 도시락 김밥
    "samgak_tuna": (["..C..", ".CCC.", ".BBB.", "CCRCC", "NNNNN"], 1),
    "samgak_bibim": (["..C..", ".CCC.", ".RRR.", "CCRCC", "NNNNN"], 1),
    "samgak_bulgogi": (["..C..", ".CCC.", ".MMM.", "CCRCC", "NNNNN"], 1),
    "dosirak": (["UCRYnU", "KKKKKK"], 3),
    "hotbar": (["fFf", "FoF", "FoF", "fFf"], 1),
    "egg": (["EeeE", "eRRe", "EeeE"], 1),
    # 음료 (the fridge)
    "banana": ([".G.", "YYY", "YWY", "YYY"], 1),
    "coffee_can": ([".s.", "MQM", "MMM", "MqM", "MMM"], 1),
    "coffee_cup": (["WWW", "EME", "EeE", "EEE"], 1),
    "soda": ([".G.", ".u.", "uuu", "GWG", "uuu", "uuu"], 1),
    "ion": ([".W.", ".b.", "bbb", "WBW", "bbb", "bbb"], 1),
    "water": ([".B.", ".u.", "uuu", "BWB", "uuu", "uuu"], 1),
    "soju": ([".G.", ".L.", "LLL", "WGW", "LLL", "LLL"], 1),
    "beer": ([".s.", "QQQ", "QKQ", "QQQ", "qqq"], 1),
}


def facing_size(kind, stack=None):
    rows, st = FACINGS[kind]
    return len(rows[0]), len(rows) * (stack if stack is not None else st)


def facing_decal(kind, a0, y0, along, flip, front, bg, stack=None):
    """The decal of a row of facings: `along` the axis the row runs (0 x, 2 z) from a0, its floor y0; the front face
    `front` shows the pictures (flip: mirrored so they read left to right from in front), the top the first row,
    the ends the edge column; '.' is the background `bg` (ramp, step)."""
    rows, st = FACINGS[kind]
    st = stack if stack is not None else st
    w, h = len(rows[0]), len(rows)

    def at(u, v):
        ch = rows[h - 1 - (v % h)][u % w]
        return bg if ch == "." else PAL[ch]

    def decal(c):
        a = c.p[along] - a0
        u = math.floor(a) % w
        if flip:
            u = w - 1 - u
        v = math.floor(c.p[1] - y0)
        if c.face == front:
            return at(u, max(0, min(h * st - 1, v)))
        if c.face == "up":
            ch = rows[0][u]
            if ch == ".":
                return bg
            r, k = PAL[ch]
            return (r, k - 1) if u == w - 1 and w > 2 else (r, k)   # a darker line between two facings
        if c.face in SIDES:
            return at(0, max(0, min(h * st - 1, v)))
        return None
    return decal


def cutout_decal(kind, a0, y0, along, flip):
    """The same pictures on a cut-out plane: '.' is a hole."""
    rows, _ = FACINGS[kind]
    w, h = len(rows[0]), len(rows)

    def decal(c):
        a = c.p[along] - a0
        u = math.floor(a) % w
        if flip:
            u = w - 1 - u
        v = math.floor(c.p[1] - y0)
        if not 0 <= v < h:
            return ("raw", (0, 0, 0, 0))
        ch = rows[h - 1 - v][u]
        return ("raw", (0, 0, 0, 0)) if ch == "." else PAL[ch]
    return decal


# ================================================================================================
# 양면 진열대 (gondola shelves)
# ================================================================================================

SHELF_FLOORS = (3, 10, 17, 24)       # the deck, then the three shelves' tops
CATEGORY = {"snack": ("과자 간식", ("fox", 3)), "noodle": ("라면", ("pepper", 2)),
            "daily": ("생활용품", ("sapphire", 2)), "meal": ("도시락 김밥", (GREEN, 2))}
# the end panels' posters (north end, south end): (title, burst, goods drawn on it)
POSTERS = {"snack": (("과자 행사", "1+1", (("chips", 4, 6), ("choco", 4, 4))), ("신상 과자", "2+1", (("shrimp", 4, 6), ("jelly", 3, 4)))),
           "noodle": (("라면 행사", "1+1", (("ramen_red", 4, 5), ("ramen_mild", 4, 5))), ("큰사발", "2+1", (("ramen_big", 5, 4), ("bagramen", 6, 3)))),
           "daily": (("생활 필수품", "1+1", (("tissue", 4, 3), ("battery", 3, 5))), ("우산 있어요", "2+1", (("umbrella", 2, 6), ("bandage", 4, 2)))),
           "meal": (("도시락 행사", "1+1", (("dosirak", 6, 2), ("hotbar", 3, 4))), ("든든한 한끼", "2+1", (("egg", 4, 3), ("dosirak", 6, 2))))}
# per level (deck first): [(kind, facings)], one side; the other side takes them in turn
LAYOUT = {
    "snack": [[("chips", 3), ("shrimp", 3), ("candy", 3), ("chips", 2)],
              [("shrimp", 3), ("choco", 3), ("jelly", 4)],
              [("chocolate", 4), ("jelly", 3), ("candy", 3)],
              [("choco", 3), ("chocolate", 4)]],
    "noodle": [[("bagramen", 4), ("ramen_big", 4)],
               [("ramen_red", 5), ("ramen_mild", 5)],
               [("ramen_big", 3), ("ramen_red", 3), ("ramen_mild", 3)],
               [("ramen_big", 4), ("bagramen", 4)]],
    "daily": [[("tissue", 4), ("umbrella", 7), ("tissue", 3)],
              [("battery", 5), ("bandage", 4), ("battery", 4)],
              [("tissue", 5), ("bandage", 5)],
              [("bandage", 4), ("tissue", 5)]],
    "meal": [[("dosirak", 4), ("hotbar", 6)],
             [("samgak_tuna", 3), ("samgak_bibim", 3), ("samgak_bulgogi", 2)],
             [("samgak_bulgogi", 3), ("samgak_tuna", 2), ("egg", 4)],
             [("egg", 5), ("hotbar", 6)]],
}
# stack heights on the top shelf (only 4 px under the header)
TOP_STACK = {"chocolate": 2, "bagramen": 1, "bandage": 2, "tissue": 1, "dosirak": 2}
Z0, Z1 = -15, 31                     # the shelves' length (z)


def _row_layout(groups, top=False):
    """[(kind, n, z0, z1, stack)] centred along the shelf with 1 px between kinds."""
    out = []
    sizes = []
    for kind, n in groups:
        st = TOP_STACK.get(kind) if top else None
        w, _ = facing_size(kind, st)
        sizes.append((kind, n, w * n, st))
    total = sum(s[2] for s in sizes) + len(sizes) - 1
    assert total <= Z1 - Z0, f"a shelf row too long: {groups} ({total} px)"
    z = Z0 + math.floor((Z1 - Z0 - total) / 2)
    for kind, n, L, st in sizes:
        out.append((kind, n, z, z + L, st))
        z += L + 1
    return out


def _shelf_state(level, i, kind, n, state, side):
    """(facings kept, depth px, stack) of a kind on a shelf in a state."""
    if state == "full":
        return n, 6, None
    if state == "empty":
        lone = (level == 1 and i == 0 and side == "w") or (level == 3 and i == 1 and side == "e") \
            or (level == 0 and i == 0 and side == "e")
        return (1, 2, 1) if lone else (0, 0, None)
    if (i + 2 * level + (side == "e")) % 4 == 1:
        return 0, 0, None
    return max(1, n - n // 3 - (1 if (i + level) % 2 else 0)), 4, None


def shelf(cat, state):
    m = HModel(f"store_shelf_{cat}_{state}", floor=0)
    word, colour = CATEGORY[cat]
    white = Paint("car_white", 3, noise=0.0, rim=1.0)
    m.group("frame")

    def plinth_d(c):
        if c.face in ("east", "west") and c.p[1] < 1:
            return ("iron", 2)
        if c.face in ("east", "west"):
            return ("iron", 3) if c.p[1] < 2.5 else ("car_white", 2)
        return None
    m.box("plinth", (0.5, 0, Z0), (15.5, 3, Z1), Paint("car_white", 2, noise=0.0, decal=plinth_d))

    def peg(c):
        if c.face in ("east", "west") and c.i[1] % 3 == 0 and c.i[2] % 3 == 0:
            return ("car_white", 2)                        # the pegboard's holes
        return None
    m.box("spine", (7.25, 3, Z0), (8.75, 28, Z1), Paint("car_white", 3, noise=0.0, decal=peg))

    def end_d(c):
        y = c.p[1]
        if c.face in ("north", "south"):
            if y < 1.5:
                return ("iron", 2)
            if 18 <= y < 19 or y >= 27:
                return colour                              # the category's colour bands
        if c.face in ("east", "west") and y >= 27:
            return colour
        return None
    end = Paint("car_white", 3, noise=0.0, rim=1.0, decal=end_d)
    m.box("end_n", (0, 0, -15.75), (16, 28, Z0), end)
    m.box("end_s", (0, 0, Z1), (16, 28, 31.75), end)
    # the header along the top: its long sides are the category picture (plates), its ends and top plain
    head = _surface(f"store_head_{cat}", lambda w=word, c=colour: ART.header(w, 368, 32, bg=c, edge=(c[0], c[1] - 1), scale=2))
    endp = _surface(f"store_end_{cat}", lambda w=word, c=colour: ART.header(w, 112, 48, bg=c, edge=(c[0], c[1] - 1), scale=2))
    back = _surface("store_plate_back", ART.plate_back)
    m.plate("header", (6.5, 28, Z0), (9.5, 32, Z1),
            {"west": (head, [0, 0, 368, 32]), "east": (head, [0, 0, 368, 32]),
             "up": (back, [0, 0, 16, 16]), "down": (back, [0, 0, 16, 16]),
             "north": (back, [0, 0, 4, 4]), "south": (back, [0, 0, 4, 4])})
    m.plate("end_sign", (1, 20, -15.77), (15, 26, -15.77), {"north": (endp, [0, 0, 112, 48])})
    m.plate("end_sign", (1, 20, 31.77), (15, 26, 31.77), {"south": (endp, [0, 0, 112, 48])})

    # the end panels' lower half: a promo poster each (the aisle's ends)
    for z, f, (title, sub, kinds) in ((-15.77, "north", POSTERS[cat][0]), (31.77, "south", POSTERS[cat][1])):
        pz = _surface(f"store_poster_{cat}_{f}", lambda t=title, s=sub, k=kinds, c=colour: ART.promo_poster(t, s, bg=c, kinds=k))
        m.plate("end_poster", (2, 4, z), (14, 18, z), {f: (pz, [0, 0, 96, 112])}, one_sided=True)

    for side in ("w", "e"):
        flip = side == "e"
        fx = 1.25 if side == "w" else 14.75                # the goods' front
        front = "west" if side == "w" else "east"
        m.group(f"side_{side}")
        for level, groups in enumerate(LAYOUT[cat]):
            gs = groups if side == "w" else list(reversed(groups))
            row = _row_layout(gs, top=(level == 3))
            y0 = SHELF_FLOORS[level]
            # the price rail along the shelf's front: an electronic price tag where each kind starts (read along the
            # rail from the viewer's left: +z from the west side, -z from the east)
            tags = [((z0 - Z0) if not flip else (Z1 - z1), (z1 - Z0) if not flip else (Z1 - z0)) for (_, _, z0, z1, _) in row]
            tags.sort()
            promo = {(level + (side == "e")) % len(tags)} if level else set()
            rail = _surface(f"store_rail_{cat}_{side}_{level}",
                            lambda t=tuple(tags), pr=tuple(promo), sd=level * 3 + (side == "e") + len(cat):
                            ART.price_rail(Z1 - Z0, list(t), h_px=1.5, promo=pr, seed=sd))
            if level:
                bx = (0.5, 7.25) if side == "w" else (8.75, 15.5)
                m.box("shelf", (bx[0], y0 - 1, Z0), (bx[1], y0, Z1), white, faces={front: None})
                m.elements[-1]["open"] = (front,)
                rx = bx[0] if side == "w" else bx[1]
                m.plate("rail", (rx, y0 - 1.5, Z0), (rx, y0, Z1), {front: (rail, [0, 0, (Z1 - Z0) * 8, 12])}, one_sided=True)
                # a shelf talker sticking out of the rail at the promo kind (1+1 / 2+1), both sides printed
                if promo and state != "empty":
                    a, b = tags[min(promo)]
                    zt = (Z0 + a + 4) if not flip else (Z1 - a - 4)
                    word = "1+1" if (level + len(cat)) % 2 else "2+1"
                    tk = _surface(f"store_talker_{word}", lambda w=word: ART.talker_big(w, w=32, h=40))
                    tx = (bx[0] - 1.75, bx[0]) if side == "w" else (bx[1], bx[1] + 1.75)
                    m.plate("talker", (tx[0], y0 - 3, zt), (tx[1], y0 - 0.5, zt),
                            {"north": (tk, [0, 0, 32, 40]), "south": (tk, [0, 0, 32, 40])})
            else:
                # the deck's rail: a thin strip on the plinth's front
                bx = (0.25, 0.5) if side == "w" else (15.5, 15.75)
                rx = bx[0] if side == "w" else bx[1]
                m.box("deck_rail", (bx[0], 1, Z0), (bx[1], 2.5, Z1), white, faces={front: None})
                m.elements[-1]["open"] = (front,)
                m.plate("deck_rail_face", (rx, 1, Z0), (rx, 2.5, Z1), {front: (rail, [0, 0, (Z1 - Z0) * 8, 12])}, one_sided=True)
            for i, (kind, n, z0, z1, st) in enumerate(row):
                keep, depth, st2 = _shelf_state(level, i, kind, n, state, side)
                if not keep:
                    continue
                stack = st if st2 is None else min(st2, st or FACINGS[kind][1])
                w, h = facing_size(kind, stack)
                za, zb = (z0, z0 + w * keep) if side == "w" else (z1 - w * keep, z1)
                xa, xb = (fx, fx + depth) if side == "w" else (fx - depth, fx)
                if kind.startswith("samgak"):
                    # triangle kimbap leaning in rows: the front a cut-out of triangles, the row behind it lower
                    m.box("samgak_front", (fx, y0, za), (fx, y0 + h, zb),
                          Paint("rice", 2, noise=0, decal=cutout_decal(kind, za, y0, 2, flip)))
                    bxa, bxb = (fx + 0.25, xb) if side == "w" else (xa, fx - 0.25)
                    m.box("samgak_row", (bxa, y0, za), (bxb, y0 + 1.5, zb),
                          Paint("rice", 2, noise=0.04, decal=lambda c, y0=y0: PAL["N"] if c.p[1] < y0 + 1 or c.face == "up" and
                                (math.floor(c.p[2]) % 5 == 2) else None))
                    continue
                fw, fh = facing_size(kind, 1)
                st_n = h // fh
                pic = _surface(f"store_fhd_{kind}_{keep}x{st_n}",
                               lambda k=kind, a=fw, b=fh, n=keep, s=st_n: ART.facings_hd(k, a, b, n, s))
                m.skin(kind, (xa, y0, za), (xb, y0 + h, zb),
                       Paint("car_white", 2, noise=0, decal=facing_decal(kind, za, y0, 2, flip, front, ("iron", 2), stack)), front, pic)
    return gui(m)


# ================================================================================================
# 벽 냉장 쇼케이스
# ================================================================================================

FRIDGE_FLOORS = (3, 9.5, 16, 22.5)
FRIDGE_LAYOUT = [  # per level: (kind, facings) from x 1.5 (+x is the viewer's left)
    [("soju", 3), ("beer", 3), ("water", 3)],
    [("soda", 3), ("ion", 3), ("water", 3)],
    [("coffee_can", 3), ("coffee_cup", 3), ("beer", 3)],
    [("banana", 3), ("coffee_cup", 3), ("banana", 3)],
]


def glass(u0, u1, v0, v1, axis_v=1):
    """Cut-out glass over a pane u (x) u0..u1, v (y, or z when axis_v = 2) v0..v1: clear, with two short parallel
    glare streaks across its upper corner (a texel wide), the rest a hole."""
    def decal(c):
        u, v = c.p[0] - u0, v1 - c.p[axis_v]
        w = u1 - u0
        d = u + v * 0.7
        if (abs(d - w * 0.55) < 0.5 or abs(d - w * 0.55 - 2.5) < 0.5 and u > 1) and v < (v1 - v0) * 0.55:
            return ("raw", (226, 242, 255, 255))
        return ("raw", (0, 0, 0, 0))
    return decal


def fridge(state):
    m = HModel(f"store_fridge_{state}", floor=0, planes=(("z>", 16),))
    case = Paint("car_white", 3, noise=0.0, rim=1.0)
    m.group("case")

    def kick(c):
        if c.face == "north" and 0.75 < c.p[1] < 2.25 and math.floor(c.p[0]) % 2 == 0:
            return ("iron", 1)                             # the vent slots
        return None
    m.box("base", (0, 0, 0), (32, 3, 16), Paint("iron", 3, "metal", decal=kick))
    m.box("side", (0, 3, 0), (1, 28, 16), case)
    m.box("side", (31, 3, 0), (32, 28, 16), case)
    m.box("top", (0, 28, 0), (32, 32, 16), case)
    head = _surface("store_fridge_head", lambda: ART.header("시원한 음료", 240, 24, bg=("water", 2), edge=("water", 1), scale=1))
    m.plate("header", (1, 28.5, -0.01), (31, 31.5, -0.01), {"north": (head, [0, 0, 240, 24])}, glow=True)
    # the lit inside: the back wall glows, the wire shelves
    m.box("back", (1, 3, 14), (31, 28, 16), glow("ice", 3, decal=lambda c: ("ice", 2) if math.floor(c.p[0]) % 8 == 0 else None))
    m.box("ceiling_light", (1, 27, 2), (31, 28, 14), glow("snow", 2))
    for y in FRIDGE_FLOORS[1:]:
        m.box("wire", (1, y - 0.5, 2.5), (31, y, 14),
              flat("steel", 4, decal=lambda c: ("steel", 3) if c.face == "up" and math.floor(c.p[2] * 2) % 2 else None))
    m.group("drinks")
    for level, groups in enumerate(FRIDGE_LAYOUT):
        y0 = FRIDGE_FLOORS[level]
        x = 1.5
        for i, (kind, n) in enumerate(groups):
            w, h = facing_size(kind)
            keep, depth = n, 9
            if state == "half":
                if (i + level) % 3 == 1:
                    keep = 0
                else:
                    keep, depth = max(1, n - 1 - (i % 2)), 4
            elif state == "empty":
                keep, depth = (1, 2) if (level, i) in ((1, 1), (3, 2)) else (0, 0)
            if keep:
                xa, xb = x, x + w * keep
                pic = _surface(f"store_fhd_{kind}_{keep}x1", lambda k=kind, a=w, b=h, n=keep: ART.facings_hd(k, a, b, n, 1))
                m.skin(kind, (xa, y0, 3), (xb, y0 + h, 3 + depth),
                       Paint("ice", 3, noise=0, decal=facing_decal(kind, xb, y0, 0, False, "north", ("ice", 3))), "north", pic)
            x += w * n + 1
        # the shelf's price strip under the drinks (the deck has none): a tag under each kind
        if level:
            tags = sorted((32 - 1.5 - sum(facing_size(k)[0] * n + 1 for k, n in groups[:i + 1]) + 1,
                           32 - 1.5 - sum(facing_size(k)[0] * n + 1 for k, n in groups[:i]))
                          for i in range(len(groups)))
            tags = [(a - 1, b - 1) for a, b in tags]
            strip = _surface(f"store_fridge_rail_{level}", lambda t=tuple(tags), lv=level: ART.price_rail(30, list(t), h_px=1.25, promo=(lv % 3,), seed=lv + 4))
            m.plate("price_strip", (1, y0 - 1.75, 2.45), (31, y0 - 0.5, 2.45), {"north": (strip, [0, 0, 240, 10])}, one_sided=True)
    m.group("doors")
    frame = Paint("steel", 4, "metal", noise=0.02, rim=1.2)
    for (a, b) in ((1, 16), (16, 31)):
        m.box("door_frame", (a, 3, 0), (b, 4, 1.5), frame)
        m.box("door_frame", (a, 27, 0), (b, 28, 1.5), frame)
        m.box("door_frame", (a, 4, 0), (a + 1, 27, 1.5), frame)
        m.box("door_frame", (b - 1, 4, 0), (b, 27, 1.5), frame)
        m.box("glass", (a + 1, 4, 0.75), (b - 1, 27, 0.75), Paint("ice", 3, noise=0, decal=glass(a + 1, b - 1, 4, 27)))
    for hx in (13.5, 17.5):
        m.box("handle", (hx, 9, -1.25), (hx + 1, 22, -0.5), Paint("steel", 5, "metal", noise=0.02, rim=1.4))
        for hy in (9.5, 20.5):
            m.box("handle_post", (hx + 0.25, hy, -0.5), (hx + 0.75, hy + 1, 0), flat("steel", 3))
    # the doors' stickers on the glass (just in front of it): a 1+1 burst on the left door, 당기세요 by each handle
    pr = _surface("store_talker_1+1", lambda: ART.talker_big("1+1", w=32, h=40))
    m.plate("sticker", (26.5, 23, 0.7), (29, 26, 0.7), {"north": (pr, [0, 0, 32, 40])}, one_sided=True)
    pull = _surface("store_fridge_pull", lambda: ART.header("당기세요", 64, 16, bg=("snow", 2), fg=("water", 1), edge=("water", 2)))
    for px0 in (10.5, 18.5):
        m.plate("pull", (px0, 22.5, 0.7), (px0 + 3, 23.25, 0.7), {"north": (pull, [0, 0, 64, 16])}, one_sided=True)
    # LED strips down the inside of the case's sides
    for lx in ((1, 1.25), (30.75, 31)):
        m.box("led", (lx[0], 4, 2), (lx[1], 27, 2.5), glow("snow", 2))
    return gui(m)


# ================================================================================================
# 아이스크림 냉동고
# ================================================================================================

def freezer():
    m = HModel("store_freezer", floor=0)
    white = Paint("car_white", 3, "lacquer", noise=0.02, rim=1.0)
    m.group("case")

    def front_d(c):
        if c.face == "north":
            y = c.p[1]
            if y < 1:
                return ("iron", 2)
            if 1.5 <= y < 11:
                return ("sapphire", 2) if y > 2.5 else ("sapphire", 1)   # the blue panel
        return None
    m.box("front", (0, 0, 0), (32, 13, 1.5), white(decal=front_d))
    m.box("back", (0, 0, 14.5), (32, 13, 16), white)
    m.box("side", (0, 0, 1.5), (1.5, 13, 14.5), white)
    m.box("side", (30.5, 0, 1.5), (32, 13, 14.5), white)
    m.box("well", (1.5, 0, 1.5), (30.5, 6, 14.5), flat("ice", 3))
    for bx in ((0, 1.25), (30.75, 32)):
        m.box("bumper", (bx[0], 0.5, -0.25), (bx[1], 12.5, 0), Paint("iron", 3, "lacquer", rim=1.2))
    m.box("bumper_rail", (1.25, 1, -0.25), (30.75, 1.75, 0), Paint("iron", 3, "lacquer", rim=1.2))
    pic = _surface("store_freezer_front", lambda: ART.header("아이스크림", 224, 32, bg=("sapphire", 2), edge=("sapphire", 2), scale=2))
    m.plate("label", (2, 3, -0.01), (30, 7, -0.01), {"north": (pic, [0, 0, 224, 32])})
    # ice creams in two baskets: bars lying in rows (left), cones standing (right)
    m.group("goods")

    def bars(c):
        if c.face != "up":
            return ("sapphire", 3) if math.floor(c.p[0]) % 3 else ("car_white", 3)
        u, v = math.floor(c.p[0] - 1.5) % 3, math.floor(c.p[2] - 2.5) % 5
        if u == 2:
            return ("sapphire", 1)
        if v == 4:
            return ("car_white", 2)
        return ("pepper", 2) if v == 2 and u == 1 else ("sapphire", 3) if v != 0 else ("car_white", 3)

    def cones(c):
        # cones standing in rows, seen from above: each a red-rimmed paper lid with chocolate in its middle
        if c.face != "up":
            return ("pepper", 2) if math.floor(c.p[0]) % 4 else ("ice", 3)
        u, v = math.floor(c.p[0] - 16.5) % 4, math.floor(c.p[2] - 2.5) % 4
        if u == 3 or v == 3:
            return ("ice", 2)
        if u == 1 and v == 1:
            return ("doenjang", 2)
        return ("pepper", 2) if (u + v) % 2 else ("car_white", 3)
    m.skin("bars", (1.5, 6, 2.5), (15.5, 10, 13.5), Paint("sapphire", 3, noise=0, decal=bars), "up",
           _surface("store_freezer_bars", ART.freezer_bars), uv=[0, 44, 56, 0])
    m.skin("cones", (16.5, 6, 2.5), (30.5, 9.5, 13.5), Paint("fox", 3, noise=0, decal=cones), "up",
           _surface("store_freezer_cones", ART.freezer_cones), uv=[0, 44, 56, 0])
    # a 2+1 sticker on the front
    st = _surface("store_talker_2+1", lambda: ART.talker_big("2+1", w=32, h=40))
    m.plate("sticker", (26, 7.5, -0.01), (29, 11.25, -0.01), {"north": (st, [0, 0, 32, 40])}, one_sided=True)
    m.box("divider", (15.5, 6, 2), (16.5, 11, 14), flat("steel", 4))
    m.group("lids")
    rim = Paint("steel", 4, "metal", noise=0.02, rim=1.2)
    m.box("rim", (0, 13, 0), (32, 13.5, 1.5), rim)
    m.box("rim", (0, 13, 14.5), (32, 13.5, 16), rim)
    m.box("rim", (0, 13, 1.5), (1.5, 13.5, 14.5), rim)
    m.box("rim", (30.5, 13, 1.5), (32, 13.5, 14.5), rim)
    for (a, b, y) in ((1.5, 17, 12.25), (15, 30.5, 12.75)):
        m.box("lid_glass", (a, y + 0.125, 1.5), (b, y + 0.125, 14.5), Paint("ice", 3, noise=0, decal=glass(a, b, 1.5, 14.5, axis_v=2)))
        m.box("lid_frame", (a, y, 1.5), (a + 0.75, y + 0.5, 14.5), flat("steel", 3))
        m.box("lid_frame", (b - 0.75, y, 1.5), (b, y + 0.5, 14.5), flat("steel", 3))
    m.box("lid_grip", (16, 13, 6), (17, 13.5, 10), flat("iron", 2))
    return gui(m)


# ================================================================================================
# 전자레인지 · 온수기 (each on its steel cabinet)
# ================================================================================================

def _cabinet(m):
    steel = Paint("steel", 4, noise=0.0, rim=1.0)

    def doors(c):
        if c.face == "north":
            if abs(c.p[0] - 8) < 0.3 or c.p[1] < 1:
                return ("steel", 2)
            if 8 <= c.p[1] < 10 and abs(abs(c.p[0] - 8) - 1.5) < 0.5:
                return ("iron", 3)                         # the handles
        return None
    m.group("cabinet")
    m.box("cabinet", (1, 0, 2), (15, 12, 15), steel(decal=doors))
    m.box("top", (0.5, 12, 1.5), (15.5, 13, 15.5), Paint("steel", 5, noise=0.0, rim=1.2))


def microwave():
    m = HModel("store_microwave", floor=0, planes=(("z>", 16),))
    _cabinet(m)
    m.group("microwave")

    def vents(c):
        if c.face in ("east", "west") and 18 <= c.p[1] < 21.5 and math.floor(c.p[2] * 2) % 3 == 0 and 5 <= c.p[2] < 12:
            return ("iron", 2)                             # the vent slots on its sides
        return None
    mf = _surface("store_microwave_front", ART.microwave_front)
    m.skin("body", (1.5, 13.5, 3), (14.5, 22.5, 14), Paint("car_white", 3, "lacquer", noise=0.02, rim=1.0, decal=vents), "north", mf)
    m.box("handle", (4.75, 15, 2), (5.5, 21, 3), Paint("steel", 4, "metal", rim=1.3))
    for fx in (2, 13):
        m.box("foot", (fx, 13, 4), (fx + 1, 13.5, 13), flat("iron", 1))
    m.box("cord", (11, 13, 14), (12, 14.5, 15), flat("iron", 1))
    return gui(m)


def kettle():
    m = HModel("store_kettle", floor=0, planes=(("z>", 16),))
    _cabinet(m)
    m.group("dispenser")

    def body(c):
        x, y = c.p[0], c.p[1]
        if c.face == "north":
            if 6 <= x < 10 and 22 <= y < 27:
                return ("water", 2) if y < 25 else ("ice", 3)       # the water window, its level
            if 5 <= x < 11 and 18 <= y < 21:
                return ("iron", 1)                                  # the tap's recess
        return None
    kf = _surface("store_kettle_front", ART.kettle_front)
    m.skin("body", (3.5, 13, 4), (12.5, 28, 13), Paint("car_white", 3, "lacquer", noise=0.02, rim=1.0, decal=body), "north", kf)
    m.box("lid", (4, 28, 4.5), (12, 29, 12.5), flat("steel", 4, rim=1.2))
    m.box("knob", (7, 29, 7.5), (9, 30, 9.5), flat("iron", 2))
    m.box("tap", (7, 18.5, 2.5), (9, 20.5, 4), flat("pepper", 2, rim=1.2))
    m.box("nozzle", (7.5, 17.5, 3), (8.5, 18.5, 3.75), flat("steel", 3))
    m.box("tray", (4.5, 13, 1.5), (11.5, 14, 4), flat("iron", 2, decal=lambda c: ("iron", 1) if c.face == "up" and c.i[0] % 2 else None))
    # paper cups stacked in a clear dispenser tube on the cabinet's corner, the top cup's rim
    cyl(m, "cups", (14.25, 5), 3, 13, 21, Paint("paper", 2, "paper", decal=lambda c: ("pepper", 2) if 15 <= c.p[1] < 16 else
                                                 ("paper", 1) if math.floor(c.p[1] * 2) % 2 and c.p[1] >= 16 else None))
    cyl(m, "cup_rim", (14.25, 5), 3, 21, 21.5, flat("snow", 2, decal=lambda c: ("paper", 1) if c.face == "up" and abs(c.p[0] - 14.25) < 0.6 and abs(c.p[2] - 5) < 0.6 else None))
    return gui(m)


# ================================================================================================
# 계산대
# ================================================================================================

def counter():
    m = HModel("store_counter", floor=0)
    m.group("cabinet")

    def front(c):
        y = c.p[1]
        if c.face == "north":
            if 10 <= y < 12:
                return (GREEN, 2) if y < 11.5 else (GREEN, 3)
            if 12 <= y < 12.5:
                return ("corn", 3)
            if 13.5 <= y:
                return ("hinoki", 3)                       # the lit edge under the top's overhang
            k = math.floor(c.p[0] + 16) % 16
            if k == 0:
                return ("hinoki", 0)                       # the laminate's panel joints (a groove)
            if k == 1:
                return ("hinoki", 3)
        if c.face == "south":
            # the worker's side: shelves of rolled bags and receipt paper, the cash drawer under the POS
            if y >= 13:
                return ("car_white", 2)
            if 6 <= y < 6.5:
                return ("car_white", 2)
            if 1 <= y < 6 or 6.5 <= y < 11:
                k = math.floor(c.p[0] + 16) % 12
                if k == 0:
                    return ("car_white", 2)                # the cubbies' walls
                b = math.floor((c.p[0] + 16) / 3)
                if (y < 4 if y < 6 else y < 9.5) and h3(b, 0 if y < 6 else 1, 0, 3) < 0.35 and k not in (1, 11):
                    return ("snow", 1) if h3(b, 1, 0, 3) < 0.6 else ("corn", 3)   # rolls of bags, receipt paper
                if (y < 1.75 or 6.5 <= y < 7.25) and k not in (0,):
                    return ("iron", 2)                     # the cubbies' floors catching light
                return ("iron", 1)
        return None
    # the cabinet on a recessed dark kick plinth
    m.box("plinth", (-15.5, 0, 2.25), (31.5, 1, 15.5), flat("iron", 1))
    m.skin("cabinet", (-16, 1, 1.5), (32, 14, 16), Paint("hinoki", 2, "grain", axis="x", noise=0.04, decal=front), "south",
           _surface("store_counter_back", ART.counter_back))
    logo = _surface("store_counter_logo", lambda: ART.sign_face().resize((160, 32), 0))
    m.plate("logo", (-4, 4, 1.49), (16, 8, 1.49), {"north": (logo, [0, 0, 160, 32])}, one_sided=True)
    poster = _surface("store_counter_poster", ART.counter_poster)
    m.plate("poster", (18, 2, 1.49), (30, 9, 1.49), {"north": (poster, [0, 0, 96, 56])}, one_sided=True)
    bagfee = _surface("store_counter_bagfee", lambda: ART.header("봉투 유료", 80, 24, bg=("snow", 2), fg=(GREEN, 1), edge=(GREEN, 2)))
    m.plate("bag_fee", (-15, 6, 1.49), (-5, 9, 1.49), {"north": (bagfee, [0, 0, 80, 24])}, one_sided=True)

    def top_d(c):
        if c.face == "north":
            return ("steel", 5) if c.p[1] >= 14.5 else ("steel", 4)     # the steel nosing
        if c.face == "up" and c.p[2] < 0.75:
            return ("snow", 2)
        return None
    m.box("top", (-16, 14, 0), (32, 15, 16), Paint("car_white", 3, noise=0.0, rim=1.2, decal=top_d))
    # the mat for the customer's goods (its top stays at y 15.25: the store's logic puts the goods there)
    m.group("mat")

    def mat_d(c):
        if c.face == "up":
            if c.p[0] < -13.5 or c.p[0] >= 1.5 or c.p[2] < 2.5 or c.p[2] >= 11.5:
                return ("iron", 1)                         # the raised lip
            return ("iron", 2) if c.i[2] % 2 else ("iron", 3)
        return None
    m.box("mat", (-14, 15, 2), (2, 15.25, 12), flat("iron", 3, decal=mat_d))
    # the customer display: a dark screen on a pole, facing the customer (its face is the store's text spot)
    m.group("customer_display")
    m.box("pole", (4.25, 15, 3.25), (5.25, 19.5, 4.25), Paint("steel", 3, "metal", rim=1.2))
    cyl(m, "foot", (4.75, 3.75), 4, 15, 15.5, Paint("iron", 2, "metal", rim=1.2))

    def scr(c):
        if c.face == "north" and 2 <= c.p[0] < 7.5 and 20 <= c.p[1] < 24:
            return ("tint", 1) if c.p[1] < 23 else ("tint", 2)
        if c.face == "north" and c.p[1] < 20 and 4 <= c.p[0] < 5.5:
            return ("steel", 4)                            # a small maker's badge under the screen
        if c.face == "south" and 3 <= c.p[0] < 6.5 and 21 <= c.p[1] < 23:
            return ("iron", 2)                             # the back's vent
        return None
    m.box("screen", (1.5, 19.5, 1.5), (8, 24.5, 3), flat("iron", 1, decal=scr))
    # the POS: a base with its cash drawer key, the screen on an arm tilted to the worker (the POS app lit on it)
    m.group("pos")

    def pos_base(c):
        if c.face == "south" and 15.5 <= c.p[1] < 16 and 10 <= c.p[0] < 16:
            return ("iron", 0)
        if c.face == "up" and 9.5 <= c.p[0] < 12 and 12 <= c.p[2] < 13.5:
            return ("raw", (90, 230, 140, 255)) if c.i[0] % 2 else ("iron", 1)   # status lamps
        return None
    m.box("pos_base", (9, 15, 8), (17, 16.5, 14), Paint("iron", 2, "metal", rim=1.2, decal=pos_base))
    m.box("pos_arm", (12.5, 16.5, 10), (13.5, 19.5, 11), Paint("steel", 3, "metal", rim=1.1))
    rot = ("x", 22.5, (13, 19.5, 11))
    m.box("pos_bezel", (9, 19.5, 10.5), (17, 25, 11.5), Paint("iron", 1, "lacquer", rim=1.2), rotation=rot,
          faces={"south": None})
    m.elements[-1]["open"] = ("south",)
    ps = _surface("store_pos_screen", ART.pos_screen)
    m.plate("pos_screen", (9, 19.5, 11.5), (17, 25, 11.5), {"south": (ps, [0, 0, 64, 44])}, rotation=rot,
            one_sided=True)
    # the receipt printer beside it, a receipt curling out of its slot
    m.group("printer")

    def printer(c):
        if c.face == "up" and 10.5 <= c.p[2] < 11:
            return ("iron", 0)                             # the paper slot
        if c.face == "north" and 16 <= c.p[1] < 16.5 and 28.5 <= c.p[0] < 29:
            return ("raw", (90, 230, 140, 255))
        if c.face == "up" and c.p[2] >= 11:
            return ("car_white", 2)                        # the lid
        return None
    m.box("printer", (25, 15, 9), (30, 17.5, 14.5), Paint("car_white", 2, "lacquer", noise=0.02, rim=1.2, decal=printer))
    rc = _surface("store_receipt", ART.receipt_paper)
    m.plate("receipt", (25.75, 17.5, 10.75), (29.25, 20, 10.75), {"north": (rc, [0, 0, 16, 24]), "south": (rc, [0, 0, 16, 24])},
            rotation=("x", -22.5, (27.5, 17.5, 10.75)))
    # the money tray on the customer's side: a shallow dark dish, two coins in it
    m.group("cash_tray")

    def tray(c):
        if c.face == "up":
            if 10.5 <= c.p[0] < 15.5 and 2.5 <= c.p[2] < 6:
                return ("iron", 1)
        return None
    m.box("cash_tray", (10, 15, 2), (16, 15.5, 6.5), Paint("iron", 3, noise=0, rim=1.3, decal=tray))
    # the card terminal facing the customer, tilted (its keypad and screen drawn on its face); the scanner's cradle
    m.group("card_terminal")
    trot = ("x", -22.5, (21, 15, 9))
    m.box("terminal", (19, 15, 2.5), (23, 16.5, 9), Paint("iron", 2, rim=1.2), rotation=trot, faces={"up": None})
    m.elements[-1]["open"] = ("up",)
    tf = _surface("store_terminal_face", ART.terminal_face)
    m.plate("terminal_face", (19, 16.5, 2.5), (23, 16.5, 9), {"up": (tf, [32, 52, 0, 0])}, rotation=trot, one_sided=True)
    m.box("terminal_back", (19, 15, 7), (23, 16, 10), flat("iron", 2))
    m.box("cradle", (19.5, 15, 11), (22.5, 17, 14), Paint(GREEN, 1, rim=1.2,
          decal=lambda c: ("raw", (255, 70, 60, 255)) if c.face == "north" and 16 <= c.p[1] < 16.5 and 20.5 <= c.p[0] < 21.5 else None))
    m.box("cradle_cup", (19.5, 17, 11), (22.5, 18, 12), flat(GREEN, 1))
    # the little rack at the left end: 숙취해소제 bottles and gum, a 2+1 talker on it
    m.group("impulse")
    m.box("rack", (24.5, 15, 1), (31.5, 16, 7), Paint("car_white", 3, rim=1.2, decal=lambda c: ("pepper", 2) if c.face == "north" and c.p[1] < 15.5 else None))
    for hx in (25.5, 27.75, 30):
        m.box("hangover", (hx - 0.75, 16, 2), (hx + 0.75, 19, 3.5), Paint("doenjang", 1, noise=0,
              decal=lambda c: ("gold", 2) if 17 <= c.p[1] < 18 else ("snow", 2) if c.face == "north" and 18.25 <= c.p[1] < 18.5 else None))
        m.box("hangover_cap", (hx - 0.5, 19, 2.25), (hx + 0.5, 19.75, 3.25), Paint("gold", 3, "metal", rim=1.2))
    m.box("gum", (25, 16, 4.5), (31, 17.5, 6.5), Paint(GREEN, 3, noise=0,
                                                       decal=lambda c: (GREEN, 3) if math.floor(c.p[0]) % 2 else ("car_white", 3)))
    tk = _surface("store_talker_2+1", lambda: ART.talker_big("2+1", w=32, h=40))
    m.plate("talker", (29.5, 17.5, 0.75), (31.5, 20, 0.75), {"north": (tk, [0, 0, 32, 40]), "south": (tk, [0, 0, 32, 40])})
    # the bag hook off the worker's side, a bundle of bags hanging from it
    m.group("bags")
    m.box("hook", (-14, 13, 16), (-13, 14, 17.5), flat("steel", 4))

    def bag(c):
        if c.face == "south" and 9.5 <= c.p[1] < 11 and -15 <= c.p[0] < -12:
            return (GREEN, 2)                              # the print
        if c.face == "south" and 10 <= c.p[1] < 10.5 and -14.25 <= c.p[0] < -13.5:
            return ("corn", 2)
        if c.face in ("east", "west", "south") and math.floor(c.p[1] * 2) % 5 == 0:
            return ("snow", 0)                             # the stacked bags' edges
        return None
    m.box("bags", (-15.5, 6, 16.5), (-11.5, 13, 17.75), Paint("snow", 1, noise=0.06, decal=bag))
    # the cash drawer under the POS on the worker's side, its key lock and handle
    m.group("drawer")

    def drawer(c):
        if c.face == "south":
            if 12.5 <= c.p[1] < 13 and 11 <= c.p[0] < 15:
                return ("steel", 4)
            if 11.5 <= c.p[1] < 12 and 15.75 <= c.p[0] < 16.25:
                return ("gold", 2)
        return None
    m.box("drawer", (9, 11, 16), (17, 13.5, 16.25), Paint("iron", 2, "metal", rim=1.2, decal=drawer))
    return gui(m)


def facing_decal_rows(rows, a0, y0, along, bg):
    """facing_decal for an ad-hoc picture (rows top to bottom), its front on any side face."""
    w, h = len(rows[0]), len(rows)

    def decal(c):
        u = math.floor(c.p[along] - a0) % w
        v = max(0, min(h - 1, math.floor(c.p[1] - y0)))
        ch = rows[h - 1 - v][u] if c.face != "up" else rows[0][u]
        return bg if ch == "." else PAL[ch]
    return decal


# ================================================================================================
# 담배 진열장
# ================================================================================================

# made-up brands: (top band, body, stripe)
PACKS = ["WBb", "WRr", "QMq", "KKs", "SSB", "WGg", "PZW", "WFf", "KQK", "uBW", "WMm", "TWr"]


def cig_shelf():
    m = HModel("store_cig_shelf", floor=0, planes=(("z>", 16),))
    m.group("cabinet")

    def doors(c):
        if c.face == "north":
            x, y = c.p[0], c.p[1]
            if y < 1:
                return ("iron", 2)
            if math.floor(x + 16) % 12 == 0:
                return ("hinoki", 0)                       # door joints
            if 8 <= y < 9 and math.floor(x + 16) % 12 in (5, 6):
                return ("steel", 4)                        # handles
        return None
    m.box("cabinet", (-16, 0, 5), (32, 12, 16), Paint("hinoki", 2, "grain", axis="x", noise=0.04, decal=doors))
    m.box("cabinet_top", (-16, 12, 4.5), (32, 13, 16), Paint("car_white", 3, "metal", rim=1.2))
    warn = _surface("store_cig_warning", lambda: ART.header("19세 미만 판매 금지", 192, 24, bg=("pepper", 2), edge=("pepper", 1)))
    m.plate("warning", (-4, 2, 4.99), (20, 5, 4.99), {"north": (warn, [0, 0, 192, 24])})
    m.group("case")
    case = Paint("blacksteel", 2, "metal", noise=0.02, rim=1.0)
    m.box("back", (-16, 13, 14), (32, 29, 16), case)
    m.box("side", (-16, 13, 6), (-15, 29, 14), case)
    m.box("side", (31, 13, 6), (32, 29, 14), case)
    m.box("top", (-16, 29, 5), (32, 32, 16), case)
    head = _surface("store_cig_head", lambda: ART.header("담배", 384, 24, bg=(GREEN, 2), edge=(GREEN, 1), scale=1))
    m.plate("header", (-16, 29.5, 4.99), (32, 32, 4.99), {"north": (head, [0, 0, 384, 20])}, glow=True)
    m.group("packs")

    def packs(seed, rail_y):
        """A row of packs: 2 texels a pack, three packs a brand side by side, the brand's top band and body colour,
        the grey health-warning picture at the bottom, a white price rail with a yellow tag under each brand."""
        def decal(c):
            x, y = c.p[0], c.p[1]
            col = math.floor((x + 16) / 2)
            brand = PACKS[(col // 3 + seed) % len(PACKS)]
            if c.face == "up":
                return PAL[brand[0]]
            v = math.floor(y - rail_y)
            if v == 0:
                return ("corn", 3) if col % 3 == 1 and math.floor(x) % 2 == 0 else ("car_white", 3)
            if v == 3:
                return PAL[brand[0]]
            if v == 2:
                return PAL[brand[1]] if math.floor(x + 16) % 2 == 0 else PAL[brand[2]]
            return ("stone", 2) if math.floor(x + 16) % 2 == 0 else ("stone", 1)
        return decal
    for (x1, y, seed) in ((21, 16.5, 0), (31, 20.5, 5), (31, 24.5, 9)):
        pk = _surface(f"store_cig_packs_{seed}", lambda n=x1 + 15, sd=seed: ART.cig_packs(n, sd))
        m.skin("row", (-15, y, 9), (x1, y + 4, 14), Paint("iron", 2, noise=0, decal=packs(seed, y)), "north", pk)
    # the gum rack and the lottery dispenser on the cabinet
    m.group("gum_lottery")
    m.box("gum", (-15, 13, 6), (21, 16, 11), Paint(GREEN, 3, noise=0, decal=facing_decal_rows(
        ["gWgPWpYWyBWb", "GWGZWzIWdbWB", "kkkkkkkkkkkk"], -15, 13, 0, ("iron", 2))))
    m.box("lotto", (22, 13, 7), (31, 21, 14), Paint("fabric_red", 2, rim=1.2, decal=lambda c: (
        ("petal_yellow", 2) if c.face == "north" and 14 <= c.p[1] < 19 and math.floor(c.p[0]) % 3 else
        ("fabric_red", 3) if c.face == "north" and 14 <= c.p[1] < 19 else None)))
    lot = _surface("store_lotto_label", lambda: ART.header("복권", 64, 16, bg=("corn", 2), fg=("fabric_red", 2), edge=("corn", 1)))
    m.plate("lotto_label", (23, 19, 6.99), (30, 21, 6.99), {"north": (lot, [0, 0, 64, 16])})
    return gui(m)


# ================================================================================================
# Seats: 시식대 stools and the parasol's chairs (inside the middle 8 x 8 of their cells)
# ================================================================================================

def bar_stool(m, name, ox, oz):
    m.group(name)
    cyl(m, "base", (ox, oz), 6, 0, 0.75, flat("iron", 2, rim=1.2))
    m.box("pole", (ox - 0.5, 0.75, oz - 0.5), (ox + 0.5, 7, oz + 0.5), flat("steel", 4, rim=1.2))
    m.box("footring", (ox - 2.5, 3.5, oz - 0.25), (ox + 2.5, 4, oz + 0.25), flat("steel", 4))

    def cushion(c):
        if c.face == "up" and math.hypot(c.p[0] - ox, c.p[2] - oz) > 2.6:
            return ("iron", 3)
        if c.face != "up":
            return (GREEN, 2)                              # a green welt round the cushion
        return None
    cyl(m, "seat", (ox, oz), 7, 7, 8, Paint("iron", 2, "lacquer", noise=0.0, decal=cushion))


def plastic_chair(m, name, ox, oz, back, paint):
    """A white plastic patio chair round (ox, oz) inside +-4: the seat, four legs, the backrest on the `back` side
    ('w' = -x, 'e' = +x), slats painted."""
    m.group(name)
    x0, x1, z0, z1 = ox - 4, ox + 4, oz - 4, oz + 4
    m.box("seat", (x0, 7, z0), (x1, 8, z1), paint)
    for (lx, lz) in ((x0, z0), (x1 - 1, z0), (x0, z1 - 1), (x1 - 1, z1 - 1)):
        m.box("leg", (lx, 0, lz), (lx + 1, 7, lz + 1), paint(rim=0.8))
    bx = x0 if back == "w" else x1 - 1

    def slats(c):
        if c.face in ("east", "west") and 9 <= c.p[1] < 15 and math.floor(c.p[2] - z0) in (2, 5):
            return (paint.ramp, paint.base - 2)
        return None
    m.box("back", (bx, 8, z0), (bx + 1, 16, z1), paint(decal=slats))
    ax = (x0 + 1, x1) if back == "w" else (x0, x1 - 1)
    m.box("armrest", (ax[0], 11, z0), (ax[1], 12, z0 + 1), paint)
    m.box("armrest", (ax[0], 11, z1 - 1), (ax[1], 12, z1), paint)


# ================================================================================================
# 시식대 · 파라솔
# ================================================================================================

EAT_SEATS = [(-1, 0), (0, 0), (1, 0)]
PARASOL_SEATS = [(-1, 0), (1, 0)]


def eat_bar():
    m = HModel("store_eat_bar", floor=0, planes=(("z>", 16),))
    m.group("bar")
    wood = Paint("hinoki", 2, "grain", axis="x", noise=0.05, rim=1.2)
    m.box("top", (-16, 13, 12), (32, 14, 16), wood)
    m.box("lip", (-16, 12.5, 12), (32, 13, 12.5), flat("hinoki", 1))
    for x in (-15, 7.5, 30):
        m.box("leg", (x, 0.5, 14), (x + 1, 12.5, 15), flat("steel", 4, rim=1.2))
        m.box("leg_foot", (x - 0.5, 0, 13.5), (x + 1.5, 0.5, 15.5), flat("iron", 2))
    for x in (-15, 7.5, 30):
        m.box("bracket", (x, 11.5, 12.5), (x + 1, 12.5, 14), flat("steel", 3))
    m.group("things")

    def holder(c):
        if c.face == "north" and 14.5 <= c.p[1] < 16:
            return ("snow", 2) if c.p[1] < 15.75 else ("car_white", 3)   # the napkins showing through its slot
        if c.face == "up" and c.p[2] < 14.5:
            return ("snow", 2)
        return None
    m.box("napkins", (-4, 14, 13), (-1, 16.5, 15), Paint("steel", 4, "metal", noise=0.02, rim=1.2, decal=holder))
    cyl(m, "chopsticks_cup", (21, 14.5), 2, 14, 16, Paint("steel", 4, "metal", rim=1.2))
    for dx in (-0.5, 0.25):
        m.box("chopstick", (20.75 + dx, 16, 14.25), (21 + dx, 18, 14.5), flat("hinoki", 3))
    m.box("spoon", (21.25, 16, 14.75), (21.75, 17.5, 15), flat("steel", 5))
    # sauces: a red hot sauce and a yellow mustard bottle with their nozzles
    for (sx, col) in ((24, ("pepper", 2)), (25.25, ("corn", 2))):
        m.box("sauce", (sx, 14, 14), (sx + 1, 16, 15), Paint(col[0], col[1], "lacquer", rim=1.2,
              decal=lambda c, col=col: ("snow", 2) if c.face == "north" and 14.75 <= c.p[1] < 15.5 else None))
        m.box("sauce_cap", (sx + 0.25, 16, 14.25), (sx + 0.75, 16.75, 14.75), flat("snow", 2))
    # a little acrylic card: 셀프 정리 (please clear your table)
    card = _surface("store_eat_card", lambda: ART.stand_card("셀프 정리", w=64, h=40))
    m.box("card_foot", (8.5, 14, 13.25), (12.5, 14.25, 14.75), flat("glass", 3))
    m.plate("card", (8.5, 14.25, 14), (12.5, 16.75, 14), {"north": (card, [0, 0, 64, 40]), "south": (card, [0, 0, 64, 40])})
    for (sx, _) in EAT_SEATS:
        bar_stool(m, f"stool_{sx}", sx * 16 + 7.5, 4.5)
    return gui(m)


def parasol():
    m = HModel("store_parasol", floor=0, scale=2)
    white = Paint("car_white", 3, "lacquer", noise=0.02, rim=1.0)
    m.group("table")
    cyl(m, "base", (8, 8), 8, 0, 1, flat("iron", 2, rim=1.2))
    m.box("pedestal", (7, 1, 7), (9, 11, 9), white)

    def top(c):
        r = math.hypot(c.p[0] - 8, c.p[2] - 8)
        if c.face == "up" and r > 5:
            return ("car_white", 2)
        if c.face == "up" and r < 1:
            return ("iron", 2)
        return None
    cyl(m, "top", (8, 8), 12, 11, 12, white(decal=top))
    m.group("parasol")
    m.box("pole", (7.5, 12, 7.5), (8.5, 40, 8.5), flat("car_white", 2, rim=1.4))

    def canopy(c):
        # eight panels, green and white, meeting along the diagonals and the middle lines
        x, z = c.p[0] - 8, c.p[2] - 8
        k = math.floor((math.atan2(z, x) + math.pi) / (math.pi / 4)) % 2
        if c.face in SIDES and c.p[1] < 36.5:
            along = x if c.face in ("north", "south") else z
            if c.p[1] < 35.25 and abs((along + 20) % 4 - 2) > 1.2:
                return (GREEN, 1) if k else ("car_white", 2)   # the scallops' dips
        return (GREEN, 2) if k else ("car_white", 3)
    cpaint = Paint(GREEN, 2, noise=0.02, decal=canopy, rim=0.8)
    m.box("valance", (-12, 34.5, -12), (28, 36.5, 28), cpaint)
    for i, (hw, y0, y1) in enumerate(((19, 36.5, 38), (15, 38, 39.5), (11, 39.5, 41), (7, 41, 42.5), (3, 42.5, 43.5))):
        m.box("canopy", (8 - hw, y0, 8 - hw), (8 + hw, y1, 8 + hw), cpaint)
    m.box("finial", (7.25, 43.5, 7.25), (8.75, 45, 8.75), flat("car_white", 3))
    # the GS26 print on the valance's four sides (the lightbox's picture, small)
    logo = _surface("store_parasol_logo", lambda: ART.sign_face().resize((80, 16), 0))
    L = 0.1
    m.plate("print", (4.25, 35.25, -12 - L), (11.75, 36.75, -12 - L), {"north": (logo, [0, 0, 80, 16])}, one_sided=True)
    m.plate("print", (4.25, 35.25, 28 + L), (11.75, 36.75, 28 + L), {"south": (logo, [0, 0, 80, 16])}, one_sided=True)
    m.plate("print", (-12 - L, 35.25, 4.25), (-12 - L, 36.75, 11.75), {"west": (logo, [0, 0, 80, 16])}, one_sided=True)
    m.plate("print", (28 + L, 35.25, 4.25), (28 + L, 36.75, 11.75), {"east": (logo, [0, 0, 80, 16])}, one_sided=True)
    # the runner on the pole and four stretchers up to the canopy's ribs
    m.box("runner", (7.25, 30, 7.25), (8.75, 31.5, 8.75), flat("steel", 4, rim=1.2))
    rib = flat("steel", 4, rim=1.1)
    m.box("stretcher", (8.75, 30.75, 7.75), (21.75, 31.25, 8.25), rib, rotation=("z", 22.5, (8.75, 31, 8)))
    m.box("stretcher", (-5.75, 30.75, 7.75), (7.25, 31.25, 8.25), rib, rotation=("z", -22.5, (7.25, 31, 8)))
    m.box("stretcher", (7.75, 30.75, 8.75), (8.25, 31.25, 21.75), rib, rotation=("x", -22.5, (8, 31, 8.75)))
    m.box("stretcher", (7.75, 30.75, -5.75), (8.25, 31.25, 7.25), rib, rotation=("x", 22.5, (8, 31, 7.25)))
    plastic_chair(m, "seat0_chair", -8, 8, "w", Paint("car_white", 3, "lacquer", noise=0.02, rim=1.0))
    plastic_chair(m, "seat1_chair", 24, 8, "e", Paint("car_white", 3, "lacquer", noise=0.02, rim=1.0))
    return gui(m)


# ================================================================================================
# 조끼 걸이 · 폐기 바구니 · 대걸레 통
# ================================================================================================

def _vest_paint(c, x0, x1, y0, y1):
    """The work vest seen from in front (north) or behind: green, a V neck, a zip, a name tag, a yellow band."""
    x, y = c.p[0], c.p[1]
    mid = (x0 + x1) / 2
    if c.face == "north":
        if y > y1 - 4 and abs(x - mid) < (y - (y1 - 4)) * 0.6 + 0.3:
            return ("iron", 1)                             # the V neck, dark inside
        if abs(x - mid) < 0.5:
            return ("steel", 4)                            # the zip
        if mid - 4 <= x < mid - 1.5 and y1 - 6 <= y < y1 - 4.5:
            return ("snow", 2)                             # the name tag
    if math.floor(y) == math.floor(y0 + 3):
        return ("corn", 3)                                 # the band
    if y < y0 + 1:
        return (GREEN, 1)
    return None


def hanger():
    m = HModel("store_hanger", floor=0)
    m.group("stand")
    cyl(m, "base", (8, 8), 10, 0, 1, Paint("iron", 2, "metal", rim=1.2))
    m.box("pole", (7.25, 1, 7.25), (8.75, 30, 8.75), Paint("hinoki", 1, "grain", axis="y", rim=1.0))
    cyl(m, "knob", (8, 8), 3, 30, 31.5, flat("hinoki", 2, rim=1.2))
    m.box("hook", (3.5, 27.5, 7.5), (7.25, 28.5, 8.5), flat("steel", 4))
    m.box("hook", (8.75, 27.5, 7.5), (12.5, 28.5, 8.5), flat("steel", 4))
    m.box("hook", (7.5, 27.5, 8.75), (8.5, 28.5, 12.5), flat("steel", 4))
    m.group("vest")
    m.box("hanger_bar", (2.75, 25.5, 6), (13.25, 26.5, 7), flat("hinoki", 3))
    m.box("hanger_hook", (7.5, 26.5, 6.25), (8.5, 28, 6.75), flat("steel", 4))
    vest = Paint(GREEN, 2, "weave", noise=0.03, decal=lambda c: _vest_paint(c, 3, 13, 13, 26))
    m.box("vest", (3, 13, 4.5), (13, 25.5, 6.5), vest)
    tag = _surface("store_hanger_tag", lambda: ART.header("알바", 32, 16, bg=("snow", 2), fg=(GREEN, 1), edge=(GREEN, 2)))
    m.plate("name_tag", (3.75, 19.75, 4.49), (6.75, 21.25, 4.49), {"north": (tag, [0, 0, 32, 16])}, one_sided=True)
    m.box("shoulder", (3.5, 25.5, 4.5), (6, 26, 6.5), vest)
    m.box("shoulder", (10, 25.5, 4.5), (12.5, 26, 6.5), vest)
    m.group("cap")
    cyl(m, "cap", (8, 10.5), 5, 27, 29, Paint(GREEN, 2, decal=lambda c: ("corn", 3) if c.face == "south" and c.p[1] < 27.5 else None))
    m.box("brim", (6, 27, 13), (10, 27.5, 15.5), flat(GREEN, 1))
    return gui(m)


def waste():
    m = HModel("store_waste", floor=0)
    m.group("crate")
    blue = Paint("sapphire", 2, "lacquer", noise=0.03, rim=1.0,
                 decal=lambda c: ("sapphire", 1) if c.face in SIDES and 7 <= c.p[1] < 8 and abs(((c.p[0] + c.p[2]) % 16) - 8) < 2 else None)
    m.box("floor", (2, 0, 3), (14, 1, 13), blue)
    m.box("wall", (2, 1, 3), (14, 9, 4), blue)
    m.box("wall", (2, 1, 12), (14, 9, 13), blue)
    m.box("wall", (2, 1, 4), (3, 9, 12), blue)
    m.box("wall", (13, 1, 4), (14, 9, 12), blue)
    lab = _surface("store_waste_label", lambda: ART.header("폐기", 64, 32, bg=("snow", 2), fg=("pepper", 2), edge=("pepper", 2), scale=1))
    m.plate("label", (4, 2, 2.99), (12, 6, 2.99), {"north": (lab, [0, 0, 64, 32])})
    m.group("goods")

    def junk(c):
        if c.face != "up":
            return ("rice", 2)
        x, z = c.p[0], c.p[2]
        if 3 <= x < 8.5:
            if math.floor(z) % 3 == 0:
                return ("roe", 1)                          # kimbap seaweed
            return ("pepper", 2) if (math.floor(x) == 5 and math.floor(z) % 3 == 1) else ("rice", 2)
        if math.floor(z) in (4, 8, 11):
            return ("iron", 1)                             # the lunch boxes' rims
        if 9.5 <= x < 11 and math.floor(z) in (6, 10):
            return ("pepper", 2)                           # red 폐기 stickers
        return ("corn", 2) if (math.floor(x) + math.floor(z)) % 3 == 0 else ("pepper", 3) if (math.floor(x) + math.floor(z)) % 3 == 1 else ("rice", 2)
    m.box("goods", (3, 1, 4), (13, 7.5, 12), Paint("rice", 2, noise=0.03, decal=junk))
    m.box("dosirak", (8, 7.5, 5), (12.5, 8.5, 9.5), flat("iron", 1, decal=lambda c: ("pepper", 2) if c.face == "up" and c.p[0] > 11 else None),
          rotation=("y", 22.5, (10, 8, 7)))
    return gui(m)


def mop():
    m = HModel("store_mop", floor=0)
    m.group("bucket")
    yellow = Paint("corn", 2, "lacquer", noise=0.03, rim=1.0)
    for (x, z) in ((3, 4), (12, 4), (3, 11.5), (12, 11.5)):
        m.box("caster", (x, 0, z), (x + 1, 1, z + 1), flat("iron", 1))
    m.box("floor", (2.5, 1, 3.5), (13.5, 2, 12.5), yellow)
    m.box("wall", (2.5, 2, 3.5), (13.5, 9, 4.5), yellow(decal=lambda c: ("iron", 1) if c.face == "north" and 5 <= c.p[1] < 6 else None))
    m.box("wall", (2.5, 2, 11.5), (13.5, 9, 12.5), yellow)
    m.box("wall", (2.5, 2, 4.5), (3.5, 9, 11.5), yellow)
    m.box("wall", (12.5, 2, 4.5), (13.5, 9, 11.5), yellow)
    m.box("water", (3.5, 2, 4.5), (12.5, 6.5, 11.5), Paint("stone", 2, noise=0.06))
    warn = _surface("store_mop_label", lambda: ART.header("미끄럼 주의", 80, 16, bg=("iron", 1), fg=("corn", 2), edge=("iron", 0)))
    m.plate("label", (3.5, 4.75, 3.49), (12.5, 6.5, 3.49), {"north": (warn, [0, 0, 80, 16])}, one_sided=True)
    m.box("wringer", (3.5, 9, 8.5), (12.5, 12, 11.5), Paint("iron", 3, "metal", rim=1.2))
    m.box("lever", (11.5, 12, 9.5), (12.5, 19, 10.5), flat("iron", 2, rim=1.2))
    m.box("lever_grip", (11.25, 19, 9.25), (12.75, 21, 10.75), flat("pepper", 2))
    m.group("mop")

    def strands(c):
        if c.face in SIDES and math.floor(c.p[0] + c.p[2]) % 2:
            return ("car_white", 1)
        return None
    m.box("head", (4, 5.5, 5), (9, 8.5, 9), Paint("car_white", 2, noise=0.08, decal=strands))
    m.box("handle", (6, 8.5, 6.5), (7, 30, 7.5), flat("signal_blue", 1, rim=1.4), rotation=("z", -22.5, (6.5, 8.5, 7)))
    return gui(m)


# ================================================================================================
# 간판 GS26
# ================================================================================================

def sign():
    m = HModel("store_sign", floor=None, planes=(("z>", 16),), scale=2)
    face = _surface("store_sign_face", ART.sign_face)
    back = _surface("store_sign_back", ART.sign_back)
    m.group("box")
    m.box("lightbox", (-32, 0, 11), (48, 16, 16), Paint("steel", 4, "metal", noise=0.02, rim=1.2))
    m.box("cap", (-33, 16, 10), (49, 17, 16), Paint("iron", 3, "metal", rim=1.2))
    m.box("sill", (-33, -1, 10.5), (49, 0, 16), Paint("iron", 3, "metal", rim=1.2))
    for bx in (-24, 38):
        m.box("bracket", (bx, 17, 12), (bx + 2, 18.5, 16), Paint("iron", 2, "metal", rim=1.2))
    m.plate("face", (-32, 0, 10.75), (48, 16, 11),
            {"north": (face, [0, 0, 320, 64]), "up": (back, [0, 0, 16, 1]), "down": (back, [0, 0, 16, 1]),
             "east": (back, [0, 0, 1, 16]), "west": (back, [0, 0, 1, 16])}, glow=True)
    return gui(m)


# ================================================================================================
# The goods (display models, drawn 2x and shrunk: BModel scale 2; bottom on y 8, centred)
# ================================================================================================

def G(name):
    return HModel(name, floor=8, scale=2)


def _ang(c):
    return math.atan2(c.p[2] - 8, c.p[0] - 8)


def _front(c):
    """True on a round thing's front half (facing north, -z)."""
    return c.face != "up" and c.face != "down" and c.p[2] < 8.01


def samgak(key, label):
    m = G(f"store_item_{key}")

    def wrap(c):
        x, y = c.p[0] - 8, c.p[1] - 8
        if c.face == "up":
            return ("rice", 3)
        if y < 2:
            return ("roe", 1)                              # the seaweed, wrapped round the base
        if abs(x) < 0.5 and c.face in ("north", "south"):
            return ("pepper", 2)                           # the red pull strip
        if c.face in ("north", "south") and abs(x) < 3 and 4 <= y < 7:
            return (label[0], label[1] + (1 if y >= 6 else 0)) if not (abs(x) < 2 and y == 5 and c.i[0] % 2) else ("snow", 2)
        if c.face in ("east", "west"):
            return ("roe", 1) if y < 3 else ("rice", 2)
        return None
    p = Paint("rice", 2, noise=0.03, decal=wrap)
    for k in range(10):
        hw = 5.5 - k * 0.55
        m.box("tri", (8 - hw, 8 + k, 6), (8 + hw, 9 + k, 10), p)
    return held(m)


def dosirak():
    m = G("store_item_dosirak")

    def lid(c):
        x, z = c.p[0], c.p[2]
        if c.face == "up":
            if 7.25 <= x < 8.75:
                return (GREEN, 2)                          # the paper sleeve
            if x < 7.25:
                return ("rice", 3) if h3(c.i[0], c.i[2], 0, 2) > 0.1 else ("roe", 1)
            if z < 7.5:
                return ("pepper", 2) if x < 11 else ("corn", 2)        # kimchi | rolled egg
            if x < 11:
                return ("doenjang", 2) if (c.i[0] + c.i[2]) % 3 else ("doenjang", 3)   # bulgogi
            return ("leaf", 3) if z < 10.5 else ("salmon", 3)          # greens | sausage
        if 7.25 <= x < 8.75:
            return (GREEN, 2)
        return ("glass", 2) if c.face in SIDES else None
    m.box("tray", (1, 8, 3), (15, 10, 13), Paint("iron", 1, rim=1.0))
    top = _surface("store_front_dosirak_top", lambda: ART.goods_front("dosirak_top", 52, 36))
    m.skin("lid", (1.5, 10, 3.5), (14.5, 12, 12.5), Paint("glass", 3, noise=0, decal=lid), "up", top, uv=[52, 36, 0, 0])
    return held(m, grip_y=8.5)


def hotbar():
    m = G("store_item_hotbar")

    def grill(c):
        if c.face in ("up", "north", "south") and math.floor(c.p[0] + c.p[1]) % 3 == 0:
            return ("onion", 1)
        return None
    m.box("body", (1, 8, 6.5), (12, 11, 9.5), Paint("onion", 2, noise=0.06, decal=grill))
    m.box("stick", (12, 9, 7.5), (17, 10, 8.5), flat("hinoki", 3))
    return held(m, grip_y=9.5)


def egg():
    m = G("store_item_egg")
    cells = {}
    for cx in (5.5, 10.5):
        for x in range(2, 15):
            for y in range(8, 15):
                for z in range(5, 12):
                    if ((x + 0.5 - cx) / 2.5) ** 2 + ((y + 0.5 - 11.5) / 3.5) ** 2 + ((z + 0.5 - 8) / 3) ** 2 <= 1:
                        cells[(x, y, z)] = "egg"

    def net(c):
        if (math.floor(c.p[0] + c.p[1]) % 4 == 0 or math.floor(c.p[0] - c.p[1]) % 4 == 0) and h3(*c.i, 4) < 0.7:
            return ("fox", 2)
        return None
    voxels(m, "egg", cells, lambda _l: Paint("bean", 3, noise=0.05, decal=net))
    m.box("tag", (7, 13, 6.5), (9, 15.5, 9.5), Paint("pepper", 2, decal=lambda c: ("snow", 2) if 14 <= c.p[1] < 14.5 else None))
    return held(m)


def _cup(m, d0, d1, h, body, lid):
    """A tapered cup in 3 bands from d0 at the foot to d1 at the rim, height h, a foil lid."""
    y = 8
    steps = [(d0, h * 0.3), ((d0 + d1) / 2, h * 0.35), (d1, h * 0.35)]
    for d, hh in steps:
        dd = round(d)
        cyl(m, "cup", (8, 8), dd, y, y + hh, body)
        y += hh
    cyl(m, "lid", (8, 8), round(d1), y, y + 0.5, lid)
    return y + 0.5


def ramen(key, ramp, label, band):
    m = G(f"store_item_{key}")
    big = key == "ramen_big"

    def body(c):
        y = c.p[1] - 8
        top = 12 if not big else 7
        if y >= top - 1.5:
            return band                                    # the rim band
        if _front(c) and abs(c.p[0] - 8) < 3.2 and top * 0.25 <= y < top * 0.85:
            dx = c.p[0] - 8
            if y >= top * 0.6:
                if abs(dx) < 2.6 and y < top * 0.85 - 0.5:
                    return ("snow", 2) if math.floor(y) == math.floor(top * 0.72) and abs(dx) < 1.6 and c.i[0] % 2 else label
                return ("snow", 2)                         # the brand's block on the white panel
            if abs(dx) < 2.2 and top * 0.3 <= y < top * 0.5:
                return ("corn", 2) if (c.i[0] + c.i[1]) % 3 == 0 else ("tomato", 3)   # the bowl: noodles in soup
            if abs(dx) < 2.6 and top * 0.25 <= y < top * 0.3:
                return ("car_white", 2)                    # the bowl's rim
            return ("snow", 2)
        if c.face in SIDES and not _front(c) and math.floor(_ang(c) * 4) % 3 == 0 and y < top - 1.5:
            return (ramp[0], ramp[1] + 1)                  # the print's stripes round the back
        if y < 1:
            return (ramp[0], ramp[1] - 1)
        return None

    def lid(c):
        if c.face == "up":
            r = math.hypot(c.p[0] - 8, c.p[2] - 8)
            if r < 2.5:
                return label
            return ("car_white", 3) if r < 4.5 else band
        return ("fish_silver", 3)
    if big:
        _cup(m, 11, 13, 7.5, Paint(ramp[0], ramp[1], noise=0.02, decal=body), Paint("car_white", 3, decal=lid))
    else:
        _cup(m, 8, 10, 12, Paint(ramp[0], ramp[1], noise=0.02, decal=body), Paint("car_white", 3, decal=lid))
    m.box("tab", (7, 8 + (12.5 if not big else 8), 1.5 if big else 2.5), (9, 8 + (13 if not big else 8.5), 3.5 if big else 4.5),
          flat("car_white", 3))
    return held(m)


def bottle(key, body, label, cap, d=6, h=13, neck=(5, 4, 3), cap_h=1.5, label_band=(3, 7), shine=True, cap_d=4):
    """A PET or glass bottle: body d to y 8+h, shoulders, neck, cap; a label band round it."""
    m = G(f"store_item_{key}")

    def paint(c):
        y = c.p[1] - 8
        if label_band[0] <= y < label_band[1]:
            if _front(c) and abs(c.p[0] - 8) < 1.5 and label_band[0] + 1 <= y < label_band[1] - 1:
                return label[1]
            return label[0]
        if shine and c.face in ("north", "west") and abs(c.p[0] - 6.5) < 0.6:
            return (body[0], body[1] + 1)
        return None
    p = Paint(body[0], body[1], noise=0.0, decal=paint)
    cyl(m, "body", (8, 8), d, 8, 8 + h, p)
    y = 8 + h
    for dd, hh in zip(neck, (1, 1, 2)):
        cyl(m, "neck", (8, 8), dd, y, y + hh, p)
        y += hh
    cyl(m, "cap", (8, 8), cap_d, y, y + cap_h, flat(cap[0], cap[1], rim=1.2))
    return held(m)


def can(key, body, band, d=6, h=10):
    m = G(f"store_item_{key}")

    def paint(c):
        y = c.p[1] - 8
        if y < 0.75 or y >= h - 0.75:
            return ("fish_silver", 3)
        if band[0] <= y < band[1]:
            return band[2]
        if _front(c) and abs(c.p[0] - 8) < 1.5 and band[1] + 1 <= y < band[1] + 3:
            return ("snow", 2)
        return None
    cyl(m, "can", (8, 8), d, 8, 8 + h, Paint(body[0], body[1], "metal", noise=0.02, decal=paint))
    cyl(m, "lid", (8, 8), d - 2, 8 + h, 8 + h + 0.5, flat("fish_silver", 4, decal=lambda c: ("iron", 3) if c.face == "up" and abs(c.p[0] - 8) < 0.6 and c.p[2] < 8 else None))
    return held(m)


def banana_milk():
    m = G("store_item_banana_milk")

    def paint(c):
        y = c.p[1] - 8
        if _front(c) and 2 <= y < 5.5 and abs(c.p[0] - 8) < 3:
            # a white label, a banana on it
            if 3 <= y < 4.5 and abs(c.p[0] - 8 - (y - 3.5) * 0.8) < 1.6:
                return ("corn", 2)
            return ("snow", 2)
        return None
    p = Paint("petal_yellow", 2, noise=0.02, decal=paint)
    for (d, a, b) in ((7, 0, 1), (9, 1, 6), (8, 6, 7), (6, 7, 8), (4, 8, 9)):
        cyl(m, "bottle", (8, 8), d, 8 + a, 8 + b, p)
    cyl(m, "cap", (8, 8), 4, 17, 18.5, flat(GREEN, 2, rim=1.2))
    return held(m)


def coffee_cup():
    m = G("store_item_coffee_cup")

    def paint(c):
        y = c.p[1] - 8
        if _front(c) and 3 <= y < 7 and abs(c.p[0] - 8) < 2.5:
            return ("doenjang", 1) if 4 <= y < 6 else ("snow", 2)
        if y >= 8:
            return ("fish_silver", 3)
        return None
    _cup(m, 7, 8, 9, Paint("bean", 3, noise=0.02, decal=paint), Paint("fish_silver", 4, decal=lambda c: ("doenjang", 2) if c.face == "up" and math.hypot(c.p[0] - 8, c.p[2] - 8) < 1.5 else None))
    m.box("straw", (12, 9, 7.5), (12.5, 17, 8.5), flat("snow", 1))
    return held(m)


def hangover():
    m = G("store_item_hangover")

    def paint(c):
        y = c.p[1] - 8
        if 1.5 <= y < 5:
            if _front(c) and abs(c.p[0] - 8) < 1.2 and 2.5 <= y < 4:
                return ("gold", 3)
            return ("iron", 1)
        return None
    p = Paint("doenjang", 1, noise=0.0, decal=paint)
    cyl(m, "bottle", (8, 8), 5, 8, 15, p)
    cyl(m, "neck", (8, 8), 3, 15, 16, p)
    cyl(m, "cap", (8, 8), 4, 16, 17.5, Paint("gold", 2, "metal", rim=1.2))
    return held(m)


def bag_pack(key, body, pic, size=(10, 12)):
    """A pillow bag of snacks: a puffed body, crimped seams top and bottom, a picture on its front."""
    m = G(f"store_item_{key}")
    w, h = size
    x0, x1 = 8 - w / 2, 8 + w / 2

    def crimp(c):
        if c.face in ("north", "south") and math.floor(c.p[0]) % 2:
            return (body[0], body[1] + 1)
        return None
    p = Paint(body[0], body[1], "lacquer", noise=0.02, decal=pic)
    m.box("bottom_seam", (x0, 8, 7.25), (x1, 9, 8.75), Paint(body[0], body[1] - 1, decal=crimp))
    m.box("body", (x0, 9, 6), (x1, 8 + h - 1, 10), p)
    front = _surface(f"store_front_{key}", lambda k=key, a=(w - 2) * 4, b=(h - 5) * 4: ART.goods_front(k, a, b))
    m.skin("puff", (x0 + 1, 10.5, 5), (x1 - 1, 8 + h - 2.5, 11), p, "north", front)
    m.box("top_seam", (x0, 8 + h - 1, 7.25), (x1, 8 + h + 0.5, 8.75), Paint(body[0], body[1] - 1, decal=crimp))
    return held(m)


def _chips_pic(c):
    y = c.p[1] - 8
    if c.face in ("north", "south"):
        if 1.5 <= y < 3 or 9 <= y < 10.5:
            return ("pepper", 2)
        dx, dy = c.p[0] - 8, y - 6
        if (dx / 2.8) ** 2 + (dy / 1.8) ** 2 < 1:
            return ("corn", 3) if dx + dy < 0.5 else ("onion", 3)      # a potato chip
        if 3.5 <= y < 4.25 and abs(c.p[0] - 8) < 3:
            return ("pepper", 3)
    return None


def _shrimp_pic(c):
    y = c.p[1] - 8
    if c.face in ("north", "south"):
        if y >= 8.5 or y < 2:
            return ("pepper", 2)
        dx, dy = c.p[0] - 8, y - 5
        r = math.hypot(dx, dy)
        if 1.2 < r < 2.8 and not (dx > 0 and dy < 0):
            return ("fox", 3) if math.floor(math.atan2(dy, dx) * 3) % 2 else ("fox", 2)   # a curled shrimp
    return None


def _jelly_pic(c):
    y = c.p[1] - 8
    if c.face in ("north", "south") and 2 <= y < 7:
        k = (math.floor(c.p[0]) // 2 + math.floor(y) // 2) % 4
        if (math.floor(c.p[0]) + math.floor(y)) % 2 == 0:
            return [("tomato", 2), ("corn", 2), (GREEN, 3), ("pink", 2)][k]   # gummy bears
    return None


def _candy_pic(c):
    if c.face in ("north", "south", "east", "west") and 1 <= c.p[1] - 8 < 9:
        k = h3(c.i[0], c.i[1], c.i[2], 7)
        if k < 0.35:
            return [("tomato", 2), ("corn", 2), ("sapphire", 3), (GREEN, 3), ("crystal", 3)][int(k * 14) % 5]
    return None


def box_pack(key, size, body, pic):
    m = G(f"store_item_{key}")
    w, h, d = size
    front = _surface(f"store_front_{key}", lambda k=key, a=round(w * 4), b=round(h * 4): ART.goods_front(k, a, b))
    m.skin("box", (8 - w / 2, 8, 8 - d / 2), (8 + w / 2, 8 + h, 8 + d / 2), Paint(body[0], body[1], "lacquer", noise=0.02, rim=1.1, decal=pic),
           "north", front)
    return held(m)


def _choco_pic(c):
    y = c.p[1] - 8
    if c.face in ("north", "south"):
        if y >= 7.5:
            return ("snow", 2)
        for cx in (5.5, 10.5):
            r = math.hypot(c.p[0] - cx, y - 3.5)
            if r < 2.2:
                return ("doenjang", 1) if r > 1.4 else ("doenjang", 2)  # two choco pies
    if c.face == "up":
        return ("pepper", 3)
    return None


def _chocolate_pic(c):
    x = c.p[0]
    if x > 12:
        return ("gold", 3)                                 # the foil at the end
    if 4 <= x < 6:
        return ("snow", 2)
    if c.face in ("north", "south") and 7 <= x < 11 and 9 <= c.p[1] < 10.5:
        return ("gold", 2)
    return None


def _gum_pic(c):
    if math.floor(c.p[0]) % 3 == 0:
        return ("snow", 2)
    return None


def _tissue_pic(c):
    if c.face in SIDES:
        if c.p[1] < 9:
            return ("sapphire", 3)
        if (math.floor(c.p[0] + c.p[2]) + math.floor(c.p[1])) % 5 == 0:
            return ("pink", 2)                             # little flowers
    if c.face == "up" and abs(c.p[0] - 8) < 3 and abs(c.p[2] - 8) < 1.2:
        return ("iron", 3)                                 # the slot
    return None


def _bandage_pic(c):
    y = c.p[1] - 8
    if c.face in ("north", "south"):
        if y >= 4.5:
            return ("pink", 2)
        if abs(c.p[0] - 8) < 3.5 and 1.5 <= y < 3.5:
            if abs(c.p[0] - 8) < 1:
                return ("salmon", 2)
            return ("salmon", 3) if (c.i[0] + c.i[1]) % 2 else ("bean", 3)   # a plaster strip
    return None


def _cig_pic(c):
    y = c.p[1] - 8
    if c.face == "north":
        if 1 <= y < 5:
            return ("stone", 2) if 1.5 <= y < 4.5 else ("car_white", 2)       # the health warning picture
        if 6 <= y < 7:
            return ("sapphire", 2)
    if y >= 7.5 and c.face in SIDES:
        return ("sapphire", 2)
    if 7.25 <= y < 7.5:
        return ("fish_silver", 3)                          # the lid's hinge line
    return None


def ice_bar():
    m = G("store_item_ice_bar")

    def pic(c):
        if c.face == "up":
            z = c.p[2]
            if z < 4 or z >= 12:
                return ("car_white", 3) if math.floor(c.p[0]) % 2 else ("car_white", 2)
            if 6 <= z < 11 and abs(c.p[0] - 8) < 1.8:
                return ("pepper", 2) if z < 10 else ("hinoki", 3)   # the popsicle on the wrapper
        return None
    m.box("end", (4.5, 8, 2), (11.5, 9, 4), flat("car_white", 3))
    m.box("wrap", (4, 8, 4), (12, 10, 12), Paint("sapphire", 3, "lacquer", noise=0.02, decal=pic))
    m.box("end", (4.5, 8, 12), (11.5, 9, 14), flat("car_white", 3))
    return held(m, grip_y=9)


def ice_cone():
    m = G("store_item_ice_cone")

    def sleeve(c):
        if math.floor(c.p[1] + _ang(c) * 2.5) % 3 == 0:
            return ("pepper", 2)
        return None
    p = Paint("car_white", 3, noise=0.02, decal=sleeve)
    for (d, a, b) in ((2, 0, 2), (3, 2, 4), (4, 4, 6), (5, 6, 8), (6, 8, 10)):
        cyl(m, "cone", (8, 8), d, 8 + a, 8 + b, p)
    cyl(m, "rim", (8, 8), 7, 18, 19, flat("pepper", 2))
    cyl(m, "lid", (8, 8), 6, 19, 20, Paint("glass", 3, noise=0, decal=lambda c: ("doenjang", 2) if c.face == "up" and (c.i[0] + c.i[2]) % 3 == 0 else ("snow", 1)))
    return held(m)


def umbrella():
    m = G("store_item_umbrella")

    def canopy(c):
        if 5 <= c.p[0] < 6:
            return ("snow", 2)                             # the strap
        if math.floor(c.p[0] + c.p[1] + c.p[2]) % 4 == 0:
            return ("ice", 3)
        return None
    p = Paint("ice", 2, noise=0, decal=canopy)
    cyl(m, "canopy", (10, 8), 4, -2, 9, p, axis="x")
    cyl(m, "canopy", (10, 8), 3, 9, 14, p, axis="x")
    m.box("tip", (14, 9.5, 7.5), (18, 10.5, 8.5), flat("iron", 2))
    m.box("shaft", (-6, 9.5, 7.5), (-2, 10.5, 8.5), flat("steel", 4))
    m.box("handle", (-10, 9.5, 7.5), (-6, 11, 8.5), flat("iron", 1, rim=1.2))
    m.box("hook", (-11, 8, 7.5), (-10, 11, 8.5), flat("iron", 1, rim=1.2))
    m.box("hook_end", (-10, 8, 7.5), (-8.5, 9, 8.5), flat("iron", 1))
    return held(m, grip_y=10)


def battery():
    m = G("store_item_battery")

    def card(c):
        if c.face == "up":
            if c.p[2] >= 12.5 and abs(c.p[0] - 8) < 1:
                return ("iron", 0)                         # the hang hole
            return ("fox", 3) if c.p[2] < 5 else ("iron", 1)
        return None
    m.box("card", (3, 8, 2), (13, 8.5, 14), Paint("iron", 1, decal=card))

    def cells(c):
        if c.face == "up" or c.face in SIDES:
            x = c.p[0]
            if 4.5 <= x < 7 or 9 <= x < 11.5:
                z = c.p[2]
                return ("copper", 3) if z >= 10 else ("iron", 2) if z < 6 else ("fish_silver", 4) if z >= 9.5 else ("iron", 1)
            return ("glass", 3)
        return None
    m.box("blister", (4, 8.5, 5), (12, 10, 11), Paint("glass", 3, noise=0, decal=cells))
    return held(m, grip_y=8.5)


def tissue():
    m = box_pack("tissue", (12, 6, 8), ("snow", 2), _tissue_pic)
    m.box("tissue", (6.5, 14, 7.5), (9.5, 16, 8.5), flat("snow", 2), rotation=("z", 22.5, (8, 14, 8)))
    return m


def lottery():
    tex = _surface("store_lottery_tex", ART.lottery)
    m = Model("store_item_lottery")
    m.group("ticket")
    m.box("ticket", (5, 8, 4), (11, 8.25, 12), tex,
          uv={"up": [48, 64, 0, 0], "down": [0, 0, 48, 64], "north": [0, 0, 1, 1], "south": [0, 0, 1, 1],
              "east": [0, 0, 1, 1], "west": [0, 0, 1, 1]})
    return held(m, grip_y=8)


# ================================================================================================
# Money, the card, the props
# ================================================================================================

def _flat_pic(name, tex_name, fn, w, h, pw, ph):
    """A picture lying flat (pw x ph px), 0.25 thick, its bottom on y 8, reading upright from the north; the picture
    is the top-left w x h texels of its texture."""
    tex = _surface(tex_name, fn)
    m = Model(name)
    m.group("flat")
    edge = [1, 1, 2, 2]
    m.box("flat", (8 - pw / 2, 8, 8 - ph / 2), (8 + pw / 2, 8.25, 8 + ph / 2), tex,
          uv={"up": [w, h, 0, 0], "down": [0, 0, w, h], "north": edge, "south": edge, "east": edge, "west": edge})
    return gui(m, fill=0.9)


def cash(n):
    return _flat_pic(f"store_cash_{n}", f"store_cash_{n}_tex", lambda n=n: ART.note(n), 56, 28, 7, 3.5)


def card():
    return _flat_pic("store_card", "store_card_tex", ART.card, 44, 28, 5.5, 3.5)


def coin(n):
    m = G(f"store_coin_{n}")
    d = 7 if n == 100 else 8
    base = 3 if n == 500 else 2

    def face(c):
        r = math.hypot(c.p[0] - 8, c.p[2] - 8)
        if c.face == "up":
            if r > d / 2 - 0.6:
                return ("fish_silver", base + 1)           # the rim
            if r < 1.1:
                return ("fish_silver", base - 1)           # the struck emblem
        if c.face in SIDES and math.floor(_ang(c) * 6) % 2:
            return ("fish_silver", base - 1)               # the milled edge
        return None
    cyl(m, "coin", (8, 8), d, 8, 9, Paint("fish_silver", base, "metal", noise=0.03, decal=face))
    return gui(m, fill=0.9)


def button(key, face_fn, tex):
    face = _surface(tex, face_fn)
    back = _surface("store_btn_back", ART.plate_back)
    m = Model(key)
    m.group("plate")
    m.box("face", (2, 5, 7.5), (14, 11, 7.75), back, faces={"north": face, "south": None}, uv={"north": [0, 0, 160, 80]}, glow=True)
    m.box("plate", (2, 5, 7.75), (14, 11, 8.5), back, glow=True)
    return gui(m, fill=0.9)


BOX_KIND = {"snack": ("과자", ("corn", 2)), "noodle": ("라면", ("pepper", 2)), "daily": ("생활용품", ("sapphire", 2)),
            "meal": ("도시락", (GREEN, 2)), "drink": ("음료", ("water", 2))}


def delivery_box(kind):
    word, col = BOX_KIND[kind]
    m = HModel(f"store_box_{kind}", floor=8)

    def kraft(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x - 8) < 1:
                return ("bean", 3) if math.floor(z) % 3 else ("bean", 2)   # the tape
            if abs(z - 8) < 0.25:
                return ("bean", 0)                         # the flaps' seam
        if c.face in ("north", "south") and abs(x - 8) < 1 and y > 13:
            return ("bean", 3)
        if c.face == "north" and 3 <= x < 7 and 9 <= y < 10:
            return col                                     # a colour bar under the label
        if c.face in ("east", "west") and 10 <= y < 13 and abs(c.p[2] - 8) < 2.5:
            return ("bean", 0) if 11 <= y < 12 and abs(c.p[2] - 8) < 1.5 else None   # the hand hole
        return None
    m.skin("box", (2, 8, 3), (14, 16, 13), Paint("bean", 1, "paper", noise=0.05, rim=1.1, decal=kraft), "up",
           _surface(f"store_box_top_{kind}", lambda c=col: ART.box_top(c)), uv=[48, 40, 0, 0])
    side = _surface("store_box_side", ART.box_side)
    m.plate("marks", (14.01, 8.5, 3.5), (14.01, 16, 12.5), {"east": (side, [0, 0, 40, 32])}, one_sided=True)
    m.plate("marks", (1.99, 8.5, 3.5), (1.99, 16, 12.5), {"west": (side, [0, 0, 40, 32])}, one_sided=True)
    lab = _surface(f"store_box_label_{kind}", lambda w=word, c=col: ART.header(w, 64, 32, bg=("snow", 2), fg=c, edge=c))
    m.plate("label", (8, 10, 2.99), (13, 13, 2.99), {"north": (lab, [0, 0, 64, 32])})
    return gui(m, fill=0.8)


def shopping_bag():
    m = G("store_bag")

    def plastic(c):
        y = c.p[1] - 8
        if c.face in ("north", "south") and 4 <= y < 8 and abs(c.p[0] - 8) < 3.5:
            if 4.5 <= y < 7.5 and abs(c.p[0] - 8 + 1.8) < 1.2:
                return ("corn", 2)                         # the sun-yellow disc of the print
            return (GREEN, 2) if 5 <= y < 7 and c.p[0] > 7 else ("snow", 2)
        if math.floor(c.p[0] * 0.7 + y) % 5 == 0:
            return ("snow", 1)                             # creases
        return None
    p = Paint("snow", 2, noise=0.03, decal=plastic)
    m.box("body", (3, 8, 5), (13, 17, 11), p)
    m.skin("bulge", (2.5, 9.5, 4.5), (13.5, 15, 11.5), p, "north", _surface("store_bag_print", ART.bag_print))
    m.box("top", (4, 17, 6), (12, 18.5, 10), p)
    for x in (4, 10.5):
        m.box("handle", (x, 18.5, 7.5), (x + 1.5, 22, 8.5), p)
    m.box("handle_top", (4, 22, 7.5), (12, 23, 8.5), p)
    cyl(m, "bottle_cap", (6.5, 7), 2, 18.5, 20, flat(GREEN, 2))
    return held(m, grip_y=22)


def spill():
    m = G("store_spill")

    def puddle(c):
        x, z = c.p[0] - 8, c.p[2] - 8
        v = vnoise(x + 30, 0, z + 30, 3.0, 5)
        r = math.hypot(x / 1.3, z)
        if r + (v - 0.5) * 6 > 9:
            return ("raw", (0, 0, 0, 0))
        if h3(c.i[0], c.i[2], 0, 2) < 0.08 or (abs(x + z * 0.5 - 2) < 0.6 and r < 6):
            return ("doenjang", 3)                         # the shine
        return ("doenjang", 1) if r < 7 else ("doenjang", 2)
    m.box("puddle", (-6, 8.1, -4), (22, 8.1, 20), Paint("doenjang", 1, noise=0, light=False, decal=puddle),
          )
    cyl(m, "cup", (11, 15), 5, 14, 22, Paint("snow", 2, decal=lambda c: (GREEN, 2) if 17 <= c.p[0] < 19 else None), axis="x")
    m.box("lid", (22, 8.25, 11.5), (22.5, 13.5, 18), flat("snow", 1))
    return gui(m, fill=0.8)


def trash_cup():
    m = G("store_trash_cup")
    body = Paint("pepper", 2, noise=0.02, decal=lambda c: ("snow", 2) if _front(c) and 11 <= c.p[1] < 14 and abs(c.p[0] - 8) < 3 else None)
    cyl(m, "cup", (8, 8), 8, 8, 12, body)
    cyl(m, "cup", (8, 8), 9, 12, 16, body(decal=lambda c: ("doenjang", 1) if c.face == "up" else
                                         (("snow", 2) if _front(c) and 12 <= c.p[1] < 14 and abs(c.p[0] - 8) < 3 else None)))
    ring(m, "rim", (8, 8), 10, 8, 16, 19, Paint("pepper", 2, decal=lambda c: ("pepper", 1) if c.face == "up" else ("snow", 2) if c.p[1] >= 18 else None))
    m.box("lid", (3, 19, 12.5), (13, 19.25, 18), flat("fish_silver", 4), rotation=("x", 45, (8, 19, 12.5)))
    m.box("chopstick", (1, 19.25, 7), (17, 19.75, 7.5), flat("hinoki", 3), rotation=("y", 22.5, (8, 19.5, 8)))
    m.box("chopstick", (1, 19.25, 8.5), (17, 19.75, 9), flat("hinoki", 3), rotation=("y", 22.5, (8, 19.5, 8)))
    return gui(m, fill=0.8)


def scanner():
    from models import bus_hold as BH
    m = G("store_scanner")
    body = Paint("iron", 2, "lacquer", noise=0.02, rim=1.2,
                 decal=lambda c: (GREEN, 2) if c.face in ("east", "west", "up") and 9 <= c.p[2] < 11 and c.p[1] > 15 else None)
    m.box("head", (5, 14, 0), (11, 19, 12), body)

    def window(c):
        if c.face == "north":
            return ("raw", (255, 70, 60, 255)) if 15 <= c.p[1] < 18 and 6 <= c.p[0] < 10 else ("raw", (60, 20, 22, 255))
        return None
    m.box("nose", (5.5, 14.5, -1), (10.5, 18.5, 0), Paint("iron", 1, glow=True, light=False, noise=0, decal=window))
    m.box("grip", (6, 5, 8), (10, 14, 12), Paint("rubber", 2, noise=0.04, rim=1.0), rotation=("x", -22.5, (8, 14, 10)))
    m.box("trigger", (7, 11, 6), (9, 14, 7.5), flat("corn", 2, rim=1.2), rotation=("x", -22.5, (8, 14, 10)))
    m.box("cable", (7.5, 3, 11), (8.5, 5, 12), flat("iron", 1), rotation=("x", -22.5, (8, 14, 10)))
    tr = BH.hold_transform(grip=BH_point(m, (8, 10, 10)), at=BH.fist(), turn=BH.turn(), scale=1.0, pose=BH.STANDING)
    BH.displays(m, tr, gui_fill=0.9)
    # first person: pointing ahead, a little in from the right
    m.display["firstperson_righthand"] = {"rotation": [0, 0, 0], "translation": [2, 3.5, -2], "scale": [0.9, 0.9, 0.9]}
    m.display["firstperson_lefthand"] = {"rotation": [0, 0, 0], "translation": [2, 3.5, -2], "scale": [0.9, 0.9, 0.9]}
    return m


def BH_point(m, drawn):
    """A drawn point of a scale-2 model in model units (the grip for the hand)."""
    return tuple(m.to_model(drawn))


def vest_item():
    m = G("store_vest")
    vest = Paint(GREEN, 2, "weave", noise=0.03, decal=lambda c: _vest_paint(c, 1, 15, 8, 24))
    m.box("vest", (1, 8, 6.5), (15, 23, 9.5), vest)
    m.box("strap", (2, 23, 6.5), (5.5, 25, 9.5), vest)
    m.box("strap", (10.5, 23, 6.5), (14, 25, 9.5), vest)
    return held(m, grip_y=m.to_model((8, 24, 8))[1])


# ================================================================================================
# 3차 (docs/plans/편의점_기획안.md §16 새 모델): the licences, the ID card, the 점장 and 야간 vests, the order tablet
# ================================================================================================

LICENSE_RIBBON = {"copper": ("copper", 2), "fish_silver": ("fish_silver", 3), "gold": ("gold", 2)}


def _face_gui(m, fill=0.9, rot=(90, 180, 0)):
    """An inventory look facing the viewer (a flat sheet lying face up, turned to stand upright, reading the right
    way round), fitted to its bounds."""
    lo, hi = _bounds(m)
    big = max(hi[i] - lo[i] for i in range(3)) / 16
    s = round(min(fill / big, 3.5), 3)
    off = [(lo[i] + hi[i]) / 2 - 8 for i in range(3)]
    r = presets._rot_xyz(list(rot))
    t = [round(-sum(r[i][j] * off[j] * s for j in range(3)), 3) for i in range(3)]
    m.display["gui"] = {"rotation": list(rot), "translation": t, "scale": [s] * 3}
    return m


def license(tier):
    """권리증: a 10 x 13 px certificate lying face up (store_art.license_face, reading from the north), a card-thick
    sheet with a metal rosette seal (the tier's bronze, silver or gold) on its bottom left and two ribbon tails off
    its bottom edge. Held up in the fist facing out (the picture to the front); the inventory shows its face."""
    from models import bus_hold as BH
    name, _border, rib, _stars = ART.LICENSE_TIERS[tier]
    rr, rs = LICENSE_RIBBON[rib]
    m = HModel(f"store_license_{tier}", floor=8, scale=2)
    face = _surface(f"store_license_{tier}_face", lambda t=tier: ART.license_face(t))
    m.group("sheet")
    m.box("sheet", (-2, 8, -5), (18, 8.5, 21), Paint("cream", 2, "paper", noise=0.02, rim=1.0))
    m.plate("face", (-2, 8.54, -5), (18, 8.54, 21), {"up": (face, [80, 104, 0, 0])})
    m.group("seal")
    # the ribbon tails under the seal, splayed, off the sheet's bottom edge (-z), V-cut ends
    tail = Paint(rr, rs, noise=0.02, rim=1.1, decal=lambda c: (rr, max(0, rs - 1)) if c.face == "up" and c.p[2] < -6.2 else None)
    m.box("tail", (11, 8.5, -8), (13, 8.75, -1), tail, rotation=("y", 22.5, (13, 8.6, -2)))
    m.box("tail", (13, 8.5, -8), (15, 8.75, -1), tail, rotation=("y", -22.5, (13, 8.6, -2)))
    hi_ = min(rs + 1, ladder_size(rr) - 1)

    def rosette(c):
        if c.face != "up":
            return (rr, max(0, rs - 1)) if math.floor(_ang_at(c, 13, -2) * 12 / math.pi) % 2 else None
        r = math.hypot(c.p[0] - 13, c.p[2] + 2)
        if r > 3.2:
            return (rr, max(0, rs - 1)) if math.floor(_ang_at(c, 13, -2) * 12 / math.pi) % 2 else (rr, hi_)
        return None
    cyl(m, "rosette", (13, -2), 8, 8.5, 9.25, Paint(rr, rs, "metal", noise=0.02, rim=1.2, decal=rosette))

    def boss(c):
        if c.face == "up":
            r = math.hypot(c.p[0] - 13, c.p[2] + 2)
            if r < 1.2:
                return (rr, hi_)
            if 1.8 < r < 2.4:
                return (rr, max(0, rs - 1))             # the struck ring
        return None
    cyl(m, "boss", (13, -2), 5, 9.25, 9.75, Paint(rr, rs, "metal", noise=0.02, rim=1.3, decal=boss))
    # held: by its bottom corner on the body's side, stood up facing out (top up, the picture to the front), the
    # sheet reaching out and up from the fist clear of the arm
    grip = m.to_model((-1, 8.25, -2))
    tr = BH.hold_transform(grip=grip, at=BH.fist(dx=0.5), turn=BH.turn(x=-90), scale=1.0, pose=BH.STANDING)
    BH.displays(m, tr, gui_fill=0.9)
    m.display["firstperson_righthand"] = {"rotation": [60, 180, 0], "translation": [1.5, 4.5, -1], "scale": [0.55, 0.55, 0.55]}
    m.display["firstperson_lefthand"] = {"rotation": [60, 180, 0], "translation": [1.5, 4.5, -1], "scale": [0.55, 0.55, 0.55]}
    m.display["fixed"] = {"rotation": [90, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}
    return _face_gui(m)


def ladder_size(r):
    from stsv_assets.palette import size
    return size(r)


def _ang_at(c, cx, cz):
    return math.atan2(c.p[2] - cz, c.p[0] - cx)


def id_card():
    """신분증: a 6.4 x 4 px ID card lying flat (store_art.id_card), reading from the north; its text area (the left
    of the picture, model +x) is left plain for the store's TextDisplay: model x 7.2..10.8, z 6.5..8.7, top y 8.25."""
    return _flat_pic("store_id_card", "store_id_card_tex", ART.id_card, 128, 80, 6.4, 4)


def _vest3_paint(kind, x0, x1, y0, y1):
    """The 점장 vest (deep green, gold piping up the V and round the hem, a gold band) or the 야간 vest (navy, silver
    reflective tape: two bands and braces up over the shoulders), seen from in front (north) or behind."""
    mid = (x0 + x1) / 2

    def decal(c):
        x, y = c.p[0], c.p[1]
        if c.face == "north":
            vn = y > y1 - 4 and abs(x - mid) < (y - (y1 - 4)) * 0.6 + 0.3
            if vn:
                return ("iron", 1)
            if abs(x - mid) < 0.5:
                return ("steel", 4)
            if kind == "manager" and y > y1 - 5 and abs(x - mid) < (y - (y1 - 4)) * 0.6 + 1.3:
                return ("gold", 2)                     # the piping up the V
            if kind == "night" and mid - 4 <= x < mid - 1.5 and y1 - 6 <= y < y1 - 4.5:
                return ("snow", 2)                     # the name tag
        if kind == "manager":
            if math.floor(y) == math.floor(y0 + 3):
                return ("gold", 2)
            if y < y0 + 1:
                return ("gold", 1)
            if c.face in ("north", "south") and (x < x0 + 0.5 or x > x1 - 0.5):
                return (GREEN, 0)
            return None
        if math.floor(y) in (math.floor(y0 + 3), math.floor(y0 + 5)):
            return ("snow", 2) if (math.floor(x) + math.floor(y)) % 3 else ("fish_silver", 4)
        if c.face in ("north", "south") and y > y0 + 5 and abs(abs(x - mid) - 4.5) < 0.75:
            return ("snow", 2) if math.floor(y) % 3 else ("fish_silver", 4)
        if y < y0 + 1:
            return ("indigo", 0)
        return None
    return decal


def vest3_item(kind):
    """점장 조끼 · 야간 조끼 items: the folded vest of store_vest in their colours; the 점장's with a gold 점장 tag."""
    m = HModel(f"store_vest_{kind}", floor=8, scale=2)
    base = Paint(GREEN, 1, "weave", noise=0.03) if kind == "manager" else Paint("indigo", 1, "weave", noise=0.03)
    vest = base(decal=_vest3_paint(kind, 1, 15, 8, 24))
    m.group("vest")
    m.box("vest", (1, 8, 6.5), (15, 23, 9.5), vest)
    m.box("strap", (2, 23, 6.5), (5.5, 25, 9.5), vest)
    m.box("strap", (10.5, 23, 6.5), (14, 25, 9.5), vest)
    if kind == "manager":
        tag = _surface("store_vest_manager_tag", ART.manager_tag)
        m.plate("tag", (2.25, 17, 6.49), (6.75, 19.25, 6.49), {"north": (tag, [0, 0, 48, 24])})
    return held(m, grip_y=m.to_model((8, 24, 8))[1])


def order_tablet():
    """발주 단말기, 1 cell, beside the counter: a weighted round foot, a steel post (its cable down the back), a head
    holding a POS tablet tilted back 45 degrees (a dark bezel, the 발주 app lit on its screen: store_art.tablet_screen,
    a green GS26 stripe on its back), and on the post's side a cradle with the handheld 발주기 (PDA) in it: a dark
    grey handset, a small lit screen, a keypad, a red scan window on top."""
    m = HModel("store_order_tablet", floor=0)
    m.group("stand")
    cyl(m, "foot", (8, 9), 10, 0, 1, Paint("iron", 2, "metal", rim=1.2))
    cyl(m, "foot_cap", (8, 9), 5, 1, 1.75, Paint("steel", 3, "metal", rim=1.2))
    m.box("post", (7.25, 1.75, 8.25), (8.75, 13.5, 9.75), Paint("steel", 4, "metal", axis="y", rim=1.1))
    m.box("cable", (7.6, 1, 9.75), (8.4, 13, 10.25), flat("iron", 1))
    m.box("mount", (6, 12.75, 8), (10, 14, 10), Paint("iron", 2, "metal", rim=1.2))
    m.group("tablet")
    rot = ("x", 45, (8, 13.5, 9))

    def back(c):
        if c.face == "south" and abs(c.p[1] - 20) < 0.75 and 4 <= c.p[0] < 12:
            return (GREEN, 2)
        if c.face == "north":
            if 13.5 <= c.p[1] < 14 and abs(c.p[0] - 8) < 0.5:
                return ("iron", 3)                     # the home key under the screen
        return None
    m.box("tablet", (2, 13, 8), (14, 22.5, 9), Paint("iron", 1, "lacquer", noise=0.01, rim=1.3, decal=back), rotation=rot)
    scr = _surface("store_tablet_screen", ART.tablet_screen)
    m.plate("screen", (2.5, 14, 7.99), (13.5, 22, 7.99), {"north": (scr, [0, 0, 88, 64])}, glow=True, rotation=rot)
    m.group("pda")
    m.box("arm", (8.75, 6, 8.5), (12, 7, 9.5), flat("steel", 4, rim=1.1))
    m.box("cradle", (12, 5, 7.5), (15.5, 8, 10.5), Paint("iron", 2, "lacquer", rim=1.2,
                                                       decal=lambda c: ("raw", (90, 230, 140, 255)) if c.face == "north" and 6 <= c.p[1] < 6.5 and 14.5 <= c.p[0] < 15 else None))

    def pda(c):
        x, y = c.p[0], c.p[1]
        if c.face == "north" and 8 <= y < 10.5 and 13 <= x < 14.5 and (c.i[0] + c.i[1]) % 2 == 0:
            return ("car_white", 1)                    # the keypad
        if c.face == "up" and c.p[2] < 9 and 13 <= x < 14.5:
            return ("raw", (220, 50, 46, 255))         # the scan window
        if c.face in ("east", "west") and 8 <= y < 11:
            return (GREEN, 2)                          # a green grip on each side
        return None
    m.box("pda", (12.5, 7, 8), (15, 13.5, 10), Paint("iron", 3, "lacquer", noise=0.02, rim=1.2, decal=pda))
    m.box("pda_screen", (13, 10.75, 7.9), (14.5, 13, 8), Paint("iron", 1, glow=True, light=False, noise=0,
                                                              decal=lambda c: ("raw", (150, 230, 170, 255)) if c.face == "north" and c.p[1] >= 12.25 else ("raw", (220, 250, 228, 255)) if c.face == "north" else None))
    return gui(m)


LICENSES = tuple(ART.LICENSE_TIERS)

# ================================================================================================

GOODS = {
    "samgak_tuna": lambda: samgak("samgak_tuna", ("sapphire", 2)),
    "samgak_bibim": lambda: samgak("samgak_bibim", ("pepper", 2)),
    "samgak_bulgogi": lambda: samgak("samgak_bulgogi", ("doenjang", 2)),
    "dosirak": dosirak,
    "hotbar": hotbar,
    "egg": egg,
    "ramen_red": lambda: ramen("ramen_red", ("pepper", 2), ("iron", 1), ("iron", 1)),
    "ramen_mild": lambda: ramen("ramen_mild", ("fox", 3), (GREEN, 2), ("snow", 2)),
    "ramen_big": lambda: ramen("ramen_big", ("wine", 3), ("pepper", 3), ("iron", 1)),
    "banana_milk": banana_milk,
    "coffee_can": lambda: can("coffee_can", ("doenjang", 2), (5, 7, ("gold", 2)), d=5, h=10),
    "coffee_cup": coffee_cup,
    "soda": lambda: bottle("soda", ("cucumber", 3), (("snow", 2), (GREEN, 2)), (GREEN, 2)),
    "ion_drink": lambda: bottle("ion_drink", ("ice", 3), (("sapphire", 2), ("snow", 2)), ("sapphire", 3)),
    "water": lambda: bottle("water", ("ice", 2), (("sapphire", 3), ("snow", 2)), ("snow", 2)),
    "soju": lambda: bottle("soju", ("bottle_green", 2), (("snow", 2), (GREEN, 3)), (GREEN, 3), d=6, h=9,
                           neck=(5, 4, 3), cap_h=1.5, label_band=(2, 6)),
    "beer": lambda: can("beer", ("gold", 2), (6, 8, ("sapphire", 1)), d=6, h=10),
    "hangover": hangover,
    "chips": lambda: bag_pack("chips", ("corn", 2), _chips_pic),
    "shrimp": lambda: bag_pack("shrimp", ("snow", 2), _shrimp_pic, size=(9, 11)),
    "choco_snack": lambda: box_pack("choco_snack", (12, 9, 3), ("pepper", 2), _choco_pic),
    "jelly": lambda: bag_pack("jelly", ("crystal", 3), _jelly_pic, size=(8, 10)),
    "chocolate": lambda: box_pack("chocolate", (11, 5, 1.5), ("doenjang", 1), _chocolate_pic),
    "candy": lambda: bag_pack("candy", ("pink", 2), _candy_pic, size=(9, 10)),
    "gum": lambda: box_pack("gum", (8, 3, 4), (GREEN, 3), _gum_pic),
    "ice_bar": ice_bar,
    "ice_cone": ice_cone,
    "umbrella": umbrella,
    "battery": battery,
    "tissue": tissue,
    "bandage": lambda: box_pack("bandage", (9, 6, 3), ("snow", 2), _bandage_pic),
    "lottery": lottery,
    "cigarette": lambda: box_pack("cigarette", (5, 9, 2.5), ("snow", 2), _cig_pic),
}

SHELF_KINDS = ("snack", "noodle", "daily", "meal")
STATES = ("full", "half", "empty")

FURNITURE = {"store_counter": counter, "store_cig_shelf": cig_shelf}
for _c in SHELF_KINDS:
    for _s in STATES:
        FURNITURE[f"store_shelf_{_c}_{_s}"] = (lambda c=_c, s=_s: shelf(c, s))
for _s in STATES:
    FURNITURE[f"store_fridge_{_s}"] = (lambda s=_s: fridge(s))
FURNITURE.update({
    "store_freezer": freezer, "store_microwave": microwave, "store_kettle": kettle, "store_eat_bar": eat_bar,
    "store_parasol": parasol, "store_hanger": hanger, "store_waste": waste, "store_mop": mop, "store_sign": sign,
})

DISPLAY = {f"store_item_{k}": fn for k, fn in GOODS.items()}
for _k in BOX_KIND:
    DISPLAY[f"store_box_{_k}"] = (lambda k=_k: delivery_box(k))
DISPLAY.update({"store_bag": shopping_bag, "store_spill": spill, "store_trash_cup": trash_cup, "store_card": card})
for _n in (1000, 5000, 10000, 50000):
    DISPLAY[f"store_cash_{_n}"] = (lambda n=_n: cash(n))
for _n in (100, 500):
    DISPLAY[f"store_coin_{_n}"] = (lambda n=_n: coin(n))
for _k in ART.BUTTON_WORDS:
    DISPLAY[f"store_btn_{_k}"] = (lambda k=_k: button(f"store_btn_{k}", lambda k=k: ART.button(k), f"store_btn_{k}_face"))
for _n in ART.CHANGE:
    DISPLAY[f"store_btn_change_{_n}"] = (lambda n=_n: button(f"store_btn_change_{n}", lambda n=n: ART.change_button(n),
                                                              f"store_btn_change_{n}_face"))
DISPLAY["store_scanner"] = scanner
DISPLAY["store_vest"] = vest_item

# 3차 (§16): the order tablet is furniture (1 cell); the rest are display / held models. The licences' items live in
# items/store_extra.yml (their model: lines point here); the others are items of items/store.yml.
FURNITURE3 = {"store_order_tablet": order_tablet}
DISPLAY3 = {f"store_license_{_t}": (lambda t=_t: license(t)) for _t in LICENSES}
DISPLAY3.update({"store_id_card": id_card, "store_vest_manager": lambda: vest3_item("manager"),
                 "store_vest_night": lambda: vest3_item("night")})
ALL3 = {**FURNITURE3, **DISPLAY3}

ALL = {**FURNITURE, **DISPLAY, **ALL3}
