/**
 * Asset Vulnerability Table Component
 *
 * Displays assets with their vulnerability counts in a sortable table.
 * Used within Account Vulns view for single account groups.
 *
 * Features:
 * - Displays asset name, type, and vulnerability count
 * - Clickable asset names for navigation to asset detail
 * - Pre-sorted by vulnerability count (descending)
 * - Bootstrap 5 styling
 *
 * Related to: Feature 018-under-vuln-management (Account Vulns)
 * User Story: US1 (P1) - View Vulnerabilities for Single AWS Account
 */

import React from 'react';
import type { AssetVulnCount } from '../services/accountVulnsService';

interface AssetVulnTableProps {
    assets: AssetVulnCount[];
    awsAccountId: string;
}

const AssetVulnTable: React.FC<AssetVulnTableProps> = ({ assets, awsAccountId }) => {
    if (assets.length === 0) {
        return (
            <div className="alert alert-info">
                <i className="bi bi-info-circle me-2"></i>
                No assets found in this AWS account.
            </div>
        );
    }

    const getVulnBadgeClass = (count: number): string => {
        if (count === 0) return 'bg-success';
        if (count < 5) return 'bg-info text-dark';
        if (count < 10) return 'bg-warning text-dark';
        return 'bg-danger';
    };

    return (
        <div className="table-responsive">
            <table className="table table-striped table-hover">
                <thead>
                    <tr>
                        <th>Asset Name</th>
                        <th>Type</th>
                        <th className="text-end">Vulnerabilities</th>
                    </tr>
                </thead>
                <tbody>
                    {assets.map((asset) => (
                        <tr key={asset.id}>
                            <td>
                                <a
                                    href={`/assets/${asset.id}`}
                                    className="text-decoration-none"
                                >
                                    {asset.name}
                                </a>
                            </td>
                            <td>
                                <span className="badge bg-secondary">{asset.type}</span>
                            </td>
                            <td className="text-end">
                                <span className={`badge ${getVulnBadgeClass(asset.vulnerabilityCount)}`}>
                                    {asset.vulnerabilityCount}
                                </span>
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
