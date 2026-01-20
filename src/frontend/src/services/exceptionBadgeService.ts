/**
 * Exception Badge Service - Real-time SSE updates for pending exception count
 *
 * Provides Server-Sent Events (SSE) connection to receive real-time updates
 * of pending exception request counts for badge display in the sidebar.
 *
 * **Features**:
 * - Automatic SSE connection management
 * - Callback-based updates (observer pattern)
 * - Error handling and auto-reconnection
 * - Connection state tracking
 *
 * **Usage**:
 * ```typescript
 * const disconnect = connectToBadgeUpdates((count) => {
 *   setBadgeCount(count);
 * });
 *
 * // Later, cleanup:
 * disconnect();
 * ```
 *
 * Feature: 031-vuln-exception-approval
 * User Story 3: ADMIN Approval Dashboard (P1)
 * Phase 6: Real-Time Badge Updates
 * Reference: spec.md FR-024
 */

/**
 * Callback function type for badge count updates.
 *
 * @param count The new pending exception count
 */
export type BadgeCountCallback = (count: number) => void;

// Track connection state to prevent reconnection storms
let connectionState: 'disconnected' | 'connecting' | 'connected' | 'error' = 'disconnected';
let errorCount = 0;
const MAX_ERRORS = 5;
const ERROR_RESET_TIMEOUT = 60000; // Reset error count after 1 minute
let reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;

/**
 * Connect to SSE endpoint for real-time badge count updates.
 *
 * **Event Flow**:
 * 1. Establishes EventSource connection to /api/exception-badge-updates
 * 2. Receives initial count event immediately
 * 3. Receives subsequent count updates when pending count changes
 * 4. Calls onUpdate callback with new count
 *
 * **Event Format** (from backend):
 * ```
 * event: count-update
 * data: {"pendingCount": 5}
 * ```
 *
 * **Error Handling**:
 * - Logs connection errors to console
 * - Prevents reconnection storms by tracking error count
 * - After 5 consecutive errors, stops reconnection attempts
 * - Callback receives 0 on connection errors (safe fallback)
 *
 * **Authentication**:
 * - Uses HttpOnly cookie-based authentication (withCredentials: true)
 * - Browser automatically sends the secman_auth cookie with the request
 * - Requires authenticated session (401 if not logged in)
 *
 * @param onUpdate Callback function to receive count updates
 * @returns Cleanup function to disconnect SSE connection
 *
 * @example
 * ```typescript
 * // In React component:
 * useEffect(() => {
 *   const disconnect = connectToBadgeUpdates((count) => {
 *     setPendingCount(count);
 *   });
 *
 *   return () => disconnect(); // Cleanup on unmount
 * }, []);
 * ```
 */
export function connectToBadgeUpdates(onUpdate: BadgeCountCallback): () => void {
  // Prevent multiple simultaneous connection attempts
  if (connectionState === 'connecting' || connectionState === 'connected') {
    console.warn('[ExceptionBadge] Connection already in progress or established');
    return () => {}; // No-op cleanup
  }

  // Stop reconnection attempts after too many errors
  if (errorCount >= MAX_ERRORS) {
    console.error('[ExceptionBadge] Too many connection errors, stopping reconnection attempts. Please refresh the page to retry.');
    onUpdate(0);
    return () => {};
  }

  connectionState = 'connecting';
  console.log('[ExceptionBadge] Connecting to SSE endpoint for real-time updates');

  // Create EventSource connection with credentials to send HttpOnly auth cookie
  // The backend CookieTokenReader will extract the JWT from the secman_auth cookie
  const eventSource = new EventSource('/api/exception-badge-updates', { withCredentials: true });

  let isCleaningUp = false;

  // Handle count-update events
  eventSource.addEventListener('count-update', (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data);
      const newCount = data.pendingCount;

      console.log('[ExceptionBadge] Received count update:', newCount);
      onUpdate(newCount);

      // Reset error count on successful message
      if (errorCount > 0) {
        errorCount = 0;
      }
    } catch (error) {
      console.error('[ExceptionBadge] Failed to parse count update event:', error, event.data);
    }
  });

  // Handle connection opened
  eventSource.addEventListener('open', () => {
    console.log('[ExceptionBadge] SSE connection established');
    connectionState = 'connected';
    // NOTE: Don't reset errorCount here - it gets reset on successful message receipt
    // This prevents the error loop where auto-reconnect resets count before we can track failures
  });

  // Handle connection errors
  eventSource.addEventListener('error', (error) => {
    if (isCleaningUp) {
      return; // Ignore errors during cleanup
    }

    errorCount++;
    connectionState = 'error';

    console.error(`[ExceptionBadge] SSE connection error (${errorCount}/${MAX_ERRORS}):`, error);

    // ALWAYS close EventSource to prevent browser auto-reconnect
    eventSource.close();

    // Fallback to 0 count on error (safe default)
    onUpdate(0);

    // Stop if max errors reached
    if (errorCount >= MAX_ERRORS) {
      console.error('[ExceptionBadge] Maximum errors reached, stopping reconnection. Refresh page to retry.');
      connectionState = 'disconnected';
      return;
    }

    // Exponential backoff: 1s, 2s, 4s, 8s, 16s (capped at 30s)
    const backoffDelay = Math.min(1000 * Math.pow(2, errorCount - 1), 30000);
    console.log(`[ExceptionBadge] Will retry in ${backoffDelay / 1000}s...`);

    connectionState = 'disconnected';

    // Schedule reconnection with backoff
    reconnectTimeoutId = setTimeout(() => {
      reconnectTimeoutId = null;
      connectToBadgeUpdates(onUpdate);
    }, backoffDelay);
  });

  // Return cleanup function
  return () => {
    isCleaningUp = true;
    console.log('[ExceptionBadge] Disconnecting from SSE endpoint');
    eventSource.close();
    connectionState = 'disconnected';
    // Cancel any pending reconnection attempt
    if (reconnectTimeoutId) {
      clearTimeout(reconnectTimeoutId);
      reconnectTimeoutId = null;
    }
  };
}

/**
 * Fetch current pending count via HTTP (fallback for SSE failures).
 *
 * Used as a backup method when SSE is not available or fails.
 * Polls the backend for the current count.
 *
 * **Use Case**: Browsers without EventSource support, or when SSE fails repeatedly
 *
 * @returns Promise resolving to current pending count
 * @throws Error if request fails or user not authenticated
 *
 * @example
 * ```typescript
 * try {
 *   const count = await fetchPendingCount();
 *   setBadgeCount(count);
 * } catch (error) {
 *   console.error('Failed to fetch count:', error);
 *   setBadgeCount(0);
 * }
 * ```
 */
export async function fetchPendingCount(): Promise<number> {
  const response = await fetch('/api/vulnerability-exception-requests/pending/count', {
    method: 'GET',
    credentials: 'include', // Include session cookies
    headers: {
      'Accept': 'application/json'
    }
  });

  if (!response.ok) {
    if (response.status === 401) {
      throw new Error('Unauthorized: User not authenticated');
    }
    throw new Error(`Failed to fetch pending count: ${response.status} ${response.statusText}`);
  }

  const data = await response.json();
  return data.count || 0;
}
