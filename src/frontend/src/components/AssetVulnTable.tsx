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
 * Related to: Feature 018-under-vuln-management (Account Vulns)
 * Feature 019: Account Vulns Severity Breakdown
 * User Story: US1 (P1) - View Vulnerabilities for Single AWS Account
 */

import React from 'react';
import type { AssetVulnCount } from '../services/accountVulnsService';
import SeverityBadge from './SeverityBadge';

interface AssetVulnTableProps {
    assets: AssetVulnCount[];
    awsAccountId: string;
}

const AssetVulnTable: React.FC<AssetVulnTableProps> = ({ assets, awsAccountId }) => {
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

    const getVulnBadgeClass = (count: number): string => {
        if (count === 0) return 'bg-success bg-opacity-10 text-success border border-success';
        if (count < 10) return 'bg-secondary bg-opacity-10 text-secondary border border-secondary';
        if (count < 50) return 'bg-warning bg-opacity-10 text-warning border border-warning';
        return 'bg-danger bg-opacity-10 text-danger border border-danger';
    };

    return (
        <div className="table-responsive">
            <table className="table table-striped table-hover">
                <thead>
                    <tr>
                        <th>Asset Name</th>
                        <th>Type</th>
                        <th className="text-end">Total Vulnerabilities</th>
                        <th>Severity Breakdown</th>
                    </tr>
                </thead>
                <tbody>
                    {assets.map((asset) => (
                        <tr key={asset.id}>
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
                        </tr>
                    ))}
                </tbody>
            </table>
            <div className="text-muted mt-2">
                <small>
                    <i className="bi bi-info-circle me-1"></i>
                    Showing {assets.length} asset{assets.length !== 1 ? 's' : ''} in AWS account {awsAccountId}
                </small>
            </div>
        </div>
    );
};

export default AssetVulnTable;
