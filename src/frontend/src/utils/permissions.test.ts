import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canAccessCompareReleases,
  canAccessNormManagement,
  canAccessReleases,
  canAccessStandardManagement,
  canAccessUseCaseManagement,
  canCreateRelease,
  canDeleteRelease,
  canUpdateReleaseStatus,
  canViewReleases,
  getPermissionErrorMessage,
  hasClassificationAccess,
  hasReqAccess,
  hasRiskAccess,
  isAdmin,
  isChampion,
  isReleaseManager,
  isReq,
  isReqAdmin,
  isRisk,
  isSecChampion,
} from './permissions.ts';

// permissions.ts is the UI half of Hard Principle #2 — RBAC enforced at the
// controller AND in the UI — and had no tests at all. The controller is the real
// boundary, so a bug here does not grant access; it either hides a menu from
// someone entitled to it, or shows a control that then 403s. Both read as broken.

const ROLE_PREDICATES: Array<[string, (roles: string[] | undefined) => boolean, string]> = [
  ['ADMIN', isAdmin, 'isAdmin'],
  ['RELEASE_MANAGER', isReleaseManager, 'isReleaseManager'],
  ['REQADMIN', isReqAdmin, 'isReqAdmin'],
  ['CHAMPION', isChampion, 'isChampion'],
  ['SECCHAMPION', isSecChampion, 'isSecChampion'],
  ['REQ', isReq, 'isReq'],
  ['RISK', isRisk, 'isRisk'],
];

test('every role predicate matches its own role and no other', () => {
  const allRoles = ROLE_PREDICATES.map(([role]) => role);

  for (const [role, predicate, name] of ROLE_PREDICATES) {
    assert.equal(predicate([role]), true, `${name} should accept ${role}`);

    for (const other of allRoles.filter(r => r !== role)) {
      assert.equal(predicate([other]), false, `${name} should reject ${other}`);
    }
  }
});

test('role matching is exact — not case-insensitive and not substring', () => {
  // The backend compares with authentication.roles.contains("ADMIN"), which is
  // exact. A looser check here would show admin-only controls to a user whose
  // role merely contains the word.
  assert.equal(isAdmin(['admin']), false);
  assert.equal(isAdmin(['Admin']), false);
  assert.equal(isAdmin(['ADMINISTRATOR']), false);
  assert.equal(isAdmin(['NOT_ADMIN']), false);
  assert.equal(isSecChampion(['SEC_CHAMPION']), false);
});

test('deprecated CHAMPION and current SECCHAMPION are not interchangeable', () => {
  // CHAMPION was renamed to SECCHAMPION; treating them as aliases is exactly the
  // regression the rename left behind.
  assert.equal(isSecChampion(['CHAMPION']), false);
  assert.equal(isChampion(['SECCHAMPION']), false);
});

test('missing, null or non-array roles never grant anything', () => {
  const badInputs: Array<string[] | undefined> = [
    undefined,
    null as unknown as string[],
    'ADMIN' as unknown as string[],
    {} as unknown as string[],
  ];

  for (const roles of badInputs) {
    for (const [, predicate, name] of ROLE_PREDICATES) {
      assert.equal(predicate(roles), false, `${name} with ${JSON.stringify(roles)}`);
    }
    assert.equal(hasRiskAccess(roles), false);
    assert.equal(hasReqAccess(roles), false);
    assert.equal(hasClassificationAccess(roles), false);
    assert.equal(canCreateRelease(roles), false);
  }
});

test('a user holding several roles satisfies each of them', () => {
  const roles = ['USER', 'REQ', 'SECCHAMPION'];

  assert.equal(isReq(roles), true);
  assert.equal(isSecChampion(roles), true);
  assert.equal(isAdmin(roles), false);
  assert.equal(hasReqAccess(roles), true);
});

test('area access sets match the documented role lists', () => {
  // Risk Management: ADMIN, RISK, SECCHAMPION.
  assert.equal(hasRiskAccess(['ADMIN']), true);
  assert.equal(hasRiskAccess(['RISK']), true);
  assert.equal(hasRiskAccess(['SECCHAMPION']), true);
  assert.equal(hasRiskAccess(['REQ']), false);
  assert.equal(hasRiskAccess(['USER']), false);

  // Requirements: ADMIN, REQ, SECCHAMPION.
  assert.equal(hasReqAccess(['ADMIN']), true);
  assert.equal(hasReqAccess(['REQ']), true);
  assert.equal(hasReqAccess(['SECCHAMPION']), true);
  assert.equal(hasReqAccess(['RISK']), false);

  // Classification: ADMIN, SECCHAMPION only.
  assert.equal(hasClassificationAccess(['ADMIN']), true);
  assert.equal(hasClassificationAccess(['SECCHAMPION']), true);
  assert.equal(hasClassificationAccess(['REQ']), false);

  // Norm / Standard / UseCase management: ADMIN, SECCHAMPION, REQ.
  for (const canAccess of [canAccessNormManagement, canAccessStandardManagement, canAccessUseCaseManagement]) {
    assert.equal(canAccess(['ADMIN']), true);
    assert.equal(canAccess(['SECCHAMPION']), true);
    assert.equal(canAccess(['REQ']), true);
    assert.equal(canAccess(['RISK']), false);
    assert.equal(canAccess(['USER']), false);
  }
});

test('release browsing follows requirements access', () => {
  for (const roles of [['ADMIN'], ['REQ'], ['SECCHAMPION']]) {
    assert.equal(canAccessReleases(roles), true);
    assert.equal(canAccessCompareReleases(roles), true);
  }
  assert.equal(canAccessReleases(['USER']), false);
  assert.equal(canAccessCompareReleases(['USER']), false);

  // canViewReleases is deliberately unconditional: any authenticated user may
  // view. It ignores its argument, which is easy to mistake for a bug later.
  assert.equal(canViewReleases(['USER']), true);
  assert.equal(canViewReleases(undefined), true);
});

test('only ADMIN and REQADMIN can create releases', () => {
  assert.equal(canCreateRelease(['ADMIN']), true);
  assert.equal(canCreateRelease(['REQADMIN']), true);
  assert.equal(canCreateRelease(['RELEASE_MANAGER']), false);
  assert.equal(canCreateRelease(['REQ']), false);
  assert.equal(canCreateRelease(['USER']), false);
});

const owner = { username: 'alice' };
const other = { username: 'bob' };
const aliceRelease = { id: 1, version: '1.0.0', createdBy: 'alice', status: 'PREPARATION' };

test('an ACTIVE release cannot be deleted by anyone, including ADMIN', () => {
  const active = { ...aliceRelease, status: 'ACTIVE' };

  assert.equal(canDeleteRelease(active, owner, ['ADMIN']), false);
  assert.equal(canDeleteRelease(active, owner, ['REQADMIN']), false);
});

test('ADMIN deletes any non-ACTIVE release; REQADMIN only its own', () => {
  for (const status of ['PREPARATION', 'ALIGNMENT', 'ARCHIVED']) {
    const release = { ...aliceRelease, status };
    assert.equal(canDeleteRelease(release, other, ['ADMIN']), true, `ADMIN / ${status}`);
    assert.equal(canDeleteRelease(release, owner, ['REQADMIN']), true, `owning REQADMIN / ${status}`);
    assert.equal(canDeleteRelease(release, other, ['REQADMIN']), false, `non-owning REQADMIN / ${status}`);
  }
});

test('RELEASE_MANAGER and plain users cannot delete releases', () => {
  assert.equal(canDeleteRelease(aliceRelease, owner, ['RELEASE_MANAGER']), false);
  assert.equal(canDeleteRelease(aliceRelease, owner, ['USER']), false);
  assert.equal(canDeleteRelease(aliceRelease, owner, []), false);
});

test('status updates: ADMIN anything, RELEASE_MANAGER only its own, REQADMIN none', () => {
  assert.equal(canUpdateReleaseStatus(aliceRelease, other, ['ADMIN']), true);
  assert.equal(canUpdateReleaseStatus(aliceRelease, owner, ['RELEASE_MANAGER']), true);
  assert.equal(canUpdateReleaseStatus(aliceRelease, other, ['RELEASE_MANAGER']), false);

  // Deliberate asymmetry with canCreateRelease, which does allow REQADMIN.
  // Pinned so the difference is a decision on record rather than an oversight.
  assert.equal(canUpdateReleaseStatus(aliceRelease, owner, ['REQADMIN']), false);

  // Unlike deletion, an ACTIVE release may still have its status changed —
  // that is how a release moves on to ARCHIVED.
  assert.equal(canUpdateReleaseStatus({ ...aliceRelease, status: 'ACTIVE' }, other, ['ADMIN']), true);
});

test('a missing release or user denies both delete and status update', () => {
  assert.equal(canDeleteRelease(null, owner, ['ADMIN']), false);
  assert.equal(canDeleteRelease(undefined, owner, ['ADMIN']), false);
  assert.equal(canDeleteRelease(aliceRelease, null, ['ADMIN']), false);
  assert.equal(canUpdateReleaseStatus(null, owner, ['ADMIN']), false);
  assert.equal(canUpdateReleaseStatus(aliceRelease, undefined, ['ADMIN']), false);
});

test('ownership comparison is exact, so a lookalike username is not the owner', () => {
  assert.equal(canDeleteRelease(aliceRelease, { username: 'Alice' }, ['REQADMIN']), false);
  assert.equal(canDeleteRelease(aliceRelease, { username: 'alice2' }, ['REQADMIN']), false);

  // A release with no recorded creator must not match a user with no username.
  const orphan = { id: 2, version: '2.0.0', status: 'PREPARATION' };
  assert.equal(canDeleteRelease(orphan, owner, ['REQADMIN']), false);
});

test('permission error messages are specific per action with a safe fallback', () => {
  assert.match(getPermissionErrorMessage('delete'), /delete this release/);
  assert.match(getPermissionErrorMessage('create'), /create releases/);
  assert.match(getPermissionErrorMessage('update'), /update this release/);
  assert.match(getPermissionErrorMessage('publish'), /publish this release/);
  assert.match(getPermissionErrorMessage('archive'), /archive this release/);
  assert.equal(
    getPermissionErrorMessage('somethingElse'),
    'You do not have permission to perform this action.'
  );

  // A prototype key must not leak Object.prototype internals into the UI.
  assert.equal(
    getPermissionErrorMessage('constructor'),
    'You do not have permission to perform this action.'
  );
});
