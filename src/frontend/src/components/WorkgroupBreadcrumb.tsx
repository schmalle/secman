import React from 'react';
import type { BreadcrumbItem, WorkgroupResponse } from '../services/workgroupApi';

/**
 * Workgroup Breadcrumb Component
 * Feature 040: Nested Workgroups (User Story 5)
 *
 * Displays ancestor path from root to current workgroup with navigation.
 * Shows: Root > Parent > Current Workgroup
 */

interface WorkgroupBreadcrumbProps {
  workgroup: WorkgroupResponse | null;
  onNavigate?: (workgroupId: number) => void;
  showHomeLink?: boolean;
}

const WorkgroupBreadcrumb: React.FC<WorkgroupBreadcrumbProps> = ({
  workgroup,
  onNavigate,
  showHomeLink = true
}) => {
  if (!workgroup) {
    return null;
  }

  const handleNavigate = (workgroupId: number) => {
    if (onNavigate) {
      onNavigate(workgroupId);
    }
  };

  return (
    <nav aria-label="breadcrumb">
      <ol className="breadcrumb mb-0">
        {/* Home/Root link */}
        {showHomeLink && (
          <li className="breadcrumb-item">
            <a
              href="#"
              onClick={(e) => {
                e.preventDefault();
                // Navigate to root view
                if (onNavigate) {
                  handleNavigate(0); // 0 represents root view
                }
              }}
              className="text-decoration-none"
            >
              <i className="bi bi-house-door"></i> Home
            </a>
          </li>
        )}

        {/* Ancestors */}
        {workgroup.ancestors && workgroup.ancestors.length > 0 && (
          workgroup.ancestors.map((ancestor) => (
            <li key={ancestor.id} className="breadcrumb-item">
              <a
                href="#"
                onClick={(e) => {
                  e.preventDefault();
                  handleNavigate(ancestor.id);
                }}
                className="text-decoration-none"
              >
                {ancestor.name}
              </a>
            </li>
          ))
        )}

        {/* Current workgroup */}
        <li className="breadcrumb-item active" aria-current="page">
          <strong>{workgroup.name}</strong>
          <span className="badge bg-secondary ms-2">
            Level {workgroup.depth}
          </span>
        </li>
      </ol>
    </nav>
  );
};

export default WorkgroupBreadcrumb;
