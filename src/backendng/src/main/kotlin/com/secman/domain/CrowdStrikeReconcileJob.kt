package com.secman.domain

import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Entity for tracking background CrowdStrike reconcile-stale jobs.
 *
 * The reconcile sweep can take longer than a reverse proxy's timeout (real incident:
 * ~65s on a 1.3M-row vulnerability table vs nginx's 60s → 504 to the CLI while the
 * backend completed fine). The sweep therefore runs as a background job: POST returns
 * 202 + jobId immediately and the CLI polls the status endpoint.
 *
 * The request payload (incl. the ~2000-entry queriedHosts list) is NOT persisted —
 * it travels in the executor closure. The row only tracks lifecycle + result.
 */
@Entity
@Table(
    name = "crowdstrike_reconcile_job",
    indexes = [Index(name = "idx_cs_reconcile_job_status", columnList = "status")]
)
class CrowdStrikeReconcileJob(
    @Id
    @Column(length = 36)
    var id: String,

    @Column(nullable = false, length = 50)
    var username: String,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ReconcileJobStatus = ReconcileJobStatus.PENDING,

    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column
    var startedAt: LocalDateTime? = null,

    @Column
    var completedAt: LocalDateTime? = null,

    @Column(length = 1000)
    var errorMessage: String? = null,

    // Result fields, populated on COMPLETED
    @Column
    var rowsDeleted: Int? = null,

    @Column
    var cutoff: LocalDateTime? = null,

    @Column(length = 500)
    var severities: String? = null,

    @Column
    var aborted: Boolean? = null,

    @Column(length = 500)
    var abortReason: String? = null
) {
    fun isRunning(): Boolean {
        return status == ReconcileJobStatus.PENDING || status == ReconcileJobStatus.RUNNING
    }
}

enum class ReconcileJobStatus {
    PENDING,    // Job created, waiting to start
    RUNNING,    // Sweep in progress
    COMPLETED,  // Finished (includes aborted-by-safety-brake sweeps — see aborted flag)
    FAILED      // Failed with error (or auto-failed as stuck)
}
