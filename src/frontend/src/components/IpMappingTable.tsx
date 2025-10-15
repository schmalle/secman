import React, { useState } from 'react';
import type { UserMapping } from '../services/userMappingService';

interface IpMappingTableProps {
  mappings: UserMapping[];
  onEdit: (mapping: UserMapping) => void;
  onDelete: (mapping: UserMapping) => void;
  isLoading?: boolean;
}

/**
 * Table component for displaying IP address mappings
 * Feature: 020-i-want-to (IP Address Mapping)
 *
 * Displays:
 * - Email
 * - AWS Account ID
 * - IP Address/Range
 * - IP Range Type (with badge)
 * - IP Count
 * - Domain
 * - Actions (Edit/Delete)
 */
export default function IpMappingTable({ mappings, onEdit, onDelete, isLoading = false }: IpMappingTableProps) {
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
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {mappings.map((mapping) => (
            <tr key={mapping.id}>
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
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
