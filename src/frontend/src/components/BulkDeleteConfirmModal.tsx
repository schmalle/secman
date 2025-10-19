import React, { useState, useEffect } from 'react';

/**
 * Confirmation modal for bulk asset deletion
 * Feature: 029-asset-bulk-operations (User Story 1 - Bulk Delete Assets)
 *
 * Related Requirements:
 * - FR-002: Display confirmation modal with warning about irreversible data loss
 * - FR-004: Display success message after bulk delete completes
 *
 * Pattern: Checkbox acknowledgment (from research.md - React modal patterns)
 * - User must check "I understand" before delete button is enabled
 * - ESC key closes modal (but disabled during deletion operation)
 * - Loading state with spinner during deletion
 *
 * Props:
 * - isOpen: Whether modal is visible
 * - onClose: Callback to close modal
 * - onConfirm: Callback when user confirms deletion
 * - isDeleting: Whether deletion is in progress
 * - assetCount: Number of assets to be deleted
 */

interface BulkDeleteConfirmModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  isDeleting: boolean;
  assetCount: number;
}

export function BulkDeleteConfirmModal({
  isOpen,
  onClose,
  onConfirm,
  isDeleting,
  assetCount
}: BulkDeleteConfirmModalProps) {
  const [acknowledged, setAcknowledged] = useState(false);

  // Reset acknowledgment when modal opens
  useEffect(() => {
    if (isOpen) {
      setAcknowledged(false);
    }
  }, [isOpen]);

  // Handle ESC key (disabled during deletion)
  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !isDeleting) {
        onClose();
      }
    };

    if (isOpen) {
      document.addEventListener('keydown', handleEscape);
      return () => document.removeEventListener('keydown', handleEscape);
    }
  }, [isOpen, isDeleting, onClose]);

  if (!isOpen) return null;

  const handleConfirm = () => {
    if (acknowledged && !isDeleting) {
      onConfirm();
    }
  };

  return (
    <div className="modal show d-block" tabIndex={-1} role="dialog" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
      <div className="modal-dialog modal-dialog-centered" role="document">
        <div className="modal-content">
          <div className="modal-header bg-danger text-white">
            <h5 className="modal-title">
              <i className="bi bi-exclamation-triangle-fill me-2"></i>
              Delete All Assets
            </h5>
            {!isDeleting && (
              <button type="button" className="btn-close btn-close-white" onClick={onClose} aria-label="Close"></button>
            )}
          </div>

          <div className="modal-body">
            <div className="alert alert-danger" role="alert">
              <strong>Warning:</strong> This action cannot be undone!
            </div>

            <p className="mb-3">
              You are about to permanently delete <strong>{assetCount}</strong> asset{assetCount !== 1 ? 's' : ''} along with:
            </p>

            <ul className="mb-3">
              <li>All associated vulnerabilities</li>
              <li>All scan results</li>
              <li>All workgroup associations</li>
            </ul>

            <p className="text-muted small mb-3">
              This operation will delete all assets from the system. If you need to preserve this data, please export assets before proceeding.
            </p>

            <div className="form-check">
              <input
                className="form-check-input"
                type="checkbox"
                id="acknowledgeDelete"
                checked={acknowledged}
                onChange={(e) => setAcknowledged(e.target.checked)}
                disabled={isDeleting}
              />
              <label className="form-check-label" htmlFor="acknowledgeDelete">
                I understand this action is irreversible and will delete all assets
              </label>
            </div>

            {isDeleting && (
              <div className="mt-3 text-center">
                <div className="spinner-border text-danger" role="status">
                  <span className="visually-hidden">Deleting...</span>
                </div>
                <p className="mt-2 text-muted">Deleting all assets... Please wait.</p>
              </div>
            )}
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={onClose}
              disabled={isDeleting}
            >
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-danger"
              onClick={handleConfirm}
              disabled={!acknowledged || isDeleting}
            >
              {isDeleting ? (
                <>
                  <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                  Deleting...
                </>
              ) : (
                <>
                  <i className="bi bi-trash3-fill me-2"></i>
                  Delete All Assets
                </>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
