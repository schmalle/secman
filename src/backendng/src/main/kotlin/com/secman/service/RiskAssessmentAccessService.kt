package com.secman.service

import com.secman.domain.*
import com.secman.repository.AssessmentAssignmentRepository
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

/** Assignment access never implies access to the underlying asset or account. */
@Singleton
open class RiskAssessmentAccessService(
    private val assignments: AssessmentAssignmentRepository,
    private val assets: AssetFilterService
) {
    fun actorId(authentication: Authentication): Long? =
        (authentication.attributes["userId"] as? Number)?.toLong()
            ?: authentication.attributes["userId"]?.toString()?.toLongOrNull()

    fun isGlobal(authentication: Authentication): Boolean =
        authentication.roles.any { it == "ADMIN" || it == "SECCHAMPION" }

    /** Use stable user IDs so renaming cannot transfer a task assignment. */
    fun activeAssignments(assessment: RiskAssessment, authentication: Authentication): List<AssessmentAssignment> {
        val actor = actorId(authentication) ?: return emptyList()
        return assignments.findByAssessmentId(assessment.id!!).filter { !it.revoked && it.userId == actor }
    }

    fun canReview(assessment: RiskAssessment, authentication: Authentication): Boolean =
        isGlobal(authentication) || activeAssignments(assessment, authentication).any { it.role == "ASSESSOR" }

    /** Combine task grants with resource visibility without treating requestors as owners. */
    fun canView(assessment: RiskAssessment, authentication: Authentication): Boolean =
        canReview(assessment, authentication) || activeAssignments(assessment, authentication).isNotEmpty() ||
            when (assessment.assessmentBasisType) {
                AssessmentBasisType.AWS_ACCOUNT -> assessment.awsAccount?.awsAccountId?.let {
                    assets.canAccessAwsAccount(it, authentication)
                } == true
                AssessmentBasisType.ASSET -> assets.canAccessAsset(assessment.assessmentBasisId, authentication)
                AssessmentBasisType.DEMAND -> assessment.demand?.existingAsset?.id?.let {
                    assets.canAccessAsset(it, authentication)
                } == true
            }

    fun canAnswer(assessment: RiskAssessment, authentication: Authentication): Boolean =
        assessment.status == "STARTED" && activeAssignments(assessment, authentication)
            .any { it.role == "RESPONDENT" && !it.submitted }

    fun permitsRequirement(assignment: AssessmentAssignment, requirementId: Long): Boolean =
        assignment.requirementIds.isBlank() || requirementId in assignment.requirementIds.split(',').mapNotNull(String::toLongOrNull)

    /** Project respondent sections before serializing questions, answers, or files. */
    fun visibleRequirements(assessment: RiskAssessment, authentication: Authentication, requirements: List<Requirement>): List<Requirement> {
        if (canReview(assessment, authentication)) return requirements
        val scoped = activeAssignments(assessment, authentication)
        if (scoped.isEmpty()) return if (canView(assessment, authentication)) requirements else emptyList()
        return requirements.filter { requirement -> scoped.any { permitsRequirement(it, requirement.id!!) } }
    }

    fun canViewAwsAccountAssessment(assessment: RiskAssessment, authentication: Authentication): Boolean =
        assessment.assessmentBasisType == AssessmentBasisType.AWS_ACCOUNT && canView(assessment, authentication)

    fun canManageAwsAccountAssessment(assessment: RiskAssessment, authentication: Authentication): Boolean =
        assessment.assessmentBasisType == AssessmentBasisType.AWS_ACCOUNT && isGlobal(authentication)

    fun canAnswerAwsAccountAssessment(assessment: RiskAssessment, authentication: Authentication): Boolean =
        assessment.assessmentBasisType == AssessmentBasisType.AWS_ACCOUNT && canAnswer(assessment, authentication)
}
