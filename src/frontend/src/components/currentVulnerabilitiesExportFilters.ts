/**
 * Builds the filter payload for POST /api/vulnerabilities/export from the
 * CurrentVulnerabilitiesTable filter state.
 *
 * Lives in its own .ts module (not inside the .tsx) so the node:test unit tier can import it —
 * Node's TypeScript stripping cannot parse JSX.
 *
 * Why it exists: the export used to send no filters at all, so selecting an AD domain narrowed
 * the on-screen table but the downloaded workbook still contained every accessible row.
 */
import type { VulnerabilityExportFilters } from "../services/vulnerabilityManagementService";

export interface CurrentVulnerabilitiesFilterState {
  severityFilter: string;
  /** Use the DEBOUNCED value: the export must match the rows currently rendered. */
  systemFilter: string;
  exceptionFilter: string;
  productFilter: string;
  /** Use the DEBOUNCED value: the export must match the rows currently rendered. */
  cveFilter: string;
  adDomainFilter: string;
  cloudAccountIdFilter: string;
  includeInstallerFindings: boolean;
}

/** Blank/whitespace-only input means "no filter", never an empty LIKE pattern. */
function clean(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

export function buildVulnerabilityExportFilters(
  state: CurrentVulnerabilitiesFilterState,
): VulnerabilityExportFilters {
  return {
    severity: clean(state.severityFilter),
    system: clean(state.systemFilter),
    exceptionStatus: clean(state.exceptionFilter),
    product: clean(state.productFilter),
    cve: clean(state.cveFilter),
    adDomain: clean(state.adDomainFilter),
    cloudAccountId: clean(state.cloudAccountIdFilter),
    includeInstallerFindings: state.includeInstallerFindings,
  };
}

/**
 * How many row filters are actually narrowing the export. Drives the Export button's tooltip
 * so it no longer claims to export "all vulnerabilities" when it does not.
 *
 * exceptionStatus is excluded on purpose: it always carries a value ("not_excepted" by default),
 * so counting it would report a filter the user never chose.
 */
export function countActiveExportFilters(
  filters: VulnerabilityExportFilters,
): number {
  const narrowing = [
    filters.severity,
    filters.system,
    filters.product,
    filters.cve,
    filters.adDomain,
    filters.cloudAccountId,
  ];
  return narrowing.filter((value) => value !== undefined).length;
}

/** Tooltip/label text for the Export button, given the filters it would send. */
export function describeExportScope(
  filters: VulnerabilityExportFilters,
): string {
  const count = countActiveExportFilters(filters);
  if (count === 0) {
    return "Export all vulnerabilities to Excel";
  }
  return count === 1
    ? "Export to Excel using the 1 active filter"
    : `Export to Excel using the ${count} active filters`;
}
