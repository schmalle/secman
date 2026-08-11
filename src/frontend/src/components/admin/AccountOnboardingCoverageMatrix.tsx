import React from 'react';
import type { CoverageResponse } from '../../services/accountOnboardingService';
import { countDeadEnds } from './accountOnboardingRules';

interface Props {
    coverage: CoverageResponse | null;
}

/**
 * Every combination of answers an owner could give, and what each would produce.
 *
 * This is the screen that earns the feature its keep. A rule set can look complete and still
 * have holes — a combination no rule covers, or one resolving to use cases the ACTIVE release
 * has no requirements for. Both are invisible until a real account owner clicks a link and
 * reaches a dead end, at which point the fix arrives days late. Listing the combinations makes
 * the hole visible while it is still cheap.
 *
 * Only single-answer questions are enumerated — a multi-select question has 2^n answers and
 * would swamp the cap on its own. When the space exceeds the server's limit the list is a
 * prefix, and that is stated rather than left to look complete.
 */
const AccountOnboardingCoverageMatrix: React.FC<Props> = ({ coverage }) => {
    if (!coverage) {
        return (
            <div>
                <h2 className="h5">Coverage</h2>
                <p className="text-muted">Coverage could not be loaded.</p>
            </div>
        );
    }

    const deadEnds = countDeadEnds(coverage.rows);

    return (
        <div>
            <h2 className="h5">Coverage — what every combination of answers would produce</h2>
            <p className="text-muted small">
                {coverage.releaseVersion
                    ? `Requirement counts are against ACTIVE release ${coverage.releaseVersion}.`
                    : 'No ACTIVE requirements release — guided onboarding is rejected until one is activated.'}
            </p>

            {deadEnds > 0 && (
                <div className="alert alert-warning" role="alert">
                    <strong>{deadEnds}</strong> combination{deadEnds === 1 ? '' : 's'} below would leave the owner
                    without an assessment. Add a rule covering {deadEnds === 1 ? 'it' : 'them'}, or mark one rule as
                    the fallback.
                </div>
            )}

            {coverage.truncated && (
                <div className="alert alert-info" role="alert">
                    There are more combinations than can be listed here. The table below is a partial view — use
                    the rule list and the test panel to check the rest.
                </div>
            )}

            {coverage.rows.length === 0 ? (
                <p className="text-muted">
                    Nothing to show yet. Add questions with answers, then rules that map them to use cases.
                </p>
            ) : (
                <div className="table-responsive" style={{ maxHeight: '420px' }}>
                    <table className="table table-sm table-hover align-middle">
                        <thead className="table-light">
                            <tr>
                                <th scope="col">If the owner answers…</th>
                                <th scope="col">Matching rules</th>
                                <th scope="col">Assessment covers</th>
                                <th scope="col" className="text-end">
                                    Requirements
                                </th>
                            </tr>
                        </thead>
                        <tbody>
                            {coverage.rows.map((row) => (
                                <tr
                                    key={row.combination.join('|')}
                                    className={row.deadEnd ? 'table-danger' : undefined}
                                >
                                    <td>
                                        <code className="small">{row.combination.join(', ')}</code>
                                    </td>
                                    <td className="small">
                                        {row.matchedRules.length > 0 ? row.matchedRules.join(', ') : <em>none</em>}
                                        {row.usedDefault && <span className="badge bg-info ms-1">fallback</span>}
                                    </td>
                                    <td className="small">
                                        {row.useCases.length > 0 ? row.useCases.join(', ') : <em>nothing</em>}
                                    </td>
                                    <td className="text-end">{row.requirementCount}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
};

export default AccountOnboardingCoverageMatrix;
