"""Runs stock Whisper small (the model family the app uses today) on clips, for comparison.

usage: whisper_baseline.py <language code> clip.wav...
Clips must be 16 kHz, mono, 16 bit PCM WAV files.
"""
import sys
import wave

import numpy as np
from faster_whisper import WhisperModel

model = WhisperModel("small", device="cpu", compute_type="int8")
language = sys.argv[1]
for clip in sys.argv[2:]:
    with wave.open(clip, "rb") as wav:
        audio = np.frombuffer(wav.readframes(wav.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    segments, _ = model.transcribe(audio, language=language, beam_size=1)
    print("BASELINE\t%s\t%s" % (clip, " ".join(s.text.strip() for s in segments)), flush=True)
