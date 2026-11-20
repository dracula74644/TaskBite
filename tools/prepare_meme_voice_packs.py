#!/usr/bin/env python3
"""Prepare reviewed short human dialogue excerpts for the preloaded meme packs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import wave

ROOT = Path(__file__).resolve().parents[1]


def prepare(cache=None):
    spec = json.loads((ROOT / "voice-pack/meme-sources.json").read_text())
    destination = ROOT / "app/src/main/assets/voices"
    for pack in spec["packs"]:
        if (destination / pack["id"]).exists():
            raise ValueError("Pack already exists; preserve it before preparing a replacement.")
    with tempfile.TemporaryDirectory(prefix="bite-memes-") as folder:
        stage = Path(folder)
        audio = stage / "audio"
        audio.mkdir()
        for name, clip in spec["clips"].items():
            assert re.fullmatch(r"[a-z0-9_]+", name)
            assert clip["audio"].startswith("https://www.myinstants.com/media/sounds/")
            original = stage / (name + ".mp3")
            cached = cache / (name + ".mp3") if cache else None
            if cached and cached.is_file():
                shutil.copyfile(cached, original)
            else:
                subprocess.run(["curl", "-fsSL", "--max-time", "30", "--max-filesize", "5242880",
                                clip["audio"], "-o", str(original)], check=True)
            if hashlib.sha256(original.read_bytes()).hexdigest() != clip["sourceSha256"]:
                raise ValueError(f"{name}: source changed; review its audio and caption again.")
            target = audio / (name + ".wav")
            assert 0 <= clip["start"] < clip["end"] <= 30
            subprocess.run(["ffmpeg", "-v", "error", "-i", str(original), "-ss", str(clip["start"]),
                            "-t", str(clip["end"] - clip["start"]), "-af", "loudnorm=I=-18:TP=-2:LRA=7",
                            "-ar", "44100", "-ac", "1", "-c:a", "pcm_s16le", "-map_metadata", "-1", str(target)], check=True)
            with wave.open(str(target)) as pcm:
                assert pcm.getnchannels() == 1 and pcm.getsampwidth() == 2 and pcm.getnframes() > 0
        for pack in spec["packs"]:
            out = stage / pack["id"]
            out.mkdir()
            categories = {}
            used = set()
            for kind, names in pack["categories"].items():
                assert 1 <= len(names) <= 5 and len(names) == len(set(names))
                categories[kind] = []
                for name in names:
                    clip = spec["clips"][name]
                    assert pack["explicit"] or not clip["explicit"]
                    target = out / (name + ".wav")
                    if name not in used:
                        shutil.copyfile(audio / target.name, target)
                    used.add(name)
                    categories[kind].append({"asset": f"voices/{pack['id']}/{target.name}",
                                            "line": clip["caption"], "source": clip["page"],
                                            "sha256": hashlib.sha256(target.read_bytes()).hexdigest()})
            catalog = {"source": "Existing human meme/dialogue clips from Myinstants",
                       "actors": [{"id": pack["id"], "name": pack["name"], "style": "CLASSIC",
                                   "description": pack["description"], "categories": categories}]}
            (out / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
            (out / "CREDITS.txt").write_text(pack["name"] + "\nPreloaded short dialogue excerpts requested by the user.\n"
                "Existing performances, trimmed to complete phrases and normalized to PCM16 mono. No generated speech.\n"
                "Original performances remain their owners' property. Sources are listed below; no endorsement is implied.\n\n"
                + "\n".join(f"{name}.wav | {spec['clips'][name]['page']} | excerpt {spec['clips'][name]['start']}–{spec['clips'][name]['end']} seconds" for name in sorted(used)) + "\n")
            destination.mkdir(parents=True, exist_ok=True)
            shutil.copytree(out, destination / pack["id"])
            print(f"{pack['name']}: {len(used)} clips, {len(categories)} situations")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, help="Previously downloaded and reviewed MP3s; hashes still verified")
    prepare(parser.parse_args().cache)
