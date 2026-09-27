package com.secman.controller

import com.secman.domain.*
import com.secman.repository.*
import com.secman.service.*
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Optional

class RiskAssessmentParticipantTest {
    private val users = mockk<UserRepository>(relaxed = true)
    private val assessments = mockk<RiskAssessmentRepository>(relaxed = true)
    private val workflow = mockk<AssessmentWorkflowService>(relaxed = true)
    private val access = RiskAssessmentAccessService(mockk(relaxed = true), mockk(relaxed = true))
    private val controller = RiskAssessmentController(assessments, mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), users, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        workflow, mockk(relaxed = true), access, mockk(relaxed = true))
    private val assessor = User(id = 1, username = "reviewer", email = "reviewer@example.test", passwordHash = "x")
    private val admin = User(id = 2, username = "manager", email = "manager@example.test", passwordHash = "x")
    private val auth = Authentication.build("manager", listOf("SECCHAMPION"), mapOf("userId" to 2L))
    private fun request() = RiskAssessmentController.CreateRiskAssessmentRequest(
        assessorRef = UserResolutionService.UserRef(email = assessor.email), endDate = LocalDate.now().plusDays(1),
        assessmentBasisType = AssessmentBasisType.SAAS, solutionName = "Example service",
        respondentRef = UserResolutionService.UserRef(email = "external@example.test"))

    @Test fun `unknown and disabled assessors cannot create a registered user implicitly`() {
        every { users.findByEmailIgnoreCase(assessor.email) } returns Optional.empty()
        assertEquals(HttpStatus.BAD_REQUEST, controller.createRiskAssessment(request(), auth).status)
        assessor.enabled = false
        every { users.findByEmailIgnoreCase(assessor.email) } returns Optional.of(assessor)
        assertEquals(HttpStatus.BAD_REQUEST, controller.createRiskAssessment(request(), auth).status)
        verify(exactly = 0) { users.save(any()); assessments.save(any()) }
    }

    @Test fun `manager creates external respondent assignment without registering a user`() {
        every { users.findByEmailIgnoreCase(assessor.email) } returns Optional.of(assessor)
        every { users.findByEmailIgnoreCase("external@example.test") } returns Optional.empty()
        every { users.findByUsername("manager") } returns Optional.of(admin)
        every { assessments.save(any()) } answers { firstArg<RiskAssessment>().also { it.id = 10 } }
        val result = controller.createRiskAssessment(request(), auth)
        assertEquals(HttpStatus.CREATED, result.status)
        verify { workflow.initialize(match { it.respondent == null && it.respondentEmail == "external@example.test" && it.requestor.id == 2L }) }
        verify(exactly = 0) { users.save(any()) }
    }

    @Test fun `recipient list syntax is rejected before storing an assessment`() {
        every { users.findByEmailIgnoreCase(assessor.email) } returns Optional.of(assessor)
        every { users.findByUsername("manager") } returns Optional.of(admin)
        for (email in listOf("first@example.test,second@example.test", "first@example.test;second@example.test", "bad\r\n@example.test")) {
            val result = controller.createRiskAssessment(request().copy(respondentRef = UserResolutionService.UserRef(email = email)), auth)
            assertEquals(HttpStatus.BAD_REQUEST, result.status)
        }
        verify(exactly = 0) { assessments.save(any()) }
    }
}
