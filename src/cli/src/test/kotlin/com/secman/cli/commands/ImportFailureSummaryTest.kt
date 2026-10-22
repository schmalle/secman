package com.secman.cli.commands

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ImportFailureSummaryTest {
    private fun summary(names: List<String>): String {
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes).use { printFailedImportHostnames(names, it) }
        return bytes.toString(Charsets.UTF_8)
    }

    @Test
    fun `all 101 names are printed despite the error detail limit`() {
        val names = (1..101).map { "server-${it.toString().padStart(3, '0')}" }
        val output = summary(names)
        assertThat(output).contains("Failed import hostnames (101, comma-separated)")
        assertThat(output.lineSequence().filter { it.startsWith("server-") }.single())
            .isEqualTo(names.joinToString(","))
    }

    @Test
    fun `names are trimmed deduplicated without regard to case and sorted`() {
        assertThat(summary(listOf(" beta ", "Alpha", "BETA", "", "  ", "Alpha")))
            .isEqualTo("\n--- Failed import hostnames (2, comma-separated) ---\nAlpha,beta\n")
    }

    @Test
    fun `empty failure list produces no failure heading`() {
        assertThat(summary(emptyList())).isEmpty()
        assertThat(summary(listOf(" ", ""))).isEmpty()
    }

    @Test
    fun `embedded newlines cannot forge extra output lines`() {
        assertThat(summary(listOf("host\r\nforged")))
            .isEqualTo("\n--- Failed import hostnames (1, comma-separated) ---\nhost__forged\n")
    }
}
