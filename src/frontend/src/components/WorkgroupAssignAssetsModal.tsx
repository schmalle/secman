import React, { useState, useEffect, useMemo } from 'react';
import { getJson, postJson, deleteJson, ApiError } from '../utils/apiJson';
import { filterAssetsByWildcard } from './workgroupAssignLogic';
import type { AssignedAsset, Workgroup, WorkgroupAsset } from './workgroupTypes';

/**
 * Manage the assets assigned to a workgroup, extracted from WorkgroupManagement.tsx.
 * Owns all modal-scoped state (current assets, staged additions and removals,
 * wildcard search) and the save API calls; the parent supplies the full asset
 * list and learns only "saved" / "closed" / an error message.
 *
 * Removals are applied before additions on save: if the add step fails, the
 * workgroup is still in its intended steady state for what succeeded.
 */
interface WorkgroupAssignAssetsModalProps {
  workgroup: Workgroup;
  /** All assets visible to the caller (the parent already fetched them). */
  assets: WorkgroupAsset[];
  onClose: () => void;
  /** Called after a successful save; parent refetches and closes. */
  onSaved: () => void | Promise<void>;
  onError: (message: string) => void;
}

const WorkgroupAssignAssetsModal: React.FC<WorkgroupAssignAssetsModalProps> = ({
  workgroup,
  assets,
  onClose,
  onSaved,
  onError,
}) => {
  const [selectedAssetIds, setSelectedAssetIds] = useState<number[]>([]);
  const [assetSearchTerm, setAssetSearchTerm] = useState('');
  const [assignedAssets, setAssignedAssets] = useState<AssignedAsset[]>([]);
  const [assignedAssetsError, setAssignedAssetsError] = useState<string | null>(null);
  // Pending removals: ids the user has marked × in the "Currently assigned" panel.
  // Strikethrough until Save changes — keeps the operation reversible inside the modal.
  const [assetIdsToRemove, setAssetIdsToRemove] = useState<number[]>([]);

  useEffect(() => {
    // Load the currently assigned assets when the dialog opens for a workgroup.
    const fetchAssigned = async () => {
      try {
        const data = await getJson<AssignedAsset[]>(
          `/api/workgroups/${workgroup.id}/assets`,
          'Failed to load current assets'
        );
        setAssignedAssets(Array.isArray(data) ? data : []);
      } catch (err) {
        setAssignedAssetsError(err instanceof Error ? err.message : 'Failed to load current assets');
      }
    };
    void fetchAssigned();
  }, [workgroup.id]);

  const filteredAssets = useMemo(
    () => filterAssetsByWildcard(assets, assetSearchTerm),
    [assets, assetSearchTerm]
  );

  // Flip an assigned row between kept and pending-removal (applied on Save).
  const toggleAssetRemoval = (assetId: number) => {
    setAssetIdsToRemove(prev =>
      prev.includes(assetId) ? prev.filter(id => id !== assetId) : [...prev, assetId]
    );
  };

  // Stage or unstage an asset from the add picker.
  const toggleAssetSelection = (assetId: number) => {
    setSelectedAssetIds(prev =>
      prev.includes(assetId) ? prev.filter(id => id !== assetId) : [...prev, assetId]
    );
  };

  // Apply the staged changes: removals first, then additions (see component doc).
  const submitAssignAssets = async () => {
    if (selectedAssetIds.length === 0 && assetIdsToRemove.length === 0) {
      onError('Select at least one asset to add or remove');
      return;
    }

    try {
      if (assetIdsToRemove.length > 0) {
        await deleteJson(
          `/api/workgroups/${workgroup.id}/assets`,
          { assetIds: assetIdsToRemove },
          'Failed to remove assets'
        );
      }

      if (selectedAssetIds.length > 0) {
        await postJson(
          `/api/workgroups/${workgroup.id}/assets`,
          { assetIds: selectedAssetIds },
          'Failed to assign assets'
        );
      }

      await onSaved();
    } catch (err) {
      onError(err instanceof ApiError || err instanceof Error ? err.message : 'An error occurred');
    }
  };

  return (
    <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'var(--scand-overlay)' }}>
      <div className="modal-dialog modal-lg">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title">Manage Assets in {workgroup.name}</h5>
            <button type="button" className="btn-close" onClick={onClose}></button>
          </div>
          <div className="modal-body">
            {/* Currently assigned — mirrors the Assign Users dialog. The × marks a row
                for removal but does not call the API until "Save changes" is pressed. */}
            <div className="mb-3 p-2 border rounded bg-light">
              <div className="d-flex justify-content-between align-items-center mb-1">
                <strong>Currently assigned ({assignedAssets.length})</strong>
                {assetIdsToRemove.length > 0 && (
                  <span className="badge bg-warning text-dark">
                    {assetIdsToRemove.length} pending removal
                  </span>
                )}
              </div>
              {assignedAssetsError && (
                <div className="alert alert-warning py-1 px-2 my-1 small mb-0" role="alert">
                  {assignedAssetsError}
                </div>
              )}
              {assignedAssets.length === 0 && !assignedAssetsError && (
                <div className="text-muted small fst-italic">No assets assigned yet.</div>
              )}
              {assignedAssets.length > 0 && (
                <div style={{ maxHeight: '180px', overflowY: 'auto' }}>
                  {assignedAssets.map(a => {
                    const pending = assetIdsToRemove.includes(a.id);
                    return (
                      <div
                        key={a.id}
                        className="d-flex justify-content-between align-items-center py-1 px-1 border-bottom"
                      >
                        <span
                          style={{
                            textDecoration: pending ? 'line-through' : 'none',
                            color: pending ? 'var(--scand-text-secondary)' : 'inherit',
                          }}
                        >
                          <strong>{a.name}</strong>
                          {a.type && <span className="text-muted small"> ({a.type})</span>}
                          {a.ip && <span className="text-muted small"> · {a.ip}</span>}
                        </span>
                        <button
                          type="button"
                          className={`btn btn-sm ${pending ? 'btn-outline-secondary' : 'btn-outline-danger'}`}
                          onClick={() => toggleAssetRemoval(a.id)}
                          title={pending ? 'Undo removal' : 'Mark for removal'}
                        >
                          {pending ? 'Undo' : '× Remove'}
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            <p className="text-muted">Add more assets to this workgroup:</p>

            {/* Search input */}
            <div className="mb-3">
              <div className="input-group">
                <span className="input-group-text">
                  <i className="bi bi-search"></i>
                </span>
                <input
                  type="text"
                  className="form-control"
                  placeholder="Search assets... (use * for wildcard, e.g. ip-10-* or *prod*)"
                  value={assetSearchTerm}
                  onChange={(e) => setAssetSearchTerm(e.target.value)}
                  autoFocus
                />
                {assetSearchTerm && (
                  <button
                    className="btn btn-outline-secondary"
                    type="button"
                    onClick={() => setAssetSearchTerm('')}
                    title="Clear search"
                  >
                    <i className="bi bi-x-lg"></i>
                  </button>
                )}
              </div>
              <small className="text-muted">
                {filteredAssets.length} of {assets.length} assets shown
                {assetSearchTerm && ` matching "${assetSearchTerm}"`}
              </small>
            </div>

            {/* Select all filtered / Clear selection buttons */}
            {filteredAssets.length > 0 && (() => {
              const assignedIds = new Set(assignedAssets.map(a => a.id));
              const addable = filteredAssets.filter(a => !assignedIds.has(a.id));
              return (
              <div className="mb-2">
                <button
                  type="button"
                  className="btn btn-sm btn-outline-primary me-2"
                  disabled={addable.length === 0}
                  onClick={() => {
                    const addableIds = addable.map(a => a.id);
                    setSelectedAssetIds(prev => [...new Set([...prev, ...addableIds])]);
                  }}
                >
                  Select all shown ({addable.length})
                </button>
                {selectedAssetIds.length > 0 && (
                  <button
                    type="button"
                    className="btn btn-sm btn-outline-secondary"
                    onClick={() => setSelectedAssetIds([])}
                  >
                    Clear selection
                  </button>
                )}
              </div>
              );
            })()}

            <div className="list-group" style={{ maxHeight: '350px', overflowY: 'auto' }}>
              {filteredAssets.length === 0 ? (
                <div className="list-group-item text-center text-muted">
                  {assetSearchTerm
                    ? `No assets found matching "${assetSearchTerm}"`
                    : 'No assets available'}
                </div>
              ) : (
                (() => {
                  // Hide assets already in the workgroup from the "add more" picker —
                  // they can only be removed via the Currently assigned panel. Keep
                  // the lookup O(1) so a large dataset doesn't blow rendering.
                  const assignedIds = new Set(assignedAssets.map(a => a.id));
                  return filteredAssets
                    .filter(asset => !assignedIds.has(asset.id))
                    .map(asset => (
                      <label key={asset.id} className="list-group-item list-group-item-action">
                        <input
                          type="checkbox"
                          className="form-check-input me-2"
                          checked={selectedAssetIds.includes(asset.id)}
                          onChange={() => toggleAssetSelection(asset.id)}
                        />
                        <strong>{asset.name}</strong> ({asset.type})
                      </label>
                    ));
                })()
              )}
            </div>
            <p className="mt-3 text-muted">{selectedAssetIds.length} asset(s) selected</p>
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-primary"
              onClick={submitAssignAssets}
              disabled={selectedAssetIds.length === 0 && assetIdsToRemove.length === 0}
            >
              Save changes
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default WorkgroupAssignAssetsModal;
