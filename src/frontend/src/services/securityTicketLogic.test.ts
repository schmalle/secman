import assert from 'node:assert/strict';
import test from 'node:test';
import {
  arnAccount, buildRequest, bulkDelivery, prepareBulk, dueText, emptyDraft, errorsByField, foreignArns, newReference, parseBulk, parseCsv,
  parseResourceLines, readyForPreview, slaLabel, splitList, type TicketMeta,
} from './securityTicketLogic.ts';

test('lists split on commas, semicolons and whitespace and drop repeats', () => {
  assert.deepEqual(splitList(' CVE-1, CVE-2;CVE-1\nCVE-3 '), ['CVE-1', 'CVE-2', 'CVE-3']);
  assert.deepEqual(splitList(''), []);
});

test('resource lines: ARNs pass through, TYPE=id@region is parsed, bare ids stay bare', () => {
  assert.deepEqual(parseResourceLines('arn:aws:ec2:eu-central-1:123456789012:instance/i-1\n\nHOSTNAME=web01@eu-west-1\ni-2\nS3_BUCKET=a@b.example'), [
    { identifier: 'arn:aws:ec2:eu-central-1:123456789012:instance/i-1' },
    { type: 'HOSTNAME', identifier: 'web01', region: 'eu-west-1' },
    { identifier: 'i-2' },
    { type: 'S3_BUCKET', identifier: 'a@b.example' },
  ]);
});

test('ARN accounts are read and foreign ones flagged', () => {
  assert.equal(arnAccount('arn:aws:iam::123456789012:role/x'), '123456789012');
  assert.equal(arnAccount('arn:aws:s3:::bucket'), '');
  assert.equal(arnAccount('i-0abc'), null);
  const text = 'arn:aws:ec2:eu-central-1:123456789012:instance/i-1\narn:aws:rds:eu-central-1:210987654321:snapshot:s\narn:aws:s3:::b';
  assert.deepEqual(foreignArns(text, '123456789012'), ['arn:aws:rds:eu-central-1:210987654321:snapshot:s']);
  assert.deepEqual(foreignArns(text, 'not-an-id'), []);
});

test('the request separates the finding from delivery and carries the idempotency key', () => {
  const draft = { ...emptyDraft(), awsAccountId: ' 123456789012 ', title: 't', description: 'd', cvesText: 'CVE-2026-1, CVE-2026-1',
    deliverTo: 'specific' as const, recipientsText: 'a@x.example b@x.example', allowUnrouted: true };
  const { ticket, delivery } = buildRequest(draft, 'ui-1', true);
  assert.equal(ticket.externalReference, 'ui-1');
  assert.equal(ticket.awsAccountId, '123456789012');
  assert.deepEqual(ticket.cves, ['CVE-2026-1']);
  assert.equal(ticket.dueAt, undefined);
  assert.deepEqual(delivery, { dryRun: true, recipients: ['a@x.example', 'b@x.example'] });

  const owners = buildRequest({ ...draft, deliverTo: 'owners' }, 'ui-1', false).delivery;
  assert.deepEqual(owners, { dryRun: false, allowUnrouted: true });
});

test('preview starts only once account, title and description are filled', () => {
  assert.equal(readyForPreview(emptyDraft()), false);
  assert.equal(readyForPreview({ ...emptyDraft(), awsAccountId: '123456789012', title: 't', description: 'd' }), true);
  assert.equal(readyForPreview({ ...emptyDraft(), awsAccountId: '1234', title: 't', description: 'd' }), false);
});

test('server field errors land on the matching form field', () => {
  const byField = errorsByField([
    { field: 'title', code: 'REQUIRED', message: 'title is required' },
    { field: 'resources[1].identifier', code: 'ACCOUNT_MISMATCH', message: 'other account' },
    { field: 'delivery.recipients[0]', code: 'NOT_ALLOWED', message: 'not allowed' },
  ]);
  assert.deepEqual(byField.title, ['title is required']);
  assert.deepEqual(byField.resources, ['resources[1].identifier: other account']);
  assert.deepEqual(byField.recipients, ['delivery.recipients[0]: not allowed']);
});

test('SLA and due labels', () => {
  const meta = { severities: [{ name: 'HIGH', slaDays: 7 }, { name: 'INFO', slaDays: null }] } as unknown as TicketMeta;
  assert.equal(slaLabel(meta, 'HIGH'), 'due in 7 days (HIGH SLA)');
  assert.equal(slaLabel(meta, 'INFO'), 'no deadline');
  assert.equal(slaLabel(null, 'HIGH'), '');
  const now = new Date('2026-11-26T12:00:00Z');
  assert.equal(dueText('2026-11-23T12:00:00Z', 'OPEN', now), '3 days overdue');
  assert.equal(dueText('2026-11-28T13:00:00Z', 'OPEN', now), 'due in 2 days');
  assert.equal(dueText('2026-11-26T20:00:00Z', 'OPEN', now), 'due today');
  assert.equal(dueText('2026-11-23T12:00:00Z', 'CLOSED', now), '');
});

test('references are prefixed and unique per call', () => {
  assert.equal(newReference(() => 'abc'), 'ui-abc');
});

test('CSV handles quotes, embedded commas and newlines', () => {
  assert.deepEqual(parseCsv('a,b\n"x, y","line1\nline2"\r\n"he said ""hi""",\n'), [
    ['a', 'b'], ['x, y', 'line1\nline2'], ['he said "hi"', ''],
  ]);
});

test('bulk input accepts JSON and CSV and reports unknown columns', () => {
  assert.equal(parseBulk('[{"awsAccountId":"1"}]').tickets.length, 1);
  assert.equal(parseBulk('{"tickets":[{},{}]}').tickets.length, 2);
  assert.match(parseBulk('{"x":1}').error ?? '', /array/);
  assert.match(parseBulk('[oops').error ?? '', /JSON/);
  const csv = parseBulk('externalReference,awsAccountId,title,severity,description,cves,resources\n' +
    'R1,123456789012,T,HIGH,D,CVE-2026-1;CVE-2026-2,arn:aws:s3:::b;HOSTNAME=web01\n');
  assert.deepEqual(csv.tickets[0], { awsAccountId: '123456789012', title: 'T', severity: 'HIGH', description: 'D',
    externalReference: 'R1', cves: ['CVE-2026-1', 'CVE-2026-2'],
    resources: [{ identifier: 'arn:aws:s3:::b' }, { type: 'HOSTNAME', identifier: 'web01' }] });
  assert.match(parseBulk('awsAccountId,colour\n1,red').error ?? '', /colour/);
  assert.match(parseBulk('  ').error ?? '', /Nothing/);
});

test('bulk runs are size-checked and get one delivery for all tickets', () => {
  assert.match(prepareBulk(JSON.stringify(Array(3).fill({})), 2).error ?? '', /At most 2/);
  assert.equal(prepareBulk('[{}]', 2).tickets.length, 1);
  assert.deepEqual(bulkDelivery('a@x.example, b@x.example', true), { dryRun: true, recipients: ['a@x.example', 'b@x.example'] });
  assert.deepEqual(bulkDelivery('  ', false), { dryRun: false });
});
