"""
Village bus passenger skins (64x64 player skins, the modern layout with both layers) for the economy
server's bus: the passengers are Mannequins whose profile carries a skin patch pointing at these
textures, so nothing is uploaded to Mojang.

    python3 economy_bus_skins.py                    # skins -> economy/content/economy/assets/economy/textures/entity/bus/
    python3 economy_bus_skins.py --sheet out.png    # + a preview sheet (front and back, flat, 4x and 1x)
    python3 economy_bus_skins.py --sheet out.png --types farmer,drunk   # the sheet with only these types

A texture at assets/economy/textures/entity/bus/<id>.png is the key "economy:entity/bus/<id>"
(ResolvableProfile.skinPatch().body(key), with .model(SLIM) for the slim ones: see SLIM / main()).

Twelve passenger types, three of each: 동네 주민 resident, 학생 student, 할머니 grandma, 회사원 office,
관광객 tourist, VIP, and the second batch 농부 farmer, 등산객 hiker, 낚시꾼 fisher, 군인 soldier (on leave, no
insignia), 커플 couple, 술 취한 아저씨 drunk. Colours are flat per area; the depth comes from the outer layer (hair volume, hats,
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


TYPES = [("resident", "동네 주민", resident), ("student", "학생", student), ("grandma", "할머니", grandma),
         ("office", "회사원", office), ("tourist", "관광객", tourist), ("vip", "VIP", vip),
         ("farmer", "농부", farmer), ("hiker", "등산객", hiker), ("fisher", "낚시꾼", fisher),
         ("soldier", "군인", soldier), ("couple", "커플", couple), ("drunk", "술 취한 아저씨", drunk)]
VARIANTS = 3


def build():
    """id -> (skin image, slim)"""
    out = {}
    for tid, _, fn in TYPES:
        for v in range(1, VARIANTS + 1):
            s = fn(v)
            out[f"{tid}_{v}"] = (s.finish(), s.slim)
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
    W, H = 150 + cw * VARIANTS, ch * len(types) + 10
    img = Image.new("RGBA", (W, H), (58, 62, 70, 255))
    d = ImageDraw.Draw(img)
    for r, (tid, name, _) in enumerate(types):
        y = 10 + r * ch
        d.text((12, y + 60), name, font=font, fill="white")
        for v in range(1, VARIANTS + 1):
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", help="also write a preview sheet here")
    ap.add_argument("--types", help="only these types on the sheet (comma separated ids)")
    args = ap.parse_args()
    skins = build()
    OUT.mkdir(parents=True, exist_ok=True)
    for key, (im, slim) in skins.items():
        im.save(OUT / f"{key}.png")
        print(f"economy:entity/bus/{key}  {'slim' if slim else 'wide'}")
    if args.sheet:
        sheet(skins, args.sheet, args.types.split(",") if args.types else None)


if __name__ == "__main__":
    main()
