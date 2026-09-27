import { test, expect } from '@playwright/test';

// Invoked by the isolated MCP lifecycle driver before its fixture cleanup.
test('submitted MCP answers have the same advisory result in the accessible UI', async ({ page }) => {
  test.skip(!process.env.RA_ASSESSMENT_ID, 'Requires the isolated MCP lifecycle fixture');
  const id = process.env.RA_ASSESSMENT_ID!;
  const requirement = process.env.RA_REQUIREMENT_ID!;
  await page.goto('/login');
  await page.locator('input[name="username"], input#username').first().fill(process.env.SECMAN_ADMIN_NAME!);
  await page.locator('input[name="password"], input#password').first().fill(process.env.SECMAN_ADMIN_PASS!);
  await page.getByRole('button', { name: 'Login', exact: true }).click();
  await page.waitForURL(url => !url.pathname.includes('login'));
  await page.goto('/risk-assessments');
  const rows = page.locator('tbody tr');
  const row = rows.filter({ hasText: 'e2e-mcp-ra-usecase' }).first();
  const analyze = row.getByRole('button', { name: 'Analyze answers', exact: true });
  await analyze.click();
  const dialog = page.getByRole('dialog', { name: /Analyze answers/ });
  await expect(dialog.getByLabel('Recommendation: Not OK')).toBeVisible();
  await expect(dialog.getByText('Advisory — human approval is still required')).toBeVisible();
  await expect(dialog.getByText('Comment: Holistic MCP answer')).toBeVisible();
  await expect(dialog.getByText(/Answer revision \d+ · Policy v2.0/)).toBeVisible();
  await dialog.getByRole('button', { name: 'Close', exact: true }).last().focus();
  await page.keyboard.press('Tab');
  await expect(dialog.getByRole('button', { name: 'Close', exact: true }).first()).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(analyze).toBeFocused();

  await analyze.click();
  await expect(dialog.getByLabel('Recommendation: Not OK')).toBeVisible();
  // Simulate a subsequent revision arriving on the read endpoint; no answers are modified.
  await page.route(`**/api/risk-assessments/${id}`, async route => {
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({ response, json: { ...body, answerRevision: body.answerRevision + 1 } });
  });
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(dialog.getByRole('alert')).toContainText('The answers changed');
  await expect(dialog.getByLabel('Recommendation: Not OK')).not.toBeVisible();
  await page.unroute(`**/api/risk-assessments/${id}`);
  await dialog.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(dialog.getByLabel('Recommendation: Not OK')).toBeVisible();
  await dialog.getByRole('button', { name: /Requirement #|REQ|e2e-mcp-ra/i }).first().click();
  await expect(page.locator(`#assessment-answer-${requirement}`)).toBeFocused();
});
