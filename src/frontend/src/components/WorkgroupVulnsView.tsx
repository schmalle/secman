/**
 * Workgroup Vulns View Component
 *
 * Main component for WG Vulns feature - displays vulnerabilities grouped by workgroups.
 *
 * Features:
 * - Fetches workgroup vulnerability summary from backend
 * - Displays assets grouped by workgroup
 * - Displays severity breakdown (critical, high, medium) at all levels
 * - Handles loading, error, and empty states
 * - Admin redirect handling
 * - No workgroup membership error handling
 *
 * Related to: Feature 022-wg-vulns-handling
 */

import React, { useState, useEffect } from 'react';
import { getWorkgroupVulns, type WorkgroupVulnsSummary } from '../services/workgroupVulnsService';
import AssetVulnTable from './AssetVulnTable';
import SeverityBadge from './SeverityBadge';

const WorkgroupVulnsView: React.FC = () => {
    console.log('[WorkgroupVulnsView] Component mounting...');

    const [summary, setSummary] = useState<WorkgroupVulnsSummary | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [isAdminRedirect, setIsAdminRedirect] = useState(false);

    useEffect(() => {
        console.log('[WorkgroupVulnsView] useEffect triggered, calling fetchWorkgroupVulns...');
        fetchWorkgroupVulns();
    }, []);

    const fetchWorkgroupVulns = async () => {
        try {
            console.log('[WorkgroupVulnsView] fetchWorkgroupVulns started - setting loading=true');
            setLoading(true);
            setError(null);
            setIsAdminRedirect(false);

            console.log('[WorkgroupVulnsView] Calling getWorkgroupVulns() API...');
            const data = await getWorkgroupVulns();
            console.log('[WorkgroupVulnsView] API call successful, received data:', data);
            console.log('[WorkgroupVulnsView] - Workgroup groups:', data.workgroupGroups?.length || 0);
            console.log('[WorkgroupVulnsView] - Total assets:', data.totalAssets);
            console.log('[WorkgroupVulnsView] - Total vulnerabilities:', data.totalVulnerabilities);

            setSummary(data);
            console.log('[WorkgroupVulnsView] State updated with summary data');
        } catch (err) {
            console.error('[WorkgroupVulnsView] Error in fetchWorkgroupVulns:', err);
            const errorMessage = err instanceof Error ? err.message : 'Failed to load workgroup vulnerabilities';
            console.log('[WorkgroupVulnsView] Error message:', errorMessage);

            // Check if it's an admin redirect error (403)
            if (errorMessage.includes('System Vulns') || errorMessage.includes('403')) {
                console.log('[WorkgroupVulnsView] Detected admin redirect error');
                setIsAdminRedirect(true);
            }

            setError(errorMessage);
        } finally {
            console.log('[WorkgroupVulnsView] fetchWorkgroupVulns completed - setting loading=false');
            setLoading(false);
        }
    };

    // Loading state
    console.log('[WorkgroupVulnsView] Render - loading:', loading, 'error:', error, 'isAdminRedirect:', isAdminRedirect, 'summary:', summary);

    if (loading) {
        console.log('[WorkgroupVulnsView] Rendering loading state');
        return (
            <div className="container-fluid p-4">
                <div className="d-flex justify-content-center align-items-center" style={{ minHeight: '400px' }}>
                    <div className="text-center">
                        <div className="spinner-border text-primary mb-3" role="status">
                            <span className="visually-hidden">Loading...</span>
                        </div>
                        <p className="text-muted">Loading workgroup vulnerabilities...</p>
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
                        Admin users should use the System Vulns view to see all vulnerabilities across all assets.
                    </p>
                    <hr />
                    <div className="mb-0">
                        <a href="/vulnerabilities/system" className="btn btn-primary me-2">
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
                        Error Loading Workgroup Vulnerabilities
                    </h4>
                    <p>{error}</p>
                    <hr />
                    <div className="mb-0">
                        <button className="btn btn-primary me-2" onClick={fetchWorkgroupVulns}>
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
    if (!summary || summary.workgroupGroups.length === 0) {
        return (
            <div className="container-fluid p-4">
                <div className="alert alert-info" role="alert">
                    <h4 className="alert-heading">
                        <i className="bi bi-info-circle me-2"></i>
                        No Workgroups Found
                    </h4>
                    <p>
                        You are not a member of any workgroups, or there are no assets in your workgroups.
                    </p>
                    <p className="mb-0">
                        Please contact your administrator to be added to workgroups.
                    </p>
                </div>
                <a href="/" className="btn btn-secondary">
                    <i className="bi bi-house me-2"></i>
                    Back to Home
                </a>
            </div>
        );
    }

    // Success state - display workgroup groups
    return (
        <div className="container-fluid p-4">
            {/* Header */}
            <div className="d-flex justify-content-between align-items-center mb-4">
                <h2>
                    <i className="bi bi-people-fill me-2"></i>
                    Workgroup Vulnerabilities
                </h2>
                <button className="btn btn-outline-primary" onClick={fetchWorkgroupVulns}>
                    <i className="bi bi-arrow-clockwise me-2"></i>
                    Refresh
                </button>
            </div>

            {/* Summary Stats */}
            <div className="row mb-4">
                <div className="col-md-3">
                    <div className="card text-center border-0 shadow-sm">
                        <div className="card-body">
                            <h6 className="card-title text-muted mb-3">Workgroups</h6>
                            <p className="h3 mb-0">{summary.workgroupGroups.length}</p>
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
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            {/* Workgroup Groups */}
            {summary.workgroupGroups.map((group) => {
                console.log('[WorkgroupVulnsView] Rendering group:', {
                    workgroupId: group.workgroupId,
                    workgroupName: group.workgroupName,
                    totalAssets: group.totalAssets,
                    assetsArray: group.assets,
                    assetsLength: group.assets?.length,
                    assetsType: typeof group.assets,
                    fullGroup: group
                });

                return (
                    <div key={group.workgroupId} className="card mb-4 border-0 shadow-sm">
                        <div className="card-header bg-light border-bottom">
                            <div className="d-flex justify-content-between align-items-center flex-wrap gap-2">
                                <div>
                                    <h5 className="mb-0 text-dark">
                                        <i className="bi bi-people-fill me-2 text-primary"></i>
                                        {group.workgroupName}
                                    </h5>
                                    {group.workgroupDescription && (
                                        <p className="text-muted small mb-0 mt-1">
                                            {group.workgroupDescription}
                                        </p>
                                    )}
                                </div>
                                {/* Workgroup-level severity breakdown */}
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
                                awsAccountId={undefined}
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

export default WorkgroupVulnsView;
