import { useEffect, useState } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedDelete } from '../utils/auth';
import { extractErrorMessage } from '../services/workgroupApi';

interface WorkgroupAwsAccountDto {
  id: number;
  workgroupId: number;
  awsAccountId: string;
  createdByUsername: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

interface Props {
  workgroupId: number;
  workgroupName: string;
  isOpen: boolean;
  onClose: () => void;
  onChange?: () => void;
}

export default function WorkgroupAccountsModal({
  workgroupId,
  workgroupName,
  isOpen,
  onClose,
  onChange,
}: Props) {
  const [accounts, setAccounts] = useState<WorkgroupAwsAccountDto[]>([]);
  const [newAccountId, setNewAccountId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const fetchAccounts = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await authenticatedGet(`/api/workgroups/${workgroupId}/aws-accounts`);
      if (!res.ok) {
        setError(await extractErrorMessage(res, 'Failed to load accounts'));
        return;
      }
      setAccounts((await res.json()) as WorkgroupAwsAccountDto[]);
    } catch {
      setError('Failed to load accounts');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      fetchAccounts();
      setNewAccountId('');
      setError(null);
    }
  }, [isOpen, workgroupId]);

  const handleAdd = async () => {
    setError(null);
    if (!/^\d{12}$/.test(newAccountId)) {
      setError('AWS Account ID must be exactly 12 numeric digits');
      return;
    }
    try {
      const res = await authenticatedPost(`/api/workgroups/${workgroupId}/aws-accounts`, {
        awsAccountId: newAccountId,
      });
      if (!res.ok) {
        if (res.status === 409) setError('That account is already assigned to this workgroup');
        else if (res.status === 400) setError(await extractErrorMessage(res, 'Invalid account ID'));
        else setError('Failed to add account');
        return;
      }
      setNewAccountId('');
      await fetchAccounts();
      onChange?.();
    } catch {
      setError('Failed to add account');
    }
  };

  const handleRemove = async (awsAccountId: string) => {
    setError(null);
    try {
      const res = await authenticatedDelete(
        `/api/workgroups/${workgroupId}/aws-accounts/${encodeURIComponent(awsAccountId)}`
      );
      if (!res.ok) {
        setError('Failed to remove account');
        return;
      }
      await fetchAccounts();
      onChange?.();
    } catch {
      setError('Failed to remove account');
    }
  };

  if (!isOpen) return null;

  return (
    <div
      className="modal show d-block"
      tabIndex={-1}
      style={{ backgroundColor: 'var(--scand-overlay)' }}
    >
      <div className="modal-dialog modal-lg">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title">
              AWS Accounts — {workgroupName}
            </h5>
            <button type="button" className="btn-close" onClick={onClose} />
          </div>
          <div className="modal-body">
            {error && <div className="alert alert-danger">{error}</div>}

            <div className="input-group mb-3">
              <input
                type="text"
                className="form-control"
                placeholder="12-digit AWS account ID"
                value={newAccountId}
                onChange={(e) => setNewAccountId(e.target.value.trim())}
                pattern="\d{12}"
                maxLength={12}
              />
              <button
                type="button"
                className="btn btn-primary"
                onClick={handleAdd}
                disabled={!/^\d{12}$/.test(newAccountId)}
              >
                Add
              </button>
            </div>

            {loading ? (
              <div>Loading…</div>
            ) : accounts.length === 0 ? (
              <div className="text-muted">No AWS accounts assigned.</div>
            ) : (
              <table className="table table-sm">
                <thead>
                  <tr>
                    <th>AWS Account ID</th>
                    <th>Granted By</th>
                    <th>Granted At</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {accounts.map((a) => (
                    <tr key={a.id}>
                      <td><code>{a.awsAccountId}</code></td>
                      <td>{a.createdByUsername ?? '—'}</td>
                      <td>{a.createdAt ?? '—'}</td>
                      <td className="text-end">
                        <button
                          type="button"
                          className="btn btn-sm btn-outline-danger"
                          onClick={() => handleRemove(a.awsAccountId)}
                        >
                          Remove
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Close
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
