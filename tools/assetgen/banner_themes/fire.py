"""1 fire: a crown of flames along the top, end flames licking outwards, embers and lava drips (approved art t01).

chat_frame: its chat banner (banner_themes/chat.py: one chat line, 18 rows of half pixels). Nothing may rise over a
chat line, so the fire is inside the plate, and it is not a steady fire: the plate idles as a bed of embers with the
words plain on it (frames 0-3), and every few seconds a gust of fire runs along it from left to right, ragged, never
covering all of it (frames 4-15, played by the shader part after part: CHAT_FLOW)."""
import math, random
from .lib_a import *
from . import chat as C
TITLE, SLUG = "화염의 군주", "fire"
GEO = {}
STATIC = 4
RED, ORA, YEL, WHI = (196, 34, 12), (255, 104, 18), (255, 194, 56), (255, 248, 214)
DEEP = (128, 16, 8)

def tongue(L, G, cx, base, h, half, ph, lean, t, layer=0):
    """one flame tongue into layer dict L {(x,y): heat}; tapers to a 1px tip."""
    hh = h * (0.74 + 0.26 * math.sin(t + ph))
    for r in range(int(hh) + 1):
        f = r / max(1, hh)
        w = half * (1 - f ** 1.25)
        sway = math.sin(r * 0.5 - t * 2 + ph) * 1.3 * f + lean * f * hh
        y = base - r
        c0 = cx + sway
        for x in range(int(math.floor(c0 - w)), int(math.ceil(c0 + w)) + 1):
            d = abs(x - c0) / max(0.55, w)
            if d > 1 and not (abs(x - c0) < 0.5): continue
            heat = (1 - min(1, d)) * 0.8 + (1 - f) * 0.6 + layer * 0.15
            if heat > L.get((x, y), -1): L[(x, y)] = heat

def colour(heat):
    return WHI if heat > 1.2 else YEL if heat > 0.9 else ORA if heat > 0.55 else RED if heat > 0.28 else DEEP

def flush(px, G, L, behind_plate=True):
    S = set(L)
    for (x, y), heat in L.items():
        if behind_plate and (x, y) in G.inside: continue
        n = sum((x + a, y + b) in S for a in (-1, 0, 1) for b in (-1, 0, 1) if a or b)
        if n < 2: continue                                   # no lone flecks
        c = colour(heat)
        # the outline of the fire is always red, the tips stay red/orange (never the dark ember colour)
        if any((x + a, y + b) not in S for a, b in DIRS4): c = RED if heat < 0.9 else ORA
        old = px[x, y]
        if old[3] == 0 or sum(c) > sum(old[:3]): px[x, y] = c + (255,)

def frame(G, i):
    t = 2 * math.pi * i / N
    im, px = G.canvas()
    rnd = random.Random(4)
    half = G.PW / 2
    back = {}
    # a crown of flames along the top, symmetric profile rising towards both ends (meets the end flames)
    offs = list(range(1, int(half) - 3, 3))
    jit = [rnd.uniform(-1.5, 1.5) for _ in offs]
    for side in (-1, 1):
        for k, o in enumerate(offs):
            cx = G.CX + side * o
            prof = 11 + 7 * (o / half) ** 1.8 + jit[k]
            ph = k * 1.9 + (0 if side < 0 else 3.3)
            tongue(back, G, cx, G.PY0 + 1, prof, 2.4, ph, side * 0.012 * o / 3, t * (1 + k % 3))
    # the end flames: two tongues per end licking outwards
    for side in (-1, 1):
        ex = G.PX0 + 2 if side < 0 else G.PX1 - 2
        tongue(back, G, ex, G.PY1 - 1, 20, 3.8, 2.2, side * 0.45, t * 2)
        tongue(back, G, ex - side * 3, G.PY1 - 3, 14, 2.8, 3.0, side * 0.30, t * 3)
        tongue(back, G, ex + side * 1, G.PY1, 9, 2.4, 4.4, side * 0.95, t * 2)
    flush(px, G, back)
    glow(px, G, (255, 90, 20), 150)
    plate(px, G, lambda x, y, g: lerp((58, 14, 10), (10, 4, 6), g), (255, 222, 130), (176, 104, 28),
          bevel=lambda x, y: lerp((150, 34, 10), (255, 110, 30), 0.5 + 0.5 * math.sin(x * 0.4 + y * 0.3 - t * 2)))
    rivets(px, G, (255, 214, 110), (190, 120, 40))
    spikes(px, G, (255, 200, 90), (255, 240, 180))
    gem(px, G, G.DIV, G.MID, (255, 220, 220), (255, 70, 76), (215, 26, 36), (170, 10, 28), (118, 5, 18), top=(255, 120, 100))
    texts(px, G, i,
          lambda g: (255, 252, 226) if g < 0.15 else lerp((255, 226, 120), (232, 160, 40), (g - 0.15) / 0.6) if g < 0.75 else lerp((200, 120, 30), (150, 80, 20), (g - 0.75) / 0.25),
          lambda g: lerp(WHI, YEL, g * 1.6) if g < 0.6 else lerp(YEL, ORA, (g - 0.6) / 0.4), (16, 3, 0))
    # small licks over the top rim
    front = {}
    for k, o in enumerate(range(2, int(half) - 6, 5)):
        for side in (-1, 1):
            tongue(front, G, G.CX + side * o, G.PY0, 3, 1.3, k * 2.0 + side, 0, t * 3, layer=1)
    for q in list(front):
        if q in G.text_near: del front[q]
    flush(px, G, front, behind_plate=False)
    # embers: 2px sparks rising on a 16-frame cycle, cooling as they climb
    sp = random.Random(8)
    for k in range(12):
        side = -1 if k % 2 else 1
        o = sp.uniform(4, half - 4); sx = G.CX + side * o
        speed = (1, 2)[k % 2]; off = sp.randint(0, 15)
        p = (i * speed + off) % 16
        y = G.PY0 - 3 - p
        x = round(sx + 1.5 * math.sin(t * speed + k))
        if p > 13 or (x, y) in G.inside: continue
        put(px, G, x, y, (255, 240, 160) if p < 6 else (255, 170, 60) if p < 10 else (220, 80, 24))
        put(px, G, x, y + 1, (255, 130, 40) if p < 10 else (150, 30, 10))
    # lava drips under the plate: swell, fall, fade
    for k, o in enumerate((-36, -12, 12, 36)):
        dx = G.CX + o
        ph = (i + k * 4) % N
        if ph < 8:
            n = 1 + ph // 3
            for r in range(n): put(px, G, dx, G.PY1 + 1 + r, (255, 140, 40) if r == n - 1 else (220, 80, 20))
        elif ph < 13:
            y = G.PY1 + 3 + (ph - 8) * 2
            put(px, G, dx, G.PY1 + 1, (200, 70, 20))
            put(px, G, dx, y, (255, 150, 50) if ph < 11 else (200, 60, 16)); put(px, G, dx, y + 1, (190, 50, 12) if ph < 11 else (130, 24, 8))
    return im


# ------------------------------------------------------------------------------------------------
# The chat banner
# ------------------------------------------------------------------------------------------------

CHAT_TILE = 64                # the plate's tile: the idle plate repeats every 64 columns
CHAT_FLOW = 16                # a gust of fire runs along the banner, 16 columns a step (banner_themes/chat.py flow_at)
CALM = 4                      # frames 0 .. 3: the idle plate; the others: the steps of a gust
GUST = 108                    # how long a gust is (columns): it has passed a 64-column part in the 12 steps
GEM7 = ("...#...", "..#a#..", ".#aaab.", "#aawabc", ".bbabc.", "..bcc..", "...c...")
GEM7_COLOURS = {"#": (255, 120, 100), "a": (255, 70, 76), "w": (255, 220, 220), "b": (200, 20, 34), "c": (118, 5, 18)}


def _lattice(ix, iy, seed):
    """a fixed pseudo-random number in [0, 1) for a lattice point"""
    h = (ix * 374761393 + iy * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65536.0


def _noise(x, y, cell_x, cell_y, wrap_x, wrap_y, seed):
    """smooth value noise that repeats every wrap_x columns and wrap_y rows"""
    fx, fy = x / cell_x, y / cell_y
    ix, iy = math.floor(fx), math.floor(fy)
    tx, ty = fx - ix, fy - iy
    tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    nx, ny = wrap_x // cell_x, wrap_y // cell_y
    v = lambda a, b: _lattice(a % nx, b % ny, seed)
    top = v(ix, iy) + (v(ix + 1, iy) - v(ix, iy)) * tx
    bot = v(ix, iy + 1) + (v(ix + 1, iy + 1) - v(ix, iy + 1)) * tx
    return top + (bot - top) * ty


def _smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def _fbm(x, y, seed):
    """three octaves of smooth noise, 0 .. 1"""
    return (0.55 * _noise(x, y, 8, 5, 1 << 20, 1 << 12, seed) + 0.30 * _noise(x, y, 4, 3, 1 << 20, 3 << 10, seed + 1)
            + 0.15 * _noise(x, y, 2, 2, 1 << 20, 1 << 12, seed + 2))


# heat -> colour, a smooth ramp (no flat bands): embers, red, orange, yellow, white hot
RAMP = ((0.10, (58, 10, 6)), (0.28, (118, 18, 8)), (0.48, (188, 36, 10)), (0.70, (238, 88, 18)), (0.92, (255, 158, 44)),
        (1.15, (255, 212, 108)), (1.45, (255, 246, 200)))


def fire_colour(h, base):
    """the colour of heat h over the plate's own colour there"""
    if h <= RAMP[0][0]:
        return lerp(base, RAMP[0][1], max(0.0, h) / RAMP[0][0])
    for (h0, c0), (h1, c1) in zip(RAMP, RAMP[1:]):
        if h <= h1:
            return lerp(c0, c1, (h - h0) / (h1 - h0))
    return RAMP[-1][1]


def gust_strength(u):
    """how strong a gust burns u columns behind its front: up fast, a body, a long tail (smooth both ways)"""
    if u <= 0 or u >= GUST:
        return 0.0
    return _smooth(u / 14) * (1.0 if u < 48 else 1 - _smooth((u - 48) / (GUST - 48)))


def gust_heat(u, y, rows):
    """A gust of fire, u columns behind its front, at inner row y (0 = top): its heat (RAMP's scale), 0 where there
    is none. Ragged on purpose: tongues of very different heights with thin stretches between them, leaning back
    as they rise (the top of a flame trails behind its foot), their edges torn by turbulence, and around every
    flame a glow that fades out instead of an outline."""
    up = 1 - y / (rows - 1)                                  # 0 at the bottom .. 1 at the top
    uu = u - 10 * up                                         # the flame leans back: higher is later
    e = gust_strength(uu)
    if e <= 0:
        return 0.0
    tall = 0.62 * _noise(uu, 0, 9, 4, 1 << 20, 16, 7) + 0.38 * _noise(uu, 0, 4, 4, 1 << 20, 16, 8)   # the tongues
    thin = _noise(uu + 40, 0, 22, 4, 1 << 20, 16, 9)         # where the gust is thin
    height = e * (0.16 + 1.55 * tall ** 1.6) * (0.45 + 0.8 * thin)
    turb = _fbm(uu, y * 1.3 - uu * 0.5, 11) - 0.5
    d = height - up + 0.42 * turb * (0.4 + up)
    if d > 0:
        return 0.36 + 1.2 * min(1.0, d / max(height, 0.08)) ** 0.75 + 0.25 * turb
    return 0.36 * math.exp(d / 0.09)


def bed_heat(x, y, rows):
    """the idle plate: a low bed of embers (the same in every frame and every CHAT_TILE columns), its glow fading upwards"""
    up = 1 - y / (rows - 1)
    bed = 0.10 + 0.13 * _noise(x, 0, 4, 4, CHAT_TILE, 16, 5)
    grain = _noise(x, y, 4, 4, CHAT_TILE, 32, 2) - 0.5
    if up >= bed:
        return 0.30 * math.exp(-(up - bed) / 0.11) + 0.03 * grain
    return 0.34 + 0.62 * (1 - up / bed) + 0.12 * grain


def frame_colour(G, x, y, e):
    """The strip's upper and lower edge at a pixel, None inside it. e: how strong the gust burns here (0: idle).
    No frame: the top is charred, with an ember smouldering here and there (the same every 64 columns); the bottom
    is the edge of the ember bed, glowing. Both heat up as the gust passes."""
    xt = x - G.TILE0
    if y == G.PY0:
        ember = C.smooth((C.noise(xt, 0, 4, 4, 64, 16, 61) - 0.62) / 0.2)
        return C.lerp(C.lerp((30, 9, 7), (150, 44, 12), 0.8 * ember), (255, 160, 56), 0.55 * e)
    if y == G.PY0 + 1:
        return C.lerp((44, 12, 9), (214, 80, 22), 0.5 * e)
    if y == G.PY1 - 1:
        glow = C.noise(xt, 0, 4, 4, 64, 16, 63)
        return C.lerp(C.lerp((170, 44, 12), (246, 110, 28), glow), (255, 196, 96), 0.7 * e)
    if y == G.PY1:
        glow = C.noise(xt, 0, 4, 4, 64, 16, 65)
        return C.lerp(C.lerp((96, 20, 8), (176, 50, 14), glow), (255, 150, 48), 0.6 * e)
    return None


def burnt(G, x, y, c, e, flicker):
    """The plate's ends are burnt away like the edge of burning paper, no end pieces: an uneven edge (a different
    depth in every row), a thin glowing rim along it that fades out into the air, charred plate behind the rim.
    Returns the pixel's colour and alpha, or None where the plate is gone. c: the plate's colour there, e: how
    strong the gust burns there (the rim flares), flicker: -1 .. 1, the rim's own slow glow."""
    left = x - G.PX0 < G.PX1 - x
    d = x - G.PX0 if left else G.PX1 - x                     # columns from the plate's end
    edge = 3.5 + 7.5 * _noise(y, 0, 3, 4, 1 << 10, 16, 21 if left else 29)
    if d >= edge + 3.5:
        return c, 255
    if d < edge - 2.4:
        return None
    hot = max(0.0, min(1.0, 0.28 + 0.18 * flicker + 0.75 * e))
    if d < edge - 1.3:
        return lerp((226, 84, 20), (255, 196, 96), hot), int(70 + 90 * hot)
    if d < edge:
        return lerp((255, 150, 44), (255, 244, 200), hot), 255
    t = (d - edge) / 3.5
    return lerp(lerp((96, 22, 8), (20, 6, 5), min(1.0, t * 2.2)), c, t * t), 255


def chat_geo(Geo):
    return C.bar(Geo, TITLE, "gust")


def chat_frame(G, i):
    """The plate as a bed of embers (frames 0 .. CALM - 1); in the other frames a gust of fire on its way along it."""
    im, px = G.canvas()
    base = G.PY1 - 2                                         # the plate's lowest inner row
    top = G.PY0 + 2
    rows = base - top + 1
    step = i - CALM
    behind = (lambda x: G.flow_at(x, step)) if step >= 0 else (lambda x: -1)
    # the end flames, licking outwards: low while the plate idles (flickering in the idle frames' own loop), high
    # while the gust is at their end of the plate
    t = 2 * math.pi * (i % CALM) / CALM if step < 0 else 2 * math.pi * i / N
    back = {}
    for side in (-1, 1):
        ex = G.PX0 + 7 if side < 0 else G.PX1 - 7
        grow = 0.42 + 0.58 * gust_strength(behind(G.PX0 if side < 0 else G.PX1))
        tongue(back, G, ex, G.PY1, 19 * grow, 5.5 * (0.6 + 0.4 * grow), 2.2, side * 0.55, t * 2)
        tongue(back, G, ex - side * 5, G.PY1 - 1, 13 * grow, 4.0, 3.0, side * 0.35, t * 3)
        tongue(back, G, ex + side * 3, G.PY1, 11 * grow, 3.6, 4.4, side * 1.0, t * 2)
        tongue(back, G, ex + side * 7, G.PY1, 6 * grow, 2.6, 1.0, side * 1.3, t * 3)
    # shaded smoothly like the fire inside, fading out at their edges instead of an outline
    for (x, y), heat in back.items():
        if G.PY0 <= y <= G.PY1 and 0 <= x < G.W:
            px[x, y] = fire_colour(0.34 + 0.8 * heat, (150, 24, 8)) + (255 if heat > 0.45 else max(70, int(255 * heat / 0.45)),)
    # the plate: its frame (frame_colour), and inside it the embers and the gust
    flicker = math.sin(t * 2)
    for (x, y) in G.inside:
        u = behind(x)
        e = gust_strength(u)
        c = frame_colour(G, x, y, e)
        if c is None:
            h = bed_heat(x - G.TILE0, y - top, rows)
            if u > 0:
                h = max(h, gust_heat(u, y - top, rows))
            c = fire_colour(h, lerp((62, 15, 10), (26, 6, 6), (y - top) / (rows - 1)))
        left = burnt(G, x, y, c, e, flicker)                 # the ends: burnt away
        if left is not None:                                 # where the plate is gone the end flames show
            px[x, y] = tuple(left[0]) + (left[1],)
    for r, row in enumerate(GEM7):
        for c, v in enumerate(row):
            if v != ".": gem_put(px, G, G.DIV - 3 + c, G.MID - 3 + r, GEM7_COLOURS[v])
    return im
