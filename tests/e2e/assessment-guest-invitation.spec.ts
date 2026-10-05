import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { requireIsolatedTarget } from './helpers/isolated-target';

// URLs contain bearer tokens; do not retain them in traces or screenshots.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

test('external respondent invitation opens questions without login', async ({ page, browser }) => {
  requireIsolatedTarget();
  const suffix = randomUUID().replaceAll('-', '');
  const email = `guest-${suffix}@example.test`;
  const title = `Guest invitation ${suffix}`;
  await page.goto('/login');
  await page.locator('#username').fill(process.env.SECMAN_ADMIN_NAME!);
  await page.locator('#password').fill(process.env.SECMAN_ADMIN_PASS!);
  await page.getByRole('button', { name: 'Login', exact: true }).click();
  await page.waitForURL(url => !url.pathname.includes('/login'));

  const call = async (path: string, data?: unknown) => {
    const result = await page.evaluate(async ({ path, data }) => {
      const csrf = document.cookie.split('; ').find(item => item.startsWith('PLAY_CSRF_TOKEN='))?.split('=').slice(1).join('=');
      const response = await fetch(path, {
        method: data === undefined ? 'GET' : 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json', ...(csrf ? { 'Csrf-Token': decodeURIComponent(csrf) } : {}) },
        ...(data === undefined ? {} : { body: JSON.stringify(data) }),
      });
      return { status: response.status, body: await response.json() };
    }, { path, data });
    expect(result.status, `Fixture API ${path}`).toBeGreaterThanOrEqual(200);
    expect(result.status, `Fixture API ${path}`).toBeLessThan(300);
    return result.body;
  };
  const actor = await call('/api/auth/status');
  const useCase = await call('/api/usecases', { name: title });
  const requirement = await call('/api/requirements', {
    shortreq: title, details: 'Confirm guest questionnaire access.', language: 'en',
    norm: 'E2E', chapter: 'GUEST', usecaseIds: [useCase.id],
  });
  const assessment = await call('/api/risk-assessments', {
    assessmentBasisType: 'SAAS', solutionName: title,
    assessorRef: { id: actor.id }, respondentRef: { email },
    endDate: new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10),
    useCaseIds: [useCase.id],
  });
  expect(Number.isSafeInteger(assessment.id)).toBe(true);
  const outcome = await call(`/api/risk-assessments/${assessment.id}/notify`, { email });
  expect(outcome.sent, 'Invitation must be accepted by the isolated SMTP sink').toBe(true);

  // The sink discards bodies. Read only this fixture's issued capability using
  // the runner's restricted DB user; never print it or expose it via a new API.
  const sql = (statement: string) => execFileSync('mariadb', [
    '--batch', '--skip-column-names', '-h', '127.0.0.1', '-u', process.env.DB_USER!,
    process.env.SECMAN_TEST_ISOLATED_DB!, '-e', statement,
  ], { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.DB_PASS }, stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  expect(sql(`SELECT COUNT(*) FROM users WHERE email='${email}'`)).toBe('0');
  const token = sql(`SELECT token FROM assessment_token WHERE risk_assessment_id=${assessment.id} AND email='${email}' AND is_used=0 ORDER BY id DESC LIMIT 1`);
  expect(/^[a-f0-9]{32}$/.test(token), 'Notification must create an invitation token').toBe(true);

  const guest = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  try {
    const guestPage = await guest.newPage();
    const loginVisits: boolean[] = [];
    guestPage.on('framenavigated', frame => {
      if (frame === guestPage.mainFrame() && new URL(frame.url()).pathname.startsWith('/login')) loginVisits.push(true);
    });
    const apiResponse = guestPage.waitForResponse(response =>
      new URL(response.url()).pathname === `/api/responses/assessment/${token}`,
    );
    await guestPage.goto(`${process.env.SECMAN_E2E_FRONTEND_URL}/respond/${token}`);
    const response = await apiResponse;
    expect(response.status(), 'Anonymous questionnaire API must succeed').toBe(200);
    const body = await response.json();
    expect(body.responses, 'Unanswered questionnaire must include an empty array').toEqual([]);
    expect(body.requirements.map((row: { id: number }) => row.id)).toContain(requirement.id);
    await expect(guestPage.getByRole('heading', { name: 'Risk Assessment Response', exact: true })).toBeVisible();
    await expect(guestPage.getByRole('heading', { name: `1. ${title}`, exact: true })).toBeVisible();
    await expect(guestPage.locator('#username, #password')).toHaveCount(0);
    await expect(guestPage.getByRole('alert').filter({ hasText: 'data.responses.find' })).toHaveCount(0);
    // Older API deployments omitted empty responses; loading them must also work.
    await guestPage.route(`**/api/responses/assessment/${token}`, async route => {
      const response = await route.fetch();
      const data = await response.json();
      delete data.responses;
      await route.fulfill({ response, json: data });
    });
    await guestPage.reload();
    await expect(guestPage.getByRole('heading', { name: 'Risk Assessment Response', exact: true })).toBeVisible();
    await expect(guestPage.getByRole('alert').filter({ hasText: 'data.responses.find' })).toHaveCount(0);
    await guestPage.unroute(`**/api/responses/assessment/${token}`);
    // A real save demonstrates that hydration completed and a late login
    // redirect cannot be mistaken for a successful server-rendered page.
    await guestPage.locator(`label[for="yes-${requirement.id}"]`).click();
    await guestPage.locator('textarea').first().fill('Guest can answer without a SecMan account.');
    const saved = guestPage.waitForResponse(response =>
      new URL(response.url()).pathname === `/api/responses/${token}/save`,
    );
    await guestPage.getByRole('button', { name: /Save Progress/ }).click();
    expect((await saved).status(), 'Guest answer save must succeed').toBe(200);
    await guestPage.reload();
    await expect(guestPage.locator(`#yes-${requirement.id}`)).toBeChecked();
    await expect(guestPage.locator('textarea').first()).toHaveValue('Guest can answer without a SecMan account.');
    await guestPage.getByRole('button', { name: /Submit Assessment/ }).click();
    await expect(guestPage.getByText('✓ Assessment Submitted Successfully')).toBeVisible();
    expect(loginVisits, 'Guest must never be sent to login').toHaveLength(0);
    expect(new URL(guestPage.url()).pathname.startsWith('/respond/')).toBe(true);
    expect((await guest.cookies()).some(cookie => cookie.name === 'secman_auth')).toBe(false);
  } catch (error) {
    // Playwright errors may contain the invitation URL; keep the report token-free.
    throw new Error(`Guest invitation regression: ${String(error).replaceAll(token, '[invitation-token]')}`);
  } finally {
    await guest.close();
  }
  // Runner deletes its entire owned schema, including fixtures from failed tests.
});
