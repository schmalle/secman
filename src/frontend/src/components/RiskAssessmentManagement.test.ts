import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('./RiskAssessmentManagement.tsx', import.meta.url), 'utf8');

test('risk assessment form supports an account-native AWS basis', () => {
  assert.match(source, /value="AWS_ACCOUNT"[\s\S]{0,180}AWS Account \(Direct Assessment\)/);
  assert.match(source, /role === 'ADMIN' \|\| role === 'SECCHAMPION'/);
  assert.match(source, /dataToSubmit\.awsAccountId = formData\.awsAccountId/);
  assert.match(source, /pattern="\[0-9\]\{12\}"/);
  assert.doesNotMatch(source, /AWS_ACCOUNT[\s\S]{0,300}dataToSubmit\.assetId/);
});
