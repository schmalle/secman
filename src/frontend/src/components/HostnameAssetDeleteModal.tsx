import React, { useState } from 'react';
import { deleteSelectedAssets, previewHostnameDeletion, type HostnameDeletionPreview, type SelectedAssetDeletionResult } from '../services/assetService';

interface Props {
  onClose: () => void;
}

export function HostnameAssetDeleteModal({ onClose }: Props) {
  const [hostnames, setHostnames] = useState('');
  const [preview, setPreview] = useState<HostnameDeletionPreview | null>(null);
  const [results, setResults] = useState<SelectedAssetDeletionResult[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const review = async () => {
    setBusy(true);
    setError(null);
    setPreview(null);
    setResults(null);
    try {
      setPreview(await previewHostnameDeletion(hostnames));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not preview hostname deletion.');
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (busy || !preview?.assets.length) return;
    const ids = preview.assets.map(asset => asset.id);
    setBusy(true);
    setError(null);
    // A lost response must require a new preview, never an automatic repeated delete.
    setPreview(null);
    try {
      setResults(await deleteSelectedAssets(ids));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Deletion failed. Preview again before retrying.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal show d-block" role="dialog" aria-modal="true" aria-labelledby="hostnameDeleteTitle"
      tabIndex={-1} style={{ backgroundColor: 'var(--scand-overlay)' }}
      onKeyDown={event => { if (event.key === 'Escape' && !busy) onClose(); }}>
      <div className="modal-dialog modal-lg modal-dialog-centered modal-dialog-scrollable">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title" id="hostnameDeleteTitle">Delete assets by hostname</h5>
            <button type="button" className="btn-close" aria-label="Close" onClick={onClose} disabled={busy} />
          </div>
          <div className="modal-body">
            <label htmlFor="deleteHostnames" className="form-label">Comma-separated hostnames</label>
            <textarea id="deleteHostnames" className="form-control" rows={3} maxLength={64000} autoFocus
              placeholder="server-one, server-two" value={hostnames} disabled={busy}
              onChange={event => { setHostnames(event.target.value); setPreview(null); setResults(null); setError(null); }}
              aria-describedby="hostnameDeleteHelp" />
            <p className="form-text" id="hostnameDeleteHelp">
              Enter up to 250 hostnames. Matches are exact and case-insensitive, using the CrowdStrike hostname
              when present, otherwise the asset name. No wildcard or short-name expansion. Older stored assets
              are included, regardless of the current table filters.
            </p>
            <button type="button" className="btn btn-outline-primary mb-3" disabled={busy || !hostnames.trim()} onClick={review}>
              {busy ? 'Working…' : 'Preview matching assets'}
            </button>
            {error && <div className="alert alert-danger" role="alert">{error}</div>}
            {preview && <>
              {preview.unmatchedHostnames.length > 0 && <div className="alert alert-warning">
                No matching assets: {preview.unmatchedHostnames.join(', ')}
              </div>}
              <p><strong>{preview.assets.length}</strong> matching assets. All listed matches, including duplicate hostnames, will be deleted.</p>
              {preview.assets.length > 0 && <>
                <div className="table-responsive">
                  <table className="table table-sm">
                    <thead><tr><th>Asset ID</th><th>Name</th><th>Hostname</th><th>AWS account</th><th>Instance</th></tr></thead>
                    <tbody>{preview.assets.map(asset => <tr key={asset.id}>
                      <td>{asset.id}</td><td>{asset.name}</td><td>{asset.hostname}</td>
                      <td>{asset.cloudAccountId || '—'}</td><td>{asset.cloudInstanceId || '—'}</td>
                    </tr>)}</tbody>
                  </table>
                </div>
                <div className="alert alert-danger">Deletion is permanent and includes related data such as vulnerabilities and asset exceptions.</div>
                <p>Assets referenced by demands, risks or risk assessments are retained. Each deletion is reported separately.</p>
              </>}
            </>}
            {results && <div role="status">
              <p>{results.filter(result => result.deleted).length} deleted; {results.filter(result => !result.deleted).length} retained.</p>
              <ul>{results.map(result => <li key={result.assetId}>
                SecMan #{result.assetId}: {result.deleted ? 'Deleted' : 'Retained'} — {result.message}
              </li>)}</ul>
            </div>}
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose} disabled={busy}>Close</button>
            <button type="button" className="btn btn-danger" onClick={remove} disabled={busy || !preview?.assets.length}>
              Permanently delete {preview?.assets.length || 0} assets
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
