import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
    choiceIdsFromDraft,
    countDeadEnds,
    describeRule,
    describeRuleFull,
    draftFromRule,
    indexChoices,
    ruleSetWarnings,
    sortRulesForDisplay,
    validateRuleDraft,
    type RuleDraft,
} from './accountOnboardingRules';
import type { OnboardingQuestion, OnboardingRule } from '../../services/accountOnboardingService';

function question(
    key: string,
    label: string,
    choices: Array<[number, string, string]>,
    inputType: OnboardingQuestion['inputType'] = 'SINGLE_SELECT',
    active = true
): OnboardingQuestion {
    return {
        id: choices[0]?.[0] ?? 1,
        questionKey: key,
        label,
        helpText: null,
        inputType,
        displayOrder: 0,
        required: true,
        active,
        referencedByRules: 0,
        choices: choices.map(([id, choiceKey, choiceLabel], i) => ({
            id,
            choiceKey,
            label: choiceLabel,
            displayOrder: i,
            active: true,
        })),
    };
}

function rule(overrides: Partial<OnboardingRule> = {}): OnboardingRule {
    return {
        id: 1,
        name: 'Rule',
        description: null,
        active: true,
        priorityOrder: 0,
        isDefault: false,
        choiceIds: [],
        combination: [],
        useCaseIds: [10],
        useCases: ['Cloud Baseline'],
        createdBy: null,
        ...overrides,
    };
}

const environment = question('environment', 'Environment', [
    [1, 'production', 'Production'],
    [2, 'test', 'Test'],
]);
const customerData = question('customer-data', 'Handles customer data', [
    [3, 'yes', 'Yes'],
    [4, 'no', 'No'],
]);
const dataTypes = question(
    'data-types',
    'Data types',
    [
        [5, 'pii', 'Personal data'],
        [6, 'financial', 'Financial data'],
    ],
    'MULTI_SELECT'
);
const questions = [environment, customerData, dataTypes];

describe('indexChoices', () => {
    test('joins every choice back to its question', () => {
        const index = indexChoices(questions);
        assert.equal(index.size, 6);
        assert.equal(index.get(3)?.questionLabel, 'Handles customer data');
        assert.equal(index.get(3)?.choiceLabel, 'Yes');
        assert.equal(index.get(5)?.multiSelect, true);
        assert.equal(index.get(1)?.multiSelect, false);
    });
});

describe('describeRule', () => {
    test('renders a two-question combination as a sentence', () => {
        const index = indexChoices(questions);
        const text = describeRule(rule({ choiceIds: [1, 3] }), index);
        assert.equal(text, 'When Environment is Production and Handles customer data is Yes');
    });

    test('several choices of one multi-select question read as "or"', () => {
        const index = indexChoices(questions);
        const text = describeRule(rule({ choiceIds: [5, 6] }), index);
        assert.equal(text, 'When Data types is Personal data or Financial data');
    });

    test('the default rule reads as a fallback, not a condition', () => {
        const index = indexChoices(questions);
        assert.equal(describeRule(rule({ isDefault: true, choiceIds: [] }), index), 'When no other rule matches');
    });

    test('a rule whose choices no longer exist does not read as "matches everything"', () => {
        const index = indexChoices(questions);
        const text = describeRule(rule({ choiceIds: [999] }), index);
        assert.match(text, /no longer exist/);
    });

    test('the full sentence names the resolved use cases', () => {
        const index = indexChoices(questions);
        const text = describeRuleFull(rule({ choiceIds: [1], useCases: ['Cloud Baseline', 'Data Protection'] }), index);
        assert.equal(
            text,
            'When Environment is Production → start assessment for Cloud Baseline, Data Protection'
        );
    });
});

describe('draftFromRule / choiceIdsFromDraft', () => {
    test('round-trips a rule through the editor shape', () => {
        const draft = draftFromRule(rule({ choiceIds: [1, 3] }), questions);
        assert.deepEqual(draft['environment'], ['production']);
        assert.deepEqual(draft['customer-data'], ['yes']);
        // A question not part of the rule is "(any)" — an empty list, not absent.
        assert.deepEqual(draft['data-types'], []);
        assert.deepEqual(choiceIdsFromDraft(draft, questions).sort(), [1, 3]);
    });

    test('an empty draft produces no choice ids', () => {
        const draft = draftFromRule(rule({ choiceIds: [] }), questions);
        assert.deepEqual(choiceIdsFromDraft(draft, questions), []);
    });
});

describe('validateRuleDraft', () => {
    const emptyDraft: RuleDraft = { environment: [], 'customer-data': [], 'data-types': [] };

    test('accepts a well-formed rule', () => {
        const draft: RuleDraft = { ...emptyDraft, environment: ['production'] };
        assert.equal(validateRuleDraft('Prod', draft, [10], false, questions), null);
    });

    test('requires a name', () => {
        assert.match(String(validateRuleDraft('  ', emptyDraft, [10], true, questions)), /name/);
    });

    test('requires at least one use case', () => {
        const draft: RuleDraft = { ...emptyDraft, environment: ['production'] };
        assert.match(String(validateRuleDraft('Prod', draft, [], false, questions)), /at least one use case/);
    });

    test('a non-default rule with no answers is refused', () => {
        const message = validateRuleDraft('Prod', emptyDraft, [10], false, questions);
        assert.match(String(message), /default fallback/);
    });

    test('the default rule may name no answers', () => {
        assert.equal(validateRuleDraft('Fallback', emptyDraft, [10], true, questions), null);
    });

    test('a second default rule is refused, naming the existing one', () => {
        const message = validateRuleDraft('Fallback 2', emptyDraft, [10], true, questions, 'Fallback');
        assert.match(String(message), /"Fallback" is already the default/);
    });

    test('two answers to a single-select question are refused as unmatchable', () => {
        const draft: RuleDraft = { ...emptyDraft, environment: ['production', 'test'] };
        const message = validateRuleDraft('Impossible', draft, [10], false, questions);
        assert.match(String(message), /could ever match both/);
    });

    test('two answers to a multi-select question are fine', () => {
        const draft: RuleDraft = { ...emptyDraft, 'data-types': ['pii', 'financial'] };
        assert.equal(validateRuleDraft('Sensitive', draft, [10], false, questions), null);
    });
});

describe('countDeadEnds', () => {
    test('counts only the dead-end rows', () => {
        assert.equal(countDeadEnds([{ deadEnd: true }, { deadEnd: false }, { deadEnd: true }]), 2);
        assert.equal(countDeadEnds([]), 0);
    });
});

describe('ruleSetWarnings', () => {
    test('a healthy rule set warns about nothing', () => {
        const rules = [rule({ choiceIds: [1] }), rule({ id: 2, name: 'Fallback', isDefault: true })];
        const coverage = { rows: [{ deadEnd: false }], hasDefaultRule: true, releaseVersion: '2.1.0' };
        assert.deepEqual(ruleSetWarnings(rules, questions, coverage), []);
    });

    test('a missing ACTIVE release is named before anything about rules', () => {
        const rules = [rule({ choiceIds: [1] })];
        const coverage = { rows: [], hasDefaultRule: false, releaseVersion: null };
        const warnings = ruleSetWarnings(rules, questions, coverage);
        assert.match(warnings[0], /ACTIVE requirements release/);
    });

    test('no rules is reported, and the fallback warning is not piled on top', () => {
        const warnings = ruleSetWarnings([], questions);
        assert.equal(warnings.filter((w) => /No active rules/.test(w)).length, 1);
        assert.equal(warnings.filter((w) => /fallback rule/.test(w)).length, 0);
    });

    test('rules without a fallback get the fallback warning', () => {
        const warnings = ruleSetWarnings([rule({ choiceIds: [1] })], questions);
        assert.equal(warnings.filter((w) => /No fallback rule/.test(w)).length, 1);
    });

    test('no active questions is reported', () => {
        const inactive = [question('x', 'X', [[1, 'a', 'A']], 'SINGLE_SELECT', false)];
        const warnings = ruleSetWarnings([rule({ choiceIds: [1] })], inactive);
        assert.ok(warnings.some((w) => /No active questions/.test(w)));
    });

    test('dead ends are counted and pluralised', () => {
        const rules = [rule({ choiceIds: [1] }), rule({ id: 2, name: 'Fallback', isDefault: true })];
        const one = ruleSetWarnings(rules, questions, {
            rows: [{ deadEnd: true }],
            hasDefaultRule: true,
            releaseVersion: '1.0.0',
        });
        assert.ok(one.some((w) => /1 answer combination would/.test(w)));
        const two = ruleSetWarnings(rules, questions, {
            rows: [{ deadEnd: true }, { deadEnd: true }],
            hasDefaultRule: true,
            releaseVersion: '1.0.0',
        });
        assert.ok(two.some((w) => /2 answer combinations would/.test(w)));
    });
});

describe('sortRulesForDisplay', () => {
    test('the fallback sorts last, the rest by priority then id', () => {
        const rules = [
            rule({ id: 3, name: 'C', priorityOrder: 2 }),
            rule({ id: 4, name: 'Fallback', isDefault: true, priorityOrder: 0 }),
            rule({ id: 1, name: 'A', priorityOrder: 1 }),
            rule({ id: 2, name: 'B', priorityOrder: 1 }),
        ];
        assert.deepEqual(sortRulesForDisplay(rules).map((r) => r.name), ['A', 'B', 'C', 'Fallback']);
    });

    test('does not mutate the input', () => {
        const rules = [rule({ id: 2, name: 'B' }), rule({ id: 1, name: 'A' })];
        sortRulesForDisplay(rules);
        assert.equal(rules[0].name, 'B');
    });
});
