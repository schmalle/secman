import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

interface Norm {
  id: number;
  name: string;
  version?: string;
  year?: number;
  createdAt: string;
  updatedAt: string;
}

interface NormFormData {
  name: string;
  version: string;
  year: string;
}

const NormManagement: React.FC = () => {
  const [norms, setNorms] = useState<Norm[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isAddingNorm, setIsAddingNorm] = useState(false);
  const [editingNorm, setEditingNorm] = useState<Norm | null>(null);
  const [formData, setFormData] = useState<NormFormData>({
    name: '',
    version: '',
    year: ''
  });

  useEffect(() => {
    fetchNorms();
  }, []);

  const fetchNorms = async () => {
    try {
      setLoading(true);
      const response = await authenticatedGet('/api/norms');
      if (response.ok) {
        const data = await response.json();
        setNorms(data);
        setError(null);
      } else {
        setError(`Failed to fetch norms: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch norms');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    try {
      const submitData = {
        name: formData.name,
        version: formData.version || null,
        year: formData.year ? parseInt(formData.year) : null
      };

      const url = editingNorm ? `/api/norms/${editingNorm.id}` : '/api/norms';
      const method = editingNorm ? 'PUT' : 'POST';
      
      if (editingNorm) {
        await authenticatedPut(`/api/norms/${editingNorm.id}`, submitData);
      } else {
        await authenticatedPost('/api/norms', submitData);
      }

      resetForm();
      await fetchNorms();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save norm');
    }
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFormData({
      ...formData,
      [e.target.name]: e.target.value
    });
  };

  const handleEdit = (norm: Norm) => {
    setEditingNorm(norm);
    setFormData({
      name: norm.name,
      version: norm.version || '',
      year: norm.year ? norm.year.toString() : ''
    });
    setIsAddingNorm(true);
  };

  const handleDelete = async (id: number) => {
    if (!confirm('Are you sure you want to delete this norm?')) {
      return;
    }

    try {
      await authenticatedDelete(`/api/norms/${id}`);

      await fetchNorms();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete norm');
    }
  };

  const handleDeleteAll = async () => {
    if (norms.length === 0) {
      setError('No norms to delete');
      return;
    }

    const confirmMessage = `Are you sure you want to delete ALL ${norms.length} norms? This action cannot be undone and will also remove all norm relationships with requirements.`;
    
    if (!confirm(confirmMessage)) {
      return;
    }

    try {
      const result = await authenticatedDelete('/api/norms/all');
      setError(null);
      
      // Show success message briefly
      alert(`${result.message} (${result.deletedCount} norms deleted)`);
      
      await fetchNorms();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete all norms');
    }
  };

  const resetForm = () => {
    setFormData({ name: '', version: '', year: '' });
    setEditingNorm(null);
    setIsAddingNorm(false);
  };

  if (loading) return <div className="text-center py-4">Loading norms...</div>;

  return (
    <div className="container-fluid p-4">
      <div className="row">
        <div className="col-12">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <h2>Norm Management</h2>
            <div className="d-flex gap-2">
              <button 
                className="btn btn-outline-primary" 
                onClick={() => setIsAddingNorm(!isAddingNorm)}
              >
                {isAddingNorm ? 'Cancel' : 'Add Norm'}
              </button>
              <button 
                className="btn btn-outline-danger" 
                onClick={handleDeleteAll}
                disabled={norms.length === 0}
                title={norms.length === 0 ? 'No norms to delete' : `Delete all ${norms.length} norms`}
              >
                Delete All Norms
              </button>
            </div>
          </div>
        </div>
      </div>

      {isAddingNorm && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-body">
                <h5 className="card-title">
                  {editingNorm ? 'Edit Norm' : 'Add New Norm'}
                </h5>
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
                    <label htmlFor="version" className="form-label">Version</label>
                    <input
                      type="text"
                      className="form-control"
                      id="version"
                      name="version"
                      value={formData.version}
                      onChange={handleInputChange}
                    />
                  </div>

                  <div className="mb-3">
                    <label htmlFor="year" className="form-label">Year</label>
                    <input
                      type="number"
                      className="form-control"
                      id="year"
                      name="year"
                      value={formData.year}
                      onChange={handleInputChange}
                      min="1900"
                      max="2100"
                    />
                  </div>

                  <div className="d-flex gap-2">
                    <button type="submit" className="btn btn-success">
                      {editingNorm ? 'Update' : 'Save'}
                    </button>
                    <button type="button" className="btn btn-secondary" onClick={resetForm}>
                      Cancel
                    </button>
                  </div>
                </form>
              </div>
            </div>
          </div>
        </div>
      )}

      {error && (
        <div className="row mb-3">
          <div className="col-12">
            <div className="alert alert-danger" role="alert">
              {error}
            </div>
          </div>
        </div>
      )}

      <div className="row">
        <div className="col-12">
          <div className="table-responsive">
            <table className="table table-striped">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Version</th>
                  <th>Year</th>
                  <th>Created</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {norms.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="text-center text-muted">
                      No norms found. Click "Add Norm" to create one.
                    </td>
                  </tr>
                ) : (
                  norms.map((norm) => (
                    <tr key={norm.id}>
                      <td>{norm.name}</td>
                      <td>{norm.version || '-'}</td>
                      <td>{norm.year || '-'}</td>
                      <td>{new Date(norm.createdAt).toLocaleDateString()}</td>
                      <td>
                        <div className="btn-group" role="group">
                          <button
                            className="btn btn-sm btn-outline-primary"
                            onClick={() => handleEdit(norm)}
                          >
                            Edit
                          </button>
                          <button
                            className="btn btn-sm btn-outline-danger"
                            onClick={() => handleDelete(norm.id)}
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

export default NormManagement;