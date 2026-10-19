import React, { useState, useEffect, useRef } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';
import { formatServerDate } from '../utils/dateUtils';
import { buddyLabel } from '../services/aiBuddyService';
import { buddySetupLabel, buddySetupMissing, orderBuddyProviders, parseBuddySetupResult, type BuddySetupResult, type BuddyProviderOption } from '../services/aiBuddySetup';

interface TranslationConfig {
  id?: number;
  apiKey: string;
  baseUrl: string;
  modelName: string;
  maxTokens: number;
  temperature: number;
  isActive: boolean;
  aiBuddyEnabled: boolean;
  aiBuddySingleInstance: boolean;
  aiBuddyProviders: string;
  aiBuddyMaxCostPerJobUsd: number;
  aiBuddyDailyBudgetUsd: number;
  aiBuddyMaxInputPricePerMillion: number;
  aiBuddyMaxOutputPricePerMillion: number;
  aiBuddyMicrosoftEvidenceEnabled: boolean;
  aiBuddyCrowdStrikeEvidenceEnabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

// Concrete IDs checked against OpenRouter's catalogue on 2026-10-10.
const openRouterModelOptions = [
  { value: 'openai/gpt-6.1-sol', label: 'GPT-6.1 Sol (latest Sol)' },
  { value: 'openai/gpt-6.1-sol-pro', label: 'GPT-6.1 Sol Pro' },
  { value: 'anthropic/claude-sonnet-5.5', label: 'Claude Sonnet 5.5 (latest Sonnet)' },
  { value: 'openai/gpt-6-sol', label: 'GPT-6 Sol' },
  { value: 'anthropic/claude-sonnet-5', label: 'Claude Sonnet 5' },
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
  const [checkingSetup, setCheckingSetup] = useState(false);
  const [setupError, setSetupError] = useState('');
  const [setupResult, setSetupResult] = useState<BuddySetupResult | null>(null);
  const [providerOptions, setProviderOptions] = useState<BuddyProviderOption[]>([]);
  const [savedSetupChecks, setSavedSetupChecks] = useState<Record<number, BuddySetupResult>>({});
  const [setupFeedback, setSetupFeedback] = useState<{ configId?: number; kind: 'info' | 'success' | 'warning' | 'danger'; message: string } | null>(null);
  const setupRequest = useRef(0);
  const setupLinkHandled = useRef(false);
  const [selectedModelOption, setSelectedModelOption] = useState('openai/gpt-6.1-sol');
  const [customModelName, setCustomModelName] = useState('');
  const [formData, setFormData] = useState<TranslationConfig>({
    apiKey: '',
    baseUrl: 'https://openrouter.ai/api/v1',
    modelName: 'openai/gpt-6.1-sol',
    maxTokens: 4000,
    temperature: 0.3,
    isActive: true,
    aiBuddyEnabled: false,
    aiBuddySingleInstance: false,
    aiBuddyProviders: '',
    aiBuddyMaxCostPerJobUsd: 0.50,
    aiBuddyDailyBudgetUsd: 10.0,
    aiBuddyMaxInputPricePerMillion: 5.0,
    aiBuddyMaxOutputPricePerMillion: 20.0,
    aiBuddyMicrosoftEvidenceEnabled: true,
    aiBuddyCrowdStrikeEvidenceEnabled: false
  });

  useEffect(() => {
    setupRequest.current += 1;
    setCheckingSetup(false);
    setSetupResult(null);
    setSetupError('');
  }, [formData]);

  useEffect(() => {
    setProviderOptions([]);
  }, [showForm, editingConfig?.id, formData.modelName, formData.baseUrl, formData.maxTokens, formData.apiKey,
    formData.aiBuddyMaxInputPricePerMillion, formData.aiBuddyMaxOutputPricePerMillion]);

  useEffect(() => {
    if (setupLinkHandled.current || !configs.length || new URLSearchParams(window.location.search).get('setup') !== 'ai-buddy') return;
    setupLinkHandled.current = true;
    handleEdit(configs.find(config => config.isActive) || configs[0]);
    window.setTimeout(() => {
      const field = document.getElementById('aiBuddyProviders');
      field?.scrollIntoView({ block: 'center' });
      field?.focus();
    }, 0);
  }, [configs]);

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
        setSavedSetupChecks({});
        setSetupFeedback(null);
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
      const response = editingConfig
        ? await authenticatedPut(`/api/translation-config/${editingConfig.id}`, formData)
        : await authenticatedPost('/api/translation-config', formData);
      if (!response.ok) {
        const result = await response.json().catch(() => null);
        throw new Error(result?.error || 'Unable to save OpenRouter configuration');
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

  const handleSetupCheck = async (config: TranslationConfig, fromForm: boolean) => {
    const version = ++setupRequest.current;
    setCheckingSetup(true);
    setSetupError('');
    if (!fromForm) {
      setSetupFeedback({ configId: config.id, kind: 'info', message: `Checking AI buddy setup for ${config.modelName}…` });
      if (config.id) setSavedSetupChecks(previous => {
        const updated = { ...previous };
        delete updated[config.id!];
        return updated;
      });
    }
    try {
      const response = await authenticatedPost('/api/translation-config/ai-buddy/setup', {
        configId: config.id ?? null, settings: config
      });
      const result = await response.json().catch(() => null);
      if (version !== setupRequest.current) return;
      if (!response.ok) {
        throw new Error(response.status === 404
          ? 'The AI buddy setup endpoint is not available on the running backend yet. It will be available after the backend is rebuilt and restarted.'
          : result?.error || 'AI buddy setup could not be verified');
      }
      const checked = parseBuddySetupResult(result);
      if (fromForm) {
        setSetupResult(checked);
        setProviderOptions(orderBuddyProviders(checked.providers));
      } else if (config.id) {
        setSavedSetupChecks(previous => ({ ...previous, [config.id!]: checked }));
        setSetupFeedback({ configId: config.id, kind: checked.ready ? 'success' : 'warning',
          message: checked.ready
            ? `AI buddy setup check passed for ${config.modelName}. The configured provider routes meet the current requirements.`
            : `AI buddy setup check did not pass for ${config.modelName}. ${checked.reasons.map(buddyLabel).join('; ') || 'Review the configuration settings.'}` });
      }
    } catch (err) {
      if (version === setupRequest.current) {
        const message = err instanceof Error ? err.message : 'AI buddy setup could not be verified';
        if (fromForm) setSetupError(message);
        else setSetupFeedback({ configId: config.id, kind: 'danger', message: `AI buddy setup check failed for ${config.modelName}. ${message}` });
      }
    } finally {
      if (version === setupRequest.current) setCheckingSetup(false);
    }
  };

  useEffect(() => {
    if (!showForm || !(formData.apiKey.trim() || editingConfig?.apiKey) ||
      formData.baseUrl.replace(/\/+$/, '') !== 'https://openrouter.ai/api/v1' ||
      !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(formData.modelName.trim())) return;
    const timer = window.setTimeout(() => void handleSetupCheck(formData, true), 600);
    return () => {
      window.clearTimeout(timer);
      setupRequest.current += 1;
    };
  }, [showForm, formData, editingConfig?.apiKey]);

  const selectProvider = (tag: string, approved: boolean) => {
    const tags = formData.aiBuddyProviders.split(',').map(value => value.trim()).filter(Boolean);
    setFormData(previous => ({ ...previous, aiBuddyProviders: (approved
      ? [...new Set([...tags, tag])] : tags.filter(value => value !== tag)).join(', ') }));
  };

  const resetForm = () => {
    setFormData({
      apiKey: '',
      baseUrl: 'https://openrouter.ai/api/v1',
      modelName: 'openai/gpt-6.1-sol',
      maxTokens: 4000,
      temperature: 0.3,
      isActive: true,
    aiBuddyEnabled: false,
    aiBuddySingleInstance: false,
    aiBuddyProviders: '',
    aiBuddyMaxCostPerJobUsd: 0.50,
    aiBuddyDailyBudgetUsd: 10.0,
    aiBuddyMaxInputPricePerMillion: 5.0,
    aiBuddyMaxOutputPricePerMillion: 20.0,
    aiBuddyMicrosoftEvidenceEnabled: true,
    aiBuddyCrowdStrikeEvidenceEnabled: false
    });
    setEditingConfig(null);
    setSelectedModelOption('openai/gpt-6.1-sol');
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
            <h2>OpenRouter / LLM Configuration Management</h2>
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
                          required={!editingConfig}
                          placeholder={editingConfig ? "Leave blank to keep the stored API key" : "sk-or-..."}
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
                        <div className="form-text">Latest Sol and Sonnet models appear first. Use a concrete model ID for AI buddy; existing custom models remain editable.</div>
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

                  <fieldset className="border rounded p-3 mb-3">
                    <legend className="float-none w-auto px-2 fs-6">AI buddy(beta)</legend>
                    <p className="text-muted">Active selects the configuration; Enabled turns on AI buddy. Neither means setup is complete. AI buddy uses this configuration’s API key, base URL and model, with output capped at 4,096 tokens.</p>
                    <div className="alert alert-info" role="status">
                      <strong>{setupResult?.ready ? 'Provider setup verified for this draft' : buddySetupLabel(formData, Boolean(editingConfig?.apiKey))}</strong>
                      {formData.aiBuddyEnabled && buddySetupMissing(formData, Boolean(editingConfig?.apiKey)).length > 0 && <ul className="mb-0 mt-2">
                        {buddySetupMissing(formData, Boolean(editingConfig?.apiKey)).map(reason => <li key={reason}>{reason}</li>)}
                      </ul>}
                      {setupResult && setupResult.reasons.length > 0 && <ul className="mb-0 mt-2">{setupResult.reasons.map(reason => <li key={reason}>{buddyLabel(reason)}</li>)}</ul>}
                    </div>
                    <div className="form-check mb-2"><input type="checkbox" className="form-check-input" id="aiBuddyEnabled" name="aiBuddyEnabled" checked={formData.aiBuddyEnabled} onChange={handleInputChange} /><label className="form-check-label" htmlFor="aiBuddyEnabled">Enable AI buddy(beta)</label></div>
                    <div className="form-check mb-2"><input type="checkbox" className="form-check-input" id="aiBuddySingleInstance" name="aiBuddySingleInstance" checked={formData.aiBuddySingleInstance} onChange={handleInputChange} /><label className="form-check-label" htmlFor="aiBuddySingleInstance">I confirm this deployment uses one backend instance</label></div>
                    <div className="form-check mb-2"><input type="checkbox" className="form-check-input" id="aiBuddyMicrosoftEvidenceEnabled" name="aiBuddyMicrosoftEvidenceEnabled" checked={formData.aiBuddyMicrosoftEvidenceEnabled} onChange={handleInputChange} /><label className="form-check-label" htmlFor="aiBuddyMicrosoftEvidenceEnabled">Retrieve public Microsoft remediation evidence</label></div>
                    <div className="form-check mb-2"><input type="checkbox" className="form-check-input" id="aiBuddyCrowdStrikeEvidenceEnabled" name="aiBuddyCrowdStrikeEvidenceEnabled" checked={formData.aiBuddyCrowdStrikeEvidenceEnabled} onChange={handleInputChange} /><label className="form-check-label" htmlFor="aiBuddyCrowdStrikeEvidenceEnabled">Retrieve CrowdStrike remediation evidence using existing Falcon credentials</label></div>
                    <div id="aiBuddyProviders" tabIndex={-1} className="mb-2">
                      <h3 className="h6">Choose provider routes</h3>
                      <p className="form-text">Routes for your selected model load automatically. Select a compatible route below to approve it. ZDR means zero data retention, as reported by OpenRouter. Save or Update applies your choices.</p>
                    </div>
                    <button type="button" className="btn btn-outline-primary mb-2" disabled={checkingSetup} onClick={() => void handleSetupCheck(formData, true)}>
                      {checkingSetup ? 'Loading routes and checking setup…' : 'Refresh provider routes'}
                    </button>
                    <p className="form-text">Loading routes reads provider metadata only. It does not generate a report or save settings.</p>
                    {setupError && <div className="alert alert-warning" role="alert">{setupError}</div>}
                    {!formData.apiKey.trim() && !editingConfig?.apiKey && <p className="text-muted">Enter an OpenRouter API key above to load provider suggestions.</p>}
                    {providerOptions.length > 0 && <div className="border rounded p-2 mb-2">
                      {providerOptions.map(provider => <div className="form-check mb-2" key={provider.tag}>
                        <input className="form-check-input" type="checkbox" id={`provider-${provider.tag}`} checked={formData.aiBuddyProviders.split(',').map(tag => tag.trim()).includes(provider.tag)}
                          disabled={!provider.compatible && !formData.aiBuddyProviders.split(',').map(tag => tag.trim()).includes(provider.tag)} onChange={event => selectProvider(provider.tag, event.target.checked)} />
                        <label className="form-check-label" htmlFor={`provider-${provider.tag}`}>
                          {provider.name} <small className="text-muted">({provider.tag})</small>
                          <span className={`badge ms-2 ${provider.reasons.includes('ZERO_RETENTION_REQUIRED') ? 'bg-secondary' : 'bg-success'}`}>
                            {provider.reasons.includes('ZERO_RETENTION_REQUIRED') ? 'ZDR not verified' : 'ZDR'}
                          </span>
                          {provider.compatible && <span className="badge bg-primary ms-2">Compatible with your settings</span>}
                        </label>
                        {!provider.compatible && <small className="d-block text-muted">{provider.reasons.map(buddyLabel).join('; ')}</small>}
                      </div>)}
                    </div>}
                    {setupResult && !setupError && providerOptions.length === 0 && <p className="text-warning">No provider routes were returned. Check the model, API key and setup messages above.</p>}
                    {providerOptions.length > 0 && !providerOptions.some(provider => provider.compatible) && <p className="text-warning">No route currently meets all requirements. Review the reasons above, choose another model or adjust your token and price limits, then refresh.</p>}
                    <details className="mb-2">
                      <summary>Advanced: edit approved route tags manually</summary>
                      <label htmlFor="aiBuddyProviderTags" className="form-label mt-2">Approved provider tags</label>
                      <input className="form-control" id="aiBuddyProviderTags" name="aiBuddyProviders" maxLength={512} value={formData.aiBuddyProviders} onChange={handleInputChange} placeholder="Comma-separated provider tags" />
                    </details>
                    <p className="form-text">AI buddy requires a concrete author/model ID, the standard HTTPS OpenRouter URL, an approved provider route with structured outputs and zero data retention, and Max Tokens of at least 128. Automatic aliases, online/search suffixes and fallback routes are not accepted. Approve provider data handling before enabling.</p>
                    <div className="row">
                      <div className="col-md-6 mb-2"><label className="form-label" htmlFor="aiBuddyMaxCostPerJobUsd">Maximum reservation per report (USD)</label><input type="number" className="form-control" id="aiBuddyMaxCostPerJobUsd" name="aiBuddyMaxCostPerJobUsd" value={formData.aiBuddyMaxCostPerJobUsd} onChange={handleInputChange} min="0.01" max="1000000" step="0.01" required /></div>
                      <div className="col-md-6 mb-2"><label className="form-label" htmlFor="aiBuddyDailyBudgetUsd">Daily AI buddy reservation budget (USD)</label><input type="number" className="form-control" id="aiBuddyDailyBudgetUsd" name="aiBuddyDailyBudgetUsd" value={formData.aiBuddyDailyBudgetUsd} onChange={handleInputChange} min="0.01" max="1000000" step="0.01" required /></div>
                      <div className="col-md-6 mb-2"><label className="form-label" htmlFor="aiBuddyMaxInputPricePerMillion">Maximum input price per million tokens (USD)</label><input type="number" className="form-control" id="aiBuddyMaxInputPricePerMillion" name="aiBuddyMaxInputPricePerMillion" value={formData.aiBuddyMaxInputPricePerMillion} onChange={handleInputChange} min="0.01" max="1000000" step="0.01" required /></div>
                      <div className="col-md-6 mb-2"><label className="form-label" htmlFor="aiBuddyMaxOutputPricePerMillion">Maximum output price per million tokens (USD)</label><input type="number" className="form-control" id="aiBuddyMaxOutputPricePerMillion" name="aiBuddyMaxOutputPricePerMillion" value={formData.aiBuddyMaxOutputPricePerMillion} onChange={handleInputChange} min="0.01" max="1000000" step="0.01" required /></div>
                    </div>
                    <p className="form-text">Budget counters are process-local and reset on restart. Set a provider-side spending ceiling as well. Saving or switching the active configuration affects subsequent requests; running analyses stop when their configuration changes.</p>
                  </fieldset>

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
              {!showForm && setupFeedback && <div className={`alert alert-${setupFeedback.kind}`} role="status" aria-live="polite">
                {setupFeedback.message}
              </div>}
              {configs.length === 0 ? (
                <p className="text-muted">No LLM configurations found. Click "Add New Configuration" to create one.</p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped table-hover">
                    <thead>
                      <tr>
                        <th>Status</th>
                        <th>AI buddy setup</th>
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
                          <td>
                            <strong>{config.id && savedSetupChecks[config.id]?.ready ? 'Provider setup verified' : buddySetupLabel(config)}</strong>
                            {config.aiBuddyEnabled && <ul className="small mb-0">{buddySetupMissing(config).map(reason => <li key={reason}>{reason}</li>)}</ul>}
                            {config.id && savedSetupChecks[config.id]?.reasons.map(reason => <div className="small" key={reason}>{buddyLabel(reason)}</div>)}
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
                                {testing === config.id ? 'Testing...' : 'Test LLM connection'}
                              </button>
                              <button onClick={() => void handleSetupCheck(config, false)} className="btn btn-outline-info mb-1" disabled={checkingSetup}>
                                {checkingSetup && setupFeedback?.configId === config.id ? 'Checking AI buddy setup…' : 'Check AI buddy setup'}
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

      {!showForm && setupError && <div className="alert alert-warning" role="alert">{setupError}</div>}

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
