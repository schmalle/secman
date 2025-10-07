/**
 * ReleaseStatusActions Component
 *
 * Provides status transition buttons for releases (Publish, Archive)
 * Enforces workflow: DRAFT → PUBLISHED → ARCHIVED
 *
 * Features:
 * - Shows "Publish" button for DRAFT releases (ADMIN/RELEASE_MANAGER only)
 * - Shows "Archive" button for PUBLISHED releases (ADMIN/RELEASE_MANAGER only)
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

type TransitionType = 'publish' | 'archive' | null;

/**
 * Status transition confirmation modal
 */
interface StatusTransitionModalProps {
    release: Release;
    transitionType: TransitionType;
    isOpen: boolean;
    isLoading: boolean;
    onClose: () => void;
    onConfirm: () => void;
}

const StatusTransitionModal: React.FC<StatusTransitionModalProps> = ({
    release,
    transitionType,
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

    if (!isOpen || !transitionType) {
        return null;
    }

    const isPublish = transitionType === 'publish';
    const title = isPublish ? 'Publish Release' : 'Archive Release';
    const newStatus = isPublish ? 'PUBLISHED' : 'ARCHIVED';
    const message = isPublish
        ? 'This will make it available for exports and comparisons.'
        : 'This will mark it as historical.';

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
                                {title}
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
                                Are you sure you want to {isPublish ? 'publish' : 'archive'}{' '}
                                <strong>{release.version} - {release.name}</strong>?
                            </p>
                            <p className="text-muted mb-0">{message}</p>
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
                                className={`btn btn-${isPublish ? 'success' : 'warning'}`}
                                onClick={onConfirm}
                                disabled={isLoading}
                            >
                                {isLoading ? (
                                    <>
                                        <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                        {isPublish ? 'Publishing...' : 'Archiving...'}
                                    </>
                                ) : (
                                    `Confirm ${isPublish ? 'Publish' : 'Archive'}`
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
    const [transitionType, setTransitionType] = useState<TransitionType>(null);
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    // Check user permissions
    const canManageStatus = hasRole(['ADMIN', 'RELEASE_MANAGER']);

    // Only show actions if user has permission
    if (!canManageStatus) {
        return null;
    }

    // Determine which button to show based on current status
    const showPublishButton = release.status === 'DRAFT';
    const showArchiveButton = release.status === 'PUBLISHED';

    /**
     * Handle status transition confirmation
     */
    const handleConfirmTransition = async () => {
        if (!transitionType) return;

        setIsLoading(true);
        setError(null);

        try {
            const newStatus = transitionType === 'publish' ? 'PUBLISHED' : 'ARCHIVED';
            const updatedRelease = await releaseService.updateStatus(release.id, newStatus);
            
            // Notify parent component of status change
            onStatusChange(updatedRelease);

            // Close modal
            setTransitionType(null);
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
            setTransitionType(null);
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

            {/* Action Buttons */}
            <div className="d-flex gap-2">
                {showPublishButton && (
                    <button
                        type="button"
                        className="btn btn-success"
                        onClick={() => setTransitionType('publish')}
                        title="Publish this release"
                    >
                        <i className="bi bi-upload me-2"></i>
                        Publish
                    </button>
                )}

                {showArchiveButton && (
                    <button
                        type="button"
                        className="btn btn-warning"
                        onClick={() => setTransitionType('archive')}
                        title="Archive this release"
                    >
                        <i className="bi bi-archive me-2"></i>
                        Archive
                    </button>
                )}
            </div>

            {/* Confirmation Modal */}
            <StatusTransitionModal
                release={release}
                transitionType={transitionType}
                isOpen={transitionType !== null}
                isLoading={isLoading}
                onClose={handleCloseModal}
                onConfirm={handleConfirmTransition}
            />
        </div>
    );
};

export default ReleaseStatusActions;
