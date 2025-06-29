import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for check-in tests
 * 
 * Optimized for speed and reliability in CI/CD environments.
 * Focuses on critical path testing for pre-commit validation.
 */

export default defineConfig({
  testDir: './tests',
  
  // Only run check-in tests
  testMatch: '**/checkin-tests.spec.ts',
  
  // Optimize for CI performance
  fullyParallel: false, // Run sequentially for stability
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0, // Single retry in CI
  workers: 1, // Single worker for consistency
  
  // Faster timeout for check-in tests
  timeout: 60000, // 1 minute per test
  expect: {
    timeout: 10000 // 10 seconds for assertions
  },
  
  // Minimal reporting for CI
  reporter: [
    ['html', { open: 'never', outputFolder: 'checkin-test-results' }],
    ['json', { outputFile: 'checkin-test-results.json' }],
    ['list'] // Console output
  ],
  
  use: {
    baseURL: 'http://localhost:4321',
    
    // Optimize for performance
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    
    // Faster navigation
    navigationTimeout: 30000,
    actionTimeout: 15000,
  },

  // Only test on Chromium for check-ins (fastest)
  projects: [
    {
      name: 'checkin-chromium',
      use: { 
        ...devices['Desktop Chrome'],
        // Disable some features for speed
        launchOptions: {
          args: [
            '--disable-web-security',
            '--disable-features=TranslateUI',
            '--disable-ipc-flooding-protection',
            '--disable-renderer-backgrounding',
            '--disable-backgrounding-occluded-windows',
            '--disable-field-trial-config',
            '--disable-background-timer-throttling'
          ]
        }
      },
    },
  ],

  // Auto-start dev server for tests
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:4321',
    reuseExistingServer: !process.env.CI,
    timeout: 120000, // 2 minutes to start server
  },
});