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
    vulnerabilityFirstSeenAt: string | null;
    assetId?: number | null;
    assetIp?: string | null;
    assetCloudAccountId?: string | null;
    assetName: string;
    onClose: () => void;
    onSuccess: () => void;
}

// UI-level scope modes — the last one maps to subject=ALL_VULNS + scope=AWS_ACCOUNT
type ScopeMode = 'ASSET' | 'GLOBAL' | 'IP' | 'AWS_ACCOUNT' | 'ALL_VULNS_AWS_ACCOUNT';

interface ScopeModeConfig {
    mode: ScopeMode;
    icon: string;
    title: string;
    description: (cveId: string | null, assetName: string) => string;
    warning?: boolean;
    danger?: boolean;
    needsAwsInput?: boolean;
    needsIpInput?: boolean;
    subject: 'CVE' | 'ALL_VULNS';
    scope: ExceptionScope;
}

const SCOPE_MODES: ReadonlyArray<ScopeModeConfig> = [
    {
        mode: 'ASSET',
        icon: 'bi-shield-check',
        title: 'This asset only',
        description: (_cve, assetName) => `Only "${assetName}" will be affected.`,
        subject: 'CVE',
        scope: 'ASSET',
    },
    {
        mode: 'IP',
        icon: 'bi-hdd-network',
        title: 'Specific IP address',
        description: (cveId) => `${cveId ?? 'This CVE'} on every asset sharing a specific IP.`,
        needsIpInput: true,
        subject: 'CVE',
        scope: 'IP',
    },
    {
        mode: 'AWS_ACCOUNT',
        icon: 'bi-cloud',
        title: 'Specific AWS account (this CVE)',
        description: (cveId) => `${cveId ?? 'This CVE'} on all assets in one AWS account.`,
        needsAwsInput: true,
        subject: 'CVE',
        scope: 'AWS_ACCOUNT',
    },
    {
        mode: 'GLOBAL',
        icon: 'bi-globe2',
        title: 'All assets — system-wide',
        description: (cveId) => `${cveId ?? 'This CVE'} will be masked across every asset in the system.`,
        warning: true,
        subject: 'CVE',
        scope: 'GLOBAL',
    },
    {
        mode: 'ALL_VULNS_AWS_ACCOUNT',
        icon: 'bi-shield-slash',
        title: 'All vulnerabilities in an AWS account',
        description: () => 'Every vulnerability on every asset in one AWS account will be excepted.',
        danger: true,
        needsAwsInput: true,
        subject: 'ALL_VULNS',
        scope: 'AWS_ACCOUNT',
    },
];

const MIN_VULNERABILITY_AGE_DAYS = 5;

function getVulnerabilityAgeDays(firstSeenAt: string | null): number {
    const anchor = firstSeenAt ? new Date(firstSeenAt) : null;
    if (!anchor || isNaN(anchor.getTime())) return Number.MAX_SAFE_INTEGER;
    return Math.floor((Date.now() - anchor.getTime()) / (1000 * 60 * 60 * 24));
}

const ExceptionRequestModal: React.FC<ExceptionRequestModalProps> = ({
    isOpen,
    vulnerabilityId,
    vulnerabilityCveId,
    vulnerabilityFirstSeenAt,
    assetId,
    assetIp,
    assetCloudAccountId,
    assetName,
    onClose,
    onSuccess
}) => {
    const vulnerabilityAgeDays = getVulnerabilityAgeDays(vulnerabilityFirstSeenAt);
    const isTooNew = vulnerabilityAgeDays < MIN_VULNERABILITY_AGE_DAYS;
    const [scopeMode, setScopeMode] = useState<ScopeMode>('ASSET');
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

    const activeModeConfig = SCOPE_MODES.find(m => m.mode === scopeMode)!;
    const scope: ExceptionScope = activeModeConfig.scope;

    // Reset form when modal opens.
    useEffect(() => {
        if (isOpen) {
            setScopeMode('ASSET');
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

    // Load AWS accounts when an AWS scope is chosen.
    useEffect(() => {
        if (!isOpen || !activeModeConfig.needsAwsInput) return;
        let cancelled = false;
        setLoadingAwsAccounts(true);
        getAccessibleAwsAccounts()
            .then(data => { if (!cancelled) setAwsAccounts(data); })
            .catch(err => {
                console.error('Failed to fetch AWS accounts:', err);
            })
            .finally(() => { if (!cancelled) setLoadingAwsAccounts(false); });
        return () => { cancelled = true; };
    }, [isOpen, scopeMode]);

    // When scope mode changes, prefill scopeValue with the asset's IP / cloud account.
    useEffect(() => {
        if (activeModeConfig.needsIpInput) {
            setScopeValue(assetIp ?? '');
        } else if (activeModeConfig.needsAwsInput) {
            setScopeValue(assetCloudAccountId ?? '');
        } else {
            setScopeValue('');
        }
    }, [scopeMode, assetIp, assetCloudAccountId]);

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

    // Determine which scope modes are allowed based on valid combinations from server.
    const allowedModes: ScopeMode[] = combos
        ? SCOPE_MODES
            .filter(m => combos.allowed.some(c => c.subject === m.subject && c.scope === m.scope))
            .map(m => m.mode)
        : SCOPE_MODES.map(m => m.mode);

    const normalizeAwsAccountInput = (input: string): string =>
        input.replace(/\D/g, '').slice(0, 12);

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

        if (scopeMode === 'ASSET' && !assetId) {
            errors.scope = 'No asset is associated with this vulnerability — pick a different scope.';
        }
        if (activeModeConfig.needsIpInput && !scopeValue.trim()) {
            errors.scope = 'IP address is required';
        }
        if (activeModeConfig.needsAwsInput && !scopeValue.trim()) {
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
                subject: activeModeConfig.subject,
                subjectValue: activeModeConfig.subject === 'CVE' ? (vulnerabilityCveId ?? '') : null,
                scope,
                scopeValue: (activeModeConfig.needsIpInput || activeModeConfig.needsAwsInput) ? scopeValue.trim() : null,
                assetId: scopeMode === 'ASSET' ? (assetId ?? null) : null,
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
                                {/* Age guard — vulnerability must be at least 5 days old */}
                                {isTooNew && (
                                    <div className="alert alert-warning d-flex align-items-start" role="alert">
                                        <i className="bi bi-clock-history me-2 mt-1 flex-shrink-0"></i>
                                        <div>
                                            <strong>Exception not available yet.</strong>
                                            {' '}Exception requests can only be submitted once a vulnerability is at least {MIN_VULNERABILITY_AGE_DAYS} days old.
                                            {' '}This vulnerability was first seen {vulnerabilityAgeDays} day{vulnerabilityAgeDays === 1 ? '' : 's'} ago.
                                            {' '}Please try again in {MIN_VULNERABILITY_AGE_DAYS - vulnerabilityAgeDays} more day{MIN_VULNERABILITY_AGE_DAYS - vulnerabilityAgeDays === 1 ? '' : 's'}.
                                        </div>
                                    </div>
                                )}

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

                                {/* Scope card grid */}
                                <div className="mb-3">
                                    <label className="form-label fw-semibold">
                                        Exception Scope <span className="text-danger">*</span>
                                    </label>
                                    <div className="form-text mb-2">
                                        Choose how broadly this exception should apply.
                                    </div>

                                    <div className="row g-2 mb-2">
                                        {SCOPE_MODES.filter(m => allowedModes.includes(m.mode)).map(m => {
                                            const isSelected = scopeMode === m.mode;
                                            const isDanger = m.danger;
                                            const isWarning = m.warning && !isDanger;

                                            let cardBg = isSelected
                                                ? (isDanger ? '#fff5f5' : isWarning ? '#fffbf0' : '#f0f7ff')
                                                : '#fff';
                                            let borderColor = isSelected
                                                ? (isDanger ? '#dc3545' : isWarning ? '#ffc107' : '#0d6efd')
                                                : '#dee2e6';
                                            let iconColor = isDanger ? '#dc3545' : isWarning ? '#856404' : '#0d6efd';
                                            if (!isSelected && (isDanger || isWarning)) iconColor = '#aaa';

                                            const dividerBefore = m.mode === 'GLOBAL' || m.mode === 'ALL_VULNS_AWS_ACCOUNT';

                                            return (
                                                <React.Fragment key={m.mode}>
                                                    {dividerBefore && (
                                                        <div className="col-12">
                                                            <div className="d-flex align-items-center gap-2 mt-1">
                                                                <hr className="flex-grow-1 my-0" />
                                                                <small className={`text-${m.danger ? 'danger' : 'warning'} fw-semibold text-nowrap`}>
                                                                    <i className="bi bi-exclamation-triangle me-1" />
                                                                    Broad scope
                                                                </small>
                                                                <hr className="flex-grow-1 my-0" />
                                                            </div>
                                                        </div>
                                                    )}
                                                    <div className="col-12 col-md-6">
                                                        <button
                                                            type="button"
                                                            className="w-100 text-start p-3 rounded-3 border-0"
                                                            style={{
                                                                background: cardBg,
                                                                border: `2px solid ${borderColor}`,
                                                                outline: isSelected ? `2px solid ${borderColor}` : 'none',
                                                                cursor: loading ? 'default' : 'pointer',
                                                                transition: 'all 0.15s ease',
                                                                boxShadow: isSelected ? `0 0 0 1px ${borderColor}20` : 'none',
                                                            }}
                                                            onClick={() => { if (!loading) setScopeMode(m.mode); }}
                                                            disabled={loading}
                                                            aria-pressed={isSelected}
                                                        >
                                                            <div className="d-flex align-items-start gap-2">
                                                                <i
                                                                    className={`bi ${m.icon} fs-5 flex-shrink-0`}
                                                                    style={{ color: iconColor, marginTop: 1 }}
                                                                />
                                                                <div className="min-w-0">
                                                                    <div className="fw-semibold" style={{ fontSize: '0.875rem', lineHeight: 1.3 }}>
                                                                        {m.title}
                                                                        {isSelected && (
                                                                            <i className="bi bi-check-circle-fill ms-2 text-success" style={{ fontSize: '0.8rem' }} />
                                                                        )}
                                                                    </div>
                                                                    <div className="text-muted" style={{ fontSize: '0.775rem', marginTop: 2 }}>
                                                                        {m.description(vulnerabilityCveId, assetName)}
                                                                    </div>
                                                                </div>
                                                            </div>
                                                        </button>
                                                    </div>
                                                </React.Fragment>
                                            );
                                        })}
                                    </div>

                                    {/* Input area for IP / AWS account scopes */}
                                    {activeModeConfig.needsIpInput && (
                                        <div className="mt-2">
                                            <label className="form-label form-label-sm" htmlFor="scope-ip-input">IP address</label>
                                            <input
                                                id="scope-ip-input"
                                                type="text"
                                                className="form-control form-control-sm"
                                                placeholder="e.g. 10.0.0.1"
                                                value={scopeValue}
                                                onChange={e => setScopeValue(e.target.value)}
                                                disabled={loading}
                                                style={{ maxWidth: 240 }}
                                            />
                                        </div>
                                    )}
                                    {activeModeConfig.needsAwsInput && (
                                        <div className="mt-2">
                                            <label className="form-label form-label-sm" htmlFor="aws-account-request-scope-input">
                                                AWS account
                                                {scopeMode === 'ALL_VULNS_AWS_ACCOUNT' && (
                                                    <span className="badge bg-danger ms-2" style={{ fontSize: '0.7rem' }}>All vulnerabilities</span>
                                                )}
                                            </label>
                                            <div className="d-flex gap-2 flex-wrap align-items-center">
                                                <input
                                                    id="aws-account-request-scope-input"
                                                    type="text"
                                                    className="form-control form-control-sm"
                                                    placeholder="12-digit account ID"
                                                    value={scopeValue}
                                                    onChange={e => setScopeValue(normalizeAwsAccountInput(e.target.value))}
                                                    disabled={loading || loadingAwsAccounts}
                                                    inputMode="numeric"
                                                    pattern="\d{12}"
                                                    maxLength={12}
                                                    list="accessible-aws-account-request-options"
                                                    autoComplete="off"
                                                    aria-label="AWS account number"
                                                    style={{ maxWidth: 200 }}
                                                />
                                                <datalist id="accessible-aws-account-request-options">
                                                    {awsAccounts.map(a => <option key={a} value={a} />)}
                                                </datalist>
                                                {awsAccounts.length > 0 && (
                                                    <select
                                                        className="form-select form-select-sm"
                                                        style={{ width: 'auto', minWidth: 180 }}
                                                        value={scopeValue}
                                                        onChange={e => setScopeValue(e.target.value)}
                                                        disabled={loading || loadingAwsAccounts}
                                                        aria-label="Accessible AWS accounts"
                                                    >
                                                        <option value="">— pick account —</option>
                                                        {awsAccounts.map(a => <option key={a} value={a}>{a}</option>)}
                                                    </select>
                                                )}
                                                {loadingAwsAccounts && (
                                                    <span className="spinner-border spinner-border-sm text-secondary" role="status" aria-label="Loading accounts" />
                                                )}
                                            </div>
                                        </div>
                                    )}

                                    {validationErrors.scope && (
                                        <div className="alert alert-danger py-2 mt-2 mb-0">
                                            <small>{validationErrors.scope}</small>
                                        </div>
                                    )}
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
                                    disabled={loading || isTooNew}
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
