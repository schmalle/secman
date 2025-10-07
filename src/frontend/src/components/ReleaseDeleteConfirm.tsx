import React from 'react';

interface ReleaseDeleteConfirmProps {
  release: {
    id: number;
    version: string;
    name: string;
  } | null;
  isOpen: boolean;
  isDeleting?: boolean;
  onClose: () => void;
  onConfirm: () => void;
}

/**
 * Confirmation modal for deleting a release
 * 
 * Shows warning message about permanent deletion and cascading deletion of snapshots
 * Provides Cancel and Confirm actions
 */
const ReleaseDeleteConfirm: React.FC<ReleaseDeleteConfirmProps> = ({
  release,
  isOpen,
  isDeleting = false,
  onClose,
  onConfirm,
}) => {
  if (!isOpen || !release) return null;

  return (
    <>
      {/* Bootstrap modal backdrop */}
      <div 
        className={`modal-backdrop fade ${isOpen ? 'show' : ''}`}
        onClick={onClose}
        style={{ display: isOpen ? 'block' : 'none' }}
      />
      
      {/* Bootstrap modal */}
      <div
        className={`modal fade ${isOpen ? 'show' : ''}`}
        style={{ display: isOpen ? 'block' : 'none' }}
        data-testid="delete-confirm-modal"
        tabIndex={-1}
        role="dialog"
        aria-labelledby="deleteModalLabel"
        aria-hidden={!isOpen}
      >
        <div className="modal-dialog modal-dialog-centered" role="document">
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title" id="deleteModalLabel">
                <i className="bi bi-exclamation-triangle-fill text-danger me-2"></i>
                Confirm Delete Release
              </h5>
              <button
                type="button"
                className="btn-close"
                onClick={onClose}
                disabled={isDeleting}
                aria-label="Close"
              />
            </div>
            
            <div className="modal-body">
              <p className="mb-3">
                Are you sure you want to delete release <strong>v{release.version}</strong>
                {release.name && ` - ${release.name}`}?
              </p>
              
              <div className="alert alert-danger mb-0" role="alert">
                <strong>Warning:</strong> This action cannot be undone.
                <ul className="mb-0 mt-2">
                  <li>All requirement snapshots for this release will be permanently removed</li>
                  <li>Historical exports referencing this release will no longer work</li>
                  <li>Comparisons involving this release will be affected</li>
                </ul>
              </div>
            </div>
            
            <div className="modal-footer">
              <button
                type="button"
                className="btn btn-secondary"
                onClick={onClose}
                disabled={isDeleting}
                data-testid="cancel-delete"
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-danger"
                onClick={onConfirm}
                disabled={isDeleting}
                data-testid="confirm-delete"
              >
                {isDeleting ? (
                  <>
                    <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                    Deleting...
                  </>
                ) : (
                  <>
                    <i className="bi bi-trash me-2"></i>
                    Delete Release
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      </div>
    </>
  );
};

export default ReleaseDeleteConfirm;
