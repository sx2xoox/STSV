"""
The donation cars' own sounds (content/stsv/items/vehicles.yml classic_roadster, retro_camper, sidecar_bike,
muscle_car, concept_ev, sushi_carnival), synthesized like sounds.py (numpy, original, nothing sampled) and written by it (sounds.py
EXTRA). Until now they borrowed other vehicles' horns, hums and starts; each gets its own here:

  <v>_horn, <v>_horn_end        the horn held (sounds.py horn_hold: HORN_HOLD s) and its release tail
  <v>_engine                    one engine segment (sounds.py engine_segment: ENGINE_PERIOD + ENGINE_FADE, re-triggered
                                by the server with the speed's pitch)
  <v>_engine_start / _stop      getting in and out (as sounds_feel.py: the start fades out over its last ENGINE_FADE s
                                as the hum fades in; its running part is the hum itself, made at pitch 1 and slowed to
                                the item's idle-pitch, so the start ends on the very sound the idle plays)

Characters:
  muscle_car        a big American V8: a deep lumpy burble (the cross-plane firing order: the exhaust pulses of each
                    bank come unevenly, so the beat swings in a two-revolution pattern), heavy low end, a hard rasp
                    on the throttle; the loudest of them. Horn: a loud, low two-tone blare.
  concept_ev        an electric car: no combustion at all. A soft, smooth hum (a gentle sine on 240 Hz at pitch 1 with
                    a faint motor harmonic and a breath of air), every partial kept low (nothing piercing even flat
                    out); much quieter than the rest. Start: a soft two-note chime "띵-" and the hum swelling in;
                    stop: the hum powering down. Horn: a soft modern two-tone beep.
  sushi_carnival    a big seven-seat family minivan: a smooth, quiet modern V6, soft and even with a little hum under
                    it, never rough (no rasp, no lope); medium-low, between the roadster and the EV. Start: a short
                    smooth starter whirr settling straight into the idle; stop: a gentle wind-down. Horn: a friendly
                    mid-pitch two-tone, a major third.
  retro_camper      a small air-cooled flat-four: a rattly, buzzy "달달달", thin and a little tinny (the tin cooling
                    shroud), modest. Horn: a cheerful old "빠-빵" (two reedy blasts, a fourth apart).
  classic_roadster  a smooth inline six: an even, sweet purr, a touch of sporty edge, medium. Horn: a classic mellow
                    two-tone.
  sidecar_bike      a vintage twin: a loping "투둥투둥" (two firings close together, then a gap) through open pipes,
                    each beat a low knock with the pipe ringing and a short bark. Horn: a small bike horn "빵빵".
  agent_sedan       검은 세단 · 요원 K (the 마을버스 secret mission's reward, not a donation car; its sounds live here
                    with the other cars'): the minivan's smooth quiet V6 (minivan_body), a little lower and quieter.
                    Horn: no horn at all but a radio: pressing keys the mic ("칙" a click and a burst of squelch, then
                    the open channel's soft hiss for as long as it is held), letting go drops it with the squelch tail
                    "치익" and a short roger beep (radio_hold, radio_tail).

    python3 sounds_donation.py   # writes only these clips (sounds.py writes them with the rest)
"""

import sys

import numpy as np

import sounds as S
import sounds_feel as F

SR = S.SR
FADE = S.ENGINE_FADE

CARS = ("muscle_car", "concept_ev", "retro_camper", "classic_roadster", "sidecar_bike", "sushi_carnival", "agent_sedan")
NAMES = [(v, k) for v in CARS for k in ("horn", "horn_end", "engine", "engine_start", "engine_stop")]

# the idle pitch each hum is played at (vehicles.yml engine.idle-pitch): the start ends on, the stop begins from, it
IDLE_PITCH = {"muscle_car": 0.5, "concept_ev": 0.7, "retro_camper": 0.6, "classic_roadster": 0.6, "sidecar_bike": 0.5,
              "sushi_carnival": 0.6, "agent_sedan": 0.6}

# loudness of the files (RMS, dBFS; peaks kept under 0.95): the other cars' hums sit at -12 .. -15 dB
ENGINE_RMS_DB = {"muscle_car": -11.5, "concept_ev": -21.0, "retro_camper": -14.5, "classic_roadster": -14.0,
                 "sidecar_bike": -13.0, "sushi_carnival": -17.0, "agent_sedan": -18.0}


def _rms(x):
    return np.sqrt(np.mean(x ** 2)) + 1e-12


def _level(x, rms_db, peak=0.95):
    """To rms_db; a pulsy engine whose peaks would pass `peak` is soft-limited there (its firing peaks rounded off, as
    the louder hums' are by their own shape), then set to the level again."""
    for _ in range(3):
        x = x * (10 ** (rms_db / 20) / _rms(x))
        if np.max(np.abs(x)) <= peak:
            return x
        x = peak * np.tanh(x / peak)
    return x * min(1.0, peak / (np.max(np.abs(x)) + 1e-12))


def _resample(x, m):
    """Band-limited resampling to m samples (FFT): slows a pitch-1 sound down to the pitch it is played at."""
    spec = np.fft.rfft(x)
    out = np.zeros(m // 2 + 1, dtype=complex)
    k = min(len(spec), len(out))
    out[:k] = spec[:k]
    return np.fft.irfft(out, m) * (m / len(x))


def _phase(rng, f):
    return 2 * np.pi * np.cumsum(f) / SR + rng.uniform(0, 2 * np.pi)


# ----------------------------------------------------------------------------------------------
# Engine bodies: body(t, scale, rng) -> samples; scale = the firing rate over its value at pitch 1 (an array)
# ----------------------------------------------------------------------------------------------

def muscle_body(t, scale, rng, throttle=1.0):
    """V8, 68 Hz firing at pitch 1 (34 Hz idling at pitch 0.5). Firing pulses whose spacing and strength swing in a
    pattern over 8 firings (two crank turns: the cross-plane lope, the burble at an eighth, a quarter and three
    eighths of the firing rate), a little random from one firing to the next; a deep exhaust body, a rumble under it
    and a rasp of bright noise on every pulse."""
    n = len(t)
    f = 68.0 * scale * (1 + 0.015 * S.smooth_noise(rng, n, 2.0))
    ph = _phase(rng, f)
    a, b, c = rng.uniform(0, 2 * np.pi, 3)
    warp = ph + 0.45 * np.sin(ph / 8 + a) + 0.25 * np.sin(ph / 4 + b)          # uneven spacing
    lope = 1 + 0.42 * np.sin(ph / 8 + c) + 0.28 * np.sin(ph / 4 + a + 1.1) + 0.16 * np.sin(3 * ph / 8 + b)
    lope *= 1 + 0.18 * S.smooth_noise(rng, n, 30.0)                             # firing-to-firing roughness
    pulses = np.exp(6.0 * (np.cos(warp) - 1)) * np.clip(lope, 0.15, None)
    pulses -= np.mean(pulses)
    body = S.eq(pulses, lambda fr: S.peak(fr, 90, 45, 1.8) * S.peak(fr, 230, 110, 0.7) * S.lowpass(fr, 1400, 2)
                * S.highpass(fr, 24, 2))
    body /= _rms(body)
    rumble = S.resonant_noise(rng, n, [(34.0, 8.0, 1.0), (68.0, 10.0, 0.5)], floor=0.6, floor_cutoff=160.0)
    rasp = S.eq(rng.standard_normal(n), lambda fr: S.peak(fr, 1300, 700, 1.0) * S.highpass(fr, 600, 2) * S.lowpass(fr, 3600, 2))
    gate = np.clip(pulses - pulses.min(), 0, None)
    gate /= np.max(gate) + 1e-9
    rasp = rasp / _rms(rasp) * gate
    return body + 0.3 * rumble + 0.75 * throttle * rasp


def ev_body(t, scale, rng):
    """The electric drive: a gentle sine on 240 Hz at pitch 1 (168 Hz idling at 0.7, 456 Hz flat out at 1.9), soft
    octave and fifth above it, a faint motor harmonic, a low hum below; a breath of air. Everything over ~1.4 kHz
    rolled away, so the top pitch stays soft."""
    n = len(t)
    out = np.zeros(n)
    for ratio, level in ((1.0, 1.0), (2.0, 0.16), (1.5, 0.07), (3.0, 0.05), (0.5, 0.22), (6.0, 0.012)):
        f = 240.0 * ratio * scale * (1 + 0.004 * S.smooth_noise(rng, n, 2.5))
        out += level * np.sin(_phase(rng, f))
    out = S.eq(out, lambda fr: S.lowpass(fr, 1400, 2) * S.highpass(fr, 60, 2))
    out /= _rms(out)
    air = S.eq(rng.standard_normal(n), lambda fr: S.lowpass(fr, 900, 2) * S.highpass(fr, 120, 2))
    return out + 0.12 * air / _rms(air)


def camper_body(t, scale, rng):
    """Air-cooled flat-four, 50 Hz firing at pitch 1 (30 Hz idling at 0.6): sharp pulses with a slight half-order
    unevenness, a thin body (little low end) and tinny resonances (~1.1 and ~2.4 kHz), valve-train rattle on the beats."""
    n = len(t)
    f = 50.0 * scale * (1 + 0.02 * S.smooth_noise(rng, n, 3.0))
    ph = _phase(rng, f)
    amp = 1 + 0.22 * np.sin(ph / 2 + rng.uniform(0, 2 * np.pi))
    amp *= 1 + 0.15 * S.smooth_noise(rng, n, 25.0)
    pulses = np.exp(8.0 * (np.cos(ph) - 1)) * amp
    pulses -= np.mean(pulses)
    body = S.eq(pulses, lambda fr: S.peak(fr, 160, 80, 0.8) * S.peak(fr, 1100, 280, 1.3) * S.peak(fr, 2400, 450, 0.6)
                * S.lowpass(fr, 3400, 2) * S.highpass(fr, 65, 2))
    body /= _rms(body)
    rattle = S.eq(rng.standard_normal(n), lambda fr: S.peak(fr, 2900, 900, 1.0) * S.highpass(fr, 1600, 2) * S.lowpass(fr, 5000, 2))
    tick = np.exp(10.0 * (np.cos(ph * 2 + 0.7) - 1)) * (1 + 0.3 * S.smooth_noise(rng, n, 40.0))
    rattle = rattle / _rms(rattle) * tick
    floor = S.resonant_noise(rng, n, [(50.0, 9.0, 1.0), (100.0, 12.0, 0.6)], floor=0.2, floor_cutoff=200.0)
    return body + 0.35 * rattle + 0.15 * floor


def roadster_body(t, scale, rng):
    """Inline six, 60 Hz firing at pitch 1 (36 Hz idling at 0.6): an even row of harmonics (no lope: a six fires
    evenly), its third order a little forward (the sweet "purr"), soft pulses for texture, a touch of rasp."""
    n = len(t)
    f = 60.0 * scale * (1 + 0.006 * S.smooth_noise(rng, n, 2.5))
    ph = _phase(rng, f)
    tone = np.zeros(n)
    for k, level in enumerate((1.0, 0.6, 0.55, 0.3, 0.22, 0.16, 0.1, 0.07, 0.05, 0.04), start=1):
        wob = 0.02 * S.smooth_noise(rng, n, 4.0)                                # each harmonic breathes a little
        tone += level * (1 + 0.15 * S.smooth_noise(rng, n, 6.0)) * np.sin(k * ph + wob)
    tone = S.eq(tone, lambda fr: S.lowpass(fr, 1600, 2) * S.highpass(fr, 26, 2))
    tone /= _rms(tone)
    pulses = np.exp(3.0 * (np.cos(ph) - 1))
    pulses -= np.mean(pulses)
    purr = S.resonant_noise(rng, n, [(60.0, 7.0, 1.0), (120.0, 8.0, 0.6), (180.0, 9.0, 0.5)], floor=0.15)
    rasp = S.eq(rng.standard_normal(n), lambda fr: S.peak(fr, 1500, 600, 1.0) * S.highpass(fr, 700, 2) * S.lowpass(fr, 3200, 2))
    rasp = rasp / _rms(rasp) * (pulses - pulses.min()) / (np.ptp(pulses) + 1e-9)
    return tone + 0.45 * purr + 0.12 * rasp


def minivan_body(t, scale, rng):
    """Smooth modern V6, 54 Hz firing at pitch 1 (32 Hz idling at 0.6): an even row of harmonics that falls away
    quickly (no lope: it fires evenly, and a quiet muffler takes the edge off), each breathing only a little; a soft
    hum of narrow resonances on its second to fourth harmonics under it and a quiet breath of intake air. Nothing
    over ~1.2 kHz, no rasp."""
    n = len(t)
    f = 54.0 * scale * (1 + 0.004 * S.smooth_noise(rng, n, 2.0))
    ph = _phase(rng, f)
    tone = np.zeros(n)
    for k, level in enumerate((0.75, 0.8, 0.55, 0.3, 0.17, 0.09, 0.05, 0.03), start=1):
        wob = 0.012 * S.smooth_noise(rng, n, 3.0)
        tone += level * (1 + 0.07 * S.smooth_noise(rng, n, 5.0)) * np.sin(k * ph + wob)
    tone = S.eq(tone, lambda fr: S.lowpass(fr, 1100, 2) * S.highpass(fr, 28, 2))
    tone /= _rms(tone)
    hum = S.resonant_noise(rng, n, [(108.0, 6.0, 1.0), (162.0, 7.0, 0.7), (216.0, 8.0, 0.3)], floor=0.08)
    hum /= _rms(hum)
    air = S.eq(rng.standard_normal(n), lambda fr: S.lowpass(fr, 1200, 2) * S.highpass(fr, 220, 2))
    return tone + 0.4 * hum + 0.07 * air / _rms(air)


# the twin: cycles (two firings each) per ENGINE_PERIOD, so 16 a second at pitch 1 (8 idling at 0.5)
TWIN_CYCLES = 8
TWIN_GAP = 0.34             # the second firing this far into the cycle: "투-둥", then the longer gap


def _twin_shot(rng, length, second):
    """One firing out of an open pipe: a low knock, the pipe ringing, a short bark of exhaust noise."""
    d = np.arange(length) / SR
    f = 78.0 if not second else 70.0
    knock = np.sin(2 * np.pi * f * d) * np.exp(-d / 0.03) + 0.5 * np.sin(2 * np.pi * 2 * f * d + 0.4) * np.exp(-d / 0.018)
    ring = 0.35 * np.sin(2 * np.pi * 215.0 * d) * np.exp(-d / 0.035)
    bark = S.eq(rng.standard_normal(length), lambda fr: S.peak(fr, 750, 400, 1.0) * S.highpass(fr, 250, 2) * S.lowpass(fr, 2600, 2))
    bark = 0.5 * bark / _rms(bark) * np.exp(-d / 0.011)
    attack = np.clip(d / 0.002, 0, 1)
    return (knock + ring + bark) * attack * (1.12 if second else 1.0)


def _twin_events(times, n, rng_for, length=None):
    """The shots at `times` (seconds; the i-th firing's shot from rng_for(i))."""
    length = length or int(0.09 * SR)
    out = np.zeros(n)
    for i, at in enumerate(times):
        j = int(round(at * SR))
        if j >= n:
            break
        shot = _twin_shot(rng_for(i), length, i % 2 == 1)
        out[j:j + length] += shot[: max(0, min(length, n - j))]
    return out


def sidecar_segment():
    """The twin's segment, built like the power tiller's (sounds_farm.py): the firings fall on the same beat in every
    segment (TWIN_CYCLES cycles per ENGINE_PERIOD) with the same shot on the same beat, and the fades are linear, so
    two segments overlapping in step add up to one firing. A soft rumble under it."""
    seconds = S.ENGINE_PERIOD + S.ENGINE_FADE
    n = int(round(seconds * SR))
    cycle = S.ENGINE_PERIOD / TWIN_CYCLES
    times = []
    k = 0
    while k * cycle < seconds:
        times += [(k + 0.25) * cycle, (k + 0.25 + TWIN_GAP) * cycle]
        k += 1
    per = 2 * TWIN_CYCLES
    out = _twin_events(times, n, lambda i: np.random.default_rng(90 + (i % per)))
    out /= np.max(np.abs(out)) + 1e-9
    rng = np.random.default_rng(91)
    rumble = S.resonant_noise(rng, n, [(32.0, 6.0, 1.0), (64.0, 8.0, 0.5)], floor=0.3)
    out += 0.05 * rumble / (np.max(np.abs(rumble)) + 1e-9)
    out = S.eq(out, lambda f: S.highpass(f, 30, 2) * S.lowpass(f, 3000, 2))
    t = S.t_axis(seconds)
    return out * np.clip(t / S.ENGINE_FADE, 0, 1) * np.clip((seconds - t) / S.ENGINE_FADE, 0, 1)


def sidecar_run(t, scale, rng):
    """The twin running at a firing rate that changes (start / stop): the cycle phase integrated from scale."""
    n = len(t)
    cyc = np.cumsum(TWIN_CYCLES / S.ENGINE_PERIOD * scale) / SR
    times = []
    for k in range(int(cyc[-1]) + 1):
        for off in (0.0, TWIN_GAP):
            i = np.searchsorted(cyc, k + off)
            if 0 < i < n:
                times.append(i / SR)
    times.sort()
    base = int(rng.integers(1 << 30))
    return _twin_events(times, n, lambda i: np.random.default_rng(base + i))


BODIES = {"muscle_car": muscle_body, "concept_ev": ev_body, "retro_camper": camper_body,
          "classic_roadster": roadster_body, "sushi_carnival": minivan_body, "agent_sedan": minivan_body}


def engine(v):
    """The hum's segment, levelled."""
    seeds = {"muscle_car": 81, "concept_ev": 82, "retro_camper": 83, "classic_roadster": 84, "sushi_carnival": 86,
             "agent_sedan": 87}
    if v == "sidecar_bike":
        x = sidecar_segment()
    else:
        rng = np.random.default_rng(seeds[v])
        x = S.engine_segment(lambda n, t: BODIES[v](t, np.ones(n), rng))
    return _level(x, ENGINE_RMS_DB[v])


# ----------------------------------------------------------------------------------------------
# Start / stop
# ----------------------------------------------------------------------------------------------

# per car: the starter (cranking rate, seconds, whine Hz), the run's rate over the start (relative to idle, a function
# of seconds since it caught), the start's and the stop's length (start = vehicle_feel.yml engine-start.ticks/20 + FADE),
# and the stop's last soft thud (as it settles; 0.7 unless given)
STARTS = {
    # a heavy starter, then a big bark of revs, a second blip, and the lumpy idle
    "muscle_car": dict(crank=(7.5, 0.55, 190.0), start=1.3 + FADE, stop=0.85, seed=101,
                       rev=lambda r: 1 + 2.2 * (r / 0.14) * np.exp(1 - r / 0.14) + 1.1 * np.exp(-((r - 0.55) / 0.09) ** 2)),
    # an old, slow-ish starter, it catches with a rattle and a small rev
    "retro_camper": dict(crank=(8.5, 0.6, 165.0), start=1.1 + FADE, stop=0.7, seed=103,
                         rev=lambda r: 1 + 0.8 * (r / 0.12) * np.exp(1 - r / 0.12)),
    # a quick clean start and one smooth rise
    "classic_roadster": dict(crank=(11.0, 0.38, 230.0), start=1.0 + FADE, stop=0.7, seed=104,
                             rev=lambda r: 1 + 0.9 * (r / 0.16) * np.exp(1 - r / 0.16)),
    # a short starter, then the twin catches and blips
    "sidecar_bike": dict(crank=(13.0, 0.28, 300.0), start=0.9 + FADE, stop=0.6, seed=105,
                         rev=lambda r: 1 + 1.1 * (r / 0.12) * np.exp(1 - r / 0.12)),
    # a short smooth starter whirr, it catches at once and eases up into the idle with hardly a rise; a gentle stop
    "sushi_carnival": dict(crank=(12.5, 0.3, 250.0), start=0.8 + FADE, stop=0.8, seed=106, thud=0.18,
                           rev=lambda r: 1 + 0.35 * (r / 0.2) * np.exp(1 - r / 0.2)),
    # the black sedan: the same smooth V6, a quick starter and one low, short blip; a soft stop
    "agent_sedan": dict(crank=(13.0, 0.28, 240.0), start=0.8 + FADE, stop=0.8, seed=107, thud=0.15,
                        rev=lambda r: 1 + 0.5 * (r / 0.15) * np.exp(1 - r / 0.15)),
}


def _run_as_played(v, t, rate, rng):
    """The running engine over clip time t at `rate` (x idle) as the server would play it: made at pitch 1 (rate 1 =
    the hum's own firing rate) and slowed down by idle-pitch, so at rate 1 it is exactly the idling hum."""
    p = IDLE_PITCH[v]
    n = len(t)
    m = int(round(n * p))
    tt = np.arange(m) / SR
    r = np.interp(tt / p, t, rate)          # the rate at the matching clip time
    if v == "sidecar_bike":
        x = sidecar_run(tt, r, rng)
        x = S.eq(x, lambda f: S.highpass(f, 30, 2) * S.lowpass(f, 3000, 2))
    else:
        x = BODIES[v](tt, r, rng)
    return _resample(x, n)


def start_clip(v):
    e = STARTS[v]
    rng = np.random.default_rng(e["seed"])
    length = e["start"]
    t = S.t_axis(length)
    rate_hz, catch, whine = e["crank"]
    run_t = np.clip(t - catch, 0, None)
    rate = np.where(t < catch, 0.35, e["rev"](run_t))
    run = _run_as_played(v, t, rate, rng)
    run /= _rms(run[t > catch]) if np.any(t > catch) else 1.0
    run_env = np.clip((t - catch) / 0.03, 0, 1) * (0.8 + 0.4 * np.exp(-run_t / 0.3))
    crank = F.starter(t, rate_hz, rng, whine=whine) * np.clip(1 - (t - catch) / 0.08, 0, 1) * np.clip(t / 0.04, 0, 1)
    x = 1.6 * crank + run * run_env
    return F._finish(x * F.envelope(t, 0.01, length, FADE), 0.85 if v == "muscle_car" else 0.8)


def stop_clip(v):
    e = STARTS[v]
    rng = np.random.default_rng(e["seed"] + 100)
    length = e["stop"]
    t = S.t_axis(length)
    k = t / length
    rate = 1 - 0.65 * k ** 1.3
    run = _run_as_played(v, t, rate, rng)
    run /= _rms(run)
    env = (1 - k) ** 1.5 * np.clip(t / 0.01, 0, 1)
    thud_t = np.clip(t - length * 0.72, 0, None)
    thud = np.sin(2 * np.pi * 46 * thud_t) * np.exp(-thud_t / 0.06) * (t >= length * 0.72)
    x = run * env + e.get("thud", 0.7) * thud
    x *= np.clip((length - t) / 0.03, 0, 1)
    return F._finish(x, 0.75 if v == "muscle_car" else 0.7)


EV = dict(start=1.0 + FADE, stop=0.8, seed=102)


def _chime(t, at, f0, decay=0.55):
    """A soft chime note: a sine with a faint octave and a faint bell partial, a 6 ms rise, a slow fade."""
    d = t - at
    on = d >= 0
    d = np.where(on, d, 0.0)
    tone = np.sin(2 * np.pi * f0 * d) + 0.12 * np.sin(2 * np.pi * 2 * f0 * d) * np.exp(-d / 0.2) \
        + 0.04 * np.sin(2 * np.pi * 2.76 * f0 * d) * np.exp(-d / 0.08)
    return np.where(on, tone * np.clip(d / 0.006, 0, 1) * np.exp(-d / decay), 0.0)


def ev_start():
    """The EV waking: a soft two-note chime "띵-" (E5 then A5) and the drive's hum swelling in, rising to its idle."""
    rng = np.random.default_rng(EV["seed"])
    length = EV["start"]
    t = S.t_axis(length)
    p = IDLE_PITCH["concept_ev"]
    chime = _chime(t, 0.02, 659.3) + 0.9 * _chime(t, 0.2, 880.0, 0.65)
    chime = S.eq(chime, lambda f: S.lowpass(f, 2500, 2))
    scale = p * (0.45 + 0.55 * np.clip((t - 0.25) / 0.6, 0, 1) ** 0.8)
    hum = ev_body(t, scale, rng)
    hum *= np.clip((t - 0.2) / 0.55, 0, 1) ** 1.5
    x = 0.55 * chime / np.max(np.abs(chime)) + 0.32 * hum / _rms(hum)
    return S.normalize(x * F.envelope(t, 0.005, length, FADE), 0.5)


def ev_stop():
    """The EV powering down: the hum sliding down and fading, a soft low tone under it fading last."""
    rng = np.random.default_rng(EV["seed"] + 100)
    length = EV["stop"]
    t = S.t_axis(length)
    k = t / length
    p = IDLE_PITCH["concept_ev"]
    hum = ev_body(t, p * (1 - 0.55 * k ** 0.9), rng)
    hum = hum / _rms(hum) * (1 - k) ** 1.4
    low = np.sin(2 * np.pi * np.cumsum(220.0 * (1 - 0.3 * k)) / SR) * np.exp(-t / 0.35) * np.clip(t / 0.02, 0, 1)
    x = 0.4 * hum + 0.25 * low
    x *= np.clip(t / 0.01, 0, 1) * np.clip((length - t) / 0.05, 0, 1)
    return S.normalize(x, 0.4)


# ----------------------------------------------------------------------------------------------
# Horns
# ----------------------------------------------------------------------------------------------

HORNS = {
    # a loud, low two-tone blare (lower and heavier than the tuner car's air horn)
    "muscle_car": dict(tones=(233.0, 294.0), rolloff=0.85, drive=3.2, formant=(1400.0, 650.0, 1.8), top=5500, seed=111),
    # a soft modern beep: two near-pure tones a major third apart, hardly any buzz, a dull top
    "concept_ev": dict(tones=(523.3, 659.3), rolloff=2.6, drive=1.0, formant=(1100.0, 500.0, 0.25), top=2800, seed=112),
    # a classic mellow two-tone: round, a little buzz
    "classic_roadster": dict(tones=(370.0, 466.2), rolloff=1.35, drive=1.5, formant=(1600.0, 700.0, 1.0), top=4800, seed=114),
    # a friendly family-car two-tone: mid-pitch, a bright major third (G4 + B4), smooth with a light buzz
    "sushi_carnival": dict(tones=(392.0, 493.9), rolloff=1.2, drive=1.7, formant=(2000.0, 800.0, 1.1), top=5600, seed=117),
}
# the bulb-like "빠-빵": a reedy, nasal low note then a fourth up
CAMPER_HORN = (dict(tones=(392.0,), rolloff=0.75, drive=2.0, formant=(1150.0, 450.0, 2.0), top=4500, seed=113),
               dict(tones=(523.3,), rolloff=0.75, drive=2.0, formant=(1150.0, 450.0, 2.0), top=4500, seed=115))
# the bike's small horn "빵빵": one buzzy tone, two blasts
SIDECAR_HORN = dict(tones=(440.0,), rolloff=0.95, drive=2.8, formant=(1700.0, 600.0, 1.3), top=5000, seed=116)


def blast_hold(first, second, blast=0.14, gap=0.06):
    """A horn tooted twice: a short blast of `first`, a gap, then `second` held (HORN_HOLD s; the server stops it on
    release, the tail follows)."""
    seconds = 0.03 + S.HORN_HOLD + 0.06
    t = S.t_axis(seconds)
    a = S.horn_tone(first, seconds, start_sag=0.04)
    b = S.horn_tone(second, seconds, start_sag=0.04)
    rise = lambda d: 1 - np.exp(-np.clip(d, 0, None) / 0.007)
    env_a = rise(t) * np.clip((blast - t) / 0.02, 0, 1)
    t2 = blast + gap
    env_b = rise(t - t2) * (t >= t2) * np.clip((seconds - t) / 0.06, 0, 1)
    return (a * env_a * S.horn_gain(first) + b * env_b * S.horn_gain(second))



def _radio_band(x):
    """A handheld radio's voice band: 300 Hz .. 3.2 kHz, a little presence peak, a touch of grit."""
    x = S.eq(x, lambda f: S.highpass(f, 320, 3) * S.lowpass(f, 3200, 3) * S.peak(f, 1800, 700, 0.6))
    x = x / (np.max(np.abs(x)) + 1e-12)
    return np.tanh(1.6 * x) / np.tanh(1.6)


def radio_hold():
    """Keying a radio (the black sedan's "horn"): a sharp click, a short loud burst of squelch noise ("칙"), a faint
    data chirp, then the open channel's soft hiss, slowly fluttering, for the rest of the hold (HORN_HOLD s; the
    server stops it on release and plays radio_tail)."""
    rng = np.random.default_rng(118)
    seconds = 0.03 + S.HORN_HOLD + 0.06
    t = S.t_axis(seconds)
    n = len(t)
    noise = _radio_band(rng.standard_normal(n))
    flutter = 1 + 0.25 * S.smooth_noise(rng, n, 6.0)
    burst = np.exp(-np.clip(t - 0.012, 0, None) / 0.05) * (t >= 0.012)
    hiss = 0.16 * flutter * np.clip((t - 0.06) / 0.05, 0, 1)
    click = np.exp(-t / 0.0025) * np.sin(2 * np.pi * 2600 * t)
    chirp_t = np.clip(t - 0.09, 0, None)
    chirp = np.sin(2 * np.pi * (1400 + 9000 * chirp_t) * chirp_t) * ((t >= 0.09) & (t < 0.15)) * 0.35
    x = 0.9 * click + noise * (0.75 * burst + hiss) + chirp
    x *= np.clip((seconds - t) / 0.06, 0, 1)
    return S.normalize(x, 0.7)


def radio_tail():
    """Letting go: the squelch tail ("치익", a hiss falling away) and a short two-note roger beep; 0.32 s."""
    rng = np.random.default_rng(119)
    seconds = 0.32
    t = S.t_axis(seconds)
    n = len(t)
    noise = _radio_band(rng.standard_normal(n))
    tail = noise * 0.7 * np.exp(-t / 0.06) * np.clip(t / 0.004, 0, 1)
    beep = np.zeros(n)
    for at, f0 in ((0.13, 2000.0), (0.2, 1500.0)):
        d = np.clip(t - at, 0, None)
        on = (t >= at) & (t < at + 0.06)
        beep += np.sin(2 * np.pi * f0 * d) * on * np.clip(d / 0.004, 0, 1) * np.clip((at + 0.06 - t) / 0.006, 0, 1)
    x = tail + 0.4 * beep
    x *= np.clip((seconds - t) / 0.02, 0, 1)
    return S.normalize(x, 0.6)


def horn(v):
    if v == "agent_sedan":
        return radio_hold(), radio_tail()
    if v == "retro_camper":
        return blast_hold(*CAMPER_HORN, blast=0.17, gap=0.07), S.horn_tail(CAMPER_HORN[1])
    if v == "sidecar_bike":
        return blast_hold(SIDECAR_HORN, SIDECAR_HORN, blast=0.12, gap=0.07), S.horn_tail(SIDECAR_HORN)
    soft = 0.6 if v == "concept_ev" else 1.0      # the EV's beep a good deal softer than the other horns
    return soft * S.horn_hold(HORNS[v]), soft * S.horn_tail(HORNS[v])


# ----------------------------------------------------------------------------------------------

def clips():
    out = {}
    for v in CARS:
        hold, tail = horn(v)
        out[(v, "horn")] = (hold, 1)
        out[(v, "horn_end")] = (tail, 1)
        out[(v, "engine")] = (engine(v), 0)
        if v == "concept_ev":
            out[(v, "engine_start")] = (ev_start(), 0)
            out[(v, "engine_stop")] = (ev_stop(), 0)
        else:
            out[(v, "engine_start")] = (start_clip(v), 0)
            out[(v, "engine_stop")] = (stop_clip(v), 0)
    return out


def main():
    for (v, kind), (samples, quality) in clips().items():
        path = S.OUT / f"{v}_{kind}.ogg"
        S.encode(samples, path, quality)
        print(f"sound   {path.relative_to(S.REPO)}  {path.stat().st_size / 1024:.1f} KB")


if __name__ == "__main__":
    sys.exit(main())
