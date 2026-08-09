import { useEffect, useState } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedDelete } from '../utils/auth';
import { extractErrorMessage } from '../services/workgroupApi';
import { getDistinctAdDomains } from '../services/vulnerabilityManagementService';

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
  const [existingDomains, setExistingDomains] = useState<string[]>([]);
  const [newDomain, setNewDomain] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [existingDomainsError, setExistingDomainsError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [existingDomainsLoading, setExistingDomainsLoading] = useState(false);

  const fetchDomains = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await authenticatedGet(`/api/workgroups/${workgroupId}/ad-domains`);
      if (!res.ok) {
        setError(await extractErrorMessage(res, 'Failed to load domains'));
        return;
      }
      setDomains((await res.json()) as WorkgroupAdDomainDto[]);
    } catch {
      setError('Failed to load domains');
    } finally {
      setLoading(false);
    }
  };

  const fetchExistingDomains = async () => {
    setExistingDomainsLoading(true);
    setExistingDomainsError(null);
    try {
      const availableDomains = await getDistinctAdDomains();
      setExistingDomains(
        Array.from(new Set(availableDomains.map((domain) => domain.trim()).filter(Boolean))).sort()
      );
    } catch {
      setExistingDomainsError('Failed to load existing AD domains');
      setExistingDomains([]);
    } finally {
      setExistingDomainsLoading(false);
    }
  };

  useEffect(() => {
    if (!isOpen) return;

    queueMicrotask(() => {
      fetchDomains();
      fetchExistingDomains();
      setNewDomain('');
      setError(null);
      setExistingDomainsError(null);
    });
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
      const res = await authenticatedPost(`/api/workgroups/${workgroupId}/ad-domains`, {
        adDomain: normalizedDomain,
      });
      if (!res.ok) {
        if (res.status === 409) setError('That AD domain is already assigned to this workgroup');
        else if (res.status === 400) setError(await extractErrorMessage(res, 'Invalid AD domain'));
        else setError('Failed to add AD domain');
        return;
      }
      setNewDomain('');
      await fetchDomains();
      onChange?.();
    } catch {
      setError('Failed to add AD domain');
    }
  };

  const handleRemove = async (adDomain: string) => {
    setError(null);
    try {
      const res = await authenticatedDelete(
        `/api/workgroups/${workgroupId}/ad-domains/${encodeURIComponent(adDomain)}`
      );
      if (!res.ok) {
        setError('Failed to remove AD domain');
        return;
      }
      await fetchDomains();
      onChange?.();
    } catch {
      setError('Failed to remove AD domain');
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

            <div className="mb-3">
              <label htmlFor="existing-ad-domains" className="form-label">
                Existing AD domains
              </label>
              <select
                id="existing-ad-domains"
                className="form-select"
                size={Math.min(Math.max(existingDomains.length, 2), 6)}
                aria-label="Existing AD domains"
                value=""
                onChange={(e) => setNewDomain(e.target.value)}
                disabled={existingDomainsLoading || existingDomains.length === 0}
              >
                {existingDomainsLoading ? (
                  <option value="">Loading domains...</option>
                ) : existingDomains.length === 0 ? (
                  <option value="">No existing AD domains found.</option>
                ) : (
                  existingDomains.map((domain) => (
                    <option key={domain} value={domain}>
                      {domain}
                    </option>
                  ))
                )}
              </select>
              {existingDomainsError && (
                <div className="form-text text-danger">{existingDomainsError}</div>
              )}
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
