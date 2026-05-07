package com.secman.dto

import com.secman.domain.AwsAccountSharing
import io.micronaut.serde.annotation.Serdeable

/**
 * Response DTO for AWS Account Sharing rules.
 *
 * Feature: AWS Account Sharing (per-account scoping in V207)
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
    /**
     * Number of AWS accounts effectively shared by this rule.
     * - shareAllAccounts == true  → count of source's mapped accounts
     * - shareAllAccounts == false → size of selectedAwsAccountIds
     */
    val sharedAwsAccountCount: Int,
    /**
     * The explicit account-id selection on this rule. Empty list means the
     * rule shares ALL of the source user's accounts (no scoping).
     */
    val selectedAwsAccountIds: List<String>,
    /**
     * Convenience flag: true iff selectedAwsAccountIds is empty.
     * Lets the UI render "Share all accounts" without re-checking the list.
     */
    val shareAllAccounts: Boolean,
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
 *
 * `awsAccountIds` is optional. When null or empty, the rule shares ALL of
 * the source user's AWS account mappings (legacy behavior). When non-empty,
 * the rule shares ONLY the listed account IDs (must each match an existing
 * mapping for the source user — unknown IDs are dropped server-side).
 */
@Serdeable
data class CreateAwsAccountSharingRequest(
    val sourceUserId: Long? = null,
    val sourceUserEmail: String? = null,
    val targetUserId: Long? = null,
    val targetUserEmail: String? = null,
    val awsAccountIds: List<String>? = null,
    /**
     * When true, the controller treats `targetUserEmail` as a brand-new
     * invite: it must be well-formed, share the caller's domain, and not
     * already correspond to an existing User or PENDING UserMapping.
     * The lazily-created User receives roles [USER, VULN] (not the
     * default [USER, VULN, REQ]) and the notification email contains a
     * "your account was just created" block.
     *
     * When false (default), the legacy create-or-find behavior applies.
     */
    val inviteByEmail: Boolean = false,
)

/**
 * Request DTO for editing the per-account scope of an existing sharing rule.
 *
 * Source and target are immutable on a sharing row (delete + create to
 * change them). Only the account-id selection can be updated in place.
 *
 * Setting `awsAccountIds` to null or empty resets the rule back to "share
 * all of the source user's accounts."
 */
@Serdeable
data class UpdateAwsAccountSharingRequest(
    val awsAccountIds: List<String>? = null,
)

/**
 * Lightweight description of an AWS account belonging to a source user,
 * used to populate the per-account picker on the create/edit form.
 */
@Serdeable
data class SourceAwsAccountResponse(
    val awsAccountId: String,
)

/**
 * Extension function to convert AwsAccountSharing entity to response DTO.
 *
 * @param sharedAwsAccountCount  effective count (see field doc above)
 * @param selectedAwsAccountIds  explicit selection, possibly empty
 */
fun AwsAccountSharing.toResponse(
    sharedAwsAccountCount: Int = 0,
    selectedAwsAccountIds: List<String> = emptyList(),
): AwsAccountSharingResponse {
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
        selectedAwsAccountIds = selectedAwsAccountIds,
        shareAllAccounts = selectedAwsAccountIds.isEmpty(),
        createdAt = this.createdAt?.toString() ?: "",
        updatedAt = this.updatedAt?.toString() ?: ""
    )
}
