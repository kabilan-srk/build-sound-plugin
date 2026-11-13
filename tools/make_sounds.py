#!/usr/bin/env python3
"""Generates the two sounds that Build Sound ships: success.wav and problem.wav.

They are original: every sample is calculated here from sine waves, using only the Python standard library.
No recording and no third-party audio is used. The script has no randomness, so the same script always makes
the same bytes.

Usage (from the project folder):
    python3 tools/make_sounds.py                 # writes into src/main/webapp/sounds/
    python3 tools/make_sounds.py some/other/dir  # writes into a folder of your choice

Output: mono, 44100 Hz, 16-bit PCM, peak about -6 dBFS.
This script is a development tool. It is not part of the plugin and is not packaged into it.
"""
import math
import os
import struct
import sys
import wave

SAMPLE_RATE = 44100
PEAK = 10 ** (-6 / 20)  # the loudest sample is 6 dB below full scale, so the sound is never harsh


def add_note(buf, start, freq, dur, partials, decay, attack=0.008):
    """Mix one note into buf (a list of samples).

    partials is a list of (frequency ratio, amplitude, decay multiplier): the fundamental plus overtones.
    """
    first = int(start * SAMPLE_RATE)
    count = int(dur * SAMPLE_RATE)
    if len(buf) < first + count:
        buf.extend([0.0] * (first + count - len(buf)))
    for i in range(count):
        t = i / SAMPLE_RATE
        # Smooth fade-in and fade-out of every note, so there are no clicks.
        fade_in = math.sin(min(1.0, t / attack) * math.pi / 2) ** 2
        fade_out = math.sin(min(1.0, (dur - t) / 0.05) * math.pi / 2) ** 2
        value = 0.0
        for ratio, amp, decay_multiplier in partials:
            value += amp * math.exp(-t * decay * decay_multiplier) * math.sin(2 * math.pi * freq * ratio * t)
        buf[first + i] += value * fade_in * fade_out


def write_wav(path, buf):
    fade = int(0.04 * SAMPLE_RATE)  # a last safety fade-out over the final 40 ms
    for i in range(fade):
        buf[-1 - i] *= math.sin((i / fade) * math.pi / 2) ** 2
    scale = PEAK / max(abs(x) for x in buf)
    data = b"".join(struct.pack("<h", int(round(x * scale * 32767))) for x in buf)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with wave.open(path, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(data)


def hz(semitones_from_a4):
    """Frequency of a note in equal temperament, A4 = 440 Hz."""
    return 440.0 * 2 ** (semitones_from_a4 / 12)


G4, E4 = hz(-2), hz(-5)
G5, C6 = hz(10), hz(15)

# Success: a soft two-note chime, G5 then C6 (a rising fourth), about 0.9 seconds.
CHIME = [(1, 1.0, 1.0), (2, 0.35, 1.6), (3, 0.12, 2.4)]

# Problem: a low soft bell, G4 then E4 (a falling minor third), about 1.45 seconds.
# The overtones are not exact multiples (2.76, 5.4), which is what makes it sound like a bell.
BELL = [(1, 1.0, 1.0), (2.0, 0.5, 1.4), (2.76, 0.25, 2.0), (5.4, 0.08, 3.5)]


def make_success(path):
    buf = []
    add_note(buf, 0.00, G5, 0.45, CHIME, 5.0)
    add_note(buf, 0.16, C6, 0.70, CHIME, 4.5)
    write_wav(path, buf)


def make_problem(path):
    buf = []
    add_note(buf, 0.00, G4, 1.0, BELL, 3.0, attack=0.012)
    add_note(buf, 0.25, E4, 1.2, BELL, 2.8, attack=0.012)
    write_wav(path, buf)


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "src", "main", "webapp", "sounds")
    make_success(os.path.join(out, "success.wav"))
    make_problem(os.path.join(out, "problem.wav"))
