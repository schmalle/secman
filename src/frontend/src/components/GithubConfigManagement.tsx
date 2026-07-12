import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { formatServerDateTime } from '../utils/dateUtils';

interface GithubAppConfig {
    id: number;
    appId: string;
    privateKeyPem: string;
    installationId?: string | null;
    organization?: string | null;
    isActive: boolean;
    createdAt: string;
    updatedAt: string;
}

const HIDDEN = '***HIDDEN***';

const emptyForm = { appId: '', privateKeyPem: '', installationId: '', organization: '' };

/**
 * Admin → GitHub App: manage the GitHub App credentials used to import
 * repositories and their Dependabot alert counts. The private key is
 * encrypted at rest and always masked in responses; App ID, installation
 * ID and organization are non-secret identifiers.
 */
const GithubConfigManagement = () => {
    const [configs, setConfigs] = useState<GithubAppConfig[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);
    const [isAdmin, setIsAdmin] = useState(false);
    const [testingId, setTestingId] = useState<number | null>(null);

    // Form state
    const [showCreateForm, setShowCreateForm] = useState(false);
    const [editingConfig, setEditingConfig] = useState<GithubAppConfig | null>(null);
    const [formData, setFormData] = useState(emptyForm);

    useEffect(() => {
        let timeoutId: ReturnType<typeof setTimeout> | null = null;
        let eventHandled = false;

        const resolve = () => {
            const user = (window as any).currentUser;
            const hasAdmin = user?.roles?.includes('ADMIN') || false;
            setIsAdmin(hasAdmin);
            if (hasAdmin) {
                void loadConfigs();
            } else {
                setLoading(false);
            }
        };

        // window.currentUser is populated asynchronously by auth-init.ts.
        // If the island hydrates first, wait for the userLoaded event (with timeout).
        if ((window as any).currentUser !== undefined) {
            resolve();
        } else {
            const onLoaded = () => {
                eventHandled = true;
                if (timeoutId) clearTimeout(timeoutId);
                resolve();
            };
            window.addEventListener('userLoaded', onLoaded, { once: true });
            timeoutId = setTimeout(() => {
                if (!eventHandled) {
                    window.removeEventListener('userLoaded', onLoaded);
                    setIsAdmin(false);
                    setLoading(false);
                }
            }, 5000);
        }

        return () => {
            if (timeoutId) clearTimeout(timeoutId);
        };
    }, []);

    const loadConfigs = async () => {
        try {
            setLoading(true);
            setError(null);
            const response = await axios.get('/api/github-config');
            setConfigs(response.data);
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to load GitHub App configurations');
        } finally {
            setLoading(false);
        }
    };

    const handleCreate = async (e: React.FormEvent) => {
        e.preventDefault();
        try {
            setError(null);
            setSuccess(null);
            await axios.post('/api/github-config', {
                appId: formData.appId,
                privateKeyPem: formData.privateKeyPem,
                installationId: formData.installationId || null,
                organization: formData.organization || null
            });
            setSuccess('GitHub App configuration created successfully');
            setShowCreateForm(false);
            setFormData(emptyForm);
            await loadConfigs();
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to create GitHub App configuration');
        }
    };

    const handleUpdate = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!editingConfig) return;
        try {
            setError(null);
            setSuccess(null);
            const updateData: any = {
                appId: formData.appId,
                installationId: formData.installationId,
                organization: formData.organization
            };
            // Only send the key when the admin actually entered a new one
            if (formData.privateKeyPem && formData.privateKeyPem !== HIDDEN) {
                updateData.privateKeyPem = formData.privateKeyPem;
            }
            await axios.put(`/api/github-config/${editingConfig.id}`, updateData);
            setSuccess('GitHub App configuration updated successfully');
            setEditingConfig(null);
            setFormData(emptyForm);
            await loadConfigs();
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to update GitHub App configuration');
        }
    };

    const handleDelete = async (id: number) => {
        if (!confirm('Are you sure you want to delete this GitHub App configuration?')) {
            return;
        }
        try {
            setError(null);
            setSuccess(null);
            await axios.delete(`/api/github-config/${id}`);
            setSuccess('GitHub App configuration deleted successfully');
            await loadConfigs();
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to delete GitHub App configuration');
        }
    };

    const handleActivate = async (id: number) => {
        try {
            setError(null);
            setSuccess(null);
            await axios.post(`/api/github-config/${id}/activate`, {});
            setSuccess('GitHub App configuration activated successfully');
            await loadConfigs();
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to activate GitHub App configuration');
        }
    };

    const handleTest = async (id: number) => {
        try {
            setError(null);
            setSuccess(null);
            setTestingId(id);
            const response = await axios.post(`/api/github-config/${id}/test`, {});
            if (response.data.success) {
                setSuccess(`Connection test succeeded: ${response.data.message}`);
            } else {
                setError(`Connection test failed: ${response.data.message}`);
            }
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to test GitHub App configuration');
        } finally {
            setTestingId(null);
        }
    };

    const startEdit = (config: GithubAppConfig) => {
        setEditingConfig(config);
        setFormData({
            appId: config.appId,
            privateKeyPem: config.privateKeyPem,
            installationId: config.installationId ?? '',
            organization: config.organization ?? ''
        });
        setShowCreateForm(false);
    };

    const cancelEdit = () => {
        setEditingConfig(null);
        setFormData(emptyForm);
    };

    const startCreate = () => {
        setShowCreateForm(true);
        setEditingConfig(null);
        setFormData(emptyForm);
    };

    if (loading) {
        return (
            <div className="container mt-4">
                <div className="d-flex justify-content-center">
                    <div className="spinner-border" role="status">
                        <span className="visually-hidden">Checking permissions…</span>
                    </div>
                </div>
            </div>
        );
    }

    if (!isAdmin) {
        return (
            <div className="container mt-4">
                <div className="alert alert-danger">
                    <i className="bi bi-shield-exclamation me-2"></i>
                    Access Denied: You do not have permission to view this page.
                </div>
            </div>
        );
    }

    return (
        <div className="container mt-4">
            <div className="d-flex justify-content-between align-items-center mb-4">
                <h2>
                    <i className="bi bi-github me-2"></i>
                    GitHub App Configuration
                </h2>
                {!showCreateForm && !editingConfig && (
                    <button className="btn btn-primary" onClick={startCreate}>
                        <i className="bi bi-plus-circle me-2"></i>
                        Add Configuration
                    </button>
                )}
            </div>

            {error && (
                <div className="alert alert-danger alert-dismissible fade show" role="alert">
                    <i className="bi bi-exclamation-triangle me-2"></i>
                    {error}
                    <button type="button" className="btn-close" onClick={() => setError(null)}></button>
                </div>
            )}

            {success && (
                <div className="alert alert-success alert-dismissible fade show" role="alert">
                    <i className="bi bi-check-circle me-2"></i>
                    {success}
                    <button type="button" className="btn-close" onClick={() => setSuccess(null)}></button>
                </div>
            )}

            {(showCreateForm || editingConfig) && (
                <div className="card mb-4">
                    <div className="card-header">
                        <h5 className="mb-0">
                            {editingConfig ? 'Edit Configuration' : 'Create New Configuration'}
                        </h5>
                    </div>
                    <div className="card-body">
                        <form onSubmit={editingConfig ? handleUpdate : handleCreate}>
                            <div className="mb-3">
                                <label htmlFor="appId" className="form-label">
                                    App ID <span className="text-danger">*</span>
                                </label>
                                <input
                                    type="text"
                                    className="form-control"
                                    id="appId"
                                    value={formData.appId}
                                    onChange={(e) => setFormData({ ...formData, appId: e.target.value })}
                                    placeholder="e.g. 123456"
                                    required
                                />
                                <small className="form-text text-muted">
                                    The numeric App ID from your GitHub App's settings page.
                                </small>
                            </div>

                            <div className="mb-3">
                                <label htmlFor="privateKeyPem" className="form-label">
                                    Private Key (PEM) <span className="text-danger">*</span>
                                </label>
                                <textarea
                                    className="form-control font-monospace"
                                    id="privateKeyPem"
                                    rows={6}
                                    value={formData.privateKeyPem}
                                    onChange={(e) => setFormData({ ...formData, privateKeyPem: e.target.value })}
                                    placeholder={editingConfig
                                        ? 'Leave unchanged or paste a new key'
                                        : '-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----'}
                                    required={!editingConfig}
                                />
                                <small className="form-text text-muted">
                                    {editingConfig
                                        ? 'Current key is hidden. Paste a new PEM key to replace it.'
                                        : "Generate under your GitHub App's settings → scroll to Private keys " +
                                          '(below Client secrets, above Danger Zone) → Generate a private key ' +
                                          '— this downloads the .pem file to paste here. Not the Client ID / ' +
                                          'Client secret shown further up that page; those support a separate ' +
                                          "interactive sign-in flow this integration doesn't use."}
                                </small>
                            </div>

                            <div className="mb-3">
                                <label htmlFor="installationId" className="form-label">Installation ID</label>
                                <input
                                    type="text"
                                    className="form-control"
                                    id="installationId"
                                    value={formData.installationId}
                                    onChange={(e) => setFormData({ ...formData, installationId: e.target.value })}
                                    placeholder="Optional — resolved via organization when empty"
                                />
                                <small className="form-text text-muted">
                                    Optional. The numeric installation ID; leave empty to resolve it from the organization.
                                </small>
                            </div>

                            <div className="mb-3">
                                <label htmlFor="organization" className="form-label">Organization</label>
                                <input
                                    type="text"
                                    className="form-control"
                                    id="organization"
                                    value={formData.organization}
                                    onChange={(e) => setFormData({ ...formData, organization: e.target.value })}
                                    placeholder="Optional — org login the App is installed on"
                                />
                                <small className="form-text text-muted">
                                    Optional. Used to pick the right installation when the App is installed on multiple accounts.
                                </small>
                            </div>

                            <div className="d-flex gap-2">
                                <button type="submit" className="btn btn-primary">
                                    <i className="bi bi-save me-2"></i>
                                    {editingConfig ? 'Update' : 'Create'}
                                </button>
                                <button
                                    type="button"
                                    className="btn btn-secondary"
                                    onClick={() => {
                                        setShowCreateForm(false);
                                        cancelEdit();
                                    }}
                                >
                                    Cancel
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {configs.length === 0 ? (
                <div className="alert alert-info">
                    <i className="bi bi-info-circle me-2"></i>
                    No GitHub App configurations found. Create one to get started.
                </div>
            ) : (
                <div className="card">
                    <div className="card-header">
                        <h5 className="mb-0">Existing Configurations</h5>
                    </div>
                    <div className="card-body">
                        <div className="table-responsive">
                            <table className="table table-hover">
                                <thead>
                                    <tr>
                                        <th>Status</th>
                                        <th>App ID</th>
                                        <th>Installation</th>
                                        <th>Organization</th>
                                        <th>Created</th>
                                        <th>Updated</th>
                                        <th>Actions</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {configs.map(config => (
                                        <tr key={config.id}>
                                            <td>
                                                {config.isActive ? (
                                                    <span className="badge bg-success">Active</span>
                                                ) : (
                                                    <span className="badge bg-secondary">Inactive</span>
                                                )}
                                            </td>
                                            <td><code>{config.appId}</code></td>
                                            <td>{config.installationId || <span className="text-muted">auto</span>}</td>
                                            <td>{config.organization || <span className="text-muted">—</span>}</td>
                                            <td><small>{formatServerDateTime(config.createdAt)}</small></td>
                                            <td><small>{formatServerDateTime(config.updatedAt)}</small></td>
                                            <td>
                                                <div className="btn-group btn-group-sm" role="group">
                                                    <button
                                                        className="btn btn-outline-info"
                                                        onClick={() => handleTest(config.id)}
                                                        disabled={testingId === config.id}
                                                        title="Test connection to GitHub"
                                                    >
                                                        {testingId === config.id ? (
                                                            <span className="spinner-border spinner-border-sm"></span>
                                                        ) : (
                                                            <i className="bi bi-plug"></i>
                                                        )}
                                                    </button>
                                                    {!config.isActive && (
                                                        <button
                                                            className="btn btn-outline-success"
                                                            onClick={() => handleActivate(config.id)}
                                                            title="Activate this configuration"
                                                        >
                                                            <i className="bi bi-check-circle"></i>
                                                        </button>
                                                    )}
                                                    <button
                                                        className="btn btn-outline-primary"
                                                        onClick={() => startEdit(config)}
                                                        title="Edit configuration"
                                                    >
                                                        <i className="bi bi-pencil"></i>
                                                    </button>
                                                    <button
                                                        className="btn btn-outline-danger"
                                                        onClick={() => handleDelete(config.id)}
                                                        title="Delete configuration"
                                                    >
                                                        <i className="bi bi-trash"></i>
                                                    </button>
                                                </div>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            )}

            <div className="card mt-4">
                <div className="card-header">
                    <h5 className="mb-0">
                        <i className="bi bi-info-circle me-2"></i>
                        About the GitHub App Integration
                    </h5>
                </div>
                <div className="card-body">
                    <p>
                        These credentials let secman list the repositories your GitHub App can access and
                        count their open Dependabot alerts (Vulnerability Management → GitHub Repos).
                        Only one configuration can be active at a time.
                    </p>
                    <h6>Required GitHub App permissions:</h6>
                    <ul>
                        <li><strong>Metadata</strong> — Read-only (list repositories)</li>
                        <li><strong>Dependabot alerts</strong> — Read-only (count open alerts)</li>
                    </ul>
                    <div className="alert alert-warning mb-0">
                        <i className="bi bi-shield-exclamation me-2"></i>
                        <strong>Security Note:</strong> The private key is encrypted at rest in the database
                        and never returned by the API.
                    </div>
                </div>
            </div>
        </div>
    );
};

export default GithubConfigManagement;
