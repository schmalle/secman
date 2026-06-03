import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { canNotifyProductUsers } from './productNotifyAccess.ts';
import { getVisibleProductSearchResults } from './productSearchResults.ts';

test('shows matching products as visible search results while searching', () => {
    const products = [
        'UniFi Network Application',
        'Unified Service Desk',
        'Windows Server 2022'
    ];

    assert.deepEqual(getVisibleProductSearchResults('Uni', products, ''), [
        'UniFi Network Application',
        'Unified Service Desk'
    ]);
});

test('does not show search results after a product is selected', () => {
    assert.deepEqual(
        getVisibleProductSearchResults('Uni', ['UniFi Network Application'], 'UniFi Network Application'),
        []
    );
});

test('allows only admins and secchampions to notify product users', () => {
    assert.equal(canNotifyProductUsers(['ADMIN']), true);
    assert.equal(canNotifyProductUsers(['SECCHAMPION']), true);
    assert.equal(canNotifyProductUsers(['USER', 'VULN']), false);
});

test('provides a route for asset id links emitted by product tables', () => {
    assert.equal(existsSync(new URL('../pages/assets/[id].astro', import.meta.url)), true);
});

test('installed products exposes the product notify action', () => {
    const source = readFileSync(new URL('./InstalledProducts.tsx', import.meta.url), 'utf8');

    assert.match(source, /Notify users/);
    assert.match(source, /createProductBroadcast/);
});

test('installed products renders only product, version, and system columns', () => {
    const source = readFileSync(new URL('./InstalledProducts.tsx', import.meta.url), 'utf8');

    assert.doesNotMatch(source, /<th>Vendor<\/th>/);
    assert.doesNotMatch(source, /<th>Category<\/th>/);
    assert.doesNotMatch(source, /<th>Last imported<\/th>/);
    assert.doesNotMatch(source, /product\.vendor/);
    assert.doesNotMatch(source, /product\.category/);
    assert.doesNotMatch(source, /product\.importedAt/);
    assert.match(source, /colSpan=\{3\}/);
});

test('admin email broadcast preview sanitizes html before rendering', () => {
    const source = readFileSync(new URL('./admin/EmailBroadcastManager.tsx', import.meta.url), 'utf8');

    assert.match(source, /DOMPurify/);
    assert.match(source, /DOMPurify\.sanitize\(html/);
    assert.match(source, /dangerouslySetInnerHTML=\{\{\s*__html:\s*sanitizedPreviewHtml\s*\}\}/);
});
