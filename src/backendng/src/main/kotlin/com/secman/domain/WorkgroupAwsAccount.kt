package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Pattern
import java.time.Instant

/**
 * WorkgroupAwsAccount entity — assigns an AWS cloud account ID to a workgroup.
 *
 * Spec: docs/superpowers/specs/2026-04-28-workgroup-aws-account-assignment-design.md
 *
 * Semantics:
 * - Adds access rule #9: a user sees an asset if Asset.cloudAccountId matches
 *   any AWS account assigned to a workgroup the user is a direct member of.
 * - Direct membership only — does not propagate through workgroup hierarchy.
 * - The same awsAccountId may be assigned to multiple workgroups.
 *
 * Business rules:
 * - awsAccountId must be exactly 12 numeric digits (matches UserMapping pattern).
 * - createdBy records the admin who granted access, for audit traceability.
 * - Unique constraint on (workgroup_id, aws_account_id) prevents duplicates.
 */
@Entity
@Table(
    name = "workgroup_aws_account",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_workgroup_aws_account",
            columnNames = ["workgroup_id", "aws_account_id"]
        )
    ],
    indexes = [
        Index(name = "idx_wg_aws_workgroup", columnList = "workgroup_id"),
        Index(name = "idx_wg_aws_account_id", columnList = "aws_account_id")
    ]
)
@Serdeable
data class WorkgroupAwsAccount(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workgroup_id", nullable = false)
    var workgroup: Workgroup,

    @Column(name = "aws_account_id", nullable = false, length = 12)
    @Pattern(regexp = "^\\d{12}$", message = "AWS Account ID must be exactly 12 numeric digits")
    var awsAccountId: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    var createdBy: User,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkgroupAwsAccount) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String =
        "WorkgroupAwsAccount(id=$id, workgroupId=${workgroup.id}, awsAccountId='$awsAccountId')"
}
