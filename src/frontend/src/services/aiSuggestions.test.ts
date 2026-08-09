import assert from 'node:assert/strict';
import test from 'node:test';
import { jobEventStreamUrl } from './aiSuggestions.ts';

// The SSE URL used to carry `?token=` populated from
// `localStorage.getItem('authToken')`. That key has been empty since the JWT
// moved into the HttpOnly `secman_auth` cookie, so the parameter authenticated
// nothing — but a URL-shaped credential is a real leak once anything refills
// that key: query strings reach access logs, proxy logs, `Referer` headers and
// browser history. These tests pin the property, not the implementation, so
// reintroducing a credential parameter under any name fails here.

test('SSE URL carries no credential in the query string', () => {
  const url = jobEventStreamUrl(42, 7);
  assert.equal(url, '/api/risk-assessments/42/ai-suggestions/jobs/7/events');
  assert.ok(!url.includes('?'), 'SSE URL must have no query string at all');
});

test('SSE URL never names a credential parameter', () => {
  const url = jobEventStreamUrl(1, 2).toLowerCase();
  for (const name of ['token', 'jwt', 'auth', 'access_token', 'apikey', 'password']) {
    assert.ok(!url.includes(name), `SSE URL must not mention "${name}"`);
  }
});

test('SSE URL is same-origin, so the cookie is sent without CORS negotiation', () => {
  const url = jobEventStreamUrl(3, 4);
  assert.ok(url.startsWith('/api/'), 'must be a relative same-origin path');
  assert.ok(!/^[a-z]+:\/\//i.test(url), 'must not be an absolute URL');
});
