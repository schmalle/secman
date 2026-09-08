import React, { useEffect, useState } from 'react';
import { saveScanner, bindIntegrationSubject, type Scanner } from '../services/integrationsService';
import { getGithubRepositories } from '../services/githubReposService';
import { authenticatedGet } from '../utils/auth';

export default function IntegrationScannerConfig({ scanner, onSaved }: { scanner?: Scanner; onSaved: () => void }) {
  const [name, setName] = useState(scanner?.name || '');
  const [source, setSource] = useState(scanner?.source || 'VISUAL');
  const [serviceUserId, setServiceUserId] = useState(scanner?.serviceUserId || 0);
  const [hours, setHours] = useState(scanner?.staleAfterHours || 24);
  const [enabled, setEnabled] = useState(scanner?.enabled ?? true);
  const [users, setUsers] = useState<{ id: number; username: string; email: string }[]>([]);
  const [targetName, setTargetName] = useState('');
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
  async function bind(event: React.FormEvent) {
    event.preventDefault();
    if (!scanner) return;
    setBusy(true); setError(''); setMessage('');
    try {
      if (scanner.source === 'GITHUB_AI') {
        const repos = await getGithubRepositories(0, 25, targetName.trim());
        const matches = repos.content.filter(repo => repo.fullName.toLowerCase() === targetName.trim().toLowerCase());
        if (matches.length !== 1 || repos.totalElements > 25) throw new Error('Enter one unambiguous repository name already in the GitHub inventory.');
        await bindIntegrationSubject(scanner.id, { githubRepositoryId: matches[0].id });
      } else {
        const response = await authenticatedGet(`/api/assets/by-name/${encodeURIComponent(targetName.trim())}`);
        if (!response.ok) throw new Error('Asset not found. Register it in the asset inventory first.');
        const asset = await response.json();
        await bindIntegrationSubject(scanner.id, { assetId: asset.id });
      }
      setMessage('Target registered.'); setTargetName(''); onSaved();
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
      <div className="col-md-3"><label className="form-label">Source<select className="form-select" value={source} disabled={!!scanner} onChange={e => setSource(e.target.value)}><option value="VISUAL">Visual scanner</option><option value="GITHUB_AI">GitHub AI</option></select></label></div>
      <div className="col-md-5"><label className="form-label">Service user<select className="form-select" required value={serviceUserId || ''} onChange={e => setServiceUserId(Number(e.target.value))}><option value="">Select a user</option>{users.map(user => <option key={user.id} value={user.id}>{user.username} ({user.email})</option>)}</select></label></div>
      <div className="col-md-4"><label className="form-label">Expected scan interval (hours)<input className="form-control" type="number" min={1} max={8760} required value={hours} onChange={e => setHours(Number(e.target.value))} /></label></div>
      <div className="col-md-4 align-self-center"><label className="form-check"><input className="form-check-input" type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} /><span className="form-check-label">Accept scan submissions</span></label></div>
      <div className="col-md-4 align-self-center"><button className="btn btn-primary" disabled={busy}>Save scanner</button></div>
    </form>
    {scanner && <form onSubmit={bind} className="border-top mt-3 pt-3">
      <label className="form-label">{scanner.source === 'GITHUB_AI' ? 'Repository (owner/name)' : 'Asset hostname'}<input className="form-control" required value={targetName} onChange={e => setTargetName(e.target.value)} /></label>
      <button className="btn btn-outline-primary ms-2" disabled={busy}>Register target</button>
      <p className="text-muted small mb-0">Scanner configuration reference: <code>SECMAN_SCANNER_ID={scanner.id}</code></p>
    </form>}
  </div></section>;
}
