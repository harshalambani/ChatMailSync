import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// Release signing (Phase A6). keystore.properties is gitignored — never
// commit it or release.jks. Falls back to no signing config (release build
// stays unsigned) when the file isn't present, e.g. on a fresh checkout that
// hasn't generated/restored a keystore yet.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.wamailsync.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wamailsync.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-beta"

        // arm64-v8a only. This used to also include x86_64 for emulator
        // testing, with an attempted per-buildType override trimming it back
        // to arm64-v8a for release (see 2026-07-04-android-feasibility-and-
        // transposition-plan.md §4's size budget) — but AGP's BuildType DSL
        // silently ignores an `ndk { abiFilters }` block entirely (confirmed
        // by inspecting the built release APK: both ABIs' native libs and
        // Chaquopy stdlib/bootstrap assets were still packaged, ~9MB of pure
        // waste). abiFilters is only actually honored at defaultConfig
        // (or per product flavor), so it's set once here for every variant.
        // All testing so far has been on a real arm64 device anyway; add
        // x86_64 back temporarily if emulator testing is ever needed.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// ---------------------------------------------------------------------------
// Shared Python core: mirror the repo's top-level src/ package into this
// module's default Chaquopy source dir at build time, instead of pointing
// Chaquopy's sourceSet directly at ../../src. Chaquopy treats a srcDir's
// *contents* as sitting at the Python path root, so pointing it straight at
// ../../src would expose config.py/mail_client.py/etc. as top-level modules
// (import config) instead of the package Windows already uses everywhere
// (import src.config) — this Copy task instead recreates a literal "src"
// package folder under src/main/python/ so every existing `from src.x import
// y` statement in the shared core works unchanged on Android too.
// The destination is regenerated on every build and is gitignored; the repo's
// src/ directory (one level up from android/) remains the single source of
// truth — nothing is hand-edited here.
// ---------------------------------------------------------------------------

val pythonCoreSrc = rootProject.projectDir.parentFile.resolve("src")
val pythonCoreDest = layout.projectDirectory.dir("src/main/python/src").asFile

// Sync, not Copy: a Copy task only adds and overwrites, so a module deleted or
// renamed in the top-level src/ was left behind here forever and still got
// packaged into the APK. That is worse than a missing file — Chaquopy resolves
// modules by name at runtime, so getModule("src.old_name") would keep silently
// succeeding against a frozen copy that drifts further from the real one with
// every fix. Sync mirrors the source exactly, deletions included.
val syncPythonCore by tasks.registering(Sync::class) {
    from(pythonCoreSrc)
    into(pythonCoreDest)
    include("**/*.py")
    exclude("**/__pycache__/**")
}

tasks.named("preBuild") {
    dependsOn(syncPythonCore)
}

// Chaquopy's own per-variant "mergeDebugPythonSources"/"mergeReleasePythonSources"
// tasks read directly from src/main/python, the same directory syncPythonCore
// writes into — Gradle's task-validation flags that as an undeclared
// dependency unless it's explicit, even though preBuild already orders it
// correctly in practice.
tasks.matching { it.name.contains("PythonSources") }.configureEach {
    dependsOn(syncPythonCore)
}

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            // Pinned exact versions: these get baked into every release APK,
            // so an unpinned install() would let a future rebuild silently
            // pick up a different (possibly vulnerable or breaking) version
            // with no code change to review. Bump deliberately.
            install("python-dateutil==2.9.0.post0")
            install("requests==2.33.1")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
