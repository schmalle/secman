package com.secman.cli.commands

import com.secman.cli.commands.WorkgroupLinkPrinter.Outcome
import com.secman.cli.service.CliStaleWorkgroupLink
import com.secman.cli.service.CliWorkgroupLink
import com.secman.cli.service.CliWorkgroupLinkSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkgroupLinkPrinterTest {

    private fun captured(block: () -> Unit): String {
        val original = System.out
        val output = java.io.ByteArrayOutputStream()
        try {
            System.setOut(java.io.PrintStream(output))
            block()
        } finally {
            System.setOut(original)
        }
        return output.toString()
    }

    private fun clean(id: String) = CliWorkgroupLink(id, "Clean-$id", "aws-Clean-$id",
        alreadyLinked = true, statusOutcome = "UNCHANGED_ENABLED", statusReason = "READY")

    private fun ownerless(id: String) = CliWorkgroupLink(id, "Sandbox-$id", "aws-Sandbox-$id",
        alreadyLinked = true, emptyMembership = true, statusOutcome = "UNCHANGED_DISABLED", statusReason = "MISSING_OWNER")

    @Test
    fun `disabled groups are attention, not failures, and empty groups are not double counted`() {
        assertEquals(Outcome(failures = 0, attention = 2),
            WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(disabledWorkgroups = 2, emptyWorkgroups = 1)))
    }

    @Test
    fun `failed links are failures and owner conflicts are attention`() {
        assertEquals(Outcome(failures = 1, attention = 3),
            WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(failed = 1, ownerConflicts = 2, emptyWorkgroups = 1)))
    }

    @Test
    fun `status preview and reason are printed`() {
        val out = captured {
            WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(dryRun = true, links = listOf(
                CliWorkgroupLink("000000000019", "Test", "aws-Test",
                    dryRun = true, statusOutcome = "WOULD_ENABLE", statusReason = "READY"))))
        }
        assertTrue(out.contains("WOULD_ENABLE (READY)"))
    }

    @Test
    fun `empty membership needs attention even when the account is linked`() {
        assertEquals(Outcome(attention = 1), WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(emptyWorkgroups = 1)))
    }

    @Test
    fun `successful reconciliation and dry run are clean`() {
        assertEquals(Outcome(), WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(membersAdded = 1, assetsRemoved = 3)))
        assertEquals(Outcome(), WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(dryRun = true, membersAdded = 1, assetsRemoved = 3)))
    }

    @Test
    fun `clean rows are hidden by default, listed with verbose, and the counters come last`() {
        val summary = CliWorkgroupLinkSummary(processed = 2, links = listOf(clean("000000000001"), ownerless("000000000002")))

        val quiet = captured { WorkgroupLinkPrinter.print(summary) }
        assertFalse(quiet.contains("aws-Clean-000000000001"))
        assertTrue(quiet.contains("aws-Sandbox-000000000002"))
        assertTrue(quiet.contains("1 account(s) without findings not listed"))
        assertTrue(quiet.indexOf("aws-Sandbox-000000000002") < quiet.indexOf("Accounts processed"))

        val verbose = captured { WorkgroupLinkPrinter.print(summary, verbose = true) }
        assertTrue(verbose.contains("aws-Clean-000000000001"))
    }

    @Test
    fun `a capped listing does not tell the operator to re-run`() {
        val out = captured {
            WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(processed = 2211, truncated = true, reportTruncated = true,
                links = List(500) { clean("%012d".format(it)) }))
        }
        assertTrue(out.contains("Listing covers 500 of 2211 accounts"))
        assertFalse(out.contains("Re-run"))
        assertFalse(out.contains("NOT linked"))
    }

    @Test
    fun `a capped input says the rest was not linked`() {
        val out = captured {
            WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(truncated = true, inputTruncated = true))
        }
        assertTrue(out.contains("were NOT linked"))
    }

    @Test
    fun `reasons and stale links are explained`() {
        val out = captured {
            WorkgroupLinkPrinter.print(CliWorkgroupLinkSummary(
                statusReasonCounts = mapOf("MISSING_OWNER" to 28),
                staleAccounts = 3,
                staleLinks = listOf(CliStaleWorkgroupLink("111111111111", "aws-closed", true))
            ))
        }
        assertTrue(out.contains("MISSING_OWNER: 28 — no valid owner email"))
        assertTrue(out.contains("Linked accounts absent from this input: 3"))
        assertTrue(out.contains("111111111111  ->  aws-closed (enabled — members keep access)"))
        assertTrue(out.contains("… and 2 more"))
    }

    @Test
    fun `failure description names every cause`() {
        assertEquals("2 import error(s), 1 account(s) failed to link, operator summary email failed",
            WorkgroupLinkPrinter.describeFailures(2, Outcome(failures = 1), notificationFailed = true))
    }
}
