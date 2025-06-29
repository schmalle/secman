import React, { useState, useEffect } from 'react';

interface UseCase {
  id: number;
  name: string;
  createdAt: string;
  updatedAt: string;
}

interface UseCaseFormData {
  name: string;
}

const UseCaseManagement: React.FC = () => {
  const [useCases, setUseCases] = useState<UseCase[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null); // Keep error state for console logging or future use
  const [isAddingUseCase, setIsAddingUseCase] = useState(false); // Replaces showModal
  const [editingUseCase, setEditingUseCase] = useState<UseCase | null>(null);
  const [formData, setFormData] = useState<UseCaseFormData>({
    name: '',
  });

  useEffect(() => {
    fetchUseCases();
  }, []);

  const fetchUseCases = async () => {
    try {
      setLoading(true);
      const response = await fetch('/api/usecases', {
        credentials: 'include'
      });
      
      if (!response.ok) {
        throw new Error('Failed to fetch use cases');
      }
      
      const data = await response.json();
      setUseCases(data);
      setError(null);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to fetch use cases';
      setError(errorMessage);
      console.error('Error fetching use cases:', errorMessage);
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    try {
      const submitData = {
        name: formData.name,
      };

      const url = editingUseCase ? `/api/usecases/${editingUseCase.id}` : '/api/usecases';
      const method = editingUseCase ? 'PUT' : 'POST';
      
      const response = await fetch(url, {
        method,
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
        body: JSON.stringify(submitData)
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Failed to save use case');
      }

      resetForm(); // Includes setIsAddingUseCase(false)
      await fetchUseCases();
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to save use case';
      setError(errorMessage);
      console.error('Error saving use case:', errorMessage);
    }
  };

  const handleEdit = (useCase: UseCase) => {
    setEditingUseCase(useCase);
    setFormData({
      name: useCase.name,
    });
    setIsAddingUseCase(true);
  };

  const handleDelete = async (id: number) => {
    if (!confirm('Are you sure you want to delete this use case?')) {
      return;
    }

    try {
      const response = await fetch(`/api/usecases/${id}`, {
        method: 'DELETE',
        credentials: 'include'
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Failed to delete use case');
      }

      await fetchUseCases();
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to delete use case';
      setError(errorMessage);
      console.error('Error deleting use case:', errorMessage);
    }
  };

  const resetForm = () => {
    setEditingUseCase(null);
    setFormData({ name: '' });
    setIsAddingUseCase(false);
    setError(null);
  };

  if (loading) return <div className="text-center py-4">Loading use cases...</div>;
  // Error display is removed from UI, errors are logged to console.

  return (
    <div className="container-fluid p-4">
      <div className="row">
        <div className="col-12">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <h2>Use Case Management</h2>
            <button
              className="btn btn-primary"
              onClick={() => {
                if (isAddingUseCase) {
                  resetForm();
                } else {
                  setIsAddingUseCase(true);
                }
              }}
            >
              {isAddingUseCase ? 'Cancel' : 'Add New Use Case'}
            </button>
          </div>
        </div>
      </div>

      {isAddingUseCase && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-body">
                <h5 className="card-title">
                  {editingUseCase ? 'Edit Use Case' : 'Add New Use Case'}
                </h5>
                <form onSubmit={handleSubmit}>
                  <div className="mb-3">
                    <label htmlFor="name" className="form-label">
                      Name
                    </label>
                    <input
                      type="text"
                      name="name"
                      id="name"
                      className="form-control"
                      value={formData.name}
                      onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                      required
                    />
                  </div>
                  <div className="d-flex justify-content-end">
                    <button
                      type="submit"
                      className="btn btn-success me-2"
                    >
                      {editingUseCase ? 'Update' : 'Save'}
                    </button>
                    <button
                      type="button"
                      onClick={resetForm}
                      className="btn btn-secondary"
                    >
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
              <h5 className="card-title">Existing Use Cases</h5>
              <div className="table-responsive">
                <table className="table table-striped table-hover">
                  <thead>
                    <tr>
                      <th>ID</th>
                      <th>Name</th>
                      <th>Created At</th>
                      <th>Updated At</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {useCases.map((useCase) => (
                      <tr key={useCase.id}>
                        <td>{useCase.id}</td>
                        <td>{useCase.name}</td>
                        <td>{new Date(useCase.createdAt).toLocaleDateString()}</td>
                        <td>{new Date(useCase.updatedAt).toLocaleDateString()}</td>
                        <td>
                          <button 
                            onClick={() => handleEdit(useCase)} 
                            className="btn btn-sm btn-warning me-2"
                          >
                            Edit
                          </button>
                          <button 
                            onClick={() => handleDelete(useCase.id)} 
                            className="btn btn-sm btn-danger"
                          >
                            Delete
                          </button>
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
    </div>
  );
};

export default UseCaseManagement;
