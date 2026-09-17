import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('./auth.ts', import.meta.url), 'utf8');

test('expired sessions preserve the current same-origin page on login redirect', () => {
    assert.match(source, /window\.location\.pathname \+ window\.location\.search/);
    assert.match(source, /encodeURIComponent\(target\)/);
    assert.match(source, /window\.location\.replace\(`\/login\$\{suffix\}`\)/);
});
