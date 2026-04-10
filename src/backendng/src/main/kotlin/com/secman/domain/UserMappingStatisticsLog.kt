package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * Execution log for the `manage-user-mappings list --send-email` CLI invocation.
 * One row per invocation, including dry-runs and zero-recipient failures.
 *
 * Feature: 085-cli-mappings-email
 * Mirrors the [AdminSummaryLog] audit pattern from feature 070.
 */
@Entity
@Table(
    name = "user_mapping_statistics_log",
    indexes = [
        Index(name = "idx_ums_log_executed_at", columnList = "executed_at"),
        Index(name = "idx_ums_log_status", columnList = "status")
    ]
)
@Serdeable
data class UserMappingStatisticsLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "executed_at", nullable = false)
    val executedAt: Instant,

    @Column(name = "invoked_by", nullable = false, length = 255)
    val invokedBy: String,

    @Column(name = "filter_email", length = 255)
    val filterEmail: String? = null,

    @Column(name = "filter_status", length = 20)
    val filterStatus: String? = null,

    @Column(name = "total_users", nullable = false)
    val totalUsers: Int,

    @Column(name = "total_mappings", nullable = false)
    val totalMappings: Int,

    @Column(name = "active_mappings", nullable = false)
    val activeMappings: Int,

    @Column(name = "pending_mappings", nullable = false)
    val pendingMappings: Int,

    @Column(name = "domain_mappings", nullable = false)
    val domainMappings: Int,

    @Column(name = "aws_account_mappings", nullable = false)
    val awsAccountMappings: Int,

    @Column(name = "recipient_count", nullable = false)
    val recipientCount: Int,

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
