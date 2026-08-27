import { test } from 'node:test';
import assert from 'node:assert/strict';
import { getEffectiveAssignedUserCount, filterAssetsByWildcard } from './workgroupAssignLogic';

const users = [
    { id: 1, username: 'a', email: 'a@x.io' },
    { id: 2, username: 'b', email: 'b@x.io' },
    { id: 3, username: 'c', email: 'c@x.io' },
];

test('effective count subtracts only removals that target assigned members', () => {
    assert.equal(getEffectiveAssignedUserCount(users, []), 3);
    assert.equal(getEffectiveAssignedUserCount(users, [1, 3]), 1);
    assert.equal(getEffectiveAssignedUserCount(users, [99]), 3);
    assert.equal(getEffectiveAssignedUserCount([], [1]), 0);
});

const assets = [
    { id: 1, name: 'ip-10-0-0-1', type: 'SERVER' },
    { id: 2, name: 'prod-db', type: 'DATABASE' },
    { id: 3, name: 'Staging-Web', type: 'SERVER' },
];

test('empty search returns everything', () => {
    assert.equal(filterAssetsByWildcard(assets, '  '), assets);
});

test('partial match is case-insensitive against name and type', () => {
    assert.deepEqual(filterAssetsByWildcard(assets, 'PROD').map(a => a.id), [2]);
    assert.deepEqual(filterAssetsByWildcard(assets, 'database').map(a => a.id), [2]);
});

test('* wildcard matches any characters', () => {
    assert.deepEqual(filterAssetsByWildcard(assets, 'ip-10-*').map(a => a.id), [1]);
    assert.deepEqual(filterAssetsByWildcard(assets, '*web*').map(a => a.id), [3]);
});

test('? wildcard matches a single character', () => {
    assert.deepEqual(filterAssetsByWildcard(assets, 'prod-d?').map(a => a.id), [2]);
});
