import type { ExceptionScope, ExceptionKind } from './vulnerabilityManagementService';

export interface ExceptionRequestScopeTarget {
  scope: ExceptionScope;
  scopeValue?: string | null;
  assetId?: number | null;
  assetName?: string | null;
  /** Absent is treated as 'VULNERABILITY' — the meaning every row had before this axis existed. */
  kind?: ExceptionKind | null;
}

export interface ExceptionRequestScopeDisplay {
  label: string;
  title: string;
  iconClass: string;
  badgeClass: string;
}

export function formatExceptionRequestScope(target: ExceptionRequestScopeTarget): ExceptionRequestScopeDisplay {
  // A NO_EDR row is stored as ALL_VULNS × ASSET, so the scope switch below would render it as
  // a bare "1 asset" — indistinguishable from a request to waive every finding on that asset.
  // Short-circuit so the badge says what the row actually is.
  if (target.kind === 'NO_EDR') {
    const assetTarget = target.assetName ?? (target.assetId ? `asset #${target.assetId}` : 'single asset');
    return {
      label: 'No EDR',
      title: `No EDR possible on ${assetTarget}`,
      iconClass: 'bi-shield-slash',
      badgeClass: 'bg-dark'
    };
  }

  switch (target.scope) {
    case 'ASSET': {
      const assetTarget = target.assetName ?? (target.assetId ? `asset #${target.assetId}` : 'single asset');
      return {
        label: '1 asset',
        title: assetTarget,
        iconClass: 'bi-bullseye',
        badgeClass: 'bg-info text-dark'
      };
    }
    case 'IP':
      return {
        label: 'IP scope',
        title: target.scopeValue ?? 'IP scope',
        iconClass: 'bi-hdd-network',
        badgeClass: 'bg-secondary'
      };
    case 'AWS_ACCOUNT':
      return {
        label: 'AWS account',
        title: target.scopeValue ?? 'AWS account scope',
        iconClass: 'bi-cloud',
        badgeClass: 'bg-primary'
      };
    case 'OS':
      return {
        label: 'OS scope',
        title: target.scopeValue ? `OS: ${target.scopeValue}` : 'OS scope',
        iconClass: 'bi-pc-display',
        badgeClass: 'bg-secondary'
      };
    case 'GLOBAL':
      return {
        label: 'All assets',
        title: 'All assets',
        iconClass: 'bi-grid-3x3',
        badgeClass: 'bg-warning text-dark'
      };
  }
}
