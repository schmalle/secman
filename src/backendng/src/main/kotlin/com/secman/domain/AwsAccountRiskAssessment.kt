package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

/**
 * Tracks a risk assessment that was automatically started for the owner of a
 * brand-new AWS account during a user-mapping import
 * (CLI: `manage-user-mappings import --start-risk-assessment`).
 *
 * Purpose:
 * - Audit trail: which import-detected account/owner pair triggered which assessment.
 * - Reminder state: the deadline reminders (2 days and 1 day before the risk
 *   assessment's end date) are sent exactly once each, surviving restarts,
 *   by stamping [reminderTwoDaysSentAt] / [reminderOneDaySentAt].
 *
 * Only assessments created through the import flow are tracked here, so the
 * reminder scheduler never touches manually created risk assessments.
 */
@Entity
@Table(
    name = "aws_account_risk_assessment",
    indexes = [
        Index(name = "idx_aws_acct_ra_account", columnList = "aws_account_id"),
        Index(name = "idx_aws_acct_ra_assessment", columnList = "risk_assessment_id")
    ]
)
@Serdeable
data class AwsAccountRiskAssessment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "aws_account_id", nullable = false, length = 12)
    @NotBlank
    var awsAccountId: String,

    @Column(name = "owner_email", nullable = false, length = 255)
    @NotBlank
    var ownerEmail: String,

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "risk_assessment_id", nullable = false)
    var riskAssessment: RiskAssessment,

    @Column(name = "use_case_name", nullable = false, length = 255)
    @NotBlank
    var useCaseName: String,

    /** When the "2 days before deadline" reminder was sent. NULL = not yet sent. */
    @Column(name = "reminder_two_days_sent_at")
    var reminderTwoDaysSentAt: LocalDateTime? = null,

    /** When the "1 day before deadline" reminder was sent. NULL = not yet sent. */
    @Column(name = "reminder_one_day_sent_at")
    var reminderOneDaySentAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null
) {
    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
    }

    override fun toString(): String {
        return "AwsAccountRiskAssessment(id=$id, awsAccountId='$awsAccountId', ownerEmail='$ownerEmail', riskAssessmentId=${riskAssessment.id})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AwsAccountRiskAssessment) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
