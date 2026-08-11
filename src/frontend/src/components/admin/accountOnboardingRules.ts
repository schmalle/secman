import type {
    OnboardingQuestion,
    OnboardingRule,
} from '../../services/accountOnboardingService';

/**
 * Pure logic behind the onboarding rule editor.
 *
 * Extracted from the `.tsx` islands because Node's test runner cannot parse JSX. The interesting
 * part is not the rendering — it is turning a set of choice ids into a sentence an operator can
 * read, and refusing to build a rule nobody could ever match. Both are covered by `npm test`.
 */

/** The "this question is not part of this rule" option in the rule builder. */
export const ANY_CHOICE = '' as const;

/** questionKey -> the choiceKeys this rule requires, or [] meaning "(any)". */
export type RuleDraft = Record<string, string[]>;

export interface ChoiceRef {
    choiceId: number;
    questionKey: string;
    questionLabel: string;
    choiceKey: string;
    choiceLabel: string;
    /** False when the question accepts only one answer — used to catch impossible combinations. */
    multiSelect: boolean;
}

/**
 * Flatten questions into a choice-id lookup.
 *
 * Rules are stored as choice ids, but every human-facing view needs the question and choice
 * *labels* alongside — this is the one place that join happens.
 */
export function indexChoices(questions: OnboardingQuestion[]): Map<number, ChoiceRef> {
    const index = new Map<number, ChoiceRef>();
    for (const question of questions) {
        for (const choice of question.choices) {
            if (choice.id == null) continue;
            index.set(choice.id, {
                choiceId: choice.id,
                questionKey: question.questionKey,
                questionLabel: question.label,
                choiceKey: choice.choiceKey,
                choiceLabel: choice.label,
                multiSelect: question.inputType === 'MULTI_SELECT',
            });
        }
    }
    return index;
}

/**
 * Render a rule as a sentence rather than a JSON blob.
 *
 * "When Environment is Production and Handles customer data is Yes" beats
 * `{"choiceIds":[3,7]}` by enough that it is worth a dedicated function — this string is what
 * makes the rule list scannable, and it is the whole reason the editor is comprehensible.
 */
export function describeRule(rule: OnboardingRule, index: Map<number, ChoiceRef>): string {
    if (rule.isDefault) {
        return 'When no other rule matches';
    }
    const byQuestion = new Map<string, { label: string; choices: string[] }>();
    for (const choiceId of rule.choiceIds) {
        const ref = index.get(choiceId);
        if (!ref) continue;
        const entry = byQuestion.get(ref.questionKey) ?? { label: ref.questionLabel, choices: [] };
        entry.choices.push(ref.choiceLabel);
        byQuestion.set(ref.questionKey, entry);
    }
    if (byQuestion.size === 0) {
        // A rule whose choices all vanished (a question was deleted under it) must not read as
        // "matches everything" — that is the opposite of what it now does, which is nothing.
        return 'When — (this rule references answers that no longer exist)';
    }
    const clauses = [...byQuestion.values()].map(
        (entry) => `${entry.label} is ${entry.choices.join(' or ')}`
    );
    return `When ${clauses.join(' and ')}`;
}

/** The full sentence, condition and consequence. */
export function describeRuleFull(rule: OnboardingRule, index: Map<number, ChoiceRef>): string {
    const target = rule.useCases.length > 0 ? rule.useCases.join(', ') : '(no use case)';
    return `${describeRule(rule, index)} → start assessment for ${target}`;
}

/** Turn a saved rule back into the editor's per-question draft shape. */
export function draftFromRule(rule: OnboardingRule, questions: OnboardingQuestion[]): RuleDraft {
    const index = indexChoices(questions);
    const draft: RuleDraft = {};
    for (const question of questions) {
        draft[question.questionKey] = [];
    }
    for (const choiceId of rule.choiceIds) {
        const ref = index.get(choiceId);
        if (!ref) continue;
        draft[ref.questionKey] = [...(draft[ref.questionKey] ?? []), ref.choiceKey];
    }
    return draft;
}

/** Turn the editor's draft back into the choice ids the API stores. */
export function choiceIdsFromDraft(draft: RuleDraft, questions: OnboardingQuestion[]): number[] {
    const ids: number[] = [];
    for (const question of questions) {
        const chosen = draft[question.questionKey] ?? [];
        for (const choiceKey of chosen) {
            const choice = question.choices.find((c) => c.choiceKey === choiceKey);
            if (choice?.id != null) ids.push(choice.id);
        }
    }
    return ids;
}

/**
 * Refuse the rule shapes the backend also refuses, so the operator finds out while editing
 * rather than on save.
 *
 * @returns a message, or null when the draft is saveable. The server re-checks all of it.
 */
export function validateRuleDraft(
    name: string,
    draft: RuleDraft,
    useCaseIds: number[],
    isDefault: boolean,
    questions: OnboardingQuestion[],
    otherDefaultRuleName?: string | null
): string | null {
    if (!name.trim()) return 'Give the rule a name.';
    if (useCaseIds.length === 0) return 'A rule must resolve to at least one use case.';

    const choiceIds = choiceIdsFromDraft(draft, questions);
    if (!isDefault && choiceIds.length === 0) {
        return 'Pick at least one answer, or mark this as the default fallback rule.';
    }
    if (isDefault && otherDefaultRuleName) {
        return `"${otherDefaultRuleName}" is already the default fallback rule.`;
    }
    // Two answers to a single-select question can never both be given, so the rule could never
    // fire. Caught here because a rule that silently never matches is the worst outcome: it
    // looks configured and does nothing.
    for (const question of questions) {
        const chosen = draft[question.questionKey] ?? [];
        if (question.inputType !== 'MULTI_SELECT' && chosen.length > 1) {
            return `"${question.label}" accepts only one answer, so no owner could ever match both.`;
        }
    }
    return null;
}

/**
 * Combinations an owner could submit that no rule covers.
 *
 * Surfaced as a count so the editor can show a persistent warning: this is precisely the
 * failure that is invisible until a real account owner walks into it.
 */
export function countDeadEnds(rows: Array<{ deadEnd: boolean }>): number {
    return rows.filter((row) => row.deadEnd).length;
}

/**
 * What to warn the operator about, in the order that matters.
 *
 * Ordered deliberately: a rule set with no fallback fails for *some* owners, one with no rules
 * at all fails for every owner, and one with no ACTIVE release fails regardless of the rules.
 * The most total failure is named first.
 */
export function ruleSetWarnings(
    rules: OnboardingRule[],
    questions: OnboardingQuestion[],
    coverage?: { rows: Array<{ deadEnd: boolean }>; hasDefaultRule: boolean; releaseVersion: string | null }
): string[] {
    const warnings: string[] = [];
    const activeQuestions = questions.filter((q) => q.active);
    const activeRules = rules.filter((r) => r.active);

    if (coverage && !coverage.releaseVersion) {
        warnings.push(
            'No ACTIVE requirements release. Guided onboarding is rejected up front until one is activated.'
        );
    }
    if (activeQuestions.length === 0) {
        warnings.push('No active questions. Guided onboarding has nothing to ask.');
    }
    if (activeRules.length === 0) {
        warnings.push('No active rules. An owner answering the questionnaire could not be given an assessment.');
    } else if (!activeRules.some((r) => r.isDefault)) {
        warnings.push(
            'No fallback rule: owners whose answers match nothing will be told a security champion will follow up.'
        );
    }
    if (coverage) {
        const deadEnds = countDeadEnds(coverage.rows);
        if (deadEnds > 0) {
            warnings.push(
                `${deadEnds} answer combination${deadEnds === 1 ? '' : 's'} would produce no requirements.`
            );
        }
    }
    return warnings;
}

/** Sort rules the way the list renders them: the fallback last, everything else by priority. */
export function sortRulesForDisplay(rules: OnboardingRule[]): OnboardingRule[] {
    return [...rules].sort((a, b) => {
        if (a.isDefault !== b.isDefault) return a.isDefault ? 1 : -1;
        if (a.priorityOrder !== b.priorityOrder) return a.priorityOrder - b.priorityOrder;
        return (a.id ?? 0) - (b.id ?? 0);
    });
}
