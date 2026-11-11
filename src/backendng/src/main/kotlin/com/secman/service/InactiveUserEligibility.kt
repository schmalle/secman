package com.secman.service

import com.secman.config.UserCleanupConfig
import com.secman.domain.User
import java.time.Duration
import java.time.Instant

/** Pure date rules of the inactive-user cleanup; all instants are UTC. */
object InactiveUserEligibility {

    /** Last login, or account creation for someone who never logged in. */
    fun lastActivity(user: User): Instant? = user.lastLogin ?: user.createdAt

    /** Strictly older than [inactiveDays]; a user without any timestamp is never inactive. */
    fun isInactive(user: User, now: Instant, inactiveDays: Int): Boolean =
        lastActivity(user)?.isBefore(now.minus(Duration.ofDays(inactiveDays.toLong()))) ?: false

    /** A warning only counts if it was sent after the last activity. */
    private fun currentWarning(user: User): Instant? {
        val warning = user.inactivityWarningSentAt ?: return null
        val activity = lastActivity(user) ?: return null
        return warning.takeIf { it.isAfter(activity) }
    }

    /** A current warning that is at least [warningDays] old authorises deletion. */
    fun hasValidWarning(user: User, now: Instant, warningDays: Int): Boolean =
        currentWarning(user)?.let { !it.isAfter(now.minus(Duration.ofDays(warningDays.toLong()))) } ?: false

    /** Inside the warning window and not yet warned since the last activity. */
    fun needsWarning(user: User, now: Instant, config: UserCleanupConfig): Boolean =
        currentWarning(user) == null &&
            isInactive(user, now, config.inactiveDays - config.warningDays)

    /**
     * Earliest deletion date for a user warned at [now]: the inactivity deadline, but never
     * sooner than [UserCleanupConfig.warningDays] after the warning.
     */
    fun deletionDate(user: User, now: Instant, config: UserCleanupConfig): Instant {
        val deadline = (lastActivity(user) ?: now).plus(Duration.ofDays(config.inactiveDays.toLong()))
        val earliest = now.plus(Duration.ofDays(config.warningDays.toLong()))
        return maxOf(deadline, earliest)
    }
}
