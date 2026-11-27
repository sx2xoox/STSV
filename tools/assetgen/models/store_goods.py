"""
편의점 상품 33종, 다시 깎음 (docs/plans/편의점_기획안.md §17: 주인 "올려놓는 상품들 모델링도 수정 좀 하고"): the goods the
customers put on the counter mat and carry (store_item_<id>, ids from §15), each an economy model of its own.

Display convention (unchanged, items/store.yml): an ItemDisplay with no transform shows the model's (8, 8, 8) at its
position; the good's BOTTOM is on that point (model y 8), centred on it, its front (the printed face) to the north.
Sizes stay about the old ones so the counter mat layout still fits (a cup ramen about 5 px across, a bottle 3 px
across and 9 tall, a snack bag 5 x 6).

Drawn at 4 times their size and shrunk by 4 (HModel scale 4: 64 texels a block, a quarter px a texel), so a label's
print, a bottle's highlight or a can's tab can be a texel. Round things are eight-sided (octa: four boxes, two of them
turned 45 degrees, so a can or a bottle is a clean octagon rather than a staircase), their print unrolled round them
(store_goods_art's wrap pictures); flat things wear 128-a-block pictures on their faces (HModel.skin / plate).
In a hand (thirdperson_righthand) each stands upright in the fist of a standing customer (models/bus_hold's solver),
the umbrella hangs from its hook.
"""

import math

from stsv_assets.bake import FACES, Paint, _turn, h3

from models import store as S
import store_goods_art as GA

GREEN = S.GREEN
K = 4                      # drawn units (texels) a model px
T22 = math.tan(math.radians(22.5))


def G4(name):
    return S.HModel(name, floor=8, scale=4)


def P(x, y, z):
    """A point x, y, z texels from the good's bottom centre -> drawn."""
    return (8 + x, 8 + y, 8 + z)


def bx(m, name, a, b, paint, **kw):
    m.box(name, P(*a), P(*b), paint, **kw)


def flat(ramp, step, **kw):
    return Paint(ramp, step, noise=0, **kw)


def picture(name, fn):
    return S._surface(name, fn)


def sampler(img, x0=0, y_top=0):
    """fn(U, y) -> the picture's texel (raw) at wrap position U (texels from the front's middle) and height y
    (texels up from the bottom), or None where it is see-through; x0: the picture's x of U = 0; y_top: the height of
    its top row."""
    px = img.load()
    W, H = img.size

    def fn(U, y):
        i = math.floor(U + x0)
        j = math.floor(y_top - y)
        if 0 <= i < W and 0 <= j < H:
            c = px[i, j]
            if c[3] > 0:
                return ("raw", c)
        return None
    return fn


def octa(m, name, r, y0, y1, paint, side=None, top=None, top_paint=None, c=(0, 0), n=4):
    """An eight-sided prism round the y axis at c = (x, z) texels from the bottom centre, inradius r, from y0 to y1:
    four boxes (r wide across, a side's length deep), the second turned 90, the others +-45 degrees, so the outline
    is a regular octagon. side(U, y, k) paints the sides: U texels round from the front's middle (to the right as
    seen from in front, wrapping at +-4 sides), y texels up from the good's bottom, k the side (0 = north, 1 = the
    north-west one...); top(x, z) the top face (flat, unlit: the four tops share it). The tops are a hair apart so
    no two faces lie in one plane. n = 2: only the first two boxes (a cross, for small round parts). Returns the
    side's length."""
    s = 2 * r * T22
    cx, cz = 8 + c[0], 8 + c[1]
    e = 0.04
    boxes = [((cx - r, cz - s / 2), (cx + r, cz + s / 2), 0, 0.0), ((cx - s / 2, cz - r), (cx + s / 2, cz + r), 0, e),
             ((cx - r, cz - s / 2), (cx + r, cz + s / 2), 45, 2 * e), ((cx - r, cz - s / 2), (cx + r, cz + s / 2), -45, 3 * e)]
    for (a, b, ang, dy) in boxes[:n]:
        def dec(ct, ang=ang):
            p = ct.p
            n = FACES[ct.face][0]
            if ang:
                p = _turn(p, "y", ang, (cx, p[1], cz))
                n = _turn(n, "y", ang, (0, 0, 0))
            if ct.face == "up":
                return top(p[0] - cx, p[2] - cz) if top else None
            if ct.face == "down":
                return None
            nx, nz = n[0], n[2]
            k = round(math.atan2(-nx, -nz) / (math.pi / 4)) % 8
            u = (p[0] - cx) * nz + (p[2] - cz) * -nx
            U = k * s + u
            if U >= 4 * s:
                U -= 8 * s
            return side(U, p[1] - 8, k) if side else None
        sp = paint(vrim=0, decal=dec)
        tp = (top_paint or Paint(paint.ramp, paint.base, noise=0, light=False))(decal=dec)
        rot = ("y", ang, (cx, 8 + y0, cz)) if ang else None
        m.box(name, (a[0], 8 + y0 + dy, a[1]), (b[0], 8 + y1 - dy, b[1]), sp, faces={"up": tp}, rotation=rot)
    return s


def wrap_pic(name, fn, r, h):
    """The wrap picture for a body of inradius r (its width: all eight sides) and h rows."""
    s = 2 * r * T22
    W = math.ceil(8 * s)
    return picture(name, lambda: fn(W, math.ceil(h))), W


def top_plate(m, name, tex, r, y, c=(0, 0), px=2):
    """A printed round top (a cut-out picture px texels a drawn unit) lying just on a body's top at height y."""
    w, _ = S.T.size_of(tex)
    d = w / px
    x0, z0 = 8 + c[0] - d / 2, 8 + c[1] - d / 2
    m.plate(name, (x0, 8 + y + 0.05, z0), (x0 + d, 8 + y + 0.05, z0 + d), {"up": (tex, [w, w, 0, 0])}, one_sided=True)


def held(m, **kw):
    return S.held(m, fill=0.9, **kw)


def gloss(U, y, at=-0.55, s=1.0):
    """A highlight streak down a round body, left of the front's middle (the light comes from the front left)."""
    return abs(U - at * 8 * s) < 0.75


# ================================================================================================
# 삼각김밥
# ================================================================================================

SAMGAK = {"samgak_tuna": (("sapphire", 2), ("fish_silver", 4)), "samgak_bibim": (("pepper", 2), ("leaf", 3)),
          "samgak_bulgogi": (("doenjang", 1), ("corn", 2))}


def _samgak_rows():
    """[(half width, half depth)] a texel row up: a triangle with rounded corners, thinner towards its top."""
    out = []
    for j in range(20):
        y = j + 0.5
        hw = min(11.6 - 0.55 * y, 9.6 + 1.6 * math.sqrt(y))
        if y > 15:
            hw = min(hw, math.sqrt(max(0.0, 3.4 ** 2 - (y - 16) ** 2)))
        if hw < 0.75:
            break
        hd = min(4.75 - 0.1 * y, 3.9 + math.sqrt(y))
        out.append((round(hw * 4) / 4, round(hd * 4) / 4))
    return out


SAMGAK_ROWS = _samgak_rows()


def samgak(key):
    """삼각김밥 in its film: a rounded triangle standing up, white rice through the clear film, the seaweed sealed
    apart round its bottom (dark green-black, the film's corner tabs 2 and 3 red), the red pull strip 1 down the
    middle of the front with its number, a flavour label (tuna blue, bibim red, bulgogi brown) with print and a
    little picture of the filling, a yellow price dot; the back's seam and a barcode sticker."""
    lab, icon = SAMGAK[key]
    m = G4(f"store_item_{key}")
    m.group("kimbap")

    def d(c):
        x, y, z = c.p[0] - 8, c.p[1] - 8, c.p[2] - 8
        front = c.face == "north"
        back = c.face == "south"
        sea = y < 7 - abs(x) * 0.18
        if c.face in ("north", "south", "up", "east", "west"):
            if front and abs(x) < 0.5 and y >= 1:
                return ("pepper", 2)                                               # the pull strip
            if front and 0.5 <= abs(x) < 1.5 and 15 <= y < 16:
                return ("pepper", 2)
            if (front or back) and sea and abs(x) > 8 and y < 2.5:
                return ("tomato", 3)                                               # the corner tabs
            if front and -6.5 <= x < 6.5 and 8 <= y < 14:
                if y >= 13 or y < 8.5 or abs(x) > 6:
                    return (lab[0], lab[1] - 1)
                if -5 <= x < -1 and 9.5 <= y < 12.5:
                    return icon if (math.floor(x) + math.floor(y)) % 3 else WHITE_T     # the filling's picture
                if 1.5 <= x < 5.5 and y >= 9:
                    return WHITE_T if math.floor(y) in (9, 11) and math.floor(x * 1.3) % 3 else lab
                return lab
            if front and 4.5 <= x < 6 and 14.5 <= y < 16:
                return ("corn", 2)                                                 # the price dot
            if back and abs(x) < 4 and 9 <= y < 12:
                return INK_T if math.floor(x * 2) % 3 and 9.5 <= y < 11.5 else WHITE_T  # the barcode sticker
            if back and abs(x) < 0.6:
                return ("fish_silver", 4)                                          # the film's seam
            if sea:
                return ("roe", 1) if (c.i[0] + c.i[1]) % 5 else ("roe", 2)
            if (front or back) and abs(x + y * 0.4 - 4) < 0.6 and not sea:
                return ("snow", 2)                                                 # the film's shine
            return ("rice", 3) if h3(c.i[0], c.i[1], c.i[2], 2) > 0.18 else ("rice", 2)
        return None
    p = Paint("rice", 3, noise=0, decal=d)
    y = 0
    for (hw, hd) in SAMGAK_ROWS:
        bx(m, "tri", (-hw, y, -hd), (hw, y + 1, hd), p)
        y += 1
    return held(m)


WHITE_T = ("snow", 2)
INK_T = ("iron", 0)


# ================================================================================================
# 도시락 · 핫바 · 구운 계란
# ================================================================================================

def dosirak():
    """도시락: a black tray (a raised rim), a clear lid over it showing the dishes (rice with sesame and a plum,
    kimchi, rolled egg, bulgogi, a sausage and greens), a green paper sleeve round its middle with a white name
    panel, a red burst and a yellow price sticker on top; the dishes seen through the lid's sides."""
    m = G4("store_item_dosirak")
    m.group("tray")

    def tray(c):
        if c.face in S.SIDES and c.p[1] - 8 >= 4:
            return ("iron", 2)                                     # the rim's lip
        return None
    bx(m, "tray", (-14, 0, -10), (14, 4.5, 10), Paint("iron", 1, "lacquer", noise=0, rim=1.3, decal=tray))
    m.group("lid")

    def lid_side(c):
        x, y = c.p[0] - 8, c.p[1] - 8
        if y >= 6.5:
            return ("glass", 3)
        if c.face in ("north", "south"):
            if x < -1:
                return ("rice", 2) if (c.i[0] + c.i[1]) % 4 else ("rice", 1)
            return ("pepper", 3) if x < 6 else ("corn", 2)
        return ("rice", 2) if c.p[2] < 8 else ("leaf", 3)
    bx(m, "lid", (-13.5, 4.5, -9.5), (13.5, 6.5, 9.5), Paint("glass", 2, noise=0, rim=1.4, decal=lid_side))
    top = picture("store_good_dosirak_lid", lambda: GA.dosirak_lid(52, 36))
    m.skin("lid_top", P(-13, 6.5, -9), P(13, 7.5, 9), Paint("glass", 3, noise=0, rim=1.5), "up", top, uv=[52, 36, 0, 0])
    m.group("sleeve")

    def band(c):
        y = c.p[1] - 8
        if c.face in ("north", "south"):
            if 1 <= y < 5:
                return WHITE_T if 2 <= y < 4 and abs(c.p[0] - 8) < 3 else (GREEN, 2)
            return (GREEN, 1)
        return None
    bx(m, "sleeve", (-3.5, 0.25, -10.25), (3.5, 7.75, 10.25), Paint(GREEN, 2, noise=0, rim=1.2, decal=band), faces={"up": None})
    m.elements[-1]["open"] = ("up",)
    sl = picture("store_good_dosirak_sleeve", lambda: GA.sleeve_top(14, 41))
    m.plate("sleeve_top", P(-3.5, 7.75, -10.25), P(3.5, 7.75, 10.25), {"up": (sl, [14, 41, 0, 0])}, one_sided=True)
    return held(m)


def hotbar():
    """핫바: a golden fish-cake bar with grill marks on a wooden stick, sealed in clear film (crimped ends, the
    stick through one), an orange printed band round its middle."""
    m = G4("store_item_hotbar")

    def bar(c):
        x = c.p[0] - 8
        if -3 <= x < 3:
            y = c.p[1] - 8
            if c.face in ("north", "south", "up"):
                if 2 <= y < 4.5 and c.face != "up" and abs(x) < 2:
                    return WHITE_T
                return ("fox", 3) if math.floor(x) % 3 else ("pepper", 2)
            return ("fox", 3)
        if c.face in ("up", "north", "south") and math.floor(x + c.p[1] * 0.6 + c.p[2] * 0.6) % 5 == 0:
            return ("doenjang", 2)                                 # grill marks
        if c.face == "up" and abs(c.p[2] - 8 + 1) < 0.6 and abs(x + 8) < 5:
            return ("snow", 2)                                     # the film's shine
        return None
    p = Paint("onion", 3, noise=0.05, rim=1.2, decal=bar)
    bx(m, "bar", (-15, 0.5, -3), (5, 6.5, 3), p)
    bx(m, "bar", (-14.5, 1.5, -3.75), (4.5, 5.5, 3.75), p)
    crimp = Paint("glass", 3, noise=0, light=False, decal=lambda c: ("glass", 2) if math.floor(c.p[1] + c.p[2]) % 2 else None)
    bx(m, "crimp", (-17, 0.5, -3.5), (-15, 6.5, 3.5), crimp)
    bx(m, "crimp", (5, 1, -3.5), (7, 6, 3.5), crimp)
    bx(m, "stick", (7, 2.5, -1), (17, 4.5, 1), flat("hinoki", 3, rim=1.2))
    return held(m, tilt=0)


EGG = [(5.5, 3, 8.5, 4), (5, 8.5, 10.5, 4), (4, 10.5, 12, 4), (2.5, 12, 13, 2)]


def _egg(m, name, cx, paint, side):
    for (r, a, b, n) in EGG:
        octa(m, name, r, 3 + a, 3 + b, paint, c=(cx, 0), n=n, side=side,
             top=lambda x, z: ("bean", 2))


def egg():
    """구운 계란: two baked eggs (brown, speckled) standing in a clear two-cup tray, an orange paper band round the
    tray with print and a little egg picture."""
    m = G4("store_item_egg")
    m.group("eggs")

    def speck(U, y, k):
        if h3(math.floor(U * 2), math.floor(y * 2), k, 9) < 0.08:
            return ("bean", 1)
        if gloss(U, y, s=1.4) and 6 < y < 12:
            return ("bean", 4)
        return None
    egg_p = Paint("bean", 2, noise=0.04)
    for cx in (-6, 6):
        _egg(m, "egg", cx, egg_p, speck)
    m.group("tray")

    def band(c):
        y = c.p[1] - 8
        x = c.p[0] - 8
        if c.face == "north":
            if 2 <= y < 6 and -3 <= x < 3:
                return ("bean", 2) if (x * x / 7 + (y - 4) ** 2 / 2.5) < 1 else ("snow", 2)
            if y >= 6.5 or y < 1:
                return ("fox", 2)
            if abs(y - 4) < 0.5 and abs(x) >= 4.5 and math.floor(x) % 3:
                return WHITE_T
            if abs(y - 2.5) < 0.5 and abs(x) >= 4.5 and math.floor(x * 1.5) % 4:
                return ("fox", 4)
        if c.face == "up":
            return ("glass", 3)
        return None
    bx(m, "tray", (-12.5, 0, -6.5), (12.5, 8, 6.5), Paint("fox", 3, noise=0, rim=1.2, decal=band))
    bx(m, "lip", (-13, 8, -7), (13, 9, 7), Paint("glass", 3, noise=0, rim=1.4))
    return held(m)


# ================================================================================================
# 컵라면
# ================================================================================================

RAMEN = {"ramen_red": [(8, 0, 9), (9, 9, 17), (10, 17, 24)], "ramen_mild": [(8, 0, 9), (9, 9, 17), (10, 17, 24)],
         "ramen_big": [(10.5, 0, 6), (12.5, 6, 14.5)]}


def ramen(key):
    """컵라면: a tapering paper cup (three bands, its rings), the print round it (ramen_wrap: the brand panel and a bowl
    of noodles on the front, flames or leaves round the sides, a barcode at the back), the white paper lid with its
    rim, the lid's print on top (ramen_lid) and its pull tab at the front. ramen_big is the wide low bowl type."""
    tiers = RAMEN[key]
    m = G4(f"store_item_{key}")
    h = tiers[-1][2]
    r_top = tiers[-1][0]
    tex, W = wrap_pic(f"store_good_{key}_wrap", lambda w, hh, k=key: GA.ramen_wrap(k, w, hh), r_top, h)
    pic = sampler(S.T.SURFACES[tex](), x0=W / 2, y_top=h)
    body = {"ramen_red": ("pepper", 2), "ramen_mild": ("fox", 3), "ramen_big": ("iron", 1)}[key]

    m.group("cup")
    for (r, a, b) in tiers:
        octa(m, "cup", r, a, b, Paint(body[0], body[1], noise=0), side=lambda U, y, k, r=r: pic(U * r_top / r, y))
    m.group("lid")
    octa(m, "lid", r_top + 0.5, h, h + 1.25, Paint("car_white", 3, noise=0), side=lambda U, y, k: ("car_white", 2) if y < h + 0.5 else None,
         top=lambda x, z: ("car_white", 3))
    lid = picture(f"store_good_{key}_lid", lambda: GA.ramen_lid(key, round(2 * (r_top + 0.5))))
    top_plate(m, "lid_print", lid, r_top + 0.5, h + 1.25)
    m.box("tab", P(-2, h + 0.6, -r_top - 3), P(2, h + 1.0, -r_top), flat("car_white", 3, rim=1.2))
    return held(m)


# ================================================================================================
# 음료
# ================================================================================================

def _bottle(m, key, body, tiers, label, label_y, cap, cap_tiers, ribs=False, shine=("snow", 2)):
    """A bottle of eight-sided tiers [(r, y0, y1)], the label picture round the body between label_y, a cap."""
    r0 = tiers[0][0]
    tex, W = wrap_pic(f"store_good_{key}_label", lambda w, hh, k=label: GA.bottle_label(k, w, hh), r0, label_y[1] - label_y[0])
    pic = sampler(S.T.SURFACES[tex](), x0=W / 2, y_top=label_y[1])
    s0 = 2 * r0 * T22

    def side(r):
        def fn(U, y, k):
            Ur = U * r0 / r
            if label_y[0] <= y < label_y[1] and r == r0:
                return pic(Ur, y)
            if abs(Ur + s0 * 1.6) < 0.9:
                return shine                                     # the highlight down the glass
            if ribs and math.floor(y) % 3 == 0 and y < label_y[0]:
                return (body[0], body[1] + 1)
            return None
        return fn
    m.group("bottle")
    for (r, a, b) in tiers:
        octa(m, "body", r, a, b, Paint(body[0], body[1], noise=0), side=side(r),
             top=lambda x, z: (body[0], body[1] + 1))
    m.group("cap")
    for (r, a, b, col) in cap_tiers:
        octa(m, "cap", r, a, b, Paint(col[0], col[1], noise=0, rim=1.3),
             side=lambda U, y, k, col=col: (col[0], col[1] - 1) if math.floor(U * 1.5) % 2 else None,
             top=lambda x, z, col=col: (col[0], col[1] + 1) if math.hypot(x, z) < 1.2 else col)


def pet(key, body, label, cap, label_y=(8, 24), h=24):
    """A PET bottle: a petal foot, the body with its label, shoulders, the neck with its ring, a ridged cap."""
    m = G4(f"store_item_{key}")
    _bottle(m, key, body, [(6, 1, h), (5.5, 0, 1), (5, h, h + 3), (3.5, h + 3, h + 5.5), (2.5, h + 5.5, h + 8)],
            label, label_y, cap, [(3, h + 8, h + 8.5, ("car_white", 3)), (3, h + 8.5, h + 12, cap)], ribs=key == "water")
    return held(m)


def soju():
    """소주: a green glass bottle, a white label with a green bird and a blue band, a green twist cap."""
    m = G4("store_item_soju")
    _bottle(m, "soju", ("bottle_green", 2), [(6, 0, 19), (4.75, 19, 21.5), (3.25, 21.5, 23.5), (2.75, 23.5, 29)], "soju", (4, 18),
            (GREEN, 2), [(3.1, 29, 33, (GREEN, 2))], shine=("bottle_green", 4))
    return held(m)


def beer():
    """맥주: a brown glass bottle, a gold oval label with a red ribbon, a gold foil collar on the neck, a crown cap."""
    m = G4("store_item_beer")
    _bottle(m, "beer", ("doenjang", 1), [(6, 0, 18), (5, 18, 20.5), (3.5, 20.5, 23), (2.75, 23, 30.5)], "beer", (3, 16),
            ("gold", 2), [(3, 30.5, 32.5, ("gold", 2))], shine=("doenjang", 3))
    octa(m, "foil", 3, 26, 29.5, Paint("gold", 2, noise=0, rim=1.3),
         side=lambda U, y, k: ("pepper", 2) if 27.25 <= y < 28.25 else None, top=lambda x, z: ("gold", 3))
    return held(m)


def hangover():
    """숙취해소제: a short brown glass bottle, a navy label with a gold slash and a white emblem, a gold ridged
    aluminium cap."""
    m = G4("store_item_hangover")
    _bottle(m, "hangover", ("doenjang", 0), [(5, 0, 14), (4, 14, 16), (2.75, 16, 18)], "hangover", (2, 13),
            ("gold", 2), [(3.25, 18, 22, ("gold", 2))], shine=("doenjang", 2))
    return held(m)


def coffee_can():
    """캔커피: a slim can, dark roast brown with a gold band, a cream oval with a steaming cup; a silver rim, top and
    pull tab."""
    m = G4("store_item_coffee_can")
    tex, W = wrap_pic("store_good_coffee_can_wrap", GA.can_wrap, 5, 21)
    pic = sampler(S.T.SURFACES[tex](), x0=W / 2, y_top=21)
    silver = Paint("fish_silver", 3, noise=0)
    octa(m, "can", 4.5, 0, 1, silver)
    octa(m, "can", 5, 1, 21, Paint("doenjang", 1, noise=0),
         side=lambda U, y, k: ("fish_silver", 4) if abs(U + 3.3) < 0.75 and not 3 <= y < 6 else pic(U, y))
    octa(m, "can", 4.25, 21, 22, silver, top=lambda x, z: ("fish_silver", 4) if math.hypot(x, z) > 3.2 else ("fish_silver", 3))
    m.box("tab", P(-1, 22, -2.5), P(1, 22.4, 0.5), flat("fish_silver", 4, rim=1.3))
    return held(m)


def coffee_cup():
    """컵커피: a clear plastic cup showing the coffee, a cream label with a latte, the silver peel lid printed on top,
    a straw in its wrapper stuck up the side."""
    m = G4("store_item_coffee_cup")
    tex, W = wrap_pic("store_good_coffee_cup_wrap", GA.cup_coffee_wrap, 7.5, 18)
    pic = sampler(S.T.SURFACES[tex](), x0=W / 2, y_top=18)
    for (r, a, b) in ((6.5, 0, 6), (7, 6, 12), (7.5, 12, 18)):
        octa(m, "cup", r, a, b, Paint("doenjang", 2, noise=0), side=lambda U, y, k, r=r: pic(U * 7.5 / r, y))
    octa(m, "rim", 8, 18, 19, Paint("glass", 3, noise=0, rim=1.3), top=lambda x, z: ("fish_silver", 4))
    lid = picture("store_good_coffee_cup_lid", lambda: GA.cup_coffee_lid(16))
    top_plate(m, "lid", lid, 8, 19)
    m.box("straw", P(7.25, 3, -4.75), P(8.75, 21, -3.25), flat("snow", 2, rim=1.2,
          decal=lambda c: ("pepper", 2) if abs(c.p[1] - 8 - 17) < 0.5 else None))
    return held(m)


def banana_milk():
    """바나나우유 (an original jar): a squat, bellied pale-yellow jar narrowing to a short neck, a green banner
    printed round the belly with a little banana, a foil cap with a green rim."""
    m = G4("store_item_banana_milk")
    tex, W = wrap_pic("store_good_banana_wrap", GA.banana_wrap, 9, 8)
    pic = sampler(S.T.SURFACES[tex](), x0=W / 2, y_top=11)
    jar = Paint("petal_yellow", 2, noise=0)

    def side(r):
        def fn(U, y, k):
            if abs(U * r / 9 + 4.5) < 0.8 and 3 < y < 16:
                return ("petal_yellow", 4)                              # the shine
            return pic(U, y) if r == 9 else None
        return fn
    for (r, a, b) in ((7, 0, 1.5), (8.5, 1.5, 3), (9, 3, 11), (8.5, 11, 13), (7, 13, 15), (5, 15, 18)):
        octa(m, "jar", r, a, b, jar, side=side(r), top=lambda x, z: ("petal_yellow", 3))
    octa(m, "cap", 5.5, 18, 19.5, Paint(GREEN, 2, noise=0, rim=1.3), top=lambda x, z: ("fish_silver", 4) if math.hypot(x, z) < 4.2 else (GREEN, 3))
    return held(m)


# ================================================================================================
# 과자 (pillow bags, boxes)
# ================================================================================================

BAG = {"chips": ((19, 20), ("corn", 2), ("pepper", 2)), "shrimp": ((17, 18), ("snow", 2), ("pepper", 2)),
       "jelly": ((15, 17), ("crystal", 3), ("crystal", 2)), "candy": ((17, 17), ("pink", 2), ("pepper", 3))}
HDK = 3                    # texels a drawn unit of the bag and box pictures


def bag(key):
    """A pillow bag standing up: a puffed body (its middle fuller than its edges), crimped seams top and bottom
    (serrated, a shade darker), the printed front (store_goods_art.bag_front) and back. The jelly is a stand-up
    pouch: a flat gusset foot, a zip line under its top seam."""
    (w, h), body, seam = BAG[key]
    m = G4(f"store_item_{key}")
    pw, ph = w * HDK, h * HDK
    x0, x1 = -w / 2 - 1, w / 2 + 1
    pouch = key == "jelly"
    yb = 0 if pouch else 2
    yt = yb + h + 2

    def crimp(c):
        if c.face in ("north", "south", "up") and math.floor(c.p[0] * 2) % 2:
            return (seam[0], seam[1] - 1)
        return None
    sp = Paint(seam[0], seam[1], noise=0, rim=1.2, decal=crimp)
    if not pouch:
        bx(m, "seam", (x0, 0, -1), (x1, 2, 1), sp)
    bx(m, "seam", (x0, yt, -1), (x1, yt + 3, 1), sp)
    bp = Paint(body[0], body[1], "lacquer", noise=0, rim=1.1)
    bx(m, "body", (x0, yb, -3), (x1, yt, 3), bp)
    front = picture(f"store_good_{key}_front", lambda: GA.bag_front(key, pw, ph))
    back = picture(f"store_good_{key}_back", lambda: GA.bag_back(key, pw, ph))
    m.skin("puff", P(-w / 2, yb + 1, -4.5), P(w / 2, yb + 1 + h, 4.5), bp, "north", front)
    m.plate("back", P(-w / 2, yb + 1, 4.52), P(w / 2, yb + 1 + h, 4.52), {"south": (back, [0, 0, pw, ph])}, one_sided=True)
    if pouch:
        bx(m, "zip", (x0 + 0.5, yt - 1.5, -3.25), (x1 - 0.5, yt - 1, 3.25), flat(seam[0], seam[1] - 1))
    return held(m)


BOX = {"choco_snack": ((24, 18, 6), ("pepper", 2)), "chocolate": ((22, 10, 3), ("doenjang", 1)),
       "gum": ((18, 7, 6), (GREEN, 3)), "bandage": ((18, 12, 6), ("snow", 2)), "cigarette": ((11, 17, 5), ("snow", 2))}


def box_good(key):
    """A printed box standing up, its picture (store_goods_art.box_front) on the front, its colour round it, a
    little print on its sides; the cigarette pack has its lid's line and a cellophane tear strip, the chocolate bar
    its foil ends."""
    (w, h, d), col = BOX[key]
    pw, ph = w * HDK, h * HDK
    m = G4(f"store_item_{key}")
    front = picture(f"store_good_{key}_front", lambda: GA.box_front(key, pw, ph))

    def sides(c):
        y = c.p[1] - 8
        if key == "cigarette":
            if abs(y - 13.5) < 0.3:
                return ("fish_silver", 4)                                   # the tear strip
            if c.face in ("up",) or y >= 9.5:
                return ("indigo", 1) if not abs(y - 13.5) < 0.6 else ("fish_silver", 4)
            if y < 2:
                return ("iron", 0)
            return None
        if key == "chocolate" and abs(c.p[0] - 8) > w / 2 - 2:
            return ("gold", 3) if math.floor(c.p[1] * 2) % 2 else ("gold", 2)   # the foil ends
        if c.face in ("east", "west") and abs(y - h / 2) < 1 and math.floor(c.p[2] * 2) % 3:
            return WHITE_T if col != ("snow", 2) else ("pink", 2)
        if c.face == "up" and key == "choco_snack" and abs(c.p[2] - 8) < 1:
            return WHITE_T
        return None
    m.skin("box", P(-w / 2, 0, -d / 2), P(w / 2, h, d / 2), Paint(col[0], col[1], "lacquer", noise=0, rim=1.1, decal=sides),
           "north", front)
    if key == "cigarette":
        m.plate("back", P(-w / 2, 0, d / 2 + 0.02), P(w / 2, h, d / 2 + 0.02),
                {"south": (front, [0, 0, pw, ph])}, one_sided=True)
    return held(m)


# ================================================================================================
# 아이스크림
# ================================================================================================

def ice_bar():
    """아이스바: a wrapped ice pop lying flat, icy blue film printed from above (a red ice pop on its stick, frost waves,
    a burst), crimped clear ends."""
    m = G4("store_item_ice_bar")
    top = picture("store_good_ice_bar_top", lambda: GA.ice_bar_top(32, 48))
    m.skin("wrap", P(-8, 0, -12), P(8, 4, 12), Paint("sapphire", 3, "lacquer", noise=0, rim=1.3), "up", top, uv=[32, 48, 0, 0])
    crimp = Paint("snow", 2, noise=0, rim=1.2, decal=lambda c: ("car_white", 2) if math.floor(c.p[0]) % 2 else None)
    bx(m, "end", (-7, 0.5, -14), (7, 2.5, -12), crimp)
    bx(m, "end", (-7, 0.5, 12), (7, 2.5, 14), crimp)
    return held(m)


def ice_cone():
    """콘 아이스크림: a conical paper sleeve (white with red diagonal bands, a cone drawn on its front), a red rim and a
    clear dome lid showing chocolate and crushed nuts."""
    m = G4("store_item_ice_cone")
    tex, W = wrap_pic("store_good_cone_wrap", GA.cone_wrap, 7.5, 19)
    pic = sampler(S.T.SURFACES[tex](), x0=W / 2, y_top=19)
    for (r, a, b) in ((2, 0, 3), (3.5, 3, 7), (5, 7, 11), (6.25, 11, 15), (7.5, 15, 19)):
        octa(m, "cone", r, a, b, Paint("snow", 2, noise=0), side=lambda U, y, k, r=r: pic(U * 7.5 / r, y))
    octa(m, "rim", 8, 19, 20.5, Paint("pepper", 2, noise=0, rim=1.3), top=lambda x, z: ("pepper", 3))
    nuts = lambda x, z: ("bean", 3) if h3(math.floor(x * 2), math.floor(z * 2), 0, 5) < 0.3 else ("doenjang", 1)
    octa(m, "dome", 7, 20.5, 22, Paint("doenjang", 1, noise=0), side=lambda U, y, k: ("doenjang", 2) if math.floor(U) % 3 == 0 else None, top=nuts)
    octa(m, "dome", 4.5, 22, 23, Paint("doenjang", 1, noise=0), top=nuts)
    return held(m)


# ================================================================================================
# 생활용품 · 복권 · 담배
# ================================================================================================

def umbrella():
    """우산: a folded clear vinyl umbrella lying along x: the rolled canopy (pale, its folds), a white strap with a
    snap round it, the steel tip, the shaft and a black J hook. In a hand it hangs from the hook, tip down."""
    m = G4("store_item_umbrella")

    def fold(c):
        if c.face in ("north", "up", "south") and math.floor(c.p[1] * 1.5 + c.p[0] * 0.25) % 4 == 0:
            return ("glass", 2)
        return None
    can = Paint("ice", 3, noise=0, rim=1.3, decal=fold)
    for (a, b, r) in ((-18, -8, 5), (-8, 4, 5.5), (4, 14, 4.25), (14, 20, 3)):
        bx(m, "canopy", (a, 5.5 - r, -r * 0.7), (b, 5.5 + r, r * 0.7), can)
        bx(m, "canopy", (a + 0.1, 5.5 - r * 0.7, -r), (b - 0.1, 5.5 + r * 0.7, r), can)
    bx(m, "strap", (-4, 0.25, -5.75), (-1, 11.25, 5.75), flat("snow", 2, rim=1.2, decal=lambda c: ("fish_silver", 4) if c.face == "north" and 7 <= c.p[1] - 8 < 9 else None))
    bx(m, "tip", (20, 4.75, -0.75), (27, 6.25, 0.75), flat("steel", 4, rim=1.2))
    bx(m, "shaft", (-24, 4.75, -0.75), (-18, 6.25, 0.75), flat("steel", 4, rim=1.2))
    hook = flat("iron", 1, rim=1.3)
    bx(m, "handle", (-33, 4, -1.5), (-24, 7, 1.5), hook)
    bx(m, "hook", (-36, 0, -1.5), (-33, 7, 1.5), hook)
    bx(m, "hook", (-33, 0, -1.5), (-30, 2.5, 1.5), hook)
    from models import bus_hold as BH
    tr = BH.hold_transform(grip=m.to_model(P(-29, 5.5, 0)), at=BH.fist(), turn=BH.turn(z=90), scale=1.0, pose=BH.STANDING)
    BH.displays(m, tr, gui_fill=0.9)
    return m


def battery():
    """건전지: a blister card lying face up (black and orange, a lightning mark, the hang hole), four AA cells under a
    clear blister: copper tops, black bodies with an orange band."""
    m = G4("store_item_battery")
    card = picture("store_good_battery_card", lambda: GA.battery_card(40, 52))
    m.skin("card", P(-10, 0, -13), P(10, 1, 13), flat("iron", 1, rim=1.2), "up", card, uv=[40, 52, 0, 0])
    bx(m, "blister", (-8.5, 1, -8), (8.5, 2, 6), Paint("glass", 3, noise=0, light=False))

    def cell(c):
        z = c.p[2] - 8
        if z < -5:
            return ("copper", 3) if z < -6.25 else ("fish_silver", 4)
        if -1 <= z < 1.5:
            return ("fox", 3)
        if z >= 5:
            return ("fish_silver", 3)
        return None
    for x in (-6, -2, 2, 6):
        bx(m, "cell", (x - 1.75, 2, -7), (x + 1.75, 4, 5), Paint("iron", 1, "metal", noise=0, rim=1.3, decal=cell))
        bx(m, "cell", (x - 1.25, 1.5, -6.9), (x + 1.25, 4.5, 4.9), Paint("iron", 1, "metal", noise=0, rim=1.3, decal=cell))
    return held(m)


def tissue():
    """휴지: a soft pack of facial tissue lying flat, rounded edges, white film printed with flowers and a blue band,
    the oval opening on top with a tissue puffing up out of it."""
    m = G4("store_item_tissue")
    top = picture("store_good_tissue_top", lambda: GA.tissue_top(48, 32))
    film = Paint("snow", 2, "lacquer", noise=0, rim=1.1,
                 decal=lambda c: ("sapphire", 3) if c.face in S.SIDES and c.p[1] - 8 < 3 else None)
    m.skin("pack", P(-12, 0, -8), P(12, 10, 8), film, "up", top, uv=[48, 32, 0, 0])
    bx(m, "edge", (-12.75, 1, -7), (12.75, 9, 7), film)
    bx(m, "edge", (-11, 1, -8.75), (11, 9, 8.75), film)
    sheet = flat("snow", 3, rim=1.3, decal=lambda c: ("car_white", 3) if math.floor(c.p[0] + c.p[1]) % 3 == 0 else None)
    bx(m, "tissue", (-4, 10, -1.5), (4, 14, 0), sheet, rotation=("z", 22.5, P(0, 10, 0)))
    bx(m, "tissue", (-2.5, 10, -0.5), (3.5, 12.5, 1), sheet, rotation=("z", -22.5, P(0, 10, 0)))
    return held(m)


def lottery():
    """즉석복권: a scratch card lying flat (store_goods_art.lottery_face: the red header, the silver panels, one
    scratched)."""
    tex = picture("store_good_lottery", GA.lottery_face)
    m = S.Model("store_item_lottery")
    m.group("ticket")
    edge = [1, 1, 2, 2]
    m.box("ticket", (5, 8, 4), (11, 8.25, 12), tex,
          uv={"up": [48, 64, 0, 0], "down": [0, 0, 2, 2], "north": edge, "south": edge, "east": edge, "west": edge})
    return S.held(m, grip_y=8, fill=0.9)


GOODS = {
    "samgak_tuna": lambda: samgak("samgak_tuna"),
    "samgak_bibim": lambda: samgak("samgak_bibim"),
    "samgak_bulgogi": lambda: samgak("samgak_bulgogi"),
    "dosirak": dosirak,
    "hotbar": hotbar,
    "egg": egg,
    "ramen_red": lambda: ramen("ramen_red"),
    "ramen_mild": lambda: ramen("ramen_mild"),
    "ramen_big": lambda: ramen("ramen_big"),
    "banana_milk": banana_milk,
    "coffee_can": coffee_can,
    "coffee_cup": coffee_cup,
    "soda": lambda: pet("soda", ("cucumber", 3), "soda", (GREEN, 2), label_y=(7, 23)),
    "ion_drink": lambda: pet("ion_drink", ("ice", 3), "ion", ("sapphire", 2), label_y=(6, 24)),
    "water": lambda: pet("water", ("glass", 3), "water", ("snow", 2), label_y=(9, 23)),
    "soju": soju,
    "beer": beer,
    "hangover": hangover,
    "chips": lambda: bag("chips"),
    "shrimp": lambda: bag("shrimp"),
    "choco_snack": lambda: box_good("choco_snack"),
    "jelly": lambda: bag("jelly"),
    "chocolate": lambda: box_good("chocolate"),
    "candy": lambda: bag("candy"),
    "gum": lambda: box_good("gum"),
    "ice_bar": ice_bar,
    "ice_cone": ice_cone,
    "umbrella": umbrella,
    "battery": battery,
    "tissue": tissue,
    "bandage": lambda: box_good("bandage"),
    "lottery": lottery,
    "cigarette": lambda: box_good("cigarette"),
}
