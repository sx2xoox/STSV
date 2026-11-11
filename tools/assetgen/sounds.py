"""
Vehicle sounds, synthesized here (numpy) and encoded to Ogg Vorbis with ffmpeg: original tones, nothing sampled.

    python3 sounds.py          # also run by build.py

Writes content/stsv/assets/stsv/sounds/vehicle/*.ogg and content/stsv/assets/stsv/sounds.json (the pack copies both as they
are). Per vehicle (police_car, light_truck, golf_cart):

  <v>_horn.ogg      the horn held down: a short attack, then a steady tone for HORN_HOLD seconds (the server's longest
                    hold, vehicles.horn.max-hold-seconds) and a short fade. Played once per press, bound to the vehicle
                    so it moves with it; on release the server stops it and plays the tail below. One clip instead of
                    re-triggered pieces: a horn is one steady tone, and two copies of it overlapping beat against each
                    other (a wobble at every seam), so it is never cut into pieces.
  <v>_horn_end.ogg  the release tail (~0.2 s): the tone dying away with a slight pitch drop, as a diaphragm horn does.
  <v>_engine.ogg    one engine segment: ENGINE_FADE fade-in, steady part, ENGINE_FADE fade-out (equal-power fades). The
                    server plays one every ENGINE_PERIOD seconds / pitch, so each fades in while the last fades out and
                    the pitch follows the speed segment by segment (vehicles.yml engine.segment-ticks = ENGINE_PERIOD*20).

Characters: the golf cart a high thin electric beep and a quiet motor whine; the 1-ton truck a low two-tone horn and a
diesel hum with a light clatter; the police car a mid two-tone horn and a soft petrol hum. The road vehicles of the
transport ladder (ROAD, vehicles_road.yml): the bicycle a brass bell "따릉" (struck and trilled; its "engine" a soft
chain tick), the delivery scooter a buzzy "빽" and a two-stroke ring, the ATV a short two-tone and a single-cylinder
thump, the van a bright car horn and a small petrol hum, the box truck a deep heavy horn and a bigger diesel.

Deterministic: fixed seeds and a bit-exact encoder, so a rebuild writes the same bytes.
"""

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
REPO = HERE.parent.parent
CONTENT = REPO / "content" / "stsv"
OUT = CONTENT / "assets" / "stsv" / "sounds" / "vehicle"
SOUNDS_JSON = CONTENT / "assets" / "stsv" / "sounds.json"

SR = 22050                  # mono; nothing here needs more than ~8 kHz
HORN_HOLD = 5.0             # seconds of steady tone in the hold clip (the longest hold the server allows)
HORN_TAIL = 0.22            # seconds of the release tail
ENGINE_FADE = 0.15          # seconds of each engine segment's fade-in and fade-out (the overlap)
ENGINE_PERIOD = 0.5         # seconds from one segment's start to the next at pitch 1 (= 10 ticks)
VEHICLES = ("police_car", "light_truck", "golf_cart")
# more vehicles' sounds, each module with NAMES [(vehicle, kind)] and clips() -> {(vehicle, kind): (samples, ogg quality)}:
# written and listed in sounds.json with these (sounds_farm.py: the ox cart, the power tiller, the tractor)
EXTRA = ("sounds_farm",)


def _extra():
    import importlib
    return [importlib.import_module(m) for m in EXTRA]


# ----------------------------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------------------------

def t_axis(seconds):
    return np.arange(int(round(seconds * SR))) / SR


def smooth_noise(rng, n, cutoff_hz):
    """Gaussian noise low-passed to cutoff_hz (a slow random wobble), unit RMS."""
    x = rng.standard_normal(n)
    x = eq(x, lambda f: 1.0 / (1.0 + (f / cutoff_hz) ** 4))
    return x / (np.sqrt(np.mean(x ** 2)) + 1e-12)


def eq(x, gain):
    """Filters x by a zero-phase gain curve gain(freq_hz) (FFT; fine for clips this short)."""
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    return np.fft.irfft(spec * gain(f), len(x))


def peak(f, centre, width, boost):
    """A resonance: 1 + boost at centre, falling off over about `width` Hz either side."""
    return 1 + boost * np.exp(-((f - centre) / width) ** 2)


def lowpass(f, cutoff, order=2):
    return 1.0 / np.sqrt(1.0 + (f / cutoff) ** (2 * order))


def highpass(f, cutoff, order=2):
    return 1.0 / np.sqrt(1.0 + (cutoff / np.maximum(f, 1e-3)) ** (2 * order))


def harmonics(phase, f0_max, rolloff, odd_boost=0.0):
    """Sum of sines n*phase with 1/n^rolloff amplitudes, up to Nyquist for the highest f0."""
    out = np.zeros_like(phase)
    n = 1
    while n * f0_max < SR / 2 * 0.9:
        a = 1.0 / n ** rolloff
        if n % 2 == 1:
            a *= 1 + odd_boost
        out += a * np.sin(n * phase)
        n += 1
    return out


def normalize(x, level):
    return x * (level / (np.max(np.abs(x)) + 1e-12))


# ----------------------------------------------------------------------------------------------
# Horns
# ----------------------------------------------------------------------------------------------

# tones (Hz), how bright (harmonic roll-off: lower = buzzier), drive (saturation), the bell's resonance (Hz, boost)
HORNS = {
    # a mid two-tone car horn: a major third apart, buzzy, with the bright bell of a disc horn
    "police_car": dict(tones=(415.0, 523.0), rolloff=1.05, drive=2.2, formant=(2300.0, 900.0, 1.6), top=6500, seed=11),
    # a low two-tone truck horn: deeper, heavier, a darker bell
    "light_truck": dict(tones=(277.0, 349.0), rolloff=0.95, drive=2.6, formant=(1300.0, 600.0, 1.8), top=4500, seed=12),
    # a small electric beep: one high tone, nearly pure, thin
    "golf_cart": dict(tones=(1245.0,), rolloff=2.2, drive=1.2, formant=(2500.0, 700.0, 0.4), top=7000, seed=13),
}


def horn_tone(spec, seconds, start_sag=0.0, end_drop=0.0):
    """
    The horn's tone for `seconds`: each pitch a buzzing harmonic series, saturated and shaped by the bell. start_sag:
    starts this much flat and settles within ~30 ms (a diaphragm getting going); end_drop: sinks this much by the end.
    """
    rng = np.random.default_rng(spec["seed"])
    t = t_axis(seconds)
    n = len(t)
    out = np.zeros(n)
    for i, f0 in enumerate(spec["tones"]):
        # a tiny random drift (0.15%) keeps it from sounding like a test tone
        drift = 1 + 0.0015 * smooth_noise(rng, n, 4.0)
        bend = 1 - start_sag * np.exp(-t / 0.03) - end_drop * (t / max(seconds, 1e-6)) ** 1.5
        freq = f0 * drift * bend
        phase = 2 * np.pi * np.cumsum(freq) / SR + rng.uniform(0, 2 * np.pi)
        voice = harmonics(phase, f0 * 1.02, spec["rolloff"], odd_boost=0.3)
        out += voice * (1.0 if i == 0 else 0.85)
    out /= np.max(np.abs(out)) + 1e-12
    out = np.tanh(spec["drive"] * out)                       # the diaphragm hitting its stops: buzz
    centre, width, boost = spec["formant"]
    out = eq(out, lambda f: peak(f, centre, width, boost) * lowpass(f, spec["top"], 3) * highpass(f, 150, 2))
    out += 0.004 * eq(rng.standard_normal(n), lambda f: peak(f, centre, width * 2, 1) * lowpass(f, 5000))   # a little air
    return out


HORN_RMS = 0.24             # how loud the steady tone is in the files (the cart's near-pure beep is not louder than the others)


def horn_gain(spec):
    """One gain for a vehicle's hold clip and tail: the steady tone at HORN_RMS, its peaks under 0.75 (room for the encoder)."""
    ref = horn_tone(spec, 0.5)
    return min(HORN_RMS / np.sqrt(np.mean(ref ** 2)), 0.75 / np.max(np.abs(ref)))


def horn_hold(spec):
    """Attack + HORN_HOLD s steady + 60 ms fade (the server stops it on release; the fade is for a hold to the end)."""
    seconds = 0.03 + HORN_HOLD + 0.06
    x = horn_tone(spec, seconds, start_sag=0.04)
    t = t_axis(seconds)
    env = 1 - np.exp(-t / 0.007)                               # ~20 ms to full
    env *= np.clip((seconds - t) / 0.06, 0, 1)
    return x * env * horn_gain(spec)


def horn_tail(spec):
    """The release: from full level dying away over ~0.2 s, sinking 3% in pitch; 3 ms in so it starts without a click."""
    x = horn_tone(spec, HORN_TAIL, end_drop=0.03)
    t = t_axis(HORN_TAIL)
    env = np.exp(-t / 0.045) * np.clip(t / 0.003, 0, 1)
    env *= np.clip((HORN_TAIL - t) / 0.02, 0, 1)
    return x * env * horn_gain(spec)


# ----------------------------------------------------------------------------------------------
# Engines
# ----------------------------------------------------------------------------------------------

def engine_segment(body):
    """Wraps a steady sound into a segment: equal-power fade-in and fade-out of ENGINE_FADE each."""
    seconds = ENGINE_PERIOD + ENGINE_FADE
    t = t_axis(seconds)
    x = body(len(t), t)
    fade_in = np.sin(0.5 * np.pi * np.clip(t / ENGINE_FADE, 0, 1))
    fade_out = np.cos(0.5 * np.pi * np.clip((t - (seconds - ENGINE_FADE)) / ENGINE_FADE, 0, 1))
    return x * fade_in * fade_out


def resonant_noise(rng, n, partials, floor=0.0, floor_cutoff=300.0):
    """
    Noise through narrow resonances: (centre Hz, bandwidth Hz, level) each. A resonance this narrow sounds like a tone
    that wobbles a little in loudness and pitch, like a running engine, and holds no fixed phase for longer than about
    1/bandwidth: two segments overlapping at a seam add up like two different engines, never cancelling or doubling.
    floor: a broad low rumble under it (low-passed noise) at this level.
    """
    def gain(f):
        g = np.zeros_like(f)
        for centre, bw, level in partials:
            g += level / (1 + ((f - centre) / (bw / 2)) ** 2)
        if floor > 0:
            g += floor * lowpass(f, floor_cutoff, 2) * highpass(f, 30, 2)
        return g
    x = eq(rng.standard_normal(n), gain)
    return x / (np.sqrt(np.mean(x ** 2)) + 1e-12)


def drifting_pulses(rng, n, t, rate, drift, sharpness):
    """A pulse train at `rate` Hz whose speed wanders by `drift` (so its beats never line up from one segment to the next)."""
    freq = rate * (1 + drift * smooth_noise(rng, n, 1.5))
    phase = 2 * np.pi * np.cumsum(freq) / SR + rng.uniform(0, 2 * np.pi)
    return np.exp(sharpness * (np.cos(phase) - 1))


def combustion(n, t, rng, f0, count, rolloff, bandwidth, floor, warmth, clatter=0.0):
    """
    A soft engine hum: the firing frequency f0 and its harmonics as narrow resonances (the hum and its rumble), a broad
    low rumble under them and, for a diesel, a light clatter: bright noise ticking at the firing rate. Low-passed so it
    stays a background sound.
    """
    partials = [(f0 * k, bandwidth * (1 + 0.15 * k), 1.0 / k ** rolloff) for k in range(1, count + 1)]
    out = resonant_noise(rng, n, partials, floor=floor)
    if clatter > 0:
        ticks = drifting_pulses(rng, n, t, f0, 0.05, 12.0)
        bright = eq(rng.standard_normal(n), lambda f: peak(f, 1900, 600, 1.0) * highpass(f, 800, 2) * lowpass(f, 3400, 2))
        bright /= np.sqrt(np.mean(bright ** 2)) + 1e-12
        out += clatter * ticks * bright
    return eq(out, lambda f: lowpass(f, warmth, 2) * highpass(f, 28, 2))


def police_engine(n, t):
    """A petrol car: a smooth hum on 52 Hz and its harmonics."""
    rng = np.random.default_rng(21)
    return combustion(n, t, rng, f0=52.0, count=10, rolloff=1.1, bandwidth=7.0, floor=0.25, warmth=750)


def truck_engine(n, t):
    """A small diesel: lower (38 Hz), rougher (wider resonances, more rumble) and a light clatter on top."""
    rng = np.random.default_rng(22)
    return combustion(n, t, rng, f0=38.0, count=16, rolloff=0.85, bandwidth=10.0, floor=0.45, warmth=950, clatter=0.35)


def drifting_tone(rng, n, partials, wobble=0.006, rate=3.0):
    """
    Steady sines (Hz, level) whose pitch each wanders by `wobble` on its own: an even loudness (unlike narrow noise,
    which swells and fades), and phases that drift apart within a segment, so at a seam the partials overlap at
    unrelated phases and never all cancel or all double at once.
    """
    out = np.zeros(n)
    for f0, level in partials:
        f = f0 * (1 + wobble * smooth_noise(rng, n, rate))
        out += level * np.sin(2 * np.pi * np.cumsum(f) / SR + rng.uniform(0, 2 * np.pi))
    return out


def cart_engine(n, t):
    """An electric motor: a soft whine on 560 Hz with its harmonics, a faint hum below, a breath of hiss."""
    rng = np.random.default_rng(24)   # the seed whose seams are evenest across the pitch range (a scan of the loudness over a period of re-triggered segments, pitch 0.55 to 1.8)
    whine = drifting_tone(rng, n, [(560.0, 1.0), (1120.0, 0.55), (1680.0, 0.35), (2240.0, 0.22), (100.0, 0.25), (200.0, 0.15)])
    hiss = eq(rng.standard_normal(n), lambda f: peak(f, 3200, 800, 1.0) * highpass(f, 1500, 2) * lowpass(f, 5000, 2))
    whine = eq(whine, lambda f: lowpass(f, 2600, 2))
    return whine / np.sqrt(np.mean(whine ** 2)) + 0.04 * hiss / (np.sqrt(np.mean(hiss ** 2)) + 1e-12)


ENGINES = {"police_car": police_engine, "light_truck": truck_engine, "golf_cart": cart_engine}
# how loud each segment is in the file (the server scales it further: vehicles.yml engine volume, config engine.volume)
ENGINE_LEVEL = {"police_car": 0.8, "light_truck": 0.85, "golf_cart": 0.6}
# a vehicle whose horn is not a horn (the bicycle's bell): its own hold clip and tail
HOLDS, TAILS = {}, {}


# ----------------------------------------------------------------------------------------------
# Road vehicles of the transport ladder (vehicles_road.yml): 짐자전거, 배달 오토바이, 사륜 오토바이, 소형 화물 밴, 탑차
# ----------------------------------------------------------------------------------------------

ROAD = ("cargo_bicycle", "delivery_scooter", "atv", "cargo_van", "box_truck")
VEHICLES = VEHICLES + ROAD

HORNS.update({
    # the bicycle's bell: a small brass dome (inharmonic partials), struck once and then trilled: "따릉"
    "cargo_bicycle": dict(f0=2350.0, seed=31),
    # a small motorbike horn: one buzzy high tone, "빽"
    "delivery_scooter": dict(tones=(480.0,), rolloff=0.85, drive=3.0, formant=(2000.0, 700.0, 1.2), top=6000, seed=32),
    # the ATV: a short two-tone, between the scooter and the van
    "atv": dict(tones=(392.0, 466.0), rolloff=1.0, drive=2.4, formant=(1900.0, 700.0, 1.4), top=5500, seed=33),
    # the van: a bright car horn, a fourth apart
    "cargo_van": dict(tones=(440.0, 587.0), rolloff=1.1, drive=2.0, formant=(2500.0, 900.0, 1.4), top=6500, seed=34),
    # the box truck: deeper and heavier than the 1-ton truck, a dark wide bell
    "box_truck": dict(tones=(208.0, 262.0), rolloff=0.9, drive=2.8, formant=(1000.0, 450.0, 2.0), top=4000, seed=35),
})

# a bicycle bell's partials: (ratio to the strike tone, level, seconds to fade by 1/e); two copies a hair apart beat slowly
BELL_PARTIALS = ((1.0, 1.0, 0.55), (2.32, 0.45, 0.30), (4.25, 0.22, 0.16), (6.63, 0.10, 0.09))
BELL_PATTERN = 0.95          # seconds from one "따릉" to the next while the bell lever is worked


def bell_strikes(spec, seconds, strikes):
    """The bell ringing: (time, strength) strikes, each setting every partial ringing and fading on its own."""
    rng = np.random.default_rng(spec["seed"])
    t = t_axis(seconds)
    out = np.zeros(len(t))
    for at, strength in strikes:
        dt = t - at
        on = dt >= 0
        for ratio, level, decay in BELL_PARTIALS:
            f = spec["f0"] * ratio
            for detune in (1.0, 1.0035):
                ph = rng.uniform(0, 2 * np.pi)
                out[on] += 0.5 * strength * level * np.exp(-dt[on] / decay) * np.sin(2 * np.pi * f * detune * dt[on] + ph)
        # the click of the hammer on the dome
        n = int(0.004 * SR)
        i = int(at * SR)
        if i < len(t):
            k = min(n, len(t) - i)
            out[i:i + k] += 0.25 * strength * rng.standard_normal(k) * np.linspace(1, 0, k)
    return eq(out, lambda f: highpass(f, 900, 2) * lowpass(f, 9000, 2))


def bell_hold(spec):
    """The bell held (the lever worked over and over): a strike "따" and a quick trill "릉", again every BELL_PATTERN
    seconds for HORN_HOLD seconds; the server stops it on release."""
    seconds = 0.03 + HORN_HOLD + 0.06
    strikes = []
    at = 0.0
    while at < seconds - 0.1:
        strikes.append((at, 1.0))
        strikes += [(at + 0.11 + k * 0.042, 0.42 - 0.04 * k) for k in range(6)]
        at += BELL_PATTERN
    x = bell_strikes(spec, seconds, strikes)
    t = t_axis(seconds)
    x *= np.clip((seconds - t) / 0.06, 0, 1)
    return x * (0.7 / (np.max(np.abs(x)) + 1e-12))


def bell_tail(spec):
    """The release: the dome still ringing out from the last strike, fading over 0.3 s."""
    seconds = 0.3
    x = bell_strikes(spec, seconds, [(0.0, 0.55)])
    t = t_axis(seconds)
    x *= np.clip(t / 0.003, 0, 1) * np.clip((seconds - t) / 0.03, 0, 1)
    return x * (0.45 / (np.max(np.abs(x)) + 1e-12))


HOLDS["cargo_bicycle"] = bell_hold
TAILS["cargo_bicycle"] = bell_tail


def bicycle_chain(n, t):
    """The bicycle has no engine: a soft chain tick (the freewheel's pawls, about 14 a second at pitch 1, a little
    uneven) over a faint hiss of tyres on the road."""
    rng = np.random.default_rng(41)
    out = np.zeros(n)
    at = rng.uniform(0, 0.03)
    while at < t[-1]:
        i = int(at * SR)
        k = min(int(0.006 * SR), n - i)
        click = rng.standard_normal(k) * np.exp(-np.arange(k) / (0.0012 * SR))
        out[i:i + k] += click * rng.uniform(0.7, 1.0)
        at += 1 / 14 * rng.uniform(0.85, 1.15)
    out = eq(out, lambda f: peak(f, 4200, 1500, 1.0) * highpass(f, 1800, 2) * lowpass(f, 8000, 2))
    out /= np.max(np.abs(out)) + 1e-12
    hiss = eq(rng.standard_normal(n), lambda f: lowpass(f, 1200, 2) * highpass(f, 150, 2))
    return out + 0.08 * hiss / (np.sqrt(np.mean(hiss ** 2)) + 1e-12)


def scooter_engine(n, t):
    """A small two-stroke: a buzzy ring on 85 Hz, bright harmonics, a light rasp."""
    rng = np.random.default_rng(42)
    return combustion(n, t, rng, f0=85.0, count=20, rolloff=0.65, bandwidth=9.0, floor=0.2, warmth=2600, clatter=0.25)


def atv_engine(n, t):
    """A single-cylinder four-stroke: a low thump (28 Hz), its harmonics up into a rough mid, a little clatter."""
    rng = np.random.default_rng(43)
    return combustion(n, t, rng, f0=28.0, count=22, rolloff=0.75, bandwidth=6.0, floor=0.5, warmth=1200, clatter=0.2)


def van_engine(n, t):
    """A small petrol van: a little higher and thinner than the patrol car."""
    rng = np.random.default_rng(44)
    return combustion(n, t, rng, f0=58.0, count=10, rolloff=1.0, bandwidth=7.0, floor=0.3, warmth=900)


def box_truck_engine(n, t):
    """A bigger diesel: lower than the 1-ton truck (30 Hz), more rumble and clatter."""
    rng = np.random.default_rng(45)
    return combustion(n, t, rng, f0=30.0, count=18, rolloff=0.8, bandwidth=9.0, floor=0.55, warmth=900, clatter=0.45)


ENGINES.update({"cargo_bicycle": bicycle_chain, "delivery_scooter": scooter_engine, "atv": atv_engine,
                "cargo_van": van_engine, "box_truck": box_truck_engine})
ENGINE_LEVEL.update({"cargo_bicycle": 0.5, "delivery_scooter": 0.75, "atv": 0.8, "cargo_van": 0.8, "box_truck": 0.85})


# ----------------------------------------------------------------------------------------------
# Writing
# ----------------------------------------------------------------------------------------------

def encode(samples, path: Path, quality):
    """float samples -> 16-bit WAV in a temp dir -> Ogg Vorbis (bit-exact: the same input gives the same file)."""
    ffmpeg = shutil.which("ffmpeg") or "/usr/bin/ffmpeg"
    pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2")
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "in.wav"
        import wave
        with wave.open(str(wav), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        path.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([ffmpeg, "-loglevel", "error", "-y", "-i", str(wav), "-map_metadata", "-1", "-fflags", "+bitexact",
                        "-flags:a", "+bitexact", "-c:a", "libvorbis", "-q:a", str(quality), "-ac", "1", "-ar", str(SR), str(path)],
                       check=True)


def sounds_json():
    """Every vehicle sound event: stsv:vehicle.<v>.horn / horn_end / engine -> stsv:vehicle/<v>_<kind>."""
    out = {}
    for v in VEHICLES:
        for kind in ("horn", "horn_end", "engine"):
            out[f"vehicle.{v}.{kind}"] = {"sounds": [{"name": f"stsv:vehicle/{v}_{kind}"}]}
    for mod in _extra():
        for v, kind in mod.NAMES:
            out[f"vehicle.{v}.{kind}"] = {"sounds": [{"name": f"stsv:vehicle/{v}_{kind}"}]}
    return out


def write():
    """Writes every clip and sounds.json; returns [(file, bytes)]."""
    written = []
    for v in VEHICLES:
        clips = {
            "horn": (HOLDS.get(v, horn_hold)(HORNS[v]), 1),
            "horn_end": (TAILS.get(v, horn_tail)(HORNS[v]), 1),
            "engine": (normalize(engine_segment(ENGINES[v]), ENGINE_LEVEL[v]), 0),
        }
        for kind, (samples, quality) in clips.items():
            path = OUT / f"{v}_{kind}.ogg"
            encode(samples, path, quality)
            written.append((path, path.stat().st_size))
    for mod in _extra():
        for (v, kind), (samples, quality) in mod.clips().items():
            path = OUT / f"{v}_{kind}.ogg"
            encode(samples, path, quality)
            written.append((path, path.stat().st_size))
    # nothing else of ours under sounds/vehicle: drop clips of vehicles or kinds that no longer exist
    keep = {p for p, _ in written}
    for stale in OUT.glob("*.ogg"):
        if stale not in keep:
            stale.unlink()
    SOUNDS_JSON.write_text(json.dumps(sounds_json(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return written


def main():
    written = write()
    for path, size in written:
        print(f"sound   {path.relative_to(REPO)}  {size / 1024:.1f} KB")
    print(f"sound   {SOUNDS_JSON.relative_to(REPO)} ({len(sounds_json())} events)")


if __name__ == "__main__":
    sys.exit(main())
