package com.secman.controller

import com.secman.domain.AssessmentBasisType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RiskAssessmentSolutionRequestTest {
    private fun request(basis: AssessmentBasisType, name: String?) =
        RiskAssessmentController.CreateRiskAssessmentRequest(assessorId = 1, endDate = LocalDate.now().plusDays(1),
            assessmentBasisType = basis, solutionName = name)

    @Test fun `solution assessments require a bounded nonblank name and exactly one basis`() {
        for (basis in listOf(AssessmentBasisType.SAAS, AssessmentBasisType.COTS)) {
            assertNull(request(basis, "Example solution").validate())
            assertEquals(basis, request(basis, "Example solution").getBasisType())
            for (name in listOf(null, " ", "x".repeat(256))) assertNotNull(request(basis, name).validate())
            assertNotNull(request(basis, "Example").copy(assetId = 2).validate())
            assertNotNull(request(basis, "Example").copy(awsAccountId = "123456789012").validate())
        }
    }
}
