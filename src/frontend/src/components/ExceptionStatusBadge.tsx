/**
 * ExceptionStatusBadge Component
 *
 * Displays color-coded status badges for vulnerability exception requests
 *
 * Status colors and icons:
 * - PENDING: Yellow badge with hourglass icon
 * - APPROVED: Green badge with shield-check icon
 * - AUTO-APPROVED: Green badge with checkmark-double icon (when autoApproved=true)
 * - REJECTED: Red badge with x-circle icon
 * - EXPIRED: Gray badge with clock icon
 * - CANCELLED: Gray badge with slash-circle icon
 *
 * Feature: 031-vuln-exception-approval
 * User Story 1: Regular User Requests Exception (P1)
 * User Story 2: ADMIN/SECCHAMPION Auto-Approval (P1)
 * Reference: spec.md FR-008, US2-3
 */

import React from 'react';
import type { ExceptionRequestStatus } from '../services/exceptionRequestService';

interface ExceptionStatusBadgeProps {
    status: ExceptionRequestStatus;
    autoApproved?: boolean;
    className?: string;
}

/**
 * Get badge color class for status using Scandinavian design system
 */
function getBadgeClass(status: ExceptionRequestStatus): string {
    switch (status) {
        case 'PENDING':
            return 'scand-pending';
        case 'APPROVED':
            return 'scand-success';
        case 'REJECTED':
            return 'scand-critical';
        case 'EXPIRED':
            return 'scand-neutral';
        case 'CANCELLED':
            return 'scand-neutral';
        default:
            return 'scand-neutral';
    }
}

/**
 * Get icon class for status
 */
function getIconClass(status: ExceptionRequestStatus): string {
    switch (status) {
        case 'PENDING':
            return 'bi-hourglass-split';
        case 'APPROVED':
            return 'bi-shield-check';
        case 'REJECTED':
            return 'bi-x-circle';
        case 'EXPIRED':
            return 'bi-clock';
        case 'CANCELLED':
            return 'bi-slash-circle';
        default:
            return 'bi-question-circle';
    }
}

/**
 * Get display label for status
 */
function getStatusLabel(status: ExceptionRequestStatus): string {
    switch (status) {
        case 'PENDING':
            return 'Pending Exception';
        case 'APPROVED':
            return 'Excepted';
        case 'REJECTED':
            return 'Rejected';
        case 'EXPIRED':
            return 'Expired';
        case 'CANCELLED':
            return 'Cancelled';
        default:
            return status;
    }
}

const ExceptionStatusBadge: React.FC<ExceptionStatusBadgeProps> = ({ status, autoApproved = false, className = '' }) => {
    // Special rendering for auto-approved requests
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

    // Standard status badge
    const badgeClass = getBadgeClass(status);
    const iconClass = getIconClass(status);
    const label = getStatusLabel(status);

    return (
        <span className={`badge ${badgeClass} ${className}`}>
            <i className={`bi ${iconClass} me-1`}></i>
            {label}
        </span>
    );
};

export default React.memo(ExceptionStatusBadge);
