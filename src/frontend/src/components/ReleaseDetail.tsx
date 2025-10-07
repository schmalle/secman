/**
 * ReleaseDetail Component
 *
 * Displays complete release metadata and paginated requirement snapshots
 *
 * Features:
 * - Full release metadata display
 * - Paginated snapshot table (50 items per page)
 * - Click snapshot to view complete details
 * - Export button for release requirements
 * - Back navigation to list
 *
 * Related to: Feature 012-build-ui-for, User Story 3 (View Release Details)
 */

import React, { useState, useEffect } from 'react';
import { releaseService, type Release, type RequirementSnapshot, type PaginatedResponse } from '../services/releaseService';
import { hasRole, getUser } from '../utils/auth';
import { canDeleteRelease } from '../utils/permissions';
import ReleaseStatusActions from './ReleaseStatusActions';
import ReleaseDeleteConfirm from './ReleaseDeleteConfirm';
import Toast from './Toast';

interface ReleaseDetailProps {
    releaseId: number;
}

interface SnapshotDetailModalProps {
    snapshot: RequirementSnapshot | null;
    isOpen: boolean;
    onClose: () => void;
}

/**
 * Modal for displaying complete snapshot details
 */
const SnapshotDetailModal: React.FC<SnapshotDetailModalProps> = ({ snapshot, isOpen, onClose }) => {
    if (!isOpen || !snapshot) {
        return null;
    }

    return (
        <>
            {/* Backdrop */}
            <div
                className="modal-backdrop fade show"
                onClick={onClose}
                style={{ zIndex: 1040 }}
            ></div>

            {/* Modal */}
            <div
                className="modal fade show d-block"
                tabIndex={-1}
                role="dialog"
                style={{ zIndex: 1050 }}
                aria-labelledby="snapshotDetailModalLabel"
                aria-modal="true"
            >
                <div className="modal-dialog modal-lg modal-dialog-scrollable">
                    <div className="modal-content">
                        {/* Header */}
                        <div className="modal-header">
                            <h5 className="modal-title" id="snapshotDetailModalLabel">
                                Requirement Snapshot: {snapshot.shortreq}
                            </h5>
                            <button
                                type="button"
                                className="btn-close"
                                aria-label="Close"
                                onClick={onClose}
                            ></button>
                        </div>

                        {/* Body */}
                        <div className="modal-body">
                            <dl className="row">
                                <dt className="col-sm-3">Short Requirement:</dt>
                                <dd className="col-sm-9">{snapshot.shortreq}</dd>

                                <dt className="col-sm-3">Chapter:</dt>
                                <dd className="col-sm-9">{snapshot.chapter}</dd>

                                <dt className="col-sm-3">Norm:</dt>
                                <dd className="col-sm-9">{snapshot.norm}</dd>

                                <dt className="col-sm-3">Details:</dt>
                                <dd className="col-sm-9">
                                    <div style={{ whiteSpace: 'pre-wrap' }}>{snapshot.details}</div>
                                </dd>

                                {snapshot.motivation && (
                                    <>
                                        <dt className="col-sm-3">Motivation:</dt>
                                        <dd className="col-sm-9">
                                            <div style={{ whiteSpace: 'pre-wrap' }}>{snapshot.motivation}</div>
                                        </dd>
                                    </>
                                )}

                                {snapshot.example && (
                                    <>
                                        <dt className="col-sm-3">Example:</dt>
                                        <dd className="col-sm-9">
                                            <div style={{ whiteSpace: 'pre-wrap' }}>{snapshot.example}</div>
                                        </dd>
                                    </>
                                )}

                                {snapshot.usecase && (
                                    <>
                                        <dt className="col-sm-3">Use Case:</dt>
                                        <dd className="col-sm-9">{snapshot.usecase}</dd>
                                    </>
                                )}

                                <dt className="col-sm-3">Snapshot Time:</dt>
                                <dd className="col-sm-9">
                                    {new Date(snapshot.snapshotTimestamp).toLocaleString()}
                                </dd>

                                <dt className="col-sm-3">Original ID:</dt>
                                <dd className="col-sm-9">{snapshot.originalRequirementId}</dd>
                            </dl>
                        </div>

                        {/* Footer */}
                        <div className="modal-footer">
                            <button type="button" className="btn btn-secondary" onClick={onClose}>
                                Close
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </>
    );
};

/**
 * Main ReleaseDetail component
 */
const ReleaseDetail: React.FC<ReleaseDetailProps> = ({ releaseId }) => {
    // State
    const [release, setRelease] = useState<Release | null>(null);
    const [snapshots, setSnapshots] = useState<RequirementSnapshot[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // Pagination state
    const [currentPage, setCurrentPage] = useState(1);
    const [totalPages, setTotalPages] = useState(1);
    const [totalItems, setTotalItems] = useState(0);
    const pageSize = 50;

    // Modal state
    const [selectedSnapshot, setSelectedSnapshot] = useState<RequirementSnapshot | null>(null);
    const [showSnapshotModal, setShowSnapshotModal] = useState(false);
    const [showDeleteModal, setShowDeleteModal] = useState(false);
    const [isDeleting, setIsDeleting] = useState(false);

    // Toast state
    const [toast, setToast] = useState<{
        show: boolean;
        message: string;
        type: 'success' | 'error' | 'warning' | 'info';
    }>({
        show: false,
        message: '',
        type: 'success',
    });

    // User info
    const user = getUser();
    const userRoles = user?.roles || [];

    // Fetch release and snapshots
    useEffect(() => {
        loadReleaseData();
    }, [releaseId, currentPage]);

    async function loadReleaseData() {
        try {
            setLoading(true);
            setError(null);

            // Fetch release metadata
            const releaseData = await releaseService.getById(releaseId);
            setRelease(releaseData);

            // Fetch snapshots
            const snapshotsData: PaginatedResponse<RequirementSnapshot> = await releaseService.getSnapshots(
                releaseId,
                currentPage,
                pageSize
            );

            setSnapshots(snapshotsData.data);
            setTotalPages(snapshotsData.totalPages);
            setTotalItems(snapshotsData.totalItems);
            setCurrentPage(snapshotsData.currentPage);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to load release details');
        } finally {
            setLoading(false);
        }
    }

    // Handle snapshot click
    function handleSnapshotClick(snapshot: RequirementSnapshot) {
        setSelectedSnapshot(snapshot);
        setShowSnapshotModal(true);
    }

    // Handle modal close
    function handleModalClose() {
        setShowSnapshotModal(false);
        setSelectedSnapshot(null);
    }

    // Handle page change
    function handlePageChange(page: number) {
        setCurrentPage(page);
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }

    // Handle export
    function handleExport(format: 'xlsx' | 'docx') {
        const url = `/api/requirements/export/${format}?releaseId=${releaseId}`;
        window.location.href = url;
    }

    // Handle back navigation
    function handleBack() {
        window.location.href = '/releases';
    }

    // Handle status change (callback from ReleaseStatusActions)
    function handleStatusChange(updatedRelease: Release) {
        setRelease(updatedRelease);
    }

    // Handle toast close
    function handleToastClose() {
        setToast((prev) => ({ ...prev, show: false }));
    }

    // Handle delete button click
    function handleDeleteClick() {
        setShowDeleteModal(true);
    }

    // Handle delete confirmation
    async function handleDeleteConfirm() {
        if (!release) return;

        setIsDeleting(true);
        try {
            await releaseService.delete(release.id);
            
            setToast({
                show: true,
                message: `Release v${release.version} deleted successfully`,
                type: 'success',
            });

            // Navigate back to list after short delay
            setTimeout(() => {
                window.location.href = '/releases';
            }, 1500);
        } catch (err) {
            const errorMessage = err instanceof Error 
                ? err.message 
                : 'Failed to delete release. Please try again.';
            
            setToast({
                show: true,
                message: errorMessage,
                type: 'error',
            });

            // Close modal on error
            setShowDeleteModal(false);
        } finally {
            setIsDeleting(false);
        }
    }

    // Handle delete modal close
    function handleDeleteModalClose() {
        if (!isDeleting) {
            setShowDeleteModal(false);
        }
    }

    // Get status badge class
    function getStatusBadgeClass(status: string): string {
        switch (status) {
            case 'DRAFT':
                return 'bg-warning text-dark';
            case 'PUBLISHED':
                return 'bg-success';
            case 'ARCHIVED':
                return 'bg-secondary';
            default:
                return 'bg-secondary';
        }
    }

    // Format date
    function formatDate(dateString: string | null): string {
        if (!dateString) return 'N/A';
        const date = new Date(dateString);
        return date.toLocaleString();
    }

    // Truncate text for table preview
    function truncate(text: string, maxLength: number = 100): string {
        if (!text) return '';
        return text.length > maxLength ? text.substring(0, maxLength) + '...' : text;
    }

    // Render loading state
    if (loading && !release) {
        return (
            <div className="container mt-4">
                <div className="d-flex justify-content-center align-items-center" style={{ minHeight: '400px' }}>
                    <div className="spinner-border text-primary" role="status">
                        <span className="visually-hidden">Loading...</span>
                    </div>
                </div>
            </div>
        );
    }

    // Render error state
    if (error) {
        return (
            <div className="container mt-4">
                <div className="alert alert-danger" role="alert">
                    <strong>Error:</strong> {error}
                    <div className="mt-3">
                        <button className="btn btn-outline-danger me-2" onClick={loadReleaseData}>
                            Retry
                        </button>
                        <button className="btn btn-outline-secondary" onClick={handleBack}>
                            Back to List
                        </button>
                    </div>
                </div>
            </div>
        );
    }

    // Render not found state
    if (!release) {
        return (
            <div className="container mt-4">
                <div className="alert alert-warning" role="alert">
                    <strong>Not Found:</strong> Release not found.
                    <div className="mt-3">
                        <button className="btn btn-outline-secondary" onClick={handleBack}>
                            Back to List
                        </button>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div className="container mt-4">
            {/* Header with Back Button */}
            <div className="row mb-4">
                <div className="col">
                    <button className="btn btn-link ps-0" onClick={handleBack}>
                        <i className="bi bi-arrow-left me-2"></i>
                        Back to Releases
                    </button>
                </div>
            </div>

            {/* Release Metadata Card */}
            <div className="card mb-4">
                <div className="card-header bg-primary text-white">
                    <h2 className="mb-0">
                        {release.name}
                        <span 
                            className={`badge ${getStatusBadgeClass(release.status)} ms-3`}
                            aria-label={`Release status: ${release.status}`}
                        >
                            {release.status}
                        </span>
                    </h2>
                </div>
                <div className="card-body">
                    <div className="row">
                        <div className="col-md-6">
                            <dl className="row">
                                <dt className="col-sm-4">Version:</dt>
                                <dd className="col-sm-8">{release.version}</dd>

                                <dt className="col-sm-4">Status:</dt>
                                <dd className="col-sm-8">
                                    <span 
                                        className={`badge ${getStatusBadgeClass(release.status)}`}
                                        aria-label={`Release status: ${release.status}`}
                                    >
                                        {release.status}
                                    </span>
                                </dd>

                                <dt className="col-sm-4">Created By:</dt>
                                <dd className="col-sm-8">{release.createdBy}</dd>

                                <dt className="col-sm-4">Created At:</dt>
                                <dd className="col-sm-8">{formatDate(release.createdAt)}</dd>
                            </dl>
                        </div>
                        <div className="col-md-6">
                            <dl className="row">
                                <dt className="col-sm-4">Release Date:</dt>
                                <dd className="col-sm-8">{formatDate(release.releaseDate)}</dd>

                                <dt className="col-sm-4">Updated At:</dt>
                                <dd className="col-sm-8">{formatDate(release.updatedAt)}</dd>

                                <dt className="col-sm-4">Requirement Count:</dt>
                                <dd className="col-sm-8">
                                    <span className="badge bg-info">{release.requirementCount}</span>
                                </dd>
                            </dl>
                        </div>
                    </div>

                    {release.description && (
                        <div className="mt-3">
                            <strong>Description:</strong>
                            <p className="mt-2">{release.description}</p>
                        </div>
                    )}

                    {/* Status Actions - Publish/Archive buttons */}
                    <div className="mt-4">
                        <ReleaseStatusActions
                            release={release}
                            onStatusChange={handleStatusChange}
                        />
                    </div>

                    {/* Action Buttons */}
                    <div className="mt-4">
                        <div className="btn-group me-3" role="group">
                            <button
                                className="btn btn-primary"
                                onClick={() => handleExport('xlsx')}
                            >
                                <i className="bi bi-download me-2"></i>
                                Export to Excel
                            </button>
                            <button
                                className="btn btn-outline-primary"
                                onClick={() => handleExport('docx')}
                            >
                                <i className="bi bi-file-word me-2"></i>
                                Export to Word
                            </button>
                        </div>
                        
                        {canDeleteRelease(release, user, userRoles) && (
                            <button
                                className="btn btn-outline-danger"
                                onClick={handleDeleteClick}
                                data-testid="delete-release-detail"
                            >
                                <i className="bi bi-trash me-2"></i>
                                Delete Release
                            </button>
                        )}
                    </div>
                </div>
            </div>

            {/* Requirement Snapshots Section */}
            <div className="card">
                <div className="card-header">
                    <h3 className="mb-0">
                        Requirement Snapshots
                        <span className="badge bg-secondary ms-2">{totalItems} total</span>
                    </h3>
                </div>
                <div className="card-body">
                    {loading && (
                        <div className="text-center py-3">
                            <div className="spinner-border spinner-border-sm text-primary" role="status">
                                <span className="visually-hidden">Loading snapshots...</span>
                            </div>
                        </div>
                    )}

                    {!loading && snapshots.length === 0 && (
                        <div className="alert alert-info">
                            <i className="bi bi-info-circle me-2"></i>
                            No requirement snapshots found in this release.
                        </div>
                    )}

                    {!loading && snapshots.length > 0 && (
                        <>
                            <div className="table-responsive">
                                <table className="table table-hover">
                                    <thead>
                                        <tr>
                                            <th>Short Req</th>
                                            <th>Chapter</th>
                                            <th>Norm</th>
                                            <th>Details</th>
                                            <th>Motivation</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {snapshots.map((snapshot) => (
                                            <tr
                                                key={snapshot.id}
                                                onClick={() => handleSnapshotClick(snapshot)}
                                                style={{ cursor: 'pointer' }}
                                            >
                                                <td>{snapshot.shortreq}</td>
                                                <td>{snapshot.chapter}</td>
                                                <td>{snapshot.norm}</td>
                                                <td>{truncate(snapshot.details, 80)}</td>
                                                <td>{truncate(snapshot.motivation, 60)}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>

                            {/* Pagination */}
                            {totalPages > 1 && (
                                <nav aria-label="Snapshot pagination" className="mt-3">
                                    <ul className="pagination justify-content-center" data-testid="pagination">
                                        <li className={`page-item ${currentPage === 1 ? 'disabled' : ''}`}>
                                            <button
                                                className="page-link"
                                                onClick={() => handlePageChange(currentPage - 1)}
                                                disabled={currentPage === 1}
                                                data-testid="prev-page"
                                            >
                                                Previous
                                            </button>
                                        </li>

                                        {[...Array(totalPages)].map((_, index) => {
                                            const page = index + 1;
                                            if (
                                                page === 1 ||
                                                page === totalPages ||
                                                (page >= currentPage - 2 && page <= currentPage + 2)
                                            ) {
                                                return (
                                                    <li
                                                        key={page}
                                                        className={`page-item ${currentPage === page ? 'active' : ''}`}
                                                    >
                                                        <button
                                                            className="page-link"
                                                            onClick={() => handlePageChange(page)}
                                                            data-testid={`page-${page}`}
                                                        >
                                                            {page}
                                                        </button>
                                                    </li>
                                                );
                                            } else if (page === currentPage - 3 || page === currentPage + 3) {
                                                return (
                                                    <li key={page} className="page-item disabled">
                                                        <span className="page-link">...</span>
                                                    </li>
                                                );
                                            }
                                            return null;
                                        })}

                                        <li className={`page-item ${currentPage === totalPages ? 'disabled' : ''}`}>
                                            <button
                                                className="page-link"
                                                onClick={() => handlePageChange(currentPage + 1)}
                                                disabled={currentPage === totalPages}
                                                data-testid="next-page"
                                            >
                                                Next
                                            </button>
                                        </li>
                                    </ul>
                                </nav>
                            )}

                            <p className="text-muted text-center mt-2">
                                Showing {(currentPage - 1) * pageSize + 1} to{' '}
                                {Math.min(currentPage * pageSize, totalItems)} of {totalItems} snapshots
                            </p>
                        </>
                    )}
                </div>
            </div>

            {/* Snapshot Detail Modal */}
            <SnapshotDetailModal
                snapshot={selectedSnapshot}
                isOpen={showSnapshotModal}
                onClose={handleModalClose}
            />

            {/* Delete Release Modal */}
            <ReleaseDeleteConfirm
                release={release}
                isOpen={showDeleteModal}
                isDeleting={isDeleting}
                onClose={handleDeleteModalClose}
                onConfirm={handleDeleteConfirm}
            />

            {/* Toast Notifications */}
            <Toast
                message={toast.message}
                type={toast.type}
                show={toast.show}
                onClose={handleToastClose}
            />
        </div>
    );
};

export default ReleaseDetail;
