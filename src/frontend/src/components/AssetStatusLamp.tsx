/**
 * Asset Status Lamp
 *
 * Traffic-light indicator answering "does this asset need manual intervention?".
 *
 *   GREEN  - no vulnerabilities, or every one is covered by an active exception
 *   YELLOW - non-excepted findings exist, all still inside the threshold window
 *   RED    - at least one non-excepted finding is older than the threshold
 *
 * RED is defined to coincide exactly with the Outdated Assets view, so a red lamp means the
 * asset is on that list and its owner is receiving reminder mail.
 *
 * Accessibility: colour is never the only channel. Every lamp carries a text label for screen
 * readers and a title tooltip explaining the reason, so the control still works for colour-blind
 * users and in monochrome print.
 */

import React from 'react';
import type { AssetInterventionStatus } from '../services/accountVulnsService';

interface AssetStatusLampProps {
    status?: AssetInterventionStatus;
    /** Non-excepted findings past the threshold; drives the explanatory tooltip. */
    overdueCount?: number;
    /** Non-excepted findings inside the window. */
    nonExceptedCount?: number;
    /** Threshold in days, echoed by the API so it is never hardcoded here. */
    thresholdDays?: number;
    /** Render the text label next to the dot (used on group headers). */
    showLabel?: boolean;
}

const LABELS: Record<AssetInterventionStatus, string> = {
    GREEN: 'No action needed',
    YELLOW: 'Within deadline',
    RED: 'Action overdue',
};

const COLORS: Record<AssetInterventionStatus, string> = {
    GREEN: 'var(--scand-success)',
    YELLOW: 'var(--scand-warning)',
    RED: 'var(--scand-danger)',
};

const AssetStatusLamp: React.FC<AssetStatusLampProps> = ({
    status,
    overdueCount,
    nonExceptedCount,
    thresholdDays,
    showLabel = false,
}) => {
    // Older backends omit the field entirely; render nothing rather than a misleading green.
    if (!status) {
        return null;
    }

    const days = thresholdDays ?? 30;
    const label = LABELS[status];

    let tooltip: string;
    if (status === 'RED') {
        const n = overdueCount ?? 0;
        tooltip = `${n} non-excepted vulnerabilit${n === 1 ? 'y' : 'ies'} older than ${days} days — needs manual intervention.`;
    } else if (status === 'YELLOW') {
        const n = nonExceptedCount ?? 0;
        tooltip = `${n} non-excepted vulnerabilit${n === 1 ? 'y' : 'ies'}, none older than ${days} days yet.`;
    } else {
        tooltip = 'No vulnerabilities, or all of them are covered by an active exception.';
    }

    return (
        <span className="d-inline-flex align-items-center gap-2" title={tooltip}>
            <span
                aria-hidden="true"
                style={{
                    display: 'inline-block',
                    width: '0.75rem',
                    height: '0.75rem',
                    borderRadius: '50%',
                    backgroundColor: COLORS[status],
                    flexShrink: 0,
                }}
            />
            {showLabel ? (
                <span className="small">{label}</span>
            ) : (
                <span className="visually-hidden">{label}</span>
            )}
        </span>
    );
};

export default AssetStatusLamp;
