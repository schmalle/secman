import React, { useEffect, useState } from 'react';
import { authenticatedDelete, authenticatedGet, authenticatedPost } from '../utils/auth';

const endpoint = '/api/profile/mcp-keys';

export default function PersonalMcpKeys() {
  const [keys, setKeys] = useState<{ keyId: string; expiresAt: string }[]>([]);
  const [secret, setSecret] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function refresh() {
    const response = await authenticatedGet(endpoint);
    if (!response.ok) throw new Error('Could not load personal keys.');
    setKeys(await response.json());
  }

  useEffect(() => { refresh().catch(() => setError('Could not load personal keys.')); }, []);

  async function changeKey(keyId?: string) {
    setBusy(true);
    setError('');
    setSecret('');
    try {
      const response = keyId ? await authenticatedDelete(endpoint + '/' + encodeURIComponent(keyId))
        : await authenticatedPost(endpoint, {});
      if (!response.ok) throw new Error('Could not update personal keys.');
      if (!keyId) setSecret((await response.json()).apiKey);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not update personal keys.');
    } finally {
      setBusy(false);
    }
  }

  return <section className="card card-body mb-3">
    <h3>Personal MCP keys</h3>
    <p>Read the accounts and assets you can see. You can have five active keys. Each expires after 90 days.</p>
    <div><button className="btn btn-primary" disabled={busy} onClick={() => changeKey()}>Create personal key</button></div>
    {error && <p role="alert">{error}</p>}
    {secret && <div className="alert alert-info mt-2">Copy this key now. It is shown once. <code>{secret}</code>
      <button className="btn btn-link" onClick={() => setSecret('')}>Hide key</button></div>}
    {keys.map(key => <div className="mt-2" key={key.keyId}><code>{key.keyId}</code> · Expires {key.expiresAt.slice(0, 10)}
      <button className="btn btn-link text-danger" disabled={busy} onClick={() => changeKey(key.keyId)}>Revoke</button></div>)}
  </section>;
}
