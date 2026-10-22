package com.secman.cli.commands

import java.io.PrintStream
import java.util.Locale

/** Keep the copyable hostname list independent of the truncated error details. */
internal fun printFailedImportHostnames(hostnames: Iterable<String>, output: PrintStream = System.err) {
    val names = hostnames.map { it.trim().replace('\r', '_').replace('\n', '_') }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
    if (names.isEmpty()) return
    output.println("\n--- Failed import hostnames (${names.size}, comma-separated) ---")
    output.println(names.joinToString(","))
}
