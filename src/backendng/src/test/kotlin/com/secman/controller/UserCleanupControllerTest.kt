package com.secman.controller

import com.secman.config.UserCleanupConfig
import com.secman.scheduler.InactiveUserCleanupScheduler
import com.secman.scheduler.UserCleanupDisabledException
import com.secman.service.UserCleanupInProgressException
import com.secman.service.UserCleanupMisconfiguredException
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Exception-to-status mapping of run-now; generic bodies only. */
class UserCleanupControllerTest {

    private val scheduler = mockk<InactiveUserCleanupScheduler>()
    private val controller = UserCleanupController(UserCleanupConfig(), mockk(), scheduler, mockk(), mockk())
    private val admin = Authentication.build("admin", listOf("ADMIN"))

    @Test
    fun `run-now returns 409 while a run is in progress`() {
        every { scheduler.runWithScheduledPolicy(any()) } throws UserCleanupInProgressException()

        assertThat(controller.runNow(admin).status.code).isEqualTo(409)
    }

    @Test
    fun `run-now returns 409 when disabled`() {
        every { scheduler.runWithScheduledPolicy(any()) } throws UserCleanupDisabledException()

        assertThat(controller.runNow(admin).status.code).isEqualTo(409)
    }

    @Test
    fun `run-now returns 400 when misconfigured and 500 without detail on failure`() {
        every { scheduler.runWithScheduledPolicy(any()) } throws UserCleanupMisconfiguredException("warning-days")
        assertThat(controller.runNow(admin).status.code).isEqualTo(400)

        every { scheduler.runWithScheduledPolicy(any()) } throws IllegalStateException("SELECT * FROM users")
        val failure = controller.runNow(admin)
        assertThat(failure.status.code).isEqualTo(500)
        assertThat(failure.body().toString()).doesNotContain("SELECT")
    }

    @Test
    fun `run-now records the admin as actor`() {
        every { scheduler.runWithScheduledPolicy("manual:admin") } returns mockk(relaxed = true)

        assertThat(controller.runNow(admin).status.code).isEqualTo(200)
    }
}
