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

    /**
     * A mapped email is not merely a database value: it becomes the SMTP recipient of the
     * AWS-account risk assessment start mail, it is interpolated into log lines, and it is
     * written into the assessment notes. So the import boundary — not the mail layer — is
     * where addresses carrying separators or control characters have to be turned away.
     */
    @Test
    fun `rejects emails carrying separators, control characters or excess length`() {
        val rejected = listOf(
            "alice\r\nBcc: attacker@evil.com" to "CR/LF (log forging, mail headers)",
            "alice,bob@corp.com" to "comma (InternetAddress.parse splits into two recipients)",
            "alice bob@corp.com" to "space",
            "<alice@corp.com>" to "angle brackets",
            "alice@corp.com;bob@corp.com" to "semicolon",
            "a".repeat(250) + "@corp.com" to "longer than the 255-char column"
        )

        for ((email, why) in rejected) {
            val result = service.bulkCreateMappings(
                BulkUserMappingRequest(
                    mappings = listOf(BulkUserMappingEntry(email = email, awsAccountId = "444444444444"))
                )
            )

            assertThat(result.errors).describedAs("must reject %s", why).hasSize(1)
            assertThat(result.created + result.createdPending).describedAs("must persist nothing for %s", why)
                .isZero()
            // The rejected value is echoed back — sanitized, so it cannot inject a log line.
            assertThat(result.errors.single()).doesNotContain("\n").doesNotContain("\r")
            // A rejected row must not make its account look brand-new to the assessment starter.
            assertThat(result.newAccounts).isEmpty()
        }
    }

    @Test
    fun `still accepts the ordinary shapes of a real address`() {
        val result = service.bulkCreateMappings(
            BulkUserMappingRequest(
                mappings = listOf(
                    BulkUserMappingEntry(email = "first.last+tag@sub.example.co.uk", awsAccountId = "555555555555"),
                    BulkUserMappingEntry(email = "UPPER_Case-99@ex-ample.io", awsAccountId = "666666666666")
                )
            )
        )

        assertThat(result.errors).isEmpty()
        assertThat(result.created + result.createdPending).isEqualTo(2)
    }
}
