import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

interface Risk {
  id: number;
  name: string;
  description: string;
  likelihood: number;
  impact: number;
  riskLevel: number;
  status?: string;
  owner?: string;
  severity?: string;
  deadline?: string;
  createdAt: string;
  updatedAt: string;
}

interface RiskFormData {
  name: string;
  description: string;
  likelihood: number;
  impact: number;
  status: string;
  owner: string;
  severity: string;
  deadline: string;
  asset_id: number;
}

const RiskManagement: React.FC = () => {
  const [risks, setRisks] = useState<Risk[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isAddingRisk, setIsAddingRisk] = useState(false);
  const [editingRisk, setEditingRisk] = useState<Risk | null>(null);
  const [formData, setFormData] = useState<RiskFormData>({
    name: '',
    description: '',
    likelihood: 1,
    impact: 1,
    status: 'OPEN',
    owner: '',
    severity: '',
    deadline: '',
    asset_id: 0,
  });
  const [assets, setAssets] = useState<any[]>([]);

  useEffect(() => {
    fetchRisks();
    fetchAssets();
  }, []);

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

  const fetchRisks = async () => {
    try {
      setLoading(true);
      const response = await authenticatedGet('/api/risks');
      if (response.ok) {
        const data = await response.json();
        setRisks(data);
        setError(null);
      } else {
        setError(`Failed to fetch risks: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch risks');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const submitData = {
        name: formData.name,
        description: formData.description,
        likelihood: Number(formData.likelihood),
        impact: Number(formData.impact),
      };
      let response;
      if (editingRisk) {
        response = await authenticatedPut(`/api/risks/${editingRisk.id}`, submitData);
      } else {
        response = await authenticatedPost('/api/risks', submitData);
      }
      if (!response.ok) {
        throw new Error(`Failed to save risk: ${response.status}`);
      }
      resetForm();
      await fetchRisks();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save risk');
    }
  };

  const handleEdit = (risk: Risk) => {
    setEditingRisk(risk);
    setFormData({
      name: risk.name,
      description: risk.description,
      likelihood: risk.likelihood,
      impact: risk.impact,
      status: risk.status || 'OPEN',
      owner: risk.owner || '',
      severity: risk.severity || '',
      deadline: risk.deadline || '',
      asset_id: 0, // Will need to be populated from asset data
    });
    setIsAddingRisk(true);
  };

  const handleDelete = async (id: number) => {
    if (!confirm('Are you sure you want to delete this risk?')) return;
    try {
      const response = await authenticatedDelete(`/api/risks/${id}`);
      if (!response.ok) {
        throw new Error(`Failed to delete risk: ${response.status}`);
      }
      await fetchRisks();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete risk');
    }
  };

  const resetForm = () => {
    setEditingRisk(null);
    setFormData({ name: '', description: '', likelihood: 1, impact: 1, status: 'OPEN', owner: '', severity: '', deadline: '', asset_id: 0 });
    setIsAddingRisk(false);
    setError(null);
  };

  if (loading) return <div className="text-center py-4">Loading risks...</div>;

  return (
    <div className="container-fluid p-4">
      <div className="row">
        <div className="col-12">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <h2>Risk Management</h2>
            <button
              className="btn btn-outline-primary"
              onClick={() => {
                if (isAddingRisk) {
                  resetForm();
                } else {
                  setIsAddingRisk(true);
                }
              }}
            >
              {isAddingRisk ? 'Cancel' : 'Add New Risk'}
            </button>
          </div>
        </div>
      </div>
      {isAddingRisk && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-body">
                <h5 className="card-title">{editingRisk ? 'Edit Risk' : 'Add New Risk'}</h5>
                <form onSubmit={handleSubmit}>
                  <div className="mb-3">
                    <label htmlFor="name" className="form-label">Name</label>
                    <input
                      type="text"
                      name="name"
                      id="name"
                      className="form-control"
                      value={formData.name}
                      onChange={e => setFormData({ ...formData, name: e.target.value })}
                      required
                      maxLength={255}
                    />
                  </div>
                  <div className="mb-3">
                    <label htmlFor="description" className="form-label">Description</label>
                    <textarea
                      name="description"
                      id="description"
                      className="form-control"
                      value={formData.description}
                      onChange={e => setFormData({ ...formData, description: e.target.value })}
                      required
                      maxLength={1024}
                    />
                  </div>
                  <div className="mb-3">
                    <label htmlFor="likelihood" className="form-label">Likelihood (1-5)</label>
                    <input
                      type="number"
                      name="likelihood"
                      id="likelihood"
                      className="form-control"
                      min={1}
                      max={5}
                      value={formData.likelihood}
                      onChange={e => setFormData({ ...formData, likelihood: Number(e.target.value) })}
                      required
                    />
                  </div>
                  <div className="mb-3">
                    <label htmlFor="impact" className="form-label">Impact (1-5)</label>
                    <input
                      type="number"
                      name="impact"
                      id="impact"
                      className="form-control"
                      min={1}
                      max={5}
                      value={formData.impact}
                      onChange={e => setFormData({ ...formData, impact: Number(e.target.value) })}
                      required
                    />
                  </div>
                  <div className="mb-3">
                    <label htmlFor="asset_id" className="form-label">Asset *</label>
                    <select
                      name="asset_id"
                      id="asset_id"
                      className="form-control"
                      value={formData.asset_id}
                      onChange={e => setFormData({ ...formData, asset_id: Number(e.target.value) })}
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
                    <label htmlFor="status" className="form-label">Status</label>
                    <select
                      name="status"
                      id="status"
                      className="form-control"
                      value={formData.status}
                      onChange={e => setFormData({ ...formData, status: e.target.value })}
                    >
                      <option value="OPEN">Open</option>
                      <option value="IN_PROGRESS">In Progress</option>
                      <option value="MITIGATED">Mitigated</option>
                      <option value="CLOSED">Closed</option>
                    </select>
                  </div>
                  <div className="mb-3">
                    <label htmlFor="owner" className="form-label">Owner</label>
                    <input
                      type="text"
                      name="owner"
                      id="owner"
                      className="form-control"
                      value={formData.owner}
                      onChange={e => setFormData({ ...formData, owner: e.target.value })}
                      placeholder="Person responsible for mitigation"
                    />
                  </div>
                  <div className="mb-3">
                    <label htmlFor="severity" className="form-label">Severity</label>
                    <select
                      name="severity"
                      id="severity"
                      className="form-control"
                      value={formData.severity}
                      onChange={e => setFormData({ ...formData, severity: e.target.value })}
                    >
                      <option value="">Select Severity</option>
                      <option value="LOW">Low</option>
                      <option value="MEDIUM">Medium</option>
                      <option value="HIGH">High</option>
                      <option value="CRITICAL">Critical</option>
                    </select>
                  </div>
                  <div className="mb-3">
                    <label htmlFor="deadline" className="form-label">Deadline</label>
                    <input
                      type="date"
                      name="deadline"
                      id="deadline"
                      className="form-control"
                      value={formData.deadline}
                      onChange={e => setFormData({ ...formData, deadline: e.target.value })}
                    />
                  </div>
                  <div className="d-flex justify-content-end">
                    <button type="submit" className="btn btn-success me-2">
                      {editingRisk ? 'Update' : 'Save'}
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
              <h5 className="card-title">Existing Risks</h5>
              <div className="table-responsive">
                <table className="table table-striped table-hover">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Description</th>
                      <th>Likelihood</th>
                      <th>Impact</th>
                      <th>Risk Level</th>
                      <th>Status</th>
                      <th>Owner</th>
                      <th>Severity</th>
                      <th>Deadline</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {risks.length === 0 ? (
                      <tr>
                        <td colSpan={10} className="text-center">
                          No risks found. Click "Add New Risk" to create one.
                        </td>
                      </tr>
                    ) : (
                      risks.map(risk => (
                        <tr key={risk.id}>
                          <td>{risk.name}</td>
                          <td>{risk.description}</td>
                          <td>{risk.likelihood}</td>
                          <td>{risk.impact}</td>
                          <td>{['Low','Medium','High','Very High'][risk.riskLevel-1] || risk.riskLevel}</td>
                          <td><span className={`badge ${risk.status === 'OPEN' ? 'bg-danger' : risk.status === 'IN_PROGRESS' ? 'bg-warning' : risk.status === 'MITIGATED' ? 'bg-success' : 'bg-secondary'}`}>{risk.status || 'OPEN'}</span></td>
                          <td>{risk.owner || '-'}</td>
                          <td>{risk.severity || '-'}</td>
                          <td>{risk.deadline ? new Date(risk.deadline).toLocaleDateString() : '-'}</td>
                          <td>
                            <button onClick={() => handleEdit(risk)} className="btn btn-sm btn-outline-primary me-2">Edit</button>
                            <button onClick={() => handleDelete(risk.id)} className="btn btn-sm btn-outline-danger">Delete</button>
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
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

export default RiskManagement;
