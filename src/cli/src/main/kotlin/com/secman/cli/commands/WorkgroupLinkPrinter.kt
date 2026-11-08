package com.secman.cli.commands

import com.secman.cli.service.CliWorkgroupLink
import com.secman.cli.service.CliWorkgroupLinkSummary

/**
 * Renders a workgroup-linking result.
 *
 * Shared by `import`, `import-s3` (where linking is a side effect of the file) and
 * `link-workgroups` (where it is the whole point), so an operator reads the same
 * lines in every place and a change to the wording cannot land in only one.
 *
 * Per-account rows come first and the counters last, so on a 2,000-account import
 * the totals sit next to the exit status instead of scrolling away above it.
 */
object WorkgroupLinkPrinter {

    /**
     * Exit status for a run where nothing failed but workgroups remain that the import
     * cannot repair (no owner, no members, owner conflict). Distinct from 1 so a cron job
     * can alert on real failures without paging on standing source data.
     * 2 and 3 are taken by `import-s3` (fatal S3/argument, unexpected error).
     */
    const val EXIT_ATTENTION = 4

    /**
     * [failures]: accounts this run could not link — drives exit 1.
     * [attention]: workgroups left disabled or contested by source data — drives
     * [EXIT_ATTENTION] when nothing failed.
     */
    data class Outcome(val failures: Int = 0, val attention: Int = 0)

    private val REASON_HINTS = mapOf(
        "MISSING_OWNER" to "no valid owner email in the source (set the cov:owner tag)",
        "NO_MEMBERS" to "the owner has no SecMan user yet",
        "ACCOUNT_MISMATCH" to "the workgroup holds more than this one account",
        "MEMBERSHIP_LIMIT" to "membership exceeds the safety limit"
    )

    /**
     * @param verbose list every account; otherwise only failures and attention rows
     *        (a dry run always lists every account — the preview is the point).
     */
    fun print(summary: CliWorkgroupLinkSummary?, verbose: Boolean = false): Outcome {
        if (summary == null) return Outcome()

        println()
        if (summary.dryRun) {
            println("Workgroup linking (DRY-RUN — no workgroup created, no account assigned):")
        } else {
            println("Workgroup linking:")
        }

        val listed = if (verbose || summary.dryRun) summary.links else summary.links.filter(::needsAttention)
        listed.forEach(::printLink)
        val unlisted = summary.links.size - listed.size
        if (unlisted > 0) println("  ($unlisted account(s) without findings not listed)")

        printCounters(summary)
        printReasons(summary)
        printTruncation(summary)
        printStale(summary)

        return Outcome(
            failures = summary.failed,
            attention = summary.ownerConflicts + maxOf(summary.emptyWorkgroups, summary.disabledWorkgroups)
        )
    }

    /** One line naming every cause of a non-zero exit, so nobody has to scroll for it. */
    fun describeFailures(importErrors: Int, outcome: Outcome, notificationFailed: Boolean = false): String =
        listOfNotNull(
            if (importErrors > 0) "$importErrors import error(s)" else null,
            if (outcome.failures > 0) "${outcome.failures} account(s) failed to link" else null,
            if (notificationFailed) "operator summary email failed" else null
        ).joinToString(", ")

    fun describeAttention(outcome: Outcome): String =
        "${outcome.attention} workgroup(s) need attention (see 'Workgroups not enabled' above); " +
            "exit $EXIT_ATTENTION"

    private fun needsAttention(link: CliWorkgroupLink): Boolean =
        link.error != null || link.ownerOutcome == "CONFLICT" || link.emptyMembership ||
            link.statusReason !in setOf("", "READY")

    private fun printLink(link: CliWorkgroupLink) {
        val where = "${link.awsAccountId}  ->  ${link.workgroupName}"
        if (link.ownerOutcome != "NO_CANDIDATE") println("  Owner: $where: ${link.ownerOutcome}")
        println("  Membership: $where: ${link.memberOutcome}; direct asset links removed${if (link.dryRun) " (planned)" else ""}: ${link.assetsRemoved}")
        if (link.statusOutcome != "NOT_EVALUATED") println("  Status: $where: ${link.statusOutcome} (${link.statusReason})")
        when {
            link.error != null -> println("  ❌ $where: ${link.error}")
            link.alreadyLinked -> println("  ⏭️  $where: already linked")
            link.dryRun && link.workgroupCreated ->
                println("  🆕 $where: would create the workgroup and link the account")
            link.dryRun -> println("  🔗 $where: would link")
            link.workgroupCreated -> println("  🆕 $where: workgroup created, account linked")
            else -> println("  🔗 $where: linked")
        }
    }

    private fun printCounters(summary: CliWorkgroupLinkSummary) {
        println("  Accounts processed: ${summary.processed}")
        println("  Owner assignments${if (summary.dryRun) " planned" else ""}: ${summary.ownersSet}")
        println("  Existing owners preserved: ${summary.ownersPreserved}")
        println("  Owner conflicts: ${summary.ownerConflicts}")
        println("  Owner memberships${if (summary.dryRun) " planned" else " added"}: ${summary.membersAdded}")
        println("  Direct asset links${if (summary.dryRun) " to remove" else " removed"}: ${summary.assetsRemoved}")
        println("  Empty workgroups (disabled; membership unresolved): ${summary.emptyWorkgroups}")
        println("  Workgroups disabled or incomplete${if (summary.dryRun) " (planned)" else ""}: ${summary.disabledWorkgroups}")
        if (summary.workgroupsCreated > 0) {
            val verb = if (summary.dryRun) "would be created" else "created"
            println("  Workgroups $verb:  ${summary.workgroupsCreated}")
        }
        val linkedVerb = if (summary.dryRun) "would be linked" else "linked"
        println("  Accounts $linkedVerb:   ${summary.linked}")
        if (summary.alreadyLinked > 0) {
            println("  Already linked:     ${summary.alreadyLinked}")
        }
        if (summary.failed > 0) {
            println("  ❌ Failed:          ${summary.failed}")
        }
    }

    private fun printReasons(summary: CliWorkgroupLinkSummary) {
        if (summary.statusReasonCounts.isEmpty()) return
        println("  Workgroups not enabled, by reason:")
        summary.statusReasonCounts.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
            val hint = REASON_HINTS[reason]?.let { " — $it" } ?: ""
            println("    $reason: $count$hint")
        }
    }

    private fun printTruncation(summary: CliWorkgroupLinkSummary) {
        if (summary.reportTruncated) {
            println(
                "  ℹ️  Listing covers ${summary.links.size} of ${summary.processed} accounts. Every account " +
                    "was processed; failures and attention items are listed first."
            )
        }
        // Said out loud so a capped run is never mistaken for a complete one. Re-running
        // does not help: the same first accounts would be processed again.
        if (summary.inputTruncated) {
            println(
                "  ⚠️  More accounts exist than one run processes; accounts beyond the backend " +
                    "limit were NOT linked. Split the input to cover the rest."
            )
        }
    }

    private fun printStale(summary: CliWorkgroupLinkSummary) {
        val stale = summary.staleAccounts ?: return
        if (stale == 0) return
        println("  Linked accounts absent from this input: $stale (report only; nothing was unlinked)")
        summary.staleLinks.forEach { link ->
            val state = if (link.workgroupEnabled) "enabled — members keep access" else "disabled"
            println("    ${link.awsAccountId}  ->  ${link.workgroupName} ($state)")
        }
        if (stale > summary.staleLinks.size) println("    … and ${stale - summary.staleLinks.size} more")
    }
}
