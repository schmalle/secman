/**
 * React component for displaying top assets by vulnerability count
 *
 * Displays top 10 assets ranked by total vulnerability count with severity breakdowns.
 * Features:
 * - Bootstrap table with responsive design
 * - Severity badges for counts
 * - Click handlers for navigation to asset detail pages
 * - Loading, error, and empty states
 *
 * Feature: 036-vuln-stats-lense
 * Task: T040 [US3]
 * Spec reference: spec.md FR-005, FR-006
 * User Story: US3 - View Asset Vulnerability Statistics (P3)
 */

import React, { useEffect, useState } from 'react';
import { vulnerabilityStatisticsApi, type TopAssetByVulnerabilitiesDto } from '../../services/api/vulnerabilityStatisticsApi';

/**
 * Handle row click - navigate to asset detail page
 */
const handleRowClick = (assetId: number) => {
  window.location.href = `/assets/${assetId}`;
};

export default function TopAssetsByVulnerabilities() {
  const [data, setData] = useState<TopAssetByVulnerabilitiesDto[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        setError(null);
        const result = await vulnerabilityStatisticsApi.getTopAssetsByVulnerabilities();
        setData(result);
      } catch (err) {
        console.error('Error fetching top assets by vulnerabilities:', err);
        setError('Failed to load asset statistics. Please try again later.');
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
          <p className="mt-3 text-muted">Loading asset statistics...</p>
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
        <div className="card-header">
          <h5 className="mb-0">
            <i className="bi bi-server me-2"></i>
            Top 10 Assets by Vulnerability Count
          </h5>
        </div>
        <div className="card-body text-center py-5">
          <i className="bi bi-inbox display-4 text-muted"></i>
          <p className="mt-3 text-muted">No asset data available.</p>
        </div>
      </div>
    );
  }

  // Data table
  return (
    <div className="card">
      <div className="card-header">
        <h5 className="mb-0">
          <i className="bi bi-server me-2"></i>
          Top 10 Assets by Vulnerability Count
        </h5>
      </div>
      <div className="card-body p-0">
        <div className="table-responsive">
          <table className="table table-hover table-sm mb-0">
            <thead className="table-light">
              <tr>
                <th scope="col">#</th>
                <th scope="col">Asset Name</th>
                <th scope="col">Type</th>
                <th scope="col">Total</th>
                <th scope="col">Critical</th>
                <th scope="col">High</th>
                <th scope="col">Medium</th>
                <th scope="col">Low</th>
              </tr>
            </thead>
            <tbody>
              {data.map((asset, index) => (
                <tr
                  key={asset.assetId}
                  onClick={() => handleRowClick(asset.assetId)}
                  style={{ cursor: 'pointer' }}
                  title={`Click to view details for ${asset.assetName}`}
                >
                  <td className="align-middle">{index + 1}</td>
                  <td className="align-middle">
                    <strong>{asset.assetName}</strong>
                    {asset.assetIp && <><br /><small className="text-muted">{asset.assetIp}</small></>}
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-secondary">{asset.assetType || 'Unknown'}</span>
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-dark">{asset.totalVulnerabilityCount}</span>
                  </td>
                  <td className="align-middle">
                    {asset.criticalCount > 0 && <span className="badge bg-danger">{asset.criticalCount}</span>}
                  </td>
                  <td className="align-middle">
                    {asset.highCount > 0 && <span className="badge bg-warning text-dark">{asset.highCount}</span>}
                  </td>
                  <td className="align-middle">
                    {asset.mediumCount > 0 && <span className="badge bg-info text-dark">{asset.mediumCount}</span>}
                  </td>
                  <td className="align-middle">
                    {asset.lowCount > 0 && <span className="badge bg-primary">{asset.lowCount}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="card-footer text-muted small">
        <i className="bi bi-info-circle me-1"></i>
        Click any row to view asset details.
      </div>
    </div>
  );
}
