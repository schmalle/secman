/**
 * ExceptionStatusBadge Component
 *
 * Displays color-coded status badges for vulnerability exception requests.
 *
 * Feature 196: when caller passes the new `subject`/`scope` props, the badge
 * renders the sentence-fragment vocabulary ("Product · AWS Account 1234")
 * instead of the bare status label. The status-driven rendering is preserved
 * for legacy callers.
 *
 * Status colors and icons:
 * - PENDING: yellow with hourglass
 * - APPROVED: green with shield-check
 * - AUTO-APPROVED: green with checkmark-double
 * - REJECTED: red with x-circle
 * - EXPIRED: gray with clock
 * - CANCELLED: gray with slash-circle
 */

import React from 'react';
import type { ExceptionRequestStatus } from '../services/exceptionRequestService';
import type { ExceptionSubject, ExceptionScope } from '../services/vulnerabilityManagementService';

interface ExceptionStatusBadgeProps {
    status: ExceptionRequestStatus;
    autoApproved?: boolean;
    className?: string;
    /** Optional Feature 196 sentence-fragment props. When provided, the badge
     *  renders "Subject · Scope [scopeValue]" instead of the bare status label. */
    subject?: ExceptionSubject | null;
    scope?: ExceptionScope | null;
    subjectValue?: string | null;
    scopeValue?: string | null;
    assetId?: number | null;
    assetName?: string | null;
}

const SUBJECT_LABELS: Record<ExceptionSubject, string> = {
    ALL_VULNS: 'All vulnerabilities',
    PRODUCT:   'Product',
    CVE:       'CVE'
};

const SCOPE_LABELS: Record<ExceptionScope, string> = {
    GLOBAL:      'Globally',
    IP:          'On IP',
    ASSET:       'On Asset',
    AWS_ACCOUNT: 'In AWS Account'
};

function getBadgeClass(status: ExceptionRequestStatus): string {
    switch (status) {
        case 'PENDING':   return 'scand-pending';
        case 'APPROVED':  return 'scand-success';
        case 'REJECTED':  return 'scand-critical';
        case 'EXPIRED':   return 'scand-neutral';
        case 'CANCELLED': return 'scand-neutral';
        default:          return 'scand-neutral';
    }
}

function getIconClass(status: ExceptionRequestStatus): string {
    switch (status) {
        case 'PENDING':   return 'bi-hourglass-split';
        case 'APPROVED':  return 'bi-shield-check';
        case 'REJECTED':  return 'bi-x-circle';
        case 'EXPIRED':   return 'bi-clock';
        case 'CANCELLED': return 'bi-slash-circle';
        default:          return 'bi-question-circle';
    }
}

function getStatusLabel(status: ExceptionRequestStatus): string {
    switch (status) {
        case 'PENDING':   return 'Pending Exception';
        case 'APPROVED':  return 'Excepted';
        case 'REJECTED':  return 'Rejected';
        case 'EXPIRED':   return 'Expired';
        case 'CANCELLED': return 'Cancelled';
        default:          return status;
    }
}

/**
 * Build the Feature 196 sentence fragment ("Product · AWS Account 1234").
 */
function buildSentenceFragment(
    subject: ExceptionSubject,
    scope: ExceptionScope,
    subjectValue: string | null | undefined,
    scopeValue: string | null | undefined,
    assetId: number | null | undefined,
    assetName: string | null | undefined
): string {
    const subj = SUBJECT_LABELS[subject];
    const scp  = SCOPE_LABELS[scope];
    const target =
        scopeValue
        ?? assetName
        ?? (assetId ? `asset #${assetId}` : '');
    const subjectSuffix = subjectValue ? ` ${subjectValue}` : '';
    return target
        ? `${subj}${subjectSuffix} · ${scp} ${target}`
        : `${subj}${subjectSuffix} · ${scp}`;
}

const ExceptionStatusBadge: React.FC<ExceptionStatusBadgeProps> = ({
    status,
    autoApproved = false,
    className = '',
    subject,
    scope,
    subjectValue,
    scopeValue,
    assetId,
    assetName
}) => {
    const badgeClass = getBadgeClass(status);
    const iconClass = getIconClass(status);

    // Feature 196 mode: caller provided subject + scope, render sentence fragment.
    if (subject && scope) {
        const sentence = buildSentenceFragment(subject, scope, subjectValue, scopeValue, assetId, assetName);
        return (
            <span
                className={`badge ${badgeClass} ${className}`}
                title={`${getStatusLabel(status)} — ${sentence}`}
            >
                <i className={`bi ${iconClass} me-1`}></i>
                {sentence}
            </span>
        );
    }

    // Legacy: auto-approved special case.
    if (status === 'APPROVED' && autoApproved) {
        return (
            <span
                className={`badge scand-success ${className}`}
                title="This request was automatically approved because the requester has ADMIN or SECCHAMPION role"
            >
                <i className="bi bi-check-circle-fill me-1"></i>
                Auto-Approved
            </span>
        );
    }

    // Legacy: status-only rendering.
    return (
        <span className={`badge ${badgeClass} ${className}`}>
            <i className={`bi ${iconClass} me-1`}></i>
            {getStatusLabel(status)}
        </span>
    );
};

export default React.memo(ExceptionStatusBadge);
