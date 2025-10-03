/**
 * Current Vulnerabilities Table Component
 *
 * Displays current vulnerabilities with filtering and sorting capabilities
 *
 * Features:
 * - Filter by severity, system, and exception status
 * - Sortable columns
 * - Exception status badges with tooltips
 * - Loading and error states
 *
 * Related to: Feature 004-i-want-to (VULN Role & Vulnerability Management UI)
 */

import React, { useState, useEffect } from 'react';
import { getCurrentVulnerabilities, type CurrentVulnerability } from '../services/vulnerabilityManagementService';

const CurrentVulnerabilitiesTable: React.FC = () => {
    const [vulnerabilities, setVulnerabilities] = useState<CurrentVulnerability[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // Filter states
    const [severityFilter, setSeverityFilter] = useState<string>('');
    const [systemFilter, setSystemFilter] = useState<string>('');
    const [exceptionFilter, setExceptionFilter] = useState<string>('');

    // Sort states
    const [sortField, setSortField] = useState<keyof CurrentVulnerability>('scanTimestamp');
    const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc');

    useEffect(() => {
        fetchVulnerabilities();
    }, [severityFilter, systemFilter, exceptionFilter]);

    const fetchVulnerabilities = async () => {
        try {
            setLoading(true);
            const data = await getCurrentVulnerabilities(
                severityFilter || undefined,
                systemFilter || undefined,
                exceptionFilter || undefined
            );
            setVulnerabilities(data);
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to fetch vulnerabilities');
        } finally {
            setLoading(false);
        }
    };

    const handleSort = (field: keyof CurrentVulnerability) => {
        if (sortField === field) {
            setSortOrder(sortOrder === 'asc' ? 'desc' : 'asc');
        } else {
            setSortField(field);
            setSortOrder('asc');
        }
    };

    const getSortedVulnerabilities = () => {
        return [...vulnerabilities].sort((a, b) => {
            const aVal = a[sortField];
            const bVal = b[sortField];

            if (aVal === null || aVal === undefined) return 1;
            if (bVal === null || bVal === undefined) return -1;

            const comparison = aVal < bVal ? -1 : aVal > bVal ? 1 : 0;
            return sortOrder === 'asc' ? comparison : -comparison;
        });
    };

    const getSeverityBadgeClass = (severity: string | null): string => {
        if (!severity) return 'bg-secondary';
        const sev = severity.toLowerCase();
        if (sev.includes('critical')) return 'bg-danger';
        if (sev.includes('high')) return 'bg-warning text-dark';
        if (sev.includes('medium')) return 'bg-info text-dark';
        if (sev.includes('low')) return 'bg-success';
        return 'bg-secondary';
    };

    const getExceptionBadge = (status: string) => {
        switch (status) {
            case 'EXCEPTED':
                return <span className="badge bg-success" title="This vulnerability is excepted">Excepted</span>;
            case 'NOT_EXCEPTED':
                return <span className="badge bg-danger" title="No active exception">Not Excepted</span>;
            case 'EXPIRED':
                return <span className="badge bg-warning text-dark" title="Exception has expired">Expired</span>;
            default:
                return <span className="badge bg-secondary">{status}</span>;
        }
    };

    const SortIcon: React.FC<{ field: keyof CurrentVulnerability }> = ({ field }) => {
        if (sortField !== field) return <i className="bi bi-chevron-expand ms-1 text-muted"></i>;
        return sortOrder === 'asc' ?
            <i className="bi bi-chevron-up ms-1"></i> :
            <i className="bi bi-chevron-down ms-1"></i>;
    };

    if (loading) {
        return (
            <div className="d-flex justify-content-center p-5">
                <div className="spinner-border" role="status">
                    <span className="visually-hidden">Loading...</span>
                </div>
            </div>
        );
    }

    if (error) {
        return (
            <div className="container-fluid p-4">
                <div className="alert alert-danger" role="alert">
                    <i className="bi bi-exclamation-triangle me-2"></i>
                    {error}
                </div>
            </div>
        );
    }

    const sortedVulnerabilities = getSortedVulnerabilities();

    return (
        <div className="container-fluid p-4">
            <div className="row">
                <div className="col-12">
                    <div className="d-flex justify-content-between align-items-center mb-4">
                        <h2>
                            <i className="bi bi-shield-exclamation me-2"></i>
                            Current Vulnerabilities
                        </h2>
                        <button
                            className="btn btn-outline-primary"
                            onClick={fetchVulnerabilities}
                        >
                            <i className="bi bi-arrow-clockwise me-2"></i>
                            Refresh
                        </button>
                    </div>
                </div>
            </div>

            {/* Filters */}
            <div className="row mb-4">
                <div className="col-md-4">
                    <label htmlFor="severityFilter" className="form-label">Severity</label>
                    <select
                        id="severityFilter"
                        className="form-select"
                        value={severityFilter}
                        onChange={(e) => setSeverityFilter(e.target.value)}
                    >
                        <option value="">All Severities</option>
                        <option value="Critical">Critical</option>
                        <option value="High">High</option>
                        <option value="Medium">Medium</option>
                        <option value="Low">Low</option>
                    </select>
                </div>
                <div className="col-md-4">
                    <label htmlFor="systemFilter" className="form-label">System</label>
                    <input
                        type="text"
                        id="systemFilter"
                        className="form-control"
                        placeholder="Filter by system name..."
                        value={systemFilter}
                        onChange={(e) => setSystemFilter(e.target.value)}
                    />
                </div>
                <div className="col-md-4">
                    <label htmlFor="exceptionFilter" className="form-label">Exception Status</label>
                    <select
                        id="exceptionFilter"
                        className="form-select"
                        value={exceptionFilter}
                        onChange={(e) => setExceptionFilter(e.target.value)}
                    >
                        <option value="">All Statuses</option>
                        <option value="EXCEPTED">Excepted</option>
                        <option value="NOT_EXCEPTED">Not Excepted</option>
                        <option value="EXPIRED">Expired</option>
                    </select>
                </div>
            </div>

            {/* Table */}
            <div className="row">
                <div className="col-12">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                Vulnerabilities ({sortedVulnerabilities.length})
                            </h5>
                            {sortedVulnerabilities.length === 0 ? (
                                <p className="text-muted">No vulnerabilities found.</p>
                            ) : (
                                <div className="table-responsive">
                                    <table className="table table-striped table-hover">
                                        <thead>
                                            <tr>
                                                <th
                                                    onClick={() => handleSort('assetName')}
                                                    style={{ cursor: 'pointer' }}
                                                >
                                                    System
                                                    <SortIcon field="assetName" />
                                                </th>
                                                <th
                                                    onClick={() => handleSort('assetIp')}
                                                    style={{ cursor: 'pointer' }}
                                                >
                                                    IP
                                                    <SortIcon field="assetIp" />
                                                </th>
                                                <th
                                                    onClick={() => handleSort('vulnerabilityId')}
                                                    style={{ cursor: 'pointer' }}
                                                >
                                                    CVE
                                                    <SortIcon field="vulnerabilityId" />
                                                </th>
                                                <th
                                                    onClick={() => handleSort('cvssSeverity')}
                                                    style={{ cursor: 'pointer' }}
                                                >
                                                    Severity
                                                    <SortIcon field="cvssSeverity" />
                                                </th>
                                                <th
                                                    onClick={() => handleSort('vulnerableProductVersions')}
                                                    style={{ cursor: 'pointer' }}
                                                >
                                                    Product
                                                    <SortIcon field="vulnerableProductVersions" />
                                                </th>
                                                <th
                                                    onClick={() => handleSort('daysOpen')}
                                                    style={{ cursor: 'pointer' }}
                                                >
                                                    Days Open
                                                    <SortIcon field="daysOpen" />
                                                </th>
                                                <th
                                                    onClick={() => handleSort('scanTimestamp')}
                                                    style={{ cursor: 'pointer' }}
                                                >
                                                    Scan Date
                                                    <SortIcon field="scanTimestamp" />
                                                </th>
                                                <th
                                                    onClick={() => handleSort('exceptionStatus')}
                                                    style={{ cursor: 'pointer' }}
                                                >
                                                    Exception
                                                    <SortIcon field="exceptionStatus" />
                                                </th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {sortedVulnerabilities.map((vuln) => (
                                                <tr key={vuln.id}>
                                                    <td>{vuln.assetName}</td>
                                                    <td>{vuln.assetIp || '-'}</td>
                                                    <td>
                                                        <code>{vuln.vulnerabilityId || '-'}</code>
                                                    </td>
                                                    <td>
                                                        <span className={`badge ${getSeverityBadgeClass(vuln.cvssSeverity)}`}>
                                                            {vuln.cvssSeverity || 'Unknown'}
                                                        </span>
                                                    </td>
                                                    <td style={{ maxWidth: '200px', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                                                        {vuln.vulnerableProductVersions || '-'}
                                                    </td>
                                                    <td>{vuln.daysOpen || '-'}</td>
                                                    <td>
                                                        {vuln.scanTimestamp ?
                                                            new Date(vuln.scanTimestamp).toLocaleDateString() : '-'}
                                                    </td>
                                                    <td>
                                                        {getExceptionBadge(vuln.exceptionStatus)}
                                                    </td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            </div>

            {/* Back to Home button */}
            <div className="row mt-4">
                <div className="col-12">
                    <a href="/" className="btn btn-secondary">
                        <i className="bi bi-house me-2"></i>
                        Back to Home
                    </a>
                </div>
            </div>
        </div>
    );
};

export default CurrentVulnerabilitiesTable;
