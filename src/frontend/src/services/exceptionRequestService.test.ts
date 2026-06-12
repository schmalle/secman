import assert from 'node:assert/strict';
import test from 'node:test';
import { createReviewExceptionRequestDto } from './exceptionReviewDto.ts';
import { formatExceptionRequestScope } from './exceptionRequestScopeFormatter.ts';

test('review DTO uses reviewComment expected by backend', () => {
  assert.deepEqual(createReviewExceptionRequestDto('TestTestTest'), {
    reviewComment: 'TestTestTest'
  });
});

test('request scope formatter returns distinct display labels for each scope', () => {
  assert.equal(formatExceptionRequestScope({ scope: 'ASSET', assetName: 'web-01' }).label, '1 asset');
  assert.equal(formatExceptionRequestScope({ scope: 'IP', scopeValue: '10.10.10.10' }).label, 'IP scope');
  assert.equal(formatExceptionRequestScope({ scope: 'AWS_ACCOUNT', scopeValue: '123456789012' }).label, 'AWS account');
  assert.equal(formatExceptionRequestScope({ scope: 'GLOBAL' }).label, 'All assets');
});
