import React, { useEffect, useState } from 'react';
import { getIntegrationSummary, getScanners, getIntegrationSubjects, getIntegrationRuns, type Scanner, type IntegrationSummary, type IntegrationSubject, type IntegrationRun, type IntegrationPage } from '../services/integrationsService';
import { coverageLabel } from '../services/integrationPresentation';
import { hasRole } from '../utils/auth';
import { formatServerDate } from '../utils/dateUtils';
import IntegrationFindingList from './IntegrationFindingList';
import IntegrationScannerConfig from './IntegrationScannerConfig';
import IntegrationRunEvidence from './IntegrationRunEvidence';
import Pagination from './Pagination';

export default function IntegrationDashboard() {
  const [summary, setSummary] = useState<IntegrationSummary | null>(null);
  const [scanners, setScanners] = useState<Scanner[]>([]);
  const [scannerId, setScannerId] = useState<number | undefined>();
  const [subjects, setSubjects] = useState<IntegrationPage<IntegrationSubject> | null>(null);
  const [runs, setRuns] = useState<IntegrationPage<IntegrationRun> | null>(null);
  const [subjectPage, setSubjectPage] = useState(0);
  const [runPage, setRunPage] = useState(0);
  const [tab, setTab] = useState('findings');
  const [admin, setAdmin] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [expandedRun, setExpandedRun] = useState<number | null>(null);
  useEffect(() => { setAdmin(hasRole('ADMIN')); }, []);
  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    Promise.all([getIntegrationSummary(), getScanners()])
      .then(([nextSummary, nextScanners]) => { if (active) { setSummary(nextSummary); setScanners(nextScanners); } })
      .catch(err => { if (active) setError(err instanceof Error ? err.message : 'Could not load integrations.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [refresh]);
  useEffect(() => {
    let active = true;
    if (tab === 'targets' && scannerId) {
      setSubjects(null); setError('');
      getIntegrationSubjects(scannerId, subjectPage).then(data => { if (active) setSubjects(data); })
        .catch(err => { if (active) setError(err instanceof Error ? err.message : 'Could not load targets.'); });
    }
    if (tab === 'runs') {
      setRuns(null); setError('');
      getIntegrationRuns(scannerId, runPage).then(data => { if (active) setRuns(data); })
        .catch(err => { if (active) setError(err instanceof Error ? err.message : 'Could not load scan history.'); });
    }
    return () => { active = false; };
  }, [scannerId, tab, subjectPage, runPage, refresh]);
  const current = scanners.find(scanner => scanner.id === scannerId);
  const cards: [string, number][] = summary ? [
    ['Open findings', summary.openFindings], ['Registered targets', summary.totalSubjects],
    ['Current', summary.healthySubjects], ['Failed', summary.failedSubjects],
    ['Stale', summary.staleSubjects], ['Not scanned', summary.unscannedSubjects],
  ] : [];
  return <div className="container-fluid py-4">
    <header className="d-flex justify-content-between align-items-center mb-4"><div><h1 className="h3">Integration results</h1><p className="text-muted mb-0">Findings, evidence and scan coverage from your security scanners.</p></div><button className="btn btn-outline-primary" onClick={() => setRefresh(value => value + 1)}>Refresh</button></header>
    {error && <div className="alert alert-danger" role="alert">{error}</div>}
    {loading ? <p role="status">Loading scanner coverage…</p> : <div className="row g-3 mb-4">{cards.map(([label, count]) => <div className="col-6 col-lg-2" key={label}><div className="card h-100"><div className="card-body"><div className="fs-3 fw-semibold">{count}</div><div className="text-muted small">{label}</div></div></div></div>)}</div>}
    <p className="small text-muted">An empty finding list does not prove a clean scan. Review target coverage and failed or incomplete runs. Stale and failed counts may overlap.</p>
    <label className="form-label mb-3">Scanner<select className="form-select" value={scannerId || ''} onChange={e => { setScannerId(e.target.value ? Number(e.target.value) : undefined); setSubjectPage(0); setRunPage(0); }}><option value="">All scanners</option>{scanners.map(scanner => <option key={scanner.id} value={scanner.id}>{scanner.name}{scanner.enabled ? '' : ' (disabled)'}</option>)}</select></label>
    <ul className="nav nav-tabs mb-3">{[['findings', 'Findings'], ['targets', 'Target coverage'], ['runs', 'Scan history'], ...(admin ? [['settings', 'Scanner settings']] : [])].map(([key, title]) => <li className="nav-item" key={key}><button className={`nav-link ${tab === key ? 'active' : ''}`} onClick={() => setTab(key)}>{title}</button></li>)}</ul>
    {tab === 'findings' && <IntegrationFindingList scannerId={scannerId} refresh={refresh} />}
    {tab === 'settings' && admin && <IntegrationScannerConfig key={scannerId || 'new'} scanner={current} onSaved={() => setRefresh(value => value + 1)} />}
    {tab === 'targets' && (!scannerId ? <p>Select a scanner to review its registered targets.</p> : !subjects ? !error && <p role="status">Loading targets…</p> : <>
      <div className="table-responsive"><table className="table table-striped"><thead><tr><th>Target</th><th>Owner</th><th>Coverage</th><th>Last scan</th><th>Last success</th><th>Open findings</th></tr></thead><tbody>{subjects.content.map(subject => <tr key={subject.id}><td><a href={`/assets/${subject.assetId}`}>{subject.name}</a></td><td>{subject.owner || 'Unassigned'}</td><td>{coverageLabel(subject)}</td><td>{formatServerDate(subject.lastScanAt, undefined, 'Never')}</td><td>{formatServerDate(subject.lastSuccessfulScanAt, undefined, 'Never')}</td><td>{subject.openFindings}</td></tr>)}{!subjects.content.length && <tr><td colSpan={6}>No registered targets. {admin && 'Register targets in scanner settings.'}</td></tr>}</tbody></table></div>
      <Pagination currentPage={subjectPage} totalPages={subjects.totalPages} totalElements={subjects.totalElements} pageSize={25} onPageChange={setSubjectPage} />
    </>)}
    {tab === 'runs' && (!runs ? !error && <p role="status">Loading scan history…</p> : <>
      <div className="table-responsive"><table className="table table-striped"><thead><tr><th>Scanner / target</th><th>Result</th><th>Coverage</th><th>Completed</th><th>Accepted / resolved</th><th>Evidence</th></tr></thead><tbody>{runs.content.map(run => <React.Fragment key={run.id}><tr><td>{run.scannerName}<div className="small text-muted">{run.subjectName}</div></td><td>{run.status}</td><td>{run.completeCoverage ? 'Complete' : 'Incomplete; missing findings retained'}</td><td>{formatServerDate(run.completedAt)}</td><td>{run.accepted} / {run.resolved}</td><td><button className="btn btn-sm btn-outline-secondary" onClick={() => setExpandedRun(expandedRun === run.id ? null : run.id)}>Run details</button></td></tr>{expandedRun === run.id && <tr><td colSpan={6}><IntegrationRunEvidence id={run.id} /></td></tr>}</React.Fragment>)}{!runs.content.length && <tr><td colSpan={6}>No scans have been received for this selection.</td></tr>}</tbody></table></div>
      <Pagination currentPage={runPage} totalPages={runs.totalPages} totalElements={runs.totalElements} pageSize={25} onPageChange={setRunPage} />
    </>)}
  </div>;
}
