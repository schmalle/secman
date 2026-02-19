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
 */
@Serdeable
data class CreateAwsAccountSharingRequest(
    val sourceUserId: Long,
    val targetUserId: Long
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
