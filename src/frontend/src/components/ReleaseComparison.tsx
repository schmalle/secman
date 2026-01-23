import React, { useState } from 'react';
import { authenticatedFetch } from '../utils/auth';
import { 
    exportComparisonToExcel, 
    type ComparisonResult,
    type ReleaseInfo,
    type RequirementSnapshotSummary,
    type RequirementDiff,
    type FieldChange
} from '../utils/comparisonExport';
import ReleaseSelector from './ReleaseSelector';

const ReleaseComparison: React.FC = () => {
    const [fromReleaseId, setFromReleaseId] = useState<number | null>(null);
    const [toReleaseId, setToReleaseId] = useState<number | null>(null);
    const [comparisonResult, setComparisonResult] = useState<ComparisonResult | null>(null);
    const [isComparing, setIsComparing] = useState<boolean>(false);
    const [error, setError] = useState<string>('');
    const [expandedItems, setExpandedItems] = useState<Set<number>>(new Set());
    const [isExporting, setIsExporting] = useState<boolean>(false);

    const handleCompare = async () => {
        if (fromReleaseId === null || toReleaseId === null) {
            setError('Please select both releases to compare');
            return;
        }

        if (fromReleaseId === toReleaseId) {
            setError('Please select two different releases to compare');
            return;
        }

        setIsComparing(true);
        setError('');
        setComparisonResult(null);

        try {
            const response = await authenticatedFetch(
                `/api/releases/compare?fromReleaseId=${fromReleaseId}&toReleaseId=${toReleaseId}`
            );

            if (!response.ok) {
                const errorData = await response.json();
                throw new Error(errorData.message || 'Failed to compare releases');
            }

            const data: ComparisonResult = await response.json();
            setComparisonResult(data);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to compare releases');
        } finally {
            setIsComparing(false);
        }
    };

    const toggleExpanded = (requirementId: number) => {
        setExpandedItems((prev) => {
            const newSet = new Set(prev);
            if (newSet.has(requirementId)) {
                newSet.delete(requirementId);
            } else {
                newSet.add(requirementId);
            }
            return newSet;
        });
    };

    const handleExportToExcel = async () => {
        if (!comparisonResult) return;

        setIsExporting(true);
        try {
            await exportComparisonToExcel(comparisonResult);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to export comparison');
        } finally {
            setIsExporting(false);
        }
    };

    return (
        <div className="release-comparison">
            <h2>Compare Releases</h2>
            <p className="text-muted">
                Compare requirement snapshots between two releases to see what changed.
            </p>

            {error && (
                <div className="alert alert-danger alert-dismissible fade show" role="alert">
                    {error}
                    <button
                        type="button"
                        className="btn-close"
                        onClick={() => setError('')}
                        aria-label="Close"
                    ></button>
                </div>
            )}

            <div className="row mb-4">
                <div className="col-md-5">
                    <ReleaseSelector
                        onReleaseChange={setFromReleaseId}
                        selectedReleaseId={fromReleaseId}
                    />
                    <small className="form-text text-muted">Baseline (From)</small>
                </div>

                <div className="col-md-2 d-flex align-items-center justify-content-center">
                    <button
                        className="btn btn-primary"
                        onClick={handleCompare}
                        disabled={isComparing || fromReleaseId === null || toReleaseId === null}
                    >
                        {isComparing ? (
                            <>
                                <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                Comparing...
                            </>
                        ) : (
                            'Compare →'
                        )}
                    </button>
                </div>

                <div className="col-md-5">
                    <ReleaseSelector
                        onReleaseChange={setToReleaseId}
                        selectedReleaseId={toReleaseId}
                    />
                    <small className="form-text text-muted">Comparison (To)</small>
                </div>
            </div>

            {comparisonResult && (
                <div className="comparison-results mt-4">
                    {/* Export Button */}
                    <div className="mb-3 d-flex justify-content-end">
                        <button
                            className="btn btn-success"
                            onClick={handleExportToExcel}
                            disabled={isExporting}
                        >
                            {isExporting ? (
                                <>
                                    <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                    Exporting...
                                </>
                            ) : (
                                <>
                                    <i className="bi bi-download me-2"></i>
                                    Export to Excel
                                </>
                            )}
                        </button>
                    </div>

                    <div className="row mb-3">
                        <div className="col-md-6">
                            <div className="card">
                                <div className="card-body">
                                    <h6 className="card-subtitle mb-2 text-muted">From Release</h6>
                                    <h5 className="card-title">{comparisonResult.fromRelease.version}</h5>
                                    <p className="card-text">{comparisonResult.fromRelease.name}</p>
                                </div>
                            </div>
                        </div>
                        <div className="col-md-6">
                            <div className="card">
                                <div className="card-body">
                                    <h6 className="card-subtitle mb-2 text-muted">To Release</h6>
                                    <h5 className="card-title">{comparisonResult.toRelease.version}</h5>
                                    <p className="card-text">{comparisonResult.toRelease.name}</p>
                                </div>
                            </div>
                        </div>
                    </div>

                    <div className="summary-stats mb-4">
                        <div className="row text-center">
                            <div className="col-md-3">
                                <div className="card bg-success text-white">
                                    <div className="card-body">
                                        <h3>{comparisonResult.added.length}</h3>
                                        <p className="mb-0">Added</p>
                                    </div>
                                </div>
                            </div>
                            <div className="col-md-3">
                                <div className="card bg-danger text-white">
                                    <div className="card-body">
                                        <h3>{comparisonResult.deleted.length}</h3>
                                        <p className="mb-0">Deleted</p>
                                    </div>
                                </div>
                            </div>
                            <div className="col-md-3">
                                <div className="card bg-warning text-dark">
                                    <div className="card-body">
                                        <h3>{comparisonResult.modified.length}</h3>
                                        <p className="mb-0">Modified</p>
                                    </div>
                                </div>
                            </div>
                            <div className="col-md-3">
                                <div className="card bg-secondary text-white">
                                    <div className="card-body">
                                        <h3>{comparisonResult.unchanged}</h3>
                                        <p className="mb-0">Unchanged</p>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* Added Requirements */}
                    {comparisonResult.added.length > 0 && (
                        <div className="added-section mb-4">
                            <h4 className="text-success">
                                <i className="bi bi-plus-circle"></i> Added Requirements ({comparisonResult.added.length})
                            </h4>
                            <div className="list-group">
                                {comparisonResult.added.map((req) => (
                                    <div key={req.id} className="list-group-item list-group-item-success">
                                        <div className="d-flex align-items-start">
                                            <span className="badge bg-secondary me-2">{req.idRevision}</span>
                                            <div>
                                                <h6 className="mb-1">{req.shortreq}</h6>
                                                {req.details && <p className="mb-0 text-muted small">{req.details}</p>}
                                            </div>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}

                    {/* Deleted Requirements */}
                    {comparisonResult.deleted.length > 0 && (
                        <div className="deleted-section mb-4">
                            <h4 className="text-danger">
                                <i className="bi bi-dash-circle"></i> Deleted Requirements ({comparisonResult.deleted.length})
                            </h4>
                            <div className="list-group">
                                {comparisonResult.deleted.map((req) => (
                                    <div key={req.id} className="list-group-item list-group-item-danger">
                                        <div className="d-flex align-items-start">
                                            <span className="badge bg-secondary me-2">{req.idRevision}</span>
                                            <div>
                                                <h6 className="mb-1">{req.shortreq}</h6>
                                                {req.details && <p className="mb-0 text-muted small">{req.details}</p>}
                                            </div>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}

                    {/* Modified Requirements */}
                    {comparisonResult.modified.length > 0 && (
                        <div className="modified-section mb-4">
                            <h4 className="text-warning">
                                <i className="bi bi-pencil-square"></i> Modified Requirements ({comparisonResult.modified.length})
                            </h4>
                            <div className="list-group">
                                {comparisonResult.modified.map((req) => {
                                    const isExpanded = expandedItems.has(req.id);

                                    return (
                                        <div key={req.id} className="list-group-item list-group-item-warning">
                                            <div
                                                className="d-flex justify-content-between align-items-center"
                                                style={{ cursor: 'pointer' }}
                                                onClick={() => toggleExpanded(req.id)}
                                            >
                                                <div className="d-flex align-items-center">
                                                    <span className="badge bg-secondary me-2">{req.internalId}</span>
                                                    <span className="badge bg-info me-2" title="Revision change">
                                                        .{req.oldRevision} → .{req.newRevision}
                                                    </span>
                                                    <h6 className="mb-0">{req.shortreq}</h6>
                                                </div>
                                                <span className="badge bg-secondary">
                                                    {req.changes.length} field{req.changes.length !== 1 ? 's' : ''} changed
                                                </span>
                                            </div>

                                            {isExpanded && (
                                                <div className="mt-3">
                                                    <table className="table table-sm table-borderless">
                                                        <thead>
                                                            <tr>
                                                                <th style={{ width: '20%' }}>Field</th>
                                                                <th style={{ width: '40%' }}>Old Value</th>
                                                                <th style={{ width: '40%' }}>New Value</th>
                                                            </tr>
                                                        </thead>
                                                        <tbody>
                                                            {req.changes.map((change, idx) => (
                                                                <tr key={idx}>
                                                                    <td>
                                                                        <strong>{change.fieldName}</strong>
                                                                    </td>
                                                                    <td className="text-danger">
                                                                        <del>{change.oldValue || '(empty)'}</del>
                                                                    </td>
                                                                    <td className="text-success">
                                                                        <ins>{change.newValue || '(empty)'}</ins>
                                                                    </td>
                                                                </tr>
                                                            ))}
                                                        </tbody>
                                                    </table>
                                                </div>
                                            )}
                                        </div>
                                    );
                                })}
                            </div>
                        </div>
                    )}

                    {comparisonResult.added.length === 0 &&
                        comparisonResult.deleted.length === 0 &&
                        comparisonResult.modified.length === 0 && (
                            <div className="alert alert-info">
                                <i className="bi bi-info-circle"></i> No changes found between these releases.
                                All {comparisonResult.unchanged} requirements are identical.
                            </div>
                        )}
                </div>
            )}
        </div>
    );
};

export default ReleaseComparison;
