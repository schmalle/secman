import assert from 'node:assert/strict';
import { test } from 'node:test';

import { normalizeIntegrationPage } from './integrationsService';

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
