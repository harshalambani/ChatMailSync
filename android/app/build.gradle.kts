import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    namespace = "com.chatmailsync.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chatmailsync.app"
        // 30, not 24 (ST-01, D33): the :core state SQL uses INSERT ... ON CONFLICT, which needs
        // SQLite 3.24; Android 11 is the first to ship that (3.28). Android 7-10 are dropped.
        minSdk = 30
        targetSdk = 36
        // Historically moved in step with the now-retired Windows app's own
        // version file, and every release published an APK alongside the
        // Windows assets - v1.2.1, v1.3.0 and v1.4.0 each bumped this file and
        // then shipped Windows only, so the phone build was stamped as
        // released without ever being published; v1.4.1 was the first release
        // to carry both. The Windows app ended at v2.1.5 (tag `windows-final`)
        // and this repo is Android-only from here on.
        versionCode = 47
        versionName = "3.0.1"

        // arm64-v8a only. abiFilters is only honoured at defaultConfig (or per product flavor),
        // never in a buildType, so it is set once here for every variant. The app ships no
        // native code of its own since CUT-03 removed the embedded Python runtime; the only .so
        // left comes from androidx (graphics-path), and this filter keeps just its arm64-v8a
        // copy. It also stops a future native dependency from quietly widening the ABI set.
        // QA-01: the CI emulator job is x86_64 and passes -PciEmulatorAbi=x86_64. Without that
        // property (every release and local build) this stays exactly arm64-v8a.
        ndk {
            abiFilters += providers.gradleProperty("ciEmulatorAbi").getOrElse("arm64-v8a")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // AGP 8 stopped generating BuildConfig by default. SettingsScreen reads
        // VERSION_NAME / VERSION_CODE from it so the About line cannot drift
        // from the APK; without this the class does not exist and the build
        // fails on an unresolved reference.
        buildConfig = true
    }

    // Opts out of AGP's Play-specific dependency metadata block: an
    // encrypted blob of the resolved dependency graph, embedded in the APK
    // signing block so only Google can decrypt and read it for Play
    // Console. This app isn't distributed through Play, and F-Droid asks
    // apps to drop the block since no one outside Google can inspect it —
    // dropping it removes that ask on every channel with no functional
    // effect.
    testOptions {
        unitTests {
            // Robolectric needs the merged manifest/resources on the test classpath.
            isIncludeAndroidResources = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// ---------------------------------------------------------------------------
// Bundles the repo's own top-level NOTICE (third-party licence notices) into
// the APK as a plain-text asset, so the in-app "Open-source licences" screen
// (OpenSourceLicensesScreen.kt) reads the exact same text a reviewer or a
// user sees in the repo, rather than a hand-copied duplicate that could
// drift from it. One source of truth:
// the destination is regenerated on every build and gitignored, and NOTICE
// itself remains the single tracked copy.
// ---------------------------------------------------------------------------
val copyNoticeAsset by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("NOTICE"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "NOTICE.txt" }
    // A CRLF NOTICE (stale Windows working copy) would change the APK bytes and
    // make the release differ from a Linux build. Fail loudly rather than
    // normalising silently; .gitattributes keeps the checkout LF.
    val noticeSource = rootProject.projectDir.parentFile.resolve("NOTICE")
    doFirst {
        if (noticeSource.readBytes().any { it == 13.toByte() }) {
            throw GradleException(
                "NOTICE contains carriage returns (CRLF). Re-checkout it with LF " +
                    "(see .gitattributes) before building, or the APK will not be reproducible.",
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(copyNoticeAsset)
}

// Merging tasks pick the asset up at package time; unit-test tasks need it
// too, since NoticeAssetTest reads the copied file straight off disk rather
// than through a packaged APK.
tasks.matching { it.name.contains("Assets") || it.name.contains("UnitTest") }.configureEach {
    dependsOn(copyNoticeAsset)
}

// ---------------------------------------------------------------------------
// QA-01: the emulator job runs the SAME golden-parity tests :core runs on the desktop JVM, but on
// ART, by copying the pure ones (no JDBC driver, no sockets, no `internal` access) into this
// module's androidTest source set at build time. One source of truth: nothing is duplicated in git.
// The goldens come from :core's test resources and are packaged as classpath resources, so the
// tests' getResourceAsStream("golden/...") works unchanged inside the test APK.
// ---------------------------------------------------------------------------
val coreTestDir = rootProject.projectDir.resolve("core/src/test/kotlin/com/chatmailsync/core/mail")
val sharedCoreTests = listOf(
    "MiniJsonReader.kt", "ParserGoldenParityTest.kt", "HtmlRendererGoldenParityTest.kt",
    "HtmlMimeGoldenParityTest.kt", "MimeGoldenParityTest.kt", "MimeCasesGoldenParityTest.kt",
    "MailIndexGoldenParityTest.kt", "MediaExtractorGoldenParityTest.kt",
    "NormaliseCutoffGoldenParityTest.kt",
    "StateHashGoldenParityTest.kt", "ChunkMessagesAndBudgetsTest.kt", "ImapUtf7Test.kt",
    
)
val qa01SharedDir = layout.projectDirectory.dir("build/generated/qa01/kotlin").asFile
val syncQa01CoreTests by tasks.registering(Sync::class) {
    from(coreTestDir) { include(sharedCoreTests) }
    // The one Robolectric test is made a plain instrumented test: same cases, real SQLite.
    from(projectDir.resolve("src/test/java/com/chatmailsync/app")) {
        include("AndroidStateDbContractTest.kt")
        filter { line ->
            when {
                line.contains("org.robolectric") -> null
                line.trim() == "@RunWith(RobolectricTestRunner::class)" -> null
                line.trim().startsWith("@SQLiteMode") -> null
                else -> line
            }
        }
    }
    into(qa01SharedDir)
}
android.sourceSets.getByName("androidTest").kotlin.srcDir(qa01SharedDir)
android.sourceSets.getByName("androidTest").resources.srcDir(rootProject.projectDir.resolve("core/src/test/resources"))
tasks.matching { it.name.startsWith("compileDebugAndroidTest") && it.name.endsWith("Kotlin") }
    .configureEach { dependsOn(syncQa01CoreTests) }

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
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(project(":core"))

    debugImplementation("androidx.compose.ui:ui-tooling")

    // JVM unit tests. CUT-02 deliberately reverses the old "no Robolectric" rule: the Android
    // StateDb (AndroidStateDb, SQLiteDatabase) is the one piece the plain JVM cannot run, and an
    // upgrade over a Python-written database depends on it. Robolectric runs the same contract
    // cases :core uses for its JDBC double against the real adapter. It is used for that and
    // nothing else; the Context-free tests stay plain JUnit.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("junit:junit:4.13.2")
}
