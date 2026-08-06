package com.secman.service

import com.secman.domain.AssessmentBasisType
import com.secman.domain.RiskAssessment
import com.secman.domain.User
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UserRepository
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Optional

class AssessmentOwnershipGuardTest {

    private val riskAssessmentRepository = mockk<RiskAssessmentRepository>()
    private val userRepository = mockk<UserRepository>()
    private val guard = AssessmentOwnershipGuard(riskAssessmentRepository, userRepository)

    private fun user(id: Long, username: String) = User(
        id = id,
        username = username,
        email = "$username@example.com",
        passwordHash = "hash"
    )

    private fun assessment(id: Long, assessor: User, requestor: User, respondent: User? = null) = RiskAssessment(
        id = id,
        startDate = LocalDate.now(),
        endDate = LocalDate.now().plusDays(30),
        assessmentBasisType = AssessmentBasisType.ASSET,
        assessmentBasisId = 1L,
        assessor = assessor,
        requestor = requestor,
        respondent = respondent
    )

    private fun authAs(username: String, vararg roles: String): Authentication {
        val auth = mockk<Authentication>()
        every { auth.name } returns username
        every { auth.roles } returns roles.toList()
        return auth
    }

    @Test
    fun `check rejects a caller who is only the respondent, not assessor or requestor`() {
        val assessor = user(1, "assessor")
        val requestor = user(2, "requestor")
        val respondent = user(3, "respondent")
        val target = assessment(10, assessor, requestor, respondent)

        every { riskAssessmentRepository.findById(10L) } returns Optional.of(target)
        every { userRepository.findByUsername("respondent") } returns Optional.of(respondent)

        val auth = authAs("respondent", "USER")

        assertThat(assertThrows(auth)).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `checkWithRespondent allows the assessment's respondent`() {
        val assessor = user(1, "assessor")
        val requestor = user(2, "requestor")
        val respondent = user(3, "respondent")
        val target = assessment(10, assessor, requestor, respondent)

        every { riskAssessmentRepository.findById(10L) } returns Optional.of(target)
        every { userRepository.findByUsername("respondent") } returns Optional.of(respondent)

        val auth = authAs("respondent", "USER")

        val result = guard.checkWithRespondent(10L, auth)
        assertThat(result.id).isEqualTo(10L)
    }

    @Test
    fun `checkWithRespondent rejects a user with no relationship to the assessment`() {
        val assessor = user(1, "assessor")
        val requestor = user(2, "requestor")
        val stranger = user(4, "stranger")
        val target = assessment(10, assessor, requestor, respondent = null)

        every { riskAssessmentRepository.findById(10L) } returns Optional.of(target)
        every { userRepository.findByUsername("stranger") } returns Optional.of(stranger)

        val auth = authAs("stranger", "USER")

        val ex = org.junit.jupiter.api.assertThrows<HttpStatusException> {
            guard.checkWithRespondent(10L, auth)
        }
        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `checkWithRespondent allows ADMIN regardless of relationship`() {
        val assessor = user(1, "assessor")
        val requestor = user(2, "requestor")
        val target = assessment(10, assessor, requestor)

        every { riskAssessmentRepository.findById(10L) } returns Optional.of(target)

        val auth = authAs("admin", "ADMIN")

        val result = guard.checkWithRespondent(10L, auth)
        assertThat(result.id).isEqualTo(10L)
    }

    @Test
    fun `checkWithRespondent returns 404 for an unknown assessment`() {
        every { riskAssessmentRepository.findById(999L) } returns Optional.empty()

        val auth = authAs("someone", "USER")

        val ex = org.junit.jupiter.api.assertThrows<HttpStatusException> {
            guard.checkWithRespondent(999L, auth)
        }
        assertThat(ex.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    private fun assertThrows(auth: Authentication): HttpStatus {
        val ex = org.junit.jupiter.api.assertThrows<HttpStatusException> {
            guard.check(10L, auth)
        }
        return ex.status
    }
}
