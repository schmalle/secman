import React from 'react';

/**
 * Severity levels supported by the badge component.
 * Feature 019: Account Vulns Severity Breakdown
 * Feature 043: Domain Vulnerabilities View (added LOW)
 */
export type SeverityLevel = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

/**
 * Props for SeverityBadge component
 */
interface SeverityBadgeProps {
  /** Severity level (CRITICAL, HIGH, MEDIUM, or LOW) */
  severity: SeverityLevel;
  /** Count of vulnerabilities at this severity level */
  count: number;
  /** Optional additional CSS classes */
  className?: string;
}

/**
 * Configuration for each severity level
 * Includes Scandinavian design system classes, icon, label, and aria-label for accessibility
 * Uses muted colors with colored text on light backgrounds for a professional look
 */
const severityConfig = {
  CRITICAL: {
    bgClass: 'scand-critical',
    textClass: '',
    icon: 'bi-exclamation-circle',
    label: 'Critical',
    ariaLabel: 'Critical severity vulnerabilities',
  },
  HIGH: {
    bgClass: 'scand-high',
    textClass: '',
    icon: 'bi-arrow-up-circle',
    label: 'High',
    ariaLabel: 'High severity vulnerabilities',
  },
  MEDIUM: {
    bgClass: 'scand-medium',
    textClass: '',
    icon: 'bi-dash-circle',
    label: 'Medium',
    ariaLabel: 'Medium severity vulnerabilities',
  },
  LOW: {
    bgClass: 'scand-low',
    textClass: '',
    icon: 'bi-arrow-down-circle',
    label: 'Low',
    ariaLabel: 'Low severity vulnerabilities',
  },
};

/**
 * Severity badge component for displaying vulnerability counts by severity level.
 *
 * Features:
 * - Color-coded badges (red/orange/blue/gray) using Bootstrap contextual colors
 * - Bootstrap Icons for visual distinction (not just color)
 * - Always displays count even if 0 (per spec requirement)
 * - Accessible labels for screen readers
 * - Supports color-blind users through icons + text + patterns
 *
 * Related to: Feature 019 - Account Vulns Severity Breakdown
 * Feature 043 - Domain Vulnerabilities View (added LOW severity)
 * User Story: US1 (P1) - View Severity Breakdown Per Asset
 *
 * @example
 * ```tsx
 * <SeverityBadge severity="CRITICAL" count={5} />
 * <SeverityBadge severity="HIGH" count={12} />
 * <SeverityBadge severity="MEDIUM" count={3} />
 * <SeverityBadge severity="LOW" count={0} />
 * ```
 */
const SeverityBadge: React.FC<SeverityBadgeProps> = ({ 
  severity, 
  count, 
  className = '' 
}) => {
  const config = severityConfig[severity];

  return (
    <span 
      className={`badge ${config.bgClass} ${config.textClass} ${className}`.trim()}
      title={`${count} ${config.label} severity ${count === 1 ? 'vulnerability' : 'vulnerabilities'}`}
      role="status"
      aria-label={`${count} ${config.ariaLabel}`}
    >
      <i className={`bi ${config.icon} me-1`} aria-hidden="true"></i>
      <span className="visually-hidden">{config.ariaLabel}: </span>
      <span>{config.label}: {count}</span>
    </span>
  );
};

export default SeverityBadge;
