package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

/**
 * Request body for POST /api/crowdstrike/servers/reconcile-stale.
 *
 * The caller asserts: "I just finished a CrowdStrike import that started at [importStartedAt]
 * and imported only vulnerabilities with [severities]. Any CrowdStrike-import-owned vulnerability
 * row matching [severities] whose `importTimestamp` is older than [importStartedAt] therefore
 * belongs to a host that disappeared from this import — clear it."
 *
 * This closes the silent-remediation gap where the daily severity-filtered import
 * (`--severity CRITICAL,HIGH`) never visits hosts that no longer have findings at those
 * severities, leaving the previous import's rows stranded indefinitely.
 */
@Serdeable
data class ReconcileStaleVulnerabilitiesRequest(
    @field:NotNull
    val importStartedAt: LocalDateTime,
    @field:NotEmpty
    val severities: List<String>
)

@Serdeable
data class ReconcileStaleVulnerabilitiesResponse(
    val rowsDeleted: Int,
    val cutoff: LocalDateTime,
    val severities: List<String>,
    val owner: String
)
