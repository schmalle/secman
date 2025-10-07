// Authentication utility functions

export interface User {
    id: number;
    username: string;
    email: string;
    roles: string[];
}

/**
 * Get the stored JWT token
 */
export function getAuthToken(): string | null {
    if (typeof window === 'undefined') return null;
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
 * Check if user is currently authenticated
 */
export function isAuthenticated(): boolean {
    return getAuthToken() !== null;
}

/**
 * Check if user has a specific role
 */
export function hasRole(role: string): boolean {
    const user = getUser();
    return user?.roles.includes(role) || false;
}

/**
 * Check if user is admin
 */
export function isAdmin(): boolean {
    return hasRole('ADMIN');
}

/**
 * Check if user has vulnerability management access (ADMIN or VULN role)
 */
export function hasVulnAccess(): boolean {
    return hasRole('ADMIN') || hasRole('VULN');
}

/**
 * Clear authentication data (logout)
 */
export function clearAuth(): void {
    if (typeof window === 'undefined') return;
    localStorage.removeItem('authToken');
    localStorage.removeItem('user');
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
    return authenticatedFetch(url, { method: 'GET' });
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