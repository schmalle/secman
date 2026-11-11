package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** DRY_RUN: nothing sent or deleted. ABORTED_BRAKE: warnings sent, deletions refused. FAILED: threw mid-run. */
@Serdeable
enum class UserCleanupRunStatus { COMPLETED, DRY_RUN, ABORTED_BRAKE, FAILED }

/** One inactive-user cleanup run (scheduled or `run-now`). Kept indefinitely. */
@Entity
@Serdeable
@Table(name = "user_cleanup_run")
data class UserCleanupRun(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "triggered_by", nullable = false)
    var triggeredBy: String,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant,

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: UserCleanupRunStatus,

    @Column(name = "dry_run", nullable = false)
    var dryRun: Boolean,

    @Column(name = "inactive_days", nullable = false)
    var inactiveDays: Int,

    @Column(name = "candidate_count", nullable = false)
    var candidateCount: Int = 0,

    @Column(name = "deleted_count", nullable = false)
    var deletedCount: Int = 0,

    @Column(name = "skipped_count", nullable = false)
    var skippedCount: Int = 0,

    @Column(name = "warned_count", nullable = false)
    var warnedCount: Int = 0,

    @Column(name = "error_count", nullable = false)
    var errorCount: Int = 0,

    /** Orphaned / stale-owner workgroup ids and truncation flags, as JSON. */
    @Column(name = "summary_json", columnDefinition = "TEXT")
    var summaryJson: String? = null
)
