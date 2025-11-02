import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

type EmailProvider = 'SMTP' | 'AMAZON_SES';

interface EmailConfig {
  id?: number;
  name: string;
  provider: EmailProvider;
  smtpHost?: string;
  smtpPort?: number;
  smtpUsername?: string;
  smtpPassword?: string;
  smtpTls?: boolean;
  smtpSsl?: boolean;
  sesAccessKey?: string;
  sesSecretKey?: string;
  sesRegion?: string;
  fromEmail: string;
  fromName: string;
  isActive: boolean;
  imapHost?: string;
  imapPort?: number;
  imapEnabled?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

const EmailConfigManagement: React.FC = () => {
  const [configs, setConfigs] = useState<EmailConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingConfig, setEditingConfig] = useState<EmailConfig | null>(null);
  const [testing, setTesting] = useState<number | null>(null);
  const [formData, setFormData] = useState<EmailConfig>({
    name: '',
    provider: 'SMTP',
    smtpHost: '',
    smtpPort: 587,
    smtpUsername: '',
    smtpPassword: '',
    smtpTls: true,
    smtpSsl: false,
    sesAccessKey: '',
    sesSecretKey: '',
    sesRegion: 'us-east-1',
    fromEmail: '',
    fromName: 'SecMan Risk Assessment',
    isActive: false,
    imapEnabled: false
  });

  // Check if user is authenticated and has admin access
  const [currentUser, setCurrentUser] = useState<any>(null);
  const [hasAdminAccess, setHasAdminAccess] = useState(false);

  useEffect(() => {
    // Check authentication and admin access
    const checkAuth = () => {
      if (typeof window !== 'undefined' && (window as any).currentUser) {
        const user = (window as any).currentUser;
        setCurrentUser(user);

        // Check admin access
        const hasAdmin = user.roles && (
          user.roles.includes('ADMIN') ||
          user.roles.includes('ROLE_ADMIN') ||
          user.roles.includes('admin')
        );
        setHasAdminAccess(hasAdmin);

        if (!hasAdmin) {
          setError('Access denied. Admin privileges required to manage email configuration.');
        }
      }
    };

    checkAuth();

    // Listen for userLoaded event
    const handleUserLoaded = () => {
      if (typeof window !== 'undefined' && (window as any).currentUser) {
        const user = (window as any).currentUser;
        setCurrentUser(user);

        // Check admin access
        const hasAdmin = user.roles && (
          user.roles.includes('ADMIN') ||
          user.roles.includes('ROLE_ADMIN') ||
          user.roles.includes('admin')
        );
        setHasAdminAccess(hasAdmin);

        if (!hasAdmin) {
          setError('Access denied. Admin privileges required to manage email configuration.');
        }
      }
    };

    if (typeof window !== 'undefined') {
      window.addEventListener('userLoaded', handleUserLoaded);
      return () => window.removeEventListener('userLoaded', handleUserLoaded);
    }
  }, []);

  useEffect(() => {
    if (currentUser && hasAdminAccess) {
      fetchConfigs();
    }
  }, [currentUser, hasAdminAccess]);

  const fetchConfigs = async () => {
    try {
      const response = await authenticatedGet('/api/email-provider-configs');
      if (response.ok) {
        const data = await response.json();
        setConfigs(data);
      } else {
        setError(`Failed to fetch email configs: ${response.status}`);
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
        await authenticatedPut(`/api/email-provider-configs/${editingConfig.id}`, formData);
      } else {
        // Use provider-specific endpoint for creation
        const endpoint = formData.provider === 'SMTP'
          ? '/api/email-provider-configs/smtp'
          : '/api/email-provider-configs/ses';
        await authenticatedPost(endpoint, formData);
      }

      await fetchConfigs();
      resetForm();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleEdit = (config: EmailConfig) => {
    setEditingConfig(config);
    setFormData({ ...config });
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this email configuration?')) {
      return;
    }

    try {
      await authenticatedDelete(`/api/email-provider-configs/${id}`);
      await fetchConfigs();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleTest = async (id: number) => {
    const testEmail = prompt('Enter email address to send test email to:');
    if (!testEmail) return;

    setTesting(id);
    try {
      await authenticatedPost(`/api/email-provider-configs/${id}/test`, { testEmailAddress: testEmail });
      setError('Test email sent successfully!');
      setTimeout(() => setError(null), 3000);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to send test email');
    } finally {
      setTesting(null);
    }
  };

  const handleActivate = async (id: number) => {
    try {
      await authenticatedPost(`/api/email-provider-configs/${id}/activate`, {});
      await fetchConfigs();
      setError('Configuration activated successfully!');
      setTimeout(() => setError(null), 3000);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to activate configuration');
    }
  };

  const resetForm = () => {
    setFormData({
      name: '',
      provider: 'SMTP',
      smtpHost: '',
      smtpPort: 587,
      smtpUsername: '',
      smtpPassword: '',
      smtpTls: true,
      smtpSsl: false,
      sesAccessKey: '',
      sesSecretKey: '',
      sesRegion: 'us-east-1',
      fromEmail: '',
      fromName: 'SecMan Risk Assessment',
      isActive: false,
      imapEnabled: false
    });
    setEditingConfig(null);
    setShowForm(false);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const { name, value, type } = e.target;
    setFormData(prev => ({ 
      ...prev, 
      [name]: type === 'checkbox' ? (e.target as HTMLInputElement).checked :
              type === 'number' ? parseInt(value) : value
    }));
  };

  if (!currentUser) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-warning" role="alert">
          <h4 className="alert-heading">Authentication Required</h4>
          <p>You must be logged in to access Email Configuration Management.</p>
          <hr />
          <p className="mb-0">Please <a href="/login">log in</a> to continue.</p>
        </div>
      </div>
    );
  }

  if (!hasAdminAccess) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-danger" role="alert">
          <h4 className="alert-heading">Access Denied</h4>
          <p>You need administrator privileges to access Email Configuration Management.</p>
          <hr />
          <p className="mb-0">Current user: <strong>{currentUser.username}</strong></p>
          <p className="mb-0">Your roles: {currentUser.roles ? currentUser.roles.join(', ') : 'None'}</p>
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
            <h2>Email Configuration Management</h2>
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
                <h5 className="card-title">{editingConfig ? 'Edit Email Configuration' : 'Add New Email Configuration'}</h5>
                <form onSubmit={handleSubmit}>
                  {/* Name and Provider Selection */}
                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="name" className="form-label">Configuration Name *</label>
                        <input
                          type="text"
                          className="form-control"
                          id="name"
                          name="name"
                          value={formData.name}
                          onChange={handleInputChange}
                          required
                          placeholder="Production SMTP"
                        />
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="provider" className="form-label">Email Provider *</label>
                        <select
                          className="form-select"
                          id="provider"
                          name="provider"
                          value={formData.provider}
                          onChange={handleInputChange}
                          disabled={!!editingConfig}
                          required
                        >
                          <option value="SMTP">SMTP</option>
                          <option value="AMAZON_SES">Amazon SES</option>
                        </select>
                        {editingConfig && <small className="text-muted">Provider cannot be changed after creation</small>}
                      </div>
                    </div>
                  </div>

                  {/* SMTP-specific fields */}
                  {formData.provider === 'SMTP' && (
                    <>
                      <div className="row">
                        <div className="col-md-6">
                          <div className="mb-3">
                            <label htmlFor="smtpHost" className="form-label">SMTP Host *</label>
                            <input
                              type="text"
                              className="form-control"
                              id="smtpHost"
                              name="smtpHost"
                              value={formData.smtpHost || ''}
                              onChange={handleInputChange}
                              required
                              placeholder="smtp.gmail.com"
                            />
                          </div>
                        </div>
                        <div className="col-md-6">
                          <div className="mb-3">
                            <label htmlFor="smtpPort" className="form-label">SMTP Port *</label>
                            <input
                              type="number"
                              className="form-control"
                              id="smtpPort"
                              name="smtpPort"
                              value={formData.smtpPort || 587}
                              onChange={handleInputChange}
                              required
                            />
                          </div>
                        </div>
                      </div>

                      <div className="row">
                        <div className="col-md-6">
                          <div className="mb-3">
                            <label htmlFor="smtpUsername" className="form-label">SMTP Username</label>
                            <input
                              type="text"
                              className="form-control"
                              id="smtpUsername"
                              name="smtpUsername"
                              value={formData.smtpUsername || ''}
                              onChange={handleInputChange}
                              placeholder="your-email@gmail.com"
                            />
                          </div>
                        </div>
                        <div className="col-md-6">
                          <div className="mb-3">
                            <label htmlFor="smtpPassword" className="form-label">SMTP Password</label>
                            <input
                              type="password"
                              className="form-control"
                              id="smtpPassword"
                              name="smtpPassword"
                              value={formData.smtpPassword || ''}
                              onChange={handleInputChange}
                              placeholder={editingConfig ? "***HIDDEN***" : ""}
                            />
                          </div>
                        </div>
                      </div>

                      <div className="row">
                        <div className="col-md-6">
                          <div className="mb-3 form-check">
                            <input
                              type="checkbox"
                              className="form-check-input"
                              id="smtpTls"
                              name="smtpTls"
                              checked={formData.smtpTls || false}
                              onChange={handleInputChange}
                            />
                            <label className="form-check-label" htmlFor="smtpTls">
                              Enable TLS
                            </label>
                          </div>
                        </div>
                        <div className="col-md-6">
                          <div className="mb-3 form-check">
                            <input
                              type="checkbox"
                              className="form-check-input"
                              id="smtpSsl"
                              name="smtpSsl"
                              checked={formData.smtpSsl || false}
                              onChange={handleInputChange}
                            />
                            <label className="form-check-label" htmlFor="smtpSsl">
                              Enable SSL
                            </label>
                          </div>
                        </div>
                      </div>
                    </>
                  )}

                  {/* Amazon SES-specific fields */}
                  {formData.provider === 'AMAZON_SES' && (
                    <>
                      <div className="row">
                        <div className="col-md-12">
                          <div className="alert alert-info">
                            <strong>Amazon SES Configuration</strong><br />
                            Make sure your AWS account has SES access and the from email address is verified in SES.
                          </div>
                        </div>
                      </div>

                      <div className="row">
                        <div className="col-md-6">
                          <div className="mb-3">
                            <label htmlFor="sesAccessKey" className="form-label">AWS Access Key ID *</label>
                            <input
                              type="text"
                              className="form-control"
                              id="sesAccessKey"
                              name="sesAccessKey"
                              value={formData.sesAccessKey || ''}
                              onChange={handleInputChange}
                              required
                              placeholder="AKIAIOSFODNN7EXAMPLE"
                            />
                          </div>
                        </div>
                        <div className="col-md-6">
                          <div className="mb-3">
                            <label htmlFor="sesSecretKey" className="form-label">AWS Secret Access Key *</label>
                            <input
                              type="password"
                              className="form-control"
                              id="sesSecretKey"
                              name="sesSecretKey"
                              value={formData.sesSecretKey || ''}
                              onChange={handleInputChange}
                              required
                              placeholder={editingConfig ? "***HIDDEN***" : ""}
                            />
                          </div>
                        </div>
                      </div>

                      <div className="row">
                        <div className="col-md-6">
                          <div className="mb-3">
                            <label htmlFor="sesRegion" className="form-label">AWS Region *</label>
                            <select
                              className="form-select"
                              id="sesRegion"
                              name="sesRegion"
                              value={formData.sesRegion || 'us-east-1'}
                              onChange={handleInputChange}
                              required
                            >
                              <option value="us-east-1">US East (N. Virginia)</option>
                              <option value="us-east-2">US East (Ohio)</option>
                              <option value="us-west-1">US West (N. California)</option>
                              <option value="us-west-2">US West (Oregon)</option>
                              <option value="eu-west-1">EU (Ireland)</option>
                              <option value="eu-central-1">EU (Frankfurt)</option>
                              <option value="ap-southeast-1">Asia Pacific (Singapore)</option>
                              <option value="ap-southeast-2">Asia Pacific (Sydney)</option>
                              <option value="ap-northeast-1">Asia Pacific (Tokyo)</option>
                            </select>
                          </div>
                        </div>
                      </div>
                    </>
                  )}

                  {/* Common fields for both providers */}
                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="fromEmail" className="form-label">From Email *</label>
                        <input
                          type="email"
                          className="form-control"
                          id="fromEmail"
                          name="fromEmail"
                          value={formData.fromEmail}
                          onChange={handleInputChange}
                          required
                          placeholder="noreply@yourcompany.com"
                        />
                        {formData.provider === 'AMAZON_SES' && (
                          <small className="text-muted">This email must be verified in Amazon SES</small>
                        )}
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="fromName" className="form-label">From Name *</label>
                        <input
                          type="text"
                          className="form-control"
                          id="fromName"
                          name="fromName"
                          value={formData.fromName}
                          onChange={handleInputChange}
                          required
                        />
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
              <h5 className="card-title">Email Configurations ({configs.length})</h5>
              {configs.length === 0 ? (
                <p className="text-muted">No email configurations found. Click "Add New Configuration" to create one.</p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped table-hover">
                    <thead>
                      <tr>
                        <th>Status</th>
                        <th>Name</th>
                        <th>Provider</th>
                        <th>Configuration</th>
                        <th>From Email</th>
                        <th>From Name</th>
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
                          <td>{config.name}</td>
                          <td>
                            <span className={`badge ${config.provider === 'SMTP' ? 'bg-primary' : 'bg-warning'}`}>
                              {config.provider === 'SMTP' ? 'SMTP' : 'Amazon SES'}
                            </span>
                          </td>
                          <td>
                            {config.provider === 'SMTP' ? (
                              <div>
                                <div><small><strong>Host:</strong> {config.smtpHost}:{config.smtpPort}</small></div>
                                <div>
                                  {config.smtpTls && <span className="badge bg-info me-1">TLS</span>}
                                  {config.smtpSsl && <span className="badge bg-info me-1">SSL</span>}
                                  {config.smtpUsername && <span className="badge bg-success">Auth</span>}
                                </div>
                              </div>
                            ) : (
                              <div>
                                <div><small><strong>Region:</strong> {config.sesRegion}</small></div>
                                <div><span className="badge bg-success">AWS SES</span></div>
                              </div>
                            )}
                          </td>
                          <td>{config.fromEmail}</td>
                          <td>{config.fromName}</td>
                          <td>
                            {config.updatedAt ? new Date(config.updatedAt).toLocaleDateString() : '-'}
                          </td>
                          <td>
                            <div className="btn-group-vertical btn-group-sm" role="group">
                              {!config.isActive && (
                                <button
                                  onClick={() => config.id && handleActivate(config.id)}
                                  className="btn btn-outline-success mb-1"
                                >
                                  Activate
                                </button>
                              )}
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
                                disabled={config.isActive}
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
            <div className={`alert ${error.includes('successfully') ? 'alert-success' : 'alert-danger'}`} role="alert">
              {error}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default EmailConfigManagement;