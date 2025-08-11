import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

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

interface Demand {
  id?: number;
  title: string;
  description?: string;
  demandType: 'CHANGE' | 'CREATE_NEW';
  existingAsset?: Asset;
  existingAssetId?: number;
  newAssetName?: string;
  newAssetType?: string;
  newAssetIp?: string;
  newAssetOwner?: string;
  newAssetDescription?: string;
  businessJustification?: string;
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'IN_PROGRESS' | 'COMPLETED';
  requestor?: User;
  requestorId: number;
  approver?: User;
  requestedDate: string;
  approvedDate?: string;
  rejectionReason?: string;
  createdAt?: string;
  updatedAt?: string;
  classification?: 'A' | 'B' | 'C';
  classificationHash?: string;
}

interface ClassificationResult {
  classification: string;
  classificationHash: string;
  confidenceScore: number;
  appliedRuleName?: string;
  evaluationLog: string[];
  timestamp: string;
}

interface DemandSummary {
  totalDemands: number;
  pendingDemands: number;
  approvedDemands: number;
  rejectedDemands: number;
  changeDemands: number;
  createNewDemands: number;
}

const DemandManagement: React.FC = () => {
  const [demands, setDemands] = useState<Demand[]>([]);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [summary, setSummary] = useState<DemandSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingDemand, setEditingDemand] = useState<Demand | null>(null);
  const [classificationResult, setClassificationResult] = useState<ClassificationResult | null>(null);
  const [classifyOnCreate, setClassifyOnCreate] = useState(false);
  const [showClassificationResult, setShowClassificationResult] = useState(false);
  
  // Filters
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [typeFilter, setTypeFilter] = useState<string>('ALL');
  const [priorityFilter, setPriorityFilter] = useState<string>('ALL');

  const [formData, setFormData] = useState<Demand>({
    title: '',
    description: '',
    demandType: 'CHANGE',
    existingAssetId: undefined,
    newAssetName: '',
    newAssetType: '',
    newAssetIp: '',
    newAssetOwner: '',
    newAssetDescription: '',
    businessJustification: '',
    priority: 'MEDIUM',
    status: 'PENDING',
    requestorId: 0,
    requestedDate: new Date().toISOString()
  });

  // Check if user is authenticated
  const [currentUser, setCurrentUser] = useState<User | null>(null);

  useEffect(() => {
    const checkAuth = () => {
      if (typeof window !== 'undefined' && (window as any).currentUser) {
        setCurrentUser((window as any).currentUser);
      }
    };
    
    checkAuth();
    
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
      fetchDemands();
      fetchAssets();
      fetchUsers();
      fetchSummary();
    }
  }, [currentUser, statusFilter, typeFilter, priorityFilter]);

  const fetchDemands = async () => {
    try {
      const params = new URLSearchParams();
      if (statusFilter !== 'ALL') params.append('status', statusFilter);
      if (typeFilter !== 'ALL') params.append('demandType', typeFilter);
      if (priorityFilter !== 'ALL') params.append('priority', priorityFilter);
      
      const url = `/api/demands${params.toString() ? '?' + params.toString() : ''}`;
      const response = await authenticatedGet(url);
      if (response.ok) {
        const data = await response.json();
        setDemands(data);
      } else {
        setError(`Failed to fetch demands: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
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

  const fetchSummary = async () => {
    try {
      const response = await authenticatedGet('/api/demands/summary');
      if (response.ok) {
        const data = await response.json();
        setSummary(data);
      } else {
        console.error('Failed to fetch summary:', response.status);
      }
    } catch (err) {
      console.error('Failed to fetch summary:', err);
    }
  };

  const classifyDemand = async () => {
    try {
      const classificationInput = {
        title: formData.title,
        description: formData.description,
        demandType: formData.demandType,
        priority: formData.priority,
        businessJustification: formData.businessJustification,
        assetType: formData.demandType === 'CHANGE' 
          ? assets.find(a => a.id === formData.existingAssetId)?.type 
          : formData.newAssetType,
        assetOwner: formData.demandType === 'CHANGE' 
          ? assets.find(a => a.id === formData.existingAssetId)?.owner 
          : formData.newAssetOwner
      };

      const response = await authenticatedPost('/api/classification/test', {
        input: classificationInput
      });

      if (response.ok) {
        const result = await response.json();
        setClassificationResult(result);
        setShowClassificationResult(true);
        return result;
      }
    } catch (err) {
      console.error('Classification failed:', err);
    }
    return null;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    if (!currentUser) {
      setError('You must be logged in to create a demand');
      return;
    }
    
    try {
      // Classify if requested
      let classification = null;
      if (classifyOnCreate && !editingDemand) {
        classification = await classifyDemand();
      }

      const dataToSubmit = {
        ...formData,
        requestorId: editingDemand ? formData.requestorId : currentUser.id,
        classification: classification?.classification,
        classificationHash: classification?.classificationHash
      };
      
      let response;
      if (editingDemand) {
        response = await authenticatedPut(`/api/demands/${editingDemand.id}`, dataToSubmit);
      } else {
        response = await authenticatedPost('/api/demands', dataToSubmit);
      }
      
      if (!response.ok) {
        throw new Error(`Failed to save demand: ${response.status}`);
      }

      // If demand was created and classified, also save the classification result
      if (classification && !editingDemand) {
        const demandResponse = await response.json();
        if (demandResponse.id) {
          await authenticatedPost('/api/classification/classify-demand', {
            demandId: demandResponse.id
          });
        }
      }

      await fetchDemands();
      await fetchSummary();
      resetForm();
      setError(null);
      setClassificationResult(null);
      setShowClassificationResult(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleEdit = (demand: Demand) => {
    setEditingDemand(demand);
    setFormData({
      title: demand.title,
      description: demand.description || '',
      demandType: demand.demandType,
      existingAssetId: demand.existingAsset?.id,
      newAssetName: demand.newAssetName || '',
      newAssetType: demand.newAssetType || '',
      newAssetIp: demand.newAssetIp || '',
      newAssetOwner: demand.newAssetOwner || '',
      newAssetDescription: demand.newAssetDescription || '',
      businessJustification: demand.businessJustification || '',
      priority: demand.priority,
      status: demand.status,
      requestorId: demand.requestor?.id || demand.requestorId,
      requestedDate: demand.requestedDate
    });
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this demand?')) {
      return;
    }

    try {
      const response = await authenticatedDelete(`/api/demands/${id}`);
      if (!response.ok) {
        throw new Error(`Failed to delete demand: ${response.status}`);
      }
      await fetchDemands();
      await fetchSummary();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleApprove = async (demand: Demand, approved: boolean, reason?: string) => {
    try {
      const response = await authenticatedPost(`/api/demands/${demand.id}/approve`, {
        approved,
        rejectionReason: reason
      });
      
      if (!response.ok) {
        throw new Error(`Failed to ${approved ? 'approve' : 'reject'} demand: ${response.status}`);
      }
      
      await fetchDemands();
      await fetchSummary();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const resetForm = () => {
    setFormData({
      title: '',
      description: '',
      demandType: 'CHANGE',
      existingAssetId: undefined,
      newAssetName: '',
      newAssetType: '',
      newAssetIp: '',
      newAssetOwner: '',
      newAssetDescription: '',
      businessJustification: '',
      priority: 'MEDIUM',
      status: 'PENDING',
      requestorId: 0,
      requestedDate: new Date().toISOString()
    });
    setEditingDemand(null);
    setShowForm(false);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const { name, value } = e.target;
    setFormData(prev => ({ 
      ...prev, 
      [name]: value === '' ? (name.includes('Id') ? undefined : '') : 
              (name.includes('Id') ? parseInt(value) : value)
    }));
  };

  const getStatusBadgeClass = (status: string) => {
    switch (status.toUpperCase()) {
      case 'APPROVED': return 'bg-success';
      case 'PENDING': return 'bg-warning';
      case 'REJECTED': return 'bg-danger';
      case 'IN_PROGRESS': return 'bg-info';
      case 'COMPLETED': return 'bg-primary';
      default: return 'bg-secondary';
    }
  };

  const getPriorityBadgeClass = (priority: string) => {
    switch (priority.toUpperCase()) {
      case 'CRITICAL': return 'bg-danger';
      case 'HIGH': return 'bg-warning';
      case 'MEDIUM': return 'bg-info';
      case 'LOW': return 'bg-secondary';
      default: return 'bg-secondary';
    }
  };

  const getAssetInfo = (demand: Demand) => {
    if (demand.demandType === 'CHANGE' && demand.existingAsset) {
      return `${demand.existingAsset.name} (${demand.existingAsset.type})`;
    } else if (demand.demandType === 'CREATE_NEW') {
      return `${demand.newAssetName || 'Unnamed'} (${demand.newAssetType || 'Unknown'})`;
    }
    return 'N/A';
  };

  // Filter demands based on selected filters
  const filteredDemands = demands.filter(demand => {
    if (statusFilter !== 'ALL' && demand.status !== statusFilter) return false;
    if (typeFilter !== 'ALL' && demand.demandType !== typeFilter) return false;
    if (priorityFilter !== 'ALL' && demand.priority !== priorityFilter) return false;
    return true;
  });

  if (!currentUser) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-warning" role="alert">
          <h4 className="alert-heading">Authentication Required</h4>
          <p>You must be logged in to access Demand Management.</p>
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
            <h2>Demand Management</h2>
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
              {showForm ? 'Cancel' : 'Add New Demand'}
            </button>
          </div>
        </div>
      </div>

      {/* Summary Cards */}
      {summary && (
        <div className="row mb-4">
          <div className="col-md-2">
            <div className="card text-center">
              <div className="card-body">
                <h5 className="card-title">Total</h5>
                <h3 className="text-primary">{summary.totalDemands}</h3>
              </div>
            </div>
          </div>
          <div className="col-md-2">
            <div className="card text-center">
              <div className="card-body">
                <h5 className="card-title">Pending</h5>
                <h3 className="text-warning">{summary.pendingDemands}</h3>
              </div>
            </div>
          </div>
          <div className="col-md-2">
            <div className="card text-center">
              <div className="card-body">
                <h5 className="card-title">Approved</h5>
                <h3 className="text-success">{summary.approvedDemands}</h3>
              </div>
            </div>
          </div>
          <div className="col-md-2">
            <div className="card text-center">
              <div className="card-body">
                <h5 className="card-title">Rejected</h5>
                <h3 className="text-danger">{summary.rejectedDemands}</h3>
              </div>
            </div>
          </div>
          <div className="col-md-2">
            <div className="card text-center">
              <div className="card-body">
                <h5 className="card-title">Changes</h5>
                <h3 className="text-info">{summary.changeDemands}</h3>
              </div>
            </div>
          </div>
          <div className="col-md-2">
            <div className="card text-center">
              <div className="card-body">
                <h5 className="card-title">New Assets</h5>
                <h3 className="text-secondary">{summary.createNewDemands}</h3>
              </div>
            </div>
          </div>
        </div>
      )}

      {showForm && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-body">
                <h5 className="card-title">{editingDemand ? 'Edit Demand' : 'Add New Demand'}</h5>
                <form onSubmit={handleSubmit}>
                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="title" className="form-label">Title *</label>
                        <input
                          type="text"
                          className="form-control"
                          id="title"
                          name="title"
                          value={formData.title}
                          onChange={handleInputChange}
                          required
                        />
                      </div>
                    </div>
                    <div className="col-md-3">
                      <div className="mb-3">
                        <label htmlFor="demandType" className="form-label">Demand Type *</label>
                        <select
                          className="form-control"
                          id="demandType"
                          name="demandType"
                          value={formData.demandType}
                          onChange={handleInputChange}
                          required
                        >
                          <option value="CHANGE">Change Existing Asset</option>
                          <option value="CREATE_NEW">Create New Asset</option>
                        </select>
                      </div>
                    </div>
                    <div className="col-md-3">
                      <div className="mb-3">
                        <label htmlFor="priority" className="form-label">Priority *</label>
                        <select
                          className="form-control"
                          id="priority"
                          name="priority"
                          value={formData.priority}
                          onChange={handleInputChange}
                          required
                        >
                          <option value="LOW">Low</option>
                          <option value="MEDIUM">Medium</option>
                          <option value="HIGH">High</option>
                          <option value="CRITICAL">Critical</option>
                        </select>
                      </div>
                    </div>
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
                    />
                  </div>

                  {formData.demandType === 'CHANGE' ? (
                    <div className="mb-3">
                      <label htmlFor="existingAssetId" className="form-label">Existing Asset *</label>
                      <select
                        className="form-control"
                        id="existingAssetId"
                        name="existingAssetId"
                        value={formData.existingAssetId || ''}
                        onChange={handleInputChange}
                        required
                      >
                        <option value="">Select Asset</option>
                        {assets.map((asset) => (
                          <option key={asset.id} value={asset.id}>
                            {asset.name} ({asset.type}) - {asset.owner}
                          </option>
                        ))}
                      </select>
                    </div>
                  ) : (
                    <div className="row">
                      <div className="col-md-6">
                        <div className="mb-3">
                          <label htmlFor="newAssetName" className="form-label">New Asset Name *</label>
                          <input
                            type="text"
                            className="form-control"
                            id="newAssetName"
                            name="newAssetName"
                            value={formData.newAssetName}
                            onChange={handleInputChange}
                            required={formData.demandType === 'CREATE_NEW'}
                          />
                        </div>
                      </div>
                      <div className="col-md-3">
                        <div className="mb-3">
                          <label htmlFor="newAssetType" className="form-label">Asset Type *</label>
                          <input
                            type="text"
                            className="form-control"
                            id="newAssetType"
                            name="newAssetType"
                            value={formData.newAssetType}
                            onChange={handleInputChange}
                            required={formData.demandType === 'CREATE_NEW'}
                          />
                        </div>
                      </div>
                      <div className="col-md-3">
                        <div className="mb-3">
                          <label htmlFor="newAssetIp" className="form-label">IP Address</label>
                          <input
                            type="text"
                            className="form-control"
                            id="newAssetIp"
                            name="newAssetIp"
                            value={formData.newAssetIp}
                            onChange={handleInputChange}
                          />
                        </div>
                      </div>
                      <div className="col-md-6">
                        <div className="mb-3">
                          <label htmlFor="newAssetOwner" className="form-label">Asset Owner *</label>
                          <input
                            type="text"
                            className="form-control"
                            id="newAssetOwner"
                            name="newAssetOwner"
                            value={formData.newAssetOwner}
                            onChange={handleInputChange}
                            required={formData.demandType === 'CREATE_NEW'}
                          />
                        </div>
                      </div>
                      <div className="col-md-6">
                        <div className="mb-3">
                          <label htmlFor="newAssetDescription" className="form-label">Asset Description</label>
                          <textarea
                            className="form-control"
                            id="newAssetDescription"
                            name="newAssetDescription"
                            value={formData.newAssetDescription}
                            onChange={handleInputChange}
                            rows={2}
                          />
                        </div>
                      </div>
                    </div>
                  )}

                  <div className="mb-3">
                    <label htmlFor="businessJustification" className="form-label">Business Justification</label>
                    <textarea
                      className="form-control"
                      id="businessJustification"
                      name="businessJustification"
                      value={formData.businessJustification}
                      onChange={handleInputChange}
                      rows={3}
                      placeholder="Explain the business need and expected benefits"
                    />
                  </div>

                  {!editingDemand && (
                    <div className="mb-3">
                      <div className="form-check">
                        <input
                          className="form-check-input"
                          type="checkbox"
                          id="classifyOnCreate"
                          checked={classifyOnCreate}
                          onChange={(e) => setClassifyOnCreate(e.target.checked)}
                        />
                        <label className="form-check-label" htmlFor="classifyOnCreate">
                          Classify demand for security requirements
                        </label>
                      </div>
                      {classifyOnCreate && (
                        <button 
                          type="button" 
                          className="btn btn-sm btn-info mt-2"
                          onClick={classifyDemand}
                        >
                          Preview Classification
                        </button>
                      )}
                    </div>
                  )}

                  {showClassificationResult && classificationResult && (
                    <div className="alert alert-info mb-3">
                      <h6>Classification Result:</h6>
                      <p className="mb-1">
                        <strong>Classification:</strong> {classificationResult.classification} - 
                        {classificationResult.classification === 'A' && ' High Risk'}
                        {classificationResult.classification === 'B' && ' Medium Risk'}
                        {classificationResult.classification === 'C' && ' Low Risk'}
                      </p>
                      <p className="mb-1">
                        <strong>Confidence:</strong> {(classificationResult.confidenceScore * 100).toFixed(0)}%
                      </p>
                      <p className="mb-0">
                        <strong>Applied Rule:</strong> {classificationResult.appliedRuleName || 'Default'}
                      </p>
                    </div>
                  )}

                  <div className="d-flex justify-content-end">
                    <button type="submit" className="btn btn-success me-2">
                      {editingDemand ? 'Update' : 'Save'}
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
                <h5 className="card-title mb-0">Demands ({filteredDemands.length})</h5>
                <div className="d-flex align-items-center gap-3">
                  <div className="d-flex align-items-center">
                    <label htmlFor="statusFilter" className="form-label me-2 mb-0">Status:</label>
                    <select
                      id="statusFilter"
                      className="form-select form-select-sm"
                      style={{ width: 'auto' }}
                      value={statusFilter}
                      onChange={(e) => setStatusFilter(e.target.value)}
                    >
                      <option value="ALL">All</option>
                      <option value="PENDING">Pending</option>
                      <option value="APPROVED">Approved</option>
                      <option value="REJECTED">Rejected</option>
                      <option value="IN_PROGRESS">In Progress</option>
                      <option value="COMPLETED">Completed</option>
                    </select>
                  </div>
                  <div className="d-flex align-items-center">
                    <label htmlFor="typeFilter" className="form-label me-2 mb-0">Type:</label>
                    <select
                      id="typeFilter"
                      className="form-select form-select-sm"
                      style={{ width: 'auto' }}
                      value={typeFilter}
                      onChange={(e) => setTypeFilter(e.target.value)}
                    >
                      <option value="ALL">All</option>
                      <option value="CHANGE">Change</option>
                      <option value="CREATE_NEW">Create New</option>
                    </select>
                  </div>
                  <div className="d-flex align-items-center">
                    <label htmlFor="priorityFilter" className="form-label me-2 mb-0">Priority:</label>
                    <select
                      id="priorityFilter"
                      className="form-select form-select-sm"
                      style={{ width: 'auto' }}
                      value={priorityFilter}
                      onChange={(e) => setPriorityFilter(e.target.value)}
                    >
                      <option value="ALL">All</option>
                      <option value="LOW">Low</option>
                      <option value="MEDIUM">Medium</option>
                      <option value="HIGH">High</option>
                      <option value="CRITICAL">Critical</option>
                    </select>
                  </div>
                </div>
              </div>
              
              {filteredDemands.length === 0 ? (
                <p className="text-muted">
                  {demands.length === 0 
                    ? "No demands found. Click \"Add New Demand\" to create one."
                    : "No demands match the current filters."
                  }
                </p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped table-hover">
                    <thead>
                      <tr>
                        <th>Title</th>
                        <th>Type</th>
                        <th>Asset/New Asset</th>
                        <th>Priority</th>
                        <th>Status</th>
                        <th>Requestor</th>
                        <th>Requested</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredDemands.map((demand) => (
                        <tr key={demand.id}>
                          <td>
                            <div>
                              <strong>{demand.title}</strong>
                              {demand.description && (
                                <><br /><small className="text-muted">{demand.description}</small></>
                              )}
                            </div>
                          </td>
                          <td>
                            <span className={`badge ${demand.demandType === 'CHANGE' ? 'bg-info' : 'bg-secondary'}`}>
                              {demand.demandType === 'CHANGE' ? 'Change' : 'Create New'}
                            </span>
                          </td>
                          <td>{getAssetInfo(demand)}</td>
                          <td>
                            <span className={`badge ${getPriorityBadgeClass(demand.priority)}`}>
                              {demand.priority}
                            </span>
                          </td>
                          <td>
                            <span className={`badge ${getStatusBadgeClass(demand.status)}`}>
                              {demand.status.replace('_', ' ')}
                            </span>
                          </td>
                          <td>{demand.requestor?.username || '-'}</td>
                          <td>
                            {demand.requestedDate ? new Date(demand.requestedDate).toLocaleDateString() : '-'}
                          </td>
                          <td>
                            <div className="btn-group-vertical btn-group-sm" role="group">
                              <button onClick={() => handleEdit(demand)} className="btn btn-outline-primary mb-1">
                                Edit
                              </button>
                              {demand.status === 'PENDING' && (
                                <>
                                  <button 
                                    onClick={() => handleApprove(demand, true)} 
                                    className="btn btn-outline-success mb-1"
                                  >
                                    Approve
                                  </button>
                                  <button 
                                    onClick={() => {
                                      const reason = prompt('Rejection reason (optional):');
                                      handleApprove(demand, false, reason || undefined);
                                    }} 
                                    className="btn btn-outline-danger mb-1"
                                  >
                                    Reject
                                  </button>
                                </>
                              )}
                              <button 
                                onClick={() => demand.id && handleDelete(demand.id)} 
                                className="btn btn-outline-danger"
                                disabled={demand.status === 'IN_PROGRESS' || demand.status === 'COMPLETED'}
                              >
                                Delete
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

      {error && (
        <div className="row mb-4">
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

export default DemandManagement;