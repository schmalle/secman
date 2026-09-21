package com.secman.service

import com.secman.domain.RiskAssessment
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UserRepository
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/** AI research requires a current global assessment manager. */
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

        val id = (authentication.attributes["userId"] as? Number)?.toLong()
            ?: authentication.attributes["userId"]?.toString()?.toLongOrNull()
        val user = id?.let { userRepository.findById(it).orElse(null) }
            ?: throw HttpStatusException(HttpStatus.FORBIDDEN, "Current user required")
        if (!user.enabled || user.roles.none { it == com.secman.domain.User.Role.ADMIN || it == com.secman.domain.User.Role.SECCHAMPION }) {
            throw HttpStatusException(HttpStatus.FORBIDDEN, "Global assessment manager required")
        }
        return assessment
    }
}
