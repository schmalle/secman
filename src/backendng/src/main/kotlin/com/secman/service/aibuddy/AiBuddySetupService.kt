package com.secman.service.aibuddy

import com.fasterxml.jackson.annotation.JsonInclude
import com.secman.domain.TranslationConfig
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import java.net.URI

@Serdeable
data class AiBuddyProviderOption(
    val tag: String,
    val name: String,
    @JsonInclude(JsonInclude.Include.ALWAYS) val compatible: Boolean,
    @JsonInclude(JsonInclude.Include.ALWAYS) val reasons: List<String>
)

@Serdeable
data class AiBuddySetupResult(
    @JsonInclude(JsonInclude.Include.ALWAYS) val ready: Boolean,
    @JsonInclude(JsonInclude.Include.ALWAYS) val reasons: List<String>,
    @JsonInclude(JsonInclude.Include.ALWAYS) val providers: List<AiBuddyProviderOption>
)

/** Explicit admin preflight only: reads provider metadata, never submits a completion or saves settings. */
@Singleton
class AiBuddySetupService(private val http: AiBuddyHttpTransport, private val json: AiBuddyJson) {
    fun check(record: TranslationConfig): AiBuddySetupResult {
        val selected = AiBuddySettingsService.from(record)
        val config = selected.config
        val reasons = mutableListOf<String>()
        if (!record.isActive) reasons += "ACTIVE_CONFIGURATION_REQUIRED"
        if (!config.enabled) reasons += "FEATURE_DISABLED"
        if (!config.singleInstance) reasons += "SINGLE_INSTANCE_REQUIRED"
        if (selected.apiKey.isBlank()) reasons += "API_KEY_REQUIRED"
        if (config.providers.isEmpty()) reasons += "APPROVED_PROVIDER_REQUIRED"
        if (record.apiKey.isNotBlank() && !AiBuddySettingsService.valid(record)) reasons += "NOT_CONFIGURED"
        selected.unavailableReason()?.let { reasons += it }
        val modelSupported = selected.baseUrl.trimEnd('/') == "https://openrouter.ai/api/v1" &&
            Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(config.model)
        if (!modelSupported) {
            if (config.model.isBlank()) reasons += "MODEL_REQUIRED" else reasons += "MODEL_UNSUPPORTED"
        }
        if (selected.apiKey.isBlank() || !modelSupported) {
            return AiBuddySetupResult(false, reasons.distinct(), emptyList())
        }
        val headers = mapOf("Authorization" to "Bearer ${selected.apiKey}")
        fun metadata(path: String, limit: Int) = http.request(
            URI.create("https://openrouter.ai/api/v1/$path"), setOf("openrouter.ai"), 15, limit, headers = headers
        ).let { response ->
            if (response.status != 200) throw AiBuddyFailure("PROVIDER_METADATA_UNAVAILABLE")
            json.parse(response.body)
        }
        val endpoints = metadata("models/${config.model}/endpoints", 2 * 1024 * 1024).path("data").path("endpoints")
        val zdr = metadata("endpoints/zdr", 8 * 1024 * 1024).path("data")
        if (!endpoints.isArray || endpoints.size() > 100 || !zdr.isArray)
            throw AiBuddyFailure("PROVIDER_METADATA_UNAVAILABLE")
        val eligibleTags = zdr.filter { it.path("model_id").asText() == config.model }
            .map { it.path("tag").asText() }.toSet()
        val options = endpoints.mapNotNull { endpoint ->
            val tag = endpoint.path("tag").asText()
            if (!Regex("[a-z0-9.-]+(?:/[a-z0-9.-]+)?").matches(tag)) return@mapNotNull null
            val issues = mutableListOf<String>()
            if (endpoint.path("status").asInt(-1) != 0) issues += "PROVIDER_OFFLINE"
            if (!endpoint.path("supported_parameters").map { it.asText() }
                    .containsAll(listOf("structured_outputs", "response_format"))) issues += "STRUCTURED_OUTPUT_REQUIRED"
            if (endpoint.path("max_completion_tokens").asInt(0) < config.maxOutputTokens) issues += "OUTPUT_LIMIT_UNSUPPORTED"
            if (tag !in eligibleTags) issues += "ZERO_RETENTION_REQUIRED"
            val input = endpoint.path("pricing").path("prompt").asText().toDoubleOrNull()
            val output = endpoint.path("pricing").path("completion").asText().toDoubleOrNull()
            if (input == null || output == null || !input.isFinite() || !output.isFinite() || input < 0 || output < 0 ||
                input * 1_000_000 > config.maxInputPricePerMillion || output * 1_000_000 > config.maxOutputPricePerMillion)
                issues += "PROVIDER_PRICE_LIMIT_EXCEEDED"
            AiBuddyProviderOption(tag, endpoint.path("provider_name").asText(tag).take(120), issues.isEmpty(), issues)
        }.distinctBy { it.tag }.sortedBy { it.tag }
        if (config.providers.any { tag -> options.none { it.tag == tag && it.compatible } }) reasons += "PROVIDER_ROUTE_UNSUPPORTED"
        return AiBuddySetupResult(reasons.isEmpty(), reasons.distinct(), options)
    }
}
