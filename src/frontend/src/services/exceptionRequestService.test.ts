import assert from 'node:assert/strict';
import test from 'node:test';
import { createReviewExceptionRequestDto } from './exceptionReviewDto.ts';

test('review DTO uses reviewComment expected by backend', () => {
  assert.deepEqual(createReviewExceptionRequestDto('TestTestTest'), {
    reviewComment: 'TestTestTest'
  });
});
