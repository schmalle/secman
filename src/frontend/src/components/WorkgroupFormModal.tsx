import React, { useState } from 'react';
import { postJson, putJson, ApiError } from '../utils/apiJson';
import type { Workgroup, WorkgroupCriticality } from './workgroupTypes';

type WorkgroupFormData = { name: string; description: string; criticality: WorkgroupCriticality };

/** The three editable fields — split out to keep the modal component itself short. */
const WorkgroupFormFields: React.FC<{
  formData: WorkgroupFormData;
  onChange: (next: WorkgroupFormData) => void;
}> = ({ formData, onChange }) => (
  <>
    <div className="mb-3">
      <label className="form-label">Name *</label>
      <input
        type="text"
        className="form-control"
        value={formData.name}
        onChange={(e) => onChange({ ...formData, name: e.target.value })}
        required
        pattern="[-a-zA-Z0-9 ]+"
        maxLength={100}
        title="Name must contain only letters, numbers, spaces, and hyphens"
      />
      <small className="text-muted">1-100 characters, alphanumeric + spaces + hyphens</small>
    </div>
    <div className="mb-3">
      <label className="form-label">Description</label>
      <textarea
        className="form-control"
        value={formData.description}
        onChange={(e) => onChange({ ...formData, description: e.target.value })}
        rows={3}
        maxLength={512}
      />
      <small className="text-muted">Optional, max 512 characters</small>
    </div>
    <div className="mb-3">
      <label className="form-label">Criticality *</label>
      <select
        className="form-select"
        value={formData.criticality}
        onChange={(e) => onChange({ ...formData, criticality: e.target.value as WorkgroupCriticality })}
        required
      >
        <option value="CRITICAL">CRITICAL</option>
        <option value="HIGH">HIGH</option>
        <option value="MEDIUM">MEDIUM</option>
        <option value="LOW">LOW</option>
        <option value="NA">N/A</option>
      </select>
      <small className="text-muted">Security criticality classification for this workgroup</small>
    </div>
  </>
);

interface WorkgroupFormModalProps {
  /** Workgroup being edited, or null to create a new one. */
  workgroup: Workgroup | null;
  onClose: () => void;
  /** Called after a successful create/update; parent refetches and closes. */
  onSaved: () => void | Promise<void>;
  onError: (message: string) => void;
}

/** Read-only parent/path/level context shown when editing (moves happen in the Tree View). */
const WorkgroupHierarchyInfo: React.FC<{ workgroup: Workgroup }> = ({ workgroup }) => (
  <div className="mb-3 pb-3 border-bottom">
    <div className="alert alert-info mb-0">
      <h6 className="alert-heading mb-2">
        <i className="bi bi-diagram-3 me-2"></i>
        Hierarchy Information
      </h6>
      {workgroup.parentId ? (
        <>
          <div className="mb-1">
            <strong>Parent Workgroup:</strong>{' '}
            {workgroup.parentName || `ID ${workgroup.parentId}`}
          </div>
          {workgroup.ancestors && workgroup.ancestors.length > 0 && (
            <div>
              <strong>Full Path:</strong>{' '}
              <span className="text-muted">
                {workgroup.ancestors.map(a => a.name).join(' > ')} &gt; {workgroup.name}
              </span>
            </div>
          )}
          {workgroup.depth && (
            <div className="mt-1">
              <span className="badge bg-secondary">Level {workgroup.depth}</span>
            </div>
          )}
        </>
      ) : (
        <div>
          <strong>Location:</strong> Root level (no parent)
          {workgroup.depth && (
            <span className="badge bg-secondary ms-2">Level {workgroup.depth}</span>
          )}
        </div>
      )}
      <div className="mt-2">
        <small className="text-muted">
          <i className="bi bi-info-circle me-1"></i>
          To move this workgroup to a different parent, use the Tree View and click "Move"
        </small>
      </div>
    </div>
  </div>
);

/**
 * Create/edit dialog for a workgroup, extracted from WorkgroupManagement.tsx.
 * Owns its own form state (seeded from the workgroup being edited) and the
 * create/update API calls; the parent only learns "saved" or "closed".
 */
const WorkgroupFormModal: React.FC<WorkgroupFormModalProps> = ({ workgroup, onClose, onSaved, onError }) => {
  const [formData, setFormData] = useState<WorkgroupFormData>({
    name: workgroup?.name ?? '',
    description: workgroup?.description ?? '',
    criticality: workgroup?.criticality ?? 'MEDIUM',
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    try {
      if (workgroup) {
        await putJson(`/api/workgroups/${workgroup.id}`, formData, 'Failed to update workgroup');
      } else {
        await postJson('/api/workgroups', formData, 'Failed to create workgroup');
      }
      await onSaved();
    } catch (err) {
      onError(err instanceof ApiError || err instanceof Error ? err.message : 'An error occurred');
    }
  };

  return (
    <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'var(--scand-overlay)' }}>
      <div className="modal-dialog">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title">{workgroup ? 'Edit Workgroup' : 'Create Workgroup'}</h5>
            <button type="button" className="btn-close" onClick={onClose}></button>
          </div>
          <form onSubmit={handleSubmit}>
            <div className="modal-body">
              {/* Parent Workgroup Info (read-only, only shown when editing) */}
              {workgroup && <WorkgroupHierarchyInfo workgroup={workgroup} />}
              <WorkgroupFormFields formData={formData} onChange={setFormData} />
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-secondary" onClick={onClose}>
                Cancel
              </button>
              <button type="submit" className="btn btn-primary">
                {workgroup ? 'Update' : 'Create'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
};

export default WorkgroupFormModal;
