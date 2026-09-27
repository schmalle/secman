package com.secman.service

import com.secman.domain.AnswerType
import com.secman.domain.Requirement
import com.secman.domain.RiskAssessment
import com.secman.dto.Recommendation
import com.secman.dto.RecommendationFinding
import com.secman.dto.RiskAssessmentRecommendation
import com.secman.repository.ResponseRepository
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.time.Instant

/**
 * Deterministic, read-only recommendation shared by the MCP evaluation tool and
 * the REST recommendation endpoint. The caller performs authorization; the
 * [assessment] passed in is already access-checked. Nothing here mutates state,
 * so the result is always advisory and pinned to the assessment's
 * [RiskAssessment.answerRevision].
 */
@Singleton
open class RiskAssessmentRecommendationService(
    private val responseRepository: ResponseRepository,
    private val workflow: AssessmentWorkflowService
) {
    fun requirementsFor(assessment: RiskAssessment): List<Requirement> = workflow.requirementsFor(assessment)

    @Transactional(readOnly = true)
    open fun recommend(assessment: RiskAssessment, completedOnly: Boolean = false): RiskAssessmentRecommendation {
        workflow.lockRecommendation(assessment)
        check(!completedOnly || assessment.status == "COMPLETED") { "Only a completed assessment can be evaluated" }
        val requirements = requirementsFor(assessment)
        val responses = responseRepository.findByRiskAssessmentId(assessment.id!!).associateBy { it.requirement.id }
        // Only the pinned questionnaire contributes to the policy result.
        val scopedIds = requirements.mapNotNull { it.id }.toSet()
        val answerCounts = AnswerType.entries.associate { type ->
            type.name to responses.values.count { it.requirement.id in scopedIds && it.answerType == type }
        }
        val noCount = answerCounts.getValue(AnswerType.NO.name)
        val naCount = answerCounts.getValue(AnswerType.N_A.name)
        val missingCount = requirements.count { responses[it.id]?.answerType == null }
        val findings = requirements.sortedBy { it.id }.mapNotNull { requirement ->
            val response = responses[requirement.id]
            when (response?.answerType) {
                AnswerType.YES -> null
                AnswerType.NO -> RecommendationFinding(
                    requirementId = requirement.id!!,
                    internalId = requirement.internalId,
                    shortreq = requirement.shortreq,
                    answerType = AnswerType.NO.name,
                    reason = "Requirement is explicitly not met.",
                    comment = response.comment
                )
                AnswerType.N_A -> RecommendationFinding(
                    requirementId = requirement.id!!,
                    internalId = requirement.internalId,
                    shortreq = requirement.shortreq,
                    answerType = AnswerType.N_A.name,
                    reason = "Marked not applicable - reviewer should confirm scope.",
                    comment = response.comment
                )
                null -> RecommendationFinding(
                    requirementId = requirement.id!!,
                    internalId = requirement.internalId,
                    shortreq = requirement.shortreq,
                    answerType = null,
                    reason = "No answer submitted.",
                    comment = response?.comment
                )
            }
        }
        val recommendation = when {
            assessment.status != "COMPLETED" -> Recommendation.NEEDS_REVIEW
            noCount > 0 -> Recommendation.NOT_OK
            naCount > 0 -> Recommendation.NEEDS_REVIEW
            missingCount > 0 -> Recommendation.NEEDS_REVIEW
            requirements.isEmpty() -> Recommendation.NEEDS_REVIEW
            else -> Recommendation.OK
        }
        return RiskAssessmentRecommendation(
            assessmentId = assessment.id!!,
            answerRevision = assessment.answerRevision,
            recommendation = recommendation,
            verdict = if (noCount == 0) "COMPLIANT" else "NON_COMPLIANT",
            summary = summary(recommendation, assessment.status, requirements.size, missingCount, noCount, naCount),
            answerCounts = answerCounts,
            requirementCount = requirements.size,
            missingAnswerCount = missingCount,
            findings = findings,
            generatedAt = Instant.now(),
            policyVersion = POLICY_VERSION
        )
    }

    private fun summary(
        recommendation: Recommendation,
        status: String,
        requirementCount: Int,
        missingCount: Int,
        noCount: Int,
        naCount: Int
    ): String {
        val answeredCount = requirementCount - missingCount
        return when (recommendation) {
            Recommendation.NOT_OK -> buildList {
                add("$noCount ${if (noCount == 1) "requirement is" else "requirements are"} not met")
                if (missingCount > 0) add("$missingCount ${if (missingCount == 1) "answer needs" else "answers need"} review")
                if (naCount > 0) add("$naCount marked not applicable")
            }.joinToString("; ", postfix = ".")
            Recommendation.NEEDS_REVIEW -> when {
                status != "COMPLETED" ->
                    "Questionnaire is not yet submitted; $answeredCount of $requirementCount requirements answered."
                missingCount > 0 ->
                    "Questionnaire is submitted but incomplete; $answeredCount of $requirementCount requirements answered."
                naCount > 0 -> "$naCount not-applicable ${if (naCount == 1) "answer requires" else "answers require"} reviewer confirmation."
                else -> "No requirements are in scope for this assessment."
            }
            Recommendation.OK -> if (naCount > 0) {
                "All $requirementCount requirements are answered and none are unmet; $naCount marked not applicable."
            } else {
                "All $requirementCount requirements are answered and none are unmet."
            }
        }
    }

    companion object {
        const val POLICY_VERSION = "2.0"
    }
}
