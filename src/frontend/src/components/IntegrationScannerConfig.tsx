import React, { useEffect, useState } from 'react';
import { saveScanner, bindIntegrationSubject, type Scanner } from '../services/integrationsService';
import { authenticatedGet } from '../utils/auth';

export default function IntegrationScannerConfig({ scanner, onSaved }: { scanner?: Scanner; onSaved: () => void }) {
  const [name, setName] = useState(scanner?.name || '');
  const [source, setSource] = useState(scanner?.source || 'VISUAL');
  const [serviceUserId, setServiceUserId] = useState(scanner?.serviceUserId || 0);
  const [hours, setHours] = useState(scanner?.staleAfterHours || 24);
  const [enabled, setEnabled] = useState(scanner?.enabled ?? true);
  const [users, setUsers] = useState<{ id: number; username: string; email: string }[]>([]);
  const [targetId, setTargetId] = useState('');
  const [targetKind, setTargetKind] = useState<'assetId' | 'githubRepositoryId'>(scanner?.source === 'GITHUB_AI' ? 'githubRepositoryId' : 'assetId');
  const [target, setTarget] = useState<Record<string, string | number | null> | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    authenticatedGet('/api/users').then(async response => {
      if (!response.ok) throw new Error('Could not load service users.');
      const data = await response.json();
      if (active) setUsers(data);
    }).catch(err => { if (active) setError(err instanceof Error ? err.message : 'Could not load users.'); });
    return () => { active = false; };
  }, []);
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true); setError(''); setMessage('');
    try {
      await saveScanner({ name: name.trim(), source, serviceUserId, staleAfterHours: hours, enabled }, scanner?.id);
      setMessage('Scanner saved.');
      onSaved();
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not save scanner.'); }
    finally { setBusy(false); }
  }
  async function preview(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true); setError(''); setTarget(null);
    try {
      if (!/^[1-9][0-9]*$/.test(targetId) || !Number.isSafeInteger(Number(targetId))) throw new Error('Enter a positive inventory ID.');
      const response = await authenticatedGet(`/api/integrations/v1/binding-target?${targetKind}=${targetId}`);
      if (!response.ok) throw new Error('Inventory target unavailable. Check its ID and your access.');
      setTarget(await response.json());
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not inspect target.'); }
    finally { setBusy(false); }
  }
  async function bind() {
    if (!scanner || !target) return;
    setBusy(true); setError(''); setMessage('');
    try {
      await bindIntegrationSubject(scanner.id, { [targetKind]: Number(target.id) });
      setMessage('Target registered. The service user must separately have asset access.');
      setTargetId(''); setTarget(null); onSaved();
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not register target.'); }
    finally { setBusy(false); }
  }
  return <section className="card mb-3"><div className="card-body">
    <h2 className="h5">{scanner ? 'Scanner settings' : 'Register a scanner'}</h2>
    <p className="small text-muted">Only the selected service user can submit results for registered targets. Ownership and workgroups are managed in the asset inventory.</p>
    {error && <div className="alert alert-danger" role="alert">{error}</div>}
    {message && <div className="alert alert-success" role="status">{message}</div>}
    <form onSubmit={submit} className="row g-3">
      <div className="col-md-4"><label className="form-label">Name<input className="form-control" required maxLength={100} value={name} onChange={e => setName(e.target.value)} /></label></div>
      <div className="col-md-3"><label className="form-label">Source<select className="form-select" value={source} disabled={!!scanner} onChange={e => setSource(e.target.value)}><option value="VISUAL">Visual scanner</option><option value="WEB_SECURITY">Web security scanner</option><option value="GITHUB_AI">GitHub AI</option><option value="SOURCE_CODE">Source code review</option></select></label></div>
      <div className="col-md-5"><label className="form-label">Service user<select className="form-select" required value={serviceUserId || ''} onChange={e => setServiceUserId(Number(e.target.value))}><option value="">Select a user</option>{users.map(user => <option key={user.id} value={user.id}>{user.username} ({user.email})</option>)}</select></label></div>
      <div className="col-md-4"><label className="form-label">Expected scan interval (hours)<input className="form-control" type="number" min={1} max={8760} required value={hours} onChange={e => setHours(Number(e.target.value))} /></label></div>
      <div className="col-md-4 align-self-center"><label className="form-check"><input className="form-check-input" type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} /><span className="form-check-label">Accept scan submissions</span></label></div>
      <div className="col-md-4 align-self-center"><button className="btn btn-primary" disabled={busy}>Save scanner</button></div>
    </form>
    {scanner && <div className="border-top mt-3 pt-3">
      <form onSubmit={preview}>
        <label className="form-label">Target type<select className="form-select" value={targetKind} disabled={busy} onChange={e => { setTargetKind(e.target.value as typeof targetKind); setTarget(null); }}>
          {scanner.source !== 'GITHUB_AI' && <option value="assetId">Asset inventory ID</option>}
          {['GITHUB_AI', 'SOURCE_CODE'].includes(scanner.source) && <option value="githubRepositoryId">GitHub inventory ID</option>}
        </select></label>
        <label className="form-label ms-2">Inventory ID<input className="form-control" required inputMode="numeric" value={targetId} disabled={busy} onChange={e => { setTargetId(e.target.value); setTarget(null); }} /></label>
        <button className="btn btn-outline-primary ms-2" disabled={busy}>Inspect target</button>
      </form>
      {target && <div className="alert alert-info"><p>Confirm this exact target:</p><dl>{Object.entries(target).map(([key, value]) => <React.Fragment key={key}><dt>{key}</dt><dd>{value ?? 'Not configured'}</dd></React.Fragment>)}</dl><button className="btn btn-primary" disabled={busy} onClick={bind}>Register this target</button></div>}
      <p className="text-muted small mb-0">Scanner ID: <code>{scanner.id}</code>. Use the inventory ID, not a hostname or repository name.</p>
    </div>}
  </div></section>;
}
