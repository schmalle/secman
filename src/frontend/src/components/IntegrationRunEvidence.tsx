import React, { useEffect, useState } from 'react';
import { downloadIntegrationAttachment, getIntegrationRun, type IntegrationRunDetail } from '../services/integrationsService';
import { safeEvidenceUrl } from '../services/integrationPresentation';

export default function IntegrationRunEvidence({ id }: { id: number }) {
  const [detail, setDetail] = useState<IntegrationRunDetail | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setDetail(null); setError('');
    getIntegrationRun(id).then(value => { if (active) setDetail(value); })
      .catch(err => { if (active) setError(err instanceof Error ? err.message : 'Could not load historical evidence.'); });
    return () => { active = false; };
  }, [id]);
  return <section aria-label="Historical run evidence">
    {error && <p role="alert" className="text-danger">{error}</p>}
    {!detail && !error && <p role="status">Loading historical evidence…</p>}
    {detail && <>
      <p className="small text-muted">Evidence as submitted in this run, independent of the finding’s current state.</p>
      <pre className="text-wrap text-break">{detail.run.metadataJson}</pre>
      {detail.findings.map(finding => <details key={finding.externalId} className="border rounded p-2 mb-2">
        <summary>{finding.severity}: {finding.title}</summary>
        <p className="small text-muted mt-2">{finding.externalId} · {finding.filePath} {finding.lineRange}</p>
        {(['description', 'recommendation', 'evidence'] as const).map(key => finding[key] && <div key={key}><strong>{key}</strong><pre className="text-wrap text-break">{finding[key]}</pre></div>)}
        <p className="small">Engine: {finding.engine || '—'} · Model: {finding.model || '—'} · Confidence: {finding.confidence ?? '—'} · Commit: {finding.commitSha || '—'}</p>
        {(['url', 'issueUrl', 'fixPrUrl'] as const).map(key => {
          const url = safeEvidenceUrl(finding[key]);
          return url && <a key={key} href={url} target="_blank" rel="noopener noreferrer" className="me-3">{key}</a>;
        })}
      </details>)}
      {!detail.findings.length && <p>No findings were submitted in this run. Check its status and coverage before treating it as clean.</p>}
      {detail.attachments.map(attachment => <button key={attachment.id} className="btn btn-sm btn-outline-secondary me-2 mb-2" onClick={() => {
        downloadIntegrationAttachment(attachment.findingId, attachment.id, attachment.fileName)
          .catch(err => setError(err instanceof Error ? err.message : 'Could not download evidence.'));
      }}>Download {attachment.fileName}</button>)}
    </>}
  </section>;
}
