import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, extractErrorMessage, parseJsonResponse } from './apiJson';

const jsonResponse = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

test('parseJsonResponse returns parsed body on OK', async () => {
    const result = await parseJsonResponse<{ a: number }>(jsonResponse({ a: 1 }), 'x');
    assert.deepEqual(result, { a: 1 });
});

test('parseJsonResponse resolves undefined for an empty body', async () => {
    const result = await parseJsonResponse<void>(new Response(null, { status: 200 }), 'x');
    assert.equal(result, undefined);
});

test('parseJsonResponse throws ApiError with backend error message and status', async () => {
    await assert.rejects(
        parseJsonResponse(jsonResponse({ error: 'nope' }, 403), 'Failed to save'),
        (err: unknown) => err instanceof ApiError && err.message === 'nope' && err.status === 403,
    );
});

test('extractErrorMessage prefers error over message', async () => {
    assert.equal(await extractErrorMessage(jsonResponse({ error: 'e', message: 'm' }, 400), 'f'), 'e');
});

test('extractErrorMessage falls back to Micronaut message shape', async () => {
    assert.equal(await extractErrorMessage(jsonResponse({ message: 'm' }, 400), 'f'), 'm');
});

test('extractErrorMessage joins Bean-Validation violations', async () => {
    const body = { _embedded: { errors: [{ message: 'a' }, { message: 'b' }] } };
    assert.equal(await extractErrorMessage(jsonResponse(body, 400), 'f'), 'a; b');
});

test('extractErrorMessage falls back with HTTP status on a non-JSON body', async () => {
    const response = new Response('<html>oops</html>', { status: 502 });
    assert.equal(await extractErrorMessage(response, 'Failed to load'), 'Failed to load (HTTP 502)');
});
