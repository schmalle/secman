package com.secman.service

import com.secman.repository.AssetRepository
import com.secman.repository.UserMappingRepository
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
    private val awsAccountSharingService: AwsAccountSharingService
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
}
