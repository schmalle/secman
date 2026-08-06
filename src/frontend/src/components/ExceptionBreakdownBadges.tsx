import React from 'react';

interface ExceptionBreakdownBadgesProps {
  exceptedCount: number;
  nonExceptedCount: number;
  className?: string;
}

const ExceptionBreakdownBadges: React.FC<ExceptionBreakdownBadgesProps> = ({
  exceptedCount,
  nonExceptedCount,
  className = ''
}) => (
  <div className={`d-flex flex-wrap gap-1 ${className}`.trim()}>
    <span
      className="badge scand-success"
      title={`${exceptedCount} ${exceptedCount === 1 ? 'vulnerability is' : 'vulnerabilities are'} covered by active exceptions`}
      role="status"
      aria-label={`${exceptedCount} excepted vulnerabilities`}
    >
      <i className="bi bi-shield-check me-1" aria-hidden="true"></i>
      Excepted: {exceptedCount}
    </span>
    <span
      className="badge scand-neutral"
      title={`${nonExceptedCount} ${nonExceptedCount === 1 ? 'vulnerability is' : 'vulnerabilities are'} not covered by active exceptions`}
      role="status"
      aria-label={`${nonExceptedCount} not excepted vulnerabilities`}
    >
      <i className="bi bi-shield-slash me-1" aria-hidden="true"></i>
      Not excepted: {nonExceptedCount}
    </span>
  </div>
);

export default ExceptionBreakdownBadges;
