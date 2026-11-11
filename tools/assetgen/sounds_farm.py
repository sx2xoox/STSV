"""
Sounds of the articulated farm vehicles (content/stsv/items/vehicles_farm.yml), synthesized like sounds.py (numpy,
original, nothing sampled) and written by it (sounds.py EXTRA): the same kinds of clip with the same lengths, so the
server plays them the same way (README "탈것": the horn held, the engine in overlapping segments).

  ox_cart          horn      the ox's "음메": a nasal hum opening into a long low "머어~" (5 s, held), a slow wobble
                   horn_end  "-에": the call falling away as it closes (~0.3 s)
                   (no engine: soft hoof steps are vanilla sounds, vehicles_farm.yml train.animal.steps)
  power_tiller     horn      a small electric horn, one buzzy tone
                   engine    the single-cylinder diesel's 통통통: a heavy thump four times a segment (8 a second at
                             pitch 1: the engine's idle at pitch 0.75 is 6 a second, flat out 11), each a dropping low
                             thud with an exhaust pop, over a little rumble. The thumps fall on the same beat in every
                             segment and the fades are linear, so two segments overlapping in step add up to one thump.
  tractor_trailer  horn      a low single-tone horn
                   engine    a big diesel: a deep hum on 30 Hz with a rattling clatter on top
"""

import numpy as np

import sounds as S

VEHICLES = ("ox_cart", "power_tiller", "tractor_trailer")
NAMES = [("ox_cart", "horn"), ("ox_cart", "horn_end"),
         ("power_tiller", "horn"), ("power_tiller", "horn_end"), ("power_tiller", "engine"),
         ("tractor_trailer", "horn"), ("tractor_trailer", "horn_end"), ("tractor_trailer", "engine")]
SR = S.SR


# ----------------------------------------------------------------------------------------------
# The ox: a moo, with formants that move (filtered frame by frame)
# ----------------------------------------------------------------------------------------------

def _formant_filter(x, centres, widths, boosts, hop=256, n=2048):
    """Filters x through resonances whose centres move over time: centres(t) -> [Hz...] (t in seconds), one gain curve a
    frame (Hann windows, half-overlapping sums: a short-time filter)."""
    win = np.hanning(n)
    out = np.zeros(len(x) + n)
    norm = np.zeros(len(x) + n)
    pad = np.concatenate([x, np.zeros(n)])
    f = np.fft.rfftfreq(n, 1 / SR)
    for start in range(0, len(x), hop):
        seg = pad[start:start + n] * win
        t = (start + n / 2) / SR
        g = np.full_like(f, 0.08)
        for c, w, b in zip(centres(t), widths, boosts):
            g += b / (1 + ((f - c) / (w / 2)) ** 2)
        y = np.fft.irfft(np.fft.rfft(seg) * g, n) * win
        out[start:start + n] += y
        norm[start:start + n] += win ** 2
    return (out / np.maximum(norm, 1e-6))[:len(x)]


def _moo(seconds, f0_curve, open_curve, seed, breath=0.03):
    """A lowing voice: a buzzy harmonic source (the vocal folds) on f0_curve(t), through formants that open from a
    closed hum (m) to an open "eo" by open_curve(t) (0 .. 1), with a little breath."""
    rng = np.random.default_rng(seed)
    t = S.t_axis(seconds)
    n = len(t)
    vib = 1 + 0.012 * np.sin(2 * np.pi * 4.6 * t) * np.clip(t / 0.8, 0, 1) + 0.006 * S.smooth_noise(rng, n, 3.0)
    f0 = f0_curve(t) * vib
    phase = 2 * np.pi * np.cumsum(f0) / SR
    src = S.harmonics(phase, float(np.max(f0)) * 1.05, 1.15)
    src += breath * rng.standard_normal(n)

    def centres(tt):
        o = float(np.clip(open_curve(np.array([tt]))[0], 0, 1))
        return [250 + 400 * o, 1000 + 250 * o, 2400 + 200 * o]
    y = _formant_filter(src, centres, widths=(180, 260, 420), boosts=(3.0, 1.6, 0.6))
    y = S.eq(y, lambda f: S.highpass(f, 70, 2) * S.lowpass(f, 3800, 2))
    # even loudness whatever the vowel (the closed hum is all low energy), then the call's own shape: the hum soft,
    # the open "eo" full
    k = int(0.04 * SR)
    rms = np.sqrt(np.convolve(y ** 2, np.ones(k) / k, mode="same")) + 1e-6
    rms = np.convolve(rms, np.ones(k) / k, mode="same")
    return y / rms * (0.45 + 0.55 * np.clip(open_curve(t), 0, 1))


MOO_RMS = 0.22


def ox_horn():
    """The held call: "음" (closed, rising) for 0.35 s, opening into "머어~" that holds for the rest, sinking a little and
    wobbling slowly; a short fade at the very end (the server stops it when space is let go)."""
    seconds = 0.03 + S.HORN_HOLD + 0.06
    f0 = lambda t: 118 + 14 * np.clip(t / 0.35, 0, 1) - 10 * np.clip((t - 0.6) / 4.0, 0, 1) + 3 * np.sin(2 * np.pi * 0.35 * t)
    opening = lambda t: np.clip((t - 0.25) / 0.3, 0, 1) * (0.9 + 0.1 * np.sin(2 * np.pi * 0.5 * t))
    x = _moo(seconds, f0, opening, seed=31)
    t = S.t_axis(seconds)
    env = (1 - np.exp(-t / 0.05)) * np.clip((seconds - t) / 0.06, 0, 1)
    x = x * env
    steady = x[int(1.0 * SR):int(2.0 * SR)]
    return x * min(MOO_RMS / np.sqrt(np.mean(steady ** 2)), 0.8 / np.max(np.abs(x)))


def ox_horn_end():
    """The release: from the open "어" closing to "에" and falling in pitch and loudness over ~0.3 s."""
    seconds = 0.32
    f0 = lambda t: 118 - 30 * (t / seconds) ** 1.3
    closing = lambda t: 1 - 0.6 * np.clip(t / seconds, 0, 1)
    x = _moo(seconds, f0, closing, seed=32, breath=0.05)
    t = S.t_axis(seconds)
    env = np.clip(t / 0.004, 0, 1) * np.exp(-t / 0.11) * np.clip((seconds - t) / 0.03, 0, 1)
    held = ox_horn()
    ref = np.sqrt(np.mean(held[int(1.0 * SR):int(2.0 * SR)] ** 2))
    y = x * env
    return y * (ref / (np.sqrt(np.mean(x[: int(0.05 * SR)] ** 2)) + 1e-9))


# ----------------------------------------------------------------------------------------------
# The power tiller: 통통통
# ----------------------------------------------------------------------------------------------

THUMPS = 4                  # thumps per segment (ENGINE_PERIOD): 8 a second at pitch 1


def _thump(rng, n):
    """One firing: a low thud sliding down (85 -> 55 Hz) dying in ~70 ms, an exhaust pop (noise round 500 Hz, ~25 ms)
    and a tiny tick of the valves."""
    t = np.arange(n) / SR
    f = 55 + 30 * np.exp(-t / 0.025)
    thud = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.07) * (1 - np.exp(-t / 0.002))
    pop = S.eq(rng.standard_normal(n), lambda fr: S.peak(fr, 520, 260, 1.0) * S.lowpass(fr, 1400, 2) * S.highpass(fr, 180, 2))
    pop = pop / (np.sqrt(np.mean(pop[: int(0.03 * SR)] ** 2)) + 1e-9) * np.exp(-t / 0.022)
    tick = S.eq(rng.standard_normal(n), lambda fr: S.highpass(fr, 2200, 2) * S.lowpass(fr, 4500, 2)) * np.exp(-t / 0.006)
    tick = tick / (np.max(np.abs(tick)) + 1e-9)
    return thud + 0.28 * pop + 0.08 * tick


def tiller_engine():
    """One segment (ENGINE_PERIOD + ENGINE_FADE): a thump every ENGINE_PERIOD / THUMPS from half a beat in, so the beat
    runs on across the seam; a soft rumble under it; linear fades (in-step copies sum to one)."""
    seconds = S.ENGINE_PERIOD + S.ENGINE_FADE
    n = int(round(seconds * SR))
    rng = np.random.default_rng(41)
    out = np.zeros(n)
    beat = S.ENGINE_PERIOD / THUMPS
    length = int(0.11 * SR)
    k = 0
    while True:
        at = (k + 0.5) * beat
        if at >= seconds:
            break
        i = int(round(at * SR))
        shot = _thump(np.random.default_rng(41 + (k % THUMPS)), length)   # the same shot on the same beat of every segment
        out[i:i + length] += shot[: max(0, min(length, n - i))]
        k += 1
    rumble = S.resonant_noise(rng, n, [(32.0, 6.0, 1.0), (64.0, 9.0, 0.5)], floor=0.3)
    out = out / (np.max(np.abs(out)) + 1e-9) + 0.06 * rumble / (np.max(np.abs(rumble)) + 1e-9)
    out = S.eq(out, lambda f: S.highpass(f, 30, 2) * S.lowpass(f, 2500, 2))
    t = S.t_axis(seconds)
    fade_in = np.clip(t / S.ENGINE_FADE, 0, 1)
    fade_out = np.clip((seconds - t) / S.ENGINE_FADE, 0, 1)
    return out * fade_in * fade_out


def tractor_engine(n, t):
    """A big diesel: deeper and rougher than the 1-ton truck, a stronger clatter."""
    rng = np.random.default_rng(43)
    return S.combustion(n, t, rng, f0=30.0, count=18, rolloff=0.8, bandwidth=11.0, floor=0.55, warmth=1000, clatter=0.5)


HORNS = {
    # the tiller: a small electric horn, one buzzy mid-high tone
    "power_tiller": dict(tones=(587.0,), rolloff=1.1, drive=2.0, formant=(2600.0, 800.0, 1.2), top=6000, seed=51),
    # the tractor: a low single tone, heavy bell
    "tractor_trailer": dict(tones=(311.0,), rolloff=0.95, drive=2.6, formant=(1500.0, 600.0, 1.6), top=4500, seed=52),
}
ENGINE_LEVEL = {"power_tiller": 0.85, "tractor_trailer": 0.85}


def clips():
    """Every clip: {(vehicle, kind): (samples, ogg quality)}."""
    return {
        ("ox_cart", "horn"): (ox_horn(), 1),
        ("ox_cart", "horn_end"): (ox_horn_end(), 1),
        ("power_tiller", "horn"): (S.horn_hold(HORNS["power_tiller"]), 1),
        ("power_tiller", "horn_end"): (S.horn_tail(HORNS["power_tiller"]), 1),
        ("power_tiller", "engine"): (S.normalize(tiller_engine(), ENGINE_LEVEL["power_tiller"]), 0),
        ("tractor_trailer", "horn"): (S.horn_hold(HORNS["tractor_trailer"]), 1),
        ("tractor_trailer", "horn_end"): (S.horn_tail(HORNS["tractor_trailer"]), 1),
        ("tractor_trailer", "engine"): (S.normalize(S.engine_segment(tractor_engine), ENGINE_LEVEL["tractor_trailer"]), 0),
    }
