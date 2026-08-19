import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  getEolCatalogStatus,
  getEolFindings,
  getEolSummary,
  getTopEolRepositories,
  triggerEolSync,
  type EolCatalogStatus,
  type EolFinding,
  type EolRepositoryRank,
  type EolSummary,
} from '../services/eolService';
import { getUser } from '../utils/auth';
import { componentLabel, describeDeadline, statusBadge, subjectLabel, urgencyRank } from './eolFormat';

const PAGE_SIZE = 100;

type StatusFilter = 'ALL' | 'EOL' | 'APPROACHING_EOL';

/**
 * "Which software in my account / on my servers is end of life?"
 *
 * Everything on this page is already scoped by the backend to the systems the
 * caller can see — the component never filters by account or owner itself, so
 * there is no client-side check standing in for an authorization decision. The
 * repositories panel is rendered only for ADMIN/SECCHAMPION, and the endpoint
 * behind it is `@Secured` for the same two roles: the hidden panel is UX, the
 * 403 is the boundary.
 */
const EolDashboard: React.FC = () => {
  // Roles live in sessionStorage, which does not exist during Astro's server
  // render — getUser() returns null there. Reading them during the *first* render
  // would make the server HTML (no role-gated UI) disagree with the client's, and
  // React 19 answers a hydration mismatch by throwing and discarding the whole
  // island. So both sides render role-free, and the gated UI appears on the first
  // post-mount render instead.
  const [roles, setRoles] = useState<string[]>([]);
  useEffect(() => {
    setRoles(getUser()?.roles ?? []);
  }, []);
  const isAdmin = roles.includes('ADMIN');
  const canSeeRepositories = isAdmin || roles.includes('SECCHAMPION');

  const [summary, setSummary] = useState<EolSummary | null>(null);
  const [catalog, setCatalog] = useState<EolCatalogStatus | null>(null);
  const [findings, setFindings] = useState<EolFinding[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<StatusFilter>('ALL');
  const [search, setSearch] = useState('');
  const [accountFilter, setAccountFilter] = useState('');
  // Off by default: installer/setup payloads ("Chrome Installer", "Photon Setup") are hidden
  // unless the user explicitly asks for them, matching the backend default.
  const [includeInstallerFindings, setIncludeInstallerFindings] = useState(false);
  const [repositories, setRepositories] = useState<EolRepositoryRank[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [syncMessage, setSyncMessage] = useState<string | null>(null);
  const findingsRef = useRef<HTMLDivElement>(null);

  const loadSummary = useCallback(() => {
    Promise.all([getEolSummary(), getEolCatalogStatus()])
      .then(([summaryResponse, catalogResponse]) => {
        setSummary(summaryResponse);
        setCatalog(catalogResponse);
      })
      .catch((err) => setError(err instanceof Error ? err.message : 'Failed to load EOL summary'));
  }, []);

  useEffect(() => {
    loadSummary();
  }, [loadSummary]);

  useEffect(() => {
    if (!canSeeRepositories) return;
    getTopEolRepositories(10)
      .then(setRepositories)
      .catch((err) => console.error('Failed to load top EOL repositories:', err));
  }, [canSeeRepositories]);

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      setLoading(true);
      getEolFindings({
        status,
        search,
        cloudAccountId: accountFilter,
        page,
        pageSize: PAGE_SIZE,
        includeInstallerFindings,
      })
        .then((response) => {
          setFindings(response.findings);
          setTotal(response.total);
          setError(null);
        })
        .catch((err) => setError(err instanceof Error ? err.message : 'Failed to load EOL findings'))
        .finally(() => setLoading(false));
    }, 250);
    return () => window.clearTimeout(timeout);
  }, [status, search, accountFilter, page, includeInstallerFindings]);

  // Any filter change invalidates the current offset — staying on page 5 of a
  // narrower result set silently shows an empty table.
  useEffect(() => {
    setPage(0);
  }, [status, search, accountFilter, includeInstallerFindings]);

  const sortedFindings = useMemo(
    () => [...findings].sort((a, b) => urgencyRank(a.status, a.daysUntilEol) - urgencyRank(b.status, b.daysUntilEol)),
    [findings],
  );

  /**
   * The account rollup can run to 50 rows, so the findings table it filters is
   * far below the fold: setting the filter alone changes nothing the user can
   * see, which reads as a dead control. Scrolling the results into view is the
   * feedback. Clicking the selected account again clears the filter.
   */
  const selectAccount = (cloudAccountId: string) => {
    setAccountFilter((current) => (current === cloudAccountId ? '' : cloudAccountId));
    findingsRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  const handleSync = async () => {
    setSyncing(true);
    setSyncMessage(null);
    try {
      const result = await triggerEolSync(false);
      setSyncMessage(
        `Sync ${String(result.status ?? 'finished')}: ${Number(result.productsSynced ?? 0)} products, ` +
          `${Number(result.findingsWritten ?? 0)} findings written.`,
      );
      loadSummary();
      setPage(0);
    } catch (err) {
      setSyncMessage(err instanceof Error ? err.message : 'EOL sync failed');
    } finally {
      setSyncing(false);
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="container-fluid mt-4">
      <div className="d-flex justify-content-between align-items-start flex-wrap gap-2 mb-3">
        <div>
          <h1 className="h3 mb-1">
            <i className="bi bi-calendar-x me-2"></i>
            End-of-life software
          </h1>
          <p className="text-muted mb-0">
            Operating systems and installed software on your systems that have reached — or are about to reach — the
            end of vendor support.
          </p>
        </div>
        {isAdmin && (
          <button type="button" className="btn btn-outline-primary" onClick={handleSync} disabled={syncing}>
            <i className="bi bi-arrow-repeat me-2"></i>
            {syncing ? 'Syncing…' : 'Sync catalogue & rescan'}
          </button>
        )}
      </div>

      {syncMessage && (
        <div className="alert alert-info" role="alert">
          {syncMessage}
        </div>
      )}
      {error && (
        <div className="alert alert-danger" role="alert">
          {error}
        </div>
      )}

      <div className="row g-3 mb-4">
        <div className="col-md-3">
          <div className="card h-100 border-danger">
            <div className="card-body">
              <div className="text-muted small">Already end of life</div>
              <div className="display-6">{summary?.eolCount ?? 0}</div>
            </div>
          </div>
        </div>
        <div className="col-md-3">
          <div className="card h-100 border-warning">
            <div className="card-body">
              <div className="text-muted small">
                EOL within {summary?.horizonMonths ?? 12} months
              </div>
              <div className="display-6">{summary?.approachingCount ?? 0}</div>
            </div>
          </div>
        </div>
        <div className="col-md-3">
          <div className="card h-100">
            <div className="card-body">
              <div className="text-muted small">Affected systems</div>
              <div className="display-6">{summary?.affectedAssets ?? 0}</div>
            </div>
          </div>
        </div>
        <div className="col-md-3">
          <div className="card h-100">
            <div className="card-body">
              <div className="text-muted small">Catalogue</div>
              <div className="fs-5">{catalog?.products ?? 0} products</div>
              <div className="text-muted small">
                {catalog?.lastSyncAt ? `Last sync ${new Date(catalog.lastSyncAt).toLocaleString()}` : 'Never synced'}
              </div>
              {catalog?.lastSyncError && <div className="text-warning small">{catalog.lastSyncError}</div>}
            </div>
          </div>
        </div>
      </div>

      {summary && summary.accounts.length > 0 && (
        <div className="card mb-4">
          <div className="card-header">
            <i className="bi bi-cloud me-2"></i>
            End-of-life software by account
          </div>
          <div className="table-responsive">
            <table className="table table-sm mb-0">
              <thead>
                <tr>
                  <th>Account</th>
                  <th className="text-end">End of life</th>
                  <th className="text-end">Approaching</th>
                </tr>
              </thead>
              <tbody>
                {summary.accounts.map((account) => {
                  const selected = accountFilter === account.cloudAccountId;
                  return (
                  <tr key={account.cloudAccountId} className={selected ? 'table-active' : undefined}>
                    <td>
                      <button
                        type="button"
                        className={`btn btn-link p-0${selected ? ' fw-bold' : ''}`}
                        aria-pressed={selected}
                        onClick={() => selectAccount(account.cloudAccountId)}
                      >
                        {account.cloudAccountId}
                      </button>
                      {selected && <i className="bi bi-funnel-fill text-primary ms-2"></i>}
                    </td>
                    <td className="text-end">{account.eolCount}</td>
                    <td className="text-end">{account.approachingCount}</td>
                  </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {canSeeRepositories && (
        <div className="card mb-4">
          <div className="card-header">
            <i className="bi bi-github me-2"></i>
            Top 10 repositories with the most end-of-life components
          </div>
          <div className="table-responsive">
            <table className="table table-sm mb-0">
              <thead>
                <tr>
                  <th style={{ width: '4rem' }}>#</th>
                  <th>Repository</th>
                  <th className="text-end">Distinct EOL components</th>
                  <th className="text-end">Findings</th>
                </tr>
              </thead>
              <tbody>
                {repositories.length === 0 && (
                  <tr>
                    <td colSpan={4} className="text-muted text-center py-3">
                      No end-of-life repository components detected. Import GitHub repositories and run the EOL sync.
                    </td>
                  </tr>
                )}
                {repositories.map((repository) => (
                  <tr key={repository.repositoryId}>
                    <td>{repository.rank}</td>
                    <td>{repository.fullName}</td>
                    <td className="text-end">{repository.distinctEolComponents}</td>
                    <td className="text-end">{repository.eolFindings}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div className="card" ref={findingsRef}>
        {accountFilter && (
          <div className="card-header bg-primary-subtle d-flex align-items-center gap-2">
            <i className="bi bi-funnel-fill"></i>
            <span>
              Showing account <strong>{accountFilter}</strong>
            </span>
            <button type="button" className="btn btn-sm btn-outline-secondary ms-auto" onClick={() => setAccountFilter('')}>
              Clear filter
            </button>
          </div>
        )}
        <div className="card-header d-flex flex-wrap gap-2 align-items-center">
          <div className="flex-grow-1">
            <input
              type="text"
              className="form-control"
              placeholder="Search component, vendor or system…"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </div>
          <div>
            <input
              type="text"
              className="form-control"
              placeholder="AWS account"
              value={accountFilter}
              onChange={(event) => setAccountFilter(event.target.value)}
            />
          </div>
          <div>
            <select
              className="form-select"
              value={status}
              onChange={(event) => setStatus(event.target.value as StatusFilter)}
            >
              <option value="ALL">All</option>
              <option value="EOL">End of life</option>
              <option value="APPROACHING_EOL">Approaching EOL</option>
            </select>
          </div>
          <div className="form-check ms-2">
            <input
              className="form-check-input"
              type="checkbox"
              id="eol-include-installer"
              checked={includeInstallerFindings}
              onChange={(event) => setIncludeInstallerFindings(event.target.checked)}
            />
            <label className="form-check-label small" htmlFor="eol-include-installer">
              Include installer / setup findings
            </label>
          </div>
        </div>

        <div className="table-responsive">
          <table className="table table-sm table-hover mb-0">
            <thead>
              <tr>
                <th>System</th>
                <th>Type</th>
                <th>Component</th>
                <th>Release cycle</th>
                <th>End of support</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr>
                  <td colSpan={6} className="text-center py-4">
                    Loading…
                  </td>
                </tr>
              )}
              {!loading && sortedFindings.length === 0 && (
                <tr>
                  <td colSpan={6} className="text-center text-muted py-4">
                    No end-of-life software found on your systems.
                  </td>
                </tr>
              )}
              {!loading &&
                sortedFindings.map((finding) => {
                  const badge = statusBadge(finding.status);
                  return (
                    <tr key={finding.id}>
                      <td>
                        {finding.assetId ? (
                          <a href={`/assets/${finding.assetId}`}>{finding.assetName}</a>
                        ) : (
                          finding.repositoryFullName
                        )}
                        {finding.cloudAccountId && (
                          <div className="text-muted small">{finding.cloudAccountId}</div>
                        )}
                      </td>
                      <td className="text-muted small">{subjectLabel(finding.subjectType)}</td>
                      <td>
                        {componentLabel(finding.componentName, finding.componentVendor, finding.componentVersion)}
                        {finding.productClass === 'INSTALLER_ARTIFACT' && (
                          <span
                            className="badge bg-secondary-subtle text-secondary-emphasis ms-2"
                            title="Classified as an installer or setup payload rather than deployed software. Hidden by default; tune the rules under Admin -> Product classification."
                          >
                            installer
                          </span>
                        )}
                      </td>
                      <td>{finding.cycle}</td>
                      <td>{describeDeadline(finding.eolDate, finding.daysUntilEol)}</td>
                      <td>
                        <span className={badge.className}>{badge.label}</span>
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>

        <div className="card-footer d-flex justify-content-between align-items-center">
          <span className="text-muted small">
            {total} component{total === 1 ? '' : 's'} · page {page + 1} of {totalPages}
          </span>
          <div className="btn-group">
            <button
              type="button"
              className="btn btn-outline-secondary btn-sm"
              disabled={page === 0}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              Previous
            </button>
            <button
              type="button"
              className="btn btn-outline-secondary btn-sm"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage((current) => current + 1)}
            >
              Next
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default EolDashboard;
