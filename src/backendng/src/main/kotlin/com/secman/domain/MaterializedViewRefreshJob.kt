package com.secman.domain

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Status enum for refresh jobs
 */
enum class RefreshJobStatus {
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * Entity tracking materialized view refresh operations
 *
 * Tracks state, progress, and result of async refresh jobs.
 * Provides audit trail and observability for refresh operations.
 *
 * Feature: 034-outdated-assets
 * Task: T005
 * Spec reference: data-model.md
 */
@Entity
@Table(
    name = "materialized_view_refresh_job",
    indexes = [
        Index(name = "idx_refresh_job_status", columnList = "status"),
        Index(name = "idx_refresh_job_started", columnList = "started_at")
    ]
)
data class MaterializedViewRefreshJob(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: RefreshJobStatus = RefreshJobStatus.RUNNING,

    @Column(name = "triggered_by", nullable = false, length = 50)
    var triggeredBy: String,

    @Column(name = "started_at", nullable = false)
    var startedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,

    @Column(name = "assets_processed", nullable = false)
    var assetsProcessed: Int = 0,

    @Column(name = "total_assets", nullable = false)
    var totalAssets: Int = 0,

    @Column(name = "progress_percentage", nullable = false)
    var progressPercentage: Int = 0,

    @Column(name = "error_message", length = 1000)
    var errorMessage: String? = null,

    @Column(name = "duration_ms")
    var durationMs: Long? = null
) {
    /**
     * Update progress and calculate percentage
     *
     * Task: T005
     */
    fun updateProgress(processed: Int) {
        assetsProcessed = processed
        progressPercentage = if (totalAssets > 0) {
            ((processed.toDouble() / totalAssets) * 100).toInt()
        } else {
            0
        }
    }

    /**
     * Mark job as completed and calculate duration
     *
     * Task: T005
     */
    fun markCompleted() {
        status = RefreshJobStatus.COMPLETED
        completedAt = LocalDateTime.now()
        durationMs = java.time.Duration.between(startedAt, completedAt).toMillis()
        progressPercentage = 100
    }

    /**
     * Mark job as failed with error message
     *
     * Task: T005
     */
    fun markFailed(error: String) {
        status = RefreshJobStatus.FAILED
        completedAt = LocalDateTime.now()
        errorMessage = error.take(1000)  // Truncate to column size
        durationMs = java.time.Duration.between(startedAt, completedAt).toMillis()
    }
}
