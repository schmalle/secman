import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';
import { formatServerDate } from '../utils/dateUtils';

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

const openRouterModelOptions = [
  { value: '~anthropic/claude-opus-latest', label: 'Claude Opus Latest' },
  { value: 'anthropic/claude-opus-4.7', label: 'Claude Opus 4.7' },
  { value: 'anthropic/claude-opus-4.7-fast', label: 'Claude Opus 4.7 (Fast)' },
  { value: 'anthropic/claude-opus-4.6', label: 'Claude Opus 4.6' },
  { value: 'anthropic/claude-opus-4.6-fast', label: 'Claude Opus 4.6 (Fast)' },
  { value: '~anthropic/claude-sonnet-latest', label: 'Claude Sonnet Latest' },
  { value: 'anthropic/claude-sonnet-4.6', label: 'Claude Sonnet 4.6' },
  { value: '~anthropic/claude-haiku-latest', label: 'Claude Haiku Latest' },
  { value: 'anthropic/claude-haiku-4.5', label: 'Claude Haiku 4.5' },
  { value: 'deepseek/deepseek-v4-pro', label: 'DeepSeek V4 Pro' },
  { value: 'deepseek/deepseek-v4-flash', label: 'DeepSeek V4 Flash' },
  { value: 'deepseek/deepseek-v4-flash:free', label: 'DeepSeek V4 Flash (free)' },
  { value: 'openai/gpt-5.5', label: 'GPT-5.5' },
  { value: 'openai/gpt-5.5-pro', label: 'GPT-5.5 Pro' },
  { value: 'mistralai/mistral-medium-3-5', label: 'Mistral Medium 3.5' },
  { value: 'meta-llama/llama-3.3-70b-instruct', label: 'Llama 3.3 70B' },
];

const CUSTOM_MODEL_OPTION = '__custom__';

const TranslationConfigManagement: React.FC = () => {
  const [configs, setConfigs] = useState<TranslationConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingConfig, setEditingConfig] = useState<TranslationConfig | null>(null);
  const [testing, setTesting] = useState<number | null>(null);
  const [selectedModelOption, setSelectedModelOption] = useState('~anthropic/claude-haiku-latest');
  const [customModelName, setCustomModelName] = useState('');
  const [formData, setFormData] = useState<TranslationConfig>({
    apiKey: '',
    baseUrl: 'https://openrouter.ai/api/v1',
    modelName: '~anthropic/claude-haiku-latest',
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
    if (openRouterModelOptions.some((model) => model.value === config.modelName)) {
      setSelectedModelOption(config.modelName);
      setCustomModelName('');
    } else {
      setSelectedModelOption(CUSTOM_MODEL_OPTION);
      setCustomModelName(config.modelName);
    }
    // Clear the API key field when editing so user can enter a new one
    setFormData({ ...config, apiKey: '' });
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this LLM configuration?')) {
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
    const testText = prompt('Enter text to test translation (optional):') || 'This is a test message for LLM configuration verification.';

    setTesting(id);
    try {
      const response = await authenticatedPost(`/api/translation-config/${id}/test`, { testText });
      // Backend returns 200 with {success, message, details} even on logical
      // failure (so admins can see the upstream rejection). We must inspect
      // the body — a 200 here is NOT enough to claim success.
      const result = await response.json().catch(() => null);
      if (result && result.success === true) {
        setError(`Translation test successful: ${result.message ?? ''}`);
        setTimeout(() => setError(null), 5000);
      } else if (result) {
        const details = result.details ? ` — ${result.details}` : '';
        setError(`Translation test failed: ${result.message ?? 'unknown error'}${details}`);
      } else {
        setError('Translation test failed: empty response from server');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to test LLM configuration');
    } finally {
      setTesting(null);
    }
  };

  const resetForm = () => {
    setFormData({
      apiKey: '',
      baseUrl: 'https://openrouter.ai/api/v1',
      modelName: '~anthropic/claude-haiku-latest',
      maxTokens: 4000,
      temperature: 0.3,
      isActive: true
    });
    setEditingConfig(null);
    setSelectedModelOption('~anthropic/claude-haiku-latest');
    setCustomModelName('');
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

  const handleModelOptionChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value;
    setSelectedModelOption(value);
    if (value !== CUSTOM_MODEL_OPTION) {
      setCustomModelName('');
      setFormData(prev => ({ ...prev, modelName: value }));
    } else {
      setFormData(prev => ({ ...prev, modelName: customModelName }));
    }
  };

  const handleCustomModelChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setCustomModelName(value);
    if (selectedModelOption === CUSTOM_MODEL_OPTION) {
      setFormData(prev => ({ ...prev, modelName: value }));
    }
  };

  if (!currentUser) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-warning" role="alert">
          <h4 className="alert-heading">Authentication Required</h4>
          <p>You must be logged in to access LLM Configuration Management.</p>
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
            <h2>LLM Configuration Management</h2>
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
                <h5 className="card-title">{editingConfig ? 'Edit LLM Configuration' : 'Add New LLM Configuration'}</h5>
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
                        <label htmlFor="modelOption" className="form-label">Predefined Model *</label>
                        <select
                          className="form-select"
                          id="modelOption"
                          value={selectedModelOption}
                          onChange={handleModelOptionChange}
                          required
                        >
                          {openRouterModelOptions.map((model) => (
                            <option key={model.value} value={model.value}>
                              {model.label}
                            </option>
                          ))}
                          <option value={CUSTOM_MODEL_OPTION}>Custom model ID</option>
                        </select>
                        <div className="form-text">Choose a known OpenRouter model.</div>
                      </div>
                      <div className="mb-3">
                        <label htmlFor="modelName" className="form-label">Custom Model ID</label>
                        <input
                          type="text"
                          className="form-control"
                          id="modelName"
                          name="modelName"
                          value={customModelName}
                          onChange={handleCustomModelChange}
                          disabled={selectedModelOption !== CUSTOM_MODEL_OPTION}
                          required={selectedModelOption === CUSTOM_MODEL_OPTION}
                          placeholder="provider/model-name"
                        />
                        <div className="form-text">Select "Custom model ID" above to enter a model manually.</div>
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
              <h5 className="card-title">LLM Configurations ({configs.length})</h5>
              {configs.length === 0 ? (
                <p className="text-muted">No LLM configurations found. Click "Add New Configuration" to create one.</p>
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
                            {formatServerDate(config.updatedAt, undefined, '-')}
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
