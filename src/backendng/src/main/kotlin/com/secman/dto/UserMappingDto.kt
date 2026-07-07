package com.secman.dto

import com.secman.domain.IpRangeType
import com.secman.domain.UserMapping
import io.micronaut.serde.annotation.Serdeable

/**
 * User Mapping Response DTO
 * Features: 013-user-mapping-upload, 020-i-want-to (IP mapping), 042-future-user-mappings
 */
@Serdeable
data class UserMappingResponse(
    val id: Long,
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val ipAddress: String?,
    val ipRangeType: IpRangeType?,
    val ipCount: Long?,
    val userId: Long?,                 // Feature 042: Nullable user reference
    val appliedAt: String?,             // Feature 042: Timestamp when mapping was applied
    val isFutureMapping: Boolean,       // Feature 042: True if user=null AND appliedAt=null
    val createdAt: String,
    val updatedAt: String
)

@Serdeable
data class CreateUserMappingRequest(
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val ipAddress: String?
)

@Serdeable
data class UpdateUserMappingRequest(
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val ipAddress: String?
)

@Serdeable
data class BulkUserMappingEntry(
    val email: String,
    val awsAccountId: String? = null,
    val domain: String? = null
)

@Serdeable
data class BulkUserMappingRequest(
    val mappings: List<BulkUserMappingEntry>,
    val dryRun: Boolean = false,
    val notifyNewAccounts: Boolean = false,
    val notifyAddress: String? = null,
    /**
     * When true, a risk assessment is started for the owner of every
     * brand-new (DB-wide) AWS account created by this import.
     * Requires [riskAssessmentUseCase]; deadline defaults to 7 days.
     */
    val startRiskAssessment: Boolean = false,
    /** Name of the use case the auto-started risk assessments are based on. */
    val riskAssessmentUseCase: String? = null,
    /** Days from today until the risk assessment deadline (endDate). Default 7. */
    val riskAssessmentDeadlineDays: Int? = null
)

@Serdeable
data class NewAccountImportInfo(
    val awsAccountId: String,
    val emails: List<String>
)

/**
 * Outcome of auto-starting a risk assessment for one (new AWS account, owner)
 * pair during a mapping import. Exactly one of [riskAssessmentId] / [error]
 * is meaningful: id set on success, error message set on per-item failure.
 */
@Serdeable
data class AccountRiskAssessmentInfo(
    val awsAccountId: String,
    val ownerEmail: String,
    val riskAssessmentId: Long? = null,
    val assessor: String? = null,
    val endDate: String? = null,
    val error: String? = null
)

@Serdeable
data class BulkUserMappingResponse(
    val totalProcessed: Int,
    val created: Int,
    val createdPending: Int,
    val skipped: Int,
    val errors: List<String>,
    val comparison: MappingComparisonResponse? = null,
    val newAccounts: List<NewAccountImportInfo> = emptyList(),
    val notificationSent: Boolean = false,
    val notificationRecipient: String? = null,
    val notificationError: String? = null,
    /** Auto-started risk assessments (one entry per new account/owner pair). */
    val riskAssessments: List<AccountRiskAssessmentInfo> = emptyList()
)

@Serdeable
data class MappingComparisonResponse(
    val dbMappingCount: Int,
    val fileMappingCount: Int,
    val newCount: Int,
    val unchangedCount: Int,
    val removedCount: Int
)

/**
 * Convert UserMapping entity to UserMappingResponse DTO
 * Feature 042: Extended to include user reference and appliedAt timestamp
 */
fun UserMapping.toResponse(): UserMappingResponse {
    val ipCount = if (ipRangeStart != null && ipRangeEnd != null) {
        ipRangeEnd!! - ipRangeStart!! + 1
    } else {
        null
    }

    return UserMappingResponse(
        id = this.id!!,
        email = this.email,
        awsAccountId = this.awsAccountId,
        domain = this.domain,
        ipAddress = this.ipAddress,
        ipRangeType = this.ipRangeType,
        ipCount = ipCount,
        userId = this.user?.id,                            // Feature 042
        appliedAt = this.appliedAt?.toString(),            // Feature 042
        isFutureMapping = this.isFutureMapping(),          // Feature 042
        createdAt = this.createdAt.toString(),
        updatedAt = this.updatedAt.toString()
    )
}
