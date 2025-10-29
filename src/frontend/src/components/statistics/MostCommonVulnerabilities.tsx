/**
 * React component for displaying most common vulnerabilities table
 *
 * Displays top 10 vulnerabilities ranked by occurrence frequency.
 * Features:
 * - Bootstrap table with responsive design
 * - Severity badges with color coding (CRITICAL=red, HIGH=orange, MEDIUM=yellow, LOW=blue, UNKNOWN=gray)
 * - Click handlers for drill-down navigation to vulnerability details
 * - Loading, error, and empty states
 *
 * Feature: 036-vuln-stats-lense
 * Task: T016 [US1]
 * Spec reference: spec.md FR-001, FR-002
 * User Story: US1 - View Most Common Vulnerabilities (P1/MVP)
 */

import React, { useEffect, useState } from 'react';
import { vulnerabilityStatisticsApi, type MostCommonVulnerabilityDto } from '../../services/api/vulnerabilityStatisticsApi';

/**
 * Map severity levels to Bootstrap badge classes
 */
const severityBadgeClass = (severity: string): string => {
  switch (severity.toUpperCase()) {
    case 'CRITICAL':
      return 'badge bg-danger';
    case 'HIGH':
      return 'badge bg-warning text-dark';
    case 'MEDIUM':
      return 'badge bg-info text-dark';
    case 'LOW':
      return 'badge bg-primary';
    case 'UNKNOWN':
    default:
      return 'badge bg-secondary';
  }
};

/**
 * Handle row click - navigate to vulnerability details
 * (Future enhancement: will link to vulnerability detail page)
 */
const handleRowClick = (vulnerabilityId: string) => {
  // TODO: Implement navigation to vulnerability detail page in future story
  console.log(`Navigate to vulnerability details: ${vulnerabilityId}`);
};

export default function MostCommonVulnerabilities() {
  const [data, setData] = useState<MostCommonVulnerabilityDto[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        setError(null);
        const result = await vulnerabilityStatisticsApi.getMostCommonVulnerabilities();
        setData(result);
      } catch (err) {
        console.error('Error fetching most common vulnerabilities:', err);
        setError('Failed to load vulnerability statistics. Please try again later.');
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, []);

  // Loading state
  if (loading) {
    return (
      <div className="card">
        <div className="card-body text-center py-5">
          <div className="spinner-border text-primary" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
          <p className="mt-3 text-muted">Loading vulnerability statistics...</p>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="card">
        <div className="card-body">
          <div className="alert alert-danger" role="alert">
            <i className="bi bi-exclamation-triangle me-2"></i>
            {error}
          </div>
        </div>
      </div>
    );
  }

  // Empty state
  if (data.length === 0) {
    return (
      <div className="card">
        <div className="card-body text-center py-5">
          <i className="bi bi-inbox display-4 text-muted"></i>
          <p className="mt-3 text-muted">No vulnerability data available.</p>
          <small className="text-muted">
            This could mean no vulnerabilities have been imported yet, or you don't have access to any workgroups with vulnerabilities.
          </small>
        </div>
      </div>
    );
  }

  // Data table
  return (
    <div className="card">
      <div className="card-header bg-primary text-white">
        <h5 className="mb-0">
          <i className="bi bi-shield-exclamation me-2"></i>
          Top 10 Most Common Vulnerabilities
        </h5>
      </div>
      <div className="card-body p-0">
        <div className="table-responsive">
          <table className="table table-hover mb-0">
            <thead className="table-light">
              <tr>
                <th scope="col">#</th>
                <th scope="col">CVE ID</th>
                <th scope="col">Severity</th>
                <th scope="col">Total Occurrences</th>
                <th scope="col">Affected Assets</th>
              </tr>
            </thead>
            <tbody>
              {data.map((vuln, index) => (
                <tr
                  key={`${vuln.vulnerabilityId}-${vuln.cvssSeverity}`}
                  onClick={() => handleRowClick(vuln.vulnerabilityId)}
                  style={{ cursor: 'pointer' }}
                  title={`Click to view details for ${vuln.vulnerabilityId}`}
                >
                  <td className="align-middle">{index + 1}</td>
                  <td className="align-middle">
                    <strong>{vuln.vulnerabilityId}</strong>
                  </td>
                  <td className="align-middle">
                    <span className={severityBadgeClass(vuln.cvssSeverity)}>
                      {vuln.cvssSeverity}
                    </span>
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-light text-dark">
                      {vuln.occurrenceCount.toLocaleString()}
                    </span>
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-light text-dark">
                      {vuln.affectedAssetCount.toLocaleString()}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="card-footer text-muted small">
        <i className="bi bi-info-circle me-1"></i>
        Showing top 10 vulnerabilities ranked by total occurrences. Click any row for details.
      </div>
    </div>
  );
}
