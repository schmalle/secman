package com.secman.service

import com.secman.repository.AssetRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import jakarta.inject.Singleton

/**
 * Resolves the full set of recipient emails who should be notified about
 * vulnerabilities/outdated assets belonging to a given AWS account.
 *
 * Single source of truth for vulnerability-notification recipient fan-out,
 * shared by both notification flows:
 *  - overdue vulnerabilities (UserVulnerabilityNotificationService)
 *  - outdated assets (NotificationService via CliController)
 *
 * Recipients are the deduplicated, case-insensitive union of:
 *  1. The AWS account owner(s) — direct UserMapping rows for the account.
 *  2. Members of any workgroup that contains an asset in the account
 *     (asset → workgroup → users).
 *  3. Users granted access to the account via the AWS sharing feature
 *     (directional, honoring per-rule account selection).
 */
@Singleton
open class AwsAccountRecipientResolver(
    private val userMappingRepository: UserMappingRepository,
    private val assetRepository: AssetRepository,
    private val awsAccountSharingService: AwsAccountSharingService,
    private val workgroupAwsAccountRepository: WorkgroupAwsAccountRepository
) {
    /**
     * @param awsAccountId the AWS account id (asset.cloudAccountId). Blank input
     *   yields an empty set — no recipients can be resolved without an account.
     */
    open fun resolveAwsAccountRecipients(awsAccountId: String): Set<String> {
        if (awsAccountId.isBlank()) return emptySet()

        val recipients = mutableSetOf<String>()

        // 1. AWS account owner(s) via direct UserMapping
        userMappingRepository.findByAwsAccountId(awsAccountId).forEach { mapping ->
            recipients.add(mapping.email.lowercase())
        }

        // 2. Members of workgroups that contain an asset in this account
        assetRepository.findDistinctWorkgroupMemberEmailsByCloudAccountId(awsAccountId).forEach { email ->
            recipients.add(email.lowercase())
        }

        // 3. Users with access to the account via the sharing feature
        awsAccountSharingService.getTargetUserEmailsForAwsAccount(awsAccountId).forEach { email ->
            recipients.add(email.lowercase())
        }

        return recipients
    }

    /**
     * `--notall` variant of [resolveAwsAccountRecipients] backing the global
     * restricted fan-out: recipients are the users whose `--notall` access set
     * contains this account — the per-account inverse of
     * `UserVulnerabilityNotificationService.getRestrictedAwsAccountIds`. Mirrors
     * every AWS-account access path in `AssetFilterService.getAccessibleAssets`
     * except AD-domain-only access (no `cloudAccountId`):
     *
     * 1. Users owning an asset in the account (manual creator, scan uploader,
     *    or the asset's `owner` field matching their username).
     * 2. Members of any workgroup that contains an asset in the account.
     * 3. The AWS account owner(s) — direct UserMapping rows for the account.
     * 4. Members of any workgroup the account is assigned to (WorkgroupAwsAccount).
     * 5. Users granted access via the AWS sharing feature.
     */
    open fun resolveRestrictedAwsAccountRecipients(awsAccountId: String): Set<String> {
        if (awsAccountId.isBlank()) return emptySet()

        val recipients = mutableSetOf<String>()

        // 1. Users owning an asset in this account (manual creator, scan uploader, owner field)
        assetRepository.findDistinctAssetOwnershipEmailsByCloudAccountId(awsAccountId).forEach { email ->
            recipients.add(email.lowercase())
        }

        // 2. Members of workgroups that contain an asset in this account
        assetRepository.findDistinctWorkgroupMemberEmailsByCloudAccountId(awsAccountId).forEach { email ->
            recipients.add(email.lowercase())
        }

        // 3. AWS account owner(s) via direct UserMapping
        userMappingRepository.findByAwsAccountId(awsAccountId).forEach { mapping ->
            recipients.add(mapping.email.lowercase())
        }

        // 4. Members of workgroups this account is assigned to (WorkgroupAwsAccount)
        workgroupAwsAccountRepository.findDistinctMemberEmailsByAwsAccountId(awsAccountId).forEach { email ->
            recipients.add(email.lowercase())
        }

        // 5. Users with access to the account via the sharing feature
        awsAccountSharingService.getTargetUserEmailsForAwsAccount(awsAccountId).forEach { email ->
            recipients.add(email.lowercase())
        }

        return recipients
    }
}
