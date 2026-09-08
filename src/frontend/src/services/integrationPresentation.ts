export interface FindingFilters {
  source?: string;
  scannerId?: number;
  subjectId?: number;
  githubRepositoryId?: number;
  owner?: string;
  severity?: string;
  state?: string;
  search?: string;
}

export function findingQuery(filters: FindingFilters, page = 0): string {
  const params = new URLSearchParams({ page: String(page), size: '25' });
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== '') params.set(key, String(value));
  }
  return params.toString();
}

export function safeEvidenceUrl(value?: string | null): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : null;
  } catch {
    return null;
  }
}

export function coverageLabel(subject: { lastStatus?: string | null; stale: boolean }): string {
  if (!subject.lastStatus) return 'Not scanned';
  if (subject.lastStatus === 'FAILED') return 'Failed';
  if (subject.stale) return 'Stale';
  if (subject.lastStatus === 'PARTIAL') return 'Partial';
  if (subject.lastStatus === 'SKIPPED') return 'Skipped';
  return 'Current';
}
