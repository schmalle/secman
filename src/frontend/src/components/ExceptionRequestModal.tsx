/**
 * ExceptionRequestModal Component
 *
 * Modal form for requesting vulnerability exceptions.
 *
 * Feature 196 (two-axis subject × scope):
 *   The modal is anchored to a specific vulnerability and asset, so subject is
 *   pre-filled to CVE with the vulnerability's CVE id. The user picks a scope
 *   (this asset / all assets / specific IP / specific AWS account) using the
 *   same sentence-builder vocabulary as the create-exception form.
 *
 * Legacy SINGLE_VULNERABILITY/CVE_PATTERN scope is gone; the equivalent rows
 * are now CVE × ASSET (this asset only) and CVE × GLOBAL (all assets).
 */

import React, { useState, useEffect } from 'react';
import {
    createRequest,
    type CreateExceptionRequestDto
} from '../services/exceptionRequestService';
import {
    getValidCombinations,
    getAccessibleAwsAccounts,
    type ExceptionScope,
    type ValidCombinationsResponse
} from '../services/vulnerabilityManagementService';
import CveLink from './CveLink';

interface ExceptionRequestModalProps {
    isOpen: boolean;
    vulnerabilityId: number;
    vulnerabilityCveId: string | null;
    assetId?: number | null;
    assetIp?: string | null;
    assetCloudAccountId?: string | null;
    assetName: string;
    onClose: () => void;
    onSuccess: () => void;
}

const SCOPE_OPTIONS: ReadonlyArray<{ value: ExceptionScope; label: string; helperKey: string }> = [
    { value: 'ASSET',       label: 'on this asset',          helperKey: 'asset' },
    { value: 'GLOBAL',      label: 'on all assets',          helperKey: 'global' },
    { value: 'IP',          label: 'on a specific IP',       helperKey: 'ip' },
    { value: 'AWS_ACCOUNT', label: 'in a specific AWS account', helperKey: 'aws' }
];

const ExceptionRequestModal: React.FC<ExceptionRequestModalProps> = ({
    isOpen,
    vulnerabilityId,
    vulnerabilityCveId,
    assetId,
    assetIp,
    assetCloudAccountId,
    assetName,
    onClose,
    onSuccess
}) => {
    // Subject is always CVE in this modal (vulnerability-anchored flow).
    const [scope, setScope] = useState<ExceptionScope>('ASSET');
    const [scopeValue, setScopeValue] = useState<string>('');
    const [reason, setReason] = useState<string>('');
    const [expirationDate, setExpirationDate] = useState<string>('');

    // UI state
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [validationErrors, setValidationErrors] = useState<{ reason?: string; expirationDate?: string; scope?: string }>({});
    const [showLongExpirationWarning, setShowLongExpirationWarning] = useState(false);
    const [longExpirationConfirmed, setLongExpirationConfirmed] = useState(false);
    const [pendingExpirationDate, setPendingExpirationDate] = useState<string | null>(null);

    // Reference data
    const [combos, setCombos] = useState<ValidCombinationsResponse | null>(null);
    const [awsAccounts, setAwsAccounts] = useState<string[]>([]);
    const [loadingAwsAccounts, setLoadingAwsAccounts] = useState(false);

    // Reset form when modal opens.
    useEffect(() => {
        if (isOpen) {
            setScope('ASSET');
            setScopeValue('');
            setReason('');
            setExpirationDate('');
            setError(null);
            setValidationErrors({});
            setLongExpirationConfirmed(false);
            setPendingExpirationDate(null);
            setShowLongExpirationWarning(false);
        }
    }, [isOpen]);

    // Load valid-combinations on open.
    useEffect(() => {
        if (!isOpen) return;
        getValidCombinations()
            .then(setCombos)
            .catch(err => console.error('Failed to fetch valid combinations:', err));
    }, [isOpen]);

    // Load AWS accounts when AWS_ACCOUNT scope chosen.
    useEffect(() => {
        if (!isOpen || scope !== 'AWS_ACCOUNT') return;
        let cancelled = false;
        setLoadingAwsAccounts(true);
        getAccessibleAwsAccounts()
            .then(data => { if (!cancelled) setAwsAccounts(data); })
            .catch(err => {
                console.error('Failed to fetch AWS accounts:', err);
            })
            .finally(() => { if (!cancelled) setLoadingAwsAccounts(false); });
        return () => { cancelled = true; };
    }, [isOpen, scope]);

    // When scope changes, prefill scopeValue with the asset's IP / cloud account
    // when available — so the modal shows a sensible default.
    useEffect(() => {
        if (scope === 'IP') {
            setScopeValue(assetIp ?? '');
        } else if (scope === 'AWS_ACCOUNT') {
            setScopeValue(assetCloudAccountId ?? '');
        } else {
            setScopeValue('');
        }
    }, [scope, assetIp, assetCloudAccountId]);

    // Escape to close.
    useEffect(() => {
        if (!isOpen) return;
        function handleKeyDown(e: KeyboardEvent) {
            if (e.key === 'Escape' && !loading) {
                onClose();
            }
        }
        document.addEventListener('keydown', handleKeyDown);
        return () => document.removeEventListener('keydown', handleKeyDown);
    }, [isOpen, loading, onClose]);

    // Determine which scopes are allowed for subject=CVE.
    const allowedScopes: ExceptionScope[] = combos
        ? combos.allowed
            .filter(c => c.subject === 'CVE')
            .map(c => c.scope)
        : ['GLOBAL', 'IP', 'ASSET', 'AWS_ACCOUNT'];

    function handleExpirationDateChange(e: React.ChangeEvent<HTMLInputElement>) {
        const value = e.target.value;
        if (value && !longExpirationConfirmed) {
            const selectedDate = new Date(value);
            const now = new Date();
            const daysInFuture = Math.floor((selectedDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
            if (daysInFuture > 365) {
                setPendingExpirationDate(value);
                setShowLongExpirationWarning(true);
                return;
            }
        }
        setExpirationDate(value);
        setValidationErrors(prev => ({ ...prev, expirationDate: undefined }));
        setError(null);
    }

    function handleConfirmLongExpiration() {
        if (pendingExpirationDate) {
            setExpirationDate(pendingExpirationDate);
            setLongExpirationConfirmed(true);
        }
        setShowLongExpirationWarning(false);
        setPendingExpirationDate(null);
    }

    function handleRejectLongExpiration() {
        setShowLongExpirationWarning(false);
        setPendingExpirationDate(null);
    }

    function validateForm(): boolean {
        const errors: { reason?: string; expirationDate?: string; scope?: string } = {};

        const reasonLength = reason.trim().length;
        if (reasonLength === 0) {
            errors.reason = 'Reason is required';
        } else if (reasonLength < 50) {
            errors.reason = `Reason must be at least 50 characters (currently ${reasonLength})`;
        } else if (reasonLength > 2048) {
            errors.reason = `Reason must not exceed 2048 characters (currently ${reasonLength})`;
        }

        if (!expirationDate) {
            errors.expirationDate = 'Expiration date is required';
        } else {
            const selectedDate = new Date(expirationDate);
            if (selectedDate <= new Date()) {
                errors.expirationDate = 'Expiration date must be in the future';
            }
        }

        if (scope === 'ASSET' && !assetId) {
            errors.scope = 'No asset is associated with this vulnerability — pick a different scope.';
        }
        if (scope === 'IP' && !scopeValue.trim()) {
            errors.scope = 'IP address is required';
        }
        if (scope === 'AWS_ACCOUNT' && !scopeValue.trim()) {
            errors.scope = 'AWS account is required';
        }

        setValidationErrors(errors);
        return Object.keys(errors).length === 0;
    }

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        if (!validateForm()) return;

        setLoading(true);
        setError(null);

        try {
            const dto: CreateExceptionRequestDto = {
                vulnerabilityId,
                subject: 'CVE',
                subjectValue: vulnerabilityCveId ?? '',
                scope,
                scopeValue: (scope === 'IP' || scope === 'AWS_ACCOUNT') ? scopeValue.trim() : null,
                assetId: scope === 'ASSET' ? (assetId ?? null) : null,
                reason: reason.trim(),
                expirationDate: new Date(expirationDate).toISOString()
            };
            await createRequest(dto);
            onSuccess();
            onClose();
        } catch (err) {
            const errorMessage = err instanceof Error ? err.message : 'Failed to create exception request. Please try again.';
            setError(errorMessage);
        } finally {
            setLoading(false);
        }
    }

    const reasonLength = reason.length;
    const reasonCountColor = reasonLength < 50 ? 'text-danger' : reasonLength > 2048 ? 'text-danger' : 'text-muted';

    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const minDate = tomorrow.toISOString().split('T')[0];

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
                                onClick={onClose}
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
                                            <strong>Vulnerability:</strong> <CveLink cveId={vulnerabilityCveId} />
                                            <br />
                                            <strong>Asset:</strong> {assetName}
                                        </div>
                                    </div>
                                </div>

                                {/* Sentence builder: subject is fixed to CVE; scope is selectable. */}
                                <div className="mb-3">
                                    <label className="form-label">
                                        Exception Scope <span className="text-danger">*</span>
                                    </label>
                                    <div className="form-text mb-2">
                                        Pick where this CVE exception should apply.
                                    </div>
                                    <div className="d-flex align-items-center flex-wrap gap-2 mb-2">
                                        <span className="fs-6 fw-light">Except</span>
                                        <span className="badge bg-info text-dark">CVE {vulnerabilityCveId ?? ''}</span>
                                        <select
                                            className="form-select form-select-sm"
                                            style={{ width: 'auto' }}
                                            value={scope}
                                            onChange={e => setScope(e.target.value as ExceptionScope)}
                                            disabled={loading}
                                            aria-label="Exception scope"
                                        >
                                            {SCOPE_OPTIONS
                                                .filter(opt => allowedScopes.includes(opt.value))
                                                .map(opt => (
                                                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                                                ))}
                                        </select>
                                        {scope === 'IP' && (
                                            <input
                                                type="text"
                                                className="form-control form-control-sm"
                                                style={{ minWidth: 160 }}
                                                placeholder="e.g. 10.0.0.1"
                                                value={scopeValue}
                                                onChange={e => setScopeValue(e.target.value)}
                                                disabled={loading}
                                            />
                                        )}
                                        {scope === 'AWS_ACCOUNT' && (
                                            <select
                                                className="form-select form-select-sm"
                                                style={{ minWidth: 200 }}
                                                value={scopeValue}
                                                onChange={e => setScopeValue(e.target.value)}
                                                disabled={loading || loadingAwsAccounts}
                                            >
                                                <option value="">— pick account —</option>
                                                {awsAccounts.map(a => (
                                                    <option key={a} value={a}>{a}</option>
                                                ))}
                                            </select>
                                        )}
                                    </div>
                                    {validationErrors.scope && (
                                        <div className="alert alert-danger py-2 mb-0">
                                            <small>{validationErrors.scope}</small>
                                        </div>
                                    )}
                                    <div
                                        className={`alert ${scope === 'GLOBAL' ? 'alert-warning' : 'alert-info'} mt-2 mb-0 py-2`}
                                        role="status"
                                    >
                                        {scope === 'ASSET' && (
                                            <>
                                                <i className="bi bi-info-circle me-1" />
                                                <strong>Scoped to one asset:</strong> only rows for <code>{assetName}</code> will be affected.
                                            </>
                                        )}
                                        {scope === 'GLOBAL' && (
                                            <>
                                                <i className="bi bi-exclamation-triangle me-1" />
                                                <strong>Broad scope:</strong> this exception will mask {vulnerabilityCveId || 'this CVE'} across <strong>every asset</strong> in the system.
                                            </>
                                        )}
                                        {scope === 'IP' && (
                                            <>
                                                <i className="bi bi-info-circle me-1" />
                                                <strong>Scoped to one IP:</strong> only assets at that IP will be affected.
                                            </>
                                        )}
                                        {scope === 'AWS_ACCOUNT' && (
                                            <>
                                                <i className="bi bi-info-circle me-1" />
                                                <strong>Scoped to one AWS account:</strong> only assets in that account will be affected.
                                            </>
                                        )}
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
                                        value={reason}
                                        onChange={(e) => {
                                            setReason(e.target.value);
                                            setValidationErrors(prev => ({ ...prev, reason: undefined }));
                                            setError(null);
                                        }}
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
                                        value={expirationDate}
                                        onChange={handleExpirationDateChange}
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
                                    onClick={onClose}
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
