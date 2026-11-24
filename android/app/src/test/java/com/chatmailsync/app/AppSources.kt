package com.chatmailsync.app

import java.io.File

/*
 * KT-13 step 4 (D242 = T1): the source-text tests used to read MainActivity.kt alone. The route bodies now live in
 * the Nav*.kt files beside it, so every such test reads this one helper instead: MainActivity.kt first, then every
 * file in the same folder whose name starts with "Nav" and ends in ".kt", sorted by name. A new Nav*.kt file is
 * picked up by name with no list to forget to update, and the route guard also fails if a composable(...) is
 * registered in a file this helper does not read (see RouteCoverageGuardTest).
 */

/** The folder holding MainActivity.kt, found from the test's working directory (the module dir or the repo's android dir). */
internal fun appMainSourceDir(): File {
    val rel = "src/main/java/com/chatmailsync/app"
    return generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, rel), File(it, "app/$rel")) }
        .first { File(it, "MainActivity.kt").isFile }
}

/** The files [appMainSources] reads, in order. */
internal fun appMainSourceFiles(dir: File = appMainSourceDir()): List<File> {
    val nav = dir.listFiles { f -> f.isFile && f.name.startsWith("Nav") && f.name.endsWith(".kt") }.orEmpty().sortedBy { it.name }
    return listOf(File(dir, "MainActivity.kt")) + nav
}

/** MainActivity.kt plus every Nav*.kt beside it, as one text. */
internal fun appMainSources(dir: File = appMainSourceDir()): String =
    appMainSourceFiles(dir).joinToString("\n") { it.readText() }

/** The route names registered by `composable("...")` (or `composable(route = "...")`) in [text]. */
internal fun composableRoutesIn(text: String): Set<String> =
    Regex("""composable\(\s*(?:route\s*=\s*)?"([^"]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
