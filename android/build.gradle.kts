import groovy.json.JsonSlurper

plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.compose.foundation.ExperimentalFoundationApi")
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// The author manifest points at the extracted exact-host SDK (amalia-native-sdk.zip).
val author = JsonSlurper().parse(file("native-plugin.json")) as Map<*, *>
val sdkRoot = file(providers.gradleProperty("amaliaNativeSdk").orNull ?: author["sdk"].toString())
val sdk = JsonSlurper().parse(sdkRoot.resolve("sdk.json")) as Map<*, *>
val classpath = ((sdk["classpath"] ?: sdk["compileOnlyClasspath"]) as List<*>)
    .map { sdkRoot.resolve(it.toString()) }
// Older SDK exports stripped .kotlin_builtins from the normalized stdlib JAR.
// Use the same official stdlib version for compilation only, never as D8 input.
val stdlib = (sdk["dependencies"] as List<*>).map { it as Map<*, *> }
    .single { it["component"].toString().startsWith("org.jetbrains.kotlin:kotlin-stdlib:") }
val compileClasspath = classpath.filter { it != sdkRoot.resolve(stdlib["path"].toString()) }

check(sdk["abi"] == author["abi"]) {
    "Native SDK ABI differs from native-plugin.json; export the SDK from the exact host build"
}

dependencies {
    // compileOnly: the host supplies Kotlin, Compose, AndroidX, protocol and its own API.
    // Never ship a fat JAR — duplicate host classes are rejected by the packager.
    compileOnly(files(compileClasspath))
    compileOnly(stdlib["component"].toString())
}