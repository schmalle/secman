package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Pattern
import java.time.Instant

/**
 * AwsAccount — admin-supplied display name for a 12-digit AWS account ID.
 *
 * Rows are created lazily when an admin names an account or when an account is
 * selected as a risk-assessment basis. A row without a display name still
 * appears in reports under its bare ID; see AccountFindingAgeService.resolveName.
 *
 * Spec: docs/superpowers/specs/2026-07-26-account-finding-age-design.md
 */
@Entity
@Table(
    name = "aws_account",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_aws_account_account_id", columnNames = ["aws_account_id"])
    ]
)
@Serdeable
data class AwsAccount(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "aws_account_id", nullable = false, length = 12)
    @Pattern(regexp = "^\\d{12}$", message = "AWS Account ID must be exactly 12 numeric digits")
    var awsAccountId: String,

    @Column(name = "name", nullable = true, length = 255)
    var name: String? = null,

    @Column(name = "updated_at", nullable = true)
    var updatedAt: Instant? = null,

    @Column(name = "updated_by", nullable = true, length = 255)
    var updatedBy: String? = null
) {
    @PrePersist
    fun onCreate() {
        updatedAt = Instant.now()
        awsAccountId = awsAccountId.trim()
        name = name?.trim()?.ifBlank { null }
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
        name = name?.trim()?.ifBlank { null }
    }
}
