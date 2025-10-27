import React, { useState, useEffect } from 'react';
import { listNotificationLogs, exportNotificationLogs } from '../services/notificationService';
import type { NotificationLog, PagedResponse } from '../services/notificationService';

export default function NotificationLogs() {
  const [logs, setLogs] = useState<NotificationLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Pagination
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // Filters
  const [notificationType, setNotificationType] = useState<string>('');
  const [status, setStatus] = useState<string>('');
  const [ownerEmail, setOwnerEmail] = useState<string>('');
  const [startDate, setStartDate] = useState<string>('');
  const [endDate, setEndDate] = useState<string>('');

  useEffect(() => {
    loadLogs();
  }, [currentPage, pageSize, notificationType, status, ownerEmail, startDate, endDate]);

  const loadLogs = async () => {
    try {
      setLoading(true);
      setError(null);

      const params: any = {
        page: currentPage,
        size: pageSize
      };

      if (notificationType) params.notificationType = notificationType;
      if (status) params.status = status;
      if (ownerEmail) params.ownerEmail = ownerEmail;
      if (startDate) params.startDate = new Date(startDate).toISOString();
      if (endDate) params.endDate = new Date(endDate).toISOString();

      const response: PagedResponse<NotificationLog> = await listNotificationLogs(params);

      setLogs(response.content);
      setTotalPages(response.totalPages);
      setTotalElements(response.totalElements);
    } catch (err: any) {
      setError(err.message || 'Failed to load notification logs');
    } finally {
      setLoading(false);
    }
  };

  const handleExport = async () => {
    try {
      const params: any = {};
      if (notificationType) params.notificationType = notificationType;
      if (status) params.status = status;
      if (ownerEmail) params.ownerEmail = ownerEmail;
      if (startDate) params.startDate = new Date(startDate).toISOString();
      if (endDate) params.endDate = new Date(endDate).toISOString();

      await exportNotificationLogs(params);
    } catch (err: any) {
      setError(err.message || 'Failed to export notification logs');
    }
  };

  const handleResetFilters = () => {
    setNotificationType('');
    setStatus('');
    setOwnerEmail('');
    setStartDate('');
    setEndDate('');
    setCurrentPage(0);
  };

  const handlePageChange = (newPage: number) => {
    if (newPage >= 0 && newPage < totalPages) {
      setCurrentPage(newPage);
    }
  };

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleString();
  };

  const getNotificationTypeLabel = (type: string) => {
    switch (type) {
      case 'OUTDATED_LEVEL1':
        return 'Outdated (Level 1)';
      case 'OUTDATED_LEVEL2':
        return 'Outdated (Level 2)';
      case 'NEW_VULNERABILITY':
        return 'New Vulnerability';
      default:
        return type;
    }
  };

  const getStatusBadgeClass = (status: string) => {
    switch (status) {
      case 'SENT':
        return 'badge bg-success';
      case 'FAILED':
        return 'badge bg-danger';
      case 'PENDING':
        return 'badge bg-warning';
      default:
        return 'badge bg-secondary';
    }
  };

  const getNotificationTypeBadgeClass = (type: string) => {
    switch (type) {
      case 'OUTDATED_LEVEL1':
        return 'badge bg-warning';
      case 'OUTDATED_LEVEL2':
        return 'badge bg-danger';
      case 'NEW_VULNERABILITY':
        return 'badge bg-info';
      default:
        return 'badge bg-secondary';
    }
  };

  if (loading && logs.length === 0) {
    return (
      <div className="text-center py-4">
        <div className="spinner-border" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  return (
    <div className="card">
      <div className="card-header d-flex justify-content-between align-items-center">
        <h5 className="mb-0">Notification Logs</h5>
        <button
          className="btn btn-sm btn-success"
          onClick={handleExport}
          title="Export current view to CSV"
        >
          <i className="bi bi-download me-1"></i>
          Export CSV
        </button>
      </div>

      <div className="card-body">
        {error && (
          <div className="alert alert-danger alert-dismissible fade show" role="alert">
            {error}
            <button
              type="button"
              className="btn-close"
              onClick={() => setError(null)}
              aria-label="Close"
            ></button>
          </div>
        )}

        {/* Filters */}
        <div className="row mb-3">
          <div className="col-md-3">
            <label htmlFor="notificationType" className="form-label">
              Notification Type
            </label>
            <select
              id="notificationType"
              className="form-select"
              value={notificationType}
              onChange={(e) => {
                setNotificationType(e.target.value);
                setCurrentPage(0);
              }}
            >
              <option value="">All Types</option>
              <option value="OUTDATED_LEVEL1">Outdated (Level 1)</option>
              <option value="OUTDATED_LEVEL2">Outdated (Level 2)</option>
              <option value="NEW_VULNERABILITY">New Vulnerability</option>
            </select>
          </div>

          <div className="col-md-2">
            <label htmlFor="status" className="form-label">
              Status
            </label>
            <select
              id="status"
              className="form-select"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setCurrentPage(0);
              }}
            >
              <option value="">All Status</option>
              <option value="SENT">Sent</option>
              <option value="FAILED">Failed</option>
              <option value="PENDING">Pending</option>
            </select>
          </div>

          <div className="col-md-3">
            <label htmlFor="ownerEmail" className="form-label">
              Owner Email
            </label>
            <input
              type="text"
              id="ownerEmail"
              className="form-control"
              placeholder="Filter by email..."
              value={ownerEmail}
              onChange={(e) => {
                setOwnerEmail(e.target.value);
                setCurrentPage(0);
              }}
            />
          </div>

          <div className="col-md-2">
            <label htmlFor="startDate" className="form-label">
              Start Date
            </label>
            <input
              type="date"
              id="startDate"
              className="form-control"
              value={startDate}
              onChange={(e) => {
                setStartDate(e.target.value);
                setCurrentPage(0);
              }}
            />
          </div>

          <div className="col-md-2">
            <label htmlFor="endDate" className="form-label">
              End Date
            </label>
            <input
              type="date"
              id="endDate"
              className="form-control"
              value={endDate}
              onChange={(e) => {
                setEndDate(e.target.value);
                setCurrentPage(0);
              }}
            />
          </div>
        </div>

        <div className="row mb-3">
          <div className="col-md-12">
            <button
              className="btn btn-sm btn-secondary"
              onClick={handleResetFilters}
            >
              <i className="bi bi-x-circle me-1"></i>
              Reset Filters
            </button>
            <span className="ms-3 text-muted">
              Showing {logs.length} of {totalElements} total logs
            </span>
          </div>
        </div>

        {/* Table */}
        {logs.length === 0 ? (
          <div className="alert alert-info">
            No notification logs found matching your filters.
          </div>
        ) : (
          <>
            <div className="table-responsive">
              <table className="table table-striped table-hover">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Asset</th>
                    <th>Owner Email</th>
                    <th>Type</th>
                    <th>Sent At</th>
                    <th>Status</th>
                    <th>Error</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map((log) => (
                    <tr key={log.id}>
                      <td>{log.id}</td>
                      <td>
                        {log.assetName}
                        {log.assetId && (
                          <small className="text-muted d-block">
                            ID: {log.assetId}
                          </small>
                        )}
                      </td>
                      <td>{log.ownerEmail}</td>
                      <td>
                        <span className={getNotificationTypeBadgeClass(log.notificationType)}>
                          {getNotificationTypeLabel(log.notificationType)}
                        </span>
                      </td>
                      <td>{formatDate(log.sentAt)}</td>
                      <td>
                        <span className={getStatusBadgeClass(log.status)}>
                          {log.status}
                        </span>
                      </td>
                      <td>
                        {log.errorMessage && (
                          <span className="text-danger" title={log.errorMessage}>
                            <i className="bi bi-exclamation-triangle"></i>
                            {log.errorMessage.length > 50
                              ? log.errorMessage.substring(0, 50) + '...'
                              : log.errorMessage}
                          </span>
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
                <select
                  className="form-select form-select-sm"
                  value={pageSize}
                  onChange={(e) => {
                    setPageSize(Number(e.target.value));
                    setCurrentPage(0);
                  }}
                  style={{ width: 'auto' }}
                >
                  <option value={10}>10 per page</option>
                  <option value={20}>20 per page</option>
                  <option value={50}>50 per page</option>
                  <option value={100}>100 per page</option>
                </select>
              </div>

              <nav aria-label="Notification logs pagination">
                <ul className="pagination pagination-sm mb-0">
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

                  {/* Page numbers */}
                  {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                    const pageNum = Math.max(
                      0,
                      Math.min(currentPage - 2 + i, totalPages - 5 + i)
                    );
                    if (pageNum < totalPages && pageNum >= 0) {
                      return (
                        <li
                          key={pageNum}
                          className={`page-item ${currentPage === pageNum ? 'active' : ''}`}
                        >
                          <button
                            className="page-link"
                            onClick={() => handlePageChange(pageNum)}
                          >
                            {pageNum + 1}
                          </button>
                        </li>
                      );
                    }
                    return null;
                  })}

                  <li
                    className={`page-item ${currentPage >= totalPages - 1 ? 'disabled' : ''}`}
                  >
                    <button
                      className="page-link"
                      onClick={() => handlePageChange(currentPage + 1)}
                      disabled={currentPage >= totalPages - 1}
                    >
                      Next
                    </button>
                  </li>
                  <li
                    className={`page-item ${currentPage >= totalPages - 1 ? 'disabled' : ''}`}
                  >
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

              <div className="text-muted small">
                Page {currentPage + 1} of {totalPages}
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
