/**
 * ExceptionRequestModal Component
 *
 * Modal form for requesting vulnerability exceptions
 *
 * Features:
 * - Scope selection (Single vulnerability or CVE pattern)
 * - Reason validation (50-2048 characters with counter)
 * - Future expiration date selection
 * - Client-side validation
 * - Success/error handling
 * - Loading states
 *
 * Feature: 031-vuln-exception-approval
 * User Story 1: Regular User Requests Exception (P1)
 * Reference: spec.md FR-003, FR-004
 */

import React, { useState, useEffect } from 'react';
import { createRequest, type CreateExceptionRequestDto, type ExceptionScope } from '../services/exceptionRequestService';

interface ExceptionRequestModalProps {
    isOpen: boolean;
    vulnerabilityId: number;
    vulnerabilityCveId: string | null;
    assetName: string;
    onClose: () => void;
    onSuccess: () => void;
}

const ExceptionRequestModal: React.FC<ExceptionRequestModalProps> = ({
    isOpen,
    vulnerabilityId,
    vulnerabilityCveId,
    assetName,
    onClose,
    onSuccess
}) => {
    // Form state
    const [formData, setFormData] = useState<{
        scope: ExceptionScope;
        reason: string;
        expirationDate: string;
    }>({
        scope: 'SINGLE_VULNERABILITY',
        reason: '',
        expirationDate: '',
    });

    // UI state
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [validationErrors, setValidationErrors] = useState<{
        reason?: string;
        expirationDate?: string;
    }>({});
    const [showLongExpirationWarning, setShowLongExpirationWarning] = useState(false);
    const [longExpirationConfirmed, setLongExpirationConfirmed] = useState(false);
    const [pendingExpirationDate, setPendingExpirationDate] = useState<string | null>(null);

    // Reset form when modal opens/closes
    useEffect(() => {
        if (isOpen) {
            setFormData({
                scope: 'SINGLE_VULNERABILITY',
                reason: '',
                expirationDate: '',
            });
            setError(null);
            setValidationErrors({});
            setLongExpirationConfirmed(false);
            setPendingExpirationDate(null);
            setShowLongExpirationWarning(false);
        }
    }, [isOpen]);

    // Handle keyboard navigation (Escape to close)
    useEffect(() => {
        if (!isOpen) return;

        function handleKeyDown(e: KeyboardEvent) {
            if (e.key === 'Escape' && !loading) {
                handleCancel();
            }
        }

        document.addEventListener('keydown', handleKeyDown);
        return () => document.removeEventListener('keydown', handleKeyDown);
    }, [isOpen, loading]);

    // Handle input changes
    function handleChange(e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) {
        const { name, value } = e.target;

        // Special handling for expiration date - check if > 365 days
        if (name === 'expirationDate' && value) {
            const selectedDate = new Date(value);
            const now = new Date();
            const daysInFuture = Math.floor((selectedDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));

            if (daysInFuture > 365 && !longExpirationConfirmed) {
                // Show warning modal
                setPendingExpirationDate(value);
                setShowLongExpirationWarning(true);
                return;
            }
        }

        setFormData((prev) => ({ ...prev, [name]: value }));

        // Clear validation error for this field
        setValidationErrors((prev) => ({ ...prev, [name]: undefined }));
        setError(null);
    }

    // Handle scope radio change
    function handleScopeChange(e: React.ChangeEvent<HTMLInputElement>) {
        setFormData((prev) => ({ ...prev, scope: e.target.value as ExceptionScope }));
    }

    // Handle long expiration warning confirmation
    function handleConfirmLongExpiration() {
        if (pendingExpirationDate) {
            setFormData((prev) => ({ ...prev, expirationDate: pendingExpirationDate }));
            setLongExpirationConfirmed(true);
        }
        setShowLongExpirationWarning(false);
        setPendingExpirationDate(null);
    }

    // Handle long expiration warning rejection
    function handleRejectLongExpiration() {
        setShowLongExpirationWarning(false);
        setPendingExpirationDate(null);
        // Date field remains unchanged (user can select a different date)
    }

    // Validate form
    function validateForm(): boolean {
        const errors: { reason?: string; expirationDate?: string } = {};

        // Validate reason (50-2048 characters)
        const reasonLength = formData.reason.trim().length;
        if (reasonLength === 0) {
            errors.reason = 'Reason is required';
        } else if (reasonLength < 50) {
            errors.reason = `Reason must be at least 50 characters (currently ${reasonLength})`;
        } else if (reasonLength > 2048) {
            errors.reason = `Reason must not exceed 2048 characters (currently ${reasonLength})`;
        }

        // Validate expiration date (must be in future)
        if (!formData.expirationDate) {
            errors.expirationDate = 'Expiration date is required';
        } else {
            const selectedDate = new Date(formData.expirationDate);
            const now = new Date();
            if (selectedDate <= now) {
                errors.expirationDate = 'Expiration date must be in the future';
            }
        }

        setValidationErrors(errors);
        return Object.keys(errors).length === 0;
    }

    // Handle form submission
    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();

        // Validate form
        if (!validateForm()) {
            return;
        }

        setLoading(true);
        setError(null);

        try {
            // Create exception request
            const dto: CreateExceptionRequestDto = {
                vulnerabilityId,
                scope: formData.scope,
                reason: formData.reason.trim(),
                expirationDate: new Date(formData.expirationDate).toISOString(),
            };

            await createRequest(dto);

            // Success - close modal and notify parent
            onSuccess();
            onClose();

        } catch (err) {
            const errorMessage = err instanceof Error ? err.message : 'Failed to create exception request. Please try again.';
            setError(errorMessage);
        } finally {
            setLoading(false);
        }
    }

    // Handle cancel
    function handleCancel() {
        onClose();
    }

    // Get character count for reason field
    const reasonLength = formData.reason.length;
    const reasonCountColor = reasonLength < 50 ? 'text-danger' : reasonLength > 2048 ? 'text-danger' : 'text-muted';

    // Get minimum date for date picker (tomorrow)
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const minDate = tomorrow.toISOString().split('T')[0];

    // Don't render if not open
    if (!isOpen) {
        return null;
    }

    return (
        <>
            {/* Backdrop */}
            <div
                className="modal-backdrop fade show"
                onClick={handleCancel}
                style={{ zIndex: 1040 }}
            ></div>

            {/* Modal */}
            <div
                className="modal fade show d-block"
                tabIndex={-1}
                role="dialog"
                style={{ zIndex: 1050 }}
                aria-labelledby="exceptionRequestModalLabel"
                aria-modal="true"
            >
                <div className="modal-dialog modal-dialog-centered modal-lg">
                    <div className="modal-content">
                        {/* Header */}
                        <div className="modal-header">
                            <h5 className="modal-title" id="exceptionRequestModalLabel">
                                Request Vulnerability Exception
                            </h5>
                            <button
                                type="button"
                                className="btn-close"
                                aria-label="Close"
                                onClick={handleCancel}
                                disabled={loading}
                            ></button>
                        </div>

                        {/* Body */}
                        <form onSubmit={handleSubmit}>
                            <div className="modal-body">
                                {/* Error Alert */}
                                {error && (
                                    <div className="alert alert-danger" role="alert">
                                        {error}
                                    </div>
                                )}

                                {/* Vulnerability Info */}
                                <div className="alert alert-info mb-3">
                                    <div className="d-flex align-items-start">
                                        <i className="bi bi-shield-exclamation me-2 mt-1"></i>
                                        <div>
                                            <strong>Vulnerability:</strong> {vulnerabilityCveId || 'Unknown CVE'}
                                            <br />
                                            <strong>Asset:</strong> {assetName}
                                        </div>
                                    </div>
                                </div>

                                {/* Scope Field */}
                                <div className="mb-3">
                                    <label className="form-label">
                                        Exception Scope <span className="text-danger">*</span>
                                    </label>
                                    <div className="form-text mb-2">
                                        Choose whether to except only this specific vulnerability or all instances of this CVE across all assets.
                                    </div>
                                    <div className="form-check">
                                        <input
                                            className="form-check-input"
                                            type="radio"
                                            name="scope"
                                            id="scopeSingle"
                                            value="SINGLE_VULNERABILITY"
                                            checked={formData.scope === 'SINGLE_VULNERABILITY'}
                                            onChange={handleScopeChange}
                                            disabled={loading}
                                        />
                                        <label className="form-check-label" htmlFor="scopeSingle">
                                            <strong>Single Vulnerability</strong> - Only this specific vulnerability on {assetName}
                                        </label>
                                    </div>
                                    <div className="form-check">
                                        <input
                                            className="form-check-input"
                                            type="radio"
                                            name="scope"
                                            id="scopePattern"
                                            value="CVE_PATTERN"
                                            checked={formData.scope === 'CVE_PATTERN'}
                                            onChange={handleScopeChange}
                                            disabled={loading}
                                        />
                                        <label className="form-check-label" htmlFor="scopePattern">
                                            <strong>CVE Pattern</strong> - All instances of {vulnerabilityCveId || 'this CVE'} across all assets
                                        </label>
                                    </div>
                                </div>

                                {/* Reason Field */}
                                <div className="mb-3">
                                    <label htmlFor="reason" className="form-label">
                                        Reason <span className="text-danger">*</span>
                                    </label>
                                    <textarea
                                        className={`form-control ${validationErrors.reason ? 'is-invalid' : ''}`}
                                        id="reason"
                                        name="reason"
                                        rows={6}
                                        placeholder="Provide a detailed justification for this exception request (minimum 50 characters)..."
                                        value={formData.reason}
                                        onChange={handleChange}
                                        disabled={loading}
                                        autoFocus
                                    ></textarea>
                                    {validationErrors.reason && (
                                        <div className="invalid-feedback d-block">
                                            {validationErrors.reason}
                                        </div>
                                    )}
                                    <div className={`form-text ${reasonCountColor}`}>
                                        {reasonLength} / 2048 characters (minimum 50 required)
                                    </div>
                                </div>

                                {/* Expiration Date Field */}
                                <div className="mb-3">
                                    <label htmlFor="expirationDate" className="form-label">
                                        Expiration Date <span className="text-danger">*</span>
                                    </label>
                                    <input
                                        type="date"
                                        className={`form-control ${validationErrors.expirationDate ? 'is-invalid' : ''}`}
                                        id="expirationDate"
                                        name="expirationDate"
                                        value={formData.expirationDate}
                                        onChange={handleChange}
                                        disabled={loading}
                                        min={minDate}
                                    />
                                    {validationErrors.expirationDate && (
                                        <div className="invalid-feedback d-block">
                                            {validationErrors.expirationDate}
                                        </div>
                                    )}
                                    <div className="form-text">
                                        When should this exception expire? Must be a future date.
                                    </div>
                                </div>

                                {/* Warning Text */}
                                <div className="alert alert-warning mb-0">
                                    <i className="bi bi-exclamation-triangle me-2"></i>
                                    <strong>Note:</strong> Your request will be reviewed by an administrator or security champion.
                                    You will be notified when your request is approved or rejected.
                                </div>
                            </div>

                            {/* Footer */}
                            <div className="modal-footer">
                                <button
                                    type="button"
                                    className="btn btn-secondary"
                                    onClick={handleCancel}
                                    disabled={loading}
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    className="btn btn-primary"
                                    disabled={loading}
                                >
                                    {loading ? (
                                        <>
                                            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                            Submitting...
                                        </>
                                    ) : (
                                        <>
                                            <i className="bi bi-send me-2"></i>
                                            Submit Request
                                        </>
                                    )}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            </div>

            {/* Long Expiration Warning Modal */}
            {showLongExpirationWarning && (
                <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
                    <div className="modal-dialog modal-dialog-centered">
                        <div className="modal-content">
                            <div className="modal-header bg-warning text-dark">
                                <h5 className="modal-title">
                                    <i className="bi bi-exclamation-triangle me-2"></i>
                                    Long Expiration Period
                                </h5>
                            </div>
                            <div className="modal-body">
                                <p className="mb-3">
                                    Exception expiration is more than <strong>365 days</strong> in the future.
                                </p>
                                <p className="mb-3">
                                    Security best practices recommend shorter exception periods with periodic review.
                                </p>
                                <p className="mb-0">
                                    Are you sure you want to continue with this expiration date?
                                </p>
                            </div>
                            <div className="modal-footer">
                                <button
                                    type="button"
                                    className="btn btn-secondary"
                                    onClick={handleRejectLongExpiration}
                                >
                                    No, Change Date
                                </button>
                                <button
                                    type="button"
                                    className="btn btn-warning"
                                    onClick={handleConfirmLongExpiration}
                                >
                                    Yes, Continue
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </>
    );
};

export default React.memo(ExceptionRequestModal);
