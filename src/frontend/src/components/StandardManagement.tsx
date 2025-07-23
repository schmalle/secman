import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

interface UseCase {
  id: number;
  name: string;
  createdAt?: string;
  updatedAt?: string;
}

interface Standard {
  id?: number;
  name: string;
  description?: string;
  useCases?: UseCase[];
  createdAt?: string;
  updatedAt?: string;
}

const StandardManagement: React.FC = () => {
  const [standards, setStandards] = useState<Standard[]>([]);
  const [useCases, setUseCases] = useState<UseCase[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingStandard, setEditingStandard] = useState<Standard | null>(null);
  const [formData, setFormData] = useState<Standard>({
    name: '',
    description: '',
    useCases: []
  });
  const [selectedUseCaseIds, setSelectedUseCaseIds] = useState<number[]>([]);

  useEffect(() => {
    fetchStandards();
    fetchUseCases();
  }, []);

  const fetchStandards = async () => {
    try {
      const response = await authenticatedGet('/api/standards');
      if (response.ok) {
        const data = await response.json();
        setStandards(data);
      } else {
        setError(`Failed to fetch standards: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const fetchUseCases = async () => {
    try {
      const response = await authenticatedGet('/api/usecases');
      if (response.ok) {
        const data = await response.json();
        setUseCases(data);
      } else {
        console.error('Failed to fetch use cases:', response.status);
      }
    } catch (err) {
      console.error('Failed to fetch use cases:', err);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    try {
      const url = editingStandard ? `/api/standards/${editingStandard.id}` : '/api/standards';
      const method = editingStandard ? 'PUT' : 'POST';
      
      const submitData = {
        name: formData.name,
        description: formData.description,
        useCaseIds: selectedUseCaseIds
      };

      if (editingStandard) {
        await authenticatedPut(`/api/standards/${editingStandard.id}`, submitData);
      } else {
        await authenticatedPost('/api/standards', submitData);
      }

      await fetchStandards();
      resetForm();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleEdit = (standard: Standard) => {
    setEditingStandard(standard);
    setFormData({
      name: standard.name,
      description: standard.description || '',
      useCases: standard.useCases || []
    });
    setSelectedUseCaseIds(standard.useCases?.map(uc => uc.id) || []);
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this standard?')) {
      return;
    }

    try {
      await authenticatedDelete(`/api/standards/${id}`);

      await fetchStandards();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const resetForm = () => {
    setFormData({
      name: '',
      description: '',
      useCases: []
    });
    setSelectedUseCaseIds([]);
    setEditingStandard(null);
    setShowForm(false);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    const { name, value } = e.target;
    setFormData(prev => ({ ...prev, [name]: value }));
  };

  const handleUseCaseSelection = (useCaseId: number) => {
    setSelectedUseCaseIds(prev => {
      if (prev.includes(useCaseId)) {
        return prev.filter(id => id !== useCaseId);
      } else {
        return [...prev, useCaseId];
      }
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
            <h2>Standard Management</h2>
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
              {showForm ? 'Cancel' : 'Add New Standard'}
            </button>
          </div>
        </div>
      </div>
      {showForm && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-body">
                <h5 className="card-title">{editingStandard ? 'Edit Standard' : 'Add New Standard'}</h5>
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
                    <label htmlFor="description" className="form-label">Description</label>
                    <textarea
                      className="form-control"
                      id="description"
                      name="description"
                      value={formData.description}
                      onChange={handleInputChange}
                      rows={3}
                      placeholder="Description of the standard"
                    />
                  </div>
                  <div className="mb-3">
                    <label className="form-label">Associated Use Cases</label>
                    <div className="border rounded p-3" style={{ maxHeight: '200px', overflowY: 'auto' }}>
                      {useCases.length === 0 ? (
                        <p className="text-muted mb-0">No use cases available</p>
                      ) : (
                        useCases.map((useCase) => (
                          <div key={useCase.id} className="form-check">
                            <input
                              className="form-check-input"
                              type="checkbox"
                              id={`usecase-${useCase.id}`}
                              checked={selectedUseCaseIds.includes(useCase.id)}
                              onChange={() => handleUseCaseSelection(useCase.id)}
                            />
                            <label className="form-check-label" htmlFor={`usecase-${useCase.id}`}>
                              {useCase.name}
                            </label>
                          </div>
                        ))
                      )}
                    </div>
                    <small className="text-muted">Select one or more use cases that apply to this standard</small>
                  </div>
                  <div className="d-flex justify-content-end">
                    <button type="submit" className="btn btn-success me-2">
                      {editingStandard ? 'Update' : 'Save'}
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
              <h5 className="card-title">Standards ({standards.length})</h5>
              {standards.length === 0 ? (
                <p className="text-muted">No standards found. Click "Add New Standard" to create one.</p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped table-hover">
                    <thead>
                      <tr>
                        <th>Name</th>
                        <th>Description</th>
                        <th>Use Cases</th>
                        <th>Created</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {standards.map((standard) => (
                        <tr key={standard.id}>
                          <td>
                            <strong>{standard.name}</strong>
                          </td>
                          <td>
                            {standard.description ? (
                              <span className="text-truncate" style={{ maxWidth: '200px', display: 'inline-block' }}>
                                {standard.description}
                              </span>
                            ) : (
                              <span className="text-muted">-</span>
                            )}
                          </td>
                          <td>
                            {standard.useCases && standard.useCases.length > 0 ? (
                              <div>
                                {standard.useCases.slice(0, 2).map((useCase, index) => (
                                  <span key={useCase.id} className="badge bg-info me-1">
                                    {useCase.name}
                                  </span>
                                ))}
                                {standard.useCases.length > 2 && (
                                  <span className="badge bg-secondary">
                                    +{standard.useCases.length - 2} more
                                  </span>
                                )}
                              </div>
                            ) : (
                              <span className="text-muted">No use cases</span>
                            )}
                          </td>
                          <td>
                            {standard.createdAt ? new Date(standard.createdAt).toLocaleDateString() : '-'}
                          </td>
                          <td>
                            <button onClick={() => handleEdit(standard)} className="btn btn-sm btn-outline-primary me-2">Edit</button>
                            <button onClick={() => standard.id && handleDelete(standard.id)} className="btn btn-sm btn-outline-danger">Delete</button>
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

export default StandardManagement;