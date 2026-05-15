/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Thin wrappers around the AI-suggestion endpoints. All requests go through
 * the shared `authenticatedFetch` helper which puts the JWT on the request,
 * redirects to /login on 401, and uses cookies.
 */
import { authenticatedGet, authenticatedPost, authenticatedDelete } from '../utils/auth';

export type SuggestionScope = 'WHOLE_ASSESSMENT' | 'SUBSET' | 'SINGLE_REQUIREMENT';

export interface Citation {
  title?: string | null;
  url: string;
  snippet?: string | null;
}

export type ConfidenceBand = 'HIGH' | 'MEDIUM' | 'LOW';
export type SuggestedAnswerType = 'YES' | 'NO' | 'N_A' | 'UNKNOWN';
export type AiJobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface AppliedSuggestion {
  requirementId: number;
  suggestedAnswerType: SuggestedAnswerType;
  suggestedComment: string | null;
  rawConfidence: number;
  confidenceBand: ConfidenceBand;
  rationale: string | null;
  citations: Citation[];
  model: string;
  promptVersion: string;
  webSearchUsed: boolean;
  createdAt: string;
}

export interface StartJobResponse {
  jobId: number;
  totalCount: number;
  estimatedCostUsd: string | null;
}

export interface AiJobStatusDto {
  id: number;
  status: AiJobStatus;
  model: string;
  scope: string;
  totalCount: number;
  completedCount: number;
  failedCount: number;
  progressPercent: number;
  totalCostUsd: string;
  estimatedCostUsd: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  errorMessage: string | null;
}

export interface ClearLowConfidenceResponse {
  deletedResponseCount: number;
}

const baseUrl = (assessmentId: number) =>
  `/api/risk-assessments/${assessmentId}/ai-suggestions`;

export async function startAiJob(
  assessmentId: number,
  payload: { scope: SuggestionScope; requirementIds?: number[]; force?: boolean }
): Promise<StartJobResponse> {
  const r = await authenticatedPost(`${baseUrl(assessmentId)}/jobs`, payload);
  if (!r.ok) throw new Error(`startAiJob failed: ${r.status} ${await r.text()}`);
  return r.json();
}

export async function getAiJob(
  assessmentId: number,
  jobId: number
): Promise<AiJobStatusDto> {
  const r = await authenticatedGet(`${baseUrl(assessmentId)}/jobs/${jobId}`);
  if (!r.ok) throw new Error(`getAiJob failed: ${r.status}`);
  return r.json();
}

export async function cancelAiJob(
  assessmentId: number,
  jobId: number
): Promise<void> {
  const r = await authenticatedDelete(`${baseUrl(assessmentId)}/jobs/${jobId}`);
  if (!r.ok && r.status !== 409) throw new Error(`cancelAiJob failed: ${r.status}`);
}

export async function listAppliedSuggestions(
  assessmentId: number
): Promise<AppliedSuggestion[]> {
  const r = await authenticatedGet(baseUrl(assessmentId));
  if (!r.ok) {
    // Endpoint may 403 if the user lacks role/ownership — degrade gracefully.
    if (r.status === 403 || r.status === 404) return [];
    throw new Error(`listAppliedSuggestions failed: ${r.status}`);
  }
  return r.json();
}

export async function clearLowConfidence(
  assessmentId: number
): Promise<ClearLowConfidenceResponse> {
  const r = await authenticatedPost(`${baseUrl(assessmentId)}/clear-low-confidence`);
  if (!r.ok) throw new Error(`clearLowConfidence failed: ${r.status}`);
  return r.json();
}

/**
 * Poll a job to completion. Returns the final status. Used as the SSE
 * fallback when EventSource isn't available or 5xx-ed.
 */
export async function pollJobUntilTerminal(
  assessmentId: number,
  jobId: number,
  onTick?: (s: AiJobStatusDto) => void,
  pollMs = 1500,
  timeoutMs = 30 * 60 * 1000
): Promise<AiJobStatusDto> {
  const start = Date.now();
  while (true) {
    const s = await getAiJob(assessmentId, jobId);
    onTick?.(s);
    if (s.status === 'COMPLETED' || s.status === 'FAILED' || s.status === 'CANCELLED') return s;
    if (Date.now() - start > timeoutMs) {
      throw new Error(`AI job ${jobId} did not finish within ${timeoutMs}ms`);
    }
    await new Promise(resolve => setTimeout(resolve, pollMs));
  }
}

// ---- SSE -----------------------------------------------------------------

export interface JobProgressEvent {
  jobId: number;
  type: 'PROGRESS' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  requirementId: number | null;
  band: ConfidenceBand | null;
  completedCount: number;
  failedCount: number;
  totalCount: number;
  totalCostUsd: string | null;
  errorMessage: string | null;
}

/**
 * Open an EventSource over the SSE endpoint. JWT travels in `?token=` because
 * EventSource has no API for request headers — same pattern as the existing
 * `/api/exception-badge-updates` and `/api/materialized-view-refresh/progress`.
 *
 * Callers must `.close()` the EventSource when done.
 */
export function openJobEventStream(
  assessmentId: number,
  jobId: number
): EventSource {
  const token = (typeof window !== 'undefined' && localStorage.getItem('authToken')) || '';
  const url = `${baseUrl(assessmentId)}/jobs/${jobId}/events?token=${encodeURIComponent(token)}`;
  return new EventSource(url, { withCredentials: true });
}
