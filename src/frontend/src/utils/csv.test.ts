import assert from 'node:assert/strict';
import test from 'node:test';
import { buildCsv, escapeCsvCell } from './csv.ts';

// The formula guard is the reason this module exists: a browser-built CSV never
// reaches the backend's ExcelSanitizer, so a hostile asset name or owner would
// otherwise land in the file as a live formula.

test('escapeCsvCell neutralizes every formula prefix Excel honours', () => {
  for (const prefix of ['=', '+', '-', '@', '\t', '\r']) {
    const escaped = escapeCsvCell(`${prefix}cmd|'/c calc'!A1`);
    // \r forces quoting, so strip the wrapper before checking the guard.
    const inner = escaped.startsWith('"') ? escaped.slice(1, -1) : escaped;
    assert.equal(inner.charAt(0), "'", `prefix ${JSON.stringify(prefix)} was not neutralized`);
  }
});

test('escapeCsvCell leaves ordinary text untouched', () => {
  assert.equal(escapeCsvCell('IPEPQEUSQL05'), 'IPEPQEUSQL05');
  assert.equal(escapeCsvCell('aws-ad.aws.glpoly.net'), 'aws-ad.aws.glpoly.net');
});

test('escapeCsvCell quotes separators, quotes and newlines per RFC 4180', () => {
  assert.equal(escapeCsvCell('a,b'), '"a,b"');
  assert.equal(escapeCsvCell('say "hi"'), '"say ""hi"""');
  assert.equal(escapeCsvCell('line1\nline2'), '"line1\nline2"');
});

test('escapeCsvCell renders numbers verbatim and blanks null/undefined', () => {
  // A negative number is not a formula risk — prefixing it would corrupt the value.
  assert.equal(escapeCsvCell(-1217), '-1217');
  assert.equal(escapeCsvCell(0), '0');
  assert.equal(escapeCsvCell(null), '');
  assert.equal(escapeCsvCell(undefined), '');
});

test('escapeCsvCell keeps the guard inside the quotes when both apply', () => {
  assert.equal(escapeCsvCell('=SUM(A1,A2)'), '"\'=SUM(A1,A2)"');
});

test('buildCsv writes a header row and CRLF line endings', () => {
  const csv = buildCsv(['System', 'Owner'], [['host-1', 'CrowdStrike Import'], ['host-2', null]]);
  assert.equal(csv, 'System,Owner\r\nhost-1,CrowdStrike Import\r\nhost-2,');
});

test('buildCsv emits only the header when there are no rows', () => {
  assert.equal(buildCsv(['System'], []), 'System');
});
