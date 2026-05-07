import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';
import WorkgroupAccountsModal from './WorkgroupAccountsModal';

interface Workgroup {
  id: number;
  name: string;
  description?: string;
  criticality: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NA';
  userCount: number;
  assetCount: number;
  awsAccountsCount?: number;
  createdAt: string;
  updatedAt: string;
  parentId?: number;
  parentName?: string;
  depth?: number;
  ancestors?: Array<{ id: number; name: string }>;
}

interface User {
  id: number | null;
  username: string;
  email: string;
  isPending?: boolean;
}

type UserRef = { id?: number; email: string };

interface Asset {
  id: number;
  name: string;
  type: string;
}

const WorkgroupManagement: React.FC = () => {
  const [workgroups, setWorkgroups] = useState<Workgroup[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingWorkgroup, setEditingWorkgroup] = useState<Workgroup | null>(null);
  const [formData, setFormData] = useState<{ name: string; description: string; criticality: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NA' }>({
    name: '',
    description: '',
    criticality: 'MEDIUM'
  });
  const [showAssignUsers, setShowAssignUsers] = useState(false);
  const [showAssignAssets, setShowAssignAssets] = useState(false);
  const [selectedWorkgroup, setSelectedWorkgroup] = useState<Workgroup | null>(null);
  const [selectedUserRefs, setSelectedUserRefs] = useState<UserRef[]>([]);
  const [selectedAssetIds, setSelectedAssetIds] = useState<number[]>([]);
  const [assetSearchTerm, setAssetSearchTerm] = useState<string>('');
  const [userSearchTerm, setUserSearchTerm] = useState<string>('');
  const [assignedUsers, setAssignedUsers] = useState<Array<{ id: number; username: string; email: string }>>([]);
  const [assignedUsersError, setAssignedUsersError] = useState<string | null>(null);
  const [accountsModalState, setAccountsModalState] = useState<{
    isOpen: boolean;
    workgroupId: number | null;
    workgroupName: string;
  }>({ isOpen: false, workgroupId: null, workgroupName: '' });

  useEffect(() => {
    fetchWorkgroups();
    fetchUsers();
    fetchAssets();
  }, []);

  const fetchWorkgroups = async () => {
    try {
      const response = await authenticatedGet('/api/workgroups');
      if (response.ok) {
        const data = await response.json();
        setWorkgroups(data);
      } else {
        setError(`Failed to fetch workgroups: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const fetchUsers = async () => {
    // Self-service workgroup management is open to non-ADMIN members; the canonical
    // /api/users list is ADMIN-only. The aws-account-sharing endpoint is the established
    // non-admin-safe user list (same {id, username, email, isPending} shape).
    // See AwsAccountSharingController.listUsersForSharing — deprecated pending a generic
    // public-safe replacement on /api/users.
    try {
      const response = await authenticatedGet('/api/aws-account-sharing/users');
      if (response.ok) {
        const data = await response.json();
        setUsers(data);
      }
    } catch (err) {
      console.error('Failed to fetch users:', err);
    }
  };

  const fetchAssets = async () => {
    try {
      const response = await authenticatedGet('/api/assets');
      if (response.ok) {
        const data = await response.json();
        setAssets(data);
      }
    } catch (err) {
      console.error('Failed to fetch assets:', err);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    try {
      if (editingWorkgroup) {
        const response = await authenticatedPut(`/api/workgroups/${editingWorkgroup.id}`, formData);
        if (!response.ok) {
          const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
          throw new Error(errorData.error || `Failed to update workgroup: ${response.status}`);
        }
      } else {
        const response = await authenticatedPost('/api/workgroups', formData);
        if (!response.ok) {
          const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
          throw new Error(errorData.error || `Failed to create workgroup: ${response.status}`);
        }
      }

      await fetchWorkgroups();
      resetForm();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleEdit = (workgroup: Workgroup) => {
    setEditingWorkgroup(workgroup);
    setFormData({
      name: workgroup.name,
      description: workgroup.description || '',
      criticality: workgroup.criticality
    });
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this workgroup? This will remove all user and asset assignments.')) {
      return;
    }

    try {
      const response = await authenticatedDelete(`/api/workgroups/${id}`);

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
        throw new Error(errorData.error || `Failed to delete workgroup: ${response.status}`);
      }

      await fetchWorkgroups();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred while deleting the workgroup');
    }
  };

  const handleAssignUsers = (workgroup: Workgroup) => {
    setSelectedWorkgroup(workgroup);
    setSelectedUserRefs([]);
    setUserSearchTerm('');
    setAssignedUsers([]);
    setAssignedUsersError(null);
    setShowAssignUsers(true);
    fetchAssignedUsers(workgroup.id);
  };

  const fetchAssignedUsers = async (workgroupId: number) => {
    try {
      const response = await authenticatedGet(`/api/workgroups/${workgroupId}/users`);
      if (response.ok) {
        const data = await response.json();
        setAssignedUsers(Array.isArray(data) ? data : []);
      } else {
        const body = await response.json().catch(() => ({}));
        setAssignedUsersError(body.error || `Failed to load current members (HTTP ${response.status})`);
      }
    } catch (err) {
      setAssignedUsersError(err instanceof Error ? err.message : 'Failed to load current members');
    }
  };

  const handleAssignAssets = (workgroup: Workgroup) => {
    setSelectedWorkgroup(workgroup);
    setSelectedAssetIds([]);
    setAssetSearchTerm('');
    setShowAssignAssets(true);
  };

  /**
   * Filter assets based on search term with wildcard support
   * Supports: partial match, * wildcard, case-insensitive
   */
  const filterAssets = (searchTerm: string): Asset[] => {
    if (!searchTerm.trim()) {
      return assets;
    }

    const term = searchTerm.toLowerCase().trim();

    // Convert wildcard pattern to regex
    // * matches any characters, ? matches single character
    const regexPattern = term
      .replace(/[.+^${}()|[\]\\]/g, '\\$&') // Escape regex special chars except * and ?
      .replace(/\*/g, '.*')  // * becomes .*
      .replace(/\?/g, '.');  // ? becomes .

    try {
      const regex = new RegExp(regexPattern);
      return assets.filter(asset =>
        regex.test(asset.name.toLowerCase()) ||
        regex.test(asset.type.toLowerCase())
      );
    } catch {
      // If regex is invalid, fall back to simple includes
      return assets.filter(asset =>
        asset.name.toLowerCase().includes(term) ||
        asset.type.toLowerCase().includes(term)
      );
    }
  };

  const filteredAssets = filterAssets(assetSearchTerm);

  const submitAssignUsers = async () => {
    if (!selectedWorkgroup || selectedUserRefs.length === 0) {
      setError('Please select at least one user');
      return;
    }

    try {
      const response = await authenticatedPost(`/api/workgroups/${selectedWorkgroup.id}/users`, {
        userRefs: selectedUserRefs
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
        throw new Error(errorData.error || `Failed to assign users: ${response.status}`);
      }

      await fetchWorkgroups();
      setShowAssignUsers(false);
      setSelectedWorkgroup(null);
      setSelectedUserRefs([]);
      setUserSearchTerm('');
      setAssignedUsers([]);
      setAssignedUsersError(null);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const submitAssignAssets = async () => {
    if (!selectedWorkgroup || selectedAssetIds.length === 0) {
      setError('Please select at least one asset');
      return;
    }

    try {
      const response = await authenticatedPost(`/api/workgroups/${selectedWorkgroup.id}/assets`, {
        assetIds: selectedAssetIds
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
        throw new Error(errorData.error || `Failed to assign assets: ${response.status}`);
      }

      await fetchWorkgroups();
      setShowAssignAssets(false);
      setSelectedWorkgroup(null);
      setSelectedAssetIds([]);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const toggleUserSelection = (user: User) => {
    setSelectedUserRefs(prev => {
      const matches = (r: UserRef) =>
        (user.id != null && r.id === user.id) ||
        (user.id == null && r.email.toLowerCase() === user.email.toLowerCase());
      if (prev.some(matches)) {
        return prev.filter(r => !matches(r));
      }
      return user.id != null
        ? [...prev, { id: user.id, email: user.email }]
        : [...prev, { email: user.email }];
    });
  };

  const toggleAssetSelection = (assetId: number) => {
    setSelectedAssetIds(prev =>
      prev.includes(assetId) ? prev.filter(id => id !== assetId) : [...prev, assetId]
    );
  };

  const resetForm = () => {
    setFormData({ name: '', description: '', criticality: 'MEDIUM' });
    setEditingWorkgroup(null);
    setShowForm(false);
  };

  if (loading) {
    return <div className="text-center p-4">Loading workgroups...</div>;
  }

  return (
    <div className="container mt-4">
      <div className="d-flex justify-content-between align-items-center mb-4">
        <h2>Workgroup Management</h2>
        <button className="btn btn-primary" onClick={() => setShowForm(true)}>
          Create Workgroup
        </button>
      </div>

      {error && (
        <div className="alert alert-danger alert-dismissible fade show" role="alert">
          {error}
          <button type="button" className="btn-close" onClick={() => setError(null)}></button>
        </div>
      )}

      {/* Create/Edit Form Modal */}
      {showForm && (
        <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog">
            <div className="modal-content">
              <div className="modal-header">
                <h5 className="modal-title">{editingWorkgroup ? 'Edit Workgroup' : 'Create Workgroup'}</h5>
                <button type="button" className="btn-close" onClick={resetForm}></button>
              </div>
              <form onSubmit={handleSubmit}>
                <div className="modal-body">
                  {/* Parent Workgroup Info (read-only, only shown when editing) */}
                  {editingWorkgroup && (
                    <div className="mb-3 pb-3 border-bottom">
                      <div className="alert alert-info mb-0">
                        <h6 className="alert-heading mb-2">
                          <i className="bi bi-diagram-3 me-2"></i>
                          Hierarchy Information
                        </h6>
                        {editingWorkgroup.parentId ? (
                          <>
                            <div className="mb-1">
                              <strong>Parent Workgroup:</strong>{' '}
                              {editingWorkgroup.parentName || `ID ${editingWorkgroup.parentId}`}
                            </div>
                            {editingWorkgroup.ancestors && editingWorkgroup.ancestors.length > 0 && (
                              <div>
                                <strong>Full Path:</strong>{' '}
                                <span className="text-muted">
                                  {editingWorkgroup.ancestors.map(a => a.name).join(' > ')} &gt; {editingWorkgroup.name}
                                </span>
                              </div>
                            )}
                            {editingWorkgroup.depth && (
                              <div className="mt-1">
                                <span className="badge bg-secondary">Level {editingWorkgroup.depth}</span>
                              </div>
                            )}
                          </>
                        ) : (
                          <div>
                            <strong>Location:</strong> Root level (no parent)
                            {editingWorkgroup.depth && (
                              <span className="badge bg-secondary ms-2">Level {editingWorkgroup.depth}</span>
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
                  )}

                  <div className="mb-3">
                    <label className="form-label">Name *</label>
                    <input
                      type="text"
                      className="form-control"
                      value={formData.name}
                      onChange={(e) => setFormData({ ...formData, name: e.target.value })}
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
                      onChange={(e) => setFormData({ ...formData, description: e.target.value })}
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
                      onChange={(e) => setFormData({ ...formData, criticality: e.target.value as 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NA' })}
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
                </div>
                <div className="modal-footer">
                  <button type="button" className="btn btn-secondary" onClick={resetForm}>
                    Cancel
                  </button>
                  <button type="submit" className="btn btn-primary">
                    {editingWorkgroup ? 'Update' : 'Create'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}

      {/* Assign Users Modal */}
      {showAssignUsers && selectedWorkgroup && (
        <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-lg">
            <div className="modal-content">
              <div className="modal-header">
                <h5 className="modal-title">Assign Users to {selectedWorkgroup.name}</h5>
                <button type="button" className="btn-close" onClick={() => setShowAssignUsers(false)}></button>
              </div>
              <div className="modal-body">
                {/* Currently assigned members — visible at the top so the admin sees who is already in the group. */}
                <div className="mb-3">
                  <div className="d-flex align-items-center mb-2">
                    <strong>Currently assigned ({assignedUsers.length})</strong>
                  </div>
                  {assignedUsersError && (
                    <div className="alert alert-warning py-2 mb-2">
                      <i className="bi bi-exclamation-triangle me-1"></i>
                      {assignedUsersError}
                    </div>
                  )}
                  {assignedUsers.length === 0 && !assignedUsersError && (
                    <div className="text-muted small fst-italic">No users assigned yet.</div>
                  )}
                  {assignedUsers.length > 0 && (
                    <div className="d-flex flex-wrap gap-1">
                      {assignedUsers.map(u => (
                        <span key={u.id} className="badge bg-success bg-opacity-25 text-success border border-success">
                          <i className="bi bi-person-check me-1"></i>
                          {u.username} <span className="text-muted">({u.email})</span>
                        </span>
                      ))}
                    </div>
                  )}
                </div>

                <p className="text-muted mb-2">Select users to add to this workgroup:</p>

                {/* Search input — mirrors the asset-assign modal, debounced via React's controlled-input render cycle. */}
                <div className="mb-3">
                  <div className="input-group">
                    <span className="input-group-text">
                      <i className="bi bi-search"></i>
                    </span>
                    <input
                      type="text"
                      className="form-control"
                      placeholder="Search by username or email..."
                      value={userSearchTerm}
                      onChange={(e) => setUserSearchTerm(e.target.value)}
                      autoFocus
                    />
                    {userSearchTerm && (
                      <button
                        type="button"
                        className="btn btn-outline-secondary"
                        onClick={() => setUserSearchTerm('')}
                        title="Clear search"
                      >
                        <i className="bi bi-x-lg"></i>
                      </button>
                    )}
                  </div>
                </div>

                {(() => {
                  const assignedIds = new Set(assignedUsers.map(u => u.id));
                  const assignedEmails = new Set(assignedUsers.map(u => u.email.toLowerCase()));
                  const term = userSearchTerm.trim().toLowerCase();
                  const filtered = users.filter(u => {
                    if (!term) return true;
                    return (u.username || '').toLowerCase().includes(term)
                        || (u.email || '').toLowerCase().includes(term);
                  });
                  // Sort: already-assigned first (so the admin sees who is in the group at a glance), then alpha by username.
                  const sorted = [...filtered].sort((a, b) => {
                    const aAssigned = (a.id != null && assignedIds.has(a.id)) || assignedEmails.has(a.email.toLowerCase());
                    const bAssigned = (b.id != null && assignedIds.has(b.id)) || assignedEmails.has(b.email.toLowerCase());
                    if (aAssigned !== bAssigned) return aAssigned ? -1 : 1;
                    return (a.username || a.email).localeCompare(b.username || b.email);
                  });

                  if (sorted.length === 0) {
                    return (
                      <div className="text-center text-muted py-3">
                        No users match "{userSearchTerm}"
                      </div>
                    );
                  }

                  return (
                    <div className="list-group" style={{ maxHeight: '400px', overflowY: 'auto' }}>
                      {sorted.map(user => {
                        const isAssigned = (user.id != null && assignedIds.has(user.id))
                          || assignedEmails.has(user.email.toLowerCase());
                        const checked = selectedUserRefs.some(r =>
                          (user.id != null && r.id === user.id) ||
                          (user.id == null && r.email.toLowerCase() === user.email.toLowerCase())
                        );
                        return (
                          <label
                            key={user.id ?? `pending:${user.email}`}
                            className={`list-group-item list-group-item-action ${isAssigned ? 'list-group-item-light' : ''}`}
                            title={isAssigned ? 'Already a member of this workgroup' : undefined}
                          >
                            <input
                              type="checkbox"
                              className="form-check-input me-2"
                              checked={isAssigned || checked}
                              disabled={isAssigned}
                              onChange={() => toggleUserSelection(user)}
                            />
                            <strong>{user.username}</strong> ({user.email})
                            {isAssigned && (
                              <span className="badge bg-success ms-2">
                                <i className="bi bi-check-circle me-1"></i>assigned
                              </span>
                            )}
                            {user.isPending && (
                              <span
                                className="badge bg-warning text-dark ms-2"
                                title="This email is known via AWS / domain mapping but has never logged in. Selecting it will create an account placeholder."
                              >
                                pending
                              </span>
                            )}
                          </label>
                        );
                      })}
                    </div>
                  );
                })()}

                <p className="mt-3 text-muted small">
                  {selectedUserRefs.length} new user(s) selected · showing {users.length} total
                </p>
              </div>
              <div className="modal-footer">
                <button type="button" className="btn btn-secondary" onClick={() => setShowAssignUsers(false)}>
                  Cancel
                </button>
                <button type="button" className="btn btn-primary" onClick={submitAssignUsers}>
                  Assign Users
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Assign Assets Modal */}
      {showAssignAssets && selectedWorkgroup && (
        <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-lg">
            <div className="modal-content">
              <div className="modal-header">
                <h5 className="modal-title">Assign Assets to {selectedWorkgroup.name}</h5>
                <button type="button" className="btn-close" onClick={() => setShowAssignAssets(false)}></button>
              </div>
              <div className="modal-body">
                <p className="text-muted">Select assets to assign to this workgroup:</p>

                {/* Search input */}
                <div className="mb-3">
                  <div className="input-group">
                    <span className="input-group-text">
                      <i className="bi bi-search"></i>
                    </span>
                    <input
                      type="text"
                      className="form-control"
                      placeholder="Search assets... (use * for wildcard, e.g. ip-10-* or *prod*)"
                      value={assetSearchTerm}
                      onChange={(e) => setAssetSearchTerm(e.target.value)}
                      autoFocus
                    />
                    {assetSearchTerm && (
                      <button
                        className="btn btn-outline-secondary"
                        type="button"
                        onClick={() => setAssetSearchTerm('')}
                        title="Clear search"
                      >
                        <i className="bi bi-x-lg"></i>
                      </button>
                    )}
                  </div>
                  <small className="text-muted">
                    {filteredAssets.length} of {assets.length} assets shown
                    {assetSearchTerm && ` matching "${assetSearchTerm}"`}
                  </small>
                </div>

                {/* Select all filtered / Clear selection buttons */}
                {filteredAssets.length > 0 && (
                  <div className="mb-2">
                    <button
                      type="button"
                      className="btn btn-sm btn-outline-primary me-2"
                      onClick={() => {
                        const filteredIds = filteredAssets.map(a => a.id);
                        setSelectedAssetIds(prev => [...new Set([...prev, ...filteredIds])]);
                      }}
                    >
                      Select all shown ({filteredAssets.length})
                    </button>
                    {selectedAssetIds.length > 0 && (
                      <button
                        type="button"
                        className="btn btn-sm btn-outline-secondary"
                        onClick={() => setSelectedAssetIds([])}
                      >
                        Clear selection
                      </button>
                    )}
                  </div>
                )}

                <div className="list-group" style={{ maxHeight: '350px', overflowY: 'auto' }}>
                  {filteredAssets.length === 0 ? (
                    <div className="list-group-item text-center text-muted">
                      {assetSearchTerm
                        ? `No assets found matching "${assetSearchTerm}"`
                        : 'No assets available'}
                    </div>
                  ) : (
                    filteredAssets.map(asset => (
                      <label key={asset.id} className="list-group-item list-group-item-action">
                        <input
                          type="checkbox"
                          className="form-check-input me-2"
                          checked={selectedAssetIds.includes(asset.id)}
                          onChange={() => toggleAssetSelection(asset.id)}
                        />
                        <strong>{asset.name}</strong> ({asset.type})
                      </label>
                    ))
                  )}
                </div>
                <p className="mt-3 text-muted">{selectedAssetIds.length} asset(s) selected</p>
              </div>
              <div className="modal-footer">
                <button type="button" className="btn btn-secondary" onClick={() => setShowAssignAssets(false)}>
                  Cancel
                </button>
                <button type="button" className="btn btn-primary" onClick={submitAssignAssets}>
                  Assign Assets
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Accounts Modal */}
      {accountsModalState.workgroupId !== null && (
        <WorkgroupAccountsModal
          workgroupId={accountsModalState.workgroupId}
          workgroupName={accountsModalState.workgroupName}
          isOpen={accountsModalState.isOpen}
          onClose={() =>
            setAccountsModalState({ isOpen: false, workgroupId: null, workgroupName: '' })
          }
          onChange={() => {
            fetchWorkgroups();
          }}
        />
      )}

      {/* Workgroups Table */}
      <div className="table-responsive">
        <table className="table table-striped table-hover">
          <thead>
            <tr>
              <th>Name</th>
              <th>Description</th>
              <th>Criticality</th>
              <th>Users</th>
              <th>Assets</th>
              <th>Accounts</th>
              <th>Created</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {workgroups.length === 0 ? (
              <tr>
                <td colSpan={8} className="text-center text-muted">
                  No workgroups found. Create one to get started.
                </td>
              </tr>
            ) : (
              workgroups.map(workgroup => {
                const criticalityColor = workgroup.criticality === 'CRITICAL' ? 'danger' :
                                        workgroup.criticality === 'HIGH' ? 'warning' :
                                        workgroup.criticality === 'MEDIUM' ? 'info' :
                                        workgroup.criticality === 'LOW' ? 'secondary' : 'light';
                const criticalityText = workgroup.criticality === 'NA' ? 'N/A' : workgroup.criticality;
                return (
                  <tr key={workgroup.id}>
                    <td><strong>{workgroup.name}</strong></td>
                    <td>{workgroup.description || <span className="text-muted">-</span>}</td>
                    <td>
                      <span className={`badge bg-${criticalityColor} ${criticalityColor === 'light' ? 'text-dark' : ''}`}>{criticalityText}</span>
                    </td>
                    <td>
                      <span className="badge bg-info">{workgroup.userCount}</span>
                    </td>
                    <td>
                      <span className="badge bg-success">{workgroup.assetCount}</span>
                    </td>
                    <td>
                      <span className="badge bg-secondary">{workgroup.awsAccountsCount ?? 0}</span>
                    </td>
                    <td>{new Date(workgroup.createdAt).toLocaleDateString()}</td>
                  <td>
                    <div className="btn-group btn-group-sm">
                      <button
                        className="btn btn-outline-primary"
                        onClick={() => handleEdit(workgroup)}
                        title="Edit workgroup"
                      >
                        Edit
                      </button>
                      <button
                        className="btn btn-outline-info"
                        onClick={() => handleAssignUsers(workgroup)}
                        title="Assign users"
                      >
                        Users
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-info ms-1"
                        onClick={() =>
                          setAccountsModalState({
                            isOpen: true,
                            workgroupId: workgroup.id,
                            workgroupName: workgroup.name,
                          })
                        }
                        title="Manage AWS accounts"
                      >
                        Accounts
                      </button>
                      <button
                        className="btn btn-outline-success"
                        onClick={() => handleAssignAssets(workgroup)}
                        title="Assign assets"
                      >
                        Assets
                      </button>
                      <button
                        className="btn btn-outline-danger"
                        onClick={() => handleDelete(workgroup.id)}
                        title="Delete workgroup"
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default WorkgroupManagement;
