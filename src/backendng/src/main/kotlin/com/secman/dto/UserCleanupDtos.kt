package com.secman.dto

import com.secman.domain.UserCleanupOutcome
import com.secman.domain.UserCleanupRunStatus
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/** One user's outcome in a cleanup run or preview. */
@Serdeable
data class UserCleanupItemDto(
    val userId: Long,
    val username: String,
    val email: String,
    val lastActivityAt: Instant?,
    val outcome: UserCleanupOutcome,
    val reason: String? = null,
    val details: Map<String, List<Long>> = emptyMap()
)

/** Result of a run or a preview. [runId] is null for a preview, which persists nothing. */
@Serdeable
data class UserCleanupRunSummary(
    val runId: Long?,
    val status: UserCleanupRunStatus,
    val dryRun: Boolean,
    val candidateCount: Int,
    val deletedCount: Int,
    val skippedCount: Int,
    val warnedCount: Int,
    val errorCount: Int,
    /** True when the safety brake would trip (dry run) or tripped (live run). */
    val brakeTripped: Boolean,
    /** More candidates existed than one run examines; the rest are looked at on later nights. */
    val inputTruncated: Boolean,
    /** Users with neither a login nor a creation time; never candidates, reported for follow-up. */
    val noActivityBaselineCount: Long = 0,
    val orphanedWorkgroupIds: List<Long>,
    val staleOwnerWorkgroupIds: List<Long>,
    val items: List<UserCleanupItemDto> = emptyList()
)

/** Deployed cleanup settings as `GET /api/user-cleanup/config` reports them. */
@Serdeable
data class UserCleanupConfigDto(
    val enabled: Boolean,
    val dryRun: Boolean,
    val inactiveDays: Int,
    val warningDays: Int,
    val maxDeletePercent: Int,
    val maxUsersPerRun: Int,
    val validationError: String?
)
