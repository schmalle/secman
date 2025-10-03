import { defineMiddleware } from 'astro:middleware';

/**
 * Server-side middleware to protect admin routes
 *
 * This middleware validates JWT tokens and checks for ADMIN role
 * before allowing access to /admin/* routes.
 */
export const onRequest = defineMiddleware(async (context, next) => {
    const { request, redirect, cookies } = context;
    const url = new URL(request.url);

    // Check if this is an admin route
    if (url.pathname.startsWith('/admin')) {
        // Get the auth token from cookies or localStorage (sent via headers)
        // Note: In SSR, we need to get the token from cookies since localStorage is client-side only
        const authToken = cookies.get('authToken')?.value;

        if (!authToken) {
            // No token, redirect to login
            return redirect('/login');
        }

        try {
            // Decode JWT to check roles (without verification - the backend will verify)
            // We're doing a basic check here for UX, backend still enforces security
            // Use Buffer.from() for Node.js compatibility (atob is browser-only)
            const payload = JSON.parse(
                Buffer.from(authToken.split('.')[1], 'base64').toString('utf-8')
            );
            const roles = payload.roles || [];

            // Check if user has ADMIN role
            if (!roles.includes('ADMIN')) {
                // User is authenticated but not an admin
                return redirect('/');
            }
        } catch (error) {
            // Invalid token format, redirect to login
            console.error('Invalid token format:', error);
            return redirect('/login');
        }
    }

    // Continue to the requested page
    return next();
});
