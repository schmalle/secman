package com.secman.dto

import com.secman.domain.UserMapping
import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class UserMappingResponse(
    val id: Long,
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val createdAt: String,
    val updatedAt: String
)

@Serdeable
data class CreateUserMappingRequest(
    val awsAccountId: String?,
    val domain: String?
)

@Serdeable
data class UpdateUserMappingRequest(
    val awsAccountId: String?,
    val domain: String?
)

fun UserMapping.toResponse(): UserMappingResponse {
    return UserMappingResponse(
        id = this.id!!,
        email = this.email,
        awsAccountId = this.awsAccountId,
        domain = this.domain,
        createdAt = this.createdAt.toString(),
        updatedAt = this.updatedAt.toString()
    )
}
