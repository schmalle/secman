import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

interface IdentityProvider {
  id?: number;
  name: string;
  type: 'OIDC' | 'SAML';
  clientId: string;
  clientSecret?: string;
  tenantId?: string;
  discoveryUrl?: string;
  authorizationUrl?: string;
  tokenUrl?: string;
  userInfoUrl?: string;
  issuer?: string;
  jwksUri?: string;
  scopes?: string;
  enabled: boolean;
  autoProvision: boolean;
  buttonText: string;
  buttonColor: string;
  roleMapping?: { [key: string]: string };
  claimMappings?: { [key: string]: string };
  createdAt?: string;
  updatedAt?: string;
}

interface ApiResponse {
  error?: string;
  message?: string;
}

export default function IdentityProviderManagement() {
  const [providers, setProviders] = useState<IdentityProvider[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<IdentityProvider | null>(null);
  const [isAddingProvider, setIsAddingProvider] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isTestingProvider, setIsTestingProvider] = useState<number | null>(null);

  const [formData, setFormData] = useState<IdentityProvider>({
    name: '',
    type: 'OIDC',
    clientId: '',
    clientSecret: '',
    discoveryUrl: '',
    authorizationUrl: '',
    tokenUrl: '',
    userInfoUrl: '',
    issuer: '',
    jwksUri: '',
    scopes: 'openid email profile',
    enabled: true,
    autoProvision: false,
    buttonText: 'Sign in with Provider',
    buttonColor: '#007bff',
    roleMapping: {},
    claimMappings: {}
  });

  // Predefined provider templates
  const providerTemplates = {
    google: {
      name: 'Google',
      discoveryUrl: 'https://accounts.google.com/.well-known/openid_configuration',
      buttonText: 'Sign in with Google',
      buttonColor: '#4285f4'
    },
    microsoft: {
      name: 'Microsoft',
      tenantId: '',
      discoveryUrl: 'https://login.microsoftonline.com/{tenantId}/v2.0/.well-known/openid_configuration',
      authorizationUrl: 'https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/authorize',
      tokenUrl: 'https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token',
      scopes: 'openid email profile',
      buttonText: 'Sign in with Microsoft',
      buttonColor: '#0078d4'
    },
    github: {
      name: 'GitHub',
      authorizationUrl: 'https://github.com/login/oauth/authorize',
      tokenUrl: 'https://github.com/login/oauth/access_token',
      userInfoUrl: 'https://api.github.com/user',
      scopes: 'user:email',
      buttonText: 'Sign in with GitHub',
      buttonColor: '#333333'
    }
  };

  useEffect(() => {
    fetchProviders();
  }, []);

  const fetchProviders = async () => {
    try {
      setIsLoading(true);
      const response = await authenticatedGet('/api/identity-providers');
      if (response.ok) {
        const data = await response.json();
        setProviders(data);
      } else {
        setError(`Failed to load identity providers: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load identity providers');
    } finally {
      setIsLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccess(null);

    try {
      setIsLoading(true);
      
      if (selectedProvider) {
        await authenticatedPut(`/api/identity-providers/${selectedProvider.id}`, formData);
        setSuccess('Provider updated successfully');
      } else {
        await authenticatedPost('/api/identity-providers', formData);
        setSuccess('Provider created successfully');
      }

      resetForm();
      fetchProviders();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save provider');
    } finally {
      setIsLoading(false);
    }
  };

  const handleDelete = async (id: number) => {
    if (!confirm('Are you sure you want to delete this identity provider?')) {
      return;
    }

    try {
      setIsLoading(true);
      await authenticatedDelete(`/api/identity-providers/${id}`);
      setSuccess('Provider deleted successfully');
      fetchProviders();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete provider');
    } finally {
      setIsLoading(false);
    }
  };

  const handleTest = async (id: number) => {
    try {
      setIsTestingProvider(id);
      await authenticatedPost(`/api/identity-providers/${id}/test`, {});
      setSuccess('Provider configuration is valid');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Provider configuration test failed');
    } finally {
      setIsTestingProvider(null);
    }
  };

  const handleEdit = (provider: IdentityProvider) => {
    setSelectedProvider(provider);
    setFormData(provider);
    setIsAddingProvider(true);
  };

  const resetForm = () => {
    setSelectedProvider(null);
    setIsAddingProvider(false);
    setFormData({
      name: '',
      type: 'OIDC',
      clientId: '',
      clientSecret: '',
      discoveryUrl: '',
      authorizationUrl: '',
      tokenUrl: '',
      userInfoUrl: '',
      issuer: '',
      jwksUri: '',
      scopes: 'openid email profile',
      enabled: true,
      autoProvision: false,
      buttonText: 'Sign in with Provider',
      buttonColor: '#007bff',
      roleMapping: {},
      claimMappings: {}
    });
  };

  const applyTemplate = (templateKey: keyof typeof providerTemplates) => {
    const template = providerTemplates[templateKey];
    setFormData(prev => ({
      ...prev,
      ...template,
      type: 'OIDC'
    }));
  };

  const handleInputChange = (field: keyof IdentityProvider, value: any) => {
    setFormData(prev => ({
      ...prev,
      [field]: value
    }));
  };

  return (
    <div className="container-fluid p-4">
      <div className="row">
        <div className="col-12">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <h2>Identity Provider Management</h2>
            <button 
              className="btn btn-primary" 
              onClick={() => setIsAddingProvider(!isAddingProvider)}
              disabled={isLoading}
            >
              {isAddingProvider ? 'Cancel' : 'Add Identity Provider'}
            </button>
          </div>
        </div>
      </div>

      {/* Success/Error Messages */}
      {success && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="alert alert-success alert-dismissible" role="alert">
              {success}
              <button type="button" className="btn-close" onClick={() => setSuccess(null)}></button>
            </div>
          </div>
        </div>
      )}

      {error && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="alert alert-danger alert-dismissible" role="alert">
              {error}
              <button type="button" className="btn-close" onClick={() => setError(null)}></button>
            </div>
          </div>
        </div>
      )}

      {/* Add/Edit Form */}
      {isAddingProvider && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-header">
                <h5>{selectedProvider ? 'Edit' : 'Add'} Identity Provider</h5>
              </div>
              <div className="card-body">
                {/* Quick Templates */}
                {!selectedProvider && (
                  <div className="mb-4">
                    <h6>Quick Setup Templates:</h6>
                    <div className="btn-group" role="group">
                      <button 
                        type="button" 
                        className="btn btn-outline-primary"
                        onClick={() => applyTemplate('google')}
                      >
                        Google
                      </button>
                      <button 
                        type="button" 
                        className="btn btn-outline-primary"
                        onClick={() => applyTemplate('microsoft')}
                      >
                        Microsoft
                      </button>
                      <button 
                        type="button" 
                        className="btn btn-outline-primary"
                        onClick={() => applyTemplate('github')}
                      >
                        GitHub
                      </button>
                    </div>
                  </div>
                )}

                <form onSubmit={handleSubmit}>
                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label className="form-label">Provider Name *</label>
                        <input
                          type="text"
                          className="form-control"
                          value={formData.name}
                          onChange={(e) => handleInputChange('name', e.target.value)}
                          required
                        />
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label className="form-label">Type *</label>
                        <select
                          className="form-select"
                          value={formData.type}
                          onChange={(e) => handleInputChange('type', e.target.value as 'OIDC' | 'SAML')}
                        >
                          <option value="OIDC">OpenID Connect</option>
                          <option value="SAML" disabled>SAML 2.0 (Coming Soon)</option>
                        </select>
                      </div>
                    </div>
                  </div>

                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label className="form-label">Client ID *</label>
                        <input
                          type="text"
                          className="form-control"
                          value={formData.clientId}
                          onChange={(e) => handleInputChange('clientId', e.target.value)}
                          required
                        />
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label className="form-label">Client Secret *</label>
                        <input
                          type="password"
                          className="form-control"
                          value={formData.clientSecret || ''}
                          onChange={(e) => handleInputChange('clientSecret', e.target.value)}
                          placeholder={selectedProvider ? 'Leave blank to keep current' : ''}
                          required={!selectedProvider}
                        />
                      </div>
                    </div>
                  </div>

                  {formData.name.toLowerCase().includes('microsoft') && (
                    <div className="mb-3">
                      <label className="form-label">Tenant ID *</label>
                      <input
                        type="text"
                        className="form-control"
                        value={formData.tenantId || ''}
                        onChange={(e) => handleInputChange('tenantId', e.target.value)}
                        placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                        pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                        required
                      />
                      <div className="form-text">
                        Azure AD Tenant ID (UUID format). Required for Microsoft authentication.
                      </div>
                    </div>
                  )}

                  <div className="mb-3">
                    <label className="form-label">Discovery URL (OIDC)</label>
                    <input
                      type="url"
                      className="form-control"
                      value={formData.discoveryUrl || ''}
                      onChange={(e) => handleInputChange('discoveryUrl', e.target.value)}
                      placeholder="https://provider.com/.well-known/openid_configuration"
                    />
                    <div className="form-text">
                      If provided, other URLs will be auto-discovered. Otherwise, specify them manually below.
                    </div>
                  </div>

                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label className="form-label">Authorization URL</label>
                        <input
                          type="url"
                          className="form-control"
                          value={formData.authorizationUrl || ''}
                          onChange={(e) => handleInputChange('authorizationUrl', e.target.value)}
                        />
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label className="form-label">Token URL</label>
                        <input
                          type="url"
                          className="form-control"
                          value={formData.tokenUrl || ''}
                          onChange={(e) => handleInputChange('tokenUrl', e.target.value)}
                        />
                      </div>
                    </div>
                  </div>

                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label className="form-label">User Info URL</label>
                        <input
                          type="url"
                          className="form-control"
                          value={formData.userInfoUrl || ''}
                          onChange={(e) => handleInputChange('userInfoUrl', e.target.value)}
                        />
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label className="form-label">Scopes</label>
                        <input
                          type="text"
                          className="form-control"
                          value={formData.scopes}
                          onChange={(e) => handleInputChange('scopes', e.target.value)}
                          placeholder="openid email profile"
                        />
                      </div>
                    </div>
                  </div>

                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label className="form-label">Button Text</label>
                        <input
                          type="text"
                          className="form-control"
                          value={formData.buttonText}
                          onChange={(e) => handleInputChange('buttonText', e.target.value)}
                        />
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label className="form-label">Button Color</label>
                        <input
                          type="color"
                          className="form-control form-control-color"
                          value={formData.buttonColor}
                          onChange={(e) => handleInputChange('buttonColor', e.target.value)}
                        />
                      </div>
                    </div>
                  </div>

                  <div className="row">
                    <div className="col-md-6">
                      <div className="form-check mb-3">
                        <input
                          className="form-check-input"
                          type="checkbox"
                          checked={formData.enabled}
                          onChange={(e) => handleInputChange('enabled', e.target.checked)}
                        />
                        <label className="form-check-label">
                          Enable Provider
                        </label>
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="form-check mb-3">
                        <input
                          className="form-check-input"
                          type="checkbox"
                          checked={formData.autoProvision}
                          onChange={(e) => handleInputChange('autoProvision', e.target.checked)}
                        />
                        <label className="form-check-label">
                          Auto-provision Users
                        </label>
                      </div>
                    </div>
                  </div>

                  <div className="d-flex gap-2">
                    <button type="submit" className="btn btn-primary" disabled={isLoading}>
                      {isLoading ? 'Saving...' : (selectedProvider ? 'Update' : 'Create')} Provider
                    </button>
                    <button type="button" className="btn btn-secondary" onClick={resetForm}>
                      Cancel
                    </button>
                  </div>
                </form>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Providers List */}
      <div className="row">
        <div className="col-12">
          <div className="card">
            <div className="card-header">
              <h5>Configured Identity Providers</h5>
            </div>
            <div className="card-body">
              {isLoading && !isAddingProvider ? (
                <div className="text-center">
                  <div className="spinner-border" role="status">
                    <span className="visually-hidden">Loading...</span>
                  </div>
                </div>
              ) : providers.length === 0 ? (
                <p className="text-muted">No identity providers configured.</p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped">
                    <thead>
                      <tr>
                        <th>Name</th>
                        <th>Type</th>
                        <th>Status</th>
                        <th>Auto-provision</th>
                        <th>Button Preview</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {providers.map(provider => (
                        <tr key={provider.id}>
                          <td>
                            <strong>{provider.name}</strong>
                            <br />
                            <small className="text-muted">{provider.clientId}</small>
                          </td>
                          <td>
                            <span className="badge bg-info">{provider.type}</span>
                          </td>
                          <td>
                            <span className={`badge ${provider.enabled ? 'bg-success' : 'bg-secondary'}`}>
                              {provider.enabled ? 'Enabled' : 'Disabled'}
                            </span>
                          </td>
                          <td>
                            <span className={`badge ${provider.autoProvision ? 'bg-warning' : 'bg-light text-dark'}`}>
                              {provider.autoProvision ? 'Yes' : 'No'}
                            </span>
                          </td>
                          <td>
                            <button
                              className="btn btn-sm"
                              style={{
                                backgroundColor: provider.buttonColor,
                                color: '#fff',
                                border: 'none'
                              }}
                              disabled
                            >
                              {provider.buttonText}
                            </button>
                          </td>
                          <td>
                            <div className="btn-group" role="group">
                              <button
                                className="btn btn-sm btn-outline-primary"
                                onClick={() => handleEdit(provider)}
                                disabled={isLoading}
                              >
                                Edit
                              </button>
                              <button
                                className="btn btn-sm btn-outline-info"
                                onClick={() => provider.id && handleTest(provider.id)}
                                disabled={isLoading || isTestingProvider === provider.id}
                              >
                                {isTestingProvider === provider.id ? 'Testing...' : 'Test'}
                              </button>
                              <button
                                className="btn btn-sm btn-outline-danger"
                                onClick={() => provider.id && handleDelete(provider.id)}
                                disabled={isLoading}
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
    </div>
  );
}