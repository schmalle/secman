package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/** The two paths that make a user a recipient of `notify-users-overdue`. */
enum class OverdueMatchType { DIRECT_ASSIGNMENT, AWS_WORKGROUP }

/** One reason an asset reaches a user; [awsAccountId] is set only for [OverdueMatchType.AWS_WORKGROUP]. */
@Serdeable
data class OverdueMatchReason(
    val type: OverdueMatchType,
    val workgroupId: Long,
    val workgroupName: String,
    val awsAccountId: String? = null
)

/** One overdue asset as a recipient sees it: severity counts plus every path that reached them. */
@Serdeable
data class OverdueAssetEntry(
    val assetId: Long,
    val assetName: String,
    val ipAddress: String?,
    val awsAccountId: String?,
    val overdueCount: Int,
    val criticalCount: Int,
    val highCount: Int,
    val mediumCount: Int,
    val lowCount: Int,
    val oldestDays: Long,
    val reasons: List<OverdueMatchReason>
)

/** A deduplicated recipient: one email address, each asset listed once. */
@Serdeable
data class OverdueRecipient(
    val userId: Long,
    val email: String,
    val assets: List<OverdueAssetEntry>
)

/** A direct workgroup membership and what it contributes; a disabled group contributes nothing. */
@Serdeable
data class OverdueWorkgroupPath(
    val workgroupId: Long,
    val name: String,
    val enabled: Boolean,
    val assignedAssetCount: Long,
    val awsAccountIds: List<String>
)

/** Why `show-notified-users` would or would not mail the user; each value has its own CLI message. */
enum class NotifiedUserStatus { USER_NOT_FOUND, USER_DISABLED, NO_PATHS, NO_OVERDUE_ASSETS, WOULD_BE_NOTIFIED }

/** `show-notified-users` report: the sender's dry run for one user plus their workgroups. */
@Serdeable
data class NotifiedUserOverview(
    val email: String,
    val status: NotifiedUserStatus,
    val userId: Long? = null,
    val username: String? = null,
    val thresholdDays: Int,
    val observationWindowStartUtc: String,
    val observationWindowEndUtc: String,
    val alreadyNotifiedToday: Boolean = false,
    val workgroups: List<OverdueWorkgroupPath> = emptyList(),
    val assets: List<OverdueAssetEntry> = emptyList(),
    val totalAssets: Int = 0,
    val totalOverdue: Int = 0,
    val totalCritical: Int = 0,
    val totalHigh: Int = 0
)

/** Per-recipient result of a `notify-users-overdue` run. */
enum class OverdueRecipientOutcome { WOULD_SEND, SENT, FAILED, SKIPPED_ALREADY_NOTIFIED }

/** One recipient line of the run summary; no mail content, only counts and workgroup names. */
@Serdeable
data class OverdueRecipientResult(
    val email: String,
    val outcome: OverdueRecipientOutcome,
    val assetCount: Int,
    val overdueCount: Int,
    val workgroups: List<String>
)

/** `notify-users-overdue` response; [status] is DRY_RUN, SUCCESS, PARTIAL_FAILURE or FAILURE. */
@Serdeable
data class OverdueNotificationRunResult(
    val status: String,
    val dryRun: Boolean,
    val thresholdDays: Int,
    val notificationUser: String? = null,
    val notificationUserExists: Boolean? = null,
    val recipientsResolved: Int,
    val emailsSent: Int,
    val emailsFailed: Int,
    val skipped: Int,
    val recipients: List<OverdueRecipientResult>
)

/** `POST /api/overdue-notifications/send` body; the email filter is optional. */
@Serdeable
data class OverdueNotificationRunRequest(
    val thresholdDays: Int = 30,
    val dryRun: Boolean = false,
    val notificationUser: String? = null
)
