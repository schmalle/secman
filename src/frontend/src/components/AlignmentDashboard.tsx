/**
 * AlignmentDashboard Component
 *
 * Dashboard for Release Managers to view and manage alignment sessions.
 * Shows reviewer progress, assessment summary, and allows finalization.
 *
 * Feature: 068-requirements-alignment-process
 */

import React, { useState, useEffect } from 'react';
import {
    releaseService,
    type Release,
    type AlignmentStatus,
    type AlignmentReviewer,
} from '../services/releaseService';
import { hasRole } from '../utils/auth';

interface AlignmentDashboardProps {
    releaseId: number;
}

type TabType = 'overview' | 'reviewers' | 'feedback';

export const AlignmentDashboard: React.FC<AlignmentDashboardProps> = ({ releaseId }) => {
    const [release, setRelease] = useState<Release | null>(null);
    const [alignmentStatus, setAlignmentStatus] = useState<AlignmentStatus | null>(null);
    const [reviewers, setReviewers] = useState<AlignmentReviewer[]>([]);
    const [feedback, setFeedback] = useState<any[]>([]);
    const [activeTab, setActiveTab] = useState<TabType>('overview');
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [actionLoading, setActionLoading] = useState<string | null>(null);

    const canManage = typeof window !== 'undefined' && hasRole(['ADMIN', 'RELEASE_MANAGER']);

    // Load data
    useEffect(() => {
        loadData();
    }, [releaseId]);

    const loadData = async () => {
        setIsLoading(true);
        setError(null);

        try {
            const [releaseData, statusData] = await Promise.all([
                releaseService.getById(releaseId),
                releaseService.getAlignmentStatus(releaseId),
            ]);

            setRelease(releaseData);
            setAlignmentStatus(statusData);

            // Load reviewers if we have a session
            if (statusData.session.id) {
                const reviewerData = await releaseService.getAlignmentReviewers(statusData.session.id);
                setReviewers(reviewerData.reviewers);

                const feedbackData = await releaseService.getAlignmentFeedback(statusData.session.id);
                setFeedback(feedbackData.requirements || []);
            }
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to load alignment data');
        } finally {
            setIsLoading(false);
        }
    };

    const handleSendReminders = async () => {
        if (!alignmentStatus?.session.id) return;

        setActionLoading('remind');
        try {
            const result = await releaseService.sendAlignmentReminders(alignmentStatus.session.id);
            alert(`Sent ${result.remindersSent} reminder(s)`);
            loadData();
        } catch (err) {
            alert(err instanceof Error ? err.message : 'Failed to send reminders');
        } finally {
            setActionLoading(null);
        }
    };

    const handleFinalize = async (activate: boolean) => {
        if (!alignmentStatus?.session.id) return;

        const action = activate ? 'finalize and activate' : 'finalize';
        if (!confirm(`Are you sure you want to ${action} this alignment?`)) return;

        setActionLoading(activate ? 'activate' : 'finalize');
        try {
            await releaseService.finalizeAlignment(alignmentStatus.session.id, activate);
            window.location.href = `/releases/${releaseId}`;
        } catch (err) {
            alert(err instanceof Error ? err.message : 'Failed to finalize');
            setActionLoading(null);
        }
    };

    const handleCancel = async () => {
        if (!alignmentStatus?.session.id) return;

        if (!confirm('Are you sure you want to cancel this alignment? The release will return to DRAFT status.')) return;

        setActionLoading('cancel');
        try {
            await releaseService.cancelAlignment(alignmentStatus.session.id);
            window.location.href = `/releases/${releaseId}`;
        } catch (err) {
            alert(err instanceof Error ? err.message : 'Failed to cancel');
            setActionLoading(null);
        }
    };

    if (isLoading) {
        return (
            <div className="d-flex justify-content-center align-items-center py-5">
                <div className="spinner-border text-primary" role="status">
                    <span className="visually-hidden">Loading...</span>
                </div>
            </div>
        );
    }

    if (error || !alignmentStatus) {
        return (
            <div className="alert alert-danger">
                <i className="bi bi-exclamation-triangle me-2"></i>
                {error || 'No active alignment session found'}
            </div>
        );
    }

    const { session, reviewers: reviewerStats, requirements, assessments } = alignmentStatus;
    const isOpen = session.status === 'OPEN';

    return (
        <div className="alignment-dashboard">
            {/* Header */}
            <div className="d-flex justify-content-between align-items-start mb-4">
                <div>
                    <h2 className="mb-1">
                        <i className="bi bi-clipboard-check me-2 text-primary"></i>
                        Alignment Dashboard
                    </h2>
                    <p className="text-muted mb-0">
                        {release?.name} v{release?.version}
                    </p>
                </div>
                <div className="d-flex gap-2">
                    <a href={`/releases/${releaseId}`} className="btn btn-outline-secondary">
                        <i className="bi bi-arrow-left me-1"></i>
                        Back to Release
                    </a>
                </div>
            </div>

            {/* Status Banner */}
            <div className={`alert ${isOpen ? 'alert-info' : 'alert-secondary'} mb-4`}>
                <div className="d-flex justify-content-between align-items-center">
                    <div>
                        <strong>
                            <i className={`bi ${isOpen ? 'bi-hourglass-split' : 'bi-check-circle'} me-2`}></i>
                            Status: {session.status}
                        </strong>
                        <span className="ms-3 text-muted">
                            Started {new Date(session.startedAt).toLocaleDateString()}
                            {session.completedAt && ` • Completed ${new Date(session.completedAt).toLocaleDateString()}`}
                        </span>
                    </div>
                    {isOpen && canManage && (
                        <div className="btn-group">
                            <button
                                className="btn btn-outline-warning btn-sm"
                                onClick={handleSendReminders}
                                disabled={actionLoading !== null}
                            >
                                {actionLoading === 'remind' ? (
                                    <span className="spinner-border spinner-border-sm"></span>
                                ) : (
                                    <><i className="bi bi-bell me-1"></i>Send Reminders</>
                                )}
                            </button>
                        </div>
                    )}
                </div>
            </div>

            {/* Stats Cards */}
            <div className="row g-3 mb-4">
                <div className="col-md-3">
                    <div className="card h-100">
                        <div className="card-body text-center">
                            <h3 className="mb-0 text-primary">{requirements.total}</h3>
                            <small className="text-muted">Changed Requirements</small>
                        </div>
                    </div>
                </div>
                <div className="col-md-3">
                    <div className="card h-100">
                        <div className="card-body text-center">
                            <h3 className="mb-0">
                                {reviewerStats.completed}/{reviewerStats.total}
                            </h3>
                            <small className="text-muted">Reviewers Complete</small>
                            <div className="progress mt-2" style={{ height: '4px' }}>
                                <div
                                    className="progress-bar bg-success"
                                    style={{ width: `${reviewerStats.completionPercent}%` }}
                                ></div>
                            </div>
                        </div>
                    </div>
                </div>
                <div className="col-md-6">
                    <div className="card h-100">
                        <div className="card-body">
                            <div className="d-flex justify-content-around text-center">
                                <div>
                                    <h4 className="mb-0 text-success">{assessments.minor}</h4>
                                    <small className="text-muted">Minor</small>
                                </div>
                                <div>
                                    <h4 className="mb-0 text-warning">{assessments.major}</h4>
                                    <small className="text-muted">Major</small>
                                </div>
                                <div>
                                    <h4 className="mb-0 text-danger">{assessments.nok}</h4>
                                    <small className="text-muted">NOK</small>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            {/* Tabs */}
            <ul className="nav nav-tabs mb-3">
                <li className="nav-item">
                    <button
                        className={`nav-link ${activeTab === 'overview' ? 'active' : ''}`}
                        onClick={() => setActiveTab('overview')}
                    >
                        Overview
                    </button>
                </li>
                <li className="nav-item">
                    <button
                        className={`nav-link ${activeTab === 'reviewers' ? 'active' : ''}`}
                        onClick={() => setActiveTab('reviewers')}
                    >
                        Reviewers ({reviewers.length})
                    </button>
                </li>
                <li className="nav-item">
                    <button
                        className={`nav-link ${activeTab === 'feedback' ? 'active' : ''}`}
                        onClick={() => setActiveTab('feedback')}
                    >
                        Feedback ({feedback.length})
                    </button>
                </li>
            </ul>

            {/* Tab Content */}
            <div className="tab-content">
                {activeTab === 'overview' && (
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">Alignment Overview</h5>
                            <dl className="row mb-0">
                                <dt className="col-sm-3">Initiated By</dt>
                                <dd className="col-sm-9">{session.initiatedBy}</dd>

                                <dt className="col-sm-3">Baseline Release</dt>
                                <dd className="col-sm-9">
                                    {session.baselineReleaseId ? `Release #${session.baselineReleaseId}` : 'None (first release)'}
                                </dd>

                                <dt className="col-sm-3">Pending Reviewers</dt>
                                <dd className="col-sm-9">{reviewerStats.pending}</dd>

                                <dt className="col-sm-3">In Progress</dt>
                                <dd className="col-sm-9">{reviewerStats.inProgress}</dd>
                            </dl>
                        </div>
                    </div>
                )}

                {activeTab === 'reviewers' && (
                    <div className="card">
                        <div className="table-responsive">
                            <table className="table table-hover mb-0">
                                <thead>
                                    <tr>
                                        <th>Reviewer</th>
                                        <th>Status</th>
                                        <th>Progress</th>
                                        <th>Assessments</th>
                                        <th>Last Activity</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {reviewers.map((reviewer) => (
                                        <tr key={reviewer.id}>
                                            <td>
                                                <strong>{reviewer.username}</strong>
                                                <br />
                                                <small className="text-muted">{reviewer.email}</small>
                                            </td>
                                            <td>
                                                <span className={`badge bg-${
                                                    reviewer.status === 'COMPLETED' ? 'success' :
                                                    reviewer.status === 'IN_PROGRESS' ? 'primary' : 'secondary'
                                                }`}>
                                                    {reviewer.status}
                                                </span>
                                            </td>
                                            <td>
                                                <div className="d-flex align-items-center gap-2">
                                                    <div className="progress flex-grow-1" style={{ height: '6px', minWidth: '80px' }}>
                                                        <div
                                                            className="progress-bar"
                                                            style={{ width: `${reviewer.completionPercent}%` }}
                                                        ></div>
                                                    </div>
                                                    <small>{reviewer.reviewedCount}/{reviewer.totalCount}</small>
                                                </div>
                                            </td>
                                            <td>
                                                <span className="text-success me-2">{reviewer.assessments.minor} Minor</span>
                                                <span className="text-warning me-2">{reviewer.assessments.major} Major</span>
                                                <span className="text-danger">{reviewer.assessments.nok} NOK</span>
                                            </td>
                                            <td>
                                                <small className="text-muted">
                                                    {reviewer.completedAt
                                                        ? `Completed ${new Date(reviewer.completedAt).toLocaleDateString()}`
                                                        : reviewer.startedAt
                                                        ? `Started ${new Date(reviewer.startedAt).toLocaleDateString()}`
                                                        : 'Not started'}
                                                </small>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </div>
                )}

                {activeTab === 'feedback' && (
                    <div className="card">
                        <div className="table-responsive">
                            <table className="table table-hover mb-0">
                                <thead>
                                    <tr>
                                        <th>Requirement</th>
                                        <th>Change</th>
                                        <th>Reviews</th>
                                        <th>Assessments</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {feedback.map((item: any) => (
                                        <tr key={item.snapshotId}>
                                            <td>
                                                <strong>{item.requirementId}</strong>
                                                <br />
                                                <small className="text-muted">{item.shortreq}</small>
                                            </td>
                                            <td>
                                                <span className={`badge bg-${
                                                    item.changeType === 'ADDED' ? 'success' :
                                                    item.changeType === 'DELETED' ? 'danger' : 'warning'
                                                }`}>
                                                    {item.changeType}
                                                </span>
                                            </td>
                                            <td>{item.reviewCount}</td>
                                            <td>
                                                <span className="text-success me-2">{item.assessments.minor} Minor</span>
                                                <span className="text-warning me-2">{item.assessments.major} Major</span>
                                                <span className="text-danger">{item.assessments.nok} NOK</span>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </div>
                )}
            </div>

            {/* Action Buttons */}
            {isOpen && canManage && (
                <div className="card mt-4">
                    <div className="card-body">
                        <h5 className="card-title">Finalize Alignment</h5>
                        {reviewerStats.pending > 0 && (
                            <div className="alert alert-warning mb-3">
                                <i className="bi bi-exclamation-triangle me-2"></i>
                                {reviewerStats.pending} reviewer(s) have not completed their review.
                                You can still finalize, but some feedback may be missing.
                            </div>
                        )}
                        <div className="d-flex gap-2">
                            <button
                                className="btn btn-success"
                                onClick={() => handleFinalize(true)}
                                disabled={actionLoading !== null}
                            >
                                {actionLoading === 'activate' ? (
                                    <span className="spinner-border spinner-border-sm me-2"></span>
                                ) : (
                                    <i className="bi bi-check-circle me-2"></i>
                                )}
                                Finalize & Activate Release
                            </button>
                            <button
                                className="btn btn-outline-primary"
                                onClick={() => handleFinalize(false)}
                                disabled={actionLoading !== null}
                            >
                                {actionLoading === 'finalize' ? (
                                    <span className="spinner-border spinner-border-sm me-2"></span>
                                ) : (
                                    <i className="bi bi-clipboard-check me-2"></i>
                                )}
                                Finalize (Keep as Draft)
                            </button>
                            <button
                                className="btn btn-outline-danger ms-auto"
                                onClick={handleCancel}
                                disabled={actionLoading !== null}
                            >
                                {actionLoading === 'cancel' ? (
                                    <span className="spinner-border spinner-border-sm me-2"></span>
                                ) : (
                                    <i className="bi bi-x-circle me-2"></i>
                                )}
                                Cancel Alignment
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default AlignmentDashboard;
