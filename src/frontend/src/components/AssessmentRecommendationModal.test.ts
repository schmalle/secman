import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('./AssessmentRecommendationModal.tsx', import.meta.url), 'utf8');

test('recommendation badge has three visually distinct branches with non-color cues', () => {
  assert.match(source, /OK: \{ className: 'bg-success', icon: '✓', text: 'OK' \}/);
  assert.match(source, /NOT_OK: \{ className: 'bg-danger', icon: '✗', text: 'Not OK' \}/);
  assert.match(source, /NEEDS_REVIEW: \{ className: 'bg-warning text-dark', icon: '!', text: 'Needs review' \}/);
  assert.match(source, /role="status"/);
  assert.match(source, /aria-label=\{`Recommendation: \$\{badge\.text\}`\}/);
  assert.match(source, /<span aria-hidden="true">\{badge\.icon\}<\/span> \{badge\.text\}/);
});

test('result is labelled as advisory next to the badge', () => {
  assert.match(source, /Advisory — human approval is still required/);
});

test('modal follows the accessible dialog conventions', () => {
  assert.match(source, /role="dialog"/);
  assert.match(source, /aria-modal="true"/);
  assert.match(source, /aria-labelledby="assessmentRecommendationModalTitle"/);
  assert.match(source, /id="assessmentRecommendationModalTitle"/);
  assert.match(source, /aria-label="Close"/);
  assert.match(source, /e\.key === 'Escape'/);
  assert.match(source, /dialogRef\.current\?\.focus\(\)/);
  assert.match(source, /tabIndex=\{-1\}/);
});

test('loading, 403, 404 and generic failure states are all covered', () => {
  assert.match(source, /Analyzing answers\.\.\./);
  assert.match(source, /response\.status === 403/);
  assert.match(source, /You don't have review authority for this assessment\./);
  assert.match(source, /response\.status === 404/);
  assert.match(source, /Assessment not found or not visible to you\./);
  assert.match(source, /alert alert-danger/);
  assert.match(source, />\s*Retry\s*</);
});

test('recommendation endpoint is fetched on mount and Refresh re-fetches', () => {
  assert.match(source, /authenticatedGet\(`\/api\/risk-assessments\/\$\{assessmentId\}\/recommendation`,/);
  assert.match(source, /void fetchRecommendation\(\);/);
  assert.match(source, />\s*Refresh\s*</);
  assert.match(source, /onClick=\{fetchRecommendation\}/);
});

test('footer shows answer revision, generation time and policy version', () => {
  assert.match(source, /Answer revision \$\{data\.answerRevision\}/);
  assert.match(source, /Policy v\$\{data\.policyVersion\}/);
  assert.match(source, /formatServerDateTime\(data\.generatedAt\)/);
  assert.match(source, />\s*Review answers\s*</);
  assert.match(source, /onClick=\{\(\) => onReviewAnswers\(\)\}/);
});

test('findings and counts render from the response, comments as plain text', () => {
  assert.match(source, /No findings — every requirement is answered and none are unmet\./);
  assert.match(source, /No answer submitted/);
  assert.match(source, /finding\.internalId \|\| `Requirement #\$\{finding\.requirementId\}`/);
  assert.match(source, /\{finding\.shortreq\}/);
  assert.match(source, /\{finding\.reason\}/);
  assert.match(source, /\{finding\.comment\}/);
  assert.match(source, /data\.answerCounts\.YES \?\? 0/);
  assert.match(source, /data\.answerCounts\.NO \?\? 0/);
  assert.match(source, /data\.answerCounts\.N_A \?\? 0/);
  assert.match(source, /data\.missingAnswerCount/);
  assert.match(source, /data\.requirementCount/);
  assert.doesNotMatch(source, /dangerouslySetInnerHTML/);
});
