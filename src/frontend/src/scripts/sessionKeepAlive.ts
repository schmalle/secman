/**
 * Session keep-alive initialization script
 * This script is loaded as a module and properly bundled by Astro/Vite
 */

import { startSessionKeepAlive, isAuthenticated } from '../utils/auth';

// Initialize session keep-alive if user is authenticated
export function initSessionKeepAlive(): void {
    if (isAuthenticated()) {
        startSessionKeepAlive();
    }
}

// Auto-initialize when this module is imported
if (typeof window !== 'undefined') {
    // Listen for userLoaded event from Layout.astro
    window.addEventListener('userLoaded', () => {
        initSessionKeepAlive();
    });
}
