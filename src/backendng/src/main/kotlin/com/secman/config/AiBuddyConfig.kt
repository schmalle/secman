package com.secman.config

import jakarta.inject.Singleton

/** Fixed resource ceilings; operational AI settings come from the active admin OpenRouter record. */
@Singleton
class AiBuddyConfig {
    var enabled: Boolean = false
    var model: String = ""
    var providers: List<String> = emptyList()
    var singleInstance: Boolean = false
    var maxCostPerJobUsd: Double = 0.50
    var dailyBudgetUsd: Double = 10.0
    var maxInputPricePerMillion: Double = 5.0
    var maxOutputPricePerMillion: Double = 20.0
    var maxConcurrentJobs: Int = 2
    var perUserStartsPerHour: Int = 5
    var perAssetStartsPerHour: Int = 10
    var requestTimeoutSeconds: Long = 60
    var jobTimeoutSeconds: Long = 300
    var retentionSeconds: Long = 1800
    var clientLeaseSeconds: Long = 60
    var maxFindings: Int = 50_000
    var maxInventoryRows: Int = 10_000
    var maxSnapshotBytes: Int = 20 * 1024 * 1024
    var maxReportBytes: Int = 10 * 1024 * 1024
    var maxRetainedJobs: Int = 100
    var maxRetainedBytes: Long = 100 * 1024 * 1024
    var maxBatches: Int = 8
    var maxOutputTokens: Int = 4096
    var maxPayloadBytes: Int = 512 * 1024
    var maxEvidenceRequests: Int = 100
    var maxEvidenceDocuments: Int = 24
    var maxEvidenceBytes: Long = 40 * 1024 * 1024
    var staleDays: Long = 7
    var crowdStrikeEvidenceEnabled: Boolean = false
    var microsoftEvidenceEnabled: Boolean = true

    fun unavailableReason(key: String): String? = when {
        !enabled -> "FEATURE_DISABLED"
        !singleInstance -> "SINGLE_INSTANCE_REQUIRED"
        key.isBlank() -> "API_KEY_REQUIRED"
        model.isBlank() -> "MODEL_REQUIRED"
        providers.isEmpty() -> "APPROVED_PROVIDER_REQUIRED"
        model.contains(':') || model == "openrouter/auto" -> "MODEL_UNSUPPORTED"
        maxConcurrentJobs !in 1..8 || maxBatches !in 1..8 || maxOutputTokens !in 128..4096 -> "NOT_CONFIGURED"
        maxCostPerJobUsd <= 0 || dailyBudgetUsd <= 0 || !maxCostPerJobUsd.isFinite() || !dailyBudgetUsd.isFinite() -> "NOT_CONFIGURED"
        requestTimeoutSeconds !in 1..60 || jobTimeoutSeconds !in 1..300 || retentionSeconds !in 1..1800 -> "NOT_CONFIGURED"
        maxFindings !in 1..50_000 || maxInventoryRows !in 1..10_000 || maxPayloadBytes !in 1024..524288 -> "NOT_CONFIGURED"
        perUserStartsPerHour !in 1..100 || perAssetStartsPerHour !in 1..100 || clientLeaseSeconds !in 10..60 -> "NOT_CONFIGURED"
        maxEvidenceRequests !in 1..100 || maxEvidenceDocuments !in 1..24 || maxEvidenceBytes !in 1024..41943040 -> "NOT_CONFIGURED"
        maxRetainedJobs !in 1..100 || maxRetainedBytes !in 1024..104857600 || maxReportBytes !in 1024..10485760 || maxSnapshotBytes !in 1024..20971520 -> "NOT_CONFIGURED"
        maxInputPricePerMillion <= 0 || maxOutputPricePerMillion <= 0 || !maxInputPricePerMillion.isFinite() || !maxOutputPricePerMillion.isFinite() || staleDays !in 1..365 -> "NOT_CONFIGURED"
        providers.any { !Regex("[a-z0-9.-]+(?:/[a-z0-9.-]+)?").matches(it) } -> "NOT_CONFIGURED"
        else -> null
    }
}
