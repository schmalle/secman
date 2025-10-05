import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

interface Workgroup {
  id: number;
  name: string;
  description?: string;
  userCount: number;
  assetCount: number;
  createdAt: string;
  updatedAt: string;
}

interface User {
  id: number;
  username: string;
  email: string;
}

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
  const [formData, setFormData] = useState<{ name: string; description: string }>({
    name: '',
    description: ''
  });
  const [showAssignUsers, setShowAssignUsers] = useState(false);
  const [showAssignAssets, setShowAssignAssets] = useState(false);
  const [selectedWorkgroup, setSelectedWorkgroup] = useState<Workgroup | null>(null);
  const [selectedUserIds, setSelectedUserIds] = useState<number[]>([]);
  const [selectedAssetIds, setSelectedAssetIds] = useState<number[]>([]);

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
    try {
      const response = await authenticatedGet('/api/users');
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
    setFormData({ name: workgroup.name, description: workgroup.description || '' });
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
    setSelectedUserIds([]);
    setShowAssignUsers(true);
  };

  const handleAssignAssets = (workgroup: Workgroup) => {
    setSelectedWorkgroup(workgroup);
    setSelectedAssetIds([]);
    setShowAssignAssets(true);
  };

  const submitAssignUsers = async () => {
    if (!selectedWorkgroup || selectedUserIds.length === 0) {
      setError('Please select at least one user');
      return;
    }

    try {
      const response = await authenticatedPost(`/api/workgroups/${selectedWorkgroup.id}/users`, {
        userIds: selectedUserIds
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
        throw new Error(errorData.error || `Failed to assign users: ${response.status}`);
      }

      await fetchWorkgroups();
      setShowAssignUsers(false);
      setSelectedWorkgroup(null);
      setSelectedUserIds([]);
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

  const toggleUserSelection = (userId: number) => {
    setSelectedUserIds(prev =>
      prev.includes(userId) ? prev.filter(id => id !== userId) : [...prev, userId]
    );
  };

  const toggleAssetSelection = (assetId: number) => {
    setSelectedAssetIds(prev =>
      prev.includes(assetId) ? prev.filter(id => id !== assetId) : [...prev, assetId]
    );
  };

  const resetForm = () => {
    setFormData({ name: '', description: '' });
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
                  <div className="mb-3">
                    <label className="form-label">Name *</label>
                    <input
                      type="text"
                      className="form-control"
                      value={formData.name}
                      onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                      required
                      pattern="[a-zA-Z0-9 -]+"
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
                <p className="text-muted">Select users to assign to this workgroup:</p>
                <div className="list-group" style={{ maxHeight: '400px', overflowY: 'auto' }}>
                  {users.map(user => (
                    <label key={user.id} className="list-group-item list-group-item-action">
                      <input
                        type="checkbox"
                        className="form-check-input me-2"
                        checked={selectedUserIds.includes(user.id)}
                        onChange={() => toggleUserSelection(user.id)}
                      />
                      <strong>{user.username}</strong> ({user.email})
                    </label>
                  ))}
                </div>
                <p className="mt-3 text-muted">{selectedUserIds.length} user(s) selected</p>
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
                <div className="list-group" style={{ maxHeight: '400px', overflowY: 'auto' }}>
                  {assets.map(asset => (
                    <label key={asset.id} className="list-group-item list-group-item-action">
                      <input
                        type="checkbox"
                        className="form-check-input me-2"
                        checked={selectedAssetIds.includes(asset.id)}
                        onChange={() => toggleAssetSelection(asset.id)}
                      />
                      <strong>{asset.name}</strong> ({asset.type})
                    </label>
                  ))}
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

      {/* Workgroups Table */}
      <div className="table-responsive">
        <table className="table table-striped table-hover">
          <thead>
            <tr>
              <th>Name</th>
              <th>Description</th>
              <th>Users</th>
              <th>Assets</th>
              <th>Created</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {workgroups.length === 0 ? (
              <tr>
                <td colSpan={6} className="text-center text-muted">
                  No workgroups found. Create one to get started.
                </td>
              </tr>
            ) : (
              workgroups.map(workgroup => (
                <tr key={workgroup.id}>
                  <td><strong>{workgroup.name}</strong></td>
                  <td>{workgroup.description || <span className="text-muted">-</span>}</td>
                  <td>
                    <span className="badge bg-info">{workgroup.userCount}</span>
                  </td>
                  <td>
                    <span className="badge bg-success">{workgroup.assetCount}</span>
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
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default WorkgroupManagement;
