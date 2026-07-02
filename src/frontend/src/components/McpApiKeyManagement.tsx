import React, { useState, useEffect } from 'react';
import { parseServerDate, formatServerDateTime } from '../utils/dateUtils';

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
  // Feature: 050-mcp-user-delegation
  delegationEnabled: boolean;
  allowedDelegationDomains: string | null;
  delegationDomainCount: number;
}

interface CreateApiKeyRequest {
  name: string;
  permissions: string[];
  expiresAt?: string;
  notes?: string;
  // Feature: 050-mcp-user-delegation
  delegationEnabled?: boolean;
  allowedDelegationDomains?: string;
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
    notes: '',
    delegationEnabled: false,
    allowedDelegationDomains: ''
  });
  const [newApiKey, setNewApiKey] = useState<string | null>(null);
  const [showInactive, setShowInactive] = useState(false);

  const permissionMeta: Record<string, { label: string; description: string; adminOnly?: boolean }> = {
    REQUIREMENTS_READ:    { label: 'Read Requirements',      description: 'View and search security requirements' },
    REQUIREMENTS_WRITE:   { label: 'Write Requirements',     description: 'Create and update security requirements' },
    REQUIREMENTS_DELETE:  { label: 'Delete Requirements',    description: 'Delete security requirements' },
    ASSESSMENTS_READ:     { label: 'Read Assessments',       description: 'View risk assessments and results' },
    ASSESSMENTS_EXECUTE:  { label: 'Execute Assessments',    description: 'Run new risk assessments' },
    ASSESSMENTS_WRITE:    { label: 'Write Assessments',      description: 'Create and update risk assessments' },
    FILES_READ:           { label: 'Read Files',             description: 'Download requirement files and attachments' },
    TAGS_READ:            { label: 'Read Tags',              description: 'View tags and categories' },
    ASSETS_READ:          { label: 'Read Assets',            description: 'View and search asset inventory' },
    ASSETS_WRITE:         { label: 'Write Assets',           description: 'Create and update assets in the inventory' },
    SCANS_READ:           { label: 'Read Scans',             description: 'View scan data and results' },
    VULNERABILITIES_READ: { label: 'Read Vulnerabilities',   description: 'View vulnerability information' },
    TRANSLATION_USE:      { label: 'Use Translations',       description: 'Translate requirements to different languages' },
    SYSTEM_INFO:          { label: 'System Information',     description: 'Access system information and statistics', adminOnly: true },
    USER_ACTIVITY:        { label: 'User Activity',          description: 'Monitor user activity and sessions', adminOnly: true },
    AUDIT_READ:           { label: 'Read Audit Logs',        description: 'View MCP server audit logs', adminOnly: true },
    WORKGROUPS_WRITE:     { label: 'Manage Workgroups',      description: 'Create, delete, and manage workgroup memberships', adminOnly: true },
    NOTIFICATIONS_SEND:   { label: 'Send Notifications',     description: 'Send admin summary emails and trigger notifications', adminOnly: true },
  };

  const permissionGroups = [
    { title: 'Requirements', keys: ['REQUIREMENTS_READ', 'REQUIREMENTS_WRITE', 'REQUIREMENTS_DELETE'] },
    { title: 'Assessments', keys: ['ASSESSMENTS_READ', 'ASSESSMENTS_EXECUTE', 'ASSESSMENTS_WRITE'] },
    { title: 'Assets & Scans', keys: ['ASSETS_READ', 'ASSETS_WRITE', 'SCANS_READ', 'VULNERABILITIES_READ'] },
    { title: 'Files & Tags', keys: ['FILES_READ', 'TAGS_READ', 'TRANSLATION_USE'] },
    { title: 'Admin Only', keys: ['SYSTEM_INFO', 'USER_ACTIVITY', 'AUDIT_READ', 'WORKGROUPS_WRITE', 'NOTIFICATIONS_SEND'] },
  ];

  useEffect(() => {
    fetchApiKeys();
  }, [showInactive]);

  const fetchApiKeys = async () => {
    try {
      setLoading(true);
      // Authentication is handled via HttpOnly cookie (credentials: 'include')
      const url = showInactive ? '/api/mcp/admin/api-keys?include_inactive=true' : '/api/mcp/admin/api-keys';
      const response = await fetch(url, {
        credentials: 'include',
        headers: {
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

    // Feature: 050-mcp-user-delegation - Validate delegation domains
    if (createForm.delegationEnabled) {
      if (!createForm.allowedDelegationDomains?.trim()) {
        setError('Allowed delegation domains are required when delegation is enabled');
        return;
      }
      // Validate domain format
      const domains = createForm.allowedDelegationDomains.split(',').map(d => d.trim()).filter(d => d);
      for (const domain of domains) {
        if (!domain.startsWith('@')) {
          setError(`Invalid domain format: '${domain}' (must start with @)`);
          return;
        }
        if (!domain.includes('.')) {
          setError(`Invalid domain format: '${domain}' (must contain a TLD)`);
          return;
        }
      }
    }

    try {
      const requestBody = {
        name: createForm.name.trim(),
        permissions: createForm.permissions,
        expiresAt: createForm.expiresAt || undefined,
        notes: createForm.notes || undefined,
        delegationEnabled: createForm.delegationEnabled || false,
        allowedDelegationDomains: createForm.delegationEnabled ? createForm.allowedDelegationDomains?.trim() : undefined
      };

      console.log('Creating API key with:', requestBody);

      // Authentication is handled via HttpOnly cookie (credentials: 'include')
      const response = await fetch('/api/mcp/admin/api-keys', {
        method: 'POST',
        credentials: 'include',
        headers: {
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
        setCreateForm({ name: '', permissions: [], expiresAt: '', notes: '', delegationEnabled: false, allowedDelegationDomains: '' });
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
      // Authentication is handled via HttpOnly cookie (credentials: 'include')
      const response = await fetch(`/api/mcp/admin/api-keys/${keyId}?reason=Revoked by user`, {
        method: 'DELETE',
        credentials: 'include'
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
    return formatServerDateTime(dateString, undefined, 'Never');
  };

  const isExpired = (expiresAt: string | null) => {
    if (!expiresAt) return false;
    // Engine-deterministic parse so Active/Expired doesn't flip between Edge and Safari.
    const parsed = parseServerDate(expiresAt);
    return parsed ? parsed.getTime() < Date.now() : false;
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
        <div className="d-flex gap-2 align-items-center">
          <div className="form-check form-switch mb-0">
            <input
              className="form-check-input"
              type="checkbox"
              id="showInactive"
              checked={showInactive}
              onChange={(e) => setShowInactive(e.target.checked)}
            />
            <label className="form-check-label" htmlFor="showInactive">Show Inactive</label>
          </div>
          <button
            className="btn btn-primary"
            onClick={() => setShowCreateForm(true)}
          >
            Create New API Key
          </button>
        </div>
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
                    {permissionGroups.map(group => (
                      <div key={group.title} className="mb-3">
                        <div className="text-muted small fw-semibold mb-1">{group.title}</div>
                        <div className="row">
                          {group.keys.map(permission => {
                            const meta = permissionMeta[permission];
                            return (
                              <div key={permission} className="col-md-6 mb-2">
                                <div className="form-check">
                                  <input
                                    className="form-check-input"
                                    type="checkbox"
                                    id={permission}
                                    checked={createForm.permissions.includes(permission)}
                                    onChange={(e) => handlePermissionChange(permission, e.target.checked)}
                                  />
                                  <label className="form-check-label" htmlFor={permission} title={meta.description}>
                                    {meta.label}
                                    {meta.adminOnly && (
                                      <span className="badge bg-warning text-dark ms-1" style={{ fontSize: '0.65em' }}>Admin</span>
                                    )}
                                  </label>
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    ))}
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

                  {/* User Delegation Section - Feature: 050-mcp-user-delegation */}
                  <div className="card border-info mb-3">
                    <div className="card-header bg-info bg-opacity-10">
                      <h6 className="mb-0">User Delegation</h6>
                    </div>
                    <div className="card-body">
                      <div className="form-check form-switch mb-3">
                        <input
                          className="form-check-input"
                          type="checkbox"
                          id="delegationEnabled"
                          checked={createForm.delegationEnabled || false}
                          onChange={(e) => setCreateForm(prev => ({
                            ...prev,
                            delegationEnabled: e.target.checked,
                            allowedDelegationDomains: e.target.checked ? prev.allowedDelegationDomains : ''
                          }))}
                        />
                        <label className="form-check-label" htmlFor="delegationEnabled">
                          Enable User Delegation
                        </label>
                      </div>
                      <div className="form-text mb-2">
                        When enabled, external tools can pass an authenticated user's email via the <code>X-MCP-User-Email</code> header.
                        Permissions will be the intersection of the user's roles and this API key's permissions.
                      </div>

                      {createForm.delegationEnabled && (
                        <div className="mb-0">
                          <label htmlFor="allowedDelegationDomains" className="form-label">
                            Allowed Email Domains *
                          </label>
                          <input
                            type="text"
                            className="form-control"
                            id="allowedDelegationDomains"
                            value={createForm.allowedDelegationDomains || ''}
                            onChange={(e) => setCreateForm(prev => ({ ...prev, allowedDelegationDomains: e.target.value }))}
                            placeholder="@company.com, @subsidiary.com"
                            required={createForm.delegationEnabled}
                          />
                          <div className="form-text">
                            Comma-separated list of allowed email domains (must start with @).
                            Only users with emails matching these domains can be delegated.
                          </div>
                        </div>
                      )}
                    </div>
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
                <th>Delegation</th>
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
                    {/* Feature: 050-mcp-user-delegation */}
                    {apiKey.delegationEnabled ? (
                      <span
                        className="badge bg-info"
                        title={apiKey.allowedDelegationDomains || 'Delegation enabled'}
                      >
                        Enabled ({apiKey.delegationDomainCount} domain{apiKey.delegationDomainCount !== 1 ? 's' : ''})
                      </span>
                    ) : (
                      <span className="badge bg-light text-muted">Disabled</span>
                    )}
                  </td>
                  <td>
                    {(apiKey.permissions ?? []).map(permission => (
                      <span key={permission} className="badge bg-light text-dark me-1">
                        {permission}
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