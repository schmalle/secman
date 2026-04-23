package com.secman.dto

import com.secman.domain.AwsAccountSharing
import io.micronaut.serde.annotation.Serdeable

/**
 * Response DTO for AWS Account Sharing rules.
 *
 * Feature: AWS Account Sharing
 */
@Serdeable
data class AwsAccountSharingResponse(
    val id: Long,
    val sourceUserId: Long,
    val sourceUserEmail: String,
    val sourceUserUsername: String,
    val targetUserId: Long,
    val targetUserEmail: String,
    val targetUserUsername: String,
    val createdById: Long,
    val createdByUsername: String,
    val sharedAwsAccountCount: Int,
    val createdAt: String,
    val updatedAt: String
)

/**
 * Request DTO for creating an AWS Account Sharing rule.
 *
 * Either `*UserId` or `*UserEmail` must be provided for each side; when only
 * email is given (for a "pending" user known via UserMapping but never
 * logged in), the backend will find-or-create a User record before saving
 * the sharing rule.
 */
@Serdeable
data class CreateAwsAccountSharingRequest(
    val sourceUserId: Long? = null,
    val sourceUserEmail: String? = null,
    val targetUserId: Long? = null,
    val targetUserEmail: String? = null
)

/**
 * Extension function to convert AwsAccountSharing entity to response DTO.
 */
fun AwsAccountSharing.toResponse(sharedAwsAccountCount: Int = 0): AwsAccountSharingResponse {
    return AwsAccountSharingResponse(
        id = this.id!!,
        sourceUserId = this.sourceUser.id!!,
        sourceUserEmail = this.sourceUser.email,
        sourceUserUsername = this.sourceUser.username,
        targetUserId = this.targetUser.id!!,
        targetUserEmail = this.targetUser.email,
        targetUserUsername = this.targetUser.username,
        createdById = this.createdBy.id!!,
        createdByUsername = this.createdBy.username,
        sharedAwsAccountCount = sharedAwsAccountCount,
        createdAt = this.createdAt?.toString() ?: "",
        updatedAt = this.updatedAt?.toString() ?: ""
    )
}
