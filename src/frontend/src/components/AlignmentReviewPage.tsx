/**
 * AlignmentReviewPage Component
 *
 * Review page for users accessing via email token link.
 * Allows reviewers to assess requirement changes (MINOR/MAJOR/NOK).
 *
 * Feature: 068-requirements-alignment-process
 */

import React, { useState, useEffect } from 'react';
import {
    alignmentReviewService,
    type ReviewAssessment,
} from '../services/alignmentReviewService';
import type { ReviewPageData, AlignmentSnapshot } from '../services/releaseService';

interface AlignmentReviewPageProps {
    token: string;
}

export const AlignmentReviewPage: React.FC<AlignmentReviewPageProps> = ({ token }) => {
    const [pageData, setPageData] = useState<ReviewPageData | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [savingSnapshot, setSavingSnapshot] = useState<number | null>(null);
    const [filter, setFilter] = useState<'all' | 'ADDED' | 'MODIFIED' | 'DELETED' | 'pending'>('all');
    const [expandedSnapshot, setExpandedSnapshot] = useState<number | null>(null);

    // Track local reviews for optimistic updates
    const [localReviews, setLocalReviews] = useState<Map<number, { assessment: ReviewAssessment; comment: string | null }>>(new Map());

    useEffect(() => {
        loadData();
    }, [token]);

    const loadData = async () => {
        setIsLoading(true);
        setError(null);

        try {
            const data = await alignmentReviewService.getReviewPageData(token);
            setPageData(data);

            // Initialize local reviews from existing data
            const reviews = new Map<number, { assessment: ReviewAssessment; comment: string | null }>();
            data.snapshots.forEach((snapshot) => {
                if (snapshot.existingReview) {
                    reviews.set(snapshot.id, {
                        assessment: snapshot.existingReview.assessment,
                        comment: snapshot.existingReview.comment,
                    });
                }
            });
            setLocalReviews(reviews);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to load review page');
        } finally {
            setIsLoading(false);
        }
    };

    const handleSubmitReview = async (
        snapshotId: number,
        assessment: ReviewAssessment,
        comment?: string
    ) => {
        setSavingSnapshot(snapshotId);

        try {
            await alignmentReviewService.submitReview(token, snapshotId, assessment, comment);

            // Update local state
            setLocalReviews((prev) => {
                const next = new Map(prev);
                next.set(snapshotId, { assessment, comment: comment || null });
                return next;
            });
        } catch (err) {
            alert(err instanceof Error ? err.message : 'Failed to submit review');
        } finally {
            setSavingSnapshot(null);
        }
    };

    const handleCompleteReview = async () => {
        if (!confirm('Are you sure you want to mark your review as complete? You can still update individual assessments later.')) {
            return;
        }

        try {
            await alignmentReviewService.completeReview(token);
            alert('Review marked as complete. Thank you!');
            loadData();
        } catch (err) {
            alert(err instanceof Error ? err.message : 'Failed to complete review');
        }
    };

    const getFilteredSnapshots = (): AlignmentSnapshot[] => {
        if (!pageData) return [];

        return pageData.snapshots.filter((snapshot) => {
            if (filter === 'all') return true;
            if (filter === 'pending') return !localReviews.has(snapshot.id);
            return snapshot.changeType === filter;
        });
    };

    if (isLoading) {
        return (
            <div className="d-flex justify-content-center align-items-center min-vh-100">
                <div className="text-center">
                    <div className="spinner-border text-primary mb-3" role="status">
                        <span className="visually-hidden">Loading...</span>
                    </div>
                    <p className="text-muted">Loading review page...</p>
                </div>
            </div>
        );
    }

    if (error || !pageData) {
        return (
            <div className="container py-5">
                <div className="alert alert-danger">
                    <h4><i className="bi bi-exclamation-triangle me-2"></i>Error</h4>
                    <p className="mb-0">{error || 'Failed to load review page'}</p>
                </div>
            </div>
        );
    }

    const { reviewer, session, snapshots, isOpen } = pageData;
    const reviewedCount = localReviews.size;
    const totalCount = snapshots.length;
    const progressPercent = totalCount > 0 ? Math.round((reviewedCount / totalCount) * 100) : 0;
    const filteredSnapshots = getFilteredSnapshots();

    return (
        <div className="alignment-review-page bg-light min-vh-100">
            {/* Header */}
            <div className="bg-primary text-white py-4 mb-4">
                <div className="container">
                    <h1 className="h3 mb-1">
                        <i className="bi bi-clipboard-check me-2"></i>
                        Requirements Review
                    </h1>
                    <p className="mb-0 opacity-75">
                        {session.releaseName} v{session.releaseVersion}
                    </p>
                </div>
            </div>

            <div className="container pb-5">
                {/* Status Card */}
                <div className="card mb-4 shadow-sm">
                    <div className="card-body">
                        <div className="row align-items-center">
                            <div className="col-md-4">
                                <h5 className="mb-1">Welcome, {reviewer.username}</h5>
                                <p className="text-muted mb-0 small">
                                    Status: <span className={`badge bg-${
                                        reviewer.status === 'COMPLETED' ? 'success' :
                                        reviewer.status === 'IN_PROGRESS' ? 'primary' : 'secondary'
                                    }`}>{reviewer.status}</span>
                                </p>
                            </div>
                            <div className="col-md-5">
                                <div className="d-flex align-items-center gap-2">
                                    <div className="progress flex-grow-1" style={{ height: '10px' }}>
                                        <div
                                            className="progress-bar bg-success"
                                            style={{ width: `${progressPercent}%` }}
                                        ></div>
                                    </div>
                                    <span className="text-muted">
                                        {reviewedCount}/{totalCount} reviewed
                                    </span>
                                </div>
                            </div>
                            <div className="col-md-3 text-end">
                                {isOpen && reviewer.status !== 'COMPLETED' && (
                                    <button
                                        className="btn btn-success"
                                        onClick={handleCompleteReview}
                                        disabled={reviewedCount === 0}
                                    >
                                        <i className="bi bi-check-circle me-2"></i>
                                        Mark Complete
                                    </button>
                                )}
                                {!isOpen && (
                                    <span className="badge bg-secondary">Review Closed</span>
                                )}
                            </div>
                        </div>
                    </div>
                </div>

                {/* Filter Bar */}
                <div className="d-flex justify-content-between align-items-center mb-3">
                    <div className="btn-group">
                        <button
                            className={`btn btn-sm ${filter === 'all' ? 'btn-primary' : 'btn-outline-primary'}`}
                            onClick={() => setFilter('all')}
                        >
                            All ({snapshots.length})
                        </button>
                        <button
                            className={`btn btn-sm ${filter === 'pending' ? 'btn-primary' : 'btn-outline-primary'}`}
                            onClick={() => setFilter('pending')}
                        >
                            Pending ({snapshots.length - reviewedCount})
                        </button>
                        <button
                            className={`btn btn-sm ${filter === 'ADDED' ? 'btn-success' : 'btn-outline-success'}`}
                            onClick={() => setFilter('ADDED')}
                        >
                            Added
                        </button>
                        <button
                            className={`btn btn-sm ${filter === 'MODIFIED' ? 'btn-warning' : 'btn-outline-warning'}`}
                            onClick={() => setFilter('MODIFIED')}
                        >
                            Modified
                        </button>
                        <button
                            className={`btn btn-sm ${filter === 'DELETED' ? 'btn-danger' : 'btn-outline-danger'}`}
                            onClick={() => setFilter('DELETED')}
                        >
                            Deleted
                        </button>
                    </div>
                    <span className="text-muted">{filteredSnapshots.length} items</span>
                </div>

                {/* Requirements List */}
                <div className="requirements-list">
                    {filteredSnapshots.map((snapshot) => (
                        <RequirementCard
                            key={snapshot.id}
                            snapshot={snapshot}
                            review={localReviews.get(snapshot.id)}
                            isExpanded={expandedSnapshot === snapshot.id}
                            isSaving={savingSnapshot === snapshot.id}
                            isOpen={isOpen}
                            onToggleExpand={() => setExpandedSnapshot(
                                expandedSnapshot === snapshot.id ? null : snapshot.id
                            )}
                            onSubmitReview={(assessment, comment) =>
                                handleSubmitReview(snapshot.id, assessment, comment)
                            }
                        />
                    ))}

                    {filteredSnapshots.length === 0 && (
                        <div className="text-center py-5 text-muted">
                            <i className="bi bi-check-circle display-4 mb-3"></i>
                            <p>No requirements match the current filter.</p>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};

// Requirement Card Sub-component
interface RequirementCardProps {
    snapshot: AlignmentSnapshot;
    review?: { assessment: ReviewAssessment; comment: string | null };
    isExpanded: boolean;
    isSaving: boolean;
    isOpen: boolean;
    onToggleExpand: () => void;
    onSubmitReview: (assessment: ReviewAssessment, comment?: string) => void;
}

const RequirementCard: React.FC<RequirementCardProps> = ({
    snapshot,
    review,
    isExpanded,
    isSaving,
    isOpen,
    onToggleExpand,
    onSubmitReview,
}) => {
    const [comment, setComment] = useState(review?.comment || '');
    const [selectedAssessment, setSelectedAssessment] = useState<ReviewAssessment | null>(
        review?.assessment || null
    );

    const handleSave = () => {
        if (!selectedAssessment) return;
        onSubmitReview(selectedAssessment, comment || undefined);
    };

    const getChangeTypeBadge = () => {
        switch (snapshot.changeType) {
            case 'ADDED':
                return <span className="badge bg-success">Added</span>;
            case 'DELETED':
                return <span className="badge bg-danger">Deleted</span>;
            case 'MODIFIED':
                return <span className="badge bg-warning text-dark">Modified</span>;
        }
    };

    const getAssessmentBadge = () => {
        if (!review) return null;
        switch (review.assessment) {
            case 'MINOR':
                return <span className="badge bg-success">Minor</span>;
            case 'MAJOR':
                return <span className="badge bg-warning text-dark">Major</span>;
            case 'NOK':
                return <span className="badge bg-danger">NOK</span>;
        }
    };

    return (
        <div className={`card mb-3 shadow-sm ${review ? 'border-start border-4 border-success' : ''}`}>
            <div
                className="card-header bg-white d-flex justify-content-between align-items-center"
                style={{ cursor: 'pointer' }}
                onClick={onToggleExpand}
            >
                <div className="d-flex align-items-center gap-2">
                    {getChangeTypeBadge()}
                    <strong>{snapshot.requirementId}</strong>
                    {getAssessmentBadge()}
                </div>
                <i className={`bi bi-chevron-${isExpanded ? 'up' : 'down'}`}></i>
            </div>

            <div className="card-body">
                <p className="mb-2">{snapshot.shortreq}</p>

                {isExpanded && (
                    <div className="mt-3">
                        {/* Diff View for Modified */}
                        {snapshot.changeType === 'MODIFIED' && snapshot.previousShortreq && (
                            <div className="mb-3">
                                <h6 className="text-muted small">Changes:</h6>
                                <div className="bg-danger bg-opacity-10 p-2 rounded mb-2">
                                    <small className="text-danger">
                                        <i className="bi bi-dash-circle me-1"></i>
                                        {snapshot.previousShortreq}
                                    </small>
                                </div>
                                <div className="bg-success bg-opacity-10 p-2 rounded">
                                    <small className="text-success">
                                        <i className="bi bi-plus-circle me-1"></i>
                                        {snapshot.shortreq}
                                    </small>
                                </div>
                            </div>
                        )}

                        {/* Details if available */}
                        {snapshot.details && (
                            <div className="mb-3">
                                <h6 className="text-muted small">Details:</h6>
                                <p className="small mb-0 bg-light p-2 rounded">{snapshot.details}</p>
                            </div>
                        )}

                        {/* Assessment Buttons */}
                        {isOpen && (
                            <div className="mt-3 pt-3 border-top">
                                <h6 className="mb-2">Your Assessment</h6>
                                <div className="d-flex gap-2 mb-3">
                                    <button
                                        className={`btn ${selectedAssessment === 'MINOR' ? 'btn-success' : 'btn-outline-success'}`}
                                        onClick={() => setSelectedAssessment('MINOR')}
                                        disabled={isSaving}
                                    >
                                        <i className="bi bi-check me-1"></i>
                                        Minor
                                    </button>
                                    <button
                                        className={`btn ${selectedAssessment === 'MAJOR' ? 'btn-warning' : 'btn-outline-warning'}`}
                                        onClick={() => setSelectedAssessment('MAJOR')}
                                        disabled={isSaving}
                                    >
                                        <i className="bi bi-exclamation-triangle me-1"></i>
                                        Major
                                    </button>
                                    <button
                                        className={`btn ${selectedAssessment === 'NOK' ? 'btn-danger' : 'btn-outline-danger'}`}
                                        onClick={() => setSelectedAssessment('NOK')}
                                        disabled={isSaving}
                                    >
                                        <i className="bi bi-x-circle me-1"></i>
                                        NOK
                                    </button>
                                </div>

                                <div className="mb-3">
                                    <textarea
                                        className="form-control"
                                        rows={2}
                                        placeholder="Optional comment..."
                                        value={comment}
                                        onChange={(e) => setComment(e.target.value)}
                                        disabled={isSaving}
                                    />
                                </div>

                                <button
                                    className="btn btn-primary"
                                    onClick={handleSave}
                                    disabled={!selectedAssessment || isSaving}
                                >
                                    {isSaving ? (
                                        <>
                                            <span className="spinner-border spinner-border-sm me-2"></span>
                                            Saving...
                                        </>
                                    ) : (
                                        <>
                                            <i className="bi bi-save me-2"></i>
                                            Save Assessment
                                        </>
                                    )}
                                </button>
                            </div>
                        )}
                    </div>
                )}
            </div>
        </div>
    );
};

export default AlignmentReviewPage;
