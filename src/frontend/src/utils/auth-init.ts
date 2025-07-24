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
 * Should be called on app startup
 */
export function initializeAuth(): void {
    try {
        const token = localStorage.getItem('authToken');
        const userStr = localStorage.getItem('user');
        
        if (token && userStr) {
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
        localStorage.removeItem('authToken');
        localStorage.removeItem('user');
        window.currentUser = null;
        window.dispatchEvent(new CustomEvent('userLoaded'));
    }
}

/**
 * Validate token by making a request to the backend
 * Optionally called to verify token is still valid
 */
export async function validateToken(): Promise<boolean> {
    const token = localStorage.getItem('authToken');
    if (!token) return false;
    
    try {
        const response = await fetch('/api/auth/status', {
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json',
            },
        });
        
        if (response.ok) {
            const userData = await response.json();
            // Update stored user data
            localStorage.setItem('user', JSON.stringify(userData));
            window.currentUser = userData;
            window.dispatchEvent(new CustomEvent('userLoaded'));
            return true;
        } else {
            // Token is invalid
            localStorage.removeItem('authToken');
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