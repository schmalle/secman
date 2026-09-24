import { execFileSync } from 'node:child_process';

export function requireIsolatedTarget(): void {
  const db = process.env.SECMAN_TEST_ISOLATED_DB ?? '';
  const token = process.env.SECMAN_TEST_OWNER_TOKEN ?? '';
  const backend = process.env.SECMAN_E2E_BACKEND_URL ?? '';
  if (!/^secman_e2e_[a-f0-9]{16}$/.test(db) ||
      !/^[a-f0-9]{32}$/.test(token) ||
      process.env.DB_NAME !== db ||
      process.env.DB_CONNECT !== `jdbc:mariadb://127.0.0.1:3306/${db}` ||
      backend !== 'http://127.0.0.1:18080' ||
      !process.env.DB_USER || !process.env.DB_PASS) {
    throw new Error('Database-mutating Playwright specs require scripts/test/run-isolated-e2e.sh');
  }

  const actualToken = execFileSync('mariadb', [
    '--batch', '--skip-column-names', '-h', '127.0.0.1', '-u', process.env.DB_USER,
    db, '-e', 'SELECT owner_token FROM secman_e2e_owner LIMIT 1',
  ], {
    encoding: 'utf8',
    env: { ...process.env, MYSQL_PWD: process.env.DB_PASS },
    stdio: ['ignore', 'pipe', 'ignore'],
  }).trim();
  if (actualToken !== token) {
    throw new Error('Disposable database ownership marker does not match');
  }
}
