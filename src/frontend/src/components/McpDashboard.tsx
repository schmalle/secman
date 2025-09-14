import React, { useState, useEffect } from 'react';

interface SessionStats {
  totalActive: number;
  recentlyActive: number;
  byConnectionType: Record<string, number>;
  utilizationPercent: number;
}

interface SystemStats {
  sessions: SessionStats;
  apiKeys: {
    totalActive: number;
    totalInactive: number;
    expiringSoon: number;
    recentlyUsed: number;
  };
  tools: {
    totalCalls: number;
    successRate: number;
    topTools: Array<{ name: string; calls: number }>;
    averageResponseTime: number;
  };
  security: {
    authFailures: number;
    permissionDenials: number;
    rateLimitHits: number;
    suspiciousActivity: number;
  };
}

interface RecentActivity {
  id: number;
  timestamp: string;
  eventType: string;
  toolName?: string;
  success: boolean;
  clientIp?: string;
  errorMessage?: string;
}

const McpDashboard: React.FC = () => {
  const [stats, setStats] = useState<SystemStats | null>(null);
  const [recentActivity, setRecentActivity] = useState<RecentActivity[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshInterval, setRefreshInterval] = useState<NodeJS.Timeout | null>(null);

  useEffect(() => {
    fetchDashboardData();

    // Auto-refresh every 30 seconds
    const interval = setInterval(fetchDashboardData, 30000);
    setRefreshInterval(interval);

    return () => {
      if (refreshInterval) clearInterval(refreshInterval);
    };
  }, []);

  const fetchDashboardData = async () => {
    try {
      const token = localStorage.getItem('authToken');

      // Fetch system statistics
      const statsResponse = await fetch('/api/mcp/admin/statistics', {
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        }
      });

      if (statsResponse.ok) {
        const statsData = await statsResponse.json();
        setStats(statsData);
      }

      // Fetch recent activity
      const activityResponse = await fetch('/api/mcp/admin/audit-logs?pageSize=10', {
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        }
      });

      if (activityResponse.ok) {
        const activityData = await activityResponse.json();
        setRecentActivity(activityData.logs || []);
      }

      setError(null);
    } catch (err) {
      setError('Failed to load dashboard data');
    } finally {
      setLoading(false);
    }
  };

  const getEventIcon = (eventType: string) => {
    switch (eventType) {
      case 'AUTH_SUCCESS':
        return '🔐';
      case 'AUTH_FAILURE':
        return '❌';
      case 'TOOL_CALL':
        return '🛠️';
      case 'SESSION_CREATED':
        return '🔗';
      case 'SESSION_CLOSED':
        return '🔚';
      case 'PERMISSION_DENIED':
        return '⛔';
      default:
        return '📝';
    }
  };

  const formatEventType = (eventType: string) => {
    return eventType.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, l => l.toUpperCase());
  };

  const getHealthStatus = (stats: SystemStats) => {
    const utilizationLevel = stats.sessions.utilizationPercent;
    const errorRate = stats.security.authFailures + stats.security.permissionDenials;

    if (utilizationLevel > 90 || errorRate > 100) {
      return { status: 'Critical', className: 'text-danger', icon: '🔴' };
    } else if (utilizationLevel > 70 || errorRate > 50) {
      return { status: 'Warning', className: 'text-warning', icon: '🟡' };
    } else {
      return { status: 'Healthy', className: 'text-success', icon: '🟢' };
    }
  };

  if (loading) {
    return (
      <div className="container mt-4">
        <div className="d-flex justify-content-center">
          <div className="spinner-border" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
        </div>
      </div>
    );
  }

  if (!stats) {
    return (
      <div className="container mt-4">
        <div className="alert alert-danger">
          {error || 'Failed to load dashboard data'}
        </div>
      </div>
    );
  }

  const healthStatus = getHealthStatus(stats);

  return (
    <div className="container mt-4">
      <div className="d-flex justify-content-between align-items-center mb-4">
        <h2>MCP Dashboard</h2>
        <div className="d-flex align-items-center">
          <span className={`me-3 ${healthStatus.className}`}>
            {healthStatus.icon} System {healthStatus.status}
          </span>
          <button className="btn btn-outline-primary btn-sm" onClick={fetchDashboardData}>
            Refresh
          </button>
        </div>
      </div>

      {error && (
        <div className="alert alert-warning alert-dismissible fade show" role="alert">
          {error}
          <button
            type="button"
            className="btn-close"
            onClick={() => setError(null)}
          ></button>
        </div>
      )}

      {/* Key Metrics Row */}
      <div className="row mb-4">
        <div className="col-md-3">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">Active Sessions</h5>
              <h3 className="text-primary">{stats.sessions.totalActive}</h3>
              <small className="text-muted">
                {stats.sessions.utilizationPercent.toFixed(1)}% capacity
              </small>
            </div>
          </div>
        </div>

        <div className="col-md-3">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">Active API Keys</h5>
              <h3 className="text-success">{stats.apiKeys.totalActive}</h3>
              <small className="text-muted">
                {stats.apiKeys.expiringSoon} expiring soon
              </small>
            </div>
          </div>
        </div>

        <div className="col-md-3">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">Tool Calls</h5>
              <h3 className="text-info">{stats.tools.totalCalls}</h3>
              <small className="text-muted">
                {stats.tools.successRate.toFixed(1)}% success rate
              </small>
            </div>
          </div>
        </div>

        <div className="col-md-3">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">Security Events</h5>
              <h3 className={stats.security.authFailures > 10 ? 'text-danger' : 'text-warning'}>
                {stats.security.authFailures + stats.security.permissionDenials}
              </h3>
              <small className="text-muted">Last 24 hours</small>
            </div>
          </div>
        </div>
      </div>

      {/* Charts Row */}
      <div className="row mb-4">
        <div className="col-md-6">
          <div className="card">
            <div className="card-header">
              <h5>Connection Types</h5>
            </div>
            <div className="card-body">
              {Object.entries(stats.sessions.byConnectionType).map(([type, count]) => (
                <div key={type} className="d-flex justify-content-between align-items-center mb-2">
                  <span>{type}</span>
                  <span className="badge bg-primary">{count}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className="col-md-6">
          <div className="card">
            <div className="card-header">
              <h5>Top Tools</h5>
            </div>
            <div className="card-body">
              {stats.tools.topTools.length > 0 ? (
                stats.tools.topTools.map((tool, index) => (
                  <div key={tool.name} className="d-flex justify-content-between align-items-center mb-2">
                    <span>#{index + 1} {tool.name}</span>
                    <span className="badge bg-info">{tool.calls} calls</span>
                  </div>
                ))
              ) : (
                <p className="text-muted">No tool usage data available</p>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Security Overview */}
      <div className="row mb-4">
        <div className="col-12">
          <div className="card">
            <div className="card-header">
              <h5>Security Overview</h5>
            </div>
            <div className="card-body">
              <div className="row">
                <div className="col-md-3 text-center">
                  <h4 className="text-danger">{stats.security.authFailures}</h4>
                  <small>Auth Failures</small>
                </div>
                <div className="col-md-3 text-center">
                  <h4 className="text-warning">{stats.security.permissionDenials}</h4>
                  <small>Permission Denials</small>
                </div>
                <div className="col-md-3 text-center">
                  <h4 className="text-info">{stats.security.rateLimitHits}</h4>
                  <small>Rate Limit Hits</small>
                </div>
                <div className="col-md-3 text-center">
                  <h4 className="text-danger">{stats.security.suspiciousActivity}</h4>
                  <small>Suspicious Activity</small>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Recent Activity */}
      <div className="card">
        <div className="card-header">
          <h5>Recent Activity</h5>
        </div>
        <div className="card-body">
          {recentActivity.length > 0 ? (
            <div className="table-responsive">
              <table className="table table-sm">
                <thead>
                  <tr>
                    <th>Time</th>
                    <th>Event</th>
                    <th>Tool</th>
                    <th>Status</th>
                    <th>Client IP</th>
                  </tr>
                </thead>
                <tbody>
                  {recentActivity.map((activity) => (
                    <tr key={activity.id}>
                      <td>
                        <small>{new Date(activity.timestamp).toLocaleTimeString()}</small>
                      </td>
                      <td>
                        <span className="me-1">{getEventIcon(activity.eventType)}</span>
                        {formatEventType(activity.eventType)}
                      </td>
                      <td>{activity.toolName || '-'}</td>
                      <td>
                        <span className={`badge ${activity.success ? 'bg-success' : 'bg-danger'}`}>
                          {activity.success ? 'Success' : 'Failed'}
                        </span>
                      </td>
                      <td>
                        <small>{activity.clientIp || '-'}</small>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-muted">No recent activity</p>
          )}
        </div>
      </div>
    </div>
  );
};

export default McpDashboard;