package com.secman.service

import com.secman.domain.AccountOnboardingChoice
import com.secman.domain.AccountOnboardingQuestion
import com.secman.domain.AccountOnboardingRule
import com.secman.domain.UseCase
import com.secman.repository.AccountOnboardingQuestionRepository
import com.secman.repository.AccountOnboardingRuleRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Turns the account owner's answers into the set of use cases their risk assessment is
 * scoped to.
 *
 * The whole engine is set containment over choice ids. There is no expression language, no
 * operator precedence and no ordering to reason about:
 *
 * - **The matcher only ever sees choice ids.** A BOOLEAN question is a question with two
 *   choices, so there is no second code path for it.
 * - **Every matching rule contributes — nothing wins.** All active rules whose choices are
 *   fully contained in the submission are unioned and deduplicated. `priorityOrder` is
 *   display order for the admin UI and decides nothing here.
 * - **The default rule is a fallback, not a participant.** It applies only when no other
 *   rule matched, which is why [AccountOnboardingRule.matches] returns false for it.
 * - **An empty questionnaire is never created.** Resolving to use cases the ACTIVE release
 *   has no requirements for is a failure, not an empty assessment — the same check
 *   [AwsAccountRiskAssessmentService.validateStartRequest] makes up front.
 *
 * Nothing here writes. That is what lets the admin preview endpoint, the MCP preview tool
 * and the real submission share one implementation.
 */
@Singleton
open class AccountOnboardingRuleMatcher(
    private val ruleRepository: AccountOnboardingRuleRepository,
    private val questionRepository: AccountOnboardingQuestionRepository,
    private val releaseRequirementScopeService: ReleaseRequirementScopeService
) {
    private val log = LoggerFactory.getLogger(AccountOnboardingRuleMatcher::class.java)

    companion object {
        /**
         * Ceiling on the combinations the coverage matrix enumerates.
         *
         * The product of choice counts grows exponentially in the number of questions, and
         * this is reachable from an authenticated admin endpoint. Past the cap the UI shows
         * the per-rule view and says so, rather than the server building a 3-million-row list.
         */
        const val MAX_COMBINATIONS = 200
    }

    /** Why a resolution produced nothing usable. Null on success. */
    enum class ResolutionFailure {
        /** No active rule matched and no default rule exists. */
        NO_RULE_MATCHED,

        /** Rules matched, but the ACTIVE release has no requirements tagged with any of their use cases. */
        EMPTY_QUESTIONNAIRE,

        /** No ACTIVE release exists to measure the assessment against. */
        NO_ACTIVE_RELEASE
    }

    data class Resolution(
        val matchedRuleNames: List<String>,
        val useCases: Set<UseCase>,
        val usedDefault: Boolean,
        /** Requirements the pinned ACTIVE release contributes for [useCases]. */
        val requirementCount: Int,
        /** Version of the ACTIVE release the count was taken against, null when there is none. */
        val releaseVersion: String? = null,
        val failure: ResolutionFailure? = null
    ) {
        val isUsable: Boolean get() = failure == null && useCases.isNotEmpty()
    }

    /** One row of the admin coverage matrix: what a given combination of answers would produce. */
    data class CombinationCoverage(
        /** `questionKey=choiceKey` per question, in display order. */
        val combination: List<String>,
        val matchedRuleNames: List<String>,
        val useCaseNames: List<String>,
        val requirementCount: Int,
        val usedDefault: Boolean
    ) {
        /** True when an owner submitting this combination would hit a dead end. */
        val isDeadEnd: Boolean get() = matchedRuleNames.isEmpty() || requirementCount == 0
    }

    /**
     * Resolve a submission. Pure — no writes, no mail, safe to call from a preview.
     *
     * @param submittedChoiceIds every choice the owner selected, across all questions. Already
     *        validated against the active question set by the caller; unknown ids simply match
     *        nothing rather than erroring here.
     */
    open fun resolve(submittedChoiceIds: Set<Long>): Resolution {
        val activeRules = ruleRepository.findByActiveTrueOrderByPriorityOrderAscIdAsc()

        val matched = activeRules.filter { it.matches(submittedChoiceIds) }
        var usedDefault = false
        var effective = matched

        if (effective.isEmpty()) {
            // The fallback is consulted only now, so a default rule can never dilute a real match.
            val defaults = activeRules.filter { it.isDefault }
            if (defaults.isEmpty()) {
                log.info(
                    "Onboarding answers matched no rule and no default rule exists (choices={})",
                    submittedChoiceIds.size
                )
                return Resolution(
                    matchedRuleNames = emptyList(),
                    useCases = emptySet(),
                    usedDefault = false,
                    requirementCount = 0,
                    failure = ResolutionFailure.NO_RULE_MATCHED
                )
            }
            effective = defaults
            usedDefault = true
        }

        // Union, deduplicated by id. UseCase.equals is by name, which would also dedupe, but
        // ids are what the requirement lookup binds so key on those.
        val useCases = effective.flatMap { it.useCases }
            .filter { it.id != null }
            .associateBy { it.id!! }
            .values
            .toSet()

        val ruleNames = effective.map { it.name }.sorted()

        if (useCases.isEmpty()) {
            // Rule validation forbids saving a rule with no use cases, so this means a use case
            // was deleted underneath one. Report it as a dead end rather than creating an empty
            // assessment.
            log.warn("Onboarding rules {} matched but resolve to no use case", ruleNames)
            return Resolution(ruleNames, emptySet(), usedDefault, 0, null, ResolutionFailure.EMPTY_QUESTIONNAIRE)
        }

        val activeRelease = releaseRequirementScopeService.findActiveRelease()
            ?: return Resolution(ruleNames, useCases, usedDefault, 0, null, ResolutionFailure.NO_ACTIVE_RELEASE)

        val requirementCount = releaseRequirementScopeService
            .requirementsForRelease(activeRelease.id!!, useCases.mapNotNull { it.id })
            .size

        if (requirementCount == 0) {
            log.warn(
                "Onboarding rules {} resolve to use cases {} but ACTIVE release {} has no requirements tagged with any of them",
                ruleNames, useCases.map { it.name }, activeRelease.version
            )
            return Resolution(
                ruleNames, useCases, usedDefault, 0, activeRelease.version, ResolutionFailure.EMPTY_QUESTIONNAIRE
            )
        }

        return Resolution(ruleNames, useCases, usedDefault, requirementCount, activeRelease.version, null)
    }

    /**
     * Every use case any active rule can resolve to.
     *
     * The fail-fast pre-flight for GUIDED onboarding: mailing an owner a link that cannot
     * possibly produce an assessment wastes their time and hides the misconfiguration until
     * they click. Checked before the first invite is minted.
     */
    open fun reachableUseCases(): Set<UseCase> =
        ruleRepository.findByActiveTrueOrderByPriorityOrderAscIdAsc()
            .flatMap { it.useCases }
            .filter { it.id != null }
            .associateBy { it.id!! }
            .values
            .toSet()

    /** True when at least one active rule names at least one use case. */
    open fun hasUsableRules(): Boolean = reachableUseCases().isNotEmpty()

    /**
     * Enumerate what every possible combination of answers would produce.
     *
     * This is the screen that makes a misconfigured rule set visible *before* an owner walks
     * into it: a combination with no matching rule, or one resolving to zero requirements, is
     * a dead end the owner would hit after clicking a link. Capped at [MAX_COMBINATIONS];
     * beyond that the caller is told the list is truncated rather than being handed a partial
     * list that looks complete.
     *
     * Only SINGLE_SELECT/BOOLEAN questions are enumerated — a MULTI_SELECT question has
     * 2^n answers and would blow the cap on its own. Rules keyed on multi-select choices
     * still show in the per-rule view.
     */
    open fun coverageMatrix(): Pair<List<CombinationCoverage>, Boolean> {
        val questions = questionRepository.findActiveWithChoices()
            .filter { !it.inputType.allowsMultiple() }
            .filter { it.choices.any { c -> c.active } }
        if (questions.isEmpty()) return emptyList<CombinationCoverage>() to false

        val axes: List<Pair<AccountOnboardingQuestion, List<AccountOnboardingChoice>>> =
            questions.map { q -> q to q.choices.filter { it.active } }

        val total = axes.fold(1L) { acc, (_, choices) -> acc * choices.size }
        val truncated = total > MAX_COMBINATIONS

        val rows = mutableListOf<CombinationCoverage>()
        cartesian(axes, 0, mutableListOf()) { picked ->
            if (rows.size >= MAX_COMBINATIONS) return@cartesian false
            val resolution = resolve(picked.mapNotNull { it.second.id }.toSet())
            rows += CombinationCoverage(
                combination = picked.map { (q, c) -> "${q.questionKey}=${c.choiceKey}" },
                matchedRuleNames = resolution.matchedRuleNames,
                useCaseNames = resolution.useCases.map { it.name }.sorted(),
                requirementCount = resolution.requirementCount,
                usedDefault = resolution.usedDefault
            )
            true
        }
        return rows to truncated
    }

    /**
     * Depth-first walk of the answer space. [visit] returns false to stop early, which is how
     * the [MAX_COMBINATIONS] cap avoids materializing the full product before truncating it.
     */
    private fun cartesian(
        axes: List<Pair<AccountOnboardingQuestion, List<AccountOnboardingChoice>>>,
        index: Int,
        picked: MutableList<Pair<AccountOnboardingQuestion, AccountOnboardingChoice>>,
        visit: (List<Pair<AccountOnboardingQuestion, AccountOnboardingChoice>>) -> Boolean
    ): Boolean {
        if (index == axes.size) return visit(picked.toList())
        val (question, choices) = axes[index]
        for (choice in choices) {
            picked += question to choice
            val keepGoing = cartesian(axes, index + 1, picked, visit)
            picked.removeAt(picked.size - 1)
            if (!keepGoing) return false
        }
        return true
    }
}
