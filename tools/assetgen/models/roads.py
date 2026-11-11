"""
국도 direction sign (economy: /관리자 국도 puts one a little way from the start, every km and before the dock's gate;
plugin dev.stsv.economy.road.RoadSign / RoadSigns). An original design in the feel of a Korean highway guide sign: two grey
steel posts on concrete footings, a green panel 3 wide and 1.5 tall with a white border, a white arrow and a blue route
shield drawn on it, a steel backing frame and two rails clamping it to the posts. The words (dock, km, route, the shield's
number) are text displays on the face, placed by RoadSign; this model leaves their places plain green.

    drawn at real size (16 px a block): the point between the posts at x = z = 8 on the road's surface (y = 0), front
    (the face) = north (-z); the viewer's right is -x (u = 32 - x across the panel, RoadSign's panel pixels);
    BModel(scale=2, shift=(0, -16, 0)) shrinks it by 2 so it fits -16..32 (it is 4.5 blocks tall);
    the plugin's display scale 2 brings it back (and lifts it 1.5 so y 0 is on the road): a texel stays 1/16 block.

The panel's numbers are RoadSign's: its bottom 3 blocks over the road (y 48), 48 x 24 px, its face 3 px in front of the
point (z 5), the shield's 3.5 (z 4.5), the posts 0.75 either side (x 8 -+ 12).
"""

from stsv_assets import presets
from stsv_assets.bake import BModel, Paint

# RoadSign (economy/src/main/java/dev/stsv/economy/road/RoadSign.java), in drawn px
BOTTOM, PW, PH = 48, 48, 24
FACE_Z, SHIELD_Z = 5, 4.5
POSTS = (-4, 20)                   # post centres (x): right and left of the driver
SHIELD = (3, 3, 10, 10)           # u0, v0, u1, v1 on the panel
ARROW = (3, 11, 11, 22)            # u0, v0, u1, v1: the box the arrow is drawn in


def _uv(p):
    """A drawn point -> panel (u, v) as the driver sees it."""
    return 32 - p[0], p[1] - BOTTOM


WHITE = ("car_white", 3)
GREEN = ("sign_green", 2)


def _arrow(u, v):
    """The upward arrow in its box: a head 8 wide and 4 high, a shaft 2 wide."""
    u0, v0, u1, v1 = ARROW
    if not (u0 <= u < u1 and v0 <= v < v1):
        return False
    x, y = u - u0, v1 - 1 - v           # x 0..7 left to right, y 0.. down from the tip
    mid = (u1 - u0) / 2 - 0.5           # 3.5
    if y < 4:
        return abs(x - mid) <= y + 0.5
    return abs(x - mid) <= 1


def face(c):
    """The panel's face: green, a white border 1 px in (corners rounded), the arrow."""
    if c.face != "north":
        return None
    u, v = _uv(c.p)
    u, v = int(u), int(v)
    # the border: the ring of texels one in from the edge, its corners rounded
    inner = 1 <= u <= PW - 2 and 1 <= v <= PH - 2
    edge = u in (1, PW - 2) or v in (1, PH - 2)
    corner = u in (1, PW - 2) and v in (1, PH - 2)
    if inner and edge and not corner:
        return WHITE
    if (u, v) in ((2, 2), (PW - 3, 2), (2, PH - 3), (PW - 3, PH - 3)):
        return WHITE
    if _arrow(u, v):
        return WHITE
    return GREEN


def shield(c):
    """The route shield: a flat top, straight sides, a bottom that narrows to a point; blue with a white rim (the number
    is a text display)."""
    if c.face != "north":
        return ("car_white", 2)
    u, v = _uv(c.p)
    u0, v0, u1, v1 = SHIELD
    x, y = int(u - u0), int(v1 - v)      # x 0..6 left to right, y 0..6 down
    w, h = int(u1 - u0), int(v1 - v0)
    cut = max(0, y - (h - 3))            # the bottom rows narrow by one a side
    if x < cut or x > w - 1 - cut:
        return GREEN
    rim = y == 0 or y == h - 1 or x == cut or x == w - 1 - cut
    return WHITE if rim else ("police_blue", 2)


def road_sign():
    m = BModel("road_sign", floor=0, shift=(0, -16, 0), scale=2)
    m.display = presets.furniture(gui_scale=0.18)
    steel = Paint("steel", 3, "grain", axis="y", noise=0.03)
    steel_x = Paint("steel", 2, "grain", axis="x", noise=0.03)
    back = Paint("steel", 2, noise=0.02)
    m.group("panel")
    x0, x1, y0, y1 = 32 - PW, 32, BOTTOM, BOTTOM + PH
    m.box("plate", (x0, y0, FACE_Z), (x1, y1, FACE_Z + 1), back, faces={"north": Paint("sign_green", 2, noise=0, decal=face, rim=0.3, ao=0.4)})
    m.box("shield", (32 - SHIELD[2], BOTTOM + SHIELD[1], SHIELD_Z), (32 - SHIELD[0], BOTTOM + SHIELD[3], FACE_Z),
          Paint("police_blue", 2, noise=0, decal=shield, rim=0.2, ao=0.3))
    m.group("frame")
    m.box("backing", (x0 + 1, y0 + 1, FACE_Z + 1), (x1 - 1, y1 - 1, FACE_Z + 2), back)
    for y in (y0 + 4, y1 - 6):
        m.box("rail", (x0 + 2, y, FACE_Z + 2), (x1 - 2, y + 2, FACE_Z + 3.5), steel_x)
    m.group("posts")
    for cx in POSTS:
        m.box("post", (cx - 1.5, 1, FACE_Z + 3.5), (cx + 1.5, y1 - 1, FACE_Z + 6.5), steel)
        m.box("cap", (cx - 2, y1 - 1, FACE_Z + 3), (cx + 2, y1, FACE_Z + 7), back)
        m.box("footing", (cx - 3, -4, FACE_Z + 2), (cx + 3, 1, FACE_Z + 8), Paint("stone", 2, "stone"))
    return m


ALL = {"road_sign": road_sign}
