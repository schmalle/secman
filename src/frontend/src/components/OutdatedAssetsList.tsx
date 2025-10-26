/**
 * OutdatedAssetsList Component
 *
 * Displays paginated table of assets with overdue vulnerabilities
 * Features:
 * - Pagination with page size selection
 * - Sorting by columns
 * - Search by asset name
 * - Severity filter
 * - Last updated timestamp
 * - Refresh button
 *
 * Feature: 034-outdated-assets
 * Task: T027-T028
 * User Story: US1 - View Outdated Assets (P1)
 * Spec reference: spec.md, wireframes/01-outdated-assets-list.md
 */

import React, { useState, useEffect } from 'react';
import {
  getOutdatedAssets,
  getLastRefreshTimestamp,
  triggerRefresh,
  getRefreshStatus,
  type OutdatedAsset,
  type OutdatedAssetsPage,
  type OutdatedAssetsParams,
  type RefreshJob
} from '../services/outdatedAssetsApi';
import { formatDistanceToNow } from 'date-fns';

const OutdatedAssetsList: React.FC = () => {
  const [assets, setAssets] = useState<OutdatedAsset[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [lastRefresh, setLastRefresh] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [refreshJob, setRefreshJob] = useState<RefreshJob | null>(null);
  const [pollIntervalId, setPollIntervalId] = useState<NodeJS.Timeout | null>(null);

  // Pagination state
  const [currentPage, setCurrentPage] = useState<number>(0);
  const [pageSize, setPageSize] = useState<number>(20);
  const [totalPages, setTotalPages] = useState<number>(0);
  const [totalElements, setTotalElements] = useState<number>(0);

  // Filter/search state
  const [searchTerm, setSearchTerm] = useState<string>('');
  const [minSeverity, setMinSeverity] = useState<string>('');
  const [sortField, setSortField] = useState<string>('oldestVulnDays');
  const [sortDirection, setSortDirection] = useState<string>('desc');

  /**
   * Fetch outdated assets from API
   */
  const fetchAssets = async () => {
    setLoading(true);
    setError(null);

    try {
      const params: OutdatedAssetsParams = {
        page: currentPage,
        size: pageSize,
        sort: `${sortField},${sortDirection}`
      };

      if (searchTerm.trim()) {
        params.searchTerm = searchTerm.trim();
      }

      if (minSeverity) {
        params.minSeverity = minSeverity as any;
      }

      const page: OutdatedAssetsPage = await getOutdatedAssets(params);

      setAssets(page.content);
      setTotalPages(page.totalPages);
      setTotalElements(page.totalElements);
      setCurrentPage(page.number);
    } catch (err: any) {
      console.error('Failed to fetch outdated assets:', err);
      setError(err.response?.data?.message || 'Failed to load outdated assets');
    } finally {
      setLoading(false);
    }
  };

  /**
   * Fetch last refresh timestamp
   */
  const fetchLastRefresh = async () => {
    try {
      const timestamp = await getLastRefreshTimestamp();
      setLastRefresh(timestamp);
    } catch (err) {
      console.error('Failed to fetch last refresh timestamp:', err);
    }
  };

  /**
   * Handle manual refresh trigger (async materialized view refresh)
   */
  const handleManualRefresh = async () => {
    try {
      setRefreshing(true);
      setError(null);

      // Trigger async refresh
      const job = await triggerRefresh();
      setRefreshJob(job);

      // Start polling for job status and progress
      pollRefreshStatus();

    } catch (err: any) {
      console.error('Failed to trigger refresh:', err);
      setError(err.response?.data?.message || 'Failed to trigger refresh');
      setRefreshing(false);
    }
  };

  /**
   * Poll refresh status until completion
   */
  const pollRefreshStatus = async () => {
    // Clear any existing poll interval
    if (pollIntervalId) {
      clearInterval(pollIntervalId);
    }

    const interval = setInterval(async () => {
      try {
        const status = await getRefreshStatus();

        if (!status) {
          // No job running - refresh completed or failed
          clearInterval(interval);
          setPollIntervalId(null);
          setRefreshing(false);
          setRefreshJob(null);

          // Reload assets and timestamp
          fetchAssets();
          fetchLastRefresh();
          return;
        }

        setRefreshJob(status);

        if (status.status === 'COMPLETED') {
          clearInterval(interval);
          setPollIntervalId(null);
          setRefreshing(false);

          // Reload assets and timestamp
          fetchAssets();
          fetchLastRefresh();
        } else if (status.status === 'FAILED') {
          clearInterval(interval);
          setPollIntervalId(null);
          setRefreshing(false);
          setError(status.errorMessage || 'Refresh failed');
        }

      } catch (err) {
        console.error('Failed to poll refresh status:', err);
        clearInterval(interval);
        setPollIntervalId(null);
        setRefreshing(false);
      }
    }, 2000); // Poll every 2 seconds

    setPollIntervalId(interval);
  };

  /**
   * Handle refresh button click (simple reload)
   */
  const handleRefresh = () => {
    fetchAssets();
    fetchLastRefresh();
  };

  /**
   * Handle sort column click
   */
  const handleSort = (field: string) => {
    if (field === sortField) {
      // Toggle direction
      setSortDirection(sortDirection === 'asc' ? 'desc' : 'asc');
    } else {
      // New field, default to descending
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
    setCurrentPage(0); // Reset to first page
  };

  /**
   * Handle search
   */
  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setCurrentPage(0); // Reset to first page
    fetchAssets();
  };

  /**
   * Get severity badge class
   */
  const getSeverityBadgeClass = (count: number, severity: string): string => {
    if (count === 0) return 'badge bg-secondary';

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
   * Format time ago
   */
  const formatTimeAgo = (timestamp: string | null): string => {
    if (!timestamp) return 'Never';
    try {
      return formatDistanceToNow(new Date(timestamp), { addSuffix: true });
    } catch (err) {
      return 'Unknown';
    }
  };

  // Fetch data on component mount and when filters change
  useEffect(() => {
    fetchAssets();
  }, [currentPage, pageSize, sortField, sortDirection, minSeverity]);

  // Fetch last refresh timestamp on mount
  useEffect(() => {
    fetchLastRefresh();
  }, []);

  // Cleanup poll interval on unmount
  useEffect(() => {
    return () => {
      if (pollIntervalId) {
        clearInterval(pollIntervalId);
      }
    };
  }, [pollIntervalId]);

  return (
    <div className="container mt-4">
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h1>Outdated Assets</h1>
        <div>
          {lastRefresh && (
            <small className="text-muted me-3">
              Last updated {formatTimeAgo(lastRefresh)}
            </small>
          )}
          <button className="btn btn-outline-secondary me-2" onClick={handleRefresh} disabled={loading || refreshing}>
            <i className="bi bi-arrow-clockwise me-1"></i>
            Reload
          </button>
          <button
            className="btn btn-primary"
            onClick={handleManualRefresh}
            disabled={loading || refreshing}
          >
            {refreshing ? (
              <>
                <span className="spinner-border spinner-border-sm me-1"></span>
                Refreshing...
              </>
            ) : (
              <>
                <i className="bi bi-database me-1"></i>
                Manual Refresh
              </>
            )}
          </button>
        </div>
      </div>

      {/* Refresh Progress Bar */}
      {refreshing && refreshJob && (
        <div className="alert alert-info mb-3">
          <div className="d-flex justify-content-between align-items-center mb-2">
            <span>
              <i className="bi bi-hourglass-split me-2"></i>
              Recalculating outdated assets...
            </span>
            <span className="badge bg-info">
              {refreshJob.assetsProcessed} / {refreshJob.totalAssets}
            </span>
          </div>
          <div className="progress" style={{ height: '20px' }}>
            <div
              className="progress-bar progress-bar-striped progress-bar-animated"
              role="progressbar"
              style={{ width: `${refreshJob.progressPercentage}%` }}
              aria-valuenow={refreshJob.progressPercentage}
              aria-valuemin={0}
              aria-valuemax={100}
            >
              {refreshJob.progressPercentage}%
            </div>
          </div>
        </div>
      )}

      {/* Search and Filters */}
      <div className="card mb-3">
        <div className="card-body">
          <form onSubmit={handleSearch} className="row g-3">
            <div className="col-md-6">
              <label htmlFor="searchTerm" className="form-label">
                Search Asset Name
              </label>
              <input
                type="text"
                className="form-control"
                id="searchTerm"
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                placeholder="Search by asset name..."
              />
            </div>
            <div className="col-md-4">
              <label htmlFor="minSeverity" className="form-label">
                Minimum Severity
              </label>
              <select
                className="form-select"
                id="minSeverity"
                value={minSeverity}
                onChange={(e) => {
                  setMinSeverity(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">All Severities</option>
                <option value="CRITICAL">Critical</option>
                <option value="HIGH">High or Above</option>
                <option value="MEDIUM">Medium or Above</option>
                <option value="LOW">Low or Above</option>
              </select>
            </div>
            <div className="col-md-2 d-flex align-items-end">
              <button type="submit" className="btn btn-secondary w-100" disabled={loading}>
                Search
              </button>
            </div>
          </form>
        </div>
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

      {/* Empty State */}
      {!loading && !error && assets.length === 0 && (
        <div className="alert alert-info" role="alert">
          <i className="bi bi-info-circle me-2"></i>
          No assets currently have overdue vulnerabilities.
        </div>
      )}

      {/* Table */}
      {!loading && !error && assets.length > 0 && (
        <>
          <div className="table-responsive">
            <table className="table table-striped table-hover">
              <thead>
                <tr>
                  <th
                    style={{ cursor: 'pointer' }}
                    onClick={() => handleSort('assetName')}
                  >
                    Asset Name{' '}
                    {sortField === 'assetName' && (
                      <i className={`bi bi-arrow-${sortDirection === 'asc' ? 'up' : 'down'}`}></i>
                    )}
                  </th>
                  <th
                    style={{ cursor: 'pointer' }}
                    onClick={() => handleSort('totalOverdueCount')}
                  >
                    Overdue Count{' '}
                    {sortField === 'totalOverdueCount' && (
                      <i className={`bi bi-arrow-${sortDirection === 'asc' ? 'up' : 'down'}`}></i>
                    )}
                  </th>
                  <th>Severity</th>
                  <th
                    style={{ cursor: 'pointer' }}
                    onClick={() => handleSort('oldestVulnDays')}
                  >
                    Oldest Vuln{' '}
                    {sortField === 'oldestVulnDays' && (
                      <i className={`bi bi-arrow-${sortDirection === 'asc' ? 'up' : 'down'}`}></i>
                    )}
                  </th>
                </tr>
              </thead>
              <tbody>
                {assets.map((asset) => (
                  <tr key={asset.id}>
                    <td>
                      <strong>{asset.assetName}</strong>
                      <br />
                      <small className="text-muted">{asset.assetType}</small>
                    </td>
                    <td>
                      <strong>{asset.totalOverdueCount}</strong>
                    </td>
                    <td>
                      {asset.criticalCount > 0 && (
                        <span className={getSeverityBadgeClass(asset.criticalCount, 'CRITICAL')} title="Critical">
                          C: {asset.criticalCount}
                        </span>
                      )}{' '}
                      {asset.highCount > 0 && (
                        <span className={getSeverityBadgeClass(asset.highCount, 'HIGH')} title="High">
                          H: {asset.highCount}
                        </span>
                      )}{' '}
                      {asset.mediumCount > 0 && (
                        <span className={getSeverityBadgeClass(asset.mediumCount, 'MEDIUM')} title="Medium">
                          M: {asset.mediumCount}
                        </span>
                      )}{' '}
                      {asset.lowCount > 0 && (
                        <span className={getSeverityBadgeClass(asset.lowCount, 'LOW')} title="Low">
                          L: {asset.lowCount}
                        </span>
                      )}
                    </td>
                    <td>
                      {asset.oldestVulnDays} days
                      {asset.oldestVulnId && (
                        <>
                          <br />
                          <small className="text-muted">{asset.oldestVulnId}</small>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div className="d-flex justify-content-between align-items-center mt-3">
            <div>
              Showing {currentPage * pageSize + 1} to{' '}
              {Math.min((currentPage + 1) * pageSize, totalElements)} of {totalElements} results
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
                  <button className="page-link" onClick={() => handlePageChange(0)} disabled={currentPage === 0}>
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
  );
};

export default OutdatedAssetsList;
