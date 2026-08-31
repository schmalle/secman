/**
 * Asset Vulnerability Table Component
 *
 * Displays assets with their vulnerability counts in a sortable table.
 * Used within Account Vulns view for single account groups.
 *
 * Features:
 * - Displays asset name, type, and vulnerability count
 * - Feature 019: Displays severity breakdown (critical, high, medium)
 * - Clickable asset names for navigation to asset detail
 * - Pre-sorted by vulnerability count (descending)
 * - Bootstrap 5 styling
 *
 * Feature 019: Account Vulns Severity Breakdown
 */

import React, { useState } from 'react';
import type { AssetVulnCount } from '../services/accountVulnsService';
import AssetStatusLamp from './AssetStatusLamp';
import ExceptionBreakdownBadges from './ExceptionBreakdownBadges';
import SeverityBadge from './SeverityBadge';

interface AssetVulnTableProps {
    assets: AssetVulnCount[];
    // Optional: only set when grouping by AWS account; absent for workgroup grouping etc.
    awsAccountId?: string;
    // Threshold in days, echoed by the API so the tooltip text is never hardcoded.
    thresholdDays?: number;
}

type StatusFilter = 'ALL' | 'ATTENTION' | 'RED' | 'YELLOW' | 'GREEN';

const AssetVulnTable: React.FC<AssetVulnTableProps> = ({ assets, awsAccountId, thresholdDays }) => {
    // Default ALL: the lamp alone is enough for small accounts, and hiding rows by default would
    // be surprising. The filter is what removes the "click through every asset" work.
    const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
    console.log('[AssetVulnTable] Rendering with:', {
        assetsType: typeof assets,
        assetsIsArray: Array.isArray(assets),
        assetsLength: assets?.length,
        awsAccountId,
        assets
    });

    // Safety check - ensure assets is an array
    if (!assets || !Array.isArray(assets)) {
        console.error('[AssetVulnTable] Invalid assets prop:', assets);
        return (
            <div className="alert alert-warning">
                <i className="bi bi-exclamation-triangle me-2"></i>
                Error: Invalid asset data received
            </div>
        );
    }

    if (assets.length === 0) {
        return (
            <div className="alert alert-info">
                <i className="bi bi-info-circle me-2"></i>
                No assets found in this AWS account.
            </div>
        );
    }

    // bg-success/bg-danger/etc. are forced solid via !important in bootstrap-overrides.css,
    // which defeats bg-opacity-10 and makes text-* the same hue as bg — invisible numbers.
    const getVulnBadgeClass = (count: number): string => {
        if (count === 0) return 'scand-success';
        if (count < 10) return 'scand-medium';
        if (count < 50) return 'scand-high';
        return 'scand-critical';
    };

    // Assets arrive worst-first from the backend, so filtering preserves that ordering.
    const visibleAssets = assets.filter((asset) => {
        if (statusFilter === 'ALL') return true;
        // Rows from an older backend carry no status; treat them as unclassified and keep them
        // visible under ALL only, rather than silently claiming they are fine.
        if (!asset.status) return false;
        if (statusFilter === 'ATTENTION') return asset.status !== 'GREEN';
        return asset.status === statusFilter;
    });

    const attentionCount = assets.filter((a) => a.status && a.status !== 'GREEN').length;
    const hasStatus = assets.some((a) => a.status);

    const filterButtons: Array<{ key: StatusFilter; label: string }> = [
        { key: 'ALL', label: `All (${assets.length})` },
        { key: 'ATTENTION', label: `Needs attention (${attentionCount})` },
        { key: 'RED', label: 'Overdue' },
        { key: 'YELLOW', label: 'Within deadline' },
        { key: 'GREEN', label: 'No action' },
    ];

    return (
        <div className="table-responsive">
            {hasStatus && (
                <div className="btn-group btn-group-sm mb-2" role="group" aria-label="Filter assets by status">
                    {filterButtons.map(({ key, label }) => (
                        <button
                            key={key}
                            type="button"
                            className={`btn ${statusFilter === key ? 'btn-primary' : 'btn-outline-secondary'}`}
                            aria-pressed={statusFilter === key}
                            onClick={() => setStatusFilter(key)}
                        >
                            {label}
                        </button>
                    ))}
                </div>
            )}
            <table className="table table-striped table-hover">
                <thead>
                    <tr>
                        <th scope="col" style={{ width: '1%' }}>
                            <span className="visually-hidden">Status</span>
                        </th>
                        <th>Asset Name</th>
                        <th>Type</th>
                        <th className="text-end">Total Vulnerabilities</th>
                        <th>Severity Breakdown</th>
                        <th>Exception Breakdown</th>
                    </tr>
                </thead>
                <tbody>
                    {visibleAssets.map((asset) => (
                        <tr key={asset.id}>
                            <td className="align-middle">
                                <AssetStatusLamp
                                    status={asset.status}
                                    overdueCount={asset.nonExceptedOverdueCount}
                                    nonExceptedCount={asset.nonExceptedCount}
                                    thresholdDays={thresholdDays}
                                />
                            </td>
                            <td>
                                <a
                                    href={`/vulnerabilities/system?hostname=${encodeURIComponent(asset.name)}`}
                                    className="text-decoration-none"
                                >
                                    {asset.name}
                                </a>
                            </td>
                            <td>
                                <span className="badge bg-secondary bg-opacity-10 text-secondary border border-secondary">{asset.type}</span>
                            </td>
                            <td className="text-end">
                                <span className={`badge ${getVulnBadgeClass(asset.vulnerabilityCount)}`}>
                                    {asset.vulnerabilityCount}
                                </span>
                            </td>
                            <td>
                                {/* Feature 019: Severity breakdown badges */}
                                <div className="d-flex flex-wrap gap-1">
                                    <SeverityBadge 
                                        severity="CRITICAL" 
                                        count={asset.criticalCount ?? 0} 
                                    />
                                    <SeverityBadge 
                                        severity="HIGH" 
                                        count={asset.highCount ?? 0} 
                                    />
                                    <SeverityBadge 
                                        severity="MEDIUM" 
                                        count={asset.mediumCount ?? 0} 
                                    />
                                </div>
                            </td>
                            <td>
                                <ExceptionBreakdownBadges
                                    exceptedCount={asset.exceptedCount ?? 0}
                                    nonExceptedCount={asset.nonExceptedCount ?? Math.max(asset.vulnerabilityCount - (asset.exceptedCount ?? 0), 0)}
                                />
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
            {visibleAssets.length === 0 && (
                <div className="alert alert-info">
                    <i className="bi bi-info-circle me-2"></i>
                    No assets match this filter.
                </div>
            )}
            <div className="text-muted mt-2">
                <small>
                    <i className="bi bi-info-circle me-1"></i>
                    Showing {visibleAssets.length} of {assets.length} asset{assets.length !== 1 ? 's' : ''}{awsAccountId ? ` in AWS account ${awsAccountId}` : ''}
                </small>
            </div>
        </div>
    );
};

export default AssetVulnTable;
