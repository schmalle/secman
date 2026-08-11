package com.secman.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The backward-compatibility contract, in one place.
 *
 * `startRiskAssessment` predates onboarding modes and is what every existing caller sends: the
 * `extensions/` clients, `ImportS3Command`, and both existing E2E drivers. Nothing in this build
 * compiles against those clients, so this test is the closest thing to a compiler for that
 * contract.
 */
class AccountOnboardingModeTest {

    @Test
    fun `the legacy flag alone resolves to DIRECT`() {
        assertThat(AccountOnboardingMode.resolve(null, startRiskAssessment = true))
            .isEqualTo(AccountOnboardingMode.DIRECT)
    }

    @Test
    fun `neither flag nor mode means do nothing - an ordinary import stays ordinary`() {
        assertThat(AccountOnboardingMode.resolve(null, startRiskAssessment = false)).isNull()
    }

    @Test
    fun `an explicit mode wins over the legacy flag`() {
        for (mode in AccountOnboardingMode.entries) {
            assertThat(AccountOnboardingMode.resolve(mode, startRiskAssessment = false)).isEqualTo(mode)
        }
        assertThat(AccountOnboardingMode.resolve(AccountOnboardingMode.DIRECT, startRiskAssessment = true))
            .isEqualTo(AccountOnboardingMode.DIRECT)
    }

    @Test
    fun `a non-DIRECT mode with the legacy flag is rejected, never guessed`() {
        for (mode in listOf(AccountOnboardingMode.WELCOME_ONLY, AccountOnboardingMode.GUIDED)) {
            assertThat(AccountOnboardingMode.validateCompatibility(mode, startRiskAssessment = true))
                .describedAs("mode %s", mode)
                .isNotNull()
                .contains("DIRECT")
        }
    }

    @Test
    fun `coherent combinations are accepted`() {
        assertThat(AccountOnboardingMode.validateCompatibility(null, startRiskAssessment = true)).isNull()
        assertThat(AccountOnboardingMode.validateCompatibility(null, startRiskAssessment = false)).isNull()
        assertThat(
            AccountOnboardingMode.validateCompatibility(AccountOnboardingMode.DIRECT, startRiskAssessment = true)
        ).isNull()
        for (mode in AccountOnboardingMode.entries) {
            assertThat(AccountOnboardingMode.validateCompatibility(mode, startRiskAssessment = false))
                .describedAs("mode %s without the legacy flag", mode)
                .isNull()
        }
    }

    @Test
    fun `the three modes are exactly the ones the CLI and MCP enums list`() {
        // Three copies of this list exist by necessity — the CLI is a separate Gradle module and
        // the MCP schema is a string enum. Pin the source of truth so a fourth mode is noticed.
        assertThat(AccountOnboardingMode.entries.map { it.name })
            .containsExactly("WELCOME_ONLY", "DIRECT", "GUIDED")
    }
}
