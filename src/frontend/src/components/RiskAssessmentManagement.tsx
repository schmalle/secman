import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';
import AssessmentPerformance from './AssessmentPerformance';

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

interface Demand {
  id: number;
  title: string;
  description?: string;
  demandType: 'CHANGE' | 'CREATE_NEW';
  existingAsset?: Asset;
  newAssetName?: string;
  newAssetType?: string;
  newAssetOwner?: string;
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'IN_PROGRESS' | 'COMPLETED';
  requestor?: User;
  requestedDate: string;
}

interface RiskAssessment {
  id?: number;
  // New unified approach
  assessmentBasisType: 'DEMAND' | 'ASSET';
  assessmentBasisId: number;
  // Legacy fields for backward compatibility
  demand?: Demand;
  demandId?: number;
  asset?: Asset;
  assetId?: number;
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
  const [approvedDemands, setApprovedDemands] = useState<Demand[]>([]);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [useCases, setUseCases] = useState<UseCase[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingAssessment, setEditingAssessment] = useState<RiskAssessment | null>(null);
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [basisTypeFilter, setBasisTypeFilter] = useState<string>('ALL');
  const [showAssessmentModal, setShowAssessmentModal] = useState(false);
  const [assessmentModalMode, setAssessmentModalMode] = useState<'perform' | 'review'>('perform');
  const [selectedAssessmentId, setSelectedAssessmentId] = useState<number | null>(null);
  const [formData, setFormData] = useState<RiskAssessment>({
    assessmentBasisType: 'DEMAND',
    assessmentBasisId: 0,
    endDate: '',
    status: 'STARTED', // This will be set automatically on the backend
    assessorId: 0,
    requestorId: 0, // This will be set automatically to current user
    respondentId: undefined,
    notes: '',
    useCaseIds: []
  });

  // Helper function to get date 14 days from now
  const getDefaultEndDate = () => {
    const date = new Date();
    date.setDate(date.getDate() + 14);
    return date.toISOString().split('T')[0]; // Format as YYYY-MM-DD
  };

  // Check if user is authenticated
  const [currentUser, setCurrentUser] = useState<User | null>(null);

  useEffect(() => {
    // Check authentication
    const checkAuth = () => {
      if (typeof window !== 'undefined' && (window as any).currentUser) {
        setCurrentUser((window as any).currentUser);
      }
    };
    
    checkAuth();
    
    // Listen for userLoaded event
    const handleUserLoaded = () => {
      if (typeof window !== 'undefined' && (window as any).currentUser) {
        setCurrentUser((window as any).currentUser);
      }
    };
    
    if (typeof window !== 'undefined') {
      window.addEventListener('userLoaded', handleUserLoaded);
      return () => window.removeEventListener('userLoaded', handleUserLoaded);
    }
  }, []);

  useEffect(() => {
    if (currentUser) {
      fetchAssessments();
      fetchApprovedDemands();
      fetchAssets();
      fetchUsers();
      fetchUseCases();
    }
  }, [currentUser]);

  const fetchAssessments = async () => {
    try {
      const response = await authenticatedGet('/api/risk-assessments');
      if (response.ok) {
        const data = await response.json();
        setAssessments(data);
      } else {
        setError(`Failed to fetch assessments: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const fetchApprovedDemands = async () => {
    try {
      const response = await authenticatedGet('/api/demands/approved/available');
      if (response.ok) {
        const data = await response.json();
        setApprovedDemands(data);
      } else {
        console.error('Failed to fetch approved demands:', response.status);
      }
    } catch (err) {
      console.error('Failed to fetch approved demands:', err);
    }
  };

  const fetchUsers = async () => {
    try {
      const response = await authenticatedGet('/api/users');
      if (response.ok) {
        const data = await response.json();
        setUsers(data);
      } else {
        console.error('Failed to fetch users:', response.status);
      }
    } catch (err) {
      console.error('Failed to fetch users:', err);
    }
  };

  const fetchAssets = async () => {
    try {
      const response = await authenticatedGet('/api/assets');
      if (response.ok) {
        const data = await response.json();
        setAssets(data);
      } else {
        console.error('Failed to fetch assets:', response.status);
      }
    } catch (err) {
      console.error('Failed to fetch assets:', err);
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
    
    if (!currentUser) {
      setError('You must be logged in to create a risk assessment');
      return;
    }
    
    try {
      // Prepare data for unified API
      const dataToSubmit: any = {
        assessorId: formData.assessorId,
        endDate: formData.endDate,
        respondentId: formData.respondentId,
        notes: formData.notes,
        useCaseIds: formData.useCaseIds
      };
      
      // Set basis-specific fields
      if (formData.assessmentBasisType === 'DEMAND') {
        dataToSubmit.demandId = formData.assessmentBasisId;
      } else {
        dataToSubmit.assetId = formData.assessmentBasisId;
      }
      
      // Set requestor to current user for new assessments
      if (!editingAssessment) {
        dataToSubmit.requestorId = currentUser.id;
      }
      
      let response;
      if (editingAssessment) {
        response = await authenticatedPut(`/api/risk-assessments/${editingAssessment.id}`, dataToSubmit);
      } else {
        response = await authenticatedPost('/api/risk-assessments', dataToSubmit);
      }
      if (!response.ok) {
        throw new Error(`Failed to save assessment: ${response.status}`);
      }

      await fetchAssessments();
      await fetchApprovedDemands(); // Refresh available demands
      resetForm();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleEdit = (assessment: RiskAssessment) => {
    setEditingAssessment(assessment);
    setFormData({
      assessmentBasisType: assessment.assessmentBasisType || (assessment.demand ? 'DEMAND' : 'ASSET'),
      assessmentBasisId: assessment.assessmentBasisId || assessment.demand?.id || assessment.asset?.id || 0,
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
      const response = await authenticatedDelete(`/api/risk-assessments/${id}`);
      if (!response.ok) {
        throw new Error(`Failed to delete assessment: ${response.status}`);
      }
      await fetchAssessments();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleSendNotification = async (assessment: RiskAssessment) => {
    if (!assessment.respondent?.email) {
      setError('No respondent email available for this assessment');
      return;
    }

    if (!window.confirm(`Send assessment notification to ${assessment.respondent.email}?`)) {
      return;
    }

    try {
      const response = await authenticatedPost(`/api/risk-assessments/${assessment.id}/notify`, {
        respondentEmail: assessment.respondent.email
      });
      if (!response.ok) {
        throw new Error(`Failed to send notification: ${response.status}`);
      }

      setError(null);
      // Show success message temporarily
      const originalError = error;
      setError('Notification sent successfully!');
      setTimeout(() => setError(originalError), 3000);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handlePerformAssessment = (assessment: RiskAssessment) => {
    if (!assessment.id) {
      setError('Invalid assessment ID');
      return;
    }
    setSelectedAssessmentId(assessment.id);
    setAssessmentModalMode('perform');
    setShowAssessmentModal(true);
  };

  const handleCheckAnswers = (assessment: RiskAssessment) => {
    if (!assessment.id) {
      setError('Invalid assessment ID');
      return;
    }
    setSelectedAssessmentId(assessment.id);
    setAssessmentModalMode('review');
    setShowAssessmentModal(true);
  };

  const handleAssessmentModalClose = () => {
    setShowAssessmentModal(false);
    setSelectedAssessmentId(null);
  };

  const handleAssessmentComplete = () => {
    setShowAssessmentModal(false);
    setSelectedAssessmentId(null);
    fetchAssessments(); // Refresh the assessments list
  };

  const resetForm = () => {
    setFormData({
      assessmentBasisType: 'DEMAND',
      assessmentBasisId: 0,
      endDate: getDefaultEndDate(), // Pre-fill with 14 days from now
      status: 'STARTED', // This will be set automatically on the backend
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
    setFormData(prev => {
      const newData = { 
        ...prev, 
        [name]: value === '' ? (name.includes('Id') ? 0 : '') : 
                (name.includes('Id') ? parseInt(value) : value)
      };
      
      // Reset basis ID when basis type changes
      if (name === 'assessmentBasisType') {
        newData.assessmentBasisId = 0;
      }
      
      return newData;
    });
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

  // Filter assessments based on status and basis type
  const filteredAssessments = assessments.filter(assessment => {
    if (statusFilter !== 'ALL' && assessment.status.toUpperCase() !== statusFilter.toUpperCase()) {
      return false;
    }
    if (basisTypeFilter !== 'ALL') {
      const basisType = assessment.assessmentBasisType || (assessment.demand ? 'DEMAND' : 'ASSET');
      if (basisType !== basisTypeFilter) {
        return false;
      }
    }
    return true;
  });

  if (!currentUser) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-warning" role="alert">
          <h4 className="alert-heading">Authentication Required</h4>
          <p>You must be logged in to access Risk Assessment Management.</p>
          <hr />
          <p className="mb-0">Please <a href="/login">log in</a> to continue.</p>
        </div>
      </div>
    );
  }

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
                  // Pre-fill end date when opening form for new assessment
                  setFormData(prev => ({
                    ...prev,
                    endDate: getDefaultEndDate(),
                    assessmentBasisType: 'DEMAND',
                    assessmentBasisId: 0
                  }));
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
                    <label htmlFor="assessmentBasisType" className="form-label">Assessment Basis *</label>
                    <select
                      className="form-control"
                      id="assessmentBasisType"
                      name="assessmentBasisType"
                      value={formData.assessmentBasisType}
                      onChange={handleInputChange}
                      required
                    >
                      <option value="DEMAND">Demand (Change Request)</option>
                      <option value="ASSET">Asset (Direct Assessment)</option>
                    </select>
                    <div className="form-text">
                      {formData.assessmentBasisType === 'DEMAND' 
                        ? 'Assess risks related to a change request or new asset creation'
                        : 'Assess risks directly on an existing asset'
                      }
                    </div>
                  </div>

                  {formData.assessmentBasisType === 'DEMAND' ? (
                    <div className="mb-3">
                      <label htmlFor="assessmentBasisId" className="form-label">Approved Demand *</label>
                      <select
                        className="form-control"
                        id="assessmentBasisId"
                        name="assessmentBasisId"
                        value={formData.assessmentBasisId}
                        onChange={handleInputChange}
                        required
                      >
                        <option value={0}>Select Approved Demand</option>
                        {approvedDemands.map((demand) => (
                          <option key={demand.id} value={demand.id}>
                            {demand.title} - {demand.demandType === 'CHANGE' 
                              ? `Change: ${demand.existingAsset?.name}` 
                              : `Create: ${demand.newAssetName}`} 
                            ({demand.priority})
                          </option>
                        ))}
                      </select>
                      {approvedDemands.length === 0 && (
                        <div className="form-text text-warning">
                          No approved demands available. <a href="/demands">Manage demands</a> to create and approve them first.
                        </div>
                      )}
                    </div>
                  ) : (
                    <div className="mb-3">
                      <label htmlFor="assessmentBasisId" className="form-label">Asset *</label>
                      <select
                        className="form-control"
                        id="assessmentBasisId"
                        name="assessmentBasisId"
                        value={formData.assessmentBasisId}
                        onChange={handleInputChange}
                        required
                      >
                        <option value={0}>Select Asset</option>
                        {assets.map((asset) => (
                          <option key={asset.id} value={asset.id}>
                            {asset.name} ({asset.type}) - {asset.owner}
                          </option>
                        ))}
                      </select>
                      {assets.length === 0 && (
                        <div className="form-text text-warning">
                          No assets available. <a href="/assets">Manage assets</a> to create them first.
                        </div>
                      )}
                    </div>
                  )}

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
                  <div className="d-flex justify-content-end">
                    <button type="submit" className="btn btn-success me-2">
                      {editingAssessment ? 'Update' : 'Save'}
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
              <div className="d-flex justify-content-between align-items-center mb-3">
                <h5 className="card-title mb-0">Risk Assessments ({filteredAssessments.length})</h5>
                <div className="d-flex align-items-center gap-3">
                  <div className="d-flex align-items-center">
                    <label htmlFor="basisTypeFilter" className="form-label me-2 mb-0">Basis Type:</label>
                    <select
                      id="basisTypeFilter"
                      className="form-select"
                      style={{ width: 'auto' }}
                      value={basisTypeFilter}
                      onChange={(e) => setBasisTypeFilter(e.target.value)}
                    >
                      <option value="ALL">All Types</option>
                      <option value="DEMAND">Demand</option>
                      <option value="ASSET">Asset</option>
                    </select>
                  </div>
                  <div className="d-flex align-items-center">
                    <label htmlFor="statusFilter" className="form-label me-2 mb-0">Status:</label>
                    <select
                      id="statusFilter"
                      className="form-select"
                      style={{ width: 'auto' }}
                      value={statusFilter}
                      onChange={(e) => setStatusFilter(e.target.value)}
                    >
                      <option value="ALL">All Statuses</option>
                      <option value="STARTED">Started</option>
                      <option value="COMPLETED">Completed</option>
                      <option value="STOPPED">Stopped</option>
                    </select>
                  </div>
                </div>
              </div>
              {filteredAssessments.length === 0 ? (
                <p className="text-muted">
                  {assessments.length === 0 
                    ? "No risk assessments found. Click \"Add New Risk Assessment\" to create one."
                    : `No risk assessments found with the selected filters (Status: ${statusFilter}, Type: ${basisTypeFilter}).`
                  }
                </p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped table-hover">
                    <thead>
                      <tr>
                        <th>Basis</th>
                        <th>Target</th>
                        <th>Type</th>
                        <th>End Date</th>
                        <th>Status</th>
                        <th>Assessor</th>
                        <th>Requestor</th>
                        <th>Respondent</th>
                        <th>Scope</th>
                        <th>Created</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredAssessments.map((assessment) => {
                        const basisType = assessment.assessmentBasisType || (assessment.demand ? 'DEMAND' : 'ASSET');
                        const getBasisInfo = () => {
                          if (basisType === 'DEMAND' && assessment.demand) {
                            return {
                              title: assessment.demand.title,
                              subtitle: assessment.demand.demandType === 'CHANGE' && assessment.demand.existingAsset
                                ? `Asset: ${assessment.demand.existingAsset.name}`
                                : assessment.demand.demandType === 'CREATE_NEW'
                                ? `New: ${assessment.demand.newAssetName}`
                                : 'Unknown demand type',
                              typeLabel: assessment.demand.demandType === 'CHANGE' ? 'Change' : 'Create New',
                              typeClass: assessment.demand.demandType === 'CHANGE' ? 'bg-info' : 'bg-secondary'
                            };
                          } else if (basisType === 'ASSET' && assessment.asset) {
                            return {
                              title: assessment.asset.name,
                              subtitle: `${assessment.asset.type} - ${assessment.asset.owner}`,
                              typeLabel: 'Direct Assessment',
                              typeClass: 'bg-success'
                            };
                          } else {
                            // Legacy support
                            return {
                              title: assessment.demand?.title || assessment.asset?.name || 'Unknown',
                              subtitle: 'Legacy Assessment',
                              typeLabel: 'Legacy',
                              typeClass: 'bg-warning'
                            };
                          }
                        };
                        const basisInfo = getBasisInfo();
                        
                        return (
                        <tr key={assessment.id}>
                          <td>
                            <span className={`badge ${basisType === 'DEMAND' ? 'bg-primary' : 'bg-info'}`}>
                              {basisType}
                            </span>
                          </td>
                          <td>
                            <div>
                              <strong>{basisInfo.title}</strong>
                              <br />
                              <small className="text-muted">{basisInfo.subtitle}</small>
                            </div>
                          </td>
                          <td>
                            <span className={`badge ${basisInfo.typeClass}`}>
                              {basisInfo.typeLabel}
                            </span>
                          </td>
                          <td>{assessment.endDate}</td>
                          <td>
                            <span className={`badge ${getStatusBadgeClass(assessment.status)}`}>
                              {assessment.status.replace('_', ' ')}
                            </span>
                          </td>
                          <td>{assessment.assessor?.username || '-'}</td>
                          <td>{assessment.requestor?.username || '-'}</td>
                          <td>{assessment.respondent?.username || '-'}</td>
                          <td>
                            {assessment.useCases && assessment.useCases.length > 0 
                              ? assessment.useCases.map(uc => uc.name).join(', ')
                              : '-'
                            }
                          </td>
                          <td>
                            {assessment.createdAt ? new Date(assessment.createdAt).toLocaleDateString() : '-'}
                          </td>
                          <td>
                            <div className="btn-group-vertical btn-group-sm" role="group">
                              <button onClick={() => handleEdit(assessment)} className="btn btn-outline-primary mb-1">Edit</button>
                              {assessment.status === 'STARTED' && (
                                <button 
                                  onClick={() => handlePerformAssessment(assessment)} 
                                  className="btn btn-outline-success mb-1"
                                  title="Perform risk assessment questionnaire"
                                >
                                  Perform Assessment
                                </button>
                              )}
                              <button 
                                onClick={() => handleCheckAnswers(assessment)} 
                                className="btn btn-outline-warning mb-1"
                                title="Check assessment answers"
                              >
                                Check Answers
                              </button>
                              {assessment.respondent && (
                                <button 
                                  onClick={() => handleSendNotification(assessment)} 
                                  className="btn btn-outline-info mb-1"
                                  title="Send notification to respondent"
                                >
                                  Notify
                                </button>
                              )}
                              <button onClick={() => assessment.id && handleDelete(assessment.id)} className="btn btn-outline-danger">Delete</button>
                            </div>
                          </td>
                        </tr>
                      );
                      })}
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

      {error && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="alert alert-danger" role="alert">
              {error}
            </div>
          </div>
        </div>
      )}

      {/* Assessment Performance Modal */}
      {showAssessmentModal && selectedAssessmentId && (
        <AssessmentPerformance
          assessmentId={selectedAssessmentId}
          mode={assessmentModalMode}
          onClose={handleAssessmentModalClose}
          onComplete={handleAssessmentComplete}
        />
      )}
    </div>
  );
};

export default RiskAssessmentManagement;
