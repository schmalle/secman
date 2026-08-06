package com.secman.service

import com.secman.domain.UserMapping
import com.secman.dto.BulkUserMappingEntry
import com.secman.dto.BulkUserMappingRequest
import com.secman.repository.UserMappingRepository
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

open class UserMappingServiceNewAccountTest : BaseIntegrationTest() {

    @Inject
    lateinit var service: UserMappingService

    @Inject
    lateinit var repository: UserMappingRepository

    @AfterEach
    fun tearDown() {
        repository.deleteAll()
    }

    @Test
    fun `newAccounts excludes pre-existing account and includes brand-new with mapped emails`() {
        // Pre-existing account in DB
        repository.save(UserMapping(email = "old@corp.com", awsAccountId = "111111111111", domain = null))

        val request = BulkUserMappingRequest(
            mappings = listOf(
                BulkUserMappingEntry(email = "old2@corp.com", awsAccountId = "111111111111"), // known acct
                BulkUserMappingEntry(email = "alice@corp.com", awsAccountId = "222222222222"), // new
                BulkUserMappingEntry(email = "bob@corp.com", awsAccountId = "333333333333"),   // new
                BulkUserMappingEntry(email = "carol@corp.com", awsAccountId = "333333333333")  // new, 2nd user
            )
        )

        val result = service.bulkCreateMappings(request)

        assertThat(result.newAccounts.map { it.awsAccountId })
            .containsExactly("222222222222", "333333333333")
        val acct333 = result.newAccounts.first { it.awsAccountId == "333333333333" }
        assertThat(acct333.emails).containsExactlyInAnyOrder("bob@corp.com", "carol@corp.com")
    }

    @Test
    fun `dry-run populates newAccounts without persisting`() {
        repository.save(UserMapping(email = "old@corp.com", awsAccountId = "111111111111", domain = null))

        val request = BulkUserMappingRequest(
            mappings = listOf(
                BulkUserMappingEntry(email = "alice@corp.com", awsAccountId = "222222222222")
            ),
            dryRun = true
        )

        val result = service.bulkCreateMappings(request)

        assertThat(result.newAccounts.map { it.awsAccountId }).containsExactly("222222222222")
        // nothing new persisted (only the seeded pre-existing row remains)
        assertThat(repository.findByAwsAccountId("222222222222")).isEmpty()
    }
}
