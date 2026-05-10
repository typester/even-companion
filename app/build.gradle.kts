import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.typester.evencompanion"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.typester.evencompanion"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir(layout.buildDirectory.dir("generated/uniffi"))
            jniLibs.srcDir("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    implementation(libs.play.services.location)
    implementation("com.alphacephei:vosk-android:0.3.47")
    // sherpa-onnx AAR: download from https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.1/sherpa-onnx-1.13.1.aar
    // and place in app/libs/sherpa-onnx-1.13.1.aar
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("org.apache.commons:commons-compress:1.26.1")
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fun ndkHome(): String {
    val localProps = Properties().apply {
        val f = rootDir.resolve("local.properties")
        if (f.exists()) load(f.inputStream())
    }
    localProps.getProperty("ndk.dir")?.let { return it }
    System.getenv("ANDROID_NDK_HOME")?.let { return it }

    // Auto-detect from Android SDK (side-by-side NDK installs)
    val sdkDir = localProps.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: "${System.getProperty("user.home")}/Library/Android/sdk"
    val ndkDir = File(sdkDir, "ndk")
    if (ndkDir.exists()) {
        ndkDir.listFiles()?.sortedDescending()?.firstOrNull()?.let { return it.absolutePath }
    }

    error(
        "NDK not found. Install via Android Studio:\n" +
        "  Settings → Android SDK → SDK Tools → NDK (Side by side)\n" +
        "Or add ndk.dir=<path> to local.properties."
    )
}

val rustTargetDir = rootDir.resolve("rust/target")
val hostLib = rustTargetDir.resolve("debug/libevencore.dylib")
val bindingsOutDir = layout.buildDirectory.dir("generated/uniffi")

val ndkAbiTargets = listOf("arm64-v8a")

// ── UniFFI binding generation ─────────────────────────────────────────────────

val rustWorkspaceDir = rootDir.resolve("rust")

tasks.register<Exec>("buildRustHostLib") {
    group = "rust"
    description = "Compile evencore for the host to extract UniFFI metadata"
    workingDir = rustWorkspaceDir
    commandLine("cargo", "build", "-p", "evencore")
    outputs.file(hostLib)
    inputs.dir(rootDir.resolve("rust/core/src"))
}

tasks.register<Exec>("generateUniFFIBindings") {
    group = "rust"
    description = "Generate Kotlin bindings from the host-compiled evencore library"
    dependsOn("buildRustHostLib")
    workingDir = rustWorkspaceDir
    doFirst { bindingsOutDir.get().asFile.mkdirs() }
    commandLine(
        "cargo", "run",
        "-p", "uniffi-bindgen",
        "--",
        "generate",
        "--library", hostLib.absolutePath,
        "--language", "kotlin",
        "--out-dir", bindingsOutDir.get().asFile.absolutePath
    )
    inputs.file(hostLib)
    outputs.dir(bindingsOutDir)
}

// ── Rust → Android cross-compilation ─────────────────────────────────────────

tasks.register<Exec>("buildRustAndroid") {
    group = "rust"
    description = "Cross-compile evencore for all Android ABIs using cargo-ndk"
    workingDir = rustWorkspaceDir
    environment("ANDROID_NDK_HOME", ndkHome())
    commandLine(
        buildList {
            add("cargo"); add("ndk")
            ndkAbiTargets.forEach { add("-t"); add(it) }
            add("-P"); add("24")
            add("-o"); add(projectDir.resolve("src/main/jniLibs").absolutePath)
            add("--manifest-path"); add("core/Cargo.toml")
            add("build")
        }
    )
    inputs.dir(rootDir.resolve("rust/core/src"))
    outputs.dir(projectDir.resolve("src/main/jniLibs"))
}

afterEvaluate {
    tasks.named("preBuild").configure { dependsOn("generateUniFFIBindings") }
    tasks.named("mergeDebugJniLibFolders").configure { dependsOn("buildRustAndroid") }
    tasks.named("mergeReleaseJniLibFolders").configure { dependsOn("buildRustAndroid") }
}
