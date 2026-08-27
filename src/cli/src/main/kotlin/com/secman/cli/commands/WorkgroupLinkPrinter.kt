package com.secman.cli.commands

import com.secman.cli.service.CliWorkgroupLinkSummary

/**
 * Renders a workgroup-linking result.
 *
 * Shared by `import` (where linking is a side effect of the file) and
 * `link-workgroups` (where it is the whole point), so an operator reads the same
 * lines in both places and a change to the wording cannot land in only one.
 */
object WorkgroupLinkPrinter {

    /**
     * Returns the number of accounts that failed to link — the caller's exit-status
     * input. Accounts reported as `alreadyLinked` are idempotent no-ops and never
     * count as failures.
     */
    fun print(summary: CliWorkgroupLinkSummary?): Int {
        if (summary == null) return 0

        println()
        if (summary.dryRun) {
            println("Workgroup linking (DRY-RUN — no workgroup created, no account assigned):")
        } else {
            println("Workgroup linking:")
        }
        println("  Accounts processed: ${summary.processed}")
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

        summary.links.forEach { link ->
            val where = "${link.awsAccountId}  ->  ${link.workgroupName}"
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

        // Said out loud so a capped run is never mistaken for a complete one.
        if (summary.truncated) {
            println(
                "  ⚠️  More accounts exist than this run reported. Re-run to continue; " +
                    "the operation is idempotent."
            )
        }

        return summary.failed
    }
}
