import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

// Resolve playwright from tests/e2e/node_modules (ESM doesn't support NODE_PATH)
const __dirname = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(__dirname, 'e2e', 'package.json'));
const { chromium } = require('playwright');

// --- Environment variables ---
const USERNAME = process.env.SECMAN_USERNAME;
const PASSWORD = process.env.SECMAN_PASSWORD;
const BASE_URL = process.env.SECMAN_BACKEND_URL;
const INSECURE = process.env.SECMAN_INSECURE;

if (!USERNAME || !PASSWORD || !BASE_URL) {
  console.error('ERROR: Missing required environment variables.');
  console.error('  SECMAN_USERNAME  = login username');
  console.error('  SECMAN_PASSWORD  = login password');
  console.error('  SECMAN_BACKEND_URL = secman instance URL (e.g. https://secman.example.com)');
  process.exit(2);
}

// --- SSL flag parsing (true/1/yes, case-insensitive) ---
const insecureLower = (INSECURE || '').toLowerCase();
const ignoreHTTPS = ['true', '1', 'yes'].includes(insecureLower);

// --- Static page list (derived from src/frontend/src/pages/**/*.astro) ---
// Excludes dynamic [id]/[token] segments. Grouped by category.
const PAGES = [
  // Root
  '/',

  // Core
  '/assets',
  '/asset',
  '/scans',
  '/import',
  '/export',
  '/import-export',

  // Vulnerabilities
  '/vulnerabilities/current',
  '/vulnerabilities/domain',
  '/vulnerabilities/system',
  '/vulnerabilities/exceptions',
  '/vulnerability-statistics',
  '/wg-vulns',
  '/account-vulns',

  // Requirements
  '/requirements',
  '/standards',
  '/norms',
  '/usecases',
  '/demands',
  '/products',
  '/reqdl',

  // Risk
  '/risks',
  '/risk-assessments',
  '/riskassessment',

  // Releases
  '/releases',
  '/releases/compare',

  // User
  '/profile',
  '/notification-preferences',
  '/notification-logs',
  '/my-exception-requests',
  '/exception-approvals',
  '/workgroups',
  '/user-management',
  '/aws-account-sharing',

  // Reports
  '/reports',
  '/public-classification',

  // Outdated assets
  '/outdated-assets',

  // Admin
  '/admin',
  '/admin/user-management',
  '/admin/identity-providers',
  '/admin/email-config',
  '/admin/falcon-config',
  '/admin/vulnerability-config',
  '/admin/translation-config',
  '/admin/notification-settings',
  '/admin/maintenance-banners',
  '/admin/requirements',
  '/admin/releases',
  '/admin/user-mappings',
  '/admin/test-email-accounts',
  '/admin/classification-rules',
  '/admin/config-bundle',
  '/admin/mcp-api-keys',
  '/admin/app-settings',
  '/admin/aws-account-sharing',
  '/admin/ec2-compliance',

  // About
  '/about',
];

const PAGE_TIMEOUT = 30_000; // 30 seconds per page
const HYDRATION_WAIT = 3_000; // 3 seconds for React hydration + initial API calls

// Console error patterns to ignore (browser-level noise, not application bugs)
const IGNORED_CONSOLE_PATTERNS = [
  /net::ERR_INVALID_HANDLE/,  // Chromium artifact from SSE connections interrupted by navigation
  /net::ERR_NETWORK_IO_SUSPENDED/,  // Chromium artifact from network requests interrupted by page navigation
  /^Failed to load resource:/,  // Browser-level HTTP error logging — redundant with [HTTP xxx] tracking
  /^%cAstro/,  // Astro framework internal audit/debug messages
];

// --- Runtime data structures ---
// PageResult: { uri, status: 'clean'|'errors'|'timeout'|'session_expired', uncaughtExceptions[], consoleErrors[], httpErrors[], loadTimeMs }
// httpErrors[]: { url, status, statusText, method } — API responses with 4xx/5xx status codes
const results = [];

// --- Helper: format progress line ---
function progressLine(index, total, uri, status) {
  const num = `[${String(index + 1).padStart(String(total).length)}/${total}]`;
  const maxUriLen = 40;
  const paddedUri = uri.length < maxUriLen
    ? uri + ' ' + '.'.repeat(maxUriLen - uri.length - 1)
    : uri;
  return `${num} ${paddedUri} ${status}`;
}

// --- Main ---
async function main() {
  const host = BASE_URL.replace(/\/+$/, '');
  const totalPages = PAGES.length;

  console.log(`Host: ${host}`);
  if (ignoreHTTPS) {
    console.log('SSL: Accepting self-signed certificates');
  }
  console.log(`Scanning ${totalPages} pages...`);
  console.log('');

  // Launch browser
  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext({
      ignoreHTTPSErrors: ignoreHTTPS,
    });
    const page = await context.newPage();

    // --- Reachability pre-check ---
    try {
      await page.goto(host, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT });
    } catch (err) {
      console.error(`ERROR: Cannot reach ${host}`);
      console.error(`  ${err.message}`);
      process.exit(2);
    }

    // --- Login flow ---
    try {
      await page.goto(`${host}/login`, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT });
      // Wait for the login form to be rendered by React hydration
      await page.locator('#username').waitFor({ state: 'visible', timeout: 15_000 });
      await page.locator('#username').fill(USERNAME);
      await page.locator('#password').fill(PASSWORD);
      await page.locator('button[type="submit"]').click();
      await page.waitForURL((url) => !url.pathname.includes('/login'), {
        timeout: 15_000,
      });
    } catch (err) {
      console.error('ERROR: Login failed.');
      console.error(`  ${err.message}`);
      process.exit(2);
    }

    // --- Page iteration ---
    for (let i = 0; i < totalPages; i++) {
      const uri = PAGES[i];
      const url = `${host}${uri}`;

      const pageResult = {
        uri,
        status: 'clean',
        uncaughtExceptions: [],
        consoleErrors: [],
        httpErrors: [],
        loadTimeMs: 0,
      };

      // Register error listeners before navigation
      const onPageError = (err) => {
        pageResult.uncaughtExceptions.push(err.message || String(err));
      };
      const onConsole = (msg) => {
        if (msg.type() === 'error') {
          const text = msg.text();
          const isIgnored = IGNORED_CONSOLE_PATTERNS.some((re) => re.test(text));
          if (!isIgnored) {
            pageResult.consoleErrors.push(text);
          }
        }
      };
      const onResponse = (response) => {
        const status = response.status();
        if (status >= 400) {
          const respUrl = response.url();
          // Only track API/backend errors, not static asset 404s
          if (respUrl.includes('/api/') || respUrl.includes('/mcp')) {
            pageResult.httpErrors.push({
              url: respUrl,
              status,
              statusText: response.statusText(),
              method: response.request().method(),
            });
          }
        }
      };

      page.on('pageerror', onPageError);
      page.on('console', onConsole);
      page.on('response', onResponse);

      const startTime = Date.now();

      try {
        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT });
        // Wait for React hydration and initial API calls to complete.
        // Using domcontentloaded instead of networkidle because secman pages use
        // SSE (Server-Sent Events) for real-time notifications, which keep a
        // persistent connection open and cause networkidle to never resolve.
        await page.waitForTimeout(HYDRATION_WAIT);
        pageResult.loadTimeMs = Date.now() - startTime;

        // Session expiry detection: check if redirected back to /login
        const currentUrl = page.url();
        if (currentUrl.includes('/login')) {
          pageResult.status = 'session_expired';
        } else if (pageResult.uncaughtExceptions.length > 0 || pageResult.consoleErrors.length > 0 || pageResult.httpErrors.length > 0) {
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

      // Remove listeners to avoid accumulation
      page.removeListener('pageerror', onPageError);
      page.removeListener('console', onConsole);
      page.removeListener('response', onResponse);

      results.push(pageResult);

      // Progress output
      const errorCount = pageResult.uncaughtExceptions.length + pageResult.consoleErrors.length + pageResult.httpErrors.length;
      let statusText;
      switch (pageResult.status) {
        case 'clean':
          statusText = 'CLEAN';
          break;
        case 'errors':
          statusText = `${errorCount} error${errorCount !== 1 ? 's' : ''}`;
          break;
        case 'timeout':
          statusText = `TIMEOUT (${Math.round(PAGE_TIMEOUT / 1000)}s)`;
          break;
        case 'session_expired':
          statusText = 'SESSION EXPIRED';
          break;
      }
      console.log(progressLine(i, totalPages, uri, statusText));
    }
  } finally {
    await browser.close();
  }

  // --- Summary report ---
  console.log('');
  console.log('=== SCAN RESULTS ===');

  const errorPages = results.filter((r) => r.status === 'errors');
  const timeoutPages = results.filter((r) => r.status === 'timeout');
  const expiredPages = results.filter((r) => r.status === 'session_expired');
  const cleanPages = results.filter((r) => r.status === 'clean');

  const hasIssues = errorPages.length > 0 || timeoutPages.length > 0 || expiredPages.length > 0;

  if (hasIssues) {
    console.log('');
    console.log('Pages with errors:');

    for (const r of errorPages) {
      console.log('');
      console.log(`  ${r.uri}`);
      for (const e of r.uncaughtExceptions) {
        console.log(`    [UNCAUGHT EXCEPTION] ${e}`);
      }
      for (const e of r.consoleErrors) {
        console.log(`    [CONSOLE ERROR] ${e}`);
      }
      for (const e of r.httpErrors) {
        console.log(`    [HTTP ${e.status}] ${e.method} ${e.url} — ${e.statusText}`);
      }
    }

    for (const r of timeoutPages) {
      console.log('');
      console.log(`  ${r.uri}`);
      console.log(`    [TIMEOUT] Page did not reach networkidle within ${Math.round(PAGE_TIMEOUT / 1000)}s`);
    }

    for (const r of expiredPages) {
      console.log('');
      console.log(`  ${r.uri}`);
      console.log(`    [SESSION EXPIRED] Redirected back to /login — session may have timed out`);
    }
  } else {
    console.log('');
    console.log('No JavaScript errors found on any page.');
  }

  console.log('');
  console.log(
    `Summary: ${results.length} pages scanned | ${cleanPages.length} clean | ${errorPages.length} errors | ${timeoutPages.length} timeout` +
    (expiredPages.length > 0 ? ` | ${expiredPages.length} session expired` : '')
  );

  process.exitCode = hasIssues ? 1 : 0;
}

main().catch((err) => {
  console.error('FATAL:', err.message);
  process.exit(2);
});
