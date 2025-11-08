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
