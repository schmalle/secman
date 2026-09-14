package com.secman.controller

import com.secman.domain.AssessmentBasisType
import com.secman.domain.AwsAccount
import com.secman.repository.AwsAccountRepository
import com.secman.repository.RiskAssessmentRepository
import io.micronaut.http.HttpStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class AwsAccountControllerTest {
    private val accounts = mockk<AwsAccountRepository>(relaxed = true)
    private val assessments = mockk<RiskAssessmentRepository>(relaxed = true)
    private val controller = AwsAccountController(accounts, assessments)

    @Test
    fun `deletes only a nameless unreferenced account row`() {
        val account = AwsAccount(id = 7, awsAccountId = "123456789012")
        every { accounts.findByAwsAccountId(account.awsAccountId) } returns Optional.of(account)
        every {
            assessments.existsByAssessmentBasisTypeAndAssessmentBasisId(AssessmentBasisType.AWS_ACCOUNT, 7)
        } returns false

        val response = controller.deleteUnreferenced(account.awsAccountId)

        assertThat(response.status).isEqualTo(HttpStatus.NO_CONTENT)
        verify { accounts.delete(account) }
    }

    @Test
    fun `refuses to delete an account still used by a risk assessment`() {
        val account = AwsAccount(id = 7, awsAccountId = "123456789012")
        every { accounts.findByAwsAccountId(account.awsAccountId) } returns Optional.of(account)
        every {
            assessments.existsByAssessmentBasisTypeAndAssessmentBasisId(AssessmentBasisType.AWS_ACCOUNT, 7)
        } returns true

        val response = controller.deleteUnreferenced(account.awsAccountId)

        assertThat(response.status).isEqualTo(HttpStatus.CONFLICT)
        verify(exactly = 0) { accounts.delete(any()) }
    }
}
