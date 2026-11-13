"""
Engine start / stop and the reverse beep (탈것의 느낌, content/stsv/vehicle_feel.yml), synthesized like sounds.py (numpy,
original, nothing sampled) and written by it (sounds.py EXTRA):

  <v>_engine_start   the driver gets in: the starter motor cranking ("드르르르"), the engine catching with a rev and
                     settling to its idle; it fades out over its last ENGINE_FADE s, as the engine's own hum
                     (sounds.py <v>_engine, played from vehicle_feel.yml engine-start.ticks on) fades in. The electric
                     golf cart: a relay click and its motor winding up. The power tiller: a hand crank, slow thumps
                     picking up into its "통통통".
  <v>_engine_stop    the last rider gets out: the idle running down and dying with a shudder (the cart: winding down
                     and a click).
  common_reverse_beep  one "삐-" of a backing-up alarm (~1.1 kHz, 0.3 s); the server repeats it while a truck reverses.

Each engine's character follows its hum in sounds.py: the firing rate at idle (vehicles.yml engine.idle-pitch x the
hum's base frequency), how rough (a diesel's clatter), how big.
"""

import numpy as np

import sounds as S

SR = S.SR
FADE = S.ENGINE_FADE

# per vehicle: idle firing rate (Hz) = the hum's base x its idle pitch, crank (starter) rate and length (s), the rev's
# peak over idle, how rough (diesel clatter), the body's warmth (low-pass Hz), the whole start's length (s: the
# engine-start.ticks in vehicle_feel.yml / 20 + the fade) and the stop's
ENGINES = {
    "police_car": dict(idle=52 * 0.6, crank=(10.0, 0.45), rev=2.2, clatter=0.0, warmth=800, start=1.0 + FADE, stop=0.7, seed=61),
    "cargo_van": dict(idle=58 * 0.6, crank=(10.5, 0.42), rev=2.1, clatter=0.0, warmth=900, start=1.0 + FADE, stop=0.65, seed=62),
    "light_truck": dict(idle=38 * 0.55, crank=(7.0, 0.65), rev=2.0, clatter=0.35, warmth=950, start=1.2 + FADE, stop=0.8, seed=63),
    "box_truck": dict(idle=30 * 0.55, crank=(6.0, 0.8), rev=1.9, clatter=0.45, warmth=900, start=1.4 + FADE, stop=0.9, seed=64),
    "tractor_trailer": dict(idle=30 * 0.6, crank=(6.5, 0.75), rev=1.9, clatter=0.4, warmth=900, start=1.4 + FADE, stop=0.9, seed=65),
    "delivery_scooter": dict(idle=85 * 0.7, crank=(14.0, 0.25), rev=2.4, clatter=0.25, warmth=2400, start=0.8 + FADE, stop=0.5, seed=66),
    "atv": dict(idle=28 * 0.6 * 2, crank=(9.0, 0.35), rev=2.3, clatter=0.2, warmth=1200, start=0.9 + FADE, stop=0.6, seed=67),
    # the sports car: a quick starter, a big bark of revs as it catches
    "sports_car": dict(idle=80 * 0.55, crank=(13.0, 0.3), rev=3.2, clatter=0.15, warmth=2400, start=1.1 + FADE, stop=0.6, seed=70),
    # the tuner car: a rough catch and two blips of the throttle
    "tuner_car": dict(idle=64 * 0.6, crank=(11.0, 0.4), rev=3.4, clatter=0.4, warmth=3000, start=1.2 + FADE, stop=0.6, seed=73),
    # the premium cars: the hyper car a quick starter and a scream of revs, the sedan barely heard, the SUV a slow heavy catch
    "hyper_car": dict(idle=96 * 0.5, crank=(14.0, 0.28), rev=3.6, clatter=0.08, warmth=2800, start=1.1 + FADE, stop=0.6, seed=75),
    "grand_sedan": dict(idle=40 * 0.6, crank=(9.0, 0.4), rev=1.5, clatter=0.0, warmth=600, start=0.9 + FADE, stop=0.7, seed=76),
    "luxury_suv": dict(idle=34 * 0.6, crank=(7.5, 0.6), rev=2.3, clatter=0.12, warmth=1000, start=1.2 + FADE, stop=0.8, seed=77),
}
TILLER = dict(idle=8 * 0.75, start=1.5 + FADE, stop=0.8, seed=68)
CART = dict(start=0.4 + FADE, stop=0.45, seed=69)

VEHICLES = tuple(ENGINES) + ("power_tiller", "golf_cart")
NAMES = ([(v, k) for v in VEHICLES for k in ("engine_start", "engine_stop")] + [("common", "reverse_beep")]
         + [("sports_car", "backfire"), ("sports_car", "skid"), ("tuner_car", "popcorn")])


# ----------------------------------------------------------------------------------------------
# Pieces
# ----------------------------------------------------------------------------------------------

def _rms(x):
    return np.sqrt(np.mean(x ** 2)) + 1e-12


def firing(t, freq, rng, warmth, clatter=0.0, sharp=5.0):
    """A running engine whose firing rate follows freq (Hz, an array over t): a pulse each firing through the engine's
    body (a low resonance, low-passed to warmth), a low rumble, and for a diesel bright clatter on each pulse."""
    n = len(t)
    f = freq * (1 + 0.02 * S.smooth_noise(rng, n, 3.0))
    phase = 2 * np.pi * np.cumsum(f) / SR + rng.uniform(0, 2 * np.pi)
    pulses = np.exp(sharp * (np.cos(phase) - 1))
    pulses -= np.mean(pulses)
    body = S.eq(pulses, lambda fr: S.lowpass(fr, warmth, 2) * S.highpass(fr, 24, 2) * S.peak(fr, 110, 60, 1.2))
    body /= _rms(body)
    rumble = S.eq(rng.standard_normal(n), lambda fr: S.lowpass(fr, 180, 2) * S.highpass(fr, 25, 2))
    out = body + 0.25 * rumble / _rms(rumble)
    if clatter > 0:
        bright = S.eq(rng.standard_normal(n), lambda fr: S.peak(fr, 1900, 600, 1.0) * S.highpass(fr, 800, 2) * S.lowpass(fr, 3400, 2))
        out += clatter * 2.0 * (pulses - pulses.min()) / (np.ptp(pulses) + 1e-9) * bright / _rms(bright)
    return out


def starter(t, rate, rng, whine=210.0):
    """The starter motor turning the engine over: a geared whine (and its harmonics) swelling with each compression
    stroke, `rate` a second, a little faster as it goes; gear noise under it."""
    n = len(t)
    r = rate * (1 + 0.25 * t / max(t[-1], 1e-6))
    stroke = 0.55 + 0.45 * np.cos(2 * np.pi * np.cumsum(r) / SR)
    w = whine * (1 + 0.06 * (stroke - 0.5))
    ph = 2 * np.pi * np.cumsum(w) / SR
    tone = np.sin(ph) + 0.5 * np.sin(2 * ph) + 0.3 * np.sin(3 * ph) + 0.15 * np.sin(5 * ph)
    grind = S.eq(rng.standard_normal(n), lambda fr: S.peak(fr, 1400, 500, 1.0) * S.highpass(fr, 500, 2) * S.lowpass(fr, 3000, 2))
    chug = S.eq(rng.standard_normal(n), lambda fr: S.lowpass(fr, 220, 2) * S.highpass(fr, 40, 2))
    return (0.5 * tone / _rms(tone) + 0.25 * grind / _rms(grind) + 0.6 * chug / _rms(chug)) * stroke


def envelope(t, attack, length, fade):
    """Up in `attack` s, down over the last `fade` s (equal power: it fades as the hum fades in)."""
    up = np.clip(t / max(attack, 1e-6), 0, 1)
    down = np.cos(0.5 * np.pi * np.clip((t - (length - fade)) / fade, 0, 1))
    return up * down


def _finish(x, level=0.8):
    return S.normalize(S.eq(x, lambda fr: S.highpass(fr, 28, 2)), level)


# ----------------------------------------------------------------------------------------------
# Clips
# ----------------------------------------------------------------------------------------------

def start_clip(v):
    e = ENGINES[v]
    rng = np.random.default_rng(e["seed"])
    length = e["start"]
    t = S.t_axis(length)
    rate, crank = e["crank"]
    catch = crank
    # cranking, then the engine catches: the firing rate jumps up past idle (a rev) and settles
    run_t = np.clip(t - catch, 0, None)
    freq = e["idle"] * (1 + (e["rev"] - 1) * (run_t / 0.12) * np.exp(1 - run_t / 0.12))
    freq = np.where(t < catch, e["idle"] * 0.4, freq)
    run = firing(t, freq, rng, e["warmth"], e["clatter"])
    run_env = np.clip((t - catch) / 0.03, 0, 1) * (0.75 + 0.5 * np.exp(-run_t / 0.25))
    crank_part = starter(t, rate, rng) * np.clip(1 - (t - catch) / 0.08, 0, 1) * np.clip(t / 0.04, 0, 1)
    x = 1.8 * crank_part + run * run_env
    return _finish(x * envelope(t, 0.01, length, FADE))


def stop_clip(v):
    e = ENGINES[v]
    rng = np.random.default_rng(e["seed"] + 100)
    length = e["stop"]
    t = S.t_axis(length)
    # the idle runs down: the firing rate sinks to a third, the last beats uneven, a shudder at the end
    k = t / length
    freq = e["idle"] * (1 - 0.65 * k ** 1.3)
    run = firing(t, freq, rng, e["warmth"] * 0.8, e["clatter"] * 0.6, sharp=6.0)
    env = (1 - k) ** 1.6 * np.clip(t / 0.01, 0, 1)
    thud_t = np.clip(t - length * 0.7, 0, None)
    thud = np.sin(2 * np.pi * 48 * thud_t) * np.exp(-thud_t / 0.06) * (t >= length * 0.7)
    x = run * env + 0.6 * thud
    x *= np.clip((length - t) / 0.03, 0, 1)
    return _finish(x, 0.7)


def tiller_start():
    """The hand crank: four slow heavy thumps getting quicker as the flywheel picks up, then it runs (통통통)."""
    rng = np.random.default_rng(TILLER["seed"])
    length = TILLER["start"]
    t = S.t_axis(length)
    out = np.zeros(len(t))
    at, gap = 0.08, 0.28
    while at < length - FADE:
        dt = t - at
        on = dt >= 0
        d = np.where(on, dt, 0.0)
        knock = np.sin(2 * np.pi * 58 * d) * np.exp(-d / 0.06) + 0.5 * np.sin(2 * np.pi * 116 * d + 0.5) * np.exp(-d / 0.035)
        pop = S.eq(rng.standard_normal(len(t)), lambda fr: S.peak(fr, 480, 240, 1.0) * S.highpass(fr, 200, 2) * S.lowpass(fr, 1400, 2))
        pop = 0.35 * pop / _rms(pop) * np.exp(-d / 0.02)
        strength = 0.6 if at < 0.7 else 1.0      # cranked, then firing
        out += np.where(on, (knock + pop) * np.clip(d / 0.004, 0, 1), 0) * strength
        gap = max(1 / (TILLER["idle"] * 1.6), gap * 0.8)
        at += gap
    clank = S.eq(rng.standard_normal(len(t)), lambda fr: S.peak(fr, 2200, 700, 1.0) * S.highpass(fr, 900, 2))
    out += 0.15 * clank / _rms(clank) * np.exp(-t / 0.05)       # the crank engaging
    return _finish(S.eq(out, lambda fr: S.lowpass(fr, 1600, 2)) * envelope(t, 0.005, length, FADE))


def tiller_stop():
    """The single cylinder's thumps slowing and stopping."""
    rng = np.random.default_rng(TILLER["seed"] + 100)
    length = TILLER["stop"]
    t = S.t_axis(length)
    out = np.zeros(len(t))
    at, gap, strength = 0.0, 1 / (TILLER["idle"] * 1.2), 1.0
    while at < length - 0.1:
        dt = t - at
        on = dt >= 0
        d = np.where(on, dt, 0.0)
        knock = np.sin(2 * np.pi * 56 * d) * np.exp(-d / 0.06) + 0.45 * np.sin(2 * np.pi * 112 * d) * np.exp(-d / 0.03)
        out += np.where(on, knock * np.clip(d / 0.004, 0, 1), 0) * strength
        gap *= 1.35
        strength *= 0.7
        at += gap
    rumble = S.eq(rng.standard_normal(len(t)), lambda fr: S.lowpass(fr, 160, 2) * S.highpass(fr, 30, 2))
    out += 0.12 * rumble / _rms(rumble) * np.exp(-t / 0.2)
    out *= np.clip((length - t) / 0.03, 0, 1)
    return _finish(out, 0.7)


def cart_start():
    """The golf cart: a relay's click, then its motor winding up to its idle whine."""
    rng = np.random.default_rng(CART["seed"])
    length = CART["start"]
    t = S.t_axis(length)
    f = 560 * (0.35 + 0.65 * np.clip(t / 0.35, 0, 1) ** 0.7)
    ph = 2 * np.pi * np.cumsum(f) / SR
    whine = np.sin(ph) + 0.5 * np.sin(2 * ph) + 0.25 * np.sin(3 * ph)
    whine *= 0.35 * np.clip((t - 0.03) / 0.1, 0, 1)
    click = rng.standard_normal(len(t)) * np.exp(-t / 0.004)
    click = S.eq(click, lambda fr: S.highpass(fr, 1500, 2) * S.lowpass(fr, 7000, 2))
    x = whine / _rms(whine) * 0.35 + click / (np.max(np.abs(click)) + 1e-9) * 0.9
    return _finish(x * envelope(t, 0.002, length, FADE), 0.6)


def cart_stop():
    """The cart's motor winding down, then the relay letting go."""
    rng = np.random.default_rng(CART["seed"] + 100)
    length = CART["stop"]
    t = S.t_axis(length)
    f = 560 * (1 - 0.7 * (t / length))
    ph = 2 * np.pi * np.cumsum(f) / SR
    whine = (np.sin(ph) + 0.5 * np.sin(2 * ph)) * (1 - t / length) ** 1.5
    dt = np.clip(t - (length - 0.08), 0, None)
    click = rng.standard_normal(len(t)) * np.exp(-dt / 0.004) * (t >= length - 0.08)
    click = S.eq(click, lambda fr: S.highpass(fr, 1500, 2) * S.lowpass(fr, 7000, 2))
    x = 0.4 * whine / _rms(whine) + 0.5 * click / (np.max(np.abs(click)) + 1e-9)
    x *= np.clip((length - t) / 0.01, 0, 1)
    return _finish(x, 0.55)


def reverse_beep():
    """One "삐-": a backing-up alarm's tone (1.1 kHz, a little square: odd harmonics), soft edges, 0.3 s."""
    length = 0.3
    t = S.t_axis(length)
    f0 = 1100.0
    ph = 2 * np.pi * f0 * t
    tone = np.sin(ph) + 0.33 * np.sin(3 * ph) + 0.12 * np.sin(5 * ph)
    env = np.clip(t / 0.01, 0, 1) * np.clip((0.24 - t) / 0.015, 0, 1)
    return S.normalize(tone * env, 0.55)


def backfire():
    """The sports car lifting off at speed: unburnt fuel popping in the exhaust. Three cracks close together (a hard
    click, a short low thump under it, a burst of bright noise dying fast), each a little softer; ~0.45 s."""
    rng = np.random.default_rng(71)
    n = int(SR * 0.5)
    t = np.arange(n) / SR
    out = np.zeros(n)
    for t0, level in ((0.02, 1.0), (0.13, 0.75), (0.21, 0.55), (0.33, 0.35)):
        d = np.where(t >= t0, t - t0, 0.0)
        on = t >= t0
        crack = S.eq(rng.standard_normal(n), lambda f: S.peak(f, 1700, 900, 1.2) * S.highpass(f, 500, 2) * S.lowpass(f, 5200, 2))
        crack /= _rms(crack)
        thump = np.sin(2 * np.pi * 95.0 * d) * np.exp(-d / 0.03)
        out += np.where(on, level * (0.55 * crack * np.exp(-d / 0.018) + 0.9 * thump) * np.clip(d / 0.0015, 0, 1), 0.0)
    return _finish(out * envelope(t, 0.0, 0.5, 0.06), 0.85)


def popcorn():
    """One pop of the tuner car's crackle (the server plays several in a row, each at its own pitch): a dry, hard
    crack out of a big empty pipe, a ring from the pipe after it, almost no thump; ~0.16 s."""
    rng = np.random.default_rng(74)
    n = int(SR * 0.18)
    t = np.arange(n) / SR
    d = np.where(t >= 0.004, t - 0.004, 0.0)
    crack = S.eq(rng.standard_normal(n), lambda f: S.peak(f, 2300, 1200, 1.4) * S.highpass(f, 700, 2) * S.lowpass(f, 7000, 2))
    crack /= _rms(crack)
    ring = np.sin(2 * np.pi * 410.0 * d) * np.exp(-d / 0.022)
    thump = np.sin(2 * np.pi * 130.0 * d) * np.exp(-d / 0.015)
    out = np.where(t >= 0.004, (0.8 * crack * np.exp(-d / 0.011) + 0.45 * ring + 0.4 * thump) * np.clip(d / 0.001, 0, 1), 0.0)
    return _finish(out * envelope(t, 0.0, 0.18, 0.04), 0.85)


def skid():
    """The sports car drifting: tyres squealing on the road. Noise through two narrow resonances that waver (a pair of
    tyres a little apart in pitch), a rough scrub under them. One piece of 0.5 s between its fades (the server plays it
    again every half second: each fades in while the last fades out)."""
    rng = np.random.default_rng(72)
    length = 0.5 + FADE
    n = int(SR * length)
    t = np.arange(n) / SR
    squeal = S.resonant_noise(rng, n, [(1380.0, 45.0, 1.0), (1560.0, 55.0, 0.8), (2850.0, 120.0, 0.35)])
    scrub = S.eq(rng.standard_normal(n), lambda f: S.highpass(f, 600, 2) * S.lowpass(f, 3800, 2))
    x = squeal / _rms(squeal) + 0.3 * scrub / _rms(scrub)
    x *= 1 + 0.12 * S.smooth_noise(rng, n, 18.0)
    fade = np.sin(0.5 * np.pi * np.clip(t / FADE, 0, 1)) * np.cos(0.5 * np.pi * np.clip((t - (length - FADE)) / FADE, 0, 1))
    return _finish(x * fade, 0.7)


def clips():
    out = {}
    for v in ENGINES:
        out[(v, "engine_start")] = (start_clip(v), 0)
        out[(v, "engine_stop")] = (stop_clip(v), 0)
    out[("power_tiller", "engine_start")] = (tiller_start(), 0)
    out[("power_tiller", "engine_stop")] = (tiller_stop(), 0)
    out[("golf_cart", "engine_start")] = (cart_start(), 0)
    out[("golf_cart", "engine_stop")] = (cart_stop(), 0)
    out[("common", "reverse_beep")] = (reverse_beep(), 0)
    out[("sports_car", "backfire")] = (backfire(), 0)
    out[("sports_car", "skid")] = (skid(), 0)
    out[("tuner_car", "popcorn")] = (popcorn(), 0)
    return out
