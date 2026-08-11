import React, { useEffect, useState } from 'react';
import {
    buildSubmitPayload,
    isSelected,
    messageForError,
    isRetryable,
    summariseResult,
    toggleChoice,
    validateSelection,
    type OnboardingQuestion,
    type Questionnaire,
    type Selection,
} from './accountOnboardingAnswers';

/**
 * The account owner's questionnaire.
 *
 * Reached from an emailed link by someone who is **not** a SecMan user, so:
 * - no auth helper is used and no cookie is read — the token in the path is the whole capability;
 * - nothing about the account is shown beyond the masked id the API returns. The API deliberately
 *   withholds the owner email, the assessor and the release, and this component must not invent a
 *   way to display what it was not given;
 * - every token failure renders one identical message, matching the single error body the backend
 *   returns for unknown / expired / used / malformed alike.
 *
 * `previewQuestions` lets the admin rule editor mount this same component to show operators
 * exactly what the owner will see. In that mode nothing is fetched and nothing can be submitted.
 */
interface Props {
    token?: string;
    /** Admin preview: render these questions instead of fetching, and disable submission. */
    previewQuestions?: OnboardingQuestion[];
}

type Phase = 'loading' | 'ready' | 'submitting' | 'done' | 'unusable' | 'recorded';

const API_BASE = '/api/public/account-onboarding';

const AccountOnboardingQuestionnaire: React.FC<Props> = ({ token, previewQuestions }) => {
    const preview = previewQuestions !== undefined;

    const [phase, setPhase] = useState<Phase>(preview ? 'ready' : 'loading');
    const [questionnaire, setQuestionnaire] = useState<Questionnaire | null>(
        preview ? { maskedAccountId: '****0000', expiresAt: '', questions: previewQuestions! } : null
    );
    const [selection, setSelection] = useState<Selection>({});
    const [message, setMessage] = useState<string | null>(null);
    const [result, setResult] = useState<{ useCases: string[]; requirementCount: number; deadline?: string } | null>(
        null
    );

    useEffect(() => {
        if (preview) {
            setQuestionnaire({ maskedAccountId: '****0000', expiresAt: '', questions: previewQuestions! });
            return;
        }
        if (!token) {
            setPhase('unusable');
            setMessage(messageForError('NOT_FOUND'));
            return;
        }
        let cancelled = false;
        (async () => {
            try {
                const response = await fetch(`${API_BASE}/${encodeURIComponent(token)}`, {
                    headers: { Accept: 'application/json' },
                });
                const body = await response.json().catch(() => null);
                if (cancelled) return;
                if (!response.ok) {
                    setPhase('unusable');
                    setMessage(messageForError(body?.error, body?.message));
                    return;
                }
                setQuestionnaire(body as Questionnaire);
                setPhase('ready');
            } catch {
                if (cancelled) return;
                setPhase('unusable');
                setMessage(messageForError('INTERNAL_ERROR'));
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [token, preview, previewQuestions]);

    const onToggle = (question: OnboardingQuestion, choiceKey: string) => {
        if (preview || phase === 'submitting') return;
        setSelection((current) => toggleChoice(current, question, choiceKey));
        setMessage(null);
    };

    const onSubmit = async (event: React.FormEvent) => {
        event.preventDefault();
        if (preview || !token || !questionnaire) return;

        const problem = validateSelection(questionnaire.questions, selection);
        if (problem) {
            setMessage(problem);
            return;
        }

        setPhase('submitting');
        setMessage(null);
        try {
            const response = await fetch(`${API_BASE}/${encodeURIComponent(token)}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: JSON.stringify(buildSubmitPayload(questionnaire.questions, selection)),
            });
            const body = await response.json().catch(() => null);

            if (response.ok) {
                setResult({
                    useCases: body?.useCases ?? [],
                    requirementCount: body?.requirementCount ?? 0,
                    deadline: body?.deadline ?? undefined,
                });
                setPhase('done');
                return;
            }
            const code = body?.error as string | undefined;
            setMessage(messageForError(code, body?.message));
            // A recorded-but-unresolved submission is an end state, not a retry: resubmitting the
            // same answers would resolve to the same nothing.
            setPhase(isRetryable(code) ? 'ready' : code === 'NOT_FOUND' ? 'unusable' : 'recorded');
        } catch {
            setMessage(messageForError('INTERNAL_ERROR'));
            setPhase('ready');
        }
    };

    if (phase === 'loading') {
        return (
            <div className="container py-5" style={{ maxWidth: '720px' }}>
                <div className="text-center text-muted">
                    <div className="spinner-border" role="status" aria-hidden="true"></div>
                    <p className="mt-3 mb-0">Loading…</p>
                </div>
            </div>
        );
    }

    if (phase === 'unusable') {
        return (
            <div className="container py-5" style={{ maxWidth: '720px' }}>
                <div className="alert alert-warning" role="alert">
                    <h2 className="h5">This link cannot be used</h2>
                    <p className="mb-0">{message ?? messageForError('NOT_FOUND')}</p>
                </div>
            </div>
        );
    }

    if (phase === 'done') {
        return (
            <div className="container py-5" style={{ maxWidth: '720px' }}>
                <div className="alert alert-success" role="alert">
                    <h2 className="h5">Thank you — that is everything we needed</h2>
                    <p className="mb-0">
                        {summariseResult(result?.useCases ?? [], result?.requirementCount ?? 0, result?.deadline)}
                    </p>
                </div>
                <p className="text-muted small">
                    Your security champion will be in touch. You can close this page.
                </p>
            </div>
        );
    }

    if (phase === 'recorded') {
        return (
            <div className="container py-5" style={{ maxWidth: '720px' }}>
                <div className="alert alert-info" role="alert">
                    <h2 className="h5">Your answers have been recorded</h2>
                    <p className="mb-0">{message}</p>
                </div>
                <p className="text-muted small">You can close this page.</p>
            </div>
        );
    }

    const questions = questionnaire?.questions ?? [];

    return (
        <div className="container py-4" style={{ maxWidth: '720px' }}>
            {!preview && (
                <>
                    <h1 className="h3">Tell us about your AWS account</h1>
                    <p className="text-muted">
                        Account <code>{questionnaire?.maskedAccountId}</code>. Your answers decide which security
                        requirements apply, so we only ask you to review the ones that do.
                        {questionnaire?.expiresAt && (
                            <>
                                {' '}
                                This link is valid until <strong>{questionnaire.expiresAt}</strong>.
                            </>
                        )}
                    </p>
                </>
            )}

            {questions.length === 0 && (
                <div className="alert alert-secondary" role="alert">
                    There are no questions to answer yet.
                </div>
            )}

            <form onSubmit={onSubmit} noValidate>
                {questions.map((question) => (
                    <fieldset key={question.questionKey} className="mb-4">
                        <legend className="h6 mb-1">
                            {question.label}
                            {question.required && (
                                <span className="text-danger ms-1" aria-hidden="true">
                                    *
                                </span>
                            )}
                        </legend>
                        {question.helpText && <p className="text-muted small mb-2">{question.helpText}</p>}
                        {question.inputType === 'MULTI_SELECT' && (
                            <p className="text-muted small mb-2">Select all that apply.</p>
                        )}
                        <div className="list-group">
                            {question.choices.map((choice) => {
                                const checked = isSelected(selection, question.questionKey, choice.choiceKey);
                                const inputId = `${question.questionKey}--${choice.choiceKey}`;
                                return (
                                    <label
                                        key={choice.choiceKey}
                                        className={`list-group-item list-group-item-action d-flex align-items-center${
                                            checked ? ' active' : ''
                                        }`}
                                        htmlFor={inputId}
                                        style={{ cursor: preview ? 'default' : 'pointer' }}
                                    >
                                        <input
                                            id={inputId}
                                            className="form-check-input me-2 mt-0"
                                            type={question.inputType === 'MULTI_SELECT' ? 'checkbox' : 'radio'}
                                            name={question.questionKey}
                                            checked={checked}
                                            disabled={preview || phase === 'submitting'}
                                            onChange={() => onToggle(question, choice.choiceKey)}
                                        />
                                        <span>{choice.label}</span>
                                    </label>
                                );
                            })}
                        </div>
                    </fieldset>
                ))}

                {message && (
                    <div className="alert alert-danger" role="alert">
                        {message}
                    </div>
                )}

                {!preview && (
                    <button type="submit" className="btn btn-primary" disabled={phase === 'submitting'}>
                        {phase === 'submitting' ? 'Submitting…' : 'Submit answers'}
                    </button>
                )}
                {preview && (
                    <p className="text-muted small mb-0">
                        Preview — this is what the account owner sees. Answers cannot be submitted here.
                    </p>
                )}
            </form>
        </div>
    );
};

export default AccountOnboardingQuestionnaire;
