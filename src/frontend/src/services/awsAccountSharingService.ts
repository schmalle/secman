import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

/**
 * Service for AWS Account Sharing API operations.
 *
 * Feature: AWS Account Sharing (per-account scoping in V207)
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
    /** Effective number of accounts shared (full source set when shareAllAccounts, otherwise the selected count). */
    sharedAwsAccountCount: number;
    /** Explicit selection on this rule. Empty when shareAllAccounts is true. */
    selectedAwsAccountIds: string[];
    /** True when no per-account scoping is configured — rule shares ALL of source's accounts. */
    shareAllAccounts: boolean;
    createdAt: string;
    updatedAt: string;
}

export interface CreateAwsAccountSharingRequest {
    // Either *UserId or *UserEmail must be provided for each side.
    // Use email when selecting a "pending" user (one known only via
    // UserMapping who hasn't logged in yet) — the backend creates the
    // User record lazily so the sharing rule's FK is satisfied.
    sourceUserId?: number | null;
    sourceUserEmail?: string | null;
    targetUserId?: number | null;
    targetUserEmail?: string | null;
    /**
     * Optional. Empty/omitted → share ALL of the source's accounts.
     * Non-empty → restrict the share to the listed account IDs.
     */
    awsAccountIds?: string[] | null;
    /**
     * When true, the backend treats `targetUserEmail` as a brand-new
     * invite: must be well-formed, share the caller's domain, and not
     * already be an existing User or PENDING UserMapping. The created
     * user gets roles [USER, VULN] and an onboarding email.
     *
     * Default false; legacy callers are unaffected.
     */
    inviteByEmail?: boolean;
}

/**
 * Update payload for editing the per-account selection on an existing
 * sharing row. Source/target are immutable — to change them, delete and
 * recreate.
 */
export interface UpdateAwsAccountSharingRequest {
    /** Empty/null → reset to "share all"; non-empty → restrict to listed IDs. */
    awsAccountIds?: string[] | null;
}

/**
 * Entry in the sharing-form user dropdown. Returned by
 * GET /api/aws-account-sharing/users — includes both active users and
 * pending users (users recorded in UserMapping who have never logged in).
 */
export interface SharingUser {
    id: number | null;
    username: string;
    email: string;
    isPending: boolean;
}

/** AWS account belonging to a source user — populates the per-account picker. */
export interface SourceAwsAccount {
    awsAccountId: string;
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
        if (response.status === 403) throw new Error('Insufficient permissions');
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
        if (response.status === 403) throw new Error('Insufficient permissions');
        if (response.status === 404) throw new Error(errorData.message || 'User not found');
        throw new Error(errorData.message || `Failed to create sharing rule: ${response.status}`);
    }

    return await response.json();
}

/**
 * Update the per-account selection of an existing sharing rule.
 */
export async function updateSharingRule(
    id: number,
    request: UpdateAwsAccountSharingRequest
): Promise<AwsAccountSharing> {
    const response = await authenticatedPut(`/api/aws-account-sharing/${id}`, request);

    if (!response.ok) {
        const errorData = await response.json().catch(() => ({ message: 'Failed to update sharing rule' }));
        if (response.status === 400) throw new Error(errorData.message || 'Invalid request');
        if (response.status === 401) throw new Error('Authentication required');
        if (response.status === 403) throw new Error('Insufficient permissions');
        if (response.status === 404) throw new Error('Sharing rule not found');
        throw new Error(errorData.message || `Failed to update sharing rule: ${response.status}`);
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
        if (response.status === 403) throw new Error('Insufficient permissions');
        if (response.status === 404) throw new Error('Sharing rule not found');
        throw new Error(`Failed to delete sharing rule: ${response.status}`);
    }
}

/**
 * List the AWS account IDs available for sharing on behalf of a given
 * source user (by id, or by email for pending users with no User row yet).
 */
export async function listSourceAccounts(opts: { userId?: number | null; email?: string | null }):
    Promise<SourceAwsAccount[]> {
    const params = new URLSearchParams();
    if (opts.userId != null) params.set('userId', String(opts.userId));
    if (opts.email) params.set('email', opts.email);
    if ([...params.keys()].length === 0) {
        throw new Error('listSourceAccounts requires either userId or email');
    }

    const response = await authenticatedGet(`/api/aws-account-sharing/source-accounts?${params.toString()}`);
    if (!response.ok) {
        if (response.status === 401) throw new Error('Authentication required');
        if (response.status === 403) throw new Error('Insufficient permissions');
        throw new Error(`Failed to list source accounts: ${response.status}`);
    }
    return await response.json();
}
