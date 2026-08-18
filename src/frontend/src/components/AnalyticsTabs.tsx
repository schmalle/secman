import React, { useEffect, useMemo, useState } from 'react';
import CurrentVulnerabilitiesTable from './CurrentVulnerabilitiesTable';
import VulnerabilityStatisticsPage from './statistics/VulnerabilityStatisticsPage';
import VulnerabilityHeatmap from './VulnerabilityHeatmap';

/**
 * One destination for the vulnerability analysis views that used to be
 * separate entries in the rail (/vulnerabilities/current, /vulnerability-statistics,
 * /vulnerability-heatmap).
 *
 * Those routes still exist and still work — bookmarks, the E2E page sweep and
 * any link already in circulation keep resolving. This page is an additional
 * entry point, not a replacement, which is why nothing was deleted to build it.
 *
 * Only the selected view is mounted. Each of the three fetches its own data on
 * mount, so rendering all three would fire three independent request storms to
 * show one of them.
 */

type TabId = 'overview' | 'lense' | 'heatmap';

interface Tab {
  id: TabId;
  label: string;
  /** Where this view lived before, kept as the deep link for anyone who needs just it. */
  standalone: string;
  /**
   * Roles that may see the tab, or undefined when everyone in Vulnerability
   * Management may. These mirror the gates the rail applied before these views
   * were folded together — the controller is still the boundary, this is the UI
   * half of the same rule (CLAUDE.md Principle 2).
   */
  roles?: string[];
}

const TABS: Tab[] = [
  {
    id: 'overview',
    label: 'Overview',
    standalone: '/vulnerabilities/current',
    roles: ['ADMIN', 'SECCHAMPION', 'VULN'],
  },
  { id: 'lense', label: 'Lense', standalone: '/vulnerability-statistics' },
  { id: 'heatmap', label: 'Heatmap', standalone: '/vulnerability-heatmap' },
];

const DEFAULT_TAB: TabId = 'overview';

function isTabId(value: string | null): value is TabId {
  return TABS.some((tab) => tab.id === value);
}

const AnalyticsTabs: React.FC = () => {
  const [active, setActive] = useState<TabId>(DEFAULT_TAB);
  /*
   * Roles come from window.currentUser, which Layout.astro leaves undefined
   * until its auth fetch resolves and then announces with a `userLoaded` event.
   * Reading it once on mount is a race: this island frequently hydrates first,
   * sees no roles, and hides a tab the user is entitled to — which is exactly
   * what happened before this listener existed. Sidebar.tsx reads roles the
   * same way for the same reason.
   */
  // null means "auth has not resolved yet", which is a different state from
  // "resolved, and this user has no roles" — see the fallback effect below.
  const [roles, setRoles] = useState<string[] | null>(null);
  useEffect(() => {
    const readRoles = () => {
      const user = (window as any).currentUser;
      if (user === undefined) return; // still pending; the event will bring us back
      setRoles((user?.roles as string[]) ?? []);
    };
    readRoles();
    window.addEventListener('userLoaded', readRoles);
    return () => window.removeEventListener('userLoaded', readRoles);
  }, []);

  const visibleTabs = useMemo(
    () => TABS.filter((tab) => !tab.roles || tab.roles.some((role) => (roles ?? []).includes(role))),
    [roles],
  );

  // `location` does not exist during Astro's server render, so the tab in the
  // URL is read after mount. Server and client both render the default first,
  // which keeps the island's markup identical on both sides.
  useEffect(() => {
    const requested = new URLSearchParams(window.location.search).get('tab');
    if (isTabId(requested)) setActive(requested);
  }, []);

  /*
   * If the selected tab is not one this user may see — a stale ?tab= link —
   * fall back to the first tab they can.
   *
   * Deliberately skipped while roles are null. During the pending window every
   * gated tab looks invisible, so running this would silently move an entitled
   * user off their default tab a moment before the roles that justify it arrive.
   */
  useEffect(() => {
    if (roles === null) return;
    if (visibleTabs.length > 0 && !visibleTabs.some((tab) => tab.id === active)) {
      setActive(visibleTabs[0].id);
    }
  }, [roles, visibleTabs, active]);

  const selectTab = (id: TabId) => {
    setActive(id);
    // Reflect the choice in the URL so the view can be shared or reloaded,
    // without adding a history entry per click.
    const url = new URL(window.location.href);
    url.searchParams.set('tab', id);
    window.history.replaceState({}, '', url);
  };

  return (
    <div className="container-fluid py-4">
      <p className="scand-label mb-2">Vulnerability Management · Analyze</p>
      <h1 className="mb-2">Analytics</h1>
      <p className="text-secondary mb-4">
        One destination for the analysis views that used to be separate entries in the rail.
      </p>

      <div className="d-flex flex-wrap align-items-center gap-3 mb-4">
        <div className="btn-group" role="tablist" aria-label="Analysis view">
          {visibleTabs.map((tab) => {
            const selected = tab.id === active;
            return (
              <button
                key={tab.id}
                type="button"
                role="tab"
                aria-selected={selected}
                className={`btn ${selected ? 'btn-primary' : 'btn-outline-dark'}`}
                onClick={() => selectTab(tab.id)}
              >
                {tab.label}
              </button>
            );
          })}
        </div>
        {visibleTabs.some((tab) => tab.id === active) && (
          <a className="small" href={TABS.find((tab) => tab.id === active)!.standalone}>
            Open this view on its own page
          </a>
        )}
      </div>

      {active === 'overview' && <CurrentVulnerabilitiesTable />}
      {active === 'lense' && <VulnerabilityStatisticsPage />}
      {active === 'heatmap' && <VulnerabilityHeatmap />}
    </div>
  );
};

export default AnalyticsTabs;
