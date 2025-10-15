package com.secman.dto

import com.secman.domain.IpRangeType
import com.secman.domain.UserMapping
import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class UserMappingResponse(
    val id: Long,
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val ipAddress: String?,
    val ipRangeType: IpRangeType?,
    val ipCount: Long?,
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
        createdAt = this.createdAt.toString(),
        updatedAt = this.updatedAt.toString()
    )
}
