import assert from 'node:assert/strict';
import test from 'node:test';

import { buildPublicStandardUrl, buildRequirementDownloadUrl } from './requirementDownloadUrl';

test('an unscoped download keeps the bare export path', () => {
  assert.equal(buildRequirementDownloadUrl({ format: 'docx' }), '/api/requirements/export/docx');
  assert.equal(buildRequirementDownloadUrl({ format: 'xlsx' }), '/api/requirements/export/xlsx');
});

test('a release is a query parameter, a use case is a path segment', () => {
  assert.equal(
    buildRequirementDownloadUrl({ format: 'docx', releaseId: 42 }),
    '/api/requirements/export/docx?releaseId=42',
  );
  assert.equal(
    buildRequirementDownloadUrl({ format: 'xlsx', useCaseId: 7, releaseId: 42 }),
    '/api/requirements/export/xlsx/usecase/7?releaseId=42',
  );
});

test('a standard scopes by query parameter, with or without a release', () => {
  assert.equal(
    buildRequirementDownloadUrl({ format: 'docx', standardId: 1 }),
    '/api/requirements/export/docx?standardId=1',
  );
  assert.equal(
    buildRequirementDownloadUrl({ format: 'docx', standardId: 1, releaseId: 42 }),
    '/api/requirements/export/docx?standardId=1&releaseId=42',
  );
});

test('use case wins over standard so the two filters never combine silently', () => {
  // The backend has no combined route; sending both would quietly drop one.
  assert.equal(
    buildRequirementDownloadUrl({ format: 'docx', useCaseId: 7, standardId: 1 }),
    '/api/requirements/export/docx/usecase/7',
  );
});

test('release id 0 and standard id 0 are still sent — only null means "unset"', () => {
  // A falsy-check here would silently drop a legitimate id.
  assert.equal(
    buildRequirementDownloadUrl({ format: 'docx', standardId: 0, releaseId: 0 }),
    '/api/requirements/export/docx?standardId=0&releaseId=0',
  );
});

test('the public standard URL encodes the slash in a name like IT/OT Security', () => {
  assert.equal(
    buildPublicStandardUrl('https://secman.example.net', 'IT/OT Security', 'docx', 'latest'),
    'https://secman.example.net/api/requirements/export/docx?standard=IT%2FOT+Security&release=latest',
  );
});

test('a null release omits the parameter rather than defaulting to latest', () => {
  // "live requirements" and "the ACTIVE release" are different answers; the link says which.
  assert.equal(
    buildPublicStandardUrl('https://secman.example.net', 'IT/OT Security', 'docx', null),
    'https://secman.example.net/api/requirements/export/docx?standard=IT%2FOT+Security',
  );
});

test('the public standard URL pins an explicit release version when given', () => {
  assert.equal(
    buildPublicStandardUrl('https://secman.example.net', 'IT/OT Security', 'xlsx', '98.739714.0'),
    'https://secman.example.net/api/requirements/export/xlsx?standard=IT%2FOT+Security&release=98.739714.0',
  );
});
