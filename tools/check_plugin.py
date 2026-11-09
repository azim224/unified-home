#!/usr/bin/env python3
"""Enforce the thin Unified Home distribution boundary; never publish SDK files."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

MAX_PACKAGE_BYTES = 5 * 1024 * 1024


def check_package(path):
    if path.stat().st_size > MAX_PACKAGE_BYTES:
        raise ValueError(f"{path}: plugin exceeds 5 MiB; inspect program inputs, do not bundle the SDK")
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) != 3 or {entry.filename for entry in entries} != {
            "platform.json", "package.sig", "classes.dex"
        }:
            raise ValueError(f"{path}: only platform.json, package.sig and classes.dex are allowed")
        if any(entry.file_size > MAX_PACKAGE_BYTES for entry in entries):
            raise ValueError(f"{path}: oversized uncompressed payload")
        manifest = json.loads(archive.read("platform.json"))
        dex = archive.read("classes.dex")
        if manifest["packageId"] != "org.amalia.unifiedhome" or manifest["runtime"] != "android-dex":
            raise ValueError(f"{path}: not the Unified Home native plugin")
        if hashlib.sha256(dex).hexdigest() != manifest["sha256"]["classes.dex"]:
            raise ValueError(f"{path}: DEX digest mismatch")
        if dex[:8] not in (b"dex\n035\0", b"dex\n037\0"):
            raise ValueError(f"{path}: unsupported DEX format")
    print(f"{path}: {path.stat().st_size:,} bytes; plugin DEX and signed metadata only")


def check_jar(path):
    with zipfile.ZipFile(path) as archive:
        classes = [name for name in archive.namelist() if name.endswith(".class")]
        if not classes or any(not name.startswith("org/amalia/unifiedhome/") for name in classes):
            raise ValueError(f"{path}: JAR must contain only Unified Home classes, not host/SDK classes")
    print(f"{path}: {len(classes)} plugin classes; no bundled host/SDK classes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("packages", nargs="*", type=Path)
    parser.add_argument("--jar", type=Path)
    args = parser.parse_args()
    if not args.packages and not args.jar:
        parser.error("Specify a package or --jar")
    try:
        if args.jar:
            check_jar(args.jar)
        for path in args.packages:
            check_package(path)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Plugin boundary error: {error}\n")


if __name__ == "__main__":
    main()
