package com.chatmailsync.core.mail

import java.io.File

/**
 * One-off, human/agent-run: writes the Kotlin-written `.cmsbackup` that
 * `tests/test_migration_kotlin_bundle.py` imports with the real Python `import_bundle`.
 * Run with `cd android && ./gradlew :core:generateBundleFixtureKotlinWritten`. Never run by
 * `test`, `build`, `assemble` or CI; `MigrationKotlinFixtureTest` fails if the committed file
 * goes stale, which is the cue to run it.
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "usage: BundleFixtureGenerator <output-cmsbackup-path>" }
    val out = File(args[0]).absoluteFile
    out.parentFile?.mkdirs()
    MigrationBundleFixture.build(out)
    println("Wrote $out (${out.length()} bytes)")
}
