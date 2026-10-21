import assert from 'node:assert/strict';
import { test } from 'node:test';

import { normalizeIntegrationPage, normalizeIntegrationRunDetail, type IntegrationRunDetail } from './integrationsService';

test('normalizes an omitted empty content collection', () => {
  const page = normalizeIntegrationPage({
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size: 25,
  });

  assert.deepEqual(page.content, []);
});

test('preserves returned page content', () => {
  const content = [{ id: 1 }];
  const page = normalizeIntegrationPage({
    content,
    totalElements: 1,
    totalPages: 1,
    number: 0,
    size: 25,
  });

  assert.equal(page.content, content);
});


test('normalizes omitted empty historical findings and attachments', () => {
  const result = normalizeIntegrationRunDetail({ run: { id: 1 } } as IntegrationRunDetail);
  assert.deepEqual(result.findings, []);
  assert.deepEqual(result.attachments, []);
  assert.equal(result.run.id, 1);
});
