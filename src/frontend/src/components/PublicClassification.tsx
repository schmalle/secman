import React, { useState } from 'react';

interface ClassificationInput {
  title: string;
  description?: string;
  demandType: string;
  priority: string;
  businessJustification?: string;
  assetType?: string;
  assetOwner?: string;
}

interface ClassificationResult {
  classification: string;
  classificationHash: string;
  confidenceScore: number;
  appliedRuleName?: string;
  evaluationLog: string[];
  timestamp: string;
}

const PublicClassification: React.FC = () => {
  const [input, setInput] = useState<ClassificationInput>({
    title: '',
    description: '',
    demandType: 'CHANGE',
    priority: 'MEDIUM',
    businessJustification: '',
    assetType: '',
    assetOwner: ''
  });

  const [result, setResult] = useState<ClassificationResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showEvaluationLog, setShowEvaluationLog] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    if (!input.title.trim()) {
      setError('Title is required');
      return;
    }

    setLoading(true);
    setError(null);
    setResult(null);

    try {
      const response = await fetch('/api/classification/public/classify', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(input)
      });

      if (response.ok) {
        const data = await response.json();
        setResult(data);
      } else {
        setError('Failed to classify. Please try again.');
      }
    } catch (err) {
      setError('An error occurred. Please try again later.');
    } finally {
      setLoading(false);
    }
  };

  const handleReset = () => {
    setInput({
      title: '',
      description: '',
      demandType: 'CHANGE',
      priority: 'MEDIUM',
      businessJustification: '',
      assetType: '',
      assetOwner: ''
    });
    setResult(null);
    setError(null);
    setShowEvaluationLog(false);
  };

  const getClassificationColor = (classification: string) => {
    switch (classification) {
      case 'A': return 'danger';
      case 'B': return 'warning';
      case 'C': return 'success';
      default: return 'secondary';
    }
  };

  const getClassificationDescription = (classification: string) => {
    switch (classification) {
      case 'A': return 'High Risk - Critical security requirements needed';
      case 'B': return 'Medium Risk - Standard security controls required';
      case 'C': return 'Low Risk - Basic security measures sufficient';
      default: return 'Unknown classification';
    }
  };

  return (
    <div className="container mt-4">
      <div className="row justify-content-center">
        <div className="col-lg-8">
          <div className="card shadow">
            <div className="card-header bg-primary text-white">
              <h3 className="mb-0">Demand Classification Tool</h3>
              <small>Public access - No authentication required</small>
            </div>
            
            <div className="card-body">
              <div className="alert alert-info mb-4">
                <strong>Instructions:</strong> Fill in the demand details below to receive an automated classification 
                based on configured security risk assessment rules. The classification will help determine the appropriate 
                security requirements for your demand.
              </div>

              <form onSubmit={handleSubmit}>
                <div className="row">
                  <div className="col-md-6 mb-3">
                    <label className="form-label">
                      Title <span className="text-danger">*</span>
                    </label>
                    <input
                      type="text"
                      className="form-control"
                      value={input.title}
                      onChange={(e) => setInput({ ...input, title: e.target.value })}
                      placeholder="Enter demand title"
                      required
                    />
                  </div>

                  <div className="col-md-6 mb-3">
                    <label className="form-label">Demand Type</label>
                    <select
                      className="form-select"
                      value={input.demandType}
                      onChange={(e) => setInput({ ...input, demandType: e.target.value })}
                    >
                      <option value="CHANGE">Change Existing Asset</option>
                      <option value="CREATE_NEW">Create New Asset</option>
                    </select>
                  </div>
                </div>

                <div className="mb-3">
                  <label className="form-label">Description</label>
                  <textarea
                    className="form-control"
                    rows={3}
                    value={input.description}
                    onChange={(e) => setInput({ ...input, description: e.target.value })}
                    placeholder="Provide a detailed description of the demand"
                  />
                </div>

                <div className="row">
                  <div className="col-md-4 mb-3">
                    <label className="form-label">Priority</label>
                    <select
                      className="form-select"
                      value={input.priority}
                      onChange={(e) => setInput({ ...input, priority: e.target.value })}
                    >
                      <option value="LOW">Low</option>
                      <option value="MEDIUM">Medium</option>
                      <option value="HIGH">High</option>
                      <option value="CRITICAL">Critical</option>
                    </select>
                  </div>

                  <div className="col-md-4 mb-3">
                    <label className="form-label">Asset Type</label>
                    <input
                      type="text"
                      className="form-control"
                      value={input.assetType}
                      onChange={(e) => setInput({ ...input, assetType: e.target.value })}
                      placeholder="e.g., Server, Application, Database"
                    />
                  </div>

                  <div className="col-md-4 mb-3">
                    <label className="form-label">Asset Owner</label>
                    <input
                      type="text"
                      className="form-control"
                      value={input.assetOwner}
                      onChange={(e) => setInput({ ...input, assetOwner: e.target.value })}
                      placeholder="Owner name or department"
                    />
                  </div>
                </div>

                <div className="mb-4">
                  <label className="form-label">Business Justification</label>
                  <textarea
                    className="form-control"
                    rows={3}
                    value={input.businessJustification}
                    onChange={(e) => setInput({ ...input, businessJustification: e.target.value })}
                    placeholder="Explain the business need and expected benefits"
                  />
                </div>

                {error && (
                  <div className="alert alert-danger mb-3">
                    {error}
                  </div>
                )}

                <div className="d-flex justify-content-between">
                  <button
                    type="submit"
                    className="btn btn-primary"
                    disabled={loading}
                  >
                    {loading ? (
                      <>
                        <span className="spinner-border spinner-border-sm me-2" />
                        Classifying...
                      </>
                    ) : (
                      'Classify Demand'
                    )}
                  </button>
                  
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={handleReset}
                  >
                    Reset Form
                  </button>
                </div>
              </form>
            </div>
          </div>

          {result && (
            <div className="card shadow mt-4">
              <div className="card-header bg-secondary text-white">
                <h4 className="mb-0">Classification Result</h4>
              </div>
              
              <div className="card-body">
                <div className="row">
                  <div className="col-md-6">
                    <div className="text-center mb-4">
                      <h1 className={`display-1 text-${getClassificationColor(result.classification)}`}>
                        {result.classification}
                      </h1>
                      <p className="lead">
                        {getClassificationDescription(result.classification)}
                      </p>
                    </div>
                  </div>
                  
                  <div className="col-md-6">
                    <dl className="row">
                      <dt className="col-sm-5">Confidence Score:</dt>
                      <dd className="col-sm-7">
                        <div className="progress" style={{ height: '25px' }}>
                          <div 
                            className="progress-bar"
                            role="progressbar"
                            style={{ width: `${result.confidenceScore * 100}%` }}
                          >
                            {(result.confidenceScore * 100).toFixed(0)}%
                          </div>
                        </div>
                      </dd>

                      <dt className="col-sm-5">Applied Rule:</dt>
                      <dd className="col-sm-7">{result.appliedRuleName || 'Default'}</dd>

                      <dt className="col-sm-5">Timestamp:</dt>
                      <dd className="col-sm-7">{new Date(result.timestamp).toLocaleString()}</dd>
                    </dl>
                  </div>
                </div>

                <div className="alert alert-light border mt-3">
                  <strong>Classification Hash:</strong>
                  <div className="font-monospace small text-break mt-1">
                    {result.classificationHash}
                  </div>
                  <small className="text-muted">
                    This unique hash can be used to verify and retrieve this classification result.
                  </small>
                </div>

                <div className="mt-3">
                  <button
                    className="btn btn-sm btn-outline-secondary"
                    onClick={() => setShowEvaluationLog(!showEvaluationLog)}
                  >
                    {showEvaluationLog ? 'Hide' : 'Show'} Evaluation Details
                  </button>
                  
                  {showEvaluationLog && (
                    <div className="mt-3">
                      <h6>Evaluation Log:</h6>
                      <pre className="bg-light p-3 rounded small">
                        {result.evaluationLog.join('\n')}
                      </pre>
                    </div>
                  )}
                </div>

                <div className="mt-4 p-3 bg-light rounded">
                  <h6>Next Steps:</h6>
                  <ul className="mb-0">
                    {result.classification === 'A' && (
                      <>
                        <li>Schedule a comprehensive security review</li>
                        <li>Prepare detailed threat modeling documentation</li>
                        <li>Engage security team for risk assessment</li>
                      </>
                    )}
                    {result.classification === 'B' && (
                      <>
                        <li>Review standard security requirements checklist</li>
                        <li>Document security controls implementation plan</li>
                        <li>Schedule security validation testing</li>
                      </>
                    )}
                    {result.classification === 'C' && (
                      <>
                        <li>Apply baseline security configuration</li>
                        <li>Document basic security measures</li>
                        <li>Proceed with standard approval process</li>
                      </>
                    )}
                  </ul>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default PublicClassification;