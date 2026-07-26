package com.secman.integration

import com.secman.domain.AwsAccount
import com.secman.repository.AwsAccountRepository
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccountFindingAgeIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var awsAccountRepository: AwsAccountRepository

    @Test
    fun `aws account row round-trips by account id`() {
        awsAccountRepository.save(
            AwsAccount(awsAccountId = "123456789012", name = "Platform Prod", updatedBy = "admin")
        )

        val found = awsAccountRepository.findByAwsAccountId("123456789012")

        assertThat(found).isPresent
        assertThat(found.get().name).isEqualTo("Platform Prod")
        assertThat(found.get().updatedAt).isNotNull()
    }

    @Test
    fun `bulk lookup returns only known accounts`() {
        awsAccountRepository.save(AwsAccount(awsAccountId = "222222222222", name = "Sandbox"))

        val rows = awsAccountRepository.findByAwsAccountIdIn(listOf("222222222222", "999999999999"))

        assertThat(rows).hasSize(1)
        assertThat(rows.first().awsAccountId).isEqualTo("222222222222")
    }
}
