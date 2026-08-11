import assert from 'node:assert/strict';
import test from 'node:test';
import {
  MAX_TEMPLATE_BYTES,
  SUPPORTED_PLACEHOLDERS,
  describeValidationReport,
  formatFileSize,
  isUsable,
  shortSha256,
  sortTemplates,
  statusBadgeClass,
  unsupportedPlaceholders,
  validateTemplateFile,
  type RequirementExportTemplateSummary,
} from './requirementExportTemplates';

/**
 * These mirror `RequirementExportTemplateValidationService` on the backend, which is the real
 * control. The tests exist so the two cannot drift silently: an admin whose template will be
 * rejected should be told why in the form rather than getting an opaque 400, and the placeholder
 * list shown in the UI must stay in step with the set the backend actually substitutes.
 */

/** Minimal File stand-in: the validator only reads `name` and `size`. */
function fakeFile(name: string, size: number): File {
  return { name, size } as File;
}

test('validateTemplateFile accepts a plausible .docx', () => {
  assert.equal(validateTemplateFile(fakeFile('company-template.docx', 45_000)), null);
});

test('validateTemplateFile accepts an uppercase extension', () => {
  assert.equal(validateTemplateFile(fakeFile('COMPANY.DOCX', 1024)), null);
});

test('validateTemplateFile rejects everything that is not a .docx', () => {
  const rejected: Array<[File | null, string]> = [
    [null, 'nothing selected'],
    [fakeFile('template.docm', 1024), 'macro-enabled'],
    [fakeFile('template.dotm', 1024), 'macro-enabled template'],
    [fakeFile('template.doc', 1024), 'legacy binary Word'],
    [fakeFile('template.pdf', 1024), 'wrong format entirely'],
    [fakeFile('template.docx.exe', 1024), 'double extension'],
    [fakeFile('template.docx', 0), 'empty file'],
    [fakeFile('template.docx', MAX_TEMPLATE_BYTES + 1), 'over the size cap'],
  ];

  for (const [file, label] of rejected) {
    assert.notEqual(validateTemplateFile(file), null, `expected rejection: ${label}`);
  }
});

test('validateTemplateFile accepts a file exactly on the size cap', () => {
  assert.equal(validateTemplateFile(fakeFile('template.docx', MAX_TEMPLATE_BYTES)), null);
});

test('the advertised placeholder list has no duplicates and describes every entry', () => {
  const names = SUPPORTED_PLACEHOLDERS.map((p) => p.name);
  assert.equal(new Set(names).size, names.length, 'duplicate placeholder advertised');
  for (const placeholder of SUPPORTED_PLACEHOLDERS) {
    assert.ok(placeholder.description.length > 0, `${placeholder.name} has no description`);
  }
});

test('the placeholder list covers the release metadata the cover page needs', () => {
  const names = new Set(SUPPORTED_PLACEHOLDERS.map((p) => p.name));
  for (const required of ['requirements', 'releaseName', 'releaseVersion', 'releaseDate', 'releaseStatus']) {
    assert.ok(names.has(required), `missing placeholder: ${required}`);
  }
});

test('unsupportedPlaceholders flags only names the backend does not substitute', () => {
  assert.deepEqual(
    unsupportedPlaceholders(['releaseName', 'companyLogo', 'requirements', 'authorName']),
    ['authorName', 'companyLogo'],
  );
});

test('unsupportedPlaceholders returns nothing for an all-supported template', () => {
  assert.deepEqual(unsupportedPlaceholders(SUPPORTED_PLACEHOLDERS.map((p) => p.name)), []);
});

test('describeValidationReport passes through a rejection body', () => {
  const described = describeValidationReport({
    valid: false,
    errors: ['Macro-enabled Word templates are not allowed.'],
    warnings: [],
    placeholders: ['requirements'],
    sha256: 'abc',
    fileSizeBytes: 10,
  });
  assert.equal(described.valid, false);
  assert.deepEqual(described.errors, ['Macro-enabled Word templates are not allowed.']);
  assert.deepEqual(described.placeholders, ['requirements']);
});

test('describeValidationReport tolerates a report with omitted arrays', () => {
  const described = describeValidationReport({ valid: true, sha256: 'abc', fileSizeBytes: 10 });
  assert.equal(described.valid, true);
  assert.deepEqual(described.errors, []);
  assert.deepEqual(described.warnings, []);
  assert.deepEqual(described.placeholders, []);
});

test('describeValidationReport treats a missing report as invalid', () => {
  const described = describeValidationReport(null);
  assert.equal(described.valid, false);
  assert.equal(described.errors.length, 1);
});

test('formatFileSize scales across the unit boundaries', () => {
  assert.equal(formatFileSize(0), '0 B');
  assert.equal(formatFileSize(512), '512 B');
  assert.equal(formatFileSize(1024), '1.0 KB');
  assert.equal(formatFileSize(1024 * 1024), '1.0 MB');
  assert.equal(formatFileSize(MAX_TEMPLATE_BYTES), '5.0 MB');
  assert.equal(formatFileSize(-1), '-');
  assert.equal(formatFileSize(Number.NaN), '-');
});

test('shortSha256 truncates for display and survives a missing digest', () => {
  assert.equal(shortSha256('0123456789abcdef0123456789abcdef'), '0123456789ab');
  assert.equal(shortSha256(null), '-');
  assert.equal(shortSha256(undefined), '-');
});

test('only ACTIVE templates are usable for an export', () => {
  assert.equal(isUsable('ACTIVE'), true);
  assert.equal(isUsable('INACTIVE'), false);
  assert.equal(isUsable('RETIRED'), false);
  assert.equal(isUsable('REJECTED'), false);
});

test('statusBadgeClass distinguishes every status', () => {
  const classes = (['ACTIVE', 'INACTIVE', 'RETIRED', 'REJECTED'] as const).map(statusBadgeClass);
  assert.equal(new Set(classes).size, 4, 'two statuses render identically');
});

test('sortTemplates puts active templates first, then newest first', () => {
  const template = (
    id: number,
    status: RequirementExportTemplateSummary['status'],
    createdAt: string,
  ): RequirementExportTemplateSummary => ({
    id,
    name: `t${id}`,
    description: null,
    versionLabel: null,
    status,
    originalFilename: 't.docx',
    fileSizeBytes: 1,
    sha256: 'x',
    uploadedBy: 'admin',
    createdAt,
    activatedAt: null,
    deactivatedAt: null,
    lastUsedAt: null,
  });

  const sorted = sortTemplates([
    template(1, 'INACTIVE', '2026-01-01T00:00:00Z'),
    template(2, 'ACTIVE', '2026-02-01T00:00:00Z'),
    template(3, 'RETIRED', '2026-03-01T00:00:00Z'),
    template(4, 'ACTIVE', '2026-04-01T00:00:00Z'),
  ]);

  assert.deepEqual(sorted.map((t) => t.id), [4, 2, 3, 1]);
});

test('sortTemplates does not mutate its input', () => {
  const input: RequirementExportTemplateSummary[] = [];
  const output = sortTemplates(input);
  assert.notEqual(output, input);
});
