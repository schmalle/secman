/**
 * Thin JSON layer over the authenticated* fetch helpers in utils/auth.ts.
 *
 * The authenticated* helpers return a raw Response, which left every call site
 * re-implementing the same dozen lines of `if (ok) json else extract-error`
 * (~65 components at the time this was extracted). These wrappers return parsed
 * data or throw an ApiError carrying the backend's message, so a component's
 * fetch collapses to `setX(await getJson<X>('/api/x'))` inside its try/catch.
 *
 * This sits ON TOP of utils/auth.ts — auth stays in the HttpOnly cookie and the
 * 401-redirect behavior of authenticatedFetch is unchanged. Never add token
 * handling here.
 */

import {
    authenticatedGet,
    authenticatedPost,
    authenticatedPut,
    authenticatedDelete,
} from './auth';

/** Error thrown for a non-OK response; message is the backend's, status the HTTP code. */
export class ApiError extends Error {
    readonly status: number;

    /** Message is what the UI shows; status lets callers branch (403 vs 404 vs 5xx). */
    constructor(message: string, status: number) {
        super(message);
        this.name = 'ApiError';
        this.status = status;
    }
}

/**
 * Extract a useful error message from a non-OK Response. Handles the three
 * shapes this app produces: our own `{error: "..."}`, Micronaut's default
 * `{message: "..."}`, and Bean-Validation `{_embedded:{errors:[{message:...}]}}`.
 * Falls back to "<fallback> (HTTP <status>)" so we never show "Unknown error".
 *
 * (Moved here from WorkgroupManagement.tsx so every component shares one copy.)
 */
export async function extractErrorMessage(response: Response, fallback: string): Promise<string> {
    try {
        const body = await response.json();
        if (body && typeof body === 'object') {
            if (typeof body.error === 'string' && body.error) return body.error;
            if (typeof body.message === 'string' && body.message) return body.message;
            const violations = body?._embedded?.errors;
            if (Array.isArray(violations) && violations.length > 0) {
                return violations.map((v: any) => v?.message).filter(Boolean).join('; ') || fallback;
            }
        }
    } catch {
        // body wasn't JSON — fall through
    }
    return `${fallback} (HTTP ${response.status})`;
}

/**
 * Turn a Response into parsed JSON or an ApiError. Exported for tests and for
 * call sites that already hold a Response. An empty body (204, or a write
 * endpoint that returns nothing) resolves to undefined rather than a parse error.
 */
export async function parseJsonResponse<T>(response: Response, errorLabel: string): Promise<T> {
    if (!response.ok) {
        throw new ApiError(await extractErrorMessage(response, errorLabel), response.status);
    }
    const text = await response.text();
    if (!text) {
        return undefined as T;
    }
    return JSON.parse(text) as T;
}

export async function getJson<T>(url: string, errorLabel = 'Request failed'): Promise<T> {
    return parseJsonResponse<T>(await authenticatedGet(url), errorLabel);
}

export async function postJson<T = void>(url: string, body?: unknown, errorLabel = 'Request failed'): Promise<T> {
    return parseJsonResponse<T>(await authenticatedPost(url, body), errorLabel);
}

export async function putJson<T = void>(url: string, body?: unknown, errorLabel = 'Request failed'): Promise<T> {
    return parseJsonResponse<T>(await authenticatedPut(url, body), errorLabel);
}

export async function deleteJson<T = void>(url: string, body?: unknown, errorLabel = 'Request failed'): Promise<T> {
    return parseJsonResponse<T>(await authenticatedDelete(url, body), errorLabel);
}
