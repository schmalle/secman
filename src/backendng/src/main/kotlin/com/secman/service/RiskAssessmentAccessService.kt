package com.secman.service

import com.secman.domain.AssessmentBasisType
import com.secman.domain.RiskAssessment
import com.secman.domain.User
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

/** Applies the basis-specific authorization boundary for risk assessments. */
@Singleton
open class RiskAssessmentAccessService {
    fun canViewAwsAccountAssessment(assessment: RiskAssessment, authentication: Authentication): Boolean =
        assessment.assessmentBasisType == AssessmentBasisType.AWS_ACCOUNT &&
            (hasUniversalAccess(authentication) || isParticipant(assessment, authentication))

    fun canManageAwsAccountAssessment(assessment: RiskAssessment, authentication: Authentication): Boolean =
        assessment.assessmentBasisType == AssessmentBasisType.AWS_ACCOUNT &&
            (hasUniversalAccess(authentication) ||
                matchesCurrentUser(assessment.assessor, authentication) ||
                matchesCurrentUser(assessment.requestor, authentication))

    fun canAnswerAwsAccountAssessment(assessment: RiskAssessment, authentication: Authentication): Boolean =
        assessment.assessmentBasisType == AssessmentBasisType.AWS_ACCOUNT &&
            assessment.respondent?.let { matchesCurrentUser(it, authentication) } == true

    private fun hasUniversalAccess(authentication: Authentication): Boolean =
        authentication.roles.any { it == "ADMIN" || it == "SECCHAMPION" }

    private fun isParticipant(assessment: RiskAssessment, authentication: Authentication): Boolean =
        matchesCurrentUser(assessment.assessor, authentication) ||
            matchesCurrentUser(assessment.requestor, authentication) ||
            assessment.respondent?.let { matchesCurrentUser(it, authentication) } == true

    private fun matchesCurrentUser(user: User, authentication: Authentication): Boolean =
        authentication.name.equals(user.username, ignoreCase = true) ||
            authentication.name.equals(user.email, ignoreCase = true)
}
