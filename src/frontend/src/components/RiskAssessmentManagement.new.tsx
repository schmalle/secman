import React, { useState, useEffect } from 'react';

interface Asset {
  id: number;
  name: string;
  type: string;
  ip?: string;
  owner: string;
}

interface User {
  id: number;
  username: string;
  email: string;
}

interface UseCase {
  id: number;
  name: string;
}

interface RiskAssessment {
  id?: number;
  asset?: Asset;
  assetId: number;
  endDate: string;
  status: string;
  assessor?: User;
  assessorId: number;
  requestor?: User;
  requestorId: number;
  respondent?: User;
  respondentId?: number;
  notes?: string;
  useCases?: UseCase[];
  useCaseIds?: number[];
  createdAt?: string;
  updatedAt?: string;
}

const RiskAssessmentManagement: React.FC = () => {
  const [assessments, setAssessments] = useState<RiskAssessment[]>([]);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [useCases, setUseCases] = useState<UseCase[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingAssessment, setEditingAssessment] = useState<RiskAssessment | null>(null);
  const [formData, setFormData] = useState<RiskAssessment>({
    assetId: 0,
    endDate: '',
    status: 'STARTED',
    assessorId: 0,
    requestorId: 0,
    respondentId: undefined,
    notes: '',
    useCaseIds: []
  });

  useEffect(() => {
    fetchAssessments();
    fetchAssets();
    fetchUsers();
    fetchUseCases();
  }, []);

  const fetchAssessments = async () => {
    try {
      const response = await fetch('/api/risk-assessments');
      if (!response.ok) {
        throw new Error('Failed to fetch risk assessments');
      }
      const data = await response.json();
      setAssessments(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const fetchAssets = async () => {
    try {
      const response = await fetch('/api/assets');
      if (!response.ok) {
        throw new Error('Failed to fetch assets');
      }
      const data = await response.json();
      setAssets(data);
    } catch (err) {
      console.error('Failed to fetch assets:', err);
    }
  };

  const fetchUsers = async () => {
    try {
      const response = await fetch('/api/users');
      if (!response.ok) {
        throw new Error('Failed to fetch users');
      }
      const data = await response.json();
      setUsers(data);
    } catch (err) {
      console.error('Failed to fetch users:', err);
    }
  };

  const fetchUseCases = async () => {
    try {
      const response = await fetch('/api/usecases');
      if (!response.ok) {
        throw new Error('Failed to fetch use cases');
      }
      const data = await response.json();
      setUseCases(data);
    } catch (err) {
      console.error('Failed to fetch use cases:', err);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    try {
      const url = editingAssessment ? `/api/risk-assessments/${editingAssessment.id}` : '/api/risk-assessments';
      const method = editingAssessment ? 'PUT' : 'POST';
      
      const response = await fetch(url, {
        method: method,
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(formData),
      });

      if (!response.ok) {
        throw new Error('Failed to save risk assessment');
      }

      await fetchAssessments();
      resetForm();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleEdit = (assessment: RiskAssessment) => {
    setEditingAssessment(assessment);
    setFormData({
      assetId: assessment.asset?.id || assessment.assetId,
      endDate: assessment.endDate,
      status: assessment.status,
      assessorId: assessment.assessor?.id || assessment.assessorId,
      requestorId: assessment.requestor?.id || assessment.requestorId,
      respondentId: assessment.respondent?.id,
      notes: assessment.notes || '',
      useCaseIds: assessment.useCases?.map(uc => uc.id) || []
    });
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this risk assessment?')) {
      return;
    }

    try {
      const response = await fetch(`/api/risk-assessments/${id}`, {
        method: 'DELETE',
      });

      if (!response.ok) {
        throw new Error('Failed to delete risk assessment');
      }

      await fetchAssessments();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const resetForm = () => {
    setFormData({
      assetId: 0,
      endDate: '',
      status: 'STARTED',
      assessorId: 0,
      requestorId: 0,
      respondentId: undefined,
      notes: '',
      useCaseIds: []
    });
    setEditingAssessment(null);
    setShowForm(false);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const { name, value } = e.target;
    setFormData(prev => ({ 
      ...prev, 
      [name]: value === '' ? (name.includes('Id') ? 0 : '') : 
              (name.includes('Id') ? parseInt(value) : value)
    }));
  };

  const handleUseCaseChange = (useCaseId: number) => {
    setFormData(prev => {
      const currentIds = prev.useCaseIds || [];
      const newIds = currentIds.includes(useCaseId)
        ? currentIds.filter(id => id !== useCaseId)
        : [...currentIds, useCaseId];
      return { ...prev, useCaseIds: newIds };
    });
  };

  const getStatusBadgeClass = (status: string) => {
    switch (status.toUpperCase()) {
      case 'COMPLETED': return 'bg-success';
      case 'STARTED': return 'bg-warning';
      case 'STOPPED': return 'bg-danger';
      default: return 'bg-secondary';
    }
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

  return (
    <div className="container-fluid p-4">
      <div className="row">
        <div className="col-12">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <h2>Risk Assessment Management</h2>
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
              {showForm ? 'Cancel' : 'Add New Risk Assessment'}
            </button>
          </div>
        </div>
      </div>

      {showForm && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-body">
                <h5 className="card-title">{editingAssessment ? 'Edit Risk Assessment' : 'Add New Risk Assessment'}</h5>
                <form onSubmit={handleSubmit}>
                  <div className="mb-3">
                    <label htmlFor="assetId" className="form-label">Asset *</label>
                    <select
                      className="form-control"
                      id="assetId"
                      name="assetId"
                      value={formData.assetId}
                      onChange={handleInputChange}
                      required
                    >
                      <option value={0}>Select Asset</option>
                      {assets.map((asset) => (
                        <option key={asset.id} value={asset.id}>
                          {asset.name} ({asset.type})
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="mb-3">
                    <label htmlFor="assessorId" className="form-label">Assessor *</label>
                    <select
                      className="form-control"
                      id="assessorId"
                      name="assessorId"
                      value={formData.assessorId}
                      onChange={handleInputChange}
                      required
                    >
                      <option value={0}>Select Assessor</option>
                      {users.map((user) => (
                        <option key={user.id} value={user.id}>
                          {user.username} ({user.email})
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="mb-3">
                    <label htmlFor="requestorId" className="form-label">Requestor *</label>
                    <select
                      className="form-control"
                      id="requestorId"
                      name="requestorId"
                      value={formData.requestorId}
                      onChange={handleInputChange}
                      required
                    >
                      <option value={0}>Select Requestor</option>
                      {users.map((user) => (
                        <option key={user.id} value={user.id}>
                          {user.username} ({user.email})
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="mb-3">
                    <label htmlFor="respondentId" className="form-label">Respondent (Addressed Person)</label>
                    <select
                      className="form-control"
                      id="respondentId"
                      name="respondentId"
                      value={formData.respondentId || ''}
                      onChange={handleInputChange}
                    >
                      <option value="">Select Respondent (Optional)</option>
                      {users.map((user) => (
                        <option key={user.id} value={user.id}>
                          {user.username} ({user.email})
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="mb-3">
                    <label htmlFor="endDate" className="form-label">End Date *</label>
                    <input
                      type="date"
                      className="form-control"
                      id="endDate"
                      name="endDate"
                      value={formData.endDate}
                      onChange={handleInputChange}
                      required
                    />
                  </div>

                  <div className="mb-3">
                    <label className="form-label">Scope (Use Cases)</label>
                    <div className="list-group" style={{ maxHeight: '200px', overflowY: 'auto' }}>
                      {useCases.map(useCase => (
                        <label key={useCase.id} className="list-group-item">
                          <input
                            className="form-check-input me-1"
                            type="checkbox"
                            value={useCase.id}
                            checked={(formData.useCaseIds || []).includes(useCase.id)}
                            onChange={() => handleUseCaseChange(useCase.id)}
                          />
                          {useCase.name}
                        </label>
                      ))}
                    </div>
                  </div>

                  <div className="mb-3">
                    <label htmlFor="notes" className="form-label">Notes</label>
                    <textarea
                      className="form-control"
                      id="notes"
                      name="notes"
                      value={formData.notes}
                      onChange={handleInputChange}
                      rows={3}
                      placeholder="Additional notes or comments"
                    />
                  </div>

                  <div className="d-flex gap-2">
                    <button type="submit" className="btn btn-success">
                      {editingAssessment ? 'Update' : 'Create'}
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

      <div className="row">
        <div className="col-12">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">Risk Assessments ({assessments.length})</h5>
              {assessments.length === 0 ? (
                <p className="text-muted">No risk assessments found. Create one to get started.</p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped">
                    <thead>
                      <tr>
                        <th>Asset</th>
                        <th>Assessor</th>
                        <th>Requestor</th>
                        <th>Respondent</th>
                        <th>End Date</th>
                        <th>Status</th>
                        <th>Use Cases</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {assessments.map((assessment) => (
                        <tr key={assessment.id}>
                          <td>{assessment.asset?.name}</td>
                          <td>{assessment.assessor?.username || '-'}</td>
                          <td>{assessment.requestor?.username || '-'}</td>
                          <td>{assessment.respondent?.username || '-'}</td>
                          <td>{assessment.endDate}</td>
                          <td>
                            <span className={`badge ${getStatusBadgeClass(assessment.status)}`}>
                              {assessment.status}
                            </span>
                          </td>
                          <td>
                            {assessment.useCases?.map(uc => (
                              <span key={uc.id} className="badge bg-secondary me-1">{uc.name}</span>
                            )) || '-'}
                          </td>
                          <td>
                            <button onClick={() => handleEdit(assessment)} className="btn btn-sm btn-outline-primary me-2">Edit</button>
                            <button onClick={() => assessment.id && handleDelete(assessment.id)} className="btn btn-sm btn-outline-danger">Delete</button>
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

      {error && (
        <div className="row">
          <div className="col-12">
            <div className="alert alert-danger" role="alert">
              {error}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default RiskAssessmentManagement;
