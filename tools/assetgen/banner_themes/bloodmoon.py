"""12 blood moon: dark crimson plate, a red moon rising behind it with bats, blood drips (approved art t12)."""
import math, random
from .lib_b import *

TITLE = "혈월의 사냥꾼"
BLOOD = (200, 16, 30); BLOOD_D = (120, 6, 16); BLOOD_L = (255, 90, 90)
BAT = [["#.........#", "##..#.#..##", "###.ooo.###", ".###ooo###.", "....#.#...."],   # wings up
       ["....#.#....", "...#ooo#...", ".###ooo###.", "###.....###", "#.........#"]]   # wings down
CRATERS = [(-6, -8, 2.4), (6, -12, 1.8), (9, -5, 2.6), (-12, -3, 1.6)]
MARIA = [(-3, -4, 4.5), (2, -6, 4.0), (-8, -9, 3.0), (6, -2, 3.5)]
DRIPS = [(0.07, 0), (0.19, 9), (0.33, 4), (0.62, 12), (0.80, 6), (0.93, 2)]


def draw(G, i):
    t = TAU * (i % N) / N
    cv = Cv(G)
    mx, my, R = G.CX, G.PY0 + 3, 16.5
    pulse = 0.5 + 0.5 * math.sin(t)
    for y in range(int(my - R - 5), G.PY0 + 1):
        for x in range(int(mx - R - 5), int(mx + R + 6)):
            d = math.hypot(x - mx, y - my)
            if d <= R:
                u = d / R
                c = lerp((240, 66, 50), (150, 16, 22), u ** 1.8)
                if math.hypot(x - mx + 2.2, y - my + 2.2) > R - 0.2 and (x - mx) + (y - my) < -9:
                    c = (255, 160, 128)          # lit rim, upper left
                if any(math.hypot(x - mx - ax, y - my - ay) <= ar for ax, ay, ar in MARIA):
                    c = lerp(c, (130, 14, 22), 0.4)
                for cx, cy, cr in CRATERS:
                    e = math.hypot(x - mx - cx, y - my - cy)
                    if e <= cr:
                        c = lerp(c, (120, 12, 18), 0.5)
                        if e > cr - 1 and (x - mx - cx) + (y - my - cy) > 0.5: c = lerp(c, (255, 140, 110), 0.55)
                cv.put(x, y, c)
            elif d <= R + 3.5:
                ring = int(d - R)
                a_ = (170, 100, 50, 20)[min(3, ring)] * (0.55 + 0.45 * pulse)
                cv.put(x, y, (220, 24, 40), int(a_))
    # ---- bats: a figure-eight across the face of the moon (behind the plate)
    for k, ph in enumerate((0.0, math.pi / 2)):
        a = t + ph
        bx = int(round(mx + 16 * math.cos(a) + 0.01))
        by = int(round(my - 9 - 2 * k + 3 * math.sin(2 * a) + 0.01))
        spr = BAT[(i // 2 + k) % 2]
        cv.sprite(bx - 5, by - 2, spr, {"#": (34, 4, 10), "o": (20, 2, 6)})
        cv.put(bx - 1, by, (255, 70, 60)); cv.put(bx + 1, by, (255, 70, 60)) if spr is BAT[0] else None
    # ---- end thorns
    for side in (-1, 1):
        x0 = G.PX0 if side < 0 else G.PX1
        for k in range(4):
            cv.put(x0 + side * (k + 1), G.MID, (190, 190, 200) if k < 3 else (255, 255, 255))
        cv.put(x0 + side * 1, G.MID - 1, (120, 120, 130)); cv.put(x0 + side * 1, G.MID + 1, (80, 80, 90))
    # ---- plate
    def fill(x, y):
        g = (y - G.PY0) / 16
        c = lerp((52, 6, 12), (14, 2, 6), g)
        # faint moon reflection: a soft red glow band near the centre
        k = max(0.0, 1 - abs(x - mx) / 40) * (1 - g) * 0.25
        return lerp(c, (150, 20, 30), k)
    plate(cv, G, fill, lambda x, y: (232, 220, 226) if y < G.MID - 4 else (176, 160, 170) if y <= G.MID + 3 else (110, 96, 108),
          lambda x, y: lerp((150, 18, 30), (80, 6, 14), (y - G.PY0) / 16))
    gem(cv, G.DIV, G.MID, (255, 240, 240), (255, 70, 80), (210, 20, 40), (140, 8, 24), (80, 4, 14))
    texts(cv, G, i, ramp((0, (255, 255, 255)), (0.25, (236, 226, 232)), (0.7, (190, 170, 180)), (1, (130, 104, 116))),
          ramp((0, (255, 210, 210)), (0.45, (255, 70, 70)), (1, (170, 10, 24))), (10, 0, 4), shine_col=(255, 255, 255))
    # ---- blood drips: a bead swells under the trim, stretches into a string, lets go, falls and fades
    for fx, off in DRIPS:
        x = int(round(G.PX0 + 8 + fx * (G.PW - 17)))
        ph = (i + off) % N
        y0 = G.PY1
        cv.put(x - 1, y0, BLOOD_D); cv.put(x, y0, BLOOD); cv.put(x + 1, y0, BLOOD_D)   # stain on the trim
        cv.put(x, y0 - 1, BLOOD_D)
        if ph < 10:
            L = (1, 1, 2, 2, 2, 3, 3, 4, 4, 5)[ph]
            for r in range(1, L + 1):
                cv.put(x, y0 + r, BLOOD_D if r < L - 1 else BLOOD)
            if L >= 2:
                cv.put(x, y0 + L, BLOOD_L); cv.put(x + 1, y0 + L, BLOOD_D) if L >= 3 else None
        else:
            k = ph - 10
            cv.put(x, y0 + 1, BLOOD_D)
            if k < 4:
                y = y0 + 5 + (k * (k + 1)) // 2
                fade = k / 4
                cv.put(x, y, lerp(BLOOD_L, BLOOD, fade)); cv.put(x, y + 1, lerp(BLOOD, BLOOD_D, fade))
                cv.put(x + 1, y + 1, lerp(BLOOD_D, (50, 4, 10), fade)); cv.put(x, y - 1, lerp(BLOOD_D, (50, 4, 10), fade)) if k < 2 else None
    return cv.im


SLUG = "bloodmoon"
GEO = {}
frame = draw


# ------------------------------------------------------------------------------------------------
# The chat banner (banner_themes/chat.py: one chat line, 18 rows of half pixels): this banner's plate in small, and
# what the full banner shows over its plate (cut off by a chat line) inside it. The red moon rises inside the plate's middle with its bats, the blood drips down inside.
# ------------------------------------------------------------------------------------------------

from . import chat as C


def chat_geo(Geo):
    return C.bar(Geo, TITLE, "gust")


# The chat banner: a night under the blood moon, in a gothic frame of tarnished silver. A bar of night sky, darkest
# at the bottom where mist lies, between two silver rails: the upper one is battlemented (merlons six columns wide,
# a gap of two: the banner's silhouette), a fang hangs from it every 16 columns (from two of them in every tile a
# drop of blood swells) and a ruby is set in every other merlon; the lower one ends in teeth the same way and is
# stained with blood. The left end is the banner's crest: a crescent blood moon held between two bat's wings cast
# in silver, its red light fading out before the words begin. The right end answers it: the rails close in a silver
# cap, and a chain off it carries a spearhead with blood on its point. While it idles the moon's halo breathes and
# the drops swell. Every two seconds the moon flares and a flight of bats leaves it and crosses the whole banner:
# black shapes with red eyes, beating their wings, and a wash of red light running with them. Bats are dark on a
# dark sky, so the words stay plain to read even as they pass behind them (and the sky is kept dark there: C.hush).
CHAT_MOON = 6.6                       # the moon's radius
CHAT_BATS = ((11, 5, 0), (30, 10, 1), (47, 4, 1), (66, 9, 0), (84, 6, 1))    # columns behind the front, its middle row, wing beat
CHAT_GEM = {"#": (255, 150, 150), "a": (255, 70, 80), "w": (255, 240, 240), "b": (190, 16, 36), "c": (80, 4, 14)}
CHAT_SILVER, CHAT_SILVER_D = (198, 192, 204), (92, 80, 94)
CHAT_SILVER_L, CHAT_SILVER_K = (240, 238, 246), (34, 24, 38)
# a wing's fingers, spread from its wrist: (degrees under the horizontal, length); the membrane between two
# fingers is scalloped
CHAT_WING = ((-8, 10.5), (24, 12.0), (56, 12.6), (88, 11.6))
CHAT_LINK = (".SS.", "S..s", "S..s", "S..s", "S..s", ".ss.")
CHAT_BLADE = (1.5, 3, 4.5, 5.5, 5, 4.5, 4, 3.5, 3, 2.5, 2, 1.5, 1, 0.5)   # the spearhead: half its height, column by column


def chat_cell(G, x):
    """a column's place in the rails' repeat of 16 columns, and which repeat it is: counted from the tile's start
    and, in the right end, from that part's own start (the plate grows by 16 columns a step, so the repeat is whole
    where the parts meet)"""
    n, k = divmod(x - (G.R0 if x >= G.R0 else G.TILE0), 16)
    return k, n


def chat_wing(px, G, sx, sy, side):
    """a bat's wing cast in silver, hanging from its wrist (sx, sy) to one side: bright finger bones, a darker
    membrane between them with a scalloped edge, a dark outline"""
    for y in range(G.PY0, G.PY1 + 1):
        for x in range(int(sx) - 13, int(sx) + 14):
            dx, dy = (x + 0.5 - sx) * side, y + 0.5 - sy
            r = math.hypot(dx, dy)
            a = math.degrees(math.atan2(dy, dx))
            if r < 0.3 or not CHAT_WING[0][0] <= a <= CHAT_WING[-1][0]: continue
            for (a0, r0), (a1, r1) in zip(CHAT_WING, CHAT_WING[1:]):
                if a0 <= a <= a1: break
            f = (a - a0) / (a1 - a0)
            edge = r0 + (r1 - r0) * f - 2.2 * math.sin(math.pi * f)
            if r > edge + 0.2: continue
            bone = min(f, 1 - f) * math.radians(a1 - a0) * r          # texels from the nearer finger
            if bone < 0.62: c = lerp(CHAT_SILVER_L, CHAT_SILVER, r / 11)
            elif r > edge - 0.9: c = CHAT_SILVER_K
            else: c = lerp(lerp((128, 116, 134), (66, 52, 70), r / 10), (120, 20, 34), 0.0)
            C.put(px, G, x, y, c)


def chat_frame(G, i):
    im, px = G.canvas()
    step = i - C.CALM
    idle = i % C.CALM
    behind = (lambda x: G.flow_at(x, step)) if step >= 0 else (lambda x: -1)
    mx, my = G.PX0 - 5.5, 9.0
    flare = C.strength(behind(G.PX0) + 16, rise=8, body=40, total=110) if step >= 0 else 0.0
    halo = 0.5 + 0.5 * math.sin(math.pi * idle / 2) if step < 0 else 1.0

    def sky(x, y):
        xt = x - G.TILE0
        g = (y - G.PY0 - 2) / 13
        c = lerp((46, 7, 15), (12, 2, 6), g)
        mist = C.noise(xt, y, 8, 3, 64, 12, 411)
        c = lerp(c, (74, 14, 22), 0.5 * C.smooth((mist - 0.5) / 0.35) * g)          # mist low in the sky
        return c

    for (x, y) in G.inside:
        xt = x - G.TILE0
        k, n = chat_cell(G, x)
        u = behind(x)
        wash = C.strength(u, rise=12, body=40, total=108)
        d = math.hypot(x + 0.5 - mx, y + 0.5 - my)
        dl, dr = x - G.PX0, G.PX1 - x
        if y in (G.PY0, G.PY1) and (k % 8 >= 6 or dr == 0): continue                # the gaps between the merlons
        if y == G.PY0:
            c = lerp(CHAT_SILVER, (120, 108, 124), C.noise(k, 0, 4, 4, 16, 16, 421))
            if k % 8 == 0: c = lerp(c, CHAT_SILVER_L, 0.6)
            if k % 8 == 5: c = lerp(c, CHAT_SILVER_K, 0.45)
            if k in (10, 11): c = (236, 60, 70) if k == 10 else (150, 12, 28)       # a ruby
        elif y == G.PY0 + 1:
            c = CHAT_SILVER_D if k not in (1, 2, 3) else lerp(CHAT_SILVER, (255, 255, 255), 0.2 if k == 2 else 0.0)    # a fang's root
        elif y == G.PY0 + 2 and k == 2:
            c = (150, 140, 154)                                                      # the fang's tip
        elif y == G.PY1 - 1:
            stain = C.smooth((C.noise(k, 0, 4, 4, 16, 16, 423) - 0.55) / 0.2)
            c = lerp((112, 98, 112), (120, 18, 28), 0.7 * stain)
        elif y == G.PY1:
            c = lerp((100, 88, 104), (150, 22, 34), 0.5 * C.smooth((C.noise(k, 0, 4, 4, 16, 16, 423) - 0.55) / 0.2))
            if k % 8 in (0, 5): c = lerp(c, CHAT_SILVER_K, 0.45)
        else:
            c = sky(x, y)
            # the moon's light on the sky round it, and the wash that runs with the bats
            if d < CHAT_MOON + 26:
                c = lerp(c, (196, 24, 34), 0.66 * (1 - (d - CHAT_MOON) / 26) ** 2 * (0.6 + 0.25 * halo + 0.5 * flare))
            c = lerp(c, (120, 14, 26), 0.40 * wash)
            if x >= G.PX0 + C.PAD - 3: c = C.hush(c, 0.045 + 0.035 * wash)
        if dr < 3 and G.PY0 < y < G.PY1:                                             # the right end's cap
            c = lerp(CHAT_SILVER, CHAT_SILVER_D, (y - G.PY0) / 16) if dr == 0 else lerp(CHAT_SILVER_L, CHAT_SILVER, (y - G.PY0) / 16) if dr == 1 else CHAT_SILVER_K
        px[x, y] = tuple(c) + (255,)

    # the crest: two wings of silver, and between them the moon, a crescent lit from the upper left, the rest of
    # its disc in its own shadow
    chat_wing(px, G, mx - 5.5, my - 6.5, -1)
    chat_wing(px, G, mx + 5.5, my - 6.5, 1)
    for yy in range(G.PY0, G.PY1 + 1):
        for xx in range(int(mx - CHAT_MOON - 1), int(mx + CHAT_MOON + 2)):
            d = math.hypot(xx + 0.5 - mx, yy + 0.5 - my)
            if d > CHAT_MOON: continue
            if math.hypot(xx + 0.5 - mx - 3.4, yy + 0.5 - my + 1.8) < CHAT_MOON - 0.9:
                c = lerp(lerp((52, 6, 14), (108, 14, 24), 1 - d / CHAT_MOON), (190, 40, 40), 0.5 * flare)
            else:
                c = lerp((255, 168, 130), (206, 30, 38), (math.hypot(xx + 0.5 - mx + 3.0, yy + 0.5 - my + 3.0) / 9.5) ** 1.5)
                c = lerp(c, (255, 214, 180), 0.45 * flare)
            C.put(px, G, xx, yy, c, 255 if d < CHAT_MOON - 0.6 else 190)

    # drops of blood swelling under two fangs of every tile (the idle frames' loop; in a flight they hang full)
    for x0 in range(G.TILE0, G.R0 - 15, 64):
        for cell, off in ((1, 0), (3, 2)):
            x = x0 + cell * 16 + 2
            if x >= G.R0 - 2: continue
            L = (1, 1, 2, 3)[(idle + off) % 4] if step < 0 else 3
            for r in range(L):
                C.put(px, G, x, G.PY0 + 3 + r, BLOOD_D if r < L - 1 else BLOOD_L if L == 3 else BLOOD)

    # the bats
    if step >= 0:
        for x in range(G.PX0 + 12, G.PX1 - 2):
            u = behind(x)
            delay = (G.part(x) - G.FLOW0) // 16
            for at, row, beat in CHAT_BATS:
                col = u - at + 5
                if not 0 <= col < 11: continue
                pose = BAT[(step + delay + beat) % 2]
                for r, line in enumerate(pose):
                    v = line[int(col)]
                    y = row + r - 2
                    if v == "." or not G.PY0 + 2 <= y <= G.PY1 - 2: continue
                    C.put(px, G, x, y, (3, 0, 2))
                if int(col) in (4, 6): C.put(px, G, x, row - 1 + (0 if pose is BAT[0] else -1) + 1, (255, 64, 56))     # its eyes

    # the right end: a chain of three links off the cap (two seen flat, one on edge between them) and on it a
    # silver spearhead, lit above its ridge and in shade under it, blood on its point; a drop falls from it
    for x0 in (G.PX1 + 1, G.PX1 + 6):
        for r, line in enumerate(CHAT_LINK):
            for c, v in enumerate(line):
                if v != ".": C.put(px, G, x0 + c, G.MID - 2 + r, CHAT_SILVER if v == "S" else CHAT_SILVER_D)
    for x in range(G.PX1 + 4, G.PX1 + 7):
        C.put(px, G, x, G.MID, CHAT_SILVER_L); C.put(px, G, x, G.MID + 1, CHAT_SILVER_D)
    bx = G.PX1 + 9
    for k, h in enumerate(CHAT_BLADE):
        for y in range(G.PY0, G.PY1 + 1):
            dy = y + 0.5 - 9.0
            if abs(dy) > h: continue
            c = (CHAT_SILVER_L if dy > -1 else (214, 210, 222)) if dy < 0 else (CHAT_SILVER_D if dy < 1 else (128, 118, 134))
            if abs(dy) > h - 1 and k < 4: c = lerp(c, CHAT_SILVER_K, 0.55)           # the blade's shoulders
            if k >= 10: c = lerp(c, BLOOD if dy < 0 else BLOOD_D, 0.85 if k >= 11 else 0.5)
            C.put(px, G, bx + k, y, c)
    fall = (idle if step < 0 else step % 4)
    C.put(px, G, bx + 12, G.MID + 3 + fall, BLOOD)
    C.put(px, G, bx + 12, G.MID + 4 + fall, BLOOD_L if fall < 3 else BLOOD_D)
    C.gem7(px, G, CHAT_GEM)
    return im
