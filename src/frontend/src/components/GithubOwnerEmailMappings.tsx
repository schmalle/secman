import React, { useEffect, useRef, useState } from 'react';
import { formatServerDate } from '../utils/dateUtils';
import {
  getOwnerEmailMappings,
  createOwnerEmailMapping,
  updateOwnerEmailMapping,
  deleteOwnerEmailMapping,
  uploadOwnerEmailMappingsCsv,
  type GithubOwnerEmailMapping,
} from '../services/githubReposService';
import { hasRole, hasVulnAccess } from '../utils/auth';
import { useClientHasVulnAccess } from '../utils/useClientAuth';

/**
 * Vulnerability Management → GitHub → "Owner email mappings" tab: default
 * notification email per GitHub owner (org/user login). Creating or editing
 * a mapping backfills `ownerEmail` on existing repos under that owner that
 * don't already have one set — repos with a manually-set email are never
 * overwritten.
 *
 * View: ADMIN/VULN/SECCHAMPION. Create/edit/delete/CSV upload: ADMIN/VULN
 * (mirrors the backend @Secured rules).
 */
const GithubOwnerEmailMappings: React.FC = () => {
  const [mappings, setMappings] = useState<GithubOwnerEmailMapping[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const [canManage, setCanManage] = useState(false);
  const hasAccess = useClientHasVulnAccess();

  const [newOwner, setNewOwner] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [creating, setCreating] = useState(false);

  const [editingId, setEditingId] = useState<number | null>(null);
  const [emailDraft, setEmailDraft] = useState('');
  const [saving, setSaving] = useState(false);

  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const load = () => {
    setLoading(true);
    getOwnerEmailMappings()
      .then((data) => {
        setMappings(data);
        setError(null);
      })
      .catch((err) => setError(err instanceof Error ? err.message : 'Failed to load mappings'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    setCanManage(hasRole(['ADMIN', 'VULN']));
    // Calls hasVulnAccess() directly rather than reading the `hasAccess` state:
    // that state is deliberately false on the first render (hydration-safety), and
    // this effect runs once, so trusting it here would deny access to everyone.
    if (!hasVulnAccess()) {
      setError('You do not have permission to view GitHub owner email mappings.');
      setLoading(false);
      return;
    }
    load();
  }, []);

  const handleCreate = async () => {
    if (!newOwner.trim() || !newEmail.trim()) return;
    setCreating(true);
    setError(null);
    setInfo(null);
    try {
      await createOwnerEmailMapping(newOwner.trim(), newEmail.trim());
      setNewOwner('');
      setNewEmail('');
      setInfo('Mapping created. Existing repos for this owner without an owner email were backfilled.');
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create mapping');
    } finally {
      setCreating(false);
    }
  };

  const startEdit = (mapping: GithubOwnerEmailMapping) => {
    setEditingId(mapping.id);
    setEmailDraft(mapping.email);
  };

  const saveEdit = async (mapping: GithubOwnerEmailMapping) => {
    if (!emailDraft.trim()) return;
    setSaving(true);
    try {
      await updateOwnerEmailMapping(mapping.id, emailDraft.trim());
      setEditingId(null);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update mapping');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (mapping: GithubOwnerEmailMapping) => {
    try {
      await deleteOwnerEmailMapping(mapping.id);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete mapping');
    }
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploading(true);
    setError(null);
    setInfo(null);
    try {
      const result = await uploadOwnerEmailMappingsCsv(file);
      setInfo(
        `CSV import complete: ${result.imported} imported, ${result.skipped} skipped` +
          (result.errors.length > 0 ? ` (${result.errors.length} errors)` : '')
      );
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'CSV upload failed');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  return (
    <div>
      {error && (
        <div className="alert alert-danger alert-dismissible fade show" role="alert">
          <i className="bi bi-exclamation-triangle me-2"></i>
          {error}
          <button type="button" className="btn-close" onClick={() => setError(null)} aria-label="Close"></button>
        </div>
      )}
      {info && (
        <div className="alert alert-success alert-dismissible fade show" role="alert">
          <i className="bi bi-check-circle me-2"></i>
          {info}
          <button type="button" className="btn-close" onClick={() => setInfo(null)} aria-label="Close"></button>
        </div>
      )}

      {canManage && (
        <div className="card mb-3">
          <div className="card-body">
            <h6 className="card-title">Add a mapping</h6>
            <div className="row g-2 align-items-end">
              <div className="col-md-4">
                <label className="form-label small">Owner (org or user login)</label>
                <input
                  type="text"
                  className="form-control"
                  value={newOwner}
                  onChange={(e) => setNewOwner(e.target.value)}
                  placeholder="acme-corp"
                  disabled={creating}
                />
              </div>
              <div className="col-md-4">
                <label className="form-label small">Default email</label>
                <input
                  type="email"
                  className="form-control"
                  value={newEmail}
                  onChange={(e) => setNewEmail(e.target.value)}
                  placeholder="owner@example.com"
                  disabled={creating}
                />
              </div>
              <div className="col-md-2">
                <button
                  className="btn btn-primary"
                  onClick={handleCreate}
                  disabled={creating || !newOwner.trim() || !newEmail.trim()}
                >
                  {creating ? <span className="spinner-border spinner-border-sm"></span> : 'Add'}
                </button>
              </div>
              <div className="col-md-2 text-end">
                <label className="btn btn-outline-secondary mb-0" title="Bulk-upload owner,email CSV rows">
                  {uploading ? (
                    <span className="spinner-border spinner-border-sm"></span>
                  ) : (
                    <>
                      <i className="bi bi-upload me-1"></i>
                      CSV upload
                    </>
                  )}
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept=".csv"
                    className="d-none"
                    onChange={handleFileUpload}
                    disabled={uploading}
                  />
                </label>
              </div>
            </div>
          </div>
        </div>
      )}

      <div className="card">
        <div className="card-body">
          <h5 className="card-title">Owner email mappings ({mappings.length})</h5>
          <p className="text-muted small">
            A default email for a GitHub owner is applied to that owner's repositories whenever their owner
            email is blank — either on the next import or immediately when the mapping is created/edited.
            Manually-set or previously-applied repo owner emails are never overwritten.
          </p>
          <div className="table-responsive">
            <table className="table table-striped table-hover align-middle">
              <thead>
                <tr>
                  <th>Owner</th>
                  <th>Email</th>
                  <th className="text-center">Repos</th>
                  <th>Created by</th>
                  <th>Updated</th>
                  {canManage && <th></th>}
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr>
                    <td colSpan={canManage ? 6 : 5} className="text-center py-4">
                      <span className="spinner-border spinner-border-sm me-2"></span>
                      Loading mappings...
                    </td>
                  </tr>
                ) : mappings.length === 0 ? (
                  <tr>
                    <td colSpan={canManage ? 6 : 5} className="text-center py-4 text-muted">
                      No owner email mappings yet.
                    </td>
                  </tr>
                ) : (
                  mappings.map((m) => (
                    <tr key={m.id}>
                      <td className="fw-semibold">{m.owner}</td>
                      <td>
                        {editingId === m.id ? (
                          <div className="input-group input-group-sm" style={{ minWidth: '220px' }}>
                            <input
                              type="email"
                              className="form-control"
                              value={emailDraft}
                              onChange={(e) => setEmailDraft(e.target.value)}
                              disabled={saving}
                            />
                            <button
                              className="btn btn-success"
                              onClick={() => saveEdit(m)}
                              disabled={saving || !emailDraft.trim()}
                              title="Save"
                            >
                              <i className="bi bi-check-lg"></i>
                            </button>
                            <button
                              className="btn btn-outline-secondary"
                              onClick={() => setEditingId(null)}
                              disabled={saving}
                              title="Cancel"
                            >
                              <i className="bi bi-x-lg"></i>
                            </button>
                          </div>
                        ) : (
                          <>
                            {m.email}
                            {canManage && (
                              <button
                                className="btn btn-link btn-sm p-0 ms-2"
                                onClick={() => startEdit(m)}
                                title="Edit email"
                              >
                                <i className="bi bi-pencil"></i>
                              </button>
                            )}
                          </>
                        )}
                      </td>
                      <td className="text-center">{m.repoCount}</td>
                      <td className="text-nowrap text-muted small">{m.createdBy}</td>
                      <td className="text-nowrap text-muted small">{formatServerDate(m.updatedAt, undefined, '—')}</td>
                      {canManage && (
                        <td className="text-nowrap">
                          <button
                            className="btn btn-outline-danger btn-sm"
                            onClick={() => handleDelete(m)}
                            title="Delete mapping"
                          >
                            <i className="bi bi-trash"></i>
                          </button>
                        </td>
                      )}
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
};

export default GithubOwnerEmailMappings;
