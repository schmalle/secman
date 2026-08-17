import test, { beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import {
  SELECTED_RELEASE_KEY,
  clearSelectedReleaseId,
  readSelectedReleaseId,
  writeSelectedReleaseId,
} from './selectedRelease.ts';

// Minimal sessionStorage stand-in. Node has no DOM, and the module deliberately touches the real
// global rather than taking an injected store, because that is what the components do.
function installStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  (globalThis as any).sessionStorage = {
    getItem: (k: string) => (data.has(k) ? data.get(k)! : null),
    setItem: (k: string, v: string) => void data.set(k, String(v)),
    removeItem: (k: string) => void data.delete(k),
  };
  return data;
}

beforeEach(() => {
  installStorage();
});

test('reads a stored id back as a number', () => {
  installStorage({ [SELECTED_RELEASE_KEY]: '44727' });
  assert.equal(readSelectedReleaseId(), 44727);
});

test('returns null when nothing is stored', () => {
  assert.equal(readSelectedReleaseId(), null);
});

test('rejects malformed values instead of producing NaN', () => {
  // parseInt('abc') is NaN, and NaN in a URL becomes /api/releases/NaN — a guaranteed 404.
  for (const bad of ['abc', '', ' ', 'null', 'undefined']) {
    installStorage({ [SELECTED_RELEASE_KEY]: bad });
    assert.equal(readSelectedReleaseId(), null, `${JSON.stringify(bad)} must not survive`);
  }
});

test('rejects ids that cannot identify a row', () => {
  for (const bad of ['0', '-1', '-44727']) {
    installStorage({ [SELECTED_RELEASE_KEY]: bad });
    assert.equal(readSelectedReleaseId(), null, `${bad} must not survive`);
  }
});

test('ignores trailing junk rather than half-parsing it', () => {
  // parseInt('44727abc') is 44727 — a silently wrong id is worse than no id.
  installStorage({ [SELECTED_RELEASE_KEY]: '44727abc' });
  assert.equal(readSelectedReleaseId(), null);
});

test('writing then reading round-trips', () => {
  writeSelectedReleaseId(44727);
  assert.equal(readSelectedReleaseId(), 44727);
});

test('writing null forgets the selection', () => {
  installStorage({ [SELECTED_RELEASE_KEY]: '44727' });
  writeSelectedReleaseId(null);
  assert.equal(readSelectedReleaseId(), null);
});

test('clearing forgets the selection', () => {
  const data = installStorage({ [SELECTED_RELEASE_KEY]: '44727' });
  clearSelectedReleaseId();
  assert.equal(readSelectedReleaseId(), null);
  assert.equal(data.has(SELECTED_RELEASE_KEY), false, 'the key itself must be removed, not blanked');
});

test('clearing a selection that is already absent is a no-op', () => {
  clearSelectedReleaseId();
  assert.equal(readSelectedReleaseId(), null);
});

test('survives storage being unavailable', () => {
  // Private/hardened browser modes throw on access rather than returning null, and a server
  // render has no sessionStorage at all. Neither may take a page down.
  (globalThis as any).sessionStorage = {
    getItem() { throw new Error('denied'); },
    setItem() { throw new Error('denied'); },
    removeItem() { throw new Error('denied'); },
  };
  assert.equal(readSelectedReleaseId(), null);
  assert.doesNotThrow(() => writeSelectedReleaseId(1));
  assert.doesNotThrow(() => clearSelectedReleaseId());

  delete (globalThis as any).sessionStorage;
  assert.equal(readSelectedReleaseId(), null);
  assert.doesNotThrow(() => clearSelectedReleaseId());
});
