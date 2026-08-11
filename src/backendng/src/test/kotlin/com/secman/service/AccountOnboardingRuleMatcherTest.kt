package com.secman.service

import com.secman.domain.AccountOnboardingChoice
import com.secman.domain.AccountOnboardingQuestion
import com.secman.domain.AccountOnboardingRule
import com.secman.domain.OnboardingInputType
import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.UseCase
import com.secman.repository.AccountOnboardingQuestionRepository
import com.secman.repository.AccountOnboardingRuleRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The semantic core of guided onboarding: answers in, use cases out.
 *
 * Every rule below states a property the doc claims verbatim, because these are the claims an
 * operator relies on when configuring a rule set they cannot otherwise test against a real
 * account owner.
 */
class AccountOnboardingRuleMatcherTest {

    private val ruleRepository = mockk<AccountOnboardingRuleRepository>(relaxed = true)
    private val questionRepository = mockk<AccountOnboardingQuestionRepository>(relaxed = true)
    private val scopeService = mockk<ReleaseRequirementScopeService>(relaxed = true)

    private lateinit var matcher: AccountOnboardingRuleMatcher

    private val activeRelease = Release(id = 42L, version = "2.3.0", name = "Q3 baseline")

    private val cloudBaseline = UseCase(id = 10L, name = "Cloud Baseline")
    private val dataProtection = UseCase(id = 11L, name = "Data Protection")
    private val internetExposure = UseCase(id = 12L, name = "Internet Exposure")

    private val environment = question(1L, "environment", OnboardingInputType.SINGLE_SELECT)
    private val production = choice(101L, environment, "production")
    private val test = choice(102L, environment, "test")

    private val customerData = question(2L, "customer-data", OnboardingInputType.SINGLE_SELECT)
    private val dataYes = choice(201L, customerData, "yes")
    private val dataNo = choice(202L, customerData, "no")

    private val dataTypes = question(3L, "data-types", OnboardingInputType.MULTI_SELECT)
    private val pii = choice(301L, dataTypes, "pii")
    private val financial = choice(302L, dataTypes, "financial")

    private fun question(id: Long, key: String, type: OnboardingInputType) =
        AccountOnboardingQuestion(id = id, questionKey = key, label = key, inputType = type)

    private fun choice(id: Long, question: AccountOnboardingQuestion, key: String, active: Boolean = true) =
        AccountOnboardingChoice(id = id, question = question, choiceKey = key, label = key, active = active)
            .also { question.choices.add(it) }

    private fun rule(
        id: Long,
        name: String,
        choices: Set<AccountOnboardingChoice>,
        useCases: Set<UseCase>,
        isDefault: Boolean = false,
        active: Boolean = true
    ) = AccountOnboardingRule(
        id = id, name = name, active = active, isDefault = isDefault,
        choices = choices.toMutableSet(), useCases = useCases.toMutableSet()
    )

    @BeforeEach
    fun setup() {
        matcher = AccountOnboardingRuleMatcher(ruleRepository, questionRepository, scopeService)
        every { scopeService.findActiveRelease() } returns activeRelease
        // Two requirements per use case, so a union of two use cases is visibly larger than one.
        every { scopeService.requirementsForRelease(eq(42L), any<Collection<Long>>()) } answers {
            val ids = secondArg<Collection<Long>>()
            ids.flatMap { id -> listOf(Requirement(id = id * 10, shortreq = "r$id-a"), Requirement(id = id * 10 + 1, shortreq = "r$id-b")) }
        }
    }

    private fun rules(vararg rules: AccountOnboardingRule) {
        every { ruleRepository.findByActiveTrueOrderByPriorityOrderAscIdAsc() } returns rules.filter { it.active }
    }

    // --- Union ---------------------------------------------------------------

    @Test
    fun `every matching rule contributes - nothing wins`() {
        rules(
            rule(1L, "Production", setOf(production), setOf(cloudBaseline)),
            rule(2L, "Customer data", setOf(dataYes), setOf(dataProtection))
        )

        val resolution = matcher.resolve(setOf(101L, 201L))

        assertThat(resolution.useCases.map { it.name }).containsExactlyInAnyOrder("Cloud Baseline", "Data Protection")
        assertThat(resolution.matchedRuleNames).containsExactly("Customer data", "Production")
        assertThat(resolution.failure).isNull()
        assertThat(resolution.requirementCount).isEqualTo(4)
    }

    @Test
    fun `use cases shared by two rules are deduplicated`() {
        rules(
            rule(1L, "Production", setOf(production), setOf(cloudBaseline)),
            rule(2L, "Customer data", setOf(dataYes), setOf(cloudBaseline, dataProtection))
        )

        val resolution = matcher.resolve(setOf(101L, 201L))

        assertThat(resolution.useCases).hasSize(2)
        assertThat(resolution.requirementCount).isEqualTo(4)
    }

    // --- Matching semantics --------------------------------------------------

    @Test
    fun `a rule spanning two questions needs both answers`() {
        rules(rule(1L, "Prod with data", setOf(production, dataYes), setOf(cloudBaseline)))

        assertThat(matcher.resolve(setOf(101L, 201L)).matchedRuleNames).containsExactly("Prod with data")
        // Only half the combination present -> no match.
        assertThat(matcher.resolve(setOf(101L)).matchedRuleNames).isEmpty()
        assertThat(matcher.resolve(setOf(201L)).matchedRuleNames).isEmpty()
    }

    @Test
    fun `a multi-select rule is satisfied when all its named choices were ticked`() {
        rules(rule(1L, "Sensitive", setOf(pii, financial), setOf(dataProtection)))

        assertThat(matcher.resolve(setOf(301L, 302L)).matchedRuleNames).containsExactly("Sensitive")
        assertThat(matcher.resolve(setOf(301L)).matchedRuleNames).isEmpty()
    }

    @Test
    fun `extra answers never prevent a match`() {
        rules(rule(1L, "Production", setOf(production), setOf(cloudBaseline)))

        assertThat(matcher.resolve(setOf(101L, 201L, 301L)).matchedRuleNames).containsExactly("Production")
    }

    @Test
    fun `an inactive rule is ignored`() {
        rules(
            rule(1L, "Production", setOf(production), setOf(cloudBaseline), active = false),
            rule(2L, "Test", setOf(test), setOf(internetExposure))
        )

        assertThat(matcher.resolve(setOf(101L)).matchedRuleNames).isEmpty()
    }

    @Test
    fun `an inactive choice cannot satisfy a rule`() {
        val retired = choice(103L, environment, "retired", active = false)
        rules(rule(1L, "Retired", setOf(retired), setOf(cloudBaseline)))

        // Even with the id submitted, an inactive choice must not fire the rule — otherwise
        // deactivating an answer would leave rules silently live.
        assertThat(matcher.resolve(setOf(103L)).matchedRuleNames).isEmpty()
    }

    // --- Default rule --------------------------------------------------------

    @Test
    fun `the default rule applies only when nothing else matched`() {
        rules(
            rule(1L, "Production", setOf(production), setOf(cloudBaseline)),
            rule(2L, "Fallback", emptySet(), setOf(internetExposure), isDefault = true)
        )

        val matched = matcher.resolve(setOf(101L))
        assertThat(matched.matchedRuleNames).containsExactly("Production")
        assertThat(matched.usedDefault).isFalse()

        val fell = matcher.resolve(setOf(102L))
        assertThat(fell.matchedRuleNames).containsExactly("Fallback")
        assertThat(fell.usedDefault).isTrue()
        assertThat(fell.useCases.map { it.name }).containsExactly("Internet Exposure")
    }

    @Test
    fun `the default rule never dilutes a real match`() {
        rules(
            rule(1L, "Production", setOf(production), setOf(cloudBaseline)),
            rule(2L, "Fallback", emptySet(), setOf(internetExposure), isDefault = true)
        )

        assertThat(matcher.resolve(setOf(101L)).useCases.map { it.name }).containsExactly("Cloud Baseline")
    }

    @Test
    fun `nothing matched and no fallback is reported, not silently empty`() {
        rules(rule(1L, "Production", setOf(production), setOf(cloudBaseline)))

        val resolution = matcher.resolve(setOf(102L))

        assertThat(resolution.failure).isEqualTo(AccountOnboardingRuleMatcher.ResolutionFailure.NO_RULE_MATCHED)
        assertThat(resolution.useCases).isEmpty()
        assertThat(resolution.isUsable).isFalse()
    }

    // --- Empty questionnaire -------------------------------------------------

    @Test
    fun `an empty questionnaire is never created`() {
        rules(rule(1L, "Production", setOf(production), setOf(cloudBaseline)))
        every { scopeService.requirementsForRelease(eq(42L), any<Collection<Long>>()) } returns emptyList()

        val resolution = matcher.resolve(setOf(101L))

        assertThat(resolution.failure).isEqualTo(AccountOnboardingRuleMatcher.ResolutionFailure.EMPTY_QUESTIONNAIRE)
        assertThat(resolution.isUsable).isFalse()
        // The matched rules are still reported, so the operator can see which rule resolved to
        // a use case the release has no requirements for.
        assertThat(resolution.matchedRuleNames).containsExactly("Production")
    }

    @Test
    fun `a rule whose use cases were deleted is a dead end, not an empty assessment`() {
        rules(rule(1L, "Orphan", setOf(production), emptySet()))

        val resolution = matcher.resolve(setOf(101L))

        assertThat(resolution.failure).isEqualTo(AccountOnboardingRuleMatcher.ResolutionFailure.EMPTY_QUESTIONNAIRE)
        assertThat(resolution.useCases).isEmpty()
    }

    @Test
    fun `no ACTIVE release is reported separately from a matching failure`() {
        rules(rule(1L, "Production", setOf(production), setOf(cloudBaseline)))
        every { scopeService.findActiveRelease() } returns null

        val resolution = matcher.resolve(setOf(101L))

        assertThat(resolution.failure).isEqualTo(AccountOnboardingRuleMatcher.ResolutionFailure.NO_ACTIVE_RELEASE)
        // The rules did match — the environment is what is missing.
        assertThat(resolution.matchedRuleNames).containsExactly("Production")
    }

    // --- Pre-flight ----------------------------------------------------------

    @Test
    fun `reachableUseCases unions every active rule`() {
        rules(
            rule(1L, "Production", setOf(production), setOf(cloudBaseline)),
            rule(2L, "Customer data", setOf(dataYes), setOf(cloudBaseline, dataProtection)),
            rule(3L, "Retired", setOf(test), setOf(internetExposure), active = false)
        )

        assertThat(matcher.reachableUseCases().map { it.name })
            .containsExactlyInAnyOrder("Cloud Baseline", "Data Protection")
        assertThat(matcher.hasUsableRules()).isTrue()
    }

    @Test
    fun `a rule set that resolves to nothing is not usable`() {
        rules(rule(1L, "Orphan", setOf(production), emptySet()))

        assertThat(matcher.hasUsableRules()).isFalse()
    }

    // --- Coverage matrix -----------------------------------------------------

    @Test
    fun `the coverage matrix enumerates single-select combinations and flags dead ends`() {
        every { questionRepository.findActiveWithChoices() } returns listOf(environment, customerData, dataTypes)
        rules(rule(1L, "Prod with data", setOf(production, dataYes), setOf(cloudBaseline)))

        val (rows, truncated) = matcher.coverageMatrix()

        // 2 environments x 2 customer-data answers. The multi-select question is deliberately
        // not enumerated — 2^n answers would swamp the cap on its own.
        assertThat(rows).hasSize(4)
        assertThat(truncated).isFalse()
        val covered = rows.single { it.combination.containsAll(listOf("environment=production", "customer-data=yes")) }
        assertThat(covered.matchedRuleNames).containsExactly("Prod with data")
        assertThat(covered.isDeadEnd).isFalse()
        assertThat(rows.count { it.isDeadEnd }).isEqualTo(3)
    }

    @Test
    fun `the coverage matrix is empty when there is nothing to enumerate`() {
        every { questionRepository.findActiveWithChoices() } returns emptyList()
        rules()

        val (rows, truncated) = matcher.coverageMatrix()

        assertThat(rows).isEmpty()
        assertThat(truncated).isFalse()
    }
}
