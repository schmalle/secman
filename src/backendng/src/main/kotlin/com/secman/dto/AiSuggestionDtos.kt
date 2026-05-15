package com.secman.dto

import com.secman.domain.AiSuggestionJobStatus
import com.secman.domain.ConfidenceBand
import com.secman.domain.SuggestedAnswerType
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * DTOs shared between the service layer, controller, and tests. Kept in one
 * file because they're tightly coupled to the feature; if they grow, split
 * into per-class files.
 */

/**
 * One web citation produced by the LLM. URLs must be https; validated in
 * [com.secman.service.CitationValidator].
 */
@Serdeable
data class Citation(
    val title: String? = null,
    val url: String,
    val snippet: String? = null
)

/**
 * The raw, parsed-but-not-yet-scored output from a single OpenRouter call.
 * Confidence and citations have already been validated; band has been
 * derived. This is what [com.secman.service.ComplianceAssistantService] hands
 * back to the job orchestrator.
 */
@Serdeable
data class SuggestionResult(
    val answer: SuggestedAnswerType,
    val rationale: String?,
    val rawConfidence: Double,
    val confidenceBand: ConfidenceBand,
    val citations: List<Citation>,
    val model: String,
    val promptVersion: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val costUsd: BigDecimal?,
    val webSearchUsed: Boolean,
    val cacheHit: Boolean = false
)

/**
 * Compact view of an asset or demand the LLM uses for context. Redacted form
 * — owner emails, IPs, internal URLs are stripped before this is built.
 */
@Serdeable
data class AssessmentContext(
    val basisType: String,                 // "ASSET" or "DEMAND"
    val basisLabel: String,                // asset.name or demand.title
    val assetType: String? = null,
    val assetGroups: List<String> = emptyList(),
    val cloudAccountId: String? = null,
    val osVersion: String? = null,
    val demandDescription: String? = null,
    val useCases: List<String> = emptyList(),
    val fewShotExamples: List<FewShotExample> = emptyList()
)

@Serdeable
data class FewShotExample(
    val requirementText: String,
    val answer: String,                    // YES | NO | N_A
    val comment: String?
)

// ---- Controller request / response DTOs ---------------------------------

@Serdeable
data class StartAiJobRequest(
    val scope: String,                     // AiSuggestionScope name
    val requirementIds: List<Long>? = null,
    val force: Boolean = false
)

@Serdeable
data class StartAiJobResponse(
    val jobId: Long,
    val totalCount: Int,
    val estimatedCostUsd: BigDecimal?
)

@Serdeable
data class AiJobStatusDto(
    val id: Long,
    val status: AiSuggestionJobStatus,
    val model: String,
    val scope: String,
    val totalCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val progressPercent: Int,
    val totalCostUsd: BigDecimal,
    val estimatedCostUsd: BigDecimal?,
    val startedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
    val errorMessage: String?
)

@Serdeable
data class AppliedSuggestionDto(
    val requirementId: Long,
    val suggestedAnswerType: SuggestedAnswerType,
    val suggestedComment: String?,
    val rawConfidence: Double,
    val confidenceBand: ConfidenceBand,
    val rationale: String?,
    val citations: List<Citation>,
    val model: String,
    val promptVersion: String,
    val webSearchUsed: Boolean,
    val createdAt: LocalDateTime
)

@Serdeable
data class ClearLowConfidenceResponse(
    val deletedResponseCount: Long
)

/**
 * SSE event payload emitted by AiSuggestionJobService.processOneRequirement
 * and on terminal transitions. The frontend consumes one event per finished
 * requirement plus a final event when the job ends.
 */
@Serdeable
data class JobProgressEvent(
    val jobId: Long,
    val type: String,                     // "PROGRESS" | "COMPLETED" | "FAILED" | "CANCELLED"
    val requirementId: Long? = null,
    val band: ConfidenceBand? = null,
    val completedCount: Int,
    val failedCount: Int,
    val totalCount: Int,
    val totalCostUsd: BigDecimal? = null,
    val errorMessage: String? = null
)
