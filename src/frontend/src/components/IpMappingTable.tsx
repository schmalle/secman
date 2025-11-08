import React, { useState } from 'react';
import type { UserMapping } from '../services/userMappingService';

interface IpMappingTableProps {
  mappings: UserMapping[];
  onEdit: (mapping: UserMapping) => void;
  onDelete: (mapping: UserMapping) => void;
  isLoading?: boolean;
  isAppliedHistory?: boolean;  // Feature 042: Disable actions for applied history
}

/**
 * Table component for displaying IP address mappings
 * Features: 020-i-want-to (IP Address Mapping), 042-future-user-mappings
 *
 * Displays:
 * - Email
 * - AWS Account ID
 * - IP Address/Range
 * - IP Range Type (with badge)
 * - IP Count
 * - Domain
 * - Status (Feature 042: Future/Active/Applied)
 * - Applied At (Feature 042)
 * - Actions (Edit/Delete, disabled for applied history)
 */
export default function IpMappingTable({ mappings, onEdit, onDelete, isLoading = false, isAppliedHistory = false }: IpMappingTableProps) {
  const [deleteConfirmId, setDeleteConfirmId] = useState<number | null>(null);

  const getRangeTypeBadge = (type?: string) => {
    switch (type) {
      case 'SINGLE':
        return <span className="badge bg-primary">Single IP</span>;
      case 'CIDR':
        return <span className="badge bg-info">CIDR Range</span>;
      case 'DASH_RANGE':
        return <span className="badge bg-warning text-dark">Dash Range</span>;
      default:
        return <span className="badge bg-secondary">N/A</span>;
    }
  };

  const formatIpCount = (count?: number) => {
    if (!count) return '-';
    if (count === 1) return '1 IP';
    if (count < 1000) return `${count} IPs`;
    if (count < 1000000) return `${(count / 1000).toFixed(1)}K IPs`;
    return `${(count / 1000000).toFixed(1)}M IPs`;
  };

  // Feature 042: Status badge based on isFutureMapping and appliedAt
  const getStatusBadge = (mapping: UserMapping) => {
    if (mapping.isFutureMapping) {
      return (
        <span className="badge bg-warning text-dark" title="Waiting for user to be created">
          <i className="bi bi-hourglass-split me-1"></i>
          Future User
        </span>
      );
    } else if (mapping.appliedAt) {
      return (
        <span className="badge bg-success" title="Mapping was applied to user">
          <i className="bi bi-check-circle me-1"></i>
          Applied
        </span>
      );
    } else {
      return (
        <span className="badge bg-primary" title="User exists, mapping is active">
          <i className="bi bi-person-check me-1"></i>
          Active
        </span>
      );
    }
  };

  // Feature 042: Format appliedAt timestamp
  const formatAppliedAt = (timestamp?: string) => {
    if (!timestamp) return '-';
    try {
      const date = new Date(timestamp);
      return date.toLocaleString();
    } catch {
      return timestamp;
    }
  };

  const handleDeleteClick = (mapping: UserMapping) => {
    setDeleteConfirmId(mapping.id);
  };

  const handleDeleteConfirm = (mapping: UserMapping) => {
    onDelete(mapping);
    setDeleteConfirmId(null);
  };

  const handleDeleteCancel = () => {
    setDeleteConfirmId(null);
  };

  if (isLoading) {
    return (
      <div className="text-center py-5">
        <div className="spinner-border text-primary" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
        <p className="mt-2 text-muted">Loading mappings...</p>
      </div>
    );
  }

  if (mappings.length === 0) {
    return (
      <div className="alert alert-info" role="alert">
        <i className="bi bi-info-circle me-2"></i>
        No user mappings found. Create a new mapping to get started.
      </div>
    );
  }

  return (
    <div className="table-responsive">
      <table className="table table-striped table-hover">
        <thead className="table-light">
          <tr>
            <th>Email</th>
            <th>AWS Account</th>
            <th>IP Address/Range</th>
            <th>Type</th>
            <th>IP Count</th>
            <th>Domain</th>
            <th>Status</th>
            {isAppliedHistory && <th>Applied At</th>}
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {mappings.map((mapping) => (
            <tr key={mapping.id} className={mapping.isFutureMapping ? 'table-warning' : ''}>
              <td>
                <small className="text-muted">{mapping.email}</small>
              </td>
              <td>
                {mapping.awsAccountId ? (
                  <code className="small">{mapping.awsAccountId}</code>
                ) : (
                  <span className="text-muted">-</span>
                )}
              </td>
              <td>
                {mapping.ipAddress ? (
                  <code className="small">{mapping.ipAddress}</code>
                ) : (
                  <span className="text-muted">-</span>
                )}
              </td>
              <td>
                {mapping.ipRangeType ? getRangeTypeBadge(mapping.ipRangeType) : '-'}
              </td>
              <td>
                <small className="text-muted">{formatIpCount(mapping.ipCount)}</small>
              </td>
              <td>
                {mapping.domain ? (
                  <small className="text-muted">{mapping.domain}</small>
                ) : (
                  <span className="text-muted">-</span>
                )}
              </td>
              <td>
                {getStatusBadge(mapping)}
              </td>
              {isAppliedHistory && (
                <td>
                  <small className="text-muted">{formatAppliedAt(mapping.appliedAt)}</small>
                </td>
              )}
              <td>
                {isAppliedHistory ? (
                  <span className="text-muted small">
                    <i className="bi bi-lock me-1"></i>
                    Read-only
                  </span>
                ) : (
                  <>
                    {deleteConfirmId === mapping.id ? (
                      <div className="btn-group btn-group-sm" role="group">
                        <button
                          type="button"
                          className="btn btn-danger"
                          onClick={() => handleDeleteConfirm(mapping)}
                        >
                          Confirm
                        </button>
                        <button
                          type="button"
                          className="btn btn-secondary"
                          onClick={handleDeleteCancel}
                        >
                          Cancel
                        </button>
                      </div>
                    ) : (
                      <div className="btn-group btn-group-sm" role="group">
                        <button
                          type="button"
                          className="btn btn-outline-primary"
                          onClick={() => onEdit(mapping)}
                          title="Edit mapping"
                        >
                          <i className="bi bi-pencil"></i>
                        </button>
                        <button
                          type="button"
                          className="btn btn-outline-danger"
                          onClick={() => handleDeleteClick(mapping)}
                          title="Delete mapping"
                        >
                          <i className="bi bi-trash"></i>
                        </button>
                      </div>
                    )}
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
