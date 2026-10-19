package com.secman.service.aibuddy

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.TranslationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AiBuddySetupServiceTest {
    private val http = mockk<AiBuddyHttpTransport>()
    private val service = AiBuddySetupService(http, AiBuddyJson(ObjectMapper()))
    private fun config() = TranslationConfig(apiKey = UUID.randomUUID().toString(), modelName = "vendor/model",
        isActive = true, aiBuddyEnabled = true, aiBuddySingleInstance = true)

    @Test
    fun `incomplete credential is reported without sending metadata requests`() {
        val result = service.check(config().copy(apiKey = ""))
        assertThat(result.ready).isFalse()
        assertThat(result.reasons).contains("API_KEY_REQUIRED", "APPROVED_PROVIDER_REQUIRED")
        verify(exactly = 0) { http.request(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `metadata offers routes without auto approval and generation is never called`() {
        every { http.request(any(), any(), any(), any(), any(), any()) } answers {
            val zdr = firstArg<java.net.URI>().path.endsWith("/zdr")
            val body = if (zdr) """{"data":[{"model_id":"vendor/model","tag":"vendor"}]}""" else
                """{"data":{"endpoints":[{"tag":"vendor","provider_name":"Vendor","status":0,"max_completion_tokens":4096,"supported_parameters":["structured_outputs","response_format"],"pricing":{"prompt":"0.000001","completion":"0.000002"}}]}}"""
            AiBuddyHttpResult(200, body.toByteArray(), null)
        }
        val draft = config()
        val missing = service.check(draft)
        assertThat(missing.ready).isFalse()
        assertThat(missing.providers.single().compatible).isTrue()
        assertThat(draft.aiBuddyProviders).isEmpty()
        assertThat(service.check(draft.copy(aiBuddyProviders = "vendor")).ready).isTrue()
        assertThat(service.check(draft.copy(aiBuddyProviders = "unknown")).reasons).contains("PROVIDER_ROUTE_UNSUPPORTED")
        verify(exactly = 6) { http.request(any(), setOf("openrouter.ai"), 15, any(), null, any()) }
    }

    @Test
    fun `a route absent from the zero retention preview cannot be approved as compatible`() {
        every { http.request(any(), any(), any(), any(), any(), any()) } answers {
            val body = if (firstArg<java.net.URI>().path.endsWith("/zdr")) """{"data":[]}""" else
                """{"data":{"endpoints":[{"tag":"vendor","status":0,"max_completion_tokens":4096,"supported_parameters":["structured_outputs","response_format"],"pricing":{"prompt":"0.000001","completion":"0.000002"}}]}}"""
            AiBuddyHttpResult(200, body.toByteArray(), null)
        }
        val result = service.check(config().copy(aiBuddyProviders = "vendor"))
        assertThat(result.ready).isFalse()
        assertThat(result.providers.single().compatible).isFalse()
        assertThat(result.providers.single().reasons).contains("ZERO_RETENTION_REQUIRED")
        assertThat(result.reasons).contains("PROVIDER_ROUTE_UNSUPPORTED")
    }
}
