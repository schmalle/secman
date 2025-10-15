import React, { useState, useEffect } from 'react';
import UserMappingUpload from './UserMappingUpload';
import IpMappingForm from './IpMappingForm';
import IpMappingTable from './IpMappingTable';
import Pagination from './Pagination';
import type { UserMapping, CreateUserMappingRequest } from '../services/userMappingService';
import {
  listUserMappings,
  createUserMapping,
  updateUserMapping,
  deleteUserMapping
} from '../services/userMappingService';

type ViewMode = 'list' | 'create' | 'edit';

interface Toast {
  id: number;
  type: 'success' | 'error' | 'info';
  message: string;
}

/**
 * Comprehensive User Mapping Management Component
 * Feature: 020-i-want-to (IP Address Mapping)
 *
 * Provides:
 * - Tab 1: Bulk upload (Excel/CSV)
 * - Tab 2: CRUD management for individual IP mappings
 *   - List view with pagination
 *   - Create new mapping
 *   - Edit existing mapping
 *   - Delete mapping
 */
export default function UserMappingManager() {
  // Tab state
  const [activeTab, setActiveTab] = useState<'upload' | 'manage'>('manage');

  // View mode state
  const [viewMode, setViewMode] = useState<ViewMode>('list');
  const [editingMapping, setEditingMapping] = useState<UserMapping | undefined>(undefined);

  // Data state
  const [mappings, setMappings] = useState<UserMapping[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  // Pagination state
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [pageSize] = useState(20);

  // Filter state
  const [emailFilter, setEmailFilter] = useState('');
  const [domainFilter, setDomainFilter] = useState('');

  // Toast notifications
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [nextToastId, setNextToastId] = useState(1);

  // Load mappings on mount and when filters/pagination change
  useEffect(() => {
    if (activeTab === 'manage' && viewMode === 'list') {
      loadMappings();
    }
  }, [activeTab, viewMode, currentPage, emailFilter, domainFilter]);

  /**
   * Load mappings from backend
   */
  const loadMappings = async () => {
    setIsLoading(true);
    try {
      const response = await listUserMappings(
        currentPage,
        pageSize,
        emailFilter || undefined,
        domainFilter || undefined
      );

      setMappings(response.content);
      setTotalPages(response.totalPages);
      setTotalElements(response.totalElements);
    } catch (error) {
      showToast('error', error instanceof Error ? error.message : 'Failed to load mappings');
    } finally {
      setIsLoading(false);
    }
  };

  /**
   * Show toast notification
   */
  const showToast = (type: 'success' | 'error' | 'info', message: string) => {
    const toast: Toast = {
      id: nextToastId,
      type,
      message,
    };

    setToasts((prev) => [...prev, toast]);
    setNextToastId((prev) => prev + 1);

    // Auto-dismiss after 5 seconds
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== toast.id));
    }, 5000);
  };

  /**
   * Dismiss toast
   */
  const dismissToast = (id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  };

  /**
   * Handle create new mapping
   */
  const handleCreateClick = () => {
    setEditingMapping(undefined);
    setViewMode('create');
  };

  /**
   * Handle edit mapping
   */
  const handleEditClick = (mapping: UserMapping) => {
    setEditingMapping(mapping);
    setViewMode('edit');
  };

  /**
   * Handle delete mapping
   */
  const handleDeleteMapping = async (mapping: UserMapping) => {
    try {
      await deleteUserMapping(mapping.id);
      showToast('success', 'Mapping deleted successfully');
      await loadMappings();
    } catch (error) {
      showToast('error', error instanceof Error ? error.message : 'Failed to delete mapping');
    }
  };

  /**
   * Handle form submit (create or update)
   */
  const handleFormSubmit = async (request: CreateUserMappingRequest) => {
    try {
      if (viewMode === 'create') {
        await createUserMapping(request);
        showToast('success', 'Mapping created successfully');
      } else if (viewMode === 'edit' && editingMapping) {
        await updateUserMapping(editingMapping.id, request);
        showToast('success', 'Mapping updated successfully');
      }

      setViewMode('list');
      setEditingMapping(undefined);
      await loadMappings();
    } catch (error) {
      throw error; // Let the form component handle the error
    }
  };

  /**
   * Handle form cancel
   */
  const handleFormCancel = () => {
    setViewMode('list');
    setEditingMapping(undefined);
  };

  /**
   * Handle page change
   */
  const handlePageChange = (page: number) => {
    setCurrentPage(page);
  };

  /**
   * Handle filter change
   */
  const handleFilterChange = () => {
    setCurrentPage(0); // Reset to first page when filtering
  };

  /**
   * Get toast icon
   */
  const getToastIcon = (type: string) => {
    switch (type) {
      case 'success':
        return 'bi-check-circle-fill';
      case 'error':
        return 'bi-exclamation-circle-fill';
      case 'info':
        return 'bi-info-circle-fill';
      default:
        return 'bi-info-circle-fill';
    }
  };

  /**
   * Get toast background class
   */
  const getToastBgClass = (type: string) => {
    switch (type) {
      case 'success':
        return 'bg-success';
      case 'error':
        return 'bg-danger';
      case 'info':
        return 'bg-info';
      default:
        return 'bg-secondary';
    }
  };

  return (
    <div className="user-mapping-manager">
      {/* Toast Container */}
      <div className="toast-container position-fixed top-0 end-0 p-3" style={{ zIndex: 9999 }}>
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={`toast show ${getToastBgClass(toast.type)} text-white`}
            role="alert"
            aria-live="assertive"
            aria-atomic="true"
          >
            <div className="toast-header">
              <i className={`bi ${getToastIcon(toast.type)} me-2`}></i>
              <strong className="me-auto">
                {toast.type.charAt(0).toUpperCase() + toast.type.slice(1)}
              </strong>
              <button
                type="button"
                className="btn-close"
                onClick={() => dismissToast(toast.id)}
                aria-label="Close"
              ></button>
            </div>
            <div className="toast-body">{toast.message}</div>
          </div>
        ))}
      </div>

      {/* Page Header */}
      <div className="row mb-4">
        <div className="col">
          <h2 className="mb-1">User Mapping Management</h2>
          <p className="text-muted">
            Map users to AWS accounts and IP addresses for access control
          </p>
        </div>
      </div>

      {/* Tabs */}
      <ul className="nav nav-tabs mb-4" role="tablist">
        <li className="nav-item" role="presentation">
          <button
            className={`nav-link ${activeTab === 'upload' ? 'active' : ''}`}
            onClick={() => setActiveTab('upload')}
            type="button"
            role="tab"
          >
            <i className="bi bi-upload me-2"></i>
            Bulk Upload
          </button>
        </li>
        <li className="nav-item" role="presentation">
          <button
            className={`nav-link ${activeTab === 'manage' ? 'active' : ''}`}
            onClick={() => setActiveTab('manage')}
            type="button"
            role="tab"
          >
            <i className="bi bi-table me-2"></i>
            Manage Mappings
          </button>
        </li>
      </ul>

      {/* Tab Content */}
      <div className="tab-content">
        {/* Upload Tab */}
        {activeTab === 'upload' && (
          <div className="tab-pane fade show active">
            <UserMappingUpload />
          </div>
        )}

        {/* Manage Tab */}
        {activeTab === 'manage' && (
          <div className="tab-pane fade show active">
            {viewMode === 'list' && (
              <>
                {/* Toolbar */}
                <div className="row mb-3">
                  <div className="col-md-8">
                    <div className="row g-2">
                      <div className="col-md-6">
                        <input
                          type="text"
                          className="form-control"
                          placeholder="Filter by email..."
                          value={emailFilter}
                          onChange={(e) => setEmailFilter(e.target.value)}
                          onKeyDown={(e) => e.key === 'Enter' && handleFilterChange()}
                        />
                      </div>
                      <div className="col-md-4">
                        <input
                          type="text"
                          className="form-control"
                          placeholder="Filter by domain..."
                          value={domainFilter}
                          onChange={(e) => setDomainFilter(e.target.value)}
                          onKeyDown={(e) => e.key === 'Enter' && handleFilterChange()}
                        />
                      </div>
                      <div className="col-md-2">
                        <button
                          type="button"
                          className="btn btn-outline-secondary w-100"
                          onClick={handleFilterChange}
                        >
                          <i className="bi bi-search"></i>
                        </button>
                      </div>
                    </div>
                  </div>
                  <div className="col-md-4 text-end">
                    <button
                      type="button"
                      className="btn btn-primary"
                      onClick={handleCreateClick}
                    >
                      <i className="bi bi-plus-circle me-2"></i>
                      Create New Mapping
                    </button>
                  </div>
                </div>

                {/* Table */}
                <IpMappingTable
                  mappings={mappings}
                  onEdit={handleEditClick}
                  onDelete={handleDeleteMapping}
                  isLoading={isLoading}
                />

                {/* Pagination */}
                <Pagination
                  currentPage={currentPage}
                  totalPages={totalPages}
                  totalElements={totalElements}
                  pageSize={pageSize}
                  onPageChange={handlePageChange}
                />
              </>
            )}

            {(viewMode === 'create' || viewMode === 'edit') && (
              <IpMappingForm
                onSubmit={handleFormSubmit}
                onCancel={handleFormCancel}
                initialData={editingMapping}
                isEditing={viewMode === 'edit'}
              />
            )}
          </div>
        )}
      </div>
    </div>
  );
}
