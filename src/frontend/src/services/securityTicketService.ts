/**
 * API client for /api/security-tickets. Every call goes through the authenticated* helpers,
 * so the HttpOnly session cookie authenticates and no token is handled here.
 *
 * Rejections are not exceptions: the server answers 4xx with a result body (field errors
 * included) and the caller decides how to show it. Only network or unexpected failures throw.
 */
import { authenticatedGet, authenticatedPost } from '../utils/auth';
import type { AccountOption, BatchResult, CreateResult, Delivery, FieldError, TicketFields, TicketMeta, TicketStatus } from './securityTicketLogic';

const BASE = '/api/security-tickets';

/** Error body of /api/security-tickets: stable code, message and optional field errors. */
export interface ApiError { error: string; message: string; errors?: FieldError[] }
/** Either the response data or the error, never an exception for a 4xx. */
export type ApiResult<T> = { ok: true; status: number; data: T } | { ok: false; status: number; error: ApiError };

/** A ticket as list rows show it. */
export interface TicketSummary {
  id: number; ticketKey: string; awsAccountId: string; awsAccountName: string | null; title: string; severity: string;
  status: TicketStatus; dueAt: string | null; overdue: boolean; createdAt: string; updatedAt: string;
  createdByUsername: string; sourceSystem: string | null; externalReference: string | null; version: number;
}

/** One activity-log entry. */
export interface TicketEvent {
  id: number; eventType: 'CREATED' | 'STATUS_CHANGED' | 'COMMENT'; actorUsername: string | null; actorKind: string;
  fromStatus: string | null; toStatus: string | null; comment: string | null; createdAt: string;
}

/** Everything the detail page shows, including what the caller may do next. */
export interface TicketDetail {
  ticket: TicketSummary; description: string; recommendedAction: string | null; cves: string[];
  resources: { type: string; identifier: string; region: string | null }[]; references: string[];
  recipientCount: number; closedAt: string | null; events: TicketEvent[];
  allowedTransitions: TicketStatus[]; commentRequiredFor: TicketStatus[]; canComment: boolean; canManageDelivery: boolean;
}

/** One page of ticket rows. */
export interface TicketPage { content: TicketSummary[]; totalElements: number; totalPages: number; page: number; size: number }
/** Figures for the count tiles, within the caller's scope. */
export interface TicketCounts { total: number; open: number; closed: number; overdue: number }

/** Turns a fetch Response into an ApiResult; a rejected create (4xx with a result body) counts as data. */
async function read<T>(response: Response): Promise<ApiResult<T>> {
  const body = await response.json().catch(() => null);
  if (response.ok) return { ok: true, status: response.status, data: body as T };
  // A rejected create answers 4xx with a CreateResult; treat it as data for the caller.
  if (body && typeof body === 'object' && 'outcome' in body) return { ok: true, status: response.status, data: body as T };
  const error: ApiError = body && typeof body === 'object' && 'error' in body
    ? body as ApiError
    : { error: `HTTP_${response.status}`, message: response.statusText || 'Request failed' };
  return { ok: false, status: response.status, error };
}

export const securityTicketService = {
  meta: async () => read<TicketMeta>(await authenticatedGet(`${BASE}/meta`)),

  accounts: async (query: string) =>
    read<AccountOption[]>(await authenticatedGet(`${BASE}/accounts?q=${encodeURIComponent(query)}&limit=10`)),

  create: async (ticket: TicketFields, delivery: Delivery) =>
    read<CreateResult>(await authenticatedPost(BASE, { ticket, delivery })),

  createBatch: async (tickets: TicketFields[], delivery: Delivery) =>
    read<BatchResult>(await authenticatedPost(`${BASE}/batch`, { tickets, delivery })),

  list: async (params: { status?: TicketStatus; q?: string; page?: number; size?: number }) => {
    const query = new URLSearchParams();
    if (params.status) query.set('status', params.status);
    if (params.q) query.set('q', params.q);
    query.set('page', String(params.page ?? 0));
    query.set('size', String(params.size ?? 25));
    return read<TicketPage>(await authenticatedGet(`${BASE}?${query.toString()}`));
  },

  summary: async () => read<TicketCounts>(await authenticatedGet(`${BASE}/summary`)),

  get: async (id: number) => read<TicketDetail>(await authenticatedGet(`${BASE}/${id}`)),

  transition: async (id: number, toStatus: TicketStatus, comment: string, expectedVersion: number) =>
    read<TicketDetail>(await authenticatedPost(`${BASE}/${id}/transitions`, { toStatus, comment: comment || undefined, expectedVersion })),

  comment: async (id: number, comment: string) =>
    read<TicketDetail>(await authenticatedPost(`${BASE}/${id}/comments`, { comment })),
};
