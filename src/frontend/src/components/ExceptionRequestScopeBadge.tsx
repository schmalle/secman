import React from 'react';
import { formatExceptionRequestScope, type ExceptionRequestScopeTarget } from '../services/exceptionRequestScopeFormatter';

interface ExceptionRequestScopeBadgeProps extends ExceptionRequestScopeTarget {
  className?: string;
}

const ExceptionRequestScopeBadge: React.FC<ExceptionRequestScopeBadgeProps> = ({
  className = '',
  ...target
}) => {
  const display = formatExceptionRequestScope(target);

  return (
    <span className={`badge ${display.badgeClass} ${className}`.trim()} title={display.title}>
      <i className={`bi ${display.iconClass} me-1`}></i>
      {display.label}
    </span>
  );
};

export default ExceptionRequestScopeBadge;
