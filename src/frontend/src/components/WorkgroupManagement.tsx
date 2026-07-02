import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';
import WorkgroupAccountsModal from './WorkgroupAccountsModal';
import WorkgroupDomainsModal from './WorkgroupDomainsModal';
import { isAwsWorkgroup } from '../services/workgroupApi';
import { formatServerDate } from '../utils/dateUtils';

// Extract a useful error message from a non-OK Response. Handles the three
// shapes we see in this app: our own `{error: "..."}`, Micronaut's default
// `{message: "..."}`, and Bean-Validation `{_embedded:{errors:[{message:...}]}}`.
// Falls back to status text so we never show the literal string "Unknown error".
async function extractErrorMessage(response: Response, fallback: string): Promise<string> {
  try {
    const body = await response.json();
    if (body && typeof body === 'object') {
      if (typeof body.error === 'string' && body.error) return body.error;
      if (typeof body.message === 'string' && body.message) return body.message;
      const violations = body?._embedded?.errors;
      if (Array.isArray(violations) && violations.length > 0) {
        return violations.map((v: any) => v?.message).filter(Boolean).join('; ') || fallback;
      }
    }
  } catch {
    // body wasn't JSON — fall through
  }
  return `${fallback} (HTTP ${response.status})`;
}

interface Workgroup {
  id: number;
  name: string;
  description?: string;
  criticality: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NA';
  userCount: number;
  assetCount: number;
  awsAccountsCount?: number;
  adDomainsCount?: number;
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

// Shape returned by GET /api/workgroups/{id}/assets — includes owner/ip for context
// rows in the "Currently assigned" panel. `type` is nullable on the backend Asset.
interface AssignedAsset {
  id: number;
  name: string;
  type: string | null;
  ip: string | null;
  owner: string | null;
}

const getEffectiveAssignedUserCount = (
  assignedUsers: Array<{ id: number; username: string; email: string }>,
  userIdsToRemove: number[]
): number => {
  const assignedIds = new Set(assignedUsers.map(user => user.id));
  const pendingRemovals = userIdsToRemove.filter(id => assignedIds.has(id)).length;
  return Math.max(0, assignedUsers.length - pendingRemovals);
};


interface WorkgroupManagementProps {
  /** When false (default), workgroups named "AWS-…" are hidden from the table. */
  showAwsWorkgroups?: boolean;
}

const WorkgroupManagement: React.FC<WorkgroupManagementProps> = ({ showAwsWorkgroups = false }) => {
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
  const [assignedAssets, setAssignedAssets] = useState<AssignedAsset[]>([]);
  const [assignedAssetsError, setAssignedAssetsError] = useState<string | null>(null);
  // Pending removals: ids the user has marked × in the "Currently assigned" panel.
  // Strikethrough until Save changes — keeps the operation reversible inside the modal.
  const [assetIdsToRemove, setAssetIdsToRemove] = useState<number[]>([]);
  const [userIdsToRemove, setUserIdsToRemove] = useState<number[]>([]);
  const [accountsModalState, setAccountsModalState] = useState<{
    isOpen: boolean;
    workgroupId: number | null;
    workgroupName: string;
  }>({ isOpen: false, workgroupId: null, workgroupName: '' });
  const [domainsModalState, setDomainsModalState] = useState<{
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
        const data = await response.json() as Workgroup[];
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
          throw new Error(await extractErrorMessage(response, 'Failed to update workgroup'));
        }
      } else {
        const response = await authenticatedPost('/api/workgroups', formData);
        if (!response.ok) {
          throw new Error(await extractErrorMessage(response, 'Failed to create workgroup'));
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
        throw new Error(await extractErrorMessage(response, 'Failed to delete workgroup'));
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
    setUserIdsToRemove([]);
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
    setAssignedAssets([]);
    setAssignedAssetsError(null);
    setAssetIdsToRemove([]);
    setShowAssignAssets(true);
    fetchAssignedAssets(workgroup.id);
  };

  const fetchAssignedAssets = async (workgroupId: number) => {
    try {
      const response = await authenticatedGet(`/api/workgroups/${workgroupId}/assets`);
      if (response.ok) {
        const data = await response.json();
        setAssignedAssets(Array.isArray(data) ? data : []);
      } else {
        const body = await response.json().catch(() => ({}));
        setAssignedAssetsError(body.error || `Failed to load current assets (HTTP ${response.status})`);
      }
    } catch (err) {
      setAssignedAssetsError(err instanceof Error ? err.message : 'Failed to load current assets');
    }
  };

  // Toggle a row in the "Currently assigned" panel between kept and pending-removal.
  const toggleAssetRemoval = (assetId: number) => {
    setAssetIdsToRemove(prev =>
      prev.includes(assetId) ? prev.filter(id => id !== assetId) : [...prev, assetId]
    );
  };

  const toggleUserRemoval = (userId: number) => {
    setUserIdsToRemove(prev =>
      prev.includes(userId) ? prev.filter(id => id !== userId) : [...prev, userId]
    );
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
  const modalExpectedUserCount = selectedWorkgroup?.userCount ?? 0;
  const modalEffectiveUserCount = getEffectiveAssignedUserCount(assignedUsers, userIdsToRemove);
  const hasUserCountMismatch = showAssignUsers
    && !!selectedWorkgroup
    && !assignedUsersError
    && modalExpectedUserCount !== modalEffectiveUserCount;

  const submitAssignUsers = async () => {
    if (!selectedWorkgroup) return;
    if (selectedUserRefs.length === 0 && userIdsToRemove.length === 0) {
      setError('Select at least one user to add or remove');
      return;
    }

    try {
      // Removals first (same ordering rationale as submitAssignAssets): if the add
      // step fails, the workgroup is still in its intended steady state for what
      // succeeded. Partial progress is not regression.
      if (userIdsToRemove.length > 0) {
        const removeResp = await authenticatedDelete(
          `/api/workgroups/${selectedWorkgroup.id}/users`,
          { userIds: userIdsToRemove }
        );
        if (!removeResp.ok) {
          throw new Error(await extractErrorMessage(removeResp, 'Failed to remove users'));
        }
      }

      if (selectedUserRefs.length > 0) {
        const addResp = await authenticatedPost(
          `/api/workgroups/${selectedWorkgroup.id}/users`,
          { userRefs: selectedUserRefs }
        );
        if (!addResp.ok) {
          throw new Error(await extractErrorMessage(addResp, 'Failed to assign users'));
        }
      }

      await fetchWorkgroups();
      setShowAssignUsers(false);
      setSelectedWorkgroup(null);
      setSelectedUserRefs([]);
      setUserSearchTerm('');
      setAssignedUsers([]);
      setAssignedUsersError(null);
      setUserIdsToRemove([]);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const submitAssignAssets = async () => {
    if (!selectedWorkgroup) return;
    if (selectedAssetIds.length === 0 && assetIdsToRemove.length === 0) {
      setError('Select at least one asset to add or remove');
      return;
    }

    try {
      // Removals first so the workgroup is in its intended steady state even if the
      // subsequent add fails — partially-applied changes are still progress, never regress.
      if (assetIdsToRemove.length > 0) {
        const removeResp = await authenticatedDelete(
          `/api/workgroups/${selectedWorkgroup.id}/assets`,
          { assetIds: assetIdsToRemove }
        );
        if (!removeResp.ok) {
          throw new Error(await extractErrorMessage(removeResp, 'Failed to remove assets'));
        }
      }

      if (selectedAssetIds.length > 0) {
        const addResp = await authenticatedPost(
          `/api/workgroups/${selectedWorkgroup.id}/assets`,
          { assetIds: selectedAssetIds }
        );
        if (!addResp.ok) {
          throw new Error(await extractErrorMessage(addResp, 'Failed to assign assets'));
        }
      }

      await fetchWorkgroups();
      setShowAssignAssets(false);
      setSelectedWorkgroup(null);
      setSelectedAssetIds([]);
      setAssetIdsToRemove([]);
      setAssignedAssets([]);
      setAssignedAssetsError(null);
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
                {/* Currently assigned members — mirrors the Manage Assets dialog. The × marks
                    a row for removal but does not call the API until "Save changes" is pressed. */}
                <div className="mb-3 p-2 border rounded bg-light">
                  <div className="d-flex justify-content-between align-items-center mb-1">
                    <strong>Currently assigned ({assignedUsers.length})</strong>
                    {userIdsToRemove.length > 0 && (
                      <span className="badge bg-warning text-dark">
                        {userIdsToRemove.length} pending removal
                      </span>
                    )}
                  </div>
                  {hasUserCountMismatch && (
                    <div className="alert alert-warning py-2 px-2 my-2 small mb-0" role="alert">
                      <i className="bi bi-exclamation-triangle me-1"></i>
                      Count mismatch: table shows <strong>{modalExpectedUserCount}</strong>, but current members in this
                      dialog are <strong>{modalEffectiveUserCount}</strong>.
                    </div>
                  )}
                  {assignedUsersError && (
                    <div className="alert alert-warning py-1 px-2 my-1 small mb-0" role="alert">
                      {assignedUsersError}
                    </div>
                  )}
                  {assignedUsers.length === 0 && !assignedUsersError && (
                    <div className="text-muted small fst-italic">No users assigned yet.</div>
                  )}
                  {assignedUsers.length > 0 && (
                    <div style={{ maxHeight: '180px', overflowY: 'auto' }}>
                      {assignedUsers.map(u => {
                        const pending = userIdsToRemove.includes(u.id);
                        return (
                          <div
                            key={u.id}
                            className="d-flex justify-content-between align-items-center py-1 px-1 border-bottom"
                          >
                            <span
                              style={{
                                textDecoration: pending ? 'line-through' : 'none',
                                color: pending ? '#999' : 'inherit',
                              }}
                            >
                              <i className="bi bi-person-check me-1"></i>
                              <strong>{u.username}</strong>
                              <span className="text-muted small"> ({u.email})</span>
                            </span>
                            <button
                              type="button"
                              className={`btn btn-sm ${pending ? 'btn-outline-secondary' : 'btn-outline-danger'}`}
                              onClick={() => toggleUserRemoval(u.id)}
                              title={pending ? 'Undo removal' : 'Mark for removal'}
                            >
                              {pending ? 'Undo' : '× Remove'}
                            </button>
                          </div>
                        );
                      })}
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
                      placeholder="Search existing users — or type a full email to invite someone new"
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
                  {/* Persistent helper — many users never realize the search box doubles as an invite-by-email
                      field. State the affordance up front so they don't have to discover it. */}
                  {(() => {
                    const caller = (typeof window !== 'undefined' ? window.currentUser : null) || null;
                    const dom = caller?.email?.split('@')[1] || 'your-domain.com';
                    const isAdmin = (caller?.roles || []).includes('ADMIN');
                    return (
                      <div className="form-text mt-1">
                        <i className="bi bi-lightbulb me-1"></i>
                        Not in the list? Type a full email like <code>name@{dom}</code> to invite a new user.
                        {!isAdmin && (
                          <> New users must be at <code>@{dom}</code> (your domain).</>
                        )}
                      </div>
                    );
                  })()}
                </div>

                {(() => {
                  const assignedIds = new Set(assignedUsers.map(u => u.id));
                  const assignedEmails = new Set(assignedUsers.map(u => u.email.toLowerCase()));
                  const termRaw = userSearchTerm.trim();
                  const term = termRaw.toLowerCase();
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

                  // Invite-by-email affordance: visible when the search term parses as an
                  // email that isn't already in the user list. Domain-restricted on the
                  // client (UX), enforced again on the server (security).
                  const looksLikeEmail = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(termRaw);
                  const matchesExistingEmail = users.some(u => u.email.toLowerCase() === term);
                  const caller = (typeof window !== 'undefined' ? window.currentUser : null) || null;
                  const callerEmail = caller?.email || '';
                  const callerDomain = callerEmail.split('@')[1]?.toLowerCase() || '';
                  const callerIsAdmin = (caller?.roles || []).includes('ADMIN');
                  const inviteDomain = looksLikeEmail ? (termRaw.split('@')[1]?.toLowerCase() || '') : '';
                  const inviteShouldShow = looksLikeEmail && !matchesExistingEmail;
                  const inviteAllowed = inviteShouldShow && (callerIsAdmin || (!!callerDomain && callerDomain === inviteDomain));
                  const inviteChecked = inviteShouldShow && selectedUserRefs.some(r => r.email.toLowerCase() === term);

                  const inviteRow = inviteShouldShow ? (
                    <label
                      key={`invite:${term}`}
                      className={`list-group-item list-group-item-action ${inviteAllowed ? '' : 'list-group-item-light text-muted'}`}
                      title={
                        inviteAllowed
                          ? 'Invite this email as a new pending user'
                          : `New users must share your email domain (@${callerDomain || '?'})`
                      }
                    >
                      <input
                        type="checkbox"
                        className="form-check-input me-2"
                        checked={inviteChecked}
                        disabled={!inviteAllowed}
                        onChange={() => toggleUserSelection({ id: null, username: termRaw.split('@')[0], email: termRaw, isPending: true })}
                      />
                      <i className={`bi ${inviteAllowed ? 'bi-person-plus' : 'bi-shield-lock'} me-1`}></i>
                      <strong>Invite new user:</strong> {termRaw}
                      <span className={`badge ms-2 ${inviteAllowed ? 'scand-success' : 'scand-critical'}`}>
                        {inviteAllowed ? 'new pending' : 'wrong domain'}
                      </span>
                    </label>
                  ) : null;

                  if (sorted.length === 0 && !inviteRow) {
                    // No existing user match AND not a parseable email yet. Tell the
                    // user exactly what to type next so they don't get stuck — the
                    // common failure mode is typing a username and giving up.
                    const hasAtSign = termRaw.includes('@');
                    return (
                      <div className="text-center text-muted py-4 border rounded bg-light">
                        <div className="mb-2">
                          <i className="bi bi-search me-1"></i>
                          No users match "<strong>{userSearchTerm}</strong>"
                        </div>
                        <div className="small">
                          <i className="bi bi-person-plus me-1"></i>
                          {hasAtSign
                            ? <>Keep typing the rest of the email address to invite a new user.</>
                            : <>To invite someone new, type their <strong>complete email address</strong> here.</>}
                        </div>
                      </div>
                    );
                  }

                  return (
                    <div className="list-group" style={{ maxHeight: '400px', overflowY: 'auto' }}>
                      {/* Invite row first — putting it at the top of a possibly-long list
                          guarantees the user sees it without scrolling. */}
                      {inviteRow}
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

                {(() => {
                  // Show staged "new pending" invites separately from existing-user picks so
                  // the admin can audit them even after clearing the search box.
                  const knownEmails = new Set(users.map(u => u.email.toLowerCase()));
                  const stagedInvites = selectedUserRefs.filter(
                    r => r.id == null && !knownEmails.has(r.email.toLowerCase())
                  );
                  if (stagedInvites.length === 0) return null;
                  return (
                    <div className="mt-3 p-2 border rounded border-success-subtle bg-success-subtle">
                      <div className="d-flex align-items-center mb-1">
                        <i className="bi bi-person-plus me-1 text-success"></i>
                        <strong className="small">Will create {stagedInvites.length} new user{stagedInvites.length !== 1 ? 's' : ''}</strong>
                      </div>
                      <div className="d-flex flex-wrap gap-1">
                        {stagedInvites.map(r => (
                          <span key={`staged:${r.email}`} className="badge scand-success">
                            {r.email}
                            <button
                              type="button"
                              className="btn-close btn-close-sm ms-2"
                              style={{ fontSize: '0.6rem' }}
                              aria-label={`Cancel invite for ${r.email}`}
                              onClick={() => toggleUserSelection({ id: null, username: r.email.split('@')[0], email: r.email, isPending: true })}
                            ></button>
                          </span>
                        ))}
                      </div>
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
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={submitAssignUsers}
                  disabled={selectedUserRefs.length === 0 && userIdsToRemove.length === 0}
                >
                  Save changes
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Manage Assets Modal */}
      {showAssignAssets && selectedWorkgroup && (
        <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-lg">
            <div className="modal-content">
              <div className="modal-header">
                <h5 className="modal-title">Manage Assets in {selectedWorkgroup.name}</h5>
                <button type="button" className="btn-close" onClick={() => setShowAssignAssets(false)}></button>
              </div>
              <div className="modal-body">
                {/* Currently assigned — mirrors the Assign Users dialog. The × marks a row
                    for removal but does not call the API until "Save changes" is pressed. */}
                <div className="mb-3 p-2 border rounded bg-light">
                  <div className="d-flex justify-content-between align-items-center mb-1">
                    <strong>Currently assigned ({assignedAssets.length})</strong>
                    {assetIdsToRemove.length > 0 && (
                      <span className="badge bg-warning text-dark">
                        {assetIdsToRemove.length} pending removal
                      </span>
                    )}
                  </div>
                  {assignedAssetsError && (
                    <div className="alert alert-warning py-1 px-2 my-1 small mb-0" role="alert">
                      {assignedAssetsError}
                    </div>
                  )}
                  {assignedAssets.length === 0 && !assignedAssetsError && (
                    <div className="text-muted small fst-italic">No assets assigned yet.</div>
                  )}
                  {assignedAssets.length > 0 && (
                    <div style={{ maxHeight: '180px', overflowY: 'auto' }}>
                      {assignedAssets.map(a => {
                        const pending = assetIdsToRemove.includes(a.id);
                        return (
                          <div
                            key={a.id}
                            className="d-flex justify-content-between align-items-center py-1 px-1 border-bottom"
                          >
                            <span
                              style={{
                                textDecoration: pending ? 'line-through' : 'none',
                                color: pending ? '#999' : 'inherit',
                              }}
                            >
                              <strong>{a.name}</strong>
                              {a.type && <span className="text-muted small"> ({a.type})</span>}
                              {a.ip && <span className="text-muted small"> · {a.ip}</span>}
                            </span>
                            <button
                              type="button"
                              className={`btn btn-sm ${pending ? 'btn-outline-secondary' : 'btn-outline-danger'}`}
                              onClick={() => toggleAssetRemoval(a.id)}
                              title={pending ? 'Undo removal' : 'Mark for removal'}
                            >
                              {pending ? 'Undo' : '× Remove'}
                            </button>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>

                <p className="text-muted">Add more assets to this workgroup:</p>

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
                {filteredAssets.length > 0 && (() => {
                  const assignedIds = new Set(assignedAssets.map(a => a.id));
                  const addable = filteredAssets.filter(a => !assignedIds.has(a.id));
                  return (
                  <div className="mb-2">
                    <button
                      type="button"
                      className="btn btn-sm btn-outline-primary me-2"
                      disabled={addable.length === 0}
                      onClick={() => {
                        const addableIds = addable.map(a => a.id);
                        setSelectedAssetIds(prev => [...new Set([...prev, ...addableIds])]);
                      }}
                    >
                      Select all shown ({addable.length})
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
                  );
                })()}

                <div className="list-group" style={{ maxHeight: '350px', overflowY: 'auto' }}>
                  {filteredAssets.length === 0 ? (
                    <div className="list-group-item text-center text-muted">
                      {assetSearchTerm
                        ? `No assets found matching "${assetSearchTerm}"`
                        : 'No assets available'}
                    </div>
                  ) : (
                    (() => {
                      // Hide assets already in the workgroup from the "add more" picker —
                      // they can only be removed via the Currently assigned panel. Keep
                      // the lookup O(1) so the 119-row dataset doesn't blow rendering.
                      const assignedIds = new Set(assignedAssets.map(a => a.id));
                      return filteredAssets
                        .filter(asset => !assignedIds.has(asset.id))
                        .map(asset => (
                          <label key={asset.id} className="list-group-item list-group-item-action">
                            <input
                              type="checkbox"
                              className="form-check-input me-2"
                              checked={selectedAssetIds.includes(asset.id)}
                              onChange={() => toggleAssetSelection(asset.id)}
                            />
                            <strong>{asset.name}</strong> ({asset.type})
                          </label>
                        ));
                    })()
                  )}
                </div>
                <p className="mt-3 text-muted">{selectedAssetIds.length} asset(s) selected</p>
              </div>
              <div className="modal-footer">
                <button type="button" className="btn btn-secondary" onClick={() => setShowAssignAssets(false)}>
                  Cancel
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={submitAssignAssets}
                  disabled={selectedAssetIds.length === 0 && assetIdsToRemove.length === 0}
                >
                  Save changes
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

      {/* Domains Modal */}
      {domainsModalState.workgroupId !== null && (
        <WorkgroupDomainsModal
          workgroupId={domainsModalState.workgroupId}
          workgroupName={domainsModalState.workgroupName}
          isOpen={domainsModalState.isOpen}
          onClose={() =>
            setDomainsModalState({ isOpen: false, workgroupId: null, workgroupName: '' })
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
              <th>Parent</th>
              <th>Name</th>
              <th>Users</th>
              <th>Assets</th>
              <th>Accounts</th>
              <th>Domains</th>
              <th>Created</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {(() => {
              const visibleWorkgroups = workgroups.filter(
                wg => showAwsWorkgroups || !isAwsWorkgroup(wg.name)
              );
              const hiddenAwsCount = workgroups.length - visibleWorkgroups.length;
              return visibleWorkgroups.length === 0 ? (
              <tr>
                <td colSpan={8} className="text-center text-muted">
                  {hiddenAwsCount > 0
                    ? 'AWS- workgroups are hidden. Enable "Show AWS- workgroups" to see them.'
                    : 'No visible workgroups found.'}
                </td>
              </tr>
            ) : (
              visibleWorkgroups.map(workgroup => {
                return (
                  <tr key={workgroup.id}>
                    <td>
                      {workgroup.parentName
                        ? workgroup.parentName
                        : <span className="text-muted fst-italic">root</span>}
                    </td>
                    <td><strong>{workgroup.name}</strong></td>
                    <td>
                      <span className="badge bg-info">{workgroup.userCount}</span>
                    </td>
                    <td>
                      <span className="badge bg-success">{workgroup.assetCount}</span>
                    </td>
                    <td>
                      <span className="badge bg-secondary">{workgroup.awsAccountsCount ?? 0}</span>
                    </td>
                    <td>
                      <span className="badge bg-secondary">{workgroup.adDomainsCount ?? 0}</span>
                    </td>
                    <td>{formatServerDate(workgroup.createdAt)}</td>
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
                        type="button"
                        className="btn btn-sm btn-secondary ms-1"
                        onClick={() =>
                          setDomainsModalState({
                            isOpen: true,
                            workgroupId: workgroup.id,
                            workgroupName: workgroup.name,
                          })
                        }
                        title="Manage AD domains"
                      >
                        Domains
                      </button>
                      <button
                        className="btn btn-outline-success"
                        onClick={() => handleAssignAssets(workgroup)}
                        title="Manage assets"
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
            );
            })()}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default WorkgroupManagement;
