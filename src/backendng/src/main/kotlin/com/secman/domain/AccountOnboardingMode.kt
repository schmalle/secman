package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * What SecMan does for the owner of a brand-new AWS account discovered by a
 * user-mapping import.
 *
 * The three modes are cumulative in what the owner receives, not in what they
 * cost the operator:
 *
 * - [WELCOME_ONLY] — a welcome mail, nothing else. Use when the account is known
 *   to be out of scope for an assessment, or when the assessment is driven elsewhere.
 * - [DIRECT] — the operator already knows which use case applies and names it.
 *   The assessment is created immediately, exactly as
 *   `--start-risk-assessment --risk-usecase` has always done.
 * - [GUIDED] — the operator does *not* know which use case applies, and the owner does.
 *   The owner is mailed a one-time link, answers a short questionnaire, and the
 *   combination of their answers resolves through admin-configured rules
 *   ([AccountOnboardingRule]) to a *set* of use cases. The assessment is created
 *   only on submit.
 */
@Serdeable
enum class AccountOnboardingMode {
    WELCOME_ONLY,
    DIRECT,
    GUIDED;

    companion object {

        /**
         * The single place the legacy flag and the new mode are reconciled.
         *
         * `startRiskAssessment` predates this enum and is still what every existing
         * caller sends — the `extensions/` clients, `ImportS3Command`, both E2E drivers.
         * Such a caller resolves to [DIRECT] and must keep getting *byte-identical*
         * behaviour, welcome mail included (i.e. absent — see
         * [com.secman.service.AccountOnboardingService.OnboardingPlan.sendWelcomeEmail]).
         *
         * @return the mode to run, or null when nothing should happen at all
         *         (no flag, no mode — today's default for an ordinary import).
         */
        fun resolve(explicit: AccountOnboardingMode?, startRiskAssessment: Boolean): AccountOnboardingMode? =
            explicit ?: if (startRiskAssessment) DIRECT else null

        /**
         * Reject the one combination that cannot be honoured, rather than guessing which
         * half the caller meant. Returns a human-readable message, or null when compatible.
         *
         * Callers phrase the message in their own vocabulary (the CLI names the flag, the
         * REST/MCP layers name the field) — this returns the neutral form.
         */
        fun validateCompatibility(explicit: AccountOnboardingMode?, startRiskAssessment: Boolean): String? {
            if (startRiskAssessment && explicit != null && explicit != DIRECT) {
                return "startRiskAssessment is only compatible with onboardingMode=DIRECT (got $explicit)"
            }
            return null
        }
    }
}
