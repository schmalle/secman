package com.secman.controller

import com.secman.domain.AssessmentBasisType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RiskAssessmentRequestTest {
    @Test
    fun `AWS account is accepted as the only assessment basis`() {
        val request = RiskAssessmentController.CreateRiskAssessmentRequest(
            assessorId = 1,
            endDate = LocalDate.now().plusDays(7),
            awsAccountId = "123456789012"
        )

        assertThat(request.validate()).isNull()
        assertThat(request.getBasisType()).isEqualTo(AssessmentBasisType.AWS_ACCOUNT)
    }

    @Test
    fun `AWS account basis requires twelve digits and excludes other bases`() {
        val invalidId = RiskAssessmentController.CreateRiskAssessmentRequest(
            assessorId = 1,
            endDate = LocalDate.now().plusDays(7),
            awsAccountId = "123"
        )
        val twoBases = invalidId.copy(awsAccountId = "123456789012", assetId = 9)

        assertThat(invalidId.validate()).contains("12 digits")
        assertThat(twoBases.validate()).contains("Exactly one")
    }
}
