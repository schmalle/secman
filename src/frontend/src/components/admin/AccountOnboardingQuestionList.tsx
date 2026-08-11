import React, { useState } from 'react';
import {
    createChoice,
    createQuestion,
    deleteChoice,
    deleteQuestion,
    updateQuestion,
    type OnboardingInputType,
    type OnboardingQuestion,
} from '../../services/accountOnboardingService';
import AccountOnboardingQuestionnaire from '../AccountOnboardingQuestionnaire';

interface Props {
    questions: OnboardingQuestion[];
    onChanged: () => void | Promise<void>;
}

const EMPTY_QUESTION = {
    questionKey: '',
    label: '',
    helpText: '',
    inputType: 'SINGLE_SELECT' as OnboardingInputType,
    required: true,
};

/**
 * The questions and their answers, plus a live preview of the real form.
 *
 * The preview mounts the *same* component the account owner sees
 * (`AccountOnboardingQuestionnaire` in preview mode) rather than a lookalike. A second renderer
 * would drift, and the whole value of the preview is that it cannot.
 */
const AccountOnboardingQuestionList: React.FC<Props> = ({ questions, onChanged }) => {
    const [draft, setDraft] = useState({ ...EMPTY_QUESTION });
    const [choiceDrafts, setChoiceDrafts] = useState<Record<number, { choiceKey: string; label: string }>>({});
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [showPreview, setShowPreview] = useState(true);

    const run = async (action: () => Promise<unknown>) => {
        setBusy(true);
        setError(null);
        try {
            await action();
            await onChanged();
        } catch (e) {
            setError(e instanceof Error ? e.message : 'The change could not be saved.');
        } finally {
            setBusy(false);
        }
    };

    const addQuestion = async () => {
        await run(async () => {
            await createQuestion({
                ...draft,
                helpText: draft.helpText.trim() || null,
                displayOrder: questions.length,
                active: true,
            });
            setDraft({ ...EMPTY_QUESTION });
        });
    };

    const toggleActive = (question: OnboardingQuestion) =>
        run(() => updateQuestion(question.id!, { ...question, active: !question.active }));

    const addChoice = async (question: OnboardingQuestion) => {
        const choiceDraft = choiceDrafts[question.id!] ?? { choiceKey: '', label: '' };
        await run(async () => {
            await createChoice(question.id!, {
                ...choiceDraft,
                displayOrder: question.choices.length,
                active: true,
            });
            setChoiceDrafts((current) => ({ ...current, [question.id!]: { choiceKey: '', label: '' } }));
        });
    };

    return (
        <div>
            {error && (
                <div className="alert alert-danger" role="alert">
                    {error}
                </div>
            )}

            {questions.length === 0 && (
                <p className="text-muted">
                    No questions yet. Add at least one, with two or more answers, before using guided onboarding.
                </p>
            )}

            <ul className="list-group mb-3">
                {questions.map((question) => (
                    <li key={question.id} className="list-group-item">
                        <div className="d-flex justify-content-between align-items-start">
                            <div>
                                <strong>{question.label}</strong>{' '}
                                <code className="small text-muted">{question.questionKey}</code>
                                <div>
                                    <span className="badge bg-secondary me-1">{question.inputType}</span>
                                    {question.required && <span className="badge bg-info me-1">required</span>}
                                    {!question.active && <span className="badge bg-warning text-dark me-1">inactive</span>}
                                    {question.referencedByRules > 0 && (
                                        <span className="badge bg-light text-dark">
                                            used by {question.referencedByRules} rule(s)
                                        </span>
                                    )}
                                </div>
                                {question.helpText && <div className="small text-muted">{question.helpText}</div>}
                            </div>
                            <div className="btn-group btn-group-sm">
                                <button
                                    type="button"
                                    className="btn btn-outline-secondary"
                                    disabled={busy}
                                    onClick={() => toggleActive(question)}
                                >
                                    {question.active ? 'Deactivate' : 'Activate'}
                                </button>
                                <button
                                    type="button"
                                    className="btn btn-outline-danger"
                                    disabled={busy || question.referencedByRules > 0}
                                    // Disabled rather than hidden, with the reason in the title: an
                                    // operator who cannot see why a delete is unavailable assumes a bug.
                                    title={
                                        question.referencedByRules > 0
                                            ? 'Rules reference this question — remove them first'
                                            : 'Delete this question'
                                    }
                                    onClick={() => run(() => deleteQuestion(question.id!))}
                                >
                                    Delete
                                </button>
                            </div>
                        </div>

                        <div className="mt-2">
                            {question.choices.map((choice) => (
                                <span key={choice.id} className="badge bg-light text-dark border me-1 mb-1">
                                    {choice.label}
                                    <code className="ms-1 text-muted">{choice.choiceKey}</code>
                                    <button
                                        type="button"
                                        className="btn-close btn-close-sm ms-2"
                                        aria-label={`Remove ${choice.label}`}
                                        disabled={busy}
                                        onClick={() => run(() => deleteChoice(question.id!, choice.id!))}
                                    />
                                </span>
                            ))}
                        </div>

                        <div className="input-group input-group-sm mt-2">
                            <input
                                className="form-control"
                                placeholder="answer key (e.g. production)"
                                value={choiceDrafts[question.id!]?.choiceKey ?? ''}
                                onChange={(e) =>
                                    setChoiceDrafts((current) => ({
                                        ...current,
                                        [question.id!]: {
                                            choiceKey: e.target.value,
                                            label: current[question.id!]?.label ?? '',
                                        },
                                    }))
                                }
                            />
                            <input
                                className="form-control"
                                placeholder="Answer label (e.g. Production)"
                                value={choiceDrafts[question.id!]?.label ?? ''}
                                onChange={(e) =>
                                    setChoiceDrafts((current) => ({
                                        ...current,
                                        [question.id!]: {
                                            choiceKey: current[question.id!]?.choiceKey ?? '',
                                            label: e.target.value,
                                        },
                                    }))
                                }
                            />
                            <button
                                type="button"
                                className="btn btn-outline-primary"
                                disabled={busy}
                                onClick={() => addChoice(question)}
                            >
                                Add answer
                            </button>
                        </div>
                    </li>
                ))}
            </ul>

            <div className="card mb-3">
                <div className="card-body">
                    <h3 className="h6">Add a question</h3>
                    <div className="row g-2">
                        <div className="col-md-5">
                            <input
                                className="form-control form-control-sm"
                                placeholder="key (e.g. environment)"
                                value={draft.questionKey}
                                onChange={(e) => setDraft({ ...draft, questionKey: e.target.value })}
                            />
                        </div>
                        <div className="col-md-7">
                            <input
                                className="form-control form-control-sm"
                                placeholder="Question shown to the owner"
                                value={draft.label}
                                onChange={(e) => setDraft({ ...draft, label: e.target.value })}
                            />
                        </div>
                        <div className="col-12">
                            <input
                                className="form-control form-control-sm"
                                placeholder="Help text (optional)"
                                value={draft.helpText}
                                onChange={(e) => setDraft({ ...draft, helpText: e.target.value })}
                            />
                        </div>
                        <div className="col-md-6">
                            <select
                                className="form-select form-select-sm"
                                value={draft.inputType}
                                onChange={(e) =>
                                    setDraft({ ...draft, inputType: e.target.value as OnboardingInputType })
                                }
                            >
                                <option value="SINGLE_SELECT">One answer</option>
                                <option value="MULTI_SELECT">Several answers</option>
                                <option value="BOOLEAN">Yes / no</option>
                            </select>
                        </div>
                        <div className="col-md-6 d-flex align-items-center">
                            <div className="form-check">
                                <input
                                    id="onboarding-question-required"
                                    className="form-check-input"
                                    type="checkbox"
                                    checked={draft.required}
                                    onChange={(e) => setDraft({ ...draft, required: e.target.checked })}
                                />
                                <label className="form-check-label" htmlFor="onboarding-question-required">
                                    Must be answered
                                </label>
                            </div>
                        </div>
                        <div className="col-12">
                            <button
                                type="button"
                                className="btn btn-sm btn-primary"
                                disabled={busy || !draft.questionKey.trim() || !draft.label.trim()}
                                onClick={addQuestion}
                            >
                                Add question
                            </button>
                        </div>
                    </div>
                </div>
            </div>

            <div className="d-flex justify-content-between align-items-center">
                <h3 className="h6 mb-0">Preview</h3>
                <button
                    type="button"
                    className="btn btn-sm btn-link"
                    onClick={() => setShowPreview((current) => !current)}
                >
                    {showPreview ? 'Hide' : 'Show'}
                </button>
            </div>
            {showPreview && (
                <div className="border rounded bg-light">
                    <AccountOnboardingQuestionnaire
                        previewQuestions={questions
                            .filter((q) => q.active)
                            .map((q) => ({
                                questionKey: q.questionKey,
                                label: q.label,
                                helpText: q.helpText,
                                inputType: q.inputType,
                                required: q.required,
                                choices: q.choices
                                    .filter((c) => c.active)
                                    .map((c) => ({ choiceKey: c.choiceKey, label: c.label })),
                            }))}
                    />
                </div>
            )}
        </div>
    );
};

export default AccountOnboardingQuestionList;
