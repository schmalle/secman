import assert from 'node:assert/strict';
import test from 'node:test';

import { componentCategoryLabel, reachabilityClass } from './webInventoryPresentation.ts';

test('component categories use concise analytic labels', () => {
  assert.equal(componentCategoryLabel('JAVASCRIPT_LIBRARY'), 'JavaScript');
  assert.equal(componentCategoryLabel('CSS_LIBRARY'), 'CSS');
  assert.equal(componentCategoryLabel('WEB_SERVER'), 'Web server');
  assert.equal(componentCategoryLabel('OTHER'), 'OTHER');
});

test('reachability states have distinct status classes', () => {
  assert.equal(reachabilityClass('REACHABLE'), 'text-bg-success');
  assert.equal(reachabilityClass('UNREACHABLE'), 'text-bg-danger');
  assert.equal(reachabilityClass('UNKNOWN'), 'text-bg-secondary');
});
