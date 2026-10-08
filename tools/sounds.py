"""Synthesises Rackcraft's rocket sounds into assets/rackcraft/sounds/rocket/*.ogg.

Everything is built from noise, a few sine waves and a random generator with fixed seeds, so the output is the same
every run and there are no third-party recordings to license. Needs numpy and soundfile (whose bundled libsndfile
writes Ogg Vorbis):

    python3 -m venv /tmp/audio && /tmp/audio/bin/pip install numpy soundfile
    /tmp/audio/bin/python tools/sounds.py

Every sound is mono, so Minecraft plays it from a point in the world and fades it with distance.
"""
from pathlib import Path

import numpy as np
import soundfile

RATE = 32000
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/rackcraft/sounds/rocket"


def seconds(duration):
    return np.arange(int(duration * RATE)) / RATE


def lowpass(signal, cutoff):
    """One-pole low-pass. ``cutoff`` is in Hz, or an array of Hz per sample for a sweep."""
    cutoff = np.broadcast_to(np.asarray(cutoff, dtype=float), signal.shape)
    alpha = 1 - np.exp(-2 * np.pi * cutoff / RATE)
    out = np.empty_like(signal)
    state = 0.0
    for index in range(len(signal)):
        state += alpha[index] * (signal[index] - state)
        out[index] = state
    return out


def highpass(signal, cutoff):
    return signal - lowpass(signal, cutoff)


def band(signal, low, high):
    return lowpass(highpass(signal, low), high)


def brown(rng, count):
    """Brown noise: deep, rumbling, normalised."""
    walk = np.cumsum(rng.standard_normal(count))
    walk = highpass(walk, 8)
    return walk / (np.max(np.abs(walk)) + 1e-9)


def white(rng, count):
    return rng.standard_normal(count)


def envelope(t, attack, hold, release, total=None):
    """Rises over ``attack`` seconds, holds, then falls away over ``release``."""
    total = t[-1] if total is None else total
    rise = np.clip(t / max(attack, 1e-6), 0, 1)
    fall = np.clip((total - t) / max(release, 1e-6), 0, 1)
    shape = np.minimum(rise, fall)
    return shape * shape * (3 - 2 * shape)


def crackle(rng, count, rate, loudness=1.0):
    """The Saturn V crackle: sparse sharp pops, each a few milliseconds of bright noise, at random loudness."""
    out = np.zeros(count)
    pops = rng.poisson(rate * count / RATE)
    for _ in range(pops):
        start = rng.integers(0, count)
        length = int(RATE * rng.uniform(0.0015, 0.007))
        end = min(count, start + length)
        decay = np.exp(-np.arange(end - start) / (length * 0.25))
        out[start:end] += rng.standard_normal(end - start) * decay * rng.uniform(0.2, 1.0) ** 2
    return band(out, 600, 3500) * loudness


def thump(t, start, frequency, decay):
    """A sub-bass hit: a low sine falling in pitch, dying away."""
    local = np.clip(t - start, 0, None)
    pitch = frequency * (1 + 1.5 * np.exp(-local * 6))
    phase = 2 * np.pi * np.cumsum(pitch) / RATE
    return np.sin(phase) * np.exp(-local / decay) * (t >= start)


def roar(rng, count, body=1.0, rumble=1.0, crack=1.0, crackle_rate=180):
    """A rocket engine at full thrust: rumble, a broad roar, and the crackle on top."""
    t = np.arange(count) / RATE
    # Most speakers can't play much under 80 Hz, so the weight sits in the low roar; the sub is extra.
    deep = highpass(brown(rng, count), 35) * rumble
    deep /= np.max(np.abs(deep)) + 1e-9
    low = band(white(rng, count), 80, 320)
    low /= np.max(np.abs(low)) + 1e-9
    broad = band(white(rng, count), 250, 1400)
    broad /= np.max(np.abs(broad)) + 1e-9
    # The roar breathes: slow random swells.
    swell = 0.8 + 0.2 * lowpass(rng.standard_normal(count), 1.5) / 0.05
    swell = np.clip(swell, 0.55, 1.15)
    sub = np.sin(2 * np.pi * (34 + 4 * np.sin(2 * np.pi * 0.3 * t)) * t) * 0.35
    return (deep * 0.6 + low * 1.6 * body + broad * 0.9 * body + sub) * swell + crackle(rng, count, crackle_rate, 1.6 * crack)


def master(signal, drive=1.6, peak=0.85):
    """Soft-clip for weight, then normalise."""
    signal = np.tanh(signal / (np.max(np.abs(signal)) + 1e-9) * drive)
    return signal / (np.max(np.abs(signal)) + 1e-9) * peak


def ignition():
    """T-8 to T-0: igniters pop, the turbopumps wind up, and the roar builds until the clamps let go."""
    rng = np.random.default_rng(101)
    t = seconds(6.0)
    count = len(t)
    pops = crackle(rng, count, 40, 3.0) * np.clip(1.6 - t, 0, 1)
    whine_pitch = 180 + 820 * np.clip(t / 5.0, 0, 1) ** 1.5
    whine = np.sin(2 * np.pi * np.cumsum(whine_pitch) / RATE) * 0.12 * envelope(t, 1.0, 0, 1.0)
    build = roar(rng, count, body=0.9, rumble=0.9, crack=0.6, crackle_rate=90) * (np.clip(t / 5.5, 0, 1) ** 2)
    return master(pops + whine + build, drive=1.3)


def liftoff():
    """Liftoff: one enormous hit as the clamps release, then full thrust, slowly drawing away."""
    rng = np.random.default_rng(202)
    t = seconds(12.0)
    count = len(t)
    boom = band(white(rng, count), 60, 500)
    hit = thump(t, 0.0, 32, 1.8) * 1.2 + boom / (np.max(np.abs(boom)) + 1e-9) * np.exp(-t / 0.6) * 1.8
    engine = roar(rng, count, body=1.0, rumble=1.3, crack=1.4, crackle_rate=260) * envelope(t, 0.4, 0, 4.0)
    return master(hit + engine, drive=2.0)


def thrust():
    """A few seconds of full thrust, faded at both ends so overlapping copies blend into one long roar."""
    rng = np.random.default_rng(303)
    t = seconds(5.0)
    return master(roar(rng, len(t), body=1.0, rumble=1.2, crack=1.2, crackle_rate=220) * envelope(t, 0.7, 0, 0.7), drive=1.8, peak=0.85)


def staging():
    """Stage separation: the pyrotechnic bang, the ring of the interstage, and the next engine lighting with a whoosh."""
    rng = np.random.default_rng(404)
    t = seconds(4.0)
    count = len(t)
    bang = highpass(white(rng, count), 120) * np.exp(-t / 0.06) * 1.6 + thump(t, 0.0, 45, 0.5)
    ring = sum(np.sin(2 * np.pi * f * t + p) * np.exp(-t / d) * a
               for f, p, d, a in ((410, 0.0, 0.9, 0.25), (1033, 1.1, 0.6, 0.15), (1771, 2.3, 0.4, 0.1), (2690, 0.4, 0.25, 0.07)))
    sweep = np.clip((t - 0.5) / 2.5, 0, 1)
    whoosh = lowpass(white(rng, count), 200 + 3500 * sweep) * envelope(t - 0.5, 0.8, 0, 1.2, total=3.5) * (t > 0.5)
    return master(bang + ring + whoosh * 1.4, drive=1.5)


def explosion():
    """A launch failure: the fireball, debris crackling, and a long rolling rumble."""
    rng = np.random.default_rng(505)
    t = seconds(9.0)
    count = len(t)
    boom = band(white(rng, count), 60, 400)
    blast = thump(t, 0.0, 30, 2.0) * 1.2 + boom / (np.max(np.abs(boom)) + 1e-9) * np.exp(-t / 0.8) * 2.2 \
        + lowpass(white(rng, count), 3000) * np.exp(-t / 0.25) * 0.8
    debris = crackle(rng, count, 120, 3.0) * np.exp(-t / 2.5)
    rolling = band(white(rng, count), 50, 250)
    rumble = (highpass(brown(rng, count), 35) * 0.5 + rolling / (np.max(np.abs(rolling)) + 1e-9)) * envelope(t, 0.2, 0, 6.0) * 1.2
    return master(blast + debris + rumble, drive=2.2)


def distant():
    """What the rest of the base hears: thunder-like rumble with the crackle softened by distance."""
    rng = np.random.default_rng(606)
    t = seconds(10.0)
    count = len(t)
    body = lowpass(roar(rng, count, body=1.0, rumble=1.0, crack=0.6, crackle_rate=200), 420)
    return master(body * envelope(t, 1.5, 0, 4.0), drive=1.4, peak=0.8)


def beep(frequency, length, harmonics=((1, 1.0), (2, 0.25), (3, 0.08))):
    """A mission-control countdown tone."""
    t = seconds(length)
    tone = sum(np.sin(2 * np.pi * frequency * n * t) * a for n, a in harmonics)
    shape = np.clip(t / 0.005, 0, 1) * np.clip((length - t) / 0.04, 0, 1)
    return master(tone * shape, drive=1.0, peak=0.7)


SOUNDS = {
    "ignition": ignition,
    "liftoff": liftoff,
    "thrust": thrust,
    "staging": staging,
    "explosion": explosion,
    "distant": distant,
    "beep": lambda: beep(1046.5, 0.18),
    "beep_final": lambda: beep(784.0, 0.9, ((1, 1.0), (1.5, 0.5), (2, 0.2))),
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, make in SOUNDS.items():
        audio = make().astype(np.float32)
        soundfile.write(OUT / f"{name}.ogg", audio, RATE, format="OGG", subtype="VORBIS")
        print(f"{name}.ogg: {len(audio) / RATE:.1f} s")


if __name__ == "__main__":
    main()
