package com.secman.service.aibuddy

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.config.AiBuddyConfig
import com.secman.dto.*
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class AiBuddyProviderTest {
    private val http = mockk<AiBuddyHttpTransport>()
    private val json = AiBuddyJson(ObjectMapper())
    private val config = AiBuddyConfig().apply { enabled = true; singleInstance = true; model = "vendor/model"; providers = listOf("vendor") }
    private val settings = mockk<AiBuddySettingsService>().also { every { it.current() } returns
        AiBuddySettings(config, "secret-canary", "https://openrouter.ai/api/v1", "fixture") }
    private val client = OpenRouterAiBuddyClient(settings, http, json)
    private val candidate = RemediationCandidate("c12345678901234567890", "Windows Server 2022", AiBuddyActionType.OS_UPDATE,
        listOf("KB1111111"), "10.0.20348.1", setOf("f-private-db-id"), emptyList(), listOf(AiBuddyCheck("OS_BUILD")))
    private val metadata = """{"data":{"endpoints":[{"tag":"vendor","provider_name":"Vendor","status":0,"context_length":100000,"max_completion_tokens":8192,"supported_parameters":["structured_outputs","response_format"],"pricing":{"prompt":"0.0000001","completion":"0.0000002"}}]}}"""
    private fun stubMetadata(raw: String = metadata) {
        every { http.request(match { it.path.endsWith("/endpoints") }, any(), any(), any(), any(), any()) } returns AiBuddyHttpResult(200, raw.toByteArray(), null)
    }
    @Test fun `model receives allowlisted facts schema and enforced routing only`() {
        stubMetadata()
        val payload = slot<ByteArray?>()
        every { http.request(match { it.path.endsWith("/completions") }, any(), any(), any(), captureNullable(payload), any()) } returns
            AiBuddyHttpResult(200, """{"model":"vendor/model","provider":"Vendor","choices":[{"finish_reason":"stop","message":{"content":"{\"orderedCandidateIds\":[\"c12345678901234567890\"]}"}}]}""".toByteArray(), null)
        assertThat(client.order(listOf(candidate)) {}).containsExactly(candidate.id)
        val raw = payload.captured!!.toString(Charsets.UTF_8)
        assertThat(raw).doesNotContain("f-private-db-id", "secret-canary", "host", "account", "tools", "plugins")
        val root = json.parse(payload.captured!!)
        assertThat(root.path("provider").path("allow_fallbacks").asBoolean()).isFalse()
        assertThat(root.path("provider").path("zdr").asBoolean()).isTrue()
        assertThat(root.path("provider").path("data_collection").asText()).isEqualTo("deny")
    }
    @Test fun `rate limit auth credit outage and model policy fail with safe codes`() {
        stubMetadata()
        for ((status, code) in listOf(429 to "PROVIDER_RATE_LIMITED", 401 to "PROVIDER_AUTH_FAILED", 402 to "PROVIDER_CREDIT_EXHAUSTED", 503 to "PROVIDER_UNAVAILABLE")) {
            every { http.request(match { it.path.endsWith("/completions") }, any(), any(), any(), any(), any()) } returns AiBuddyHttpResult(status, "secret provider text".toByteArray(), 10)
            assertThat(assertThrows<AiBuddyFailure> { client.order(listOf(candidate)) {} }.code).isEqualTo(code)
        }
    }
    @Test fun `unsupported schema context and price reject before paid calls`() {
        for (raw in listOf(metadata.replace("structured_outputs", "text"), metadata.replace("100000", "100"), metadata.replace("0.0000001", "1.0"))) {
            stubMetadata(raw)
            assertThrows<AiBuddyFailure> { client.order(listOf(candidate)) {} }
        }
        verify(exactly = 0) { http.request(match { it.path.endsWith("/completions") }, any(), any(), any(), any(), any()) }
    }
}
