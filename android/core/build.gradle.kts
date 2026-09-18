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

// A third source set, separate from `main` and `test`, that holds only
// LiveImapHarness.kt (the interactive, human-run, real-network entry
// point). It is never part of `main` — see the harnessJar task below —
// so it can never end up in the `:core` jar a later phase wires the app
// to. It compiles against `main`'s output (ImapTransport, checkConnection,
// etc.) but is otherwise independent of `test`.
sourceSets {
    create("harness") {
        kotlin.srcDir("src/harness/kotlin")
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += sourceSets["main"].output + sourceSets["main"].runtimeClasspath
    }
}

dependencies {
    "harnessImplementation"(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

// A runnable jar bundling the `harness` and `main` classes, with a
// Main-Class manifest entry — built specifically so the harness can be
// launched with a plain `java -jar ...` from a real terminal, bypassing
// the Gradle daemon entirely. Gradle's own JavaExec (the previous approach
// here) never attaches a real console, so System.console() is always null
// there and a masked password prompt is impossible from inside Gradle —
// see LiveImapHarness.kt's doc comment for the full explanation and the
// exact run command. Deliberately NOT part of `test`, `build`, `assemble`,
// or any task CI runs.
tasks.register<Jar>("harnessJar") {
    group = "verification"
    description = "Builds a runnable jar for the interactive, human-run live IMAP harness " +
        "(Yahoo only — never Gmail). Run it with: java -jar core/build/libs/core-harness.jar " +
        "(never through Gradle — see LiveImapHarness.kt for why). Never touched by CI or `test`."
    archiveFileName.set("core-harness.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "com.chatmailsync.core.mail.LiveImapHarnessKt")
    }
    // Fat jar: `main` + `harness` classes, plus every runtime dependency
    // (kotlin-stdlib) unpacked in, so `java -jar core-harness.jar` is a
    // fully standalone invocation from a real terminal -- no extra
    // -classpath wiring for a human to get right by hand.
    from(sourceSets["main"].output)
    from(sourceSets["harness"].output)
    from({
        (sourceSets["harness"].runtimeClasspath - sourceSets["main"].output - sourceSets["harness"].output)
            .filter { it.exists() }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
    dependsOn("harnessClasses")
}
