import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';
import PortHistory from './PortHistory';
import VulnerabilityHistory from './VulnerabilityHistory';

interface WorkgroupSummary {
  id: number;
  name: string;
}

interface Workgroup {
  id: number;
  name: string;
  description?: string;
}

interface Asset {
  id?: number;
  name: string;
  type: string;
  ip?: string;
  owner: string;
  description?: string;
  groups?: string;
  cloudAccountId?: string;
  cloudInstanceId?: string;
  osVersion?: string;
  adDomain?: string;
  createdAt?: string;
  updatedAt?: string;
  workgroups?: WorkgroupSummary[];
}

const AssetManagement: React.FC = () => {
  const [assets, setAssets] = useState<Asset[]>([]);
  const [workgroups, setWorkgroups] = useState<Workgroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingAsset, setEditingAsset] = useState<Asset | null>(null);
  const [formData, setFormData] = useState<Asset & { workgroupIds?: number[] }>({
    name: '',
    type: '',
    ip: '',
    owner: '',
    description: '',
    workgroupIds: []
  });
  const [showPortHistory, setShowPortHistory] = useState(false);
  const [selectedAssetForPorts, setSelectedAssetForPorts] = useState<Asset | null>(null);
  const [showVulnerabilities, setShowVulnerabilities] = useState(false);
  const [selectedAssetForVulns, setSelectedAssetForVulns] = useState<Asset | null>(null);

  // Filter states
  const [nameFilter, setNameFilter] = useState<string>('');
  const [ipFilter, setIpFilter] = useState<string>('');
  const [ownerFilter, setOwnerFilter] = useState<string>('');
  const [workgroupFilter, setWorkgroupFilter] = useState<string>('');

  useEffect(() => {
    fetchAssets();
    fetchWorkgroups();
  }, []);

  const fetchAssets = async () => {
    try {
      const response = await authenticatedGet('/api/assets');
      if (response.ok) {
        const data = await response.json();
        setAssets(data);
      } else {
        setError(`Failed to fetch assets: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const fetchWorkgroups = async () => {
    try {
      const response = await authenticatedGet('/api/workgroups');
      if (response.ok) {
        const data: Workgroup[] = await response.json();
        setWorkgroups(data);
      }
    } catch (err) {
      console.error('Failed to fetch workgroups:', err);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    try {
      if (editingAsset) {
        await authenticatedPut(`/api/assets/${editingAsset.id}`, formData);
      } else {
        await authenticatedPost('/api/assets', formData);
      }

      await fetchAssets();
      resetForm();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleEdit = (asset: Asset) => {
    setEditingAsset(asset);
    setFormData({
      ...asset,
      workgroupIds: asset.workgroups?.map(wg => wg.id) || []
    });
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this asset?')) {
      return;
    }

    try {
      const response = await authenticatedDelete(`/api/assets/${id}`);
      
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
        throw new Error(errorData.error || `Failed to delete asset: ${response.status}`);
      }

      await fetchAssets();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred while deleting the asset');
    }
  };

  const resetForm = () => {
    setFormData({
      name: '',
      type: '',
      ip: '',
      owner: '',
      description: '',
      workgroupIds: []
    });
    setEditingAsset(null);
    setShowForm(false);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const { name, value } = e.target;
    setFormData(prev => ({ ...prev, [name]: value }));
  };

  const handleWorkgroupChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const workgroupId = parseInt(e.target.value);
    const { checked } = e.target;
    setFormData(prev => {
      const newWorkgroupIds = checked
        ? [...(prev.workgroupIds || []), workgroupId]
        : (prev.workgroupIds || []).filter(id => id !== workgroupId);
      return { ...prev, workgroupIds: newWorkgroupIds };
    });
  };

  const handleShowPorts = (asset: Asset) => {
    setSelectedAssetForPorts(asset);
    setShowPortHistory(true);
  };

  const handleClosePortHistory = () => {
    setShowPortHistory(false);
    setSelectedAssetForPorts(null);
  };

  const handleShowVulnerabilities = (asset: Asset) => {
    setSelectedAssetForVulns(asset);
    setShowVulnerabilities(true);
  };

  const handleCloseVulnerabilities = () => {
    setShowVulnerabilities(false);
    setSelectedAssetForVulns(null);
  };

  // Filter assets based on current filter values
  const getFilteredAssets = () => {
    return assets.filter(asset => {
      const nameMatch = !nameFilter || asset.name.toLowerCase().includes(nameFilter.toLowerCase());
      const ipMatch = !ipFilter || (asset.ip && asset.ip.toLowerCase().includes(ipFilter.toLowerCase()));
      const ownerMatch = !ownerFilter || asset.owner.toLowerCase().includes(ownerFilter.toLowerCase());
      const workgroupMatch = !workgroupFilter || (
        asset.workgroups && asset.workgroups.some(wg =>
          wg.name.toLowerCase().includes(workgroupFilter.toLowerCase())
        )
      );

      return nameMatch && ipMatch && ownerMatch && workgroupMatch;
    });
  };

  if (loading) {
    return (
      <div className="d-flex justify-content-center">
        <div className="spinner-border" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-danger" role="alert">
          {error}
        </div>
      </div>
    );
  }

  return (
    <div className="container-fluid p-4">
      <div className="row">
        <div className="col-12">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <h2>Asset Management</h2>
            <button
              className="btn btn-primary"
              onClick={() => {
                if (showForm) {
                  resetForm();
                } else {
                  setShowForm(true);
                }
              }}
            >
              {showForm ? 'Cancel' : 'Add New Asset'}
            </button>
          </div>
        </div>
      </div>
      {showForm && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-body">
                <h5 className="card-title">{editingAsset ? 'Edit Asset' : 'Add New Asset'}</h5>
                <form onSubmit={handleSubmit}>
                  <div className="mb-3">
                    <label htmlFor="name" className="form-label">Name *</label>
                    <input
                      type="text"
                      className="form-control"
                      id="name"
                      name="name"
                      value={formData.name}
                      onChange={handleInputChange}
                      required
                    />
                  </div>
                  <div className="mb-3">
                    <label htmlFor="type" className="form-label">Type *</label>
                    <select
                      className="form-control"
                      id="type"
                      name="type"
                      value={formData.type}
                      onChange={handleInputChange}
                      required
                    >
                      <option value="">Select Type</option>
                      <option value="Server">Server</option>
                      <option value="Workstation">Workstation</option>
                      <option value="Network Device">Network Device</option>
                      <option value="Mobile Device">Mobile Device</option>
                      <option value="IoT Device">IoT Device</option>
                      <option value="Database">Database</option>
                      <option value="Application">Application</option>
                      <option value="Other">Other</option>
                    </select>
                  </div>
                  <div className="mb-3">
                    <label htmlFor="ip" className="form-label">IP Address</label>
                    <input
                      type="text"
                      className="form-control"
                      id="ip"
                      name="ip"
                      value={formData.ip}
                      onChange={handleInputChange}
                      placeholder="e.g., 192.168.1.100"
                    />
                  </div>
                  <div className="mb-3">
                    <label htmlFor="owner" className="form-label">Owner *</label>
                    <input
                      type="text"
                      className="form-control"
                      id="owner"
                      name="owner"
                      value={formData.owner}
                      onChange={handleInputChange}
                      placeholder="Person or team responsible"
                      required
                    />
                  </div>
                  <div className="mb-3">
                    <label htmlFor="description" className="form-label">Description</label>
                    <textarea
                      className="form-control"
                      id="description"
                      name="description"
                      value={formData.description}
                      onChange={handleInputChange}
                      placeholder="Description of the asset"
                      rows={3}
                    />
                  </div>
                  <div className="mb-3">
                    <label className="form-label">Workgroups</label>
                    <div>
                      {workgroups.length > 0 ? (
                        workgroups.map(workgroup => (
                          <div className="form-check" key={workgroup.id}>
                            <input
                              className="form-check-input"
                              type="checkbox"
                              id={`workgroup-${workgroup.id}`}
                              value={workgroup.id}
                              checked={(formData.workgroupIds || []).includes(workgroup.id)}
                              onChange={handleWorkgroupChange}
                            />
                            <label className="form-check-label" htmlFor={`workgroup-${workgroup.id}`}>
                              {workgroup.name}
                            </label>
                          </div>
                        ))
                      ) : (
                        <small className="text-muted">No workgroups available</small>
                      )}
                    </div>
                  </div>
                  <div className="d-flex justify-content-end">
                    <button type="submit" className="btn btn-success me-2">
                      {editingAsset ? 'Update' : 'Save'}
                    </button>
                    <button type="button" onClick={resetForm} className="btn btn-secondary">
                      Cancel
                    </button>
                  </div>
                </form>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Filters */}
      <div className="row mb-4">
        <div className="col-12">
          <div className="card">
            <div className="card-body">
              <h6 className="card-title">Filters</h6>
              <div className="row">
                <div className="col-md-3">
                  <label htmlFor="nameFilter" className="form-label">Name</label>
                  <input
                    type="text"
                    id="nameFilter"
                    className="form-control"
                    placeholder="Filter by name..."
                    value={nameFilter}
                    onChange={(e) => setNameFilter(e.target.value)}
                  />
                </div>
                <div className="col-md-3">
                  <label htmlFor="ipFilter" className="form-label">IP Address</label>
                  <input
                    type="text"
                    id="ipFilter"
                    className="form-control"
                    placeholder="Filter by IP..."
                    value={ipFilter}
                    onChange={(e) => setIpFilter(e.target.value)}
                  />
                </div>
                <div className="col-md-3">
                  <label htmlFor="ownerFilter" className="form-label">Owner</label>
                  <input
                    type="text"
                    id="ownerFilter"
                    className="form-control"
                    placeholder="Filter by owner..."
                    value={ownerFilter}
                    onChange={(e) => setOwnerFilter(e.target.value)}
                  />
                </div>
                <div className="col-md-3">
                  <label htmlFor="workgroupFilter" className="form-label">Workgroups</label>
                  <input
                    type="text"
                    id="workgroupFilter"
                    className="form-control"
                    placeholder="Filter by workgroup..."
                    value={workgroupFilter}
                    onChange={(e) => setWorkgroupFilter(e.target.value)}
                  />
                </div>
              </div>
              {(nameFilter || ipFilter || ownerFilter || workgroupFilter) && (
                <div className="mt-2">
                  <button
                    className="btn btn-sm btn-outline-secondary"
                    onClick={() => {
                      setNameFilter('');
                      setIpFilter('');
                      setOwnerFilter('');
                      setWorkgroupFilter('');
                    }}
                  >
                    Clear All Filters
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="row">
        <div className="col-12">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">Assets ({getFilteredAssets().length})</h5>
              {getFilteredAssets().length === 0 ? (
                <p className="text-muted">
                  {assets.length === 0
                    ? 'No assets found. Click "Add New Asset" to create one.'
                    : 'No assets match the current filters.'}
                </p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped table-hover">
                    <thead>
                      <tr>
                        <th>Name</th>
                        <th>Type</th>
                        <th>IP Address</th>
                        <th>Owner</th>
                        <th>Description</th>
                        <th>Workgroups</th>
                        <th>Created</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {getFilteredAssets().map((asset) => (
                        <tr key={asset.id}>
                          <td>{asset.name}</td>
                          <td>
                            <span className="badge bg-info">{asset.type}</span>
                          </td>
                          <td>{asset.ip || '-'}</td>
                          <td>{asset.owner}</td>
                          <td>{asset.description || '-'}</td>
                          <td>
                            {asset.workgroups && asset.workgroups.length > 0 ? (
                              <div>
                                {asset.workgroups.map(wg => (
                                  <span key={wg.id} className="badge bg-info me-1">{wg.name}</span>
                                ))}
                              </div>
                            ) : (
                              <span className="text-muted">None</span>
                            )}
                          </td>
                          <td>
                            {asset.createdAt ? new Date(asset.createdAt).toLocaleDateString() : '-'}
                          </td>
                          <td>
                            <div className="btn-group" role="group">
                              <button onClick={() => handleEdit(asset)} className="btn btn-sm btn-outline-primary">
                                <i className="bi bi-pencil"></i> Edit
                              </button>
                              {asset.ip && (
                                <button
                                  onClick={() => handleShowPorts(asset)}
                                  className="btn btn-sm btn-outline-info"
                                  title="Show port history"
                                >
                                  <i className="bi bi-diagram-3"></i> Ports
                                </button>
                              )}
                              <button
                                onClick={() => handleShowVulnerabilities(asset)}
                                className="btn btn-sm btn-outline-danger"
                                title="Show vulnerabilities"
                              >
                                <i className="bi bi-shield-exclamation"></i> Vulnerabilities
                              </button>
                              <button
                                onClick={() => {
                                  if (asset.id !== undefined && asset.id !== null) {
                                    handleDelete(asset.id);
                                  }
                                }}
                                className="btn btn-sm btn-outline-danger"
                                disabled={asset.id === undefined || asset.id === null}
                              >
                                <i className="bi bi-trash"></i> Delete
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Back to Home button */}
      <div className="row mt-4">
        <div className="col-12">
          <a href="/" className="btn btn-secondary">Back to Home</a>
        </div>
      </div>

      {/* Port History Modal */}
      {showPortHistory && selectedAssetForPorts && selectedAssetForPorts.id && (
        <PortHistory
          assetId={selectedAssetForPorts.id}
          assetName={selectedAssetForPorts.name}
          onClose={handleClosePortHistory}
        />
      )}

      {/* Vulnerability History Modal */}
      {showVulnerabilities && selectedAssetForVulns && selectedAssetForVulns.id && (
        <VulnerabilityHistory
          assetId={selectedAssetForVulns.id}
          assetName={selectedAssetForVulns.name}
          onClose={handleCloseVulnerabilities}
        />
      )}

    </div>
  );
};

export default AssetManagement;
