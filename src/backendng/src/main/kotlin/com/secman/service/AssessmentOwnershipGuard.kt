package com.secman.service

import com.secman.domain.RiskAssessment
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UserRepository
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Authorisation helper shared by the AI-suggestion endpoints. Caller is
 * authorised iff one of:
 *   1) authentication.roles contains ADMIN
 *   2) the assessment's assessor == caller
 *   3) the assessment's requestor == caller
 *
 * SECCHAMPION role alone does NOT grant access to any random assessment —
 * the user must be the creator (assessor or requestor) for that specific
 * assessment.
 */
@Singleton
class AssessmentOwnershipGuard(
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val userRepository: UserRepository
) {
    private val log = LoggerFactory.getLogger(AssessmentOwnershipGuard::class.java)

    /**
     * @throws HttpStatusException(404) when the assessment is unknown.
     * @throws HttpStatusException(403) when the caller has no claim to it.
     * @return the assessment when the check passes.
     */
    fun check(assessmentId: Long, authentication: Authentication): RiskAssessment {
        val assessment = riskAssessmentRepository.findById(assessmentId).orElse(null)
            ?: throw HttpStatusException(HttpStatus.NOT_FOUND, "Assessment not found")

        if (authentication.roles.contains("ADMIN")) {
            return assessment
        }

        val username = authentication.name
        val user = userRepository.findByUsername(username).orElse(null)
        if (user == null) {
            log.warn("AssessmentOwnershipGuard: user not found for principal {}", username)
            throw HttpStatusException(HttpStatus.FORBIDDEN, "User not recognized")
        }

        val isOwner = assessment.assessor.id == user.id || assessment.requestor.id == user.id
        if (!isOwner) {
            log.info(
                "AssessmentOwnershipGuard: forbidden — user {} (id={}) is neither assessor (id={}) nor requestor (id={}) of assessment {}",
                username, user.id, assessment.assessor.id, assessment.requestor.id, assessmentId
            )
            throw HttpStatusException(HttpStatus.FORBIDDEN, "You do not have access to this assessment")
        }
        return assessment
    }
}
