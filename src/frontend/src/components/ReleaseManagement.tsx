import React, { useState, useEffect } from 'react';
import { authenticatedFetch } from '../utils/auth';

interface Release {
    id: number;
    version: string;
    name: string;
    description: string;
    status: 'DRAFT' | 'ACTIVE' | 'PUBLISHED' | 'ARCHIVED';
    requirementCount: number;
    releaseDate: string | null;
    createdBy: string;
    createdAt: string;
    updatedAt: string;
}

interface ReleaseStats {
    currentRelease: Release | null;
    draftCount: number;
    totalReleases: number;
    activeAssessments: number;
}

const ReleaseManagement = () => {
    const [releases, setReleases] = useState<Release[]>([]);
    const [stats, setStats] = useState<ReleaseStats | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showCreateModal, setShowCreateModal] = useState(false);
    const [selectedRelease, setSelectedRelease] = useState<Release | null>(null);
    const [showEditModal, setShowEditModal] = useState(false);

    // Form state
    const [formData, setFormData] = useState({
        version: '',
        name: '',
        description: ''
    });

    useEffect(() => {
        loadData();
    }, []);

    const loadData = async () => {
        try {
            setLoading(true);
            const releasesResponse = await authenticatedFetch('/api/releases');

            if (!releasesResponse.ok) {
                throw new Error('Failed to load data');
            }

            const releasesData = await releasesResponse.json();
            setReleases(releasesData);

            // Calculate stats from releases data
            const publishedReleases = releasesData.filter((r: Release) => r.status === 'PUBLISHED');
            const draftReleases = releasesData.filter((r: Release) => r.status === 'DRAFT');

            setStats({
                currentRelease: publishedReleases[0] || null,
                draftCount: draftReleases.length,
                totalReleases: releasesData.length,
                activeAssessments: 0
            });
        } catch (err) {
            setError('Failed to load release data');
            console.error('Error loading data:', err);
        } finally {
            setLoading(false);
        }
    };

    const validateVersion = (version: string): string | null => {
        if (!version.trim()) {
            return 'Version is required';
        }
        // Check semantic versioning format (major.minor.patch)
        if (!/^\d+\.\d+\.\d+$/.test(version.trim())) {
            return 'Version must follow semantic versioning format (e.g., 1.0.0)';
        }
        return null;
    };

    const handleCreateRelease = async () => {
        try {
            // Validate version format before sending request
            const versionError = validateVersion(formData.version);
            if (versionError) {
                setError(versionError);
                return;
            }

            if (!formData.name.trim()) {
                setError('Release name is required');
                return;
            }

            const response = await authenticatedFetch('/api/releases', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(formData)
            });

            if (!response.ok) {
                if (response.status === 401) {
                    setError('Authentication required. Please log in again.');
                    // Optionally redirect to login
                    setTimeout(() => {
                        window.location.href = '/login';
                    }, 2000);
                    return;
                }
                const errorData = await response.json();
                throw new Error(errorData.error || 'Failed to create release');
            }

            setShowCreateModal(false);
            setFormData({ version: '', name: '', description: '' });
            setError(null); // Clear any previous errors
            loadData();
        } catch (err) {
            console.error('Error creating release:', err);
            setError(err instanceof Error ? err.message : 'Failed to create release');
        }
    };

    const handleEditRelease = async () => {
        if (!selectedRelease) return;

        try {
            const response = await authenticatedFetch(`/api/releases/${selectedRelease.id}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(formData)
            });

            if (!response.ok) {
                const errorData = await response.json();
                throw new Error(errorData.error || 'Failed to update release');
            }

            setShowEditModal(false);
            setSelectedRelease(null);
            setFormData({ version: '', name: '', description: '' });
            loadData();
        } catch (err) {
            console.error('Error updating release:', err);
            setError(err instanceof Error ? err.message : 'Failed to update release');
        }
    };

    const handlePublishRelease = async (releaseId: number) => {
        try {
            const response = await authenticatedFetch(`/api/releases/${releaseId}/publish`, {
                method: 'POST'
            });

            if (!response.ok) {
                const errorData = await response.json();
                throw new Error(errorData.error || 'Failed to publish release');
            }

            loadData();
        } catch (err) {
            console.error('Error publishing release:', err);
            setError(err instanceof Error ? err.message : 'Failed to publish release');
        }
    };

    const handleArchiveRelease = async (releaseId: number) => {
        try {
            const response = await authenticatedFetch(`/api/releases/${releaseId}/archive`, {
                method: 'POST'
            });

            if (!response.ok) {
                const errorData = await response.json();
                throw new Error(errorData.error || 'Failed to archive release');
            }

            loadData();
        } catch (err) {
            console.error('Error archiving release:', err);
            setError(err instanceof Error ? err.message : 'Failed to archive release');
        }
    };

    const handleDeleteRelease = async (releaseId: number) => {
        if (!confirm('Are you sure you want to delete this release? This action cannot be undone.')) {
            return;
        }

        try {
            const response = await authenticatedFetch(`/api/releases/${releaseId}`, {
                method: 'DELETE'
            });

            if (!response.ok) {
                const errorData = await response.json();
                throw new Error(errorData.error || 'Failed to delete release');
            }

            loadData();
        } catch (err) {
            console.error('Error deleting release:', err);
            setError(err instanceof Error ? err.message : 'Failed to delete release');
        }
    };

    const openEditModal = (release: Release) => {
        setSelectedRelease(release);
        setFormData({
            version: release.version,
            name: release.name,
            description: release.description || ''
        });
        setShowEditModal(true);
    };

    const getStatusBadgeClass = (status: string) => {
        switch (status) {
            case 'DRAFT': return 'bg-warning text-dark';
            case 'ACTIVE': return 'bg-primary';
            case 'PUBLISHED': return 'bg-success';
            case 'ARCHIVED': return 'bg-secondary';
            default: return 'bg-light text-dark';
        }
    };

    const formatDate = (dateString: string) => {
        return new Date(dateString).toLocaleDateString('en-US', {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit'
        });
    };

    if (loading) {
        return (
            <div className="container-fluid mt-4">
                <div className="d-flex justify-content-center">
                    <div className="spinner-border" role="status">
                        <span className="visually-hidden">Loading...</span>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div className="container-fluid mt-4">
            {/* Header Section */}
            <div className="row mb-4">
                <div className="col-12">
                    <div className="d-flex justify-content-between align-items-center">
                        <div>
                            <h2><i className="bi bi-tags me-2"></i>Release Management</h2>
                            <p className="text-muted">Manage system versions and releases</p>
                        </div>
                        <button 
                            className="btn btn-primary"
                            onClick={() => setShowCreateModal(true)}
                        >
                            <i className="bi bi-plus-circle me-2"></i>Create New Release
                        </button>
                    </div>
                </div>
            </div>

            {/* Error Alert */}
            {error && (
                <div className="alert alert-danger alert-dismissible fade show" role="alert">
                    {error}
                    <button 
                        type="button" 
                        className="btn-close" 
                        onClick={() => setError(null)}
                        aria-label="Close"
                    ></button>
                </div>
            )}

            {/* Release Statistics Cards */}
            {stats && (
                <div className="row mb-4">
                    <div className="col-md-3">
                        <div className="card bg-primary text-white">
                            <div className="card-body">
                                <h5 className="card-title">Active Release</h5>
                                {stats.currentRelease ? (
                                    <>
                                        <h3>v{stats.currentRelease.version}</h3>
                                        <small>{stats.currentRelease.name}</small>
                                    </>
                                ) : (
                                    <h3>None</h3>
                                )}
                            </div>
                        </div>
                    </div>
                    <div className="col-md-3">
                        <div className="card bg-warning text-dark">
                            <div className="card-body">
                                <h5 className="card-title">Draft Releases</h5>
                                <h3>{stats.draftCount}</h3>
                                <small>In development</small>
                            </div>
                        </div>
                    </div>
                    <div className="col-md-3">
                        <div className="card bg-success text-white">
                            <div className="card-body">
                                <h5 className="card-title">Total Releases</h5>
                                <h3>{stats.totalReleases}</h3>
                                <small>All time</small>
                            </div>
                        </div>
                    </div>
                    <div className="col-md-3">
                        <div className="card bg-info text-white">
                            <div className="card-body">
                                <h5 className="card-title">Active Assessments</h5>
                                <h3>{stats.activeAssessments}</h3>
                                <small>In progress</small>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Releases Table */}
            <div className="row">
                <div className="col-12">
                    <div className="card">
                        <div className="card-header">
                            <h5><i className="bi bi-list me-2"></i>All Releases</h5>
                        </div>
                        <div className="card-body">
                            <div className="table-responsive">
                                <table className="table table-hover">
                                    <thead>
                                        <tr>
                                            <th>Version</th>
                                            <th>Name</th>
                                            <th>Status</th>
                                            <th>Requirements</th>
                                            <th>Created By</th>
                                            <th>Created At</th>
                                            <th>Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {releases.map(release => (
                                            <tr key={release.id}>
                                                <td>
                                                    <strong>v{release.version}</strong>
                                                </td>
                                                <td>
                                                    <div>
                                                        <strong>{release.name}</strong>
                                                        {release.description && (
                                                            <div className="text-muted small">
                                                                {release.description.length > 50
                                                                    ? release.description.substring(0, 50) + '...'
                                                                    : release.description}
                                                            </div>
                                                        )}
                                                    </div>
                                                </td>
                                                <td>
                                                    <span className={`badge ${getStatusBadgeClass(release.status)}`}>
                                                        {release.status}
                                                    </span>
                                                </td>
                                                <td>
                                                    <span className="badge bg-info">
                                                        {release.requirementCount} frozen
                                                    </span>
                                                </td>
                                                <td>{release.createdBy}</td>
                                                <td>{formatDate(release.createdAt)}</td>
                                                <td>
                                                    <div className="btn-group btn-group-sm">
                                                        <button
                                                            className="btn btn-outline-info"
                                                            onClick={() => window.location.href = `/release/${release.id}/requirements`}
                                                            title="View Requirements"
                                                        >
                                                            <i className="bi bi-list-check"></i>
                                                        </button>
                                                        {release.status === 'DRAFT' && (
                                                            <>
                                                                <button
                                                                    className="btn btn-outline-primary"
                                                                    onClick={() => openEditModal(release)}
                                                                    title="Edit"
                                                                >
                                                                    <i className="bi bi-pencil"></i>
                                                                </button>
                                                                <button
                                                                    className="btn btn-outline-danger"
                                                                    onClick={() => handleDeleteRelease(release.id)}
                                                                    title="Delete"
                                                                >
                                                                    <i className="bi bi-trash"></i>
                                                                </button>
                                                            </>
                                                        )}
                                                        <button
                                                            className="btn btn-outline-secondary"
                                                            onClick={() => window.location.href = `/export?releaseId=${release.id}`}
                                                            title="Export"
                                                        >
                                                            <i className="bi bi-download"></i>
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
                </div>
            </div>

            {/* Create Release Modal */}
            {showCreateModal && (
                <div className="modal fade show d-block" tabIndex={-1} style={{backgroundColor: 'rgba(0,0,0,0.5)'}}>
                    <div className="modal-dialog modal-lg">
                        <div className="modal-content">
                            <div className="modal-header">
                                <h5 className="modal-title">
                                    <i className="bi bi-plus-circle me-2"></i>Create New Release
                                </h5>
                                <button 
                                    type="button" 
                                    className="btn-close"
                                    onClick={() => {
                                        setShowCreateModal(false);
                                        setFormData({ version: '', name: '', description: '' });
                                        setError(null);
                                    }}
                                ></button>
                            </div>
                            <div className="modal-body">
                                {error && (
                                    <div className="alert alert-danger" role="alert">
                                        {error}
                                    </div>
                                )}
                                <div className="row">
                                    <div className="col-md-6">
                                        <div className="mb-3">
                                            <label className="form-label">Version Number *</label>
                                            <input
                                                type="text"
                                                className={`form-control ${formData.version && validateVersion(formData.version) ? 'is-invalid' : ''}`}
                                                placeholder="e.g., 1.0.0"
                                                value={formData.version}
                                                onChange={(e) => setFormData({...formData, version: e.target.value})}
                                            />
                                            <div className="form-text">Use semantic versioning (major.minor.patch)</div>
                                            {formData.version && validateVersion(formData.version) && (
                                                <div className="invalid-feedback d-block">
                                                    {validateVersion(formData.version)}
                                                </div>
                                            )}
                                        </div>
                                    </div>
                                    <div className="col-md-6">
                                        <div className="mb-3">
                                            <label className="form-label">Release Name *</label>
                                            <input
                                                type="text"
                                                className="form-control"
                                                placeholder="e.g., Q1 2024 Compliance Update"
                                                value={formData.name}
                                                onChange={(e) => setFormData({...formData, name: e.target.value})}
                                            />
                                        </div>
                                    </div>
                                </div>
                                <div className="mb-3">
                                    <label className="form-label">Description</label>
                                    <textarea
                                        className="form-control"
                                        rows={3}
                                        placeholder="Describe the changes and updates in this release..."
                                        value={formData.description}
                                        onChange={(e) => setFormData({...formData, description: e.target.value})}
                                    />
                                </div>
                            </div>
                            <div className="modal-footer">
                                <button 
                                    type="button" 
                                    className="btn btn-secondary"
                                    onClick={() => {
                                        setShowCreateModal(false);
                                        setFormData({ version: '', name: '', description: '' });
                                    }}
                                >
                                    Cancel
                                </button>
                                <button 
                                    type="button" 
                                    className="btn btn-primary"
                                    onClick={handleCreateRelease}
                                    disabled={!formData.version || !formData.name || validateVersion(formData.version) !== null}
                                >
                                    <i className="bi bi-save me-2"></i>Create Release
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Edit Release Modal */}
            {showEditModal && selectedRelease && (
                <div className="modal fade show d-block" tabIndex={-1} style={{backgroundColor: 'rgba(0,0,0,0.5)'}}>
                    <div className="modal-dialog modal-lg">
                        <div className="modal-content">
                            <div className="modal-header">
                                <h5 className="modal-title">
                                    <i className="bi bi-pencil me-2"></i>Edit Release
                                </h5>
                                <button 
                                    type="button" 
                                    className="btn-close"
                                    onClick={() => {
                                        setShowEditModal(false);
                                        setSelectedRelease(null);
                                        setFormData({ version: '', name: '', description: '' });
                                    }}
                                ></button>
                            </div>
                            <div className="modal-body">
                                <div className="row">
                                    <div className="col-md-6">
                                        <div className="mb-3">
                                            <label className="form-label">Version Number *</label>
                                            <input
                                                type="text"
                                                className="form-control"
                                                placeholder="e.g., 1.0.0"
                                                value={formData.version}
                                                onChange={(e) => setFormData({...formData, version: e.target.value})}
                                            />
                                            <div className="form-text">Use semantic versioning (major.minor.patch)</div>
                                        </div>
                                    </div>
                                    <div className="col-md-6">
                                        <div className="mb-3">
                                            <label className="form-label">Release Name *</label>
                                            <input
                                                type="text"
                                                className="form-control"
                                                placeholder="e.g., Q1 2024 Compliance Update"
                                                value={formData.name}
                                                onChange={(e) => setFormData({...formData, name: e.target.value})}
                                            />
                                        </div>
                                    </div>
                                </div>
                                <div className="mb-3">
                                    <label className="form-label">Description</label>
                                    <textarea
                                        className="form-control"
                                        rows={3}
                                        placeholder="Describe the changes and updates in this release..."
                                        value={formData.description}
                                        onChange={(e) => setFormData({...formData, description: e.target.value})}
                                    />
                                </div>
                            </div>
                            <div className="modal-footer">
                                <button 
                                    type="button" 
                                    className="btn btn-secondary"
                                    onClick={() => {
                                        setShowEditModal(false);
                                        setSelectedRelease(null);
                                        setFormData({ version: '', name: '', description: '' });
                                    }}
                                >
                                    Cancel
                                </button>
                                <button 
                                    type="button" 
                                    className="btn btn-primary"
                                    onClick={handleEditRelease}
                                    disabled={!formData.version || !formData.name}
                                >
                                    <i className="bi bi-save me-2"></i>Update Release
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default ReleaseManagement;