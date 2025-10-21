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
 * - Browser automatically attempts reconnection (EventSource default behavior)
 * - Callback receives 0 on connection errors (safe fallback)
 *
 * **Authentication**:
 * - Uses default browser credentials (cookies/session)
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
  console.log('[ExceptionBadge] Connecting to SSE endpoint for real-time updates');

  // Create EventSource connection
  const eventSource = new EventSource('/api/exception-badge-updates', {
    withCredentials: true // Include session cookies for authentication
  });

  // Handle count-update events
  eventSource.addEventListener('count-update', (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data);
      const newCount = data.pendingCount;

      console.log('[ExceptionBadge] Received count update:', newCount);
      onUpdate(newCount);
    } catch (error) {
      console.error('[ExceptionBadge] Failed to parse count update event:', error, event.data);
    }
  });

  // Handle connection opened
  eventSource.addEventListener('open', () => {
    console.log('[ExceptionBadge] SSE connection established');
  });

  // Handle connection errors
  eventSource.addEventListener('error', (error) => {
    console.error('[ExceptionBadge] SSE connection error:', error);

    // Check if connection is closed (readyState 2 = CLOSED)
    if (eventSource.readyState === EventSource.CLOSED) {
      console.warn('[ExceptionBadge] SSE connection closed by server');
      // EventSource will attempt automatic reconnection
    }

    // Fallback to 0 count on error (safe default)
    onUpdate(0);
  });

  // Return cleanup function
  return () => {
    console.log('[ExceptionBadge] Disconnecting from SSE endpoint');
    eventSource.close();
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
