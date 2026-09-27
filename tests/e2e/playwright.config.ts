import { defineConfig } from '@playwright/test';
import { requireIsolatedTarget } from './helpers/isolated-target';

requireIsolatedTarget();

export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.ts',

  timeout: 60_000,
  expect: {
    timeout: 10_000,
  },

  retries: 0,

  reporter: [
    ['html', { open: 'never' }],
    ['list'],
  ],

  use: {
    baseURL: process.env.FRONTEND_URL || process.env.SECMAN_BASE_URL || process.env.SECMAN_BACKEND_URL || 'http://localhost:4321',
    ignoreHTTPSErrors: true,
    navigationTimeout: 30_000,
    actionTimeout: 10_000,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },

  projects: [
    {
      name: 'chrome',
      use: { channel: 'chrome' },
    },
    {
      name: 'msedge',
      use: { channel: 'msedge' },
    },
  ],
});
