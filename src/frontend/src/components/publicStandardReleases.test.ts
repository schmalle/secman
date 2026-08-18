import test from 'node:test';
import assert from 'node:assert/strict';
import {
  PUBLISHED_STATUSES,
  defaultReleaseId,
  releaseOptionLabel,
  selectableReleases,
  type PublicRelease,
} from './publicStandardReleases.ts';

function release(over: Partial<PublicRelease> & { id: number }): PublicRelease {
  return { version: '1.0', name: '', status: 'ARCHIVED', ...over };
}

test('offers only released versions to anonymous visitors', () => {
  const picked = selectableReleases([
    release({ id: 1, status: 'PREPARATION' }),
    release({ id: 2, status: 'ALIGNMENT' }),
    release({ id: 3, status: 'ACTIVE' }),
    release({ id: 4, status: 'ARCHIVED' }),
  ]);
  assert.deepEqual(picked.map(r => r.id), [3, 4]);
});

test('drafts are excluded even when nothing is active', () => {
  const picked = selectableReleases([
    release({ id: 1, status: 'PREPARATION' }),
    release({ id: 2, status: 'ALIGNMENT' }),
  ]);
  assert.deepEqual(picked, []);
});

test('the active release sorts ahead of newer archived ones', () => {
  // id 9 is archived and numerically higher, but the version in force must still lead.
  const picked = selectableReleases([
    release({ id: 9, status: 'ARCHIVED' }),
    release({ id: 3, status: 'ACTIVE' }),
  ]);
  assert.deepEqual(picked.map(r => r.id), [3, 9]);
});

test('archived releases run newest first', () => {
  const picked = selectableReleases([
    release({ id: 2, status: 'ARCHIVED' }),
    release({ id: 7, status: 'ARCHIVED' }),
    release({ id: 5, status: 'ARCHIVED' }),
  ]);
  assert.deepEqual(picked.map(r => r.id), [7, 5, 2]);
});

test('does not mutate the caller array', () => {
  const input = [release({ id: 1, status: 'ACTIVE' }), release({ id: 2 })];
  const snapshot = input.map(r => r.id);
  selectableReleases(input);
  assert.deepEqual(input.map(r => r.id), snapshot);
});

test('defaults to the active release', () => {
  const id = defaultReleaseId([
    release({ id: 4, status: 'ARCHIVED' }),
    release({ id: 6, status: 'ACTIVE' }),
  ]);
  assert.equal(id, 6);
});

test('reports no default when nothing is active, rather than guessing', () => {
  // The download endpoint 404s on release=latest here; the page must say so, not fall back
  // to the live requirement set.
  assert.equal(defaultReleaseId([release({ id: 4, status: 'ARCHIVED' })]), null);
  assert.equal(defaultReleaseId([]), null);
});

test('labels lead with the version and mark the current one', () => {
  assert.equal(
    releaseOptionLabel(release({ id: 1, version: '2026.1', name: 'Spring', status: 'ACTIVE' })),
    'v2026.1 (Spring) — current',
  );
  assert.equal(
    releaseOptionLabel(release({ id: 2, version: '2025.4', name: 'Autumn' })),
    'v2025.4 (Autumn)',
  );
});

test('label omits a name that only repeats the version', () => {
  assert.equal(releaseOptionLabel(release({ id: 1, version: '3.0', name: '3.0' })), 'v3.0');
  assert.equal(releaseOptionLabel(release({ id: 1, version: '3.0', name: '   ' })), 'v3.0');
  assert.equal(releaseOptionLabel(release({ id: 1, version: '3.0', name: '' })), 'v3.0');
});

test('published statuses stay pinned to the documented lifecycle', () => {
  assert.deepEqual([...PUBLISHED_STATUSES], ['ACTIVE', 'ARCHIVED']);
});

test('a deployment with no releases at all defaults to the live set', () => {
  // null is the live requirement set for both URL builders (parameter omitted), which is what
  // a fresh deployment with nothing released yet must fall back to.
  assert.equal(defaultReleaseId([]), null);
  assert.deepEqual(selectableReleases([]), []);
});
