// Authentication initialization - call this on app startup

export interface User {
    id: number;
    username: string;
    email: string;
    roles: string[];
}

declare global {
    interface Window {
        currentUser?: User | null;
    }
}

/**
 * Initialize authentication state from localStorage
 * Should be called on app startup.
 *
 * Note: The JWT token is stored in an HttpOnly cookie (secman_auth)
 * for XSS protection. Only user data is stored in localStorage.
 */
export function initializeAuth(): void {
    try {
        const userStr = localStorage.getItem('user');

        if (userStr) {
            const user = JSON.parse(userStr);
            window.currentUser = user;
            console.log('Auth initialized with user:', user);
        } else {
            window.currentUser = null;
            console.log('No authentication data found');
        }

        // Dispatch event to notify components
        window.dispatchEvent(new CustomEvent('userLoaded'));
    } catch (error) {
        console.error('Failed to initialize auth:', error);
        // Clear invalid data
        localStorage.removeItem('user');
        window.currentUser = null;
        window.dispatchEvent(new CustomEvent('userLoaded'));
    }
}

/**
 * Validate session by making a request to the backend.
 * Uses HttpOnly cookie for authentication (sent automatically with credentials: 'include').
 */
export async function validateToken(): Promise<boolean> {
    // Check if we have user data (indicates a logged-in session)
    const userStr = localStorage.getItem('user');
    if (!userStr) return false;

    try {
        const response = await fetch('/api/auth/status', {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json',
            },
            credentials: 'include',  // Send HttpOnly cookie automatically
        });

        if (response.ok) {
            const userData = await response.json();
            // Update stored user data
            localStorage.setItem('user', JSON.stringify(userData));
            window.currentUser = userData;
            window.dispatchEvent(new CustomEvent('userLoaded'));
            return true;
        } else {
            // Session is invalid - clear user data
            localStorage.removeItem('user');
            window.currentUser = null;
            window.dispatchEvent(new CustomEvent('userLoaded'));
            return false;
        }
    } catch (error) {
        console.error('Token validation failed:', error);
        return false;
    }
}