/**
 * Domain Vulns View Component
 *
 * Feature: 042-domain-vulnerabilities-view
 *
 * Main component for Domain Vulns feature - displays vulnerabilities grouped by AD domain.
 * Queries CrowdStrike Falcon API directly based on user's domain mappings.
 *
 * Features:
 * - Fetches domain vulnerability summary from Falcon API via backend
 * - Displays devices grouped by Active Directory domain
 * - Displays severity breakdown (critical, high, medium, low) at all levels
 * - Handles loading, error, and empty states
 * - Admin redirect handling
 * - No domain mapping error handling
 * - Real-time data from CrowdStrike (not local database)
 *
 * Similar to AccountVulnsView but queries Falcon API directly instead of local database.
 */

import React, { useState, useEffect } from 'react';
import { getDomainVulns, type DomainVulnsSummary } from '../services/domainVulnsService';
import SeverityBadge from './SeverityBadge';

const DomainVulnsView: React.FC = () => {
    console.log('[DomainVulnsView] Component mounting...');

    const [summary, setSummary] = useState<DomainVulnsSummary | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [isAdminRedirect, setIsAdminRedirect] = useState(false);

    const formatTimestamp = (timestamp: string): string => {
        const date = new Date(timestamp);
        if (Number.isNaN(date.getTime())) {
            return timestamp;
        }

        return date.toLocaleString(undefined, {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        });
    };

    useEffect(() => {
        console.log('[DomainVulnsView] useEffect triggered, calling fetchDomainVulns...');
        fetchDomainVulns();
    }, []);

    const fetchDomainVulns = async () => {
        try {
            console.log('[DomainVulnsView] fetchDomainVulns started - setting loading=true');
            setLoading(true);
            setError(null);
            setIsAdminRedirect(false);

            console.log('[DomainVulnsView] Calling getDomainVulns() API...');
            const data = await getDomainVulns();
            console.log('[DomainVulnsView] API call successful, received data:', data);
            console.log('[DomainVulnsView] - Domain groups:', data.domainGroups?.length || 0);
            console.log('[DomainVulnsView] - Total devices:', data.totalDevices);
            console.log('[DomainVulnsView] - Total vulnerabilities:', data.totalVulnerabilities);

            setSummary(data);
            console.log('[DomainVulnsView] State updated with summary data');
        } catch (err) {
            console.error('[DomainVulnsView] Error in fetchDomainVulns:', err);
            const errorMessage = err instanceof Error ? err.message : 'Failed to load domain vulnerabilities';
            console.log('[DomainVulnsView] Error message:', errorMessage);

            // Check if it's an admin redirect error (403)
            if (errorMessage.includes('System Vulns') || errorMessage.includes('Admin') || errorMessage.includes('403')) {
                console.log('[DomainVulnsView] Detected admin redirect error');
                setIsAdminRedirect(true);
            }

            setError(errorMessage);
        } finally {
            console.log('[DomainVulnsView] fetchDomainVulns completed - setting loading=false');
            setLoading(false);
        }
    };

    // Loading state
    console.log('[DomainVulnsView] Render - loading:', loading, 'error:', error, 'isAdminRedirect:', isAdminRedirect, 'summary:', summary);

    if (loading) {
        console.log('[DomainVulnsView] Rendering loading state');
        return (
            <div className="container-fluid p-4">
                <div className="d-flex justify-content-center align-items-center" style={{ minHeight: '400px' }}>
                    <div className="text-center">
                        <div className="spinner-border text-primary mb-3" role="status">
                            <span className="visually-hidden">Loading...</span>
                        </div>
                        <p className="text-muted">Querying CrowdStrike Falcon API...</p>
                        <small className="text-muted">This may take a few moments...</small>
                    </div>
                </div>
            </div>
        );
    }

    // Admin redirect error state
    if (isAdminRedirect) {
        return (
            <div className="container-fluid p-4">
                <div className="alert alert-warning" role="alert">
                    <h4 className="alert-heading">
                        <i className="bi bi-shield-lock me-2"></i>
                        Admin Access Notice
                    </h4>
                    <p>
                        Admin users should use the System Vulns view to see all vulnerabilities across all accounts and domains.
                    </p>
                    <hr />
                    <div className="mb-0">
                        <a href="/system-vulns" className="btn btn-primary me-2">
                            <i className="bi bi-arrow-right me-2"></i>
                            Go to System Vulns
                        </a>
                        <a href="/" className="btn btn-outline-secondary">
                            <i className="bi bi-house me-2"></i>
                            Back to Home
                        </a>
                    </div>
                </div>
            </div>
        );
    }

    // General error state
    if (error) {
        return (
            <div className="container-fluid p-4">
                <div className="alert alert-danger" role="alert">
                    <h4 className="alert-heading">
                        <i className="bi bi-exclamation-triangle me-2"></i>
                        Error Loading Domain Vulnerabilities
                    </h4>
                    <p>{error}</p>
                    <hr />
                    <div className="mb-0">
                        <button className="btn btn-primary me-2" onClick={fetchDomainVulns}>
                            <i className="bi bi-arrow-clockwise me-2"></i>
                            Try Again
                        </button>
                        <a href="/" className="btn btn-outline-secondary">
                            <i className="bi bi-house me-2"></i>
                            Back to Home
                        </a>
                    </div>
                </div>
            </div>
        );
    }

    // No data state
    if (!summary || summary.domainGroups.length === 0) {
        return (
            <div className="container-fluid p-4">
                <div className="alert alert-info" role="alert">
                    <h4 className="alert-heading">
                        <i className="bi bi-info-circle me-2"></i>
                        No Domains Found
                    </h4>
                    <p>
                        You don't have any AD domains mapped to your user account, or there are no devices in CrowdStrike for your mapped domains.
                    </p>
                    <p className="mb-0">
                        Please contact your administrator to set up domain mappings.
                    </p>
                </div>
                <a href="/" className="btn btn-secondary">
                    <i className="bi bi-house me-2"></i>
                    Back to Home
                </a>
            </div>
        );
    }

    // Success state - display domain groups
    return (
        <div className="container-fluid p-4">
            {/* Header */}
            <div className="d-flex justify-content-between align-items-center mb-4">
                <h2>
                    <i className="bi bi-hdd-network me-2"></i>
                    Domain Vulnerabilities
                </h2>
                <button className="btn btn-outline-primary" onClick={fetchDomainVulns}>
                    <i className="bi bi-arrow-clockwise me-2"></i>
                    Refresh from Falcon API
                </button>
            </div>

            {/* Info banner */}
            <div className="alert alert-info d-flex align-items-center mb-4" role="alert">
                <i className="bi bi-info-circle-fill me-2"></i>
                <div>
                    <strong>Live Data from CrowdStrike Falcon</strong> - This view queries the Falcon API directly in real-time based on your domain mappings.
                </div>
            </div>

            {/* Summary Stats */}
            <div className="row mb-4">
                <div className="col-md-3">
                    <div className="card text-center border-0 shadow-sm">
                        <div className="card-body">
                            <h6 className="card-title text-muted mb-3">AD Domains</h6>
                            <p className="h3 mb-0">{summary.domainGroups.length}</p>
                        </div>
                    </div>
                </div>
                <div className="col-md-3">
                    <div className="card text-center border-0 shadow-sm">
                        <div className="card-body">
                            <h6 className="card-title text-muted mb-3">Total Devices</h6>
                            <p className="h3 mb-0">{summary.totalDevices}</p>
                        </div>
                    </div>
                </div>
                <div className="col-md-3">
                    <div className="card text-center border-0 shadow-sm">
                        <div className="card-body">
                            <h6 className="card-title text-muted mb-3">Total Vulnerabilities</h6>
                            <p className="h3 mb-0">{summary.totalVulnerabilities}</p>
                        </div>
                    </div>
                </div>
                {/* Global severity summary */}
                <div className="col-md-3">
                    <div className="card text-center border-0 shadow-sm">
                        <div className="card-body">
                            <h6 className="card-title text-muted mb-3">By Severity</h6>
                            <div className="d-flex flex-column gap-2 align-items-center">
                                <SeverityBadge
                                    severity="CRITICAL"
                                    count={summary.globalCritical ?? 0}
                                />
                                <SeverityBadge
                                    severity="HIGH"
                                    count={summary.globalHigh ?? 0}
                                />
                                <SeverityBadge
                                    severity="MEDIUM"
                                    count={summary.globalMedium ?? 0}
                                />
                                <SeverityBadge
                                    severity="LOW"
                                    count={summary.globalLow ?? 0}
                                />
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            {/* Query timestamp */}
            <div className="row mb-4">
                <div className="col-12">
                    <div className="card border-0 shadow-sm bg-light">
                        <div className="card-body">
                            <h6 className="text-muted mb-1">Queried from Falcon API</h6>
                            <div className="fw-semibold">{formatTimestamp(summary.queriedAt)}</div>
                        </div>
                    </div>
                </div>
            </div>

            {/* Domain Groups */}
            {summary.domainGroups.map((group) => {
                console.log('[DomainVulnsView] Rendering group:', {
                    domain: group.domain,
                    totalDevices: group.totalDevices,
                    devicesArray: group.devices,
                    devicesLength: group.devices?.length,
                    fullGroup: group
                });

                return (
                    <div key={group.domain} className="card mb-4 border-0 shadow-sm">
                        <div className="card-header bg-light border-bottom">
                            <div className="d-flex justify-content-between align-items-center flex-wrap gap-2">
                                <h5 className="mb-0 text-dark">
                                    <i className="bi bi-hdd-network me-2 text-primary"></i>
                                    Domain: {group.domain}
                                </h5>
                                {/* Domain-level severity breakdown */}
                                <div className="d-flex gap-2 flex-wrap align-items-center">
                                    <span className="badge bg-secondary bg-opacity-10 text-secondary border border-secondary">
                                        {group.totalDevices} device{group.totalDevices !== 1 ? 's' : ''}
                                    </span>
                                    <SeverityBadge
                                        severity="CRITICAL"
                                        count={group.totalCritical ?? 0}
                                    />
                                    <SeverityBadge
                                        severity="HIGH"
                                        count={group.totalHigh ?? 0}
                                    />
                                    <SeverityBadge
                                        severity="MEDIUM"
                                        count={group.totalMedium ?? 0}
                                    />
                                    <SeverityBadge
                                        severity="LOW"
                                        count={group.totalLow ?? 0}
                                    />
                                </div>
                            </div>
                        </div>
                        <div className="card-body">
                            {/* Device table */}
                            <div className="table-responsive">
                                <table className="table table-hover align-middle mb-0">
                                    <thead className="table-light">
                                        <tr>
                                            <th>Hostname</th>
                                            <th>IP Address</th>
                                            <th className="text-end">Vulnerabilities</th>
                                            <th className="text-end">Critical</th>
                                            <th className="text-end">High</th>
                                            <th className="text-end">Medium</th>
                                            <th className="text-end">Low</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {group.devices && group.devices.length > 0 ? (
                                            group.devices.map((device) => (
                                                <tr key={device.hostname}>
                                                    <td>
                                                        <strong>{device.hostname}</strong>
                                                    </td>
                                                    <td>
                                                        <code className="text-muted">{device.ip || 'N/A'}</code>
                                                    </td>
                                                    <td className="text-end">
                                                        <span className="badge bg-primary">
                                                            {device.vulnerabilityCount}
                                                        </span>
                                                    </td>
                                                    <td className="text-end">
                                                        <span className="badge bg-danger">
                                                            {device.criticalCount ?? 0}
                                                        </span>
                                                    </td>
                                                    <td className="text-end">
                                                        <span className="badge bg-warning text-dark">
                                                            {device.highCount ?? 0}
                                                        </span>
                                                    </td>
                                                    <td className="text-end">
                                                        <span className="badge bg-info text-dark">
                                                            {device.mediumCount ?? 0}
                                                        </span>
                                                    </td>
                                                    <td className="text-end">
                                                        <span className="badge bg-secondary">
                                                            {device.lowCount ?? 0}
                                                        </span>
                                                    </td>
                                                </tr>
                                            ))
                                        ) : (
                                            <tr>
                                                <td colSpan={7} className="text-center text-muted py-4">
                                                    No devices found in this domain
                                                </td>
                                            </tr>
                                        )}
                                    </tbody>
                                </table>
                            </div>
                        </div>
                    </div>
                );
            })}

            {/* Back to Home button */}
            <div className="mt-4">
                <a href="/" className="btn btn-secondary">
                    <i className="bi bi-house me-2"></i>
                    Back to Home
                </a>
            </div>
        </div>
    );
};

export default DomainVulnsView;
