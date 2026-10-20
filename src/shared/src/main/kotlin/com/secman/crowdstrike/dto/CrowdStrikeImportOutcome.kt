package com.secman.crowdstrike.dto

import io.micronaut.serde.annotation.Serdeable

/** Counts cover the requested severities, before supported-CVE filtering and deduplication. */
@Serdeable
data class CrowdStrikeFindingCoverage(
    val fetched: Int,
    val accepted: Int,
    val missingCve: Int = 0,
    val duplicates: Int = 0,
    val invalid: Int = 0,
    val complete: Boolean = true
)

@Serdeable
data class CrowdStrikeHostImportOutcome(
    val hostname: String,
    val status: String,
    val assetId: Long? = null,
    val formerInstanceId: String? = null,
    val instanceId: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val coverage: CrowdStrikeFindingCoverage? = null,
    val aid: String? = null,
    val accountId: String? = null,
    val recoveryRequested: Boolean = false,
    val recoveryAttempts: Int = 0,
    val recoveryStatus: String? = null,
    val recoveryReason: String? = null,
    val recoveryAssetId: Long? = null
)

/** Plain operator diagnostic, also usable by clients that do not render structured outcomes. */
fun CrowdStrikeHostImportOutcome.recoverySummary(): String? = if (!recoveryRequested) null else
    "Delete-on-fail: ${recoveryStatus ?: "UNKNOWN"}; attempts=$recoveryAttempts/2" +
        (recoveryAssetId?.let { "; target asset=$it" } ?: "") +
        (recoveryReason?.let { "; reason=$it" } ?: "")
