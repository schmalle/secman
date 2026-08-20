import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildVulnerabilityExportFilters,
  countActiveExportFilters,
  describeExportScope,
  type CurrentVulnerabilitiesFilterState,
} from './currentVulnerabilitiesExportFilters';

const empty: CurrentVulnerabilitiesFilterState = {
  severityFilter: '',
  systemFilter: '',
  exceptionFilter: 'not_excepted',
  productFilter: '',
  cveFilter: '',
  adDomainFilter: '',
  cloudAccountIdFilter: '',
  includeInstallerFindings: false,
};

test('the AD domain reaches the export payload', () => {
  const filters = buildVulnerabilityExportFilters({
    ...empty,
    adDomainFilter: 'ad.glpoly.net',
  });

  assert.equal(filters.adDomain, 'ad.glpoly.net');
});

test('every filter maps to its request field', () => {
  const filters = buildVulnerabilityExportFilters({
    severityFilter: 'Critical',
    systemFilter: 'THMTPPI1',
    exceptionFilter: 'overdue',
    productFilter: 'Edge (Chromium-Based)',
    cveFilter: 'CVE-2026-19557',
    adDomainFilter: 'ad.glpoly.net',
    cloudAccountIdFilter: '123456789012',
    includeInstallerFindings: true,
  });

  assert.deepEqual(filters, {
    severity: 'Critical',
    system: 'THMTPPI1',
    exceptionStatus: 'overdue',
    product: 'Edge (Chromium-Based)',
    cve: 'CVE-2026-19557',
    adDomain: 'ad.glpoly.net',
    cloudAccountId: '123456789012',
    includeInstallerFindings: true,
  });
});

test('blank and whitespace-only filters are dropped, never sent as empty strings', () => {
  const filters = buildVulnerabilityExportFilters({
    ...empty,
    severityFilter: '   ',
    systemFilter: '',
    adDomainFilter: '\t',
  });

  assert.equal(filters.severity, undefined);
  assert.equal(filters.system, undefined);
  assert.equal(filters.adDomain, undefined);
  // JSON.stringify drops undefined keys, so the backend sees them as absent -> null.
  assert.equal(JSON.parse(JSON.stringify(filters)).adDomain, undefined);
});

test('filter values are trimmed', () => {
  const filters = buildVulnerabilityExportFilters({
    ...empty,
    adDomainFilter: '  ad.glpoly.net  ',
  });

  assert.equal(filters.adDomain, 'ad.glpoly.net');
});

test('includeInstallerFindings is carried through in both states', () => {
  assert.equal(
    buildVulnerabilityExportFilters({ ...empty, includeInstallerFindings: true })
      .includeInstallerFindings,
    true,
  );
  assert.equal(
    buildVulnerabilityExportFilters(empty).includeInstallerFindings,
    false,
  );
});

test('the default exceptionStatus is still sent so the export matches the default table view', () => {
  assert.equal(buildVulnerabilityExportFilters(empty).exceptionStatus, 'not_excepted');
});

test('active filter count ignores the always-present exceptionStatus', () => {
  assert.equal(countActiveExportFilters(buildVulnerabilityExportFilters(empty)), 0);
  assert.equal(
    countActiveExportFilters(
      buildVulnerabilityExportFilters({ ...empty, adDomainFilter: 'ad.glpoly.net' }),
    ),
    1,
  );
  assert.equal(
    countActiveExportFilters(
      buildVulnerabilityExportFilters({
        ...empty,
        adDomainFilter: 'ad.glpoly.net',
        severityFilter: 'High',
        cveFilter: 'CVE-2026-19557',
      }),
    ),
    3,
  );
});

test('the export button no longer claims to export everything when a filter is set', () => {
  assert.equal(
    describeExportScope(buildVulnerabilityExportFilters(empty)),
    'Export all vulnerabilities to Excel',
  );
  assert.equal(
    describeExportScope(
      buildVulnerabilityExportFilters({ ...empty, adDomainFilter: 'ad.glpoly.net' }),
    ),
    'Export to Excel using the 1 active filter',
  );
  assert.match(
    describeExportScope(
      buildVulnerabilityExportFilters({
        ...empty,
        adDomainFilter: 'ad.glpoly.net',
        severityFilter: 'High',
      }),
    ),
    /2 active filters/,
  );
});
