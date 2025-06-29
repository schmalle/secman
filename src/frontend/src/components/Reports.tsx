import React, { useState, useEffect } from 'react';

interface ReportSummary {
  assessmentSummary: {
    total: number;
    statusBreakdown: Record<string, number>;
  };
  riskSummary: {
    total: number;
    statusBreakdown: Record<string, number>;
    riskLevelBreakdown: Record<string, number>;
  };
  assetCoverage: {
    totalAssets: number;
    assetsWithAssessments: number;
    coveragePercentage: number;
  };
  recentAssessments: Array<{
    id: number;
    assetName: string;
    status: string;
    assessor: string;
    startDate: string;
    endDate: string;
  }>;
  highPriorityRisks: Array<{
    id: number;
    name: string;
    assetName: string;
    riskLevel: number;
    status: string;
    owner: string;
    severity: string;
    deadline?: string;
  }>;
}

interface MitigationReport {
  summary: {
    totalOpenRisks: number;
    overdueRisks: number;
    unassignedRisks: number;
  };
  risks: Array<{
    id: number;
    name: string;
    description: string;
    assetName: string;
    riskLevel: number;
    status: string;
    owner: string;
    severity: string;
    deadline?: string;
    isOverdue?: boolean;
    likelihood: number;
    impact: number;
  }>;
}

const Reports: React.FC = () => {
  const [reportSummary, setReportSummary] = useState<ReportSummary | null>(null);
  const [mitigationReport, setMitigationReport] = useState<MitigationReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'summary' | 'mitigation'>('summary');

  useEffect(() => {
    if (activeTab === 'summary') {
      fetchReportSummary();
    } else {
      fetchMitigationReport();
    }
  }, [activeTab]);

  const fetchReportSummary = async () => {
    try {
      setLoading(true);
      const response = await fetch('/api/reports/risk-assessment-summary');
      if (!response.ok) {
        throw new Error('Failed to fetch report summary');
      }
      const data = await response.json();
      setReportSummary(data);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const fetchMitigationReport = async () => {
    try {
      setLoading(true);
      const response = await fetch('/api/reports/risk-mitigation-status');
      if (!response.ok) {
        throw new Error('Failed to fetch mitigation report');
      }
      const data = await response.json();
      setMitigationReport(data);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const getRiskLevelBadgeClass = (level: number) => {
    switch (level) {
      case 1: return 'bg-success';
      case 2: return 'bg-warning';
      case 3: return 'bg-danger';
      case 4: return 'bg-dark';
      default: return 'bg-secondary';
    }
  };

  const getRiskLevelName = (level: number) => {
    switch (level) {
      case 1: return 'Low';
      case 2: return 'Medium';
      case 3: return 'High';
      case 4: return 'Very High';
      default: return 'Unknown';
    }
  };

  const getStatusBadgeClass = (status: string) => {
    switch (status.toUpperCase()) {
      case 'COMPLETED': return 'bg-success';
      case 'IN_PROGRESS': return 'bg-warning';
      case 'PENDING': return 'bg-secondary';
      case 'CANCELLED': return 'bg-danger';
      case 'OPEN': return 'bg-danger';
      case 'MITIGATED': return 'bg-success';
      case 'CLOSED': return 'bg-secondary';
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
          <h2>Risk Assessment Reports</h2>
          
          {/* Tab Navigation */}
          <ul className="nav nav-tabs mb-4" role="tablist">
            <li className="nav-item" role="presentation">
              <button
                className={`nav-link ${activeTab === 'summary' ? 'active' : ''}`}
                onClick={() => setActiveTab('summary')}
                type="button"
                role="tab"
              >
                Summary Report
              </button>
            </li>
            <li className="nav-item" role="presentation">
              <button
                className={`nav-link ${activeTab === 'mitigation' ? 'active' : ''}`}
                onClick={() => setActiveTab('mitigation')}
                type="button"
                role="tab"
              >
                Risk Mitigation Status
              </button>
            </li>
          </ul>

          {error && (
            <div className="alert alert-danger" role="alert">
              {error}
            </div>
          )}

          {/* Summary Report Tab */}
          {activeTab === 'summary' && reportSummary && (
            <div className="tab-pane fade show active">
              {/* Key Metrics Cards */}
              <div className="row mb-4">
                <div className="col-md-3">
                  <div className="card text-center">
                    <div className="card-body">
                      <h5 className="card-title">Total Assessments</h5>
                      <h2 className="text-primary">{reportSummary.assessmentSummary.total}</h2>
                    </div>
                  </div>
                </div>
                <div className="col-md-3">
                  <div className="card text-center">
                    <div className="card-body">
                      <h5 className="card-title">Total Risks</h5>
                      <h2 className="text-warning">{reportSummary.riskSummary.total}</h2>
                    </div>
                  </div>
                </div>
                <div className="col-md-3">
                  <div className="card text-center">
                    <div className="card-body">
                      <h5 className="card-title">Asset Coverage</h5>
                      <h2 className="text-info">{reportSummary.assetCoverage.coveragePercentage}%</h2>
                      <small className="text-muted">
                        {reportSummary.assetCoverage.assetsWithAssessments} of {reportSummary.assetCoverage.totalAssets} assets
                      </small>
                    </div>
                  </div>
                </div>
                <div className="col-md-3">
                  <div className="card text-center">
                    <div className="card-body">
                      <h5 className="card-title">High Priority Risks</h5>
                      <h2 className="text-danger">{reportSummary.highPriorityRisks.length}</h2>
                    </div>
                  </div>
                </div>
              </div>

              {/* Status Breakdowns */}
              <div className="row mb-4">
                <div className="col-md-6">
                  <div className="card">
                    <div className="card-body">
                      <h5 className="card-title">Assessment Status Breakdown</h5>
                      {Object.entries(reportSummary.assessmentSummary.statusBreakdown).map(([status, count]) => (
                        <div key={status} className="d-flex justify-content-between align-items-center mb-2">
                          <span className={`badge ${getStatusBadgeClass(status)} me-2`}>{status.replace('_', ' ')}</span>
                          <span className="fw-bold">{count}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
                <div className="col-md-6">
                  <div className="card">
                    <div className="card-body">
                      <h5 className="card-title">Risk Level Breakdown</h5>
                      {Object.entries(reportSummary.riskSummary.riskLevelBreakdown).map(([level, count]) => (
                        <div key={level} className="d-flex justify-content-between align-items-center mb-2">
                          <span className={`badge ${getRiskLevelBadgeClass(parseInt(level))} me-2`}>
                            {getRiskLevelName(parseInt(level))}
                          </span>
                          <span className="fw-bold">{count}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              </div>

              {/* Recent Assessments */}
              <div className="row mb-4">
                <div className="col-12">
                  <div className="card">
                    <div className="card-body">
                      <h5 className="card-title">Recent Risk Assessments</h5>
                      <div className="table-responsive">
                        <table className="table table-striped">
                          <thead>
                            <tr>
                              <th>Asset</th>
                              <th>Status</th>
                              <th>Assessor</th>
                              <th>Start Date</th>
                              <th>End Date</th>
                            </tr>
                          </thead>
                          <tbody>
                            {reportSummary.recentAssessments.map((assessment) => (
                              <tr key={assessment.id}>
                                <td>{assessment.assetName}</td>
                                <td>
                                  <span className={`badge ${getStatusBadgeClass(assessment.status)}`}>
                                    {assessment.status.replace('_', ' ')}
                                  </span>
                                </td>
                                <td>{assessment.assessor || '-'}</td>
                                <td>{new Date(assessment.startDate).toLocaleDateString()}</td>
                                <td>{new Date(assessment.endDate).toLocaleDateString()}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              {/* High Priority Risks */}
              <div className="row">
                <div className="col-12">
                  <div className="card">
                    <div className="card-body">
                      <h5 className="card-title">High Priority Risks</h5>
                      <div className="table-responsive">
                        <table className="table table-striped">
                          <thead>
                            <tr>
                              <th>Risk Name</th>
                              <th>Asset</th>
                              <th>Risk Level</th>
                              <th>Status</th>
                              <th>Owner</th>
                              <th>Severity</th>
                              <th>Deadline</th>
                            </tr>
                          </thead>
                          <tbody>
                            {reportSummary.highPriorityRisks.map((risk) => (
                              <tr key={risk.id}>
                                <td>{risk.name}</td>
                                <td>{risk.assetName}</td>
                                <td>
                                  <span className={`badge ${getRiskLevelBadgeClass(risk.riskLevel)}`}>
                                    {getRiskLevelName(risk.riskLevel)}
                                  </span>
                                </td>
                                <td>
                                  <span className={`badge ${getStatusBadgeClass(risk.status)}`}>
                                    {risk.status}
                                  </span>
                                </td>
                                <td>{risk.owner || '-'}</td>
                                <td>{risk.severity || '-'}</td>
                                <td>{risk.deadline ? new Date(risk.deadline).toLocaleDateString() : '-'}</td>
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
          )}

          {/* Mitigation Report Tab */}
          {activeTab === 'mitigation' && mitigationReport && (
            <div className="tab-pane fade show active">
              {/* Mitigation Summary */}
              <div className="row mb-4">
                <div className="col-md-4">
                  <div className="card text-center">
                    <div className="card-body">
                      <h5 className="card-title">Open Risks</h5>
                      <h2 className="text-warning">{mitigationReport.summary.totalOpenRisks}</h2>
                    </div>
                  </div>
                </div>
                <div className="col-md-4">
                  <div className="card text-center">
                    <div className="card-body">
                      <h5 className="card-title">Overdue Risks</h5>
                      <h2 className="text-danger">{mitigationReport.summary.overdueRisks}</h2>
                    </div>
                  </div>
                </div>
                <div className="col-md-4">
                  <div className="card text-center">
                    <div className="card-body">
                      <h5 className="card-title">Unassigned Risks</h5>
                      <h2 className="text-secondary">{mitigationReport.summary.unassignedRisks}</h2>
                    </div>
                  </div>
                </div>
              </div>

              {/* Risk Mitigation Table */}
              <div className="row">
                <div className="col-12">
                  <div className="card">
                    <div className="card-body">
                      <h5 className="card-title">Risk Mitigation Tracking</h5>
                      <div className="table-responsive">
                        <table className="table table-striped">
                          <thead>
                            <tr>
                              <th>Risk Name</th>
                              <th>Asset</th>
                              <th>Risk Level</th>
                              <th>Status</th>
                              <th>Owner</th>
                              <th>Severity</th>
                              <th>Deadline</th>
                              <th>Likelihood</th>
                              <th>Impact</th>
                            </tr>
                          </thead>
                          <tbody>
                            {mitigationReport.risks.map((risk) => (
                              <tr key={risk.id} className={risk.isOverdue ? 'table-danger' : ''}>
                                <td>
                                  <div>
                                    <strong>{risk.name}</strong>
                                    <br />
                                    <small className="text-muted">{risk.description}</small>
                                  </div>
                                </td>
                                <td>{risk.assetName}</td>
                                <td>
                                  <span className={`badge ${getRiskLevelBadgeClass(risk.riskLevel)}`}>
                                    {getRiskLevelName(risk.riskLevel)}
                                  </span>
                                </td>
                                <td>
                                  <span className={`badge ${getStatusBadgeClass(risk.status)}`}>
                                    {risk.status}
                                  </span>
                                </td>
                                <td>{risk.owner || <span className="text-muted">Unassigned</span>}</td>
                                <td>{risk.severity || '-'}</td>
                                <td>
                                  {risk.deadline ? (
                                    <span className={risk.isOverdue ? 'text-danger fw-bold' : ''}>
                                      {new Date(risk.deadline).toLocaleDateString()}
                                      {risk.isOverdue && ' (OVERDUE)'}
                                    </span>
                                  ) : '-'}
                                </td>
                                <td>{risk.likelihood}</td>
                                <td>{risk.impact}</td>
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
          )}
        </div>
      </div>
    </div>
  );
};

export default Reports;