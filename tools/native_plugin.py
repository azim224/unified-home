#!/usr/bin/env python3
"""Create, validate and package an Android-native V3 plugin against an exported host SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True
from amalia_cli import CliError, find_d8, gradle_command, inspect_jars, read_json, reject_source_payloads, run

ROOT = Path(__file__).resolve().parents[1]
ID = re.compile(r"[a-z0-9]+(?:[.-][a-z0-9]+)+")
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")


def fail(message):
    raise CliError(message)


def sdk_files(directory):
    root = Path(directory).expanduser().resolve()
    metadata = read_json(root / "sdk.json")
    if metadata.get("schemaVersion") != 1:
        fail("Unsupported native SDK schema")
    hashes = metadata.get("sha256")
    if hashes is None and isinstance(metadata.get("files"), dict):
        hashes = {name: info["sha256"] for name, info in metadata["files"].items()}
    paths = metadata.get("classpath", metadata.get("compileOnlyClasspath"))
    metadata["classpath"] = paths
    versions = metadata.get("versions", {})
    metadata.setdefault("kotlinVersion", versions.get("kotlin", "2.2.10"))
    metadata.setdefault("composeCompilerVersion", metadata["kotlinVersion"])
    if not isinstance(hashes, dict) or not hashes or not isinstance(paths, list) or not paths:
        fail("SDK must contain a checksum inventory and a nonempty classpath")
    for name, digest in hashes.items():
        if not isinstance(name, str) or Path(name).is_absolute() or ".." in Path(name).parts:
            fail("Unsafe SDK inventory path")
        path = root / name
        if path.is_symlink():
            fail("Unsafe SDK inventory path")
        if not path.resolve().is_relative_to(root) or not path.is_file():
            fail(f"SDK inventory file missing or outside SDK: {name}")
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            fail(f"SDK checksum mismatch: {name}")
    for name in paths:
        if not isinstance(name, str) or name not in hashes or not name.endswith(".jar"):
            fail("Every SDK classpath JAR must be inventoried")
    if not isinstance(metadata.get("abi"), str) or not metadata["abi"]:
        fail("SDK ABI is missing")
    return root, metadata, [root / name for name in paths]


SOURCE = '''package __NAMESPACE__

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.my.amali.plugin.AmaliaPlugin
import com.my.amali.plugin.NativePluginEntrypoint
import com.my.amali.plugin.PluginCategory
import com.my.amali.plugin.PluginMetadata
import com.my.amali.plugin.PromptBuilder
import com.my.amali.plugin.PromptContributor
import com.my.amali.plugin.ScreenContributor
import com.my.amali.plugin.ScreenHost
import com.my.amali.plugin.ScreenSpec
import com.my.amali.ui.components.AmaliaScreen
import com.my.amali.ui.components.GlassCard
import org.amalia.platform.protocol.PluginContext

class Entry : NativePluginEntrypoint {
    override val contribution: AmaliaPlugin = NativeProduct()
    override suspend fun start(context: PluginContext) = Unit
    override suspend fun stop() = Unit
}

class NativeProduct : AmaliaPlugin, ScreenContributor, PromptContributor {
    override val metadata = PluginMetadata(
        id = "__PACKAGE_ID__.native", name = "__NAME__", version = "1.0.0",
        category = PluginCategory.SCREEN,
    )
    override fun contribute(builder: PromptBuilder) {
        builder.rule("Do not claim that a plugin action succeeded without a real result.")
    }
    override fun screens() = listOf(ScreenSpec(
        route = "amalia://screen/__PACKAGE_ID__", titleRes = 0,
        title = "__NAME__", inNavigation = true,
        content = { host -> ProductScreen(host) },
    ))
}

private data class Copy(val title: String, val body: String, val back: String)
private val translations = mapOf(
    "ru" to Copy("Ваше пространство", "Экран работает через native API Амалии.", "Назад"),
    "en" to Copy("Your space", "This screen uses Amalia’s native API.", "Back"),
    "de" to Copy("Ihr Bereich", "Dieser Bildschirm nutzt Amalias native API.", "Zurück"),
    "es" to Copy("Tu espacio", "Esta pantalla usa la API nativa de Amalia.", "Atrás"),
    "fr" to Copy("Votre espace", "Cet écran utilise l’API native d’Amalia.", "Retour"),
    "ja" to Copy("あなたの空間", "この画面はAmaliaのネイティブAPIを使用しています。", "戻る"),
    "zh" to Copy("你的空间", "此界面使用Amalia原生API。", "返回"),
    "hi" to Copy("आपका स्थान", "यह स्क्रीन Amalia के नेटिव API का उपयोग करती है।", "वापस"),
    "ar" to Copy("مساحتك", "تستخدم هذه الشاشة واجهة Amalia الأصلية.", "رجوع"),
)

@Composable
private fun ProductScreen(host: ScreenHost) {
    val language = LocalConfiguration.current.locales[0].language
    val copy = translations[language] ?: translations.getValue("en")
    AmaliaScreen(title = copy.title, onBack = host::goBack, backLabel = copy.back) { insets ->
        Column(Modifier.padding(insets)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(copy.body, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
'''


def create(args):
    sdk, metadata, _ = sdk_files(args.sdk)
    if not ID.fullmatch(args.package_id):
        fail("Invalid package ID (lowercase reverse domain required)")
    namespace = "org.amalia.author.p_" + args.package_id.encode("utf-8").hex()
    name = args.name or args.package_id.rsplit(".", 1)[-1]
    if not re.fullmatch(r"[A-Za-z0-9 _-]{1,80}", name):
        fail("Project name must use 1–80 ASCII letters, digits, spaces, underscores or hyphens")
    directory = Path(args.directory).expanduser().resolve()
    if directory.exists():
        fail("Destination already exists; no files were changed")
    kotlin = metadata.get("kotlinVersion", "2.2.10")
    compose = metadata.get("composeCompilerVersion", kotlin)
    if not re.fullmatch(r"\d+\.\d+\.\d+", kotlin) or not re.fullmatch(r"\d+\.\d+\.\d+", compose):
        fail("Unsupported compiler versions in SDK metadata")
    source = SOURCE.replace("__NAMESPACE__", namespace).replace("__PACKAGE_ID__", args.package_id).replace("__NAME__", name)
    files = {
        "native-plugin.json": json.dumps(dict(schemaVersion=1, packageId=args.package_id,
            publisher=args.publisher or args.package_id.rsplit(".", 1)[0], version="1.0.0",
            entryPoint=namespace + ".Entry", sdk=str(sdk), abi=metadata["abi"]), indent=2) + "\n",
        "settings.gradle.kts": 'pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }\n'
            'dependencyResolutionManagement { repositories { mavenCentral() } }\nrootProject.name = "native-plugin"\n',
        "build.gradle.kts": '''import groovy.json.JsonSlurper
plugins {
    kotlin("jvm") version "__KOTLIN__"
    id("org.jetbrains.kotlin.plugin.compose") version "__COMPOSE__"
}
kotlin { jvmToolchain(17); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
val author = JsonSlurper().parse(file("native-plugin.json")) as Map<*, *>
val sdkRoot = file(providers.gradleProperty("amaliaNativeSdk").orNull ?: author["sdk"].toString())
val sdk = JsonSlurper().parse(sdkRoot.resolve("sdk.json")) as Map<*, *>
val classpath = ((sdk["classpath"] ?: sdk["compileOnlyClasspath"]) as List<*>).map { sdkRoot.resolve(it.toString()) }
check(sdk["abi"] == author["abi"]) { "Native SDK ABI differs from project; regenerate/review compatibility" }
dependencies { compileOnly(files(classpath)) }
// The host supplies Kotlin and every exported dependency. Never make a fat JAR.
'''.replace("__KOTLIN__", kotlin).replace("__COMPOSE__", compose),
        "src/main/kotlin/" + namespace.replace(".", "/") + "/Entry.kt": source,
        "gradle.properties": "kotlin.stdlib.default.dependency=false\n",
        ".gitignore": ".gradle/\nbuild/\ndist/\n*.pem\n*.key\n",
        "README.md": "# Native Amalia plugin\n\n"
            "Compile: `./gradlew jar` (or Gradle 9.7.1). JDK 17 required.\n"
            "Edit `native-plugin.json` and `src/main/kotlin/`; keep contribution ID equal to packageId + `.native`.\n"
            "Package with `python3 /path/to/amalia/tools/native_plugin.py package --project . "
            "--private-key /private/author.pem --key-id author-1 --d8 /path/to/d8`.\n"
            "Pin publisher, review android.native and activate in Amalia. No APK is built.\n"
            "Do not bundle host/AndroidX/Kotlin classes. This SDK is specific to the matching host build.\n",
    }
    publisher = args.publisher or args.package_id.rsplit(".", 1)[0]
    if not ID.fullmatch(publisher):
        fail("Invalid publisher ID")
    directory.mkdir(parents=True)
    for name, text in files.items():
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    for name in ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / "android" / name, target)
    (directory / "gradlew").chmod(0o755)
    print(f"Created {directory}; native code has full app-process privileges. Review before activation.")


def validate(project, override_sdk=None):
    root = Path(project).expanduser().resolve()
    manifest = read_json(root / "native-plugin.json")
    fields = {"schemaVersion", "packageId", "publisher", "version", "entryPoint", "sdk", "abi"}
    if not isinstance(manifest, dict) or set(manifest) != fields or manifest["schemaVersion"] != 1:
        fail("Invalid native-plugin.json fields/schema")
    for field in ("packageId", "publisher"):
        if not isinstance(manifest[field], str) or not ID.fullmatch(manifest[field]):
            fail(f"Invalid {field}")
    if not isinstance(manifest["version"], str) or not VERSION.fullmatch(manifest["version"]):
        fail("Use a numeric semantic version")
    if not isinstance(manifest["entryPoint"], str) or not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", manifest["entryPoint"]):
        fail("Invalid Java entrypoint")
    sdk, metadata, classpath = sdk_files(override_sdk or manifest["sdk"])
    if manifest["abi"] != metadata["abi"]:
        fail("Project ABI does not match SDK")
    for path in ("settings.gradle.kts", "build.gradle.kts"):
        if not (root / path).is_file():
            fail(f"Missing {path}")
    reject_source_payloads(root)
    if not any((root / "src/main").rglob("*.kt")):
        fail("No Kotlin plugin sources")
    return root, manifest, sdk, classpath


def package(args):
    root, manifest, sdk, classpath = validate(args.project, args.sdk)
    key = Path(args.private_key).expanduser().resolve()
    if not key.is_file():
        fail("Publisher private key not found")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", args.key_id):
        fail("Invalid key ID")
    d8 = find_d8(args.d8)
    output = Path(args.output).expanduser().resolve() if args.output else root / "dist" / f"{manifest['packageId']}-{manifest['version']}.amaliaplug"
    if output.exists():
        fail("Output exists; refusing overwrite")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".native-package-", dir=root) as temp:
        directory = Path(temp)
        init = directory / "inputs.gradle"
        resolved = directory / "inputs.json"
        init.write_text('''import groovy.json.JsonOutput
gradle.projectsEvaluated {
    def p = gradle.rootProject
    p.tasks.register('amaliaNativeInputs') {
        dependsOn p.tasks.named('jar')
        doLast {
            def files = { n -> p.configurations.getByName(n).files.collect { it.absolutePath }.sort() }
            new File(System.getProperty('amalia.native.inputs')).text = JsonOutput.toJson([
                jar: p.tasks.named('jar').get().archiveFile.get().asFile.absolutePath,
                runtime: files('runtimeClasspath'), compile: files('compileClasspath')])
        }
    }
}
''')
        run(gradle_command(root) + ["--no-daemon", "--console=plain", "-I", str(init),
            f"-Damalia.native.inputs={resolved}", f"-PamaliaNativeSdk={sdk}",
            *args.gradle_arg, "jar", "amaliaNativeInputs"], root)
        inputs = read_json(resolved)
        # Exact SDK payload digests identify shared runtime dependencies, even after relocation.
        shared_hashes = {hashlib.sha256(path.read_bytes()).hexdigest() for path in classpath}
        jars = [Path(inputs["jar"])]
        for value in inputs["runtime"]:
            path = Path(value)
            if path.suffix != ".jar" or not path.is_file():
                fail(f"Only resource-free JVM JAR dependencies are supported: {path.name}")
            if hashlib.sha256(path.read_bytes()).hexdigest() not in shared_hashes:
                # Kotlin dependencies are supplied by the host; raw Maven JARs differ from
                # normalized SDK copies, so recognize their reserved definitions as shared.
                with zipfile.ZipFile(path) as archive:
                    names = archive.namelist()
                if any(n.startswith(("kotlin/", "kotlinx/", "androidx/", "org/amalia/platform/")) and n.endswith(".class") for n in names):
                    fail(f"Use the exported compileOnly SDK, not runtime dependency {path.name}")
                jars.append(path)
        jars = list(dict.fromkeys(jars))
        inspect_jars(jars, manifest["entryPoint"])
        # D8 parent linkage would otherwise silently resolve a different host class.
        # Inspect all SDK definitions, not just AndroidX/Kotlin namespace prefixes.
        host_classes = set()
        for library in classpath:
            with zipfile.ZipFile(library) as archive:
                host_classes.update(n for n in archive.namelist() if n.endswith(".class"))
        for program in jars:
            with zipfile.ZipFile(program) as archive:
                overlap = host_classes.intersection(archive.namelist())
            if overlap:
                fail(f"Program JAR duplicates an exported host class: {sorted(overlap)[0]}")
        for value in inputs["compile"]:
            path = Path(value)
            if path not in jars and path not in classpath:
                if not path.is_file() or path.suffix != ".jar":
                    fail(f"Unsupported compile classpath: {path.name}")
                classpath.append(path)
        dex = directory / "dex"
        dex.mkdir()
        # The host runs API 26+, but its single-DEX inventory intentionally accepts 035/037.
        # D8 min-api 26 emits 038; min-api 25 preserves this established loader format.
        command = [d8, "--min-api", "25", "--output", dex]
        for path in classpath:
            command += ["--lib" if path.name == "android.jar" else "--classpath", path]
        run(command + jars, root)
        if sorted(p.name for p in dex.iterdir()) != ["classes.dex"]:
            fail("Host requires a single classes.dex; resources and multidex are unsupported")
        offers = directory / "offers.json"
        caps = directory / "caps.json"
        offers.write_text("[]\n")
        caps.write_text('["android.native"]\n')
        staged = directory / "plugin.amaliaplug"
        run([sys.executable, ROOT / "tools/PackagePlatformPlugin.py", "--dex", dex / "classes.dex",
             "--package-id", manifest["packageId"], "--publisher", manifest["publisher"],
             "--version", manifest["version"], "--entrypoint", manifest["entryPoint"],
             "--abi", manifest["abi"], "--key-id", args.key_id, "--private-key", key,
             "--offers", offers, "--capabilities", caps, "--output", staged])
        with zipfile.ZipFile(staged) as archive:
            raw = archive.read("platform.json")
            signed = json.loads(raw)
            if signed["sha256"]["classes.dex"] != hashlib.sha256(archive.read("classes.dex")).hexdigest():
                fail("Packaged DEX digest mismatch")
            import base64
            (directory / "signature.der").write_bytes(base64.b64decode(json.loads(archive.read("package.sig"))["signature"], validate=True))
        (directory / "platform.json").write_bytes(raw)
        result = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-passin", "pass:"], check=True, capture_output=True)
        (directory / "public.pem").write_bytes(result.stdout)
        info = subprocess.run(["openssl", "pkey", "-pubin", "-in", str(directory / "public.pem"), "-text", "-noout"], check=True, capture_output=True)
        if b"ASN1 OID: prime256v1" not in info.stdout:
            fail("Publisher key must be P-256")
        run(["openssl", "dgst", "-sha256", "-verify", directory / "public.pem", "-signature", directory / "signature.der", directory / "platform.json"])
        with output.open("xb") as target:
            target.write(staged.read_bytes())
    print(f"Packaged {output}. Publisher trust, android.native consent and activation are separate host actions.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    create_parser = sub.add_parser("create")
    create_parser.add_argument("--directory", required=True)
    create_parser.add_argument("--package-id", required=True)
    create_parser.add_argument("--publisher")
    create_parser.add_argument("--name")
    create_parser.add_argument("--sdk", required=True, help="Extracted native SDK directory containing sdk.json")
    validate_parser = sub.add_parser("validate")
    validate_parser.add_argument("--project", required=True)
    validate_parser.add_argument("--sdk")
    package_parser = sub.add_parser("package")
    package_parser.add_argument("--project", required=True)
    package_parser.add_argument("--sdk")
    package_parser.add_argument("--private-key", required=True)
    package_parser.add_argument("--key-id", required=True)
    package_parser.add_argument("--d8")
    package_parser.add_argument("--output")
    package_parser.add_argument("--gradle-arg", action="append", default=[])
    args = parser.parse_args()
    try:
        if args.command == "create":
            create(args)
        elif args.command == "validate":
            root, _, _, _ = validate(args.project, args.sdk)
            print(f"Validated native project and SDK checksums: {root}")
        else:
            package(args)
    except (CliError, OSError, ValueError, TypeError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Native plugin error: {error}\n")


if __name__ == "__main__":
    main()
