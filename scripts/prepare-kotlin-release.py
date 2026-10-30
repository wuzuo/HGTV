#!/usr/bin/env python3
"""Package an existing APK with an updater manifest, using its actual metadata."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def prepare(apk, aapt, output, tag=None, notes=""):
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    package_line = next(line for line in badging.splitlines() if line.startswith("package:"))
    fields = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    package, version = fields["name"], fields["versionName"]
    code = int(fields["versionCode"])
    sdk = int(re.search(r"^sdkVersion:'(\d+)'", badging, re.M).group(1))
    if package != "io.github.tsukiyo_zero.hgtv" or not 1 <= code <= 2147483647:
        raise ValueError("APK package or versionCode is not supported")
    if not re.fullmatch(r"[0-9][0-9A-Za-z.+-]{0,63}", version):
        raise ValueError("Unsupported APK versionName")
    android = {23: "android6", 26: "android8"}.get(sdk)
    if not android:
        raise ValueError(f"Unsupported minSdk: {sdk}")
    if tag is None:
        base = re.match(r"\d+\.\d+\.\d+", version)
        if not base:
            raise ValueError("Specify --tag for a non-semantic versionName")
        tag = "kotlin-v" + base.group()
    if not re.fullmatch(r"kotlin-v[0-9][0-9A-Za-z.+-]{0,80}", tag):
        raise ValueError("Unsupported release tag")
    if len(notes) > 1500:
        raise ValueError("Update notes must not exceed 1500 characters")
    size = apk.stat().st_size
    if not 1 <= size <= 80 * 1024 * 1024:
        raise ValueError("APK exceeds updater size limit")
    suffix = "-" + android
    stem = version[:-len(suffix)] if version.endswith(suffix) else version
    name = f"hgtv-kotlin-{stem}-{android}.apk"
    manifest = dict(schema=1, packageName=package, versionCode=code, versionName=version,
                    minSdk=sdk, tag=tag, apk=dict(name=name, size=size,
                    sha256=hashlib.sha256(apk.read_bytes()).hexdigest()), notes=notes)
    destination = output / tag
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / name
    if apk.resolve() != target.resolve():
        shutil.copyfile(apk, target)
    temporary = destination / "update.json.tmp"
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, destination / "update.json")
    if notes:
        (destination / "release-notes.md").write_text(notes.strip() + "\n", encoding="utf-8")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--aapt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--tag")
    parser.add_argument("--notes-file", type=Path)
    args = parser.parse_args()
    notes = args.notes_file.read_text(encoding="utf-8").strip() if args.notes_file else ""
    print(prepare(args.apk, args.aapt, args.output, args.tag, notes))


if __name__ == "__main__":
    main()
