package com.secman.scheduler

import com.secman.config.UserCleanupConfig
import com.secman.service.InactiveUserCleanupService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The cron must honour the enabled flag and pass the deployed config, brake included. */
class InactiveUserCleanupSchedulerTest {

    private val service = mockk<InactiveUserCleanupService>(relaxed = true)

    @Test
    fun `disabled config does not run`() {
        val scheduler = InactiveUserCleanupScheduler(service, UserCleanupConfig(enabled = false))

        scheduler.runScheduledCleanup()

        verify(exactly = 0) { service.run(any(), any()) }
        assertThatThrownBy { scheduler.runWithScheduledPolicy("manual:admin") }
            .isInstanceOf(UserCleanupDisabledException::class.java)
    }

    @Test
    fun `enabled run uses the deployed config including the brake`() {
        val config = UserCleanupConfig(enabled = true, maxDeletePercent = 7)
        every { service.run(any(), any()) } returns mockk(relaxed = true)

        InactiveUserCleanupScheduler(service, config).runScheduledCleanup()

        verify(exactly = 1) { service.run("scheduler", config) }
    }
}
