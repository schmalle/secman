package com.secman.service

import com.secman.domain.AssessmentBasisType
import com.secman.domain.RiskAssessment
import com.secman.domain.User
import io.micronaut.security.authentication.Authentication
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RiskAssessmentAccessServiceTest {
    private val service = RiskAssessmentAccessService()

    @Test
    fun `unrelated risk user cannot view AWS account assessment`() {
        val assessment = assessment()

        assertFalse(service.canViewAwsAccountAssessment(assessment, auth("other", "RISK")))
    }

    @Test
    fun `respondent can view but cannot manage AWS account assessment`() {
        val assessment = assessment()
        val authentication = auth("respondent@example.com", "RISK")

        assertTrue(service.canViewAwsAccountAssessment(assessment, authentication))
        assertTrue(service.canAnswerAwsAccountAssessment(assessment, authentication))
        assertFalse(service.canManageAwsAccountAssessment(assessment, authentication))
    }

    @Test
    fun `requestor and privileged roles cannot answer for AWS account respondent`() {
        val assessment = assessment()

        assertFalse(service.canAnswerAwsAccountAssessment(assessment, auth("requestor", "RISK")))
        assertFalse(service.canAnswerAwsAccountAssessment(assessment, auth("admin", "ADMIN")))
        assertFalse(service.canAnswerAwsAccountAssessment(assessment, auth("champion", "SECCHAMPION")))
    }

    @Test
    fun `requestor and privileged roles can manage AWS account assessment`() {
        val assessment = assessment()

        assertTrue(service.canManageAwsAccountAssessment(assessment, auth("requestor", "RISK")))
        assertTrue(service.canManageAwsAccountAssessment(assessment, auth("admin", "ADMIN")))
        assertTrue(service.canManageAwsAccountAssessment(assessment, auth("champion", "SECCHAMPION")))
    }

    private fun assessment(): RiskAssessment {
        val assessor = user(1, "assessor")
        val requestor = user(2, "requestor")
        val respondent = user(3, "respondent")
        return RiskAssessment(
            id = 10,
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(7),
            assessmentBasisType = AssessmentBasisType.AWS_ACCOUNT,
            assessmentBasisId = 20,
            assessor = assessor,
            requestor = requestor,
            respondent = respondent
        )
    }

    private fun user(id: Long, name: String) = User(
        id = id,
        username = name,
        email = "$name@example.com",
        passwordHash = name
    )

    private fun auth(name: String, vararg roles: String): Authentication =
        Authentication.build(name, roles.toList())
}
