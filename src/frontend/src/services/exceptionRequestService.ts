/**
 * Exception Request Service
 *
 * Handles API calls for vulnerability exception request management
 *
 * Feature: 031-vuln-exception-approval
 * User Story 1: Regular User Requests Exception (P1)
 */

import { authenticatedGet, authenticatedPost, authenticatedDelete } from '../utils/auth';

/**
 * Exception request status values
 */
export type ExceptionRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED';

/**
 * Exception scope values
 */
export type ExceptionScope = 'SINGLE_VULNERABILITY' | 'CVE_PATTERN';

/**
 * DTO for creating an exception request
 */
export interface CreateExceptionRequestDto {
  vulnerabilityId: number;
  scope: ExceptionScope;
  reason: string;
  expirationDate: string; // ISO 8601 datetime string
}

/**
 * Full exception request DTO from API
 */
export interface VulnerabilityExceptionRequestDto {
  id: number;
  vulnerabilityId: number;
  vulnerabilityCveId: string | null;
  assetId: number;
  assetName: string;
  assetIp: string | null;
  requestedByUserId: number;
  requestedByUsername: string;
  scope: ExceptionScope;
  reason: string;
  expirationDate: string;
  status: ExceptionRequestStatus;
  autoApproved: boolean;
  reviewedByUserId: number | null;
  reviewedByUsername: string | null;
  reviewDate: string | null;
  reviewComment: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

/**
 * Summary statistics for user's exception requests
 */
export interface ExceptionRequestSummaryDto {
  totalRequests: number;
  pendingCount: number;
  approvedCount: number;
  rejectedCount: number;
  expiredCount: number;
  cancelledCount: number;
}

/**
 * Paginated response wrapper
 */
export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

/**
 * Create a new exception request
 *
 * @param dto Request data with vulnerability ID, scope, reason, and expiration date
 * @returns Created exception request
 * @throws Error on 400 (validation), 401 (unauthorized), 409 (duplicate), 500 (server error)
 */
export async function createRequest(dto: CreateExceptionRequestDto): Promise<VulnerabilityExceptionRequestDto> {
  const response = await authenticatedPost('/api/vulnerability-exception-requests', dto);

  if (response.ok) {
    return await response.json();
  }

  // Handle error responses
  if (response.status === 400) {
    const error = await response.json();
    throw new Error(error.error || 'Invalid request data. Check reason length (50-2048 chars) and expiration date (must be in future).');
  }

  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 404) {
    const error = await response.json();
    throw new Error(error.error || 'Vulnerability not found.');
  }

  if (response.status === 409) {
    const error = await response.json();
    throw new Error(error.error || 'An active exception request already exists for this vulnerability.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to create exception request.');
  }

  throw new Error(`Failed to create exception request: ${response.status}`);
}

/**
 * Get current user's exception requests with optional filtering and pagination
 *
 * @param status Optional status filter (PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED)
 * @param page Page number (0-indexed)
 * @param size Page size (20, 50, or 100)
 * @returns Paginated list of exception requests
 * @throws Error on 401 (unauthorized), 500 (server error)
 */
export async function getMyRequests(
  status?: ExceptionRequestStatus,
  page: number = 0,
  size: number = 20
): Promise<PagedResponse<VulnerabilityExceptionRequestDto>> {
  const params = new URLSearchParams();
  if (status) {
    params.append('status', status);
  }
  params.append('page', page.toString());
  params.append('size', size.toString());

  const url = `/api/vulnerability-exception-requests/my?${params.toString()}`;
  const response = await authenticatedGet(url);

  if (response.ok) {
    return await response.json();
  }

  // Handle error responses
  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to fetch exception requests.');
  }

  throw new Error(`Failed to fetch exception requests: ${response.status}`);
}

/**
 * Get summary statistics for current user's exception requests
 *
 * @returns Summary with counts by status
 * @throws Error on 401 (unauthorized), 500 (server error)
 */
export async function getMySummary(): Promise<ExceptionRequestSummaryDto> {
  const response = await authenticatedGet('/api/vulnerability-exception-requests/my/summary');

  if (response.ok) {
    return await response.json();
  }

  // Handle error responses
  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to fetch request summary.');
  }

  throw new Error(`Failed to fetch request summary: ${response.status}`);
}

/**
 * Get exception request by ID
 *
 * @param id Request ID
 * @returns Exception request details
 * @throws Error on 401 (unauthorized), 403 (forbidden), 404 (not found), 500 (server error)
 */
export async function getRequestById(id: number): Promise<VulnerabilityExceptionRequestDto> {
  const response = await authenticatedGet(`/api/vulnerability-exception-requests/${id}`);

  if (response.ok) {
    return await response.json();
  }

  // Handle error responses
  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 403) {
    throw new Error('You do not have permission to view this request.');
  }

  if (response.status === 404) {
    throw new Error('Exception request not found.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to fetch exception request.');
  }

  throw new Error(`Failed to fetch exception request: ${response.status}`);
}

/**
 * Cancel an exception request (requester only, PENDING status only)
 *
 * @param id Request ID
 * @returns void on success
 * @throws Error on 400 (invalid status), 401 (unauthorized), 403 (not owner), 404 (not found), 500 (server error)
 */
export async function cancelRequest(id: number): Promise<void> {
  const response = await authenticatedDelete(`/api/vulnerability-exception-requests/${id}`);

  if (response.status === 204) {
    return; // Success - no content
  }

  // Handle error responses
  if (response.status === 400) {
    const error = await response.json();
    throw new Error(error.error || 'Cannot cancel this request. Only PENDING requests can be cancelled.');
  }

  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 403) {
    throw new Error('You do not have permission to cancel this request. Only the requester can cancel their own requests.');
  }

  if (response.status === 404) {
    throw new Error('Exception request not found.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to cancel exception request.');
  }

  throw new Error(`Failed to cancel exception request: ${response.status}`);
}

/**
 * Get pending exception requests (ADMIN/SECCHAMPION only)
 *
 * @param page Page number (0-indexed)
 * @param size Page size (20, 50, or 100)
 * @returns Paginated list of pending requests sorted by oldest first
 * @throws Error on 401 (unauthorized), 403 (forbidden), 500 (server error)
 */
export async function getPendingRequests(
  page: number = 0,
  size: number = 20
): Promise<PagedResponse<VulnerabilityExceptionRequestDto>> {
  const params = new URLSearchParams();
  params.append('page', page.toString());
  params.append('size', size.toString());

  const url = `/api/vulnerability-exception-requests/pending?${params.toString()}`;
  const response = await authenticatedGet(url);

  if (response.ok) {
    return await response.json();
  }

  // Handle error responses
  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 403) {
    throw new Error('Access denied. This feature requires ADMIN or SECCHAMPION role.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to fetch pending requests.');
  }

  throw new Error(`Failed to fetch pending requests: ${response.status}`);
}

/**
 * Get count of pending exception requests (ADMIN/SECCHAMPION only)
 *
 * @returns Count of pending requests
 * @throws Error on 401 (unauthorized), 403 (forbidden), 500 (server error)
 */
export async function getPendingCount(): Promise<number> {
  const response = await authenticatedGet('/api/vulnerability-exception-requests/pending/count');

  if (response.ok) {
    const data = await response.json();
    return data.count || 0;
  }

  // Handle error responses
  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 403) {
    throw new Error('Access denied. This feature requires ADMIN or SECCHAMPION role.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to fetch pending count.');
  }

  throw new Error(`Failed to fetch pending count: ${response.status}`);
}

/**
 * DTO for review action (approve/reject)
 */
export interface ReviewExceptionRequestDto {
  comment?: string;
}

/**
 * Approve an exception request (ADMIN/SECCHAMPION only)
 *
 * @param id Request ID
 * @param comment Optional approval comment
 * @returns Updated exception request
 * @throws Error on 400 (invalid state), 401 (unauthorized), 403 (forbidden), 404 (not found), 409 (concurrent approval), 500 (server error)
 */
export async function approveRequest(id: number, comment?: string): Promise<VulnerabilityExceptionRequestDto> {
  const dto: ReviewExceptionRequestDto = comment ? { comment } : {};
  const response = await authenticatedPost(`/api/vulnerability-exception-requests/${id}/approve`, dto);

  if (response.ok) {
    return await response.json();
  }

  // Handle error responses
  if (response.status === 400) {
    const error = await response.json();
    throw new Error(error.error || 'Cannot approve this request. Invalid status transition.');
  }

  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 403) {
    throw new Error('Access denied. This feature requires ADMIN or SECCHAMPION role.');
  }

  if (response.status === 404) {
    throw new Error('Exception request not found.');
  }

  if (response.status === 409) {
    const error = await response.json();
    throw new Error(error.error || 'This request was just reviewed by another user. Please refresh.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to approve exception request.');
  }

  throw new Error(`Failed to approve exception request: ${response.status}`);
}

/**
 * Reject an exception request (ADMIN/SECCHAMPION only)
 *
 * @param id Request ID
 * @param comment Required rejection comment (10-1024 characters)
 * @returns Updated exception request
 * @throws Error on 400 (invalid state/comment), 401 (unauthorized), 403 (forbidden), 404 (not found), 409 (concurrent rejection), 500 (server error)
 */
export async function rejectRequest(id: number, comment: string): Promise<VulnerabilityExceptionRequestDto> {
  const dto: ReviewExceptionRequestDto = { comment };
  const response = await authenticatedPost(`/api/vulnerability-exception-requests/${id}/reject`, dto);

  if (response.ok) {
    return await response.json();
  }

  // Handle error responses
  if (response.status === 400) {
    const error = await response.json();
    throw new Error(error.error || 'Cannot reject this request. Ensure comment is 10-1024 characters.');
  }

  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 403) {
    throw new Error('Access denied. This feature requires ADMIN or SECCHAMPION role.');
  }

  if (response.status === 404) {
    throw new Error('Exception request not found.');
  }

  if (response.status === 409) {
    const error = await response.json();
    throw new Error(error.error || 'This request was just reviewed by another user. Please refresh.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to reject exception request.');
  }

  throw new Error(`Failed to reject exception request: ${response.status}`);
}

/**
 * Top requester entry for statistics
 */
export interface TopRequesterDto {
  username: string;
  count: number;
}

/**
 * Top CVE entry for statistics
 */
export interface TopCVEDto {
  cveId: string;
  count: number;
}

/**
 * Statistics response for exception requests
 */
export interface ExceptionStatisticsDto {
  totalRequests: number;
  approvalRatePercent: number | null;
  averageApprovalTimeHours: number | null;
  requestsByStatus: Record<string, number>;
  topRequesters: TopRequesterDto[];
  topCVEs: TopCVEDto[];
}

/**
 * Get statistics for exception requests (ADMIN/SECCHAMPION only)
 *
 * @param dateRange Optional date range filter (7days, 30days, 90days, alltime). Defaults to 30days
 * @returns Statistics with metrics and aggregations
 * @throws Error on 401 (unauthorized), 403 (forbidden), 500 (server error)
 */
export async function getStatistics(dateRange?: string): Promise<ExceptionStatisticsDto> {
  const params = new URLSearchParams();
  if (dateRange) {
    params.append('dateRange', dateRange);
  }

  const url = `/api/vulnerability-exception-requests/statistics${params.toString() ? '?' + params.toString() : ''}`;
  const response = await authenticatedGet(url);

  if (response.ok) {
    return await response.json();
  }

  // Handle error responses
  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 403) {
    throw new Error('Access denied. This feature requires ADMIN or SECCHAMPION role.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to fetch statistics.');
  }

  throw new Error(`Failed to fetch statistics: ${response.status}`);
}

/**
 * Export filters for Excel export
 */
export interface ExportFilters {
  status?: ExceptionRequestStatus;
  dateRange?: string;
  requesterId?: number;
  reviewerId?: number;
}

/**
 * Export exception requests to Excel (ADMIN/SECCHAMPION only)
 *
 * @param filters Optional filters (status, dateRange, requesterId, reviewerId)
 * @returns void - triggers browser download
 * @throws Error on 401 (unauthorized), 403 (forbidden), 500 (server error)
 */
export async function exportToExcel(filters?: ExportFilters): Promise<void> {
  const params = new URLSearchParams();
  if (filters?.status) {
    params.append('status', filters.status);
  }
  if (filters?.dateRange) {
    params.append('dateRange', filters.dateRange);
  }
  if (filters?.requesterId) {
    params.append('requesterId', filters.requesterId.toString());
  }
  if (filters?.reviewerId) {
    params.append('reviewerId', filters.reviewerId.toString());
  }

  const url = `/api/vulnerability-exception-requests/export${params.toString() ? '?' + params.toString() : ''}`;
  const response = await authenticatedGet(url);

  if (response.ok) {
    // Extract filename from Content-Disposition header if available
    const contentDisposition = response.headers.get('Content-Disposition');
    let filename = 'exception-requests.xlsx';
    if (contentDisposition) {
      const filenameMatch = contentDisposition.match(/filename="(.+)"/);
      if (filenameMatch) {
        filename = filenameMatch[1];
      }
    }

    // Download the blob
    const blob = await response.blob();
    const downloadUrl = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = downloadUrl;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(downloadUrl);
    return;
  }

  // Handle error responses
  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 403) {
    throw new Error('Access denied. This feature requires ADMIN or SECCHAMPION role.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to export exception requests.');
  }

  throw new Error(`Failed to export exception requests: ${response.status}`);
}
