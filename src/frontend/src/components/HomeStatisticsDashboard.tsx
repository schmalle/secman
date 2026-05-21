import React, { useEffect, useMemo, useState } from 'react';
import { authenticatedGet, getUser, hasRole } from '../utils/auth';

type StatItem = {
  label: string;
  value: string;
  subtitle: string;
  icon: string;
};

type DashboardState = {
  assets: number | null;
  users: number | null;
  runningAssessments: number | null;
  releases: number | null;
  lastCrowdStrikeCheckin: string | null;
};

const initialState: DashboardState = {
  assets: null,
  users: null,
  runningAssessments: null,
  releases: null,
  lastCrowdStrikeCheckin: null
};

const formatDateTime = (isoOrNever: string): string => {
  if (isoOrNever === 'never') return 'Never imported';
  const parsed = new Date(isoOrNever);
  if (Number.isNaN(parsed.getTime())) return 'Unknown';
  return parsed.toLocaleString();
};

const formatCount = (value: number | null): string => {
  if (value == null) return '—';
  return new Intl.NumberFormat().format(value);
};

const HomeStatisticsDashboard: React.FC = () => {
  const [stats, setStats] = useState<DashboardState>(initialState);

  useEffect(() => {
    const load = async () => {
      const next: DashboardState = { ...initialState };

      try {
        const assetsResp = await authenticatedGet('/api/assets');
        if (assetsResp.ok) {
          const assets = await assetsResp.json();
          next.assets = Array.isArray(assets) ? assets.length : null;
        }
      } catch (error) {
        console.error('Failed to load asset statistics:', error);
      }

      try {
        const assessmentsResp = await authenticatedGet('/api/risk-assessments');
        if (assessmentsResp.ok) {
          const assessments = await assessmentsResp.json();
          if (Array.isArray(assessments)) {
            next.runningAssessments = assessments.filter(a => a?.status === 'IN_PROGRESS').length;
          }
        }
      } catch (error) {
        console.error('Failed to load risk assessment statistics:', error);
      }

      try {
        const releasesResp = await authenticatedGet('/api/releases');
        if (releasesResp.ok) {
          const releases = await releasesResp.json();
          next.releases = Array.isArray(releases) ? releases.length : null;
        }
      } catch (error) {
        console.error('Failed to load release statistics:', error);
      }

      try {
        const csResp = await authenticatedGet('/api/crowdstrike/last-checkin');
        if (csResp.ok) {
          next.lastCrowdStrikeCheckin = formatDateTime((await csResp.text()).trim());
        }
      } catch (error) {
        console.error('Failed to load CrowdStrike check-in statistics:', error);
      }

      if (hasRole('ADMIN')) {
        try {
          const usersResp = await authenticatedGet('/api/users');
          if (usersResp.ok) {
            const users = await usersResp.json();
            next.users = Array.isArray(users) ? users.length : null;
          }
        } catch (error) {
          console.error('Failed to load user statistics:', error);
        }
      }

      setStats(next);
    };

    void load();
  }, []);

  const isAdmin = useMemo(() => hasRole('ADMIN'), []);
  const userName = getUser()?.username ?? 'there';

  const cards: StatItem[] = [
    { label: 'Systems in Asset Inventory', value: formatCount(stats.assets), subtitle: 'Visible to your role', icon: 'bi-hdd-network' },
    { label: 'Active Standard Releases', value: formatCount(stats.releases), subtitle: 'All release records', icon: 'bi-journals' },
    { label: 'Running Risk Assessments', value: formatCount(stats.runningAssessments), subtitle: 'Status: IN_PROGRESS', icon: 'bi-clipboard2-pulse' },
    { label: 'Last CrowdStrike Import', value: stats.lastCrowdStrikeCheckin ?? '—', subtitle: 'Most recent server check-in', icon: 'bi-shield-check' }
  ];

  if (isAdmin) {
    cards.splice(1, 0, {
      label: 'Users',
      value: formatCount(stats.users),
      subtitle: 'Registered user accounts',
      icon: 'bi-people'
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
                <p className="display-6 fw-bold mb-2">{card.value}</p>
                <p className="text-muted mb-0">{card.subtitle}</p>
              </div>
            </article>
          </div>
        ))}
      </div>
    </section>
  );
};

export default HomeStatisticsDashboard;
