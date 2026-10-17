package com.secman.controller

import com.secman.dto.AccountWelcomeTestRequest
import com.secman.dto.OwnerMailDelivery
import com.secman.service.AccountOnboardingRateLimiter
import com.secman.service.OwnerMailDeliveryService
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AccountWelcomeTestControllerTest {
    private val limiter = mockk<AccountOnboardingRateLimiter>()
    private val mail = mockk<OwnerMailDeliveryService>()
    private val controller = AccountOnboardingController(mockk(), mockk(), mockk(), mockk(), mockk(),
        mockk(), mockk(), limiter, mail)
    private val actor = Authentication.build("admin", listOf("ADMIN"))
    private val valid = AccountWelcomeTestRequest("test@example.test", listOf("111111111111", "222222222222"))

    @Test fun `one validated rate limited request makes one grouped test call`() {
        every { limiter.tryAcquire(AccountOnboardingRateLimiter.Bucket.SIMULATE, "admin") } returns true
        every { mail.testWelcome(valid.accountIds, valid.recipient, "admin") } returns OwnerMailDelivery(true, "SENT")
        assertEquals(HttpStatus.OK, controller.testWelcome(valid, actor).status)
        verify(exactly = 1) { mail.testWelcome(valid.accountIds, valid.recipient, "admin") }
    }

    @Test fun `invalid recipients and unbounded or malformed accounts cannot send`() {
        for (request in listOf(valid.copy(recipient = "a@example.test,b@example.test"),
            valid.copy(accountIds = emptyList()), valid.copy(accountIds = List(201) { "111111111111" }),
            valid.copy(accountIds = listOf("invalid")))) {
            assertEquals(HttpStatus.BAD_REQUEST, controller.testWelcome(request, actor).status)
        }
        verify { mail wasNot Called; limiter wasNot Called }
    }

    @Test fun `rate limiting refuses mail before transport`() {
        every { limiter.tryAcquire(any(), any()) } returns false
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, controller.testWelcome(valid, actor).status)
        verify { mail wasNot Called }
    }
}
