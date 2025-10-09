package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Instant

/**
 * UserMapping entity - stores mappings between user emails, AWS account IDs, and domains
 * 
 * Feature: 013-user-mapping-upload
 * Purpose: Enable role-based access control across multiple AWS accounts and domains
 * 
 * Business Rules:
 * - One email can map to multiple AWS accounts (many-to-many)
 * - One email can map to multiple domains (many-to-many)
 * - Unique constraint on (email, awsAccountId, domain) prevents duplicates
 * - Email and domain are normalized to lowercase for case-insensitive matching
 * - AWS account IDs must be exactly 12 numeric digits
 * 
 * Example Mappings:
 * - john@example.com → 123456789012 → example.com
 * - john@example.com → 987654321098 → example.com (same user, different AWS account)
 * - john@example.com → 123456789012 → other.com (same user, different domain)
 * 
 * Related to: Feature 013 (User Mapping Upload)
 */
@Entity
@Table(
    name = "user_mapping",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_mapping_composite",
            columnNames = ["email", "aws_account_id", "domain"]
        )
    ],
    indexes = [
        Index(name = "idx_user_mapping_email", columnList = "email"),
        Index(name = "idx_user_mapping_aws_account", columnList = "aws_account_id"),
        Index(name = "idx_user_mapping_domain", columnList = "domain"),
        Index(name = "idx_user_mapping_email_aws", columnList = "email,aws_account_id")
    ]
)
@Serdeable
data class UserMapping(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, length = 255)
    @Email(message = "Invalid email format")
    @NotBlank(message = "Email address is required")
    var email: String,

    @Column(name = "aws_account_id", nullable = false, length = 12)
    @NotBlank(message = "AWS Account ID is required")
    @Pattern(regexp = "^\\d{12}$", message = "AWS Account ID must be exactly 12 numeric digits")
    var awsAccountId: String,

    @Column(nullable = false, length = 255)
    @NotBlank(message = "Domain is required")
    @Pattern(regexp = "^[a-z0-9.-]+$", message = "Domain must contain only lowercase letters, numbers, dots, and hyphens")
    var domain: String,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    /**
     * Lifecycle callback - executed before entity is persisted to database
     * Normalizes email and domain to lowercase, trims whitespace
     */
    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
        // Normalize email and domain to lowercase for case-insensitive matching
        email = email.lowercase().trim()
        domain = domain.lowercase().trim()
        awsAccountId = awsAccountId.trim()
    }

    /**
     * Lifecycle callback - executed before entity is updated in database
     * Updates the updatedAt timestamp
     */
    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    override fun toString(): String {
        return "UserMapping(id=$id, email='$email', awsAccountId='$awsAccountId', domain='$domain')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserMapping) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
