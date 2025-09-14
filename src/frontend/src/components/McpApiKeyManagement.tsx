import React, { useState, useEffect } from 'react';

interface ApiKey {
  id: number;
  keyId: string;
  name: string;
  permissions: string[];
  isActive: boolean;
  lastUsedAt: string | null;
  expiresAt: string | null;
  createdAt: string;
  notes: string | null;
}

interface CreateApiKeyRequest {
  name: string;
  permissions: string[];
  expiresAt?: string;
  notes?: string;
}

const McpApiKeyManagement: React.FC = () => {
  const [apiKeys, setApiKeys] = useState<ApiKey[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [createForm, setCreateForm] = useState<CreateApiKeyRequest>({
    name: '',
    permissions: [],
    expiresAt: '',
    notes: ''
  });
  const [newApiKey, setNewApiKey] = useState<string | null>(null);

  const availablePermissions = [
    'REQUIREMENTS_READ',
    'REQUIREMENTS_WRITE',
    'ASSESSMENTS_READ',
    'ASSESSMENTS_WRITE',
    'TAGS_READ',
    'SYSTEM_INFO',
    'USER_ACTIVITY'
  ];

  useEffect(() => {
    fetchApiKeys();
  }, []);

  const fetchApiKeys = async () => {
    try {
      setLoading(true);
      const token = localStorage.getItem('authToken');
      const response = await fetch('/api/mcp/admin/api-keys', {
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        }
      });

      if (response.ok) {
        const data = await response.json();
        setApiKeys(data.apiKeys || []);
      } else {
        setError('Failed to fetch API keys');
      }
    } catch (err) {
      setError('Network error while fetching API keys');
    } finally {
      setLoading(false);
    }
  };

  const createApiKey = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null); // Clear previous errors

    if (!createForm.name.trim()) {
      setError('API key name is required');
      return;
    }

    if (createForm.permissions.length === 0) {
      setError('Please select at least one permission from the checkboxes above');
      return;
    }

    try {
      const token = localStorage.getItem('authToken');
      if (!token) {
        setError('You must be logged in to create API keys');
        return;
      }

      const requestBody = {
        name: createForm.name.trim(),
        permissions: createForm.permissions,
        expiresAt: createForm.expiresAt || undefined,
        notes: createForm.notes || undefined
      };

      console.log('Creating API key with:', requestBody);

      const response = await fetch('/api/mcp/admin/api-keys', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(requestBody)
      });

      console.log('Response status:', response.status);

      if (response.ok) {
        const data = await response.json();
        console.log('API key created:', data);
        setNewApiKey(data.apiKey);
        setShowCreateForm(false);
        setCreateForm({ name: '', permissions: [], expiresAt: '', notes: '' });
        fetchApiKeys();
        setError(null);
      } else {
        const errorData = await response.json().catch(() => ({ error: { message: 'Unknown error' } }));
        console.error('API key creation failed:', response.status, errorData);
        setError(errorData.error?.message || `Failed to create API key (${response.status})`);
      }
    } catch (err) {
      console.error('Network error:', err);
      setError('Network error while creating API key');
    }
  };

  const revokeApiKey = async (keyId: string, name: string) => {
    if (!confirm(`Are you sure you want to revoke the API key "${name}"? This action cannot be undone.`)) {
      return;
    }

    try {
      const token = localStorage.getItem('authToken');
      const response = await fetch(`/api/mcp/admin/api-keys/${keyId}?reason=Revoked by user`, {
        method: 'DELETE',
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });

      if (response.ok) {
        fetchApiKeys();
        setError(null);
      } else {
        setError('Failed to revoke API key');
      }
    } catch (err) {
      setError('Network error while revoking API key');
    }
  };

  const handlePermissionChange = (permission: string, checked: boolean) => {
    setCreateForm(prev => ({
      ...prev,
      permissions: checked
        ? [...prev.permissions, permission]
        : prev.permissions.filter(p => p !== permission)
    }));
  };

  const formatDate = (dateString: string | null) => {
    if (!dateString) return 'Never';
    return new Date(dateString).toLocaleString();
  };

  const isExpired = (expiresAt: string | null) => {
    if (!expiresAt) return false;
    return new Date(expiresAt) < new Date();
  };

  const getStatusBadge = (apiKey: ApiKey) => {
    if (!apiKey.isActive) {
      return <span className="badge bg-secondary">Inactive</span>;
    }
    if (isExpired(apiKey.expiresAt)) {
      return <span className="badge bg-danger">Expired</span>;
    }
    return <span className="badge bg-success">Active</span>;
  };

  if (loading) {
    return (
      <div className="container mt-4">
        <div className="d-flex justify-content-center">
          <div className="spinner-border" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="container mt-4">
      <div className="d-flex justify-content-between align-items-center mb-4">
        <h2>MCP API Keys</h2>
        <button
          className="btn btn-primary"
          onClick={() => setShowCreateForm(true)}
        >
          Create New API Key
        </button>
      </div>

      {error && (
        <div className="alert alert-danger alert-dismissible fade show" role="alert">
          {error}
          <button
            type="button"
            className="btn-close"
            onClick={() => setError(null)}
          ></button>
        </div>
      )}

      {newApiKey && (
        <div className="alert alert-success alert-dismissible fade show" role="alert">
          <h5>API Key Created Successfully!</h5>
          <p>Your new API key (copy this now, it won't be shown again):</p>
          <code className="d-block p-2 bg-light border rounded">{newApiKey}</code>
          <button
            type="button"
            className="btn-close"
            onClick={() => setNewApiKey(null)}
          ></button>
        </div>
      )}

      {/* Create API Key Modal */}
      {showCreateForm && (
        <div className="modal show d-block" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog">
            <div className="modal-content">
              <div className="modal-header">
                <h5 className="modal-title">Create New MCP API Key</h5>
                <button
                  type="button"
                  className="btn-close"
                  onClick={() => setShowCreateForm(false)}
                ></button>
              </div>
              <form onSubmit={createApiKey}>
                <div className="modal-body">
                  <div className="mb-3">
                    <label htmlFor="keyName" className="form-label">Name *</label>
                    <input
                      type="text"
                      className="form-control"
                      id="keyName"
                      value={createForm.name}
                      onChange={(e) => setCreateForm(prev => ({ ...prev, name: e.target.value }))}
                      placeholder="e.g., Claude Desktop Integration"
                      required
                    />
                  </div>

                  <div className="mb-3">
                    <label className="form-label">Permissions *</label>
                    <div className="row">
                      {availablePermissions.map(permission => (
                        <div key={permission} className="col-md-6 mb-2">
                          <div className="form-check">
                            <input
                              className="form-check-input"
                              type="checkbox"
                              id={permission}
                              checked={createForm.permissions.includes(permission)}
                              onChange={(e) => handlePermissionChange(permission, e.target.checked)}
                            />
                            <label className="form-check-label" htmlFor={permission}>
                              {permission.replace(/_/g, ' ')}
                            </label>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>

                  <div className="mb-3">
                    <label htmlFor="expiresAt" className="form-label">Expires At (Optional)</label>
                    <input
                      type="datetime-local"
                      className="form-control"
                      id="expiresAt"
                      value={createForm.expiresAt}
                      onChange={(e) => setCreateForm(prev => ({ ...prev, expiresAt: e.target.value }))}
                    />
                    <div className="form-text">Leave empty for no expiration</div>
                  </div>

                  <div className="mb-3">
                    <label htmlFor="notes" className="form-label">Notes</label>
                    <textarea
                      className="form-control"
                      id="notes"
                      rows={3}
                      value={createForm.notes}
                      onChange={(e) => setCreateForm(prev => ({ ...prev, notes: e.target.value }))}
                      placeholder="Optional notes about this API key"
                    />
                  </div>
                </div>
                <div className="modal-footer">
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={() => setShowCreateForm(false)}
                  >
                    Cancel
                  </button>
                  <button type="submit" className="btn btn-primary">
                    Create API Key
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}

      {/* API Keys Table */}
      {apiKeys.length === 0 ? (
        <div className="text-center py-5">
          <h5>No API Keys Found</h5>
          <p className="text-muted">Create your first MCP API key to get started.</p>
        </div>
      ) : (
        <div className="table-responsive">
          <table className="table table-striped">
            <thead>
              <tr>
                <th>Name</th>
                <th>Status</th>
                <th>Permissions</th>
                <th>Last Used</th>
                <th>Expires</th>
                <th>Created</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {apiKeys.map(apiKey => (
                <tr key={apiKey.id}>
                  <td>
                    <strong>{apiKey.name}</strong>
                    {apiKey.notes && (
                      <small className="d-block text-muted">{apiKey.notes}</small>
                    )}
                  </td>
                  <td>{getStatusBadge(apiKey)}</td>
                  <td>
                    {apiKey.permissions.map(permission => (
                      <span key={permission} className="badge bg-light text-dark me-1">
                        {permission.replace(/_/g, ' ')}
                      </span>
                    ))}
                  </td>
                  <td>{formatDate(apiKey.lastUsedAt)}</td>
                  <td>
                    {apiKey.expiresAt ? (
                      <span className={isExpired(apiKey.expiresAt) ? 'text-danger' : ''}>
                        {formatDate(apiKey.expiresAt)}
                      </span>
                    ) : (
                      'Never'
                    )}
                  </td>
                  <td>{formatDate(apiKey.createdAt)}</td>
                  <td>
                    {apiKey.isActive && (
                      <button
                        className="btn btn-outline-danger btn-sm"
                        onClick={() => revokeApiKey(apiKey.keyId, apiKey.name)}
                      >
                        Revoke
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default McpApiKeyManagement;