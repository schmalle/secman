/**
 * NoEdrExceptionRequestModal Component
 *
 * Modal form for recording that an asset cannot run an EDR agent ("No EDR possible").
 *
 * Deliberately separate from ExceptionRequestModal rather than a seventh scope mode in it.
 * That modal is anchored to a specific vulnerability (it takes vulnerabilityId and
 * vulnerabilityCveId as required props and its whole sentence builder is CVE-centric), which
 * is the wrong entry point for a statement about a box's hardware or image. Adding a mode
 * there would have produced nonsense copy and forced vulnerabilityId optional throughout.
 *
 * The shape is fixed by construction: kind=NO_EDR, scope=ASSET, subject=ALL_VULNS filler with
 * no subjectValue. There are no subject or scope pickers, so an over-broad request is not
 * expressible from this form at all — the backend enforces the same invariants independently.
 *
 * Approving one of these suppresses NOTHING. Its only effect is to remove the asset from the
 * denominator of the EDR-coverage KPI on the home dashboard.
 */

import React, { useState } from 'react';
import {
    createRequest,
    type CreateExceptionRequestDto
} from '../services/exceptionRequestService';

interface NoEdrExceptionRequestModalProps {
    isOpen: boolean;
    assetId: number;
    assetName: string;
    onClose: () => void;
    onSuccess: () => void;
}

const MIN_REASON_LENGTH = 50;
const MAX_REASON_LENGTH = 2048;

const NoEdrExceptionRequestModal: React.FC<NoEdrExceptionRequestModalProps> = ({
    isOpen,
    assetId,
    assetName,
    onClose,
    onSuccess
}) => {
    const [reason, setReason] = useState('');
    const [expirationDate, setExpirationDate] = useState('');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [validationErrors, setValidationErrors] = useState<{ reason?: string; expirationDate?: string }>({});

    function resetAndClose() {
        setReason('');
        setExpirationDate('');
        setError(null);
        setValidationErrors({});
        onClose();
    }

    // Same reason and expiry rules as ExceptionRequestModal, so a user moving between the two
    // forms does not meet different validation for the same fields.
    function validateForm(): boolean {
        const errors: { reason?: string; expirationDate?: string } = {};

        const reasonLength = reason.trim().length;
        if (reasonLength === 0) {
            errors.reason = 'Reason is required';
        } else if (reasonLength < MIN_REASON_LENGTH) {
            errors.reason = `Reason must be at least ${MIN_REASON_LENGTH} characters (currently ${reasonLength})`;
        } else if (reasonLength > MAX_REASON_LENGTH) {
            errors.reason = `Reason must not exceed ${MAX_REASON_LENGTH} characters (currently ${reasonLength})`;
        }

        if (!expirationDate) {
            errors.expirationDate = 'Expiration date is required';
        } else if (new Date(expirationDate) <= new Date()) {
            errors.expirationDate = 'Expiration date must be in the future';
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
                kind: 'NO_EDR',
                // ALL_VULNS is filler: both enum columns are NOT NULL server-side, and the
                // subject axis is inert for this kind because a NO_EDR exception never
                // matches a finding.
                subject: 'ALL_VULNS',
                scope: 'ASSET',
                subjectValue: null,
                scopeValue: null,
                assetId,
                reason: reason.trim(),
                expirationDate: new Date(expirationDate).toISOString()
            };
            await createRequest(dto);
            onSuccess();
            resetAndClose();
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to create the request. Please try again.');
        } finally {
            setLoading(false);
        }
    }

    const reasonLength = reason.length;
    const reasonCountColor =
        reasonLength < MIN_REASON_LENGTH || reasonLength > MAX_REASON_LENGTH ? 'text-danger' : 'text-muted';

    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const minDate = tomorrow.toISOString().split('T')[0];

    if (!isOpen) {
        return null;
    }

    return (
        <>
            <div className="modal-backdrop fade show" onClick={resetAndClose} style={{ zIndex: 1040 }}></div>

            <div
                className="modal fade show d-block"
                tabIndex={-1}
                role="dialog"
                style={{ zIndex: 1050 }}
                aria-labelledby="noEdrExceptionModalLabel"
                aria-modal="true"
                data-testid="no-edr-exception-modal"
            >
                <div className="modal-dialog modal-dialog-centered">
                    <div className="modal-content">
                        <div className="modal-header">
                            <h5 className="modal-title" id="noEdrExceptionModalLabel">
                                Request &quot;No EDR possible&quot; Exception
                            </h5>
                            <button
                                type="button"
                                className="btn-close"
                                aria-label="Close"
                                onClick={resetAndClose}
                                disabled={loading}
                            ></button>
                        </div>

                        <form onSubmit={handleSubmit}>
                            <div className="modal-body">
                                {error && (
                                    <div className="alert alert-danger" role="alert">
                                        {error}
                                    </div>
                                )}

                                {/* The whole request, stated as one sentence. There is nothing to
                                    choose here — the scope is the named asset, always. */}
                                <div className="alert alert-secondary d-flex align-items-start mb-3">
                                    <i className="bi bi-shield-slash fs-4 me-3 text-secondary" aria-hidden="true"></i>
                                    <div>
                                        <div className="fw-semibold" data-testid="no-edr-exception-sentence">
                                            No EDR possible on <strong>{assetName}</strong>
                                            {expirationDate ? <> until <strong>{expirationDate}</strong></> : null}
                                        </div>
                                        <div className="small text-muted mt-1">
                                            This records that the system cannot run a CrowdStrike sensor. It does
                                            <strong> not</strong> waive any vulnerability — the system&apos;s findings stay
                                            visible and continue to count. It only removes the system from the EDR
                                            coverage metric.
                                        </div>
                                    </div>
                                </div>

                                <div className="mb-3">
                                    <label htmlFor="noEdrReason" className="form-label">
                                        Why can this system not run an EDR agent?{' '}
                                        <span className="text-danger">*</span>
                                    </label>
                                    <textarea
                                        className={`form-control ${validationErrors.reason ? 'is-invalid' : ''}`}
                                        id="noEdrReason"
                                        name="reason"
                                        data-testid="no-edr-exception-reason"
                                        rows={4}
                                        value={reason}
                                        onChange={(e) => {
                                            setReason(e.target.value);
                                            setValidationErrors((prev) => ({ ...prev, reason: undefined }));
                                        }}
                                        disabled={loading}
                                        placeholder="e.g. vendor-locked appliance image; the platform team has confirmed the sensor is unsupported. Compensating controls: ..."
                                    />
                                    {validationErrors.reason && (
                                        <div className="invalid-feedback">{validationErrors.reason}</div>
                                    )}
                                    <div className={`form-text ${reasonCountColor}`}>
                                        {reasonLength} / {MAX_REASON_LENGTH} characters (minimum {MIN_REASON_LENGTH})
                                    </div>
                                </div>

                                <div className="mb-2">
                                    <label htmlFor="noEdrExpirationDate" className="form-label">
                                        Review by <span className="text-danger">*</span>
                                    </label>
                                    <input
                                        type="date"
                                        className={`form-control ${validationErrors.expirationDate ? 'is-invalid' : ''}`}
                                        id="noEdrExpirationDate"
                                        name="expirationDate"
                                        data-testid="no-edr-exception-expiration"
                                        value={expirationDate}
                                        min={minDate}
                                        onChange={(e) => {
                                            setExpirationDate(e.target.value);
                                            setValidationErrors((prev) => ({ ...prev, expirationDate: undefined }));
                                        }}
                                        disabled={loading}
                                    />
                                    {validationErrors.expirationDate && (
                                        <div className="invalid-feedback">{validationErrors.expirationDate}</div>
                                    )}
                                    <div className="form-text">
                                        The exemption lapses on this date and the system re-enters the coverage
                                        metric, so hardware that is later replaced does not stay exempt forever.
                                    </div>
                                </div>
                            </div>

                            <div className="modal-footer">
                                <button
                                    type="button"
                                    className="btn btn-secondary"
                                    onClick={resetAndClose}
                                    disabled={loading}
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    className="btn btn-primary"
                                    disabled={loading}
                                    data-testid="no-edr-exception-submit"
                                >
                                    {loading ? 'Submitting…' : 'Submit request'}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            </div>
        </>
    );
};

export default NoEdrExceptionRequestModal;
