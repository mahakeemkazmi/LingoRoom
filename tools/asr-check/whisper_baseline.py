"""Runs stock Whisper small (the model family the app uses today) on clips, for comparison.

usage: whisper_baseline.py <language code> clip.wav...
"""
import sys

from faster_whisper import WhisperModel

model = WhisperModel("small", device="cpu", compute_type="int8")
language = sys.argv[1]
for clip in sys.argv[2:]:
    segments, _ = model.transcribe(clip, language=language, beam_size=1)
    print("BASELINE\t%s\t%s" % (clip, " ".join(s.text.strip() for s in segments)), flush=True)
