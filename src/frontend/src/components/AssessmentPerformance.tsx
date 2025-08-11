import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut } from '../utils/auth';

interface Asset {
  id: number;
  name: string;
  type: string;
  ip?: string;
  owner: string;
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
}

interface Requirement {
  id: number;
  shortreq: string;
  details?: string;
  norm?: string;
  chapter?: string;
  usecase?: string;
}

interface RiskAssessment {
  id: number;
  assessmentBasisType: 'DEMAND' | 'ASSET';
  assessmentBasisId: number;
  demand?: Demand;
  asset?: Asset;
  endDate: string;
  status: string;
  assessor?: { id: number; username: string; email: string };
  requestor?: { id: number; username: string; email: string };
  respondent?: { id: number; username: string; email: string };
  notes?: string;
}

interface Response {
  id?: number;
  requirement: { id: number };
  answerType: 'YES' | 'NO' | 'N_A';
  comment?: string;
}

interface AssessmentData {
  assessment: RiskAssessment;
  requirements: Requirement[];
  responses: Response[];
  isComplete: boolean;
  completionPercentage: number;
  canEdit: boolean;
  canReview: boolean;
}

interface RequirementWithResponse {
  requirement: Requirement;
  response: Response | null;
  canRaiseRisk: boolean;
}

interface ResponseFormData {
  [requirementId: number]: {
    answerType: 'YES' | 'NO' | 'N_A' | '';
    comment: string;
    raiseRisk?: boolean;
    riskDescription?: string;
    riskLikelihood?: number;
    riskImpact?: number;
  };
}

interface AssessmentPerformanceProps {
  assessmentId: number;
  mode: 'perform' | 'review';
  onClose: () => void;
  onComplete?: () => void;
}

const AssessmentPerformance: React.FC<AssessmentPerformanceProps> = ({ 
  assessmentId, 
  mode, 
  onClose,
  onComplete 
}) => {
  const [assessmentData, setAssessmentData] = useState<AssessmentData | null>(null);
  const [requirementsWithResponses, setRequirementsWithResponses] = useState<RequirementWithResponse[]>([]);
  const [responses, setResponses] = useState<ResponseFormData>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [saveMessage, setSaveMessage] = useState('');
  const [currentTab, setCurrentTab] = useState<'assessment' | 'risk-raising'>('assessment');
  const [filterStatus, setFilterStatus] = useState<'all' | 'compliant' | 'non-compliant' | 'not-applicable'>('all');

  useEffect(() => {
    fetchAssessmentData();
  }, [assessmentId]);

  const fetchAssessmentData = async () => {
    try {
      setLoading(true);
      setError(null);

      // Fetch assessment data
      const assessmentResponse = await authenticatedGet(`/api/responses/assessment/${assessmentId}/authenticated`);
      if (!assessmentResponse.ok) {
        throw new Error('Failed to load assessment data');
      }
      const data: AssessmentData = await assessmentResponse.json();
      setAssessmentData(data);

      // Fetch requirements with responses
      const requirementsResponse = await authenticatedGet(`/api/responses/assessment/${assessmentId}/requirements-with-responses`);
      if (!requirementsResponse.ok) {
        throw new Error('Failed to load requirements');
      }
      const reqWithResp: RequirementWithResponse[] = await requirementsResponse.json();
      setRequirementsWithResponses(reqWithResp);

      // Initialize form data with existing responses
      const initialResponses: ResponseFormData = {};
      reqWithResp.forEach(item => {
        initialResponses[item.requirement.id] = {
          answerType: item.response?.answerType || '',
          comment: item.response?.comment || '',
          raiseRisk: false,
          riskDescription: '',
          riskLikelihood: 3,
          riskImpact: 3
        };
      });
      setResponses(initialResponses);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const handleAnswerChange = (requirementId: number, answerType: 'YES' | 'NO' | 'N_A') => {
    setResponses(prev => ({
      ...prev,
      [requirementId]: {
        ...prev[requirementId],
        answerType
      }
    }));
  };

  const handleCommentChange = (requirementId: number, comment: string) => {
    setResponses(prev => ({
      ...prev,
      [requirementId]: {
        ...prev[requirementId],
        comment
      }
    }));
  };

  const handleRiskToggle = (requirementId: number) => {
    setResponses(prev => ({
      ...prev,
      [requirementId]: {
        ...prev[requirementId],
        raiseRisk: !prev[requirementId].raiseRisk
      }
    }));
  };

  const handleRiskFieldChange = (requirementId: number, field: string, value: any) => {
    setResponses(prev => ({
      ...prev,
      [requirementId]: {
        ...prev[requirementId],
        [field]: value
      }
    }));
  };

  const saveProgress = async () => {
    if (!assessmentData || saving) return;
    
    setSaving(true);
    setSaveMessage('');
    
    try {
      // Prepare responses to save
      const responsesToSave = Object.entries(responses)
        .filter(([_, data]) => data.answerType)
        .map(([requirementId, data]) => ({
          requirementId: parseInt(requirementId),
          answerType: data.answerType,
          comment: data.comment
        }));

      if (responsesToSave.length === 0) {
        setSaveMessage('No responses to save. Please answer at least one requirement.');
        setTimeout(() => setSaveMessage(''), 3000);
        return;
      }

      const response = await authenticatedPost(`/api/responses/assessment/${assessmentId}/bulk-save`, {
        responses: responsesToSave
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({ error: 'UNKNOWN', message: 'Unknown error occurred' }));
        if (errorData.error === 'AUTHENTICATION_ERROR') {
          throw new Error('Authentication error: Please refresh the page and log in again.');
        }
        throw new Error(errorData.message || 'Failed to save responses');
      }

      const result = await response.json();
      
      let message = `Progress saved successfully! (${result.savedCount} responses saved)`;
      if (result.errors && result.errors.length > 0) {
        message += ` Note: ${result.errorCount} errors occurred.`;
        console.warn('Save errors:', result.errors);
      }
      
      setSaveMessage(message);
      setTimeout(() => setSaveMessage(''), 3000);

      // Refresh data
      await fetchAssessmentData();
    } catch (err) {
      setSaveMessage(`Failed to save: ${err instanceof Error ? err.message : 'Unknown error'}`);
      console.error('Save failed:', err);
    } finally {
      setSaving(false);
    }
  };

  const submitAssessment = async () => {
    if (!assessmentData) return;
    
    // Check if all requirements have answers
    const unansweredRequirements = requirementsWithResponses.filter(item => 
      !responses[item.requirement.id]?.answerType
    );
    
    if (unansweredRequirements.length > 0) {
      setError(`Please answer all requirements before submitting. Missing ${unansweredRequirements.length} answer(s).`);
      return;
    }
    
    setSubmitting(true);
    setError(null);
    
    try {
      // Save all responses first
      await saveProgress();
      
      // Update assessment status
      const statusResponse = await authenticatedPut(`/api/risk-assessments/${assessmentId}`, {
        status: 'COMPLETED'
      });
      
      if (!statusResponse.ok) {
        throw new Error('Failed to complete assessment');
      }
      
      // Handle risk raising for non-compliant items
      const risksToRaise = Object.entries(responses)
        .filter(([_, data]) => data.raiseRisk && (data.answerType === 'NO' || data.answerType === 'N_A'))
        .map(([requirementId, data]) => ({
          requirementId: parseInt(requirementId),
          description: data.riskDescription || `Non-compliance with requirement ${requirementId}`,
          likelihood: data.riskLikelihood || 3,
          impact: data.riskImpact || 3
        }));

      if (risksToRaise.length > 0) {
        // Create risks for non-compliant items
        for (const risk of risksToRaise) {
          try {
            const riskResponse = await authenticatedPost(`/api/responses/assessment/${assessmentId}/create-risk`, risk);
            if (!riskResponse.ok) {
              console.error('Failed to create risk for requirement', risk.requirementId);
            }
          } catch (err) {
            console.error('Error creating risk:', err);
          }
        }
      }
      
      if (onComplete) {
        onComplete();
      }
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to submit assessment');
    } finally {
      setSubmitting(false);
    }
  };

  const getProgressPercentage = () => {
    if (!requirementsWithResponses.length) return 0;
    const answeredCount = requirementsWithResponses.filter(item => 
      responses[item.requirement.id]?.answerType
    ).length;
    return Math.round((answeredCount / requirementsWithResponses.length) * 100);
  };

  const getAnswerBadgeClass = (answerType: string) => {
    switch (answerType) {
      case 'YES': return 'bg-success';
      case 'NO': return 'bg-danger';
      case 'N_A': return 'bg-warning text-dark';
      default: return 'bg-secondary';
    }
  };

  const getFilteredRequirements = () => {
    if (filterStatus === 'all') return requirementsWithResponses;
    
    return requirementsWithResponses.filter(item => {
      const answerType = responses[item.requirement.id]?.answerType;
      switch (filterStatus) {
        case 'compliant':
          return answerType === 'YES';
        case 'non-compliant':
          return answerType === 'NO';
        case 'not-applicable':
          return answerType === 'N_A';
        default:
          return true;
      }
    });
  };

  if (loading) {
    return (
      <div className="modal-body">
        <div className="d-flex justify-content-center py-5">
          <div className="spinner-border text-primary" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
        </div>
      </div>
    );
  }

  if (error && !assessmentData) {
    return (
      <div className="modal-body">
        <div className="alert alert-danger" role="alert">
          <h5 className="alert-heading">Error</h5>
          <p>{error}</p>
        </div>
      </div>
    );
  }

  if (!assessmentData) {
    return (
      <div className="modal-body">
        <div className="alert alert-warning" role="alert">
          <p>No assessment data available.</p>
        </div>
      </div>
    );
  }

  const isReadOnly = mode === 'review' || !assessmentData.canEdit;

  return (
    <div className="modal fade show d-block" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
      <div className="modal-dialog modal-xl modal-dialog-scrollable">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title">
              {mode === 'perform' ? 'Perform Assessment' : 'Review Assessment Answers'}
            </h5>
            <button type="button" className="btn-close" onClick={onClose}></button>
          </div>

          <div className="modal-body">
            {/* Assessment Info */}
            <div className="card mb-3">
              <div className="card-body">
                <div className="row">
                  <div className="col-md-6">
                    <p className="mb-1">
                      <strong>Basis:</strong>{' '}
                      {assessmentData.assessment.assessmentBasisType === 'DEMAND' 
                        ? `Demand: ${assessmentData.assessment.demand?.title}`
                        : `Asset: ${assessmentData.assessment.asset?.name}`}
                    </p>
                    <p className="mb-1">
                      <strong>Assessor:</strong> {assessmentData.assessment.assessor?.username}
                    </p>
                    <p className="mb-1">
                      <strong>Requestor:</strong> {assessmentData.assessment.requestor?.username}
                    </p>
                  </div>
                  <div className="col-md-6">
                    <p className="mb-1">
                      <strong>End Date:</strong> {assessmentData.assessment.endDate}
                    </p>
                    <p className="mb-1">
                      <strong>Status:</strong>{' '}
                      <span className={`badge ${assessmentData.assessment.status === 'COMPLETED' ? 'bg-success' : 'bg-warning'}`}>
                        {assessmentData.assessment.status}
                      </span>
                    </p>
                    <p className="mb-1">
                      <strong>Progress:</strong>{' '}
                      <span className="badge bg-primary">
                        {getProgressPercentage()}% Complete
                      </span>
                    </p>
                  </div>
                </div>
              </div>
            </div>

            {/* Progress Bar */}
            <div className="progress mb-3" style={{ height: '25px' }}>
              <div 
                className="progress-bar" 
                role="progressbar" 
                style={{ width: `${getProgressPercentage()}%` }}
                aria-valuenow={getProgressPercentage()} 
                aria-valuemin={0} 
                aria-valuemax={100}
              >
                {getProgressPercentage()}%
              </div>
            </div>

            {/* Tabs */}
            <ul className="nav nav-tabs mb-3">
              <li className="nav-item">
                <button 
                  className={`nav-link ${currentTab === 'assessment' ? 'active' : ''}`}
                  onClick={() => setCurrentTab('assessment')}
                >
                  Assessment ({requirementsWithResponses.length} Requirements)
                </button>
              </li>
              <li className="nav-item">
                <button 
                  className={`nav-link ${currentTab === 'risk-raising' ? 'active' : ''}`}
                  onClick={() => setCurrentTab('risk-raising')}
                >
                  Risk Raising
                  {Object.values(responses).filter(r => r.raiseRisk).length > 0 && (
                    <span className="badge bg-danger ms-2">
                      {Object.values(responses).filter(r => r.raiseRisk).length}
                    </span>
                  )}
                </button>
              </li>
            </ul>

            {/* Filter for Review Mode */}
            {mode === 'review' && currentTab === 'assessment' && (
              <div className="mb-3">
                <div className="btn-group" role="group">
                  <button 
                    className={`btn btn-sm ${filterStatus === 'all' ? 'btn-primary' : 'btn-outline-primary'}`}
                    onClick={() => setFilterStatus('all')}
                  >
                    All ({requirementsWithResponses.length})
                  </button>
                  <button 
                    className={`btn btn-sm ${filterStatus === 'compliant' ? 'btn-success' : 'btn-outline-success'}`}
                    onClick={() => setFilterStatus('compliant')}
                  >
                    Compliant ({requirementsWithResponses.filter(r => responses[r.requirement.id]?.answerType === 'YES').length})
                  </button>
                  <button 
                    className={`btn btn-sm ${filterStatus === 'non-compliant' ? 'btn-danger' : 'btn-outline-danger'}`}
                    onClick={() => setFilterStatus('non-compliant')}
                  >
                    Non-Compliant ({requirementsWithResponses.filter(r => responses[r.requirement.id]?.answerType === 'NO').length})
                  </button>
                  <button 
                    className={`btn btn-sm ${filterStatus === 'not-applicable' ? 'btn-warning' : 'btn-outline-warning'}`}
                    onClick={() => setFilterStatus('not-applicable')}
                  >
                    N/A ({requirementsWithResponses.filter(r => responses[r.requirement.id]?.answerType === 'N_A').length})
                  </button>
                </div>
              </div>
            )}

            {/* Assessment Tab */}
            {currentTab === 'assessment' && (
              <div className="list-group">
                {getFilteredRequirements().map((item, index) => (
                  <div key={item.requirement.id} className="list-group-item">
                    <div className="d-flex justify-content-between align-items-start mb-2">
                      <h6 className="mb-1">
                        {index + 1}. {item.requirement.shortreq}
                        {responses[item.requirement.id]?.answerType && (
                          <span className={`badge ${getAnswerBadgeClass(responses[item.requirement.id].answerType)} ms-2`}>
                            {responses[item.requirement.id].answerType.replace('_', '/')}
                          </span>
                        )}
                      </h6>
                    </div>
                    
                    {item.requirement.details && (
                      <p className="text-muted small mb-2">{item.requirement.details}</p>
                    )}
                    
                    {item.requirement.norm && (
                      <p className="text-muted small mb-2">
                        <strong>Norm:</strong> {item.requirement.norm} 
                        {item.requirement.chapter && ` - Chapter: ${item.requirement.chapter}`}
                      </p>
                    )}

                    <div className="row">
                      <div className="col-md-6">
                        <label className="form-label small">Compliance Status</label>
                        <div className="btn-group w-100" role="group">
                          <input
                            type="radio"
                            className="btn-check"
                            name={`answer-${item.requirement.id}`}
                            id={`yes-${item.requirement.id}`}
                            checked={responses[item.requirement.id]?.answerType === 'YES'}
                            onChange={() => handleAnswerChange(item.requirement.id, 'YES')}
                            disabled={isReadOnly}
                          />
                          <label className="btn btn-outline-success btn-sm" htmlFor={`yes-${item.requirement.id}`}>
                            Compliant
                          </label>
                          
                          <input
                            type="radio"
                            className="btn-check"
                            name={`answer-${item.requirement.id}`}
                            id={`no-${item.requirement.id}`}
                            checked={responses[item.requirement.id]?.answerType === 'NO'}
                            onChange={() => handleAnswerChange(item.requirement.id, 'NO')}
                            disabled={isReadOnly}
                          />
                          <label className="btn btn-outline-danger btn-sm" htmlFor={`no-${item.requirement.id}`}>
                            Non-Compliant
                          </label>
                          
                          <input
                            type="radio"
                            className="btn-check"
                            name={`answer-${item.requirement.id}`}
                            id={`na-${item.requirement.id}`}
                            checked={responses[item.requirement.id]?.answerType === 'N_A'}
                            onChange={() => handleAnswerChange(item.requirement.id, 'N_A')}
                            disabled={isReadOnly}
                          />
                          <label className="btn btn-outline-warning btn-sm" htmlFor={`na-${item.requirement.id}`}>
                            N/A
                          </label>
                        </div>
                      </div>
                      
                      <div className="col-md-6">
                        <label className="form-label small">Comments</label>
                        <textarea
                          className="form-control form-control-sm"
                          rows={2}
                          placeholder="Add comments..."
                          value={responses[item.requirement.id]?.comment || ''}
                          onChange={(e) => handleCommentChange(item.requirement.id, e.target.value)}
                          disabled={isReadOnly}
                        />
                      </div>
                    </div>

                    {/* Risk Raising Option for Non-Compliant Items */}
                    {(responses[item.requirement.id]?.answerType === 'NO' || 
                      responses[item.requirement.id]?.answerType === 'N_A') && 
                      !isReadOnly && (
                      <div className="mt-2">
                        <div className="form-check">
                          <input
                            className="form-check-input"
                            type="checkbox"
                            id={`raise-risk-${item.requirement.id}`}
                            checked={responses[item.requirement.id]?.raiseRisk || false}
                            onChange={() => handleRiskToggle(item.requirement.id)}
                          />
                          <label className="form-check-label text-danger" htmlFor={`raise-risk-${item.requirement.id}`}>
                            Raise risk for this non-compliance
                          </label>
                        </div>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}

            {/* Risk Raising Tab */}
            {currentTab === 'risk-raising' && (
              <div>
                {Object.entries(responses)
                  .filter(([_, data]) => data.raiseRisk)
                  .map(([requirementId, data]) => {
                    const requirement = requirementsWithResponses.find(r => r.requirement.id === parseInt(requirementId))?.requirement;
                    if (!requirement) return null;
                    
                    return (
                      <div key={requirementId} className="card mb-3">
                        <div className="card-body">
                          <h6 className="card-title">{requirement.shortreq}</h6>
                          <div className="row">
                            <div className="col-md-12 mb-2">
                              <label className="form-label small">Risk Description</label>
                              <textarea
                                className="form-control"
                                rows={3}
                                placeholder="Describe the risk..."
                                value={data.riskDescription || ''}
                                onChange={(e) => handleRiskFieldChange(parseInt(requirementId), 'riskDescription', e.target.value)}
                                disabled={isReadOnly}
                              />
                            </div>
                            <div className="col-md-6">
                              <label className="form-label small">Likelihood (1-5)</label>
                              <input
                                type="number"
                                className="form-control"
                                min="1"
                                max="5"
                                value={data.riskLikelihood || 3}
                                onChange={(e) => handleRiskFieldChange(parseInt(requirementId), 'riskLikelihood', parseInt(e.target.value))}
                                disabled={isReadOnly}
                              />
                            </div>
                            <div className="col-md-6">
                              <label className="form-label small">Impact (1-5)</label>
                              <input
                                type="number"
                                className="form-control"
                                min="1"
                                max="5"
                                value={data.riskImpact || 3}
                                onChange={(e) => handleRiskFieldChange(parseInt(requirementId), 'riskImpact', parseInt(e.target.value))}
                                disabled={isReadOnly}
                              />
                            </div>
                          </div>
                          {!isReadOnly && (
                            <button 
                              className="btn btn-sm btn-outline-danger mt-2"
                              onClick={() => handleRiskToggle(parseInt(requirementId))}
                            >
                              Remove Risk
                            </button>
                          )}
                        </div>
                      </div>
                    );
                  })}
                
                {Object.values(responses).filter(r => r.raiseRisk).length === 0 && (
                  <div className="alert alert-info">
                    No risks have been marked for raising. Switch to the Assessment tab and mark non-compliant items to raise risks.
                  </div>
                )}
              </div>
            )}

            {/* Save Message */}
            {saveMessage && (
              <div className={`alert mt-3 ${saveMessage.includes('success') ? 'alert-success' : 'alert-danger'}`}>
                {saveMessage}
              </div>
            )}

            {/* Error Display */}
            {error && (
              <div className="alert alert-danger mt-3" role="alert">
                {error}
              </div>
            )}
          </div>

          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Close
            </button>
            {!isReadOnly && (
              <>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={saveProgress}
                  disabled={saving}
                >
                  {saving ? 'Saving...' : 'Save Progress'}
                </button>
                <button
                  type="button"
                  className="btn btn-success"
                  onClick={submitAssessment}
                  disabled={submitting || getProgressPercentage() < 100}
                >
                  {submitting ? 'Submitting...' : 'Complete Assessment'}
                </button>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default AssessmentPerformance;