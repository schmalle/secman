package com.secman.service

import com.secman.config.UserCleanupConfig
import com.secman.domain.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InactiveUserEligibilityTest {

    private val now = Instant.parse("2026-11-11T03:15:00Z")
    private val config = UserCleanupConfig(inactiveDays = 365, warningDays = 30)

    @Test
    fun `null last_login falls back to created_at`() {
        val user = user(lastLogin = null, createdAt = daysAgo(400))

        assertThat(InactiveUserEligibility.lastActivity(user)).isEqualTo(daysAgo(400))
        assertThat(InactiveUserEligibility.isInactive(user, now, 365)).isTrue()
    }

    @Test
    fun `both null is not inactive`() {
        val user = user(lastLogin = null, createdAt = null)

        assertThat(InactiveUserEligibility.isInactive(user, now, 365)).isFalse()
        assertThat(InactiveUserEligibility.needsWarning(user, now, config)).isFalse()
    }

    @Test
    fun `inactive boundary is exclusive at exactly 365 days`() {
        assertThat(InactiveUserEligibility.isInactive(user(lastLogin = daysAgo(365)), now, 365)).isFalse()
        assertThat(InactiveUserEligibility.isInactive(user(lastLogin = daysAgo(365).minusSeconds(1)), now, 365)).isTrue()
    }

    @Test
    fun `warning is due 30 days before the deadline`() {
        assertThat(InactiveUserEligibility.needsWarning(user(lastLogin = daysAgo(336)), now, config)).isTrue()
        assertThat(InactiveUserEligibility.needsWarning(user(lastLogin = daysAgo(334)), now, config)).isFalse()
    }

    @Test
    fun `login after warning invalidates it`() {
        val user = user(lastLogin = daysAgo(370), warningSentAt = daysAgo(380))

        assertThat(InactiveUserEligibility.hasValidWarning(user, now, 30)).isFalse()
        assertThat(InactiveUserEligibility.needsWarning(user, now, config)).isTrue()
    }

    @Test
    fun `warning younger than 30 days is not valid`() {
        val user = user(lastLogin = daysAgo(370), warningSentAt = daysAgo(29))

        assertThat(InactiveUserEligibility.hasValidWarning(user, now, 30)).isFalse()
        assertThat(InactiveUserEligibility.needsWarning(user, now, config)).isFalse()
    }

    @Test
    fun `warning 30 days old is valid`() {
        val user = user(lastLogin = daysAgo(370), warningSentAt = daysAgo(30))

        assertThat(InactiveUserEligibility.hasValidWarning(user, now, 30)).isTrue()
    }

    @Test
    fun `deletion date is the later of deadline and warning plus 30 days`() {
        assertThat(InactiveUserEligibility.deletionDate(user(lastLogin = daysAgo(300)), now, config))
            .isEqualTo(daysAgo(300).plus(Duration.ofDays(365)))
        // Deadline already passed (feature switched on late): the user still gets 30 days.
        assertThat(InactiveUserEligibility.deletionDate(user(lastLogin = daysAgo(500)), now, config))
            .isEqualTo(now.plus(Duration.ofDays(30)))
    }

    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    private fun user(lastLogin: Instant?, createdAt: Instant? = daysAgo(1000), warningSentAt: Instant? = null) = User(
        id = 1, username = "u", email = "u@secman.test", passwordHash = "x",
        createdAt = createdAt, lastLogin = lastLogin, inactivityWarningSentAt = warningSentAt
    )
}
