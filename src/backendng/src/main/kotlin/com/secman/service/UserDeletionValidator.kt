package com.secman.service

import com.secman.repository.*
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton

@Singleton
class UserDeletionValidator(
    private val demandRepository: DemandRepository,
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val releaseRepository: ReleaseRepository,
    private val demandClassificationRuleRepository: DemandClassificationRuleRepository,
    private val demandClassificationResultRepository: DemandClassificationResultRepository
) {

    @Serdeable
    data class ValidationResult(
        val canDelete: Boolean,
        val blockingReferences: List<BlockingReference>,
        val message: String
    )

    @Serdeable
    data class BlockingReference(
        val entityType: String,
        val count: Int,
        val role: String,
        val details: String
    )

    fun validateUserDeletion(userId: Long): ValidationResult {
        val blockingReferences = mutableListOf<BlockingReference>()

        // Check Demands where user is requestor (non-nullable)
        val demandsAsRequestor = demandRepository.findByRequestorId(userId)
        if (demandsAsRequestor.isNotEmpty()) {
            blockingReferences.add(
                BlockingReference(
                    entityType = "Demand",
                    count = demandsAsRequestor.size,
                    role = "requestor",
                    details = "User is the requestor for ${demandsAsRequestor.size} demand(s)"
                )
            )
        }

        // Check Demands where user is approver (nullable, but still informative)
        val demandsAsApprover = demandRepository.findByApproverId(userId)
        if (demandsAsApprover.isNotEmpty()) {
            blockingReferences.add(
                BlockingReference(
                    entityType = "Demand",
                    count = demandsAsApprover.size,
                    role = "approver",
                    details = "User is the approver for ${demandsAsApprover.size} demand(s)"
                )
            )
        }

        // Check Risk Assessments where user is assessor (non-nullable)
        val riskAssessmentsAsAssessor = riskAssessmentRepository.findByAssessorId(userId)
        if (riskAssessmentsAsAssessor.isNotEmpty()) {
            blockingReferences.add(
                BlockingReference(
                    entityType = "RiskAssessment",
                    count = riskAssessmentsAsAssessor.size,
                    role = "assessor",
                    details = "User is the assessor for ${riskAssessmentsAsAssessor.size} risk assessment(s)"
                )
            )
        }

        // Check Risk Assessments where user is requestor (non-nullable)
        val riskAssessmentsAsRequestor = riskAssessmentRepository.findByRequestorId(userId)
        if (riskAssessmentsAsRequestor.isNotEmpty()) {
            blockingReferences.add(
                BlockingReference(
                    entityType = "RiskAssessment",
                    count = riskAssessmentsAsRequestor.size,
                    role = "requestor",
                    details = "User is the requestor for ${riskAssessmentsAsRequestor.size} risk assessment(s)"
                )
            )
        }

        // Check Risk Assessments where user is respondent (nullable, but still informative)
        val riskAssessmentsAsRespondent = riskAssessmentRepository.findByRespondentId(userId)
        if (riskAssessmentsAsRespondent.isNotEmpty()) {
            blockingReferences.add(
                BlockingReference(
                    entityType = "RiskAssessment",
                    count = riskAssessmentsAsRespondent.size,
                    role = "respondent",
                    details = "User is the respondent for ${riskAssessmentsAsRespondent.size} risk assessment(s)"
                )
            )
        }

        // Check Releases where user is creator (nullable, but still informative)
        val releasesAsCreator = releaseRepository.findByCreatedBy_Id(userId)
        if (releasesAsCreator.isNotEmpty()) {
            blockingReferences.add(
                BlockingReference(
                    entityType = "Release",
                    count = releasesAsCreator.size,
                    role = "creator",
                    details = "User created ${releasesAsCreator.size} release(s)"
                )
            )
        }

        // Check Demand Classification Rules where user is creator (nullable, but still informative)
        val classificationRulesAsCreator = demandClassificationRuleRepository.findByCreatedBy_Id(userId)
        if (classificationRulesAsCreator.isNotEmpty()) {
            blockingReferences.add(
                BlockingReference(
                    entityType = "DemandClassificationRule",
                    count = classificationRulesAsCreator.size,
                    role = "creator",
                    details = "User created ${classificationRulesAsCreator.size} classification rule(s)"
                )
            )
        }

        // Check Demand Classification Results where user overrode classification (nullable, but still informative)
        val classificationResultsAsOverrider = demandClassificationResultRepository.findByOverriddenBy_Id(userId)
        if (classificationResultsAsOverrider.isNotEmpty()) {
            blockingReferences.add(
                BlockingReference(
                    entityType = "DemandClassificationResult",
                    count = classificationResultsAsOverrider.size,
                    role = "overrider",
                    details = "User overrode ${classificationResultsAsOverrider.size} classification result(s)"
                )
            )
        }

        // Generate appropriate message
        val message = if (blockingReferences.isEmpty()) {
            "User can be safely deleted"
        } else {
            val summary = blockingReferences.joinToString(", ") {
                "${it.count} ${it.entityType}(s) as ${it.role}"
            }
            "Cannot delete user due to existing references: $summary. Please reassign or remove these records first."
        }

        return ValidationResult(
            canDelete = blockingReferences.isEmpty(),
            blockingReferences = blockingReferences,
            message = message
        )
    }
}
