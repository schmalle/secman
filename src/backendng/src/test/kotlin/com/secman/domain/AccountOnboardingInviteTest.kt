package com.secman.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * The invite token is a credential: whoever holds one can create a risk assessment as an account
 * owner. These tests pin the three properties that make holding one hard and losing one cheap.
 */
class AccountOnboardingInviteTest {

    private fun invite(
        expiresAt: LocalDateTime = LocalDateTime.now().plusDays(14),
        status: InviteStatus = InviteStatus.PENDING,
        accountId: String = "123456789012"
    ) = AccountOnboardingInvite(
        id = 1L,
        token = AccountOnboardingInvite.generateToken(),
        awsAccountId = accountId,
        ownerEmail = "alice@corp.com",
        expiresAt = expiresAt,
        status = status
    )

    @Test
    fun `tokens are 256 bits of lowercase hex`() {
        val token = AccountOnboardingInvite.generateToken()

        assertThat(token).hasSize(64)
        assertThat(token).matches("^[a-f0-9]{64}$")
        assertThat(AccountOnboardingInvite.TOKEN_PATTERN.matches(token)).isTrue()
    }

    @Test
    fun `tokens do not repeat`() {
        // Not a randomness test — a smoke check that generateToken is not accidentally memoised
        // or seeded per instance, which would be catastrophic and easy to miss.
        val tokens = (1..500).map { AccountOnboardingInvite.generateToken() }.toSet()
        assertThat(tokens).hasSize(500)
    }

    @Test
    fun `the token pattern rejects everything a lookup should refuse without touching the database`() {
        for (candidate in listOf(
            "",
            "short",
            "A".repeat(64),                       // uppercase
            "g".repeat(64),                       // out of hex range
            "a".repeat(63),
            "a".repeat(65),
            "a".repeat(32) + "-" + "a".repeat(31) // a UUID-with-dashes shape
        )) {
            assertThat(AccountOnboardingInvite.TOKEN_PATTERN.matches(candidate))
                .describedAs("'%s'", candidate)
                .isFalse()
        }
    }

    @Test
    fun `only a pending, unexpired invite is usable`() {
        assertThat(invite().isUsable()).isTrue()
        assertThat(invite(expiresAt = LocalDateTime.now().minusMinutes(1)).isUsable()).isFalse()
        for (status in listOf(InviteStatus.SUBMITTED, InviteStatus.EXPIRED, InviteStatus.CANCELLED)) {
            assertThat(invite(status = status).isUsable()).describedAs("%s", status).isFalse()
        }
    }

    @Test
    fun `the account id is masked to its last four digits`() {
        // The public GET must not confirm a full account id to whoever presents a token.
        assertThat(invite(accountId = "123456789012").maskedAccountId()).isEqualTo("****9012")
        assertThat(invite(accountId = "12").maskedAccountId()).isEqualTo("****")
    }

    @Test
    fun `redact never reveals enough of a token to be usable`() {
        val token = AccountOnboardingInvite.generateToken()
        val redacted = AccountOnboardingInvite.redact(token)

        assertThat(redacted).hasSize(9).endsWith("…")
        assertThat(redacted).startsWith(token.take(8))
        assertThat(token).doesNotStartWith(redacted)
        assertThat(AccountOnboardingInvite.redact(null)).isEqualTo("<none>")
        assertThat(AccountOnboardingInvite.redact("")).isEqualTo("<none>")
    }

    @Test
    fun `toString cannot leak the token`() {
        // toString reaches logs and exception messages by accident more often than by design.
        val subject = invite()
        assertThat(subject.toString()).doesNotContain(subject.token)
        assertThat(subject.toString()).contains(subject.token.take(8))
    }

    @Test
    fun `the default expiry is shorter than the assessment token's thirty days`() {
        // This token creates a risk assessment rather than opening one that already exists, so
        // it deliberately lives a shorter life than AssessmentToken.
        assertThat(AccountOnboardingInvite.DEFAULT_EXPIRY_DAYS).isEqualTo(14)
        assertThat(AccountOnboardingInvite.DEFAULT_EXPIRY_DAYS).isLessThan(30)
        assertThat(AccountOnboardingInvite.MIN_EXPIRY_DAYS).isEqualTo(1)
        assertThat(AccountOnboardingInvite.MAX_EXPIRY_DAYS).isEqualTo(90)
    }
}
