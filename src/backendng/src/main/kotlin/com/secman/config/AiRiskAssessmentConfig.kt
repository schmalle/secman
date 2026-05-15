package com.secman.config

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.serde.annotation.Serdeable

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Binds `secman.ai.risk-assessment.*` from application.yml.
 *
 * Defaults are safe-by-default: feature is OFF, model and cost caps must be
 * explicitly enabled. See docs/ENVIRONMENT.md for env-var overrides.
 */
@ConfigurationProperties("secman.ai.risk-assessment")
@Serdeable
data class AiRiskAssessmentConfig(
    var enabled: Boolean = false,
    var model: String = "anthropic/claude-sonnet-4.6:online",
    var maxCostPerJobUsd: Double = 5.0,
    var maxConcurrentJobsGlobal: Int = 2,
    var perRequestTimeoutSeconds: Long = 60,
    var jobTimeoutMinutes: Long = 30,
    var confidence: Confidence = Confidence(),
    var tokenEstimate: TokenEstimate = TokenEstimate(),
    /**
     * USD per 1k tokens, keyed by model id.
     * Example map shape (YAML):
     *   pricing-per-1k-tokens:
     *     anthropic/claude-sonnet-4.6:online:
     *       input: 0.003
     *       output: 0.015
     *
     * Values are read as a map of model -> nested Pricing. Missing entries
     * fall back to a conservative default (see ComplianceAssistantService).
     */
    var pricingPer1kTokens: Map<String, Pricing> = emptyMap()
) {
    @ConfigurationProperties("confidence")
    @Serdeable
    data class Confidence(
        var highThreshold: Double = 0.75,
        var mediumThreshold: Double = 0.50
    )

    @ConfigurationProperties("token-estimate")
    @Serdeable
    data class TokenEstimate(
        var inputPerRequirement: Int = 400,
        var outputPerRequirement: Int = 200
    )

    @Serdeable
    data class Pricing(
        var input: Double = 0.0,
        var output: Double = 0.0
    )
}

/**
 * Companion config binding `secman.openrouter.*`. Lives in this file because
 * it's only consumed by the AI feature (and TranslationService has its own
 * config table-backed flow).
 */
@ConfigurationProperties("secman.openrouter")
@Serdeable
data class OpenRouterConfig(
    var apiKey: String = "",
    var baseUrl: String = "https://openrouter.ai/api/v1"
)
