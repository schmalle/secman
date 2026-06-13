import { test, expect, type Locator, type Page } from '@playwright/test';
import fs from 'node:fs';

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
    'E2E_EXCEPTION_MATRIX_FILE',
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
const MATRIX_FILE = process.env.E2E_EXCEPTION_MATRIX_FILE!;

type ExceptionSubject = 'ALL_VULNS' | 'PRODUCT' | 'CVE';
type ExceptionScope = 'GLOBAL' | 'IP' | 'ASSET' | 'AWS_ACCOUNT';
type MatrixAction = 'approve' | 'reject';

interface MatrixCase {
    key: string;
    action: MatrixAction;
    expectedStatus: 'APPROVED' | 'REJECTED';
    subject: ExceptionSubject;
    scope: ExceptionScope;
    subjectValue: string | null;
    scopeValue: string | null;
    reasonMarker: string;
    reason: string;
    requesterUsername: string;
    requesterEmail: string;
    requestId: number;
    vulnerabilityId: number;
    cve: string;
    assetId: number;
    assetName: string;
    assetIp: string;
    awsAccount: string | null;
    product: string;
}

interface MatrixFixture {
    requesterUsername: string;
    requesterEmail: string;
    forbidden: { subject: ExceptionSubject; scope: ExceptionScope };
    cases: MatrixCase[];
}

const matrixFixture: MatrixFixture = JSON.parse(fs.readFileSync(MATRIX_FILE, 'utf8'));
const matrixCases = matrixFixture.cases;
const approveCases = matrixCases.filter((c) => c.action === 'approve');
const rejectCases = matrixCases.filter((c) => c.action === 'reject');

if (matrixCases.length !== 22 || approveCases.length !== 11 || rejectCases.length !== 11) {
    throw new Error(`Invalid matrix fixture ${MATRIX_FILE}: expected 22 cases, got ${matrixCases.length}`);
}

const SUBJECT_BADGE_LABELS: Record<ExceptionSubject, string> = {
    ALL_VULNS: 'All vulnerabilities',
    PRODUCT: 'Product',
    CVE: 'CVE',
};

const SCOPE_BADGE_LABELS: Record<ExceptionScope, string> = {
    GLOBAL: 'All assets',
    IP: 'IP scope',
    ASSET: '1 asset',
    AWS_ACCOUNT: 'AWS account',
};

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


async function waitForResponseOrVisible(
    page: Page,
    responsePredicate: Parameters<Page['waitForResponse']>[0],
    visibleLocator: ReturnType<Page['locator']>,
    timeout = 30_000
) {
    if (await visibleLocator.isVisible().catch(() => false)) {
        return;
    }
    await Promise.race([
        page.waitForResponse(responsePredicate, { timeout }),
        visibleLocator.waitFor({ state: 'visible', timeout }),
    ]);
    await expect(visibleLocator).toBeVisible();
}

async function waitForSystemVulnerabilitiesReady(page: Page) {
    await waitForResponseOrVisible(
        page,
        (response) => response.url().includes('/api/vulnerabilities/current/system') && response.request().method() === 'GET' && response.ok(),
        page.locator('table')
    );
}

async function waitForCurrentVulnerabilitiesReady(page: Page) {
    await waitForResponseOrVisible(
        page,
        (response) =>
            response.url().includes('/api/vulnerabilities/current') &&
            !response.url().includes('/api/vulnerabilities/current/system') &&
            response.request().method() === 'GET' &&
            response.ok(),
        page.getByRole('button', { name: /request exception/i }).first()
    );
}

async function waitForOutdatedAssetsReady(page: Page) {
    await waitForResponseOrVisible(
        page,
        (response) => response.url().includes('/api/outdated-assets') && response.request().method() === 'GET' && response.ok(),
        page.locator('table')
    );
}

async function waitForMyExceptionRequestsReady(page: Page) {
    await waitForResponseOrVisible(
        page,
        (response) => response.url().includes('/api/vulnerability-exception-requests/my') && response.request().method() === 'GET' && response.ok(),
        page.locator('table')
    );
}

async function waitForAccountVulnsReady(page: Page) {
    await waitForResponseOrVisible(
        page,
        (response) => response.url().includes('/api/assets/by-cloud-account') && response.request().method() === 'GET' && response.ok(),
        page.locator('main')
    );
    await expect.poll(async () => (await page.textContent('body')) ?? '').toContain('AWS Account');
}

async function waitForVulnerabilityExceptionsReady(page: Page) {
    await waitForResponseOrVisible(
        page,
        (response) => response.url().includes('/api/vulnerability-exceptions') && response.request().method() === 'GET' && response.ok(),
        page.getByText('Vulnerability Exceptions')
    );
}

async function waitForPendingExceptionRequestsReady(page: Page) {
    await waitForResponseOrVisible(
        page,
        (response) =>
            response.url().includes('/api/vulnerability-exception-requests/pending') &&
            response.request().method() === 'GET' &&
            response.ok(),
        page.locator('main')
    );
}

async function waitForAwsAccountSharingReady(page: Page) {
    await waitForResponseOrVisible(
        page,
        (response) => response.url().includes('/api/aws-account-sharing') && response.request().method() === 'GET' && response.ok(),
        page.getByRole('heading', { name: /aws account sharing/i })
    );
    // Fallback: selected-account badge text can lag behind row render in CI.
    await page.waitForTimeout(500);
}

async function logout(page: Page) {
    await page.evaluate(() => localStorage.clear());
    await page.context().clearCookies();
}

function rowForApproval(page: Page, requestId: number): Locator {
    return page.getByTestId(`exception-approval-row-${requestId}`);
}

function rowForMyRequest(page: Page, requestId: number): Locator {
    return page.getByTestId(`my-exception-request-row-${requestId}`);
}

async function setSelectToValue(select: Locator, value: string) {
    await expect(select).toBeVisible();
    await select.selectOption(value);
    await expect(select).toHaveValue(value);
}

async function expectSelectOptionValues(select: Locator, expected: string[]) {
    await expect.poll(async () =>
        await select.locator('option').evaluateAll((options) =>
            options.map((option) => (option as HTMLOptionElement).value)
        )
    ).toEqual(expected);
}

function futureDateTimeLocal(days = 45): string {
    const date = new Date();
    date.setDate(date.getDate() + days);
    return date.toISOString().slice(0, 16);
}

function requestStatusLabel(status: 'APPROVED' | 'REJECTED'): RegExp {
    return status === 'APPROVED' ? /APPROVED|Approved|Excepted/ : /REJECTED|Rejected/;
}

async function setApprovalPageSize(page: Page, size: string) {
    const pageSize = page.locator('#pageSize');
    await expect(pageSize).toBeVisible();
    await pageSize.selectOption(size);
    await waitForPendingExceptionRequestsReady(page);
}

async function approveCaseThroughUi(page: Page, item: MatrixCase) {
    const row = rowForApproval(page, item.requestId);
    await expect(row).toBeVisible();
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByTestId(`exception-approval-quick-approve-${item.requestId}`).click();
    await expect(row).toHaveCount(0, { timeout: 15_000 });
}

async function rejectCaseThroughUi(page: Page, item: MatrixCase) {
    const row = rowForApproval(page, item.requestId);
    await expect(row).toBeVisible();
    await page.getByTestId(`exception-approval-quick-reject-${item.requestId}`).click();
    const modal = page.getByRole('dialog', { name: /review exception request/i });
    await expect(modal).toBeVisible();

    await modal.getByRole('button', { name: /Reject Request/ }).click();
    await expect(page.getByText(/Rejection comment is required/i)).toBeVisible();

    await modal.locator('#rejectComment').fill(`UI rejection for matrix case ${item.key}`);
    await modal.getByRole('button', { name: /Reject Request/ }).click();
    await expect(page.getByText(/Confirm Rejection/i)).toBeVisible();
    await modal.getByRole('button', { name: /Confirm Reject/ }).click();
    await expect(modal).toHaveCount(0, { timeout: 15_000 });
    await expect(row).toHaveCount(0, { timeout: 15_000 });
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
        await waitForSystemVulnerabilitiesReady(page);

        let body = (await page.textContent('body')) ?? '';
        // testasset1 has both CVEs (vuln1 and vuln2).
        expect(body).toContain(CVE_V1);
        expect(body).toContain(CVE_V2);
        expect(body).toContain(ASSET1);

        await page.goto(`/vulnerabilities/system?hostname=${encodeURIComponent(ASSET2)}`);
        await page.waitForLoadState('domcontentloaded');
        await waitForSystemVulnerabilitiesReady(page);

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
        await waitForOutdatedAssetsReady(page);

        // The outdated-assets page paginates over potentially thousands of
        // overdue assets. Use the in-page search to scope to our test asset
        // so the assertion is deterministic. The search is button-based —
        // filling the field alone does not apply the filter.
        const search = page.locator('#searchTerm');
        await search.waitFor({ state: 'visible', timeout: 10_000 });
        await search.fill(ASSET1);
        await page.getByRole('button', { name: /^Search$/ }).click();
        await waitForOutdatedAssetsReady(page);

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
        await waitForSystemVulnerabilitiesReady(page);

        const body = (await page.textContent('body')) ?? '';
        expect(body).toContain(CVE_V1);
        expect(body).toContain(ASSET1);

        await logout(page);
    });

    test('e2etestuser2 can view their own asset vulnerabilities via system page', async ({ page }) => {
        await login(page, USER2.user, USER2.pass);

        await page.goto(`/vulnerabilities/system?hostname=${encodeURIComponent(ASSET2)}`);
        await page.waitForLoadState('domcontentloaded');
        await waitForSystemVulnerabilitiesReady(page);

        const body = (await page.textContent('body')) ?? '';
        expect(body).toContain(CVE_V2);
        expect(body).toContain(ASSET2);

        await logout(page);
    });

    test('e2etestuser1 sees their APPROVED exception request on My Exception Requests', async ({ page }) => {
        await login(page, USER1.user, USER1.pass);

        await page.goto('/my-exception-requests');
        await page.waitForLoadState('domcontentloaded');
        await waitForMyExceptionRequestsReady(page);
        await page.locator('#pageSize').selectOption('50');
        await waitForMyExceptionRequestsReady(page);

        // The approved request references vuln1 / asset1
        const row = rowForMyRequest(page, Number(REQ_APPROVE_ID));
        await expect(row).toBeVisible();
        await expect(row).toContainText(CVE_V1);
        await expect(row).toContainText(requestStatusLabel('APPROVED'));

        await logout(page);
    });

    test('e2etestuser2 sees their REJECTED exception request on My Exception Requests', async ({ page }) => {
        await login(page, USER2.user, USER2.pass);

        await page.goto('/my-exception-requests');
        await page.waitForLoadState('domcontentloaded');
        await waitForMyExceptionRequestsReady(page);

        const row = rowForMyRequest(page, Number(REQ_REJECT_ID));
        await expect(row).toBeVisible();
        await expect(row).toContainText(CVE_V2);
        await expect(row).toContainText(/REJECTED|Rejected/);

        await logout(page);
    });

    test('direct exception form exposes all valid subject and scope controls', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        await page.goto('/vulnerabilities/exceptions');
        await page.waitForLoadState('domcontentloaded');
        await waitForVulnerabilityExceptionsReady(page);

        await page.getByRole('button', { name: /create exception/i }).click();
        await expect(page.getByRole('heading', { name: /create exception/i })).toBeVisible();

        const subject = page.getByTestId('exception-subject-select');
        const scope = page.getByTestId('exception-scope-select');
        await expect.poll(async () => await scope.locator('option').count()).toBeGreaterThan(0);

        await expect(subject.locator('option')).toHaveText([
            'all vulnerabilities',
            'vulnerabilities for product',
            'vulnerabilities matching CVE',
        ]);

        await setSelectToValue(subject, 'PRODUCT');
        await expect(page.getByPlaceholder(/Apache HTTP Server/i)).toBeVisible();
        await expectSelectOptionValues(scope, ['GLOBAL', 'IP', 'ASSET', 'AWS_ACCOUNT']);

        await setSelectToValue(scope, 'IP');
        await expect(page.getByTestId('exception-ip-input')).toBeVisible();

        await setSelectToValue(scope, 'ASSET');
        await expect(page.getByTestId('exception-asset-select')).toBeVisible();

        await setSelectToValue(scope, 'AWS_ACCOUNT');
        await expect(page.getByTestId('exception-aws-account-input')).toBeVisible();
        await expect(page.getByTestId('exception-aws-account-select')).toBeVisible();

        await setSelectToValue(subject, 'CVE');
        await expect(page.getByTestId('exception-cve-input')).toBeVisible();
        await expectSelectOptionValues(scope, ['GLOBAL', 'IP', 'ASSET', 'AWS_ACCOUNT']);

        await setSelectToValue(subject, 'ALL_VULNS');
        await expectSelectOptionValues(scope, ['IP', 'ASSET', 'AWS_ACCOUNT']);
        await expect(scope.locator('option[value="GLOBAL"]')).toHaveCount(0);

        await setSelectToValue(scope, 'IP');
        await page.getByTestId('exception-ip-input').fill(approveCases.find((item) => item.scope === 'IP')!.assetIp);
        await page.locator('#expirationDate').fill(futureDateTimeLocal());
        await page.locator('#reason').fill('E2E direct form control validation for all vulnerabilities impact preview before creating anything.');
        await page.getByRole('button', { name: /preview & create/i }).click();
        await expect(page.getByRole('heading', { name: /exception impact preview/i })).toBeVisible();
        await page.locator('.modal[role="dialog"]').filter({ hasText: 'Exception Impact Preview' })
            .getByRole('button', { name: 'Close' })
            .click();

        await logout(page);
    });

    test('admin approval dashboard shows every matrix request with subject and scope context', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        await page.goto('/exception-approvals');
        await page.waitForLoadState('domcontentloaded');
        await waitForPendingExceptionRequestsReady(page);
        await setApprovalPageSize(page, '50');

        for (const item of matrixCases) {
            const row = rowForApproval(page, item.requestId);
            await expect(row).toBeVisible();
            await expect(row).toContainText(item.requesterUsername);
            await expect(row).toContainText(item.reasonMarker);
            await expect(page.getByTestId(`exception-approval-subject-${item.requestId}`).locator('.badge'))
                .toHaveText(SUBJECT_BADGE_LABELS[item.subject]);
            await expect(page.getByTestId(`exception-approval-scope-${item.requestId}`).locator('.badge'))
                .toHaveText(SCOPE_BADGE_LABELS[item.scope]);
            if (item.subjectValue) {
                await expect(row).toContainText(item.subjectValue);
            }
            if (item.scopeValue) {
                await expect(row).toContainText(item.scopeValue);
            }
            if (item.scope === 'ASSET') {
                await expect(row).toContainText(item.assetName);
            }
            await expect(page.getByTestId(`exception-approval-quick-approve-${item.requestId}`)).toBeVisible();
            await expect(page.getByTestId(`exception-approval-quick-reject-${item.requestId}`)).toBeVisible();
        }

        await logout(page);
    });

    test('admin approves and rejects every matrix request through the approval UI', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        await page.goto('/exception-approvals');
        await page.waitForLoadState('domcontentloaded');
        await waitForPendingExceptionRequestsReady(page);
        await setApprovalPageSize(page, '50');

        for (const item of approveCases) {
            await approveCaseThroughUi(page, item);
        }
        for (const item of rejectCases) {
            await rejectCaseThroughUi(page, item);
        }

        for (const item of matrixCases) {
            await expect(rowForApproval(page, item.requestId)).toHaveCount(0);
        }

        await logout(page);
    });

    test('user1 sees final APPROVED and REJECTED matrix states on My Exception Requests', async ({ page }) => {
        await login(page, USER1.user, USER1.pass);

        await page.goto('/my-exception-requests');
        await page.waitForLoadState('domcontentloaded');
        await waitForMyExceptionRequestsReady(page);
        await page.locator('#pageSize').selectOption('50');
        await waitForMyExceptionRequestsReady(page);

        for (const item of matrixCases) {
            const row = rowForMyRequest(page, item.requestId);
            await expect(row).toBeVisible();
            await expect(row).toContainText(requestStatusLabel(item.expectedStatus));
            await expect(page.getByTestId(`my-exception-request-subject-${item.requestId}`).locator('.badge'))
                .toHaveText(SUBJECT_BADGE_LABELS[item.subject]);
            await expect(page.getByTestId(`my-exception-request-scope-${item.requestId}`).locator('.badge'))
                .toHaveText(SCOPE_BADGE_LABELS[item.scope]);
            if (item.subjectValue) {
                await expect(row).toContainText(item.subjectValue);
            }
        }

        await logout(page);
    });

    test('approved matrix exceptions appear on the active list and rejected matrix requests do not', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        await page.goto('/vulnerabilities/exceptions');
        await page.waitForLoadState('domcontentloaded');
        await waitForVulnerabilityExceptionsReady(page);

        for (const item of approveCases) {
            await page.locator('#subjectFilter').selectOption(item.subject);
            await page.locator('#scopeFilter').selectOption(item.scope);
            const body = (await page.textContent('body')) ?? '';
            expect(body).toContain(item.reasonMarker);
        }

        await page.locator('#subjectFilter').selectOption('ALL');
        await page.locator('#scopeFilter').selectOption('ALL');

        const body = (await page.textContent('body')) ?? '';
        expect(body).toContain(CVE_V1);
        for (const item of approveCases) {
            expect(body).toContain(item.reasonMarker);
        }
        for (const item of rejectCases) {
            expect(body).not.toContain(item.reasonMarker);
        }

        await logout(page);
    });

    test('exception request modal allows AWS account scope to be typed or picked', async ({ page }) => {
        await login(page, ADMIN.user, ADMIN.pass);

        const modalCase = rejectCases[0];
        await page.route('**/api/vulnerabilities/current**', async (route) => {
            const url = new URL(route.request().url());
            if (url.pathname !== '/api/vulnerabilities/current') {
                await route.continue();
                return;
            }

            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify({
                    content: [{
                        id: modalCase.vulnerabilityId,
                        assetId: modalCase.assetId,
                        assetName: modalCase.assetName,
                        assetIp: modalCase.assetIp,
                        cloudInstanceId: null,
                        vulnerabilityId: modalCase.cve,
                        cvssSeverity: 'High',
                        vulnerableProductVersions: modalCase.product,
                        daysOpen: '9 days',
                        scanTimestamp: new Date().toISOString(),
                        hasException: false,
                        exceptionReason: null,
                        ageInDays: 9,
                        overdueStatus: 'OK',
                        daysOverdue: null,
                        exceptionId: null,
                        exceptionEndDate: null,
                        exceptionSubject: null,
                        exceptionScope: null,
                        exceptionScopesAsset: null,
                    }],
                    totalElements: 1,
                    totalPages: 1,
                    currentPage: 0,
                    pageSize: 50,
                    hasNext: false,
                    hasPrevious: false,
                }),
            });
        });

        await page.goto('/vulnerabilities/current');
        await page.waitForLoadState('domcontentloaded');
        await waitForCurrentVulnerabilitiesReady(page);

        await page.getByRole('button', { name: /request exception/i }).first().click();
        await expect(page.getByRole('heading', { name: /request vulnerability exception/i })).toBeVisible();

        await page.getByLabel('Exception scope').selectOption('AWS_ACCOUNT');

        const accountInput = page.getByLabel('AWS account number');
        await expect(accountInput).toBeVisible();
        await expect(accountInput).toBeEnabled();
        await accountInput.fill(AWS_ACCOUNT_A);
        await expect(accountInput).toHaveValue(AWS_ACCOUNT_A);

        const accountPicker = page.getByLabel('Accessible AWS accounts');
        await expect(accountPicker).toContainText(AWS_ACCOUNT_B);
        await accountPicker.selectOption(AWS_ACCOUNT_B);
        await expect(accountInput).toHaveValue(AWS_ACCOUNT_B);

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
        await waitForAwsAccountSharingReady(page);

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
        await waitForAccountVulnsReady(page);

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
        await waitForAccountVulnsReady(page);

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

    // ========================================================================
    // Phase 10 import/export UI checks
    //
    // These tests are gated by env vars set by the shell driver after Phase 10
    // (export → delete-all → import round-trip). They are skipped when the
    // driver hasn't run Phase 10 so the spec remains usable in isolation.
    // ========================================================================

    test('exceptions UI shows zero rows after MCP delete-all (Phase 10.9)', async ({ page }) => {
        test.skip(
            !process.env.EXPECTED_EXCEPTION_COUNT_AFTER_DELETE,
            'Phase 10 driver did not run; skip this UI check'
        );

        await login(page, ADMIN.user, ADMIN.pass);
        await page.goto('/vulnerabilities/exceptions');
        await page.waitForLoadState('domcontentloaded');
        // Wait for the table to fully render (header label is stable).
        await waitForVulnerabilityExceptionsReady(page);

        const expectedCve = process.env.EXPECTED_EXCEPTION_CVE!;
        // No row containing the test CVE.
        await expect(page.locator(`tr:has-text("${expectedCve}")`)).toHaveCount(0);

        await logout(page);
    });

    test('exceptions UI shows the re-imported row (Phase 10.12)', async ({ page }) => {
        test.skip(
            !process.env.EXPECTED_EXCEPTION_COUNT_AFTER_IMPORT,
            'Phase 10 driver did not import; skip this UI check'
        );

        await login(page, ADMIN.user, ADMIN.pass);
        await page.goto('/vulnerabilities/exceptions');
        await page.waitForLoadState('domcontentloaded');
        await waitForVulnerabilityExceptionsReady(page);

        const expectedCve = process.env.EXPECTED_EXCEPTION_CVE!;
        await expect(page.locator(`tr:has-text("${expectedCve}")`).first()).toBeVisible();

        await logout(page);
    });
});
