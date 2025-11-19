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
 * Features: 013-user-mapping-upload, 020-i-want-to (IP address mapping), 042-future-user-mappings
 * Purpose: Enable role-based access control across multiple AWS accounts, domains, and IP addresses
 * Now supports future user mappings - mappings for users who don't yet exist in the system
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
 * - User reference is nullable - allows future user mappings (Feature 042)
 * - AppliedAt tracks when future user mapping was applied to a user (Feature 042)
 *
 * Example Mappings:
 * - john@example.com → 123456789012 → null → null (email + AWS account only)
 * - john@example.com → null → example.com → null (email + domain only)
 * - john@example.com → null → null → 192.168.1.0/24 (email + IP range)
 * - john@example.com → 123456789012 → example.com → 192.168.1.0/24 (email + AWS + domain + IP)
 * - future@example.com → 123456789012 → null → null + user=null + appliedAt=null (future user mapping)
 *
 * Related to: Feature 013 (User Mapping Upload), Feature 020 (IP Address Mapping), Feature 042 (Future User Mappings)
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
        Index(name = "idx_user_mapping_email_ip", columnList = "email,ip_address"),
        Index(name = "idx_user_mapping_applied_at", columnList = "applied_at"), // Feature 042: Efficient filtering for Current vs Applied History tabs
        Index(name = "idx_user_mapping_status", columnList = "status"), // Feature 049: Status filtering
        Index(name = "idx_user_mapping_email_status", columnList = "email,status") // Feature 049: User + status queries
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

    // Feature 042: User reference (nullable for future user mappings)
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "user_id", nullable = true)
    var user: User? = null,

    // Feature 042: Timestamp when mapping was applied to user (null = not yet applied)
    @Column(name = "applied_at", nullable = true)
    var appliedAt: Instant? = null,

    @Column(name = "aws_account_id", nullable = true, length = 12)
    @Pattern(regexp = "^\\d{12}$", message = "AWS Account ID must be exactly 12 numeric digits")
    var awsAccountId: String?,

    @Column(nullable = true, length = 255)
    @Pattern(regexp = "^[a-zA-Z0-9.-]+$", message = "Domain must contain only letters, numbers, dots, and hyphens")
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
    var updatedAt: Instant? = null,

    // Feature 049: Status tracking for pending vs active mappings
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: MappingStatus = MappingStatus.ACTIVE
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

        // Feature 049: Set status based on user existence
        // PENDING if user is null (future user mapping), ACTIVE otherwise
        if (user == null && status == MappingStatus.ACTIVE) {
            status = MappingStatus.PENDING
        }
    }

    /**
     * Lifecycle callback - executed before entity is updated in database
     * Updates the updatedAt timestamp
     */
    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    /**
     * Feature 042: Returns true if this is a future user mapping (not yet applied)
     * A future mapping has no user reference and no appliedAt timestamp
     */
    fun isFutureMapping(): Boolean {
        return user == null && appliedAt == null
    }

    /**
     * Feature 042: Returns true if this is an applied historical mapping
     * An applied mapping has an appliedAt timestamp (regardless of user reference)
     */
    fun isAppliedMapping(): Boolean {
        return appliedAt != null
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
