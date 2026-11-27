import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('each workgroup AWS account ID links to the Assets account filter', () => {
  const modal = readFileSync(new URL('./WorkgroupAccountsModal.tsx', import.meta.url), 'utf8');
  const assets = readFileSync(new URL('./AssetManagement.tsx', import.meta.url), 'utf8');

  assert.match(modal, /<a href=\{`\/assets\?accountId=\$\{encodeURIComponent\(a\.awsAccountId\)\}`\}>\s*\{a\.awsAccountId\}\s*<\/a>/);
  assert.match(assets, /setAccountIdFilter\(params\.get\('accountId'\)/);
  assert.match(assets, /params\.set\('accountId', accountIdFilter\.trim\(\)\)/);
});
