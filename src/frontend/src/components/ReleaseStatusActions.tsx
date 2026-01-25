/**
 * ReleaseStatusActions Component
 *
 * Provides status transition button for releases (Set Active)
 * Enforces workflow: DRAFT → ACTIVE, ACTIVE → LEGACY (automatic)
 *
 * Features:
 * - Shows "Set Active" button for DRAFT releases (ADMIN/RELEASE_MANAGER only)
 * - When a release is set to ACTIVE, the previously ACTIVE release becomes LEGACY
 * - Confirmation modal before transitions
 * - Loading state during API calls
 * - Error handling with user feedback
 *
 * Related to: Feature 012-build-ui-for, User Story 5 (Status Lifecycle)
 */

import React, { useState } from 'react';
import { releaseService, type Release } from '../services/releaseService';
import { hasRole } from '../utils/auth';

interface ReleaseStatusActionsProps {
    release: Release;
    onStatusChange: (updatedRelease: Release) => void;
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
                                Any previously active release will be moved to LEGACY status.
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
export const ReleaseStatusActions: React.FC<ReleaseStatusActionsProps> = ({ release, onStatusChange }) => {
    const [showModal, setShowModal] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    // Check user permissions
    const canManageStatus = typeof window !== 'undefined' && hasRole(['ADMIN', 'RELEASE_MANAGER']);

    // Only show actions if user has permission and release is in DRAFT status
    if (!canManageStatus || release.status !== 'DRAFT') {
        return null;
    }

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

            {/* Action Button */}
            <button
                type="button"
                className="btn btn-success"
                onClick={() => setShowModal(true)}
                title="Set this release as active"
            >
                <i className="bi bi-check-circle me-2"></i>
                Set Active
            </button>

            {/* Confirmation Modal */}
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
