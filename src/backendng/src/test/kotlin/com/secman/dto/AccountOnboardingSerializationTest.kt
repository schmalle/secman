package com.secman.dto

import io.micronaut.serde.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guards the wire contract of the account-onboarding endpoints.
 *
 * Micronaut Serde's default inclusion is NON_EMPTY, so an empty list is dropped
 * from the payload entirely and the client sees `undefined` rather than `[]`.
 * The admin onboarding UI reads every one of these lists with a direct
 * `.join()` / `.map()` / `.length` / `for..of`, each of which throws on
 * undefined — and throws *before* the `|| '(no use case)'`-style fallback
 * sitting right next to it can run, so the guard that appears to be there is
 * unreachable exactly when it is needed.
 *
 * That is not hypothetical: `GET /api/account-onboarding/rules/coverage`
 * omitting `rows` on an instance with no rules configured crashed the whole
 * `/admin/account-onboarding` island (caught by `/e2ejs`). These assertions
 * exist so the next such field is caught here rather than in the browser.
 *
 * Sibling of [UserDashboardSerializationTest], which pins the same property for
 * the same reason on the user dashboard.
 *
 * ID prefix: AOS-*
 */
class AccountOnboardingSerializationTest {

    private val mapper = ObjectMapper.getDefault()

    /** Every key that must survive an empty collection, per response type. */
    private fun assertKeepsEmptyArrays(json: String, vararg keys: String) {
        for (key in keys) {
            assertTrue(
                json.contains("\"$key\":[]"),
                "$key must serialize as [] when empty, got: $json"
            )
        }
    }

    @Test
    @DisplayName("AOS-001: coverage response keeps an empty rows array — the crash /e2ejs actually caught")
    fun coverageKeepsEmptyRows() {
        val json = mapper.writeValueAsString(
            OnboardingCoverageResponse(rows = emptyList(), truncated = false, hasDefaultRule = false)
        )

        assertKeepsEmptyArrays(json, "rows")
    }

    @Test
    @DisplayName("AOS-002: a rule with no choices or use cases keeps all four arrays")
    fun ruleResponseKeepsEmptyArrays() {
        val json = mapper.writeValueAsString(
            OnboardingRuleResponse(
                id = 1L,
                name = "rule",
                description = null,
                active = true,
                priorityOrder = 0,
                isDefault = false
            )
        )

        // useCases backs `rule.useCases.join(', ') || '(no use case)'` in
        // AccountOnboardingRuleEditor — the fallback proves empty is expected.
        assertKeepsEmptyArrays(json, "choiceIds", "combination", "useCaseIds", "useCases")
    }

    @Test
    @DisplayName("AOS-003: a question with no choices keeps an empty choices array")
    fun questionResponseKeepsEmptyChoices() {
        val json = mapper.writeValueAsString(
            OnboardingQuestionResponse(
                id = 1L,
                questionKey = "q",
                label = "Q",
                helpText = null,
                inputType = "SINGLE_SELECT",
                displayOrder = 0,
                required = true,
                active = true
            )
        )

        assertKeepsEmptyArrays(json, "choices")
    }

    @Test
    @DisplayName("AOS-004: a preview that resolves to nothing keeps both arrays")
    fun previewResponseKeepsEmptyArrays() {
        val json = mapper.writeValueAsString(
            OnboardingPreviewResponse(failure = "NO_RULE_MATCHED")
        )

        assertKeepsEmptyArrays(json, "matchedRules", "useCases")
    }

    @Test
    @DisplayName("AOS-005: a coverage row that matches nothing keeps all three arrays")
    fun coverageRowKeepsEmptyArrays() {
        val json = mapper.writeValueAsString(
            OnboardingCoverageRow(
                combination = emptyList(),
                matchedRules = emptyList(),
                useCases = emptyList(),
                requirementCount = 0,
                usedDefault = false,
                deadEnd = true
            )
        )

        assertKeepsEmptyArrays(json, "combination", "matchedRules", "useCases")
    }

    @Test
    @DisplayName("AOS-006: an empty rule matrix keeps rules and reachableUseCases")
    fun ruleMatrixKeepsEmptyArrays() {
        val json = mapper.writeValueAsString(
            OnboardingRuleMatrix(
                questionCount = 0,
                choiceCount = 0,
                activeRuleCount = 0,
                hasDefaultRule = false
            )
        )

        assertKeepsEmptyArrays(json, "rules", "reachableUseCases")
    }

    @Test
    @DisplayName("AOS-007: a simulation with no results keeps both arrays")
    fun simulateResponseKeepsEmptyArrays() {
        val json = mapper.writeValueAsString(
            SimulateOnboardingResponse(
                awsAccountId = "123456789012",
                ownerEmail = "owner@example.com",
                mode = "GUIDED",
                dryRun = true
            )
        )

        assertKeepsEmptyArrays(json, "onboarding", "riskAssessments")
    }

    @Test
    @DisplayName("AOS-008: the public questionnaire keeps an empty questions array")
    fun publicQuestionnaireKeepsEmptyQuestions() {
        val json = mapper.writeValueAsString(
            PublicQuestionnaireResponse(
                maskedAccountId = "****6789",
                expiresAt = "2026-01-01T00:00:00Z",
                questions = emptyList()
            )
        )

        assertKeepsEmptyArrays(json, "questions")
    }

    @Test
    @DisplayName("AOS-009: a submission resolving to no use cases keeps the array")
    fun submitAnswersResponseKeepsEmptyUseCases() {
        val json = mapper.writeValueAsString(
            SubmitAnswersResponse(status = "OK", riskAssessmentId = null)
        )

        assertKeepsEmptyArrays(json, "useCases")
    }
}
