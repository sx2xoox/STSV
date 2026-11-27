"""
편의점 §17 "행동을 눈으로 보이게" (docs/plans/편의점_기획안.md §17, 주인: "대걸레를 들었으면 들고있는걸 보여주고, 닦으면
닦는것도 보여주고 … 다 그냥 텍스트로 끝내네"): the things a worker holds for a moment, the furniture's states and the
props that show what is going on. Every id is an item of items/store.yml.

Held (store_held_*, the worker's main hand for a moment): each has its own display transforms, solved for a standing
player (models/bus_hold: the arm 18 degrees forward with an item) in third person and placed by hand in first person
(the camera frame: x right, y up, -z ahead; the right hand's item sits at (8.96, -8.32, -11.52) px before its
transform, vanilla's arm transform):

  store_held_mop          대걸레: a string mop, white cotton strands in a yellow clamp, a blue aluminium handle with a
                          black grip and a hanging loop. Third person: held diagonally like a real mop, the hand a
                          little above the middle of the handle, the head on the floor ahead of the feet, the handle
                          slanting up past the right shoulder; first person: the handle from the bottom right of the
                          view down and ahead to the head on the floor.
  store_held_box_<snack|noodle|daily|meal|drink>
                          a delivery box (store_box_<kind>'s look) hugged in front of the chest: third person the box
                          in front of the body's middle at chest height, the right hand on its side (both arms round
                          it, as far as one hand can show it); first person big, low in the middle of the view, its
                          label to the player.
  store_held_jug          온수기 물통: a blue-capped 5 L water jug carried by its moulded handle, hanging from the fist.
  store_held_waste_bag    폐기 봉지: a tied clear bag of expired kimbap and lunch boxes, a red 폐기 tape, hanging
                          from the fist by its knot.
  store_held_tray         치운 쟁반: a brown tray held level at the waist, two empty cup ramen cups, a paper cup,
                          chopsticks and a crumpled napkin on it.
  store_held_pda          발주기: the handheld order terminal, its lit screen tilted up to the player's face.
  store_held_id           받아 든 신분증: the customer's ID card held up facing the player (its print filled in).

Furniture states (the same cells and anchor as their base, only the model swapped; models/store.py):
  store_microwave_<open|run|done>, store_kettle_<low|empty>, store_order_tablet_<snack|noodle|daily|meal|drink|sent>,
  store_hanger_empty.

Props (display models: bottom on the display's position, centred, front north):
  store_spill_2 · store_spill_3   the spill wiped smaller (store_spill is the first, biggest stage, the cup beside it):
                                  2 = a smaller puddle with the mop's arcs through it, 3 = a damp sheen and streaks.
  store_wet_sign                  노란 "미끄럼 주의" A-frame sign (two panels, the slipping figure, the words).
  store_box_<kind>_open           the delivery box with its flaps folded out and the goods packed inside (store.py).
  store_trash_cup                 다 먹은 컵라면, again: the red cup ramen of store_item_ramen_red emptied: broth and a
                                  few noodles left, the lid peeled back and standing up at the back, chopsticks across.
"""

import math

import numpy as np

from stsv_assets.bake import Paint, cyl, h3, vnoise, voxels

from models import store as S
from models import store_goods as SG
import store_goods_art as GA

GREEN = S.GREEN
FP_BASE = np.array([8.96, -8.32, -11.52])      # the right hand's item point in first person (px, camera frame)


def flat(ramp, step, **kw):
    return Paint(ramp, step, noise=0, **kw)


def _bh():
    from models import bus_hold as BH
    return BH


def _rot_from(y_to, z_to=None, x_hint=(1.0, 0.0, 0.0)):
    """A rotation taking the model's +y to y_to (and its -z, its front, as near z_to as can be, else its +x as near
    x_hint): 3 x 3."""
    y = np.array(y_to, float)
    y /= np.linalg.norm(y)
    if z_to is not None:
        f = np.array(z_to, float)                   # where the front (-z) should look
        z = -f
        z = z - y * (z @ y)
        z /= np.linalg.norm(z)
        x = np.cross(y, z)
    else:
        x = np.array(x_hint, float)
        x = x - y * (x @ y)
        x /= np.linalg.norm(x)
        z = np.cross(x, y)
    return np.column_stack([x, y, z])


def first_person(m, grip, hand, r, scale=1.0):
    """firstperson_righthand putting model point grip at camera point hand (px), the model turned by r (3 x 3); the
    left hand mirrored."""
    BH = _bh()
    g = np.array(grip, float) - 8
    t = np.array(hand, float) - FP_BASE - r @ (scale * g)
    rot = BH.euler_xyz(r)
    d = {"rotation": rot, "translation": [round(float(v), 3) + 0.0 for v in t], "scale": [scale] * 3}
    for v in d["translation"]:
        assert -80 <= v <= 80, (m.name, d)
    m.display["firstperson_righthand"] = d
    m.display["firstperson_lefthand"] = {"rotation": [rot[0], -rot[1], -rot[2]], "translation": [-d["translation"][0]] + d["translation"][1:],
                                         "scale": [scale] * 3}
    return m


def third_person(m, grip, at, turn, frame="arm", gui_fill=0.9):
    BH = _bh()
    tr = BH.hold_transform(grip=grip, at=at, turn=turn, scale=1.0, frame=frame, pose=BH.STANDING)
    BH.displays(m, tr, gui_fill=gui_fill)
    return m


# ================================================================================================
# 대걸레
# ================================================================================================

MOP_GRIP_PX = 2.0          # the hand's place up the handle (px above the model's middle)


def held_mop():
    """대걸레, drawn upright at twice its size (1 px = 2 texels): the strands' foot at -14 px, the clamp, the handle to
    +13 px with its grip and loop."""
    m = S.HModel("store_held_mop", floor=None, scale=2)

    def d(px):
        return 8 + 2 * px

    def B(name, a, b, paint, **kw):
        m.box(name, (d(a[0]), d(a[1]), d(a[2])), (d(b[0]), d(b[1]), d(b[2])), paint, **kw)
    m.group("head")

    def strands(c):
        k = math.floor(c.p[0] + c.p[2] * 0.5)
        if c.face in S.SIDES and k % 2:
            return ("car_white", 1)
        if c.face in S.SIDES and c.p[1] < d(-13.25) and k % 3 == 0:
            return ("stone", 3)                                    # the wet, greyed tips
        return None
    wet = Paint("car_white", 2, noise=0.08, rim=1.1, decal=strands)
    B("strands", (-3.25, -14, -1.5), (3.25, -11.5, 1.5), wet)
    B("strands", (-3.75, -13.75, -1), (3.75, -12.5, 1), wet)
    for (x, z, ang) in ((-4.5, 0.6, 22.5), (4.25, -0.4, -22.5), (-1.5, -2.2, 0), (2, 2.1, 0)):
        B("strand", (x - 0.4, -14, z - 0.4), (x + 0.4, -12, z + 0.4), wet, rotation=("z", ang, (d(x), d(-12), d(z))))
    B("clamp", (-3.5, -11.5, -1.25), (3.5, -10.5, 1.25), flat("corn", 2, rim=1.3))
    B("clamp_screw", (-0.75, -10.5, -0.75), (0.75, -9.5, 0.75), flat("corn", 1, rim=1.2))
    m.group("handle")
    B("handle", (-0.5, -9.5, -0.5), (0.5, 10, 0.5), Paint("signal_blue", 2, "metal", noise=0, rim=1.4))
    B("grip", (-0.65, 10, -0.65), (0.65, 13, 0.65), Paint("rubber", 1, "speckle", noise=0.2, rim=1.2))
    B("cap", (-0.5, 13, -0.5), (0.5, 13.5, 0.5), flat("signal_blue", 1))
    B("loop", (-0.25, 13.5, -1.25), (0.25, 14.5, 1.25), flat("rubber", 2))
    # third person: the head on the floor ahead (body frame (3, -23.5, -11)), the hand on the handle, the handle on
    # through the fist and up past the shoulder (the hand's place solved so the strands reach the floor)
    grip = (8, 8 + MOP_GRIP_PX, 8)
    third_person(m, grip, _bh().fist(), _bh().turn(x=32, z=-10.7))
    # first person: the hand low on the right, the handle down and ahead to the head on the floor
    hand = np.array([6.5, -6.0, -12.0])
    head = np.array([-1.5, -17.0, -30.0])
    r = _rot_from(hand - head, x_hint=(1, 0, 0.2))
    first_person(m, grip, hand, r, scale=1.0)
    m.display["ground"] = {"rotation": [90, 0, 0], "translation": [0, 1, 0], "scale": [0.45, 0.45, 0.45]}
    return m


# ================================================================================================
# 상자 (hugged), 물통, 폐기 봉지, 쟁반, 발주기, 신분증
# ================================================================================================

def held_box(kind):
    """The delivery box store_box_<kind>, hugged: third person in front of the body's middle at chest height (its
    back against the chest), its label forward; first person low in the middle of the view, its label to the player,
    its top showing."""
    m = S.delivery_box(kind, name=f"store_held_box_{kind}")
    centre = (8, 12, 8)
    third_person(m, centre, (0.5, -9.5, -7.25), _bh().turn(), frame="body", gui_fill=0.8)
    r = _rot_from((0, 0.96, 0.28), z_to=(0, -0.28, 0.96))          # turned to the player, tipped to show its top
    first_person(m, centre, (1.5, -7.5, -16.0), r, scale=1.0)
    return m


def held_jug():
    """온수기 물통: a 5 L jug of pale blue plastic (water three quarters up it, darker), a moulded carry handle on its
    shoulder, a blue cap, a white label with a blue drop."""
    m = S.HModel("store_held_jug", floor=None, scale=2)

    def d(px):
        return 8 + 2 * px

    def B(name, a, b, paint, **kw):
        m.box(name, (d(a[0]), d(a[1]), d(a[2])), (d(b[0]), d(b[1]), d(b[2])), paint, **kw)

    def body(c):
        y = (c.p[1] - 8) / 2
        x = (c.p[0] - 8) / 2
        if c.face == "north" and -2 <= x < 2 and -6 <= y < -2.5:
            if -1 <= x < 0.5 and -5 <= y < -3.5:
                return ("water", 2)
            return ("snow", 2)                                      # the label
        if c.face in S.SIDES and y < -1.5:
            return ("water", 3) if (math.floor(c.p[1]) % 6) else ("water", 2)
        if c.face in S.SIDES and abs(y + 1.25) < 0.3:
            return ("water", 4)                                     # the water's surface
        return None
    plastic = Paint("ice", 3, noise=0, rim=1.3, decal=body)
    B("jug", (-3, -9, -2.5), (3, -0.5, 2.5), plastic)
    B("jug", (-2.5, -9.25, -2), (2.5, 0, 2), plastic)
    B("shoulder", (-2.25, -0.5, -1.75), (2.25, 0.75, 1.75), Paint("ice", 3, noise=0, rim=1.3))
    B("neck", (-2.25, 0.75, -2), (-0.25, 1.75, 0), flat("ice", 3, rim=1.2))
    B("cap", (-2.4, 1.75, -2.15), (-0.1, 2.75, 0.15), Paint("sapphire", 2, noise=0, rim=1.3,
      decal=lambda c: ("sapphire", 1) if c.face in S.SIDES and math.floor(c.p[0] + c.p[2]) % 2 else None))
    grip = Paint("ice", 2, noise=0, rim=1.3)
    B("handle_post", (0.25, 0.75, -0.5), (1.25, 3.25, 0.5), grip)
    B("handle_post", (2, 0.75, -0.5), (2.75, 3.25, 0.5), grip)
    B("handle", (0.25, 3.25, -0.5), (2.75, 4, 0.5), grip)
    g = (8 + 1.5, 8 + 3.6, 8)
    third_person(m, g, _bh().fist(0, 0.5), _bh().turn(y=90))
    first_person(m, g, (7.0, -1.0, -14.5), _rot_from((0, 1, 0), z_to=(-0.7, 0, -0.7)), scale=0.9)
    return m


def held_waste_bag():
    """폐기 봉지: a tied, half-clear grey bag bulging with expired triangle kimbap and lunch boxes (their colours
    through the film), a red 폐기 tape round it, two knot ears at the top."""
    m = S.HModel("store_held_waste_bag", floor=None, scale=2)
    cells = {}
    for x in range(-8, 8):
        for y in range(-20, 2):
            for z in range(-6, 6):
                px, py, pz = x + 0.5, y + 0.5, z + 0.5
                if (px / 7.5) ** 2 + ((py + 9) / 10.5) ** 2 + (pz / 5.5) ** 2 <= 1 and py < 0:
                    cells[(8 + x, 8 + y, 8 + z)] = "bag"
    for y in range(0, 4):
        for x in range(-1 - (y > 1), 1 + (y > 1)):
            for z in (-1, 0):
                cells[(8 + x, 8 + y, 8 + z)] = "bag"

    def film(c):
        x, y, z = c.p[0] - 8, c.p[1] - 8, c.p[2] - 8
        if abs(y + 7) < 1 and c.face in S.SIDES:
            return ("pepper", 2) if math.floor(x + z) % 5 else ("snow", 2)          # the 폐기 tape
        n = vnoise(x + 20, y + 20, z + 20, 6.0, 4)
        if y < -3 and c.face != "up":
            if n < 0.28:
                return ("stone", 3)                                  # the goods' shapes through the film
            if n > 0.74:
                return ("corn", 3) if n < 0.8 else ("stone", 3)
        if (math.floor(x * 0.5 + y * 0.8) % 9) == 0 and c.face in S.SIDES:
            return ("snow", 2)                                       # creases catching the light
        return None
    voxels(m, "bag", cells, lambda _l: Paint("fish_silver", 4, noise=0, rim=1.4, decal=film))
    ear = Paint("fish_silver", 4, noise=0, rim=1.3)
    m.box("ear", (5, 12, 7), (8, 13, 8), ear, rotation=("z", 45, (8, 12, 7.5)))
    m.box("ear", (8, 12, 7), (11, 13, 8), ear, rotation=("z", -45, (8, 12, 7.5)))
    g = (8, 8 + 1.0, 8)
    third_person(m, g, _bh().fist(0, 0.5), _bh().turn(y=90))
    first_person(m, g, (6.5, -0.5, -14.5), _rot_from((0, 1, 0), z_to=(-0.6, 0, -0.8)), scale=0.9)
    return m


def held_tray():
    """치운 쟁반: a brown plastic tray (a raised lip), two empty cup ramen cups (red, orange) with their lids hanging, a
    paper cup, a pair of chopsticks across and a crumpled napkin."""
    m = S.HModel("store_held_tray", floor=None, scale=2)

    def d(px):
        return 8 + 2 * px

    def B(name, a, b, paint, **kw):
        m.box(name, (d(a[0]), d(a[1]), d(a[2])), (d(b[0]), d(b[1]), d(b[2])), paint, **kw)
    tray = Paint("doenjang", 2, "lacquer", noise=0, rim=1.3)
    B("tray", (-5.5, 0, -3.75), (5.5, 0.5, 3.75), tray)
    B("lip", (-5.5, 0.5, -3.75), (5.5, 1, -3.25), tray)
    B("lip", (-5.5, 0.5, 3.25), (5.5, 1, 3.75), tray)
    B("lip", (-5.5, 0.5, -3.25), (-5, 1, 3.25), tray)
    B("lip", (5, 0.5, -3.25), (5.5, 1, 3.25), tray)
    for (cx, cz, col) in ((-2.5, -0.5, ("pepper", 2)), (1.25, 1.25, ("fox", 3))):
        def side(c, col=col):
            if c.face == "up":
                rr = math.hypot(c.p[0] - d(cx), c.p[2] - d(cz))
                return ("snow", 2) if rr > 3 else ("doenjang", 1) if h3(c.i[0], c.i[2], 0, 3) > 0.2 else ("corn", 3)
            return ("snow", 2) if abs(c.p[1] - d(2)) < 1.25 and c.p[2] < d(cz) else None
        cyl(m, "cup", (d(cx), d(cz)), 7, d(0.5), d(3.5), Paint(col[0], col[1], noise=0, decal=side))
        m.box("lid", (d(cx - 1.5), d(3.5), d(cz + 1.6)), (d(cx + 1.5), d(5.5), d(cz + 1.75)), flat("fish_silver", 4, rim=1.2),
              rotation=("x", 22.5, (d(cx), d(3.5), d(cz + 1.7))))
    cyl(m, "paper_cup", (d(3.5), d(-1.75)), 4, d(0.5), d(3), Paint("snow", 2, noise=0,
        decal=lambda c: ("bean", 1) if c.face == "up" else (GREEN, 2) if abs(c.p[1] - d(1.75)) < 1 else None))
    B("chopstick", (-4.5, 1, -2.75), (2, 1.25, -2.5), flat("hinoki", 3), rotation=("y", -22.5, (d(-1), d(1), d(-2.6))))
    B("chopstick", (-4.5, 1, -2.25), (2, 1.25, -2), flat("hinoki", 3), rotation=("y", -22.5, (d(-1), d(1), d(-2.1))))
    B("napkin", (2.5, 0.5, 1.5), (4.5, 1.5, 3), flat("snow", 2, rim=1.3), rotation=("y", 22.5, (d(3.5), d(1), d(2.25))))
    g = (d(5), d(0.25), d(2.75))
    g = (8 + (g[0] - 8) / 2, 8 + (g[1] - 8) / 2, 8 + (g[2] - 8) / 2)
    third_person(m, g, _bh().fist(0, 1.0), _bh().turn())
    first_person(m, (8, 8.25, 8), (0.5, -7.0, -15.0), _rot_from((0, 0.9, 0.44), z_to=(0, -0.44, 0.9)), scale=1.0)
    return m


def held_pda():
    """발주기: the handheld order terminal (the one in store_order_tablet's cradle): a dark grey handset, its screen
    lit with the order list (a green header, lines, a send key), a keypad, green grips down its sides, a red scan
    window on its top edge."""
    m = S.HModel("store_held_pda", floor=None, scale=4)

    def d(px):
        return 8 + 4 * px

    def B(name, a, b, paint, **kw):
        m.box(name, (d(a[0]), d(a[1]), d(a[2])), (d(b[0]), d(b[1]), d(b[2])), paint, **kw)

    def body(c):
        x, y = (c.p[0] - 8) / 4, (c.p[1] - 8) / 4
        if c.face == "north" and -1 <= x < 1 and -3 <= y < -0.75:
            return ("car_white", 2) if (c.i[0] + c.i[1]) % 3 else ("iron", 3)          # the keypad
        if c.face == "north" and -0.5 <= x < 0.5 and -0.6 <= y < -0.25:
            return (GREEN, 3)                                                          # the scan key
        if c.face in ("east", "west") and -2.5 <= y < 0:
            return (GREEN, 2)
        if c.face == "up" and c.p[2] < 8:
            return ("pepper", 3)
        return None
    B("handset", (-1.5, -3.5, -0.5), (1.5, 3, 0.5), Paint("iron", 2, "lacquer", noise=0, rim=1.3, decal=body))
    B("head", (-1.25, 3, -0.6), (1.25, 3.5, 0.6), Paint("iron", 1, noise=0, rim=1.3,
      decal=lambda c: ("pepper", 3) if c.face in ("up", "north") else None))
    scr = S._surface("store_pda_screen", lambda: GA.tablet_order("drink").resize((44, 32), 0))
    m.plate("screen", (d(-1.25), d(0), d(-0.52)), (d(1.25), d(2.75), d(-0.52)), {"north": (scr, [0, 0, 44, 32])}, glow=True, one_sided=True)
    g = (8, 8 - 2.0, 8)
    third_person(m, g, _bh().fist(0, 0.5, -1.75), _bh().turn(x=25, y=180))
    first_person(m, g, (4.0, -6.5, -11.5), _rot_from((0, 0.85, -0.53), z_to=(-0.15, 0.53, 0.85)), scale=1.0)
    return m


def held_id():
    """받아 든 신분증: the ID card stood up, its face (its print filled in) to the player, a plain back to the
    customer."""
    tex = S._surface("store_held_id_face", GA.id_held_face)
    back = S._surface("store_held_id_back", GA.id_back)
    m = S.Model("store_held_id")
    m.group("card")
    w, h = 6.4, 4
    edge = [30, 18, 31, 19]
    m.box("card", (8 - w / 2, 8 - h / 2, 7.9), (8 + w / 2, 8 + h / 2, 8.1), back,
          faces={"south": tex, "north": back, "up": back, "down": back, "east": back, "west": back},
          uv={"south": [0, 0, 128, 80], "north": [64, 0, 0, 40], "up": edge, "down": edge, "east": edge, "west": edge})
    g = (8, 8 - h / 2 + 0.4, 8)
    # third person: pinched up in front of the knuckles (clear of the forearm), its face to the player, its top
    # tipped a little away so it reads from over the shoulder
    third_person(m, g, _bh().fist(0, 0, -3.25), _bh().turn(x=-20))
    first_person(m, g, (4.5, -5.5, -11.5), _rot_from((0, 0.94, -0.34), z_to=(0, -0.34, -0.94)), scale=1.0)
    return m


# ================================================================================================
# 엎지른 음료 2 · 3, 미끄럼 주의, 다 먹은 컵라면
# ================================================================================================

def spill(stage):
    """The spill wiped smaller (stage 1 is store.py's store_spill, the cup beside it): 2 = the cup picked up, a smaller
    puddle with the mop's arcs dragged through it and a wet sheen round it; 3 = only a few streaks of cola in the
    mop's arcs and the floor's wet shine."""
    m = S.G(f"store_spill_{stage}")
    rad = {2: 6.0, 3: 4.0}[stage]
    clear = ("raw", (0, 0, 0, 0))

    def puddle(c):
        x, z = c.p[0] - 8, c.p[2] - 8
        v = vnoise(x + 30 + stage * 7, 0, z + 30, 3.0, 5 + stage)
        r = math.hypot(x / 1.3, z)
        a = math.hypot(x + 10, z + 7)                 # the mop swung from the front left
        arc = (a * 1.2) % 3.0
        if stage == 3:
            if r + (v - 0.5) * 4 > rad * 1.7:
                return clear
            if arc < 0.6 and r < rad * 1.5:
                return ("doenjang", 2) if v > 0.45 else ("bean", 2)      # the last streaks of cola
            if arc > 2.2 and r < rad * 1.3 and (math.floor(x) + math.floor(z)) % 2 == 0:
                return ("ice", 3)                                        # the wet shine
            return clear
        if r + (v - 0.5) * 6 > rad * 1.45:
            return clear
        if r + (v - 0.5) * 6 > rad:
            if arc < 0.8:
                return ("doenjang", 2)                                   # dragged out in arcs
            return ("ice", 3) if (math.floor(x) + math.floor(z)) % 2 == 0 else clear
        if h3(c.i[0], c.i[2], 0, 2) < 0.08 or (abs(x + z * 0.5 - 2) < 0.6 and r < 4):
            return ("doenjang", 3)
        return ("doenjang", 2) if arc < 0.7 else ("doenjang", 1)
    m.box("puddle", (-6, 8.1, -4), (22, 8.1, 20), Paint("doenjang", 1, noise=0, light=False, decal=puddle))
    return S.gui(m, fill=0.8)


def wet_sign():
    """미끄럼 주의 A-frame (a real 10 px wide, 12 px tall sign, about 0.75 block): two safety-yellow panels hinged at
    the top (their picture on both outer faces), a black handle bar with its slot, black feet."""
    m = S.HModel("store_wet_sign", floor=8)
    face = S._surface("store_wet_sign_face", GA.wet_sign_face)
    yellow = Paint("corn", 2, "lacquer", noise=0, rim=1.2)
    top = 20
    for (z0, z1, ang, f) in ((7.5, 8, 22.5, "north"), (8, 8.5, -22.5, "south")):
        rot = ("x", ang, (8, top, 8))
        m.box("panel", (3, 7, z0), (13, top, z1), yellow, rotation=rot, faces={f: None})
        m.elements[-1]["open"] = (f,)
        zf = z0 - 0.01 if f == "north" else z1 + 0.01
        m.plate("face", (3, 7, zf), (13, top, zf), {f: (face, [0, 0, 60, 84])}, rotation=rot, one_sided=True)
        foot_z = 8 - 4.6 if f == "north" else 8 + 4.6
        m.box("foot", (3.5, 8, foot_z - 0.75), (5, 8.75, foot_z + 0.75), flat("iron", 1))
        m.box("foot", (11, 8, foot_z - 0.75), (12.5, 8.75, foot_z + 0.75), flat("iron", 1))
    m.box("handle", (5, top, 7.25), (11, top + 1.5, 8.75), Paint("iron", 1, noise=0, rim=1.3,
          decal=lambda c: ("iron", 0) if c.face in ("north", "south") and 6.5 <= c.p[0] < 9.5 and c.p[1] < top + 1 else None))
    return S.gui(m, fill=0.8)


def trash_cup():
    """다 먹은 컵라면: store_item_ramen_red's cup emptied: a little broth with noodle bits and green onion inside its
    white rim, the lid peeled back and standing up at the back (its foil inside to the front), wooden chopsticks
    across the top, a drip of soup down the front."""
    m = SG.G4("store_trash_cup")
    tiers = SG.RAMEN["ramen_red"]
    h = tiers[-1][2]
    r_top = tiers[-1][0]
    tex, W = SG.wrap_pic("store_good_ramen_red_wrap", lambda w, hh: GA.ramen_wrap("ramen_red", w, hh), r_top, h)
    pic = SG.sampler(S.T.SURFACES[tex](), x0=W / 2, y_top=h)

    def side(r):
        def fn(U, y, k):
            if abs(U - 2) < 0.6 and h - 5 < y < h and k == 0:
                return ("doenjang", 1)                                   # a drip of soup
            return pic(U * r_top / r, y)
        return fn

    def broth(x, z):
        rr = math.hypot(x, z)
        if rr > r_top - 1.25:
            return ("snow", 2)                                           # the cup's white rim
        if rr > r_top - 2:
            return ("car_white", 1)
        n = h3(math.floor(x * 2), math.floor(z * 2), 0, 7)
        if n < 0.12:
            return ("corn", 3)                                           # bits of noodle
        if n < 0.16:
            return ("leaf", 3)
        return ("tomato", 1) if (math.floor(x) + math.floor(z)) % 5 else ("tomato", 2)
    for (r, a, b) in tiers:
        SG.octa(m, "cup", r, a, b, Paint("pepper", 2, noise=0), side=side(r),
                top=broth if b == h else (lambda x, z: ("pepper", 3)))
    lid = S._surface("store_good_ramen_red_lid", lambda: GA.ramen_lid("ramen_red", round(2 * (r_top + 0.5))))
    w, _ = S.T.size_of(lid)
    dd = w / 2
    foil = S._surface("store_trash_cup_foil", lambda: _foil(w))
    rot = ("x", 45, SG.P(0, h, r_top - 0.5))
    m.plate("lid", SG.P(-dd / 2, h, r_top - 0.5), SG.P(dd / 2, h + dd, r_top - 0.5),
            {"south": (lid, [0, 0, w, w]), "north": (foil, [0, 0, w, w])}, rotation=rot)
    chop = Paint("hinoki", 3, noise=0, rim=1.2)
    for dz in (-1.5, 0.5):
        m.box("chopstick", SG.P(-13, h, dz), SG.P(12, h + 1, dz + 1), chop, rotation=("y", 22.5, SG.P(0, h, 0)))
    return S.gui(m, fill=0.8)


def _foil(w):
    from store_art import Pic
    P = Pic(w, w)
    c = w / 2
    P.ell(c, c, c, c, ("fish_silver", 4))
    P.ell(c, c, c - 2, c - 2, ("fish_silver", 3))
    for k in range(6):
        P.p(c - 6 + k * 2, c + 2 + (k % 2), ("tomato", 2))                # a few splashes of soup on the foil
    return P.im


# ================================================================================================

BOX_KINDS = tuple(S.BOX_KIND)
HELD = {"store_held_mop": held_mop, "store_held_jug": held_jug, "store_held_waste_bag": held_waste_bag,
        "store_held_tray": held_tray, "store_held_pda": held_pda, "store_held_id": held_id}
for _k in BOX_KINDS:
    HELD[f"store_held_box_{_k}"] = (lambda k=_k: held_box(k))

ORDER_SCREENS = ("snack", "noodle", "daily", "meal", "drink", "sent")
FURNITURE4 = {f"store_microwave_{_s}": (lambda s=_s: S.microwave(s)) for _s in ("open", "run", "done")}
FURNITURE4.update({f"store_kettle_{_l}": (lambda lv=_l: S.kettle(lv)) for _l in ("low", "empty")})
FURNITURE4.update({f"store_order_tablet_{_g}": (lambda g=_g: S.order_tablet(g)) for _g in ORDER_SCREENS})
FURNITURE4["store_hanger_empty"] = lambda: S.hanger(empty=True)

PROPS4 = {"store_spill_2": lambda: spill(2), "store_spill_3": lambda: spill(3), "store_wet_sign": wet_sign,
          "store_trash_cup": trash_cup}
for _k in BOX_KINDS:
    PROPS4[f"store_box_{_k}_open"] = (lambda k=_k: S.delivery_box(k, opened=True))

ALL4 = {**HELD, **FURNITURE4, **PROPS4}
