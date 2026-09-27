import assert from 'node:assert/strict';
import test from 'node:test';
import { exactParticipantId } from './assessmentParticipantSearch';

const users = [
  { id: 1, username: 'Alice', email: 'alice@example.test' },
  { id: 2, username: 'Alicia', email: 'alicia@example.test' },
];

test('typing a unique username or email selects the existing identity', () => {
  assert.equal(exactParticipantId(users, ' ALICE '), 1);
  assert.equal(exactParticipantId(users, 'ALICIA@EXAMPLE.TEST'), 2);
});

test('partial, empty, unknown, or ambiguous names require explicit selection', () => {
  for (const query of ['Ali', '', 'unknown']) assert.equal(exactParticipantId(users, query), undefined);
  assert.equal(exactParticipantId([...users, { id: 3, username: 'alice', email: 'other@example.test' }], 'alice'), undefined);
});

test('participant search renders visible selectable matches for both fields', async () => {
  const { readFileSync } = await import('node:fs');
  const source = readFileSync(new URL('./RiskAssessmentManagement.tsx', import.meta.url), 'utf8');
  for (const role of ['assessor', 'respondent']) {
    assert.ok(source.includes(`<AssessmentParticipantMatches query={${role}Search}`));
    const cap = role[0].toUpperCase() + role.slice(1);
    assert.ok(source.includes(`onSelect={id => set${cap}RefValue(\`id:\${id}\`)}`));
  }
  const matches = readFileSync(new URL('./AssessmentParticipantMatches.tsx', import.meta.url), 'utf8');
  assert.match(matches, /type="button"/);
  assert.match(matches, /onClick=\{\(\) => onSelect\(user.id!\)\}/);
  assert.match(matches, /No matching users found/);
  assert.match(matches, /role="alert"/);
  assert.match(matches, /Searching users/);
});
