import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { requireIsolatedTarget } from './helpers/isolated-target';
import { loginViaApi } from './helpers/session';

test.use({ trace: 'off', screenshot: 'off' });

test('Create Exception applies *net*,*oracle* globally as one approved rule', async ({ page }) => {
  requireIsolatedTarget();
  function sql(statement: string) {
    return execFileSync('mariadb', ['--batch', '--skip-column-names', '-h', '127.0.0.1',
      '-u', process.env.DB_USER!, process.env.SECMAN_TEST_ISOLATED_DB!, '-e', statement], {
      encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.DB_PASS },
    }).trim();
  }
  const assetName = 'product-pattern-' + randomUUID();
  const reason = 'The product owners approved a temporary exception for this upgrade ' + assetName;
  sql(`INSERT INTO asset (name,type,owner,verified_observed_at)
       VALUES ('${assetName}','SERVER','e2e',UTC_TIMESTAMP());
       SET @asset=LAST_INSERT_ID();
       INSERT INTO vulnerability (asset_id,scan_timestamp,vulnerability_id,vulnerable_product_versions,product_labels_json,cvss_severity,first_seen_at)
       VALUES (@asset,NOW(),'CVE-2026-1001','.NET Core 6.0',NULL,'HIGH',NOW()-INTERVAL 480 DAY),
              (@asset,NOW(),'CVE-2026-1002','Oracle Client','["Oracle Client"]','HIGH',NOW()-INTERVAL 480 DAY),
              (@asset,NOW(),'CVE-2026-1003','PostgreSQL','["PostgreSQL"]','HIGH',NOW()-INTERVAL 480 DAY)`);
  await loginViaApi(page);
  const systemPage = '/vulnerabilities/system?hostname=' + assetName;
  await page.goto(systemPage);
  const netRow = page.getByRole('row').filter({ hasText: '.NET Core 6.0' });
  await expect(netRow.getByText('Not Excepted', { exact: true })).toBeVisible();
  await page.goto('/vulnerabilities/exceptions');
  await page.getByRole('button', { name: /Create Exception$/ }).click();
  const form = page.locator('.modal form');
  await form.getByLabel('Exception scope').selectOption('GLOBAL');
  await form.getByLabel('Product names or wildcard patterns').fill('*net*,*oracle*');
  await form.locator('#reason').fill(reason);
  const response = page.waitForResponse(r => r.url().endsWith('/api/vulnerability-exception-requests') && r.request().method() === 'POST');
  await form.getByRole('button', { name: /Create Exception$/ }).click();
  expect((await response).status()).toBe(201);
  await expect(page.getByRole('row').filter({ hasText: reason }).filter({ hasText: '*net*, *oracle*' })).toHaveCount(1);
  await expect.poll(() => sql(`SELECT GROUP_CONCAT(v.excepted+0 ORDER BY v.vulnerability_id)
    FROM vulnerability v JOIN asset a ON a.id=v.asset_id WHERE a.name='${assetName}'`)).toBe('1,1,0');
  await page.goto(systemPage);
  await expect(netRow.getByText('Excepted', { exact: true })).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: 'PostgreSQL' }).getByText('Not Excepted', { exact: true })).toBeVisible();
});
