import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

/**
 * The page CSP is authored as a literal <meta http-equiv="Content-Security-Policy"> in each
 * layout, so nothing in the build or type system checks it. These tests pin the directives
 * that a feature would otherwise break invisibly.
 *
 * `img-src ... blob:` is the one that has already bitten: ProfilePictureCard loads the file a
 * user picked via URL.createObjectURL(file), and a blob: URL is NOT covered by 'self'. Without
 * the directive the browser blocks the load, the <img> fires onerror, and the cropper reports
 * "That file could not be read as an image" — for every format, having never contacted the
 * backend. That failure is invisible until somebody actually uploads a picture.
 */

const layoutsDir = dirname(fileURLToPath(import.meta.url));

const LAYOUTS = ['Layout.astro', 'BaseLayout.astro'];

function cspOf(layout: string): string {
  const source = readFileSync(join(layoutsDir, layout), 'utf8');
  const match = source.match(/http-equiv="Content-Security-Policy"\s+content="([^"]+)"/);
  assert.ok(match, `${layout} has no Content-Security-Policy meta tag`);
  return match![1];
}

function directive(csp: string, name: string): string {
  const found = csp
    .split(';')
    .map((part) => part.trim())
    .find((part) => part === name || part.startsWith(`${name} `));
  assert.ok(found, `CSP has no ${name} directive: ${csp}`);
  return found!;
}

for (const layout of LAYOUTS) {
  test(`${layout} allows blob: images so the profile-picture cropper can read the picked file`, () => {
    assert.match(directive(cspOf(layout), 'img-src'), /\bblob:/);
  });

  test(`${layout} still restricts img-src to same-origin plus data: and blob:`, () => {
    // Guards the fix from being over-applied into a wildcard.
    assert.doesNotMatch(directive(cspOf(layout), 'img-src'), /\*/);
  });

  test(`${layout} keeps object-src 'none'`, () => {
    assert.equal(directive(cspOf(layout), 'object-src'), "object-src 'none'");
  });
}
