import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

interface EmailConfig {
  id?: number;
  smtpHost: string;
  smtpPort: number;
  smtpUsername: string;
  smtpPassword: string;
  smtpTls: boolean;
  smtpSsl: boolean;
  fromEmail: string;
  fromName: string;
  isActive: boolean;
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
    smtpHost: '',
    smtpPort: 587,
    smtpUsername: '',
    smtpPassword: '',
    smtpTls: true,
    smtpSsl: false,
    fromEmail: '',
    fromName: 'SecMan Risk Assessment',
    isActive: true
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
      const response = await authenticatedGet('/api/email-config');
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
        await authenticatedPut(`/api/email-config/${editingConfig.id}`, formData);
      } else {
        await authenticatedPost('/api/email-config', formData);
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
      await authenticatedDelete(`/api/email-config/${id}`);
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
      await authenticatedPost(`/api/email-config/${id}/test`, { testEmail });
      setError('Test email sent successfully!');
      setTimeout(() => setError(null), 3000);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to send test email');
    } finally {
      setTesting(null);
    }
  };

  const resetForm = () => {
    setFormData({
      smtpHost: '',
      smtpPort: 587,
      smtpUsername: '',
      smtpPassword: '',
      smtpTls: true,
      smtpSsl: false,
      fromEmail: '',
      fromName: 'SecMan Risk Assessment',
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
                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="smtpHost" className="form-label">SMTP Host *</label>
                        <input
                          type="text"
                          className="form-control"
                          id="smtpHost"
                          name="smtpHost"
                          value={formData.smtpHost}
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
                          value={formData.smtpPort}
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
                          value={formData.smtpUsername}
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
                          value={formData.smtpPassword}
                          onChange={handleInputChange}
                          placeholder={editingConfig ? "***HIDDEN***" : ""}
                        />
                      </div>
                    </div>
                  </div>

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

                  <div className="row">
                    <div className="col-md-4">
                      <div className="mb-3 form-check">
                        <input
                          type="checkbox"
                          className="form-check-input"
                          id="smtpTls"
                          name="smtpTls"
                          checked={formData.smtpTls}
                          onChange={handleInputChange}
                        />
                        <label className="form-check-label" htmlFor="smtpTls">
                          Enable TLS
                        </label>
                      </div>
                    </div>
                    <div className="col-md-4">
                      <div className="mb-3 form-check">
                        <input
                          type="checkbox"
                          className="form-check-input"
                          id="smtpSsl"
                          name="smtpSsl"
                          checked={formData.smtpSsl}
                          onChange={handleInputChange}
                        />
                        <label className="form-check-label" htmlFor="smtpSsl">
                          Enable SSL
                        </label>
                      </div>
                    </div>
                    <div className="col-md-4">
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
              <h5 className="card-title">Email Configurations ({configs.length})</h5>
              {configs.length === 0 ? (
                <p className="text-muted">No email configurations found. Click "Add New Configuration" to create one.</p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped table-hover">
                    <thead>
                      <tr>
                        <th>Status</th>
                        <th>SMTP Host</th>
                        <th>Port</th>
                        <th>From Email</th>
                        <th>From Name</th>
                        <th>Security</th>
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
                          <td>{config.smtpHost}</td>
                          <td>{config.smtpPort}</td>
                          <td>{config.fromEmail}</td>
                          <td>{config.fromName}</td>
                          <td>
                            <div>
                              {config.smtpTls && <span className="badge bg-info me-1">TLS</span>}
                              {config.smtpSsl && <span className="badge bg-info me-1">SSL</span>}
                              {config.smtpUsername && <span className="badge bg-success">Auth</span>}
                            </div>
                          </td>
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