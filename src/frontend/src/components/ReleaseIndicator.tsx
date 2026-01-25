/**
 * ReleaseIndicator Component
 * Feature: 067-requirement-releases, FR-003
 *
 * Displays the current release context in the upper right of the requirements UI.
 * Shows "CURRENT" when viewing live requirements or the release version when
 * viewing a historical release.
 */

import React from 'react';

interface Release {
    id: number;
    version: string;
    name: string;
    status: string;
}

interface ReleaseIndicatorProps {
    selectedRelease: Release | null;
    onClearRelease?: () => void;
}

const ReleaseIndicator: React.FC<ReleaseIndicatorProps> = ({
    selectedRelease,
    onClearRelease
}) => {
    const isHistorical = selectedRelease !== null;

    return (
        <div
            className={`release-indicator d-flex align-items-center gap-2 px-3 py-2 rounded ${
                isHistorical ? 'bg-warning bg-opacity-25 border border-warning' : 'bg-success bg-opacity-10 border border-success'
            }`}
            data-testid="release-indicator"
        >
            <i className={`bi ${isHistorical ? 'bi-clock-history' : 'bi-check-circle'}`}></i>
            <span className="fw-semibold">
                {isHistorical ? (
                    <>
                        v{selectedRelease.version}
                        <span className="text-muted ms-2 fw-normal" style={{ fontSize: '0.85em' }}>
                            (Historical)
                        </span>
                    </>
                ) : (
                    'CURRENT'
                )}
            </span>
            {isHistorical && onClearRelease && (
                <button
                    className="btn btn-sm btn-outline-secondary ms-2 py-0 px-1"
                    onClick={onClearRelease}
                    title="Return to current requirements"
                    aria-label="Clear release selection"
                >
                    <i className="bi bi-x"></i>
                </button>
            )}
        </div>
    );
};

export default ReleaseIndicator;
