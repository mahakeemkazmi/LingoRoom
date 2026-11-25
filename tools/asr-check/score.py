"""Scores speech recognition output against reference transcripts.

Reads lines of the form
    REFERENCE<TAB>clip<TAB>text
    RESULT<TAB>clip<TAB>info<TAB>text      (IndicConformer, from AsrCheck)
    BASELINE<TAB>clip<TAB>text             (Whisper small)
from the files given as arguments, and prints word and character error rates.
Diacritics are ignored. Spacing differences still count as word errors, so the
word error rate is a strict upper bound.
"""
import sys
import unicodedata


def norm(text):
    text = "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.combining(c))
    text = "".join(c if (c.isalnum() or c.isspace()) else " " for c in text)
    return text.lower().split()


def edits(a, b):
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        prev, row[0] = row[:], i
        for j, y in enumerate(b, 1):
            row[j] = min(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + (x != y))
    return row[-1]


refs, systems = {}, {"RESULT": {}, "BASELINE": {}}
for path in sys.argv[1:]:
    for line in open(path, encoding="utf-8", errors="replace"):
        parts = line.rstrip("\n").split("\t")
        if parts[0] == "REFERENCE" and len(parts) >= 3:
            refs[parts[1]] = parts[2]
        elif parts[0] == "RESULT" and len(parts) >= 4:
            systems["RESULT"][parts[1]] = parts[3]
        elif parts[0] == "BASELINE" and len(parts) >= 3:
            systems["BASELINE"][parts[1]] = parts[2]

names = {"RESULT": "IndicConformer", "BASELINE": "Whisper small"}
for group in sorted({clip.split("/")[1] for clip in refs if "/" in clip}):
    for key, outputs in systems.items():
        word_errors = words = char_errors = chars = clips = 0
        for clip, ref in refs.items():
            if clip.split("/")[1] != group or clip not in outputs:
                continue
            r, h = norm(ref), norm(outputs[clip])
            word_errors += edits(r, h)
            words += len(r)
            char_errors += edits(list("".join(r)), list("".join(h)))
            chars += len("".join(r))
            clips += 1
        if words:
            print("SCORE\t%s\t%s\tclips=%d\twords=%d\tWER=%.1f%%\tCER=%.1f%%" % (
                group, names[key], clips, words, 100.0 * word_errors / words, 100.0 * char_errors / chars))
