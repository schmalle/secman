import React, { useState, useEffect } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

interface TestEmailAccount {
  id?: number;
  name: string;
  email: string;
  provider: 'GMAIL' | 'OUTLOOK' | 'YAHOO' | 'CUSTOM';
  credentials: string;
  smtpHost?: string;
  smtpPort?: number;
  imapHost?: string;
  imapPort?: number;
  description?: string;
  status: 'VERIFICATION_PENDING' | 'TESTING' | 'VERIFIED' | 'VERIFICATION_FAILED';
  lastTestedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

interface TestEmailAccountStats {
  total: number;
  verified: number;
  pending: number;
  failed: number;
  providerDistribution: Record<string, number>;
}

const TestEmailAccountManagement: React.FC = () => {
  const [accounts, setAccounts] = useState<TestEmailAccount[]>([]);
  const [stats, setStats] = useState<TestEmailAccountStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingAccount, setEditingAccount] = useState<TestEmailAccount | null>(null);
  const [testing, setTesting] = useState<number | null>(null);
  const [sending, setSending] = useState<number | null>(null);
  const [filterStatus, setFilterStatus] = useState<string>('');
  const [filterProvider, setFilterProvider] = useState<string>('');

  const [formData, setFormData] = useState<TestEmailAccount>({
    name: '',
    email: '',
    provider: 'GMAIL',
    credentials: '',
    smtpHost: '',
    smtpPort: 587,
    imapHost: '',
    imapPort: 993,
    description: '',
    status: 'VERIFICATION_PENDING'
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
          setError('Access denied. Admin privileges required to manage test email accounts.');
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
          setError('Access denied. Admin privileges required to manage test email accounts.');
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
      fetchAccounts();
      fetchStats();
    }
  }, [currentUser, hasAdminAccess, filterStatus, filterProvider]);

  const fetchAccounts = async () => {
    try {
      let url = '/api/test-email-accounts';
      const params = new URLSearchParams();

      if (filterStatus) params.append('status', filterStatus);
      if (filterProvider) params.append('provider', filterProvider);

      if (params.toString()) {
        url += '?' + params.toString();
      }

      const response = await authenticatedGet(url);
      if (response.ok) {
        const data = await response.json();
        setAccounts(data);
      } else {
        setError(`Failed to fetch test email accounts: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const fetchStats = async () => {
    try {
      const response = await authenticatedGet('/api/test-email-accounts/stats');
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
      if (editingAccount) {
        await authenticatedPut(`/api/test-email-accounts/${editingAccount.id}`, formData);
      } else {
        await authenticatedPost('/api/test-email-accounts', formData);
      }

      await fetchAccounts();
      await fetchStats();
      resetForm();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleEdit = (account: TestEmailAccount) => {
    setEditingAccount(account);
    setFormData({ ...account, credentials: '' }); // Don't show credentials
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this test email account?')) {
      return;
    }

    try {
      await authenticatedDelete(`/api/test-email-accounts/${id}`);
      await fetchAccounts();
      await fetchStats();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleTest = async (id: number) => {
    setTesting(id);
    try {
      const response = await authenticatedPost(`/api/test-email-accounts/${id}/test`, {});
      if (response.ok) {
        const result = await response.json();
        setError(result.success ? 'Connection test successful!' : 'Connection test failed');
        setTimeout(() => setError(null), 3000);
        await fetchAccounts();
      } else {
        setError('Connection test failed');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to test connection');
    } finally {
      setTesting(null);
    }
  };

  const handleSendTest = async (id: number) => {
    const testEmail = prompt('Enter email address to send test email to:');
    if (!testEmail) return;

    setSending(id);
    try {
      const response = await authenticatedPost(`/api/test-email-accounts/${id}/send-test`, {
        toEmail: testEmail
      });

      if (response.ok) {
        const result = await response.json();
        setError(result.success ? 'Test email sent successfully!' : 'Failed to send test email');
        setTimeout(() => setError(null), 3000);
      } else {
        setError('Failed to send test email');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to send test email');
    } finally {
      setSending(null);
    }
  };

  const resetForm = () => {
    setFormData({
      name: '',
      email: '',
      provider: 'GMAIL',
      credentials: '',
      smtpHost: '',
      smtpPort: 587,
      imapHost: '',
      imapPort: 993,
      description: '',
      status: 'VERIFICATION_PENDING'
    });
    setEditingAccount(null);
    setShowForm(false);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const { name, value, type } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: type === 'number' ? parseInt(value) || 0 : value
    }));
  };

  const getProviderDefaults = (provider: string) => {
    switch (provider) {
      case 'GMAIL':
        return { smtpHost: 'smtp.gmail.com', smtpPort: 587, imapHost: 'imap.gmail.com', imapPort: 993 };
      case 'OUTLOOK':
        return { smtpHost: 'smtp-mail.outlook.com', smtpPort: 587, imapHost: 'outlook.office365.com', imapPort: 993 };
      case 'YAHOO':
        return { smtpHost: 'smtp.mail.yahoo.com', smtpPort: 587, imapHost: 'imap.mail.yahoo.com', imapPort: 993 };
      default:
        return { smtpHost: '', smtpPort: 587, imapHost: '', imapPort: 993 };
    }
  };

  const handleProviderChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const provider = e.target.value;
    const defaults = getProviderDefaults(provider);
    setFormData(prev => ({
      ...prev,
      provider: provider as any,
      ...defaults
    }));
  };

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'VERIFIED': return 'bg-success';
      case 'VERIFICATION_PENDING': return 'bg-warning';
      case 'TESTING': return 'bg-info';
      case 'VERIFICATION_FAILED': return 'bg-danger';
      default: return 'bg-secondary';
    }
  };

  if (!currentUser) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-warning" role="alert">
          <h4 className="alert-heading">Authentication Required</h4>
          <p>You must be logged in to access Test Email Account Management.</p>
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
          <p>You need administrator privileges to access Test Email Account Management.</p>
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
            <h2>Test Email Account Management</h2>
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
              {showForm ? 'Cancel' : 'Add New Account'}
            </button>
          </div>
        </div>
      </div>

      {/* Statistics Cards */}
      {stats && (
        <div className="row mb-4">
          <div className="col-md-3">
            <div className="card text-center">
              <div className="card-body">
                <h5 className="card-title">{stats.total}</h5>
                <p className="card-text">Total Accounts</p>
              </div>
            </div>
          </div>
          <div className="col-md-3">
            <div className="card text-center text-success">
              <div className="card-body">
                <h5 className="card-title">{stats.verified}</h5>
                <p className="card-text">Verified</p>
              </div>
            </div>
          </div>
          <div className="col-md-3">
            <div className="card text-center text-warning">
              <div className="card-body">
                <h5 className="card-title">{stats.pending}</h5>
                <p className="card-text">Pending</p>
              </div>
            </div>
          </div>
          <div className="col-md-3">
            <div className="card text-center text-danger">
              <div className="card-body">
                <h5 className="card-title">{stats.failed}</h5>
                <p className="card-text">Failed</p>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Filter Section */}
      <div className="row mb-4">
        <div className="col-md-6">
          <select
            className="form-select"
            value={filterStatus}
            onChange={(e) => setFilterStatus(e.target.value)}
          >
            <option value="">All Statuses</option>
            <option value="VERIFIED">Verified</option>
            <option value="VERIFICATION_PENDING">Pending</option>
            <option value="TESTING">Testing</option>
            <option value="VERIFICATION_FAILED">Failed</option>
          </select>
        </div>
        <div className="col-md-6">
          <select
            className="form-select"
            value={filterProvider}
            onChange={(e) => setFilterProvider(e.target.value)}
          >
            <option value="">All Providers</option>
            <option value="GMAIL">Gmail</option>
            <option value="OUTLOOK">Outlook</option>
            <option value="YAHOO">Yahoo</option>
            <option value="CUSTOM">Custom</option>
          </select>
        </div>
      </div>

      {showForm && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-body">
                <h5 className="card-title">{editingAccount ? 'Edit Test Email Account' : 'Add New Test Email Account'}</h5>
                <form onSubmit={handleSubmit}>
                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="name" className="form-label">Account Name *</label>
                        <input
                          type="text"
                          className="form-control"
                          id="name"
                          name="name"
                          value={formData.name}
                          onChange={handleInputChange}
                          required
                          placeholder="Test Account 1"
                        />
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="email" className="form-label">Email Address *</label>
                        <input
                          type="email"
                          className="form-control"
                          id="email"
                          name="email"
                          value={formData.email}
                          onChange={handleInputChange}
                          required
                          placeholder="test@example.com"
                        />
                      </div>
                    </div>
                  </div>

                  <div className="row">
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="provider" className="form-label">Email Provider *</label>
                        <select
                          className="form-select"
                          id="provider"
                          name="provider"
                          value={formData.provider}
                          onChange={handleProviderChange}
                          required
                        >
                          <option value="GMAIL">Gmail</option>
                          <option value="OUTLOOK">Outlook</option>
                          <option value="YAHOO">Yahoo</option>
                          <option value="CUSTOM">Custom SMTP/IMAP</option>
                        </select>
                      </div>
                    </div>
                    <div className="col-md-6">
                      <div className="mb-3">
                        <label htmlFor="credentials" className="form-label">Password/App Password *</label>
                        <input
                          type="password"
                          className="form-control"
                          id="credentials"
                          name="credentials"
                          value={formData.credentials}
                          onChange={handleInputChange}
                          required={!editingAccount}
                          placeholder={editingAccount ? "***HIDDEN***" : "Password or App Password"}
                        />
                      </div>
                    </div>
                  </div>

                  <div className="row">
                    <div className="col-md-3">
                      <div className="mb-3">
                        <label htmlFor="smtpHost" className="form-label">SMTP Host</label>
                        <input
                          type="text"
                          className="form-control"
                          id="smtpHost"
                          name="smtpHost"
                          value={formData.smtpHost}
                          onChange={handleInputChange}
                          placeholder="smtp.gmail.com"
                        />
                      </div>
                    </div>
                    <div className="col-md-3">
                      <div className="mb-3">
                        <label htmlFor="smtpPort" className="form-label">SMTP Port</label>
                        <input
                          type="number"
                          className="form-control"
                          id="smtpPort"
                          name="smtpPort"
                          value={formData.smtpPort}
                          onChange={handleInputChange}
                        />
                      </div>
                    </div>
                    <div className="col-md-3">
                      <div className="mb-3">
                        <label htmlFor="imapHost" className="form-label">IMAP Host</label>
                        <input
                          type="text"
                          className="form-control"
                          id="imapHost"
                          name="imapHost"
                          value={formData.imapHost}
                          onChange={handleInputChange}
                          placeholder="imap.gmail.com"
                        />
                      </div>
                    </div>
                    <div className="col-md-3">
                      <div className="mb-3">
                        <label htmlFor="imapPort" className="form-label">IMAP Port</label>
                        <input
                          type="number"
                          className="form-control"
                          id="imapPort"
                          name="imapPort"
                          value={formData.imapPort}
                          onChange={handleInputChange}
                        />
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
                      placeholder="Description of this test account..."
                    />
                  </div>

                  <div className="d-flex justify-content-end">
                    <button type="submit" className="btn btn-success me-2">
                      {editingAccount ? 'Update' : 'Save'}
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
              <h5 className="card-title">Test Email Accounts ({accounts.length})</h5>
              {accounts.length === 0 ? (
                <p className="text-muted">No test email accounts found. Click "Add New Account" to create one.</p>
              ) : (
                <div className="table-responsive">
                  <table className="table table-striped table-hover">
                    <thead>
                      <tr>
                        <th>Name</th>
                        <th>Email</th>
                        <th>Provider</th>
                        <th>Status</th>
                        <th>Last Tested</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {accounts.map((account) => (
                        <tr key={account.id}>
                          <td>
                            <strong>{account.name}</strong>
                            {account.description && (
                              <small className="d-block text-muted">{account.description}</small>
                            )}
                          </td>
                          <td>{account.email}</td>
                          <td>
                            <span className="badge bg-info">{account.provider}</span>
                          </td>
                          <td>
                            <span className={`badge ${getStatusBadge(account.status)}`}>
                              {account.status.replace('_', ' ')}
                            </span>
                          </td>
                          <td>
                            {account.lastTestedAt
                              ? new Date(account.lastTestedAt).toLocaleString()
                              : 'Never'
                            }
                          </td>
                          <td>
                            <div className="btn-group-vertical btn-group-sm" role="group">
                              <button
                                onClick={() => handleEdit(account)}
                                className="btn btn-outline-primary mb-1"
                              >
                                Edit
                              </button>
                              <button
                                onClick={() => account.id && handleTest(account.id)}
                                className="btn btn-outline-info mb-1"
                                disabled={testing === account.id}
                              >
                                {testing === account.id ? 'Testing...' : 'Test Connection'}
                              </button>
                              <button
                                onClick={() => account.id && handleSendTest(account.id)}
                                className="btn btn-outline-success mb-1"
                                disabled={sending === account.id || account.status !== 'VERIFIED'}
                              >
                                {sending === account.id ? 'Sending...' : 'Send Test Email'}
                              </button>
                              <button
                                onClick={() => account.id && handleDelete(account.id)}
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

export default TestEmailAccountManagement;