import React, { useEffect, useState } from 'react';

import {
  getWebComponents,
  getWebExposures,
  getWebExposureSummary,
  type IntegrationPage,
  type WebComponent,
  type WebExposure,
  type WebExposureSummary,
} from '../services/integrationsService';
import {
  componentCategoryLabel,
  reachabilityClass,
} from '../services/webInventoryPresentation';
import { formatServerDate } from '../utils/dateUtils';
import Pagination from './Pagination';

const PAGE_SIZE = 25;

export default function ExternalExposureAnalytics() {
  const [summary, setSummary] = useState<WebExposureSummary | null>(null);
  const [exposures, setExposures] = useState<IntegrationPage<WebExposure> | null>(null);
  const [components, setComponents] = useState<IntegrationPage<WebComponent> | null>(null);
  const [exposurePage, setExposurePage] = useState(0);
  const [componentPage, setComponentPage] = useState(0);
  const [category, setCategory] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setError('');
    getWebExposureSummary()
      .then((value) => { if (active) setSummary(value); })
      .catch((reason) => {
        if (active) setError(reason instanceof Error ? reason.message : 'Could not load exposure analytics.');
      });
    return () => { active = false; };
  }, [refresh]);

  useEffect(() => {
    let active = true;
    setExposures(null);
    getWebExposures(exposurePage)
      .then((value) => { if (active) setExposures(value); })
      .catch((reason) => {
        if (active) setError(reason instanceof Error ? reason.message : 'Could not load scanned assets.');
      });
    return () => { active = false; };
  }, [exposurePage, refresh]);

  useEffect(() => {
    let active = true;
    setComponents(null);
    getWebComponents(componentPage, { category: category || undefined, state: 'OPEN', search: search || undefined })
      .then((value) => { if (active) setComponents(value); })
      .catch((reason) => {
        if (active) setError(reason instanceof Error ? reason.message : 'Could not load software components.');
      });
    return () => { active = false; };
  }, [category, componentPage, refresh, search]);

  const cards: [string, number][] = summary ? [
    ['Configured assets', summary.configuredAssets],
    ['Scanned assets', summary.scannedAssets],
    ['Reachable', summary.reachableAssets],
    ['Unknown', summary.unknownAssets],
    ['Software components', summary.activeComponents],
    ['Web servers', summary.webServers],
  ] : [];

  return (
    <section aria-labelledby="external-exposure-title">
      <header className="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
        <div>
          <h2 id="external-exposure-title" className="h4 mb-1">External exposure</h2>
          <p className="text-secondary mb-0">
            Reachability is observed from the web scanner&apos;s network vantage point. Components are passive fingerprints, not vulnerability findings.
          </p>
        </div>
        <button type="button" className="btn btn-outline-primary btn-sm" onClick={() => setRefresh((value) => value + 1)}>
          Refresh
        </button>
      </header>

      {error && <div className="alert alert-danger" role="alert">{error}</div>}
      {!summary ? <p role="status">Loading exposure summary…</p> : (
        <>
          <div className="row g-3 mb-3">
            {cards.map(([label, value]) => (
              <div className="col-6 col-xl-2" key={label}>
                <div className="card h-100"><div className="card-body">
                  <div className="fs-3 fw-semibold">{value}</div>
                  <div className="small text-secondary">{label}</div>
                </div></div>
              </div>
            ))}
          </div>
          <p className="small text-secondary">
            JavaScript: {summary.javascriptLibraries} · CSS: {summary.cssLibraries} · Last observation: {formatServerDate(summary.lastObservedAt, undefined, 'Never')}
          </p>
        </>
      )}

      <h3 className="h5 mt-4">Scanned assets</h3>
      {!exposures ? <p role="status">Loading scanned assets…</p> : (
        <>
          <div className="table-responsive">
            <table className="table table-striped align-middle">
              <thead><tr><th>Asset</th><th>Configured target</th><th>Exposure</th><th>HTTP</th><th>Observed</th></tr></thead>
              <tbody>
                {exposures.content.map((exposure) => (
                  <tr key={exposure.id}>
                    <td><a href={`/assets/${exposure.assetId}`}>{exposure.assetName}</a><div className="small text-secondary">{exposure.owner || 'Unassigned'}</div></td>
                    <td><code>{exposure.configuredUrl}</code>{exposure.effectiveUrl && exposure.effectiveUrl !== exposure.configuredUrl && <div className="small text-secondary">→ {exposure.effectiveUrl}</div>}</td>
                    <td><span className={`badge ${reachabilityClass(exposure.reachability)}`}>{exposure.reachability}</span></td>
                    <td>{exposure.httpStatus ?? '—'}{exposure.redirectCount > 0 && <div className="small text-secondary">{exposure.redirectCount} redirects</div>}</td>
                    <td>{formatServerDate(exposure.observedAt)}</td>
                  </tr>
                ))}
                {!exposures.content.length && <tr><td colSpan={5}>No web exposure observations are available in your asset scope.</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination currentPage={exposurePage} totalPages={exposures.totalPages} totalElements={exposures.totalElements} pageSize={PAGE_SIZE} onPageChange={setExposurePage} />
        </>
      )}

      <div className="d-flex flex-wrap align-items-end justify-content-between gap-3 mt-4 mb-2">
        <h3 className="h5 mb-0">Software components</h3>
        <form
          className="d-flex flex-wrap gap-2"
          onSubmit={(event) => { event.preventDefault(); setComponentPage(0); setSearch(searchInput.trim()); }}
        >
          <label className="form-label mb-0">Category
            <select className="form-select form-select-sm" value={category} onChange={(event) => { setCategory(event.target.value); setComponentPage(0); }}>
              <option value="">All</option>
              <option value="JAVASCRIPT_LIBRARY">JavaScript</option>
              <option value="CSS_LIBRARY">CSS</option>
              <option value="WEB_SERVER">Web server</option>
            </select>
          </label>
          <label className="form-label mb-0">Search
            <input className="form-control form-control-sm" value={searchInput} maxLength={200} onChange={(event) => setSearchInput(event.target.value)} />
          </label>
          <button type="submit" className="btn btn-outline-secondary btn-sm align-self-end">Apply</button>
        </form>
      </div>
      {!components ? <p role="status">Loading software components…</p> : (
        <>
          <div className="table-responsive">
            <table className="table table-striped align-middle">
              <thead><tr><th>Component</th><th>Category</th><th>Version</th><th>Asset</th><th>Confidence</th><th>Last seen</th></tr></thead>
              <tbody>
                {components.content.map((component) => (
                  <tr key={component.id}>
                    <td>{component.name}<div className="small text-secondary">{component.evidence}</div></td>
                    <td>{componentCategoryLabel(component.category)}</td>
                    <td>{component.version || 'Unknown'}</td>
                    <td><a href={`/assets/${component.assetId}`}>{component.assetName}</a></td>
                    <td>{Math.round(component.confidence * 100)}%</td>
                    <td>{formatServerDate(component.lastSeenAt)}</td>
                  </tr>
                ))}
                {!components.content.length && <tr><td colSpan={6}>No matching current components are available in your asset scope.</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination currentPage={componentPage} totalPages={components.totalPages} totalElements={components.totalElements} pageSize={PAGE_SIZE} onPageChange={setComponentPage} />
        </>
      )}
    </section>
  );
}
