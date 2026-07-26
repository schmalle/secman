import { useEffect, useState } from 'react';
import { authenticatedGet, authenticatedPut, hasRole } from '../utils/auth';

interface AccountFindingAge {
  awsAccountId: string;
  accountName: string;
  oldestFindingFirstSeenAt: string;
  oldestFindingDaysOpen: number;
  oldestFindingCve: string | null;
  oldestFindingSeverity: string | null;
  oldestFindingAssetName: string | null;
  oldestFindingAssetInstanceId: string | null;
  openFindingCount: number;
  affectedAssetCount: number;
}

export default function AccountFindingAgeTable() {
  const [mounted, setMounted] = useState(false);
  const [rows, setRows] = useState<AccountFindingAge[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [nameDraft, setNameDraft] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => setMounted(true), []);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const resp = await authenticatedGet('/api/admin/account-finding-age/top?limit=10');
      if (!resp.ok) {
        setError(resp.status === 403 ? 'Administrator access required.' : 'Failed to load the report.');
        setRows([]);
        return;
      }
      setRows(await resp.json());
    } catch (e) {
      setError('Failed to load the report.');
      setRows([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (mounted && hasRole('ADMIN')) {
      load();
    } else if (mounted) {
      setLoading(false);
    }
  }, [mounted]);

  const startEdit = (row: AccountFindingAge) => {
    setEditingId(row.awsAccountId);
    setNameDraft(row.accountName === row.awsAccountId ? '' : row.accountName);
  };

  const saveName = async (row: AccountFindingAge) => {
    setSaving(true);
    try {
      const resp = await authenticatedPut(
        `/api/admin/aws-accounts/${row.awsAccountId}/name`,
        { name: nameDraft.trim() || null }
      );
      if (!resp.ok) throw new Error('save failed');
      setEditingId(null);
      await load();
    } catch (e) {
      setError('Failed to save the account name.');
    } finally {
      setSaving(false);
    }
  };

  if (!mounted) return null;

  if (!hasRole('ADMIN')) {
    return (
      <div className="container mt-4">
        <div className="alert alert-danger">Administrator access required.</div>
      </div>
    );
  }

  return (
    <div className="container-fluid mt-4">
      <h2 className="mb-1">Longest-Open Findings by Account</h2>
      <p className="text-muted">
        AWS accounts ranked by the age of their oldest still-open vulnerability. Findings covered by
        an active exception are excluded. Click the pencil to name an account.
      </p>

      {error && <div className="alert alert-danger">{error}</div>}

      {loading ? (
        <div className="text-muted">Loading…</div>
      ) : rows.length === 0 ? (
        <div className="alert alert-info">No accounts with open findings.</div>
      ) : (
        <div className="table-responsive">
          <table className="table table-hover align-middle">
            <thead>
              <tr>
                <th>#</th>
                <th>Account name</th>
                <th>Account ID</th>
                <th className="text-end">Days open</th>
                <th>Oldest CVE</th>
                <th>Severity</th>
                <th>Asset</th>
                <th className="text-end">Open findings</th>
                <th className="text-end">Assets</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={r.awsAccountId}>
                  <td>{i + 1}</td>
                  <td>
                    {editingId === r.awsAccountId ? (
                      <div className="d-flex gap-1">
                        <input
                          className="form-control form-control-sm"
                          value={nameDraft}
                          autoFocus
                          disabled={saving}
                          onChange={(e) => setNameDraft(e.target.value)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') saveName(r);
                            if (e.key === 'Escape') setEditingId(null);
                          }}
                        />
                        <button className="btn btn-sm btn-primary" disabled={saving} onClick={() => saveName(r)}>
                          <i className="bi bi-check-lg"></i>
                        </button>
                        <button className="btn btn-sm btn-secondary" disabled={saving} onClick={() => setEditingId(null)}>
                          <i className="bi bi-x-lg"></i>
                        </button>
                      </div>
                    ) : (
                      <>
                        {r.accountName === r.awsAccountId
                          ? <span className="text-muted">unnamed</span>
                          : r.accountName}
                        <button
                          className="btn btn-link btn-sm p-0 ms-2"
                          onClick={() => startEdit(r)}
                          title="Edit account name"
                        >
                          <i className="bi bi-pencil"></i>
                        </button>
                      </>
                    )}
                  </td>
                  <td><code>{r.awsAccountId}</code></td>
                  <td className="text-end fw-bold text-danger">{r.oldestFindingDaysOpen}</td>
                  <td>{r.oldestFindingCve ?? '-'}</td>
                  <td>{r.oldestFindingSeverity ?? '-'}</td>
                  <td>{r.oldestFindingAssetName ?? '-'}</td>
                  <td className="text-end">{r.openFindingCount}</td>
                  <td className="text-end">{r.affectedAssetCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
