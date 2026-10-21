import React from 'react';

interface SelectedAssetDeleteModalProps {
  assets: Array<{ id: number; name: string }>;
  isDeleting: boolean;
  onClose: () => void;
  onConfirm: () => void;
}

export function SelectedAssetDeleteModal({ assets, isDeleting, onClose, onConfirm }: SelectedAssetDeleteModalProps) {
  return (
    <div className="modal show d-block" role="dialog" aria-modal="true" aria-labelledby="selectedAssetDeleteTitle"
      tabIndex={-1} style={{ backgroundColor: 'var(--scand-overlay)' }}
      onKeyDown={event => { if (event.key === 'Escape' && !isDeleting) onClose(); }}>
      <div className="modal-dialog modal-dialog-centered modal-dialog-scrollable">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title" id="selectedAssetDeleteTitle">Delete selected assets</h5>
            <button type="button" className="btn-close" aria-label="Close" onClick={onClose} disabled={isDeleting} />
          </div>
          <div className="modal-body">
            <p>Permanently delete these <strong>{assets.length}</strong> selected assets and their related data, including vulnerabilities and asset exceptions? This cannot be undone.</p>
            <ul>{assets.map(asset => <li key={asset.id}>{asset.name} (SecMan #{asset.id})</li>)}</ul>
            <p className="text-muted">Assets referenced by demands, risks or risk assessments are retained. Each deletion is reported separately.</p>
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose} disabled={isDeleting} autoFocus>Cancel</button>
            <button type="button" className="btn btn-danger" onClick={onConfirm} disabled={isDeleting || assets.length === 0}>
              {isDeleting ? 'Deleting…' : `Permanently delete ${assets.length} assets`}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
