/**
 * OutdatedAssetDetail Component
 *
 * Displays detailed view of a single outdated asset with its vulnerabilities
 * Features:
 * - Asset summary (name, type, overdue count, oldest vulnerability)
 * - Severity breakdown
 * - Paginated vulnerability table
 * - Back navigation
 *
 * Feature: 034-outdated-assets
 * Task: T039-T045
 * User Story: US2 - View Asset Details (P1)
 * Spec reference: spec.md, wireframes/02-asset-detail-view.md
 */

import React, { useState, useEffect } from 'react';
import {
  getOutdatedAssetById,
  getAssetVulnerabilities,
  type OutdatedAsset,
  type Vulnerability,
  type VulnerabilitiesPage
} from '../services/outdatedAssetsApi';
import { formatDistanceToNow } from 'date-fns';
import CveLink from './CveLink';

interface OutdatedAssetDetailProps {
  assetId: number;
}

const OutdatedAssetDetail: React.FC<OutdatedAssetDetailProps> = ({ assetId }) => {
  const [asset, setAsset] = useState<OutdatedAsset | null>(null);
  const [vulnerabilities, setVulnerabilities] = useState<Vulnerability[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [vulnLoading, setVulnLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  // Pagination state
  const [currentPage, setCurrentPage] = useState<number>(0);
  const [pageSize, setPageSize] = useState<number>(20);
  const [totalPages, setTotalPages] = useState<number>(0);
  const [totalElements, setTotalElements] = useState<number>(0);

  // Sorting state
  const [sortField, setSortField] = useState<string>('daysOpen');
  const [sortDirection, setSortDirection] = useState<string>('desc');

  /**
   * Fetch asset details
   */
  const fetchAsset = async () => {
    setLoading(true);
    setError(null);

    try {
      const assetData = await getOutdatedAssetById(assetId);
      setAsset(assetData);
    } catch (err: any) {
      console.error('Failed to fetch asset details:', err);
      if (err.response?.status === 404) {
        setError('Asset not found or you do not have access to view it.');
      } else {
        setError(err.response?.data?.message || 'Failed to load asset details');
      }
    } finally {
      setLoading(false);
    }
  };

  /**
   * Fetch vulnerabilities for the asset
   */
  const fetchVulnerabilities = async () => {
    if (!asset) return;

    setVulnLoading(true);

    try {
      const sort = `${sortField},${sortDirection}`;
      const page: VulnerabilitiesPage = await getAssetVulnerabilities(
        assetId,
        currentPage,
        pageSize,
        sort
      );

      setVulnerabilities(page.content);
      setTotalPages(page.totalPages);
      setTotalElements(page.totalElements);
    } catch (err: any) {
      console.error('Failed to fetch vulnerabilities:', err);
      // Don't set error - just log it, vulnerabilities are secondary
    } finally {
      setVulnLoading(false);
    }
  };

  /**
   * Handle sort column click
   */
  const handleSort = (field: string) => {
    if (field === sortField) {
      setSortDirection(sortDirection === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDirection('desc');
    }
  };

  /**
   * Handle page change
   */
  const handlePageChange = (newPage: number) => {
    setCurrentPage(newPage);
  };

  /**
   * Handle page size change
   */
  const handlePageSizeChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setPageSize(parseInt(e.target.value, 10));
    setCurrentPage(0);
  };

  /**
   * Get severity badge class
   */
  const getSeverityBadgeClass = (severity: string): string => {
    switch (severity) {
      case 'CRITICAL':
        return 'badge bg-danger';
      case 'HIGH':
        return 'badge bg-warning';
      case 'MEDIUM':
        return 'badge bg-info';
      case 'LOW':
        return 'badge bg-light text-dark';
      default:
        return 'badge bg-secondary';
    }
  };

  /**
   * Format date
   */
  const formatDate = (dateStr: string): string => {
    try {
      return new Date(dateStr).toLocaleDateString();
    } catch (err) {
      return 'Unknown';
    }
  };

  /**
   * Format time ago
   */
  const formatTimeAgo = (dateStr: string): string => {
    try {
      return formatDistanceToNow(new Date(dateStr), { addSuffix: true });
    } catch (err) {
      return 'Unknown';
    }
  };

  // Fetch asset on mount
  useEffect(() => {
    fetchAsset();
  }, [assetId]);

  // Fetch vulnerabilities when asset is loaded or pagination/sort changes
  useEffect(() => {
    if (asset) {
      fetchVulnerabilities();
    }
  }, [asset, currentPage, pageSize, sortField, sortDirection]);

  return (
    <div className="container mt-4">
      {/* Back Button */}
      <div className="mb-3">
        <a href="/outdated-assets" className="btn btn-outline-secondary">
          <i className="bi bi-arrow-left me-1"></i>
          Back to Outdated Assets
        </a>
      </div>

      {/* Loading State */}
      {loading && (
        <div className="text-center my-5">
          <div className="spinner-border" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
        </div>
      )}

      {/* Error State */}
      {error && (
        <div className="alert alert-danger" role="alert">
          <i className="bi bi-exclamation-triangle me-2"></i>
          {error}
        </div>
      )}

      {/* Asset Details */}
      {!loading && !error && asset && (
        <>
          {/* Asset Summary Card */}
          <div className="card mb-4">
            <div className="card-header">
              <h2 className="mb-0">
                {asset.assetName}
                <small className="text-muted ms-2">({asset.assetType})</small>
              </h2>
            </div>
            <div className="card-body">
              <div className="row">
                <div className="col-md-3">
                  <h5>Total Overdue</h5>
                  <p className="fs-3 text-danger mb-0">
                    <strong>{asset.totalOverdueCount}</strong>
                  </p>
                  <small className="text-muted">vulnerabilities</small>
                </div>

                <div className="col-md-3">
                  <h5>Oldest Vulnerability</h5>
                  <p className="fs-3 mb-0">
                    <strong>{asset.oldestVulnDays}</strong>
                  </p>
                  <small className="text-muted">days open</small>
                  {asset.oldestVulnId && (
                    <div className="mt-2">
                      <small className="text-muted">{asset.oldestVulnId}</small>
                    </div>
                  )}
                </div>

                <div className="col-md-6">
                  <h5>Severity Breakdown</h5>
                  <div className="d-flex gap-3">
                    {asset.criticalCount > 0 && (
                      <div>
                        <span className={getSeverityBadgeClass('CRITICAL')}>
                          Critical: {asset.criticalCount}
                        </span>
                      </div>
                    )}
                    {asset.highCount > 0 && (
                      <div>
                        <span className={getSeverityBadgeClass('HIGH')}>
                          High: {asset.highCount}
                        </span>
                      </div>
                    )}
                    {asset.mediumCount > 0 && (
                      <div>
                        <span className={getSeverityBadgeClass('MEDIUM')}>
                          Medium: {asset.mediumCount}
                        </span>
                      </div>
                    )}
                    {asset.lowCount > 0 && (
                      <div>
                        <span className={getSeverityBadgeClass('LOW')}>
                          Low: {asset.lowCount}
                        </span>
                      </div>
                    )}
                  </div>
                  <div className="mt-2">
                    <small className="text-muted">
                      Last calculated {formatTimeAgo(asset.lastCalculatedAt)}
                    </small>
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Vulnerabilities Table */}
          <div className="card">
            <div className="card-header">
              <h3 className="mb-0">Overdue Vulnerabilities</h3>
            </div>
            <div className="card-body">
              {vulnLoading && (
                <div className="text-center my-3">
                  <div className="spinner-border spinner-border-sm" role="status">
                    <span className="visually-hidden">Loading...</span>
                  </div>
                </div>
              )}

              {!vulnLoading && vulnerabilities.length === 0 && (
                <div className="alert alert-info" role="alert">
                  No vulnerabilities found.
                </div>
              )}

              {!vulnLoading && vulnerabilities.length > 0 && (
                <>
                  <div className="table-responsive">
                    <table className="table table-striped table-hover">
                      <thead>
                        <tr>
                          <th
                            style={{ cursor: 'pointer' }}
                            onClick={() => handleSort('vulnerabilityId')}
                          >
                            CVE ID{' '}
                            {sortField === 'vulnerabilityId' && (
                              <i className={`bi bi-arrow-${sortDirection === 'asc' ? 'up' : 'down'}`}></i>
                            )}
                          </th>
                          <th
                            style={{ cursor: 'pointer' }}
                            onClick={() => handleSort('cvssSeverity')}
                          >
                            Severity{' '}
                            {sortField === 'cvssSeverity' && (
                              <i className={`bi bi-arrow-${sortDirection === 'asc' ? 'up' : 'down'}`}></i>
                            )}
                          </th>
                          <th
                            style={{ cursor: 'pointer' }}
                            onClick={() => handleSort('cvssscore')}
                          >
                            CVSS Score{' '}
                            {sortField === 'cvssscore' && (
                              <i className={`bi bi-arrow-${sortDirection === 'asc' ? 'up' : 'down'}`}></i>
                            )}
                          </th>
                          <th
                            style={{ cursor: 'pointer' }}
                            onClick={() => handleSort('daysOpen')}
                          >
                            Days Open{' '}
                            {sortField === 'daysOpen' && (
                              <i className={`bi bi-arrow-${sortDirection === 'asc' ? 'up' : 'down'}`}></i>
                            )}
                          </th>
                          <th>Scan Date</th>
                        </tr>
                      </thead>
                      <tbody>
                        {vulnerabilities.map((vuln) => (
                          <tr key={vuln.id}>
                            <td>
                              <CveLink cveId={vuln.vulnerabilityId} />
                              {vuln.vulnerableProductVersions && (
                                <>
                                  <br />
                                  <small className="text-muted">
                                    {vuln.vulnerableProductVersions}
                                  </small>
                                </>
                              )}
                            </td>
                            <td>
                              <span className={getSeverityBadgeClass(vuln.cvssSeverity)}>
                                {vuln.cvssSeverity}
                              </span>
                            </td>
                            <td>{vuln.cvssscore ? vuln.cvssscore.toFixed(1) : 'N/A'}</td>
                            <td>
                              <strong>{vuln.daysOpen}</strong> days
                            </td>
                            <td>{formatDate(vuln.scanTimestamp)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>

                  {/* Pagination */}
                  <div className="d-flex justify-content-between align-items-center mt-3">
                    <div>
                      Showing {currentPage * pageSize + 1} to{' '}
                      {Math.min((currentPage + 1) * pageSize, totalElements)} of {totalElements}{' '}
                      vulnerabilities
                    </div>
                    <div className="d-flex align-items-center gap-2">
                      <label htmlFor="pageSize" className="form-label mb-0">
                        Per page:
                      </label>
                      <select
                        id="pageSize"
                        className="form-select form-select-sm"
                        style={{ width: 'auto' }}
                        value={pageSize}
                        onChange={handlePageSizeChange}
                      >
                        <option value="10">10</option>
                        <option value="20">20</option>
                        <option value="50">50</option>
                        <option value="100">100</option>
                      </select>
                    </div>
                    <nav>
                      <ul className="pagination mb-0">
                        <li className={`page-item ${currentPage === 0 ? 'disabled' : ''}`}>
                          <button
                            className="page-link"
                            onClick={() => handlePageChange(0)}
                            disabled={currentPage === 0}
                          >
                            First
                          </button>
                        </li>
                        <li className={`page-item ${currentPage === 0 ? 'disabled' : ''}`}>
                          <button
                            className="page-link"
                            onClick={() => handlePageChange(currentPage - 1)}
                            disabled={currentPage === 0}
                          >
                            Previous
                          </button>
                        </li>
                        <li className="page-item active">
                          <span className="page-link">
                            {currentPage + 1} of {totalPages}
                          </span>
                        </li>
                        <li className={`page-item ${currentPage >= totalPages - 1 ? 'disabled' : ''}`}>
                          <button
                            className="page-link"
                            onClick={() => handlePageChange(currentPage + 1)}
                            disabled={currentPage >= totalPages - 1}
                          >
                            Next
                          </button>
                        </li>
                        <li className={`page-item ${currentPage >= totalPages - 1 ? 'disabled' : ''}`}>
                          <button
                            className="page-link"
                            onClick={() => handlePageChange(totalPages - 1)}
                            disabled={currentPage >= totalPages - 1}
                          >
                            Last
                          </button>
                        </li>
                      </ul>
                    </nav>
                  </div>
                </>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
};

export default OutdatedAssetDetail;
