package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Serdeable
enum class CrowdStrikeCleanupStatus {
    SUCCESS,
    PARTIAL,
    ABORTED_SAFETY_BRAKE,
    FAILED
}

@Entity
@Serdeable
@Table(
    name = "crowdstrike_cleanup_run",
    indexes = [
        Index(name = "idx_cs_cleanup_started", columnList = "started_at"),
        Index(name = "idx_cs_cleanup_status", columnList = "status")
    ]
)
data class CrowdStrikeCleanupRun(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: CrowdStrikeCleanupStatus,

    @Column(name = "triggered_by", nullable = false, length = 100)
    var triggeredBy: String,

    @Column(name = "stale_days", nullable = false)
    var staleDays: Int,

    @Column(name = "cutoff", nullable = false)
    var cutoff: LocalDateTime,

    @Column(name = "candidate_count", nullable = false)
    var candidateCount: Int = 0,

    @Column(name = "deleted_count", nullable = false)
    var deletedCount: Int = 0,

    @Column(name = "error_count", nullable = false)
    var errorCount: Int = 0,

    @Column(name = "total_crowdstrike_tracked", nullable = false)
    var totalCrowdStrikeTracked: Long = 0,

    @Column(name = "started_at", nullable = false)
    var startedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,

    @Column(name = "duration_ms")
    var durationMs: Long? = null,

    @Column(name = "error_message", length = 1000)
    var errorMessage: String? = null
)
