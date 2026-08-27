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
 *                 was made, [alreadyLinked] true when it already existed (an idempotent
 *                 no-op). [workgroupCreated] says whether the workgroup itself had to be
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
    val error: String? = null
)

/**
 * Aggregate result of a linking run.
 *
 * The counters cover every processed pair; [links] is capped at
 * `WorkgroupAccountLinkService.MAX_REPORTED_LINKS` so a 5,000-account import cannot
 * return a 5,000-element array. [truncated] says the cap was hit — the counters stay
 * accurate either way, and nothing is dropped silently.
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
    val truncated: Boolean = false
)

/**
 * Request body of `POST /api/user-mappings/link-workgroup-accounts` — the correction
 * path that re-links from what is already stored, with no source file involved.
 */
@Serdeable
data class LinkWorkgroupAccountsRequest(
    val dryRun: Boolean = false
)
