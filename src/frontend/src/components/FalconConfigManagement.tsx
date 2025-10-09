import React, { useState, useEffect } from 'react';
import axios from 'axios';

interface FalconConfig {
    id: number;
    clientId: string;
    clientSecret: string;
    cloudRegion: string;
    isActive: boolean;
    createdAt: string;
    updatedAt: string;
}

const VALID_REGIONS = ['us-1', 'us-2', 'eu-1', 'us-gov-1', 'us-gov-2'];

const FalconConfigManagement = () => {
    const [configs, setConfigs] = useState<FalconConfig[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);
    const [isAdmin, setIsAdmin] = useState(false);

    // Form state
    const [showCreateForm, setShowCreateForm] = useState(false);
    const [editingConfig, setEditingConfig] = useState<FalconConfig | null>(null);
    const [formData, setFormData] = useState({
        clientId: '',
        clientSecret: '',
        cloudRegion: 'us-1'
    });

    useEffect(() => {
        checkAdminAndLoadConfigs();
    }, []);

    const checkAdminAndLoadConfigs = async () => {
        const user = (window as any).currentUser;
        const hasAdmin = user?.roles?.includes('ADMIN') || false;
        setIsAdmin(hasAdmin);

        if (hasAdmin) {
            await loadConfigs();
        } else {
            setLoading(false);
        }
    };

    const loadConfigs = async () => {
        try {
            setLoading(true);
            setError(null);
            
            const token = localStorage.getItem('authToken');
            const response = await axios.get('/api/falcon-config', {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            
            setConfigs(response.data);
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to load Falcon configurations');
        } finally {
            setLoading(false);
        }
    };

    const handleCreate = async (e: React.FormEvent) => {
        e.preventDefault();
        
        try {
            setError(null);
            setSuccess(null);
            
            const token = localStorage.getItem('authToken');
            await axios.post('/api/falcon-config', formData, {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            
            setSuccess('Falcon configuration created successfully');
            setShowCreateForm(false);
            setFormData({ clientId: '', clientSecret: '', cloudRegion: 'us-1' });
            await loadConfigs();
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to create Falcon configuration');
        }
    };

    const handleUpdate = async (e: React.FormEvent) => {
        e.preventDefault();
        
        if (!editingConfig) return;
        
        try {
            setError(null);
            setSuccess(null);
            
            const token = localStorage.getItem('authToken');
            
            // Only send fields that are not masked
            const updateData: any = {
                cloudRegion: formData.cloudRegion
            };
            
            if (formData.clientId && formData.clientId !== '***HIDDEN***') {
                updateData.clientId = formData.clientId;
            }
            
            if (formData.clientSecret && formData.clientSecret !== '***HIDDEN***') {
                updateData.clientSecret = formData.clientSecret;
            }
            
            await axios.put(`/api/falcon-config/${editingConfig.id}`, updateData, {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            
            setSuccess('Falcon configuration updated successfully');
            setEditingConfig(null);
            setFormData({ clientId: '', clientSecret: '', cloudRegion: 'us-1' });
            await loadConfigs();
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to update Falcon configuration');
        }
    };

    const handleDelete = async (id: number) => {
        if (!confirm('Are you sure you want to delete this Falcon configuration?')) {
            return;
        }
        
        try {
            setError(null);
            setSuccess(null);
            
            const token = localStorage.getItem('authToken');
            await axios.delete(`/api/falcon-config/${id}`, {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            
            setSuccess('Falcon configuration deleted successfully');
            await loadConfigs();
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to delete Falcon configuration');
        }
    };

    const handleActivate = async (id: number) => {
        try {
            setError(null);
            setSuccess(null);
            
            const token = localStorage.getItem('authToken');
            await axios.post(`/api/falcon-config/${id}/activate`, {}, {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            
            setSuccess('Falcon configuration activated successfully');
            await loadConfigs();
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to activate Falcon configuration');
        }
    };

    const startEdit = (config: FalconConfig) => {
        setEditingConfig(config);
        setFormData({
            clientId: config.clientId,
            clientSecret: config.clientSecret,
            cloudRegion: config.cloudRegion
        });
        setShowCreateForm(false);
    };

    const cancelEdit = () => {
        setEditingConfig(null);
        setFormData({ clientId: '', clientSecret: '', cloudRegion: 'us-1' });
    };

    const startCreate = () => {
        setShowCreateForm(true);
        setEditingConfig(null);
        setFormData({ clientId: '', clientSecret: '', cloudRegion: 'us-1' });
    };

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

    if (loading) {
        return (
            <div className="container mt-4">
                <div className="text-center">
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
                <h2>
                    <i className="bi bi-shield-lock me-2"></i>
                    CrowdStrike Falcon Configuration
                </h2>
                {!showCreateForm && !editingConfig && (
                    <button 
                        className="btn btn-primary"
                        onClick={startCreate}
                    >
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
                                <label htmlFor="clientId" className="form-label">
                                    Client ID <span className="text-danger">*</span>
                                </label>
                                <input
                                    type="text"
                                    className="form-control"
                                    id="clientId"
                                    value={formData.clientId}
                                    onChange={(e) => setFormData({ ...formData, clientId: e.target.value })}
                                    placeholder={editingConfig ? 'Leave unchanged or enter new value' : 'Enter Falcon API Client ID'}
                                    required={!editingConfig}
                                />
                                <small className="form-text text-muted">
                                    {editingConfig ? 'Current value is hidden. Enter a new value to update.' : 'Your CrowdStrike Falcon API Client ID'}
                                </small>
                            </div>

                            <div className="mb-3">
                                <label htmlFor="clientSecret" className="form-label">
                                    Client Secret <span className="text-danger">*</span>
                                </label>
                                <input
                                    type="password"
                                    className="form-control"
                                    id="clientSecret"
                                    value={formData.clientSecret}
                                    onChange={(e) => setFormData({ ...formData, clientSecret: e.target.value })}
                                    placeholder={editingConfig ? 'Leave unchanged or enter new value' : 'Enter Falcon API Client Secret'}
                                    required={!editingConfig}
                                />
                                <small className="form-text text-muted">
                                    {editingConfig ? 'Current value is hidden. Enter a new value to update.' : 'Your CrowdStrike Falcon API Client Secret'}
                                </small>
                            </div>

                            <div className="mb-3">
                                <label htmlFor="cloudRegion" className="form-label">
                                    Cloud Region <span className="text-danger">*</span>
                                </label>
                                <select
                                    className="form-select"
                                    id="cloudRegion"
                                    value={formData.cloudRegion}
                                    onChange={(e) => setFormData({ ...formData, cloudRegion: e.target.value })}
                                    required
                                >
                                    {VALID_REGIONS.map(region => (
                                        <option key={region} value={region}>{region}</option>
                                    ))}
                                </select>
                                <small className="form-text text-muted">
                                    Select the CrowdStrike cloud region for your API endpoint
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
                    No Falcon configurations found. Create one to get started.
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
                                        <th>Cloud Region</th>
                                        <th>Client ID</th>
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
                                            <td>
                                                <code>{config.cloudRegion}</code>
                                            </td>
                                            <td>
                                                <span className="text-muted font-monospace">
                                                    {config.clientId}
                                                </span>
                                            </td>
                                            <td>
                                                <small>{new Date(config.createdAt).toLocaleString()}</small>
                                            </td>
                                            <td>
                                                <small>{new Date(config.updatedAt).toLocaleString()}</small>
                                            </td>
                                            <td>
                                                <div className="btn-group btn-group-sm" role="group">
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
                        About CrowdStrike Falcon Integration
                    </h5>
                </div>
                <div className="card-body">
                    <p>
                        This configuration is used by the Falcon vulnerability helper tool to query the CrowdStrike Falcon API.
                        Only one configuration can be active at a time.
                    </p>
                    <h6>Valid Cloud Regions:</h6>
                    <ul>
                        <li><code>us-1</code> - US Commercial 1</li>
                        <li><code>us-2</code> - US Commercial 2</li>
                        <li><code>eu-1</code> - EU Cloud</li>
                        <li><code>us-gov-1</code> - US Government 1</li>
                        <li><code>us-gov-2</code> - US Government 2</li>
                    </ul>
                    <div className="alert alert-warning">
                        <i className="bi bi-shield-exclamation me-2"></i>
                        <strong>Security Note:</strong> Client ID and Client Secret are encrypted at rest in the database.
                    </div>
                </div>
            </div>
        </div>
    );
};

export default FalconConfigManagement;
