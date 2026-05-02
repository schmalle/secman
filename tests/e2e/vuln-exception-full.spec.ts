import { test, expect, type Page } from '@playwright/test';

/**
 * Phase 8 (UI) of the full vulnerability + exception E2E test.
 *
 * Assumes the shell driver (scriptpp/test/test-e2e-vuln-exception-full.sh) has
 * already created the testbed via MCP:
 *   - Users:  e2etestuser1, e2etestuser2  (USER + VULN + REQ roles)
 *   - Assets: testasset1 (owner=user1), testasset2 (owner=user2)
 *   - Vulns:  vuln1 on asset1 (40d, CRITICAL, overdue),
 *             vuln2 on both assets (5d, CRITICAL)
 *   - Exception requests:
 *       APPROVED on vuln1 (id = E2E_REQ_APPROVE_ID)
 *       REJECTED on vuln2-asset2 (id = E2E_REQ_REJECT_ID)
 *
 * The spec verifies the UI reflects that state: scoped visibility, the admin
 * approval dashboard, and the user's "my requests" view.
 */

const required = [
    'SECMAN_ADMIN_NAME', 'SECMAN_ADMIN_PASS',
    'E2E_USER1_NAME', 'E2E_USER1_PASS',
    'E2E_USER2_NAME', 'E2E_USER2_PASS',
    'E2E_ASSET1_NAME', 'E2E_ASSET2_NAME',
    'E2E_CVE_VULN1', 'E2E_CVE_VULN2',
    'E2E_REQ_APPROVE_ID', 'E2E_REQ_REJECT_ID',
] as const;

const missing = required.filter((k) => !process.env[k]);
if (missing.length > 0) {
    throw new Error(
        `Missing required env vars for vuln-exception-full.spec.ts: ${missing.join(', ')}. ` +
        `Run via scriptpp/test/test-e2e-vuln-exception-full.sh which sets them.`,
    );
}

const ADMIN = { user: process.env.SECMAN_ADMIN_NAME!, pass: process.env.SECMAN_ADMIN_PASS! };
const USER1 = { user: process.env.E2E_USER1_NAME!,    pass: process.env.E2E_USER1_PASS! };
const USER2 = { user: process.env.E2E_USER2_NAME!,    pass: process.env.E2E_USER2_PASS! };

const ASSET1 = process.env.E2E_ASSET1_NAME!;
const ASSET2 = process.env.E2E_ASSET2_NAME!;
const CVE_V1 = process.env.E2E_CVE_VULN1!;
const CVE_V2 = process.env.E2E_CVE_VULN2!;
const REQ_APPROVE_ID = process.env.E2E_REQ_APPROVE_ID!;
const REQ_REJECT_ID  = process.env.E2E_REQ_REJECT_ID!;

async function login(page: Page, username: string, password: string) {
    await page.goto('/login');
    await page.waitForLoadState('domcontentloaded');
    const submitBtn = page.locator('button[type="submit"]');
    await submitBtn.waitFor({ state: 'visible', timeout: 15_000 });
    await page.waitForTimeout(1000); // React hydration
    await page.locator('#username').fill(username);
    await page.locator('#password').fill(password);
    await submitBtn.click();
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30_000 });
}

async function logout(page: Page) {
    await page.evaluate(() => localStorage.clear());
    await page.context().clearCookies();
}

test.describe.serial('Vulnerability + exception lifecycle (UI)', () => {

    test('admin sees both test assets and all CVEs in vulnerability list', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        await page.goto('/vulnerabilities/current');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        const body = (await page.textContent('body')) ?? '';
        // Both CVEs should be on the page (admin sees everything).
        expect(body).toContain(CVE_V1);
        expect(body).toContain(CVE_V2);
        // Both assets are referenced (vuln rows show asset names).
        expect(body).toContain(ASSET1);
        expect(body).toContain(ASSET2);

        await logout(page);
    });

    test('admin sees testasset1 in the outdated/overdue assets view', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        await page.goto('/outdated-assets');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        const body = (await page.textContent('body')) ?? '';
        expect(body).toContain(ASSET1);
        // testasset2 has only a 5-day vuln, so it must NOT appear as overdue.
        expect(body).not.toContain(ASSET2);

        await logout(page);
    });

    test('e2etestuser1 sees only their own scoped assets/vulnerabilities', async ({ page }) => {
        await login(page, USER1.user, USER1.pass);

        await page.goto('/account-vulns');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        const body = (await page.textContent('body')) ?? '';
        // user1 owns testasset1 — sees vuln1 + vuln2 on it
        expect(body).toContain(CVE_V1);
        expect(body).toContain(ASSET1);
        // user1 must NOT see testasset2 (owned by user2)
        expect(body).not.toContain(ASSET2);

        await logout(page);
    });

    test('e2etestuser2 sees only their own scoped assets/vulnerabilities', async ({ page }) => {
        await login(page, USER2.user, USER2.pass);

        await page.goto('/account-vulns');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        const body = (await page.textContent('body')) ?? '';
        // user2 owns testasset2 — sees vuln2 there. Must NOT see asset1 or vuln1.
        expect(body).toContain(ASSET2);
        expect(body).not.toContain(ASSET1);
        expect(body).not.toContain(CVE_V1);

        await logout(page);
    });

    test('e2etestuser1 sees their APPROVED exception request on My Exception Requests', async ({ page }) => {
        await login(page, USER1.user, USER1.pass);

        await page.goto('/my-exception-requests');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        const body = (await page.textContent('body')) ?? '';
        // The approved request references vuln1 / asset1
        expect(body).toContain(CVE_V1);
        expect(body).toMatch(/APPROVED|Approved/);

        await logout(page);
    });

    test('e2etestuser2 sees their REJECTED exception request on My Exception Requests', async ({ page }) => {
        await login(page, USER2.user, USER2.pass);

        await page.goto('/my-exception-requests');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        const body = (await page.textContent('body')) ?? '';
        expect(body).toContain(CVE_V2);
        expect(body).toMatch(/REJECTED|Rejected/);

        await logout(page);
    });

    test('admin exception-approvals dashboard reflects the reviewed requests', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        await page.goto('/exception-approvals');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        const body = (await page.textContent('body')) ?? '';
        // Both reviewed requests should be present (the dashboard usually shows
        // all states or supports filtering — we just look for the CVEs).
        expect(body).toContain(CVE_V1);
        expect(body).toContain(CVE_V2);

        await logout(page);
    });
});
