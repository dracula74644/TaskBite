#!/usr/bin/env python3
"""Prepare a verified APK and updater manifest locally. This does not publish a release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE = "6e77b320a3506b432abbb0f9bad694f5eee2755e0a235a23e76ec0d56f874984"


def prepare(apk, output, sdk, notes="", public=False):
    versions = sorted((sdk / "build-tools").iterdir(),
                      key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)), reverse=True)
    tools = next(p for p in versions if (p / "apksigner").is_file() and (p / "aapt2").is_file())
    signature = subprocess.run([str(tools / "apksigner"), "verify", "--print-certs", str(apk)],
                               check=True, capture_output=True, text=True).stdout
    if f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}" not in signature:
        raise ValueError("Use the original TaskBite release signing key.")
    badging = subprocess.run([str(tools / "aapt2"), "dump", "badging", str(apk)],
                             check=True, capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk_match = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    if not package or not sdk_match or package[1] != "com.dracula.taskbite":
        raise ValueError("Expected a readable TaskBite APK.")
    version_code, version = int(package[2]), package[3]
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", version) or not 0 < version_code <= 2147483647:
        raise ValueError("Invalid release version.")
    if "application-debuggable" in badging:
        raise ValueError("Publish a release build, not a debuggable APK.")
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError("Damaged APK archive.")
        personal_root = ROOT / "app/src/personal/assets"
        personal_paths = {"assets/" + p.relative_to(personal_root).as_posix()
                          for p in personal_root.rglob("*") if p.is_file()}
        personal_prefixes = tuple("assets/voices/" + name + "/" for name in
                                  ("high_on_life", "bite_original", "bite_sweet"))
        personal = any(n in personal_paths or n.startswith(personal_prefixes) for n in archive.namelist())
    if public and personal:
        raise ValueError("This APK contains the personal voice pack. Its CREDITS.txt excludes redistribution; prepare a public build with distributable assets first.")
    size = apk.stat().st_size
    if not 0 < size <= 128 * 1024 * 1024:
        raise ValueError("Updates must be under 128 MB.")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    name = f"TaskBite-{version}.apk"
    manifest = dict(formatVersion=1, packageName=package[1], versionCode=version_code,
                    versionName=version, minSdk=int(sdk_match[1]), apk=name, size=size,
                    sha256=digest, notes=notes[:4000])
    output.mkdir(parents=True, exist_ok=True)
    target = output / name
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        raise ValueError("The output folder already contains a different APK for this version.")
    if target.resolve() != apk.resolve():
        shutil.copy2(apk, target)
    (output / "taskbite-update.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (output / (name + ".sha256")).write_text(f"{digest}  {name}\n")
    print(f"Prepared {target} and {output / 'taskbite-update.json'}")
    print(f"Release tag: v{version}. Publish both files as assets of one stable release.")
    if personal:
        print("Personal voice assets are included. This package has not been approved for public redistribution.")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk"))))
    parser.add_argument("--notes", type=Path, help="UTF-8 release notes file")
    parser.add_argument("--public", action="store_true", help="Reject APKs containing personal voice assets")
    args = parser.parse_args()
    try:
        prepare(args.apk.resolve(), args.output.resolve(), args.sdk, args.notes.read_text() if args.notes else "", args.public)
    except (ValueError, OSError, subprocess.CalledProcessError, StopIteration) as error:
        parser.exit(1, f"Could not prepare the update: {error}\n")
