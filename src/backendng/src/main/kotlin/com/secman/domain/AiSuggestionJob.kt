package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * One row per "start AI pre-fill" action. Lifecycle mirrors ExportJob:
 * QUEUED → RUNNING → (COMPLETED | FAILED | CANCELLED). Heartbeat lets the
 * scheduled cleanup mark abandoned jobs as FAILED.
 */
@Entity
@Table(
    name = "ai_suggestion_job",
    indexes = [
        Index(name = "idx_aijob_assessment", columnList = "risk_assessment_id, status"),
        Index(name = "idx_aijob_status_heartbeat", columnList = "status, last_heartbeat_at")
    ]
)
class AiSuggestionJob(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "risk_assessment_id", nullable = false)
    var riskAssessmentId: Long,

    @Column(name = "triggered_by_user_id", nullable = false)
    var triggeredByUserId: Long,

    @Column(name = "model", nullable = false, length = 128)
    var model: String,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 32)
    var scope: AiSuggestionScope,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: AiSuggestionJobStatus = AiSuggestionJobStatus.QUEUED,

    @Column(name = "total_count", nullable = false)
    var totalCount: Int = 0,

    @Column(name = "completed_count", nullable = false)
    var completedCount: Int = 0,

    @Column(name = "failed_count", nullable = false)
    var failedCount: Int = 0,

    @Column(name = "total_cost_usd", nullable = false, precision = 10, scale = 6)
    var totalCostUsd: BigDecimal = BigDecimal.ZERO,

    @Column(name = "estimated_cost_usd", precision = 10, scale = 6)
    var estimatedCostUsd: BigDecimal? = null,

    @Column(name = "started_at")
    var startedAt: LocalDateTime? = null,

    @Column(name = "finished_at")
    var finishedAt: LocalDateTime? = null,

    @Column(name = "last_heartbeat_at")
    var lastHeartbeatAt: LocalDateTime? = null,

    @Column(name = "error_message", length = 2048)
    var errorMessage: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
) {
    fun progressPercent(): Int {
        if (totalCount == 0) return 0
        return ((completedCount + failedCount) * 100 / totalCount).coerceIn(0, 100)
    }
}
