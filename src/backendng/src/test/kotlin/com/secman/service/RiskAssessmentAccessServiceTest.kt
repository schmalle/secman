package com.secman.service

import com.secman.domain.*
import com.secman.repository.AssessmentAssignmentRepository
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RiskAssessmentAccessServiceTest {
    private val assignments = mockk<AssessmentAssignmentRepository>(relaxed = true)
    private val assets = mockk<AssetFilterService>(relaxed = true)
    private val service = RiskAssessmentAccessService(assignments, assets)
    private val user = User(id = 1, username = "requestor", email = "one@example.test", passwordHash = "x")
    private val assessment = RiskAssessment(id = 10, startDate = LocalDate.now(), endDate = LocalDate.now(),
        assessmentBasisType = AssessmentBasisType.AWS_ACCOUNT, assessmentBasisId = 20,
        awsAccount = AwsAccount(id = 20, awsAccountId = "123456789012"), assessor = user, requestor = user)
    private fun auth(role: String = "USER", id: Long = 1) = Authentication.build("renamed", listOf(role), mapOf("userId" to id))

    @Test fun `legacy requestor and assessor fields do not grant access`() {
        assertFalse(service.canView(assessment, auth()))
        assertFalse(service.canAnswer(assessment, auth()))
        assertFalse(service.canManageAwsAccountAssessment(assessment, auth()))
    }
    @Test fun `assigned user survives rename but not revocation`() {
        val assignment = AssessmentAssignment(assessmentId = 10, userId = 1, email = user.email, role = "RESPONDENT")
        every { assignments.findByAssessmentId(10) } returns listOf(assignment)
        assertTrue(service.canView(assessment, auth()))
        assertTrue(service.canAnswer(assessment, auth()))
        assertFalse(service.canView(assessment, auth(id = 2)))
        assignment.revoked = true
        assertFalse(service.canView(assessment, auth()))
        verify(exactly = 0) { assets.canAccessAsset(any(), any()) }
    }
    @Test fun `one asset is not whole account authority`() {
        every { assets.canAccessAsset(any(), any()) } returns true
        assertFalse(service.canView(assessment, auth()))
        every { assets.canAccessAwsAccount("123456789012", any()) } returns true
        assertTrue(service.canView(assessment, auth()))
        assertFalse(service.canAnswer(assessment, auth()))
    }
    @Test fun `global roles review but cannot impersonate a respondent`() {
        for (role in listOf("ADMIN", "SECCHAMPION")) {
            assertTrue(service.canReview(assessment, auth(role)))
            assertTrue(service.canManageAwsAccountAssessment(assessment, auth(role)))
            assertFalse(service.canAnswer(assessment, auth(role)))
        }
    }
    @Test fun `submitted assignment is read only and section limited`() {
        every { assignments.findByAssessmentId(10) } returns listOf(AssessmentAssignment(
            assessmentId = 10, userId = 1, email = user.email, role = "RESPONDENT", submitted = true, requirementIds = "11"))
        assertFalse(service.canAnswer(assessment, auth()))
        val visible = service.visibleRequirements(assessment, auth(), listOf(
            Requirement(id = 11, shortreq = "one"), Requirement(id = 12, shortreq = "two")))
        assertEquals(listOf(11L), visible.map { it.id })
    }
}
