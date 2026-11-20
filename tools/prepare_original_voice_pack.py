#!/usr/bin/env python3
"""Package supplied human recordings. Never generates speech or placeholder audio."""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, tempfile, wave
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('recordings',type=Path)
parser.add_argument('--performer',required=True)
parser.add_argument('--style',choices=['classic','sweet'],default='classic')
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
script='sweet-recording-script.json' if args.style=='sweet' else 'recording-script.json'
spec=json.loads((root/'voice-pack'/script).read_text())
missing=[c['file'] for clips in spec['categories'].values() for c in clips if not (args.recordings/c['file']).is_file()]
if missing: parser.error('Missing human recordings: '+', '.join(missing))
pack_id='bite_sweet' if args.style=='sweet' else 'bite_original'
output=root/'app/src/personal/assets/voices'/pack_id
output.parent.mkdir(parents=True,exist_ok=True)
if output.exists(): parser.error('An original pack already exists. Preserve it before preparing a replacement.')
with tempfile.TemporaryDirectory(prefix='bite-original-') as tmp:
 stage=Path(tmp); categories={}
 for kind,clips in spec['categories'].items():
  categories[kind]=[]
  for i,clip in enumerate(clips):
   source=(args.recordings/clip['file']).resolve()
   if not source.is_relative_to(args.recordings.resolve()): parser.error('Recording path leaves its source directory.')
   meta=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','json',str(source)]))
   seconds=float(meta['format']['duration'])
   if not 0<seconds<=30: parser.error(f'{clip["file"]}: expected 1–30 seconds of recorded speech.')
   target=stage/f'{kind.lower()}_{i+1:02d}.wav'
   subprocess.run(['ffmpeg','-v','error','-y','-i',str(source),'-af','loudnorm=I=-18:TP=-2:LRA=7',
                   '-ar','44100','-ac','1','-c:a','pcm_s16le','-map_metadata','-1',str(target)],check=True)
   with wave.open(str(target)) as w:
    if w.getnframes()==0 or target.stat().st_size>6*1024*1024: parser.error('Invalid or oversized recording.')
   categories[kind].append({'asset':f'voices/{pack_id}/{target.name}','line':clip['line'],
                            'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
 catalog={'source':'User-supplied human recordings','actors':[{'id':pack_id,'name':spec['name'],
           'style':args.style.upper(),'description':f'Full roast lines · {args.performer}','categories':categories}]}
 (stage/'catalog.json').write_text(json.dumps(catalog,indent=2)+'\n')
 (stage/'CREDITS.txt').write_text(f"{spec['name']}\nHuman performer: {args.performer}\nOriginal TaskBite scripts. User-supplied performances, normalized and converted to mono PCM16 WAV.\n")
 shutil.copytree(stage,output)
count=sum(len(clips) for clips in spec['categories'].values())
print(f"Prepared {count} full recorded lines. Select {spec['name']} after rebuilding the personal APK.")
