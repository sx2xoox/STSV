"""
Village bus passenger skins (64x64 player skins, the modern layout with both layers) for the economy
server's bus: the passengers are Mannequins whose profile carries a skin patch pointing at these
textures, so nothing is uploaded to Mojang.

    python3 economy_bus_skins.py                    # skins -> economy/content/economy/assets/economy/textures/entity/bus/
    python3 economy_bus_skins.py --sheet out.png    # + a preview sheet (front and back, flat, 4x and 1x)
    python3 economy_bus_skins.py --sheet out.png --types farmer,drunk   # the sheet with only these types
    python3 economy_bus_skins.py --shiny-sheet shiny.png   # + the 별난 손님 sheet (each kind's first look and its <id>_s1)

Also NPCs drawn the same way that are not passengers (NPCS: 현장 소장 site_boss of the 도박장, 딜러 dealer of its 별관's baccarat table,
본사 점검원 hq_inspector of the 편의점 and one more of the 편의점's, STORE_NPCS), written to the same folder.

A texture at assets/economy/textures/entity/bus/<id>.png is the key "economy:entity/bus/<id>"
(ResolvableProfile.skinPatch().body(key), with .model(SLIM) for the slim ones: see SLIM / main()).

Fifty-four passenger types, three of each: 동네 주민 resident, 학생 student, 할머니 grandma, 회사원 office,
관광객 tourist, VIP, and the second batch 농부 farmer, 등산객 hiker, 낚시꾼 fisher, 군인 soldier (on leave, no
insignia), 커플 couple, 술 취한 아저씨 drunk, and the third batch 택배기사 courier, 유치원생 kinder, 캠핑족 camper,
해녀 haenyeo, 야구팬 fan (an invented team), 간호사 nurse, and the fourth batch 수다쟁이 아줌마 chatty, 멀미하는 아저씨
motionsick, 애 업은 엄마 mom, 강아지 데려온 할아버지 dogwalker (the dog is not here), 수상한 선글라스 남자 shades (the same man
three times), 산타 할아버지 santa, 유령 손님 ghost, 배달 라이더 rider, 스님 monk, 유튜버 youtuber (no logos, no real team or
company colours), and the fifth batch 시장 아줌마 market, 동네 아저씨 uncle, 대학생 univ, 공사장 인부 worker, 초등학생 kid,
닭 든 할머니 chicken (the hen in her arms is a held model), 지각생 tardy, 면접 청년 interview, 엿장수 yeot, 외국인 여행객 traveler (no
flags), 큰소리 통화남 phone, 반말 아저씨 rude, 무임승차 청년 dodger, 벨 장난 꼬마 prankster, 쩍벌남 spread, 민원 아줌마
complainer, 과자 부스러기 학생 crumbs, 친절한 신사 gentleman, 무당 shaman, 우체부 mailman (no emblem), 붕어빵 장수 bungeo,
신혼부부 bride (bride, groom, 한복 bride, 한복 groom: four looks), 경찰 police (no badge or lettering), 마술사 magician, 씨름 선수 wrestler, 건달 thug (tattoos with no lettering
or real group's marks).
Colours are flat per area; the depth comes from the outer layer (hair volume, hats,
jackets, cardigans, backpacks, glasses) and one shared light pass (finish()), so faces stay clean: eyes are
a white pixel and a pupil, the mouth two pixels.
"""

import argparse
from pathlib import Path

from PIL import Image

HERE = Path(__file__).parent
REPO = HERE.parent.parent
OUT = REPO / "economy" / "content" / "economy" / "assets" / "economy" / "textures" / "entity" / "bus"


# ---------------------------------------------------------------------------------------------
# the skin template
# ---------------------------------------------------------------------------------------------

def parts(slim):
    """part -> ((u, v, w, h, d) of the inner layer, (u, v) of the outer layer)"""
    a = 3 if slim else 4
    return {
        "head": ((0, 0, 8, 8, 8), (32, 0)),
        "body": ((16, 16, 8, 12, 4), (16, 32)),
        "rarm": ((40, 16, a, 12, 4), (40, 32)),
        "larm": ((32, 48, a, 12, 4), (48, 48)),
        "rleg": ((0, 16, 4, 12, 4), (0, 32)),
        "lleg": ((16, 48, 4, 12, 4), (0, 48)),
    }


def rects(u, v, w, h, d):
    """face -> (x0, y0, width, height) in the texture. "right" is the part's own right side (the player's
    right): it touches the front at its last column; "left" touches the front at its first column."""
    return {"top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d), "right": (u, v + d, d, h),
            "front": (u + d, v + d, w, h), "left": (u + d + w, v + d, d, h), "back": (u + 2 * d + w, v + d, w, h)}


def C(s):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)


def shade(c, k):
    return tuple(max(0, min(255, round(v * k))) for v in c[:3]) + (c[3],)


def fwd(fc, x, w=4):
    """on a side face: 0 at the column touching the front, w-1 at the back"""
    return x if fc == "left" else w - 1 - x


SIDES = ("left", "right")


class Skin:
    def __init__(self, slim=False):
        self.slim = slim
        self.P = parts(slim)
        self.im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))

    def paint(self, part, fn, layer=0):
        """fn(face, x, y, w, h) -> colour or None; x, y inside the face, y down"""
        (u, v, w, h, d), ov = self.P[part]
        if layer:
            u, v = ov
        for face, (x0, y0, fw, fh) in rects(u, v, w, h, d).items():
            for y in range(fh):
                for x in range(fw):
                    c = fn(face, x, y, fw, fh)
                    if c is not None:
                        self.im.putpixel((x0 + x, y0 + y), c)

    def fill(self, part, col, layer=0):
        self.paint(part, lambda *a: col, layer)

    def finish(self):
        """one light pass for both layers: tops lit, backs and sides a little darker, cloth a touch darker
        towards its bottom and at the edges of each face (reads round, not flat). The face stays flat."""
        im = self.im.copy()
        k_face = {"top": 1.06, "bottom": 0.82, "front": 1.0, "back": 0.93, "right": 0.94, "left": 0.94}
        for part, ((u, v, w, h, d), (ou, ov)) in self.P.items():
            for (bu, bv) in ((u, v), (ou, ov)):
                for face, (x0, y0, fw, fh) in rects(bu, bv, w, h, d).items():
                    for y in range(fh):
                        for x in range(fw):
                            c = self.im.getpixel((x0 + x, y0 + y))
                            if c[3] == 0:
                                continue
                            k = k_face[face]
                            if part == "head":
                                k = 1.0 if face == "front" else 1 + (k - 1) * 0.7
                            elif face not in ("top", "bottom"):
                                k *= 1.03 - 0.07 * y / (fh - 1)
                                if x == 0 or x == fw - 1:
                                    k *= 0.95
                            im.putpixel((x0 + x, y0 + y), shade(c, k))
        return im


# ---------------------------------------------------------------------------------------------
# heads
# ---------------------------------------------------------------------------------------------

# hair: (inner layer cover, outer layer volume); each style -> per face test (x, y) (sides: f = 0 at the front edge)
HAIR = {
    "short": dict(front=lambda x, y: y == 0 or (y == 1 and x not in (3, 4)),
                  side=lambda f, y: y <= 1 or (y <= 5 and f >= 4) or (y <= 3 and f == 0),
                  back=lambda x, y: y <= 5,
                  o_front=lambda x, y: y == 0, o_side=lambda f, y: y == 0 or (y <= 2 and f >= 4), o_back=lambda x, y: y <= 3),
    "part": dict(front=lambda x, y: y == 0 or (y == 1 and x >= 3) or (y == 2 and x >= 5),
                 side=lambda f, y: y <= 1 or (y <= 5 and f >= 4) or (y <= 3 and f == 0),
                 back=lambda x, y: y <= 5,
                 o_front=lambda x, y: y == 0 and x >= 2, o_side=lambda f, y: y == 0, o_back=lambda x, y: y <= 2),
    "messy": dict(front=lambda x, y: y == 0 or (y == 1 and x not in (2, 5)),
                  side=lambda f, y: y <= 2 or (y <= 5 and f >= 4) or (y <= 3 and f == 0),
                  back=lambda x, y: y <= 5,
                  o_front=lambda x, y: y == 0 or (y == 1 and x in (0, 3, 6)), o_side=lambda f, y: y <= 1,
                  o_back=lambda x, y: y <= 3 or (y == 4 and x % 3 == 1)),
    "buzz": dict(front=lambda x, y: y == 0, side=lambda f, y: y == 0 or (y <= 4 and f >= 5), back=lambda x, y: y <= 4,
                 o_front=None, o_side=None, o_back=None),
    "slick": dict(front=lambda x, y: y == 0 or (y == 1 and x in (0, 1, 6, 7)),
                  side=lambda f, y: y <= 1 or (y <= 5 and f >= 3),
                  back=lambda x, y: y <= 5,
                  o_front=None, o_side=None, o_back=None),
    "perm": dict(front=lambda x, y: y <= 1 or (y <= 3 and x in (0, 7)),
                 side=lambda f, y: y <= 4 or (y <= 5 and f >= 3),
                 back=lambda x, y: y <= 6,
                 o_front=lambda x, y: y == 0 or (y == 1 and x not in (2, 5)),
                 o_side=lambda f, y: y <= 3 or (y == 4 and f % 2 == 1), o_back=lambda x, y: y <= 5 or (y == 6 and x % 2 == 0)),
    "bob": dict(front=lambda x, y: y <= 1 or (y <= 5 and x in (0, 7)),
                side=lambda f, y: y <= 5,
                back=lambda x, y: y <= 6,
                o_front=lambda x, y: y <= 1, o_side=lambda f, y: y <= 5, o_back=lambda x, y: y <= 6),
    "bald": dict(front=lambda x, y: False,
                 side=lambda f, y: 2 <= y <= 4 and f >= 2,
                 back=lambda x, y: 2 <= y <= 4,
                 o_front=None, o_side=None, o_back=None),
    "long": dict(front=lambda x, y: y <= 1 or (x in (0, 7) and y <= 6),
                 side=lambda f, y: True,
                 back=lambda x, y: True,
                 o_front=lambda x, y: y == 0, o_side=lambda f, y: y <= 6 and f >= 1, o_back=lambda x, y: True),
}


def head(s, skin, hair, style, eye="2a2233", mouth="a8584f", pupils="in", brows=None, blush=None, extra=()):
    """skin all over, hair by style on both layers, then the face on the front:
    row 3 brows, row 4 eyes (a white and a pupil each), row 5 nose shade, row 6 mouth"""
    sk, hr, H = C(skin), C(hair), HAIR[style]

    def inner(fc, x, y, w, h):
        if fc == "top":
            return sk if style == "bald" else hr
        if fc == "bottom":
            return shade(sk, 0.9)
        if fc == "back":
            return hr if H["back"](x, y) else sk
        if fc in SIDES:
            return hr if H["side"](fwd(fc, x, 8), y) else sk
        if H["front"](x, y):
            return hr
        if brows and y == 3 and x in (1, 2, 5, 6):
            return C(brows)
        if y == 4 and x in (1, 2, 5, 6):
            pupil = {"in": (2, 5), "out": (1, 6), "side": (2, 6)}[pupils]
            return C(eye) if x in pupil else C("f4f1ec")
        if y == 5 and x in (3, 4):
            return shade(sk, 0.9)
        if y == 6 and x in (3, 4):
            return C(mouth)
        if blush and y == 5 and x in (1, 6):
            return C(blush)
        return sk
    s.paint("head", inner)

    def outer(fc, x, y, w, h):
        if fc == "top":
            return shade(hr, 1.06) if (x + 2 * y) % 5 else shade(hr, 0.92)
        if fc == "bottom" or H["o_front"] is None:
            return None
        test = {"front": H["o_front"], "back": H["o_back"]}.get(fc)
        if fc in SIDES:
            ok = H["o_side"](fwd(fc, x, 8), y)
        else:
            ok = test(x, y)
        if not ok:
            return None
        if style == "perm" and (x + 2 * y) % 4 == 0:
            return shade(hr, 0.88)                        # curls: a regular sprinkle, not noise
        return hr
    if style not in ("buzz", "bald"):
        s.paint("head", outer, layer=1)
    for e in extra:
        e(s)


def glasses(frame, lens=None):
    """round-ish specs on the outer layer: a frame above and below each eye, the eyes stay clear"""
    F = C(frame)
    Lc = C(lens) if lens else None

    def f(fc, x, y, w, h):
        if fc == "front":
            if y in (3, 5) and x in (1, 2, 5, 6):
                return F
            if y == 4 and x in (0, 3, 4, 7):
                return F
            return None
        if fc in SIDES and y == 4 and fwd(fc, x, 8) <= 4:
            return F
        return None
    return lambda s: s.paint("head", f, layer=1)


def sunglasses(lens, frame, shine):
    """solid dark lenses (2 x 2 each, a shine pixel in the top outer corner) with a bridge and temples, on the
    inner layer so they sit flat on the face"""
    L, F, S = C(lens), C(frame), C(shine)

    def f(fc, x, y, w, h):
        if fc == "front":
            if y in (3, 4) and x in (1, 2, 5, 6):
                return S if (y == 3 and x in (1, 5)) else L
            if y == 3 and x in (0, 3, 4, 7):
                return F
            return None
        if fc in SIDES and y == 3 and fwd(fc, x, 8) <= 4:
            return F
        return None
    return lambda s: s.paint("head", f)


def big_shades(lens, frame):
    """round celebrity sunglasses, 3 wide each, on the outer layer"""
    L, F = C(lens), C(frame)

    def f(fc, x, y, w, h):
        if fc == "front":
            if y in (3, 4) and x in (0, 1, 2, 5, 6, 7):
                return shade(L, 1.5) if (y == 3 and x in (1, 6)) else L
            if y == 3 and x in (3, 4):
                return F
            return None
        if fc in SIDES and y == 3 and fwd(fc, x, 8) <= 4:
            return F
        return None
    return lambda s: s.paint("head", f, layer=1)


def straw_hat(band):
    """a straw hat on the outer layer: the crown over the top and row 0, a band in row 1, the brim in row 2"""
    straw, B = C("e6c66e"), C(band)

    def f(fc, x, y, w, h):
        if fc == "top":
            return shade(straw, 1.08) if (x + y) % 4 else straw
        if fc == "bottom":
            return None
        if y == 0:
            return straw
        if y == 1:
            return B
        if y == 2:
            return shade(straw, 1.1)                      # the brim
        return None
    return lambda s: s.paint("head", f, layer=1)


def mustache(col):
    return lambda s: s.paint("head", lambda fc, x, y, w, h: C(col) if fc == "front" and y == 6 and 2 <= x <= 5 else None)


def tired():
    """dark circles under the eyes (overtime)"""
    return lambda s: s.paint("head", lambda fc, x, y, w, h: C("b88f8a") if fc == "front" and y == 5 and x in (1, 2, 5, 6) else None)


def happy_eyes(col):
    """eyes closed in a smile: a dark line where the eyes are"""
    return lambda s: s.paint("head", lambda fc, x, y, w, h: (C(col) if fc == "front" and y == 4 and x in (1, 2, 5, 6) else None))


def earring(col):
    return lambda s: s.paint("head", lambda fc, x, y, w, h: C(col) if fc in SIDES and y == 5 and fwd(fc, x, 8) == 3 else None)


def lips(col):
    return lambda s: s.paint("head", lambda fc, x, y, w, h: C(col) if fc == "front" and y == 6 and 2 <= x <= 5 else None)


# ---------------------------------------------------------------------------------------------
# bodies
# ---------------------------------------------------------------------------------------------

def arms(s, cloth, skin, sleeve=10, cuff=None, layer_cloth=None):
    """cloth down to row `sleeve`, a cuff row (inner layer) under it if given, the hand below; layer_cloth:
    a jacket sleeve on the outer layer over the top rows"""
    sk = C(skin)
    for a in ("rarm", "larm"):
        def f(fc, x, y, w, h):
            if fc == "bottom":
                return sk
            if fc == "top":
                return C(cloth)
            if y < sleeve:
                return C(cloth)
            if cuff and y == sleeve:
                return C(cuff)
            return sk
        s.paint(a, f)
        if layer_cloth:
            s.paint(a, lambda fc, x, y, w, h: C(layer_cloth) if fc != "bottom" and (fc == "top" or y < sleeve) else None, 1)


def legs(s, cloth, shoe, shoe_rows=1, skin=None, bare=None, sock=None):
    """trousers (or shorts: `bare` = the first bare row, skin below it), socks, shoes"""
    for lg in ("rleg", "lleg"):
        def f(fc, x, y, w, h):
            if fc == "bottom":
                return shade(C(shoe), 0.8)
            if fc == "top":
                return C(cloth)
            if y >= h - shoe_rows:
                return C(shoe)
            if sock and y >= h - shoe_rows - 2:
                return C(sock)
            if bare is not None and y >= bare:
                return C(skin)
            return C(cloth)
        s.paint(lg, f)


def body(s, fn):
    s.paint("body", fn)


def jacket(s, col, open_rows=6, open_cols=(2, 5), lapel=None, edge=None):
    """an open jacket / blazer / cardigan on the body's outer layer: the front open between open_cols for
    open_rows rows (the shirt and tie show), lapels one shade darker"""
    J = C(col)
    a, b = open_cols

    def f(fc, x, y, w, h):
        if fc == "front":
            if y < open_rows and a <= x <= b:
                return None
            if lapel and y < open_rows and x in (a - 1, b + 1):
                return C(lapel)
            if edge and y >= open_rows and x == (a + b) // 2 + 1:
                return C(edge)
            return J
        return J
    s.paint("body", f, layer=1)


def backpack(s, col, strap):
    """a backpack on the outer layer: the bag on the back with a flap and a pocket, straps over the shoulders
    and down the front"""
    B, S = C(col), C(strap)

    def f(fc, x, y, w, h):
        if fc == "back":
            if 1 <= x <= 6 and 1 <= y <= 10:
                if y <= 3:
                    return shade(B, 0.82) if y == 3 else B   # the flap
                if 2 <= x <= 5 and 6 <= y <= 9:
                    return shade(B, 0.88) if y == 6 else shade(B, 1.08)   # the pocket
                return B
            return None
        if fc == "top" and x in (1, 6):
            return S
        if fc == "front" and x in (1, 6) and y <= 7:
            return S
        return None
    s.paint("body", f, layer=1)


def lanyard(cord, card):
    """an ID card on a cord, clipped at the chest (outer layer, so it sits on a jacket too)"""
    def f(fc, x, y, w, h):
        if fc == "front":
            if x == 6 and y <= 4:
                return C(cord)
            if x in (5, 6) and y in (5, 6):
                return C("f7f7f2") if y == 5 else C(card)
        if fc == "back" and y == 0 and 1 <= x <= 6:
            return C(cord)
        return None
    return lambda s: s.paint("body", f, layer=1)


def camera(strap):
    """a camera hanging on the chest, its strap round the neck (outer layer)"""
    def f(fc, x, y, w, h):
        if fc == "front":
            if (y <= 1 and x in (1, 6)) or (2 <= y <= 3 and x in (2, 5)):
                return C(strap)
            if 4 <= y <= 6 and 1 <= x <= 6:
                if y == 5 and x in (3, 4):
                    return C("5b8fc9")                    # the lens
                if y == 4 and x == 5:
                    return C("e9e9e9")                    # the flash
                return C("26262b")
            return None
        if fc == "back" and y == 0 and 1 <= x <= 6:
            return C(strap)
        if fc == "top" and x in (1, 6):
            return C(strap)
        return None
    return lambda s: s.paint("body", f, layer=1)


def flowers(base, petal, centre, leaf=None, step=(4, 4)):
    """a tidy repeat of little plus-shaped flowers (hawaiian shirts, 몸빼): centres on a staggered grid"""
    B, P, Cc = C(base), C(petal), C(centre)
    Lf = C(leaf) if leaf else None
    sx, sy = step

    def at(x, y):
        for cy in range(-sy, 20, sy):
            row = (cy // sy) % 2
            for cx in range(-sx + 1 + row * (sx // 2), 12, sx):
                dx, dy = abs(x - cx), abs(y - cy)
                if dx == 0 and dy == 0:
                    return Cc
                if dx + dy == 1:
                    return P
        if Lf and (x + y * 3) % 7 == 3 and (x + y) % 2 == 0:
            return Lf
        return B
    return at


# ---------------------------------------------------------------------------------------------
# the passengers
# ---------------------------------------------------------------------------------------------

def resident(v):
    """동네 주민: everyday neighbours"""
    if v == 1:
        # 아저씨 in a green tracksuit (white side stripes, a zip, a little belly) and blue slippers
        s, sk, tr = Skin(), "e9b48e", "1f8a5b"
        head(s, sk, "2a2522", "short", mouth="9a5a50")

        def b(fc, x, y, w, h):
            if fc == "front" and x == 4 and y < 9:
                return C("cfd3d6")                        # the zip
            if fc == "front" and 6 <= y <= 9 and 2 <= x <= 5:
                return shade(C(tr), 1.1)                  # the belly catching the light
            if fc in SIDES and x in (1, 2):
                return C("f5f5f5")
            return C(tr)
        body(s, b)
        for p in ("rarm", "larm", "rleg", "lleg"):
            s.paint(p, lambda fc, x, y, w, h: C("f5f5f5") if fc in SIDES and x in (1, 2) else C(tr))
        arms(s, tr, sk, sleeve=10)
        for p in ("rarm", "larm"):
            s.paint(p, lambda fc, x, y, w, h: C("f5f5f5") if fc in SIDES and x in (1, 2) and y < 10 else None)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: (C(sk) if y == 10 else C("3a6fd8")) if y >= 10 or fc == "bottom" else None)
        return s
    if v == 2:
        # 아주머니: a coral fleece zip-up over a striped tee, a short bob, black leggings, white trainers
        s, sk = Skin(slim=True), "f0c3a0"
        head(s, sk, "4a2e22", "bob", mouth="c0605a", blush="f2a99a")
        fleece = "e8735f"

        def b(fc, x, y, w, h):
            if fc == "front" and 3 <= x <= 4 and y < 8:
                return C("2f4f8f") if y % 2 else C("f2f2ee")   # the tee's stripes
            return C(fleece)
        body(s, b)
        jacket(s, fleece, open_rows=8, open_cols=(3, 4), lapel=None)
        s.paint("body", lambda fc, x, y, w, h: C("c95a48") if fc == "front" and y >= 8 and x == 4 else None, 1)   # zip
        arms(s, fleece, sk, sleeve=10, layer_cloth=fleece)
        legs(s, "26262c", "f4f4f4", shoe_rows=2)
        s.paint("rleg", lambda fc, x, y, w, h: C("d9d9d9") if y == 10 and fc != "bottom" else None)
        s.paint("lleg", lambda fc, x, y, w, h: C("d9d9d9") if y == 10 and fc != "bottom" else None)
        return s
    # 청년: a grey hoodie (hood down on the back), jeans, earbuds, messy brown hair
    s, sk = Skin(), "d9a07a"
    head(s, sk, "6b4026", "messy", pupils="side", extra=[earring("ffffff")])
    hood = "8d939b"

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and 2 <= x <= 5:
                return shade(C(hood), 0.75)               # the neckline
            if y in (1, 2, 3) and x in (3, 4) and y != 1:
                return C("f2f2f2")                        # drawstrings
            if 7 <= y <= 9 and 2 <= x <= 5:
                return shade(C(hood), 0.9 if y > 7 else 0.8)   # the pouch pocket
            if y == 11:
                return shade(C(hood), 0.85)               # the hem
        return C(hood)
    body(s, b)
    s.paint("body", lambda fc, x, y, w, h: shade(C(hood), 0.85) if fc == "back" and y <= 3 and 1 <= x <= 6 and not (y == 3 and x in (1, 6)) else None, 1)   # the hood
    arms(s, hood, sk, sleeve=10, cuff=None)
    s.paint("rarm", lambda fc, x, y, w, h: shade(C(hood), 0.85) if y == 9 and fc not in ("top", "bottom") else None)
    s.paint("larm", lambda fc, x, y, w, h: shade(C(hood), 0.85) if y == 9 and fc not in ("top", "bottom") else None)
    legs(s, "3d5f95", "ececec", shoe_rows=1)
    s.paint("rleg", lambda fc, x, y, w, h: C("33507f") if fc == "front" and x == 0 and y < 11 else None)
    s.paint("lleg", lambda fc, x, y, w, h: C("33507f") if fc == "front" and x == 3 and y < 11 else None)
    return s


def student(v):
    """학생: school uniforms with a backpack"""
    if v == 1:
        # navy blazer, white shirt, red tie, name tag, grey trousers, blue backpack
        s, sk = Skin(), "f1c7a5"
        head(s, sk, "1b1b22", "short")
        shirt, tie = C("f4f4f0"), C("c0392b")

        def b(fc, x, y, w, h):
            if fc == "front":
                if 3 <= x <= 4 and 1 <= y <= 6:
                    return tie if y < 6 else shade(tie, 0.85)
                if y == 11:
                    return C("2a2a2a")
                return shirt
            return shirt
        body(s, b)
        jacket(s, "2b3a67", open_rows=7, open_cols=(2, 5), lapel="22305a")
        s.paint("body", lambda fc, x, y, w, h: C("f1d36b") if fc == "front" and y == 3 and x == 6 else None, 1)   # name tag
        backpack(s, "2f6fd6", "24489a")
        arms(s, "f4f4f0", sk, sleeve=10, layer_cloth="2b3a67")
        legs(s, "6b6f78", "1a1a1a")
        return s
    if v == 2:
        # a girl in a burgundy blazer, a red ribbon, a checked skirt, white knee socks, yellow backpack
        s, sk = Skin(slim=True), "f3cdb0"
        head(s, sk, "2b1d18", "long", mouth="c45a5a", blush="f5aaa0")
        shirt = C("f4f4f0")

        def b(fc, x, y, w, h):
            if fc == "front" and y == 1 and 2 <= x <= 5:
                return C("d03a3a") if x in (2, 5) else C("a82c2c")   # the ribbon
            return shirt
        body(s, b)
        jacket(s, "7a2638", open_rows=6, open_cols=(2, 5), lapel="64202f")
        s.paint("body", lambda fc, x, y, w, h: C("f1d36b") if fc == "front" and y == 3 and x == 6 else None, 1)
        backpack(s, "f2b632", "c48d1c")
        arms(s, "f4f4f0", sk, sleeve=10, layer_cloth="7a2638")
        skirt = lambda x, y: C("1f2a44") if y == 5 else (C("3b4a6b") if x % 2 else C("4a5c84"))   # a pleated skirt
        legs(s, "5a6d94", "1c1c1c", skin=sk, bare=6, sock="f4f4f4", shoe_rows=1)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: skirt(x, y) if y <= 5 and fc not in ("top", "bottom") else None)
            s.paint(lg, lambda fc, x, y, w, h: skirt(x, y) if y <= 5 and fc not in ("top", "bottom") else None, 1)
            s.paint(lg, lambda fc, x, y, w, h: C("f4f4f4") if fc != "bottom" and y in (7, 8) else None)   # knee socks
        return s
    # summer uniform: short sleeves, navy trousers, round glasses, buzz cut, a red backpack
    s, sk = Skin(), "c98b5e"
    head(s, sk, "1c1a1a", "buzz", extra=[glasses("2a2a2a")])
    shirt = C("f6f6f2")

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and x in (3, 4):
                return C(sk)                               # open collar
            if x == 4 and 1 <= y <= 10 and y % 3 == 1:
                return C("d8d8d0")                         # buttons
            if y == 3 and x == 1:
                return C("3f6bd1")                         # a school badge
            if y == 11:
                return C("1f2533")
        return shirt
    body(s, b)
    backpack(s, "d84a3a", "a33328")
    arms(s, "f6f6f2", sk, sleeve=4)
    legs(s, "2b3346", "f0f0f0", shoe_rows=1)
    return s


def grandma(v):
    """할머니: a perm, a cardigan, flowery 몸빼 trousers, rubber shoes"""
    if v == 1:
        s, sk = Skin(slim=True), "e8b996"
        head(s, sk, "c9c5c0", "perm", mouth="b0645a", blush="f0a8a0", extra=[glasses("8a6a3a")])
        cardi, blouse = "7b3f8c", "efe2c8"
        mom = flowers("e85d8f", "ffe08a", "fff6d8", leaf="6fc26a")
        shoe = "4a7bd1"
    elif v == 2:
        s, sk = Skin(slim=True), "efc4a2"
        head(s, sk, "f1efea", "perm", mouth="b0645a", blush="f4a3a3", extra=[happy_eyes("3a2a2a")])
        cardi, blouse = "b8323a", "f5efe2"
        mom = flowers("5b3f8f", "f2c8e8", "ffe08a", leaf="7fc98a")
        shoe = "7a2a3a"
    else:
        s, sk = Skin(slim=True), "d9a47c"
        head(s, sk, "5a2d2a", "perm", mouth="a0544c", blush="e8968a", extra=[earring("e8c25a")])   # a dyed perm
        cardi, blouse = "3f8f7a", "f2e6cc"
        mom = flowers("c9a46a", "8a4a2a", "f2e2b0", leaf=None)
        shoe = "2f2f35"

    def b(fc, x, y, w, h):
        if fc == "front" and 3 <= x <= 4 and y == 0:
            return shade(C(blouse), 0.85)
        return C(blouse)
    body(s, b)
    # the cardigan: open down the middle, buttons on one edge, long to the hips
    jacket(s, cardi, open_rows=12, open_cols=(3, 4))
    s.paint("body", lambda fc, x, y, w, h: C("f3e9d2") if fc == "front" and x == 2 and y in (2, 5, 8) else None, 1)
    s.paint("body", lambda fc, x, y, w, h: shade(C(cardi), 0.82) if fc in ("front", "back") and y == 11 else None, 1)
    arms(s, blouse, sk, sleeve=10, layer_cloth=cardi)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: shade(C(cardi), 0.82) if y == 9 and fc not in ("top", "bottom") else None, 1)
    for lg in ("rleg", "lleg"):
        def f(fc, x, y, w, h):
            if fc == "bottom":
                return shade(C(shoe), 0.8)
            if y == 11:
                return C(shoe)
            if fc == "top":
                return mom(0, 1)
            if y == 10:
                return shade(mom(1, 2), 0.82)              # the gathered cuff
            return mom(x, y)
        s.paint(lg, f)
    return s


def office(v):
    """회사원: shirt and tie"""
    if v == 1:
        # a charcoal suit, a blue tie, an ID card, a side part
        s, sk = Skin(), "f0c4a0"
        head(s, sk, "16161a", "part")
        suit, shirt, tie = "2d3138", "f4f4f4", "2e6bd1"
        extra = [lanyard("2e6bd1", "4a90d9")]
    elif v == 2:
        # a beige blazer, a navy tie, a pencil skirt, a bob; ID card on a red cord
        s, sk = Skin(slim=True), "f2cba9"
        head(s, sk, "3a2620", "bob", mouth="c4544e", extra=[earring("f0f0f0")])
        suit, shirt, tie = "c9b28f", "f7f7f4", "263a66"
        extra = [lanyard("c0392b", "e86a5a")]
    else:
        # overtime: a crumpled shirt, sleeves rolled, the tie pulled loose, glasses and dark circles
        s, sk = Skin(), "dcae88"
        head(s, sk, "2a2724", "messy", pupils="out", extra=[tired(), glasses("3a3a40")])
        suit, shirt, tie = None, "eef1f6", "e0a92a"
        extra = []

    def b(fc, x, y, w, h):
        if fc == "front":
            if v == 3:
                if y == 0 and x in (3, 4):
                    return C(sk)                           # the open collar
                if 2 <= y <= 6 and x in (3, 4) and not (y == 6 and x == 3):
                    return C(tie) if y != 2 else shade(C(tie), 0.85)   # the tie pulled down, its knot low
                if y == 11:
                    return C("2a2a2a")
                if (x, y) in ((1, 7), (6, 8)):
                    return shade(C(shirt), 0.9)            # creases
                return C(shirt)
            if 3 <= x <= 4 and 1 <= y <= 7:
                return C(tie) if y % 3 else shade(C(tie), 0.82)
        return C(shirt)
    body(s, b)
    if suit:
        jacket(s, suit, open_rows=6, open_cols=(2, 5), lapel=shade_hex(suit, 0.8), edge=shade_hex(suit, 0.8))
        arms(s, shirt, sk, sleeve=9, cuff=shirt, layer_cloth=suit)
    else:
        arms(s, shirt, sk, sleeve=5)
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: shade(C(shirt), 0.85) if y in (4, 5) and fc not in ("top", "bottom") else None)   # the rolled cuff
    for e in extra:
        e(s)
    if v == 2:
        legs(s, "2b2b30", "1a1a1a", skin=sk, bare=6, shoe_rows=1)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: C("2b2b30") if y <= 5 and fc not in ("top", "bottom") else None, 1)
            s.paint(lg, lambda fc, x, y, w, h: C("e8c0a0") if 6 <= y <= 10 and fc != "bottom" else None)   # tights
    else:
        legs(s, "33363d" if v == 1 else "4a4e57", "121212", shoe_rows=2 if v == 1 else 1)
    return s


def shade_hex(h, k):
    c = shade(C(h), k)
    return "%02x%02x%02x" % c[:3]


def tourist(v):
    """관광객: a loud hawaiian shirt, a straw hat, a camera"""
    if v == 1:
        s, sk = Skin(), "f2c09a"
        head(s, sk, "5a3b22", "short", extra=[sunglasses("d9682a", "c8963c", "ffd9a0"), straw_hat("c0392b")])
        shirt = flowers("19a7c9", "ff7a59", "ffd34d")
        shorts, sandal = "c8b07a", "7a4a2a"
    elif v == 2:
        s, sk = Skin(slim=True), "f4c8a8"
        head(s, sk, "c06a2a", "long", mouth="d0505a", blush="f59a8a", extra=[straw_hat("2f8fd6")])
        shirt = flowers("d8343f", "fff4e8", "ffd34d")
        shorts, sandal = "5b86c2", "e0a060"
    else:
        s, sk = Skin(), "a86e48"
        head(s, sk, "1e1814", "short", mouth="7a3e36", extra=[big_shades("202028", "e0c050"), straw_hat("2e8b57")])
        shirt = flowers("f6c933", "2e9e5b", "ffffff")
        shorts, sandal = "f0ece0", "3a3a3a"

    def b(fc, x, y, w, h):
        if fc == "front" and x in (3, 4) and y < 2:
            return C(sk)                                   # the open collar
        return shirt(x, y)
    body(s, b)
    camera("3a2a1e" if v != 3 else "c0392b")(s)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: shirt(x, y) if y < 4 or fc == "top" else C(sk))
    for lg in ("rleg", "lleg"):
        def f(fc, x, y, w, h):
            if fc == "bottom":
                return C(sandal)
            if y <= 4 or fc == "top":
                return shade(C(shorts), 0.85) if y == 4 else C(shorts)
            if y == 11:
                return C(sandal) if fc != "front" or x in (0, 3) else C(sk)   # sandal straps, toes
            if y == 10 and fc == "front" and x in (1, 2):
                return C(sandal)
            return C(sk)
        s.paint(lg, f)
    if v == 1:
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: C("f09a80") if y in (5, 6) and fc == "front" else None)   # sunburnt knees
    return s


def vip(v):
    """VIP: black and gold, dark glasses"""
    if v == 1:
        # a tuxedo with satin lapels, a bow tie, studs, a gold watch, slicked hair
        s, sk = Skin(), "f0c8a8"
        head(s, sk, "0e0e12", "slick", extra=[sunglasses("16161c", "d4af37", "6a6a80")])
        tux = "15151a"

        def b(fc, x, y, w, h):
            if fc == "front":
                if y == 1 and 2 <= x <= 5:
                    return C("141418") if x in (2, 5) else C("2c2c34")   # the bow tie
                if 3 <= x <= 4 and y % 2 == 1 and y > 2:
                    return C("e8c25a")                     # gold studs
            return C("fafafa")
        body(s, b)
        jacket(s, tux, open_rows=9, open_cols=(3, 4), lapel="34343e", edge="34343e")
        s.paint("body", lambda fc, x, y, w, h: C("ffffff") if fc == "front" and x == 1 and y == 3 else None, 1)   # pocket square
        arms(s, "fafafa", sk, sleeve=9, cuff="fafafa", layer_cloth=tux)
        s.paint("larm", lambda fc, x, y, w, h: C("e8c25a") if y == 9 and fc not in ("top", "bottom") else None)   # gold watch
        legs(s, tux, "050505", shoe_rows=2)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: C("34343e") if fc in SIDES and x == 1 and y < 10 else None)   # satin stripe
        return s
    if v == 2:
        # 회장님: white hair slicked back, bushy white brows, a mustache, black shirt and gold tie, gold chain
        s, sk = Skin(), "e2ad86"
        head(s, sk, "e9e6df", "slick", brows="f4f1ea", mouth="9a4f45",
             extra=[mustache("f0ede6"), sunglasses("1a1408", "c9a227", "8a7a50")])
        suit, shirt, tie = "1a1a20", "26262e", "d4af37"

        def b(fc, x, y, w, h):
            if fc == "front" and 3 <= x <= 4 and 1 <= y <= 7:
                return C(tie) if y % 3 else C("b8942a")
            return C(shirt)
        body(s, b)
        jacket(s, suit, open_rows=6, open_cols=(2, 5), lapel="2c2c34", edge="0e0e12")
        s.paint("body", lambda fc, x, y, w, h: C("d4af37") if fc == "front" and ((x == 1 and y == 3) or (y == 8 and x in (1, 2))) else None, 1)   # pin, chain
        arms(s, shirt, sk, sleeve=9, cuff="d4af37", layer_cloth=suit)
        legs(s, suit, "3a2214", shoe_rows=2)
        return s
    # the VIP lady: long dark waves, big round shades, red lips, a black dress, gold necklace and bracelets
    s, sk = Skin(slim=True), "f1c9a8"
    head(s, sk, "1a1216", "long", mouth="c0222e", extra=[lips("c0222e"), big_shades("15151a", "d4af37"), earring("d4af37")])
    dress = C("121216")

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and 2 <= x <= 5:
                return C(sk)                               # the neckline
            if (y == 1 and x in (2, 5)) or (y == 2 and 3 <= x <= 4):
                return C("d4af37")                         # the necklace
            if y == 7:
                return C("d4af37")                         # a gold belt
        if fc == "back" and y == 7:
            return C("d4af37")
        return dress
    body(s, b)
    arms(s, "121216", sk, sleeve=3)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: C("d4af37") if y == 9 and fc not in ("top", "bottom") else None)
    legs(s, "121216", "121216", skin=sk, bare=7, shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: C("121216") if y <= 6 and fc not in ("top", "bottom") else None, 1)   # the dress's hem
        s.paint(lg, lambda fc, x, y, w, h: C("b01e28") if y == 11 and fc == "front" else None)   # red soles peek out
    return s


# ---------------------------------------------------------------------------------------------
# more hats, faces and bits (the second batch of passengers)
# ---------------------------------------------------------------------------------------------

def cap(col, bill=None, gap=True):
    """a baseball / work cap on the outer layer: the crown over the top and rows 0-1, the bill a darker row 2
    across the front, the strap gap at the back"""
    K = C(col)
    B = C(bill) if bill else shade(K, 0.8)

    def f(fc, x, y, w, h):
        if fc == "top":
            return K
        if fc == "bottom":
            return None
        if fc == "front":
            return K if y <= 1 else (B if y == 2 else None)
        if fc == "back":
            if gap and y == 1 and x in (3, 4):
                return None
            return K if y <= 1 else None
        if fc in SIDES:
            return K if y <= 1 else (B if y == 2 and fwd(fc, x, 8) == 0 else None)
        return None
    return lambda s: s.paint("head", f, layer=1)


def visor(col):
    """a sun visor (썬캡): a band round the head in row 1, a deep peak in row 2 over the brows; no top"""
    K = C(col)

    def f(fc, x, y, w, h):
        if fc in ("top", "bottom"):
            return None
        if y == 1:
            return K
        if fc == "front" and y == 2:
            return shade(K, 0.82)
        return None
    return lambda s: s.paint("head", f, layer=1)


def bucket_hat(col):
    """a bucket hat on the outer layer: the crown over the top and rows 0-1, a brim in row 2 all round"""
    K = C(col)

    def f(fc, x, y, w, h):
        if fc == "top":
            return shade(K, 1.06) if (x in (0, 7) or y in (0, 7)) else K
        if fc == "bottom":
            return None
        if y <= 1:
            return K
        if y == 2:
            return shade(K, 0.82)                          # the brim
        return None
    return lambda s: s.paint("head", f, layer=1)


def beret(col):
    """a beret: over the top and rows 0-1, pulled down a row further on one side (the wearer's right)"""
    K = C(col)

    def f(fc, x, y, w, h):
        if fc == "top":
            return K
        if fc == "bottom":
            return None
        if fc == "front":
            return K if y <= 1 or (y == 2 and x <= 2) else None
        if fc == "right":
            return K if y <= 2 else None
        if fc == "left":
            return K if y <= 1 else None
        if fc == "back":
            return K if y <= 1 or (y == 2 and x >= 5) else None
        return None
    return lambda s: s.paint("head", f, layer=1)


def flush(cheek, nose):
    """a drinker's glow: a red cheek under each eye's outer corner and a red nose"""
    def f(fc, x, y, w, h):
        if fc != "front" or y != 5:
            return None
        if x in (1, 6):
            return C(cheek)
        if x in (3, 4):
            return C(nose)
        return None
    return lambda s: s.paint("head", f)


def lids(col):
    """heavy eyelids: the row over the eyes a shade of the skin (sleepy, tipsy)"""
    return lambda s: s.paint("head", lambda fc, x, y, w, h: C(col) if fc == "front" and y == 3 and x in (1, 2, 5, 6) else None)


def tie_band(col):
    """a necktie tied round the head (넥타이 머리띠): row 2 all round on the outer layer, the knot and two ends
    hanging at the back"""
    T = C(col)

    def f(fc, x, y, w, h):
        if fc in ("top", "bottom"):
            return None
        if y == 2:
            return T
        if fc == "back" and x in (4, 5) and 3 <= y <= 5 and not (x == 4 and y == 5):
            return shade(T, 0.85)
        return None
    return lambda s: s.paint("head", f, layer=1)


def neck_towel(col, stripe):
    """a towel round the neck (body outer layer): over the shoulders, round the collar, the two ends hanging
    down the chest with a stripe at the end"""
    T, S = C(col), C(stripe)

    def f(fc, x, y, w, h):
        if fc == "top":
            return T if y in (1, 2) else None
        if fc == "front":
            if y == 0:
                return T
            if x in (1, 2, 5, 6) and y <= 3:
                return S if y == 3 else T
            return None
        if fc in ("back", "left", "right"):
            return T if y == 0 else None
        return None
    return lambda s: s.paint("body", f, layer=1)


def plaid(base, line, cross):
    """a check on a regular 4 px grid: lines every 4th column and row, darker where they cross"""
    B, L, X = C(base), C(line), C(cross)
    return lambda x, y: X if (x % 4 == 1 and y % 4 == 1) else (L if (x % 4 == 1 or y % 4 == 1) else B)


def hiking_pack(s, col, strap, buckle):
    """a mountain pack: the bag up the whole back (a lid, a strap down the middle, a bottom pocket), shoulder
    straps down the front joined by a chest strap and a hip belt"""
    B, S, K = C(col), C(strap), C(buckle)

    def f(fc, x, y, w, h):
        if fc == "back":
            if 1 <= x <= 6 and y <= 10:
                if y <= 2:
                    return shade(B, 0.82) if y == 2 else shade(B, 1.08)   # the lid
                if x in (3, 4) and y <= 6:
                    return S                                               # the compression strap
                if 2 <= x <= 5 and 7 <= y <= 9:
                    return shade(B, 0.88) if y == 7 else shade(B, 1.06)   # the pocket
                return B
            return None
        if fc == "top":
            return S if x in (1, 6) else (B if y >= 2 and 1 <= x <= 6 else None)
        if fc == "front":
            if x in (1, 6) and y <= 8:
                return S
            if y in (3, 8) and 2 <= x <= 5:
                return K if x in (3, 4) else S                             # the chest strap, the hip belt
            return None
        if fc in SIDES and y == 8:
            return S
        return None
    s.paint("body", f, layer=1)


def vest(s, col, pockets, open_cols=(3, 4), trim=None):
    """a fishing / work vest on the body's outer layer: no sleeves, open down the middle, pockets on a grid
    (two rows of two on each side), a big pocket on the back"""
    V, P = C(col), C(pockets)
    a, b = open_cols

    def f(fc, x, y, w, h):
        if fc == "front":
            if a <= x <= b:
                return None
            if trim and x in (a - 1, b + 1):
                return C(trim)
            if y in (4, 5, 7, 8) and x in (0, 1, 6, 7):
                return shade(P, 0.85) if y in (4, 7) else P                # pocket flaps and pockets
            return V
        if fc == "back" and 2 <= x <= 5 and 3 <= y <= 7:
            return shade(V, 0.9) if y == 3 else shade(V, 1.05)
        if fc == "bottom":
            return None
        return V
    s.paint("body", f, layer=1)


def boots(s, col, rows=3, top=None):
    """boots over the trousers' bottom rows, a lighter band at the top if given"""
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: ((C(top) if (top and y == 12 - rows) else C(col))
                                            if fc == "bottom" or (fc != "top" and y >= 12 - rows) else None))


def heart(col, at=(2, 2)):
    """a heart (5 x 4) on the chest, its top left corner at `at`"""
    H = C(col)
    cx, cy = at
    shape = ("xx.xx", "xxxxx", ".xxx.", "..x..")

    def f(fc, x, y, w, h):
        if fc != "front":
            return None
        dx, dy = x - cx, y - cy
        if 0 <= dy < 4 and 0 <= dx < 5 and shape[dy][dx] == "x":
            return H
        return None
    return f


# ---------------------------------------------------------------------------------------------
# the second batch
# ---------------------------------------------------------------------------------------------

def farmer(v):
    """농부: tanned, a towel round the neck, work clothes"""
    if v == 1:
        # 아저씨: a straw hat, a red plaid shirt under denim overalls, green rubber boots
        s, sk = Skin(), "b9784e"
        head(s, sk, "2a2320", "short", mouth="8a4a3e", extra=[straw_hat("6a8a3a")])
        shirt = plaid("c8463a", "9a3028", "6a2018")
        den = C("3f63a0")

        def b(fc, x, y, w, h):
            if fc == "front":
                if 2 <= x <= 5 and y >= 3:
                    if y == 3:
                        return C("e0c050") if x in (2, 5) else shade(den, 0.85)   # buckles, the bib's edge
                    if y in (5, 6) and x in (3, 4):
                        return shade(den, 0.88)                 # the bib pocket
                    return den
                if x in (2, 5) and y <= 2:
                    return den                                  # straps
                if y >= 8:
                    return den
            if fc == "back" and ((x in (2, 5) and y <= 7) or y >= 8):
                return den
            if fc in SIDES and y >= 8:
                return den
            return shirt(x, y)
        body(s, b)
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: C(sk) if fc == "bottom" or y > 7 else
                    (shade(C("c8463a"), 0.8) if y == 7 else shirt(x, y)))   # rolled sleeves
        legs(s, "3f63a0", "2e6b3a", shoe_rows=3)
        neck_towel("f4f2ea", "6aa0d8")(s)
        return s
    if v == 2:
        # 아주머니: a pink sun visor, a flowery 몸빼, a work shirt with arm covers (토시), a towel
        s, sk = Skin(slim=True), "c98a5c"
        head(s, sk, "2e221e", "bob", mouth="9a4a40", blush="d8826a", extra=[visor("e86a9a")])
        shirt = "e9e2cf"
        mom = flowers("6a4aa0", "f4b4d8", "ffe08a", leaf="8ac77a")

        def b(fc, x, y, w, h):
            if fc == "front" and y == 0 and x in (3, 4):
                return C(sk)
            if fc == "front" and x == 4 and 2 <= y <= 9 and y % 3 == 2:
                return shade(C(shirt), 0.82)                    # buttons
            return C(shirt)
        body(s, b)
        arms(s, shirt, sk, sleeve=10)
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: (C("5a9ad0") if y in (5, 9) else C("8cc0e8"))
                    if 5 <= y <= 9 and fc not in ("top", "bottom") else None, 1)
        for lg in ("rleg", "lleg"):
            def f(fc, x, y, w, h):
                if fc == "bottom" or y == 11:
                    return C("3a3a40")
                if fc == "top":
                    return mom(0, 1)
                if y == 10:
                    return shade(mom(1, 2), 0.82)               # the gathered cuff
                return mom(x, y)
            s.paint(lg, f)
        neck_towel("fafafa", "e86a9a")(s)
        return s
    # 할아버지: white hair under a green work cap, white brows and a mustache, smiling eyes, a beige work vest over
    # a grey shirt, khaki trousers, black rubber boots, a towel
    s, sk = Skin(), "b07048"
    head(s, sk, "e8e4dc", "short", brows="f2efe8", mouth="7a3a30",
         extra=[happy_eyes("3a2a22"), mustache("eeeae2"), cap("3f7a4a", "2f5a38")])
    shirt = "8a9098"
    body(s, lambda fc, x, y, w, h: C(sk) if fc == "front" and y == 0 and x in (3, 4) else C(shirt))
    vest(s, "c9b48a", "b09a6e", open_cols=(3, 4))
    arms(s, shirt, sk, sleeve=10, cuff=shade_hex(shirt, 0.85))
    legs(s, "6a5e4a", "1e1e22", shoe_rows=3)
    neck_towel("f4f2ea", "3f7a4a")(s)
    return s


def hiker(v):
    """등산객: a bright mountain jacket, a hat, a big pack, hiking trousers and boots"""
    if v == 1:
        s, sk, jk = Skin(), "e2ab84", "d63a32"
        head(s, sk, "2a2522", "short", extra=[cap("23324f", "1a2238"), sunglasses("26262e", "3a3a44", "6a7a8a")])
        trousers, boot = "4e5560", "6a4a30"
        pack = ("2a3d66", "15151a", "e0b030")
    elif v == 2:
        s, sk, jk = Skin(slim=True), "f0c4a0", "f08a2a"
        head(s, sk, "3a2620", "long", mouth="c05a50", blush="f2a090", extra=[visor("ffffff")])
        trousers, boot = "26262c", "8a5a3a"
        pack = ("6a3a8a", "26262c", "e8e8e8")
    else:
        s, sk, jk = Skin(), "d39a72", "1f9e9a"
        head(s, sk, "1c1a1a", "short", extra=[bucket_hat("6f7f4a"), glasses("2a2a2a")])
        trousers, boot = "6a604c", "3a2a20"
        pack = ("d6782a", "2a2a2a", "f0d040")
    J = C(jk)

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and 2 <= x <= 5:
                return shade(J, 0.8)                           # the stand-up collar
            if x == 4 and y >= 1:
                return shade(J, 0.8)                           # the zip
            if y == 11:
                return shade(J, 0.85)
        if fc in SIDES and x == 1 and y <= 9:
            return shade(J, 0.78)                              # a contrast seam down the side
        return J
    body(s, b)
    arms(s, jk, sk, sleeve=10, cuff=shade_hex(jk, 0.8))
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: shade(J, 0.78) if fc in SIDES and x == 1 and y < 10 else None)
    hiking_pack(s, *pack)
    legs(s, trousers, boot, shoe_rows=2)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: C("e8e0d0") if y == 11 and fc not in ("top", "bottom") else None)   # light soles
    return s


def fisher(v):
    """낚시꾼: a vest full of pockets, a hat, boots or waders"""
    if v == 1:
        # chest waders (olive) with braces, a khaki vest over them, an olive bucket hat
        s, sk = Skin(), "d9a07a"
        head(s, sk, "2a2320", "short", mouth="8a4a40", extra=[bucket_hat("6e7a3a")])
        shirt, wader = "d8d2c0", "3e5a3a"

        def b(fc, x, y, w, h):
            if fc in ("front", "back") and (y >= 4 or (x in (2, 5) and y <= 3)):
                return C(wader)                                 # the waders' bib and braces
            if fc in SIDES and y >= 4:
                return C(wader)
            return C(shirt)
        body(s, b)
        vest(s, "b8a87a", "9a8a5e", open_cols=(2, 5))
        arms(s, shirt, sk, sleeve=8, cuff=shade_hex(shirt, 0.85))
        legs(s, wader, "1e2a1e", shoe_rows=1)
        return s
    if v == 2:
        # 아저씨 in sunglasses and a cap, a beige vest over a navy tee, grey trousers, short boots
        s, sk = Skin(), "c48a62"
        head(s, sk, "1e1a18", "short", mouth="7a3e36",
             extra=[sunglasses("1a1e26", "2a2a30", "5a7a9a"), cap("e8e2d4", "c8c0ae")])
        tee = "2a3a5a"
        body(s, lambda fc, x, y, w, h: C(sk) if fc == "front" and y == 0 and x in (3, 4) else C(tee))
        vest(s, "d6c8a0", "bba982", open_cols=(3, 4), trim="a89870")
        arms(s, tee, sk, sleeve=4)
        legs(s, "7a7e86", "2a2a2a", shoe_rows=1)
        boots(s, "3a3a3a", rows=3, top="5a5a5a")
        return s
    # a young angler: a teal vest over a white long tee, a navy bucket hat, yellow rain boots
    s, sk = Skin(slim=True), "f0c6a4"
    head(s, sk, "4a2e22", "bob", mouth="c0605a", blush="f2a99a", extra=[bucket_hat("2a3a66")])
    shirt = "f2f2ee"
    body(s, lambda fc, x, y, w, h: C(shirt))
    vest(s, "2f8f8a", "247370", open_cols=(3, 4))
    arms(s, shirt, sk, sleeve=10)
    legs(s, "3d5f95", "f2c02a", shoe_rows=3)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(C("f2c02a"), 0.85) if y == 9 and fc not in ("top", "bottom") else None)
    return s


def soldier(v):
    """군인 (on leave): plain olive, a buzz cut, a cap or beret, no insignia"""
    olive, boot = "56603a", "141414"
    O = C(olive)
    if v == 1:
        # the everyday uniform with a patrol cap: pocket flaps, buttons, a belt, boots
        s, sk = Skin(), "e0a882"
        head(s, sk, "1a1a1a", "buzz", mouth="8a4a40", extra=[cap(olive, shade_hex(olive, 0.75), gap=False)])

        def b(fc, x, y, w, h):
            if fc == "front":
                if y == 0 and x in (3, 4):
                    return C(sk)                                # the open collar
                if y == 0 and x in (2, 5):
                    return shade(O, 0.8)
                if y == 3 and x in (1, 2, 5, 6):
                    return shade(O, 0.84)                       # breast pocket flaps
                if y == 9 and x in (3, 4):
                    return C("9a9a88")                          # the buckle
                if x == 4 and 1 <= y <= 8 and y % 3 == 1:
                    return shade(O, 0.75)                       # buttons
            if y == 9 and fc not in ("top", "bottom"):
                return C("2a2a22")                              # the belt
            return O
        body(s, b)
        arms(s, olive, sk, sleeve=10, cuff=shade_hex(olive, 0.8))
        legs(s, olive, boot, shoe_rows=3)
        return s
    if v == 2:
        # the dress uniform: a dark green jacket with brass buttons, a white shirt and a dark tie, a black beret
        s, sk = Skin(), "f0c09a"
        head(s, sk, "1a1a1a", "buzz", mouth="9a5048", extra=[beret("44523a")])
        coat = "37462e"
        body(s, lambda fc, x, y, w, h: C("1e2224") if fc == "front" and 3 <= x <= 4 and 1 <= y <= 5 else C("f4f4f0"))
        jacket(s, coat, open_rows=5, open_cols=(3, 4), lapel=shade_hex(coat, 0.8))
        s.paint("body", lambda fc, x, y, w, h: C("d4b04a") if fc == "front" and x == 3 and y in (6, 8, 10) else None, 1)   # brass buttons
        s.paint("body", lambda fc, x, y, w, h: shade(C(coat), 0.8) if fc == "front" and y == 3 and x in (0, 1, 6, 7) else None, 1)   # pocket flaps
        arms(s, "f4f4f0", sk, sleeve=10, layer_cloth=coat)
        legs(s, coat, "0e0e0e", shoe_rows=1)
        return s
    # off duty: an olive field jacket open over a sand tee, a duffel strap across the chest, a cap, boots
    s, sk = Skin(), "c88c62"
    head(s, sk, "1a1a1a", "buzz", mouth="7a3e36", pupils="side", extra=[cap("3a4230", "2a3022", gap=False)])
    body(s, lambda fc, x, y, w, h: C("cdbb92"))
    jacket(s, olive, open_rows=12, open_cols=(3, 4), lapel=shade_hex(olive, 0.82))

    def strap(fc, x, y, w, h):
        if fc == "front" and 1 <= y <= 8 and x == 8 - y:
            return C("2a2a22")                                  # the duffel strap, shoulder to hip
        if fc == "back" and 1 <= y <= 8 and x == y - 1:
            return C("2a2a22")
        return None
    s.paint("body", strap, 1)
    arms(s, olive, sk, sleeve=10, layer_cloth=olive)
    legs(s, olive, boot, shoe_rows=3)
    return s


def couple(v):
    """커플: pastel couple wear with a heart on the chest"""
    if v == 1:
        # the boyfriend: a mint couple hoodie with a white heart, light jeans, white trainers
        s, sk = Skin(), "f0c6a2"
        head(s, sk, "2a1e18", "part", mouth="b0605a")
        top, hc = "9ee0c8", "ffffff"
    elif v == 2:
        # the girlfriend: the same mint hoodie, long hair, a pink hairpin, a white skirt
        s, sk = Skin(slim=True), "f6d0b2"
        head(s, sk, "3a2620", "long", mouth="d0606a", blush="f8b0b0",
             extra=[lambda s: s.paint("head", lambda fc, x, y, w, h: C("ff8fb0") if fc == "front" and y == 1 and x in (5, 6) else None, 1)])
        top, hc = "9ee0c8", "ffffff"
    else:
        # a lavender couple tee with a pink heart, a bob with a pink bow, denim shorts
        s, sk = Skin(slim=True), "f2cbab"
        head(s, sk, "5a3a2a", "bob", mouth="d0606a", blush="f8b0b0",
             extra=[lambda s: s.paint("head", lambda fc, x, y, w, h: C("ff7aa0") if fc == "front" and y == 1 and x in (1, 2) else None, 1)])
        top, hc = "c8b4ec", "ff7aa0"
    H = C(top)
    hrt = heart(hc)

    def b(fc, x, y, w, h):
        c = hrt(fc, x, y, w, h)
        if c:
            return c
        if fc == "front":
            if y == 0 and 2 <= x <= 5:
                return shade(H, 0.8)                            # the neckline
            if v < 3 and 8 <= y <= 9 and 2 <= x <= 5:
                return shade(H, 0.9)                            # the pouch pocket
            if y == 11:
                return shade(H, 0.85)
        return H
    body(s, b)
    if v < 3:
        s.paint("body", lambda fc, x, y, w, h: shade(H, 0.88) if fc == "back" and y <= 3 and 1 <= x <= 6 and not (y == 3 and x in (1, 6)) else None, 1)   # the hood
        arms(s, top, sk, sleeve=10, cuff=shade_hex(top, 0.85))
    else:
        arms(s, top, sk, sleeve=4)
    if v == 1:
        jeans = "8fb0d8"
        legs(s, jeans, "f6f6f6", shoe_rows=1)
        s.paint("rleg", lambda fc, x, y, w, h: shade(C(jeans), 0.9) if fc == "front" and x == 0 and y < 11 else None)
        s.paint("lleg", lambda fc, x, y, w, h: shade(C(jeans), 0.9) if fc == "front" and x == 3 and y < 11 else None)
    elif v == 2:
        skirt = "f4f4f0"
        legs(s, skirt, "f6f6f6", skin=sk, bare=5, shoe_rows=1, sock="ffffff")
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: (shade(C(skirt), 0.9) if y == 4 else C(skirt)) if y <= 4 and fc not in ("top", "bottom") else None, 1)
    else:
        jeans = "8fb0d8"
        legs(s, jeans, "f6f6f6", skin=sk, bare=4, shoe_rows=1, sock="ffffff")
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: shade(C(jeans), 0.85) if y == 3 and fc not in ("top", "bottom") else None)   # rolled hem
    return s


def drunk(v):
    """술 취한 아저씨: on the way home from 회식: flushed, rumpled, the tie gone astray (funny, not sad)"""
    if v == 1:
        # the classic: the tie pulled loose and swung aside, the shirt untucked on one side, messy hair, red nose
        s, sk = Skin(), "eab48e"
        head(s, sk, "2a2724", "messy", mouth="a04a44", pupils="side", extra=[flush("f08a7a", "e0645a"), lids("d49a78")])
        shirt = C("f2f2ee")
        T = C("c0392b")

        def b(fc, x, y, w, h):
            if fc == "front":
                if y == 0 and x in (3, 4):
                    return C(sk)                                # the open collar
                if (x, y) in ((4, 1), (4, 2), (5, 3), (5, 4), (6, 5), (6, 6)):
                    return shade(T, 0.8) if y == 1 else T       # the tie, knot low and swung aside
                if y == 11:
                    return shirt if x >= 4 else C("2a2a2a")     # the tail hangs out on one side
            if fc == "back" and y == 11:
                return shirt if x <= 3 else C("2a2a2a")
            return shirt
        body(s, b)
        arms(s, "f2f2ee", sk, sleeve=7)
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: shade(shirt, 0.86) if y in (6, 7) and fc not in ("top", "bottom") else None)   # rolled cuffs
        legs(s, "3a3e48", "1a1a1a")
        return s
    if v == 2:
        # the tie round the head (넥타이 머리띠), a blue shirt with braces, happy closed eyes, rosy
        s, sk = Skin(), "eeb08a"
        head(s, sk, "1e1c1a", "short", mouth="a83c3c", extra=[happy_eyes("3a2420"), flush("f0806e", "e0584e"), tie_band("e0b040")])
        S, Br = C("a8c4e4"), C("7a2a2a")

        def b(fc, x, y, w, h):
            if fc == "front":
                if y <= 1 and x in (3, 4):
                    return C(sk)                                # two buttons open
                if x in (1, 6):
                    return Br                                   # braces
                if x == 4 and 2 <= y <= 10 and y % 3 == 2:
                    return shade(S, 0.82)
                if y == 11:
                    return C("26262a")
            if fc == "back":
                if y <= 10 and x == (3 if y < 4 else 2) or y <= 10 and x == (4 if y < 4 else 5):
                    return Br                                   # braces, a Y at the back
                if y == 11:
                    return C("26262a")
            return S
        body(s, b)
        arms(s, "a8c4e4", sk, sleeve=10, cuff=shade_hex("a8c4e4", 0.85))
        legs(s, "45484f", "1a1a1a")
        return s
    # the 부장님: balding, glasses, a beige jacket open, the tie flipped over the shoulder
    s, sk = Skin(), "e8a882"
    head(s, sk, "4a3e36", "bald", brows="3a302a", mouth="a04a44",
         extra=[flush("f08070", "e05a50"), lids("cc8e6c"), glasses("6a5a3a")])
    shirt, coat = C("f6f2ea"), "b8a684"
    T = C("2e5fb0")

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and x in (3, 4):
                return C(sk)
            if (x, y) == (4, 1):
                return shade(T, 0.8)                            # the knot; the blade went over the shoulder
            if y == 11:
                return C("2a2a2a")
        return shirt
    body(s, b)
    jacket(s, coat, open_rows=12, open_cols=(2, 5), lapel=shade_hex(coat, 0.82))
    s.paint("body", lambda fc, x, y, w, h: T if (fc == "back" and x == 5 and y <= 4) or (fc == "top" and x == 5) else None, 1)
    s.paint("body", lambda fc, x, y, w, h: shade(C(coat), 0.85) if fc == "front" and (x, y) in ((0, 6), (7, 8)) else None, 1)   # rumples
    arms(s, "f6f2ea", sk, sleeve=9, cuff="f6f2ea", layer_cloth=coat)
    legs(s, "5a5650", "2a1e16")
    return s


# ---------------------------------------------------------------------------------------------
# helpers for the third batch
# ---------------------------------------------------------------------------------------------

def gloves(s, col, cuff=None):
    """work gloves over the hands (rows 10-11 and the bottom), a cuff row on top if given"""
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: ((C(cuff) if cuff and y == 10 else C(col))
                                           if fc == "bottom" or (fc != "top" and y >= 10) else None))


def cargo(s, col, pocket):
    """cargo trousers: a flap pocket on the outer side of each thigh (rows 4-6) and a seam down the side"""
    P = C(pocket)
    for lg in ("rleg", "lleg"):
        def f(fc, x, y, w, h, lg=lg):
            outer_side = "right" if lg == "rleg" else "left"
            if fc == outer_side and 1 <= x <= 2 and 4 <= y <= 6:
                return shade(P, 0.85) if y == 4 else P
            return None
        s.paint(lg, f)


def scanner(cord):
    """a handheld parcel scanner on a cord round the neck: a dark body with a lit screen on the chest"""
    def f(fc, x, y, w, h):
        if fc == "front":
            if (y <= 2 and x in (2, 5)) or (y == 3 and x in (2, 5)):
                return C(cord)
            if 4 <= y <= 6 and 2 <= x <= 5:
                if y == 4 and x in (3, 4):
                    return C("7fe0a0")                    # the screen
                if y == 6 and x == 5:
                    return C("e05a3a")                    # the trigger light
                return C("2e3036")
            return None
        if fc == "back" and y == 0 and 2 <= x <= 5:
            return C(cord)
        if fc == "top" and x in (2, 5):
            return C(cord)
        return None
    return lambda s: s.paint("body", f, layer=1)


def waist_pouch(belt, pouch):
    """a belt round the hips (row 9) with a pouch on the wearer's left front (outer layer)"""
    Bt, P = C(belt), C(pouch)

    def f(fc, x, y, w, h):
        if fc in ("top", "bottom"):
            return None
        if fc == "front" and 5 <= x <= 7 and 8 <= y <= 11:
            return shade(P, 0.82) if y == 8 else P
        if y == 9:
            return Bt
        return None
    return lambda s: s.paint("body", f, layer=1)


def big_eyes(col, glint="ffffff"):
    """kids' eyes: 2 x 2 dark eyes (rows 3-4) with a glint in the top corner, no brows"""
    E, G = C(col), C(glint)

    def f(fc, x, y, w, h):
        if fc == "front" and y in (3, 4) and x in (1, 2, 5, 6):
            return G if (y == 3 and x in (2, 6)) else E
        return None
    return lambda s: s.paint("head", f)


def kid_hat(col, strap=None):
    """a round kindergarten hat: crown and rows 0-1, a soft lighter brim in row 2 all round, an elastic chin
    strap down the sides if given"""
    K = C(col)

    def f(fc, x, y, w, h):
        if fc == "top":
            return shade(K, 1.08) if 2 <= x <= 5 and 2 <= y <= 5 else K
        if fc == "bottom":
            return C(strap) if strap and y == 7 else None
        if fc == "front" and y == 1 and x in (3, 4):
            return C("ffffff")                            # the kindergarten badge
        if y <= 1:
            return K
        if y == 2:
            return shade(K, 1.15) if fc == "front" else shade(K, 0.88)   # the brim
        if strap and fc in SIDES and fwd(fc, x, 8) == 2 and y >= 3:
            return C(strap)
        return None
    return lambda s: s.paint("head", f, layer=1)


def pigtails(col, tie):
    """two bunches sticking out at the sides behind the ears (outer layer), a hair tie on each"""
    H, T = C(col), C(tie)

    def f(fc, x, y, w, h):
        if fc in SIDES:
            g = fwd(fc, x, 8)
            if 5 <= g <= 6 and 3 <= y <= 6:
                return T if y == 3 else (shade(H, 0.88) if y == 6 else H)
        if fc == "back" and x in (0, 7) and 3 <= y <= 6:
            return T if y == 3 else H
        return None
    return lambda s: s.paint("head", f, layer=1)


def ponytail(col, tie, length=3):
    """hair tied at the back: a tie at row 3 of the head's back, the tail down the head (outer layer) and on
    over the top rows of the body's back"""
    H, T = C(col), C(tie)

    def hf(fc, x, y, w, h):
        if fc == "back" and x in (3, 4) and y >= 3:
            return T if y == 3 else (shade(H, 0.9) if x == 4 else H)
        return None

    def bf(fc, x, y, w, h):
        if fc == "back" and x in (3, 4) and y < length:
            return shade(H, 0.9) if (x == 4 or y == length - 1) else H
        return None

    def apply(s):
        s.paint("head", hf, layer=1)
        s.paint("body", bf, layer=1)
    return apply


def bun(col, tie):
    """a bun at the back of the head (outer layer, rows 1-4) with a tie under it"""
    H, T = C(col), C(tie)

    def f(fc, x, y, w, h):
        if fc == "back" and 2 <= x <= 5 and 1 <= y <= 4:
            if y == 4:
                return T if x in (3, 4) else None
            if (x in (2, 5)) and y in (1, 3):
                return None                               # rounded corners
            return shade(H, 0.85) if (x + y) % 3 == 0 else H
        if fc == "top" and 3 <= x <= 4 and y == 7:
            return H
        return None
    return lambda s: s.paint("head", f, layer=1)


def beanie(col, cuff=None, pom=None):
    """a knit beanie on the outer layer: the crown and rows 0-2, a ribbed turned-up cuff in row 2, an optional
    pompom on the top"""
    K = C(col)
    Cf = C(cuff) if cuff else shade(K, 0.85)

    def f(fc, x, y, w, h):
        if fc == "top":
            if pom and 3 <= x <= 4 and 3 <= y <= 4:
                return C(pom)
            return shade(K, 0.9) if x % 2 else K
        if fc == "bottom":
            return None
        if y <= 1:
            return shade(K, 0.9) if x % 2 else K               # the knit ribs
        if y == 2:
            return shade(Cf, 0.9) if x % 2 else Cf
        return None
    return lambda s: s.paint("head", f, layer=1)


def beard(col):
    """a short full beard (inner layer): the chin row, the jaw at the sides of the mouth, round the jaw on the sides"""
    B = C(col)

    def f(fc, x, y, w, h):
        if fc == "front":
            if y == 7 or (y in (5, 6) and x in (0, 7)) or (y == 6 and x in (1, 2, 5, 6)):
                return B
            return None
        if fc in SIDES and fwd(fc, x, 8) <= 2 and y >= 5:
            return B
        if fc == "bottom":
            return B
        return None
    return lambda s: s.paint("head", f)


def fleece(col, yoke):
    """a fleece: a soft regular texture, a contrast yoke over the shoulders (rows 0-3), a short zip (half zip)"""
    F, Y = C(col), C(yoke)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return Y
        if fc == "front" and x == 4 and y <= 4:
            return shade(Y, 0.75) if y >= 1 else shade(Y, 0.85)
        if fc == "front" and y == 0 and 2 <= x <= 5:
            return shade(Y, 0.85)                            # the collar
        if y <= 3:
            return Y if y < 3 else shade(Y, 0.85)
        if fc == "front" and x == 1 and y in (5, 6):
            return shade(F, 0.85)                            # a chest pocket zip
        if y == 11:
            return shade(F, 0.85)                            # the hem
        return shade(F, 0.94) if (x * 3 + y * 5) % 7 == 0 else F
    return f


def camp_pack(s, col, strap, mat, mat_line):
    """a tall camping pack on the outer layer: a rolled sleeping mat strapped across the top (body rows 0-2 on
    the back, round onto the top and the sides, the roll's spiral at its ends), the bag below it with side
    bulk, two compression straps, a front pocket and a mug clipped on; shoulder straps and a sternum strap"""
    B, S, M, L = C(col), C(strap), C(mat), C(mat_line)

    def f(fc, x, y, w, h):
        if fc == "back":
            if y <= 2:
                if x in (2, 5):
                    return S                                         # the straps round the roll
                if y == 1 and x in (0, 7):
                    return L                                         # the spiral at the ends
                return shade(M, 1.08) if y == 0 else (shade(M, 0.85) if y == 2 else M)
            if y <= 10:
                if y == 3:
                    return shade(B, 0.8) if 1 <= x <= 6 else None   # the lid's edge
                if x in (0, 7):
                    return None
                if x in (2, 5) and y <= 6:
                    return S                                         # compression straps
                if 2 <= x <= 5 and 7 <= y <= 10:
                    return shade(B, 0.86) if y == 7 else shade(B, 1.06)   # the front pocket
                return B
            return None
        if fc == "top":
            if x in (1, 6):
                return S
            return M if y >= 1 else None
        if fc in SIDES:
            g = fwd(fc, x)
            if y <= 2 and g >= 2:
                return L if (g == 2 and y == 1) or (g == 3 and y in (0, 2)) else M   # the roll end
            if 3 <= y <= 10 and g >= 2:
                if fc == "left" and y in (8, 9) and g == 2:
                    return C("d8d8d0") if y == 8 else C("a0a0a0")    # a tin mug clipped on
                return B
            return None
        if fc == "front":
            if x in (1, 6) and y <= 9:
                return S
            if y == 4 and 2 <= x <= 5:
                return S if x in (2, 5) else C("e8e0c8")             # the sternum strap
            return None
        return None
    s.paint("body", f, layer=1)


def hood(col):
    """a close-fitting wetsuit hood on the head's outer layer: all over the top, the back and the sides, the
    face open from row 2 down between the edge columns"""
    K = C(col)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "front":
            return K if (y <= 1 or x in (0, 7)) else None
        if fc == "top":
            return shade(K, 1.15) if (x + y) % 5 == 0 else K
        return K
    return lambda s: s.paint("head", f, layer=1)


def dive_mask(rim, glass, worn=False, twin=False):
    """a diving mask on the head's outer layer. Pushed up: the glass on the forehead (rows 1-2), the strap
    round the head in row 2. Worn: the rim round rows 2-5 with glass over the face, the eyes left clear (the
    inner layer shows through), the strap round the head in row 3. twin: two round eyepieces (족은눈) instead
    of one wide glass (왕눈)."""
    R, G = C(rim), C(glass)

    def f(fc, x, y, w, h):
        if fc in ("top", "bottom"):
            return None
        if fc != "front":
            return R if y == (3 if worn else 2) else None
        if not worn:
            if twin:
                if y in (1, 2) and x in (1, 2, 5, 6):
                    return shade(G, 1.25) if (y == 1 and x in (1, 5)) else G
                if y in (1, 2) and x in (0, 3, 4, 7):
                    return R
                return None
            if y == 1 and 1 <= x <= 6:
                return shade(G, 1.25) if x in (2, 3) else G
            if y == 2 and 1 <= x <= 6:
                return R
            if y in (1, 2) and x in (0, 7):
                return R
            if y == 0 and 1 <= x <= 6:
                return R
            return None
        if y in (2, 5) and 0 <= x <= 7:
            return R if not (y == 5 and x in (3, 4)) else None    # the nose shows under the mask
        if y in (3, 4) and x in (0, 7):
            return R
        if y == 3 and 1 <= x <= 6:
            return shade(G, 1.25) if x in (1, 5) else G
        if y == 4 and x in (3, 4):
            return G
        return None
    return lambda s: s.paint("head", f, layer=1)


def weight_belt(belt, lead):
    """a diver's weight belt round the waist (row 8) with lead blocks on it (outer layer)"""
    Bt, Ld = C(belt), C(lead)

    def f(fc, x, y, w, h):
        if fc in ("top", "bottom") or y not in (8, 9):
            return None
        if fc == "front" and x in (3, 4):
            return C("c8c8c0") if y == 8 else None             # the buckle
        if fc in ("front", "back") and x in (0, 1, 6, 7) or (fc in SIDES and x in (1, 2)):
            return Ld if y == 8 else shade(Ld, 0.8)            # the lead blocks
        return Bt if y == 8 else None
    return lambda s: s.paint("body", f, layer=1)


def net_bag(mesh, buoy):
    """a 망사리 (net bag) on the back hanging under an orange 테왁 float: the float rows 0-2, the mesh below as a
    see-through checker (outer layer)"""
    M, Bu = C(mesh), C(buoy)

    def f(fc, x, y, w, h):
        if fc != "back":
            if fc == "top" and x in (1, 6):
                return C("2a2a2a")
            if fc == "front" and x in (1, 6) and y <= 2:
                return C("2a2a2a")                          # the cord over the shoulders
            return None
        if 1 <= y <= 3 and 1 <= x <= 6 and not (y in (1, 3) and x in (1, 6)):
            return shade(Bu, 1.15) if (y == 1 and x in (2, 3)) else Bu
        if 4 <= y <= 10 and 1 <= x <= 6:
            if y == 4:
                return M
            return M if (x + y) % 2 == 0 else None
        if y == 11 and 2 <= x <= 5:
            return shade(M, 0.85)
        return None
    return lambda s: s.paint("body", f, layer=1)


DIGITS = {"0": ("###", "#.#", "#.#", "#.#", "###"), "1": (".#.", "##.", ".#.", ".#.", "###"),
          "2": ("###", "..#", "###", "#..", "###"), "3": ("###", "..#", ".##", "..#", "###"),
          "4": ("#.#", "#.#", "###", "..#", "..#"), "5": ("###", "#..", "###", "..#", "###"),
          "6": ("###", "#..", "###", "#.#", "###"), "7": ("###", "..#", ".#.", ".#.", ".#."),
          "8": ("###", "#.#", "###", "#.#", "###"), "9": ("###", "#.#", "###", "..#", "###")}


def number(num, top=3):
    """a two-digit shirt number (3 x 5 digits) on the body's back: columns 0-2 and 5-7, rows top..top+4.
    Returns a test (x, y) -> True where the number is"""
    a, b = DIGITS[num[0]], DIGITS[num[1]]

    def at(x, y):
        dy = y - top
        if not 0 <= dy < 5:
            return False
        if 0 <= x <= 2:
            return a[dy][x] == "#"
        if 5 <= x <= 7:
            return b[dy][x - 5] == "#"
        return False
    return at


def smile(col):
    """a wide smile: the mouth row 6 four pixels wide, the corners a little lighter"""
    def f(fc, x, y, w, h):
        if fc == "front" and y == 6 and 2 <= x <= 5:
            return shade(C(col), 1.25) if x in (2, 5) else C(col)
        return None
    return lambda s: s.paint("head", f)


def face_paint(c1, c2=None):
    """team colours painted under one eye (the wearer's right cheek, rows 5-6) as two short stripes"""
    def f(fc, x, y, w, h):
        if fc == "front" and x in (1, 2) and y in (5, 6):
            return C(c1) if y == 5 else C(c2 or c1)
        return None
    return lambda s: s.paint("head", f)


def scrubs_top(col, pocket, skin, vneck=True):
    """a scrub top: a V neck, a chest pocket with a pen in it, side vents at the hem"""
    S = C(col)

    def f(fc, x, y, w, h):
        if fc == "front":
            if vneck and ((y == 0 and 2 <= x <= 5) or (y == 1 and x in (3, 4))):
                return C(skin)
            if vneck and ((y == 1 and x in (2, 5)) or (y == 2 and x in (3, 4))):
                return shade(S, 0.8)                     # the V's piping
            if 4 <= y <= 6 and x in (5, 6):
                if y == 4:
                    return C(pocket) if x == 6 else shade(S, 0.82)   # the pen clipped in the pocket
                return shade(S, 0.9)
            if y == 11:
                return shade(S, 0.86)
        if fc in SIDES and y >= 10 and x == 1:
            return shade(S, 0.75)                        # the side vent
        return S
    return f


# ---------------------------------------------------------------------------------------------
# the third batch
# ---------------------------------------------------------------------------------------------

def courier(v):
    """택배기사: a work polo, cargo trousers, gloves, a scanner or a waist pouch (generic colours, no logos)"""
    if v == 1:
        # navy polo with sky shoulder panels, a navy cap, grey cargo trousers, grey gloves, a scanner
        s, sk = Skin(), "e0a882"
        head(s, sk, "1e1c1a", "short", mouth="8a4a40", extra=[cap("23305a", "1a2444")])
        polo, panel, collar = "2a3866", "7fb4e0", "7fb4e0"
        trousers, pocket, shoe = "5c6068", "4e5258", "1e1e22"
        glove, extra = ("a6a8ac", "6a6c72"), [scanner("2a2a2a")]
    elif v == 2:
        # a young woman: a forest green work shirt with lime trims, a ponytail through a green cap,
        # khaki cargo trousers, a waist pouch
        s, sk = Skin(slim=True), "f0c6a4"
        head(s, sk, "2e1e18", "short", mouth="b85a50", blush="f0a898",
             extra=[cap("2f6a42", "24543a"), ponytail("2e1e18", "c8e05a")])
        polo, panel, collar = "2f6a42", "c8e05a", "c8e05a"
        trousers, pocket, shoe = "a89a74", "8e8160", "3a3a3a"
        glove, extra = ("e8e2d0", "c8e05a"), [waist_pouch("2a2a2a", "3a3a3a")]
    else:
        # the veteran: a zipped brown work jacket with a cream collar, no cap, greying hair, a mustache, dark brown
        # cargos, tan gloves, both the scanner and a pouch
        s, sk = Skin(), "b9784e"
        head(s, sk, "5a5450", "short", brows="2e2a26", mouth="7a3e36", extra=[mustache("4a4440")])
        polo, panel, collar = "7a5232", "d8c8a8", "5e3e26"
        trousers, pocket, shoe = "4a3a2c", "3c2f24", "1a1a1a"
        glove, extra = ("c8a06a", "9a7a4e"), [scanner("d8c8a8"), waist_pouch("1e1e1e", "5e3e26")]
    P, Pn = C(polo), C(panel)

    def b(fc, x, y, w, h):
        if fc == "top":
            return Pn
        if fc == "front":
            if v == 3:
                if y == 0 and 1 <= x <= 6:
                    return Pn if x not in (3, 4) else C(sk)  # the cream collar
                if x == 4 and y >= 1:
                    return shade(P, 0.75)                   # the zip
                if y in (5, 6) and x in (0, 1, 6, 7):
                    return shade(P, 0.82) if y == 5 else shade(P, 0.92)   # chest pockets
                if y == 11:
                    return shade(P, 0.82)
                return P
            if y == 0 and x in (1, 2, 5, 6):
                return C(collar)                            # the polo collar
            if x in (3, 4) and y <= 2:
                return C(sk) if (y == 0) else (C("e8e8e8") if (x == 4 and y == 2) else shade(P, 0.85))   # placket
            if y == 1 and x in (0, 7):
                return Pn                                   # the shoulder panel reaching round
            if y == 11:
                return shade(P, 0.85)
        if fc in ("back", "left", "right") and y == 0:
            return Pn
        if fc == "back" and y == 1:
            return Pn
        if fc in SIDES and x == 1 and 2 <= y <= 10:
            return Pn                                       # a contrast seam down the side
        return P
    body(s, b)
    arms(s, polo, sk, sleeve=10 if v == 3 else 4)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: (Pn if fc == "top" or y <= 1 else
                                           (shade(P, 0.82) if y == (9 if v == 3 else 3) else None))
                if fc != "bottom" and y <= (9 if v == 3 else 3) else None)
    gloves(s, *glove)
    legs(s, trousers, shoe, shoe_rows=2)
    cargo(s, trousers, pocket)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: C("e0e0dc") if y == 11 and fc not in ("top", "bottom") else None)   # soles
    for e in extra:
        e(s)
    return s


def kinder(v):
    """유치원생: round hats, smocks, little backpacks, rosy cheeks, big eyes"""
    if v == 1:
        # a boy: a yellow round hat with a chin strap, a sky smock with a white round collar, navy shorts,
        # a red backpack
        s, sk = Skin(), "f6d2b4"
        head(s, sk, "2a1e18", "short", mouth="e0707a", blush="ff9a9a",
             extra=[big_eyes("2a2026"), kid_hat("ffd23a", strap="e8b820")])
        smock, collar, emb = "8ccbf0", "ffffff", "ffd23a"
        shorts, sock, shoe = "2a3a66", "ffffff", "e8443a"
        bag = ("e8443a", "b8302a")
    elif v == 2:
        # a girl: a yellow hat over pigtails with pink ties, a pink smock, white tights, pink shoes, a yellow bag
        s, sk = Skin(slim=True), "f8d8bc"
        head(s, sk, "4a2a1e", "bob", mouth="e0707a", blush="ff9aa8",
             extra=[big_eyes("3a2420"), pigtails("4a2a1e", "ff6a9a"), kid_hat("ffd23a")])
        smock, collar, emb = "ffa8c8", "ffffff", "ff6a9a"
        shorts, sock, shoe = "ffa8c8", "fafafa", "ff6a9a"
        bag = ("ffc83a", "e09a1a")
    else:
        # a boy in an orange round hat, a mint smock with a ducky pocket, a water bottle across the chest,
        # khaki shorts, a blue bag
        s, sk = Skin(), "c88c62"
        head(s, sk, "151214", "messy", mouth="d0606a", blush="ec8a7a",
             extra=[big_eyes("1a1416"), kid_hat("ff8a2a", strap="d86a1a")])
        smock, collar, emb = "8ee0bc", "ffffff", "ffd23a"
        shorts, sock, shoe = "c8b07a", "ffffff", "3a7ae0"
        bag = ("3a7ae0", "2a58a8")
    S = C(smock)

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and 1 <= x <= 6:
                return C(collar)                             # the round collar
            if y == 1 and x in (1, 2, 5, 6):
                return C(collar)
            if v == 2 and y == 1 and x in (3, 4):
                return C("ff6a9a")                           # a ribbon
            if 7 <= y <= 9 and 2 <= x <= 5:
                if v == 3 and (x, y) in ((3, 8), (4, 8), (4, 7)):
                    return C(emb)                            # a ducky on the pocket
                if v == 3 and (x, y) == (5, 7):
                    return C("ff8a2a")                       # its beak
                if v != 3 and (x, y) == (3, 8):
                    return C(emb)                            # a little flower
                return shade(S, 0.9) if y == 7 else shade(S, 1.06)   # the pocket
            if y == 3 and x == 6:
                return C("ffffff")                           # a name tag
        if fc == "back" and y == 0 and 2 <= x <= 5:
            return C(collar)
        return S
    body(s, b)
    # the smock runs down over the top of the legs (outer layer) as a short skirt-like hem
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: (shade(S, 0.85) if y == 1 else S)
                if y <= 1 and fc not in ("top", "bottom") else None, 1)
    arms(s, smock, sk, sleeve=9, cuff=collar)

    def kid_bag(fc, x, y, w, h):
        B, St = C(bag[0]), C(bag[1])
        if fc == "back":
            if 1 <= x <= 6 and 2 <= y <= 8 and not (y in (2, 8) and x in (1, 6)):
                if y == 2 or y == 3:
                    return shade(B, 0.82) if y == 3 else B   # the flap
                if (x, y) in ((3, 5), (4, 5)):
                    return C("ffffff")                       # a reflector
                return B
            return None
        if fc == "top" and x in (1, 6):
            return St
        if fc == "front" and x in (1, 6) and y <= 6:
            return St
        return None
    s.paint("body", kid_bag, 1)
    if v == 3:
        def bottle(fc, x, y, w, h):
            if fc == "front" and 1 <= y <= 7 and x == y - 1 and x != 6:
                return C("3a7ae0")                           # the strap across the chest
            if fc == "front" and x in (6, 7) and 7 <= y <= 10:
                return C("ffd23a") if y == 7 else C("5ac0f0")   # the bottle at the hip
            return None
        s.paint("body", bottle, 1)
    legs(s, shorts, shoe, shoe_rows=1, skin=sk, bare=5 if v != 2 else 12, sock=sock)
    if v == 2:
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: C("fafafa") if 3 <= y <= 10 and fc not in ("top", "bottom") else None)   # tights
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: C("ffffff") if y == 11 and fc == "front" and x in (1, 2) else None)   # velcro
    return s


def camper(v):
    """캠핑족: a beanie or a bucket hat, a fleece or a flannel shirt, a tall pack with a rolled sleeping mat"""
    if v == 1:
        # a mustard fleece with a brown yoke, a grey beanie, olive trousers, a forest pack with an orange mat
        s, sk = Skin(), "e2ab84"
        head(s, sk, "3a2a20", "short", mouth="8a4a40", extra=[beanie("7a7e84", "5e6268")])
        top = fleece("d8a640", "6a4a30")
        sleeve, cuffc = "d8a640", "6a4a30"
        trousers, shoe = "5a6040", "6a4a30"
        pack = ("2f5a3a", "1e2a20", "e8743a", "a84a20")
    elif v == 2:
        # a woman: a cream bucket hat over a long braid, a burgundy fleece with a cream yoke, dark leggings,
        # a navy pack with a lime mat
        s, sk = Skin(slim=True), "f0c6a4"
        head(s, sk, "5a3424", "bob", mouth="c05a50", blush="f0a090",
             extra=[bucket_hat("e8dcc0"), ponytail("5a3424", "e8dcc0", length=0)])
        top = fleece("8a2a3a", "e8dcc0")
        sleeve, cuffc = "8a2a3a", "e8dcc0"
        trousers, shoe = "2e2e36", "8a6a4a"
        pack = ("2a3a5e", "16161c", "b8d84a", "6a8a2a")
    else:
        # a bearded man in a red-and-black flannel shirt, an orange beanie with a pompom, jeans,
        # a tan pack with a blue mat
        s, sk = Skin(), "d39a72"
        head(s, sk, "2a1e16", "short", mouth="8a3e36",
             extra=[beard("2a1e16"), beanie("e8682a", "c85420", pom="f4f0e6")])
        pl = plaid("b8322e", "2a1e1e", "1a1414")
        top = lambda fc, x, y, w, h: (C(sk) if fc == "front" and y == 0 and x in (3, 4) else
                                      (C("2a1e1e") if fc == "front" and x == 4 and 1 <= y <= 10 and y % 3 == 1 else pl(x, y)))
        sleeve, cuffc = None, "2a1e1e"
        trousers, shoe = "3d5f95", "5a3a24"
        pack = ("b89a6a", "4a3a28", "3a7ad0", "1e4a90")
    body(s, top)
    if sleeve:
        arms(s, sleeve, sk, sleeve=10, cuff=cuffc)
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: C(cuffc) if fc != "bottom" and (fc == "top" or y <= 2) else None)   # the yoke over the shoulder
    else:
        pl = plaid("b8322e", "2a1e1e", "1a1414")
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: C(sk) if fc == "bottom" or y >= 10 else
                    (C(cuffc) if y == 9 else pl(x, y)))
    camp_pack(s, *pack)
    legs(s, trousers, shoe, shoe_rows=2)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: (C("d8c8a8") if y == 11 else
                                           (C("e8d8a0") if (fc == "front" and y == 10 and x in (1, 2)) else None))
                if fc not in ("top", "bottom") else None)   # gum soles, laces
    return s


def haenyeo(v):
    """해녀: Jeju women divers in black wetsuits with colour trims, a diving mask, older and warm"""
    suit = "1c1c22"
    Su = C(suit)
    if v == 1:
        # a hood, a single wide mask (왕눈) pushed up on the forehead, orange trims, smiling, a weight belt
        s, sk = Skin(slim=True), "c88a5e"
        head(s, sk, "2a2a2e", "short", mouth="9a4a44", blush="d8806a",
             extra=[happy_eyes("3a2420"), smile("9a4a44"), hood(suit), dive_mask("2a2a2e", "9ad0e8")])
        trim = "f07a2a"
        extra = [weight_belt("3a3a3e", "8a8e94")]
    elif v == 2:
        # no hood: a grey perm, a white cotton headscarf, twin goggles (족은눈) pushed up, pink trims,
        # an orange 테왁 float and net bag on the back
        s, sk = Skin(slim=True), "d39668"
        head(s, sk, "b8b4ae", "perm", mouth="9a4a44", blush="e08a70", brows="8a8680",
             extra=[smile("9a4a44"), lambda s: s.paint("head", lambda fc, x, y, w, h: (C("f4f2ec") if y <= 1 else None)
                                      if fc not in ("top", "bottom") else (C("f4f2ec") if fc == "top" else None), 1),
                    dive_mask("26262a", "a8dce8", twin=True)])
        trim = "e8508a"
        extra = [weight_belt("3a3a3e", "8a8e94"), net_bag("e8a040", "f06a1a")]
    else:
        # the mask worn over the eyes, a hood, yellow trims, a net bag on the back
        s, sk = Skin(slim=True), "b07048"
        head(s, sk, "2a2a2e", "short", mouth="8a3e36", blush="c8705a",
             extra=[smile("8a3e36"), hood(suit), dive_mask("2a2a2e", "8ac8e0", worn=True)])
        trim = "f0c83a"
        extra = [weight_belt("3a3a3e", "8a8e94"), net_bag("d8c070", "f07a2a")]
    T = C(trim)

    def b(fc, x, y, w, h):
        if fc == "top":
            return T if y in (0, 3) else Su
        if fc == "front":
            if v == 2 and y == 0 and 2 <= x <= 5:
                return C(sk)                                 # the neck without a hood
            if y == 0 and x in (0, 1, 6, 7):
                return T                                     # trim over the shoulders
            if x == 4 and 1 <= y <= 6:
                return shade(Su, 1.6) if y == 1 else shade(Su, 1.35)   # the zip
        if fc in ("back", "left", "right") and y == 0:
            return T
        if fc in SIDES and x == 1:
            return T                                         # a stripe down the side
        if fc == "back" and x in (3, 4) and y <= 6:
            return shade(Su, 1.3) if x == 4 else Su          # the back zip
        return Su
    body(s, b)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: (C(sk) if fc == "bottom" or y >= 11 else
                                           (T if (fc in SIDES and x == 1 and y < 10) or y == 10 or fc == "top" else Su)))
    legs(s, suit, "2a2a30", shoe_rows=2)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: T if (fc in SIDES and x == 1 and y < 10) or (y == 9 and fc != "bottom" and fc != "top") else None)
    for e in extra:
        e(s)
    return s


def fan(v):
    """야구팬: an invented team's jersey (number on the back), a cap, a face paint stripe, a towel"""
    if v == 1:
        # teal jersey, orange piping and number 27, a teal cap with an orange bill, an orange cheek stripe
        s, sk = Skin(), "eab08a"
        jer, acc, num, pin = "1f8a8a", "f07a2a", "27", None
        head(s, sk, "1e1a18", "short", mouth="9a4a44",
             extra=[cap(jer, acc), face_paint(acc, jer)])
        trousers, shoe, towel = "3d5f95", "f2f2f2", None
    elif v == 2:
        # a woman: a cream jersey with purple pinstripes and gold number 11, a purple cap, a ponytail, a gold
        # cheek stripe, a purple towel round the neck
        s, sk = Skin(slim=True), "f4cdb0"
        jer, acc, num, pin = "f2eee2", "6a3a9a", "11", "6a3a9a"
        head(s, sk, "3a2620", "short", mouth="d0565e", blush="f5a8a0",
             extra=[cap("6a3a9a", "e0b030"), ponytail("3a2620", "e0b030", length=3), face_paint("e0b030", "6a3a9a")])
        trousers, shoe, towel = "26262c", "fafafa", ("6a3a9a", "e0b030")
        numcol = "e0b030"
    else:
        # 아저씨: a navy jersey with red pinstripes, a white number 52, a red cap, glasses, a red cheek stripe,
        # a white towel with a red stripe
        s, sk = Skin(), "d8a078"
        jer, acc, num, pin = "1e2a50", "d8303a", "52", "d8303a"
        head(s, sk, "2a2724", "short", mouth="8a4040",
             extra=[glasses("2a2a2a"), cap("d8303a", "1e2a50"), face_paint("d8303a", "ffffff")])
        trousers, shoe, towel = "6a6c72", "2a2a2a", ("f6f6f2", "d8303a")
        numcol = "ffffff"
    if v == 1:
        numcol = acc
    J, A, N = C(jer), C(acc), C(numcol)
    back_no = number(num, top=3)

    def b(fc, x, y, w, h):
        if fc == "top":
            return J
        if fc == "back":
            if back_no(x, y):
                return N
            if y == 1 and 2 <= x <= 5:
                return shade(N, 0.85)                        # a name bar over the number
        if fc == "front":
            if y == 0 and 2 <= x <= 5:
                return C(sk) if 3 <= x <= 4 else A           # the V collar
            if x == 4 and y in (1, 2):
                return shade(J, 0.8) if not pin else A       # the short button placket
            if y == 4 and 1 <= x <= 6:
                return A                                     # an invented script wordmark
            if (x, y) in ((1, 3), (3, 3), (5, 3), (6, 5)):
                return A                                     # its letters' tops and the tail
        if pin and (fc != "front" or not (3 <= y <= 5)) and x % 3 == 1:
            return shade(C(pin), 1.0)                         # pinstripes
        return J
    body(s, b)
    arms(s, jer if v != 2 else acc, sk, sleeve=4, cuff=acc if v != 2 else "e0b030")
    if v == 1:
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: A if (y == 3 and fc not in ("top", "bottom")) else None)
    legs(s, trousers, shoe, shoe_rows=1)
    if towel:
        neck_towel(*towel)(s)
    return s


def nurse(v):
    """간호사 (going home after a night shift): scrubs, an ID lanyard, tired eyes, hair tied or short"""
    if v == 1:
        # teal scrubs, a bun, white clogs, a blue lanyard
        s, sk = Skin(slim=True), "f2cba9"
        head(s, sk, "2a1e1a", "short", mouth="b8605a", extra=[tired(), lids("e0b090"), bun("2a1e1a", "3a8ad8")])
        sc, pen, shoe = "2a9a9a", "3a6ad8", "f4f4f4"
        extra = [lanyard("3a6ad8", "5aa0e8")]
    elif v == 2:
        # a man in navy scrubs over a long-sleeved grey tee, glasses, short hair, a messenger strap, black trainers
        s, sk = Skin(), "d8a078"
        head(s, sk, "141214", "short", mouth="8a4a40", pupils="side", extra=[tired(), glasses("3a3a40")])
        sc, pen, shoe = "26345e", "e04a3a", "222226"
        extra = [lanyard("2a2a2a", "f0c040")]
    else:
        # pink scrubs under an open oatmeal cardigan, a ponytail, a green lanyard, white trainers
        s, sk = Skin(slim=True), "f0c4a0"
        head(s, sk, "5a3224", "short", mouth="c0585a", extra=[tired(), lids("dcae8a"), ponytail("5a3224", "2a2a2a", length=3)])
        sc, pen, shoe = "f0a0b8", "2a2a2a", "f6f6f6"
        extra = [lanyard("3a9a5a", "6ac08a")]
    body(s, scrubs_top(sc, pen, sk))
    if v == 2:
        arms(s, "8a9098", sk, sleeve=10, cuff=shade_hex("8a9098", 0.85))
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: C(sc) if fc != "bottom" and (fc == "top" or y < 4) else None)
        def strap(fc, x, y, w, h):
            if fc == "front" and 1 <= y <= 8 and x == y - 1 and x != 6:
                return C("4a3a2e")                           # the messenger bag's strap
            if fc == "back" and 1 <= y <= 8 and x == 8 - y:
                return C("4a3a2e")
            if fc == "top" and x == 1:
                return C("4a3a2e")
            return None
        s.paint("body", strap, 1)
    elif v == 3:
        cardi = "d8c8a8"
        jacket(s, cardi, open_rows=12, open_cols=(2, 5), lapel=shade_hex(cardi, 0.88))
        s.paint("body", lambda fc, x, y, w, h: (shade(C(cardi), 0.82) if (fc in ("front", "back", "left", "right") and y == 11)
                                                or (fc == "front" and x == 1 and y in (3, 6, 9)) else None), 1)
        arms(s, sc, sk, sleeve=10, layer_cloth=cardi)
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: shade(C(cardi), 0.82) if y == 9 and fc not in ("top", "bottom") else None, 1)
    else:
        arms(s, sc, sk, sleeve=4)
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: shade(C(sc), 0.85) if y == 3 and fc not in ("top", "bottom") else None)
    legs(s, sc, shoe, shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(C(sc), 0.85) if y == 10 and fc not in ("top", "bottom") else None)   # the cuffed hem
    for e in extra:
        e(s)
    return s


# ---------------------------------------------------------------------------------------------
# helpers for the fourth batch
# ---------------------------------------------------------------------------------------------

def gold_earrings(col):
    """drop earrings: two pixels down each side of the head (rows 5-6) just behind the cheek"""
    E = C(col)
    return lambda s: s.paint("head", lambda fc, x, y, w, h: (shade(E, 1.15) if y == 5 else E)
                             if fc in SIDES and y in (5, 6) and fwd(fc, x, 8) == 2 else None)


def chatter(lip, inside="5a1e22"):
    """a mouth open mid-sentence: lipstick at the corners, the open mouth dark between them"""
    def f(fc, x, y, w, h):
        if fc == "front" and y == 6 and 2 <= x <= 5:
            return C(lip) if x in (2, 5) else C(inside)
        return None
    return lambda s: s.paint("head", f)


def handbag(s, col, clasp, arm="larm"):
    """a handbag hung on the forearm (outer layer): the handle over rows 5-6, the bag rows 7-10 with a flap
    and a clasp"""
    B, K = C(col), C(clasp)

    def f(fc, x, y, w, h):
        if fc in ("top", "bottom"):
            return None
        if y in (5, 6) and fc in ("front", "back") and x in (0, w - 1):
            return shade(B, 0.8)                              # the handle
        if 7 <= y <= 10:
            if y == 7:
                return shade(B, 0.82)                         # the flap
            if fc == "front" and y == 8 and x == w // 2:
                return K                                      # the clasp
            return B
        return None
    s.paint(arm, f, 1)


def sweat(col="d6f0ff"):
    """beads of cold sweat on the forehead and the temples (outer layer)"""
    S = C(col)

    def f(fc, x, y, w, h):
        if fc == "front" and (x, y) in ((6, 2), (1, 3), (7, 4)):
            return S
        if fc in SIDES and (fwd(fc, x, 8), y) in ((1, 3), (2, 5)):
            return S
        return None
    return lambda s: s.paint("head", f, layer=1)


def queasy(skin, shadow, mouth):
    """the motion-sick face: green shadows under the eyes, the eyes squeezed (pupil low), a wobbly mouth"""
    def f(fc, x, y, w, h):
        if fc != "front":
            return None
        if y == 5 and x in (1, 2, 5, 6):
            return C(shadow)
        if y == 3 and x in (1, 2, 5, 6):
            return shade(C(skin), 0.88)                      # heavy lids
        if y == 6 and 2 <= x <= 5:
            return C(mouth) if x in (2, 4) else C(skin)
        if y == 7 and x in (3, 5):
            return C(mouth)                                  # the wobble
        return None
    return lambda s: s.paint("head", f)


def hanky(col, check):
    """a handkerchief pressed over the mouth (head outer layer, rows 5-7)"""
    K, Ch = C(col), C(check)

    def f(fc, x, y, w, h):
        if fc == "front" and 2 <= x <= 5 and 6 <= y <= 7:
            return Ch if (x + y) % 2 else K
        if fc == "front" and y == 5 and x in (3, 4):
            return K
        return None
    return lambda s: s.paint("head", f, layer=1)


def baby_front(s, carrier, strap, hat, skin, pants, ears=False):
    """a baby in a front carrier (아기띠), body outer layer: the carrier panel rows 3-9 with straps over the shoulders
    and an X on the back, the baby's head on top of it (rows 0-3: a hat, a sleeping face, rosy cheeks), two tiny
    feet dangling under it"""
    Ca, St, Ht, Sk, Pn = C(carrier), C(strap), C(hat), C(skin), C(pants)

    def f(fc, x, y, w, h):
        if fc == "front":
            if 2 <= x <= 5 and y <= 3:
                if y == 0:
                    return Ht if (not ears or x in (2, 5)) else None
                if y == 1:
                    return Ht
                if y == 2:
                    return shade(Sk, 0.82) if x in (3, 4) else Sk     # closed sleeping eyes
                return C("f4a0a0") if x in (2, 5) else Sk             # cheeks
            if x in (1, 6) and y <= 2:
                return St
            if 1 <= x <= 6 and 3 <= y <= 9:
                if y == 9:
                    return St                                         # the waist belt
                if (x, y) in ((1, 4), (6, 4)):
                    return Sk                                         # tiny hands over the edge
                return shade(Ca, 1.08) if y == 3 else Ca
            if y == 9:
                return St
            if y in (10, 11) and x in (2, 5):
                return Pn if y == 10 else Sk                          # little feet
            return None
        if fc == "top":
            return St if x in (1, 6) else None
        if fc == "back":
            if y == 9 or (2 <= y <= 8 and (x == y - 1 or x == 8 - y)):
                return St
            return None
        if fc in SIDES and y == 9:
            return St
        return None
    s.paint("body", f, 1)


def podaegi(s, quilt, pattern, hat, skin, band):
    """a baby on the back in a 포대기 (body outer layer): the quilt wrapped rows 3-10 on the back, the baby's head
    peeking out over it (rows 0-3), the wrap's long band round the chest and tied at the front"""
    Q, P, Ht, Sk, Bd = C(quilt), C(pattern), C(hat), C(skin), C(band)

    def f(fc, x, y, w, h):
        if fc == "back":
            if 2 <= x <= 5 and y <= 3:
                if y == 0:
                    return Ht
                if y == 1:
                    return shade(Ht, 0.85) if x in (2, 5) else Ht
                return Sk if y == 2 or x in (3, 4) else Ht            # the back of the neck, little ears
            if 3 <= y <= 10 and 0 <= x <= 7:
                if y in (4, 5):
                    return Bd                                         # the band round the chest
                if (x + 2 * y) % 5 == 0:
                    return P
                return shade(Q, 0.9) if y == 10 else Q
            return None
        if fc in SIDES:
            if y in (4, 5):
                return Bd
            if 6 <= y <= 10 and fwd(fc, x) >= 2:
                return Q
            return None
        if fc == "front":
            if y in (4, 5) and not (y == 5 and x in (3, 4)):
                return Bd
            if (x, y) in ((3, 6), (2, 7), (4, 6), (5, 7)):
                return shade(Bd, 0.85)                                # the knot's ends
            return None
        return None
    s.paint("body", f, 1)


def flat_cap(col, fleck):
    """a flat cap (헌팅캡) of tweed on the outer layer: the crown over the top and rows 0-1, a short bill in row 2"""
    K, F = C(col), C(fleck)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return F if (x * 3 + y) % 5 == 0 else K
        if y <= 1:
            return F if (x * 2 + y) % 5 == 0 else K
        if fc == "front" and y == 2 and 1 <= x <= 6:
            return shade(K, 0.75)                             # the bill
        return None
    return lambda s: s.paint("head", f, layer=1)


def earpiece(col="d8d4cc"):
    """a security earpiece: a bud in the right ear, the clear coiled cord down the side of the neck"""
    E = C(col)

    def f(fc, x, y, w, h):
        if fc == "right":
            g = fwd(fc, x, 8)
            if (g, y) == (4, 4):
                return E
            if g == 5 and 5 <= y <= 7:
                return shade(E, 0.92) if y % 2 else E
        return None

    def g(fc, x, y, w, h):
        if fc == "right" and fwd(fc, x) == 2 and y <= 3:
            return shade(E, 0.92) if y % 2 else E
        return None

    def apply(s):
        s.paint("head", f)
        s.paint("body", g, 1)
    return apply


def santa_hat(red, fur, pom):
    """a Santa hat (outer layer): red over the top and rows 0-1, a white fur band in row 2, the tip flopped down
    the back to a white pompom"""
    R, F, P = C(red), C(fur), C(pom)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return shade(R, 1.08) if (x + y) % 4 == 0 else R
        if y <= 1:
            return R
        if y == 2:
            return shade(F, 0.95) if x % 2 else F
        if fc == "back" and 4 <= x <= 6 and 3 <= y <= 5:
            return R if y < 5 or x == 4 else None
        if fc == "back" and x in (5, 6) and y in (5, 6):
            return P
        return None
    return lambda s: s.paint("head", f, layer=1)


def santa_beard(col):
    """a big white beard and mustache (inner layer over the jaw, outer layer for the puff, the tip down on the chest
    is drawn with the body)"""
    B = C(col)

    def inner(fc, x, y, w, h):
        if fc == "front":
            if y == 3 and x in (1, 2, 5, 6):
                return B                                      # bushy white brows
            if y == 5 and x in (0, 1, 2, 5, 6, 7):
                return B
            if y == 6:
                return C("b8505a") if x in (3, 4) else B      # the mouth in the beard
            if y == 7:
                return B
            return None
        if fc in SIDES and fwd(fc, x, 8) <= 3 and y >= 4:
            return B
        if fc == "bottom":
            return B
        return None

    def outer(fc, x, y, w, h):
        if fc == "front":
            if y == 5 and x in (1, 2, 5, 6):
                return shade(B, 0.96)                         # the mustache's puff
            if y == 7 or (y == 6 and x in (0, 1, 6, 7)):
                return B
            return None
        if fc in SIDES and fwd(fc, x, 8) <= 2 and y >= 5:
            return B
        if fc == "bottom" and y <= 4:
            return B
        return None

    def apply(s):
        s.paint("head", inner)
        s.paint("head", outer, layer=1)
    return apply


def hollow_eyes(col="181a26", rim="9aa4b8"):
    """a ghost's eyes: no whites, two dark hollows with a grey-blue shadow round them"""
    E, R = C(col), C(rim)

    def f(fc, x, y, w, h):
        if fc != "front":
            return None
        if y == 4 and x in (1, 2, 5, 6):
            return E
        if y == 5 and x in (1, 2, 5, 6):
            return R
        if y == 3 and x in (1, 2, 5, 6):
            return shade(R, 1.08)
        return None
    return lambda s: s.paint("head", f)


def fade_feet(s, col, rows=(9, 10, 11), layer=0):
    """a ghost's legs fading out towards the floor: the bottom rows go pale, the feet are not there"""
    K = C(col)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: (shade(K, 1.0 + 0.04 * (y - rows[0])) if fc != "top" and (fc == "bottom" or y in rows)
                                            else None), layer)


def gat(col):
    """a 갓 (the old horsehair hat) on the outer layer: the crown over the top and rows 0-1, the see-through brim in
    row 2 (every other pixel), the bead strings (갓끈) down the cheeks"""
    K = C(col)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return K if 2 <= x <= 5 and 2 <= y <= 5 else (K if (x + y) % 2 == 0 else None)
        if y <= 1:
            return K if (fc != "front" or 1 <= x <= 6) else None
        if y == 2:
            return K if x % 2 == 0 else shade(K, 1.6)
        if fc == "front" and x in (0, 7) and 3 <= y <= 7:
            return C("6a5a40") if y % 2 else C("2a2420")      # the bead strings
        return None
    return lambda s: s.paint("head", f, layer=1)


def school_cap(col, badge):
    """an old school cap (학생모): flat top, rows 0-1, a short shiny visor in row 2, a white badge on the front"""
    K, B = C(col), C(badge)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return K
        if fc == "front" and y == 0 and x in (3, 4):
            return B
        if y <= 1:
            return K
        if fc == "front" and y == 2 and 1 <= x <= 6:
            return shade(K, 1.5) if x in (2, 3) else shade(K, 1.2)
        return None
    return lambda s: s.paint("head", f, layer=1)


def helmet(shell, visor, stripe):
    """an open-face delivery helmet (outer layer): the shell over the top, the back and the sides down to the jaw,
    the tinted visor flipped up in row 2, a stripe over the crown, a chin strap"""
    Sh, V, St = C(shell), C(visor), C(stripe)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return C("2a2a2a") if y == 0 and 2 <= x <= 5 else None
        if fc == "top":
            return St if x in (3, 4) else (shade(Sh, 1.1) if (x, y) == (2, 2) else Sh)
        if fc == "front":
            if y <= 1:
                return St if (y == 0 and x in (3, 4)) else Sh
            if y == 2:
                return shade(V, 1.4) if x in (1, 2) else V   # the visor, up
            if x in (0, 7) and y <= 5:
                return Sh
            return None
        if fc == "back":
            if x in (3, 4) and y <= 3:
                return St
            return Sh if y <= 6 else None
        if fc in SIDES:
            g = fwd(fc, x, 8)
            if y <= 5 or (g >= 3 and y <= 6):
                return shade(V, 1.2) if (g == 0 and y == 2) else Sh
            if g == 1 and y >= 6:
                return C("2a2a2a")                            # the chin strap
            return None
        return None
    return lambda s: s.paint("head", f, layer=1)


def delivery_box(s, col, band, strap):
    """an insulated delivery bag on the back (outer layer): the box rows 0-10 with a lid seam and a reflective band,
    shoulder straps down the front, the box's top over the shoulders"""
    B, Bd, St = C(col), C(band), C(strap)

    def f(fc, x, y, w, h):
        if fc == "back":
            if y <= 10:
                if y == 1:
                    return shade(B, 0.78)                     # the lid's seam
                if y == 6:
                    return Bd                                  # the reflective band
                if y == 0:
                    return shade(B, 1.1)
                return B
            return None
        if fc == "top":
            return B if y >= 2 else (St if x in (1, 6) else None)
        if fc in SIDES:
            g = fwd(fc, x)
            if g >= 2 and y <= 10:
                return Bd if y == 6 else B
            return None
        if fc == "front" and x in (1, 6) and y <= 7:
            return St
        return None
    s.paint("body", f, 1)


def mala(bead, big, tassel):
    """prayer beads (염주) round the neck on the chest (outer layer): a U of alternating beads, a big bead at the
    bottom with a tassel"""
    B, G, T = C(bead), C(big), C(tassel)
    path = [(1, 0), (1, 1), (1, 2), (1, 3), (2, 4), (2, 5), (3, 6), (4, 6), (5, 5), (5, 4), (6, 3), (6, 2), (6, 1), (6, 0)]

    def f(fc, x, y, w, h):
        if fc == "front":
            if (x, y) in path:
                return B if path.index((x, y)) % 2 == 0 else shade(B, 1.25)
            if (x, y) in ((3, 7), (4, 7)):
                return G
            if x in (3, 4) and y == 8:
                return T if x == 3 else shade(T, 0.85)
            return None
        if fc == "top" and x in (1, 6):
            return B
        if fc == "back" and y == 0 and 1 <= x <= 6:
            return B if x % 2 else shade(B, 1.25)
        return None
    return lambda s: s.paint("body", f, layer=1)


def wrist_beads(s, col, arm="larm"):
    """a short string of beads (단주) round the wrist"""
    B = C(col)
    s.paint(arm, lambda fc, x, y, w, h: (B if (x + (fc == "front")) % 2 else shade(B, 1.3))
            if y == 9 and fc not in ("top", "bottom") else None, 1)


def hoodie_body(col, string, pocket=True, mark=None):
    """a hoodie: the neckline, two drawstrings, a pouch pocket, a hem; mark(x, y) -> a print on the chest"""
    H = C(col)

    def f(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and 2 <= x <= 5:
                return shade(H, 0.78)
            if x in (2, 5) and 1 <= y <= 3:
                return C(string) if y != 1 else shade(H, 0.85)
            if mark:
                m = mark(x, y)
                if m:
                    return m
            if pocket and 7 <= y <= 9 and 2 <= x <= 5:
                return shade(H, 0.88 if y == 7 else 0.94)
            if y == 11:
                return shade(H, 0.85)
        return H
    return f


def hood_down(s, col):
    s.paint("body", lambda fc, x, y, w, h: shade(C(col), 0.85) if fc == "back" and y <= 3 and 1 <= x <= 6
            and not (y == 3 and x in (1, 6)) else None, 1)


def back_cap(col, bill):
    """a cap turned backwards: the crown over the top and rows 0-1, the bill at the back in row 2, the strap
    gap at the front"""
    K, B = C(col), C(bill)

    def f(fc, x, y, w, h):
        if fc == "top":
            return K
        if fc == "bottom":
            return None
        if fc == "front":
            if y == 1 and x in (3, 4):
                return None
            return K if y <= 1 else None
        if fc == "back":
            return K if y <= 1 else (B if y == 2 else None)
        if fc in SIDES:
            return K if y <= 1 else (B if y == 2 and fwd(fc, x, 8) == 7 else None)
        return None
    return lambda s: s.paint("head", f, layer=1)


def headphones(band, cup):
    """over-ear headphones (outer layer): the band over the top, a cup on each side over rows 3-5"""
    Bd, Cp = C(band), C(cup)

    def f(fc, x, y, w, h):
        if fc == "top" and x in (0, 7) or (fc == "top" and y in (3, 4) and x not in (0, 7)):
            return Bd if fc == "top" and y in (3, 4) else None
        if fc in SIDES:
            g = fwd(fc, x, 8)
            if 3 <= g <= 5 and 3 <= y <= 5:
                return shade(Cp, 1.2) if (g, y) == (4, 4) else Cp
            if g == 4 and y <= 2:
                return Bd
        return None
    return lambda s: s.paint("head", f, layer=1)


# ---------------------------------------------------------------------------------------------
# the fourth batch
# ---------------------------------------------------------------------------------------------

def chatty(v):
    """수다쟁이 아줌마: a tight perm, a flowery blouse, gold earrings, lipstick, a mouth that never stops, a handbag"""
    if v == 1:
        # a dark brown perm, a coral blouse with white flowers, black slacks, a tan handbag
        s, sk = Skin(slim=True), "f0c3a0"
        head(s, sk, "3a2420", "perm", mouth="c0303a", blush="f2a090", brows="3a2420",
             extra=[chatter("d0303a"), gold_earrings("e8c25a")])
        blouse = flowers("e8505a", "fff4ec", "ffd34d", step=(4, 4))
        slacks, shoe, bag = "26262c", "6a3a2a", ("7a4226", "e8c25a")
    elif v == 2:
        # a wine-dyed perm under a pink sun visor, a purple blouse with pink flowers, beige slacks, a black handbag
        s, sk = Skin(slim=True), "e8b896"
        head(s, sk, "6a2438", "perm", mouth="c02a5a", blush="f0a0a0", brows="4a1e2a",
             extra=[chatter("d02a6a"), gold_earrings("e8c25a"), visor("ff7aa8")])
        blouse = flowers("7a4aa8", "ffb0d8", "fff0a0", step=(4, 4))
        slacks, shoe, bag = "d8c8a6", "2a2a2a", ("1e1e24", "e8c25a")
    else:
        # a black perm going grey, gold-rimmed glasses, an emerald blouse with yellow flowers, navy slacks, a red bag
        s, sk = Skin(slim=True), "dcaa84"
        head(s, sk, "2a2426", "perm", mouth="b02a30", blush="e89a8a", brows="2a2426",
             extra=[chatter("c02a30"), gold_earrings("e8c25a"), glasses("c9a227")])
        s.paint("head", lambda fc, x, y, w, h: C("8a8686") if fc != "bottom" and (x * 3 + y * 2) % 7 == 0
                and (fc == "top" or y <= 3) else None, 1)   # grey threads in the perm
        blouse = flowers("1f8a6a", "ffe066", "fff6d8", step=(4, 4))
        slacks, shoe, bag = "2a3456", "1e1e22", ("c0303a", "e8c25a")

    def b(fc, x, y, w, h):
        if fc == "front" and y == 0 and x in (3, 4):
            return C(sk)                                     # the open collar
        if fc == "front" and y == 0 and x in (2, 5):
            return C("fafafa")                               # a white collar edge
        return blouse(x, y)
    body(s, b)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: C(sk) if fc == "bottom" or y >= 9 else
                (C("fafafa") if y == 8 else blouse(x + 1, y)))
    s.paint("rarm", lambda fc, x, y, w, h: C("e8c25a") if y == 9 and fc not in ("top", "bottom") else None)   # a gold bangle
    handbag(s, *bag)
    legs(s, slacks, shoe, shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(C(slacks), 0.88) if fc == "front" and x == 1 and y < 11 else None)   # the crease
    return s


def motionsick(v):
    """멀미하는 아저씨: a pale green face, cold sweat, a towel or a handkerchief, the clothes come undone"""
    if v == 1:
        # an office 아저씨: shirt, tie pulled loose, a handkerchief pressed to his mouth, grey trousers
        s, sk = Skin(), "d4d8a8"
        head(s, sk, "2a2724", "part", mouth="7a8a5a",
             extra=[queasy(sk, "a8bc84", "6a7a4a"), sweat(), hanky("f4f4f0", "8ab0e0")])
        shirt, tie = C("eef1f6"), C("2e5fb0")

        def b(fc, x, y, w, h):
            if fc == "front":
                if y == 0 and x in (3, 4):
                    return C(sk)
                if (x, y) in ((4, 1), (4, 2), (3, 3), (3, 4), (4, 5)):
                    return shade(tie, 0.8) if y == 1 else tie   # the tie yanked loose
                if y == 11:
                    return C("33363d")
                if (x, y) in ((1, 4), (6, 7)):
                    return shade(shirt, 0.9)                   # sweaty creases
            return shirt
        body(s, b)
        arms(s, "eef1f6", sk, sleeve=9, cuff="eef1f6")
        legs(s, "55585f", "1a1a1a")
        return s
    if v == 2:
        # a navy windbreaker zipped to the chin, a white towel round the neck, a cap pushed back, sweat
        s, sk = Skin(), "ccd4a4"
        head(s, sk, "1e1c1a", "short", mouth="6a7a4a",
             extra=[queasy(sk, "a0b47c", "5e6e42"), sweat(), cap("8a8e94", "6a6e74")])
        wb = C("2a3a66")

        def b(fc, x, y, w, h):
            if fc == "front":
                if x == 4:
                    return C("c8ccd0") if y <= 1 else shade(wb, 0.75)   # the zip
                if y == 11:
                    return shade(wb, 0.82)
                if y == 6 and x in (1, 6):
                    return shade(wb, 0.8)                      # pocket slits
            if fc in SIDES and x == 1:
                return C("e8e8e0")                             # a white piping stripe
            return wb
        body(s, b)
        arms(s, "2a3a66", sk, sleeve=10, cuff=shade_hex("2a3a66", 0.75))
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: C("e8e8e0") if fc in SIDES and x == 1 and y < 10 else None)
        neck_towel("f4f2ea", "8ac0e8")(s)
        legs(s, "6a6c72", "2a2a2a", shoe_rows=1)
        return s
    # a beige windbreaker open over a polo, bald on top, a pink hand towel in one hand, a black plastic bag in the other
    s, sk = Skin(), "d0d6a0"
    head(s, sk, "3a3632", "bald", brows="2a2622", mouth="6a7a4a",
         extra=[queasy(sk, "a4b87e", "5e6e42"), sweat("e0f4ff"), glasses("4a4a50")])
    polo = "6a8a5a"

    def b(fc, x, y, w, h):
        if fc == "front" and y == 0 and x in (3, 4):
            return C(sk)
        if fc == "front" and y == 0 and x in (2, 5):
            return shade(C(polo), 1.2)                         # the polo collar
        return C(polo)
    body(s, b)
    jacket(s, "c8b890", open_rows=12, open_cols=(3, 4), lapel=shade_hex("c8b890", 0.85))
    arms(s, polo, sk, sleeve=10, layer_cloth="c8b890")
    legs(s, "5e5a50", "3a2a1e")
    return s


def mom(v):
    """애 업은 엄마: a ponytail, comfy clothes, a baby on the front (아기띠) or the back (포대기)"""
    if v == 1:
        # a navy carrier with a baby in a yellow bear hat, a striped tee, jeans, white trainers, a brown ponytail
        s, sk = Skin(slim=True), "f2c8a6"
        head(s, sk, "5a3424", "short", mouth="c05a5a", blush="f2a8a0",
             extra=[ponytail("5a3424", "2a2a2a", length=4)])
        S1, S2 = C("f4f4f0"), C("2a3a66")
        body(s, lambda fc, x, y, w, h: (S2 if y % 3 == 2 else S1) if fc != "top" else S1)
        arms(s, "f4f4f0", sk, sleeve=10)
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: S2 if y < 10 and y % 3 == 2 and fc not in ("top", "bottom") else None)
        baby_front(s, "2a3a66", "1e2a4a", "ffd23a", "f8d8bc", "8ccbf0", ears=True)
        legs(s, "5a7ab0", "f6f6f6", shoe_rows=1)
        return s
    if v == 2:
        # a 포대기 in pink with flowers, the baby in a knit hat peeking over, a beige knit, black leggings, black hair
        s, sk = Skin(slim=True), "e8b896"
        head(s, sk, "1e1a1a", "short", mouth="b85a50", blush="eea090",
             extra=[ponytail("1e1a1a", "e05a7a", length=2)])
        knit = C("e0d0b0")
        body(s, lambda fc, x, y, w, h: shade(knit, 0.9) if (fc == "front" and x in (2, 5) and y >= 1) or y == 11 else knit)
        arms(s, "e0d0b0", sk, sleeve=10, cuff=shade_hex("e0d0b0", 0.85))
        podaegi(s, "e86a8a", "fff0a0", "a8d8f0", "f8d8bc", "c84a6a")
        legs(s, "26262c", "8a6a4a", shoe_rows=1)
        return s
    # a grey carrier with a baby in a pink bonnet, a sage hoodie, grey joggers, a diaper bag on the shoulder, a bun
    s, sk = Skin(slim=True), "f0c4a0"
    head(s, sk, "6a4430", "short", mouth="c06060", blush="f2a0a0", pupils="side",
         extra=[bun("6a4430", "f0c040"), tired()])
    body(s, hoodie_body("9ab89a", "f4f4f0", pocket=False))
    arms(s, "9ab89a", sk, sleeve=10, cuff=shade_hex("9ab89a", 0.85))
    baby_front(s, "8a8e96", "4a4e56", "ff9ac0", "f8d8bc", "f4f4f0")

    def bag(fc, x, y, w, h):
        if fc == "front" and 0 <= y <= 8 and x == 7 - y // 2 and x >= 6:
            return C("c8a070")
        if fc == "back" and 1 <= y <= 8 and x == y - 1:
            return C("c8a070")                                # the strap across the back
        if fc == "left" and 6 <= y <= 10:
            return shade(C("e8d4b0"), 0.9) if y == 6 else C("e8d4b0")   # the diaper bag at the hip
        return None
    s.paint("body", bag, 1)
    legs(s, "8a8c92", "f4f4f4", shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: C("6e7076") if y == 10 and fc not in ("top", "bottom") else None)   # cuffed joggers
    return s


def dogwalker(v):
    """강아지 데려온 할아버지: a flat cap, a cardigan (his walking stick and the dog's lead are held models: bus.yml
    types.dogwalker.hold / hold-off, models/bus_hold.py)"""
    if v == 1:
        # a brown tweed cap, a beige cardigan over a white shirt, brown slacks, glasses, a white mustache, a red lead
        s, sk = Skin(), "dcaa86"
        head(s, sk, "e8e4dc", "short", brows="f2efe8", mouth="8a4a40",
             extra=[mustache("eeeae2"), glasses("6a5a3a"), flat_cap("7a6248", "a08868")])
        shirt, cardi, slacks = "f4f4f0", "d8c8a0", "6a5440"
    elif v == 2:
        # a navy cap, a burgundy cardigan over a sky blue shirt, grey slacks, white brows, smiling eyes, a blue lead
        s, sk = Skin(), "d09a74"
        head(s, sk, "d8d4cc", "short", brows="f0ede6", mouth="8a4a40",
             extra=[happy_eyes("3a2a22"), flat_cap("2a3456", "4a5476")])
        shirt, cardi, slacks = "a8c8e8", "7a2a38", "6a6c72"
    else:
        # a beige cap, an olive cardigan over a checked shirt, khaki slacks, a grey mustache, a green lead
        s, sk = Skin(), "c88c62"
        head(s, sk, "9a9690", "short", brows="7a7670", mouth="7a3e36",
             extra=[mustache("8a8680"), flat_cap("c8b48a", "a8946a")])
        shirt, cardi, slacks = None, "5e6a3e", "a89a74"
    chk = plaid("e8e0d0", "c06a5a", "9a4a3a")

    def b(fc, x, y, w, h):
        if shirt is None:
            if fc == "front" and y == 0 and x in (3, 4):
                return C(sk)
            return chk(x, y)
        if fc == "front" and y == 0 and x in (3, 4):
            return C(sk)
        return C(shirt)
    body(s, b)
    jacket(s, cardi, open_rows=5, open_cols=(3, 4), lapel=shade_hex(cardi, 0.85))
    s.paint("body", lambda fc, x, y, w, h: (C("e8dcc0") if fc == "front" and x == 3 and y in (6, 8, 10) else
                                           (shade(C(cardi), 0.82) if fc in ("front", "back", "left", "right") and y == 11 else None)), 1)
    s.paint("body", lambda fc, x, y, w, h: shade(C(cardi), 0.88) if fc == "front" and x == 4 and y >= 5 else None, 1)   # the placket
    arms(s, shirt or "e8e0d0", sk, sleeve=10, layer_cloth=cardi)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: shade(C(cardi), 0.82) if y == 9 and fc not in ("top", "bottom") else None, 1)
    legs(s, slacks, "2a1e16", shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(C(slacks), 0.88) if fc == "front" and x == 1 and y < 11 else None)
    return s


def shades(v):
    """수상한 선글라스 남자: the same man every time: black suit, white shirt, a thin black tie, black glasses, an earpiece,
    slicked hair, no expression. The three differ only in a pixel or two."""
    s, sk = Skin(), "e6b896"
    head(s, sk, "0e0e12", "slick", brows="0e0e12", mouth="8a5048",
         extra=[sunglasses("0a0a0e", "1a1a20", "3a3e4a" if v != 2 else "0a0a0e"), earpiece()])
    s.paint("head", lambda fc, x, y, w, h: (C("8a5048") if (fc == "front" and y == 6 and (x in (2, 3, 4, 5) if v != 3 else x in (3, 4, 5)))
                                            else None))   # a flat line of a mouth (v3: the corner barely up)
    if v == 3:
        s.paint("head", lambda fc, x, y, w, h: C(sk) if fc == "front" and (x, y) == (2, 6) else None)
    suit, shirt = "141418", C("f6f6f4")

    def b(fc, x, y, w, h):
        if fc == "front" and x in (3, 4) and 1 <= y <= 7:
            return C("0a0a0c") if y != 1 else C("1c1c22")      # the thin black tie, its knot
        if fc == "front" and y == 11:
            return C("0a0a0c")
        return shirt
    body(s, b)
    jacket(s, suit, open_rows=6, open_cols=(2, 5), lapel="0c0c10", edge="0c0c10")
    if v == 2:
        s.paint("body", lambda fc, x, y, w, h: C("f6f6f4") if fc == "front" and (x, y) == (6, 3) else None, 1)   # a pocket square
    arms(s, "f6f6f4", sk, sleeve=9, cuff="f6f6f4", layer_cloth=suit)
    if v == 3:
        s.paint("rarm", lambda fc, x, y, w, h: C("b8bcc4") if fc == "front" and (x, y) == (1, 9) else None)   # a silver cufflink
    legs(s, suit, "050505", shoe_rows=2)
    return s


def santa(v):
    """산타 할아버지: a red suit with white fur, a black belt, a white beard, the hat on the outer layer"""
    if v == 1:
        # the classic: white gloves, black boots
        s, sk = Skin(), "f0c0a0"
        red, fur, glove = "c8202a", "f6f6f2", "f6f6f2"
        extra = [santa_hat(red, fur, fur)]
        sack = None
    elif v == 2:
        # round gold spectacles, black gloves, a brown sack of presents on his back
        s, sk = Skin(), "e8b28e"
        red, fur, glove = "b01e28", "f2f0ea", "1e1e22"
        extra = [santa_hat(red, fur, fur), glasses("d4af37")]
        sack = ("9a6a3a", "c8a040")
    else:
        # a jolly one: rosy cheeks and nose, closed laughing eyes, green gloves, a white sack
        s, sk = Skin(), "f2c4a4"
        red, fur, glove = "d82830", "fafaf6", "2e8b4a"
        extra = [santa_hat(red, fur, fur), happy_eyes("3a2420"),
                 lambda s: s.paint("head", lambda fc, x, y, w, h: C("f08a7a") if fc == "front" and y == 5 and x in (3, 4) else None)]
        sack = ("e8e4d8", "c8303a")
    head(s, sk, "f2f0ea", "short", mouth="b8505a", blush="f2909a" if v == 3 else None)
    santa_beard("f6f6f2")(s)
    for e in extra:
        e(s)
    R, F = C(red), C(fur)

    def b(fc, x, y, w, h):
        if fc == "front":
            if x in (3, 4):
                return F                                     # the fur down the front
            if y == 11:
                return F
        if fc in ("back", "left", "right") and y == 11:
            return F
        if y == 8 and fc not in ("top", "bottom"):
            return C("1a1a1a")                               # the belt
        return R
    body(s, b)
    s.paint("body", lambda fc, x, y, w, h: (C("e8c25a") if x in (3, 4) else None) if fc == "front" and y == 8 else None, 1)   # the buckle
    s.paint("body", lambda fc, x, y, w, h: C("f6f6f2") if fc == "front" and ((y <= 1 and 2 <= x <= 5) or (y == 2 and x in (3, 4))) else None, 1)   # the beard's tip
    arms(s, red, sk, sleeve=9, cuff=fur)
    gloves(s, glove)
    if sack:
        Sa, Ti = C(sack[0]), C(sack[1])

        def sk_(fc, x, y, w, h):
            if fc == "back" and 1 <= x <= 7 and 1 <= y <= 9:
                if y == 1 and x in (5, 6):
                    return Ti                                # the tie at the neck of the sack
                if y <= 2 and x < 4:
                    return None
                return shade(Sa, 0.88) if (x + y) % 4 == 0 else Sa
            if fc == "top" and x == 6:
                return Ti
            if fc == "front" and x == 6 and y <= 2:
                return Ti                                    # the twisted top held over the shoulder
            return None
        s.paint("body", sk_, 1)
    legs(s, red, "141414", shoe_rows=3)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: F if y == 8 and fc not in ("top", "bottom") else None, 1)   # fur at the boot tops
    return s


def ghost(v):
    """유령 손님: pale blue-grey and old-fashioned, hollow eyes, the feet fading out (eerie, nothing gory)"""
    if v == 1:
        # the long-haired maiden ghost in a white 소복: black hair hanging over the face and down the back
        s, sk = Skin(slim=True), "dfe6ee"
        head(s, sk, "0e0e14", "long", mouth="6a7088", extra=[hollow_eyes()])

        def hair(fc, x, y, w, h):
            if fc == "front" and (x in (0, 1, 6, 7) and y <= 7 or (y == 2 and x in (2, 5))):
                return C("0e0e14") if (x + y) % 3 else C("1c1c26")
            return None
        s.paint("head", hair, 1)
        robe = C("e8ecf2")

        def b(fc, x, y, w, h):
            if fc == "front":
                if (x, y) in ((2, 0), (3, 1), (4, 2), (5, 3)):
                    return shade(robe, 0.86)                 # the crossed collar
                if (x, y) in ((4, 4), (4, 5), (5, 4), (5, 6)):
                    return C("c8d0dc")                       # the 고름 ties
                if y >= 7:
                    return shade(robe, 0.97)                 # the skirt's waist
            return robe
        body(s, b)
        s.paint("body", lambda fc, x, y, w, h: (C("0e0e14") if (x + y) % 4 else C("1c1c26"))
                if fc == "back" and y <= 7 else None, 1)    # the hair down to the waist
        arms(s, "e8ecf2", sk, sleeve=11)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: (shade(robe, 0.9) if (fc == "front" and x % 3 == 0) else robe)
                    if fc != "bottom" else C("d8e0ea"))
            s.paint(lg, lambda fc, x, y, w, h: (shade(robe, 0.94) if x % 2 else robe) if fc not in ("top", "bottom") and y <= 8 else None, 1)
        fade_feet(s, "dce6f2", rows=(9, 10, 11))
        return s
    if v == 2:
        # the old gentleman: a 갓 and its bead strings, a blue-grey 두루마기, a thin grey beard
        s, sk = Skin(), "d6dee6"
        head(s, sk, "4a4e58", "short", mouth="5a6070",
             extra=[hollow_eyes(), gat("1a1a22"),
                    lambda s: s.paint("head", lambda fc, x, y, w, h: C("b8c0cc") if fc == "front" and (y == 7 and 2 <= x <= 5 or (y == 6 and x in (2, 5))) else None)])
        coat = C("aab8c8")

        def b(fc, x, y, w, h):
            if fc == "front":
                if (x, y) in ((2, 0), (3, 1), (4, 2), (5, 3), (6, 4)):
                    return C("eef2f6")                       # the white 동정 collar
                if (x, y) in ((5, 5), (5, 6), (6, 5), (6, 7)):
                    return shade(coat, 0.8)                  # the tie
                if x == 3 and y >= 4:
                    return shade(coat, 0.88)                 # the overlap
            return coat
        body(s, b)
        s.paint("body", lambda fc, x, y, w, h: (C("2a2420") if x in (1, 6) and y <= 3 else None) if fc == "front" else None, 1)   # the bead strings on the chest
        arms(s, "aab8c8", sk, sleeve=11)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: shade(coat, 0.94) if fc != "bottom" else C("c8d2de"))
            s.paint(lg, lambda fc, x, y, w, h: (shade(coat, 0.9) if x % 3 == 0 else coat) if fc not in ("top", "bottom") and y <= 7 else None, 1)
        fade_feet(s, "c8d4e2", rows=(9, 10, 11))
        return s
    # a schoolboy from long ago: a black stand-collar uniform with brass buttons, a school cap
    s, sk = Skin(), "d8e0e8"
    head(s, sk, "1a1c24", "short", mouth="5a6070", extra=[hollow_eyes(), school_cap("1e2230", "eef2f6")])
    uni = C("2a3040")

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0:
                return shade(uni, 0.7)                       # the stand-up collar
            if x == 4 and y in (2, 4, 6, 8, 10):
                return C("a89a6a")                           # tarnished brass buttons
            if x == 3 and y >= 1:
                return shade(uni, 0.85)
        return uni
    body(s, b)
    arms(s, "2a3040", sk, sleeve=10, cuff=shade_hex("2a3040", 0.8))
    legs(s, "2a3040", "2a3040", shoe_rows=1)
    fade_feet(s, "a8b4c4", rows=(9, 10, 11))
    return s


def rider(v):
    """배달 라이더: a helmet, a bright jacket with reflective tape, gloves, an insulated box on the back"""
    if v == 1:
        # an orange jacket, a white helmet with an orange stripe, a grey box, black gloves
        s, sk = Skin(), "dcaa84"
        head(s, sk, "1e1c1a", "short", mouth="8a4a40", extra=[helmet("f2f2f0", "3a4a5a", "f0702a")])
        jk, tape, trousers, glove, box = "f0702a", "d8dce0", "2a2c32", ("1e1e22", "3a3a40"), ("4a4e56", "d8dce0", "1e1e22")
    elif v == 2:
        # a young woman in a hi-vis lime jacket, a black helmet, a ponytail, a navy box
        s, sk = Skin(slim=True), "f0c6a4"
        head(s, sk, "2e1e18", "short", mouth="b85a50", blush="f0a898",
             extra=[helmet("1e1e24", "6a8aa8", "c8f03a"), ponytail("2e1e18", "c8f03a", length=0)])
        jk, tape, trousers, glove, box = "c8f03a", "d8dce0", "26262c", ("2a2a30", "c8f03a"), ("2a3456", "c8f03a", "1e1e22")
    else:
        # a cobalt blue jacket with yellow tape, a yellow helmet, sunglasses, a red box
        s, sk = Skin(), "c88c62"
        head(s, sk, "151214", "short", mouth="7a3e36",
             extra=[sunglasses("1a1e26", "2a2a30", "5a7a9a"), helmet("f6c832", "2a2a30", "2a5ad8")])
        jk, tape, trousers, glove, box = "2a5ad8", "f6c832", "3a3e48", ("1e1e22", "f6c832"), ("c8303a", "f6c832", "1e1e22")
    J, T = C(jk), C(tape)

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and 2 <= x <= 5:
                return shade(J, 0.8)                         # the collar
            if x == 4 and y >= 1:
                return shade(J, 0.78)                        # the zip
        if y == 7 and fc not in ("top", "bottom"):
            return T                                         # reflective tape round the chest
        if fc == "front" and y == 11:
            return shade(J, 0.85)
        return J
    body(s, b)
    arms(s, jk, sk, sleeve=10)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: T if y == 6 and fc not in ("top", "bottom") else None)
    gloves(s, *glove)
    delivery_box(s, *box)
    legs(s, trousers, "1e1e22", shoe_rows=2)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: T if y == 5 and fc not in ("top", "bottom") else None)   # tape on the trousers
    return s


HAIR["shaved"] = dict(front=lambda x, y: False, side=lambda f, y: False, back=lambda x, y: False,
                      o_front=None, o_side=None, o_back=None)


def monk(v):
    """스님: a shaved head, a grey robe, prayer beads (염주) round the neck and on the wrist"""
    if v == 1:
        # the grey jacket and trousers (동방), beads, grey rubber shoes, calm closed eyes
        s, sk = Skin(), "e2b08c"
        head(s, sk, "c09a7e", "shaved", mouth="9a5a50", extra=[happy_eyes("6a4a3a")])
        robe, beadc, sash = "9a9c96", "5a3a22", None
    elif v == 2:
        # the long robe (장삼) with a brown 가사 over one shoulder, an older monk with white brows and glasses
        s, sk = Skin(), "d6a27c"
        head(s, sk, "b48e72", "shaved", brows="f0ece4", mouth="8a4a40", extra=[glasses("6a5a3a")])
        robe, beadc, sash = "7e807c", "3a2a1e", "8a3a22"
    else:
        # a nun (비구니) in a grey knit hat (털모자), a light robe, a 바랑 (cloth bag) over the shoulder
        s, sk = Skin(slim=True), "f0c6a4"
        head(s, sk, "d0b098", "shaved", mouth="b0605a", blush="f0b0a0",
             extra=[beanie("8a8c88", "7a7c78")])
        robe, beadc, sash = "b0b2ac", "6a4a2e", None
    R = C(robe)

    def b(fc, x, y, w, h):
        if fc == "front":
            if (x, y) in ((2, 0), (3, 1), (4, 2), (5, 3)) or (x, y) in ((1, 0),):
                return shade(R, 0.82)                        # the crossed collar
            if (x, y) in ((5, 4), (5, 5), (6, 4)):
                return shade(R, 0.78)                        # the tie
            if v == 2 and y >= 9:
                return shade(R, 0.95)
        return R
    body(s, b)
    mala(beadc, beadc, "8a3a2a")(s)
    arms(s, robe, sk, sleeve=10)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: (shade(R, 0.92) if y >= 7 else R) if fc not in ("top", "bottom") and y < 10 else None, 1)   # the wide sleeves
    wrist_beads(s, beadc)
    if sash:
        S = C(sash)

        def kasaya(fc, x, y, w, h):
            if fc == "front" and 6 <= x + (y * 2) // 3 <= 7:
                return S if (x + y) % 4 else shade(S, 0.85)    # over the left shoulder, down to the right hip
            if fc == "back" and 0 <= x - (y * 2) // 3 <= 1:
                return S if (x + y) % 4 else shade(S, 0.85)
            if fc == "top" and x >= 5:
                return S
            if fc == "left" and y <= 2:
                return S
            return None
        s.paint("body", kasaya, 1)
    if v == 3:
        def barang(fc, x, y, w, h):
            if fc == "front" and 0 <= y <= 7 and x == 1 + y // 2 and x <= 3 and y <= 5:
                return C("6a5a40")
            if fc == "right" and 6 <= y <= 10:
                return C("c8b48a") if y > 6 else shade(C("c8b48a"), 0.85)
            if fc == "back" and 0 <= y <= 6 and x == 6 - y // 2:
                return C("6a5a40")
            return None
        s.paint("body", barang, 1)
    long = v == 2
    legs(s, robe, "9a9c96" if v != 2 else "e8e8e4", shoe_rows=1)
    for lg in ("rleg", "lleg"):
        if long:
            s.paint(lg, lambda fc, x, y, w, h: (shade(R, 0.92) if x % 3 == 0 else R) if fc not in ("top", "bottom") and y <= 8 else None, 1)
        else:
            s.paint(lg, lambda fc, x, y, w, h: C("e8e6de") if y in (9, 10) and fc not in ("top", "bottom") else None)   # white 행전 wraps
    return s


def youtuber(v):
    """유튜버: a bright hoodie, a cap, a phone held up in a little ring light, colourful"""
    if v == 1:
        # a hot pink hoodie, a black cap turned backwards, headphones round the neck, purple joggers
        s, sk = Skin(), "eab08a"
        head(s, sk, "1e1a18", "short", mouth="c0505a", pupils="side",
             extra=[smile("c0505a"), back_cap("1e1e24", "1e1e24")])
        hood_c, string, trousers, shoe = "ff4a9a", "ffffff", "6a3aa8", "ffffff"
        mark = lambda x, y: (C("ffd23a") if (x, y) in ((3, 5), (4, 5), (4, 4), (3, 6)) else None)   # a little lightning bolt
        extra = []
    elif v == 2:
        # a girl with pink-dyed hair under a yellow cap, a lavender hoodie with a red REC dot, a pleated skirt
        s, sk = Skin(slim=True), "f6d0b2"
        head(s, sk, "ff8ac0", "long", mouth="e0506a", blush="ffa8b0",
             extra=[smile("e0506a"), cap("ffd23a", "f0b020")])
        hood_c, string, trousers, shoe = "b8a0f0", "ffffff", "2a2a34", "ffffff"
        mark = lambda x, y: (C("ff3040") if (x, y) in ((1, 3), (2, 3)) else None)
        extra = []
    else:
        # a gamer: a lime hoodie, a cap and headphones over it, a gradient of a tee under, cyan joggers
        s, sk = Skin(), "d39a72"
        head(s, sk, "2a1e16", "short", mouth="8a3e36",
             extra=[cap("2a2a34", "ff4a9a"), headphones("1e1e24", "30d0f0")])
        hood_c, string, trousers, shoe = "9af03a", "2a2a34", "30a8d8", "2a2a34"
        mark = lambda x, y: (C("2a2a34") if (x, y) in ((3, 4), (4, 5), (3, 6)) else None)   # a play-button arrow
        extra = []
    body(s, hoodie_body(hood_c, string, mark=mark))
    hood_down(s, hood_c)
    arms(s, hood_c, sk, sleeve=10, cuff=shade_hex(hood_c, 0.82))
    if v == 1:
        def phones(fc, x, y, w, h):
            if fc == "front" and y == 0 and x in (1, 2, 5, 6):
                return C("2a2a34") if x in (1, 6) else C("ff4a9a")
            if fc == "back" and y == 0 and 1 <= x <= 6:
                return C("2a2a34")
            return None
        s.paint("body", phones, 1)
    legs(s, trousers, shoe, shoe_rows=1, skin=sk if v == 2 else None, bare=6 if v == 2 else None)
    if v == 2:
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: (C("2a2a34") if x % 2 else C("3a3a48")) if y <= 5 and fc not in ("top", "bottom") else None, 1)
            s.paint(lg, lambda fc, x, y, w, h: C("ffffff") if 8 <= y <= 10 and fc != "bottom" else None)   # long socks
    else:
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: shade(C(trousers), 0.8) if y == 10 and fc not in ("top", "bottom") else None)
    for e in extra:
        e(s)
    return s


# ---------------------------------------------------------------------------------------------
# helpers for the fifth batch
# ---------------------------------------------------------------------------------------------

CLEAR = (0, 0, 0, 0)                                      # paint this to take a pixel away again


def angry_brows(col):
    """grumpy brows: the inner ends pulled down to the eyes (row 3), the outer ends up (row 2)"""
    B = C(col)
    return lambda s: s.paint("head", lambda fc, x, y, w, h: B if fc == "front" and (x, y) in ((1, 2), (2, 3), (5, 3), (6, 2)) else None)


def worried_brows(col):
    """brows tilted up in the middle (nervous): the inner ends in row 2, the outer ends in row 3"""
    B = C(col)
    return lambda s: s.paint("head", lambda fc, x, y, w, h: B if fc == "front" and (x, y) in ((1, 3), (2, 2), (5, 2), (6, 3)) else None)


def frown(col):
    """a stern turned-down mouth: the mouth row 6, its corners dropped to row 7"""
    return lambda s: s.paint("head", lambda fc, x, y, w, h: C(col) if fc == "front" and (x, y) in ((3, 6), (4, 6), (2, 7), (5, 7)) else None)


def toothpick(col="e8d49a"):
    """a toothpick out of the corner of the mouth"""
    return lambda s: s.paint("head", lambda fc, x, y, w, h: (C(col) if x == 5 else shade(C(col), 1.1))
                             if fc == "front" and (x, y) in ((5, 6), (6, 6)) else None)


def grin(col, teeth="fafafa"):
    """a cheeky lopsided grin: teeth showing in row 6, one corner hitched up into row 5, the lower lip in row 7"""
    def f(fc, x, y, w, h):
        if fc != "front":
            return None
        if (x, y) in ((2, 6), (5, 6), (6, 5), (3, 7), (4, 7)):
            return C(col)
        if y == 6 and x in (3, 4):
            return C(teeth)
        return None
    return lambda s: s.paint("head", f)


def side_cap(col, bill):
    """a cap turned sideways: the crown over the top and rows 0-1, the bill sticking out over the wearer's right ear"""
    K, B = C(col), C(bill)

    def f(fc, x, y, w, h):
        if fc == "top":
            return K
        if fc == "bottom":
            return None
        if y <= 1:
            return None if (fc == "left" and y == 1 and fwd(fc, x, 8) in (3, 4)) else K   # the strap gap at the other side
        if y == 2 and fc == "right" and 1 <= fwd(fc, x, 8) <= 6:
            return B
        if y == 2 and fc == "front" and x == 0:
            return B
        return None
    return lambda s: s.paint("head", f, layer=1)


def shades_up(lens, frame):
    """sunglasses pushed up onto the head (outer layer, row 1 over the hair)"""
    L, F = C(lens), C(frame)

    def f(fc, x, y, w, h):
        if fc == "front" and y == 1:
            if x in (1, 2, 5, 6):
                return shade(L, 1.6) if x in (1, 5) else L
            return F
        if fc == "front" and y == 0 and x in (1, 2, 5, 6):
            return F
        if fc in SIDES and y == 1 and fwd(fc, x, 8) <= 4:
            return F
        return None
    return lambda s: s.paint("head", f, layer=1)


def big_visor(col):
    """a big sun visor (썬캡): a band round row 1, a deep peak over the brows that wraps round onto the sides"""
    K = C(col)

    def f(fc, x, y, w, h):
        if fc in ("top", "bottom"):
            return None
        if y == 1:
            return shade(K, 1.08) if fc == "front" else K
        if y == 2 and (fc == "front" or (fc in SIDES and fwd(fc, x, 8) <= 2)):
            return shade(K, 0.84)
        return None
    return lambda s: s.paint("head", f, layer=1)


def hard_hat(col):
    """a construction hard hat (outer layer): the dome over the top with a ridge down the middle, rows 0-1, a brim
    in row 2 all round"""
    K = C(col)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return shade(K, 1.12) if x in (3, 4) else K
        if fc == "front" and y <= 1 and x in (3, 4):
            return shade(K, 1.12)                         # the ridge
        if y <= 1:
            return K
        if y == 2:
            return shade(K, 0.8)                          # the brim
        return None
    return lambda s: s.paint("head", f, layer=1)


def head_towel(col, line):
    """a towel tied over the head (수건): over the top and rows 0-1, its woven border in row 2, the knot at the
    forehead"""
    T, L = C(col), C(line)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return shade(T, 0.94) if (x + y) % 3 == 0 else T
        if fc == "front" and y <= 1 and x in (3, 4):
            return shade(T, 0.82) if y == 1 else shade(T, 0.9)   # the knot
        if y <= 1:
            return T
        if y == 2:
            return L
        return None
    return lambda s: s.paint("head", f, layer=1)


def earbud_wire(col="f4f4f4"):
    """wired earphones: a white bud in each ear, the wires down the neck to a Y on the chest and into a pocket"""
    E = C(col)
    path = ((2, 0), (2, 1), (3, 2), (5, 0), (5, 1), (4, 2), (4, 3), (4, 4), (4, 5), (4, 6))

    def hf(fc, x, y, w, h):
        if fc in SIDES and fwd(fc, x, 8) == 3 and 4 <= y <= 7:
            return E if y > 4 else shade(E, 1.05)
        return None

    def bf(fc, x, y, w, h):
        if fc == "front" and (x, y) in path:
            return E
        return None

    def apply(s):
        s.paint("head", hf, 1)
        s.paint("body", bf, 1)
    return apply


def hivis(s, col, strip, neck=True):
    """a high-visibility vest (body outer layer): no sleeves, two reflective bands round the body (rows 5, 9) and
    up over the shoulders, a V neck"""
    V, S = C(col), C(strip)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return S if x in (1, 6) else V
        if neck and fc == "front" and ((y == 0 and 2 <= x <= 5) or (y in (1, 2) and x in (3, 4))):
            return None
        if y in (5, 9):
            return S
        if fc in ("front", "back") and x in (1, 6) and y < 5:
            return S
        if fc == "front" and x == 4 and y > 2:
            return shade(V, 0.85)                         # the zip
        return V
    s.paint("body", f, 1)


def specks(s, parts_, col, k=0, mod=9, layer=0):
    """dust / crumbs: single pixels on a regular sprinkle over the given parts' side faces"""
    D = C(col)
    for p in parts_:
        s.paint(p, lambda fc, x, y, w, h: D if fc not in ("top", "bottom") and (x * 4 + y * 3 + k) % mod == 0 else None, layer)


def mombbae(s, pattern, shoe):
    """몸빼 trousers: the flowery pattern, the gathered cuff in row 10, rubber shoes"""
    for lg in ("rleg", "lleg"):
        def f(fc, x, y, w, h):
            if fc == "bottom":
                return shade(C(shoe), 0.8)
            if y == 11:
                return C(shoe)
            if fc == "top":
                return pattern(0, 1)
            if y == 10:
                return shade(pattern(1, 2), 0.82)
            return pattern(x, y)
        s.paint(lg, f)


def apron(s, col, tie, pocket=True, bib=True, pattern=None, legs_rows=4):
    """an apron (body outer layer): a bib up the chest on a neck strap, the skirt rows 4-11 and on over the front of
    the thighs, ties round the waist (row 5), a pocket"""
    A, T = C(col), C(tie)

    def at(x, y):
        return pattern(x, y) if pattern else A

    def f(fc, x, y, w, h):
        if fc == "front":
            if bib and y <= 3:
                if 2 <= x <= 5:
                    return at(x, y)
                return T if (y == 0 and x in (1, 6)) else None
            if not bib and y < 5:
                return None
            if y == 5:
                return T if x in (0, 7) else at(x, y)
            if pocket and 7 <= y <= 9 and 2 <= x <= 5:
                return shade(A, 0.82) if y == 7 else shade(at(x, y), 0.94)
            return at(x, y)
        if fc in SIDES:
            return T if y == 5 else None
        if fc == "back":
            if y == 5 or (x in (3, 4) and y in (6, 7)):
                return T                                  # the bow at the back
            if y == 0 and 1 <= x <= 6:
                return T if bib else None
        if fc == "top" and bib and x in (1, 6):
            return T
        return None
    s.paint("body", f, 1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: (shade(at(x, y), 0.86) if y == legs_rows - 1 else at(x, y))
                if fc == "front" and y < legs_rows else None, 1)


def toshi(s, pattern, edge):
    """arm covers (토시) over the forearms (outer layer rows 4-9), elastic at both ends"""
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: (C(edge) if y in (4, 9) else pattern(x, y))
                if 4 <= y <= 9 and fc not in ("top", "bottom") else None, 1)


def toast(crust="b8743a", bread="f4d898"):
    """a slice of toast held in the mouth (head outer layer): 4 x 3 at the mouth, a crust round a pale middle"""
    def f(fc, x, y, w, h):
        if fc == "front" and 3 <= x <= 6 and 5 <= y <= 7:
            if y == 6 and x in (4, 5):
                return C(bread)
            if (x, y) == (3, 5):
                return None                               # a bite out of the corner
            return C(crust)
        return None
    return lambda s: s.paint("head", f, layer=1)


def phone_at_ear(skin, case, screen="2a2a30"):
    """a phone pressed to the right ear by a raised hand (head outer layer): the phone on the side of the head, the
    hand round it, the fingers and the phone showing at the face's edge"""
    Sk, Ca, Sc = C(skin), C(case), C(screen)

    def f(fc, x, y, w, h):
        if fc == "right":
            g = fwd(fc, x, 8)
            if 1 <= g <= 4 and 2 <= y <= 6:
                if g in (2, 3) and 3 <= y <= 5:
                    return Sc
                return Ca
            if 1 <= g <= 5 and y == 7 or (g == 5 and 4 <= y <= 6):
                return Sk                                  # the hand
        if fc == "front" and x == 0:
            if 2 <= y <= 4:
                return Ca
            if 5 <= y <= 7:
                return Sk
        if fc == "bottom" and x == 0 and y >= 5:
            return Sk
        return None
    return lambda s: s.paint("head", f, layer=1)


def gold_chain(col="e8c25a"):
    """a chunky gold chain round the neck, lying on the chest (outer layer)"""
    G = C(col)
    path = ((2, 0), (2, 1), (2, 2), (3, 3), (4, 3), (5, 2), (5, 1), (5, 0))

    def f(fc, x, y, w, h):
        if fc == "front" and (x, y) in path:
            return G if (x + y) % 2 else shade(G, 1.18)
        if fc == "back" and y == 0 and 2 <= x <= 5:
            return G
        return None
    return lambda s: s.paint("body", f, layer=1)


def big_backpack(s, col, strap, cover=None):
    """a big school bag for a small kid: over the whole back (rows 0-10), bulging round the sides, a reflector, a
    yellow safety cover over its bottom half if given; straps down the front"""
    B, S = C(col), C(strap)
    Cv = C(cover) if cover else None

    def f(fc, x, y, w, h):
        if fc == "back":
            if y <= 10:
                if y == 0:
                    return shade(B, 1.1)
                if y == 3:
                    return shade(B, 0.8)                  # the flap's edge
                if Cv and y >= 6:
                    return Cv if y > 6 else shade(Cv, 0.85)
                if (x, y) in ((3, 5), (4, 5)):
                    return C("f4f4f4")                     # a reflector
                return B
            return None
        if fc == "top":
            return S if x in (1, 6) else (B if y >= 2 else None)
        if fc in SIDES:
            g = fwd(fc, x)
            if g >= 2 and y <= 10:
                return (Cv if Cv and y >= 6 else B)
            return None
        if fc == "front" and x in (1, 6) and y <= 8:
            return S
        return None
    s.paint("body", f, 1)


SAEKDONG = ("d8303a", "f2c43a", "2f6fd0", "f4f4f0", "3aa05a", "e86aa0")


def saekdong(s, top, skin, start=2):
    """색동 sleeves: the jacket colour at the shoulder, then rainbow stripes one row each down to the wrist"""
    for a in ("rarm", "larm"):
        def f(fc, x, y, w, h):
            if fc == "bottom":
                return C(skin)
            if fc == "top" or y < start:
                return C(top)
            if y <= 9:
                return C(SAEKDONG[(y - start) % len(SAEKDONG)])
            if y == 10:
                return C("f4f4f0")                         # the white cuff
            return C(skin)
        s.paint(a, f)


def hanbok_body(top, skirt, ribbon, collar="f6f4ee", top_rows=5):
    """a 저고리 and 치마: the short jacket rows 0..top_rows-1 with the white collar (동정) crossing, the long ribbon
    (고름) hanging from the knot, the skirt below"""
    T, S, R, Co = C(top), C(skirt), C(ribbon), C(collar)

    def f(fc, x, y, w, h):
        if fc == "front":
            if (x, y) in ((1, 0), (2, 0), (3, 1), (4, 2), (5, 3)):
                return Co
            if (x, y) in ((5, top_rows - 1), (6, top_rows - 1)):
                return shade(R, 0.85)                      # the knot
            if x == 5 and top_rows <= y <= top_rows + 4 or x == 6 and top_rows <= y <= top_rows + 2:
                return R                                   # the ribbon's ends
        if fc == "top":
            return T
        if y < top_rows:
            return T
        if y == top_rows and fc != "bottom":
            return shade(S, 0.88)                          # the skirt's waistband
        return S
    return f


def long_skirt(s, col, shoe, pleat=0.9):
    """a long skirt down to the ankles on both leg layers, pleats on the outer layer, shoes peeking out"""
    K = C(col)
    legs(s, col, shoe, shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: (shade(K, pleat) if x % 2 else K) if fc not in ("top", "bottom") and y <= 10 else None, 1)


def goggal(col, flower1, flower2):
    """a white peaked 고깔 (folded hat): over the top with the fold down the middle, rows 0-1, down over the ears,
    paper flowers on the front"""
    K = C(col)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return shade(K, 0.88) if x in (3, 4) else K
        if fc == "front":
            if y == 0 and x in (3, 4):
                return shade(K, 0.88)                      # the fold
            if (x, y) in ((1, 1), (6, 1)):
                return C(flower1)
            if (x, y) in ((2, 1), (5, 1)):
                return C(flower2)
            if y <= 1 or (y == 2 and x in (0, 7)):
                return K
            return None
        if fc in SIDES:
            return K if y <= 2 or (y <= 4 and fwd(fc, x, 8) >= 2) else None
        if fc == "back":
            return K if y <= 3 else None
        return None
    return lambda s: s.paint("head", f, layer=1)


def jokduri(col, gem1, gem2):
    """a bride's 족두리: a small black crown on the top of the head with jewels, a gold pin (비녀) through the hair
    at the back"""
    K = C(col)

    def f(fc, x, y, w, h):
        if fc == "top":
            if 2 <= x <= 5 and 2 <= y <= 5:
                return C(gem1) if (x, y) in ((3, 3), (4, 4)) else (C(gem2) if (x, y) in ((4, 3), (3, 4)) else K)
            return None
        if fc == "front" and y == 0 and 2 <= x <= 5:
            return C(gem1) if x in (3, 4) else K
        if fc == "back" and y == 0 and 2 <= x <= 5:
            return K
        if fc == "back" and y == 5 and 1 <= x <= 6:
            return C("e8c25a")                             # the 비녀
        return None
    return lambda s: s.paint("head", f, layer=1)


def yeonji(col="e0303a"):
    """연지곤지: a red dot on each cheek and one on the forehead"""
    return lambda s: s.paint("head", lambda fc, x, y, w, h: C(col) if fc == "front" and (x, y) in ((1, 5), (6, 5), (3, 2), (4, 2)) else None)


def veil(col, tiara, gem):
    """a bridal veil (outer layer): a tiara at the hairline, the veil over the back and the sides of the head and on
    down the back of the body"""
    V, T, G = C(col), C(tiara), C(gem)

    def hf(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "front":
            if y == 0 and 1 <= x <= 6:
                return G if x in (3, 4) else T
            return None
        if fc == "top":
            return T if y == 7 else (V if y <= 3 else None)
        if fc == "back":
            return shade(V, 0.96) if (x + y) % 4 == 0 else V
        if fc in SIDES:
            return V if fwd(fc, x, 8) >= 3 else None
        return None

    def bf(fc, x, y, w, h):
        if fc == "back" and y <= 9 and not (y == 9 and x in (0, 7)):
            return shade(V, 0.96) if (x + y) % 4 == 0 else V
        if fc in SIDES and fwd(fc, x) == 3 and y <= 7:
            return V
        return None

    def apply(s):
        s.paint("head", hf, 1)
        s.paint("body", bf, 1)
    return apply


def top_hat(col, band):
    """a magician's top hat (outer layer): the crown over the top with a shine and rows 0, a band in row 1, the brim
    in row 2 all round"""
    K, B = C(col), C(band)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return shade(K, 1.6) if (x == 1 or y == 1) and 1 <= x <= 6 and 1 <= y <= 6 else K
        if y == 0:
            return shade(K, 1.5) if (fc == "front" and x == 1) else K
        if y == 1:
            return B
        if y == 2:
            return shade(K, 1.25)                          # the brim catching the light
        return None
    return lambda s: s.paint("head", f, layer=1)


def cape(s, col, lining):
    """a cape over the shoulders (outer layer): all over the back and the sides of the body and over the outer side
    and the back of the arms, the lining showing along its front edges and the turned-up collar"""
    K, L = C(col), C(lining)

    def bf(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "front":
            if x in (0, 7):
                return L
            if y == 0 and x in (1, 6):
                return L
            return None
        if fc == "top":
            return K
        return K
    s.paint("body", bf, 1)
    for a, outer in (("rarm", "right"), ("larm", "left")):
        s.paint(a, lambda fc, x, y, w, h, outer=outer: (K if fc in ("back", outer, "top") and y <= 10 else
                                                      (L if fc == "front" and y <= 10 and x == (0 if outer == "right" else w - 1) else None)), 1)

    def collar(fc, x, y, w, h):
        if fc == "back" and y >= 6:
            return L
        if fc in SIDES and y >= 6 and fwd(fc, x, 8) >= 4:
            return L
        return None
    s.paint("head", collar, 1)


def police_cap(col, band, bill):
    """a peaked uniform cap (outer layer, no badge): the crown over the top and row 0, a band in row 1, a shiny
    black peak in row 2 at the front"""
    K, B, P = C(col), C(band), C(bill)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return shade(K, 1.08) if 1 <= x <= 6 and 1 <= y <= 6 else K
        if y == 0:
            return K
        if y == 1:
            return B
        if y == 2 and fc == "front":
            return shade(P, 1.6) if x in (2, 3) else P
        if y == 2 and fc in SIDES and fwd(fc, x, 8) == 0:
            return P
        return None
    return lambda s: s.paint("head", f, layer=1)


def mail_bag(s, strap, bag, letter):
    """a postman's bag on a strap across the body (outer layer): the strap from the right shoulder down to the left
    hip, the bag on the hip with a flap and letters peeking out"""
    St, B, Lt = C(strap), C(bag), C(letter)

    def f(fc, x, y, w, h):
        if fc == "front":
            if 4 <= x <= 7 and 7 <= y <= 11:
                if y == 7:
                    return shade(B, 0.8)                   # the flap
                if y == 8 and x == 6:
                    return C("d8c890")                     # the buckle
                return B
            if (x, y) in ((5, 6), (6, 6)):
                return Lt                                  # letters
            if 0 <= y <= 6 and x == y:
                return St
            return None
        if fc == "back" and 0 <= y <= 7 and x == 7 - y:
            return St
        if fc == "top" and x == 0:
            return St
        if fc == "left" and 7 <= y <= 11 and fwd(fc, x) <= 1:
            return B
        return None
    s.paint("body", f, 1)


def stripes(c1, c2, every=2):
    A, B = C(c1), C(c2)
    return lambda x, y: A if y % every == 0 else B


def bow_tie(col, knot=None):
    """a bow tie at the collar (body front): two wings over rows 0-2, the knot between them"""
    K = C(col)
    Kn = C(knot) if knot else shade(K, 0.8)
    return lambda fc, x, y: (K if fc == "front" and x in (2, 5) and y <= 2 else
                             (Kn if fc == "front" and x in (3, 4) and y == 1 else None))


def curl_mustache(col):
    """a waxed handlebar mustache: row 6 under the nose, the tips curled up into row 5"""
    return lambda s: s.paint("head", lambda fc, x, y, w, h: C(col) if fc == "front" and ((y == 6 and 2 <= x <= 5) or (x, y) in ((1, 5), (6, 5))) else None)


def padded(col):
    """a padded jacket: puffy bands, a seam every third row, a zip"""
    K = C(col)

    def f(fc, x, y, w, h):
        if fc == "front" and x == 4 and y >= 1:
            return shade(K, 0.72)
        if fc == "front" and y == 0 and 2 <= x <= 5:
            return shade(K, 0.85)                         # the collar
        if y % 3 == 2:
            return shade(K, 0.84)
        if y % 3 == 0:
            return shade(K, 1.06)
        return K
    return f


def tweed(col, fleck):
    K, F = C(col), C(fleck)
    return lambda x, y: F if (x * 3 + y * 2) % 7 == 0 else (shade(K, 0.92) if (x + y * 2) % 5 == 0 else K)


def pattern_jacket(s, at, open_rows=6, open_cols=(2, 5), lapel=None):
    """jacket() with a patterned cloth: at(x, y) -> colour"""
    a, b = open_cols

    def f(fc, x, y, w, h):
        if fc == "front":
            if y < open_rows and a <= x <= b:
                return None
            if lapel and y < open_rows and x in (a - 1, b + 1):
                return C(lapel)
        return at(x, y)
    s.paint("body", f, layer=1)


def satba(s, col):
    """the 샅바 of 씨름: a thick band round the waist (rows 9-11, outer layer) and a loop round the right thigh"""
    K = C(col)

    def bf(fc, x, y, w, h):
        if fc in ("top", "bottom") or y < 9:
            return None
        return shade(K, 0.82) if y == 9 else (shade(K, 1.08) if (x + y) % 3 == 0 else K)
    s.paint("body", bf, 1)
    s.paint("rleg", lambda fc, x, y, w, h: (K if y == 1 else shade(K, 0.86)) if fc not in ("top", "bottom") and y in (1, 2) else None, 1)
    s.paint("rleg", lambda fc, x, y, w, h: K if fc in ("front", "left") and y == 0 else None, 1)


# ---------------------------------------------------------------------------------------------
# the fifth batch
# ---------------------------------------------------------------------------------------------

def market(v):
    """시장 아줌마: a perm, a flowery 몸빼, an apron, arm covers (토시)"""
    if v == 1:
        # a dark brown perm, a lilac top, a navy apron, pink flowery 몸빼, red-checked 토시
        s, sk = Skin(slim=True), "e0aa84"
        head(s, sk, "3a2620", "perm", mouth="b0504a", blush="eb9a8a", extra=[gold_earrings("e8c25a")])
        shirt, apr, tie, apat = "b89ad0", "2a3a6a", "f4f4f0", None
        mom_ = flowers("e85d8f", "ffe08a", "fff6d8", leaf="6fc26a")
        to, toe, shoe = plaid("e8605a", "b8382f", "8a2a22"), "8a2a22", "3a7ad8"
        pouch = None
    elif v == 2:
        # a wine perm under a pink visor, a white top, a red-checked apron, purple 몸빼, blue 토시, a money belt (전대)
        s, sk = Skin(slim=True), "d49a72"
        head(s, sk, "6a2a3a", "perm", mouth="a8443e", blush="e08a7a", extra=[visor("ff7aa8")])
        shirt, apr, tie, apat = "f2f0ea", "d8403a", "f4f4f0", plaid("d8403a", "f4e8e4", "f8f8f8")
        mom_ = flowers("5b3f8f", "f2c8e8", "ffe08a", leaf="7fc98a")
        to, toe, shoe = (lambda x, y: C("5a9ad0")), "2a5a90", "7a2a3a"
        pouch = ("4a3428", "6a4a32")
    else:
        # a grey perm, glasses, a mint top, a yellow apron, a red flowery 몸빼, flowery 토시
        s, sk = Skin(slim=True), "e8b896"
        head(s, sk, "9a9690", "perm", mouth="b0645a", blush="f0a8a0", extra=[glasses("7a5a3a")])
        shirt, apr, tie, apat = "8ad0b8", "f2c42a", "c89a1a", None
        mom_ = flowers("d8343f", "fff4e8", "ffd34d", leaf="5aa05a")
        to, toe, shoe = flowers("f4f0e0", "e86a9a", "f2c43a", step=(3, 3)), "e86a9a", "2f2f35"
        pouch = None

    def b(fc, x, y, w, h):
        if fc == "front" and y == 0 and x in (3, 4):
            return C(sk)
        return C(shirt)
    body(s, b)
    arms(s, shirt, sk, sleeve=10)
    toshi(s, to, toe)
    mombbae(s, mom_, shoe)
    apron(s, apr, tie, pattern=apat)
    if pouch:
        waist_pouch(*pouch)(s)
    return s


def uncle(v):
    """동네 아저씨: thin on top, a sleeveless undershirt (런닝) or a polo stretched over a belly, slippers"""
    if v in (1, 3):
        if v == 1:
            # balding, a white 런닝, grey shorts, blue slippers
            s, sk = Skin(), "dca07a"
            head(s, sk, "2a2522", "bald", brows="2a2522", mouth="8a4a40")
            run, shorts, slip, bare = "f6f6f2", "7a7e86", "3a6fd8", 6
        else:
            # buzz-cut grey, a light grey ribbed 런닝, a towel round the neck, rolled-up tracksuit trousers, green slippers
            s, sk = Skin(), "c88c62"
            head(s, sk, "8a8680", "buzz", brows="4a4642", mouth="7a3e36", pupils="side")
            run, shorts, slip, bare = "d8dade", "2a3a5a", "3a9a5a", 8
        R = C(run)

        def b(fc, x, y, w, h):
            if fc == "top":
                return C(sk) if x in (0, 7) else R
            if fc == "front":
                if (y == 0 and 2 <= x <= 5) or (y == 1 and x in (3, 4)):
                    return C(sk)                           # the scoop neck
                if y <= 2 and x in (0, 7):
                    return C(sk)                           # the arm holes
                if 6 <= y <= 10 and 2 <= x <= 5:
                    return shade(R, 0.95) if y in (6, 10) else shade(R, 1.02)   # the belly
                if (x, y) == (4, 9):
                    return shade(R, 0.86)
            if fc == "back" and y <= 2 and x in (0, 7):
                return C(sk)
            if v == 3 and fc in ("front", "back") and x % 2 == 0:
                return shade(R, 0.93)                      # the ribbing
            return R
        body(s, b)
        arms(s, sk, sk, sleeve=0)
        legs(s, shorts, slip, shoe_rows=1, skin=sk, bare=bare)
        if v == 3:
            for lg in ("rleg", "lleg"):
                s.paint(lg, lambda fc, x, y, w, h: shade(C(shorts), 0.82) if y == 7 and fc not in ("top", "bottom") else None)   # rolled up
                s.paint(lg, lambda fc, x, y, w, h: C("f4f4f4") if fc in SIDES and x == 1 and y < 7 else None)
            neck_towel("f4f2ea", "e8605a")(s)
    else:
        # short greying hair, a mint polo over a belly, a belt, beige slacks, white socks in brown slippers
        s, sk = Skin(), "e2ab84"
        head(s, sk, "4a4642", "short", brows="3a3632", mouth="8a4a40")
        s.paint("head", lambda fc, x, y, w, h: C("9a9690") if fc in SIDES and fwd(fc, x, 8) >= 4 and y in (3, 4) else None)   # grey at the temples
        P = C("8ad0b8")

        def b(fc, x, y, w, h):
            if fc == "front":
                if y == 0 and x in (3, 4):
                    return C(sk)
                if y == 0 and x in (2, 5):
                    return shade(P, 1.15)                  # the collar
                if x == 4 and y in (1, 2):
                    return shade(P, 0.8)                   # buttons
                if 5 <= y <= 8 and 2 <= x <= 5:
                    return shade(P, 1.06)                  # the belly pushing the polo out
            if y == 9:
                return C("e8c25a") if fc == "front" and x in (3, 4) else C("3a2a1e")   # the belt
            if y >= 10:
                return C("c8b48a")
            return P
        body(s, b)
        arms(s, "8ad0b8", sk, sleeve=4)
        legs(s, "c8b48a", "7a4a2a", shoe_rows=1, sock="f4f4f4")
        slip = "7a4a2a"
    # slippers: open at the heel, the bare (or socked) heel showing at the back, a white band across the front
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: (C("f4f4f4") if fc == "front" and x in (1, 2) else
                                          (C(sk) if v != 2 and fc == "back" else None)) if y == 11 else None)
    return s


def univ(v):
    """대학생: a hoodie, wired earphones, a backpack, messy hair"""
    if v == 1:
        s, sk = Skin(), "e6b48e"
        head(s, sk, "2a2220", "messy", pupils="side")
        hood_c, pack, jeans, shoe = "2a3a66", ("8a8e96", "4a4e56"), "3d5f95", "f0f0f0"
    elif v == 2:
        s, sk = Skin(slim=True), "f2c8a6"
        head(s, sk, "4a2e22", "long", mouth="c05a5a", blush="f2a8a0")
        s.paint("head", lambda fc, x, y, w, h: C("4a2e22") if fc == "front" and (x, y) in ((2, 1), (5, 2)) else None, 1)   # stray strands
        hood_c, pack, jeans, shoe = "d8c8a8", ("26262c", "1a1a1e"), "26262c", "f4f4f4"
    else:
        s, sk = Skin(), "d39a72"
        head(s, sk, "1c1a1a", "messy", mouth="8a4a40", extra=[glasses("2a2a2a"), tired()])
        hood_c, pack, jeans, shoe = "7a2a38", ("5e6a3e", "3e4a2a"), "4a4e57", "2a2a2a"
    body(s, hoodie_body(hood_c, shade_hex(hood_c, 0.7)))
    arms(s, hood_c, sk, sleeve=10, cuff=shade_hex(hood_c, 0.82))
    backpack(s, *pack)
    earbud_wire()(s)
    legs(s, jeans, shoe, shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(C(jeans), 0.88) if fc == "front" and x == 1 and y < 11 else None)
    return s


def worker(v):
    """공사장 인부: a yellow hard hat, a high-vis vest, dusty work clothes, cotton gloves, boots"""
    if v == 1:
        s, sk = Skin(), "c88c62"
        head(s, sk, "1e1a18", "short", mouth="7a3e36")
        cloth, trousers, vestc, strip = "7a808a", "6a604c", "ff7a1a", "e8eef0"
    elif v == 2:
        s, sk = Skin(), "b9784e"
        head(s, sk, "2a2522", "short", brows="2a2522", mouth="7a3e36", extra=[mustache("2a2522")])
        cloth, trousers, vestc, strip = "2a3a5a", "4e5560", "c8f03a", "e8eef0"
    else:
        s, sk = Skin(), "a86e48"
        head(s, sk, "1c1a1a", "buzz", mouth="6a3430", pupils="side", extra=[sweat()])
        cloth, trousers, vestc, strip = "5e6a3e", "5a5040", "c8f03a", "f0a030"
    hard_hat("f2c22a")(s)
    body(s, lambda fc, x, y, w, h: C(sk) if fc == "front" and y == 0 and x in (3, 4) else C(cloth))
    arms(s, cloth, sk, sleeve=10)
    legs(s, trousers, "6a4a2a", shoe_rows=2)
    dust = "%02x%02x%02x" % tuple((a + b) // 2 for a, b in zip(C(cloth)[:3], C("d8ccb0")[:3]))
    specks(s, ("body", "rarm", "larm"), dust, k=v, mod=13)   # dust
    specks(s, ("rleg", "lleg"), "%02x%02x%02x" % tuple((a + b) // 2 for a, b in zip(C(trousers)[:3], C("d8ccb0")[:3])), k=v + 3, mod=11)
    hivis(s, vestc, strip)
    gloves(s, "f0ece0", cuff="d8d0bc")
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: C("d8402a") if fc == "back" and y == 11 else None)   # the red-dipped palms
    boots(s, "5a3e26", rows=2, top="7a5a3a")
    if v == 3:
        neck_towel("f4f2ea", "3a7ad8")(s)
    return s


def kid(v):
    """초등학생: a yellow cap or safety hat, a big school bag, bright clothes, big eyes"""
    if v == 1:
        # a red tee, navy shorts, a blue bag under a yellow safety cover
        s, sk = Skin(), "f2c8a6"
        head(s, sk, "2a1e18", "short", mouth="d0606a", blush="ff9a9a", extra=[big_eyes("2a2026"), cap("ffd23a", "f0b020")])
        tee, shorts, shoe, bag = "e8443a", "2a3a66", "f4f4f4", ("3a7ae0", "2a58a8", "ffe23a")
    elif v == 2:
        # a yellow round safety hat over pigtails, a pink hoodie, a denim skirt, a lilac bag
        s, sk = Skin(slim=True), "f8d8bc"
        head(s, sk, "4a2a1e", "bob", mouth="e0707a", blush="ff9aa8",
             extra=[big_eyes("3a2420"), pigtails("4a2a1e", "ff6a9a"), kid_hat("ffd23a", strap="e8b820")])
        tee, shorts, shoe, bag = "ff8ab8", "5a86c2", "ff6a9a", ("b8a0f0", "8a70c8", None)
    else:
        # a lime tee, khaki shorts, an orange bag, an indoor-shoe bag (실내화 주머니) in hand
        s, sk = Skin(), "c88c62"
        head(s, sk, "151214", "messy", mouth="d0606a", blush="ec8a7a", extra=[big_eyes("1a1416"), cap("ffd23a", "f0b020")])
        tee, shorts, shoe, bag = "9ae03a", "c8b07a", "3a7ae0", ("ff8a2a", "d06a1a", "ffe23a")
    T = C(tee)

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and 2 <= x <= 5:
                return shade(T, 0.8)
            if y == 3 and x == 6:
                return C("ffffff")                         # a name tag
            if v == 1 and y == 5:
                return C("f4f4f4")                         # a white stripe
            if v == 2 and x in (2, 5) and 1 <= y <= 3:
                return C("ffffff")                         # drawstrings
        if v == 1 and y == 5 and fc != "top":
            return C("f4f4f4")
        return T
    body(s, b)
    arms(s, tee, sk, sleeve=10 if v == 2 else 4)
    big_backpack(s, bag[0], bag[1], cover=bag[2])
    if v == 2:
        legs(s, shorts, shoe, shoe_rows=1, skin=sk, bare=4, sock="fafafa")
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: shade(C(shorts), 0.85 if y == 3 else 1.0) if y <= 3 and fc not in ("top", "bottom") else None, 1)
    else:
        legs(s, shorts, shoe, shoe_rows=1, skin=sk, bare=5, sock="ffffff")
    return s


def chicken_grandma(v):
    """닭 든 할머니: a towel over her head, a cardigan, flowery 몸빼 (the hen in her arms is a held model: bus.yml
    types.chicken.hold, models/bus_hold.py)"""
    if v == 1:
        s, sk = Skin(slim=True), "d9a47c"
        head(s, sk, "c9c5c0", "perm", mouth="a0544c", blush="e8968a", extra=[happy_eyes("3a2a2a"), head_towel("f4f2ea", "5a8ad0")])
        blouse, cardi = "efe2c8", "6a4a8a"
        mom_ = flowers("e85d8f", "ffe08a", "fff6d8", leaf="6fc26a")
        shoe = "4a7bd1"
    elif v == 2:
        s, sk = Skin(slim=True), "c98a5c"
        head(s, sk, "e8e4dc", "perm", mouth="9a4a40", blush="d8826a", extra=[head_towel("f2a8c0", "f4f4f0")])
        blouse, cardi = "f5efe2", "3f7a5a"
        mom_ = flowers("5b3f8f", "f2c8e8", "ffe08a", leaf="7fc98a")
        shoe = "7a2a3a"
    else:
        s, sk = Skin(slim=True), "e0aa84"
        head(s, sk, "5a5450", "perm", mouth="a0544c", blush="e8968a", extra=[glasses("8a6a3a"), head_towel("f2d84a", "c89a1a")])
        blouse, cardi = "e8e0d0", "a8323a"
        mom_ = flowers("2a5a8a", "f4f4f0", "ffd34d", leaf=None)
        shoe = "2f2f35"
    body(s, lambda fc, x, y, w, h: C(blouse))
    jacket(s, cardi, open_rows=12, open_cols=(2, 5))
    arms(s, blouse, sk, sleeve=10, layer_cloth=cardi)
    mombbae(s, mom_, shoe)
    return s


def tardy(v):
    """지각생: the uniform thrown on askew, a slice of toast in the mouth, bed hair, a bead of sweat"""
    if v == 1:
        # the tie swung aside, the blazer slipping off one shoulder, the shirt tail out, the bag on one strap
        s, sk = Skin(), "f1c7a5"
        head(s, sk, "1b1b22", "messy", pupils="side", extra=[toast(), sweat()])
        blazer, tie, trousers = "2b3a67", "c0392b", "6b6f78"
    elif v == 2:
        # a girl: the ribbon crooked, hair all over the place, one knee sock down
        s, sk = Skin(slim=True), "f3cdb0"
        head(s, sk, "2b1d18", "long", mouth="c45a5a", blush="f5aaa0", pupils="side", extra=[toast(), sweat()])
        s.paint("head", lambda fc, x, y, w, h: C("2b1d18") if fc == "front" and (x, y) in ((2, 1), (3, 1), (5, 2)) else None, 1)
        blazer, tie, trousers = "7a2638", "d03a3a", "3b4a6b"
    else:
        # the blazer buttoned one off, a cowlick sticking up, indoor slippers still on
        s, sk = Skin(), "c98b5e"
        head(s, sk, "1c1a1a", "short", pupils="out", extra=[toast("a8642a", "f0d088"), sweat()])
        s.paint("head", lambda fc, x, y, w, h: C("1c1a1a") if (fc == "top" and x in (5, 6) and y in (2, 3)) or
                (fc == "front" and y == 0 and x in (5, 6)) or (fc == "back" and y == 0 and x in (1, 2)) else None, 1)   # the cowlick
        blazer, tie, trousers = "1f2a44", "2e6bd1", "2b3346"
    shirt, T = C("f4f4f0"), C(tie)

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and x in (3, 4):
                return C(sk)
            if v != 2 and (x, y) in ((4, 1), (5, 2), (5, 3), (6, 4), (6, 5)):
                return shade(T, 0.8) if y == 1 else T      # the tie swung aside
            if v == 2 and (x, y) in ((3, 1), (4, 2), (5, 1), (2, 2)):
                return T                                    # the crooked ribbon
            if y == 11:
                return shirt if x <= 3 else C("2a2a2a")     # the tail hangs out
        if fc == "back" and y == 11:
            return shirt if x >= 4 else C("2a2a2a")
        return shirt
    body(s, b)
    orows, ocols = (12, (2, 5)) if v != 3 else (6, (3, 4))
    jacket(s, blazer, open_rows=orows, open_cols=ocols, lapel=shade_hex(blazer, 0.82))
    backpack(s, "2f6fd6" if v != 2 else "f2b632", "24489a" if v != 2 else "c48d1c")
    s.paint("body", lambda fc, x, y, w, h: ((C(shade_hex(blazer, 0.82)) if y < orows and x == ocols[1] + 1 else C(blazer))
                                           if (fc == "front" and x == 6 and y <= 7) else (C(blazer) if fc == "top" and x == 6 else None)), 1)   # the bag on one strap
    if v == 1:
        s.paint("body", lambda fc, x, y, w, h: CLEAR if (fc == "front" and x >= 6 and y <= 2) or (fc == "left" and y <= 1) or
                (fc == "top" and x >= 6) else None, 1)        # slipping off the shoulder
    if v == 3:
        s.paint("body", lambda fc, x, y, w, h: (C("d8b04a") if (x, y) in ((2, 6), (5, 8), (2, 10)) else
                                               (shade(C(blazer), 0.8) if (x, y) in ((3, 8), (4, 10)) else None))
                if fc == "front" else None, 1)              # the buttons in the wrong holes
    arms(s, "f4f4f0", sk, sleeve=10, layer_cloth=blazer)
    if v == 1:
        s.paint("larm", lambda fc, x, y, w, h: CLEAR if y <= 1 and fc != "bottom" else None, 1)
    if v == 2:
        skirt = lambda x, y: C("1f2a44") if y == 5 else (C("3b4a6b") if x % 2 else C("4a5c84"))
        legs(s, "5a6d94", "1c1c1c", skin=sk, bare=6, shoe_rows=1)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: skirt(x, y) if y <= 5 and fc not in ("top", "bottom") else None)
            s.paint(lg, lambda fc, x, y, w, h: skirt(x, y) if y <= 5 and fc not in ("top", "bottom") else None, 1)
        s.paint("lleg", lambda fc, x, y, w, h: C("f4f4f4") if fc != "bottom" and y in (7, 8) else None)        # one knee sock up
        s.paint("rleg", lambda fc, x, y, w, h: C("f4f4f4") if fc != "bottom" and y == 10 else None)            # the other slid down
    else:
        legs(s, trousers, "1a1a1a" if v == 1 else "f4f4f4", shoe_rows=1)
        if v == 3:
            s.paint("rleg", lambda fc, x, y, w, h: C("3a7ad8") if fc == "front" and y == 11 and x in (1, 2) else None)   # 실내화
            s.paint("lleg", lambda fc, x, y, w, h: C("3a7ad8") if fc == "front" and y == 11 and x in (1, 2) else None)
        else:
            s.paint("lleg", lambda fc, x, y, w, h: C("c8d0e0") if y == 10 and fc not in ("top", "bottom") else None)   # a sock showing
    return s


def interview(v):
    """면접 청년: a neat navy suit, a white shirt and a tie, slick hair, a folder of papers, a nervous look"""
    if v == 1:
        s, sk = Skin(), "f0c4a0"
        head(s, sk, "16161a", "part", extra=[worried_brows("16161a")])
        suit, tie = "22305a", "c0392b"
    elif v == 2:
        s, sk = Skin(slim=True), "f2cba9"
        head(s, sk, "1e1614", "slick", mouth="c4544e", extra=[worried_brows("1e1614"), ponytail("1e1614", "1e1614", length=2)])
        suit, tie = "22305a", "fafafa"                  # a plain blouse, no tie
    else:
        s, sk = Skin(), "dcae88"
        head(s, sk, "2a2724", "slick", pupils="side", extra=[glasses("3a3a40"), sweat()])
        suit, tie = "1e2a4e", "2e6bd1"
    T = C(tie)

    def b(fc, x, y, w, h):
        if fc == "front" and 3 <= x <= 4 and 1 <= y <= (7 if v != 2 else 2):
            if v == 3 and y % 2 == 0:
                return shade(T, 0.7)                       # a striped tie
            return T if y > 1 else shade(T, 0.82)
        return C("fafafa")
    body(s, b)
    jacket(s, suit, open_rows=6 if v != 2 else 4, open_cols=(2, 5), lapel=shade_hex(suit, 0.8), edge=shade_hex(suit, 0.8))
    s.paint("body", lambda fc, x, y, w, h: C("d8b04a") if fc == "front" and x == 4 and y in (7, 9) else None, 1)   # buttons, done up
    arms(s, "fafafa", sk, sleeve=9, cuff="fafafa", layer_cloth=suit)
    if v == 2:
        legs(s, suit, "1a1a1a", skin=sk, bare=6, shoe_rows=1)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: C(suit) if y <= 5 and fc not in ("top", "bottom") else None, 1)
            s.paint(lg, lambda fc, x, y, w, h: C("e8c0a0") if 6 <= y <= 10 and fc != "bottom" else None)
    else:
        legs(s, suit, "121212", shoe_rows=2)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: shade(C(suit), 0.85) if fc == "front" and x == 1 and y < 10 else None)   # the crease
    return s


def yeot(v):
    """엿장수: a straw hat, a white 한복 under a bright patchwork vest, clown cheeks, the big iron scissors in hand"""
    if v == 1:
        s, sk = Skin(), "e2ab84"
        head(s, sk, "2a2522", "short", mouth="c0303a", extra=[smile("c0303a"), flush("f05a5a", "e83a3a"), straw_hat("d8303a")])
        vestp = lambda x, y: C(SAEKDONG[x % 6])                     # rainbow stripes
    elif v == 2:
        s, sk = Skin(), "c88c62"
        head(s, sk, "1e1a18", "short", mouth="c0303a", extra=[happy_eyes("2a1e18"), smile("c0303a"), flush("f05a5a", "e83a3a"), straw_hat("2f6fd0")])
        vestp = lambda x, y: C(("d8303a", "f2c43a", "3aa05a", "2f6fd0")[(x // 2 + y // 3) % 4])   # patchwork squares
    else:
        s, sk = Skin(), "d39a72"
        head(s, sk, "3a3632", "short", brows="2a2622", mouth="c0303a",
             extra=[mustache("2a2622"), flush("f05a5a", "e83a3a"), straw_hat("3aa05a"),
                    lambda s: s.paint("head", lambda fc, x, y, w, h: (C("f04a8a") if (x, y) in ((5, 1), (6, 0), (6, 2), (7, 1)) else
                                                                      (C("ffe04a") if (x, y) == (6, 1) else None)) if fc == "front" else None, 1)])   # a paper flower
        vestp = flowers("f2c43a", "d8303a", "2f6fd0", step=(4, 4))
    white = "f2efe6"
    body(s, lambda fc, x, y, w, h: (C("c8c4b8") if (x, y) in ((2, 0), (3, 1), (4, 2)) else C(white)) if fc == "front" else C(white))

    def vf(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "front" and 3 <= x <= 4:
            return None
        if fc == "front" and x in (2, 5):
            return C("1e1e22")                              # black trim
        return vestp(x, y)
    s.paint("body", vf, 1)
    arms(s, white, sk, sleeve=10)
    legs(s, white, "1e1e22", shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: C("2f6fd0") if y == 9 and fc not in ("top", "bottom") else None)   # 대님 ankle ties
        s.paint(lg, lambda fc, x, y, w, h: shade(C(white), 0.9) if y <= 8 and fc not in ("top", "bottom") and x % 2 else None, 1)   # baggy 바지
    return s


def traveler(v):
    """외국인 여행객: blond or red hair, sunburnt cheeks, a huge hiking pack, a map in hand, shorts (no flags)"""
    if v == 1:
        s, sk = Skin(), "f6d8c4"
        head(s, sk, "f0d070", "messy", eye="3a6ab0", mouth="c0605a", extra=[flush("f29a88", "f08070")])
        tee, shorts, pack = "3aa0a0", "c8b07a", ("e86a2a", "2a2a2a", "f0d040")
    elif v == 2:
        s, sk = Skin(slim=True), "f8dcc8"
        head(s, sk, "c8502a", "short", eye="3a8a5a", mouth="d0606a",
             extra=[flush("f29a88", "f08a7a"), ponytail("c8502a", "2a2a2a", length=4),
                    lambda s: s.paint("head", lambda fc, x, y, w, h: C("c88a6a") if fc == "front" and (x, y) in ((1, 5), (6, 5), (2, 5)) else None)])   # freckles
        tee, shorts, pack = "f4f4f0", "5a6a4a", ("6a3a8a", "26262c", "e8e8e8")
    else:
        s, sk = Skin(), "f4d4bc"
        head(s, sk, "e8c060", "short", eye="4a7ab0", mouth="c0605a",
             extra=[beard("d8b050"), flush("f29a88", "f08070"), bucket_hat("6f7f4a")])
        tee, shorts, pack = "e8c84a", "6a604c", ("3a7a4a", "1e2a1e", "e8e0c8")
    T = C(tee)
    body(s, lambda fc, x, y, w, h: shade(T, 0.82) if fc == "front" and y == 0 and 2 <= x <= 5 else T)
    arms(s, tee, sk, sleeve=4)
    s.paint("rarm", lambda fc, x, y, w, h: C("f29a88") if fc == "front" and y in (5, 6) else None)   # sunburnt arms
    hiking_pack(s, *pack)
    legs(s, shorts, "6a4a30", shoe_rows=2, skin=sk, bare=5, sock="f4f4f4")
    return s


def phone_man(v):
    """큰소리 통화남: a flashy shirt open at the neck, a gold chain, a phone held to his ear, mouth wide open"""
    if v == 1:
        s, sk = Skin(), "e2ab84"
        shirt = flowers("6a2a8a", "e8c25a", "fff0a0", step=(4, 4))
        trousers, shoe, hair = "f2f0ea", "f4f4f4", "16161a"
    elif v == 2:
        s, sk = Skin(), "c88c62"
        shirt = lambda x, y: C("3a2414") if (x * 3 + y * 5) % 7 in (0, 1) and (x + y) % 2 else (C("c8902a") if (x + y * 2) % 5 == 0 else C("e8b860"))   # leopard
        trousers, shoe, hair = "1a1a1e", "f4f4f4", "2a2420"
    else:
        s, sk = Skin(), "dcaa86"
        shirt = flowers("d8303a", "f4f4f0", "2a2a2a", leaf=None, step=(4, 4))
        trousers, shoe, hair = "d8c8a6", "6a3a2a", "3a3632"
    head(s, sk, hair, "slick", brows=hair, mouth="5a1e22", pupils="side",
         extra=[chatter(shade_hex(sk, 0.7)), phone_at_ear(sk, "e8c25a")])

    def b(fc, x, y, w, h):
        if fc == "front" and ((y <= 1 and 2 <= x <= 5) or (y == 2 and x in (3, 4))):
            return C(sk)                                    # open down to the chest
        return shirt(x, y)
    body(s, b)
    gold_chain()(s)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: C(sk) if fc == "bottom" or y >= 4 else shirt(x + 1, y))
    s.paint("larm", lambda fc, x, y, w, h: C("e8c25a") if y == 9 and fc not in ("top", "bottom") else None)   # a gold watch
    s.paint("rarm", lambda fc, x, y, w, h: C(sk) if fc == "bottom" else None)
    legs(s, trousers, shoe, shoe_rows=1)
    s.paint("body", lambda fc, x, y, w, h: (C("e8c25a") if fc == "front" and x in (3, 4) else C("1e1e22")) if y == 11 and fc not in ("top", "bottom") else None)   # a belt
    return s


def rude(v):
    """반말 아저씨: a frown under angry brows, a golf cap, a windbreaker, a toothpick"""
    if v == 1:
        s, sk = Skin(), "dca07a"
        capc, bill, wb, trousers = "f4f4f0", "2a3a66", "2a3a66", "6a6c72"
    elif v == 2:
        s, sk = Skin(), "c48a62"
        capc, bill, wb, trousers = "d8c8a6", "a8323a", "7a2a38", "4a4e57"
    else:
        s, sk = Skin(), "e2ab84"
        capc, bill, wb, trousers = "2a2a2e", "e8c25a", "5e6a3e", "8a8070"
    head(s, sk, "2a2522" if v != 3 else "7a7670", "short", mouth="6a3430",
         extra=[angry_brows("1e1a18"), frown("6a3430"), toothpick(), cap(capc, bill)])
    W = C(wb)

    def b(fc, x, y, w, h):
        if fc == "front":
            if x == 4:
                return C("c8ccd0") if y <= 1 else shade(W, 0.75)   # the zip
            if y == 0 and x in (2, 3, 5):
                return shade(W, 0.85)                      # the collar
            if 6 <= y <= 9 and 1 <= x <= 6 and x != 4:
                return shade(W, 1.07)                      # the belly
            if y == 11:
                return shade(W, 0.82)
        if fc in SIDES and x == 1:
            return C("e8e8e0") if v != 3 else C("d8c8a6")  # the piping
        return W
    body(s, b)
    arms(s, wb, sk, sleeve=10, cuff=shade_hex(wb, 0.75))
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: (C("e8e8e0") if v != 3 else C("d8c8a6")) if fc in SIDES and x == 1 and y < 10 else None)
    legs(s, trousers, "2a2a2a", shoe_rows=1)
    return s


def dodger(v):
    """무임승차 청년: a track suit, the hood up or a cap backwards, eyes sliding sideways, a bead of sweat"""
    if v == 1:
        s, sk = Skin(), "e6b48e"
        ts, stripe = "1e1e24", "f4f4f4"
        hat = [hood(ts)]
    elif v == 2:
        s, sk = Skin(), "c88c62"
        ts, stripe = "2a3a66", "e8c84a"
        hat = [back_cap("d8303a", "a8222a")]
    else:
        s, sk = Skin(), "d39a72"
        ts, stripe = "6a2232", "f4f4f4"
        hat = [cap("2a2a2e", "1a1a1e"), hood(ts)]
    head(s, sk, "1c1a1a", "short", mouth="7a3e36", pupils="side", extra=[sweat()] + hat)
    T = C(ts)

    def b(fc, x, y, w, h):
        if fc == "front":
            if x == 4:
                return C("c8ccd0") if y <= 1 else shade(T, 0.7)   # the zip, done right up
            if y == 0 and 2 <= x <= 5:
                return shade(T, 0.8)
            if y == 7 and x in (1, 6):
                return shade(T, 0.75)                      # pocket slits
        if fc in SIDES and x == 1:
            return C(stripe)
        return T
    body(s, b)
    if v != 2:
        s.paint("body", lambda fc, x, y, w, h: shade(T, 0.85) if fc == "back" and y <= 1 and 1 <= x <= 6 else None, 1)   # the hood's back
    for p in ("rarm", "larm", "rleg", "lleg"):
        s.paint(p, lambda fc, x, y, w, h: C(stripe) if fc in SIDES and x == 1 else T)
    arms(s, ts, sk, sleeve=10, cuff=shade_hex(ts, 0.75))
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: C(stripe) if fc in SIDES and x == 1 and y < 10 else None)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: (C(stripe) if fc in SIDES and x == 1 else T) if fc != "bottom" else C("f4f4f4"))
        s.paint(lg, lambda fc, x, y, w, h: C("f4f4f4") if y == 11 or fc == "bottom" else None)   # white trainers
    return s


def prankster(v):
    """벨 장난 꼬마: a small kid, a cap on sideways, a cheeky grin, a striped shirt, a plaster on the knee"""
    if v == 1:
        s, sk = Skin(), "f2c8a6"
        head(s, sk, "2a1e18", "messy", mouth="c0505a", blush="ff9a9a", extra=[big_eyes("2a2026"), grin("a03040"), side_cap("2f6fd0", "1f4fa0")])
        st, shorts, shoe = stripes("d8303a", "f4f4f4"), "2a3a66", "f4f4f4"
    elif v == 2:
        s, sk = Skin(), "c88c62"
        head(s, sk, "151214", "short", mouth="c0505a", blush="ec8a7a", extra=[big_eyes("1a1416"), grin("8a2a30"), side_cap("d8303a", "a8222a")])
        st, shorts, shoe = stripes("2f6fd0", "f4f4f4"), "c8b07a", "e8443a"
    else:
        s, sk = Skin(slim=True), "f8d8bc"
        head(s, sk, "5a3424", "bob", mouth="c0505a", blush="ff9aa8",
             extra=[big_eyes("3a2420"), grin("a03040"), side_cap("3aa05a", "2a7a40"), ponytail("5a3424", "ff6a9a", length=2)])
        st, shorts, shoe = stripes("f2c43a", "2a3a66"), "5a86c2", "ff6a9a"
    body(s, lambda fc, x, y, w, h: st(x, y + 1))
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: C(sk) if fc == "bottom" or y >= 4 else st(x, y + 1))
    legs(s, shorts, shoe, shoe_rows=1, skin=sk, bare=5, sock="ffffff")
    s.paint("rleg", lambda fc, x, y, w, h: (C("f0d0a0") if x in (1, 2) else None) if fc == "front" and y == 6 else None)   # a plaster
    return s


def spread(v):
    """쩍벌남: an oversized tracksuit, sunglasses pushed up on the head, a smug look, big trainers"""
    if v == 1:
        s, sk = Skin(), "e2ab84"
        ts, trim, shoe = "2a3a66", "f4f4f4", "f4f4f4"
    elif v == 2:
        s, sk = Skin(), "c88c62"
        ts, trim, shoe = "5a2a7a", "e8c25a", "1e1e22"
    else:
        s, sk = Skin(), "dcaa86"
        ts, trim, shoe = "8a8e96", "1e1e22", "f4f4f4"
    head(s, sk, "1e1a18", "short", mouth="8a4a40", pupils="out",
         extra=[shades_up(("3a8ad8", "e05a9a", "f08a2a")[v - 1], "e8c25a" if v == 2 else "f4f4f4"), lambda s: s.paint("head", lambda fc, x, y, w, h: C("8a4a40") if fc == "front" and (x, y) == (5, 5) else None)])
    T = C(ts)

    def b(fc, x, y, w, h):
        if fc == "front":
            if x == 4 and y >= 2:
                return shade(T, 0.75)
            if y <= 1 and 3 <= x <= 4:
                return C("f4f4f4")                         # a white tee under the zip
            if y == 11:
                return shade(T, 0.8)
        if fc in SIDES and x in (1, 2):
            return C(trim) if x == 1 else T
        return T
    body(s, b)
    s.paint("body", lambda fc, x, y, w, h: (C(trim) if fc in SIDES and x == 1 else (shade(T, 0.92) if (x + y) % 5 == 0 else T))
            if fc != "bottom" and not (fc == "front" and (x == 4 or (y <= 1 and x == 3))) else None, 1)   # baggy
    arms(s, ts, sk, sleeve=11, layer_cloth=ts)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: C(trim) if fc in SIDES and x == 1 and y < 11 else None, 1)
    legs(s, ts, shoe, shoe_rows=2)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: (C(trim) if fc in SIDES and x == 1 else (shade(T, 0.9) if y in (3, 7) else T))
                if fc not in ("top", "bottom") and y <= 9 else None, 1)   # baggy trousers
        s.paint(lg, lambda fc, x, y, w, h: C(shoe) if fc not in ("top",) and y >= 10 else None, 1)   # chunky trainers
        s.paint(lg, lambda fc, x, y, w, h: C("c8c8c8") if y == 11 and fc not in ("top", "bottom") else None, 1)
    return s


def complainer(v):
    """민원 아줌마: a short perm, a big sun visor, sunglasses, a quilted vest, a stern mouth"""
    if v == 1:
        s, sk = Skin(slim=True), "e8b896"
        hair, vis, lens, vestc, blouse, slacks = "2a2024", "f4f4f0", "1a1a20", "6a3a8a", "f0b0c8", "2a2a30"
    elif v == 2:
        s, sk = Skin(slim=True), "d49a72"
        hair, vis, lens, vestc, blouse, slacks = "5a2a2a", "ff7aa8", "5a3a1e", "1e2a4e", "f4f4f0", "d8c8a6"
    else:
        s, sk = Skin(slim=True), "e0aa84"
        hair, vis, lens, vestc, blouse, slacks = "3a3632", "e8d8b0", "2a3a2a", "c8323a", "e8e0d0", "4a4e57"
    head(s, sk, hair, "perm", mouth="8a3a3a",
         extra=[sunglasses(lens, "2a2a2a", shade_hex(lens, 2.2)), frown("8a3a3a"), big_visor(vis), gold_earrings("e8c25a")])
    body(s, lambda fc, x, y, w, h: C(sk) if fc == "front" and y == 0 and x in (3, 4) else C(blouse))
    V = C(vestc)

    def vf(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "front" and y == 0 and x in (3, 4):
            return None
        if fc == "front" and x == 4:
            return shade(V, 0.7)                           # the zip
        if y == 0:
            return shade(V, 0.9)                           # the collar
        if (x + y) % 4 == 0 or (x - y) % 4 == 0:
            return shade(V, 0.82)                          # the diamond quilting
        return V
    s.paint("body", vf, 1)
    arms(s, blouse, sk, sleeve=10)
    legs(s, slacks, "1e1e22", shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(C(slacks), 0.88) if fc == "front" and x == 1 and y < 11 else None)
    return s


def crumbs(v):
    """과자 부스러기 학생: headphones on, an open snack bag in hand, crumbs down the front and round the mouth"""
    if v == 1:
        # a white school shirt under a navy sweater vest, a red snack bag
        s, sk = Skin(), "f1c7a5"
        head(s, sk, "1b1b22", "short", mouth="a8584f", pupils="side", extra=[headphones("1e1e24", "e8443a")])
        trousers = "2b3346"
    elif v == 2:
        # a beige cardigan, a plaid skirt, a yellow snack bag, white headphones
        s, sk = Skin(slim=True), "f3cdb0"
        head(s, sk, "3a2620", "long", mouth="c45a5a", blush="f5aaa0", extra=[headphones("f4f4f4", "b8a0f0")])
        trousers = "3b4a6b"
    else:
        # an orange hoodie, a blue snack bag
        s, sk = Skin(), "d39a72"
        head(s, sk, "2a1e16", "messy", mouth="8a3e36", extra=[headphones("2a2a34", "30d0f0")])
        trousers = "4a4e57"
    if v == 1:
        body(s, lambda fc, x, y, w, h: C(sk) if fc == "front" and y == 0 and x in (3, 4) else C("f4f4f0"))
        Sv = C("2b3a67")

        def sweater(fc, x, y, w, h):
            if fc == "bottom":
                return None
            if fc == "front" and ((y == 0 and 1 <= x <= 6) or (y == 1 and 2 <= x <= 5) or (y == 2 and 3 <= x <= 4)):
                return None                                 # the V neck
            return shade(Sv, 0.85) if y == 11 else Sv
        s.paint("body", sweater, 1)
        arms(s, "f4f4f0", sk, sleeve=4)
    elif v == 2:
        body(s, lambda fc, x, y, w, h: C("f4f4f0"))
        jacket(s, "d8c8a0", open_rows=12, open_cols=(3, 4), lapel=shade_hex("d8c8a0", 0.85))
        arms(s, "f4f4f0", sk, sleeve=10, layer_cloth="d8c8a0")
    else:
        body(s, hoodie_body("f08a2a", "f4f4f4"))
        hood_down(s, "f08a2a")
        arms(s, "f08a2a", sk, sleeve=10, cuff=shade_hex("f08a2a", 0.82))
    snack_crumb = C("e0b060")

    def cr(fc, x, y, w, h):
        if fc == "front" and (x, y) in ((1, 4), (5, 6), (2, 8), (6, 9), (3, 11), (6, 2)):
            return snack_crumb if (x + y) % 2 else shade(snack_crumb, 0.82)
        return None
    s.paint("body", cr, 1)
    s.paint("head", lambda fc, x, y, w, h: snack_crumb if fc == "front" and (x, y) in ((2, 7), (5, 7), (5, 6)) else None)   # crumbs round the mouth
    if v == 2:
        skirt = plaid("3b4a6b", "5a6d94", "7a2638")
        legs(s, "5a6d94", "1c1c1c", skin=sk, bare=6, sock="f4f4f4", shoe_rows=1)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: skirt(x, y) if y <= 5 and fc not in ("top", "bottom") else None, 1)
    else:
        legs(s, trousers, "1a1a1a" if v == 1 else "f4f4f4", shoe_rows=1)
    s.paint("lleg", lambda fc, x, y, w, h: snack_crumb if fc == "front" and (x, y) in ((1, 2), (2, 6)) else None, 1)
    return s


def gentleman(v):
    """친절한 신사: grey hair, round glasses, a gentle smile, a tweed jacket, a bow tie, a pocket square"""
    if v == 1:
        s, sk = Skin(), "e8b896"
        head(s, sk, "c8c4bc", "part", brows="b8b4ac", mouth="a8584f", extra=[glasses("8a6a3a"), smile("a8584f")])
        tw, bt, sq, trousers = tweed("8a6a4a", "a2825e"), "c0303a", "f4f4f0", "5a5650"
    elif v == 2:
        s, sk = Skin(), "dca07a"
        head(s, sk, "e8e4dc", "part", brows="f2efe8", mouth="8a4a40", extra=[glasses("3a3a40"), mustache("eeeae2")])
        tw, bt, sq, trousers = tweed("7a7e84", "92969c"), "2a3a66", "c0303a", "3a3e48"
    else:
        s, sk = Skin(), "d09a74"
        head(s, sk, "9a9690", "part", brows="8a8680", mouth="8a4a40",
             extra=[glasses("c9a227"), smile("8a4a40"), flat_cap("6a5440", "8a7258")])
        tw, bt, sq, trousers = tweed("5a6a4a", "6e7e5c"), "7a2a38", "e8c25a", "6a604c"
    bow = bow_tie(bt)

    def b(fc, x, y, w, h):
        c = bow(fc, x, y)
        if c:
            return c
        if fc == "front" and y >= 2 and 2 <= x <= 5:
            return C("c8b88a") if v != 2 else C("e8e0d0")   # a waistcoat / a knit
        return C("f4f4f0")
    body(s, b)
    pattern_jacket(s, tw, open_rows=8, open_cols=(2, 5), lapel=None)
    s.paint("body", lambda fc, x, y, w, h: C(sq) if fc == "front" and (x, y) in ((1, 3), (0, 3)) else None, 1)   # the pocket square
    s.paint("body", lambda fc, x, y, w, h: shade(tw(x, y), 0.8) if fc == "front" and x in (1, 6) and y < 8 else None, 1)   # the lapels
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: C(sk) if fc == "bottom" or y >= 10 else (C("f4f4f0") if y == 9 else tw(x, y)))
        s.paint(a, lambda fc, x, y, w, h: (shade(tw(x, y), 0.8) if y == 8 else tw(x + 1, y)) if fc != "bottom" and y <= 8 else None, 1)
        s.paint(a, lambda fc, x, y, w, h: shade(tw(x, y), 0.78) if fc in SIDES and x == 1 and y in (6, 7) else None, 1)   # elbow patch
    legs(s, trousers, "6a3a1e", shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(C(trousers), 0.88) if fc == "front" and x == 1 and y < 11 else None)
    return s


def shaman(v):
    """무당: a 한복 in the five colours (오방색), 색동 sleeves, a ritual headpiece, bells and a fan"""
    if v == 1:
        # a white 고깔, a red 저고리, a blue 치마, bells
        s, sk = Skin(slim=True), "f0c8a6"
        head(s, sk, "1a1414", "slick", mouth="c0222e", extra=[lips("c0222e"), goggal("f6f4ee", "e0303a", "f2c43a")])
        top, skirt, ribbon = "d8303a", "2a4ab0", "f2c43a"
    elif v == 2:
        # a black hat with a red tassel and feathers, a blue sleeveless 쾌자 over a red robe, a red skirt, a fan
        s, sk = Skin(slim=True), "e0aa84"
        hat = gat("1a1a22")
        feather = lambda s: s.paint("head", lambda fc, x, y, w, h: (C("d8303a") if (fc == "top" and 3 <= x <= 4 and 3 <= y <= 4) else
                                                                    (C("3aa05a") if fc == "right" and fwd(fc, x, 8) in (4, 5) and y <= 1 else
                                                                     (C("f2c43a") if fc == "right" and fwd(fc, x, 8) == 6 and y <= 1 else None))), 1)
        head(s, sk, "1a1414", "slick", mouth="b0202a", extra=[lips("b0202a"), hat, feather])
        top, skirt, ribbon = "d8303a", "d8303a", "f2c43a"
    else:
        # a red headband with a yellow knot, a yellow 저고리, a red 치마, bells and a fan
        s, sk = Skin(slim=True), "e8b896"
        band = lambda s: s.paint("head", lambda fc, x, y, w, h: (C("d8303a") if y == 2 else
                                                                 (C("f2c43a") if fc == "back" and x in (3, 4) and y in (3, 4) else None))
                                 if fc not in ("top", "bottom") else None, 1)
        head(s, sk, "1a1414", "long", mouth="c0222e", extra=[lips("c0222e"), band])
        top, skirt, ribbon = "f2c43a", "d8303a", "2a4ab0"
    body(s, hanbok_body(top, skirt, ribbon, top_rows=5 if v != 2 else 12))
    saekdong(s, top, sk)
    if v == 2:
        K = C("2a4ab0")

        def kwaeja(fc, x, y, w, h):
            if fc == "bottom":
                return None
            if fc == "front" and 3 <= x <= 4:
                return None
            if fc == "front" and x in (2, 5):
                return C("f2c43a")                          # the trim
            return K
        s.paint("body", kwaeja, 1)
    long_skirt(s, skirt, "f4f4f0")
    return s


def mailman(v):
    """우체부: a postal-red cap and uniform (no emblem), a leather mail bag with letters peeking out"""
    red = "c8282a"
    R = C(red)
    if v == 1:
        s, sk = Skin(), "e0a882"
        head(s, sk, "1e1c1a", "short", mouth="8a4a40", extra=[cap(red, "1e1e22")])
        trousers = "2a3346"
    elif v == 2:
        s, sk = Skin(slim=True), "f0c6a4"
        head(s, sk, "2e1e18", "short", mouth="b85a50", blush="f0a898", extra=[cap(red, "1e1e22"), ponytail("2e1e18", "c8282a", length=3)])
        trousers = "2a3346"
    else:
        s, sk = Skin(), "c88c62"
        head(s, sk, "5a5450", "short", brows="2e2a26", mouth="7a3e36", extra=[mustache("4a4440"), cap(red, "1e1e22")])
        trousers = "4a4e57"

    def b(fc, x, y, w, h):
        if fc == "front":
            if v == 1:
                if y == 0 and x in (1, 2, 5, 6):
                    return C("f4f4f0")                     # the polo collar
                if x in (3, 4) and y <= 2:
                    return C(sk) if y == 0 else shade(R, 0.8)
            else:
                if y == 0 and 1 <= x <= 6:
                    return shade(R, 0.8) if x not in (3, 4) else C("f4f4f0")
                if x == 4 and y >= 1:
                    return shade(R, 0.72)                  # the zip
                if v == 3 and y % 3 == 2:
                    return shade(R, 0.85)                  # the padded vest's seams
            if y == 3 and x in (1, 2, 5, 6):
                return shade(R, 0.84)                      # pocket flaps
            if y == 11:
                return shade(R, 0.82)
        if fc in SIDES and x == 1:
            return C("1e1e22")                             # a dark seam
        return R
    body(s, b)
    if v == 3:
        arms(s, "8a8e96", sk, sleeve=10, cuff="6a6e74")
    else:
        arms(s, red, sk, sleeve=4 if v == 1 else 10, cuff=None if v == 1 else shade_hex(red, 0.8))
    mail_bag(s, "5a3a22", "8a5a2a", "f4f4f0")
    legs(s, trousers, "1a1a1a", shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: R if fc in SIDES and x == 1 and y < 11 else None)   # a red stripe down the side
    return s


def bungeo(v):
    """붕어빵 장수: a knit beanie, a padded jacket, an apron with a little fish-bread on it, mittens, rosy cheeks"""
    if v == 1:
        s, sk = Skin(), "e2ab84"
        head(s, sk, "2a2522", "short", mouth="8a4a40", blush="f0907a", extra=[beanie("7a7e84", "5e6268", pom="f4f4f0")])
        jk, apr, tie, mit, trousers = "2a3a66", "c8323a", "f4f4f0", "8a5a2a", "4a4e57"
    elif v == 2:
        s, sk = Skin(slim=True), "f2c8a6"
        head(s, sk, "4a2e22", "bob", mouth="c05a5a", blush="f59a8a", extra=[beanie("e86a9a", "c84a7a", pom="f4f4f0")])
        jk, apr, tie, mit, trousers = "ece4d4", "3a8a5a", "f4f4f0", "d8303a", "26262c"
    else:
        s, sk = Skin(), "c48a62"
        head(s, sk, "3a3632", "short", brows="2a2622", mouth="7a3e36", blush="e0806a",
             extra=[mustache("2a2622"), beanie("1e1e24", "2a2a30")])
        jk, apr, tie, mit, trousers = "5e6a3e", "f4f4f0", "c8b48a", "7a7e84", "5a5040"
    pf = padded(jk)
    body(s, lambda fc, x, y, w, h: pf(fc, x, y, w, h))
    s.paint("body", lambda fc, x, y, w, h: pf(fc, x, y, w, h) if fc != "bottom" else None, 1)   # puffy
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: C(sk) if fc == "bottom" else pf("side", x, y, w, h))
        s.paint(a, lambda fc, x, y, w, h: pf("side", x, y, w, h) if fc != "bottom" and y <= 9 else None, 1)
    gloves(s, mit, cuff=shade_hex(mit, 1.2))
    fish = {(3, 7), (4, 7), (6, 7), (2, 8), (3, 8), (4, 8), (5, 8), (3, 9), (4, 9), (6, 9)}
    A = C(apr)
    apron(s, apr, tie, pocket=False, pattern=lambda x, y: (C("c8822a") if (x, y) in fish else A))
    s.paint("body", lambda fc, x, y, w, h: C("3a2a1e") if fc == "front" and (x, y) == (3, 8) else None, 1)   # the fish's eye
    legs(s, trousers, "2a2a2a", shoe_rows=2)
    return s


def samo(col, wing):
    """a groom's 사모 (the black gauze hat of 사모관대): a round crown over the top and rows 0-2, a raised back
    (rows 0-3 behind), and its two flat wings (뿔) standing out at the back of each side"""
    K, Wg = C(col), C(wing)

    def f(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "top":
            return shade(K, 1.1) if 2 <= x <= 5 and 1 <= y <= 4 else K
        if fc == "front":
            return K if y <= 1 or (y == 2 and x in (0, 7)) else None
        if fc == "back":
            return K if y <= 3 else None
        if fc in SIDES:
            f_ = fwd(fc, x, 8)
            if f_ >= 6 and 1 <= y <= 3:
                return Wg                                  # the wings
            return K if y <= 1 or (y == 2 and f_ >= 2) else None
        return None
    return lambda s: s.paint("head", f, layer=1)


def bride(v):
    """신혼부부: the bride in white with a veil, the groom in a black tuxedo, the bride again in a wedding 한복, and her
    groom in 사모관대 (look 4: the partner of look 3; bus.yml types.bride.pairs)"""
    if v == 4:
        # the 한복 groom: a black 사모 with its wings, a deep blue round-collared 단령 robe to the ankles, a 흉배 (a gold
        # square with a white crane) on the chest, a red 각대 belt with gold plaques, black 목화 boots
        s, sk = Skin(), "f0c4a0"
        head(s, sk, "16161a", "slick", mouth="a8584f", extra=[smile("a8584f"), samo("1a1a22", "2c2c36")])
        robe = "24408c"
        R = C(robe)

        def b(fc, x, y, w, h):
            if fc == "front":
                if (x, y) in ((2, 0), (5, 0), (3, 1), (4, 1)):
                    return C("f6f4ee")                     # the round collar's white inner edge
                if y == 0 and x in (3, 4):
                    return C(sk)
                if 3 <= y <= 6 and 2 <= x <= 5:
                    if (x, y) in ((3, 4), (4, 4), (4, 5)):
                        return C("fafafa")                 # the crane
                    if (x, y) == (3, 5):
                        return C("d8303a")                 # its red crown
                    return C("e8c25a") if y in (3, 6) or x in (2, 5) else C("2a3a8a")
            if y == 8 and fc != "top":
                return C("e8c25a") if fc == "front" and x in (1, 3, 4, 6) else C("b8202a")   # the 각대
            if fc in ("front", "back") and y == 9 and x in (0, 7):
                return C("b8202a")                         # the belt hanging low at the sides
            return R
        body(s, b)
        arms(s, robe, sk, sleeve=10, cuff=shade_hex(robe, 0.8))
        s.paint("rarm", lambda fc, x, y, w, h: shade(R, 0.9) if fc not in ("top", "bottom") and y < 10 else None, 1)   # the wide sleeves
        s.paint("larm", lambda fc, x, y, w, h: shade(R, 0.9) if fc not in ("top", "bottom") and y < 10 else None, 1)
        legs(s, robe, "141418", shoe_rows=2)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: (shade(R, 0.9) if x % 2 else R) if fc not in ("top", "bottom") and y <= 9 else None, 1)
            s.paint(lg, lambda fc, x, y, w, h: C("f4f4f0") if fc == "front" and y == 9 else None)   # white 버선 at the boot tops
        return s
    if v == 1:
        # a white dress with a sweetheart neckline, a veil and a tiara, a bouquet
        s, sk = Skin(slim=True), "f6d0b2"
        head(s, sk, "5a3a2a", "bob", mouth="d0506a", blush="f8b0b0", extra=[lips("d0506a"), veil("fafaff", "d8dce4", "a8d8ff")])
        W = C("fafafa")

        def b(fc, x, y, w, h):
            if fc == "top":
                return C(sk)
            if y == 0 or (y == 1 and x in (0, 3, 4, 7)):
                return C(sk)                               # bare shoulders, the sweetheart neckline
            if y == 5 and fc != "bottom":
                return C("e8e0f0")                         # a satin sash
            if y in (2, 3) and (x + y) % 3 == 0:
                return C("ecece8")                         # lace
            return W
        body(s, b)
        arms(s, sk, sk, sleeve=0)

        long_skirt(s, "fafafa", "f4f4f4", pleat=0.95)
        return s
    if v == 2:
        # the groom: a black tuxedo, a white shirt, a black bow tie, a white flower on the lapel, slick hair
        s, sk = Skin(), "f0c4a0"
        head(s, sk, "16161a", "slick", mouth="a8584f", extra=[smile("a8584f")])
        bow = bow_tie("141418", "2c2c34")

        def b(fc, x, y, w, h):
            c = bow(fc, x, y)
            if c:
                return c
            if fc == "front" and 3 <= x <= 4 and y in (4, 6, 8):
                return C("e0e0e0")                         # studs
            return C("fafafa")
        body(s, b)
        jacket(s, "15151a", open_rows=9, open_cols=(2, 5), lapel="34343e", edge="34343e")
        s.paint("body", lambda fc, x, y, w, h: (C("fafafa") if (x, y) == (1, 3) else (C("f8c8d8") if (x, y) == (1, 4) else
                                               (C("4a9a4a") if (x, y) == (1, 5) else None))) if fc == "front" else None, 1)   # the boutonniere
        arms(s, "fafafa", sk, sleeve=9, cuff="fafafa", layer_cloth="15151a")
        legs(s, "15151a", "050505", shoe_rows=2)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: C("34343e") if fc in SIDES and x == 1 and y < 10 else None)
        return s
    # the 한복 bride: a green 저고리 with 색동 sleeves over a red 치마, a 족두리, 연지곤지, the hair in a low bun
    s, sk = Skin(slim=True), "f8d8c0"
    head(s, sk, "1a1414", "slick", mouth="d0303a", extra=[lips("d0303a"), yeonji(), jokduri("1a1a22", "e0303a", "3aa0d8")])
    top, skirt = "7ac04a", "d8202a"                        # 녹의홍상: a green jacket over a red skirt
    body(s, hanbok_body(top, skirt, "8a1a6a", collar="f6f4ee", top_rows=5))
    saekdong(s, top, sk, start=3)
    s.paint("rarm", lambda fc, x, y, w, h: C("f4f4f0") if y >= 10 and fc != "top" else None, 1)   # the long white 한삼 over the hands
    s.paint("larm", lambda fc, x, y, w, h: C("f4f4f0") if y >= 10 and fc != "top" else None, 1)
    long_skirt(s, skirt, "f4f4f0")
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: C("e8c25a") if fc not in ("top", "bottom") and y == 8 and x % 2 == 0 else None, 1)   # a gold-leaf border (금박)
    return s


def police(v):
    """경찰: a police-blue uniform and peaked cap (no badge, no lettering), reflective strips"""
    navy, strip = "1e2e5a", "d8e0e8"
    N = C(navy)
    if v == 1:
        # a light blue shirt, a navy tie, a duty belt, a reflective band on the cap
        s, sk = Skin(), "e0a882"
        head(s, sk, "1a1a1a", "short", mouth="8a4a40", extra=[police_cap(navy, strip, "0e0e12")])
        S = C("a8c4e8")

        def b(fc, x, y, w, h):
            if fc == "front":
                if 3 <= x <= 4 and 1 <= y <= 6:
                    return N if y > 1 else shade(N, 0.8)
                if y == 3 and x in (1, 2, 5, 6):
                    return shade(S, 0.84)                  # pocket flaps
            if y == 9:
                return C("c8ccd0") if fc == "front" and x in (3, 4) else C("1a1a1e")   # the duty belt
            if fc in SIDES and y in (7, 8) and fwd(fc, x) == 1:
                return C("1a1a1e")                         # a pouch on the belt
            if y >= 10:
                return N
            if fc in SIDES and y == 0:
                return C(strip)
            return S
        body(s, b)
        arms(s, "a8c4e8", sk, sleeve=4)
        for a in ("rarm", "larm"):
            s.paint(a, lambda fc, x, y, w, h: C(strip) if y == 2 and fc not in ("top", "bottom") else None)   # reflective arm bands
    elif v == 2:
        # a navy jacket with reflective bands, a bun under the cap
        s, sk = Skin(slim=True), "f0c6a4"
        head(s, sk, "1e1614", "slick", mouth="b85a50", extra=[bun("1e1614", "1e1614"), police_cap(navy, strip, "0e0e12")])

        def b(fc, x, y, w, h):
            if fc == "front":
                if x == 4 and y >= 1:
                    return shade(N, 0.7)
                if y == 0 and 2 <= x <= 5:
                    return C("a8c4e8") if x in (3, 4) else shade(N, 0.8)
            if y in (6, 7) and fc != "bottom":
                return C(strip) if y == 6 else C("c8f03a")  # the reflective band
            if y == 9 and fc != "bottom":
                return C("1a1a1e")
            return N
        body(s, b)
        arms(s, navy, sk, sleeve=10, cuff=strip)
    else:
        # a lime high-vis vest with silver strips over the navy jacket, white gloves (traffic duty)
        s, sk = Skin(), "c88c62"
        head(s, sk, "1a1a1a", "short", mouth="7a3e36", extra=[police_cap(navy, strip, "0e0e12")])
        body(s, lambda fc, x, y, w, h: C("a8c4e8") if fc == "front" and y <= 1 and x in (3, 4) else N)
        hivis(s, "c8f03a", strip)
        arms(s, navy, sk, sleeve=10, cuff=strip)
        gloves(s, "fafafa")
    legs(s, navy, "0e0e12", shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: C(strip) if fc in SIDES and x == 1 and 1 <= y <= 9 else None)   # reflective piping
    return s


def magician(v):
    """마술사: a top hat, a black cape with a red lining, white gloves, a waxed mustache"""
    if v == 1:
        # a red waistcoat, a wand
        s, sk = Skin(), "f0c4a0"
        head(s, sk, "16161a", "slick", brows="16161a", extra=[curl_mustache("16161a"), top_hat("15151a", "c8202a")])
        wc = "c8202a"
    elif v == 2:
        # silver hair and mustache, a purple waistcoat, a fan of playing cards
        s, sk = Skin(), "e2ab84"
        head(s, sk, "c8c4bc", "slick", brows="d8d4cc", extra=[curl_mustache("e8e4dc"), top_hat("15151a", "6a3a9a")])
        wc = "6a3a9a"
    else:
        # a goatee, a gold waistcoat, a monocle, a wand and a rose
        s, sk = Skin(), "c88c62"
        head(s, sk, "2a1e16", "slick", brows="2a1e16", pupils="side",
             extra=[curl_mustache("2a1e16"), top_hat("15151a", "e8c25a"),
                    lambda s: s.paint("head", lambda fc, x, y, w, h: C("e8c25a") if fc == "front" and ((y in (3, 5) and x in (5, 6)) or (y == 4 and x in (4, 7))) else None, 1),
                    lambda s: s.paint("head", lambda fc, x, y, w, h: C("2a1e16") if fc == "front" and (x, y) in ((3, 7), (4, 7)) else None)])
        wc = "c8a030"
    bow = bow_tie("15151a")

    def b(fc, x, y, w, h):
        c = bow(fc, x, y)
        if c:
            return c
        if fc == "front" and 2 <= x <= 5 and 3 <= y <= 9:
            return C(wc) if not (x in (3, 4) and y % 2 == 0) else C("e8c25a")   # the waistcoat and its buttons
        if fc == "front" and y >= 10:
            return C("15151a")
        return C("fafafa")
    body(s, b)
    jacket(s, "15151a", open_rows=10, open_cols=(2, 5), lapel="2c2c34")
    arms(s, "fafafa", sk, sleeve=9, cuff="fafafa", layer_cloth="15151a")
    cape(s, "121216", "c8202a")
    gloves(s, "fafafa")
    legs(s, "15151a", "050505", shoe_rows=2)
    return s


def wrestler(v):
    """씨름 선수: bare arms and chest, a big build, white shorts under the 샅바 (red or blue), a headband, bare feet"""
    if v == 1:
        s, sk = Skin(), "d8a078"
        head(s, sk, "1a1a1a", "short", mouth="7a3e36", extra=[angry_brows("1a1a1a"), tie_band("d8303a")])
        sat = "d8303a"
    elif v == 2:
        s, sk = Skin(), "c48a62"
        head(s, sk, "1a1a1a", "buzz", mouth="6a3430", extra=[smile("6a3430"), tie_band("2a5ad8")])
        sat = "2a5ad8"
    else:
        s, sk = Skin(), "b9784e"
        head(s, sk, "1a1a1a", "short", brows="1a1a1a", mouth="6a3430", extra=[mustache("1a1a1a"), tie_band("d8303a")])
        sat = "d8303a"
    S = C(sk)

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 3 and 1 <= x <= 6 and x not in (3, 4):
                return shade(S, 0.9)                       # the chest
            if x in (3, 4) and 2 <= y <= 3:
                return shade(S, 0.92)
            if 5 <= y <= 8 and 2 <= x <= 5:
                return shade(S, 1.05) if y < 8 else shade(S, 0.94)   # the belly
            if (x, y) == (4, 7):
                return shade(S, 0.8)
        if y >= 10:
            return C("f4f4f0")                             # shorts
        return S
    body(s, b)
    arms(s, sk, sk, sleeve=0)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: shade(S, 1.05) if fc in SIDES and 2 <= y <= 4 else None)   # big shoulders
    legs(s, "f4f4f0", sk, shoe_rows=1, skin=sk, bare=5)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(S, 0.9) if fc == "bottom" else None)
    satba(s, sat)
    return s


def tattoo(s, ink, ink2, top=0, bottom=11, arms_=("rarm", "larm")):
    """건달's sleeve tattoo on bare arms (rows top..bottom of the arm, front and sides): a coiling dragon's scales,
    two inks in a regular pattern (no letters, no symbols of real groups)"""
    I, J = C(ink), C(ink2)

    def f(fc, x, y, w, h):
        if fc in ("top", "bottom") or not top <= y <= bottom:
            return None
        k = (x + 2 * y) % 5
        if k == 0:
            return I
        if k == 2 and y % 2 == 0:
            return J
        if fc == "front" and (x + y) % 7 == 3:
            return shade(J, 1.2)                         # a scale catching the light
        return None
    for a in arms_:
        s.paint(a, f)


def thug(v):
    """건달: a slick pompadour, a gold chain, tattooed arms (scales of a dragon, no lettering), a sneer; 반말 and swagger.
    1: a black shirt open at the neck, the sleeves rolled up; 2: a white vest (러닝) and track trousers, a buzz cut,
    a gold tooth; 3: a black jacket over a red shirt, sunglasses pushed up on the hair"""
    if v == 1:
        s, sk = Skin(), "d8a07a"
        head(s, sk, "141418", "slick", brows="141418", mouth="6a3430", pupils="side",
             extra=[angry_brows("141418"), toothpick()])
        shirt, trousers, shoe = "1e1e24", "26262c", "f2f2ee"
        Sh = C(shirt)

        def b(fc, x, y, w, h):
            if fc == "front" and ((y <= 1 and 2 <= x <= 5) or (y == 2 and x in (3, 4))):
                return C(sk)                               # open down to the chest
            if fc == "front" and x == 4 and y >= 3:
                return shade(Sh, 1.25) if y % 3 == 0 else Sh   # the buttons
            if y == 11:
                return C("e8c25a") if fc == "front" and x in (3, 4) else C("101014")   # a gold buckle
            return Sh
        body(s, b)
        arms(s, shirt, sk, sleeve=3, cuff="2e2e36")       # sleeves rolled up above the elbow
        tattoo(s, "2a4a6a", "3a7a6a", top=4, bottom=10)
        gold_chain()(s)
        s.paint("larm", lambda fc, x, y, w, h: C("e8c25a") if y == 10 and fc not in ("top", "bottom") else None)   # a gold watch
        legs(s, trousers, shoe, shoe_rows=1)
    elif v == 2:
        s, sk = Skin(), "c48a62"
        head(s, sk, "1a1a1a", "buzz", mouth="5a2a24",
             extra=[angry_brows("1a1a1a"), grin("5a2a24", teeth="f2eee6")])
        s.paint("head", lambda fc, x, y, w, h: C("e8c25a") if fc == "front" and (x, y) == (4, 6) else None)   # a gold tooth
        s.paint("head", lambda fc, x, y, w, h: shade(C(sk), 0.8) if fc == "front" and (x, y) in ((6, 2), (6, 3)) else None)   # a scar through the brow
        vest, pants = "f2f0ea", "1e2a4a"

        def b(fc, x, y, w, h):
            if fc in ("front", "back") and y <= 2 and 2 <= x <= 5:
                return C(sk) if fc == "front" or y == 0 else C(vest)   # the vest's deep neck
            if fc in SIDES and y <= 1:
                return C(sk)                               # the bare shoulders
            if y >= 10:
                return C(pants)
            return C(vest)
        body(s, b)
        arms(s, sk, sk, sleeve=0)                          # bare arms
        tattoo(s, "2a3a6a", "2a6a5a", top=0, bottom=9)
        gold_chain()(s)
        s.paint("rarm", lambda fc, x, y, w, h: C("e8c25a") if y == 9 and fc not in ("top", "bottom") else None)   # a gold bracelet
        legs(s, pants, "f4f4f4", shoe_rows=1)
        for lg in ("rleg", "lleg"):
            s.paint(lg, lambda fc, x, y, w, h: C("f2f0ea") if fc in SIDES and fwd(fc, x) == 1 and y < 11 else None)   # the track stripes
    else:
        s, sk = Skin(), "e2ab84"
        head(s, sk, "2a1e18", "slick", brows="2a1e18", mouth="6a3430",
             extra=[frown("6a3430"), shades_up("1a1a22", "e8c25a")])
        red, jacket_c = "b8242e", "16161a"
        J = C(jacket_c)

        def b(fc, x, y, w, h):
            if fc == "front" and y <= 1 and x in (3, 4):
                return C(sk)
            if fc == "front" and 2 <= x <= 5:
                return C(red) if y < 11 else C("101014")   # the red shirt between the lapels
            if fc == "front" and x in (1, 6) and y <= 4:
                return shade(J, 1.3)                       # the lapels
            if y == 11:
                return C("101014")
            return J
        body(s, b)
        arms(s, jacket_c, sk, sleeve=10, cuff=red)
        tattoo(s, "2a4a6a", "3a7a6a", top=11, bottom=11, arms_=("rarm",))   # the dragon's tail on the back of the hand
        gold_chain()(s)
        s.paint("larm", lambda fc, x, y, w, h: C("e8c25a") if fc == "front" and (x, y) == (1, 11) else None)   # a gold ring
        legs(s, jacket_c, "3a2418", shoe_rows=1)
    return s


TYPES = [("resident", "동네 주민", resident), ("student", "학생", student), ("grandma", "할머니", grandma),
         ("office", "회사원", office), ("tourist", "관광객", tourist), ("vip", "VIP", vip),
         ("farmer", "농부", farmer), ("hiker", "등산객", hiker), ("fisher", "낚시꾼", fisher),
         ("soldier", "군인", soldier), ("couple", "커플", couple), ("drunk", "술 취한 아저씨", drunk),
         ("courier", "택배기사", courier), ("kinder", "유치원생", kinder), ("camper", "캠핑족", camper),
         ("haenyeo", "해녀", haenyeo), ("fan", "야구팬", fan), ("nurse", "간호사", nurse),
         ("chatty", "수다쟁이 아줌마", chatty), ("motionsick", "멀미하는 아저씨", motionsick), ("mom", "애 업은 엄마", mom),
         ("dogwalker", "강아지 할아버지", dogwalker), ("shades", "선글라스 남자", shades), ("santa", "산타 할아버지", santa),
         ("ghost", "유령 손님", ghost), ("rider", "배달 라이더", rider), ("monk", "스님", monk), ("youtuber", "유튜버", youtuber),
         ("market", "시장 아줌마", market), ("uncle", "동네 아저씨", uncle), ("univ", "대학생", univ),
         ("worker", "공사장 인부", worker), ("kid", "초등학생", kid), ("chicken", "닭 든 할머니", chicken_grandma),
         ("tardy", "지각생", tardy), ("interview", "면접 청년", interview), ("yeot", "엿장수", yeot),
         ("traveler", "외국인 여행객", traveler), ("phone", "큰소리 통화남", phone_man), ("rude", "반말 아저씨", rude),
         ("dodger", "무임승차 청년", dodger), ("prankster", "벨 장난 꼬마", prankster), ("spread", "쩍벌남", spread),
         ("complainer", "민원 아줌마", complainer), ("crumbs", "과자 부스러기 학생", crumbs),
         ("gentleman", "친절한 신사", gentleman), ("shaman", "무당", shaman), ("mailman", "우체부", mailman),
         ("bungeo", "붕어빵 장수", bungeo), ("bride", "신혼부부", bride), ("police", "경찰", police),
         ("magician", "마술사", magician), ("wrestler", "씨름 선수", wrestler), ("thug", "건달", thug)]
VARIANTS = 3
# kinds with more looks than VARIANTS (a pair's partner looks: 신혼부부 4 = the 한복 groom)
LOOKS = {"bride": 4}


def looks(tid):
    """how many looks (<id>_1 .. <id>_n) a kind has"""
    return LOOKS.get(tid, VARIANTS)


# ---------------------------------------------------------------------------------------------
# NPCs drawn the same way but not bus passengers (no _1.._3 looks, no shiny): written next to the passengers, the key
# "economy:entity/bus/<id>" all the same
# ---------------------------------------------------------------------------------------------

def site_boss():
    """현장 소장 (도박장의 칩 환전 창구, docs/plans/도박장_기획.md): a yellow hard hat with a green-cross safety sticker, a
    pencil behind his ear, a thick moustache, a gold tooth in a sly grin; a lime hi-vis vest riding up over a pot belly
    (the shirt's belly bulging out under its hem on the outer layer), a walkie-talkie clipped to the vest, navy work
    clothes with the sleeves pushed up, a gold watch, a rolled blueprint in his right hand, dusty knees, safety boots"""
    s, sk = Skin(), "c8865a"
    head(s, sk, "4a4642", "short", brows="221c1a", mouth="7a3e36", pupils="in")
    s.paint("head", lambda fc, x, y, w, h: C("8a8680") if fc in SIDES and fwd(fc, x, 8) >= 4 and y in (3, 4) else None)   # grey at the temples
    s.paint("head", lambda fc, x, y, w, h: shade(C(sk), 0.88) if fc == "front" and y == 7 and 1 <= x <= 6 else None)   # a double chin
    s.paint("head", lambda fc, x, y, w, h: C("221c1a") if fc == "front" and (x, y) in ((2, 5), (5, 5)) else None)      # the moustache's ends
    mustache("221c1a")(s)
    grin("5a2a24", teeth="f2eee6")(s)
    s.paint("head", lambda fc, x, y, w, h: C("e8c25a") if fc == "front" and (x, y) == (4, 6) else None)                 # the gold tooth
    hard_hat("f2c22a")(s)

    def sticker(fc, x, y, w, h):
        # the 안전제일 sticker on the front of the hat: a green cross on white
        if fc == "front" and y <= 1 and 2 <= x <= 5:
            return C("2e9a4a") if x in (3, 4) else C("f6f4ee")
        return None
    s.paint("head", sticker, 1)

    def pencil(fc, x, y, w, h):
        # a carpenter's pencil behind his right ear (the head's own right side)
        if fc == "right" and y == 3 and 3 <= fwd(fc, x, 8) <= 6:
            return C("e8a830") if fwd(fc, x, 8) > 3 else C("3a2a22")
        return None
    s.paint("head", pencil, 1)
    shirt, navy, vest, strip = "f0ece4", "2e3a52", "c8f03a", "e8eef0"
    N, V, S = C(navy), C(vest), C(strip)

    def b(fc, x, y, w, h):
        if fc == "front" and y == 0 and x in (3, 4):
            return C(sk)
        if fc == "front" and y <= 2 and x in (3, 4):
            return C(shirt)                                # the undershirt in the open collar
        if y == 11:
            return C("e8c25a") if fc == "front" and x in (3, 4) else C("2a1e18")   # the belt under the belly
        if fc == "front" and 9 <= y <= 10 and 1 <= x <= 6:
            return shade(C(shirt), 1.04 if y == 9 else 0.94)   # the untucked shirt over the belly
        return N
    body(s, b)

    def v(fc, x, y, w, h):
        # the vest: no sleeves, two reflective bands, rides up to row 8 over the belly; the belly bulges under it
        if fc == "bottom":
            return None
        if fc == "front" and 9 <= y <= 10 and 1 <= x <= 6:
            return shade(C(shirt), 1.02 if y == 9 else 0.9)   # the belly sticking out under the vest
        if y > 8:
            return None
        if fc == "top":
            return S if x in (1, 6) else V
        if fc == "front" and ((y == 0 and 2 <= x <= 5) or (y in (1, 2) and x in (3, 4))):
            return None
        if fc == "front" and (x, y) in ((5, 2), (5, 3)):
            return C("26262c")                             # the walkie-talkie on the chest
        if fc == "front" and (x, y) == (5, 1):
            return C("5a5e66")                             # its antenna
        if fc == "front" and (x, y) == (1, 3):
            return C("f6f4ee")                             # a name tag
        if y in (4, 7):
            return S
        if fc in ("front", "back") and x in (1, 6) and y < 4:
            return S
        if fc == "front" and 5 <= y <= 8 and 2 <= x <= 5:
            return shade(V, 1.08)                          # stretched tight over the belly, catching the light
        if fc == "front" and y == 8:
            return shade(V, 0.85)
        return V
    s.paint("body", v, 1)
    arms(s, navy, sk, sleeve=7, cuff=shade_hex(navy, 1.25))
    s.paint("larm", lambda fc, x, y, w, h: C("e8c25a") if fc not in ("top", "bottom") and y == 10 else None)   # the gold watch
    s.paint("larm", lambda fc, x, y, w, h: shade(C("e8c25a"), 1.15) if fc == "front" and y == 10 and x == 1 else None)

    def plan(fc, x, y, w, h):
        # a rolled blueprint held in the right hand: pale blue paper sticking out in front and below
        if fc == "front" and 9 <= y <= 11 and x in (1, 2):
            return C("cfe0f2") if y < 11 else C("9ab8dc")
        if fc == "bottom" and x in (1, 2):
            return C("9ab8dc")
        return None
    s.paint("rarm", plan, 1)
    legs(s, "3a4256", "6a4a2a", shoe_rows=2)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(C("3a4256"), 1.3) if fc == "front" and y in (5, 6) and (x * 3 + y) % 4 == 1 else None)   # dusty knees
    boots(s, "6a4a2a", rows=2, top="8a6a4a")
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: C("9aa0a8") if fc == "front" and y == 11 and x in (1, 2) else None)   # the steel toe caps
    return s


def dealer():
    """딜러 (도박장 별관의 바카라 테이블, docs/plans/도박장_기획.md §7): a young woman casino dealer, neat and friendly, not
    sexualised: a glossy dark brown bob with side-swept bangs and a small gold hairpin, soft brows, bright eyes with a
    lash line, a little blush and a gentle smile; a crisp white shirt with a pointed collar, a red bow tie, a fitted
    black vest (a V showing the shirt, two gold buttons, its points at the hem) with a gold name badge, white sleeves
    with black arm garters and gold cufflinks, black slacks with a crease, black flats. Slim arms."""
    s, sk = Skin(slim=True), "f6d2b6"
    hair, hair_lit = "5a3022", "7e4a32"
    H, HL, SKN = C(hair), C(hair_lit), C(sk)

    def inner(fc, x, y, w, h):
        if fc == "top":
            return HL if (x + y) % 4 == 0 else H
        if fc == "bottom":
            return shade(SKN, 0.9)
        if fc == "back":
            return H if y <= 6 else shade(SKN, 0.92)
        if fc in SIDES:
            f = fwd(fc, x, 8)
            return H if (y <= 5 or (y == 6 and f >= 2)) and not (f == 0 and y >= 3) else SKN
        # front: side-swept bangs (heavier on the wearer's right = the viewer's left), hair down both sides
        if y == 0 or (y == 1 and x <= 5) or (y == 2 and x <= 2):
            return HL if (y == 1 and x in (2, 3)) else H
        if x in (0, 7) and y <= 5:
            return H
        if y == 3 and x in (1, 2, 5, 6):
            return C("2a1a1a") if x in (1, 6) or y == 3 else None    # the lash line over each eye
        if y == 4 and x in (1, 2, 5, 6):
            return C("24161c") if x in (2, 5) else C("fbf8f4")      # the eyes: a dark iris inside, a white
        if y == 5 and x in (1, 6):
            return C("f0a8a0")                                    # a little blush
        if y == 5 and x in (3, 4):
            return shade(SKN, 0.93)
        if y == 6 and x in (3, 4):
            return C("d0606a")                                    # the smile
        if y == 6 and x in (2, 5):
            return shade(SKN, 0.94)                               # the smile's corners lifted
        return SKN
    s.paint("head", inner)
    # the lash line: both eyes' upper row dark (outer and inner pixel)
    s.paint("head", lambda fc, x, y, w, h: C("2a1a1a") if fc == "front" and y == 3 and x in (1, 2, 5, 6) else None)
    s.paint("head", lambda fc, x, y, w, h: C("2a1a1a") if fc == "front" and (x, y) in ((0, 3), (7, 3)) else None)   # the lashes' flick
    s.paint("head", lambda fc, x, y, w, h: C("8a5a44") if fc == "front" and y == 2 and x in (5, 6) else None)   # a soft brow under the fringe

    def outer(fc, x, y, w, h):
        # the bob's volume: the crown, the fringe's front edge, the sides to the jaw, curving in at the ends
        if fc == "top":
            return HL if (x + 2 * y) % 5 == 0 else H
        if fc == "bottom":
            return None
        if fc == "front":
            if y == 0 or (y == 1 and x <= 4):
                return HL if (x, y) in ((1, 1), (2, 1)) else H
            if x in (0, 7) and 1 <= y <= 6:
                return shade(H, 0.85) if y == 6 else H
            if (x, y) == (6, 1):
                return C("e8c050")                                # the gold hairpin
            return None
        if fc in SIDES:
            f = fwd(fc, x, 8)
            if y <= 5 or (y == 6 and f >= 1):
                if fc == "left" and y == 1 and f in (1, 2):
                    return C("e8c050")                            # the pin seen from the side
                return HL if (y == 1 and f % 3 == 1) else H
            return None
        if fc == "back":
            if y <= 6:
                return shade(H, 0.85) if y == 6 else (HL if (x + y) % 5 == 0 else H)
            return None
        return None
    s.paint("head", outer, 1)

    shirt, vest, bow = "fafafa", "1e1e24", "c8202e"
    SH, V, B = C(shirt), C(vest), C(bow)

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and x in (2, 5):
                return shade(SH, 0.86)                            # the collar's points
            if y <= 1 and 2 <= x <= 5:
                if x in (3, 4):
                    return shade(B, 0.8) if y == 0 else B         # the bow tie's knot
                return B if y in (0, 1) else SH
            if x in (3, 4) and y in (3, 5):
                return shade(SH, 0.88)                            # the shirt's buttons
            return SH
        return SH
    body(s, b)

    def v(fc, x, y, w, h):
        # the vest on the outer layer: a V opening down to row 5, gold buttons, its points at the hem, a name badge
        if fc == "bottom":
            return None
        if fc == "top":
            return V if x in (0, 1, 6, 7) else None
        if fc == "front":
            if y <= 5 and 2 + (y >= 1) * 0 <= x <= 5 and abs(x - 3.5) <= 2.5 - y * 0.4:
                return None
            if y == 1 and x in (2, 5):
                return None
            if y == 11 and x in (3, 4):
                return None                                       # the points: the shirt shows between them
            if x in (3, 4) and y in (7, 9):
                return C("e8c050") if x == 3 else V
            if (x, y) in ((5, 4), (6, 4)):
                return C("e8c050") if x == 5 else C("f8f0d8")      # the name badge
            return shade(V, 1.18) if x in (2, 5) and y <= 7 else V
        if fc == "back":
            if y == 6:
                return shade(V, 1.3)                              # the back belt
            return shade(V, 0.95) if x in (3, 4) else V
        return V
    s.paint("body", v, 1)
    arms(s, shirt, sk, sleeve=11, cuff=shirt)
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: C("1e1e24") if fc not in ("top", "bottom") and y == 3 else None)      # the arm garter
        s.paint(a, lambda fc, x, y, w, h: shade(SH, 0.9) if fc not in ("top", "bottom") and y == 10 else None)   # the cuff
        s.paint(a, lambda fc, x, y, w, h: C("e8c050") if fc in ("left", "right") and y == 10 and x == 1 else None)   # the cufflink
    legs(s, "24242c", "141418", shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(C("24242c"), 1.25) if fc == "front" and x == 1 and y < 11 else None)   # the crease
        s.paint(lg, lambda fc, x, y, w, h: SKN if fc in ("front", "left", "right") and y == 10 else None)   # a glimpse of ankle over the flats
    return s


def hq_inspector():
    """본사 점검원 (편의점 하루 이벤트 "본사 점검", docs/plans/편의점_기획안.md §16): a neat woman from head office: dark hair
    pulled back into a low bun, a tidy side fringe, thin dark-framed glasses, a straight mouth; a navy suit (a fitted
    jacket buttoned once, its lapels, a pocket square of the brand's green), a white blouse with a small collar, a
    company ID card on a green lanyard, a clipboard of white checklists held in her left hand (a steel clip, ticks on
    the sheet), navy trousers, polished black shoes. Slim arms."""
    s, sk = Skin(slim=True), "f2d0b4"
    hair = "2a2024"
    head(s, sk, hair, "part", brows="5a4440", mouth="b8786e", pupils="in")
    H = C(hair)

    def bun(fc, x, y, w, h):
        # the low bun at the back (outer layer), the hair smoothed back over the top
        if fc == "back" and 4 <= y <= 6 and 2 <= x <= 5 and not (y == 6 and x in (2, 5)):
            return shade(H, 1.25) if (x, y) == (3, 4) else H
        if fc == "top":
            return shade(H, 1.12) if x == 3 and y % 2 == 0 else H
        return None
    s.paint("head", bun, 1)
    s.paint("head", lambda fc, x, y, w, h: H if fc in SIDES and y <= 5 and fwd(fc, x, 8) >= 3 else None)   # combed back over the ears
    # thin browline glasses: the dark upper rims over the eyes, the bridge between them, the temples
    s.paint("head", lambda fc, x, y, w, h: (C("4a3c3e") if y == 3 and x in (1, 2, 5, 6) else None)
            if fc == "front" else (C("4a3c3e") if fc in SIDES and y == 3 and fwd(fc, x, 8) <= 3 else None), 1)
    navy, blouse, green = "1f2a48", "f6f6f2", "1f8a52"
    N, B = C(navy), C(blouse)

    def b(fc, x, y, w, h):
        if fc == "front":
            if y == 0 and x in (3, 4):
                return C(sk)
            if y == 0 and x in (2, 5):
                return shade(B, 0.9)                     # the blouse's collar points
            if x in (3, 4) and y in (2, 4):
                return shade(B, 0.92)                    # buttons
            if y == 11:
                return C("16161c")
        return B
    body(s, b)

    def suit(fc, x, y, w, h):
        if fc == "bottom":
            return None
        if fc == "front":
            if y < 5 and 2 + (1 if y == 0 else 0) <= x <= 5 - (1 if y == 0 else 0) and abs(x - 3.5) <= 2.5 - y * 0.35:
                return None                                # the V of the lapels
            if y < 5 and x in (1, 6):
                return shade(N, 0.78)                      # the lapels
            if (x, y) == (3, 6):
                return C("c8c4bc")                         # the one button
            if y >= 6 and x == 4:
                return shade(N, 0.85)                      # the jacket's front edge
            if y == 9 and x in (1, 2, 5, 6):
                return shade(N, 0.8)                       # the pocket flaps
            if (x, y) == (6, 2):
                return C(green)                            # the pocket square
            return N
        if fc == "back":
            return shade(N, 0.9) if x in (3, 4) and y >= 7 else N
        return N
    s.paint("body", suit, 1)
    lanyard(green, "f4f4ee")(s)
    # the ID card's green band and a tiny photo (on the card painted by the lanyard)
    s.paint("body", lambda fc, x, y, w, h: (C(green) if (x, y) == (6, 5) else C("8a96a8") if (x, y) == (5, 6) else None) if fc == "front" else None, 1)
    arms(s, blouse, sk, sleeve=10, cuff=blouse, layer_cloth=navy)

    def board(fc, x, y, w, h):
        # the clipboard in her left hand, held against her side: a brown board, white sheets, the steel clip, ticks
        if fc == "front" and 7 <= y <= 11:
            if y == 7:
                return C("a8aeb6") if x == 1 else C("8a6a44")
            if x == 0:
                return C("8a6a44")
            if (x, y) in ((1, 9), (2, 10)):
                return C("1f8a52")                         # the ticks
            return C("f8f8f4") if y % 2 else C("dcdcd6")
        if fc == "left" and 7 <= y <= 11:
            return C("8a6a44") if x in (0, 3) else C("f2f2ee")
        if fc == "bottom":
            return C("8a6a44")
        return None
    s.paint("larm", board, 1)
    legs(s, navy, "141418", shoe_rows=1)
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(N, 1.2) if fc == "front" and x == 1 and y < 11 else None)   # the crease
        s.paint(lg, lambda fc, x, y, w, h: C("3a3a44") if fc == "front" and y == 11 and x == 1 else None)     # the shine on the shoe
    return s


def night_regular():
    """숨은 비밀 손님 (관리자 문서). A quiet late-night regular, a little odd but never scary: a plain mid-grey hoodie
    with the hood up over messy black hair that falls over the forehead, pale skin with a cool tint, tired half-lidded
    eyes with grey shadows under them looking straight ahead, a small flat mouth; grey sweatpants, white socks and
    dark slides. No marks or prints. Wide arms."""
    s, sk = Skin(), "e6dcd4"
    hair, grey = "1a1a20", "8a8c92"
    head(s, sk, hair, "messy", eye="20222c", mouth="b09a98", pupils="in", extra=[tired(), lids(shade_hex(sk, 0.86))])
    s.paint("head", lambda fc, x, y, w, h: C(hair) if fc == "front" and (y == 1 or (y == 2 and x in (0, 7))) else None)   # the fringe low, strands at the sides
    s.paint("head", lambda fc, x, y, w, h: C("cbbfc0") if fc == "front" and y == 5 and x in (1, 2, 5, 6) else None)  # faint shadows under the eyes
    s.paint("head", lambda fc, x, y, w, h: C("dcd6d2") if fc == "front" and y == 4 and x in (1, 6) else None)       # the whites dimmed: half-lidded
    s.paint("head", lambda fc, x, y, w, h: shade(C(sk), 0.95) if fc == "front" and y == 6 and x in (2, 5) else None)
    G_ = C(grey)

    def hood_up(fc, x, y, w, h):
        # the hood up: over the top, the back and the sides, framing the face (rows 0-1 and the edge columns), its
        # rim a shade lighter where it catches the shop's light
        if fc == "bottom":
            return None
        if fc == "front":
            if y == 0:
                return shade(G_, 1.1)
            if x in (0, 7) and y <= 6:
                return shade(G_, 1.05) if y < 6 else shade(G_, 0.9)
            return None
        if fc == "top":
            return shade(G_, 1.08) if (x + y) % 5 == 0 else G_
        if fc == "back" and y >= 6 and x in (3, 4):
            return shade(G_, 0.85)                         # the seam
        return G_
    s.paint("head", hood_up, 1)
    body(s, hoodie_body(grey, "c8c8cc", pocket=True))
    s.paint("body", lambda fc, x, y, w, h: shade(G_, 0.8) if fc == "front" and y == 0 and 2 <= x <= 5 else None)   # the hood's shadow at the neck
    arms(s, grey, sk, sleeve=11, cuff=shade_hex(grey, 0.85))
    for a in ("rarm", "larm"):
        s.paint(a, lambda fc, x, y, w, h: shade(G_, 0.9) if fc in ("front", "back") and y in (4, 8) and x in (1, 2) else None)   # creases
    legs(s, "4e5058", "26262c", shoe_rows=1, sock="eeeeea")
    for lg in ("rleg", "lleg"):
        s.paint(lg, lambda fc, x, y, w, h: shade(C("4e5058"), 0.85) if fc not in ("top", "bottom") and y == 8 else None)   # the cuffed hem
    return s


# 편의점 (tools/assetgen/store_assets.py writes only these two): 본사 점검원, and night_regular: 숨은 비밀 손님 (관리자 문서)
NPCS = {"site_boss": site_boss, "dealer": dealer, "hq_inspector": hq_inspector, "night_regular": night_regular}
STORE_NPCS = ("hq_inspector", "night_regular")


def build_npcs():
    """id -> (skin image, slim) of the NPCs above"""
    out = {}
    for nid, fn in NPCS.items():
        s = fn()
        out[nid] = (s.finish(), s.slim)
    return out


def build():
    """id -> (skin image, slim)"""
    out = {}
    for tid, _, fn in TYPES:
        for v in range(1, looks(tid) + 1):
            s = fn(v)
            out[f"{tid}_{v}"] = (s.finish(), s.slim)
    return out


# ---------------------------------------------------------------------------------------------
# 별난 손님 (shiny passengers, README "마을버스" 별난 손님): one <id>_s1 per kind, phase 1 = the kind's first look with
# its clothes and hair in another palette (gold, or a pastel) plus a small accessory of its own (a star or heart pin, a
# flower, a tiny crown, a ribbon). The skin itself (sampled from the face) keeps its colour; the 유령 손님's shiny is a
# perfectly healthy person (warm skin: the plugin shows it un-see-through). The secret ??? (shades) has none.
# ---------------------------------------------------------------------------------------------

SHINY_SKIP = {"shades"}
# the pair kinds (bus.yml pair: true): their other half's shiny <id>_s2 is this look recoloured the same way (theme and
# pin of _s1), so a shiny pair is two people, not twins: a man and a woman (커플, 신혼부부), a boy and a girl (유치원생)
SHINY_PARTNER = {"couple": 2, "kinder": 2, "bride": 2}
# theme -> how a colour changes: ("gold", ...) a gold ramp by lightness; ("pastel", hue shift, tint) a hue turn, softened
SHINY_THEMES = [
    ("gold", None, None),
    ("pastel", 0.55, "ffb6d0"),     # 벚꽃
    ("pastel", 0.33, "aef0d0"),     # 민트
    ("pastel", 0.75, "d0b8ff"),     # 라벤더
    ("pastel", 0.15, "a8dcff"),     # 하늘
    ("pastel", 0.90, "e8f590"),     # 연두
]
SHINY_PINS = ["star", "heart", "flower", "crown", "ribbon"]
GOLD_RAMP = [(0.0, (70, 44, 8)), (0.35, (168, 112, 20)), (0.65, (232, 184, 52)), (0.85, (250, 224, 120)), (1.0, (255, 250, 214))]


def _hsv(c):
    import colorsys
    return colorsys.rgb_to_hsv(c[0] / 255, c[1] / 255, c[2] / 255)


def _rgb(h, s, v, a=255):
    import colorsys
    r, g, b = colorsys.hsv_to_rgb(h % 1.0, max(0.0, min(1.0, s)), max(0.0, min(1.0, v)))
    return (round(r * 255), round(g * 255), round(b * 255), a)


def _ramp(stops, t):
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            k = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            return tuple(round(c0[i] + (c1[i] - c0[i]) * k) for i in range(3))
    return stops[-1][1]


def shiny_colour(c, theme):
    """A clothes or hair colour in the theme's palette (alpha kept)."""
    kind, shift, tint = theme
    if kind == "gold":
        lum = (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255
        return _ramp(GOLD_RAMP, 0.15 + 0.8 * lum) + (c[3],)
    h, s, v = _hsv(c)
    t = C(tint)
    th = _hsv(t)[0]
    # a grey (suits, black hair) takes the theme's own hue; a colour turns by `shift`; all of it soft and light
    hue = th if s < 0.15 else h + shift
    out = _rgb(hue, max(s * 0.7, 0.38), 0.4 + 0.58 * v)
    return tuple(round(out[i] * 0.75 + t[i] * 0.25) for i in range(3)) + (c[3],)


def face_skin(im):
    """The skin colour: the commonest colour of the inner head's front below the eyes (rows 5-7)."""
    from collections import Counter
    seen = Counter(im.getpixel((8 + x, 8 + y)) for x in range(8) for y in range(5, 8))
    return seen.most_common(1)[0][0]


def _near(a, b, tol=22):
    return a[3] and b[3] and sum(abs(a[i] - b[i]) for i in range(3)) <= tol


def shiny_pin(s, kind, col, light):
    """The small accessory on the head's outer layer (front, the wearer's left of the top) or on top."""
    Co, Li = C(col), C(light)
    if kind == "star":
        px = {(6, 0): Co, (5, 1): Co, (6, 1): Li, (7, 1): Co, (6, 2): Co}
    elif kind == "heart":
        px = {(5, 0): Co, (7, 0): Co, (5, 1): Co, (6, 1): Li, (7, 1): Co, (6, 2): Co}
    elif kind == "flower":
        px = {(6, 0): Co, (5, 1): Co, (6, 1): Li, (7, 1): Co, (6, 2): Co, (5, 2): C("5aa04a")}
    elif kind == "ribbon":
        px = {(4, 0): Co, (6, 0): Co, (4, 1): Co, (5, 1): Li, (6, 1): Co, (5, 0): Co}
    else:   # a tiny crown on the hair (front edge and top)
        px = {(2, 0): Co, (3, 0): Li, (4, 0): Li, (5, 0): Co, (2, 1): Co, (5, 1): Co}
        s.paint("head", lambda fc, x, y, w, h: (Li if (x + y) % 3 == 0 else Co) if fc == "top" and 2 <= x <= 5 and 2 <= y <= 5 else None, 1)
    s.paint("head", lambda fc, x, y, w, h: px.get((x, y)) if fc == "front" else None, 1)


def shiny(tid, fn, i, look=1):
    """The kind's 별난 look (see above): its first skin (or `look`: a pair's other half), recoloured, with its pin and
    two glints on the chest."""
    s = fn(look)
    theme = SHINY_THEMES[i % len(SHINY_THEMES)]
    pin = SHINY_PINS[i % len(SHINY_PINS)]
    im = s.im
    skin = face_skin(im)
    warm = tid == "ghost"   # the ghost's shiny: a healthy, living face
    for y in range(64):
        for x in range(64):
            c = im.getpixel((x, y))
            if c[3] == 0:
                continue
            if _near(c, skin):
                if warm:
                    k = (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / max(1, 0.299 * skin[0] + 0.587 * skin[1] + 0.114 * skin[2])
                    im.putpixel((x, y), shade(C("f0c4a0"), k))
                continue
            if 9 <= x < 15 and 11 <= y < 16:
                continue   # the face itself (eyes, nose, mouth, cheeks) as it was
            im.putpixel((x, y), shiny_colour(c, theme))
    if theme[0] == "gold":
        shiny_pin(s, pin, "ff6aa8", "ffd0e4")
    else:
        shiny_pin(s, pin, "f2c23a", "fff4b8")
    glint = C("fffbe8")
    s.paint("body", lambda fc, x, y, w, h: glint if fc == "front" and (x, y) in ((1, 2), (6, 6)) else None, 1)
    return s


def silhouette():
    """인물 도감's unmet person (its head in the codex): a plain dark figure, a pale "?" on the face."""
    s = Skin()
    dark, edge, mark = C("2a2c38"), C("3a3d4c"), C("c8cce0")
    q = {(2, 1), (3, 1), (4, 1), (5, 1), (1, 2), (6, 2), (5, 3), (4, 4), (3, 5), (3, 7)}
    for part in ("head", "body", "rarm", "larm", "rleg", "lleg"):
        s.paint(part, lambda fc, x, y, w, h: edge if fc == "top" else dark)
    s.paint("head", lambda fc, x, y, w, h: mark if fc == "front" and (x, y) in q else None)
    return s


SILHOUETTE = OUT.parent / "people" / "unknown.png"


def build_shiny():
    """<id>_s1 -> (skin image, slim) for every kind but SHINY_SKIP, and <id>_s2 for the pair kinds (SHINY_PARTNER)."""
    out = {}
    for i, (tid, _, fn) in enumerate(TYPES):
        if tid in SHINY_SKIP:
            continue
        s = shiny(tid, fn, i)
        out[f"{tid}_s1"] = (s.finish(), s.slim)
        if tid in SHINY_PARTNER:
            s = shiny(tid, fn, i, SHINY_PARTNER[tid])
            out[f"{tid}_s2"] = (s.finish(), s.slim)
    return out


# ---------------------------------------------------------------------------------------------
# preview: front and back as a flat 2D player
# ---------------------------------------------------------------------------------------------

def flat(im, slim, back=False):
    """16 x 32 front (or back) view, outer layer composited over the inner one"""
    P = parts(slim)
    a = 3 if slim else 4
    out = Image.new("RGBA", (16, 32), (0, 0, 0, 0))
    left_arm_x = 4 - a
    place = {"head": (4, 0), "body": (4, 8), "rarm": (left_arm_x, 8), "larm": (12, 8), "rleg": (4, 20), "lleg": (8, 20)}
    if back:
        place.update({"rarm": (12, 8), "larm": (left_arm_x, 8), "rleg": (8, 20), "lleg": (4, 20)})
    for part, ((u, v, w, h, d), (ou, ov)) in P.items():
        face = "back" if back else "front"
        for (bu, bv) in ((u, v), (ou, ov)):
            x0, y0, fw, fh = rects(bu, bv, w, h, d)[face]
            out.alpha_composite(im.crop((x0, y0, x0 + fw, y0 + fh)), place[part])
    return out


def sheet(skins, path, types=None):
    from PIL import ImageDraw, ImageFont
    font = ImageFont.truetype(str(HERE / "fonts" / "Sunflower-Medium.ttf"), 18)
    small = ImageFont.truetype(str(HERE / "fonts" / "Sunflower-Medium.ttf"), 14)
    cw, ch = 64 * 2 + 16 + 40 + 24, 128 + 52
    types = [t for t in TYPES if types is None or t[0] in types]
    W, H = 150 + cw * max(looks(t[0]) for t in types), ch * len(types) + 10
    img = Image.new("RGBA", (W, H), (58, 62, 70, 255))
    d = ImageDraw.Draw(img)
    for r, (tid, name, _) in enumerate(types):
        y = 10 + r * ch
        d.text((12, y + 60), name, font=font, fill="white")
        for v in range(1, looks(tid) + 1):
            key = f"{tid}_{v}"
            im, slim = skins[key]
            x = 150 + (v - 1) * cw
            d.rectangle((x - 6, y - 4, x + cw - 14, y + ch - 10), fill=(72, 77, 86, 255))
            d.text((x, y), f"{key}  {'slim' if slim else 'wide'}", font=small, fill=(220, 220, 220))
            fr, bk = flat(im, slim), flat(im, slim, back=True)
            img.alpha_composite(fr.resize((64, 128), Image.NEAREST), (x, y + 20))
            img.alpha_composite(bk.resize((64, 128), Image.NEAREST), (x + 72, y + 20))
            img.alpha_composite(fr, (x + 146, y + 60))              # about how big it is a few blocks away
            img.alpha_composite(fr.resize((8, 16), Image.BOX), (x + 150, y + 100))
    img.save(path)


def shiny_sheet(skins, shinies, path, cols=6):
    """별난 손님 preview: each kind's first look beside its shiny one (a pair's _s2: the partner look beside it)."""
    from PIL import ImageDraw, ImageFont
    small = ImageFont.truetype(str(HERE / "fonts" / "Sunflower-Medium.ttf"), 14)
    names = {tid: name for tid, name, _ in TYPES}
    keys = list(shinies)
    cw, ch = 4 * 68 + 20, 128 + 40
    rows = (len(keys) + cols - 1) // cols
    img = Image.new("RGBA", (cw * cols + 10, ch * rows + 10), (58, 62, 70, 255))
    d = ImageDraw.Draw(img)
    for k, key in enumerate(keys):
        tid = key[:-3]
        base = f"{tid}_{SHINY_PARTNER[tid]}" if key.endswith("_s2") else f"{tid}_1"
        x, y = 10 + (k % cols) * cw, 10 + (k // cols) * ch
        d.rectangle((x - 4, y - 4, x + cw - 14, y + ch - 10), fill=(72, 77, 86, 255))
        d.text((x, y), f"{names[tid]} {key}", font=small, fill=(255, 230, 150))
        for j, (im, slim) in enumerate((skins[base], shinies[key])):
            img.alpha_composite(flat(im, slim).resize((64, 128), Image.NEAREST), (x + j * 136, y + 20))
            img.alpha_composite(flat(im, slim, back=True).resize((64, 128), Image.NEAREST), (x + j * 136 + 66, y + 20))
    img.save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", help="also write a preview sheet here")
    ap.add_argument("--types", help="only these types on the sheet (comma separated ids)")
    ap.add_argument("--shiny-sheet", help="also write the 별난 손님 preview sheet here")
    args = ap.parse_args()
    skins = build()
    shinies = build_shiny()
    OUT.mkdir(parents=True, exist_ok=True)
    for key, (im, slim) in {**skins, **shinies, **build_npcs()}.items():
        im.save(OUT / f"{key}.png")
        print(f"economy:entity/bus/{key}  {'slim' if slim else 'wide'}")
    SILHOUETTE.parent.mkdir(parents=True, exist_ok=True)
    silhouette().finish().save(SILHOUETTE)
    print("economy:entity/people/unknown  wide")
    if args.sheet:
        sheet(skins, args.sheet, args.types.split(",") if args.types else None)
    if args.shiny_sheet:
        shiny_sheet(skins, shinies, args.shiny_sheet)


if __name__ == "__main__":
    main()
