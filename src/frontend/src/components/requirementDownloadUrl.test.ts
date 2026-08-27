import assert from 'node:assert/strict';
import test from 'node:test';

import { buildAuthenticatedRequirementExportUrl, buildPublicStandardUrl, buildRequirementDownloadUrl } from './requirementDownloadUrl';

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

test('authenticated export: plain endpoint with no scope', () => {
  assert.equal(
    buildAuthenticatedRequirementExportUrl({ format: 'docx' }),
    '/api/requirements/export/docx',
  );
});

test('authenticated export: use case rides as a path segment', () => {
  assert.equal(
    buildAuthenticatedRequirementExportUrl({ format: 'xlsx', useCaseId: 7 }),
    '/api/requirements/export/xlsx/usecase/7',
  );
});

test('authenticated export: translated variant appends the language segment', () => {
  assert.equal(
    buildAuthenticatedRequirementExportUrl({ format: 'docx', translationLanguage: 'german' }),
    '/api/requirements/export/docx/translated/german',
  );
});

test('authenticated export: use case + translation + release compose', () => {
  assert.equal(
    buildAuthenticatedRequirementExportUrl({
      format: 'docx', useCaseId: 3, translationLanguage: 'french', releaseId: 12,
    }),
    '/api/requirements/export/docx/usecase/3/translated/french?releaseId=12',
  );
});

test('authenticated export: releaseId is carried on the use-case route too', () => {
  // The Import/Export screen had drifted and dropped releaseId here — pinned so it cannot again.
  assert.equal(
    buildAuthenticatedRequirementExportUrl({ format: 'xlsx', useCaseId: 5, releaseId: 2 }),
    '/api/requirements/export/xlsx/usecase/5?releaseId=2',
  );
});
