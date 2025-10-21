/**
 * MyExceptionRequests Component
 *
 * Dashboard page for viewing and managing user's own exception requests
 *
 * Features:
 * - Summary statistics cards (Total, Approved, Pending, Rejected)
 * - Status filter dropdown
 * - Paginated table with sortable columns
 * - View details modal
 * - Cancel pending requests
 * - Empty state handling
 *
 * Feature: 031-vuln-exception-approval
 * User Story 1: Regular User Requests Exception (P1)
 * Reference: spec.md FR-010 to FR-017
 */

import React, { useState, useEffect } from 'react';
import {
    getMyRequests,
    getMySummary,
    cancelRequest,
    type VulnerabilityExceptionRequestDto,
    type ExceptionRequestSummaryDto,
    type ExceptionRequestStatus,
    type PagedResponse
} from '../services/exceptionRequestService';
import ExceptionStatusBadge from './ExceptionStatusBadge';
import ExceptionRequestDetailModal from './ExceptionRequestDetailModal';

const MyExceptionRequests: React.FC = () => {
    // Data states
    const [requests, setRequests] = useState<PagedResponse<VulnerabilityExceptionRequestDto> | null>(null);
    const [summary, setSummary] = useState<ExceptionRequestSummaryDto | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [successMessage, setSuccessMessage] = useState<string | null>(null);

    // Filter and pagination states
    const [statusFilter, setStatusFilter] = useState<ExceptionRequestStatus | undefined>(undefined);
    const [currentPage, setCurrentPage] = useState(0);
    const [pageSize, setPageSize] = useState(20);

    // Modal states
    const [showDetailModal, setShowDetailModal] = useState(false);
    const [selectedRequestId, setSelectedRequestId] = useState<number | null>(null);

    // Cancellation state
    const [cancellingId, setCancellingId] = useState<number | null>(null);

    useEffect(() => {
        fetchData();
    }, [statusFilter, currentPage, pageSize]);

    const fetchData = async () => {
        try {
            setLoading(true);
            setError(null);

            // Fetch summary and requests in parallel
            const [summaryData, requestsData] = await Promise.all([
                getMySummary(),
                getMyRequests(statusFilter, currentPage, pageSize)
            ]);

            setSummary(summaryData);
            setRequests(requestsData);
        } catch (err) {
            const errorMessage = err instanceof Error ? err.message : 'Failed to load exception requests';
            setError(errorMessage);
        } finally {
            setLoading(false);
        }
    };

    const handleStatusFilterChange = (status: string) => {
        setStatusFilter(status === '' ? undefined : status as ExceptionRequestStatus);
        setCurrentPage(0); // Reset to first page when filter changes
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

    const handleCancelRequest = async (requestId: number) => {
        if (!confirm('Are you sure you want to cancel this exception request?')) {
            return;
        }

        try {
            setCancellingId(requestId);
            await cancelRequest(requestId);

            setSuccessMessage('Exception request cancelled successfully.');

            // Auto-dismiss success message after 5 seconds
            setTimeout(() => {
                setSuccessMessage(null);
            }, 5000);

            // Refresh data
            fetchData();
        } catch (err) {
            const errorMessage = err instanceof Error ? err.message : 'Failed to cancel request';
            setError(errorMessage);
        } finally {
            setCancellingId(null);
        }
    };

    const handleDetailModalUpdate = () => {
        // Refresh data after detail modal update (e.g., after cancel)
        fetchData();
    };

    // Render loading state
    if (loading && !requests) {
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
    if (error && !requests) {
        return (
            <div className="container-fluid p-4">
                <div className="alert alert-danger" role="alert">
                    <i className="bi bi-exclamation-triangle me-2"></i>
                    {error}
                </div>
            </div>
        );
    }

    return (
        <div className="container-fluid p-4">
            {/* Page Header */}
            <div className="row">
                <div className="col-12">
                    <div className="d-flex justify-content-between align-items-center mb-4">
                        <h2>
                            <i className="bi bi-clipboard-check me-2"></i>
                            My Exception Requests
                        </h2>
                        <button
                            className="btn btn-outline-primary"
                            onClick={fetchData}
                            disabled={loading}
                        >
                            <i className="bi bi-arrow-clockwise me-2"></i>
                            Refresh
                        </button>
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
            {error && requests && (
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
            {summary && (
                <div className="row mb-4">
                    <div className="col-md-3">
                        <div className="card">
                            <div className="card-body">
                                <h6 className="card-subtitle mb-2 text-muted">Total Requests</h6>
                                <h3 className="card-title mb-0">{summary.totalRequests}</h3>
                            </div>
                        </div>
                    </div>
                    <div className="col-md-3">
                        <div className="card border-success">
                            <div className="card-body">
                                <h6 className="card-subtitle mb-2 text-success">Approved</h6>
                                <h3 className="card-title mb-0 text-success">{summary.approvedCount}</h3>
                            </div>
                        </div>
                    </div>
                    <div className="col-md-3">
                        <div className="card border-warning">
                            <div className="card-body">
                                <h6 className="card-subtitle mb-2 text-warning">Pending</h6>
                                <h3 className="card-title mb-0 text-warning">{summary.pendingCount}</h3>
                            </div>
                        </div>
                    </div>
                    <div className="col-md-3">
                        <div className="card border-danger">
                            <div className="card-body">
                                <h6 className="card-subtitle mb-2 text-danger">Rejected</h6>
                                <h3 className="card-title mb-0 text-danger">{summary.rejectedCount}</h3>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Filters and Controls */}
            <div className="row mb-3">
                <div className="col-md-4">
                    <label htmlFor="statusFilter" className="form-label">Filter by Status</label>
                    <select
                        id="statusFilter"
                        className="form-select"
                        value={statusFilter || ''}
                        onChange={(e) => handleStatusFilterChange(e.target.value)}
                    >
                        <option value="">All Statuses</option>
                        <option value="PENDING">Pending</option>
                        <option value="APPROVED">Approved</option>
                        <option value="REJECTED">Rejected</option>
                        <option value="EXPIRED">Expired</option>
                        <option value="CANCELLED">Cancelled</option>
                    </select>
                </div>
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

            {/* Table */}
            <div className="row">
                <div className="col-12">
                    <div className="card">
                        <div className="card-body">
                            {requests && requests.content.length === 0 ? (
                                <div className="text-center py-5">
                                    <i className="bi bi-inbox display-1 text-muted"></i>
                                    <p className="text-muted mt-3">
                                        No exception requests yet.
                                        <br />
                                        Request an exception from the vulnerabilities view.
                                    </p>
                                    <a href="/current-vulnerabilities" className="btn btn-primary mt-2">
                                        <i className="bi bi-shield-exclamation me-2"></i>
                                        View Vulnerabilities
                                    </a>
                                </div>
                            ) : (
                                <>
                                    <div className="table-responsive">
                                        <table className="table table-striped table-hover">
                                            <thead>
                                                <tr>
                                                    <th>Status</th>
                                                    <th>CVE ID</th>
                                                    <th>Asset</th>
                                                    <th>Scope</th>
                                                    <th>Submitted</th>
                                                    <th>Actions</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {requests?.content.map((request) => (
                                                    <tr key={request.id}>
                                                        <td>
                                                            <ExceptionStatusBadge status={request.status} autoApproved={request.autoApproved} />
                                                        </td>
                                                        <td>
                                                            {request.vulnerabilityCveId ? (
                                                                <code>{request.vulnerabilityCveId}</code>
                                                            ) : (
                                                                <span
                                                                    className="text-muted"
                                                                    title="Vulnerability has been remediated or removed"
                                                                >
                                                                    <i className="bi bi-info-circle me-1"></i>
                                                                    Remediated
                                                                </span>
                                                            )}
                                                        </td>
                                                        <td>{request.assetName || 'N/A'}</td>
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
                                                        <td>
                                                            {new Date(request.createdAt).toLocaleDateString()}
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
                                                                {request.status === 'PENDING' && (
                                                                    <button
                                                                        className="btn btn-outline-danger"
                                                                        onClick={() => handleCancelRequest(request.id)}
                                                                        disabled={cancellingId === request.id}
                                                                        title="Cancel request"
                                                                    >
                                                                        {cancellingId === request.id ? (
                                                                            <span className="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
                                                                        ) : (
                                                                            <i className="bi bi-x-circle"></i>
                                                                        )}
                                                                    </button>
                                                                )}
                                                            </div>
                                                        </td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>

                                    {/* Pagination */}
                                    {requests && requests.totalPages > 1 && (
                                        <div className="d-flex justify-content-between align-items-center mt-3">
                                            <div className="text-muted">
                                                Showing {currentPage * pageSize + 1} to {Math.min((currentPage + 1) * pageSize, requests.totalElements)} of {requests.totalElements} requests
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
                                                    {Array.from({ length: requests.totalPages }, (_, i) => i).map((page) => (
                                                        <li key={page} className={`page-item ${currentPage === page ? 'active' : ''}`}>
                                                            <button
                                                                className="page-link"
                                                                onClick={() => setCurrentPage(page)}
                                                            >
                                                                {page + 1}
                                                            </button>
                                                        </li>
                                                    ))}
                                                    <li className={`page-item ${currentPage === requests.totalPages - 1 ? 'disabled' : ''}`}>
                                                        <button
                                                            className="page-link"
                                                            onClick={() => setCurrentPage(currentPage + 1)}
                                                            disabled={currentPage === requests.totalPages - 1}
                                                            aria-label="Next"
                                                        >
                                                            <span aria-hidden="true">&raquo;</span>
                                                        </button>
                                                    </li>
                                                </ul>
                                            </nav>
                                            <div className="text-muted">
                                                Page {currentPage + 1} of {requests.totalPages}
                                            </div>
                                        </div>
                                    )}
                                </>
                            )}
                        </div>
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

            {/* Detail Modal */}
            {selectedRequestId && (
                <ExceptionRequestDetailModal
                    isOpen={showDetailModal}
                    requestId={selectedRequestId}
                    onClose={handleCloseDetailModal}
                    onUpdate={handleDetailModalUpdate}
                />
            )}
        </div>
    );
};

export default React.memo(MyExceptionRequests);
