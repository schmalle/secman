package com.secman.service

import com.secman.repository.UserRepository
import com.secman.testutil.TestDataFactory
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Activity bookkeeping that the inactive-user cleanup relies on. */
@MicronautTest(environments = ["test"], transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserActivityServiceIntegrationTest {

    @Inject lateinit var activity: UserActivityService
    @Inject lateinit var userRepository: UserRepository

    private val created = mutableListOf<Long>()

    @AfterEach
    fun cleanUp() {
        created.forEach { userRepository.deleteById(it) }
        created.clear()
    }

    @Test
    fun `recordLogin always updates`() {
        val id = userWithLastLogin(Instant.now().minus(Duration.ofMinutes(5)))

        activity.recordLogin(id)

        assertThat(lastLogin(id)).isAfter(Instant.now().minus(Duration.ofMinutes(1)))
    }

    @Test
    fun `delegated activity updates at most once per 24h`() {
        val recent = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS)
        val recentId = userWithLastLogin(recent)
        val staleId = userWithLastLogin(Instant.now().minus(Duration.ofHours(25)))
        val neverId = userWithLastLogin(null)

        activity.recordDelegatedActivity(recentId)
        activity.recordDelegatedActivity(staleId)
        activity.recordDelegatedActivity(neverId)

        assertThat(lastLogin(recentId)).isEqualTo(recent)
        assertThat(lastLogin(staleId)).isAfter(Instant.now().minus(Duration.ofMinutes(1)))
        assertThat(lastLogin(neverId)).isAfter(Instant.now().minus(Duration.ofMinutes(1)))
    }

    private fun userWithLastLogin(lastLogin: Instant?): Long {
        val tag = UUID.randomUUID().toString().take(8)
        val user = TestDataFactory.createRegularUser("act-$tag", "act-$tag@secman.test").apply { this.lastLogin = lastLogin }
        return userRepository.save(user).id!!.also { created += it }
    }

    private fun lastLogin(id: Long): Instant? = userRepository.findById(id).get().lastLogin?.truncatedTo(ChronoUnit.SECONDS)
}
