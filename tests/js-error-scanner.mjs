import { createRequire } from 'node:module';
import { readdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative, sep } from 'node:path';

// Resolve playwright from tests/e2e/node_modules (ESM doesn't support NODE_PATH)
const __dirname = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(__dirname, 'e2e', 'package.json'));
const { chromium } = require('playwright');

const USERNAME = process.env.SECMAN_LOGIN_USER || process.env.SECMAN_ADMIN_NAME;
const PASSWORD = process.env.SECMAN_LOGIN_PASS || process.env.SECMAN_ADMIN_PASS;
const BASE_URL = process.env.SECMAN_BACKEND_URL;
const INSECURE = process.env.SECMAN_INSECURE;
const RUN_LABEL = process.env.SECMAN_RUN_LABEL || '';
const JSON_OUT = process.env.SECMAN_SCAN_JSON_OUT || '';

if (!USERNAME || !PASSWORD || !BASE_URL) {
  console.error('ERROR: Missing required environment variables.');
  process.exit(2);
}

const insecureLower = (INSECURE || '').toLowerCase();
const ignoreHTTPS = ['true', '1', 'yes'].includes(insecureLower);
const IS_ADMIN_RUN = RUN_LABEL.toLowerCase() === 'admin';

const PAGE_TIMEOUT = 30_000;
const HYDRATION_WAIT = 3_000;

const IGNORED_CONSOLE_PATTERNS = [
  /net::ERR_INVALID_HANDLE/,
  /net::ERR_NETWORK_IO_SUSPENDED/,
  /^%cAstro/,
];

const PAGE_EXCLUDE_PREFIXES = ['/api', '/mcp'];
const PAGE_EXCLUDE_EXACT = ['/login', '/login/success', '/404', '/500'];
const STATIC_PAGES = ['/'];

function walk(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...walk(path));
    } else {
      out.push(path);
    }
  }
  return out;
}

function toRoute(filePath, pagesRoot) {
  const rel = relative(pagesRoot, filePath).split(sep).join('/');
  if (!rel.endsWith('.astro')) return null;
  if (rel.includes('[')) return null;
  const noExt = rel.replace(/\.astro$/, '');
  const route = noExt === 'index' ? '/' : noExt.endsWith('/index') ? `/${noExt.slice(0, -'/index'.length)}` : `/${noExt}`;
  if (PAGE_EXCLUDE_EXACT.includes(route)) return null;
  if (PAGE_EXCLUDE_PREFIXES.some((p) => route.startsWith(p))) return null;
  return route;
}

function discoverPages() {
  const pagesRoot = join(__dirname, '..', 'src', 'frontend', 'src', 'pages');
  const discovered = walk(pagesRoot)
    .map((f) => toRoute(f, pagesRoot))
    .filter(Boolean);
  const pages = [...new Set([...STATIC_PAGES, ...discovered])].sort();
  if (pages.length < 20) {
    throw new Error(`Route discovery returned too few pages (${pages.length}).`);
  }
  return pages;
}

function progressLine(index, total, uri, status) {
  const num = `[${String(index + 1).padStart(String(total).length)}/${total}]`;
  const maxUriLen = 40;
  const paddedUri = uri.length < maxUriLen ? uri + ' ' + '.'.repeat(maxUriLen - uri.length - 1) : uri;
  return `${num} ${paddedUri} ${status}`;
}

function isExpectedHttpError(uri, httpError) {
  const url = httpError.url;
  if (!IS_ADMIN_RUN && httpError.status === 403) {
    if (url.includes('/api/')) {
      return true;
    }
  }
  if (httpError.status === 404 && (url.includes('/api/account-vulns') || url.includes('/api/domain-vulns'))) {
    return true;
  }
  return false;
}

async function main() {
  const pages = discoverPages();
  const results = [];
  let host = BASE_URL.replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(host)) host = `https://${host}`;

  console.log(`Host: ${host}`);
  if (RUN_LABEL) console.log(`Run:  ${RUN_LABEL} (user=${USERNAME})`);
  console.log(`Scanning ${pages.length} pages (discovered from frontend routes)...\n`);

  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext({ ignoreHTTPSErrors: ignoreHTTPS });
    const page = await context.newPage();

    try {
      await page.goto(host, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT });
    } catch (err) {
      console.error(`ERROR: Cannot reach ${host}`);
      console.error(`  ${err.message}`);
      process.exit(2);
    }

    try {
      await page.goto(`${host}/login`, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT });
      await page.locator('#username').waitFor({ state: 'visible', timeout: 15_000 });
      await page.locator('astro-island:not([ssr])').first().waitFor({ state: 'attached', timeout: 30_000 });
      await page.locator('#username').fill(USERNAME);
      await page.locator('#password').fill(PASSWORD);
      await page.locator('button[type="submit"]').click();
      await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30_000 });
    } catch (err) {
      console.error('ERROR: Login failed.');
      console.error(`  ${err.message}`);
      process.exit(2);
    }

    for (let i = 0; i < pages.length; i++) {
      const uri = pages[i];
      const url = `${host}${uri}`;
      const pageResult = { uri, status: 'clean', uncaughtExceptions: [], consoleErrors: [], httpErrors: [], expectedHttpErrors: [], loadTimeMs: 0 };

      const onPageError = (err) => pageResult.uncaughtExceptions.push(err.message || String(err));
      const onConsole = (msg) => {
        if (msg.type() === 'error') {
          const text = msg.text();
          if (!IS_ADMIN_RUN && text === 'Failed to load resource: the server responded with a status of 403 (Forbidden)') {
            return;
          }
          if (text === 'Failed to load resource: the server responded with a status of 404 (Not Found)') {
            return;
          }
          if (!IGNORED_CONSOLE_PATTERNS.some((re) => re.test(text))) {
            pageResult.consoleErrors.push(text);
          }
        }
      };
      const onResponse = (response) => {
        const status = response.status();
        if (status >= 400) {
          const respUrl = response.url();
          if (respUrl.includes('/api/') || respUrl.includes('/mcp')) {
            const err = { url: respUrl, status, statusText: response.statusText(), method: response.request().method() };
            if (isExpectedHttpError(uri, err)) {
              pageResult.expectedHttpErrors.push(err);
            } else {
              pageResult.httpErrors.push(err);
            }
          }
        }
      };

      page.on('pageerror', onPageError);
      page.on('console', onConsole);
      page.on('response', onResponse);

      const startTime = Date.now();
      try {
        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT });
        await page.waitForTimeout(HYDRATION_WAIT);
        pageResult.loadTimeMs = Date.now() - startTime;
        const currentUrl = page.url();
        if (currentUrl.includes('/login')) {
          pageResult.status = 'session_expired';
        } else if (pageResult.uncaughtExceptions.length || pageResult.consoleErrors.length || pageResult.httpErrors.length) {
          pageResult.status = 'errors';
        }
      } catch (err) {
        pageResult.loadTimeMs = Date.now() - startTime;
        if (err.message && err.message.includes('Timeout')) {
          pageResult.status = 'timeout';
        } else {
          pageResult.status = 'errors';
          pageResult.uncaughtExceptions.push(`Navigation error: ${err.message}`);
        }
      }

      page.removeListener('pageerror', onPageError);
      page.removeListener('console', onConsole);
      page.removeListener('response', onResponse);
      results.push(pageResult);

      const errorCount = pageResult.uncaughtExceptions.length + pageResult.consoleErrors.length + pageResult.httpErrors.length;
      const statusText = pageResult.status === 'clean' ? 'CLEAN' :
        pageResult.status === 'errors' ? `${errorCount} error${errorCount !== 1 ? 's' : ''}` :
          pageResult.status === 'timeout' ? `TIMEOUT (${Math.round(PAGE_TIMEOUT / 1000)}s)` : 'SESSION EXPIRED';
      console.log(progressLine(i, pages.length, uri, statusText));
    }
  } finally {
    await browser.close();
  }

  const errorPages = results.filter((r) => r.status === 'errors');
  const timeoutPages = results.filter((r) => r.status === 'timeout');
  const expiredPages = results.filter((r) => r.status === 'session_expired');
  const cleanPages = results.filter((r) => r.status === 'clean');
  const expectedDeniedCount = results.reduce((sum, r) => sum + r.expectedHttpErrors.length, 0);
  const hasIssues = errorPages.length > 0 || timeoutPages.length > 0 || expiredPages.length > 0;

  console.log('\n=== SCAN RESULTS ===\n');
  if (hasIssues) {
    console.log('Pages with errors:');
    for (const r of errorPages) {
      console.log(`\n  ${r.uri}`);
      for (const e of r.uncaughtExceptions) console.log(`    [UNCAUGHT EXCEPTION] ${e}`);
      for (const e of r.consoleErrors) console.log(`    [CONSOLE ERROR] ${e}`);
      for (const e of r.httpErrors) console.log(`    [HTTP ${e.status}] ${e.method} ${e.url} — ${e.statusText}`);
    }
    for (const r of timeoutPages) console.log(`\n  ${r.uri}\n    [TIMEOUT] Page did not complete DOMContentLoaded + hydration wait (${Math.round(PAGE_TIMEOUT / 1000)}s nav + ${Math.round(HYDRATION_WAIT / 1000)}s hydration)`);
    for (const r of expiredPages) console.log(`\n  ${r.uri}\n    [SESSION EXPIRED] Redirected back to /login — session may have timed out`);
  } else {
    console.log('No JavaScript errors found on any page.');
  }

  const runPrefix = RUN_LABEL ? `[${RUN_LABEL}] ` : '';
  console.log(`\n${runPrefix}Summary: ${results.length} pages scanned | ${cleanPages.length} clean | ${errorPages.length} errors | ${timeoutPages.length} timeout${expiredPages.length > 0 ? ` | ${expiredPages.length} session expired` : ''} | ${expectedDeniedCount} expected RBAC denials`);

  if (JSON_OUT) {
    writeFileSync(JSON_OUT, JSON.stringify({
      timestamp: new Date().toISOString(),
      host,
      runLabel: RUN_LABEL,
      username: USERNAME,
      pagesScanned: results.length,
      cleanPages: cleanPages.length,
      errorPages: errorPages.length,
      timeoutPages: timeoutPages.length,
      sessionExpiredPages: expiredPages.length,
      expectedRbacDenials: expectedDeniedCount,
      results,
    }, null, 2));
  }

  process.exitCode = hasIssues ? 1 : 0;
}

main().catch((err) => {
  console.error('FATAL:', err.message);
  process.exit(2);
});
