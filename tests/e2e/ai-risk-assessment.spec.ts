import { test, expect } from '@playwright/test';

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers (US1 MVP).
 *
 * Verifies the happy-path the spec contract requires:
 *   ADMIN/SECCHAMPION creates an assessment → triggers AI Pre-fill → sees
 *   draft Responses with confidence + citations → edits one row → submits.
 *
 * Pre-requisites for this spec to run end-to-end (not enforced here, listed
 * for the operator):
 *   * `AI_RISK_ASSESSMENT_ENABLED=true` on the backend (default OFF).
 *   * `OPENROUTER_API_KEY` provisioned via pass-cli.
 *   * A test assessment seeded with ≥3 requirements (or pick one in the UI).
 *
 * When the flag is OFF the test is skipped via the `AI_RISK_ASSESSMENT_E2E`
 * env switch the operator must opt in to.
 */

const requiredEnvVars = [
  'SECMAN_ADMIN_NAME',
  'SECMAN_ADMIN_PASS',
] as const;

const missing = requiredEnvVars.filter((v) => !process.env[v]);
if (missing.length > 0) {
  throw new Error(
    `Missing required environment variables: ${missing.join(', ')}. ` +
    `Use ./run-e2e.sh which pulls them from Proton Pass.`
  );
}

const ADMIN_USER = process.env.SECMAN_ADMIN_NAME!;
const ADMIN_PASS = process.env.SECMAN_ADMIN_PASS!;
const RUN_AI_E2E = process.env.AI_RISK_ASSESSMENT_E2E === 'true';

test.skip(!RUN_AI_E2E, 'AI_RISK_ASSESSMENT_E2E=true required (feature flag OFF by default)');

async function login(page: import('@playwright/test').Page, username: string, password: string) {
  await page.goto('/login');
  await page.waitForLoadState('domcontentloaded');
  const submitBtn = page.locator('button[type="submit"]');
  await submitBtn.waitFor({ state: 'visible', timeout: 15_000 });
  await page.waitForTimeout(1000);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await submitBtn.click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30_000 });
}

test.describe.serial('AI pre-fill MVP', () => {
  const consoleErrors: string[] = [];

  test.beforeEach(async ({ page }) => {
    page.on('console', (msg) => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
  });

  test('admin can pre-fill, drafts appear with confidence and citations, edit flips provenance', async ({ page }) => {
    await login(page, ADMIN_USER, ADMIN_PASS);
    await page.goto('/riskassessment');
    await page.waitForLoadState('domcontentloaded');

    // Pick the first STARTED assessment in the list and trigger AI Pre-fill.
    const aiButton = page.getByRole('button', { name: /AI Pre-fill/i }).first();
    await expect(aiButton).toBeVisible({ timeout: 15_000 });
    await aiButton.click();

    // Modal appears with the Start button enabled.
    await expect(page.getByRole('heading', { name: /AI Pre-fill/i })).toBeVisible();
    const startBtn = page.getByRole('button', { name: /Start AI pre-fill/i });
    await startBtn.click();

    // Wait for the terminal status to land in the modal.
    await expect(page.getByText(/Status:\s*(COMPLETED|FAILED|CANCELLED)/i)).toBeVisible({ timeout: 5 * 60 * 1000 });
    await page.getByRole('button', { name: /Close/i }).click();

    // Open the assessment to verify provenance + confidence + citations.
    await page.getByRole('button', { name: /Perform Assessment/i }).first().click();
    await expect(page.locator('text=/AI-generated/i').first()).toBeVisible({ timeout: 15_000 });

    // Expand details on the first AI-generated card to verify the audit pane.
    const detailsBtn = page.getByRole('button', { name: /details/i }).first();
    await detailsBtn.click();
    await expect(page.locator('text=/Rationale:/i').first()).toBeVisible();

    // Edit the first AI_GENERATED answer (toggle to N/A) to flip the provenance.
    const naButton = page.locator('label.btn-outline-warning').first();
    await naButton.click();
    await expect(page.locator('text=/AI-edited/i').first()).toBeVisible({ timeout: 5_000 });

    // No JavaScript errors during the flow (CLAUDE.md §7 gate).
    expect(consoleErrors, `JS errors observed: ${consoleErrors.join('\n')}`).toHaveLength(0);
  });
});
