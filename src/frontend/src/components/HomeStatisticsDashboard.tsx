import React, { useEffect, useState } from 'react';
import { authenticatedGet, getUser, hasRole } from '../utils/auth';
import { formatServerDate } from '../utils/dateUtils';
import { getDashboardPreferences } from '../services/dashboardPreferenceService';

type StatItem = {
  label: string;
  value: string;
  subtitle: string;
  icon: string;
};

type AwsCleanServerKpi = {
  available: boolean;
  percentage: number | null;
  totalAwsServers: number | null;
  cleanAwsServers: number | null;
};

type EdrCoverageKpi = {
  available: boolean;
  percentage: number | null;
  totalEc2Instances: number | null;
  eligibleEc2Instances: number | null;
  coveredEc2Instances: number | null;
  excludedByNoEdrException: number | null;
  agentSeenWithinDays: number | null;
};

type DashboardState = {
  assets: number | null;
  users: number | null;
  activeUsers: number | null;
  runningAssessments: number | null;
  releases: number | null;
  lastCrowdStrikeCheckin: string | null;
  awsCleanServerKpi: AwsCleanServerKpi | null;
  edrCoverageKpi: EdrCoverageKpi | null;
  accountFindingAge: Array<{
    awsAccountId: string;
    accountName: string;
    oldestFindingDaysOpen: number;
  }> | null;
};

const initialState: DashboardState = {
  assets: null,
  users: null,
  activeUsers: null,
  runningAssessments: null,
  releases: null,
  lastCrowdStrikeCheckin: null,
  awsCleanServerKpi: null,
  edrCoverageKpi: null,
  accountFindingAge: null
};

const formatAwsCleanServerKpi = (kpi: AwsCleanServerKpi | null): string => {
  if (!kpi || !kpi.available || kpi.percentage == null) return 'Not available';
  return `${kpi.percentage}%`;
};

const formatEdrCoverageKpi = (kpi: EdrCoverageKpi | null): string => {
  if (!kpi || !kpi.available || kpi.percentage == null) return 'Not available';
  return `${kpi.percentage}%`;
};

/**
 * The subtitle carries the counts the percentage hides: how many instances are actually
 * covered, and how many were taken out of the denominator by an approved "No EDR possible"
 * exception. Without the exemption count a rising percentage could equally mean better
 * coverage or more exemptions.
 */
const describeEdrCoverage = (kpi: EdrCoverageKpi | null): string => {
  if (!kpi || !kpi.available) {
    return 'Awaiting the first CrowdStrike import since this metric was introduced';
  }
  const covered = formatCount(kpi.coveredEc2Instances);
  const eligible = formatCount(kpi.eligibleEc2Instances);
  const days = kpi.agentSeenWithinDays ?? 7;
  const base = `${covered} of ${eligible} EC2 instances seen by CrowdStrike in the last ${days} days`;
  const excluded = kpi.excludedByNoEdrException ?? 0;
  return excluded > 0
    ? `${base} · ${formatCount(excluded)} excluded as "No EDR possible"`
    : base;
};

const formatDate = (isoOrNever: string): string => {
  if (isoOrNever === 'never') return 'Never imported';
  return formatServerDate(isoOrNever, undefined, 'Unknown');
};

const formatCount = (value: number | null): string => {
  if (value == null) return '—';
  return new Intl.NumberFormat().format(value);
};

const HomeStatisticsDashboard: React.FC = () => {
  const [stats, setStats] = useState<DashboardState>(initialState);
  const [userName, setUserName] = useState('there');
  const [isAdmin, setIsAdmin] = useState(false);
  const [showAwsCleanServerKpiCard, setShowAwsCleanServerKpiCard] = useState(false);
  const [showEdrCoverageKpiCard, setShowEdrCoverageKpiCard] = useState(false);

  useEffect(() => {
    setUserName(getUser()?.username ?? 'there');
    setIsAdmin(hasRole('ADMIN'));

    const load = async () => {
      const next: DashboardState = { ...initialState };

      try {
        const assetsResp = await authenticatedGet('/api/assets/count');
        if (assetsResp.ok) {
          const assets = await assetsResp.json();
          next.assets = typeof assets?.count === 'number' ? assets.count : null;
        }
      } catch (error) {
        console.warn('Failed to load asset statistics:', error);
      }

      if (hasRole(['ADMIN', 'RISK', 'SECCHAMPION'])) {
        try {
          const assessmentsResp = await authenticatedGet('/api/risk-assessments');
          if (assessmentsResp.ok) {
            const assessments = await assessmentsResp.json();
            if (Array.isArray(assessments)) {
              next.runningAssessments = assessments.filter(a => a?.status === 'IN_PROGRESS').length;
            }
          }
        } catch (error) {
          console.warn('Failed to load risk assessment statistics:', error);
        }
      }

      try {
        const releasesResp = await authenticatedGet('/api/releases?status=ACTIVE');
        if (releasesResp.ok) {
          const releasesPage = await releasesResp.json();
          next.releases = typeof releasesPage?.totalItems === 'number' ? releasesPage.totalItems : null;
        }
      } catch (error) {
        console.warn('Failed to load release statistics:', error);
      }

      try {
        const csResp = await authenticatedGet('/api/crowdstrike/last-checkin');
        if (csResp.ok) {
          next.lastCrowdStrikeCheckin = formatDate((await csResp.text()).trim());
        }
      } catch (error) {
        console.warn('Failed to load CrowdStrike check-in statistics:', error);
      }

      if (hasRole(['ADMIN', 'SECCHAMPION'])) {
        // Default both KPI cards to visible; only hide one if the preference fetch
        // succeeds and explicitly says so. A failed preference fetch must not blank
        // either KPI card.
        setShowAwsCleanServerKpiCard(true);
        setShowEdrCoverageKpiCard(true);

        try {
          const prefs = await getDashboardPreferences();
          setShowAwsCleanServerKpiCard(prefs.showAwsCleanServerKpi);
          setShowEdrCoverageKpiCard(prefs.showEdrCoverageKpi);
        } catch (error) {
          console.warn('Failed to load dashboard KPI visibility preferences:', error);
        }

        try {
          const kpiResp = await authenticatedGet('/api/dashboard/aws-clean-server-kpi');
          if (kpiResp.ok) {
            next.awsCleanServerKpi = await kpiResp.json();
          }
        } catch (error) {
          console.warn('Failed to load AWS clean-server KPI:', error);
        }

        // Separate try/catch from the KPI above: either endpoint failing must degrade only
        // its own card, not blank the dashboard or suppress the other KPI.
        try {
          const edrResp = await authenticatedGet('/api/dashboard/edr-coverage-kpi');
          if (edrResp.ok) {
            next.edrCoverageKpi = await edrResp.json();
          }
        } catch (error) {
          console.warn('Failed to load EDR coverage KPI:', error);
        }
      }

      if (hasRole('ADMIN')) {
        try {
          const agingResp = await authenticatedGet('/api/admin/account-finding-age/top?limit=10');
          if (agingResp.ok) {
            next.accountFindingAge = await agingResp.json();
          }
        } catch (error) {
          console.warn('Failed to load account finding-age report:', error);
        }
      }

      if (hasRole('ADMIN')) {
        try {
          const usersResp = await authenticatedGet('/api/users');
          if (usersResp.ok) {
            const users = await usersResp.json();
            next.users = Array.isArray(users) ? users.length : null;
          }
        } catch (error) {
          console.warn('Failed to load user statistics:', error);
        }

        try {
          const activeUsersResp = await authenticatedGet('/api/auth/activity-summary');
          if (activeUsersResp.ok) {
            const activity = await activeUsersResp.json();
            next.activeUsers = typeof activity?.activeUsers === 'number' ? activity.activeUsers : null;
          }
        } catch (error) {
          console.warn('Failed to load active user statistics:', error);
        }
      }

      setStats(next);
    };

    void load();
  }, []);

  const cards: StatItem[] = [
    { label: 'Systems in Asset Inventory', value: formatCount(stats.assets), subtitle: 'All asset records', icon: 'bi-hdd-network' },
    { label: 'Active Standard Releases', value: formatCount(stats.releases), subtitle: 'Releases with status ACTIVE', icon: 'bi-journals' },
    { label: 'Running Risk Assessments', value: formatCount(stats.runningAssessments), subtitle: 'Status: IN_PROGRESS', icon: 'bi-clipboard2-pulse' },
    { label: 'Last CrowdStrike Import', value: stats.lastCrowdStrikeCheckin ?? '—', subtitle: 'Most recent server check-in', icon: 'bi-shield-check' }
  ];

  if (isAdmin) {
    cards.splice(1, 0, {
      label: 'Users',
      value: formatCount(stats.users),
      subtitle: 'Registered user accounts',
      icon: 'bi-people'
    }, {
      label: 'Active Users',
      value: formatCount(stats.activeUsers),
      subtitle: 'Authenticated activity in last 15 minutes',
      icon: 'bi-person-check'
    });
  }

  if (showAwsCleanServerKpiCard) {
    cards.push({
      label: 'AWS Servers Without Old Vulnerabilities',
      value: formatAwsCleanServerKpi(stats.awsCleanServerKpi),
      subtitle: 'Share of AWS servers with no vulnerability older than 30 days',
      icon: 'bi-cloud-check'
    });
  }
  if (showEdrCoverageKpiCard) {
    cards.push({
      label: 'EC2 Instances With CrowdStrike Installed',
      value: formatEdrCoverageKpi(stats.edrCoverageKpi),
      subtitle: describeEdrCoverage(stats.edrCoverageKpi),
      icon: 'bi-shield-lock'
    });
  }

  return (
    <section className="container-fluid py-4 px-4 px-lg-5">
      <div className="d-flex flex-column flex-md-row justify-content-between align-items-md-end mb-4 gap-2">
        <div>
          <h1 className="display-6 fw-semibold mb-1">Welcome back, {userName}</h1>
          <p className="text-muted mb-0">Security posture at a glance</p>
        </div>
      </div>

      <div className="row g-4">
        {cards.map((card) => (
          <div className="col-12 col-md-6 col-xl-4" key={card.label}>
            <article className="card border-0 shadow-sm h-100">
              <div className="card-body p-4">
                <div className="d-flex justify-content-between align-items-start mb-3">
                  <h2 className="h6 text-muted text-uppercase mb-0" style={{ letterSpacing: '0.04em' }}>{card.label}</h2>
                  <i className={`bi ${card.icon} fs-4 text-primary`} aria-hidden="true"></i>
                </div>
                <p className="fw-bold mb-2" style={{ fontSize: '1.75rem', lineHeight: 1.2 }}>{card.value}</p>
                <p className="text-muted mb-0">{card.subtitle}</p>
              </div>
            </article>
          </div>
        ))}
      </div>

      {isAdmin && stats.accountFindingAge && stats.accountFindingAge.length > 0 && (
        <div className="row g-4 mt-0">
          <div className="col-12">
            <article className="card border-0 shadow-sm h-100">
              <div className="card-header bg-transparent border-0 pt-4 px-4 pb-0 d-flex justify-content-between align-items-center">
                <h2 className="h6 text-muted text-uppercase mb-0" style={{ letterSpacing: '0.04em' }}>
                  <i className="bi bi-hourglass-bottom me-2"></i>Longest-open findings by account
                </h2>
                <a href="/account-finding-age" className="btn btn-sm btn-outline-primary">View all</a>
              </div>
              <div className="card-body p-4">
                <ul className="list-group list-group-flush">
                  {stats.accountFindingAge.map((a) => (
                    <li key={a.awsAccountId} className="list-group-item px-0 d-flex justify-content-between align-items-center">
                      <span>
                        {a.accountName}
                        {a.accountName !== a.awsAccountId && (
                          <small className="text-muted ms-2">{a.awsAccountId}</small>
                        )}
                      </span>
                      <span className="badge bg-danger rounded-pill">{a.oldestFindingDaysOpen} d</span>
                    </li>
                  ))}
                </ul>
              </div>
            </article>
          </div>
        </div>
      )}
    </section>
  );
};

export default HomeStatisticsDashboard;
