package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * AssetDeletionAuditLog entity for permanent audit trail of asset cascade deletion operations.
 *
 * Immutable audit records - no UPDATE or DELETE operations allowed after creation.
 * Provides complete traceability for compliance, debugging, and data recovery.
 *
 * Related to: Feature 033-cascade-asset-deletion (FR-011 - Audit logging)
 *
 * @property id Unique identifier
 * @property assetId ID of deleted asset (preserved after deletion)
 * @property assetName Name of deleted asset (preserved)
 * @property deletedByUser Username who performed deletion
 * @property deletionTimestamp When deletion occurred (indexed)
 * @property vulnerabilitiesCount Number of vulnerabilities deleted
 * @property assetExceptionsCount Number of ASSET-type exceptions deleted
 * @property exceptionRequestsCount Number of exception requests deleted
 * @property deletedVulnerabilityIds JSON array of vulnerability IDs deleted
 * @property deletedExceptionIds JSON array of exception IDs deleted
 * @property deletedRequestIds JSON array of request IDs deleted
 * @property operationType SINGLE or BULK operation
 * @property bulkOperationId UUID for bulk operations (correlates multiple deletions)
 */
@Entity
@Table(
    name = "asset_deletion_audit_log",
    indexes = [
        Index(name = "idx_audit_asset", columnList = "asset_id"),
        Index(name = "idx_audit_user", columnList = "deleted_by_user"),
        Index(name = "idx_audit_deletion_timestamp", columnList = "deletion_timestamp"),
        Index(name = "idx_audit_bulk_op", columnList = "bulk_operation_id")
    ]
)
@Serdeable
data class AssetDeletionAuditLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /**
     * ID of the deleted asset
     * Not a foreign key to preserve audit logs after asset deletion
     */
    @Column(name = "asset_id", nullable = false)
    @NotNull
    var assetId: Long,

    /**
     * Name of the deleted asset (preserved for historical reference)
     */
    @Column(name = "asset_name", nullable = false, length = 255)
    @NotBlank
    @Size(max = 255)
    var assetName: String,

    /**
     * Username of user who performed the deletion (denormalized for audit trail)
     * Preserved even if user account deleted
     */
    @Column(name = "deleted_by_user", nullable = false, length = 255)
    @NotBlank
    @Size(max = 255)
    var deletedByUser: String,

    /**
     * When the deletion occurred (indexed for compliance reporting)
     */
    @Column(name = "deletion_timestamp", nullable = false)
    @NotNull
    var deletionTimestamp: LocalDateTime? = null,

    /**
     * Number of vulnerabilities cascade deleted
     */
    @Column(name = "vulnerabilities_count", nullable = false)
    @NotNull
    var vulnerabilitiesCount: Int = 0,

    /**
     * Number of ASSET-type exceptions cascade deleted
     */
    @Column(name = "asset_exceptions_count", nullable = false)
    @NotNull
    var assetExceptionsCount: Int = 0,

    /**
     * Number of exception requests cascade deleted
     */
    @Column(name = "exception_requests_count", nullable = false)
    @NotNull
    var exceptionRequestsCount: Int = 0,

    /**
     * JSON array of vulnerability IDs deleted
     * Example: [12345, 12346, 12347]
     * Stored as TEXT to support large arrays (up to 1000+ IDs)
     */
    @Column(name = "deleted_vulnerability_ids", columnDefinition = "TEXT", nullable = false)
    @NotBlank
    var deletedVulnerabilityIds: String = "[]",

    /**
     * JSON array of exception IDs deleted
     * Example: [501, 502]
     */
    @Column(name = "deleted_exception_ids", columnDefinition = "TEXT", nullable = false)
    @NotBlank
    var deletedExceptionIds: String = "[]",

    /**
     * JSON array of exception request IDs deleted
     * Example: [7001, 7002, 7003]
     */
    @Column(name = "deleted_request_ids", columnDefinition = "TEXT", nullable = false)
    @NotBlank
    var deletedRequestIds: String = "[]",

    /**
     * Type of operation: SINGLE asset deletion or BULK deletion
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20)
    @NotNull
    var operationType: DeletionOperationType = DeletionOperationType.SINGLE,

    /**
     * UUID for bulk operations to correlate multiple asset deletions
     * Nullable for single asset deletions
     */
    @Column(name = "bulk_operation_id", length = 36)
    @Size(max = 36)
    var bulkOperationId: String? = null
) {
    @PrePersist
    fun onCreate() {
        if (deletionTimestamp == null) {
            deletionTimestamp = LocalDateTime.now()
        }
    }

    override fun toString(): String {
        return "AssetDeletionAuditLog(id=$id, assetId=$assetId, assetName='$assetName', " +
                "deletedByUser='$deletedByUser', timestamp=$deletionTimestamp, " +
                "vulnerabilities=$vulnerabilitiesCount, exceptions=$assetExceptionsCount, " +
                "requests=$exceptionRequestsCount, operationType=$operationType)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssetDeletionAuditLog) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}

/**
 * Type of asset deletion operation
 */
enum class DeletionOperationType {
    /**
     * Single asset deletion (DELETE /api/assets/{id})
     */
    SINGLE,

    /**
     * Bulk asset deletion (DELETE /api/assets/bulk)
     */
    BULK
}
