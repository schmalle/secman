export interface SessionStats {
  totalActive: number;
  recentlyActive: number;
  byConnectionType: Record<string, number>;
  utilizationPercent: number;
}

export interface SystemStats {
  sessions: SessionStats;
  apiKeys: {
    totalActive: number;
    totalInactive: number;
    expiringSoon: number;
    recentlyUsed: number;
  };
  tools: {
    totalCalls: number;
    successRate: number;
    topTools: Array<{ name: string; calls: number }>;
    averageResponseTime: number;
  };
  security: {
    authFailures: number;
    permissionDenials: number;
    rateLimitHits: number;
    suspiciousActivity: number;
  };
}

export interface RecentActivity {
  id: number;
  timestamp: string;
  eventType: string;
  toolName?: string;
  success: boolean;
  clientIp?: string;
  errorMessage?: string;
}

async function fetchSection<T>(fetcher: typeof fetch, url: string): Promise<T | null> {
  const response = await fetcher(url, { credentials: 'include' });
  return response.ok ? response.json() as Promise<T> : null;
}

export async function loadMcpDashboardData(fetcher: typeof fetch = fetch) {
  const [stats, activity] = await Promise.allSettled([
    fetchSection<SystemStats>(fetcher, '/api/mcp/admin/statistics'),
    fetchSection<{ logs: RecentActivity[] }>(fetcher, '/api/mcp/admin/audit-logs?pageSize=10'),
  ]);

  return {
    stats: stats.status === 'fulfilled' ? stats.value : null,
    recentActivity: activity.status === 'fulfilled' && activity.value ? activity.value.logs || [] : null,
    failed: stats.status === 'rejected' || activity.status === 'rejected',
  };
}
