import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import test from 'node:test';

const script = readFileSync(new URL('./chunkRecovery.js', import.meta.url), 'utf8');

function page(status = 404, storage = new Map<string, string>(), blockedStorage = false) {
  const listeners = new Map<string, (event: any) => Promise<void>>();
  const requests: Array<{ url: string; options: any }> = [];
  let reloads = 0;
  const statuses = new Map<string, number>();
  class Element {
    tagName = 'ASTRO-ISLAND';
    children: any[] = [];
    textContent = '';
    attributes = new Map([['component-url', '/_astro/AssetManagement.old.js']]);
    getAttribute(name: string) { return this.attributes.get(name); }
    setAttribute(name: string, value: string) { this.attributes.set(name, value); }
    append(child: any) { this.children.push(child); }
    replaceChildren(child: any) { this.children = [child]; }
    addEventListener() {}
  }
  const island = new Element();
  runInNewContext(script, {
    HTMLElement: Element, URL, WeakSet,
    document: {
      addEventListener: (name: string, callback: any) => listeners.set(name, callback),
      createElement: () => new Element(),
    },
    location: {
      href: 'https://secman.example/assets?owner=me', origin: 'https://secman.example',
      pathname: '/assets', search: '?owner=me', reload: () => reloads++,
    },
    sessionStorage: {
      getItem(key: string) { if (blockedStorage) throw new Error('blocked'); return storage.get(key); },
      setItem: (key: string, value: string) => storage.set(key, value),
    },
    fetch: async (url: URL, options: any) => {
      requests.push({ url: url.href, options });
      if (status === 0) throw new Error('offline');
      return { status: statuses.get(url.pathname) ?? status };
    },
  });
  return { island, requests, statuses, reloads: () => reloads, storage,
    fail: (target = island) => listeners.get('astro:hydration-error')!({ target }) };
}

test('missing deployment bundle refreshes the current page once across page loads', async () => {
  const first = page();
  await first.fail();
  assert.equal(first.reloads(), 1);
  assert.equal(first.requests[0].options.method, 'HEAD');
  assert.equal(first.requests[0].options.cache, 'no-store');
  const second = page(404, first.storage);
  await second.fail();
  assert.equal(second.reloads(), 0);
  assert.equal(second.island.children[0].attributes.get('role'), 'alert');
  assert.match(second.island.children[0].textContent, /could not load/);
});

test('a missing renderer also refreshes when the component is present', async () => {
  const p = page(200);
  p.statuses.set('/_astro/client.old.js', 410);
  p.island.attributes.set('renderer-url', '/_astro/client.old.js');
  await p.fail();
  assert.equal(p.requests.length, 2);
  assert.equal(p.reloads(), 1);
});

test('concurrent island failures trigger only one refresh', async () => {
  const p = page();
  const other = Object.assign(Object.create(Object.getPrototypeOf(p.island)), p.island);
  await Promise.all([p.fail(), p.fail(other), p.fail()]);
  assert.equal(p.reloads(), 1);
});

for (const status of [200, 403, 500, 0]) {
  test(`status ${status} shows a fallback without a speculative reload`, async () => {
    const p = page(status);
    await p.fail();
    assert.equal(p.reloads(), 0);
    assert.equal(p.island.children[0].attributes.get('role'), 'alert');
  });
}

test('blocked storage shows a fallback instead of risking a refresh loop', async () => {
  const p = page(404, new Map(), true);
  await p.fail();
  assert.equal(p.reloads(), 0);
  assert.equal(p.island.children.length, 1);
});

test('does not probe external or non-build URLs', async () => {
  const p = page();
  p.island.attributes.set('component-url', 'https://external.example/_astro/file.js');
  p.island.attributes.set('renderer-url', '/api/private');
  await p.fail();
  assert.equal(p.requests.length, 0);
  assert.equal(p.reloads(), 0);
});
