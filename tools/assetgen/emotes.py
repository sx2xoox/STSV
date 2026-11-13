"""
Emotes (감정표현, admin test feature): a jointed puppet made of text displays that wears the player's skin.

    python3 -I emotes.py             # the pack files (plugin/src/main/resources/pack/emotes; also run by build.py)
    python3 -I emotes.py --preview   # + docs/previews/emote_<id>.png for every emote of content/stsv/emotes.yml
    python3 -I emotes.py --fixture   # + plugin/src/test/fixtures/emote/rig.json (numbers the plugin's rig test checks)
    python3 -I emotes.py --check     # fails when the fixture is not what this file and emotes.yml give

The puppet (plugin: dev.stsv.core.emote). The game cannot pose a player's joints from the server, so the plugin
puts the player aside (a spectator on a camera) and stands a puppet in their place: every face of every body-part
box is one text display whose text is that face's skin pixels, one glyph per pixel, coloured per character. The
previews are drawn from those displays' transformations by the client's rules, not from the boxes, so a mistake in
the transformations shows. What the 26.3 client does with a text display (read from its code,
DisplayRenderer$TextDisplayRenderer.submitInner, and replayed here by text_rect):

  * the pose is the entity's rotation (none: yaw and pitch 0), then the display's transformation (translation,
    left rotation, scale), then a turn of pi about Y and a scale of -0.025 on every axis, then a translation of
    (1 - width / 2, -(lines * 10 - 1), 0) in text pixels. So a text pixel is 0.025 block, text x runs along local
    +x, text y (down) along local -y, and n lines of width w cover local x in [(1 - w/2), (1 + w/2)] * 0.025 and
    local y in [-0.025, (10 n - 1) * 0.025]: the block is one pixel right of the origin and one pixel under it;
  * lines are 10 text pixels apart whatever the font; a glyph's quad is its bitmap, drawn at the pen position
    (TrueType: the advance is exact, no extra pixel), its corners in the order top-left, bottom-left, bottom-right,
    top-right: counter-clockwise seen from local +z, and the world text pipelines cull back faces and write depth.
    A text display is a one-sided, depth-tested sheet facing local +z: six of them facing outward are a solid box.

So the pixel glyph (PIXEL) is a TrueType square of exactly its advance, 10 x 10 text pixels on rows 0..9 of a line
(pixel_ttf rows 1..10): pixels tile without a gap or an overlap in both directions, because neighbouring quads share
their corner coordinates exactly (the pen moves by whole pixels and every quad goes through the same matrix). A
larger square would overlap its neighbour by a tenth of a skin pixel and move every colour edge, so it is exact. A
transparent skin pixel is GAP, a private-use space of the same advance (a real space would be a place to wrap).
Between the faces of a box, each with its own matrix, the corners only agree to rounding: every face is drawn SEAL
times larger about its centre so the faces overlap by a hair instead of leaving pinholes on the edges.

The rig (same numbers in the plugin's EmoteRig, checked by the fixture): units are skin pixels, origin on the ground
between the feet, +x the puppet's left, +y up, +z forward. A joint turns about its pivot by Rz(roll) Ry(yaw) Rx(pitch)
(degrees). With `legs: planted` the legs are solved (two bones of 6 px) so each sole centre stays on its spot.
"""

import json
import math
import sys
from pathlib import Path

import numpy as np
import yaml
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

REPO = HERE.parent.parent
OUT = REPO / "plugin" / "src" / "main" / "resources" / "pack" / "emotes"
EMOTES = REPO / "content" / "stsv" / "emotes.yml"
PREVIEWS = REPO / "docs" / "previews"
FIXTURE = REPO / "plugin" / "src" / "test" / "fixtures" / "emote" / "rig.json"
NS = "stsv"

# ------------------------------------------------------------------------------------------------
# The font
# ------------------------------------------------------------------------------------------------

FONT = f"{NS}:emote"
PIXEL = ""             # one skin pixel: a square of CELL x CELL text pixels
GAP = ""               # a transparent skin pixel: the same advance, nothing drawn
CELL = 10                    # text pixels: the glyph's advance and the game's line pitch
TTF = dict(size=16.0, oversample=1.0, shift=[0.0, 0.0])


def own_hinting(ttf: bytes) -> bytes:
    """pixel_ttf's fonts carry a three-byte do-nothing prep so that FreeType uses the font's own (empty) hinting and
    leaves the grid-aligned outlines alone. That holds for FreeType 2.13; FreeType 2.14 auto-hints every TrueType
    font that has no fpgm and a prep of seven bytes or less, whatever else it says, and its auto-hinter (the CJK
    one, for a private-use character) lifts the square's top edge by 0.4 pixel: an eleventh, half-transparent row
    over every pixel. With a prep longer than that, both leave the square exact (check_glyph, and by hand with both
    libraries through FT_Load_Glyph as the client calls it)."""
    import io
    from fontTools.ttLib import TTFont
    font = TTFont(io.BytesIO(ttf))
    font.recalcTimestamp = False
    font["prep"].program.fromBytecode(bytes([0xB0, 0x00, 0x21] * 3))        # PUSHB 0, POP, three times
    font["maxp"].maxSizeOfInstructions = 9
    out = io.BytesIO()
    font.save(out)
    return out.getvalue()


def pack_files():
    """The pack files: the pixel font (TrueType: a bitmap glyph would advance by its width + 1)."""
    from title_banners import pixel_ttf
    # cell row r is drawn at text y r - 1: rows 1..CELL are text y 0..CELL-1, a whole line
    square = [(x, r) for r in range(1, CELL + 1) for x in range(CELL)]
    ttf = own_hinting(pixel_ttf({PIXEL: (square, CELL)}, "STSV Emote Pixel"))
    font = {"providers": [{"type": "space", "advances": {GAP: CELL}},
                          {"type": "ttf", "file": f"{NS}:emote/pixel.ttf", **TTF, "skip": ""}]}
    return {f"assets/{NS}/font/emote.json": json.dumps(font, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            f"assets/{NS}/font/emote/pixel.ttf": ttf}


def check_glyph(ttf: bytes):
    """FreeType (Pillow's) draws the square as CELL x CELL fully covered pixels, its top 7 pixels over the baseline
    (the game puts a glyph's top at text y 7 - that: y 0, the top of the line), and advances exactly CELL."""
    import io
    font = ImageFont.truetype(io.BytesIO(ttf), 16, layout_engine=ImageFont.Layout.BASIC)
    mask, _ = font.getmask2(PIXEL, mode="L")
    assert mask.size == (CELL, CELL), f"the square is drawn as {mask.size}"
    assert set(mask) == {255}, "a pixel of the square is not fully covered"
    assert font.getlength(PIXEL) == CELL, font.getlength(PIXEL)
    assert font.getbbox(PIXEL, anchor="ls") == (0, -7, CELL, CELL - 7), font.getbbox(PIXEL, anchor="ls")


def write(out_dir: Path = OUT):
    files = pack_files()
    check_glyph(files[f"assets/{NS}/font/emote/pixel.ttf"])
    total = 0
    for path, data in sorted(files.items()):
        f = out_dir / path
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(data)
        total += len(data)
    return f"{len(files)} files, {total} bytes"


# ------------------------------------------------------------------------------------------------
# The rig
# ------------------------------------------------------------------------------------------------

PX = 0.9375 / 16             # a skin pixel in blocks (the player model's scale)
TEXT_PX = 0.025              # a text pixel in blocks at scale 1
SEAL = 1.004                 # every face this much larger about its centre (see above)
LEG = 6.0                    # thigh and shin length

# joint: parent, pivot. This order is the plugin's (EmoteRig.Joint).
JOINTS = [
    ("root", None, (0, 0, 0)),
    ("hips", "root", (0, 16, 0)),
    ("chest", "root", (0, 16, 0)),
    ("head", "chest", (0, 24, 0)),
    ("arm_r", "chest", (-5, 22, 0)),
    ("arm_l", "chest", (5, 22, 0)),
    ("thigh_r", "hips", (-2, 12, 0)),
    ("shin_r", "thigh_r", (-2, 6, 0)),
    ("thigh_l", "hips", (2, 12, 0)),
    ("shin_l", "thigh_l", (2, 6, 0)),
]
JOINT_NAMES = [j[0] for j in JOINTS]
# what a pose holds: every joint's angles, and these vectors
VECTORS = {"root": (0, 0, 0), "root_rot": (0, 0, 0), "foot_r": (0, 0, 0), "foot_l": (0, 0, 0),
           "knee_r": (0, 0, 1), "knee_l": (0, 0, 1)}
CHANNELS = ["root", "root_rot"] + JOINT_NAMES[1:] + ["foot_r", "foot_l", "knee_r", "knee_l"]
# both sides at once: the left one as written, the right one mirrored
PAIRS = {"arms": ("arm", "rot"), "thighs": ("thigh", "rot"), "shins": ("shin", "rot"),
         "feet": ("foot", "vec"), "knees": ("knee", "vec")}
EASES = {"linear": lambda u: u, "sine": lambda u: (1 - math.cos(math.pi * u)) / 2,
         "in": lambda u: u * u, "out": lambda u: 1 - (1 - u) * (1 - u)}


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def euler(pitch, yaw, roll):
    """Rz(roll) Ry(yaw) Rx(pitch), degrees"""
    return rot_z(math.radians(roll)) @ rot_y(math.radians(yaw)) @ rot_x(math.radians(pitch))


def about(pivot, r, offset=(0, 0, 0)):
    """4 x 4: turn by r about the pivot, then move by the offset"""
    m = np.eye(4)
    m[:3, :3] = r
    p = np.array(pivot, float)
    m[:3, 3] = p - r @ p + np.array(offset, float)
    return m


def default_pose():
    pose = {c: np.zeros(3) for c in CHANNELS}
    for c, v in VECTORS.items():
        pose[c] = np.array(v, float)
    return pose


def read_pose(section, base, where, problems):
    """A pose section over a base pose: channels, and the both-sides shorthands"""
    pose = {c: v.copy() for c, v in base.items()}
    for key, value in (section or {}).items():
        if key in ("at", "ease"):
            continue
        if not (isinstance(value, list) and len(value) == 3 and all(isinstance(x, (int, float)) for x in value)):
            problems.append(f"{where}: {key} must be three numbers")
            continue
        v = np.array(value, float)
        if key in PAIRS:
            stem, kind = PAIRS[key]
            pose[stem + "_l"] = v
            pose[stem + "_r"] = v * (np.array([1, -1, -1]) if kind == "rot" else np.array([-1, 1, 1]))
        elif key in pose:
            pose[key] = v
        else:
            problems.append(f"{where}: unknown '{key}'")
    return pose


def read_emotes(path=EMOTES):
    """{id: emote}: name, length, step, loop, planted, camera, keyframes [(at, ease, pose)], and the problems found"""
    doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    out, problems = {}, []
    for eid, e in (doc.get("emotes") or {}).items():
        length, step = int(e.get("length", 20)), max(1, int(e.get("step", 2)))
        base = read_pose(e.get("pose"), default_pose(), f"{eid}.pose", problems)
        frames = []
        for k in e.get("keyframes") or [{"at": 0}]:
            at = int(k.get("at", 0))
            if not 0 <= at < length or (frames and at <= frames[-1][0]):
                problems.append(f"{eid}: keyframe at {at} out of order or outside 0..{length - 1}")
                continue
            ease = k.get("ease", "linear")
            if ease not in EASES:
                problems.append(f"{eid}: unknown ease '{ease}'")
                ease = "linear"
            frames.append((at, ease, read_pose(k, base, f"{eid} at {at}", problems)))
        cam = e.get("camera") or {}
        props = []
        for pr in e.get("props") or []:
            rows, colour = [str(r) for r in pr.get("pixels") or []], str(pr.get("color", ""))
            if pr.get("joint") not in JOINT_NAMES[1:] or len(pr.get("at") or []) != 3 or not rows or len(colour) != 7 or len({len(r) for r in rows}) != 1:
                problems.append(f"{eid}.props: joint, at [x, y, z], color \"#rrggbb\" and pixels (rows of one length) are needed")
                continue
            rgb = tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))
            props.append(dict(joint=pr["joint"], at=tuple(float(v) for v in pr["at"]), size=float(pr.get("size", 1)),
                              grid=[[rgb if ch == "#" else None for ch in r] for r in rows]))
        out[eid] = dict(id=eid, name=e.get("name", eid), length=length, step=step, loop=bool(e.get("loop", True)),
                        planted=e.get("legs", "free") == "planted", frames=frames, props=props,
                        camera=(float(cam.get("around", 150)), float(cam.get("up", 14)), float(cam.get("distance", 3.6))))
    return out, problems


def pose_at(emote, tick):
    """The pose at a tick (may be a fraction): eased between the keyframes around it; a loop goes on from the last
    keyframe to the first, anything else holds its first and last keyframes."""
    frames, length = emote["frames"], emote["length"]
    t = tick % length if emote["loop"] else min(max(tick, 0), length)
    nxt = next((i for i, f in enumerate(frames) if f[0] > t), None)
    if nxt == 0:
        if not emote["loop"]:
            return frames[0][2]
        a, b, t0, t1 = frames[-1], frames[0], frames[-1][0] - length, frames[0][0]
    elif nxt is None:
        if not emote["loop"]:
            return frames[-1][2]
        a, b, t0, t1 = frames[-1], frames[0], frames[-1][0], frames[0][0] + length
    else:
        a, b, t0, t1 = frames[nxt - 1], frames[nxt], frames[nxt - 1][0], frames[nxt][0]
    u = EASES[b[1]]((t - t0) / (t1 - t0)) if t1 > t0 else 1.0
    return {c: a[2][c] + (b[2][c] - a[2][c]) * u for c in CHANNELS}


def norm(v):
    n = float(np.linalg.norm(v))
    return v / n if n > 1e-9 else None


def solve_leg(hip, foot, pole):
    """Two bones of LEG from the hip socket to the sole centre, the knee towards the pole: the thigh's and the shin's
    rotation and the knee. A foot out of reach is pulled in along the line to the hip."""
    d = foot - hip
    dist = float(np.linalg.norm(d))
    if dist < 1e-6:
        d, dist = np.array([0.0, -1.0, 0.0]), 1.0
    reach = min(max(dist, 0.5), 2 * LEG - 1e-3)
    down = d / dist
    foot = hip + down * reach
    up = -down
    side = None
    for p in (pole, np.array([0.0, 0.0, 1.0]), np.array([0.0, 1.0, 0.0])):
        side = norm(p - up * float(p @ up))
        if side is not None:
            break
    knee = hip + down * (reach / 2) + side * math.sqrt(LEG * LEG - reach * reach / 4)
    hinge = norm(np.cross(up, side))

    def frame(top, bottom):
        y = (top - bottom) / LEG
        return np.column_stack([hinge, y, np.cross(hinge, y)])

    return frame(hip, knee), frame(knee, foot), knee


def matrices(pose, planted):
    """Every joint's 4 x 4 (rest pixels -> posed pixels), in JOINTS order"""
    pivots = {n: np.array(p, float) for n, _, p in JOINTS}
    out = {"root": about(pivots["root"], euler(*pose["root_rot"]), pose["root"])}
    for name, parent, pivot in JOINTS[1:]:
        if planted and name.startswith(("thigh", "shin")):
            continue
        out[name] = out[parent] @ about(pivot, euler(*pose[name]))
    if planted:
        for side, sx in (("r", -1), ("l", 1)):
            hip = (out["hips"] @ np.append(pivots["thigh_" + side], 1))[:3]
            foot = np.array([2.0 * sx, 0, 0]) + pose["foot_" + side]
            r_thigh, r_shin, knee = solve_leg(hip, foot, pose["knee_" + side])
            for name, r, at in (("thigh_" + side, r_thigh, hip), ("shin_" + side, r_shin, knee)):
                m = np.eye(4)
                m[:3, :3] = r
                m[:3, 3] = at - r @ pivots[name]
                out[name] = m
    return [out[n] for n in JOINT_NAMES]


# ------------------------------------------------------------------------------------------------
# The boxes, their faces, the skin
# ------------------------------------------------------------------------------------------------

# skin part: base (u, v), overlay (u, v), width, height, depth (a 64 x 64 skin; slim arms are 3 wide)
SKIN_PARTS = {
    "head": ((0, 0), (32, 0), 8, 8, 8),
    "body": ((16, 16), (16, 32), 8, 12, 4),
    "arm_r": ((40, 16), (40, 32), 4, 12, 4),
    "arm_l": ((32, 48), (48, 48), 4, 12, 4),
    "leg_r": ((0, 16), (0, 32), 4, 12, 4),
    "leg_l": ((16, 48), (0, 48), 4, 12, 4),
}
# box: joint, skin part, its rows [r0, r1) from the top, x0 (the puppet's right edge), top y of the skin part, z0 (the
# back). Where the body bends (waist, knee, hip joint) the boxes go OVERLAP rows past the joint, into each other:
# straight they show the same pixels in the same place, bent their far ends fill the corner a plain cut would open
# (a box turning about the middle of its end leaves a wedge on the outside of the bend). The hips go on under the
# body's last row with the first rows of the two legs side by side (rows 12, 13 of "body"): the seat.
# This order is the plugin's (PuppetModel.BOXES).
OVERLAP = 2
BOXES = [
    ("head", "head", 0, 8, -4, 32, -4),
    ("chest", "body", 0, 8 + OVERLAP, -4, 24, -2),
    ("hips", "body", 8 - OVERLAP, 12 + OVERLAP, -4, 24, -2),
    ("arm_r", "arm_r", 0, 12, -8, 24, -2),
    ("arm_l", "arm_l", 0, 12, 4, 24, -2),
    ("thigh_r", "leg_r", 0, 6 + OVERLAP, -4, 12, -2),
    ("shin_r", "leg_r", 6 - OVERLAP, 12, -4, 12, -2),
    ("thigh_l", "leg_l", 0, 6 + OVERLAP, 0, 12, -2),
    ("shin_l", "leg_l", 6 - OVERLAP, 12, 0, 12, -2),
]
INFLATE = {"head": 0.5}      # the second layer stands this far off its box (hat), the others:
INFLATE_OTHER = 0.25
# face: its axes in the box (u = text right, v = text up, both seen from outside; normal = u x v) and its shade
# (text displays have no directional light: the shade is fixed, by where the face looks in the rest pose)
FACES = {
    "front": ((1, 0, 0), (0, 1, 0), 0.86),
    "back": ((-1, 0, 0), (0, 1, 0), 0.80),
    "left": ((0, 0, -1), (0, 1, 0), 0.70),
    "right": ((0, 0, 1), (0, 1, 0), 0.70),
    "top": ((1, 0, 0), (0, 0, -1), 1.00),
    "bottom": ((1, 0, 0), (0, 0, 1), 0.55),
}
FACE_NAMES = list(FACES)


def to_modern(skin: Image.Image) -> Image.Image:
    """A 64 x 64 skin as the client reads it: a 64 x 32 one gets its left limbs from the right ones (mirrored) and,
    if nothing in its hat area is transparent, loses the hat; the first layer is always opaque."""
    skin = skin.convert("RGBA")
    if skin.size not in ((64, 64), (64, 32)):
        raise ValueError(f"skin is {skin.size}")
    legacy = skin.height == 32
    px = np.zeros((64, 64, 4), np.uint8)
    px[:skin.height] = np.asarray(skin)
    if legacy:
        for sx, sy, dx, dy, w, h in ((4, 16, 16, 32, 4, 4), (8, 16, 16, 32, 4, 4), (0, 20, 24, 32, 4, 12),
                                     (4, 20, 16, 32, 4, 12), (8, 20, 8, 32, 4, 12), (12, 20, 16, 32, 4, 12),
                                     (44, 16, -8, 32, 4, 4), (48, 16, -8, 32, 4, 4), (40, 20, 0, 32, 4, 12),
                                     (44, 20, -8, 32, 4, 12), (48, 20, -16, 32, 4, 12), (52, 20, -8, 32, 4, 12)):
            px[sy + dy:sy + dy + h, sx + dx:sx + dx + w] = px[sy:sy + h, sx:sx + w][:, ::-1]
    px[0:16, 0:32, 3] = 255
    if legacy and (px[0:32, 32:64, 3] >= 128).all():
        px[0:32, 32:64, 3] = 0
    px[16:32, 0:64, 3] = 255
    px[48:64, 16:48, 3] = 255
    return Image.fromarray(px, "RGBA")


def skin_part(part, slim):
    """(base (u, v), overlay (u, v), width, height, depth) of a skin part; slim arms are 3 wide"""
    base, over, w, h, d = SKIN_PARTS[part]
    return base, over, (3 if slim and part.startswith("arm") else w), h, d


def side_row(skin_px, part, layer, face, row, slim):
    """One row of a skin part's side (front, back, left, right), as read from outside: colours or None (transparent,
    second layer only). Under the body's last row come the first rows of the legs, side by side."""
    base, over, w, h, d = skin_part(part, slim)
    if part == "body" and row >= h:
        below = lambda leg, f: side_row(skin_px, leg, layer, f, row - h, slim)
        return {"front": lambda: below("leg_r", "front") + below("leg_l", "front"),
                "back": lambda: below("leg_l", "back") + below("leg_r", "back"),
                "right": lambda: below("leg_r", "right"), "left": lambda: below("leg_l", "left")}[face]()
    u0, v0 = over if layer else base
    x = {"right": u0, "front": u0 + d, "left": u0 + d + w, "back": u0 + 2 * d + w}[face]
    return [skin_pixel(skin_px, x + i, v0 + d + row, layer) for i in range(w if face in ("front", "back") else d)]


def skin_pixel(skin_px, x, y, layer):
    c = skin_px[y, x]
    return tuple(int(v) for v in c[:3]) if layer == 0 or c[3] >= 128 else None


def box_faces(skin_px, box, layer, slim):
    """The faces of one box in one layer: dicts of name, joint, layer, centre, u, v, cols, rows, cell width and
    height (cw, ch), grid and shade; grid[row][col] = (r, g, b) or None, as the text reads (row 0 on top). An end
    that is not the skin part's own is closed with a cap coloured from the row next to it. Faces with nothing on
    them (second layer) are left out."""
    joint, part, r0, r1, x0, top, z0 = box
    base, over, w, h, d = skin_part(part, slim)
    if slim and part == "arm_r":
        x0 += 1
    u0, v0 = over if layer else base
    g = (INFLATE.get(joint, INFLATE_OTHER)) if layer else 0.0
    sides = {f: [side_row(skin_px, part, layer, f, r, slim) for r in range(r0, r1)] for f in ("right", "front", "left", "back")}

    def lid(x):
        return [[skin_pixel(skin_px, x + i, v0 + j, layer) for i in range(w)] for j in range(d)]

    def cap(row):
        """a w x d lid seen from above (row 0 at the back, col 0 at the puppet's right): every pixel takes the
        colour of the nearest side's pixel in that row (front or back first, then the sides)"""
        f, b, r, l = sides["front"][row], sides["back"][row], sides["right"][row], sides["left"][row]
        return [[min([(d - 1 - j, 0, f[i]), (j, 1, b[w - 1 - i]), (i, 2, r[j]), (w - 1 - i, 3, l[d - 1 - j])],
                     key=lambda c: (c[0], c[1]))[2] for i in range(w)] for j in range(d)]

    grids = dict(sides)
    grids["top"] = lid(u0 + d) if r0 == 0 else cap(0)
    grids["bottom"] = (lid(u0 + d + w) if r1 == h else cap(r1 - r0 - 1))[::-1]

    # the second layer stands off its box; past an end that is not the skin part's own only by half of that, which
    # keeps its cap just outside the first layer's
    lo = np.array([x0 - g, top - r1 - (g if r1 == h else g / 2), z0 - g])
    hi = np.array([x0 + w + g, top - r0 + (g if r0 == 0 else g / 2), z0 + d + g])
    mid, size = (lo + hi) / 2, hi - lo
    pivot = next(np.array(p, float) for n, _, p in JOINTS if n == joint)
    faces = []
    for name in FACE_NAMES:
        grid = grids[name]
        if all(c is None for row in grid for c in row):
            continue
        u, v, shade = np.array(FACES[name][0], float), np.array(FACES[name][1], float), FACES[name][2]
        n = np.cross(u, v)
        rows, cols = len(grid), len(grid[0])
        centre = mid + n * abs(float(n @ size)) / 2
        # A text display's text stands on its origin, and between two poses the client moves that origin in a
        # straight line while it turns the text: the further the origin is from the joint, the further the face
        # strays from its box on the way. So a face whose top edge is nearer the joint than its bottom edge is
        # written upside down (turned half around in its own plane): its origin is then at that edge.
        if float((pivot - centre) @ v) > 1e-6:
            u, v, grid = -u, -v, [line[::-1] for line in grid[::-1]]
        faces.append(dict(name=name, joint=joint, layer=layer, centre=centre, u=u, v=v, cols=cols, rows=rows,
                          cw=abs(float(u @ size)) / cols, ch=abs(float(v @ size)) / rows, grid=grid, shade=shade))
    return faces


def puppet_faces(skin: Image.Image, slim=False):
    """Every face of the puppet: the first layer of every box, then the second layer's faces that are not empty"""
    px = np.asarray(to_modern(skin))
    return [f for layer in (0, 1) for box in BOXES for f in box_faces(px, box, layer, slim)]


# ------------------------------------------------------------------------------------------------
# A face as a text display
# ------------------------------------------------------------------------------------------------

def quaternion(r):
    """(x, y, z, w) of a rotation matrix, w >= 0"""
    t = r[0, 0] + r[1, 1] + r[2, 2]
    if t > 0:
        s = math.sqrt(t + 1) * 2
        q = ((r[2, 1] - r[1, 2]) / s, (r[0, 2] - r[2, 0]) / s, (r[1, 0] - r[0, 1]) / s, s / 4)
    elif r[0, 0] > r[1, 1] and r[0, 0] > r[2, 2]:
        s = math.sqrt(1 + r[0, 0] - r[1, 1] - r[2, 2]) * 2
        q = (s / 4, (r[0, 1] + r[1, 0]) / s, (r[0, 2] + r[2, 0]) / s, (r[2, 1] - r[1, 2]) / s)
    elif r[1, 1] > r[2, 2]:
        s = math.sqrt(1 + r[1, 1] - r[0, 0] - r[2, 2]) * 2
        q = ((r[0, 1] + r[1, 0]) / s, s / 4, (r[1, 2] + r[2, 1]) / s, (r[0, 2] - r[2, 0]) / s)
    else:
        s = math.sqrt(1 + r[2, 2] - r[0, 0] - r[1, 1]) * 2
        q = ((r[0, 2] + r[2, 0]) / s, (r[1, 2] + r[2, 1]) / s, s / 4, (r[1, 0] - r[0, 1]) / s)
    q = np.array(q)
    return -q if q[3] < 0 else q


def display(face, joint_matrix, facing=0.0, lift=0.0):
    """The transformation of a face's text display: translation (blocks from the entity), rotation matrix, scale.
    facing: the puppet's turn about +y (radians; a body yaw of a degrees is -radians(a)); lift: the entity stands
    this many blocks over the puppet's ground."""
    turn = rot_y(facing)
    r = turn @ joint_matrix[:3, :3] @ np.column_stack([face["u"], face["v"], np.cross(face["u"], face["v"])])
    centre = turn @ (joint_matrix @ np.append(face["centre"], 1))[:3] * PX - np.array([0, lift, 0])
    scale = np.array([face["cw"] * PX / (CELL * TEXT_PX) * SEAL, face["ch"] * PX / (CELL * TEXT_PX) * SEAL, 1.0])
    # the middle of the text block, in the display's own frame at scale 1 (see the header)
    middle = np.array([TEXT_PX, TEXT_PX * (CELL * face["rows"] / 2 - 1), 0])
    return centre - r @ (scale * middle), r, scale


def text_rect(translation, r, scale, cols, rows):
    """What the client draws for that display: the corner the text starts at (top-left as read) and the vectors
    of one glyph cell to the right and down, in blocks from the entity."""
    w = CELL * cols
    origin = np.array([(1 - w / 2) * TEXT_PX, (CELL * rows - 1) * TEXT_PX, 0])
    return (translation + r @ (scale * origin), r @ (scale * np.array([CELL * TEXT_PX, 0, 0])),
            r @ (scale * np.array([0, -CELL * TEXT_PX, 0])))


# ------------------------------------------------------------------------------------------------
# Preview
# ------------------------------------------------------------------------------------------------

def sample_skin():
    """A made-up skin for the previews (plain shapes; the left wrist and the right knee are marked so a mirrored
    limb shows): hair and a cap brim on the hat layer, an open vest on the jacket layer."""
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    def part(name, colour, layer=0):
        base, over, w, h, dd = SKIN_PARTS[name]
        u, v = over if layer else base
        d.rectangle([u, v + dd, u + 2 * (w + dd) - 1, v + dd + h - 1], fill=colour)
        d.rectangle([u + dd, v, u + dd + 2 * w - 1, v + dd - 1], fill=colour)
        return u, v

    skin, hair, shirt, trim, jeans, shoe = (232, 184, 150), (74, 48, 34), (38, 150, 160), (250, 240, 220), (52, 62, 110), (60, 60, 64)
    u, v = part("head", skin)
    d.rectangle([u, v + 8, u + 31, v + 9], fill=hair)                    # hair line all around
    d.rectangle([u + 8, v, u + 15, v + 7], fill=hair)                    # top of the head
    d.rectangle([u + 16, v + 8, u + 31, v + 15], fill=hair)              # left side and back
    d.rectangle([u, v + 8, u + 7, v + 12], fill=hair)                    # right side
    d.rectangle([u + 16, v + 13, u + 23, v + 15], fill=skin)
    d.rectangle([u, v + 13, u + 7, v + 15], fill=skin)
    for ex in (u + 9, u + 13):                                           # eyes
        d.rectangle([ex, v + 11, ex + 1, v + 12], fill=(255, 255, 255))
        d.point((ex + (1 if ex == u + 9 else 0), v + 12), fill=(40, 60, 120))
    d.rectangle([u + 11, v + 14, u + 12, v + 14], fill=(170, 96, 86))    # mouth
    u, v = part("body", shirt)
    d.rectangle([u + 4, v + 9, u + 11, v + 10], fill=trim)               # chest stripe
    d.rectangle([u, v + 14, u + 23, v + 15], fill=(28, 28, 32))          # belt, all around
    d.rectangle([u + 20, v + 6, u + 27, v + 7], fill=trim)               # back: a stripe under the shoulders
    for name in ("arm_r", "arm_l"):
        u, v = part(name, shirt)
        d.rectangle([u, v + 10, u + 15, v + 15], fill=skin)              # bare forearms
        d.rectangle([u + 4, v, u + 7, v + 3], fill=shirt)
        d.rectangle([u + 8, v, u + 11, v + 3], fill=skin)                # hand end
    u, v = SKIN_PARTS["arm_l"][0]
    d.rectangle([u, v + 12, u + 15, v + 12], fill=(210, 40, 40))         # left wristband
    for name in ("leg_r", "leg_l"):
        u, v = part(name, jeans)
        d.rectangle([u, v + 14, u + 15, v + 15], fill=shoe)
        d.rectangle([u + 8, v, u + 11, v + 3], fill=shoe)                # soles
    u, v = SKIN_PARTS["leg_r"][0]
    d.rectangle([u + 4, v + 9, u + 7, v + 10], fill=(240, 200, 60))      # right knee patch
    # second layer: a cap (top and a brim over the eyes), an open vest
    u, v = SKIN_PARTS["head"][1]
    d.rectangle([u + 8, v, u + 15, v + 7], fill=(200, 60, 50))
    d.rectangle([u, v + 8, u + 31, v + 9], fill=(200, 60, 50))
    d.rectangle([u + 8, v + 10, u + 15, v + 10], fill=(150, 40, 36))
    u, v = SKIN_PARTS["body"][1]
    vest = (88, 70, 52)
    d.rectangle([u, v + 4, u + 23, v + 13], fill=vest)
    d.rectangle([u + 7, v + 4, u + 8, v + 13], fill=(0, 0, 0, 0))        # open at the front
    d.rectangle([u + 4, v, u + 11, v + 3], fill=vest)
    d.rectangle([u + 6, v, u + 9, v + 3], fill=(0, 0, 0, 0))             # the neck hole
    return im


def render(faces, joints, around, up, size, scale, facing=0.0, ground=True):
    """An orthographic picture of the puppet, as the client would draw it: every face from its text display's
    transformation (text_rect), one-sided, z-buffered. around: the camera's place, degrees from the puppet's front
    towards its left (180 = straight behind); up: degrees above the horizon."""
    w, h = size
    ss = 3
    a, e = math.radians(around), math.radians(up)
    to_cam = np.array([math.sin(a) * math.cos(e), math.sin(e), math.cos(a) * math.cos(e)])
    right = norm(np.cross(np.array([0.0, 1.0, 0.0]), to_cam))
    upv = np.cross(to_cam, right)
    k = scale * ss                                   # picture pixels per block
    ox, oy = w * ss / 2, h * ss * 0.9
    colour = np.zeros((h * ss, w * ss, 3), np.uint8)
    colour[:] = (236, 238, 242)
    depth = np.full((h * ss, w * ss), -1e9)
    if ground:
        colour[int(oy):int(oy) + ss, :] = (150, 156, 168)       # the ground under the puppet's feet
    for f in faces:
        t, r, s = display(f, joints[JOINT_NAMES.index(f["joint"])], facing)
        if float((r @ np.array([0.0, 0.0, 1.0])) @ to_cam) <= 1e-6:
            continue                                 # the text faces away: culled
        p0, du, dv = text_rect(t, r, s, f["cols"], f["rows"])
        e1, e2 = du * f["cols"], dv * f["rows"]
        sx0, sy0, z0 = ox + float(p0 @ right) * k, oy - float(p0 @ upv) * k, float(p0 @ to_cam)
        a1, b1, z1 = float(e1 @ right) * k, -float(e1 @ upv) * k, float(e1 @ to_cam)
        a2, b2, z2 = float(e2 @ right) * k, -float(e2 @ upv) * k, float(e2 @ to_cam)
        det = a1 * b2 - a2 * b1
        if abs(det) < 1e-9:
            continue
        xs = [sx0, sx0 + a1, sx0 + a2, sx0 + a1 + a2]
        ys = [sy0, sy0 + b1, sy0 + b2, sy0 + b1 + b2]
        x_lo, x_hi = max(0, int(math.floor(min(xs)))), min(w * ss - 1, int(math.ceil(max(xs))))
        y_lo, y_hi = max(0, int(math.floor(min(ys)))), min(h * ss - 1, int(math.ceil(max(ys))))
        if x_lo > x_hi or y_lo > y_hi:
            continue
        gx, gy = np.meshgrid(np.arange(x_lo, x_hi + 1) + 0.5 - sx0, np.arange(y_lo, y_hi + 1) + 0.5 - sy0)
        fu = (gx * b2 - gy * a2) / det
        fv = (gy * a1 - gx * b1) / det
        inside = (fu >= 0) & (fu < 1) & (fv >= 0) & (fv < 1)
        z = z0 + fu * z1 + fv * z2
        col = np.clip((fu * f["cols"]).astype(int), 0, f["cols"] - 1)
        row = np.clip((fv * f["rows"]).astype(int), 0, f["rows"] - 1)
        grid = np.array([[c if c is not None else (-1, -1, -1) for c in line] for line in f["grid"]], float)
        rgb = grid[row, col]
        shown = inside & (rgb[..., 0] >= 0) & (z > depth[y_lo:y_hi + 1, x_lo:x_hi + 1])
        block_c = colour[y_lo:y_hi + 1, x_lo:x_hi + 1]
        block_c[shown] = np.clip(np.round(rgb[shown] * f["shade"]), 0, 255).astype(np.uint8)
        depth[y_lo:y_hi + 1, x_lo:x_hi + 1][shown] = z[shown]
    return Image.fromarray(colour, "RGB").resize((w, h), Image.LANCZOS)


def label_font(size):
    for path in ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"):
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def prop_face(prop):
    """A prop as a face (the plugin's PuppetModel.prop): a flat picture on its joint, looking the way the puppet looks,
    not shaded"""
    grid = prop["grid"]
    return dict(name="prop", joint=prop["joint"], layer=0, centre=np.array(prop["at"], float), u=np.array([1.0, 0.0, 0.0]),
                v=np.array([0.0, 1.0, 0.0]), cols=len(grid[0]), rows=len(grid), cw=prop["size"], ch=prop["size"], grid=grid, shade=1.0)


def preview(emote, out: Path, views=None, ticks=None, cell=(176, 236), scale=112):
    """A contact sheet of the loop: one column per tick the plugin sends (every `step`), one row per view"""
    faces = puppet_faces(sample_skin()) + [prop_face(p) for p in emote.get("props", [])]
    views = views or [("3/4 back", emote["camera"][0], emote["camera"][1]), ("side", 90, 4)]
    ticks = ticks if ticks is not None else list(range(0, emote["length"], emote["step"]))
    font, pad, head = label_font(13), 6, 22
    sheet = Image.new("RGB", (pad + len(ticks) * (cell[0] + pad), head + len(views) * (cell[1] + pad)), (255, 255, 255))
    d = ImageDraw.Draw(sheet)
    for c, tick in enumerate(ticks):
        joints = matrices(pose_at(emote, tick), emote["planted"])
        d.text((pad + c * (cell[0] + pad) + 4, 4), f"tick {tick:g}", fill=(60, 60, 70), font=font)
        for r, (label, around, up) in enumerate(views):
            im = render(faces, joints, around, up, cell, scale)
            at = (pad + c * (cell[0] + pad), head + r * (cell[1] + pad))
            sheet.paste(im, at)
            if c == 0:
                d.text((at[0] + 4, at[1] + 3), label, fill=(90, 90, 100), font=font)
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out, optimize=True)
    return sheet.size


# ------------------------------------------------------------------------------------------------
# The fixture: numbers the plugin's rig must give too
# ------------------------------------------------------------------------------------------------

def fixture(emotes):
    """For every emote at a few ticks (also between two sent poses), a puppet turned and lifted: every joint's
    matrix and, for a plain skin, every face's display transformation."""
    skin = Image.new("RGBA", (64, 64), (120, 130, 140, 255))
    faces = puppet_faces(skin)
    cases = []
    for eid, e in sorted(emotes.items()):
        for tick in sorted({0.0, e["step"] * 1.0, e["step"] * 1.5, e["length"] / 2, e["length"] - 0.25}):
            facing, lift = -math.radians(37.0), 1.0
            joints = matrices(pose_at(e, tick), e["planted"])
            shown = []
            for f in faces:
                t, r, s = display(f, joints[JOINT_NAMES.index(f["joint"])], facing, lift)
                shown.append(dict(joint=f["joint"], layer=f["layer"], face=f["name"], cols=f["cols"], rows=f["rows"],
                                  translation=[round(float(x), 6) for x in t],
                                  rotation=[round(float(x), 6) for x in quaternion(r)],
                                  scale=[round(float(x), 6) for x in s]))
            cases.append(dict(emote=eid, tick=tick, yaw=37.0, lift=lift,
                              joints={n: [round(float(x), 5) for x in m[:3].reshape(-1)] for n, m in zip(JOINT_NAMES, joints)},
                              faces=shown))
    return dict(px=PX, seal=SEAL, cell=CELL, joints=JOINT_NAMES, cases=cases)


def fixture_text(emotes):
    return json.dumps(fixture(emotes), ensure_ascii=False, separators=(",", ":")) + "\n"


def main():
    print(f"emotes (plugin/src/main/resources/pack/emotes): {write()}")
    emotes, problems = read_emotes()
    for p in problems:
        print("emotes.yml:", p)
    if problems:
        sys.exit(1)
    if "--fixture" in sys.argv:
        FIXTURE.parent.mkdir(parents=True, exist_ok=True)
        FIXTURE.write_text(fixture_text(emotes), encoding="utf-8")
        print(f"fixture: {FIXTURE.relative_to(REPO)} ({FIXTURE.stat().st_size / 1024:.0f} KB)")
    if "--check" in sys.argv:
        if not FIXTURE.exists() or FIXTURE.read_text(encoding="utf-8") != fixture_text(emotes):
            sys.exit(f"{FIXTURE.relative_to(REPO)} is stale: python3 -I tools/assetgen/emotes.py --fixture")
        print("fixture is current")
    if "--preview" in sys.argv:
        for eid, e in emotes.items():
            out = PREVIEWS / f"emote_{eid}.png"
            print(f"preview: {out.relative_to(REPO)} {preview(e, out)}")


if __name__ == "__main__":
    main()
