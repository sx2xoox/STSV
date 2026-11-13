"""1 fire: a crown of flames along the top, end flames licking outwards, embers and lava drips (approved art t01).

chat_frame: its chat banner (banner_themes/chat.py: one chat line, 18 rows of half pixels). Nothing may rise over a
chat line, so the fire is where there is room: big flames licking outwards at both ends, and a fire along the
plate's inner bottom, kept to dark reds as it burns behind the letters."""
import math, random
from .lib_a import *
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

CHAT_END = 40                 # columns of plate after the text where the fire blazes (banner_themes/chat.py ChatGeo)
CHAT_TILE = 64                # the plate's tile: everything drawn where the text is repeats every 64 columns
# the chat banner's text: lighter than the full banner's (it stands in the fire), with a dark outline
CHAT_TINT = dict(title=((255, 250, 226), (255, 224, 140)), nick=((255, 255, 240), (255, 208, 110)), outline=(34, 6, 4))
# sparks rising over the fire, the same every 64 columns: column, speed (rows a frame), first frame
SPARKS = ((3, 1, 0), (11, 2, 5), (20, 1, 9), (29, 2, 13), (37, 1, 3), (46, 2, 10), (53, 1, 6), (60, 2, 1))
BLAZE_SPARKS = ((5, 2, 3), (13, 1, 12), (20, 2, 8), (27, 1, 1), (33, 2, 14))
GEM7 = ("...#...", "..#a#..", ".#aaab.", "#aawabc", ".bbabc.", "..bcc..", "...c...")
GEM7_COLOURS = {"#": (255, 120, 100), "a": (255, 70, 76), "w": (255, 220, 220), "b": (200, 20, 34), "c": (118, 5, 18)}
EMBER = ((40, 8, 6), (84, 12, 8), (140, 20, 8), (204, 44, 12), (246, 100, 20), (255, 178, 52), (255, 236, 150))


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


def fire_heat(x, y, i, rows):
    """The fire that fills the plate: 0 (none) .. about 1.6 (white hot) at a column and inner row (0 = top) of frame
    i. Tongues of flame: every column has a flame height that changes over the loop (noise over column and frame),
    the tongues sway as they rise, and a finer noise climbing 2 rows a frame breaks up their bodies; over the
    tongues only a faint glow. The same every CHAT_TILE columns, and frame 16 is frame 0."""
    up = 1 - y / (rows - 1)                                  # 0 at the bottom .. 1 at the top
    xs = x + 1.7 * math.sin(y * 0.8 - 2 * math.pi * i / 8) * up
    height = 0.30 + 0.62 * (0.6 * _noise(xs, i, 4, 4, CHAT_TILE, 16, 1) + 0.4 * _noise(xs + 2, i + 2, 8, 8, CHAT_TILE, 16, 3))
    grain = _noise(x, y + 2 * i, 4, 4, CHAT_TILE, 32, 2) - 0.5
    if up >= height:
        return 0.10 * (1 - up) + 0.10 * (height - up + 0.3) + 0.08 * grain
    return 0.30 + 1.15 * ((height - up) / height) ** 0.9 + 0.30 * grain


def chat_geo(Geo):
    return Geo(TITLE, end=CHAT_END)


def chat_frame(G, i):
    """The whole plate burns inside: a fire climbing from its bottom over its full width (dark at the top, glowing
    at the bottom, the same every CHAT_TILE columns so the tiled plate is seamless), sparks rising over it, and
    after the text the heat rises into a white-hot blaze at the plate's right end. The letters keep clear of it by
    their dark outline (title_banners.py)."""
    t = 2 * math.pi * i / N
    im, px = G.canvas()
    base = G.PY1 - 2                                         # the plate's lowest inner row
    top = G.PY0 + 2
    rows = base - top + 1
    blaze0 = G.PX1 - G.END - 9                               # the right end's first column (it keeps 10 more columns)
    glow = lambda x: max(0.0, min(1.0, (x - blaze0) / 30))   # 0 where the plate is tiled, 1 in the blaze
    # the end flames: four tongues an end, licking outwards, as high as the plate
    back = {}
    for side in (-1, 1):
        ex = G.PX0 + 5 if side < 0 else G.PX1 - 5
        tongue(back, G, ex, G.PY1, 19, 5.5, 2.2, side * 0.55, t * 2)
        tongue(back, G, ex - side * 5, G.PY1 - 1, 13, 4.0, 3.0, side * 0.35, t * 3)
        tongue(back, G, ex + side * 3, G.PY1, 11, 3.6, 4.4, side * 1.0, t * 2)
        tongue(back, G, ex + side * 7, G.PY1, 6, 2.6, 1.0, side * 1.3, t * 3)
    for q in [q for q in back if q[1] < G.PY0 or q[1] > G.PY1]: del back[q]
    flush(px, G, back)
    # the plate: gold edge (light over the middle, dark under it), a glowing bevel, and the fire
    hot = {}
    for (x, y) in G.inside:
        if (x, y) in G.edge:
            tip = y in (G.MID, G.MID + 1) and x in (G.PX0, G.PX1)
            c = (255, 240, 180) if tip else (255, 222, 130) if y <= G.MID else (176, 104, 28)
        elif (x, y) in G.bevel:
            c = lerp((150, 34, 10), (255, 110, 30), 0.5 + 0.5 * math.sin(x * math.pi / 8 + y * 0.3 - t * 2))
        else:
            g = glow(x)
            h = fire_heat(x, y - top, i, rows) + g * (0.5 + 0.35 * (y - top) / (rows - 1))
            hot[(x, y)] = h
            c = EMBER[6] if h > 1.42 else EMBER[5] if h > 1.12 else EMBER[4] if h > 0.86 else EMBER[3] if h > 0.62 \
                else EMBER[2] if h > 0.42 else EMBER[1] if h > 0.24 else EMBER[0]
        px[x, y] = c + (255,)
    # sparks over the fire: the same every CHAT_TILE columns where the plate is tiled, more in the blaze
    def spark(x, p, bright):
        y = base - 3 - p
        x += p // 5
        if (x, y) not in G.core or y < top or hot.get((x, y), 9) > 0.62: return
        px[x, y] = ((255, 236, 150) if p < 6 else (255, 170, 60) if p < 11 else (214, 78, 22)) + (255,)
        if bright and (x, y + 1) in G.core and hot.get((x, y + 1), 9) <= 0.62:
            px[x, y + 1] = (255, 150, 44, 255)
    for x0 in range(G.PX0 - G.PX0 % CHAT_TILE, blaze0, CHAT_TILE):
        for col, speed, start in SPARKS:
            if x0 + col < blaze0: spark(x0 + col, (i * speed + start) % 16, False)
    for col, speed, start in BLAZE_SPARKS:
        spark(blaze0 + col, (i * speed + start) % 16, True)
    # a spike off each tip
    for side, x0 in ((-1, G.PX0), (1, G.PX1)):
        for k in range(6):
            for y in (G.MID, G.MID + 1):
                put(px, G, x0 + side * (k + 1), y, (255, 200, 90) if k < 4 else (255, 240, 180))
    for r, row in enumerate(GEM7):
        for c, v in enumerate(row):
            if v != ".": gem_put(px, G, G.DIV - 3 + c, G.MID - 3 + r, GEM7_COLOURS[v])
    return im
