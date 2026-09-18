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

test('asset list uses WebKit-safe sticky header cells inside a fixed shell', () => {
    const source = readFileSync(new URL('./AssetManagement.tsx', import.meta.url), 'utf8');
    const styleSource = readFileSync(new URL('./scrollableTableStyles.ts', import.meta.url), 'utf8');

    assert.match(source, /height: 'calc\(100dvh - 9\.5rem\)'/);
    assert.match(source, /scrollContainerStyle, stickyHeaderCellStyle/);
    assert.match(styleSource, /overflow: 'auto'/);
    assert.match(styleSource, /export const stickyHeaderCellStyle: React\.CSSProperties/);
    assert.match(styleSource, /backgroundColor: 'var\(--bs-table-bg, #f8f9fa\)'/);
    assert.match(source, /borderCollapse: 'separate'/);
    assert.match(source, /<th style=\{stickyHeaderCellStyle\}>Name<\/th>/);
    assert.doesNotMatch(source, /<thead[^>]+position: 'sticky'/);
});
