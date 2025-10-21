/**
 * ApprovalDetailModal Component
 *
 * Modal for viewing exception request details and performing approval/rejection actions
 *
 * Features:
 * - Complete request details display
 * - Approve action with optional comment
 * - Reject action with required comment (10-1024 chars)
 * - Confirmation dialogs
 * - Concurrent approval detection (409 handling)
 * - Success/error handling
 *
 * Feature: 031-vuln-exception-approval
 * User Story 3: ADMIN Approval Dashboard (P1)
 * Reference: spec.md FR-021 to FR-023
 */

import React, { useState, useEffect } from 'react';
import { getRequestById, approveRequest, rejectRequest, type VulnerabilityExceptionRequestDto } from '../services/exceptionRequestService';
import ExceptionStatusBadge from './ExceptionStatusBadge';

interface ApprovalDetailModalProps {
  isOpen: boolean;
  requestId: number;
  onClose: () => void;
  onApprove: () => void;
  onReject: () => void;
}

const ApprovalDetailModal: React.FC<ApprovalDetailModalProps> = ({
  isOpen,
  requestId,
  onClose,
  onApprove,
  onReject
}) => {
  const [request, setRequest] = useState<VulnerabilityExceptionRequestDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Action states
  const [approving, setApproving] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [showApproveConfirm, setShowApproveConfirm] = useState(false);
  const [showRejectConfirm, setShowRejectConfirm] = useState(false);

  // Form states
  const [approveComment, setApproveComment] = useState('');
  const [rejectComment, setRejectComment] = useState('');
  const [rejectCommentError, setRejectCommentError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) {
      fetchRequestDetails();
      // Reset states
      setApproveComment('');
      setRejectComment('');
      setRejectCommentError(null);
      setShowApproveConfirm(false);
      setShowRejectConfirm(false);
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

  const handleApproveClick = () => {
    setShowApproveConfirm(true);
  };

  const handleApproveConfirm = async () => {
    try {
      setApproving(true);
      setError(null);

      await approveRequest(requestId, approveComment || undefined);

      // Success - close modal and notify parent
      onApprove();
      onClose();
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to approve request';
      setError(errorMessage);
    } finally {
      setApproving(false);
      setShowApproveConfirm(false);
    }
  };

  const handleRejectClick = () => {
    // Validate reject comment
    const trimmedComment = rejectComment.trim();
    if (trimmedComment.length === 0) {
      setRejectCommentError('Rejection comment is required');
      return;
    }
    if (trimmedComment.length < 10) {
      setRejectCommentError(`Comment must be at least 10 characters (currently ${trimmedComment.length})`);
      return;
    }
    if (trimmedComment.length > 1024) {
      setRejectCommentError(`Comment must not exceed 1024 characters (currently ${trimmedComment.length})`);
      return;
    }

    setRejectCommentError(null);
    setShowRejectConfirm(true);
  };

  const handleRejectConfirm = async () => {
    try {
      setRejecting(true);
      setError(null);

      await rejectRequest(requestId, rejectComment.trim());

      // Success - close modal and notify parent
      onReject();
      onClose();
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to reject request';
      setError(errorMessage);
    } finally {
      setRejecting(false);
      setShowRejectConfirm(false);
    }
  };

  // Handle keyboard navigation (Escape to close)
  useEffect(() => {
    if (!isOpen) return;

    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape' && !approving && !rejecting && !showApproveConfirm && !showRejectConfirm) {
        onClose();
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, approving, rejecting, showApproveConfirm, showRejectConfirm]);

  // Don't render if not open
  if (!isOpen) {
    return null;
  }

  const rejectCommentLength = rejectComment.length;
  const rejectCountColor = rejectCommentLength < 10 ? 'text-danger' : rejectCommentLength > 1024 ? 'text-danger' : 'text-muted';

  return (
    <>
      {/* Backdrop */}
      <div
        className="modal-backdrop fade show"
        onClick={!approving && !rejecting && !showApproveConfirm && !showRejectConfirm ? onClose : undefined}
        style={{ zIndex: 1040 }}
      ></div>

      {/* Modal */}
      <div
        className="modal fade show d-block"
        tabIndex={-1}
        role="dialog"
        style={{ zIndex: 1050 }}
        aria-labelledby="approvalDetailModalLabel"
        aria-modal="true"
      >
        <div className="modal-dialog modal-dialog-centered modal-xl">
          <div className="modal-content">
            {/* Header */}
            <div className="modal-header">
              <h5 className="modal-title" id="approvalDetailModalLabel">
                Review Exception Request
              </h5>
              <button
                type="button"
                className="btn-close"
                aria-label="Close"
                onClick={onClose}
                disabled={approving || rejecting}
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
                    <ExceptionStatusBadge status={request.status} autoApproved={request.autoApproved} className="fs-6" />
                  </div>

                  <div className="row">
                    {/* Left Column - Request Details */}
                    <div className="col-md-6">
                      {/* Vulnerability Information */}
                      <div className="mb-4">
                        <h6 className="text-muted mb-3">Vulnerability Information</h6>
                        {!request.vulnerabilityCveId && !request.vulnerabilityId && (
                          <div className="alert alert-warning mb-3">
                            <i className="bi bi-info-circle me-2"></i>
                            <strong>Vulnerability No Longer Exists</strong>
                            <p className="mb-0 mt-1 small">
                              The vulnerability has been remediated or removed. The request remains for audit purposes.
                            </p>
                          </div>
                        )}
                        <div className="mb-2">
                          <strong>CVE ID:</strong><br />
                          <code>{request.vulnerabilityCveId || 'Unknown'}</code>
                        </div>
                        <div className="mb-2">
                          <strong>Asset:</strong><br />
                          {request.assetName || 'N/A'}
                          {request.assetIp && (
                            <span className="text-muted ms-2">({request.assetIp})</span>
                          )}
                        </div>
                      </div>

                      {/* Request Information */}
                      <div className="mb-4">
                        <h6 className="text-muted mb-3">Request Information</h6>
                        <div className="mb-2">
                          <strong>Scope:</strong><br />
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
                        <div className="mb-2">
                          <strong>Expiration Date:</strong><br />
                          {new Date(request.expirationDate).toLocaleDateString()}
                        </div>
                        <div className="mb-2">
                          <strong>Submitted:</strong><br />
                          {new Date(request.createdAt).toLocaleString()}
                        </div>
                        <div className="mb-2">
                          <strong>Requested By:</strong><br />
                          {request.requestedByUsername}
                          {!request.requestedByUserId && (
                            <span className="badge bg-secondary ms-2" title="User account has been deleted">
                              Account Inactive
                            </span>
                          )}
                        </div>
                      </div>

                      {/* Reason */}
                      <div className="mb-4">
                        <strong>Reason:</strong>
                        <div className="border rounded p-3 mt-2 bg-light" style={{ whiteSpace: 'pre-wrap', maxHeight: '200px', overflowY: 'auto' }}>
                          {request.reason}
                        </div>
                      </div>
                    </div>

                    {/* Right Column - Review Actions */}
                    <div className="col-md-6">
                      {request.status === 'PENDING' && (
                        <>
                          {/* Approve Section */}
                          <div className="card border-success mb-3">
                            <div className="card-header bg-success text-white">
                              <i className="bi bi-check-circle me-2"></i>
                              Approve Request
                            </div>
                            <div className="card-body">
                              <div className="mb-3">
                                <label htmlFor="approveComment" className="form-label">
                                  Approval Comment (Optional)
                                </label>
                                <textarea
                                  className="form-control"
                                  id="approveComment"
                                  rows={4}
                                  placeholder="Add any additional notes about this approval..."
                                  value={approveComment}
                                  onChange={(e) => setApproveComment(e.target.value)}
                                  disabled={approving || rejecting}
                                  maxLength={1024}
                                ></textarea>
                                <div className="form-text text-muted">
                                  {approveComment.length} / 1024 characters
                                </div>
                              </div>
                              {!showApproveConfirm ? (
                                <button
                                  className="btn btn-success w-100"
                                  onClick={handleApproveClick}
                                  disabled={approving || rejecting}
                                >
                                  <i className="bi bi-check-circle me-2"></i>
                                  Approve Exception
                                </button>
                              ) : (
                                <div>
                                  <div className="alert alert-warning mb-2">
                                    <i className="bi bi-exclamation-triangle me-2"></i>
                                    <strong>Confirm Approval:</strong> This will create an active exception for the vulnerability.
                                  </div>
                                  <div className="d-flex gap-2">
                                    <button
                                      className="btn btn-success flex-grow-1"
                                      onClick={handleApproveConfirm}
                                      disabled={approving}
                                    >
                                      {approving ? (
                                        <>
                                          <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                          Approving...
                                        </>
                                      ) : (
                                        'Confirm Approve'
                                      )}
                                    </button>
                                    <button
                                      className="btn btn-secondary"
                                      onClick={() => setShowApproveConfirm(false)}
                                      disabled={approving}
                                    >
                                      Cancel
                                    </button>
                                  </div>
                                </div>
                              )}
                            </div>
                          </div>

                          {/* Reject Section */}
                          <div className="card border-danger">
                            <div className="card-header bg-danger text-white">
                              <i className="bi bi-x-circle me-2"></i>
                              Reject Request
                            </div>
                            <div className="card-body">
                              <div className="mb-3">
                                <label htmlFor="rejectComment" className="form-label">
                                  Rejection Comment <span className="text-danger">*</span>
                                </label>
                                <textarea
                                  className={`form-control ${rejectCommentError ? 'is-invalid' : ''}`}
                                  id="rejectComment"
                                  rows={4}
                                  placeholder="Explain why this request is being rejected (minimum 10 characters)..."
                                  value={rejectComment}
                                  onChange={(e) => {
                                    setRejectComment(e.target.value);
                                    setRejectCommentError(null);
                                  }}
                                  disabled={approving || rejecting}
                                  maxLength={1024}
                                ></textarea>
                                {rejectCommentError && (
                                  <div className="invalid-feedback d-block">
                                    {rejectCommentError}
                                  </div>
                                )}
                                <div className={`form-text ${rejectCountColor}`}>
                                  {rejectCommentLength} / 1024 characters (minimum 10 required)
                                </div>
                              </div>
                              {!showRejectConfirm ? (
                                <button
                                  className="btn btn-danger w-100"
                                  onClick={handleRejectClick}
                                  disabled={approving || rejecting}
                                >
                                  <i className="bi bi-x-circle me-2"></i>
                                  Reject Request
                                </button>
                              ) : (
                                <div>
                                  <div className="alert alert-danger mb-2">
                                    <i className="bi bi-exclamation-triangle me-2"></i>
                                    <strong>Confirm Rejection:</strong> The requester will be notified of your decision.
                                  </div>
                                  <div className="d-flex gap-2">
                                    <button
                                      className="btn btn-danger flex-grow-1"
                                      onClick={handleRejectConfirm}
                                      disabled={rejecting}
                                    >
                                      {rejecting ? (
                                        <>
                                          <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                          Rejecting...
                                        </>
                                      ) : (
                                        'Confirm Reject'
                                      )}
                                    </button>
                                    <button
                                      className="btn btn-secondary"
                                      onClick={() => setShowRejectConfirm(false)}
                                      disabled={rejecting}
                                    >
                                      Cancel
                                    </button>
                                  </div>
                                </div>
                              )}
                            </div>
                          </div>
                        </>
                      )}

                      {/* Already Reviewed Message */}
                      {request.status !== 'PENDING' && (
                        <div className="alert alert-info">
                          <i className="bi bi-info-circle me-2"></i>
                          This request has already been reviewed.
                          <br />
                          <strong>Status:</strong> {request.status}
                          {request.reviewedByUsername && (
                            <>
                              <br />
                              <strong>Reviewed By:</strong> {request.reviewedByUsername}
                              {!request.reviewedByUserId && (
                                <span className="badge bg-secondary ms-2" title="Reviewer account has been deleted">
                                  Account Inactive
                                </span>
                              )}
                            </>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                </>
              )}
            </div>

            {/* Footer */}
            <div className="modal-footer">
              <button
                type="button"
                className="btn btn-secondary"
                onClick={onClose}
                disabled={approving || rejecting}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      </div>
    </>
  );
};

export default React.memo(ApprovalDetailModal);
