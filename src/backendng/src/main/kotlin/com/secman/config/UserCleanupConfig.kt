package com.secman.config

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.serde.annotation.Serdeable

/**
 * Inactive user cleanup (`docs/USER_CLEANUP.md`). Off and in dry-run by default: an
 * operator opts in per environment, looks at the dry-run report, then turns dry-run off.
 */
@ConfigurationProperties("secman.user-cleanup")
@Serdeable
data class UserCleanupConfig(
    val enabled: Boolean = false,
    val dryRun: Boolean = true,
    val inactiveDays: Int = 365,
    /** The warning goes out this many days before deletion; deletion needs a warning this old. */
    val warningDays: Int = 30,
    /** Abort the deletion phase when more than this share of all users would be deleted. */
    val maxDeletePercent: Int = 10,
    val maxUsersPerRun: Int = 500
) {
    /** Null when valid, otherwise why the run must refuse to start. */
    fun validate(): String? = when {
        inactiveDays <= 0 -> "inactive-days must be > 0"
        warningDays <= 0 -> "warning-days must be > 0"
        warningDays >= inactiveDays -> "warning-days must be smaller than inactive-days"
        maxDeletePercent !in 0..100 -> "max-delete-percent must be between 0 and 100"
        maxUsersPerRun <= 0 -> "max-users-per-run must be > 0"
        else -> null
    }
}
