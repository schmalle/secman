import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

interface NotificationConfig {
  id?: number;
  name: string;
  description?: string;
  recipientEmails: string;
  notificationTiming: 'immediate' | 'daily' | 'weekly' | 'monthly';
  notificationFrequency: 'all' | 'critical_only' | 'high_only' | 'medium_and_above';
  conditions?: string;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

interface NotificationStats {
  emailStats: {
    totalSent: number;
    totalFailed: number;
    totalPending: number;
    successRate: number;
  };
  configStats: {
    statistics: {
      total: number;
      active: number;
      immediate: number;
    };
    timingDistribution: Record<string, number>;
    frequencyDistribution: Record<string, number>;
  };
}

const NotificationConfigManagement: React.FC = () => {
  const [configs, setConfigs] = useState<NotificationConfig[]>([]);
  const [stats, setStats] = useState<NotificationStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingConfig, setEditingConfig] = useState<NotificationConfig | null>(null);
  const [showActiveOnly, setShowActiveOnly] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');

  const [formData, setFormData] = useState<NotificationConfig>({
    name: '',
    description: '',
    recipientEmails: '',
    notificationTiming: 'immediate',
    notificationFrequency: 'all',
    conditions: '',
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
          setError('Access denied. Admin privileges required to manage notification configurations.');
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
          setError('Access denied. Admin privileges required to manage notification configurations.');
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
      fetchStats();
    }
  }, [currentUser, hasAdminAccess, showActiveOnly]);

  const fetchConfigs = async () => {
    try {
      let url = '/api/notifications/configs';
      if (showActiveOnly) {
        url += '?active=true';
      }

      const response = await authenticatedGet(url);
      if (response.ok) {
        const data = await response.json();
        setConfigs(data);
      } else {
        setError(`Failed to fetch notification configs: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const fetchStats = async () => {
    try {
      const response = await authenticatedGet('/api/notifications/stats');
      if (response.ok) {
        const data = await response.json();
        setStats(data);
      }
    } catch (err) {
      console.error('Failed to fetch stats:', err);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    try {
      if (editingConfig) {
        await authenticatedPut(`/api/notifications/configs/${editingConfig.id}`, formData);
      } else {
        await authenticatedPost('/api/notifications/configs', formData);
      }

      await fetchConfigs();
      await fetchStats();
      resetForm();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleEdit = (config: NotificationConfig) => {
    setEditingConfig(config);
    setFormData({ ...config });
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this notification configuration?')) {
      return;
    }

    try {
      await authenticatedDelete(`/api/notifications/configs/${id}`);
      await fetchConfigs();
      await fetchStats();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleActivate = async (id: number) => {
    try {
      await authenticatedPost(`/api/notifications/configs/${id}/activate`, {});
      await fetchConfigs();
      setError('Configuration activated successfully');
      setTimeout(() => setError(null), 3000);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to activate configuration');
    }
  };

  const handleDeactivate = async (id: number) => {
    try {
      await authenticatedPost(`/api/notifications/configs/${id}/deactivate`, {});
      await fetchConfigs();
      setError('Configuration deactivated successfully');
      setTimeout(() => setError(null), 3000);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to deactivate configuration');
    }
  };

  const handleTest = async (id: number) => {
    const testData = {
      riskAssessmentData: {
        id: 1,
        title: 'Test Risk Assessment',
        riskLevel: 'HIGH',
        createdBy: 'test-user',
        category: 'Security',
        impact: 'HIGH',
        probability: 'MEDIUM'
      }
    };

    try {
      const response = await authenticatedPost(`/api/notifications/configs/${id}/test`, testData);
      if (response.ok) {
        const result = await response.json();
        setError(result.matches ? 'Test passed: Configuration matches criteria' : 'Test result: Configuration does not match criteria');
        setTimeout(() => setError(null), 5000);
      } else {
        setError('Test failed');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to test configuration');
    }
  };

  const handleSendManual = async () => {
    const recipientEmails = prompt('Enter recipient email addresses (comma-separated):');
    if (!recipientEmails) return;

    const subject = prompt('Enter email subject:') || 'Manual Notification from SecMan';
    const htmlContent = prompt('Enter email content:') || 'This is a manual notification from SecMan.';

    try {
      const response = await authenticatedPost('/api/notifications/send', {
        recipientEmails,
        subject,
        htmlContent
      });

      if (response.ok) {
        const result = await response.json();
        setError(`Manual notification sent: ${result.successful} of ${result.recipients} recipients`);
        setTimeout(() => setError(null), 3000);
      } else {
        setError('Failed to send manual notification');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to send manual notification');
    }
  };

  const resetForm = () => {
    setFormData({
      name: '',
      description: '',
      recipientEmails: '',
      notificationTiming: 'immediate',
      notificationFrequency: 'all',
      conditions: '',
      isActive: true
    });
    setEditingConfig(null);
    setShowForm(false);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const { name, value, type } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: type === 'checkbox' ? (e.target as HTMLInputElement).checked : value
    }));
  };

  const filteredConfigs = configs.filter(config =>
    config.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    config.recipientEmails.toLowerCase().includes(searchQuery.toLowerCase()) ||
    (config.description && config.description.toLowerCase().includes(searchQuery.toLowerCase()))
  );

  const getTimingBadge = (timing: string) => {
    switch (timing) {
      case 'immediate': return 'bg-danger';
      case 'daily': return 'bg-warning';
      case 'weekly': return 'bg-info';
      case 'monthly': return 'bg-secondary';
      default: return 'bg-secondary';
    }
  };

  const getFrequencyBadge = (frequency: string) => {
    switch (frequency) {
      case 'critical_only': return 'bg-danger';
      case 'high_only': return 'bg-warning';
      case 'medium_and_above': return 'bg-info';
      case 'all': return 'bg-success';
      default: return 'bg-secondary';
    }
  };

  if (!currentUser) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-warning" role="alert">
          <h4 className="alert-heading">Authentication Required</h4>
          <p>You must be logged in to access Notification Configuration Management.</p>
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
          <p>You need administrator privileges to access Notification Configuration Management.</p>
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
            <h2>Notification Configuration Management</h2>
            <div>
              <button
                className="btn btn-info me-2"
                onClick={handleSendManual}
              >
                Send Manual Notification
              </button>
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
      </div>

      {/* Statistics Cards */}
      {stats && (
        <div className="row mb-4">
          <div className="col-md-2">
            <div className="card text-center">
              <div className="card-body">
                <h5 className="card-title">{stats.configStats.statistics.total}</h5>
                <p className="card-text">Total Configs</p>
              </div>
            </div>
          </div>
          <div className="col-md-2">
            <div className="card text-center text-success">
              <div className="card-body">
                <h5 className="card-title">{stats.configStats.statistics.active}</h5>
                <p className="card-text">Active</p>
              </div>
            </div>
          </div>
          <div className="col-md-2">
            <div className="card text-center text-danger">
              <div className="card-body">
                <h5 className="card-title">{stats.configStats.statistics.immediate}</h5>
                <p className="card-text">Immediate</p>
              </div>
            </div>
          </div>
          <div className="col-md-2">
            <div className="card text-center text-success">
              <div className="card-body">
                <h5 className="card-title">{stats.emailStats.totalSent}</h5>
                <p className="card-text">Emails Sent</p>
              </div>
            </div>
          </div>
          <div className="col-md-2">
            <div className="card text-center text-danger">
              <div className="card-body">
                <h5 className="card-title">{stats.emailStats.totalFailed}</h5>
                <p className="card-text">Failed</p>
              </div>
            </div>
          </div>
          <div className="col-md-2">
            <div className="card text-center text-info">
              <div className="card-body">
                <h5 className="card-title">{stats.emailStats.successRate.toFixed(1)}%</h5>
                <p className="card-text">Success Rate</p>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Filter Section */}
      <div className="row mb-4">
        <div className="col-md-6">
          <input
            type="text"
            className="form-control"
            placeholder="Search configurations..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>
        <div className="col-md-6">
          <div className="form-check">
            <input
              className="form-check-input"
              type="checkbox"
              id="showActiveOnly"
              checked={showActiveOnly}
              onChange={(e) => setShowActiveOnly(e.target.checked)}
            />
            <label className="form-check-label" htmlFor="showActiveOnly">
              Show active configurations only
            </label>
          </div>
        </div>
      </div>

      {showForm && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-body">
                <h5 className="card-title">{editingConfig ? 'Edit Notification Configuration' : 'Add New Notification Configuration'}</h5>
                <form onSubmit={handleSubmit}>
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
                          placeholder="High Priority Alerts"
                        />
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="recipientEmails" className="form-label">Recipient Emails *</label>
                        <input
                          type="text"
                          className="form-control"
                          id="recipientEmails"
                          name="recipientEmails"
                          value={formData.recipientEmails}
                          onChange={handleInputChange}
                          required
                          placeholder="admin@company.com, security@company.com"
                        />
                        <small className="form-text text-muted">Comma-separated email addresses</small>
                      </div>
                    </div>
                  </div>

                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="notificationTiming" className="form-label">Notification Timing</label>
                        <select
                          className="form-select"
                          id="notificationTiming"
                          name="notificationTiming"
                          value={formData.notificationTiming}
                          onChange={handleInputChange}
                        >
                          <option value="immediate">Immediate</option>
                          <option value="daily">Daily Digest</option>
                          <option value="weekly">Weekly Summary</option>
                          <option value="monthly">Monthly Report</option>
                        </select>
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="notificationFrequency" className="form-label">Notification Frequency</label>
                        <select
                          className="form-select"
                          id="notificationFrequency"
                          name="notificationFrequency"
                          value={formData.notificationFrequency}
                          onChange={handleInputChange}
                        >
                          <option value="all">All Risk Levels</option>
                          <option value="medium_and_above">Medium and Above</option>
                          <option value="high_only">High Priority Only</option>
                          <option value="critical_only">Critical Only</option>
                        </select>
                      </div>
                    </div>
                  </div>

                  <div className="mb-3">
                    <label htmlFor="description" className="form-label">Description</label>
                    <textarea
                      className="form-control"
                      id="description"
                      name="description"
                      value={formData.description}
                      onChange={handleInputChange}
                      rows={3}
                      placeholder="Description of this notification configuration..."
                    />
                  </div>

                  <div className="mb-3">
                    <label htmlFor="conditions" className="form-label">Conditions (JSON)</label>
                    <textarea
                      className="form-control"
                      id="conditions"
                      name="conditions"
                      value={formData.conditions}
                      onChange={handleInputChange}
                      rows={4}
                      placeholder='{"category": "Security", "impact": "HIGH"}'
                    />
                    <small className="form-text text-muted">Optional JSON conditions to filter risk assessments</small>
                  </div>

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
                      Active Configuration
                    </label>
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
              <h5 className="card-title">Notification Configurations ({filteredConfigs.length})</h5>
              {filteredConfigs.length === 0 ? (
                <p className="text-muted">No notification configurations found. Click "Add New Configuration" to create one.</p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped table-hover">
                    <thead>
                      <tr>
                        <th>Name</th>
                        <th>Recipients</th>
                        <th>Timing</th>
                        <th>Frequency</th>
                        <th>Status</th>
                        <th>Updated</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredConfigs.map((config) => (
                        <tr key={config.id}>
                          <td>
                            <strong>{config.name}</strong>
                            {config.description && (
                              <small className="d-block text-muted">{config.description}</small>
                            )}
                          </td>
                          <td>
                            <small>{config.recipientEmails}</small>
                          </td>
                          <td>
                            <span className={`badge ${getTimingBadge(config.notificationTiming)}`}>
                              {config.notificationTiming}
                            </span>
                          </td>
                          <td>
                            <span className={`badge ${getFrequencyBadge(config.notificationFrequency)}`}>
                              {config.notificationFrequency.replace('_', ' ')}
                            </span>
                          </td>
                          <td>
                            <span className={`badge ${config.isActive ? 'bg-success' : 'bg-secondary'}`}>
                              {config.isActive ? 'Active' : 'Inactive'}
                            </span>
                          </td>
                          <td>
                            {config.updatedAt
                              ? new Date(config.updatedAt).toLocaleDateString()
                              : '-'
                            }
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
                              >
                                Test
                              </button>
                              {config.isActive ? (
                                <button
                                  onClick={() => config.id && handleDeactivate(config.id)}
                                  className="btn btn-outline-warning mb-1"
                                >
                                  Deactivate
                                </button>
                              ) : (
                                <button
                                  onClick={() => config.id && handleActivate(config.id)}
                                  className="btn btn-outline-success mb-1"
                                >
                                  Activate
                                </button>
                              )}
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
            <div className={`alert ${error.includes('successfully') || error.includes('successful') ? 'alert-success' : 'alert-danger'}`} role="alert">
              {error}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default NotificationConfigManagement;