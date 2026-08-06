import React, { useState } from 'react';
import { authenticatedFetch } from '../utils/auth';
import {
    exportComparisonToExcel,
    type ComparisonResult,
    type RequirementSnapshotSummary,
    type RequirementDiff,
} from '../utils/comparisonExport';
import ReleaseSelector from './ReleaseSelector';

type ChangeEntry =
    | { type: 'added'; req: RequirementSnapshotSummary }
    | { type: 'deleted'; req: RequirementSnapshotSummary }
    | { type: 'modified'; req: RequirementDiff };

const ReleaseComparison: React.FC = () => {
    const [fromReleaseId, setFromReleaseId] = useState<number | null>(null);
    const [toReleaseId, setToReleaseId] = useState<number | null>(null);
    const [comparisonResult, setComparisonResult] = useState<ComparisonResult | null>(null);
    const [isComparing, setIsComparing] = useState<boolean>(false);
    const [error, setError] = useState<string>('');
    const [expandedItems, setExpandedItems] = useState<Set<string>>(new Set());
    const [isExporting, setIsExporting] = useState<boolean>(false);
    const [filterType, setFilterType] = useState<'all' | 'added' | 'deleted' | 'modified'>('all');

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
        setExpandedItems(new Set());

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

    const toggleExpanded = (key: string) => {
        setExpandedItems((prev) => {
            const newSet = new Set(prev);
            if (newSet.has(key)) {
                newSet.delete(key);
            } else {
                newSet.add(key);
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

    const buildChangeEntries = (): ChangeEntry[] => {
        const entries: ChangeEntry[] = [];
        if (filterType === 'all' || filterType === 'added') {
            addedList.forEach((req) => entries.push({ type: 'added', req }));
        }
        if (filterType === 'all' || filterType === 'deleted') {
            deletedList.forEach((req) => entries.push({ type: 'deleted', req }));
        }
        if (filterType === 'all' || filterType === 'modified') {
            modifiedList.forEach((req) => entries.push({ type: 'modified', req }));
        }
        return entries;
    };

    const getStatusBadge = (type: 'added' | 'deleted' | 'modified') => {
        switch (type) {
            case 'added':
                return <span className="badge bg-success">Added</span>;
            case 'deleted':
                return <span className="badge bg-danger">Deleted</span>;
            case 'modified':
                return <span className="badge bg-warning text-dark">Modified</span>;
        }
    };

    const getRowClass = (type: 'added' | 'deleted' | 'modified') => {
        switch (type) {
            case 'added':
                return 'table-success';
            case 'deleted':
                return 'table-danger';
            case 'modified':
                return 'table-warning';
        }
    };

    const getIdRevision = (entry: ChangeEntry): string => {
        if (entry.type === 'modified') {
            return `${entry.req.internalId}.${entry.req.newRevision}`;
        }
        return entry.req.idRevision;
    };

    const getShortreq = (entry: ChangeEntry): string => {
        return entry.req.shortreq;
    };

    const getEntryKey = (entry: ChangeEntry): string => {
        if (entry.type === 'modified') {
            return `mod-${entry.req.id}`;
        }
        return `${entry.type}-${entry.req.id}`;
    };

    const addedList = comparisonResult?.added ?? [];
    const deletedList = comparisonResult?.deleted ?? [];
    const modifiedList = comparisonResult?.modified ?? [];
    const totalChanges = addedList.length + deletedList.length + modifiedList.length;

    return (
        <div className="release-comparison">
            <div className="d-flex justify-content-between align-items-center mb-3">
                <div>
                    <h2 className="mb-1">Compare Releases</h2>
                    <p className="text-muted mb-0">
                        Compare requirement snapshots between two releases to see what changed.
                    </p>
                </div>
                <a href="/releases" className="btn btn-outline-secondary">
                    <i className="bi bi-arrow-left me-2"></i>
                    Back to Releases
                </a>
            </div>

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
                            <>
                                <i className="bi bi-arrow-left-right me-2"></i>
                                Compare
                            </>
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
                    {/* Release info + Export row */}
                    <div className="d-flex justify-content-between align-items-start mb-3">
                        <div className="d-flex gap-3">
                            <div className="text-muted">
                                <strong>{comparisonResult.fromRelease.version}</strong>{' '}
                                <span className="small">({comparisonResult.fromRelease.name})</span>
                            </div>
                            <i className="bi bi-arrow-right text-muted"></i>
                            <div className="text-muted">
                                <strong>{comparisonResult.toRelease.version}</strong>{' '}
                                <span className="small">({comparisonResult.toRelease.name})</span>
                            </div>
                        </div>
                        <button
                            className="btn btn-success btn-sm"
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
                                    <i className="bi bi-file-earmark-excel me-2"></i>
                                    Export to Excel
                                </>
                            )}
                        </button>
                    </div>

                    {/* Summary stat badges as filter buttons */}
                    <div className="d-flex gap-2 mb-4 flex-wrap">
                        <button
                            className={`btn btn-sm ${filterType === 'all' ? 'btn-dark' : 'btn-outline-dark'}`}
                            onClick={() => setFilterType('all')}
                        >
                            All Changes <span className="badge bg-light text-dark ms-1">{totalChanges}</span>
                        </button>
                        <button
                            className={`btn btn-sm ${filterType === 'added' ? 'btn-success' : 'btn-outline-success'}`}
                            onClick={() => setFilterType('added')}
                        >
                            Added <span className="badge bg-light text-dark ms-1">{addedList.length}</span>
                        </button>
                        <button
                            className={`btn btn-sm ${filterType === 'deleted' ? 'btn-danger' : 'btn-outline-danger'}`}
                            onClick={() => setFilterType('deleted')}
                        >
                            Deleted <span className="badge bg-light text-dark ms-1">{deletedList.length}</span>
                        </button>
                        <button
                            className={`btn btn-sm ${filterType === 'modified' ? 'btn-warning' : 'btn-outline-warning'}`}
                            onClick={() => setFilterType('modified')}
                        >
                            Modified <span className="badge bg-light text-dark ms-1">{modifiedList.length}</span>
                        </button>
                        <span className="text-muted align-self-center ms-2 small">
                            {comparisonResult.unchanged} unchanged
                        </span>
                    </div>

                    {/* Unified changes table */}
                    {totalChanges > 0 ? (
                        <div className="table-responsive">
                            <table className="table table-hover table-sm align-middle">
                                <thead className="table-dark">
                                    <tr>
                                        <th style={{ width: '100px' }}>Status</th>
                                        <th style={{ width: '130px' }}>ID.Revision</th>
                                        <th>Short Requirement</th>
                                        <th style={{ width: '150px' }}>Changes</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {buildChangeEntries().map((entry) => {
                                        const key = getEntryKey(entry);
                                        const isExpanded = expandedItems.has(key);
                                        const isModified = entry.type === 'modified';

                                        return (
                                            <React.Fragment key={key}>
                                                <tr
                                                    className={getRowClass(entry.type)}
                                                    style={isModified ? { cursor: 'pointer' } : undefined}
                                                    onClick={isModified ? () => toggleExpanded(key) : undefined}
                                                >
                                                    <td>{getStatusBadge(entry.type)}</td>
                                                    <td>
                                                        <code>{getIdRevision(entry)}</code>
                                                        {isModified && (
                                                            <span className="text-muted small ms-1">
                                                                (was .{(entry.req as RequirementDiff).oldRevision})
                                                            </span>
                                                        )}
                                                    </td>
                                                    <td className="text-truncate" style={{ maxWidth: '400px' }}>
                                                        {getShortreq(entry)}
                                                    </td>
                                                    <td>
                                                        {isModified ? (
                                                            <span className="d-flex align-items-center gap-1">
                                                                <span className="badge bg-secondary">
                                                                    {(entry.req as RequirementDiff).changes.length} field{(entry.req as RequirementDiff).changes.length !== 1 ? 's' : ''}
                                                                </span>
                                                                <i className={`bi bi-chevron-${isExpanded ? 'up' : 'down'} small`}></i>
                                                            </span>
                                                        ) : (
                                                            <span className="text-muted small">--</span>
                                                        )}
                                                    </td>
                                                </tr>
                                                {isModified && isExpanded && (
                                                    <tr>
                                                        <td colSpan={4} className="p-0">
                                                            <div className="bg-light p-3 border-top">
                                                                <table className="table table-sm table-borderless mb-0">
                                                                    <thead>
                                                                        <tr className="text-muted small">
                                                                            <th style={{ width: '20%' }}>Field</th>
                                                                            <th style={{ width: '40%' }}>Old Value</th>
                                                                            <th style={{ width: '40%' }}>New Value</th>
                                                                        </tr>
                                                                    </thead>
                                                                    <tbody>
                                                                        {(entry.req as RequirementDiff).changes.map((change, idx) => (
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
                                                        </td>
                                                    </tr>
                                                )}
                                            </React.Fragment>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </div>
                    ) : (
                        <div className="alert alert-info">
                            <i className="bi bi-info-circle me-2"></i>
                            No changes found between these releases.
                            All {comparisonResult.unchanged} requirements are identical.
                        </div>
                    )}
                </div>
            )}
        </div>
    );
};

export default ReleaseComparison;
