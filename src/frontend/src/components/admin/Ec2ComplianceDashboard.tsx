/**
 * EC2 Compliance Dashboard Component
 *
 * Displays summary cards and paginated overview table of asset compliance status.
 * Features: summary cards, search, status filter, pagination, links to detail page.
 *
 * Feature: ec2-vulnerability-tracking
 */

import React, { useState, useEffect } from 'react';
import {
  getComplianceOverview,
  getComplianceSummary,
  triggerRecalculation,
  type AssetComplianceOverview,
  type AssetComplianceSummary,
} from '../../services/assetComplianceApi';

const Ec2ComplianceDashboard: React.FC = () => {
  const [assets, setAssets] = useState<AssetComplianceOverview[]>([]);
  const [summary, setSummary] = useState<AssetComplianceSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Pagination
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize] = useState(20);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Filters
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('');

  // Recalculation
  const [recalculating, setRecalculating] = useState(false);

  const fetchData = async () => {
    setLoading(true);
    setError(null);
    try {
      const [overviewData, summaryData] = await Promise.all([
        getComplianceOverview({
          page: currentPage,
          size: pageSize,
          searchTerm: searchTerm.trim() || undefined,
          statusFilter: statusFilter || undefined,
        }),
        getComplianceSummary(),
      ]);

      setAssets(overviewData.content);
      setTotalPages(overviewData.totalPages);
      setTotalElements(overviewData.totalElements);
      setSummary(summaryData);
    } catch (err: any) {
      setError(err.message || 'Failed to load compliance data');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [currentPage, pageSize, statusFilter]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setCurrentPage(0);
    fetchData();
  };

  const handleRecalculate = async () => {
    if (!confirm('This will calculate initial compliance status for all assets without history. Continue?')) return;
    setRecalculating(true);
    try {
      const result = await triggerRecalculation();
      alert(`Recalculation complete: ${result.assetsProcessed} assets processed.`);
      fetchData();
    } catch (err: any) {
      alert('Recalculation failed: ' + (err.message || 'Unknown error'));
    } finally {
      setRecalculating(false);
    }
  };

  const formatDate = (dateStr: string) => {
    const date = new Date(dateStr);
    return date.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
  };

  const formatDuration = (dateStr: string) => {
    const days = Math.floor((Date.now() - new Date(dateStr).getTime()) / (1000 * 60 * 60 * 24));
    if (days === 0) return 'today';
    if (days === 1) return '1 day';
    return `${days} days`;
  };

  return (
    <div>
      {/* Summary Cards */}
      {summary && (
        <div className="row mb-4">
          <div className="col-md-3 mb-3">
            <div className="card text-center">
              <div className="card-body">
                <h6 className="card-subtitle mb-2 text-muted">Total Assets</h6>
                <h3 className="card-title mb-0">{summary.totalAssets}</h3>
              </div>
            </div>
          </div>
          <div className="col-md-3 mb-3">
            <div className="card text-center border-success">
              <div className="card-body">
                <h6 className="card-subtitle mb-2 text-success">Compliant</h6>
                <h3 className="card-title mb-0 text-success">{summary.compliantCount}</h3>
              </div>
            </div>
          </div>
          <div className="col-md-3 mb-3">
            <div className="card text-center border-danger">
              <div className="card-body">
                <h6 className="card-subtitle mb-2 text-danger">Non-Compliant</h6>
                <h3 className="card-title mb-0 text-danger">{summary.nonCompliantCount}</h3>
              </div>
            </div>
          </div>
          <div className="col-md-3 mb-3">
            <div className="card text-center">
              <div className="card-body">
                <h6 className="card-subtitle mb-2 text-muted">Compliance %</h6>
                <h3 className="card-title mb-0">{summary.compliancePercentage}%</h3>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Filter Bar */}
      <div className="card mb-4">
        <div className="card-body">
          <form onSubmit={handleSearch} className="row g-3 align-items-end">
            <div className="col-md-4">
              <label className="form-label">Search by asset name</label>
              <input
                type="text"
                className="form-control"
                placeholder="Enter asset name..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
              />
            </div>
            <div className="col-md-3">
              <label className="form-label">Status</label>
              <select
                className="form-select"
                value={statusFilter}
                onChange={(e) => { setStatusFilter(e.target.value); setCurrentPage(0); }}
              >
                <option value="">All</option>
                <option value="COMPLIANT">Compliant</option>
                <option value="NON_COMPLIANT">Non-Compliant</option>
              </select>
            </div>
            <div className="col-md-2">
              <button type="submit" className="btn btn-primary w-100">
                <i className="bi bi-search me-1"></i> Search
              </button>
            </div>
            <div className="col-md-3 text-end">
              <button
                type="button"
                className="btn btn-outline-secondary"
                onClick={handleRecalculate}
                disabled={recalculating}
              >
                <i className="bi bi-arrow-clockwise me-1"></i>
                {recalculating ? 'Recalculating...' : 'Recalculate All'}
              </button>
            </div>
          </form>
        </div>
      </div>

      {/* Error */}
      {error && (
        <div className="alert alert-danger">{error}</div>
      )}

      {/* Data Table */}
      <div className="card">
        <div className="card-body">
          {loading ? (
            <div className="text-center py-4">
              <div className="spinner-border text-primary" role="status">
                <span className="visually-hidden">Loading...</span>
              </div>
            </div>
          ) : assets.length === 0 ? (
            <div className="text-center py-4 text-muted">
              No compliance data available. Run a vulnerability import or click "Recalculate All" to seed initial data.
            </div>
          ) : (
            <>
              <div className="table-responsive">
                <table className="table table-hover">
                  <thead>
                    <tr>
                      <th>Asset Name</th>
                      <th>Type</th>
                      <th>Cloud Instance ID</th>
                      <th>Status</th>
                      <th>Since</th>
                      <th>Duration</th>
                      <th>Overdue Count</th>
                      <th>Oldest Vuln (days)</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {assets.map((asset) => (
                      <tr key={asset.assetId}>
                        <td className="fw-medium">{asset.assetName}</td>
                        <td><small className="text-muted">{asset.assetType || '-'}</small></td>
                        <td><small className="text-muted">{asset.cloudInstanceId || '-'}</small></td>
                        <td>
                          <span className={`badge ${asset.currentStatus === 'COMPLIANT' ? 'bg-success' : 'bg-danger'}`}>
                            {asset.currentStatus === 'COMPLIANT' ? 'Compliant' : 'Non-Compliant'}
                          </span>
                        </td>
                        <td>{formatDate(asset.lastChangeAt)}</td>
                        <td>{formatDuration(asset.lastChangeAt)}</td>
                        <td>{asset.overdueCount}</td>
                        <td>{asset.oldestVulnDays ?? '-'}</td>
                        <td>
                          <a
                            href={`/admin/ec2-compliance/${asset.assetId}`}
                            className="btn btn-sm btn-outline-primary"
                          >
                            <i className="bi bi-clock-history me-1"></i> History
                          </a>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Pagination */}
              {totalPages > 1 && (
                <nav className="d-flex justify-content-between align-items-center mt-3">
                  <small className="text-muted">
                    Showing {currentPage * pageSize + 1}-{Math.min((currentPage + 1) * pageSize, totalElements)} of {totalElements}
                  </small>
                  <ul className="pagination pagination-sm mb-0">
                    <li className={`page-item ${currentPage === 0 ? 'disabled' : ''}`}>
                      <button className="page-link" onClick={() => setCurrentPage(currentPage - 1)}>Previous</button>
                    </li>
                    {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => {
                      const pageNum = Math.max(0, Math.min(currentPage - 2, totalPages - 5)) + i;
                      if (pageNum >= totalPages) return null;
                      return (
                        <li key={pageNum} className={`page-item ${pageNum === currentPage ? 'active' : ''}`}>
                          <button className="page-link" onClick={() => setCurrentPage(pageNum)}>{pageNum + 1}</button>
                        </li>
                      );
                    })}
                    <li className={`page-item ${currentPage >= totalPages - 1 ? 'disabled' : ''}`}>
                      <button className="page-link" onClick={() => setCurrentPage(currentPage + 1)}>Next</button>
                    </li>
                  </ul>
                </nav>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default Ec2ComplianceDashboard;
