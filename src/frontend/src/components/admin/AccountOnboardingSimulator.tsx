import React, { useState } from 'react';
import { simulateOnboarding, type SimulateResponse } from '../../services/accountOnboardingService';

type Mode = 'WELCOME_ONLY' | 'DIRECT' | 'GUIDED';

/**
 * Run the whole onboarding path against an account id and address you type in.
 *
 * Calls the same endpoint the CLI and MCP surfaces call, which in turn calls what a real import
 * calls — so this is a genuine rehearsal, not a mock. That cuts both ways: a live run sends real
 * mail to whatever address is entered, so the button says so, the confirm dialog names the
 * recipient, and dry-run is the default.
 */
const AccountOnboardingSimulator: React.FC = () => {
    const [awsAccountId, setAwsAccountId] = useState('');
    const [ownerEmail, setOwnerEmail] = useState('');
    const [mode, setMode] = useState<Mode>('GUIDED');
    const [useCase, setUseCase] = useState('');
    // Dry run is the default. A panel that mails a stranger on first click would be a trap.
    const [dryRun, setDryRun] = useState(true);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [result, setResult] = useState<SimulateResponse | null>(null);

    const run = async () => {
        if (!dryRun) {
            const confirmed = window.confirm(
                `This will send real email to ${ownerEmail.trim()} and create real records for account ` +
                    `${awsAccountId.trim()}.\n\nContinue?`
            );
            if (!confirmed) return;
        }
        setBusy(true);
        setError(null);
        setResult(null);
        try {
            setResult(
                await simulateOnboarding({
                    awsAccountId: awsAccountId.trim(),
                    ownerEmail: ownerEmail.trim(),
                    mode,
                    riskAssessmentUseCase: mode === 'DIRECT' ? useCase.trim() || null : null,
                    dryRun,
                })
            );
        } catch (e) {
            setError(e instanceof Error ? e.message : 'The simulation failed.');
        } finally {
            setBusy(false);
        }
    };

    const valid = /^\d{12}$/.test(awsAccountId.trim()) && ownerEmail.trim().includes('@');

    return (
        <div>
            <h2 className="h5">Try it — simulate onboarding an account</h2>
            <p className="text-muted small">
                Runs exactly what a mapping import runs, against an account id and address you choose. Use it to
                see the welcome mail, or to walk the guided questionnaire yourself before anyone else does.
            </p>

            {error && (
                <div className="alert alert-danger" role="alert">
                    {error}
                </div>
            )}

            <div className="row g-2 align-items-end">
                <div className="col-md-3">
                    <label className="form-label small mb-0" htmlFor="sim-account">
                        AWS account id
                    </label>
                    <input
                        id="sim-account"
                        className="form-control form-control-sm"
                        placeholder="999999999999"
                        value={awsAccountId}
                        onChange={(e) => setAwsAccountId(e.target.value)}
                    />
                </div>
                <div className="col-md-4">
                    <label className="form-label small mb-0" htmlFor="sim-email">
                        Owner email
                    </label>
                    <input
                        id="sim-email"
                        className="form-control form-control-sm"
                        placeholder="you@example.com"
                        value={ownerEmail}
                        onChange={(e) => setOwnerEmail(e.target.value)}
                    />
                </div>
                <div className="col-md-2">
                    <label className="form-label small mb-0" htmlFor="sim-mode">
                        Mode
                    </label>
                    <select
                        id="sim-mode"
                        className="form-select form-select-sm"
                        value={mode}
                        onChange={(e) => setMode(e.target.value as Mode)}
                    >
                        <option value="WELCOME_ONLY">Welcome only</option>
                        <option value="DIRECT">Direct assessment</option>
                        <option value="GUIDED">Guided</option>
                    </select>
                </div>
                {mode === 'DIRECT' && (
                    <div className="col-md-3">
                        <label className="form-label small mb-0" htmlFor="sim-usecase">
                            Use case
                        </label>
                        <input
                            id="sim-usecase"
                            className="form-control form-control-sm"
                            placeholder="Cloud Onboarding"
                            value={useCase}
                            onChange={(e) => setUseCase(e.target.value)}
                        />
                    </div>
                )}
                <div className="col-md-3">
                    <div className="form-check">
                        <input
                            id="sim-dry-run"
                            className="form-check-input"
                            type="checkbox"
                            checked={dryRun}
                            onChange={(e) => setDryRun(e.target.checked)}
                        />
                        <label className="form-check-label small" htmlFor="sim-dry-run">
                            Dry run (send nothing)
                        </label>
                    </div>
                </div>
                <div className="col-12">
                    <button
                        type="button"
                        className={`btn btn-sm ${dryRun ? 'btn-primary' : 'btn-danger'}`}
                        disabled={busy || !valid}
                        onClick={run}
                    >
                        {busy ? 'Running…' : dryRun ? 'Preview' : 'Send for real'}
                    </button>
                </div>
            </div>

            {result && (
                <div className="mt-3">
                    <h3 className="h6">
                        {result.dryRun ? 'Dry run — nothing was sent or saved' : 'Done'}
                    </h3>
                    <ul className="list-group">
                        {result.onboarding.map((entry, i) => (
                            <li key={i} className="list-group-item small">
                                {entry.error ? (
                                    <span className="text-danger">{entry.error}</span>
                                ) : entry.skipped ? (
                                    <span className="text-warning">Skipped — {entry.skipReason}</span>
                                ) : entry.dryRun ? (
                                    <span>
                                        Would run {entry.mode} onboarding for {entry.ownerEmail}
                                        {entry.questionnaireExpiresAt && `, link valid until ${entry.questionnaireExpiresAt}`}
                                    </span>
                                ) : entry.questionnaireInviteId ? (
                                    <span>
                                        Questionnaire invite #{entry.questionnaireInviteId} sent
                                        {entry.questionnaireExpiresAt && `, expires ${entry.questionnaireExpiresAt}`}.{' '}
                                        {/* The link is only ever in the mail. Rendering it here would put a
                                            live credential on an admin screen and into any screenshot of it. */}
                                        The link is in the email, not shown here.
                                    </span>
                                ) : entry.riskAssessmentId ? (
                                    <span>Assessment #{entry.riskAssessmentId} started.</span>
                                ) : entry.welcomeEmailSent ? (
                                    <span>Welcome mail sent.</span>
                                ) : (
                                    <span className="text-warning">Nothing sent — check the email configuration.</span>
                                )}
                            </li>
                        ))}
                    </ul>
                    {result.ruleMatrix && (
                        <p className="text-muted small mt-2 mb-0">
                            {result.ruleMatrix.activeRuleCount} active rule(s) covering{' '}
                            {result.ruleMatrix.reachableUseCases.length} use case(s) →{' '}
                            {result.ruleMatrix.reachableRequirementCount} requirement(s)
                            {result.ruleMatrix.hasDefaultRule ? ', with a fallback.' : ', no fallback rule.'}
                        </p>
                    )}
                </div>
            )}
        </div>
    );
};

export default AccountOnboardingSimulator;
