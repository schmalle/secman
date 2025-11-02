import React, { useState } from 'react';
import type { WorkgroupResponse } from '../services/workgroupApi';
import { deleteWorkgroup } from '../services/workgroupApi';

/**
 * Delete Workgroup Confirmation Modal Component
 * Feature 040: Nested Workgroups (User Story 4)
 *
 * Modal dialog for confirming workgroup deletion with child promotion.
 * When deleted:
 * - Children are promoted to grandparent level
 * - If root-level, children become root-level
 * - All users and assets remain assigned
 */

interface DeleteWorkgroupConfirmationProps {
  show: boolean;
  workgroup: WorkgroupResponse | null;
  onClose: () => void;
  onSuccess: () => void;
}

const DeleteWorkgroupConfirmation: React.FC<DeleteWorkgroupConfirmationProps> = ({
  show,
  workgroup,
  onClose,
  onSuccess
}) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmText, setConfirmText] = useState('');

  const handleDelete = async () => {
    if (!workgroup) {
      setError('Workgroup is required');
      return;
    }

    if (confirmText !== workgroup.name) {
      setError('Confirmation text does not match workgroup name');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      await deleteWorkgroup(workgroup.id);
      onSuccess();
      onClose();
    } catch (err) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('Failed to delete workgroup');
      }
      console.error('Failed to delete workgroup:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleClose = () => {
    setConfirmText('');
    setError(null);
    onClose();
  };

  if (!show || !workgroup) return null;

  const hasChildren = workgroup.childCount > 0;
  const hasParent = workgroup.parentId !== null && workgroup.parentId !== undefined;
  const promotionTarget = hasParent
    ? workgroup.ancestors.length > 0
      ? workgroup.ancestors[workgroup.ancestors.length - 1].name
      : 'grandparent'
    : 'root level';

  return (
    <>
      {/* Modal backdrop */}
      <div
        className="modal-backdrop fade show"
        onClick={handleClose}
        style={{ zIndex: 1040 }}
      ></div>

      {/* Modal dialog */}
      <div
        className="modal fade show d-block"
        tabIndex={-1}
        style={{ zIndex: 1050 }}
      >
        <div className="modal-dialog modal-dialog-centered">
          <div className="modal-content">
            <div className="modal-header bg-danger text-white">
              <h5 className="modal-title">
                <i className="bi bi-exclamation-triangle-fill me-2"></i>
                Delete Workgroup
              </h5>
              <button
                type="button"
                className="btn-close btn-close-white"
                onClick={handleClose}
                disabled={loading}
              ></button>
            </div>

            <div className="modal-body">
              {/* Warning message */}
              <div className="alert alert-warning">
                <strong>Warning:</strong> You are about to delete the following workgroup:
              </div>

              {/* Workgroup info */}
              <div className="card mb-3">
                <div className="card-body">
                  <h6 className="card-title">
                    <i className="bi bi-folder2"></i> {workgroup.name}
                  </h6>
                  {workgroup.description && (
                    <p className="card-text text-muted small">{workgroup.description}</p>
                  )}
                  <div className="small text-muted">
                    <div>
                      <strong>Location:</strong>{' '}
                      {workgroup.ancestors.length > 0
                        ? workgroup.ancestors.map(a => a.name).join(' > ') + ' > ' + workgroup.name
                        : 'Root level'}
                    </div>
                    <div>
                      <strong>Depth:</strong> Level {workgroup.depth}
                    </div>
                    {hasChildren && (
                      <div className="text-info">
                        <strong>Children:</strong> {workgroup.childCount} workgroup{workgroup.childCount === 1 ? '' : 's'}
                      </div>
                    )}
                  </div>
                </div>
              </div>

              {/* Child promotion info */}
              {hasChildren && (
                <div className="alert alert-info">
                  <i className="bi bi-info-circle"></i>{' '}
                  <strong>Child Promotion:</strong><br />
                  The {workgroup.childCount} child workgroup{workgroup.childCount === 1 ? '' : 's'} will be
                  promoted to <strong>{promotionTarget}</strong>.
                  {!hasParent && (
                    <div className="mt-2">
                      (Children will become root-level workgroups)
                    </div>
                  )}
                </div>
              )}

              {/* Error alert */}
              {error && (
                <div className="alert alert-danger">
                  <i className="bi bi-exclamation-triangle"></i> {error}
                </div>
              )}

              {/* Confirmation input */}
              <div className="mb-3">
                <label htmlFor="confirmText" className="form-label">
                  Type <code>{workgroup.name}</code> to confirm deletion:
                </label>
                <input
                  type="text"
                  className="form-control"
                  id="confirmText"
                  value={confirmText}
                  onChange={(e) => setConfirmText(e.target.value)}
                  placeholder={workgroup.name}
                  disabled={loading}
                  autoFocus
                />
              </div>

              {/* Additional warnings */}
              <div className="border-top pt-3">
                <h6 className="text-danger small mb-2">This action will:</h6>
                <ul className="small text-muted mb-0">
                  <li>Permanently delete this workgroup</li>
                  {hasChildren && (
                    <li>Promote {workgroup.childCount} child workgroup{workgroup.childCount === 1 ? '' : 's'} to {promotionTarget}</li>
                  )}
                  <li>Remove all user assignments to this workgroup</li>
                  <li>Remove all asset assignments to this workgroup</li>
                  <li className="text-danger"><strong>This action cannot be undone</strong></li>
                </ul>
              </div>
            </div>

            <div className="modal-footer">
              <button
                type="button"
                className="btn btn-secondary"
                onClick={handleClose}
                disabled={loading}
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-danger"
                onClick={handleDelete}
                disabled={loading || confirmText !== workgroup.name}
              >
                {loading ? (
                  <>
                    <span className="spinner-border spinner-border-sm me-2" role="status"></span>
                    Deleting...
                  </>
                ) : (
                  <>
                    <i className="bi bi-trash me-2"></i>
                    Delete Workgroup
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

export default DeleteWorkgroupConfirmation;
