import React, { useEffect, useMemo, useState } from 'react';
import { formatServerDate } from '../utils/dateUtils';
import {
  getGithubRepositories,
  updateOwnerEmail,
  triggerGithubImport,
  createRepoAlertException,
  deleteRepoAlertException,
  getGithubRepoAlerts,
  severityRank,
  severityBadgeClass,
  type GithubRepo,
  type GithubRepoAlert,
} from '../services/githubReposService';
import { hasVulnAccess, hasRole } from '../utils/auth';

/**
 * Vulnerability Management → GitHub: repositories accessible via the
 * configured GitHub App with their open high/critical Dependabot alert
 * counts, last import / last finding timestamps, per-repo owner email
 * (the 30-day alert recipient) and alert exceptions.
 *
 * View: ADMIN/VULN/SECCHAMPION. Owner-email edits, imports and exception
 * management: ADMIN/VULN (mirrors the backend @Secured rules).
 */
const GithubRepoManagement: React.FC = () => {
  const [repos, setRepos] = useState<GithubRepo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [importing, setImporting] = useState(false);

  // Inline owner-email editing
  const [editingRepoId, setEditingRepoId] = useState<number | null>(null);
  const [emailDraft, setEmailDraft] = useState('');
  const [savingEmail, setSavingEmail] = useState(false);

  // Exception modal
  const [exceptionRepo, setExceptionRepo] = useState<GithubRepo | null>(null);
  const [exceptionReason, setExceptionReason] = useState('');
  const [exceptionExpiry, setExceptionExpiry] = useState('');
  const [savingException, setSavingException] = useState(false);

  // Per-repo alert expansion
  const [expandedRepoId, setExpandedRepoId] = useState<number | null>(null);
  const [alertsByRepo, setAlertsByRepo] = useState<Record<number, GithubRepoAlert[]>>({});
  const [loadingAlerts, setLoadingAlerts] = useState(false);
  const [alertsError, setAlertsError] = useState<string | null>(null);

  const canManage = hasRole(['ADMIN', 'VULN']);

  const loadRepos = () => {
    getGithubRepositories()
      .then((data) => {
        setRepos(data);
        setError(null);
      })
      .catch((err) => setError(err instanceof Error ? err.message : 'Failed to load repositories'))
      .finally(() => setLoading(false));
  };

  const toggleExpand = (repo: GithubRepo) => {
    if (expandedRepoId === repo.id) {
      setExpandedRepoId(null);
      return;
    }
    setExpandedRepoId(repo.id);
    setAlertsError(null);
    if (alertsByRepo[repo.id]) return;
    setLoadingAlerts(true);
    getGithubRepoAlerts(repo.id)
      .then((data) => {
        setAlertsByRepo((prev) => ({ ...prev, [repo.id]: data }));
      })
      .catch((err) => setAlertsError(err instanceof Error ? err.message : 'Failed to load alerts'))
      .finally(() => setLoadingAlerts(false));
  };

  useEffect(() => {
    if (!hasVulnAccess()) {
      setError('You do not have permission to view GitHub repositories.');
      setLoading(false);
      return;
    }
    loadRepos();
  }, []);

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return repos
      .filter((r) => {
        if (!term) return true;
        return (
          r.fullName.toLowerCase().includes(term) ||
          r.owner.toLowerCase().includes(term) ||
          (r.ownerEmail ?? '').toLowerCase().includes(term)
        );
      })
      .sort(
        (x, y) =>
          y.criticalCount - x.criticalCount ||
          y.highCount - x.highCount ||
          x.fullName.localeCompare(y.fullName)
      );
  }, [repos, search]);

  const totals = useMemo(
    () => ({
      critical: repos.reduce((sum, r) => sum + r.criticalCount, 0),
      high: repos.reduce((sum, r) => sum + r.highCount, 0),
    }),
    [repos]
  );

  const handleImport = async () => {
    setImporting(true);
    setInfo(null);
    setError(null);
    try {
      const result = await triggerGithubImport();
      setInfo(
        `Import complete: ${result.reposDiscovered} repositories (${result.reposNew} new, ` +
          `${result.reposUpdated} updated), ${result.totalCritical} critical / ${result.totalHigh} high open alerts` +
          (result.errors.length > 0 ? ` — ${result.errors.length} errors` : '')
      );
      loadRepos();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Import failed');
    } finally {
      setImporting(false);
    }
  };

  const startEmailEdit = (repo: GithubRepo) => {
    setEditingRepoId(repo.id);
    setEmailDraft(repo.ownerEmail ?? '');
  };

  const saveEmail = async (repo: GithubRepo) => {
    setSavingEmail(true);
    try {
      await updateOwnerEmail(repo.id, emailDraft.trim() || null);
      setEditingRepoId(null);
      loadRepos();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update owner email');
    } finally {
      setSavingEmail(false);
    }
  };

  const openExceptionModal = (repo: GithubRepo) => {
    setExceptionRepo(repo);
    setExceptionReason('');
    setExceptionExpiry('');
  };

  const saveException = async () => {
    if (!exceptionRepo || !exceptionReason.trim()) return;
    setSavingException(true);
    try {
      await createRepoAlertException({
        githubRepositoryId: exceptionRepo.id,
        reason: exceptionReason.trim(),
        expirationDate: exceptionExpiry ? new Date(exceptionExpiry).toISOString() : null,
      });
      setExceptionRepo(null);
      loadRepos();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create exception');
    } finally {
      setSavingException(false);
    }
  };

  const removeException = async (repo: GithubRepo) => {
    if (!repo.activeException) return;
    try {
      await deleteRepoAlertException(repo.activeException.id);
      loadRepos();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete exception');
    }
  };

  return (
    <div className="container-fluid py-4">
      <div className="d-flex justify-content-between align-items-center mb-4">
        <div>
          <h1 className="h3 mb-1">
            <i className="bi bi-github me-2"></i>
            GitHub Repositories
          </h1>
          <p className="text-muted mb-0">
            Repositories accessible via the GitHub App with their open high/critical Dependabot alerts.
          </p>
        </div>
        <div className="d-flex align-items-center gap-3">
          <div className="text-end">
            <div className="fw-semibold fs-4">
              <span className="text-danger">{totals.critical}</span>
              {' / '}
              <span className="text-warning">{totals.high}</span>
            </div>
            <div className="text-muted small">critical / high</div>
          </div>
          {canManage && (
            <button className="btn btn-primary" onClick={handleImport} disabled={importing}>
              {importing ? (
                <>
                  <span className="spinner-border spinner-border-sm me-2"></span>
                  Importing...
                </>
              ) : (
                <>
                  <i className="bi bi-arrow-repeat me-2"></i>
                  Import now
                </>
              )}
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="alert alert-danger alert-dismissible fade show" role="alert">
          <i className="bi bi-exclamation-triangle me-2"></i>
          {error}
          <button type="button" className="btn-close" onClick={() => setError(null)} aria-label="Close"></button>
        </div>
      )}
      {info && (
        <div className="alert alert-success alert-dismissible fade show" role="alert">
          <i className="bi bi-check-circle me-2"></i>
          {info}
          <button type="button" className="btn-close" onClick={() => setInfo(null)} aria-label="Close"></button>
        </div>
      )}

      <div className="row g-2 mb-3">
        <div className="col-md-6">
          <div className="input-group">
            <span className="input-group-text">
              <i className="bi bi-search"></i>
            </span>
            <input
              type="text"
              className="form-control"
              placeholder="Search repository, owner, or email..."
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
      </div>

      <div className="card">
        <div className="card-body">
          <h5 className="card-title">Repositories ({filtered.length})</h5>
          <div className="table-responsive">
            <table className="table table-striped table-hover align-middle">
              <thead>
                <tr>
                  <th style={{ width: '2.5rem' }}></th>
                  <th>Repository</th>
                  <th>Owner</th>
                  <th className="text-center">Critical</th>
                  <th className="text-center">High</th>
                  <th>Last import</th>
                  <th>Last high/critical finding</th>
                  <th>Owner email</th>
                  <th>Exception</th>
                  {canManage && <th></th>}
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr>
                    <td colSpan={canManage ? 10 : 9} className="text-center py-4">
                      <span className="spinner-border spinner-border-sm me-2"></span>
                      Loading repositories...
                    </td>
                  </tr>
                ) : filtered.length === 0 ? (
                  <tr>
                    <td colSpan={canManage ? 10 : 9} className="text-center py-4 text-muted">
                      No GitHub repositories imported yet.
                      {canManage && ' Configure the GitHub App under Admin → GitHub App and press "Import now".'}
                    </td>
                  </tr>
                ) : (
                  filtered.map((r) => (
                    <React.Fragment key={r.id}>
                      <tr>
                        <td>
                          <button
                            className="btn btn-link btn-sm p-0"
                            onClick={() => toggleExpand(r)}
                            title={expandedRepoId === r.id ? 'Collapse alerts' : 'Show alerts'}
                          >
                            <i className={`bi bi-chevron-${expandedRepoId === r.id ? 'down' : 'right'}`}></i>
                          </button>
                        </td>
                        <td className="fw-semibold">
                          {r.htmlUrl ? (
                            <a href={r.htmlUrl} target="_blank" rel="noopener noreferrer">
                              {r.name}
                            </a>
                          ) : (
                            r.name
                          )}
                          {r.archived && (
                            <span className="badge bg-secondary ms-2" title="Archived on GitHub">
                              archived
                            </span>
                          )}
                        </td>
                        <td className="text-nowrap">{r.owner}</td>
                        <td className="text-center">
                          <span className={`badge ${r.criticalCount > 0 ? 'bg-danger' : 'bg-light text-dark border'}`}>
                            {r.criticalCount}
                          </span>
                        </td>
                        <td className="text-center">
                          <span className={`badge ${r.highCount > 0 ? 'bg-warning text-dark' : 'bg-light text-dark border'}`}>
                            {r.highCount}
                          </span>
                        </td>
                        <td className="text-nowrap text-muted small">
                          {formatServerDate(r.lastImportAt, undefined, '—')}
                        </td>
                        <td className="text-nowrap text-muted small">
                          {formatServerDate(r.lastHighCriticalFindingAt, undefined, '—')}
                        </td>
                        <td>
                          {editingRepoId === r.id ? (
                            <div className="input-group input-group-sm" style={{ minWidth: '220px' }}>
                              <input
                                type="email"
                                className="form-control"
                                value={emailDraft}
                                placeholder="owner@example.com"
                                onChange={(e) => setEmailDraft(e.target.value)}
                                disabled={savingEmail}
                              />
                              <button
                                className="btn btn-success"
                                onClick={() => saveEmail(r)}
                                disabled={savingEmail}
                                title="Save"
                              >
                                <i className="bi bi-check-lg"></i>
                              </button>
                              <button
                                className="btn btn-outline-secondary"
                                onClick={() => setEditingRepoId(null)}
                                disabled={savingEmail}
                                title="Cancel"
                              >
                                <i className="bi bi-x-lg"></i>
                              </button>
                            </div>
                          ) : (
                            <>
                              {r.ownerEmail || <span className="text-muted">unmapped</span>}
                              {canManage && (
                                <button
                                  className="btn btn-link btn-sm p-0 ms-2"
                                  onClick={() => startEmailEdit(r)}
                                  title="Edit owner email"
                                >
                                  <i className="bi bi-pencil"></i>
                                </button>
                              )}
                            </>
                          )}
                        </td>
                        <td>
                          {r.activeException ? (
                            <span
                              className="badge bg-info text-dark"
                              title={`${r.activeException.reason} (by ${r.activeException.createdBy})`}
                            >
                              Excepted
                              {r.activeException.expirationDate &&
                                ` until ${formatServerDate(r.activeException.expirationDate, undefined, '')}`}
                            </span>
                          ) : (
                            <span className="text-muted">—</span>
                          )}
                        </td>
                        {canManage && (
                          <td className="text-nowrap">
                            {r.activeException ? (
                              <button
                                className="btn btn-outline-danger btn-sm"
                                onClick={() => removeException(r)}
                                title="Remove alert exception"
                              >
                                <i className="bi bi-x-circle me-1"></i>
                                Remove exception
                              </button>
                            ) : (
                              <button
                                className="btn btn-outline-secondary btn-sm"
                                onClick={() => openExceptionModal(r)}
                                title="Except this repository from the 30-day alert"
                              >
                                <i className="bi bi-shield-slash me-1"></i>
                                Except
                              </button>
                            )}
                          </td>
                        )}
                      </tr>
                      {expandedRepoId === r.id && (
                        <tr>
                          <td></td>
                          <td colSpan={canManage ? 9 : 8} className="bg-light">
                            {loadingAlerts && !alertsByRepo[r.id] ? (
                              <div className="py-2">
                                <span className="spinner-border spinner-border-sm me-2"></span>
                                Loading alerts...
                              </div>
                            ) : alertsError ? (
                              <div className="text-danger py-2">{alertsError}</div>
                            ) : (alertsByRepo[r.id] ?? []).length === 0 ? (
                              <div className="text-muted py-2">No open Dependabot alerts for this repository.</div>
                            ) : (
                              <table className="table table-sm table-borderless mb-0">
                                <thead>
                                  <tr className="text-muted small">
                                    <th>Severity</th>
                                    <th>Package</th>
                                    <th>Ecosystem</th>
                                    <th>Advisory</th>
                                    <th>Vulnerable range</th>
                                    <th>Patched</th>
                                    <th>Updated</th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {[...(alertsByRepo[r.id] ?? [])]
                                    .sort((x, y) => severityRank(y.severity) - severityRank(x.severity))
                                    .map((a) => (
                                      <tr key={a.id}>
                                        <td>
                                          <span className={`badge ${severityBadgeClass(a.severity)}`}>
                                            {a.severity?.toUpperCase()}
                                          </span>
                                        </td>
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
                                        <td className="text-nowrap text-muted small">
                                          {formatServerDate(a.alertUpdatedAt, undefined, '—')}
                                        </td>
                                      </tr>
                                    ))}
                                </tbody>
                              </table>
                            )}
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {exceptionRepo && (
        <>
          <div className="modal d-block" tabIndex={-1} role="dialog">
            <div className="modal-dialog" role="document">
              <div className="modal-content">
                <div className="modal-header">
                  <h5 className="modal-title">
                    <i className="bi bi-shield-slash me-2"></i>
                    Except {exceptionRepo.fullName}
                  </h5>
                  <button type="button" className="btn-close" onClick={() => setExceptionRepo(null)}></button>
                </div>
                <div className="modal-body">
                  <p className="text-muted small">
                    While the exception is active, this repository is skipped by the
                    "vulnerabilities not decreasing" owner alerts.
                  </p>
                  <div className="mb-3">
                    <label className="form-label">
                      Reason <span className="text-danger">*</span>
                    </label>
                    <textarea
                      className="form-control"
                      rows={3}
                      value={exceptionReason}
                      onChange={(e) => setExceptionReason(e.target.value)}
                      placeholder="Why is this repository excepted from alerting?"
                    />
                  </div>
                  <div className="mb-3">
                    <label className="form-label">Expiration date (optional)</label>
                    <input
                      type="date"
                      className="form-control"
                      value={exceptionExpiry}
                      onChange={(e) => setExceptionExpiry(e.target.value)}
                    />
                    <div className="form-text">Leave empty for a permanent exception.</div>
                  </div>
                </div>
                <div className="modal-footer">
                  <button type="button" className="btn btn-secondary" onClick={() => setExceptionRepo(null)}>
                    Cancel
                  </button>
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={saveException}
                    disabled={savingException || !exceptionReason.trim()}
                  >
                    {savingException ? (
                      <>
                        <span className="spinner-border spinner-border-sm me-2"></span>
                        Saving...
                      </>
                    ) : (
                      'Create exception'
                    )}
                  </button>
                </div>
              </div>
            </div>
          </div>
          <div className="modal-backdrop fade show"></div>
        </>
      )}
    </div>
  );
};

export default GithubRepoManagement;
