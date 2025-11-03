/**
 * Current Vulnerabilities Table Component
 *
 * Displays current vulnerabilities with filtering, sorting, and pagination capabilities
 *
 * Features:
 * - Filter by severity, system, exception status, and overdue status
 * - Sortable columns including overdue status
 * - Exception and overdue status badges with tooltips
 * - Pagination (50 items per page)
 * - Loading and error states
 *
 * Related to: Feature 004-i-want-to (VULN Role & Vulnerability Management UI)
 * Related to: Feature 021-vulnerability-overdue-exception-logic
 */

import React, { useState, useEffect } from 'react';
import {
    getCurrentVulnerabilities,
    cleanupDuplicateVulnerabilities,
    getDistinctProducts,
    getDistinctAdDomains,
    type CurrentVulnerability,
    type PaginatedVulnerabilitiesResponse,
    type VulnerabilityCleanupResult
} from '../services/vulnerabilityManagementService';
import OverdueStatusBadge from './OverdueStatusBadge';
import ExceptionRequestModal from './ExceptionRequestModal';
import { isAdmin } from '../utils/auth';

const CurrentVulnerabilitiesTable: React.FC = () => {
    const [paginatedResponse, setPaginatedResponse] = useState<PaginatedVulnerabilitiesResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [successMessage, setSuccessMessage] = useState<string | null>(null);

    // Exception request modal state
    const [showRequestModal, setShowRequestModal] = useState(false);
    const [selectedVulnerability, setSelectedVulnerability] = useState<CurrentVulnerability | null>(null);

    // Cleanup modal state
    const [showCleanupModal, setShowCleanupModal] = useState(false);
    const [cleanupResult, setCleanupResult] = useState<VulnerabilityCleanupResult | null>(null);
    const [cleanupLoading, setCleanupLoading] = useState(false);

    // Filter states
    const [severityFilter, setSeverityFilter] = useState<string>('');
    const [systemFilter, setSystemFilter] = useState<string>('');
    const [exceptionFilter, setExceptionFilter] = useState<string>('');
    const [productFilter, setProductFilter] = useState<string>('');
    const [adDomainFilter, setAdDomainFilter] = useState<string>('');

    // Available filter options
    const [availableProducts, setAvailableProducts] = useState<string[]>([]);
    const [availableAdDomains, setAvailableAdDomains] = useState<string[]>([]);

    // Pagination states
    const [currentPage, setCurrentPage] = useState<number>(0);
    const [pageSize] = useState<number>(50);

    // Sort states
    const [sortField, setSortField] = useState<keyof CurrentVulnerability>('scanTimestamp');
    const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc');

    useEffect(() => {
        fetchVulnerabilities();
    }, [severityFilter, systemFilter, exceptionFilter, productFilter, adDomainFilter, currentPage]);

    useEffect(() => {
        // Fetch available filter options on mount
        const fetchFilterOptions = async () => {
            try {
                const [products, domains] = await Promise.all([
                    getDistinctProducts(undefined, 100),
                    getDistinctAdDomains()
                ]);
                setAvailableProducts(products);
                setAvailableAdDomains(domains);
            } catch (err) {
                console.error('Failed to fetch filter options:', err);
            }
        };
        fetchFilterOptions();
    }, []);

    const fetchVulnerabilities = async () => {
        try {
            setLoading(true);
            const data = await getCurrentVulnerabilities(
                severityFilter || undefined,
                systemFilter || undefined,
                exceptionFilter || undefined,
                productFilter || undefined,
                adDomainFilter || undefined,
                currentPage,
                pageSize
            );
            setPaginatedResponse(data);
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to fetch vulnerabilities');
        } finally {
            setLoading(false);
        }
    };

    const handleFilterChange = () => {
        // Reset to first page when filters change
        setCurrentPage(0);
    };

    const handleRequestException = (vuln: CurrentVulnerability) => {
        setSelectedVulnerability(vuln);
        setShowRequestModal(true);
    };

    const handleModalClose = () => {
        setShowRequestModal(false);
        setSelectedVulnerability(null);
    };

    const handleRequestSuccess = () => {
        setSuccessMessage('Exception request submitted successfully! Your request is pending review.');

        // Auto-dismiss success message after 5 seconds
        setTimeout(() => {
            setSuccessMessage(null);
        }, 5000);

        // Refresh the vulnerability list
        fetchVulnerabilities();
    };

    const handleCleanupDuplicates = async () => {
        try {
            setCleanupLoading(true);
            const result = await cleanupDuplicateVulnerabilities();
            setCleanupResult(result);
            setShowCleanupModal(true);

            // Refresh the vulnerability list after cleanup
            fetchVulnerabilities();
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to cleanup duplicates');
        } finally {
            setCleanupLoading(false);
        }
    };

    const handleCloseCleanupModal = () => {
        setShowCleanupModal(false);
        setCleanupResult(null);
    };

    const handleSort = (field: keyof CurrentVulnerability) => {
        if (sortField === field) {
            setSortOrder(sortOrder === 'asc' ? 'desc' : 'asc');
        } else {
            setSortField(field);
            setSortOrder('asc');
        }
    };

    const getSortedVulnerabilities = () => {
        if (!paginatedResponse || !paginatedResponse.content || !Array.isArray(paginatedResponse.content)) {
            return [];
        }

        return [...paginatedResponse.content].sort((a, b) => {
            const aVal = a[sortField];
            const bVal = b[sortField];

            if (aVal === null || aVal === undefined) return 1;
            if (bVal === null || bVal === undefined) return -1;

            const comparison = aVal < bVal ? -1 : aVal > bVal ? 1 : 0;
            return sortOrder === 'asc' ? comparison : -comparison;
        });
    };

    const getSeverityBadgeClass = (severity: string | null): string => {
        if (!severity) return 'bg-secondary';
        const sev = severity.toLowerCase();
        if (sev.includes('critical')) return 'bg-danger';
        if (sev.includes('high')) return 'bg-warning text-dark';
        if (sev.includes('medium')) return 'bg-info text-dark';
        if (sev.includes('low')) return 'bg-success';
        return 'bg-secondary';
    };

    const getExceptionBadge = (hasException: boolean, reason: string | null) => {
        if (hasException) {
            return (
                <span 
                    className="badge bg-success" 
                    title={reason || "This vulnerability is excepted"}
                >
                    Excepted
                </span>
            );
        }
        return <span className="badge bg-danger" title="No active exception">Not Excepted</span>;
    };

    const SortIcon: React.FC<{ field: keyof CurrentVulnerability }> = ({ field }) => {
        if (sortField !== field) return <i className="bi bi-chevron-expand ms-1 text-muted"></i>;
        return sortOrder === 'asc' ?
            <i className="bi bi-chevron-up ms-1"></i> :
            <i className="bi bi-chevron-down ms-1"></i>;
    };

    const handlePageChange = (newPage: number) => {
        setCurrentPage(newPage);
        window.scrollTo({ top: 0, behavior: 'smooth' });
    };

    const renderPagination = () => {
        if (!paginatedResponse || paginatedResponse.totalPages <= 1) return null;

        const { currentPage, totalPages, hasPrevious, hasNext, totalElements } = paginatedResponse;
        const startItem = currentPage * pageSize + 1;
        const endItem = Math.min((currentPage + 1) * pageSize, totalElements);

        // Generate page numbers to show
        const maxPagesToShow = 7;
        let startPage = Math.max(0, currentPage - Math.floor(maxPagesToShow / 2));
        let endPage = Math.min(totalPages - 1, startPage + maxPagesToShow - 1);
        
        // Adjust if we're near the end
        if (endPage - startPage < maxPagesToShow - 1) {
            startPage = Math.max(0, endPage - maxPagesToShow + 1);
        }

        const pageNumbers = [];
        for (let i = startPage; i <= endPage; i++) {
            pageNumbers.push(i);
        }

        return (
            <div className="d-flex justify-content-between align-items-center mt-4">
                <div className="text-muted">
                    Showing {startItem} to {endItem} of {totalElements} vulnerabilities
                </div>
                <nav aria-label="Vulnerability pagination">
                    <ul className="pagination mb-0">
                        <li className={`page-item ${!hasPrevious ? 'disabled' : ''}`}>
                            <button
                                className="page-link"
                                onClick={() => handlePageChange(currentPage - 1)}
                                disabled={!hasPrevious}
                                aria-label="Previous"
                            >
                                <span aria-hidden="true">&laquo;</span>
                            </button>
                        </li>
                        
                        {startPage > 0 && (
                            <>
                                <li className="page-item">
                                    <button className="page-link" onClick={() => handlePageChange(0)}>
                                        1
                                    </button>
                                </li>
                                {startPage > 1 && (
                                    <li className="page-item disabled">
                                        <span className="page-link">...</span>
                                    </li>
                                )}
                            </>
                        )}

                        {pageNumbers.map(pageNum => (
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
                        ))}

                        {endPage < totalPages - 1 && (
                            <>
                                {endPage < totalPages - 2 && (
                                    <li className="page-item disabled">
                                        <span className="page-link">...</span>
                                    </li>
                                )}
                                <li className="page-item">
                                    <button 
                                        className="page-link" 
                                        onClick={() => handlePageChange(totalPages - 1)}
                                    >
                                        {totalPages}
                                    </button>
                                </li>
                            </>
                        )}

                        <li className={`page-item ${!hasNext ? 'disabled' : ''}`}>
                            <button
                                className="page-link"
                                onClick={() => handlePageChange(currentPage + 1)}
                                disabled={!hasNext}
                                aria-label="Next"
                            >
                                <span aria-hidden="true">&raquo;</span>
                            </button>
                        </li>
                    </ul>
                </nav>
                <div className="text-muted">
                    Page {currentPage + 1} of {totalPages}
                </div>
            </div>
        );
    };

    if (loading) {
        return (
            <div className="d-flex justify-content-center p-5">
                <div className="spinner-border" role="status">
                    <span className="visually-hidden">Loading...</span>
                </div>
            </div>
        );
    }

    if (error) {
        return (
            <div className="container-fluid p-4">
                <div className="alert alert-danger" role="alert">
                    <i className="bi bi-exclamation-triangle me-2"></i>
                    {error}
                </div>
            </div>
        );
    }

    const sortedVulnerabilities = getSortedVulnerabilities();
    const totalCount = paginatedResponse?.totalElements || 0;

    return (
        <div className="container-fluid p-4">
            <div className="row">
                <div className="col-12">
                    <div className="d-flex justify-content-between align-items-center mb-4">
                        <h2>
                            <i className="bi bi-shield-exclamation me-2"></i>
                            Current Vulnerabilities
                        </h2>
                        <div className="d-flex gap-2">
                            {isAdmin() && (
                                <button
                                    className="btn btn-warning"
                                    onClick={handleCleanupDuplicates}
                                    disabled={cleanupLoading}
                                >
                                    {cleanupLoading ? (
                                        <>
                                            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                            Cleaning...
                                        </>
                                    ) : (
                                        <>
                                            <i className="bi bi-trash3 me-2"></i>
                                            Cleanup Duplicates
                                        </>
                                    )}
                                </button>
                            )}
                            <button
                                className="btn btn-outline-primary"
                                onClick={fetchVulnerabilities}
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

            {/* Filters */}
            <div className="row mb-4">
                <div className="col-md-3">
                    <label htmlFor="severityFilter" className="form-label">Severity</label>
                    <select
                        id="severityFilter"
                        className="form-select"
                        value={severityFilter}
                        onChange={(e) => {
                            setSeverityFilter(e.target.value);
                            handleFilterChange();
                        }}
                    >
                        <option value="">All Severities</option>
                        <option value="Critical">Critical</option>
                        <option value="High">High</option>
                        <option value="Medium">Medium</option>
                        <option value="Low">Low</option>
                    </select>
                </div>
                <div className="col-md-3">
                    <label htmlFor="systemFilter" className="form-label">System</label>
                    <input
                        type="text"
                        id="systemFilter"
                        className="form-control"
                        placeholder="Filter by system name..."
                        value={systemFilter}
                        onChange={(e) => {
                            setSystemFilter(e.target.value);
                            handleFilterChange();
                        }}
                    />
                </div>
                <div className="col-md-3">
                    <label htmlFor="exceptionFilter" className="form-label">
                        Overdue Status
                        <i 
                            className="bi bi-question-circle ms-2 text-muted" 
                            data-bs-toggle="tooltip" 
                            title="Filter by vulnerability overdue status. OVERDUE = exceeds threshold, EXCEPTED = has active exception, OK = within threshold"
                            style={{ cursor: 'help', fontSize: '0.875rem' }}
                        ></i>
                    </label>
                    <select
                        id="exceptionFilter"
                        className="form-select"
                        value={exceptionFilter}
                        onChange={(e) => {
                            setExceptionFilter(e.target.value);
                            handleFilterChange();
                        }}
                    >
                        <option value="">All Statuses</option>
                        <option value="overdue">🔴 Overdue Only</option>
                        <option value="excepted">🛡️ Excepted Only</option>
                        <option value="ok">✅ OK Only</option>
                    </select>
                </div>
                <div className="col-md-3">
                    <label htmlFor="productFilter" className="form-label">Product</label>
                    <select
                        id="productFilter"
                        className="form-select"
                        value={productFilter}
                        onChange={(e) => {
                            setProductFilter(e.target.value);
                            handleFilterChange();
                        }}
                    >
                        <option value="">All Products</option>
                        {availableProducts.map((product) => (
                            <option key={product} value={product}>
                                {product}
                            </option>
                        ))}
                    </select>
                </div>
            </div>

            {/* Second row of filters */}
            <div className="row mb-4">
                <div className="col-md-3">
                    <label htmlFor="adDomainFilter" className="form-label">
                        <i className="bi bi-building me-2"></i>
                        AD Domain
                    </label>
                    <select
                        id="adDomainFilter"
                        className="form-select"
                        value={adDomainFilter}
                        onChange={(e) => {
                            setAdDomainFilter(e.target.value);
                            handleFilterChange();
                        }}
                    >
                        <option value="">All Domains</option>
                        {availableAdDomains.map((domain) => (
                            <option key={domain} value={domain}>
                                {domain}
                            </option>
                        ))}
                    </select>
                </div>
            </div>

            {/* Table */}
            <div className="row">
                <div className="col-12">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                Vulnerabilities ({totalCount})
                            </h5>
                            {sortedVulnerabilities.length === 0 ? (
                                <p className="text-muted">
                                    No vulnerabilities found. Upload vulnerability scan results to see them here.
                                </p>
                            ) : (
                                <>
                                    <div className="table-responsive">
                                        <table className="table table-striped table-hover">
                                            <thead>
                                                <tr>
                                                    <th
                                                        onClick={() => handleSort('assetName')}
                                                        style={{ cursor: 'pointer' }}
                                                    >
                                                        System
                                                        <SortIcon field="assetName" />
                                                    </th>
                                                    <th
                                                        onClick={() => handleSort('assetIp')}
                                                        style={{ cursor: 'pointer' }}
                                                    >
                                                        IP
                                                        <SortIcon field="assetIp" />
                                                    </th>
                                                    <th
                                                        onClick={() => handleSort('vulnerabilityId')}
                                                        style={{ cursor: 'pointer' }}
                                                    >
                                                        CVE
                                                        <SortIcon field="vulnerabilityId" />
                                                    </th>
                                                    <th
                                                        onClick={() => handleSort('cvssSeverity')}
                                                        style={{ cursor: 'pointer' }}
                                                    >
                                                        Severity
                                                        <SortIcon field="cvssSeverity" />
                                                    </th>
                                                    <th
                                                        onClick={() => handleSort('vulnerableProductVersions')}
                                                        style={{ cursor: 'pointer' }}
                                                    >
                                                        Product
                                                        <SortIcon field="vulnerableProductVersions" />
                                                    </th>
                                                    <th
                                                        onClick={() => handleSort('daysOpen')}
                                                        style={{ cursor: 'pointer' }}
                                                    >
                                                        Days Open
                                                        <SortIcon field="daysOpen" />
                                                    </th>
                                                    <th
                                                        onClick={() => handleSort('scanTimestamp')}
                                                        style={{ cursor: 'pointer' }}
                                                    >
                                                        Scan Date
                                                        <SortIcon field="scanTimestamp" />
                                                    </th>
                                                    <th
                                                        onClick={() => handleSort('overdueStatus')}
                                                        style={{ cursor: 'pointer' }}
                                                    >
                                                        Overdue Status
                                                        <SortIcon field="overdueStatus" />
                                                    </th>
                                                    <th>Actions</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {sortedVulnerabilities.map((vuln) => (
                                                    <tr key={vuln.id}>
                                                        <td>{vuln.assetName}</td>
                                                        <td>{vuln.assetIp || '-'}</td>
                                                        <td>
                                                            <code>{vuln.vulnerabilityId || '-'}</code>
                                                        </td>
                                                        <td>
                                                            <span className={`badge ${getSeverityBadgeClass(vuln.cvssSeverity)}`}>
                                                                {vuln.cvssSeverity || 'Unknown'}
                                                            </span>
                                                        </td>
                                                        <td style={{ maxWidth: '200px', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                                                            {vuln.vulnerableProductVersions || '-'}
                                                        </td>
                                                        <td>{vuln.daysOpen || '-'}</td>
                                                        <td>
                                                            {vuln.scanTimestamp ?
                                                                new Date(vuln.scanTimestamp).toLocaleDateString() : '-'}
                                                        </td>
                                                        <td>
                                                            <OverdueStatusBadge
                                                                status={vuln.overdueStatus || 'OK'}
                                                                daysOverdue={vuln.daysOverdue}
                                                                ageInDays={vuln.ageInDays}
                                                                exceptionReason={vuln.exceptionReason}
                                                                exceptionEndDate={vuln.exceptionEndDate}
                                                            />
                                                        </td>
                                                        <td>
                                                            {vuln.overdueStatus === 'OVERDUE' ? (
                                                                <button
                                                                    className="btn btn-sm btn-outline-primary"
                                                                    onClick={() => handleRequestException(vuln)}
                                                                    disabled={vuln.hasException}
                                                                    title={vuln.hasException ? 'Exception already exists' : 'Request an exception for this vulnerability'}
                                                                >
                                                                    <i className="bi bi-shield-plus me-1"></i>
                                                                    Request Exception
                                                                </button>
                                                            ) : (
                                                                <button
                                                                    className="btn btn-sm btn-outline-secondary"
                                                                    disabled={true}
                                                                    title="Exceptions can only be requested for overdue vulnerabilities"
                                                                >
                                                                    <i className="bi bi-shield-plus me-1"></i>
                                                                    Request Exception
                                                                </button>
                                                            )}
                                                        </td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                    
                                    {/* Pagination Controls */}
                                    {renderPagination()}
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

            {/* Exception Request Modal */}
            {selectedVulnerability && (
                <ExceptionRequestModal
                    isOpen={showRequestModal}
                    vulnerabilityId={selectedVulnerability.id}
                    vulnerabilityCveId={selectedVulnerability.vulnerabilityId}
                    assetName={selectedVulnerability.assetName}
                    onClose={handleModalClose}
                    onSuccess={handleRequestSuccess}
                />
            )}

            {/* Cleanup Results Modal */}
            {showCleanupModal && cleanupResult && (
                <div className="modal show d-block" tabIndex={-1} role="dialog" style={{backgroundColor: 'rgba(0,0,0,0.5)'}}>
                    <div className="modal-dialog modal-dialog-centered">
                        <div className="modal-content">
                            <div className="modal-header bg-success text-white">
                                <h5 className="modal-title">
                                    <i className="bi bi-check-circle me-2"></i>
                                    Cleanup Complete
                                </h5>
                                <button
                                    type="button"
                                    className="btn-close btn-close-white"
                                    onClick={handleCloseCleanupModal}
                                    aria-label="Close"
                                ></button>
                            </div>
                            <div className="modal-body">
                                <div className="alert alert-info mb-3">
                                    <i className="bi bi-info-circle me-2"></i>
                                    {cleanupResult.message}
                                </div>

                                <h6 className="mb-3">Statistics:</h6>
                                <table className="table table-sm">
                                    <tbody>
                                        <tr>
                                            <td><strong>Total Scanned:</strong></td>
                                            <td className="text-end">{cleanupResult.totalScanned.toLocaleString()}</td>
                                        </tr>
                                        <tr>
                                            <td><strong>Duplicates Found:</strong></td>
                                            <td className="text-end">
                                                <span className={cleanupResult.duplicatesFound > 0 ? 'text-warning' : 'text-success'}>
                                                    {cleanupResult.duplicatesFound.toLocaleString()}
                                                </span>
                                            </td>
                                        </tr>
                                        <tr>
                                            <td><strong>Duplicates Removed:</strong></td>
                                            <td className="text-end">
                                                <span className={cleanupResult.duplicatesRemoved > 0 ? 'text-danger' : 'text-muted'}>
                                                    {cleanupResult.duplicatesRemoved.toLocaleString()}
                                                </span>
                                            </td>
                                        </tr>
                                        <tr className="table-active">
                                            <td><strong>Unique Remaining:</strong></td>
                                            <td className="text-end">
                                                <strong className="text-success">
                                                    {cleanupResult.uniqueVulnerabilitiesRemaining.toLocaleString()}
                                                </strong>
                                            </td>
                                        </tr>
                                    </tbody>
                                </table>

                                {cleanupResult.duplicatesRemoved > 0 && (
                                    <div className="alert alert-success mb-0 mt-3">
                                        <i className="bi bi-check2 me-2"></i>
                                        Successfully removed {cleanupResult.duplicatesRemoved} duplicate{cleanupResult.duplicatesRemoved !== 1 ? 's' : ''}!
                                    </div>
                                )}
                            </div>
                            <div className="modal-footer">
                                <button
                                    type="button"
                                    className="btn btn-primary"
                                    onClick={handleCloseCleanupModal}
                                >
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

export default CurrentVulnerabilitiesTable;
