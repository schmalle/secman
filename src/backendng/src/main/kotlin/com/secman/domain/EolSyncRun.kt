package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

/**
 * Audit record of one EOL catalogue download and/or matching scan.
 *
 * Satisfies the A09 requirement that imports carry actor + target + outcome:
 * [triggeredBy] is the authenticated username, [sourceKey] the upstream
 * catalogue, and [status] the outcome. [errorSummary] holds a short, already
 * sanitized message — never an upstream response body or stack trace.
 */
@Entity
@Table(
    name = "eol_sync_run",
    indexes = [
        Index(name = "idx_eol_sync_run_started", columnList = "started_at"),
        // runId is the handle a polling client holds. Unique so the lookup can
        // return Optional without risking NonUniqueResultException, and so a
        // duplicate can never be introduced by a future non-UUID generator.
        Index(name = "idx_eol_sync_run_run_id", columnList = "run_id", unique = true),
        // The concurrency guard reads by status on every trigger.
        Index(name = "idx_eol_sync_run_status", columnList = "status")
    ]
)
@Serdeable
data class EolSyncRun(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "run_id", nullable = false, length = 64)
    var runId: String = "",

    @Column(name = "source_key", nullable = false, length = 64)
    var sourceKey: String = EolProduct.DEFAULT_SOURCE_KEY,

    @Column(name = "triggered_by", nullable = false, length = 255)
    var triggeredBy: String = "",

    /**
     * RUNNING | SUCCESS | PARTIAL | FAILED.
     *
     * RUNNING is written the moment the run is accepted and is the only
     * non-terminal value. It exists because the sync is dispatched
     * asynchronously: the row *is* the job record clients poll, so it has to be
     * visible before the work finishes, not written once at the end.
     */
    @Column(name = "status", nullable = false, length = 32)
    var status: String = "SUCCESS",

    @Column(name = "products_requested", nullable = false)
    var productsRequested: Int = 0,

    @Column(name = "products_synced", nullable = false)
    var productsSynced: Int = 0,

    @Column(name = "releases_synced", nullable = false)
    var releasesSynced: Int = 0,

    @Column(name = "assets_scanned", nullable = false)
    var assetsScanned: Int = 0,

    @Column(name = "repositories_scanned", nullable = false)
    var repositoriesScanned: Int = 0,

    @Column(name = "findings_written", nullable = false)
    var findingsWritten: Int = 0,

    @Column(name = "findings_removed", nullable = false)
    var findingsRemoved: Int = 0,

    @Column(name = "eol_findings", nullable = false)
    var eolFindings: Int = 0,

    @Column(name = "approaching_findings", nullable = false)
    var approachingFindings: Int = 0,

    /**
     * Upstream product keys that failed to sync, comma-joined and bounded.
     *
     * Stored rather than derived because a polling client asks for the outcome
     * after the worker thread is gone — the in-memory result no longer exists.
     * A delimited column is enough for a list that is empty on a healthy run
     * and is only ever rendered by `secman eol-sync --verbose`.
     */
    @Column(name = "products_failed", length = 2048)
    var productsFailed: String? = null,

    @Column(name = "error_summary", length = 1024)
    var errorSummary: String? = null,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "finished_at")
    var finishedAt: Instant? = null
)
