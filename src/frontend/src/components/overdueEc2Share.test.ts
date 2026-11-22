import assert from 'node:assert/strict';
import test from 'node:test';
import { formatOverdueEc2Share } from './overdueEc2Share';

test('uses overdue servers as the numerator and the entire current fleet as the denominator', () => {
  const response = { available: true, overdueAwsServers: 891, totalAwsServers: 1555, unknownAwsServers: 254 };
  assert.deepEqual(formatOverdueEc2Share(response), {
    value: '57.3%',
    subtitle: '891 of 1,555 current EC2 servers'
  });
});

test('distinguishes zero overdue servers from an empty fleet and missing data', () => {
  assert.equal(formatOverdueEc2Share({ available: true, overdueAwsServers: 0, totalAwsServers: 4 }).value, '0%');
  assert.equal(formatOverdueEc2Share({ available: true, overdueAwsServers: 0, totalAwsServers: 0 }).value, 'No current EC2 servers');
  assert.equal(formatOverdueEc2Share(null).value, 'Not available');
});
