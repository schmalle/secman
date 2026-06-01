import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('asset creation type dropdown includes SaaS', () => {
    const source = readFileSync(new URL('./AssetManagement.tsx', import.meta.url), 'utf8');

    assert.match(source, /<option value="SaaS">SaaS<\/option>/);
});
