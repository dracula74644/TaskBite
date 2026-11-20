#!/usr/bin/env python3
"""Build action-specific edits of CC BY human performances; no generated speech."""
from pathlib import Path
from zipfile import ZipFile
import hashlib, json, subprocess, sys, tempfile, wave
from voice_variety import extend_standard
root = Path(__file__).resolve().parents[1]
archive = Path(sys.argv[1])
actors = {'alex': 'Alex Brodie', 'sean': 'Sean Lenhart', 'meghan': 'Meghan Christian'}
# Use complete spoken phrases, with short pauses; never invent words in a performer's voice.
reactions = {
    'CREATED': [('Alright. Get ready.', ['confirmation_9', 'miscellaneous_8']),
                ('You got it. Let’s go!', ['confirmation_4', 'confirmation_7'])],
    'STARTED': [('3… 2… 1… Go!', ['miscellaneous_9']),
                ('Let’s go! Good luck.', ['confirmation_7', 'farewell_9'])],
    'COMPLETED': [('Objective complete. Wow!', ['completion_8', 'miscellaneous_10']),
                  ('All done. [Laughter]', ['completion_1', 'miscellaneous_12'])],
    'SNOOZE': [('[Sigh] No way.', ['miscellaneous_17', 'refusal_2']),
               ('Not happening. Let’s go!', ['refusal_9', 'confirmation_7'])],
    'OVERDUE': [('Hey! Get ready. Let’s go!', ['greeting_3', 'miscellaneous_8', 'confirmation_7']),
                ('[Clears throat] On it.', ['miscellaneous_13', 'confirmation_10'])],
    'ABANDON': [('Huh? Hello.', ['miscellaneous_3', 'greeting_1']),
                ('Welcome. Good luck.', ['greeting_6', 'farewell_9'])],
    'IDLE': [('[Clears throat] What’s up?', ['miscellaneous_13', 'greeting_10']),
             ('Hello. Get ready.', ['greeting_1', 'miscellaneous_8'])],
    'TINY': [('Hmm… Let’s go!', ['miscellaneous_2', 'confirmation_7']),
             ('Huh? On it.', ['miscellaneous_3', 'confirmation_10'])],
}
output = root / 'app/src/main/assets/voices'
output.mkdir(parents=True, exist_ok=True)
previous = json.loads((output / 'catalog.json').read_text()) if (output / 'catalog.json').exists() else {'actors': []}
manifest = {'source': 'https://dillonbecker.itch.io/sdap', 'producer': 'Dillon Becker',
            'license': 'https://creativecommons.org/licenses/by/4.0/',
            'archiveSha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
            'edits': 'Silence trimmed, complete phrases joined with 160 ms pauses, loudness normalized, mono PCM16 44.1 kHz.', 'actors': []}
with ZipFile(archive) as z, tempfile.TemporaryDirectory() as tmp:
    temp = Path(tmp)
    names = [n for n in z.namelist() if not n.startswith('__MACOSX/')]
    for actor, name in actors.items():
        sources, frames = {}, {}
        for part in sorted({part for variants in reactions.values() for _, parts in variants for part in parts}):
            filename = f'{part}_{actor}.wav'
            original = next(n for n in names if n.endswith('/' + filename))
            sources[part] = original
            source, trimmed = temp / filename, temp / f'trim-{filename}'
            source.write_bytes(z.read(original))
            trim = 'silenceremove=start_periods=1:start_duration=0.02:start_threshold=-45dB'
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(source), '-af', f'{trim},areverse,{trim},areverse',
                            '-ac', '1', '-ar', '44100', '-c:a', 'pcm_s16le', str(trimmed)], check=True)
            with wave.open(str(trimmed)) as w:
                frames[part] = w.readframes(w.getnframes())
                assert frames[part], original
        categories = {}
        for kind, variants in reactions.items():
            categories[kind] = []
            for i, (line, parts) in enumerate(variants):
                joined = temp / f'join-{actor}-{kind}-{i}.wav'
                with wave.open(str(joined), 'wb') as w:
                    w.setparams((1, 2, 44100, 0, 'NONE', 'not compressed'))
                    w.writeframes((b'\x00\x00' * 7056).join(frames[part] for part in parts))
                target = output / f'reaction_{kind.lower()}_{i}_{actor}.wav'
                subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(joined), '-af', 'loudnorm=I=-18:TP=-2:LRA=7',
                                '-ac', '1', '-ar', '44100', '-c:a', 'pcm_s16le', '-map_metadata', '-1', str(target)], check=True)
                categories[kind].append({'asset': 'voices/' + target.name, 'line': line,
                                         'originals': [sources[part] for part in parts],
                                         'sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
        manifest['actors'].append({'id': actor, 'name': name, 'description': 'Edited human reactions', 'categories': extend_standard(categories)})
(output / 'catalog.json').write_text(json.dumps(manifest, indent=2) + '\n')
used = {c['asset'] for a in manifest['actors'] for clips in a['categories'].values() for c in clips}
for actor in previous['actors']:
    for clips in actor['categories'].values():
        for clip in clips:
            path = root / 'app/src/main/assets' / clip['asset']
            if clip['asset'] not in used and path.resolve().parent == output.resolve():
                path.unlink(missing_ok=True)
print(f'Prepared {len(actors)} human voices with 16 action-specific edits each.')
