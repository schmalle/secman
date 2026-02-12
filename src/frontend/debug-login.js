// Debug script to test login manually
import { chromium } from 'playwright';

(async () => {
  const browser = await chromium.launch({ headless: false, slowMo: 500 });
  const page = await browser.newPage();

  try {
    console.log('Navigating to login page...');
    await page.goto('http://localhost:4321/login');
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    console.log('Filling login form...');
    await page.fill('input[id="username"]', 'adminuser');
    await page.fill('input[id="password"]', 'password');

    console.log('Submitting form...');

    // Listen for network requests
    page.on('response', response => {
      if (response.url().includes('/api/auth/login')) {
        console.log(`Login API response: ${response.status()}`);
        response.json().then(data => console.log('Response body:', data)).catch(() => {});
      }
    });

    await page.click('button[type="submit"]');

    // Wait a bit
    await page.waitForTimeout(5000);

    console.log('Current URL:', page.url());
    console.log('LocalStorage:', await page.evaluate(() => {
      return {
        authToken: localStorage.getItem('authToken') || '(migrated to HttpOnly cookie)',
        user: localStorage.getItem('user')
      };
    }));

  } catch (error) {
    console.error('Error:', error.message);
    await page.screenshot({ path: 'debug-error.png' });
    console.log('Screenshot saved to debug-error.png');
  } finally {
    await browser.close();
  }
})();
