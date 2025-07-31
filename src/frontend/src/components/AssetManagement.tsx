import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

interface Asset {
  id?: number;
  name: string;
  type: string;
  ip?: string;
  owner: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

const AssetManagement: React.FC = () => {
  const [assets, setAssets] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingAsset, setEditingAsset] = useState<Asset | null>(null);
  const [formData, setFormData] = useState<Asset>({
    name: '',
    type: '',
    ip: '',
    owner: '',
    description: ''
  });

  useEffect(() => {
    fetchAssets();
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
    setFormData({ ...asset });
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this asset?')) {
      return;
    }

    try {
      await authenticatedDelete(`/api/assets/${id}`);

      await fetchAssets();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const resetForm = () => {
    setFormData({
      name: '',
      type: '',
      ip: '',
      owner: '',
      description: ''
    });
    setEditingAsset(null);
    setShowForm(false);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const { name, value } = e.target;
    setFormData(prev => ({ ...prev, [name]: value }));
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
              className="btn btn-outline-primary"
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
      <div className="row">
        <div className="col-12">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">Assets ({assets.length})</h5>
              {assets.length === 0 ? (
                <p className="text-muted">No assets found. Click "Add New Asset" to create one.</p>
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
                        <th>Created</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {assets.map((asset) => (
                        <tr key={asset.id}>
                          <td>{asset.name}</td>
                          <td>
                            <span className="badge bg-info">{asset.type}</span>
                          </td>
                          <td>{asset.ip || '-'}</td>
                          <td>{asset.owner}</td>
                          <td>{asset.description || '-'}</td>
                          <td>
                            {asset.createdAt ? new Date(asset.createdAt).toLocaleDateString() : '-'}
                          </td>
                          <td>
                            <button onClick={() => handleEdit(asset)} className="btn btn-sm btn-outline-primary me-2">Edit</button>
                            <button onClick={() => asset.id && handleDelete(asset.id)} className="btn btn-sm btn-outline-danger">Delete</button>
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

    </div>
  );
};

export default AssetManagement;
