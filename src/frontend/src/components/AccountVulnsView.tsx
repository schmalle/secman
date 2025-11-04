/**
 * Account Vulns View Component
 *
 * Main component for Account Vulns feature - displays vulnerabilities grouped by AWS account.
 *
 * Features:
 * - Fetches account vulnerability summary from backend
 * - Displays assets grouped by AWS account
 * - Feature 019: Displays severity breakdown (critical, high, medium) at all levels
 * - Handles loading, error, and empty states
 * - Admin redirect handling
 * - No AWS account mapping error handling
 *
 * Related to: Feature 018-under-vuln-management (Account Vulns)
 * Feature 019: Account Vulns Severity Breakdown
 * User Story: US1 (P1) - View Vulnerabilities for Single AWS Account
 * User Story: US2 (P1) - View Account-Level Severity Aggregation
 * User Story: US3 (P1) - Global Severity Summary
 */

import React, { useState, useEffect } from 'react';
import { getAccountVulns, type AccountVulnsSummary } from '../services/accountVulnsService';
import AssetVulnTable from './AssetVulnTable';
import SeverityBadge from './SeverityBadge';

const AccountVulnsView: React.FC = () => {
    console.log('[AccountVulnsView] Component mounting...');

    const [summary, setSummary] = useState<AccountVulnsSummary | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [isAdminRedirect, setIsAdminRedirect] = useState(false);

    const formatImportTimestamp = (timestamp: string): string => {
        const date = new Date(timestamp);
        if (Number.isNaN(date.getTime())) {
            return timestamp;
        }

        return date.toLocaleString(undefined, {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit'
        });
    };

    useEffect(() => {
        console.log('[AccountVulnsView] useEffect triggered, calling fetchAccountVulns...');
        fetchAccountVulns();
    }, []);

    const fetchAccountVulns = async () => {
        try {
            console.log('[AccountVulnsView] fetchAccountVulns started - setting loading=true');
            setLoading(true);
            setError(null);
            setIsAdminRedirect(false);

            console.log('[AccountVulnsView] Calling getAccountVulns() API...');
            const data = await getAccountVulns();
            console.log('[AccountVulnsView] API call successful, received data:', data);
            console.log('[AccountVulnsView] - Account groups:', data.accountGroups?.length || 0);
            console.log('[AccountVulnsView] - Total assets:', data.totalAssets);
            console.log('[AccountVulnsView] - Total vulnerabilities:', data.totalVulnerabilities);

            setSummary(data);
            console.log('[AccountVulnsView] State updated with summary data');
        } catch (err) {
            console.error('[AccountVulnsView] Error in fetchAccountVulns:', err);
            const errorMessage = err instanceof Error ? err.message : 'Failed to load account vulnerabilities';
            console.log('[AccountVulnsView] Error message:', errorMessage);

            // Check if it's an admin redirect error (403)
            if (errorMessage.includes('System Vulns') || errorMessage.includes('403')) {
                console.log('[AccountVulnsView] Detected admin redirect error');
                setIsAdminRedirect(true);
            }

            setError(errorMessage);
        } finally {
            console.log('[AccountVulnsView] fetchAccountVulns completed - setting loading=false');
            setLoading(false);
        }
    };

    // Loading state
    console.log('[AccountVulnsView] Render - loading:', loading, 'error:', error, 'isAdminRedirect:', isAdminRedirect, 'summary:', summary);

    if (loading) {
        console.log('[AccountVulnsView] Rendering loading state');
        return (
            <div className="container-fluid p-4">
                <div className="d-flex justify-content-center align-items-center" style={{ minHeight: '400px' }}>
                    <div className="text-center">
                        <div className="spinner-border text-primary mb-3" role="status">
                            <span className="visually-hidden">Loading...</span>
                        </div>
                        <p className="text-muted">Loading account vulnerabilities...</p>
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
                        Admin users should use the System Vulns view to see all vulnerabilities across all accounts.
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
                        Error Loading Account Vulnerabilities
                    </h4>
                    <p>{error}</p>
                    <hr />
                    <div className="mb-0">
                        <button className="btn btn-primary me-2" onClick={fetchAccountVulns}>
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
    if (!summary || summary.accountGroups.length === 0) {
        return (
            <div className="container-fluid p-4">
                <div className="alert alert-info" role="alert">
                    <h4 className="alert-heading">
                        <i className="bi bi-info-circle me-2"></i>
                        No AWS Accounts Found
                    </h4>
                    <p>
                        You don't have any AWS accounts mapped to your user account, or there are no assets in your mapped accounts.
                    </p>
                    <p className="mb-0">
                        Please contact your administrator to set up AWS account mappings.
                    </p>
                </div>
                <a href="/" className="btn btn-secondary">
                    <i className="bi bi-house me-2"></i>
                    Back to Home
                </a>
            </div>
        );
    }

    const lastImport = summary.lastImport ?? null;

    // Success state - display account groups
    return (
        <div className="container-fluid p-4">
            {/* Header */}
            <div className="d-flex justify-content-between align-items-center mb-4">
                <h2>
                    <i className="bi bi-cloud me-2"></i>
                    Account Vulnerabilities
                </h2>
                <button className="btn btn-outline-primary" onClick={fetchAccountVulns}>
                    <i className="bi bi-arrow-clockwise me-2"></i>
                    Refresh
                </button>
            </div>

            {/* Summary Stats */}
            <div className="row mb-4">
                <div className="col-md-3">
                    <div className="card text-center border-0 shadow-sm">
                        <div className="card-body">
                            <h6 className="card-title text-muted mb-3">AWS Accounts</h6>
                            <p className="h3 mb-0">{summary.accountGroups.length}</p>
                        </div>
                    </div>
                </div>
                <div className="col-md-3">
                    <div className="card text-center border-0 shadow-sm">
                        <div className="card-body">
                            <h6 className="card-title text-muted mb-3">Total Assets</h6>
                            <p className="h3 mb-0">{summary.totalAssets}</p>
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
                {/* Feature 019: Global severity summary (US3) */}
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
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            {lastImport && (
                <div className="row mb-4">
                    <div className="col-12">
                        <div className="card border-0 shadow-sm bg-light">
                            <div className="card-body d-flex flex-column flex-lg-row align-items-start align-items-lg-center gap-3">
                                <div>
                                    <h6 className="text-muted mb-1">Last CrowdStrike Import</h6>
                                    <div className="fw-semibold">{formatImportTimestamp(lastImport.importedAt)}</div>
                                    <small className="text-muted">
                                        Imported by {lastImport.importedBy || 'system automation'}
                                    </small>
                                </div>
                                <div className="d-flex flex-wrap gap-2 ms-lg-auto">
                                    <span className="badge bg-primary bg-opacity-10 text-primary border border-primary">
                                        {lastImport.serversProcessed} servers processed
                                    </span>
                                    <span className="badge bg-success bg-opacity-10 text-success border border-success">
                                        {lastImport.vulnerabilitiesImported} vulnerabilities imported
                                    </span>
                                    <span className="badge bg-secondary bg-opacity-10 text-secondary border border-secondary">
                                        {lastImport.vulnerabilitiesSkipped} skipped
                                    </span>
                                    <span className="badge bg-info bg-opacity-10 text-info border border-info">
                                        {lastImport.serversCreated} created / {lastImport.serversUpdated} updated
                                    </span>
                                    {lastImport.errorCount > 0 && (
                                        <span className="badge bg-danger bg-opacity-10 text-danger border border-danger">
                                            {lastImport.errorCount} error{lastImport.errorCount !== 1 ? 's' : ''}
                                        </span>
                                    )}
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Account Groups */}
            {summary.accountGroups.map((group) => {
                console.log('[AccountVulnsView] Rendering group:', {
                    awsAccountId: group.awsAccountId,
                    totalAssets: group.totalAssets,
                    assetsArray: group.assets,
                    assetsLength: group.assets?.length,
                    assetsType: typeof group.assets,
                    fullGroup: group
                });

                return (
                    <div key={group.awsAccountId} className="card mb-4 border-0 shadow-sm">
                        <div className="card-header bg-light border-bottom">
                            <div className="d-flex justify-content-between align-items-center flex-wrap gap-2">
                                <h5 className="mb-0 text-dark">
                                    <i className="bi bi-cloud me-2 text-primary"></i>
                                    AWS Account: {group.awsAccountId}
                                </h5>
                                {/* Feature 019: Account-level severity breakdown (US2) */}
                                <div className="d-flex gap-2 flex-wrap align-items-center">
                                    <span className="badge bg-secondary bg-opacity-10 text-secondary border border-secondary">
                                        {group.totalAssets} asset{group.totalAssets !== 1 ? 's' : ''}
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
                                </div>
                            </div>
                        </div>
                        <div className="card-body">
                            <AssetVulnTable
                                assets={group.assets || []}
                                awsAccountId={group.awsAccountId}
                            />
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

export default AccountVulnsView;
