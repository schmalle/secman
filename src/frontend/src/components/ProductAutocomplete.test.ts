import assert from 'node:assert/strict';
import test from 'node:test';
import { getProductSuggestions } from './ProductAutocomplete';

test('returns all known products when no filter is entered', () => {
    const products = Array.from({ length: 25 }, (_, index) => `Product ${index + 1}`);

    assert.deepEqual(getProductSuggestions('', products), products);
});

test('returns all matching products instead of capping visible matches', () => {
    const products = Array.from({ length: 25 }, (_, index) => `Apache HTTP Server ${index + 1}`);

    assert.deepEqual(getProductSuggestions('apache', products), products);
});
