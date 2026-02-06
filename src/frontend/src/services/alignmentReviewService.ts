/**
 * Alignment Review Service
 *
 * Handles public API calls for reviewers accessing via token link.
 * These endpoints don't require authentication - the token serves as auth.
 *
 * Feature: 068-requirements-alignment-process
 */

import type { ReviewPageData, AlignmentSnapshot } from './releaseService';

const API_BASE = import.meta.env.PUBLIC_API_URL || '';

export type ReviewAssessment = 'OK' | 'CHANGE' | 'NOGO';

export interface SubmitReviewRequest {
    snapshotId: number;
    assessment: ReviewAssessment;
    comment?: string;
}

export interface SubmitReviewResult {
    success: boolean;
    reviewId: number;
    assessment: string;
    message: string;
}

/**
 * Alignment Review Service for token-based reviewer access
 */
export const alignmentReviewService = {
    /**
     * Get review page data using token
     *
     * @param token Review token from email link
     * @returns Review page data including snapshots and existing reviews
     */
    async getReviewPageData(token: string): Promise<ReviewPageData> {
        const response = await fetch(`${API_BASE}/api/alignment/review/${token}`);

        if (!response.ok) {
            if (response.status === 404) {
                throw new Error('Invalid or expired review link');
            }
            throw new Error(`Failed to load review page: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Submit a review for a requirement
     *
     * @param token Review token
     * @param snapshotId ID of the requirement snapshot
     * @param assessment Assessment (OK, CHANGE, NOGO)
     * @param comment Optional comment
     * @returns Submit result
     */
    async submitReview(
        token: string,
        snapshotId: number,
        assessment: ReviewAssessment,
        comment?: string
    ): Promise<SubmitReviewResult> {
        const response = await fetch(`${API_BASE}/api/alignment/review/${token}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                snapshotId,
                assessment,
                comment,
            }),
        });

        if (!response.ok) {
            const errorData = await response.json().catch(() => ({}));
            throw new Error(errorData.message || `Failed to submit review: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Mark review as complete
     *
     * @param token Review token
     * @returns Completion result
     */
    async completeReview(token: string): Promise<{ success: boolean; message: string }> {
        const response = await fetch(`${API_BASE}/api/alignment/review/${token}/complete`, {
            method: 'POST',
        });

        if (!response.ok) {
            const errorData = await response.json().catch(() => ({}));
            throw new Error(errorData.message || `Failed to complete review: ${response.statusText}`);
        }

        return response.json();
    },
};
