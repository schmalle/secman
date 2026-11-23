import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { markAssetObserved, requireIsolatedTarget } from './helpers/isolated-target';
import { loginViaApi } from './helpers/session';

test.use({ trace: 'off', screenshot: 'off' });

test('UI, CLI and delegated MCP review the same immutable batch', async ({ page, request }) => {
  requireIsolatedTarget();
  const backend = process.env.SECMAN_E2E_BACKEND_URL!;
  const account = '000000000007';
  const reason = 'The application owner has approved a temporary migration window for these versions.';
  const through = new Date(Date.now() + 90 * 86400000).toISOString().slice(0, 10);
  async function mcp(name: string, args: object) {
    const response = await request.post(backend + '/api/mcp/tools/call', {
      headers: { 'X-MCP-API-Key': process.env.SECMAN_MCP_KEY!, 'X-MCP-User-Email': process.env.SECMAN_ADMIN_EMAIL! },
      data: { jsonrpc: '2.0', id: randomUUID(), method: 'tools/call', params: { name, arguments: args } },
    });
    const result = await response.json();
    if (!response.ok() || result.error || result.result?.isError) throw new Error(`MCP ${name} failed: ${result.error?.message ?? response.status()}`);
    const content = result.result.content;
    return Array.isArray(content) ? JSON.parse(content[0].text) : content;
  }
  const asset = await mcp('create_asset', { name: 'batch-e2e', type: 'SERVER', owner: 'batch', cloudAccountId: account });
  markAssetObserved(asset.id);
  const preview = await mcp('preview_product_exception_batch', { manifest: {
    version: 1, accounts: [account], products: { exact: ['Tomcat'], glob: ['SQL Server 202*'] }, reason, through,
  } });
  const jar = resolve('../../src/cli/build/libs/cli-0.1.0-all.jar');
  function cli(...args: string[]) {
    return JSON.parse(execFileSync('java', ['-jar', jar, 'exception', 'batch', ...args, '--output', 'json'], {
      encoding: 'utf8', env: { ...process.env, SECMAN_HOST: backend, SECMAN_BACKEND_URL: backend },
    }));
  }
  const cliPreview = cli('preview', '--account', account, '--exact', 'Tomcat', '--glob', 'SQL Server 202*', '--reason', reason, '--through', through);
  expect(cliPreview.impact.draft).toEqual(preview.impact.draft);
  const requestKey = randomUUID();
  const receipt = cli('submit', '--preview-id', preview.previewId, '--request-key', requestKey);
  expect(receipt.status).toBe('APPROVED');
  expect((await mcp('submit_product_exception_batch', { previewId: preview.previewId, requestKey })).batchId).toBe(receipt.batchId);
  await loginViaApi(page);
  await page.goto('/vulnerabilities/exceptions?batch=' + receipt.batchId);
  await expect(page.getByRole('heading', { name: 'APPROVED · ' + receipt.batchId })).toBeVisible();
  await page.getByRole('button', { name: 'cancel', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'CANCELLED · ' + receipt.batchId })).toBeVisible();
  expect((await mcp('get_product_exception_batch', { batchId: receipt.batchId })).status).toBe('CANCELLED');
  await page.getByRole('checkbox', { name: account }).check();
  await page.getByLabel('Exact product labels, one per line').fill('Tomcat');
  await page.getByLabel('Wildcard product labels, one per line').fill('SQL Server 202*');
  await page.getByLabel('Through date').fill(through);
  await page.getByLabel('Business justification').fill(reason);
  await page.getByRole('button', { name: 'Preview impact' }).click();
  await expect(page.getByText('No current matches.', { exact: false }).first()).toBeVisible();
  await page.getByRole('button', { name: 'Create approved exception' }).click();
  await expect(page.getByRole('heading', { name: /^APPROVED ·/ })).toBeVisible();
});
