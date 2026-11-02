"""
STSV shared palette. EVERY texture must take its colours from here.

Rules (see docs/ART_STYLE.md):
  - each material is one ramp, ordered dark -> light, 3..6 steps
  - neighbouring ramps share hue families so materials sit together (warm metals, cool steel, earthy stone)
  - adding a colour = adding a ramp here, never hard-coding RGB in a generator
"""

PALETTE = {
    # structural
    "brick":   [(66, 24, 22), (98, 36, 30), (128, 50, 38), (156, 68, 50), (184, 94, 68)],
    "soot":    [(30, 22, 22), (48, 34, 32), (70, 46, 40), (96, 60, 50)],
    "mortar":  [(40, 34, 33), (56, 48, 46), (74, 64, 60)],
    "steel":   [(26, 28, 32), (40, 43, 48), (56, 60, 66), (76, 81, 88), (104, 110, 118), (140, 146, 154)],
    "stone":   [(64, 64, 68), (88, 88, 92), (112, 112, 116), (138, 138, 142)],
    "wood":    [(52, 34, 22), (78, 52, 32), (104, 72, 44), (132, 94, 58)],
    # metals & accents
    "copper":  [(78, 38, 24), (120, 60, 34), (168, 90, 50), (206, 128, 76), (234, 170, 118)],
    "brass":   [(110, 80, 32), (170, 130, 56), (212, 176, 92), (240, 214, 140)],
    "gold":    [(128, 82, 18), (190, 140, 36), (244, 202, 74), (255, 242, 164)],
    "hazard":  [(196, 150, 24), (236, 190, 40)],
    "rubber":  [(16, 16, 18), (26, 26, 30), (38, 38, 44), (54, 54, 62)],
    # light & heat
    "fire":    [(56, 10, 8), (124, 26, 12), (196, 66, 16), (238, 134, 28), (255, 204, 76), (255, 244, 168)],
    "lamp":    [(150, 90, 30), (230, 160, 60), (255, 220, 130), (255, 248, 210)],
    # ui-ish details
    "dial":    [(200, 194, 176), (230, 224, 206)],
    "signal":  [(150, 24, 22), (196, 32, 28), (70, 150, 60)],
}


def ramp(name: str, i: int):
    r = PALETTE[name]
    return (*r[max(0, min(len(r) - 1, i))], 255)


def size(name: str) -> int:
    return len(PALETTE[name])
