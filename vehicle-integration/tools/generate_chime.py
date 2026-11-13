"""Generate the bundled, short two-note simulation warning chime."""

import math
import pathlib
import struct
import wave

OUT = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/res/raw/alert_chime.wav"
RATE = 22_050
NOTES = ((880.0, 0.20), (659.25, 0.28))

OUT.parent.mkdir(parents=True, exist_ok=True)
samples = []
for frequency, duration in NOTES:
    count = int(RATE * duration)
    for i in range(count):
        t = i / RATE
        envelope = min(1.0, t / 0.012) * math.exp(-7.0 * t / duration)
        value = 0.44 * envelope * math.sin(2 * math.pi * frequency * t)
        samples.append(int(value * 32767))
    samples.extend([0] * int(RATE * 0.045))

with wave.open(str(OUT), "wb") as audio:
    audio.setnchannels(1)
    audio.setsampwidth(2)
    audio.setframerate(RATE)
    audio.writeframes(struct.pack("<" + "h" * len(samples), *samples))
print(OUT)
