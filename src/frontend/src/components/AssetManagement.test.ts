import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('asset creation type dropdown includes SaaS', () => {
    const source = readFileSync(new URL('./AssetManagement.tsx', import.meta.url), 'utf8');

    assert.match(source, /<option value="SaaS">SaaS<\/option>/);
});

test('CrowdStrike name overrides are visible and resettable', () => {
    const source = readFileSync(new URL('./AssetManagement.tsx', import.meta.url), 'utf8');

    assert.match(source, /CrowdStrike hostname:/);
    assert.match(source, /Restore CrowdStrike hostname/);
    assert.match(source, /\/name\/reset/);
});
