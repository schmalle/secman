package com.secman.repository

import com.secman.domain.UserMapping
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

open class UserMappingRepositoryNewAccountTest : BaseIntegrationTest() {

    @Inject
    lateinit var repository: UserMappingRepository

    @AfterEach
    fun tearDown() {
        repository.deleteAll()
    }

    @Test
    fun `findExistingAwsAccountIds returns only ids already present`() {
        repository.save(UserMapping(email = "a@corp.com", awsAccountId = "111111111111", domain = null))
        repository.save(UserMapping(email = "b@corp.com", awsAccountId = "222222222222", domain = null))

        val result = repository.findExistingAwsAccountIds(
            listOf("111111111111", "222222222222", "333333333333")
        )

        assertThat(result).containsExactlyInAnyOrder("111111111111", "222222222222")
        assertThat(result).doesNotContain("333333333333")
    }
}
