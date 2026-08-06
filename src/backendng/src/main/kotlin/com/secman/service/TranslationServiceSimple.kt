package com.secman.service

import com.secman.domain.TranslationConfig
import com.secman.repository.TranslationConfigRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class TranslationServiceSimple(
    private val translationConfigRepository: TranslationConfigRepository,
    private val httpClient: HttpClient
) {

    private val logger = LoggerFactory.getLogger(TranslationServiceSimple::class.java)

    @Serdeable
    data class TestResult(
        val success: Boolean,
        val message: String,
        val details: String? = null
    )

    /**
     * Get the active translation configuration
     */
    fun getActiveConfig(): TranslationConfig? {
        return translationConfigRepository.findActiveConfig().orElse(null)
    }

    /**
     * Translate text (placeholder implementation)
     */
    fun translateText(text: String, targetLanguage: String): String {
        // For now, just return the original text with a note
        // This will be enhanced when we integrate with OpenRouter
        return "$text [Note: Translation to ${getSupportedLanguages()[targetLanguage] ?: targetLanguage} will be implemented with OpenRouter API]"
    }

    /**
     * Test the translation configuration by hitting the upstream provider.
     *
     * For OpenRouter (baseUrl contains "openrouter.ai"), calls GET /auth/key,
     * which is the canonical key-introspection endpoint: it returns the owning
     * account info on success and an authoritative 401 on a bad key. This is
     * the cheapest possible "real" auth check.
     *
     * For non-OpenRouter base URLs, falls back to a structural validation only
     * (we don't know the upstream's introspection endpoint).
     */
    fun testConfiguration(config: TranslationConfig): TestResult {
        if (!config.isValid()) {
            return TestResult(
                success = false,
                message = "Configuration validation failed",
                details = "Invalid API key, base URL, or model settings"
            )
        }

        val trimmedKey = config.apiKey.trim()
        // Key fingerprint: first 8 + last 4 chars. Safe to surface to the
        // calling admin (they already know the secret) and lets them confirm
        // the value in DB matches what they think they pasted.
        val keyFingerprint = if (trimmedKey.length >= 12) {
            "${trimmedKey.take(8)}...${trimmedKey.takeLast(4)} (${trimmedKey.length} chars)"
        } else {
            "<too short: ${trimmedKey.length} chars>"
        }

        if (trimmedKey != config.apiKey) {
            return TestResult(
                success = false,
                message = "API key has leading/trailing whitespace",
                details = "Stored value has surrounding whitespace. Re-enter the key — saves now strip whitespace. Fingerprint: $keyFingerprint"
            )
        }

        val isOpenRouter = config.baseUrl.contains("openrouter.ai", ignoreCase = true)
        if (!isOpenRouter) {
            return TestResult(
                success = true,
                message = "Configuration is structurally valid",
                details = "Live upstream test is only implemented for openrouter.ai base URLs. Key fingerprint: $keyFingerprint"
            )
        }

        // Sanity check: OpenRouter keys start with sk-or-. Reject obvious mismatches early.
        if (!trimmedKey.startsWith("sk-or-")) {
            return TestResult(
                success = false,
                message = "API key does not look like an OpenRouter key",
                details = "OpenRouter keys start with 'sk-or-v1-'. The configured key fingerprint is $keyFingerprint — that does not match the OpenRouter format. If this is an Anthropic key (sk-ant-...) or another provider's key, change the base URL or paste an OpenRouter key."
            )
        }

        // Real call: GET https://openrouter.ai/api/v1/auth/key
        val url = "${config.baseUrl.trimEnd('/')}/auth/key"
        val request = HttpRequest.GET<Any>(url)
            .header("Authorization", "Bearer $trimmedKey")
            .header("HTTP-Referer", "https://secman.local")
            .header("X-Title", "SecMan Configuration Test")

        logger.info("Translation config test: GET {} with key fingerprint {}", url, keyFingerprint)

        return try {
            val response = httpClient.toBlocking().exchange(request, String::class.java)
            TestResult(
                success = true,
                message = "Configuration test successful",
                details = "OpenRouter accepted the key (fingerprint $keyFingerprint). Account info: ${response.body()?.take(200) ?: "(no body)"}"
            )
        } catch (e: HttpClientResponseException) {
            val body = try {
                e.response.getBody(String::class.java).orElse(null)
            } catch (_: Exception) {
                null
            }
            logger.error("Translation config test failed - URL: {}, key fingerprint: {}, status: {}, body: {}",
                url, keyFingerprint, e.status, body)

            val hint = when {
                body?.contains("\"User not found.\"") == true ->
                    "OpenRouter does not recognise the account behind this key. Confirm the fingerprint above matches a key listed at openrouter.ai/keys, and that the account hasn't been deleted/disabled. To verify the key directly, run: curl -H \"Authorization: Bearer <YOUR_KEY>\" https://openrouter.ai/api/v1/auth/key — if that also returns 401, the key is bad on OpenRouter's side."
                e.status.code == 401 -> "Key is invalid or revoked. Re-issue at openrouter.ai/keys."
                e.status.code == 402 -> "Payment required. Check credit balance at openrouter.ai."
                e.status.code == 429 -> "Rate limited."
                else -> "Upstream HTTP ${e.status.code}."
            }

            TestResult(
                success = false,
                message = "OpenRouter rejected the key (HTTP ${e.status.code})",
                details = "Key fingerprint in DB: $keyFingerprint. URL: $url. $hint Raw upstream body: ${body?.take(300) ?: "(empty)"}"
            )
        } catch (e: Exception) {
            logger.error("Translation config test errored - URL: {}, key fingerprint: {}", url, keyFingerprint, e)
            TestResult(
                success = false,
                message = "Configuration test errored",
                details = "Key fingerprint in DB: $keyFingerprint. ${e.message ?: e::class.simpleName ?: "unknown error"}"
            )
        }
    }

    /**
     * Get supported languages
     */
    fun getSupportedLanguages(): Map<String, String> {
        return TranslationConfig.SUPPORTED_LANGUAGES
    }

    /**
     * Get available models
     */
    fun getAvailableModels(): Map<String, String> {
        return TranslationConfig.AVAILABLE_MODELS
    }
}