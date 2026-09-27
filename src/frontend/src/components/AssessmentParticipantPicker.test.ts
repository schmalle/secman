import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('assessment form uses one picker per person without duplicate select controls', () => {
  const source = readFileSync(new URL('./RiskAssessmentManagement.tsx', import.meta.url), 'utf8');
  assert.equal((source.match(/<AssessmentParticipantPicker /g) || []).length, 2);
  assert.match(source, /col-md-6/);
  assert.doesNotMatch(source, /id="assessorRef"|id="respondentRef"|id="externalRespondent"/);
  assert.match(source, /id="respondent" label="Respondent" allowEmail/);
});

test('participant picker supports keyboard selection, email invitations and clear', () => {
  const source = readFileSync(new URL('./AssessmentParticipantPicker.tsx', import.meta.url), 'utf8');
  for (const marker of ['role="combobox"', 'role="listbox"', 'aria-activedescendant', "'ArrowDown'", "'Escape'", "'Enter'", 'Invite ${email}', 'Clear ${label.toLowerCase()}']) {
    assert.ok(source.includes(marker), marker);
  }
  assert.match(source, /setOpen\(false\)/);
});
