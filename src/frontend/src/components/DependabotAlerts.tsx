import React, { useEffect, useMemo, useState } from 'react';
import {
  getDependabotAlerts,
  severityRank,
  severityBadgeClass,
  type DependabotAlert,
} from '../services/dependabotAlertsService';
import { hasVulnAccess } from '../utils/auth';

/**
 * Read-only view of GitHub Dependabot alerts ingested into secman
 * (via the CLI `query dependabot-alerts` → POST /api/dependabot-alerts/import).
 * Linked from the Vulnerability Management sidebar section.
 */
const DependabotAlerts: React.FC = () => {
  const [alerts, setAlerts] = useState<DependabotAlert[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [stateFilter, setStateFilter] = useState<string>('open');

  useEffect(() => {
    if (!hasVulnAccess()) {
      setError('You do not have permission to view Dependabot alerts.');
      setLoading(false);
      return;
    }
    getDependabotAlerts()
      .then((data) => {
        setAlerts(data);
        setError(null);
      })
      .catch((err) => setError(err instanceof Error ? err.message : 'Failed to load alerts'))
      .finally(() => setLoading(false));
  }, []);

  const states = useMemo(
    () => Array.from(new Set(alerts.map((a) => a.state))).sort(),
    [alerts]
  );

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return alerts
      .filter((a) => stateFilter === 'all' || a.state === stateFilter)
      .filter((a) => {
        if (!term) return true;
        return (
          a.packageName.toLowerCase().includes(term) ||
          a.repository.toLowerCase().includes(term) ||
          (a.cveId ?? '').toLowerCase().includes(term) ||
          (a.ghsaId ?? '').toLowerCase().includes(term) ||
          (a.summary ?? '').toLowerCase().includes(term)
        );
      })
      .sort(
        (x, y) =>
          severityRank(y.severity) - severityRank(x.severity) ||
          x.repository.localeCompare(y.repository) ||
          y.alertNumber - x.alertNumber
      );
  }, [alerts, search, stateFilter]);

  const openCount = useMemo(() => alerts.filter((a) => a.state === 'open').length, [alerts]);

  return (
    <div className="container-fluid py-4">
      <div className="d-flex justify-content-between align-items-center mb-4">
        <div>
          <h1 className="h3 mb-1">
            <i className="bi bi-github me-2"></i>
            Dependabot Alerts
          </h1>
          <p className="text-muted mb-0">
            Dependency vulnerabilities reported by GitHub Dependabot and ingested into secman.
          </p>
        </div>
        <div className="text-end">
          <div className="fw-semibold fs-4">{openCount}</div>
          <div className="text-muted small">open</div>
        </div>
      </div>

      {error && (
        <div className="alert alert-danger alert-dismissible fade show" role="alert">
          <i className="bi bi-exclamation-triangle me-2"></i>
          {error}
          <button type="button" className="btn-close" onClick={() => setError(null)} aria-label="Close"></button>
        </div>
      )}

      <div className="row g-2 mb-3">
        <div className="col-md-8">
          <div className="input-group">
            <span className="input-group-text">
              <i className="bi bi-search"></i>
            </span>
            <input
              type="text"
              className="form-control"
              placeholder="Search package, repository, CVE, GHSA, or summary..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            {search && (
              <button className="btn btn-outline-secondary" type="button" onClick={() => setSearch('')} title="Clear">
                <i className="bi bi-x-lg"></i>
              </button>
            )}
          </div>
        </div>
        <div className="col-md-4">
          <select
            className="form-select"
            value={stateFilter}
            onChange={(e) => setStateFilter(e.target.value)}
            aria-label="Filter by state"
          >
            <option value="all">All states</option>
            {states.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="card">
        <div className="card-body">
          <h5 className="card-title">Alerts ({filtered.length})</h5>
          <div className="table-responsive">
            <table className="table table-striped table-hover align-middle">
              <thead>
                <tr>
                  <th>Severity</th>
                  <th>Repository</th>
                  <th>Package</th>
                  <th>Ecosystem</th>
                  <th>Advisory</th>
                  <th>Vulnerable range</th>
                  <th>Patched</th>
                  <th>State</th>
                  <th>Updated</th>
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr>
                    <td colSpan={9} className="text-center py-4">
                      <span className="spinner-border spinner-border-sm me-2"></span>
                      Loading alerts...
                    </td>
                  </tr>
                ) : filtered.length === 0 ? (
                  <tr>
                    <td colSpan={9} className="text-center py-4 text-muted">
                      No Dependabot alerts found.
                    </td>
                  </tr>
                ) : (
                  filtered.map((a) => (
                    <tr key={a.id}>
                      <td>
                        <span className={`badge ${severityBadgeClass(a.severity)}`}>
                          {a.severity?.toUpperCase()}
                        </span>
                      </td>
                      <td className="text-nowrap">{a.repository}</td>
                      <td className="fw-semibold">
                        {a.packageName}
                        {a.manifestPath && (
                          <div className="text-muted small">{a.manifestPath}</div>
                        )}
                      </td>
                      <td>{a.ecosystem}</td>
                      <td>
                        {a.htmlUrl ? (
                          <a href={a.htmlUrl} target="_blank" rel="noopener noreferrer">
                            {a.cveId || a.ghsaId || 'advisory'}
                          </a>
                        ) : (
                          a.cveId || a.ghsaId || '—'
                        )}
                        {a.summary && <div className="text-muted small">{a.summary}</div>}
                      </td>
                      <td className="text-nowrap">{a.vulnerableVersionRange || '—'}</td>
                      <td className="text-nowrap">{a.firstPatchedVersion || '—'}</td>
                      <td>
                        <span className="badge bg-light text-dark border">{a.state}</span>
                      </td>
                      <td className="text-nowrap text-muted small">
                        {a.alertUpdatedAt ? new Date(a.alertUpdatedAt).toLocaleDateString() : '—'}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
};

export default DependabotAlerts;
