import { useEffect, useState } from 'react';
import axios from 'axios';

interface WorkgroupAdDomainDto {
  id: number;
  workgroupId: number;
  adDomain: string;
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

const DOMAIN_PATTERN = /^[a-zA-Z0-9.-]+$/;

export default function WorkgroupDomainsModal({
  workgroupId,
  workgroupName,
  isOpen,
  onClose,
  onChange,
}: Props) {
  const [domains, setDomains] = useState<WorkgroupAdDomainDto[]>([]);
  const [newDomain, setNewDomain] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const fetchDomains = async () => {
    setLoading(true);
    setError(null);
    try {
      const token = localStorage.getItem('authToken');
      const res = await axios.get<WorkgroupAdDomainDto[]>(
        `/api/workgroups/${workgroupId}/ad-domains`,
        { headers: { Authorization: `Bearer ${token}` } }
      );
      setDomains(res.data);
    } catch (e: any) {
      setError(e.response?.data?.error ?? 'Failed to load domains');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      fetchDomains();
      setNewDomain('');
      setError(null);
    }
  }, [isOpen, workgroupId]);

  const normalizedDomain = newDomain.trim().toLowerCase();
  const canSubmit = normalizedDomain.length > 0 && DOMAIN_PATTERN.test(normalizedDomain);

  const handleAdd = async () => {
    setError(null);
    if (!canSubmit) {
      setError('AD domain must contain only letters, numbers, dots, and hyphens');
      return;
    }
    try {
      const token = localStorage.getItem('authToken');
      await axios.post(
        `/api/workgroups/${workgroupId}/ad-domains`,
        { adDomain: normalizedDomain },
        { headers: { Authorization: `Bearer ${token}` } }
      );
      setNewDomain('');
      await fetchDomains();
      onChange?.();
    } catch (e: any) {
      const status = e.response?.status;
      if (status === 409) setError('That AD domain is already assigned to this workgroup');
      else if (status === 400) setError(e.response?.data?.error ?? 'Invalid AD domain');
      else setError('Failed to add AD domain');
    }
  };

  const handleRemove = async (adDomain: string) => {
    setError(null);
    try {
      const token = localStorage.getItem('authToken');
      await axios.delete(
        `/api/workgroups/${workgroupId}/ad-domains/${encodeURIComponent(adDomain)}`,
        { headers: { Authorization: `Bearer ${token}` } }
      );
      await fetchDomains();
      onChange?.();
    } catch (e: any) {
      setError('Failed to remove AD domain');
    }
  };

  if (!isOpen) return null;

  return (
    <div
      className="modal show d-block"
      tabIndex={-1}
      style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}
    >
      <div className="modal-dialog modal-lg">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title">AD Domains — {workgroupName}</h5>
            <button type="button" className="btn-close" onClick={onClose} />
          </div>
          <div className="modal-body">
            {error && <div className="alert alert-danger">{error}</div>}

            <div className="input-group mb-3">
              <input
                type="text"
                className="form-control"
                placeholder="corp.example.com"
                value={newDomain}
                onChange={(e) => setNewDomain(e.target.value.trim())}
                pattern="[a-zA-Z0-9.-]+"
                maxLength={255}
              />
              <button
                type="button"
                className="btn btn-primary"
                onClick={handleAdd}
                disabled={!canSubmit}
              >
                Add
              </button>
            </div>

            {loading ? (
              <div>Loading…</div>
            ) : domains.length === 0 ? (
              <div className="text-muted">No AD domains assigned.</div>
            ) : (
              <table className="table table-sm">
                <thead>
                  <tr>
                    <th>AD Domain</th>
                    <th>Granted By</th>
                    <th>Granted At</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {domains.map((d) => (
                    <tr key={d.id}>
                      <td><code>{d.adDomain}</code></td>
                      <td>{d.createdByUsername ?? '—'}</td>
                      <td>{d.createdAt ?? '—'}</td>
                      <td className="text-end">
                        <button
                          type="button"
                          className="btn btn-sm btn-outline-danger"
                          onClick={() => handleRemove(d.adDomain)}
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
