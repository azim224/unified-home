#!/usr/bin/env python3
"""Native packaging helpers vendored from the host CLI; no host checkout required."""


import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent

RESERVED = ("com/my/amali/", "org/amalia/platform/", "tech/amalia/platform/",
            "org/json/", "java/", "javax/", "android/", "androidx/", "dalvik/", "sun/",
            "com/android/", "kotlin/", "kotlinx/")

class CliError(Exception):
    pass

def fail(message):
    raise CliError(message)

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail(f"Duplicate JSON key: {key}")
        result[key] = value
    return result

def read_json(path):
    if path.stat().st_size > 1024 * 1024:
        fail(f"JSON exceeds 1 MiB: {path}")
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)

def reject_source_payloads(project):
    for root in (project / "src/main/resources", project / "src/main/res", project / "src/main/assets",
                 project / "src/main/jniLibs", project / "src/main/jni"):
        if root.exists() and any(p.is_file() for p in root.rglob("*")):
            fail(f"Resources/native payloads are not supported by the single-DEX format: {root}")
    if (project / "src/main/AndroidManifest.xml").exists():
        fail("Android projects/AARs are unsupported; use a pure Kotlin/JVM plugin")

def gradle_command(project):
    wrapper = project / "gradlew"
    if wrapper.is_file():
        return [str(wrapper)] if os.access(wrapper, os.X_OK) else ["bash", str(wrapper)]
    gradle = shutil.which("gradle")
    if not gradle:
        fail("Gradle not found: install Gradle 9.7.1 or supply the project's Gradle wrapper")
    return [gradle]

def run(command, cwd=None):
    subprocess.run([str(arg) for arg in command], cwd=cwd, check=True)

def find_d8(explicit):
    if explicit:
        path = Path(explicit).expanduser().resolve()
        if not path.is_file() or not os.access(path, os.X_OK):
            fail(f"--d8 must name an executable D8 file: {path}")
        return path
    installed = shutil.which("d8")
    if installed:
        return Path(installed).resolve()
    homes = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
             str(Path.home() / "Android/Sdk")]
    local = ROOT / "local.properties"
    if local.is_file():
        for line in local.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                homes.append(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    candidates = []
    for home in homes:
        if home:
            for path in (Path(home) / "build-tools").glob("*/d8"):
                if path.is_file() and os.access(path, os.X_OK):
                    numbers = tuple(int(n) for n in re.findall(r"\d+", path.parent.name))
                    candidates.append((numbers, "-" not in path.parent.name, str(path)))
    if not candidates:
        fail("D8 not found; install Android SDK build-tools or pass --d8 /path/to/d8")
    return Path(max(candidates)[2])

def inspect_jars(paths, entrypoint):
    definitions = set()
    for path in paths:
        if path.suffix.lower() != ".jar" or not path.is_file():
            fail(f"Only JVM JAR program inputs are supported (no AAR/directories): {path}")
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                name = info.filename
                if info.is_dir():
                    continue
                if name.endswith(".class"):
                    if name.startswith(RESERVED):
                        fail(f"Reserved host/shared class in {path.name}: {name}")
                    if name.startswith("META-INF/versions/") or name == "module-info.class":
                        fail(f"Modular/multi-release classes are unsupported: {path.name}: {name}")
                    # Kotlin value-class mangling can appear in generated coroutine names.
                    if not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$-]*(?:/[A-Za-z_$][A-Za-z0-9_$-]*)*\.class", name):
                        fail(f"Unsupported class name: {path.name}: {name}")
                    if name in definitions:
                        fail(f"Duplicate class definition: {name}")
                    definitions.add(name)
                else:
                    # JAR bookkeeping is not runtime data; all other payloads would be lost in DEX.
                    bookkeeping = (name.upper() == "META-INF/MANIFEST.MF"
                        or re.fullmatch(r"META-INF/[^/]+\.kotlin_module", name)
                        or re.fullmatch(r"(?i)(?:META-INF/)?(?:LICENSE|NOTICE|COPYING)(?:[._-][^/]*)?", name))
                    if not bookkeeping:
                        fail(f"Resource/native/unsupported payload would be lost: {path.name}: {name}")
    if entrypoint.replace(".", "/") + ".class" not in definitions:
        fail("Manifest entryPoint is not defined in the program JARs")
