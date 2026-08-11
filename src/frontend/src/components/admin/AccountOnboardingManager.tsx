import React, { useCallback, useEffect, useState } from 'react';
import { canAccessAccountOnboarding } from '../../utils/permissions';
import {
    getCoverage,
    listQuestions,
    listRules,
    listUseCases,
    type CoverageResponse,
    type OnboardingQuestion,
    type OnboardingRule,
    type UseCaseOption,
} from '../../services/accountOnboardingService';
import { ruleSetWarnings } from './accountOnboardingRules';
import AccountOnboardingQuestionList from './AccountOnboardingQuestionList';
import AccountOnboardingRuleEditor from './AccountOnboardingRuleEditor';
import AccountOnboardingCoverageMatrix from './AccountOnboardingCoverageMatrix';
import AccountOnboardingSimulator from './AccountOnboardingSimulator';

/**
 * Configure what a new AWS account's owner is asked, and what their answers mean.
 *
 * Two columns because there are two questions an operator is answering, and conflating them is
 * what makes rule engines opaque:
 *   left  — what the owner is asked, with a live preview of the actual form;
 *   right — which combinations of answers lead to which use cases, written as sentences.
 *
 * Below both sits the coverage matrix, which is the screen that earns its keep: it shows the
 * combinations that resolve to nothing *before* an account owner walks into one.
 *
 * The role gate follows `ClassificationRuleManager`: `window.currentUser` is populated
 * asynchronously by Layout.astro, so we read it, subscribe to `userLoaded`, and fall back after
 * a timeout rather than rendering "Access Denied" to an admin whose roles simply had not
 * arrived. This is UX only — `@Secured("ADMIN","SECCHAMPION")` on the controller is the boundary.
 */
const AccountOnboardingManager: React.FC = () => {
    const [isLoadingAuth, setIsLoadingAuth] = useState(true);
    const [authorized, setAuthorized] = useState(false);

    const [questions, setQuestions] = useState<OnboardingQuestion[]>([]);
    const [rules, setRules] = useState<OnboardingRule[]>([]);
    const [useCases, setUseCases] = useState<UseCaseOption[]>([]);
    const [coverage, setCoverage] = useState<CoverageResponse | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        let settled = false;
        const evaluate = () => {
            const roles = (window as any).currentUser?.roles as string[] | undefined;
            if (!roles) return false;
            settled = true;
            setAuthorized(canAccessAccountOnboarding(roles));
            setIsLoadingAuth(false);
            return true;
        };
        if (evaluate()) return;

        const onUserLoaded = () => evaluate();
        window.addEventListener('userLoaded', onUserLoaded, { once: true });
        const timer = setTimeout(() => {
            if (settled) return;
            setAuthorized(false);
            setIsLoadingAuth(false);
        }, 2000);

        return () => {
            window.removeEventListener('userLoaded', onUserLoaded);
            clearTimeout(timer);
        };
    }, []);

    const reload = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            // Coverage is fetched alongside rather than after: its dead-end count is part of the
            // warning banner, and showing the rules without it would present an incomplete
            // picture as if it were complete.
            const [q, r, uc, cov] = await Promise.all([
                listQuestions(),
                listRules(),
                listUseCases(),
                getCoverage().catch(() => null),
            ]);
            setQuestions(q);
            setRules(r);
            setUseCases(uc);
            setCoverage(cov);
        } catch (error) {
            setLoadError(error instanceof Error ? error.message : 'Could not load the onboarding configuration.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        if (authorized) void reload();
    }, [authorized, reload]);

    if (isLoadingAuth) {
        return <div className="text-muted">Checking permissions…</div>;
    }

    if (!authorized) {
        return (
            <div className="alert alert-danger" role="alert">
                <strong>Access Denied:</strong> You do not have permission to configure account onboarding.
            </div>
        );
    }

    const warnings = ruleSetWarnings(
        rules,
        questions,
        coverage ? { rows: coverage.rows, hasDefaultRule: coverage.hasDefaultRule, releaseVersion: coverage.releaseVersion } : undefined
    );

    return (
        <div>
            <p className="text-muted">
                When a mapping import discovers an AWS account SecMan has never seen, the owner can be sent a
                one-time link asking how they use it. Their answers resolve through the rules below into the
                use cases their risk assessment is scoped to — every matching rule contributes, and the
                results are combined.
            </p>

            {loadError && (
                <div className="alert alert-danger" role="alert">
                    {loadError}
                </div>
            )}

            {warnings.length > 0 && (
                <div className="alert alert-warning" role="alert">
                    <strong>Check the configuration:</strong>
                    <ul className="mb-0 mt-1">
                        {warnings.map((warning) => (
                            <li key={warning}>{warning}</li>
                        ))}
                    </ul>
                </div>
            )}

            {loading && <div className="text-muted mb-3">Loading…</div>}

            <div className="row g-4">
                <div className="col-lg-6">
                    <h2 className="h5">What the account owner is asked</h2>
                    <AccountOnboardingQuestionList questions={questions} onChanged={reload} />
                </div>
                <div className="col-lg-6">
                    <h2 className="h5">Which answers lead to which assessment</h2>
                    <AccountOnboardingRuleEditor
                        questions={questions}
                        rules={rules}
                        useCases={useCases}
                        onChanged={reload}
                    />
                </div>
            </div>

            <hr className="my-4" />

            <AccountOnboardingCoverageMatrix coverage={coverage} />

            <hr className="my-4" />

            <AccountOnboardingSimulator />
        </div>
    );
};

export default AccountOnboardingManager;
