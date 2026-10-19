import React, { useEffect, useRef, useState } from 'react';
import AiBuddyPatchGuide from './AiBuddyPatchGuide';
import AiBuddyInventoryForm from './AiBuddyInventoryForm';
import { parseBuddyReport } from '../services/aiBuddyReport';
import { buddyEvidenceUrl, buddyLabel, buddyRequest, type BuddyCapabilities, type BuddyInventoryInput, type BuddyReport, type BuddyStatus } from '../services/aiBuddyService';
import { ApiError } from '../utils/apiJson';
import { canAccessBetaFeatures } from '../utils/permissions';
import { formatServerDateTime } from '../utils/dateUtils';

export default function AiBuddyPanel({ assetId }: { assetId: number }) {
  const [capability, setCapability] = useState<BuddyCapabilities | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [stage, setStage] = useState('');
  const [error, setError] = useState('');
  const [report, setReport] = useState<BuddyReport | null>(null);
  const [page, setPage] = useState(0);
  const active = useRef<string | null>(null);
  const lock = useRef(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const mounted = useRef(false);
  const request = useRef<AbortController | null>(null);

  useEffect(() => {
    mounted.current = true;
    const refresh = async () => {
      try {
        const current = await buddyRequest<BuddyCapabilities>('/capabilities');
        if (!mounted.current) return;
        setCapability(current);
        if (!current.available) { setReport(null); if (!current.enabled) setOpen(false); }
        if (active.current && !lock.current && current.available) {
          const jobId = active.current;
          const updated = parseBuddyReport(await buddyRequest<unknown>(`/assets/${assetId}/jobs/${jobId}/report`));
          if (mounted.current && active.current === jobId) setReport(updated);
        }
      } catch (err) {
        if (!mounted.current) return;
        if (err instanceof ApiError && [401, 403, 404].includes(err.status)) { setReport(null); setOpen(false); active.current = null; }
        else if (active.current && !lock.current) { setReport(null); setError('This temporary report has expired or is unavailable. Generate a new report.'); active.current = null; }
      }
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 30000);
    window.addEventListener('focus', refresh);
    return () => {
      mounted.current = false;
      window.clearInterval(timer);
      window.removeEventListener('focus', refresh);
      request.current?.abort();
      if (active.current) void buddyRequest(`/assets/${assetId}/jobs/${active.current}`, 'DELETE').catch(() => undefined);
      active.current = null;
    };
  }, [assetId]);

  const cancel = async () => {
    request.current?.abort(); request.current = null;
    if (active.current) await buddyRequest(`/assets/${assetId}/jobs/${active.current}`, 'DELETE').catch(() => undefined);
    active.current = null; lock.current = false; setBusy(false); setReport(null);
  };
  const close = () => { void cancel(); setOpen(false); button.current?.focus(); };
  const generate = async (inventory?: BuddyInventoryInput) => {
    if (lock.current) return;
    lock.current = true; setBusy(true); setOpen(true); setReport(null); setPage(0); setError(''); setStage('Checking HIGH and CRITICAL findings and installed software');
    window.setTimeout(() => heading.current?.focus(), 0);
    const abort = new AbortController(); request.current = abort;
    try {
      const started = await buddyRequest<BuddyStatus>(`/assets/${assetId}/jobs`, 'POST', { requestId: crypto.randomUUID(), ...(inventory ? { inventory } : {}) }, abort.signal);
      active.current = started.jobId;
      while (!abort.signal.aborted) {
        const status = await buddyRequest<BuddyStatus>(`/assets/${assetId}/jobs/${started.jobId}`, 'GET', undefined, abort.signal);
        if (!mounted.current) return;
        setStage(buddyLabel(status.stage));
        if (status.reportAvailable) {
          const result = parseBuddyReport(await buddyRequest<unknown>(`/assets/${assetId}/jobs/${started.jobId}/report`, 'GET', undefined, abort.signal));
          if (result.schemaVersion !== 1) throw new Error('Unsupported report format');
          if (mounted.current) setReport(result);
          break;
        }
        if (['FAILED', 'CANCELLED'].includes(status.state)) throw new Error(buddyLabel(status.error?.code || status.state));
        await new Promise<void>(resolve => {
          const finish = () => { window.clearTimeout(timer); abort.signal.removeEventListener('abort', finish); resolve(); };
          const timer = window.setTimeout(finish, 2000); abort.signal.addEventListener('abort', finish, { once: true });
        });
      }
    } catch (err) {
      if (mounted.current && !abort.signal.aborted) {
        setReport(null);
        setError(err instanceof Error ? buddyLabel(err.message) : 'Analysis unavailable');
        if (err instanceof ApiError && [401, 403, 404].includes(err.status)) { setOpen(false); setCapability(null); }
      }
    } finally {
      if (request.current === abort) { lock.current = false; if (mounted.current) setBusy(false); }
    }
  };

  if (!capability?.eligible || !canAccessBetaFeatures(capability.roles)) return null;
  const ledger = report?.findings.slice(page * 50, (page + 1) * 50) || [];
  return <div className="my-3">
    <button ref={button} type="button" className="btn btn-outline-primary" disabled={busy || !(capability.enabled ?? capability.available)}
      aria-expanded={open} onClick={() => { if (capability.available && !report) void generate(); else { setOpen(true); window.setTimeout(() => heading.current?.focus(), 0); } }}>Create patch guide · AI buddy</button>
    {!capability.available && <small className="ms-2 text-muted">{buddyLabel(capability.unavailableReason || 'NOT_CONFIGURED')}.</small>}
    {open && <section className="card mt-3" aria-label="AI buddy remediation report" onKeyDown={event => { if (event.key === 'Escape') close(); }}>
      <div className="card-header d-flex justify-content-between align-items-center"><h3 ref={heading} tabIndex={-1} className="h5 mb-0">Patch guide</h3><button type="button" className="btn btn-sm btn-outline-secondary" onClick={close}>Close</button></div>
      <div className="card-body">
        {!capability.available && <div className="alert alert-info" role="status">{buddyLabel(capability.unavailableReason || 'NOT_CONFIGURED')}. Report generation will be available when setup is complete.
          {capability.roles.includes('ADMIN') && capability.enabled && <a className="d-block mt-2" href="/admin/translation-config?setup=ai-buddy#aiBuddyProviders">Open configuration</a>}
        </div>}
        <p>Update instructions for recorded HIGH and CRITICAL findings. You apply the changes.</p>
        <details className="mb-3"><summary>Scope and privacy</summary><p>Findings across sources are included, including excepted findings; installer artifacts are excluded. Table filters and pagination do not affect the guide. Only allowlisted public product/version information and remediation candidates are sent to the configured provider. Hostnames, IPs, account IDs and usernames stay in SecMan. Guides expire after at most 30 minutes.</p></details>
        {!busy && capability.available && (report || error) && <button type="button" className="btn btn-outline-primary mb-3" onClick={() => void generate()}>{report ? 'Create a new guide' : 'Retry patch guide'}</button>}
        {busy && <div role="status" aria-live="polite"><span className="spinner-border spinner-border-sm me-2" aria-hidden="true" />{stage}<button className="btn btn-sm btn-outline-secondary ms-3" type="button" onClick={() => void cancel()}>Cancel</button></div>}
        {error && <div className="alert alert-warning" role="alert">{error}. Retry explicitly when the issue has been resolved.</div>}
        {report && <>
          <AiBuddyPatchGuide key={report.jobId} report={report} />
          {capability.inventoryInputSupported && <AiBuddyInventoryForm busy={busy} onCreate={inventory => void generate(inventory)} />}
          <details className="mt-3"><summary>Show evidence, timestamps and supporting findings</summary>
          <h4 className="h5">{report.system.displayName}</h4>
          <p><strong>Report completeness: {buddyLabel(report.analysis.completeness)}</strong> · {report.analysis.model ? `Action order generated with ${report.analysis.model}` : 'No model call was needed or available; see limitations.'}</p>
          {report.analysis.completeness === 'PARTIAL' && <div className="alert alert-warning" role="alert">This report is partial. Review the limitations and findings without verified remediation before acting.</div>}
          <p>{report.system.osNameVersion || 'OS inventory missing'} · Build: {report.system.build || 'unknown'} · Architecture: {report.system.architecture || 'unknown'}</p>
          <p>Generated: {formatServerDateTime(report.generatedAt)} · Snapshot: {formatServerDateTime(report.snapshot.capturedAt)} · Last seen: {report.snapshot.assetLastSeen ? formatServerDateTime(report.snapshot.assetLastSeen) : 'unknown'}</p>
          {report.snapshot.stale && <div className="alert alert-warning" role="alert">Snapshot warning: {report.snapshot.staleReasons.map(buddyLabel).join(', ')}. Generate again after inventory or findings change.</div>}
          <p>Sensor last seen: {report.snapshot.sensorLastSeen ? formatServerDateTime(report.snapshot.sensorLastSeen) : 'unknown'} · Finding imports: {report.snapshot.vulnerabilityImportFrom ? formatServerDateTime(report.snapshot.vulnerabilityImportFrom) : 'unknown'} to {report.snapshot.vulnerabilityImportTo ? formatServerDateTime(report.snapshot.vulnerabilityImportTo) : 'unknown'}</p>
          <p>{report.scope.consideredFindingCount} findings considered; {report.scope.uniqueCveCount} unique CVEs; {report.scope.nonCveFindingCount} findings without CVEs; {report.scope.exceptedFindingCount} excepted; {report.scope.excludedFindingCount} installer artifacts excluded; {report.scope.duplicateRowCount} duplicate rows retained.</p>
          <p><strong>{report.analysis.verifiedCoveredFindingCount} verified coverage</strong>; {report.analysis.conditionalFindingCount} require applicability checks; {report.analysis.noVerifiedRemediationCount} have no verified remediation.</p>
          <p>Verified means an authoritative relationship and all required applicability checks are satisfied. “Requires applicability check” identifies a documented relationship with unresolved prerequisites. “Insufficient information” is advice requiring investigation.</p>
          <h4 className="h5">Recommended order</h4>
          {!report.actions.length && <p>No verified remediation candidates were found. Check vendor guidance and missing inventory; this is not proof that the system is safe.</p>}
          {report.actions.map(action => <div key={action.id} className="border rounded p-3 mb-2">
            <h5>{action.order}. {buddyLabel(action.type)}: {action.targetProduct}</h5>
            <p><strong>{buddyLabel(action.evidenceState)}</strong> · {action.patchIds.join(', ') || 'No verified patch identifier'} {action.targetVersion && ` · Target version/build ${action.targetVersion}`} · Reboot: {buddyLabel(action.reboot)}</p>
            <p>{action.coveredFindingRefs.length} associated findings: {action.evidenceState === 'VERIFIED' ? 'verified coverage' : action.evidenceState === 'REQUIRES_APPLICABILITY_CHECK' ? 'conditional coverage requiring applicability checks' : 'investigation required; no verified coverage'}. A recommendation is not evidence of installation or resolution.</p>
            <ul>{[...action.applicability, ...action.prerequisites].map((check, index) => <li key={`${check.code}-${index}`}>{buddyLabel(check.code)}: {check.satisfied === true ? 'verified' : 'manual verification required'}{check.value && ` (${check.value})`}</li>)}</ul>
            <details><summary>CVEs covered by this action ({action.coveredCves.length})</summary><p>{action.coveredCves.join(', ')}</p></details>
            <ul>{report.evidence.filter(e => action.evidenceRefs.includes(e.ref)).map(e => <li key={e.ref}>{buddyEvidenceUrl(e.url) ? <a href={buddyEvidenceUrl(e.url)!} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">{e.title}</a> : e.title} · {e.source} · Retrieved {formatServerDateTime(e.retrievedAt)}{e.providerAdvice && <p className="mt-2">Provider advice requiring manual verification: {e.providerAdvice}</p>}</li>)}</ul>
          </div>)}
          <h4 className="h5">Findings and remaining issues</h4>
          <div className="table-responsive"><table className="table table-sm"><thead><tr><th>Finding</th><th>Product</th><th>Source</th><th>Evidence outcome</th></tr></thead><tbody>{ledger.map(f => <tr key={f.ref}><td>{f.identifier || 'No CVE identifier'}{f.excepted && ' (excepted)'}</td><td>{f.product || 'unknown'}</td><td>{f.source}</td><td>{buddyLabel(f.disposition)}</td></tr>)}</tbody></table></div>
          <div className="d-flex gap-2"><button className="btn btn-sm btn-outline-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous findings</button><span>Page {page + 1} of {Math.max(1, Math.ceil(report.findings.length / 50))}</span><button className="btn btn-sm btn-outline-secondary" disabled={(page + 1) * 50 >= report.findings.length} onClick={() => setPage(page + 1)}>Next findings</button></div>
          <h4 className="h5 mt-3">Limitations and verification</h4><ul>{report.analysis.limitations.map(code => <li key={code}>{buddyLabel(code)}</li>)}</ul>
          <p>Verify product/build/edition, architecture, installed updates, servicing prerequisites and vendor applicability before acting. Follow vendor installation order and reboot instructions. Confirm installation, refresh inventory and rescan; review findings that remain. This report cannot mark findings resolved.</p>
          </details>
        </>}
      </div>
    </section>}
  </div>;
}
