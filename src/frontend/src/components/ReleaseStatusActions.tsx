/**
 * ReleaseStatusActions Component
 *
 * Provides status transition buttons for releases
 * Enforces workflow: PREPARATION → ALIGNMENT → ACTIVE, ACTIVE → ARCHIVED (automatic)
 *
 * Features:
 * - Shows "Start Alignment" button for PREPARATION releases (ADMIN/RELEASE_MANAGER only)
 * - Shows alignment dashboard link for ALIGNMENT releases
 * - Shows "Set Active" button for ALIGNMENT releases after alignment
 * - When a release is set to ACTIVE, the previously ACTIVE release becomes ARCHIVED
 * - Confirmation modal before transitions
 * - Loading state during API calls
 * - Error handling with user feedback
 *
 * Related to: Feature 012-build-ui-for, User Story 5 (Status Lifecycle)
 * Updated for: Feature 068-requirements-alignment-process
 */

import React, { useState, useEffect } from 'react';
import { releaseService, type Release, type AlignmentStatus } from '../services/releaseService';
import { hasRole } from '../utils/auth';

interface ReleaseStatusActionsProps {
    release: Release;
    onStatusChange: (updatedRelease: Release) => void;
    onAlignmentStart?: () => void;
}

/**
 * Status transition confirmation modal
 */
interface StatusTransitionModalProps {
    release: Release;
    isOpen: boolean;
    isLoading: boolean;
    onClose: () => void;
    onConfirm: () => void;
}

const StatusTransitionModal: React.FC<StatusTransitionModalProps> = ({
    release,
    isOpen,
    isLoading,
    onClose,
    onConfirm
}) => {
    // Handle keyboard navigation (Escape to close)
    React.useEffect(() => {
        if (!isOpen) return;

        function handleKeyDown(e: KeyboardEvent) {
            if (e.key === 'Escape' && !isLoading) {
                onClose();
            }
        }

        document.addEventListener('keydown', handleKeyDown);
        return () => document.removeEventListener('keydown', handleKeyDown);
    }, [isOpen, isLoading, onClose]);

    if (!isOpen) {
        return null;
    }

    return (
        <>
            {/* Backdrop */}
            <div
                className="modal-backdrop fade show"
                onClick={isLoading ? undefined : onClose}
                style={{ zIndex: 1040 }}
            ></div>

            {/* Modal */}
            <div
                className="modal fade show d-block"
                tabIndex={-1}
                role="dialog"
                style={{ zIndex: 1050 }}
                aria-labelledby="statusTransitionModalLabel"
                aria-modal="true"
            >
                <div className="modal-dialog">
                    <div className="modal-content">
                        {/* Header */}
                        <div className="modal-header">
                            <h5 className="modal-title" id="statusTransitionModalLabel">
                                Set Release Active
                            </h5>
                            <button
                                type="button"
                                className="btn-close"
                                aria-label="Close"
                                onClick={onClose}
                                disabled={isLoading}
                            ></button>
                        </div>

                        {/* Body */}
                        <div className="modal-body">
                            <p>
                                Are you sure you want to set{' '}
                                <strong>{release.version} - {release.name}</strong> as active?
                            </p>
                            <p className="text-muted mb-0">
                                This will mark this release as the currently active version.
                                Any previously active release will be moved to ARCHIVED status.
                            </p>
                        </div>

                        {/* Footer */}
                        <div className="modal-footer">
                            <button
                                type="button"
                                className="btn btn-secondary"
                                onClick={onClose}
                                disabled={isLoading}
                            >
                                Cancel
                            </button>
                            <button
                                type="button"
                                className="btn btn-success"
                                onClick={onConfirm}
                                disabled={isLoading}
                            >
                                {isLoading ? (
                                    <>
                                        <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                        Setting Active...
                                    </>
                                ) : (
                                    'Confirm Set Active'
                                )}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </>
    );
};

/**
 * Main component: Status action buttons with workflow enforcement
 */
export const ReleaseStatusActions: React.FC<ReleaseStatusActionsProps> = ({
    release,
    onStatusChange,
    onAlignmentStart
}) => {
    const [showModal, setShowModal] = useState(false);
    const [showAlignmentModal, setShowAlignmentModal] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [hasChanges, setHasChanges] = useState<boolean | null>(null);
    const [alignmentStatus, setAlignmentStatus] = useState<AlignmentStatus | null>(null);
    const [reviewAll, setReviewAll] = useState(false);

    // Check user permissions
    const canManageStatus = typeof window !== 'undefined' && hasRole(['ADMIN', 'RELEASE_MANAGER']);

    // Check for changes when in PREPARATION status
    useEffect(() => {
        if (release.status === 'PREPARATION' && canManageStatus) {
            releaseService.checkAlignmentRequired(release.id)
                .then(result => setHasChanges(result.hasChanges))
                .catch(() => setHasChanges(null));
        }
    }, [release.id, release.status, canManageStatus]);

    // Get alignment status when ALIGNMENT
    useEffect(() => {
        if (release.status === 'ALIGNMENT' && canManageStatus) {
            releaseService.getAlignmentStatus(release.id)
                .then(status => setAlignmentStatus(status))
                .catch(() => setAlignmentStatus(null));
        }
    }, [release.id, release.status, canManageStatus]);

    // Only show actions if user has permission
    if (!canManageStatus) {
        return null;
    }

    // Don't show actions for ACTIVE or ARCHIVED releases
    if (release.status === 'ACTIVE' || release.status === 'ARCHIVED') {
        return null;
    }

    /**
     * Handle starting alignment process
     */
    const handleStartAlignment = async () => {
        setIsLoading(true);
        setError(null);

        try {
            const result = await releaseService.startAlignment(release.id, reviewAll);

            // Notify parent
            onAlignmentStart?.();

            // Update release status locally
            onStatusChange({ ...release, status: 'ALIGNMENT' });

            setShowAlignmentModal(false);
        } catch (err) {
            const message = err instanceof Error ? err.message : 'Failed to start alignment';
            setError(message);
        } finally {
            setIsLoading(false);
        }
    };

    /**
     * Handle status transition confirmation
     */
    const handleConfirmTransition = async () => {
        setIsLoading(true);
        setError(null);

        try {
            const updatedRelease = await releaseService.updateStatus(release.id, 'ACTIVE');

            // Notify parent component of status change
            onStatusChange(updatedRelease);

            // Close modal
            setShowModal(false);
        } catch (err) {
            const message = err instanceof Error ? err.message : 'Failed to update status';
            setError(message);
        } finally {
            setIsLoading(false);
        }
    };

    /**
     * Close modal and reset state
     */
    const handleCloseModal = () => {
        if (!isLoading) {
            setShowModal(false);
            setShowAlignmentModal(false);
            setReviewAll(false);
            setError(null);
        }
    };

    return (
        <div className="release-status-actions">
            {/* Error Alert */}
            {error && (
                <div className="alert alert-danger alert-dismissible fade show" role="alert">
                    <strong>Error:</strong> {error}
                    <button
                        type="button"
                        className="btn-close"
                        aria-label="Close"
                        onClick={() => setError(null)}
                    ></button>
                </div>
            )}

            {/* PREPARATION status: Show Start Alignment button */}
            {release.status === 'PREPARATION' && (
                <div className="d-flex flex-column gap-2">
                    <div className="d-flex gap-2">
                        <button
                            type="button"
                            className="btn btn-primary"
                            onClick={() => setShowAlignmentModal(true)}
                            title="Start requirements alignment process"
                        >
                            <i className="bi bi-clipboard-check me-2"></i>
                            Start Alignment
                        </button>
                        <button
                            type="button"
                            className="btn btn-success"
                            onClick={() => setShowModal(true)}
                            title="Skip alignment and activate directly"
                        >
                            <i className="bi bi-check-circle me-2"></i>
                            Set Active
                        </button>
                    </div>
                    {hasChanges === false && (
                        <small className="text-muted">
                            <i className="bi bi-info-circle me-1"></i>
                            No requirement changes detected since last active release
                        </small>
                    )}
                </div>
            )}

            {/* ALIGNMENT status: Show alignment progress and dashboard link */}
            {release.status === 'ALIGNMENT' && alignmentStatus && (
                <div className="d-flex flex-column gap-2">
                    <div className="d-flex gap-2 align-items-center">
                        <span className="badge bg-info">
                            <i className="bi bi-hourglass-split me-1"></i>
                            In Review
                        </span>
                        <small className="text-muted">
                            {alignmentStatus.reviewers.completed}/{alignmentStatus.reviewers.total} reviewers complete
                        </small>
                    </div>
                    <div className="btn-group">
                        <a
                            href={`/releases/${release.id}/alignment`}
                            className="btn btn-outline-primary btn-sm"
                        >
                            <i className="bi bi-graph-up me-1"></i>
                            View Dashboard
                        </a>
                    </div>
                </div>
            )}

            {/* Alignment Start Modal */}
            {showAlignmentModal && (
                <>
                    <div
                        className="modal-backdrop fade show"
                        onClick={isLoading ? undefined : handleCloseModal}
                        style={{ zIndex: 1040 }}
                    ></div>
                    <div
                        className="modal fade show d-block"
                        tabIndex={-1}
                        role="dialog"
                        style={{ zIndex: 1050 }}
                    >
                        <div className="modal-dialog">
                            <div className="modal-content">
                                <div className="modal-header">
                                    <h5 className="modal-title">Start Requirements Alignment</h5>
                                    <button
                                        type="button"
                                        className="btn-close"
                                        onClick={handleCloseModal}
                                        disabled={isLoading}
                                    ></button>
                                </div>
                                <div className="modal-body">
                                    <p>
                                        Starting the alignment process for{' '}
                                        <strong>{release.version} - {release.name}</strong>
                                    </p>

                                    <div className="mb-3">
                                        <label className="form-label fw-semibold">Review Scope</label>
                                        <div className="form-check">
                                            <input
                                                type="radio"
                                                className="form-check-input"
                                                name="reviewScope"
                                                id="scopeChanged"
                                                checked={!reviewAll}
                                                onChange={() => setReviewAll(false)}
                                            />
                                            <label className="form-check-label" htmlFor="scopeChanged">
                                                Review changed requirements
                                                <small className="d-block text-muted">Only requirements added, modified, or deleted since the last active release</small>
                                            </label>
                                        </div>
                                        <div className="form-check mt-2">
                                            <input
                                                type="radio"
                                                className="form-check-input"
                                                name="reviewScope"
                                                id="scopeAll"
                                                checked={reviewAll}
                                                onChange={() => setReviewAll(true)}
                                            />
                                            <label className="form-check-label" htmlFor="scopeAll">
                                                Review all requirements
                                                <small className="d-block text-muted">Include every current requirement for a full review</small>
                                            </label>
                                        </div>
                                    </div>

                                    <p className="text-muted">
                                        This will:
                                    </p>
                                    <ul className="text-muted">
                                        <li>Change release status to ALIGNMENT</li>
                                        <li>Send email notifications to all users with REQ role</li>
                                        <li>Allow reviewers to submit feedback on requirement changes</li>
                                    </ul>
                                </div>
                                <div className="modal-footer">
                                    <button
                                        type="button"
                                        className="btn btn-secondary"
                                        onClick={handleCloseModal}
                                        disabled={isLoading}
                                    >
                                        Cancel
                                    </button>
                                    <button
                                        type="button"
                                        className="btn btn-primary"
                                        onClick={handleStartAlignment}
                                        disabled={isLoading}
                                    >
                                        {isLoading ? (
                                            <>
                                                <span className="spinner-border spinner-border-sm me-2"></span>
                                                Starting...
                                            </>
                                        ) : (
                                            'Start Alignment'
                                        )}
                                    </button>
                                </div>
                            </div>
                        </div>
                    </div>
                </>
            )}

            {/* Direct Activation Modal (for no-changes case) */}
            <StatusTransitionModal
                release={release}
                isOpen={showModal}
                isLoading={isLoading}
                onClose={handleCloseModal}
                onConfirm={handleConfirmTransition}
            />
        </div>
    );
};

export default ReleaseStatusActions;
