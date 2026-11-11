package com.secman.service

import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Records that a user was active, for the inactive-user cleanup.
 *
 * Bookkeeping only: a database hiccup here is logged and swallowed so it can never turn a
 * valid login or MCP call into a failure.
 */
@Singleton
open class UserActivityService(private val userRepository: UserRepository) {

    private val log = LoggerFactory.getLogger(UserActivityService::class.java)

    /** A direct login (password, OAuth, passkey) always moves last_login. */
    open fun recordLogin(userId: Long) = touch(userId, Instant.now(), Duration.ZERO)

    /** MCP delegation counts as activity but is written at most once per day per user. */
    open fun recordDelegatedActivity(userId: Long) = touch(userId, Instant.now(), DELEGATION_THROTTLE)

    private fun touch(userId: Long, now: Instant, throttle: Duration) {
        try {
            userRepository.touchLastLogin(userId, now, now.minus(throttle))
        } catch (e: Exception) {
            log.warn("Could not record activity for userId={}: {}", userId, e.javaClass.simpleName)
        }
    }

    private companion object {
        val DELEGATION_THROTTLE: Duration = Duration.ofHours(24)
    }
}
