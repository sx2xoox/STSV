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
