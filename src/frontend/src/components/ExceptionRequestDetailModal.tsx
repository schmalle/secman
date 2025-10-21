/**
 * ExceptionRequestDetailModal Component
 *
 * Modal for viewing detailed information about an exception request
 *
 * Features:
 * - Vulnerability details (CVE, severity, asset, days open, products)
 * - Request details (scope, reason, expiration date)
 * - Status information (current status, reviewer, review comments)
 * - Auto-approval indicator
 * - Cancel action for pending requests (owner only)
 *
 * Feature: 031-vuln-exception-approval
 * User Story 1: Regular User Requests Exception (P1)
 * Reference: spec.md FR-017
 */

import React, { useState, useEffect } from 'react';
import { getRequestById, cancelRequest, type VulnerabilityExceptionRequestDto } from '../services/exceptionRequestService';
import ExceptionStatusBadge from './ExceptionStatusBadge';

interface ExceptionRequestDetailModalProps {
    isOpen: boolean;
    requestId: number;
    onClose: () => void;
    onUpdate?: () => void; // Optional callback after cancel
}

const ExceptionRequestDetailModal: React.FC<ExceptionRequestDetailModalProps> = ({
    isOpen,
    requestId,
    onClose,
    onUpdate
}) => {
    const [request, setRequest] = useState<VulnerabilityExceptionRequestDto | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [cancelling, setCancelling] = useState(false);

    useEffect(() => {
        if (isOpen) {
            fetchRequestDetails();
        }
    }, [isOpen, requestId]);

    const fetchRequestDetails = async () => {
        try {
            setLoading(true);
            setError(null);
            const data = await getRequestById(requestId);
            setRequest(data);
        } catch (err) {
            const errorMessage = err instanceof Error ? err.message : 'Failed to load request details';
            setError(errorMessage);
        } finally {
            setLoading(false);
        }
    };

    const handleCancel = async () => {
        if (!confirm('Are you sure you want to cancel this exception request?')) {
            return;
        }

        try {
            setCancelling(true);
            await cancelRequest(requestId);

            // Notify parent and close
            if (onUpdate) {
                onUpdate();
            }
            onClose();
        } catch (err) {
            const errorMessage = err instanceof Error ? err.message : 'Failed to cancel request';
            setError(errorMessage);
        } finally {
            setCancelling(false);
        }
    };

    // Handle keyboard navigation (Escape to close)
    useEffect(() => {
        if (!isOpen) return;

        function handleKeyDown(e: KeyboardEvent) {
            if (e.key === 'Escape' && !cancelling) {
                onClose();
            }
        }

        document.addEventListener('keydown', handleKeyDown);
        return () => document.removeEventListener('keydown', handleKeyDown);
    }, [isOpen, cancelling]);

    // Don't render if not open
    if (!isOpen) {
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
                aria-labelledby="exceptionRequestDetailModalLabel"
                aria-modal="true"
            >
                <div className="modal-dialog modal-dialog-centered modal-lg">
                    <div className="modal-content">
                        {/* Header */}
                        <div className="modal-header">
                            <h5 className="modal-title" id="exceptionRequestDetailModalLabel">
                                Exception Request Details
                            </h5>
                            <button
                                type="button"
                                className="btn-close"
                                aria-label="Close"
                                onClick={onClose}
                                disabled={cancelling}
                            ></button>
                        </div>

                        {/* Body */}
                        <div className="modal-body">
                            {loading && (
                                <div className="d-flex justify-content-center p-5">
                                    <div className="spinner-border" role="status">
                                        <span className="visually-hidden">Loading...</span>
                                    </div>
                                </div>
                            )}

                            {error && (
                                <div className="alert alert-danger" role="alert">
                                    <i className="bi bi-exclamation-triangle me-2"></i>
                                    {error}
                                </div>
                            )}

                            {request && !loading && (
                                <>
                                    {/* Status Section */}
                                    <div className="mb-4">
                                        <h6 className="text-muted mb-2">Current Status</h6>
                                        <div className="d-flex align-items-center">
                                            <ExceptionStatusBadge status={request.status} autoApproved={request.autoApproved} className="fs-6" />
                                        </div>
                                    </div>

                                    {/* Auto-Approval Info Alert */}
                                    {request.autoApproved && (
                                        <div className="alert alert-info mb-4">
                                            <i className="bi bi-info-circle me-2"></i>
                                            <strong>Auto-Approved:</strong> This request was automatically approved because you have ADMIN or SECCHAMPION role.
                                        </div>
                                    )}

                                    {/* Vulnerability Information */}
                                    <div className="mb-4">
                                        <h6 className="text-muted mb-3">Vulnerability Information</h6>
                                        <div className="row">
                                            <div className="col-md-6 mb-2">
                                                <strong>CVE ID:</strong>
                                                <br />
                                                <code>{request.vulnerabilityCveId || 'Unknown'}</code>
                                            </div>
                                            <div className="col-md-6 mb-2">
                                                <strong>Asset:</strong>
                                                <br />
                                                {request.assetName}
                                                {request.assetIp && (
                                                    <span className="text-muted ms-2">({request.assetIp})</span>
                                                )}
                                            </div>
                                        </div>
                                    </div>

                                    {/* Request Information */}
                                    <div className="mb-4">
                                        <h6 className="text-muted mb-3">Request Information</h6>
                                        <div className="row mb-3">
                                            <div className="col-md-6 mb-2">
                                                <strong>Scope:</strong>
                                                <br />
                                                {request.scope === 'SINGLE_VULNERABILITY' ? (
                                                    <span className="badge bg-info text-dark">
                                                        <i className="bi bi-bullseye me-1"></i>
                                                        Single Vulnerability
                                                    </span>
                                                ) : (
                                                    <span className="badge bg-primary">
                                                        <i className="bi bi-grid-3x3 me-1"></i>
                                                        CVE Pattern
                                                    </span>
                                                )}
                                            </div>
                                            <div className="col-md-6 mb-2">
                                                <strong>Expiration Date:</strong>
                                                <br />
                                                {new Date(request.expirationDate).toLocaleDateString()}
                                            </div>
                                            <div className="col-md-6 mb-2">
                                                <strong>Submitted:</strong>
                                                <br />
                                                {new Date(request.createdAt).toLocaleString()}
                                            </div>
                                            <div className="col-md-6 mb-2">
                                                <strong>Requested By:</strong>
                                                <br />
                                                {request.requestedByUsername}
                                            </div>
                                        </div>
                                        <div>
                                            <strong>Reason:</strong>
                                            <div className="border rounded p-3 mt-2 bg-light" style={{ whiteSpace: 'pre-wrap' }}>
                                                {request.reason}
                                            </div>
                                        </div>
                                    </div>

                                    {/* Review Information (if reviewed) */}
                                    {(request.status === 'APPROVED' || request.status === 'REJECTED') && (
                                        <div className="mb-4">
                                            <h6 className="text-muted mb-3">Review Information</h6>
                                            <div className="row mb-3">
                                                <div className="col-md-6 mb-2">
                                                    <strong>Reviewed By:</strong>
                                                    <br />
                                                    {request.reviewedByUsername || 'Unknown'}
                                                </div>
                                                <div className="col-md-6 mb-2">
                                                    <strong>Review Date:</strong>
                                                    <br />
                                                    {request.reviewDate ? new Date(request.reviewDate).toLocaleString() : '-'}
                                                </div>
                                            </div>
                                            {request.reviewComment && (
                                                <div>
                                                    <strong>Review Comment:</strong>
                                                    <div className={`border rounded p-3 mt-2 ${request.status === 'APPROVED' ? 'bg-success-subtle' : 'bg-danger-subtle'}`} style={{ whiteSpace: 'pre-wrap' }}>
                                                        {request.reviewComment}
                                                    </div>
                                                </div>
                                            )}
                                        </div>
                                    )}

                                    {/* Cancellation Information (if cancelled) */}
                                    {request.status === 'CANCELLED' && (
                                        <div className="alert alert-secondary">
                                            <i className="bi bi-info-circle me-2"></i>
                                            This request was cancelled on {request.updatedAt ? new Date(request.updatedAt).toLocaleString() : 'unknown date'}.
                                        </div>
                                    )}

                                    {/* Expiration Information (if expired) */}
                                    {request.status === 'EXPIRED' && (
                                        <div className="alert alert-warning">
                                            <i className="bi bi-clock me-2"></i>
                                            This approved exception expired on {new Date(request.expirationDate).toLocaleDateString()}.
                                        </div>
                                    )}
                                </>
                            )}
                        </div>

                        {/* Footer */}
                        <div className="modal-footer">
                            <button
                                type="button"
                                className="btn btn-secondary"
                                onClick={onClose}
                                disabled={cancelling}
                            >
                                Close
                            </button>
                            {request && (request.status === 'PENDING' || (request.status === 'APPROVED' && request.autoApproved)) && (
                                <button
                                    type="button"
                                    className="btn btn-danger"
                                    onClick={handleCancel}
                                    disabled={cancelling}
                                    title={request.status === 'APPROVED' ? 'Revoke auto-approved exception' : 'Cancel pending request'}
                                >
                                    {cancelling ? (
                                        <>
                                            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                            Cancelling...
                                        </>
                                    ) : (
                                        <>
                                            <i className="bi bi-x-circle me-2"></i>
                                            {request.status === 'APPROVED' ? 'Revoke Exception' : 'Cancel Request'}
                                        </>
                                    )}
                                </button>
                            )}
                        </div>
                    </div>
                </div>
            </div>
        </>
    );
};

export default React.memo(ExceptionRequestDetailModal);
