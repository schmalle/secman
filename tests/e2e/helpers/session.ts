import type { Page } from '@playwright/test';

/**
 * Establishes a signed-in session without driving the login form.
 *
 * Two things make a session in this app, and both are set here:
 *  - the JWT, which arrives as the HttpOnly `secman_auth` cookie and lands on the browser
 *    context's jar (`page.request` shares it), and
 *  - `sessionStorage['user']`, which `Login.tsx` writes after a successful post and which
 *    `Layout.astro` treats as the presence check — without it the layout bounces to /login
 *    regardless of the cookie.
 *
 * Filling the form instead makes every test depend on React hydration completing inside the 10s
 * `actionTimeout`, which is flaky against a dev server that compiles routes on first hit — it was
 * a recurring Edge-only failure before this helper existed. Use the form only when the login form
 * itself is what is under test.
 */
export async function loginViaApi(
    page: Page,
    username = process.env.SECMAN_ADMIN_NAME!,
    password = process.env.SECMAN_ADMIN_PASS!,
): Promise<void> {
    const response = await page.request.post('/api/auth/login', { data: { username, password } });
    if (!response.ok()) {
        throw new Error(`Login failed with HTTP ${response.status()}`);
    }
    const user = await response.json();
    // Any same-origin document will do; this only establishes the origin for sessionStorage.
    await page.goto('/login');
    await page.evaluate((u) => sessionStorage.setItem('user', JSON.stringify(u)), user);
}

/** Fails fast with a useful message rather than an opaque `undefined` deep inside a test. */
export function requireEnv(...names: string[]): void {
    const missing = names.filter((v) => !process.env[v]);
    if (missing.length > 0) {
        throw new Error(
            `Missing required environment variables: ${missing.join(', ')}. ` +
            `Set them directly or use ./run-e2e.sh with Proton Pass.`
        );
    }
}
