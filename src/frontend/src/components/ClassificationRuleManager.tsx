import React, { useState, useEffect } from 'react';
import { csrfPost } from '../utils/csrf';

// Define an interface for the user data expected from the backend
interface User {
  id: number;
  username: string;
  email: string;
  roles: string[];
}

// Define a type for the global variable
declare global {
  interface Window {
    currentUser?: User | null;
  }
}

interface RuleCondition {
  type: 'IF' | 'AND' | 'OR' | 'NOT' | 'COMPARISON';
  field?: string;
  operator?: string;
  value?: any;
  conditions?: RuleCondition[];
}

interface ClassificationRule {
  id?: number;
  name: string;
  description?: string;
  ruleJson?: string;
  condition?: RuleCondition;
  classification: 'A' | 'B' | 'C';
  confidenceScore: number;
  active?: boolean;
  priorityOrder?: number;
}

interface TestResult {
  classification: string;
  classificationHash: string;
  confidenceScore: number;
  appliedRuleName?: string;
  evaluationLog: string[];
  timestamp: string;
}

const ClassificationRuleManager: React.FC = () => {
  const [isAdmin, setIsAdmin] = useState(false);
  const [isLoadingAuth, setIsLoadingAuth] = useState(true);
  const [rules, setRules] = useState<ClassificationRule[]>([]);
  const [selectedRule, setSelectedRule] = useState<ClassificationRule | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [isCreating, setIsCreating] = useState(false);
  const [testInput, setTestInput] = useState({
    title: '',
    description: '',
    demandType: 'CHANGE',
    priority: 'MEDIUM',
    businessJustification: '',
    assetType: '',
    assetOwner: ''
  });
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const availableFields = [
    'title', 'description', 'demandType', 'priority', 
    'businessJustification', 'assetType', 'assetOwner'
  ];

  const operators = [
    'EQUALS', 'NOT_EQUALS', 'CONTAINS', 'NOT_CONTAINS',
    'STARTS_WITH', 'ENDS_WITH', 'GREATER_THAN', 'LESS_THAN',
    'IS_NULL', 'IS_NOT_NULL'
  ];

  useEffect(() => {
    const checkAdminRole = () => {
      if (window.currentUser) {
        const isUserAdmin = window.currentUser.roles?.includes('ADMIN') ?? false;
        setIsAdmin(isUserAdmin);
        setIsLoadingAuth(false);
        
        if (isUserAdmin) {
          loadRules();
        }
      } else {
        // If currentUser isn't loaded yet, wait for the event
        const handleUserLoaded = () => {
          const isUserAdmin = window.currentUser?.roles?.includes('ADMIN') ?? false;
          setIsAdmin(isUserAdmin);
          setIsLoadingAuth(false);
          
          if (isUserAdmin) {
            loadRules();
          }
        };
        
        window.addEventListener('userLoaded', handleUserLoaded, { once: true });

        // Set a timeout in case the event never fires
        setTimeout(() => {
          if (isLoadingAuth) {
            setIsLoadingAuth(false);
            setIsAdmin(false);
          }
        }, 2000);
      }
    };

    checkAdminRole();
  }, [isLoadingAuth]);

  const loadRules = async () => {
    setLoading(true);
    try {
      const response = await fetch('/api/classification/rules', {
        credentials: 'include',
      });
      if (response.ok) {
        const data = await response.json();
        setRules(data);
      } else {
        setError('Failed to load rules');
      }
    } catch (err) {
      setError('Error loading rules: ' + err);
    } finally {
      setLoading(false);
    }
  };

  const createDefaultCondition = (): RuleCondition => ({
    type: 'COMPARISON',
    field: 'priority',
    operator: 'EQUALS',
    value: 'HIGH'
  });

  const createNewRule = () => {
    setSelectedRule({
      name: '',
      description: '',
      condition: createDefaultCondition(),
      classification: 'C',
      confidenceScore: 1.0,
      active: true
    });
    setIsCreating(true);
    setIsEditing(true);
  };

  const saveRule = async () => {
    if (!selectedRule) return;

    setLoading(true);
    try {
      const url = isCreating 
        ? '/api/classification/rules'
        : `/api/classification/rules/${selectedRule.id}`;
      
      const method = isCreating ? 'POST' : 'PUT';
      
      const response = await fetch(url, {
        method,
        headers: {
          'Content-Type': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify({
          name: selectedRule.name,
          description: selectedRule.description,
          condition: selectedRule.condition,
          classification: selectedRule.classification,
          confidenceScore: selectedRule.confidenceScore,
          active: selectedRule.active
        })
      });

      if (response.ok) {
        await loadRules();
        setSelectedRule(null);
        setIsEditing(false);
        setIsCreating(false);
        setError(null);
      } else {
        setError('Failed to save rule');
      }
    } catch (err) {
      setError('Error saving rule: ' + err);
    } finally {
      setLoading(false);
    }
  };

  const deleteRule = async (ruleId: number) => {
    if (!confirm('Are you sure you want to delete this rule?')) return;

    setLoading(true);
    try {
      const response = await fetch(`/api/classification/rules/${ruleId}`, {
        method: 'DELETE',
        credentials: 'include',
      });

      if (response.ok) {
        await loadRules();
        if (selectedRule?.id === ruleId) {
          setSelectedRule(null);
        }
      } else {
        setError('Failed to delete rule');
      }
    } catch (err) {
      setError('Error deleting rule: ' + err);
    } finally {
      setLoading(false);
    }
  };

  const testClassification = async () => {
    setLoading(true);
    try {
      const response = await fetch('/api/classification/test', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify({
          input: testInput,
          ruleId: selectedRule?.id
        })
      });

      if (response.ok) {
        const result = await response.json();
        setTestResult(result);
      } else {
        setError('Failed to test classification');
      }
    } catch (err) {
      setError('Error testing classification: ' + err);
    } finally {
      setLoading(false);
    }
  };

  const exportRules = async () => {
    try {
      const response = await fetch('/api/classification/rules/export', {
        credentials: 'include',
      });

      if (response.ok) {
        const blob = await response.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'classification-rules.json';
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(url);
        document.body.removeChild(a);
      } else {
        setError('Failed to export rules');
      }
    } catch (err) {
      setError('Error exporting rules: ' + err);
    }
  };

  const importRules = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;

    const formData = new FormData();
    formData.append('file', file);

    setLoading(true);
    try {
      await csrfPost('/api/classification/rules/import', formData);
      await loadRules();
      setError(null);
    } catch (err) {
      setError('Error importing rules: ' + err);
    } finally {
      setLoading(false);
    }
  };

  const renderConditionEditor = (condition: RuleCondition, onChange: (c: RuleCondition) => void) => {
    return (
      <div className="border rounded p-3 mb-2">
        <div className="mb-2">
          <label className="form-label">Condition Type</label>
          <select 
            className="form-select"
            value={condition.type}
            onChange={(e) => onChange({ ...condition, type: e.target.value as any })}
          >
            <option value="COMPARISON">Comparison</option>
            <option value="AND">AND</option>
            <option value="OR">OR</option>
            <option value="NOT">NOT</option>
            <option value="IF">IF</option>
          </select>
        </div>

        {condition.type === 'COMPARISON' && (
          <>
            <div className="mb-2">
              <label className="form-label">Field</label>
              <select 
                className="form-select"
                value={condition.field || ''}
                onChange={(e) => onChange({ ...condition, field: e.target.value })}
              >
                <option value="">Select field...</option>
                {availableFields.map(field => (
                  <option key={field} value={field}>{field}</option>
                ))}
              </select>
            </div>

            <div className="mb-2">
              <label className="form-label">Operator</label>
              <select 
                className="form-select"
                value={condition.operator || ''}
                onChange={(e) => onChange({ ...condition, operator: e.target.value })}
              >
                <option value="">Select operator...</option>
                {operators.map(op => (
                  <option key={op} value={op}>{op}</option>
                ))}
              </select>
            </div>

            {!['IS_NULL', 'IS_NOT_NULL'].includes(condition.operator || '') && (
              <div className="mb-2">
                <label className="form-label">Value</label>
                <input 
                  type="text"
                  className="form-control"
                  value={condition.value || ''}
                  onChange={(e) => onChange({ ...condition, value: e.target.value })}
                />
              </div>
            )}
          </>
        )}

        {['AND', 'OR', 'NOT', 'IF'].includes(condition.type) && (
          <div className="ms-3">
            <label className="form-label">Sub-conditions</label>
            {(condition.conditions || []).map((subCondition, index) => (
              <div key={index} className="mb-2">
                {renderConditionEditor(subCondition, (updated) => {
                  const newConditions = [...(condition.conditions || [])];
                  newConditions[index] = updated;
                  onChange({ ...condition, conditions: newConditions });
                })}
                <button 
                  className="btn btn-sm btn-danger mt-1"
                  onClick={() => {
                    const newConditions = (condition.conditions || []).filter((_, i) => i !== index);
                    onChange({ ...condition, conditions: newConditions });
                  }}
                >
                  Remove
                </button>
              </div>
            ))}
            <button 
              className="btn btn-sm btn-secondary"
              onClick={() => {
                const newConditions = [...(condition.conditions || []), createDefaultCondition()];
                onChange({ ...condition, conditions: newConditions });
              }}
            >
              Add Sub-condition
            </button>
          </div>
        )}
      </div>
    );
  };

  // Loading state
  if (isLoadingAuth) {
    return (
      <div className="alert alert-info">
        <i className="spinner-border spinner-border-sm me-2"></i>
        Checking permissions...
      </div>
    );
  }

  // Access denied state
  if (!isAdmin) {
    return (
      <div className="alert alert-danger">
        <i className="bi bi-exclamation-triangle-fill me-2"></i>
        Access Denied: You do not have permission to manage classification rules.
      </div>
    );
  }

  return (
    <div className="container-fluid">
      <div className="row">
        <div className="col-md-4">
          <div className="card">
            <div className="card-header d-flex justify-content-between align-items-center">
              <h5 className="mb-0">Classification Rules</h5>
              <div>
                <button 
                  className="btn btn-sm btn-primary me-1"
                  onClick={createNewRule}
                  disabled={loading}
                >
                  New Rule
                </button>
                <button 
                  className="btn btn-sm btn-secondary me-1"
                  onClick={exportRules}
                  disabled={loading}
                >
                  Export
                </button>
                <label className="btn btn-sm btn-secondary mb-0">
                  Import
                  <input 
                    type="file"
                    accept=".json"
                    onChange={importRules}
                    style={{ display: 'none' }}
                    disabled={loading}
                  />
                </label>
              </div>
            </div>
            <div className="card-body">
              {loading && <div className="text-center">Loading...</div>}
              {error && <div className="alert alert-danger">{error}</div>}
              
              <div className="list-group">
                {rules.map(rule => (
                  <button
                    key={rule.id}
                    className={`list-group-item list-group-item-action ${selectedRule?.id === rule.id ? 'active' : ''}`}
                    onClick={() => {
                      setSelectedRule(rule);
                      setIsEditing(false);
                      setIsCreating(false);
                    }}
                  >
                    <div className="d-flex justify-content-between align-items-center">
                      <div>
                        <strong>{rule.name}</strong>
                        <br />
                        <small>Classification: {rule.classification}</small>
                        {!rule.active && <span className="badge bg-secondary ms-2">Inactive</span>}
                      </div>
                      <span className="badge bg-primary">{rule.priorityOrder}</span>
                    </div>
                  </button>
                ))}
              </div>
            </div>
          </div>
        </div>

        <div className="col-md-8">
          {selectedRule && (
            <div className="card">
              <div className="card-header d-flex justify-content-between align-items-center">
                <h5 className="mb-0">
                  {isCreating ? 'Create Rule' : isEditing ? 'Edit Rule' : 'Rule Details'}
                </h5>
                <div>
                  {!isEditing && !isCreating && (
                    <>
                      <button 
                        className="btn btn-sm btn-primary me-1"
                        onClick={() => setIsEditing(true)}
                      >
                        Edit
                      </button>
                      <button 
                        className="btn btn-sm btn-danger"
                        onClick={() => deleteRule(selectedRule.id!)}
                      >
                        Delete
                      </button>
                    </>
                  )}
                  {(isEditing || isCreating) && (
                    <>
                      <button 
                        className="btn btn-sm btn-success me-1"
                        onClick={saveRule}
                        disabled={loading}
                      >
                        Save
                      </button>
                      <button 
                        className="btn btn-sm btn-secondary"
                        onClick={() => {
                          setIsEditing(false);
                          setIsCreating(false);
                          if (isCreating) {
                            setSelectedRule(null);
                          } else {
                            loadRules();
                          }
                        }}
                      >
                        Cancel
                      </button>
                    </>
                  )}
                </div>
              </div>
              <div className="card-body">
                <div className="mb-3">
                  <label className="form-label">Name</label>
                  <input 
                    type="text"
                    className="form-control"
                    value={selectedRule.name}
                    onChange={(e) => setSelectedRule({ ...selectedRule, name: e.target.value })}
                    disabled={!isEditing && !isCreating}
                  />
                </div>

                <div className="mb-3">
                  <label className="form-label">Description</label>
                  <textarea 
                    className="form-control"
                    value={selectedRule.description || ''}
                    onChange={(e) => setSelectedRule({ ...selectedRule, description: e.target.value })}
                    disabled={!isEditing && !isCreating}
                    rows={2}
                  />
                </div>

                <div className="mb-3">
                  <label className="form-label">Classification</label>
                  <select 
                    className="form-select"
                    value={selectedRule.classification}
                    onChange={(e) => setSelectedRule({ ...selectedRule, classification: e.target.value as 'A' | 'B' | 'C' })}
                    disabled={!isEditing && !isCreating}
                  >
                    <option value="A">A - High Risk</option>
                    <option value="B">B - Medium Risk</option>
                    <option value="C">C - Low Risk</option>
                  </select>
                </div>

                <div className="mb-3">
                  <label className="form-label">Confidence Score</label>
                  <input 
                    type="number"
                    className="form-control"
                    value={selectedRule.confidenceScore}
                    onChange={(e) => setSelectedRule({ ...selectedRule, confidenceScore: parseFloat(e.target.value) })}
                    disabled={!isEditing && !isCreating}
                    min="0"
                    max="1"
                    step="0.1"
                  />
                </div>

                {(isEditing || isCreating) && selectedRule.condition && (
                  <div className="mb-3">
                    <label className="form-label">Rule Condition</label>
                    {renderConditionEditor(selectedRule.condition, (updated) => 
                      setSelectedRule({ ...selectedRule, condition: updated })
                    )}
                  </div>
                )}

                {!isEditing && !isCreating && (
                  <div className="mb-3">
                    <label className="form-label">Rule JSON</label>
                    <pre className="bg-light p-2 rounded">
                      {JSON.stringify(selectedRule.condition || JSON.parse(selectedRule.ruleJson || '{}'), null, 2)}
                    </pre>
                  </div>
                )}
              </div>
            </div>
          )}

          <div className="card mt-3">
            <div className="card-header">
              <h5 className="mb-0">Test Classification</h5>
            </div>
            <div className="card-body">
              <div className="row">
                <div className="col-md-6">
                  <div className="mb-2">
                    <label className="form-label">Title</label>
                    <input 
                      type="text"
                      className="form-control"
                      value={testInput.title}
                      onChange={(e) => setTestInput({ ...testInput, title: e.target.value })}
                    />
                  </div>
                  <div className="mb-2">
                    <label className="form-label">Description</label>
                    <textarea 
                      className="form-control"
                      value={testInput.description}
                      onChange={(e) => setTestInput({ ...testInput, description: e.target.value })}
                      rows={2}
                    />
                  </div>
                  <div className="mb-2">
                    <label className="form-label">Demand Type</label>
                    <select 
                      className="form-select"
                      value={testInput.demandType}
                      onChange={(e) => setTestInput({ ...testInput, demandType: e.target.value })}
                    >
                      <option value="CHANGE">Change</option>
                      <option value="CREATE_NEW">Create New</option>
                    </select>
                  </div>
                  <div className="mb-2">
                    <label className="form-label">Priority</label>
                    <select 
                      className="form-select"
                      value={testInput.priority}
                      onChange={(e) => setTestInput({ ...testInput, priority: e.target.value })}
                    >
                      <option value="LOW">Low</option>
                      <option value="MEDIUM">Medium</option>
                      <option value="HIGH">High</option>
                      <option value="CRITICAL">Critical</option>
                    </select>
                  </div>
                </div>
                <div className="col-md-6">
                  <div className="mb-2">
                    <label className="form-label">Business Justification</label>
                    <textarea 
                      className="form-control"
                      value={testInput.businessJustification}
                      onChange={(e) => setTestInput({ ...testInput, businessJustification: e.target.value })}
                      rows={2}
                    />
                  </div>
                  <div className="mb-2">
                    <label className="form-label">Asset Type</label>
                    <input 
                      type="text"
                      className="form-control"
                      value={testInput.assetType}
                      onChange={(e) => setTestInput({ ...testInput, assetType: e.target.value })}
                    />
                  </div>
                  <div className="mb-2">
                    <label className="form-label">Asset Owner</label>
                    <input 
                      type="text"
                      className="form-control"
                      value={testInput.assetOwner}
                      onChange={(e) => setTestInput({ ...testInput, assetOwner: e.target.value })}
                    />
                  </div>
                </div>
              </div>
              
              <button 
                className="btn btn-primary mt-2"
                onClick={testClassification}
                disabled={loading}
              >
                Test Classification
              </button>

              {testResult && (
                <div className="mt-3">
                  <h6>Test Result:</h6>
                  <div className="alert alert-info">
                    <strong>Classification:</strong> {testResult.classification}<br />
                    <strong>Confidence:</strong> {(testResult.confidenceScore * 100).toFixed(0)}%<br />
                    <strong>Applied Rule:</strong> {testResult.appliedRuleName || 'Default'}<br />
                    <strong>Hash:</strong> <code>{testResult.classificationHash}</code>
                  </div>
                  <details>
                    <summary>Evaluation Log</summary>
                    <pre className="bg-light p-2 rounded mt-2">
                      {testResult.evaluationLog.join('\n')}
                    </pre>
                  </details>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ClassificationRuleManager;