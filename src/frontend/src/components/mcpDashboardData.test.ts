import assert from 'node:assert/strict';
import test from 'node:test';
import { loadMcpDashboardData } from './mcpDashboardData.ts';

test('starts both dashboard requests before either responds', async () => {
  const pending = new Map<string, (response: Response) => void>();
  const fetcher = ((url: string, options: RequestInit) => {
    assert.equal(options.credentials, 'include');
    return new Promise<Response>(resolve => pending.set(url, resolve));
  }) as typeof fetch;

  const loading = loadMcpDashboardData(fetcher);
  assert.deepEqual([...pending.keys()], [
    '/api/mcp/admin/statistics',
    '/api/mcp/admin/audit-logs?pageSize=10',
  ]);

  pending.get('/api/mcp/admin/audit-logs?pageSize=10')!(Response.json({ logs: [{ id: 7 }] }));
  pending.get('/api/mcp/admin/statistics')!(Response.json({ sessions: { totalActive: 3 } }));
  const result = await loading;
  assert.equal(result.stats?.sessions.totalActive, 3);
  assert.equal(result.recentActivity?.[0].id, 7);
  assert.equal(result.failed, false);
});

test('keeps successful activity when statistics fails', async () => {
  const fetcher = ((url: string) => url.endsWith('/statistics')
    ? Promise.reject(new Error('network error'))
    : Promise.resolve(Response.json({ logs: [{ id: 8 }] }))) as typeof fetch;

  const result = await loadMcpDashboardData(fetcher);
  assert.equal(result.stats, null);
  assert.equal(result.recentActivity?.[0].id, 8);
  assert.equal(result.failed, true);
});

test('does not treat an unsuccessful HTTP response as a network failure', async () => {
  const fetcher = (async () => new Response(null, { status: 403 })) as typeof fetch;

  const result = await loadMcpDashboardData(fetcher);
  assert.deepEqual(result, { stats: null, recentActivity: null, failed: false });
});
