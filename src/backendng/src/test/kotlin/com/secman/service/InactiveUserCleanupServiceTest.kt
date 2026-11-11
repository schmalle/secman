package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.config.UserCleanupConfig
import com.secman.domain.User
import com.secman.domain.UserCleanupOutcome
import com.secman.domain.UserCleanupRun
import com.secman.domain.UserCleanupRunItem
import com.secman.dto.UserCleanupItemDto
import com.secman.repository.UserCleanupRunItemRepository
import com.secman.repository.UserCleanupRunRepository
import com.secman.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Failure paths that need a database error on demand, so repositories are mocked. */
class InactiveUserCleanupServiceTest {

    private val config = UserCleanupConfig(enabled = true, dryRun = false, maxDeletePercent = 100)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val exclusions = mockk<InactiveUserExclusions>()
    private val executor = mockk<InactiveUserDeletionExecutor>()
    private val runRepository = mockk<UserCleanupRunRepository>(relaxed = true)
    private val itemRepository = mockk<UserCleanupRunItemRepository>(relaxed = true)

    private val service = InactiveUserCleanupService(
        config, userRepository, exclusions, mockk(relaxed = true), executor,
        runRepository, itemRepository, mockk(relaxed = true), ObjectMapper()
    )

    private val inactive = User(
        id = 7, username = "gone", email = "gone@secman.test", passwordHash = "x",
        lastLogin = Instant.now().minus(Duration.ofDays(400)),
        inactivityWarningSentAt = Instant.now().minus(Duration.ofDays(31))
    )

    @BeforeEach
    fun repositoriesEchoWhatTheySave() {
        every { itemRepository.save(any<UserCleanupRunItem>()) } answers { firstArg() }
        every { runRepository.update(any<UserCleanupRun>()) } answers { firstArg() }
    }

    @Test
    fun `failed run record save does not leave the run guard set`() {
        every { runRepository.save(any<UserCleanupRun>()) } throws IllegalStateException("db down") andThenAnswer { firstArg<UserCleanupRun>() }
        every { userRepository.findIdsWithActivityBefore(any(), any()) } returns emptyList()

        assertThatThrownBy { service.run("first", config) }.isInstanceOf(IllegalStateException::class.java)

        // Before the fix the guard stayed set and this threw UserCleanupInProgressException.
        service.run("second", config)
    }

    @Test
    fun `deleted items are persisted even when the run fails afterwards`() {
        every { runRepository.save(any<UserCleanupRun>()) } answers { firstArg<UserCleanupRun>().apply { id = 99 } }
        every { userRepository.findIdsWithActivityBefore(any(), any()) } returns listOf(7L)
        every { userRepository.findByIdIn(any()) } returns listOf(inactive)
        every { userRepository.count() } returns 1
        every { exclusions.reason(any(), any()) } returns null
        every { executor.deleteIfStillEligible(7, any(), any()) } returns
            UserCleanupItemDto(7, "gone", "gone@secman.test", inactive.lastLogin, UserCleanupOutcome.DELETED)
        every { userRepository.countNoActivityBaseline() } throws IllegalStateException("db hiccup after deletions")

        assertThatThrownBy { service.run("scheduler", config) }.isInstanceOf(IllegalStateException::class.java)

        verify { itemRepository.save(match<UserCleanupRunItem> { it.outcome == UserCleanupOutcome.DELETED && it.email == "gone@secman.test" }) }
    }
}
