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
    status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
    releaseDate: string | null;
    createdBy: string;
    createdAt: string;
    updatedAt: string;
    requirementCount: number;
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
    async updateStatus(id: number, status: 'PUBLISHED' | 'ARCHIVED'): Promise<Release> {
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
};
