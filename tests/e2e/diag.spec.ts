import { test } from '@playwright/test';
test('diagnose', async ({ page }) => {
  page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') console.log('CONSOLE', m.type(), m.text().slice(0, 300)); });
  page.on('pageerror', (e) => console.log('PAGEERROR', String(e).slice(0, 400)));
  const res = await page.request.post('/api/auth/login', {
    data: { username: process.env.SECMAN_ADMIN_NAME!, password: process.env.SECMAN_ADMIN_PASS! },
  });
  const user = await res.json();
  console.log('ROLES', JSON.stringify(user.roles));
  await page.goto('/login');
  await page.evaluate((u) => sessionStorage.setItem('user', JSON.stringify(u)), user);
  await page.goto('/requirements');
  await page.waitForTimeout(4000);
  const txt = await page.evaluate(() => document.body.innerText.slice(0, 300));
  console.log('BODY TEXT >>>', txt.replace(/\n+/g, ' | '));
});
