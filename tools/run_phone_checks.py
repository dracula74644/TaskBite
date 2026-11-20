#!/usr/bin/env python3
"""Build and run isolated phone tests. Does not install, clear, or uninstall the production Bite app."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
APP = "com.dracula.taskbite.devcheck"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB serial, needed when more than one phone is connected")
    args = parser.parse_args()
    adb = SDK / "platform-tools/adb"
    devices = subprocess.run([str(adb), "devices"], check=True, capture_output=True, text=True).stdout
    available = [line.split()[0] for line in devices.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == "device"]
    serial = args.serial or (available[0] if len(available) == 1 else None)
    if serial not in available:
        parser.error("Connect and authorize a USB-debugging phone first; use --serial if several are connected.")
    subprocess.run([str(ROOT / "gradlew"), "--no-daemon", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx768m",
                    "-PdeviceChecks=true", "-PincludePersonalVoices=false", "assembleDebug", "assembleDebugAndroidTest"], cwd=ROOT, check=True)
    tools = sorted((SDK / "build-tools").iterdir(), reverse=True)
    aapt = next(folder / "aapt2" for folder in tools if (folder / "aapt2").exists())
    apks = [(ROOT / "app/build/outputs/apk/debug/app-debug.apk", APP),
            (ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", APP + ".test")]
    for apk, expected in apks:
        metadata = subprocess.run([str(aapt), "dump", "badging", str(apk)], check=True, capture_output=True, text=True).stdout
        if f"package: name='{expected}'" not in metadata:
            raise RuntimeError("Refusing to install a non-isolated package.")
    for apk, _ in apks:
        subprocess.run([str(adb), "-s", serial, "install", "-r", str(apk)], check=True)
    result = subprocess.run([str(adb), "-s", serial, "shell", "am", "instrument", "-w", "-r",
                             APP + ".test/androidx.test.runner.AndroidJUnitRunner"], capture_output=True, text=True)
    report = ROOT / "build/reports/phone-checks/instrumentation.txt"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(result.stdout + result.stderr)
    print(result.stdout)
    print(f"Saved phone test results to {report}")
    if result.returncode or "FAILURES!!!" in result.stdout or "OK (" not in result.stdout:
        raise RuntimeError("Phone checks did not pass; inspect the report.")
    print("Automated phone checks passed. Complete the listening and lock-screen checks in docs/phone-testing.md.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(f"Phone checks stopped: {error}")
