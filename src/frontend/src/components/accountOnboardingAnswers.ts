/**
 * Pure logic behind the public onboarding questionnaire.
 *
 * Extracted out of `AccountOnboardingQuestionnaire.tsx` because Node's test runner cannot parse
 * JSX — anything testable has to live in a `.ts` sibling (see docs/TESTING.md §Frontend). That
 * constraint is doing real work here: answer validation and the error-message mapping are the
 * parts most likely to drift from the backend, and they are now covered by `npm test`.
 */

export type OnboardingInputType = 'SINGLE_SELECT' | 'MULTI_SELECT' | 'BOOLEAN';

export interface OnboardingChoice {
    choiceKey: string;
    label: string;
}

export interface OnboardingQuestion {
    questionKey: string;
    label: string;
    helpText?: string | null;
    inputType: OnboardingInputType;
    required: boolean;
    choices: OnboardingChoice[];
}

export interface Questionnaire {
    maskedAccountId: string;
    expiresAt: string;
    questions: OnboardingQuestion[];
}

/** questionKey -> the choiceKeys currently selected for it. */
export type Selection = Record<string, string[]>;

export interface SubmittedAnswer {
    questionKey: string;
    choiceKeys: string[];
}

/**
 * Mirrors the backend cap in `AccountOnboardingService.MAX_TOTAL_SELECTIONS`.
 *
 * Checked client-side purely so an obvious mistake gets an instant, specific message instead of
 * a round trip; the server enforces it regardless. A UI check is never the boundary.
 */
export const MAX_TOTAL_SELECTIONS = 200;

/** True when this question accepts more than one answer. BOOLEAN behaves like SINGLE_SELECT. */
export function allowsMultiple(question: OnboardingQuestion): boolean {
    return question.inputType === 'MULTI_SELECT';
}

/**
 * Apply a click to the current selection and return a new one.
 *
 * Single-select replaces; multi-select toggles. Returning a fresh object rather than mutating
 * keeps the React state update honest.
 */
export function toggleChoice(
    selection: Selection,
    question: OnboardingQuestion,
    choiceKey: string
): Selection {
    const current = selection[question.questionKey] ?? [];
    if (!allowsMultiple(question)) {
        // Clicking the selected option again clears it, which is the only way to unset an
        // optional single-select question.
        const next = current.includes(choiceKey) ? [] : [choiceKey];
        return { ...selection, [question.questionKey]: next };
    }
    const next = current.includes(choiceKey)
        ? current.filter((key) => key !== choiceKey)
        : [...current, choiceKey];
    return { ...selection, [question.questionKey]: next };
}

export function isSelected(selection: Selection, questionKey: string, choiceKey: string): boolean {
    return (selection[questionKey] ?? []).includes(choiceKey);
}

/**
 * Validate a selection against the questions as rendered.
 *
 * @returns a message to show the owner, or null when the selection may be submitted.
 */
export function validateSelection(questions: OnboardingQuestion[], selection: Selection): string | null {
    let total = 0;
    for (const question of questions) {
        const chosen = selection[question.questionKey] ?? [];
        total += chosen.length;
        if (question.required && chosen.length === 0) {
            return `Please answer "${question.label}".`;
        }
        if (!allowsMultiple(question) && chosen.length > 1) {
            return `"${question.label}" accepts only one answer.`;
        }
    }
    if (total > MAX_TOTAL_SELECTIONS) {
        return `Too many selections (maximum ${MAX_TOTAL_SELECTIONS}).`;
    }
    return null;
}

/**
 * Build the request body.
 *
 * Questions with no selection are omitted entirely rather than sent as an empty array: the
 * backend refuses unknown keys but treats an absent question as unanswered, and sending
 * `choiceKeys: []` would be an answer that says nothing.
 */
export function buildSubmitPayload(
    questions: OnboardingQuestion[],
    selection: Selection
): { answers: SubmittedAnswer[] } {
    const answers: SubmittedAnswer[] = [];
    for (const question of questions) {
        const chosen = selection[question.questionKey] ?? [];
        if (chosen.length === 0) continue;
        answers.push({ questionKey: question.questionKey, choiceKeys: chosen });
    }
    return { answers };
}

/**
 * Turn a backend error code into something the account owner can act on.
 *
 * `NOT_FOUND` is deliberately vague on both sides: the server returns one identical body for a
 * malformed, unknown, expired, used or cancelled token so the endpoint cannot be used to tell
 * which, and repeating a more specific guess here would undo that.
 */
export function messageForError(code: string | undefined, fallback?: string): string {
    switch (code) {
        case 'NOT_FOUND':
            return 'This link is invalid or has expired. If you still need to answer, ask your security team to send a new one.';
        case 'NO_RULE_MATCHED':
        case 'EMPTY_QUESTIONNAIRE':
            return 'Thanks — your answers have been recorded. They do not yet map to a set of requirements, so a security champion will follow up with you.';
        case 'RATE_LIMITED':
            return 'Too many attempts. Please wait a few minutes and try again.';
        case 'VALIDATION_ERROR':
            return fallback ?? 'Some answers could not be accepted. Please review them and try again.';
        case 'INTERNAL_ERROR':
            return 'Something went wrong on our side. Your answers were not saved — please try again in a few minutes.';
        default:
            return fallback ?? 'Something went wrong. Please try again in a few minutes.';
    }
}

/** True when the outcome is one the owner can retry by resubmitting the same form. */
export function isRetryable(code: string | undefined): boolean {
    return code === 'VALIDATION_ERROR' || code === 'RATE_LIMITED' || code === 'INTERNAL_ERROR';
}

/**
 * Human summary of what the submission produced.
 *
 * Names the use cases because that is the owner's only feedback that their answers were
 * understood — "submitted" alone tells them nothing about what they just triggered.
 */
export function summariseResult(useCases: string[], requirementCount: number, deadline?: string | null): string {
    const scope = useCases.length > 0 ? useCases.join(', ') : 'your account';
    const requirements = `${requirementCount} requirement${requirementCount === 1 ? '' : 's'}`;
    const due = deadline ? ` It is due by ${deadline}.` : '';
    return `A risk assessment covering ${scope} has been started, with ${requirements} to review.${due}`;
}
