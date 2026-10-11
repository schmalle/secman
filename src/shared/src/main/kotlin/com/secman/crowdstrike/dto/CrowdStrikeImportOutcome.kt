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
    val coverage: CrowdStrikeFindingCoverage? = null
)
