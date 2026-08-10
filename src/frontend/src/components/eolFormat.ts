/**
 * Pure presentation helpers for the EOL views.
 *
 * Extracted out of `EolDashboard.tsx` on purpose: the frontend unit tier runs on
 * Node's own runner with type stripping and cannot parse JSX, so anything that
 * deserves a test has to live in a sibling `.ts` module
 * (CLAUDE.md §Test Infrastructure / docs/TESTING.md §Frontend).
 */

export type EolStatus = 'EOL' | 'APPROACHING_EOL' | 'SUPPORTED';

export interface EolStatusBadge {
  label: string;
  className: string;
}

/** Bootstrap badge class per lifecycle state. */
export function statusBadge(status: EolStatus): EolStatusBadge {
  switch (status) {
    case 'EOL':
      return { label: 'End of life', className: 'badge bg-danger' };
    case 'APPROACHING_EOL':
      return { label: 'Approaching EOL', className: 'badge bg-warning text-dark' };
    default:
      return { label: 'Supported', className: 'badge bg-success' };
  }
}

/**
 * Human deadline for a finding.
 *
 * `daysUntilEol` is null when the upstream catalogue flagged a cycle as end of
 * life without publishing a date — that must read as "already end of life",
 * never as "0 days left", which would imply a precision we do not have.
 */
export function describeDeadline(eolDate: string | null | undefined, daysUntilEol: number | null | undefined): string {
  if (!eolDate) {
    return 'already end of life (no date published)';
  }
  if (daysUntilEol === null || daysUntilEol === undefined) {
    return eolDate;
  }
  if (daysUntilEol < 0) {
    return `${eolDate} (${Math.abs(daysUntilEol)} days ago)`;
  }
  if (daysUntilEol === 0) {
    return `${eolDate} (today)`;
  }
  return `${eolDate} (in ${daysUntilEol} days)`;
}

/**
 * Urgency bucket used to sort and colour rows. Lower sorts first.
 * Anything already past EOL outranks everything still in the future.
 */
export function urgencyRank(status: EolStatus, daysUntilEol: number | null | undefined): number {
  if (status === 'EOL') return -1;
  if (daysUntilEol === null || daysUntilEol === undefined) return Number.MAX_SAFE_INTEGER;
  return daysUntilEol;
}

/** Display name for a component, falling back through the fields we may have. */
export function componentLabel(name: string, vendor?: string | null, version?: string | null): string {
  const parts: string[] = [];
  if (vendor && vendor.trim() && !name.toLowerCase().startsWith(vendor.trim().toLowerCase())) {
    parts.push(vendor.trim());
  }
  parts.push(name);
  if (version && version.trim()) {
    parts.push(version.trim());
  }
  return parts.join(' ');
}

/**
 * Key used to mark an installed-product row as EOL on the installed-products
 * page. Both sides must normalize identically or the badge silently never shows.
 */
export function assetComponentKey(assetId: number | null | undefined, componentName: string): string {
  return `${assetId ?? 0}::${componentName.trim().toLowerCase()}`;
}

/** Subject-type label shown in the findings table. */
export function subjectLabel(subjectType: string): string {
  switch (subjectType) {
    case 'ASSET_OS':
      return 'Operating system';
    case 'ASSET_PRODUCT':
      return 'Installed software';
    case 'REPOSITORY_COMPONENT':
      return 'Repository dependency';
    default:
      return subjectType;
  }
}
