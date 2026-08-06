// Authentication is handled via HttpOnly secman_auth cookie with credentials: 'include'

/**
 * Service for AI-Powered Norm Mapping API operations
 * Feature: 058-ai-norm-mapping
 *
 * Provides AI-powered suggestions for mapping requirements to ISO 27001 and IEC 62443 controls.
 */

// ========== DTOs ==========

/**
 * Request for AI norm mapping suggestions
 */
export interface NormMappingSuggestionRequest {
  requirementIds?: number[] | null;  // Optional filter, null = all unmapped
}

/**
 * Response containing AI-generated norm suggestions
 */
export interface NormMappingSuggestionResponse {
  suggestions: RequirementSuggestions[];
  totalRequirementsAnalyzed: number;
  totalSuggestionsGenerated: number;
  // Partial failure tracking
  batchesProcessed?: number;
  batchesFailed?: number;
  failedBatchErrors?: BatchFailureInfo[];
  processingTimeMs?: number;
  partialSuccess?: boolean;
}

/**
 * Info about a failed batch
 */
export interface BatchFailureInfo {
  batchNumber: number;
  requirementCount: number;
  errorMessage: string;
  errorType: 'TIMEOUT' | 'API_ERROR' | 'PARSE_ERROR' | 'UNKNOWN';
}

/**
 * Suggestions for a single requirement
 */
export interface RequirementSuggestions {
  requirementId: number;
  requirementTitle: string;
  suggestions: NormSuggestion[];
}

/**
 * Single norm suggestion from AI
 */
export interface NormSuggestion {
  standard: string;           // e.g., "ISO 27001:2022"
  control: string;            // e.g., "A.8.1.1"
  controlName: string;        // e.g., "Inventory of assets"
  confidence: number;         // 1-5
  reasoning: string;          // Brief explanation
  normId?: number | null;     // ID if norm exists in DB, null if will be created
}

/**
 * Request to apply selected norm mappings
 */
export interface ApplyMappingsRequest {
  mappings: Record<number, NormToApply[]>;  // requirementId -> norms to add
}

/**
 * Single norm to apply to a requirement
 */
export interface NormToApply {
  normId?: number | null;     // Existing norm ID
  standard?: string | null;   // For new norm creation
  control?: string | null;    // For new norm creation
  version?: string | null;    // For new norm creation
}

/**
 * Response after applying mappings
 */
export interface ApplyMappingsResponse {
  updatedRequirements: number;
  newNormsCreated: number;
  existingNormsLinked: number;
}

/**
 * Response for unmapped count
 */
export interface UnmappedCountResponse {
  count: number;
}

/**
 * Error response from API
 */
export interface NormMappingErrorResponse {
  error: string;
  details?: string | null;
}

/**
 * Response for auto-apply mapping operation
 */
export interface AutoApplyMappingsResponse {
  totalRequirementsAnalyzed: number;
  requirementsSuccessfullyMapped: number;
  requirementsFailed: number;
  totalMappingsApplied: number;
  newNormsCreated: number;
  existingNormsLinked: number;
  failedRequirements: FailedRequirementInfo[];
  processingTimeMs: number;
}

export interface FailedRequirementInfo {
  requirementId: number;
  requirementTitle: string;
  errorMessage: string;
  errorType: 'TIMEOUT' | 'API_ERROR' | 'PARSE_ERROR' | 'UNKNOWN';
}

// ========== API Functions ==========

/**
 * Get AI suggestions for norm mappings
 * Feature: 058-ai-norm-mapping (User Story 1)
 *
 * Analyzes requirements without existing norm mappings and returns AI-generated
 * suggestions for ISO 27001 and IEC 62443 control mappings.
 *
 * @param request Optional request to filter specific requirements
 * @returns AI-generated suggestions grouped by requirement
 * @throws Error if API call fails or user is not authenticated
 */
export async function suggestMappings(
  request?: NormMappingSuggestionRequest
): Promise<NormMappingSuggestionResponse> {
  const response = await fetch('/api/norm-mapping/suggest', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include',
    body: JSON.stringify(request || {}),
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({
      error: 'Failed to get suggestions',
    })) as NormMappingErrorResponse;

    if (response.status === 400) {
      throw new Error(errorData.error || 'Invalid request or configuration error');
    } else if (response.status === 401) {
      throw new Error('Authentication required');
    } else if (response.status === 403) {
      throw new Error('Insufficient permissions (requires ADMIN, REQ, or SECCHAMPION role)');
    } else if (response.status === 503) {
      throw new Error('AI service unavailable');
    } else {
      throw new Error(errorData.error || `Failed to get suggestions: ${response.status}`);
    }
  }

  return await response.json();
}

/**
 * Apply selected norm mappings to requirements
 * Feature: 058-ai-norm-mapping (User Story 2)
 *
 * Saves the selected AI-suggested norm mappings to requirements.
 * Creates new norm entries if they don't exist in the database.
 *
 * @param request Mappings to apply (requirementId -> norms)
 * @returns Summary of applied mappings
 * @throws Error if API call fails or user is not authenticated
 */
export async function applyMappings(
  request: ApplyMappingsRequest
): Promise<ApplyMappingsResponse> {
  const response = await fetch('/api/norm-mapping/apply', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({
      error: 'Failed to apply mappings',
    })) as NormMappingErrorResponse;

    if (response.status === 400) {
      throw new Error(errorData.error || 'Invalid request');
    } else if (response.status === 401) {
      throw new Error('Authentication required');
    } else if (response.status === 403) {
      throw new Error('Insufficient permissions (requires ADMIN, REQ, or SECCHAMPION role)');
    } else if (response.status === 404) {
      throw new Error('Requirement not found');
    } else {
      throw new Error(errorData.error || `Failed to apply mappings: ${response.status}`);
    }
  }

  return await response.json();
}

/**
 * Get count of unmapped requirements
 * Feature: 058-ai-norm-mapping (User Story 4)
 *
 * Returns the number of requirements without any norm mappings.
 *
 * @returns Count of unmapped requirements
 * @throws Error if API call fails or user is not authenticated
 */
export async function getUnmappedCount(): Promise<number> {
  const response = await fetch('/api/norm-mapping/unmapped-count', {
    method: 'GET',
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include',
  });

  if (!response.ok) {
    if (response.status === 401) {
      throw new Error('Authentication required');
    } else if (response.status === 403) {
      throw new Error('Insufficient permissions');
    } else {
      throw new Error(`Failed to get unmapped count: ${response.status}`);
    }
  }

  const data: UnmappedCountResponse = await response.json();
  return data.count;
}
