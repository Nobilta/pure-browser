import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import java.io.File
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
        versionCode = 19
        versionName = "0.9.1"
        testInstrumentationRunner = "com.mybrowser.validation.NativeFilterInstrumentation"

        // Release ships arm64-v8a only; Android version and CPU ABI are separate limits.
        // Older devices with a 32-bit Android installation are not included. Overridable via
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
            // A directly distributed APK benefits from ZIP-compressed DEX. Android 10+
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
        // ModalBottomSheet is @ExperimentalMaterial3Api with the pinned BOM. The whole
        // menu/cast UI is built on it, so opt in once here instead of per-file.
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

// Rust builds share the same entry point with standalone builds.
val rustDir = rootProject.file("rust")
val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

// An Exec task does not run through a login shell, so PATH additions from a shell profile are
// not guaranteed to be visible. Name the rustup locations on this host instead of assuming the
// macOS toolchain directory.
val rustupHome = System.getenv("RUSTUP_HOME") ?: (System.getProperty("user.home") + "/.rustup")
val cargoHome = System.getenv("CARGO_HOME") ?: (System.getProperty("user.home") + "/.cargo")

// rustup installs the default toolchain as stable-<host triple>. Unknown combinations fall back
// to the shim directory, which still resolves cargo the same way a shell would.
fun rustHostTriple(): String? {
    val cpu = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        else -> return null
    }
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.startsWith("mac") || os.startsWith("darwin") -> "$cpu-apple-darwin"
        os.startsWith("linux") -> "$cpu-unknown-linux-gnu"
        os.startsWith("windows") -> "$cpu-pc-windows-msvc"
        else -> null
    }
}

fun rustPathEntries(): List<String> = buildList {
    // The shim directory comes first because it resolves whatever toolchain rustup considers
    // current, exactly as a shell would. A pinned toolchain (CI installs a versioned one and adds
    // the Android targets to it) would otherwise be shadowed by a same-named "stable-<triple>"
    // toolchain that happens to exist on the machine but has no Android target installed.
    add("$cargoHome/bin")
    rustHostTriple()?.let { add("$rustupHome/toolchains/stable-$it/bin") }
}.filter { File(it).isDirectory }

// A bare "bash" on Windows resolves to the WSL launcher in C:\Windows\System32, which fails with
// "Windows Subsystem for Linux has no installed distributions". Git for Windows provides the
// POSIX shell the Rust scripts need; override with -Pmybrowser.bash or PURE_BASH.
fun resolveBash(): String {
    val override = providers.gradleProperty("mybrowser.bash").orNull ?: System.getenv("PURE_BASH")
    if (!override.isNullOrBlank()) return override
    if (!isWindowsHost) return "bash" // PATH lookup keeps the macOS and Linux behavior unchanged
    val candidates = listOfNotNull(
        System.getenv("ProgramFiles")?.let { "$it\\Git\\bin\\bash.exe" },
        System.getenv("ProgramFiles(x86)")?.let { "$it\\Git\\bin\\bash.exe" },
        System.getenv("LocalAppData")?.let { "$it\\Programs\\Git\\bin\\bash.exe" },
    )
    return candidates.firstOrNull { File(it).isFile } ?: "bash"
}

val bashShell = resolveBash()

// Arguments reach the shell and native cargo as separate argv entries, so spaces in the home
// directory are safe; forward slashes are understood by both the MSYS shell and native tools.
fun shellPath(file: File): String = file.absolutePath.replace('\\', '/')

val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Cross-compiles application Rust modules for each configured ABI."

    val abiProp = providers.gradleProperty("mybrowser.abi").orElse("arm64-v8a")
    val abis = abiProp.map { it.split(',').map { s -> s.trim() }.filter { it.isNotEmpty() } }
    val outDir = layout.buildDirectory.dir("rustJniLibs")

    inputs.dir(rustDir.resolve("adblock/src"))
    inputs.dir(rustDir.resolve("url_utils/src"))
    inputs.dir(rustDir.resolve("site_identity"))
    inputs.files(
        rustDir.resolve("adblock/Cargo.toml"), rustDir.resolve("url_utils/Cargo.toml"),
        rustDir.resolve("Cargo.toml"), rustDir.resolve("Cargo.lock"),
        rustDir.resolve("build.sh"), rustDir.resolve("resolve-android-ndk.sh"),
    )
    inputs.property("abis", abis)
    inputs.property("androidApi", libs.versions.minSdk.get())
    val filterOpt = providers.gradleProperty("mybrowser.filterOpt").orElse("")
    inputs.property("filterOpt", filterOpt)
    outputs.dir(outDir)

    workingDir(rustDir)
    val hostPath = rustPathEntries().joinToString(File.pathSeparator)
    val inheritedPath = System.getenv("PATH").orEmpty()
    environment(
        "PATH",
        if (hostPath.isEmpty()) inheritedPath else "$hostPath${File.pathSeparator}$inheritedPath",
    )
    environment("RUSTUP_HOME", rustupHome)
    environment("CARGO_HOME", cargoHome)
    environment("PURE_FILTER_OPT", filterOpt.get())
    commandLine(listOf(bashShell, "-c", """
        set -euo pipefail
        for abi in "${'$'}@"; do
            case ${'$'}abi in
                arm64-v8a) target=aarch64-linux-android ;;
                x86_64) target=x86_64-linux-android ;;
                *) echo "Unmapped ABI: ${'$'}abi" >&2; exit 1 ;;
            esac
            TARGET="${'$'}target" ABI="${'$'}abi" bash ./build.sh
        done
    """.trimIndent(), "cargoBuild") + abis.get())
}

// mergeJniLibFolders is the first task that reads the staged .so, so hooking every
// variant's copy of it covers debug and release without naming them.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoBuild) }

// Exercise the real JNI integration on the development host as well as in emulator runs.
val hostTestJni = layout.buildDirectory.dir("hostTestJni")
val cargoBuildHostTests = tasks.register<Exec>("cargoBuildHostTests") {
    inputs.dir(rustDir.resolve("site_identity"))
    inputs.dir(rustDir.resolve("adblock/src"))
    inputs.dir(rustDir.resolve("url_utils/src"))
    inputs.files(rustDir.resolve("Cargo.toml"), rustDir.resolve("Cargo.lock"),
        rustDir.resolve("adblock/Cargo.toml"), rustDir.resolve("url_utils/Cargo.toml"),
        rustDir.resolve("build-host-tests.sh"))
    outputs.dir(hostTestJni)
    workingDir(rustDir)
    commandLine(
        bashShell,
        shellPath(rustDir.resolve("build-host-tests.sh")),
        shellPath(hostTestJni.get().asFile),
    )
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(cargoBuildHostTests)
    inputs.dir(hostTestJni)
    systemProperty("java.library.path", hostTestJni.get().asFile.absolutePath)
    if (isWindowsHost) {
        // Robolectric creates its per-test data directory under java.io.tmpdir, which the Windows
        // runner hands to the JVM in 8.3 short form (C:\Users\RUNNER~1\...). File.getCanonicalPath()
        // expands that form for a path that exists but not for one that does not, and AndroidX's
        // FileProvider compares a canonical file path against roots canonicalized before the file
        // appeared, so no root ever matches there. A short directory of our own has no alias to
        // disagree about.
        val temp = File(System.getenv("SystemDrive") ?: "C:", "pure-browser-tests")
        val usable = temp.isDirectory || temp.mkdirs()
        systemProperty("java.io.tmpdir", if (usable) temp.absolutePath else System.getProperty("java.io.tmpdir"))
    }
}

dependencies {
    implementation(libs.zxing.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core)
    implementation(libs.androidx.webkit)
    implementation(libs.coroutines.android)

    // Compose. Every androidx.compose.* dependency takes its version from the BOM.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    // Robolectric 4.16 brings ASM 9.8, which cannot parse classes produced by JDK 26.
    // Pin the complete ASM family together for host-side tests; it is not packaged in the
    // Android application.
    testImplementation(libs.asm)
    testImplementation(libs.asm.commons)
    testImplementation(libs.asm.tree)
    testImplementation(libs.asm.util)
}

// Commit resolved versions as well as the catalog; upgrades explicitly refresh the lock.
dependencyLocking { lockAllConfigurations() }
