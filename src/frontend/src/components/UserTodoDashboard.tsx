import React, { useEffect, useState } from 'react';
import { authenticatedGet, getUser } from '../utils/auth';
import { formatServerDateTime } from '../utils/dateUtils';

/**
 * Personal todo dashboard shown on the home page for users without the
 * ADMIN or SECCHAMPION role.
 *
 * Renders entirely from a single GET /api/user-dashboard response so the
 * page needs exactly one round-trip after login.
 */

type VulnerabilitySeverityCounts = {
  critical: number;
  high: number;
  medium: number;
  low: number;
  other: number;
  total: number;
};

type OverdueAssetSummary = {
  assetCount: number;
  criticalCount: number;
  highCount: number;
  oldestVulnDays: number;
  lastCalculatedAt: string | null;
};

type ExceptionRequestSummary = {
  totalRequests: number;
  approvedCount: number;
  pendingCount: number;
  rejectedCount: number;
  expiredCount: number;
  cancelledCount: number;
};

type RiskAssessmentTodo = {
  id: number;
  basisType: string;
  basisName: string | null;
  status: string;
  endDate: string;
  daysUntilDue: number;
  overdue: boolean;
  respondUrl: string | null;
};

type DashboardViewFlags = {
  accountVulns: boolean;
  workgroupVulns: boolean;
  domainVulns: boolean;
};

type UserDashboardData = {
  assetCount: number;
  vulnerabilities: VulnerabilitySeverityCounts;
  overdue: OverdueAssetSummary;
  exceptionRequests: ExceptionRequestSummary;
  riskAssessments: RiskAssessmentTodo[];
  views: DashboardViewFlags;
};

const formatCount = (value: number | null | undefined): string => {
  if (value == null) return '—';
  return new Intl.NumberFormat().format(value);
};

/** First vulnerability view the user actually has data for */
const primaryVulnLink = (views: DashboardViewFlags): { href: string; label: string } | null => {
  if (views.accountVulns) return { href: '/account-vulns', label: 'Account Vulns' };
  if (views.workgroupVulns) return { href: '/wg-vulns', label: 'Workgroup Vulns' };
  if (views.domainVulns) return { href: '/vulnerabilities/domain', label: 'Domain Vulns' };
  return null;
};

const deadlineBadge = (item: RiskAssessmentTodo): React.ReactElement => {
  if (item.overdue) {
    return (
      <span className="badge text-bg-danger">
        <i className="bi bi-exclamation-triangle-fill me-1" aria-hidden="true"></i>
        Overdue by {formatCount(Math.abs(item.daysUntilDue))} {Math.abs(item.daysUntilDue) === 1 ? 'day' : 'days'}
      </span>
    );
  }
  if (item.daysUntilDue === 0) {
    return <span className="badge text-bg-warning">Due today</span>;
  }
  if (item.daysUntilDue <= 3) {
    return <span className="badge text-bg-warning">Due in {item.daysUntilDue} {item.daysUntilDue === 1 ? 'day' : 'days'}</span>;
  }
  return <span className="badge text-bg-secondary">Due in {item.daysUntilDue} days</span>;
};

const SeverityChip: React.FC<{ label: string; count: number; className: string }> = ({ label, count, className }) => (
  <span className={`badge ${className}`} title={`${label} severity`}>
    {label}: {formatCount(count)}
  </span>
);

const UserTodoDashboard: React.FC = () => {
  const [data, setData] = useState<UserDashboardData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [userName, setUserName] = useState('there');

  useEffect(() => {
    setUserName(getUser()?.username ?? 'there');

    const load = async () => {
      try {
        const resp = await authenticatedGet('/api/user-dashboard');
        if (resp.ok) {
          setData(await resp.json());
        } else {
          setError('Could not load your dashboard. Please try again later.');
        }
      } catch (err) {
        console.warn('Failed to load user dashboard:', err);
        setError('Could not load your dashboard. Please try again later.');
      } finally {
        setLoading(false);
      }
    };

    void load();
  }, []);

  if (loading) {
    return (
      <section className="container-fluid py-4 px-4 px-lg-5">
        <div className="d-flex align-items-center gap-2 text-muted">
          <div className="spinner-border spinner-border-sm" role="status" aria-hidden="true"></div>
          <span>Loading your dashboard…</span>
        </div>
      </section>
    );
  }

  if (error || !data) {
    return (
      <section className="container-fluid py-4 px-4 px-lg-5">
        <h1 className="display-6 fw-semibold mb-3">Welcome back, {userName}</h1>
        <div className="alert alert-warning" role="alert">
          <i className="bi bi-exclamation-circle me-2" aria-hidden="true"></i>
          {error ?? 'Could not load your dashboard.'}
        </div>
      </section>
    );
  }

  const { vulnerabilities, overdue, exceptionRequests, riskAssessments = [], views } = data;
  const vulnLink = primaryVulnLink(views);

  const overdueAction = overdue.assetCount > 0;
  // lastCalculatedAt === null means the outdated-asset view has never been
  // calculated (or a refresh was interrupted) — a zero count is not a clean SLA.
  const overdueDataAvailable = overdue.lastCalculatedAt !== null;
  const criticalAction = vulnerabilities.critical > 0;
  const hasActions = overdueAction || criticalAction || riskAssessments.length > 0;

  return (
    <section className="container-fluid py-4 px-4 px-lg-5">
      <div className="d-flex flex-column flex-md-row justify-content-between align-items-md-end mb-4 gap-2">
        <div>
          <h1 className="display-6 fw-semibold mb-1">Welcome back, {userName}</h1>
          <p className="text-muted mb-0">Your security to-dos at a glance</p>
        </div>
      </div>

      {/* KPI tiles */}
      <div className="row g-4 mb-4">
        <div className="col-12 col-md-6 col-xl-3">
          <article className="card border-0 shadow-sm h-100">
            <div className="card-body p-4">
              <div className="d-flex justify-content-between align-items-start mb-3">
                <h2 className="h6 text-muted text-uppercase mb-0" style={{ letterSpacing: '0.04em' }}>My Systems</h2>
                <i className="bi bi-hdd-network fs-4 text-primary" aria-hidden="true"></i>
              </div>
              <p className="fw-bold mb-2" style={{ fontSize: '1.75rem', lineHeight: 1.2 }}>{formatCount(data.assetCount)}</p>
              <p className="text-muted mb-0">Assets you can access</p>
            </div>
          </article>
        </div>

        <div className="col-12 col-md-6 col-xl-3">
          <article className="card border-0 shadow-sm h-100">
            <div className="card-body p-4">
              <div className="d-flex justify-content-between align-items-start mb-3">
                <h2 className="h6 text-muted text-uppercase mb-0" style={{ letterSpacing: '0.04em' }}>Open Vulnerabilities</h2>
                <i className="bi bi-bug fs-4 text-primary" aria-hidden="true"></i>
              </div>
              <p className="fw-bold mb-2" style={{ fontSize: '1.75rem', lineHeight: 1.2 }}>{formatCount(vulnerabilities.total)}</p>
              <div className="d-flex flex-wrap gap-1">
                <SeverityChip label="Critical" count={vulnerabilities.critical} className="text-bg-danger" />
                <SeverityChip label="High" count={vulnerabilities.high} className="text-bg-warning" />
                <SeverityChip label="Medium" count={vulnerabilities.medium} className="text-bg-secondary" />
                <SeverityChip label="Low" count={vulnerabilities.low} className="text-bg-light border" />
              </div>
            </div>
          </article>
        </div>

        <div className="col-12 col-md-6 col-xl-3">
          <article className={`card border-0 shadow-sm h-100 ${overdueAction ? 'border-start border-danger border-4' : ''}`}>
            <div className="card-body p-4">
              <div className="d-flex justify-content-between align-items-start mb-3">
                <h2 className="h6 text-muted text-uppercase mb-0" style={{ letterSpacing: '0.04em' }}>Overdue Patching</h2>
                <i className={`bi bi-alarm fs-4 ${overdueAction ? 'text-danger' : 'text-primary'}`} aria-hidden="true"></i>
              </div>
              <p className={`fw-bold mb-2 ${overdueAction ? 'text-danger' : ''}`} style={{ fontSize: '1.75rem', lineHeight: 1.2 }}>
                {overdueDataAvailable ? formatCount(overdue.assetCount) : '—'}
              </p>
              <p className="text-muted mb-0">
                {overdueAction
                  ? `Assets past their SLA — oldest finding is ${formatCount(overdue.oldestVulnDays)} days old`
                  : overdueDataAvailable
                    ? 'No assets past their remediation SLA'
                    : 'Not available yet — overdue data has not been calculated'}
              </p>
            </div>
          </article>
        </div>

        <div className="col-12 col-md-6 col-xl-3">
          <article className="card border-0 shadow-sm h-100">
            <div className="card-body p-4">
              <div className="d-flex justify-content-between align-items-start mb-3">
                <h2 className="h6 text-muted text-uppercase mb-0" style={{ letterSpacing: '0.04em' }}>Exception Requests</h2>
                <i className="bi bi-shield-exclamation fs-4 text-primary" aria-hidden="true"></i>
              </div>
              <p className="fw-bold mb-2" style={{ fontSize: '1.75rem', lineHeight: 1.2 }}>{formatCount(exceptionRequests.pendingCount)}</p>
              <p className="text-muted mb-0">
                Pending approval · <a href="/my-exception-requests">view my requests</a>
              </p>
            </div>
          </article>
        </div>
      </div>

      {/* Action required */}
      <div className="row g-4">
        <div className="col-12 col-xl-8">
          <article className="card border-0 shadow-sm">
            <div className="card-header bg-transparent border-0 pt-4 px-4 pb-0">
              <h2 className="h5 mb-0">
                <i className="bi bi-list-check me-2 text-primary" aria-hidden="true"></i>
                Action required
              </h2>
            </div>
            <div className="card-body p-4">
              {!hasActions && (
                <div className="d-flex align-items-center gap-3 py-3">
                  <i className="bi bi-check-circle-fill fs-3 text-success" aria-hidden="true"></i>
                  <div>
                    <p className="fw-semibold mb-0">You're all caught up</p>
                    <p className="text-muted mb-0">No overdue findings, no critical vulnerabilities, no assessments waiting on you.</p>
                  </div>
                </div>
              )}

              {hasActions && (
                <ul className="list-group list-group-flush">
                  {overdueAction && (
                    <li className="list-group-item px-0 d-flex justify-content-between align-items-center gap-3 flex-wrap">
                      <div className="d-flex align-items-center gap-3">
                        <i className="bi bi-alarm-fill fs-4 text-danger" aria-hidden="true"></i>
                        <div>
                          <p className="fw-semibold mb-0">
                            Patch {formatCount(overdue.assetCount)} {overdue.assetCount === 1 ? 'asset' : 'assets'} past the remediation SLA
                          </p>
                          <p className="text-muted mb-0">
                            {formatCount(overdue.criticalCount)} critical and {formatCount(overdue.highCount)} high overdue findings
                            {overdue.oldestVulnDays > 0 ? ` — oldest is ${formatCount(overdue.oldestVulnDays)} days old` : ''}
                          </p>
                        </div>
                      </div>
                      {vulnLink && (
                        <a className="btn btn-sm btn-outline-danger" href={vulnLink.href}>
                          Review <i className="bi bi-arrow-right ms-1" aria-hidden="true"></i>
                        </a>
                      )}
                    </li>
                  )}

                  {criticalAction && (
                    <li className="list-group-item px-0 d-flex justify-content-between align-items-center gap-3 flex-wrap">
                      <div className="d-flex align-items-center gap-3">
                        <i className="bi bi-bug-fill fs-4 text-danger" aria-hidden="true"></i>
                        <div>
                          <p className="fw-semibold mb-0">
                            Review {formatCount(vulnerabilities.critical)} critical {vulnerabilities.critical === 1 ? 'vulnerability' : 'vulnerabilities'} on your systems
                          </p>
                          <p className="text-muted mb-0">Fix, or request an exception with a documented reason</p>
                        </div>
                      </div>
                      {vulnLink && (
                        <a className="btn btn-sm btn-outline-primary" href={vulnLink.href}>
                          Review <i className="bi bi-arrow-right ms-1" aria-hidden="true"></i>
                        </a>
                      )}
                    </li>
                  )}

                  {riskAssessments.map((item) => (
                    <li key={item.id} className="list-group-item px-0 d-flex justify-content-between align-items-center gap-3 flex-wrap">
                      <div className="d-flex align-items-center gap-3">
                        <i className="bi bi-clipboard2-pulse fs-4 text-primary" aria-hidden="true"></i>
                        <div>
                          <p className="fw-semibold mb-0">
                            Answer risk assessment{item.basisName ? ` for ${item.basisName}` : ` #${item.id}`}
                          </p>
                          <p className="text-muted mb-1">
                            {item.basisType === 'ASSET' ? 'Asset' : 'Demand'} assessment · due {item.endDate}
                          </p>
                          {deadlineBadge(item)}
                        </div>
                      </div>
                      {item.respondUrl && (
                        <a className="btn btn-sm btn-primary" href={item.respondUrl}>
                          Respond <i className="bi bi-arrow-right ms-1" aria-hidden="true"></i>
                        </a>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </article>
        </div>

        {/* Side column: waiting + quick links */}
        <div className="col-12 col-xl-4 d-flex flex-column gap-4">
          {exceptionRequests.pendingCount > 0 && (
            <article className="card border-0 shadow-sm">
              <div className="card-header bg-transparent border-0 pt-4 px-4 pb-0">
                <h2 className="h5 mb-0">
                  <i className="bi bi-hourglass-split me-2 text-primary" aria-hidden="true"></i>
                  Waiting on others
                </h2>
              </div>
              <div className="card-body p-4">
                <p className="mb-2">
                  <span className="fw-semibold">{formatCount(exceptionRequests.pendingCount)}</span> exception{' '}
                  {exceptionRequests.pendingCount === 1 ? 'request' : 'requests'} awaiting approval.
                </p>
                <p className="text-muted mb-3">
                  {formatCount(exceptionRequests.approvedCount)} approved · {formatCount(exceptionRequests.rejectedCount)} rejected overall
                </p>
                <a className="btn btn-sm btn-outline-primary" href="/my-exception-requests">
                  My exception requests <i className="bi bi-arrow-right ms-1" aria-hidden="true"></i>
                </a>
              </div>
            </article>
          )}

          <article className="card border-0 shadow-sm">
            <div className="card-header bg-transparent border-0 pt-4 px-4 pb-0">
              <h2 className="h5 mb-0">
                <i className="bi bi-compass me-2 text-primary" aria-hidden="true"></i>
                Your views
              </h2>
            </div>
            <div className="card-body p-4">
              <div className="list-group list-group-flush">
                {views.accountVulns && (
                  <a href="/account-vulns" className="list-group-item list-group-item-action px-0 d-flex justify-content-between align-items-center">
                    <span><i className="bi bi-cloud me-2 text-muted" aria-hidden="true"></i>AWS Account Vulnerabilities</span>
                    <i className="bi bi-chevron-right text-muted" aria-hidden="true"></i>
                  </a>
                )}
                {views.workgroupVulns && (
                  <a href="/wg-vulns" className="list-group-item list-group-item-action px-0 d-flex justify-content-between align-items-center">
                    <span><i className="bi bi-people me-2 text-muted" aria-hidden="true"></i>Workgroup Vulnerabilities</span>
                    <i className="bi bi-chevron-right text-muted" aria-hidden="true"></i>
                  </a>
                )}
                {views.domainVulns && (
                  <a href="/vulnerabilities/domain" className="list-group-item list-group-item-action px-0 d-flex justify-content-between align-items-center">
                    <span><i className="bi bi-diagram-3 me-2 text-muted" aria-hidden="true"></i>Domain Vulnerabilities</span>
                    <i className="bi bi-chevron-right text-muted" aria-hidden="true"></i>
                  </a>
                )}
                <a href="/my-exception-requests" className="list-group-item list-group-item-action px-0 d-flex justify-content-between align-items-center">
                  <span><i className="bi bi-shield-exclamation me-2 text-muted" aria-hidden="true"></i>My Exception Requests</span>
                  <i className="bi bi-chevron-right text-muted" aria-hidden="true"></i>
                </a>
                <a href="/profile" className="list-group-item list-group-item-action px-0 d-flex justify-content-between align-items-center">
                  <span><i className="bi bi-person-gear me-2 text-muted" aria-hidden="true"></i>Profile &amp; Notifications</span>
                  <i className="bi bi-chevron-right text-muted" aria-hidden="true"></i>
                </a>
              </div>
              {overdue.lastCalculatedAt && (
                <p className="text-muted small mt-3 mb-0">
                  Overdue data refreshed {formatServerDateTime(overdue.lastCalculatedAt, undefined, 'recently')}
                </p>
              )}
            </div>
          </article>
        </div>
      </div>
    </section>
  );
};

export default UserTodoDashboard;
