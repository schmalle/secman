import assert from 'node:assert/strict';
import test from 'node:test';
import { formatExceptionRequestSubject } from './exceptionRequestSubjectFormatter.ts';

/**
 * Regression: the exception request detail modal rendered `vulnerabilityCve || 'Unknown'`
 * as the sole vulnerability identity. Under the Feature 196 two-axis model
 * (subject x scope), `vulnerabilityCve` is null BY DESIGN for every rule-style request —
 * VulnerabilityExceptionRequestService only denormalizes `cveId` when the request resolves
 * to exactly one CVE. So a PRODUCT or ALL_VULNS request, and any multi-CVE request,
 * displayed the literal string "Unknown" while its real identity sat unread in
 * `subjectValue`. The list views (MyExceptionRequests, ExceptionApprovalDashboard) already
 * render subject + subjectValue; the modal was never migrated.
 */

test('a PRODUCT request names the product instead of claiming Unknown', () => {
  const display = formatExceptionRequestSubject({
    subject: 'PRODUCT',
    subjectValue: 'python-urllib3',
    vulnerabilityCve: null,
  });

  assert.equal(display.label, 'Product');
  assert.equal(display.value, 'python-urllib3');
  assert.equal(display.isCode, true);
  assert.notEqual(display.value, 'Unknown');
});

test('an ALL_VULNS request says so rather than claiming Unknown', () => {
  const display = formatExceptionRequestSubject({
    subject: 'ALL_VULNS',
    subjectValue: null,
    vulnerabilityCve: null,
  });

  assert.equal(display.label, 'Subject');
  assert.equal(display.value, 'All vulnerabilities');
  assert.equal(display.isCode, false);
});

test('a multi-CVE request lists every CVE, none of which is denormalized to cveId', () => {
  const display = formatExceptionRequestSubject({
    subject: 'CVE',
    subjectValue: 'CVE-2024-1111, CVE-2024-2222',
    vulnerabilityCve: null,
  });

  assert.equal(display.label, 'CVE IDs');
  assert.equal(display.value, 'CVE-2024-1111, CVE-2024-2222');
  assert.equal(display.isCode, true);
});

test('a finding-anchored single-CVE request still shows the CVE it always showed', () => {
  const display = formatExceptionRequestSubject({
    subject: 'CVE',
    subjectValue: 'CVE-2024-1111',
    vulnerabilityCve: 'CVE-2024-1111',
  });

  assert.equal(display.label, 'CVE ID');
  assert.equal(display.value, 'CVE-2024-1111');
  assert.equal(display.isCode, true);
});

test('the live vulnerability CVE wins when subjectValue was never populated', () => {
  const display = formatExceptionRequestSubject({
    subject: 'CVE',
    subjectValue: null,
    vulnerabilityCve: 'CVE-2023-9999',
  });

  assert.equal(display.label, 'CVE ID');
  assert.equal(display.value, 'CVE-2023-9999');
});

test('a NO_EDR request has no vulnerability subject at all and must not read as a CVE', () => {
  const display = formatExceptionRequestSubject({
    subject: 'ALL_VULNS',
    subjectValue: null,
    vulnerabilityCve: null,
    kind: 'NO_EDR',
  });

  assert.equal(display.label, 'Subject');
  assert.equal(display.value, 'No EDR agent possible');
  assert.equal(display.isCode, false);
  assert.notEqual(display.value, 'All vulnerabilities');
});

test('a CVE request whose vulnerability row was deleted reads as remediated, not Unknown', () => {
  const display = formatExceptionRequestSubject({
    subject: 'CVE',
    subjectValue: null,
    vulnerabilityCve: null,
  });

  assert.equal(display.label, 'CVE ID');
  assert.equal(display.value, 'Remediated');
  assert.equal(display.isCode, false);
  assert.equal(display.muted, true);
});

test('a missing kind is treated as VULNERABILITY, matching every pre-Feature-196 row', () => {
  const display = formatExceptionRequestSubject({
    subject: 'PRODUCT',
    subjectValue: 'openssl',
    vulnerabilityCve: null,
    kind: undefined,
  });

  assert.equal(display.value, 'openssl');
});

test('whitespace in a stored subjectValue never renders as a blank identity', () => {
  const display = formatExceptionRequestSubject({
    subject: 'PRODUCT',
    subjectValue: '   ',
    vulnerabilityCve: null,
  });

  assert.equal(display.value, 'Any product');
  assert.equal(display.isCode, false);
});
