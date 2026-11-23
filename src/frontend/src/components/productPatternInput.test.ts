import assert from 'node:assert/strict';
import test from 'node:test';
import { productFields, productListError, quoteProduct } from './productPatternInput.ts';

test('accepts adjacent case-insensitive wildcard expressions with optional spaces', () => {
  for (const input of ['*net*,*oracle*', '*net*, *oracle*']) {
    assert.equal(productListError(input), null);
    assert.deepEqual(productFields(input).map(f => f.value), ['*net*', '*oracle*']);
  }
});

test('suggested names with punctuation survive replacement of the middle field', () => {
  const input = '*net*,or,*tomcat*';
  const field = productFields(input)[1];
  const name = 'Oracle, "Enterprise"';
  const result = input.slice(0, field.start) + quoteProduct(name) + input.slice(field.end);
  assert.equal(productListError(result), null);
  assert.deepEqual(productFields(result).map(f => f.value), ['*net*', name, '*tomcat*']);
});

test('rejects empty entries, all-star patterns, and incomplete quoted names', () => {
  for (const input of ['', '*net*,', '*,abc', '**', '"unfinished']) assert.ok(productListError(input));
});
