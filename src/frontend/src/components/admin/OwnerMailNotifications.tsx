import React, { useCallback, useEffect, useState } from 'react';
import { authenticatedGet, authenticatedPost } from '../../utils/auth';

interface Notification {
  id: number;
  awsAccountId: string;
  ownerEmail: string;
  welcomeEmail: { status: string; retryable: boolean; errorCode?: string };
}

export default function OwnerMailNotifications() {
  const [rows, setRows] = useState<Notification[]>([]);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const response = await authenticatedGet(`/api/admin/owner-mail-notifications?page=${page}&pageSize=20`);
      if (!response.ok) throw new Error('Could not load owner-mail delivery records. ADMIN access is required.');
      const data = await response.json();
      setRows(data.notifications);
      setTotal(data.totalElements);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load delivery records.');
    } finally { setBusy(false); }
  }, [page]);
  useEffect(() => { void load(); }, [load]);
  const retry = async (id: number) => {
    setBusy(true);
    setError('');
    try {
      const response = await authenticatedPost(`/api/admin/owner-mail-notifications/${id}/retry`, {});
      if (!response.ok) throw new Error(response.status === 409
        ? 'This record is no longer retryable. Refresh to see its current state.' : 'Retry failed. Refresh before trying again.');
      await load();
    } catch (err) { setError(err instanceof Error ? err.message : 'Retry failed.'); }
    finally { setBusy(false); }
  };
  return <section className="card mt-4" aria-labelledby="owner-mail-title">
    <div className="card-body">
      <h2 className="h5" id="owner-mail-title">Owner welcome-mail delivery</h2>
      <p className="small text-muted">Records are retained for 90 days. SENT means the mail server accepted the message. Pending delivery with an uncertain outcome cannot be retried safely.</p>
      {error && <div className="alert alert-danger" role="alert">{error}</div>}
      {busy && <p role="status">Loading delivery status…</p>}
      {!busy && rows.length === 0 && !error && <p>No retained delivery records.</p>}
      <div className="table-responsive"><table className="table">
        <thead><tr><th>Account</th><th>Owner</th><th>Status</th><th>Action</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.id}>
          <td>{row.awsAccountId}</td><td>{row.ownerEmail}</td>
          <td>{row.welcomeEmail.status}{row.welcomeEmail.errorCode && ` — ${row.welcomeEmail.errorCode}`}</td>
          <td>{row.welcomeEmail.retryable && <button className="btn btn-sm btn-outline-primary" disabled={busy}
            onClick={() => void retry(row.id)}>Retry notification #{row.id}</button>}</td>
        </tr>)}</tbody>
      </table></div>
      <div className="d-flex gap-2">
        <button className="btn btn-outline-secondary" disabled={busy} onClick={() => void load()}>Refresh delivery status</button>
        <button className="btn btn-outline-secondary" disabled={busy || page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <button className="btn btn-outline-secondary" disabled={busy || (page + 1) * 20 >= total} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </div>
  </section>;
}
