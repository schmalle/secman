import { test, expect, type Page } from '@playwright/test';

/**
 * Phase 8 (UI) of the full vulnerability + exception E2E test.
 *
 * Assumes the shell driver (scripts/test/test-e2e-vuln-exception-full.sh) has
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
    'E2E_USER1_EMAIL', 'E2E_USER2_EMAIL',
    'E2E_ASSET1_NAME', 'E2E_ASSET2_NAME',
    'E2E_CVE_VULN1', 'E2E_CVE_VULN2',
    'E2E_REQ_APPROVE_ID', 'E2E_REQ_REJECT_ID',
    'E2E_AWS_ACCOUNT_A', 'E2E_AWS_ACCOUNT_B', 'E2E_AWS_ACCOUNT_C',
    'E2E_AWS_ASSET_A_NAME', 'E2E_AWS_ASSET_B_NAME', 'E2E_AWS_ASSET_C_NAME',
    'E2E_AWS_SHARING_RULE_ID',
] as const;

const missing = required.filter((k) => !process.env[k]);
if (missing.length > 0) {
    throw new Error(
        `Missing required env vars for vuln-exception-full.spec.ts: ${missing.join(', ')}. ` +
        `Run via scripts/test/test-e2e-vuln-exception-full.sh which sets them.`,
    );
}

const ADMIN = { user: process.env.SECMAN_ADMIN_NAME!, pass: process.env.SECMAN_ADMIN_PASS! };
const USER1 = { user: process.env.E2E_USER1_NAME!,    pass: process.env.E2E_USER1_PASS! };
const USER2 = { user: process.env.E2E_USER2_NAME!,    pass: process.env.E2E_USER2_PASS! };

const USER1_EMAIL = process.env.E2E_USER1_EMAIL!;
const USER2_EMAIL = process.env.E2E_USER2_EMAIL!;

const ASSET1 = process.env.E2E_ASSET1_NAME!;
const ASSET2 = process.env.E2E_ASSET2_NAME!;
const CVE_V1 = process.env.E2E_CVE_VULN1!;
const CVE_V2 = process.env.E2E_CVE_VULN2!;
const REQ_APPROVE_ID = process.env.E2E_REQ_APPROVE_ID!;
const REQ_REJECT_ID  = process.env.E2E_REQ_REJECT_ID!;

// AWS account sharing testbed (set up by Phase 8 of the shell driver)
const AWS_ACCOUNT_A = process.env.E2E_AWS_ACCOUNT_A!;
const AWS_ACCOUNT_B = process.env.E2E_AWS_ACCOUNT_B!;
const AWS_ACCOUNT_C = process.env.E2E_AWS_ACCOUNT_C!;
const AWS_ASSET_A   = process.env.E2E_AWS_ASSET_A_NAME!;
const AWS_ASSET_B   = process.env.E2E_AWS_ASSET_B_NAME!;
const AWS_ASSET_C   = process.env.E2E_AWS_ASSET_C_NAME!;
const AWS_SHARING_RULE_ID = process.env.E2E_AWS_SHARING_RULE_ID!;

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

        // /vulnerabilities/current paginates over the entire DB (potentially
        // hundreds of thousands of rows), so our two test CVEs are unlikely
        // to be on the first page. Use the per-system view to scope to our
        // test asset deterministically.
        await page.goto(`/vulnerabilities/system?hostname=${encodeURIComponent(ASSET1)}`);
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        let body = (await page.textContent('body')) ?? '';
        // testasset1 has both CVEs (vuln1 and vuln2).
        expect(body).toContain(CVE_V1);
        expect(body).toContain(CVE_V2);
        expect(body).toContain(ASSET1);

        await page.goto(`/vulnerabilities/system?hostname=${encodeURIComponent(ASSET2)}`);
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        body = (await page.textContent('body')) ?? '';
        // testasset2 has only vuln2.
        expect(body).toContain(CVE_V2);
        expect(body).toContain(ASSET2);

        await logout(page);
    });

    test('admin sees testasset1 in the outdated/overdue assets view', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        await page.goto('/outdated-assets');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        // The outdated-assets page paginates over potentially thousands of
        // overdue assets. Use the in-page search to scope to our test asset
        // so the assertion is deterministic. The search is button-based —
        // filling the field alone does not apply the filter.
        const search = page.locator('#searchTerm');
        await search.waitFor({ state: 'visible', timeout: 10_000 });
        await search.fill(ASSET1);
        await page.getByRole('button', { name: /^Search$/ }).click();
        await page.waitForTimeout(2500); // search round-trip

        const body = (await page.textContent('body')) ?? '';
        expect(body).toContain(ASSET1);
        // testasset2 has only a 5-day vuln, so it must NOT appear as overdue.
        expect(body).not.toContain(ASSET2);

        await logout(page);
    });

    test('e2etestuser1 can view their own asset vulnerabilities via system page', async ({ page }) => {
        await login(page, USER1.user, USER1.pass);

        // Note: The original test used /account-vulns which scopes by AWS account
        // mappings — our test users have none, so we use /vulnerabilities/system
        // (CrowdStrike Vulnerability Lookup) which is open to any VULN-role user
        // by design. The negative isolation assertion is verified by the MCP phases
        // (get_vulnerabilities) which DO enforce owner-based access control.
        await page.goto(`/vulnerabilities/system?hostname=${encodeURIComponent(ASSET1)}`);
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        const body = (await page.textContent('body')) ?? '';
        expect(body).toContain(CVE_V1);
        expect(body).toContain(ASSET1);

        await logout(page);
    });

    test('e2etestuser2 can view their own asset vulnerabilities via system page', async ({ page }) => {
        await login(page, USER2.user, USER2.pass);

        await page.goto(`/vulnerabilities/system?hostname=${encodeURIComponent(ASSET2)}`);
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        const body = (await page.textContent('body')) ?? '';
        expect(body).toContain(CVE_V2);
        expect(body).toContain(ASSET2);

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

    test('admin exception-approvals dashboard shows zero pending after the test reviewed all requests', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        await page.goto('/exception-approvals');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        // The dashboard only lists PENDING requests. We approved/rejected/cancelled
        // every request our test created, so the dashboard should reflect "0 pending"
        // and the empty-state message. The actual reviewed requests are visible
        // per-user via /my-exception-requests (covered by previous tests) and as
        // active exceptions on the next assertion.
        const body = (await page.textContent('body')) ?? '';
        expect(body).toMatch(/No pending requests|0\s*$|Awaiting review/);

        await logout(page);
    });

    test('approved exception appears on the active vulnerability exceptions list', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        await page.goto('/vulnerabilities/exceptions');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        const body = (await page.textContent('body')) ?? '';
        // Only CVE_V1's request was APPROVED, so an active exception exists for it.
        // CVE_V2's requests were REJECTED and CANCELLED — no exception should be created.
        expect(body).toContain(CVE_V1);

        await logout(page);
    });

    // ========================================================================
    // AWS Account Sharing UI verification
    //
    // Pre-state from Phase 8 of the shell driver:
    //   user1 mappings: AWS_ACCOUNT_A, AWS_ACCOUNT_C
    //   user2 mappings: AWS_ACCOUNT_B
    //   sharing rule:   user1 -> user2, scoped to [AWS_ACCOUNT_A] only
    //
    // Therefore the UI must show:
    //   user1 in /account-vulns -> A and C (own), NOT B
    //   user2 in /account-vulns -> A (shared) and B (own), NOT C (scope-leak guard)
    //   admin in /aws-account-sharing -> the rule with both users' emails
    // ========================================================================

    test('admin AWS account sharing dashboard lists the scoped rule', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        await page.goto('/aws-account-sharing');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2500);

        const body = (await page.textContent('body')) ?? '';
        // Both endpoints of the sharing rule are visible in the row text.
        expect(body).toContain(USER1_EMAIL);
        expect(body).toContain(USER2_EMAIL);
        // Per-account scoping renders a "selected" badge with the count (1).
        expect(body.toLowerCase()).toContain('selected');

        await logout(page);
    });

    test('user1 sees their own AWS accounts (A and C) on /account-vulns', async ({ page }) => {
        await login(page, USER1.user, USER1.pass);

        await page.goto('/account-vulns');
        await page.waitForLoadState('domcontentloaded');
        // Account-vulns paints account groups + assets after a fetch round-trip.
        await page.waitForTimeout(3500);

        const body = (await page.textContent('body')) ?? '';
        // user1 has direct mappings to A and C; both account ids and both asset
        // names (testaws-a, testaws-c) must be visible.
        expect(body).toContain(AWS_ACCOUNT_A);
        expect(body).toContain(AWS_ACCOUNT_C);
        expect(body).toContain(AWS_ASSET_A);
        expect(body).toContain(AWS_ASSET_C);
        // user1 has no mapping or sharing for account B — must NOT appear.
        expect(body).not.toContain(AWS_ACCOUNT_B);
        expect(body).not.toContain(AWS_ASSET_B);

        await logout(page);
    });

    test('user2 sees account A (shared) and B (own) but NOT C on /account-vulns', async ({ page }) => {
        await login(page, USER2.user, USER2.pass);

        await page.goto('/account-vulns');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(3500);

        const body = (await page.textContent('body')) ?? '';
        // user2 owns account B and has been granted account A via the scoped rule.
        expect(body).toContain(AWS_ACCOUNT_A);
        expect(body).toContain(AWS_ACCOUNT_B);
        expect(body).toContain(AWS_ASSET_A);
        expect(body).toContain(AWS_ASSET_B);
        // CRITICAL scope guard: account C was added to user1's mappings AFTER the
        // sharing rule was created. The rule was scoped to A only, so C must
        // NOT propagate to user2 — neither the account id nor the asset name.
        expect(body).not.toContain(AWS_ACCOUNT_C);
        expect(body).not.toContain(AWS_ASSET_C);

        await logout(page);
    });
});
