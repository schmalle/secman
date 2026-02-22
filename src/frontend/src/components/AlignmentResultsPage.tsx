/**
 * AlignmentResultsPage Component
 *
 * Public read-only page for viewing alignment results after finalization.
 * Accessed via token link from finalization notification email.
 * Shows accepted/rejected decisions for each requirement change.
 */

import React, { useState, useEffect } from 'react';

const API_BASE = import.meta.env.PUBLIC_API_URL || '';

type FilterType = 'all' | 'accepted' | 'rejected' | 'noDecision';

interface AlignmentResultsPageProps {
    token: string;
}

interface ResultsData {
    session: {
        id: number;
        releaseName: string;
        releaseVersion: string;
        status: string;
        changedRequirementsCount: number;
        startedAt: string | null;
        completedAt: string | null;
        completionNotes: string | null;
    };
    summary: {
        total: number;
        accepted: number;
        rejected: number;
        noDecision: number;
    };
    requirements: RequirementResult[];
}

interface RequirementResult {
    snapshotId: number;
    requirementId: string;
    shortreq: string;
    details: string | null;
    changeType: string;
    chapter: string | null;
    assessments: {
        ok: number;
        change: number;
        nogo: number;
    };
    reviews: {
        reviewerName: string;
        assessment: string;
        comment: string | null;
    }[];
    adminDecision: {
        decision: string;
        comment: string | null;
        decidedBy: string;
    } | null;
}

export const AlignmentResultsPage: React.FC<AlignmentResultsPageProps> = ({ token }) => {
    const [data, setData] = useState<ResultsData | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [filter, setFilter] = useState<FilterType>('all');
    const [expandedSnapshot, setExpandedSnapshot] = useState<number | null>(null);

    useEffect(() => {
        loadData();
    }, [token]);

    const loadData = async () => {
        setIsLoading(true);
        setError(null);

        try {
            const response = await fetch(`${API_BASE}/api/alignment/results/${token}`);
            if (!response.ok) {
                if (response.status === 404) {
                    throw new Error('Invalid or expired results link');
                }
                throw new Error(`Failed to load results: ${response.statusText}`);
            }
            const resultsData = await response.json();
            setData(resultsData);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to load alignment results');
        } finally {
            setIsLoading(false);
        }
    };

    const getFilteredRequirements = (): RequirementResult[] => {
        if (!data) return [];
        switch (filter) {
            case 'accepted':
                return data.requirements.filter(r => r.adminDecision?.decision === 'ACCEPTED');
            case 'rejected':
                return data.requirements.filter(r => r.adminDecision?.decision === 'REJECTED');
            case 'noDecision':
                return data.requirements.filter(r => !r.adminDecision);
            default:
                return data.requirements;
        }
    };

    const formatDate = (dateStr: string | null): string => {
        if (!dateStr) return '—';
        try {
            return new Date(dateStr).toLocaleDateString('en-US', {
                year: 'numeric',
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
            });
        } catch {
            return dateStr;
        }
    };

    const getChangeTypeBadge = (changeType: string) => {
        const colors: Record<string, string> = {
            ADDED: 'bg-success',
            MODIFIED: 'bg-warning text-dark',
            DELETED: 'bg-danger',
        };
        return (
            <span className={`badge ${colors[changeType] || 'bg-secondary'}`}>
                {changeType}
            </span>
        );
    };

    const getDecisionBadge = (decision: string | null | undefined) => {
        if (!decision) {
            return <span className="badge bg-secondary">No Decision</span>;
        }
        if (decision === 'ACCEPTED') {
            return <span className="badge bg-success">Accepted</span>;
        }
        return <span className="badge bg-danger">Rejected</span>;
    };

    const getAssessmentBadge = (assessment: string) => {
        const colors: Record<string, string> = {
            OK: 'bg-success',
            CHANGE: 'bg-warning text-dark',
            NOGO: 'bg-danger',
        };
        return (
            <span className={`badge ${colors[assessment] || 'bg-secondary'} me-1`}>
                {assessment}
            </span>
        );
    };

    // Loading state
    if (isLoading) {
        return (
            <div className="container py-5">
                <div className="text-center">
                    <div className="spinner-border text-primary" role="status">
                        <span className="visually-hidden">Loading...</span>
                    </div>
                    <p className="mt-3 text-muted">Loading alignment results...</p>
                </div>
            </div>
        );
    }

    // Error state
    if (error) {
        return (
            <div className="container py-5">
                <div className="row justify-content-center">
                    <div className="col-md-6">
                        <div className="card border-danger">
                            <div className="card-body text-center">
                                <i className="bi bi-exclamation-triangle text-danger" style={{ fontSize: '3rem' }}></i>
                                <h4 className="mt-3">Unable to Load Results</h4>
                                <p className="text-muted">{error}</p>
                                <button className="btn btn-primary" onClick={loadData}>
                                    <i className="bi bi-arrow-clockwise me-2"></i>Try Again
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        );
    }

    if (!data) return null;

    const filtered = getFilteredRequirements();

    return (
        <div className="container py-4" style={{ maxWidth: '960px' }}>
            {/* Header */}
            <div className="text-center mb-4">
                <div
                    className="p-4 rounded-top"
                    style={{
                        background: 'linear-gradient(135deg, #28a745 0%, #20c997 100%)',
                    }}
                >
                    <h1 className="text-white mb-1" style={{ fontSize: '1.75rem' }}>
                        <i className="bi bi-check-circle me-2"></i>
                        Alignment Results
                    </h1>
                    <p className="text-white-50 mb-0">
                        {data.session.releaseName} v{data.session.releaseVersion}
                    </p>
                </div>
                <div className="bg-white p-3 rounded-bottom shadow-sm border border-top-0">
                    <div className="row text-muted small">
                        <div className="col-sm-6">
                            <i className="bi bi-calendar-event me-1"></i>
                            Started: {formatDate(data.session.startedAt)}
                        </div>
                        <div className="col-sm-6">
                            <i className="bi bi-calendar-check me-1"></i>
                            Completed: {formatDate(data.session.completedAt)}
                        </div>
                    </div>
                    {data.session.completionNotes && (
                        <p className="text-muted small mt-2 mb-0">
                            <i className="bi bi-chat-left-text me-1"></i>
                            {data.session.completionNotes}
                        </p>
                    )}
                </div>
            </div>

            {/* Summary Stats */}
            <div className="row g-3 mb-4">
                <div className="col-6 col-md-3">
                    <div className="card text-center h-100">
                        <div className="card-body py-3">
                            <div className="fs-3 fw-bold text-primary">{data.summary.total}</div>
                            <div className="text-muted small">Total Changes</div>
                        </div>
                    </div>
                </div>
                <div className="col-6 col-md-3">
                    <div className="card text-center h-100">
                        <div className="card-body py-3">
                            <div className="fs-3 fw-bold text-success">{data.summary.accepted}</div>
                            <div className="text-muted small">Accepted</div>
                        </div>
                    </div>
                </div>
                <div className="col-6 col-md-3">
                    <div className="card text-center h-100">
                        <div className="card-body py-3">
                            <div className="fs-3 fw-bold text-danger">{data.summary.rejected}</div>
                            <div className="text-muted small">Rejected</div>
                        </div>
                    </div>
                </div>
                <div className="col-6 col-md-3">
                    <div className="card text-center h-100">
                        <div className="card-body py-3">
                            <div className="fs-3 fw-bold text-secondary">{data.summary.noDecision}</div>
                            <div className="text-muted small">No Decision</div>
                        </div>
                    </div>
                </div>
            </div>

            {/* Filter Tabs */}
            <ul className="nav nav-pills mb-3">
                <li className="nav-item">
                    <button
                        className={`nav-link ${filter === 'all' ? 'active' : ''}`}
                        onClick={() => setFilter('all')}
                    >
                        All <span className="badge bg-light text-dark ms-1">{data.summary.total}</span>
                    </button>
                </li>
                <li className="nav-item">
                    <button
                        className={`nav-link ${filter === 'accepted' ? 'active' : ''}`}
                        onClick={() => setFilter('accepted')}
                    >
                        <i className="bi bi-check-circle me-1"></i>Accepted{' '}
                        <span className="badge bg-light text-dark ms-1">{data.summary.accepted}</span>
                    </button>
                </li>
                <li className="nav-item">
                    <button
                        className={`nav-link ${filter === 'rejected' ? 'active' : ''}`}
                        onClick={() => setFilter('rejected')}
                    >
                        <i className="bi bi-x-circle me-1"></i>Rejected{' '}
                        <span className="badge bg-light text-dark ms-1">{data.summary.rejected}</span>
                    </button>
                </li>
                <li className="nav-item">
                    <button
                        className={`nav-link ${filter === 'noDecision' ? 'active' : ''}`}
                        onClick={() => setFilter('noDecision')}
                    >
                        No Decision{' '}
                        <span className="badge bg-light text-dark ms-1">{data.summary.noDecision}</span>
                    </button>
                </li>
            </ul>

            {/* Requirements List */}
            {filtered.length === 0 ? (
                <div className="text-center text-muted py-5">
                    <i className="bi bi-inbox" style={{ fontSize: '3rem' }}></i>
                    <p className="mt-2">No requirements match the selected filter.</p>
                </div>
            ) : (
                <div className="d-flex flex-column gap-3">
                    {filtered.map((req) => {
                        const isExpanded = expandedSnapshot === req.snapshotId;
                        return (
                            <div key={req.snapshotId} className="card">
                                <div
                                    className="card-body"
                                    style={{ cursor: 'pointer' }}
                                    onClick={() =>
                                        setExpandedSnapshot(isExpanded ? null : req.snapshotId)
                                    }
                                >
                                    <div className="d-flex justify-content-between align-items-start">
                                        <div className="flex-grow-1">
                                            <div className="d-flex align-items-center gap-2 mb-1">
                                                <strong className="text-primary">{req.requirementId}</strong>
                                                {getChangeTypeBadge(req.changeType)}
                                                {getDecisionBadge(req.adminDecision?.decision)}
                                            </div>
                                            <p className="mb-0 text-truncate" style={{ maxWidth: '700px' }}>
                                                {req.shortreq}
                                            </p>
                                        </div>
                                        <div className="ms-3 text-muted">
                                            <i
                                                className={`bi bi-chevron-${isExpanded ? 'up' : 'down'}`}
                                            ></i>
                                        </div>
                                    </div>

                                    {/* Assessment summary (always visible) */}
                                    <div className="mt-2">
                                        {req.assessments.ok > 0 && (
                                            <span className="badge bg-success me-1">
                                                OK: {req.assessments.ok}
                                            </span>
                                        )}
                                        {req.assessments.change > 0 && (
                                            <span className="badge bg-warning text-dark me-1">
                                                CHANGE: {req.assessments.change}
                                            </span>
                                        )}
                                        {req.assessments.nogo > 0 && (
                                            <span className="badge bg-danger me-1">
                                                NOGO: {req.assessments.nogo}
                                            </span>
                                        )}
                                    </div>
                                </div>

                                {/* Expanded details */}
                                {isExpanded && (
                                    <div className="card-footer bg-light">
                                        {/* Requirement details */}
                                        {req.chapter && (
                                            <p className="text-muted small mb-2">
                                                <strong>Chapter:</strong> {req.chapter}
                                            </p>
                                        )}
                                        {req.details && (
                                            <p className="small mb-3">{req.details}</p>
                                        )}

                                        {/* Admin Decision */}
                                        {req.adminDecision && (
                                            <div
                                                className={`alert ${
                                                    req.adminDecision.decision === 'ACCEPTED'
                                                        ? 'alert-success'
                                                        : 'alert-danger'
                                                } py-2 mb-3`}
                                            >
                                                <strong>
                                                    <i
                                                        className={`bi ${
                                                            req.adminDecision.decision === 'ACCEPTED'
                                                                ? 'bi-check-circle'
                                                                : 'bi-x-circle'
                                                        } me-1`}
                                                    ></i>
                                                    Admin Decision: {req.adminDecision.decision}
                                                </strong>
                                                <span className="text-muted ms-2 small">
                                                    by {req.adminDecision.decidedBy}
                                                </span>
                                                {req.adminDecision.comment && (
                                                    <p className="mb-0 mt-1 small">
                                                        {req.adminDecision.comment}
                                                    </p>
                                                )}
                                            </div>
                                        )}

                                        {/* Reviewer assessments */}
                                        {req.reviews.length > 0 && (
                                            <>
                                                <h6 className="text-muted small text-uppercase mb-2">
                                                    Reviewer Assessments
                                                </h6>
                                                <div className="list-group list-group-flush">
                                                    {req.reviews.map((review, idx) => (
                                                        <div
                                                            key={idx}
                                                            className="list-group-item px-0 py-2 bg-transparent"
                                                        >
                                                            <div className="d-flex align-items-center justify-content-between">
                                                                <span className="small">
                                                                    <i className="bi bi-person me-1"></i>
                                                                    {review.reviewerName}
                                                                </span>
                                                                {getAssessmentBadge(review.assessment)}
                                                            </div>
                                                            {review.comment && (
                                                                <p className="mb-0 mt-1 small text-muted ps-3">
                                                                    {review.comment}
                                                                </p>
                                                            )}
                                                        </div>
                                                    ))}
                                                </div>
                                            </>
                                        )}

                                        {req.reviews.length === 0 && (
                                            <p className="text-muted small mb-0">
                                                No reviewer assessments submitted.
                                            </p>
                                        )}
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>
            )}

            {/* Footer */}
            <div className="text-center text-muted small mt-4 pb-4">
                <p className="mb-0">
                    <i className="bi bi-shield-lock me-1"></i>
                    This page is read-only and accessible via a secure link.
                </p>
            </div>
        </div>
    );
};

export default AlignmentResultsPage;
