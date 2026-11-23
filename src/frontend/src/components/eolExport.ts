import type { EolFinding, EolFindingList } from '../services/eolService';

// EolQueryService caps pageSize at 500.
const PAGE_SIZE = 500;
// Keep the existing export limit even if a server reports a stale total.
const MAX_PAGES = 200;
const CONCURRENT_PAGES = 4;

/** Read all export pages in order while keeping at most four requests in flight. */
export async function loadEolExportFindings(
  fetchPage: (page: number, pageSize: number) => Promise<EolFindingList>,
): Promise<EolFinding[]> {
  const first = await fetchPage(0, PAGE_SIZE);
  const findings = [...first.findings];
  if (first.findings.length === 0) return findings;

  const pageCount = Math.min(MAX_PAGES, Math.ceil(first.total / PAGE_SIZE));
  for (let start = 1; start < pageCount; start += CONCURRENT_PAGES) {
    const pages = await Promise.all(
      Array.from(
        { length: Math.min(CONCURRENT_PAGES, pageCount - start) },
        (_, offset) => fetchPage(start + offset, PAGE_SIZE),
      ),
    );
    for (const page of pages) {
      if (page.findings.length === 0) return findings;
      findings.push(...page.findings);
      if (findings.length >= page.total) return findings;
    }
  }
  return findings;
}
