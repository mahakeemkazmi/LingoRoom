"""Downloads real speech clips with reference transcripts from Google's FLEURS test set.

usage: fetch_fleurs.py <clips per language> <config>...      e.g. fetch_fleurs.py 25 ur_pk hi_in
Writes clips/<config>/<n>.wav (16 kHz mono 16 bit) and appends REFERENCE lines to refs.tsv.
"""
import io
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

import pyarrow.parquet as pq


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "lingo-room-ci"})
    with urllib.request.urlopen(request, timeout=300) as response:
        return response.read()


def parquet_urls(config):
    urls = []
    try:
        listing = json.loads(fetch("https://datasets-server.huggingface.co/parquet?dataset=google%2Ffleurs&config=" + config + "&split=test"))
        urls += [f["url"] for f in listing.get("parquet_files", []) if f.get("split") == "test"]
    except Exception as error:
        print("no parquet listing for %s: %s" % (config, error))
    urls.append("https://huggingface.co/datasets/google/fleurs/resolve/" + urllib.parse.quote("refs/convert/parquet", safe="") + "/" + config + "/test/0000.parquet")
    return urls


count = int(sys.argv[1])
for config in sys.argv[2:]:
    table = None
    for url in parquet_urls(config):
        try:
            data = fetch(url)
            table = pq.ParquetFile(io.BytesIO(data)).read_row_group(0)
            break
        except Exception as error:
            print("cannot read %s: %s" % (url, error))
    if table is None:
        print("no clips for " + config)
        continue
    rows = table.slice(0, count).to_pylist()
    os.makedirs("clips/" + config, exist_ok=True)
    written = 0
    with open("refs.tsv", "a", encoding="utf-8") as refs:
        for index, row in enumerate(rows):
            text = row.get("transcription") or row.get("raw_transcription") or ""
            audio = (row.get("audio") or {}).get("bytes")
            if not audio or not text:
                continue
            source = "clips/%s/%d.src" % (config, index)
            target = "clips/%s/%d.wav" % (config, index)
            with open(source, "wb") as handle:
                handle.write(audio)
            done = subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", source, "-ar", "16000", "-ac", "1", "-sample_fmt", "s16", target])
            os.remove(source)
            if done.returncode == 0:
                refs.write("REFERENCE\t%s\t%s\n" % (target, " ".join(text.split())))
                written += 1
    print("%s: %d clips" % (config, written))
