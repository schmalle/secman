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
/**
 * One device the import run actually queried (Stage-1 device population). Carries the
 * identifiers the backend uses to resolve the device to persisted `Asset` rows: the
 * hostname (short or FQDN, matched like the import's `findPotentialDuplicates`) and,
 * when known, the AWS EC2 instance id.
 */
@Serdeable
data class QueriedHostDto(
    val hostname: String? = null,
    val instanceId: String? = null
)

@Serdeable
data class ReconcileStaleVulnerabilitiesRequest(
    @field:NotNull
    val importStartedAt: LocalDateTime,
    @field:NotEmpty
    val severities: List<String>,
    /**
     * The full set of devices this run actually queried — the Stage-1 device population,
     * INCLUDING hosts that returned zero matching vulnerabilities (the silent-remediation
     * case the sweep exists to clean up). The reconcile DELETE is scoped to assets resolved
     * from these hosts, so a host that legitimately dropped out of the run's scope (e.g.
     * outside the importer's `--last-seen-days` window, or offline) is NEVER deleted.
     *
     * Fail-safe: when null or empty the sweep deletes nothing. This degrades a partial
     * deploy (new backend + old CLI that doesn't send this) to "no cleanup" rather than
     * the previous "delete every stale row across all assets".
     */
    val queriedHosts: List<QueriedHostDto>? = null
)

@Serdeable
data class ReconcileStaleVulnerabilitiesResponse(
    val rowsDeleted: Int,
    val cutoff: LocalDateTime,
    val severities: List<String>,
    val owner: String,
    /**
     * True when the zero-refresh safety brake fired: the run refreshed no rows for the
     * swept severities while stale candidates existed, so the bulk DELETE was refused to
     * avoid wiping the entire prior population (suspected empty/failed run). `rowsDeleted`
     * is 0 in this case. Surfaced so cron/operators can distinguish a healthy "nothing to
     * clean" (aborted=false, rowsDeleted=0) from a refused-dangerous sweep.
     */
    val aborted: Boolean = false,
    val abortReason: String? = null
)

/**
 * Service-layer result of a reconcile sweep. Carries the deleted-row count plus whether
 * the zero-refresh brake aborted the sweep, so the controller can surface it to the CLI
 * without leaning on a sentinel count.
 */
@Serdeable
data class ReconcileStaleResult(
    val rowsDeleted: Int,
    val aborted: Boolean = false,
    val abortReason: String? = null
)
