import React, { useState, useEffect } from 'react';
import { authenticatedGet } from '../utils/auth';

interface Scan {
  id: number;
  scanType: string;
  filename: string;
  scanDate: string;
  uploadedBy: string;
  hostCount: number;
  duration: string;
  createdAt: string;
}

interface Host {
  ipAddress: string;
  hostname: string | null;
  discoveredAt: string;
  portCount: number;
}

interface ScanDetail {
  id: number;
  scanType: string;
  filename: string;
  scanDate: string;
  uploadedBy: string;
  hostCount: number;
  duration: string;
  createdAt: string;
  hosts: Host[];
}

interface PagedResponse {
  content: Scan[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

/**
 * ScanManagement component
 *
 * Displays scan history with pagination and detail view.
 * ADMIN-only access enforced by backend.
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - FR-007, FR-008: Scans page with admin-only access
 */
const ScanManagement: React.FC = () => {
  const [scans, setScans] = useState<Scan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [pageSize] = useState(20);
  const [scanTypeFilter, setScanTypeFilter] = useState<string>('');

  // Detail modal state
  const [selectedScan, setSelectedScan] = useState<ScanDetail | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);

  useEffect(() => {
    fetchScans();
  }, [currentPage, scanTypeFilter]);

  const fetchScans = async () => {
    setLoading(true);
    setError(null);

    try {
      let url = `/api/scans?page=${currentPage}&size=${pageSize}`;
      if (scanTypeFilter) {
        url += `&scanType=${scanTypeFilter}`;
      }

      const response = await authenticatedGet(url);

      if (response.ok) {
        const data: PagedResponse = await response.json();
        setScans(data.content);
        setTotalPages(data.totalPages);
        setTotalElements(data.totalElements);
      } else if (response.status === 403) {
        setError('Access denied. This page is only accessible to administrators.');
      } else {
        const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
        setError(errorData.error || `Failed to fetch scans: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const fetchScanDetail = async (scanId: number) => {
    setLoadingDetail(true);

    try {
      const response = await authenticatedGet(`/api/scans/${scanId}`);

      if (response.ok) {
        const data: ScanDetail = await response.json();
        setSelectedScan(data);
        setShowDetailModal(true);
      } else {
        const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
        setError(errorData.error || `Failed to fetch scan detail: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoadingDetail(false);
    }
  };

  const handleScanClick = (scanId: number) => {
    fetchScanDetail(scanId);
  };

  const closeDetailModal = () => {
    setShowDetailModal(false);
    setSelectedScan(null);
  };

  const formatDate = (dateString: string): string => {
    const date = new Date(dateString);
    return date.toLocaleString();
  };

  const handlePageChange = (newPage: number) => {
    if (newPage >= 0 && newPage < totalPages) {
      setCurrentPage(newPage);
    }
  };

  const handleFilterChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setScanTypeFilter(e.target.value);
    setCurrentPage(0); // Reset to first page when filter changes
  };

  if (loading && scans.length === 0) {
    return (
      <div className="d-flex justify-content-center p-5">
        <div className="spinner-border" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  return (
    <div className="container-fluid p-4">
      <div className="row">
        <div className="col-12">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <h2>Scan Management</h2>
            <div className="d-flex align-items-center gap-2">
              <label htmlFor="scanTypeFilter" className="me-2">Filter:</label>
              <select
                id="scanTypeFilter"
                className="form-select"
                style={{ width: 'auto' }}
                value={scanTypeFilter}
                onChange={handleFilterChange}
              >
                <option value="">All Scans</option>
                <option value="nmap">Nmap</option>
                <option value="masscan">Masscan</option>
              </select>
            </div>
          </div>

          {error && (
            <div className="alert alert-danger" role="alert">
              {error}
            </div>
          )}

          <div className="card">
            <div className="card-body">
              <div className="table-responsive">
                <table className="table table-hover">
                  <thead>
                    <tr>
                      <th>ID</th>
                      <th>Type</th>
                      <th>Filename</th>
                      <th>Scan Date</th>
                      <th>Uploaded By</th>
                      <th>Hosts</th>
                      <th>Duration</th>
                      <th>Created At</th>
                    </tr>
                  </thead>
                  <tbody>
                    {scans.length === 0 ? (
                      <tr>
                        <td colSpan={8} className="text-center text-muted">
                          No scans found. Upload a scan file to get started.
                        </td>
                      </tr>
                    ) : (
                      scans.map((scan) => (
                        <tr
                          key={scan.id}
                          onClick={() => handleScanClick(scan.id)}
                          style={{ cursor: 'pointer' }}
                        >
                          <td>{scan.id}</td>
                          <td>
                            <span className={`badge bg-${scan.scanType === 'nmap' ? 'primary' : 'secondary'}`}>
                              {scan.scanType}
                            </span>
                          </td>
                          <td>{scan.filename}</td>
                          <td>{formatDate(scan.scanDate)}</td>
                          <td>{scan.uploadedBy}</td>
                          <td>{scan.hostCount}</td>
                          <td>{scan.duration}</td>
                          <td>{formatDate(scan.createdAt)}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>

              {/* Pagination */}
              {totalPages > 1 && (
                <div className="d-flex justify-content-between align-items-center mt-3">
                  <div className="text-muted">
                    Showing {currentPage * pageSize + 1} to {Math.min((currentPage + 1) * pageSize, totalElements)} of {totalElements} scans
                  </div>
                  <nav>
                    <ul className="pagination mb-0">
                      <li className={`page-item ${currentPage === 0 ? 'disabled' : ''}`}>
                        <button
                          className="page-link"
                          onClick={() => handlePageChange(currentPage - 1)}
                          disabled={currentPage === 0}
                        >
                          Previous
                        </button>
                      </li>
                      {[...Array(totalPages)].map((_, index) => (
                        <li
                          key={index}
                          className={`page-item ${currentPage === index ? 'active' : ''}`}
                        >
                          <button
                            className="page-link"
                            onClick={() => handlePageChange(index)}
                          >
                            {index + 1}
                          </button>
                        </li>
                      ))}
                      <li className={`page-item ${currentPage === totalPages - 1 ? 'disabled' : ''}`}>
                        <button
                          className="page-link"
                          onClick={() => handlePageChange(currentPage + 1)}
                          disabled={currentPage === totalPages - 1}
                        >
                          Next
                        </button>
                      </li>
                    </ul>
                  </nav>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Scan Detail Modal */}
      {showDetailModal && selectedScan && (
        <div className="modal show d-block" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-xl">
            <div className="modal-content">
              <div className="modal-header">
                <h5 className="modal-title">Scan Detail: {selectedScan.filename}</h5>
                <button type="button" className="btn-close" onClick={closeDetailModal}></button>
              </div>
              <div className="modal-body">
                {loadingDetail ? (
                  <div className="d-flex justify-content-center p-3">
                    <div className="spinner-border" role="status">
                      <span className="visually-hidden">Loading...</span>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="row mb-3">
                      <div className="col-md-6">
                        <p><strong>Scan Type:</strong> <span className={`badge bg-${selectedScan.scanType === 'nmap' ? 'primary' : 'secondary'}`}>{selectedScan.scanType}</span></p>
                        <p><strong>Scan Date:</strong> {formatDate(selectedScan.scanDate)}</p>
                        <p><strong>Uploaded By:</strong> {selectedScan.uploadedBy}</p>
                      </div>
                      <div className="col-md-6">
                        <p><strong>Host Count:</strong> {selectedScan.hostCount}</p>
                        <p><strong>Duration:</strong> {selectedScan.duration}</p>
                        <p><strong>Created At:</strong> {formatDate(selectedScan.createdAt)}</p>
                      </div>
                    </div>

                    <h6>Discovered Hosts ({selectedScan.hosts.length})</h6>
                    <div className="table-responsive">
                      <table className="table table-sm">
                        <thead>
                          <tr>
                            <th>IP Address</th>
                            <th>Hostname</th>
                            <th>Discovered At</th>
                            <th>Port Count</th>
                          </tr>
                        </thead>
                        <tbody>
                          {selectedScan.hosts.map((host, index) => (
                            <tr key={index}>
                              <td><code>{host.ipAddress}</code></td>
                              <td>{host.hostname || <span className="text-muted">N/A</span>}</td>
                              <td>{formatDate(host.discoveredAt)}</td>
                              <td>{host.portCount}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </>
                )}
              </div>
              <div className="modal-footer">
                <button type="button" className="btn btn-secondary" onClick={closeDetailModal}>
                  Close
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ScanManagement;
