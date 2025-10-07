/**
 * ReleaseList Component
 *
 * Main list view for browsing releases with filtering, search, and pagination
 *
 * Features:
 * - Status filter (ALL, DRAFT, PUBLISHED, ARCHIVED)
 * - Search by version or name
 * - Pagination (20 items per page)
 * - Empty state handling
 * - Click to navigate to detail page
 * - RBAC: Show create button for ADMIN/RELEASE_MANAGER only
 *
 * Related to: Feature 012-build-ui-for, User Story 1 (View and Browse Releases)
 */

import React, { useState, useEffect } from 'react';
import { releaseService, type Release, type PaginatedResponse } from '../services/releaseService';
import { hasRole, getUser } from '../utils/auth';
import { canDeleteRelease } from '../utils/permissions';
import ReleaseCreateModal from './ReleaseCreateModal';
import ReleaseDeleteConfirm from './ReleaseDeleteConfirm';
import Toast from './Toast';

interface ReleaseListProps {
    // No props needed - standalone component
}

const ReleaseList: React.FC<ReleaseListProps> = () => {
    // State
    const [releases, setReleases] = useState<Release[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    
    // Filters
    const [statusFilter, setStatusFilter] = useState<string>('ALL');
    const [searchQuery, setSearchQuery] = useState('');
    const [debouncedSearch, setDebouncedSearch] = useState('');
    
    // Pagination
    const [currentPage, setCurrentPage] = useState(1);
    const [totalPages, setTotalPages] = useState(1);
    const [totalItems, setTotalItems] = useState(0);
    const pageSize = 20;

    // Modal state
    const [showCreateModal, setShowCreateModal] = useState(false);
    const [showDeleteModal, setShowDeleteModal] = useState(false);
    const [releaseToDelete, setReleaseToDelete] = useState<Release | null>(null);
    const [isDeleting, setIsDeleting] = useState(false);

    // Toast state
    const [toast, setToast] = useState<{
        show: boolean;
        message: string;
        type: 'success' | 'error' | 'warning' | 'info';
    }>({
        show: false,
        message: '',
        type: 'success',
    });

    // User/Role info
    const user = getUser();
    const userRoles = user?.roles || [];
    const canCreate = hasRole('ADMIN') || hasRole('RELEASE_MANAGER');

    // Debounce search query (300ms)
    useEffect(() => {
        const timer = setTimeout(() => {
            setDebouncedSearch(searchQuery);
            setCurrentPage(1); // Reset to page 1 when search changes
        }, 300);

        return () => clearTimeout(timer);
    }, [searchQuery]);

    // Fetch releases
    useEffect(() => {
        loadReleases();
    }, [statusFilter, debouncedSearch, currentPage]);

    async function loadReleases() {
        try {
            setLoading(true);
            setError(null);

            const result: PaginatedResponse<Release> = await releaseService.list(
                statusFilter,
                currentPage,
                pageSize
            );

            setReleases(result.data);
            setTotalPages(result.totalPages);
            setTotalItems(result.totalItems);
            setCurrentPage(result.currentPage);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to load releases');
            setReleases([]);
        } finally {
            setLoading(false);
        }
    }

    // Filter releases by search query (client-side if API doesn't support)
    const filteredReleases = releases.filter((release) => {
        if (!debouncedSearch) return true;
        const query = debouncedSearch.toLowerCase();
        return (
            release.version.toLowerCase().includes(query) ||
            release.name.toLowerCase().includes(query)
        );
    });

    // Handle status filter change
    function handleStatusChange(e: React.ChangeEvent<HTMLSelectElement>) {
        setStatusFilter(e.target.value);
        setCurrentPage(1);
    }

    // Handle search input
    function handleSearchChange(e: React.ChangeEvent<HTMLInputElement>) {
        setSearchQuery(e.target.value);
    }

    // Handle page change
    function handlePageChange(page: number) {
        setCurrentPage(page);
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }

    // Handle create button click
    function handleCreateClick() {
        setShowCreateModal(true);
    }

    // Handle create success
    function handleCreateSuccess() {
        // Show success toast
        setToast({
            show: true,
            message: 'Release created successfully! It has been added to the list with DRAFT status.',
            type: 'success',
        });

        // Reload releases
        loadReleases();
    }

    // Handle modal close
    function handleModalClose() {
        setShowCreateModal(false);
    }

    // Handle toast close
    function handleToastClose() {
        setToast((prev) => ({ ...prev, show: false }));
    }

    // Handle delete button click
    function handleDeleteClick(release: Release, e: React.MouseEvent) {
        e.stopPropagation(); // Prevent row click navigation
        setReleaseToDelete(release);
        setShowDeleteModal(true);
    }

    // Handle delete confirmation
    async function handleDeleteConfirm() {
        if (!releaseToDelete) return;

        setIsDeleting(true);
        try {
            await releaseService.delete(releaseToDelete.id);
            
            setToast({
                show: true,
                message: `Release v${releaseToDelete.version} deleted successfully`,
                type: 'success',
            });

            // Close modal
            setShowDeleteModal(false);
            setReleaseToDelete(null);

            // Reload releases
            await loadReleases();
        } catch (err) {
            const errorMessage = err instanceof Error 
                ? err.message 
                : 'Failed to delete release. Please try again.';
            
            setToast({
                show: true,
                message: errorMessage,
                type: 'error',
            });

            // Close modal on error too
            setShowDeleteModal(false);
            setReleaseToDelete(null);
        } finally {
            setIsDeleting(false);
        }
    }

    // Handle delete modal close
    function handleDeleteModalClose() {
        if (!isDeleting) {
            setShowDeleteModal(false);
            setReleaseToDelete(null);
        }
    }

    // Navigate to detail page
    function handleReleaseClick(releaseId: number) {
        window.location.href = `/releases/${releaseId}`;
    }

    // Get status badge class
    function getStatusBadgeClass(status: string): string {
        switch (status) {
            case 'DRAFT':
                return 'bg-warning text-dark';
            case 'PUBLISHED':
                return 'bg-success';
            case 'ARCHIVED':
                return 'bg-secondary';
            default:
                return 'bg-secondary';
        }
    }

    // Format date
    function formatDate(dateString: string | null): string {
        if (!dateString) return 'N/A';
        const date = new Date(dateString);
        return date.toLocaleDateString();
    }

    // Render loading state
    if (loading && releases.length === 0) {
        return (
            <div className="container mt-4">
                <div className="d-flex justify-content-center align-items-center" style={{ minHeight: '400px' }}>
                    <div className="spinner-border text-primary" role="status">
                        <span className="visually-hidden">Loading...</span>
                    </div>
                </div>
            </div>
        );
    }

    // Render error state
    if (error) {
        return (
            <div className="container mt-4">
                <div className="alert alert-danger" role="alert">
                    <strong>Error:</strong> {error}
                    <button className="btn btn-sm btn-outline-danger ms-3" onClick={loadReleases}>
                        Retry
                    </button>
                </div>
            </div>
        );
    }

    // Render empty state
    if (!loading && filteredReleases.length === 0 && !debouncedSearch) {
        return (
            <div className="container mt-4">
                <div className="row mb-4">
                    <div className="col">
                        <h2>Releases</h2>
                    </div>
                    {canCreate && (
                        <div className="col-auto">
                            <button className="btn btn-primary" onClick={handleCreateClick}>
                                <i className="bi bi-plus-circle me-2"></i>
                                Create New Release
                            </button>
                        </div>
                    )}
                </div>
                
                <div className="empty-state text-center py-5">
                    <i className="bi bi-inbox" style={{ fontSize: '4rem', color: '#6c757d' }}></i>
                    <h4 className="mt-3">No releases found</h4>
                    <p className="text-muted">
                        {canCreate
                            ? 'Get started by creating your first release to snapshot requirements.'
                            : 'No releases have been created yet. Contact an administrator.'}
                    </p>
                    {canCreate && (
                        <button className="btn btn-primary mt-3" onClick={handleCreateClick}>
                            Create First Release
                        </button>
                    )}
                </div>
            </div>
        );
    }

    return (
        <div className="container mt-4">
            {/* Header with Create Button */}
            <div className="row mb-4">
                <div className="col">
                    <h2>Releases</h2>
                    <p className="text-muted">
                        Manage requirement version snapshots for compliance and audit purposes
                    </p>
                </div>
                {canCreate && (
                    <div className="col-auto">
                        <button className="btn btn-primary" onClick={handleCreateClick}>
                            <i className="bi bi-plus-circle me-2"></i>
                            Create New Release
                        </button>
                    </div>
                )}
            </div>

            {/* Filters and Search */}
            <div className="row mb-3">
                <div className="col-md-4">
                    <label htmlFor="statusFilter" className="form-label">
                        Status
                    </label>
                    <select
                        id="statusFilter"
                        className="form-select"
                        value={statusFilter}
                        onChange={handleStatusChange}
                        data-testid="status-filter"
                    >
                        <option value="ALL">All Statuses</option>
                        <option value="DRAFT">Draft</option>
                        <option value="PUBLISHED">Published</option>
                        <option value="ARCHIVED">Archived</option>
                    </select>
                </div>
                <div className="col-md-8">
                    <label htmlFor="searchBox" className="form-label">
                        Search
                    </label>
                    <input
                        id="searchBox"
                        type="search"
                        className="form-control"
                        placeholder="Search by version or name..."
                        value={searchQuery}
                        onChange={handleSearchChange}
                    />
                </div>
            </div>

            {/* Results count */}
            {loading && (
                <div className="text-center py-3">
                    <div className="spinner-border spinner-border-sm text-primary" role="status">
                        <span className="visually-hidden">Loading...</span>
                    </div>
                </div>
            )}

            {!loading && (
                <p className="text-muted mb-3">
                    Showing {filteredReleases.length} of {totalItems} releases
                </p>
            )}

            {/* Releases Table */}
            <div className="table-responsive">
                <table className="table table-hover">
                    <thead>
                        <tr>
                            <th>Version</th>
                            <th>Name</th>
                            <th>Status</th>
                            <th>Created By</th>
                            <th>Created Date</th>
                            <th>Requirements</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {filteredReleases.map((release) => (
                            <tr
                                key={release.id}
                                onClick={() => handleReleaseClick(release.id)}
                                style={{ cursor: 'pointer' }}
                                data-testid={`release-card-${release.id}`}
                            >
                                <td>
                                    <span data-testid="release-version">
                                        {release.version}
                                    </span>
                                </td>
                                <td>
                                    <a href={`/releases/${release.id}`} onClick={(e) => e.preventDefault()}>
                                        {release.name}
                                    </a>
                                </td>
                                <td>
                                    <span 
                                        className={`badge ${getStatusBadgeClass(release.status)}`}
                                        aria-label={`Release status: ${release.status}`}
                                    >
                                        {release.status}
                                    </span>
                                </td>
                                <td>{release.createdBy}</td>
                                <td>{formatDate(release.createdAt)}</td>
                                <td>
                                    <span className="badge bg-info">{release.requirementCount}</span>
                                </td>
                                <td onClick={(e) => e.stopPropagation()}>
                                    {canDeleteRelease(release, user, userRoles) && (
                                        <button
                                            className="btn btn-sm btn-outline-danger"
                                            onClick={(e) => handleDeleteClick(release, e)}
                                            title="Delete release"
                                            data-testid={`delete-release-${release.id}`}
                                        >
                                            <i className="bi bi-trash"></i>
                                        </button>
                                    )}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            {/* No results message (after search) */}
            {!loading && filteredReleases.length === 0 && debouncedSearch && (
                <div className="text-center py-4">
                    <p className="text-muted">No releases match your search criteria.</p>
                </div>
            )}

            {/* Pagination */}
            {totalPages > 1 && (
                <nav aria-label="Release pagination" className="mt-4">
                    <ul className="pagination justify-content-center" data-testid="pagination">
                        <li className={`page-item ${currentPage === 1 ? 'disabled' : ''}`}>
                            <button
                                className="page-link"
                                onClick={() => handlePageChange(currentPage - 1)}
                                disabled={currentPage === 1}
                                data-testid="prev-page"
                            >
                                Previous
                            </button>
                        </li>

                        {[...Array(totalPages)].map((_, index) => {
                            const page = index + 1;
                            // Show first page, last page, current page, and pages around current
                            if (
                                page === 1 ||
                                page === totalPages ||
                                (page >= currentPage - 2 && page <= currentPage + 2)
                            ) {
                                return (
                                    <li
                                        key={page}
                                        className={`page-item ${currentPage === page ? 'active' : ''}`}
                                    >
                                        <button
                                            className="page-link"
                                            onClick={() => handlePageChange(page)}
                                            data-testid={`page-${page}`}
                                        >
                                            {page}
                                        </button>
                                    </li>
                                );
                            } else if (page === currentPage - 3 || page === currentPage + 3) {
                                return (
                                    <li key={page} className="page-item disabled">
                                        <span className="page-link">...</span>
                                    </li>
                                );
                            }
                            return null;
                        })}

                        <li className={`page-item ${currentPage === totalPages ? 'disabled' : ''}`}>
                            <button
                                className="page-link"
                                onClick={() => handlePageChange(currentPage + 1)}
                                disabled={currentPage === totalPages}
                                data-testid="next-page"
                            >
                                Next
                            </button>
                        </li>
                    </ul>
                </nav>
            )}

            {/* Create Release Modal */}
            <ReleaseCreateModal
                isOpen={showCreateModal}
                onClose={handleModalClose}
                onSuccess={handleCreateSuccess}
            />

            {/* Delete Release Modal */}
            <ReleaseDeleteConfirm
                release={releaseToDelete}
                isOpen={showDeleteModal}
                isDeleting={isDeleting}
                onClose={handleDeleteModalClose}
                onConfirm={handleDeleteConfirm}
            />

            {/* Toast Notifications */}
            <Toast
                message={toast.message}
                type={toast.type}
                show={toast.show}
                onClose={handleToastClose}
            />
        </div>
    );
};

export default ReleaseList;
