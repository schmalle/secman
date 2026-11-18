import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadAssessmentSections } from './assessmentSections';

test('starts every independent read before waiting for a response', async () => {
  const started: string[] = [];
  let release: () => void = () => {};
  const gate = new Promise<void>(resolve => { release = resolve; });
  const pending = loadAssessmentSections(
    async () => { started.push('assessment'); await gate; return 1; },
    async () => { started.push('requirements'); await gate; return 2; },
    async () => { started.push('suggestions'); await gate; return 3; },
    () => assert.fail('suggestions should succeed'),
  );
  assert.deepEqual(started, ['assessment', 'requirements', 'suggestions']);
  release();
  const result = await pending;
  assert.deepEqual(result, { assessment: 1, requirements: 2, suggestions: 3 });
});

test('optional suggestions can fail without hiding required data', async () => {
  const failure = new Error('feature disabled');
  const errors: unknown[] = [];
  const result = await loadAssessmentSections(
    async () => 'assessment',
    async () => ['requirement'],
    async () => { throw failure; },
    error => { errors.push(error); },
  );
  assert.deepEqual(result, {
    assessment: 'assessment', requirements: ['requirement'], suggestions: null,
  });
  assert.deepEqual(errors, [failure]);
});

test('a required read failure still rejects the load', async () => {
  const failure = new Error('requirements unavailable');
  await assert.rejects(
    loadAssessmentSections(
      async () => 'assessment',
      async () => { throw failure; },
      async () => [],
      () => assert.fail('suggestions should succeed'),
    ),
    failure,
  );
});
