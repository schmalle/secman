package com.secman.service

import com.secman.domain.AssessmentBasisType
import com.secman.domain.AnswerType
import com.secman.domain.RiskAssessment
import com.secman.domain.Response
import com.secman.dto.AssessmentContext
import com.secman.dto.FewShotExample
import com.secman.repository.AssetRepository
import com.secman.repository.DemandRepository
import com.secman.repository.ResponseRepository
import jakarta.inject.Singleton

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Builds the redaction-safe context for AI calls.  Pulls ONLY the fields the
 * spec allows: asset name/type/groups/cloudAccountId/osVersion, OR demand
 * description/title.  Owner/email/IP/adDomain are never touched.
 */
@Singleton
class AssessmentContextBuilder(
    private val assetRepository: AssetRepository,
    private val demandRepository: DemandRepository,
    private val responseRepository: ResponseRepository
) {

    fun build(assessment: RiskAssessment): AssessmentContext {
        val useCases = assessment.useCases.mapNotNull { it.name }
        val fewShot = collectFewShot(assessment)

        return when (assessment.assessmentBasisType) {
            AssessmentBasisType.ASSET -> {
                val asset = assetRepository.findById(assessment.assessmentBasisId).orElse(null)
                AssessmentContext(
                    basisType = "ASSET",
                    basisLabel = asset?.name ?: "Unknown asset",
                    assetType = asset?.type,
                    assetGroups = parseGroups(asset?.groups),
                    cloudAccountId = asset?.cloudAccountId,
                    osVersion = asset?.osVersion,
                    demandDescription = null,
                    useCases = useCases,
                    fewShotExamples = fewShot
                )
            }
            AssessmentBasisType.DEMAND -> {
                val demand = demandRepository.findById(assessment.assessmentBasisId).orElse(null)
                AssessmentContext(
                    basisType = "DEMAND",
                    basisLabel = demand?.title ?: "Unknown demand",
                    assetType = demand?.existingAsset?.type,
                    assetGroups = parseGroups(demand?.existingAsset?.groups),
                    cloudAccountId = demand?.existingAsset?.cloudAccountId,
                    osVersion = demand?.existingAsset?.osVersion,
                    demandDescription = demand?.description,
                    useCases = useCases,
                    fewShotExamples = fewShot
                )
            }
        }
    }

    private fun parseGroups(groups: String?): List<String> {
        if (groups.isNullOrBlank()) return emptyList()
        return groups.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Up to 3 existing, human-authored responses on the same assessment, used
     * as few-shot signal. We intentionally pick from human (MANUAL) sources
     * only — AI-generated rows would create a feedback loop.
     */
    private fun collectFewShot(assessment: RiskAssessment): List<FewShotExample> {
        val id = assessment.id ?: return emptyList()
        val all: List<Response> = responseRepository.findByRiskAssessmentId(id)
        return all
            .filter { it.source == com.secman.domain.ResponseSource.MANUAL && it.answerType != null }
            .take(3)
            .map {
                FewShotExample(
                    requirementText = it.requirement.shortreq,
                    answer = (it.answerType ?: AnswerType.N_A).name,
                    comment = it.comment
                )
            }
    }
}
