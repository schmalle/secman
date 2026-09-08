import React, { useEffect, useState } from 'react';
import { getIntegrationFindings, getIntegrationFinding, downloadIntegrationAttachment, type IntegrationFinding, type IntegrationPage } from '../services/integrationsService';
import { safeEvidenceUrl, type FindingFilters } from '../services/integrationPresentation';
import { formatServerDate } from '../utils/dateUtils';
import Pagination from './Pagination';

function EvidenceLink({ value, label }: { value?: string | null; label: string }) {
  const href = safeEvidenceUrl(value);
  return href ? <a href={href} target="_blank" rel="noopener noreferrer">{label}</a> : null;
}

export function IntegrationFindingDetail({ finding, onClose }: { finding: IntegrationFinding; onClose: () => void }) {
  const [error, setError] = useState('');
  return <section className="card my-3 border-primary" aria-label="Finding details">
    <div className="card-header d-flex justify-content-between align-items-center">
      <h3 className="h5 mb-0">{finding.title}</h3>
      <button className="btn btn-sm btn-outline-secondary" onClick={onClose}>Close details</button>
    </div>
    <div className="card-body">
      <p>{finding.severity} · {finding.state} · {finding.scannerName} · {finding.excepted ? 'Covered by an exception' : 'No active exception'}</p>
      <p><a href={`/assets/${finding.assetId}`}>{finding.subjectName}</a> · Owner: {finding.owner || 'Unassigned'}</p>
      <dl className="row small">
        <dt className="col-sm-3">First observed</dt><dd className="col-sm-9">{formatServerDate(finding.firstSeenAt)}</dd>
        <dt className="col-sm-3">Last observed</dt><dd className="col-sm-9">{formatServerDate(finding.lastSeenAt)}</dd>
        {finding.resolvedAt && <><dt className="col-sm-3">Resolved</dt><dd className="col-sm-9">{formatServerDate(finding.resolvedAt)}</dd></>}
        {finding.filePath && <><dt className="col-sm-3">Location</dt><dd className="col-sm-9 text-break">{finding.filePath} {finding.lineRange}</dd></>}
        {(finding.engine || finding.model) && <><dt className="col-sm-3">Scanner engine / model</dt><dd className="col-sm-9">{finding.engine} {finding.model}</dd></>}
        {finding.commitSha && <><dt className="col-sm-3">Commit</dt><dd className="col-sm-9 text-break">{finding.commitSha}</dd></>}
        {finding.confidence != null && <><dt className="col-sm-3">Confidence</dt><dd className="col-sm-9">{Math.round(finding.confidence * 100)}%</dd></>}
      </dl>
      {[['Description', finding.description], ['Recommendation', finding.recommendation], ['Evidence', finding.evidence]].map(([label, value]) => value &&
        <div className="mb-3" key={label}><h4 className="h6">{label}</h4><div className="text-break" style={{ whiteSpace: 'pre-wrap' }}>{value}</div></div>)}
      <div className="d-flex flex-wrap gap-3 mb-3">
        <EvidenceLink value={finding.url} label="Source page" />
        <EvidenceLink value={finding.issueUrl} label="Issue" />
        <EvidenceLink value={finding.fixPrUrl} label="Fix pull request" />
        {finding.vulnerabilityId != null && <a href={`/assets/${finding.assetId}`}>Manage vulnerability and request an exception</a>}
      </div>
      {finding.attachments?.length > 0 && <><h4 className="h6">Attachments</h4><ul className="list-unstyled">
        {finding.attachments.map(attachment => <li key={attachment.id} className="mb-1"><button className="btn btn-link p-0" onClick={() => {
          setError('');
          downloadIntegrationAttachment(finding.id, attachment.id, attachment.fileName).catch(err => setError(err instanceof Error ? err.message : 'Download failed.'));
        }}>{attachment.fileName}</button> <span className="text-muted small">{attachment.contentType}</span></li>)}
      </ul></>}
      {error && <p className="text-danger" role="alert">{error}</p>}
    </div>
  </section>;
}

export default function IntegrationFindingList({ githubRepositoryId, scannerId, refresh = 0 }: { githubRepositoryId?: number; scannerId?: number; refresh?: number }) {
  const [filters, setFilters] = useState<FindingFilters>({ state: 'OPEN' });
  const [page, setPage] = useState(0);
  const [data, setData] = useState<IntegrationPage<IntegrationFinding> | null>(null);
  const [selected, setSelected] = useState<IntegrationFinding | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const query = JSON.stringify({ ...filters, githubRepositoryId, scannerId });
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    setSelected(null);
    setSelectedId(null);
    getIntegrationFindings(JSON.parse(query), page)
      .then(result => { if (active) setData(result); })
      .catch(err => { if (active) setError(err instanceof Error ? err.message : 'Could not load findings.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [query, page, refresh]);

  const update = (key: keyof FindingFilters, value: string) => {
    setPage(0);
    setFilters(previous => ({ ...previous, [key]: value }));
  };
  useEffect(() => {
    let active = true;
    if (selectedId == null) { setSelected(null); setDetailLoading(false); return; }
    setDetailLoading(true);
    getIntegrationFinding(selectedId).then(value => { if (active) setSelected(value); })
      .catch(err => { if (active) setError(err instanceof Error ? err.message : 'Could not load finding.'); })
      .finally(() => { if (active) setDetailLoading(false); });
    return () => { active = false; };
  }, [selectedId]);

  return <section aria-label={githubRepositoryId ? 'AI repository findings' : 'Integration findings'}>
    <h2 className="h5">{githubRepositoryId ? 'AI scanner findings' : 'Findings'} {data && <span className="text-muted">({data.totalElements})</span>}</h2>
    {githubRepositoryId && <p className="small text-muted">Scanner observations are shown separately from Dependabot alerts.</p>}
    <div className="row g-2 mb-3">
      <div className="col-md"><label className="form-label small" htmlFor={`finding-search-${githubRepositoryId || 'all'}`}>Search</label><input id={`finding-search-${githubRepositoryId || 'all'}`} className="form-control form-control-sm" value={filters.search || ''} onChange={e => update('search', e.target.value)} placeholder="Title or subject" /></div>
      {!githubRepositoryId && <div className="col-md"><label className="form-label small">Source<select className="form-select form-select-sm" value={filters.source || ''} onChange={e => update('source', e.target.value)}><option value="">All sources</option><option value="GITHUB_AI">GitHub AI</option><option value="VISUAL">Visual scanner</option></select></label></div>}
      <div className="col-md"><label className="form-label small">Owner<input className="form-control form-control-sm" value={filters.owner || ''} onChange={e => update('owner', e.target.value)} placeholder="Owner name" /></label></div>
      <div className="col-md"><label className="form-label small">Severity<select className="form-select form-select-sm" value={filters.severity || ''} onChange={e => update('severity', e.target.value)}><option value="">All severities</option>{['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'].map(value => <option key={value}>{value}</option>)}</select></label></div>
      <div className="col-md"><label className="form-label small">State<select className="form-select form-select-sm" value={filters.state || ''} onChange={e => update('state', e.target.value)}><option value="">All states</option><option>OPEN</option><option>RESOLVED</option></select></label></div>
    </div>
    {error && <div className="alert alert-danger" role="alert">{error}</div>}
    {loading ? <p role="status">Loading findings…</p> : !error && <>
      <div className="table-responsive"><table className="table table-striped align-middle"><thead><tr><th>Finding</th><th>Subject / owner</th><th>Severity</th><th>State</th><th>Last observed</th></tr></thead><tbody>
        {data?.content.map(finding => <tr key={finding.id}>
          <td><button className="btn btn-link p-0 text-start" disabled={detailLoading} onClick={() => setSelectedId(finding.id)}>{finding.title}</button><div className="text-muted small">{finding.scannerName}</div></td>
          <td><a href={`/assets/${finding.assetId}`}>{finding.subjectName}</a><div className="small text-muted">{finding.owner || 'Unassigned'}</div></td>
          <td><span className={`badge ${['CRITICAL', 'HIGH'].includes(finding.severity) ? 'bg-danger' : 'bg-secondary'}`}>{finding.severity}</span></td>
          <td>{finding.state}{finding.excepted && <div className="small text-muted">Excepted</div>}</td><td>{formatServerDate(finding.lastSeenAt)}</td>
        </tr>)}
        {!data?.content.length && <tr><td colSpan={5} className="text-muted py-3">No findings match these filters. Check scan coverage before interpreting this as a clean result.</td></tr>}
      </tbody></table></div>
      {data && <Pagination currentPage={page} totalPages={data.totalPages} totalElements={data.totalElements} pageSize={25} onPageChange={setPage} />}
    </>}
    {detailLoading && <p role="status">Loading evidence…</p>}
    {selected && <IntegrationFindingDetail finding={selected} onClose={() => setSelectedId(null)} />}
  </section>;
}
