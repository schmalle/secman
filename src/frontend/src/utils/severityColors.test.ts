import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { SEVERITY_HEX, THEME_HEX, hexToRgba, severityHex, severityHexAlpha } from './severityColors.ts';

// severityColors.ts exists so every chart, badge and status lamp uses one palette
// instead of each component reaching for its own Bootstrap red. Its hex values are
// hand-copied from styles/theme.css, which is the part that silently rots.

test('every severity the backend emits maps to its own colour', () => {
  assert.equal(severityHex('CRITICAL'), SEVERITY_HEX.critical);
  assert.equal(severityHex('HIGH'), SEVERITY_HEX.high);
  assert.equal(severityHex('MEDIUM'), SEVERITY_HEX.medium);
  assert.equal(severityHex('LOW'), SEVERITY_HEX.low);

  const distinct = new Set(Object.values(SEVERITY_HEX));
  assert.equal(distinct.size, Object.keys(SEVERITY_HEX).length, 'two severities share a colour');
});

test('severity lookup is case-insensitive', () => {
  // Severity reaches the frontend uppercase from the backend but lowercase from
  // some chart datasets, and both must land on the same colour.
  for (const value of ['critical', 'Critical', 'CRITICAL']) {
    assert.equal(severityHex(value), SEVERITY_HEX.critical, value);
  }
});

test('unknown, null and undefined severities fall back rather than throwing', () => {
  assert.equal(severityHex('INFORMATIONAL'), SEVERITY_HEX.unknown);
  assert.equal(severityHex(''), SEVERITY_HEX.unknown);
  assert.equal(severityHex(null), SEVERITY_HEX.unknown);
  assert.equal(severityHex(undefined), SEVERITY_HEX.unknown);
});

test('hexToRgba converts each channel and preserves alpha', () => {
  assert.equal(hexToRgba('#9B6B6B', 0.5), 'rgba(155, 107, 107, 0.5)');
  assert.equal(hexToRgba('#000000', 1), 'rgba(0, 0, 0, 1)');
  assert.equal(hexToRgba('#FFFFFF', 0), 'rgba(255, 255, 255, 0)');
});

test('severityHexAlpha composes the lookup with the alpha conversion', () => {
  assert.equal(severityHexAlpha('HIGH', 0.2), hexToRgba(SEVERITY_HEX.high, 0.2));
  assert.equal(severityHexAlpha('nonsense', 0.2), hexToRgba(SEVERITY_HEX.unknown, 0.2));
});

test('the palette still matches the CSS tokens it was copied from', () => {
  // The file says these values are "kept in sync manually" with theme.css. That is
  // the claim worth testing: nothing else notices when a token is retuned in CSS
  // and the charts keep painting the old colour.
  const theme = readFileSync(new URL('../styles/theme.css', import.meta.url), 'utf8');

  const tokens: Array<[string, string]> = [
    ['--scand-danger', SEVERITY_HEX.critical],
    ['--scand-warning', SEVERITY_HEX.high],
    ['--scand-info', SEVERITY_HEX.medium],
    ['--scand-muted', SEVERITY_HEX.low],
    ['--scand-text-secondary', SEVERITY_HEX.unknown],
    ['--scand-primary', THEME_HEX.primary],
    ['--scand-primary-light', THEME_HEX.primaryLight],
    ['--scand-success', THEME_HEX.success],
  ];

  for (const [token, expected] of tokens) {
    const match = theme.match(new RegExp(`${token}\\s*:\\s*(#[0-9a-fA-F]{6})`));
    assert.ok(match, `${token} not found in theme.css`);
    assert.equal(
      match![1].toLowerCase(),
      expected.toLowerCase(),
      `${token} drifted from SEVERITY_HEX — update severityColors.ts to match theme.css`
    );
  }
});
