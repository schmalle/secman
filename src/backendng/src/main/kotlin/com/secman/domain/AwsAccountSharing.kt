package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * AwsAccountSharing entity - stores directional, non-transitive sharing rules
 * for AWS account visibility between users.
 *
 * Feature: AWS Account Sharing
 *
 * Semantics:
 * - sourceUser shares their AWS account visibility with targetUser
 * - targetUser can then see all assets whose cloudAccountId matches
 *   any of sourceUser's AWS account mappings (via user_mapping)
 * - Sharing is directional: A->B does NOT imply B->A
 * - Sharing is NOT transitive: if A->B and B->C, C does NOT see A's accounts via B
 *
 * Business Rules:
 * - sourceUser and targetUser must be different users
 * - Unique constraint on (source_user_id, target_user_id) prevents duplicates
 * - Only ADMIN users can create/manage sharing rules
 * - Application-level cleanup in UserController.delete() removes sharing rules before user deletion
 * - createdBy tracks which admin created the rule for audit purposes
 */
@Entity
@Table(
    name = "aws_account_sharing",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_aws_sharing_source_target",
            columnNames = ["source_user_id", "target_user_id"]
        )
    ],
    indexes = [
        Index(name = "idx_aws_sharing_source", columnList = "source_user_id"),
        Index(name = "idx_aws_sharing_target", columnList = "target_user_id"),
        Index(name = "idx_aws_sharing_created_by", columnList = "created_by_id")
    ]
)
@Serdeable
data class AwsAccountSharing(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_user_id", nullable = false)
    var sourceUser: User,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_user_id", nullable = false)
    var targetUser: User,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    var createdBy: User,

    /**
     * Optional per-account scoping. When empty, the rule shares ALL of the
     * source user's AWS accounts (legacy behavior — preserved so existing
     * rules and any new mappings on the source auto-propagate). When
     * non-empty, only the listed account IDs are visible to the target.
     *
     * Backed by aws_account_sharing_account (see V207). Cascade DELETE on
     * the FK means removing a sharing row drops its scoping rows too.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "aws_account_sharing_account",
        joinColumns = [JoinColumn(name = "sharing_id", nullable = false)]
    )
    @Column(name = "aws_account_id", nullable = false, length = 64)
    var selectedAwsAccountIds: MutableSet<String> = mutableSetOf(),

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
        if (other !is AwsAccountSharing) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String {
        return "AwsAccountSharing(id=$id, sourceUserId=${sourceUser.id}, targetUserId=${targetUser.id})"
    }
}
