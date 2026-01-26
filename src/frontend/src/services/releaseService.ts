/**
 * Release Service
 *
 * Handles API calls for release management functionality
 *
 * Related to: Feature 012-build-ui-for (Release Management UI Enhancement)
 * Backend APIs from: Feature 011-i-want-to (Release-Based Requirement Version Management)
 */

import { authenticatedFetch } from '../utils/auth';

export interface Release {
    id: number;
    version: string;
    name: string;
    description: string;
    status: 'DRAFT' | 'IN_REVIEW' | 'ACTIVE' | 'LEGACY';
    releaseDate: string | null;
    createdBy: string;
    createdAt: string;
    updatedAt: string;
    requirementCount: number;
}

// Feature 185: Requirements Alignment Process Types
export interface AlignmentSession {
    id: number;
    releaseId: number;
    releaseName: string;
    releaseVersion: string;
    status: 'OPEN' | 'COMPLETED' | 'CANCELLED';
    changedRequirementsCount: number;
    initiatedBy: string;
    baselineReleaseId: number | null;
    startedAt: string;
    completedAt: string | null;
    completionNotes: string | null;
}

export interface AlignmentReviewer {
    id: number;
    userId: number;
    username: string;
    email: string;
    status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED';
    reviewedCount: number;
    totalCount: number;
    completionPercent: number;
    assessments: AssessmentCounts;
    startedAt: string | null;
    completedAt: string | null;
    notifiedAt: string | null;
    reminderCount: number;
}

export interface AlignmentSnapshot {
    id: number;
    requirementId: string;
    changeType: 'ADDED' | 'MODIFIED' | 'DELETED';
    shortreq: string;
    previousShortreq: string | null;
    details: string | null;
    previousDetails: string | null;
    chapter: string | null;
    previousChapter: string | null;
    changeSummary: string;
    existingReview?: {
        id: number;
        assessment: 'MINOR' | 'MAJOR' | 'NOK';
        comment: string | null;
    } | null;
}

export interface AssessmentCounts {
    minor: number;
    major: number;
    nok: number;
}

export interface AlignmentStatus {
    session: AlignmentSession;
    reviewers: {
        total: number;
        completed: number;
        inProgress: number;
        pending: number;
        completionPercent: number;
    };
    requirements: {
        total: number;
        reviewed: number;
    };
    assessments: AssessmentCounts;
}

export interface AlignmentStartResult {
    success: boolean;
    message: string;
    session: AlignmentSession;
    reviewerCount: number;
    changedRequirements: number;
    changes: {
        added: number;
        modified: number;
        deleted: number;
    };
}

export interface ReviewPageData {
    reviewer: {
        id: number;
        username: string;
        status: string;
        reviewedCount: number;
    };
    session: {
        id: number;
        status: string;
        releaseName: string;
        releaseVersion: string;
        changedCount: number;
        startedAt: string;
    };
    snapshots: AlignmentSnapshot[];
    isOpen: boolean;
}

export interface RequirementSnapshot {
    id: number;
    releaseId: number;
    originalRequirementId: number;
    shortreq: string;
    chapter: string;
    norm: string;
    details: string;
    motivation: string;
    example: string;
    usecase: string;
    usecaseIdsSnapshot: string; // JSON array
    normIdsSnapshot: string; // JSON array
    snapshotTimestamp: string;
}

export interface ReleaseComparison {
    fromRelease: Release;
    toRelease: Release;
    added: RequirementSnapshot[];
    deleted: RequirementSnapshot[];
    modified: ModifiedRequirement[];
}

export interface ModifiedRequirement {
    requirementId: number;
    fromSnapshot: RequirementSnapshot;
    toSnapshot: RequirementSnapshot;
    fieldChanges: FieldChange[];
}

export interface FieldChange {
    fieldName: string;
    oldValue: string;
    newValue: string;
}

export interface PaginatedResponse<T> {
    data: T[];
    currentPage: number;
    totalPages: number;
    totalItems: number;
    pageSize: number;
}

export interface CreateReleaseRequest {
    version: string;
    name: string;
    description?: string;
}

/**
 * Release Service API wrapper
 */
export const releaseService = {
    /**
     * List all releases with optional filtering and pagination
     *
     * @param status Optional status filter (DRAFT, PUBLISHED, ARCHIVED)
     * @param page Page number (1-indexed)
     * @param pageSize Number of items per page
     * @returns Paginated list of releases
     */
    async list(
        status?: string,
        page: number = 1,
        pageSize: number = 20
    ): Promise<PaginatedResponse<Release>> {
        const params = new URLSearchParams();
        if (status && status !== 'ALL') {
            params.append('status', status);
        }
        params.append('page', page.toString());
        params.append('pageSize', pageSize.toString());

        const response = await authenticatedFetch(`/api/releases?${params}`);
        
        if (!response.ok) {
            throw new Error(`Failed to list releases: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Get release by ID
     *
     * @param id Release ID
     * @returns Release details
     */
    async getById(id: number): Promise<Release> {
        const response = await authenticatedFetch(`/api/releases/${id}`);
        
        if (!response.ok) {
            if (response.status === 404) {
                throw new Error('Release not found');
            }
            throw new Error(`Failed to get release: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Create a new release
     *
     * @param data Release creation data
     * @returns Created release (with DRAFT status)
     */
    async create(data: CreateReleaseRequest): Promise<Release> {
        const response = await authenticatedFetch('/api/releases', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(data),
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || `Failed to create release: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Update release status
     *
     * @param id Release ID
     * @param status New status (PUBLISHED or ARCHIVED)
     * @returns Updated release
     */
    async updateStatus(id: number, status: 'ACTIVE'): Promise<Release> {
        const response = await authenticatedFetch(`/api/releases/${id}/status`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ status }),
        });

        if (!response.ok) {
            throw new Error(`Failed to update release status: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Delete release
     *
     * @param id Release ID
     */
    async delete(id: number): Promise<void> {
        const response = await authenticatedFetch(`/api/releases/${id}`, {
            method: 'DELETE',
        });

        if (!response.ok) {
            if (response.status === 403) {
                throw new Error('You do not have permission to delete this release.');
            }
            throw new Error(`Failed to delete release: ${response.statusText}`);
        }
    },

    /**
     * Get requirement snapshots for a release
     *
     * @param id Release ID
     * @param page Page number (1-indexed)
     * @param pageSize Number of items per page
     * @returns Paginated list of requirement snapshots
     */
    async getSnapshots(
        id: number,
        page: number = 1,
        pageSize: number = 50
    ): Promise<PaginatedResponse<RequirementSnapshot>> {
        const params = new URLSearchParams();
        params.append('page', page.toString());
        params.append('pageSize', pageSize.toString());

        const response = await authenticatedFetch(`/api/releases/${id}/requirements?${params}`);
        
        if (!response.ok) {
            throw new Error(`Failed to get release snapshots: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Compare two releases
     *
     * @param fromReleaseId Source release ID
     * @param toReleaseId Target release ID
     * @returns Comparison results
     */
    async compare(fromReleaseId: number, toReleaseId: number): Promise<ReleaseComparison> {
        const params = new URLSearchParams();
        params.append('fromReleaseId', fromReleaseId.toString());
        params.append('toReleaseId', toReleaseId.toString());

        const response = await authenticatedFetch(`/api/releases/compare?${params}`);

        if (!response.ok) {
            throw new Error(`Failed to compare releases: ${response.statusText}`);
        }

        return response.json();
    },

    // ========== Feature 185: Requirements Alignment Process ==========

    /**
     * Check if a release has changes that need alignment review
     *
     * @param releaseId Release ID
     * @returns Whether the release has changes to review
     */
    async checkAlignmentRequired(releaseId: number): Promise<{ hasChanges: boolean }> {
        const response = await authenticatedFetch(`/api/releases/${releaseId}/alignment/check`);

        if (!response.ok) {
            throw new Error(`Failed to check alignment status: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Start alignment process for a DRAFT release
     *
     * @param releaseId Release ID
     * @returns Alignment start result with session details
     */
    async startAlignment(releaseId: number): Promise<AlignmentStartResult> {
        const response = await authenticatedFetch(`/api/releases/${releaseId}/alignment/start`, {
            method: 'POST',
        });

        if (!response.ok) {
            const errorData = await response.json().catch(() => ({}));
            throw new Error(errorData.message || `Failed to start alignment: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Get alignment status for a release
     *
     * @param releaseId Release ID
     * @returns Alignment status including reviewer progress
     */
    async getAlignmentStatus(releaseId: number): Promise<AlignmentStatus> {
        const response = await authenticatedFetch(`/api/releases/${releaseId}/alignment`);

        if (!response.ok) {
            if (response.status === 404) {
                throw new Error('No active alignment session for this release');
            }
            throw new Error(`Failed to get alignment status: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Get alignment session details by session ID
     *
     * @param sessionId Alignment session ID
     * @returns Alignment status
     */
    async getAlignmentSessionStatus(sessionId: number): Promise<AlignmentStatus> {
        const response = await authenticatedFetch(`/api/alignment/${sessionId}`);

        if (!response.ok) {
            throw new Error(`Failed to get alignment session: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Get alignment snapshots (changed requirements)
     *
     * @param sessionId Alignment session ID
     * @param changeType Optional filter by change type
     * @returns List of requirement changes
     */
    async getAlignmentSnapshots(
        sessionId: number,
        changeType?: 'ADDED' | 'MODIFIED' | 'DELETED'
    ): Promise<{ snapshots: AlignmentSnapshot[]; totalCount: number }> {
        const params = new URLSearchParams();
        if (changeType) {
            params.append('changeType', changeType);
        }

        const response = await authenticatedFetch(`/api/alignment/${sessionId}/snapshots?${params}`);

        if (!response.ok) {
            throw new Error(`Failed to get alignment snapshots: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Get reviewer details for an alignment session
     *
     * @param sessionId Alignment session ID
     * @returns List of reviewers with their progress
     */
    async getAlignmentReviewers(sessionId: number): Promise<{ reviewers: AlignmentReviewer[] }> {
        const response = await authenticatedFetch(`/api/alignment/${sessionId}/reviewers`);

        if (!response.ok) {
            throw new Error(`Failed to get alignment reviewers: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Get aggregated feedback for all requirements
     *
     * @param sessionId Alignment session ID
     * @returns Per-requirement feedback summary
     */
    async getAlignmentFeedback(sessionId: number): Promise<any> {
        const response = await authenticatedFetch(`/api/alignment/${sessionId}/feedback`);

        if (!response.ok) {
            throw new Error(`Failed to get alignment feedback: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Send reminder emails to incomplete reviewers
     *
     * @param sessionId Alignment session ID
     * @returns Reminder send result
     */
    async sendAlignmentReminders(sessionId: number): Promise<{ success: boolean; remindersSent: number }> {
        const response = await authenticatedFetch(`/api/alignment/${sessionId}/remind`, {
            method: 'POST',
        });

        if (!response.ok) {
            throw new Error(`Failed to send reminders: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Finalize alignment and optionally activate release
     *
     * @param sessionId Alignment session ID
     * @param activateRelease Whether to activate the release
     * @param notes Optional completion notes
     * @returns Finalization result
     */
    async finalizeAlignment(
        sessionId: number,
        activateRelease: boolean,
        notes?: string
    ): Promise<{ success: boolean; message: string; session: AlignmentSession }> {
        const response = await authenticatedFetch(`/api/alignment/${sessionId}/finalize`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ activateRelease, notes }),
        });

        if (!response.ok) {
            const errorData = await response.json().catch(() => ({}));
            throw new Error(errorData.message || `Failed to finalize alignment: ${response.statusText}`);
        }

        return response.json();
    },

    /**
     * Cancel alignment and return release to DRAFT
     *
     * @param sessionId Alignment session ID
     * @param notes Optional cancellation notes
     * @returns Cancellation result
     */
    async cancelAlignment(
        sessionId: number,
        notes?: string
    ): Promise<{ success: boolean; message: string; session: AlignmentSession }> {
        const response = await authenticatedFetch(`/api/alignment/${sessionId}/cancel`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ notes }),
        });

        if (!response.ok) {
            const errorData = await response.json().catch(() => ({}));
            throw new Error(errorData.message || `Failed to cancel alignment: ${response.statusText}`);
        }

        return response.json();
    },
};
