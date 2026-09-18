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
// A fourth source set holding only StateFixtureGenerator.kt -- a one-off,
// human/agent-run entry point that writes a synthetic sync_state.db using
// the real Kotlin StateRepository/SqliteJdbcStateDb, so a Python pytest can
// prove it can read a database Kotlin (not Python) wrote -- the other half
// of the cross-language DB-compatibility proof this PR's brief requires
// (see StateDbGoldenParityTest.kt for the Python-writes/Kotlin-reads half).
// It needs SqliteJdbcStateDb, which lives in `test` (it is a test-only
// StateDb backed by the testImplementation-only sqlite-jdbc dependency), so
// its classpath includes both `main`'s and `test`'s output -- mirroring the
// `harness` source set's pattern above, but reaching one source set further.
// Never part of `main`, `test`, `build`, or `assemble` -- see the
// generateStateFixtureKotlinWritten task below.
sourceSets {
    create("harness") {
        kotlin.srcDir("src/harness/kotlin")
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += sourceSets["main"].output + sourceSets["main"].runtimeClasspath
    }
    create("fixtureGen") {
        kotlin.srcDir("src/fixtureGen/kotlin")
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output +
            sourceSets["main"].compileClasspath + sourceSets["test"].compileClasspath
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output +
            sourceSets["main"].runtimeClasspath + sourceSets["test"].runtimeClasspath
    }
}

dependencies {
    "harnessImplementation"(kotlin("stdlib"))
    "fixtureGenImplementation"(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    // JVM-only SQLite driver, testImplementation ONLY -- never shipped in the
    // app. Backs StateDb's JVM test implementation (SqliteJdbcStateDb) so
    // StateRepository (State.kt) can be exercised against a real .db file,
    // including ones Python's state.py actually wrote. See StateDb.kt's doc
    // comment and the plan document, section H.6.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
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

// One-off run: writes the Kotlin-side cross-language DB fixture to
// tests/fixtures/state_fixture_kotlin_written.db (repo root, where the
// Python pytest suite reads it from). Deliberately NOT wired into `test`,
// `build`, or `assemble` -- run it by hand only when StateFixtureGenerator.kt
// or the schema it writes changes:
//   cd android && ./gradlew :core:generateStateFixtureKotlinWritten
tasks.register<JavaExec>("generateStateFixtureKotlinWritten") {
    group = "verification"
    description = "One-off: writes tests/fixtures/state_fixture_kotlin_written.db via the real " +
        "Kotlin StateRepository, for the Python pytest suite to read (proves Python can read a " +
        "Kotlin-written database). Never touched by CI, build, test, or assemble."
    mainClass.set("com.chatmailsync.core.mail.StateFixtureGeneratorKt")
    classpath = sourceSets["fixtureGen"].runtimeClasspath
    args = listOf(projectDir.resolve("../../tests/fixtures/state_fixture_kotlin_written.db").absolutePath)
    dependsOn("fixtureGenClasses")
}
