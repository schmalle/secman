import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

interface TranslationConfig {
  id?: number;
  apiKey: string;
  baseUrl: string;
  modelName: string;
  maxTokens: number;
  temperature: number;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

const TranslationConfigManagement: React.FC = () => {
  const [configs, setConfigs] = useState<TranslationConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingConfig, setEditingConfig] = useState<TranslationConfig | null>(null);
  const [testing, setTesting] = useState<number | null>(null);
  const [formData, setFormData] = useState<TranslationConfig>({
    apiKey: '',
    baseUrl: 'https://openrouter.ai/api/v1',
    modelName: 'anthropic/claude-3-haiku',
    maxTokens: 4000,
    temperature: 0.3,
    isActive: true
  });

  // Check if user is authenticated
  const [currentUser, setCurrentUser] = useState<any>(null);

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
      fetchConfigs();
    }
  }, [currentUser]);

  const fetchConfigs = async () => {
    try {
      const response = await authenticatedGet('/api/translation-config');
      if (response.ok) {
        const data = await response.json();
        setConfigs(data);
      } else {
        setError(`Failed to fetch translation configs: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    try {
      if (editingConfig) {
        await authenticatedPut(`/api/translation-config/${editingConfig.id}`, formData);
      } else {
        await authenticatedPost('/api/translation-config', formData);
      }

      await fetchConfigs();
      resetForm();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleEdit = (config: TranslationConfig) => {
    setEditingConfig(config);
    // Clear the API key field when editing so user can enter a new one
    setFormData({ ...config, apiKey: '' });
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this translation configuration?')) {
      return;
    }

    try {
      await authenticatedDelete(`/api/translation-config/${id}`);
      await fetchConfigs();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleTest = async (id: number) => {
    const testText = prompt('Enter text to test translation (optional):') || 'This is a test message for translation configuration verification.';

    setTesting(id);
    try {
      await authenticatedPost(`/api/translation-config/${id}/test`, { testText });
      setError('Translation test successful!');
      setTimeout(() => setError(null), 3000);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to test translation configuration');
    } finally {
      setTesting(null);
    }
  };

  const resetForm = () => {
    setFormData({
      apiKey: '',
      baseUrl: 'https://openrouter.ai/api/v1',
      modelName: 'anthropic/claude-3-haiku',
      maxTokens: 4000,
      temperature: 0.3,
      isActive: true
    });
    setEditingConfig(null);
    setShowForm(false);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const { name, value, type } = e.target;
    setFormData(prev => ({ 
      ...prev, 
      [name]: type === 'checkbox' ? (e.target as HTMLInputElement).checked :
              type === 'number' ? parseFloat(value) || 0 : value
    }));
  };

  if (!currentUser) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-warning" role="alert">
          <h4 className="alert-heading">Authentication Required</h4>
          <p>You must be logged in to access Translation Configuration Management.</p>
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
            <h2>Translation Configuration Management</h2>
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
              {showForm ? 'Cancel' : 'Add New Configuration'}
            </button>
          </div>
        </div>
      </div>

      {showForm && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-body">
                <h5 className="card-title">{editingConfig ? 'Edit Translation Configuration' : 'Add New Translation Configuration'}</h5>
                <form onSubmit={handleSubmit}>
                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="apiKey" className="form-label">OpenRouter API Key *</label>
                        <input
                          type="password"
                          className="form-control"
                          id="apiKey"
                          name="apiKey"
                          value={formData.apiKey}
                          onChange={handleInputChange}
                          required
                          placeholder={editingConfig ? "Enter new API key" : "sk-or-..."}
                        />
                        <div className="form-text">Get your API key from <a href="https://openrouter.ai/keys" target="_blank" rel="noopener noreferrer">OpenRouter</a></div>
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="baseUrl" className="form-label">Base URL *</label>
                        <input
                          type="url"
                          className="form-control"
                          id="baseUrl"
                          name="baseUrl"
                          value={formData.baseUrl}
                          onChange={handleInputChange}
                          required
                        />
                      </div>
                    </div>
                  </div>

                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="modelName" className="form-label">Model *</label>
                        <select
                          className="form-select"
                          id="modelName"
                          name="modelName"
                          value={formData.modelName}
                          onChange={handleInputChange}
                          required
                        >
                          <option value="anthropic/claude-3-haiku">Claude 3 Haiku (Fast & Affordable)</option>
                          <option value="anthropic/claude-3.5-sonnet">Claude 3.5 Sonnet (Balanced)</option>
                          <option value="anthropic/claude-3-opus">Claude 3 Opus (Most Capable)</option>
                          <option value="openai/gpt-4o-mini">GPT-4o Mini (Fast & Affordable)</option>
                          <option value="openai/gpt-4o">GPT-4o (Balanced)</option>
                          <option value="meta-llama/llama-3.1-8b-instruct">Llama 3.1 8B (Open Source)</option>
                          <option value="meta-llama/llama-3.1-70b-instruct">Llama 3.1 70B (Open Source)</option>
                        </select>
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="maxTokens" className="form-label">Max Tokens</label>
                        <input
                          type="number"
                          className="form-control"
                          id="maxTokens"
                          name="maxTokens"
                          value={formData.maxTokens}
                          onChange={handleInputChange}
                          min="100"
                          max="8000"
                        />
                      </div>
                    </div>
                  </div>

                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="temperature" className="form-label">Temperature</label>
                        <input
                          type="number"
                          className="form-control"
                          id="temperature"
                          name="temperature"
                          value={formData.temperature}
                          onChange={handleInputChange}
                          min="0"
                          max="1"
                          step="0.1"
                        />
                        <div className="form-text">Lower values make output more focused and deterministic</div>
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3 form-check">
                        <input
                          type="checkbox"
                          className="form-check-input"
                          id="isActive"
                          name="isActive"
                          checked={formData.isActive}
                          onChange={handleInputChange}
                        />
                        <label className="form-check-label" htmlFor="isActive">
                          Set as Active Configuration
                        </label>
                      </div>
                    </div>
                  </div>

                  <div className="d-flex justify-content-end">
                    <button type="submit" className="btn btn-success me-2">
                      {editingConfig ? 'Update' : 'Save'}
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
              <h5 className="card-title">Translation Configurations ({configs.length})</h5>
              {configs.length === 0 ? (
                <p className="text-muted">No translation configurations found. Click "Add New Configuration" to create one.</p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped table-hover">
                    <thead>
                      <tr>
                        <th>Status</th>
                        <th>Model</th>
                        <th>Base URL</th>
                        <th>Max Tokens</th>
                        <th>Temperature</th>
                        <th>Updated</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {configs.map((config) => (
                        <tr key={config.id}>
                          <td>
                            <span className={`badge ${config.isActive ? 'bg-success' : 'bg-secondary'}`}>
                              {config.isActive ? 'Active' : 'Inactive'}
                            </span>
                          </td>
                          <td>{config.modelName}</td>
                          <td>{config.baseUrl}</td>
                          <td>{config.maxTokens}</td>
                          <td>{config.temperature}</td>
                          <td>
                            {config.updatedAt ? new Date(config.updatedAt).toLocaleDateString() : '-'}
                          </td>
                          <td>
                            <div className="btn-group-vertical btn-group-sm" role="group">
                              <button 
                                onClick={() => handleEdit(config)} 
                                className="btn btn-outline-primary mb-1"
                              >
                                Edit
                              </button>
                              <button 
                                onClick={() => config.id && handleTest(config.id)} 
                                className="btn btn-outline-info mb-1"
                                disabled={testing === config.id}
                              >
                                {testing === config.id ? 'Testing...' : 'Test'}
                              </button>
                              <button 
                                onClick={() => config.id && handleDelete(config.id)} 
                                className="btn btn-outline-danger"
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

      {error && (
        <div className="row mt-4">
          <div className="col-12">
            <div className={`alert ${error.includes('successful') ? 'alert-success' : 'alert-danger'}`} role="alert">
              {error}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default TranslationConfigManagement;