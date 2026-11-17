"""
특별 탈것's cartoon sounds (content/stsv/items/vehicles_fun.yml), synthesized like sounds.py (numpy, original, nothing
sampled) and written by it (sounds.py EXTRA):

  toy_duck_squeak           the rubber duck squeezed: a high reedy squeak gliding up and down (~1.7 to 2.6 kHz), the
                            air rasping through its little whistle. The server pitches it up a step with every quick
                            squeak after another.
  pogo_stick_boing          the spring landing: "boi-oi-oing", a low twang that wobbles and dies away.
  pogo_stick_sproing        the spring overloaded: a long rising "sproiiing" with a rattle, as the stick throws its rider.
  balloon_chair_trombone    the balloons popped: the sad trombone, "wah wah wah waaah", four notes down, the last one
                            sagging with a wobble.
  common_slide_whistle      someone thrown off (the rodeo bull, a tumble): a slide whistle swooping up.
"""

import numpy as np

import sounds as S

SR = S.SR

NAMES = [("toy_duck", "squeak"), ("pogo_stick", "boing"), ("pogo_stick", "sproing"), ("balloon_chair", "trombone"),
         ("common", "slide_whistle")]


def _rms(x):
    return np.sqrt(np.mean(x ** 2)) + 1e-12


def _tone(freq, harmonics):
    """A tone following freq (Hz, an array), with harmonic weights [1st, 2nd, ...]."""
    ph = 2 * np.pi * np.cumsum(freq) / SR
    out = np.zeros_like(freq)
    for k, w in enumerate(harmonics, start=1):
        out += w * np.sin(k * ph)
    return out


def _env(t, attack, release, length):
    up = np.clip(t / max(attack, 1e-6), 0, 1)
    down = np.clip((length - t) / max(release, 1e-6), 0, 1)
    return up * down


def duck_squeak():
    length = 0.32
    t = S.t_axis(length)
    rng = np.random.default_rng(41)
    k = t / length
    # up quickly, a held top, a little droop at the end (the squeeze and the let go)
    f = 1750 + 900 * np.sin(np.pi * np.clip(k * 1.3, 0, 1)) ** 0.6 - 250 * np.clip((k - 0.75) / 0.25, 0, 1)
    f *= 1 + 0.012 * np.sin(2 * np.pi * 38 * t)
    reed = _tone(f, [1.0, 0.55, 0.32, 0.18, 0.1])
    # the rasp: the air chopping through the reed
    rasp = 0.55 + 0.45 * np.sign(np.sin(2 * np.pi * 72 * t))
    air = S.eq(rng.standard_normal(len(t)), lambda fr: S.peak(fr, 2600, 900, 1.0) * S.highpass(fr, 1200, 2))
    x = reed / _rms(reed) * rasp + 0.18 * air / _rms(air)
    return S.normalize(x * _env(t, 0.012, 0.05, length), 0.8)


def pogo_boing():
    length = 0.6
    t = S.t_axis(length)
    # a twang: a low tone whose pitch wobbles fast and slows down as it dies
    wob = np.exp(-t / 0.25)
    f = 190 * (1 + 0.35 * wob * np.sin(2 * np.pi * (14 - 6 * t) * t)) * (1 + 0.25 * np.exp(-t / 0.05))
    x = _tone(f, [1.0, 0.4, 0.15])
    return S.normalize(x * np.exp(-t / 0.22) * _env(t, 0.004, 0.05, length), 0.85)


def pogo_sproing():
    length = 0.9
    t = S.t_axis(length)
    rng = np.random.default_rng(43)
    k = t / length
    f = 160 + 900 * k ** 1.6
    f *= 1 + 0.18 * np.sin(2 * np.pi * 22 * t) * (1 - k)
    x = _tone(f, [1.0, 0.5, 0.25, 0.12])
    rattle = S.eq(rng.standard_normal(len(t)), lambda fr: S.peak(fr, 900, 500, 1.0) * S.highpass(fr, 300, 2))
    rattle *= (np.sin(2 * np.pi * 30 * t) > 0.6) * np.exp(-t / 0.3)
    x = x / _rms(x) + 0.5 * rattle / _rms(rattle)
    return S.normalize(x * _env(t, 0.01, 0.12, length), 0.85)


def trombone():
    """Four notes down (Bb, A, Ab, G below middle C), the last held with a sagging wobble; a brassy buzz with a mute's wah."""
    notes = [(233.1, 0.0, 0.42), (220.0, 0.48, 0.42), (207.7, 0.96, 0.42), (196.0, 1.44, 1.15)]
    length = 2.7
    t = S.t_axis(length)
    out = np.zeros_like(t)
    for i, (f0, start, dur) in enumerate(notes):
        tt = t - start
        on = (tt >= 0) & (tt < dur)
        last = i == len(notes) - 1
        f = f0 * (1 - (0.06 * np.clip(tt / dur, 0, 1) if last else 0)) * (1 + (0.025 * np.sin(2 * np.pi * 5.5 * tt) * np.clip(tt / 0.3, 0, 1) if last else 0))
        tone = _tone(np.where(on, f, f0), [1.0, 0.8, 0.6, 0.45, 0.3, 0.2, 0.12])
        # the plunger mute: each note opens "wah"
        wah = 0.35 + 0.65 * np.clip(tt / 0.12, 0, 1) * np.exp(-np.clip(tt - 0.12, 0, None) / (0.9 if last else 0.5))
        env = np.clip(tt / 0.02, 0, 1) * np.clip((dur - tt) / 0.06, 0, 1)
        out += np.where(on, tone * wah * env, 0)
    out = S.eq(out, lambda fr: S.lowpass(fr, 2400, 2) * S.peak(fr, 700, 400, 0.8))
    return S.normalize(out, 0.8)


def slide_whistle():
    length = 0.75
    t = S.t_axis(length)
    k = t / length
    f = 700 + 1500 * (1 - np.cos(np.pi * k)) / 2
    f *= 1 + 0.01 * np.sin(2 * np.pi * 6 * t)
    x = _tone(f, [1.0, 0.08])
    rng = np.random.default_rng(45)
    breath = S.eq(rng.standard_normal(len(t)), lambda fr: S.peak(fr, 2000, 1500, 1.0))
    x = x / _rms(x) + 0.12 * breath / _rms(breath)
    return S.normalize(x * _env(t, 0.03, 0.1, length), 0.75)


def clips():
    return {
        ("toy_duck", "squeak"): (duck_squeak(), 0),
        ("pogo_stick", "boing"): (pogo_boing(), 0),
        ("pogo_stick", "sproing"): (pogo_sproing(), 0),
        ("balloon_chair", "trombone"): (trombone(), 0),
        ("common", "slide_whistle"): (slide_whistle(), 0),
    }
