/**
 * Account Vulns View Component
 *
 * Main component for Account Vulns feature - displays vulnerabilities grouped by AWS account.
 *
 * Features:
 * - Fetches account vulnerability summary from backend
 * - Displays assets grouped by AWS account
 * - Handles loading, error, and empty states
 * - Admin redirect handling
 * - No AWS account mapping error handling
 *
 * Related to: Feature 018-under-vuln-management (Account Vulns)
 * User Story: US1 (P1) - View Vulnerabilities for Single AWS Account
 * User Story: US3 (P1) - Error Handling for Missing Mappings
 * User Story: US4 (P1) - Admin Role Redirect
 */

import React, { useState, useEffect } from 'react';
import { getAccountVulns, type AccountVulnsSummary } from '../services/accountVulnsService';
import AssetVulnTable from './AssetVulnTable';

const AccountVulnsView: React.FC = () => {
    const [summary, setSummary] = useState<AccountVulnsSummary | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [isAdminRedirect, setIsAdminRedirect] = useState(false);

    useEffect(() => {
        fetchAccountVulns();
    }, []);

    const fetchAccountVulns = async () => {
        try {
            setLoading(true);
            setError(null);
            setIsAdminRedirect(false);

            const data = await getAccountVulns();
            setSummary(data);
        } catch (err) {
            const errorMessage = err instanceof Error ? err.message : 'Failed to load account vulnerabilities';

            // Check if it's an admin redirect error (403)
            if (errorMessage.includes('System Vulns') || errorMessage.includes('403')) {
                setIsAdminRedirect(true);
            }

            setError(errorMessage);
        } finally {
            setLoading(false);
        }
    };

    // Loading state
    if (loading) {
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
                <div className="col-md-4">
                    <div className="card text-center">
                        <div className="card-body">
                            <h5 className="card-title text-muted">AWS Accounts</h5>
                            <p className="display-6">{summary.accountGroups.length}</p>
                        </div>
                    </div>
                </div>
                <div className="col-md-4">
                    <div className="card text-center">
                        <div className="card-body">
                            <h5 className="card-title text-muted">Total Assets</h5>
                            <p className="display-6">{summary.totalAssets}</p>
                        </div>
                    </div>
                </div>
                <div className="col-md-4">
                    <div className="card text-center">
                        <div className="card-body">
                            <h5 className="card-title text-muted">Total Vulnerabilities</h5>
                            <p className="display-6 text-danger">{summary.totalVulnerabilities}</p>
                        </div>
                    </div>
                </div>
            </div>

            {/* Account Groups */}
            {summary.accountGroups.map((group) => (
                <div key={group.awsAccountId} className="card mb-4">
                    <div className="card-header bg-primary text-white">
                        <div className="d-flex justify-content-between align-items-center">
                            <h5 className="mb-0">
                                <i className="bi bi-cloud-fill me-2"></i>
                                AWS Account: {group.awsAccountId}
                            </h5>
                            <span className="badge bg-light text-dark">
                                {group.totalAssets} asset{group.totalAssets !== 1 ? 's' : ''} | {group.totalVulnerabilities} vuln{group.totalVulnerabilities !== 1 ? 's' : ''}
                            </span>
                        </div>
                    </div>
                    <div className="card-body">
                        <AssetVulnTable
                            assets={group.assets}
                            awsAccountId={group.awsAccountId}
                        />
                    </div>
                </div>
            ))}

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
