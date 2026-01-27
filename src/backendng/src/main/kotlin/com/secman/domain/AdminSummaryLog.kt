package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * Execution log for admin summary email sends.
 * One record per CLI execution.
 * Feature: 070-admin-summary-email
 */
@Entity
@Table(
    name = "admin_summary_log",
    indexes = [
        Index(name = "idx_admin_summary_log_executed_at", columnList = "executed_at")
    ]
)
@Serdeable
data class AdminSummaryLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "executed_at", nullable = false)
    val executedAt: Instant,

    @Column(name = "recipient_count", nullable = false)
    val recipientCount: Int,

    @Column(name = "user_count", nullable = false)
    val userCount: Long,

    @Column(name = "vulnerability_count", nullable = false)
    val vulnerabilityCount: Long,

    @Column(name = "asset_count", nullable = false)
    val assetCount: Long,

    @Column(name = "emails_sent", nullable = false)
    val emailsSent: Int = 0,

    @Column(name = "emails_failed", nullable = false)
    val emailsFailed: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: ExecutionStatus,

    @Column(name = "dry_run", nullable = false)
    val dryRun: Boolean = false
)

/**
 * Execution status for admin summary email sends.
 * Feature: 070-admin-summary-email
 */
enum class ExecutionStatus {
    /** All emails sent successfully */
    SUCCESS,
    /** Some emails failed, some succeeded */
    PARTIAL_FAILURE,
    /** All emails failed or no recipients found */
    FAILURE,
    /** Dry run mode, no emails sent */
    DRY_RUN
}
