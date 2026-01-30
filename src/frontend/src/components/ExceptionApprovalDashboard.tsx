/**
 * ExceptionApprovalDashboard Component
 *
 * Dashboard for ADMIN/SECCHAMPION to review and approve/reject exception requests
 *
 * Features:
 * - Summary statistics cards (pending count, approvals, rejections)
 * - Pending requests table sorted by oldest first
 * - View details, approve, and reject actions
 * - Pagination support (20/50/100 items per page)
 * - Empty state handling
 *
 * Feature: 031-vuln-exception-approval
 * User Story 3: ADMIN Approval Dashboard (P1)
 * Reference: spec.md FR-018 to FR-023
 */

import React, { useState, useEffect } from 'react';
import {
  getPendingRequests,
  approveRequest,
  rejectRequest,
  getStatistics,
  exportToExcel,
  type VulnerabilityExceptionRequestDto,
  type PagedResponse,
  type ExceptionStatisticsDto
} from '../services/exceptionRequestService';
import ExceptionStatusBadge from './ExceptionStatusBadge';
import ApprovalDetailModal from './ApprovalDetailModal';
import CveLink from './CveLink';

const ExceptionApprovalDashboard: React.FC = () => {
  // Data states
  const [pendingRequests, setPendingRequests] = useState<PagedResponse<VulnerabilityExceptionRequestDto> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Pagination states
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // Modal states
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [selectedRequestId, setSelectedRequestId] = useState<number | null>(null);

  // Action states
  const [approvingId, setApprovingId] = useState<number | null>(null);
  const [rejectingId, setRejectingId] = useState<number | null>(null);

  // Statistics states
  const [showStatistics, setShowStatistics] = useState(false);
  const [statisticsData, setStatisticsData] = useState<ExceptionStatisticsDto | null>(null);
  const [statisticsDateRange, setStatisticsDateRange] = useState('30days');
  const [statisticsLoading, setStatisticsLoading] = useState(false);
  const [statisticsError, setStatisticsError] = useState<string | null>(null);

  // Export state
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    fetchPendingRequests();
  }, [currentPage, pageSize]);

  useEffect(() => {
    if (showStatistics) {
      fetchStatistics();
    }
  }, [showStatistics, statisticsDateRange]);

  const fetchPendingRequests = async () => {
    try {
      setLoading(true);
      setError(null);

      const data = await getPendingRequests(currentPage, pageSize);
      setPendingRequests(data);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to load pending requests';
      setError(errorMessage);
    } finally {
      setLoading(false);
    }
  };

  const handlePageSizeChange = (size: number) => {
    setPageSize(size);
    setCurrentPage(0); // Reset to first page when page size changes
  };

  const handleViewDetails = (requestId: number) => {
    setSelectedRequestId(requestId);
    setShowDetailModal(true);
  };

  const handleCloseDetailModal = () => {
    setShowDetailModal(false);
    setSelectedRequestId(null);
  };

  const handleQuickApprove = async (request: VulnerabilityExceptionRequestDto) => {
    if (!confirm(`Approve exception request for ${request.vulnerabilityCveId || 'vulnerability'} on ${request.assetName}?`)) {
      return;
    }

    try {
      setApprovingId(request.id);
      await approveRequest(request.id);

      setSuccessMessage(`Exception request #${request.id} approved successfully.`);

      // Auto-dismiss success message after 5 seconds
      setTimeout(() => {
        setSuccessMessage(null);
      }, 5000);

      // Refresh pending list
      fetchPendingRequests();
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to approve request';
      setError(errorMessage);
    } finally {
      setApprovingId(null);
    }
  };

  const handleQuickReject = (request: VulnerabilityExceptionRequestDto) => {
    // Open detail modal for rejection (requires comment)
    handleViewDetails(request.id);
  };

  const handleModalApprove = () => {
    // Refresh data after modal approval
    fetchPendingRequests();
    handleCloseDetailModal();
  };

  const handleModalReject = () => {
    // Refresh data after modal rejection
    fetchPendingRequests();
    handleCloseDetailModal();
  };

  const fetchStatistics = async () => {
    try {
      setStatisticsLoading(true);
      setStatisticsError(null);

      const data = await getStatistics(statisticsDateRange);
      setStatisticsData(data);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to load statistics';
      setStatisticsError(errorMessage);
    } finally {
      setStatisticsLoading(false);
    }
  };

  const handleExportToExcel = async () => {
    try {
      setExporting(true);
      setError(null);

      await exportToExcel({ dateRange: statisticsDateRange });

      setSuccessMessage('Exception requests exported successfully.');
      setTimeout(() => {
        setSuccessMessage(null);
      }, 5000);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to export requests';
      setError(errorMessage);
    } finally {
      setExporting(false);
    }
  };

  // Render loading state
  if (loading && !pendingRequests) {
    return (
      <div className="container-fluid p-4">
        <div className="d-flex justify-content-center p-5">
          <div className="spinner-border" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
        </div>
      </div>
    );
  }

  // Render error state
  if (error && !pendingRequests) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-danger" role="alert">
          <i className="bi bi-exclamation-triangle me-2"></i>
          {error}
        </div>
      </div>
    );
  }

  const pendingCount = pendingRequests?.totalElements || 0;

  return (
    <div className="container-fluid p-4">
      {/* Page Header */}
      <div className="row">
        <div className="col-12">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <h2>
              <i className="bi bi-shield-check me-2"></i>
              Exception Approvals
            </h2>
            <div className="btn-group" role="group">
              <button
                className="btn btn-outline-success"
                onClick={handleExportToExcel}
                disabled={exporting}
                title="Export to Excel"
              >
                {exporting ? (
                  <>
                    <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                    Exporting...
                  </>
                ) : (
                  <>
                    <i className="bi bi-file-earmark-excel me-2"></i>
                    Export to Excel
                  </>
                )}
              </button>
              <button
                className="btn btn-outline-primary"
                onClick={fetchPendingRequests}
                disabled={loading}
              >
                <i className="bi bi-arrow-clockwise me-2"></i>
                Refresh
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Success Message */}
      {successMessage && (
        <div className="row mb-3">
          <div className="col-12">
            <div className="alert alert-success alert-dismissible fade show" role="alert">
              <i className="bi bi-check-circle me-2"></i>
              {successMessage}
              <button
                type="button"
                className="btn-close"
                onClick={() => setSuccessMessage(null)}
                aria-label="Close"
              ></button>
            </div>
          </div>
        </div>
      )}

      {/* Error Message */}
      {error && pendingRequests && (
        <div className="row mb-3">
          <div className="col-12">
            <div className="alert alert-warning alert-dismissible fade show" role="alert">
              <i className="bi bi-exclamation-triangle me-2"></i>
              {error}
              <button
                type="button"
                className="btn-close"
                onClick={() => setError(null)}
                aria-label="Close"
              ></button>
            </div>
          </div>
        </div>
      )}

      {/* Summary Cards */}
      <div className="row mb-4">
        <div className="col-md-4">
          <div className="card border-danger">
            <div className="card-body">
              <h6 className="card-subtitle mb-2 text-danger">Pending Requests</h6>
              <h3 className="card-title mb-0 text-danger">
                {pendingCount}
                {pendingCount > 0 && (
                  <span className="badge bg-danger ms-2">{pendingCount}</span>
                )}
              </h3>
              <p className="text-muted small mb-0 mt-2">
                Awaiting review
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* Controls */}
      <div className="row mb-3">
        <div className="col-md-4">
          <label htmlFor="pageSize" className="form-label">Items per Page</label>
          <select
            id="pageSize"
            className="form-select"
            value={pageSize}
            onChange={(e) => handlePageSizeChange(Number(e.target.value))}
          >
            <option value="20">20</option>
            <option value="50">50</option>
            <option value="100">100</option>
          </select>
        </div>
      </div>

      {/* Pending Requests Table */}
      <div className="row">
        <div className="col-12">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">
                Pending Requests
                {pendingCount > 0 && (
                  <span className="badge bg-danger ms-2">{pendingCount}</span>
                )}
              </h5>

              {pendingRequests && pendingRequests.content.length === 0 ? (
                <div className="text-center py-5">
                  <i className="bi bi-check-circle display-1 text-success"></i>
                  <p className="text-muted mt-3">
                    No pending requests. Great job staying on top of security governance!
                  </p>
                </div>
              ) : (
                <>
                  <div className="table-responsive">
                    <table className="table table-striped table-hover">
                      <thead>
                        <tr>
                          <th>CVE ID</th>
                          <th>Asset</th>
                          <th>Requested By</th>
                          <th>Scope</th>
                          <th>Reason</th>
                          <th>Submitted</th>
                          <th>Days Pending</th>
                          <th>Actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {pendingRequests?.content.map((request) => {
                          const submittedDate = new Date(request.createdAt);
                          const now = new Date();
                          const daysPending = Math.floor((now.getTime() - submittedDate.getTime()) / (1000 * 60 * 60 * 24));

                          return (
                            <tr key={request.id}>
                              <td>
                                <CveLink cveId={request.vulnerabilityCveId} />
                              </td>
                              <td>{request.assetName}</td>
                              <td>{request.requestedByUsername}</td>
                              <td>
                                {request.scope === 'SINGLE_VULNERABILITY' ? (
                                  <span className="badge bg-info text-dark">
                                    <i className="bi bi-bullseye me-1"></i>
                                    Single
                                  </span>
                                ) : (
                                  <span className="badge bg-primary">
                                    <i className="bi bi-grid-3x3 me-1"></i>
                                    Pattern
                                  </span>
                                )}
                              </td>
                              <td style={{ maxWidth: '300px' }}>
                                <div
                                  style={{
                                    overflow: 'hidden',
                                    textOverflow: 'ellipsis',
                                    whiteSpace: 'nowrap'
                                  }}
                                  title={request.reason}
                                >
                                  {request.reason.substring(0, 100)}
                                  {request.reason.length > 100 && '...'}
                                </div>
                              </td>
                              <td>
                                {submittedDate.toLocaleDateString()}
                              </td>
                              <td>
                                <span className={`badge ${daysPending > 7 ? 'bg-danger' : daysPending > 3 ? 'bg-warning text-dark' : 'bg-secondary'}`}>
                                  {daysPending} days
                                </span>
                              </td>
                              <td>
                                <div className="btn-group btn-group-sm" role="group">
                                  <button
                                    className="btn btn-outline-secondary"
                                    onClick={() => handleViewDetails(request.id)}
                                    title="View details"
                                  >
                                    <i className="bi bi-eye"></i>
                                  </button>
                                  <button
                                    className="btn btn-outline-success"
                                    onClick={() => handleQuickApprove(request)}
                                    disabled={approvingId === request.id}
                                    title="Quick approve"
                                  >
                                    {approvingId === request.id ? (
                                      <span className="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
                                    ) : (
                                      <i className="bi bi-check-circle"></i>
                                    )}
                                  </button>
                                  <button
                                    className="btn btn-outline-danger"
                                    onClick={() => handleQuickReject(request)}
                                    title="Reject (requires comment)"
                                  >
                                    <i className="bi bi-x-circle"></i>
                                  </button>
                                </div>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>

                  {/* Pagination */}
                  {pendingRequests && pendingRequests.totalPages > 1 && (
                    <div className="d-flex justify-content-between align-items-center mt-3">
                      <div className="text-muted">
                        Showing {currentPage * pageSize + 1} to {Math.min((currentPage + 1) * pageSize, pendingRequests.totalElements)} of {pendingRequests.totalElements} requests
                      </div>
                      <nav aria-label="Request pagination">
                        <ul className="pagination mb-0">
                          <li className={`page-item ${currentPage === 0 ? 'disabled' : ''}`}>
                            <button
                              className="page-link"
                              onClick={() => setCurrentPage(currentPage - 1)}
                              disabled={currentPage === 0}
                              aria-label="Previous"
                            >
                              <span aria-hidden="true">&laquo;</span>
                            </button>
                          </li>
                          {Array.from({ length: pendingRequests.totalPages }, (_, i) => i).map((page) => (
                            <li key={page} className={`page-item ${currentPage === page ? 'active' : ''}`}>
                              <button
                                className="page-link"
                                onClick={() => setCurrentPage(page)}
                              >
                                {page + 1}
                              </button>
                            </li>
                          ))}
                          <li className={`page-item ${currentPage === pendingRequests.totalPages - 1 ? 'disabled' : ''}`}>
                            <button
                              className="page-link"
                              onClick={() => setCurrentPage(currentPage + 1)}
                              disabled={currentPage === pendingRequests.totalPages - 1}
                              aria-label="Next"
                            >
                              <span aria-hidden="true">&raquo;</span>
                            </button>
                          </li>
                        </ul>
                      </nav>
                      <div className="text-muted">
                        Page {currentPage + 1} of {pendingRequests.totalPages}
                      </div>
                    </div>
                  )}
                </>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Statistics Section */}
      <div className="row mt-4">
        <div className="col-12">
          <div className="card">
            <div className="card-header" style={{ cursor: 'pointer' }} onClick={() => setShowStatistics(!showStatistics)}>
              <div className="d-flex justify-content-between align-items-center">
                <h5 className="mb-0">
                  <i className="bi bi-bar-chart me-2"></i>
                  Analytics & Reporting
                </h5>
                <i className={`bi ${showStatistics ? 'bi-chevron-up' : 'bi-chevron-down'}`}></i>
              </div>
            </div>

            {showStatistics && (
              <div className="card-body">
                {/* Date Range Selector */}
                <div className="row mb-4">
                  <div className="col-md-4">
                    <label htmlFor="statsDateRange" className="form-label">Time Period</label>
                    <select
                      id="statsDateRange"
                      className="form-select"
                      value={statisticsDateRange}
                      onChange={(e) => setStatisticsDateRange(e.target.value)}
                    >
                      <option value="7days">Last 7 Days</option>
                      <option value="30days">Last 30 Days</option>
                      <option value="90days">Last 90 Days</option>
                      <option value="alltime">All Time</option>
                    </select>
                  </div>
                </div>

                {/* Statistics Error */}
                {statisticsError && (
                  <div className="alert alert-warning" role="alert">
                    <i className="bi bi-exclamation-triangle me-2"></i>
                    {statisticsError}
                  </div>
                )}

                {/* Statistics Loading */}
                {statisticsLoading ? (
                  <div className="d-flex justify-content-center p-5">
                    <div className="spinner-border" role="status">
                      <span className="visually-hidden">Loading statistics...</span>
                    </div>
                  </div>
                ) : statisticsData ? (
                  <>
                    {/* Metrics Cards */}
                    <div className="row mb-4">
                      {/* Total Requests */}
                      <div className="col-md-3">
                        <div className="card border-primary">
                          <div className="card-body">
                            <h6 className="card-subtitle mb-2 text-primary">Total Requests</h6>
                            <h3 className="card-title mb-0 text-primary">{statisticsData.totalRequests}</h3>
                          </div>
                        </div>
                      </div>

                      {/* Approval Rate */}
                      <div className="col-md-3">
                        <div className="card border-success">
                          <div className="card-body">
                            <h6 className="card-subtitle mb-2 text-success">Approval Rate</h6>
                            <h3 className="card-title mb-0 text-success">
                              {statisticsData.approvalRatePercent !== null
                                ? `${statisticsData.approvalRatePercent.toFixed(1)}%`
                                : 'N/A'}
                            </h3>
                          </div>
                        </div>
                      </div>

                      {/* Average Approval Time */}
                      <div className="col-md-3">
                        <div className="card border-info">
                          <div className="card-body">
                            <h6 className="card-subtitle mb-2 text-info">Avg Approval Time</h6>
                            <h3 className="card-title mb-0 text-info">
                              {statisticsData.averageApprovalTimeHours !== null
                                ? `${statisticsData.averageApprovalTimeHours.toFixed(1)}h`
                                : 'N/A'}
                            </h3>
                          </div>
                        </div>
                      </div>

                      {/* Requests by Status */}
                      <div className="col-md-3">
                        <div className="card border-secondary">
                          <div className="card-body">
                            <h6 className="card-subtitle mb-2 text-secondary">By Status</h6>
                            <div className="small">
                              {Object.entries(statisticsData.requestsByStatus).map(([status, count]) => (
                                <div key={status} className="d-flex justify-content-between">
                                  <span>{status}:</span>
                                  <strong>{count}</strong>
                                </div>
                              ))}
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>

                    {/* Top Requesters and Top CVEs */}
                    <div className="row">
                      {/* Top Requesters */}
                      <div className="col-md-6">
                        <div className="card">
                          <div className="card-body">
                            <h6 className="card-title">Top Requesters</h6>
                            {statisticsData.topRequesters.length > 0 ? (
                              <table className="table table-sm">
                                <thead>
                                  <tr>
                                    <th>Username</th>
                                    <th className="text-end">Requests</th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {statisticsData.topRequesters.map((requester, idx) => (
                                    <tr key={idx}>
                                      <td>{requester.username}</td>
                                      <td className="text-end">{requester.count}</td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            ) : (
                              <p className="text-muted">No data available</p>
                            )}
                          </div>
                        </div>
                      </div>

                      {/* Top CVEs */}
                      <div className="col-md-6">
                        <div className="card">
                          <div className="card-body">
                            <h6 className="card-title">Top CVEs</h6>
                            {statisticsData.topCVEs.length > 0 ? (
                              <table className="table table-sm">
                                <thead>
                                  <tr>
                                    <th>CVE ID</th>
                                    <th className="text-end">Requests</th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {statisticsData.topCVEs.map((cve, idx) => (
                                    <tr key={idx}>
                                      <td><CveLink cveId={cve.cveId} /></td>
                                      <td className="text-end">{cve.count}</td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            ) : (
                              <p className="text-muted">No data available</p>
                            )}
                          </div>
                        </div>
                      </div>
                    </div>
                  </>
                ) : (
                  <p className="text-muted text-center">Click to expand and view statistics</p>
                )}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Back to Home button */}
      <div className="row mt-4">
        <div className="col-12">
          <a href="/" className="btn btn-secondary">
            <i className="bi bi-house me-2"></i>
            Back to Home
          </a>
        </div>
      </div>

      {/* Approval Detail Modal */}
      {selectedRequestId && (
        <ApprovalDetailModal
          isOpen={showDetailModal}
          requestId={selectedRequestId}
          onClose={handleCloseDetailModal}
          onApprove={handleModalApprove}
          onReject={handleModalReject}
        />
      )}
    </div>
  );
};

export default React.memo(ExceptionApprovalDashboard);
