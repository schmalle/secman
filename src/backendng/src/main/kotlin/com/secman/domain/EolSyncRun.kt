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
        Index(name = "idx_eol_sync_run_started", columnList = "started_at")
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

    /** SUCCESS | PARTIAL | FAILED */
    @Column(name = "status", nullable = false, length = 32)
    var status: String = "SUCCESS",

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

    @Column(name = "error_summary", length = 1024)
    var errorSummary: String? = null,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "finished_at")
    var finishedAt: Instant? = null
)
