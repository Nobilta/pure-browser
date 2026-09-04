import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // No org.jetbrains.kotlin.android: AGP 9 built-in Kotlin covers it and
    // applying it would conflict.
    //
    // The Compose compiler plugin is a different matter — AGP refuses to configure a
    // module with `compose = true` unless it is applied. See the catalog for why its
    // version is pinned to 2.2.10.
    alias(libs.plugins.kotlin.compose)
}

// Signing material lives outside the build script and outside version control.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.mybrowser"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mybrowser"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // Release ships arm64-v8a only: that is the sole target the Rust filter engine is
        // cross-compiled for, and every device at minSdk 34 is arm64. Overridable via
        // -Pmybrowser.abi so a debug build can add x86_64 for the emulator — Compose pulls
        // in libandroidx.graphics.path.so, so an arm64-only APK will not install there.
        ndk {
            abiFilters += providers.gradleProperty("mybrowser.abi").get()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        // Off: the Compose migration deleted the last XML layout, so there is nothing to
        // generate bindings for.
        viewBinding = false
        // Requires org.jetbrains.kotlin.plugin.compose (applied above). AGP fails
        // configuration with "the Compose Compiler Gradle plugin is required when compose
        // is enabled" otherwise, even though it supplies the compiler coordinates itself.
        compose = true
        // Nothing generates BuildConfig fields we need yet; off to save a class.
        buildConfig = false
    }

    compileOptions {
        // AGP 9 defaults Java to 11; 17 to match the JDK toolchain.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // cargoBuild is the single source of native libraries.  The old checked-in
        // jniLibs directory contained stale copies with different names (and therefore
        // doubled the APK size); using only the generated directory keeps the Kotlin JNI
        // declarations and the packaged symbols in lock-step for every ABI.
        getByName("main").jniLibs.directories.add(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath,
        )
    }

    packaging {
        dex {
            // A directly distributed APK benefits from ZIP-compressed DEX. Android 14+
            // extracts it during installation; this trades install work and some installed
            // storage for a materially smaller download without changing runtime code.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/**/LICENSE.txt",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                // Metadata read only by kotlin-reflect, which is not a dependency.
                // Worth ~47KB of a 225KB APK.
                "**/*.kotlin_builtins",
                "**/*.kotlin_metadata",
                "**/*.kotlin_module",
            )
        }
        jniLibs {
            // Likewise compress the already stripped arm64 libraries in the APK. They are
            // extracted by PackageManager, which is supported by every target device.
            useLegacyPackaging = true
            // Rust .so is already stripped by the cargo release profile.
            keepDebugSymbols += "**/*.so"
        }
    }

    lint {
        // A browser touches a lot of deprecated-but-necessary WebView surface.
        warningsAsErrors = false
        abortOnError = true
        disable += setOf("GradleDependency")
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resource table; without this it
            // fails at startup with "No such manifest file: build/intermediates/...".
            isIncludeAndroidResources = true
        }
    }
}

// AGP 9: top-level kotlin block replaces android.kotlinOptions.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Warnings stay warnings; a browser has unavoidable deprecated API use.
        allWarningsAsErrors.set(false)
        // ModalBottomSheet is still @ExperimentalMaterial3Api in 1.4.x. The whole
        // menu/cast UI is built on it, so opt in once here instead of per-file.
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

// ---------------------------------------------------------------------------------------
// Rust native libraries
//
// Builds the application Rust modules for each ABI and stages the .so files into
// build/rustJniLibs/<abi>/. Legacy crates are opt-in (see includeLegacyRust below).
// ---------------------------------------------------------------------------------------
val rustDir = rootProject.file("rust")
// Only these crates are on an application code path today. The downloader and legacy
// filename parser remain source-compatible optional integrations, but packaging them by
// default added roughly 3 MB of native code and made every build compile unused network
// stacks. Enable them explicitly with -Pmybrowser.includeLegacyRust=true when an external
// integration needs those JNI entry points.
val includeLegacyRust = providers.gradleProperty("mybrowser.includeLegacyRust")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

// Rust ABI name per Android ABI
val rustTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)

val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Cross-compiles application Rust modules for each configured ABI."

    val abiProp = providers.gradleProperty("mybrowser.abi").orElse("arm64-v8a")
    val abis = abiProp.map { it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() } }
    val outDir = layout.buildDirectory.dir("rustJniLibs")

    val rustupHome = System.getProperty("user.home") + "/.rustup"
    val cargoHome = System.getProperty("user.home") + "/.cargo"
    val toolchainPath = "$rustupHome/toolchains/stable-aarch64-apple-darwin/bin"

    inputs.dir(rustDir.resolve("adblock/src"))
    inputs.dir(rustDir.resolve("url_utils/src"))
    inputs.dir(rustDir.resolve("cache/src"))
    inputs.files(
        rustDir.resolve("adblock/Cargo.toml"),
        rustDir.resolve("url_utils/Cargo.toml"),
        rustDir.resolve("cache/Cargo.toml"),
    )
    inputs.property("includeLegacyRust", includeLegacyRust)
    if (includeLegacyRust.get()) {
        inputs.dir(rustDir.resolve("downloader/src"))
        inputs.dir(rustDir.resolve("filename_parser/src"))
        inputs.files(
            rustDir.resolve("downloader/Cargo.toml"),
            rustDir.resolve("filename_parser/Cargo.toml"),
        )
    }
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))
    inputs.property("abis", abis)
    outputs.dir(outDir)

    // Build via shell with full Rust environment
    workingDir(rustDir)
    environment("PATH", "$toolchainPath:${System.getenv("PATH")}")
    environment("RUSTUP_HOME", rustupHome)
    environment("CARGO_HOME", cargoHome)

    commandLine("bash", "-c", """
        set -e

        # Resolve the NDK at execution time through the shared resolver. Keeping this
        # logic in one script avoids hard-coded developer paths and keeps standalone
        # rust/build.sh and Gradle builds on the same toolchain.
        if [ -z "${'$'}{ANDROID_NDK_HOME:-}" ]; then
            ANDROID_NDK_HOME="${'$'}(bash "${rustDir.absolutePath}/resolve-android-ndk.sh" "${rootProject.projectDir.absolutePath}")"
            export ANDROID_NDK_HOME
        fi
        if [ -z "${'$'}{ANDROID_NDK_HOME:-}" ] || [ ! -d "${'$'}ANDROID_NDK_HOME" ]; then
            echo "ANDROID_NDK_HOME could not be resolved" >&2
            exit 1
        fi
        toolchain="${'$'}ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64"
        if [ ! -x "${'$'}toolchain/bin/aarch64-linux-android34-clang" ]; then
            # Some NDK distributions use the arm64 host directory name.
            toolchain="${'$'}ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-aarch64"
        fi
        if [ ! -x "${'$'}toolchain/bin/aarch64-linux-android34-clang" ]; then
            echo "No Android clang toolchain found under ${'$'}ANDROID_NDK_HOME" >&2
            exit 1
        fi
        export PATH="${'$'}toolchain/bin:${'$'}PATH"
        export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${'$'}toolchain/bin/aarch64-linux-android34-clang"
        export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="${'$'}toolchain/bin/x86_64-linux-android34-clang"
        export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="${'$'}toolchain/bin/llvm-ar"
        export CARGO_TARGET_X86_64_LINUX_ANDROID_AR="${'$'}toolchain/bin/llvm-ar"

        for abi in ${abis.get().joinToString(" ")}; do
            case ${'$'}abi in
                arm64-v8a) target=aarch64-linux-android ;;
                x86_64) target=x86_64-linux-android ;;
                *) echo "Unmapped ABI: ${'$'}abi"; exit 1 ;;
            esac

            echo "Building Rust modules for ${'$'}target..."

            # Never let a removed/renamed crate survive in the staged directory.  Gradle
            # treats this directory as the complete jniLibs source set, so stale files
            # would otherwise be packaged into a later APK even though the current
            # workspace no longer builds them.
            out="${outDir.get().asFile.absolutePath}/${'$'}abi"
            rm -rf "${'$'}out"

            # Build only crates used by the Android application. Legacy JNI crates can be
            # opted in with -Pmybrowser.includeLegacyRust=true; keeping them out of the
            # default APK avoids shipping dead code and its transitive TLS/encoding stack.
            cargo build --release --target ${'$'}target -p adblock -p cache -p url_utils

            # Create output directory
            mkdir -p "${'$'}out"

            # Copy all .so files with the names used by System.loadLibrary().
            cp "target/${'$'}target/release/libadblock.so" "${'$'}out/libmybrowser_adblock.so"
            cp "target/${'$'}target/release/liburl_utils.so" "${'$'}out/libmybrowser_url_utils.so"
            cp "target/${'$'}target/release/libcache.so" "${'$'}out/libmybrowser_cache.so"

            if [ "${includeLegacyRust.get()}" = "true" ]; then
                cargo build --release --target ${'$'}target -p downloader -p filename_parser
                cp "target/${'$'}target/release/libdownloader.so" "${'$'}out/libmybrowser_downloader.so"
                cp "target/${'$'}target/release/libfilename_parser.so" "${'$'}out/libmybrowser_filename_parser.so"
            fi

            echo "✅ Built ${'$'}abi successfully"
        done
    """.trimIndent())
}

// mergeJniLibFolders is the first task that reads the staged .so, so hooking every
// variant's copy of it covers debug and release without naming them.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoBuild) }

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core)
    implementation(libs.androidx.webkit)
    implementation(libs.coroutines.android)

    // Compose. Every androidx.compose.* dependency takes its version from the BOM.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // Robolectric 4.16 brings ASM 9.8, which cannot parse classes produced by JDK 26.
    // Pin the complete ASM family together for host-side tests; it is not packaged in the
    // Android application.
    testImplementation(libs.asm)
    testImplementation(libs.asm.commons)
    testImplementation(libs.asm.tree)
    testImplementation(libs.asm.util)
}
