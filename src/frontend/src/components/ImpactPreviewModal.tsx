/**
 * Impact Preview Modal Component
 *
 * Shows a preview of vulnerabilities that would be affected by an exception before creating it
 *
 * Features:
 * - Sample of affected vulnerabilities (first 10)
 * - Total count
 * - Grouping by severity
 * - Confirm/Cancel actions
 *
 * Related to: Feature 021-vulnerability-overdue-exception-logic (Phase 3)
 */

import React from 'react';

interface VulnerabilitySummary {
    id: number;
    vulnerabilityId: string;
    cvssSeverity: string;
    assetName: string;
}

interface ImpactPreviewModalProps {
    show: boolean;
    onClose: () => void;
    onConfirm: () => void;
    exceptionType: string;
    targetValue: string;
    totalCount: number;
    sampleSize: number;
    countBySeverity: Record<string, number>;
    isSubmitting?: boolean;
}

const ImpactPreviewModal: React.FC<ImpactPreviewModalProps> = ({
    show,
    onClose,
    onConfirm,
    exceptionType,
    targetValue,
    totalCount,
    sampleSize,
    countBySeverity,
    isSubmitting = false
}) => {
    if (!show) return null;

    const getSeverityBadgeClass = (severity: string): string => {
        switch (severity?.toUpperCase()) {
            case 'CRITICAL':
                return 'bg-danger';
            case 'HIGH':
                return 'bg-warning text-dark';
            case 'MEDIUM':
                return 'bg-info';
            case 'LOW':
                return 'bg-secondary';
            default:
                return 'bg-light text-dark';
        }
    };

    const getImpactClass = (): string => {
        if (totalCount === 0) return 'text-secondary';
        if (totalCount < 10) return 'text-success';
        if (totalCount < 50) return 'text-warning';
        return 'text-danger';
    };

    return (
        <>
            {/* Backdrop */}
            <div
                className="modal-backdrop fade show"
                style={{ zIndex: 1040 }}
                onClick={onClose}
            ></div>

            {/* Modal */}
            <div
                className="modal fade show d-block"
                tabIndex={-1}
                style={{ zIndex: 1050 }}
                role="dialog"
            >
                <div className="modal-dialog modal-lg modal-dialog-centered modal-dialog-scrollable">
                    <div className="modal-content">
                        {/* Header */}
                        <div className="modal-header">
                            <h5 className="modal-title">
                                <i className="bi bi-eye me-2"></i>
                                Exception Impact Preview
                            </h5>
                            <button
                                type="button"
                                className="btn-close"
                                onClick={onClose}
                                disabled={isSubmitting}
                                aria-label="Close"
                            ></button>
                        </div>

                        {/* Body */}
                        <div className="modal-body">
                            {/* Exception Details */}
                            <div className="alert alert-info mb-4" role="alert">
                                <h6 className="alert-heading">
                                    <i className="bi bi-info-circle me-2"></i>
                                    Creating {exceptionType} Exception
                                </h6>
                                <p className="mb-0">
                                    <strong>Target:</strong> <code>{targetValue}</code>
                                </p>
                            </div>

                            {/* Total Impact */}
                            <div className="card mb-4">
                                <div className="card-body text-center">
                                    <h2 className={`display-4 mb-2 ${getImpactClass()}`}>
                                        {totalCount}
                                    </h2>
                                    <p className="text-muted mb-0">
                                        {totalCount === 1 ? 'vulnerability' : 'vulnerabilities'} will be excepted
                                    </p>
                                </div>
                            </div>

                            {/* Severity Breakdown */}
                            {totalCount > 0 && Object.keys(countBySeverity).length > 0 && (
                                <div className="mb-4">
                                    <h6 className="mb-3">
                                        <i className="bi bi-bar-chart me-2"></i>
                                        Breakdown by Severity
                                    </h6>
                                    <div className="row g-2">
                                        {Object.entries(countBySeverity)
                                            .sort((a, b) => {
                                                const order = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFORMATIONAL'];
                                                return order.indexOf(a[0].toUpperCase()) - order.indexOf(b[0].toUpperCase());
                                            })
                                            .map(([severity, count]) => (
                                                <div key={severity} className="col-6 col-md-4">
                                                    <div className="card">
                                                        <div className="card-body p-3 text-center">
                                                            <span className={`badge ${getSeverityBadgeClass(severity)} mb-2 w-100`}>
                                                                {severity}
                                                            </span>
                                                            <h4 className="mb-0">{count}</h4>
                                                        </div>
                                                    </div>
                                                </div>
                                            ))}
                                    </div>
                                </div>
                            )}

                            {/* Warning if no vulnerabilities */}
                            {totalCount === 0 && (
                                <div className="alert alert-warning" role="alert">
                                    <i className="bi bi-exclamation-triangle me-2"></i>
                                    <strong>Note:</strong> This exception will not affect any current vulnerabilities.
                                    It will only apply to future vulnerabilities matching this criteria.
                                </div>
                            )}

                            {/* Sample Size Info */}
                            {totalCount > sampleSize && (
                                <div className="alert alert-secondary mb-3" role="alert">
                                    <small>
                                        <i className="bi bi-info-circle me-2"></i>
                                        Showing first {sampleSize} of {totalCount} vulnerabilities. All {totalCount} will be excepted.
                                    </small>
                                </div>
                            )}

                            {/* Additional Info */}
                            <div className="card bg-light">
                                <div className="card-body">
                                    <h6 className="card-title">
                                        <i className="bi bi-lightbulb me-2"></i>
                                        What happens next?
                                    </h6>
                                    <ul className="mb-0 small">
                                        <li>These vulnerabilities will be marked as <span className="badge bg-primary">EXCEPTED</span></li>
                                        <li>They will not show as <span className="badge bg-danger">OVERDUE</span> even if they pass the threshold</li>
                                        <li>The exception will remain active until its expiration date</li>
                                        <li>You can modify or delete the exception anytime before it expires</li>
                                    </ul>
                                </div>
                            </div>
                        </div>

                        {/* Footer */}
                        <div className="modal-footer">
                            <button
                                type="button"
                                className="btn btn-secondary"
                                onClick={onClose}
                                disabled={isSubmitting}
                            >
                                <i className="bi bi-x-lg me-2"></i>
                                Cancel
                            </button>
                            <button
                                type="button"
                                className="btn btn-primary"
                                onClick={onConfirm}
                                disabled={isSubmitting}
                            >
                                {isSubmitting ? (
                                    <>
                                        <span className="spinner-border spinner-border-sm me-2" role="status"></span>
                                        Creating...
                                    </>
                                ) : (
                                    <>
                                        <i className="bi bi-check-lg me-2"></i>
                                        Confirm & Create Exception
                                    </>
                                )}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </>
    );
};

export default ImpactPreviewModal;
