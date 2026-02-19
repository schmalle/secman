import { authenticatedGet, authenticatedPost, authenticatedDelete } from '../utils/auth';

/**
 * Service for AWS Account Sharing API operations.
 *
 * Feature: AWS Account Sharing
 */

export interface AwsAccountSharing {
    id: number;
    sourceUserId: number;
    sourceUserEmail: string;
    sourceUserUsername: string;
    targetUserId: number;
    targetUserEmail: string;
    targetUserUsername: string;
    createdById: number;
    createdByUsername: string;
    sharedAwsAccountCount: number;
    createdAt: string;
    updatedAt: string;
}

export interface CreateAwsAccountSharingRequest {
    sourceUserId: number;
    targetUserId: number;
}

export interface AwsAccountSharingListResponse {
    content: AwsAccountSharing[];
    totalElements: number;
    totalPages: number;
    page: number;
    size: number;
}

/**
 * List AWS account sharing rules with pagination.
 */
export async function listSharingRules(
    page: number = 0,
    size: number = 20
): Promise<AwsAccountSharingListResponse> {
    const params = new URLSearchParams({
        page: page.toString(),
        size: size.toString()
    });

    const response = await authenticatedGet(`/api/aws-account-sharing?${params.toString()}`);

    if (!response.ok) {
        if (response.status === 401) throw new Error('Authentication required');
        if (response.status === 403) throw new Error('Insufficient permissions (ADMIN role required)');
        throw new Error(`Failed to list sharing rules: ${response.status}`);
    }

    return await response.json();
}

/**
 * Create a new AWS account sharing rule.
 */
export async function createSharingRule(
    request: CreateAwsAccountSharingRequest
): Promise<AwsAccountSharing> {
    const response = await authenticatedPost('/api/aws-account-sharing', request);

    if (!response.ok) {
        const errorData = await response.json().catch(() => ({ message: 'Failed to create sharing rule' }));
        if (response.status === 400) throw new Error(errorData.message || 'Invalid request');
        if (response.status === 401) throw new Error('Authentication required');
        if (response.status === 403) throw new Error('Insufficient permissions (ADMIN role required)');
        if (response.status === 404) throw new Error(errorData.message || 'User not found');
        throw new Error(errorData.message || `Failed to create sharing rule: ${response.status}`);
    }

    return await response.json();
}

/**
 * Delete an AWS account sharing rule.
 */
export async function deleteSharingRule(id: number): Promise<void> {
    const response = await authenticatedDelete(`/api/aws-account-sharing/${id}`);

    if (!response.ok) {
        if (response.status === 401) throw new Error('Authentication required');
        if (response.status === 403) throw new Error('Insufficient permissions (ADMIN role required)');
        if (response.status === 404) throw new Error('Sharing rule not found');
        throw new Error(`Failed to delete sharing rule: ${response.status}`);
    }
}
