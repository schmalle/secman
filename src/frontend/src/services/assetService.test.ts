import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseHostnameDeletionPreview } from './assetService';

test('hostname deletion preview accepts an all-matched response with no unmatched hostnames', () => {
  assert.deepEqual(parseHostnameDeletionPreview({
    assets: [{
      id: 17,
      name: 'web-one',
      hostname: 'web-one.example.test',
      cloudAccountId: null,
      cloudInstanceId: 'i-0123456789abcdef0',
    }],
    unmatchedHostnames: [],
  }), {
    assets: [{ id: 17, name: 'web-one', hostname: 'web-one.example.test', cloudInstanceId: 'i-0123456789abcdef0' }],
    unmatchedHostnames: [],
  });
});

test('hostname deletion preview accepts a no-match response with no assets', () => {
  assert.deepEqual(parseHostnameDeletionPreview({
    assets: [],
    unmatchedHostnames: ['missing.example.test'],
  }), {
    assets: [],
    unmatchedHostnames: ['missing.example.test'],
  });
});

test('hostname deletion preview rejects missing arrays and malformed nested entries', () => {
  const valid = {
    assets: [{ id: 17, name: 'web-one', hostname: 'web-one.example.test' }],
    unmatchedHostnames: ['missing.example.test'],
  };
  const malformed = [
    {},
    { ...valid, assets: undefined },
    { ...valid, unmatchedHostnames: undefined },
    { ...valid, assets: [null] },
    { ...valid, assets: [{ ...valid.assets[0], id: '17' }] },
    { ...valid, assets: [{ ...valid.assets[0], id: 0 }] },
    { ...valid, assets: [{ ...valid.assets[0], name: null }] },
    { ...valid, assets: [{ ...valid.assets[0], hostname: null }] },
    { ...valid, assets: [{ ...valid.assets[0], cloudAccountId: 123 }] },
    { ...valid, unmatchedHostnames: [null] },
  ];

  for (const response of malformed) {
    assert.throws(() => parseHostnameDeletionPreview(response), /server returned an invalid response/);
  }
});
