// Authentication utility functions

export interface User {
    id: number;
    username: string;
    email: string;
    roles: string[];
}

/**
 * Get the stored JWT token.
 * Note: With HttpOnly cookies, the token is not accessible via JavaScript.
 * This function now returns null for new logins, but may return a token
 * for users who logged in before the security update.
 * @deprecated Use cookie-based authentication with credentials: 'include' instead
 */
export function getAuthToken(): string | null {
    if (typeof window === 'undefined') return null;
    // For backward compatibility during migration, check localStorage
    // New logins don't store tokens in localStorage (XSS protection)
    return localStorage.getItem('authToken');
}

/**
 * Get the stored user information
 */
export function getUser(): User | null {
    if (typeof window === 'undefined') return null;
    const userStr = localStorage.getItem('user');
    if (!userStr) return null;

    try {
        return JSON.parse(userStr);
    } catch (e) {
        return null;
    }
}

/**
 * Check if user is currently authenticated.
 * With HttpOnly cookies, we check for user data in localStorage instead of the token.
 * The actual authentication is validated by the server using the HttpOnly cookie.
 */
export function isAuthenticated(): boolean {
    // Check for user data (stored on successful login)
    // The actual token is in an HttpOnly cookie, not accessible via JS
    return getUser() !== null;
}

/**
 * Check if user has a specific role or any of the specified roles
 * @param role Single role string or array of roles to check
 * @returns true if user has the role (or any of the roles if array)
 */
export function hasRole(role: string | string[]): boolean {
    const user = getUser();
    if (!user?.roles) return false;

    if (Array.isArray(role)) {
        return role.some(r => user.roles.includes(r));
    }
    return user.roles.includes(role);
}

/**
 * Check if user is admin
 */
export function isAdmin(): boolean {
    return hasRole('ADMIN');
}

/**
 * Check if user has vulnerability management access (ADMIN, VULN, or SECCHAMPION role)
 * Feature: 025-role-based-access-control - SECCHAMPION has broad access including vulns
 */
export function hasVulnAccess(): boolean {
    return hasRole('ADMIN') || hasRole('VULN') || hasRole('SECCHAMPION');
}

/**
 * Clear authentication data (logout).
 * Note: The HttpOnly cookie is cleared by the /api/auth/logout endpoint.
 * This function clears client-side data (user info and legacy token storage).
 */
export function clearAuth(): void {
    if (typeof window === 'undefined') return;
    // Clear user data
    localStorage.removeItem('user');
    // Clear legacy token storage (for backward compatibility during migration)
    localStorage.removeItem('authToken');
}

/**
 * Create authenticated fetch request headers
 */
export function getAuthHeaders(): Record<string, string> {
    const token = getAuthToken();
    const headers: Record<string, string> = {
        'Content-Type': 'application/json',
    };
    
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }
    
    return headers;
}

/**
 * Make an authenticated API request
 */
export async function authenticatedFetch(url: string, options: RequestInit = {}): Promise<Response> {
    const authHeaders = getAuthHeaders();
    
    const response = await fetch(url, {
        ...options,
        headers: {
            ...authHeaders,
            ...options.headers,
        },
        credentials: 'include',
    });
    
    // If we get 401, clear auth data and redirect to login
    if (response.status === 401) {
        clearAuth();
        if (typeof window !== 'undefined') {
            window.location.href = '/login';
        }
    }
    
    return response;
}

/**
 * Convenience method for GET requests
 */
export async function authenticatedGet(url: string): Promise<Response> {
    console.log('[auth] authenticatedGet called with URL:', url);
    const response = await authenticatedFetch(url, { method: 'GET' });
    console.log('[auth] authenticatedGet completed for URL:', url, 'status:', response.status);
    return response;
}

/**
 * Convenience method for POST requests
 */
export async function authenticatedPost(url: string, data?: any): Promise<Response> {
    const token = getAuthToken();
    const headers: Record<string, string> = {};

    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }

    // Handle FormData (file uploads) differently - don't set Content-Type, let browser handle it
    if (data instanceof FormData) {
        const response = await fetch(url, {
            method: 'POST',
            headers: headers,
            body: data,
            credentials: 'include',
        });

        if (response.status === 401) {
            clearAuth();
            if (typeof window !== 'undefined') {
                window.location.href = '/login';
            }
        }

        return response;
    }

    // For regular JSON data
    return authenticatedFetch(url, {
        method: 'POST',
        body: data ? JSON.stringify(data) : undefined,
    });
}

/**
 * Convenience method for PUT requests
 */
export async function authenticatedPut(url: string, data?: any): Promise<Response> {
    return authenticatedFetch(url, {
        method: 'PUT', 
        body: data ? JSON.stringify(data) : undefined,
    });
}

/**
 * Convenience method for DELETE requests
 */
export async function authenticatedDelete(url: string): Promise<Response> {
    return authenticatedFetch(url, { method: 'DELETE' });
}

// Session keep-alive configuration
const TOKEN_REFRESH_INTERVAL = 30 * 60 * 1000;  // 30 minutes
const HEARTBEAT_INTERVAL = 15 * 60 * 1000;       // 15 minutes

let refreshIntervalId: number | null = null;
let heartbeatIntervalId: number | null = null;

/**
 * Refresh the JWT token silently.
 * The token is stored in an HttpOnly cookie, so we use credentials: 'include'.
 * Returns true if successful, false otherwise.
 */
export async function refreshToken(): Promise<string | null> {
    // With HttpOnly cookies, we don't have direct access to the token
    // Check if user data exists (indicating a logged-in session)
    if (!isAuthenticated()) {
        return null;
    }

    try {
        const response = await fetch('/api/auth/refresh', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            credentials: 'include', // Send HttpOnly cookie for authentication
        });

        if (response.ok) {
            const data = await response.json();
            console.log('[auth] Token refreshed successfully (stored in HttpOnly cookie)');
            // Token is now in the updated HttpOnly cookie, not accessible via JS
            return data.token || 'refreshed';
        } else if (response.status === 401) {
            // Token is invalid/expired, clear auth and redirect
            console.log('[auth] Token refresh failed - session expired');
            clearAuth();
            if (typeof window !== 'undefined') {
                window.location.href = '/login';
            }
        }
    } catch (error) {
        console.error('[auth] Token refresh error:', error);
    }

    return null;
}

/**
 * Send a heartbeat to keep the session alive.
 * This validates the token without generating a new one.
 */
export async function sendHeartbeat(): Promise<boolean> {
    if (!isAuthenticated()) {
        return false;
    }

    try {
        const response = await fetch('/api/auth/heartbeat', {
            method: 'GET',
            credentials: 'include', // Send HttpOnly cookie for authentication
        });

        if (response.ok) {
            console.log('[auth] Heartbeat successful');
            return true;
        } else if (response.status === 401) {
            // Session expired, try to refresh
            console.log('[auth] Heartbeat failed - attempting token refresh');
            const newToken = await refreshToken();
            return newToken !== null;
        }
    } catch (error) {
        console.error('[auth] Heartbeat error:', error);
    }

    return false;
}

/**
 * Start the session keep-alive service.
 * This runs periodic heartbeats and token refreshes to prevent session timeout.
 */
export function startSessionKeepAlive(): void {
    if (typeof window === 'undefined') return;

    // Stop any existing intervals
    stopSessionKeepAlive();

    // Only start if authenticated
    if (!isAuthenticated()) {
        return;
    }

    console.log('[auth] Starting session keep-alive service');

    // Periodic heartbeat to validate session
    heartbeatIntervalId = window.setInterval(async () => {
        if (isAuthenticated()) {
            await sendHeartbeat();
        } else {
            stopSessionKeepAlive();
        }
    }, HEARTBEAT_INTERVAL);

    // Periodic token refresh to extend session
    refreshIntervalId = window.setInterval(async () => {
        if (isAuthenticated()) {
            await refreshToken();
        } else {
            stopSessionKeepAlive();
        }
    }, TOKEN_REFRESH_INTERVAL);
}

/**
 * Stop the session keep-alive service.
 */
export function stopSessionKeepAlive(): void {
    if (typeof window === 'undefined') return;

    if (heartbeatIntervalId !== null) {
        window.clearInterval(heartbeatIntervalId);
        heartbeatIntervalId = null;
    }

    if (refreshIntervalId !== null) {
        window.clearInterval(refreshIntervalId);
        refreshIntervalId = null;
    }

    console.log('[auth] Session keep-alive service stopped');
}

/**
 * Enhanced clearAuth that also stops keep-alive service.
 */
export function logout(): void {
    stopSessionKeepAlive();
    clearAuth();
}