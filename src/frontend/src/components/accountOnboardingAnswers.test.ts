import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
    allowsMultiple,
    buildSubmitPayload,
    isRetryable,
    isSelected,
    messageForError,
    summariseResult,
    toggleChoice,
    validateSelection,
    MAX_TOTAL_SELECTIONS,
    type OnboardingQuestion,
    type Selection,
} from './accountOnboardingAnswers';

const single: OnboardingQuestion = {
    questionKey: 'environment',
    label: 'Which environment?',
    inputType: 'SINGLE_SELECT',
    required: true,
    choices: [
        { choiceKey: 'production', label: 'Production' },
        { choiceKey: 'test', label: 'Test' },
    ],
};

const multi: OnboardingQuestion = {
    questionKey: 'data-types',
    label: 'What data does it hold?',
    inputType: 'MULTI_SELECT',
    required: false,
    choices: [
        { choiceKey: 'pii', label: 'Personal data' },
        { choiceKey: 'financial', label: 'Financial data' },
    ],
};

const boolean: OnboardingQuestion = {
    questionKey: 'internet-facing',
    label: 'Internet facing?',
    inputType: 'BOOLEAN',
    required: true,
    choices: [
        { choiceKey: 'yes', label: 'Yes' },
        { choiceKey: 'no', label: 'No' },
    ],
};

describe('allowsMultiple', () => {
    test('only MULTI_SELECT accepts more than one answer', () => {
        assert.equal(allowsMultiple(multi), true);
        assert.equal(allowsMultiple(single), false);
        // BOOLEAN is a two-choice single-select, not a third kind of thing.
        assert.equal(allowsMultiple(boolean), false);
    });
});

describe('toggleChoice', () => {
    test('single-select replaces the previous answer', () => {
        let selection: Selection = {};
        selection = toggleChoice(selection, single, 'production');
        assert.deepEqual(selection['environment'], ['production']);
        selection = toggleChoice(selection, single, 'test');
        assert.deepEqual(selection['environment'], ['test']);
    });

    test('single-select clicking the selected option clears it', () => {
        let selection: Selection = toggleChoice({}, single, 'production');
        selection = toggleChoice(selection, single, 'production');
        assert.deepEqual(selection['environment'], []);
    });

    test('multi-select accumulates and toggles off', () => {
        let selection: Selection = {};
        selection = toggleChoice(selection, multi, 'pii');
        selection = toggleChoice(selection, multi, 'financial');
        assert.deepEqual(selection['data-types'], ['pii', 'financial']);
        selection = toggleChoice(selection, multi, 'pii');
        assert.deepEqual(selection['data-types'], ['financial']);
    });

    test('does not mutate the input', () => {
        const selection: Selection = { environment: ['production'] };
        const next = toggleChoice(selection, single, 'test');
        assert.deepEqual(selection['environment'], ['production']);
        assert.deepEqual(next['environment'], ['test']);
    });

    test('answers to different questions are independent', () => {
        let selection: Selection = toggleChoice({}, single, 'production');
        selection = toggleChoice(selection, multi, 'pii');
        assert.deepEqual(selection['environment'], ['production']);
        assert.deepEqual(selection['data-types'], ['pii']);
    });
});

describe('isSelected', () => {
    test('reports membership and copes with an unanswered question', () => {
        const selection: Selection = { environment: ['production'] };
        assert.equal(isSelected(selection, 'environment', 'production'), true);
        assert.equal(isSelected(selection, 'environment', 'test'), false);
        assert.equal(isSelected(selection, 'data-types', 'pii'), false);
    });
});

describe('validateSelection', () => {
    test('accepts a complete selection', () => {
        const selection: Selection = { environment: ['production'], 'internet-facing': ['no'] };
        assert.equal(validateSelection([single, boolean], selection), null);
    });

    test('names the unanswered required question', () => {
        const message = validateSelection([single, boolean], { environment: ['production'] });
        assert.match(String(message), /Internet facing\?/);
    });

    test('an optional question may be left blank', () => {
        assert.equal(validateSelection([multi], {}), null);
    });

    test('rejects two answers to a single-select question', () => {
        const selection: Selection = { environment: ['production', 'test'] };
        const message = validateSelection([single], selection);
        assert.match(String(message), /only one answer/);
    });

    test('rejects more selections than the backend accepts', () => {
        const many: OnboardingQuestion = {
            ...multi,
            choices: Array.from({ length: MAX_TOTAL_SELECTIONS + 1 }, (_, i) => ({
                choiceKey: `c${i}`,
                label: `Choice ${i}`,
            })),
        };
        const selection: Selection = {
            'data-types': many.choices.map((c) => c.choiceKey),
        };
        assert.match(String(validateSelection([many], selection)), /Too many selections/);
    });
});

describe('buildSubmitPayload', () => {
    test('includes only answered questions', () => {
        const selection: Selection = { environment: ['production'], 'data-types': [] };
        const payload = buildSubmitPayload([single, multi], selection);
        assert.deepEqual(payload, { answers: [{ questionKey: 'environment', choiceKeys: ['production'] }] });
    });

    test('preserves question order and multi-select values', () => {
        const selection: Selection = { 'data-types': ['pii', 'financial'], environment: ['test'] };
        const payload = buildSubmitPayload([single, multi], selection);
        assert.deepEqual(payload.answers.map((a) => a.questionKey), ['environment', 'data-types']);
        assert.deepEqual(payload.answers[1].choiceKeys, ['pii', 'financial']);
    });

    test('an empty selection produces an empty answer list, not undefined', () => {
        assert.deepEqual(buildSubmitPayload([single], {}), { answers: [] });
    });
});

describe('messageForError', () => {
    test('NOT_FOUND stays vague, matching the backend single error body', () => {
        const message = messageForError('NOT_FOUND');
        assert.match(message, /invalid or has expired/);
        // Must not distinguish expired from unknown — that would undo the server's care.
        assert.doesNotMatch(message, /already (used|submitted)/i);
    });

    test('an unresolved submission is framed as recorded, not failed', () => {
        assert.match(messageForError('NO_RULE_MATCHED'), /recorded/);
        assert.match(messageForError('EMPTY_QUESTIONNAIRE'), /security champion/);
    });

    test('validation errors surface the server message when there is one', () => {
        assert.equal(messageForError('VALIDATION_ERROR', 'Question X must be answered'), 'Question X must be answered');
    });

    test('an unknown code still produces something actionable', () => {
        assert.match(messageForError(undefined), /try again/i);
        assert.equal(messageForError('SOMETHING_NEW', 'Server said so'), 'Server said so');
    });
});

describe('isRetryable', () => {
    test('only transient or fixable outcomes are retryable', () => {
        assert.equal(isRetryable('VALIDATION_ERROR'), true);
        assert.equal(isRetryable('RATE_LIMITED'), true);
        assert.equal(isRetryable('INTERNAL_ERROR'), true);
        assert.equal(isRetryable('NOT_FOUND'), false);
        assert.equal(isRetryable('NO_RULE_MATCHED'), false);
    });
});

describe('summariseResult', () => {
    test('names the resolved use cases so the owner sees their answers were understood', () => {
        const text = summariseResult(['Cloud Baseline', 'Data Protection'], 7, '2026-08-25');
        assert.match(text, /Cloud Baseline, Data Protection/);
        assert.match(text, /7 requirements/);
        assert.match(text, /due by 2026-08-25/);
    });

    test('singular requirement reads correctly', () => {
        assert.match(summariseResult(['Cloud Baseline'], 1), /1 requirement\b/);
    });

    test('copes with no use cases and no deadline', () => {
        const text = summariseResult([], 0, null);
        assert.match(text, /your account/);
        assert.doesNotMatch(text, /due by/);
    });
});
