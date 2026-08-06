import assert from 'node:assert/strict';
import test from 'node:test';
import {
  calculateCacheAgeMinutes,
  formatCacheAge,
  getCacheBadgeVariant,
  getCacheIcon,
  isLiveData,
} from './cacheUtils.ts';

// These five functions drive what the CrowdStrike lookup tells an analyst about how
// old the data in front of them is, and they all derive from one arithmetic step.
// The bug this file guards against is the one dateUtils.ts already documents: a
// zoneless or microsecond timestamp parsed differently per browser, which shifted
// the age by the local UTC offset and made "Live data" appear on stale results.

/** A timestamp `secondsAgo` in the past, in the zoneless form the backend sends. */
function agedBySeconds(secondsAgo: number): string {
  return new Date(Date.now() - secondsAgo * 1000).toISOString();
}

test('age is whole minutes, rounded down', () => {
  assert.equal(calculateCacheAgeMinutes(agedBySeconds(0)), 0);
  assert.equal(calculateCacheAgeMinutes(agedBySeconds(59)), 0);
  assert.equal(calculateCacheAgeMinutes(agedBySeconds(90)), 1);
  assert.equal(calculateCacheAgeMinutes(agedBySeconds(7 * 60 + 30)), 7);
});

test('a future timestamp yields a negative age rather than clamping', () => {
  // Clock skew between the backend and the browser is real. Nothing downstream
  // treats negatives specially — they all fall into the "< 1" branch and read as
  // live — so the behaviour is pinned here rather than left to be rediscovered.
  const future = new Date(Date.now() + 5 * 60 * 1000).toISOString();
  assert.ok(calculateCacheAgeMinutes(future) < 0);
  assert.equal(isLiveData(future), true);
});

test('a zoneless backend timestamp is read as UTC, not as browser-local', () => {
  // This is the divergence dateUtils.parseServerDate exists to remove: dropping the
  // trailing Z must not shift the age by the local offset.
  const withZone = new Date(Date.now() - 3 * 60 * 1000).toISOString();
  const zoneless = withZone.replace('Z', '');

  assert.equal(calculateCacheAgeMinutes(zoneless), calculateCacheAgeMinutes(withZone));
});

test('microsecond precision does not change the computed age', () => {
  const base = new Date(Date.now() - 4 * 60 * 1000).toISOString(); // ...sss Z
  const micro = base.replace('Z', '830'); // ...ssssss, zoneless — what MariaDB emits

  assert.equal(calculateCacheAgeMinutes(micro), calculateCacheAgeMinutes(base));
});

test('an unparseable timestamp reports as live — a known wart, pinned deliberately', () => {
  // calculateCacheAgeMinutes returns 0 when parsing fails, and 0 means "Live data"
  // everywhere downstream. So a malformed timestamp is displayed as the freshest
  // possible result rather than as unknown. Changing that is a product decision;
  // until it is made, this asserts what actually happens so nobody assumes better.
  for (const bad of ['', 'not-a-date', 'null']) {
    assert.equal(calculateCacheAgeMinutes(bad), 0, `age for ${JSON.stringify(bad)}`);
    assert.equal(isLiveData(bad), true, `isLiveData for ${JSON.stringify(bad)}`);
    assert.equal(formatCacheAge(bad), 'Live data');
  }
});

test('the live threshold is one whole minute', () => {
  assert.equal(isLiveData(agedBySeconds(30)), true);
  assert.equal(isLiveData(agedBySeconds(59)), true);
  assert.equal(isLiveData(agedBySeconds(90)), false);
});

test('display text switches from live to a cached age', () => {
  assert.equal(formatCacheAge(agedBySeconds(30)), 'Live data');
  assert.equal(formatCacheAge(agedBySeconds(90)), 'Cached (1 min ago)');
  assert.equal(formatCacheAge(agedBySeconds(15 * 60)), 'Cached (15 min ago)');
});

test('badge variant steps at 1, 5 and 10 minutes', () => {
  const cases: Array<[number, string]> = [
    [30, 'success'],        // < 1 min
    [90, 'info'],           // 1 min
    [4 * 60 + 59, 'info'],  // just under 5
    [5 * 60, 'warning'],    // exactly 5
    [9 * 60 + 59, 'warning'],
    [10 * 60, 'secondary'], // exactly 10
    [60 * 60, 'secondary'], // far past the documented 15-min range
  ];

  for (const [secondsAgo, expected] of cases) {
    assert.equal(
      getCacheBadgeVariant(agedBySeconds(secondsAgo)),
      expected,
      `${secondsAgo}s ago`
    );
  }
});

test('icon distinguishes live from cached only', () => {
  assert.equal(getCacheIcon(agedBySeconds(30)), 'bi-lightning-charge-fill');
  assert.equal(getCacheIcon(agedBySeconds(90)), 'bi-clock-history');
  assert.equal(getCacheIcon(agedBySeconds(60 * 60)), 'bi-clock-history');
});
