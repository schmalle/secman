import React, { useEffect, useState } from 'react';
import { getJson } from '../utils/apiJson';
import { getAssetVulnerabilities, type Vulnerability } from '../services/vulnerabilityService';
import { buddyRequest, type BuddyCapabilities } from '../services/aiBuddyService';
import AiBuddyPanel from './AiBuddyPanel';
import CrowdStrikeVulnerabilityLookup from './CrowdStrikeVulnerabilityLookup';

interface SystemAsset { id: number; name: string; osVersion: string | null }
export default function SystemVulnerabilities() {
  const [mode, setMode] = useState<'loading' | 'recorded' | 'legacy'>('loading');
  const [asset, setAsset] = useState<SystemAsset | null>(null);
  const [findings, setFindings] = useState<Vulnerability[]>([]);
  const [hostname, setHostname] = useState('');
  const [error, setError] = useState('');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const load = async (id: string | null, name: string) => {
    setLoading(true); setError(''); setAsset(null); setFindings([]); setPage(0);
    try {
      const result = await getJson<SystemAsset>(id ? `/api/assets/${encodeURIComponent(id)}` : `/api/assets/by-name/${encodeURIComponent(name.trim())}`);
      const rows = await getAssetVulnerabilities(result.id);
      setAsset(result); setFindings(rows);
    } catch (err) { setError(err instanceof Error ? err.message : 'System unavailable'); }
    finally { setLoading(false); }
  };
  useEffect(() => {
    let stopped = false;
    const params = new URLSearchParams(window.location.search);
    const id = params.get('assetId');
    const name = params.get('hostname') || '';
    void buddyRequest<BuddyCapabilities>('/capabilities').then(current => {
      if (stopped) return;
      if (current.roles.some(role => ['ADMIN', 'VULN'].includes(role))) setMode('legacy');
      else { setMode('recorded'); if (id || name) void load(id, name); }
    }).catch(() => { if (!stopped) { setMode('recorded'); setError('Unable to load your current permissions.'); } });
    return () => { stopped = true; };
  }, []);
  if (mode === 'legacy') return <CrowdStrikeVulnerabilityLookup />;
  if (mode === 'loading') return <p role="status">Loading system view…</p>;
  return <div className="container-fluid py-4"><h1 className="h3">System vulnerabilities</h1>
    <form className="d-flex gap-2 my-3" onSubmit={event => { event.preventDefault(); if (hostname.trim()) void load(null, hostname); }}>
      <label htmlFor="recorded-system" className="visually-hidden">Recorded system name</label><input id="recorded-system" className="form-control" value={hostname} onChange={event => setHostname(event.target.value)} placeholder="Recorded system name" required />
      <button className="btn btn-primary" disabled={loading}>Search</button>
    </form>
    {loading && <p role="status">Loading recorded findings…</p>}{error && <div className="alert alert-warning" role="alert">{error}</div>}
    {asset && <><h2 className="h4">{asset.name}</h2><p>{asset.osVersion || 'OS inventory missing'} · All currently recorded findings</p><AiBuddyPanel key={asset.id} assetId={asset.id} />
      {!findings.length ? <p>No findings are currently recorded. This does not establish that the system is vulnerability-free.</p> : <>
        <div className="table-responsive"><table className="table"><thead><tr><th>CVE / finding</th><th>Product</th><th>Severity</th><th>Scan time</th></tr></thead><tbody>{findings.slice(page * 50, (page + 1) * 50).map(f => <tr key={f.id}><td>{f.vulnerabilityId || 'No CVE identifier'}</td><td>{f.vulnerableProductVersions}</td><td>{f.cvssSeverity}</td><td>{f.scanTimestamp}</td></tr>)}</tbody></table></div>
        <button className="btn btn-outline-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button><span className="mx-3">Page {page + 1} of {Math.ceil(findings.length / 50)}</span><button className="btn btn-outline-secondary" disabled={(page + 1) * 50 >= findings.length} onClick={() => setPage(page + 1)}>Next</button>
      </>}
    </>}
  </div>;
}
