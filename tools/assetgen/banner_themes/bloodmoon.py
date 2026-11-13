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


# The chat banner: a night under the blood moon. A bar of night sky, darkest at the bottom where mist lies, between
# two rails of tarnished silver (a fang hangs from the upper one every 16 columns, and from two of them in every tile
# a drop of blood swells). The moon stands in the left end, its red light fading out before the words begin; the
# right end is a silver thorn. While it idles the moon's halo breathes and the drops swell. Every two seconds the
# moon flares and a flight of bats leaves it and crosses the whole banner: black shapes with red eyes, beating
# their wings, and a wash of red light running with them. Bats are dark on a dark sky, so the words stay plain to
# read even as they pass behind them (and the sky is kept dark there: C.hush).
CHAT_MOON = 6.6                       # the moon's radius
CHAT_BATS = ((11, 5, 0), (30, 10, 1), (47, 4, 1), (66, 9, 0), (84, 6, 1))    # columns behind the front, its middle row, wing beat
CHAT_GEM = {"#": (255, 150, 150), "a": (255, 70, 80), "w": (255, 240, 240), "b": (190, 16, 36), "c": (80, 4, 14)}
CHAT_SILVER, CHAT_SILVER_D = (198, 192, 204), (92, 80, 94)


def chat_frame(G, i):
    im, px = G.canvas()
    step = i - C.CALM
    idle = i % C.CALM
    behind = (lambda x: G.flow_at(x, step)) if step >= 0 else (lambda x: -1)
    mx, my = G.PX0 + 7.5, G.MID + 0.5
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
        cell = xt % 16
        u = behind(x)
        wash = C.strength(u, rise=12, body=40, total=108)
        d = math.hypot(x - mx, y - my)
        a = 255
        if y == G.PY0:
            c = lerp(CHAT_SILVER, (120, 108, 124), C.noise(xt, 0, 4, 4, 64, 16, 421))
            if cell == 0: c = lerp(c, (30, 18, 26), 0.6)
        elif y == G.PY0 + 1:
            c = CHAT_SILVER_D if cell not in (7, 8, 9) else lerp(CHAT_SILVER, (255, 255, 255), 0.2 if cell == 8 else 0.0)    # a fang's root
        elif y == G.PY0 + 2 and cell == 8:
            c = (150, 140, 154)                                                      # the fang's tip
        elif y == G.PY1 - 1:
            stain = C.smooth((C.noise(xt, 0, 6, 4, 64, 16, 423) - 0.55) / 0.2)
            c = lerp((112, 98, 112), (120, 18, 28), 0.7 * stain)
        elif y == G.PY1:
            c = lerp((64, 52, 64), (20, 10, 16), 0.6 if cell == 0 else 0.0)
        else:
            c = sky(x, y)
            # the moon's light on the sky round it, and the wash that runs with the bats
            if d < CHAT_MOON + 22:
                c = lerp(c, (196, 24, 34), 0.62 * (1 - (d - CHAT_MOON) / 22) ** 2 * (0.6 + 0.25 * halo + 0.5 * flare))
            c = lerp(c, (120, 14, 26), 0.40 * wash)
            if x >= G.PX0 + C.PAD - 3: c = C.hush(c, 0.045 + 0.035 * wash)
        # the corners, rounded
        k = C.corner(G, x, y, 2.5)
        if k <= 0: continue
        px[x, y] = tuple(c) + (int(a * k),)

    # the moon: a disc with dark seas, lit from the upper left, in the left end (over the rails)
    for yy in range(G.PY0, G.PY1 + 1):
        for xx in range(int(mx - CHAT_MOON - 1), int(mx + CHAT_MOON + 2)):
            d = math.hypot(xx - mx, yy - my)
            if d > CHAT_MOON: continue
            t = d / CHAT_MOON
            c = lerp((236, 70, 52), (150, 18, 26), t ** 1.6)
            if math.hypot(xx - mx + 2.0, yy - my + 2.0) > CHAT_MOON - 0.2 and (xx - mx) + (yy - my) < -4: c = (255, 150, 118)      # lit rim
            if any(math.hypot(xx - mx - ax, yy - my - ay) <= ar for ax, ay, ar in ((-2, -1.5, 2.0), (2.5, 1.0, 1.6), (-0.5, 3.2, 1.3))):
                c = lerp(c, (104, 10, 20), 0.5)
            c = lerp(c, (255, 190, 150), 0.35 * flare)
            C.put(px, G, xx, yy, c, 255 if d < CHAT_MOON - 0.6 else 190)

    # drops of blood swelling under two fangs of every tile (the idle frames' loop; in a flight they hang full)
    for x0 in range(G.TILE0, G.R0 - 15, 64):
        for cell, off in ((1, 0), (3, 2)):
            x = x0 + cell * 16 + 8
            if x >= G.R0 - 2: continue
            L = (1, 1, 2, 3)[(idle + off) % 4] if step < 0 else 3
            for r in range(L):
                C.put(px, G, x, G.PY0 + 3 + r, BLOOD_D if r < L - 1 else BLOOD_L if L == 3 else BLOOD)

    # the bats
    if step >= 0:
        for x in range(G.PX0 + 12, G.PX1 - 1):
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

    # the right end: a silver spearhead
    for k, h in enumerate((1, 1, 2, 3, 2, 1, 1, 0)):
        for y in range(G.MID - h + (1 if h else 0), G.MID + h + 1):
            lit = y <= G.MID
            C.put(px, G, G.PX1 + 1 + k, y, (255, 255, 255) if k == 7 else (214, 210, 222) if lit else (128, 118, 134))
    C.gem7(px, G, CHAT_GEM)
    return im
