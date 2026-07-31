import assert from 'node:assert/strict';
import test from 'node:test';
import { createReviewExceptionRequestDto } from './exceptionReviewDto.ts';
import { formatExceptionRequestScope } from './exceptionRequestScopeFormatter.ts';
import { safeReadError } from './exceptionRequestService.ts';

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
  const os = formatExceptionRequestScope({ scope: 'OS', scopeValue: 'Windows Server 2019' });
  assert.equal(os.label, 'OS scope');
  assert.equal(os.title, 'OS: Windows Server 2019');
});

/**
 * Regression: production 500 on POST /api/vulnerability-exception-requests/{id}/approve.
 *
 * The backend's catch-all returned a bare 500 with NO body. The error branches here then called
 * `await response.json()` on it, which threw, and the banner showed
 * "Failed to execute 'json' on 'Response': Unexpected end of JSON input" — the frontend's own
 * parse failure — instead of anything about the actual server error.
 *
 * safeReadError must never throw, whatever the server sends back.
 */
test('safeReadError survives an empty 500 body instead of throwing a JSON parse error', async () => {
  const emptyServerError = new Response('', { status: 500 });
  const message = await safeReadError(emptyServerError);

  // Empty string, so the caller falls through to its own human-readable default.
  assert.equal(message, '');
  assert.doesNotMatch(message, /Unexpected end of JSON input/);
});

test('safeReadError extracts the backend ErrorResponse shape', async () => {
  // What the fixed controller now returns.
  const body = JSON.stringify({
    error: 'Failed to approve exception request',
    details: 'Data too long for column \'reason\' at row 1',
    status: 500
  });
  const response = new Response(body, { status: 500, headers: { 'Content-Type': 'application/json' } });

  assert.equal(await safeReadError(response), 'Failed to approve exception request');
});

test('safeReadError falls back to raw text for a non-JSON body', async () => {
  const response = new Response('502 Bad Gateway', { status: 502 });
  assert.equal(await safeReadError(response), '502 Bad Gateway');
});
