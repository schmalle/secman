package com.secman.cli.commands

import com.secman.cli.service.CliWorkgroupLinkSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkgroupLinkPrinterTest {
    @Test
    fun `disabled groups including safety limits cause failure without double counting empty groups`() {
        assertEquals(2, WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(disabledWorkgroups = 2, emptyWorkgroups = 1)))
    }

    @Test
    fun `status preview and reason are printed`() {
        val original = System.out
        val output = java.io.ByteArrayOutputStream()
        try {
            System.setOut(java.io.PrintStream(output))
            WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(dryRun = true, links = listOf(
                com.secman.cli.service.CliWorkgroupLink("000000000019", "Test", "aws-Test",
                    dryRun = true, statusOutcome = "WOULD_ENABLE", statusReason = "READY"))))
            org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("WOULD_ENABLE (READY)"))
        } finally {
            System.setOut(original)
        }
    }
    @Test
    fun `empty membership makes the import incomplete even when the account is linked`() {
        assertEquals(1, WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(emptyWorkgroups = 1)))
    }

    @Test
    fun `successful reconciliation and dry run are not failures`() {
        assertEquals(0, WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(membersAdded = 1, assetsRemoved = 3)))
        assertEquals(0, WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(dryRun = true, membersAdded = 1, assetsRemoved = 3)))
    }
}
