package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * One row per requirement per AI run. Holds the model's JSON output (parsed
 * + validated), the derived confidence band, model + prompt provenance, and
 * usage/cost data for audit.
 *
 * Status semantics:
 *   APPLIED    — live suggestion for (assessment, requirement). Exactly one
 *                APPLIED row should exist per (assessment, requirement) at any
 *                time; this is enforced in the service layer.
 *   SUPERSEDED — replaced by a later APPLIED row from a re-run. Kept for audit.
 *   FAILED     — LLM call or validation failed. No Response is written.
 *
 * Citations are stored as JSON text (LONGTEXT) parsed/serialized via Jackson
 * in the service layer — see ComplianceAssistantService.
 */
@Entity
@Table(
    name = "ai_answer_suggestion",
    indexes = [
        Index(name = "idx_aisug_assessment_req", columnList = "risk_assessment_id, requirement_id, status"),
        Index(name = "idx_aisug_band", columnList = "confidence_band, status"),
        Index(name = "idx_aisug_job", columnList = "job_id")
    ]
)
class AiAnswerSuggestion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "job_id", nullable = false)
    var jobId: Long,

    @Column(name = "risk_assessment_id", nullable = false)
    var riskAssessmentId: Long,

    @Column(name = "requirement_id", nullable = false)
    var requirementId: Long,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_answer_type", nullable = false, length = 16)
    var suggestedAnswerType: SuggestedAnswerType,

    @Column(name = "suggested_comment", columnDefinition = "TEXT")
    var suggestedComment: String? = null,

    @Column(name = "raw_confidence", nullable = false)
    var rawConfidence: Double,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_band", nullable = false, length = 8)
    var confidenceBand: ConfidenceBand,

    @Column(name = "rationale", columnDefinition = "TEXT")
    var rationale: String? = null,

    /**
     * Citations as JSON text. Parsed by ComplianceAssistantService /
     * AiSuggestionController via Jackson into a List<Citation>.
     */
    @Column(name = "citations", columnDefinition = "LONGTEXT")
    var citationsJson: String? = null,

    @Column(name = "model", nullable = false, length = 128)
    var model: String,

    @Column(name = "prompt_version", nullable = false, length = 16)
    var promptVersion: String,

    @Column(name = "input_tokens")
    var inputTokens: Int? = null,

    @Column(name = "output_tokens")
    var outputTokens: Int? = null,

    @Column(name = "cost_usd", precision = 10, scale = 6)
    var costUsd: BigDecimal? = null,

    @Column(name = "web_search_used", nullable = false)
    var webSearchUsed: Boolean = false,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: AiAnswerSuggestionStatus,

    @Column(name = "error_message", length = 2048)
    var errorMessage: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "superseded_at")
    var supersededAt: LocalDateTime? = null
)
