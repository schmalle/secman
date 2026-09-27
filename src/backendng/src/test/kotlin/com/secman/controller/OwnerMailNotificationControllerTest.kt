package com.secman.controller

import com.secman.dto.OwnerMailDelivery
import com.secman.service.OwnerMailDeliveryService
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OwnerMailNotificationControllerTest {
    @Test fun `retry accepts the string user ID carried by JWT authentication`() {
        val service = mockk<OwnerMailDeliveryService>()
        every { service.retry(42, 7) } returns OwnerMailDelivery(true, "SENT", 42)
        val authentication = Authentication.build("admin", listOf("ADMIN"), mapOf("userId" to "7"))
        val result = OwnerMailNotificationController(service).retry(42, authentication)
        assertEquals(HttpStatus.OK, result.status)
        verify(exactly = 1) { service.retry(42, 7) }
    }
}
