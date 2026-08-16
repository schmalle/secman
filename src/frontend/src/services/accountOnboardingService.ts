import {
    authenticatedGet,
    authenticatedPost,
    authenticatedPut,
    authenticatedDelete,
} from '../utils/auth';

/**
 * Client for `/api/account-onboarding` (ADMIN or SECCHAMPION).
 *
 * Uses the `authenticated*` helpers from `utils/auth` rather than raw fetch or axios: they send
 * the HttpOnly `secman_auth` cookie via `credentials: 'include'` and handle the 401 → redirect
 * path in one place. See CLAUDE.md §Auth.
 *
 * The public questionnaire endpoint is deliberately NOT in this module — it is unauthenticated
 * and lives in `components/AccountOnboardingQuestionnaire.tsx`, so no auth helper can ever leak
 * a credential onto it.
 */

const API_BASE = '/api/account-onboarding';

export type OnboardingInputType = 'SINGLE_SELECT' | 'MULTI_SELECT' | 'BOOLEAN';

export interface OnboardingChoice {
    id: number | null;
    choiceKey: string;
    label: string;
    displayOrder: number;
    active: boolean;
}

export interface OnboardingQuestion {
    id: number | null;
    questionKey: string;
    label: string;
    helpText: string | null;
    inputType: OnboardingInputType;
    displayOrder: number;
    required: boolean;
    active: boolean;
    choices: OnboardingChoice[];
    /** How many rules reference this question's choices — non-zero blocks deletion. */
    referencedByRules: number;
}

export interface OnboardingRule {
    id: number | null;
    name: string;
    description: string | null;
    active: boolean;
    priorityOrder: number;
    isDefault: boolean;
    choiceIds: number[];
    /** `questionKey=choiceKey`, sorted — the human-readable form of choiceIds. */
    combination: string[];
    useCaseIds: number[];
    useCases: string[];
    createdBy: string | null;
}

export interface CoverageRow {
    combination: string[];
    matchedRules: string[];
    useCases: string[];
    requirementCount: number;
    usedDefault: boolean;
    /** True when an owner submitting this combination would hit a dead end. */
    deadEnd: boolean;
}

export interface CoverageResponse {
    rows: CoverageRow[];
    /** True when the combination space exceeded the cap and `rows` is a prefix, not the whole. */
    truncated: boolean;
    hasDefaultRule: boolean;
    releaseVersion: string | null;
}

export interface PreviewResponse {
    matchedRules: string[];
    useCases: string[];
    requirementCount: number;
    usedDefault: boolean;
    releaseVersion: string | null;
    failure: string | null;
}

export interface OnboardingInfo {
    awsAccountId: string;
    ownerEmail: string;
    mode: string;
    welcomeEmailSent: boolean;
    questionnaireInviteId: number | null;
    questionnaireExpiresAt: string | null;
    riskAssessmentId: number | null;
    dryRun: boolean;
    skipped: boolean;
    skipReason: string | null;
    error: string | null;
}

export interface SimulateResponse {
    awsAccountId: string;
    ownerEmail: string;
    mode: string;
    dryRun: boolean;
    onboarding: OnboardingInfo[];
    riskAssessments: Array<{
        riskAssessmentId: number | null;
        assessor: string | null;
        endDate: string | null;
        useCase: string | null;
        releaseVersion: string | null;
        requirementCount: number | null;
        skipped: boolean;
        skipReason: string | null;
        error: string | null;
    }>;
    ruleMatrix: {
        questionCount: number;
        choiceCount: number;
        activeRuleCount: number;
        hasDefaultRule: boolean;
        reachableUseCases: string[];
        reachableRequirementCount: number;
        releaseVersion: string | null;
    } | null;
}

export interface UseCaseOption {
    id: number;
    name: string;
}

/**
 * Turn a non-OK response into an Error carrying the server's own message.
 *
 * The backend returns a generic message plus a code; surfacing that message is what makes the
 * refusals ("2 rule(s) reference this choice", "already the default fallback rule") actionable
 * instead of a bare 409.
 */
async function raise(response: Response): Promise<never> {
    let message = `Request failed (${response.status})`;
    try {
        const body = await response.json();
        if (body?.message) message = body.message;
        else if (body?.error) message = body.error;
    } catch {
        // Body was not JSON — keep the status-based message.
    }
    throw new Error(message);
}

async function json<T>(response: Response): Promise<T> {
    if (!response.ok) return raise(response);
    return (await response.json()) as T;
}

// --- Questions and choices ---------------------------------------------------

export async function listQuestions(): Promise<OnboardingQuestion[]> {
    return json(await authenticatedGet(`${API_BASE}/questions`));
}

export async function createQuestion(question: Partial<OnboardingQuestion>): Promise<OnboardingQuestion> {
    return json(await authenticatedPost(`${API_BASE}/questions`, question));
}

export async function updateQuestion(
    id: number,
    question: Partial<OnboardingQuestion>
): Promise<OnboardingQuestion> {
    return json(await authenticatedPut(`${API_BASE}/questions/${id}`, question));
}

export async function deleteQuestion(id: number): Promise<void> {
    const response = await authenticatedDelete(`${API_BASE}/questions/${id}`);
    if (!response.ok) await raise(response);
}

export async function reorderQuestions(ids: number[]): Promise<OnboardingQuestion[]> {
    return json(await authenticatedPut(`${API_BASE}/questions/order`, { ids }));
}

export async function createChoice(
    questionId: number,
    choice: Partial<OnboardingChoice>
): Promise<OnboardingChoice> {
    return json(await authenticatedPost(`${API_BASE}/questions/${questionId}/choices`, choice));
}

export async function updateChoice(
    questionId: number,
    choiceId: number,
    choice: Partial<OnboardingChoice>
): Promise<OnboardingChoice> {
    return json(await authenticatedPut(`${API_BASE}/questions/${questionId}/choices/${choiceId}`, choice));
}

export async function deleteChoice(questionId: number, choiceId: number): Promise<void> {
    const response = await authenticatedDelete(`${API_BASE}/questions/${questionId}/choices/${choiceId}`);
    if (!response.ok) await raise(response);
}

// --- Rules -------------------------------------------------------------------

export async function listRules(): Promise<OnboardingRule[]> {
    return json(await authenticatedGet(`${API_BASE}/rules`));
}

export async function createRule(rule: Partial<OnboardingRule>): Promise<OnboardingRule> {
    return json(await authenticatedPost(`${API_BASE}/rules`, rule));
}

export async function updateRule(id: number, rule: Partial<OnboardingRule>): Promise<OnboardingRule> {
    return json(await authenticatedPut(`${API_BASE}/rules/${id}`, rule));
}

export async function deleteRule(id: number): Promise<void> {
    const response = await authenticatedDelete(`${API_BASE}/rules/${id}`);
    if (!response.ok) await raise(response);
}

export async function getCoverage(): Promise<CoverageResponse> {
    // Micronaut Serde omits an empty `rows` from the payload entirely, so a configuration with no
    // questions or no rules arrives without the key. Normalise here rather than in each consumer:
    // both the warning banner and the matrix index into `rows` unguarded.
    const coverage = await json<CoverageResponse>(await authenticatedGet(`${API_BASE}/rules/coverage`));
    return { ...coverage, rows: coverage.rows ?? [] };
}

/** Resolve a set of answers without writing anything — the admin twin of a dry run. */
export async function previewRules(
    answers: Array<{ questionKey: string; choiceKeys: string[] }>
): Promise<PreviewResponse> {
    return json(await authenticatedPost(`${API_BASE}/rules/preview`, { answers }));
}

// --- Simulate ----------------------------------------------------------------

export async function simulateOnboarding(request: {
    awsAccountId: string;
    ownerEmail: string;
    mode: string;
    riskAssessmentUseCase?: string | null;
    riskAssessmentDeadlineDays?: number | null;
    questionnaireExpiryDays?: number | null;
    sendWelcomeEmail?: boolean | null;
    dryRun: boolean;
}): Promise<SimulateResponse> {
    return json(await authenticatedPost(`${API_BASE}/simulate`, request));
}

/** Use cases available to attach to a rule. Reuses the existing use-case API, not a new one. */
export async function listUseCases(): Promise<UseCaseOption[]> {
    return json(await authenticatedGet('/api/usecases'));
}
