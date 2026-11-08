package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Outcome of linking one AWS account to the workgroup named after its display name.
 *
 * Deliberately the same three shapes as [AccountRiskAssessmentInfo] and
 * [AccountOnboardingInfo], so every surface — the CLI printout, the MCP result,
 * the REST response — renders all of them by one rule:
 *
 * - **done**    — [error] null, [skipped] false. [linked] true when a new assignment
 *                 was made, [alreadyLinked] true when it already existed (membership
 *                 and status are still reconciled). [workgroupCreated] says whether the workgroup itself had to be
 *                 created for this account.
 * - **skipped** — [skipped] true and [skipReason] set. Not a failure; callers must not
 *                 let these drive a non-zero exit status.
 * - **failed**  — [error] set. The usual cause is a display name that cannot be a
 *                 workgroup name at all (see WorkgroupAccountLinkService).
 *
 * [dryRun] entries describe what *would* have happened; no workgroup was created and
 * no assignment was written.
 */
@Serdeable
data class WorkgroupAccountLinkInfo(
    val awsAccountId: String,
    val displayName: String,
    /** The resolved workgroup name — always "aws-" + [displayName]. */
    val workgroupName: String,
    val workgroupId: Long? = null,
    val workgroupCreated: Boolean = false,
    val linked: Boolean = false,
    val alreadyLinked: Boolean = false,
    val dryRun: Boolean = false,
    val skipped: Boolean = false,
    val skipReason: String? = null,
    val error: String? = null,
    val ownerOutcome: String = "NO_CANDIDATE",
    val memberOutcome: String = "NO_MATCH",
    val assetsRemoved: Long = 0,
    val emptyMembership: Boolean = false,
    val statusOutcome: String = "NOT_EVALUATED",
    val statusReason: String = ""
)

/**
 * Aggregate result of a linking run.
 *
 * The counters cover every processed pair; [links] is capped at
 * `WorkgroupAccountLinkService.MAX_REPORTED_LINKS` so a 5,000-account import cannot
 * return a 5,000-element array. Failures and workgroups needing attention are sorted
 * ahead of clean rows before the cap, so the cap only ever hides clean rows.
 *
 * Two different caps, reported separately because they mean different things:
 * - [reportTruncated]: every pair was processed, only [links] is shortened.
 * - [inputTruncated]: more pairs existed than one run processes; the rest were NOT linked.
 * [truncated] is kept as `reportTruncated || inputTruncated` for older clients.
 *
 * [statusReasonCounts] counts the not-READY reconciliation reasons over every processed
 * pair (e.g. MISSING_OWNER, NO_MEMBERS), so a capped [links] list still explains them.
 * [staleAccounts] counts accounts linked to an AWS-managed workgroup but absent from
 * this run's input; [staleLinks] samples them. Report only — nothing is unlinked.
 * Null when it could not be computed (input or stored links over the cap).
 */
@Serdeable
data class WorkgroupAccountLinkSummary(
    val processed: Int = 0,
    val workgroupsCreated: Int = 0,
    val linked: Int = 0,
    val alreadyLinked: Int = 0,
    val failed: Int = 0,
    val dryRun: Boolean = false,
    val links: List<WorkgroupAccountLinkInfo> = emptyList(),
    val truncated: Boolean = false,
    val ownersSet: Int = 0,
    val ownersPreserved: Int = 0,
    val ownerConflicts: Int = 0,
    val membersAdded: Int = 0,
    val assetsRemoved: Long = 0,
    val emptyWorkgroups: Int = 0,
    val disabledWorkgroups: Int = 0,
    val reportTruncated: Boolean = false,
    val inputTruncated: Boolean = false,
    val statusReasonCounts: Map<String, Int> = emptyMap(),
    val staleAccounts: Int? = null,
    val staleLinks: List<StaleWorkgroupAccountLink> = emptyList()
)

/** An account still linked to an AWS-managed workgroup that the import no longer lists. */
@Serdeable
data class StaleWorkgroupAccountLink(
    val awsAccountId: String,
    val workgroupName: String,
    val workgroupEnabled: Boolean
)

/**
 * Request body of `POST /api/user-mappings/link-workgroup-accounts` — the correction
 * path that re-links from what is already stored, with no source file involved.
 */
@Serdeable
data class LinkWorkgroupAccountsRequest(
    val dryRun: Boolean = false
)
