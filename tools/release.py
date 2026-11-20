#!/usr/bin/env python3
"""Test, sign and prepare a public update locally; publish only with --publish. Signing keys stay local."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

from prepare_update_release import prepare

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "dracula74644/TaskBite"
SDK = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))


def run(*args, capture=False):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, capture_output=capture)


def release(notes, publish=False):
    if publish:
        run("git", "diff", "--quiet", "HEAD")
        unpublished = run("git", "ls-files", "--others", "--exclude-standard", "app", "tools", "gradle", ".github", "docs", capture=True).stdout
        if unpublished.strip():
            raise ValueError("Commit the release source files before publishing.")
    run("./gradlew", "--no-daemon", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx768m",
        "-PincludePersonalVoices=false", "testDebugUnitTest", "lintDebug", "assembleRelease")
    source = ROOT / "app/build/outputs/apk/release/app-release.apk"
    with tempfile.TemporaryDirectory(prefix="bite-release-") as directory:
        staged = Path(directory)
        manifest = prepare(source, staged, SDK, notes.read_text(), public=True)
        destination = ROOT / "build/releases/public" / manifest["versionName"]
        prepare(source, destination, SDK, notes.read_text(), public=True)
    tag = "v" + manifest["versionName"]
    if not publish:
        print(f"Verified public assets: {destination}. Nothing uploaded.")
        return
    head = run("git", "rev-parse", "HEAD", capture=True).stdout.strip()
    remote = run("git", "ls-remote", "origin", "refs/heads/main", capture=True).stdout.split()
    if not remote or remote[0] != head:
        raise ValueError("Push this exact tested commit to origin/main before publishing.")
    previous = json.loads(run("gh", "release", "list", "--repo", REPOSITORY, "--limit", "100", "--json", "tagName", capture=True).stdout)
    if any(item["tagName"] == tag for item in previous):
        raise ValueError(f"Release {tag} already exists. Existing releases are never overwritten.")
    if run("git", "ls-remote", "origin", "refs/tags/" + tag, capture=True).stdout.strip():
        raise ValueError(f"Tag {tag} already exists. Use a new version for a new build.")
    assets = [destination / manifest["apk"], destination / "taskbite-update.json", destination / (manifest["apk"] + ".sha256")]
    run("gh", "release", "create", tag, *map(str, assets), "--repo", REPOSITORY, "--target", head,
        "--draft", "--title", f"Bite {manifest['versionName']}", "--notes-file", str(notes.resolve()))
    # A draft remains private if an upload/download verification fails.
    with tempfile.TemporaryDirectory(prefix="bite-upload-check-") as directory:
        run("gh", "release", "download", tag, "--repo", REPOSITORY, "--dir", directory)
        for asset in assets:
            downloaded = Path(directory) / asset.name
            if hashlib.sha256(downloaded.read_bytes()).digest() != hashlib.sha256(asset.read_bytes()).digest():
                raise ValueError("Uploaded assets failed verification. Release remains a draft.")
    run("gh", "release", "edit", tag, "--repo", REPOSITORY, "--draft=false", "--latest")
    print(f"Published https://github.com/{REPOSITORY}/releases/tag/{tag}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--notes", required=True, type=Path)
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    try:
        release(args.notes, args.publish)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release stopped: {error}\n")
