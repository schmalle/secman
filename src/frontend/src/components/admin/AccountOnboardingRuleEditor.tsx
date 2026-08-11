import React, { useMemo, useState } from 'react';
import {
    createRule,
    deleteRule,
    previewRules,
    updateRule,
    type OnboardingQuestion,
    type OnboardingRule,
    type PreviewResponse,
    type UseCaseOption,
} from '../../services/accountOnboardingService';
import {
    ANY_CHOICE,
    choiceIdsFromDraft,
    describeRule,
    draftFromRule,
    indexChoices,
    sortRulesForDisplay,
    validateRuleDraft,
    type RuleDraft,
} from './accountOnboardingRules';

interface Props {
    questions: OnboardingQuestion[];
    rules: OnboardingRule[];
    useCases: UseCaseOption[];
    onChanged: () => void | Promise<void>;
}

function emptyDraft(questions: OnboardingQuestion[]): RuleDraft {
    const draft: RuleDraft = {};
    for (const question of questions) draft[question.questionKey] = [];
    return draft;
}

/**
 * Rules as sentences, edited through one dropdown per question.
 *
 * The design decision worth stating: a rule is *never* shown as JSON and *never* edited as a
 * free-form list of ids. Each question gets a dropdown whose first option is an explicit
 * "(any)" — meaning "this question is not part of this rule" — so the two things an operator
 * confuses ("I did not restrict this" vs "I forgot this") become one visible choice. Combined
 * with client-side validation that mirrors the server's, an unmatchable rule cannot be built.
 */
const AccountOnboardingRuleEditor: React.FC<Props> = ({ questions, rules, useCases, onChanged }) => {
    const [editingId, setEditingId] = useState<number | 'new' | null>(null);
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [isDefault, setIsDefault] = useState(false);
    const [draft, setDraft] = useState<RuleDraft>({});
    const [selectedUseCaseIds, setSelectedUseCaseIds] = useState<number[]>([]);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [preview, setPreview] = useState<PreviewResponse | null>(null);

    const index = useMemo(() => indexChoices(questions), [questions]);
    const sorted = useMemo(() => sortRulesForDisplay(rules), [rules]);
    const existingDefault = rules.find((r) => r.isDefault);

    const startNew = () => {
        setEditingId('new');
        setName('');
        setDescription('');
        setIsDefault(false);
        setDraft(emptyDraft(questions));
        setSelectedUseCaseIds([]);
        setError(null);
    };

    const startEdit = (rule: OnboardingRule) => {
        setEditingId(rule.id!);
        setName(rule.name);
        setDescription(rule.description ?? '');
        setIsDefault(rule.isDefault);
        setDraft(draftFromRule(rule, questions));
        setSelectedUseCaseIds(rule.useCaseIds);
        setError(null);
    };

    const save = async () => {
        const otherDefault = existingDefault && existingDefault.id !== editingId ? existingDefault.name : null;
        const problem = validateRuleDraft(name, draft, selectedUseCaseIds, isDefault, questions, otherDefault);
        if (problem) {
            setError(problem);
            return;
        }
        setBusy(true);
        setError(null);
        try {
            const payload = {
                name: name.trim(),
                description: description.trim() || null,
                active: true,
                priorityOrder: rules.length,
                isDefault,
                choiceIds: choiceIdsFromDraft(draft, questions),
                useCaseIds: selectedUseCaseIds,
            };
            if (editingId === 'new') await createRule(payload);
            else await updateRule(editingId as number, payload);
            setEditingId(null);
            await onChanged();
        } catch (e) {
            setError(e instanceof Error ? e.message : 'The rule could not be saved.');
        } finally {
            setBusy(false);
        }
    };

    const remove = async (rule: OnboardingRule) => {
        setBusy(true);
        setError(null);
        try {
            await deleteRule(rule.id!);
            await onChanged();
        } catch (e) {
            setError(e instanceof Error ? e.message : 'The rule could not be deleted.');
        } finally {
            setBusy(false);
        }
    };

    /** Resolve the draft's answers through the real matcher, writing nothing. */
    const testDraft = async () => {
        setBusy(true);
        setError(null);
        try {
            const answers = questions
                .map((q) => ({ questionKey: q.questionKey, choiceKeys: draft[q.questionKey] ?? [] }))
                .filter((a) => a.choiceKeys.length > 0);
            setPreview(await previewRules(answers));
        } catch (e) {
            setError(e instanceof Error ? e.message : 'The preview failed.');
        } finally {
            setBusy(false);
        }
    };

    return (
        <div>
            {error && (
                <div className="alert alert-danger" role="alert">
                    {error}
                </div>
            )}

            <ul className="list-group mb-3">
                {sorted.length === 0 && (
                    <li className="list-group-item text-muted">
                        No rules yet. Without one, an owner's answers cannot be turned into an assessment.
                    </li>
                )}
                {sorted.map((rule) => (
                    <li key={rule.id} className="list-group-item">
                        <div className="d-flex justify-content-between align-items-start">
                            <div>
                                <strong>{rule.name}</strong>
                                {rule.isDefault && <span className="badge bg-info ms-2">fallback</span>}
                                {!rule.active && <span className="badge bg-warning text-dark ms-2">inactive</span>}
                                {/* The sentence, not the ids — this is what makes the list readable. */}
                                <div className="small">
                                    {describeRule(rule, index)} →{' '}
                                    <strong>{rule.useCases.join(', ') || '(no use case)'}</strong>
                                </div>
                                {rule.description && <div className="small text-muted">{rule.description}</div>}
                            </div>
                            <div className="btn-group btn-group-sm">
                                <button
                                    type="button"
                                    className="btn btn-outline-secondary"
                                    disabled={busy}
                                    onClick={() => startEdit(rule)}
                                >
                                    Edit
                                </button>
                                <button
                                    type="button"
                                    className="btn btn-outline-danger"
                                    disabled={busy}
                                    onClick={() => remove(rule)}
                                >
                                    Delete
                                </button>
                            </div>
                        </div>
                    </li>
                ))}
            </ul>

            {editingId === null && (
                <button type="button" className="btn btn-sm btn-primary" disabled={busy} onClick={startNew}>
                    Add a rule
                </button>
            )}

            {editingId !== null && (
                <div className="card">
                    <div className="card-body">
                        <h3 className="h6">{editingId === 'new' ? 'New rule' : 'Edit rule'}</h3>

                        <div className="mb-2">
                            <input
                                className="form-control form-control-sm"
                                placeholder="Rule name (e.g. Production workload)"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                            />
                        </div>
                        <div className="mb-3">
                            <input
                                className="form-control form-control-sm"
                                placeholder="Why this rule exists (optional)"
                                value={description}
                                onChange={(e) => setDescription(e.target.value)}
                            />
                        </div>

                        <div className="form-check mb-3">
                            <input
                                id="onboarding-rule-default"
                                className="form-check-input"
                                type="checkbox"
                                checked={isDefault}
                                onChange={(e) => setIsDefault(e.target.checked)}
                            />
                            <label className="form-check-label" htmlFor="onboarding-rule-default">
                                Use this as the fallback when no other rule matches
                            </label>
                        </div>

                        {!isDefault && (
                            <>
                                <p className="small text-muted mb-2">
                                    <strong>When</strong> the owner answers…
                                </p>
                                {questions.map((question) => {
                                    const chosen = draft[question.questionKey] ?? [];
                                    const multi = question.inputType === 'MULTI_SELECT';
                                    return (
                                        <div className="mb-2" key={question.questionKey}>
                                            <label className="form-label small mb-0">{question.label}</label>
                                            <select
                                                className="form-select form-select-sm"
                                                multiple={multi}
                                                value={multi ? chosen : (chosen[0] ?? ANY_CHOICE)}
                                                onChange={(e) => {
                                                    const values = multi
                                                        ? Array.from(e.target.selectedOptions, (o) => o.value).filter(
                                                              (v) => v !== ANY_CHOICE
                                                          )
                                                        : e.target.value === ANY_CHOICE
                                                          ? []
                                                          : [e.target.value];
                                                    setDraft((current) => ({
                                                        ...current,
                                                        [question.questionKey]: values,
                                                    }));
                                                }}
                                            >
                                                {/* Explicit "(any)": the difference between "not restricted"
                                                    and "forgotten" has to be visible, not inferred. */}
                                                <option value={ANY_CHOICE}>(any — not part of this rule)</option>
                                                {question.choices.map((choice) => (
                                                    <option key={choice.id} value={choice.choiceKey}>
                                                        {choice.label}
                                                    </option>
                                                ))}
                                            </select>
                                        </div>
                                    );
                                })}
                            </>
                        )}

                        <p className="small text-muted mt-3 mb-1">
                            <strong>Then</strong> start an assessment covering…
                        </p>
                        <select
                            className="form-select form-select-sm mb-3"
                            multiple
                            value={selectedUseCaseIds.map(String)}
                            onChange={(e) =>
                                setSelectedUseCaseIds(
                                    Array.from(e.target.selectedOptions, (o) => Number(o.value))
                                )
                            }
                        >
                            {useCases.map((useCase) => (
                                <option key={useCase.id} value={useCase.id}>
                                    {useCase.name}
                                </option>
                            ))}
                        </select>

                        {preview && (
                            <div className="alert alert-secondary small" role="status">
                                {preview.failure
                                    ? `These answers resolve to nothing (${preview.failure}).`
                                    : `Matches: ${preview.matchedRules.join(', ') || 'none'} → ${
                                          preview.useCases.join(', ') || 'no use case'
                                      } (${preview.requirementCount} requirement(s)${
                                          preview.releaseVersion ? ` in release ${preview.releaseVersion}` : ''
                                      })`}
                                {preview.usedDefault && ' — via the fallback rule.'}
                            </div>
                        )}

                        <div className="btn-group btn-group-sm">
                            <button type="button" className="btn btn-primary" disabled={busy} onClick={save}>
                                Save
                            </button>
                            <button
                                type="button"
                                className="btn btn-outline-secondary"
                                disabled={busy || isDefault}
                                title="Resolve these answers through the live rule set without saving"
                                onClick={testDraft}
                            >
                                Test these answers
                            </button>
                            <button
                                type="button"
                                className="btn btn-outline-secondary"
                                disabled={busy}
                                onClick={() => {
                                    setEditingId(null);
                                    setPreview(null);
                                }}
                            >
                                Cancel
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default AccountOnboardingRuleEditor;
