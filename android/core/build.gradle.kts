import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android-free Kotlin/JVM module — the first slice of the long-term Kotlin
// port of the Python core described in
// 2026-09-17-kotlin-core-fdroid-plan-and-windows-audit.md (sections D/E).
//
// Deliberately plain kotlin("jvm"): no Android plugin, no Chaquopy, no
// android.* imports anywhere under src/. That is the point of this module —
// it has to build and test on a bare JVM so it can eventually ship inside an
// F-Droid build that never touches Chaquopy. JUnit tests here run as normal
// JVM unit tests (no Robolectric, no instrumentation).
//
// NOT wired into :app yet — see the PR body for why (Phase 1 is a spike;
// :app keeps using the Python/Chaquopy path until a later phase cuts over).
plugins {
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        // Matches :app's jvmTarget (JVM_17) — see app/build.gradle.kts.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

// Interactive live-IMAP harness for a human to run by hand, typing an app
// password at a prompt (never echoed, never read from a file or env var).
// Deliberately NOT part of `test` or any task CI runs — see
// LiveImapHarness.kt and the PR body for how to invoke it.
tasks.register<JavaExec>("liveImapHarness") {
    group = "verification"
    description = "Interactive, human-run IMAP connect+APPEND check against a real mailbox " +
        "(Yahoo only — never Gmail). Prompts for the app password; never touched by CI or `test`."
    mainClass.set("com.chatmailsync.core.mail.LiveImapHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["test"].runtimeClasspath
    standardInput = System.`in`
}
