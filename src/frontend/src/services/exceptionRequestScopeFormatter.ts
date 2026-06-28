import type { ExceptionScope } from './vulnerabilityManagementService';

export interface ExceptionRequestScopeTarget {
  scope: ExceptionScope;
  scopeValue?: string | null;
  assetId?: number | null;
  assetName?: string | null;
}

export interface ExceptionRequestScopeDisplay {
  label: string;
  title: string;
  iconClass: string;
  badgeClass: string;
}

export function formatExceptionRequestScope(target: ExceptionRequestScopeTarget): ExceptionRequestScopeDisplay {
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
