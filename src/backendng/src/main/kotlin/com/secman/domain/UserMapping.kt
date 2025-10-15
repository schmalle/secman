package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Instant

/**
 * UserMapping entity - stores mappings between user emails, AWS account IDs, domains, and IP addresses
 *
 * Features: 013-user-mapping-upload, 020-i-want-to (IP address mapping)
 * Purpose: Enable role-based access control across multiple AWS accounts, domains, and IP addresses
 *
 * Business Rules:
 * - Email is REQUIRED
 * - At least one of AWS Account ID, Domain, or IP Address must be provided
 * - One email can map to multiple AWS accounts (many-to-many)
 * - One email can map to multiple domains (many-to-many)
 * - One email can map to multiple IP addresses/ranges (many-to-many)
 * - Unique constraint on (email, awsAccountId, domain, ipAddress) prevents duplicates
 * - Email and domain are normalized to lowercase for case-insensitive matching
 * - AWS account IDs must be exactly 12 numeric digits when provided
 * - IP addresses support three formats: single (192.168.1.100), CIDR (192.168.1.0/24), dash range (192.168.1.1-192.168.1.100)
 *
 * Example Mappings:
 * - john@example.com → 123456789012 → null → null (email + AWS account only)
 * - john@example.com → null → example.com → null (email + domain only)
 * - john@example.com → null → null → 192.168.1.0/24 (email + IP range)
 * - john@example.com → 123456789012 → example.com → 192.168.1.0/24 (email + AWS + domain + IP)
 *
 * Related to: Feature 013 (User Mapping Upload), Feature 020 (IP Address Mapping)
 */
@Entity
@Table(
    name = "user_mapping",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_mapping_composite",
            columnNames = ["email", "aws_account_id", "domain", "ip_address"]
        )
    ],
    indexes = [
        Index(name = "idx_user_mapping_email", columnList = "email"),
        Index(name = "idx_user_mapping_aws_account", columnList = "aws_account_id"),
        Index(name = "idx_user_mapping_domain", columnList = "domain"),
        Index(name = "idx_user_mapping_email_aws", columnList = "email,aws_account_id"),
        Index(name = "idx_user_mapping_ip_address", columnList = "ip_address"),
        Index(name = "idx_user_mapping_ip_range", columnList = "ip_range_start,ip_range_end"),
        Index(name = "idx_user_mapping_email_ip", columnList = "email,ip_address")
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

    @Column(name = "aws_account_id", nullable = true, length = 12)
    @Pattern(regexp = "^\\d{12}$", message = "AWS Account ID must be exactly 12 numeric digits")
    var awsAccountId: String?,

    @Column(nullable = true, length = 255)
    @Pattern(regexp = "^[a-z0-9.-]+$", message = "Domain must contain only lowercase letters, numbers, dots, and hyphens")
    var domain: String?,

    // IP Address Mapping Fields (Feature 020)
    @Column(name = "ip_address", nullable = true, length = 100)
    var ipAddress: String? = null,

    @Column(name = "ip_range_type", nullable = true, length = 20)
    @Enumerated(EnumType.STRING)
    var ipRangeType: IpRangeType? = null,

    @Column(name = "ip_range_start", nullable = true)
    var ipRangeStart: Long? = null,

    @Column(name = "ip_range_end", nullable = true)
    var ipRangeEnd: Long? = null,

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
        domain = domain?.lowercase()?.trim()
        awsAccountId = awsAccountId?.trim()
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
        return "UserMapping(id=$id, email='$email', awsAccountId='$awsAccountId', domain='$domain', ipAddress='$ipAddress', ipRangeType=$ipRangeType)"
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
