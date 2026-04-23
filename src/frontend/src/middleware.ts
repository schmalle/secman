import { defineMiddleware } from 'astro:middleware';

/**
 * Admin pages that may also be accessed by non-admin roles.
 * Each entry lists the extra roles (beyond ADMIN) that are allowed.
 */
const ADMIN_ROLE_EXCEPTIONS: Array<{ path: string; extraRoles: string[] }> = [
    // AWS Account Sharing — viewable by VULN (audit) and SECCHAMPION (manage).
    // Authorization for mutations is additionally enforced in the controller.
    { path: '/admin/aws-account-sharing', extraRoles: ['VULN', 'SECCHAMPION'] },
];

/**
 * Server-side middleware to protect admin routes
 *
 * This middleware validates JWT tokens and checks for ADMIN role
 * before allowing access to /admin/* routes. Some paths are additionally
 * accessible to other roles via ADMIN_ROLE_EXCEPTIONS.
 */
export const onRequest = defineMiddleware(async (context, next) => {
    const { request, redirect, cookies } = context;
    const url = new URL(request.url);

    // Check if this is an admin route
    if (url.pathname.startsWith('/admin')) {
        // Get the auth token from cookies or localStorage (sent via headers)
        // Note: In SSR, we need to get the token from cookies since localStorage is client-side only
        // Cookie name must match AuthController.AUTH_COOKIE_NAME ("secman_auth")
        const authToken = cookies.get('secman_auth')?.value;

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
            const roles: string[] = payload.roles || [];

            const allowedExtraRoles = ADMIN_ROLE_EXCEPTIONS
                .filter(rule => url.pathname === rule.path || url.pathname.startsWith(rule.path + '/'))
                .flatMap(rule => rule.extraRoles);

            const permitted = roles.includes('ADMIN') ||
                allowedExtraRoles.some(role => roles.includes(role));

            if (!permitted) {
                // User is authenticated but lacks a permitted role for this admin page
                return redirect('/');
            }
        } catch (error) {
            // Invalid token format, redirect to login
            console.error('Invalid token format:', error);
            return redirect('/login');
        }
    }

    // Continue to the requested page
    const response = await next();

    // Enforce clickjacking protection via HTTP headers.
    // frame-ancestors cannot be delivered via <meta> per CSP spec — it must come from
    // a response header. X-Frame-Options is the legacy fallback for older browsers.
    try {
        response.headers.set('X-Frame-Options', 'DENY');
        const existingCsp = response.headers.get('Content-Security-Policy');
        if (existingCsp) {
            if (!/frame-ancestors/i.test(existingCsp)) {
                response.headers.set(
                    'Content-Security-Policy',
                    existingCsp.replace(/;\s*$/, '') + "; frame-ancestors 'none'"
                );
            }
        } else {
            response.headers.set('Content-Security-Policy', "frame-ancestors 'none'");
        }
    } catch {
        // Some response types (e.g. redirects from earlier in this middleware)
        // may have immutable headers — safe to ignore; redirects are not framed.
    }

    return response;
});
