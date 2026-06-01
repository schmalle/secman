import assert from 'node:assert/strict';
import test from 'node:test';
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
