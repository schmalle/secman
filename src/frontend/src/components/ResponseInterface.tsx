import React, { useState, useEffect } from 'react';
import RequirementFileUpload from './RequirementFileUpload';

interface Requirement {
  id: number;
  shortreq: string;
  details: string;
}

interface RiskAssessment {
  id: number;
  asset: {
    id: number;
    name: string;
    type: string;
  };
  endDate: string;
  status: string;
  requestor: {
    username: string;
  };
  notes?: string;
}

interface ExistingResponse {
  id: number;
  requirement: {
    id: number;
  };
  answer: 'YES' | 'NO' | 'N_A';
  comment?: string;
}

interface AssessmentData {
  riskAssessment: RiskAssessment;
  requirements: Requirement[];
  existingResponses: ExistingResponse[];
  respondentEmail: string;
  expiresAt: string;
}

interface ResponseFormData {
  [requirementId: number]: {
    answer: 'YES' | 'NO' | 'N_A' | '';
    comment: string;
  };
}

interface ResponseInterfaceProps {
  token: string;
}

const ResponseInterface: React.FC<ResponseInterfaceProps> = ({ token }) => {
  const [assessmentData, setAssessmentData] = useState<AssessmentData | null>(null);
  const [responses, setResponses] = useState<ResponseFormData>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [autoSaveTimeout, setAutoSaveTimeout] = useState<NodeJS.Timeout | null>(null);
  const [manualSaving, setManualSaving] = useState(false);
  const [saveMessage, setSaveMessage] = useState('');

  useEffect(() => {
    fetchAssessmentData();
  }, [token]);

  useEffect(() => {
    // Auto-save responses after 2 seconds of inactivity
    if (autoSaveTimeout) {
      clearTimeout(autoSaveTimeout);
    }
    
    const timeout = setTimeout(() => {
      saveAllResponses();
    }, 2000);
    
    setAutoSaveTimeout(timeout);
    
    return () => {
      if (timeout) clearTimeout(timeout);
    };
  }, [responses]);

  const fetchAssessmentData = async () => {
    try {
      const response = await fetch(`/api/responses/assessment/${token}`);
      if (!response.ok) {
        if (response.status === 401) {
          throw new Error('Invalid or expired access link');
        }
        throw new Error('Failed to load assessment data');
      }
      
      const data: AssessmentData = await response.json();
      setAssessmentData(data);
      
      // Initialize responses with existing data
      const initialResponses: ResponseFormData = {};
      data.requirements.forEach(req => {
        const existingResponse = data.existingResponses.find(resp => resp.requirement.id === req.id);
        initialResponses[req.id] = {
          answer: existingResponse?.answer || '',
          comment: existingResponse?.comment || ''
        };
      });
      setResponses(initialResponses);
      
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const saveAllResponses = async () => {
    if (!assessmentData || saving) return;
    
    setSaving(true);
    
    try {
      for (const [requirementId, responseData] of Object.entries(responses)) {
        if (responseData.answer) {
          await fetch(`/api/responses/${token}/save`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
            },
            body: JSON.stringify({
              requirementId: parseInt(requirementId),
              answer: responseData.answer,
              comment: responseData.comment
            }),
          });
        }
      }
    } catch (err) {
      console.error('Auto-save failed:', err);
    } finally {
      setSaving(false);
    }
  };

  const handleAnswerChange = (requirementId: number, answer: 'YES' | 'NO' | 'N_A') => {
    setResponses(prev => ({
      ...prev,
      [requirementId]: {
        ...prev[requirementId],
        answer
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

  const handleManualSave = async () => {
    setManualSaving(true);
    setSaveMessage('');
    
    try {
      // Check if there are any responses to save
      const responsesToSave = Object.entries(responses).filter(([_, responseData]) => responseData.answer);
      console.log('Responses to save:', responsesToSave);
      
      if (responsesToSave.length === 0) {
        setSaveMessage('No responses to save. Please answer at least one requirement.');
        setTimeout(() => setSaveMessage(''), 3000);
        return;
      }
      
      // Save each response individually with better error handling
      let savedCount = 0;
      for (const [requirementId, responseData] of responsesToSave) {
        console.log(`Saving requirement ${requirementId}:`, responseData);
        
        const response = await fetch(`/api/responses/${token}/save`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            requirementId: parseInt(requirementId),
            answer: responseData.answer,
            comment: responseData.comment
          }),
        });
        
        if (!response.ok) {
          const errorText = await response.text();
          console.error(`Failed to save requirement ${requirementId}:`, response.status, errorText);
          throw new Error(`Failed to save requirement ${requirementId}: ${response.status} ${errorText}`);
        }
        
        const result = await response.json();
        console.log(`Successfully saved requirement ${requirementId}:`, result);
        savedCount++;
      }
      
      setSaveMessage(`Progress saved successfully! (${savedCount} response${savedCount !== 1 ? 's' : ''} saved)`);
      
      // Clear success message after 3 seconds
      setTimeout(() => {
        setSaveMessage('');
      }, 3000);
    } catch (err) {
      setSaveMessage(`Failed to save progress: ${err instanceof Error ? err.message : 'Unknown error'}`);
      console.error('Manual save failed:', err);
    } finally {
      setManualSaving(false);
    }
  };

  const submitAssessment = async () => {
    if (!assessmentData) return;
    
    // Check if all requirements have answers
    const unansweredRequirements = assessmentData.requirements.filter(req => 
      !responses[req.id]?.answer
    );
    
    if (unansweredRequirements.length > 0) {
      setError(`Please answer all requirements before submitting. Missing ${unansweredRequirements.length} answer(s).`);
      return;
    }
    
    setSubmitting(true);
    setError(null);
    
    try {
      // Save all responses first
      await saveAllResponses();
      
      // Submit assessment
      const response = await fetch(`/api/responses/${token}/submit`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
      });
      
      if (!response.ok) {
        throw new Error('Failed to submit assessment');
      }
      
      setSubmitted(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to submit assessment');
    } finally {
      setSubmitting(false);
    }
  };

  const getProgressPercentage = () => {
    if (!assessmentData) return 0;
    const answeredCount = assessmentData.requirements.filter(req => responses[req.id]?.answer).length;
    return Math.round((answeredCount / assessmentData.requirements.length) * 100);
  };

  const getAnswerBadgeClass = (answer: string) => {
    switch (answer) {
      case 'YES': return 'bg-success';
      case 'NO': return 'bg-danger';
      case 'N_A': return 'bg-warning text-dark';
      default: return 'bg-secondary';
    }
  };

  if (loading) {
    return (
      <div className="d-flex justify-content-center py-5">
        <div className="text-center">
          <div className="spinner-border text-primary" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
          <p className="mt-3">Loading assessment...</p>
        </div>
      </div>
    );
  }

  if (error && !assessmentData) {
    return (
      <div className="alert alert-danger" role="alert">
        <h4 className="alert-heading">Error</h4>
        <p>{error}</p>
      </div>
    );
  }

  if (submitted) {
    return (
      <div className="alert alert-success" role="alert">
        <h4 className="alert-heading">✓ Assessment Submitted Successfully</h4>
        <p>Thank you for completing the risk assessment. Your responses have been recorded and the requestor has been notified.</p>
        <hr />
        <p className="mb-0">You can now close this page.</p>
      </div>
    );
  }

  if (!assessmentData) {
    return (
      <div className="alert alert-warning" role="alert">
        <h4 className="alert-heading">Assessment Not Found</h4>
        <p>The requested assessment could not be found or has expired.</p>
      </div>
    );
  }

  return (
    <div>
      <h1 className="h3 mb-4">Risk Assessment Response</h1>
      
      {/* Assessment Info */}
      <div className="card mb-4">
        <div className="card-body">
          <div className="row">
            <div className="col-md-6">
              <p><strong>Asset:</strong> {assessmentData.riskAssessment.asset.name}</p>
              <p><strong>Type:</strong> {assessmentData.riskAssessment.asset.type}</p>
              <p><strong>Requestor:</strong> {assessmentData.riskAssessment.requestor.username}</p>
            </div>
            <div className="col-md-6">
              <p><strong>Respondent:</strong> {assessmentData.respondentEmail}</p>
              <p><strong>Deadline:</strong> {new Date(assessmentData.expiresAt).toLocaleDateString()}</p>
              <p><strong>Status:</strong> 
                <span className="badge bg-warning ms-2">{assessmentData.riskAssessment.status}</span>
              </p>
            </div>
          </div>
          {assessmentData.riskAssessment.notes && (
            <div className="mt-3">
              <p><strong>Notes:</strong> {assessmentData.riskAssessment.notes}</p>
            </div>
          )}
        </div>
      </div>

      {/* Progress */}
      <div className="card mb-4">
        <div className="card-body">
          <div className="d-flex justify-content-between align-items-center mb-2">
            <h5 className="mb-0">Progress</h5>
            <div className="d-flex align-items-center">
              {saving && (
                <span className="badge bg-info me-2">
                  <i className="bi bi-cloud-arrow-up"></i> Auto-saving...
                </span>
              )}
              <span className="badge bg-primary">
                {assessmentData.requirements.filter(req => responses[req.id]?.answer).length} of {assessmentData.requirements.length} complete
              </span>
            </div>
          </div>
          <div className="progress">
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
        </div>
      </div>

      {/* Requirements Assessment */}
      <div className="card mb-4">
        <div className="card-header">
          <h5 className="mb-0">Requirements Assessment</h5>
          <small className="text-muted">
            Please evaluate each requirement and provide your assessment. All fields are required.
          </small>
        </div>
        <div className="card-body">
          {assessmentData.requirements.length === 0 ? (
            <div className="alert alert-warning" role="alert">
              <h6 className="alert-heading">No Requirements Found</h6>
              <p className="mb-2">No requirements are associated with the selected use cases for this assessment.</p>
              <hr />
              <p className="mb-0">Please contact the administrator to configure requirements for this assessment.</p>
            </div>
          ) : (
            <div className="list-group list-group-flush">
              {assessmentData.requirements.map((requirement, index) => (
                <div key={requirement.id} className="list-group-item border-0 border-bottom">
                  <div className="d-flex justify-content-between align-items-start mb-2">
                    <h6 className="mb-2 fw-bold">
                      {index + 1}. {requirement.shortreq}
                      {responses[requirement.id]?.answer && (
                        <span className={`badge ${getAnswerBadgeClass(responses[requirement.id].answer)} ms-2`}>
                          {responses[requirement.id].answer.replace('_', '/')}
                        </span>
                      )}
                    </h6>
                  </div>
                  
                  {requirement.details && (
                    <p className="text-muted mb-3 small">{requirement.details}</p>
                  )}
                  
                  <div className="row">
                    <div className="col-md-6 mb-3">
                      <label className="form-label small">Your Assessment *</label>
                      <div className="btn-group w-100" role="group">
                        <input
                          type="radio"
                          className="btn-check"
                          name={`answer-${requirement.id}`}
                          id={`yes-${requirement.id}`}
                          checked={responses[requirement.id]?.answer === 'YES'}
                          onChange={() => handleAnswerChange(requirement.id, 'YES')}
                        />
                        <label className="btn btn-outline-success btn-sm" htmlFor={`yes-${requirement.id}`}>
                          Yes
                        </label>
                        
                        <input
                          type="radio"
                          className="btn-check"
                          name={`answer-${requirement.id}`}
                          id={`no-${requirement.id}`}
                          checked={responses[requirement.id]?.answer === 'NO'}
                          onChange={() => handleAnswerChange(requirement.id, 'NO')}
                        />
                        <label className="btn btn-outline-danger btn-sm" htmlFor={`no-${requirement.id}`}>
                          No
                        </label>
                        
                        <input
                          type="radio"
                          className="btn-check"
                          name={`answer-${requirement.id}`}
                          id={`na-${requirement.id}`}
                          checked={responses[requirement.id]?.answer === 'N_A'}
                          onChange={() => handleAnswerChange(requirement.id, 'N_A')}
                        />
                        <label className="btn btn-outline-warning btn-sm" htmlFor={`na-${requirement.id}`}>
                          N/A
                        </label>
                      </div>
                    </div>
                    
                    <div className="col-md-6 mb-3">
                      <label htmlFor={`comment-${requirement.id}`} className="form-label small">
                        Comments
                      </label>
                      <textarea
                        className="form-control form-control-sm"
                        id={`comment-${requirement.id}`}
                        rows={2}
                        placeholder="Optional comments..."
                        value={responses[requirement.id]?.comment || ''}
                        onChange={(e) => handleCommentChange(requirement.id, e.target.value)}
                      />
                    </div>
                  </div>
                  
                  {/* File Upload Section */}
                  <RequirementFileUpload
                    riskAssessmentId={assessmentData.riskAssessment.id}
                    requirementId={requirement.id}
                    requirementTitle={requirement.shortreq}
                  />
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Error Display */}
      {error && (
        <div className="alert alert-danger mb-4" role="alert">
          {error}
        </div>
      )}

      {/* Submit Actions */}
      <div className="card">
        <div className="card-body">
          <div className="d-flex justify-content-between align-items-center">
            <div>
              <h5 className="mb-1">Assessment Actions</h5>
              <p className="text-muted mb-0 small">
                {assessmentData.requirements.length === 0 
                  ? 'No requirements to assess'
                  : `${assessmentData.requirements.filter(req => responses[req.id]?.answer).length} of ${assessmentData.requirements.length} requirements completed`
                }
              </p>
            </div>
            <div className="d-flex gap-2">
              <button
                className="btn btn-outline-primary"
                onClick={handleManualSave}
                disabled={manualSaving || assessmentData.requirements.length === 0}
              >
                {manualSaving ? (
                  <>
                    <span className="spinner-border spinner-border-sm me-2" role="status"></span>
                    Saving...
                  </>
                ) : (
                  <>
                    <i className="bi bi-save me-2"></i>
                    Save Progress
                  </>
                )}
              </button>
              <button
                className="btn btn-success"
                onClick={submitAssessment}
                disabled={submitting || (assessmentData.requirements.length > 0 && getProgressPercentage() < 100) || assessmentData.requirements.length === 0}
              >
                {submitting ? (
                  <>
                    <span className="spinner-border spinner-border-sm me-2" role="status"></span>
                    Submitting...
                  </>
                ) : assessmentData.requirements.length === 0 ? (
                  'No Requirements to Submit'
                ) : (
                  <>
                    <i className="bi bi-check-circle me-2"></i>
                    Submit Assessment
                  </>
                )}
              </button>
            </div>
          </div>
          
          {/* Save Message */}
          {saveMessage && (
            <div className={`alert mt-3 mb-0 ${saveMessage.includes('success') ? 'alert-success' : 'alert-danger'}`}>
              <i className={`bi ${saveMessage.includes('success') ? 'bi-check-circle' : 'bi-exclamation-triangle'} me-2`}></i>
              {saveMessage}
            </div>
          )}
          
          {/* Progress Warning */}
          {assessmentData.requirements.length > 0 && getProgressPercentage() < 100 && !saveMessage && (
            <div className="alert alert-warning mt-3 mb-0">
              <i className="bi bi-exclamation-triangle"></i> 
              Please complete all requirements before submitting.
            </div>
          )}
          
          {/* No Requirements Info */}
          {assessmentData.requirements.length === 0 && (
            <div className="alert alert-info mt-3 mb-0">
              <i className="bi bi-info-circle"></i> 
              Please contact the administrator to configure requirements for this assessment.
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default ResponseInterface;
