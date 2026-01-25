/**
 * Overdue Status Badge Component
 *
 * Displays visual indicator for vulnerability overdue status.
 * Shows color-coded badges with optional tooltip details.
 *
 * Status Types:
 * - OK (green): Vulnerability within acceptable age threshold
 * - OVERDUE (red): Vulnerability exceeds threshold with no exception
 * - EXCEPTED (blue): Vulnerability has active exception
 *
 * Features:
 * - Color-coded badges using Bootstrap classes
 * - Emoji icons for quick visual identification
 * - Tooltips with detailed information
 * - Optional detailed display for exception reasons
 * - Accessible (not relying solely on color)
 *
 * Related to: Feature 021-vulnerability-overdue-exception-logic
 */

import React from 'react';

interface OverdueStatusBadgeProps {
    status: 'OK' | 'OVERDUE' | 'EXCEPTED';
    daysOverdue?: number | null;
    ageInDays?: number;
    exceptionReason?: string | null;
    exceptionEndDate?: string | null;
    showDetails?: boolean;
    size?: 'sm' | 'md' | 'lg';
}

const OverdueStatusBadge: React.FC<OverdueStatusBadgeProps> = ({
    status,
    daysOverdue,
    ageInDays,
    exceptionReason,
    exceptionEndDate,
    showDetails = false,
    size = 'md'
}) => {
    const getBadgeConfig = () => {
        switch (status) {
            case 'OVERDUE':
                return {
                    className: 'badge scand-critical',
                    icon: '🔴',
                    text: daysOverdue ? `OVERDUE (${daysOverdue}d)` : 'OVERDUE',
                    tooltip: daysOverdue
                        ? `This vulnerability is ${daysOverdue} days past the configured threshold`
                        : 'This vulnerability exceeds the configured age threshold',
                    ariaLabel: `Overdue status: ${daysOverdue || '?'} days over threshold`
                };
            case 'EXCEPTED':
                const endDate = exceptionEndDate ? new Date(exceptionEndDate).toLocaleDateString() : 'unknown date';
                return {
                    className: 'badge scand-medium',
                    icon: '🛡️',
                    text: 'EXCEPTED',
                    tooltip: exceptionReason
                        ? `Excepted until ${endDate}: ${exceptionReason}`
                        : `This vulnerability has an active exception until ${endDate}`,
                    ariaLabel: `Excepted status: Exception active until ${endDate}`
                };
            case 'OK':
            default:
                return {
                    className: 'badge scand-success',
                    icon: '✅',
                    text: 'OK',
                    tooltip: ageInDays
                        ? `This vulnerability is ${ageInDays} days old, within the acceptable threshold`
                        : 'This vulnerability is within the acceptable age threshold',
                    ariaLabel: `OK status: Within acceptable age threshold`
                };
        }
    };

    const config = getBadgeConfig();
    
    // Size classes
    const sizeClass = {
        'sm': 'badge-sm',
        'md': '',
        'lg': 'badge-lg'
    }[size];

    return (
        <div className="d-inline-block">
            <span
                className={`${config.className} ${sizeClass}`}
                data-bs-toggle="tooltip"
                data-bs-placement="top"
                title={config.tooltip}
                style={{ cursor: 'help', fontWeight: 500 }}
                aria-label={config.ariaLabel}
                role="status"
            >
                <span aria-hidden="true">{config.icon}</span>{' '}
                {config.text}
            </span>
            
            {showDetails && status === 'EXCEPTED' && exceptionReason && (
                <div 
                    className="small text-muted mt-1" 
                    style={{ fontSize: '0.75rem', maxWidth: '300px' }}
                    role="note"
                >
                    <i className="bi bi-info-circle me-1"></i>
                    {exceptionReason}
                    {exceptionEndDate && (
                        <div className="mt-1">
                            <i className="bi bi-calendar-event me-1"></i>
                            Until: {new Date(exceptionEndDate).toLocaleDateString()}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
};

export default OverdueStatusBadge;
