import React, { useState, useEffect } from 'react';
import type { WorkgroupResponse } from '../services/workgroupApi';
import { createChildWorkgroup } from '../services/workgroupApi';

/**
 * Create Child Workgroup Modal Component
 * Feature 040: Nested Workgroups (User Story 1)
 *
 * Modal dialog for creating a child workgroup under a parent.
 * Validates:
 * - Name is required (1-255 characters)
 * - Description is optional (max 1000 characters)
 * - Parent must not be at maximum depth (5 levels)
 */

interface CreateChildWorkgroupModalProps {
  show: boolean;
  parentWorkgroup: WorkgroupResponse | null;
  onClose: () => void;
  onSuccess: (child: WorkgroupResponse) => void;
}

const CreateChildWorkgroupModal: React.FC<CreateChildWorkgroupModalProps> = ({
  show,
  parentWorkgroup,
  onClose,
  onSuccess
}) => {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Reset form when modal is opened/closed
  useEffect(() => {
    if (show) {
      setName('');
      setDescription('');
      setError(null);
    }
  }, [show]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!parentWorkgroup) {
      setError('Parent workgroup is required');
      return;
    }

    // Client-side validation
    if (!name.trim()) {
      setError('Name is required');
      return;
    }

    if (name.length > 255) {
      setError('Name must not exceed 255 characters');
      return;
    }

    if (description.length > 1000) {
      setError('Description must not exceed 1000 characters');
      return;
    }

    // Check depth limit
    if (parentWorkgroup.depth >= 5) {
      setError('Cannot create child: parent is at maximum depth (5 levels)');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const child = await createChildWorkgroup(parentWorkgroup.id, {
        name: name.trim(),
        description: description.trim() || undefined
      });

      onSuccess(child);
      onClose();
    } catch (err) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('Failed to create child workgroup');
      }
      console.error('Failed to create child workgroup:', err);
    } finally {
      setLoading(false);
    }
  };

  if (!show) return null;

  return (
    <>
      {/* Modal backdrop */}
      <div
        className="modal-backdrop fade show"
        onClick={onClose}
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
            <div className="modal-header">
              <h5 className="modal-title">
                <i className="bi bi-plus-circle me-2"></i>
                Create Child Workgroup
              </h5>
              <button
                type="button"
                className="btn-close"
                onClick={onClose}
                disabled={loading}
              ></button>
            </div>

            <form onSubmit={handleSubmit}>
              <div className="modal-body">
                {/* Parent info */}
                {parentWorkgroup && (
                  <div className="alert alert-info">
                    <strong>Parent:</strong> {parentWorkgroup.name}
                    <br />
                    <small className="text-muted">
                      Current depth: {parentWorkgroup.depth} / 5
                      {parentWorkgroup.depth >= 5 && (
                        <span className="text-danger ms-2">
                          (Maximum depth reached)
                        </span>
                      )}
                    </small>
                    {parentWorkgroup.ancestors.length > 0 && (
                      <div className="mt-2">
                        <small className="text-muted">
                          Path: {`${parentWorkgroup.ancestors.map(a => a.name).join(' > ')} > ${parentWorkgroup.name}`}
                        </small>
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

                {/* Name field */}
                <div className="mb-3">
                  <label htmlFor="childName" className="form-label">
                    Name <span className="text-danger">*</span>
                  </label>
                  <input
                    type="text"
                    className="form-control"
                    id="childName"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="e.g., Engineering Team"
                    maxLength={255}
                    required
                    disabled={loading}
                    autoFocus
                  />
                  <small className="text-muted">
                    {name.length} / 255 characters
                  </small>
                </div>

                {/* Description field */}
                <div className="mb-3">
                  <label htmlFor="childDescription" className="form-label">
                    Description <span className="text-muted">(optional)</span>
                  </label>
                  <textarea
                    className="form-control"
                    id="childDescription"
                    rows={3}
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder="Optional description for this workgroup"
                    maxLength={1000}
                    disabled={loading}
                  ></textarea>
                  <small className="text-muted">
                    {description.length} / 1000 characters
                  </small>
                </div>

                {/* Validation hints */}
                <div className="border-top pt-3">
                  <h6 className="text-muted small mb-2">Validation Rules:</h6>
                  <ul className="small text-muted mb-0">
                    <li>Name must be unique among siblings</li>
                    <li>Maximum nesting depth: 5 levels</li>
                    <li>Child will be at depth {parentWorkgroup ? parentWorkgroup.depth + 1 : '?'}</li>
                  </ul>
                </div>
              </div>

              <div className="modal-footer">
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={onClose}
                  disabled={loading}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={loading || !name.trim() || (parentWorkgroup?.depth ?? 0) >= 5}
                >
                  {loading ? (
                    <>
                      <span className="spinner-border spinner-border-sm me-2" role="status"></span>
                      Creating...
                    </>
                  ) : (
                    <>
                      <i className="bi bi-plus-circle me-2"></i>
                      Create Child
                    </>
                  )}
                </button>
              </div>
            </form>
          </div>
        </div>
      </div>
    </>
  );
};

export default CreateChildWorkgroupModal;
